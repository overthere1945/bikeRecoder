package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.CumulativeEntry
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideStore
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
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.CompletableDeferred
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

    private fun history(offlineMaps: OfflineMapController = offline) = HistoryViewModel(rides, postStore, offlineMaps, log)

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
        val vm = HistoryViewModel(slow, postStore, offline, log)
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

    private fun short(distance: Double) = SegmentTotals.ZERO.copy(distanceM = distance, movingTimeMs = 1_000)

    private fun ride(distance: Double, movingMs: Long = 60_000) =
        SegmentTotals.ZERO.copy(distanceM = distance, movingTimeMs = movingMs)

    private suspend fun route(segmentId: Long, vararg points: GeoPoint) =
        postStore.saveSimplified(segmentId, CumulativeEntry(segmentId, day2, 0.0, 0L, points.toList()))

    private suspend fun heartRate(segmentId: Long, avg: Int?, status: HrStatus = HrStatus.READY) =
        postStore.saveExtras(SegmentExtras(segmentId, hrStatus = status, avgBpm = avg, maxBpm = avg?.plus(20)))

    private fun HistoryViewModel.cards() = months.value.flatMap { it.cards }

    private fun HistoryViewModel.card(tripId: Long) = cards().single { it.tripId == tripId }

    /** Spec §6.11: "YYYY년 M월 · {거리} · {n}회" over the shown trips of the month of their first date, newest month first. */
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
        segment(3, startedAt = 450, date = LocalDate.of(2026, 10, 3), totals = ride(2_450.0))
        segment(4, startedAt = 500, date = LocalDate.of(2026, 10, 25), totals = short(3.0)) // hidden: not counted
        val vm = history()
        advanceUntilIdle()

        assertEquals(listOf("2026년 10월 · 3.45km · 1회", "2026년 9월 · 2.00km · 2회"), vm.months.value.map { it.header })
        assertEquals(listOf(listOf(3L), listOf(2L, 1L)), vm.months.value.map { g -> g.cards.map { it.tripId } })
    }

    @Test
    fun badgeSameDayAndMultiDay() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED, TripType.MULTI_DAY)
        trip(3, TripStatus.COMPLETED, TripType.MULTI_DAY)
        segment(1, startedAt = 100, date = day1)
        segment(2, startedAt = 200, date = day1)
        segment(2, startedAt = 300, date = day2)
        segment(3, startedAt = 400, date = day2)
        val vm = history()
        advanceUntilIdle()

        assertEquals("당일", vm.card(1).badge)
        assertEquals("여러 날 · 2일", vm.card(2).badge)
        assertEquals("여러 날 · 1일", vm.card(3).badge, "the trip's kind, as before")
        assertEquals("10월 11일 (일)", vm.card(2).date, "the first date")
        assertEquals("10월 12일 (월)", vm.card(3).date)
    }

    @Test
    fun line2WithAverageSpeed() = runTest(dispatcher) {
        trip(1, TripStatus.COMPLETED)
        trip(2, TripStatus.COMPLETED)
        segment(1, startedAt = 100, totals = ride(1_000.0, movingMs = 300_000))
        segment(1, startedAt = 200, totals = ride(580.0, movingMs = 420_000))
        segment(2, startedAt = 300, totals = ride(20.0, movingMs = 0)) // no moving time: 0.0
        val vm = history()
        advanceUntilIdle()

        // 1.58 km in 12 min = 7.9 km/h (distance / moving time).
        assertEquals("1.58km · 12분 · 7.9km/h", vm.card(1).line2)
        assertEquals("0.02km · 0분 · 0.0km/h", vm.card(2).line2)
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

        assertEquals(2, vm.card(1).photoCount)
        // Weighted by moving time: (130 × 1 min + 150 × 3 min) / 4 min = 145.
        assertEquals(145, vm.card(1).avgBpm)
        assertEquals(0, vm.card(2).photoCount)
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

    private fun photo(mediaId: Long) =
        PlacedPhoto(mediaId, takenAt = mediaId, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK)
}
