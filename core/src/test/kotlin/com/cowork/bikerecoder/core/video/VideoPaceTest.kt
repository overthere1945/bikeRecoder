package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPaceTest {

    /** 북쪽으로 곧게 2 km(5 m마다 1초). */
    private val straight2km = timelineOf(straightPoints(0, 401))

    /** [from]~[to] m 사이의 평균 빠르기(거리 ÷ 그 사이 영상 시간, 경로 미터/초). */
    private fun PaceProfile.meanSpeed(from: Double, to: Double) = (to - from) / (secondAt(to) - secondAt(from))

    @Test
    fun rideSecondsFollowsTheSquareRoot() {
        assertEquals(11.0, VideoPace.rideSeconds(1.0), 0.05)
        assertEquals(21.8, VideoPace.rideSeconds(10.0), 0.1)
        assertEquals(41.4, VideoPace.rideSeconds(50.0), 0.1)
        assertEquals(4.0, VideoPace.rideSeconds(0.01), 1e-9)
    }

    @Test
    fun alongStartsAtZeroEndsAtTotalAndNeverDecreases() {
        val pace = VideoPace.profile(straight2km, 15.0, emptyList(), emptyList())
        val total = straight2km.routeDistance.total
        assertEquals(2_000.0, total, 1e-6)
        assertEquals(total, pace.totalM, 1e-9)
        assertEquals(0.0, pace.alongAt(0.0), 1e-9)
        assertEquals(total, pace.alongAt(15.0), 1e-9)
        var previous = 0.0
        for (i in 0 until 300) {
            val s = 15.0 * i / 299
            val along = pace.alongAt(s)
            assertTrue(along >= previous, "${s}초에서 줄어듦: $previous → $along")
            assertEquals(s, pace.secondAt(along), 1e-6)
            previous = along
        }
    }

    @Test
    fun speedRatioAtMostFour() {
        // 북쪽 1.5 km 뒤 동쪽 1.5 km, 사진 셋.
        val timeline = timelineOf(pathPoints(listOf(0.0 to 0.0, 1_500.0 to 0.0, 1_500.0 to 1_500.0)))
        val seconds = VideoPace.rideSeconds(3.0)
        val pace = VideoPace.profile(timeline, seconds, listOf(500.0, 1_600.0, 2_500.0), listOf(1_500.0))
        val factors = (0..400).map { pace.speedFactor(2.0 + (seconds - 4.0) * it / 400) }
        assertTrue(factors.max() / factors.min() <= 4.0 + 1e-6, "빠르기 비 ${factors.max() / factors.min()}")
    }

    @Test
    fun straightIsFasterThanACurve() {
        // 북쪽 1 km 곧게, 그 뒤 100 m마다 90° 꺾는 지그재그 1 km(동·북 번갈아).
        val corners = mutableListOf(0.0 to 0.0, 1_000.0 to 0.0)
        repeat(10) { k ->
            val (n, e) = corners.last()
            corners += if (k % 2 == 0) n to e + 100.0 else n + 100.0 to e
        }
        val timeline = timelineOf(pathPoints(corners))
        val total = timeline.routeDistance.total
        assertEquals(2_000.0, total, 0.5)
        val pace = VideoPace.profile(timeline, VideoPace.rideSeconds(2.0), emptyList(), emptyList())
        val straight = pace.meanSpeed(0.0, 1_000.0)
        val zigzag = pace.meanSpeed(1_000.0, total)
        assertTrue(straight >= 1.5 * zigzag, "곧은 길 $straight, 지그재그 $zigzag")
    }

    @Test
    fun slowsBeforeAPhoto() {
        val pace = VideoPace.profile(straight2km, 15.0, listOf(1_000.0), emptyList())
        val beforePhoto = pace.meanSpeed(860.0, 990.0)
        val plain = pace.meanSpeed(300.0, 600.0)
        assertTrue(beforePhoto <= 0.6 * plain, "사진 앞 $beforePhoto, 곧은 길 $plain")
    }

    @Test
    fun easesInAndOut() {
        val pace = VideoPace.profile(straight2km, 15.0, emptyList(), emptyList())
        val middle = pace.speedFactor(7.5)
        assertTrue(pace.speedFactor(0.2) < 0.5 * middle, "시작 ${pace.speedFactor(0.2)}, 가운데 $middle")
        assertTrue(pace.speedFactor(14.8) < 0.5 * middle, "끝 ${pace.speedFactor(14.8)}, 가운데 $middle")
    }

    @Test
    fun warpPutsAnchorsExactly() {
        val pace = VideoPace.profile(straight2km, 15.0, listOf(1_200.0), emptyList())
        val warped = pace.warped(listOf(5.0 to 5.3, 15.0 to 15.0))
        assertEquals(15.0, warped.seconds, 1e-12)
        assertEquals(5.3, warped.secondAt(pace.alongAt(5.0)), 1e-6)
        assertEquals(pace.alongAt(5.0), warped.alongAt(5.3), 1e-6)
        assertEquals(pace.totalM, warped.alongAt(15.0), 1e-9)
        var previous = 0.0
        for (i in 0 until 300) {
            val along = warped.alongAt(15.0 * i / 299)
            assertTrue(along >= previous)
            previous = along
        }
    }

    @Test
    fun jitterInPlaceGetsNoTime() {
        // 1 km 곧게, 그 자리에서 ±3 m로 흔들리는 200점(10분, 3초 간격), 다시 1 km 곧게.
        val first = straightPoints(0, 201)
        val jitter = List(200) { trackPoint(203_000L + it * 3_000L, 1_000.0 + 3.0 * sin(it * 1.7)) }
        val second = straightPoints(803_000, 200, startM = 1_005.0)
        val timeline = timelineOf(first + jitter + second)
        val route = timeline.routeDistance
        val jitterFrom = route.cumulative[200]
        val jitterTo = route.cumulative[400]
        assertTrue(jitterTo - jitterFrom > 500.0, "흔들림이 경로 거리를 늘린다: ${jitterTo - jitterFrom}")

        val pace = VideoPace.profile(timeline, 15.0, emptyList(), emptyList())
        val spent = pace.secondAt(jitterTo) - pace.secondAt(jitterFrom)
        assertTrue(spent < 0.3, "흔들림에 쓴 시간 ${spent}초")
        assertEquals(route.total, pace.alongAt(15.0), 1e-9)
    }
}
