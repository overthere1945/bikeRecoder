package com.cowork.bikerecoder.ui.activity

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The activity copy of the screens and the notification (V1.9.0 spec §2.4, §3.2). */
class ActivityTextTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val sunset = ZonedDateTime.of(2026, 11, 1, 17, 32, 0, 0, seoul).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Test
    fun names() {
        assertEquals(listOf("자전거", "걷기", "달리기", "등산"), ActivityType.entries.map(ActivityText::name))
    }

    @Test
    fun recordingTitleKeepsTheBikeWording() {
        assertEquals("주행 기록 중", ActivityText.recordingTitle(ActivityType.BIKE))
        assertEquals("걷기 기록 중", ActivityText.recordingTitle(ActivityType.WALK))
        assertEquals("달리기 기록 중", ActivityText.recordingTitle(ActivityType.RUN))
        assertEquals("등산 기록 중", ActivityText.recordingTitle(ActivityType.HIKE))
    }

    @Test
    fun sunsetLineBeforeAfter() {
        assertEquals("일몰 17:32 · 1시간 40분 남음", ActivityText.sunsetLine(sunset, sunset - 100 * minute, seoul))
        assertEquals("일몰 17:32 · 1시간 40분 남음", ActivityText.sunsetLine(sunset, sunset - 100 * minute - 59_999, seoul))
        assertEquals("일몰 17:32 · 0분 남음", ActivityText.sunsetLine(sunset, sunset - 1, seoul))
        assertEquals("일몰 17:32 · 지남", ActivityText.sunsetLine(sunset, sunset, seoul))
        assertEquals("일몰 17:32 · 지남", ActivityText.sunsetLine(sunset, sunset + 30 * minute, seoul))
        val morning = ZonedDateTime.of(2026, 6, 21, 7, 5, 0, 0, seoul).toInstant().toEpochMilli()
        assertEquals("일몰 07:05 · 지남", ActivityText.sunsetLine(morning, morning + 1, seoul))
    }

    @Test
    fun warningFromOneHour() {
        assertFalse(ActivityText.sunsetWarning(sunset, sunset - 60 * minute - 1))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset - 60 * minute))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset - 1))
        assertTrue(ActivityText.sunsetWarning(sunset, sunset + 60 * minute), "after the sunset too")
    }

    @Test
    fun notificationSunsetPartFromOneHour() {
        assertNull(ActivityText.sunsetNotice(sunset, sunset - 61 * minute))
        assertEquals("일몰까지 1시간 0분", ActivityText.sunsetNotice(sunset, sunset - 60 * minute))
        assertEquals("일몰까지 25분", ActivityText.sunsetNotice(sunset, sunset - 25 * minute - 30_000))
        assertEquals("일몰 지남", ActivityText.sunsetNotice(sunset, sunset))
    }
}
