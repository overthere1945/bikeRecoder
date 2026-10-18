package com.cowork.bikerecoder.ui

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import kotlinx.coroutines.flow.first
import com.cowork.bikerecoder.ui.onboarding.OnboardingScreen
import com.cowork.bikerecoder.ui.onboarding.OnboardingStep
import com.cowork.bikerecoder.ui.onboarding.OnboardingViewModel
import com.cowork.bikerecoder.ui.onboarding.effectiveOnboardingStep
import com.cowork.bikerecoder.ui.onboarding.readPermissionState
import com.cowork.bikerecoder.ui.favorite.FavoriteChips
import com.cowork.bikerecoder.ui.favorite.FavoriteDialogs
import com.cowork.bikerecoder.ui.favorite.FavoriteEvent
import com.cowork.bikerecoder.ui.favorite.FavoritesScreen
import com.cowork.bikerecoder.ui.favorite.FavoritesViewModel
import com.cowork.bikerecoder.ui.history.HistoryScreen
import com.cowork.bikerecoder.ui.history.HistoryViewModel
import com.cowork.bikerecoder.ui.history.SegmentDetailScreen
import com.cowork.bikerecoder.ui.history.SegmentDetailViewModel
import com.cowork.bikerecoder.ui.history.TripDetailScreen
import com.cowork.bikerecoder.ui.history.TripDetailViewModel
import com.cowork.bikerecoder.ui.main.MainScreen
import com.cowork.bikerecoder.ui.main.MainViewModel
import com.cowork.bikerecoder.ui.plan.PlanMap
import com.cowork.bikerecoder.ui.plan.PlanScreen
import com.cowork.bikerecoder.ui.plan.PlanViewModel
import com.cowork.bikerecoder.ui.search.SearchScreen
import com.cowork.bikerecoder.ui.settings.SettingsScreen
import com.cowork.bikerecoder.ui.settings.SettingsViewModel
import com.cowork.bikerecoder.ui.search.SearchViewModel
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ui.navigate.NavigateScreen
import com.cowork.bikerecoder.ui.navigate.NavigateViewModel
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ui.main.RideTarget
import com.cowork.bikerecoder.ui.record.RecordScreen
import com.cowork.bikerecoder.ui.record.RecordViewModel
import com.cowork.bikerecoder.ui.trip.StartPlan
import com.cowork.bikerecoder.ui.trip.TripStartDialogs
import com.cowork.bikerecoder.ui.trip.TripStartState
import com.cowork.bikerecoder.ui.trip.TripStartViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SEARCH_PATTERN = "$SEARCH?${SearchViewModel.ARG_REGISTER}={${SearchViewModel.ARG_REGISTER}}"
    const val FAVORITES = "favorites"
    const val PLAN = "plan"
    const val NAVIGATE = "navigate"
    const val ARG_FROM_PLAN = "fromPlan"
    const val NAVIGATE_PATTERN = "$NAVIGATE?${NavigateViewModel.ARG_TRIP}={${NavigateViewModel.ARG_TRIP}}" +
        "&$ARG_FROM_PLAN={$ARG_FROM_PLAN}&${NavigateViewModel.ARG_RECORD_NOTICE}={${NavigateViewModel.ARG_RECORD_NOTICE}}"
    const val SETTINGS = "settings"
    const val RECORD = "record"
    const val RECORD_PATTERN = "$RECORD?${RecordViewModel.ARG_TRIP}={${RecordViewModel.ARG_TRIP}}"
    const val HISTORY = "history"
    const val TRIP_DETAIL = "history/trip/{${TripDetailViewModel.ARG_TRIP}}"
    const val SEGMENT_DETAIL = "history/segment/{${SegmentDetailViewModel.ARG_SEGMENT}}"

    fun tripDetail(tripId: Long) = "history/trip/$tripId"

    /**
     * The search in register mode (spec §7.4, §8.2): a tapped place becomes this home or work, or (CUSTOM) is named as a
     * custom favorite. [SEARCH] is the normal one.
     */
    fun searchRegister(kind: FavoriteKind) = "$SEARCH?${SearchViewModel.ARG_REGISTER}=${kind.name}"

    fun segmentDetail(segmentId: Long) = "history/segment/$segmentId"

    /**
     * The guidance screen; with [tripId] it starts guiding that trip once visible. [recordNotice]: tell once that the
     * recording ended for this start was not fully saved.
     */
    fun navigate(tripId: Long? = null, fromPlan: Boolean = false, recordNotice: Boolean = false) =
        "$NAVIGATE?${NavigateViewModel.ARG_TRIP}=${tripId ?: -1}&$ARG_FROM_PLAN=$fromPlan" +
            "&${NavigateViewModel.ARG_RECORD_NOTICE}=$recordNotice"

    /** The record screen; with [tripId] it starts recording that trip once visible. */
    fun record(tripId: Long? = null) = "$RECORD?${RecordViewModel.ARG_TRIP}=${tripId ?: -1}"
}

/** What the activity was launched for, from a notification. */
sealed interface LaunchRequest {
    /** "안내가 중단되었습니다. 눌러서 재개": start guiding [tripId] again. */
    data class ResumeTrip(val tripId: Long) : LaunchRequest

    /** The progress notification: show the running guidance or recording ([rideRoute]). */
    data object ShowNavigation : LaunchRequest
}

/**
 * The screen of the running ride: guidance while it is starting or running (its screen also shows the recording),
 * else the record screen while a recording is starting or running; null when nothing runs.
 */
fun rideRoute(guidance: NavUiState, ride: RideUiState): String? = when {
    guidance is NavUiState.Starting || guidance is NavUiState.Active -> Routes.navigate()
    ride is RideUiState.Starting || ride is RideUiState.Active -> Routes.record()
    else -> null
}

/**
 * The routes the app opens with on a fresh start: onboarding until it is done; otherwise main, with the
 * running ride's screen ([rideRoute]) on top (e.g. the activity was closed during a single-day trip, which has no
 * main-screen banner leading back).
 */
fun initialRoutes(onboardingDone: Boolean, guidance: NavUiState, ride: RideUiState = RideUiState.Idle): List<String> =
    if (!onboardingDone) listOf(Routes.ONBOARDING) else listOfNotNull(Routes.MAIN, rideRoute(guidance, ride))

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
    val initial = remember {
        val state = readPermissionState(context, container.segments)
        initialRoutes(
            effectiveOnboardingStep(state, skippedSteps) == OnboardingStep.DONE,
            container.navigationState,
            container.rideState,
        )
    }
    val startDestination = initial.first()
    // Once per fresh start (a recreated activity restores its own back stack).
    var initialOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (initialOpened) return@LaunchedEffect
        initialOpened = true
        initial.drop(1).forEach { navController.openOnMain(it) }
    }

    // Activity-scoped too: the chips and the place sheets of every screen share one save/rename/delete dialog.
    val favoritesViewModel: FavoritesViewModel = viewModel(
        viewModelStoreOwner = context.findActivity(),
        factory = FavoritesViewModel.factory(container),
    )
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val favoritesLoaded by favoritesViewModel.favoritesLoaded.collectAsStateWithLifecycle()
    val favoriteDialog by favoritesViewModel.dialog.collectAsStateWithLifecycle()
    // The chips and the favorites screen: a favorite becomes the destination, then the plan screen.
    val selectFavorite: (Favorite) -> Unit = { favorite ->
        planViewModel.setDestination(favorite)
        navController.openPlan()
    }
    // [chipsModifier] is the caller's outer spacing (outside the sideways scroll). [isCurrent]: the hosting screen is on
    // top (a double tap opens the favorites screen once). [onRegister] (the map main only) adds the home/work register
    // chips.
    val favoriteChips: @Composable (Modifier, () -> Boolean, ((FavoriteKind) -> Unit)?) -> Unit =
        { chipsModifier, isCurrent, onRegister ->
            FavoriteChips(
                modifier = chipsModifier,
                favorites = favorites,
                onSelect = selectFavorite,
                onDelete = favoritesViewModel::askDelete,
                onOpenFavorites = { if (isCurrent()) navController.navigate(Routes.FAVORITES) },
                onRegister = onRegister,
            )
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
            LaunchRequest.ShowNavigation -> rideRoute(container.navigationState, container.rideState)
        }
        // Ignored while onboarding is still showing (there is no main screen to return to yet).
        if (route != null && navController.currentDestination?.route != Routes.ONBOARDING) navController.openOnMain(route)
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
        composable(Routes.MAIN) { entry ->
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
            // A double tap must not push a second screen: only the main screen on top navigates.
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            // The record-only start asks which trip, like [안내 시작] on the plan screen.
            val tripStart: TripStartViewModel = viewModel(factory = TripStartViewModel.factory(container))
            val startState by tripStart.state.collectAsStateWithLifecycle()
            MainScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                currentLocation = container.currentLocation,
                onOpenSearch = { if (isCurrent()) navController.navigate(Routes.SEARCH) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onResumeTrip = { tripId -> navController.openOnMain(Routes.navigate(tripId)) },
                onShowRide = { target ->
                    val route = when (target) {
                        RideTarget.NAVIGATE -> Routes.navigate()
                        RideTarget.RECORD -> Routes.record()
                    }
                    navController.openOnMain(route)
                },
                onStartRecording = {
                    val nav = container.navigationState
                    when {
                        // A summary not seen yet is shown first, never replaced by a new start.
                        container.rideState is RideUiState.Finished -> navController.openOnMain(Routes.record())
                        // While guiding, the recording belongs to the guided trip: no questions.
                        nav is NavUiState.Starting -> navController.openOnMain(Routes.record(nav.tripId))
                        nav is NavUiState.Active -> navController.openOnMain(Routes.record(nav.tripId))
                        else -> tripStart.onRecordPressed()
                    }
                },
                onContinueRecording = { tripId -> navController.openOnMain(Routes.record(tripId)) },
                onSetDestination = setDestinationAndOpenPlan,
                onAddWaypoint = addWaypointAndOpenPlan,
                favoriteChips = {
                    // No register chips before the list has loaded: an empty placeholder is not "no home, no work".
                    favoriteChips(
                        Modifier,
                        isCurrent,
                        if (favoritesLoaded) {
                            { kind -> if (isCurrent()) navController.navigate(Routes.searchRegister(kind)) }
                        } else {
                            null
                        },
                    )
                },
                onSaveFavorite = favoritesViewModel::startSave,
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
                navController.openOnMain(Routes.record(ready.tripId))
            }
            // A recording that ended while another screen showed (e.g. guidance arrival stopped it after the guidance
            // screen was left): its summary is shown on the record screen. Checked live: [확인] there may have closed it
            // before this state caught up.
            val mainState by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(mainState.pendingSummary) {
                if (mainState.pendingSummary && container.rideState is RideUiState.Finished) {
                    navController.openOnMain(Routes.record())
                }
            }
        }
        composable(
            Routes.SEARCH_PATTERN,
            arguments = listOf(
                navArgument(SearchViewModel.ARG_REGISTER) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
            // A second tap on a result while this screen fades out after a save must not save (and ask) again.
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            // Keeps the shared location source running while searching (used as the "near" point).
            container.currentLocation.collectAsStateWithLifecycle()
            SearchScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSetDestination = setDestinationAndOpenPlan,
                onAddWaypoint = addWaypointAndOpenPlan,
                favoriteChips = { favoriteChips(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), isCurrent, null) },
                onSaveFavorite = favoritesViewModel::startSave,
                // The replace question and refusals are the shared dialogs below; a save ends in [FavoriteEvent.Registered].
                onRegister = { place, kind -> if (isCurrent()) favoritesViewModel.saveAs(place, kind) },
            )
        }
        composable(Routes.FAVORITES) { entry ->
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            FavoritesScreen(
                favorites = favorites.filter { it.kind == FavoriteKind.CUSTOM },
                onBack = { if (isCurrent()) navController.popBackStack() },
                // At the limit the shared dialog says so and this screen stays.
                onAdd = {
                    if (isCurrent() && favoritesViewModel.startAdd()) {
                        navController.navigate(Routes.searchRegister(FavoriteKind.CUSTOM))
                    }
                },
                onSelect = { favorite -> if (isCurrent()) selectFavorite(favorite) },
                onRename = favoritesViewModel::startRename,
                onDelete = favoritesViewModel::askDelete,
            )
        }
        composable(Routes.PLAN) { entry ->
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            val user by container.currentLocation.collectAsStateWithLifecycle()
            val tripStart: TripStartViewModel = viewModel(factory = TripStartViewModel.factory(container))
            val startState by tripStart.state.collectAsStateWithLifecycle()
            PlanScreen(
                viewModel = planViewModel,
                map = { state, modifier -> PlanMap(state, user, container.tileSource, modifier, onLongPress = planViewModel::onLongPress) },
                onBack = { navController.popBackStack() },
                onAddStop = { if (isCurrent()) navController.navigate(Routes.SEARCH) },
                onStartNavigation = {
                    val plan = planViewModel.state.value
                    tripStart.onStartPressed(StartPlan(plan.profile, plan.stops))
                },
                onSaveFavorite = favoritesViewModel::startSave,
            )
            TripStartDialogs(
                state = startState,
                onChooseType = tripStart::chooseType,
                onContinue = tripStart::continueTrip,
                onStartNew = tripStart::startNew,
                onDismiss = tripStart::dismiss,
                onKeepRecording = tripStart::keepRecording,
                onSplitRecording = tripStart::splitRecording,
            )
            LaunchedEffect(startState) {
                when (val state = startState) {
                    // A summary not seen yet is shown first (the plan stays for the next [안내 시작]).
                    TripStartState.ShowSummary -> {
                        tripStart.summaryShown()
                        navController.openOnMain(Routes.record())
                    }
                    is TripStartState.Ready -> {
                        tripStart.consumeReady()
                        navController.openOnMain(
                            Routes.navigate(state.tripId, fromPlan = true, recordNotice = state.recordNotice != null),
                        )
                    }
                    else -> Unit
                }
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
                navArgument(NavigateViewModel.ARG_RECORD_NOTICE) {
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
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            NavigateScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                onClose = {
                    if (!navController.popBackStack(Routes.MAIN, inclusive = false)) navController.navigate(Routes.MAIN)
                },
                // [안내만 끝내기 (기록 계속)]: the recording goes on, on the record screen.
                onGuidanceEndedWhileRecording = { if (isCurrent()) navController.openOnMain(Routes.record()) },
            )
        }
        composable(
            Routes.RECORD_PATTERN,
            arguments = listOf(
                navArgument(RecordViewModel.ARG_TRIP) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val viewModel: RecordViewModel = viewModel(factory = RecordViewModel.factory(container))
            // Only while this screen is on top: after [확인] it has already navigated away when the state turns idle.
            val isCurrent = { navController.currentBackStackEntry?.id == entry.id }
            RecordScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                onClose = {
                    if (isCurrent() && !navController.popBackStack(Routes.MAIN, inclusive = false)) {
                        navController.navigate(Routes.MAIN)
                    }
                },
                onShowGuidance = { if (isCurrent()) navController.openOnMain(Routes.navigate()) },
                onSetDestination = { if (isCurrent()) navController.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.HISTORY) {
            val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(container))
            HistoryScreen(
                viewModel = viewModel,
                onOpenTrip = { tripId -> navController.navigate(Routes.tripDetail(tripId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.TRIP_DETAIL,
            arguments = listOf(navArgument(TripDetailViewModel.ARG_TRIP) { type = NavType.LongType }),
        ) {
            val viewModel: TripDetailViewModel = viewModel(factory = TripDetailViewModel.factory(container))
            TripDetailScreen(
                viewModel = viewModel,
                onOpenSegment = { segmentId -> navController.navigate(Routes.segmentDetail(segmentId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SEGMENT_DETAIL,
            arguments = listOf(navArgument(SegmentDetailViewModel.ARG_SEGMENT) { type = NavType.LongType }),
        ) {
            val viewModel: SegmentDetailViewModel = viewModel(factory = SegmentDetailViewModel.factory(container))
            SegmentDetailScreen(
                viewModel = viewModel,
                tileSource = container.tileSource,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
    // Collected here, not on the search screen: each save is told once even if that screen was left meanwhile; only a
    // register-mode search still on top goes back (to main for home/work, to the favorites screen for a custom one).
    LaunchedEffect(favoritesViewModel) {
        favoritesViewModel.events.collect { event ->
            when (event) {
                is FavoriteEvent.Registered -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    val top = navController.currentBackStackEntry
                    val registering = top?.destination?.route == Routes.SEARCH_PATTERN &&
                        SearchViewModel.registerKind(top.arguments?.getString(SearchViewModel.ARG_REGISTER)) != null
                    if (registering) navController.popBackStack()
                }
            }
        }
    }
    FavoriteDialogs(
        dialog = favoriteDialog,
        onSave = favoritesViewModel::save,
        onConfirmReplace = favoritesViewModel::confirmReplace,
        onRename = favoritesViewModel::rename,
        onConfirmDelete = favoritesViewModel::confirmDelete,
        onDismiss = favoritesViewModel::dismiss,
        onSaveCustom = favoritesViewModel::saveCustom,
    )
}

/** The guidance or record screen on top of main (plan/search/ride entries are dropped). */
private fun NavController.openOnMain(route: String) {
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
