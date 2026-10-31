package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationProfileTest {

    /** 위도 1도 ≈ 111.2 km. 북쪽으로 [meters]만큼 간 점. */
    private fun north(startLat: Double, meters: Double, lon: Double = 127.0) =
        GeoPoint(startLat + meters / 111_195.0, lon)

    private fun tp(i: Int, p: GeoPoint, alt: Double?) = TrackPoint(i * 1000L, p, alt, null, 5f)

    /** 북쪽으로 [lengthM]를 [spacingM] 간격으로 가는 구간. 고도는 [alt](거리). */
    private fun segment(
        startLat: Double,
        lengthM: Double,
        spacingM: Double = 10.0,
        alt: (Double) -> Double?,
    ): List<TrackPoint> {
        val n = (lengthM / spacingM).toInt()
        return (0..n).map { i -> tp(i, north(startLat, i * spacingM), alt(i * spacingM)) }
    }

    private fun profileOf(vararg segs: List<TrackPoint>) = ElevationProfile.fromSegments(segs.toList())

    @Test
    fun flatLineHasZeroGrade() {
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { 30.0 }))
        assertEquals(10.0, p.stepM)
        assertEquals(p.distancesM.size, p.elevationsM.size)
        assertEquals(p.distancesM.size, p.gradesPct.size)
        assertEquals(0.0, p.distancesM.first())
        assertTrue(p.gradesPct.all { kotlin.math.abs(it) < 1e-6 })
        assertEquals(30.0, p.minElevationM, 1e-6)
        assertEquals(30.0, p.maxElevationM, 1e-6)
        assertEquals(listOf(BandRun(0.0, p.totalM, GradeBand.FLAT)), p.bands)
        assertTrue(p.boundariesM.isEmpty())
    }

    @Test
    fun steadyClimbGradeIsFivePercent() {
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { 100.0 + it * 0.05 }))
        val mid = p.gradesPct.size / 2
        assertEquals(5.0, p.gradesPct[mid], 0.1)
        assertEquals(5.0, p.gradeAt(500.0), 0.1)
        assertEquals(GradeBand.UP_4, p.bandAt(500.0))
        assertEquals(125.0, p.elevationAt(500.0), 0.5)
        assertEquals(p.totalM, p.distancesM.last(), 1e-9)
    }

    @Test
    fun nullAltitudesAreSkipped() {
        val p = assertNotNull(
            profileOf(segment(37.0, 1000.0) { d -> if (d in 300.0..600.0) null else 50.0 + d * 0.02 }),
        )
        // 건너뛴 점 구간은 앞뒤 고도를 거리로 보간한다: 300 m에서 56, 700 m에서 64 -> 500 m는 약 60.
        assertEquals(60.0, p.elevationAt(500.0), 1.0)
        assertEquals(1000.0, p.totalM, 3.0)
    }

    @Test
    fun infiniteAltitudesAreSkippedLikeNull() {
        val withNull = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d == 500.0) null else 50.0 + d * 0.02 }))
        val withInf = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d == 500.0) Double.POSITIVE_INFINITY else 50.0 + d * 0.02 }))
        val withNegInf = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d == 500.0) Double.NEGATIVE_INFINITY else 50.0 + d * 0.02 }))
        for (p in listOf(withInf, withNegInf)) {
            assertTrue(p.elevationsM.all { it.isFinite() })
            assertTrue(p.gradesPct.all { it.isFinite() })
            assertTrue(p.minElevationM.isFinite() && p.maxElevationM.isFinite())
            assertEquals(withNull.bands, p.bands)
            assertEquals(withNull.elevationsM.toList(), p.elevationsM.toList())
            assertEquals(withNull.gradesPct.toList(), p.gradesPct.toList())
        }
    }

    @Test
    fun gradeWindowIsFiftyMetresEachSide() {
        // 500 m까지 평지, 이후 7% 오르막. 고도는 앞뒤 25 m 이동 평균으로 다듬고, 경사는 앞뒤 50 m의 차이로 잰다.
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d <= 500.0) 0.0 else (d - 500.0) * 0.07 }))
        // 400 m: 창(350~450 m)이 모두 평지.
        assertEquals(0.0, p.gradeAt(400.0), 0.05)
        // 450 m: 창(400~500 m)이 평지. 다듬기가 새는 만큼만 0을 넘는다. (창이 ±100 m였다면 3.5% 근처.)
        assertEquals(0.4, p.gradeAt(450.0), 0.3)
        // 500 m: 창(450~550 m)의 절반이 오르막이라 7%의 절반 근처.
        assertEquals(3.5, p.gradeAt(500.0), 0.4)
        // 550 m: 창(500~600 m)이 모두 오르막이라 7% 근처. (창이 ±25 m였어도 7%라 450·500 m가 창 폭을 가린다.)
        assertEquals(7.0, p.gradeAt(550.0), 0.6)
        assertEquals(7.0, p.gradeAt(700.0), 0.05)
    }

    @Test
    fun spikeIsSmoothed() {
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d == 500.0) 30.0 else 0.0 }))
        assertTrue(p.maxElevationM - p.minElevationM < 10.0, "diff=${p.maxElevationM - p.minElevationM}")
    }

    @Test
    fun tooFewAltitudesIsNull() {
        assertNull(profileOf(segment(37.0, 1000.0) { d -> if (d == 0.0) 10.0 else null }))
        assertNull(profileOf(segment(37.0, 1000.0) { null }))
        assertNull(ElevationProfile.fromSegments(emptyList()))
        assertNull(ElevationProfile.fromSegments(listOf(emptyList())))
    }

    @Test
    fun shorterThanFiftyMetresIsNull() {
        assertNull(profileOf(segment(37.0, 40.0, 10.0) { it }))
        assertNotNull(profileOf(segment(37.0, 60.0, 10.0) { it }))
    }

    @Test
    fun segmentsJoinWithoutGap() {
        val a = segment(37.0, 1000.0) { 10.0 + it * 0.01 }
        val b = segment(37.3, 1000.0) { 20.0 + it * 0.01 } // 약 33 km 떨어진 곳에서 다시 시작
        val p = assertNotNull(profileOf(a, b))
        assertEquals(2000.0, p.totalM, 6.0)
        assertEquals(1, p.boundariesM.size)
        assertEquals(1000.0, p.boundariesM[0], 3.0)
        // 이음매의 점은 원래 경로 위: 1,000 m 직전은 첫 구간 끝, 직후는 둘째 구간 시작.
        val before = p.pointAt(p.boundariesM[0] - 1.0)
        val after = p.pointAt(p.boundariesM[0] + 1.0)
        assertTrue(GeoMath.distanceM(before, a.last().point) < 5.0)
        assertTrue(GeoMath.distanceM(after, b.first().point) < 5.0)
    }

    @Test
    fun longTripUsesTwentyMetreStep() {
        val p = assertNotNull(profileOf(segment(36.0, 51_000.0, 100.0) { it * 0.01 }))
        assertEquals(20.0, p.stepM)
        assertEquals(20.0, p.distancesM[1] - p.distancesM[0])
    }

    @Test
    fun fiftyKmUsesTenMetreStep() {
        val points = listOf(GeoPoint(37.0, 127.0), GeoPoint(37.45, 127.0))
        val route = routeOf(points, listOf(0.0, 50_000.0), listOf(1, 1), listOf(10.0, 20.0))
        val p = assertNotNull(ElevationProfile.fromRoute(route))
        assertEquals(10.0, p.stepM)
        val over = routeOf(points, listOf(0.0, 50_000.5), listOf(1, 1), listOf(10.0, 20.0))
        assertEquals(20.0, assertNotNull(ElevationProfile.fromRoute(over)).stepM)
    }

    @Test
    fun bandsMergeRuns() {
        // 0~500 m 평지, 이후 7% 오르막 500 m. (8%는 UP_6/UP_8의 경계라 쓰지 않는다.)
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { d -> if (d <= 500.0) 0.0 else (d - 500.0) * 0.07 }))
        assertTrue(p.bands.size >= 2)
        assertEquals(0.0, p.bands.first().startM, 1e-9)
        assertEquals(p.totalM, p.bands.last().endM, 1e-9)
        for (i in 1 until p.bands.size) {
            assertEquals(p.bands[i - 1].endM, p.bands[i].startM, 1e-9)
            assertTrue(p.bands[i - 1].band != p.bands[i].band, "인접한 같은 단계는 합쳐야 한다")
        }
        assertEquals(GradeBand.FLAT, p.bands.first().band)
        assertEquals(GradeBand.UP_6, p.bandAt(800.0))
        assertEquals(GradeBand.FLAT, p.bandAt(100.0))
    }

    @Test
    fun pointAtInterpolates() {
        val p = assertNotNull(profileOf(segment(37.0, 1000.0, 50.0) { 10.0 }))
        val start = north(37.0, 0.0)
        val end = north(37.0, 1000.0)
        assertEquals(start, p.pointAt(-5.0))
        assertEquals(end, p.pointAt(p.totalM + 100.0))
        val mid = p.pointAt(p.totalM / 2)
        assertTrue(GeoMath.distanceM(mid, north(37.0, 500.0)) < 3.0)
        val quarter = p.pointAt(p.totalM / 4 + 12.0) // 점 사이
        assertTrue(GeoMath.distanceM(quarter, north(37.0, 250.0 + 12.0)) < 3.0)
    }

    @Test
    fun sliceWithNaNBoundIsNull() {
        val p = assertNotNull(profileOf(segment(37.0, 1000.0) { 10.0 }))
        assertNull(p.slice(Double.NaN, 100.0))
        assertNull(p.slice(0.0, Double.NaN))
    }

    @Test
    fun sliceRestartsAtZero() {
        val a = segment(37.0, 1000.0) { 100.0 + it * 0.05 }
        val b = segment(37.3, 1000.0) { 150.0 + it * 0.05 }
        val p = assertNotNull(profileOf(a, b))
        val s = assertNotNull(p.slice(800.0, 1500.0))
        assertEquals(0.0, s.distancesM.first())
        assertEquals(700.0, s.totalM, 1e-6)
        assertEquals(p.stepM, s.stepM)
        assertEquals(p.elevationAt(800.0), s.elevationAt(0.0), 1e-6)
        assertEquals(p.elevationAt(1500.0), s.elevationAt(700.0), 1e-6)
        assertEquals(1, s.boundariesM.size)
        assertEquals(p.boundariesM[0] - 800.0, s.boundariesM[0], 1e-6)
        assertTrue(GeoMath.distanceM(s.pointAt(0.0), p.pointAt(800.0)) < 1.0)
        assertTrue(GeoMath.distanceM(s.pointAt(700.0), p.pointAt(1500.0)) < 1.0)
        assertTrue(s.minElevationM >= p.minElevationM && s.maxElevationM <= p.maxElevationM)
        assertNull(p.slice(100.0, 149.0))
        assertNull(p.slice(300.0, 100.0))
        assertNotNull(p.slice(-50.0, 5_000.0)) // 범위를 벗어나면 맞춘다
    }

    private fun routeOf(
        points: List<GeoPoint>,
        cumulativeM: List<Double>,
        stops: List<Int>,
        elevations: List<Double?>,
    ) = Route(
        points = points,
        cumulativeM = cumulativeM,
        instructions = emptyList(),
        stopPointIndices = stops,
        summary = RouteSummary(cumulativeM.last(), 0, 0.0, RouteProfile.BALANCED),
        elevationsM = elevations,
    )

    @Test
    fun fromRouteUsesStopBoundaries() {
        val pts = (0..100).map { north(37.0, it * 20.0) }
        val route = routeOf(
            pts,
            GeoMath.cumulativeDistances(pts),
            stops = listOf(50, 100),
            elevations = pts.indices.map { 10.0 + it * 0.1 },
        )
        val p = assertNotNull(ElevationProfile.fromRoute(route))
        assertEquals(listOf(route.cumulativeM[50]), p.boundariesM)
        assertEquals(route.cumulativeM[100], p.totalM, 1e-9)
        assertEquals(10.0, p.elevationAt(0.0), 1.0)
    }

    @Test
    fun fromRouteWithoutElevationsIsNull() {
        val pts = (0..100).map { north(37.0, it * 20.0) }
        val route = routeOf(pts, GeoMath.cumulativeDistances(pts), listOf(100), emptyList())
        assertNull(ElevationProfile.fromRoute(route))
    }
}
