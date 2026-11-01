package com.cowork.bikerecoder.core.activity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityFormatTest {

    @Test
    fun formatsPace() {
        assertEquals("5'32\"", ActivityFormat.pace(10.84 / 3.6))
        assertEquals(332, ActivityFormat.paceSeconds(10.84 / 3.6))
    }

    @Test
    fun roundsUpToNextMinute() {
        // 359.6초/km -> 360초 -> 6'00"
        assertEquals("6'00\"", ActivityFormat.pace(1000.0 / 359.6))
        assertEquals(360, ActivityFormat.paceSeconds(1000.0 / 359.6))
    }

    @Test
    fun belowOneKmhIsDashes() {
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.pace(0.99 / 3.6))
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.pace(null))
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.pace(0.0))
        assertNull(ActivityFormat.paceSeconds(null))
        assertNull(ActivityFormat.paceSeconds(0.0))
        assertEquals("--'--\"", ActivityFormat.NO_PACE)
    }

    @Test
    fun overSixtyMinutesIsDashes() {
        // 1 km/h = 3,600초/km는 그대로 나온다. 3,600.6초는 반올림해 3,601초이므로 뺀다.
        assertEquals("60'00\"", ActivityFormat.pace(1.0 / 3.6))
        assertNull(ActivityFormat.paceSeconds(1000.0 / 3600.6))
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.pace(1000.0 / 3600.6))
    }

    @Test
    fun paceOfDistanceAndTime() {
        assertEquals("5'00\"", ActivityFormat.paceOf(1000.0, 300_000))
        assertEquals("5'00\"", ActivityFormat.paceOf(2000.0, 600_000))
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.paceOf(0.0, 300_000))
        assertEquals(ActivityFormat.NO_PACE, ActivityFormat.paceOf(1000.0, 0))
    }
}
