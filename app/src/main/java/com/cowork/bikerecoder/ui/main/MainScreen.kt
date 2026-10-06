package com.cowork.bikerecoder.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.cowork.bikerecoder.ui.common.PlaceActionSheet
import kotlinx.coroutines.flow.StateFlow

private val KOREA_CENTER = GeoPoint(36.5, 127.8)

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    tileSource: TileSource,
    currentLocation: StateFlow<LocationFix?>,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onResumeTrip: (tripId: Long) -> Unit,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fix by currentLocation.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBanner() }

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
                Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp) {
                    IconButton(onClick = onOpenSettings) { GlyphIcon("⚙", "설정") }
                }
            }
            state.banner?.let { banner ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(
                            "🚩 ${banner.destinationName} · 여러 날 ${banner.dayNumber}일차",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onResumeTrip(banner.tripId) }) { Text("이어서 안내") }
                    }
                }
            }
        }

        if (user != null) {
            FloatingActionButton(
                onClick = { fit = CameraFit(listOf(user.point)) },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(bottom = 24.dp, end = 12.dp),
            ) { Text("내 위치", modifier = Modifier.padding(horizontal = 16.dp)) }
        }
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
        val name = if (pressed.loading) "선택한 위치" else pressed.title
        PlaceActionSheet(
            title = if (pressed.loading) "주소를 확인하는 중…" else pressed.title,
            subtitle = null,
            onSetDestination = {
                viewModel.dismissPressed()
                onSetDestination(name, pressed.point)
            },
            onAddWaypoint = {
                viewModel.dismissPressed()
                onAddWaypoint(name, pressed.point)
            },
            onDismiss = viewModel::dismissPressed,
        )
    }
}
