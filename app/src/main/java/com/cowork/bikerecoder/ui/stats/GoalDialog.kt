package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.stats.GoalInput
import com.cowork.bikerecoder.core.stats.Goals
import com.cowork.bikerecoder.ui.feed.groupedInt

const val GOAL_DIALOG_TITLE = "목표"
const val GOAL_WEEKLY_LABEL = "주간 목표 (km)"
const val GOAL_YEARLY_LABEL = "연간 목표 (km)"
const val GOAL_ERROR = "1~100,000 사이 숫자를 넣어 주세요"
const val GOAL_SAVE = "저장"
const val GOAL_CANCEL = "취소"

object GoalDialogTags {
    const val WEEKLY = "goal-weekly"
    const val YEARLY = "goal-yearly"
    const val SAVE = "goal-save"
    const val CANCEL = "goal-cancel"

    fun weeklyChip(km: Int) = "goal-weekly-chip-$km"
    fun yearlyChip(km: Int) = "goal-yearly-chip-$km"
}

private val WEEKLY_SUGGESTIONS = listOf(50, 100, 150)
private val YEARLY_SUGGESTIONS = listOf(1_000, 2_000, 3_000)

/**
 * The goal window (V1.4.0 spec §3.1): two fields, the weekly and the yearly kilometres, each with three suggestion chips
 * that fill it. [weeklyKm] and [yearlyKm] start the fields (null = empty). A field takes whole numbers from
 * [Goals.MIN_KM] to [Goals.MAX_KM] (a thousands comma is fine); empty means no such goal. A field that does not fit shows
 * [GOAL_ERROR] and blocks [GOAL_SAVE]. [onSave] gets what the fields mean ([GoalInput.Empty] or [GoalInput.Valid]); [onDismiss] is [취소].
 */
@Composable
fun GoalDialog(
    weeklyKm: Int?,
    yearlyKm: Int?,
    onSave: (GoalInput, GoalInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var weekly by remember { mutableStateOf(weeklyKm?.toString().orEmpty()) }
    var yearly by remember { mutableStateOf(yearlyKm?.toString().orEmpty()) }
    val weeklyInput = Goals.parse(weekly)
    val yearlyInput = Goals.parse(yearly)
    val valid = weeklyInput !is GoalInput.Invalid && yearlyInput !is GoalInput.Invalid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(GOAL_DIALOG_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                GoalField(GOAL_WEEKLY_LABEL, weekly, GoalDialogTags.WEEKLY, weeklyInput, WEEKLY_SUGGESTIONS, GoalDialogTags::weeklyChip) {
                    weekly = it
                }
                GoalField(GOAL_YEARLY_LABEL, yearly, GoalDialogTags.YEARLY, yearlyInput, YEARLY_SUGGESTIONS, GoalDialogTags::yearlyChip) {
                    yearly = it
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(weeklyInput, yearlyInput) }, enabled = valid, modifier = Modifier.testTag(GoalDialogTags.SAVE)) {
                Text(GOAL_SAVE)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(GoalDialogTags.CANCEL)) { Text(GOAL_CANCEL) }
        },
    )
}

@Composable
private fun GoalField(
    label: String,
    text: String,
    tag: String,
    input: GoalInput,
    suggestions: List<Int>,
    chipTag: (Int) -> String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = input is GoalInput.Invalid,
            supportingText = if (input is GoalInput.Invalid) ({ Text(GOAL_ERROR) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (km in suggestions) {
                SuggestionChip(
                    onClick = { onChange(km.toString()) },
                    label = { Text(groupedInt(km)) },
                    modifier = Modifier.testTag(chipTag(km)),
                )
            }
        }
    }
}
