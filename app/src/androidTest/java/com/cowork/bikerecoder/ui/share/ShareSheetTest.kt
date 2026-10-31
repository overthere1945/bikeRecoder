package com.cowork.bikerecoder.ui.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.share.DEFAULT_SHARE_HASHTAGS
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.PackageShareApps
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareFacts
import com.cowork.bikerecoder.share.SharePick
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.autoShareText
import com.cowork.bikerecoder.share.fileLines
import com.cowork.bikerecoder.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The "활동 공유하기" sheet (V1.5.0 spec §1.2) in the real bottom sheet, with fake app lookups: the seven buttons and the
 * hidden ones, dimming when no image can be made, the making row, mood chips, [처음 글로], the stale line, "보낼 것".
 */
class ShareSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val auto = autoShareText(
        ShareFacts(
            title = "저녁 라이딩 · 서울 강서구",
            firstDate = LocalDate.of(2026, 10, 25),
            lastDate = LocalDate.of(2026, 10, 25),
            distanceM = 32_100.0,
            movingTimeMs = 6_300_000,
            ascentM = 123.4,
            regionName = "서울 강서구",
        ),
        DEFAULT_SHARE_HASHTAGS,
    )

    private val video = ShareCandidate("content://media/external/video/media/42", MediaKind.VIDEO, Aspect.LANDSCAPE, 1L, 12L * 1024 * 1024)

    /** A finished trip with a fresh landscape video: story makes a portrait image. */
    private fun state(
        text: String = auto,
        general: SharePick = SharePick.File(video),
        story: SharePick = SharePick.MakeImage,
        email: SharePick = SharePick.File(video),
        blocked: String? = null,
        canRetry: Boolean = false,
        making: Boolean = false,
        tripFinished: Boolean = true,
        stale: Boolean = false,
        moods: Set<Mood> = emptySet(),
    ) = ShareSheetState(
        tripId = 7,
        text = text,
        autoText = auto,
        stale = stale,
        moods = moods,
        general = general,
        story = story,
        email = email,
        fileLines = fileLines(general, story, email),
        blocked = blocked,
        canRetry = canRetry,
        making = making,
        tripFinished = tripFinished,
    )

    private class FakeApps(
        private val missing: Set<ShareTarget> = emptySet(),
        private val icons: (ShareTarget) -> Drawable? = { null },
    ) : ShareApps {
        override fun available(target: ShareTarget) = target !in missing
        override fun instagramFeed() = null
        override fun instagramStory() = null
        override fun icon(target: ShareTarget): Drawable? = icons(target)
    }

    private val pressed = mutableListOf<ShareTarget>()
    private val edits = mutableListOf<String>()
    private val moodsPressed = mutableListOf<Mood>()
    private var resets = 0
    private var cancels = 0
    private var retries = 0
    private var dismissals = 0

    private fun show(state: () -> ShareSheetState, apps: ShareApps = FakeApps()) {
        composeRule.setContent {
            MaterialTheme {
                ShareSheet(
                    state = state(),
                    apps = apps,
                    onEdit = { edits += it },
                    onReset = { resets++ },
                    onMood = { moodsPressed += it },
                    onPress = { pressed += it },
                    onCancelMaking = { cancels++ },
                    onRetry = { retries++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    private fun show(state: ShareSheetState, apps: ShareApps = FakeApps()) = show({ state }, apps)

    private fun button(label: String) = composeRule.onNodeWithText(label).performScrollTo()

    @Test
    fun allSevenButtonsWhenAppsPresent() {
        show(state())

        composeRule.onNodeWithText("활동 공유하기").assertIsDisplayed()
        for (label in LABELS) button(label).assertIsDisplayed().assertIsEnabled()
        button("복사").performClick()
        button("페이스북").performClick()
        assertEquals(listOf(ShareTarget.COPY, ShareTarget.FACEBOOK), pressed)
    }

    @Test
    fun missingAppsHidden() {
        show(state(), FakeApps(missing = setOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY)))

        for (label in listOf("페이스북", "인스타그램", "스토리")) composeRule.onNodeWithText(label).assertDoesNotExist()
        for (label in listOf("이메일", "복사", "저장", "더 보기")) button(label).assertIsDisplayed()
    }

    @Test
    fun missingMailAppHidesEmail() {
        show(state(), FakeApps(missing = setOf(ShareTarget.EMAIL)))

        composeRule.onNodeWithText("이메일").assertDoesNotExist()
        button("페이스북").assertIsDisplayed()
    }

    /** Spec §2.5: the reason in place of "보낼 것", the buttons that need an image dimmed, [다시 시도]. */
    @Test
    fun blockedDimsFileButtonsAndShowsReason() {
        val reason = "영상을 만드는 중이라 지금은 이미지를 만들 수 없습니다"
        show(
            state(
                general = SharePick.MakeImage, story = SharePick.MakeImage, email = SharePick.MakeImage,
                blocked = reason, canRetry = true,
            ),
        )

        composeRule.onNodeWithText(reason).assertIsDisplayed()
        composeRule.onNodeWithText("보낼 것", substring = true).assertDoesNotExist()
        for (label in listOf("페이스북", "인스타그램", "스토리", "저장")) button(label).assertIsNotEnabled()
        for (label in listOf("이메일", "복사", "더 보기")) button(label).assertIsEnabled()
        button("페이스북").performClick()
        assertTrue(pressed.isEmpty())

        button("다시 시도").performClick()
        assertEquals(1, retries)
    }

    /** An unfinished trip (no image can be made) dims only the buttons whose pick is an image to make. */
    @Test
    fun unfinishedTripDimsOnlyImageButtons() {
        show(state(tripFinished = false, blocked = ShareViewModel.MSG_UNFINISHED))

        button("스토리").assertIsNotEnabled()
        for (label in listOf("페이스북", "인스타그램", "저장", "이메일")) button(label).assertIsEnabled()
        composeRule.onNodeWithText("다시 시도").assertDoesNotExist()
    }

    @Test
    fun makingShowsProgressAndCancel() {
        show(state(making = true))

        composeRule.onNodeWithText("요약 이미지를 만드는 중…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ShareTags.PROGRESS).assertExists()
        for (label in LABELS) composeRule.onNodeWithText(label).assertDoesNotExist()
        composeRule.onNodeWithText("취소").performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun moodChipToggles() {
        var current by mutableStateOf(state(moods = setOf(Mood.SANGKWAE)))
        show({ current })

        composeRule.onNodeWithContentDescription("감정: 상쾌").assertIsSelected()
        composeRule.onNodeWithContentDescription("감정: 신남").assertIsNotSelected()
        composeRule.onNodeWithText("😄 신남").performClick()
        assertEquals(listOf(Mood.SINNAM), moodsPressed)

        current = current.copy(moods = setOf(Mood.SANGKWAE, Mood.SINNAM))
        composeRule.onNodeWithContentDescription("감정: 신남").assertIsSelected()
    }

    @Test
    fun resetButton() {
        var current by mutableStateOf(state())
        show({ current })
        composeRule.onNodeWithText("처음 글로").assertIsNotEnabled()

        current = current.copy(text = "$auto\n고친 글")
        composeRule.onNodeWithText("처음 글로").assertIsEnabled().performClick()
        assertEquals(1, resets)
    }

    @Test
    fun typingEdits() {
        show(state(text = "글"))

        composeRule.onNodeWithTag(ShareTags.TEXT).performTextInput("자")

        // Where the cursor starts is the field's business; the typed text reaches the view model.
        assertTrue(edits.last() in setOf("자글", "글자"))
    }

    @Test
    fun staleLine() {
        var current by mutableStateOf(state(stale = true))
        show({ current })
        composeRule.onNodeWithText("기록이 바뀌어 숫자가 다를 수 있습니다").assertIsDisplayed()

        current = current.copy(stale = false)
        composeRule.onNodeWithText("기록이 바뀌어 숫자가 다를 수 있습니다").assertDoesNotExist()
    }

    @Test
    fun fileLinesShown() {
        show(state())

        composeRule.onNodeWithText("보낼 것: 영상 · 가로 · 12 MB").assertIsDisplayed()
        composeRule.onNodeWithText("스토리: 요약 이미지 · 세로 (누르면 만듭니다)").assertIsDisplayed()
        // E-mail sends what the others send: no line of its own.
        composeRule.onNodeWithText("이메일:", substring = true).assertDoesNotExist()
    }

    /** Download/bikeRecoderTest/v150-share-sheet.png: the app icons of this phone where it has the apps. */
    @Test
    fun screenshotSheet() {
        show(state(moods = setOf(Mood.SANGKWAE), text = "$auto #상쾌"), screenshotApps())
        screenshot("v150-share-sheet")
    }

    /** Download/bikeRecoderTest/v150-share-making.png. */
    @Test
    fun screenshotMaking() {
        show(state(making = true), screenshotApps())
        screenshot("v150-share-making")
    }

    /** Every button shown; the icons are this phone's own where it has Facebook or Instagram. */
    private fun screenshotApps(): ShareApps {
        val real = PackageShareApps(context)
        return FakeApps(icons = real::icon)
    }

    /** The whole screen (the sheet is its own window) into Download/bikeRecoderTest/[name].png. */
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "ShareSheetTest"
        val LABELS = listOf("페이스북", "인스타그램", "스토리", "이메일", "복사", "저장", "더 보기")
    }
}
