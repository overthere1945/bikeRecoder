package com.cowork.bikerecoder.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The closing card's six numbers (spec §3.7): heart rate or "주행 N회", calories or the ascent. */
class FrameRendererCellsTest {

    private fun totals(avgBpm: Int?, calories: Double?) =
        TripTotals(12_345.0, 3_600_000L, 3.43, 9.0, 120.4, avgBpm, calories)

    @Test
    fun sixCellsWithFallbacks() {
        val first = listOf(
            "거리" to "12.34km", "이동 시간" to "1시간 0분", "평균 속도" to "12.3km/h", "최고 속도" to "32.4km/h",
        )

        assertEquals(
            first + listOf("주행" to "2회", "상승 고도" to "120m"),
            FrameRenderer.summaryCells(totals(avgBpm = null, calories = null), segmentCount = 2),
        )
        assertEquals(
            first + listOf("평균 심박" to "132bpm", "칼로리" to "210kcal"),
            FrameRenderer.summaryCells(totals(avgBpm = 132, calories = 210.4), segmentCount = 2),
        )
        assertEquals(
            first + listOf("평균 심박" to "132bpm", "상승 고도" to "120m"),
            FrameRenderer.summaryCells(totals(avgBpm = 132, calories = null), segmentCount = 1),
        )
        assertEquals(
            first + listOf("주행" to "1회", "칼로리" to "210kcal"),
            FrameRenderer.summaryCells(totals(avgBpm = null, calories = 210.4), segmentCount = 1),
        )
    }

    @Test
    fun longDateSpellsTheWeekday() {
        assertEquals("10월 25일 일요일", FrameRenderer.longDate("10월 25일 (일)"))
        assertEquals("10월 17일 ~ 18일", FrameRenderer.longDate("10월 17일 ~ 18일"))
        assertEquals("10월 31일 ~ 11월 1일", FrameRenderer.longDate("10월 31일 ~ 11월 1일"))
    }
}
