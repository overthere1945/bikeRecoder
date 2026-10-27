package com.cowork.bikerecoder.core.post

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RideColorsTest {

    @Test
    fun tenDistinctColoursInOrder() {
        val expected = listOf(
            "#E53935", "#1E88E5", "#43A047", "#8E24AA", "#FB8C00",
            "#00ACC1", "#D81B60", "#3949AB", "#6D4C41", "#7CB342",
        )
        assertEquals(expected, RideColors.PALETTE)
        assertEquals(10, RideColors.PALETTE.distinct().size)
        expected.forEachIndexed { i, c -> assertEquals(c, RideColors.colorAt(i)) }
    }

    @Test
    fun eleventhRepeatsFirst() {
        assertEquals(RideColors.colorAt(0), RideColors.colorAt(10))
        assertEquals(RideColors.colorAt(3), RideColors.colorAt(23))
    }
}
