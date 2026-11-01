package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoPoint

/** 기록 중 화면·저장에 쓰는 주행 통계. 고도 값은 고도를 알 수 없으면 null. */
data class RideStats(
    val distanceM: Double,
    val movingTimeMs: Long,
    val elapsedMs: Long,
    val currentSpeedMps: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val altitudeM: Double?,
    val ascentM: Double,
    val descentM: Double,
    val minAltitudeM: Double?,
    val maxAltitudeM: Double?,
    /** 기록 중 사슬의 최근 30초 이동 거리 ÷ 시간. 창 안 점이 2개 미만이면(시작·일시정지 직후) null. */
    val recentSpeedMps: Double? = null,
    /** 이 세션의 마지막 [RecordingEvent.KmReached.lapMovingTimeMs]. 아직 km 표시가 없으면 null. */
    val lastLapMovingTimeMs: Long? = null,
    /**
     * 그 마지막 랩이 지난 km 수(앞 km 표시부터의 km 차이, 보통 1). 한 점이 여러 km를 건너뛰면 그 수다.
     * 1 km 페이스는 음성과 같이 [lastLapMovingTimeMs] ÷ 이 값이다.
     */
    val lastLapKms: Int = 1,
) {
    companion object {
        val ZERO = RideStats(
            distanceM = 0.0,
            movingTimeMs = 0L,
            elapsedMs = 0L,
            currentSpeedMps = 0.0,
            avgSpeedMps = 0.0,
            maxSpeedMps = 0.0,
            altitudeM = null,
            ascentM = 0.0,
            descentM = 0.0,
            minAltitudeM = null,
            maxAltitudeM = null,
            recentSpeedMps = null,
            lastLapMovingTimeMs = null,
        )
    }
}

/** 저장되는 궤적 점 하나. */
data class TrackPoint(
    val timeMillis: Long,
    val point: GeoPoint,
    val altitudeM: Double?,
    val speedMps: Float?,
    val accuracyM: Float,
)
