package com.cowork.bikerecoder.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.trip.StartPrompt
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.GuidanceRecording
import com.cowork.bikerecoder.nav.guidingTripId
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.recordingTripId
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.plan.PlannedStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The plan being started: its profile and stops (last = destination). */
data class StartPlan(val profile: RouteProfile, val stops: List<PlannedStop>)

/** What a trip recorded so far: [days] different dates with a ride and [distanceM] in all (empty segments not counted). */
data class PastRecords(val days: Int, val distanceM: Double)

sealed interface TripStartState {
    data object Idle : TripStartState
    data object Working : TripStartState

    /**
     * "어떻게 기록할까요?" [여행 이어서 · {day}일째] [새 기록 시작] [취소]: the active multi-day [trip], named [destinationName]
     * ([ActivityText.recordName] without stops), on its [day]-th day ([TripManager.dayNumber]); [past] is what it recorded so far
     * (null if that could not be read).
     */
    data class AskContinue(
        val trip: Trip,
        val destinationName: String,
        val day: Int = 1,
        val past: PastRecords? = null,
    ) : TripStartState

    /** "기록을 어떻게 할까요?" [이어서 기록] [새 구간으로 나누기] [취소] */
    data object AskRecording : TripStartState

    /**
     * The trip to guide; the caller starts navigation and calls [TripStartViewModel.consumeReady]. [recordNotice]: the
     * recording ended for this start could not be fully saved ("기록 저장에 문제가 있습니다"); tell the user once.
     */
    data class Ready(val tripId: Long, val recordNotice: String? = null) : TripStartState

    /**
     * A finished recording's summary has not been seen: the caller shows it (the record screen) and calls
     * [TripStartViewModel.summaryShown]; nothing was started.
     */
    data object ShowSummary : TripStartState
    data class Error(val message: String) : TripStartState
}

/** [PastRecords] of a trip's segments: the dates and the distance of the ones that went somewhere. */
fun pastRecordsOf(segments: List<RideSegment>): PastRecords {
    val rides = segments.filterNot { it.isEmpty }
    return PastRecords(days = rides.map { it.localDate }.distinct().size, distanceM = rides.sumOf { it.totals.distanceM })
}

/** The activity of a trip's last segment (the latest start; a mixed trip counts its last one); null without segments. */
fun lastActivityOf(segments: List<RideSegment>): ActivityType? = segments.maxByOrNull { it.startedAt }?.activity

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
 * [안내 시작] on the plan screen → which trip to guide (spec §5, V1.9.1): no active multi-day trip → a new
 * [TripType.SINGLE_DAY] trip starts at once, without a question (completing any active one; whether it becomes a
 * multi-day trip is chosen when it ends, "내일 이어서 하기"); an active multi-day trip → "어떻게 기록할까요?":
 * [여행 이어서] (its stops are replaced only if the plan differs from its unvisited stops, and it takes the plan's
 * profile) or [새 기록 시작] (a new single-day trip, the active one is completed).
 *
 * [● 기록 시작] on the map main screen ([onRecordPressed]) does the same for a recording without a plan: a new trip
 * has no stops and [defaultProfile]; [여행 이어서] keeps the trip's stops and profile as they are. A press while a
 * recording already runs (the button had not caught up) opens that recording ([TripStartState.Ready] of its trip)
 * instead of ending it.
 *
 * [안내 시작] while a recording runs (spec §4.2) does not ask to continue another trip: the plan joins the recorded
 * trip ([attach]: a guidance of that trip is closed first, the trip takes the plan's profile and, if they differ, its
 * stops) and the recording goes on or is split into a new segment as [recordingMode] says (ASK:
 * [TripStartState.AskRecording]). A split is finished (the new segment records) before [TripStartState.Ready]: the
 * guidance must begin into the running recording to belong to it (ruling U1).
 *
 * A recording of another trip ([recordingTrip]) that started while a question was open is ended by [endRecording] (a
 * segment only, no summary) before the new trip starts or another trip is continued (ruling U2): starting a trip
 * completes the active one, which must not happen under a live recording, and the new guidance then records its own
 * trip. Stopping is preferred over refusing the start: the user has already chosen to end that trip, and the segment
 * keeps every point recorded so far. A save problem of that recording comes with [TripStartState.Ready.recordNotice].
 * A summary not seen yet ([pendingSummary]) is shown first ([TripStartState.ShowSummary]), like [● 기록 시작] does.
 */
class TripStartViewModel(
    private val tripManager: TripManager,
    private val tripStore: TripStore,
    private val log: WarnLog = androidWarnLog(TAG),
    /** The settings' route profile, for trips started by [onRecordPressed]. */
    private val defaultProfile: suspend () -> RouteProfile = { RouteProfile.CYCLEWAY_FIRST },
    /** The trip being recorded (starting or running), null if none. */
    private val recordingTrip: () -> Long? = { null },
    /** Ends that recording as a segment only ([com.cowork.bikerecoder.ride.RideController.endForAnotherTrip]); its save error. */
    private val endRecording: suspend () -> String? = { null },
    /** A finished recording's summary waits to be seen: it is shown before anything starts. */
    private val pendingSummary: () -> Boolean = { false },
    /** The message of a recording whose start failed ([RideUiState.Failed]); null if none. */
    private val recordingFailure: () -> String? = { null },
    /** The setting "안내를 새로 시작할 때 기록". */
    private val recordingMode: suspend () -> GuidanceRecording = { GuidanceRecording.ASK },
    /** Closes the running segment and records a new one of the same trip ([RideController.splitSegment]); its save error. */
    private val splitSegment: suspend () -> String? = { null },
    /** 그 여행을 안내하는 중이면 닫는다(begin이 같은 여행이면 아무것도 하지 않으므로 새 경유지가 반영되도록). */
    private val closeGuidanceOf: (tripId: Long) -> Unit = {},
    /** What a trip recorded so far ([PastRecords]), for [TripStartState.AskContinue]; null if unknown. */
    private val pastRecords: suspend (tripId: Long) -> PastRecords? = { null },
    /** The activity of a trip's last segment ([lastActivityOf]), for naming a trip without stops; null if unknown. */
    private val tripActivity: suspend (tripId: Long) -> ActivityType? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow<TripStartState>(TripStartState.Idle)
    val state: StateFlow<TripStartState> = _state.asStateFlow()

    private var plan: StartPlan? = null

    /** The flow was started by [onRecordPressed]: the trip's stops and profile are left alone. */
    private var recordOnly = false

    /** The trip that was recording when [TripStartState.AskRecording] was asked. */
    private var askedRecording: Long? = null

    fun onStartPressed(plan: StartPlan) {
        if (_state.value != TripStartState.Idle || plan.stops.isEmpty()) return
        if (pendingSummary()) {
            _state.value = TripStartState.ShowSummary
            return
        }
        this.plan = plan
        recordOnly = false
        val recording = recordingTrip()
        if (recording == null) {
            work { prompt() }
            return
        }
        // The plan joins the trip being recorded: continuing another trip is not asked.
        askedRecording = recording
        work {
            when (recordingMode()) {
                GuidanceRecording.ASK -> TripStartState.AskRecording
                GuidanceRecording.CONTINUE -> attach(recording, split = false)
                GuidanceRecording.SPLIT -> attach(recording, split = true)
            }
        }
    }

    /** [이어서 기록]: the plan joins the recorded trip, the segment goes on. */
    fun keepRecording() = answerRecording(split = false)

    /** [새 구간으로 나누기]: the plan joins the recorded trip, which records a new segment. */
    fun splitRecording() = answerRecording(split = true)

    private fun answerRecording(split: Boolean) {
        if (_state.value != TripStartState.AskRecording) return
        val asked = askedRecording
        askedRecording = null
        work {
            // The recording ended while asking: the usual start instead.
            if (asked == null || recordingTrip() != asked) prompt() else attach(asked, split)
        }
    }

    /**
     * Puts the plan on the recorded trip [tripId] and, if [split], waits for the new segment to record — only then may
     * the guidance begin (it joins the recording, ruling U1). A split that ends without a running recording starts no
     * guidance: a stop during the split shows its summary, a new segment that could not start says so.
     */
    private suspend fun attach(tripId: Long, split: Boolean): TripStartState {
        val p = checkNotNull(plan)
        // A guidance of this trip is closed first: begin() ignores the trip it already guides, so the new stops count.
        closeGuidanceOf(tripId)
        changePlan(tripId, p)
        if (!split) return TripStartState.Ready(tripId)
        val notice = splitSegment()
        if (recordingTrip() == tripId) return TripStartState.Ready(tripId, notice)
        if (pendingSummary()) return TripStartState.ShowSummary
        return TripStartState.Error(recordingFailure() ?: MSG_FAILED)
    }

    /** [tripId] takes [p]'s profile and, only if they differ from its unvisited stops, its stops. */
    private suspend fun changePlan(tripId: Long, p: StartPlan) {
        // The route is computed from the trip's profile: the one chosen on the plan screen wins.
        tripManager.changeProfile(tripId, p.profile)
        // Replacing the stops deletes the trip's offline maps: only when the plan differs.
        val remaining = tripManager.remainingStops(tripId).map { it.name to it.point }
        if (remaining != p.stops.map { it.name to it.point }) tripManager.changeStops(tripId, p.stops.toTripStops())
    }

    /** [● 기록 시작]: which trip to record (no plan, so the plan-start's empty-stops guard does not apply). */
    fun onRecordPressed() {
        if (_state.value != TripStartState.Idle) return
        recordOnly = true
        work {
            plan = StartPlan(defaultProfile(), emptyList())
            // Already recording (the button had not caught up): that recording is shown, never ended.
            recordingTrip()?.let { return@work TripStartState.Ready(it) }
            prompt()
        }
    }

    /** No multi-day trip to continue: a new single-day trip starts at once; otherwise continuing it is asked. */
    private suspend fun prompt(): TripStartState = when (val prompt = tripManager.startPrompt()) {
        StartPrompt.StartNew -> startNewTrip()
        is StartPrompt.AskContinue -> askContinue(prompt.trip)
    }

    private suspend fun askContinue(trip: Trip): TripStartState.AskContinue {
        val past = try {
            pastRecords(trip.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the subtitle is shorter without it.
            log.warn("Reading the records of trip ${trip.id} failed", e)
            null
        }
        return TripStartState.AskContinue(trip, destinationName(trip.id), tripManager.dayNumber(trip), past)
    }

    /**
     * A new [TripType.SINGLE_DAY] trip of the plan (completing the active one). Whatever is recorded then belongs to
     * another trip: it is ended first (ruling U2).
     */
    private suspend fun startNewTrip(): TripStartState {
        val p = checkNotNull(plan)
        val notice = if (recordingTrip() != null) endRecording() else null
        return TripStartState.Ready(tripManager.startTrip(TripType.SINGLE_DAY, p.profile, p.stops.toTripStops()).id, notice)
    }

    fun continueTrip() {
        val p = plan ?: return
        val ask = _state.value as? TripStartState.AskContinue ?: return
        work {
            val tripId = ask.trip.id
            val notice = if (recordingTrip().let { it != null && it != tripId }) endRecording() else null
            // Recording only: the trip keeps its stops and profile.
            if (recordOnly) return@work TripStartState.Ready(tripId, notice)
            changePlan(tripId, p)
            TripStartState.Ready(tripId, notice)
        }
    }

    /** [새 기록 시작]: a new single-day trip starts; the active multi-day trip is completed by that start. */
    fun startNew() {
        if (plan == null || _state.value !is TripStartState.AskContinue) return
        work { startNewTrip() }
    }

    fun dismiss() {
        when (_state.value) {
            is TripStartState.AskContinue, TripStartState.AskRecording, is TripStartState.Error -> {
                recordOnly = false
                askedRecording = null
                _state.value = TripStartState.Idle
            }
            else -> Unit
        }
    }

    /** The caller opened the summary of [TripStartState.ShowSummary]. */
    fun summaryShown() {
        if (_state.value == TripStartState.ShowSummary) _state.value = TripStartState.Idle
    }

    fun consumeReady() {
        if (_state.value is TripStartState.Ready) {
            plan = null
            recordOnly = false
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
                log.warn("Starting a trip failed", e)
                TripStartState.Error(MSG_FAILED)
            }
        }
    }

    private suspend fun destinationName(tripId: Long): String {
        val stops = tripStore.stops(tripId)
        // A trip started for recording only has no stops: it is named after what it recorded.
        if (stops.isEmpty()) return ActivityText.recordName(activityOrNull(tripId))
        return (stops.firstOrNull { it.isDestination } ?: stops.last()).name
    }

    private suspend fun activityOrNull(tripId: Long): ActivityType? = try {
        tripActivity(tripId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Only the name is more general without it.
        log.warn("Reading the activity of trip $tripId failed", e)
        null
    }

    companion object {
        const val MSG_FAILED = "여행을 시작하지 못했습니다"
        private const val TAG = "TripStartViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TripStartViewModel(
                    container.tripManager,
                    container.tripStore,
                    defaultProfile = { container.settings.settings.first().defaultProfile },
                    // Read without creating the ride controller; it exists whenever something records.
                    recordingTrip = { container.rideState.recordingTripId },
                    endRecording = { container.ride.endForAnotherTrip() },
                    pendingSummary = { container.rideState is RideUiState.Finished },
                    recordingFailure = { (container.rideState as? RideUiState.Failed)?.message },
                    recordingMode = { container.settings.settings.first().recordingOnNewGuidance },
                    // Only called while a recording runs, so the ride controller exists.
                    splitSegment = { container.ride.splitSegment() },
                    closeGuidanceOf = { id -> if (container.navigationState.guidingTripId == id) container.navigation.close() },
                    pastRecords = { id -> pastRecordsOf(container.rideStore.segments(id)) },
                    tripActivity = { id -> lastActivityOf(container.rideStore.segments(id)) },
                )
            }
        }
    }
}
