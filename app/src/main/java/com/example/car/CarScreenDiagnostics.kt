package com.example.car

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A4 & B4: Live Android Auto surface, camera, screen-stack, and template diagnostics snapshot.
 * Observed by the phone's Diagnostics screen ("Car screen" section) and included in "Copy all".
 */
data class CarScreenDiagnosticsSnapshot(
    val isConnected: Boolean = false,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val dpi: Int = 0,
    val pixelRatio: Float = 0f,
    val currentZoom: Double? = null,
    val currentTilt: Double? = null,
    val visibleArea: String = "—",
    val stableArea: String = "—",
    val carApiLevel: Int = 0,
    val topScreenClass: String = "—",
    val lastTemplateType: String = "—",
    val lastRoutingInfoHadStep: Boolean? = null,
    val lastTemplateError: String? = null
)

object CarScreenDiagnosticsStore {
    private val _state = MutableStateFlow(CarScreenDiagnosticsSnapshot())
    val state: StateFlow<CarScreenDiagnosticsSnapshot> = _state.asStateFlow()

    fun onSessionConnected(carApiLevel: Int) {
        _state.update { it.copy(isConnected = true, carApiLevel = carApiLevel) }
    }

    fun onSessionDisconnected() {
        _state.update {
            it.copy(
                isConnected = false,
                surfaceWidth = 0,
                surfaceHeight = 0,
                topScreenClass = "—"
            )
        }
    }

    fun onSurfaceAvailable(width: Int, height: Int, dpi: Int, pixelRatio: Float) {
        val rectStr = "0,0,${width}x${height}"
        _state.update {
            it.copy(
                isConnected = true,
                surfaceWidth = width,
                surfaceHeight = height,
                dpi = dpi,
                pixelRatio = pixelRatio,
                visibleArea = rectStr,
                stableArea = rectStr
            )
        }
    }

    fun onVisibleAreaChanged(rect: Rect) {
        _state.update { it.copy(visibleArea = formatRect(rect)) }
    }

    fun onStableAreaChanged(rect: Rect) {
        _state.update { it.copy(stableArea = formatRect(rect)) }
    }

    fun onCameraUpdated(zoom: Double, tilt: Double) {
        _state.update { it.copy(currentZoom = zoom, currentTilt = tilt) }
    }

    fun onTopScreenChanged(screenName: String) {
        _state.update { it.copy(topScreenClass = screenName) }
    }

    fun onTemplateBuilt(templateType: String, routingInfoHadStep: Boolean? = null) {
        _state.update {
            it.copy(
                lastTemplateType = templateType,
                lastRoutingInfoHadStep = routingInfoHadStep
            )
        }
    }

    fun onTemplateError(errorSummary: String) {
        _state.update { it.copy(lastTemplateError = errorSummary) }
    }

    fun resetForTest() {
        _state.value = CarScreenDiagnosticsSnapshot()
    }

    private fun formatRect(r: Rect): String = "${r.left},${r.top}-${r.right},${r.bottom} (${r.width()}x${r.height()})"
}
