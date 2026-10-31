package com.cowork.bikerecoder.ui.history

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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.cowork.bikerecoder.core.video.RideTitle

/**
 * "제목 바꾸기" (V1.4.0 spec §2.5). [current]: the trip's own title (the field starts with it; null = empty);
 * [placeholder]: the automatic title, shown dimmed while the field is empty. The field takes [RideTitle.MAX_CUSTOM_CODE_POINTS]
 * code points and no more: typing past it is ignored, a longer paste is cut after the 30th code point (never inside
 * a surrogate pair). [onSave] gets the text as typed (the caller trims it; empty clears the title); [onDismiss] is [취소].
 */
@Composable
fun RenameTripDialog(
    current: String?,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf(current.orEmpty().let { TextFieldValue(RideTitle.truncateCodePoints(it, RideTitle.MAX_CUSTOM_CODE_POINTS), TextRange(it.length)) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(RENAME_TITLE) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { next -> field = limited(field, next) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(field.text) }) { Text(RENAME_SAVE) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(RENAME_CANCEL) } },
    )
}

/**
 * [next] if it fits in 30 code points. Typing at the limit changes nothing ([old] stays); a paste that goes over is cut
 * after the 30th code point.
 */
internal fun limited(old: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val max = RideTitle.MAX_CUSTOM_CODE_POINTS
    if (next.text.codePointCount(0, next.text.length) <= max) return next
    if (old.text.codePointCount(0, old.text.length) >= max) return old
    val cut = RideTitle.truncateCodePoints(next.text, max)
    return TextFieldValue(cut, TextRange(minOf(next.selection.end, cut.length)))
}

const val RENAME_TITLE = "제목 바꾸기"
const val RENAME_SAVE = "저장"
const val RENAME_CANCEL = "취소"
