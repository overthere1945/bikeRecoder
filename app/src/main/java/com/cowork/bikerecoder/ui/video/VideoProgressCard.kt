package com.cowork.bikerecoder.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cowork.bikerecoder.video.MediaKind

/**
 * Where trip [tripId] offers its video (spec §1.1, §3.2): the progress card while its job runs, else [영상 만들기]
 * [이미지 만들기] (which open [viewModel]'s sheet).
 */
@Composable
fun MediaEntry(viewModel: VideoViewModel, tripId: Long, modifier: Modifier = Modifier) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val making by viewModel.makingTrip.collectAsStateWithLifecycle()
    MediaEntryContent(
        // A job of another trip shows nothing here (its buttons then say "이미 영상을 만들고 있습니다").
        progress = progress?.takeIf { making == null || making == tripId },
        onMake = { kind -> viewModel.open(tripId, kind) },
        onCancel = viewModel::cancel,
        modifier = modifier,
    )
}

/** [progress] (a job runs) as [VideoProgressCard], else the two buttons. */
@Composable
fun MediaEntryContent(progress: VideoProgress?, onMake: (MediaKind) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    if (progress != null) {
        VideoProgressCard(progress, onCancel, modifier)
    } else {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MakeButton("영상 만들기", Icons.Filled.Movie, { onMake(MediaKind.VIDEO) }, VideoTags.MAKE_VIDEO, Modifier.weight(1f))
            MakeButton("이미지 만들기", Icons.Filled.Image, { onMake(MediaKind.IMAGE) }, VideoTags.MAKE_IMAGE, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MakeButton(text: String, icon: ImageVector, onClick: () -> Unit, tag: String, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = 48.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "영상 만드는 중 N% · 약 M분 남음" (or "이미지 만드는 중") with its bar — indeterminate until the first frame — and
 * [취소], in the cards' look (the cumulative map's ride card: rounded, secondary container).
 */
@Composable
fun VideoProgressCard(progress: VideoProgress, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
            .testTag(VideoTags.PROGRESS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (progress.kind == MediaKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                progress.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            val percent = progress.percent
            // The default track is the card's own colour: a light primary track shows how much is left.
            val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), trackColor = track)
            } else {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    trackColor = track,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.testTag(VideoTags.CANCEL)) { Text("취소") }
    }
}
