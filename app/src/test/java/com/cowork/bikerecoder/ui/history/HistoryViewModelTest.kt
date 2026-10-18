package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/** The history list, the trip detail and the segment detail on [FakeRideStore]: delete guards, confirmation, refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val offline = RecordingOfflineMaps()
    private val log = RecordingLog()

    private val day1 = LocalDate.of(2026, 10, 11)
    private val day2 = LocalDate.of(2026, 10, 12)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun trip(id: Long, status: TripStatus, type: TripType = TripType.SINGLE_DAY) {
        trips.trips[id] = Trip(id, type, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
    }

    /** A segment of [tripId] started at [startedAt]; closed with [totals] unless [open]. */
    private suspend fun segment(
        tripId: Long,
        startedAt: Long,
        date: LocalDate = day2,
        open: Boolean = false,
        totals: SegmentTotals = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000),
    ): Long {
        val id = rides.openSegment(tripId, startedAt, date)
        if (!open) rides.closeSegment(id, startedAt + 1_000, totals)
        return id
    }

    private fun history(offlineMaps: OfflineMapController = offline) = HistoryViewModel(rides, offlineMaps, log)

    private val postStore = FakePostStore { trips.trips.keys.flatMap { rides.segments(it) } }

    private fun TestScope.postProcessor() = PostProcessor(
        rides, postStore, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope,
        warnLog = { _, _ -> },
    )

    private fun TestScope.tripDetail() = TripDetailViewModel(rides, 1, postStore, postProcessor(), compute = dispatcher)

    private fun TestScope.segmentDetail(id: Long) =
        SegmentDetailViewModel(rides, id, postStore, postProcessor(), log, compute = dispatcher)

    @Test
    fun activeTripCannotBeDeleted() = runTest(dispatcher) {
        trip(1, TripStatus.ACTIVE)
        segment(1, startedAt = 100)
        val vm = history()
        advanceUntilIdle()

        vm.requestDelete(1)
        advanceUntilIdle()

        assertEquals(HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE), vm.dialog.value)
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(listOf(1L), vm.trips.value.map { it.trip.id })
        vm.dismiss()
        assertNull(vm.dialog.value)
    }

    @Test
    fun completedTripBeingRecordedCannotBeDeleted() = runTest(dispatcher) {
        // Guidance arrival completes the trip while its recording still runs (open segment).
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100, open = true)
        val vm = history()
        advanceUntilIdle()

        vm.requestDelete(1)
        advanceUntilIdle()

        assertEquals(HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE), vm.dialog.value)
        assertEquals(emptyList<Long>(), rides.deletedTrips)
    }

    @Test
    fun completedTripIsDeletedAfterConfirm() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        segment(2, startedAt = 200)
        val vm = history()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), vm.trips.value.map { it.trip.id })

        vm.requestDelete(1)
        advanceUntilIdle()
        assertEquals(HistoryDialog.ConfirmDelete(1), vm.dialog.value)
        assertEquals(emptyList<Long>(), rides.deletedTrips)

        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(listOf(1L), offline.deleted)
        assertEquals(listOf(2L), vm.trips.value.map { it.trip.id })
        assertNull(vm.dialog.value)
    }

    @Test
    fun cancelledDeleteKeepsTheTrip() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val vm = history()
        advanceUntilIdle()

        vm.requestDelete(1)
        advanceUntilIdle()
        vm.dismiss()
        vm.confirmDelete()
        advanceUntilIdle()

        assertNull(vm.dialog.value)
        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(listOf(1L), vm.trips.value.map { it.trip.id })
    }

    @Test
    fun offlineMapFailureIsLoggedAndTheTripStillDeleted() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val failing = object : OfflineMapController {
            override suspend fun deleteForTrip(tripId: Long) = throw IOException("map storage")
            override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
        }
        val vm = history(failing)
        advanceUntilIdle()

        vm.requestDelete(1)
        advanceUntilIdle()
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(emptyList<Any>(), vm.trips.value)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun tripDetailGroupsSegmentsByDateWithTotals() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED, TripType.MULTI_DAY)
        val a = segment(1, 100, day1, totals = totals(10_000.0, 1_000, maxSpeed = 8.0, ascent = 30.0, min = 20.0, max = 90.0))
        val b = segment(1, 200, day2, totals = totals(5_000.0, 2_000, maxSpeed = 9.0, ascent = 10.0, min = 40.0, max = 120.0))
        val c = segment(1, 300, day2, totals = totals(1_000.0, 500, maxSpeed = 6.0, ascent = 5.0, min = null, max = null))
        val vm = tripDetail()
        advanceUntilIdle()

        assertEquals(listOf(day1, day2), vm.segmentsByDate.value.keys.toList())
        assertEquals(listOf(a), vm.segmentsByDate.value.getValue(day1).map { it.id })
        assertEquals(listOf(b, c), vm.segmentsByDate.value.getValue(day2).map { it.id })
        val total = vm.total.value
        assertEquals(16_000.0, total.distanceM, 1e-9)
        assertEquals(3_500L, total.movingTimeMs)
        assertEquals(9.0, total.maxSpeedMps, 1e-9)
        assertEquals(45.0, total.ascentM, 1e-9)
        assertEquals(20.0, total.minAltitudeM)
        assertEquals(120.0, total.maxAltitudeM)
    }

    @Test
    fun openSegmentCannotBeDeleted() = runTest(dispatcher) {
        trip(1, TripStatus.ACTIVE)
        val open = segment(1, startedAt = 100, open = true)
        val vm = tripDetail()
        advanceUntilIdle()

        vm.requestDeleteSegment(open)
        advanceUntilIdle()

        assertEquals(HistoryDialog.Message(MSG_OPEN_SEGMENT_NOT_DELETABLE), vm.dialog.value)
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), rides.deletedSegments)
    }

    @Test
    fun closedSegmentIsDeletedAfterConfirm() = runTest(dispatcher) {
        trip(1, TripStatus.ACTIVE)
        val first = segment(1, startedAt = 100)
        val second = segment(1, startedAt = 200)
        val vm = tripDetail()
        advanceUntilIdle()

        vm.requestDeleteSegment(first)
        advanceUntilIdle()
        assertEquals(HistoryDialog.ConfirmDelete(first), vm.dialog.value)
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(first), rides.deletedSegments)
        assertEquals(listOf(second), vm.segmentsByDate.value.values.flatten().map { it.id })
        assertEquals(1_000.0, vm.total.value.distanceM, 1e-9)
        assertNull(vm.dialog.value)
    }

    @Test
    fun segmentDetailLoadsTrackAndCharts() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        val id = segment(1, startedAt = 100)
        val points = listOf(0.0, 5.0, 10.0).mapIndexed { i, m ->
            TrackPoint(i * 1_000L, GeoPoint(37.5 + Math.toDegrees(m / 6_371_008.8), 127.0), 100.0 + i, null, 5f)
        }
        rides.appendPoints(id, points, SegmentTotals.ZERO)
        val vm = segmentDetail(id)
        advanceUntilIdle()

        val loaded = vm.state.value as SegmentDetailState.Loaded
        assertEquals(id, loaded.segment.id)
        assertEquals(points.map { it.point }, loaded.track)
        assertEquals(listOf(100.0, 101.0, 102.0), loaded.altitude.map { it.value })
        // No recorded speed: the first point has no predecessor, the others are 5 m/s = 18 km/h.
        assertEquals(2, loaded.speed.size)
        loaded.speed.forEach { assertEquals(18.0, it.value, 1e-6) }
    }

    @Test
    fun deletedSegmentShowsMissing() = runTest(dispatcher) {
        val vm = segmentDetail(42)
        advanceUntilIdle()

        assertEquals(SegmentDetailState.Missing, vm.state.value)
    }

    private fun totals(distance: Double, moving: Long, maxSpeed: Double, ascent: Double, min: Double?, max: Double?) =
        SegmentTotals(
            distanceM = distance,
            movingTimeMs = moving,
            elapsedMs = moving * 2,
            maxSpeedMps = maxSpeed,
            ascentM = ascent,
            descentM = ascent / 2,
            minAltitudeM = min,
            maxAltitudeM = max,
        )

    @Test
    fun aTripWithOnlyEmptySegmentsIsNotListed() = runTest(dispatcher) {
        // E.g. a recording started and stopped before any usable fix: kept in the store, not shown.
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = SegmentTotals.ZERO.copy(elapsedMs = 30_000))
        segment(2, startedAt = 200, date = day1, totals = SegmentTotals.ZERO.copy(elapsedMs = 30_000))
        segment(2, startedAt = 300, date = day2)
        val vm = history()
        advanceUntilIdle()

        val listed = vm.trips.value.single()
        assertEquals(2L, listed.trip.id)
        assertEquals(day2, listed.firstDate, "the empty segment's day does not count")
        assertEquals(1, listed.dayCount)
        assertEquals(3, rides.segments(1).size + rides.segments(2).size, "nothing deleted")
    }

    @Test
    fun emptySegmentsAreHiddenFromTheTripDetail() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100, date = day1, totals = SegmentTotals.ZERO.copy(elapsedMs = 30_000))
        val kept = segment(1, startedAt = 200, date = day2)
        val vm = tripDetail()
        advanceUntilIdle()

        assertEquals(listOf(day2), vm.segmentsByDate.value.keys.toList())
        assertEquals(listOf(kept), vm.segmentsByDate.value.getValue(day2).map { it.id })
        assertEquals(1_000.0, vm.total.value.distanceM)
        assertEquals(0L, vm.total.value.elapsedMs, "the hidden segment's time is not in the totals")
    }
}
