package com.cowork.bikerecoder.core.offline

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * R9: 기존 TestRoutes.pointAt은 111_320 m/deg(위도)를 가정하지만 GeoMath는
 * R = 6_371_008.8 (약 111_195 m/deg)을 사용한다. 이 테스트 전용으로 GeoMath와
 * 일치하는 자체 오프셋 헬퍼를 사용한다(pointAt을 재사용하거나 수정하지 않음).
 */
private const val EARTH_RADIUS_M = 6_371_008.8
private val ORIGIN = GeoPoint(37.5, 127.0)
private val GEOMETRY_FACTORY = GeometryFactory()

private fun offset(origin: GeoPoint, eastM: Double, northM: Double): GeoPoint {
    val metersPerDegLat = EARTH_RADIUS_M * PI / 180.0
    val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))
    return GeoPoint(
        lat = origin.lat + northM / metersPerDegLat,
        lon = origin.lon + eastM / metersPerDegLon,
    )
}

/** origin으로부터의 동쪽 오프셋(m)을 역산한다. 회랑 경계의 along-route 범위를 검증할 때 쓴다. */
private fun eastMOf(p: GeoPoint, origin: GeoPoint): Double {
    val metersPerDegLat = EARTH_RADIUS_M * PI / 180.0
    val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))
    return (p.lon - origin.lon) * metersPerDegLon
}

private fun buildRoute(points: List<GeoPoint>): Route {
    val cumulative = GeoMath.cumulativeDistances(points)
    return Route(
        points = points,
        cumulativeM = cumulative,
        instructions = emptyList(),
        stopPointIndices = listOf(points.size - 1),
        summary = RouteSummary(
            totalDistanceM = cumulative.last(),
            ascentM = 0,
            cyclewayRatio = 0.0,
            profile = RouteProfile.BALANCED,
        ),
    )
}

/** ORIGIN에서 동쪽으로 뻗은 직선 경로. stepM 간격으로 점을 찍는다. */
private fun straightRoute(lengthM: Double, stepM: Double = 1_000.0): Route {
    val points = ArrayList<GeoPoint>()
    var d = 0.0
    while (d < lengthM) {
        points.add(offset(ORIGIN, d, 0.0))
        d += stepM
    }
    points.add(offset(ORIGIN, lengthM, 0.0))
    return buildRoute(points)
}

private fun toGeometry(rings: List<List<GeoPoint>>): Geometry {
    val polygons = rings.map { ring ->
        val coords = ring.map { Coordinate(it.lon, it.lat) }.toTypedArray()
        GEOMETRY_FACTORY.createPolygon(GEOMETRY_FACTORY.createLinearRing(coords))
    }
    return if (polygons.size == 1) {
        polygons[0]
    } else {
        GEOMETRY_FACTORY.createMultiPolygon(polygons.toTypedArray())
    }
}

class CorridorPlannerTest {

    @Test
    fun `corridor covers points 1_9km away but not 2_2km`() {
        val route = straightRoute(lengthM = 10_000.0, stepM = 10_000.0)
        val plan = CorridorPlanner.plan(route)
        val geometry = toGeometry(plan.polygon)

        val insidePoint = offset(ORIGIN, 5_000.0, 1_900.0)
        val outsidePoint = offset(ORIGIN, 5_000.0, 2_200.0)

        assertTrue(
            geometry.contains(GEOMETRY_FACTORY.createPoint(Coordinate(insidePoint.lon, insidePoint.lat))),
            "1.9km 지점은 회랑 안쪽이어야 함",
        )
        assertFalse(
            geometry.contains(GEOMETRY_FACTORY.createPoint(Coordinate(outsidePoint.lon, outsidePoint.lat))),
            "2.2km 지점은 회랑 바깥이어야 함",
        )
    }

    @Test
    fun `box tile count at zoom 14 for seoul box`() {
        val box = BoundingBox(south = 37.45, west = 126.85, north = 37.65, east = 127.15)
        val z = 14
        val n = 1 shl z

        fun lonToX(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt()
        fun latToY(lat: Double): Int {
            val latRad = Math.toRadians(lat)
            return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        }

        val xMin = lonToX(box.west)
        val xMax = lonToX(box.east)
        val yMin = latToY(box.north)
        val yMax = latToY(box.south)
        val expected = (xMax - xMin + 1).toLong() * (yMax - yMin + 1).toLong()

        val actual = TileMath.tilesForBox(box, z, z)

        assertEquals(expected, actual)
    }

    @Test
    fun `short route is not truncated`() {
        val route = straightRoute(lengthM = 20_000.0, stepM = 1_000.0)
        val plan = CorridorPlanner.plan(route)

        assertFalse(plan.truncated)
        assertTrue(plan.estimatedTiles < 15_000, "estimatedTiles=${plan.estimatedTiles}")
    }

    @Test
    fun `huge corridor is truncated to 150km`() {
        val route = straightRoute(lengthM = 300_000.0, stepM = 1_000.0)
        val plan = CorridorPlanner.plan(route, maxTiles = 500)

        assertTrue(plan.truncated)
        val maxEastM = plan.polygon.flatten().maxOf { eastMOf(it, ORIGIN) }
        assertTrue(
            maxEastM <= 150_000.0 + 2_000.0 + 100.0,
            "maxEastM=$maxEastM should stay within start + 150km + 2km",
        )
    }
}
