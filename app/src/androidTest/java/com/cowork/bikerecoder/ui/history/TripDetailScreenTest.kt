package com.cowork.bikerecoder.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The stateless trip detail on fake data (spec §3.5). The feed's own tests are in ui/feed/FeedScreenTest. */
class TripDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val date = LocalDate.of(2026, 10, 12)

    private val segment = RideSegment(
        id = 11,
        tripId = 7,
        startedAt = 1_760_245_200_000, // 2026-10-12 14:00 Seoul
        endedAt = 1_760_245_200_000 + 6_300_000,
        localDate = date,
        totals = SegmentTotals.ZERO.copy(distanceM = 32_100.0, movingTimeMs = 6_300_000),
    )

    private val opened = mutableListOf<Long>()
    private var confirmed = 0

    @Test
    fun tripOpensItsSegment() {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = null,
                    onOpenSegment = { opened += it },
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("여행 합계").assertIsDisplayed()
        composeRule.onNodeWithText("2026년 10월 12일").assertIsDisplayed()
        composeRule.onNodeWithText(segmentRowText(segment)).performClick()
        assertEquals(listOf(11L), opened)
    }

    @Test
    fun notDeletableShowsWhy() {
        composeRule.setContent {
            MaterialTheme {
                TripDetailContent(
                    segmentsByDate = mapOf(date to listOf(segment)),
                    total = segment.totals,
                    dialog = HistoryDialog.Message(MSG_OPEN_SEGMENT_NOT_DELETABLE),
                    onOpenSegment = {},
                    onRequestDelete = {},
                    onConfirmDelete = { confirmed++ },
                    onDismiss = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(MSG_OPEN_SEGMENT_NOT_DELETABLE).assertIsDisplayed()
        composeRule.onNodeWithText("삭제").assertDoesNotExist()
        composeRule.onNodeWithText("확인").performClick()
        assertEquals(0, confirmed)
    }

    /** Spec §6.9: [다시 가져오기] in the trip's totals, also with no heart-rate line; disabled while a pass runs. */
    @Test
    fun tripRefetchButtonIsShownAndCallsBack() {
        var refetches = 0
        var refetching by mutableStateOf(false)
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
                    onRefetchHeartRate = { refetches++ },
                    refetching = refetching,
                )
            }
        }

        composeRule.onNodeWithText("다시 가져오기").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, refetches)
        composeRule.runOnIdle { refetching = true }
        composeRule.onNodeWithText("다시 가져오기").assertIsNotEnabled()
    }

    @Test
    fun tripWithoutRefetchCallbackHasNoButton() {
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
                )
            }
        }

        composeRule.onNodeWithText("다시 가져오기").assertDoesNotExist()
    }
}
