package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BestEffortsTest {

    /** 적도 위 동쪽 직선. 지구 반지름 6,371,008.8 m 기준으로 1 m의 경도. */
    private val degPerM = 180.0 / (Math.PI * 6_371_008.8)

    private fun pt(timeMs: Long, meters: Double) = TrackPoint(
        timeMillis = timeMs,
        point = GeoPoint(0.0, meters * degPerM),
        altitudeM = null,
        speedMps = null,
        accuracyM = 5f,
    )

    /** 1초 간격으로 [speeds]의 각 구간(초 수, m/s)을 이어 붙인다. */
    private fun track(vararg legs: Pair<Int, Double>): List<TrackPoint> {
        val out = mutableListOf(pt(0, 0.0))
        var t = 0L
        var m = 0.0
        for ((seconds, mps) in legs) repeat(seconds) {
            t += 1000
            m += mps
            out += pt(t, m)
        }
        return out
    }

    private fun List<BestEffort>.of(d: BestEffortDistance) = firstOrNull { it.distance == d }

    @Test
    fun steadyFiveKGivesOneKTime() {
        val efforts = BestEfforts.find(track((1667 to 3.0))) // 5,001 m
        val one = assertNotNull(efforts.of(BestEffortDistance.ONE_K))
        assertEquals(333_333.0, one.timeMs.toDouble(), 1000.0)
        val five = assertNotNull(efforts.of(BestEffortDistance.FIVE_K))
        assertEquals(1_666_667.0, five.timeMs.toDouble(), 1000.0)
        assertNull(efforts.of(BestEffortDistance.TEN_K))
    }

    @Test
    fun findsFastMiddleKilometre() {
        // 0~2 km는 3 m/s, 2~3 km는 4 m/s, 3~5 km는 3 m/s.
        val pts = track((667 to 3.0), (250 to 4.0), (667 to 3.0)) // 2,001 + 1,000 + 2,001 m
        val one = assertNotNull(BestEfforts.find(pts).of(BestEffortDistance.ONE_K))
        assertEquals(250_000.0, one.timeMs.toDouble(), 1000.0)
        // 4 m/s 구간은 점 667~917.
        assertTrue(one.startIndex in 667..917, "startIndex=${one.startIndex}")
    }

    @Test
    fun interpolatesOvershoot() {
        // 10초 간격, 10초마다 30 m(3 m/s). 1 km 지점은 점 사이(33.33번째 간격)에 있다.
        val pts = (0..40).map { pt(it * 10_000L, it * 30.0) }
        val one = assertNotNull(BestEfforts.find(pts).of(BestEffortDistance.ONE_K))
        assertEquals(333_333.0, one.timeMs.toDouble(), 5.0)
        assertEquals(0, one.startIndex)
    }

    @Test
    fun doesNotCrossGap() {
        // 0.6 km, 15초 틈, 0.6 km. 틈을 건너면 1 km가 되지만 세지 않는다.
        val a = track((200 to 3.0)) // 600 m, 200 s
        val b = a.map { pt(it.timeMillis + 215_000, it.point.lon / degPerM + 600.0) }
        assertNull(BestEfforts.find(a + b).of(BestEffortDistance.ONE_K))
    }

    @Test
    fun doesNotCrossBackwardsTime() {
        val a = track((200 to 3.0))
        val b = a.map { pt(it.timeMillis - 1_000_000, it.point.lon / degPerM + 600.0) }
        assertNull(BestEfforts.find(a + b).of(BestEffortDistance.ONE_K))
    }

    @Test
    fun usesBestChunkAfterGap() {
        // 느린 1.2 km(2 m/s) + 틈 + 빠른 1.2 km(5 m/s).
        val slow = track((600 to 2.0))
        val offset = 1200.0
        val fast = track((240 to 5.0)).map { pt(it.timeMillis + 700_000, it.point.lon / degPerM + offset) }
        val one = assertNotNull(BestEfforts.find(slow + fast).of(BestEffortDistance.ONE_K))
        assertEquals(200_000.0, one.timeMs.toDouble(), 1000.0)
        assertTrue(one.startIndex >= slow.size)
    }

    @Test
    fun tooShortHasNone() {
        val efforts = BestEfforts.find(track((1633 to 3.0))) // 4,899 m
        assertNotNull(efforts.of(BestEffortDistance.ONE_K))
        assertNull(efforts.of(BestEffortDistance.FIVE_K))
    }

    @Test
    fun emptyAndSinglePoint() {
        assertTrue(BestEfforts.find(emptyList()).isEmpty())
        assertTrue(BestEfforts.find(listOf(pt(0, 0.0))).isEmpty())
    }

    @Test
    fun ofKeyAndHalf() {
        assertEquals(BestEffortDistance.HALF, BestEffortDistance.ofKey(21098))
        assertEquals(21097.5, BestEffortDistance.HALF.targetM)
        assertEquals(BestEffortDistance.ONE_K, BestEffortDistance.ofKey(1000))
        assertNull(BestEffortDistance.ofKey(42))
    }

    @Test
    fun hundredThousandPointsUnderOneSecond() {
        val pts = (0 until 100_000).map { pt(it * 1000L, it * 3.0) }
        BestEfforts.find(pts) // warm-up
        val t0 = System.nanoTime()
        val efforts = BestEfforts.find(pts)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(4, efforts.size)
        assertTrue(ms < 1000, "took $ms ms")
    }
}
