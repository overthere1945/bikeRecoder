package com.cowork.bikerecoder.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideStats

/** The guidance screen's recording line (spec §3.3): "{거리} · {이동 시간} · 평균 {x}". */
fun rideSummaryLine(stats: RideStats): String =
    "${RideFormat.distance(stats.distanceM)} · ${RideFormat.clock(stats.movingTimeMs)} · 평균 ${RideFormat.speedKmh(stats.avgSpeedMps)}"

/** The bottom line of the record screen's map page (spec §3.2), with the current speed first. */
fun rideMapSummaryLine(stats: RideStats): String =
    "${RideFormat.speedKmh(stats.currentSpeedMps)}km/h · ${rideSummaryLine(stats)}"

/**
 * The numbers page (spec §3.2): the current speed large, then distance, moving time, average, maximum, altitude and
 * the total climb and descent. [dimmed] while paused.
 */
@Composable
fun RideNumbersPage(stats: RideStats, dimmed: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.alpha(if (dimmed) DIMMED_ALPHA else 1f).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("현재 속도", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${RideFormat.speedKmh(stats.currentSpeedMps)}km/h",
            style = MaterialTheme.typography.displayLarge,
        )
        NumberRow(
            "거리" to RideFormat.distance(stats.distanceM),
            "이동 시간" to RideFormat.clock(stats.movingTimeMs),
        )
        NumberRow(
            "평균" to "${RideFormat.speedKmh(stats.avgSpeedMps)}km/h",
            "최고" to "${RideFormat.speedKmh(stats.maxSpeedMps)}km/h",
        )
        NumberRow(
            "고도" to RideFormat.altitude(stats.altitudeM),
            "누적 ↑/↓" to "${RideFormat.altitude(stats.ascentM)} / ${RideFormat.altitude(stats.descentM)}",
        )
    }
}

/** Two label/value cells side by side. */
@Composable
internal fun NumberRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        NumberCell(left.first, left.second, Modifier.weight(1f))
        NumberCell(right.first, right.second, Modifier.weight(1f))
    }
}

/** The value on one line: shrunk (down to [MIN_VALUE_SIZE]) only when it would not fit, e.g. "14.5 / 23.4km/h" in a card. */
@Composable
private fun NumberCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val style = MaterialTheme.typography.headlineSmall
        Text(
            value,
            style = style,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_VALUE_SIZE, maxFontSize = style.fontSize, stepSize = 1.sp),
        )
    }
}

private val MIN_VALUE_SIZE = 14.sp

private const val DIMMED_ALPHA = 0.4f
