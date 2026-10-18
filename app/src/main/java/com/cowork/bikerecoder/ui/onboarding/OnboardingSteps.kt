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

/**
 * Like [nextOnboardingStep], but steps the user chose to skip ("나중에") do not count as missing.
 * [OnboardingStep.FINE_LOCATION] can never be skipped.
 */
fun effectiveOnboardingStep(s: PermissionState, skipped: Set<OnboardingStep>): OnboardingStep {
    fun pending(step: OnboardingStep, satisfied: Boolean) =
        !satisfied && (step == OnboardingStep.FINE_LOCATION || step !in skipped)
    return when {
        pending(OnboardingStep.FINE_LOCATION, s.fine) -> OnboardingStep.FINE_LOCATION
        pending(OnboardingStep.BACKGROUND_LOCATION, s.background) -> OnboardingStep.BACKGROUND_LOCATION
        pending(OnboardingStep.NOTIFICATIONS, s.notifications) -> OnboardingStep.NOTIFICATIONS
        pending(OnboardingStep.BATTERY, s.batteryExempt) -> OnboardingStep.BATTERY
        pending(OnboardingStep.SEGMENTS, s.segmentsReady) -> OnboardingStep.SEGMENTS
        else -> OnboardingStep.DONE
    }
}
