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
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.RideSegment

/** The summary after a recording ends (spec §3.4): distance, moving time, average/maximum, climb and the map. */
@Composable
fun SegmentSummaryContent(segment: RideSegment, map: @Composable (Modifier) -> Unit, onConfirm: () -> Unit) {
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
            NumberRow(
                "거리" to RideFormat.distance(totals.distanceM),
                "이동 시간" to RideFormat.clock(totals.movingTimeMs),
            )
            NumberRow(
                "평균/최고" to "${RideFormat.speedKmh(totals.avgSpeedMps)} / ${RideFormat.speedKmh(totals.maxSpeedMps)}km/h",
                "누적 상승" to RideFormat.altitude(totals.ascentM),
            )
            map(Modifier.fillMaxWidth().weight(1f))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("확인") }
        }
    }
}
