package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoPoint
import java.time.LocalDate

/** 누적 지도·요약에 쓰는 완료 구간 한 건. [points]는 이미 단순화된 경로. */
data class CumulativeEntry(
    val segmentId: Long,
    val localDate: LocalDate,
    val distanceM: Double,
    val movingTimeMs: Long,
    val points: List<GeoPoint>,
)

/** [month]는 [year]가 있을 때만 의미가 있다. 둘 다 null이면 전체. */
data class PeriodFilter(val year: Int? = null, val month: Int? = null)

data class CumulativeTotals(val distanceM: Double, val rides: Int, val movingTimeMs: Long)

object CumulativeSummary {

    fun filter(entries: List<CumulativeEntry>, period: PeriodFilter): List<CumulativeEntry> {
        val year = period.year ?: return entries
        val month = period.month
        return entries.filter {
            it.localDate.year == year && (month == null || it.localDate.monthValue == month)
        }
    }

    fun totals(entries: List<CumulativeEntry>): CumulativeTotals = CumulativeTotals(
        distanceM = entries.sumOf { it.distanceM },
        rides = entries.size,
        movingTimeMs = entries.sumOf { it.movingTimeMs },
    )

    /** 기록이 있는 해, 내림차순. */
    fun years(entries: List<CumulativeEntry>): List<Int> =
        entries.map { it.localDate.year }.distinct().sortedDescending()

    /** [year]에서 기록이 있는 달(1–12), 오름차순. */
    fun months(entries: List<CumulativeEntry>, year: Int): List<Int> =
        entries.filter { it.localDate.year == year }.map { it.localDate.monthValue }.distinct().sorted()
}
