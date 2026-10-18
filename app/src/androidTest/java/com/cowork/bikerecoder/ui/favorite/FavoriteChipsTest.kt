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
    private val deleted = mutableListOf<Favorite>()
    private val registered = mutableListOf<FavoriteKind>()
    private var opened = 0

    private fun show(favorites: List<Favorite>, register: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                FavoriteChips(
                    favorites = favorites,
                    onSelect = { selected += it },
                    onDelete = { deleted += it },
                    onOpenFavorites = { opened++ },
                    onRegister = if (register) ({ registered += it }) else null,
                )
            }
        }
    }

    private fun left(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x

    @Test
    fun noFavoritesAndNoRegisterShowsOnlyTheFavoritesChip() {
        show(emptyList())
        composeRule.onNodeWithTag(FavoriteTags.ROW).assertIsDisplayed()
        composeRule.onNodeWithText("★ 즐겨찾기").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).assertDoesNotExist()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.WORK)).assertDoesNotExist()
    }

    @Test
    fun noFavoritesWithRegisterShowsBothRegisterChipsThenTheFavoritesChip() {
        show(emptyList(), register = true)

        composeRule.onNodeWithText("🏠 집 등록").performScrollTo().assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("🏢 회사 등록").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FavoriteTags.ALL).performScrollTo().assertIsDisplayed()
        assertTrue(left(FavoriteTags.register(FavoriteKind.HOME)) < left(FavoriteTags.register(FavoriteKind.WORK)))
        assertTrue(left(FavoriteTags.register(FavoriteKind.WORK)) < left(FavoriteTags.ALL))

        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.WORK)).performClick()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).performClick()
        assertEquals(listOf(FavoriteKind.WORK, FavoriteKind.HOME), registered)
        assertEquals(emptyList<Favorite>(), selected)
        assertEquals(0, opened)
    }

    @Test
    fun customFavoritesAreNotChips() {
        show(listOf(home, work, cafe), register = true)

        composeRule.onNodeWithText("★ 카페").assertDoesNotExist()
        composeRule.onNodeWithTag(FavoriteTags.chip(cafe.id)).assertDoesNotExist()
        composeRule.onNodeWithText("직접 입력").assertDoesNotExist()
        composeRule.onNodeWithText("★ 즐겨찾기").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onlyHomeShowsHomeThenRegisterWorkThenTheFavoritesChip() {
        show(listOf(home, cafe), register = true)

        composeRule.onNodeWithText("🏠 집").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("🏢 회사 등록").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("★ 즐겨찾기").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).assertDoesNotExist()
        assertTrue(left(FavoriteTags.chip(home.id)) < left(FavoriteTags.register(FavoriteKind.WORK)))
        assertTrue(left(FavoriteTags.register(FavoriteKind.WORK)) < left(FavoriteTags.ALL))

        composeRule.onNodeWithText("🏠 집").performClick()
        assertEquals(listOf(home), selected)
        assertEquals(emptyList<FavoriteKind>(), registered)
    }

    @Test
    fun homeWorkThenTheFavoritesChipAndATapOpensTheScreen() {
        show(listOf(home, work, cafe))

        composeRule.onNodeWithText("🏠 집").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("🏢 회사").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("★ 즐겨찾기").performScrollTo().assertIsDisplayed()
        assertTrue(left(FavoriteTags.chip(home.id)) < left(FavoriteTags.chip(work.id)))
        assertTrue(left(FavoriteTags.chip(work.id)) < left(FavoriteTags.ALL))

        composeRule.onNodeWithTag(FavoriteTags.ALL).performClick()
        assertEquals(1, opened)
        assertEquals(emptyList<Favorite>(), selected)

        composeRule.onNodeWithText("🏢 회사").performClick()
        assertEquals(listOf(work), selected)
    }

    @Test
    fun registerChipsAndTheFavoritesChipHaveNoMenu() {
        show(emptyList(), register = true)

        composeRule.onNodeWithTag(FavoriteTags.register(FavoriteKind.HOME)).performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertDoesNotExist()
        composeRule.onNodeWithText("이름 바꾸기").assertDoesNotExist()

        composeRule.onNodeWithTag(FavoriteTags.ALL).performScrollTo().performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertDoesNotExist()
        composeRule.onNodeWithText("이름 바꾸기").assertDoesNotExist()
    }

    @Test
    fun homeChipMenuHasOnlyDelete() {
        show(listOf(home, work, cafe))

        composeRule.onNodeWithTag(FavoriteTags.chip(home.id)).performScrollTo().performTouchInput { longClick() }
        composeRule.onNodeWithText("삭제").assertIsDisplayed()
        composeRule.onNodeWithText("이름 바꾸기").assertDoesNotExist()

        composeRule.onNodeWithText("삭제").performClick()
        assertEquals(listOf(home), deleted)
        assertEquals(emptyList<Favorite>(), selected)
    }
}
