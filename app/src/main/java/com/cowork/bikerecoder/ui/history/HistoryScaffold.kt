package com.cowork.bikerecoder.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cowork.bikerecoder.ui.common.GlyphIcon

/** The screen frame of the trip, segment and cumulative-map screens: [←] and [title] above [content]. */
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
