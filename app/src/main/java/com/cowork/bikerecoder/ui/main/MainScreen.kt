package com.cowork.bikerecoder.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.common.PressedPlaceSheet
import com.cowork.bikerecoder.ui.record.RECORDING_INTERRUPTED
import kotlinx.coroutines.flow.StateFlow

private val KOREA_CENTER = GeoPoint(36.5, 127.8)

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    tileSource: TileSource,
    currentLocation: StateFlow<LocationFix?>,
    onOpenSearch: () -> Unit,
    /** ☰ → [기록]. */
    onOpenHistory: () -> Unit,
    /** ☰ → [설정]. */
    onOpenSettings: () -> Unit,
    onResumeTrip: (tripId: Long) -> Unit,
    /** [보기] on the riding banner. */
    onShowRide: (RideTarget) -> Unit,
    /** [● 기록 시작] with the location permission granted: which trip to record is asked next. */
    onStartRecording: () -> Unit,
    /** [이어서 기록] on "기록이 중단되었습니다". */
    onContinueRecording: (tripId: Long) -> Unit,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fix by currentLocation.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBanner() }
    val context = LocalContext.current

    // Recording needs the location permission: asked when missing, then [afterPermission] runs.
    var permissionDenied by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterPermission
        afterPermission = null
        if (granted) action?.invoke() else permissionDenied = true
    }
    val withLocationPermission = { action: () -> Unit ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Centre on the user once, the first time a fix is known; later only via the 내 위치 button.
    var fit by remember { mutableStateOf<CameraFit?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    val user = fix
    LaunchedEffect(user != null) {
        if (user != null && !centeredOnce) {
            centeredOnce = true
            fit = CameraFit(listOf(user.point))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BikeMap(
            tileSource = tileSource,
            overlay = MapOverlay(route = null, stops = emptyList(), user = user),
            cameraMode = CameraMode.FREE,
            onLongPress = viewModel::onLongPress,
            onUserGesture = {},
            modifier = Modifier.fillMaxSize(),
            initialCenter = user?.point ?: KOREA_CENTER,
            initialZoom = if (user != null) 14.0 else 7.0,
            fit = fit,
        )

        Column(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.weight(1f).clickable(onClick = onOpenSearch),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlyphIcon("🔍", null)
                        Text("장소, 주소 검색", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp) {
                        IconButton(onClick = { menuOpen = true }) { GlyphIcon("☰", "메뉴") }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("기록") }, onClick = {
                            menuOpen = false
                            onOpenHistory()
                        })
                        DropdownMenuItem(text = { Text("설정") }, onClick = {
                            menuOpen = false
                            onOpenSettings()
                        })
                    }
                }
            }
            state.riding?.let { riding ->
                BannerCard(text = riding.text, action = "보기") { onShowRide(riding.target) }
            }
            state.visibleBanner?.let { banner ->
                BannerCard(text = banner.text, action = banner.action.label) {
                    when (banner.action) {
                        BannerAction.RESUME -> onResumeTrip(banner.tripId)
                        // The chosen place goes into the plan; [안내 시작] → [이어서] adds it to this trip.
                        BannerAction.NEXT_DESTINATION -> onOpenSearch()
                    }
                }
            }
        }

        // [● 기록 시작] bottom right, [내 위치] above it.
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(bottom = 24.dp, end = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (user != null) {
                FloatingActionButton(onClick = { fit = CameraFit(listOf(user.point)) }) {
                    Text("내 위치", modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            if (!state.recording) {
                // Disabled until the interrupted recording is answered (closing it would race the new start).
                Button(
                    onClick = { withLocationPermission(onStartRecording) },
                    enabled = state.canStartRecording,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 6.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                ) { Text("● 기록 시작") }
            }
        }
    }

    state.interrupted?.let { interrupted ->
        // Must be answered: [기록 시작] waits for it.
        AlertDialog(
            onDismissRequest = {},
            text = { Text(RECORDING_INTERRUPTED) },
            confirmButton = {
                // Without the permission the offer stays (the denial dialog explains why).
                Button(onClick = {
                    withLocationPermission {
                        viewModel.dismissInterrupted()
                        onContinueRecording(interrupted.tripId)
                    }
                }) { Text("이어서 기록") }
            },
            dismissButton = { TextButton(onClick = viewModel::closeInterruptedRide) { Text("종료") } },
        )
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            text = { Text("위치 권한이 있어야 기록할 수 있습니다") },
            confirmButton = {
                TextButton(onClick = {
                    permissionDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("설정 열기") }
            },
            dismissButton = { TextButton(onClick = { permissionDenied = false }) { Text("취소") } },
        )
    }

    state.staleTrip?.let { stale ->
        AlertDialog(
            onDismissRequest = viewModel::dismissStaleTrip,
            text = { Text("${stale.destinationName} 여행이 3일 이상 멈춰 있습니다. 완료 처리할까요?") },
            confirmButton = { TextButton(onClick = viewModel::completeStaleTrip) { Text("완료") } },
            dismissButton = { TextButton(onClick = viewModel::dismissStaleTrip) { Text("계속 유지") } },
        )
    }

    state.pressed?.let { pressed ->
        PressedPlaceSheet(
            pressed = pressed,
            onSetDestination = { name, point ->
                viewModel.dismissPressed()
                onSetDestination(name, point)
            },
            onAddWaypoint = { name, point ->
                viewModel.dismissPressed()
                onAddWaypoint(name, point)
            },
            onSaveFavorite = null, // Task 4
            onDismiss = viewModel::dismissPressed,
        )
    }
}

@Composable
private fun BannerCard(text: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
