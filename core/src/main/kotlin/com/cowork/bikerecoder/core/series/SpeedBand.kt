package com.cowork.bikerecoder.core.series

import com.cowork.bikerecoder.core.activity.ActivityType
import java.util.Locale

/**
 * 속도 색 구간. 그래프와 지도 선이 같은 색을 쓴다. 구간 경계는 활동 종류마다 다르다([of]).
 * [legend]는 자전거(km/h) 범례 문구이고, 다른 종류는 [legend]에 종류를 넘긴다.
 */
enum class SpeedBand(val argb: Long, val legend: String) {
    SLOW(0xFF90CAF9, "10 미만"),
    EASY(0xFF26A69A, "10~15"),
    STEADY(0xFF66BB6A, "15~20"),
    FAST(0xFFFFA726, "20~25"),
    SPRINT(0xFFE53935, "25 이상"),
    ;

    /** `#RRGGBB`. 한 번만 만든다. */
    val hex: String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /** 종류별 범례 문구. 달리기는 페이스(분'초"/km), 나머지는 km/h. */
    fun legend(activity: ActivityType): String = LEGENDS.getValue(activity)[ordinal]

    companion object {
        /** 종류별 경계 속도(km/h) 네 개. 달리기는 7'00"·6'00"·5'00"·4'00"/km에 해당한다. */
        private val BOUNDS = mapOf(
            ActivityType.BIKE to doubleArrayOf(10.0, 15.0, 20.0, 25.0),
            ActivityType.RUN to doubleArrayOf(60.0 / 7.0, 10.0, 12.0, 15.0),
            ActivityType.WALK to doubleArrayOf(3.0, 4.0, 5.0, 6.0),
            ActivityType.HIKE to doubleArrayOf(1.0, 2.0, 3.0, 4.0),
        )

        private val LEGENDS = mapOf(
            ActivityType.BIKE to listOf("10 미만", "10~15", "15~20", "20~25", "25 이상"),
            ActivityType.RUN to listOf("7'00\" 넘게", "6'~7'", "5'~6'", "4'~5'", "4'00\" 미만"),
            ActivityType.WALK to listOf("3 미만", "3~4", "4~5", "5~6", "6 이상"),
            ActivityType.HIKE to listOf("1 미만", "1~2", "2~3", "3~4", "4 이상"),
        )

        /** 경계 값은 위 구간에 넣는다(자전거 10은 [EASY]). */
        fun of(kmh: Double, activity: ActivityType = ActivityType.BIKE): SpeedBand {
            val b = BOUNDS.getValue(activity)
            return when {
                kmh < b[0] -> SLOW
                kmh < b[1] -> EASY
                kmh < b[2] -> STEADY
                kmh < b[3] -> FAST
                else -> SPRINT
            }
        }
    }
}
