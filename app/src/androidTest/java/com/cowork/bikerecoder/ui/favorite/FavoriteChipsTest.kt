package com.cowork.bikerecoder.ui.favorite

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FavoriteChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val p = GeoPoint(35.1, 129.0)
    private val home = Favorite(1, FavoriteKind.HOME, "집", p, null, 1)
    private val work = Favorite(2, FavoriteKind.WORK, "회사", p, null, 2)
    private val cafe = Favorite(3, FavoriteKind.CUSTOM, "카페", p, null, 3)

    private val selected = mutableListOf<Favorite>()
    private val renamed = mutableListOf<Favorite>()
    private val deleted = mutableListOf<Favorite>()
    private val registered = mutableListOf<FavoriteKind>()

    private fun show(favorites: List<Favorite>, register: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                FavoriteChips(
                    favorites = favorites,
                    onSelect = { selected += it },
                    onRename = { renamed += it },
                    onDelete = { deleted += it },
                    onRegister = if (register) ({ registered += it }) else null,
                )
            }
        }
    }

    @Test
    fun noFavoritesAndNoRegisterHidesTheRow() {
        show(emptyList())
        composeRule.onNodeWithTag(FavoriteTags.ROW).assertDoesNotExist()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).assertDoesNotExist()
    }

    @Test
    fun noFavoritesWithRegisterShowsBothRegisterChips() {
        show(emptyList(), register = true)

        composeRule.onNodeWithTag(FavoriteTags.ROW).assertIsDisplayed()
        composeRule.onNodeWithText("🏠 집 등록").performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("🏢 회사 등록").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.WORK)).performClick()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).performClick()
        assertEquals(listOf(FavoriteKind.WORK, FavoriteKind.HOME), registered)
        assertEquals(emptyList<Favorite>(), selected)
    }

    @Test
    fun onlyHomeShowsHomeThenRegisterWorkThenCustom() {
        show(listOf(home, cafe), register = true)

        composeRule.onNodeWithText("🏠 집").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("🏢 회사 등록").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("★ 카페").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).assertDoesNotExist()
        val left = { text: String -> composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x }
        assertTrue(left("🏠 집") < left("🏢 회사 등록"))
        assertTrue(left("🏢 회사 등록") < left("★ 카페"))

        composeRule.onNodeWithText("🏠 집").performClick()
        assertEquals(listOf(home), selected)
        assertEquals(emptyList<FavoriteKind>(), registered)
    }

    @Test
    fun registerChipsHaveNoMenu() {
        show(emptyList(), register = true)

        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertDoesNotExist()
        composeRule.onNodeWithText("이름 바꾸기").assertDoesNotExist()
    }

    @Test
    fun chipsShowInOrderAndATapSelects() {
        show(listOf(home, work, cafe))

        composeRule.onNodeWithText("🏠 집").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("🏢 회사").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("★ 카페").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("★ 카페").performClick()
        assertEquals(listOf(cafe), selected)
    }

    @Test
    fun longPressOnACustomChipOffersRenameAndDelete() {
        show(listOf(home, work, cafe))

        composeRule.onNodeWithTag(FavoriteTags.chip(cafe.id)).performScrollTo().performTouchInput { longClick() }
        composeRule.onNodeWithText("이름 바꾸기").assertIsDisplayed()
        composeRule.onNodeWithText("삭제").assertIsDisplayed()

        composeRule.onNodeWithText("삭제").performClick()
        assertEquals(listOf(cafe), deleted)
        assertEquals(emptyList<Favorite>(), renamed)
        assertEquals(emptyList<Favorite>(), selected)
    }

    @Test
    fun homeChipMenuHasOnlyDelete() {
        show(listOf(home, work, cafe))

        composeRule.onNodeWithTag(FavoriteTags.chip(home.id)).performScrollTo().performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertIsDisplayed()
        composeRule.onNodeWithText("이름 바꾸기").assertDoesNotExist()
    }
}
