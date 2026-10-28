package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.PhotoCandidate
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
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
import com.cowork.bikerecoder.post.PhotoSource
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The photos of the segment and trip detail view models (spec §2.3): state, hide/unhide, prune and a pass on open. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailPhotosTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val gallery = FakePhotoSource()
    private val log = RecordingLog()

    /** Counts [PhotoSource.existing] (one call per prune that has photos to check). */
    private var existingCalls = 0
    private val countingGallery = object : PhotoSource by gallery {
        override suspend fun existing(mediaIds: Collection<Long>): Set<Long> {
            existingCalls++
            return gallery.existing(mediaIds)
        }
    }

    /** Post-processing passes (each lists the segments needing work once). */
    private var passes = 0

    private val day1 = LocalDate.of(2026, 10, 17)
    private val day2 = LocalDate.of(2026, 10, 18)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        trips.trips[TRIP] = Trip(TRIP, TripType.MULTI_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
        store.beforeList = { passes++ }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.processor() = PostProcessor(
        rides, store, countingGallery, FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope,
        warnLog = { _, _ -> },
    )

    /** A closed segment with three points 0.0001° apart, starting at [startedAt]. */
    private suspend fun segment(startedAt: Long, date: LocalDate = day2, lat: Double = 37.5): Long {
        val id = rides.openSegment(TRIP, startedAt, date)
        val points = (0..2).map { i -> TrackPoint(startedAt + i * 1_000L, GeoPoint(lat + i * 0.0001, 127.0), null, 5f, 5f) }
        rides.appendPoints(id, points, TOTALS)
        rides.closeSegment(id, startedAt + 2_000L, TOTALS)
        return id
    }

    /**
     * Puts photos [mediaIds] on [segmentId], taken in that order inside it (so the segment owns them, ruling R13, and a
     * pass keeps them there), and keeps them in the gallery. The gallery copies are dated far from every ride, so a
     * post-processing pass never places them again.
     */
    private suspend fun photos(segmentId: Long, vararg mediaIds: Long) {
        val start = rides.segment(segmentId)!!.startedAt
        store.upsertPhotos(
            segmentId,
            mediaIds.map { m -> PlacedPhoto(m, start + 10L * m, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK) },
        )
        gallery.photos = gallery.photos + mediaIds.map { PhotoCandidate(it, FAR_AWAY + it, exif = null) }
    }

    private fun TestScope.segmentDetail(id: Long) =
        SegmentDetailViewModel(rides, id, store, processor(), log, compute = dispatcher)

    private fun TestScope.tripDetail() = TripDetailViewModel(rides, TRIP, store, processor(), log, compute = dispatcher)

    @Test
    fun segmentDetailSplitsVisibleAndHiddenPhotos() = runTest(dispatcher) {
        val id = segment(startedAt = 100)
        photos(id, 1, 2, 3)
        store.setHidden(store.photos.value.first { it.mediaId == 2L }.id, hidden = true)

        val vm = segmentDetail(id)
        advanceUntilIdle()

        assertEquals(listOf(1L, 3L), vm.photos.value.visible.map { it.mediaId })
        assertEquals(listOf(2L), vm.photos.value.hidden.map { it.mediaId })
    }

    @Test
    fun segmentDetailHidesAndShowsAgain() = runTest(dispatcher) {
        val id = segment(startedAt = 100)
        photos(id, 1, 2)
        val vm = segmentDetail(id)
        advanceUntilIdle()

        vm.setHidden(vm.photos.value.visible.first(), hidden = true)
        advanceUntilIdle()
        assertEquals(listOf(2L), vm.photos.value.visible.map { it.mediaId })
        assertEquals(listOf(1L), vm.photos.value.hidden.map { it.mediaId })
        assertEquals(true, store.photos.value.first { it.mediaId == 1L }.hidden)

        vm.setHidden(vm.photos.value.hidden.single(), hidden = false)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.photos.value.visible.map { it.mediaId })
        assertEquals(0, vm.photos.value.hidden.size)
    }

    @Test
    fun segmentDetailPrunesAndRequestsOnceOnOpen() = runTest(dispatcher) {
        val id = segment(startedAt = 100)
        photos(id, 1, 2)
        // Photo 2 was deleted from the gallery.
        gallery.photos = gallery.photos.filter { it.mediaId == 1L }

        val vm = segmentDetail(id)
        advanceUntilIdle()

        assertEquals(1, existingCalls, "pruned once")
        assertEquals(1, passes, "one post-processing pass")
        assertEquals(listOf(1L), vm.photos.value.visible.map { it.mediaId })
    }

    @Test
    fun tripDetailShowsThePhotosOfEveryDay() = runTest(dispatcher) {
        val a = segment(startedAt = 100, date = day1)
        val b = segment(startedAt = 10_000, date = day2)
        photos(a, 1)
        photos(b, 2, 3)

        val vm = tripDetail()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), vm.photos.value.visible.map { it.mediaId })
        vm.setHidden(vm.photos.value.visible[1], hidden = true)
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L), vm.photos.value.visible.map { it.mediaId })
        assertEquals(listOf(2L), vm.photos.value.hidden.map { it.mediaId })
    }

    @Test
    fun tripDetailPrunesAndRequestsOnceEvenWhenRefreshed() = runTest(dispatcher) {
        val a = segment(startedAt = 100, date = day1)
        val b = segment(startedAt = 10_000, date = day2)
        photos(a, 1)
        photos(b, 2)
        gallery.photos = gallery.photos.filter { it.mediaId == 2L }

        val vm = tripDetail()
        advanceUntilIdle()
        vm.refresh()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, existingCalls, "pruned once, for both segments")
        assertEquals(1, passes, "one post-processing pass")
        assertEquals(listOf(2L), vm.photos.value.visible.map { it.mediaId })
    }

    /** Ruling R10: each segment is its own line (no connector between segments or days). */
    @Test
    fun tripDetailMapHasTheSegmentsInOrderAsSeparateLines() = runTest(dispatcher) {
        val a = segment(startedAt = 100, date = day1, lat = 37.0)
        val b = segment(startedAt = 10_000, date = day2, lat = 38.0)

        val vm = tripDetail()
        advanceUntilIdle()

        assertEquals(listOf(rides.points(a), rides.points(b)).map { p -> p.map { it.point } }, vm.lines.value.map { it.points })
    }

    private companion object {
        const val TRIP = 1L
        const val FAR_AWAY = 1_000_000_000_000L
        val TOTALS = SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000)
    }
}
