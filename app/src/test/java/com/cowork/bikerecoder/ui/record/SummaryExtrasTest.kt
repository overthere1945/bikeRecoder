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
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
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
 * times; a detailed READY (I5: not a sparse one), closing the summary or leaving the screen stops that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryExtrasTest {

    private val trips = FakeTripStore()
    private val rides = FakeRideStore(trips)
    private val store = FakePostStore { rides.segments(TRIP) }
    private val heartRate = FakeHeartRateSource()
    private val shown = MutableStateFlow<Long?>(null)
    private val offline = RecordingOfflineMaps()
    private val shortRides = ShortRides(rides, trips, offline, RecordingLog())

    /** A finished one-minute ride ([totals] and [endedAt] for a longer one) of a trip with [status]. */
    private suspend fun TestScope.setup(
        totals: SegmentTotals = TOTALS,
        endedAt: Long = 60_000L,
        status: TripStatus = TripStatus.COMPLETED,
    ): Pair<Long, SummaryExtras> {
        trips.trips[TRIP] = Trip(TRIP, TripType.SINGLE_DAY, status, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, null)
        val id = rides.openSegment(TRIP, 0L, LocalDate.of(2026, 10, 18))
        rides.appendPoints(id, listOf(TrackPoint(0L, GeoPoint(37.5, 127.0), null, 5f, 5f)), totals)
        rides.closeSegment(id, endedAt, totals)
        val processor = PostProcessor(
            rides, store, FakePhotoSource(), heartRate, clock = { testScheduler.currentTime }, scope = backgroundScope,
            warnLog = { _, _ -> },
        )
        return id to SummaryExtras(shown, store, processor, shortRides, backgroundScope)
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

    /**
     * I5: all-day samples (about one per 10 min) can be in Health Connect before the watch workout arrives (2–11 min
     * after the end). READY from those alone is not detailed, so the reads go on until the detailed heart rate is in.
     */
    @Test
    fun aSparseReadyKeepsReadingUntilTheDetailedHeartRateArrives() = runTest {
        val (id, extras) = setup(TOTALS.copy(movingTimeMs = 10 * MINUTE), endedAt = 10 * MINUTE)
        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(5 * MINUTE, 95)), calories = null) }
        extras.onScreen(true)
        shown.value = id
        runCurrent()
        assertEquals(HrStatus.READY, extras.state.value.heartRate?.hrStatus)
        assertEquals(false, extras.state.value.heartRate?.hrDetailed)

        advanceTimeBy(3 * MINUTE + 1)
        assertEquals(3, refetches(), "sparse READY: still read every minute")

        val workout = (0 until 10).map { HeartRateSample(it * MINUTE + 30_000L, 120 + it) }
        heartRate.reads = { _, _ -> HrRead.Samples(workout, calories = 80.0) }
        advanceTimeBy(MINUTE)
        assertEquals(4, refetches())
        assertEquals(true, extras.state.value.heartRate?.hrDetailed)
        assertEquals(80.0, extras.state.value.heartRate?.calories)

        advanceTimeBy(20 * MINUTE)
        assertEquals(4, refetches(), "detailed READY: no more reads")
    }

    @Test
    fun aSparseReadyIsKeptWhenALaterReadIsEmpty() = runTest {
        val (id, extras) = setup(TOTALS.copy(movingTimeMs = 10 * MINUTE), endedAt = 10 * MINUTE)
        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(5 * MINUTE, 95)), calories = null) }
        extras.onScreen(true)
        shown.value = id
        runCurrent()

        heartRate.reads = { _, _ -> HrRead.Empty }
        advanceTimeBy(30 * MINUTE)

        assertEquals(15, refetches(), "at most 15 reads")
        assertEquals(HrStatus.READY, extras.state.value.heartRate?.hrStatus, "R7: the sparse heart rate stays")
        assertEquals(95, extras.state.value.heartRate?.avgBpm)
    }

    /** Spec §6.1: the summary's reads replace the heart rate only when they bring more samples than are stored. */
    @Test
    fun aSummaryReadWithNoMoreSamplesKeepsTheStoredHeartRate() = runTest {
        val (id, extras) = setup(TOTALS.copy(movingTimeMs = 10 * MINUTE), endedAt = 10 * MINUTE)
        val sparse = listOf(HeartRateSample(2 * MINUTE, 90), HeartRateSample(8 * MINUTE, 110))
        heartRate.reads = { _, _ -> HrRead.Samples(sparse, calories = null) }
        extras.onScreen(true)
        shown.value = id
        runCurrent()
        assertEquals(100, extras.state.value.heartRate?.avgBpm)

        heartRate.reads = { _, _ -> HrRead.Samples(listOf(HeartRateSample(5 * MINUTE, 150)), calories = 30.0) }
        advanceTimeBy(2 * MINUTE + 1)
        assertEquals(2, refetches(), "sparse READY: still read")
        assertEquals(100, extras.state.value.heartRate?.avgBpm, "one sample does not replace two")
        assertEquals(30.0, extras.state.value.heartRate?.calories, "R17: calories missing so far are filled in")
        assertEquals(sparse, store.samples(id))
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

    /** Spec §6.10: a summary of a ride under 10 m asks "거의 움직이지 않은 기록입니다. 저장할까요?" once per segment. */
    @Test
    fun shortSummaryAsksOnce() = runTest {
        val (id, extras) = setup(SHORT)
        // The guidance screen's summary of the same segment (both view models can be alive at once).
        val guidance = SummaryExtras(shown, store, PostProcessor(
            rides, store, FakePhotoSource(), heartRate, clock = { 0L }, scope = backgroundScope, warnLog = { _, _ -> },
        ), shortRides, backgroundScope)
        assertEquals(false, extras.state.value.askShort, "no summary: nothing to ask")
        shown.value = id
        runCurrent()
        assertEquals(true, extras.state.value.askShort)
        assertEquals(true, guidance.state.value.askShort)

        extras.keepShort()
        runCurrent()
        assertEquals(false, extras.state.value.askShort, "answered")
        assertEquals(false, guidance.state.value.askShort, "answered on the other screen")
        shown.value = null
        runCurrent()
        shown.value = id
        runCurrent()
        assertEquals(false, extras.state.value.askShort, "not asked again for that segment")
    }

    @Test
    fun aLongRideIsNotAskedAboutNorDiscarded() = runTest {
        val (id, extras) = setup(SHORT.copy(distanceM = 10.0))
        shown.value = id
        runCurrent()
        assertEquals(false, extras.state.value.askShort, "10 m is a ride")

        var closed = 0
        extras.discardShort { closed++ }
        runCurrent()
        assertEquals(emptyList<Long>(), rides.deletedSegments, "never deletes a ride of 10 m or more")
        assertEquals(1, closed)
    }

    @Test
    fun discardDeletesSegmentAndEmptyFinishedTrip() = runTest {
        val (id, extras) = setup(SHORT)
        shown.value = id
        runCurrent()

        var closed = 0
        extras.discardShort { closed++ }
        extras.discardShort { closed++ } // a second tap before the dialog goes
        runCurrent()

        assertEquals(false, extras.state.value.askShort)
        assertEquals(listOf(id), rides.deletedSegments)
        assertEquals(listOf(TRIP), rides.deletedTrips)
        assertEquals(listOf(TRIP), offline.deleted, "the trip's offline maps go with it")
        assertEquals(1, closed, "then the summary closes")
    }

    @Test
    fun discardKeepsTripWithOtherSegments() = runTest {
        val (id, extras) = setup(SHORT)
        val earlier = rides.openSegment(TRIP, -100_000L, LocalDate.of(2026, 10, 18))
        rides.closeSegment(earlier, -50_000L, TOTALS)
        shown.value = id
        runCurrent()

        var closed = 0
        extras.discardShort { closed++ }
        runCurrent()

        assertEquals(listOf(id), rides.deletedSegments)
        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(listOf(earlier), rides.segments(TRIP).map { it.id })
        assertEquals(1, closed)
    }

    @Test
    fun discardKeepsActiveTrip() = runTest {
        val (id, extras) = setup(SHORT, status = TripStatus.ACTIVE)
        shown.value = id
        runCurrent()

        var closed = 0
        extras.discardShort { closed++ }
        runCurrent()

        assertEquals(listOf(id), rides.deletedSegments)
        assertEquals(emptyList<Long>(), rides.deletedTrips, "an ACTIVE trip is never deleted")
        assertEquals(emptyList<Long>(), offline.deleted)
        assertEquals(true, trips.trips.containsKey(TRIP))
        assertEquals(1, closed)
    }

    @Test
    fun keepLeavesEverything() = runTest {
        val (id, extras) = setup(SHORT)
        shown.value = id
        runCurrent()

        extras.keepShort()
        runCurrent()

        assertEquals(false, extras.state.value.askShort)
        assertEquals(emptyList<Long>(), rides.deletedSegments)
        assertEquals(emptyList<Long>(), rides.deletedTrips)
        assertEquals(listOf(id), rides.segments(TRIP).map { it.id })
    }

    private companion object {
        const val TRIP = 1L
        const val MINUTE = 60_000L
        val TOTALS = SegmentTotals.ZERO.copy(distanceM = 100.0, movingTimeMs = 60_000)
        val SHORT = SegmentTotals.ZERO.copy(distanceM = 0.27, movingTimeMs = 0)
    }
}
