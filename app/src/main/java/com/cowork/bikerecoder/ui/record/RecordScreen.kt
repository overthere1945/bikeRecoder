package com.cowork.bikerecoder.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.NavigationService
import com.cowork.bikerecoder.ui.common.KeepScreenOn
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ride.RideUiState
import kotlinx.coroutines.launch

/** Test tags of the record screen. */
object RecordTags {
    const val PAGER = "record-pager"
    const val CHIP = "record-chip"
    const val PAUSE = "record-pause"
    const val RESUME = "record-resume"
    const val STOP = "record-stop"
    const val BAND = "record-band"
    const val NUMBERS_PAGE = "record-numbers"

    /** The top strip (chip and bands). */
    const val TOP = "record-top"

    /** The bottom summary line of the map page. */
    const val SUMMARY = "record-summary"
}

/**
 * The record screen (spec §3.2–3.4) over the app-wide recording: start progress and errors, the two-page recording
 * view and the segment summary. Leaving the screen never stops the recording. [onClose] goes back to the map main
 * screen; after the summary, [onShowGuidance] is used instead while guidance runs.
 */
@Composable
fun RecordScreen(viewModel: RecordViewModel, tileSource: TileSource, onClose: () -> Unit, onShowGuidance: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val navigating by viewModel.navigating.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val serviceWarning by viewModel.serviceWarning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Start only while the screen is visible: a location FGS needs a visible activity (spec §2.2).
    LifecycleResumeEffect(viewModel) {
        viewModel.takePendingStart()?.let { tripId ->
            NavigationService.start(context)
            viewModel.start(tripId)
        }
        onPauseOrDispose { }
    }

    val state = ui
    KeepScreenOn(keepScreenOn && (state is RideUiState.Starting || state is RideUiState.Active))

    val closeError = {
        onClose()
        viewModel.dismissSummary()
    }
    val closeSummary = {
        if (navigating) onShowGuidance() else onClose()
        viewModel.dismissSummary()
    }
    BackHandler(enabled = state is RideUiState.Finished || state is RideUiState.Failed) {
        if (state is RideUiState.Finished) closeSummary() else closeError()
    }

    // The warning comes once per recording; show it once per visit of this screen.
    val saveWarning = (state as? RideUiState.Active)?.saveWarning ?: (state as? RideUiState.Finished)?.error
    var warned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saveWarning) {
        if (saveWarning == null || warned) return@LaunchedEffect
        warned = true
        snackbar.showSnackbar(saveWarning, duration = SnackbarDuration.Long)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // About to start (the previous summary or error must not flash first).
            viewModel.hasPendingStart && state !is RideUiState.Starting && state !is RideUiState.Active ->
                RecordStatusContent(RideUiState.Idle, onClose = onClose)
            state is RideUiState.Idle -> LaunchedEffect(Unit) { onClose() }
            state is RideUiState.Starting || state is RideUiState.Failed -> RecordStatusContent(state, onClose = closeError)
            state is RideUiState.Active -> RecordContent(
                state = state,
                navigating = navigating,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onToggleAutoPause = viewModel::setAutoPause,
                onEnd = viewModel::onEndChoice,
                map = { modifier -> RecordMap(state, tileSource, modifier) },
                serviceWarning = serviceWarning,
            )
            state is RideUiState.Finished -> SegmentSummaryContent(
                segment = state.segment,
                map = { modifier -> SummaryMap(state.track, tileSource, modifier) },
                onConfirm = closeSummary,
            )
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.Center).padding(16.dp))
    }
}

/**
 * Not recording yet: [RideUiState.Failed] shows its message and [닫기] ([onClose]); anything else (starting, or about to
 * start) shows the progress.
 */
@Composable
fun RecordStatusContent(state: RideUiState, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state is RideUiState.Failed) {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                TextButton(onClick = onClose) { Text("닫기") }
            } else {
                CircularProgressIndicator()
                Text("기록을 준비하는 중…", textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The running recording (spec §3.2): the auto-pause chip and the pause / service / weak-GPS bands on top, two pages (the map with
 * the summary line, the numbers) and [일시정지]/[재개] [정지] below. The pager itself takes no drags: MapLibre's view never
 * asks its parents not to intercept, so a scrolling pager would claim every horizontal drag on the map and cancel it.
 * Pages turn by swiping the summary line, the numbers page or the top strip, or by tapping the page dots.
 */
@Composable
fun RecordContent(
    state: RideUiState.Active,
    navigating: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onToggleAutoPause: (Boolean) -> Unit,
    onEnd: (RecordingEnd) -> Unit,
    map: @Composable (Modifier) -> Unit,
    /** The foreground service could not start ("화면이 꺼지면 안내가 멈출 수 있습니다"). */
    serviceWarning: String? = null,
) {
    val pager = rememberPagerState { RIDE_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    var askEnd by rememberSaveable { mutableStateOf(false) }
    val paused = state.state as? RecordingState.Paused
    val dimAlpha = if (paused != null) DIMMED_ALPHA else 1f

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .swipeTurnsPages(pager, scope)
                    .padding(12.dp)
                    .testTag(RecordTags.TOP),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoPauseChip(state.autoPause, onToggleAutoPause, Modifier.testTag(RecordTags.CHIP))
                if (paused != null) PauseBand(paused.kind, Modifier.testTag(RecordTags.BAND))
                if (serviceWarning != null) Band(serviceWarning, MaterialTheme.colorScheme.error)
                if (state.gpsWeak) Band("GPS 신호 약함", MaterialTheme.colorScheme.error)
            }

            HorizontalPager(
                state = pager,
                // Keeps the map page (its MapView and camera) alive while the numbers page shows.
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(RecordTags.PAGER),
            ) { page ->
                if (page == RIDE_MAP_PAGE) {
                    // The summary line sits below the map, not over it, so a swipe on it never reaches the map.
                    Column(modifier = Modifier.fillMaxSize()) {
                        map(Modifier.weight(1f).fillMaxWidth())
                        Text(
                            rideMapSummaryLine(state.stats),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(vertical = 14.dp, horizontal = 12.dp)
                                .alpha(dimAlpha)
                                .swipeTurnsPages(pager, scope)
                                .testTag(RecordTags.SUMMARY),
                        )
                    }
                } else {
                    RideNumbersPage(
                        stats = state.stats,
                        dimmed = paused != null,
                        modifier = Modifier.fillMaxSize().swipeTurnsPages(pager, scope).testTag(RecordTags.NUMBERS_PAGE),
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageDots(current = pager.currentPage, onSelect = { scope.launch { pager.animateScrollToPage(it) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PauseResumeButton(
                        paused = paused != null,
                        onPause = onPause,
                        onResume = onResume,
                        pauseTag = RecordTags.PAUSE,
                        resumeTag = RecordTags.RESUME,
                        modifier = Modifier.weight(1f),
                    )
                    StopButton("정지", onClick = { askEnd = true }, modifier = Modifier.weight(1f).testTag(RecordTags.STOP))
                }
            }
        }
    }

    if (askEnd) {
        RecordEndDialog(
            type = state.tripType,
            navigating = navigating,
            onChoose = { end ->
                askEnd = false
                onEnd(end)
            },
            onDismiss = { askEnd = false },
        )
    }
}

/** The auto-pause chip of the running recording ([autoPauseChipText]); a tap toggles it. */
@Composable
internal fun AutoPauseChip(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = enabled,
        onClick = { onToggle(!enabled) },
        label = { Text(autoPauseChipText(enabled)) },
        modifier = modifier,
    )
}

/** The band shown while the recording is paused ([pauseBandText]). */
@Composable
internal fun PauseBand(kind: PauseKind, modifier: Modifier = Modifier) {
    Band(pauseBandText(kind), MaterialTheme.colorScheme.tertiary, modifier)
}

/** [재개] while paused, else [일시정지]; [pauseTag] / [resumeTag] tag the button shown. */
@Composable
internal fun PauseResumeButton(
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    pauseTag: String,
    resumeTag: String,
    modifier: Modifier = Modifier,
) {
    if (paused) {
        Button(onClick = onResume, modifier = modifier.testTag(resumeTag)) { Text("재개") }
    } else {
        OutlinedButton(onClick = onPause, modifier = modifier.testTag(pauseTag)) { Text("일시정지") }
    }
}

/** The red button that ends the recording ([정지], [기록 끝내기]); it asks first ([RecordEndDialog]). */
@Composable
internal fun StopButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = modifier,
    ) { Text(label) }
}

/** "기록을 끝낼까요?" with [recordEndChoices] and [취소]. */
@Composable
internal fun RecordEndDialog(type: TripType, navigating: Boolean, onChoose: (RecordingEnd) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(RECORD_END_QUESTION) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recordEndChoices(type, navigating).forEach { choice ->
                    Button(onClick = { onChoose(choice.end) }, modifier = Modifier.fillMaxWidth()) { Text(choice.label) }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("취소") }
            }
        },
    )
}

@Composable
private fun Band(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

/** The map page: the track so far and the rider, followed until the map is moved. */
@Composable
private fun RecordMap(state: RideUiState.Active, tileSource: TileSource, modifier: Modifier) {
    var cameraMode by rememberSaveable { mutableStateOf(CameraMode.FOLLOW) }
    Box(modifier = modifier) {
        BikeMap(
            tileSource = tileSource,
            overlay = MapOverlay(route = null, stops = emptyList(), user = state.fix, track = state.track),
            cameraMode = cameraMode,
            onLongPress = {},
            onUserGesture = { cameraMode = CameraMode.FREE },
            modifier = Modifier.fillMaxSize(),
            initialCenter = state.fix?.point ?: state.track.lastOrNull(),
            initialZoom = 16.0,
        )
        if (cameraMode == CameraMode.FREE) {
            Button(
                onClick = { cameraMode = CameraMode.FOLLOW },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { Text("현재 위치로") }
        }
    }
}

/** The summary's map: the whole track framed. */
@Composable
internal fun SummaryMap(track: List<GeoPoint>, tileSource: TileSource, modifier: Modifier) {
    val fit = remember(track) { track.takeIf { it.isNotEmpty() }?.let { CameraFit(it) } }
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(route = null, stops = emptyList(), user = null, track = track),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = track.firstOrNull(),
        initialZoom = 14.0,
        fit = fit,
    )
}

private const val DIMMED_ALPHA = 0.4f
