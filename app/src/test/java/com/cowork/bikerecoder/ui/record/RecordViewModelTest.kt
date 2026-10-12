package com.cowork.bikerecoder.ui.record

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.ride.FakePressureSource
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    private class Setup(val vm: RecordViewModel, val tripManager: TripManager, val navigation: MutableStateFlow<NavUiState>)

    private fun TestScope.setup(args: Map<String, Any?> = emptyMap()): Setup {
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
        val ride = RideController(
            rideStore = FakeRideStore(trips, clock),
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
        val vm = RecordViewModel(ride, navState, flowOf(true), SavedStateHandle(args))
        runCurrent()
        return Setup(vm, tripManager, navState)
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
}
