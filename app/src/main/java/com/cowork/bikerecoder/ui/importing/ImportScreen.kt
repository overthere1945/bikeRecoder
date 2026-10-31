package com.cowork.bikerecoder.ui.importing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.importing.IMPORT_CLOSE
import com.cowork.bikerecoder.importing.IMPORT_NOTHING_FOUND
import com.cowork.bikerecoder.importing.IMPORT_PICK_OTHER
import com.cowork.bikerecoder.importing.IMPORT_SCREEN_TITLE
import com.cowork.bikerecoder.importing.IMPORT_SELECT_ALL
import com.cowork.bikerecoder.importing.IMPORT_STOP
import com.cowork.bikerecoder.importing.IMPORT_VIEW_SUMMARY
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.importing.RideKey
import com.cowork.bikerecoder.importing.ScanRow
import com.cowork.bikerecoder.importing.importButtonText
import com.cowork.bikerecoder.importing.importingText
import com.cowork.bikerecoder.importing.readingText
import com.cowork.bikerecoder.importing.resultText
import com.cowork.bikerecoder.importing.rowDateText
import com.cowork.bikerecoder.importing.statusLine
import com.cowork.bikerecoder.ui.common.GlyphIcon
import com.cowork.bikerecoder.ui.history.RouteDrawing

/** Test tags of the GPX import: its screen and its rows in the settings. */
object ImportTags {
    const val SELECT_ALL = "import_select_all"
    const val IMPORT = "import_button"
    const val PROGRESS = "import_progress"
    const val STOP = "import_stop"
    const val VIEW_SUMMARY = "import_view_summary"
    const val CLOSE = "import_close"
    const val PICK_AGAIN = "import_pick_again"

    /** The settings row "지난 기록 가져오기 (GPX)". */
    const val SETTINGS_ROW = "settings_import"

    /** The settings row "가져온 기록 N개" and its [모두 지우기]. */
    const val SETTINGS_IMPORTED = "settings_imported"
    const val SETTINGS_DELETE_ALL = "settings_imported_delete_all"

    fun row(key: RideKey) = "import_row_${key.sourceIndex}_${key.itemIndex}"
}

private val PREVIEW_SIZE = 44.dp
private const val DIMMED = 0.38f

/**
 * The "기록 가져오기" screen (V1.6.0 spec §1.2, §1.3) of [viewModel]. Back while reading stops the reading; back while
 * importing leaves the import running. [onOpenSummary]: [요약 보기], the summary tab. [onPickAgain]: [다른 파일 고르기],
 * the system file picker whose files go to [ImportViewModel.scan].
 */
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onOpenSummary: () -> Unit,
    onPickAgain: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back = {
        viewModel.close()
        onBack()
    }
    BackHandler(onBack = back)
    ImportContent(
        state = state,
        onBack = back,
        onToggle = viewModel::toggle,
        onToggleAll = viewModel::toggleAll,
        onImport = viewModel::importChecked,
        onStop = viewModel::stop,
        onOpenSummary = {
            viewModel.close()
            onOpenSummary()
        },
        onClose = back,
        onPickAgain = onPickAgain,
    )
}

@Composable
internal fun ImportContent(
    state: ImportUiState,
    onBack: () -> Unit,
    onToggle: (RideKey) -> Unit,
    onToggleAll: () -> Unit,
    onImport: () -> Unit,
    onStop: () -> Unit,
    onOpenSummary: () -> Unit,
    onClose: () -> Unit,
    onPickAgain: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { GlyphIcon("←", "뒤로") }
                Text(IMPORT_SCREEN_TITLE, style = MaterialTheme.typography.titleLarge)
            }
            when (state) {
                is ImportUiState.Reading -> Progress(readingText(state.done, state.total), state.done, state.total)
                is ImportUiState.Review -> Review(state, onToggle, onToggleAll, onImport)
                ImportUiState.Empty -> Centered {
                    Text(IMPORT_NOTHING_FOUND, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = onPickAgain, modifier = Modifier.testTag(ImportTags.PICK_AGAIN)) {
                        Text(IMPORT_PICK_OTHER)
                    }
                }
                is ImportUiState.Job -> when (val job = state.state) {
                    is ImportJobState.Importing -> Progress(importingText(job.done, job.total), job.done, job.total) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.testTag(ImportTags.STOP)) { Text(IMPORT_STOP) }
                    }
                    is ImportJobState.Finished -> Centered {
                        Text(resultText(job), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onClose, modifier = Modifier.testTag(ImportTags.CLOSE)) {
                                Text(IMPORT_CLOSE)
                            }
                            Button(onClick = onOpenSummary, modifier = Modifier.testTag(ImportTags.VIEW_SUMMARY)) {
                                Text(IMPORT_VIEW_SUMMARY)
                            }
                        }
                    }
                    // No run (it was seen elsewhere): only the title and back.
                    ImportJobState.Idle -> Unit
                }
            }
        }
    }
}

/** [text] over a progress bar of [done] / [total], then [action]. */
@Composable
private fun Progress(text: String, done: Int, total: Int, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().testTag(ImportTags.PROGRESS),
        )
        action()
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Review(
    state: ImportUiState.Review,
    onToggle: (RideKey) -> Unit,
    onToggleAll: () -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.notices.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(state.header, style = MaterialTheme.typography.titleMedium)
            Text(
                state.files,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val selectable = state.selectable
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ImportTags.SELECT_ALL)
                .toggleable(
                    value = state.allChecked,
                    enabled = selectable.isNotEmpty(),
                    role = Role.Checkbox,
                    onValueChange = { onToggleAll() },
                )
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.allChecked, onCheckedChange = null, enabled = selectable.isNotEmpty(), modifier = Modifier.padding(12.dp))
            Text(IMPORT_SELECT_ALL, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows, key = { "${it.key.sourceIndex}/${it.key.itemIndex}" }) { row ->
                ReviewRow(row, checked = row.key in state.checked, selectable = row.key in selectable, onToggle = { onToggle(row.key) })
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val count = state.checked.size
            Button(
                onClick = onImport,
                enabled = count > 0,
                modifier = Modifier.fillMaxWidth().testTag(ImportTags.IMPORT),
            ) { Text(importButtonText(count)) }
        }
    }
}

/**
 * One ride (spec §1.2): checkbox, 44 dp route drawing, the start "2023년 5월 14일 (일) 오전 9:12" (the file name when it
 * cannot be read) and the status line. Rows that cannot be picked are dimmed; an unreadable one's reason is in the error
 * colour.
 */
@Composable
private fun ReviewRow(row: ScanRow, checked: Boolean, selectable: Boolean, onToggle: () -> Unit) {
    val unreadable = row.status is RideStatus.Unreadable
    val dim = if (selectable) Modifier else Modifier.alpha(DIMMED)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(ImportTags.row(row.key))
            .toggleable(value = checked, enabled = selectable, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = selectable, modifier = Modifier.padding(12.dp))
        RouteDrawing(
            listOf(row.preview to RideColors.colorAt(0)),
            Modifier.size(PREVIEW_SIZE).then(dim),
            background = MaterialTheme.colorScheme.surfaceVariant,
            padding = 4.dp,
            lineWidth = 2.dp,
            corner = 6.dp,
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                row.startedAt?.let { rowDateText(it) } ?: row.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = dim,
            )
            Text(
                statusLine(row),
                style = MaterialTheme.typography.bodyMedium,
                color = if (unreadable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (unreadable) Modifier else dim,
            )
        }
    }
}
