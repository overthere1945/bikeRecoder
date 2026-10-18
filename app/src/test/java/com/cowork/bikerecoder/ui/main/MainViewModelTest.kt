package com.cowork.bikerecoder.ui.main

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTripStore()
    private var now = 1_000L
    private val tripManager = TripManager(store, RecordingOfflineMaps(), clock = { now })
    private val guidance = MutableStateFlow<NavUiState>(NavUiState.Idle)
    private val ride = MutableStateFlow<RideUiState>(RideUiState.Idle)
    private var openSegment: RideSegment? = null
    private var closeCalls = 0

    private val noSearch = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = SearchResult.Ok(emptyList())
        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = SearchResult.Ok(null)

        override suspend fun nearby(point: GeoPoint): SearchResult<List<Place>> = SearchResult.Ok(emptyList())
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = MainViewModel(
        tripManager, store, noSearch, guidance,
        ride = ride,
        interrupted = { openSegment },
        closeInterrupted = {
            closeCalls++
            openSegment = null
        },
    ).also { runCurrent() }

    private fun recording(tripId: Long, distanceM: Double, movingMs: Long) = RideUiState.Active(
        tripId = tripId,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = RecordingState.Recording,
        stats = RideStats.ZERO.copy(distanceM = distanceM, movingTimeMs = movingMs, elapsedMs = movingMs),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private fun segment(id: Long, tripId: Long) =
        RideSegment(id, tripId, startedAt = 0, endedAt = null, LocalDate.of(2026, 10, 12), SegmentTotals.ZERO)

    private fun stop(name: String, isDestination: Boolean) =
        TripStop(0, 0, 0, name, GeoPoint(37.5, 127.0), isDestination, visitedAt = null)

    private suspend fun multiDayTrip(): Long = tripManager.startTrip(
        TripType.MULTI_DAY,
        RouteProfile.BALANCED,
        listOf(stop("남산", isDestination = false), stop("여의도", isDestination = true)),
    ).id

    @Test
    fun aMultiDayTripWithStopsLeftOffersToResumeGuidance() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        store.markVisited(store.stops(tripId).first().id, 500L)

        val banner = viewModel().state.value.banner!!

        assertEquals(TripBanner(tripId, "여의도", 1, BannerAction.RESUME), banner)
        assertEquals("🚩 여의도 · 여러 날 1일차", banner.text)
        assertEquals("이어서 안내", banner.action.label)
    }

    @Test
    fun aMultiDayTripWithoutStopsLeftAsksForTheNextDestination() = runTest(dispatcher) {
        // E.g. a single-day trip converted to multi-day after arriving: its destination is visited.
        val tripId = multiDayTrip()
        store.stops(tripId).forEach { store.markVisited(it.id, 500L) }

        val banner = viewModel().state.value.banner!!

        assertEquals(TripBanner(tripId, "여의도", 1, BannerAction.NEXT_DESTINATION), banner)
        assertEquals("🚩 여의도 · 여러 날 1일차", banner.text)
        assertEquals("다음 목적지 정하기", banner.action.label)
    }

    @Test
    fun runningGuidanceShowsTheGuidanceBannerForAnyTripType() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.state.value.guiding)

        guidance.value = NavUiState.Starting(tripId = 3)
        runCurrent()
        assertTrue(vm.state.value.guiding)

        guidance.value = NavUiState.Finished(3, TripType.SINGLE_DAY, com.cowork.bikerecoder.nav.FinishReason.ARRIVED)
        runCurrent()
        assertFalse(vm.state.value.guiding)
    }

    @Test
    fun whileGuidingTheTripBannerGivesWayToTheGuidanceBanner() = runTest(dispatcher) {
        multiDayTrip()
        val vm = viewModel()
        assertTrue(vm.state.value.banner != null)

        guidance.value = NavUiState.Starting(tripId = 1)
        runCurrent()

        assertTrue(vm.state.value.guiding)
        assertNull(vm.state.value.visibleBanner)
    }

    @Test
    fun ridingBannerShowsDistanceAndMovingTime() = runTest(dispatcher) {
        val vm = viewModel()
        assertNull(vm.state.value.riding)

        ride.value = recording(tripId = 7, distanceM = 3_010.0, movingMs = 600_000)
        runCurrent()

        assertEquals(RidingBanner("● 주행 중 · 3.01km · 0:10:00", RideTarget.RECORD), vm.state.value.riding)
        assertTrue(vm.state.value.recording, "the record button is hidden while recording")

        // Recording with guidance: [보기] opens the guidance screen.
        guidance.value = NavUiState.Starting(tripId = 7)
        runCurrent()
        assertEquals(RidingBanner("● 주행 중 · 3.01km · 0:10:00", RideTarget.NAVIGATE), vm.state.value.riding)
    }

    @Test
    fun guidanceOnlyBanner() = runTest(dispatcher) {
        multiDayTrip()
        val vm = viewModel()

        guidance.value = NavUiState.Starting(tripId = 1)
        runCurrent()

        assertEquals(RidingBanner("● 주행 중 · 안내 중", RideTarget.NAVIGATE), vm.state.value.riding)
        assertNull(vm.state.value.visibleBanner)
        assertFalse(vm.state.value.recording, "recording can still be started for the guided trip")
    }

    @Test
    fun theTripBannerGivesWayToTheRidingBanner() = runTest(dispatcher) {
        multiDayTrip()
        val vm = viewModel()
        assertTrue(vm.state.value.visibleBanner != null)

        ride.value = recording(tripId = 1, distanceM = 0.0, movingMs = 0)
        runCurrent()

        assertNull(vm.state.value.visibleBanner)
    }

    @Test
    fun aMultiDayRecordingTripWithoutStopsIsNamedRideRecord() = runTest(dispatcher) {
        val tripId = tripManager.startTrip(TripType.MULTI_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id

        val banner = viewModel().state.value.banner!!

        assertEquals(TripBanner(tripId, "주행 기록", 1, BannerAction.NEXT_DESTINATION), banner)
    }

    @Test
    fun interruptedRideIsOfferedOnlyWhenIdle() = runTest(dispatcher) {
        openSegment = segment(id = 4, tripId = 9)
        val vm = viewModel()

        assertEquals(InterruptedRide(segmentId = 4, tripId = 9), vm.state.value.interrupted)
        assertFalse(vm.state.value.canStartRecording, "[기록 시작] waits for the answer")

        vm.closeInterruptedRide()
        runCurrent()

        assertEquals(1, closeCalls)
        assertNull(vm.state.value.interrupted)
        assertTrue(vm.state.value.canStartRecording)

        // Not while a recording starts or runs (it closes the leftover segment itself).
        openSegment = segment(id = 5, tripId = 9)
        ride.value = RideUiState.Starting(9)
        assertNull(viewModel().state.value.interrupted)
        // Nor while guiding (the recording would belong to another trip than the guided one).
        ride.value = RideUiState.Idle
        guidance.value = NavUiState.Starting(tripId = 3)
        assertNull(viewModel().state.value.interrupted)
    }

    @Test
    fun continuingAnInterruptedRideOnlyClosesTheQuestion() = runTest(dispatcher) {
        openSegment = segment(id = 4, tripId = 9)
        val vm = viewModel()

        vm.dismissInterrupted()

        assertNull(vm.state.value.interrupted)
        assertEquals(0, closeCalls)
        assertTrue(vm.state.value.canStartRecording)
    }

    @Test
    fun recordingCannotStartBeforeTheInterruptedCheckAnswered() = runTest(dispatcher) {
        val vm = MainViewModel(tripManager, store, noSearch, guidance, ride = ride, interrupted = { openSegment })
        // Nothing ran yet: the check has not answered.
        assertFalse(vm.state.value.canStartRecording)
        runCurrent()
        assertTrue(vm.state.value.canStartRecording)
    }

    @Test
    fun aSummaryLeftByGuidanceIsPendingAndBlocksANewRecording() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.state.value.pendingSummary)
        assertTrue(vm.state.value.canStartRecording)

        // Arrival stopped the recording: its summary waits to be seen.
        ride.value = RideUiState.Finished(segment(id = 4, tripId = 9).copy(endedAt = 60_000), emptyList(), error = null)
        runCurrent()
        assertTrue(vm.state.value.pendingSummary)
        assertFalse(vm.state.value.canStartRecording, "[기록 시작] must not replace the summary")

        ride.value = RideUiState.Idle // dismissed
        runCurrent()
        assertFalse(vm.state.value.pendingSummary)
        assertTrue(vm.state.value.canStartRecording)
    }

    private val fourDaysMs = 4L * 24 * 60 * 60 * 1000

    @Test
    fun aMultiDayTripIdleForDaysIsAskedAbout() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        now += fourDaysMs

        assertEquals(StaleTrip(tripId, "여의도"), viewModel().state.value.staleTrip)
    }

    @Test
    fun noStaleTripPromptWhileItIsRecorded() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        now += fourDaysMs
        ride.value = recording(tripId, distanceM = 100.0, movingMs = 20_000)

        assertNull(viewModel().state.value.staleTrip)
    }

    @Test
    fun noStaleTripPromptWhileGuiding() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        now += fourDaysMs
        guidance.value = NavUiState.Starting(tripId)

        assertNull(viewModel().state.value.staleTrip)
    }

    @Test
    fun aStaleTripPromptIsWithdrawnWhenARideStarts() = runTest(dispatcher) {
        val tripId = multiDayTrip()
        now += fourDaysMs
        val vm = viewModel()
        assertEquals(tripId, vm.state.value.staleTrip?.tripId)

        ride.value = RideUiState.Starting(tripId)
        runCurrent()

        assertNull(vm.state.value.staleTrip, "[완료] must not complete the trip being recorded")
    }
}
