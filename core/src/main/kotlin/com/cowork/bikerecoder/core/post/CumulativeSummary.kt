package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 누적 지도·요약에 쓰는 완료 구간 한 건. [points]는 이미 단순화된 경로. */
data class CumulativeEntry(
    val segmentId: Long,
    val localDate: LocalDate,
    val distanceM: Double,
    val movingTimeMs: Long,
    val points: List<GeoPoint>,
)

/** 누적 지도에 넣는 주행의 최소 거리(m). 이보다 짧은 구간은 GPS 흔들림으로 보고 뺀다. */
const val MIN_RIDE_DISTANCE_M = 10.0

/**
 * [month]는 [year]가 있을 때만 의미가 있다. 둘 다 null이면 전체.
 * [weekStart](월요일)가 있으면 [year]·[month]보다 우선하며, 그 주 7일(다른 달 날짜 포함)을 고른다.
 */
data class PeriodFilter(
    val year: Int? = null,
    val month: Int? = null,
    val weekStart: LocalDate? = null,
)

data class WeekOption(val start: LocalDate, val label: String)

data class CumulativeTotals(val distanceM: Double, val rides: Int, val movingTimeMs: Long)

object CumulativeSummary {

    /** 최소 거리 이상인 주행만, 최신 순(날짜 내림, 같으면 segmentId 내림). */
    fun rides(entries: List<CumulativeEntry>): List<CumulativeEntry> =
        entries.filter { it.distanceM >= MIN_RIDE_DISTANCE_M }
            .sortedWith(compareByDescending<CumulativeEntry> { it.localDate }.thenByDescending { it.segmentId })

    fun filter(entries: List<CumulativeEntry>, period: PeriodFilter): List<CumulativeEntry> {
        val rides = rides(entries)
        val weekStart = period.weekStart
        if (weekStart != null) {
            val weekEnd = weekStart.plusDays(6)
            return rides.filter { it.localDate >= weekStart && it.localDate <= weekEnd }
        }
        val year = period.year ?: return rides
        val month = period.month
        return rides.filter {
            it.localDate.year == year && (month == null || it.localDate.monthValue == month)
        }
    }

    fun totals(entries: List<CumulativeEntry>): CumulativeTotals {
        val rides = rides(entries)
        return CumulativeTotals(
            distanceM = rides.sumOf { it.distanceM },
            rides = rides.size,
            movingTimeMs = rides.sumOf { it.movingTimeMs },
        )
    }

    /** 기록이 있는 해, 내림차순. */
    fun years(entries: List<CumulativeEntry>): List<Int> =
        rides(entries).map { it.localDate.year }.distinct().sortedDescending()

    /** [year]에서 기록이 있는 달(1–12), 오름차순. */
    fun months(entries: List<CumulativeEntry>, year: Int): List<Int> =
        rides(entries).filter { it.localDate.year == year }.map { it.localDate.monthValue }.distinct().sorted()

    /**
     * [year]년 [month]월과 겹치는 월요일 시작 주 가운데 기록이 있는 주, 오래된 순.
     * 주 번호는 그 달 1일이 든 주를 1주로 센다. 라벨은 "M월 N주 (M/d~M/d)".
     */
    fun weeks(entries: List<CumulativeEntry>, year: Int, month: Int): List<WeekOption> {
        val first = LocalDate.of(year, month, 1)
        val last = first.with(TemporalAdjusters.lastDayOfMonth())
        val firstWeekStart = mondayOf(first)
        return rides(entries)
            .map { mondayOf(it.localDate) }
            .distinct()
            .filter { it <= last && it.plusDays(6) >= first }
            .sorted()
            .map { start ->
                val n = ChronoUnit.WEEKS.between(firstWeekStart, start).toInt() + 1
                val end = start.plusDays(6)
                WeekOption(start, "${month}월 ${n}주 (${start.monthValue}/${start.dayOfMonth}~${end.monthValue}/${end.dayOfMonth})")
            }
    }

    fun thisWeek(today: LocalDate): PeriodFilter = PeriodFilter(weekStart = mondayOf(today))

    fun thisMonth(today: LocalDate): PeriodFilter = PeriodFilter(year = today.year, month = today.monthValue)

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
