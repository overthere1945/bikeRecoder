package com.cowork.bikerecoder.ui.plan

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    private class FakeRouter : Router {
        val requests = mutableListOf<RouteRequest>()
        val lastRequest: RouteRequest get() = requests.last()
        var result: (RouteRequest) -> RouteResult = { request -> RouteResult.Success(route(request.profile)) }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun route(request: RouteRequest): RouteResult {
            requests += request
            gate?.await()
            return result(request)
        }
    }

    private val here = GeoPoint(35.1, 129.0)
    private val a = GeoPoint(35.2, 129.1)
    private val b = GeoPoint(35.3, 129.2)
    private val c = GeoPoint(35.4, 129.3)

    private val router = FakeRouter()
    private var location: GeoPoint? = here
    private var segmentsReady = true

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PlanViewModel(
        router = router,
        defaultProfile = flowOf(RouteProfile.CYCLEWAY_FIRST),
        currentLocation = { location },
        segmentsReady = { segmentsReady },
    )

    private fun TestScope.settle() {
        advanceTimeBy(PlanViewModel.RECOMPUTE_DEBOUNCE_MS + 1)
        runCurrent()
    }

    @Test
    fun `recomputes after profile change`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        settle()
        assertEquals(RouteProfile.CYCLEWAY_FIRST, router.lastRequest.profile)

        vm.setProfile(RouteProfile.SHORTEST)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1, router.requests.size) // still debouncing
        advanceTimeBy(2)
        runCurrent()
        assertEquals(RouteProfile.SHORTEST, router.lastRequest.profile)
        assertEquals(RouteProfile.SHORTEST, vm.state.value.profile)
        assertEquals(here, router.lastRequest.start)
        assertEquals(listOf(a), router.lastRequest.stops)
    }

    @Test
    fun `rapid changes route once`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        advanceTimeBy(200)
        vm.addWaypoint("B", b)
        advanceTimeBy(200)
        vm.addWaypoint("C", c)
        settle()
        assertEquals(1, router.requests.size)
        assertEquals(listOf(b, c, a), router.lastRequest.stops)
    }

    @Test
    fun `waypoint inserted before destination`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        vm.addWaypoint("B", b)
        assertEquals(listOf("B", "A"), vm.state.value.stops.map { it.name })
        settle()
        assertEquals(listOf(b, a), router.lastRequest.stops)
    }

    @Test
    fun `waypoint added before any destination stays a waypoint when destination is set`() = runTest {
        val vm = viewModel()
        vm.addWaypoint("B", b)
        vm.setDestination("A", a)
        assertEquals(listOf("B", "A"), vm.state.value.stops.map { it.name })
        vm.setDestination("C", c) // replaces the destination only
        assertEquals(listOf("B", "C"), vm.state.value.stops.map { it.name })
    }

    @Test
    fun `move reorders`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        vm.addWaypoint("B", b)
        vm.addWaypoint("C", c) // [B, C, A]
        assertEquals(listOf("B", "C", "A"), vm.state.value.stops.map { it.name })
        vm.move(1, 0)
        assertEquals(listOf("C", "B", "A"), vm.state.value.stops.map { it.name })
        vm.move(5, 0) // out of range ignored
        assertEquals(listOf("C", "B", "A"), vm.state.value.stops.map { it.name })
    }

    @Test
    fun `remove drops the stop and clears the route when empty`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        vm.addWaypoint("B", b)
        settle()
        vm.remove(vm.state.value.stops.first().key)
        assertEquals(listOf("A"), vm.state.value.stops.map { it.name })
        vm.remove(vm.state.value.stops.single().key)
        settle()
        assertEquals(RouteUiState.Idle, vm.state.value.route)
        assertFalse(vm.state.value.canStart)
    }

    @Test
    fun `ready route enables start`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        assertEquals(RouteUiState.Loading, vm.state.value.route)
        assertFalse(vm.state.value.canStart)
        settle()
        assertInstanceOf(RouteUiState.Ready::class.java, vm.state.value.route)
        assertTrue(vm.state.value.canStart)
    }

    @Test
    fun `an edit during debounce hides the stale route and cancels in-flight work`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        router.gate = CompletableDeferred()
        settle() // router.route is now suspended on the gate
        vm.addWaypoint("B", b)
        assertEquals(RouteUiState.Loading, vm.state.value.route)
        router.gate!!.complete(Unit) // the cancelled first computation must not publish
        runCurrent()
        assertEquals(RouteUiState.Loading, vm.state.value.route)
        settle()
        assertEquals(listOf(b, a), router.lastRequest.stops)
        assertInstanceOf(RouteUiState.Ready::class.java, vm.state.value.route)
    }

    @Test
    fun `destination within 50m shows error`() = runTest {
        val vm = viewModel()
        vm.setDestination("Near", GeoPoint(here.lat + 0.00027, here.lon)) // about 30 m
        settle()
        assertEquals(RouteUiState.Error("목적지가 현재 위치와 너무 가깝습니다"), vm.state.value.route)
        assertTrue(router.requests.isEmpty())
        assertFalse(vm.state.value.canStart)
    }

    @Test
    fun `no segments disables start and never calls the router`() = runTest {
        segmentsReady = false
        val vm = viewModel()
        vm.setDestination("A", a)
        settle()
        assertEquals(RouteUiState.Error("경로 데이터가 없습니다. 설정에서 내려받으세요"), vm.state.value.route)
        assertFalse(vm.state.value.canStart)
        assertTrue(router.requests.isEmpty())
    }

    @Test
    fun `ready route with segments gone is not startable after the next recompute`() = runTest {
        val vm = viewModel()
        vm.setDestination("A", a)
        settle()
        assertTrue(vm.state.value.canStart)
        segmentsReady = false
        vm.setProfile(RouteProfile.BALANCED)
        settle()
        assertFalse(vm.state.value.canStart)
        assertEquals(1, router.requests.size)
    }

    @Test
    fun `unknown location waits and then routes once it appears`() = runTest {
        location = null
        val vm = viewModel()
        vm.setDestination("A", a)
        settle()
        assertEquals(RouteUiState.Error("현재 위치를 확인하는 중입니다"), vm.state.value.route)
        assertTrue(router.requests.isEmpty())
        location = here
        advanceTimeBy(PlanViewModel.LOCATION_POLL_MS + 1)
        runCurrent()
        assertInstanceOf(RouteUiState.Ready::class.java, vm.state.value.route)
    }

    @Test
    fun `failures map to korean messages`() = runTest {
        val vm = viewModel()
        val expected = mapOf(
            RouteFailure.NO_SEGMENT_DATA to "경로 데이터가 없습니다. 설정에서 내려받으세요",
            RouteFailure.NO_ROUTE to "경로를 찾을 수 없습니다",
            RouteFailure.TIMEOUT to "경로 계산 시간이 너무 깁니다. 경유지를 추가해 주세요",
            RouteFailure.OTHER to "경로를 계산하지 못했습니다",
        )
        vm.setDestination("A", a)
        for ((reason, message) in expected) {
            router.result = { RouteResult.Failure(reason, "detail") }
            vm.addWaypoint("w-$reason", b) // any change triggers a recompute
            settle()
            assertEquals(RouteUiState.Error(message), vm.state.value.route, reason.name)
            assertFalse(vm.state.value.canStart)
        }
    }

    @Test
    fun `router exception becomes the generic error`() = runTest {
        router.result = { error("boom") }
        val vm = viewModel()
        vm.setDestination("A", a)
        settle()
        assertEquals(RouteUiState.Error("경로를 계산하지 못했습니다"), vm.state.value.route)
    }

    @Test
    fun `default profile from settings is applied until the user chooses one`() = runTest {
        val vm = PlanViewModel(router, flowOf(RouteProfile.BALANCED), { here }, { true })
        runCurrent()
        assertEquals(RouteProfile.BALANCED, vm.state.value.profile)

        val vm2 = PlanViewModel(router, flowOf(RouteProfile.BALANCED), { here }, { true })
        vm2.setProfile(RouteProfile.SHORTEST)
        runCurrent()
        assertEquals(RouteProfile.SHORTEST, vm2.state.value.profile)
    }

    companion object {
        fun route(profile: RouteProfile): Route {
            val pts = listOf(GeoPoint(35.1, 129.0), GeoPoint(35.2, 129.1))
            return Route(
                points = pts,
                cumulativeM = listOf(0.0, 14_000.0),
                instructions = emptyList(),
                stopPointIndices = listOf(1),
                summary = RouteSummary(14_000.0, 100, 0.5, profile),
            )
        }
    }
}
