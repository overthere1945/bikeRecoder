package com.cowork.bikerecoder.ride

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppDatabase
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.data.RoomRideStore
import com.cowork.bikerecoder.data.RoomTripStore
import com.cowork.bikerecoder.location.SharedLocationSource
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.NoOffline
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.Replay
import com.cowork.bikerecoder.nav.scenarioAsset
import com.cowork.bikerecoder.nav.scenarioGpx
import com.cowork.bikerecoder.ride.RideFixtures.StopAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recording scenarios (spec §5.2 ①–⑤) against the real [RideController] and [NavigationController] on the main
 * thread, an in-memory Room DB and a recording voice. The track is replayed at 20× through one
 * [SharedLocationSource] that both controllers subscribe to; [ReplayClock] makes the controller's clock run at the same
 * 20× so elapsed time matches the replay. Nothing is spoken for real and no file is written.
 */
@RunWith(AndroidJUnit4::class)
class RideScenarioTest {

    /** Wall-clock time from [startMillis], running [SPEEDUP] times as fast as real time. */
    private class ReplayClock(private val startMillis: Long, private val speedup: Double) : () -> Long {
        private val base = SystemClock.elapsedRealtime()
        override fun invoke(): Long = startMillis + ((SystemClock.elapsedRealtime() - base) * speedup).toLong()
    }

    private lateinit var db: AppDatabase
    private lateinit var tripStore: RoomTripStore
    private lateinit var store: RoomRideStore
    private lateinit var tripManager: TripManager
    private val voice = RecordingVoiceOutput()
    private val scopes = mutableListOf<CoroutineScope>()
    private val destination = GeoPoint(37.5143891, 127.0390685)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        tripStore = RoomTripStore(db)
        store = RoomRideStore(db)
        tripManager = TripManager(tripStore, NoOffline, clock = System::currentTimeMillis)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    private val assetContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }

    /** One recording stack: the controllers of an app run, sharing one replayed location stream. */
    private inner class Stack(fixes: List<LocationFix>, routeJson: String? = null, scope: CoroutineScope = newScope()) {
        val replay = Replay(fixes, SPEEDUP)
        val shared = SharedLocationSource({ replay }, scope)
        val clock = ReplayClock(startMillis = System.currentTimeMillis(), speedup = SPEEDUP)
        val router = AssetRouter(listOfNotNull(routeJson))
        val navigation = NavigationController(
            router, tripManager, tripStore, NoOffline, voice, { shared }, flowOf(true), scope,
        )
        val ride = RideController(
            rideStore = store,
            tripStore = tripStore,
            tripManager = tripManager,
            navigation = navigation,
            voice = voice,
            location = shared,
            pressure = null,
            settings = flowOf(AppSettings()),
            scope = scope,
            clock = clock,
            saveIntervalMs = 250,
        )
        val lastFixTime = fixes.last().timeMillis

        /** Waits until the replay has ended and the recording has processed its last fix. */
        suspend fun replayed() {
            withTimeout(TIMEOUT_MS) { replay.done.await() }
            withTimeout(TIMEOUT_MS) { ride.ui.first { it is RideUiState.Active && it.fix?.timeMillis == lastFixTime } }
        }

        suspend fun start(tripId: Long) = withContext(Dispatchers.Main) { ride.startRecording(tripId) }

        suspend fun stop(end: RecordingEnd = RecordingEnd.SEGMENT_ONLY): RideUiState.Finished {
            withContext(Dispatchers.Main) { ride.stopRecording(end) }
            return ride.ui.value as RideUiState.Finished
        }
    }

    private suspend fun trip(type: TripType = TripType.SINGLE_DAY): Long = tripManager.startTrip(
        type,
        RouteProfile.CYCLEWAY_FIRST,
        listOf(TripStop(0, 0, 0, "도착", destination, isDestination = true, visitedAt = null)),
    ).id

    private fun recordedKms() = voice.spoken.filter { "킬로미터 이동. 평균 속도" in it && "남은 거리" !in it }

    private fun combinedKms() = voice.spoken
        .filter { "킬로미터 이동. 평균 속도" in it && "남은 거리" in it && "도착 예정" in it }
        .map { it.substringBefore("킬로미터").toInt() }

    private fun guidanceKms() = voice.spoken.filter { "킬로미터 이동. 남은 거리" in it }

    @Test
    fun recordOnlyThreeKm() = runBlocking {
        val s = Stack(RideFixtures.straight(3_010.0, startMillis = System.currentTimeMillis()))
        val tripId = trip()

        s.start(tripId)!!.join()
        s.replayed()
        val finished = s.stop()

        assertEquals(voice.spoken.toString(), 1, voice.spoken.count { it == "기록을 시작합니다" })
        assertEquals(voice.spoken.toString(), 3, recordedKms().size)
        val saved = store.segment(finished.segment.id)!!
        assertNotNull(saved.endedAt)
        assertEquals(3_010.0, saved.totals.distanceM, 1.0)
        assertNull(finished.error)
    }

    @Test
    fun twoSignalStops() = runBlocking {
        val fixes = RideFixtures.straight(
            1_500.0,
            stops = listOf(StopAt(500.0, 30), StopAt(1_000.0, 30)),
            startMillis = System.currentTimeMillis(),
        )
        val s = Stack(fixes)
        val tripId = trip()

        s.start(tripId)!!.join()
        s.replayed()
        val finished = s.stop()

        assertEquals(voice.spoken.toString(), 2, voice.spoken.count { it == "일시정지되었습니다" })
        assertEquals(voice.spoken.toString(), 2, voice.spoken.count { it == "기록을 다시 시작합니다" })
        val saved = store.segment(finished.segment.id)!!
        assertTrue("${saved.totals}", saved.totals.movingTimeMs < saved.totals.elapsedMs)
        assertEquals(1_470.0, saved.totals.distanceM, 1.0)
    }

    @Test
    fun guidanceWithRecordingSpeaksOneSentencePerKm() = runBlocking {
        val s = Stack(
            scenarioGpx(assetContext, "scenario_follow.gpx"),
            routeJson = scenarioAsset(assetContext, "route_follow.geojson"),
        )
        val tripId = trip()

        withContext(Dispatchers.Main) { s.navigation.start(tripId) }
        withTimeout(TIMEOUT_MS) { s.ride.ui.first { it is RideUiState.Finished } }

        assertEquals(voice.spoken.toString(), listOf(1, 2), combinedKms())
        assertTrue(voice.spoken.toString(), guidanceKms().isEmpty())
        assertTrue(voice.spoken.toString(), recordedKms().isEmpty())
    }

    @Test
    fun killedRecordingResumesInANewSegment() = runBlocking {
        val tripId = trip(TripType.MULTI_DAY)
        val scopeA = newScope()
        val a = Stack(RideFixtures.straight(3_010.0, startMillis = System.currentTimeMillis()), scope = scopeA)
        a.start(tripId)!!.join()
        val firstSegment = (a.ride.ui.value as RideUiState.Active).segmentId
        // Wait for a save to land, then kill: no stop, no close.
        withTimeout(TIMEOUT_MS) {
            while (store.segment(firstSegment)!!.totals.distanceM <= 0.0) delay(100)
        }
        scopeA.cancel()
        assertNull(store.segment(firstSegment)!!.endedAt)

        val b = Stack(RideFixtures.straight(500.0, startMillis = System.currentTimeMillis()))
        val interrupted = withContext(Dispatchers.Main) { b.ride.interrupted() }
        assertNotNull(interrupted)
        assertEquals(firstSegment, interrupted!!.id)
        b.start(interrupted.tripId)!!.join()

        val segments = store.segments(tripId)
        assertEquals(2, segments.size)
        assertNotNull("the killed segment is closed at its last save", segments[0].endedAt)
        assertNull(segments[1].endedAt)
        b.stop()
        assertNotNull(store.segment(segments[1].id)!!.endedAt)
    }

    @Test
    fun arrivalEndsBothWithSummary() = runBlocking {
        val s = Stack(
            scenarioGpx(assetContext, "scenario_follow.gpx"),
            routeJson = scenarioAsset(assetContext, "route_follow.geojson"),
        )
        val tripId = trip(TripType.SINGLE_DAY)

        withContext(Dispatchers.Main) { s.navigation.start(tripId) }
        val finished = withTimeout(TIMEOUT_MS) { s.ride.ui.first { it is RideUiState.Finished } } as RideUiState.Finished

        assertEquals(
            NavUiState.Finished(tripId, TripType.SINGLE_DAY, FinishReason.ARRIVED),
            withTimeout(TIMEOUT_MS) { s.navigation.ui.first { it is NavUiState.Finished } },
        )
        val saved = store.segment(finished.segment.id)!!
        assertNotNull(saved.endedAt)
        assertEquals(2_370.0, saved.totals.distanceM, 15.0)
        assertEquals(TripStatus.COMPLETED, tripStore.trip(tripId)!!.status)
        assertNull(finished.error)
    }

    private companion object {
        const val SPEEDUP = 20.0
        const val TIMEOUT_MS = 90_000L
    }
}
