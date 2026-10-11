package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.trip.Trip
import java.time.LocalDate

/** 구간 하나의 누적 합계. 고도 값은 고도를 알 수 없으면 null. */
data class SegmentTotals(
    val distanceM: Double,
    val movingTimeMs: Long,
    val elapsedMs: Long,
    val maxSpeedMps: Double,
    val ascentM: Double,
    val descentM: Double,
    val minAltitudeM: Double?,
    val maxAltitudeM: Double?,
) {
    companion object {
        val ZERO = SegmentTotals(
            distanceM = 0.0,
            movingTimeMs = 0L,
            elapsedMs = 0L,
            maxSpeedMps = 0.0,
            ascentM = 0.0,
            descentM = 0.0,
            minAltitudeM = null,
            maxAltitudeM = null,
        )
    }
}

fun RideStats.toTotals() = SegmentTotals(
    distanceM = distanceM,
    movingTimeMs = movingTimeMs,
    elapsedMs = elapsedMs,
    maxSpeedMps = maxSpeedMps,
    ascentM = ascentM,
    descentM = descentM,
    minAltitudeM = minAltitudeM,
    maxAltitudeM = maxAltitudeM,
)

/** 한 번의 기록 시작~종료. [endedAt]이 null이면 아직 열려 있다. */
data class RideSegment(
    val id: Long,
    val tripId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val localDate: LocalDate,
    val totals: SegmentTotals,
)

/** 목록 카드 한 장: dayCount = 구간이 있는 서로 다른 날짜 수, firstDate = 가장 이른 구간 날짜. */
data class TripRideSummary(
    val trip: Trip,
    val firstDate: LocalDate,
    val dayCount: Int,
    val distanceM: Double,
    val movingTimeMs: Long,
    val lastStartedAt: Long,
)

interface RideStore {
    suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long

    /** 점 삽입과 구간 합계 갱신을 한 트랜잭션으로 한다. */
    suspend fun appendPoints(segmentId: Long, points: List<TrackPoint>, totals: SegmentTotals)

    suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals)

    /** endedAt == null인 구간, startedAt 내림차순. */
    suspend fun openSegments(): List<RideSegment>

    suspend fun segment(id: Long): RideSegment?

    /** startedAt 오름차순. */
    suspend fun segments(tripId: Long): List<RideSegment>

    /** timeMillis 오름차순. */
    suspend fun points(segmentId: Long): List<TrackPoint>

    /** 그 여행·그 날짜 구간의 합. */
    suspend fun dayTotals(tripId: Long, localDate: LocalDate): DayTotals

    /** 구간이 1개 이상인 여행, lastStartedAt 내림차순. */
    suspend fun tripSummaries(): List<TripRideSummary>

    /** 위치 기록도 CASCADE로 지운다. */
    suspend fun deleteSegment(id: Long)

    /** trip 행을 지우면 경유지·구간·위치가 CASCADE로 지워진다. */
    suspend fun deleteTrip(tripId: Long)
}
