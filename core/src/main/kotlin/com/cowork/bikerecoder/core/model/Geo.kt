package com.cowork.bikerecoder.core.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 위경도 좌표. */
data class GeoPoint(val lat: Double, val lon: Double)

/** 단말의 위치 측위 결과 한 건. */
data class LocationFix(
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timeMillis: Long,
)

/** 선분 ab 위에서 p에 가장 가까운 점의 투영 결과. fraction ∈ [0,1]. */
data class Projection(val point: GeoPoint, val fraction: Double, val distanceM: Double)

object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 두 좌표 사이의 대원거리(haversine), 미터 단위. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)

        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }

    /**
     * 선분 ab 위에서 p에 가장 가까운 점을 구한다.
     * 짧은 선분을 가정하고 위도 중심 등장방형(equirectangular) 근사로 평면에 투영한다.
     */
    fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val latRef = Math.toRadians(a.lat)
        val cosLat = cos(latRef)

        // 평면 좌표로 변환 (x = 경도 방향, y = 위도 방향), 단위: 라디안 * 지구 반경 = 미터
        fun toXy(point: GeoPoint): DoubleArray {
            val x = Math.toRadians(point.lon) * cosLat * EARTH_RADIUS_M
            val y = Math.toRadians(point.lat) * EARTH_RADIUS_M
            return doubleArrayOf(x, y)
        }

        val pxy = toXy(p)
        val axy = toXy(a)
        val bxy = toXy(b)

        val abx = bxy[0] - axy[0]
        val aby = bxy[1] - axy[1]
        val lenSq = abx * abx + aby * aby

        val rawFraction = if (lenSq == 0.0) {
            0.0
        } else {
            val apx = pxy[0] - axy[0]
            val apy = pxy[1] - axy[1]
            (apx * abx + apy * aby) / lenSq
        }
        val fraction = rawFraction.coerceIn(0.0, 1.0)

        val projLat = a.lat + fraction * (b.lat - a.lat)
        val projLon = a.lon + fraction * (b.lon - a.lon)
        val projected = GeoPoint(projLat, projLon)

        return Projection(point = projected, fraction = fraction, distanceM = distanceM(p, projected))
    }

    /** points와 같은 길이의 목록을 반환하며, [0] = 0.0, 각 원소는 시작점부터의 누적 거리(m). */
    fun cumulativeDistances(points: List<GeoPoint>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<Double>(points.size)
        var total = 0.0
        result.add(0.0)
        for (i in 1 until points.size) {
            total += distanceM(points[i - 1], points[i])
            result.add(total)
        }
        return result
    }
}
