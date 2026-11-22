package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Android 15-compliant Foreground Service (`foregroundServiceType="location"`) keeping offline
 * turn-by-turn GPS navigation and voice prompts active in the background.
 *
 * Lifecycle guarantees (preventing `ForegroundServiceDidNotStartInTimeException`):
 * - `startForegroundService()` is called ONLY once when navigation starts (never on GPS ticks).
 * - `onStartCommand()` ALWAYS calls `ServiceCompat.startForeground()` as its very first step for
 *   every start request (including null intents, stop actions, or revoked permissions).
 * - In-navigation updates refresh the notification directly via `NotificationManagerCompat.notify()`
 *   on the same `NOTIFICATION_ID`, throttled to at most once every 2 seconds and only when text
 *   changed.
 * - Stopping navigation uses `context.stopService()`, and internal shutdowns strictly use
 *   `stopSelf(startId)` (never parameterless `stopSelf()`).
 * - Always returns `START_NOT_STICKY`.
 */
class NavigationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)

        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: lastNotifiedTitle.ifBlank { getString(R.string.app_name) }
        val body = intent?.getStringExtra(EXTRA_BODY) ?: lastNotifiedBody
        val notification = buildNotification(this, title, body)
        val hasPerm = hasLocationPermission(this)

        // CRITICAL: Always satisfy the startForegroundService() contract as the very first step
        // before any branching, permission exit, null-intent exit, or stop handling.
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasPerm) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (_: SecurityException) {
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
        }

        // Null intent (unexpected system restart) -> stop cleanly after satisfying startForeground()
        if (intent == null) {
            shouldRemainRunning = false
            isServiceCreatedAndRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Notification "Stop" action button -> notify UI and stop cleanly using startId
        if (intent.action == ACTION_STOP) {
            shouldRemainRunning = false
            isServiceCreatedAndRunning = false
            resetThrottleState()
            sendBroadcast(Intent(BROADCAST_NAVIGATION_STOPPED).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Permission revoked or stop() was called while this start request was queued
        if (!hasPerm || !shouldRemainRunning) {
            shouldRemainRunning = false
            isServiceCreatedAndRunning = false
            resetThrottleState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        isServiceCreatedAndRunning = true
        synchronized(stateLock) {
            lastNotifiedTitle = title
            lastNotifiedBody = body
            lastNotifyElapsedMs = SystemClock.elapsedRealtime()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isServiceCreatedAndRunning = false
        resetThrottleState()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "rahyab_navigation_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START_OR_UPDATE = "com.example.rahyab.ACTION_NAV_UPDATE"
        const val ACTION_STOP = "com.example.rahyab.ACTION_NAV_STOP"
        const val BROADCAST_NAVIGATION_STOPPED = "com.example.rahyab.NAV_STOPPED"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"

        const val MIN_UPDATE_INTERVAL_MS = 2_000L

        private val stateLock = Any()

        @Volatile
        private var shouldRemainRunning: Boolean = false

        @Volatile
        private var isServiceCreatedAndRunning: Boolean = false

        @Volatile
        internal var startForegroundInvocations: Int = 0
            private set

        private var lastNotifiedTitle: String = ""
        private var lastNotifiedBody: String = ""
        private var lastNotifyElapsedMs: Long = 0L

        fun isRunning(): Boolean = shouldRemainRunning && isServiceCreatedAndRunning

        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Starts the foreground service ONCE when navigation begins. If the service is already
         * running (e.g., route change or reroute), updates the existing notification directly
         * without calling `startForegroundService()` again.
         */
        fun startOrUpdate(context: Context, title: String, body: String) {
            if (!hasLocationPermission(context)) return

            if (shouldRemainRunning) {
                updateNotification(context, title, body, force = true)
                return
            }

            shouldRemainRunning = true
            synchronized(stateLock) {
                lastNotifiedTitle = title
                lastNotifiedBody = body
                lastNotifyElapsedMs = SystemClock.elapsedRealtime()
            }

            val intent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Updates the active navigation notification directly via `NotificationManagerCompat.notify()`
         * using the same `NOTIFICATION_ID` without starting or restarting the service.
         *
         * Throttled to at most once every 2 seconds (unless [force] is true for a route change)
         * and skipped whenever the title and body text have not changed.
         *
         * @return true if a notification update was posted, false if skipped/throttled.
         */
        @SuppressLint("MissingPermission")
        fun updateNotification(
            context: Context,
            title: String,
            body: String,
            force: Boolean = false,
            nowElapsedMs: Long = SystemClock.elapsedRealtime()
        ): Boolean {
            if (!shouldRemainRunning) return false

            synchronized(stateLock) {
                if (title == lastNotifiedTitle && body == lastNotifiedBody) {
                    return false
                }
                if (!force && (nowElapsedMs - lastNotifyElapsedMs) < MIN_UPDATE_INTERVAL_MS) {
                    return false
                }
                lastNotifiedTitle = title
                lastNotifiedBody = body
                lastNotifyElapsedMs = nowElapsedMs
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            return runCatching {
                ensureChannel(context)
                val notification = buildNotification(context, title, body)
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                true
            }.getOrDefault(false)
        }

        /**
         * Stops the foreground service cleanly using `context.stopService()` instead of sending
         * an `ACTION_STOP` intent via `startService()`.
         */
        fun stop(context: Context) {
            shouldRemainRunning = false
            isServiceCreatedAndRunning = false
            resetThrottleState()
            runCatching {
                context.stopService(Intent(context, NavigationForegroundService::class.java))
            }
            runCatching {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
        }

        internal fun resetForTesting() {
            shouldRemainRunning = false
            isServiceCreatedAndRunning = false
            startForegroundInvocations = 0
            resetThrottleState()
        }

        private fun resetThrottleState() {
            synchronized(stateLock) {
                lastNotifiedTitle = ""
                lastNotifiedBody = ""
                lastNotifyElapsedMs = 0L
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_nav_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_nav_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context, title: String, body: String): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(context, NavigationForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                101,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.notification_action_stop),
                    stopPendingIntent
                )
                .build()
        }
    }
}
