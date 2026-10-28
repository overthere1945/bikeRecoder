package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElevationGraphTest {

    /** 1초에 5 m씩 북쪽으로, 고도는 [altitudes] 그대로인 한 구간. */
    private fun timeline(altitudes: List<Double?>): RideTimeline {
        val track = altitudes.mapIndexed { i, a -> trackPoint(i * 1_000L, i * 5.0, a) }
        return RideTimelineBuilder.build(listOf(segment(1, 0) to track), SEOUL)
    }

    @Test
    fun flatRideIsLow() {
        val graph = assertNotNull(ElevationGraph.of(timeline(listOf(10.0, 10.5, 11.0, 10.2))))
        assertTrue(graph.ys.max() - graph.ys.min() < 0.1)
    }

    @Test
    fun hundredMetreClimbFills() {
        val graph = assertNotNull(ElevationGraph.of(timeline(listOf(0.0, 25.0, 50.0, 75.0, 100.0))))
        assertTrue(graph.ys.min() <= 0.05, "min=${graph.ys.min()}")
        assertTrue(graph.ys.max() >= 0.95, "max=${graph.ys.max()}")
        assertTrue(graph.ys.all { it in 0.0..1.0 })
    }

    @Test
    fun verticalRangeIsAtLeast40mWithFourMetreFloor() {
        // 실제 범위 10 m → 40 m 범위, 가장 낮은 점은 바닥에서 4 m 위
        val graph = assertNotNull(ElevationGraph.of(timeline(listOf(100.0, 110.0))))
        val range = 40.0 + 4.0
        assertEquals(4.0 / range, graph.ys[0], 1e-9)
        assertEquals(14.0 / range, graph.ys[1], 1e-9)
    }

    @Test
    fun xsSpanZeroToOneByRouteDistance() {
        val graph = assertNotNull(ElevationGraph.of(timeline(listOf(0.0, 5.0, 3.0, 8.0, 1.0))))
        assertEquals(5, graph.xs.size)
        assertEquals(0.0, graph.xs.first(), 1e-9)
        assertEquals(1.0, graph.xs.last(), 1e-9)
        assertEquals(0.25, graph.xs[1], 1e-3)
    }

    @Test
    fun missingAltitudeGivesNull() {
        assertNull(ElevationGraph.of(timeline(listOf(null, 12.0, null, null))))
        assertNull(ElevationGraph.of(timeline(listOf(null, null, null))))
        assertNull(ElevationGraph.of(RideTimeline(emptyList())))
    }

    @Test
    fun pointsWithoutAltitudeAreSkipped() {
        val graph = assertNotNull(ElevationGraph.of(timeline(listOf(0.0, null, 10.0, null, 20.0))))
        assertEquals(3, graph.xs.size)
        assertEquals(0.0, graph.xs.first(), 1e-9)
        assertEquals(0.5, graph.xs[1], 1e-3)
        assertEquals(1.0, graph.xs.last(), 1e-9)
    }

    @Test
    fun multiDayIsOneLine() {
        val day1 = (0..4).map { trackPoint(it * 1_000L, it * 5.0, 10.0 + it) }
        val day2Start = 86_400_000L
        val day2 = (0..4).map { trackPoint(day2Start + it * 1_000L, 1_000.0 + it * 5.0, 20.0 + it) }
        val timeline = RideTimelineBuilder.build(
            listOf(segment(1, 0, LocalDate.of(2026, 10, 17)) to day1, segment(2, day2Start, LocalDate.of(2026, 10, 18)) to day2),
            SEOUL,
        )
        val graph = assertNotNull(ElevationGraph.of(timeline))
        assertEquals(0.0, graph.xs.first(), 1e-9)
        assertEquals(1.0, graph.xs.last(), 1e-9)
        for (i in 1 until graph.xs.size) assertTrue(graph.xs[i] >= graph.xs[i - 1], "xs[$i]")
        // 구간 사이 이동은 0이므로 둘째 날 첫 점의 x는 첫날 마지막 점과 같다
        assertEquals(graph.xs[4], graph.xs[5], 1e-9)
    }

    @Test
    fun doneFractionFollowsRouteDistance() {
        val timeline = timeline(listOf(0.0, 10.0, 20.0, 30.0, 40.0))
        val graph = assertNotNull(ElevationGraph.of(timeline))
        assertEquals(0.0, graph.doneFraction(timeline.at(0), timeline), 1e-9)
        assertEquals(0.5, graph.doneFraction(timeline.at(2_000), timeline), 1e-3)
        assertEquals(0.625, graph.doneFraction(timeline.at(2_500), timeline), 1e-3)
        assertEquals(1.0, graph.doneFraction(timeline.at(4_000), timeline), 1e-9)
        assertEquals(1.0, graph.doneFraction(timeline.at(99_000), timeline), 1e-9)
    }

    @Test
    fun doneFractionIgnoresGapBetweenSegments() {
        val day1 = (0..4).map { trackPoint(it * 1_000L, it * 5.0, 10.0) }
        val day2Start = 86_400_000L
        val day2 = (0..4).map { trackPoint(day2Start + it * 1_000L, 1_000.0 + it * 5.0, 10.0) }
        val timeline = RideTimelineBuilder.build(
            listOf(segment(1, 0, LocalDate.of(2026, 10, 17)) to day1, segment(2, day2Start, LocalDate.of(2026, 10, 18)) to day2),
            SEOUL,
        )
        val graph = assertNotNull(ElevationGraph.of(timeline))
        // 이동 시간 4초에 첫날이 끝나고 둘째 날이 이어진다(사이 1000 m는 세지 않음): 거리 20 m / 전체 40 m
        assertEquals(0.5, graph.doneFraction(timeline.at(4_000), timeline), 1e-3)
        // 둘째 날 5 m 더 가면 25 / 40
        assertEquals(0.625, graph.doneFraction(timeline.at(5_000), timeline), 1e-3)
    }
}
