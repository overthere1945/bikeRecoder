package com.cowork.bikerecoder.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads the real system state. Cheap enough to call on the main thread (a few file existence checks). */
fun readPermissionState(context: Context, segments: SegmentRepository): PermissionState {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return PermissionState(
        fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        // POST_NOTIFICATIONS only exists from API 33; before that notifications need no runtime grant.
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
        segmentsReady = segments.hasRequired(),
    )
}

sealed interface SegmentDownload {
    data object Idle : SegmentDownload

    /** [index] is 1-based among the [count] files still being fetched; [totalBytes] is -1 when unknown. */
    data class Downloading(val index: Int, val count: Int, val readBytes: Long, val totalBytes: Long) : SegmentDownload

    data object Failed : SegmentDownload
}

data class OnboardingUiState(
    val permissions: PermissionState,
    val segmentsSkipped: Boolean = false,
    val download: SegmentDownload = SegmentDownload.Idle,
) {
    /** The step to show. Skipping the route-data step ("나중에") counts as finished. */
    val step: OnboardingStep
        get() = nextOnboardingStep(permissions).let {
            if (it == OnboardingStep.SEGMENTS && segmentsSkipped) OnboardingStep.DONE else it
        }
}

class OnboardingViewModel(
    private val readState: () -> PermissionState,
    private val segments: SegmentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(readState()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /** Re-reads permissions/battery/segments, e.g. when the user comes back from system Settings. */
    fun refresh() {
        _state.update { it.copy(permissions = readState()) }
    }

    /**
     * Downloads the REQUIRED segments that are missing, strictly one after another. A second call while a
     * download is running is ignored, so the same name is never downloaded twice concurrently.
     */
    fun downloadSegments() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            val missing = segments.list().filter { it.required && !it.installed }.map { it.name }
            for ((i, name) in missing.withIndex()) {
                _state.update { it.copy(download = SegmentDownload.Downloading(i + 1, missing.size, 0L, -1L)) }
                val result = segments.download(name) { read, total ->
                    _state.update {
                        it.copy(download = SegmentDownload.Downloading(i + 1, missing.size, read, total))
                    }
                }
                if (result.isFailure) {
                    _state.update { it.copy(download = SegmentDownload.Failed) }
                    return@launch
                }
            }
            _state.update { it.copy(download = SegmentDownload.Idle, permissions = readState()) }
        }
    }

    /** [나중에]: finish onboarding without route data (the plan screen keeps its start button disabled). */
    fun skipSegments() {
        downloadJob?.cancel()
        _state.update { it.copy(segmentsSkipped = true, download = SegmentDownload.Idle) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    readState = { readPermissionState(container.appContext, container.segments) },
                    segments = container.segments,
                )
            }
        }
    }
}
