package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint

/**
 * 자동 일시정지 판정. 입력 속도는 위치 한 건의 속도다(이동 평균 아님).
 * 느림/빠름이 시작된 시각을 기억하고 조건이 깨지면 지운다. 비교는 Double 그대로, 허용 오차 없음.
 *
 * 정지 지점이 없으면(일시정지 판정 전·[reset] 후) 재개는 속도 조건만 본다.
 * 위치 공백(fix 사이의 긴 간격) 처리는 호출자 몫이고, 상태가 바뀔 때(재개·종료 등) 호출자가 [reset]을 불러야 한다.
 */
class AutoPauseDetector(
    private val pauseBelowMps: Double = 3.0 / 3.6,
    private val pauseAfterMs: Long = 5_000,
    private val resumeAtMps: Double = 5.0 / 3.6,
    private val resumeAfterMs: Long = 2_000,
    private val resumeDistanceM: Double = 15.0,
) {
    private var slowSinceMs: Long? = null
    private var fastSinceMs: Long? = null
    private var stopPoint: GeoPoint? = null

    /** 기록 중 위치마다. 속도 < 3km/h가 시작된 시각부터 timeMillis까지 ≥ 5초면 true(그때 위치가 정지 지점). */
    fun shouldPause(timeMillis: Long, speedMps: Double, point: GeoPoint): Boolean {
        if (speedMps >= pauseBelowMps) {
            slowSinceMs = null
            return false
        }
        val since = slowSinceMs ?: timeMillis.also { slowSinceMs = it }
        if (timeMillis - since < pauseAfterMs) return false
        stopPoint = point
        slowSinceMs = null
        fastSinceMs = null
        return true
    }

    /** 자동 일시정지 중 위치마다. 속도 ≥ 5km/h가 시작된 시각부터 ≥ 2초, 또는 정지 지점에서 ≥ 15m면 true. */
    fun shouldResume(timeMillis: Long, speedMps: Double, point: GeoPoint): Boolean {
        val stop = stopPoint
        val farFromStop = stop != null && GeoMath.distanceM(stop, point) >= resumeDistanceM
        if (speedMps >= resumeAtMps) {
            if (fastSinceMs == null) fastSinceMs = timeMillis
        } else {
            fastSinceMs = null
        }
        val fastLongEnough = fastSinceMs?.let { timeMillis - it >= resumeAfterMs } == true
        if (!farFromStop && !fastLongEnough) return false
        slowSinceMs = null
        fastSinceMs = null
        return true
    }

    fun reset() {
        slowSinceMs = null
        fastSinceMs = null
        stopPoint = null
    }
}
