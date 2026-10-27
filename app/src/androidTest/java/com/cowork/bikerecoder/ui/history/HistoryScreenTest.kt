package com.cowork.bikerecoder.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The stateless history list and trip detail on fake data (spec §3.5). */
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val date = LocalDate.of(2026, 10, 12)

    private val summary = TripRideSummary(
        trip = Trip(7, TripType.SINGLE_DAY, TripStatus.COMPLETED, RouteProfile.CYCLEWAY_FIRST, 0L, 0L, 0L),
        firstDate = date,
        dayCount = 1,
        distanceM = 32_100.0,
        movingTimeMs = 6_300_000,
        lastStartedAt = 0L,
    )

    private val segment = RideSegment(
        id = 11,
        tripId = 7,
        startedAt = 1_760_245_200_000, // 2026-10-12 14:00 Seoul
        endedAt = 1_760_245_200_000 + 6_300_000,
        localDate = date,
        totals = SegmentTotals.ZERO.copy(distanceM = 32_100.0, movingTimeMs = 6_300_000),
    )

    private val opened = mutableListOf<Long>()
    private val requested = mutableListOf<Long>()
    private var confirmed = 0

    @Test
    fun listOpensTripThenSegment() {
        var showTrip by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                if (!showTrip) {
                    HistoryContent(
                        trips = listOf(summary),
                        dialog = null,
                        onOpenTrip = {
                            opened += it
                            showTrip = true
                        },
                        onRequestDelete = {},
                        onConfirmDelete = {},
                        onDismiss = {},
                        onBack = {},
                    )
                } else {
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
        }

        composeRule.onNodeWithText("2026년 10월 12일 · 당일 · 32.10km · 1시간 45분").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(7L), opened)

        composeRule.onNodeWithText("여행 합계").assertIsDisplayed()
        composeRule.onNodeWithText("2026년 10월 12일").assertIsDisplayed()
        composeRule.onNodeWithText(segmentRowText(segment)).performClick()
        assertEquals(listOf(7L, 11L), opened)
    }

    @Test
    fun cumulativeMapButtonOnTop() {
        var cumulative = 0
        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    trips = emptyList(),
                    dialog = null,
                    onOpenTrip = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    onOpenCumulative = { cumulative++ },
                )
            }
        }

        composeRule.onNodeWithText("기록이 없습니다").assertIsDisplayed() // also without any trip
        composeRule.onNodeWithText("누적 지도").performClick()
        assertEquals(1, cumulative)
    }

    @Test
    fun longPressAsksBeforeDeleting() {
        var dialog by mutableStateOf<HistoryDialog?>(null)
        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    trips = listOf(summary),
                    dialog = dialog,
                    onOpenTrip = { opened += it },
                    onRequestDelete = {
                        requested += it
                        dialog = HistoryDialog.ConfirmDelete(it)
                    },
                    onConfirmDelete = {
                        confirmed++
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("2026년 10월 12일 · 당일 · 32.10km · 1시간 45분").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(listOf(7L), requested)
        assertEquals(emptyList<Long>(), opened)

        composeRule.onNodeWithText(CONFIRM_DELETE_TRIP).assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertIsDisplayed()
        composeRule.onNodeWithText("삭제").performClick()
        composeRule.waitForIdle()
        assertEquals(1, confirmed)
        composeRule.onNodeWithText(CONFIRM_DELETE_TRIP).assertDoesNotExist()
    }

    /** Spec §6.10: "짧은 기록 N개 숨김" [정리] under the list, then "짧은 기록 N개를 삭제할까요?" [삭제] [취소]. */
    @Test
    fun hiddenShortTripsOfferCleanup() {
        var dialog by mutableStateOf<HistoryDialog?>(null)
        var hidden by mutableStateOf(2)
        var cleanups = 0
        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    trips = listOf(summary),
                    dialog = dialog,
                    onOpenTrip = {},
                    onRequestDelete = {},
                    onConfirmDelete = { confirmed++ },
                    onDismiss = { dialog = null },
                    onBack = {},
                    hiddenShortCount = hidden,
                    onRequestCleanup = { dialog = HistoryDialog.ConfirmCleanup(hidden) },
                    onConfirmCleanup = {
                        cleanups++
                        dialog = null
                        hidden = 0
                    },
                )
            }
        }

        composeRule.onNodeWithText("짧은 기록 2개 숨김").assertIsDisplayed()
        composeRule.onNodeWithText("정리").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("짧은 기록 2개를 삭제할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("취소").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("짧은 기록 2개를 삭제할까요?").assertDoesNotExist()
        assertEquals(0, cleanups)

        composeRule.onNodeWithText("정리").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("삭제").performClick()
        composeRule.waitForIdle()
        assertEquals(1, cleanups)
        assertEquals("not the single-trip delete", 0, confirmed)
        composeRule.onNodeWithText("짧은 기록", substring = true).assertDoesNotExist()
    }

    /** Only short trips: the list says it is empty and still offers [정리]. */
    @Test
    fun onlyShortTripsStillOfferCleanup() {
        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    trips = emptyList(),
                    dialog = null,
                    onOpenTrip = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onDismiss = {},
                    onBack = {},
                    hiddenShortCount = 3,
                )
            }
        }

        composeRule.onNodeWithText("기록이 없습니다").assertIsDisplayed()
        composeRule.onNodeWithText("짧은 기록 3개 숨김").assertIsDisplayed()
        composeRule.onNodeWithText("정리").assertIsDisplayed()
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
