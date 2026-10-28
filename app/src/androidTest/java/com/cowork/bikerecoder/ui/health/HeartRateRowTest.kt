package com.cowork.bikerecoder.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.post.SegmentExtras
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The heart-rate row's four states, the calories dash, the "not detailed" hint and [다시 가져오기] (spec §3.2, §3.3),
 * and what the section shows instead of "심박 가져오는 중" without permission or Health Connect (ruling R8).
 */
class HeartRateRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var refetches = 0
    private var extras by mutableStateOf<SegmentExtras?>(null)

    private fun extras(status: HrStatus, calories: Double? = null, detailed: Boolean = true) =
        SegmentExtras(1, hrStatus = status, hrDetailed = detailed, avgBpm = 132, maxBpm = 171, calories = calories)

    private fun row() {
        composeRule.setContent { MaterialTheme { HeartRateRow(extras, onRefetch = { refetches++ }) } }
    }

    @Test
    fun eachStateShowsItsWordsAndAlwaysOffersToFetchAgain() {
        row()
        composeRule.onNodeWithText("심박 가져오는 중").assertIsDisplayed()
        composeRule.onNodeWithText("다시 가져오기").performClick()

        extras = extras(HrStatus.PENDING)
        composeRule.onNodeWithText("심박 가져오는 중").assertIsDisplayed()
        extras = extras(HrStatus.NONE)
        composeRule.onNodeWithText("심박 기록 없음").assertIsDisplayed()
        composeRule.onNodeWithText("다시 가져오기").performClick()
        extras = extras(HrStatus.FAILED)
        composeRule.onNodeWithText("심박을 가져오지 못했습니다").assertIsDisplayed()
        composeRule.onNodeWithText("다시 가져오기").performClick()
        extras = extras(HrStatus.READY, calories = 312.4)
        composeRule.onNodeWithText("평균 심박 132 · 최고 171 bpm · 312 kcal").assertIsDisplayed()
        composeRule.onNodeWithText("다시 가져오기").performClick()

        assertEquals(4, refetches)
    }

    @Test
    fun noCaloriesShowsADash() {
        extras = extras(HrStatus.READY)
        row()
        composeRule.onNodeWithText("평균 심박 132 · 최고 171 bpm · – kcal").assertIsDisplayed()
    }

    @Test
    fun heartRateThatIsNotDetailedSaysHowToGetMore() {
        extras = extras(HrStatus.READY, detailed = false)
        row()
        composeRule.onNodeWithText("워치에서 운동을 시작하면 자세한 심박이 기록됩니다").assertIsDisplayed()

        extras = extras(HrStatus.READY, detailed = true)
        composeRule.onNodeWithText("워치에서 운동을 시작하면 자세한 심박이 기록됩니다").assertDoesNotExist()
        extras = extras(HrStatus.NONE, detailed = false)
        composeRule.onNodeWithText("워치에서 운동을 시작하면 자세한 심박이 기록됩니다").assertDoesNotExist()
    }

    @Test
    fun withoutPermissionOrHealthConnectTheSectionDoesNotWaitForNothing() {
        var access by mutableStateOf(HeartRateAccess.NO_PERMISSION)
        extras = extras(HrStatus.PENDING)
        composeRule.setContent {
            MaterialTheme {
                Column { HeartRateSection(extras, access, onRefetch = {}) { Text("권한 카드") } }
            }
        }
        composeRule.onNodeWithText("권한 카드").assertIsDisplayed()
        composeRule.onNodeWithText("심박 가져오는 중").assertDoesNotExist()

        access = HeartRateAccess.UNAVAILABLE
        composeRule.onNodeWithText("Health Connect를 사용할 수 없습니다").assertIsDisplayed()
        composeRule.onNodeWithText("심박 가져오는 중").assertDoesNotExist()
        composeRule.onNodeWithText("권한 카드").assertDoesNotExist()

        // Heart rate already read stays on screen, with the card under it.
        access = HeartRateAccess.NO_PERMISSION
        extras = extras(HrStatus.READY)
        composeRule.onNodeWithText("평균 심박 132 · 최고 171 bpm · – kcal").assertIsDisplayed()
        composeRule.onNodeWithText("권한 카드").assertIsDisplayed()

        access = HeartRateAccess.GRANTED
        extras = extras(HrStatus.PENDING)
        composeRule.onNodeWithText("심박 가져오는 중").assertIsDisplayed()
        composeRule.onNodeWithText("권한 카드").assertDoesNotExist()
    }
}
