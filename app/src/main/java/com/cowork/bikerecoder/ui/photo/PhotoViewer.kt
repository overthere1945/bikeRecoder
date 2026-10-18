package com.cowork.bikerecoder.ui.photo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.post.RidePhoto
import com.cowork.bikerecoder.ui.common.GlyphIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tags of the full-screen photo view. */
object PhotoViewerTags {
    const val PAGER = "photo-viewer-pager"
}

/**
 * The full-screen view (spec §2.3): [photos] side by side in the given order, starting at [startIndex]; the shown
 * photo's time ("10월 18일 오후 2:31") and [숨기기] ([다시 보이기] on a hidden one, both [onToggleHidden]); back
 * ([onDismiss], also the system back).
 */
@Composable
fun PhotoViewer(
    photos: List<RidePhoto>,
    startIndex: Int,
    thumbnails: PhotoThumbnails,
    onToggleHidden: (RidePhoto) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (photos.isEmpty()) return@Dialog
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { photos[it].id },
                modifier = Modifier.fillMaxSize().testTag(PhotoViewerTags.PAGER),
            ) { page ->
                FullPhoto(photos[page].mediaId, thumbnails)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding()) {
                GlyphIcon("←", "뒤로", modifier = Modifier.background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp))
            }
            val current = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(photoTimeText(current.takenAt), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onToggleHidden(current) }) { Text(if (current.hidden) "다시 보이기" else "숨기기") }
            }
        }
    }
}

/** One photo, fitted to the screen; a spinner while it is read, black if it cannot be. */
@Composable
private fun FullPhoto(mediaId: Long, thumbnails: PhotoThumbnails) {
    val state by produceState<FullPhotoState>(FullPhotoState.Loading, mediaId) {
        value = thumbnails.full(mediaId)?.let { FullPhotoState.Loaded(it) } ?: FullPhotoState.Failed
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            FullPhotoState.Loading -> CircularProgressIndicator(color = Color.White)
            FullPhotoState.Failed -> Unit
            is FullPhotoState.Loaded ->
                Image(s.bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private sealed interface FullPhotoState {
    data object Loading : FullPhotoState

    data object Failed : FullPhotoState

    data class Loaded(val bitmap: ImageBitmap) : FullPhotoState
}

/**
 * Which photos the full-screen view shows: the ids it was opened with, in that order, so a photo hidden (or shown again)
 * while it is open stays in it with its button turned over.
 */
@Stable
class PhotoViewerState internal constructor() {
    var openIds by mutableStateOf<List<Long>?>(null)
        private set
    var startIndex by mutableIntStateOf(0)
        private set

    /** Opens [photos] at [index]; nothing for no photos. */
    fun open(photos: List<RidePhoto>, index: Int) {
        if (photos.isEmpty()) return
        openIds = photos.map { it.id }
        startIndex = index.coerceIn(0, photos.lastIndex)
    }

    /** A tap on the map preview [photoId]: opens [photos] at it; nothing if it is no longer among them. */
    fun openPhoto(photos: List<RidePhoto>, photoId: Long) {
        val index = photos.indexOfFirst { it.id == photoId }
        if (index >= 0) open(photos, index)
    }

    fun close() {
        openIds = null
    }
}

@Composable
fun rememberPhotoViewerState(): PhotoViewerState = remember { PhotoViewerState() }

/**
 * The full-screen view of [state] over the screen's current [photos]: a photo removed meanwhile (deleted from the
 * gallery) drops out, and with none left the view closes.
 */
@Composable
fun PhotoViewerHost(
    state: PhotoViewerState,
    photos: RidePhotos,
    thumbnails: PhotoThumbnails,
    onToggleHidden: (RidePhoto) -> Unit,
) {
    val ids = state.openIds ?: return
    val shown = remember(ids, photos) { viewerPhotos(ids, photos) }
    if (shown.isEmpty()) {
        LaunchedEffect(Unit) { state.close() }
        return
    }
    PhotoViewer(shown, state.startIndex, thumbnails, onToggleHidden, onDismiss = state::close)
}

/**
 * What the viewer opened with [openIds] shows of the screen's current [photos]: those ids in that order, hidden or not
 * (a photo hidden or shown again while open stays), without the ones gone meanwhile (deleted from the gallery).
 */
internal fun viewerPhotos(openIds: List<Long>, photos: RidePhotos): List<RidePhoto> {
    val byId = (photos.visible + photos.hidden).associateBy { it.id }
    return openIds.mapNotNull { byId[it] }
}

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val PHOTO_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

/** When a photo was taken, in Seoul like the rest of the history: "10월 18일 오후 2:31". */
internal fun photoTimeText(takenAt: Long): String = PHOTO_TIME.format(Instant.ofEpochMilli(takenAt).atZone(SEOUL))
