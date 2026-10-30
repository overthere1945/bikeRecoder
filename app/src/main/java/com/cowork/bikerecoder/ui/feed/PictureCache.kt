package com.cowork.bikerecoder.ui.feed

import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Pictures by file path, at most [maxBytes] in all ([bytesOf] each); the least recently used goes first. One larger than the
 * whole cache is not kept (it would push all the others out).
 */
internal class PictureCache<V : Any>(private val maxBytes: Int, private val bytesOf: (V) -> Int) {
    private val lru = object : LruCache<String, V>(maxBytes) {
        override fun sizeOf(key: String, value: V): Int = bytesOf(value)
    }

    operator fun get(path: String): V? = lru[path]

    operator fun set(path: String, value: V) {
        if (bytesOf(value) <= maxBytes) lru.put(path, value)
    }
}

private const val MAX_CACHE_BYTES = 32 * 1024 * 1024

/** An eighth of the heap ([maxMemory]), at most 32 MB (8 pictures of 1280×720 at the S22's usual heap). */
internal fun pictureCacheBytes(maxMemory: Long): Int = (maxMemory / 8).coerceAtMost(MAX_CACHE_BYTES.toLong()).toInt()

/** The largest power of two that still reads a picture [width] px wide at least [cardWidth] px wide (1 while it is unknown). */
internal fun pictureSampleSize(width: Int, cardWidth: Int): Int {
    if (cardWidth <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= cardWidth) sample *= 2
    return sample
}

/**
 * The feed's satellite pictures once decoded, for this process (review I3): a card that scrolls back into view draws its
 * picture on the first frame instead of the line drawing. A picture file's name holds its key, so a path never stands for
 * another picture.
 */
internal object FeedPictureCache {
    private val cache = PictureCache<ImageBitmap>(pictureCacheBytes(Runtime.getRuntime().maxMemory())) {
        it.asAndroidBitmap().allocationByteCount
    }

    operator fun get(file: File): ImageBitmap? = cache[file.path]

    operator fun set(file: File, picture: ImageBitmap) {
        cache[file.path] = picture
    }

    /** Decodes [file] for a card [cardWidth] px wide and keeps it; null when it cannot be read. Off the main thread. */
    fun decode(file: File, cardWidth: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = pictureSampleSize(bounds.outWidth, cardWidth) }
        return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()?.also { this[file] = it }
    }
}
