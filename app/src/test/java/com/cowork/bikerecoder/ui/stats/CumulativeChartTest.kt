package com.cowork.bikerecoder.ui.stats

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The scale and the end bubble of the cumulative chart. */
class CumulativeChartTest {

    @Test
    fun theTopTickIsTheMaxPlusFivePercentRoundedUpToTheNextStep() {
        assertEquals(1.0, niceTop(0.0), 0.0)
        assertEquals(200.0, niceTop(186.0), 0.0) // 195.3
        assertEquals(250.0, niceTop(205.0), 0.0) // 215.25
        assertEquals(12.0, niceTop(9.6), 0.0) // 10.08
        assertEquals(1.0, niceTop(0.9), 0.0) // 0.945
        assertEquals(1.2, niceTop(1.0), 0.0) // 1.05 (exactly the first step above it)
        assertEquals(60.0, niceTop(50.0), 0.0) // 52.5
        assertEquals(0.0025, niceTop(0.002), 0.0) // 0.0021
        assertEquals(1_000.0, niceTop(950.0), 0.0) // 997.5 -> 1000 (10 x 100)
        assertEquals(3_000.0, niceTop(2_800.0), 0.0) // 2940
    }

    @Test
    fun theTopTickNeverFallsBelowTheMax() {
        for (max in listOf(0.01, 0.5, 1.0, 1.7, 2.0, 4.5, 9.09, 9.1, 45.4, 90.9, 99.0, 454.5, 909.0, 7.6, 8.4)) {
            val top = niceTop(max)
            assertTrue(top >= max * 1.05 - 1e-9, "$max -> $top")
        }
    }

    @Test
    fun theLabelsOfThreeTicksAreNeverTheSame() {
        for (max in listOf(0.0, 0.0004, 0.002, 0.7, 1.0, 5.0, 9.6, 12.0, 186.0, 205.0, 2_800.0)) {
            for (metric in StatMetric.entries) {
                val top = niceTop(max).let { if (metric == StatMetric.TIME) timeTop(it) else it }
                val labels = tickTexts(metric, top)
                assertEquals(3, labels.toSet().size, "$metric $max -> $labels")
            }
        }
    }

    @Test
    fun distanceAndAscentTicksAreExactDecimals() {
        assertEquals(listOf("0", "100", "200"), tickTexts(StatMetric.DISTANCE, 200.0))
        assertEquals(listOf("0", "1.25", "2.5"), tickTexts(StatMetric.DISTANCE, 2.5))
        assertEquals(listOf("0", "0.6", "1.2"), tickTexts(StatMetric.ASCENT, 1.2))
        assertEquals(listOf("0", "0.00125", "0.0025"), tickTexts(StatMetric.DISTANCE, 0.0025))
        assertEquals(listOf("0", "500", "1,000"), tickTexts(StatMetric.DISTANCE, 1_000.0))
    }

    @Test
    fun timeTicksAreHoursAndMinutes() {
        assertEquals(listOf("0:00", "1:15", "2:30"), tickTexts(StatMetric.TIME, 2.5))
        assertEquals(listOf("0:00", "0:15", "0:30"), tickTexts(StatMetric.TIME, 0.5))
        assertEquals(listOf("0:00", "5:00", "10:00"), tickTexts(StatMetric.TIME, 10.0))
        // Under two minutes there would be no minute between the ticks: the scale is never smaller than that.
        assertEquals(2.0 / 60, timeTop(niceTop(0.0004)), 1e-12)
        assertEquals(listOf("0:00", "0:01", "0:02"), tickTexts(StatMetric.TIME, timeTop(niceTop(0.0004))))
        assertEquals(2.0, timeTop(niceTop(1.6)), 0.0)
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
