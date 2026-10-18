package com.cowork.bikerecoder.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.post.SegmentExtras
import kotlin.math.roundToInt

const val HR_PENDING = "심박 가져오는 중"
const val HR_NONE = "심박 기록 없음"
const val HR_FAILED = "심박을 가져오지 못했습니다"
const val HR_REFETCH = "다시 가져오기"
const val HR_NOT_DETAILED = "워치에서 자전거 운동을 시작하면 자세한 심박이 기록됩니다"

/**
 * A segment's heart rate (spec §3.2, §3.3) for its [extras] (null = not known yet, read as PENDING): the numbers
 * when READY (with the hint under them when the heart rate is not detailed), otherwise the state's words; always
 * [다시 가져오기] ([onRefetch]).
 */
@Composable
fun HeartRateRow(extras: SegmentExtras?, onRefetch: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(heartRateRowText(extras), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefetch) { Text(HR_REFETCH) }
        }
        if (extras?.hrStatus == HrStatus.READY && !extras.hrDetailed) {
            Text(
                HR_NOT_DETAILED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The row's words: [heartRateLine] when READY, otherwise the state ("심박 가져오는 중" while unknown). */
internal fun heartRateRowText(extras: SegmentExtras?): String = when (extras?.hrStatus) {
    null, HrStatus.PENDING -> HR_PENDING
    HrStatus.NONE -> HR_NONE
    HrStatus.FAILED -> HR_FAILED
    HrStatus.READY -> heartRateLine(extras.avgBpm, extras.maxBpm, extras.calories)
}

/** "평균 심박 {avg} · 최고 {max} bpm · {kcal} kcal", "· – kcal" without calories (spec §3.3). */
fun heartRateLine(avgBpm: Int?, maxBpm: Int?, calories: Double?): String =
    "평균 심박 ${avgBpm ?: "–"} · 최고 ${maxBpm ?: "–"} bpm · ${calories?.roundToInt() ?: "–"} kcal"
