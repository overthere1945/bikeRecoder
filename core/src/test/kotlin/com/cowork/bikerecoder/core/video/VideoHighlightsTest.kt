package com.cowork.bikerecoder.core.video

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoHighlightsTest {

    @Test
    fun fastestStretchFound() {
        // 0~60초는 초당 1 m(0~60 m), 61~90초는 초당 8 m(68~300 m).
        val slow = straightPoints(0, 61, stepM = 1.0)
        val fast = straightPoints(61_000, 30, startM = 68.0, stepM = 8.0)
        val highlights = VideoHighlights.alongM(timelineOf(slow + fast))
        assertEquals(1, highlights.size)
        assertTrue(highlights[0] > 60.0 && highlights[0] < 300.0, "빠른 곳 밖: ${highlights[0]}")
    }

    @Test
    fun climbTopFound() {
        // 200 m에서 100 m로 내려간 뒤 140 m까지 40 m 오르고 다시 100 m로(가장 높은 곳은 출발점).
        val altitudes = (0..100).map { 200.0 - it } + (1..40).map { 100.0 + it } + (1..40).map { 140.0 - it }
        val timeline = timelineOf(altitudePoints(altitudes))
        val top = timeline.routeDistance.cumulative[140]
        val highlights = VideoHighlights.alongM(timeline)
        assertEquals(3, highlights.size, "$highlights")
        assertEquals(highlights.sorted(), highlights)
        assertEquals(1, highlights.count { abs(it - top) <= 20.0 }, "$highlights, 꼭대기 $top")
        assertEquals(0.0, highlights[0], 1e-9) // 가장 높은 곳 = 출발점
    }

    @Test
    fun smallClimbIgnored() {
        val altitudes = List(30) { 100.0 } + (1..20).map { 100.0 + it } + List(30) { 120.0 }
        val highlights = VideoHighlights.alongM(timelineOf(altitudePoints(altitudes)))
        // 꼭대기 없음: 가장 빠른 곳(같은 빠르기면 첫 10초) + 가장 높은 곳(120 m에 처음 닿은 점).
        assertEquals(2, highlights.size, "$highlights")
        assertEquals(25.0, highlights[0], 1e-6)
        assertEquals(245.0, highlights[1], 1e-6)
    }

    @Test
    fun noAltitudeOnlyFastest() {
        assertEquals(1, VideoHighlights.alongM(simpleTimeline(60)).size)
        assertEquals(emptyList(), VideoHighlights.alongM(simpleTimeline(5)))
    }
}
