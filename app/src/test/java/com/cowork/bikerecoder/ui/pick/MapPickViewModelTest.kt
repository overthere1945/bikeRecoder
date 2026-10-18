package com.cowork.bikerecoder.ui.pick

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchError
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MapPickViewModelTest {
    private val start = GeoPoint(37.5665, 126.978)
    private val a = GeoPoint(35.1, 129.0)
    private val b = GeoPoint(35.2, 129.1)

    /** Every reverse-geocode call, in order. */
    private val asked = mutableListOf<GeoPoint>()

    /** Points whose lookup is held until the test completes it; others answer at once from [answers]. */
    private val gates = mutableMapOf<GeoPoint, CompletableDeferred<SearchResult<String?>>>()
    private val answers = mutableMapOf<GeoPoint, SearchResult<String?>>()
    private var throwing = false

    private val search = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> =
            SearchResult.Ok(emptyList())

        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> {
            asked += point
            if (throwing) throw IOException("offline")
            gates[point]?.let { return it.await() }
            return answers[point] ?: SearchResult.Ok("주소 ${point.lat}")
        }

        override suspend fun nearby(point: GeoPoint): SearchResult<List<Place>> = SearchResult.Ok(emptyList())
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the initial center is looked up 300ms after opening`() = runTest {
        val vm = MapPickViewModel(search, start)
        assertEquals(start, vm.state.value.center)
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canConfirm)
        assertEquals("주소를 확인하는 중…", vm.state.value.title)
        advanceTimeBy(299)
        assertTrue(asked.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(start), asked)
        assertEquals("주소 37.5665", vm.state.value.address)
        assertFalse(vm.state.value.loading)
        assertEquals("주소 37.5665", vm.state.value.title)
    }

    @Test
    fun `without an initial center nothing is looked up and nothing can be confirmed`() = runTest {
        val vm = MapPickViewModel(search, null)
        advanceTimeBy(1_000)
        assertTrue(asked.isEmpty())
        assertNull(vm.state.value.center)
        assertFalse(vm.state.value.canConfirm)
        assertNull(vm.result())
    }

    @Test
    fun `idle events are debounced 300ms and only the last center is looked up`() = runTest {
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onMoveStarted()
        vm.onCameraIdle(a)
        advanceTimeBy(200)
        vm.onMoveStarted()
        vm.onCameraIdle(b)
        advanceTimeBy(299)
        assertTrue(asked.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(b), asked)
        assertEquals(b, vm.state.value.center)
        assertEquals("주소 35.2", vm.state.value.address)
    }

    @Test
    fun `the button is disabled while the map moves and enabled again when it stops`() = runTest {
        val vm = MapPickViewModel(search, start)
        advanceTimeBy(400)
        assertTrue(vm.state.value.canConfirm)
        vm.onMoveStarted()
        assertTrue(vm.state.value.moving)
        assertFalse(vm.state.value.canConfirm)
        assertNull(vm.result())
        vm.onCameraIdle(a)
        assertFalse(vm.state.value.moving)
        // Stopped, but its address is still being looked up.
        assertFalse(vm.state.value.canConfirm)
        assertNull(vm.result())
        advanceTimeBy(400)
        assertTrue(vm.state.value.canConfirm)
        assertEquals(PickedPoint(a, "주소 35.1"), vm.result())
    }

    @Test
    fun `a new idle cancels the lookup in flight and its late answer is dropped`() = runTest {
        val gateA = CompletableDeferred<SearchResult<String?>>()
        gates[a] = gateA
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onCameraIdle(a)
        advanceTimeBy(301)
        assertEquals(listOf(a), asked)
        vm.onMoveStarted()
        vm.onCameraIdle(b)
        gateA.complete(SearchResult.Ok("늦은 주소"))
        runCurrent()
        assertEquals(b, vm.state.value.center)
        assertNull(vm.state.value.address)
        assertTrue(vm.state.value.loading)
        advanceTimeBy(301)
        assertEquals("주소 35.2", vm.state.value.address)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `an answer arriving after the map started moving again is dropped`() = runTest {
        val gateA = CompletableDeferred<SearchResult<String?>>()
        gates[a] = gateA
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onCameraIdle(a)
        advanceTimeBy(301)
        vm.onMoveStarted()
        gateA.complete(SearchResult.Ok("움직이는 중 도착"))
        runCurrent()
        assertNull(vm.state.value.address)
        assertTrue(vm.state.value.moving)
    }

    @Test
    fun `stopping again at the same point looks it up again`() = runTest {
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onCameraIdle(a)
        advanceTimeBy(301)
        vm.onMoveStarted()
        vm.onCameraIdle(a)
        advanceTimeBy(301)
        assertEquals(listOf(a, a), asked)
        assertEquals("주소 35.1", vm.state.value.address)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a failed lookup leaves no address and the title falls back to the coordinates`() = runTest {
        answers[a] = SearchResult.Err(SearchError.NETWORK)
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onCameraIdle(a)
        advanceTimeBy(301)
        assertNull(vm.state.value.address)
        assertFalse(vm.state.value.loading)
        assertEquals("35.10000, 129.00000", vm.state.value.title)
        // A failed lookup never blocks the button.
        assertTrue(vm.state.value.canConfirm)
        assertEquals(PickedPoint(a, null), vm.result())
    }

    @Test
    fun `a throwing lookup is treated as no address`() = runTest {
        throwing = true
        val vm = MapPickViewModel(search, null)
        runCurrent()
        vm.onCameraIdle(b)
        advanceTimeBy(301)
        assertNull(vm.state.value.address)
        assertFalse(vm.state.value.loading)
        assertEquals("35.20000, 129.10000", vm.state.value.title)
        assertTrue(vm.state.value.canConfirm)
        assertEquals(PickedPoint(b, null), vm.result())
    }

    @Test
    fun `a picked point becomes a place named by its address or the fallback`() {
        assertEquals(
            Place("부산 동구 중앙대로 206", "부산 동구 중앙대로 206", a, null),
            PickedPoint(a, "부산 동구 중앙대로 206").toPlace(),
        )
        assertEquals(Place("지도에서 선택한 위치", "", a, null), PickedPoint(a, null).toPlace())
    }
}
