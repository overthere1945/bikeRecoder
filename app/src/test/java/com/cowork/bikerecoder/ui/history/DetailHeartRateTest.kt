package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.HeartRateCharts
import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ui.health.HeartRateAccess
import com.cowork.bikerecoder.ui.health.TrackColorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The heart rate of the segment and trip detail view models (spec §3.3): the [기본]/[심박] track colours, the chart,
 * [다시 가져오기], the trip's time-weighted totals and its map of separate segment lines (ruling R10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailHeartRateTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val heartRate = FakeHeartRateSource()
    private val log = RecordingLog()

    private val day1 = LocalDate.of(2026, 10, 17)
    private val day2 = LocalDate.of(2026, 10, 18)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        trips.trips[TRIP] = Trip(TRIP, TripType.MULTI_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.processor() = PostProcessor(
        rides, store, FakePhotoSource(), heartRate, clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> },
    )

    /** A closed segment with [n] points 1 s and 0.0001° apart, starting at [startedAt]. */
    private suspend fun segment(
        startedAt: Long,
        date: LocalDate = day2,
        lat: Double = 37.5,
        n: Int = 4,
        movingTimeMs: Long = 60_000,
    ): Long {
        val totals = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = movingTimeMs)
        val id = rides.openSegment(TRIP, startedAt, date)
        val points = (0 until n).map { i -> TrackPoint(startedAt + i * 1_000L, GeoPoint(lat + i * 0.0001, 127.0), null, 5f, 5f) }
        rides.appendPoints(id, points, totals)
        rides.closeSegment(id, startedAt + (n - 1) * 1_000L, totals)
        return id
    }

    private suspend fun ready(
        id: Long,
        samples: List<HeartRateSample>,
        avg: Int = 120,
        max: Int = 150,
        calories: Double? = null,
        detailed: Boolean = true,
    ) {
        store.replaceSamples(id, samples)
        store.saveExtras(
            SegmentExtras(
                id, hrStatus = HrStatus.READY, hrLastTriedAt = 1L, hrDetailed = detailed, avgBpm = avg, maxBpm = max,
                calories = calories,
            ),
        )
    }

    private fun TestScope.segmentDetail(id: Long) =
        SegmentDetailViewModel(rides, id, store, processor(), log, compute = dispatcher)

    private fun TestScope.tripDetail() = TripDetailViewModel(rides, TRIP, store, processor(), log, compute = dispatcher)

    @Test
    fun heartRateModeColoursEveryTrackPoint() = runTest(dispatcher) {
        val id = segment(startedAt = 0, n = 4)
        // Each point takes the nearest sample: 0 s and 1 s → 90 (blue), 2 s and 3 s → 165 (red).
        val samples = listOf(HeartRateSample(0L, 90), HeartRateSample(3_000L, 165))
        ready(id, samples)
        val vm = segmentDetail(id)
        advanceUntilIdle()

        assertTrue(vm.canColorByHeartRate.value)
        assertEquals(TrackColorMode.DEFAULT, vm.colorMode.value, "[기본] first")
        assertNull(vm.trackColors.value, "[기본]: the track's own colour")

        vm.setColorMode(TrackColorMode.HEART_RATE)
        advanceUntilIdle()
        val colors = vm.trackColors.value!!
        assertEquals(4, colors.size, "one colour per track point")
        assertEquals(HeartRateColor.bands(rides.points(id), samples).map { it.colorHex }, colors)
        assertEquals(listOf("#1E88E5", "#1E88E5", "#E53935", "#E53935"), colors)

        vm.setColorMode(TrackColorMode.DEFAULT)
        advanceUntilIdle()
        assertNull(vm.trackColors.value)
    }

    @Test
    fun aSegmentWithoutDetailedHeartRateCannotBeColoured() = runTest(dispatcher) {
        val id = segment(startedAt = 0)
        ready(id, listOf(HeartRateSample(0L, 120)), detailed = false)
        val vm = segmentDetail(id)
        advanceUntilIdle()

        assertFalse(vm.canColorByHeartRate.value)
        vm.setColorMode(TrackColorMode.HEART_RATE)
        advanceUntilIdle()
        assertNull(vm.trackColors.value)
    }

    @Test
    fun theHeartRateChartUsesTheDistanceAxis() = runTest(dispatcher) {
        val id = segment(startedAt = 0)
        val samples = listOf(HeartRateSample(0L, 110), HeartRateSample(2_000L, 130))
        ready(id, samples)
        val vm = segmentDetail(id)
        advanceUntilIdle()

        assertEquals(HeartRateCharts.bpm(rides.points(id), samples), vm.heartRateChart.value)
        assertEquals(4, vm.heartRateChart.value.size)
        assertEquals(120, vm.heartRate.value?.avgBpm)
    }

    @Test
    fun refetchReadsTheSegmentAgainAndShowsWhatCameIn() = runTest(dispatcher) {
        val id = segment(startedAt = 0)
        val vm = segmentDetail(id)
        advanceUntilIdle()
        assertEquals(HrStatus.PENDING, vm.heartRate.value?.hrStatus, "the pass on open found nothing yet")
        val before = heartRate.calls.size

        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(1_000L, 140)), calories = 12.0) }
        vm.refetchHeartRate()
        advanceUntilIdle()

        assertEquals(before + 1, heartRate.calls.size)
        assertEquals(HrStatus.READY, vm.heartRate.value?.hrStatus)
        assertEquals(140, vm.heartRate.value?.avgBpm)
        assertEquals(4, vm.heartRateChart.value.size, "the new samples are charted")
    }

    @Test
    fun tripHeartRateIsWeightedByMovingTime() = runTest(dispatcher) {
        val a = segment(startedAt = 0, date = day1, movingTimeMs = 60_000)
        val b = segment(startedAt = 100_000, date = day2, movingTimeMs = 180_000)
        val c = segment(startedAt = 200_000, date = day2, movingTimeMs = 60_000)
        ready(a, listOf(HeartRateSample(0L, 100)), avg = 100, max = 130, calories = 100.0)
        ready(b, listOf(HeartRateSample(100_000L, 160)), avg = 160, max = 175, calories = 200.0, detailed = false)
        ready(c, listOf(HeartRateSample(200_000L, 140)), avg = 140, max = 150, calories = null)
        val vm = tripDetail()
        advanceUntilIdle()

        val hr = vm.heartRate.value
        // (100·1 + 160·3 + 140·1) / 5 = 144
        assertEquals(144, hr.avgBpm, "time-weighted, not (100 + 160 + 140) / 3")
        assertEquals(175, hr.maxBpm)
        assertEquals(300.0, hr.calories, "the calories that are known, summed")
        assertEquals(mapOf(day1 to 100, day2 to 155), hr.avgByDate)
        assertTrue(hr.canColor, "one detailed segment is enough")
    }

    @Test
    fun tripRefetchReadsEverySegment() = runTest(dispatcher) {
        val a = segment(startedAt = 0, date = day1)
        val b = segment(startedAt = 100_000, date = day2)
        val open = rides.openSegment(TRIP, 200_000, day2) // still being recorded: left alone
        val vm = tripDetail()
        advanceUntilIdle()
        val before = heartRate.calls.size

        heartRate.reads = { from, _ -> HrRead.Samples(listOf(HeartRateSample(from + 1_000L, if (from < 100_000L) 110 else 150)), calories = null) }
        vm.refetchHeartRate()
        assertTrue(vm.refetching.value, "busy at once")
        advanceUntilIdle()

        assertEquals(before + 2, heartRate.calls.size, "one read per closed segment, not the open one")
        assertFalse(vm.refetching.value)
        assertEquals(HrStatus.READY, store.extras(a)?.hrStatus)
        assertEquals(HrStatus.READY, store.extras(b)?.hrStatus)
        assertNull(store.extras(open))
    }

    @Test
    fun tripRefetchRunsOnceWhileBusy() = runTest(dispatcher) {
        segment(startedAt = 0, date = day1)
        segment(startedAt = 100_000, date = day2)
        val vm = tripDetail()
        advanceUntilIdle()
        val before = heartRate.calls.size

        vm.refetchHeartRate()
        vm.refetchHeartRate()
        advanceUntilIdle()
        assertEquals(before + 2, heartRate.calls.size, "the second press while busy is ignored")

        vm.refetchHeartRate()
        advanceUntilIdle()
        assertEquals(before + 4, heartRate.calls.size, "a new pass is possible once the first is over")
        assertFalse(vm.refetching.value)
    }

    @Test
    fun tripRefetchContinuesPastAFailingSegment() = runTest(dispatcher) {
        val a = segment(startedAt = 0, date = day1)
        val b = segment(startedAt = 100_000, date = day2)
        val vm = tripDetail()
        advanceUntilIdle()
        val before = heartRate.calls.size

        heartRate.reads = { from, _ ->
            if (from < 100_000L) throw IllegalStateException("Health Connect hiccup")
            HrRead.Samples(listOf(HeartRateSample(from + 1_000L, 150)), calories = null)
        }
        vm.refetchHeartRate()
        advanceUntilIdle()

        assertEquals(before + 2, heartRate.calls.size, "the failing segment did not stop the pass")
        assertNotEquals(HrStatus.READY, store.extras(a)?.hrStatus)
        assertEquals(HrStatus.READY, store.extras(b)?.hrStatus)
        assertFalse(vm.refetching.value, "not stuck busy")
    }

    @Test
    fun tripTotalsFollowRefetchedExtras() = runTest(dispatcher) {
        val a = segment(startedAt = 0, date = day1, movingTimeMs = 60_000)
        val b = segment(startedAt = 100_000, date = day2, movingTimeMs = 180_000)
        ready(a, listOf(HeartRateSample(0L, 100)), avg = 100, max = 110, calories = null)
        ready(b, listOf(HeartRateSample(100_000L, 160)), avg = 160, max = 175, calories = 200.0)
        val vm = tripDetail()
        advanceUntilIdle()
        // (100·1 + 160·3) / 4 = 145
        assertEquals(145, vm.heartRate.value.avgBpm)
        assertEquals(175, vm.heartRate.value.maxBpm)
        assertEquals(200.0, vm.heartRate.value.calories)

        // Only the first segment has more to read now (100, 160, 170: average 143, highest 170, 42 kcal); R7/R17 keep the second.
        heartRate.reads = { from, _ ->
            if (from >= 100_000L) HrRead.Empty
            else HrRead.Samples(
                listOf(HeartRateSample(from, 100), HeartRateSample(from + 1_000L, 160), HeartRateSample(from + 2_000L, 170)),
                calories = 42.0,
            )
        }
        vm.refetchHeartRate()
        advanceUntilIdle()

        val hr = vm.heartRate.value
        // (143·1 + 160·3) / 4 = 155.75
        assertEquals(156, hr.avgBpm, "time-weighted over the refetched and the kept segment")
        assertEquals(175, hr.maxBpm)
        assertEquals(242.0, hr.calories, "42 + 200")
        assertEquals(mapOf(day1 to 143, day2 to 160), hr.avgByDate)
    }

    @Test
    fun tripRefetchSurvivesAFailingSegmentRead() = runTest(dispatcher) {
        segment(startedAt = 0, date = day1)
        segment(startedAt = 100_000, date = day2)
        var failing = false
        val flaky = object : RideStore by rides {
            override suspend fun segments(tripId: Long): List<RideSegment> {
                if (failing) throw IllegalStateException("database busy")
                return rides.segments(tripId)
            }
        }
        val vm = TripDetailViewModel(flaky, TRIP, store, processor(), log, compute = dispatcher)
        advanceUntilIdle()
        val before = heartRate.calls.size

        failing = true
        vm.refetchHeartRate()
        advanceUntilIdle()
        assertEquals(before, heartRate.calls.size, "nothing could be read")
        assertFalse(vm.refetching.value, "not stuck busy")

        failing = false
        vm.refetchHeartRate()
        advanceUntilIdle()
        assertEquals(before + 2, heartRate.calls.size, "the next press works")
        assertFalse(vm.refetching.value)
    }

    @Test
    fun theRefetchButtonIsOfferedOnlyWhenHeartRateMayBeRead() {
        var pressed = 0
        val press = { pressed++; Unit }
        assertNull(tripRefetchAction(HeartRateAccess.NO_PERMISSION, press), "the permission card instead")
        assertNull(tripRefetchAction(HeartRateAccess.UNAVAILABLE, press))
        assertNull(tripRefetchAction(HeartRateAccess.CHECKING, press), "not before the access is known")
        tripRefetchAction(HeartRateAccess.GRANTED, press)!!.invoke()
        assertEquals(1, pressed)
    }

    @Test
    fun aTripWithoutHeartRateHasNoTotals() = runTest(dispatcher) {
        segment(startedAt = 0)
        val vm = tripDetail()
        advanceUntilIdle()

        assertNull(vm.heartRate.value.avgBpm)
        assertNull(vm.heartRate.value.calories)
        assertFalse(vm.heartRate.value.canColor)
    }

    @Test
    fun theTripMapDrawsEachSegmentAsItsOwnLineColouredByItsOwnSamples() = runTest(dispatcher) {
        val a = segment(startedAt = 0, date = day1, lat = 37.0, n = 3)
        val b = segment(startedAt = 100_000, date = day2, lat = 38.0, n = 5)
        ready(a, listOf(HeartRateSample(0L, 90)))
        ready(b, listOf(HeartRateSample(100_000L, 170)))
        val vm = tripDetail()
        advanceUntilIdle()

        assertEquals(
            listOf(MapLine(rides.points(a).map { it.point }), MapLine(rides.points(b).map { it.point })),
            vm.lines.value,
            "one line per segment, in order, no connector",
        )

        vm.setColorMode(TrackColorMode.HEART_RATE)
        advanceUntilIdle()
        val lines = vm.lines.value
        assertEquals(listOf(3, 5), lines.map { it.colors?.size }, "colour lists as long as each segment's track")
        assertEquals(List(3) { "#1E88E5" }, lines[0].colors)
        assertEquals(List(5) { "#E53935" }, lines[1].colors)
    }

    /** Review minor 1: once no segment is detailed (no [심박] any more), the trip's lines lose their colours. */
    @Test
    fun theTripLinesAreNotColouredOnceTheToggleIsGone() = runTest(dispatcher) {
        val a = segment(startedAt = 0, n = 3)
        ready(a, listOf(HeartRateSample(0L, 90)))
        val vm = tripDetail()
        advanceUntilIdle()
        vm.setColorMode(TrackColorMode.HEART_RATE)
        advanceUntilIdle()
        assertEquals(3, vm.lines.value.single().colors?.size)

        // Read again: now not detailed.
        ready(a, listOf(HeartRateSample(0L, 90)), detailed = false)
        advanceUntilIdle()

        assertFalse(vm.heartRate.value.canColor)
        assertNull(vm.lines.value.single().colors, "no colours without the toggle and the key")
    }

    private companion object {
        const val TRIP = 1L
    }
}
