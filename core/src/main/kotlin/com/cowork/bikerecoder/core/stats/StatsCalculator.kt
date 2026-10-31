package com.cowork.bikerecoder.core.stats

import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 기간 통계. 주는 월요일~일요일. `rides` 인자를 받는 함수는 모두 [rides]를 거친 목록을 받는다고 본다.
 */
object StatsCalculator {

    /** 최소 거리 이상인 주행만, 시작 순(같으면 segmentId 순). */
    fun rides(all: List<RideStat>): List<RideStat> =
        all.filter { it.distanceM >= MIN_RIDE_DISTANCE_M }
            .sortedWith(compareBy<RideStat> { it.startedAt }.thenBy { it.segmentId })

    /** [date]가 든 [kind] 기간. [PeriodKind.ALL]은 첫 주행 날(없으면 [today])부터 [today]까지. */
    fun periodOf(kind: PeriodKind, date: LocalDate, rides: List<RideStat>, today: LocalDate): StatsPeriod =
        when (kind) {
            PeriodKind.WEEK -> weekOf(mondayOf(date))
            PeriodKind.MONTH -> monthOf(date.withDayOfMonth(1))
            PeriodKind.YEAR -> yearOf(date.withDayOfYear(1))
            PeriodKind.ALL -> StatsPeriod(PeriodKind.ALL, rides.minOfOrNull { it.localDate } ?: today, today)
        }

    /** 바로 앞 기간. [PeriodKind.ALL]에는 쓰지 않는다. */
    fun previous(period: StatsPeriod): StatsPeriod = shift(period, -1)

    /** 바로 뒤 기간. [PeriodKind.ALL]에는 쓰지 않는다. */
    fun next(period: StatsPeriod): StatsPeriod = shift(period, 1)

    /**
     * 넘겨 볼 수 있는 기간의 시작일 범위: 첫 주행이 든 기간의 시작부터 이번 기간의 시작까지.
     * 기록이 없으면 이번 기간뿐. [PeriodKind.ALL]에는 쓰지 않는다(이번 기간 하나를 돌려준다).
     */
    fun bounds(kind: PeriodKind, rides: List<RideStat>, today: LocalDate): ClosedRange<LocalDate> {
        val thisStart = periodOf(kind, today, emptyList(), today).start
        val first = rides.minOfOrNull { it.localDate } ?: return thisStart..thisStart
        val firstStart = periodOf(kind, first, emptyList(), today).start
        return minOf(firstStart, thisStart)..thisStart
    }

    /** [from]~[to](양 끝 포함)에 날짜가 든 주행의 네 값 합. */
    fun totals(rides: List<RideStat>, from: LocalDate, to: LocalDate): StatTotals {
        val inRange = rides.filter { it.localDate >= from && it.localDate <= to }
        return StatTotals(
            distanceM = inRange.sumOf { it.distanceM },
            rides = inRange.size,
            movingTimeMs = inRange.sumOf { it.movingTimeMs },
            ascentM = inRange.sumOf { it.ascentM },
        )
    }

    /** [PeriodKind.ALL] 기간이 걸친 달 수가 이 값 이하면 막대를 달마다, 넘으면 해마다 그린다. */
    private const val ALL_MONTHLY_MAX_MONTHS = 24

    /** [period]([PeriodKind.ALL])의 막대가 달마다인가: 시작한 달부터 끝난 달까지 24달 이하. 아니면 해마다. */
    fun allIsMonthly(period: StatsPeriod): Boolean = monthsSpanned(period) <= ALL_MONTHLY_MAX_MONTHS

    private fun monthsSpanned(period: StatsPeriod): Long =
        ChronoUnit.MONTHS.between(period.start.withDayOfMonth(1), period.endInclusive.withDayOfMonth(1)) + 1

    /**
     * 막대 그래프 칸. [PeriodKind.WEEK] 7일, [PeriodKind.MONTH] 그 달에 걸친 주마다(그 달 날짜로 자른다),
     * [PeriodKind.YEAR] 12달, [PeriodKind.ALL] 24달 이하면 달마다(그 달 1일~말일),
     * 그보다 길면 해마다(그 해 1월 1일~12월 31일). 기준은 [allIsMonthly].
     */
    fun buckets(rides: List<RideStat>, period: StatsPeriod, today: LocalDate): List<Bucket> {
        val ranges: List<Pair<LocalDate, LocalDate>> = when (period.kind) {
            PeriodKind.WEEK -> (0L..6L).map { period.start.plusDays(it) }.map { it to it }
            PeriodKind.MONTH -> {
                val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
                var weekStart = mondayOf(period.start)
                while (weekStart <= period.endInclusive) {
                    ranges += maxOf(weekStart, period.start) to minOf(weekStart.plusDays(6), period.endInclusive)
                    weekStart = weekStart.plusDays(7)
                }
                ranges
            }
            PeriodKind.YEAR -> (0L..11L).map { monthOf(period.start.plusMonths(it)) }.map { it.start to it.endInclusive }
            PeriodKind.ALL -> if (allIsMonthly(period)) {
                (0L until monthsSpanned(period)).map { monthOf(period.start.withDayOfMonth(1).plusMonths(it)) }.map { it.start to it.endInclusive }
            } else {
                (period.start.year..period.endInclusive.year).map { LocalDate.of(it, 1, 1) to LocalDate.of(it, 12, 31) }
            }
        }
        return ranges.map { (start, end) -> Bucket(start, end, totals(rides, start, end), upcoming = start > today) }
    }

    /**
     * 앞 기간과의 비교. [PeriodKind.ALL]이면 null. [period]가 오늘을 품은 이번 기간이면 오늘까지의 합을
     * 앞 기간의 같은 날(없으면 그 기간 마지막 날)까지와 비교하고, 아니면 끝난 두 기간 전체를 비교한다.
     */
    fun comparison(rides: List<RideStat>, period: StatsPeriod, today: LocalDate): Comparison? {
        if (period.kind == PeriodKind.ALL) return null
        val prev = previous(period)
        val inProgress = period.start <= today && today <= period.endInclusive
        if (!inProgress) {
            return Comparison(
                current = totals(rides, period.start, period.endInclusive),
                previous = totals(rides, prev.start, prev.endInclusive),
                sameDay = false,
            )
        }
        val prevEnd = when (period.kind) {
            PeriodKind.WEEK -> today.minusDays(7)
            PeriodKind.MONTH -> prev.start.withDayOfMonth(minOf(today.dayOfMonth, prev.start.lengthOfMonth()))
            PeriodKind.YEAR -> today.minusYears(1)
            PeriodKind.ALL -> error("unreachable")
        }
        return Comparison(
            current = totals(rides, period.start, today),
            previous = totals(rides, prev.start, prevEnd),
            sameDay = true,
        )
    }

    private fun shift(period: StatsPeriod, n: Long): StatsPeriod = when (period.kind) {
        PeriodKind.WEEK -> weekOf(period.start.plusWeeks(n))
        PeriodKind.MONTH -> monthOf(period.start.plusMonths(n))
        PeriodKind.YEAR -> yearOf(period.start.plusYears(n))
        PeriodKind.ALL -> throw IllegalArgumentException("ALL has no previous/next")
    }

    internal fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun weekOf(monday: LocalDate) = StatsPeriod(PeriodKind.WEEK, monday, monday.plusDays(6))

    private fun monthOf(first: LocalDate) =
        StatsPeriod(PeriodKind.MONTH, first, first.with(TemporalAdjusters.lastDayOfMonth()))

    private fun yearOf(first: LocalDate) =
        StatsPeriod(PeriodKind.YEAR, first, first.with(TemporalAdjusters.lastDayOfYear()))
}
