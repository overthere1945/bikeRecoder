package com.cowork.bikerecoder.ui.navigate

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.SummaryFormatter
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.navigation.NavState
import com.cowork.bikerecoder.core.voice.KoreanPhrases
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.nav.NavigationService
import com.cowork.bikerecoder.ui.trip.EndNavigationDialog
import com.cowork.bikerecoder.ui.trip.TRIP_COMPLETED
import com.cowork.bikerecoder.ui.trip.canConvertToMultiDay
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val TTS_MISSING =
    "한국어 음성(TTS)을 사용할 수 없습니다. 설정 > 일반 관리 > 텍스트 음성 변환에서 한국어를 설치하세요"

private val phrases = KoreanPhrases()
private val etaFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

/**
 * Turn-by-turn guidance (spec §4.5): turn card on top, the map following the rider, remaining distance /
 * ETA / speed and [음소거] [전체 경로] [종료] below. Also hosts the start progress, start errors and the
 * end-of-trip screens. Leaving the screen never stops guidance; [onClose] is called once the controller is
 * idle (closed, or nothing to show).
 */
@Composable
fun NavigateScreen(viewModel: NavigateViewModel, tileSource: TileSource, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val offlineProgress by viewModel.offlineProgress.collectAsStateWithLifecycle()
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
        viewModel.ttsNotice.first { it }
        viewModel.ttsNoticeShown()
        snackbar.showSnackbar(TTS_MISSING, duration = SnackbarDuration.Long)
    }

    val state = ui
    KeepScreenOn(keepScreenOn && (state is NavUiState.Starting || state is NavUiState.Active))

    BackHandler(enabled = state !is NavUiState.Idle) {
        if (state is NavUiState.Active) askEnd = true else viewModel.close()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            NavUiState.Idle -> if (!viewModel.hasPendingStart) LaunchedEffect(Unit) { onClose() }
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
                tileSource = tileSource,
                mapDownloadText = offlineProgress?.takeIf { it.tripId == state.tripId }?.chipText,
                onMute = viewModel::setMuted,
                onEnd = { askEnd = true },
            )
            is NavUiState.Finished ->
                if (state.error != null) {
                    // The trip's new state was not saved: say so instead of reporting success.
                    FailedContent(message = state.error, onRetry = null, onClose = viewModel::close)
                } else if (state.reason == FinishReason.STOPPED_TODAY) {
                    // Nothing to confirm: the main screen's banner offers [이어서 안내].
                    LaunchedEffect(state) { viewModel.close() }
                } else {
                    FinishedContent(
                        canConvert = canConvertToMultiDay(state),
                        onConvert = viewModel::convertToMultiDay,
                        onConfirm = viewModel::close,
                    )
                }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.Center).padding(16.dp))
    }

    if (askEnd && state is NavUiState.Active) {
        EndNavigationDialog(
            type = state.type,
            onChoose = { action ->
                askEnd = false
                viewModel.onEndChoice(action)
            },
            onDismiss = { askEnd = false },
        )
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun ActiveContent(
    state: NavUiState.Active,
    tileSource: TileSource,
    mapDownloadText: String?,
    onMute: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    var cameraMode by rememberSaveable { mutableStateOf(CameraMode.FOLLOW) }
    var fit by remember { mutableStateOf<CameraFit?>(null) }
    val nav = state.state

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

        Column(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TurnCard(nav)
            if (mapDownloadText != null) MapDownloadIndicator(mapDownloadText)
            if (nav.gpsWeak) Banner("GPS 신호 약함")
            if (nav.rerouting) Banner("경로를 다시 탐색하는 중…")
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (cameraMode == CameraMode.FREE) {
                Button(onClick = { cameraMode = CameraMode.FOLLOW }) { Text("현재 위치로") }
            }
            BottomPanel(
                nav = nav,
                muted = state.muted,
                onMute = onMute,
                onOverview = {
                    cameraMode = CameraMode.FREE
                    fit = CameraFit(nav.route.points)
                },
                onEnd = onEnd,
            )
        }
    }
}

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
private fun Banner(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

@Composable
private fun BottomPanel(nav: NavState, muted: Boolean, onMute: (Boolean) -> Unit, onOverview: () -> Unit, onEnd: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("남은 거리", SummaryFormatter.distance(nav.progress.remainingM))
                Stat("도착 예정", if (nav.etaMillis > 0) etaText(nav.etaMillis) else "--")
                Stat("속도", "${(nav.speedMps * 3.6).roundToInt()}km/h")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onMute(!muted) }, modifier = Modifier.weight(1f)) {
                    Text(if (muted) "음소거 해제" else "음소거")
                }
                OutlinedButton(onClick = onOverview, modifier = Modifier.weight(1f)) { Text("전체 경로") }
                Button(onClick = onEnd, modifier = Modifier.weight(1f)) { Text("종료") }
            }
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
