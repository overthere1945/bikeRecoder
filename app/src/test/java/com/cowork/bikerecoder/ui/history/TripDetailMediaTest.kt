package com.cowork.bikerecoder.ui.history

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
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
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The trip detail's video entry (V1.3.0 spec §1.1, §5.1): [영상 만들기] [이미지 만들기] only on a completed trip with a
 * finished ride of 10 m or more; "만든 영상·이미지" newest first, and the files deleted from the gallery dropped when
 * the screen opens — only when the gallery says so, never on a failed check (ruling V6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailMediaTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val media = FakeTripMedia()
    private val gallery = mutableMapOf<String, Boolean>()
    private val log = RecordingLog()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun trip(status: TripStatus) {
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
    }

    private suspend fun ride(distanceM: Double, close: Boolean = true) {
        val totals = SegmentTotals.ZERO.copy(distanceM = distanceM, movingTimeMs = 60_000)
        val id = rides.openSegment(TRIP, 0L, LocalDate.of(2026, 10, 25))
        rides.appendPoints(id, listOf(TrackPoint(0L, GeoPoint(37.5, 127.0), null, 5f, 5f)), totals)
        if (close) rides.closeSegment(id, 1_000L, totals)
    }

    private fun TestScope.detail() = TripDetailViewModel(
        rides, TRIP, store,
        PostProcessor(rides, store, FakePhotoSource(), FakeHeartRateSource(), clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> }),
        log, compute = dispatcher,
        tripStore = trips,
        tripMedia = media,
        mediaExists = { uri -> gallery[uri] ?: throw IllegalStateException("MediaStore query failed for $uri") },
    )

    @Test
    fun aCompletedTripWithARideOffersTheButtons() = runTest(dispatcher) {
        trip(TripStatus.COMPLETED)
        ride(distanceM = 1_500.0)
        val vm = detail()
        advanceUntilIdle()
        assertTrue(vm.canMakeMedia.value)
    }

    @Test
    fun anActiveTripOrOneWithoutATargetRideDoesNot() = runTest(dispatcher) {
        trip(TripStatus.ACTIVE)
        ride(distanceM = 1_500.0)
        val active = detail()
        advanceUntilIdle()
        assertFalse(active.canMakeMedia.value)

        trip(TripStatus.COMPLETED)
        rides.deleteTrip(TRIP)
        trip(TripStatus.COMPLETED)
        ride(distanceM = 9.0)
        val short = detail()
        advanceUntilIdle()
        assertFalse(short.canMakeMedia.value)
    }

    @Test
    fun theMadeFilesAreListedNewestFirstAndGoneOnesPrunedOnOpen() = runTest(dispatcher) {
        trip(TripStatus.COMPLETED)
        ride(distanceM = 1_500.0)
        media.items.value = listOf(
            TripMedia(1, TRIP, MediaKind.VIDEO, "content://video/1", 100L, "{}"),
            TripMedia(2, TRIP, MediaKind.IMAGE, "content://image/2", 200L, "{}"),
            TripMedia(3, TRIP, MediaKind.IMAGE, "content://image/3", 300L, "{}"),
            TripMedia(4, 99L, MediaKind.IMAGE, "content://image/4", 400L, "{}"),
        )
        gallery["content://video/1"] = true
        gallery["content://image/2"] = false
        // content://image/3: the check fails, so it is kept.
        val vm = detail()
        advanceUntilIdle()

        assertEquals(listOf(listOf(2L)), media.removed)
        assertEquals(listOf(3L, 1L), vm.media.value.map { it.id })
        assertEquals(1, log.messages.count { it.contains("made file 3") })
    }

    private class FakeTripMedia : TripMediaStore {
        val items = MutableStateFlow<List<TripMedia>>(emptyList())
        val removed = mutableListOf<List<Long>>()

        override fun observe(tripId: Long): Flow<List<TripMedia>> =
            items.map { list -> list.filter { it.tripId == tripId }.sortedByDescending { it.createdAt } }

        override fun observeVideos(): Flow<List<TripMedia>> = error("not used")

        override suspend fun add(m: TripMedia): Long = error("not used")

        override suspend fun remove(ids: Collection<Long>) {
            removed += ids.toList()
            items.value = items.value.filterNot { it.id in ids }
        }
    }

    private companion object {
        const val TRIP = 3L
    }
}
