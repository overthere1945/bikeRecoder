package com.cowork.bikerecoder.ui.stats

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The scale and the end bubble of the cumulative chart. */
class CumulativeChartTest {

    @Test
    fun theTopTickIsTheMaxPlusTenPercentRoundedUpTo125() {
        assertEquals(1.0, niceTop(0.0), 0.0)
        assertEquals(1.0, niceTop(0.9), 0.0) // 0.99
        assertEquals(2.0, niceTop(1.0), 0.0) // 1.1
        assertEquals(5.0, niceTop(2.5), 0.0) // 2.75
        assertEquals(20.0, niceTop(10.0), 0.0) // 11
        assertEquals(50.0, niceTop(30.0), 0.0) // 33
        assertEquals(200.0, niceTop(124.0), 0.0) // 136.4
        assertEquals(2_000.0, niceTop(1_818.0), 0.0) // 1999.8
        assertEquals(0.5, niceTop(0.3), 1e-12) // 0.33
        assertEquals(0.002, niceTop(0.0015), 1e-12)
    }

    @Test
    fun theTopTickNeverFallsBelowTheMax() {
        for (max in listOf(0.01, 0.5, 1.0, 1.7, 2.0, 4.5, 9.09, 9.1, 45.4, 90.9, 99.0, 454.5, 909.0)) {
            val top = niceTop(max)
            assertEquals(true, top >= max * 1.1 - 1e-9, "$max -> $top")
        }
    }

    @Test
    fun tickTextsDropTrailingZeros() {
        assertEquals("0", tickText(0.0))
        assertEquals("25", tickText(25.0))
        assertEquals("2.5", tickText(2.5))
        assertEquals("0.25", tickText(0.25))
        assertEquals("1,000", tickText(1_000.0))
        assertEquals("0.01", tickText(0.005)) // two decimals, half up
    }

    @Test
    fun theBubbleStaysInsideThePlot() {
        // Plot (10, 20)-(110, 100); radius 16.
        fun centre(x: Float, y: Float) = bubbleCentre(Offset(x, y), 16f, 10f, 20f, 110f, 100f)
        assertEquals(Offset(60f, 60f), centre(60f, 60f))
        assertEquals(Offset(26f, 84f), centre(10f, 100f)) // the first day of a period at zero
        assertEquals(Offset(94f, 36f), centre(110f, 20f))
        assertEquals(Offset(94f, 84f), centre(110f, 100f))
    }

    @Test
    fun aBubbleBiggerThanThePlotIsCentredOnIt() {
        assertEquals(Offset(60f, 60f), bubbleCentre(Offset(10f, 100f), 50f, 10f, 20f, 110f, 100f))
    }
}
