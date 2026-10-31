package com.cowork.bikerecoder.share

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only looks at what the intents contain; nothing is launched at any app. */
@RunWith(AndroidJUnit4::class)
class ShareIntentsTest {
    private val uri = "content://media/external/video/media/42"
    private val video = ShareCandidate(uri, MediaKind.VIDEO, Aspect.PORTRAIT, createdAt = 1L, sizeBytes = 1000L)
    private val text = "첫 줄 제목\n둘째 줄\n#자전거"
    private val feed = ComponentName("com.instagram.android", "com.instagram.share.handleractivity.ShareHandlerActivity")
    private val story = ComponentName("com.instagram.android", "com.instagram.share.handleractivity.StoryShareHandlerActivity")

    private class FakeApps(
        private val feed: ComponentName? = null,
        private val story: ComponentName? = null,
    ) : ShareApps {
        override fun available(target: ShareTarget) = true
        override fun instagramFeed() = feed
        override fun instagramStory() = story
        override fun icon(target: ShareTarget): Drawable? = null
    }

    private fun Intent.stream(): Uri? = @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    private fun assertFileBasics(intent: Intent) {
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        assertEquals(Uri.parse(uri), intent.stream())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Uri.parse(uri), intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun facebookTargetsPackage() {
        val intent = ShareIntents.file(ShareTarget.FACEBOOK, video, text, FakeApps())

        assertFileBasics(intent)
        assertEquals("com.facebook.katana", intent.`package`)
        assertNull(intent.component)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun instagramUsesFeedComponent() {
        val intent = ShareIntents.file(ShareTarget.INSTAGRAM, video, text, FakeApps(feed, story))

        assertFileBasics(intent)
        assertEquals(feed, intent.component)
    }

    @Test
    fun instagramFallsBackToInstagramPackage() {
        val intent = ShareIntents.file(ShareTarget.INSTAGRAM, video, text, FakeApps())

        assertFileBasics(intent)
        assertNull(intent.component)
        assertEquals("com.instagram.android", intent.`package`)
    }

    @Test
    fun storyUsesStoryComponent() {
        val intent = ShareIntents.file(ShareTarget.STORY, video, text, FakeApps(feed, story))

        assertFileBasics(intent)
        assertEquals(story, intent.component)
    }

    @Test
    fun storyFallsBackToInstagramPackage() {
        val intent = ShareIntents.file(ShareTarget.STORY, video, text, FakeApps(feed, null))

        assertFileBasics(intent)
        assertNull(intent.component)
        assertEquals("com.instagram.android", intent.`package`)
    }

    @Test
    fun emailHasSelectorSubjectText() {
        val intent = ShareIntents.file(ShareTarget.EMAIL, video, text, FakeApps())

        assertFileBasics(intent)
        assertEquals(Intent.ACTION_SENDTO, intent.selector!!.action)
        assertEquals("mailto:", intent.selector!!.data.toString())
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun moreIsChooserWithTitle() {
        val intent = ShareIntents.file(ShareTarget.MORE, video, text, FakeApps())

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertEquals("활동 공유하기", intent.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertFileBasics(inner)
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        // The chooser itself carries the grant, so the app picked there can read the file.
        assertEquals(Uri.parse(uri), intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun textOnlyHasNoStream() {
        val intent = ShareIntents.textOnly(ShareTarget.FACEBOOK, text)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNull(intent.clipData)
        assertEquals("com.facebook.katana", intent.`package`)
    }

    @Test
    fun textOnlyEmailHasSubjectAndSelector() {
        val intent = ShareIntents.textOnly(ShareTarget.EMAIL, text)

        assertEquals("text/plain", intent.type)
        assertEquals("mailto:", intent.selector!!.data.toString())
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun textOnlyMoreIsChooser() {
        val intent = ShareIntents.textOnly(ShareTarget.MORE, text)

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", inner.type)
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(inner.hasExtra(Intent.EXTRA_STREAM))
    }
}
