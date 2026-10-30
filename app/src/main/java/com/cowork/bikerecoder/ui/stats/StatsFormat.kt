package com.cowork.bikerecoder.ui.stats

import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.post.CumulativeSummary
import com.cowork.bikerecoder.core.stats.Comparison
import com.cowork.bikerecoder.core.stats.PeriodKind
import com.cowork.bikerecoder.core.stats.RideRecordKind
import com.cowork.bikerecoder.core.stats.RideRecords
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.StatTotals
import com.cowork.bikerecoder.core.stats.StatsCalculator
import com.cowork.bikerecoder.core.stats.StatsPeriod
import com.cowork.bikerecoder.core.stats.Streak
import com.cowork.bikerecoder.ui.feed.groupedInt
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.feed.meterText
import com.cowork.bikerecoder.ui.feed.signedKmText
import com.cowork.bikerecoder.ui.history.cardDateText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The three values a bar chart and the totals can show (spec §3.3: 거리 · 시간 · 상승). */
enum class StatMetric { DISTANCE, TIME, ASCENT }

/** "24.1 km", "1시간 45분", "123 m": [totals] in the unit of [metric]. */
fun metricText(metric: StatMetric, totals: StatTotals): String = when (metric) {
    StatMetric.DISTANCE -> kmText(totals.distanceM)
    StatMetric.TIME -> RideFormat.duration(totals.movingTimeMs)
    StatMetric.ASCENT -> meterText(totals.ascentM)
}

/** The number a bar is as tall as: metres, milliseconds or metres of ascent. */
fun metricValue(metric: StatMetric, totals: StatTotals): Double = when (metric) {
    StatMetric.DISTANCE -> totals.distanceM
    StatMetric.TIME -> totals.movingTimeMs.toDouble()
    StatMetric.ASCENT -> totals.ascentM
}

/**
 * The title above the numbers (spec §3.3): a week reads like the cumulative map's weeks ("10월 4주 (10/19~10/25)", a week
 * of the month of its Monday), then "2026년 10월", "2026년", "전체".
 */
fun statsPeriodTitle(period: StatsPeriod): String = when (period.kind) {
    PeriodKind.WEEK -> CumulativeSummary.weekOf(period.start, period.start.year, period.start.monthValue).label
    PeriodKind.MONTH -> "${period.start.year}년 ${period.start.monthValue}월"
    PeriodKind.YEAR -> "${period.start.year}년"
    PeriodKind.ALL -> "전체"
}

/** "10월 3주" (of the month of its Monday [monday]); with [withYear] "2025년 12월 5주". */
fun weekName(monday: LocalDate, withYear: Boolean): String {
    val week = CumulativeSummary.weekOf(monday, monday.year, monday.monthValue)
    return (if (withYear) "${monday.year}년 " else "") + "${monday.monthValue}월 ${week.number}주"
}

/**
 * The sentence under the distance (spec §3.3). A finished period is compared with the one before it by name ("9월보다",
 * "전 주보다" for a past week, "2025년보다"); the period in progress up to the same day ("지난달·지난주 이맘때보다", "작년 이맘때보다").
 * The value is the difference in distance, with the percentage only when the earlier value is above 0. Null for [PeriodKind.ALL]
 * and when both are 0.
 */
fun comparisonText(comparison: Comparison, period: StatsPeriod): String? {
    if (period.kind == PeriodKind.ALL) return null
    val current = comparison.current.distanceM
    val previous = comparison.previous.distanceM
    if (current == 0.0 && previous == 0.0) return null
    val name = if (comparison.sameDay) {
        when (period.kind) {
            PeriodKind.WEEK -> "지난주 이맘때"
            PeriodKind.MONTH -> "지난달 이맘때"
            else -> "작년 이맘때"
        }
    } else {
        when (period.kind) {
            PeriodKind.WEEK -> "전 주"
            PeriodKind.MONTH -> "${period.start.minusMonths(1).monthValue}월"
            else -> "${period.start.year - 1}년"
        }
    }
    val diff = current - previous
    val percent = if (previous > 0.0) " (${signedPercent(diff / previous * 100.0)})" else ""
    return "${name}보다 ${signedKmText(diff)}$percent"
}

/** "+33%" / "−12%" (a real minus sign), rounded half up. */
private fun signedPercent(percent: Double): String {
    val rounded = BigDecimal(percent).setScale(0, RoundingMode.HALF_UP)
    return (if (rounded.signum() < 0) "−" else "+") + rounded.abs().toPlainString() + "%"
}

/** The three sentences of spec §3.2. */
fun streakText(streak: Streak): String = when {
    streak.weeks >= 1 && streak.includesThisWeek -> "${streak.weeks}주 연속 주행 중"
    streak.weeks >= 1 -> "${streak.weeks}주 연속 · 이번 주에 타면 ${streak.weeks + 1}주"
    else -> "이번 주에 타면 연속 주행이 시작됩니다"
}

/** "지금 속도면 연말에 약 1,180 km". */
fun projectionText(km: Int): String = "지금 속도면 연말에 약 ${groupedInt(km)} km"

private const val WEEKDAYS = "월화수목금토일"

private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** The week bar's N of "N주" in the month of [period]. */
private fun weekNumberIn(period: StatsPeriod, bucketStart: LocalDate): Int =
    CumulativeSummary.weekOf(mondayOf(bucketStart), period.start.year, period.start.monthValue).number

/** What stands under a bar: "월", "3주", "10", "2026". */
fun barAxisLabel(kind: PeriodKind, bucketStart: LocalDate, period: StatsPeriod): String = when (kind) {
    PeriodKind.WEEK -> WEEKDAYS[bucketStart.dayOfWeek.value - 1].toString()
    PeriodKind.MONTH -> "${weekNumberIn(period, bucketStart)}주"
    PeriodKind.YEAR -> "${bucketStart.monthValue}"
    PeriodKind.ALL -> "${bucketStart.year}"
}

/** The bar's name in "10월 3주 · 24.1 km": "10월 25일 (일)", "10월 4주", "10월", "2026년". */
fun barCaptionName(kind: PeriodKind, bucketStart: LocalDate, period: StatsPeriod): String = when (kind) {
    PeriodKind.WEEK -> cardDateText(bucketStart)
    PeriodKind.MONTH -> "${period.start.monthValue}월 ${weekNumberIn(period, bucketStart)}주"
    PeriodKind.YEAR -> "${bucketStart.monthValue}월"
    PeriodKind.ALL -> "${bucketStart.year}년"
}

/** One line of 개인 기록: [label] and the value with its date; [target] is where a tap goes (null: nowhere). */
data class RecordRow(val label: String, val value: String, val target: RecordTarget?)

sealed interface RecordTarget {
    /** The page of that segment. */
    data class Segment(val id: Long) : RecordTarget

    /** The stats of that week (start = its Monday) or month (start = its first day). */
    data class Period(val kind: PeriodKind, val start: LocalDate) : RecordTarget
}

/**
 * The 개인 기록 block (spec §3.4) over all rides: the best ride of each kind, the best week and month, the longest streak.
 * Empty under two rides. Weeks and months outside [today]'s year name the year.
 */
fun recordRows(all: List<RideStat>, today: LocalDate): List<RecordRow> {
    if (StatsCalculator.rides(all).size < 2) return emptyList()
    val rows = mutableListOf<RecordRow>()
    for (record in RideRecords.rideRecords(all)) {
        val ride = record.ride
        val (label, value) = when (record.kind) {
            RideRecordKind.LONGEST_DISTANCE -> "가장 긴 주행" to kmText(ride.distanceM)
            RideRecordKind.LONGEST_TIME -> "가장 오래 탄 주행" to RideFormat.duration(ride.movingTimeMs)
            RideRecordKind.MOST_ASCENT -> "가장 많이 오른 주행" to meterText(ride.ascentM)
            RideRecordKind.FASTEST_AVG -> "가장 빠른 평균 속도" to "${RideFormat.speedKmh(record.value)} km/h"
        }
        rows += RecordRow(label, "$value · ${RideFormat.date(ride.localDate)}", RecordTarget.Segment(ride.segmentId))
    }
    RideRecords.bestWeek(all)?.let {
        rows += RecordRow(
            "가장 많이 탄 주",
            "${kmText(it.distanceM)} · ${weekName(it.start, withYear = it.start.year != today.year)}",
            RecordTarget.Period(PeriodKind.WEEK, it.start),
        )
    }
    RideRecords.bestMonth(all)?.let {
        rows += RecordRow(
            "가장 많이 탄 달",
            "${kmText(it.distanceM)} · ${it.start.year}년 ${it.start.monthValue}월",
            RecordTarget.Period(PeriodKind.MONTH, it.start),
        )
    }
    RideRecords.longestStreak(all)?.let {
        val first = weekName(it.firstWeek, withYear = it.firstWeek.year != today.year)
        val range = if (it.firstWeek == it.lastWeek) {
            first
        } else {
            "$first~${weekName(it.lastWeek, withYear = it.lastWeek.year != it.firstWeek.year)}"
        }
        rows += RecordRow("가장 긴 연속 주행", "${it.weeks}주 ($range)", null)
    }
    return rows
}
