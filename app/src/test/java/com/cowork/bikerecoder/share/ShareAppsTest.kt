package com.cowork.bikerecoder.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which Instagram activity is the feed and which the story share screen (V1.5.0 final review I1), and which Facebook's
 * post screen.
 */
class ShareAppsTest {
    private val pkg = "com.instagram.android"
    private val feed = "com.instagram.share.handleractivity.ShareHandlerActivity"
    private val reels = "com.instagram.share.handleractivity.ClipsShareHandlerActivity"
    private val story = "com.instagram.share.handleractivity.StoryShareHandlerActivity"
    private val direct = "com.instagram.direct.share.handler.DirectShareHandlerActivity"
    private val directStory = "com.instagram.share.handleractivity.DirectStoryShareHandlerActivity"

    private fun r(className: String, exported: Boolean = true) = ShareReceiver(pkg, className, exported)

    @Test
    fun feedPrefersShareHandlerActivityInAnyOrder() {
        val all = listOf(r(reels), r(story), r(direct), r(feed))
        assertEquals(feed, pickInstagramFeed(all)?.className)
        assertEquals(feed, pickInstagramFeed(all.reversed())?.className)
        assertEquals(feed, pickInstagramFeed(listOf(r(feed), r(reels)))?.className)
        assertEquals(feed, pickInstagramFeed(listOf(r(reels), r(feed)))?.className)
    }

    @Test
    fun feedWithoutExactNameTakesFirstByNameThatIsNeitherStoryNorDirect() {
        val all = listOf(r(story), r(direct), r("com.instagram.x.ReelsShare"), r("com.instagram.x.AShare"))
        assertEquals("com.instagram.x.AShare", pickInstagramFeed(all)?.className)
        assertEquals("com.instagram.x.AShare", pickInstagramFeed(all.reversed())?.className)
    }

    @Test
    fun feedExcludesDirectAndStory() {
        assertNull(pickInstagramFeed(listOf(r(story), r(direct), r(directStory))))
        assertNull(pickInstagramFeed(emptyList()))
    }

    @Test
    fun storyExcludesDirectStoryNames() {
        assertEquals(story, pickInstagramStory(listOf(r(directStory), r(feed), r(story)))?.className)
        assertEquals(story, pickInstagramStory(listOf(r(story), r(directStory)))?.className)
        assertNull(pickInstagramStory(listOf(r(feed), r(directStory), r(direct))))
    }

    @Test
    fun nonExportedAreDropped() {
        assertEquals(reels, pickInstagramFeed(listOf(r(feed, exported = false), r(reels)))?.className)
        assertNull(pickInstagramFeed(listOf(r(feed, exported = false))))
        assertNull(pickInstagramStory(listOf(r(story, exported = false))))
    }

    @Test
    fun matchingIgnoresCase() {
        assertNull(pickInstagramFeed(listOf(r("a.b.storyShare"), r("a.b.DIRECTShare"))))
        assertEquals("a.b.STORYShare", pickInstagramStory(listOf(r("a.b.STORYShare")))?.className)
        assertNull(pickInstagramStory(listOf(r("a.b.STORYdirect"))))
    }

    /** The `ACTION_SEND_MULTIPLE` `image/jpeg` receivers of Instagram on the S22, in package-manager order. */
    private val s22Multiple = listOf(
        "com.instagram.share.handleractivity.ShareHandlerActivityMultipleFeedAlias",
        "com.instagram.share.handleractivity.ReelShareHandlerActivity",
        "com.instagram.share.handleractivity.MultiStoryShareHandlerActivity",
    )

    /** The `ACTION_SEND` `image/jpeg` receivers of Instagram on the S22, in package-manager order. */
    private val s22Single = listOf(
        "com.instagram.direct.share.handler.DirectExternalMediaShareActivityPhoto",
        "com.instagram.share.handleractivity.ShareHandlerActivity",
        "com.instagram.share.handleractivity.StoryShareHandlerActivity",
        "com.instagram.share.handleractivity.ReelShareHandlerActivity",
    )

    @Test
    fun s22MultipleFeedIsTheMultipleFeedAliasNotTheReel() {
        assertEquals(
            "com.instagram.share.handleractivity.ShareHandlerActivityMultipleFeedAlias",
            pickInstagramFeed(s22Multiple.map { r(it) })?.className,
        )
        assertEquals(
            "com.instagram.share.handleractivity.ShareHandlerActivityMultipleFeedAlias",
            pickInstagramFeed(s22Multiple.reversed().map { r(it) })?.className,
        )
    }

    @Test
    fun s22SingleFeedIsShareHandlerActivity() {
        assertEquals(feed, pickInstagramFeed(s22Single.map { r(it) })?.className)
        assertEquals(story, pickInstagramStory(s22Single.map { r(it) })?.className)
    }

    @Test
    fun feedNeverPicksAReel() {
        val reel = "com.instagram.share.handleractivity.ReelShareHandlerActivity"
        assertNull(pickInstagramFeed(listOf(r(reel), r(story))))
        assertNull(pickInstagramStory(listOf(r(reel))))
    }

    /** Facebook 585's receivers on the S25 in package-manager order: `ACTION_SEND` `image/png`, `ACTION_SEND_MULTIPLE`. */
    private val fb = "com.facebook.katana"
    private val fbPost = "com.facebook.composer.shareintent.ImplicitShareIntentHandlerDefaultAlias"
    private val s25FacebookSingle = listOf(
        "com.facebook.composer.shareintent.ShareToGroupsAlias",
        fbPost,
        "com.facebook.inspiration.fbshorts.shareintent.InpirationFbShortsSharePhotoAlias",
        "com.facebook.inspiration.platformsharing.activity.InpirationCameraShareDefaultAlias",
        "com.facebook.timeline.stagingground.Fb4aProfilePictureShareActivityAlias",
    )
    private val s25FacebookMultiple = listOf(
        "com.facebook.composer.shareintent.ShareToGroupsAlias",
        fbPost,
        "com.facebook.inspiration.fbshorts.shareintent.InpirationFbShortsShareMultipleAlias",
        "com.facebook.inspiration.platformsharing.activity.InpirationCameraShareMultipleAlias",
    )

    private fun f(className: String, exported: Boolean = true) = ShareReceiver(fb, className, exported)

    @Test
    fun s25FacebookPostIsTheImplicitShareHandlerNotGroups() {
        assertEquals(fbPost, pickFacebookFeed(s25FacebookSingle.map { f(it) })?.className)
        assertEquals(fbPost, pickFacebookFeed(s25FacebookSingle.reversed().map { f(it) })?.className)
        assertEquals(fbPost, pickFacebookFeed(s25FacebookMultiple.map { f(it) })?.className)
    }

    @Test
    fun facebookWithoutThePostHandlerIsNull() {
        assertNull(pickFacebookFeed(s25FacebookMultiple.filter { it != fbPost }.map { f(it) }))
        assertNull(pickFacebookFeed(listOf(f(fbPost, exported = false))))
        assertNull(pickFacebookFeed(emptyList()))
    }
}
