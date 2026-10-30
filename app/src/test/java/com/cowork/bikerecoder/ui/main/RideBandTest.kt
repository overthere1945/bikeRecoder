package com.cowork.bikerecoder.ui.main

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.Progress
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.fixture
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The ride/guidance band above the tab bar (V1.4.0 spec §1.4). */
class RideBandTest {

    private val route = BRouterGeoJsonParser.parse(fixture("route_follow.geojson"), RouteProfile.BALANCED, emptyList())

    private fun riding(state: RecordingState, distanceM: Double = 12_400.0, movingMinutes: Long = 48) = RideUiState.Active(
        tripId = 4,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = state,
        stats = RideStats.ZERO.copy(distanceM = distanceM, movingTimeMs = movingMinutes * 60_000 + 59_000),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private fun guiding(remainingM: Double) = NavUiState.Active(
        tripId = 4,
        type = TripType.SINGLE_DAY,
        state = NavState(route, Progress(500.0, 0.0, remainingM, null, null), 5.0, 0, gpsWeak = false, rerouting = false),
        stops = emptyList(),
        fix = null,
        muted = false,
    )

    @Test
    fun recording() {
        assertEquals("● 기록 중 · 12.4 km · 48분", rideBandText(riding(RecordingState.Recording), NavUiState.Idle))
    }

    @Test
    fun paused() {
        assertEquals("Ⅱ 일시정지 · 12.4 km · 48분", rideBandText(riding(RecordingState.Paused(PauseKind.MANUAL)), NavUiState.Idle))
        assertEquals("Ⅱ 일시정지 · 12.4 km · 48분", rideBandText(riding(RecordingState.Paused(PauseKind.AUTO)), NavUiState.Idle))
    }

    @Test
    fun longRideShowsHours() {
        assertEquals(
            "● 기록 중 · 64.2 km · 2시간 5분",
            rideBandText(riding(RecordingState.Recording, distanceM = 64_249.0, movingMinutes = 125), NavUiState.Idle),
        )
    }

    @Test
    fun guidanceOnly() {
        assertEquals("▲ 안내 중 · 남은 거리 3.2 km", rideBandText(RideUiState.Idle, guiding(3_200.0)))
    }

    @Test
    fun recordingWinsOverGuidance() {
        assertEquals("● 기록 중 · 12.4 km · 48분", rideBandText(riding(RecordingState.Recording), guiding(3_200.0)))
    }

    @Test
    fun nothingRunningHasNoBand() {
        assertNull(rideBandText(RideUiState.Idle, NavUiState.Idle))
        assertNull(rideBandText(RideUiState.Starting(4), NavUiState.Starting(4)))
        assertNull(rideBandText(RideUiState.Failed(4, "x"), NavUiState.Failed(4, "x")))
        assertNull(rideBandText(RideUiState.Idle, NavUiState.Finished(4, TripType.SINGLE_DAY, FinishReason.ARRIVED)))
    }
}
