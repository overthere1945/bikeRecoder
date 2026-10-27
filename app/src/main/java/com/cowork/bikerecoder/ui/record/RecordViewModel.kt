package com.cowork.bikerecoder.ui.record

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.nav.NavNotifications
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.ScreenNotice
import com.cowork.bikerecoder.ride.ShortRides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The record screen's view of the app-wide [RideController]. The screen never owns the recording: leaving it (or the
 * UI being destroyed) does not stop it.
 *
 * @param savedState carries [ARG_TRIP], the trip to start recording once the screen is resumed (-1 = none).
 *
 * The summary's extras (photo count, spec §2.3; heart rate, spec §3.3) and its post-processing pass (spec §1.1 ①) come
 * from [SummaryExtras].
 */
class RecordViewModel(
    private val ride: RideController,
    navigation: StateFlow<NavUiState>,
    keepScreenOn: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    /** "화면이 꺼지면 안내가 멈출 수 있습니다" when the location foreground service could not start. */
    val serviceWarning: StateFlow<String?> = MutableStateFlow(null),
    postStore: PostStore,
    postProcessor: PostProcessor,
    shortRides: ShortRides,
) : ViewModel() {

    val ui: StateFlow<RideUiState> = ride.ui

    private val extras = SummaryExtras(
        summarySegment = ride.ui.map { (it as? RideUiState.Finished)?.segment?.id },
        postStore = postStore,
        postProcessor = postProcessor,
        shortRides = shortRides,
        scope = viewModelScope,
    )

    /**
     * "사진 N장" and the heart rate of the summary; a pass when it appears, and the heart rate read again every minute
     * while it is on screen ([SummaryExtras]).
     */
    val summaryExtras: StateFlow<SummaryExtrasState> = extras.state

    /** [다시 가져오기] on the summary. */
    fun refetchHeartRate() = extras.refetchNow()

    /** The summary is (or no longer is) on a started screen ([SummaryExtras.onScreen]). */
    fun summaryOnScreen(shown: Boolean) = extras.onScreen(shown)

    /**
     * [버리기] on a short ride's summary (spec §6.10): deletes it, then [close]s the summary the screen's usual way — only
     * if that summary is still the one shown.
     */
    fun discardShortRide(close: () -> Unit) {
        val shown = ui.value as? RideUiState.Finished ?: return
        extras.discardShort { if (ui.value === shown) close() }
    }

    /** [저장] (or dismissed) on a short ride's summary: it stays. */
    fun keepShortRide() = extras.keepShort()

    /** Guidance is starting or running: [정지] then ends only the recording. */
    val navigating: StateFlow<Boolean> = navigation.map(NavNotifications::isGuiding)
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavNotifications.isGuiding(navigation.value))

    /** 기록 화면이 보일 알림: 기록이 Active일 때만. */
    val noticeForScreen: StateFlow<ScreenNotice?> = combine(ride.ui, ride.notice, ::recordNotice)
        .stateIn(viewModelScope, SharingStarted.Eagerly, recordNotice(ride.ui.value, ride.notice.value))

    val keepScreenOn: StateFlow<Boolean> = keepScreenOn.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hasPendingStart: Boolean
        get() = (savedState.get<Long>(ARG_TRIP) ?: -1L) >= 0 && savedState.get<Boolean>(KEY_STARTED) != true

    /** The trip to start recording, exactly once per navigation entry (survives recreation). */
    fun takePendingStart(): Long? {
        if (!hasPendingStart) return null
        savedState[KEY_STARTED] = true
        return savedState.get<Long>(ARG_TRIP)
    }

    /** Call right after starting the ride foreground service (from the visible screen). */
    fun start(tripId: Long) {
        ride.startRecording(tripId)
    }

    fun pause() = ride.pause()

    fun resume() = ride.resume()

    fun setAutoPause(enabled: Boolean) = ride.setAutoPause(enabled)

    /** The answer to "기록을 끝낼까요?"; the stop finishes in the ride scope even if this screen goes away. */
    fun onEndChoice(end: RecordingEnd) {
        viewModelScope.launch { ride.stopRecording(end) }
    }

    /** The screen showed the notice [id]. */
    fun noticeShown(id: Long) = ride.noticeShown(id)

    /** Closes the summary or the start error. */
    fun dismissSummary() = ride.dismissSummary()

    companion object {
        const val ARG_TRIP = "trip"
        private const val KEY_STARTED = "started"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RecordViewModel(
                    ride = container.ride,
                    navigation = container.navigation.ui,
                    keepScreenOn = container.settings.settings.map { it.keepScreenOn },
                    savedState = createSavedStateHandle(),
                    serviceWarning = container.guidanceService.warning,
                    postStore = container.postStore,
                    postProcessor = container.postProcessor,
                    shortRides = container.shortRides,
                )
            }
        }
    }
}

private fun recordNotice(ride: RideUiState, notice: ScreenNotice?): ScreenNotice? =
    notice?.takeIf { ride is RideUiState.Active }
