package com.cowork.bikerecoder.ui.favorite

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoriteRepository

object FavoriteDialogTags {
    /** The name field of "즐겨찾기 이름". */
    const val NAME = "favorite-name"

    /** The "추천 이름" chip row. */
    const val SUGGESTIONS = "favorite-name-suggestions"

    fun suggestion(index: Int) = "favorite-name-suggestion-$index"
}

/** The dialog [FavoritesViewModel.dialog] asks for, if any. */
@Composable
fun FavoriteDialogs(
    dialog: FavoriteDialog?,
    onSave: (FavoriteKind, String?) -> Unit,
    onConfirmReplace: () -> Unit,
    onRename: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit,
    onSaveCustom: (String) -> Unit,
    /** [직접 입력] in "즐겨찾기에 저장". */
    onStartCustom: () -> Unit,
    /** [저장] in the current-location question. */
    onConfirmCurrent: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is FavoriteDialog.ChooseName -> ChooseNameDialog(dialog, onSave, onStartCustom, onDismiss)
        is FavoriteDialog.ConfirmCurrent -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.question) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dialog.place.name)
                    WarningText(dialog.accuracyWarning)
                }
            },
            confirmButton = { TextButton(onClick = onConfirmCurrent) { Text("저장") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
        is FavoriteDialog.ConfirmReplace -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(dialog.question) },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("바꾸기") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
        is FavoriteDialog.Message -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(dialog.text) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        )
        is FavoriteDialog.Rename -> RenameDialog(dialog, onRename, onDismiss)
        is FavoriteDialog.NameFavorite -> NameFavoriteDialog(dialog, onSaveCustom, onDismiss)
        is FavoriteDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(FavoriteRepository.DELETE_QUESTION) },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("삭제") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        )
    }
}

/** "즐겨찾기에 저장": [집] [회사] [직접 입력] (the name dialog, spec §8.4), [취소]. */
@Composable
private fun ChooseNameDialog(
    dialog: FavoriteDialog.ChooseName,
    onSave: (FavoriteKind, String?) -> Unit,
    onStartCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("즐겨찾기에 저장") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onSave(FavoriteKind.HOME, null) }, modifier = Modifier.weight(1f)) { Text("집") }
                    OutlinedButton(onClick = { onSave(FavoriteKind.WORK, null) }, modifier = Modifier.weight(1f)) { Text("회사") }
                }
                OutlinedButton(onClick = onStartCustom, modifier = Modifier.fillMaxWidth()) { Text("직접 입력") }
                ErrorText(dialog.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("취소") } },
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

/**
 * "즐겨찾기 이름" (spec §8.4): the name field, the "추천 이름" chips (a tap puts that name in the field), the refusal,
 * [저장] [취소]. The field follows [FavoriteDialog.NameFavorite.initialName] until the rider edits it (it may arrive
 * with the suggestions).
 */
@Composable
private fun NameFavoriteDialog(dialog: FavoriteDialog.NameFavorite, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(dialog.place, dialog.register) { mutableStateOf(dialog.initialName) }
    var edited by rememberSaveable(dialog.place, dialog.register) { mutableStateOf(false) }
    LaunchedEffect(dialog.initialName) { if (!edited) name = dialog.initialName }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("즐겨찾기 이름") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WarningText(dialog.accuracyWarning)
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(FavoriteRepository.MAX_NAME_LENGTH)
                        edited = true
                    },
                    label = { Text("이름") },
                    singleLine = true,
                    isError = dialog.error != null,
                    modifier = Modifier.fillMaxWidth().testTag(FavoriteDialogTags.NAME),
                )
                ErrorText(dialog.error)
                if (dialog.loadingSuggestions || dialog.suggestions.isNotEmpty()) {
                    Text("추천 이름", style = MaterialTheme.typography.labelLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag(FavoriteDialogTags.SUGGESTIONS),
                    ) {
                        dialog.suggestions.forEachIndexed { index, suggestion ->
                            SuggestionChip(
                                onClick = {
                                    name = suggestion.take(FavoriteRepository.MAX_NAME_LENGTH)
                                    edited = true
                                },
                                label = { Text(suggestion, maxLines = 1) },
                                modifier = Modifier.testTag(FavoriteDialogTags.suggestion(index)),
                            )
                        }
                        if (dialog.loadingSuggestions) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** A caution that does not block saving (e.g. the location accuracy): not in the error colour. */
@Composable
private fun WarningText(warning: String?) {
    if (warning != null) Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
