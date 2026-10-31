package com.cowork.bikerecoder.core.elevation

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackAxisTest {

    /** 위도 1도 ≈ 111.2 km. 북쪽으로 [meters]만큼 간 점. */
    private fun north(startLat: Double, meters: Double) = GeoPoint(startLat + meters / 111_195.0, 127.0)

    /** [startLat]에서 북쪽으로 [count]점, 10 m·1초 간격. */
    private fun segment(startLat: Double, count: Int, startMs: Long = 0L): List<TrackPoint> =
        (0 until count).map { i -> TrackPoint(startMs + i * 1_000L, north(startLat, i * 10.0), null, null, 5f) }

    private fun lengthOf(seg: List<TrackPoint>): Double =
        seg.zipWithNext().sumOf { (a, b) -> GeoMath.distanceM(a.point, b.point) }

    @Test
    fun segmentsJoinWithoutGap() {
        val a = segment(37.0, 51) // 500 m
        val b = segment(37.0 + 1_500.0 / 111_195.0, 31, startMs = 600_000L) // a 끝에서 1 km 떨어진 곳부터 300 m
        val axis = assertNotNull(TrackAxis.of(listOf(a, b)))

        assertEquals(lengthOf(a) + lengthOf(b), axis.totalM, 1e-9)
        assertEquals(1, axis.boundariesM.size)
        assertEquals(lengthOf(a), axis.boundariesM[0], 1e-9)
        assertEquals(axis.distancesM[50], axis.distancesM[51], 1e-12) // 구간 사이 거리 0
        assertEquals(82, axis.points.size)
        assertEquals(listOf(a.map { it.point }, b.map { it.point }), axis.paths)
        assertContentEquals(IntArray(51) { 0 } + IntArray(31) { 1 }, axis.segmentOf)
        assertContentEquals((a + b).map { it.timeMillis }.toLongArray(), axis.timesMs)
        assertEquals(0.0, axis.distancesM.first())
        assertEquals(axis.totalM, axis.distancesM.last())
    }

    @Test
    fun emptySegmentsSkipped() {
        val a = segment(37.0, 11)
        val b = segment(37.1, 6, startMs = 100_000L)
        val plain = assertNotNull(TrackAxis.of(listOf(a, b)))
        val withEmpty = assertNotNull(TrackAxis.of(listOf(emptyList(), a, emptyList(), emptyList(), b, emptyList())))

        assertEquals(plain.points, withEmpty.points)
        assertEquals(plain.paths, withEmpty.paths)
        assertEquals(2, withEmpty.paths.size)
        assertContentEquals(plain.distancesM, withEmpty.distancesM)
        assertContentEquals(plain.segmentOf, withEmpty.segmentOf)
        assertEquals(plain.boundariesM, withEmpty.boundariesM)
        assertEquals(1, withEmpty.boundariesM.size)
    }

    @Test
    fun fewerThanTwoPointsIsNull() {
        assertNull(TrackAxis.of(emptyList()))
        assertNull(TrackAxis.of(listOf(emptyList(), emptyList())))
        assertNull(TrackAxis.of(listOf(segment(37.0, 1))))
        assertNull(TrackAxis.of(listOf(emptyList(), segment(37.0, 1), emptyList())))
        assertNotNull(TrackAxis.of(listOf(segment(37.0, 1), segment(37.1, 1))))
    }

    /** V1.7.0 코드(TrackAxis로 옮기기 전)로 [profileInput]에서 계산해 둔 값. */
    @Test
    fun profileUnchanged() {
        val p = assertNotNull(ElevationProfile.fromSegments(profileInput()))
        val expectedD = doubleArrayOf(
            0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0, 150.0,
            160.0, 170.0, 180.0, 190.0, 200.0, 210.0, 220.0, 230.0, 240.0, 250.0, 260.0, 270.0, 280.0, 290.0, 300.0,
            310.0, 320.0, 330.0, 340.0, 350.0, 360.0, 367.4089351882591,
        )
        val expectedE = doubleArrayOf(
            31.06664466650467, 31.59996704958943, 32.133289473226576, 33.199934339904665, 34.26657932406979,
            35.33322440578686, 36.39986961388405, 37.46651494155846, 38.53316036940508, 39.59980591472198,
            40.666451602675764, 41.73309740443992, 42.799743321596345, 43.866389365661014, 44.933035528244,
            46.767418000419596, 48.342998691750566, 49.65977756623027, 50.629407992501356, 51.18858311506085,
            50.7212201150955, 50.2538570484296, 49.69058520387209, 49.071568596239146, 48.604205334017934,
            48.13684200437749, 47.5660069306617, 46.97712920452724, 46.62287012722452, 46.455611907098486,
            46.48400870638737, 46.81153221642178, 47.44866207069937, 48.190919553076235, 48.85310029414309,
            49.36509870936666, 49.65864349844108, 49.89758093716947,
        )
        assertEquals(expectedD.size, p.distancesM.size)
        assertEquals(expectedE.size, p.elevationsM.size)
        expectedD.indices.forEach { assertEquals(expectedD[it], p.distancesM[it], 1e-9) }
        expectedE.indices.forEach { assertEquals(expectedE[it], p.elevationsM[it], 1e-9) }
        assertEquals(2, p.boundariesM.size)
        assertEquals(168.75333978450885, p.boundariesM[0], 1e-9)
        assertEquals(287.5060575845728, p.boundariesM[1], 1e-9)
    }

    /** profileUnchanged 입력: 빈 구간, 고도 null, 서로 떨어진 구간 셋. */
    private fun profileInput(): List<List<TrackPoint>> {
        fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)
        val a = (0..12).map { i ->
            TrackPoint(i * 2_000L, p(37.0 + i * 0.00012, 127.0 + i * 0.00005), if (i == 4) null else 30.0 + i * 1.5, null, 5f)
        }
        val b = (0..9).map { i ->
            TrackPoint(100_000L + i * 3_000L, p(37.01 + i * 0.0001, 127.002 - i * 0.00008), 52.0 - i * 0.7 + (i % 3) * 0.4, null, 5f)
        }
        val c = (0..6).map { i ->
            TrackPoint(200_000L + i * 1_000L, p(37.02, 127.01 + i * 0.00015), if (i == 0 || i == 6) null else 45.0 + i, null, 5f)
        }
        return listOf(emptyList(), a, b, emptyList(), c)
    }
}
