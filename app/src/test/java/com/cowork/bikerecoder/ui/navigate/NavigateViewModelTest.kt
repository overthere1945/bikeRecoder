package com.cowork.bikerecoder.ui.navigate

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.recording.SegmentTotals
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
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideControls
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.ScreenNotice
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

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

    /** The recording commands the view model sent. */
    private val rideCommands = mutableListOf<String>()
    private val rideControls = object : RideControls {
        override fun pause() {
            rideCommands += "pause"
        }

        override fun resume() {
            rideCommands += "resume"
        }

        override fun setAutoPause(enabled: Boolean) {
            rideCommands += "autoPause $enabled"
        }

        override suspend fun stopRecording(end: RecordingEnd) {
            rideCommands += "stop $end"
        }

        override fun announce(text: String) {
            announced += text
        }

        override fun noticeShown(id: Long) {
            rideCommands += "noticeShown $id"
        }
    }

    /** What the view model announced through the recording (voice and on-screen notice). */
    private val announced = mutableListOf<String>()

    /** The recording's on-screen notice ([com.cowork.bikerecoder.ride.RideController.notice]). */
    private val rideNotice = MutableStateFlow<ScreenNotice?>(null)

    /** The recording the view model sees, and how often its summary was dismissed. */
    private val rideUi = MutableStateFlow<RideUiState>(RideUiState.Idle)
    private var summaryDismissals = 0

    private fun TestScope.setup(
        args: Map<String, Any?> = emptyMap(),
        voice: VoiceOutput = RecordingVoiceOutput(),
        noticeShown: MutableStateFlow<Boolean> = MutableStateFlow(false),
        serviceStatus: com.cowork.bikerecoder.nav.GuidanceServiceStatus = com.cowork.bikerecoder.nav.GuidanceServiceStatus(),
    ): Setup {
        val tripManager = TripManager(store, offline, clock = { testScheduler.currentTime })
        val controller = NavigationController(
            AssetRouter(listOf(fixture("route_follow.geojson"))), tripManager, store, offline, voice,
            { ScriptedLocationSource(gpxFixture("scenario_waypoint.gpx")) }, flowOf(true), backgroundScope,
            clock = { testScheduler.currentTime }, log = com.cowork.bikerecoder.nav.RecordingLog(),
        )
        val vm = NavigateViewModel(
            controller, voice, flowOf(true), SavedStateHandle(args), noticeShown, { noticeShown.value = true },
            serviceWarning = serviceStatus.warning,
            rideUi = rideUi,
            dismissRideSummary = {
                summaryDismissals++
                rideUi.value = RideUiState.Idle
            },
            rideControls = rideControls,
            rideNotice = rideNotice,
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
    fun aFailedForegroundServiceIsShownAsAScreenOffWarning() = runTest(dispatcher) {
        val status = com.cowork.bikerecoder.nav.GuidanceServiceStatus()
        val s = setup(serviceStatus = status)
        assertNull(s.vm.serviceWarning.value)

        status.foregroundFailed()

        assertEquals("화면이 꺼지면 안내가 멈출 수 있습니다", s.vm.serviceWarning.value)
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

    private fun recordingOf(tripId: Long) = RideUiState.Active(
        tripId = tripId,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = RecordingState.Recording,
        stats = RideStats.ZERO.copy(distanceM = 1_200.0, movingTimeMs = 240_000),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private fun summaryOf(tripId: Long, error: String? = null) = RideUiState.Finished(
        segment = RideSegment(1, tripId, startedAt = 0, endedAt = 60_000, LocalDate.of(2026, 10, 12), SegmentTotals.ZERO),
        track = emptyList(),
        error = error,
    )

    @Test
    fun endGuidanceOnlyKeepsTheTripAndGoesIdle() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        rideUi.value = recordingOf(id)

        s.vm.onEndChoice(EndAction.END_GUIDANCE_ONLY)
        runCurrent()

        assertEquals(NavUiState.Idle, s.vm.ui.value)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(id).status)
        assertEquals(0, summaryDismissals, "the recording goes on")
    }

    @Test
    fun cancelGuidanceEndsGuidanceOnlyAndAnnouncesOnce() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        rideUi.value = recordingOf(id)

        s.vm.cancelGuidance()
        runCurrent()

        assertEquals(NavUiState.Idle, s.vm.ui.value)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(id).status)
        assertEquals(listOf("안내를 마칩니다. 기록은 계속됩니다"), announced)
        assertEquals(0, summaryDismissals, "the recording goes on")
    }

    @Test
    fun endGuidanceOnlyFromTheDialogAnnouncesToo() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        rideUi.value = recordingOf(id)

        s.vm.onEndChoice(EndAction.END_GUIDANCE_ONLY)
        runCurrent()

        assertEquals(listOf("안내를 마칩니다. 기록은 계속됩니다"), announced)
    }

    @Test
    fun guidanceScreenTakesNoticesOnlyWhileGuiding() = runTest(dispatcher) {
        val s = setup()
        val notice = ScreenNotice(1, "새 구간으로 기록을 시작합니다")
        rideNotice.value = notice
        runCurrent()
        assertNull(s.vm.noticeForScreen.value, "no guidance: the record screen's")

        val id = s.runningTrip(TripType.SINGLE_DAY)
        rideUi.value = recordingOf(id)
        runCurrent()
        assertEquals(notice, s.vm.noticeForScreen.value)

        s.vm.noticeShown(1)
        assertEquals(listOf("noticeShown 1"), rideCommands)

        s.vm.close()
        runCurrent()
        assertNull(s.vm.noticeForScreen.value, "the guidance ended: the record screen shows it")
    }

    @Test
    fun afterTheGuidanceEndsARecordingGoesToTheRecordScreen() {
        assertTrue(goesToRecordAfterGuidance(recordingOf(1)))
        assertTrue(goesToRecordAfterGuidance(RideUiState.Starting(1)))
        assertFalse(goesToRecordAfterGuidance(RideUiState.Idle))
        assertFalse(goesToRecordAfterGuidance(summaryOf(1)))
    }

    @Test
    fun recordingFlagFollowsRideUi() = runTest(dispatcher) {
        val s = setup()
        runCurrent()
        assertFalse(s.vm.recording.value)

        rideUi.value = RideUiState.Starting(1)
        runCurrent()
        assertTrue(s.vm.recording.value, "a starting recording goes on after the guidance ends")

        rideUi.value = recordingOf(1)
        runCurrent()
        assertTrue(s.vm.recording.value)

        rideUi.value = summaryOf(1)
        runCurrent()
        assertFalse(s.vm.recording.value)
    }

    @Test
    fun theFinishedScreenWaitsForTheRecordingOfItsTripToStop() {
        val arrived = NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED)
        val stopped = NavUiState.Finished(1, TripType.MULTI_DAY, FinishReason.STOPPED_TODAY)

        assertEquals(FinishedView.Waiting, finishedView(arrived, recordingOf(1)))
        assertEquals(FinishedView.Waiting, finishedView(arrived, RideUiState.Starting(1)))
        // A stop for today must not close before its summary arrives.
        assertEquals(FinishedView.Waiting, finishedView(stopped, recordingOf(1)))
        // A recording of another trip is not stopped by this guidance: nothing to wait for.
        assertEquals(FinishedView.Done(summary = null, completed = true, canConvert = true), finishedView(arrived, recordingOf(2)))
    }

    @Test
    fun theSegmentSummaryIsShownAboveTheTripCompletion() {
        val summary = summaryOf(1)

        assertEquals(
            FinishedView.Done(summary, completed = true, canConvert = true),
            finishedView(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED), summary),
        )
        assertEquals(
            FinishedView.Done(summary, completed = true, canConvert = false),
            finishedView(NavUiState.Finished(1, TripType.MULTI_DAY, FinishReason.COMPLETED), summary),
        )
        // Not recording: V1.0.0.
        assertEquals(
            FinishedView.Done(summary = null, completed = true, canConvert = true),
            finishedView(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.COMPLETED), RideUiState.Idle),
        )
        // Another trip's summary is not this guidance's.
        assertEquals(
            FinishedView.Done(summary = null, completed = true, canConvert = true),
            finishedView(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.COMPLETED), summaryOf(2)),
        )
    }

    @Test
    fun stoppedTodayClosesAtOnceUnlessThereIsASummary() {
        val stopped = NavUiState.Finished(1, TripType.MULTI_DAY, FinishReason.STOPPED_TODAY)
        val summary = summaryOf(1)

        assertEquals(FinishedView.Close, finishedView(stopped, RideUiState.Idle))
        assertEquals(FinishedView.Done(summary, completed = false, canConvert = false), finishedView(stopped, summary))
    }

    @Test
    fun aTripWriteErrorIsShownInsteadOfSuccess() {
        val failed = NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED, error = "저장 실패")

        assertEquals(FinishedView.Error("저장 실패"), finishedView(failed, summaryOf(1)))
        assertEquals(FinishedView.Waiting, finishedView(failed, recordingOf(1)))
    }

    @Test
    fun confirmingTheFinishedScreenDismissesTheSummaryAndCloses() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = summaryOf(id)
        runCurrent()

        s.vm.confirmFinished()

        assertEquals(1, summaryDismissals)
        assertEquals(RideUiState.Idle, rideUi.value)
        assertEquals(NavUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun confirmingWithoutASummaryDismissesNothing() = runTest(dispatcher) {
        val s = setup()
        s.runningTrip(TripType.SINGLE_DAY)
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = RideUiState.Failed(9, "기록을 시작하지 못했습니다")
        runCurrent()

        s.vm.confirmFinished()

        assertEquals(0, summaryDismissals, "only a summary is dismissed here")
        assertEquals(NavUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun convertingToMultiDayDismissesTheSummaryItWasShownWith() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = summaryOf(id)
        runCurrent()

        s.vm.convertToMultiDay()
        runCurrent()

        assertEquals(NavUiState.Idle, s.vm.ui.value)
        assertEquals(TripType.MULTI_DAY, store.trips.getValue(id).type)
        assertEquals(1, summaryDismissals)
    }

    @Test
    fun backOnTheErrorViewLeavesTheSummaryForTheMainScreen() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        store.failUpdate = { it.status == TripStatus.COMPLETED }
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = summaryOf(id)
        runCurrent()
        assertInstanceOf(FinishedView.Error::class.java, finishedView(s.vm.ui.value as NavUiState.Finished, rideUi.value))

        s.vm.confirmFinished() // back on the error view = its [닫기]

        assertEquals(0, summaryDismissals, "the summary was never on screen")
        assertEquals(summaryOf(id), rideUi.value)
        assertEquals(NavUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun anotherTripsSummaryIsNotDismissedByConfirming() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = summaryOf(id + 100)
        runCurrent()

        s.vm.confirmFinished()

        assertEquals(0, summaryDismissals)
        assertEquals(NavUiState.Idle, s.vm.ui.value)
    }

    @Test
    fun anotherTripsSummaryIsNotDismissedByConverting() = runTest(dispatcher) {
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        s.vm.onEndChoice(EndAction.COMPLETE_TRIP)
        runCurrent()
        rideUi.value = summaryOf(id + 100)
        runCurrent()

        s.vm.convertToMultiDay()
        runCurrent()

        assertEquals(NavUiState.Idle, s.vm.ui.value)
        assertEquals(0, summaryDismissals)
    }

    @Test
    fun aRecordSaveProblemOfThePreviousTripIsNoticedOnce() = runTest(dispatcher) {
        val s = setup(args = mapOf(NavigateViewModel.ARG_TRIP to 7L, NavigateViewModel.ARG_RECORD_NOTICE to true))

        assertEquals("이전 기록 저장에 문제가 있습니다", s.vm.takeRecordNotice())
        assertNull(s.vm.takeRecordNotice())
        assertNull(setup(args = mapOf(NavigateViewModel.ARG_TRIP to 7L)).vm.takeRecordNotice())
    }

    @Test
    fun theRecordControlsOfTheNumbersPageCommandTheRecording() = runTest(dispatcher) {
        // Ruling F2: pause / resume / the auto-pause chip / [기록 끝내기] while guiding.
        val s = setup()
        val id = s.runningTrip(TripType.SINGLE_DAY)
        rideUi.value = recordingOf(id)

        s.vm.pauseRecording()
        s.vm.resumeRecording()
        s.vm.setRecordingAutoPause(false)
        s.vm.endRecording()
        runCurrent()

        assertEquals(listOf("pause", "resume", "autoPause false", "stop SEGMENT_ONLY"), rideCommands)
        assertInstanceOf(NavUiState.Active::class.java, s.vm.ui.value, "the guidance goes on")
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(id).status)
    }

    @Test
    fun aRecordingThatCouldNotStartForTheGuidedTripIsShown() {
        val failed = RideUiState.Failed(1, "기록을 시작하지 못했습니다")

        assertEquals("기록을 시작하지 못했습니다", guidedRideFailure(1, failed))
        assertNull(guidedRideFailure(2, failed), "another trip's failure is not this guidance's")
        assertNull(guidedRideFailure(1, recordingOf(1)))
        assertNull(guidedRideFailure(1, RideUiState.Idle))
    }

    @Test
    fun theRideFailureBannerIsClosedWithoutTouchingAnythingElse() = runTest(dispatcher) {
        val s = setup()
        rideUi.value = RideUiState.Failed(1, "기록을 시작하지 못했습니다")

        s.vm.dismissRideFailure()
        assertEquals(1, summaryDismissals)

        rideUi.value = summaryOf(1)
        s.vm.dismissRideFailure()
        assertEquals(1, summaryDismissals, "a summary is not a failure")
    }
}
