package com.cowork.bikerecoder.core.stats

import java.time.LocalDate

/** 목표 입력 칸의 해석 결과. */
sealed interface GoalInput {
    /** 칸이 비었다: 그 목표를 없앤다. */
    data object Empty : GoalInput

    data class Valid(val km: Int) : GoalInput

    data object Invalid : GoalInput
}

/** 주간·연간 거리 목표 계산. 구간 거리는 모두 [StatsCalculator.rides]를 거친 것만 센다. */
object Goals {
    const val MIN_KM = 1
    const val MAX_KM = 100_000

    private val PLAIN = Regex("[0-9]+")
    private val GROUPED = Regex("[0-9]{1,3}(,[0-9]{3})+")

    /** 앞뒤 빈칸을 떼고, 숫자만이거나 천 단위 쉼표가 맞는 정수만 받는다. 범위는 [MIN_KM]~[MAX_KM]. */
    fun parse(text: String): GoalInput {
        val t = text.trim()
        if (t.isEmpty()) return GoalInput.Empty
        if (!PLAIN.matches(t) && !GROUPED.matches(t)) return GoalInput.Invalid
        val km = t.replace(",", "").toIntOrNull() ?: return GoalInput.Invalid
        return if (km in MIN_KM..MAX_KM) GoalInput.Valid(km) else GoalInput.Invalid
    }

    /** 목표가 있고, 더하기 전엔 미만이던 것이 더한 뒤 이상이 됐을 때만 true. */
    fun crossed(goalKm: Int?, beforeM: Double, afterM: Double): Boolean {
        if (goalKm == null) return false
        val goalM = goalKm * 1000.0
        return beforeM < goalM && afterM >= goalM
    }

    /**
     * "지금 속도면 연말에 약 N km". 올해 거리 ÷ 1월 1일부터 오늘까지 날 수 × 올해 날 수를 10 km 단위로 반올림
     * (반올림은 .5에서 올림). 7일째 전이거나 올해 거리가 0 이하면 null.
     */
    fun yearProjectionKm(yearDistanceM: Double, today: LocalDate): Int? {
        if (today.dayOfYear < 7 || yearDistanceM <= 0.0) return null
        val km = yearDistanceM / 1000.0
        val projected = km / today.dayOfYear * today.lengthOfYear()
        return Math.round(projected / 10.0).toInt() * 10
    }

    /**
     * 그 구간 `localDate`가 든 주의 거리 (그 구간 빼고, 넣고). 구간이 짧은 기록이라 걸러졌거나 없으면
     * 둘이 같아서 [crossed]가 false다.
     */
    fun weekBeforeAfter(rides: List<RideStat>, segmentId: Long): Pair<Double, Double> =
        beforeAfter(rides, segmentId) { StatsCalculator.mondayOf(it) }

    /** [weekBeforeAfter]와 같되 그 구간 `localDate`가 든 해 기준. */
    fun yearBeforeAfter(rides: List<RideStat>, segmentId: Long): Pair<Double, Double> =
        beforeAfter(rides, segmentId) { it.withDayOfYear(1) }

    private fun beforeAfter(
        rides: List<RideStat>,
        segmentId: Long,
        periodStart: (LocalDate) -> LocalDate,
    ): Pair<Double, Double> {
        val kept = StatsCalculator.rides(rides)
        val target = kept.firstOrNull { it.segmentId == segmentId }
        val after = if (target == null) 0.0 else {
            val start = periodStart(target.localDate)
            kept.filter { periodStart(it.localDate) == start }.sumOf { it.distanceM }
        }
        return (if (target == null) 0.0 else after - target.distanceM) to after
    }
}
