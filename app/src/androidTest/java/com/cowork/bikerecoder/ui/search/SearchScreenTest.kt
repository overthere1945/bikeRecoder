package com.cowork.bikerecoder.ui.search

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.favorite.FavoriteChips
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
                    viewModel = SearchViewModel(fake) { null },
                    onBack = {},
                    onSetDestination = { _, _ -> },
                    onAddWaypoint = { _, _ -> },
                    // The same slot BikeNavHost builds for the search screen, with no favorites saved.
                    favoriteChips = {
                        FavoriteChips(
                            favorites = emptyList(),
                            onSelect = {},
                            onRename = {},
                            onDelete = {},
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

        results.performScrollToNode(hasText("장소 30"))
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
    fun withoutFavoritesTheResultsAreaSitsRightUnderTheSearchRow() {
        // Empty query + no favorites: nothing but the search row's own 4dp padding may separate them.
        val query = composeRule.onNodeWithTag(SearchTags.QUERY).fetchSemanticsNode().boundsInWindow
        val area = composeRule.onNodeWithTag(SearchTags.RESULTS_AREA).fetchSemanticsNode().boundsInWindow
        val rowPadding = with(composeRule.density) { 4.dp.toPx() }
        assertTrue("gap ${area.top - query.bottom}px must be at most the row padding ${rowPadding}px", area.top - query.bottom <= rowPadding + 1)
    }
}
