package com.cowork.bikerecoder.ui.navigate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationService
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ride.ScreenNotice
import com.cowork.bikerecoder.ui.common.KeepScreenOn
import com.cowork.bikerecoder.ui.common.NoticeSnackbarHost
import com.cowork.bikerecoder.ui.record.AutoPauseChip
import com.cowork.bikerecoder.ui.record.PageDots
import com.cowork.bikerecoder.ui.record.PauseBand
import com.cowork.bikerecoder.ui.record.PauseResumeButton
import com.cowork.bikerecoder.ui.record.RecordEndDialog
import com.cowork.bikerecoder.ui.record.RIDE_MAP_PAGE
import com.cowork.bikerecoder.ui.record.RIDE_PAGE_COUNT
import com.cowork.bikerecoder.ui.record.RideNumbersPage
import com.cowork.bikerecoder.ui.record.SegmentSummaryContent
import com.cowork.bikerecoder.ui.record.StopButton
import com.cowork.bikerecoder.ui.record.SummaryMap
import com.cowork.bikerecoder.ui.record.rideSummaryLine
import com.cowork.bikerecoder.ui.record.swipeTurnsPages
import com.cowork.bikerecoder.ui.trip.EndNavigationDialog
import com.cowork.bikerecoder.ui.trip.TRIP_COMPLETED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Test tags of the guidance screen while recording. */
object NavigateTags {
    const val PAGER = "navigate-pager"
    const val RECORD_LINE = "navigate-record-line"
    const val NUMBERS_PAGE = "navigate-numbers"
    const val PANEL = "navigate-panel"
    const val CHIP = "navigate-record-chip"
    const val PAUSE = "navigate-record-pause"
    const val RESUME = "navigate-record-resume"
    const val END_RECORDING = "navigate-record-end"
    const val RIDE_FAILURE = "navigate-ride-failure"
}

private const val PAUSED_ALPHA = 0.4f

private const val TTS_MISSING =
    "한국어 음성(TTS)을 사용할 수 없습니다. 설정 > 일반 관리 > 텍스트 음성 변환에서 한국어를 설치하세요"

private val phrases = KoreanPhrases()
private val etaFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

/**
 * Turn-by-turn guidance (spec §4.5): turn card on top, the map following the rider, remaining distance /
 * ETA / speed and [음소거] [전체 경로] [종료] below. While a recording starts or runs (spec §3) the buttons are [음소거]
 * [안내 취소] [주행 종료] and [전체 경로] moves onto the map; once it runs its line sits under the turn
 * card and a second page shows its numbers. The recording's on-screen notices show on the guidance map while guiding. Also hosts the start
 * progress, start errors and the end-of-trip screens (with the recording's segment summary). Leaving the screen never
 * stops guidance; once the controller is idle (closed, or nothing to show) [onGuidanceEndedWhileRecording] is called if
 * a recording goes on ([goesToRecordAfterGuidance]: [안내 취소], ruling U1's arrival), else [onClose].
 */
@Composable
fun NavigateScreen(
    viewModel: NavigateViewModel,
    tileSource: TileSource,
    onClose: () -> Unit,
    onGuidanceEndedWhileRecording: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val ride by viewModel.rideUi.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val offlineProgress by viewModel.offlineProgress.collectAsStateWithLifecycle()
    val serviceWarning by viewModel.serviceWarning.collectAsStateWithLifecycle()
    val notice by viewModel.noticeForScreen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var askEnd by rememberSaveable { mutableStateOf(false) }

    // Start only while the screen is visible: a location FGS needs a visible activity (spec §2.2).
    LifecycleResumeEffect(viewModel) {
        viewModel.takePendingStart()?.let { tripId ->
            NavigationService.start(context)
            viewModel.begin(tripId)
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        val notice = viewModel.takeRecordNotice() ?: return@LaunchedEffect
        snackbar.showSnackbar(notice, duration = SnackbarDuration.Long)
    }

    LaunchedEffect(viewModel) {
        viewModel.ttsNotice.first { it }
        viewModel.ttsNoticeShown()
        snackbar.showSnackbar(TTS_MISSING, duration = SnackbarDuration.Long)
    }

    val state = ui
    KeepScreenOn(keepScreenOn && (state is NavUiState.Starting || state is NavUiState.Active))

    BackHandler(enabled = state !is NavUiState.Idle) {
        when (state) {
            is NavUiState.Active -> askEnd = true
            is NavUiState.Finished -> viewModel.confirmFinished()
            else -> viewModel.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            NavUiState.Idle -> if (!viewModel.hasPendingStart) {
                LaunchedEffect(Unit) {
                    // E.g. [안내 취소] or ruling U1's arrival: the recording goes on, on its own screen.
                    if (goesToRecordAfterGuidance(viewModel.rideUi.value)) onGuidanceEndedWhileRecording() else onClose()
                }
            }
            is NavUiState.Starting -> StartingContent(onCancel = viewModel::close)
            is NavUiState.Failed -> FailedContent(
                message = state.message,
                onRetry = if (state.canRetry) {
                    {
                        NavigationService.start(context)
                        viewModel.begin(state.tripId)
                    }
                } else {
                    null
                },
                onClose = viewModel::close,
            )
            is NavUiState.Active -> ActiveContent(
                state = state,
                ride = ride as? RideUiState.Active,
                rideFailure = guidedRideFailure(state.tripId, ride),
                tileSource = tileSource,
                mapDownloadText = offlineProgress?.takeIf { it.tripId == state.tripId }?.chipText,
                serviceWarning = serviceWarning,
                showRecordingButtons = recording,
                notice = notice,
                onNoticeShown = viewModel::noticeShown,
                onMute = viewModel::setMuted,
                onEnd = { askEnd = true },
                onCancelGuidance = viewModel::cancelGuidance,
                onDismissRideFailure = viewModel::dismissRideFailure,
                recordControls = RecordControls(
                    onPause = viewModel::pauseRecording,
                    onResume = viewModel::resumeRecording,
                    onToggleAutoPause = viewModel::setRecordingAutoPause,
                    onEnd = viewModel::endRecording,
                ),
            )
            is NavUiState.Finished -> when (val view = finishedView(state, ride)) {
                FinishedView.Waiting -> SavingContent()
                // Nothing to confirm: the main screen's banner offers [이어서 안내].
                FinishedView.Close -> LaunchedEffect(state) { viewModel.close() }
                // The trip's new state was not saved: say so instead of reporting success. A summary is not shown here
                // ([닫기] = back = confirmFinished leaves it for the main screen).
                is FinishedView.Error ->
                    FailedContent(message = view.message, onRetry = null, onClose = viewModel::confirmFinished)
                is FinishedView.Done -> {
                    val summary = view.summary
                    if (summary == null) {
                        FinishedContent(
                            canConvert = view.canConvert,
                            onConvert = viewModel::convertToMultiDay,
                            onConfirm = viewModel::confirmFinished,
                        )
                    } else {
                        SegmentSummaryContent(
                            segment = summary.segment,
                            map = { modifier -> SummaryMap(summary.track, tileSource, modifier) },
                            onConfirm = viewModel::confirmFinished,
                        ) {
                            if (view.completed) Text(TRIP_COMPLETED, style = MaterialTheme.typography.headlineSmall)
                            if (view.canConvert) {
                                OutlinedButton(onClick = viewModel::convertToMultiDay) { Text("여러 날로 바꾸기") }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.Center).padding(16.dp))
    }

    if (askEnd && state is NavUiState.Active) {
        EndNavigationDialog(
            type = state.type,
            recording = recording,
            onChoose = { action ->
                askEnd = false
                viewModel.onEndChoice(action)
            },
            onDismiss = { askEnd = false },
        )
    }
}

/**
 * Guidance (spec §4.5) in one layout whether or not a recording runs, so the map keeps its place in the composition
 * (its MapView is not recreated when the auto-started recording turns active): a pager over the bottom panel whose
 * first page is the guidance map. While recording (spec §3.3) the record line sits under the turn card and a second
 * page shows the recording's numbers with its controls (ruling F2: the auto-pause chip, [일시정지]/[재개] and
 * [기록 끝내기], which ends only the recording). As on the record screen the pager takes no drags (the map keeps its
 * own): pages turn by swiping the record line, the numbers page or the bottom panel, or by the page dots.
 * [rideFailure]: the guided trip's recording could not start (a banner; a tap closes it with [onDismissRideFailure]).
 * [showRecordingButtons]: a recording starts or runs — [GuidanceButtonRow]'s recording buttons, and [전체 경로] on the map.
 * The recording's on-screen [notice] sits at the bottom of the map page, above the BottomEnd [전체 경로] button, so it
 * covers neither that button nor the page dots ([onNoticeShown] reports it shown); while the numbers page shows, a
 * notice waits until the map page is back.
 */
@Composable
private fun ActiveContent(
    state: NavUiState.Active,
    ride: RideUiState.Active?,
    rideFailure: String?,
    tileSource: TileSource,
    mapDownloadText: String?,
    serviceWarning: String?,
    showRecordingButtons: Boolean,
    notice: ScreenNotice?,
    onNoticeShown: (Long) -> Unit,
    onMute: (Boolean) -> Unit,
    onEnd: () -> Unit,
    onCancelGuidance: () -> Unit,
    onDismissRideFailure: () -> Unit,
    recordControls: RecordControls,
) {
    var cameraMode by rememberSaveable { mutableStateOf(CameraMode.FOLLOW) }
    var fit by remember { mutableStateOf<CameraFit?>(null) }
    val nav = state.state
    val recording = ride != null
    val pager = rememberPagerState { if (recording) RIDE_PAGE_COUNT else 1 }
    // The map page stays composed behind the numbers page: its notice host takes notices only while it is seen.
    val mapPageSeen by remember(pager) {
        derivedStateOf { pager.settledPage == RIDE_MAP_PAGE && !pager.isScrollInProgress }
    }
    val scope = rememberCoroutineScope()
    val pausedState = ride?.state as? RecordingState.Paused
    val paused = pausedState != null
    var askEndRecording by rememberSaveable { mutableStateOf(false) }
    val showOverview = {
        cameraMode = CameraMode.FREE
        fit = CameraFit(nav.route.points)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                // Keeps the map page (its MapView and camera) alive while the numbers page shows.
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(NavigateTags.PAGER),
            ) { page ->
                if (page == RIDE_MAP_PAGE) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        BikeMap(
                            tileSource = tileSource,
                            overlay = MapOverlay(route = nav.route, stops = state.stops, user = state.fix),
                            cameraMode = cameraMode,
                            onLongPress = {},
                            onUserGesture = { cameraMode = CameraMode.FREE },
                            modifier = Modifier.fillMaxSize(),
                            initialCenter = state.fix?.point ?: nav.route.points.first(),
                            initialZoom = 17.0,
                            fit = fit,
                        )
                        if (showRecordingButtons) {
                            // Its place in the button row is taken by [안내 취소].
                            FilledTonalButton(
                                onClick = showOverview,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp)
                                    .testTag(GuidanceTags.OVERVIEW_ON_MAP),
                            ) { Text(GuidanceButton.OVERVIEW.label) }
                        }
                        // Clears the 12 dp + 48 dp [전체 경로] button at BottomEnd. On the numbers page a notice waits.
                        NoticeSnackbarHost(
                            notice = notice,
                            onShown = onNoticeShown,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                            active = mapPageSeen,
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TurnCard(nav)
                            if (ride != null) {
                                Text(
                                    rideSummaryLine(ride.stats),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(vertical = 10.dp, horizontal = 12.dp)
                                        .alpha(if (paused) PAUSED_ALPHA else 1f)
                                        .swipeTurnsPages(pager, scope)
                                        .testTag(NavigateTags.RECORD_LINE),
                                )
                            }
                            if (mapDownloadText != null) MapDownloadIndicator(mapDownloadText)
                            if (serviceWarning != null) Banner(serviceWarning)
                            if (rideFailure != null) {
                                Banner(
                                    rideFailure,
                                    Modifier.clickable(onClickLabel = "닫기", onClick = onDismissRideFailure)
                                        .testTag(NavigateTags.RIDE_FAILURE),
                                )
                            }
                            if (nav.gpsWeak) Banner("GPS 신호 약함")
                            if (nav.rerouting) Banner("경로를 다시 탐색하는 중…")
                        }
                    }
                } else if (ride != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .swipeTurnsPages(pager, scope)
                            .padding(12.dp)
                            .testTag(NavigateTags.NUMBERS_PAGE),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoPauseChip(ride.autoPause, recordControls.onToggleAutoPause, Modifier.testTag(NavigateTags.CHIP))
                        if (pausedState != null) PauseBand(pausedState.kind)
                        RideNumbersPage(stats = ride.stats, dimmed = paused, modifier = Modifier.weight(1f).fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            PauseResumeButton(
                                paused = paused,
                                onPause = recordControls.onPause,
                                onResume = recordControls.onResume,
                                pauseTag = NavigateTags.PAUSE,
                                resumeTag = NavigateTags.RESUME,
                                modifier = Modifier.weight(1f),
                            )
                            StopButton(
                                "기록 끝내기",
                                onClick = { askEndRecording = true },
                                modifier = Modifier.weight(1f).testTag(NavigateTags.END_RECORDING),
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pager.currentPage == RIDE_MAP_PAGE && cameraMode == CameraMode.FREE) {
                    Button(onClick = { cameraMode = CameraMode.FOLLOW }) { Text("현재 위치로") }
                }
                if (recording) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PageDots(current = pager.currentPage, onSelect = { scope.launch { pager.animateScrollToPage(it) } })
                    }
                }
                BottomPanel(
                    nav = nav,
                    recording = showRecordingButtons,
                    muted = state.muted,
                    onMute = onMute,
                    onOverview = showOverview,
                    onEnd = onEnd,
                    onCancelGuidance = onCancelGuidance,
                    modifier = Modifier.swipeTurnsPages(pager, scope).testTag(NavigateTags.PANEL),
                )
            }
        }
    }

    if (askEndRecording && ride != null) {
        // While guiding, only the recording ends (the guidance owns the trip).
        RecordEndDialog(
            type = ride.tripType,
            navigating = true,
            onChoose = {
                askEndRecording = false
                recordControls.onEnd()
            },
            onDismiss = { askEndRecording = false },
        )
    }
}

/** The recording's controls on the guidance numbers page (ruling F2). [onEnd]: [기록 끝내기] after its confirmation. */
private class RecordControls(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onToggleAutoPause: (Boolean) -> Unit,
    val onEnd: () -> Unit,
)

@Composable
private fun TurnCard(nav: NavState) {
    val progress = nav.progress
    val next = progress.nextInstruction
    val instructions = nav.route.instructions
    val afterNext = next?.let { instructions.getOrNull(instructions.indexOf(it) + 1) }
    val turnText = next?.let { phrases.turn(it.type, it.roundaboutExit) } ?: "목적지"

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(turnIcon(next?.type ?: TurnType.STRAIGHT), contentDescription = turnText, modifier = Modifier.size(56.dp))
            Column {
                Text(
                    SummaryFormatter.distance(progress.distanceToNextInstructionM ?: progress.remainingM),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(turnText, style = MaterialTheme.typography.titleMedium)
                if (afterNext != null) {
                    Text(
                        "그다음 ${phrases.turn(afterNext.type, afterNext.roundaboutExit)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Material turn icons per [TurnType] (roundabouts: right-hand traffic, counter-clockwise). */
private fun turnIcon(type: TurnType): ImageVector = when (type) {
    TurnType.STRAIGHT -> Icons.Filled.Straight
    TurnType.LEFT -> Icons.Filled.TurnLeft
    TurnType.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
    TurnType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    TurnType.RIGHT -> Icons.Filled.TurnRight
    TurnType.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
    TurnType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    TurnType.KEEP_LEFT -> Icons.Filled.ForkLeft
    TurnType.KEEP_RIGHT -> Icons.Filled.ForkRight
    TurnType.U_TURN -> Icons.Filled.UTurnLeft
    TurnType.ROUNDABOUT -> Icons.Filled.RoundaboutRight
}

/** Small, unobtrusive "map is being saved for offline use" chip; hidden when nothing is downloading. */
@Composable
private fun MapDownloadIndicator(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

@Composable
private fun BottomPanel(
    nav: NavState,
    recording: Boolean,
    muted: Boolean,
    onMute: (Boolean) -> Unit,
    onOverview: () -> Unit,
    onEnd: () -> Unit,
    onCancelGuidance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("남은 거리", SummaryFormatter.distance(nav.progress.remainingM))
                Stat("도착 예정", if (nav.etaMillis > 0) etaText(nav.etaMillis) else "--")
                Stat("속도", "${(nav.speedMps * 3.6).roundToInt()}km/h")
            }
            GuidanceButtonRow(
                recording = recording,
                muted = muted,
                onMute = onMute,
                onOverview = onOverview,
                onEnd = onEnd,
                onCancelGuidance = onCancelGuidance,
            )
        }
    }
}

private fun etaText(epochMillis: Long): String =
    etaFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StartingContent(onCancel: () -> Unit) {
    CenteredPanel {
        CircularProgressIndicator()
        Text("현재 위치를 확인하고 경로를 계산하는 중…", textAlign = TextAlign.Center)
        TextButton(onClick = onCancel) { Text("취소") }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: (() -> Unit)?, onClose: () -> Unit) {
    CenteredPanel {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        if (onRetry != null) Button(onClick = onRetry) { Text("다시 시도") }
        TextButton(onClick = onClose) { Text("닫기") }
    }
}

/** The guidance ended and the recording of its trip is still being saved: its summary comes next. */
@Composable
private fun SavingContent() {
    CenteredPanel {
        CircularProgressIndicator()
        Text("기록을 저장하는 중…", textAlign = TextAlign.Center)
    }
}

@Composable
private fun FinishedContent(canConvert: Boolean, onConvert: () -> Unit, onConfirm: () -> Unit) {
    CenteredPanel {
        Text(TRIP_COMPLETED, style = MaterialTheme.typography.headlineSmall)
        if (canConvert) OutlinedButton(onClick = onConvert) { Text("여러 날로 바꾸기") }
        Button(onClick = onConfirm) { Text("확인") }
    }
}

@Composable
private fun CenteredPanel(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}
