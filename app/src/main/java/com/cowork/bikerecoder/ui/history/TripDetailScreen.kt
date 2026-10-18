package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.cowork.bikerecoder.ui.record.NumberRow
import java.time.LocalDate

@Composable
fun TripDetailScreen(viewModel: TripDetailViewModel, onOpenSegment: (Long) -> Unit, onBack: () -> Unit) {
    val segmentsByDate by viewModel.segmentsByDate.collectAsStateWithLifecycle()
    val total by viewModel.total.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
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
    )
}

/** The trip's totals on top, then its segments under a header per date (spec §3.5); a long press asks to delete. */
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
) {
    HistoryScaffold(title = "여행 기록", onBack = onBack) {
        if (segmentsByDate.isEmpty()) {
            EmptyText()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
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
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_SEGMENT, onConfirmDelete, onDismiss)
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
