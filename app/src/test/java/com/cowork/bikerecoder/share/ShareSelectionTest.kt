package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 공유 항목 고르기(스펙 §1.2). */
class ShareSelectionTest {

    private val video = ShareCandidate("v", MediaKind.VIDEO, Aspect.LANDSCAPE, 200, 1L)
    private val sat = ShareItem.SatelliteCard

    private fun photo(n: Int) = ShareItem.Photo(mediaId = n * 10L, index = n)

    private fun photos(n: Int) = (1..n).map { photo(it) }

    private fun feed(vararg chosen: ShareItem) = ShareSelection(ShareMode.FEED, chosen.toList())

    @Test
    fun itemsListCardsFirstThenPhotosThenVideo() {
        assertEquals(
            listOf(sat, ShareItem.PhotoCard, ShareItem.Photo(7, 1), ShareItem.Photo(3, 2), ShareItem.Video(video)),
            shareItems(listOf(7L, 3L), video),
        )
    }

    @Test
    fun photosGoneMeansSatelliteOnly() {
        assertEquals(listOf(sat), shareItems(emptyList(), null))
    }

    @Test
    fun tapNumbersInOrder() {
        var s = feed()
        for (n in listOf(3, 1, 2)) s = s.toggle(photo(n)).first
        assertEquals(listOf(photo(3), photo(1), photo(2)), s.chosen)
    }

    @Test
    fun tapAgainRemovesAndRenumbers() {
        val (after, notice) = feed(photo(1), photo(2), photo(3)).toggle(photo(2))
        assertEquals(listOf(photo(1), photo(3)), after.chosen)
        assertNull(notice)
    }

    @Test
    fun eleventhIsRefused() {
        val full = feed(*photos(10).toTypedArray())
        val (after, notice) = full.toggle(photo(11))
        assertEquals(full, after)
        assertEquals(ShareSelection.MSG_MAX, notice)
        assertEquals("최대 10장까지 고를 수 있습니다", notice)
    }

    @Test
    fun tenthIsAccepted() {
        val (after, notice) = feed(*photos(9).toTypedArray()).toggle(photo(10))
        assertEquals(10, after.chosen.size)
        assertNull(notice)
    }

    @Test
    fun videoIsAlone() {
        val v = ShareItem.Video(video)
        val withVideo = feed(sat, photo(1)).toggle(v).first
        assertEquals(listOf<ShareItem>(v), withVideo.chosen)
        assertEquals(listOf<ShareItem>(photo(2)), withVideo.toggle(photo(2)).first.chosen)
        assertEquals(emptyList<ShareItem>(), withVideo.toggle(v).first.chosen)
    }

    @Test
    fun storyKeepsOne() {
        val story = ShareSelection(ShareMode.STORY, listOf(sat))
        val (swapped, notice) = story.toggle(photo(1))
        assertEquals(listOf<ShareItem>(photo(1)), swapped.chosen)
        assertNull(notice)
        assertEquals(listOf<ShareItem>(photo(2)), swapped.toggle(photo(2)).first.chosen)
        assertEquals(emptyList<ShareItem>(), swapped.toggle(photo(1)).first.chosen)
        assertEquals(ShareMode.STORY, swapped.mode)
    }

    @Test
    fun initialFeed() {
        fun initial(n: Int) = ShareSelection.initial(ShareMode.FEED, shareItems((1..n).map { it * 10L }, null), cardsAllowed = true)
        assertEquals(listOf<ShareItem>(sat), initial(0).chosen)
        assertEquals(listOf(sat, photo(1), photo(2), photo(3)), initial(3).chosen)
        assertEquals(listOf(sat, photo(1), photo(2), photo(3), photo(4)), initial(4).chosen)
        assertEquals(listOf(sat, photo(1), photo(4), photo(6), photo(9)), initial(9).chosen)
        assertEquals(ShareMode.FEED, initial(9).mode)
    }

    @Test
    fun initialStory() {
        val items = shareItems(listOf(10L, 20L, 30L), video)
        assertEquals(listOf<ShareItem>(sat), ShareSelection.initial(ShareMode.STORY, items, cardsAllowed = true).chosen)
    }

    @Test
    fun cardsNotAllowed() {
        fun feed(n: Int) = ShareSelection.initial(ShareMode.FEED, shareItems((1..n).map { it * 10L }, null), cardsAllowed = false)
        fun story(n: Int) = ShareSelection.initial(ShareMode.STORY, shareItems((1..n).map { it * 10L }, null), cardsAllowed = false)
        assertEquals(listOf(photo(1), photo(4), photo(6), photo(9)), feed(9).chosen)
        assertEquals(listOf(photo(1), photo(2)), feed(2).chosen)
        assertEquals(emptyList<ShareItem>(), feed(0).chosen)
        assertEquals(listOf<ShareItem>(photo(1)), story(5).chosen)
        assertEquals(emptyList<ShareItem>(), story(0).chosen)
    }
}
