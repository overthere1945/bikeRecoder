package com.cowork.bikerecoder.ui.navigate

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The guidance buttons (spec §3): [안내 취소] [주행 종료] while recording, V1.0.0 otherwise. */
class GuidanceButtonRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(recording: Boolean, muted: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                GuidanceButtonRow(
                    recording = recording,
                    muted = muted,
                    onMute = { calls += "mute $it" },
                    onOverview = { calls += "overview" },
                    onEnd = { calls += "end" },
                    onCancelGuidance = { calls += "cancel" },
                )
            }
        }
    }

    @Test
    fun recordingShowsCancelAndEndRide() {
        show(recording = true)

        composeRule.onNodeWithText("음소거").assertIsDisplayed()
        composeRule.onNodeWithText("전체 경로").assertDoesNotExist()
        composeRule.onNodeWithText("종료").assertDoesNotExist()
        composeRule.onNodeWithText("안내 취소").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("주행 종료").assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("cancel", "end"), calls)
    }

    @Test
    fun notRecordingShowsTheV1Buttons() {
        show(recording = false, muted = true)

        composeRule.onNodeWithText("음소거 해제").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("전체 경로").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("종료").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("안내 취소").assertDoesNotExist()
        composeRule.onNodeWithText("주행 종료").assertDoesNotExist()

        composeRule.waitForIdle()
        assertEquals(listOf("mute false", "overview", "end"), calls)
    }

    @Test
    fun notMutedSaysMute() {
        show(recording = false)

        composeRule.onNodeWithText("음소거").assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("mute true"), calls)
    }
}
