package com.cowork.bikerecoder.core.stats

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsCalculatorTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private var nextId = 1L

    private fun ride(
        date: LocalDate,
        distanceM: Double = 10_000.0,
        movingMs: Long = 1_000L,
        ascentM: Double = 0.0,
        startedAt: Long = nextId * 1_000,
    ): RideStat {
        val id = nextId++
        return RideStat(id, 1L, startedAt, date, distanceM, movingMs, ascentM)
    }

    private fun period(kind: PeriodKind, from: LocalDate, to: LocalDate) = StatsPeriod(kind, from, to)

    @Test
    fun ridesDropsShortOnes() {
        val all = listOf(
            ride(d(2026, 10, 1), distanceM = 9.9),
            ride(d(2026, 10, 2), distanceM = 10.0),
            ride(d(2026, 10, 3), distanceM = 0.0),
        )
        val kept = StatsCalculator.rides(all)
        assertEquals(listOf(all[1]), kept)
    }

    @Test
    fun ridesAreOrderedByStartTime() {
        val late = ride(d(2026, 10, 2), startedAt = 300)
        val early = ride(d(2026, 10, 1), startedAt = 100)
        val mid = ride(d(2026, 10, 1), startedAt = 200)
        assertEquals(listOf(early, mid, late), StatsCalculator.rides(listOf(late, early, mid)))
    }

    @Test
    fun periods() {
        val today = d(2026, 10, 31)
        assertEquals(period(PeriodKind.WEEK, d(2026, 10, 26), d(2026, 11, 1)), StatsCalculator.periodOf(PeriodKind.WEEK, today, emptyList(), today))
        assertEquals(period(PeriodKind.MONTH, d(2026, 10, 1), d(2026, 10, 31)), StatsCalculator.periodOf(PeriodKind.MONTH, today, emptyList(), today))
        assertEquals(period(PeriodKind.YEAR, d(2026, 1, 1), d(2026, 12, 31)), StatsCalculator.periodOf(PeriodKind.YEAR, today, emptyList(), today))
        val rides = listOf(ride(d(2025, 3, 2)), ride(d(2026, 5, 1)))
        assertEquals(period(PeriodKind.ALL, d(2025, 3, 2), today), StatsCalculator.periodOf(PeriodKind.ALL, today, rides, today))
        // 기록이 없으면 ALL은 오늘 하루
        assertEquals(period(PeriodKind.ALL, today, today), StatsCalculator.periodOf(PeriodKind.ALL, today, emptyList(), today))
    }

    @Test
    fun previousAndNext() {
        val month = period(PeriodKind.MONTH, d(2026, 3, 1), d(2026, 3, 31))
        assertEquals(period(PeriodKind.MONTH, d(2026, 2, 1), d(2026, 2, 28)), StatsCalculator.previous(month))
        assertEquals(period(PeriodKind.MONTH, d(2026, 4, 1), d(2026, 4, 30)), StatsCalculator.next(month))
        val year = period(PeriodKind.YEAR, d(2028, 1, 1), d(2028, 12, 31))
        assertEquals(period(PeriodKind.YEAR, d(2027, 1, 1), d(2027, 12, 31)), StatsCalculator.previous(year))
        assertEquals(period(PeriodKind.YEAR, d(2029, 1, 1), d(2029, 12, 31)), StatsCalculator.next(year))
        val janFirst = period(PeriodKind.MONTH, d(2026, 1, 1), d(2026, 1, 31))
        assertEquals(period(PeriodKind.MONTH, d(2025, 12, 1), d(2025, 12, 31)), StatsCalculator.previous(janFirst))
    }

    @Test
    fun weekAcrossYearEnd() {
        val today = d(2026, 6, 1)
        val week = StatsCalculator.periodOf(PeriodKind.WEEK, d(2026, 1, 2), emptyList(), today)
        assertEquals(period(PeriodKind.WEEK, d(2025, 12, 29), d(2026, 1, 4)), week)
        assertEquals(period(PeriodKind.WEEK, d(2025, 12, 22), d(2025, 12, 28)), StatsCalculator.previous(week))
        assertEquals(period(PeriodKind.WEEK, d(2026, 1, 5), d(2026, 1, 11)), StatsCalculator.next(week))

        val rides = listOf(ride(d(2025, 12, 30), distanceM = 1_000.0), ride(d(2026, 1, 2), distanceM = 2_000.0))
        val dec = StatsCalculator.buckets(rides, period(PeriodKind.MONTH, d(2025, 12, 1), d(2025, 12, 31)), today)
        assertEquals(d(2025, 12, 29) to d(2025, 12, 31), dec.last().start to dec.last().endInclusive)
        assertEquals(1_000.0, dec.last().totals.distanceM)
        val jan = StatsCalculator.buckets(rides, period(PeriodKind.MONTH, d(2026, 1, 1), d(2026, 1, 31)), today)
        assertEquals(d(2026, 1, 1) to d(2026, 1, 4), jan.first().start to jan.first().endInclusive)
        assertEquals(2_000.0, jan.first().totals.distanceM)
    }

    @Test
    fun bucketsPerKind() {
        val today = d(2026, 12, 31)
        val week = StatsCalculator.buckets(emptyList(), period(PeriodKind.WEEK, d(2026, 10, 26), d(2026, 11, 1)), today)
        assertEquals(7, week.size)
        assertTrue(week.all { it.start == it.endInclusive })
        assertEquals(d(2026, 10, 26), week.first().start)
        assertEquals(d(2026, 11, 1), week.last().start)

        val oct = StatsCalculator.buckets(emptyList(), period(PeriodKind.MONTH, d(2026, 10, 1), d(2026, 10, 31)), today)
        assertEquals(
            listOf(
                d(2026, 10, 1) to d(2026, 10, 4),
                d(2026, 10, 5) to d(2026, 10, 11),
                d(2026, 10, 12) to d(2026, 10, 18),
                d(2026, 10, 19) to d(2026, 10, 25),
                d(2026, 10, 26) to d(2026, 10, 31),
            ),
            oct.map { it.start to it.endInclusive },
        )

        val year = StatsCalculator.buckets(emptyList(), period(PeriodKind.YEAR, d(2026, 1, 1), d(2026, 12, 31)), today)
        assertEquals(12, year.size)
        assertEquals(d(2026, 2, 1) to d(2026, 2, 28), year[1].start to year[1].endInclusive)

        // [전체] of more than 24 months: a bar per year.
        val rides = listOf(ride(d(2024, 3, 2)), ride(d(2026, 5, 1)))
        val all = StatsCalculator.buckets(rides, period(PeriodKind.ALL, d(2024, 3, 2), today), today)
        assertEquals(listOf(d(2024, 1, 1), d(2025, 1, 1), d(2026, 1, 1)), all.map { it.start })
        assertEquals(listOf(d(2024, 12, 31), d(2025, 12, 31), d(2026, 12, 31)), all.map { it.endInclusive })
        assertEquals(listOf(1, 0, 1), all.map { it.totals.rides })
    }

    @Test
    fun allWithin24MonthsHasMonthBuckets() {
        val today = d(2026, 10, 31)
        val rides = listOf(ride(d(2025, 11, 3)), ride(d(2026, 10, 5)))
        val all = StatsCalculator.buckets(rides, period(PeriodKind.ALL, d(2025, 11, 3), today), today)
        assertEquals(12, all.size)
        assertEquals(d(2025, 11, 1) to d(2025, 11, 30), all.first().start to all.first().endInclusive)
        assertEquals(d(2026, 10, 1) to d(2026, 10, 31), all.last().start to all.last().endInclusive)
        assertEquals(listOf(1, 1), all.filter { it.totals.rides > 0 }.map { it.totals.rides })
        assertTrue(all.none { it.upcoming })
        assertTrue(StatsCalculator.allIsMonthly(period(PeriodKind.ALL, d(2025, 11, 3), today)))
    }

    @Test
    fun allOf24MonthsIsMonthly() {
        val today = d(2026, 10, 31)
        val start = d(2024, 11, 20)
        assertEquals(24, StatsCalculator.buckets(emptyList(), period(PeriodKind.ALL, start, today), today).size)
        assertTrue(StatsCalculator.allIsMonthly(period(PeriodKind.ALL, start, today)))
    }

    @Test
    fun allOf25MonthsIsYearly() {
        val today = d(2026, 10, 31)
        val start = d(2024, 10, 20)
        val all = StatsCalculator.buckets(emptyList(), period(PeriodKind.ALL, start, today), today)
        assertEquals(listOf(d(2024, 1, 1), d(2025, 1, 1), d(2026, 1, 1)), all.map { it.start })
        assertFalse(StatsCalculator.allIsMonthly(period(PeriodKind.ALL, start, today)))
    }

    @Test
    fun allWithNoRidesIsOneMonth() {
        val today = d(2026, 10, 31)
        val p = StatsCalculator.periodOf(PeriodKind.ALL, today, emptyList(), today)
        val all = StatsCalculator.buckets(emptyList(), p, today)
        assertEquals(listOf(d(2026, 10, 1) to d(2026, 10, 31)), all.map { it.start to it.endInclusive })
    }

    @Test
    fun upcomingBucketsAreThoseStartingAfterToday() {
        val today = d(2026, 10, 28)
        // [년]: 11·12월 칸만 오지 않았다. 오늘이 든 10월 칸은 아니다.
        val year = StatsCalculator.buckets(emptyList(), period(PeriodKind.YEAR, d(2026, 1, 1), d(2026, 12, 31)), today)
        assertEquals((1..12).map { it > 10 }, year.map { it.upcoming })
        // [월] 10월: 오늘(수)이 든 마지막 주 칸은 이미 왔다.
        val oct = StatsCalculator.buckets(emptyList(), period(PeriodKind.MONTH, d(2026, 10, 1), d(2026, 10, 31)), today)
        assertTrue(oct.none { it.upcoming })
        // 오늘이 10-24면 마지막 칸(10-26~)은 오지 않았다.
        val earlier = StatsCalculator.buckets(emptyList(), period(PeriodKind.MONTH, d(2026, 10, 1), d(2026, 10, 31)), d(2026, 10, 24))
        assertEquals(listOf(false, false, false, false, true), earlier.map { it.upcoming })
        // [주]: 오늘 다음 날부터
        val week = StatsCalculator.buckets(emptyList(), period(PeriodKind.WEEK, d(2026, 10, 26), d(2026, 11, 1)), today)
        assertEquals(listOf(false, false, false, true, true, true, true), week.map { it.upcoming })
        // 지난 기간은 오지 않은 칸이 없다.
        val past = StatsCalculator.buckets(emptyList(), period(PeriodKind.YEAR, d(2025, 1, 1), d(2025, 12, 31)), today)
        assertTrue(past.none { it.upcoming })
    }

    @Test
    fun totalsSumFourValues() {
        val rides = listOf(
            ride(d(2026, 10, 1), distanceM = 1_000.0, movingMs = 100, ascentM = 10.5),
            ride(d(2026, 10, 15), distanceM = 2_500.0, movingMs = 200, ascentM = 20.0),
            ride(d(2026, 11, 1), distanceM = 9_000.0, movingMs = 900, ascentM = 90.0),
        )
        assertEquals(
            StatTotals(3_500.0, 2, 300, 30.5),
            StatsCalculator.totals(rides, d(2026, 10, 1), d(2026, 10, 31)),
        )
        assertEquals(StatTotals(0.0, 0, 0, 0.0), StatsCalculator.totals(rides, d(2026, 9, 1), d(2026, 9, 30)))
        assertEquals(StatTotals(0.0, 0, 0, 0.0), StatsCalculator.totals(emptyList(), d(2026, 9, 1), d(2026, 9, 30)))
    }

    @Test
    fun comparisonPastPeriodsFull() {
        val today = d(2026, 10, 31)
        val rides = listOf(
            ride(d(2026, 8, 31), distanceM = 4_000.0),
            ride(d(2026, 9, 1), distanceM = 1_000.0),
            ride(d(2026, 9, 30), distanceM = 2_000.0),
            ride(d(2026, 10, 1), distanceM = 8_000.0),
        )
        val c = assertNotNull(StatsCalculator.comparison(rides, period(PeriodKind.MONTH, d(2026, 9, 1), d(2026, 9, 30)), today))
        assertFalse(c.sameDay)
        assertEquals(3_000.0, c.current.distanceM)
        assertEquals(4_000.0, c.previous.distanceM)
    }

    @Test
    fun comparisonCurrentSameDay() {
        // 10-12 기준 [월]
        run {
            val today = d(2026, 10, 12)
            val rides = listOf(
                ride(d(2026, 9, 1), distanceM = 1.0e3), ride(d(2026, 9, 12), distanceM = 2.0e3), ride(d(2026, 9, 13), distanceM = 100.0e3),
                ride(d(2026, 10, 1), distanceM = 3.0e3), ride(d(2026, 10, 12), distanceM = 4.0e3),
            )
            val c = assertNotNull(StatsCalculator.comparison(rides, StatsCalculator.periodOf(PeriodKind.MONTH, today, rides, today), today))
            assertTrue(c.sameDay)
            assertEquals(7.0e3, c.current.distanceM)
            assertEquals(3.0e3, c.previous.distanceM)
        }
        // 3-31 [월]: 앞 달 2월은 28일까지
        run {
            val today = d(2027, 3, 31)
            val rides = listOf(ride(d(2027, 2, 1), distanceM = 1.0e3), ride(d(2027, 2, 28), distanceM = 2.0e3), ride(d(2027, 3, 31), distanceM = 5.0e3))
            val c = assertNotNull(StatsCalculator.comparison(rides, StatsCalculator.periodOf(PeriodKind.MONTH, today, rides, today), today))
            assertEquals(5.0e3, c.current.distanceM)
            assertEquals(3.0e3, c.previous.distanceM)
        }
        // 2028-02-29 [년]: 작년 2-28까지
        run {
            val today = d(2028, 2, 29)
            val rides = listOf(
                ride(d(2027, 1, 1), distanceM = 1.0e3), ride(d(2027, 2, 28), distanceM = 2.0e3), ride(d(2027, 3, 1), distanceM = 50.0e3),
                ride(d(2028, 1, 1), distanceM = 4.0e3), ride(d(2028, 2, 29), distanceM = 8.0e3),
            )
            val c = assertNotNull(StatsCalculator.comparison(rides, StatsCalculator.periodOf(PeriodKind.YEAR, today, rides, today), today))
            assertEquals(12.0e3, c.current.distanceM)
            assertEquals(3.0e3, c.previous.distanceM)
        }
        // 10-31(토) [주]: 10-26~31 대 10-19~24
        run {
            val today = d(2026, 10, 31)
            val rides = listOf(
                ride(d(2026, 10, 19), distanceM = 1.0e3), ride(d(2026, 10, 24), distanceM = 2.0e3), ride(d(2026, 10, 25), distanceM = 70.0e3),
                ride(d(2026, 10, 26), distanceM = 4.0e3), ride(d(2026, 10, 31), distanceM = 8.0e3),
            )
            val c = assertNotNull(StatsCalculator.comparison(rides, StatsCalculator.periodOf(PeriodKind.WEEK, today, rides, today), today))
            assertTrue(c.sameDay)
            assertEquals(12.0e3, c.current.distanceM)
            assertEquals(3.0e3, c.previous.distanceM)
        }
    }

    @Test
    fun comparisonAllIsNull() {
        val today = d(2026, 10, 31)
        val rides = listOf(ride(d(2026, 1, 1)))
        assertNull(StatsCalculator.comparison(rides, StatsCalculator.periodOf(PeriodKind.ALL, today, rides, today), today))
    }

    @Test
    fun boundsStopAtFirstRideAndThisPeriod() {
        val today = d(2026, 10, 31)
        val rides = listOf(ride(d(2026, 8, 15)), ride(d(2026, 9, 3)))
        assertEquals(d(2026, 8, 10)..d(2026, 10, 26), StatsCalculator.bounds(PeriodKind.WEEK, rides, today))
        assertEquals(d(2026, 8, 1)..d(2026, 10, 1), StatsCalculator.bounds(PeriodKind.MONTH, rides, today))
        assertEquals(d(2026, 1, 1)..d(2026, 1, 1), StatsCalculator.bounds(PeriodKind.YEAR, rides, today))
        // 기록이 없으면 이번 기간만
        assertEquals(d(2026, 10, 26)..d(2026, 10, 26), StatsCalculator.bounds(PeriodKind.WEEK, emptyList(), today))
        assertEquals(d(2026, 10, 1)..d(2026, 10, 1), StatsCalculator.bounds(PeriodKind.MONTH, emptyList(), today))
    }
}
