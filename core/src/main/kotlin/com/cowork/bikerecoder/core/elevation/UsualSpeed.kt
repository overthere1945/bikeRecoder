package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.recording.RideSegment

/**
 * 평소 속도(스펙 §1.6): 끝난 구간 중 거리 [MIN_DISTANCE_M] 이상·이동 시간 > 0인 것을 시작 시각 내림차순으로
 * [RECENT]개 골라 거리 합 ÷ 이동 시간 합. 그런 구간이 없으면 [DEFAULT_MPS]. 가져온 구간도 포함한다.
 */
object UsualSpeed {
    const val DEFAULT_MPS = 15.0 / 3.6
    const val RECENT = 10
    const val MIN_DISTANCE_M = 1_000.0

    fun of(segments: List<RideSegment>): Double {
        val picked = segments
            .filter { it.endedAt != null && it.totals.distanceM >= MIN_DISTANCE_M && it.totals.movingTimeMs > 0 }
            .sortedByDescending { it.startedAt }
            .take(RECENT)
        if (picked.isEmpty()) return DEFAULT_MPS
        val distanceM = picked.sumOf { it.totals.distanceM }
        val movingSec = picked.sumOf { it.totals.movingTimeMs } / 1_000.0
        return distanceM / movingSec
    }
}
