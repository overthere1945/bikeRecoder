package com.cowork.bikerecoder.ui.trip

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ui.common.ChoiceSheet

/**
 * The questions of [TripStartViewModel]'s flow, shown over the plan screen and the map main screen: the continue sheet
 * ("어떻게 기록할까요?" [여행 이어서 · N일째] [새 기록 시작]), the recording sheet ("기록을 어떻게 할까요?" [이어서 기록]
 * [새 구간으로 나누기]) and the start error.
 */
@Composable
fun TripStartDialogs(
    state: TripStartState,
    onContinue: () -> Unit,
    onStartNew: () -> Unit,
    onDismiss: () -> Unit,
    onKeepRecording: () -> Unit = {},
    onSplitRecording: () -> Unit = {},
) {
    when (state) {
        is TripStartState.AskContinue -> {
            val continueCard = TripCards.continueTrip(state)
            ChoiceSheet(
                title = TripChoiceText.CONTINUE_QUESTION,
                choices = listOf(continueCard, TripCards.startNew()),
                onChoose = { if (it == continueCard) onContinue() else onStartNew() },
                dismissLabel = TripChoiceText.CANCEL,
                onDismiss = onDismiss,
            )
        }
        TripStartState.AskRecording -> {
            val keep = TripCards.keepRecording()
            ChoiceSheet(
                title = RECORDING_QUESTION,
                choices = listOf(keep, TripCards.splitRecording()),
                onChoose = { if (it == keep) onKeepRecording() else onSplitRecording() },
                dismissLabel = TripChoiceText.CANCEL,
                onDismiss = onDismiss,
            )
        }
        is TripStartState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } },
        )
        TripStartState.Idle, TripStartState.Working, is TripStartState.Ready, TripStartState.ShowSummary -> Unit
    }
}

/**
 * [종료] on the guidance screen: "안내를 끝낼까요?" with the trip type's choices, which depend on [recording] and, for a
 * multi-day trip, its [day] ([endChoices]); [취소] keeps guiding.
 */
@Composable
fun EndNavigationSheet(
    type: TripType,
    recording: Boolean,
    onChoose: (EndAction) -> Unit,
    onDismiss: () -> Unit,
    day: Int = 1,
) {
    val choices = endChoices(type, recording, day)
    ChoiceSheet(
        title = END_QUESTION,
        choices = choices.map { it.card },
        onChoose = { card -> choices.first { it.card == card }.action.let(onChoose) },
        dismissLabel = TripChoiceText.CANCEL,
        onDismiss = onDismiss,
    )
}
