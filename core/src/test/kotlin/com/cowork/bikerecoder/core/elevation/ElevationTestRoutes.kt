package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary

/**
 * elevation 시험용: 북쪽으로 [lengthM]를 [spacingM] 간격으로 가는 경로. 고도는 누적 거리의 함수 [elevation].
 * 마지막 점은 정확히 [lengthM] 위치(마지막 간격이 짧을 수 있다).
 */
internal fun elevationRoute(lengthM: Double, spacingM: Double = 10.0, elevation: (Double) -> Double): Route {
    val n = (lengthM / spacingM).toInt()
    val offsets = (0..n).map { it * spacingM }.toMutableList()
    if (lengthM - offsets.last() > 1e-9) offsets.add(lengthM)
    val points = offsets.map { GeoPoint(37.0 + it / 111_195.0, 127.0) }
    val cumulative = GeoMath.cumulativeDistances(points)
    return Route(
        points = points,
        cumulativeM = cumulative,
        instructions = emptyList(),
        stopPointIndices = listOf(points.size - 1),
        summary = RouteSummary(cumulative.last(), 0, 0.0, RouteProfile.BALANCED),
        elevationsM = cumulative.map(elevation),
    )
}

internal fun elevationProfile(lengthM: Double, spacingM: Double = 10.0, elevation: (Double) -> Double): ElevationProfile =
    checkNotNull(ElevationProfile.fromRoute(elevationRoute(lengthM, spacingM, elevation)))
