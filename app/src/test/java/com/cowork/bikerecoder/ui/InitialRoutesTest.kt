package com.cowork.bikerecoder.ui

import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ride.RideUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InitialRoutesTest {

    @Test
    fun runningGuidanceOpensTheGuidanceScreenOnTopOfMain() {
        // E.g. the activity was closed with back while a single-day trip was guided: no banner would lead back.
        assertEquals(listOf(Routes.MAIN, Routes.navigate()), initialRoutes(onboardingDone = true, NavUiState.Starting(4)))
    }

    @Test
    fun withoutGuidanceTheAppOpensOnMain() {
        assertEquals(listOf(Routes.MAIN), initialRoutes(onboardingDone = true, NavUiState.Idle))
        assertEquals(
            listOf(Routes.MAIN),
            initialRoutes(onboardingDone = true, NavUiState.Finished(4, TripType.SINGLE_DAY, FinishReason.ARRIVED)),
        )
        assertEquals(listOf(Routes.MAIN), initialRoutes(onboardingDone = true, NavUiState.Failed(4, "x")))
    }

    @Test
    fun onboardingComesFirst() {
        assertEquals(listOf(Routes.ONBOARDING), initialRoutes(onboardingDone = false, NavUiState.Starting(4)))
    }

    @Test
    fun runningRecordingOpensTheRecordScreen() {
        assertEquals(
            listOf(Routes.MAIN, Routes.record()),
            initialRoutes(onboardingDone = true, NavUiState.Idle, recording()),
        )
        assertEquals(
            listOf(Routes.MAIN, Routes.record()),
            initialRoutes(onboardingDone = true, NavUiState.Idle, RideUiState.Starting(4)),
        )
        // Guidance wins: its screen shows the recording too.
        assertEquals(
            listOf(Routes.MAIN, Routes.navigate()),
            initialRoutes(onboardingDone = true, NavUiState.Starting(4), recording()),
        )
        assertEquals(listOf(Routes.MAIN), initialRoutes(onboardingDone = true, NavUiState.Idle, RideUiState.Failed(4, "x")))
    }

    @Test
    fun theNotificationOpensTheRunningRide() {
        assertEquals(Routes.navigate(), rideRoute(NavUiState.Starting(4), recording()))
        assertEquals(Routes.record(), rideRoute(NavUiState.Idle, recording()))
        assertEquals(null, rideRoute(NavUiState.Idle, RideUiState.Idle))
    }

    @Test
    fun recordRoutes() {
        assertEquals("record?trip=-1", Routes.record())
        assertEquals("record?trip=12", Routes.record(12))
        assertEquals("record?trip={trip}", Routes.RECORD_PATTERN)
    }

    private fun recording() = RideUiState.Active(
        tripId = 4,
        tripType = TripType.SINGLE_DAY,
        segmentId = 1,
        state = RecordingState.Recording,
        stats = RideStats.ZERO,
        autoPause = true,
        track = emptyList(),
        fix = null,
        gpsWeak = false,
        saveWarning = null,
    )
}
