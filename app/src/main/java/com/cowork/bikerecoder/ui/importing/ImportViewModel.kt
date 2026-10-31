package com.cowork.bikerecoder.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.importing.GpxImportJob
import com.cowork.bikerecoder.importing.ImportHandoff
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.importing.ImportScanner
import com.cowork.bikerecoder.importing.RideKey
import com.cowork.bikerecoder.importing.ScanResult
import com.cowork.bikerecoder.importing.ScanRow
import com.cowork.bikerecoder.importing.filesText
import com.cowork.bikerecoder.importing.headerText
import com.cowork.bikerecoder.importing.unreadableText
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

    /**
     * No ride found: "가져올 주행을 찾지 못했습니다" with [다른 파일 고르기], then the limits hit ([notices]) and why each
     * file could not be read ([unreadable], "notes.txt: GPX 파일이 아닙니다").
     */
    data class Empty(val notices: List<String> = emptyList(), val unreadable: List<String> = emptyList()) : ImportUiState

    /** Nothing to show (no files were handed over and no import runs, e.g. after a restart): the screen goes back. */
    data object Closed : ImportUiState

    /** The import job: its progress, then its result. */
    data class Job(val state: ImportJobState) : ImportUiState
}

/** A new ride or another activity can be picked; overlapping, duplicate and unreadable rows cannot. */
fun ScanRow.isSelectable(): Boolean = status == RideStatus.New || status == RideStatus.NotCycling

/**
 * The review list of the picked files and the hand-over to the one import job (V1.6.0 spec §1.2, §1.3). It lives as
 * long as the screen's back stack entry, so the list and the checks survive a rotation. When created it takes the files
 * of [handoff] and reads them; with none (the settings row during an import) it shows the job, or, when no import runs
 * either (the process was restarted on this screen), [ImportUiState.Closed]. When files were handed over, [uiState]
 * starts as their reading and never as [ImportUiState.Closed].
 *
 * [GpxImportJob.start] keeps the files readable with up to 128 grant calls and 256 file opens, so it runs on [io].
 */
class ImportViewModel(
    private val scanner: ImportScanner,
    private val job: GpxImportJob,
    handoff: ImportHandoff,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private sealed interface Phase {
        data class Reading(val done: Int, val total: Int) : Phase
        data class Review(val result: ScanResult, val checked: Set<RideKey>) : Phase
        data class Empty(val notices: List<String>, val unreadable: List<String>) : Phase

        /** Handed to the job, whose [GpxImportJob.start] has not returned yet. */
        data class Starting(val total: Int) : Phase

        /** Following the job. */
        data object Job : Phase
    }

    /** The files handed over, taken first: the state starts as their reading, never as [ImportUiState.Closed]. */
    private val handedOver: List<String>? = handoff.take()?.takeIf { it.isNotEmpty() }

    private val phase = MutableStateFlow<Phase>(handedOver?.let { Phase.Reading(0, it.size) } ?: Phase.Job)
    private var scanJob: Job? = null

    val uiState: StateFlow<ImportUiState> = combine(phase, job.state, ::uiStateOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, uiStateOf(phase.value, job.state.value))

    init {
        handedOver?.let(::scan)
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
                result == null -> Phase.Empty(emptyList(), emptyList())
                // No ride: only why each file could not be read.
                result.rows.none { it.startedAt != null } -> Phase.Empty(
                    result.notices,
                    result.rows.mapNotNull { row ->
                        (row.status as? RideStatus.Unreadable)?.let { unreadableText(row.fileName, it.problem) }
                    },
                )
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
        is Phase.Empty -> ImportUiState.Empty(p.notices, p.unreadable)
        is Phase.Starting -> ImportUiState.Job(ImportJobState.Importing(0, p.total))
        // No run to follow (none was handed over, or its result was seen): nothing to show.
        Phase.Job -> if (jobState == ImportJobState.Idle) ImportUiState.Closed else ImportUiState.Job(jobState)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ImportViewModel(
                    scanner = ImportScanner(container.importReader, container.importStore, clock = System::currentTimeMillis),
                    job = container.gpxImportJob,
                    handoff = container.importHandoff,
                )
            }
        }
    }
}
