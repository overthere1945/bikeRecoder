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

    @Test
    fun hikeElapsedFromSegmentStart() {
        // 일시정지 60초 뒤: 이동 15초 = 실제로 75초가 지났다(산행 시간은 쉰 시간을 뺀 이동 시간이 아니다).
        val paused = RideTimelineBuilder.build(
            listOf(segment(1, 0) to straightPoints(0, 11) + straightPoints(70_000, 11, startM = 50.0)),
            SEOUL,
        )
        assertEquals(0L, Hud.at(0, paused, emptyList()).elapsedMs)
        assertEquals(75_000L, Hud.at(15_000, paused, emptyList()).elapsedMs)

        // 둘째 구간은 자기 첫 점부터 센다.
        val first = straightPoints(0, 11)
        val second = straightPoints(3_600_000, 11, startM = 1_050.0)
        val two = RideTimelineBuilder.build(
            listOf(segment(1, 0) to first, segment(2, 3_600_000, LocalDate.of(2026, 10, 18)) to second),
            SEOUL,
        )
        assertEquals(9_000L, Hud.at(9_000, two, emptyList()).elapsedMs)
        assertEquals(5_000L, Hud.at(15_000, two, emptyList()).elapsedMs)
    }

    @Test
    fun ascentAccumulates() {
        // 100 → 104 → 108 → 104 → 110 m, 1초마다. 3 m 문턱: 오르막 4 + 4 + 6, 내리막은 세지 않는다.
        val altitudes = listOf(100.0, 104.0, 108.0, 104.0, 110.0)
        val points = altitudes.mapIndexed { i, a -> trackPoint(i * 1_000L, i * 5.0, a) }
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)

        assertEquals(0.0, Hud.at(0, timeline, emptyList()).ascentM, 1e-9)
        assertEquals(4.0, Hud.at(1_000, timeline, emptyList()).ascentM, 1e-9)
        assertEquals(8.0, Hud.at(2_000, timeline, emptyList()).ascentM, 1e-9)
        assertEquals(8.0, Hud.at(3_000, timeline, emptyList()).ascentM, 1e-9)
        assertEquals(14.0, Hud.at(4_000, timeline, emptyList()).ascentM, 1e-9)
        // 점 사이는 직선으로 보탠다: 3~4초 한가운데는 8 + 6 / 2.
        assertEquals(11.0, Hud.at(3_500, timeline, emptyList()).ascentM, 1e-9)
    }

    @Test
    fun ascentStartsOverInTheNextSegmentAndIgnoresMissingAltitude() {
        val first = listOf(trackPoint(0, 0.0, 100.0), trackPoint(1_000, 5.0, 110.0))
        val second = listOf(trackPoint(3_600_000, 50.0, 300.0), trackPoint(3_601_000, 55.0, null), trackPoint(3_602_000, 60.0, 305.0))
        val timeline = RideTimelineBuilder.build(
            listOf(segment(1, 0) to first, segment(2, 3_600_000, LocalDate.of(2026, 10, 18)) to second),
            SEOUL,
        )
        assertEquals(5.0, Hud.at(500, timeline, emptyList()).ascentM, 1e-9)
        // 둘째 구간의 첫 고도는 기준점일 뿐이다(300 → 305, 고도 없는 점은 건너뜀): 앞 구간의 10 m는 이어 세지 않는다.
        assertEquals(0.0, Hud.at(1_500, timeline, emptyList()).ascentM, 1e-9)
        assertEquals(5.0, Hud.at(timeline.totalMoveMs, timeline, emptyList()).ascentM, 1e-9)
    }
}
