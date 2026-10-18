package com.cowork.bikerecoder.ui.trip

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "기록을 어떻게 할까요?" on the plan screen: [안내 시작] while a recording runs (spec §4.2). */
class TripStartDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                TripStartDialogs(
                    state = TripStartState.AskRecording,
                    onChooseType = { calls += "type" },
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
        show()

        composeRule.onNodeWithText("기록을 어떻게 할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("이어서 기록").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("새 구간으로 나누기").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("취소").assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("keep", "split", "dismiss"), calls)
    }
}
