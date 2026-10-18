package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.LocationFix

/**
 * [TrackFilter]가 채택한 위치.
 * [speedMps] = fix.speedMps ?: 직전 채택 위치와의 구간 속도(직전 없음·dt ≤ 0이면 0).
 * [reanchored]면 거리 연결을 끊는다(이때 구간 속도는 의미가 없으므로 fix.speedMps ?: 0).
 */
data class AcceptedFix(val fix: LocationFix, val speedMps: Double, val reanchored: Boolean)

/**
 * 정확도 > [maxAccuracyM] 제외, 직전 채택 위치 대비 구간 속도 > [maxSpeedMps] 제외.
 * 속도 때문에 연달아 버려지는 위치가 [reanchorAfter]번째가 되면 그 위치를 버리지 않고
 * 새 기준점으로 받는다(`reanchored = true`). 정확도로 버려진 위치는 연속 횟수에 세지 않는다.
 */
class TrackFilter(
    private val maxAccuracyM: Float = 30f,
    private val maxSpeedMps: Double = 80.0 / 3.6,
    private val reanchorAfter: Int = 5,
) {
    private var last: LocationFix? = null
    private var jumpRejects = 0

    /** null = 버림. */
    fun accept(fix: LocationFix): AcceptedFix? {
        if (fix.accuracyM > maxAccuracyM) return null

        val prev = last
        if (prev == null) {
            last = fix
            jumpRejects = 0
            return AcceptedFix(fix, fix.speedMps?.toDouble() ?: 0.0, reanchored = false)
        }

        val dtMs = fix.timeMillis - prev.timeMillis
        val impliedMps = if (dtMs > 0) GeoMath.distanceM(prev.point, fix.point) / (dtMs / 1000.0) else 0.0

        if (impliedMps > maxSpeedMps) {
            jumpRejects++
            if (jumpRejects < reanchorAfter) return null
            jumpRejects = 0
            last = fix
            return AcceptedFix(fix, fix.speedMps?.toDouble() ?: 0.0, reanchored = true)
        }

        jumpRejects = 0
        last = fix
        return AcceptedFix(fix, fix.speedMps?.toDouble() ?: impliedMps, reanchored = false)
    }
}
