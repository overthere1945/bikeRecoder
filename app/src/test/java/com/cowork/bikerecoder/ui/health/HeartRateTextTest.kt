package com.cowork.bikerecoder.ui.health

import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.post.SegmentExtras
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The heart-rate row's words for each state (spec §3.2, §3.3). */
class HeartRateTextTest {

    private fun extras(status: HrStatus, calories: Double? = null) =
        SegmentExtras(1, hrStatus = status, avgBpm = 132, maxBpm = 171, calories = calories)

    @Test
    fun eachStateHasItsWords() {
        assertEquals("심박 가져오는 중", heartRateRowText(null))
        assertEquals("심박 가져오는 중", heartRateRowText(extras(HrStatus.PENDING)))
        assertEquals("심박 기록 없음", heartRateRowText(extras(HrStatus.NONE)))
        assertEquals("심박을 가져오지 못했습니다", heartRateRowText(extras(HrStatus.FAILED)))
        assertEquals("평균 심박 132 · 최고 171 bpm · 312 kcal", heartRateRowText(extras(HrStatus.READY, calories = 312.4)))
    }

    @Test
    fun noCaloriesIsADash() {
        assertEquals("평균 심박 132 · 최고 171 bpm · – kcal", heartRateRowText(extras(HrStatus.READY)))
        assertEquals("평균 심박 98 · 최고 120 bpm · – kcal", heartRateLine(98, 120, null))
        assertEquals("평균 심박 98 · 최고 120 bpm · 1235 kcal", heartRateLine(98, 120, 1_234.6))
    }
}
