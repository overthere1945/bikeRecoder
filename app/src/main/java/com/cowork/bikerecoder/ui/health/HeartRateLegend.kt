package com.cowork.bikerecoder.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.core.post.HrBand

/** How a detail map colours the track (spec §3.3): [기본] the track's red, [심박] by heart rate. Not remembered. */
enum class TrackColorMode { DEFAULT, HEART_RATE }

/** Test tags of the [기본] / [심박] chips. */
object TrackColorTags {
    const val DEFAULT = "track-color-default"
    const val HEART_RATE = "track-color-heart-rate"
}

/** The legend's label of each colour (spec §3.3), in the legend's order. */
internal val HR_LEGEND: List<Pair<HrBand, String>> = listOf(
    HrBand.BLUE to "100 미만",
    HrBand.GREEN to "100–119",
    HrBand.YELLOW to "120–139",
    HrBand.ORANGE to "140–159",
    HrBand.RED to "160 이상",
    HrBand.NONE to "기록 없음",
)

/** The heart-rate colours' key for a map corner: the five bands and grey (no sample within 30 s). */
@Composable
fun HeartRateLegend(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = Color.White.copy(alpha = 0.85f)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HR_LEGEND.forEach { (band, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(band.color(), CircleShape))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                }
            }
        }
    }
}

/** [기본] [심박] over a detail map; [mode] is the one chosen. */
@Composable
fun TrackColorToggle(mode: TrackColorMode, onModeChange: (TrackColorMode) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackColorChip("기본", mode == TrackColorMode.DEFAULT, TrackColorTags.DEFAULT) { onModeChange(TrackColorMode.DEFAULT) }
        TrackColorChip("심박", mode == TrackColorMode.HEART_RATE, TrackColorTags.HEART_RATE) {
            onModeChange(TrackColorMode.HEART_RATE)
        }
    }
}

@Composable
private fun TrackColorChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
        colors = FilterChipDefaults.filterChipColors(containerColor = Color.White.copy(alpha = 0.9f)),
    )
}

private fun HrBand.color(): Color = Color(android.graphics.Color.parseColor(colorHex))
