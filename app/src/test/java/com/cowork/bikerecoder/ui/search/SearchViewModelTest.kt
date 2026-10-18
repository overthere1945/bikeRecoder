package com.cowork.bikerecoder.ui.search

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchError
import com.cowork.bikerecoder.search.SearchResult
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

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing is debounced 400ms and only the last query is searched`() = runTest {
        val vm = SearchViewModel(search) { here }
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
        val vm = SearchViewModel(search) { here }
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
        val vm = SearchViewModel(search) { null }
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
}
