package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.post.HeartRateColor
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.SpeedBand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The big map's tabs (V1.8.0 spec §2.4) and the speed and heart-rate legends' text and colour pairs. */
class ChartTabTest {

    @Test
    fun argNamesTab() {
        assertEquals(listOf("elevation", "speed", "heartRate"), ChartTab.entries.map { it.arg })
        ChartTab.entries.forEach { assertEquals(it, ChartTab.of(it.arg)) }
        // None or an unknown one: the elevation tab.
        assertEquals(ChartTab.ELEVATION, ChartTab.of(null))
        assertEquals(ChartTab.ELEVATION, ChartTab.of("cadence"))
        assertEquals(listOf("고도", "속도", "심박"), ChartTab.entries.map { it.title })
    }

    @Test
    fun speedLegendPairsBands() {
        assertEquals(
            listOf(
                "10 미만" to "#90CAF9",
                "10~15" to "#26A69A",
                "15~20" to "#66BB6A",
                "20~25" to "#FFA726",
                "25 이상" to "#E53935",
            ),
            metricLegend(MetricKind.SPEED),
        )
        // Each text's colour is the one a speed in its range is drawn in.
        listOf(5.0, 12.0, 17.0, 22.0, 30.0).zip(metricLegend(MetricKind.SPEED)).forEach { (kmh, cell) ->
            assertEquals(SpeedBand.of(kmh).hex, cell.second, cell.first)
        }
    }

    @Test
    fun heartLegendPairsBands() {
        assertEquals(
            listOf(
                "100 미만" to "#1E88E5",
                "100~120" to "#43A047",
                "120~140" to "#FDD835",
                "140~160" to "#FB8C00",
                "160 이상" to "#E53935",
            ),
            metricLegend(MetricKind.HEART_RATE),
        )
        // Each text's colour is the one a heart rate in its range is drawn in.
        listOf(90, 110, 130, 150, 170).zip(metricLegend(MetricKind.HEART_RATE)).forEach { (bpm, cell) ->
            assertEquals(HeartRateColor.band(bpm).colorHex, cell.second, cell.first)
        }
    }
}
