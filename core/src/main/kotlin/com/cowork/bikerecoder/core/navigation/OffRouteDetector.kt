package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.LocationFix

/**
 * 경로 이탈을 판정한다.
 *
 * 정확도가 나쁜 위치(`accuracyM > maxAccuracyM`)는 무시한다(타이머 유지, 초기화하지 않음).
 * `lateralOffsetM < thresholdM`이면 연속 이탈 타이머를 초기화한다.
 * `lateralOffsetM >= thresholdM`인 첫 관측 시각부터 `durationMs` 이상 지속되고,
 * 마지막 재탐색 종료로부터 `minRerouteIntervalMs`가 지났으면 true를 한 번 반환한다.
 * `onRerouteFinished`가 호출되기 전까지는 다시 true를 반환하지 않는다.
 */
class OffRouteDetector(
    private val thresholdM: Double = 40.0,
    private val durationMs: Long = 8_000,
    private val maxAccuracyM: Float = 30f,
    private val minRerouteIntervalMs: Long = 15_000,
) {

    private var streakStartMillis: Long? = null
    private var awaitingRerouteFinish: Boolean = false
    private var lastRerouteFinishedMillis: Long? = null

    fun onFix(fix: LocationFix, lateralOffsetM: Double): Boolean {
        if (fix.accuracyM > maxAccuracyM) {
            return false
        }

        if (lateralOffsetM < thresholdM) {
            streakStartMillis = null
            return false
        }

        val streakStart = streakStartMillis ?: fix.timeMillis.also { streakStartMillis = it }

        if (awaitingRerouteFinish) {
            return false
        }

        val durationElapsed = fix.timeMillis - streakStart >= durationMs
        if (!durationElapsed) {
            return false
        }

        val lastFinished = lastRerouteFinishedMillis
        val intervalOk = lastFinished == null || fix.timeMillis - lastFinished >= minRerouteIntervalMs
        if (!intervalOk) {
            return false
        }

        awaitingRerouteFinish = true
        return true
    }

    /** 재탐색 성공/실패와 관계없이 호출한다. 연속 이탈 판정을 초기화한다. */
    fun onRerouteFinished(timeMillis: Long) {
        awaitingRerouteFinish = false
        streakStartMillis = null
        lastRerouteFinishedMillis = timeMillis
    }
}
