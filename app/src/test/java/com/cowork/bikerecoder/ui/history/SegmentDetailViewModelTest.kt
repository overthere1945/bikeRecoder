package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEfforts
import com.cowork.bikerecoder.core.activity.BestEffort
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ride.FakeRideStore
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/**
 * Changing a finished segment's activity (V1.9.0 spec §1.5): offered only once the segment has ended, the totals are
 * recomputed with the new activity's recording rules, the run's best efforts follow, and a recording too short for the
 * rules changes the type only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val log = RecordingLog()
    private val day = LocalDate.of(2026, 10, 25)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.processor() = PostProcessor(
        rides, store, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> },
    )

    private fun TestScope.detail(id: Long, ride: RideStore = rides) =
        SegmentDetailViewModel(ride, id, store, processor(), log, compute = dispatcher)

    /**
     * A segment of [count] points one second and 3 m apart (3 m/s: 1.2 km in 400 s) with the stored [totals] of its
     * [activity]; [closed] = ended after the last point.
     */
    private suspend fun segment(
        activity: ActivityType,
        count: Int = 401,
        closed: Boolean = true,
        stepM: Double = 3.0,
    ): Long {
        val startedAt = 1_000_000L
        val step = stepM / 111_320.0
        val points = List(count) { i ->
            TrackPoint(startedAt + i * 1_000L, GeoPoint(37.5 + i * step, 127.0), 30.0, (stepM).toFloat(), 5f)
        }
        val totals = SegmentTotals.ZERO.copy(distanceM = 1_234.0, movingTimeMs = 400_000L, elapsedMs = 400_000L)
        val id = rides.openSegment(TRIP, startedAt, day, activity)
        rides.appendPoints(id, points, totals)
        if (closed) rides.closeSegment(id, startedAt + (count - 1) * 1_000L, totals)
        return id
    }

    /** A store that counts the writes of [RideStore.changeActivity] and can fail them. */
    private inner class CountingStore(var fail: Boolean = false) : RideStore by rides {
        val calls = mutableListOf<Triple<ActivityType, SegmentTotals?, List<BestEffort>>>()

        override suspend fun changeActivity(
            segmentId: Long,
            activity: ActivityType,
            totals: SegmentTotals?,
            efforts: List<BestEffort>,
        ) {
            if (fail) throw IOException("disk full")
            calls += Triple(activity, totals, efforts)
            rides.changeActivity(segmentId, activity, totals, efforts)
        }
    }

    @Test
    fun changeActivityHiddenWhileRecording() = runTest(dispatcher) {
        val open = segment(ActivityType.BIKE, closed = false)
        val done = segment(ActivityType.BIKE)

        val recording = detail(open)
        val finished = detail(done)
        advanceUntilIdle()

        assertFalse(recording.canChangeActivity.value, "the segment is still being recorded")
        assertTrue(finished.canChangeActivity.value)
    }

    @Test
    fun sameActivityWritesNothing() = runTest(dispatcher) {
        val id = segment(ActivityType.WALK)
        val counting = CountingStore()
        val vm = detail(id, counting)
        advanceUntilIdle()

        vm.changeActivity(ActivityType.WALK)
        advanceUntilIdle()

        assertTrue(counting.calls.isEmpty())
        assertFalse(vm.changeFailed.value)
    }

    @Test
    fun changeToRunSavesEfforts() = runTest(dispatcher) {
        val id = segment(ActivityType.WALK)
        val counting = CountingStore()
        val vm = detail(id, counting)
        advanceUntilIdle()

        vm.changeActivity(ActivityType.RUN)
        advanceUntilIdle()

        val (activity, totals, efforts) = counting.calls.single()
        assertEquals(ActivityType.RUN, activity)
        // 400 s of 3 m/s: the recomputed totals replace the stored ones.
        assertNotEquals(1_234.0, totals!!.distanceM)
        assertEquals(1_200.0, totals.distanceM, 30.0)
        assertEquals(BestEfforts.find(rides.points(id)), efforts)
        assertEquals(listOf(BestEffortDistance.ONE_K), efforts.map { it.distance })
        assertEquals(efforts, rides.bestEfforts[id])
        // The screen shows what was stored.
        assertEquals(ActivityType.RUN, rides.segment(id)!!.activity)
        assertEquals(ActivityType.RUN, (vm.state.value as SegmentDetailState.Loaded).segment.activity)
        assertEquals(totals.distanceM, (vm.state.value as SegmentDetailState.Loaded).segment.totals.distanceM)
    }

    @Test
    fun changeFromRunClearsEfforts() = runTest(dispatcher) {
        val id = segment(ActivityType.RUN)
        rides.saveBestEfforts(id, listOf(BestEffort(BestEffortDistance.ONE_K, 300_000L, 0)))
        val counting = CountingStore()
        val vm = detail(id, counting)
        advanceUntilIdle()

        vm.changeActivity(ActivityType.HIKE)
        advanceUntilIdle()

        val (activity, _, efforts) = counting.calls.single()
        assertEquals(ActivityType.HIKE, activity)
        assertTrue(efforts.isEmpty(), "efforts belong to runs only")
        assertEquals(emptyList<BestEffort>(), rides.bestEfforts[id])
    }

    @Test
    fun tooShortChangesOnlyType() = runTest(dispatcher) {
        // Five points 1 m apart: 4 m, under the 10 m the rules need.
        val id = segment(ActivityType.BIKE, count = 5, stepM = 1.0)
        val before = rides.segment(id)!!.totals
        val counting = CountingStore()
        val vm = detail(id, counting)
        advanceUntilIdle()

        vm.changeActivity(ActivityType.WALK)
        advanceUntilIdle()

        val (activity, totals, _) = counting.calls.single()
        assertEquals(ActivityType.WALK, activity)
        assertEquals(null, totals, "null keeps the stored totals")
        assertEquals(before, rides.segment(id)!!.totals)
        assertEquals(ActivityType.WALK, rides.segment(id)!!.activity)
    }

    @Test
    fun failureWritesNothingAndIsToldOnce() = runTest(dispatcher) {
        val id = segment(ActivityType.WALK)
        val counting = CountingStore(fail = true)
        val vm = detail(id, counting)
        advanceUntilIdle()

        vm.changeActivity(ActivityType.RUN)
        advanceUntilIdle()

        assertTrue(vm.changeFailed.value)
        assertEquals(1, log.messages.size)
        assertEquals(ActivityType.WALK, rides.segment(id)!!.activity)
        assertEquals(1_234.0, rides.segment(id)!!.totals.distanceM)
        assertEquals(ActivityType.WALK, (vm.state.value as SegmentDetailState.Loaded).segment.activity)

        vm.clearChangeFailed()
        assertFalse(vm.changeFailed.value)
    }

    @Test
    fun speedSeriesFollowsTheNewActivity() = runTest(dispatcher) {
        // 3 m/s = 10.8 km/h: a bike's second band, a run's third (10~12).
        val id = segment(ActivityType.BIKE)
        val vm = detail(id)
        advanceUntilIdle()
        val series = (vm.state.value as SegmentDetailState.Loaded).speedSeries!!
        val mid = series.totalM / 2
        val asBike = series.colorHexAt(mid)

        vm.changeActivity(ActivityType.RUN)
        advanceUntilIdle()

        val after = (vm.state.value as SegmentDetailState.Loaded).speedSeries!!
        assertNotEquals(asBike, after.colorHexAt(after.totalM / 2))
        assertEquals(listOf(ActivityType.RUN), after.segmentActivities)
    }

    private companion object {
        const val TRIP = 1L
    }
}
