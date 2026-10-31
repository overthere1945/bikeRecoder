package com.cowork.bikerecoder.ui.stats

import com.cowork.bikerecoder.core.stats.Comparison
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.StatTotals
import com.cowork.bikerecoder.core.stats.StatsPeriod
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.ui.feed.feedMonthHeaderText
import com.cowork.bikerecoder.ui.feed.meterText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The words of the stats tab (V1.4.0 spec §3, §5). */
class StatsFormatTest {

    private fun totals(km: Double) = StatTotals(km * 1000.0, if (km > 0) 1 else 0, 0L, 0.0)

    private fun comparison(current: Double, previous: Double, sameDay: Boolean) =
        Comparison(totals(current), totals(previous), sameDay)

    private val october = StatsPeriod(PeriodKind.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    private val week = StatsPeriod(PeriodKind.WEEK, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))
    private val year = StatsPeriod(PeriodKind.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    @Test
    fun comparisonTexts() {
        // Finished periods: against the one before, by its name; a real minus sign (U+2212).
        assertEquals("9월보다 +21.3 km (+33%)", comparisonText(comparison(85.8, 64.5, false), october))
        assertEquals("전 주보다 −4.0 km (−12%)", comparisonText(comparison(30.0, 34.0, false), week))
        assertEquals("2025년보다 +100.0 km (+10%)", comparisonText(comparison(1100.0, 1000.0, false), year))
        // Last week itself reads like any past week: against the one before it.
        val lastWeek = StatsPeriod(PeriodKind.WEEK, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertEquals("전 주보다 +6.0 km (+20%)", comparisonText(comparison(36.0, 30.0, false), lastWeek))
        // January compares with December of the year before.
        val january = StatsPeriod(PeriodKind.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        assertEquals("12월보다 +1.0 km (+10%)", comparisonText(comparison(11.0, 10.0, false), january))
        // The period in progress: up to the same day.
        assertEquals("지난달 이맘때보다 +5.2 km (+10%)", comparisonText(comparison(57.2, 52.0, true), october))
        assertEquals("지난주 이맘때보다 −1.5 km (−2%)", comparisonText(comparison(70.0, 71.5, true), week))
        assertEquals("작년 이맘때보다 +12.3 km (+1%)", comparisonText(comparison(1012.3, 1000.0, true), year))
    }

    @Test
    fun comparisonPercentOnlyWhenThePreviousIsAboveZero() {
        assertEquals("지난달 이맘때보다 +5.2 km", comparisonText(comparison(5.2, 0.0, true), october))
        assertEquals("9월보다 −100.0 km (−100%)", comparisonText(comparison(0.0, 100.0, false), october))
    }

    @Test
    fun comparisonIsNullWhenBothAreZeroOrForAll() {
        assertNull(comparisonText(comparison(0.0, 0.0, false), october))
        assertNull(comparisonText(comparison(0.0, 0.0, true), week))
        val all = StatsPeriod(PeriodKind.ALL, LocalDate.of(2025, 3, 1), LocalDate.of(2026, 10, 31))
        assertNull(comparisonText(comparison(10.0, 5.0, false), all))
    }

    @Test
    fun streakTexts() {
        assertEquals("4주 연속 주행 중", streakText(Streak(4, includesThisWeek = true)))
        assertEquals("1주 연속 주행 중", streakText(Streak(1, includesThisWeek = true)))
        assertEquals("3주 연속 · 이번 주에 타면 4주", streakText(Streak(3, includesThisWeek = false)))
        assertEquals("이번 주에 타면 연속 주행이 시작됩니다", streakText(Streak(0, includesThisWeek = false)))
    }

    @Test
    fun projectionText() {
        assertEquals("지금 속도면 연말에 약 1,180 km", projectionText(1180))
        assertEquals("지금 속도면 연말에 약 90 km", projectionText(90))
    }

    @Test
    fun periodTitles() {
        assertEquals("10월 4주 (10/19~10/25)", statsPeriodTitle(week))
        assertEquals("2026년 10월", statsPeriodTitle(october))
        assertEquals("2026년", statsPeriodTitle(year))
        assertEquals("전체", statsPeriodTitle(StatsPeriod(PeriodKind.ALL, LocalDate.of(2025, 3, 1), LocalDate.of(2026, 10, 31))))
        // A week of two months is a week of the month of its Monday; across the year end it has its dates.
        assertEquals("12월 5주 (12/29~1/4)", statsPeriodTitle(StatsPeriod(PeriodKind.WEEK, LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4))))
    }

    @Test
    fun weekNames() {
        assertEquals("10월 3주", weekName(LocalDate.of(2026, 10, 12), withYear = false))
        assertEquals("2025년 12월 5주", weekName(LocalDate.of(2025, 12, 29), withYear = true))
    }

    private fun ride(id: Long, date: LocalDate, km: Double, minutes: Long, ascent: Double = 0.0) = RideStat(
        segmentId = id,
        tripId = id,
        startedAt = date.atTime(8, 0).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),
        localDate = date,
        distanceM = km * 1000.0,
        movingTimeMs = minutes * 60_000,
        ascentM = ascent,
    )

    @Test
    fun recordRowTexts() {
        val rides = listOf(
            ride(1, LocalDate.of(2026, 9, 14), 42.1, 180),
            ride(2, LocalDate.of(2026, 10, 20), 20.0, 60, ascent = 350.0),
            ride(3, LocalDate.of(2026, 9, 21), 5.0, 30),
        )

        val rows = recordRows(rides, LocalDate.of(2026, 10, 31))

        assertEquals(
            listOf(
                RecordRow("가장 긴 주행", "42.1 km · 2026년 9월 14일", RecordTarget.Segment(1)),
                RecordRow("가장 오래 탄 주행", "3시간 0분 · 2026년 9월 14일", RecordTarget.Segment(1)),
                RecordRow("가장 많이 오른 주행", "350 m · 2026년 10월 20일", RecordTarget.Segment(2)),
                RecordRow("가장 빠른 평균 속도", "20.0 km/h · 2026년 10월 20일", RecordTarget.Segment(2)),
                RecordRow("가장 많이 탄 주", "42.1 km · 9월 3주", RecordTarget.Period(PeriodKind.WEEK, LocalDate.of(2026, 9, 14))),
                RecordRow("가장 많이 탄 달", "47.1 km · 2026년 9월", RecordTarget.Period(PeriodKind.MONTH, LocalDate.of(2026, 9, 1))),
                RecordRow("가장 긴 연속 주행", "2주 (9월 3주~9월 4주)", null),
            ),
            rows,
        )
    }

    @Test
    fun recordRowsNameTheYearOutsideThisOne() {
        val rides = listOf(
            ride(1, LocalDate.of(2025, 12, 30), 50.0, 120),
            ride(2, LocalDate.of(2026, 1, 6), 10.0, 30),
        )

        val rows = recordRows(rides, LocalDate.of(2026, 10, 31)).associateBy { it.label }

        assertEquals("50.0 km · 2025년 12월 5주", rows.getValue("가장 많이 탄 주").value)
        assertEquals("50.0 km · 2025년 12월", rows.getValue("가장 많이 탄 달").value)
        assertEquals("2주 (2025년 12월 5주~2026년 1월 2주)", rows.getValue("가장 긴 연속 주행").value)
    }

    @Test
    fun recordRowsAreEmptyUnderTwoRides() {
        assertEquals(emptyList<RecordRow>(), recordRows(emptyList(), LocalDate.of(2026, 10, 31)))
        assertEquals(
            emptyList<RecordRow>(),
            recordRows(listOf(ride(1, LocalDate.of(2026, 9, 14), 42.1, 180)), LocalDate.of(2026, 10, 31)),
        )
        // A ride under 10 m is not a ride.
        assertEquals(
            emptyList<RecordRow>(),
            recordRows(
                listOf(ride(1, LocalDate.of(2026, 9, 14), 42.1, 180), ride(2, LocalDate.of(2026, 9, 15), 0.005, 1)),
                LocalDate.of(2026, 10, 31),
            ),
        )
    }

    @Test
    fun barTextsPerPeriodKind() {
        val sunday = LocalDate.of(2026, 10, 25)
        assertEquals("일", barAxisLabel(PeriodKind.WEEK, sunday, october))
        assertEquals("10월 25일 (일)", barCaptionName(PeriodKind.WEEK, sunday, week))
        // A month's week bars: N of "N주" counted as in the cumulative map; the 25th is in the week of the 19th (4주).
        assertEquals("4주", barAxisLabel(PeriodKind.MONTH, sunday, october))
        assertEquals("10월 4주", barCaptionName(PeriodKind.MONTH, sunday, october))
        assertEquals("10", barAxisLabel(PeriodKind.YEAR, LocalDate.of(2026, 10, 1), year))
        assertEquals("10월", barCaptionName(PeriodKind.YEAR, LocalDate.of(2026, 10, 1), year))
        val all = StatsPeriod(PeriodKind.ALL, LocalDate.of(2020, 3, 1), LocalDate.of(2026, 10, 31))
        assertEquals("2026", barAxisLabel(PeriodKind.ALL, LocalDate.of(2026, 1, 1), all))
        assertEquals("2026년", barCaptionName(PeriodKind.ALL, LocalDate.of(2026, 1, 1), all))
    }

    @Test
    fun metricTexts() {
        val totals = StatTotals(24_100.0, 3, 6_300_000, 123.4)
        assertEquals("24.1 km", metricText(StatMetric.DISTANCE, totals))
        assertEquals("1시간 45분", metricText(StatMetric.TIME, totals))
        assertEquals("123 m", metricText(StatMetric.ASCENT, totals))
        assertEquals(24_100.0, metricValue(StatMetric.DISTANCE, totals), 1e-9)
        assertEquals(6_300_000.0, metricValue(StatMetric.TIME, totals), 1e-9)
        assertEquals(123.4, metricValue(StatMetric.ASCENT, totals), 1e-9)
    }

    @Test
    fun barShortTexts() {
        assertEquals("24.1", barShortText(StatMetric.DISTANCE, StatTotals(24_100.0, 1, 0, 0.0)))
        assertEquals("99.9", barShortText(StatMetric.DISTANCE, StatTotals(99_940.0, 1, 0, 0.0)))
        assertEquals("100", barShortText(StatMetric.DISTANCE, StatTotals(99_960.0, 1, 0, 0.0)))
        assertEquals("124", barShortText(StatMetric.DISTANCE, StatTotals(123_700.0, 1, 0, 0.0)))
        // Thousands are grouped like the ticks and meterText.
        assertEquals("1,124", barShortText(StatMetric.DISTANCE, StatTotals(1_123_700.0, 1, 0, 0.0)))
        assertEquals("4,370", barShortText(StatMetric.ASCENT, StatTotals(0.0, 1, 0, 4_370.4)))
        assertEquals("1:45", barShortText(StatMetric.TIME, StatTotals(0.0, 1, 6_300_000, 0.0)))
        assertEquals("0:19", barShortText(StatMetric.TIME, StatTotals(0.0, 1, 19 * 60_000L + 30_000, 0.0)))
        assertEquals("120", barShortText(StatMetric.ASCENT, StatTotals(0.0, 1, 0, 119.6)))
        // Nothing to write on an empty bar.
        for (metric in StatMetric.entries) assertEquals("", barShortText(metric, StatTotals(0.0, 0, 0, 0.0)))
    }

    @Test
    fun metricUnitTexts() {
        assertEquals("km", metricUnitText(StatMetric.DISTANCE))
        assertEquals("시간", metricUnitText(StatMetric.TIME))
        assertEquals("m", metricUnitText(StatMetric.ASCENT))
    }

    @Test
    fun cumulativeValuesAreInTheUnitOfTheChart() {
        val totals = StatTotals(24_100.0, 1, 5_400_000, 120.0)
        assertEquals(24.1, cumulativeValue(StatMetric.DISTANCE, totals), 1e-9)
        assertEquals(1.5, cumulativeValue(StatMetric.TIME, totals), 1e-9)
        assertEquals(120.0, cumulativeValue(StatMetric.ASCENT, totals), 1e-9)
    }

    @Test
    fun cumulativeTextsWriteZeroWhereTheBarWritesNothing() {
        assertEquals("24.1", cumulativeText(StatMetric.DISTANCE, StatTotals(24_100.0, 1, 0, 0.0)))
        assertEquals("1:45", cumulativeText(StatMetric.TIME, StatTotals(0.0, 1, 6_300_000, 0.0)))
        val none = StatTotals(0.0, 0, 0, 0.0)
        assertEquals("0", cumulativeText(StatMetric.DISTANCE, none))
        assertEquals("0:00", cumulativeText(StatMetric.TIME, none))
        assertEquals("0", cumulativeText(StatMetric.ASCENT, none))
    }

    @Test
    fun cumulativeAxisNamesAreFirstMiddleAndLast() {
        assertEquals(listOf("월", "목", "일"), cumulativeAxisNames(PeriodKind.WEEK, 7))
        assertEquals(listOf("1일", "15일", "31일"), cumulativeAxisNames(PeriodKind.MONTH, 31))
        assertEquals(listOf("1일", "15일", "30일"), cumulativeAxisNames(PeriodKind.MONTH, 30))
        assertEquals(listOf("1월", "7월", "12월"), cumulativeAxisNames(PeriodKind.YEAR, 365))
    }

    @Test
    fun theMiddleAxisNameStandsOnItsOwnDay() {
        assertEquals(3, cumulativeMiddleIndex(PeriodKind.WEEK, 7)) // 목
        assertEquals(14, cumulativeMiddleIndex(PeriodKind.MONTH, 28)) // the 15th
        assertEquals(14, cumulativeMiddleIndex(PeriodKind.MONTH, 31))
        assertEquals(181, cumulativeMiddleIndex(PeriodKind.YEAR, 365)) // July 1
        assertEquals(182, cumulativeMiddleIndex(PeriodKind.YEAR, 366))
    }

    @Test
    fun metricLabelsNameTheAscent() {
        assertEquals("거리", metricLabel(StatMetric.DISTANCE))
        assertEquals("시간", metricLabel(StatMetric.TIME))
        assertEquals("상승 고도", metricLabel(StatMetric.ASCENT))
    }

    @Test
    fun monthlyAllBarTexts() {
        val all = StatsPeriod(PeriodKind.ALL, LocalDate.of(2025, 11, 3), LocalDate.of(2026, 10, 31))
        // The first bar and every January carry the year; the others are just the month.
        assertEquals("’25.11", barAxisLabel(PeriodKind.ALL, LocalDate.of(2025, 11, 1), all))
        assertEquals("12월", barAxisLabel(PeriodKind.ALL, LocalDate.of(2025, 12, 1), all))
        assertEquals("’26.1", barAxisLabel(PeriodKind.ALL, LocalDate.of(2026, 1, 1), all))
        assertEquals("10월", barAxisLabel(PeriodKind.ALL, LocalDate.of(2026, 10, 1), all))
        assertEquals("2026년 10월", barCaptionName(PeriodKind.ALL, LocalDate.of(2026, 10, 1), all))
        // A [전체] of 25 months or more stays yearly.
        val long = StatsPeriod(PeriodKind.ALL, LocalDate.of(2024, 10, 3), LocalDate.of(2026, 10, 31))
        assertEquals("2025", barAxisLabel(PeriodKind.ALL, LocalDate.of(2025, 1, 1), long))
        assertEquals("2025년", barCaptionName(PeriodKind.ALL, LocalDate.of(2025, 1, 1), long))
    }

    /** Review M1: the V1.4.0 screens write metres like kilometres, with a space. */
    @Test
    fun meterTextHasASpaceAndRoundsHalfUp() {
        assertEquals("123 m", meterText(123.4))
        assertEquals("124 m", meterText(123.5))
        assertEquals("0 m", meterText(0.0))
        assertEquals("1,234 m", meterText(1_234.0))
    }

    @Test
    fun feedMonthHeaderUsesTheCardsKilometres() {
        assertEquals("2026년 10월 · 32.1 km · 7회", feedMonthHeaderText(2026, 10, 32_100.0, 7))
        assertEquals("2026년 9월 · 0.0 km · 1회", feedMonthHeaderText(2026, 9, 12.0, 1))
    }

    @Test
    fun goalProgressTextGroupsThousands() {
        assertEquals("64 / 100 km", com.cowork.bikerecoder.ui.feed.goalProgressText(64_200.0, 100))
        assertEquals("412 / 1,000 km", com.cowork.bikerecoder.ui.feed.goalProgressText(412_900.0, 1000))
        assertEquals("1,250 / 3,000 km", com.cowork.bikerecoder.ui.feed.goalProgressText(1_250_000.0, 3000))
        // The ring's two lines (review M3).
        assertEquals("1,234", com.cowork.bikerecoder.ui.feed.goalDoneText(1_234_900.0))
        assertEquals("/ 10,000 km", com.cowork.bikerecoder.ui.feed.goalOfText(10_000))
    }
}
