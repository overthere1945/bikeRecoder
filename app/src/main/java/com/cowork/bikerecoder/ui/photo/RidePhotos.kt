package com.cowork.bikerecoder.ui.photo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.map.MAP_PHOTO_DIAMETER
import com.cowork.bikerecoder.map.MapPhoto
import com.cowork.bikerecoder.post.PhotoThumbnails
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.RidePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * A ride screen's photos, both in time order (spec §2.3): [visible] on the map and in the strip, [hidden] only behind
 * "숨긴 사진 N장".
 */
data class RidePhotos(val visible: List<RidePhoto> = emptyList(), val hidden: List<RidePhoto> = emptyList()) {
    companion object {
        fun of(all: List<RidePhoto>): RidePhotos {
            val (hidden, visible) = all.partition { it.hidden }
            return RidePhotos(visible, hidden)
        }
    }
}

/** [photos] (a [PostStore.observePhotos]) as [RidePhotos]; a store failure is logged and shows no photos. */
internal fun observeRidePhotos(photos: Flow<List<RidePhoto>>, log: WarnLog): Flow<RidePhotos> = photos
    .map(RidePhotos::of)
    .catch { e ->
        log.warn("Could not read the ride photos", e)
        emit(RidePhotos())
    }

/** [숨기기] / [다시 보이기]; a failure is logged (the photo stays as it was). */
internal suspend fun setPhotoHidden(store: PostStore, photo: RidePhoto, hidden: Boolean, log: WarnLog) {
    try {
        store.setHidden(photo.id, hidden)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not change photo ${photo.id}", e)
    }
}

/**
 * [photos] as map previews: grey discs at first, then (all at once, so the map uploads its images once) with their
 * thumbnails. Thumbnails already read are kept while the list changes.
 */
@Composable
fun rememberMapPhotos(photos: List<RidePhoto>, thumbnails: PhotoThumbnails): List<MapPhoto> {
    val sizePx = with(LocalDensity.current) { MAP_PHOTO_DIAMETER.roundToPx() }
    var loaded by remember { mutableStateOf<Map<Long, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(photos, sizePx) {
        val missing = photos.map { it.mediaId }.distinct().filterNot { it in loaded }
        if (missing.isEmpty()) return@LaunchedEffect
        val read = missing.mapNotNull { id -> thumbnails.thumbnail(id, sizePx)?.let { id to it } }
        if (read.isNotEmpty()) loaded = loaded + read
    }
    return remember(photos, loaded) { photos.map { MapPhoto(it.id, it.point, loaded[it.mediaId]) } }
}
