package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffort
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import com.cowork.bikerecoder.core.activity.BestEfforts
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.location.SharedLocationSource
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.ride.RideFixtures.StopAt
import com.cowork.bikerecoder.ride.RideFixtures.north
import com.cowork.bikerecoder.ride.RideFixtures.straight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The controller on JVM fakes with virtual time. Fixes come from [ScriptedLocationSource] at speedup 1, so a fix's
 * time is the virtual time it arrives at (recordings start at virtual time 0 unless stated).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideControllerTest {

    private val trips = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val voice = RecordingVoiceOutput()
    private val log = RecordingLog()

    /** The test clock starts at epoch 0 = 1970-01-01 09:00 in Asia/Seoul. */
    private val testDay = LocalDate.of(1970, 1, 1)

    private inner class Harness(val scope: TestScope, var location: LocationSource) {
        val clock = { scope.testScheduler.currentTime }
        val store = FakeRideStore(trips, clock)
        val tripManager = TripManager(trips, offline, clock)
        val pressure = FakePressureSource()
        val settings = MutableStateFlow(AppSettings())
        /** Every sunset computation the controller asked for, and what it answers ([sunsetAnswer]). */
        val sunsetCalls = mutableListOf<SunsetCall>()
        var sunsetAnswer: (LocalDate) -> Long? = { null }
        val navigation = NavigationController(
            router = AssetRouter(emptyList()),
            tripManager = tripManager,
            tripStore = trips,
            offline = offline,
            voice = voice,
            locationSource = { ScriptedLocationSource(emptyList()) },
            voiceEnabled = flowOf(true),
            scope = scope.backgroundScope,
            clock = clock,
            log = log,
        )

        fun newController(scope: CoroutineScope = this.scope.backgroundScope, rideStore: RideStore = store) = RideController(
            rideStore = rideStore,
            tripStore = trips,
            tripManager = tripManager,
            navigation = navigation,
            voice = voice,
            location = location,
            pressure = pressure,
            settings = settings,
            scope = scope,
            clock = clock,
            log = log,
            sunsetOf = { lat, lon, date, zone ->
                sunsetCalls += SunsetCall(lat, lon, date, zone)
                sunsetAnswer(date)
            },
        )

        val controller = newController()

        suspend fun trip(type: TripType = TripType.SINGLE_DAY): Long =
            tripManager.startTrip(type, RouteProfile.CYCLEWAY_FIRST, emptyList()).id

        suspend fun start(tripId: Long, c: RideController = controller, activity: ActivityType = ActivityType.BIKE) {
            assertNotNull(c.startRecording(tripId, activity)?.also { it.join() }, "started")
            assertInstanceOf(RideUiState.Active::class.java, c.ui.value)
        }

        val active: RideUiState.Active get() = controller.ui.value as RideUiState.Active
    }

    data class SunsetCall(val lat: Double, val lon: Double, val date: LocalDate, val zone: ZoneId)

    private fun TestScope.harness(fixes: List<LocationFix>) = Harness(this, ScriptedLocationSource(fixes, speedup = 1.0))

    /** The scripted fixes (18 km/h, speedup 1) of a [splitHarness], shared like the app's location stream. */
    private lateinit var scripted: ScriptedLocationSource

    private fun TestScope.splitHarness(fixes: List<LocationFix> = straight(3_000.0)): Harness {
        scripted = ScriptedLocationSource(fixes, speedup = 1.0)
        return Harness(this, SharedLocationSource({ scripted }, backgroundScope))
    }

    private fun TestScope.play(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun kmReports() = voice.spoken.filter { "킬로미터 이동. 평균 시속" in it }.map { it.substringBefore("킬로미터").toInt() }

    @Test
    fun recordOnlyThreeKmAnnouncesStartAndEachKm() = runTest {
        val source = ScriptedLocationSource(straight(3_010.0), speedup = 1.0)
        val h = Harness(this, source)
        val tripId = h.trip(TripType.SINGLE_DAY)

        h.start(tripId)
        play(605_000)
        assertTrue(h.pressure.collecting, "pressure is collected while recording")
        h.controller.stopRecording(RecordingEnd.COMPLETE_TRIP)

        assertEquals(1, voice.spoken.count { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertEquals(listOf(1, 2, 3), kmReports(), voice.spoken.toString())
        val saved = h.store.segments(tripId).single()
        assertEquals(3_010.0, saved.totals.distanceM, 1.0)
        assertNotNull(saved.endedAt)
        assertEquals(TripStatus.COMPLETED, trips.trips.getValue(tripId).status)
        val finished = h.controller.ui.value as RideUiState.Finished
        assertEquals(saved, finished.segment)
        assertNull(finished.error)
        assertFalse(source.collecting, "location released at stop")
        assertFalse(h.pressure.collecting, "pressure released at stop")
        assertEquals(603, h.store.points(saved.id).size, "every fix saved once")

        h.controller.dismissSummary()
        assertEquals(RideUiState.Idle, h.controller.ui.value)
    }

    @Test
    fun twoSignalStopsPauseAndResumeTwice() = runTest {
        val h = harness(straight(1_500.0, stops = listOf(StopAt(500.0, 30), StopAt(1_000.0, 30))))
        h.start(h.trip())

        play(400_000)

        assertEquals(2, voice.spoken.count { it == "일시정지되었습니다" }, voice.spoken.toString())
        assertEquals(2, voice.spoken.count { it == "기록을 다시 시작합니다" }, voice.spoken.toString())
        val stats = h.active.stats
        assertTrue(stats.movingTimeMs < stats.elapsedMs, "$stats")
        assertEquals(1_470.0, stats.distanceM, 1.0)
    }

    @Test
    fun pointsAreSavedEvery5sWithTotals() = runTest {
        val h = harness(straight(1_000.0))
        h.start(h.trip())

        play(23_000)

        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), h.store.appendCalls.map { it.first })
        val distances = h.store.appendCalls.map { it.second.distanceM }
        assertEquals(distances.sorted(), distances)
        assertTrue(distances.zipWithNext().all { (a, b) -> b > a }, "$distances")
    }

    @Test
    fun saveFailureWarnsOnceAndRetriesTheBufferedPoints() = runTest {
        val h = harness(straight(1_000.0))
        val warnings = mutableListOf<String?>()
        backgroundScope.launch {
            h.controller.ui.filterIsInstance<RideUiState.Active>().map { it.saveWarning }.distinctUntilChanged().toList(warnings)
        }
        h.store.failAppend = true
        h.start(h.trip())

        play(15_500)
        assertEquals(3, h.store.failedAppends)
        assertEquals(RideController.MSG_SAVE_PROBLEM, h.active.saveWarning)
        assertInstanceOf(RideUiState.Active::class.java, h.controller.ui.value)

        h.store.failAppend = false
        play(5_000)

        assertEquals(listOf(null, RideController.MSG_SAVE_PROBLEM), warnings, "the warning changed once")
        assertEquals(20_000L, h.store.appendCalls.single().first)
        val points = h.store.points(h.active.segmentId)
        assertTrue(points.size >= 20, "points of the failed saves kept: ${points.size}")
        assertEquals((0 until points.size).map { it * 1_000L }, points.map { it.timeMillis }, "all points from the start, once")
    }

    @Test
    fun startingClosesALeftoverOpenSegmentAtItsLastSaveTime() = runTest {
        val h = harness(straight(100.0))
        val tripId = h.trip()
        val leftover = h.store.openSegment(tripId, 1_000, testDay)
        h.store.appendPoints(leftover, emptyList(), SegmentTotals.ZERO.copy(distanceM = 200.0, elapsedMs = 60_000))

        h.start(tripId)

        val closed = h.store.segment(leftover)!!
        assertEquals(61_000L, closed.endedAt)
        assertEquals(200.0, closed.totals.distanceM)
        val open = h.store.openSegments().single()
        assertEquals(h.active.segmentId, open.id)
    }

    @Test
    fun startingAnEndedTripFails() = runTest {
        val h = harness(straight(100.0))
        val tripId = h.trip()
        h.tripManager.complete(tripId)

        h.controller.startRecording(tripId)!!.join()

        assertEquals(RideUiState.Failed(tripId, RideController.MSG_START_FAILED), h.controller.ui.value)
        assertTrue(h.store.openSegments().isEmpty())
        h.controller.dismissSummary()
        assertEquals(RideUiState.Idle, h.controller.ui.value)
    }

    @Test
    fun interruptedSegmentResumesAsANewSegmentOfTheSameTrip() = runTest {
        val h = harness(straight(1_100.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        val aJob = Job(backgroundScope.coroutineContext.job)
        val a = h.newController(CoroutineScope(backgroundScope.coroutineContext + aJob))
        h.start(tripId, a)
        play(201_000)
        aJob.cancel() // the process died without a stop
        runCurrent()

        val b = h.newController()
        val leftover = h.store.openSegments().single()
        assertTrue(leftover.totals.distanceM >= 990.0, "1 km saved: ${leftover.totals}")
        assertEquals(leftover, b.interrupted())

        h.start(tripId, b)

        assertEquals(leftover.startedAt + leftover.totals.elapsedMs, h.store.segment(leftover.id)!!.endedAt)
        val resumed = h.store.openSegments().single()
        assertNotEquals(leftover.id, resumed.id)
        assertEquals(tripId, resumed.tripId)
        assertNull(resumed.endedAt)
        assertNull(b.interrupted(), "not idle while recording")
    }

    @Test
    fun closeInterruptedEndsTheTrip() = runTest {
        val h = harness(emptyList())

        val single = h.trip(TripType.SINGLE_DAY)
        val s = h.store.openSegment(single, 0, testDay)
        h.store.appendPoints(s, emptyList(), SegmentTotals.ZERO.copy(elapsedMs = 30_000))
        h.controller.closeInterrupted()
        assertEquals(30_000L, h.store.segment(s)!!.endedAt)
        assertEquals(TripStatus.COMPLETED, trips.trips.getValue(single).status)

        val multi = h.trip(TripType.MULTI_DAY)
        val m = h.store.openSegment(multi, 0, testDay)
        h.store.appendPoints(m, emptyList(), SegmentTotals.ZERO.copy(elapsedMs = 40_000))
        play(50_000)
        h.controller.closeInterrupted()
        assertEquals(40_000L, h.store.segment(m)!!.endedAt)
        val trip = trips.trips.getValue(multi)
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertEquals(50_000L, trip.lastActiveAt)
        assertNull(h.controller.interrupted())
    }

    @Test
    fun stopTodayKeepsMultiDayTripActive() = runTest {
        val h = harness(straight(100.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        h.start(tripId)
        play(30_000)

        h.controller.stopRecording(RecordingEnd.STOP_TODAY)

        assertEquals(TripStatus.ACTIVE, trips.trips.getValue(tripId).status)
        assertEquals(30_000L, trips.trips.getValue(tripId).lastActiveAt)
        val finished = h.controller.ui.value as RideUiState.Finished
        assertEquals(30_000L, finished.segment.endedAt)
        assertEquals(100.0, finished.segment.totals.distanceM, 1.0)
        assertTrue(finished.track.isNotEmpty())
        assertEquals(finished.segment, h.store.segment(finished.segment.id))
    }

    @Test
    fun dayKmContinuesAcrossSegments() = runTest {
        val h = harness(straight(1_000.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        val earlier = h.store.openSegment(tripId, 0, testDay)
        h.store.closeSegment(earlier, 0, SegmentTotals.ZERO.copy(distanceM = 1_500.0, movingTimeMs = 300_000))

        h.start(tripId)
        play(205_000)

        assertEquals(listOf(2), kmReports(), voice.spoken.toString())
    }

    @Test
    fun autoPauseSettingIsTheDefaultAndTheChipTogglesIt() = runTest {
        val h = harness(straight(100.0))
        h.settings.value = AppSettings(autoPauseEnabled = false)
        h.start(h.trip())
        assertFalse(h.active.autoPause)

        h.controller.setAutoPause(true)

        assertTrue(h.active.autoPause)
    }

    @Test
    fun voiceOffSpeaksNothing() = runTest {
        val h = harness(straight(1_100.0))
        h.settings.value = AppSettings(voiceEnabled = false)
        h.start(h.trip())

        play(225_000)

        assertTrue(voice.spoken.isEmpty(), voice.spoken.toString())
        assertEquals(1_100.0, h.active.stats.distanceM, 1.0)
    }

    @Test
    fun trackKeepsPointsAtLeast10mApart() = runTest {
        val h = harness(straight(300.0))
        h.start(h.trip())

        play(65_000)

        val track = h.active.track
        assertTrue(track.size >= 25, "track: ${track.size}")
        assertTrue(track.zipWithNext().all { (a, b) -> GeoMath.distanceM(a, b) >= 10.0 })
    }

    @Test
    fun gpsWeakAfter10sWithoutFixes() = runTest {
        val h = harness(straight(100.0))
        h.start(h.trip())
        play(20_500)
        assertFalse(h.active.gpsWeak)
        val distance = h.active.stats.distanceM

        play(12_000)

        assertTrue(h.active.gpsWeak)
        assertEquals(distance, h.active.stats.distanceM)
    }

    /** First subscription: 0–100 m (t 0–20 s), then the stream fails; the second goes on from 105 m at t = 35 s. */
    private class FailingLocationSource(private val clock: () -> Long) : LocationSource {
        var subscriptions = 0
            private set

        override fun fixes(): Flow<LocationFix> = flow {
            val n = ++subscriptions
            val fixes = if (n == 1) {
                straight(100.0)
            } else {
                (0..40).map { i -> straight(5.0).last().copy(point = north(105.0 + 5 * i), timeMillis = 35_000L + i * 1_000) }
            }
            for (fix in fixes) {
                delay(fix.timeMillis - clock())
                emit(fix)
            }
            if (n == 1) throw IOException("location permission revoked")
            awaitCancellation()
        }
    }

    @Test
    fun locationFailureKeepsRecordingAndRetries() = runTest {
        val source = FailingLocationSource { testScheduler.currentTime }
        val h = Harness(this, source)
        h.start(h.trip())

        play(21_000)
        assertInstanceOf(RideUiState.Active::class.java, h.controller.ui.value)
        assertTrue(log.messages.any { "Location" in it }, log.messages.toString())
        play(11_000) // t = 32 s, 12 s after the failure
        assertTrue(h.active.gpsWeak)
        assertEquals(100.0, h.active.stats.distanceM, 1.0)
        assertInstanceOf(RideUiState.Active::class.java, h.controller.ui.value)
        assertEquals(2, source.subscriptions, "resubscribed 5 s after the failure")

        play(8_000) // t = 40 s
        assertFalse(h.active.gpsWeak)
        assertTrue(h.active.stats.distanceM > 101.0, "${h.active.stats.distanceM}")
    }

    @Test
    fun endForAnotherTripWaitsForAStopAlreadyInFlight() = runTest {
        val h = harness(emptyList())
        val slow = object : RideStore by h.store {
            override suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals) {
                delay(1_000)
                h.store.closeSegment(segmentId, endedAt, totals)
            }
        }
        val c = h.newController(rideStore = slow)
        val tripId = h.trip()
        h.start(tripId, c)
        launch { c.stopRecording(RecordingEnd.SEGMENT_ONLY) }
        runCurrent() // that stop is now closing the segment

        var ended = false
        launch {
            c.endForAnotherTrip()
            ended = true
        }
        runCurrent()
        assertFalse(ended, "waits for the stop in flight")
        assertNull(h.store.segments(tripId).single().endedAt)

        play(1_001)
        assertTrue(ended)
        assertNotNull(h.store.segments(tripId).single().endedAt, "closed before the other trip starts")
        assertEquals(RideUiState.Idle, c.ui.value)
    }

    @Test
    fun recordingTouchesItsTripAtTheStartAndAtMostOncePerMinuteWithTheSaves() = runTest {
        // The stale-trip prompt must not see a trip that is being recorded as idle.
        val h = harness(straight(2_000.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        play(10_000)

        h.start(tripId)
        runCurrent()
        assertEquals(10_000L, trips.trips.getValue(tripId).lastActiveAt, "touched when the recording starts")

        play(55_000) // t = 65 s: saves at 15..65 s, less than a minute since the touch
        assertEquals(10_000L, trips.trips.getValue(tripId).lastActiveAt)
        play(5_000) // t = 70 s: the save a minute after the touch touches again
        assertEquals(70_000L, trips.trips.getValue(tripId).lastActiveAt)
    }

    @Test
    fun aFailingTouchIsLoggedAndTheRecordingGoesOn() = runTest {
        val h = harness(straight(500.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        trips.failUpdate = { true }

        h.start(tripId)
        play(70_000)

        assertInstanceOf(RideUiState.Active::class.java, h.controller.ui.value)
        assertTrue(log.messages.any { it.startsWith("Could not touch trip $tripId") }, log.messages.toString())
        assertTrue(h.store.appendCalls.isNotEmpty(), "the saves go on")
    }

    @Test
    fun aFailedFinalSaveIsRetriedOnceAtTheStop() = runTest {
        val h = harness(straight(500.0))
        val tripId = h.trip()
        h.start(tripId)
        play(12_500)
        val segmentId = h.active.segmentId
        h.store.failNextAppends = 1

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        val finished = h.controller.ui.value as RideUiState.Finished
        assertNull(finished.error, "the retry saved the last points")
        assertEquals(1, h.store.failedAppends)
        assertNotNull(h.store.segment(segmentId)!!.endedAt)
        assertEquals(13, h.store.points(segmentId).size, "every point up to the stop, once")
    }

    @Test
    fun aFinalSaveFailingTwiceLeavesTheSegmentOpenForRecovery() = runTest {
        val h = harness(straight(500.0))
        val tripId = h.trip(TripType.MULTI_DAY)
        h.start(tripId)
        play(12_500)
        val segmentId = h.active.segmentId
        val lastGood = h.store.segment(segmentId)!!
        h.store.failAppend = true

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        val finished = h.controller.ui.value as RideUiState.Finished
        assertEquals(RideController.MSG_SAVE_PROBLEM, finished.error)
        assertEquals(2, h.store.failedAppends, "tried once more")
        assertEquals(lastGood, h.store.segment(segmentId), "still open at its last good save; recovery closes it there")
        assertEquals(segmentId, h.store.openSegments().single().id)
    }
    @Test
    fun splitClosesTheSegmentAndRecordsANewOneOfTheSameTrip() = runTest {
        val h = splitHarness()
        val tripId = h.trip()
        h.start(tripId)
        play(20_000) // 100 m

        assertNull(h.controller.splitSegment())

        val segments = h.store.segments(tripId)
        assertEquals(2, segments.size, segments.toString())
        assertNotNull(segments[0].endedAt)
        assertEquals(100.0, segments[0].totals.distanceM, 1.0)
        assertNull(segments[1].endedAt)
        assertEquals(segments[1].id, h.active.segmentId)
        assertEquals(tripId, h.active.tripId)
        assertEquals(TripStatus.ACTIVE, trips.trips.getValue(tripId).status)
        assertEquals(1, trips.trips.size)

        // The shared stream replays the closed segment's last fix to the new collector: it is not recorded twice.
        play(5_000)
        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)
        val firstTimes = h.store.points(segments[0].id).map { it.timeMillis }
        val secondTimes = h.store.points(segments[1].id).map { it.timeMillis }
        assertEquals((0..20).map { it * 1_000L }, firstTimes)
        assertEquals((21..25).map { it * 1_000L }, secondTimes)
    }

    @Test
    fun splitNeverShowsASummaryOrIdle() = runTest {
        val h = splitHarness()
        h.start(h.trip())
        play(20_000)
        val states = mutableListOf<RideUiState>()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.controller.ui.toList(states) }

        h.controller.splitSegment()
        play(5_000)
        collecting.cancel()

        assertTrue(states.none { it is RideUiState.Idle || it is RideUiState.Finished }, states.toString())
        val kinds = states.map { it::class }.zipWithNext().filter { (a, b) -> a != b }.map { it.second }
        assertEquals(
            listOf(RideUiState.Active::class, RideUiState.Starting::class, RideUiState.Active::class),
            listOf(states.first()::class) + kinds,
        )
    }

    @Test
    fun splitKeepsTheAutoPauseChipOfTheRecording() = runTest {
        val h = splitHarness()
        h.settings.value = AppSettings(autoPauseEnabled = true)
        h.start(h.trip())
        play(20_000)
        h.controller.setAutoPause(false)
        val firstSegment = h.active.segmentId

        h.controller.splitSegment()

        assertNotEquals(firstSegment, h.active.segmentId)
        assertFalse(h.active.autoPause)
    }

    @Test
    fun splitAnnouncesTheNewSegmentOnceByVoiceAndOnScreen() = runTest {
        val h = splitHarness()
        h.start(h.trip())
        play(20_000)

        h.controller.splitSegment()
        play(5_000)

        assertEquals(1, voice.spoken.count { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertEquals("기록을 시작합니다", voice.spoken.first())
        assertEquals(1, voice.spoken.count { it == "새 구간으로 기록을 시작합니다" }, voice.spoken.toString())
        assertEquals("새 구간으로 기록을 시작합니다", h.controller.notice.value?.text)
    }

    @Test
    fun splitWithVoiceOffStillShowsTheNotice() = runTest {
        val h = splitHarness()
        h.settings.value = AppSettings(voiceEnabled = false)
        h.start(h.trip())
        play(20_000)

        h.controller.splitSegment()

        assertTrue(voice.spoken.none { it == "새 구간으로 기록을 시작합니다" }, voice.spoken.toString())
        assertEquals("새 구간으로 기록을 시작합니다", h.controller.notice.value?.text)
    }

    @Test
    fun splitKeepsTheFixesThatArriveWhileTheSegmentsChange() = runTest {
        val h = splitHarness()
        val tripId = h.trip()
        h.start(tripId)
        play(20_000)
        val gate = CompletableDeferred<Unit>()
        h.store.openGate = gate

        val split = launch { h.controller.splitSegment() }
        play(3_000) // the fixes at 21, 22 and 23 s arrive while the new segment is being opened
        assertInstanceOf(RideUiState.Starting::class.java, h.controller.ui.value)
        gate.complete(Unit)
        play(5_000)
        split.join()
        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        val (first, second) = h.store.segments(tripId)
        val firstTimes = h.store.points(first.id).map { it.timeMillis }
        val secondTimes = h.store.points(second.id).map { it.timeMillis }
        assertEquals(20_000L, firstTimes.last())
        assertTrue(secondTimes.containsAll(listOf(21_000L, 22_000L, 23_000L)), secondTimes.toString())
        assertTrue(firstTimes.intersect(secondTimes.toSet()).isEmpty(), "$firstTimes / $secondTimes")
        assertEquals((21..28).map { it * 1_000L }, secondTimes, "every later fix once, in order")
    }

    @Test
    fun splitKeepsTheLocationStreamSubscribed() = runTest {
        val h = splitHarness()
        h.start(h.trip())
        play(20_000)

        h.controller.splitSegment()
        play(10_000)

        assertEquals(1, scripted.collections)
        assertTrue(scripted.collecting)
        assertTrue(h.active.stats.distanceM > 40.0, "the new segment records: ${h.active.stats}")
    }

    @Test
    fun splitContinuesTheDayKmCount() = runTest {
        val h = splitHarness()
        h.start(h.trip())
        play(240_000) // 1_200 m
        assertEquals(listOf(1), kmReports(), voice.spoken.toString())

        h.controller.splitSegment()
        play(180_000) // 2_100 m

        assertEquals(listOf(1, 2), kmReports(), voice.spoken.toString())
    }

    @Test
    fun splitReportsASaveProblem() = runTest {
        val h = splitHarness()
        val tripId = h.trip()
        h.start(tripId)
        play(20_000)
        h.store.failAppend = true

        assertEquals(RideController.MSG_SAVE_PROBLEM, h.controller.splitSegment())

        assertInstanceOf(RideUiState.Active::class.java, h.controller.ui.value)
        assertEquals(2, h.store.segments(tripId).size)
        assertEquals(h.store.segments(tripId).last().id, h.active.segmentId)
    }

    @Test
    fun aStopDuringTheSplitIsNotUndone() = runTest {
        val h = splitHarness()
        val tripId = h.trip()
        h.start(tripId)
        play(20_000)
        val gate = CompletableDeferred<Unit>()
        h.store.closeGate = gate

        var result: String? = "not returned"
        val split = launch { result = h.controller.splitSegment() }
        runCurrent()
        val stop = launch { h.controller.stopRecording(RecordingEnd.COMPLETE_TRIP) }
        runCurrent()
        gate.complete(Unit)
        split.join()
        stop.join()
        play(5_000)

        assertNull(result)
        val segment = h.store.segments(tripId).single()
        assertNotNull(segment.endedAt)
        assertEquals(segment, (h.controller.ui.value as RideUiState.Finished).segment)
        assertEquals(TripStatus.ACTIVE, trips.trips.getValue(tripId).status, "the split's segment-only end came first")
        assertTrue(voice.spoken.none { it == "새 구간으로 기록을 시작합니다" }, voice.spoken.toString())
        assertFalse(scripted.collecting, "location released")
    }

    @Test
    fun splitWithoutARecordingDoesNothing() = runTest {
        val h = splitHarness()
        val tripId = h.trip()

        assertNull(h.controller.splitSegment())

        assertEquals(RideUiState.Idle, h.controller.ui.value)
        assertTrue(h.store.segments(tripId).isEmpty())
    }

    @Test
    fun announceSpeaksAndShowsTheSameTextOnce() = runTest {
        val h = harness(straight(100.0))
        h.start(h.trip())
        val text = "안내를 마칩니다. 기록은 계속됩니다"

        h.controller.announce(text)

        assertEquals(1, voice.spoken.count { it == text }, voice.spoken.toString())
        val notice = h.controller.notice.value!!
        assertEquals(text, notice.text)
        h.controller.noticeShown(notice.id + 1)
        assertEquals(notice, h.controller.notice.value)
        h.controller.noticeShown(notice.id)
        assertNull(h.controller.notice.value)
    }

    @Test
    fun announceWithVoiceOffOnlyShows() = runTest {
        val h = harness(straight(100.0))
        h.settings.value = AppSettings(voiceEnabled = false)
        h.start(h.trip())

        h.controller.announce("안내를 마칩니다. 기록은 계속됩니다")

        assertTrue(voice.spoken.isEmpty(), voice.spoken.toString())
        assertEquals("안내를 마칩니다. 기록은 계속됩니다", h.controller.notice.value?.text)
    }

    @Test
    fun announceWithoutARecordingDoesNothing() = runTest {
        val h = harness(straight(100.0))

        h.controller.announce("안내를 마칩니다. 기록은 계속됩니다")

        assertTrue(voice.spoken.isEmpty(), voice.spoken.toString())
        assertNull(h.controller.notice.value)
    }

    @Test
    fun aFailedStartClearsANoticePostedWhileStarting() = runTest {
        val h = harness(straight(100.0))
        val tripId = h.trip()
        h.tripManager.complete(tripId)
        val starting = h.controller.startRecording(tripId)!!
        assertEquals(RideUiState.Starting(tripId), h.controller.ui.value)
        h.controller.announce("안내를 마칩니다. 기록은 계속됩니다")
        assertNotNull(h.controller.notice.value)

        starting.join()

        assertEquals(RideUiState.Failed(tripId, RideController.MSG_START_FAILED), h.controller.ui.value)
        assertNull(h.controller.notice.value)
    }

    @Test
    fun aStoppedRecordingClearsItsNotice() = runTest {
        val h = harness(straight(100.0))
        h.start(h.trip())
        h.controller.announce("안내를 마칩니다. 기록은 계속됩니다")

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        assertInstanceOf(RideUiState.Finished::class.java, h.controller.ui.value)
        assertNull(h.controller.notice.value)
    }

    // --- V1.9.0 activity types (Task 5) ---

    @Test
    fun startUsesChosenActivity() = runTest {
        // 2.5 km/h: below the bike's 3 km/h auto-pause, above the run's 2 km/h.
        val h = harness(straight(100.0, speedKmh = 2.5))
        val tripId = h.trip()

        h.start(tripId, activity = ActivityType.RUN)
        play(60_000)

        assertEquals(ActivityType.RUN, h.store.segments(tripId).single().activity)
        assertEquals(ActivityType.RUN, h.active.activity)
        assertEquals(RecordingState.Recording, h.active.state, "a run is not auto-paused at 2.5 km/h")
        assertTrue(voice.spoken.none { it == "일시정지되었습니다" }, voice.spoken.toString())
        assertEquals("달리기 기록을 시작합니다", voice.spoken.first())
        assertNull(h.navigation.kmSource, "only a bike recording supplies the guidance's km")

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)
        assertEquals(ActivityType.RUN, (h.controller.ui.value as RideUiState.Finished).segment.activity)
    }

    @Test
    fun theSameFixesPauseABikeRecording() = runTest {
        val h = harness(straight(100.0, speedKmh = 2.5))
        h.start(h.trip())

        play(60_000)

        assertEquals(ActivityType.BIKE, h.active.activity)
        assertInstanceOf(RecordingState.Paused::class.java, h.active.state)
    }

    @Test
    fun splitAlwaysOpensABikeSegment() = runTest {
        // The split's only caller is the guidance start, and guidance is for bikes (P14).
        val h = splitHarness()
        val tripId = h.trip()
        h.start(tripId, activity = ActivityType.WALK)
        play(20_000)
        assertNull(h.navigation.kmSource, "a walk is not the guidance's km source")

        h.controller.splitSegment()

        assertEquals(listOf(ActivityType.WALK, ActivityType.BIKE), h.store.segments(tripId).map { it.activity })
        assertEquals(ActivityType.BIKE, h.active.activity)
        assertEquals(RideUiState.Active::class, h.controller.ui.value::class)
        assertNotNull(h.navigation.kmSource, "the bike segment supplies the guidance's km")

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)
        assertEquals(ActivityType.BIKE, (h.controller.ui.value as RideUiState.Finished).segment.activity)
    }

    @Test
    fun runKmSpeaksPaceSentence() = runTest {
        // 3 m/s: the first kilometre is reached at 1_002 m after 334 s.
        val h = harness(straight(1_100.0, speedKmh = 10.8))
        h.start(h.trip(), activity = ActivityType.RUN)

        play(370_000)

        assertEquals(
            listOf("1킬로미터. 이번 1킬로미터 5분 34초, 평균 페이스 5분 33초입니다."),
            voice.spoken.filter { "킬로미터" in it },
        )
    }

    @Test
    fun aFixJumpingOverSeveralKmSpeaksThePerKmLap() = runTest {
        // 900 m at 3 m/s (t 0..300 s), then one fix 1.2 km further 300 s later (a GPS gap counted as moving).
        val before = straight(900.0, speedKmh = 10.8)
        val jump = before.last().copy(point = north(2_100.0), timeMillis = 600_000)
        val h = harness(before + jump)
        h.start(h.trip(), activity = ActivityType.RUN)

        play(610_000)

        // Lap: 600 s over 2 km = 5'00" per km, not "10분" for "이번 1킬로미터"; average 2_100 m / 600 s = 4'46".
        assertEquals(
            listOf("2킬로미터. 이번 1킬로미터 5분, 평균 페이스 4분 46초입니다."),
            voice.spoken.filter { "킬로미터" in it },
        )
    }

    @Test
    fun bikeKmSentenceUnchangedButSiSok() = runTest {
        val h = harness(straight(1_100.0))
        h.start(h.trip())

        play(225_000)

        assertEquals(
            listOf("1킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 3분입니다."),
            voice.spoken.filter { "킬로미터" in it },
        )
        assertEquals("기록을 시작합니다", voice.spoken.first())
    }

    @Test
    fun hikeStartPhraseWithSunset() = runTest {
        val h = harness(straight(200.0, speedKmh = 3.0))
        h.sunsetAnswer = { 5_400_000L } // 1 h 30 min after the start
        val tripId = h.trip()

        h.start(tripId, activity = ActivityType.HIKE)
        play(3_000)

        assertEquals(listOf("등산 기록을 시작합니다 일몰까지 1시간 30분 남았습니다."), voice.spoken)
        assertEquals(5_400_000L, h.active.sunsetMillis)
        assertEquals(ActivityType.HIKE, h.store.segments(tripId).single().activity)
        val call = h.sunsetCalls.single()
        assertEquals(testDay, call.date)
        assertEquals(ZoneId.of("Asia/Seoul"), call.zone)
        assertEquals(RideFixtures.north(0.0).lat, call.lat, 1e-9)
    }

    @Test
    fun hikeStartWithoutALocationSpeaksWithoutTheTail() = runTest {
        val h = harness(emptyList())
        h.sunsetAnswer = { 5_400_000L }
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(1_000)
        assertTrue(voice.spoken.isEmpty(), "waits a moment for the first location: ${voice.spoken}")
        play(10_000)

        assertEquals(listOf("등산 기록을 시작합니다"), voice.spoken)
        assertNull(h.active.sunsetMillis, "unknown until a location is known")
    }

    @Test
    fun hikeStartSentenceIsNeverSpokenAfterAPause() = runTest {
        val h = harness(emptyList())
        h.start(h.trip(), activity = ActivityType.HIKE)

        h.controller.pause()

        assertEquals(listOf("등산 기록을 시작합니다", "일시정지되었습니다"), voice.spoken)
    }

    @Test
    fun hikeCuesComeOnTicksWhilePaused() = runTest {
        val h = harness(straight(100.0, speedKmh = 3.0))
        h.start(h.trip(), activity = ActivityType.HIKE)
        h.controller.pause()

        play(1_799_000)
        assertTrue(voice.spoken.none { it.startsWith("산행") }, voice.spoken.toString())
        play(1_000)

        assertEquals(RecordingState.Paused(com.cowork.bikerecoder.core.recording.PauseKind.MANUAL), h.active.state)
        val report = voice.spoken.single { it.startsWith("산행") }
        assertTrue(report.startsWith("산행 30분. "), report)
        assertTrue(report.endsWith("킬로미터입니다."), "no sunset tail without a sunset: $report")
    }

    @Test
    fun hikeSunsetCuesAndThePeriodicReportTail() = runTest {
        val h = harness(straight(100.0, speedKmh = 3.0))
        h.sunsetAnswer = { 2_700_000L } // 45 min after the start: the 2 h and 1 h cues are already past
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(1_801_000)

        val hikeSpeech = voice.spoken.filter { "일몰" in it || it.startsWith("산행") }
        assertEquals("등산 기록을 시작합니다 일몰까지 45분 남았습니다.", hikeSpeech[0])
        assertEquals("일몰까지 30분 남았습니다. 서둘러 하산하세요.", hikeSpeech[1])
        assertTrue(hikeSpeech[2].startsWith("산행 30분. "), hikeSpeech.toString())
        assertTrue(hikeSpeech[2].endsWith(" 일몰까지 15분 남았습니다."), hikeSpeech.toString())
        assertEquals(3, hikeSpeech.size, hikeSpeech.toString())
    }

    @Test
    fun voiceOffSilencesHikeCues() = runTest {
        val h = harness(straight(100.0, speedKmh = 3.0))
        h.settings.value = AppSettings(voiceEnabled = false)
        h.sunsetAnswer = { 2_400_000L }
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(1_900_000)

        assertTrue(voice.spoken.isEmpty(), voice.spoken.toString())
        assertEquals(2_400_000L, h.active.sunsetMillis, "the screen still knows the sunset")
    }

    @Test
    fun noSunsetForOtherActivities() = runTest {
        val h = harness(straight(200.0, speedKmh = 6.0))
        h.sunsetAnswer = { 5_400_000L }
        h.start(h.trip(), activity = ActivityType.WALK)

        play(60_000)

        assertTrue(h.sunsetCalls.isEmpty())
        assertNull(h.active.sunsetMillis)
        assertEquals("걷기 기록을 시작합니다", voice.spoken.first())
    }

    @Test
    fun finishSavesBestEffortsForRun() = runTest {
        val h = harness(straight(1_100.0, speedKmh = 12.0))
        h.start(h.trip(), activity = ActivityType.RUN)
        play(340_000)
        val segmentId = h.active.segmentId

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        val efforts = h.store.bestEfforts.getValue(segmentId)
        assertEquals(listOf(BestEffortDistance.ONE_K), efforts.map { it.distance })
        assertEquals(300_000.0, efforts.single().timeMs.toDouble(), 1_000.0)
    }

    /** A run left open by a crash or kill gets its best efforts when it is closed at its last save (as [finish] does). */
    @Test
    fun anInterruptedRunSavesItsBestEffortsWhenClosed() = runTest {
        val h = harness(straight(1_100.0, speedKmh = 12.0))
        val runTrip = h.trip(TripType.MULTI_DAY)
        val aJob = Job(backgroundScope.coroutineContext.job)
        val a = h.newController(CoroutineScope(backgroundScope.coroutineContext + aJob))
        h.start(runTrip, a, ActivityType.RUN)
        play(340_000)
        aJob.cancel() // the process died without a stop
        runCurrent()
        val leftover = h.store.openSegments().single()
        assertFalse(leftover.id in h.store.bestEfforts)

        h.newController().closeInterrupted()

        assertNotNull(h.store.segment(leftover.id)!!.endedAt)
        val efforts = h.store.bestEfforts.getValue(leftover.id)
        assertEquals(listOf(BestEffortDistance.ONE_K), efforts.map { it.distance })
        assertEquals(BestEfforts.find(h.store.points(leftover.id)), efforts)
    }

    @Test
    fun anInterruptedBikeSavesNoBestEfforts() = runTest {
        val h = harness(emptyList())
        val bike = h.store.openSegment(h.trip(TripType.MULTI_DAY), 0, testDay)
        h.store.appendPoints(bike, emptyList(), SegmentTotals.ZERO.copy(elapsedMs = 30_000))

        h.controller.closeInterrupted()

        assertNotNull(h.store.segment(bike)!!.endedAt)
        assertFalse(bike in h.store.bestEfforts)
    }

    @Test
    fun finishSavesNothingForBike() = runTest {
        val h = harness(straight(1_100.0, speedKmh = 12.0))
        h.start(h.trip())
        play(340_000)
        val segmentId = h.active.segmentId

        h.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)

        assertFalse(segmentId in h.store.bestEfforts)
    }

    @Test
    fun aFailedBestEffortsSaveIsOnlyLogged() = runTest {
        val h = harness(straight(1_100.0, speedKmh = 12.0))
        val failing = object : RideStore by h.store {
            override suspend fun saveBestEfforts(segmentId: Long, efforts: List<BestEffort>) =
                throw IOException("disk full")
        }
        val c = h.newController(rideStore = failing)
        h.start(h.trip(), c, ActivityType.RUN)
        play(340_000)

        c.stopRecording(RecordingEnd.SEGMENT_ONLY)

        val finished = c.ui.value as RideUiState.Finished
        assertNull(finished.error)
        assertNotNull(h.store.segment(finished.segment.id)!!.endedAt)
        assertTrue(log.messages.any { "best efforts" in it }, log.messages.toString())
    }

    @Test
    fun sunsetComputedOnceWhileStayingClose() = runTest {
        val h = harness(straight(900.0, speedKmh = 3.0))
        h.sunsetAnswer = { 50_000_000L }
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(1_100_000)

        assertEquals(900.0, h.active.stats.distanceM, 1.0)
        assertEquals(1, h.sunsetCalls.size, h.sunsetCalls.toString())
    }

    @Test
    fun sunsetRecomputedAfterOneKm() = runTest {
        // A point every 3 m: none lies on a 1 km boundary.
        val h = harness(straight(2_500.0, speedKmh = 10.8))
        h.sunsetAnswer = { 50_000_000L }
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(850_000)

        // At 0 m, then at the first point more than 1 km from it (1_002 m), then more than 1 km from that (2_004 m).
        val distances = h.sunsetCalls.map { GeoMath.distanceM(north(0.0), GeoPoint(it.lat, it.lon)) }
        assertEquals(3, distances.size, distances.toString())
        assertEquals(0.0, distances[0], 0.5)
        assertEquals(1_002.0, distances[1], 0.5)
        assertEquals(2_004.0, distances[2], 0.5)
    }

    @Test
    fun sunsetRecomputedWhenTheDateChanges() = runTest {
        // Midnight in Seoul is 15:00 UTC: start 10 s before it.
        val start = 54_000_000L - 10_000
        play(start)
        val h = harness(straight(30.0, speedKmh = 3.0, startMillis = start))
        h.sunsetAnswer = { date -> if (date == testDay) 30_000_000L else 120_000_000L }
        h.start(h.trip(), activity = ActivityType.HIKE)

        play(9_000)
        assertEquals(listOf(testDay), h.sunsetCalls.map { it.date })
        play(2_000)

        assertEquals(listOf(testDay, testDay.plusDays(1)), h.sunsetCalls.map { it.date })
        assertEquals(120_000_000L, h.active.sunsetMillis)
    }
}
