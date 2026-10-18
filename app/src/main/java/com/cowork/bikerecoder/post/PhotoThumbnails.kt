package com.cowork.bikerecoder.post

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gallery images for the photo screens, read on [Dispatchers.IO] and kept in memory caches (no image library):
 * [thumbnail] through `ContentResolver.loadThumbnail`, [full] decoded with its long side at most `maxPx`. A photo
 * that cannot be read (deleted meanwhile, permission revoked, ...) gives null; failures are not cached.
 */
class PhotoThumbnails(
    private val contentResolver: ContentResolver,
    private val photoSource: PhotoSource,
    private val warnLog: WarnLog = androidWarnLog("PhotoThumbnails"),
) {
    private val thumbnails = bitmapCache(THUMBNAIL_CACHE_BYTES)
    private val fulls = bitmapCache(FULL_CACHE_BYTES)

    /** The gallery thumbnail of [mediaId], about [sizePx] square (the provider keeps the aspect ratio). */
    suspend fun thumbnail(mediaId: Long, sizePx: Int): ImageBitmap? = cached(thumbnails, "$mediaId@$sizePx", mediaId) {
        contentResolver.loadThumbnail(photoSource.uri(mediaId), Size(sizePx, sizePx), null)
    }

    /** The photo itself (EXIF orientation applied), scaled down so its long side is at most [maxPx]. */
    suspend fun full(mediaId: Long, maxPx: Int = 2048): ImageBitmap? = cached(fulls, "$mediaId@$maxPx", mediaId) {
        val source = ImageDecoder.createSource(contentResolver, photoSource.uri(mediaId))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > maxPx) {
                val scale = maxPx.toDouble() / longest
                decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            }
        }
    }

    private suspend fun cached(
        cache: LruCache<String, ImageBitmap>,
        key: String,
        mediaId: Long,
        load: () -> android.graphics.Bitmap,
    ): ImageBitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                load().asImageBitmap().also { cache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnLog.warn("Could not read photo $mediaId", e)
                null
            }
        }
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
        const val FULL_CACHE_BYTES = 48 * 1024 * 1024

        fun bitmapCache(maxBytes: Int) = object : LruCache<String, ImageBitmap>(maxBytes) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
        }
    }
}
