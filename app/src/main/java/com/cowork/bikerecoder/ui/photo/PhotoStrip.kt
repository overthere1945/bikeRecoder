package com.cowork.bikerecoder.ui.photo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.post.RidePhoto

/** Test tags of the photo strip. */
object PhotoStripTags {
    /** The [index]th preview (0-based) of the strip. */
    fun item(index: Int) = "photo-strip-$index"
}

/**
 * "사진 N장" with the previews in time order (scrolling sideways); a tap opens the full-screen view at that photo
 * ([onOpen] with its index in [photos]). "숨긴 사진 N장" at the end of the row opens the hidden ones ([onOpenHidden]).
 * Nothing without any photo.
 */
@Composable
fun PhotoStrip(
    photos: List<RidePhoto>,
    hiddenCount: Int,
    thumbnails: PhotoThumbnails,
    onOpen: (index: Int) -> Unit,
    onOpenHidden: () -> Unit,
) {
    if (photos.isEmpty() && hiddenCount == 0) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("사진 ${photos.size}장", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                PhotoThumbnail(
                    mediaId = photo.mediaId,
                    thumbnails = thumbnails,
                    size = STRIP_PHOTO_SIZE,
                    modifier = Modifier
                        .clickable { onOpen(index) }
                        .semantics { contentDescription = "사진 ${index + 1}" }
                        .testTag(PhotoStripTags.item(index)),
                )
            }
            if (hiddenCount > 0) {
                item(key = "hidden") { TextButton(onClick = onOpenHidden) { Text("숨긴 사진 ${hiddenCount}장") } }
            }
        }
    }
}

/** A [size] square preview of gallery photo [mediaId], centre-cropped; grey until (or unless) it is read. */
@Composable
internal fun PhotoThumbnail(mediaId: Long, thumbnails: PhotoThumbnails, size: Dp, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, mediaId, sizePx) { value = thumbnails.thumbnail(mediaId, sizePx) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

private val STRIP_PHOTO_SIZE = 72.dp
