package com.example.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.RahYabApplication
import com.example.data.local.SavedPlaceEntity
import com.example.data.local.SavedPlaceType
import com.example.data.preferences.UserSettings
import com.example.i18n.BilingualFormatter
import com.example.i18n.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AA4: Android Auto ListTemplate screen for Favorites (with Home & Work pinned at the top)
 * or Recent places. Selecting any row launches [CarRoutePreviewScreen].
 */
class CarPlacesListScreen(
    carContext: CarContext,
    private val app: RahYabApplication,
    private val renderer: CarMapSurfaceRenderer,
    private val showRecents: Boolean
) : Screen(carContext) {

    private var scope: CoroutineScope? = null
    private var settings: UserSettings = UserSettings()
    private var places: List<SavedPlaceEntity> = emptyList()
    private var isLoading: Boolean = true

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
                    app.database.savedPlaceDao().observeAll().collectLatest { all ->
                        places = if (showRecents) {
                            all.filter { it.type == SavedPlaceType.RECENT.name }
                                .sortedByDescending { it.updatedAt }
                        } else {
                            val home = all.filter { it.type == SavedPlaceType.HOME.name }
                            val work = all.filter { it.type == SavedPlaceType.WORK.name }
                            val favs = all.filter { it.type == SavedPlaceType.FAVORITE.name }
                                .sortedByDescending { it.updatedAt }
                            home + work + favs
                        }
                        isLoading = false
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope?.cancel()
                scope = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val str = stringsFor(settings.language)
        val title = if (showRecents) str.carActionRecent else str.carActionFavorites
        val builder = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        if (isLoading) {
            return builder.setLoading(true).build()
        }

        val ref = app.navigationHub.getCurrentReferenceLocation()
        val listBuilder = ItemList.Builder()
            .setNoItemsMessage(if (showRecents) str.carNoRecentPlaces else str.carNoSavedPlaces)

        places.take(6).forEach { entity ->
            val place = CarMapActionsHelper.savedEntityToPlace(entity, ref)
            val distStr = BilingualFormatter.formatDistance(
                place.distanceMeters,
                settings.distanceUnit,
                settings.language
            )
            val prefix = when (entity.type) {
                SavedPlaceType.HOME.name -> "${str.savedHome} · "
                SavedPlaceType.WORK.name -> "${str.savedWork} · "
                else -> ""
            }
            val rowTitle = (prefix + entity.name).trim().ifBlank { entity.address }
            val subtitle = listOf(distStr, entity.address.take(44))
                .filter { it.isNotBlank() }
                .joinToString(" · ")

            val rowBuilder = Row.Builder()
                .setTitle(rowTitle)
                .setOnClickListener {
                    screenManager.push(
                        CarRoutePreviewScreen(carContext, app, renderer, place)
                    )
                }
            if (subtitle.isNotBlank()) {
                rowBuilder.addText(subtitle)
            }
            listBuilder.addItem(rowBuilder.build())
        }

        return builder.setSingleList(listBuilder.build()).build()
    }
}
