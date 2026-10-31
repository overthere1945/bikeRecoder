package com.cowork.bikerecoder.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryFormatterTest {
    @Test
    fun formats() {
        assertEquals("87.4km", SummaryFormatter.distance(87_400.0))
        assertEquals("약 5시간 50분", SummaryFormatter.duration(87_400.0))
        assertEquals("약 20분", SummaryFormatter.duration(5_000.0))
        assertEquals("오르막 420m · 자전거도로 78%", SummaryFormatter.ascentAndRatio(420, 0.781))
    }

    @Test
    fun `short distances use meters`() {
        assertEquals("850m", SummaryFormatter.distance(850.0))
        assertEquals("1.0km", SummaryFormatter.distance(1_000.0))
    }

    @Test
    fun `whole hours and tiny trips`() {
        assertEquals("약 1시간", SummaryFormatter.duration(15_000.0))
        assertEquals("약 1분", SummaryFormatter.duration(10.0))
        assertEquals("약 1시간 30분", SummaryFormatter.duration(30_000.0, speedKmh = 20.0))
    }

    @Test
    fun `duration of seconds rounds to minutes like duration`() {
        assertEquals("약 5시간 50분", SummaryFormatter.durationOf(20_976.0)) // 349.6분
        assertEquals("약 1시간", SummaryFormatter.durationOf(3_629.0)) // 60.48분
        assertEquals("약 1분", SummaryFormatter.durationOf(5.0))
        assertEquals("약 3분", SummaryFormatter.durationOf(150.0)) // 2.5분 → 3분
    }
}
