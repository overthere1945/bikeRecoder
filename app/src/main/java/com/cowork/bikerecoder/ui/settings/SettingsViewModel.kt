package com.cowork.bikerecoder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.data.GuidanceRecording
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.offline.OfflineMapStorage
import com.cowork.bikerecoder.offline.SegmentInfo
import com.cowork.bikerecoder.offline.SegmentRepository
import com.cowork.bikerecoder.offline.UpdateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is going on with one routing data file. At most one operation per file runs at a time. */
sealed interface SegmentStatus {
    data object Idle : SegmentStatus
    data object Checking : SegmentStatus
    data object UpdateAvailable : SegmentStatus
    data object UpToDate : SegmentStatus

    /** The server could not be asked (offline, HTTP error). */
    data object CheckFailed : SegmentStatus

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
    /** "가져온 기록 N개" (V1.6.0 spec §4.3); the row shows when above 0. */
    val importedCount: Int = 0,
    /** The GPX import job: "가져오는 중 12/35" on the row, [모두 지우기] dimmed while importing. */
    val importJob: ImportJobState = ImportJobState.Idle,
    val deletingImported: Boolean = false,
    /** "가져온 기록을 지웠습니다" is due (once, [SettingsViewModel.importedDeletedShown]). */
    val importedDeleted: Boolean = false,
) {
    /** The import job runs. */
    val importing: Boolean get() = importJob is ImportJobState.Importing

    fun statusOf(name: String): SegmentStatus = segmentStatus[name] ?: SegmentStatus.Idle
}

class SettingsViewModel(
    settings: Flow<AppSettings>,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val segments: SegmentRepository,
    private val mapStorage: OfflineMapStorage,
    /** How many trips were imported (V1.6.0 spec §4.3). */
    importedCount: Flow<Int> = flowOf(0),
    /** The one GPX import job's state. */
    importState: Flow<ImportJobState> = flowOf(ImportJobState.Idle),
    /** Deletes every imported trip; returns how many. */
    private val deleteImportedTrips: suspend () -> Int = { 0 },
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(segments = segments.list()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Running operation per segment name; touched on the main thread only. */
    private val segmentJobs = HashMap<String, Job>()
    private var mapJob: Job? = null
    private var sizeJob: Job? = null

    init {
        viewModelScope.launch { settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch { importedCount.collect { n -> _state.update { it.copy(importedCount = n) } } }
        viewModelScope.launch { importState.collect { s -> _state.update { it.copy(importJob = s) } } }
        refreshMapSize()
    }

    // ---- toggles ----

    fun setProfile(profile: RouteProfile) = edit { it.copy(defaultProfile = profile) }
    fun setVoiceEnabled(enabled: Boolean) = edit { it.copy(voiceEnabled = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = edit { it.copy(keepScreenOn = enabled) }
    fun setWifiOnlyOfflineMaps(enabled: Boolean) = edit { it.copy(wifiOnlyOfflineMaps = enabled) }
    fun setAutoStartRecording(enabled: Boolean) = edit { it.copy(autoStartRecordingWithNavigation = enabled) }
    fun setAutoPause(enabled: Boolean) = edit { it.copy(autoPauseEnabled = enabled) }
    fun setRecordingOnNewGuidance(mode: GuidanceRecording) = edit { it.copy(recordingOnNewGuidance = mode) }

    /** "사진·심박 연결" (spec §5.1): false lets the photo card show again ([허용]), true after a refusal. */
    fun setPhotoPromptDismissed(dismissed: Boolean) = edit { it.copy(photoPromptDismissed = dismissed) }

    /** "사진·심박 연결" (spec §5.1): false lets the heart-rate card show again ([허용]), true after a refusal. */
    fun setHeartRatePromptDismissed(dismissed: Boolean) = edit { it.copy(heartRatePromptDismissed = dismissed) }

    /** "공유" (spec §3.5): the default hashtags, already normalized; "" = none. */
    fun setShareHashtags(value: String) = edit { it.copy(shareHashtags = value) }

    private fun edit(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { updateSettings(transform) }
    }

    // ---- routing data files ----

    /** Asks the server whether [name] has a newer version. Ignored while another operation on [name] runs. */
    fun checkUpdate(name: String) = runSegmentJob(name, SegmentStatus.Checking) {
        setStatus(
            name,
            when (segments.checkUpdate(name)) {
                UpdateCheck.NEWER -> SegmentStatus.UpdateAvailable
                UpdateCheck.SAME -> SegmentStatus.UpToDate
                UpdateCheck.UNKNOWN -> SegmentStatus.CheckFailed
            },
        )
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

    /**
     * Lists the segments and measures the stored maps again: the settings tab keeps this view model while other screens
     * download or delete them (V1.4.0 spec §1.1), so it refreshes whenever the tab is shown.
     */
    fun refresh() {
        refreshSegments()
        refreshMapSize()
    }

    private fun refreshSegments() {
        _state.update { it.copy(segments = segments.list()) }
    }

    // ---- offline maps ----

    fun refreshMapSize() {
        sizeJob?.cancel()
        sizeJob = viewModelScope.launch {
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

    // ---- imported trips ----

    private var deleteImportedRun: Job? = null

    /**
     * [모두 지우기] (V1.6.0 spec §4.3) after its confirmation: every imported trip. Ignored while importing or deleting;
     * "가져온 기록을 지웠습니다" follows ([SettingsUiState.importedDeleted]).
     */
    fun deleteAllImported() {
        if (deleteImportedRun?.isActive == true || _state.value.importing) return
        _state.update { it.copy(deletingImported = true) }
        deleteImportedRun = viewModelScope.launch {
            val deleted = try {
                deleteImportedTrips()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(deletingImported = false, importedDeleted = deleted) }
        }
    }

    /** "가져온 기록을 지웠습니다" was shown. */
    fun importedDeletedShown() {
        _state.update { it.copy(importedDeleted = false) }
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
                    importedCount = container.importStore.observeImported().map { it.size },
                    importState = container.gpxImportJob.state,
                    deleteImportedTrips = container.importStore::deleteAllImported,
                )
            }
        }
    }
}
