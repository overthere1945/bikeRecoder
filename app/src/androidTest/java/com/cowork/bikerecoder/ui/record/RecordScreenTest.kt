package com.cowork.bikerecoder.ui.record

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * The record screen's content. The map slot is a classic View that consumes every touch (as MapLibre's MapView does,
 * without requestDisallowInterceptTouchEvent), so the Compose/View interop of a drag on the map is the real one.
 */
class RecordScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val autoPauseCalls = mutableListOf<Boolean>()
    private val ends = mutableListOf<RecordingEnd>()
    private val mapTouches = mutableListOf<Int>()

    private fun active(state: RecordingState = RecordingState.Recording) = RideUiState.Active(
        tripId = 1,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = state,
        stats = RideStats.ZERO.copy(currentSpeedMps = 5.0, distanceM = 3_010.0, movingTimeMs = 600_000, avgSpeedMps = 5.0),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private fun show(state: RideUiState.Active) {
        composeRule.setContent {
            MaterialTheme {
                RecordContent(
                    state = state,
                    navigating = false,
                    onPause = {},
                    onResume = {},
                    onToggleAutoPause = { autoPauseCalls += it },
                    onEnd = { ends += it },
                    map = { modifier ->
                        // Like MapLibre's MapView: a classic View that takes every touch and never asks its
                        // parents not to intercept.
                        AndroidView(
                            factory = { context ->
                                object : View(context) {
                                    @SuppressLint("ClickableViewAccessibility")
                                    override fun onTouchEvent(event: MotionEvent): Boolean {
                                        mapTouches += event.actionMasked
                                        return true
                                    }
                                }.apply { setBackgroundColor(android.graphics.Color.LTGRAY) }
                            },
                            modifier = modifier.testTag(MAP),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun swipingTheSummaryShowsTheNumbersPage() {
        show(active())

        composeRule.onNodeWithTag(RecordTags.SUMMARY).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("최고").assertIsDisplayed()
    }

    @Test
    fun draggingTheMapKeepsTheMapPage() {
        show(active())

        composeRule.onNodeWithTag(MAP).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // The map got the whole drag (not cancelled by the pager) and the page stayed.
        assertEquals(MotionEvent.ACTION_UP, mapTouches.last())
        assertFalse(MotionEvent.ACTION_CANCEL in mapTouches)
        // The numbers page is composed next to the map (beyondViewportPageCount) but not shown.
        composeRule.onNodeWithText("최고").assertIsNotDisplayed()
        composeRule.onNodeWithTag(RecordTags.SUMMARY).assertIsDisplayed()
            .assertTextEquals("18.0km/h · 3.01km · 0:10:00 · 평균 18.0")
    }

    @Test
    fun swipingTheNumbersPageGoesBackToTheMap() {
        show(active())
        composeRule.onNodeWithTag(RecordTags.SUMMARY).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RecordTags.NUMBERS_PAGE).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(RecordTags.SUMMARY).assertIsDisplayed()
    }

    @Test
    fun theTopStripAndThePageDotsTurnPages() {
        show(active())

        composeRule.onNodeWithTag(RecordTags.TOP).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("최고").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("지도").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RecordTags.SUMMARY).assertIsDisplayed()
    }

    @Test
    fun startingAndFailedStates() {
        val state = mutableStateOf<RideUiState>(RideUiState.Starting(1))
        var closed = 0
        composeRule.setContent { MaterialTheme { RecordStatusContent(state.value, onClose = { closed++ }) } }

        composeRule.onNodeWithText("기록을 준비하는 중…").assertIsDisplayed()

        state.value = RideUiState.Failed(1, "기록을 시작하지 못했습니다")
        composeRule.onNodeWithText("기록을 시작하지 못했습니다").assertIsDisplayed()
        composeRule.onNodeWithText("닫기").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun autoPauseChipToggles() {
        show(active())

        composeRule.onNodeWithText("자동 일시정지 ON").assertIsDisplayed()
        composeRule.onNodeWithTag(RecordTags.CHIP).performClick()

        assertEquals(listOf(false), autoPauseCalls)
    }

    @Test
    fun stopAsksForConfirmation() {
        show(active())

        composeRule.onNodeWithTag(RecordTags.STOP).performClick()
        composeRule.onNodeWithText("기록을 끝낼까요?").assertIsDisplayed()
        composeRule.onNodeWithText("끝내기").performClick()

        assertEquals(listOf(RecordingEnd.COMPLETE_TRIP), ends)
        composeRule.onNodeWithText("기록을 끝낼까요?").assertDoesNotExist()
    }

    @Test
    fun pausedShowsTheBand() {
        show(active(RecordingState.Paused(PauseKind.AUTO)))

        composeRule.onNodeWithTag(RecordTags.BAND).assertIsDisplayed().assertTextEquals("일시정지됨 (자동)")
        composeRule.onNodeWithTag(RecordTags.RESUME).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordTags.PAUSE).assertDoesNotExist()
    }

    private companion object {
        const val MAP = "map-slot"
    }
}
