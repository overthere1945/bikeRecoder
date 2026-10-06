package com.cowork.bikerecoder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.offline.OfflineMapStorage
import com.cowork.bikerecoder.offline.SegmentInfo
import com.cowork.bikerecoder.offline.SegmentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is going on with one routing data file. At most one operation per file runs at a time. */
sealed interface SegmentStatus {
    data object Idle : SegmentStatus
    data object Checking : SegmentStatus
    data object UpdateAvailable : SegmentStatus
    data object UpToDate : SegmentStatus

    /** [totalBytes] is -1 while the server has not told the size. */
    data class Downloading(val readBytes: Long, val totalBytes: Long) : SegmentStatus
    data object Failed : SegmentStatus
}

/** Size of the offline maps stored for trips. */
sealed interface MapStorageState {
    data object Loading : MapStorageState
    data class Size(val bytes: Long) : MapStorageState
    data object Unknown : MapStorageState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val segments: List<SegmentInfo> = emptyList(),
    val segmentStatus: Map<String, SegmentStatus> = emptyMap(),
    val mapStorage: MapStorageState = MapStorageState.Loading,
    val deletingMaps: Boolean = false,
    val deleteMapsFailed: Boolean = false,
) {
    fun statusOf(name: String): SegmentStatus = segmentStatus[name] ?: SegmentStatus.Idle
}

class SettingsViewModel(
    settings: Flow<AppSettings>,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val segments: SegmentRepository,
    private val mapStorage: OfflineMapStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(segments = segments.list()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Running operation per segment name; touched on the main thread only. */
    private val segmentJobs = HashMap<String, Job>()
    private var mapJob: Job? = null

    init {
        viewModelScope.launch { settings.collect { s -> _state.update { it.copy(settings = s) } } }
        refreshMapSize()
    }

    // ---- toggles ----

    fun setProfile(profile: RouteProfile) = edit { it.copy(defaultProfile = profile) }
    fun setVoiceEnabled(enabled: Boolean) = edit { it.copy(voiceEnabled = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = edit { it.copy(keepScreenOn = enabled) }
    fun setWifiOnlyOfflineMaps(enabled: Boolean) = edit { it.copy(wifiOnlyOfflineMaps = enabled) }

    private fun edit(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { updateSettings(transform) }
    }

    // ---- routing data files ----

    /** Asks the server whether [name] has a newer version. Ignored while another operation on [name] runs. */
    fun checkUpdate(name: String) = runSegmentJob(name, SegmentStatus.Checking) {
        val newer = segments.hasUpdate(name)
        setStatus(name, if (newer) SegmentStatus.UpdateAvailable else SegmentStatus.UpToDate)
    }

    /** Downloads (or replaces) [name]. Ignored while another operation on [name] runs. */
    fun download(name: String) = runSegmentJob(name, SegmentStatus.Downloading(0L, -1L)) {
        var shownTenthsOfMb = -1L
        val result = segments.download(name) { read, total ->
            val tenths = read * 10 / BYTES_PER_MB
            if (tenths != shownTenthsOfMb) {
                shownTenthsOfMb = tenths
                setStatus(name, SegmentStatus.Downloading(read, total))
            }
        }
        setStatus(name, if (result.isSuccess) SegmentStatus.Idle else SegmentStatus.Failed)
        refreshSegments()
    }

    /** Removes [name]. Ignored while another operation on [name] runs. */
    fun delete(name: String) {
        if (segmentJobs[name]?.isActive == true) return
        segments.delete(name)
        setStatus(name, SegmentStatus.Idle)
        refreshSegments()
    }

    private fun runSegmentJob(name: String, initial: SegmentStatus, block: suspend () -> Unit) {
        if (segmentJobs[name]?.isActive == true) return
        setStatus(name, initial)
        segmentJobs[name] = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(name, SegmentStatus.Failed)
            }
        }
    }

    private fun setStatus(name: String, status: SegmentStatus) {
        _state.update { it.copy(segmentStatus = it.segmentStatus + (name to status)) }
    }

    private fun refreshSegments() {
        _state.update { it.copy(segments = segments.list()) }
    }

    // ---- offline maps ----

    fun refreshMapSize() {
        viewModelScope.launch {
            val size = try {
                MapStorageState.Size(mapStorage.totalBytes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MapStorageState.Unknown
            }
            _state.update { it.copy(mapStorage = size) }
        }
    }

    /** Deletes every stored trip map. Ignored while a deletion is running. */
    fun deleteAllMaps() {
        if (mapJob?.isActive == true) return
        _state.update { it.copy(deletingMaps = true, deleteMapsFailed = false) }
        mapJob = viewModelScope.launch {
            var failed = false
            try {
                mapStorage.deleteAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            _state.update { it.copy(deletingMaps = false, deleteMapsFailed = failed) }
            refreshMapSize()
        }
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = container.settings.settings,
                    updateSettings = { transform -> container.settings.update(transform) },
                    segments = container.segments,
                    mapStorage = container.offlineMaps,
                )
            }
        }
    }
}
