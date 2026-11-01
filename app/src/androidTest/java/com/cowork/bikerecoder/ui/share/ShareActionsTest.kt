package com.cowork.bikerecoder.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.share.MSG_INSTAGRAM_ONE
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareFile
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.share.msgEmailTrimmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the share sheet's events do (V1.5.0 spec §1.2, §3.6, §4.2; share cards spec §5): files as `ACTION_SEND` or
 * `ACTION_SEND_MULTIPLE`, what a button leaves out told with the paste hint, long notices. Nothing is launched at any
 * app: [launched] keeps it.
 */
@RunWith(AndroidJUnit4::class)
class ShareActionsTest {
    private val text = "저녁 라이딩 · 서울 강서구\n2026년 10월 25일 (일)\n#자전거 #라이딩"
    private val cards = listOf(
        ShareFile("content://com.cowork.bikerecoder.share/7/satellite.jpg", "image/jpeg", 400_000L),
        ShareFile("content://com.cowork.bikerecoder.share/7/photo-card.jpg", "image/jpeg", 600_000L),
        ShareFile("content://com.cowork.bikerecoder.share/7/slide-21.jpg", "image/jpeg", 500_000L),
    )

    private val launched = mutableListOf<Intent>()
    private val clips = mutableListOf<ClipData>()
    private val told = mutableListOf<Pair<String, Boolean>>()
    private var closes = 0

    private class Apps(private val multiple: Boolean = false) : ShareApps {
        override fun available(target: ShareTarget) = true
        override fun instagramFeed() = null
        override fun instagramStory() = null
        override fun instagramTakesMultiple() = multiple
        override fun icon(target: ShareTarget): Drawable? = null
    }

    private fun actions(
        sdk: Int = Build.VERSION.SDK_INT,
        apps: ShareApps = Apps(),
        launch: (Intent) -> Unit = { launched += it },
    ) = ShareActions(
        apps = apps,
        launch = launch,
        clip = { clips += it },
        tell = { message, long -> told += message to long },
        close = { closes++ },
        sdk = sdk,
    )

    private fun ClipData.text() = getItemAt(0).text.toString()

    private fun Intent.stream(): Uri? = @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun Intent.streams(): List<Uri> =
        @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.toList()

    @Test
    fun facebookCopiesTheTextFirstThenOpensAndCloses() {
        actions().handle(ShareEvent.Send(ShareTarget.FACEBOOK, cards.take(1), text))

        assertEquals(listOf("활동" to text), clips.map { it.description.label.toString() to it.text() })
        assertEquals(listOf(MSG_TEXT_COPIED_PASTE to false), told)
        val intent = launched.single()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.facebook.katana", intent.`package`)
        assertEquals("image/jpeg", intent.type)
        assertEquals(Uri.parse(cards[0].uri), intent.stream())
        assertEquals(1, closes)
    }

    /** Spec §5.1: several files go as one `ACTION_SEND_MULTIPLE` in the strip's order. */
    @Test
    fun multipleFilesLaunchSendMultiple() {
        actions().handle(ShareEvent.Send(ShareTarget.FACEBOOK, cards, text))
        actions().handle(ShareEvent.Send(ShareTarget.MORE, cards, text))

        val (facebook, more) = launched
        assertEquals(Intent.ACTION_SEND_MULTIPLE, facebook.action)
        assertEquals("com.facebook.katana", facebook.`package`)
        assertEquals(cards.map { Uri.parse(it.uri) }, facebook.streams())
        assertEquals(Intent.ACTION_CHOOSER, more.action)
        @Suppress("DEPRECATION")
        val inner = more.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, inner.action)
        assertEquals(cards.map { Uri.parse(it.uri) }, inner.streams())
        // Only Facebook takes the paste hint.
        assertEquals(listOf(MSG_TEXT_COPIED_PASTE to false), told)
        assertEquals(2, closes)
    }

    /** Spec §5.3: an Instagram that takes one image gets the first; the notice and the paste hint are told together. */
    @Test
    fun instagramSingleOnlyTells() {
        actions(apps = Apps(multiple = false)).handle(ShareEvent.Send(ShareTarget.INSTAGRAM, cards, text))

        val intent = launched.single()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.instagram.android", intent.`package`)
        assertEquals(Uri.parse(cards[0].uri), intent.stream())
        assertEquals(listOf("$MSG_INSTAGRAM_ONE\n$MSG_TEXT_COPIED_PASTE" to true), told)
        assertEquals(1, clips.size)
        assertEquals(1, closes)
    }

    @Test
    fun instagramMultipleTakesAll() {
        actions(apps = Apps(multiple = true)).handle(ShareEvent.Send(ShareTarget.INSTAGRAM, cards, text))

        val intent = launched.single()
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals(cards.map { Uri.parse(it.uri) }, intent.streams())
        assertEquals(listOf(MSG_TEXT_COPIED_PASTE to false), told)
    }

    /** Spec §5.4: files are dropped from the end to stay within 20 MB, and that is told; nothing goes to the clipboard. */
    @Test
    fun emailTrimTells() {
        val big = listOf(
            ShareFile("content://com.cowork.bikerecoder.share/7/a.jpg", "image/jpeg", 15L * 1024 * 1024),
            ShareFile("content://com.cowork.bikerecoder.share/7/b.jpg", "image/jpeg", 10L * 1024 * 1024),
        )
        actions().handle(ShareEvent.Send(ShareTarget.EMAIL, big, text))

        val intent = launched.single()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(Uri.parse(big[0].uri), intent.stream())
        assertEquals("mailto:", intent.selector!!.data.toString())
        assertEquals(listOf(msgEmailTrimmed(1) to true), told)
        assertTrue(clips.isEmpty())
        assertEquals(1, closes)
    }

    /** Spec §2.5: e-mail with the text only; nothing goes to the clipboard. */
    @Test
    fun emailWithTextOnly() {
        actions().handle(ShareEvent.Send(ShareTarget.EMAIL, emptyList(), text))

        assertTrue(clips.isEmpty())
        assertTrue(told.isEmpty())
        val intent = launched.single()
        assertEquals("text/plain", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("저녁 라이딩 · 서울 강서구", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(1, closes)
    }

    @Test
    fun noAppSaysSoAndTheSheetStays() {
        actions(launch = { throw ActivityNotFoundException() }).handle(ShareEvent.Send(ShareTarget.MORE, cards, text))

        assertEquals(listOf(MSG_NO_APP to false), told)
        assertEquals(0, closes)
    }

    /** An Instagram activity that is not open to the app throws on launch: the same message, the sheet stays. */
    @Test
    fun securityExceptionReadsAsNoApp() {
        actions(launch = { throw SecurityException("not exported") })
            .handle(ShareEvent.Send(ShareTarget.INSTAGRAM, cards.take(1), text))

        assertEquals(listOf(MSG_TEXT_COPIED_PASTE to false, MSG_NO_APP to false), told)
        assertEquals(0, closes)
    }

    /** Spec §3.6: label "활동"; "복사했습니다" below Android 13 only (the phone tells it from 13 on). The sheet stays. */
    @Test
    fun copyPutsTheTextOnTheClipboard() {
        actions(sdk = Build.VERSION_CODES.TIRAMISU).handle(ShareEvent.Copy(text))
        assertEquals(listOf("활동" to text), clips.map { it.description.label.toString() to it.text() })
        assertTrue(told.isEmpty())

        actions(sdk = Build.VERSION_CODES.S_V2).handle(ShareEvent.Copy(text))
        assertEquals(listOf(MSG_COPIED to false), told)
        assertTrue(launched.isEmpty())
        assertEquals(0, closes)
    }

    @Test
    fun noticeIsTold() {
        actions().handle(ShareEvent.Notice("카드 1장을 갤러리에 저장했습니다"))

        assertEquals(listOf("카드 1장을 갤러리에 저장했습니다" to false), told)
        assertEquals(0, closes)
    }

    /** Spec §5.6: the sticker's notice is shown for longer. */
    @Test
    fun stickerNoticeIsLong() {
        actions().handle(ShareEvent.Notice(ShareViewModel.MSG_STICKER_SAVED, long = true))

        assertEquals(listOf(ShareViewModel.MSG_STICKER_SAVED to true), told)
        assertEquals(0, closes)
    }
}
