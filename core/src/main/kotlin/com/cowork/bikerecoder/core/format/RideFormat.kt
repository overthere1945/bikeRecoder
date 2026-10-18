package com.cowork.bikerecoder.core.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** 기록 화면과 음성 문구가 함께 쓰는 표시 형식. 소수 처리는 [BigDecimal]로 하여 이진 부동소수 경계 오류를 피한다. */
object RideFormat {

    private val MPS_TO_KMH = BigDecimal("3.6")
    private val THOUSAND = BigDecimal(1_000)

    /** m/s를 km/h 소수 첫째 자리(HALF_UP)로 표기한다: 5.0 → "18.0". 단위는 붙이지 않는다. */
    fun speedKmh(mps: Double): String =
        BigDecimal(mps).multiply(MPS_TO_KMH).setScale(1, RoundingMode.HALF_UP).toPlainString()

    /** 분 단위로 내림한다. 1시간 미만 "{m}분", 이상 "{h}시간 {m}분". */
    fun duration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours == 0L) "${minutes}분" else "${hours}시간 ${minutes}분"
    }

    /** 초 단위로 내림한 "H:MM:SS". */
    fun clock(ms: Long): String {
        val totalSeconds = ms / 1_000
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    /** km 소수 둘째 자리로 내림하고 "km"를 붙인다: 3_009.9 → "3.00km". */
    fun distance(m: Double): String =
        "${BigDecimal(m).divide(THOUSAND, 2, RoundingMode.DOWN).toPlainString()}km"

    /** 가장 가까운 정수 미터로 반올림한다("123m"). 값이 없으면 "--". */
    fun altitude(m: Double?): String =
        if (m == null) "--" else "${BigDecimal(m).setScale(0, RoundingMode.HALF_UP).toPlainString()}m"

    /** "2026년 10월 12일". */
    fun date(d: LocalDate): String = "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}
