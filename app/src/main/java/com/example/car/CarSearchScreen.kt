package com.example.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.RahYabApplication
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AA5: Android Auto offline SearchTemplate backed by [com.example.data.search.OfflinePlaceSearchEngine]
 * (places + house-number addresses), sorted by distance.
 *
 * When the query is blank or the car is moving (keyboard restricted while driving), shows
 * quick Favorites & Recent places instead so the driver can still pick a destination safely.
 */
class CarSearchScreen(
    carContext: CarContext,
    private val app: RahYabApplication,
    private val renderer: CarMapSurfaceRenderer
) : Screen(carContext) {

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private var settings: UserSettings = UserSettings()
    private var savedPlaces: List<SavedPlaceEntity> = emptyList()
    private var queryText: String = ""
    private var results: List<PlaceSearchResult> = emptyList()
    private var isSearching: Boolean = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope?.cancel()
                val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                scope = s
                s.launch {
                    app.preferencesRepository.settingsFlow.collectLatest {
                        settings = it
                        invalidate()
                    }
                }
                s.launch {
                    app.database.savedPlaceDao().observeAll().collectLatest {
                        savedPlaces = it
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                searchJob?.cancel()
                scope?.cancel()
                scope = null
            }
        })
    }

    private fun runOfflineSearch(query: String, immediate: Boolean) {
        queryText = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            isSearching = false
            invalidate()
            return
        }
        isSearching = true
        invalidate()
        searchJob = scope?.launch {
            if (!immediate) delay(280L)
            val ref = app.navigationHub.getCurrentReferenceLocation()
            val found = runCatching {
                app.offlineSearchEngine.search(
                    query = trimmed,
                    userLat = ref.lat,
                    userLon = ref.lon,
                    limit = 6
                )
            }.getOrDefault(emptyList())
            results = found.sortedBy { it.distanceMeters }
            isSearching = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val str = stringsFor(settings.language)
        val isDriving = (app.locationTracker.currentFix.value?.speedMps ?: 0f) > 2.2f
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                runOfflineSearch(searchText, immediate = false)
            }

            override fun onSearchSubmitted(searchText: String) {
                runOfflineSearch(searchText, immediate = true)
            }
        }

        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(if (isDriving) str.carSearchDrivingHint else str.carSearchHint)
            .setShowKeyboardByDefault(!isDriving)
            .setLoading(isSearching)

        if (!isSearching) {
            builder.setItemList(buildItemList(str))
        }
        return builder.build()
    }

    private fun buildItemList(str: com.example.i18n.AppStrings): ItemList {
        val ref = app.navigationHub.getCurrentReferenceLocation()
        val listBuilder = ItemList.Builder()

        if (queryText.isBlank()) {
            listBuilder.setNoItemsMessage(str.carNoSavedPlaces)
            val prioritized = savedPlaces.filter { it.type == SavedPlaceType.HOME.name } +
                savedPlaces.filter { it.type == SavedPlaceType.WORK.name } +
                savedPlaces.filter { it.type == SavedPlaceType.FAVORITE.name }.sortedByDescending { it.updatedAt } +
                savedPlaces.filter { it.type == SavedPlaceType.RECENT.name }.sortedByDescending { it.updatedAt }

            prioritized.take(6).forEach { entity ->
                val place = CarMapActionsHelper.savedEntityToPlace(entity, ref)
                listBuilder.addItem(buildPlaceRow(place))
            }
        } else {
            listBuilder.setNoItemsMessage(str.carNoResults)
            results.take(6).forEach { place ->
                listBuilder.addItem(buildPlaceRow(place))
            }
        }
        return listBuilder.build()
    }

    private fun buildPlaceRow(place: PlaceSearchResult): Row {
        val distStr = BilingualFormatter.formatDistance(
            place.distanceMeters,
            settings.distanceUnit,
            settings.language
        )
        val subtitle = listOf(distStr, place.formattedAddress.take(42))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val rowBuilder = Row.Builder()
            .setTitle(place.name.ifBlank { place.formattedAddress })
            .setOnClickListener {
                screenManager.push(
                    CarRoutePreviewScreen(carContext, app, renderer, place)
                )
            }
        if (subtitle.isNotBlank()) {
            rowBuilder.addText(subtitle)
        }
        return rowBuilder.build()
    }
}
