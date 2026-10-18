package com.cowork.bikerecoder.ui.photo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.cowork.bikerecoder.post.RidePhoto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The full-screen view (spec §2.3): the time taken, [숨기기]↔[다시 보이기], swiping, back. */
class PhotoViewerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val thumbnails = fakeThumbnails()
    private val first = ridePhoto(1, seoul(14, 31))
    private val second = ridePhoto(2, seoul(15, 5))
    private var dismissed = 0

    /** The viewer over [photos], with hide/unhide applied to the list (as the screens do through the store). */
    private fun show(photos: List<RidePhoto>, startIndex: Int = 0) {
        var current by mutableStateOf(photos)
        composeRule.setContent {
            MaterialTheme {
                PhotoViewer(
                    photos = current,
                    startIndex = startIndex,
                    thumbnails = thumbnails,
                    onToggleHidden = { toggled ->
                        current = current.map { if (it.id == toggled.id) it.copy(hidden = !it.hidden) else it }
                    },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun showsTheTimeTaken() {
        show(listOf(first, second))

        composeRule.onNodeWithText("10월 18일 오후 2:31").assertIsDisplayed()
    }

    @Test
    fun hideTurnsIntoShowAgainAndBack() {
        show(listOf(first, second))

        composeRule.onNodeWithText("숨기기").performClick()
        composeRule.onNodeWithText("다시 보이기").assertIsDisplayed()
        composeRule.onNodeWithText("숨기기").assertDoesNotExist()
        // Still the same photo.
        composeRule.onNodeWithText("10월 18일 오후 2:31").assertIsDisplayed()

        composeRule.onNodeWithText("다시 보이기").performClick()
        composeRule.onNodeWithText("숨기기").assertIsDisplayed()
    }

    @Test
    fun aHiddenPhotoOffersShowAgain() {
        show(listOf(first.copy(hidden = true)))

        composeRule.onNodeWithText("다시 보이기").assertIsDisplayed()
    }

    @Test
    fun swipingTurnsToTheNextPhotoAndBack() {
        show(listOf(first, second))

        composeRule.onNodeWithTag(PhotoViewerTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("10월 18일 오후 3:05").assertIsDisplayed()

        composeRule.onNodeWithTag(PhotoViewerTags.PAGER).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("10월 18일 오후 2:31").assertIsDisplayed()
    }

    @Test
    fun opensAtTheStartIndex() {
        show(listOf(first, second), startIndex = 1)

        composeRule.onNodeWithText("10월 18일 오후 3:05").assertIsDisplayed()
    }

    @Test
    fun backCloses() {
        show(listOf(first))

        composeRule.onNodeWithContentDescription("뒤로").performClick()

        assertEquals(1, dismissed)
    }
}
