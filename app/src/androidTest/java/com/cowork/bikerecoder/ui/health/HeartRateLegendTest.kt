package com.cowork.bikerecoder.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The heart-rate colours' key (spec §3.3) and the [기본] [심박] choice. */
class HeartRateLegendTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTheFiveBandsAndGrey() {
        composeRule.setContent { MaterialTheme { HeartRateLegend() } }

        listOf("100 미만", "100–119", "120–139", "140–159", "160 이상", "기록 없음").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun theToggleSwitchesBetweenDefaultAndHeartRate() {
        var mode by mutableStateOf(TrackColorMode.DEFAULT)
        composeRule.setContent { MaterialTheme { Column { TrackColorToggle(mode, onModeChange = { mode = it }) } } }

        composeRule.onNodeWithText("기본").assertIsDisplayed()
        composeRule.onNodeWithTag(TrackColorTags.DEFAULT).assertIsSelected()
        composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).assertIsNotSelected().performClick()
        composeRule.waitForIdle()
        assertEquals(TrackColorMode.HEART_RATE, mode)
        composeRule.onNodeWithTag(TrackColorTags.HEART_RATE).assertIsSelected()
        composeRule.onNodeWithText("기본").performClick()
        composeRule.waitForIdle()
        assertEquals(TrackColorMode.DEFAULT, mode)
    }
}
