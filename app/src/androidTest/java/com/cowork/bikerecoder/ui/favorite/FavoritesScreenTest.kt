package com.cowork.bikerecoder.ui.favorite

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FavoritesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val p = GeoPoint(35.1, 129.0)
    private val cafe = Favorite(3, FavoriteKind.CUSTOM, "카페", p, "부산 해운대구 우동", 3)
    private val park = Favorite(5, FavoriteKind.CUSTOM, "공원", p, null, 5)

    private var back = 0
    private var added = 0
    private val selected = mutableListOf<Favorite>()
    private val renamed = mutableListOf<Favorite>()
    private val deleted = mutableListOf<Favorite>()

    private fun show(favorites: List<Favorite>, loaded: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                FavoritesScreen(
                    favorites = favorites,
                    loaded = loaded,
                    onBack = { back++ },
                    onAdd = { added++ },
                    onSelect = { selected += it },
                    onRename = { renamed += it },
                    onDelete = { deleted += it },
                )
            }
        }
    }

    private fun top(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test
    fun titleAddLineFirstThenTheFavoritesInOrder() {
        show(listOf(cafe, park))

        composeRule.onNodeWithText("즐겨찾기").assertIsDisplayed()
        composeRule.onNodeWithTag(FavoritesTags.ADD).assertIsDisplayed().assertTextContains("＋ 즐겨찾기 추가")
        composeRule.onNodeWithTag(FavoritesTags.row(cafe.id)).assertTextContains("카페").assertTextContains("부산 해운대구 우동")
        composeRule.onNodeWithTag(FavoritesTags.row(park.id)).assertTextContains("공원")
        assertTrue(top(FavoritesTags.ADD) < top(FavoritesTags.row(cafe.id)))
        assertTrue(top(FavoritesTags.row(cafe.id)) < top(FavoritesTags.row(park.id)))
        composeRule.onNodeWithTag(FavoritesTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("등록한 즐겨찾기가 없습니다").assertDoesNotExist()
    }

    @Test
    fun noFavoritesShowsTheEmptyTextUnderTheAddLine() {
        show(emptyList())

        composeRule.onNodeWithTag(FavoritesTags.ADD).assertIsDisplayed()
        composeRule.onNodeWithText("등록한 즐겨찾기가 없습니다").assertIsDisplayed()
        assertTrue(top(FavoritesTags.ADD) < top(FavoritesTags.EMPTY))
    }

    @Test
    fun beforeTheListHasLoadedThereIsNoEmptyText() {
        show(emptyList(), loaded = false)

        composeRule.onNodeWithTag(FavoritesTags.ADD).assertIsDisplayed()
        composeRule.onNodeWithTag(FavoritesTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithText("등록한 즐겨찾기가 없습니다").assertDoesNotExist()
    }

    @Test
    fun addBackAndTapCallBack() {
        show(listOf(cafe, park))

        composeRule.onNodeWithTag(FavoritesTags.ADD).performClick()
        assertEquals(1, added)
        composeRule.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(1, back)
        composeRule.onNodeWithTag(FavoritesTags.row(park.id)).performClick()
        assertEquals(listOf(park), selected)
        assertEquals(emptyList<Favorite>(), renamed)
        assertEquals(emptyList<Favorite>(), deleted)
    }

    @Test
    fun theMenuButtonOffersRenameAndDelete() {
        show(listOf(cafe, park))

        composeRule.onNodeWithTag(FavoritesTags.menu(cafe.id)).performClick()
        composeRule.onNodeWithText("이름 바꾸기").assertIsDisplayed()
        composeRule.onNodeWithText("삭제").assertIsDisplayed().performClick()
        assertEquals(listOf(cafe), deleted)

        composeRule.onNodeWithTag(FavoritesTags.menu(park.id)).performClick()
        composeRule.onNodeWithText("이름 바꾸기").performClick()
        assertEquals(listOf(park), renamed)
        assertEquals(emptyList<Favorite>(), selected)
    }

    @Test
    fun aLongPressOpensTheSameMenu() {
        show(listOf(cafe))

        composeRule.onNodeWithTag(FavoritesTags.row(cafe.id)).performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertIsDisplayed()
        composeRule.onNodeWithText("이름 바꾸기").assertIsDisplayed().performClick()
        assertEquals(listOf(cafe), renamed)
        assertEquals(emptyList<Favorite>(), selected)
    }
}
