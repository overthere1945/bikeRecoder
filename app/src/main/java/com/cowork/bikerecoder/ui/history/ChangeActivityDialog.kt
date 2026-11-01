package com.cowork.bikerecoder.ui.history

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.export.ExportText
import com.cowork.bikerecoder.ui.activity.ActivityBadge
import com.cowork.bikerecoder.ui.activity.ActivityText

/** Test tags of the segment screen's [⋮] and the change-activity dialog. */
object ChangeActivityTags {
    const val MORE = "segment_more"
    const val ITEM = "change_activity_item"
    const val DIALOG = "change_activity_dialog"
    const val NOTE = "change_activity_note"
    const val CONFIRM = "change_activity_confirm"
    const val CANCEL = "change_activity_cancel"

    /** The radio row of [type]. */
    fun row(type: ActivityType): String = "change_activity_row_${type.name.lowercase()}"
}

/** The segment screen's [⋮] with "활동 종류 바꾸기" (V1.9.0 spec §1.5); a tap closes the menu and calls [onChange]. */
@Composable
internal fun SegmentMoreMenu(onChange: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(ChangeActivityTags.MORE)) {
            Icon(Icons.Filled.MoreVert, contentDescription = ExportText.MORE)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(ActivityText.CHANGE_ACTIVITY) },
                onClick = {
                    open = false
                    onChange()
                },
                modifier = Modifier.testTag(ChangeActivityTags.ITEM),
            )
        }
    }
}

/**
 * "활동 종류 바꾸기" (V1.9.0 spec §1.5): the four activities as radio rows (18 dp badge and name, the [current] one
 * selected first), the [note] that positions not saved during a pause cannot be revived (null: none, as in the GPX import
 * list, where only the type changes), and [취소] / [바꾸기]. [바꾸기] gives the picked activity to [onConfirm] and is off
 * while the pick is the one that is there.
 */
@Composable
internal fun ChangeActivityDialog(
    current: ActivityType,
    onConfirm: (ActivityType) -> Unit,
    onDismiss: () -> Unit,
    note: String? = ActivityText.CHANGE_NOTE,
) {
    var picked by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ChangeActivityTags.DIALOG),
        title = { Text(ActivityText.CHANGE_ACTIVITY) },
        text = {
            Column {
                Column(Modifier.selectableGroup()) {
                    ActivityType.entries.forEach { type ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = picked == type, role = Role.RadioButton, onClick = { picked = type })
                                .testTag(ChangeActivityTags.row(type)),
                        ) {
                            RadioButton(selected = picked == type, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            ActivityBadge(type, BADGE, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(ActivityText.name(type), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (note != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp).testTag(ChangeActivityTags.NOTE),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked) },
                enabled = picked != current,
                modifier = Modifier.testTag(ChangeActivityTags.CONFIRM),
            ) { Text(ActivityText.CHANGE_CONFIRM) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ChangeActivityTags.CANCEL)) {
                Text(ActivityText.CHANGE_CANCEL)
            }
        },
    )
}

/** The toast of a change that could not be saved, shown once ([onShown] clears it). */
@Composable
internal fun ChangeActivityFailure(failed: Boolean, onShown: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(failed) {
        if (failed) {
            Toast.makeText(context, ActivityText.CHANGE_FAILED, Toast.LENGTH_SHORT).show()
            onShown()
        }
    }
}

/** Badge size in the dialog's rows (spec §2.1: 18 dp in chips and compact rows). */
private val BADGE = 18.dp
