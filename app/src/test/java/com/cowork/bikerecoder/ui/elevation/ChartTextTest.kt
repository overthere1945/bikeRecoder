package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.series.MetricKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The speed and heart-rate chart copy of V1.8.0 spec §3. */
class ChartTextTest {

    @Test
    fun scrubLines() {
        assertEquals("1.1km · 18km/h", ChartText.scrubLine(MetricKind.SPEED, 1_100.0, 18.4))
        assertEquals("1.1km · 145bpm", ChartText.scrubLine(MetricKind.HEART_RATE, 1_100.0, 145.4))
        assertEquals("1.1km · --bpm", ChartText.scrubLine(MetricKind.HEART_RATE, 1_100.0, null))
        assertEquals("850m · --km/h", ChartText.scrubLine(MetricKind.SPEED, 850.0, null))
        // A smoothed stop can come out a hair under zero: never "-0".
        assertEquals("850m · 0km/h", ChartText.scrubLine(MetricKind.SPEED, 850.0, -1e-14))
    }

    @Test
    fun descriptions() {
        assertEquals("속도 그래프, 평균 18km/h, 최고 42km/h", ChartText.speedDescription(18.4, 41.6))
        assertEquals("속도 그래프, 평균 0km/h, 최고 0km/h", ChartText.speedDescription(-1e-14, 0.0))
        assertEquals("심박 그래프, 평균 132bpm, 최고 171bpm", ChartText.heartDescription(132, 171))
    }

    @Test
    fun valueLabels() {
        assertEquals("34km/h", ChartText.valueLabel(MetricKind.SPEED, 34.2))
        assertEquals("0km/h", ChartText.valueLabel(MetricKind.SPEED, -1e-14))
        assertEquals("162bpm", ChartText.valueLabel(MetricKind.HEART_RATE, 161.5))
    }

    @Test
    fun legends() {
        assertEquals(listOf("10 미만", "10~15", "15~20", "20~25", "25 이상"), ChartText.SPEED_LEGEND)
        assertEquals(listOf("100 미만", "100~120", "120~140", "140~160", "160 이상"), ChartText.HEART_LEGEND)
    }

    @Test
    fun titles() {
        assertEquals("고도", ChartText.TITLE_ELEVATION)
        assertEquals("속도", ChartText.TITLE_SPEED)
        assertEquals("심박", ChartText.TITLE_HEART)
        assertEquals("속도 정보가 없습니다", ChartText.NO_SPEED)
    }
}
