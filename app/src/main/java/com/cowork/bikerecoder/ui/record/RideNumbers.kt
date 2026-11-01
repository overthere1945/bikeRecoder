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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowork.bikerecoder.core.activity.ActivityFormat
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.ui.activity.ActivityText
import kotlinx.coroutines.delay
import java.time.ZoneId

/** Test tags of the numbers page. */
object NumbersTags {
    /** The big current speed / pace. */
    const val CURRENT = "numbers-current"

    /** The hike's 산행 시간 and 현재 고도 (the same size). */
    const val HIKE_TIME = "numbers-hike-time"
    const val HIKE_ALTITUDE = "numbers-hike-altitude"

    /** The hike's sunset line; [SUNSET_WARNING] instead while the sunset is at most an hour away or past. */
    const val SUNSET = "numbers-sunset"
    const val SUNSET_WARNING = "numbers-sunset-warning"
}

/**
 * The recording line (spec §3.3, plan P15): a bike "{거리} · {이동 시간} · 평균 {x}", a run or walk
 * "{거리} · {이동 시간} · 평균 {페이스}/km", a hike "산행 {H:MM:SS} · {고도} · ↑ {상승}".
 */
fun rideSummaryLine(stats: RideStats, activity: ActivityType = ActivityType.BIKE): String = when (activity) {
    ActivityType.BIKE ->
        "${RideFormat.distance(stats.distanceM)} · ${RideFormat.clock(stats.movingTimeMs)} · 평균 ${RideFormat.speedKmh(stats.avgSpeedMps)}"
    ActivityType.RUN, ActivityType.WALK ->
        "${RideFormat.distance(stats.distanceM)} · ${RideFormat.clock(stats.movingTimeMs)} · 평균 ${averagePaceText(stats)}"
    ActivityType.HIKE ->
        "산행 ${RideFormat.clock(stats.elapsedMs)} · ${RideFormat.altitude(stats.altitudeM)} · ↑ ${RideFormat.altitude(stats.ascentM)}"
}

/**
 * The bottom line of the record screen's map page (spec §3.2): [rideSummaryLine] led by the current speed (bike) or
 * pace (run, walk); a hike has no lead-in. [paused]: the pace is unknown while paused (its 30 s window is not aged by
 * the clock).
 */
fun rideMapSummaryLine(stats: RideStats, activity: ActivityType = ActivityType.BIKE, paused: Boolean = false): String =
    when (activity) {
        ActivityType.BIKE -> "${RideFormat.speedKmh(stats.currentSpeedMps)}km/h · ${rideSummaryLine(stats, activity)}"
        ActivityType.RUN, ActivityType.WALK -> "${currentPaceText(stats, paused)} · ${rideSummaryLine(stats, activity)}"
        ActivityType.HIKE -> rideSummaryLine(stats, activity)
    }

/** The pace of the last 30 seconds, "5'30"/km"; "--'--"/km" while paused or before two points. */
internal fun currentPaceText(stats: RideStats, paused: Boolean): String =
    "${ActivityFormat.pace(if (paused) null else stats.recentSpeedMps)}/km"

/** The pace of the whole moving time, "5'32"/km". */
internal fun averagePaceText(stats: RideStats): String =
    "${ActivityFormat.paceOf(stats.distanceM, stats.movingTimeMs)}/km"

/** The pace of the last full kilometre, "5'32"/km"; "--'--"/km" before the first one. */
internal fun lastKmPaceText(stats: RideStats): String =
    "${stats.lastLapMovingTimeMs?.let { ActivityFormat.paceOf(1000.0, it) } ?: ActivityFormat.NO_PACE}/km"

/**
 * The numbers page (spec §3.2) of the [activity]:
 * - bike: the current speed large, then distance, moving time, average, maximum, altitude and the total climb/descent;
 * - run and walk: the current pace large, then distance, moving time, average pace, the last kilometre's pace (run) or
 *   the average speed (walk), altitude and climb/descent;
 * - hike: 산행 시간 and 현재 고도 side by side at one size, then climb, descent, distance, moving time and the sunset
 *   line (from [sunsetMillis] and [nowMillis]; hidden while the sunset is unknown).
 * [dimmed] while paused: the current pace is then unknown.
 */
@Composable
fun RideNumbersPage(
    stats: RideStats,
    activity: ActivityType = ActivityType.BIKE,
    sunsetMillis: Long? = null,
    nowMillis: Long = 0L,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.alpha(if (dimmed) DIMMED_ALPHA else 1f).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (activity) {
            ActivityType.BIKE -> BikeNumbers(stats)
            ActivityType.RUN, ActivityType.WALK -> PaceNumbers(stats, activity, paused = dimmed)
            ActivityType.HIKE -> HikeNumbers(stats, sunsetMillis, nowMillis)
        }
    }
}

@Composable
private fun BikeNumbers(stats: RideStats) {
    BigNumber("현재 속도", "${RideFormat.speedKmh(stats.currentSpeedMps)}km/h")
    NumberRow(
        "거리" to RideFormat.distance(stats.distanceM),
        "이동 시간" to RideFormat.clock(stats.movingTimeMs),
    )
    NumberRow(
        "평균" to "${RideFormat.speedKmh(stats.avgSpeedMps)}km/h",
        "최고" to "${RideFormat.speedKmh(stats.maxSpeedMps)}km/h",
    )
    AltitudeRow(stats)
}

@Composable
private fun PaceNumbers(stats: RideStats, activity: ActivityType, paused: Boolean) {
    BigNumber("현재 페이스", currentPaceText(stats, paused))
    NumberRow(
        "거리" to RideFormat.distance(stats.distanceM),
        "이동 시간" to RideFormat.clock(stats.movingTimeMs),
    )
    NumberRow(
        "평균 페이스" to averagePaceText(stats),
        if (activity == ActivityType.RUN) "직전 1 km" to lastKmPaceText(stats)
        else "평균 속도" to "${RideFormat.speedKmh(stats.avgSpeedMps)}km/h",
    )
    AltitudeRow(stats)
}

@Composable
private fun HikeNumbers(stats: RideStats, sunsetMillis: Long?, nowMillis: Long) {
    // Both at one fixed size (no auto-sizing): they are the page's two equal main numbers.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        HikeBigCell("산행 시간", RideFormat.clock(stats.elapsedMs), NumbersTags.HIKE_TIME, Modifier.weight(1f))
        HikeBigCell("현재 고도", RideFormat.altitude(stats.altitudeM), NumbersTags.HIKE_ALTITUDE, Modifier.weight(1f))
    }
    NumberRow(
        "누적 상승" to RideFormat.altitude(stats.ascentM),
        "누적 하강" to RideFormat.altitude(stats.descentM),
    )
    NumberRow(
        "거리" to RideFormat.distance(stats.distanceM),
        "이동 시간" to RideFormat.clock(stats.movingTimeMs),
    )
    if (sunsetMillis != null) {
        val warning = ActivityText.sunsetWarning(sunsetMillis, nowMillis)
        Text(
            ActivityText.sunsetLine(sunsetMillis, nowMillis, ZoneId.systemDefault()),
            style = MaterialTheme.typography.titleMedium,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(if (warning) NumbersTags.SUNSET_WARNING else NumbersTags.SUNSET),
        )
    }
}

@Composable
private fun AltitudeRow(stats: RideStats) {
    NumberRow(
        "고도" to RideFormat.altitude(stats.altitudeM),
        "누적 ↑/↓" to "${RideFormat.altitude(stats.ascentM)} / ${RideFormat.altitude(stats.descentM)}",
    )
}

/** The page's large number under its label. */
@Composable
private fun BigNumber(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag(NumbersTags.CURRENT))
}

@Composable
private fun HikeBigCell(label: String, value: String, tag: String, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * The current time for the hike's sunset line, refreshed every second while [active]; without it (not a hike) no
 * timer runs.
 */
@Composable
fun rememberNowMillis(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(NOW_TICK_MS)
        }
    }
    return now
}

private const val NOW_TICK_MS = 1_000L

/** Two label/value cells side by side. */
@Composable
internal fun NumberRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        NumberCell(left.first, left.second, Modifier.weight(1f))
        NumberCell(right.first, right.second, Modifier.weight(1f))
    }
}

/** One label/value cell across the width (the trip totals' second row, V1.7.0 spec §2.3). */
@Composable
internal fun NumberRow(only: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth()) { NumberCell(only.first, only.second, Modifier.weight(1f)) }
}

/** Three label/value cells side by side (the trip totals' third row, V1.7.0 spec §2.3). */
@Composable
internal fun NumberRow(left: Pair<String, String>, middle: Pair<String, String>, right: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        NumberCell(left.first, left.second, Modifier.weight(1f))
        NumberCell(middle.first, middle.second, Modifier.weight(1f))
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
