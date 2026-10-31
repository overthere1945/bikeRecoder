package com.cowork.bikerecoder.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.importing.GpxImportJob
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.importing.ImportScanner
import com.cowork.bikerecoder.importing.RideKey
import com.cowork.bikerecoder.importing.ScanResult
import com.cowork.bikerecoder.importing.ScanRow
import com.cowork.bikerecoder.importing.filesText
import com.cowork.bikerecoder.importing.headerText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the "기록 가져오기" screen shows (V1.6.0 spec §1.2, §1.3). */
sealed interface ImportUiState {
    /** "파일을 읽는 중… 12/38". */
    data class Reading(val done: Int, val total: Int) : ImportUiState

    /**
     * The review list: [rows] newest first, unreadable ones last; [checked] the rows to import; [notices] the limits hit;
     * [header] "찾은 주행 38개 · 새로 35개" and [files] "파일 38개에서".
     */
    data class Review(
        val rows: List<ScanRow>,
        val checked: Set<RideKey>,
        val notices: List<String>,
        val header: String,
        val files: String,
    ) : ImportUiState {
        /** The rows that can be picked: new rides and other activities. */
        val selectable: Set<RideKey> get() = rows.filter { it.isSelectable() }.mapTo(LinkedHashSet()) { it.key }

        /** [전체 선택] is on: every row that can be picked is. */
        val allChecked: Boolean get() = selectable.let { it.isNotEmpty() && checked.containsAll(it) }
    }

    /** "가져올 주행을 찾지 못했습니다" with [다른 파일 고르기]. */
    data object Empty : ImportUiState

    /** The import job: its progress, then its result. */
    data class Job(val state: ImportJobState) : ImportUiState
}

/** A new ride or another activity can be picked; overlapping, duplicate and unreadable rows cannot. */
fun ScanRow.isSelectable(): Boolean = status == RideStatus.New || status == RideStatus.NotCycling

/**
 * The review list of the picked files and the hand-over to the one import job (V1.6.0 spec §1.2, §1.3). It lives as
 * long as the screen's back stack entry, so the list and the checks survive a rotation. Opened with [uris] it reads them
 * at once; opened with none (the settings row during an import) it shows the job.
 *
 * [GpxImportJob.start] keeps the files readable with up to 128 binder calls, so it runs on [io].
 */
class ImportViewModel(
    private val scanner: ImportScanner,
    private val job: GpxImportJob,
    uris: List<String> = emptyList(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private sealed interface Phase {
        data class Reading(val done: Int, val total: Int) : Phase
        data class Review(val result: ScanResult, val checked: Set<RideKey>) : Phase
        data object Empty : Phase

        /** Handed to the job, whose [GpxImportJob.start] has not returned yet. */
        data class Starting(val total: Int) : Phase

        /** Following the job. */
        data object Job : Phase
    }

    private val phase = MutableStateFlow<Phase>(Phase.Job)
    private var scanJob: Job? = null

    val uiState: StateFlow<ImportUiState> = combine(phase, job.state, ::uiStateOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, uiStateOf(phase.value, job.state.value))

    init {
        if (uris.isNotEmpty()) scan(uris)
    }

    /** Reads [uris] into the review list ("파일을 읽는 중…"); a reading still going on is dropped. */
    fun scan(uris: List<String>) {
        if (uris.isEmpty()) return
        scanJob?.cancel()
        // A result no screen showed (the import screen was left during the run) is not shown any more.
        job.acknowledge()
        phase.value = Phase.Reading(0, uris.size)
        scanJob = viewModelScope.launch {
            val result = try {
                scanner.scan(uris) { done, total -> phase.value = Phase.Reading(done, total) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            phase.value = when {
                result == null || result.rows.isEmpty() -> Phase.Empty
                else -> Phase.Review(result, result.rows.filter { it.status == RideStatus.New }.mapTo(LinkedHashSet()) { it.key })
            }
        }
    }

    /** Checks or unchecks [key]; rows that cannot be picked stay as they are. */
    fun toggle(key: RideKey) = updateReview { review ->
        val row = review.result.rows.firstOrNull { it.key == key }
        if (row == null || !row.isSelectable()) return@updateReview review
        review.copy(checked = if (key in review.checked) review.checked - key else review.checked + key)
    }

    /** [전체 선택]: all rows that can be picked on, or off when they all are. */
    fun toggleAll() = updateReview { review ->
        val selectable = review.result.rows.filter { it.isSelectable() }.map { it.key }.toSet()
        val all = selectable.isNotEmpty() && review.checked.containsAll(selectable)
        review.copy(checked = if (all) review.checked - selectable else review.checked + selectable)
    }

    /** [N개 가져오기]: the checked rows go to the import job, and the screen follows it. */
    @OptIn(DelicateCoroutinesApi::class)
    fun importChecked() {
        val review = phase.value as? Phase.Review ?: return
        val picks = review.result.rows.filter { it.key in review.checked }
        if (picks.isEmpty()) return
        phase.value = Phase.Starting(picks.size)
        // ATOMIC: the screen left at once still starts the import it asked for.
        viewModelScope.launch(io, start = CoroutineStart.ATOMIC) {
            // False while another run goes on: the screen then shows that one.
            job.start(review.result.sources, picks)
            phase.value = Phase.Job
        }
    }

    /** [멈추기]: the run ends after the ride being saved. */
    fun stop() = job.stop()

    /** Back, or [닫기] / [요약 보기]: a reading stops; a result shown is done with. A running import goes on. */
    fun close() {
        scanJob?.cancel()
        if (phase.value == Phase.Job) job.acknowledge()
    }

    private fun updateReview(transform: (Phase.Review) -> Phase.Review) {
        phase.update { p -> if (p is Phase.Review) transform(p) else p }
    }

    private fun uiStateOf(p: Phase, jobState: ImportJobState): ImportUiState = when (p) {
        is Phase.Reading -> ImportUiState.Reading(p.done, p.total)
        is Phase.Review -> ImportUiState.Review(
            rows = p.result.rows,
            checked = p.checked,
            notices = p.result.notices,
            header = headerText(
                found = p.result.rows.count { it.startedAt != null },
                new = p.result.rows.count { it.status == RideStatus.New },
            ),
            files = filesText(p.result.sources.size),
        )
        Phase.Empty -> ImportUiState.Empty
        is Phase.Starting -> ImportUiState.Job(ImportJobState.Importing(0, p.total))
        Phase.Job -> ImportUiState.Job(jobState)
    }

    companion object {
        /** The picked URIs, one per line (a URI never holds a raw line break). */
        const val ARG_URIS = "uris"

        fun urisArg(uris: List<String>): String = uris.joinToString("\n")

        fun parseUris(arg: String?): List<String> = arg?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportViewModel(
                    scanner = ImportScanner(container.importReader, container.importStore, clock = System::currentTimeMillis),
                    job = container.gpxImportJob,
                    uris = parseUris(createSavedStateHandle().get<String>(ARG_URIS)),
                )
            }
        }
    }
}
