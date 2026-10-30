package com.cowork.bikerecoder.ui.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The decoded feed pictures kept in memory (review I3): sized in bytes, least recently used first out; decoded at card size. */
class PictureCacheTest {

    /** A "picture" of [bytes] bytes. */
    private data class Pic(val name: String, val bytes: Int)

    private fun cache(maxBytes: Int) = PictureCache<Pic>(maxBytes) { it.bytes }

    @Test
    fun keepsWhatFitsAndDropsTheLeastRecentlyUsed() {
        val cache = cache(maxBytes = 10)
        cache["a"] = Pic("a", 4)
        cache["b"] = Pic("b", 4)
        // "a" used again: "b" is now the oldest.
        assertEquals(Pic("a", 4), cache["a"])

        cache["c"] = Pic("c", 4)

        assertEquals(Pic("a", 4), cache["a"])
        assertNull(cache["b"])
        assertEquals(Pic("c", 4), cache["c"])
    }

    @Test
    fun aPictureLargerThanTheWholeCacheIsNotKept() {
        val cache = cache(maxBytes = 10)
        cache["a"] = Pic("a", 4)

        cache["big"] = Pic("big", 11)

        assertNull(cache["big"])
        assertEquals(Pic("a", 4), cache["a"])
    }

    @Test
    fun anEighthOfTheHeapAtMost32MB() {
        assertEquals(16 * MB, pictureCacheBytes(128L * MB))
        assertEquals(32 * MB, pictureCacheBytes(256L * MB))
        assertEquals(32 * MB, pictureCacheBytes(512L * MB))
        assertEquals(32 * MB, pictureCacheBytes(Long.MAX_VALUE))
    }

    @Test
    fun sampleSizeKeepsTheCardWidth() {
        // A 1280 px picture on a card 990 px wide (S22) or 690 px wide is read whole.
        assertEquals(1, pictureSampleSize(1280, 990))
        assertEquals(1, pictureSampleSize(1280, 690))
        // Half or a quarter as wide still covers the card.
        assertEquals(2, pictureSampleSize(1280, 640))
        assertEquals(2, pictureSampleSize(1280, 400))
        assertEquals(4, pictureSampleSize(1280, 320))
        // The card's width not known yet: whole.
        assertEquals(1, pictureSampleSize(1280, 0))
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
