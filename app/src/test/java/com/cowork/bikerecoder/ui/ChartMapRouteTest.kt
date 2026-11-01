package com.cowork.bikerecoder.ui

import com.cowork.bikerecoder.ui.elevation.ChartTab
import com.cowork.bikerecoder.ui.elevation.ElevationSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The big map screen's route (V1.8.0 spec §2.4): kind, id and the tab it opens on. */
class ChartMapRouteTest {

    @Test
    fun routeNamesTab() {
        assertEquals("elevation/trip/3?tab=speed", Routes.elevation(ElevationSource.Trip(3), ChartTab.SPEED))
        assertEquals("elevation/segment/5?tab=heartRate", Routes.elevation(ElevationSource.Segment(5), ChartTab.HEART_RATE))
        assertEquals("elevation/plan/0?tab=elevation", Routes.elevation(ElevationSource.Plan, ChartTab.ELEVATION))
        assertTrue(Routes.ELEVATION.endsWith("?tab={tab}"), Routes.ELEVATION)
    }
}
