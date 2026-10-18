package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HrRead
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ride.FakeRideStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The summary's heart rate (spec §3.2): while the summary is on screen it is read again every minute, at most 15
 * times; READY, closing the summary or leaving the screen stops that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryExtrasTest {

    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val heartRate = FakeHeartRateSource()
    private val shown = MutableStateFlow<Long?>(null)

    private suspend fun TestScope.setup(): Pair<Long, SummaryExtras> {
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
        val id = rides.openSegment(TRIP, 0L, LocalDate.of(2026, 10, 18))
        rides.appendPoints(id, listOf(TrackPoint(0L, GeoPoint(37.5, 127.0), null, 5f, 5f)), TOTALS)
        rides.closeSegment(id, 60_000L, TOTALS)
        val processor = PostProcessor(
            rides, store, FakePhotoSource(), heartRate, clock = { testScheduler.currentTime }, scope = backgroundScope,
            warnLog = { _, _ -> },
        )
        return id to SummaryExtras(shown, store, processor, backgroundScope)
    }

    /** Reads of the heart-rate source other than the one pass the summary asks for when it appears. */
    private fun refetches() = heartRate.calls.size - 1

    @Test
    fun readsAgainEveryMinuteFifteenTimesWhileShown() = runTest {
        val (id, extras) = setup()
        extras.onScreen(true)
        shown.value = id
        runCurrent()
        assertEquals(0, refetches(), "only the pass at first")

        advanceTimeBy(MINUTE + 1)
        assertEquals(1, refetches(), "one minute later")
        advanceTimeBy(14 * MINUTE)
        assertEquals(15, refetches(), "every minute for 15 minutes")
        advanceTimeBy(30 * MINUTE)
        assertEquals(15, refetches(), "no more after 15")
        assertEquals(HrStatus.PENDING, extras.state.value.heartRate?.hrStatus)
    }

    @Test
    fun stopsOnceTheHeartRateIsReady() = runTest {
        val (id, extras) = setup()
        extras.onScreen(true)
        shown.value = id
        runCurrent()

        advanceTimeBy(2 * MINUTE + 1)
        assertEquals(2, refetches())
        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(30_000L, 120)), calories = null) }
        advanceTimeBy(MINUTE)
        assertEquals(3, refetches())
        assertEquals(HrStatus.READY, extras.state.value.heartRate?.hrStatus)

        advanceTimeBy(20 * MINUTE)
        assertEquals(3, refetches(), "READY: no more reads")
    }

    @Test
    fun stopsWhenTheSummaryCloses() = runTest {
        val (id, extras) = setup()
        extras.onScreen(true)
        shown.value = id
        runCurrent()

        advanceTimeBy(3 * MINUTE + 1)
        assertEquals(3, refetches())
        shown.value = null
        advanceTimeBy(20 * MINUTE)
        assertEquals(3, refetches(), "closed: no more reads")
        assertEquals(null, extras.state.value.heartRate)
    }

    @Test
    fun pausesWhileTheScreenIsLeftAndCountsOnAfterwards() = runTest {
        val (id, extras) = setup()
        shown.value = id
        runCurrent()
        advanceTimeBy(5 * MINUTE)
        assertEquals(0, refetches(), "not on screen yet: only the pass")

        extras.onScreen(true)
        advanceTimeBy(4 * MINUTE + 1)
        assertEquals(4, refetches())
        extras.onScreen(false)
        advanceTimeBy(30 * MINUTE)
        assertEquals(4, refetches(), "left the screen: no reads")

        extras.onScreen(true)
        advanceTimeBy(60 * MINUTE)
        assertEquals(15, refetches(), "at most 15 in all for one summary")
    }

    /** Review minor 2: stopping and starting more often than once a minute does not put the next read off. */
    @Test
    fun aScreenStoppedAndStartedOftenStillReadsEveryMinute() = runTest {
        val (id, extras) = setup()
        extras.onScreen(true)
        shown.value = id
        runCurrent()

        // On for 25 s, off for 1 s, eleven times (to 286 s): reads fall due at 60, 120, 180 and 240 s.
        repeat(11) {
            advanceTimeBy(25_000)
            extras.onScreen(false)
            advanceTimeBy(1_000)
            extras.onScreen(true)
        }
        runCurrent()

        assertEquals(4, refetches(), "every minute although no stretch on screen lasts a minute")
    }

    @Test
    fun refetchNowReadsTheShownSegmentAtOnce() = runTest {
        val (id, extras) = setup()
        extras.refetchNow()
        runCurrent()
        assertEquals(0, heartRate.calls.size, "no summary: nothing to read")

        shown.value = id
        runCurrent()
        extras.refetchNow()
        runCurrent()
        assertEquals(1, refetches(), "[다시 가져오기]")
    }

    private companion object {
        const val TRIP = 1L
        const val MINUTE = 60_000L
        val TOTALS = SegmentTotals.ZERO.copy(distanceM = 100.0, movingTimeMs = 60_000)
    }
}
