package com.cowork.bikerecoder.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.StartPrompt
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ui.plan.PlannedStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The plan being started: its profile and stops (last = destination). */
data class StartPlan(val profile: RouteProfile, val stops: List<PlannedStop>)

sealed interface TripStartState {
    data object Idle : TripStartState
    data object Working : TripStartState

    /** "오늘 하루 일정인가요, 여러 날 일정인가요?" */
    data object AskType : TripStartState

    /** "{destinationName} 여행을 이어서 진행할까요?" */
    data class AskContinue(val trip: Trip, val destinationName: String) : TripStartState

    /** The trip to guide; the caller starts navigation and calls [TripStartViewModel.consumeReady]. */
    data class Ready(val tripId: Long) : TripStartState
    data class Error(val message: String) : TripStartState
}

/** Planned stops as trip stops: order = index, the last one is the destination, none visited. */
fun List<PlannedStop>.toTripStops(): List<TripStop> = mapIndexed { index, stop ->
    TripStop(
        id = 0,
        tripId = 0,
        order = index,
        name = stop.name,
        point = stop.point,
        isDestination = index == lastIndex,
        visitedAt = null,
    )
}

/**
 * [안내 시작] on the plan screen → which trip to guide (spec §5):
 * no active multi-day trip → ask the type ([당일] default / [여러 날]) and start a new trip (completing any
 * active one); an active multi-day trip → ask to continue it ([이어서]: its stops are replaced only if the
 * plan differs from its unvisited stops; it keeps its own profile) or [새로 시작] (→ ask the type).
 */
class TripStartViewModel(
    private val tripManager: TripManager,
    private val tripStore: TripStore,
) : ViewModel() {

    private val _state = MutableStateFlow<TripStartState>(TripStartState.Idle)
    val state: StateFlow<TripStartState> = _state.asStateFlow()

    private var plan: StartPlan? = null

    fun onStartPressed(plan: StartPlan) {
        if (_state.value != TripStartState.Idle || plan.stops.isEmpty()) return
        this.plan = plan
        work {
            when (val prompt = tripManager.startPrompt()) {
                StartPrompt.AskType -> TripStartState.AskType
                is StartPrompt.AskContinue -> TripStartState.AskContinue(prompt.trip, destinationName(prompt.trip.id))
            }
        }
    }

    fun chooseType(type: TripType) {
        val p = plan ?: return
        if (_state.value != TripStartState.AskType) return
        work { TripStartState.Ready(tripManager.startTrip(type, p.profile, p.stops.toTripStops()).id) }
    }

    fun continueTrip() {
        val p = plan ?: return
        val ask = _state.value as? TripStartState.AskContinue ?: return
        work {
            val tripId = ask.trip.id
            val remaining = tripManager.remainingStops(tripId).map { it.name to it.point }
            if (remaining != p.stops.map { it.name to it.point }) tripManager.changeStops(tripId, p.stops.toTripStops())
            TripStartState.Ready(tripId)
        }
    }

    /** [새로 시작]: the new trip's type is asked next; the active trip is completed when it starts. */
    fun startNew() {
        if (_state.value is TripStartState.AskContinue) _state.value = TripStartState.AskType
    }

    fun dismiss() {
        when (_state.value) {
            TripStartState.AskType, is TripStartState.AskContinue, is TripStartState.Error ->
                _state.value = TripStartState.Idle
            else -> Unit
        }
    }

    fun consumeReady() {
        if (_state.value is TripStartState.Ready) {
            plan = null
            _state.value = TripStartState.Idle
        }
    }

    private fun work(block: suspend () -> TripStartState) {
        _state.value = TripStartState.Working
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TripStartState.Error(MSG_FAILED)
            }
        }
    }

    private suspend fun destinationName(tripId: Long): String {
        val stops = tripStore.stops(tripId)
        return (stops.firstOrNull { it.isDestination } ?: stops.lastOrNull())?.name ?: "목적지"
    }

    companion object {
        const val MSG_FAILED = "여행을 시작하지 못했습니다"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TripStartViewModel(container.tripManager, container.tripStore) }
        }
    }
}
