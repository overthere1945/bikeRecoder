package com.cowork.bikerecoder.ui.navigate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavNotifications
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.offline.OfflineProgress
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideControls
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.ScreenNotice
import com.cowork.bikerecoder.ride.recordingTripId
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.ui.record.SummaryExtras
import com.cowork.bikerecoder.ui.record.SummaryExtrasState
import com.cowork.bikerecoder.ui.trip.EndAction
import com.cowork.bikerecoder.ui.trip.canConvertToMultiDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The guidance screen's view of the app-wide [NavigationController]. The screen itself never owns the
 * session: leaving it (or the UI being destroyed) does not stop guidance.
 *
 * @param savedState carries [ARG_TRIP], the trip to start once the screen is resumed (-1 = none).
 * @param ttsNoticeShown / [markTtsNoticeShown]: the persisted "Korean TTS missing" hint flag (shown once ever).
 */
class NavigateViewModel(
    private val controller: NavigationController,
    voice: VoiceOutput,
    keepScreenOn: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    ttsNoticeShown: Flow<Boolean>,
    markTtsNoticeShown: suspend () -> Unit,
    /** The corridor map download of the running trip (null = nothing to show). */
    val offlineProgress: StateFlow<OfflineProgress?> = MutableStateFlow(null),
    log: WarnLog = androidWarnLog(TAG),
    /** "화면이 꺼지면 안내가 멈출 수 있습니다" when the location foreground service could not start. */
    val serviceWarning: StateFlow<String?> = MutableStateFlow(null),
    /** The app-wide recording (shown with the guidance; its summary when both end). */
    val rideUi: StateFlow<RideUiState> = MutableStateFlow(RideUiState.Idle),
    /** Closes the recording's summary ([com.cowork.bikerecoder.ride.RideController.dismissSummary]). */
    private val dismissRideSummary: () -> Unit = {},
    /** The recording's commands for the controls on the numbers page (ruling F2). */
    private val rideControls: RideControls = RideControls.None,
    /** The recording's on-screen notice ([com.cowork.bikerecoder.ride.RideController.notice]). */
    val rideNotice: StateFlow<ScreenNotice?> = MutableStateFlow(null),
    private val phrases: KoreanPhrases = KoreanPhrases(),
    /** With [postProcessor]: the segment summary's extras ([summaryExtras], ruling R9). */
    postStore: PostStore,
    postProcessor: PostProcessor,
) : ViewModel() {

    val ui: StateFlow<NavUiState> = controller.ui

    /**
     * "사진 N장" (and later the heart rate) of the segment summary shown on the finished screen, and its pass when it
     * appears — the same as the record screen's summary ([SummaryExtras], ruling R9).
     */
    val summaryExtras: StateFlow<SummaryExtrasState> = SummaryExtras(
        summarySegment = combine(controller.ui, rideUi) { nav, ride -> shownSummary(nav, ride)?.segment?.id },
        postStore = postStore,
        postProcessor = postProcessor,
        scope = viewModelScope,
    ).state

    /** A recording is starting or running: [종료] offers to end the guidance only, and the recording goes on. */
    val recording: StateFlow<Boolean> = rideUi.map(::isRecording)
        .stateIn(viewModelScope, SharingStarted.Eagerly, isRecording(rideUi.value))

    /** 안내 화면이 보일 알림: 안내가 Starting/Active일 때만(그 밖에는 기록 화면 몫). */
    val noticeForScreen: StateFlow<ScreenNotice?> = combine(controller.ui, rideNotice, ::guidanceNotice)
        .stateIn(viewModelScope, SharingStarted.Eagerly, guidanceNotice(controller.ui.value, rideNotice.value))

    val keepScreenOn: StateFlow<Boolean> = keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _ttsNotice = MutableStateFlow(false)

    /** True while the "Korean TTS missing" snackbar should be shown. */
    val ttsNotice: StateFlow<Boolean> = _ttsNotice.asStateFlow()

    init {
        viewModelScope.launch {
            // The engine reports availability only after its asynchronous init; give it a moment.
            val ready = withTimeoutOrNull(TTS_GRACE_MS) { voice.available.first { it } }
            if (ready != null || ttsNoticeShown.first()) return@launch
            try {
                markTtsNoticeShown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not save that the TTS notice was shown", e) // Show it anyway.
            }
            _ttsNotice.value = true
        }
    }

    val hasPendingStart: Boolean
        get() = (savedState.get<Long>(ARG_TRIP) ?: -1L) >= 0 && savedState.get<Boolean>(KEY_STARTED) != true

    /** The trip to start, exactly once per navigation entry (survives recreation). */
    fun takePendingStart(): Long? {
        if (!hasPendingStart) return null
        savedState[KEY_STARTED] = true
        return savedState.get<Long>(ARG_TRIP)
    }

    /**
     * "이전 기록 저장에 문제가 있습니다" when the start ended a recording that was not fully saved ([ARG_RECORD_NOTICE]);
     * exactly once per navigation entry (survives recreation).
     */
    fun takeRecordNotice(): String? {
        if (savedState.get<Boolean>(ARG_RECORD_NOTICE) != true || savedState.get<Boolean>(KEY_NOTICED) == true) return null
        savedState[KEY_NOTICED] = true
        return MSG_PREVIOUS_RECORD_NOT_SAVED
    }

    /** Call right after starting the foreground service (from the visible screen). */
    fun begin(tripId: Long) {
        controller.begin(tripId)
    }

    fun setMuted(muted: Boolean) = controller.setMuted(muted)

    fun onEndChoice(action: EndAction) {
        when (action) {
            EndAction.STOP_TODAY -> controller.stopToday()
            EndAction.COMPLETE_TRIP -> viewModelScope.launch { controller.completeTrip() }
            // The trip stays as it is; the recording goes on (the screen then shows the record screen, which shows
            // the notice).
            EndAction.END_GUIDANCE_ONLY -> {
                controller.close()
                rideControls.announce(phrases.guidanceEndedRecordingGoesOn)
            }
        }
    }

    /** [안내 취소] (no confirmation): the same as the dialog's [안내만 끝내기 (기록 계속)]. */
    fun cancelGuidance() = onEndChoice(EndAction.END_GUIDANCE_ONLY)

    /** The screen showed the notice [id]. */
    fun noticeShown(id: Long) = rideControls.noticeShown(id)

    fun pauseRecording() = rideControls.pause()

    fun resumeRecording() = rideControls.resume()

    fun setRecordingAutoPause(enabled: Boolean) = rideControls.setAutoPause(enabled)

    /**
     * [기록 끝내기] on the numbers page: only the recording ends (its trip is the guidance's); the guidance goes on and the
     * summary waits for the end of the guidance (or the main screen). The stop finishes in the ride scope.
     */
    fun endRecording() {
        viewModelScope.launch { rideControls.stopRecording(RecordingEnd.SEGMENT_ONLY) }
    }

    /** Closes the "기록을 시작하지 못했습니다" banner ([guidedRideFailure]); anything else the recording shows stays. */
    fun dismissRideFailure() {
        if (rideUi.value is RideUiState.Failed) dismissRideSummary()
    }

    /** [여러 날로 바꾸기] on the finished screen; the summary shown with it is closed once the trip is converted. */
    fun convertToMultiDay() {
        val shown = shownSummary()
        viewModelScope.launch {
            controller.convertToMultiDay()
            if (shown != null && controller.ui.value == NavUiState.Idle) dismissSummary(shown)
        }
    }

    /**
     * [확인] or [닫기] (or back) on the finished screen: closes the recording's summary if it was on screen, then the
     * guidance. A summary not shown here (the error view, another trip's) stays for the main screen.
     */
    fun confirmFinished() {
        shownSummary()?.let(::dismissSummary)
        close()
    }

    /** The summary the finished screen shows right now ([finishedView]), if any. */
    private fun shownSummary(): RideUiState.Finished? = shownSummary(ui.value, rideUi.value)

    /** Only [summary] itself: a newer recording state is left alone. */
    private fun dismissSummary(summary: RideUiState.Finished) {
        if (rideUi.value === summary) dismissRideSummary()
    }

    /** Leave guidance without changing the trip (cancel a start, close an error or a finished trip). */
    fun close() = controller.close()

    fun ttsNoticeShown() {
        _ttsNotice.value = false
    }

    companion object {
        const val ARG_TRIP = "trip"

        /** True: the start ended a recording of another trip that could not be fully saved ([takeRecordNotice]). */
        const val ARG_RECORD_NOTICE = "recordNotice"
        const val MSG_PREVIOUS_RECORD_NOT_SAVED = "이전 기록 저장에 문제가 있습니다"
        private const val KEY_NOTICED = "recordNoticed"
        private const val KEY_STARTED = "started"
        const val TTS_GRACE_MS = 5_000L
        private const val TAG = "NavigateViewModel"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NavigateViewModel(
                    controller = container.navigation,
                    voice = container.voice,
                    keepScreenOn = container.settings.settings.map { it.keepScreenOn },
                    savedState = createSavedStateHandle(),
                    ttsNoticeShown = container.settings.settings.map { it.ttsNoticeShown },
                    markTtsNoticeShown = { container.settings.update { it.copy(ttsNoticeShown = true) } },
                    offlineProgress = container.offlineMaps.progress,
                    serviceWarning = container.guidanceService.warning,
                    // Starting the guidance creates the ride controller anyway (it follows the guidance).
                    rideUi = container.ride.ui,
                    dismissRideSummary = container.ride::dismissSummary,
                    rideControls = container.ride,
                    rideNotice = container.ride.notice,
                    postStore = container.postStore,
                    postProcessor = container.postProcessor,
                )
            }
        }
    }
}

/** The recording's summary the finished screen shows for [nav] and [ride] ([finishedView]), if any. */
private fun shownSummary(nav: NavUiState, ride: RideUiState): RideUiState.Finished? =
    (nav as? NavUiState.Finished)?.let { (finishedView(it, ride) as? FinishedView.Done)?.summary }

private fun isRecording(ride: RideUiState): Boolean = ride.recordingTripId != null

private fun guidanceNotice(nav: NavUiState, notice: ScreenNotice?): ScreenNotice? =
    notice?.takeIf { NavNotifications.isGuiding(nav) }

/** 안내가 Idle이 됐을 때 기록이 시작 중이거나 돌고 있으면 기록 화면으로(안내 취소, U1 도착), 아니면 메인으로. */
fun goesToRecordAfterGuidance(ride: RideUiState): Boolean = isRecording(ride)

/** The message of a recording of the guided trip [tripId] that could not start (shown as a banner); else null. */
fun guidedRideFailure(tripId: Long, ride: RideUiState): String? =
    (ride as? RideUiState.Failed)?.takeIf { it.tripId == tripId }?.message

/** What the guidance screen shows once guidance finished (spec §3.3, §3.4). */
sealed interface FinishedView {
    /** The recording of the guided trip is still stopping: its summary comes next. */
    data object Waiting : FinishedView

    /** Stopped for today without a summary: nothing to confirm (the main screen's banner offers [이어서 안내]). */
    data object Close : FinishedView

    /** The trip's new state was not saved: say so instead of reporting success. */
    data class Error(val message: String) : FinishedView

    /**
     * [summary] (the guided trip's segment, null if none) above "여행을 완료했습니다" when [completed], with
     * [여러 날로 바꾸기] when [canConvert], and [확인].
     */
    data class Done(val summary: RideUiState.Finished?, val completed: Boolean, val canConvert: Boolean) : FinishedView
}

/** The finished screen for [nav] while the recording is [ride]. */
fun finishedView(nav: NavUiState.Finished, ride: RideUiState): FinishedView {
    // Any finished guidance stops the recording of its trip; wait for that stop (also before closing for today).
    if (ride.recordingTripId == nav.tripId) return FinishedView.Waiting
    nav.error?.let { return FinishedView.Error(it) }
    val summary = (ride as? RideUiState.Finished)?.takeIf { it.segment.tripId == nav.tripId }
    return when {
        nav.reason != FinishReason.STOPPED_TODAY ->
            FinishedView.Done(summary, completed = true, canConvert = canConvertToMultiDay(nav))
        summary != null -> FinishedView.Done(summary, completed = false, canConvert = false)
        else -> FinishedView.Close
    }
}
