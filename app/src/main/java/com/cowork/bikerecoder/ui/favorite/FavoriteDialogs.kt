package com.cowork.bikerecoder.ui.favorite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoriteRepository

/** The dialog [FavoritesViewModel.dialog] asks for, if any. */
@Composable
fun FavoriteDialogs(
    dialog: FavoriteDialog?,
    onSave: (FavoriteKind, String?) -> Unit,
    onConfirmReplace: () -> Unit,
    onRename: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is FavoriteDialog.ChooseName -> ChooseNameDialog(dialog, onSave, onDismiss)
        is FavoriteDialog.ConfirmReplace -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(dialog.question) },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("바꾸기") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
        is FavoriteDialog.Rename -> RenameDialog(dialog, onRename, onDismiss)
        is FavoriteDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(FavoriteRepository.DELETE_QUESTION) },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("삭제") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
    }
}

@Composable
private fun ChooseNameDialog(
    dialog: FavoriteDialog.ChooseName,
    onSave: (FavoriteKind, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(dialog.place) { mutableStateOf(dialog.place.name.take(FavoriteRepository.MAX_NAME_LENGTH)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("즐겨찾기에 저장") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onSave(FavoriteKind.HOME, null) }, modifier = Modifier.weight(1f)) { Text("집") }
                    OutlinedButton(onClick = { onSave(FavoriteKind.WORK, null) }, modifier = Modifier.weight(1f)) { Text("회사") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(FavoriteRepository.MAX_NAME_LENGTH) },
                    label = { Text("직접 입력") },
                    singleLine = true,
                    isError = dialog.error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(dialog.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(FavoriteKind.CUSTOM, name) }, enabled = name.isNotBlank()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RenameDialog(dialog: FavoriteDialog.Rename, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(dialog.favorite.id) { mutableStateOf(dialog.favorite.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 바꾸기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(FavoriteRepository.MAX_NAME_LENGTH) },
                    label = { Text("이름") },
                    singleLine = true,
                    isError = dialog.error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(dialog.error)
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
