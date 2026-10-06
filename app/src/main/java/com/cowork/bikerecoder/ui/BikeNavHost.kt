package com.cowork.bikerecoder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import kotlinx.coroutines.flow.first
import com.cowork.bikerecoder.ui.onboarding.OnboardingScreen
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import com.cowork.bikerecoder.ui.onboarding.OnboardingViewModel
import com.cowork.bikerecoder.ui.onboarding.effectiveOnboardingStep
import com.cowork.bikerecoder.ui.onboarding.readPermissionState

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val PLAN = "plan"
    const val NAVIGATE = "navigate"
    const val SETTINGS = "settings"
}

@Composable
fun BikeNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // The persisted skip choices decide the start destination; show nothing until they are read.
    val skipped by produceState<Set<OnboardingStep>?>(initialValue = null) {
        value = container.settings.settings.first().skippedOnboardingSteps
    }
    val skippedSteps = skipped ?: run {
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }
    val startDestination = remember {
        val state = readPermissionState(context, container.segments)
        if (effectiveOnboardingStep(state, skippedSteps) == OnboardingStep.DONE) Routes.MAIN else Routes.ONBOARDING
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(container))
            OnboardingScreen(
                viewModel = viewModel,
                onDone = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.MAIN) {
            // Placeholder until Task 18 builds the real main screen.
            BikeMap(
                tileSource = container.tileSource,
                overlay = MapOverlay(route = null, stops = emptyList(), user = null),
                cameraMode = CameraMode.FREE,
                onLongPress = {},
                onUserGesture = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(Routes.SEARCH) { ComingSoon() }
        composable(Routes.PLAN) { ComingSoon() }
        composable(Routes.NAVIGATE) { ComingSoon() }
        composable(Routes.SETTINGS) { ComingSoon() }
    }
}

@Composable
private fun ComingSoon() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
            Text("준비 중", style = MaterialTheme.typography.titleLarge)
        }
    }
}
