package com.cowork.bikerecoder.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OfflineProgressTest {
    @Test
    fun beforeStartShowsEstimatedSize() {
        // 15 000 tiles x 15 KB = 225 MB
        val p = OfflineProgress(1, 0, 15_000, 15_000 * 15_000L)
        assertEquals("지도 저장 준비 · 약 225 MB", p.chipText)
    }

    @Test
    fun tinyEstimateRoundsUpToOneMegabyte() {
        assertEquals("지도 저장 준비 · 약 1 MB", OfflineProgress(1, 0, 10, 150_000).chipText)
    }

    @Test
    fun startedShowsPercent() {
        val p = OfflineProgress(1, 50, 200, 1_000_000, started = true)
        assertEquals("지도 저장 중 25%", p.chipText)
        assertEquals("지도 저장 중 0%", OfflineProgress(1, 0, 0, 0, started = true).chipText)
        assertEquals("지도 저장 중 100%", OfflineProgress(1, 300, 200, 0, started = true).chipText)
    }

    @Test
    fun failedDownloadShowsNothing() {
        assertNull(OfflineProgress(1, 5, 10, 1_000, error = "boom", started = true).chipText)
        assertNull(OfflineProgress(1, 0, 10, 1_000, error = "boom").chipText)
    }
}
