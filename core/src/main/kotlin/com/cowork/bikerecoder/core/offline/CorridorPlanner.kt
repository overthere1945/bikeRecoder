package com.cowork.bikerecoder.core.offline

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** 지구 반경(m). GeoMath와 동일한 값을 사용해 두 변환이 일치하게 한다. */
private const val EARTH_RADIUS_M = 6_371_008.8

private val GEOMETRY_FACTORY = GeometryFactory()

/** 위경도 경계 상자. */
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * 경로 주변 오프라인 지도 다운로드 범위 계획.
 *
 * @param polygon 회랑(corridor)의 외곽 링 목록. 각 링은 첫 점과 끝 점이 같은 닫힌 목록이다.
 *   (JTS buffer 결과가 MultiPolygon일 수 있어 목록으로 반환하며, 구멍(hole)은 무시한다.)
 * @param overview 회랑 전체를 포함하는 개요용 경계 상자(z5-9 다운로드 범위 계산에 쓰임).
 * @param estimatedTiles z10-14 회랑 타일 수 + z5-9 개요 타일 수의 합.
 * @param truncated maxTiles를 넘어 경로를 maxLengthM으로 잘랐는지 여부.
 */
data class CorridorPlan(
    val polygon: List<List<GeoPoint>>,
    val overview: BoundingBox,
    val estimatedTiles: Long,
    val truncated: Boolean,
)

/** 표준 Web Mercator XYZ 슬리피맵 타일 좌표 변환과 타일 수 추정. */
object TileMath {

    /**
     * 주어진 다각형(List<List<GeoPoint>>, 외곽 링 목록)과 교차하는 z[minZoom..maxZoom] XYZ 타일 수의 합.
     * 각 줌에서 다각형의 경계 상자로 후보 타일을 좁히고, 각 후보 타일 사각형이 다각형과 교차하는지 JTS로 판정한다.
     */
    fun tilesForPolygon(polygon: List<List<GeoPoint>>, minZoom: Int, maxZoom: Int): Long {
        val geometry = toJtsGeometry(polygon) ?: return 0L
        val envelope = geometry.envelopeInternal

        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val xMin = lonToTileX(envelope.minX, n)
            val xMax = lonToTileX(envelope.maxX, n)
            // 위도가 커질수록(북쪽) 타일 y는 작아진다.
            val yMin = latToTileY(envelope.maxY, n)
            val yMax = latToTileY(envelope.minY, n)

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    if (tilePolygon(x, y, n).intersects(geometry)) {
                        total += 1
                    }
                }
            }
        }
        return total
    }

    /** 경계 상자를 덮는 z[minZoom..maxZoom] XYZ 타일 수의 합(교차 판정 없이 상자 전체를 덮는 타일 수). */
    fun tilesForBox(box: BoundingBox, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val xMin = lonToTileX(box.west, n)
            val xMax = lonToTileX(box.east, n)
            val yMin = latToTileY(box.north, n)
            val yMax = latToTileY(box.south, n)

            val xCount = (xMax - xMin + 1).toLong()
            val yCount = (yMax - yMin + 1).toLong()
            total += xCount * yCount
        }
        return total
    }

    private fun toJtsGeometry(polygon: List<List<GeoPoint>>): Geometry? {
        val polygons = polygon.mapNotNull { ring ->
            if (ring.size < 4) return@mapNotNull null
            val coords = ring.map { Coordinate(it.lon, it.lat) }.toTypedArray()
            GEOMETRY_FACTORY.createPolygon(GEOMETRY_FACTORY.createLinearRing(coords))
        }
        if (polygons.isEmpty()) return null
        return if (polygons.size == 1) {
            polygons[0]
        } else {
            GEOMETRY_FACTORY.createMultiPolygon(polygons.toTypedArray())
        }
    }

    private fun lonToTileX(lon: Double, n: Int): Int {
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, n: Int): Int {
        val clampedLat = lat.coerceIn(-85.05112877980659, 85.05112877980659)
        val latRad = Math.toRadians(clampedLat)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    private fun tileLat(y: Int, n: Int): Double {
        val yFrac = y.toDouble() / n
        val latRad = atan(sinh(PI * (1.0 - 2.0 * yFrac)))
        return Math.toDegrees(latRad)
    }

    private fun tilePolygon(x: Int, y: Int, n: Int): Polygon {
        val west = x.toDouble() / n * 360.0 - 180.0
        val east = (x + 1).toDouble() / n * 360.0 - 180.0
        val north = tileLat(y, n)
        val south = tileLat(y + 1, n)
        val coords = arrayOf(
            Coordinate(west, south),
            Coordinate(east, south),
            Coordinate(east, north),
            Coordinate(west, north),
            Coordinate(west, south),
        )
        return GEOMETRY_FACTORY.createPolygon(GEOMETRY_FACTORY.createLinearRing(coords))
    }
}

/** 경로 주변 오프라인 다운로드 회랑(corridor)과 타일 수를 계산한다. */
object CorridorPlanner {

    private const val SIMPLIFY_TOLERANCE_M = 50.0
    private const val CORRIDOR_MIN_ZOOM = 10
    private const val CORRIDOR_MAX_ZOOM = 14
    private const val OVERVIEW_MIN_ZOOM = 5
    private const val OVERVIEW_MAX_ZOOM = 9

    /**
     * [route]에서 [fromDistanceAlongM] 이후 구간에 대한 회랑 계획을 만든다.
     * 타일 수가 [maxTiles]를 넘으면 [fromDistanceAlongM]부터 [maxLengthM]까지만 잘라 다시 계산하고 truncated=true로 표시한다.
     */
    fun plan(
        route: Route,
        fromDistanceAlongM: Double = 0.0,
        halfWidthM: Double = 2_000.0,
        maxTiles: Long = 15_000,
        maxLengthM: Double = 150_000.0,
    ): CorridorPlan {
        val full = buildPlan(route, fromDistanceAlongM, null, halfWidthM, truncated = false)
        if (full.estimatedTiles <= maxTiles) {
            return full
        }
        return buildPlan(
            route,
            fromDistanceAlongM,
            fromDistanceAlongM + maxLengthM,
            halfWidthM,
            truncated = true,
        )
    }

    private fun buildPlan(
        route: Route,
        fromDistanceAlongM: Double,
        toDistanceAlongM: Double?,
        halfWidthM: Double,
        truncated: Boolean,
    ): CorridorPlan {
        val points = selectPoints(route, fromDistanceAlongM, toDistanceAlongM)

        val centerLat = points.map { it.lat }.average()
        val cosLat = cos(Math.toRadians(centerLat))

        fun toXy(p: GeoPoint): Coordinate {
            val x = Math.toRadians(p.lon) * cosLat * EARTH_RADIUS_M
            val y = Math.toRadians(p.lat) * EARTH_RADIUS_M
            return Coordinate(x, y)
        }

        fun toGeoPoint(c: Coordinate): GeoPoint {
            val lat = Math.toDegrees(c.y / EARTH_RADIUS_M)
            val lon = Math.toDegrees(c.x / (EARTH_RADIUS_M * cosLat))
            return GeoPoint(lat, lon)
        }

        val lineCoords = points.map { toXy(it) }.toTypedArray()
        val line = GEOMETRY_FACTORY.createLineString(lineCoords)
        val buffered = line.buffer(halfWidthM)
        val simplified = DouglasPeuckerSimplifier.simplify(buffered, SIMPLIFY_TOLERANCE_M)

        val rings = extractExteriorRings(simplified).map { ring -> ring.map { toGeoPoint(it) } }

        val overview = boundingBoxOf(rings)
        val estimatedTiles =
            TileMath.tilesForPolygon(rings, CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM) +
                TileMath.tilesForBox(overview, OVERVIEW_MIN_ZOOM, OVERVIEW_MAX_ZOOM)

        return CorridorPlan(
            polygon = rings,
            overview = overview,
            estimatedTiles = estimatedTiles,
            truncated = truncated,
        )
    }

    /** cumulativeM이 [fromM, toM] 범위인 점들을 고른다(toM==null이면 상한 없음). 최소 2점을 보장한다. */
    private fun selectPoints(route: Route, fromM: Double, toM: Double?): List<GeoPoint> {
        val filtered = route.points.indices.filter { i ->
            val d = route.cumulativeM[i]
            d >= fromM && (toM == null || d <= toM)
        }.map { route.points[it] }

        return if (filtered.size >= 2) filtered else route.points.takeLast(max(2, route.points.size))
    }

    private fun boundingBoxOf(rings: List<List<GeoPoint>>): BoundingBox {
        var south = Double.POSITIVE_INFINITY
        var west = Double.POSITIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        for (ring in rings) {
            for (p in ring) {
                south = min(south, p.lat)
                north = max(north, p.lat)
                west = min(west, p.lon)
                east = max(east, p.lon)
            }
        }
        return BoundingBox(south, west, north, east)
    }

    private fun extractExteriorRings(geometry: Geometry): List<List<Coordinate>> {
        val polygons = when (geometry) {
            is Polygon -> listOf(geometry)
            is MultiPolygon -> (0 until geometry.numGeometries).map { geometry.getGeometryN(it) as Polygon }
            else -> emptyList()
        }
        return polygons.map { it.exteriorRing.coordinates.toList() }
    }
}
