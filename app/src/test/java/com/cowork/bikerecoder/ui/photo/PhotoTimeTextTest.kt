package com.cowork.bikerecoder.ui.photo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PhotoTimeTextTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun koreanMonthDayAndTwelveHourClock() {
        assertEquals("10월 18일 오후 2:31", photoTimeText(at(10, 18, 14, 31)))
        assertEquals("1월 5일 오전 9:05", photoTimeText(at(1, 5, 9, 5)))
        assertEquals("12월 31일 오전 12:00", photoTimeText(at(12, 31, 0, 0)))
        assertEquals("6월 1일 오후 12:00", photoTimeText(at(6, 1, 12, 0)))
    }
}
