package com.cowork.bikerecoder.ui.navigate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The buttons under the guidance numbers (spec §3). */
enum class GuidanceButton(val label: String) {
    MUTE("음소거"),
    OVERVIEW("전체 경로"),
    END("종료"),
    CANCEL_GUIDANCE("안내 취소"),
    END_RIDE("주행 종료"),
}

/** 기록 중이 아니면 V1.0.0 그대로 [MUTE, OVERVIEW, END]; 기록 중이면 [MUTE, CANCEL_GUIDANCE, END_RIDE]. */
fun guidanceButtons(recording: Boolean): List<GuidanceButton> =
    if (recording) {
        listOf(GuidanceButton.MUTE, GuidanceButton.CANCEL_GUIDANCE, GuidanceButton.END_RIDE)
    } else {
        listOf(GuidanceButton.MUTE, GuidanceButton.OVERVIEW, GuidanceButton.END)
    }

/** Test tags of the guidance buttons. */
object GuidanceTags {
    /** [전체 경로] on the map while recording (its place in the row is taken by [안내 취소]). */
    const val OVERVIEW_ON_MAP = "navigate-overview-map"

    fun button(b: GuidanceButton) = "navigate-button-${b.name}"
}

/**
 * The row of [guidanceButtons]. MUTE는 muted면 "음소거 해제". END와 END_RIDE는 [onEnd](종료 대화상자),
 * CANCEL_GUIDANCE는 [onCancelGuidance](확인 없음).
 */
@Composable
internal fun GuidanceButtonRow(
    recording: Boolean,
    muted: Boolean,
    onMute: (Boolean) -> Unit,
    onOverview: () -> Unit,
    onEnd: () -> Unit,
    onCancelGuidance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        guidanceButtons(recording).forEach { button ->
            val buttonModifier = Modifier.weight(1f).testTag(GuidanceTags.button(button))
            when (button) {
                GuidanceButton.MUTE -> OutlinedButton(onClick = { onMute(!muted) }, modifier = buttonModifier) {
                    Text(if (muted) "음소거 해제" else button.label, textAlign = TextAlign.Center)
                }
                GuidanceButton.OVERVIEW -> OutlinedButton(onClick = onOverview, modifier = buttonModifier) {
                    Text(button.label, textAlign = TextAlign.Center)
                }
                GuidanceButton.CANCEL_GUIDANCE -> OutlinedButton(onClick = onCancelGuidance, modifier = buttonModifier) {
                    // One line, like the other two buttons; the recording goes on (the label says only what ends).
                    Text(button.label, textAlign = TextAlign.Center, maxLines = 1)
                }
                GuidanceButton.END, GuidanceButton.END_RIDE -> Button(onClick = onEnd, modifier = buttonModifier) {
                    Text(button.label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
