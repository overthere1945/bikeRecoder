package com.cowork.bikerecoder.ui.record

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

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

    private var setDestinations = 0

    private fun show(
        state: RideUiState.Active,
        serviceWarning: String? = null,
        navigating: Boolean = false,
        onSetDestination: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                RecordContent(
                    state = state,
                    navigating = navigating,
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
                    serviceWarning = serviceWarning,
                    onSetDestination = onSetDestination,
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
    fun pageDotsHaveA48dpTouchTarget() {
        show(active())

        composeRule.onNodeWithContentDescription("지도").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("숫자").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theServiceWarningIsShown() {
        show(active(), serviceWarning = "화면이 꺼지면 안내가 멈출 수 있습니다")

        composeRule.onNodeWithText("화면이 꺼지면 안내가 멈출 수 있습니다").assertIsDisplayed()
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

    @Test
    fun setDestinationShowsWhenNotGuiding() {
        show(active(), onSetDestination = { setDestinations++ })

        composeRule.onNodeWithTag(RecordTags.SET_DESTINATION).assertIsDisplayed().assertTextEquals("목적지 정하기")
            .performClick()

        composeRule.waitForIdle()
        assertEquals(1, setDestinations)
        composeRule.onNodeWithTag(RecordTags.SUMMARY).assertIsDisplayed()
    }

    @Test
    fun setDestinationHiddenWhileGuiding() {
        show(active(), navigating = true, onSetDestination = { setDestinations++ })

        composeRule.onNodeWithTag(RecordTags.SET_DESTINATION).assertDoesNotExist()
        composeRule.onNodeWithText("목적지 정하기").assertDoesNotExist()
    }

    private companion object {
        const val MAP = "map-slot"
    }

    @Test
    fun summaryShowsThePhotoCount() {
        var count by mutableIntStateOf(3)
        composeRule.setContent {
            MaterialTheme {
                SegmentSummaryContent(
                    segment = RideSegment(
                        id = 1,
                        tripId = 1,
                        startedAt = 0L,
                        endedAt = 600_000L,
                        localDate = LocalDate.of(2026, 10, 18),
                        totals = SegmentTotals.ZERO.copy(distanceM = 3_010.0, movingTimeMs = 600_000),
                    ),
                    map = {},
                    onConfirm = {},
                    footer = { SummaryPhotoCount(count) },
                )
            }
        }

        composeRule.onNodeWithText("사진 3장").assertIsDisplayed()
        count = 0
        composeRule.waitForIdle()
        composeRule.onNodeWithText("사진", substring = true).assertDoesNotExist()
    }

    /** Spec §3.1, §3.4: the goal and new-record lines come under "사진 N장". */
    @Test
    fun summaryShowsTheAchievementLinesUnderThePhotoCount() {
        val state = SummaryExtrasState(
            photoCount = 2,
            achievements = listOf("이번 주 목표 100 km 달성", "🏆 가장 긴 주행 신기록"),
        )
        composeRule.setContent {
            MaterialTheme {
                SegmentSummaryContent(
                    segment = RideSegment(
                        id = 1,
                        tripId = 1,
                        startedAt = 0L,
                        endedAt = 600_000L,
                        localDate = LocalDate.of(2026, 10, 18),
                        totals = SegmentTotals.ZERO.copy(distanceM = 3_010.0, movingTimeMs = 600_000),
                    ),
                    map = {},
                    onConfirm = {},
                    footer = { SummaryExtrasContent(state, photoPrompt = {}) },
                )
            }
        }

        composeRule.onNodeWithText("사진 2장").assertIsDisplayed()
        composeRule.onNodeWithText("이번 주 목표 100 km 달성").assertIsDisplayed()
        composeRule.onNodeWithText("🏆 가장 긴 주행 신기록").assertIsDisplayed()
        val photoBottom = composeRule.onNodeWithText("사진 2장").getBoundsInRoot().bottom
        val first = composeRule.onNodeWithText("이번 주 목표 100 km 달성").getBoundsInRoot()
        val second = composeRule.onNodeWithText("🏆 가장 긴 주행 신기록").getBoundsInRoot()
        assertTrue("below the photo line", first.top >= photoBottom)
        assertTrue("in order", second.top >= first.bottom)
    }

    /** Spec §3.3, ruling R9: the summary's heart-rate row, [다시 가져오기], and telling it is on screen. */
    @Test
    fun summaryShowsTheHeartRateRow() {
        var refetches = 0
        val onScreen = mutableListOf<Boolean>()
        val state = SummaryExtrasState(
            heartRate = SegmentExtras(1, hrStatus = HrStatus.READY, hrDetailed = true, avgBpm = 128, maxBpm = 166, calories = 210.0),
        )
        composeRule.setContent {
            MaterialTheme {
                SegmentSummaryContent(
                    segment = RideSegment(
                        id = 1,
                        tripId = 1,
                        startedAt = 0L,
                        endedAt = 600_000L,
                        localDate = LocalDate.of(2026, 10, 18),
                        totals = SegmentTotals.ZERO.copy(distanceM = 3_010.0, movingTimeMs = 600_000),
                    ),
                    map = {},
                    onConfirm = {},
                    footer = {
                        SummaryExtrasContent(state, photoPrompt = {}, onRefetch = { refetches++ }, onScreen = { onScreen += it })
                    },
                )
            }
        }

        composeRule.onNodeWithText("평균 심박 128 · 최고 166 bpm · 210 kcal").assertIsDisplayed()
        composeRule.onNodeWithText("다시 가져오기").performClick()
        composeRule.waitForIdle()
        assertEquals(1, refetches)
        assertEquals(listOf(true), onScreen)
    }

    /** Spec §6.10: a short ride's summary asks "거의 움직이지 않은 기록입니다. 저장할까요?" [버리기] [저장]. */
    @Test
    fun shortRideSummaryAsksToDiscard() {
        var state by mutableStateOf(SummaryExtrasState(askShort = true))
        var discards = 0
        var keeps = 0
        composeRule.setContent {
            MaterialTheme {
                SegmentSummaryContent(
                    segment = RideSegment(
                        id = 1,
                        tripId = 1,
                        startedAt = 0L,
                        endedAt = 60_000L,
                        localDate = LocalDate.of(2026, 10, 27),
                        totals = SegmentTotals.ZERO.copy(distanceM = 0.27),
                    ),
                    map = {},
                    onConfirm = {},
                    footer = {
                        SummaryExtrasContent(
                            state,
                            photoPrompt = {},
                            onDiscard = { discards++ },
                            onKeep = {
                                keeps++
                                state = state.copy(askShort = false)
                            },
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("거의 움직이지 않은 기록입니다. 저장할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("버리기").assertIsDisplayed()
        composeRule.onNodeWithText("버리기").performClick()
        composeRule.waitForIdle()
        assertEquals(1, discards)

        composeRule.onNodeWithText("저장").performClick()
        composeRule.waitForIdle()
        assertEquals(1, keeps)
        composeRule.onNodeWithText("거의 움직이지 않은 기록입니다. 저장할까요?").assertDoesNotExist()
    }

    /** V1.5.0 spec §1.1: [공유] under the summary shares the segment's whole trip; none when the trip has nothing to share. */
    @Test
    fun summaryShareButton() {
        var state by mutableStateOf(SummaryExtrasState(shareTripId = 7))
        val shared = mutableListOf<Long>()
        composeRule.setContent {
            MaterialTheme {
                SegmentSummaryContent(
                    segment = RideSegment(
                        id = 1,
                        tripId = 7,
                        startedAt = 0L,
                        endedAt = 600_000L,
                        localDate = LocalDate.of(2026, 10, 18),
                        totals = SegmentTotals.ZERO.copy(distanceM = 3_010.0, movingTimeMs = 600_000),
                    ),
                    map = {},
                    onConfirm = {},
                    footer = { SummaryExtrasContent(state, photoPrompt = {}, onShare = { shared += it }) },
                )
            }
        }

        composeRule.onNodeWithText("공유").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(7L), shared)

        state = SummaryExtrasState(shareTripId = null)
        composeRule.onNodeWithText("공유").assertDoesNotExist()
        composeRule.onNodeWithText("확인").assertIsDisplayed()
    }
}
