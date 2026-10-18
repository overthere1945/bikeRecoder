package com.cowork.bikerecoder.ui.search

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.RecentPlaceRules
import com.cowork.bikerecoder.search.RecentPlaceStore
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.ui.favorite.FavoriteChips
import com.cowork.bikerecoder.ui.favorite.FavoriteTags
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Measures the real on-screen keyboard (IME insets), so it needs a device with the soft keyboard enabled
 * and the screen unlocked.
 */
class SearchScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fake = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> =
            SearchResult.Ok((1..30).map { Place("장소 $it", "주소 $it", GeoPoint(35.0 + it / 1000.0, 129.0), null) })

        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = SearchResult.Ok(null)
    }

    /** In memory, with the real list rules; empty unless a test fills it. */
    private val recent = object : RecentPlaceStore {
        override val places = MutableStateFlow<List<Place>>(emptyList())

        override suspend fun add(place: Place) {
            places.value = RecentPlaceRules.add(places.value, place)
        }

        override suspend fun remove(place: Place) {
            places.value = RecentPlaceRules.remove(places.value, place)
        }

        override suspend fun clear() {
            places.value = emptyList()
        }
    }

    private val station = Place("부산역", "부산 동구 중앙대로 206", GeoPoint(35.115, 129.041), null)
    private val beach = Place("해운대해수욕장", "부산 해운대구 우동", GeoPoint(35.158, 129.160), null)

    private fun showRecent(vararg places: Place) {
        recent.places.value = places.toList()
        composeRule.waitForIdle()
    }

    @Before
    fun setUp() {
        composeRule.runOnUiThread {
            // The same window conditions as MainActivity: edge-to-edge + adjustResize.
            composeRule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        composeRule.setContent {
            MaterialTheme {
                SearchScreen(
                    viewModel = SearchViewModel(fake, recent) { null },
                    onBack = {},
                    onSetDestination = { _, _ -> },
                    onAddWaypoint = { _, _ -> },
                    // The same slot BikeNavHost builds for the search screen, with no favorites saved.
                    favoriteChips = {
                        FavoriteChips(
                            favorites = emptyList(),
                            onSelect = {},
                            onDelete = {},
                            onOpenFavorites = {},
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                )
            }
        }
    }

    private fun imeHeight(): Int {
        var ime = 0
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            ime = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return ime
    }

    /** Window-space y of the keyboard's top edge. */
    private fun keyboardTop(): Float {
        var decorHeight = 0
        composeRule.runOnUiThread { decorHeight = composeRule.activity.window.decorView.height }
        return (decorHeight - imeHeight()).toFloat()
    }

    private fun openResultsWithKeyboardUp() {
        composeRule.onNodeWithTag(SearchTags.QUERY).performClick()
        composeRule.onNodeWithTag(SearchTags.QUERY).performTextInput("카페")
        composeRule.waitUntil(10_000) {
            imeHeight() > 0 &&
                composeRule.onAllNodes(hasText("장소 1") and hasAnyAncestor(hasTestTag(SearchTags.RESULTS)))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun resultsEndAboveTheKeyboard() {
        openResultsWithKeyboardUp()
        val keyboardTop = keyboardTop()
        val results = composeRule.onNodeWithTag(SearchTags.RESULTS)
        assertTrue(
            "results bottom ${results.fetchSemanticsNode().boundsInWindow.bottom} must be above keyboard top $keyboardTop",
            results.fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 1,
        )

        // performScrollToIndex moves the list state directly (scrollToItem), so the keyboard stays up. Not
        // performScrollToNode: it scrolls through the semantics ScrollBy action, which Compose dispatches as
        // NestedScrollSource.UserInput (like an accessibility scroll), so the hide-on-drag connection treats it as a
        // drag and drops the keyboard; the list then spans the whole screen and keyboardTop above is stale.
        results.performScrollToIndex(29)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("장소 30").assertIsDisplayed()
        val last = composeRule.onNodeWithText("장소 30").fetchSemanticsNode().boundsInWindow
        assertTrue("last result bottom ${last.bottom} must be above keyboard top $keyboardTop", last.bottom <= keyboardTop + 1)
        assertTrue("keyboard must still be up after a programmatic scroll", imeHeight() > 0)
    }

    @Test
    fun draggingTheResultsHidesTheKeyboard() {
        openResultsWithKeyboardUp()
        composeRule.onNodeWithTag(SearchTags.RESULTS).performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { imeHeight() == 0 }
        composeRule.onNodeWithTag(SearchTags.QUERY).assertIsNotFocused()
    }

    @Test
    fun withoutFavoritesOnlyTheFavoritesChipSitsBetweenTheSearchRowAndTheResults() {
        // Empty query + no favorites (spec §8.1): [★ 즐겨찾기] alone, no register chips, then the results area right
        // under the chip row (only its own 8dp padding between).
        composeRule.onNodeWithTag(FavoriteTags.ALL).assertIsDisplayed()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).assertDoesNotExist()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.WORK)).assertDoesNotExist()
        val query = composeRule.onNodeWithTag(SearchTags.QUERY).fetchSemanticsNode().boundsInWindow
        val chips = composeRule.onNodeWithTag(FavoriteTags.ROW).fetchSemanticsNode().boundsInWindow
        val area = composeRule.onNodeWithTag(SearchTags.RESULTS_AREA).fetchSemanticsNode().boundsInWindow
        val padding = with(composeRule.density) { 8.dp.toPx() }
        assertTrue("chips must sit under the search row", chips.top >= query.bottom)
        assertTrue("gap ${area.top - chips.bottom}px must be at most ${padding}px", area.top - chips.bottom in 0f..padding + 1)
    }

    @Test
    fun withNoRecentPlacesNothingIsShown() {
        composeRule.onNodeWithTag(SearchTags.RECENT_HEADER).assertDoesNotExist()
        composeRule.onNodeWithText("최근 검색").assertDoesNotExist()
    }

    @Test
    fun recentPlacesShowNewestFirstWithoutDistanceUnderAHeader() {
        showRecent(beach, station.copy(distanceM = 3_400))
        composeRule.onNodeWithTag(SearchTags.RECENT_HEADER).assertIsDisplayed().assertTextContains("최근 검색")
        composeRule.onNodeWithTag(SearchTags.RECENT_CLEAR).assertIsDisplayed().assertTextContains("전체 삭제")
        composeRule.onNodeWithTag(SearchTags.recent(0))
            .assertTextContains("해운대해수욕장").assertTextContains("부산 해운대구 우동")
        composeRule.onNodeWithTag(SearchTags.recent(1)).assertTextContains("부산역")
        composeRule.onNodeWithText("km", substring = true).assertDoesNotExist()
    }

    @Test
    fun typingAQueryHidesTheRecentPlaces() {
        showRecent(station)
        composeRule.onNodeWithTag(SearchTags.QUERY).performTextInput("카페")
        composeRule.onNodeWithTag(SearchTags.RECENT_HEADER).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.QUERY).performTextClearance()
        composeRule.onNodeWithTag(SearchTags.RECENT_HEADER).assertIsDisplayed()
    }

    @Test
    fun xRemovesOneRecentPlace() {
        showRecent(beach, station)
        composeRule.onNodeWithTag(SearchTags.recentRemove(0)).assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodes(hasContentDescription("지우기")).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(SearchTags.recentRemove(0)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(station), recent.places.value)
        composeRule.onNodeWithText("해운대해수욕장").assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.recent(0)).assertTextContains("부산역")
    }

    @Test
    fun clearAllRemovesTheListAndItsHeader() {
        showRecent(beach, station)
        composeRule.onNodeWithTag(SearchTags.RECENT_CLEAR).performClick()
        composeRule.waitForIdle()
        assertTrue(recent.places.value.isEmpty())
        composeRule.onNodeWithTag(SearchTags.RECENT_HEADER).assertDoesNotExist()
        composeRule.onNodeWithText("부산역").assertDoesNotExist()
    }

    @Test
    fun tappingARecentPlaceOpensItsSheetAndMovesItToTheTop() {
        showRecent(beach, station)
        composeRule.onNodeWithTag(SearchTags.recent(1)).performClick()
        composeRule.onNodeWithText("목적지로 설정").assertIsDisplayed()
        composeRule.onNodeWithText("경유지로 추가").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(listOf(station, beach), recent.places.value)
    }

    @Test
    fun draggingTheRecentPlacesHidesTheKeyboard() {
        showRecent(*(1..RecentPlaceRules.MAX).map { Place("최근 $it", "주소 $it", GeoPoint(35.0 + it / 100.0, 129.0), null) }.toTypedArray())
        composeRule.onNodeWithTag(SearchTags.QUERY).performClick()
        composeRule.waitUntil(10_000) { imeHeight() > 0 }
        val keyboardTop = keyboardTop()
        val area = composeRule.onNodeWithTag(SearchTags.RESULTS_AREA).fetchSemanticsNode().boundsInWindow
        assertTrue("recent list bottom ${area.bottom} must be above keyboard top $keyboardTop", area.bottom <= keyboardTop + 1)

        composeRule.onNodeWithTag(SearchTags.recent(0)).performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { imeHeight() == 0 }
        composeRule.onNodeWithTag(SearchTags.QUERY).assertIsNotFocused()
    }
}
