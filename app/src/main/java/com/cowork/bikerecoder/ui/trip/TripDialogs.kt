package com.cowork.bikerecoder.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.trip.TripType

/** The dialogs of [TripStartViewModel]'s flow, shown over the plan screen. */
@Composable
fun TripStartDialogs(
    state: TripStartState,
    onChooseType: (TripType) -> Unit,
    onContinue: () -> Unit,
    onStartNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        TripStartState.AskType -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(TRIP_TYPE_QUESTION) },
            // [당일] is the default answer, so it is the primary button.
            confirmButton = { Button(onClick = { onChooseType(TripType.SINGLE_DAY) }) { Text("당일") } },
            dismissButton = { TextButton(onClick = { onChooseType(TripType.MULTI_DAY) }) { Text("여러 날") } },
        )
        is TripStartState.AskContinue -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(continueQuestion(state.destinationName)) },
            confirmButton = { Button(onClick = onContinue) { Text("이어서") } },
            dismissButton = { TextButton(onClick = onStartNew) { Text("새로 시작") } },
        )
        is TripStartState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        )
        TripStartState.Idle, TripStartState.Working, is TripStartState.Ready -> Unit
    }
}

/** [종료] on the guidance screen: "안내를 끝낼까요?" with the trip type's choices (see [endChoices]). */
@Composable
fun EndNavigationDialog(type: TripType, onChoose: (EndAction) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(END_QUESTION) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                endChoices(type).forEach { choice ->
                    Button(onClick = { onChoose(choice.action) }, modifier = Modifier.fillMaxWidth()) { Text(choice.label) }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("취소") }
            }
        },
    )
}
