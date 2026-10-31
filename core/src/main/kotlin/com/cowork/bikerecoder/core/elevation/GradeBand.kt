package com.cowork.bikerecoder.core.elevation

/** 경사 7단계. 그래프·지도 선·범례가 같은 색과 글을 쓴다. [argb]는 0xAARRGGBB. */
enum class GradeBand(val argb: Long, val legend: String) {
    STEEP_DOWN(0xFF4FC3F7, "-6% 미만"),
    FLAT(0xFF1E63D6, "평지·완만"),
    UP_2(0xFFF9C80E, "2%"),
    UP_4(0xFFF57C00, "4%"),
    UP_6(0xFFE53935, "6%"),
    UP_8(0xFFA31515, "8%"),
    UP_10(0xFF7B1FA2, "10%");

    companion object {
        /** 경사(%)가 속한 단계. 경계는 위쪽 단계에 포함하지 않는다(예: 2.0은 FLAT, 2.01은 UP_2). */
        fun of(gradePct: Double): GradeBand = when {
            gradePct < -6.0 -> STEEP_DOWN
            gradePct <= 2.0 -> FLAT
            gradePct <= 4.0 -> UP_2
            gradePct <= 6.0 -> UP_4
            gradePct <= 8.0 -> UP_6
            gradePct <= 10.0 -> UP_8
            else -> UP_10
        }
    }
}

/** 같은 경사 단계가 이어지는 구간 [startM, endM]. */
data class BandRun(val startM: Double, val endM: Double, val band: GradeBand)
