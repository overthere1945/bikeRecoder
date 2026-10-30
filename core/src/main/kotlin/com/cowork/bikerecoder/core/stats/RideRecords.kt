package com.cowork.bikerecoder.core.stats

import java.time.LocalDate

/** 연속 주행 N주. [includesThisWeek]는 이번 주에 주행이 있어 센 것인지(없으면 지난주부터 센 것). */
data class Streak(val weeks: Int, val includesThisWeek: Boolean)

/** 가장 길게 이어진 주 묶음. [firstWeek]·[lastWeek]는 그 주의 월요일. */
data class StreakRun(val weeks: Int, val firstWeek: LocalDate, val lastWeek: LocalDate)

enum class RideRecordKind { LONGEST_DISTANCE, LONGEST_TIME, MOST_ASCENT, FASTEST_AVG }

/** [value]: 거리 m, 이동 시간 ms, 상승 m, 평균 속도 m/s. */
data class RideRecord(val kind: RideRecordKind, val ride: RideStat, val value: Double)

/** [start]는 주면 월요일, 달이면 1일. */
data class PeriodRecord(val start: LocalDate, val distanceM: Double)

/**
 * 연속 주행과 개인 기록(전체 기간). 모든 함수는 [StatsCalculator.rides]를 거친 주행만 본다.
 * "더 이른" 주행은 `startedAt`이 작은 쪽이고, 같으면 `segmentId`가 작은 쪽이다.
 */
object RideRecords {

    /** 가장 빠른 평균 속도 기록에 드는 최소 거리. */
    const val FASTEST_MIN_DISTANCE_M = 5_000.0

    /** 이번 주에 주행이 있으면 이번 주부터, 없으면 지난주부터 거꾸로 센 연속 주 수. */
    fun streak(rides: List<RideStat>, today: LocalDate): Streak {
        val weeks = StatsCalculator.rides(rides).map { StatsCalculator.mondayOf(it.localDate) }.toSet()
        val thisWeek = StatsCalculator.mondayOf(today)
        val includesThisWeek = thisWeek in weeks
        var week = if (includesThisWeek) thisWeek else thisWeek.minusWeeks(1)
        var count = 0
        while (week in weeks) {
            count++
            week = week.minusWeeks(1)
        }
        return Streak(count, includesThisWeek && count > 0)
    }

    /** 가장 긴 연속 주행. 길이가 같으면 먼저 것. 주행이 없으면 null. */
    fun longestStreak(rides: List<RideStat>): StreakRun? {
        val weeks = StatsCalculator.rides(rides).map { StatsCalculator.mondayOf(it.localDate) }.distinct().sorted()
        if (weeks.isEmpty()) return null
        var best: StreakRun? = null
        var runStart = weeks.first()
        var runLast = weeks.first()
        var runLen = 1
        fun close() {
            if (best == null || runLen > best!!.weeks) best = StreakRun(runLen, runStart, runLast)
        }
        for (w in weeks.drop(1)) {
            if (w == runLast.plusWeeks(1)) {
                runLast = w
                runLen++
            } else {
                close()
                runStart = w
                runLast = w
                runLen = 1
            }
        }
        close()
        return best
    }

    /**
     * 주행 항목 네 가지의 최고 기록. 주행이 2회 미만이면 빈 목록. 같은 값이면 먼저 세운 주행.
     * [RideRecordKind.FASTEST_AVG]는 거리 [FASTEST_MIN_DISTANCE_M] 이상에 이동 시간이 0보다 큰 주행만 본다.
     * 대상 주행이 없는 항목은 빠진다.
     */
    fun rideRecords(rides: List<RideStat>): List<RideRecord> {
        val kept = StatsCalculator.rides(rides)
        if (kept.size < 2) return emptyList()
        return RideRecordKind.entries.mapNotNull { kind -> best(kept, kind) }
    }

    /** 거리가 가장 큰 주(월요일 시작). 같으면 먼저 주. 주행이 없으면 null. */
    fun bestWeek(rides: List<RideStat>): PeriodRecord? =
        bestPeriod(rides) { StatsCalculator.mondayOf(it.localDate) }

    /** 거리가 가장 큰 달(1일 시작). 같으면 먼저 달. 주행이 없으면 null. */
    fun bestMonth(rides: List<RideStat>): PeriodRecord? =
        bestPeriod(rides) { it.localDate.withDayOfMonth(1) }

    /** 지금 주행 기록 네 가지 중 하나라도 갖고 있는 구간의 여행 id들. */
    fun recordHolderTrips(rides: List<RideStat>): Set<Long> =
        rideRecords(rides).map { it.ride.tripId }.toSet()

    /**
     * [segmentId] 구간이 세운 신기록 항목. 그 항목에 해당하는 더 이른 주행이 있고, 이 구간이 그 최고값보다
     * 클 때만이다. 이 구간이 짧은 기록이라 걸러졌으면 빈 목록.
     */
    fun newRecords(rides: List<RideStat>, segmentId: Long): List<RideRecordKind> {
        val kept = StatsCalculator.rides(rides)
        val target = kept.firstOrNull { it.segmentId == segmentId } ?: return emptyList()
        val earlier = kept.filter { isEarlier(it, target) }
        if (earlier.isEmpty()) return emptyList()
        return RideRecordKind.entries.filter { kind ->
            val mine = valueOf(kind, target) ?: return@filter false
            val prior = earlier.mapNotNull { valueOf(kind, it) }.maxOrNull() ?: return@filter false
            mine > prior
        }
    }

    private fun isEarlier(a: RideStat, b: RideStat): Boolean =
        a.startedAt < b.startedAt || (a.startedAt == b.startedAt && a.segmentId < b.segmentId)

    /** 그 항목에 드는 주행이 아니면 null. */
    private fun valueOf(kind: RideRecordKind, ride: RideStat): Double? = when (kind) {
        RideRecordKind.LONGEST_DISTANCE -> ride.distanceM
        RideRecordKind.LONGEST_TIME -> ride.movingTimeMs.toDouble()
        RideRecordKind.MOST_ASCENT -> ride.ascentM
        RideRecordKind.FASTEST_AVG ->
            if (ride.distanceM >= FASTEST_MIN_DISTANCE_M && ride.movingTimeMs > 0) {
                ride.distanceM / (ride.movingTimeMs / 1000.0)
            } else {
                null
            }
    }

    /** [kept]는 시작 순이라 첫 최대가 가장 이른 주행이다(`>`로만 바꾼다). */
    private fun best(kept: List<RideStat>, kind: RideRecordKind): RideRecord? {
        var best: RideRecord? = null
        for (ride in kept) {
            val v = valueOf(kind, ride) ?: continue
            if (best == null || v > best.value) best = RideRecord(kind, ride, v)
        }
        return best
    }

    private fun bestPeriod(rides: List<RideStat>, startOf: (RideStat) -> LocalDate): PeriodRecord? =
        StatsCalculator.rides(rides)
            .groupBy(startOf)
            .map { (start, group) -> PeriodRecord(start, group.sumOf { it.distanceM }) }
            .sortedBy { it.start }
            .fold(null as PeriodRecord?) { acc, p -> if (acc == null || p.distanceM > acc.distanceM) p else acc }
}
