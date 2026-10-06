package com.cowork.bikerecoder.ui.onboarding

enum class OnboardingStep { FINE_LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY, SEGMENTS, DONE }

data class PermissionState(
    val fine: Boolean,
    val background: Boolean,
    val notifications: Boolean,
    val batteryExempt: Boolean,
    val segmentsReady: Boolean,
)

/** The first onboarding step that is still missing, in the fixed order; [OnboardingStep.DONE] when nothing is missing. */
fun nextOnboardingStep(s: PermissionState): OnboardingStep = when {
    !s.fine -> OnboardingStep.FINE_LOCATION
    !s.background -> OnboardingStep.BACKGROUND_LOCATION
    !s.notifications -> OnboardingStep.NOTIFICATIONS
    !s.batteryExempt -> OnboardingStep.BATTERY
    !s.segmentsReady -> OnboardingStep.SEGMENTS
    else -> OnboardingStep.DONE
}
