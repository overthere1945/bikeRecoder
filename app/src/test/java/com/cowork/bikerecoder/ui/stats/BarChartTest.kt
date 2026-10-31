package com.cowork.bikerecoder.ui.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which values and axis names of the chart are written. */
class BarChartTest {

    private fun bar(value: Double, short: String = if (value > 0) value.toString() else "", upcoming: Boolean = false) =
        BarState("", value, "", upcoming, short)

    /** A text is as wide as ten pixels a character; the gap is 4 px. */
    private val width: (String) -> Float = { it.length * 10f }
    private val gap = 4f

    private fun values(bars: List<BarState>, selected: Int?, column: Float) = valueLabelIndices(bars, selected, column, gap, width)

    @Test
    fun everyBarWithAValueWhenTheyAreApart() {
        val bars = listOf(bar(0.0), bar(3.5, "3.5"), bar(12.0, "12"), bar(1.0, upcoming = true).copy(shortText = ""))
        assertEquals(setOf(1, 2), values(bars, null, 50f))
    }

    @Test
    fun aTextMayReachPastItsColumnIfNothingIsInTheWay() {
        // "12:30" is 50 px in a 30 px column: written, its neighbours being empty.
        val bars = listOf(bar(0.0), bar(5.0, "12:30"), bar(0.0), bar(0.0), bar(0.0), bar(0.0), bar(0.0))
        assertEquals(setOf(1), values(bars, null, 30f))
        // 35 px of the second column reaches the edge of the chart when it is the first one.
        val atEdge = listOf(bar(5.0, "12:30"), bar(0.0))
        assertEquals(emptySet<Int>(), values(atEdge, null, 30f))
    }

    @Test
    fun theTallestWinsBeforeTheOnesOnItsLeft() {
        // Three 30 px texts in 20 px columns: 0 and 1 touch, 1 and 2 touch; the tallest (1) is kept, 0 and 2 are not.
        val bars = listOf(bar(1.0, "a12"), bar(9.0, "b12"), bar(2.0, "c12"), bar(0.0), bar(0.0))
        assertEquals(setOf(1), values(bars, null, 20f))
    }

    @Test
    fun theSelectedWinsBeforeTheTallest() {
        val bars = listOf(bar(1.0, "a12"), bar(9.0, "b12"), bar(2.0, "c12"), bar(0.0), bar(0.0))
        assertEquals(setOf(2), values(bars, 2, 20f))
        // Without a text on the tallest the other two (40 px columns: 10 px apart is plenty) are both written.
        assertEquals(setOf(0, 2), values(listOf(bar(1.0, "a12"), bar(9.0, ""), bar(2.0, "c12"), bar(0.0), bar(0.0)), null, 40f))
    }

    @Test
    fun gapIsKept() {
        // Two 20 px texts, centres 24 px apart: 4 px between them, exactly the gap, and written; 23 px apart is not.
        val bars = listOf(bar(1.0, "ab"), bar(2.0, "cd"))
        assertEquals(setOf(0, 1), values(bars, null, 24f))
        assertEquals(setOf(1), values(bars, null, 23f))
    }

    @Test
    fun manyBarsWriteTheTallestAndTheSelectedOnly() {
        val bars = List(24) { bar(if (it == 5) 9.0 else 1.0, "v$it") }
        assertEquals(setOf(5, 20), values(bars, 20, 15f))
        assertEquals(setOf(5), values(bars, null, 15f))
        assertEquals(setOf(5), values(bars, 5, 15f))
    }

    @Test
    fun manyBarsTakeTheFirstOfEqualTallest() {
        val bars = List(13) { bar(if (it == 3 || it == 8) 9.0 else 1.0, "9") }
        assertEquals(setOf(3), values(bars, null, 15f))
    }

    @Test
    fun manyBarsTheSelectedWinsWhenItTouchesTheTallest() {
        val bars = List(13) { bar(if (it == 3) 9.0 else 1.0, "24.1") }
        assertEquals(setOf(4), values(bars, 4, 15f))
        assertEquals(setOf(6, 3), values(bars, 6, 15f))
        // A text wider than the chart is not written.
        assertEquals(emptySet<Int>(), values(List(13) { bar(1.0, "x".repeat(40)) }, null, 15f))
    }

    @Test
    fun manyEmptyBarsWriteNothing() {
        assertEquals(emptySet<Int>(), values(List(13) { bar(0.0) }, null, 15f))
    }

    // Axis names.

    private val monthly12 = listOf("’25.11", "12월", "’26.1", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월")

    private fun names(labels: List<String>, selected: Int?, column: Float, w: (String) -> Float = width): Set<Int> =
        axisLabelIndices(labels, selected, labels.indices.filter { '’' in labels[it] }.toSet(), column, gap, w)

    @Test
    fun twelveMonthlyNamesAllFitWhenTheyAreNarrow() {
        // Real widths at 1x (about 5.5 px per dp-character, 27 dp columns): everything is written.
        assertEquals(monthly12.indices.toSet(), names(monthly12, null, 60f) { it.length * 8f })
    }

    @Test
    fun twelveMonthlyNamesKeepTheYearOnesAndDropTheCrowded() {
        // 10 px a character in 30 px columns: "’25.11" (60 px) reaches into the next column; "12월" (30 px) would touch it.
        val kept = names(monthly12, null, 30f)
        assertTrue(0 in kept && 2 in kept, "the names with a year are kept: $kept")
        assertTrue(1 !in kept, "12월 touches the first name: $kept")
        assertTrue(3 !in kept, "2월 touches ’26.1: $kept")
        // Every kept pair is apart by the gap.
        val extents = kept.sorted().map { (it + 0.5f) * 30f to width(monthly12[it]) }
        for ((a, b) in extents.zipWithNext()) assertTrue(b.first - b.second / 2 >= a.first + a.second / 2 + gap)
    }

    @Test
    fun twentyFourMonthlyNames() {
        val labels = List(24) { i ->
            val month = (10 + i) % 12 + 1
            val year = 2024 + (10 + i) / 12
            if (i == 0 || month == 1) "’${year % 100}.$month" else "${month}월"
        }
        val kept = names(labels, null, 15f)
        // The first name goes first; January 2025 (two columns on) would touch it and is left out; January 2026 is written.
        assertTrue(0 in kept)
        assertTrue(2 !in kept, "${labels[2]}")
        assertTrue(14 in kept, "${labels[14]}")
        val sorted = kept.sorted()
        for ((a, b) in sorted.zipWithNext()) {
            val aRight = (a + 0.5f) * 15f + width(labels[a]) / 2
            val bLeft = (b + 0.5f) * 15f - width(labels[b]) / 2
            assertTrue(bLeft >= aRight + gap, "${labels[a]} and ${labels[b]} touch")
        }
    }

    @Test
    fun theSelectedNameIsAlwaysKept() {
        val kept = names(monthly12, 1, 30f)
        assertTrue(1 in kept)
        // It wins against the first name it touches.
        assertTrue(0 !in kept)
        // Whatever is selected, in any width.
        for (w in listOf(1f, 10f, 40f)) for (i in monthly12.indices) assertTrue(i in names(monthly12, i, 30f) { it.length * w })
    }

    @Test
    fun withoutYearNamesTheFirstGoesFirstThenLeftToRight() {
        val labels = listOf("월", "화", "수", "목", "금", "토", "일")
        // 10 px names in 12 px columns: 2 px apart, below the gap: every other one.
        assertEquals(setOf(0, 2, 4, 6), names(labels, null, 12f))
        assertEquals(setOf(1, 3, 5), names(labels, 1, 12f))
    }
}
