package com.cowork.bikerecoder.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The icon of a [SheetChoice], drawn in its tile ([ChoiceSheet]). */
enum class ChoiceIcon {
    /** 오늘로 끝내기, 여행 완료. */
    FINISH,

    /** 내일 이어서 하기, 오늘은 여기까지. */
    TOMORROW,

    /** 여행 이어서, 이어서 기록. */
    CONTINUE,

    /** 새 기록 시작. */
    NEW,

    /** 안내만 끝내기. */
    GUIDANCE_ONLY,

    /** 새 구간으로 나누기. */
    SPLIT,

    /** 기록 끝내기 (while guiding: only the recording ends). */
    STOP_RECORDING,

    /** 버리기 (a short ride, V1.9.2). */
    DISCARD,

    /** 저장 (a short ride, V1.9.2). */
    KEEP,
}

/**
 * One card of a [ChoiceSheet]: [title] with a one-line [subtitle] and an [icon]; [recommended] is the suggested answer
 * (filled with the primary container). [id] names the card's test tag ([ChoiceSheetTags.card]).
 */
data class SheetChoice(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ChoiceIcon,
    val recommended: Boolean = false,
)

/** Test tags of [ChoiceSheet]. */
object ChoiceSheetTags {
    const val TITLE = "choice-sheet-title"
    const val SUMMARY = "choice-sheet-summary"

    /** The bottom text button ([계속 기록하기] or [취소]). */
    const val DISMISS = "choice-sheet-dismiss"

    fun card(id: String) = "choice-$id"
}

/** The card's semantics: true on the recommended card ([SheetChoice.recommended]), for tests of its look. */
val Recommended = SemanticsPropertyKey<Boolean>("Recommended")
var SemanticsPropertyReceiver.recommended by Recommended

/**
 * The bottom sheet that asks how to end or start (V1.9.1): [title], an optional [summary] line (with [summaryLeading]
 * before it, e.g. the activity badge), one card per [choices] (1–3) and a text button [dismissLabel] at the bottom (none when null).
 * A card is one accessible button read as "{title}, {subtitle}" (the recommended one with the state "추천"); [title] is the
 * sheet's heading; tapping it calls [onChoose] (the caller removes the
 * sheet). [onDismiss]: the bottom button, a swipe down or a tap outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(
    title: String,
    choices: List<SheetChoice>,
    onChoose: (SheetChoice) -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit,
    summary: String? = null,
    summaryLeading: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }.testTag(ChoiceSheetTags.TITLE),
            )
            if (summary != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag(ChoiceSheetTags.SUMMARY),
                ) {
                    summaryLeading?.invoke()
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            choices.forEach { choice -> ChoiceCard(choice, onClick = { onChoose(choice) }) }
            if (dismissLabel != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ChoiceSheetTags.DISMISS),
                ) { Text(dismissLabel) }
            } else {
                // No bottom button: keep the last card off the navigation bar.
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** One card: the icon tile, the title and the subtitle; the recommended one is filled and outlined in the primary colour. */
@Composable
internal fun ChoiceCard(choice: SheetChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CARD_CORNER)
    val border = if (choice.recommended) BorderStroke(1.5.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant)
    val spoken = "${choice.title}, ${choice.subtitle}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (choice.recommended) colors.primaryContainer else colors.surface)
            .border(border, shape)
            // One button (clickable merges the texts into it), read as its title and subtitle in one sentence.
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = spoken
                // A screen reader says it: the filled look is no help to it.
                if (choice.recommended) stateDescription = RECOMMENDED_STATE
                recommended = choice.recommended
            }
            .testTag(ChoiceSheetTags.card(choice.id))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(RoundedCornerShape(TILE_CORNER))
                .background(if (choice.recommended) colors.primary else colors.surfaceVariant),
        ) {
            Icon(
                choice.icon.vector(),
                contentDescription = null,
                tint = if (choice.recommended) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                choice.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (choice.recommended) colors.onPrimaryContainer else colors.onSurface,
            )
            Text(
                choice.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ChoiceIcon.vector(): ImageVector = when (this) {
    ChoiceIcon.FINISH -> Icons.Rounded.Flag
    ChoiceIcon.TOMORROW -> Icons.Rounded.Bedtime
    ChoiceIcon.CONTINUE -> Icons.Rounded.Route
    ChoiceIcon.NEW -> Icons.Rounded.Add
    ChoiceIcon.GUIDANCE_ONLY -> Icons.Rounded.Navigation
    ChoiceIcon.SPLIT -> Icons.AutoMirrored.Rounded.CallSplit
    ChoiceIcon.STOP_RECORDING -> Icons.Rounded.Stop
    ChoiceIcon.DISCARD -> Icons.Rounded.DeleteOutline
    ChoiceIcon.KEEP -> Icons.Rounded.Save
}

/** What a screen reader says after the recommended card ([SheetChoice.recommended]). */
private const val RECOMMENDED_STATE = "추천"

private val CARD_CORNER = 16.dp
private val TILE_SIZE = 40.dp
private val TILE_CORNER = 12.dp
