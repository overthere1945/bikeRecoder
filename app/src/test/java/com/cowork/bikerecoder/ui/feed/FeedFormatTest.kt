package com.cowork.bikerecoder.ui.feed

import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The card's bottom line follows the activity (V1.9.0 spec §2.2); a bike's is what it was. */
class FeedFormatTest {

    // 32.1 km in 1 h 45 min.
    private val bikeMps = 32_100.0 / 6_300.0

    @Test
    fun bikeLineIsUnchanged() {
        assertEquals("평균 18.3 km/h · ♥ 132", averageLineText(bikeMps, 132))
        assertEquals("평균 18.3 km/h", averageLineText(bikeMps, null))
        assertEquals(averageLineText(bikeMps, 132), averageLineText(ActivityType.BIKE, bikeMps, 132, 123.4))
        assertEquals(averageLineText(bikeMps, null), averageLineText(ActivityType.BIKE, bikeMps, null, 0.0))
    }

    @Test
    fun runAndWalkShowThePace() {
        val mps = 1000.0 / 332 // 5'32"/km
        assertEquals("평균 5'32\"/km · ♥ 150", averageLineText(ActivityType.RUN, mps, 150, 12.0))
        assertEquals("평균 5'32\"/km", averageLineText(ActivityType.WALK, mps, null, 12.0))
        assertEquals("평균 --'--\"/km", averageLineText(ActivityType.RUN, 0.0, null, 0.0))
    }

    @Test
    fun hikeShowsTheAscentAndTheSpeed() {
        val mps = 2.1 / 3.6
        assertEquals("상승 520 m · 평균 2.1 km/h", averageLineText(ActivityType.HIKE, mps, null, 520.0))
        assertEquals("상승 520 m · 평균 2.1 km/h · ♥ 128", averageLineText(ActivityType.HIKE, mps, 128, 520.0))
    }
}
