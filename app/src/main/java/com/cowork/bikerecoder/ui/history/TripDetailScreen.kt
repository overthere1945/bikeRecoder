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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
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
 */
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    tileSource: TileSource,
    thumbnails: PhotoThumbnails,
    onOpenSegment: (Long) -> Unit,
    onBack: () -> Unit,
    photoPrompt: @Composable () -> Unit = {},
    heartRateGate: HeartRateGate? = null,
    mediaEntry: @Composable () -> Unit = {},
    mediaThumbnail: MediaThumbnail = { _, _ -> null },
    onOpenMedia: (TripMedia) -> Unit = {},
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
    val media by viewModel.media.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val customTitle by viewModel.customTitle.collectAsStateWithLifecycle()
    val autoTitle by viewModel.autoTitle.collectAsStateWithLifecycle()
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
 * The trip's title row ([title] with [✎] that calls [onRename], V1.4.0 spec §2.5) above everything else, then the map ([map], every day's track and photos; outside the scrolling part, so it keeps its drags), then
 * [videos] ([영상 만들기] [이미지 만들기] and "만든 영상·이미지", V1.3.0 spec §1.1, §5.1),
 * [photos] (the photo strip and card, spec §2.3), the totals (with the trip's [heartRate], spec §3.3) and its segments
 * under a header per date (with that day's average heart rate); a long press asks to delete.
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
) {
    HistoryScaffold(title = "여행 기록", onBack = onBack) {
        if (segmentsByDate.isEmpty()) {
            EmptyText()
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (title != null && onRename != null) TripTitleRow(title, onRename)
                map?.invoke(Modifier.fillMaxWidth().height(240.dp))
                TripDetailList(
                    segmentsByDate, total, heartRate, onOpenSegment, onRequestDelete, videos, photos, onRefetchHeartRate,
                    refetching, Modifier.weight(1f),
                )
            }
        }
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_SEGMENT, onConfirmDelete, onDismiss)
}

/** The shown title in `titleLarge`, and [✎] on the right: "제목 바꾸기". */
@Composable
private fun TripTitleRow(title: String, onRename: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = RENAME_TITLE) }
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
                "누적 상승" to RideFormat.altitude(total.ascentM),
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
