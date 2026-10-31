package com.cowork.bikerecoder.core.elevation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 오르막 보정 시간(스펙 §1.7). 단면의 칸(거리 축의 이웃 표본 사이)마다 그 가운데의 100 m 경사 g(%)로 속도를 정해
 * 시간을 더한다. 칸 길이는 실제 거리 차이를 쓰므로 마지막 칸이 짧아도 맞다.
 * - g ≥ 0: `max(base / (1 + UP_K × g), min(base, MIN_UP_MPS))` — 6 km/h 밑으로 내리지 않되 base가 더 느리면 base.
 * - g < 0: `base × min(MAX_DOWN_FACTOR, 1 + DOWN_K × |g|)`.
 * 단면이 없거나 단면 밖 거리는 `거리 ÷ base`.
 */
object RideTimeEstimator {
    const val UP_K = 0.12
    const val DOWN_K = 0.03
    const val MIN_UP_MPS = 6.0 / 3.6
    const val MAX_DOWN_FACTOR = 1.3

    /** [fromM]~[toM](단면 거리 축) 구간을 [baseMps]로 갈 때 걸리는 초. [toM] ≤ [fromM]이면 0. */
    fun seconds(profile: ElevationProfile?, fromM: Double, toM: Double, baseMps: Double): Double {
        if (toM <= fromM) return 0.0
        if (profile == null) return (toM - fromM) / baseMps

        val d = profile.distancesM
        val from = fromM.coerceIn(0.0, profile.totalM)
        val to = toM.coerceIn(0.0, profile.totalM)
        // 단면 밖으로 나간 거리(경로 길이와 단면 길이가 조금 다를 때)는 기준 속도로.
        var total = ((toM - fromM) - (to - from)) / baseMps
        for (i in 0 until d.size - 1) {
            if (d[i + 1] <= from) continue
            if (d[i] >= to) break
            val a = max(d[i], from)
            val b = min(d[i + 1], to)
            if (b <= a) continue
            total += (b - a) / speedMps(profile.gradeAt((a + b) / 2), baseMps)
        }
        return total
    }

    /** 경사 [gradePct]에서의 속도. */
    fun speedMps(gradePct: Double, baseMps: Double): Double =
        if (gradePct >= 0) {
            max(baseMps / (1 + UP_K * gradePct), min(baseMps, MIN_UP_MPS))
        } else {
            baseMps * min(MAX_DOWN_FACTOR, 1 + DOWN_K * abs(gradePct))
        }
}
