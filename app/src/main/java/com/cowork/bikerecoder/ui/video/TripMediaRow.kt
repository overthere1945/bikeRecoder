package com.cowork.bikerecoder.ui.video

import android.content.ContentResolver
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads the gallery thumbnail of a made file, about `sizePx` square; null when it cannot be read. */
typealias MediaThumbnail = suspend (uri: String, sizePx: Int) -> ImageBitmap?

/**
 * "만든 영상·이미지" (spec §5.1): the trip's videos and images, newest first, sideways — each its gallery thumbnail
 * (a video with ▶) and "영상 · 세로"; a tap opens it ([onOpen], the gallery's view). Nothing when none was made.
 */
@Composable
fun TripMediaRow(media: List<TripMedia>, thumbnail: MediaThumbnail, onOpen: (TripMedia) -> Unit, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().testTag(VideoTags.MEDIA_ROW), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("만든 영상·이미지", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(media, key = { it.id }) { item -> MediaTile(item, thumbnail, onOpen) }
        }
    }
}

@Composable
private fun MediaTile(media: TripMedia, thumbnail: MediaThumbnail, onOpen: (TripMedia) -> Unit) {
    val label = mediaLabel(media)
    val sizePx = with(LocalDensity.current) { TILE_HEIGHT.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, media.uri, sizePx) { value = thumbnail(media.uri, sizePx) }
    Column(
        modifier = Modifier
            .width(TILE_WIDTH)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { onOpen(media) }
            .semantics { contentDescription = label }
            .testTag(VideoTags.media(media.id)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(TILE_WIDTH, TILE_HEIGHT)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (media.kind == MediaKind.IMAGE) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (media.kind == MediaKind.VIDEO) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        Row(modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "영상 · 세로", "이미지 · 가로". */
internal fun mediaLabel(media: TripMedia): String {
    val kind = if (media.kind == MediaKind.VIDEO) "영상" else "이미지"
    val aspect = if (VideoOptionsJson.decode(media.optionsJson).aspect == Aspect.PORTRAIT) "세로" else "가로"
    return "$kind · $aspect"
}

/** The gallery's own thumbnail of [uri] (video or image), read off the main thread. */
fun galleryThumbnail(resolver: ContentResolver): MediaThumbnail = { uri, sizePx ->
    withContext(Dispatchers.IO) {
        try {
            resolver.loadThumbnail(uri.toUri(), Size(sizePx, sizePx), null).asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

private val TILE_WIDTH = 96.dp
private val TILE_HEIGHT = 128.dp
