package com.cowork.bikerecoder.ui.share

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cowork.bikerecoder.share.ShareItem
import com.cowork.bikerecoder.share.ShareMode

/**
 * The big preview of a strip item (share cards spec §1.2): a full-screen dialog with the item as it is sent — a card's
 * drawn file at the screen's width, a photo, the video's thumbnail — and [닫기]. The photo card has the trip's photos
 * below it; tapping one makes it the card's background ([onPhotoCard]), and the card shown is drawn again.
 *
 * [preview] loads the big picture at a width in px; [pictures] has the strip's thumbnails for the photo row.
 */
@Composable
internal fun SharePreview(
    item: ShareItem,
    state: ShareSheetState,
    pictures: SharePictureCache,
    preview: suspend (ShareItem, ShareSheetState, Int) -> ImageBitmap?,
    onPhotoCard: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize().testTag(ShareTags.PREVIEW)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BigPicture(item, state, preview)
                }
                if (item == ShareItem.PhotoCard) PhotoRow(state, pictures, onPhotoCard)
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 16.dp),
                ) {
                    Text("닫기")
                }
            }
        }
    }
}

/**
 * The item as it is sent, at the screen's width: a card's file, a photo as its slide (spec §2.4, [PhotoSlide]); a spinner
 * while it is drawn or read (a picture of another key, such as the photo card before its background changed, counts as
 * still reading), a warning when its card failed.
 */
@Composable
private fun BigPicture(item: ShareItem, state: ShareSheetState, preview: suspend (ShareItem, ShareSheetState, Int) -> ImageBitmap?) {
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val key = pictureKey(item, state)
    val current by rememberUpdatedState(state)
    val loaded by produceState<Loaded?>(null, key, widthPx) {
        value = Loaded(key, key?.let { preview(item, current, widthPx) })
    }
    val status = state.cardOf(item)
    val fresh = loaded?.takeIf { it.key == key }
    val picture = fresh?.picture
    when {
        status is CardStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(if (status.noSpace) ShareViewModel.MSG_NO_SPACE else ShareViewModel.MSG_CARD_FAILED)
        }
        picture != null && item is ShareItem.Photo -> PhotoSlide(picture, state.mode, itemName(item))
        picture != null -> Image(
            picture,
            contentDescription = itemName(item),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
        fresh == null || status == CardStatus.Rendering -> CircularProgressIndicator(color = Color.White)
        else -> Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(48.dp))
    }
}

/**
 * A photo the way its slide is drawn (spec §2.4) without drawing the slide (that waits for the press): a frame of the
 * [mode]'s card shape; an upright photo fills it, a wide one sits whole over a blurred (API 31+) and darkened copy of
 * itself filling the frame.
 */
@Composable
private fun PhotoSlide(photo: ImageBitmap, mode: ShareMode, name: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(mode.size.width.toFloat() / mode.size.height)
            .clipToBounds()
            .testTag(ShareTags.PREVIEW_FRAME),
    ) {
        if (photo.height >= photo.width) {
            Image(photo, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Image(
                photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(BACKDROP_BLUR) else Modifier),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DARKEN)))
            Image(photo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The trip's photos; the photo card's background is marked. */
@Composable
private fun PhotoRow(state: ShareSheetState, pictures: SharePictureCache, onPhotoCard: (Long) -> Unit) {
    val photos = state.items.filterIsInstance<ShareItem.Photo>()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos, key = { it.mediaId }) { photo ->
            val chosen = photo.mediaId == state.photoCardMediaId
            val shape = MaterialTheme.shapes.small
            Box(
                modifier = Modifier
                    .size(PHOTO_SIZE)
                    .clip(shape)
                    .background(Color.DarkGray)
                    .then(if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable { onPhotoCard(photo.mediaId) }
                    .semantics {
                        contentDescription = itemName(photo)
                        selected = chosen
                    },
            ) {
                pictures.picture(photo, state)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** A finished read of the big picture of [key] ([picture] null: there is none, or it could not be read). */
private class Loaded(val key: String?, val picture: ImageBitmap?)

private val PHOTO_SIZE = 64.dp
private val BACKDROP_BLUR = 24.dp
private const val BACKDROP_DARKEN = 0.4f
