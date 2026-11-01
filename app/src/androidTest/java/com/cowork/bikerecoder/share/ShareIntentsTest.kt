package com.cowork.bikerecoder.share

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private val video = listOf(ShareFile(uri, "video/mp4", sizeBytes = 1000L))
    private val text = "첫 줄 제목\n둘째 줄\n#자전거"
    private val feed = ComponentName("com.instagram.android", "com.instagram.share.handleractivity.ShareHandlerActivity")
    private val story = ComponentName("com.instagram.android", "com.instagram.share.handleractivity.StoryShareHandlerActivity")

    private class FakeApps(
        private val feed: ComponentName? = null,
        private val story: ComponentName? = null,
        private val feedMultiple: ComponentName? = null,
        private val mail: List<String> = emptyList(),
    ) : ShareApps {
        override fun available(target: ShareTarget) = true
        override fun instagramFeed() = feed
        override fun instagramStory() = story
        override fun instagramFeedMultiple() = feedMultiple
        override fun instagramTakesMultiple() = feedMultiple != null
        override fun mailPackages() = mail
        override fun icon(target: ShareTarget): Drawable? = null
    }

    private val gmail = "com.google.android.gm"
    private val otherMail = "com.samsung.android.email.provider"

    private fun Intent.stream(): Uri? = @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.inner(): Intent = getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Suppress("DEPRECATION")
    private fun Intent.initial(): List<Intent> =
        getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }

    private fun assertFileBasics(intent: Intent) {
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        assertEquals(Uri.parse(uri), intent.stream())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Uri.parse(uri), intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun facebookTargetsPackage() {
        val intent = ShareIntents.files(ShareTarget.FACEBOOK, video, text, FakeApps())

        assertFileBasics(intent)
        assertEquals("com.facebook.katana", intent.`package`)
        assertNull(intent.component)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun instagramUsesFeedComponent() {
        val intent = ShareIntents.files(ShareTarget.INSTAGRAM, video, text, FakeApps(feed, story))

        assertFileBasics(intent)
        assertEquals(feed, intent.component)
    }

    @Test
    fun instagramFallsBackToInstagramPackage() {
        val intent = ShareIntents.files(ShareTarget.INSTAGRAM, video, text, FakeApps())

        assertFileBasics(intent)
        assertNull(intent.component)
        assertEquals("com.instagram.android", intent.`package`)
    }

    @Test
    fun storyUsesStoryComponent() {
        val intent = ShareIntents.files(ShareTarget.STORY, video, text, FakeApps(feed, story))

        assertFileBasics(intent)
        assertEquals(story, intent.component)
    }

    @Test
    fun storyFallsBackToInstagramPackage() {
        val intent = ShareIntents.files(ShareTarget.STORY, video, text, FakeApps(feed, null))

        assertFileBasics(intent)
        assertNull(intent.component)
        assertEquals("com.instagram.android", intent.`package`)
    }

    /** One mail app: the files go straight to it, no `mailto:` selector (it did not resolve on the S22). */
    @Test
    fun emailWithOneMailAppIsAimedAtIt() {
        val intent = ShareIntents.files(ShareTarget.EMAIL, video, text, FakeApps(mail = listOf(gmail)))

        assertFileBasics(intent)
        assertEquals(gmail, intent.`package`)
        assertNull(intent.selector)
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** Several mail apps: a chooser aimed at the first, the others as initial intents; the chooser carries the grant. */
    @Test
    fun emailWithTwoMailAppsIsChooserOverThem() {
        val intent = ShareIntents.files(ShareTarget.EMAIL, video, text, FakeApps(mail = listOf(gmail, otherMail)))

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertEquals("활동 공유하기", intent.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val inner = intent.inner()
        assertFileBasics(inner)
        assertEquals(gmail, inner.`package`)
        assertNull(inner.selector)
        assertEquals("첫 줄 제목", inner.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        val second = intent.initial().single()
        assertFileBasics(second)
        assertEquals(otherMail, second.`package`)
        assertEquals("첫 줄 제목", second.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, second.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(Uri.parse(uri), intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** No mail app (the button is hidden then): the plain send with subject and text, nothing aimed. */
    @Test
    fun emailWithoutMailAppIsPlainSend() {
        val intent = ShareIntents.files(ShareTarget.EMAIL, video, text, FakeApps())

        assertFileBasics(intent)
        assertNull(intent.`package`)
        assertNull(intent.selector)
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun moreIsChooserWithTitle() {
        val intent = ShareIntents.files(ShareTarget.MORE, video, text, FakeApps())

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
        val intent = ShareIntents.textOnly(ShareTarget.FACEBOOK, text, FakeApps())

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNull(intent.clipData)
        assertEquals("com.facebook.katana", intent.`package`)
    }

    @Test
    fun textOnlyEmailIsAimedAtTheMailApp() {
        val intent = ShareIntents.textOnly(ShareTarget.EMAIL, text, FakeApps(mail = listOf(gmail)))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(gmail, intent.`package`)
        assertNull(intent.selector)
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun textOnlyEmailWithTwoMailAppsIsChooserOverThem() {
        val intent = ShareIntents.textOnly(ShareTarget.EMAIL, text, FakeApps(mail = listOf(gmail, otherMail)))

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        val inner = intent.inner()
        assertEquals("text/plain", inner.type)
        assertEquals(gmail, inner.`package`)
        assertNull(inner.selector)
        assertEquals("첫 줄 제목", inner.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        val second = intent.initial().single()
        assertEquals(otherMail, second.`package`)
        assertEquals(text, second.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun textOnlyMoreIsChooser() {
        val intent = ShareIntents.textOnly(ShareTarget.MORE, text, FakeApps())

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", inner.type)
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(inner.hasExtra(Intent.EXTRA_STREAM))
    }

    private val cards = listOf(
        ShareFile("content://com.cowork.bikerecoder.share/cards/a.jpg", "image/jpeg", 100L),
        ShareFile("content://com.cowork.bikerecoder.share/cards/b.jpg", "image/jpeg", 200L),
        ShareFile("content://com.cowork.bikerecoder.share/cards/c.jpg", "image/jpeg", 300L),
    )

    private fun Intent.streams(): List<Uri> =
        @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.toList()

    @Test
    fun oneFileIsSend() {
        val intent = ShareIntents.files(ShareTarget.FACEBOOK, cards.take(1), text, FakeApps())

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(Uri.parse(cards[0].uri), intent.stream())
        assertEquals(1, intent.clipData!!.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("com.facebook.katana", intent.`package`)
    }

    @Test
    fun manyFilesIsSendMultiple() {
        val intent = ShareIntents.files(ShareTarget.FACEBOOK, cards, text, FakeApps())

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals(cards.map { Uri.parse(it.uri) }, intent.streams())
        assertEquals(3, intent.clipData!!.itemCount)
        assertEquals(cards.map { Uri.parse(it.uri) }, (0 until 3).map { intent.clipData!!.getItemAt(it).uri })
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("com.facebook.katana", intent.`package`)
    }

    @Test
    fun instagramMultipleUsesFeedMultipleComponent() {
        val multi = ComponentName("com.instagram.android", "com.instagram.share.handleractivity.ShareHandlerActivity")
        val intent = ShareIntents.files(ShareTarget.INSTAGRAM, cards, text, FakeApps(feed, story, multi))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals(multi, intent.component)
    }

    @Test
    fun moreWrapsChooser() {
        val intent = ShareIntents.files(ShareTarget.MORE, cards, text, FakeApps())

        assertEquals(Intent.ACTION_CHOOSER, intent.action)
        assertEquals("활동 공유하기", intent.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val inner = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, inner.action)
        assertEquals(cards.map { Uri.parse(it.uri) }, inner.streams())
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        // The chooser itself carries the grant for every card.
        assertEquals(3, intent.clipData!!.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun emailWithManyFilesIsAimedAtTheMailApp() {
        val intent = ShareIntents.files(ShareTarget.EMAIL, cards, text, FakeApps(mail = listOf(gmail)))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals(gmail, intent.`package`)
        assertNull(intent.selector)
        assertEquals("첫 줄 제목", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(cards.map { Uri.parse(it.uri) }, intent.streams())
        assertEquals(3, intent.clipData!!.itemCount)
    }
}
