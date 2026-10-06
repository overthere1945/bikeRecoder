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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ui.navigate.NavigateScreen
import com.cowork.bikerecoder.ui.navigate.NavigateViewModel
import com.cowork.bikerecoder.ui.trip.StartPlan
import com.cowork.bikerecoder.ui.trip.TripStartDialogs
import com.cowork.bikerecoder.ui.trip.TripStartState
import com.cowork.bikerecoder.ui.trip.TripStartViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val PLAN = "plan"
    const val NAVIGATE = "navigate"
    const val ARG_FROM_PLAN = "fromPlan"
    const val NAVIGATE_PATTERN = "$NAVIGATE?${NavigateViewModel.ARG_TRIP}={${NavigateViewModel.ARG_TRIP}}&$ARG_FROM_PLAN={$ARG_FROM_PLAN}"
    const val SETTINGS = "settings"

    /** The guidance screen; with [tripId] it starts guiding that trip once visible. */
    fun navigate(tripId: Long? = null, fromPlan: Boolean = false) =
        "$NAVIGATE?${NavigateViewModel.ARG_TRIP}=${tripId ?: -1}&$ARG_FROM_PLAN=$fromPlan"
}

/** What the activity was launched for, from a notification. */
sealed interface LaunchRequest {
    /** "안내가 중단되었습니다. 눌러서 재개": start guiding [tripId] again. */
    data class ResumeTrip(val tripId: Long) : LaunchRequest

    /** The progress notification: show the running guidance. */
    data object ShowNavigation : LaunchRequest
}

@Composable
fun BikeNavHost(
    container: AppContainer,
    launchRequest: LaunchRequest? = null,
    onLaunchRequestHandled: () -> Unit = {},
) {
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

    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        val route = when (request) {
            is LaunchRequest.ResumeTrip -> Routes.navigate(request.tripId)
            LaunchRequest.ShowNavigation -> Routes.navigate()
        }
        // Ignored while onboarding is still showing (there is no main screen to return to yet).
        if (navController.currentDestination?.route != Routes.ONBOARDING) navController.openNavigate(route)
        onLaunchRequestHandled()
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
                onResumeTrip = { tripId -> navController.openNavigate(Routes.navigate(tripId)) },
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
            val tripStart: TripStartViewModel = viewModel(factory = TripStartViewModel.factory(container))
            val startState by tripStart.state.collectAsStateWithLifecycle()
            PlanScreen(
                viewModel = planViewModel,
                map = { state, modifier -> PlanMap(state, user, container.tileSource, modifier) },
                onBack = { navController.popBackStack() },
                onAddStop = { navController.navigate(Routes.SEARCH) },
                onStartNavigation = {
                    val plan = planViewModel.state.value
                    tripStart.onStartPressed(StartPlan(plan.profile, plan.stops))
                },
            )
            TripStartDialogs(
                state = startState,
                onChooseType = tripStart::chooseType,
                onContinue = tripStart::continueTrip,
                onStartNew = tripStart::startNew,
                onDismiss = tripStart::dismiss,
            )
            LaunchedEffect(startState) {
                val ready = startState as? TripStartState.Ready ?: return@LaunchedEffect
                tripStart.consumeReady()
                navController.openNavigate(Routes.navigate(ready.tripId, fromPlan = true))
            }
        }
        composable(
            Routes.NAVIGATE_PATTERN,
            arguments = listOf(
                navArgument(NavigateViewModel.ARG_TRIP) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument(Routes.ARG_FROM_PLAN) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val viewModel: NavigateViewModel = viewModel(factory = NavigateViewModel.factory(container))
            val tripId = entry.arguments?.getLong(NavigateViewModel.ARG_TRIP) ?: -1L
            if (entry.arguments?.getBoolean(Routes.ARG_FROM_PLAN) == true) {
                // The plan has become the trip: empty it once guidance of that trip is running.
                LaunchedEffect(tripId) {
                    container.navigation.ui.first { it is NavUiState.Active && it.tripId == tripId }
                    planViewModel.clear()
                }
            }
            NavigateScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                onClose = {
                    if (!navController.popBackStack(Routes.MAIN, inclusive = false)) navController.navigate(Routes.MAIN)
                },
            )
        }
        composable(Routes.SETTINGS) { ComingSoon() }
    }
}

/** The guidance screen on top of main (plan/search entries are dropped). */
private fun NavController.openNavigate(route: String) {
    navigate(route) { popUpTo(Routes.MAIN) }
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
