package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.core.recording.ChartPoint
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.map.BikeMap
import com.cowork.bikerecoder.map.CameraFit
import com.cowork.bikerecoder.map.CameraMode
import com.cowork.bikerecoder.map.MapLine
import com.cowork.bikerecoder.map.MapOverlay
import com.cowork.bikerecoder.map.MapPhoto
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.ui.health.HeartRateGate
import com.cowork.bikerecoder.ui.health.HeartRateLegend
import com.cowork.bikerecoder.ui.health.HeartRateSection
import com.cowork.bikerecoder.ui.health.TrackColorMode
import com.cowork.bikerecoder.ui.health.TrackColorToggle
import com.cowork.bikerecoder.ui.photo.PhotoStrip
import com.cowork.bikerecoder.ui.photo.PhotoViewerHost
import com.cowork.bikerecoder.ui.photo.rememberMapPhotos
import com.cowork.bikerecoder.ui.photo.rememberPhotoViewerState
import com.cowork.bikerecoder.ui.record.NumberRow

/**
 * What a detail screen's map shows: the [track] (with [trackColors], one per point, in [심박] — spec §3.3) or the
 * trip's segments as separate [lines] (ruling R10), and the previews of the visible photos only ([photos], spec §2.3);
 * a tap on one gives its id to [onPhotoClick].
 */
class DetailMapContent(
    val track: List<GeoPoint>,
    val photos: List<MapPhoto>,
    val onPhotoClick: (Long) -> Unit,
    val trackColors: List<String>? = null,
    val lines: List<MapLine> = emptyList(),
)

/** The detail screens' MapLibre map ([SegmentMap]) for [content]. */
@Composable
internal fun DetailMap(content: DetailMapContent, tileSource: TileSource, modifier: Modifier) {
    SegmentMap(content.track, tileSource, modifier, content.photos, content.onPhotoClick, content.trackColors, content.lines)
}

/**
 * [map] with the colour controls over it (spec §3.3): [기본] [심박] in the top corner when [canColor], and the colours'
 * key in the bottom corner while [mode] is [TrackColorMode.HEART_RATE].
 */
@Composable
internal fun ColoredDetailMap(
    canColor: Boolean,
    mode: TrackColorMode,
    onModeChange: (TrackColorMode) -> Unit,
    modifier: Modifier,
    map: @Composable BoxScope.(Modifier) -> Unit,
) {
    Box(modifier) {
        map(Modifier.fillMaxSize())
        if (canColor) {
            TrackColorToggle(mode, onModeChange, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (mode == TrackColorMode.HEART_RATE) HeartRateLegend(Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

/**
 * [photoPrompt]: the photo permission card where it applies (spec §2.4), shown under the photo strip.
 * [heartRateGate]: the heart-rate permission flow (spec §3.4; null = none).
 * [map]: the map for its content (replaceable in tests).
 */
@Composable
fun SegmentDetailScreen(
    viewModel: SegmentDetailViewModel,
    tileSource: TileSource,
    thumbnails: PhotoThumbnails,
    onBack: () -> Unit,
    photoPrompt: @Composable () -> Unit = {},
    heartRateGate: HeartRateGate? = null,
    map: @Composable (DetailMapContent, Modifier) -> Unit = { content, modifier -> DetailMap(content, tileSource, modifier) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    val heartRateChart by viewModel.heartRateChart.collectAsStateWithLifecycle()
    val canColor by viewModel.canColorByHeartRate.collectAsStateWithLifecycle()
    val colorMode by viewModel.colorMode.collectAsStateWithLifecycle()
    val trackColors by viewModel.trackColors.collectAsStateWithLifecycle()
    val viewer = rememberPhotoViewerState()
    when (val s = state) {
        SegmentDetailState.Loading -> HistoryScaffold(title = "구간 기록", onBack = onBack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        SegmentDetailState.Missing -> HistoryScaffold(title = "구간 기록", onBack = onBack) { EmptyText() }
        is SegmentDetailState.Loaded -> {
            val mapPhotos = rememberMapPhotos(photos, thumbnails)
            SegmentDetailContent(
                segment = s.segment,
                altitude = s.altitude,
                speed = s.speed,
                map = { modifier ->
                    ColoredDetailMap(canColor, colorMode, viewModel::setColorMode, modifier) { inner ->
                        map(
                            DetailMapContent(
                                s.track, mapPhotos, { photoId -> viewer.openPhoto(photos.visible, photoId) }, trackColors,
                            ),
                            inner,
                        )
                    }
                },
                onBack = onBack,
                photos = {
                    PhotoStrip(
                        photos = photos.visible,
                        hiddenCount = photos.hidden.size,
                        thumbnails = thumbnails,
                        onOpen = { viewer.open(photos.visible, it) },
                        onOpenHidden = { viewer.open(photos.hidden, 0) },
                    )
                    photoPrompt()
                },
                heartRate = { HeartRateSection(heartRate, viewModel::refetchHeartRate, heartRateGate) },
                heartRateChart = heartRateChart.takeIf { heartRate?.hrStatus == HrStatus.READY },
            )
        }
    }
    PhotoViewerHost(viewer, photos, thumbnails, onToggleHidden = { viewModel.setHidden(it, hidden = !it.hidden) })
}

/**
 * One segment (spec §3.5): the map on top (outside the scrolling part, so it keeps its drags), then [photos] (the photo
 * strip and card, spec §2.3), distance, moving and elapsed time, average/maximum, ascent/descent, highest/lowest
 * altitude, [heartRate] (the heart-rate row and card, spec §3.3) and the altitude and speed charts, and the heart-rate
 * chart over the same distance axis when there is heart rate ([heartRateChart] not null).
 */
@Composable
fun SegmentDetailContent(
    segment: RideSegment,
    altitude: List<ChartPoint>,
    speed: List<ChartPoint>,
    map: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    photos: @Composable () -> Unit = {},
    heartRate: @Composable () -> Unit = {},
    heartRateChart: List<ChartPoint>? = null,
) {
    val totals = segment.totals
    HistoryScaffold(title = "구간 기록", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            map(Modifier.fillMaxWidth().height(240.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                photos()
                Text(
                    "${RideFormat.date(segment.localDate)} · ${startTimeText(segment)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberRow(
                    "거리" to RideFormat.distance(totals.distanceM),
                    "이동 시간" to RideFormat.clock(totals.movingTimeMs),
                )
                NumberRow(
                    "경과 시간" to RideFormat.clock(totals.elapsedMs),
                    "평균/최고" to "${RideFormat.speedKmh(totals.avgSpeedMps)} / ${RideFormat.speedKmh(totals.maxSpeedMps)}km/h",
                )
                NumberRow(
                    "누적 상승" to RideFormat.altitude(totals.ascentM),
                    "누적 하강" to RideFormat.altitude(totals.descentM),
                )
                NumberRow(
                    "최고 고도" to RideFormat.altitude(totals.maxAltitudeM),
                    "최저 고도" to RideFormat.altitude(totals.minAltitudeM),
                )
                heartRate()
                Text("고도", style = MaterialTheme.typography.titleMedium)
                LineChart(altitude, unit = "m", modifier = Modifier.fillMaxWidth().height(160.dp))
                Text("속도", style = MaterialTheme.typography.titleMedium)
                LineChart(speed, unit = "km/h", modifier = Modifier.fillMaxWidth().height(160.dp))
                if (heartRateChart != null) {
                    Text("심박", style = MaterialTheme.typography.titleMedium)
                    LineChart(heartRateChart, unit = "bpm", modifier = Modifier.fillMaxWidth().height(160.dp))
                }
            }
        }
    }
}

/**
 * The [track] (in [trackColors]) or the separate [lines] framed, with a start (orange) and an end (red) marker (the
 * first and last point of all), and the [photos] ([onPhotoClick] opens one).
 */
@Composable
internal fun SegmentMap(
    track: List<GeoPoint>,
    tileSource: TileSource,
    modifier: Modifier,
    photos: List<MapPhoto> = emptyList(),
    onPhotoClick: ((Long) -> Unit)? = null,
    trackColors: List<String>? = null,
    lines: List<MapLine> = emptyList(),
) {
    val all = remember(track, lines) { track + lines.flatMap { it.points } }
    val fit = remember(all) { all.takeIf { it.isNotEmpty() }?.let { CameraFit(it) } }
    val stops = remember(all) { startEndStops(all) }
    BikeMap(
        tileSource = tileSource,
        overlay = MapOverlay(
            route = null, stops = stops, user = null, track = track, photos = photos, trackColors = trackColors,
            lines = lines,
        ),
        cameraMode = CameraMode.FREE,
        onLongPress = {},
        onUserGesture = {},
        modifier = modifier,
        initialCenter = all.firstOrNull(),
        initialZoom = 14.0,
        fit = fit,
        onPhotoClick = onPhotoClick,
    )
}

/** Start and end of the track as two stops (the end drawn as the destination); none without a track. */
internal fun startEndStops(track: List<GeoPoint>): List<Stop> {
    if (track.isEmpty()) return emptyList()
    return listOf(
        Stop(id = 0, name = "출발", point = track.first(), isDestination = false),
        Stop(id = 1, name = "도착", point = track.last(), isDestination = true),
    )
}
