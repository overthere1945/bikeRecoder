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
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.ui.trip.EndAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    log: WarnLog = androidWarnLog(TAG),
) : ViewModel() {

    val ui: StateFlow<NavUiState> = controller.ui
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

    /** Call right after starting the foreground service (from the visible screen). */
    fun begin(tripId: Long) {
        controller.begin(tripId)
    }

    fun setMuted(muted: Boolean) = controller.setMuted(muted)

    fun onEndChoice(action: EndAction) {
        when (action) {
            EndAction.STOP_TODAY -> controller.stopToday()
            EndAction.COMPLETE_TRIP -> viewModelScope.launch { controller.completeTrip() }
        }
    }

    fun convertToMultiDay() {
        viewModelScope.launch { controller.convertToMultiDay() }
    }

    /** Leave guidance without changing the trip (cancel a start, close an error or a finished trip). */
    fun close() = controller.close()

    fun ttsNoticeShown() {
        _ttsNotice.value = false
    }

    companion object {
        const val ARG_TRIP = "trip"
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
                )
            }
        }
    }
}
