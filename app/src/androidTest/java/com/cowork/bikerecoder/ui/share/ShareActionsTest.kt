package com.cowork.bikerecoder.ui.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.share.ShareApps
import com.cowork.bikerecoder.share.ShareCandidate
import com.cowork.bikerecoder.share.ShareTarget
import com.cowork.bikerecoder.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the share sheet's events do (V1.5.0 spec §1.2, §3.6, §4.2). Nothing is launched at any app: [launched] keeps it. */
@RunWith(AndroidJUnit4::class)
class ShareActionsTest {
    private val text = "저녁 라이딩 · 서울 강서구\n2026년 10월 25일 (일)\n#자전거 #라이딩"
    private val image = ShareCandidate("content://media/external/images/media/9", MediaKind.IMAGE, Aspect.PORTRAIT, 1L, null)

    private val launched = mutableListOf<Intent>()
    private val clips = mutableListOf<ClipData>()
    private val told = mutableListOf<String>()
    private var resultsSeen = 0
    private var closes = 0

    private object Apps : ShareApps {
        override fun available(target: ShareTarget) = true
        override fun instagramFeed() = null
        override fun instagramStory() = null
        override fun icon(target: ShareTarget): Drawable? = null
    }

    private fun actions(sdk: Int = Build.VERSION.SDK_INT, launch: (Intent) -> Unit = { launched += it }) = ShareActions(
        apps = Apps,
        launch = launch,
        clip = { clips += it },
        tell = { told += it },
        resultSeen = { resultsSeen++ },
        close = { closes++ },
        sdk = sdk,
    )

    private fun ClipData.text() = getItemAt(0).text.toString()

    @Test
    fun facebookCopiesTheTextFirstThenOpensAndCloses() {
        actions().handle(ShareEvent.Send(ShareTarget.FACEBOOK, image, text))

        assertEquals(listOf("활동" to text), clips.map { it.description.label.toString() to it.text() })
        assertEquals(listOf(MSG_TEXT_COPIED_PASTE), told)
        val intent = launched.single()
        assertEquals("com.facebook.katana", intent.`package`)
        assertEquals("image/png", intent.type)
        assertEquals(Uri.parse(image.uri), @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, closes)
        assertEquals(0, resultsSeen)
    }

    @Test
    fun instagramAndStoryCopyTooAndAMadeImageTakesTheResultAway() {
        actions().handle(ShareEvent.Send(ShareTarget.STORY, image, text, made = true))
        actions().handle(ShareEvent.Send(ShareTarget.INSTAGRAM, image, text))

        assertEquals(2, clips.size)
        assertEquals(listOf(MSG_TEXT_COPIED_PASTE, MSG_TEXT_COPIED_PASTE), told)
        assertTrue(launched.all { it.`package` == "com.instagram.android" })
        assertEquals(1, resultsSeen)
        assertEquals(2, closes)
    }

    /** Spec §2.5: e-mail with the text only; nothing goes to the clipboard. */
    @Test
    fun emailWithTextOnly() {
        actions().handle(ShareEvent.Send(ShareTarget.EMAIL, null, text))

        assertTrue(clips.isEmpty())
        assertTrue(told.isEmpty())
        val intent = launched.single()
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("저녁 라이딩 · 서울 강서구", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(1, closes)
    }

    @Test
    fun noAppSaysSoAndTheSheetStays() {
        actions(launch = { throw ActivityNotFoundException() }).handle(ShareEvent.Send(ShareTarget.MORE, image, text))

        assertEquals(listOf(MSG_NO_APP), told)
        assertEquals(0, closes)
    }

    /** Spec §3.6: label "활동"; "복사했습니다" below Android 13 only (the phone tells it from 13 on). The sheet stays. */
    @Test
    fun copyPutsTheTextOnTheClipboard() {
        actions(sdk = Build.VERSION_CODES.TIRAMISU).handle(ShareEvent.Copy(text))
        assertEquals(listOf("활동" to text), clips.map { it.description.label.toString() to it.text() })
        assertTrue(told.isEmpty())

        actions(sdk = Build.VERSION_CODES.S_V2).handle(ShareEvent.Copy(text))
        assertEquals(listOf(MSG_COPIED), told)
        assertTrue(launched.isEmpty())
        assertEquals(0, closes)
    }

    @Test
    fun noticeIsTold() {
        actions().handle(ShareEvent.Notice("갤러리에 저장했습니다"))

        assertEquals(listOf("갤러리에 저장했습니다"), told)
        assertEquals(0, closes)
    }
}
