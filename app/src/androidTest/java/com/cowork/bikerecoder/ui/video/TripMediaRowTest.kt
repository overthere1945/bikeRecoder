package com.cowork.bikerecoder.ui.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoOptionsJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * "만든 영상·이미지" (spec §5.1) and the trip's entry (§1.1, §3.2): each made file with its label, a tap opens it; the two
 * buttons, or the progress card with [취소] while the job runs.
 */
class TripMediaRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val video = TripMedia(2, 7, MediaKind.VIDEO, "content://media/external/video/media/2", 200L, VideoOptionsJson.encode(VideoOptions()))
    private val image = TripMedia(1, 7, MediaKind.IMAGE, "content://media/external/images/media/1", 100L,
        VideoOptionsJson.encode(VideoOptions(aspect = Aspect.LANDSCAPE)))

    @Test
    fun theMadeFilesAreShownAndATapOpensOne() {
        val opened = mutableListOf<TripMedia>()
        val asked = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                TripMediaRow(listOf(video, image), thumbnail = { uri, _ -> asked += uri; null }, onOpen = { opened += it })
            }
        }
        composeRule.onNodeWithText("만든 영상·이미지").assertIsDisplayed()
        composeRule.onNodeWithText("영상 · 세로").assertIsDisplayed()
        composeRule.onNodeWithText("이미지 · 가로").assertIsDisplayed()

        composeRule.onNodeWithTag(VideoTags.media(1)).performClick()
        composeRule.onNodeWithTag(VideoTags.media(2)).performClick()
        assertEquals(listOf(image, video), opened)
        composeRule.waitForIdle()
        assertEquals(setOf(video.uri, image.uri), asked.toSet())
    }

    @Test
    fun nothingMadeShowsNoRow() {
        composeRule.setContent { MaterialTheme { TripMediaRow(emptyList(), thumbnail = { _, _ -> null }, onOpen = {}) } }
        composeRule.onNodeWithTag(VideoTags.MEDIA_ROW).assertDoesNotExist()
        composeRule.onNodeWithText("만든 영상·이미지").assertDoesNotExist()
    }

    @Test
    fun theButtonsOpenTheSheetOfTheirKind() {
        val made = mutableListOf<MediaKind>()
        composeRule.setContent { MaterialTheme { MediaEntryContent(progress = null, onMake = { made += it }, onCancel = {}) } }
        composeRule.onNodeWithText("영상 만들기").performClick()
        composeRule.onNodeWithText("이미지 만들기").performClick()
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.IMAGE), made)
    }

    @Test
    fun whileMakingTheProgressCardReplacesTheButtons() {
        var cancels = 0
        composeRule.setContent {
            MaterialTheme {
                MediaEntryContent(
                    progress = VideoProgress(MediaKind.VIDEO, 42, "영상 만드는 중 42% · 약 2분 남음"),
                    onMake = {},
                    onCancel = { cancels++ },
                )
            }
        }
        composeRule.onNodeWithText("영상 만드는 중 42% · 약 2분 남음").assertIsDisplayed()
        composeRule.onNodeWithText("영상 만들기").assertDoesNotExist()
        composeRule.onNodeWithText("취소").performClick()
        assertEquals(1, cancels)
    }
}
