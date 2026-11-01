package com.cowork.bikerecoder.ui.trip

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ui.common.ChoiceSheetTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The start sheets: "기록을 어떻게 할까요?" on the plan screen ([안내 시작] while a recording runs, spec §4.2) and
 * "어떻게 기록할까요?" when a multi-day trip is active (V1.9.1).
 */
class TripStartDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: TripStartState) {
        composeRule.setContent {
            MaterialTheme {
                TripStartDialogs(
                    state = state,
                    onContinue = { calls += "continue" },
                    onStartNew = { calls += "startNew" },
                    onDismiss = { calls += "dismiss" },
                    onKeepRecording = { calls += "keep" },
                    onSplitRecording = { calls += "split" },
                )
            }
        }
    }

    @Test
    fun recordingQuestionOffersKeepSplitCancel() {
        show(TripStartState.AskRecording)

        composeRule.onNodeWithText("기록을 어떻게 할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("이어서 기록").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("새 구간으로 나누기").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("취소").assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("keep", "split", "dismiss"), calls)
    }

    @Test
    fun continueQuestionOffersTheTripOrANewRecord() {
        val trip = Trip(1, TripType.MULTI_DAY, TripStatus.ACTIVE, RouteProfile.BALANCED, 0, 0, null)
        show(TripStartState.AskContinue(trip, "부산", day = 3, past = PastRecords(2, 182_400.0)))

        composeRule.onNodeWithText("어떻게 기록할까요?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("여행 이어서 · 3일째, 부산 · 지난 기록 2일, 182.40km")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("start-new")).performClick()
        composeRule.onNodeWithText("취소").performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("continue", "startNew", "dismiss"), calls)
    }
}
