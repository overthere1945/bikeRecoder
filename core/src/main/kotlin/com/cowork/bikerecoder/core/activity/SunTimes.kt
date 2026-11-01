package com.cowork.bikerecoder.core.activity

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 일몰 시각 계산(NOAA 태양 위치식). 오차는 1~2분 안이다. */
object SunTimes {

    /** 해의 윗가장자리가 수평선에 닿는 천정 거리(도): 90° + 대기 굴절·태양 반지름. */
    private const val ZENITH_DEG = 90.833

    private const val UNIX_EPOCH_JD = 2440587.5
    private const val MS_PER_MIN = 60_000L

    /**
     * [date](시간대 [zone]의 날짜)의 일몰 시각, epoch ms. 해가 지지 않거나(백야) 뜨지 않는 날은 null.
     */
    fun sunset(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Long? {
        var epochDay = date.toEpochDay()
        // 경도 기준 태양일이 [zone]의 날짜와 어긋나면 하루씩 옮겨 한 번 더 구한다.
        repeat(2) {
            val ms = sunsetOnUtcDay(lat, lon, epochDay) ?: return null
            val local = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            when {
                local == date -> return ms
                local.isBefore(date) -> epochDay++
                else -> epochDay--
            }
        }
        return null
    }

    /** UTC 날짜 [epochDay]의 경도 기준 태양일에 있는 일몰. */
    private fun sunsetOnUtcDay(lat: Double, lon: Double, epochDay: Long): Long? {
        val jd0 = epochDay + UNIX_EPOCH_JD // 그 UTC 날 0시의 율리우스일
        // 지방 정오 근처에서 시작해 일몰 시각으로 두 번 다듬는다.
        var minutes = 720.0 - 4.0 * lon
        repeat(3) {
            val (decl, eqTime) = solarPosition(jd0 + minutes / 1440.0)
            val latR = Math.toRadians(lat)
            val cosH = cos(Math.toRadians(ZENITH_DEG)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
            if (cosH < -1.0 || cosH > 1.0) return null
            val haDeg = Math.toDegrees(acos(cosH))
            // 일몰은 정오 뒤: 720 - 4(경도 - 시간각) - 균시차 (분, UTC)
            minutes = 720.0 - 4.0 * (lon - haDeg) - eqTime
        }
        return epochDay * 86_400_000L + Math.round(minutes * MS_PER_MIN)
    }

    /** 율리우스일 [jd]의 (적위[rad], 균시차[분]). */
    private fun solarPosition(jd: Double): Pair<Double, Double> {
        val t = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mR = Math.toRadians(m)
        val c = sin(mR) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mR) * (0.019993 - 0.000101 * t) +
            sin(3 * mR) * 0.000289
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(l0 + c - 0.00569 - 0.00478 * sin(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(omega))
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).let { it * it }
        val l0R = Math.toRadians(l0)
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2 * l0R) - 2 * e * sin(mR) + 4 * e * y * sin(mR) * cos(2 * l0R) -
                0.5 * y * y * sin(4 * l0R) - 1.25 * e * e * sin(2 * mR),
        )
        return decl to eqTime
    }
}
