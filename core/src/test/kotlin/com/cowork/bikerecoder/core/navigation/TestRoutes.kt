package com.cowork.bikerecoder.core.navigation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import kotlin.math.cos

/**
 * navigation 패키지 테스트 전용 Route 생성 헬퍼.
 * 기준점(ORIGIN)에서 동쪽(east)/북쪽(north)으로 미터 단위 오프셋을 적용해 좌표를 만든다.
 * 등장방형 근사를 쓰므로 GeoMath.distanceM과 사실상 동일한 거리를 재현한다.
 */
private const val ORIGIN_LAT = 37.5
private const val ORIGIN_LON = 127.0
private const val METERS_PER_DEG_LAT = 111_320.0

fun pointAt(eastM: Double, northM: Double): GeoPoint {
    val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(ORIGIN_LAT))
    val lat = ORIGIN_LAT + northM / METERS_PER_DEG_LAT
    val lon = ORIGIN_LON + eastM / metersPerDegLon
    return GeoPoint(lat, lon)
}

private fun buildRoute(
    points: List<GeoPoint>,
    instructions: List<Instruction> = emptyList(),
    stopPointIndices: List<Int> = listOf(points.size - 1),
): Route {
    val cumulative = GeoMath.cumulativeDistances(points)
    return Route(
        points = points,
        cumulativeM = cumulative,
        instructions = instructions,
        stopPointIndices = stopPointIndices,
        summary = RouteSummary(
            totalDistanceM = cumulative.last(),
            ascentM = 0,
            cyclewayRatio = 0.0,
            profile = RouteProfile.BALANCED,
        ),
    )
}

/** 동쪽으로 뻗은 직선 경로. */
fun straightRoute(lengthM: Double = 1_000.0, instructions: List<Instruction> = emptyList()): Route {
    val points = listOf(pointAt(0.0, 0.0), pointAt(lengthM, 0.0))
    return buildRoute(points, instructions)
}

/** 여러 꼭짓점으로 완만하게 휘어지는 곡선 경로. */
fun curvedRoute(): Route {
    val points = listOf(
        pointAt(0.0, 0.0),
        pointAt(300.0, 0.0),
        pointAt(500.0, 200.0),
        pointAt(500.0, 500.0),
        pointAt(800.0, 700.0),
    )
    return buildRoute(points)
}

/** 왕복 경로: A -> B -> A, 동일한 직선을 따라 왕복한다. */
fun outAndBackRoute(oneWayLengthM: Double = 1_000.0): Route {
    val a = pointAt(0.0, 0.0)
    val b = pointAt(oneWayLengthM, 0.0)
    return buildRoute(listOf(a, b, a))
}

/** 순환 경로: 목적지가 출발점과 같다. */
fun loopRoute(): Route {
    val a = pointAt(0.0, 0.0)
    val points = listOf(
        a,
        pointAt(2_000.0, 0.0),
        pointAt(2_000.0, 2_000.0),
        pointAt(0.0, 2_000.0),
        a,
    )
    return buildRoute(points)
}

/**
 * 동쪽 [fromEastM]에서 시작해 [lengthM]를 [spacingM] 간격 꼭짓점으로 가는 직선 경로. 고도는 경로 위 거리의 함수 [elevation].
 * [turns]는 꼭짓점 인덱스 → 회전 종류.
 */
fun eastRouteWithElevation(
    lengthM: Double,
    fromEastM: Double = 0.0,
    spacingM: Double = 10.0,
    turns: Map<Int, com.cowork.bikerecoder.core.model.TurnType> = emptyMap(),
    elevation: (Double) -> Double,
): Route {
    val n = Math.round(lengthM / spacingM).toInt()
    val points = (0..n).map { pointAt(fromEastM + it * spacingM, 0.0) }
    val cumulative = GeoMath.cumulativeDistances(points)
    return buildRoute(
        points,
        turns.toSortedMap().map { (idx, type) -> Instruction(idx, type, 0, cumulative[idx]) },
    ).copy(elevationsM = cumulative.map(elevation))
}
