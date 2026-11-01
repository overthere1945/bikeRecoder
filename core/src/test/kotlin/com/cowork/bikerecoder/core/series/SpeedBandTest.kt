package com.cowork.bikerecoder.core.series

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SpeedBandTest {

    @Test
    fun boundaries() {
        assertEquals(SpeedBand.SLOW, SpeedBand.of(0.0))
        assertEquals(SpeedBand.SLOW, SpeedBand.of(9.99))
        assertEquals(SpeedBand.EASY, SpeedBand.of(10.0))
        assertEquals(SpeedBand.EASY, SpeedBand.of(14.99))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(15.0))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(19.99))
        assertEquals(SpeedBand.FAST, SpeedBand.of(20.0))
        assertEquals(SpeedBand.FAST, SpeedBand.of(24.99))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(25.0))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(80.0))
    }

    @Test
    fun hexAndLegend() {
        assertEquals(
            listOf(
                Triple(SpeedBand.SLOW, "#90CAF9", "10 미만"),
                Triple(SpeedBand.EASY, "#26A69A", "10~15"),
                Triple(SpeedBand.STEADY, "#66BB6A", "15~20"),
                Triple(SpeedBand.FAST, "#FFA726", "20~25"),
                Triple(SpeedBand.SPRINT, "#E53935", "25 이상"),
            ),
            SpeedBand.entries.map { Triple(it, it.hex, it.legend) },
        )
        assertEquals(0xFF90CAF9, SpeedBand.SLOW.argb)
        assertEquals(0xFFE53935, SpeedBand.SPRINT.argb)
    }
}
