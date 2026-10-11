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
