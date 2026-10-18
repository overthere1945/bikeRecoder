package com.cowork.bikerecoder.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.RecentPlaceRules
import com.cowork.bikerecoder.search.RecentPlaceStore
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The search opened from a [🏠 집 등록] / [🏢 회사 등록] chip (spec §7.4). */
class SearchRegisterModeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val station = Place("부산역", "부산 동구 중앙대로 206", GeoPoint(35.115, 129.041), 1_200)
    private val beach = Place("해운대해수욕장", "", GeoPoint(35.158, 129.160), null)

    private val fake = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = SearchResult.Ok(listOf(station))

        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> = SearchResult.Ok(null)
    }

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

    private val registered = mutableListOf<Pair<FavoritePlace, FavoriteKind>>()

    private fun show(kind: FavoriteKind) {
        composeRule.setContent {
            MaterialTheme {
                SearchScreen(
                    viewModel = SearchViewModel(fake, recent, register = kind) { null },
                    onBack = {},
                    onSetDestination = { _, _ -> },
                    onAddWaypoint = { _, _ -> },
                    favoriteChips = { Text("칩", modifier = Modifier.testTag(CHIPS)) },
                    onSaveFavorite = {},
                    onRegister = { place, k -> registered += place to k },
                )
            }
        }
    }

    @Test
    fun homeModeAsksForHomeAndHasNoChips() {
        show(FavoriteKind.HOME)
        composeRule.onNodeWithText("집 검색", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(CHIPS).assertDoesNotExist()
    }

    @Test
    fun workModeAsksForWork() {
        show(FavoriteKind.WORK)
        composeRule.onNodeWithText("회사 검색", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingAResultSavesItWithNoSheetAndRemembersIt() {
        show(FavoriteKind.WORK)
        composeRule.onNodeWithTag(SearchTags.QUERY).performTextInput("부산역")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("부산역") and hasAnyAncestor(hasTestTag(SearchTags.RESULTS)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("부산역") and hasAnyAncestor(hasTestTag(SearchTags.RESULTS))).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(FavoritePlace(station.name, station.point, station.address) to FavoriteKind.WORK), registered)
        composeRule.onNodeWithText("목적지로 설정").assertDoesNotExist()
        assertEquals(listOf(station.copy(distanceM = null)), recent.places.value)
    }

    @Test
    fun tappingARecentPlaceSavesIt() {
        recent.places.value = listOf(beach)
        show(FavoriteKind.HOME)
        composeRule.onNodeWithTag(SearchTags.recent(0)).performClick()
        composeRule.waitForIdle()

        // A blank address is saved as none, as from the sheet.
        assertEquals(listOf(FavoritePlace(beach.name, beach.point, null) to FavoriteKind.HOME), registered)
        composeRule.onNodeWithText("목적지로 설정").assertDoesNotExist()
    }

    private companion object {
        const val CHIPS = "chips"
    }
}
