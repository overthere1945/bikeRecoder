package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class CumulativeSummaryTest {

    private fun entry(id: Long, date: LocalDate, distanceM: Double, movingMs: Long) =
        CumulativeEntry(id, date, distanceM, movingMs, listOf(GeoPoint(37.0, 127.0)))

    private val entries = listOf(
        entry(1, LocalDate.of(2025, 12, 31), 10_000.0, 3_600_000),
        entry(2, LocalDate.of(2026, 1, 5), 20_000.0, 7_200_000),
        entry(3, LocalDate.of(2026, 1, 20), 5_000.0, 1_800_000),
        entry(4, LocalDate.of(2026, 3, 2), 30_000.0, 5_400_000),
    )

    private fun ids(l: List<CumulativeEntry>) = l.map { it.segmentId }

    @Test
    fun filterByYear() {
        assertEquals(listOf(2L, 3L, 4L), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2026))))
        assertEquals(listOf(1L), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2025))))
        assertEquals(emptyList(), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2024))))
    }

    @Test
    fun filterByYearAndMonth() {
        assertEquals(listOf(2L, 3L), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 1))))
        assertEquals(listOf(4L), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 3))))
        assertEquals(emptyList(), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 2))))
    }

    @Test
    fun noFilterKeepsAll() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(CumulativeSummary.filter(entries, PeriodFilter())))
    }

    @Test
    fun totalsSum() {
        val t = CumulativeSummary.totals(entries)
        assertEquals(65_000.0, t.distanceM, 1e-9)
        assertEquals(4, t.rides)
        assertEquals(18_000_000L, t.movingTimeMs)
        assertEquals(CumulativeTotals(0.0, 0, 0L), CumulativeSummary.totals(emptyList()))
    }

    @Test
    fun yearsDescending() {
        assertEquals(listOf(2026, 2025), CumulativeSummary.years(entries))
        assertEquals(emptyList(), CumulativeSummary.years(emptyList()))
    }

    @Test
    fun monthsOfYear() {
        assertEquals(listOf(1, 3), CumulativeSummary.months(entries, 2026))
        assertEquals(listOf(12), CumulativeSummary.months(entries, 2025))
        assertEquals(emptyList(), CumulativeSummary.months(entries, 2024))
    }

    @Test
    fun monthWithoutYearIgnored() {
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            ids(CumulativeSummary.filter(entries, PeriodFilter(year = null, month = 3))),
        )
    }
}
