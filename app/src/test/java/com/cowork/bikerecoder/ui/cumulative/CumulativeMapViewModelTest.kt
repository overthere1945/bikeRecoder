package com.cowork.bikerecoder.ui.cumulative

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.CumulativeTotals
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PeriodFilter
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.HeartRateSource
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The cumulative route map (spec §4): simplified routes only, the period filter, its totals, the first fit. */
@OptIn(ExperimentalCoroutinesApi::class)
class CumulativeMapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private var pointReads = 0
    private val countingRides = object : RideStore by rides {
        override suspend fun points(segmentId: Long): List<TrackPoint> {
            pointReads++
            return rides.points(segmentId)
        }
    }
    private val store = FakePostStore { rides.segments(TRIP) }
    private var entryReads = 0
    private val countingStore = object : PostStore by store {
        override suspend fun cumulativeEntries(): List<CumulativeEntry> {
            entryReads++
            return store.cumulativeEntries()
        }
    }
    private val log = RecordingLog()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.processor(heartRate: HeartRateSource = FakeHeartRateSource()) = PostProcessor(
        countingRides, countingStore, FakePhotoSource(), heartRate, clock = { NOW }, scope = backgroundScope,
        warnLog = { _, _ -> },
    )

    /** Runs the view model and the pass (in the background scope, which advanceUntilIdle alone leaves) until idle. */
    private fun TestScope.settle() = repeat(4) {
        runCurrent()
        advanceUntilIdle()
    }

    private fun TestScope.viewModel(postStore: PostStore = countingStore) =
        CumulativeMapViewModel(postStore, processor(), log, compute = dispatcher)

    /** A simplified route of [id] on [date], two points at [lat]. */
    private fun entry(
        id: Long,
        date: LocalDate,
        distanceM: Double = 1_000.0,
        movingTimeMs: Long = 600_000L,
        lat: Double = 37.5,
        points: List<GeoPoint> = listOf(GeoPoint(lat, 127.0), GeoPoint(lat + 0.01, 127.01)),
    ) = CumulativeEntry(id, date, distanceM, movingTimeMs, points).also { store.simplified[id] = it }

    /** A closed ride segment of [TRIP] with a few track points at [lat]; [done] marks its post-processing finished. */
    private suspend fun ride(startedAt: Long, done: Boolean, lat: Double = 37.6): Long {
        val totals = SegmentTotals.ZERO.copy(distanceM = 2_000.0, movingTimeMs = 300_000L)
        val id = rides.openSegment(TRIP, startedAt, LocalDate.of(2026, 10, 18))
        val points = (0 until 4).map { i -> TrackPoint(startedAt + i * 1_000L, GeoPoint(lat, 127.0 + i * 0.001), null, 5f, 5f) }
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, startedAt + 3_000L, totals)
        if (done) store.saveExtras(SegmentExtras(id, photoScanDone = true, hrStatus = HrStatus.NONE, simplifiedDone = true))
        return id
    }

    @Test
    fun drawsTheStoredSimplifiedRoutesNotTheTracks() = runTest(dispatcher) {
        // Each ride's track (4 points at 37.6°N) differs from its stored simplified route (2 points at 35/36°N): a
        // map drawn from the tracks, or totals taken from the segments (2 km each), would not match.
        val a = ride(startedAt = 1_000L, done = true)
        val b = ride(startedAt = 10_000L, done = true)
        val simplifiedA = entry(a, LocalDate.of(2026, 10, 17), distanceM = 1_500.0, lat = 35.0)
        val simplifiedB = entry(b, LocalDate.of(2026, 10, 18), distanceM = 2_500.0, lat = 36.0)
        val tracks = listOf(rides.points(a), rides.points(b)).map { track -> track.map { it.point } }

        val vm = viewModel()
        settle()

        val state = vm.state.value
        assertEquals(listOf(simplifiedA, simplifiedB), state.entries)
        assertEquals(listOf(simplifiedA.points, simplifiedB.points), state.lines)
        assertTrue(state.lines.flatten().none { p -> tracks.flatten().contains(p) }, "no track point is drawn")
        assertEquals(4_000.0, state.totals.distanceM)
        assertTrue(entryReads >= 1)
        // The view model has no RideStore; the pass it asked for had nothing to do, so no track was read at all.
        assertEquals(0, pointReads)
    }

    @Test
    fun periodChangesSummaryAndRoutes() = runTest(dispatcher) {
        entry(1, LocalDate.of(2025, 12, 30), distanceM = 10_000.0, movingTimeMs = 1_800_000L)
        entry(2, LocalDate.of(2026, 1, 5), distanceM = 20_000.0, movingTimeMs = 3_600_000L)
        entry(3, LocalDate.of(2026, 3, 1), distanceM = 5_000.0, movingTimeMs = 900_000L)
        val vm = viewModel()
        settle()

        assertEquals(PeriodFilter(), vm.state.value.period)
        assertEquals(CumulativeTotals(35_000.0, 3, 6_300_000L), vm.state.value.totals)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.entries.map { it.segmentId })

        vm.selectYear(2026)
        settle()
        assertEquals(PeriodFilter(2026), vm.state.value.period)
        assertEquals(listOf(2L, 3L), vm.state.value.entries.map { it.segmentId })
        assertEquals(CumulativeTotals(25_000.0, 2, 4_500_000L), vm.state.value.totals)
        assertEquals(listOf(1, 3), vm.state.value.months)
        assertEquals(2, vm.state.value.lines.size)

        vm.selectMonth(3)
        settle()
        assertEquals(PeriodFilter(2026, 3), vm.state.value.period)
        assertEquals(listOf(3L), vm.state.value.entries.map { it.segmentId })
        assertEquals(CumulativeTotals(5_000.0, 1, 900_000L), vm.state.value.totals)

        // [월 ▾] → 전체: the whole year again.
        vm.selectMonth(null)
        settle()
        assertEquals(PeriodFilter(2026), vm.state.value.period)

        // Another year starts with no month.
        vm.selectMonth(1)
        vm.selectYear(2025)
        settle()
        assertEquals(PeriodFilter(2025), vm.state.value.period)
        assertEquals(listOf(1L), vm.state.value.entries.map { it.segmentId })
        assertEquals(listOf(12), vm.state.value.months)

        vm.showAll()
        settle()
        assertEquals(PeriodFilter(), vm.state.value.period)
        assertEquals(3, vm.state.value.entries.size)
        assertEquals(emptyList<Int>(), vm.state.value.months, "no month list without a year")
    }

    @Test
    fun yearsNewestFirst() = runTest(dispatcher) {
        entry(1, LocalDate.of(2024, 6, 1))
        entry(2, LocalDate.of(2026, 1, 5))
        entry(3, LocalDate.of(2025, 3, 1))
        entry(4, LocalDate.of(2026, 2, 1))
        val vm = viewModel()
        settle()

        assertEquals(listOf(2026, 2025, 2024), vm.state.value.years)
        // The year list stays whole while a period is chosen.
        vm.selectYear(2025)
        settle()
        assertEquals(listOf(2026, 2025, 2024), vm.state.value.years)
    }

    @Test
    fun emptyState() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.state.value.empty, "not before the routes are read")
        settle()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.empty)
        assertEquals(CumulativeTotals(0.0, 0, 0L), vm.state.value.totals)
        assertNull(vm.fit.value)
    }

    @Test
    fun segmentsThatNeverMovedAreLeftOut() = runTest(dispatcher) {
        // distanceM == 0: the rider never moved (the history lists leave these out too).
        entry(1, LocalDate.of(2026, 10, 18), distanceM = 0.0, movingTimeMs = 0L)
        val vm = viewModel()
        settle()
        assertTrue(vm.state.value.empty)

        entry(2, LocalDate.of(2026, 10, 18), distanceM = 3_000.0, movingTimeMs = 600_000L)
        val second = viewModel()
        settle()
        assertFalse(second.state.value.empty)
        assertEquals(CumulativeTotals(3_000.0, 1, 600_000L), second.state.value.totals)
        assertEquals(listOf(2L), second.state.value.entries.map { it.segmentId })
    }

    @Test
    fun rereadsWhenProcessingEnds() = runTest(dispatcher) {
        // A ride not processed yet: its simplified route appears once the pass that the screen asked for ends.
        val id = ride(startedAt = 1_000L, done = false)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        store.beforeList = {
            runs++
            gate.await()
        }
        val vm = viewModel()
        settle()
        assertEquals(1, runs, "opening the screen asks for one pass")
        // Read once, nothing yet, but the pass may still bring routes: not "아직 기록이 없습니다".
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.settled)
        assertFalse(vm.state.value.empty)

        gate.complete(Unit)
        settle()

        assertEquals(listOf(id), vm.state.value.entries.map { it.segmentId })
        assertFalse(vm.state.value.empty)
        assertEquals(1, vm.state.value.totals.rides)
    }

    @Test
    fun fitFramesEveryRouteOnce() = runTest(dispatcher) {
        entry(1, LocalDate.of(2025, 5, 1), points = listOf(GeoPoint(35.1, 129.0), GeoPoint(35.2, 129.1)))
        entry(2, LocalDate.of(2026, 5, 1), points = listOf(GeoPoint(37.5, 126.9), GeoPoint(37.6, 127.0)))
        entry(3, LocalDate.of(2026, 6, 1), points = emptyList()) // nothing to draw or frame, still counted
        val vm = viewModel()
        settle()

        val fit = vm.fit.value!!
        assertEquals(listOf(GeoPoint(35.1, 126.9), GeoPoint(37.6, 129.1)), fit.points, "the corners of all routes")
        assertEquals(2, vm.state.value.lines.size)
        assertEquals(3, vm.state.value.totals.rides)

        // Choosing a period does not move the camera again.
        vm.selectYear(2026)
        settle()
        assertSame(fit, vm.fit.value)
    }

    @Test
    fun aSingleSpotIsCentred() = runTest(dispatcher) {
        entry(1, LocalDate.of(2026, 5, 1), points = listOf(GeoPoint(37.5, 127.0)))
        val vm = viewModel()
        settle()

        assertEquals(listOf(GeoPoint(37.5, 127.0)), vm.fit.value!!.points)
        assertEquals(emptyList<List<GeoPoint>>(), vm.state.value.lines, "one point is no line")
    }

    @Test
    fun aStoreFailureIsReportedAndKeepsWhatIsShown() = runTest(dispatcher) {
        entry(1, LocalDate.of(2026, 5, 1))
        var fail = false
        val failing = object : PostStore by store {
            override suspend fun cumulativeEntries(): List<CumulativeEntry> {
                if (fail) throw IllegalStateException("database closed")
                return store.cumulativeEntries()
            }
        }
        val processor = processor()
        val vm = CumulativeMapViewModel(failing, processor, log, compute = dispatcher)
        settle()
        assertEquals(1, vm.state.value.entries.size)

        // The re-read after the next pass fails: the routes already shown stay.
        fail = true
        processor.runOnce()
        settle()
        assertEquals(1, vm.state.value.entries.size)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun routesAppearAsTheyAreSimplifiedAndTheFitFollows() = runTest(dispatcher) {
        // The first pass after the upgrade: two earlier rides, neither simplified. The pass goes newest first and is
        // held at the older ride's heart rate, after the newer one's route is saved.
        val older = ride(startedAt = 1_000L, done = false, lat = 35.0)
        val newer = ride(startedAt = 100_000L, done = false, lat = 37.0)
        val gate = CompletableDeferred<Unit>()
        val fake = FakeHeartRateSource()
        val holding = object : HeartRateSource by fake {
            override suspend fun read(from: Long, to: Long): HrRead {
                if (from < 100_000L) gate.await()
                return fake.read(from, to)
            }
        }
        val start = CompletableDeferred<Unit>()
        store.beforeList = { start.await() }
        val vm = CumulativeMapViewModel(countingStore, processor(holding), log, compute = dispatcher)
        settle()
        assertEquals(emptyList<Long>(), vm.state.value.entries.map { it.segmentId })
        assertFalse(vm.state.value.empty)
        assertNull(vm.fit.value)

        // The pass starts and saves the newer ride's route, then waits: read again in the middle of the pass.
        start.complete(Unit)
        settle()
        assertEquals(listOf(newer), vm.state.value.entries.map { it.segmentId }, "shown before the pass ends")
        assertFalse(vm.state.value.settled)
        assertFalse(vm.state.value.empty)
        val first = vm.fit.value!!
        assertEquals(37.0, first.points.minOf { it.lat })

        gate.complete(Unit)
        settle()

        assertEquals(setOf(older, newer), vm.state.value.entries.map { it.segmentId }.toSet())
        assertTrue(vm.state.value.settled)
        val second = vm.fit.value!!
        assertNotSame(first, second, "more routes: framed again")
        assertEquals(35.0, second.points.minOf { it.lat })
    }

    @Test
    fun theFitStopsFollowingOnceTheUserMovesTheMap() = runTest(dispatcher) {
        entry(1, LocalDate.of(2026, 5, 1), lat = 37.0)
        val processor = processor()
        val vm = CumulativeMapViewModel(countingStore, processor, log, compute = dispatcher)
        settle()
        val first = vm.fit.value!!

        vm.onMapMoved()
        entry(2, LocalDate.of(2026, 5, 2), lat = 35.0)
        processor.runOnce()
        settle()

        assertEquals(2, vm.state.value.entries.size)
        assertSame(first, vm.fit.value, "the camera is the user's now")
    }

    @Test
    fun aFailedFirstReadIsRetriedOnTheNextPassAndOnATimer() = runTest(dispatcher) {
        entry(1, LocalDate.of(2026, 5, 1))
        var failures = 2
        val failing = object : PostStore by store {
            override suspend fun cumulativeEntries(): List<CumulativeEntry> {
                if (failures > 0) {
                    failures--
                    throw IllegalStateException("database busy")
                }
                return store.cumulativeEntries()
            }
        }
        val processor = processor()
        val vm = CumulativeMapViewModel(failing, processor, log, compute = dispatcher)
        runCurrent()
        advanceTimeBy(CumulativeMapViewModel.REREAD_SPACING_MS + 1)
        assertEquals(1, log.messages.size, "the first read fails")
        assertFalse(vm.state.value.loaded)

        // The next pass that ends reads again (it fails again).
        processor.runOnce()
        runCurrent()
        assertEquals(2, log.messages.size)
        assertFalse(vm.state.value.loaded)

        // Nothing else comes: the timer reads again, so the screen is never left without routes for good.
        advanceTimeBy(CumulativeMapViewModel.RETRY_MS + 1)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertEquals(1, vm.state.value.entries.size)
        settle()
        assertEquals(2, log.messages.size, "nothing fails once read")
    }

    @Test
    fun aMonthWithoutAYearIsIgnored() = runTest(dispatcher) {
        entry(1, LocalDate.of(2026, 3, 1))
        entry(2, LocalDate.of(2026, 4, 1))
        val vm = viewModel()
        settle()

        vm.selectMonth(3)
        settle()

        assertEquals(PeriodFilter(), vm.state.value.period)
        assertEquals(2, vm.state.value.entries.size)
        assertEquals(emptyList<Int>(), vm.state.value.months)
    }

    @Test
    fun summaryText() {
        assertEquals("35.00km · 주행 3회 · 1시간 45분", cumulativeSummaryText(CumulativeTotals(35_000.0, 3, 6_300_000L)))
        assertEquals("0.00km · 주행 0회 · 0분", cumulativeSummaryText(CumulativeTotals(0.0, 0, 0L)))
    }

    private companion object {
        const val TRIP = 1L
        const val NOW = 10_000_000L
    }
}
