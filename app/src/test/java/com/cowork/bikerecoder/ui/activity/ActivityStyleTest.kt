package com.cowork.bikerecoder.ui.activity

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.cowork.bikerecoder.core.activity.ActivityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The activity colours of V1.9.0 spec §2.1, verbatim. */
class ActivityStyleTest {

    @Test
    fun coloursAndHex() {
        assertEquals(listOf("#D85A30", "#1D9E75", "#D4537E", "#639922"), ActivityType.entries.map(ActivityStyle::hex))
        assertEquals(Color(0xFFD85A30), ActivityStyle.color(ActivityType.BIKE))
        assertEquals(Color(0xFF639922), ActivityStyle.color(ActivityType.HIKE))
    }

    @Test
    fun badgeColoursLightAndDark() {
        assertEquals(Color(0xFFFAECE7) to Color(0xFF993C1D), ActivityStyle.badgeColors(ActivityType.BIKE, dark = false))
        assertEquals(Color(0xFF712B13) to Color(0xFFF5C4B3), ActivityStyle.badgeColors(ActivityType.BIKE, dark = true))
        assertEquals(Color(0xFFE1F5EE) to Color(0xFF0F6E56), ActivityStyle.badgeColors(ActivityType.WALK, dark = false))
        assertEquals(Color(0xFF085041) to Color(0xFF9FE1CB), ActivityStyle.badgeColors(ActivityType.WALK, dark = true))
        assertEquals(Color(0xFFFBEAF0) to Color(0xFF993556), ActivityStyle.badgeColors(ActivityType.RUN, dark = false))
        assertEquals(Color(0xFF72243E) to Color(0xFFF4C0D1), ActivityStyle.badgeColors(ActivityType.RUN, dark = true))
        assertEquals(Color(0xFFEAF3DE) to Color(0xFF3B6D11), ActivityStyle.badgeColors(ActivityType.HIKE, dark = false))
        assertEquals(Color(0xFF27500A) to Color(0xFFC0DD97), ActivityStyle.badgeColors(ActivityType.HIKE, dark = true))
    }

    @Test
    fun namesAreTheActivityNames() {
        assertEquals(listOf("자전거", "걷기", "달리기", "등산"), ActivityType.entries.map(ActivityStyle::name))
    }

    @Test
    fun darknessFollowsTheAppTheme() {
        // The app's theme is the default light scheme, whatever the system setting says.
        assertFalse(ActivityStyle.isDark(lightColorScheme().surface))
        assertTrue(ActivityStyle.isDark(darkColorScheme().surface))
        assertFalse(ActivityStyle.isDark(Color.White))
        assertTrue(ActivityStyle.isDark(Color.Black))
    }
}
