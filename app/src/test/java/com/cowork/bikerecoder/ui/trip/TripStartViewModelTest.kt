package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.data.AppSettings
import com.cowork.bikerecoder.data.GuidanceRecording
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.location.SharedLocationSource
import com.cowork.bikerecoder.nav.AssetRouter
import com.cowork.bikerecoder.nav.FakeTripStore
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationController
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.nav.RecordingOfflineMaps
import com.cowork.bikerecoder.nav.RecordingVoiceOutput
import com.cowork.bikerecoder.nav.ScriptedLocationSource
import com.cowork.bikerecoder.nav.fixture
import com.cowork.bikerecoder.nav.gpxFixture
import com.cowork.bikerecoder.ride.FakeRideStore
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideController
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.recordingTripId
import com.cowork.bikerecoder.ui.plan.PlannedStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.io.IOException
import java.time.LocalDate

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

    /**
     * A real recording controller on the same fakes. Without [ride] no fixes come (only starting, splitting and stopping
     * matter); with it both controllers follow the 2.37 km Z route of RideNavigationTest through one shared stream.
     */
    private inner class Recording(scope: TestScope, settings: AppSettings = AppSettings(), ride: Boolean = false) {
        val clock = { scope.testScheduler.currentTime }
        val rideStore = FakeRideStore(store, clock)

        /** While true, opening a segment fails (a recording start fails). */
        var failOpen = false
        private val log = RecordingLog()
        val voice = RecordingVoiceOutput()
        private val location: LocationSource = if (ride) {
            SharedLocationSource({ ScriptedLocationSource(gpxFixture("scenario_follow.gpx"), speedup = 1.0) }, scope.backgroundScope)
        } else {
            ScriptedLocationSource(emptyList())
        }
        val navigation = NavigationController(
            AssetRouter(if (ride) listOf(fixture("route_follow.geojson")) else emptyList()), tripManager, store, offline,
            RecordingVoiceOutput(), { location }, flowOf(true), scope.backgroundScope, clock = clock, log = log,
        )
        val controller = RideController(
            rideStore = object : RideStore by rideStore {
                override suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long {
                    if (failOpen) throw IOException("disk full")
                    return rideStore.openSegment(tripId, startedAt, localDate)
                }
            },
            tripStore = store,
            tripManager = tripManager,
            navigation = navigation,
            voice = voice,
            location = location,
            pressure = null,
            settings = MutableStateFlow(settings),
            scope = scope.backgroundScope,
            clock = clock,
            log = log,
        )

        val active: RideUiState.Active get() = controller.ui.value as RideUiState.Active

        /** The trip's status when the recording was ended for another trip (null: never ended). */
        var statusWhenEnded: TripStatus? = null

        fun viewModel(
            recordedTrip: Long,
            mode: GuidanceRecording = GuidanceRecording.ASK,
            closed: MutableList<Long> = mutableListOf(),
        ) = TripStartViewModel(
            tripManager,
            store,
            recordingTrip = { controller.ui.value.recordingTripId },
            endRecording = {
                controller.endForAnotherTrip().also { statusWhenEnded = store.trips.getValue(recordedTrip).status }
            },
            pendingSummary = { controller.ui.value is RideUiState.Finished },
            recordingFailure = { (controller.ui.value as? RideUiState.Failed)?.message },
            recordingMode = { mode },
            splitSegment = { controller.splitSegment() },
            closeGuidanceOf = { closed += it },
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
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
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
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
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
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
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
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
        r.rideStore.failAppend = true
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
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
        vm.chooseType(TripType.SINGLE_DAY)
        runCurrent()

        assertNull((vm.state.value as TripStartState.Ready).recordNotice)
    }

    @Test
    fun withoutAutoStartTheEndedRecordingsSummaryStays() = runTest(dispatcher) {
        val r = Recording(this, AppSettings(autoStartRecordingWithNavigation = false))
        val old = existingTrip(TripType.SINGLE_DAY)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        // Started while the question is open: the safety net (ruling U2) ends it.
        r.record(old)
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


    // ---- [안내 시작] while recording: the plan joins the recorded trip (spec §4.2, §4.3) ----

    /** A trip started by [● 기록 시작]: no stops. */
    private suspend fun recordedTrip(): Long =
        tripManager.startTrip(TripType.SINGLE_DAY, RouteProfile.CYCLEWAY_FIRST, emptyList()).id

    @Test
    fun planStartWhileRecordingAsksHowToRecord() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        assertEquals(TripStartState.AskRecording, vm.state.value)
        assertEquals("기록을 어떻게 할까요?", RECORDING_QUESTION)
        assertEquals(1, store.trips.size)
        assertTrue(store.stops(old).isEmpty())
        assertNull(r.rideStore.segments(old).single().endedAt)
    }

    @Test
    fun askCancelDoesNothing() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val closed = mutableListOf<Long>()
        val vm = r.viewModel(old, closed = closed).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.dismiss()
        runCurrent()

        assertEquals(TripStartState.Idle, vm.state.value)
        assertTrue(store.stops(old).isEmpty())
        assertNull(r.rideStore.segments(old).single().endedAt)
        assertEquals(1, store.trips.size)
        assertEquals(RouteProfile.CYCLEWAY_FIRST, store.trips.getValue(old).profile)
        assertTrue(closed.isEmpty())
        assertInstanceOf(RideUiState.Active::class.java, r.controller.ui.value)
    }

    @Test
    fun keepRecordingAttachesThePlanToTheRecordedTrip() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val segmentId = r.active.segmentId
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.keepRecording()
        runCurrent()

        assertEquals(TripStartState.Ready(old, null), vm.state.value)
        assertEquals(listOf("남산", "여의도"), store.stops(old).map { it.name })
        assertEquals(RouteProfile.BALANCED, store.trips.getValue(old).profile)
        assertEquals(1, store.trips.size)
        val segment = r.rideStore.segments(old).single()
        assertEquals(segmentId, segment.id)
        assertNull(segment.endedAt)
        assertEquals(segmentId, r.active.segmentId)
        assertTrue("새 구간으로 기록을 시작합니다" !in r.voice.spoken, r.voice.spoken.toString())
        assertEquals(null, r.statusWhenEnded, "the safety net was not used")
    }

    @Test
    fun splitRecordingOpensANewSegmentOfTheSameTrip() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        vm.splitRecording()
        runCurrent()

        assertEquals(TripStartState.Ready(old, null), vm.state.value)
        val segments = r.rideStore.segments(old)
        assertEquals(2, segments.size, segments.toString())
        assertNotNull(segments[0].endedAt)
        assertNull(segments[1].endedAt)
        assertEquals(segments[1].id, r.active.segmentId)
        assertEquals(1, store.trips.size)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(old).status)
        assertEquals(listOf("남산", "여의도"), store.stops(old).map { it.name })
        assertEquals(1, r.voice.spoken.count { it == "새 구간으로 기록을 시작합니다" }, r.voice.spoken.toString())
    }

    @Test
    fun continueSettingAttachesWithoutAsking() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old, GuidanceRecording.CONTINUE).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertNull(r.rideStore.segments(old).single().endedAt)
        assertEquals(listOf("남산", "여의도"), store.stops(old).map { it.name })
    }

    @Test
    fun splitSettingSplitsWithoutAsking() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old, GuidanceRecording.SPLIT).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(2, r.rideStore.segments(old).size)
    }

    @Test
    fun splitSaveProblemComesWithTheStart() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        r.rideStore.failAppend = true
        val vm = r.viewModel(old, GuidanceRecording.SPLIT).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        val ready = vm.state.value as TripStartState.Ready
        assertEquals(old, ready.tripId)
        assertEquals("기록 저장에 문제가 있습니다", ready.recordNotice)
    }

    @Test
    fun attachingClosesAGuidanceOfThatTripFirst() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val closed = mutableListOf<Long>()
        // The stops of the trip when its guidance was closed: closing comes before the plan is attached.
        var stopsWhenClosed: Int? = null
        val vm = TripStartViewModel(
            tripManager,
            store,
            recordingTrip = { r.controller.ui.value.recordingTripId },
            recordingMode = { GuidanceRecording.CONTINUE },
            closeGuidanceOf = {
                closed += it
                stopsWhenClosed = store.stopsByTrip[it].orEmpty().size
            },
        ).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        assertEquals(listOf(old), closed)
        assertEquals(0, stopsWhenClosed)
        assertEquals(TripStartState.Ready(old), vm.state.value)
    }

    @Test
    fun theSamePlanAgainLeavesTheStopsAndTheOfflineMaps() = runTest(dispatcher) {
        // Re-guiding a trip whose stops already are the plan (e.g. after "안내 취소 · 기록 계속"): no changeStops.
        val r = Recording(this)
        val old = existingTrip(TripType.SINGLE_DAY)
        val idsBefore = store.stops(old).map { it.id }
        r.record(old)
        val vm = r.viewModel(old, GuidanceRecording.CONTINUE).also { runCurrent() }

        vm.onStartPressed(plan.copy(profile = RouteProfile.SHORTEST)) // the trip's own profile
        runCurrent()

        assertEquals(TripStartState.Ready(old), vm.state.value)
        assertEquals(idsBefore, store.stops(old).map { it.id })
        assertTrue(offline.deleted.isEmpty(), "neither changeStops nor a profile change")
    }

    @Test
    fun answerAfterTheRecordingEndedFallsBackToTheUsualQuestions() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.AskRecording, vm.state.value)
        r.controller.stopRecording(RecordingEnd.SEGMENT_ONLY)
        r.controller.dismissSummary()
        vm.keepRecording()
        runCurrent()

        assertEquals(TripStartState.AskType, vm.state.value)
        assertTrue(store.stops(old).isEmpty(), "nothing was attached")
    }

    @Test
    fun aSplitWhoseNewSegmentCannotStartStartsNoGuidance() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        r.failOpen = true
        val vm = r.viewModel(old, GuidanceRecording.SPLIT).also { runCurrent() }

        vm.onStartPressed(plan)
        runCurrent()

        assertInstanceOf(RideUiState.Failed::class.java, r.controller.ui.value)
        assertEquals(TripStartState.Error("기록을 시작하지 못했습니다"), vm.state.value)
        vm.dismiss()
        assertEquals(TripStartState.Idle, vm.state.value)
    }

    @Test
    fun aStopDuringTheSplitShowsTheSummaryInsteadOfStarting() = runTest(dispatcher) {
        val r = Recording(this)
        val old = recordedTrip()
        r.record(old)
        val vm = r.viewModel(old, GuidanceRecording.SPLIT).also { runCurrent() }
        val gate = CompletableDeferred<Unit>()
        r.rideStore.closeGate = gate

        vm.onStartPressed(plan)
        runCurrent()
        assertEquals(TripStartState.Working, vm.state.value, "the split is closing the segment")
        val stop = backgroundScope.launch { r.controller.stopRecording(RecordingEnd.SEGMENT_ONLY) }
        runCurrent()
        gate.complete(Unit)
        stop.join()
        runCurrent()

        assertEquals(TripStartState.ShowSummary, vm.state.value)
        assertInstanceOf(RideUiState.Finished::class.java, r.controller.ui.value)
        assertEquals(1, r.rideStore.segments(old).size, "no new segment")
    }

    @Test
    fun theGuidanceStartedAfterASplitJoinsTheRecordingAndItsArrivalKeepsRecording() = runTest(dispatcher) {
        // Ruling U1 after a split: the guidance must begin only once the new segment records (the km source is
        // briefly null while splitting, and a guidance begun then would not belong to the recording).
        val r = Recording(this, ride = true)
        val old = recordedTrip()
        r.record(old)
        advanceTimeBy(20_000)
        runCurrent()
        val vm = r.viewModel(old, GuidanceRecording.SPLIT).also { runCurrent() }
        var rideWhenReady: RideUiState? = null
        // Like the plan screen, but at once: the guidance begins the moment the start is ready.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { state ->
                if (state is TripStartState.Ready) {
                    rideWhenReady = r.controller.ui.value
                    assertNotNull(r.navigation.kmSource, "the recording supplies the km announcements at the begin")
                    r.navigation.begin(state.tripId)
                    vm.consumeReady()
                }
            }
        }

        vm.onStartPressed(StartPlan(RouteProfile.BALANCED, listOf(PlannedStop(9, "도착", zDestination))))
        val notice = r.controller.notice.first { it?.text == "목적지에 도착했습니다" }!!
        runCurrent()

        val segments = r.rideStore.segments(old)
        assertEquals(2, segments.size, segments.toString())
        assertEquals(segments[1].id, (rideWhenReady as RideUiState.Active).segmentId, "the split was done before")
        assertEquals("목적지에 도착했습니다", notice.text)
        assertEquals(NavUiState.Idle, r.navigation.ui.value)
        assertInstanceOf(RideUiState.Active::class.java, r.controller.ui.value)
        assertEquals(segments[1].id, r.active.segmentId)
        assertNull(r.rideStore.segments(old)[1].endedAt)
        assertEquals(TripStatus.ACTIVE, store.trips.getValue(old).status)
    }

    /** The end of RideNavigationTest's Z route. */
    private val zDestination = GeoPoint(37.5143891, 127.0390685)
}

