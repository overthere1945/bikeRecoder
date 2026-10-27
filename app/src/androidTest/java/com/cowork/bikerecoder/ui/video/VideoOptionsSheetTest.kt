package com.cowork.bikerecoder.ui.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.core.video.RouteColoring
import com.cowork.bikerecoder.core.video.VideoLength
import com.cowork.bikerecoder.core.video.VideoOptions
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.PendingJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The options sheet and the video dialogs (spec §1.2, §1.3, §3.2–3.4): the items and defaults, [심박] only when offered,
 * the picked music, the estimate, [만들기] reachable on the phone's screen in the real bottom sheet.
 */
class VideoOptionsSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val defaults = VideoSheet(TRIP, MediaKind.VIDEO, VideoOptions(), canColorByHeart = false, minutes = 3)

    @Test
    fun theVideoSheetShowsTheItemsWithTheDefaultsChosen() {
        composeRule.setContent { MaterialTheme { VideoOptionsContent(defaults, {}, {}, {}, {}) } }

        composeRule.onNodeWithText("영상 만들기").assertIsDisplayed()
        for (label in listOf("화면 비율", "길이", "사진 넣기", "숫자 표시", "배경음악", "음악 고르기")) {
            composeRule.onNodeWithText(label).assertExists()
        }
        composeRule.onNodeWithText("세로 9:16").assertIsSelected()
        composeRule.onNodeWithText("가로 16:9").assertIsNotSelected()
        composeRule.onNodeWithText("짧게(약 30초)").assertIsNotSelected()
        composeRule.onNodeWithText("보통(약 1분)").assertIsSelected()
        composeRule.onNodeWithText("길게(약 2분)").assertIsNotSelected()
        composeRule.onNodeWithText("없음").assertIsSelected()
        // No detailed heart rate: no [경로 색] choice.
        composeRule.onNodeWithText("경로 색").assertDoesNotExist()
        composeRule.onNodeWithText("심박").assertDoesNotExist()
        composeRule.onNodeWithTag(VideoTags.ESTIMATE).assertTextEquals("약 3분 걸립니다")
        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsEnabled()
        composeRule.onNodeWithText("만들기").assertExists()
    }

    @Test
    fun heartRateIsOfferedWhenTheTripHasIt() {
        composeRule.setContent {
            MaterialTheme { VideoOptionsContent(defaults.copy(canColorByHeart = true), {}, {}, {}, {}) }
        }
        composeRule.onNodeWithText("경로 색").assertExists()
        composeRule.onNodeWithText("기본").assertIsSelected()
        composeRule.onNodeWithText("심박").assertIsNotSelected()
    }

    @Test
    fun choicesChangeTheOptions() {
        var sheet by mutableStateOf(defaults.copy(canColorByHeart = true))
        val seen = mutableListOf<VideoOptions>()
        composeRule.setContent {
            MaterialTheme {
                VideoOptionsContent(sheet, { seen += it; sheet = sheet.copy(options = it) }, {}, {}, {})
            }
        }
        composeRule.onNodeWithText("가로 16:9").performClick()
        composeRule.onNodeWithText("길게(약 2분)").performClick()
        composeRule.onNodeWithText("사진 넣기").performClick()
        composeRule.onNodeWithText("숫자 표시").performClick()
        composeRule.onNodeWithText("심박").performClick()

        assertEquals(
            VideoOptions(Aspect.LANDSCAPE, VideoLength.LONG, photos = false, hud = false, coloring = RouteColoring.HEART_RATE),
            seen.last(),
        )
        composeRule.onNodeWithText("가로 16:9").assertIsSelected()
        composeRule.onNodeWithText("길게(약 2분)").assertIsSelected()
    }

    @Test
    fun pickedMusicShowsItsNameAndNoneGoesBack() {
        var picks = 0
        var clears = 0
        val withMusic = defaults.copy(options = VideoOptions(musicUri = "content://music/1"), musicName = "여의도 산책.mp3")
        composeRule.setContent { MaterialTheme { VideoOptionsContent(withMusic, {}, { picks++ }, { clears++ }, {}) } }

        composeRule.onNodeWithTag(VideoTags.MUSIC_NAME).assertTextEquals("여의도 산책.mp3")
        composeRule.onNodeWithText("음악 고르기").assertIsSelected()
        composeRule.onNodeWithText("없음").assertIsNotSelected()
        composeRule.onNodeWithText("음악 고르기").performClick()
        composeRule.onNodeWithText("없음").performClick()
        assertEquals(1, picks)
        assertEquals(1, clears)
    }

    @Test
    fun makeWaitsForTheEstimateThenStarts() {
        var sheet by mutableStateOf(defaults.copy(minutes = null))
        var made = 0
        composeRule.setContent { MaterialTheme { VideoOptionsContent(sheet, {}, {}, {}, { made++ }) } }

        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsNotEnabled()
        sheet = sheet.copy(minutes = 2)
        composeRule.onNodeWithTag(VideoTags.ESTIMATE).assertTextEquals("약 2분 걸립니다")
        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsEnabled().performClick()
        assertEquals(1, made)

        // Starting (the music is being encoded): busy, not pressable again.
        sheet = sheet.copy(starting = true)
        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsNotEnabled()
    }

    @Test
    fun theImageSheetAsksOnlyForTheAspect() {
        val image = VideoSheet(TRIP, MediaKind.IMAGE, VideoOptions(aspect = Aspect.LANDSCAPE))
        composeRule.setContent { MaterialTheme { VideoOptionsContent(image, {}, {}, {}, {}) } }

        composeRule.onNodeWithText("이미지 만들기").assertIsDisplayed()
        composeRule.onNodeWithText("가로 16:9").assertIsSelected()
        for (label in listOf("길이", "사진 넣기", "숫자 표시", "경로 색", "배경음악")) {
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        composeRule.onNodeWithTag(VideoTags.ESTIMATE).assertDoesNotExist()
        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsEnabled()
    }

    /** The longest sheet (heart rate and music) in the real bottom sheet: [만들기] is on screen. */
    @Test
    fun makeIsReachableInTheBottomSheet() {
        val longest = defaults.copy(
            canColorByHeart = true,
            options = VideoOptions(musicUri = "content://music/1"),
            musicName = "아주 긴 이름의 배경음악 파일 - 한강 자전거길 여의도에서 반포까지.mp3",
        )
        composeRule.setContent { MaterialTheme { VideoOptionsSheet(longest, {}, {}, {}, {}, {}) } }
        composeRule.onNodeWithTag(VideoTags.MAKE).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoTags.ESTIMATE).assertIsDisplayed()
    }

    @Test
    fun theDialogsSayTheSpecsWordsAndAnswer() {
        var dialog by mutableStateOf<VideoDialog?>(VideoDialog.LowBattery(15))
        val answers = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                VideoDialogs(
                    dialog = dialog,
                    onConfirmBattery = { answers += "계속" },
                    onWithoutMusic = { answers += "음악 없이" },
                    onRetry = { answers += "다시" },
                    onCloseUnfinished = { answers += "닫기" },
                    onDismiss = { answers += "취소" },
                )
            }
        }
        composeRule.onNodeWithText("배터리가 부족합니다(15%). 계속할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("계속").performClick()
        composeRule.onNodeWithText("취소").performClick()

        dialog = VideoDialog.MusicUnreadable
        composeRule.onNodeWithText("음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?").assertIsDisplayed()
        composeRule.onNodeWithText("음악 없이 만들기").performClick()

        dialog = VideoDialog.Unfinished(PendingJob(TRIP, MediaKind.VIDEO, "{}", 0L))
        composeRule.onNodeWithText("영상을 만들지 못했습니다. 다시 만들까요?").assertIsDisplayed()
        composeRule.onNodeWithText("다시 만들기").performClick()
        composeRule.onNodeWithText("닫기").performClick()

        dialog = VideoDialog.Message("저장 공간이 부족합니다(약 72 MB 필요)")
        composeRule.onNodeWithText("저장 공간이 부족합니다(약 72 MB 필요)").assertIsDisplayed()
        composeRule.onNodeWithText("확인").performClick()

        assertEquals(listOf("계속", "취소", "음악 없이", "다시", "닫기", "취소"), answers)
    }

    private companion object {
        const val TRIP = 7L
    }
}
