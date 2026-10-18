package com.cowork.bikerecoder.ui.search

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.RecentPlaceRules
import com.cowork.bikerecoder.search.RecentPlaceStore
import com.cowork.bikerecoder.search.SearchError
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val here = GeoPoint(35.1, 129.0)
    private val place = Place("부산역", "부산 동구", GeoPoint(35.11, 129.04), 3_400)
    private val queries = mutableListOf<Pair<String, GeoPoint?>>()
    private var outcome: SearchResult<List<Place>> = SearchResult.Ok(listOf(place))

    private val search = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> {
            queries += query to near
            return outcome
        }

        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = SearchResult.Ok(null)
    }

    /** In memory, with the real list rules; [failing] makes every write throw. */
    private class FakeRecent : RecentPlaceStore {
        val list = MutableStateFlow<List<Place>>(emptyList())
        val added = mutableListOf<Place>()
        var failing = false
        override val places: Flow<List<Place>> = list

        override suspend fun add(place: Place) {
            added += place
            if (failing) throw java.io.IOException("disk full")
            list.value = RecentPlaceRules.add(list.value, place)
        }

        override suspend fun remove(place: Place) {
            list.value = RecentPlaceRules.remove(list.value, place)
        }

        override suspend fun clear() {
            list.value = emptyList()
        }
    }

    private val recent = FakeRecent()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing is debounced 400ms and only the last query is searched`() = runTest {
        val vm = SearchViewModel(search, recent) { here }
        runCurrent()
        vm.onQueryChange("부")
        advanceTimeBy(300)
        vm.onQueryChange("부산")
        advanceTimeBy(399)
        runCurrent()
        assertEquals(0, queries.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("부산" to here), queries)
        assertEquals(SearchUiState.Results(listOf(place)), vm.state.value.result)
    }

    @Test
    fun `blank query clears results without searching`() = runTest {
        val vm = SearchViewModel(search, recent) { here }
        vm.onQueryChange("부산")
        advanceTimeBy(401)
        runCurrent()
        vm.onQueryChange("  ")
        runCurrent()
        assertEquals(SearchUiState.Idle, vm.state.value.result)
        assertEquals(1, queries.size)
    }

    @Test
    fun `errors use the korean message and selecting does not disturb the search`() = runTest {
        outcome = SearchResult.Err(SearchError.NETWORK)
        val vm = SearchViewModel(search, recent) { null }
        vm.onQueryChange("부산")
        advanceTimeBy(401)
        runCurrent()
        assertEquals(SearchUiState.Error(SearchError.NETWORK.messageKo), vm.state.value.result)
        vm.select(place)
        runCurrent()
        assertEquals(place, vm.state.value.selected)
        assertEquals(SearchUiState.Error(SearchError.NETWORK.messageKo), vm.state.value.result)
        assertEquals(listOf("부산" to null), queries)
    }

    @Test
    fun `selecting a place opens the sheet and adds it to the recent places`() = runTest {
        val vm = SearchViewModel(search, recent) { here }
        runCurrent()
        vm.select(place)
        runCurrent()
        assertEquals(place, vm.state.value.selected)
        assertEquals(listOf(place), recent.added)
        assertEquals(listOf(place.copy(distanceM = null)), vm.state.value.recent)
    }

    @Test
    fun `the recent list follows the store and can be edited`() = runTest {
        val other = Place("해운대", "부산 해운대구", GeoPoint(35.16, 129.16), null)
        recent.list.value = listOf(other)
        val vm = SearchViewModel(search, recent) { here }
        runCurrent()
        assertEquals(listOf(other), vm.state.value.recent)

        vm.select(place)
        runCurrent()
        assertEquals(listOf(place.copy(distanceM = null), other), vm.state.value.recent)

        vm.removeRecent(other)
        runCurrent()
        assertEquals(listOf(place.copy(distanceM = null)), vm.state.value.recent)

        vm.clearRecent()
        runCurrent()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun `a failing store still opens the sheet`() = runTest {
        recent.failing = true
        val vm = SearchViewModel(search, recent) { here }
        runCurrent()
        vm.select(place)
        runCurrent()
        assertEquals(place, vm.state.value.selected)
        assertEquals(listOf(place), recent.added)
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun `in register mode a tap is only remembered, with no sheet`() = runTest {
        val vm = SearchViewModel(search, recent, register = FavoriteKind.WORK) { here }
        runCurrent()
        assertEquals(FavoriteKind.WORK, vm.state.value.register)
        vm.select(place)
        runCurrent()
        assertNull(vm.state.value.selected)
        assertEquals(listOf(place), recent.added)
        assertEquals(listOf(place.copy(distanceM = null)), vm.state.value.recent)
    }

    @Test
    fun `the register argument names home, work or a custom favorite`() {
        assertEquals(FavoriteKind.HOME, SearchViewModel.registerKind("HOME"))
        assertEquals(FavoriteKind.WORK, SearchViewModel.registerKind("WORK"))
        assertNull(SearchViewModel.registerKind(null))
        assertEquals(FavoriteKind.CUSTOM, SearchViewModel.registerKind("CUSTOM"))
        assertNull(SearchViewModel.registerKind("bogus"))
    }
}
