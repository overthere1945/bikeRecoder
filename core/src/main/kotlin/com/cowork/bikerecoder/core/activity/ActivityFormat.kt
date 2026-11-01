package com.cowork.bikerecoder.core.activity

import java.util.Locale
import kotlin.math.roundToInt

/** 활동 종류별 표시 형식. 달리기·걷기의 페이스(분'초"/km)를 만든다. */
object ActivityFormat {

    /** 페이스를 알 수 없을 때의 표시. */
    const val NO_PACE = "--'--\""

    /** 1 km/h 미만은 멈춘 것으로 본다. */
    private const val MIN_KMH = 1.0

    /** 60분/km를 넘는 페이스는 알 수 없는 것으로 본다. */
    private const val MAX_PACE_SECONDS = 3_600

    /** 속도(m/s)를 `5'32"`로. 알 수 없으면 [NO_PACE]. */
    fun pace(speedMps: Double?): String {
        val s = paceSeconds(speedMps) ?: return NO_PACE
        return String.format(Locale.ROOT, "%d'%02d\"", s / 60, s % 60)
    }

    /** 거리·시간으로 구한 평균 페이스. 거리나 시간이 0 이하면 [NO_PACE]. */
    fun paceOf(distanceM: Double, timeMs: Long): String {
        if (distanceM <= 0.0 || timeMs <= 0L) return NO_PACE
        return pace(distanceM / (timeMs / 1000.0))
    }

    /**
     * 1 km당 초(반올림). 속도가 null이거나 1 km/h 미만이거나, 반올림한 값이 3,600초를 넘으면 null.
     */
    fun paceSeconds(speedMps: Double?): Int? {
        if (speedMps == null || !speedMps.isFinite() || speedMps * 3.6 < MIN_KMH) return null
        val s = (1000.0 / speedMps).roundToInt()
        return if (s > MAX_PACE_SECONDS) null else s
    }
}
