package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryImageLayoutTest {

    private fun overlaps(a: Box, b: Box) =
        a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun ImageLayout.all() = listOf(header, map, footer) + stats + photos

    private fun assertSane(layout: ImageLayout, aspect: Aspect, photoCount: Int) {
        assertEquals(6, layout.stats.size)
        assertEquals(photoCount.coerceIn(0, 3), layout.photos.size)
        val boxes = layout.all()
        boxes.forEach { b ->
            assertTrue(b.w > 0f && b.h > 0f, "$aspect $b")
            assertTrue(b.x >= 0f && b.y >= 0f && b.x + b.w <= aspect.width && b.y + b.h <= aspect.height, "$aspect 화면 밖 $b")
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            assertTrue(!overlaps(boxes[i], boxes[j]), "$aspect 겹침 ${boxes[i]} ${boxes[j]}")
        }
    }

    @Test
    fun layoutPortraitAndLandscape() {
        for (count in -1..5) for (aspect in Aspect.entries) assertSane(SummaryImageLayout.layout(aspect, count), aspect, count)

        // 세로: 위에서부터 머리 → 지도(약 절반) → 숫자 2줄×3칸 → 사진 → 바닥.
        val p = SummaryImageLayout.layout(Aspect.PORTRAIT, 3)
        assertTrue(p.map.h / Aspect.PORTRAIT.height in 0.45f..0.55f, "지도 ${p.map}")
        assertTrue(p.header.y + p.header.h <= p.map.y)
        assertTrue(p.map.y + p.map.h <= p.stats.minOf { it.y })
        assertEquals(2, p.stats.map { it.y }.distinct().size)
        assertEquals(3, p.stats.map { it.x }.distinct().size)
        assertTrue(p.stats.maxOf { it.y + it.h } <= p.photos.minOf { it.y })
        assertTrue(p.photos.maxOf { it.y + it.h } <= p.footer.y)

        // 가로: 왼쪽 60%는 지도, 나머지는 모두 오른쪽 40%.
        val l = SummaryImageLayout.layout(Aspect.LANDSCAPE, 2)
        assertEquals(0f, l.map.x)
        assertEquals(0.6f * Aspect.LANDSCAPE.width, l.map.w)
        (l.all() - l.map).forEach { assertTrue(it.x >= l.map.x + l.map.w, "오른쪽 아님 $it") }
        assertEquals(2, l.stats.map { it.y }.distinct().size)
        assertEquals(3, l.stats.map { it.x }.distinct().size)
    }

    /** The empty space between the last content (stats or photos) and the footer. */
    private fun ImageLayout.footerGap(): Float = footer.y - (stats + photos).maxOf { it.y + it.h }

    /**
     * No photo row: its height goes back to the layout, no large empty band above "bikeRecoder". Portrait: the map grows by
     * that height and the footer gap stays as with photos. Landscape: the right column is centred above the footer.
     */
    @Test
    fun withoutPhotosNothingIsLeftEmptyAboveTheFooter() {
        val portrait = SummaryImageLayout.layout(Aspect.PORTRAIT, 0)
        val portraitWith = SummaryImageLayout.layout(Aspect.PORTRAIT, 3)
        assertTrue(portrait.map.h > portraitWith.map.h, "지도 ${portrait.map} vs ${portraitWith.map}")
        assertTrue(portrait.footerGap() <= 48f, "세로 빈 띠 ${portrait.footerGap()}")
        assertEquals(portraitWith.footerGap(), portrait.footerGap())

        for (count in 0..3) {
            val l = SummaryImageLayout.layout(Aspect.LANDSCAPE, count)
            val top = l.header.y
            val bottomGap = l.footerGap()
            // The block (header … last row) sits in the middle of the column above the footer: the space above it and the
            // space below it differ by at most the gap kept to the footer.
            assertTrue(top >= 48f, "가로 위 $top")
            assertTrue(kotlin.math.abs(bottomGap - (top - 48f) - 24f) <= 1f, "가로 $count: 위 $top 아래 $bottomGap")
        }
        val l0 = SummaryImageLayout.layout(Aspect.LANDSCAPE, 0)
        assertTrue(l0.footerGap() < 300f, "가로 빈 띠 ${l0.footerGap()}")
    }
}
