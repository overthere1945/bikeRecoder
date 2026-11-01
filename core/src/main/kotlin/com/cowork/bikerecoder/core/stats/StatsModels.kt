package com.cowork.bikerecoder.core.stats

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import java.time.LocalDate

/** 통계에 쓰는 끝난 구간 한 건. 거르지 않은 상태로 들어오며, 걸러 쓰는 일은 [StatsCalculator.rides]가 한다. */
data class RideStat(
    val segmentId: Long,
    val tripId: Long,
    val startedAt: Long,
    val localDate: LocalDate,
    val distanceM: Double,
    val movingTimeMs: Long,
    val ascentM: Double,
    /** 끝난 시각(ms). 통계에는 쓰지 않고, 요약 카드 그림의 키가 구간 변경을 알아보는 데 쓴다. */
    val endedAt: Long = 0L,
    /** 구간의 활동 종류. */
    val activity: ActivityType = ActivityType.BIKE,
    /** 구간의 경과 시간(ms, 멈춘 때 포함). */
    val elapsedMs: Long = 0L,
    /** 구간의 가장 높은 고도(m). 고도를 모르면 null. */
    val maxAltitudeM: Double? = null,
)

/** 저장된 달리기 최고 기록 한 줄: [segmentId] 구간의 [distance] 거리를 [timeMs]에 달렸다. */
data class StoredBestEffort(val segmentId: Long, val distance: BestEffortDistance, val timeMs: Long)

enum class PeriodKind { WEEK, MONTH, YEAR, ALL }

/** [start]~[endInclusive] 날짜 구간(양 끝 포함). */
data class StatsPeriod(val kind: PeriodKind, val start: LocalDate, val endInclusive: LocalDate)

data class StatTotals(val distanceM: Double, val rides: Int, val movingTimeMs: Long, val ascentM: Double)

/** 막대 하나. [upcoming]은 [start]가 오늘보다 뒤라서 아직 오지 않은 칸. */
data class Bucket(val start: LocalDate, val endInclusive: LocalDate, val totals: StatTotals, val upcoming: Boolean)

/** [sameDay]가 true면 [current]는 이번 기간 오늘까지, [previous]는 앞 기간의 같은 날까지. */
data class Comparison(val current: StatTotals, val previous: StatTotals, val sameDay: Boolean)

/**
 * 날마다 쌓이는 누적. [current]`[i]`는 이번 기간 첫날부터 i번째 날까지의 합(이번 기간이면 오늘까지, 끝난 기간이면 기간 끝까지),
 * [previous]는 앞 기간 전체의 같은 누적, [length]는 두 기간의 날 수 중 큰 값(가로축 길이).
 */
data class CumulativeSeries(val current: List<StatTotals>, val previous: List<StatTotals>, val length: Int)
