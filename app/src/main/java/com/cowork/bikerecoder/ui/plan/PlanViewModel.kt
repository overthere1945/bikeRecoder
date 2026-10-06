package com.cowork.bikerecoder.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.ui.common.routeFailureText
import kotlinx.coroutines.CancellationException
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
 */
class PlanViewModel(
    private val router: Router,
    defaultProfile: Flow<RouteProfile>,
    private val locations: StateFlow<LocationFix?>,
    private val segmentsReady: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

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

    fun setDestination(name: String, p: GeoPoint) {
        val stop = PlannedStop(nextKey++, name, p)
        edit { s -> s.withInputs(stops = if (s.stops.isEmpty()) listOf(stop) else s.stops.dropLast(1) + stop) }
    }

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

    /** Empties the plan and goes back to the settings' default profile (call when navigation starts). */
    fun clear() {
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
            is RouteResult.Success -> publish(stops, profile, RouteUiState.Ready(result.route), startable = dataReady)
            is RouteResult.Failure -> publish(stops, profile, RouteUiState.Error(routeFailureText(result.reason)), startable = false)
        }
    }

    /** Publishes only if the inputs are still the ones this computation started from. */
    private fun publish(stops: List<PlannedStop>, profile: RouteProfile, route: RouteUiState, startable: Boolean) {
        _state.update { s ->
            if (s.stops != stops || s.profile != profile) s else s.copy(route = route, canStart = startable && route is RouteUiState.Ready)
        }
    }

    companion object {
        const val RECOMPUTE_DEBOUNCE_MS = 300L
        const val MAX_FIX_AGE_MS = 30_000L
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
                )
            }
        }
    }
}
