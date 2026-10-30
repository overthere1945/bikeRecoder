package com.cowork.bikerecoder.ui.feed

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.ui.history.RouteDrawing
import com.cowork.bikerecoder.video.TripMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The dark ground of the line drawing shown until the satellite picture exists (spec §2.4). */
internal val DRAWING_GROUND = Color(0xFF1E2A22)

/** The route lines on the dark ground are drawn in the same colours as the old list's previews. */
private val DRAWING_PADDING = 24.dp

/** A thumbnail's side in px when asking the gallery for a photo. */
private const val PHOTO_THUMB_PX = 320

/**
 * A trip's card (V1.4.0 spec §2.3): title, date line, the satellite picture 16:9 (the line drawing until it exists) with 🏆 at
 * the top left and [▶ 영상] at the bottom right, the three big numbers, the average line and up to three photos (+N on the
 * third). A tap opens the trip ([onClick]), a long press asks to delete it ([onLongClick]); [photo] reads a gallery thumbnail.
 */
@Composable
fun FeedCard(
    card: FeedCardState,
    photo: suspend (mediaId: Long, sizePx: Int) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenVideo: (TripMedia) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .testTag(FeedTags.card(card.tripId)),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                card.dateLine + if (card.inProgress) " · 진행 중" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CardPicture(card, onOpenVideo)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BigNumber("거리", kmText(card.distanceM))
            BigNumber("이동 시간", RideFormat.duration(card.movingTimeMs))
            BigNumber("상승", meterText(card.ascentM))
        }
        Text(
            averageLineText(card.avgSpeedMps, card.avgBpm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
        )
        if (card.photoMediaIds.isNotEmpty()) PhotoRow(card, photo)
    }
}

@Composable
private fun BigNumber(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The 16:9 picture with its two badges. */
@Composable
private fun CardPicture(card: FeedCardState, onOpenVideo: (TripMedia) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(DRAWING_GROUND)) {
        val image = rememberPicture(card.thumbFile)
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag(FeedTags.picture(card.tripId)),
            )
        } else {
            RouteDrawing(
                card.routes,
                Modifier.fillMaxSize(),
                background = DRAWING_GROUND,
                padding = DRAWING_PADDING,
                lineWidth = 3.dp,
                corner = 0.dp,
            )
        }
        if (card.trophy) {
            Text(
                "🏆",
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(6.dp)
                    .testTag(FeedTags.trophy(card.tripId)),
            )
        }
        card.video?.let { video ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .heightIn(min = 48.dp)
                    .padding(end = 4.dp)
                    .clickable(role = Role.Button) { onOpenVideo(video) }
                    .padding(horizontal = 4.dp)
                    .testTag(FeedTags.video(card.tripId)),
            ) {
                Text(
                    "▶ 영상",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Up to three square thumbnails; the third carries "+N" when there are more. A tap falls through to the card. */
@Composable
private fun PhotoRow(card: FeedCardState, photo: suspend (Long, Int) -> ImageBitmap?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (index in 0 until FeedViewModel.MAX_CARD_PHOTOS) {
            val mediaId = card.photoMediaIds.getOrNull(index)
            val cell = Modifier.weight(1f).aspectRatio(1f)
            if (mediaId == null) {
                Spacer(cell)
                continue
            }
            val thumbnail by produceState<ImageBitmap?>(null, mediaId) { value = photo(mediaId, PHOTO_THUMB_PX) }
            Box(
                modifier = cell
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface)
                    .testTag(FeedTags.photo(card.tripId, index)),
            ) {
                thumbnail?.let {
                    Image(it, contentDescription = "사진", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (index == FeedViewModel.MAX_CARD_PHOTOS - 1 && card.morePhotos > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Text("+${card.morePhotos}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/**
 * The picture file: at once when this process has decoded it before ([FeedPictureCache]), else decoded off the main thread at
 * the screen's width; null while it is read, or when there is none or it cannot be read.
 */
@Composable
private fun rememberPicture(file: File?): ImageBitmap? {
    val cardWidth = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val picture = remember(file) { mutableStateOf(file?.let { FeedPictureCache[it] }) }
    LaunchedEffect(file) {
        if (file == null || picture.value != null) return@LaunchedEffect
        picture.value = withContext(Dispatchers.IO) {
            try {
                FeedPictureCache.decode(file, cardWidth)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    return picture.value
}
