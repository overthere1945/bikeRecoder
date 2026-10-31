package com.cowork.bikerecoder.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cowork.bikerecoder.share.MAX_HASHTAGS_INPUT
import com.cowork.bikerecoder.share.normalizeHashtags

/**
 * "기본 해시태그" (spec §3.5). The one-line field starts with [current] (the value in effect) and takes at most
 * [MAX_HASHTAGS_INPUT] characters. [onSave] gets [normalizeHashtags] of the input (an empty result means "none");
 * [onDismiss] is [취소].
 */
@Composable
fun ShareHashtagsDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.take(MAX_HASHTAGS_INPUT)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기본 해시태그") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_HASHTAGS_INPUT) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(SettingsTags.SHARE_HASHTAGS_INPUT),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(normalizeHashtags(text)) }, modifier = Modifier.testTag(SettingsTags.CONFIRM)) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTags.CANCEL)) { Text("취소") }
        },
    )
}
