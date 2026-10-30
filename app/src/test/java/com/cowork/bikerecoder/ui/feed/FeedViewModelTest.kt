package com.cowork.bikerecoder.ui.feed

import androidx.lifecycle.viewModelScope
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.feed.FeedThumbKey
import com.cowork.bikerecoder.core.feed.ThumbSegment
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.TripDeleteCondition
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripLabelStore
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ui.history.CONFIRM_DELETE_TRIP
import com.cowork.bikerecoder.ui.history.HistoryDialog
import com.cowork.bikerecoder.ui.history.MSG_ACTIVE_TRIP_NOT_DELETABLE
import com.cowork.bikerecoder.ui.history.MSG_OPEN_SEGMENT_NOT_DELETABLE
import com.cowork.bikerecoder.ui.history.SegmentDetailState
import com.cowork.bikerecoder.ui.history.SegmentDetailViewModel
import com.cowork.bikerecoder.ui.history.TripDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The history list, the trip detail and the segment detail on [FakeRideStore]: delete guards, confirmation, refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

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

    private val stats = FakeRideStats { trips.trips.keys.flatMap { rides.segments(it) } }
    private val labels = FakeTripLabelStore()
    private val media = FakeFeedMedia()
    private val pictures = FakeFeedPictures()
    private val weeklyGoal = MutableStateFlow<Int?>(null)

    /** (trip, first point) of every region lookup the feed asked for. */
    private val regionAsks = mutableListOf<Pair<Long, GeoPoint>>()

    /** Wednesday 2026-10-28 12:00 in Seoul. */
    private var nowMs = seoul(2026, 10, 28, 12, 0)

    private fun seoul(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, SEOUL).toInstant().toEpochMilli()

    /** An open segment that has already gone [distance] metres. */
    private suspend fun recording(tripId: Long, startedAt: Long, distance: Double = 500.0): Long {
        val id = segment(tripId, startedAt, open = true)
        rides.appendPoints(id, emptyList(), SegmentTotals.ZERO.copy(distanceM = distance, movingTimeMs = 30_000))
        return id
    }

    private fun TestScope.history(
        offlineMaps: OfflineMapController = offline,
        processor: PostProcessor = postProcessor(),
        rideStore: RideStore = rides,
    ) = FeedViewModel(
        rideStore, postStore, processor, offlineMaps, log,
        rideStats = stats,
        labels = labels,
        weeklyGoalKm = weeklyGoal,
        tripMedia = media,
        pictures = pictures,
        ensureRegion = { tripId, start ->
            regionAsks += tripId to start
            null
        },
        clock = { nowMs },
        zone = SEOUL,
    )

    private val postStore = FakePostStore { trips.trips.keys.flatMap { rides.segments(it) } }

    /** A pass that finds nothing to do: it only counts [PostProcessor.finishedRuns] up. */
    private fun TestScope.idleProcessor() = PostProcessor(
        rides, object : PostStore by postStore {
            override suspend fun segmentsNeedingWork(now: Long): List<RideSegment> = emptyList()
        },
        FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> },
    )

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

    /** Spec §6.10: a trip under 10 m in all that is not in progress is hidden and counted for [정리]. */
    @Test
    fun historyHidesShortTrips() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        trip(3, TripStatus.ACTIVE)
        trip(4, TripStatus.COMPLETED)
        trip(5, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = short(0.27))
        segment(2, startedAt = 200)
        segment(3, startedAt = 300, totals = short(0.08)) // ACTIVE: in progress, shown
        segment(4, startedAt = 400, totals = short(4.0))
        segment(4, startedAt = 450, totals = short(5.0)) // 9 m in all: still short
        segment(5, startedAt = 500, totals = short(6.0))
        segment(5, startedAt = 550, totals = short(4.0)) // 10 m in all: a ride
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(5L, 3L, 2L), vm.trips.value.map { it.trip.id })
        assertEquals(2, vm.hiddenShortCount.value)
    }

    @Test
    fun aShortTripBeingRecordedIsShown() = runTest(dispatcher) {
        // Guidance arrival completed the trip while its recording runs: the closed short segment's trip is in progress.
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = short(1.0))
        segment(1, startedAt = 200, open = true)
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.trips.value.map { it.trip.id })
        assertEquals(0, vm.hiddenShortCount.value)
        vm.requestCleanup()
        advanceUntilIdle()
        assertNull(vm.dialog.value, "nothing hidden: nothing to clean up")
    }

    @Test
    fun cleanupDeletesOnlyHiddenShortTrips() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        trip(3, TripStatus.ACTIVE)
        trip(4, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = short(0.27))
        segment(2, startedAt = 200)
        segment(3, startedAt = 300, totals = short(0.08))
        segment(4, startedAt = 400, totals = short(3.0))
        val vm = history()
        advanceUntilIdle()

        vm.requestCleanup()
        advanceUntilIdle()
        assertEquals(HistoryDialog.ConfirmCleanup(2), vm.dialog.value)
        assertEquals(emptyList<Long>(), rides.deletedTrips, "asks first")

        // Trip 4 started again meanwhile (now ACTIVE): it is not deleted.
        trips.trips[4] = trips.trips.getValue(4).copy(status = TripStatus.ACTIVE)
        vm.confirmCleanup()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(listOf(1L), offline.deleted)
        assertEquals(listOf(4L, 3L, 2L), vm.trips.value.map { it.trip.id })
        assertEquals(0, vm.hiddenShortCount.value)
        assertNull(vm.dialog.value)
    }

    @Test
    fun cancelledCleanupKeepsEverything() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = short(0.27))
        val vm = history()
        advanceUntilIdle()

        vm.requestCleanup()
        advanceUntilIdle()
        vm.dismiss()
        vm.confirmCleanup()
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(1, vm.hiddenShortCount.value)
    }

    /** Ruling R21: a trip whose segments are all exactly 0 m is short too — hidden, counted and cleaned up. */
    @Test
    fun zeroMetreTripsCountAsShort() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.ACTIVE)
        trip(3, TripStatus.COMPLETED)
        trip(4, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = SegmentTotals.ZERO)
        segment(1, startedAt = 150, totals = SegmentTotals.ZERO)
        segment(2, startedAt = 200, totals = SegmentTotals.ZERO) // ACTIVE: not counted
        segment(3, startedAt = 300)
        segment(4, startedAt = 400, totals = SegmentTotals.ZERO)
        segment(4, startedAt = 450, open = true) // being recorded: not counted
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(3L), vm.trips.value.map { it.trip.id })
        assertEquals(1, vm.hiddenShortCount.value)

        vm.requestCleanup()
        advanceUntilIdle()
        assertEquals(HistoryDialog.ConfirmCleanup(1), vm.dialog.value)
        vm.confirmCleanup()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(0, vm.hiddenShortCount.value)
        assertEquals(setOf(2L, 3L, 4L), trips.trips.keys)
    }

    /**
     * Ruling R20: while one trip's offline maps are being deleted (which can wait for a running download), another
     * counted trip turns ACTIVE: it is not deleted, as each delete checks the status in its own statement.
     */
    @Test
    fun cleanupKeepsATripThatTurnsActiveWhileMapsAreDeleted() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 200, totals = short(1.0))
        segment(2, startedAt = 100, totals = short(2.0))
        val gate = CompletableDeferred<Unit>()
        val mapsDeleted = mutableListOf<Long>()
        val slowMaps = object : OfflineMapController {
            override suspend fun deleteForTrip(tripId: Long) {
                gate.await()
                mapsDeleted += tripId
            }
            override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
        }
        val vm = history(slowMaps)
        advanceUntilIdle()
        vm.requestCleanup()
        advanceUntilIdle()
        vm.confirmCleanup()
        advanceUntilIdle()
        assertEquals(listOf(1L), rides.deletedTrips, "the first trip's row is gone; its maps are being deleted")

        trips.trips[2] = trips.trips.getValue(2).copy(status = TripStatus.ACTIVE)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips, "trip 2 turned ACTIVE: kept")
        assertEquals(listOf(1L), mapsDeleted, "maps only for the deleted trip")
        assertEquals(listOf(2L), vm.trips.value.map { it.trip.id })
    }

    /** Ruling R20: the single delete is conditional too — a trip found ACTIVE at the delete is kept and says why. */
    @Test
    fun singleDeleteOfATripThatTurnedActiveIsRefused() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val slow = object : RideStore by rides {
            var flipOnNextOpenRead = false
            override suspend fun openSegments() = rides.openSegments().also {
                if (flipOnNextOpenRead) trips.trips[1] = trips.trips.getValue(1).copy(status = TripStatus.ACTIVE)
            }
        }
        val vm = history(rideStore = slow)
        advanceUntilIdle()
        vm.requestDelete(1)
        advanceUntilIdle()
        assertEquals(HistoryDialog.ConfirmDelete(1), vm.dialog.value)

        // The trip starts right after the in-progress check passed, before the delete.
        slow.flipOnNextOpenRead = true
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(emptyList<Long>(), offline.deleted)
        assertEquals(HistoryDialog.Message(MSG_ACTIVE_TRIP_NOT_DELETABLE), vm.dialog.value)
    }

    /**
     * A trip with no segment (guidance only) is never counted as short, and the [정리] statement itself refuses it —
     * not only the caller that picks the ids (final review FIX-NOW 4, like the Room DAO).
     */
    @Test
    fun aTripWithoutSegmentsIsNeitherCountedNorCleanedUp() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(2, startedAt = 200, totals = short(0.27))
        val vm = history()
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), vm.trips.value.map { it.trip.id })
        assertEquals(1, vm.hiddenShortCount.value)
        vm.requestCleanup()
        advanceUntilIdle()
        vm.confirmCleanup()
        advanceUntilIdle()

        assertEquals(listOf(2L), rides.deletedTrips)
        assertFalse(rides.deleteTripIf(1, TripDeleteCondition.SHORT_NOT_RECORDING), "no segment: not a short ride")
        assertEquals(setOf(1L), trips.trips.keys)
    }

    /**
     * The trip row is gone: its offline maps are still deleted when the list is left while they wait (e.g. for a
     * running download), so no map region is left behind (final review FIX-NOW 3).
     */
    @Test
    fun mapDeleteFinishesWhenTheListIsLeft() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val gate = CompletableDeferred<Unit>()
        val mapsDeleted = mutableListOf<Long>()
        val slowMaps = object : OfflineMapController {
            override suspend fun deleteForTrip(tripId: Long) {
                gate.await()
                mapsDeleted += tripId
            }
            override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
        }
        val vm = history(slowMaps)
        advanceUntilIdle()
        vm.requestDelete(1)
        advanceUntilIdle()
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(listOf(1L), rides.deletedTrips, "the row is gone; its maps are being deleted")

        vm.viewModelScope.cancel() // the list is left
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1L), mapsDeleted)
    }

    /** Spec §6.11: an open list shows what a pass saved once the pass ends, like the cumulative map (FIX-NOW 2). */
    @Test
    fun cardsRefreshWhenAPassFinishes() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        val id = segment(1, startedAt = 100)
        val processor = idleProcessor()
        val vm = history(processor = processor)
        advanceUntilIdle()
        assertEquals(emptyList<Pair<List<GeoPoint>, String>>(), vm.card(1).routes)

        // The pass saves the route, the photo and the heart rate.
        val p = GeoPoint(37.5, 127.0)
        val q = GeoPoint(37.51, 127.01)
        route(id, p, q)
        postStore.upsertPhotos(id, listOf(photo(1)))
        heartRate(id, 137)
        advanceUntilIdle()
        assertEquals(0, vm.card(1).photoMediaIds.size, "not read again before the pass ends")

        processor.runOnce()
        advanceUntilIdle()

        assertEquals(listOf(listOf(p, q) to RideColors.colorAt(0)), vm.card(1).routes)
        assertEquals(listOf(1L), vm.card(1).photoMediaIds)
        assertEquals(137, vm.card(1).avgBpm)
    }

    private fun short(distance: Double) = SegmentTotals.ZERO.copy(distanceM = distance, movingTimeMs = 1_000)

    private fun ride(distance: Double, movingMs: Long = 60_000) =
        SegmentTotals.ZERO.copy(distanceM = distance, movingTimeMs = movingMs)

    private suspend fun route(segmentId: Long, vararg points: GeoPoint) =
        postStore.saveSimplified(segmentId, CumulativeEntry(segmentId, day2, 0.0, 0L, points.toList()))

    private suspend fun heartRate(segmentId: Long, avg: Int?, status: HrStatus = HrStatus.READY) =
        postStore.saveExtras(SegmentExtras(segmentId, hrStatus = status, avgBpm = avg, maxBpm = avg?.plus(20)))

    private fun FeedViewModel.cards() = months.value.flatMap { it.cards }

    private fun FeedViewModel.card(tripId: Long) = cards().single { it.tripId == tripId }

    /**
     * Spec §6.11: "YYYY년 M월 · {거리} · {n}회" over the shown trips of the month of their first date, newest month first. The
     * distance is the sum of the cards below it (finished rides of 10 m or more, review M1), in the cards' "32.1 km".
     */
    @Test
    fun monthHeadersAndTotals() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED, TripType.MULTI_DAY)
        trip(3, TripStatus.COMPLETED)
        trip(4, TripStatus.COMPLETED)
        segment(1, startedAt = 100, date = LocalDate.of(2026, 9, 30), totals = ride(1_000.0))
        // Trip 2 began in September and went on into October: it belongs to September, its first date's month.
        segment(2, startedAt = 200, date = LocalDate.of(2026, 9, 30), totals = ride(500.0))
        segment(2, startedAt = 300, date = LocalDate.of(2026, 10, 1), totals = ride(500.0))
        segment(3, startedAt = 400, date = LocalDate.of(2026, 10, 3), totals = ride(1_000.0))
        segment(3, startedAt = 450, date = LocalDate.of(2026, 10, 3), totals = ride(2_449.0))
        // Under 10 m: not on trip 3's card, so not in the header either (3,458 m would read 3.5 km).
        segment(3, startedAt = 470, date = LocalDate.of(2026, 10, 3), totals = short(9.0))
        segment(4, startedAt = 500, date = LocalDate.of(2026, 10, 25), totals = short(3.0)) // hidden: not counted
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf("2026년 10월 · 3.4 km · 1회", "2026년 9월 · 2.0 km · 2회"), vm.months.value.map { it.header })
        assertEquals(3_449.0, vm.card(3).distanceM, 1e-9)
        assertEquals(listOf(listOf(3L), listOf(2L, 1L)), vm.months.value.map { g -> g.cards.map { it.tripId } })
    }

    /** Spec §2.3: one day "10월 25일 (일) 오후 6:12", several "10월 18일~20일", across months "10월 31일~11월 1일". */
    @Test
    fun dateLineOneDayAndSeveralDays() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED, TripType.MULTI_DAY)
        trip(3, TripStatus.COMPLETED, TripType.MULTI_DAY)
        segment(1, seoul(2026, 10, 25, 18, 12), LocalDate.of(2026, 10, 25))
        segment(2, seoul(2026, 10, 18, 9, 5), LocalDate.of(2026, 10, 18))
        segment(2, seoul(2026, 10, 20, 8, 0), LocalDate.of(2026, 10, 20))
        segment(3, seoul(2026, 10, 31, 23, 0), LocalDate.of(2026, 10, 31))
        segment(3, seoul(2026, 11, 1, 7, 0), LocalDate.of(2026, 11, 1))
        val vm = history()
        advanceUntilIdle()

        assertEquals("10월 25일 (일) 오후 6:12", vm.card(1).dateLine)
        assertEquals("10월 18일~20일", vm.card(2).dateLine)
        assertEquals("10월 31일~11월 1일", vm.card(3).dateLine)
        assertFalse(vm.card(1).inProgress)
    }

    @Test
    fun cardTitleUsesLabel() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        trip(3, TripStatus.COMPLETED)
        trip(4, TripStatus.COMPLETED, TripType.MULTI_DAY)
        val evening = seoul(2026, 10, 25, 18, 12)
        segment(1, evening, LocalDate.of(2026, 10, 25))
        segment(2, evening + 1_000_000, LocalDate.of(2026, 10, 25))
        segment(3, evening + 2_000_000, LocalDate.of(2026, 10, 25))
        segment(4, seoul(2026, 10, 18, 9, 0), LocalDate.of(2026, 10, 18))
        segment(4, seoul(2026, 10, 19, 9, 0), LocalDate.of(2026, 10, 19))
        labels.labels.value = mapOf(
            1L to TripLabel(customTitle = "퇴근 길 한 바퀴", regionName = "서울 강서구"),
            2L to TripLabel(regionName = "서울 강서구"),
            4L to TripLabel(regionName = "강원 춘천시"),
        )
        val vm = history()
        advanceUntilIdle()

        assertEquals("퇴근 길 한 바퀴", vm.card(1).title, "the custom title, no region")
        assertEquals("저녁 라이딩 · 서울 강서구", vm.card(2).title)
        assertEquals("저녁 라이딩", vm.card(3).title, "no region known yet")
        assertEquals("2일 여행 · 강원 춘천시", vm.card(4).title)

        // A region found later reaches the open list.
        labels.setRegionName(3, "서울 마포구")
        advanceUntilIdle()
        assertEquals("저녁 라이딩 · 서울 마포구", vm.card(3).title)
    }

    /** Spec §2.3: the numbers are the sums over the finished segments of 10 m or more; average = distance / moving time. */
    @Test
    fun cardNumbersSumRidesOfTenMetresOrMore() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = ride(1_000.0, movingMs = 300_000).copy(ascentM = 12.0))
        segment(1, startedAt = 200, totals = ride(2_000.0, movingMs = 600_000).copy(ascentM = 30.0))
        segment(1, startedAt = 300, totals = ride(9.0, movingMs = 5_000).copy(ascentM = 99.0)) // under 10 m: not counted
        segment(2, startedAt = 400, totals = ride(20.0, movingMs = 0))
        val vm = history()
        advanceUntilIdle()

        val card = vm.card(1)
        assertEquals(3_000.0, card.distanceM, 1e-9)
        assertEquals(900_000L, card.movingTimeMs)
        assertEquals(42.0, card.ascentM, 1e-9)
        assertEquals(3_000.0 / 900.0, card.avgSpeedMps, 1e-9)
        assertEquals(0.0, vm.card(2).avgSpeedMps, 1e-9, "no moving time: 0")
    }

    @Test
    fun photoAndHeartRateOnlyWhenPresent() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        val a = segment(1, startedAt = 100, totals = ride(1_000.0, movingMs = 60_000))
        val b = segment(1, startedAt = 200, totals = ride(1_000.0, movingMs = 180_000))
        val c = segment(2, startedAt = 300)
        val d = segment(2, startedAt = 400)
        postStore.upsertPhotos(a, listOf(photo(1), photo(2)))
        postStore.upsertPhotos(b, listOf(photo(3)))
        postStore.setHidden(postStore.photos.value.single { it.mediaId == 3L }.id, true) // hidden: not counted
        heartRate(a, 130)
        heartRate(b, 150)
        heartRate(c, 140, HrStatus.PENDING) // not READY: not counted
        heartRate(d, null)
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), vm.card(1).photoMediaIds)
        // Weighted by moving time: (130 × 1 min + 150 × 3 min) / 4 min = 145.
        assertEquals(145, vm.card(1).avgBpm)
        assertEquals(emptyList<Long>(), vm.card(2).photoMediaIds)
        assertNull(vm.card(2).avgBpm)
    }

    @Test
    fun cardWithoutRouteHasEmptyPreview() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        val first = segment(1, startedAt = 100)
        segment(1, startedAt = 200) // no simplified route yet
        val third = segment(1, startedAt = 300)
        segment(2, startedAt = 400)
        val p = GeoPoint(37.5, 127.0)
        val q = GeoPoint(37.51, 127.01)
        route(first, p, q)
        route(third, q, p)
        val vm = history()
        advanceUntilIdle()

        // Each segment keeps the colour of its place in the trip, also when one before it has no route yet.
        assertEquals(
            listOf(listOf(p, q) to RideColors.colorAt(0), listOf(q, p) to RideColors.colorAt(2)),
            vm.card(1).routes,
        )
        assertEquals(emptyList<Pair<List<GeoPoint>, String>>(), vm.card(2).routes)
    }

    @Test
    fun manyTripsLoad() = runTest(dispatcher) {
        val count = 1_200 // more than SQLite's 999 bound variables
        for (id in 1L..count) {
            trip(id, TripStatus.COMPLETED)
            route(segment(id, startedAt = id), GeoPoint(37.5, 127.0), GeoPoint(37.5 + id * 1e-5, 127.0))
        }
        val vm = history()
        advanceUntilIdle()

        assertEquals(count, vm.cards().size)
        assertEquals(count, vm.cards().count { it.routes.size == 1 })
        assertEquals(1, postStore.segmentCardReads, "one read for all the cards, not one per trip")
    }

    @Test
    fun cardsStillListedWhenExtrasCannotBeRead() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        postStore.failSegmentCards = true
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.cards().map { it.tripId })
        assertEquals(emptyList<Pair<List<GeoPoint>, String>>(), vm.card(1).routes)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun cardPhotosMaxThreePlusMore() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        val a = segment(1, startedAt = 100)
        val b = segment(1, startedAt = 200)
        val c = segment(2, startedAt = 300)
        // Taken in the order of the media ids; photo 4 is hidden; photo 6 is in both segments (one picture).
        postStore.upsertPhotos(a, listOf(photo(5), photo(1), photo(4)))
        postStore.upsertPhotos(b, listOf(photo(3), photo(2), photo(6)))
        postStore.upsertPhotos(c, listOf(photo(9)))
        postStore.setHidden(postStore.photos.value.single { it.mediaId == 4L }.id, true)
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), vm.card(1).photoMediaIds)
        assertEquals(2, vm.card(1).morePhotos, "5 shown photos: 3 and +2")
        assertEquals(listOf(9L), vm.card(2).photoMediaIds)
        assertEquals(0, vm.card(2).morePhotos)
    }

    @Test
    fun cardVideoIsNewestVideo() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        segment(2, startedAt = 200)
        fun made(id: Long, tripId: Long, kind: MediaKind, createdAt: Long) =
            TripMedia(id, tripId, kind, "content://media/$id", createdAt, "{}")
        media.items.value = listOf(
            made(1, 1, MediaKind.VIDEO, createdAt = 10),
            made(2, 1, MediaKind.VIDEO, createdAt = 30),
            made(3, 1, MediaKind.IMAGE, createdAt = 50), // newer, but an image
            made(4, 1, MediaKind.VIDEO, createdAt = 20),
        )
        val vm = history()
        advanceUntilIdle()

        assertEquals("content://media/2", vm.card(1).videoUri)
        assertEquals(2L, vm.card(1).video?.id)
        assertNull(vm.card(2).video)
        assertNull(vm.card(2).videoUri)
    }

    /** Spec §2.3: 🏆 when the trip has a ride that holds one of the four personal records (§3.4). */
    @Test
    fun trophyOnRecordHolderTripsOnly() = runTest(dispatcher) {
        for (id in 1L..3L) trip(id, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = ride(10_000.0, movingMs = 1_800_000).copy(ascentM = 300.0))
        segment(2, startedAt = 200, totals = ride(4_000.0, movingMs = 1_200_000).copy(ascentM = 50.0))
        segment(3, startedAt = 300, totals = ride(3_000.0, movingMs = 900_000).copy(ascentM = 20.0))
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf(true, false, false), listOf(1L, 2L, 3L).map { vm.card(it).trophy })
    }

    @Test
    fun noTrophyBeforeASecondRide() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val vm = history()
        advanceUntilIdle()

        assertFalse(vm.card(1).trophy)
    }

    /** Spec §2.4: no picture key for a trip in progress or without a finished ride of 10 m or more; the line drawing then. */
    @Test
    fun inProgressCardHasNoThumbKey() = runTest(dispatcher) {
        trip(1, TripStatus.ACTIVE)
        trip(2, TripStatus.COMPLETED)
        trip(3, TripStatus.COMPLETED)
        trip(4, TripStatus.COMPLETED)
        trip(5, TripStatus.ACTIVE)
        segment(5, startedAt = 700) // ACTIVE between two days: nothing open, so its picture can be drawn
        segment(1, startedAt = 100)
        segment(1, startedAt = 200, open = true)
        recording(2, startedAt = 300) // completed by guidance, still recording
        segment(3, startedAt = 400)
        segment(4, startedAt = 500, totals = ride(6.0)) // too short to draw; the trip is listed as it is in progress
        recording(4, startedAt = 600)
        val vm = history()
        advanceUntilIdle()

        assertTrue(vm.card(1).inProgress)
        assertNull(vm.card(1).thumbKey)
        assertNull(vm.card(1).thumbFile)
        assertTrue(vm.card(2).inProgress)
        assertNull(vm.card(2).thumbKey)
        assertFalse(vm.card(3).inProgress)
        assertEquals(
            FeedThumbKey.of(listOf(ThumbSegment(rides.segments(3).single().id, 400 + 1_000, 1_000.0))),
            vm.card(3).thumbKey,
        )
        assertNull(vm.card(4).thumbKey, "no ride of 10 m or more")
        assertTrue(vm.card(5).inProgress)
        assertEquals(
            FeedThumbKey.of(listOf(ThumbSegment(rides.segments(5).single().id, 700 + 1_000, 1_000.0))),
            vm.card(5).thumbKey,
            "an ACTIVE trip with no open segment still has its picture",
        )
    }

    @Test
    fun thumbFileIsTheOneOfTheCurrentKey() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        segment(2, startedAt = 200)
        val vm = history()
        advanceUntilIdle()
        val key1 = vm.card(1).thumbKey!!
        assertNull(vm.card(1).thumbFile)

        pictures.filesFlow.value = mapOf(1L to File("/feed/1-$key1.webp"), 2L to File("/feed/2-0123456789ab.webp"))
        advanceUntilIdle()

        assertEquals(File("/feed/1-$key1.webp"), vm.card(1).thumbFile)
        assertNull(vm.card(2).thumbFile, "a picture of an older key is not this trip's picture")
    }

    @Test
    fun shownCardRequestsThumbAndRegion() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        val first = segment(1, startedAt = 100)
        segment(2, startedAt = 200)
        val p = GeoPoint(37.5, 127.0)
        route(first, p, GeoPoint(37.51, 127.01))
        labels.labels.value = mapOf(2L to TripLabel(regionName = "서울 강서구"))
        val vm = history()
        advanceUntilIdle()
        assertEquals(emptyList<Pair<Long, String>>(), pictures.wanted, "nothing before a card is on screen")

        vm.onCardShown(1)
        vm.onCardShown(2)
        advanceUntilIdle()

        assertEquals(listOf(1L to vm.card(1).thumbKey!!, 2L to vm.card(2).thumbKey!!), pictures.wanted)
        assertEquals(listOf(1L to p), regionAsks, "only the card whose label has no region yet")

        vm.onCardHidden(1)
        assertEquals(listOf(1L), pictures.unwanted)
    }

    @Test
    fun shownCardWithoutRouteAsksForTheFirstTrackPoint() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        val id = segment(1, startedAt = 100)
        val first = GeoPoint(37.6, 127.1)
        rides.appendPoints(id, listOf(TrackPoint(1L, first, null, 5f, 5f)), ride(1_000.0))
        val vm = history()
        advanceUntilIdle()

        vm.onCardShown(1)
        advanceUntilIdle()

        assertEquals(listOf(1L to first), regionAsks)
    }

    @Test
    fun aShownCardWhoseKeyChangesIsWantedAgain() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100)
        val vm = history()
        advanceUntilIdle()
        vm.onCardShown(1)
        val before = vm.card(1).thumbKey!!

        segment(1, startedAt = 200, totals = ride(2_000.0))
        vm.refresh()
        stats.tick.value++
        advanceUntilIdle()

        val after = vm.card(1).thumbKey!!
        assertTrue(before != after)
        assertEquals(listOf(1L to before, 1L to after), pictures.wanted)
    }

    @Test
    fun deleteAlsoDeletesThumbnail() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.ACTIVE)
        segment(1, startedAt = 100)
        segment(2, startedAt = 200, open = true)
        val vm = history()
        advanceUntilIdle()

        vm.requestDelete(2)
        advanceUntilIdle()
        vm.confirmDelete() // refused: in progress
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), pictures.deleted)

        vm.requestDelete(1)
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), pictures.deleted, "not before the delete is confirmed")
        vm.confirmDelete()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(listOf(1L), pictures.deleted)
    }

    @Test
    fun cleanupAlsoDeletesTheThumbnailsOfWhatItDeleted() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = short(3.0))
        val vm = history()
        advanceUntilIdle()

        vm.requestCleanup()
        vm.confirmCleanup()
        advanceUntilIdle()

        assertEquals(listOf(1L), rides.deletedTrips)
        assertEquals(listOf(1L), pictures.deleted)
    }

    /** Spec §2.1 / D5: this week up to today against last week up to the same weekday. */
    @Test
    fun weekStripSumsThisWeekAndComparesSameDay() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        // Today is Wednesday 2026-10-28; this week began Monday the 26th, last week on the 19th.
        segment(1, 1, LocalDate.of(2026, 10, 26), totals = ride(10_000.0))
        segment(1, 2, LocalDate.of(2026, 10, 28), totals = ride(5_000.0))
        segment(1, 3, LocalDate.of(2026, 10, 28), totals = ride(4.0)) // under 10 m: no ride
        segment(1, 4, LocalDate.of(2026, 10, 28), open = true) // still recording
        segment(1, 5, LocalDate.of(2026, 10, 19), totals = ride(4_000.0))
        segment(1, 6, LocalDate.of(2026, 10, 21), totals = ride(1_000.0)) // last Wednesday: counted
        segment(1, 7, LocalDate.of(2026, 10, 22), totals = ride(20_000.0)) // last Thursday: after the same day
        segment(1, 8, LocalDate.of(2026, 10, 12), totals = ride(7_000.0)) // two weeks ago
        val vm = history()
        advanceUntilIdle()

        val strip = vm.weekStrip.value
        assertEquals(15_000.0, strip.distanceM, 1e-9)
        assertEquals(2, strip.rides)
        assertEquals(10_000.0, strip.diffM!!, 1e-9)
    }

    @Test
    fun weekStripHasNoComparisonWhenBothWeeksAreEmpty() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, 1, LocalDate.of(2026, 10, 12), totals = ride(7_000.0))
        val vm = history()
        advanceUntilIdle()

        assertEquals(0.0, vm.weekStrip.value.distanceM, 1e-9)
        assertEquals(0, vm.weekStrip.value.rides)
        assertNull(vm.weekStrip.value.diffM)
    }

    @Test
    fun weekStripGoalAndStreak() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, 1, LocalDate.of(2026, 10, 27))
        segment(1, 2, LocalDate.of(2026, 10, 20))
        segment(1, 3, LocalDate.of(2026, 10, 13))
        segment(1, 4, LocalDate.of(2026, 10, 1)) // the week of 9/28: a gap at 10/5
        val vm = history()
        advanceUntilIdle()

        assertNull(vm.weekStrip.value.weeklyGoalKm)
        assertEquals(Streak(3, includesThisWeek = true), vm.weekStrip.value.streak)

        weeklyGoal.value = 100
        advanceUntilIdle()
        assertEquals(100, vm.weekStrip.value.weeklyGoalKm)

        // A week later and nothing yet in the new week: the streak runs through last week.
        nowMs = seoul(2026, 11, 3, 12, 0)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(Streak(3, includesThisWeek = false), vm.weekStrip.value.streak)
        assertEquals(0, vm.weekStrip.value.rides)
    }

    /** A process that lives into a new week shows the new week after the next resume, with no ride recorded between. */
    @Test
    fun refreshTakesTheNewDateForTheWeekStrip() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        segment(1, 1, LocalDate.of(2026, 10, 27), totals = ride(8_000.0))
        val vm = history()
        advanceUntilIdle()
        assertEquals(8_000.0, vm.weekStrip.value.distanceM, 1e-9)
        assertEquals(Streak(1, includesThisWeek = true), vm.weekStrip.value.streak)

        nowMs = seoul(2026, 11, 3, 9, 0) // Tuesday of the next week; no stats emission
        advanceUntilIdle()
        assertEquals(8_000.0, vm.weekStrip.value.distanceM, 1e-9, "not before the screen is resumed")

        vm.refresh()
        advanceUntilIdle()

        assertEquals(0.0, vm.weekStrip.value.distanceM, 1e-9)
        assertEquals(0, vm.weekStrip.value.rides)
        assertEquals(Streak(1, includesThisWeek = false), vm.weekStrip.value.streak)
        assertEquals(-8_000.0, vm.weekStrip.value.diffM!!, 1e-9, "last week up to Tuesday has the Tuesday-or-earlier ride")
    }

    @Test
    fun feedIsReadyAfterTheFirstLoad() = runTest(dispatcher) {
        val vm = history()
        assertFalse(vm.ready.value)
        advanceUntilIdle()
        assertTrue(vm.ready.value)
        assertEquals(emptyList<FeedMonth>(), vm.months.value)
    }

    private fun photo(mediaId: Long) =
        PlacedPhoto(mediaId, takenAt = mediaId, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK)

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
