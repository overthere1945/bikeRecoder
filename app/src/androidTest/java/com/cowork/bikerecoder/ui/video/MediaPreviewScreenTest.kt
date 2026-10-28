package com.cowork.bikerecoder.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The preview of a made file (spec §3.5, §3.4): its title and name, the 720p note, the satellite fallback note (V1.3.1
 * §4), [공유] and [갤러리에서 보기].
 */
class MediaPreviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aVideoMadeAt720pSaysSoAndItsButtonsAnswer() {
        val taps = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                MediaPreviewScreen(
                    uri = "content://media/external/video/media/9",
                    kind = MediaKind.VIDEO,
                    downscaled = true,
                    satelliteFallback = false,
                    fileName = "bikeRecoder_20261025_0858.mp4",
                    onBack = { taps += "back" },
                    onShare = { taps += "share" },
                    onOpenGallery = { taps += "gallery" },
                    media = { Box(it) },
                )
            }
        }
        composeRule.onNodeWithText("영상을 만들었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("bikeRecoder_20261025_0858.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("이 휴대폰은 720p로 만들었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("공유").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("갤러리에서 보기").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("←").performClick()
        assertEquals(listOf("share", "gallery", "back"), taps)
    }

    @Test
    fun anImageAtFullSizeHasNoNote() {
        composeRule.setContent {
            MaterialTheme {
                MediaPreviewScreen(
                    uri = "content://media/external/images/media/9",
                    kind = MediaKind.IMAGE,
                    downscaled = false,
                    satelliteFallback = false,
                    fileName = null,
                    onBack = {},
                    onShare = {},
                    onOpenGallery = {},
                    media = { Box(it) },
                )
            }
        }
        composeRule.onNodeWithText("이미지를 만들었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("이 휴대폰은 720p로 만들었습니다").assertDoesNotExist()
        composeRule.onNodeWithText(FALLBACK).assertDoesNotExist()
        composeRule.onNodeWithTag(VideoTags.SHARE).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoTags.GALLERY).assertIsDisplayed()
    }

    /** V1.3.1 spec §4: made on the plain map says so, in the 720p note's place; both notes are two lines. */
    @Test
    fun fallbackNotice() {
        var downscaled by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                MediaPreviewScreen(
                    uri = "content://media/external/video/media/9",
                    kind = MediaKind.VIDEO,
                    downscaled = downscaled,
                    satelliteFallback = true,
                    fileName = null,
                    onBack = {},
                    onShare = {},
                    onOpenGallery = {},
                    media = { Box(it) },
                )
            }
        }
        composeRule.onNodeWithText(FALLBACK).assertIsDisplayed()
        composeRule.onNodeWithText("이 휴대폰은 720p로 만들었습니다").assertDoesNotExist()

        downscaled = true
        val fallback = composeRule.onNodeWithText(FALLBACK).assertIsDisplayed().getUnclippedBoundsInRoot()
        val small = composeRule.onNodeWithText("이 휴대폰은 720p로 만들었습니다").assertIsDisplayed().getUnclippedBoundsInRoot()
        val share = composeRule.onNodeWithTag(VideoTags.SHARE).getUnclippedBoundsInRoot()
        // Two lines, stacked above the buttons, not overlapping.
        assertTrue("$small / $fallback", small.bottom <= fallback.top || fallback.bottom <= small.top)
        assertTrue("$fallback / $share", fallback.bottom <= share.top && small.bottom <= share.top)
    }

    private companion object {
        const val FALLBACK = "위성 지도를 받지 못해 일반 지도로 만들었습니다"
    }
}
