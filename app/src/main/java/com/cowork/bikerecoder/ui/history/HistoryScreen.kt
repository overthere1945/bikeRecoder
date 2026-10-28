package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.ui.common.GlyphIcon

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onOpenTrip: (Long) -> Unit, onOpenCumulative: () -> Unit, onBack: () -> Unit) {
    val months by viewModel.months.collectAsStateWithLifecycle()
    val hiddenShortCount by viewModel.hiddenShortCount.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    // Back from a trip whose segments were deleted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    HistoryContent(
        months = months,
        dialog = dialog,
        onOpenTrip = onOpenTrip,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismiss = viewModel::dismiss,
        onBack = onBack,
        onOpenCumulative = onOpenCumulative,
        hiddenShortCount = hiddenShortCount,
        onRequestCleanup = viewModel::requestCleanup,
        onConfirmCleanup = viewModel::confirmCleanup,
    )
}

object HistoryTags {
    fun card(tripId: Long) = "history-card-$tripId"
}

/**
 * [누적 지도] (spec §4), then the trip cards by month under "YYYY년 M월 · {거리} · {n}회", newest first (spec §6.11); a
 * tap opens the trip, a long press asks to delete it. After the last month, when short trips are hidden
 * ([hiddenShortCount] > 0, spec §6.10), "짧은 기록 N개 숨김" with [정리] ([onRequestCleanup]).
 */
@Composable
fun HistoryContent(
    months: List<MonthGroup>,
    dialog: HistoryDialog?,
    onOpenTrip: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onOpenCumulative: () -> Unit = {},
    hiddenShortCount: Int = 0,
    onRequestCleanup: () -> Unit = {},
    onConfirmCleanup: () -> Unit = {},
) {
    HistoryScaffold(title = "기록", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedButton(onClick = onOpenCumulative, modifier = Modifier.padding(horizontal = 16.dp)) { Text("누적 지도") }
            if (months.isEmpty() && hiddenShortCount == 0) {
                EmptyText()
            } else {
                TripCards(months, hiddenShortCount, onOpenTrip, onRequestDelete, onRequestCleanup)
            }
        }
    }
    HistoryDialogs(dialog, CONFIRM_DELETE_TRIP, onConfirmDelete, onDismiss, onConfirmCleanup)
}

@Composable
private fun TripCards(
    months: List<MonthGroup>,
    hiddenShortCount: Int,
    onOpenTrip: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onRequestCleanup: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (month in months) {
            item(key = "month:${month.header}") {
                Text(
                    month.header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(month.cards, key = { it.tripId }) { card ->
                TripCardRow(card, onClick = { onOpenTrip(card.tripId) }, onLongClick = { onRequestDelete(card.tripId) })
            }
        }
        if (months.isEmpty()) {
            item(key = "empty") { Text("기록이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (hiddenShortCount > 0) {
            item(key = "hiddenShort") { HiddenShortRow(hiddenShortCount, onRequestCleanup) }
        }
    }
}

/**
 * A trip's card (spec §6.11), shaped like the cumulative map's ride rows: the route preview, then the date in bold
 * with the badge at the top right, "{거리} · {이동 시간} · {평균}km/h", and only when known "📷 n" / "♥ bpm"; "›" at the end.
 */
@Composable
private fun TripCardRow(card: TripCard, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 48.dp)
            .padding(8.dp)
            .testTag(HistoryTags.card(card.tripId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoutePreview(card.routes)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.date,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    card.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(card.line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.photoCount > 0 || card.avgBpm != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (card.photoCount > 0) {
                        Text("📷 ${card.photoCount}", style = MaterialTheme.typography.bodySmall)
                    }
                    card.avgBpm?.let { Text("♥ $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 8.dp))
    }
}

/** "짧은 기록 N개 숨김" [정리] at the bottom of the list (spec §6.10). */
@Composable
private fun HiddenShortRow(count: Int, onRequestCleanup: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "짧은 기록 ${count}개 숨김",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestCleanup) { Text("정리") }
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

/**
 * "Cannot delete" ([확인]) or "[question]" ([삭제]/[취소]); on the list also "짧은 기록 N개를 삭제할까요?" ([삭제] =
 * [onConfirmCleanup]/[취소]).
 */
@Composable
internal fun HistoryDialogs(
    dialog: HistoryDialog?,
    question: String,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmCleanup: () -> Unit = {},
) {
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
        is HistoryDialog.ConfirmCleanup -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text("짧은 기록 ${dialog.count}개를 삭제할까요?") },
            confirmButton = { TextButton(onClick = onConfirmCleanup) { Text("삭제") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
    }
}
