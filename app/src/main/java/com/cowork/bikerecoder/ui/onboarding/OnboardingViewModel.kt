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
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

/** Persisted "나중에" choices. */
interface OnboardingSkipStore {
    val skipped: Flow<Set<OnboardingStep>>
    suspend fun update(transform: (Set<OnboardingStep>) -> Set<OnboardingStep>)
}

class SettingsOnboardingSkipStore(private val settings: SettingsRepository) : OnboardingSkipStore {
    override val skipped: Flow<Set<OnboardingStep>> = settings.settings.map { it.skippedOnboardingSteps }
    override suspend fun update(transform: (Set<OnboardingStep>) -> Set<OnboardingStep>) {
        settings.update { it.copy(skippedOnboardingSteps = transform(it.skippedOnboardingSteps)) }
    }
}

data class OnboardingUiState(
    val permissions: PermissionState,
    /** False until the persisted skip set has been read; the screen shows nothing before that. */
    val skipLoaded: Boolean = false,
    val skipped: Set<OnboardingStep> = emptySet(),
    val download: SegmentDownload = SegmentDownload.Idle,
) {
    val step: OnboardingStep get() = effectiveOnboardingStep(permissions, skipped)
}

class OnboardingViewModel(
    private val readState: () -> PermissionState,
    private val segments: SegmentRepository,
    private val skipStore: OnboardingSkipStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState(readState()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            skipStore.skipped.collect { skipped ->
                _state.update { it.copy(skipLoaded = true, skipped = skipped) }
            }
        }
    }

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
                var shownTenthsOfMb = -1L
                val result = segments.download(name) { read, total ->
                    // Only publish when the displayed value (0.1 MB) changes, not on every 64 KB buffer.
                    val tenths = read * 10 / BYTES_PER_MB
                    if (tenths != shownTenthsOfMb) {
                        shownTenthsOfMb = tenths
                        _state.update {
                            it.copy(download = SegmentDownload.Downloading(i + 1, missing.size, read, total))
                        }
                    }
                }
                if (result.isFailure) {
                    _state.update { it.copy(download = SegmentDownload.Failed) }
                    return@launch
                }
            }
            _state.update { it.copy(download = SegmentDownload.Idle, permissions = readState()) }
            skipStore.update { it - OnboardingStep.SEGMENTS }
        }
    }

    /**
     * [나중에]: remember the choice and move on. Fine location is mandatory and cannot be skipped.
     * Skipping route data leaves the plan screen's start button disabled.
     */
    fun skipStep(step: OnboardingStep) {
        if (step == OnboardingStep.FINE_LOCATION || step == OnboardingStep.DONE) return
        if (step == OnboardingStep.SEGMENTS) {
            downloadJob?.cancel()
            _state.update { it.copy(download = SegmentDownload.Idle) }
        }
        viewModelScope.launch { skipStore.update { it + step } }
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    readState = { readPermissionState(container.appContext, container.segments) },
                    segments = container.segments,
                    skipStore = SettingsOnboardingSkipStore(container.settings),
                )
            }
        }
    }
}
