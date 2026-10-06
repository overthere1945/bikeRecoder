package com.cowork.bikerecoder.ui.onboarding

import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.BACKGROUND_LOCATION
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.BATTERY
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.DONE
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.FINE_LOCATION
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.NOTIFICATIONS
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep.SEGMENTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnboardingStepsTest {
    @Test
    fun `order of steps`() {
        assertEquals(FINE_LOCATION, nextOnboardingStep(PermissionState(false, false, false, false, false)))
        assertEquals(BACKGROUND_LOCATION, nextOnboardingStep(PermissionState(true, false, false, false, false)))
        assertEquals(NOTIFICATIONS, nextOnboardingStep(PermissionState(true, true, false, false, false)))
        assertEquals(BATTERY, nextOnboardingStep(PermissionState(true, true, true, false, false)))
        assertEquals(SEGMENTS, nextOnboardingStep(PermissionState(true, true, true, true, false)))
        assertEquals(DONE, nextOnboardingStep(PermissionState(true, true, true, true, true)))
    }

    @Test
    fun `earlier missing step wins over later granted ones`() {
        assertEquals(FINE_LOCATION, nextOnboardingStep(PermissionState(false, true, true, true, true)))
        assertEquals(BACKGROUND_LOCATION, nextOnboardingStep(PermissionState(true, false, true, true, true)))
        assertEquals(NOTIFICATIONS, nextOnboardingStep(PermissionState(true, true, false, true, true)))
        assertEquals(BATTERY, nextOnboardingStep(PermissionState(true, true, true, false, true)))
    }
}
