package com.example.navigation

import com.example.data.search.OfflinePlaceSearchEngine
import kotlin.math.abs

/**
 * Single-clock synchronized pose for BOTH the vehicle marker (at its true map-matched road
 * position) AND the map camera (which may include bounded forward look-ahead framing).
 *
 * Driving marker and camera from the exact same interpolation parameter guarantees zero phase
 * lag, zero rubber-banding between marker and moving map, and coherent mid-animation retargeting.
 */
data class SynchronizedMapPose(
    val markerLat: Double,
    val markerLon: Double,
    val markerBearing: Float,
    val camLat: Double,
    val camLon: Double,
    val camZoom: Double,
    val camTilt: Double,
    val camBearing: Double,
    val padLeft: Double,
    val padTop: Double,
    val padRight: Double,
    val padBottom: Double,
    val updateCamera: Boolean
) {
    fun paddingArray(): DoubleArray = doubleArrayOf(padLeft, padTop, padRight, padBottom)
}

/**
 * Pure-Kotlin shared animation clock driving vehicle marker and map camera together on Phone
 * and Android Auto.
 *
 * Guarantees:
 * - Rejects stale out-of-order GPS fix timestamps so old positions never pull the marker back.
 * - Snaps/coalesces directly to the latest reliable fix after a GPS gap (>= 3 s or >= 120 m)
 *   without replaying a backlog of intermediate positions.
 * - Mid-animation retargets start from the exact sampled pose at `nowMs` (no jumps or overlapping
 *   animations).
 * - Uses linear pacing during continuous follow updates (constant velocity, zero ease-in-out lag)
 *   and smooth cubic ease-out for Turn Focus enter/tighten/exit or manual recenter transitions.
 * - Yields camera completely when the user pans/pinches while continuing to animate the marker.
 */
class SynchronizedFollowAnimator {

    enum class Easing {
        LINEAR,
        CUBIC_OUT
    }

    private var startPose: SynchronizedMapPose? = null
    private var targetPose: SynchronizedMapPose? = null
    private var startTimeMs: Long = 0L
    private var durationMs: Long = 0L
    private var easing: Easing = Easing.LINEAR
    private var lastAcceptedFixTimestampMs: Long = 0L
    private var isCameraFollowing: Boolean = true
    private var hasInitializedPose: Boolean = false

    val isAnimating: Boolean
        get() = startPose != null && targetPose != null && durationMs > 0L

    fun hasPose(): Boolean = hasInitializedPose

    fun reset() {
        startPose = null
        targetPose = null
        startTimeMs = 0L
        durationMs = 0L
        lastAcceptedFixTimestampMs = 0L
        hasInitializedPose = false
    }

    fun pauseCameraFollow() {
        isCameraFollowing = false
        val tgt = targetPose
        if (tgt != null && tgt.updateCamera) {
            targetPose = tgt.copy(updateCamera = false)
        }
        val st = startPose
        if (st != null && st.updateCamera) {
            startPose = st.copy(updateCamera = false)
        }
    }

    fun resumeCameraFollow() {
        isCameraFollowing = true
    }

    /**
     * Accepts a new navigation update (both true vehicle marker position and bounded camera target).
     * Returns true if accepted, false if rejected as a stale out-of-order fix.
     */
    fun onNavigationTargetUpdate(
        nowMs: Long,
        fixTimestampMs: Long,
        markerLat: Double,
        markerLon: Double,
        markerBearing: Float,
        camLat: Double,
        camLon: Double,
        camZoom: Double,
        camTilt: Double,
        camBearing: Double,
        padding: DoubleArray,
        durationMs: Int,
        isFollowingCamera: Boolean = true,
        forceSnap: Boolean = false,
        useEaseOut: Boolean = false
    ): Boolean {
        if (!forceSnap && fixTimestampMs > 0L && lastAcceptedFixTimestampMs > 0L &&
            fixTimestampMs < lastAcceptedFixTimestampMs
        ) {
            return false
        }

        val current = sample(nowMs)
        val gapTimeMs = if (fixTimestampMs > 0L && lastAcceptedFixTimestampMs > 0L) {
            fixTimestampMs - lastAcceptedFixTimestampMs
        } else 0L
        val jumpMeters = if (current != null) {
            OfflinePlaceSearchEngine.haversineMeters(current.markerLat, current.markerLon, markerLat, markerLon)
        } else 0.0
        val isGpsGapSnap = current != null && (gapTimeMs >= GPS_GAP_SNAP_MS || jumpMeters >= GPS_GAP_SNAP_METERS)

        if (fixTimestampMs > 0L) {
            lastAcceptedFixTimestampMs = fixTimestampMs
        }
        isCameraFollowing = isFollowingCamera

        val padL = padding.getOrElse(0) { 0.0 }
        val padT = padding.getOrElse(1) { 0.0 }
        val padR = padding.getOrElse(2) { 0.0 }
        val padB = padding.getOrElse(3) { 0.0 }

        val nextTarget = SynchronizedMapPose(
            markerLat = markerLat,
            markerLon = markerLon,
            markerBearing = CompassTracker.normalizeAngle(markerBearing),
            camLat = camLat,
            camLon = camLon,
            camZoom = camZoom,
            camTilt = camTilt,
            camBearing = CompassTracker.normalizeAngle(camBearing.toFloat()).toDouble(),
            padLeft = padL,
            padTop = padT,
            padRight = padR,
            padBottom = padB,
            updateCamera = isFollowingCamera
        )

        val effectiveSnap = forceSnap || isGpsGapSnap || current == null || durationMs <= 0
        if (effectiveSnap) {
            startPose = nextTarget
            targetPose = nextTarget
            startTimeMs = nowMs
            this.durationMs = 0L
            easing = Easing.LINEAR
            hasInitializedPose = true
            return true
        }

        val effectiveStart = if (isFollowingCamera && !current.updateCamera) {
            // Re-entering follow from manual pan: start camera smoothly from target/recenter transition
            current.copy(updateCamera = true)
        } else {
            current.copy(updateCamera = isFollowingCamera)
        }
        startPose = effectiveStart
        targetPose = nextTarget
        startTimeMs = nowMs
        this.durationMs = durationMs.toLong().coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)
        easing = if (useEaseOut) Easing.CUBIC_OUT else Easing.LINEAR
        hasInitializedPose = true
        return true
    }

    /**
     * Updates ONLY the vehicle marker while the user is manually panning/pinching the map.
     * Never modifies or commands the camera.
     */
    fun onMarkerOnlyUpdate(
        nowMs: Long,
        fixTimestampMs: Long,
        markerLat: Double,
        markerLon: Double,
        markerBearing: Float,
        durationMs: Int = 220,
        forceSnap: Boolean = false
    ): Boolean {
        if (!forceSnap && fixTimestampMs > 0L && lastAcceptedFixTimestampMs > 0L &&
            fixTimestampMs < lastAcceptedFixTimestampMs
        ) {
            return false
        }
        val current = sample(nowMs)
        val gapTimeMs = if (fixTimestampMs > 0L && lastAcceptedFixTimestampMs > 0L) {
            fixTimestampMs - lastAcceptedFixTimestampMs
        } else 0L
        val jumpMeters = if (current != null) {
            OfflinePlaceSearchEngine.haversineMeters(current.markerLat, current.markerLon, markerLat, markerLon)
        } else 0.0
        val isGpsGapSnap = current != null && (gapTimeMs >= GPS_GAP_SNAP_MS || jumpMeters >= GPS_GAP_SNAP_METERS)
        if (fixTimestampMs > 0L) lastAcceptedFixTimestampMs = fixTimestampMs

        val base = current ?: SynchronizedMapPose(
            markerLat = markerLat, markerLon = markerLon, markerBearing = CompassTracker.normalizeAngle(markerBearing),
            camLat = markerLat, camLon = markerLon, camZoom = 15.0, camTilt = 0.0, camBearing = markerBearing.toDouble(),
            padLeft = 0.0, padTop = 0.0, padRight = 0.0, padBottom = 0.0, updateCamera = false
        )
        val nextTarget = base.copy(
            markerLat = markerLat,
            markerLon = markerLon,
            markerBearing = CompassTracker.normalizeAngle(markerBearing),
            updateCamera = false
        )
        if (forceSnap || isGpsGapSnap || current == null || durationMs <= 0) {
            startPose = nextTarget
            targetPose = nextTarget
            startTimeMs = nowMs
            this.durationMs = 0L
        } else {
            startPose = base.copy(updateCamera = false)
            targetPose = nextTarget
            startTimeMs = nowMs
            this.durationMs = durationMs.toLong().coerceIn(MIN_ANIM_MS, 500L)
            easing = Easing.LINEAR
        }
        hasInitializedPose = true
        return true
    }

    /**
     * Samples the exact synchronized marker + camera pose at [nowMs].
     * Returns null only before the very first update.
     */
    fun sample(nowMs: Long): SynchronizedMapPose? {
        val from = startPose ?: return null
        val to = targetPose ?: return from
        if (durationMs <= 0L || nowMs >= startTimeMs + durationMs) {
            startPose = to
            durationMs = 0L
            return to
        }
        val rawT = ((nowMs - startTimeMs).toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val u = when (easing) {
            Easing.LINEAR -> rawT
            Easing.CUBIC_OUT -> {
                val inv = 1.0 - rawT
                1.0 - inv * inv * inv
            }
        }
        return SynchronizedMapPose(
            markerLat = lerp(from.markerLat, to.markerLat, u),
            markerLon = lerp(from.markerLon, to.markerLon, u),
            markerBearing = lerpAngleFloat(from.markerBearing, to.markerBearing, u.toFloat()),
            camLat = lerp(from.camLat, to.camLat, u),
            camLon = lerp(from.camLon, to.camLon, u),
            camZoom = lerp(from.camZoom, to.camZoom, u),
            camTilt = lerp(from.camTilt, to.camTilt, u),
            camBearing = lerpAngleDouble(from.camBearing, to.camBearing, u),
            padLeft = lerp(from.padLeft, to.padLeft, u),
            padTop = lerp(from.padTop, to.padTop, u),
            padRight = lerp(from.padRight, to.padRight, u),
            padBottom = lerp(from.padBottom, to.padBottom, u),
            updateCamera = isCameraFollowing && to.updateCamera
        )
    }

    fun isAnimationFinishedAt(nowMs: Long): Boolean =
        durationMs <= 0L || nowMs >= startTimeMs + durationMs

    companion object {
        const val GPS_GAP_SNAP_MS = 3_000L
        const val GPS_GAP_SNAP_METERS = 120.0
        private const val MIN_ANIM_MS = 80L
        private const val MAX_ANIM_MS = 1_500L

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

        private fun lerpAngleFloat(fromDeg: Float, toDeg: Float, t: Float): Float {
            val delta = CompassTracker.shortestAngleDelta(fromDeg, toDeg)
            return CompassTracker.normalizeAngle(fromDeg + delta * t)
        }

        private fun lerpAngleDouble(fromDeg: Double, toDeg: Double, t: Double): Double {
            var delta = (toDeg - fromDeg) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            val res = (fromDeg + delta * t) % 360.0
            return if (res < 0.0) res + 360.0 else res
        }

        fun needsCameraSnapOnRecenter(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double
        ): Boolean = OfflinePlaceSearchEngine.haversineMeters(fromLat, fromLon, toLat, toLon) >= 1_500.0 ||
            abs(fromLat) < 0.001
    }
}
