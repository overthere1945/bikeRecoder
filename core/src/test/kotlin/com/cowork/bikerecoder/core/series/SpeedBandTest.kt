package com.cowork.bikerecoder.core.series

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SpeedBandTest {

    @Test
    fun boundaries() {
        assertEquals(SpeedBand.SLOW, SpeedBand.of(0.0))
        assertEquals(SpeedBand.SLOW, SpeedBand.of(9.99))
        assertEquals(SpeedBand.EASY, SpeedBand.of(10.0))
        assertEquals(SpeedBand.EASY, SpeedBand.of(14.99))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(15.0))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(19.99))
        assertEquals(SpeedBand.FAST, SpeedBand.of(20.0))
        assertEquals(SpeedBand.FAST, SpeedBand.of(24.99))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(25.0))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(80.0))
    }

    @Test
    fun hexAndLegend() {
        assertEquals(
            listOf(
                Triple(SpeedBand.SLOW, "#90CAF9", "10 미만"),
                Triple(SpeedBand.EASY, "#26A69A", "10~15"),
                Triple(SpeedBand.STEADY, "#66BB6A", "15~20"),
                Triple(SpeedBand.FAST, "#FFA726", "20~25"),
                Triple(SpeedBand.SPRINT, "#E53935", "25 이상"),
            ),
            SpeedBand.entries.map { Triple(it, it.hex, it.legend) },
        )
        assertEquals(0xFF90CAF9, SpeedBand.SLOW.argb)
        assertEquals(0xFFE53935, SpeedBand.SPRINT.argb)
    }

    @Test
    fun bikeUnchanged() {
        assertEquals(SpeedBand.EASY, SpeedBand.of(10.0, ActivityType.BIKE))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(25.0, ActivityType.BIKE))
        assertEquals("10 미만", SpeedBand.SLOW.legend)
        assertEquals(SpeedBand.entries.map { it.legend }, SpeedBand.entries.map { it.legend(ActivityType.BIKE) })
    }

    @Test
    fun runBoundsGoUp() {
        val run = ActivityType.RUN
        assertEquals(SpeedBand.SLOW, SpeedBand.of(8.5, run))
        assertEquals(SpeedBand.EASY, SpeedBand.of(60.0 / 7.0, run))
        assertEquals(SpeedBand.EASY, SpeedBand.of(9.99, run))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(10.0, run))
        assertEquals(SpeedBand.FAST, SpeedBand.of(12.0, run))
        assertEquals(SpeedBand.FAST, SpeedBand.of(14.99, run))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(15.0, run))
    }

    @Test
    fun walkBounds() = assertBounds(ActivityType.WALK, 3.0, 4.0, 5.0, 6.0)

    @Test
    fun hikeBounds() = assertBounds(ActivityType.HIKE, 1.0, 2.0, 3.0, 4.0)

    private fun assertBounds(a: ActivityType, b1: Double, b2: Double, b3: Double, b4: Double) {
        assertEquals(SpeedBand.SLOW, SpeedBand.of(b1 - 0.01, a))
        assertEquals(SpeedBand.EASY, SpeedBand.of(b1, a))
        assertEquals(SpeedBand.EASY, SpeedBand.of(b2 - 0.01, a))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(b2, a))
        assertEquals(SpeedBand.STEADY, SpeedBand.of(b3 - 0.01, a))
        assertEquals(SpeedBand.FAST, SpeedBand.of(b3, a))
        assertEquals(SpeedBand.FAST, SpeedBand.of(b4 - 0.01, a))
        assertEquals(SpeedBand.SPRINT, SpeedBand.of(b4, a))
    }

    @Test
    fun legendsPerActivity() {
        fun legends(a: ActivityType) = SpeedBand.entries.map { it.legend(a) }
        assertEquals(listOf("7'00\" 넘게", "6'~7'", "5'~6'", "4'~5'", "4'00\" 미만"), legends(ActivityType.RUN))
        assertEquals(listOf("3 미만", "3~4", "4~5", "5~6", "6 이상"), legends(ActivityType.WALK))
        assertEquals(listOf("1 미만", "1~2", "2~3", "3~4", "4 이상"), legends(ActivityType.HIKE))
    }
}
