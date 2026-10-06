package io.github.crockalet.haunt.android.ui

import io.github.crockalet.haunt.ui.screens.OnboardingStep

/** What the onboarding wizard needs to know (a snapshot taken on resume / after a permission result). */
data class OnboardingInputs(
    val developerOptionsEnabled: Boolean,
    val mockAppSelected: Boolean,
    val locationPermission: Boolean,
    val notificationPermission: Boolean,
    /** Notifications were asked for once already (they're optional: don't block on them). */
    val notificationAsked: Boolean,
    /** The user finished the wizard before (first launch is over). */
    val completedBefore: Boolean,
) {
    /** Mocking can work: Haunt is the mock location app and may run its location service. */
    val ready: Boolean get() = mockAppSelected && locationPermission
}

/**
 * Which onboarding step to show, or null for none. Shown on first launch (until the user taps
 * "Start haunting") and whenever mocking can't work; it advances by itself as each requirement is
 * met, and after the first launch it closes as soon as everything is ready.
 */
object OnboardingFlow {
    fun step(i: OnboardingInputs): OnboardingStep? {
        if (i.completedBefore && i.ready) return null
        return when {
            !i.mockAppSelected && !i.developerOptionsEnabled -> OnboardingStep.DeveloperOptions
            !i.mockAppSelected -> OnboardingStep.SelectMockApp
            !i.locationPermission -> OnboardingStep.Permissions
            !i.notificationPermission && !i.notificationAsked && !i.completedBefore -> OnboardingStep.Permissions
            i.completedBefore -> null
            else -> OnboardingStep.Done
        }
    }
}
