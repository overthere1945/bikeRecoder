package com.cowork.bikerecoder.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Test tags of the export UI. */
object ExportTags {
    const val MENU = "export_menu"
    const val PROGRESS = "export_progress"
    const val DIALOG = "export_dialog"
    const val RECORDING_NOTE = "export_recording_note"
}

/**
 * The saved dialog (spec §4.2): "내보냈습니다", "Download/bikeRecoder/{파일 이름}" with the name the phone gave the file,
 * and [공유] [파일 열기] [닫기].
 */
@Composable
fun ExportDialog(saved: ExportResult.Saved, onShare: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ExportTags.DIALOG),
        title = { Text(ExportText.SAVED_TITLE) },
        text = { Text(ExportText.savedPath(saved.displayName)) },
        // Three buttons in the dialog's own flow row, in the order of the spec.
        confirmButton = {
            TextButton(onClick = onShare) { Text(ExportText.SHARE) }
            TextButton(onClick = onOpen) { Text(ExportText.OPEN) }
            TextButton(onClick = onDismiss) { Text(ExportText.CLOSE) }
        },
    )
}

/**
 * The [⋮] ("더 보기") with "GPX로 내보내기" and "KML로 내보내기"; a tap closes the menu and calls [onExport]. While
 * [enabled] is false (the trip is being recorded) both items are dimmed and "기록을 마친 뒤 내보낼 수 있습니다" is shown
 * under them. [running]: an export is under way, the button is disabled.
 */
@Composable
fun ExportMenu(onExport: (ExportFormat) -> Unit, enabled: Boolean = true, running: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !running, modifier = Modifier.testTag(ExportTags.MENU)) {
            Icon(Icons.Filled.MoreVert, contentDescription = ExportText.MORE)
        }
        DropdownMenu(expanded = open && !running, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(ExportText.menuItem(format)) },
                    enabled = enabled,
                    onClick = {
                        open = false
                        onExport(format)
                    },
                )
            }
            if (!enabled) {
                Text(
                    ExportText.RECORDING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(ExportTags.RECORDING_NOTE),
                )
            }
        }
    }
}

/** The small progress indicator over the panel while an export is built and saved; fills [modifier]'s box. */
@Composable
fun ExportProgress(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp, shadowElevation = 6.dp) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(32.dp).testTag(ExportTags.PROGRESS))
        }
    }
}

/**
 * What a screen shows for its [state] apart from the progress: the saved dialog (its [공유] and [파일 열기] start the
 * phone's share sheet and viewer), or a Toast for a failure or a recording trip. [onClear] when the outcome has been
 * shown (the dialog closed, the Toast posted).
 */
@Composable
fun ExportOutcome(state: ExportState, onClear: () -> Unit) {
    val context = LocalContext.current
    when (state) {
        is ExportState.Done -> ExportDialog(
            saved = state.saved,
            onShare = { context.startExport(ExportIntents.share(state.saved)) },
            onOpen = { context.startExport(ExportIntents.view(state.saved)) },
            onDismiss = onClear,
        )
        ExportState.Failed -> LaunchedEffect(state) {
            Toast.makeText(context, ExportText.SAVE_FAILED, Toast.LENGTH_SHORT).show()
            onClear()
        }
        ExportState.Recording -> LaunchedEffect(state) {
            Toast.makeText(context, ExportText.RECORDING, Toast.LENGTH_SHORT).show()
            onClear()
        }
        ExportState.Idle, ExportState.Running -> Unit
    }
}

/** The intents of the saved dialog's buttons; only built here, launching them is [startExport]'s job. */
object ExportIntents {

    /** [공유]: the phone's share sheet with the file (read permission granted to the target). */
    fun share(saved: ExportResult.Saved): Intent {
        val uri = saved.uri.toUri()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = saved.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }

    /** [파일 열기]: an app that shows the file's type. */
    fun view(saved: ExportResult.Saved): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri.toUri(), saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Starts [intent]; without an app for it, the Toast "열 수 있는 앱이 없습니다". */
private fun Context.startExport(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, ExportText.NO_APP, Toast.LENGTH_SHORT).show()
    }
}
