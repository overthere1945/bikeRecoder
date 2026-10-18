package com.cowork.bikerecoder.nav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.gpx.GpxParser
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.location.GpxLocationSource
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPX scenarios (spec §7.3) against the real controller on the main thread, an in-memory Room DB,
 * GpxLocationSource at 20× and a recording voice. Fixtures (androidTest assets, also used by the JVM
 * NavigationControllerTest): a synthetic 2.4 km Z route in Seoul — north 800 m, right, east 800 m, left,
 * north 800 m — in BRouter FormatJson shape, and GPX tracks on that geometry (10 m / 2 s = 18 km/h).
 */
@RunWith(AndroidJUnit4::class)
class NavigationScenarioTest {

    /** GpxLocationSource(speedup = 20) that reports when its replay has ended. */
    private class Replay(fixes: List<LocationFix>) : LocationSource {
        private val gpx = GpxLocationSource(fixes, speedup = 20.0)
        val done = CompletableDeferred<Unit>()
        override fun fixes(): Flow<LocationFix> = gpx.fixes().onCompletion { done.complete(Unit) }
    }

    private object NoOffline : OfflineMapController {
        override suspend fun deleteForTrip(tripId: Long) = Unit
        override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
    }

    private lateinit var db: AppDatabase
    private lateinit var store: RoomTripStore
    private lateinit var tripManager: TripManager
    private val voice = RecordingVoiceOutput()
    private val scopes = mutableListOf<CoroutineScope>()

    private val destination = GeoPoint(37.5143891, 127.0390685)
    private val besideWaypoint = GeoPoint(37.5035973, 127.0295466) // 40 m west of the route at 400 m
    private val waypoint1 = GeoPoint(37.5035973, 127.03)
    private val waypoint2 = GeoPoint(37.5071946, 127.0345343)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomTripStore(db)
        tripManager = TripManager(store, NoOffline, clock = System::currentTimeMillis)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes().decodeToString() }

    private fun gpx(name: String) = GpxParser.parse(asset(name))

    private fun controller(router: Router, source: LocationSource): NavigationController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        return NavigationController(router, tripManager, store, NoOffline, voice, { source }, flowOf(true), scope)
    }

    private fun stop(name: String, point: GeoPoint, isDestination: Boolean = false) =
        TripStop(0, 0, 0, name, point, isDestination, visitedAt = null)

    private suspend fun trip(type: TripType, vararg stops: TripStop) =
        tripManager.startTrip(type, RouteProfile.CYCLEWAY_FIRST, stops.toList()).id

    private suspend fun NavigationController.startOnMain(tripId: Long) = withContext(Dispatchers.Main) { start(tripId) }

    private suspend fun NavigationController.await(predicate: (NavUiState) -> Boolean): NavUiState =
        withTimeout(TIMEOUT_MS) { ui.first(predicate) }

    /** Waits for the replay to end and for the main thread to process what it emitted. */
    private suspend fun Replay.finish() {
        withTimeout(TIMEOUT_MS) { done.await() }
        repeat(2) { withContext(Dispatchers.Main) { } }
    }

    private fun turnWords() = voice.spoken.mapNotNull { s ->
        listOf("우회전", "좌회전").firstOrNull { it in s }?.let { w -> if ("앞에서" in s) "far $w" else "near $w" }
    }

    @Test
    fun followScenarioAnnouncesTurnsKmAndArrival() = runBlocking {
        val router = AssetRouter(listOf(asset("route_follow.geojson")))
        val c = controller(router, Replay(gpx("scenario_follow.gpx")))
        val tripId = trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        c.startOnMain(tripId)
        val finished = c.await { it is NavUiState.Finished }

        assertEquals(voice.spoken.toString(), listOf("far 우회전", "near 우회전", "far 좌회전", "near 좌회전"), turnWords())
        assertEquals(voice.spoken.toString(), 2, voice.spoken.count { "킬로미터 이동" in it })
        assertEquals("목적지에 도착했습니다", voice.spoken.last())
        assertEquals(NavUiState.Finished(tripId, TripType.SINGLE_DAY, FinishReason.ARRIVED), finished)
        assertEquals(TripStatus.COMPLETED, store.trip(tripId)!!.status)
    }

    @Test
    fun deviationScenarioReroutesExactlyOnce() = runBlocking {
        val router = AssetRouter(listOf(asset("route_follow.geojson"), asset("route_reroute.geojson")))
        val replay = Replay(gpx("scenario_deviate.gpx"))
        val c = controller(router, replay)
        val tripId = trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        c.startOnMain(tripId)
        replay.finish()

        assertEquals(voice.spoken.toString(), 1, voice.spoken.count { it == "경로를 벗어났습니다. 다시 탐색합니다" })
        assertEquals(2, router.requests.size)
        val reroute = router.requests.last().let { BRouterGeoJsonParser.parse(asset("route_reroute.geojson"), it.profile, it.stops) }
        val active = c.ui.value as NavUiState.Active
        assertEquals("route replaced by the reroute", reroute.points, active.state.route.points)
    }

    @Test
    fun spikeScenarioDoesNotReroute() = runBlocking {
        val router = AssetRouter(listOf(asset("route_follow.geojson")))
        val replay = Replay(gpx("scenario_spike.gpx"))
        val c = controller(router, replay)
        val tripId = trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))

        c.startOnMain(tripId)
        replay.finish()

        assertEquals(1, router.requests.size)
        assertFalse(voice.spoken.toString(), voice.spoken.any { "벗어났습니다" in it })
    }

    @Test
    fun waypointScenarioMarksVisited() = runBlocking {
        val router = AssetRouter(listOf(asset("route_follow.geojson")))
        val replay = Replay(gpx("scenario_waypoint.gpx"))
        val c = controller(router, replay)
        val tripId = trip(TripType.SINGLE_DAY, stop("경유", besideWaypoint), stop("도착", destination, isDestination = true))

        c.startOnMain(tripId)
        replay.finish()

        val (wp, dest) = store.stops(tripId)
        assertNotNull(wp.visitedAt)
        assertNull(dest.visitedAt)
    }

    @Test
    fun resumeAfterRecreateUsesRemainingStops() = runBlocking {
        val firstReplay = Replay(gpx("scenario_waypoint.gpx"))
        val first = controller(AssetRouter(listOf(asset("route_follow.geojson"))), firstReplay)
        val tripId = trip(
            TripType.MULTI_DAY,
            stop("경유1", waypoint1),
            stop("경유2", waypoint2),
            stop("도착", destination, isDestination = true),
        )
        first.startOnMain(tripId)
        firstReplay.finish()
        assertNotNull(store.stops(tripId)[0].visitedAt)

        // Discard the controller (as if the process died) and resume further along with a new one.
        withContext(Dispatchers.Main) { first.close() }
        scopes.first().cancel()
        val rest = gpx("scenario_follow.gpx").drop(gpx("scenario_waypoint.gpx").size)
        val router = AssetRouter(listOf(asset("route_follow.geojson")))
        val second = controller(router, Replay(rest))
        second.startOnMain(tripId)
        second.await { it is NavUiState.Active }

        assertEquals(listOf(waypoint2, destination), router.requests.last().stops)
    }

    @Test
    fun tripTransitions() = runBlocking {
        // Single day → arrival → COMPLETED; [여러 날로 바꾸기] → ACTIVE multi-day.
        val single = controller(AssetRouter(listOf(asset("route_follow.geojson"))), Replay(gpx("scenario_follow.gpx")))
        val singleId = trip(TripType.SINGLE_DAY, stop("도착", destination, isDestination = true))
        single.startOnMain(singleId)
        single.await { it is NavUiState.Finished }
        assertEquals(TripStatus.COMPLETED, store.trip(singleId)!!.status)
        withContext(Dispatchers.Main) { single.convertToMultiDay() }
        assertEquals(TripStatus.ACTIVE, store.trip(singleId)!!.status)
        assertEquals(TripType.MULTI_DAY, store.trip(singleId)!!.type)

        // Multi day → [오늘은 여기까지] → ACTIVE → [이어서 안내] → [여행 완료] → COMPLETED.
        val multi = controller(AssetRouter(listOf(asset("route_follow.geojson"))), Replay(gpx("scenario_waypoint.gpx")))
        val multiId = trip(TripType.MULTI_DAY, stop("도착", destination, isDestination = true))
        multi.startOnMain(multiId)
        multi.await { it is NavUiState.Active }
        withContext(Dispatchers.Main) { multi.stopToday() }
        multi.await { it is NavUiState.Finished && it.reason == FinishReason.STOPPED_TODAY }
        assertEquals(TripStatus.ACTIVE, store.trip(multiId)!!.status)

        multi.startOnMain(multiId)
        multi.await { it is NavUiState.Active }
        withContext(Dispatchers.Main) { multi.completeTrip() }
        assertEquals(NavUiState.Finished(multiId, TripType.MULTI_DAY, FinishReason.COMPLETED), multi.ui.value)
        assertEquals(TripStatus.COMPLETED, store.trip(multiId)!!.status)
        assertTrue(voice.spoken.isNotEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
    }
}
