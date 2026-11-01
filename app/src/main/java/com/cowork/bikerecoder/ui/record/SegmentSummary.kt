package com.cowork.bikerecoder.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.SegmentTotals
import com.cowork.bikerecoder.ui.activity.ActivityText

/**
 * The summary after a recording ends (spec §3.4): the activity's three card numbers (V1.9.0 spec §2.2), then the
 * numbers of the old summary they leave out, and the map, then [footer] (e.g. the guidance's "여행을 완료했습니다") above [확인].
 */
@Composable
fun SegmentSummaryContent(
    segment: RideSegment,
    map: @Composable (Modifier) -> Unit,
    onConfirm: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    val totals = segment.totals
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("구간 요약", style = MaterialTheme.typography.headlineSmall)
            Text(
                RideFormat.date(segment.localDate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val cells = ActivityText.cardCells(
                segment.activity, totals.distanceM, totals.movingTimeMs, totals.elapsedMs, totals.ascentM, totals.maxAltitudeM,
            )
            NumberRow(cells[0], cells[1], cells[2])
            RestCells(segment.activity, totals)
            map(Modifier.fillMaxWidth().weight(1f))
            footer()
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("확인") }
        }
    }
}

/** The numbers of the old summary (distance, moving time, average/maximum, climb) the card's three cells leave out. */
@Composable
private fun RestCells(activity: ActivityType, totals: SegmentTotals) {
    // Two cells to a row; a last one alone takes a row of its own.
    summaryRestCells(activity, totals).chunked(2).forEach { row ->
        if (row.size == 2) NumberRow(row[0], row[1]) else NumberRow(row[0])
    }
}

/**
 * The cells under the card's three (spec §3.2): what the V1.8.0 summary showed and they leave out. A bike keeps the top
 * speed and the climb; a run and a walk the average speed ("평균 페이스" is on the card) and the climb; a hike the
 * distance, the moving time and the speeds (km/h).
 */
internal fun summaryRestCells(activity: ActivityType, totals: SegmentTotals): List<Pair<String, String>> {
    val climb = "누적 상승" to RideFormat.altitude(totals.ascentM)
    val averageSpeed = "평균 속도" to "${RideFormat.speedKmh(totals.avgSpeedMps)}km/h"
    return when (activity) {
        ActivityType.BIKE -> listOf("최고 속도" to "${RideFormat.speedKmh(totals.maxSpeedMps)}km/h", climb)
        ActivityType.WALK, ActivityType.RUN -> listOf(averageSpeed, climb)
        ActivityType.HIKE -> listOf(
            "거리" to RideFormat.distance(totals.distanceM),
            "이동 시간" to RideFormat.clock(totals.movingTimeMs),
            "평균/최고" to "${RideFormat.speedKmh(totals.avgSpeedMps)} / ${RideFormat.speedKmh(totals.maxSpeedMps)}km/h",
        )
    }
}
