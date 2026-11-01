package com.cowork.bikerecoder.core.stats

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import java.time.LocalDate

/** 연속 주행 N주. [includesThisWeek]는 이번 주에 주행이 있어 센 것인지(없으면 지난주부터 센 것). */
data class Streak(val weeks: Int, val includesThisWeek: Boolean)

/** 가장 길게 이어진 주 묶음. [firstWeek]·[lastWeek]는 그 주의 월요일. */
data class StreakRun(val weeks: Int, val firstWeek: LocalDate, val lastWeek: LocalDate)

/**
 * 개인 기록 항목. 항목마다 어느 종류의 구간끼리 비교하는지([activity])와 작을수록 좋은지([lowerIsBetter])를 안다.
 * [effort]가 있으면 달리기 최고 기록이고 값은 그 거리를 달린 시간(ms)이다. 선언 순서가 목록·신기록 줄의 순서다.
 */
enum class RideRecordKind(
    val activity: ActivityType,
    val lowerIsBetter: Boolean = false,
    val effort: BestEffortDistance? = null,
) {
    LONGEST_DISTANCE(ActivityType.BIKE),
    LONGEST_TIME(ActivityType.BIKE),
    MOST_ASCENT(ActivityType.BIKE),
    FASTEST_AVG(ActivityType.BIKE),

    RUN_LONGEST_DISTANCE(ActivityType.RUN),
    RUN_BEST_1K(ActivityType.RUN, true, BestEffortDistance.ONE_K),
    RUN_BEST_5K(ActivityType.RUN, true, BestEffortDistance.FIVE_K),
    RUN_BEST_10K(ActivityType.RUN, true, BestEffortDistance.TEN_K),
    RUN_BEST_HALF(ActivityType.RUN, true, BestEffortDistance.HALF),

    WALK_LONGEST_DISTANCE(ActivityType.WALK),
    WALK_LONGEST_TIME(ActivityType.WALK),

    HIKE_MOST_ASCENT(ActivityType.HIKE),
    HIKE_HIGHEST(ActivityType.HIKE),
    HIKE_LONGEST_ELAPSED(ActivityType.HIKE),
}

/**
 * [value]: 거리 m, 이동 시간 ms, 상승 m, 평균 속도 m/s, 최고 고도 m, 경과 시간 ms, 달리기 최고 기록은 걸린 시간 ms.
 */
data class RideRecord(val kind: RideRecordKind, val ride: RideStat, val value: Double)

/** [start]는 주면 월요일, 달이면 1일. */
data class PeriodRecord(val start: LocalDate, val distanceM: Double)

/**
 * 연속 주행과 개인 기록(전체 기간). 모든 함수는 [StatsCalculator.rides]를 거친 주행만 본다.
 * "더 이른" 주행은 `startedAt`이 작은 쪽이고, 같으면 `segmentId`가 작은 쪽이다.
 * 기록은 같은 종류([RideRecordKind.activity]) 구간끼리만 비교한다.
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
     * [activity] 종류 구간의 항목별 최고 기록. 그 종류의 구간이 2회 미만이면 빈 목록. 같은 값이면 먼저 세운 주행.
     * [RideRecordKind.FASTEST_AVG]는 거리 [FASTEST_MIN_DISTANCE_M] 이상에 이동 시간이 0보다 큰 주행만 본다.
     * 달리기 최고 기록은 [efforts]에 저장된 값으로 찾고 작을수록 좋다.
     * 대상 주행이 없거나 최고값이 0 이하인 항목은 기록이 아니므로 빠진다(상승 0 m, 이동 시간 0 ms 등).
     * 자전거만 보는 호출은 `rideRecords(rides)`다.
     */
    fun rideRecords(
        rides: List<RideStat>,
        efforts: List<StoredBestEffort> = emptyList(),
        activity: ActivityType = ActivityType.BIKE,
    ): List<RideRecord> {
        val kept = StatsCalculator.rides(rides).filter { it.activity == activity }
        if (kept.size < 2) return emptyList()
        val times = effortTimes(efforts)
        return RideRecordKind.entries.filter { it.activity == activity }.mapNotNull { kind -> best(kept, kind, times) }
    }

    /** 거리가 가장 큰 주(월요일 시작). 같으면 먼저 주. 주행이 없으면 null. */
    fun bestWeek(rides: List<RideStat>): PeriodRecord? =
        bestPeriod(rides) { StatsCalculator.mondayOf(it.localDate) }

    /** 거리가 가장 큰 달(1일 시작). 같으면 먼저 달. 주행이 없으면 null. */
    fun bestMonth(rides: List<RideStat>): PeriodRecord? =
        bestPeriod(rides) { it.localDate.withDayOfMonth(1) }

    /** 어느 종류든 기록 항목 하나라도 갖고 있는 구간의 여행 id들. */
    fun recordHolderTrips(rides: List<RideStat>, efforts: List<StoredBestEffort> = emptyList()): Set<Long> =
        ActivityType.entries.flatMapTo(HashSet()) { activity -> rideRecords(rides, efforts, activity).map { it.ride.tripId } }

    /** 저장된 최고 기록이 없는 호출을 위한 [newRecords]. */
    fun newRecords(rides: List<RideStat>, segmentId: Long): List<RideRecordKind> =
        newRecords(rides, emptyList(), segmentId)

    /**
     * [segmentId] 구간이 세운 신기록 항목(그 구간의 종류 항목만). 그 항목에 해당하는 같은 종류의 더 이른 주행이 있고,
     * 이 구간이 그 최고값보다 나을 때만이다(큰 쪽이 좋은 항목은 더 클 때, [RideRecordKind.lowerIsBetter]는 더 작을 때.
     * 값이 0이면 신기록이 아니다). 이 구간이 짧은 기록이라 걸러졌으면 빈 목록.
     */
    fun newRecords(rides: List<RideStat>, efforts: List<StoredBestEffort>, segmentId: Long): List<RideRecordKind> {
        val kept = StatsCalculator.rides(rides)
        val target = kept.firstOrNull { it.segmentId == segmentId } ?: return emptyList()
        val earlier = kept.filter { it.activity == target.activity && isEarlier(it, target) }
        if (earlier.isEmpty()) return emptyList()
        val times = effortTimes(efforts)
        return RideRecordKind.entries.filter { kind ->
            if (kind.activity != target.activity) return@filter false
            val mine = valueOf(kind, target, times) ?: return@filter false
            if (mine <= 0.0) return@filter false
            val priors = earlier.mapNotNull { valueOf(kind, it, times) }
            val prior = (if (kind.lowerIsBetter) priors.minOrNull() else priors.maxOrNull()) ?: return@filter false
            if (kind.lowerIsBetter) mine < prior else mine > prior
        }
    }

    /** 구간별·거리별 저장된 가장 빠른 시간(ms). */
    private fun effortTimes(efforts: List<StoredBestEffort>): Map<Pair<Long, BestEffortDistance>, Long> =
        efforts.groupBy { it.segmentId to it.distance }.mapValues { (_, list) -> list.minOf { it.timeMs } }

    private fun isEarlier(a: RideStat, b: RideStat): Boolean =
        a.startedAt < b.startedAt || (a.startedAt == b.startedAt && a.segmentId < b.segmentId)

    /** 그 항목에 드는 주행이 아니면 null. */
    private fun valueOf(kind: RideRecordKind, ride: RideStat, times: Map<Pair<Long, BestEffortDistance>, Long>): Double? {
        if (ride.activity != kind.activity) return null
        return when (kind) {
            RideRecordKind.LONGEST_DISTANCE,
            RideRecordKind.RUN_LONGEST_DISTANCE,
            RideRecordKind.WALK_LONGEST_DISTANCE,
            -> ride.distanceM
            RideRecordKind.LONGEST_TIME, RideRecordKind.WALK_LONGEST_TIME -> ride.movingTimeMs.toDouble()
            RideRecordKind.MOST_ASCENT, RideRecordKind.HIKE_MOST_ASCENT -> ride.ascentM
            RideRecordKind.FASTEST_AVG ->
                if (ride.distanceM >= FASTEST_MIN_DISTANCE_M && ride.movingTimeMs > 0) {
                    ride.distanceM / (ride.movingTimeMs / 1000.0)
                } else {
                    null
                }
            RideRecordKind.HIKE_HIGHEST -> ride.maxAltitudeM
            RideRecordKind.HIKE_LONGEST_ELAPSED -> ride.elapsedMs.toDouble()
            RideRecordKind.RUN_BEST_1K,
            RideRecordKind.RUN_BEST_5K,
            RideRecordKind.RUN_BEST_10K,
            RideRecordKind.RUN_BEST_HALF,
            -> times[ride.segmentId to kind.effort!!]?.toDouble()
        }
    }

    /** [kept]는 시작 순이라 첫 최고가 가장 이른 주행이다(더 나은 값일 때만 바꾼다). */
    private fun best(kept: List<RideStat>, kind: RideRecordKind, times: Map<Pair<Long, BestEffortDistance>, Long>): RideRecord? {
        var best: RideRecord? = null
        for (ride in kept) {
            val v = valueOf(kind, ride, times) ?: continue
            if (v <= 0.0) continue
            if (best == null || (if (kind.lowerIsBetter) v < best.value else v > best.value)) best = RideRecord(kind, ride, v)
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
