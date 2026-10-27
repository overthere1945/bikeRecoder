package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 끝 장면 요약 카드의 자리(스펙 §2.4): 아래쪽, 세로 2칸·가로 4칸. 카메라가 그 위에 경로를 맞춘다. */
class OutroCardLayoutTest {

    @Test
    fun portraitCardHasTwoColumnsAndSitsLow() {
        // 1 unit = 3 px; 7 cells → 4 rows: (56 + 4·48 + 30)·3 = 834 high, (48 + 2·140 − 20)·3 = 924 wide, 24·3 above the bottom.
        assertEquals(Box(78f, 1014f, 924f, 834f), OutroCardLayout.card(1080, 1920, 7))
        assertEquals(2, OutroCardLayout.columns(1080, 1920))
        // 5 cells (no heart rate, no calories) → 3 rows.
        assertEquals(Box(78f, 1158f, 924f, 690f), OutroCardLayout.card(1080, 1920, 5))
    }

    @Test
    fun landscapeCardHasFourColumns() {
        assertEquals(4, OutroCardLayout.columns(1920, 1080))
        // 7 cells → 2 rows: (56 + 2·48 + 30)·3 = 546 high, (48 + 4·140 − 20)·3 = 1764 wide.
        assertEquals(Box(78f, 462f, 1764f, 546f), OutroCardLayout.card(1920, 1080, 7))
    }

    @Test
    fun cardScalesWithThe720Fallback() {
        val full = OutroCardLayout.card(1080, 1920, 6)
        val small = OutroCardLayout.card(720, 1280, 6)
        assertEquals(full.x * 2 / 3, small.x, 1e-3f)
        assertEquals(full.y * 2 / 3, small.y, 1e-3f)
        assertEquals(full.w * 2 / 3, small.w, 1e-3f)
        assertEquals(full.h * 2 / 3, small.h, 1e-3f)
        assertEquals(2f, OutroCardLayout.unit(720, 1280))
    }
}
