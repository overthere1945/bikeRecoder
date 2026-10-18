package com.cowork.bikerecoder.ui.favorite

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
    private var startedCustom = 0
    private var confirmedCurrent = 0
    private var dismissed = 0

    private fun show(dialog: FavoriteDialog?) {
        composeRule.setContent {
            MaterialTheme {
                FavoriteDialogs(
                    dialog = dialog,
                    onSave = { kind, name -> saved += kind to name },
                    onConfirmReplace = {},
                    onRename = {},
                    onConfirmDelete = {},
                    onDismiss = { dismissed++ },
                    onSaveCustom = { savedCustom += it },
                    onStartCustom = { startedCustom++ },
                    onConfirmCurrent = { confirmedCurrent++ },
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
        // [직접 입력] opens the name dialog (spec §8.4) instead of a field here.
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("직접 입력").assertIsDisplayed().performClick()
        assertEquals(1, startedCustom)
        assertEquals(emptyList<Pair<FavoriteKind, String?>>(), saved)

        composeRule.onNodeWithText("취소").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun nameFavoriteIsPrefilledAndSavesTheName() {
        show(FavoriteDialog.NameFavorite(FavoritePlace("해운대 해수욕장", p, null), initialName = "해운대 해수욕장", register = true))

        composeRule.onNodeWithText("즐겨찾기 이름").assertIsDisplayed()
        composeRule.onNodeWithText("집").assertDoesNotExist()
        val field = composeRule.onNodeWithTag(FavoriteDialogTags.NAME)
        field.assertTextEquals("이름", "해운대 해수욕장")
        // Typing stops at 20 characters.
        val long = "가나다라마바사아자차카타파하".repeat(2) // 28
        field.performTextReplacement(long)
        field.assertTextEquals("이름", long.take(20))
        field.performTextClearance()
        composeRule.onNodeWithText("저장").assertIsNotEnabled()
        field.performTextInput("바다")
        composeRule.onNodeWithText("저장").assertIsEnabled().performClick()
        assertEquals(listOf("바다"), savedCustom)
        assertEquals(emptyList<Pair<FavoriteKind, String?>>(), saved)
        // No suggestions: no "추천 이름" row.
        composeRule.onNodeWithText("추천 이름").assertDoesNotExist()
    }

    @Test
    fun aSuggestionReplacesTheName() {
        show(
            FavoriteDialog.NameFavorite(
                FavoritePlace("부산 해운대구 우동 1", p, "부산 해운대구 우동 1"),
                initialName = "GS25 우동점",
                suggestions = listOf("GS25 우동점", "스타벅스 해운대점", "부산 해운대구 우동 1"),
                register = true,
            ),
        )

        composeRule.onNodeWithText("추천 이름").assertIsDisplayed()
        composeRule.onNodeWithTag(FavoriteDialogTags.SUGGESTIONS).assertIsDisplayed()
        val field = composeRule.onNodeWithTag(FavoriteDialogTags.NAME)
        field.assertTextEquals("이름", "GS25 우동점")

        composeRule.onNodeWithTag(FavoriteDialogTags.suggestion(1)).performScrollTo().performClick()
        field.assertTextEquals("이름", "스타벅스 해운대점")
        composeRule.onNodeWithTag(FavoriteDialogTags.suggestion(2)).performScrollTo().performClick()
        field.assertTextEquals("이름", "부산 해운대구 우동 1")

        composeRule.onNodeWithText("저장").performClick()
        assertEquals(listOf("부산 해운대구 우동 1"), savedCustom)
    }

    @Test
    fun theNameFollowsTheSuggestionsUntilEdited() {
        val place = FavoritePlace("부산 해운대구 우동 1", p, "부산 해운대구 우동 1")
        var dialog by mutableStateOf<FavoriteDialog?>(FavoriteDialog.NameFavorite(place, "", loadingSuggestions = true, register = true))
        composeRule.setContent {
            MaterialTheme {
                FavoriteDialogs(
                    dialog = dialog,
                    onSave = { _, _ -> },
                    onConfirmReplace = {},
                    onRename = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onSaveCustom = { savedCustom += it },
                    onStartCustom = {},
                    onConfirmCurrent = {},
                )
            }
        }
        val field = composeRule.onNodeWithTag(FavoriteDialogTags.NAME)
        field.assertTextEquals("이름", "")
        composeRule.onNodeWithText("추천 이름").assertIsDisplayed()

        dialog = FavoriteDialog.NameFavorite(place, "스타벅스", suggestions = listOf("스타벅스", place.address!!), register = true)
        composeRule.waitForIdle()
        field.assertTextEquals("이름", "스타벅스")

        // Once the rider typed, a later name does not overwrite it.
        field.performTextReplacement("우리 집 앞")
        dialog = FavoriteDialog.NameFavorite(place, "다른 이름", suggestions = listOf("다른 이름"), register = true)
        composeRule.waitForIdle()
        field.assertTextEquals("이름", "우리 집 앞")
    }

    @Test
    fun nameFavoriteShowsTheRefusalAndTheWarningAndCancels() {
        show(
            FavoriteDialog.NameFavorite(
                FavoritePlace("카페", p, null),
                initialName = "카페",
                error = "같은 이름의 즐겨찾기가 있습니다",
                register = false,
                accuracyWarning = "위치 오차가 큽니다(약 88m)",
            ),
        )
        composeRule.onNodeWithText("같은 이름의 즐겨찾기가 있습니다").assertIsDisplayed()
        composeRule.onNodeWithText("위치 오차가 큽니다(약 88m)").assertIsDisplayed()
        composeRule.onNodeWithText("취소").performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), savedCustom)
    }

    @Test
    fun confirmCurrentAsksWithTheAddressAndTheWarning() {
        show(
            FavoriteDialog.ConfirmCurrent(
                FavoriteKind.HOME,
                FavoritePlace("부산 해운대구 우동 1", p, "부산 해운대구 우동 1"),
                "현재 위치를 집으로 저장할까요?",
                accuracyWarning = "위치 오차가 큽니다(약 88m)",
            ),
        )
        composeRule.onNodeWithText("현재 위치를 집으로 저장할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("부산 해운대구 우동 1").assertIsDisplayed()
        composeRule.onNodeWithText("위치 오차가 큽니다(약 88m)").assertIsDisplayed()
        composeRule.onNodeWithText("저장").performClick()
        assertEquals(1, confirmedCurrent)
        composeRule.onNodeWithText("취소").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun confirmCurrentWithoutAnAddressShowsTheCoordinatesAndNoWarning() {
        show(
            FavoriteDialog.ConfirmCurrent(
                FavoriteKind.WORK,
                FavoritePlace("35.10000, 129.00000", p, null),
                "현재 위치를 회사로 저장할까요?",
                accuracyWarning = null,
            ),
        )
        composeRule.onNodeWithText("현재 위치를 회사로 저장할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("35.10000, 129.00000").assertIsDisplayed()
        composeRule.onNodeWithText("위치 오차가 큽니다", substring = true).assertDoesNotExist()
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
