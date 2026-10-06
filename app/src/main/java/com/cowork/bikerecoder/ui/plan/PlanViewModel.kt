package com.cowork.bikerecoder.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The route being planned. Scoped to the Activity so the main screen's long-press sheet, the search
 * results and the plan screen all edit the same stop list.
 *
 * The last stop is the destination; waypoints are inserted in front of it. Every change to the stops or
 * the profile cancels the running computation, shows [RouteUiState.Loading] and recomputes after
 * [RECOMPUTE_DEBOUNCE_MS].
 *
 * @param defaultProfile the profile preselected from settings (used unless the user already picked one).
 */
class PlanViewModel(
    private val router: Router,
    defaultProfile: Flow<RouteProfile>,
    private val currentLocation: () -> GeoPoint?,
    private val segmentsReady: () -> Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PlanUiState(stops = emptyList(), profile = RouteProfile.CYCLEWAY_FIRST, route = RouteUiState.Idle, canStart = false),
    )
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private var nextKey = 1L
    private var profileChosen = false

    /** False while the list holds only waypoints (their last element then acts as the destination). */
    private var hasDestination = false

    init {
        viewModelScope.launch {
            val profile = defaultProfile.first()
            if (!profileChosen) _state.update { it.withInputs(profile = profile) }
        }
        viewModelScope.launch {
            _state.map { it.stops to it.profile }.distinctUntilChanged().collectLatest { (stops, profile) ->
                // collectLatest cancels the previous block at once; the delay is the debounce.
                delay(RECOMPUTE_DEBOUNCE_MS)
                recompute(stops, profile)
            }
        }
    }

    fun setDestination(name: String, p: GeoPoint) {
        _state.update { s ->
            val stop = PlannedStop(nextKey++, name, p)
            val stops = if (hasDestination && s.stops.isNotEmpty()) s.stops.dropLast(1) + stop else s.stops + stop
            hasDestination = true
            s.withInputs(stops = stops)
        }
    }

    fun addWaypoint(name: String, p: GeoPoint) {
        _state.update { s ->
            val stop = PlannedStop(nextKey++, name, p)
            val stops = if (hasDestination && s.stops.isNotEmpty()) {
                s.stops.dropLast(1) + stop + s.stops.last()
            } else {
                s.stops + stop
            }
            s.withInputs(stops = stops)
        }
    }

    fun remove(key: Long) {
        _state.update { s ->
            val index = s.stops.indexOfFirst { it.key == key }
            if (index < 0) return@update s
            if (hasDestination && index == s.stops.lastIndex) hasDestination = false
            s.withInputs(stops = s.stops.filterNot { it.key == key })
        }
    }

    fun move(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.stops.indices || to !in s.stops.indices || from == to) return@update s
            val stops = s.stops.toMutableList()
            stops.add(to, stops.removeAt(from))
            s.withInputs(stops = stops)
        }
    }

    fun setProfile(p: RouteProfile) {
        profileChosen = true
        _state.update { it.withInputs(profile = p) }
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

    private suspend fun recompute(stops: List<PlannedStop>, profile: RouteProfile) {
        if (stops.isEmpty()) {
            publish(stops, profile, RouteUiState.Idle)
            return
        }
        if (!segmentsReady()) {
            publish(stops, profile, RouteUiState.Error(MSG_NO_SEGMENTS))
            return
        }
        var start = currentLocation()
        while (start == null) {
            publish(stops, profile, RouteUiState.Error(MSG_NO_LOCATION))
            delay(LOCATION_POLL_MS)
            start = currentLocation()
        }
        if (GeoMath.distanceM(start, stops.last().point) <= MIN_DESTINATION_DISTANCE_M) {
            publish(stops, profile, RouteUiState.Error(MSG_TOO_CLOSE))
            return
        }
        publish(stops, profile, RouteUiState.Loading)
        val request = RouteRequest(start, null, stops.map { it.point }, profile)
        val result = try {
            router.route(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouteResult.Failure(RouteFailure.OTHER, e.message.orEmpty())
        }
        publish(
            stops,
            profile,
            when (result) {
                is RouteResult.Success -> RouteUiState.Ready(result.route)
                is RouteResult.Failure -> RouteUiState.Error(messageFor(result.reason))
            },
        )
    }

    /** Publishes only if the inputs are still the ones this computation started from. */
    private fun publish(stops: List<PlannedStop>, profile: RouteProfile, route: RouteUiState) {
        _state.update { s ->
            if (s.stops != stops || s.profile != profile) {
                s
            } else {
                s.copy(route = route, canStart = route is RouteUiState.Ready && segmentsReady())
            }
        }
    }

    private fun messageFor(reason: RouteFailure): String = when (reason) {
        RouteFailure.NO_SEGMENT_DATA -> MSG_NO_SEGMENTS
        RouteFailure.NO_ROUTE -> "경로를 찾을 수 없습니다"
        RouteFailure.TIMEOUT -> "경로 계산 시간이 너무 깁니다. 경유지를 추가해 주세요"
        RouteFailure.OTHER -> "경로를 계산하지 못했습니다"
    }

    companion object {
        const val RECOMPUTE_DEBOUNCE_MS = 300L
        const val LOCATION_POLL_MS = 1_000L
        private const val MIN_DESTINATION_DISTANCE_M = 50.0
        private const val MSG_NO_SEGMENTS = "경로 데이터가 없습니다. 설정에서 내려받으세요"
        private const val MSG_NO_LOCATION = "현재 위치를 확인하는 중입니다"
        private const val MSG_TOO_CLOSE = "목적지가 현재 위치와 너무 가깝습니다"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlanViewModel(
                    router = container.router,
                    defaultProfile = container.settings.settings.map { it.defaultProfile },
                    currentLocation = { container.currentLocation.value?.point },
                    segmentsReady = { container.segments.hasRequired() },
                )
            }
        }
    }
}
