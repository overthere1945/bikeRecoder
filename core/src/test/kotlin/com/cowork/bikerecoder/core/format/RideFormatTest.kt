package com.cowork.bikerecoder.core.format

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class RideFormatTest {

    @Test
    fun `duration reading`() {
        assertEquals("0분", RideFormat.duration(59_999))
        assertEquals("59분", RideFormat.duration(3_599_999))
        assertEquals("1시간 0분", RideFormat.duration(3_600_000))
        assertEquals("1시간 30분", RideFormat.duration(5_400_000))
    }

    @Test
    fun `clock distance speed altitude date`() {
        assertEquals("0:05:07", RideFormat.clock(307_000))
        assertEquals("12:00:00", RideFormat.clock(43_200_000))
        assertEquals("3.00km", RideFormat.distance(3_009.9))
        assertEquals("12.34km", RideFormat.distance(12_345.0))
        assertEquals("18.0", RideFormat.speedKmh(5.0))
        assertEquals("--", RideFormat.altitude(null))
        assertEquals("124m", RideFormat.altitude(123.5))
        assertEquals("2026년 10월 12일", RideFormat.date(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun `clock floors partial seconds`() = assertEquals("0:00:59", RideFormat.clock(59_999))

    @Test
    fun `speed rounds half up at the decimal boundary`() {
        assertEquals("0.1", RideFormat.speedKmh(0.025))   // 0.09 km/h -> 0.1
        assertEquals("0.0", RideFormat.speedKmh(0.0))
        assertEquals("36.0", RideFormat.speedKmh(10.0))
    }

    @Test
    fun `altitude rounds negative values without a negative zero`() {
        assertEquals("0m", RideFormat.altitude(-0.4))
        assertEquals("-12m", RideFormat.altitude(-12.0))
    }
}
