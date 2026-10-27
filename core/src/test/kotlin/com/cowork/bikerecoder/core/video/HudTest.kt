package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.post.HeartRateSample
import java.time.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudTest {

    @Test
    fun hudSpeedTenSecondAverage() {
        // 0~30초는 초당 5 m(0~150 m), 31~60초는 초당 10 m(160~450 m).
        val slow = straightPoints(0, 31, stepM = 5.0)
        val fast = straightPoints(31_000, 30, startM = 160.0, stepM = 10.0)
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to slow + fast), SEOUL)

        val mid = Hud.at(30_000, timeline, emptyList())
        assertEquals(0.150, mid.distanceKm, 1e-6)
        // 20~40초: 100 m → 250 m, 20초에 150 m = 7.5 m/s.
        assertEquals(27.0, mid.speedKmh, 1e-6)
        // 처음·끝은 범위 안쪽만: 0~10초 5 m/s, 50~60초 10 m/s.
        assertEquals(18.0, Hud.at(0, timeline, emptyList()).speedKmh, 1e-6)
        assertEquals(36.0, Hud.at(60_000, timeline, emptyList()).speedKmh, 1e-6)
        assertEquals(0.450, Hud.at(60_000, timeline, emptyList()).distanceKm, 1e-6)
    }

    @Test
    fun hudDistanceSkipsGapBetweenSegments() {
        // 두 번째 구간은 1 km 떨어진 곳에서 시작한다(옮겨 간 거리는 세지 않는다).
        val first = straightPoints(0, 11, stepM = 5.0)
        val second = straightPoints(3_600_000, 11, startM = 1_050.0, stepM = 5.0)
        val timeline = RideTimelineBuilder.build(
            listOf(segment(1, 0) to first, segment(2, 3_600_000, LocalDate.of(2026, 10, 17)) to second),
            SEOUL,
        )
        val end = Hud.at(timeline.totalMoveMs, timeline, emptyList())
        assertEquals(0.100, end.distanceKm, 1e-6)
        // 10초 지점(구간 경계) 앞뒤 10초 = 0~20초에 100 m.
        assertEquals(18.0, Hud.at(10_000, timeline, emptyList()).speedKmh, 1e-6)
    }

    @Test
    fun hudHeartWithinThirtySeconds() {
        val timeline = simpleTimeline(120)
        assertEquals(130, Hud.at(60_000, timeline, listOf(HeartRateSample(90_000, 130))).heartBpm)
        assertNull(Hud.at(60_000, timeline, listOf(HeartRateSample(91_000, 130))).heartBpm)
        assertEquals(140, Hud.at(60_000, timeline, listOf(HeartRateSample(29_000, 120), HeartRateSample(61_000, 140))).heartBpm)
        assertNull(Hud.at(60_000, timeline, emptyList()).heartBpm)

        // 일시정지 60초 뒤: 이동 15초 = 실제 시각 75초. 심박은 실제 시각으로 찾는다.
        val paused = RideTimelineBuilder.build(
            listOf(segment(1, 0) to straightPoints(0, 11) + straightPoints(70_000, 11, startM = 50.0)),
            SEOUL,
        )
        assertEquals(150, Hud.at(15_000, paused, listOf(HeartRateSample(75_000, 150))).heartBpm)
        assertNull(Hud.at(15_000, paused, listOf(HeartRateSample(15_000, 150))).heartBpm)
    }

    @Test
    fun hudAltitudeNullWhenMissing() {
        assertNull(Hud.at(5_000, simpleTimeline(10), emptyList()).altitudeM)

        val withAltitude = listOf(trackPoint(0, 0.0, 100.0), trackPoint(10_000, 50.0, 120.0))
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to withAltitude), SEOUL)
        assertEquals(110.0, Hud.at(5_000, timeline, emptyList()).altitudeM!!, 1e-9)
    }
}
