package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun longZigzagSplitsDeeplyWithoutOverflowingStackAndStaysWithinTolerance() {
        // 좌우 약 33 m로 번갈아 튀는 1만 점: 매 단계 가장 먼 점이 구간 끝 바로 옆이라
        // 재귀 구현이면 호출 깊이가 O(n)이 된다. 작은 스택(128 KB) 스레드에서 돌린다.
        val n = 10_000
        val pts = (0 until n).map { i ->
            GeoPoint(37.0 + (if (i % 2 == 0) 0.0003 else -0.0003), 127.0 + i * 0.00001)
        }
        var out: List<GeoPoint>? = null
        var failure: Throwable? = null
        val t = Thread(null, {
            try {
                out = RouteSimplifier.simplify(pts)
            } catch (e: Throwable) {
                failure = e
            }
        }, "simplify-small-stack", 128 * 1024L)
        t.start()
        t.join()
        assertEquals(null, failure)
        val result = out!!
        assertEquals(pts.first(), result.first())
        assertEquals(pts.last(), result.last())
        // 결과는 원본의 부분수열이다. 각 원본 점을 덮는 결과 선분까지의 거리를 확인한다.
        val indexOf = HashMap<GeoPoint, Int>(n * 2)
        pts.forEachIndexed { i, p -> indexOf[p] = i }
        val kept = result.map { indexOf.getValue(it) }
        var seg = 0
        var worst = 0.0
        for (i in pts.indices) {
            while (seg < kept.size - 2 && kept[seg + 1] < i) seg++
            worst = maxOf(worst, GeoMath.project(pts[i], result[seg], result[seg + 1]).distanceM)
        }
        assertTrue(worst <= RouteSimplifier.TOLERANCE_M + 0.5, "worst deviation $worst m")
        assertTrue(result.size > 2)
    }

    @Test
    fun customToleranceIsHonoured() {
        val pts = listOf(GeoPoint(37.0, 127.0), GeoPoint(37.0003, 127.0005), GeoPoint(37.0, 127.001)) // 중간 점이 약 33 m 벗어남
        assertEquals(3, RouteSimplifier.simplify(pts, 10.0).size)
        assertEquals(2, RouteSimplifier.simplify(pts, 100.0).size)
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
    fun polylineDecodeRejectsMalformedInput() {
        val valid = PolylineCodec.encode(listOf(GeoPoint(37.5665, 126.978), GeoPoint(37.5671, 126.9795)))
        // 위도 값 뒤에서 끝남(경도 없음)
        val firstLat = valid.takeWhile { it.code - 63 >= 0x20 } + valid[valid.indexOfFirst { it.code - 63 < 0x20 }]
        assertThrows<IllegalArgumentException> { PolylineCodec.decode(firstLat) }
        // 값 중간에서 잘림(이어짐 비트가 켜진 문자로 끝남)
        assertThrows<IllegalArgumentException> { PolylineCodec.decode("~") }
        assertThrows<IllegalArgumentException> { PolylineCodec.decode(valid.dropLast(1) + "~") }
        // 범위 밖 문자
        assertThrows<IllegalArgumentException> { PolylineCodec.decode("\u0020abc") }
        assertThrows<IllegalArgumentException> { PolylineCodec.decode("한글") }
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
