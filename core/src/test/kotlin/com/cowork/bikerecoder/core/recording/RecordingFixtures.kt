package com.cowork.bikerecoder.core.recording

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix

/** 기준점에서 북쪽(경선 위)으로 [m]미터 간 좌표. GeoMath와 같은 지구 반경을 쓰므로 haversine 거리가 m과 같다. */
fun north(m: Double) = GeoPoint(37.5 + Math.toDegrees(m / 6_371_008.8), 127.0)

fun fix(
    t: Long,
    m: Double,
    speedKmh: Double? = null,
    accuracyM: Float = 5f,
    altitudeM: Double? = null,
    verticalAccuracyM: Float? = null,
): LocationFix = LocationFix(
    point = north(m),
    accuracyM = accuracyM,
    speedMps = speedKmh?.let { (it / 3.6).toFloat() },
    bearingDeg = null,
    timeMillis = t,
    altitudeM = altitudeM,
    verticalAccuracyM = verticalAccuracyM,
)
