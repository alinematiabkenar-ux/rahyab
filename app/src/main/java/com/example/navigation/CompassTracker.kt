package com.example.navigation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Hardware compass tracker for stationary & low-speed heading (< 3 mph).
 *
 * - Uses `Sensor.TYPE_ROTATION_VECTOR` with automatic fallback to
 *   `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` if rotation vector is unavailable.
 * - Remaps axes for current screen rotation (`Surface.ROTATION_0/90/180/270`).
 * - Converts magnetic north to true north via `GeomagneticField` declination at the user's location.
 * - Applies wrap-around-safe circular smoothing so the map arrow never shakes.
 * - Automatically pauses sensors when the app is in the background and not navigating.
 */
class CompassTracker(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometerSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _headingDegrees = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = _headingDegrees.asStateFlow()

    private val _isLowAccuracy = MutableStateFlow(false)
    val isLowAccuracy: StateFlow<Boolean> = _isLowAccuracy.asStateFlow()

    private var isListening = false
    private var isAppInForeground = true
    private var isNavigating = false

    private var declinationDegrees: Float = 0f
    private var smoothedHeading: Float? = null

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravityReading = FloatArray(3)
    private val magneticReading = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false

    fun updateLocationForDeclination(lat: Double, lon: Double, altitudeMeters: Double = 0.0) {
        runCatching {
            val field = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                altitudeMeters.toFloat(),
                System.currentTimeMillis()
            )
            declinationDegrees = field.declination
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        if (isAppInForeground == inForeground) return
        isAppInForeground = inForeground
        syncListeningState()
    }

    fun setNavigationMode(active: Boolean) {
        if (isNavigating == active) return
        isNavigating = active
        syncListeningState()
    }

    fun startIfActive() {
        syncListeningState()
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        runCatching { sensorManager.unregisterListener(this) }
    }

    private fun syncListeningState() {
        val shouldListen = isAppInForeground || isNavigating
        if (shouldListen && !isListening) {
            startListening()
        } else if (!shouldListen && isListening) {
            stop()
        }
    }

    private fun startListening() {
        if (isListening) return
        var registered = false
        if (rotationVectorSensor != null) {
            registered = sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )
        } else if (accelerometerSensor != null && magnetometerSensor != null) {
            hasGravity = false
            hasMagnetic = false
            val regAcc = sensorManager.registerListener(
                this,
                accelerometerSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            val regMag = sensorManager.registerListener(
                this,
                magnetometerSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            registered = regAcc && regMag
        }
        isListening = registered
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                processRotationMatrix(rotationMatrix)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPassFilterVec(event.values, gravityReading, hasGravity)
                hasGravity = true
                if (hasMagnetic && SensorManager.getRotationMatrix(rotationMatrix, null, gravityReading, magneticReading)) {
                    processRotationMatrix(rotationMatrix)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPassFilterVec(event.values, magneticReading, hasMagnetic)
                hasMagnetic = true
                if (hasGravity && SensorManager.getRotationMatrix(rotationMatrix, null, gravityReading, magneticReading)) {
                    processRotationMatrix(rotationMatrix)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val type = sensor?.type ?: return
        if (type == Sensor.TYPE_ROTATION_VECTOR || type == Sensor.TYPE_MAGNETIC_FIELD) {
            _isLowAccuracy.value = (
                accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                )
        }
    }

    private fun processRotationMatrix(matrix: FloatArray) {
        val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        val (axisX, axisY) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }

        val targetMatrix = if (axisX == SensorManager.AXIS_X && axisY == SensorManager.AXIS_Y) {
            matrix
        } else {
            if (!SensorManager.remapCoordinateSystem(matrix, axisX, axisY, remappedMatrix)) {
                return
            }
            remappedMatrix
        }

        SensorManager.getOrientation(targetMatrix, orientationAngles)
        val magneticAzimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val trueNorthDeg = normalizeAngle(magneticAzimuthDeg + declinationDegrees)

        val current = smoothedHeading
        if (current == null) {
            smoothedHeading = trueNorthDeg
            _headingDegrees.value = trueNorthDeg
            return
        }

        val delta = shortestAngleDelta(current, trueNorthDeg)
        if (abs(delta) < DEADBAND_DEGREES) {
            return
        }
        val next = normalizeAngle(current + SMOOTHING_ALPHA * delta)
        smoothedHeading = next
        _headingDegrees.value = next
    }

    private fun lowPassFilterVec(input: FloatArray, output: FloatArray, initialized: Boolean) {
        if (!initialized) {
            output[0] = input[0]
            output[1] = input[1]
            output[2] = input[2]
            return
        }
        val alpha = 0.2f
        output[0] += alpha * (input[0] - output[0])
        output[1] += alpha * (input[1] - output[1])
        output[2] += alpha * (input[2] - output[2])
    }

    companion object {
        private const val SMOOTHING_ALPHA = 0.16f
        private const val DEADBAND_DEGREES = 0.8f

        fun normalizeAngle(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        fun shortestAngleDelta(fromDeg: Float, toDeg: Float): Float =
            ((toDeg - fromDeg + 540f) % 360f) - 180f

        fun lerpAngle(fromDeg: Float, toDeg: Float, fraction: Float): Float =
            normalizeAngle(fromDeg + shortestAngleDelta(fromDeg, toDeg) * fraction.coerceIn(0f, 1f))
    }
}
