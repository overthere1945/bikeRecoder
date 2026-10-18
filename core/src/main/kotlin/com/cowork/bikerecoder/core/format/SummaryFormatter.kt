package com.cowork.bikerecoder.core.format

import java.util.Locale
import kotlin.math.roundToInt

/** 경로 요약 문구(한국어) 포맷터. */
object SummaryFormatter {

    /** 1km 미만은 "850m", 이상은 소수 첫째 자리까지 "87.4km". */
    fun distance(m: Double): String =
        if (m < 1_000.0) "${m.roundToInt()}m" else String.format(Locale.ROOT, "%.1fkm", m / 1_000.0)

    /** [speedKmh] 기준 예상 시간. "약 5시간 50분", 1시간 미만 "약 25분". 분 단위로 반올림하며 최소 1분. */
    fun duration(m: Double, speedKmh: Double = 15.0): String {
        val minutes = (m / 1_000.0 / speedKmh * 60.0).roundToInt().coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "약 ${rest}분"
            rest == 0 -> "약 ${hours}시간"
            else -> "약 ${hours}시간 ${rest}분"
        }
    }

    /** "오르막 420m · 자전거도로 78%". [ratio]는 0.0..1.0. */
    fun ascentAndRatio(ascentM: Int, ratio: Double): String =
        "오르막 ${ascentM}m · 자전거도로 ${(ratio * 100).roundToInt()}%"
}
