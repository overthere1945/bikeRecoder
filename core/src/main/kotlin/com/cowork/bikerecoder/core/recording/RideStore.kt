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
    /** 평균 속도 = distanceM / (movingTimeMs / 1000.0), 이동 시간 0이면 0 ([DayTotals.avgSpeedMps]와 같은 식). */
    val avgSpeedMps: Double
        get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0

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
) {
    /** 간 거리가 없는 구간(쓸 만한 위치 없이 끝난 기록 등): 기록 목록에 보이지 않는다(데이터는 지우지 않는다). */
    val isEmpty: Boolean get() = totals.distanceM <= 0.0
}

/**
 * 목록 카드 한 장: dayCount = 구간이 있는 서로 다른 날짜 수, firstDate = 가장 이른 구간 날짜. 빈 구간([RideSegment.isEmpty])은
 * 세지 않는다.
 */
data class TripRideSummary(
    val trip: Trip,
    val firstDate: LocalDate,
    val dayCount: Int,
    val distanceM: Double,
    val movingTimeMs: Long,
    val lastStartedAt: Long,
)

/**
 * When [RideStore.deleteTripIf] may delete a trip; checked in the same statement that deletes it, so a trip that
 * becomes ACTIVE or starts recording meanwhile is never deleted. Every condition requires COMPLETED.
 */
enum class TripDeleteCondition {
    /** No segment at all, open or closed ([버리기] of a trip's last short segment, spec §6.10). */
    NO_SEGMENTS,

    /** No open segment (the history's delete of a finished trip, spec §3.5). */
    NOT_RECORDING,

    /** No open segment and under [com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M] in all ([정리], spec §6.10). */
    SHORT_NOT_RECORDING,
}

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

    /** 빈 구간이 아닌([RideSegment.isEmpty]) 구간이 1개 이상인 여행, lastStartedAt 내림차순. */
    suspend fun tripSummaries(): List<TripRideSummary>

    /** 위치 기록도 CASCADE로 지운다. */
    suspend fun deleteSegment(id: Long)

    /** trip 행을 지우면 경유지·구간·위치가 CASCADE로 지워진다. */
    suspend fun deleteTrip(tripId: Long)

    /** [deleteTrip], but only if [condition] holds at that moment (one statement); true if the trip was deleted. */
    suspend fun deleteTripIf(tripId: Long, condition: TripDeleteCondition): Boolean

    /** 구간이 있고 모두 빈 구간([RideSegment.isEmpty])인 여행 — [tripSummaries]에 없는 여행 (R21: 짧은 기록으로 센다). */
    suspend fun emptyTrips(): List<Trip>
}
