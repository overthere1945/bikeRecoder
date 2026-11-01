package com.cowork.bikerecoder.core.activity

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SunTimesTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val oslo = ZoneId.of("Europe/Oslo")

    /** 일몰이 [hh]:[mm] ±2분인지 본다. */
    private fun assertSunset(lat: Double, lon: Double, date: LocalDate, zone: ZoneId, hh: Int, mm: Int) {
        val ms = assertNotNull(SunTimes.sunset(lat, lon, date, zone), "no sunset for $date")
        val local = Instant.ofEpochMilli(ms).atZone(zone)
        assertEquals(date, local.toLocalDate())
        val actual = local.hour * 60 + local.minute + local.second / 60.0
        assertEquals((hh * 60 + mm).toDouble(), actual, 2.0, "got $local")
    }

    @Test
    fun seoulSummerSolstice() = assertSunset(37.5663, 126.9779, LocalDate.of(2026, 6, 21), seoul, 19, 57)

    @Test
    fun seoulWinterSolstice() = assertSunset(37.5663, 126.9779, LocalDate.of(2026, 12, 22), seoul, 17, 18)

    @Test
    fun seoulEquinox() = assertSunset(37.5663, 126.9779, LocalDate.of(2026, 3, 20), seoul, 18, 43)

    @Test
    fun jejuSummerSolstice() = assertSunset(33.4996, 126.5312, LocalDate.of(2026, 6, 21), seoul, 19, 47)

    @Test
    fun jejuWinterSolstice() = assertSunset(33.4996, 126.5312, LocalDate.of(2026, 12, 22), seoul, 17, 30)

    @Test
    fun polarNightHasNoSunset() {
        assertNull(SunTimes.sunset(69.6492, 18.9553, LocalDate.of(2026, 12, 21), oslo))
    }

    @Test
    fun midnightSunHasNoSunset() {
        assertNull(SunTimes.sunset(69.6492, 18.9553, LocalDate.of(2026, 6, 21), oslo))
    }

    @Test
    fun farFromZoneMeridianStillReturnsLocalDate() {
        // 경도 126도인데 UTC-8 시간대(날짜 경계가 어긋나는 경우)에서도 그 지역 날짜의 일몰을 낸다.
        val zone = ZoneId.of("America/Los_Angeles")
        val date = LocalDate.of(2026, 6, 21)
        val ms = assertNotNull(SunTimes.sunset(37.5663, 126.9779, date, zone))
        assertEquals(date, Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    }
}
