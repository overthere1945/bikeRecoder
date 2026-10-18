package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteSimplifierTest {

    @Test
    fun straightLineKeepsEnds() {
        val pts = (0 until 100).map { GeoPoint(37.0 + it * 0.0001, 127.0 + it * 0.0001) }
        val out = RouteSimplifier.simplify(pts)
        assertEquals(listOf(pts.first(), pts.last()), out)
    }

    @Test
    fun simplifyKeepsShapeWithinTolerance() {
        // 10개의 톱니(진폭 약 55 m), 톱니당 100점, 작은 결정적 흔들림 포함.
        val pts = (0 until 1000).map { i ->
            val phase = (i % 100) / 100.0
            val tri = if (phase < 0.5) phase * 2 else (1 - phase) * 2
            GeoPoint(
                lat = 37.0 + tri * 0.0005 + sin(i * 1.7) * 0.00001,
                lon = 127.0 + i * 0.00002,
            )
        }
        val out = RouteSimplifier.simplify(pts)
        assertTrue(out.size < pts.size * 0.2, "simplified size ${out.size}")
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        var worst = 0.0
        for (p in pts) {
            var best = Double.MAX_VALUE
            for (i in 0 until out.size - 1) {
                best = minOf(best, GeoMath.project(p, out[i], out[i + 1]).distanceM)
            }
            worst = maxOf(worst, best)
        }
        assertTrue(worst <= RouteSimplifier.TOLERANCE_M + 0.5, "worst deviation $worst m")
    }

    @Test
    fun longRouteDoesNotOverflowStack() {
        // 재귀 구현이면 깊은 호출이 생길 수 있는 곡선(로그 나선 비슷한 단조 곡률) 20만 점.
        val pts = (0 until 200_000).map { i ->
            val t = i * 0.00005
            GeoPoint(37.0 + 0.2 * sin(t), 127.0 + t * 0.001)
        }
        val out = RouteSimplifier.simplify(pts)
        assertTrue(out.size in 2 until pts.size)
    }

    @Test
    fun polylineRoundTrip() {
        val pts = listOf(
            GeoPoint(37.56650, 126.97800),
            GeoPoint(37.56712, 126.97955),
            GeoPoint(-33.86882, 151.20929),
            GeoPoint(0.0, -0.00001),
            GeoPoint(-12.34567, -179.99999),
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(pts))
        assertEquals(pts.size, decoded.size)
        for (i in pts.indices) {
            assertTrue(abs(pts[i].lat - decoded[i].lat) <= 1e-5, "lat $i")
            assertTrue(abs(pts[i].lon - decoded[i].lon) <= 1e-5, "lon $i")
        }
    }

    @Test
    fun polylineMatchesGoogleReferenceExample() {
        val pts = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineCodec.encode(pts))
        val decoded = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, decoded.size)
        assertEquals(43.252, decoded[2].lat, 1e-9)
        assertEquals(-126.453, decoded[2].lon, 1e-9)
    }

    @Test
    fun polylineEmpty() {
        assertEquals("", PolylineCodec.encode(emptyList()))
        assertEquals(emptyList(), PolylineCodec.decode(""))
    }

    @Test
    fun tinyInputUnchanged() {
        val p0 = GeoPoint(37.0, 127.0)
        val p1 = GeoPoint(37.1, 127.1)
        assertEquals(emptyList(), RouteSimplifier.simplify(emptyList()))
        assertEquals(listOf(p0), RouteSimplifier.simplify(listOf(p0)))
        assertEquals(listOf(p0, p1), RouteSimplifier.simplify(listOf(p0, p1)))
    }
}
