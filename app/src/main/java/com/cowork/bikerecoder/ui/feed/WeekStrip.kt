package com.cowork.bikerecoder.ui.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object FeedTags {
    const val STRIP = "feed-week-strip"
    const val RING = "feed-goal-ring"
    const val EMPTY = "feed-empty"
    const val LIST = "feed-list"

    fun card(tripId: Long) = "feed-card-$tripId"
    fun video(tripId: Long) = "feed-video-$tripId"
    fun trophy(tripId: Long) = "feed-trophy-$tripId"
    fun picture(tripId: Long) = "feed-picture-$tripId"
    fun photo(tripId: Long, index: Int) = "feed-photo-$tripId-$index"
}

/**
 * The strip on top of the feed (V1.4.0 spec §2.1): "이번 주 64.2 km · 4회", the comparison with last week up to the same weekday,
 * "N주 연속", and on the right the goal ring (when there is a weekly goal) or [목표 정하기]. A tap on the strip opens the
 * stats of this week.
 */
@Composable
fun WeekStrip(state: WeekStripState, onOpenStatsWeek: () -> Unit, onSetGoals: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag(FeedTags.STRIP),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenStatsWeek).padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    weekTotalText(state.distanceM, state.rides),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.diffM?.let { Text(weekDiffText(it), style = MaterialTheme.typography.bodyMedium) }
                if (state.streak.weeks >= 1) Text("${state.streak.weeks}주 연속", style = MaterialTheme.typography.bodyMedium)
            }
            val goal = state.weeklyGoalKm
            if (goal == null) {
                FilledTonalButton(onClick = onSetGoals) { Text("목표 정하기") }
            } else {
                GoalRing(state.distanceM, goal)
            }
        }
    }
}

/**
 * The weekly goal ring (spec §3.1): the week's kilometres of the goal, stopping at 100 %; another colour and "달성" once reached.
 * It is drawn for a secondary-container background (also by the stats tab's goal card).
 */
@Composable
internal fun GoalRing(distanceM: Double, goalKm: Int, tag: String = FeedTags.RING) {
    val reached = distanceM >= goalKm * 1000.0
    val fraction = (distanceM / (goalKm * 1000.0)).coerceIn(0.0, 1.0).toFloat()
    val track = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f)
    val progress = if (reached) GOAL_REACHED else MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(RING_SIZE).testTag(tag)) {
        Canvas(modifier = Modifier.size(RING_SIZE)) {
            val width = 8.dp.toPx()
            val inset = width / 2
            val arc = Size(size.width - width, size.height - width)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(width))
            drawArc(
                progress, -90f, 360f * fraction, useCenter = false, topLeft = Offset(inset, inset), size = arc,
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                goalProgressText(distanceM, goalKm),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (reached) Text("달성", style = MaterialTheme.typography.labelSmall, color = GOAL_REACHED_TEXT)
        }
    }
}

private val RING_SIZE = 84.dp
internal val GOAL_REACHED = Color(0xFF2E7D32)
internal val GOAL_REACHED_TEXT = Color(0xFF1B5E20)
