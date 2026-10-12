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
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The record screen's view of the app-wide [RideController]. The screen never owns the recording: leaving it (or the
 * UI being destroyed) does not stop it.
 *
 * @param savedState carries [ARG_TRIP], the trip to start recording once the screen is resumed (-1 = none).
 */
class RecordViewModel(
    private val ride: RideController,
    navigation: StateFlow<NavUiState>,
    keepScreenOn: Flow<Boolean>,
    private val savedState: SavedStateHandle,
    /** "화면이 꺼지면 안내가 멈출 수 있습니다" when the location foreground service could not start. */
    val serviceWarning: StateFlow<String?> = MutableStateFlow(null),
) : ViewModel() {

    val ui: StateFlow<RideUiState> = ride.ui

    /** Guidance is starting or running: [정지] then ends only the recording. */
    val navigating: StateFlow<Boolean> = navigation.map(NavNotifications::isGuiding)
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavNotifications.isGuiding(navigation.value))

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
                )
            }
        }
    }
}
