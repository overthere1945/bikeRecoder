package com.cowork.bikerecoder.ui

import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
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
}
