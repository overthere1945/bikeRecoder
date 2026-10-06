package com.cowork.bikerecoder.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.flow.first
import com.cowork.bikerecoder.ui.onboarding.OnboardingScreen
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import com.cowork.bikerecoder.ui.onboarding.OnboardingViewModel
import com.cowork.bikerecoder.ui.onboarding.effectiveOnboardingStep
import com.cowork.bikerecoder.ui.onboarding.readPermissionState
import com.cowork.bikerecoder.ui.main.MainScreen
import com.cowork.bikerecoder.ui.main.MainViewModel
import com.cowork.bikerecoder.ui.plan.PlanMap
import com.cowork.bikerecoder.ui.plan.PlanScreen
import com.cowork.bikerecoder.ui.plan.PlanViewModel
import com.cowork.bikerecoder.ui.search.SearchScreen
import com.cowork.bikerecoder.ui.search.SearchViewModel

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
    // Activity-scoped so main, search and plan edit one shared plan.
    val planViewModel: PlanViewModel = viewModel(
        viewModelStoreOwner = context.findActivity(),
        factory = PlanViewModel.factory(container),
    )
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

    val setDestinationAndOpenPlan: (String, GeoPoint) -> Unit = { name, point ->
        planViewModel.setDestination(name, point)
        navController.openPlan()
    }
    val addWaypointAndOpenPlan: (String, GeoPoint) -> Unit = { name, point ->
        planViewModel.addWaypoint(name, point)
        navController.openPlan()
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
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
            MainScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                currentLocation = container.currentLocation,
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                // Task 19 wires the real resume flow; the trip id travels with it.
                onResumeTrip = { _ -> navController.navigate(Routes.NAVIGATE) },
                onSetDestination = setDestinationAndOpenPlan,
                onAddWaypoint = addWaypointAndOpenPlan,
            )
        }
        composable(Routes.SEARCH) {
            val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
            // Keeps the shared location source running while searching (used as the "near" point).
            container.currentLocation.collectAsStateWithLifecycle()
            SearchScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSetDestination = setDestinationAndOpenPlan,
                onAddWaypoint = addWaypointAndOpenPlan,
            )
        }
        composable(Routes.PLAN) {
            val user by container.currentLocation.collectAsStateWithLifecycle()
            PlanScreen(
                viewModel = planViewModel,
                map = { state, modifier -> PlanMap(state, user, container.tileSource, modifier) },
                onBack = { navController.popBackStack() },
                onAddStop = { navController.navigate(Routes.SEARCH) },
                // Task 19 replaces this with the trip-type flow.
                onStartNavigation = { navController.navigate(Routes.NAVIGATE) },
            )
        }
        composable(Routes.NAVIGATE) { ComingSoon() }
        composable(Routes.SETTINGS) { ComingSoon() }
    }
}

/** Back to the plan screen: main stays underneath, search/earlier plan entries are dropped. */
private fun NavController.openPlan() {
    navigate(Routes.PLAN) { popUpTo(Routes.MAIN) }
}

private fun Context.findActivity(): ComponentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    error("BikeNavHost must be hosted in a ComponentActivity")
}

@Composable
private fun ComingSoon() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
            Text("준비 중", style = MaterialTheme.typography.titleLarge)
        }
    }
}
