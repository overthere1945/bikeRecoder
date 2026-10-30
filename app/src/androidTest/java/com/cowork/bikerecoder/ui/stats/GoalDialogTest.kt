package com.cowork.bikerecoder.ui.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cowork.bikerecoder.core.stats.GoalInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 목표 창(V1.4.0 스펙 §3.1): 추천 칩, 1~100,000 검사, 칸을 비우면 목표가 없어진다. */
class GoalDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val saved = mutableListOf<Pair<GoalInput, GoalInput>>()
    private var dismissed = 0

    private fun show(weeklyKm: Int? = null, yearlyKm: Int? = null) = composeRule.setContent {
        MaterialTheme {
            GoalDialog(weeklyKm, yearlyKm, onSave = { w, y -> saved += w to y }, onDismiss = { dismissed++ })
        }
    }

    private fun SemanticsNodeInteraction.fieldText(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun showsTheWindowWithTheSuggestions() {
        show()

        composeRule.onNodeWithText("목표").assertIsDisplayed()
        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).assertIsDisplayed()
        composeRule.onNodeWithTag(GoalDialogTags.YEARLY).assertIsDisplayed()
        for (km in listOf("50", "100", "150", "1,000", "2,000", "3,000")) composeRule.onNodeWithText(km).assertIsDisplayed()
        composeRule.onNodeWithText("저장").assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
    }

    @Test
    fun startsWithTheCurrentGoals() {
        show(weeklyKm = 100, yearlyKm = 1000)

        assertEquals("100", composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).fieldText())
        assertEquals("1000", composeRule.onNodeWithTag(GoalDialogTags.YEARLY).fieldText())
    }

    @Test
    fun suggestionChipFillsTheField() {
        show()

        composeRule.onNodeWithTag(GoalDialogTags.weeklyChip(150)).performClick()
        composeRule.onNodeWithTag(GoalDialogTags.yearlyChip(2000)).performClick()

        assertEquals("150", composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).fieldText())
        assertEquals("2000", composeRule.onNodeWithTag(GoalDialogTags.YEARLY).fieldText())
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).performClick()
        assertEquals(listOf<Pair<GoalInput, GoalInput>>(GoalInput.Valid(150) to GoalInput.Valid(2000)), saved)
    }

    @Test
    fun zeroShowsTheErrorAndBlocksSave() {
        show()
        composeRule.onNodeWithText("1~100,000 사이 숫자를 넣어 주세요").assertDoesNotExistCompat()

        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).performTextInput("0")

        composeRule.onNodeWithText("1~100,000 사이 숫자를 넣어 주세요").assertIsDisplayed()
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).performClick()
        assertEquals(emptyList<Pair<GoalInput, GoalInput>>(), saved)
    }

    @Test
    fun outOfRangeAndTextAreRefusedAndAGoodValueClearsTheError() {
        show()
        val weekly = composeRule.onNodeWithTag(GoalDialogTags.WEEKLY)

        weekly.performTextInput("100001")
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).assertIsNotEnabled()
        weekly.performTextClearance()
        weekly.performTextInput("1.5")
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).assertIsNotEnabled()
        weekly.performTextClearance()
        weekly.performTextInput(" 1,000 ")

        composeRule.onNodeWithText("1~100,000 사이 숫자를 넣어 주세요").assertDoesNotExistCompat()
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).assertIsEnabled().performClick()
        assertEquals(listOf<Pair<GoalInput, GoalInput>>(GoalInput.Valid(1000) to GoalInput.Empty), saved)
    }

    @Test
    fun emptyFieldsSaveAsEmpty() {
        show(weeklyKm = 100, yearlyKm = 1000)

        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).performTextClearance()
        composeRule.onNodeWithTag(GoalDialogTags.YEARLY).performTextClearance()
        composeRule.onNodeWithTag(GoalDialogTags.SAVE).assertIsEnabled().performClick()

        assertEquals(listOf<Pair<GoalInput, GoalInput>>(GoalInput.Empty to GoalInput.Empty), saved)
    }

    @Test
    fun cancelSavesNothing() {
        show(weeklyKm = 100)

        composeRule.onNodeWithTag(GoalDialogTags.WEEKLY).performTextInput("5")
        composeRule.onNodeWithTag(GoalDialogTags.CANCEL).performClick()

        assertEquals(1, dismissed)
        assertEquals(emptyList<Pair<GoalInput, GoalInput>>(), saved)
    }

    private fun SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
