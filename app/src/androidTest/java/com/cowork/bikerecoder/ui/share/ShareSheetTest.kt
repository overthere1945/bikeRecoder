package com.cowork.bikerecoder.ui.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.share.DEFAULT_SHARE_HASHTAGS
import com.cowork.bikerecoder.share.Mood
import com.cowork.bikerecoder.share.PackageShareApps
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareFacts
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode
import com.cowork.bikerecoder.share.ShareSelection
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.autoShareText
import com.cowork.bikerecoder.share.cards.CardKind
import com.cowork.bikerecoder.share.shareItems
import com.cowork.bikerecoder.share.toggleTag
import com.cowork.bikerecoder.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The "활동 공유하기" sheet (share cards spec §1; V1.5.0 spec §1.2) in the real bottom sheet, with fake app lookups and
 * pictures: the mode row and its buttons, the strip (numbers, removing, long press to the preview, no photo card without
 * photos, cards dimmed on an unfinished trip), "지나간 길" chips, dimming with nothing picked, the hidden buttons, the
 * making row, the error line, mood chips, [처음 글로], the stale line.
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
        listOf("#한강자전거길"),
    )

    private val video = ShareCandidate("content://media/external/video/media/42", MediaKind.VIDEO, Aspect.LANDSCAPE, 1L, 12L * 1024 * 1024)

    /**
     * Trip 7, finished, with [photos] (ids) and no video unless [withVideo]; the mode's first selection; both feed cards
     * drawn ([cardsOf]).
     */
    private fun state(
        text: String = auto,
        mode: ShareMode = ShareMode.FEED,
        photos: List<Long> = listOf(21, 22),
        withVideo: Boolean = false,
        tripFinished: Boolean = true,
        chosen: List<ShareItem>? = null,
        placeTags: List<String> = emptyList(),
        waiting: Boolean = false,
        error: String? = null,
        stale: Boolean = false,
        moods: Set<Mood> = emptySet(),
    ): ShareSheetState {
        val items = shareItems(photos, video.takeIf { withVideo })
        val initial = ShareSelection.initial(mode, items, tripFinished)
        return ShareSheetState(
            tripId = 7,
            text = text,
            autoText = auto,
            stale = stale,
            moods = moods,
            mode = mode,
            items = items,
            selection = chosen?.let { initial.copy(chosen = it) } ?: initial,
            cards = if (tripFinished) cardsOf(mode, photos.firstOrNull()) else emptyMap(),
            photoCardMediaId = photos.firstOrNull(),
            placeTags = placeTags,
            tripFinished = tripFinished,
            waiting = waiting,
            error = error,
        )
    }

    private fun cardsOf(mode: ShareMode, background: Long?): Map<CardKey, CardStatus> = buildMap {
        put(CardKey(CardKind.SATELLITE, mode, null), CardStatus.Ready(cardFile("satellite-$mode")))
        if (background != null) put(CardKey(CardKind.PHOTO, mode, background), CardStatus.Ready(cardFile("photo-$mode-$background")))
    }

    private fun cardFile(name: String) = ShareFile("content://com.cowork.bikerecoder.share/7/$name.jpg", "image/jpeg", 300_000)

    private class FakeApps(
        private val missing: Set<ShareTarget> = emptySet(),
        private val icons: (ShareTarget) -> Drawable? = { null },
    ) : ShareApps {
        override fun available(target: ShareTarget) = target !in missing
        override fun instagramFeed() = null
        override fun instagramStory() = null
        override fun instagramTakesMultiple() = false
        override fun mailPackages() = emptyList<String>()
        override fun icon(target: ShareTarget): Drawable? = icons(target)
    }

    private val pressed = mutableListOf<ShareTarget>()
    private val edits = mutableListOf<String>()
    private val moodsPressed = mutableListOf<Mood>()
    private val photoCards = mutableListOf<Long>()
    private val thumbnailsAsked = mutableListOf<ShareItem>()
    private val previewsAsked = mutableListOf<ShareItem>()
    private var resets = 0
    private var cancels = 0
    private var retries = 0
    private var dismissals = 0

    /** The sheet over [current]; taps, modes and place chips change it as the view model would. */
    private var current by mutableStateOf(state())

    private fun show(state: ShareSheetState = current, apps: ShareApps = FakeApps()) {
        current = state
        composeRule.setContent {
            MaterialTheme {
                ShareSheet(
                    state = current,
                    apps = apps,
                    thumbnail = { item, _ ->
                        thumbnailsAsked += item
                        ImageBitmap(72, 72)
                    },
                    preview = { item, _, _ ->
                        previewsAsked += item
                        // Photo 21 is wide, photo 22 upright; the cards are 4:5.
                        when {
                            item is ShareItem.Photo && item.mediaId == 21L -> ImageBitmap(160, 90)
                            item is ShareItem.Photo -> ImageBitmap(90, 160)
                            else -> ImageBitmap(108, 135)
                        }
                    },
                    onEdit = { edits += it },
                    onReset = { resets++ },
                    onMood = { moodsPressed += it },
                    onPress = { pressed += it },
                    onMode = { mode ->
                        current = current.copy(
                            mode = mode,
                            selection = ShareSelection.initial(mode, current.items, current.tripFinished),
                            cards = current.cards + cardsOf(mode, current.photoCardMediaId),
                        )
                    },
                    onTap = { item -> current = current.copy(selection = current.selection.toggle(item).first) },
                    onPhotoCard = { photoCards += it },
                    onPlace = { tag -> current = current.copy(text = toggleTag(current.text, tag)) },
                    onCancelMaking = { cancels++ },
                    onRetry = { retries++ },
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    private fun button(label: String) = composeRule.onNodeWithText(label).performScrollTo()

    private fun item(i: Int) = composeRule.onNodeWithTag(ShareTags.item(i))

    @Test
    fun allButtonsWhenAppsPresent() {
        show()

        composeRule.onNodeWithText("활동 공유하기").assertIsDisplayed()
        for (label in FEED_LABELS) button(label).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("스토리").assertDoesNotExist()
        button("복사").performClick()
        button("페이스북").performClick()
        button("스티커").performClick()
        assertEquals(listOf(ShareTarget.COPY, ShareTarget.FACEBOOK, ShareTarget.STICKER), pressed)
    }

    /** Spec §1.3: the first row's second button follows the mode. */
    @Test
    fun modeSwapsInstagramAndStory() {
        show()
        composeRule.onNodeWithTag(ShareTags.MODE_FEED).assertIsSelected()
        button("인스타그램").assertIsDisplayed()
        composeRule.onNodeWithText("스토리").assertDoesNotExist()

        composeRule.onNodeWithTag(ShareTags.MODE_STORY).performClick()

        composeRule.onNodeWithTag(ShareTags.MODE_STORY).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.MODE_FEED).assertIsNotSelected()
        button("스토리").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("인스타그램").assertDoesNotExist()
        for (label in listOf("페이스북", "이메일", "더 보기", "복사", "저장", "스티커")) button(label).assertIsDisplayed()
        // Story: the satellite card alone, marked with a check (its name still says it is picked).
        item(0).assert(hasContentDescription("위성 카드, 1번째"))
        item(2).assert(hasContentDescription("사진 1"))

        composeRule.onNodeWithText("스토리").performClick()
        assertEquals(listOf(ShareTarget.STORY), pressed)
    }

    /** Spec §1.2: numbered in pick order; a tap on a picked item takes it out and the ones after it move up. */
    @Test
    fun stripNumbersAndRemoves() {
        show()
        composeRule.onNodeWithText("보낼 것").assertIsDisplayed()
        item(0).assert(hasContentDescription("위성 카드, 1번째")).assertIsSelected()
        item(1).assert(hasContentDescription("사진 카드")).assertIsNotSelected()
        item(2).assert(hasContentDescription("사진 1, 2번째"))
        item(3).assert(hasContentDescription("사진 2, 3번째"))

        item(0).performClick()

        item(0).assert(hasContentDescription("위성 카드")).assertIsNotSelected()
        item(2).assert(hasContentDescription("사진 1, 1번째"))
        item(3).assert(hasContentDescription("사진 2, 2번째"))

        item(1).performClick()
        item(1).assert(hasContentDescription("사진 카드, 3번째")).assertIsSelected()
    }

    /** A long press opens the preview at full size; the photo card's photo row picks its background; [닫기] closes. */
    @Test
    fun longPressOpensPreview() {
        show()

        item(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(ShareTags.PREVIEW).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("위성 카드").assertIsDisplayed()
        composeRule.onNodeWithText("닫기").performClick()
        composeRule.onNodeWithTag(ShareTags.PREVIEW).assertDoesNotExist()
        // A long press does not pick.
        item(0).assert(hasContentDescription("위성 카드, 1번째"))

        item(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(ShareTags.PREVIEW).assertIsDisplayed()
        val inPreview = hasAnyAncestor(hasTestTag(ShareTags.PREVIEW))
        composeRule.onNode(hasContentDescription("사진 1") and inPreview).assertIsSelected()
        composeRule.onNode(hasContentDescription("사진 2") and inPreview).assertIsNotSelected().performClick()
        assertEquals(listOf(22L), photoCards)
        composeRule.onNodeWithText("닫기").performClick()

        assertEquals(listOf<ShareItem>(ShareItem.SatelliteCard, ShareItem.PhotoCard), previewsAsked)
        assertTrue(pressed.isEmpty())
    }

    /** Spec §1.2: a photo is previewed as it is sent, in a frame of the mode's card shape (wide or upright). */
    @Test
    fun photoPreviewHasTheModesShape() {
        show()

        item(2).performTouchInput { longClick() }
        assertPreviewShape(1080f / 1350)
        composeRule.onNodeWithContentDescription("사진 1").assertIsDisplayed()
        composeRule.onNodeWithText("닫기").performClick()

        composeRule.onNodeWithTag(ShareTags.MODE_STORY).performClick()
        item(3).performTouchInput { longClick() }
        assertPreviewShape(1080f / 1920)
        composeRule.onNodeWithText("닫기").performClick()

        // A card is shown as its file: no frame.
        item(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(ShareTags.PREVIEW_FRAME).assertDoesNotExist()
    }

    /** A short space (a small screen, landscape): the preview shrinks to fit and keeps its shape, nothing cut off. */
    @Test
    fun previewFitsAShortSpace() {
        var item: ShareItem by mutableStateOf(ShareItem.Photo(22, 2))
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(360.dp, 400.dp).testTag(CONTAINER)) {
                    SharePreviewPicture(
                        item = item,
                        state = state(mode = ShareMode.STORY),
                        preview = { shown, _, _ -> if (shown is ShareItem.Photo) ImageBitmap(90, 160) else ImageBitmap(108, 192) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val container = composeRule.onNodeWithTag(CONTAINER).getUnclippedBoundsInRoot()

        assertFitsInside(composeRule.onNodeWithTag(ShareTags.PREVIEW_FRAME).assertIsDisplayed(), 1080f / 1920, container)

        item = ShareItem.SatelliteCard
        assertFitsInside(composeRule.onNodeWithContentDescription("위성 카드").assertIsDisplayed(), 108f / 192, container)
    }

    private fun assertFitsInside(node: SemanticsNodeInteraction, widthOverHeight: Float, container: DpRect) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertEquals(widthOverHeight, (bounds.right - bounds.left) / (bounds.bottom - bounds.top), 0.01f)
        val slack = 0.5.dp
        assertTrue("$bounds in $container", bounds.left >= container.left - slack && bounds.right <= container.right + slack)
        assertTrue("$bounds in $container", bounds.top >= container.top - slack && bounds.bottom <= container.bottom + slack)
    }

    private fun assertPreviewShape(widthOverHeight: Float) {
        val bounds = composeRule.onNodeWithTag(ShareTags.PREVIEW_FRAME).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(widthOverHeight, (bounds.right - bounds.left) / (bounds.bottom - bounds.top), 0.01f)
    }

    /** Spec §1.2: no photo card without photos; the video ends the strip. */
    @Test
    fun noPhotoCardWithoutPhotos() {
        show(state(photos = emptyList(), withVideo = true))

        item(0).assert(hasContentDescription("위성 카드, 1번째"))
        item(1).assert(hasContentDescription("영상"))
        item(2).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("사진 카드", substring = true).assertDoesNotExist()
    }

    /** Spec §4.5: a chip is on while its tag is in the text; a tap adds or takes it away. */
    @Test
    fun placeChipTogglesText() {
        show(state(placeTags = listOf("#한강자전거길", "#북한산")))

        composeRule.onNodeWithText("지나간 길").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ShareTags.place("#한강자전거길")).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.place("#북한산")).assertIsNotSelected().performClick()

        composeRule.onNodeWithTag(ShareTags.place("#북한산")).assertIsSelected()
        composeRule.onNodeWithTag(ShareTags.TEXT).assert(hasText("#북한산", substring = true))

        composeRule.onNodeWithTag(ShareTags.place("#한강자전거길")).performClick()
        composeRule.onNodeWithTag(ShareTags.place("#한강자전거길")).assertIsNotSelected()
    }

    @Test
    fun noPlaceRowWithoutTags() {
        show(state(placeTags = emptyList()))

        composeRule.onNodeWithText("지나간 길").assertDoesNotExist()
    }

    /** Spec §1.3: with nothing picked the app buttons and [저장] are dimmed; [이메일] and [더 보기] send the text. */
    @Test
    fun nothingChosenDimsButtons() {
        show(state(chosen = emptyList()))

        for (label in listOf("페이스북", "인스타그램", "저장")) button(label).assertIsNotEnabled()
        for (label in listOf("이메일", "더 보기", "복사", "스티커")) button(label).assertIsEnabled()
        button("페이스북").performClick()
        button("저장").performClick()
        assertTrue(pressed.isEmpty())

        button("이메일").performClick()
        assertEquals(listOf(ShareTarget.EMAIL), pressed)
    }

    /** Spec §1.3: an unfinished trip says why over the strip; its cards and [스티커] are dimmed, photos can be sent. */
    @Test
    fun unfinishedTripLine() {
        show(state(tripFinished = false))

        composeRule.onNodeWithText("여행을 마치면 카드를 만들 수 있습니다").assertIsDisplayed()
        item(0).assert(hasContentDescription("위성 카드")).assertIsNotEnabled()
        item(1).assert(hasContentDescription("사진 카드")).assertIsNotEnabled()
        item(2).assert(hasContentDescription("사진 1, 1번째")).assertIsEnabled()
        button("스티커").assertIsNotEnabled()
        button("저장").assertIsNotEnabled()
        button("페이스북").assertIsEnabled()

        item(0).performClick()
        item(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(ShareTags.PREVIEW).assertDoesNotExist()
        item(0).assert(hasContentDescription("위성 카드"))
    }

    /** Each picture is asked once while the sheet is open, not again on every change. */
    @Test
    fun picturesLoadedOnce() {
        show()
        composeRule.waitForIdle()
        assertEquals(current.items.toSet(), thumbnailsAsked.toSet())
        val asked = thumbnailsAsked.size

        current = current.copy(text = "$auto\n고친 글")
        item(0).performClick()
        composeRule.waitForIdle()

        assertEquals(asked, thumbnailsAsked.size)
    }

    @Test
    fun missingAppsHidden() {
        show(state(), FakeApps(missing = setOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.STORY)))

        for (label in listOf("페이스북", "인스타그램")) composeRule.onNodeWithText(label).assertDoesNotExist()
        for (label in listOf("이메일", "더 보기", "복사", "저장", "스티커")) button(label).assertIsDisplayed()
    }

    @Test
    fun missingMailAppHidesEmail() {
        show(state(), FakeApps(missing = setOf(ShareTarget.EMAIL)))

        composeRule.onNodeWithText("이메일").assertDoesNotExist()
        button("페이스북").assertIsDisplayed()
    }

    /** Spec §6: the error in place, [다시 시도] calls retry. */
    @Test
    fun errorLineRetries() {
        show(state(error = ShareViewModel.MSG_CARD_FAILED))

        composeRule.onNodeWithText("카드를 만들지 못했습니다").performScrollTo().assertIsDisplayed()
        button("다시 시도").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun makingShowsProgressAndCancel() {
        show(state(waiting = true))

        composeRule.onNodeWithText("카드를 만드는 중…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ShareTags.PROGRESS).assertExists()
        for (label in FEED_LABELS) composeRule.onNodeWithText(label).assertDoesNotExist()
        composeRule.onNodeWithText("취소").performScrollTo().performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun moodChipToggles() {
        show(state(moods = setOf(Mood.SANGKWAE)))

        composeRule.onNodeWithContentDescription("감정: 상쾌").assertIsSelected()
        composeRule.onNodeWithContentDescription("감정: 신남").assertIsNotSelected()
        composeRule.onNodeWithText("😄 신남").performClick()
        assertEquals(listOf(Mood.SINNAM), moodsPressed)

        current = current.copy(moods = setOf(Mood.SANGKWAE, Mood.SINNAM))
        composeRule.onNodeWithContentDescription("감정: 신남").assertIsSelected()
    }

    @Test
    fun resetButton() {
        show()
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
        show(state(stale = true))
        composeRule.onNodeWithText("기록이 바뀌어 숫자가 다를 수 있습니다").assertIsDisplayed()

        current = current.copy(stale = false)
        composeRule.onNodeWithText("기록이 바뀌어 숫자가 다를 수 있습니다").assertDoesNotExist()
    }

    /** Download/bikeRecoderTest/v1100-share-sheet.png: the app icons of this phone where it has the apps. */
    @Test
    fun screenshotSheet() {
        show(state(moods = setOf(Mood.SANGKWAE), text = "$auto #상쾌", placeTags = listOf("#한강자전거길")), screenshotApps())
        screenshot("v1100-share-sheet")
    }

    /** Download/bikeRecoderTest/v1100-share-making.png. */
    @Test
    fun screenshotMaking() {
        show(state(waiting = true), screenshotApps())
        screenshot("v1100-share-making")
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
        const val CONTAINER = "short-container"
        val FEED_LABELS = listOf("페이스북", "인스타그램", "이메일", "더 보기", "복사", "저장", "스티커")
    }
}
