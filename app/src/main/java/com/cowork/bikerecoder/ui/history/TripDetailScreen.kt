package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.core.series.MetricKind
import com.cowork.bikerecoder.core.series.MetricSeries
import com.cowork.bikerecoder.export.ExportFormat
import com.cowork.bikerecoder.export.ExportMenu
import com.cowork.bikerecoder.export.ExportOutcome
import com.cowork.bikerecoder.export.ExportProgress
import com.cowork.bikerecoder.export.ExportState
import com.cowork.bikerecoder.importing.importedFromText
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.ui.elevation.ChartTab
import com.cowork.bikerecoder.ui.elevation.ChartText
import com.cowork.bikerecoder.ui.elevation.ElevationChart
import com.cowork.bikerecoder.ui.elevation.MetricChart
import com.cowork.bikerecoder.ui.health.HR_REFETCH
import com.cowork.bikerecoder.ui.health.HeartRateAccess
import com.cowork.bikerecoder.ui.health.HeartRateGate
import com.cowork.bikerecoder.ui.health.HeartRateNotice
import com.cowork.bikerecoder.ui.health.heartRateLine
import com.cowork.bikerecoder.ui.health.rememberHeartRateAccess
import com.cowork.bikerecoder.ui.photo.PhotoStrip
import com.cowork.bikerecoder.ui.photo.PhotoViewerHost
import com.cowork.bikerecoder.ui.photo.rememberMapPhotos
import com.cowork.bikerecoder.ui.photo.rememberPhotoViewerState
import com.cowork.bikerecoder.ui.record.NumberRow
import com.cowork.bikerecoder.ui.video.MediaThumbnail
import com.cowork.bikerecoder.ui.video.TripMediaRow
import com.cowork.bikerecoder.video.TripMedia
import java.time.LocalDate

/**
 * [photoPrompt]: the photo permission card where it applies (spec §2.4), shown under the photo strip.
 * [heartRateGate]: the heart-rate permission card or "Health Connect를 사용할 수 없습니다" (spec §3.4; null = none).
 * [map]: the map for its content (replaceable in tests).
 * [mediaEntry]: [영상 만들기] [이미지 만들기] (or the progress card) of this trip, shown when it is completed with a ride to
 * make it from (V1.3.0 spec §1.1); [mediaThumbnail] and [onOpenMedia]: "만든 영상·이미지" (§5.1).
 * [onShare]: the paper plane on the title row, shown while the trip has a finished ride to share (V1.5.0 spec §1.1).
 * [onOpenChart]: a small chart's [지도에 표시] / tap opens the big map on that chart's tab (V1.7.0 spec §2.3, V1.8.0 §2.2).
 * The title row's [⋮] exports the trip as GPX or KML (V1.7.0 spec §4.1, §4.2), through the view model.
 */
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    tileSource: TileSource,
    thumbnails: PhotoThumbnails,
    onOpenSegment: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenChart: (ChartTab) -> Unit = {},
    photoPrompt: @Composable () -> Unit = {},
    heartRateGate: HeartRateGate? = null,
    mediaEntry: @Composable () -> Unit = {},
    mediaThumbnail: MediaThumbnail = { _, _ -> null },
    onOpenMedia: (TripMedia) -> Unit = {},
    onShare: () -> Unit = {},
    map: @Composable (DetailMapContent, Modifier) -> Unit = { content, modifier -> DetailMap(content, tileSource, modifier) },
) {
    val segmentsByDate by viewModel.segmentsByDate.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    val colorMode by viewModel.colorMode.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val refetching by viewModel.refetching.collectAsStateWithLifecycle()
    val canMakeMedia by viewModel.canMakeMedia.collectAsStateWithLifecycle()
    val canShare by viewModel.canShare.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val customTitle by viewModel.customTitle.collectAsStateWithLifecycle()
    val autoTitle by viewModel.autoTitle.collectAsStateWithLifecycle()
    val importedFrom by viewModel.importedFrom.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val profileLoaded by viewModel.profileLoaded.collectAsStateWithLifecycle()
    val speedSeries by viewModel.speedSeries.collectAsStateWithLifecycle()
    val heartSeries by viewModel.heartSeries.collectAsStateWithLifecycle()
    val heartDescription by viewModel.heartDescription.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    val access = rememberHeartRateAccess(heartRateGate)
    val viewer = rememberPhotoViewerState()
    val mapPhotos = rememberMapPhotos(photos, thumbnails)
    // A recording of this trip may have gone on meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    TripDetailContent(
        segmentsByDate = segmentsByDate,
        total = total,
        title = title,
        onRename = { renaming = true },
        onShare = onShare.takeIf { canShare },
        importedFrom = importedFrom,
        profile = profile,
        profileLoaded = profileLoaded,
        onOpenChart = onOpenChart,
        speedSeries = speedSeries,
        heartSeries = heartSeries,
        heartDescription = heartDescription,
        onExport = if (viewModel.canExport) viewModel::export else null,
        exportEnabled = !recording,
        exporting = exportState == ExportState.Running,
        dialog = dialog,
        onOpenSegment = onOpenSegment,
        onRequestDelete = viewModel::requestDeleteSegment,
        onConfirmDelete = viewModel::confirmDelete,
        onDismiss = viewModel::dismiss,
        onBack = onBack,
        map = { modifier ->
            ColoredDetailMap(heartRate.canColor, colorMode, viewModel::setColorMode, modifier) { inner, fitPadding ->
                map(
                    DetailMapContent(
                        emptyList(), mapPhotos, { photoId -> viewer.openPhoto(photos.visible, photoId) }, lines = lines,
                        fitPadding = fitPadding,
                    ),
                    inner,
                )
            }
        },
        photos = {
            PhotoStrip(
                photos = photos.visible,
                hiddenCount = photos.hidden.size,
                thumbnails = thumbnails,
                onOpen = { viewer.open(photos.visible, it) },
                onOpenHidden = { viewer.open(photos.hidden, 0) },
            )
            photoPrompt()
            HeartRateNotice(heartRateGate, access)
        },
        heartRate = heartRate,
        onRefetchHeartRate = tripRefetchAction(access.value, viewModel::refetchHeartRate),
        refetching = refetching,
        // Nothing to offer nor show: no item (and no gap) at all.
        videos = if (canMakeMedia || media.isNotEmpty()) {
            {
                if (canMakeMedia) mediaEntry()
                TripMediaRow(media, mediaThumbnail, onOpenMedia)
            }
        } else {
            null
        },
    )
    PhotoViewerHost(viewer, photos, thumbnails, onToggleHidden = { viewModel.setHidden(it, hidden = !it.hidden) })
    ExportOutcome(exportState, onClear = viewModel::clearExport)
    if (renaming) {
        RenameTripDialog(
            current = customTitle,
            placeholder = autoTitle.orEmpty(),
            onSave = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

/**
 * What [다시 가져오기] does on the trip's totals (spec §6.9): [refetch] only while heart rate may be read; without the
 * permission (the card is shown instead) or Health Connect, and while the access is still being checked, no button.
 */
internal fun tripRefetchAction(access: HeartRateAccess, refetch: () -> Unit): (() -> Unit)? =
    refetch.takeIf { access == HeartRateAccess.GRANTED }

/**
 * The trip's title row ([title] with [✎] that calls [onRename], V1.4.0 spec §2.5; the paper plane [onShare] before it,
 * V1.5.0 spec §1.1) above everything else, under it "{파일 이름}에서 가져옴" of an imported trip ([importedFrom], V1.6.0
 * spec §4.2), then the map ([map], every day's track and photos; outside the scrolling part, so it keeps its drags), then
 * [videos] ([영상 만들기] [이미지 만들기] and "만든 영상·이미지", V1.3.0 spec §1.1, §5.1),
 * [photos] (the photo strip and card, spec §2.3), the totals (with the trip's [heartRate], spec §3.3; the third row is
 * the ascent, descent and highest altitude, V1.7.0 spec §2.3), under them the small charts (V1.8.0 spec §2.2), each under
 * its title: 고도 of [profile], 속도 of [speedSeries] (read out with [total]'s average and highest) and, only with a
 * [heartSeries], 심박 (read out as [heartDescription]); each with [지도에 표시], opening the big map on its tab through
 * [onOpenChart]; none while [profileLoaded] is false. Then its segments under a header per date (with that day's average
 * heart rate); a long press asks to delete.
 * [onExport]: the title row's [⋮] at its right end (the row is drawn for it even while there is no [title]) with "GPX로 내보내기" and "KML로 내보내기" (V1.7.0 spec §4.1), dimmed
 * with "기록을 마친 뒤 내보낼 수 있습니다" while [exportEnabled] is false; [exporting] shows a small progress indicator
 * over the screen's content (§4.2).
 */
@Composable
fun TripDetailContent(
    segmentsByDate: Map<LocalDate, List<RideSegment>>,
    total: SegmentTotals,
    dialog: HistoryDialog?,
    onOpenSegment: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    map: (@Composable (Modifier) -> Unit)? = null,
    photos: @Composable () -> Unit = {},
    heartRate: TripHeartRate = TripHeartRate(),
    onRefetchHeartRate: (() -> Unit)? = null,
    refetching: Boolean = false,
    videos: (@Composable () -> Unit)? = null,
    title: String? = null,
    onRename: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    importedFrom: String? = null,
    profile: ElevationProfile? = null,
    profileLoaded: Boolean = true,
    onOpenChart: ((ChartTab) -> Unit)? = null,
    speedSeries: MetricSeries? = null,
    heartSeries: MetricSeries? = null,
    heartDescription: String = "",
    onExport: ((ExportFormat) -> Unit)? = null,
    exportEnabled: Boolean = true,
    exporting: Boolean = false,
) {
    HistoryScaffold(title = "여행 기록", onBack = onBack) {
        if (segmentsByDate.isEmpty()) {
            EmptyText()
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // A trip without a title yet (no ride of 10 m finished) still gets the row for its [⋮].
                val titled = title != null && onRename != null
                if (titled || onExport != null) {
                    TripTitleRow(
                        title = if (titled) title else null,
                        onRename = if (titled) onRename else null,
                        onShare = if (titled) onShare else null,
                    ) {
                        if (onExport != null) ExportMenu(onExport, enabled = exportEnabled, running = exporting)
                    }
                }
                if (importedFrom != null) {
                    Text(
                        importedFromText(importedFrom),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                map?.invoke(Modifier.fillMaxWidth().height(240.dp))
                TripDetailList(
                    segmentsByDate, total, heartRate, onOpenSegment, onRequestDelete, videos, photos, onRefetchHeartRate,
                    refetching,
                    charts = if (profileLoaded) {
                        { TripCharts(profile, speedSeries, heartSeries, total, heartDescription, onOpenChart) }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // Over the content, in the scaffold's box.
            if (exporting) ExportProgress()
        }
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_SEGMENT, onConfirmDelete, onDismiss)
}

/**
 * The shown title in `titleLarge` (an empty area when [title] is null), and on the right the paper plane "공유" (when
 * [onShare]), [✎]: "제목 바꾸기" (when [onRename]), and at the end [more] (the export [⋮]).
 */
@Composable
private fun TripTitleRow(title: String?, onRename: (() -> Unit)?, onShare: (() -> Unit)?, more: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onShare != null) {
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "공유")
            }
        }
        if (onRename != null) {
            IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = RENAME_TITLE) }
        }
        more()
    }
}

@Composable
private fun TripDetailList(
    segmentsByDate: Map<LocalDate, List<RideSegment>>,
    total: SegmentTotals,
    heartRate: TripHeartRate,
    onOpenSegment: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    videos: (@Composable () -> Unit)?,
    photos: @Composable () -> Unit,
    onRefetchHeartRate: (() -> Unit)?,
    refetching: Boolean,
    charts: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (videos != null) {
            item(key = "videos") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { videos() }
            }
        }
        item(key = "photos") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { photos() }
        }
        item(key = "total") { TripTotal(total, heartRate, onRefetchHeartRate, refetching) }
        if (charts != null) {
            item(key = "charts") { charts() }
        }
        segmentsByDate.forEach { (date, segments) ->
            item(key = "date-$date") {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(RideFormat.date(date), style = MaterialTheme.typography.titleMedium)
                    heartRate.avgByDate[date]?.let { avg ->
                        Spacer(Modifier.weight(1f))
                        Text(
                            "평균 심박 $avg bpm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(segments, key = { it.id }) { segment ->
                Text(
                    segmentRowText(segment),
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenSegment(segment.id) },
                            onLongClick = { onRequestDelete(segment.id) },
                        )
                        .padding(vertical = 12.dp),
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 고도, 속도 and 심박 (only with [heart]) under their `titleSmall` titles (V1.8.0 spec §2.2); [onOpenChart] opens the big
 * map on the tab of the chart tapped. One item, so the three are laid out (and found) together.
 */
@Composable
private fun TripCharts(
    profile: ElevationProfile?,
    speed: MetricSeries?,
    heart: MetricSeries?,
    total: SegmentTotals,
    heartDescription: String,
    onOpenChart: ((ChartTab) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChartTitle(ChartText.TITLE_ELEVATION)
        ElevationChart(profile, onOpen = onOpenChart?.let { { it(ChartTab.ELEVATION) } })
        ChartTitle(ChartText.TITLE_SPEED)
        MetricChart(
            speed, MetricKind.SPEED, ChartText.speedDescription(total), onOpen = onOpenChart?.let { { it(ChartTab.SPEED) } },
        )
        if (heart != null) {
            ChartTitle(ChartText.TITLE_HEART)
            MetricChart(
                heart, MetricKind.HEART_RATE, heartDescription, onOpen = onOpenChart?.let { { it(ChartTab.HEART_RATE) } },
            )
        }
    }
}

@Composable
private fun ChartTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TripTotal(total: SegmentTotals, heartRate: TripHeartRate, onRefetchHeartRate: (() -> Unit)?, refetching: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("여행 합계", style = MaterialTheme.typography.titleMedium)
            NumberRow(
                "거리" to RideFormat.distance(total.distanceM),
                "이동 시간" to RideFormat.clock(total.movingTimeMs),
            )
            NumberRow(
                "평균/최고" to "${RideFormat.speedKmh(total.avgSpeedMps)} / ${RideFormat.speedKmh(total.maxSpeedMps)}km/h",
            )
            NumberRow(
                "누적 상승" to RideFormat.altitude(total.ascentM),
                "누적 하강" to RideFormat.altitude(total.descentM),
                "최고 고도" to RideFormat.altitude(total.maxAltitudeM),
            )
            if (heartRate.avgBpm != null || onRefetchHeartRate != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (heartRate.avgBpm != null) {
                        Text(
                            heartRateLine(heartRate.avgBpm, heartRate.maxBpm, heartRate.calories),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (onRefetchHeartRate != null) {
                        OutlinedButton(onClick = onRefetchHeartRate, enabled = !refetching) { Text(HR_REFETCH) }
                    }
                }
            }
        }
    }
}
