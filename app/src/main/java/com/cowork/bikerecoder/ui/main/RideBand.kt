package com.cowork.bikerecoder.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ride.RideUiState
import com.cowork.bikerecoder.ui.feed.kmText

/**
 * The line above the tab bar while a ride runs (V1.4.0 spec §1.4): "● 기록 중 · 12.4 km · 48분" (distance and moving
 * time), "Ⅱ 일시정지 · …" while paused, "▲ 안내 중 · 남은 거리 3.2 km" for guidance without a recording; a recording wins
 * over guidance. Null while nothing runs (nor while one is only starting, ended or failed). The kilometres are the V1.4.0
 * km text ([kmText]) and the time the minutes of the record summaries ([RideFormat.duration]).
 */
fun rideBandText(ride: RideUiState, guidance: NavUiState): String? = when {
    ride is RideUiState.Active -> {
        val head = if (ride.state is RecordingState.Paused) "Ⅱ 일시정지" else "● 기록 중"
        "$head · ${kmText(ride.stats.distanceM)} · ${RideFormat.duration(ride.stats.movingTimeMs)}"
    }
    guidance is NavUiState.Active -> "▲ 안내 중 · 남은 거리 ${kmText(guidance.state.progress.remainingM)}"
    else -> null
}

/** The band itself: one line across the screen; a tap opens the running ride's screen. */
@Composable
internal fun RideBand(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("›", style = MaterialTheme.typography.titleMedium)
        }
    }
}
