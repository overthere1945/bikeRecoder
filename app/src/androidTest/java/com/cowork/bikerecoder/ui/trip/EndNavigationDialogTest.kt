package com.cowork.bikerecoder.ui.trip

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.trip.TripType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "안내를 끝낼까요?" on the guidance screen, with and without a recording (spec §3.3). */
class EndNavigationDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<EndAction>()
    private var dismissed = 0

    private fun show(type: TripType, recording: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                EndNavigationDialog(type = type, recording = recording, onChoose = { chosen += it }, onDismiss = { dismissed++ })
            }
        }
    }

    @Test
    fun recordingChoicesAreShown() {
        show(TripType.SINGLE_DAY, recording = true)

        composeRule.onNodeWithText(END_QUESTION).assertIsDisplayed()
        composeRule.onNodeWithText("안내만 끝내기 (기록 계속)").assertIsDisplayed()
        composeRule.onNodeWithText("모두 끝내기").assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
        composeRule.onNodeWithText("끝내기").assertDoesNotExist()

        composeRule.onNodeWithText("안내만 끝내기 (기록 계속)").performClick()
        assertEquals(listOf(EndAction.END_GUIDANCE_ONLY), chosen)
    }

    @Test
    fun multiDayRecordingChoicesAreShown() {
        show(TripType.MULTI_DAY, recording = true)

        composeRule.onNodeWithText("안내만 끝내기").assertIsDisplayed()
        composeRule.onNodeWithText("오늘은 여기까지").assertIsDisplayed()
        composeRule.onNodeWithText("여행 완료").assertIsDisplayed()

        composeRule.onNodeWithText("취소").performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList<EndAction>(), chosen)
    }

    @Test
    fun withoutRecordingTheChoicesAreUnchanged() {
        show(TripType.SINGLE_DAY, recording = false)

        composeRule.onNodeWithText("끝내기").assertIsDisplayed()
        composeRule.onNodeWithText("안내만 끝내기 (기록 계속)").assertDoesNotExist()
        composeRule.onNodeWithText("모두 끝내기").assertDoesNotExist()
    }
}
