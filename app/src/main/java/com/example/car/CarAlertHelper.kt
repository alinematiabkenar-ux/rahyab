package com.example.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import com.example.R
import com.example.data.online.WeatherAlertItem
import com.example.i18n.AppLanguage
import com.example.i18n.BilingualFormatter
import com.example.i18n.DistanceUnit
import com.example.i18n.stringsFor
import com.example.navigation.AheadAlert

/**
 * AA7: Surfaces road alerts (speed/red-light cameras, speed bumps, hazards, railroad, school zones,
 * severe weather, road closures) and arrival notifications on Android Auto using
 * [AppManager.showAlert] (Car API 5+) with automatic [CarToast] fallback on earlier API levels.
 */
class CarAlertHelper(private val carContext: CarContext) {

    private val shownAlertIds = HashSet<String>()
    private var nextAlertId = 100

    fun reset() {
        shownAlertIds.clear()
    }

    fun showAheadAlertIfNew(
        alert: AheadAlert?,
        unit: DistanceUnit,
        language: AppLanguage
    ) {
        if (alert == null) return
        if (!shownAlertIds.add("ahead_${alert.id}")) return

        val title = if (language == AppLanguage.PERSIAN) alert.titleFa else alert.titleEn
        val distText = BilingualFormatter.formatDistance(alert.distanceMeters, unit, language)
        val subtitle = listOf(distText, alert.detail).filter { it.isNotBlank() }.joinToString(" · ")
        displayAlertOrToast(title = title, subtitle = subtitle, durationMs = 6000L, language = language)
    }

    fun showWeatherAlertIfNew(
        weather: WeatherAlertItem?,
        language: AppLanguage
    ) {
        if (weather == null) return
        if (!shownAlertIds.add("weather_${weather.id}")) return
        val subtitle = weather.endsText.takeIf { it.isNotBlank() } ?: weather.area.take(50)
        displayAlertOrToast(title = weather.title.take(40), subtitle = subtitle, durationMs = 7000L, language = language)
    }

    fun showArrivalAlert(destinationName: String, language: AppLanguage) {
        val str = stringsFor(language)
        displayAlertOrToast(
            title = str.carArrivedTitle,
            subtitle = destinationName.take(48),
            durationMs = 6000L,
            language = language
        )
    }

    fun showLaneChangeCueAlert(maneuverIndex: Int, pulseAtMs: Long, language: AppLanguage) {
        if (pulseAtMs <= 0L) return
        if (!shownAlertIds.add("lane_cue_${maneuverIndex}_${pulseAtMs}")) return
        val str = stringsFor(language)
        displayAlertOrToast(
            title = str.carChangeLanesNowTitle,
            subtitle = "",
            durationMs = 3500L,
            language = language
        )
    }

    fun showClosureReportedToast(language: AppLanguage) {
        val str = stringsFor(language)
        runCatching {
            CarToast.makeText(carContext, str.carClosureReportedToast, CarToast.LENGTH_SHORT).show()
        }
    }

    private fun displayAlertOrToast(
        title: String,
        subtitle: String,
        durationMs: Long,
        language: AppLanguage
    ) {
        val str = stringsFor(language)
        if (carContext.carAppApiLevel >= 5) {
            runCatching {
                val id = nextAlertId++
                val builder = Alert.Builder(id, CarText.create(title.take(50)), durationMs)
                    .setIcon(CarMapActionsHelper.icon(carContext, R.drawable.ic_car_closure))
                    .addAction(
                        Action.Builder()
                            .setTitle(str.carAlertDismiss)
                            .setOnClickListener {
                                runCatching {
                                    carContext.getCarService(AppManager::class.java).dismissAlert(id)
                                }
                            }
                            .build()
                    )
                if (subtitle.isNotBlank()) {
                    builder.setSubtitle(CarText.create(subtitle.take(64)))
                }
                carContext.getCarService(AppManager::class.java).showAlert(builder.build())
                return
            }
        }
        val toastText = if (subtitle.isNotBlank()) "$title — $subtitle" else title
        runCatching {
            CarToast.makeText(carContext, toastText, CarToast.LENGTH_LONG).show()
        }
    }
}
