package com.cowork.bikerecoder.ui.navigate

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.nav.fixture
import com.cowork.bikerecoder.nav.gpxFixture
import com.cowork.bikerecoder.tts.VoiceOutput
import com.cowork.bikerecoder.ui.trip.EndAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigateViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val destination = GeoPoint(37.5143891, 127.0390685)

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private class Setup(val vm: NavigateViewModel, val tripManager: TripManager, val controller: NavigationController)

    private fun TestScope.setup(
        args: Map<String, Any?> = emptyMap(),
        voice: VoiceOutput = RecordingVoiceOutput(),
        noticeShown: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ): Setup {
        val tripManager = TripManager(store, offline, clock = { testScheduler.currentTime })
        val controller = NavigationController(
            AssetRouter(listOf(fixture("route_follow.geojson"))), tripManager, store, offline, voice,
            { ScriptedLocationSource(gpxFixture("scenario_waypoint.gpx")) }, flowOf(true), backgroundScope,
            clock = { testScheduler.currentTime }, log = com.cowork.bikerecoder.nav.RecordingLog(),
        )
        val vm = NavigateViewModel(
            controller, voice, flowOf(true), SavedStateHandle(args), noticeShown, { noticeShown.value = true },
        )
        return Setup(vm, tripManager, controller)
    }

    private suspend fun Setup.runningTrip(type: TripType): Long {
        val id = tripManager.startTrip(
            type,
            RouteProfile.BALANCED,
            listOf(TripStop(0, 0, 0, "도착", destination, isDestination = true, visitedAt = null)),
        ).id
        controller.start(id)
        controller.ui.first { it is NavUiState.Active }
        return id
    }

    @Test
    fun pendingStartIsTakenOnce() = runTest(dispatcher) {
        val s = setup(args = mapOf(NavigateViewModel.ARG_TRIP to 7L))

        assertTrue(s.vm.hasPendingStart)
        assertEquals(7L, s.vm.takePendingStart())
        assertNull(s.vm.takePendingStart())
        assertFalse(s.vm.hasPendingStart)
    }

    @Test
    fun noTripArgumentMeansNothingToStart() = runTest(dispatcher) {
        val s = setup(args = mapOf(NavigateViewModel.ARG_TRIP to -1L))

        assertFalse(s.vm.hasPendingStart)
        assertNull(s.vm.takePendingStart())
    }

    @Test
    fun stopTodayChoiceKeepsTheMultiDayTripActive() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.MULTI_DAY)

        s.vm.onEndChoice(EndAction.STOP_TODAY)
        runCurrent()

        assertEquals(NavUiState.Finished(id, TripType.MULTI_DAY, FinishReason.STOPPED_TODAY), s.vm.ui.value)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(id).status)
    }

    @Test
    fun completeChoiceCompletesTheTrip() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)

        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()

        assertEquals(NavUiState.Finished(id, TripType.SINGLE_DAY, FinishReason.COMPLETED), s.vm.ui.value)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(id).status)

        s.vm.convertToMultiDay()
        runCurrent()
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(id).status)
        assertEquals(TripType.MULTI_DAY, store.trips.getValue(id).type)
        assertEquals(NavUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun missingKoreanTtsIsNoticedOnceEver() = runTest(dispatcher) {
        val unavailable = object : VoiceOutput by RecordingVoiceOutput() {
            override val available = MutableStateFlow(false)
        }
        val shown = MutableStateFlow(false) // the persisted settings flag
        val first = setup(voice = unavailable, noticeShown = shown)
        advanceTimeBy(NavigateViewModel.TTS_GRACE_MS + 1)
        runCurrent()
        assertTrue(first.vm.ttsNotice.value)
        first.vm.ttsNoticeShown()
        assertFalse(first.vm.ttsNotice.value)

        assertTrue(shown.value, "persisted")

        val second = setup(voice = unavailable, noticeShown = shown)
        advanceTimeBy(NavigateViewModel.TTS_GRACE_MS + 1)
        runCurrent()
        assertFalse(second.vm.ttsNotice.value)
    }

    @Test
    fun aTtsNoticeShownInAnEarlierRunIsNotShownAgain() = runTest(dispatcher) {
        val unavailable = object : VoiceOutput by RecordingVoiceOutput() {
            override val available = MutableStateFlow(false)
        }
        val s = setup(voice = unavailable, noticeShown = MutableStateFlow(true))
        advanceTimeBy(NavigateViewModel.TTS_GRACE_MS + 1)
        runCurrent()

        assertFalse(s.vm.ttsNotice.value)
    }

    @Test
    fun ttsThatBecomesAvailableInTimeIsNotNoticed() = runTest(dispatcher) {
        val availability = MutableStateFlow(false)
        val lateVoice = object : VoiceOutput by RecordingVoiceOutput() {
            override val available = availability
        }
        val s = setup(voice = lateVoice)
        advanceTimeBy(1_000)
        availability.value = true
        advanceTimeBy(NavigateViewModel.TTS_GRACE_MS)
        runCurrent()

        assertFalse(s.vm.ttsNotice.value)
    }
}
