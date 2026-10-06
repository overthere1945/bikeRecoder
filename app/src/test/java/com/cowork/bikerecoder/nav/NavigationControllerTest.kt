package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.MemoryDayDistances
import com.cowork.bikerecoder.location.GpxLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.ui.common.routeFailureText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The controller on JVM fakes with virtual time: the same GPX/GeoJSON fixtures as the device
 * NavigationScenarioTest (a 2.4 km Z route in Seoul: north 800 m, right, east 800 m, left, north 800 m).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationControllerTest {

    private val store = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val voice = RecordingVoiceOutput()
    private val log = RecordingLog()
    private val follow = fixture("route_follow.geojson")
    private val reroute = fixture("route_reroute.geojson")

    private val destination = GeoPoint(37.5143891, 127.0390685)
    private val besideWaypoint = GeoPoint(37.5035973, 127.0295466) // 40 m west of the route at 400 m
    private val waypoint1 = GeoPoint(37.5035973, 127.03) // on the route at 400 m
    private val waypoint2 = GeoPoint(37.5071946, 127.0345343) // on the route at 1.2 km

    private fun stop(name: String, point: GeoPoint, isDestination: Boolean = false) =
        TripStop(0, 0, 0, name, point, isDestination, visitedAt = null)

    private inner class Harness(
        val scope: TestScope,
        val router: AssetRouter,
        var source: ScriptedLocationSource,
        voiceEnabled: Flow<Boolean>,
        val dayDistances: MemoryDayDistances = MemoryDayDistances(),
    ) {
        val tripManager = TripManager(store, offline, clock = { scope.testScheduler.currentTime })
        val controller = NavigationController(
            router = router,
            tripManager = tripManager,
            tripStore = store,
            offline = offline,
            voice = voice,
            locationSource = { source },
            voiceEnabled = voiceEnabled,
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
            log = log,
            dayDistances = dayDistances,
        )

        suspend fun trip(type: TripType, vararg stops: TripStop): Long =
            tripManager.startTrip(type, RouteProfile.CYCLEWAY_FIRST, stops.toList()).id

        suspend fun awaitFinished(): NavUiState.Finished = controller.ui.first { it is NavUiState.Finished } as NavUiState.Finished
        suspend fun awaitActive(): NavUiState.Active = controller.ui.first { it is NavUiState.Active } as NavUiState.Active
    }

    private fun TestScope.harness(
        gpx: String,
        routes: List<String> = listOf(follow),
        voiceEnabled: Flow<Boolean> = flowOf(true),
    ) = Harness(this, AssetRouter(routes), ScriptedLocationSource(gpxFixture(gpx)), voiceEnabled)

    private fun turnWords(spoken: List<String>) =
        spoken.mapNotNull { s -> listOf("우회전", "좌회전").firstOrNull { it in s }?.let { w -> if ("앞에서" in s) "far $w" else "near $w" } }

    @Test
    fun followScenarioAnnouncesTurnsKmAndArrival() = runTest {
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        val finished = h.awaitFinished()

        assertEquals(listOf("far 우회전", "near 우회전", "far 좌회전", "near 좌회전"), turnWords(voice.spoken))
        assertEquals(2, voice.spoken.count { it.contains("킬로미터 이동") }, voice.spoken.toString())
        assertEquals("목적지에 도착했습니다", voice.spoken.last())
        assertEquals(NavUiState.Finished(tripId, TripType.SINGLE_DAY, FinishReason.ARRIVED), finished)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(tripId).status)
        assertNotNull(store.stops(tripId).single().visitedAt)
        assertEquals(listOf(tripId), offline.downloaded)
        // The route request started at the first fix and went to the unvisited stops with the trip's profile.
        val request = h.router.requests.single()
        assertEquals(gpxFixture("scenario_follow.gpx").first().point, request.start)
        assertEquals(listOf(destination), request.stops)
        assertEquals(RouteProfile.CYCLEWAY_FIRST, request.profile)
        assertFalse(h.source.collecting, "location source stopped at arrival")
    }

    /** The test clock starts at epoch 0 = 1970-01-01 09:00 in Asia/Seoul. */
    private val testDay = LocalDate.of(1970, 1, 1)

    @Test
    fun resumingTheSameDayContinuesTodaysKilometres() = runTest {
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))
        h.dayDistances.save(tripId, testDay, 3_400.0)

        h.controller.start(tripId)
        h.awaitFinished()

        val kmReports = voice.spoken.filter { it.contains("킬로미터 이동") }
        assertEquals(listOf("4킬로미터", "5킬로미터"), kmReports.map { it.substringBefore(" ") }, voice.spoken.toString())
        val saved = h.dayDistances.distanceOn(tripId, testDay)
        assertTrue(saved > 3_400.0 + 2_300.0, "today's distance saved at the end: $saved")
    }

    @Test
    fun aNewDayStartsTheKilometresAtZero() = runTest {
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))
        h.dayDistances.save(tripId, testDay.minusDays(1), 3_400.0)

        h.controller.start(tripId)
        h.awaitFinished()

        val kmReports = voice.spoken.filter { it.contains("킬로미터 이동") }
        assertEquals(listOf("1킬로미터", "2킬로미터"), kmReports.map { it.substringBefore(" ") })
        assertTrue(h.dayDistances.distanceOn(tripId, testDay) in 2_300.0..2_600.0)
    }

    @Test
    fun todaysDistanceIsSavedEveryKilometreAndWhenStoppedForToday() = runTest {
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        h.controller.ui.first { it is NavUiState.Active && it.state.progress.distanceAlongM > 1_100.0 }
        runCurrent()
        val atOneKm = h.dayDistances.distanceOn(tripId, testDay)
        assertTrue(atOneKm >= 1_000.0, "saved when the first kilometre was passed: $atOneKm")

        h.controller.stopToday()
        h.awaitFinished()
        assertTrue(h.dayDistances.distanceOn(tripId, testDay) > atOneKm + 50.0, "saved again at the end")
    }

    @Test
    fun deviationScenarioReroutesExactlyOnce() = runTest {
        val h = harness("scenario_deviate.gpx", routes = listOf(follow, reroute))
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, voice.spoken.count { it == "경로를 벗어났습니다. 다시 탐색합니다" }, voice.spoken.toString())
        assertEquals(2, h.router.requests.size)
        val active = h.controller.ui.value as NavUiState.Active
        assertEquals(BRouterPoints.count(reroute), active.state.route.points.size, "route replaced by the reroute")
    }

    @Test
    fun spikeScenarioDoesNotReroute() = runTest {
        val h = harness("scenario_spike.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, h.router.requests.size)
        assertFalse(voice.spoken.any { "벗어났습니다" in it })
        assertInstanceOf(NavUiState.Active::class.java, h.controller.ui.value)
    }

    @Test
    fun waypointScenarioMarksVisited() = runTest {
        val h = harness("scenario_waypoint.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("경유", besideWaypoint), stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        advanceTimeBy(60_000)
        runCurrent()

        val (wp, dest) = store.stops(tripId)
        assertNotNull(wp.visitedAt)
        assertNull(dest.visitedAt)
        assertTrue("경유지에 도착했습니다" in voice.spoken)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(tripId).status)
        val active = h.controller.ui.value as NavUiState.Active
        assertEquals(listOf(dest.id), active.stops.map { it.id }, "only the destination remains on the map")
    }

    @Test
    fun resumeAfterRecreateUsesRemainingStops() = runTest {
        val first = harness("scenario_waypoint.gpx")
        val tripId = first.trip(
            TripType.MULTI_DAY,
            stop("경유1", waypoint1),
            stop("경유2", waypoint2),
            stop("도착", destination, isDestination = true),
        )
        first.controller.start(tripId)
        advanceTimeBy(60_000)
        runCurrent()
        assertNotNull(store.stops(tripId)[0].visitedAt)

        // The process dies: a brand-new controller, resumed from the main screen further along the route.
        first.controller.close()
        val rest = gpxFixture("scenario_follow.gpx").drop(gpxFixture("scenario_waypoint.gpx").size)
        val second = Harness(this, AssetRouter(listOf(follow)), ScriptedLocationSource(rest), flowOf(true))
        second.controller.start(tripId)
        second.awaitActive()

        assertEquals(listOf(waypoint2, destination), second.router.requests.last().stops)
        assertEquals(rest.first().point, second.router.requests.last().start)
    }

    @Test
    fun tripTransitions() = runTest {
        // Single day: arrival completes the trip; converting reopens it as an active multi-day trip.
        val h = harness("scenario_follow.gpx")
        val single = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(single)
        assertEquals(FinishReason.ARRIVED, h.awaitFinished().reason)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(single).status)
        h.controller.convertToMultiDay()
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(single).status)
        assertEquals(TripType.MULTI_DAY, store.trips.getValue(single).type)
        assertEquals(NavUiState.Idle, h.controller.ui.value)

        // Multi day: stop today keeps it active, resume, then complete.
        h.source = ScriptedLocationSource(gpxFixture("scenario_waypoint.gpx"))
        val multi = h.trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(multi)
        h.awaitActive()
        h.controller.stopToday()
        assertEquals(NavUiState.Finished(multi, TripType.MULTI_DAY, FinishReason.STOPPED_TODAY), h.awaitFinished())
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(multi).status)
        assertFalse(h.source.collecting, "location source stopped for today")

        h.controller.start(multi)
        h.awaitActive()
        h.controller.completeTrip()
        assertEquals(NavUiState.Finished(multi, TripType.MULTI_DAY, FinishReason.COMPLETED), h.controller.ui.value)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(multi).status)
        assertFalse(h.source.collecting)
    }

    @Test
    fun routeFailureShowsErrorWithoutSessionAndRetryStarts() = runTest {
        val h = harness("scenario_follow.gpx")
        h.router.failure = RouteResult.Failure(RouteFailure.NO_ROUTE, "none")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)

        assertEquals(NavUiState.Failed(tripId, routeFailureText(RouteFailure.NO_ROUTE)), h.controller.ui.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(voice.spoken.isEmpty(), "no session, no guidance")
        assertFalse(h.source.collecting)
        assertTrue(offline.downloaded.isEmpty())

        h.router.failure = null
        h.controller.start(tripId)
        h.awaitActive()
        assertEquals(2, h.router.requests.size)
    }

    @Test
    fun voiceDisabledInSettingsSpeaksNothing() = runTest {
        // Like DataStore, the setting arrives asynchronously — here only after the first turn prompts (600 m in,
        // 6 s at 20×) were due. Nothing may be spoken before it is known.
        val h = harness("scenario_follow.gpx", voiceEnabled = flow { delay(10_000); emit(false) })
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)
        h.awaitFinished()

        assertTrue(voice.spoken.isEmpty(), voice.spoken.toString())
    }

    @Test
    fun muteIsReflectedInStateAndVoice() = runTest {
        val h = harness("scenario_waypoint.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(tripId)
        h.awaitActive()

        h.controller.setMuted(true)

        assertTrue(voice.muted)
        assertTrue((h.controller.ui.value as NavUiState.Active).muted)
    }

    @Test
    fun ticksDriveTheGpsWatchdogAndTouchEveryMinute() = runTest {
        // Two fixes, then silence: only the 1 s ticks can flag the GPS as weak.
        val fixes = gpxFixture("scenario_follow.gpx").take(2)
        val h = Harness(this, AssetRouter(listOf(follow)), ScriptedLocationSource(fixes), flowOf(true))
        val tripId = h.trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))
        val created = store.trips.getValue(tripId).lastActiveAt

        h.controller.start(tripId)
        advanceTimeBy(15_000)
        runCurrent()

        assertTrue((h.controller.ui.value as NavUiState.Active).state.gpsWeak)
        assertEquals(1, voice.spoken.count { it == "GPS 신호가 약합니다" })

        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(store.trips.getValue(tripId).lastActiveAt > created, "touched while guiding")
    }

    @Test
    fun beginForTheRunningTripDoesNotRestart() = runTest {
        val h = harness("scenario_waypoint.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(tripId)
        h.awaitActive()

        h.controller.begin(tripId)
        runCurrent()

        assertEquals(1, h.router.requests.size)
        assertEquals(1, h.source.collections)
    }

    @Test
    fun closeStopsGuidanceWithoutTouchingTheTrip() = runTest {
        val h = harness("scenario_waypoint.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(tripId)
        h.awaitActive()

        h.controller.close()
        runCurrent()

        assertEquals(NavUiState.Idle, h.controller.ui.value)
        assertFalse(h.source.collecting)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(tripId).status)
    }

    @Test
    fun aLocationSourceWithoutAnyFixFailsTheStart() = runTest {
        val failing = object : LocationSource {
            override fun fixes() = flow<LocationFix> { throw SecurityException("no permission") }
        }
        val ended = GpxLocationSource(emptyList())
        for (source in listOf(failing, ended)) {
            val router = AssetRouter(listOf(follow))
            val tripManager = TripManager(store, offline, clock = { testScheduler.currentTime })
            val tripId = tripManager.startTrip(
                TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, listOf(stop("도착", destination, isDestination = true)),
            ).id
            val controller = NavigationController(
                router, tripManager, store, offline, voice, { source }, flowOf(true), backgroundScope,
                clock = { testScheduler.currentTime }, log = log,
            )

            controller.start(tripId)

            assertEquals(NavUiState.Failed(tripId, NavigationController.MSG_NO_LOCATION), controller.ui.value)
            assertTrue(router.requests.isEmpty())
        }
        assertTrue(log.messages.isNotEmpty(), "the location failure is logged")
    }

    @Test
    fun noFixWithinTimeoutFailsTheStart() = runTest {
        val silent = ScriptedLocationSource(emptyList())
        val h = Harness(this, AssetRouter(listOf(follow)), silent, flowOf(true))
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.begin(tripId)
        advanceTimeBy(NavigationController.FIRST_FIX_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(NavUiState.Starting(tripId), h.controller.ui.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(NavUiState.Failed(tripId, NavigationController.MSG_NO_LOCATION), h.controller.ui.value)
        assertFalse(silent.collecting, "location source stopped")
        assertTrue(h.router.requests.isEmpty())
    }

    @Test
    fun theStartWaitsForAnAccurateFirstFix() = runTest {
        val track = gpxFixture("scenario_follow.gpx")
        val fixes = listOf(track[0].copy(accuracyM = 80f), track[1].copy(accuracyM = 45f)) + track.drop(2)
        val h = Harness(this, AssetRouter(listOf(follow)), ScriptedLocationSource(fixes), flowOf(true))
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.start(tripId)

        assertEquals(track[2].point, h.router.requests.single().start, "the first fix within 30 m")
    }

    @Test
    fun withoutAnAccurateFixTheBestOneIsUsedAfterTheTimeout() = runTest {
        val track = gpxFixture("scenario_follow.gpx")
        val fixes = listOf(track[0].copy(accuracyM = 80f), track[1].copy(accuracyM = 45f), track[2].copy(accuracyM = 60f))
        val h = Harness(this, AssetRouter(listOf(follow)), ScriptedLocationSource(fixes), flowOf(true))
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        h.controller.begin(tripId)
        advanceTimeBy(NavigationController.FIRST_FIX_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(NavUiState.Starting(tripId), h.controller.ui.value)
        assertTrue(h.router.requests.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(track[1].point, h.router.requests.single().start, "the most accurate fix seen")
    }

    @Test
    fun aTripThatIsNoLongerActiveIsNotStarted() = runTest {
        // E.g. a stale "안내가 중단되었습니다" notification tapped after the trip was completed.
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.tripManager.complete(tripId)

        h.controller.start(tripId)

        assertEquals(NavUiState.Failed(tripId, NavigationController.MSG_TRIP_ENDED, canRetry = false), h.controller.ui.value)
        assertEquals(0, h.source.collections)
        assertTrue(h.router.requests.isEmpty())
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(tripId).status)
    }

    @Test
    fun aFailedCompleteOrStopTodayIsReportedNotShownAsSuccess() = runTest {
        for (type in TripType.entries) {
            val h = harness("scenario_waypoint.gpx")
            val tripId = h.trip(type, stop("도착", destination, isDestination = true))
            h.controller.start(tripId)
            h.awaitActive()
            store.failUpdate = { true }
            log.messages.clear()

            if (type == TripType.MULTI_DAY) h.controller.stopToday() else h.controller.completeTrip()
            val finished = h.awaitFinished()
            store.failUpdate = { false }

            assertEquals(NavigationController.MSG_SAVE_FAILED, finished.error, "$type")
            assertEquals(TripStatus.ACTIVE, store.trips.getValue(tripId).status)
            assertFalse(h.source.collecting, "guidance stopped")
            assertTrue(log.messages.isNotEmpty(), "the failure is logged")
            h.controller.close()
        }
    }

    @Test
    fun aFailedConvertToMultiDayIsReportedNotShownAsSuccess() = runTest {
        val h = harness("scenario_follow.gpx")
        val tripId = h.trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        h.controller.start(tripId)
        val finished = h.awaitFinished()
        store.failUpdate = { true }

        h.controller.convertToMultiDay()

        assertEquals(finished.copy(error = NavigationController.MSG_SAVE_FAILED), h.controller.ui.value)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(tripId).status)
        assertTrue(log.messages.isNotEmpty(), "the failure is logged")
    }
}

private object BRouterPoints {
    fun count(json: String): Int =
        com.cowork.bikerecoder.routing.BRouterGeoJsonParser.parse(json, RouteProfile.BALANCED, emptyList()).points.size
}
