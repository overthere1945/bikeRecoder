package com.cowork.bikerecoder.ui.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which bars of the chart get their value written above them. */
class BarChartTest {

    private fun bar(value: Double, short: String = if (value > 0) value.toString() else "", upcoming: Boolean = false) =
        BarState("", value, "", upcoming, short)

    /** A text is as wide as ten pixels a character. */
    private val width: (String) -> Float = { it.length * 10f }

    @Test
    fun everyBarWithAValueWhenFewAndItFits() {
        val bars = listOf(bar(0.0), bar(3.5), bar(12.0), bar(1.0, upcoming = true).copy(shortText = ""))
        assertEquals(setOf(1, 2), valueLabelIndices(bars, null, 50f, width))
    }

    @Test
    fun aTextThatDoesNotFitItsColumnIsLeftOut() {
        val bars = listOf(bar(3.5, "3.5"), bar(12.0, "12.0"))
        // 30 px for "3.5", 40 px for "12.0": a 35 px column holds the first only.
        assertEquals(setOf(0), valueLabelIndices(bars, null, 35f, width))
        assertEquals(emptySet<Int>(), valueLabelIndices(bars, null, 20f, width))
    }

    @Test
    fun manyBarsWriteTheTallestAndTheSelectedOnly() {
        val bars = List(24) { bar(if (it == 5) 9.0 else 1.0, "v$it") }
        assertEquals(setOf(5, 20), valueLabelIndices(bars, 20, 15f, width))
        assertEquals(setOf(5), valueLabelIndices(bars, null, 15f, width))
        assertEquals(setOf(5), valueLabelIndices(bars, 5, 15f, width))
    }

    @Test
    fun manyBarsTakeTheFirstOfEqualTallest() {
        val bars = List(13) { bar(if (it == 3 || it == 8) 9.0 else 1.0, "9") }
        assertEquals(setOf(3), valueLabelIndices(bars, null, 15f, width))
    }

    @Test
    fun manyBarsMayReachIntoTheNeighbours() {
        val bars = List(13) { bar(if (it == 3) 9.0 else 1.0, "24.1") } // 40 px in a 15 px column
        assertEquals(setOf(3), valueLabelIndices(bars, null, 15f, width))
        // Wider than three columns: left out.
        assertEquals(emptySet<Int>(), valueLabelIndices(bars, null, 10f, width))
    }

    @Test
    fun theSelectedWinsWhenItTouchesTheTallest() {
        val bars = List(13) { bar(if (it == 3) 9.0 else 1.0, "24.1") }
        assertEquals(setOf(4), valueLabelIndices(bars, 4, 15f, width))
        assertEquals(setOf(3, 6), valueLabelIndices(bars, 6, 15f, width))
    }

    @Test
    fun manyEmptyBarsWriteNothing() {
        val bars = List(13) { bar(0.0) }
        assertEquals(emptySet<Int>(), valueLabelIndices(bars, null, 15f, width))
    }
}
