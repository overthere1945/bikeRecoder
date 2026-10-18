package com.cowork.bikerecoder.ui.favorite

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoriteDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val p = GeoPoint(35.1, 129.0)
    private val saved = mutableListOf<Pair<FavoriteKind, String?>>()
    private val savedCustom = mutableListOf<String>()

    private fun show(dialog: FavoriteDialog?) {
        composeRule.setContent {
            MaterialTheme {
                FavoriteDialogs(
                    dialog = dialog,
                    onSave = { kind, name -> saved += kind to name },
                    onConfirmReplace = {},
                    onRename = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onSaveCustom = { savedCustom += it },
                )
            }
        }
    }

    @Test
    fun chooseNameOffersHomeWorkAndCustom() {
        show(FavoriteDialog.ChooseName(FavoritePlace("해운대 해수욕장", p, null)))

        composeRule.onNodeWithText("즐겨찾기에 저장").assertIsDisplayed()
        composeRule.onNodeWithText("집").assertIsDisplayed()
        composeRule.onNodeWithText("회사").assertIsDisplayed()
        composeRule.onNodeWithText("직접 입력").assertIsDisplayed()

        // Prefilled with the place name; typing stops at 20 characters.
        val field = composeRule.onNode(hasSetTextAction())
        field.assertTextEquals("직접 입력", "해운대 해수욕장")
        val long = "가나다라마바사아자차카타파하".repeat(2) // 28
        field.performTextReplacement(long)
        field.assertTextEquals("직접 입력", long.take(20))

        // An empty field cannot be saved.
        field.performTextClearance()
        composeRule.onNodeWithText("저장").assertIsNotEnabled()
        field.performTextInput("카페")
        composeRule.onNodeWithText("저장").assertIsEnabled().performClick()
        assertEquals(listOf(FavoriteKind.CUSTOM to "카페"), saved)
    }

    @Test
    fun nameCustomIsPrefilledWithThePlaceAndSavesTheName() {
        show(FavoriteDialog.NameCustom(FavoritePlace("해운대 해수욕장", p, null)))

        composeRule.onNodeWithText("즐겨찾기 이름").assertIsDisplayed()
        composeRule.onNodeWithText("집").assertDoesNotExist()
        val field = composeRule.onNode(hasSetTextAction())
        field.assertTextEquals("이름", "해운대 해수욕장")
        field.performTextClearance()
        composeRule.onNodeWithText("저장").assertIsNotEnabled()
        field.performTextInput("바다")
        composeRule.onNodeWithText("저장").assertIsEnabled().performClick()
        assertEquals(listOf("바다"), savedCustom)
        assertEquals(emptyList<Pair<FavoriteKind, String?>>(), saved)
    }

    @Test
    fun nameCustomShowsTheRefusal() {
        show(FavoriteDialog.NameCustom(FavoritePlace("카페", p, null), error = "같은 이름의 즐겨찾기가 있습니다"))
        composeRule.onNodeWithText("같은 이름의 즐겨찾기가 있습니다").assertIsDisplayed()
    }

    @Test
    fun homeAndWorkSaveWithoutAName() {
        show(FavoriteDialog.ChooseName(FavoritePlace("장소", p, null)))
        composeRule.onNodeWithText("집").performClick()
        composeRule.onNodeWithText("회사").performClick()
        assertEquals(listOf(FavoriteKind.HOME to null, FavoriteKind.WORK to null), saved)
    }

    @Test
    fun errorAndDeleteTexts() {
        show(FavoriteDialog.ChooseName(FavoritePlace("장소", p, null), error = "같은 이름의 즐겨찾기가 있습니다"))
        composeRule.onNodeWithText("같은 이름의 즐겨찾기가 있습니다").assertIsDisplayed()
    }

    @Test
    fun deleteAsksFirst() {
        show(FavoriteDialog.ConfirmDelete(Favorite(1, FavoriteKind.CUSTOM, "카페", p, null, 1)))
        composeRule.onNodeWithText("즐겨찾기에서 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("삭제").assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
    }
}
