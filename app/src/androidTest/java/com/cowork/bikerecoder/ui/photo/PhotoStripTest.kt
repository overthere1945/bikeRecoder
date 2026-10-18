package com.cowork.bikerecoder.ui.photo

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "사진 N장", the previews and "숨긴 사진 N장" (spec §2.3). */
class PhotoStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val thumbnails = fakeThumbnails()
    private val photos = listOf(ridePhoto(1, seoul(14, 0)), ridePhoto(2, seoul(14, 10)), ridePhoto(3, seoul(14, 20)))
    private val opened = mutableListOf<Int>()
    private var openedHidden = 0

    private fun show(visible: List<com.cowork.bikerecoder.post.RidePhoto>, hiddenCount: Int) {
        composeRule.setContent {
            MaterialTheme {
                PhotoStrip(
                    photos = visible,
                    hiddenCount = hiddenCount,
                    thumbnails = thumbnails,
                    onOpen = { opened += it },
                    onOpenHidden = { openedHidden++ },
                )
            }
        }
    }

    @Test
    fun countsThePhotosAndOpensTheTappedOne() {
        show(photos, hiddenCount = 0)

        composeRule.onNodeWithText("사진 3장").assertIsDisplayed()
        composeRule.onNodeWithText("숨긴 사진", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PhotoStripTags.item(1)).performClick()
        composeRule.onNodeWithContentDescription("사진 3").performClick()

        assertEquals(listOf(1, 2), opened)
    }

    @Test
    fun hiddenPhotosAreBehindTheirOwnLink() {
        show(photos.take(2), hiddenCount = 1)

        composeRule.onNodeWithText("사진 2장").assertIsDisplayed()
        composeRule.onNodeWithText("숨긴 사진 1장").assertIsDisplayed().performClick()

        assertEquals(1, openedHidden)
        assertEquals(emptyList<Int>(), opened)
    }

    @Test
    fun onlyHiddenPhotosStillShowTheLink() {
        show(emptyList(), hiddenCount = 2)

        composeRule.onNodeWithText("사진 0장").assertIsDisplayed()
        composeRule.onNodeWithText("숨긴 사진 2장").assertIsDisplayed()
    }

    @Test
    fun nothingWithoutPhotos() {
        show(emptyList(), hiddenCount = 0)

        composeRule.onNodeWithText("사진", substring = true).assertDoesNotExist()
    }
}
