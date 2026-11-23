package com.example.data.packs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.RahYabApplication
import com.example.RahYabRoutes
import com.example.data.local.PackInstallStatus
import com.example.data.model.UsStatesCatalog
import com.example.i18n.AppLanguage
import com.example.i18n.stringsFor
import com.example.util.CrashLogManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job (every 7 days on a connected network) that fetches manifest.json,
 * compares installed state versions against the manifest, notifies the user on a low-importance
 * "Map updates" channel, and optionally triggers an unmetered-Wi-Fi + charging auto-download.
 */
class MapUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RahYabApplication ?: return Result.success()
        val settings = runCatching { app.preferencesRepository.settingsFlow.first() }.getOrNull()
            ?: return Result.success()

        if (!settings.onlineFeaturesEnabled || !settings.autoCheckMapUpdatesEnabled) {
            return Result.success()
        }
        val baseUrl = settings.effectivePackBaseUrl
        if (baseUrl.isBlank()) return Result.success()

        val nowMs = System.currentTimeMillis()
        val manifestResult = app.packManager.fetchManifest(baseUrl)
        return manifestResult.fold(
            onSuccess = {
                val installed = app.database.installedPackDao().getAllOnce()
                val outdated = installed.filter { it.status == PackInstallStatus.UPDATE_AVAILABLE.name }
                val summary = if (outdated.isEmpty()) {
                    "Up to date"
                } else {
                    val codes = outdated.joinToString(", ") { it.stateCode.uppercase() }
                    "${outdated.size} update(s) available ($codes)"
                }
                app.preferencesRepository.setLastMapUpdateCheck(nowMs, summary)

                if (outdated.isNotEmpty()) {
                    val names = outdated.map { pack ->
                        val info = UsStatesCatalog.byCode[pack.stateCode.lowercase()]
                        if (settings.language == AppLanguage.PERSIAN) {
                            info?.nameFa ?: pack.stateCode.uppercase()
                        } else {
                            info?.nameEn ?: pack.stateCode.uppercase()
                        }
                    }
                    postMapUpdateNotification(applicationContext, names, settings.language)

                    if (settings.autoUpdateMapsOnWifi) {
                        if (isOnUnmeteredWifiAndCharging(applicationContext)) {
                            app.packManager.updateAllOutdatedPacks(baseUrl)
                        } else {
                            enqueueAutoDownloadWhenUnmeteredCharging(applicationContext)
                        }
                    }
                }
                Result.success()
            },
            onFailure = { err ->
                val detail = "Failed: ${err.message.orEmpty().take(80)}"
                runCatching { app.preferencesRepository.setLastMapUpdateCheck(nowMs, detail) }
                Result.retry()
            }
        )
    }

    companion object {
        const val WORK_NAME_PERIODIC = "rahyab_weekly_map_updates"
        const val WORK_NAME_AUTO_DOWNLOAD = "rahyab_auto_download_map_updates"
        const val CHANNEL_ID_MAP_UPDATES = "rahyab_map_updates_low"
        const val NOTIFICATION_ID_MAP_UPDATES = 4102
        const val EXTRA_OPEN_TAB = "com.example.EXTRA_OPEN_TAB"

        fun schedulePeriodic(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<MapUpdateCheckWorker>(7, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }.onFailure { err ->
                CrashLogManager.logError(context, "MapUpdateWorker", "Failed to schedule periodic map check", err)
            }
        }

        fun enqueueAutoDownloadWhenUnmeteredCharging(context: Context) {
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build()
                val request = OneTimeWorkRequestBuilder<MapUpdateAutoDownloadWorker>()
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME_AUTO_DOWNLOAD,
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }.onFailure { err ->
                CrashLogManager.logError(context, "MapUpdateWorker", "Failed to enqueue auto-download worker", err)
            }
        }

        fun isOnUnmeteredWifiAndCharging(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val isUnmeteredWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (!isUnmeteredWifi) return false

            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        fun postMapUpdateNotification(
            context: Context,
            stateNames: List<String>,
            language: AppLanguage
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }
            runCatching {
                val str = stringsFor(language)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (nm != null && nm.getNotificationChannel(CHANNEL_ID_MAP_UPDATES) == null) {
                        val channel = NotificationChannel(
                            CHANNEL_ID_MAP_UPDATES,
                            str.mapUpdateChannelName,
                            NotificationManager.IMPORTANCE_LOW
                        )
                        nm.createNotificationChannel(channel)
                    }
                }
                val body = MapUpdateVersionHelper.formatUpdateNotificationBody(stateNames, language)
                val openMapsIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_OPEN_TAB, RahYabRoutes.ROUTE_MAPS)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID_MAP_UPDATES,
                    openMapsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID_MAP_UPDATES)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(str.mapUpdateChannelName)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pendingIntent)
                    .build()
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MAP_UPDATES, notification)
            }.onFailure { err ->
                CrashLogManager.logError(context, "MapUpdateWorker", "Failed to post map update notification", err)
            }
        }
    }
}

/**
 * One-time WorkManager job constrained to UNMETERED network + CHARGING that kicks off
 * smart map pack updates when the user opted into automatic Wi-Fi + charging updates.
 */
class MapUpdateAutoDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RahYabApplication ?: return Result.success()
        val settings = runCatching { app.preferencesRepository.settingsFlow.first() }.getOrNull()
            ?: return Result.success()
        if (!settings.onlineFeaturesEnabled || !settings.autoUpdateMapsOnWifi) {
            return Result.success()
        }
        val baseUrl = settings.effectivePackBaseUrl
        if (baseUrl.isBlank()) return Result.success()

        app.packManager.fetchManifest(baseUrl).onSuccess {
            app.packManager.updateAllOutdatedPacks(baseUrl)
        }
        return Result.success()
    }
}
