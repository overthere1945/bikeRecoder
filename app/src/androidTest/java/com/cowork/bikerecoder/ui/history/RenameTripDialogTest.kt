package com.cowork.bikerecoder.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import java.time.LocalDate
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "제목 바꾸기" 창(V1.4.0 스펙 §2.5, 규칙 D6): 30 코드 포인트까지, [저장]은 입력을 넘기고 [취소]는 그대로 둔다. */
class RenameTripDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val saved = mutableListOf<String>()
    private var dismissed = 0

    private fun show(current: String? = null) = composeRule.setContent {
        MaterialTheme {
            RenameTripDialog(current, "저녁 라이딩 · 서울 강서구", onSave = { saved += it }, onDismiss = { dismissed++ })
        }
    }

    @Test
    fun showsTitleAndAutomaticTitleAsHint() {
        show()

        composeRule.onNodeWithText("제목 바꾸기").assertIsDisplayed()
        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
        composeRule.onNodeWithText("저장").assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
    }

    /** A custom title in the field; clearing it shows the given automatic title as the hint, not the custom one. */
    @Test
    fun clearedFieldShowsTheAutomaticTitleAsHint() {
        show("한강 한 바퀴")
        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertDoesNotExist()

        composeRule.onNode(hasSetTextAction()).performTextClearance()

        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
    }

    @Test
    fun thirtyOneCharactersKeepThirty() {
        show()

        composeRule.onNode(hasSetTextAction()).performTextInput("가".repeat(31))

        composeRule.onNode(hasSetTextAction()).assertTextEquals("가".repeat(30))
    }

    @Test
    fun emojiCountAsOneAndAreNotSplit() {
        show()

        composeRule.onNode(hasSetTextAction()).performTextInput("🚴".repeat(31))

        composeRule.onNode(hasSetTextAction()).assertTextEquals("🚴".repeat(30))
    }

    @Test
    fun typingMoreAtTheLimitIsIgnored() {
        show("가".repeat(30))

        composeRule.onNode(hasSetTextAction()).performTextInput("나")

        composeRule.onNode(hasSetTextAction()).assertTextEquals("가".repeat(30))
    }

    @Test
    fun saveHandsOverTheInput() {
        show("한강")

        composeRule.onNode(hasSetTextAction()).performTextInput(" 한 바퀴")
        composeRule.onNodeWithText("저장").performClick()

        assertEquals(listOf("한강 한 바퀴"), saved)
    }

    @Test
    fun cancelHandsOverNothing() {
        show("한강")

        composeRule.onNodeWithText("취소").performClick()

        assertEquals(emptyList<String>(), saved)
        assertEquals(1, dismissed)
    }

    /** The trip screen's first row: the shown title and ✎ "제목 바꾸기". */
    @Test
    fun tripScreenTitleRowOpensTheDialogRequest() {
        val date = LocalDate.of(2026, 10, 25)
        val segment = RideSegment(1, 1, 0L, 1_000L, date, SegmentTotals.ZERO.copy(distanceM = 1_000.0, movingTimeMs = 60_000))
        var renames = 0
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    title = "저녁 라이딩 · 서울 강서구",
                    onRename = { renames++ },
                )
            }
        }

        composeRule.onNodeWithText("저녁 라이딩 · 서울 강서구").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("제목 바꾸기").performClick()

        assertEquals(1, renames)
    }
}
