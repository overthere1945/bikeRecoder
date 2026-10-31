package com.cowork.bikerecoder.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportResult
import com.cowork.bikerecoder.export.ExportRunner
import com.cowork.bikerecoder.export.ExportState
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.elevation.RideTimeEstimator
import com.cowork.bikerecoder.core.elevation.UsualSpeed
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.ui.common.PlaceLookup
import com.cowork.bikerecoder.ui.common.PressedPlace
import com.cowork.bikerecoder.ui.common.addressOrNull
import com.cowork.bikerecoder.ui.common.routeFailureText
import com.cowork.bikerecoder.ui.elevation.ElevationHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The route being planned. Scoped to the Activity so the main screen's long-press sheet, the search
 * results and the plan screen all edit the same stop list.
 *
 * The last stop is always the destination; waypoints are inserted in front of it. Every change to the
 * stops or the profile cancels the running computation, shows [RouteUiState.Loading] and recomputes after
 * [RECOMPUTE_DEBOUNCE_MS].
 *
 * @param defaultProfile the profile preselected from settings (used unless the user already picked one).
 * @param locations the shared current-location state; fixes older than [MAX_FIX_AGE_MS] (by `timeMillis`
 *   against [clock]) are ignored and the computation waits for a fresh one.
 * @param usualSpeed reads the usual speed (V1.7.0 spec §1.6). It is read again for every route found (this view model
 *   lives with the Activity, rides recorded meanwhile count); a read that fails or takes longer than
 *   [USUAL_SPEED_TIMEOUT_MS] is logged and [UsualSpeed.DEFAULT_MPS]. The route is published with that speed, its time at
 *   that speed corrected for the climbs ([RideTimeEstimator], spec §2.5) and its elevation profile computed on [compute].
 * @param handoff where [openElevation] hands the profile and route line to the "고도" map screen.
 * @param exportRoute [com.cowork.bikerecoder.export.TripExporter.exportRoute] (V1.7.0 spec §4.1): the ready route as
 *   GPX or KML ([export]); without it the plan offers no export.
 */
class PlanViewModel(
    private val router: Router,
    defaultProfile: Flow<RouteProfile>,
    private val locations: StateFlow<LocationFix?>,
    private val segmentsReady: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Address of a long-pressed point, null when unknown. */
    addressOf: suspend (GeoPoint) -> String? = { null },
    private val usualSpeed: suspend () -> Double = { UsualSpeed.DEFAULT_MPS },
    private val handoff: ElevationHandoff = ElevationHandoff(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val log: WarnLog = androidWarnLog(TAG),
    private val exportRoute: (suspend (Route, List<Stop>, String, ExportFormat) -> ExportResult)? = null,
) : ViewModel() {

    private val exporter = ExportRunner(viewModelScope, log)

    /** The export started from the summary's [⋮]. */
    val exportState: StateFlow<ExportState> = exporter.state

    /** The [⋮] is offered (while the route is ready). */
    val canExport: Boolean get() = exportRoute != null

    private val lookup = PlaceLookup(addressOf, viewModelScope)

    /** The long-pressed map point whose sheet is open. */
    val pressed: StateFlow<PressedPlace?> = lookup.pressed

    private val _state = MutableStateFlow(
        PlanUiState(stops = emptyList(), profile = RouteProfile.CYCLEWAY_FIRST, route = RouteUiState.Idle, canStart = false),
    )
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    /** Bumped on every effective input change, so the recompute trigger never depends on value equality. */
    private val changes = MutableStateFlow(0)

    private var nextKey = 1L
    private var profileChosen = false
    private var latestDefault = RouteProfile.CYCLEWAY_FIRST

    init {
        viewModelScope.launch {
            defaultProfile.collect { profile ->
                latestDefault = profile
                if (!profileChosen) edit { it.withInputs(profile = profile) }
            }
        }
        viewModelScope.launch {
            changes.collectLatest {
                // collectLatest cancels the previous block at once; the delay is the debounce.
                delay(RECOMPUTE_DEBOUNCE_MS)
                val s = _state.value
                recompute(s.stops, s.profile)
            }
        }
    }

    fun onLongPress(point: GeoPoint) = lookup.onLongPress(point)

    fun dismissPressed() = lookup.dismiss()

    fun setDestination(name: String, p: GeoPoint) {
        val stop = PlannedStop(nextKey++, name, p)
        edit { s -> s.withInputs(stops = if (s.stops.isEmpty()) listOf(stop) else s.stops.dropLast(1) + stop) }
    }

    /** A favorite chip was tapped: the destination only, named after the favorite (waypoints stay). */
    fun setDestination(favorite: Favorite) = setDestination(favorite.name, favorite.point)

    fun addWaypoint(name: String, p: GeoPoint) {
        val stop = PlannedStop(nextKey++, name, p)
        edit { s -> s.withInputs(stops = if (s.stops.isEmpty()) listOf(stop) else s.stops.dropLast(1) + stop + s.stops.last()) }
    }

    fun remove(key: Long) {
        edit { s -> s.withInputs(stops = s.stops.filterNot { it.key == key }) }
    }

    fun move(from: Int, to: Int) {
        edit { s ->
            if (from !in s.stops.indices || to !in s.stops.indices || from == to) return@edit s
            val stops = s.stops.toMutableList()
            stops.add(to, stops.removeAt(from))
            s.withInputs(stops = stops)
        }
    }

    fun setProfile(p: RouteProfile) {
        profileChosen = true
        edit { it.withInputs(profile = p) }
    }

    /**
     * The plan screen is visible again (e.g. back from Settings): a failed route is recomputed (the route data
     * may have been downloaded meanwhile), and a ready one only if the route data is gone. A ready route is
     * not recomputed otherwise, so it does not flicker on every resume.
     */
    fun onResume() {
        val s = _state.value
        if (s.stops.isEmpty()) return
        val stale = when (s.route) {
            is RouteUiState.Error -> true
            is RouteUiState.Ready -> !segmentsReady()
            else -> false
        }
        if (stale) changes.update { it + 1 }
    }

    /**
     * The elevation chart was tapped: hands the ready route's profile and line to the "고도" map screen
     * ([ElevationHandoff]). False (nothing handed over) without a ready route with elevation.
     */
    fun openElevation(): Boolean {
        val s = _state.value
        val route = (s.route as? RouteUiState.Ready)?.route ?: return false
        val profile = s.elevation ?: return false
        handoff.put(profile, route.points)
        return true
    }

    /**
     * "GPX로 내보내기" / "KML로 내보내기": the ready route with its waypoints and destination, named after the destination.
     * Nothing without a ready route.
     */
    fun export(format: ExportFormat) {
        val run = exportRoute ?: return
        val s = _state.value
        val route = (s.route as? RouteUiState.Ready)?.route ?: return
        val stops = s.stops.mapIndexed { i, stop -> Stop(stop.key, stop.name, stop.point, isDestination = i == s.stops.lastIndex) }
        val destination = s.stops.lastOrNull()?.name ?: return
        exporter.run { run(route, stops, destination, format) }
    }

    /** The export's outcome has been shown (dialog closed, Toast posted). */
    fun clearExport() = exporter.clear()

    /**
     * Empties the plan and goes back to the settings' default profile (call when navigation starts). An export of the old
     * route still running is forgotten, so its dialog does not show up on a later plan.
     */
    fun clear() {
        exporter.discard()
        profileChosen = false
        edit { it.withInputs(stops = emptyList(), profile = latestDefault) }
    }

    private fun edit(transform: (PlanUiState) -> PlanUiState) {
        var changed = false
        _state.update { s ->
            val next = transform(s)
            changed = next != s
            next
        }
        if (changed) changes.update { it + 1 }
    }

    /** Applies an input change: the previous route is stale, so hide it until the recompute finishes. */
    private fun PlanUiState.withInputs(
        stops: List<PlannedStop> = this.stops,
        profile: RouteProfile = this.profile,
    ): PlanUiState {
        if (stops == this.stops && profile == this.profile) return this
        return copy(
            stops = stops,
            profile = profile,
            route = if (stops.isEmpty()) RouteUiState.Idle else RouteUiState.Loading,
            canStart = false,
            elevation = null,
            durationSeconds = null,
        )
    }

    private fun isFresh(fix: LocationFix?) = fix != null && clock() - fix.timeMillis <= MAX_FIX_AGE_MS

    private suspend fun recompute(stops: List<PlannedStop>, profile: RouteProfile) {
        if (stops.isEmpty()) {
            publish(stops, profile, RouteUiState.Idle, startable = false)
            return
        }
        val dataReady = segmentsReady() // once per recompute
        if (!dataReady) {
            publish(stops, profile, RouteUiState.Error(MSG_NO_SEGMENTS), startable = false)
            return
        }
        val fix = locations.value?.takeIf(::isFresh) ?: run {
            publish(stops, profile, RouteUiState.Error(MSG_NO_LOCATION), startable = false)
            // Collecting (not polling) keeps the location source alive only while this is running.
            locations.first(::isFresh)!!
        }
        val start = fix.point
        // Only a plan without waypoints can be "too close"; a loop (destination = start) is legitimate.
        if (stops.size == 1 && GeoMath.distanceM(start, stops.last().point) <= MIN_DESTINATION_DISTANCE_M) {
            publish(stops, profile, RouteUiState.Error(MSG_TOO_CLOSE), startable = false)
            return
        }
        publish(stops, profile, RouteUiState.Loading, startable = false)
        val request = RouteRequest(start, null, stops.map { it.point }, profile)
        val result = try {
            router.route(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouteResult.Failure(RouteFailure.OTHER, e.message.orEmpty())
        }
        when (result) {
            is RouteResult.Success -> {
                val route = result.route
                val elevation = withContext(compute) { ElevationProfile.fromRoute(route) }
                val usual = readUsualSpeed()
                val seconds = RideTimeEstimator.seconds(elevation, 0.0, route.summary.totalDistanceM, usual)
                publish(stops, profile, RouteUiState.Ready(route), startable = dataReady, elevation, seconds, usual)
            }
            is RouteResult.Failure -> publish(stops, profile, RouteUiState.Error(routeFailureText(result.reason)), startable = false)
        }
    }

    /** The usual speed now; [UsualSpeed.DEFAULT_MPS] (logged) if the read fails or hangs. */
    private suspend fun readUsualSpeed(): Double {
        val mps = try {
            withTimeoutOrNull(USUAL_SPEED_TIMEOUT_MS) { usualSpeed() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the recent rides; the usual speed is the default", e)
            return UsualSpeed.DEFAULT_MPS
        }
        if (mps == null) log.warn("Reading the recent rides took over $USUAL_SPEED_TIMEOUT_MS ms; the usual speed is the default", null)
        return mps ?: UsualSpeed.DEFAULT_MPS
    }

    /**
     * Publishes only if the inputs are still the ones this computation started from. [elevation], [durationSeconds] and
     * [usualSpeedMps] (what [durationSeconds] was computed with) belong to a Ready [route] (null / unchanged otherwise).
     */
    private fun publish(
        stops: List<PlannedStop>,
        profile: RouteProfile,
        route: RouteUiState,
        startable: Boolean,
        elevation: ElevationProfile? = null,
        durationSeconds: Double? = null,
        usualSpeedMps: Double? = null,
    ) {
        _state.update { s ->
            if (s.stops != stops || s.profile != profile) {
                s
            } else {
                s.copy(
                    route = route,
                    canStart = startable && route is RouteUiState.Ready,
                    elevation = elevation,
                    durationSeconds = durationSeconds,
                    usualSpeedMps = usualSpeedMps ?: s.usualSpeedMps,
                )
            }
        }
    }

    companion object {
        const val RECOMPUTE_DEBOUNCE_MS = 300L
        const val MAX_FIX_AGE_MS = 30_000L

        /** A usual speed read taking longer than this falls back to the default (a hung store must not block the route). */
        const val USUAL_SPEED_TIMEOUT_MS = 2_000L
        private const val TAG = "PlanViewModel"
        private const val MIN_DESTINATION_DISTANCE_M = 50.0
        private val MSG_NO_SEGMENTS = routeFailureText(RouteFailure.NO_SEGMENT_DATA)
        private const val MSG_NO_LOCATION = "현재 위치를 확인하는 중입니다"
        private const val MSG_TOO_CLOSE = "목적지가 현재 위치와 너무 가깝습니다"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlanViewModel(
                    router = container.router,
                    defaultProfile = container.settings.settings.map { it.defaultProfile },
                    locations = container.currentLocation,
                    segmentsReady = { container.segments.hasRequired() },
                    addressOf = { container.placeSearch.addressOrNull(it) },
                    usualSpeed = {
                        withContext(Dispatchers.IO) { UsualSpeed.of(container.rideStore.recentSegments(UsualSpeed.RECENT)) }
                    },
                    handoff = container.elevationHandoff,
                    exportRoute = container.tripExporter::exportRoute,
                )
            }
        }
    }
}
