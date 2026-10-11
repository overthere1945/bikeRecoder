package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.navigation.Progress
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.recording.toTotals
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NavNotificationsTest {

    private val route = BRouterGeoJsonParser.parse(fixture("route_follow.geojson"), RouteProfile.BALANCED, emptyList())

    private fun active(progress: Progress) = NavUiState.Active(
        tripId = 1,
        type = TripType.SINGLE_DAY,
        state = NavState(route, progress, speedMps = 5.0, etaMillis = 0, gpsWeak = false, rerouting = false),
        stops = emptyList(),
        fix = null,
        muted = false,
    )

    @Test
    fun activeShowsTheNextTurnAndTheRemainingDistance() {
        val progress = Progress(500.0, 0.0, 1_900.0, route.instructions.first(), 300.0)

        assertEquals("300m 앞 우회전 · 남은 1.9km", NavNotifications.contentText(active(progress)))
    }

    @Test
    fun afterTheLastTurnShowsTheDestination() {
        val progress = Progress(2_000.0, 0.0, 400.0, null, null)

        assertEquals("목적지까지 · 남은 400m", NavNotifications.contentText(active(progress)))
    }

    @Test
    fun startingShowsRouteComputation() {
        assertEquals("경로를 계산하는 중…", NavNotifications.contentText(NavUiState.Starting(1)))
    }

    @Test
    fun beforeAndroid13OnlyTheAppNotificationSwitchCounts() {
        // API 30–32 have no POST_NOTIFICATIONS runtime permission: checkSelfPermission reports it denied.
        assertTrue(NavNotifications.canPost(sdkInt = 30, notificationsEnabled = true, postPermissionGranted = false))
        assertTrue(NavNotifications.canPost(sdkInt = 32, notificationsEnabled = true, postPermissionGranted = false))
        assertFalse(NavNotifications.canPost(sdkInt = 32, notificationsEnabled = false, postPermissionGranted = false))
    }

    @Test
    fun fromAndroid13ThePermissionIsAlsoRequired() {
        assertTrue(NavNotifications.canPost(sdkInt = 33, notificationsEnabled = true, postPermissionGranted = true))
        assertFalse(NavNotifications.canPost(sdkInt = 33, notificationsEnabled = true, postPermissionGranted = false))
        assertFalse(NavNotifications.canPost(sdkInt = 36, notificationsEnabled = false, postPermissionGranted = true))
    }

    private fun recording(state: RecordingState = RecordingState.Recording) = RideUiState.Active(
        tripId = 1,
        tripType = TripType.SINGLE_DAY,
        segmentId = 7,
        state = state,
        stats = RideStats.ZERO.copy(distanceM = 3_010.0, movingTimeMs = 600_000),
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )

    private val guiding = active(Progress(500.0, 0.0, 1_900.0, route.instructions.first(), 300.0))

    @Test
    fun `recording only notice`() {
        assertEquals(RideNotice("주행 기록 중", "3.01km · 이동 10분"), NavNotifications.rideNotice(NavUiState.Idle, recording()))
    }

    @Test
    fun `recording starting notice`() {
        assertEquals(
            RideNotice("주행 기록 중", "기록을 준비하는 중…"),
            NavNotifications.rideNotice(NavUiState.Idle, RideUiState.Starting(1)),
        )
    }

    @Test
    fun `paused notice`() {
        val notice = NavNotifications.rideNotice(NavUiState.Idle, recording(RecordingState.Paused(PauseKind.AUTO)))

        assertEquals(RideNotice("일시정지됨", "3.01km · 이동 10분"), notice)
    }

    @Test
    fun `guidance and recording notice`() {
        val notice = NavNotifications.rideNotice(guiding, recording())

        assertEquals("길 안내 중 · 기록 중", notice?.title)
        assertEquals(NavNotifications.contentText(guiding), notice?.text)
    }

    @Test
    fun `guidance only notice`() {
        assertEquals(
            RideNotice("길 안내 중", NavNotifications.contentText(guiding)),
            NavNotifications.rideNotice(guiding, RideUiState.Idle),
        )
        // The recording guidance starts is still starting: guidance only.
        assertEquals(
            RideNotice("길 안내 중", "경로를 계산하는 중…"),
            NavNotifications.rideNotice(NavUiState.Starting(1), RideUiState.Starting(1)),
        )
    }

    @Test
    fun `nothing running gives null`() {
        val segment = RideSegment(7, 1, 0, 600_000, LocalDate.of(2026, 10, 12), RideStats.ZERO.toTotals())

        assertNull(NavNotifications.rideNotice(NavUiState.Idle, RideUiState.Idle))
        assertNull(
            NavNotifications.rideNotice(
                NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED),
                RideUiState.Finished(segment, emptyList(), null),
            ),
        )
        assertNull(NavNotifications.rideNotice(NavUiState.Failed(1, "x"), RideUiState.Failed(1, "y")))
    }

    @Test
    fun `interrupted notice resumes guidance only with stops left`() {
        val trip = Trip(5, TripType.SINGLE_DAY, TripStatus.ACTIVE, RouteProfile.BALANCED, 0, 0, null)

        assertEquals(InterruptedNotice(resumeTripId = 5), NavNotifications.interruptedNotice(trip, 2, 1))
        assertEquals(InterruptedNotice(null), NavNotifications.interruptedNotice(trip, 0, 1))
        assertEquals(InterruptedNotice(null), NavNotifications.interruptedNotice(null, 0, 1))
        assertNull(NavNotifications.interruptedNotice(null, 0, 0))
    }
}
