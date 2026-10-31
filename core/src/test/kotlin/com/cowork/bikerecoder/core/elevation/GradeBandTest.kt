package com.cowork.bikerecoder.core.elevation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GradeBandTest {

    @Test
    fun boundaries() {
        assertEquals(GradeBand.STEEP_DOWN, GradeBand.of(-6.01))
        assertEquals(GradeBand.FLAT, GradeBand.of(-6.0))
        assertEquals(GradeBand.FLAT, GradeBand.of(2.0))
        assertEquals(GradeBand.UP_2, GradeBand.of(2.01))
        assertEquals(GradeBand.UP_2, GradeBand.of(4.0))
        assertEquals(GradeBand.UP_4, GradeBand.of(4.01))
        assertEquals(GradeBand.UP_4, GradeBand.of(6.0))
        assertEquals(GradeBand.UP_6, GradeBand.of(6.01))
        assertEquals(GradeBand.UP_6, GradeBand.of(8.0))
        assertEquals(GradeBand.UP_8, GradeBand.of(8.01))
        assertEquals(GradeBand.UP_8, GradeBand.of(10.0))
        assertEquals(GradeBand.UP_10, GradeBand.of(10.01))
    }

    @Test
    fun legendsAndColorsMatchSpec() {
        val expected = listOf(
            Triple(GradeBand.STEEP_DOWN, 0xFF4FC3F7, "-6% 미만"),
            Triple(GradeBand.FLAT, 0xFF1E63D6, "평지·완만"),
            Triple(GradeBand.UP_2, 0xFFF9C80E, "2%"),
            Triple(GradeBand.UP_4, 0xFFF57C00, "4%"),
            Triple(GradeBand.UP_6, 0xFFE53935, "6%"),
            Triple(GradeBand.UP_8, 0xFFA31515, "8%"),
            Triple(GradeBand.UP_10, 0xFF7B1FA2, "10%"),
        )
        assertEquals(expected.map { it.first }, GradeBand.entries)
        for ((band, argb, legend) in expected) {
            assertEquals(argb, band.argb, band.name)
            assertEquals(legend, band.legend, band.name)
        }
    }
}
