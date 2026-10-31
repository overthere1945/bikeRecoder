package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.navigation.AheadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The elevation copy of V1.7.0 spec §5 and the chart's km axis (spec §2.1). */
class ElevationTextTest {

    @Test
    fun scrubLine() {
        assertEquals("1.2km · 35m · 경사 5%", ElevationText.scrubLine(1_234.0, 35.4, 4.6))
        assertEquals("850m · 102m · 경사 -3%", ElevationText.scrubLine(850.0, 101.5, -3.2))
    }

    @Test
    fun chartDescription() {
        assertEquals("고도 그래프, 최저 12m, 최고 240m, 오르막 3곳", ElevationText.chartDescription(12.2, 239.6, 3))
    }

    @Test
    fun usualSpeedLine() {
        assertEquals("평소 속도 18 km/h 기준", ElevationText.usualSpeedLine(18.4 / 3.6))
    }

    @Test
    fun aheadLine() {
        assertEquals("오르막 남은 거리 420m · 평균 6%", ElevationText.aheadLine(AheadState.InClimb(420.0, 5.6)))
        assertEquals(
            "300m 앞 오르막 · 1.2km · 평균 7%",
            ElevationText.aheadLine(AheadState.ClimbAhead(300.0, 1_200.0, 6.5)),
        )
        assertEquals("앞 2 km 평탄", ElevationText.aheadLine(AheadState.Flat))
    }

    @Test
    fun kmTickStepGivesThreeToSixTicks() {
        // The spec's 1·2·5·10 km first.
        assertEquals(1.0, kmTickStepKm(4_000.0))
        assertEquals(2.0, kmTickStepKm(9_000.0))
        assertEquals(5.0, kmTickStepKm(24_000.0))
        assertEquals(10.0, kmTickStepKm(45_000.0))
        // Shorter or longer rides fall back to the same 1-2-5 series.
        assertEquals(0.5, kmTickStepKm(1_600.0))
        assertEquals(50.0, kmTickStepKm(180_000.0))
    }
}
