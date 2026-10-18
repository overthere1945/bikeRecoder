package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.map.TileSource
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.ui.photo.PhotoStrip
import com.cowork.bikerecoder.ui.photo.PhotoViewerHost
import com.cowork.bikerecoder.ui.photo.rememberMapPhotos
import com.cowork.bikerecoder.ui.photo.rememberPhotoViewerState
import com.cowork.bikerecoder.ui.record.NumberRow
import java.time.LocalDate

/**
 * [photoPrompt]: the photo permission card where it applies (spec §2.4), shown under the photo strip.
 * [map]: the map for its content (replaceable in tests).
 */
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    tileSource: TileSource,
    thumbnails: PhotoThumbnails,
    onOpenSegment: (Long) -> Unit,
    onBack: () -> Unit,
    photoPrompt: @Composable () -> Unit = {},
    map: @Composable (DetailMapContent, Modifier) -> Unit = { content, modifier -> DetailMap(content, tileSource, modifier) },
) {
    val segmentsByDate by viewModel.segmentsByDate.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val viewer = rememberPhotoViewerState()
    val mapPhotos = rememberMapPhotos(photos, thumbnails)
    // A recording of this trip may have gone on meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    TripDetailContent(
        segmentsByDate = segmentsByDate,
        total = total,
        dialog = dialog,
        onOpenSegment = onOpenSegment,
        onRequestDelete = viewModel::requestDeleteSegment,
        onConfirmDelete = viewModel::confirmDelete,
        onDismiss = viewModel::dismiss,
        onBack = onBack,
        map = { modifier ->
            map(DetailMapContent(track, mapPhotos) { photoId -> viewer.openPhoto(photos.visible, photoId) }, modifier)
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
        },
    )
    PhotoViewerHost(viewer, photos, thumbnails, onToggleHidden = { viewModel.setHidden(it, hidden = !it.hidden) })
}

/**
 * The trip's map ([map], every day's track and photos; outside the scrolling part, so it keeps its drags), then
 * [photos] (the photo strip and card, spec §2.3), the totals and its segments under a header per date (spec §3.5);
 * a long press asks to delete.
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
) {
    HistoryScaffold(title = "여행 기록", onBack = onBack) {
        if (segmentsByDate.isEmpty()) {
            EmptyText()
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                map?.invoke(Modifier.fillMaxWidth().height(240.dp))
                TripDetailList(segmentsByDate, total, onOpenSegment, onRequestDelete, photos, Modifier.weight(1f))
            }
        }
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_SEGMENT, onConfirmDelete, onDismiss)
}

@Composable
private fun TripDetailList(
    segmentsByDate: Map<LocalDate, List<RideSegment>>,
    total: SegmentTotals,
    onOpenSegment: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    photos: @Composable () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item(key = "photos") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { photos() }
        }
        item(key = "total") { TripTotal(total) }
        segmentsByDate.forEach { (date, segments) ->
            item(key = "date-$date") {
                Text(
                    RideFormat.date(date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
private fun TripTotal(total: SegmentTotals) {
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
        }
    }
}
