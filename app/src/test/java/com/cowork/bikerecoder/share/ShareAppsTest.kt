package com.cowork.bikerecoder.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which Instagram activity is the feed and which the story share screen (V1.5.0 final review I1). */
class ShareAppsTest {
    private val pkg = "com.instagram.android"
    private val feed = "com.instagram.share.handleractivity.ShareHandlerActivity"
    private val reels = "com.instagram.share.handleractivity.ClipsShareHandlerActivity"
    private val story = "com.instagram.share.handleractivity.StoryShareHandlerActivity"
    private val direct = "com.instagram.direct.share.handler.DirectShareHandlerActivity"
    private val directStory = "com.instagram.share.handleractivity.DirectStoryShareHandlerActivity"

    private fun r(className: String, exported: Boolean = true) = InstagramReceiver(pkg, className, exported)

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
}
