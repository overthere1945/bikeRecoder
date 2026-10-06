package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.ui.plan.PlannedStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

        vm.onStartPressed(plan)
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

        vm.onStartPressed(StartPlan(RouteProfile.BALANCED, listOf(a, c)))
        runCurrent()
        vm.continueTrip()
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(listOf("남산", "잠실"), store.stops(old).map { it.name })
        assertEquals(listOf(old), offline.deleted)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(old).status)
    }

    @Test
    fun continueComparesAgainstTheUnvisitedStopsOnly() = runTest(dispatcher) {
        val old = existingTrip(TripType.MULTI_DAY)
        store.markVisited(store.stops(old).first().id, 500L)
        val vm = viewModel()

        // The plan from the main banner holds only what is left: unchanged.
        vm.onStartPressed(StartPlan(RouteProfile.BALANCED, listOf(b)))
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
}
