package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShareCardLayoutTest {

    private fun overlaps(a: Box, b: Box) =
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun inside(inner: Box, outer: Box) =
        inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.w <= outer.x + outer.w && inner.y + inner.h <= outer.y + outer.h

    @Test
    fun feedBandsAndMapDoNotOverlap() {
        val size = CardSize.FEED
        val boxes = ShareCardLayout.satellite(size)
        assertEquals(Box(0f, 0f, 1080f, 1350f * 0.13f), boxes.top)
        assertEquals(Box(0f, 1350f * 0.76f, 1080f, 1350f * 0.24f), boxes.bottom)
        assertTrue(!overlaps(boxes.top, boxes.bottom))
        assertTrue(!overlaps(boxes.top, boxes.map))
        assertTrue(!overlaps(boxes.bottom, boxes.map))
        assertTrue(inside(boxes.map, Box(0f, 0f, 1080f, 1350f)))
    }

    @Test
    fun storyKeepsTextOutOfSafeZones() {
        val size = CardSize.STORY
        val low = size.height * ShareCardLayout.STORY_SAFE
        val high = size.height * (1 - ShareCardLayout.STORY_SAFE)
        for (boxes in listOf(ShareCardLayout.satellite(size), ShareCardLayout.photoCard(size))) {
            for (b in listOf(boxes.top, boxes.bottom, boxes.map)) {
                assertTrue(b.y >= low - 0.01f && b.y + b.h <= high + 0.01f, "안전 구역 밖 $b")
            }
        }
        val sat = ShareCardLayout.satellite(size)
        assertEquals(low, sat.top.y, 0.01f)
        assertEquals(high, sat.bottom.y + sat.bottom.h, 0.01f)
        assertEquals(size.height * 0.09f, sat.top.h, 0.01f)
        assertEquals(size.height * 0.20f, sat.bottom.h, 0.01f)
    }

    @Test
    fun mapInsideBetweenBands() {
        for (size in listOf(CardSize.FEED, CardSize.STORY)) {
            val b = ShareCardLayout.satellite(size)
            val inset = ShareCardLayout.MAP_INSET
            assertEquals(size.width * inset, b.map.x, 0.01f)
            assertEquals(size.width * (1 - 2 * inset), b.map.w, 0.01f)
            assertEquals(b.top.y + b.top.h + size.height * inset, b.map.y, 0.01f)
            assertEquals(b.bottom.y - size.height * inset, b.map.y + b.map.h, 0.01f)
            assertTrue(b.map.w > 0f && b.map.h > 0f)
        }
    }

    @Test
    fun photoCardMiniMapIsASquareInsideTheBottomBand() {
        for (size in listOf(CardSize.FEED, CardSize.STORY)) {
            val b = ShareCardLayout.photoCard(size)
            assertEquals(ShareCardLayout.satellite(size).bottom, b.bottom)
            assertEquals(0f, b.top.h)
            assertEquals(b.bottom.h * 0.6f, b.map.w, 0.01f)
            assertEquals(b.map.w, b.map.h, 0.01f)
            assertTrue(inside(b.map, b.bottom))
            // Left-aligned with a margin, vertically centred in the band.
            assertTrue(b.map.x > b.bottom.x)
            assertEquals(b.bottom.y + b.bottom.h / 2, b.map.y + b.map.h / 2, 0.01f)
        }
    }

    @Test
    fun stickerHasNoBands() {
        assertFailsWith<IllegalArgumentException> { ShareCardLayout.satellite(CardSize.STICKER) }
        assertFailsWith<IllegalArgumentException> { ShareCardLayout.photoCard(CardSize.STICKER) }
    }

    @Test
    fun coverCropsPortraitCentre() {
        // Landscape source into a portrait card: full height, centre 2400 px of width.
        assertEquals(Box(800f, 0f, 2400f, 3000f), ShareCardLayout.cover(4000, 3000, 1080, 1350))
        // Tall source: full width, centre crop of the height.
        assertEquals(Box(0f, 500f, 3000f, 3000f), ShareCardLayout.cover(3000, 4000, 1080, 1080))
        // Same aspect: the whole source.
        assertEquals(Box(0f, 0f, 2160f, 2700f), ShareCardLayout.cover(2160, 2700, 1080, 1350))
    }

    @Test
    fun fitWidthLandscape() {
        assertEquals(Box(0f, 270f, 1080f, 810f), ShareCardLayout.fitWidth(4000, 3000, 1080, 1350))
    }
}
