package com.cowork.bikerecoder.ui.trip

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ui.common.ChoiceSheetTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "안내를 끝낼까요?" on the guidance screen, with and without a recording (spec §3.3, V1.9.1 sheet). */
class EndNavigationSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<EndAction>()
    private var dismissed = 0

    private fun show(type: TripType, recording: Boolean, day: Int = 1) {
        composeRule.setContent {
            MaterialTheme {
                EndNavigationSheet(
                    type = type,
                    recording = recording,
                    onChoose = { chosen += it },
                    onDismiss = { dismissed++ },
                    day = day,
                )
            }
        }
    }

    @Test
    fun recordingChoicesAreShown() {
        show(TripType.SINGLE_DAY, recording = true)

        composeRule.onNodeWithText(END_QUESTION).assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("guidance-only")).assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("finish-today")).assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("continue-tomorrow")).assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()

        composeRule.onNodeWithTag(ChoiceSheetTags.card("guidance-only")).performClick()
        assertEquals(listOf(EndAction.END_GUIDANCE_ONLY), chosen)
    }

    @Test
    fun aSingleDayTripCanBeKeptForTomorrow() {
        show(TripType.SINGLE_DAY, recording = false)

        composeRule.onNodeWithTag(ChoiceSheetTags.card("guidance-only")).assertDoesNotExist()
        composeRule.onNodeWithText("오늘로 끝내기").assertIsDisplayed()
        composeRule.onNodeWithText("내일 이어서 하기").assertIsDisplayed().performClick()

        assertEquals(listOf(EndAction.CONTINUE_TOMORROW), chosen)
    }

    @Test
    fun multiDayRecordingChoicesAreShown() {
        show(TripType.MULTI_DAY, recording = true, day = 2)

        composeRule.onNodeWithText("안내만 끝내기").assertIsDisplayed()
        composeRule.onNodeWithText("오늘은 여기까지").assertIsDisplayed()
        composeRule.onNodeWithText("2일째 기록을 마치고 여행은 이어 갑니다").assertIsDisplayed()
        composeRule.onNodeWithText("여행 완료").assertIsDisplayed()

        composeRule.onNodeWithTag(ChoiceSheetTags.DISMISS).performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList<EndAction>(), chosen)
    }
}
