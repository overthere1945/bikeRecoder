package com.cowork.bikerecoder.ui.record

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.core.post.PlacedPhoto
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.GuidanceServiceStatus
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.post.FakeHeartRateSource
import com.cowork.bikerecoder.post.FakePhotoSource
import com.cowork.bikerecoder.post.FakePostStore
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.ride.FakePressureSource
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The record screen's view model on the Task 10 fakes (a real [RideController], virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val trips = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val voice = RecordingVoiceOutput()
    private val log = RecordingLog()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class Setup(
        val vm: RecordViewModel,
        val tripManager: TripManager,
        val navigation: MutableStateFlow<NavUiState>,
        val ride: RideController,
        val postStore: FakePostStore,
    ) {
        /** Post-processing passes so far. */
        var passes = 0
    }

    private fun TestScope.setup(
        args: Map<String, Any?> = emptyMap(),
        serviceStatus: GuidanceServiceStatus = GuidanceServiceStatus(),
    ): Setup {
        val clock = { testScheduler.currentTime }
        val tripManager = TripManager(trips, offline, clock)
        val navigation = NavigationController(
            router = AssetRouter(emptyList()),
            tripManager = tripManager,
            tripStore = trips,
            offline = offline,
            voice = voice,
            locationSource = { ScriptedLocationSource(emptyList()) },
            voiceEnabled = flowOf(true),
            scope = backgroundScope,
            clock = clock,
            log = log,
        )
        val rideStore = FakeRideStore(trips, clock)
        val postStore = FakePostStore { trips.trips.keys.flatMap { rideStore.segments(it) } }
        val postProcessor = PostProcessor(
            rideStore, postStore, FakePhotoSource(), FakeHeartRateSource(), clock = clock, scope = backgroundScope,
            warnLog = { _, _ -> },
        )
        val ride = RideController(
            rideStore = rideStore,
            tripStore = trips,
            tripManager = tripManager,
            navigation = navigation,
            voice = voice,
            location = ScriptedLocationSource(emptyList()),
            pressure = FakePressureSource(),
            settings = MutableStateFlow(AppSettings()),
            scope = backgroundScope,
            clock = clock,
            log = log,
        )
        val navState = MutableStateFlow<NavUiState>(NavUiState.Idle)
        val vm = RecordViewModel(
            ride, navState, flowOf(true), SavedStateHandle(args), serviceStatus.warning, postStore, postProcessor,
        )
        val setup = Setup(vm, tripManager, navState, ride, postStore)
        postStore.beforeList = { setup.passes++ }
        runCurrent()
        return setup
    }

    @Test
    fun pendingStartIsTakenOnce() = runTest(dispatcher) {
        val vm = setup(mapOf(RecordViewModel.ARG_TRIP to 5L)).vm

        assertTrue(vm.hasPendingStart)
        assertEquals(5L, vm.takePendingStart())
        assertNull(vm.takePendingStart())
        assertFalse(vm.hasPendingStart)
    }

    @Test
    fun noTripMeansNoPendingStart() = runTest(dispatcher) {
        val vm = setup().vm

        assertFalse(vm.hasPendingStart)
        assertNull(vm.takePendingStart())
    }

    @Test
    fun pauseResumeAndTheChipDriveTheRecording() = runTest(dispatcher) {
        val s = setup()
        val tripId = s.tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id
        s.vm.start(tripId)
        runCurrent()
        assertInstanceOf(RideUiState.Active::class.java, s.vm.ui.value)

        s.vm.pause()
        assertEquals(RecordingState.Paused(PauseKind.MANUAL), (s.vm.ui.value as RideUiState.Active).state)
        s.vm.resume()
        assertEquals(RecordingState.Recording, (s.vm.ui.value as RideUiState.Active).state)
        s.vm.setAutoPause(false)
        assertFalse((s.vm.ui.value as RideUiState.Active).autoPause)
    }

    @Test
    fun endChoiceStopsTheRecording() = runTest(dispatcher) {
        val s = setup()
        val tripId = s.tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id
        s.vm.start(tripId)
        runCurrent()

        s.vm.onEndChoice(RecordingEnd.COMPLETE_TRIP)
        runCurrent()

        assertInstanceOf(RideUiState.Finished::class.java, s.vm.ui.value)
        assertEquals(TripStatus.COMPLETED, trips.trips.getValue(tripId).status)

        s.vm.dismissSummary()
        assertEquals(RideUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun navigatingFollowsTheGuidance() = runTest(dispatcher) {
        val s = setup()
        assertFalse(s.vm.navigating.value)

        s.navigation.value = NavUiState.Starting(3)
        runCurrent()
        assertTrue(s.vm.navigating.value)

        s.navigation.value = NavUiState.Idle
        runCurrent()
        assertFalse(s.vm.navigating.value)
    }

    @Test
    fun aFailedForegroundServiceIsShownOnTheRecordScreenToo() = runTest(dispatcher) {
        val status = GuidanceServiceStatus()
        val vm = setup(serviceStatus = status).vm
        assertNull(vm.serviceWarning.value)

        status.foregroundFailed()

        assertEquals(GuidanceServiceStatus.MSG_SCREEN_OFF_RISK, vm.serviceWarning.value)
    }

    @Test
    fun recordScreenTakesNoticesOnlyWhileRecording() = runTest(dispatcher) {
        val s = setup()
        s.ride.announce("안내를 마칩니다. 기록은 계속됩니다")
        runCurrent()
        assertNull(s.vm.noticeForScreen.value, "nothing is recording")

        val tripId = s.tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id
        s.vm.start(tripId)
        runCurrent()
        assertInstanceOf(RideUiState.Active::class.java, s.vm.ui.value)
        s.ride.announce("안내를 마칩니다. 기록은 계속됩니다")
        runCurrent()

        val notice = s.vm.noticeForScreen.value
        assertEquals("안내를 마칩니다. 기록은 계속됩니다", notice?.text)

        s.vm.noticeShown(notice!!.id)
        runCurrent()
        assertNull(s.vm.noticeForScreen.value)
    }

    @Test
    fun aSplitShowsItsNoticeOnTheRecordScreen() = runTest(dispatcher) {
        val s = setup()
        val tripId = s.tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id
        s.vm.start(tripId)
        runCurrent()
        val firstSegment = (s.vm.ui.value as RideUiState.Active).segmentId

        backgroundScope.launch { s.ride.splitSegment() }
        runCurrent()

        val active = s.vm.ui.value as RideUiState.Active
        assertTrue(active.segmentId != firstSegment, "a new segment records")
        assertEquals("새 구간으로 기록을 시작합니다", s.vm.noticeForScreen.value?.text)
    }

    @Test
    fun summaryCountsVisiblePhotosAndRequestsOnePass() = runTest(dispatcher) {
        val s = setup()
        val tripId = s.tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id
        s.vm.start(tripId)
        runCurrent()
        assertEquals(0, s.vm.summaryExtras.value.photoCount)
        assertEquals(0, s.passes, "no pass while recording")

        s.vm.onEndChoice(RecordingEnd.COMPLETE_TRIP)
        runCurrent()
        val segmentId = (s.vm.ui.value as RideUiState.Finished).segment.id
        assertEquals(1, s.passes, "the summary asks for one pass")

        s.postStore.upsertPhotos(
            segmentId,
            (1L..3L).map { PlacedPhoto(it, it, GeoPoint(37.5, 127.0), PhotoPositionSource.TRACK) },
        )
        s.postStore.setHidden(s.postStore.photos.value.first().id, hidden = true)
        runCurrent()
        assertEquals(2, s.vm.summaryExtras.value.photoCount, "hidden photos are not counted")
        assertEquals(1, s.passes)

        s.vm.dismissSummary()
        runCurrent()
        assertEquals(0, s.vm.summaryExtras.value.photoCount)
        assertEquals(1, s.passes)
    }
}
