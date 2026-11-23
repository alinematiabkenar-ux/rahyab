package com.example.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.RahYabApplication
import com.example.data.preferences.UserSettings
import com.example.data.search.PlaceSearchResult
import com.example.i18n.stringsFor
import com.example.navigation.OfflineRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * AA5: Android Auto Route Preview screen showing up to 3 offline Valhalla routes with duration
 * and distance. Uses [MapWithContentTemplate] on Car API level 7+ and
 * [RoutePreviewNavigationTemplate] on earlier API levels. Tapping "Start" launches active
 * turn-by-turn navigation in [com.example.navigation.RahYabNavigationHub].
 */
class CarRoutePreviewScreen(
    carContext: CarContext,
    private val app: RahYabApplication,
    private val renderer: CarMapSurfaceRenderer,
    private val destination: PlaceSearchResult
) : Screen(carContext) {

    private var scope: CoroutineScope? = null
    private var settings: UserSettings = UserSettings()
    private var routes: List<OfflineRoute> = emptyList()
    private var selectedIndex: Int = 0
    private var isCalculating: Boolean = true
    private var routeError: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                app.navigationHub.calculateRoutesForPlace(destination)
            }

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
                    val hub = app.navigationHub
                    combine(
                        hub.candidateRoutes,
                        hub.selectedRouteIndex,
                        hub.isCalculatingRoute,
                        hub.routeError
                    ) { r, idx, calc, err ->
                        PreviewState(r, idx, calc, err)
                    }.collectLatest { st ->
                        routes = st.routes.take(3)
                        selectedIndex = st.selectedIndex.coerceIn(0, routes.lastIndex.coerceAtLeast(0))
                        isCalculating = st.isCalculating
                        routeError = st.error
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

    private data class PreviewState(
        val routes: List<OfflineRoute>,
        val selectedIndex: Int,
        val isCalculating: Boolean,
        val error: String?
    )

    private fun startSelectedRoute() {
        app.navigationHub.startNavigation(forceSimulate = null)
        screenManager.popToRoot()
        screenManager.push(CarNavigationScreen(carContext, app, renderer))
    }

    override fun onGetTemplate(): Template {
        val str = stringsFor(settings.language)
        val title = destination.name.ifBlank { str.carRoutePreviewTitle }

        if (carContext.carAppApiLevel >= 7 && !isCalculating && routes.isNotEmpty()) {
            return buildApi7MapWithContentTemplate(title, str)
        }

        val builder = RoutePreviewNavigationTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        if (carContext.carAppApiLevel >= 2) {
            builder.setMapActionStrip(CarMapActionsHelper.buildMapActionStrip(carContext, renderer))
        }

        if (isCalculating) {
            return builder.setLoading(true).build()
        }

        val itemList = buildSelectableRouteList(str)
        builder.setItemList(itemList)
        if (routes.isNotEmpty()) {
            builder.setNavigateAction(
                Action.Builder()
                    .setTitle(str.carActionStart)
                    .setOnClickListener { startSelectedRoute() }
                    .build()
            )
        }
        return builder.build()
    }

    private fun buildApi7MapWithContentTemplate(
        title: String,
        str: com.example.i18n.AppStrings
    ): Template {
        val listBuilder = ItemList.Builder()
            .setNoItemsMessage(routeError ?: str.missingRoutingTiles)
        routes.forEachIndexed { idx, route ->
            val tag = routeTag(route, str)
            val summary = CarMapActionsHelper.buildRouteSummarySpannable(
                durationSeconds = route.durationSeconds,
                distanceMeters = route.distanceMeters,
                unit = settings.distanceUnit,
                suffixLabel = tag
            )
            val isSelected = idx == selectedIndex
            val row = Row.Builder()
                .setTitle(summary)
                .addText(
                    if (isSelected) "${str.carActionStart} · ${route.summaryLabel}" else route.summaryLabel
                )
                .setOnClickListener {
                    if (selectedIndex == idx) {
                        startSelectedRoute()
                    } else {
                        app.navigationHub.selectCandidateRoute(idx)
                    }
                }
                .build()
            listBuilder.addItem(row)
        }

        val contentTemplate = ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()

        val startStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(str.carActionStart)
                    .setOnClickListener { startSelectedRoute() }
                    .build()
            )
            .build()

        val mapController = MapController.Builder()
            .setMapActionStrip(CarMapActionsHelper.buildMapActionStrip(carContext, renderer))
            .build()

        return MapWithContentTemplate.Builder()
            .setContentTemplate(contentTemplate)
            .setActionStrip(startStrip)
            .setMapController(mapController)
            .build()
    }

    private fun buildSelectableRouteList(str: com.example.i18n.AppStrings): ItemList {
        val listBuilder = ItemList.Builder()
            .setNoItemsMessage(routeError ?: str.missingRoutingTiles)
        if (routes.isNotEmpty()) {
            listBuilder
                .setSelectedIndex(selectedIndex)
                .setOnSelectedListener { idx ->
                    app.navigationHub.selectCandidateRoute(idx)
                }
            routes.forEach { route ->
                val tag = routeTag(route, str)
                val summary = CarMapActionsHelper.buildRouteSummarySpannable(
                    durationSeconds = route.durationSeconds,
                    distanceMeters = route.distanceMeters,
                    unit = settings.distanceUnit,
                    suffixLabel = tag
                )
                val rowBuilder = Row.Builder().setTitle(summary)
                if (route.summaryLabel.isNotBlank()) {
                    rowBuilder.addText(route.summaryLabel)
                }
                listBuilder.addItem(rowBuilder.build())
            }
        }
        return listBuilder.build()
    }

    private fun routeTag(route: OfflineRoute, str: com.example.i18n.AppStrings): String = when {
        route.isFastestRoute -> str.carRouteFastestTag
        route.isShortestRoute -> str.carRouteShortestTag
        else -> ""
    }
}
