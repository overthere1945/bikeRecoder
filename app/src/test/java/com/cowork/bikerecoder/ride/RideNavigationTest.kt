package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.location.SharedLocationSource
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.nav.fixture
import com.cowork.bikerecoder.nav.gpxFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Recording and guidance together on JVM fakes with virtual time (spec scenarios ③ and ⑤): the 2.37 km Z route
 * of the device NavigationScenarioTest, replayed at speedup 1 (10 m every 2 s, 18 km/h) through one
 * [SharedLocationSource] that both controllers subscribe to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideNavigationTest {

    private val trips = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val voice = RecordingVoiceOutput()
    private val log = RecordingLog()
    private val destination = GeoPoint(37.5143891, 127.0390685)

    /** The test clock starts at epoch 0 = 1970-01-01 09:00 in Asia/Seoul. */
    private val testDay = LocalDate.of(1970, 1, 1)

    private inner class Harness(val scope: TestScope) {
        val clock = { scope.testScheduler.currentTime }
        val scripted = ScriptedLocationSource(gpxFixture("scenario_follow.gpx"), speedup = 1.0)
        val shared = SharedLocationSource({ scripted }, scope.backgroundScope)
        val store = FakeRideStore(trips, clock)
        val tripManager = TripManager(trips, offline, clock)
        val settings = MutableStateFlow(AppSettings())
        val router = AssetRouter(listOf(fixture("route_follow.geojson")))
        val navigation = NavigationController(
            router = router,
            tripManager = tripManager,
            tripStore = trips,
            offline = offline,
            voice = voice,
            locationSource = { shared },
            voiceEnabled = flowOf(true),
            scope = scope.backgroundScope,
            clock = clock,
            log = log,
        )
        val ride = RideController(
            rideStore = store,
            tripStore = trips,
            tripManager = tripManager,
            navigation = navigation,
            voice = voice,
            location = shared,
            pressure = null,
            settings = settings,
            scope = scope.backgroundScope,
            clock = clock,
            log = log,
        )

        suspend fun trip(type: TripType = TripType.SINGLE_DAY): Long = tripManager.startTrip(
            type,
            RouteProfile.CYCLEWAY_FIRST,
            listOf(TripStop(0, 0, 0, "도착", destination, isDestination = true, visitedAt = null)),
        ).id

        val active: RideUiState.Active get() = ride.ui.value as RideUiState.Active

        suspend fun guidedPast(m: Double) =
            navigation.ui.first { it is NavUiState.Active && it.state.progress.distanceAlongM > m }

        suspend fun rideFinished(): RideUiState.Finished = ride.ui.first { it is RideUiState.Finished } as RideUiState.Finished
    }

    private fun TestScope.play(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** The combined sentence: the recording's day totals plus the guidance's remaining distance and ETA. */
    private fun combinedKms() = voice.spoken
        .filter { "킬로미터 이동. 평균 속도" in it && "남은 거리" in it && "도착 예정" in it }
        .map { it.substringBefore("킬로미터").toInt() }

    /** The recording-only sentence ("…이동 시간 {시간}입니다."). */
    private fun recordingKms() = voice.spoken
        .filter { "킬로미터 이동. 평균 속도" in it && "남은 거리" !in it }
        .map { it.substringBefore("킬로미터").toInt() }

    /** The guidance-only sentence ("{n}킬로미터 이동. 남은 거리 …"). */
    private fun guidanceKms() = voice.spoken
        .filter { "킬로미터 이동. 남은 거리" in it }
        .map { it.substringBefore("킬로미터").toInt() }

    @Test
    fun guidanceAutoStartsRecordingWhenEnabled() = runTest {
        val h = Harness(this)
        val tripId = h.trip()

        h.navigation.start(tripId)
        play(10_000)

        assertInstanceOf(NavUiState.Active::class.java, h.navigation.ui.value)
        assertEquals(tripId, h.active.tripId)
        assertEquals(1, voice.spoken.count { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertNotNull(h.navigation.kmSource, "the recording supplies the km announcements")
        assertEquals(tripId, h.store.openSegments().single().tripId)
    }

    @Test
    fun guidanceDoesNotStartRecordingWhenDisabled() = runTest {
        val h = Harness(this)
        h.settings.value = AppSettings(autoStartRecordingWithNavigation = false)
        val tripId = h.trip()

        h.navigation.start(tripId)
        play(60_000)

        assertInstanceOf(NavUiState.Active::class.java, h.navigation.ui.value)
        assertEquals(RideUiState.Idle, h.ride.ui.value)
        assertTrue(voice.spoken.none { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertNull(h.navigation.kmSource)
        assertTrue(h.store.openSegments().isEmpty())
    }

    @Test
    fun combinedKmSentenceOncePerKmAndNoGuidanceKmReport() = runTest {
        val h = Harness(this)
        val tripId = h.trip()

        h.navigation.start(tripId)
        h.rideFinished()

        assertEquals(listOf(1, 2), combinedKms(), voice.spoken.toString())
        assertTrue(guidanceKms().isEmpty(), voice.spoken.toString())
        assertTrue(recordingKms().isEmpty(), "no duplicate sentence from the recording: ${voice.spoken}")
    }

    @Test
    fun arrivalEndsGuidanceAndRecordingWithSummary() = runTest {
        val h = Harness(this)
        val tripId = h.trip(TripType.SINGLE_DAY)

        h.navigation.start(tripId)
        val finished = h.rideFinished()

        assertEquals(NavUiState.Finished(tripId, TripType.SINGLE_DAY, FinishReason.ARRIVED), h.navigation.ui.value)
        assertNotNull(finished.segment.endedAt)
        assertEquals(tripId, finished.segment.tripId)
        assertEquals(2_370.0, finished.segment.totals.distanceM, 15.0)
        assertNull(finished.error)
        assertEquals(finished.segment, h.store.segment(finished.segment.id))
        assertEquals(TripStatus.COMPLETED, trips.trips.getValue(tripId).status)
        assertTrue("목적지에 도착했습니다" in voice.spoken, voice.spoken.toString())
        assertNull(h.navigation.kmSource)
        play(10_000)
        assertTrue(!h.scripted.collecting, "the location stream stopped with both")
    }

    @Test
    fun endingGuidanceOnlyKeepsRecording() = runTest {
        val h = Harness(this)
        val tripId = h.trip()
        h.navigation.start(tripId)

        h.guidedPast(1_200.0)
        h.navigation.close()
        play(300_000)

        assertEquals(NavUiState.Idle, h.navigation.ui.value)
        assertInstanceOf(RideUiState.Active::class.java, h.ride.ui.value)
        assertEquals(tripId, h.active.tripId)
        assertEquals(2_400.0, h.active.stats.distanceM, 15.0, "recorded to the end of the 2.4 km track (no arrival ends it)")
        assertEquals(listOf(1), combinedKms(), voice.spoken.toString())
        assertEquals(listOf(2), recordingKms(), voice.spoken.toString())
        assertTrue(voice.spoken.single { it.startsWith("2킬로미터") }.endsWith("입니다."))
        assertTrue(guidanceKms().isEmpty(), voice.spoken.toString())
    }

    @Test
    fun stopTodayEndsRecordingAndKeepsTheTrip() = runTest {
        val h = Harness(this)
        val tripId = h.trip(TripType.MULTI_DAY)
        h.navigation.start(tripId)
        h.guidedPast(500.0)

        h.navigation.stopToday()
        val finished = h.rideFinished()

        assertEquals(NavUiState.Finished(tripId, TripType.MULTI_DAY, FinishReason.STOPPED_TODAY), h.navigation.ui.value)
        assertEquals(tripId, finished.segment.tripId)
        assertNotNull(finished.segment.endedAt)
        assertNull(finished.error)
        assertEquals(TripStatus.ACTIVE, trips.trips.getValue(tripId).status)
        assertNull(h.navigation.kmSource)
    }

    @Test
    fun kmSourceIsClearedWhenRecordingStops() = runTest {
        val h = Harness(this)
        val tripId = h.trip()
        h.navigation.start(tripId)
        h.guidedPast(500.0)

        h.ride.stopRecording(RecordingEnd.SEGMENT_ONLY)

        assertInstanceOf(RideUiState.Finished::class.java, h.ride.ui.value)
        assertNull(h.navigation.kmSource)
        assertEquals(TripStatus.ACTIVE, trips.trips.getValue(tripId).status, "only the segment ended")
        h.navigation.ui.first { it is NavUiState.Finished }
        assertEquals(listOf(1, 2), guidanceKms(), voice.spoken.toString())
        assertTrue(combinedKms().isEmpty(), voice.spoken.toString())
        assertTrue(recordingKms().isEmpty(), voice.spoken.toString())
        assertEquals(1, voice.spoken.count { it == "기록을 시작합니다" }, "not restarted while the guidance goes on")
    }

    @Test
    fun combinedKmCountsAgainFromOneAfterMidnight() = runTest {
        // 1.5 km ridden earlier today; the guidance starts 120 s before midnight (Asia/Seoul) and passes 0:00
        // after 600 m. Today's 2nd km comes at 500 m, the new day's 1st km at about 1_600 m.
        val h = Harness(this)
        val tripId = h.trip(TripType.MULTI_DAY)
        val earlier = h.store.openSegment(tripId, 0, testDay)
        h.store.closeSegment(earlier, 0, SegmentTotals.ZERO.copy(distanceM = 1_500.0, movingTimeMs = 300_000))
        val midnight = 15 * 3_600_000L
        play(midnight - 120_000 - testScheduler.currentTime)

        h.navigation.start(tripId)
        h.rideFinished()

        assertEquals(listOf(2, 1), combinedKms(), voice.spoken.toString())
        assertTrue(recordingKms().isEmpty(), voice.spoken.toString())
        assertTrue(guidanceKms().isEmpty(), voice.spoken.toString())
    }

    @Test
    fun guidanceAutoStartsRecordingAgainAfterASummary() = runTest {
        val h = Harness(this)
        val tripId = h.trip()
        h.navigation.start(tripId)
        h.guidedPast(300.0)
        h.ride.stopRecording(RecordingEnd.SEGMENT_ONLY)
        assertInstanceOf(RideUiState.Finished::class.java, h.ride.ui.value)

        h.navigation.close()
        h.navigation.start(tripId)
        play(10_000)

        assertInstanceOf(NavUiState.Active::class.java, h.navigation.ui.value)
        assertEquals(tripId, h.active.tripId)
        assertEquals(2, voice.spoken.count { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertNotNull(h.navigation.kmSource)
        assertEquals(2, h.store.segments(tripId).size)
    }

    @Test
    fun guidanceAutoStartsRecordingAfterAStartError() = runTest {
        val h = Harness(this)
        val ended = h.trip()
        h.tripManager.complete(ended)
        h.ride.startRecording(ended)!!.join()
        assertInstanceOf(RideUiState.Failed::class.java, h.ride.ui.value)
        val tripId = h.trip()

        h.navigation.start(tripId)
        play(10_000)

        assertEquals(tripId, h.active.tripId)
        assertEquals(1, voice.spoken.count { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertEquals(tripId, h.store.openSegments().single().tripId)
    }

    @Test
    fun aGuidanceStartWhoseRoutingFailsRecordsNothing() = runTest {
        val h = Harness(this)
        h.router.failure = RouteResult.Failure(RouteFailure.NO_ROUTE, "none")
        val tripId = h.trip()

        h.navigation.start(tripId)
        play(60_000)

        assertInstanceOf(NavUiState.Failed::class.java, h.navigation.ui.value)
        assertEquals(RideUiState.Idle, h.ride.ui.value)
        assertTrue(h.store.openSegments().isEmpty())
        assertTrue(voice.spoken.none { it == "기록을 시작합니다" }, voice.spoken.toString())
        assertNull(h.navigation.kmSource)
    }

    @Test
    fun muteDuringGuidanceEndsWithTheGuidanceOnly() = runTest {
        // Ruling F1: mute belongs to the guidance run; the recording that goes on after it is never muted.
        val h = Harness(this)
        val tripId = h.trip()
        h.navigation.start(tripId)
        h.guidedPast(1_200.0)
        h.navigation.setMuted(true)

        h.navigation.close()
        play(300_000)
        h.ride.pause()

        assertTrue(!voice.muted, "the shared voice is unmuted when the guidance ends")
        assertEquals(tripId, h.active.tripId)
        assertEquals(listOf(2), recordingKms(), voice.spoken.toString())
        assertTrue("일시정지되었습니다" in voice.spoken, voice.spoken.toString())
    }

    @Test
    fun muteDuringAFinishedGuidanceDoesNotSilenceTheNextRide() = runTest {
        val h = Harness(this)
        val tripId = h.trip(TripType.MULTI_DAY)
        h.navigation.start(tripId)
        h.guidedPast(500.0)
        h.navigation.setMuted(true)

        h.navigation.stopToday()
        h.rideFinished()
        h.ride.dismissSummary()
        voice.spoken.clear()
        h.ride.startRecording(tripId)!!.join()

        assertTrue(!voice.muted, "the shared voice is unmuted when the guidance finished")
        assertEquals(listOf("기록을 시작합니다"), voice.spoken.toList())
    }
}
