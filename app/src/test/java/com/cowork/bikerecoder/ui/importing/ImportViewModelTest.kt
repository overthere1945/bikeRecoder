package com.cowork.bikerecoder.ui.importing

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.importing.FakeImportReader
import com.cowork.bikerecoder.importing.FakeImportStore
import com.cowork.bikerecoder.importing.GpxImportJob
import com.cowork.bikerecoder.importing.ImportHandoff
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.importing.ImportScanner
import com.cowork.bikerecoder.importing.RideKey
import com.cowork.bikerecoder.importing.T0
import com.cowork.bikerecoder.importing.gpx
import com.cowork.bikerecoder.importing.zip
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val reader = FakeImportReader()
    private val store = FakeImportStore()
    private val hour = 3_600_000L
    private val now = T0 + 1_000 * hour
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val job = GpxImportJob(
        reader = reader,
        store = store,
        postProcessor = {},
        profile = { RouteProfile.SHORTEST },
        clock = { now },
        scope = scope,
        log = WarnLog { _, _ -> },
    )
    private lateinit var io: kotlinx.coroutines.test.TestDispatcher

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        io = StandardTestDispatcher()
    }

    /** Every view model made here: stopped before Main is reset, so none touches it afterwards. */
    private val made = mutableListOf<ImportViewModel>()

    @AfterEach
    fun tearDown() {
        made.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** The screen opened with [uris] handed over (null: nothing handed over, e.g. after the process was restarted). */
    private fun viewModel(uris: List<String>? = null) = ImportViewModel(
        ImportScanner(reader, store, clock = { now }),
        job,
        ImportHandoff().apply { if (uris != null) put(uris) },
        io = io,
    ).also { made += it }

    private fun ImportViewModel.await(predicate: (ImportUiState) -> Boolean): ImportUiState =
        runBlocking { withTimeout(20_000) { uiState.first(predicate) } }

    private fun ImportViewModel.review(): ImportUiState.Review = await { it is ImportUiState.Review } as ImportUiState.Review

    private fun ImportUiState.Review.keyOf(fileName: String): RideKey = rows.single { it.fileName == fileName }.key

    @Test
    fun newRowsStartChecked() {
        val a = reader.add("a.gpx", gpx(T0))
        val b = reader.add("b.gpx", gpx(T0 + hour))
        val vm = viewModel(listOf(a, b))

        val review = vm.review()

        assertEquals(listOf("b.gpx", "a.gpx"), review.rows.map { it.fileName })
        assertEquals(setOf(RideKey(0, 0), RideKey(1, 0)), review.checked)
        assertEquals("찾은 주행 2개 · 새로 2개", review.header)
        assertEquals("파일 2개에서", review.files)
        assertEquals(emptyList<String>(), review.notices)
    }

    @Test
    fun notCyclingStartsUncheckedButToggles() {
        val run = reader.add("run.gpx", gpx(T0, type = "running"))
        val vm = viewModel(emptyList())
        vm.scan(listOf(run))

        val review = vm.review()
        assertEquals(RideStatus.NotCycling, review.rows.single().status)
        assertEquals(emptySet<RideKey>(), review.checked)
        assertEquals("찾은 주행 1개 · 새로 0개", review.header)

        vm.toggle(RideKey(0, 0))
        assertEquals(setOf(RideKey(0, 0)), (vm.uiState.value as ImportUiState.Review).checked)
        vm.toggle(RideKey(0, 0))
        assertEquals(emptySet<RideKey>(), (vm.uiState.value as ImportUiState.Review).checked)
    }

    @Test
    fun blockedRowsCannotToggle() {
        store.spans += TimeSpan(T0 - 10_000, T0 + 10_000)
        val overlap = reader.add("overlap.gpx", gpx(T0))
        val first = reader.add("first.gpx", gpx(T0 + hour))
        val dup = reader.add("dup.gpx", gpx(T0 + hour))
        val text = reader.add("notes.txt", "hello".toByteArray())
        val vm = viewModel(listOf(overlap, first, dup, text))

        val review = vm.review()
        assertEquals(
            mapOf(
                "overlap.gpx" to RideStatus.OverlapsExisting,
                "first.gpx" to RideStatus.New,
                "dup.gpx" to RideStatus.DuplicateInList,
            ),
            review.rows.filter { it.startedAt != null }.associate { it.fileName to it.status },
        )
        assertEquals(setOf(review.keyOf("first.gpx")), review.checked)

        for (name in listOf("overlap.gpx", "dup.gpx", "notes.txt")) vm.toggle(review.keyOf(name))

        assertEquals(setOf(review.keyOf("first.gpx")), (vm.uiState.value as ImportUiState.Review).checked)
    }

    @Test
    fun toggleAllOnlySelectable() {
        store.spans += TimeSpan(T0 - 10_000, T0 + 10_000)
        val overlap = reader.add("overlap.gpx", gpx(T0))
        val new = reader.add("new.gpx", gpx(T0 + hour))
        val run = reader.add("run.gpx", gpx(T0 + 2 * hour, type = "running"))
        val vm = viewModel(listOf(overlap, new, run))
        val review = vm.review()
        val selectable = setOf(review.keyOf("new.gpx"), review.keyOf("run.gpx"))
        assertEquals(selectable, review.selectable)
        assertEquals(false, review.allChecked)

        vm.toggleAll()
        val all = vm.uiState.value as ImportUiState.Review
        assertEquals(selectable, all.checked)
        assertEquals(true, all.allChecked)

        vm.toggleAll()
        assertEquals(emptySet<RideKey>(), (vm.uiState.value as ImportUiState.Review).checked)

        vm.toggleAll()
        assertEquals(selectable, (vm.uiState.value as ImportUiState.Review).checked)
    }

    @Test
    fun importCheckedStartsJobWithPicks() {
        val a = reader.add("a.gpx", gpx(T0))
        val b = reader.add("b.gpx", gpx(T0 + hour))
        val run = reader.add("run.gpx", gpx(T0 + 2 * hour, type = "running"))
        val vm = viewModel(listOf(a, b, run))
        vm.review()

        vm.importChecked()

        // Shown at once; the job (whose start takes the file grants) only starts on the io dispatcher.
        assertEquals(ImportUiState.Job(ImportJobState.Importing(0, 2)), vm.uiState.value)
        assertEquals(emptyList<List<String>>(), reader.held)
        io.scheduler.advanceUntilIdle()
        assertEquals(listOf(listOf(a, b, run)), reader.held)

        val done = vm.await { (it as? ImportUiState.Job)?.state is ImportJobState.Finished } as ImportUiState.Job
        assertEquals(ImportJobState.Finished(imported = 2, skipped = 0, stopped = false), done.state)
        assertEquals(setOf("a.gpx", "b.gpx"), store.saved.map { it.fileName }.toSet())

        // [닫기] after the result: the job is idle again.
        vm.close()
        assertEquals(ImportJobState.Idle, job.state.value)
    }

    @Test
    fun backWhileReadingCancels() {
        val a = reader.add("a.gpx", gpx(T0))
        val b = reader.add("b.gpx", gpx(T0 + hour))
        val opened = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val closed = CountDownLatch(1)
        reader.wrap = { stream ->
            opened.countDown()
            gate.await(20, TimeUnit.SECONDS)
            object : java.io.FilterInputStream(stream) {
                override fun close() {
                    super.close()
                    closed.countDown()
                }
            }
        }
        val vm = viewModel(listOf(a, b))
        assertTrue(opened.await(20, TimeUnit.SECONDS))
        assertEquals(ImportUiState.Reading(0, 2), vm.uiState.value)

        vm.close()
        gate.countDown()
        assertTrue(closed.await(20, TimeUnit.SECONDS))
        Thread.sleep(300)

        assertEquals(listOf(a), reader.opened.toList())
        assertTrue(vm.uiState.value is ImportUiState.Reading, "still ${vm.uiState.value}")
    }

    @Test
    fun emptyWhenNoRows() {
        // Only a folder inside: nothing to list.
        val empty = reader.add("empty.zip", zip("activities/" to ByteArray(0)))
        val vm = viewModel(listOf(empty))

        assertEquals(ImportUiState.Empty(), vm.await { it !is ImportUiState.Reading })
    }

    @Test
    fun emptyWhenNoRideOnlyUnreadable() {
        val text = reader.add("notes.txt", "hello".toByteArray())
        val vm = viewModel(listOf(text, "content://x/gone.gpx"))

        assertEquals(
            ImportUiState.Empty(unreadable = listOf("notes.txt: GPX 파일이 아닙니다", "gone.gpx: 파일을 읽을 수 없습니다")),
            vm.await { it !is ImportUiState.Reading },
        )
    }

    @Test
    fun nothingHandedOverWhileIdleCloses() {
        // The process was restarted on this screen: the picked files are gone and nothing runs.
        assertEquals(ImportUiState.Closed, viewModel().uiState.value)
    }

    @Test
    fun nothingHandedOverWhileImportingShowsTheJob() {
        val gate = CountDownLatch(1)
        val a = reader.add("a.gpx", gpx(T0))
        val picks = viewModel(listOf(a)).review().rows
        reader.wrap = { gate.await(20, TimeUnit.SECONDS); it }
        assertTrue(job.start(listOf(a), picks))

        // The settings row during the import.
        assertEquals(ImportUiState.Job(ImportJobState.Importing(0, 1)), viewModel().uiState.value)
        gate.countDown()
        runBlocking { withTimeout(20_000) { job.state.first { it is ImportJobState.Finished } } }
    }

    @Test
    fun closeLeavesARunningImportAlone() {
        val gate = CountDownLatch(1)
        val a = reader.add("a.gpx", gpx(T0))
        val vm = viewModel(listOf(a))
        vm.review()
        reader.wrap = { gate.await(20, TimeUnit.SECONDS); it }
        vm.importChecked()
        io.scheduler.advanceUntilIdle()
        assertEquals(ImportJobState.Importing(0, 1), job.state.value)

        vm.close()
        assertEquals(ImportJobState.Importing(0, 1), job.state.value)

        gate.countDown()
        val done = vm.await { (it as? ImportUiState.Job)?.state is ImportJobState.Finished } as ImportUiState.Job
        assertEquals(ImportJobState.Finished(imported = 1, skipped = 0, stopped = false), done.state)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun scanAcknowledgesAStaleResult() {
        val a = reader.add("a.gpx", gpx(T0))
        val picks = viewModel(listOf(a)).review().rows
        assertTrue(job.start(listOf(a), picks))
        runBlocking { withTimeout(20_000) { job.state.first { it is ImportJobState.Finished } } }

        // A result no screen showed; new files come in.
        val b = reader.add("b.gpx", gpx(T0 + hour))
        val vm = viewModel(listOf(b))

        assertEquals(ImportJobState.Idle, job.state.value)
        assertEquals(listOf("b.gpx"), vm.review().rows.map { it.fileName })
    }
}
