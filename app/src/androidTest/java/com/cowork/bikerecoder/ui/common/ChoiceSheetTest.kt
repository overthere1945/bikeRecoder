package com.cowork.bikerecoder.ui.common

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ui.record.RecordContent
import com.cowork.bikerecoder.ui.record.RecordTags
import com.cowork.bikerecoder.ui.trip.PastRecords
import com.cowork.bikerecoder.ui.trip.TripStartDialogs
import com.cowork.bikerecoder.ui.trip.TripStartState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The V1.9.1 choice sheet: each card is one button read as "{title}, {subtitle}", the recommended card is marked
 * ([Recommended]; a screen reader hears "추천"), the title is a heading and the bottom button, a swipe down or Back
 * dismiss without choosing. Screenshots `v191-end-sheet` (the record screen's [정지]) and
 * `v191-continue-sheet` (the continue question) go to Download/bikeRecoderTest/.
 */
class ChoiceSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<String>()
    private var dismissed = 0

    private val finish = SheetChoice("finish", "오늘로 끝내기", "이 기록을 마치고 요약을 봅니다", ChoiceIcon.FINISH, recommended = true)
    private val tomorrow = SheetChoice("tomorrow", "내일 이어서 하기", "여러 날 여행으로 남겨 둡니다", ChoiceIcon.TOMORROW)
    private val split = SheetChoice("split", "새 구간으로 나누기", "새 구간을 기록합니다", ChoiceIcon.SPLIT)

    private val isRecommended = SemanticsMatcher.expectValue(Recommended, true)
    private val isNotRecommended = SemanticsMatcher.expectValue(Recommended, false)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun cardsAreButtonsReadAsTitleAndSubtitle() {
        composeRule.setContent {
            MaterialTheme {
                ChoiceSheet(
                    title = "기록을 끝낼까요?",
                    choices = listOf(finish, tomorrow, split),
                    onChoose = { chosen += it.id },
                    dismissLabel = "계속 기록하기",
                    onDismiss = { dismissed++ },
                    summary = "자전거 · 3.01km · 0:10:00",
                )
            }
        }

        composeRule.onNodeWithTag(ChoiceSheetTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("자전거 · 3.01km · 0:10:00").assertIsDisplayed()
        listOf(finish, tomorrow, split).forEach { card ->
            composeRule.onNodeWithTag(ChoiceSheetTags.card(card.id))
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(isButton)
                .assert(if (card.recommended) isRecommended else isNotRecommended)
            composeRule.onNodeWithContentDescription("${card.title}, ${card.subtitle}").assertIsDisplayed()
        }

        composeRule.onNodeWithTag(ChoiceSheetTags.card("tomorrow")).performClick()
        composeRule.onNodeWithText("새 구간으로 나누기").performClick()
        composeRule.onNodeWithTag(ChoiceSheetTags.DISMISS).assertIsDisplayed().performClick()

        composeRule.waitForIdle()
        assertEquals(listOf("tomorrow", "split"), chosen)
        assertEquals(1, dismissed)
    }

    @Test
    fun swipeDownDismissesWithoutChoosing() {
        showSheet()

        composeRule.onNodeWithTag(ChoiceSheetTags.TITLE).performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 1_500f, durationMillis = 300)
        }

        composeRule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), chosen)
    }

    @Test
    fun backPressDismissesWithoutChoosing() {
        showSheet()

        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(500)

        composeRule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), chosen)
    }

    @Test
    fun theTitleIsAHeadingAndTheRecommendedCardSaysSo() {
        showSheet()

        composeRule.onNodeWithTag(ChoiceSheetTags.TITLE).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(ChoiceSheetTags.card("finish"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "추천"))
        listOf("tomorrow", "split").forEach { id ->
            composeRule.onNodeWithTag(ChoiceSheetTags.card(id))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        }
    }

    private fun showSheet() {
        composeRule.setContent {
            MaterialTheme {
                ChoiceSheet(
                    title = "기록을 끝낼까요?",
                    choices = listOf(finish, tomorrow, split),
                    onChoose = { chosen += it.id },
                    dismissLabel = "계속 기록하기",
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithTag(ChoiceSheetTags.TITLE).assertIsDisplayed()
    }

    @Test
    fun endSheetScreenshot() {
        val ride = RideUiState.Active(
            tripId = 1,
            tripType = TripType.SINGLE_DAY,
            segmentId = 1,
            state = RecordingState.Recording,
            stats = RideStats.ZERO.copy(currentSpeedMps = 5.6, distanceM = 23_480.0, movingTimeMs = 4_512_000, avgSpeedMps = 5.2),
            autoPause = true,
            track = emptyList(),
            fix = null,
            gpsWeak = false,
            saveWarning = null,
            activity = ActivityType.BIKE,
        )
        composeRule.setContent {
            MaterialTheme {
                RecordContent(
                    state = ride,
                    navigating = false,
                    onPause = {},
                    onResume = {},
                    onToggleAutoPause = {},
                    onEnd = {},
                    map = { modifier -> Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) },
                )
            }
        }

        composeRule.onNodeWithTag(RecordTags.STOP).performClick()
        composeRule.onNodeWithText("기록을 끝낼까요?").assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("finish-today")).assert(isRecommended)
        composeRule.onNodeWithTag(ChoiceSheetTags.card("continue-tomorrow")).assert(isNotRecommended)
        composeRule.onNodeWithText("자전거 · 23.48km · 1:15:12").assertIsDisplayed()
        composeRule.waitForIdle()
        screenshot("v191-end-sheet")
    }

    @Test
    fun continueSheetScreenshot() {
        val trip = Trip(7, TripType.MULTI_DAY, TripStatus.ACTIVE, RouteProfile.CYCLEWAY_FIRST, 0, 0, null)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { Text("지도") }
                TripStartDialogs(
                    state = TripStartState.AskContinue(trip, "부산", day = 3, past = PastRecords(2, 182_400.0)),
                    onContinue = {},
                    onStartNew = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("어떻게 기록할까요?").assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("continue-trip")).assert(isRecommended)
        composeRule.onNodeWithText("부산 · 지난 기록 2일, 182.40km").assertIsDisplayed()
        composeRule.onNodeWithTag(ChoiceSheetTags.card("start-new")).assert(isNotRecommended)
        composeRule.waitForIdle()
        screenshot("v191-continue-sheet")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png. */
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/")
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "ChoiceSheetTest"
    }
}
