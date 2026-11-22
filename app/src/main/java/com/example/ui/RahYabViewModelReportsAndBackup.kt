package com.example.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.data.local.BackupAndTransferManager
import com.example.data.local.ReportType
import com.example.data.local.SavedPlaceType
import com.example.data.local.UserReportEntity
import com.example.i18n.stringsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun RahYabViewModel.submitUserReportImpl(type: ReportType) {
    val loc = _navigationState.value.snappedLocation ?: getEffectiveReferenceLocation()
    val bearing = if (_navigationState.value.isNavigating) {
        _navigationState.value.snappedBearing
    } else {
        currentGpsFix.value?.bearing ?: -1f
    }
    val roadName = _navigationState.value.currentManeuver?.streetNames?.firstOrNull().orEmpty()
    val now = System.currentTimeMillis()
    val expiresAt = now + type.defaultTtlMinutes * 60_000L

    viewModelScope.launch {
        userReportDao.insert(
            UserReportEntity(
                type = type.name,
                lat = loc.lat,
                lon = loc.lon,
                bearing = bearing,
                roadName = roadName,
                createdAt = now,
                expiresAt = expiresAt
            )
        )
        _nowTick.value = System.currentTimeMillis()
        _snackbarMessage.value = stringsFor(settings.value.language).reportSavedToast
    }
}

internal fun RahYabViewModel.confirmUserReportImpl(reportId: Long) {
    viewModelScope.launch {
        val newExpiry = System.currentTimeMillis() + 60 * 60_000L
        userReportDao.confirmReport(reportId, newExpiry)
    }
}

internal fun RahYabViewModel.updateReportNoteImpl(reportId: Long, note: String) {
    viewModelScope.launch {
        userReportDao.updateNote(reportId, note.trim())
        _nowTick.value = System.currentTimeMillis()
    }
}

internal fun RahYabViewModel.dismissUserReportImpl(reportId: Long) {
    viewModelScope.launch {
        userReportDao.deleteById(reportId)
        _nowTick.value = System.currentTimeMillis()
    }
}

internal fun RahYabViewModel.exportReportsToUriImpl(uri: Uri) {
    viewModelScope.launch {
        val str = stringsFor(settings.value.language)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val reports = userReportDao.getActiveOnce(System.currentTimeMillis())
                val json = BackupAndTransferManager.exportReportsJson(reports)
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open output stream")
            }
        }
        _snackbarMessage.value = if (result.isSuccess) str.backupExportSuccess else str.backupTransferFailed
    }
}

internal fun RahYabViewModel.importReportsFromUriImpl(uri: Uri) {
    viewModelScope.launch {
        val str = stringsFor(settings.value.language)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val json = app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: error("Cannot read file")
                val imported = BackupAndTransferManager.importReportsJson(json)
                for (r in imported) {
                    if (userReportDao.getByUuid(r.uuid) == null) {
                        userReportDao.insert(r)
                    }
                }
                imported.size
            }
        }
        _nowTick.value = System.currentTimeMillis()
        _snackbarMessage.value = if (result.isSuccess) {
            "${str.backupImportSuccess} (${result.getOrDefault(0)})"
        } else {
            str.backupTransferFailed
        }
    }
}

internal fun RahYabViewModel.exportFullBackupToUriImpl(uri: Uri) {
    viewModelScope.launch {
        val str = stringsFor(settings.value.language)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.value
                val places = savedPlaceDao.getAllOnce()
                val reports = userReportDao.getActiveOnce(System.currentTimeMillis())
                val json = BackupAndTransferManager.exportFullBackupJson(s, places, reports)
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open output stream")
            }
        }
        _snackbarMessage.value = if (result.isSuccess) str.backupExportSuccess else str.backupTransferFailed
    }
}

internal fun RahYabViewModel.importFullBackupFromUriImpl(uri: Uri) {
    viewModelScope.launch {
        val str = stringsFor(settings.value.language)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val json = app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    ?: error("Cannot read backup file")
                val parsed = BackupAndTransferManager.importFullBackupJson(json)
                parsed.settings?.let { s ->
                    prefs.setLanguage(s.language)
                    prefs.setDistanceUnit(s.distanceUnit)
                    prefs.setThemeMode(s.themeMode)
                    prefs.setVoiceGuidanceEnabled(s.voiceGuidanceEnabled)
                    prefs.setSpeedWarningAudioEnabled(s.speedWarningAudioEnabled)
                    prefs.setSpeedWarningThresholdMph(s.speedWarningThresholdMph)
                    prefs.setJunctionPreviewEnabled(s.junctionPreviewEnabled)
                    prefs.setAlertSpeedDropEnabled(s.alertSpeedDropEnabled)
                    prefs.setAlertSharpCurveEnabled(s.alertSharpCurveEnabled)
                    prefs.setAlertTollBoothEnabled(s.alertTollBoothEnabled)
                    prefs.setAlertRailroadEnabled(s.alertRailroadEnabled)
                    prefs.setAlertSchoolZoneEnabled(s.alertSchoolZoneEnabled)
                    prefs.setAlertStopSignEnabled(s.alertStopSignEnabled)
                    prefs.setAlertSpeedCameraEnabled(s.alertSpeedCameraEnabled)
                    prefs.setAlertRedLightCameraEnabled(s.alertRedLightCameraEnabled)
                    prefs.setShowTrafficLights(s.showTrafficLights)
                    prefs.set3DView(s.is3DView)
                    prefs.setAvoidTolls(s.avoidTolls)
                    prefs.setAvoidHighways(s.avoidHighways)
                    prefs.setAvoidFerries(s.avoidFerries)
                    prefs.setRoutePreference(s.routePreference)
                    prefs.setGitHubRepository(s.githubOwner, s.githubRepo)
                    prefs.setPackBaseUrl(s.packBaseUrl)
                    prefs.setOnboardingCompleted(true)
                }
                for (place in parsed.savedPlaces) {
                    if (place.type == SavedPlaceType.HOME.name || place.type == SavedPlaceType.WORK.name) {
                        savedPlaceDao.deleteByType(place.type)
                    }
                    savedPlaceDao.insert(place)
                }
                for (report in parsed.reports) {
                    if (userReportDao.getByUuid(report.uuid) == null) {
                        userReportDao.insert(report)
                    }
                }
            }
        }
        _nowTick.value = System.currentTimeMillis()
        _snackbarMessage.value = if (result.isSuccess) str.backupImportSuccess else str.backupTransferFailed
    }
}
