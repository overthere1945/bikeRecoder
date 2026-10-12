package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.recordingTripId
import com.cowork.bikerecoder.ui.plan.PlannedStop
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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripStartViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTripStore()
    private val offline = RecordingOfflineMaps()
    private val tripManager = TripManager(store, offline, clock = { 1_000L })

    private val a = PlannedStop(1, "남산", GeoPoint(37.55, 126.99))
    private val b = PlannedStop(2, "여의도", GeoPoint(37.52, 126.92))
    private val plan = StartPlan(RouteProfile.BALANCED, listOf(a, b))

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = TripStartViewModel(tripManager, store).also { runCurrent() }

    private suspend fun existingTrip(type: TripType): Long = tripManager.startTrip(
        type,
        RouteProfile.SHORTEST,
        listOf(a, b).toTripStops(),
    ).id

    @Test
    fun plannedStopsBecomeOrderedTripStopsEndingInTheDestination() {
        val stops = listOf(a, b).toTripStops()

        assertEquals(listOf(0, 1), stops.map { it.order })
        assertEquals(listOf(false, true), stops.map { it.isDestination })
        assertEquals(listOf("남산", "여의도"), stops.map { it.name })
        assertEquals(listOf(a.point, b.point), stops.map { it.point })
        assertTrue(stops.all { it.visitedAt == null && it.id == 0L })
    }

    @Test
    fun noActiveTripAsksTypeAndStartsTheChosenType() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.AskType, vm.state.value)

        vm.chooseType(TripType.MULTI_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        val trip = store.trips.getValue(ready.tripId)
        assertEquals(TripType.MULTI_DAY, trip.type)
        assertEquals(RouteProfile.BALANCED, trip.profile)
        assertEquals(listOf("남산", "여의도"), store.stops(ready.tripId).map { it.name })

        vm.consumeReady()
        assertEquals(TripStartState.Idle, vm.state.value)
    }

    @Test
    fun activeSingleDayTripAsksTypeAndStartingCompletesIt() = runTest(dispatcher) {
        val old = existingTrip(TripType.SINGLE_DAY)
        val vm = viewModel()

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.AskType, vm.state.value)
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        assertEquals(TripStatus.COMPLETED, store.trips.getValue(old).status)
        val ready = vm.state.value as TripStartState.Ready
        assertEquals(TripType.SINGLE_DAY, store.trips.getValue(ready.tripId).type)
    }

    @Test
    fun activeMultiDayTripAsksToContinueByDestinationName() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        val vm = viewModel()

        vm.onStartPressed(plan)
        runCurrent()

        val ask = vm.state.value as TripStartState.AskContinue
        assertEquals(old, ask.trip.id)
        assertEquals("여의도", ask.destinationName)
        assertEquals("여의도 여행을 이어서 진행할까요?", continueQuestion(ask.destinationName))
    }

    @Test
    fun continueWithTheSamePlanKeepsTheTripsStops() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        val idsBefore = store.stops(old).map { it.id }
        val vm = viewModel()

        vm.onStartPressed(plan.copy(profile = RouteProfile.SHORTEST))
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(idsBefore, store.stops(old).map { it.id })
        assertTrue(offline.deleted.isEmpty(), "changeStops not called")
    }

    @Test
    fun continueWithAChangedPlanReplacesTheStops() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        val c = PlannedStop(3, "잠실", GeoPoint(37.51, 127.10))
        val vm = viewModel()

        vm.onStartPressed(StartPlan(RouteProfile.SHORTEST, listOf(a, c)))
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(listOf("남산", "잠실"), store.stops(old).map { it.name })
        assertEquals(listOf(old), offline.deleted)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(old).status)
    }

    @Test
    fun continueUsesTheProfileChosenOnThePlanScreen() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY) // SHORTEST
        val vm = viewModel()

        vm.onStartPressed(plan) // BALANCED, same stops
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(RouteProfile.BALANCED, store.trips.getValue(old).profile)
    }

    @Test
    fun continueComparesAgainstTheUnvisitedStopsOnly() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        store.markVisited(store.stops(old).first().id, 500L)
        val vm = viewModel()

        // The plan from the main banner holds only what is left: unchanged.
        vm.onStartPressed(StartPlan(RouteProfile.SHORTEST, listOf(b)))
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertTrue(offline.deleted.isEmpty())
        assertEquals(2, store.stops(old).size)
    }

    @Test
    fun startNewAsksTypeThenCompletesThePreviousTrip() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        val vm = viewModel()

        vm.onStartPressed(plan)
        runCurrent()
        vm.startNew()
        assertEquals(TripStartState.AskType, vm.state.value)
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        assertEquals(TripStatus.COMPLETED, store.trips.getValue(old).status)
        val ready = vm.state.value as TripStartState.Ready
        assertTrue(ready.tripId != old)
    }

    @Test
    fun dismissingADialogStartsNothing() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onStartPressed(plan)
        runCurrent()
        vm.dismiss()
        runCurrent()

        assertEquals(TripStartState.Idle, vm.state.value)
        assertTrue(store.trips.isEmpty())
    }

    @Test
    fun aFailingStoreShowsAnError() = runTest(dispatcher) {
        val broken = object : com.cowork.bikerecoder.core.trip.TripStore by store {
            override suspend fun activeTrip() = error("disk full")
        }
        val log = com.cowork.bikerecoder.nav.RecordingLog()
        val vm = TripStartViewModel(TripManager(broken, offline, clock = { 1L }), broken, log)

        vm.onStartPressed(plan)
        runCurrent()

        assertEquals(TripStartState.Error(TripStartViewModel.MSG_FAILED), vm.state.value)
        assertEquals(1, log.messages.size, "the failure is logged")
        vm.dismiss()
        assertEquals(TripStartState.Idle, vm.state.value)
    }

    @Test
    fun recordNewTripHasNoStops() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onRecordPressed()
        runCurrent()
        assertEquals(TripStartState.AskType, vm.state.value)
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        val trip = store.trips.getValue(ready.tripId)
        assertEquals(TripType.SINGLE_DAY, trip.type)
        assertEquals(RouteProfile.CYCLEWAY_FIRST, trip.profile)
        assertTrue(store.stops(ready.tripId).isEmpty())
    }

    @Test
    fun recordNewTripTakesTheDefaultProfile() = runTest(dispatcher) {
        val vm = TripStartViewModel(tripManager, store, defaultProfile = { RouteProfile.SHORTEST })

        vm.onRecordPressed()
        runCurrent()
        vm.chooseType(TripType.MULTI_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        assertEquals(RouteProfile.SHORTEST, store.trips.getValue(ready.tripId).profile)
    }

    @Test
    fun recordContinueKeepsTheTripsStopsAndProfile() = runTest(dispatcher) {
        val old = tripManager.startTrip(TripType.MULTI_DAY, RouteProfile.BALANCED, listOf(a, b).toTripStops()).id
        val vm = viewModel() // default profile CYCLEWAY_FIRST

        vm.onRecordPressed()
        runCurrent()
        assertInstanceOf(TripStartState.AskContinue::class.java, vm.state.value)
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(listOf("남산", "여의도"), store.stops(old).map { it.name })
        assertEquals(RouteProfile.BALANCED, store.trips.getValue(old).profile)
        assertTrue(offline.deleted.isEmpty())
    }

    @Test
    fun aPlanStartAfterARecordStartUsesThePlanAgain() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onRecordPressed()
        runCurrent()
        vm.dismiss()

        vm.onStartPressed(plan)
        runCurrent()
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        assertEquals(listOf("남산", "여의도"), store.stops(ready.tripId).map { it.name })
    }

    @Test
    fun planStartWithEmptyStopsStillDoesNothing() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onStartPressed(StartPlan(RouteProfile.BALANCED, emptyList()))
        runCurrent()

        assertEquals(TripStartState.Idle, vm.state.value)
    }

    /** A real recording controller on the same fakes (no fixes needed: only starting and stopping matter here). */
    private inner class Recording(scope: TestScope, settings: AppSettings = AppSettings()) {
        val clock = { scope.testScheduler.currentTime }
        val rideStore = FakeRideStore(store, clock)
        private val log = RecordingLog()
        val controller = RideController(
            rideStore = rideStore,
            tripStore = store,
            tripManager = tripManager,
            navigation = NavigationController(
                AssetRouter(emptyList()), tripManager, store, offline, RecordingVoiceOutput(),
                { ScriptedLocationSource(emptyList()) }, flowOf(true), scope.backgroundScope, clock = clock, log = log,
            ),
            voice = RecordingVoiceOutput(),
            location = ScriptedLocationSource(emptyList()),
            pressure = null,
            settings = MutableStateFlow(settings),
            scope = scope.backgroundScope,
            clock = clock,
            log = log,
        )

        /** The trip's status when the recording was ended for another trip (null: never ended). */
        var statusWhenEnded: TripStatus? = null

        fun viewModel(recordedTrip: Long) = TripStartViewModel(
            tripManager,
            store,
            recordingTrip = { controller.ui.value.recordingTripId },
            endRecording = {
                controller.endForAnotherTrip().also { statusWhenEnded = store.trips.getValue(recordedTrip).status }
            },
        )

        suspend fun record(tripId: Long) {
            val start = controller.startRecording(tripId)
            assertNotNull(start, "started")
            start!!.join()
            assertInstanceOf(RideUiState.Active::class.java, controller.ui.value)
        }
    }

    @Test
    fun guidingANewTripFirstEndsTheRunningRecordingAsASegmentWithoutSummary() = runTest(dispatcher) {
        val r = Recording(this)
        val old = existingTrip(TripType.SINGLE_DAY)
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.AskType, vm.state.value)
        assertEquals(null, r.statusWhenEnded, "asking ends nothing")
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        assertNotEquals(old, ready.tripId)
        // The segment was closed while the old trip was still active, and only then was the old trip completed.
        assertEquals(TripStatus.ACTIVE, r.statusWhenEnded)
        assertNotNull(r.rideStore.segments(old).single().endedAt)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(old).status)
        assertEquals(RideUiState.Idle, r.controller.ui.value, "no summary is left behind")
    }

    @Test
    fun startNewOnARecordedMultiDayTripEndsItsRecordingFirst() = runTest(dispatcher) {
        val r = Recording(this)
        val old = existingTrip(TripType.MULTI_DAY)
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        assertInstanceOf(TripStartState.AskContinue::class.java, vm.state.value)
        vm.startNew()
        vm.chooseType(TripType.MULTI_DAY)
        runCurrent()

        assertInstanceOf(TripStartState.Ready::class.java, vm.state.value)
        assertEquals(TripStatus.ACTIVE, r.statusWhenEnded)
        assertNotNull(r.rideStore.segments(old).single().endedAt)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(old).status)
        assertEquals(RideUiState.Idle, r.controller.ui.value)
    }

    @Test
    fun continuingTheRecordedTripKeepsItsRecording() = runTest(dispatcher) {
        val r = Recording(this)
        val old = existingTrip(TripType.MULTI_DAY)
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(null, r.statusWhenEnded)
        assertInstanceOf(RideUiState.Active::class.java, r.controller.ui.value)
        assertEquals(null, r.rideStore.segments(old).single().endedAt)
    }

    @Test
    fun aRecordingThatCouldNotBeSavedIsNoticedWithTheStart() = runTest(dispatcher) {
        val r = Recording(this)
        val old = existingTrip(TripType.SINGLE_DAY)
        r.record(old)
        r.rideStore.failAppend = true
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        assertEquals(RideController.MSG_SAVE_PROBLEM, ready.recordNotice)
        assertEquals(TripStatus.COMPLETED, store.trips.getValue(old).status)
    }

    @Test
    fun aCleanStopHasNoNotice() = runTest(dispatcher) {
        val r = Recording(this)
        val old = existingTrip(TripType.SINGLE_DAY)
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        assertNull((vm.state.value as TripStartState.Ready).recordNotice)
    }

    @Test
    fun withoutAutoStartTheEndedRecordingsSummaryStays() = runTest(dispatcher) {
        val r = Recording(this, AppSettings(autoStartRecordingWithNavigation = false))
        val old = existingTrip(TripType.SINGLE_DAY)
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        assertInstanceOf(TripStartState.Ready::class.java, vm.state.value)
        assertEquals(TripStatus.ACTIVE, r.statusWhenEnded)
        // Nothing will record the new trip, so the summary is left for the main screen.
        val finished = r.controller.ui.value as RideUiState.Finished
        assertEquals(old, finished.segment.tripId)
    }

    @Test
    fun aPendingSummaryIsShownBeforeAPlanStart() = runTest(dispatcher) {
        var pending = true
        val vm = TripStartViewModel(tripManager, store, pendingSummary = { pending }).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.ShowSummary, vm.state.value)
        assertTrue(store.trips.isEmpty(), "nothing started")

        vm.summaryShown()
        assertEquals(TripStartState.Idle, vm.state.value)

        pending = false
        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.AskType, vm.state.value)
    }
}
