package com.cowork.bikerecoder.core.activity

import com.cowork.bikerecoder.core.recording.TrackPoint
import com.cowork.bikerecoder.core.recording.north
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityChangeTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val startedAt = 1_000_000L
    private val seconds = 7_200

    /**
     * 1.5 km/h로 2시간 오르는 등산 점(1초 간격). 속도는 60초 주기의 사인으로 0.5~2.5 km/h 사이를 흔들린다.
     * 0.7 km/h 아래인 시간은 한 주기에 약 12초라 20초를 넘지 않는다. 난수는 쓰지 않는다.
     */
    private val hikePoints: List<TrackPoint> = run {
        var m = 0.0
        (0..seconds).map { t ->
            val kmh = 1.5 + 1.0 * sin(2 * PI * t / 60.0)
            if (t > 0) m += kmh / 3.6
            TrackPoint(startedAt + t * 1_000L, north(m), 100.0 + m * 0.1, (kmh / 3.6).toFloat(), 5f)
        }
    }
    private val endedAt = startedAt + seconds * 1_000L
    private val elapsedMs = seconds * 1_000L

    @Test
    fun hikeRecomputeKeepsMostMovingTime() {
        val t = assertNotNull(ActivityChange.recompute(startedAt, endedAt, hikePoints, ActivityType.HIKE, seoul))
        assertTrue(t.movingTimeMs > elapsedMs * 0.9, "${t.movingTimeMs} / $elapsedMs")
        assertEquals(elapsedMs, t.elapsedMs)
        assertTrue(t.distanceM in 2_900.0..3_100.0, "${t.distanceM}") // 1.5 km/h × 2시간
    }

    @Test
    fun bikeRecomputeCutsMovingTime() {
        val hike = assertNotNull(ActivityChange.recompute(startedAt, endedAt, hikePoints, ActivityType.HIKE, seoul))
        val bike = assertNotNull(ActivityChange.recompute(startedAt, endedAt, hikePoints, ActivityType.BIKE, seoul))
        assertTrue(bike.movingTimeMs < hike.movingTimeMs * 0.5, "${bike.movingTimeMs} vs ${hike.movingTimeMs}")
        assertTrue(bike.distanceM < hike.distanceM)
    }

    @Test
    fun doesNotTouchPoints() {
        val before = hikePoints.toList()
        ActivityChange.recompute(startedAt, endedAt, hikePoints, ActivityType.WALK, seoul)
        assertEquals(before, hikePoints)
    }

    @Test
    fun tooShortKeepsTotals() {
        assertNull(ActivityChange.recompute(startedAt, endedAt, hikePoints.take(1), ActivityType.BIKE, seoul))
        assertNull(ActivityChange.recompute(startedAt, endedAt, emptyList(), ActivityType.BIKE, seoul))
    }

    @Test
    fun tooShortDistanceKeepsTotals() {
        // 점은 둘이지만 5 m: MIN_RIDE_DISTANCE_M(10 m) 미만
        val two = listOf(
            TrackPoint(startedAt, north(0.0), null, null, 5f),
            TrackPoint(startedAt + 3_000, north(5.0), null, null, 5f),
        )
        assertNull(ActivityChange.recompute(startedAt, startedAt + 3_000, two, ActivityType.WALK, seoul))
    }
}
