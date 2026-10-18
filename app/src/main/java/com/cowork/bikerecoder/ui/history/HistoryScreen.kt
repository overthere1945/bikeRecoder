package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.recording.TripRideSummary
import com.cowork.bikerecoder.ui.common.GlyphIcon

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenTrip: (Long) -> Unit, onOpenCumulative: () -> Unit, onBack: () -> Unit) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    // Back from a trip whose segments were deleted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    HistoryContent(
        trips = trips,
        dialog = dialog,
        onOpenTrip = onOpenTrip,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismiss = viewModel::dismiss,
        onBack = onBack,
        onOpenCumulative = onOpenCumulative,
    )
}

/** [누적 지도] (spec §4), then the trip cards, newest first (spec §3.5); a long press asks to delete the trip. */
@Composable
fun HistoryContent(
    trips: List<TripRideSummary>,
    dialog: HistoryDialog?,
    onOpenTrip: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onOpenCumulative: () -> Unit = {},
) {
    HistoryScaffold(title = "기록", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedButton(onClick = onOpenCumulative, modifier = Modifier.padding(horizontal = 16.dp)) { Text("누적 지도") }
            if (trips.isEmpty()) {
                EmptyText()
            } else {
                TripCards(trips, onOpenTrip, onRequestDelete)
            }
        }
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_TRIP, onConfirmDelete, onDismiss)
}

@Composable
private fun TripCards(trips: List<TripRideSummary>, onOpenTrip: (Long) -> Unit, onRequestDelete: (Long) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(trips, key = { it.trip.id }) { summary ->
            Card(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpenTrip(summary.trip.id) },
                    onLongClick = { onRequestDelete(summary.trip.id) },
                ),
            ) {
                Text(tripCardText(summary), modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** The screen frame of the history screens: [←] and [title] above [content]. */
@Composable
internal fun HistoryScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
internal fun EmptyText() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("기록이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Cannot delete" ([확인]) or "[question]" ([삭제]/[취소]). */
@Composable
internal fun HistoryDialogs(dialog: HistoryDialog?, question: String, onConfirmDelete: () -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        is HistoryDialog.Message -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(dialog.text) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        )
        is HistoryDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(question) },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("삭제") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
    }
}
