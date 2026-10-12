package com.cowork.bikerecoder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.trip.RIDE_RECORD_NAME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** The trip banner's button. */
enum class BannerAction(val label: String) {
    /** Guide the trip's remaining stops. */
    RESUME("이어서 안내"),

    /** Nothing left to guide (e.g. converted after arriving): pick the next destination for the same trip (R21). */
    NEXT_DESTINATION("다음 목적지 정하기"),
}

/** "🚩 {destinationName} · 여러 날 {dayNumber}일차" banner for the active multi-day trip. */
data class TripBanner(val tripId: Long, val destinationName: String, val dayNumber: Int, val action: BannerAction) {
    val text: String get() = "🚩 $destinationName · 여러 날 ${dayNumber}일차"
}

/** A trip left idle for 3+ days, asked about once when the main screen first opens. */
data class StaleTrip(val tripId: Long, val destinationName: String)

/** The map point the user long-pressed, with its address lookup. */
data class PressedPlace(val point: GeoPoint, val address: String?, val loading: Boolean) {
    /** Address when known, else the coordinates (also the fallback when the lookup failed). */
    val title: String get() = address ?: String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)
}

/** Which screen the riding banner's [보기] opens. */
enum class RideTarget { NAVIGATE, RECORD }

/** "● 주행 중 · …" [보기] while guidance or a recording is starting or running (spec §3.1). */
data class RidingBanner(val text: String, val target: RideTarget)

/** A recording that never stopped (its segment is still open), offered as "기록이 중단되었습니다". */
data class InterruptedRide(val segmentId: Long, val tripId: Long)

data class MainUiState(
    val banner: TripBanner? = null,
    val staleTrip: StaleTrip? = null,
    val pressed: PressedPlace? = null,
    /** Guidance is starting or running (any trip type). */
    val guiding: Boolean = false,
    val riding: RidingBanner? = null,
    /** A recording is starting or running: [● 기록 시작] is hidden. */
    val recording: Boolean = false,
    val interrupted: InterruptedRide? = null,
    /** The interrupted-recording check has not answered yet, or its [종료] is still closing the segment. */
    val recoveryBusy: Boolean = false,
    /**
     * A finished recording's summary has not been seen yet (e.g. guidance arrival stopped the recording while another
     * screen showed): it is shown before anything else records.
     */
    val pendingSummary: Boolean = false,
) {
    /** The trip banner gives way to the riding banner. */
    val visibleBanner: TripBanner? get() = banner.takeUnless { guiding || riding != null }

    /**
     * [● 기록 시작] may start a recording: none runs, the interrupted recording was answered (closing it would
     * race the new start) and no summary waits to be seen (a new start would replace it).
     */
    val canStartRecording: Boolean get() = !recording && !recoveryBusy && interrupted == null && !pendingSummary
}

class MainViewModel(
    private val tripManager: TripManager,
    private val tripStore: TripStore,
    private val placeSearch: PlaceSearch,
    private val guidance: Flow<NavUiState>,
    private val ride: Flow<RideUiState> = flowOf(RideUiState.Idle),
    /** The most recent open segment (see [com.cowork.bikerecoder.ride.RideController.interrupted]). */
    private val interrupted: suspend () -> RideSegment? = { null },
    /** [종료] on the interrupted recording ([com.cowork.bikerecoder.ride.RideController.closeInterrupted]). */
    private val closeInterrupted: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState(recoveryBusy = true))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var addressJob: Job? = null

    init {
        viewModelScope.launch {
            val stale = runCatchingNonCancel { tripManager.stalePrompt() }
            _state.update { it.copy(staleTrip = stale?.let { t -> StaleTrip(t.id, destinationName(t.id)) }) }
        }
        refreshBanner()
        viewModelScope.launch {
            combine(guidance, ride) { nav, r -> riding(nav, r) }
                .distinctUntilChanged()
                .collect { (guiding, recording, riding, pendingSummary) ->
                    val before = _state.value
                    _state.update {
                        it.copy(
                            guiding = guiding,
                            recording = recording,
                            riding = riding,
                            pendingSummary = pendingSummary,
                            // A new recording closes the leftover segment itself; while guiding it is not offered.
                            interrupted = it.interrupted.takeUnless { guiding || recording },
                        )
                    }
                    // Guidance or recording ended (arrived, stopped for today, ...): the trip may have changed.
                    if ((before.guiding && !guiding) || (before.recording && !recording)) refreshBanner()
                }
        }
        viewModelScope.launch { checkInterrupted() }
    }

    private data class Riding(
        val guiding: Boolean,
        val recording: Boolean,
        val banner: RidingBanner?,
        val pendingSummary: Boolean,
    )

    /** A starting recording shows zero distance and time; a summary or a start error is not riding. */
    private fun riding(nav: NavUiState, rideState: RideUiState): Riding {
        val guiding = nav is NavUiState.Starting || nav is NavUiState.Active
        val stats = when (rideState) {
            is RideUiState.Active -> rideState.stats
            is RideUiState.Starting -> RideStats.ZERO
            else -> null
        }
        val target = if (guiding) RideTarget.NAVIGATE else RideTarget.RECORD
        val banner = when {
            stats != null ->
                RidingBanner("● 주행 중 · ${RideFormat.distance(stats.distanceM)} · ${RideFormat.clock(stats.movingTimeMs)}", target)
            guiding -> RidingBanner("● 주행 중 · 안내 중", target)
            else -> null
        }
        return Riding(guiding, stats != null, banner, pendingSummary = rideState is RideUiState.Finished)
    }

    /** Spec §4.6: offered once, when the main screen opens with nothing riding. */
    private suspend fun checkInterrupted() {
        try {
            // The first state of each flow (the collector above may not have run yet).
            if (riding(guidance.first(), ride.first()).banner != null) return
            val segment = runCatchingNonCancel { interrupted() } ?: return
            val now = _state.value
            if (now.guiding || now.recording) return
            _state.update { it.copy(interrupted = InterruptedRide(segment.id, segment.tripId)) }
        } finally {
            _state.update { it.copy(recoveryBusy = false) }
        }
    }

    /** [종료] on "기록이 중단되었습니다": closes the segment and ends its trip (ruling P2). */
    fun closeInterruptedRide() {
        if (_state.value.interrupted == null) return
        _state.update { it.copy(interrupted = null, recoveryBusy = true) }
        viewModelScope.launch {
            try {
                runCatchingNonCancel { closeInterrupted() }
            } finally {
                _state.update { it.copy(recoveryBusy = false) }
            }
            refreshBanner()
        }
    }

    /** [이어서 기록]: the record screen starts a new segment of the same trip (that start closes the old one). */
    fun dismissInterrupted() {
        _state.update { it.copy(interrupted = null) }
    }

    /** Re-reads the active multi-day trip (e.g. after returning from navigation or completing a trip). */
    fun refreshBanner() {
        viewModelScope.launch {
            val banner = runCatchingNonCancel {
                tripStore.activeTrip()?.takeIf { it.type == TripType.MULTI_DAY }?.let { trip ->
                    // Never offer [이어서 안내] for a trip with nothing left to guide.
                    val action = if (tripManager.remainingStops(trip.id).isEmpty()) {
                        BannerAction.NEXT_DESTINATION
                    } else {
                        BannerAction.RESUME
                    }
                    TripBanner(trip.id, destinationName(trip.id), tripManager.dayNumber(trip), action)
                }
            }
            _state.update { it.copy(banner = banner) }
        }
    }

    /** [완료] on the stale-trip dialog. */
    fun completeStaleTrip() {
        val stale = _state.value.staleTrip ?: return
        _state.update { it.copy(staleTrip = null) }
        viewModelScope.launch {
            runCatchingNonCancel { tripManager.complete(stale.tripId) }
            refreshBanner()
        }
    }

    /** [계속 유지]. */
    fun dismissStaleTrip() {
        _state.update { it.copy(staleTrip = null) }
    }

    fun onLongPress(point: GeoPoint) {
        addressJob?.cancel()
        _state.update { it.copy(pressed = PressedPlace(point, address = null, loading = true)) }
        addressJob = viewModelScope.launch {
            val address = try {
                (placeSearch.addressOf(point) as? SearchResult.Ok)?.value?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { s -> s.copy(pressed = s.pressed?.takeIf { it.point == point }?.copy(address = address, loading = false)) }
        }
    }

    fun dismissPressed() {
        addressJob?.cancel()
        _state.update { it.copy(pressed = null) }
    }

    private suspend fun destinationName(tripId: Long): String {
        val stops = runCatchingNonCancel { tripStore.stops(tripId) }.orEmpty()
        // A trip started for recording only has no stops.
        if (stops.isEmpty()) return RIDE_RECORD_NAME
        return (stops.firstOrNull { it.isDestination } ?: stops.last()).name
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "MainViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val log = androidWarnLog(TAG)
                MainViewModel(
                    container.tripManager,
                    container.tripStore,
                    container.placeSearch,
                    container.navigationUi,
                    ride = container.rideUi,
                    // Read without creating the ride controller (it would create the guidance and bind the TTS
                    // engine); without one nothing records, so every open segment is interrupted.
                    interrupted = {
                        if (container.rideState != RideUiState.Idle) {
                            null
                        } else {
                            try {
                                container.rideStore.openSegments().firstOrNull()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("Could not read the open segments", e)
                                null
                            }
                        }
                    },
                    closeInterrupted = { container.ride.closeInterrupted() },
                )
            }
        }
    }
}
