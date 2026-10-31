package com.cowork.bikerecoder.core.series

import java.util.Locale

/** 속도 색 구간(km/h). 그래프와 지도 선이 같은 색을 쓴다. [legend]는 범례 문구. */
enum class SpeedBand(val argb: Long, val legend: String) {
    SLOW(0xFF90CAF9, "10 미만"),
    EASY(0xFF26A69A, "10~15"),
    STEADY(0xFF66BB6A, "15~20"),
    FAST(0xFFFFA726, "20~25"),
    SPRINT(0xFFE53935, "25 이상"),
    ;

    /** `#RRGGBB`. */
    val hex: String get() = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    companion object {
        /** 경계 값은 위 구간에 넣는다(10은 [EASY]). */
        fun of(kmh: Double): SpeedBand = when {
            kmh < 10.0 -> SLOW
            kmh < 15.0 -> EASY
            kmh < 20.0 -> STEADY
            kmh < 25.0 -> FAST
            else -> SPRINT
        }
    }
}
