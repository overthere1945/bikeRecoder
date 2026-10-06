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

    @Test
    fun `effective step skips skipped steps`() {
        val allButSegments = PermissionState(true, true, true, true, false)
        assertEquals(DONE, effectiveOnboardingStep(allButSegments, setOf(SEGMENTS)))
        assertEquals(SEGMENTS, effectiveOnboardingStep(allButSegments, emptySet()))
        assertEquals(
            NOTIFICATIONS,
            effectiveOnboardingStep(PermissionState(true, false, false, true, true), setOf(BACKGROUND_LOCATION)),
        )
        assertEquals(DONE, effectiveOnboardingStep(PermissionState(true, false, false, false, false), setOf(BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY, SEGMENTS)))
    }

    @Test
    fun `fine location can never be skipped`() {
        assertEquals(
            FINE_LOCATION,
            effectiveOnboardingStep(PermissionState(false, false, false, false, false), OnboardingStep.entries.toSet()),
        )
    }

    @Test
    fun `granted steps are not affected by skip set`() {
        assertEquals(DONE, effectiveOnboardingStep(PermissionState(true, true, true, true, true), setOf(SEGMENTS)))
    }
}
