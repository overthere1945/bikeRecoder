package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.ui.feed.GOAL_REACHED
import com.cowork.bikerecoder.ui.feed.GOAL_REACHED_TEXT
import com.cowork.bikerecoder.ui.feed.GoalRing
import com.cowork.bikerecoder.ui.feed.goalProgressText

const val GOAL_EMPTY_HINT = "목표를 정하면 진행 상황을 보여 드립니다"
const val GOAL_SET = "목표 정하기"
const val GOAL_REACHED_WORD = "달성"
const val WEEKLY_GOAL_TITLE = "주간 목표"
const val YEARLY_GOAL_TITLE = "연간 목표"

/**
 * The goal card on top of the stats tab (V1.4.0 spec §3.1). The weekly goal is the ring of the summary tab's strip, the
 * yearly one a bar with "412 / 1,000 km" and, once the year is a week old, "지금 속도면 연말에 약 N km". Both stop at 100 %
 * and read "달성" when reached. Without a goal: [GOAL_EMPTY_HINT] and [GOAL_SET]. A tap on the card (or the button)
 * calls [onEdit].
 */
@Composable
fun GoalCard(
    weekly: GoalProgress?,
    yearly: GoalProgress?,
    projectionText: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    openTip: String? = null,
    onToggleTip: (String) -> Unit = {},
) {
    // One "?" for the card: by the hint without a goal, else by the title of the first goal.
    val tip: @Composable () -> Unit = {
        HelpTip(StatsHelp.GOAL_TOPIC, StatsHelp.GOAL, openTip == StatsHelp.GOAL_TOPIC, onToggle = { onToggleTip(StatsHelp.GOAL_TOPIC) })
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(StatsTags.GOAL_CARD),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.clickable(role = Role.Button, onClick = onEdit).padding(16.dp),
        ) {
            if (weekly == null && yearly == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(GOAL_EMPTY_HINT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    tip()
                }
                Button(onClick = onEdit, modifier = Modifier.testTag(StatsTags.GOAL_SET)) { Text(GOAL_SET) }
            }
            if (weekly != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        WEEKLY_GOAL_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    tip()
                    GoalRing(weekly.distanceM, weekly.goalKm, tag = StatsTags.WEEKLY_RING)
                }
            }
            if (yearly != null) YearlyGoal(yearly, projectionText, tip.takeIf { weekly == null })
        }
    }
}

@Composable
private fun YearlyGoal(yearly: GoalProgress, projectionText: String?, tip: (@Composable () -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                YEARLY_GOAL_TITLE,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            tip?.invoke()
            Text(
                goalProgressText(yearly.distanceM, yearly.goalKm),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(StatsTags.YEARLY_TEXT),
            )
            if (yearly.reached) {
                Text(
                    " $GOAL_REACHED_WORD",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GOAL_REACHED_TEXT,
                    modifier = Modifier.testTag(StatsTags.YEARLY_REACHED),
                )
            }
        }
        LinearProgressIndicator(
            progress = { yearly.fraction },
            color = if (yearly.reached) GOAL_REACHED else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).testTag(StatsTags.YEARLY_BAR),
        )
        if (projectionText != null) {
            Text(projectionText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(StatsTags.PROJECTION))
        }
    }
}
