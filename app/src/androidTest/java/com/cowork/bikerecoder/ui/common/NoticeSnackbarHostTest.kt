package com.cowork.bikerecoder.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cowork.bikerecoder.ride.ScreenNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The recording's on-screen notice as a snackbar, once per notice (spec §6.1). */
class NoticeSnackbarHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val shown = mutableListOf<Long>()
    private val notice = mutableStateOf<ScreenNotice?>(null)

    private fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme { NoticeSnackbarHost(notice = notice.value, onShown = { shown += it }) }
        }
    }

    /** Lets the snackbar appear, well within its ≈ 4 s (the clock is the test's). */
    private fun waitForText(text: String) {
        composeRule.mainClock.advanceTimeBy(APPEAR_MS)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun showsTheTextOnceAndReportsItShown() {
        show()

        notice.value = ScreenNotice(1, "안내를 마칩니다. 기록은 계속됩니다")
        waitForText("안내를 마칩니다. 기록은 계속됩니다")
        assertEquals(listOf(1L), shown)

        // Cleared (as when reported shown), then the same notice again (e.g. an equal copy): not reported again.
        notice.value = null
        composeRule.mainClock.advanceTimeBy(APPEAR_MS)
        notice.value = ScreenNotice(1, "안내를 마칩니다. 기록은 계속됩니다")
        composeRule.mainClock.advanceTimeBy(APPEAR_MS)
        assertEquals(listOf(1L), shown)
    }

    @Test
    fun aNewNoticeIsShownAgain() {
        show()
        notice.value = ScreenNotice(1, "안내를 마칩니다. 기록은 계속됩니다")
        waitForText("안내를 마칩니다. 기록은 계속됩니다")

        notice.value = ScreenNotice(2, "목적지에 도착했습니다")
        waitForText("목적지에 도착했습니다")

        assertEquals(listOf(1L, 2L), shown)
    }

    @Test
    fun clearingTheNoticeOnceShownKeepsTheSnackbar() {
        // As in the app: reporting the notice shown clears it at once.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                NoticeSnackbarHost(
                    notice = notice.value,
                    onShown = { id ->
                        shown += id
                        if (notice.value?.id == id) notice.value = null
                    },
                )
            }
        }

        notice.value = ScreenNotice(1, "목적지에 도착했습니다")
        waitForText("목적지에 도착했습니다")
        // Still there after the notice was cleared.
        composeRule.mainClock.advanceTimeBy(APPEAR_MS)
        composeRule.onNodeWithText("목적지에 도착했습니다").assertIsDisplayed()

        assertEquals(listOf(1L), shown)
    }

    private companion object {
        const val APPEAR_MS = 1_000L
    }
}
