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
        assertEquals(listOf(4L, 3L, 2L), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2026))))
        assertEquals(listOf(1L), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2025))))
        assertEquals(emptyList(), ids(CumulativeSummary.filter(entries, PeriodFilter(year = 2024))))
    }

    @Test
    fun filterByYearAndMonth() {
        assertEquals(listOf(3L, 2L), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 1))))
        assertEquals(listOf(4L), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 3))))
        assertEquals(emptyList(), ids(CumulativeSummary.filter(entries, PeriodFilter(2026, 2))))
    }

    @Test
    fun noFilterKeepsAll() {
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(CumulativeSummary.filter(entries, PeriodFilter())))
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
    fun monthsAreSortedAscendingForUnorderedInput() {
        val shuffled = listOf(
            entry(1, LocalDate.of(2026, 3, 2), 100.0, 1),
            entry(2, LocalDate.of(2026, 1, 5), 100.0, 1),
            entry(3, LocalDate.of(2026, 3, 9), 100.0, 1),
            entry(4, LocalDate.of(2026, 2, 1), 100.0, 1),
            entry(5, LocalDate.of(2025, 11, 1), 100.0, 1),
        )
        assertEquals(listOf(1, 2, 3), CumulativeSummary.months(shuffled, 2026))
        assertEquals(listOf(2026, 2025), CumulativeSummary.years(shuffled))
    }

    @Test
    fun monthWithoutYearIgnored() {
        assertEquals(
            listOf(4L, 3L, 2L, 1L),
            ids(CumulativeSummary.filter(entries, PeriodFilter(year = null, month = 3))),
        )
    }

    // --- V1.2.1: 최소 거리·주·빠른 선택 ---

    @Test
    fun jitterSegmentsAreNotRides() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 25), 0.27, 1_000),
            entry(2, LocalDate.of(2026, 10, 25), 0.1, 1_000),
            entry(3, LocalDate.of(2026, 10, 25), 0.0, 1_000),
            entry(4, LocalDate.of(2026, 10, 25), 1870.0, 600_000),
            entry(5, LocalDate.of(2026, 10, 25), 1586.7, 900_000),
        )
        assertEquals(listOf(5L, 4L), ids(CumulativeSummary.rides(list)))
        val t = CumulativeSummary.totals(list)
        assertEquals(3456.7, t.distanceM, 1e-9)
        assertEquals(2, t.rides)
        assertEquals(1_500_000L, t.movingTimeMs)
    }

    @Test
    fun exactlyTenMetresCounts() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 25), 10.0, 1),
            entry(2, LocalDate.of(2026, 10, 25), 9.999, 1),
        )
        assertEquals(listOf(1L), ids(CumulativeSummary.rides(list)))
    }

    @Test
    fun ridesAreNewestFirst() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 1), 100.0, 1),
            entry(3, LocalDate.of(2026, 10, 25), 100.0, 1),
            entry(2, LocalDate.of(2026, 10, 25), 100.0, 1),
        )
        assertEquals(listOf(3L, 2L, 1L), ids(CumulativeSummary.rides(list)))
    }

    @Test
    fun weeksOfOctober2026() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 1), 1000.0, 1),
            entry(2, LocalDate.of(2026, 10, 20), 1000.0, 1),
            entry(3, LocalDate.of(2026, 10, 25), 1000.0, 1),
        )
        val weeks = CumulativeSummary.weeks(list, 2026, 10)
        assertEquals(
            listOf("10월 1주 (9/28~10/4)", "10월 4주 (10/19~10/25)"),
            weeks.map { it.label },
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 19)), weeks.map { it.start })
    }

    @Test
    fun weekOfNeighbouringMonthRecordShowsInBothMonths() {
        val list = listOf(entry(1, LocalDate.of(2026, 9, 30), 1000.0, 1))
        assertEquals(listOf("9월 5주 (9/28~10/4)"), CumulativeSummary.weeks(list, 2026, 9).map { it.label })
        assertEquals(listOf("10월 1주 (9/28~10/4)"), CumulativeSummary.weeks(list, 2026, 10).map { it.label })
    }

    @Test
    fun weekOfAWeekWithoutRides() {
        assertEquals(
            WeekOption(LocalDate.of(2026, 10, 26), "10월 5주 (10/26~11/1)", 5),
            CumulativeSummary.weekOf(LocalDate.of(2026, 10, 26), 2026, 10),
        )
        assertEquals(
            WeekOption(LocalDate.of(2026, 12, 28), "1월 1주 (12/28~1/3)", 1),
            CumulativeSummary.weekOf(LocalDate.of(2026, 12, 28), 2027, 1),
        )
        assertEquals(
            WeekOption(LocalDate.of(2026, 12, 28), "12월 5주 (12/28~1/3)", 5),
            CumulativeSummary.weekOf(LocalDate.of(2026, 12, 28), 2026, 12),
        )
    }

    @Test
    fun weeksCarryTheirNumber() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 1), 1000.0, 1),
            entry(2, LocalDate.of(2026, 10, 25), 1000.0, 1),
        )
        assertEquals(listOf(1, 4), CumulativeSummary.weeks(list, 2026, 10).map { it.number })
    }

    @Test
    fun weeksIgnoreJitterSegments() {
        val list = listOf(entry(1, LocalDate.of(2026, 10, 20), 0.2, 1))
        assertEquals(emptyList(), CumulativeSummary.weeks(list, 2026, 10))
    }

    @Test
    fun weekSpanningMonthsShowsAllItsDays() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 9, 27), 1000.0, 1),
            entry(2, LocalDate.of(2026, 9, 30), 1000.0, 1),
            entry(3, LocalDate.of(2026, 10, 1), 1000.0, 1),
            entry(4, LocalDate.of(2026, 10, 4), 1000.0, 1),
            entry(5, LocalDate.of(2026, 10, 5), 1000.0, 1),
        )
        val got = CumulativeSummary.filter(list, PeriodFilter(weekStart = LocalDate.of(2026, 9, 28)))
        assertEquals(listOf(4L, 3L, 2L), ids(got))
    }

    @Test
    fun weekWinsOverMonth() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 9, 30), 1000.0, 1),
            entry(2, LocalDate.of(2026, 10, 20), 1000.0, 1),
        )
        val period = PeriodFilter(2026, 10, LocalDate.of(2026, 9, 28))
        assertEquals(listOf(1L), ids(CumulativeSummary.filter(list, period)))
    }

    @Test
    fun filterDropsJitter() {
        val list = listOf(
            entry(1, LocalDate.of(2026, 10, 20), 5.0, 1),
            entry(2, LocalDate.of(2026, 10, 20), 500.0, 1),
        )
        assertEquals(listOf(2L), ids(CumulativeSummary.filter(list, PeriodFilter())))
    }

    @Test
    fun thisWeekStartsOnMonday() {
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 10, 26)), CumulativeSummary.thisWeek(LocalDate.of(2026, 10, 27)))
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 10, 26)), CumulativeSummary.thisWeek(LocalDate.of(2026, 10, 26)))
        assertEquals(PeriodFilter(weekStart = LocalDate.of(2026, 10, 19)), CumulativeSummary.thisWeek(LocalDate.of(2026, 10, 25)))
    }

    @Test
    fun thisMonth() {
        assertEquals(PeriodFilter(2026, 10), CumulativeSummary.thisMonth(LocalDate.of(2026, 10, 27)))
    }
}
