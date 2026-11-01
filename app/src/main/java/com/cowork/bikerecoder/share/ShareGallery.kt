package com.cowork.bikerecoder.share

import android.content.ContentResolver
import android.net.Uri
import com.cowork.bikerecoder.core.video.MediaNaming
import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.MediaStoreWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Puts share cards into the gallery's `Pictures/bikeRecoder/` ([저장] spec §5.5, [스티커] spec §5.6), not into
 * `TripMedia` (the trip screen does not list them). [displayName] is the wanted name ([ShareGalleryNames]); a name
 * already in the folder gets `_2`, `_3`, ….
 */
interface ShareGallery {
    suspend fun saveJpeg(file: ShareFile, displayName: String)

    suspend fun savePng(file: ShareFile, displayName: String)
}

/** The gallery file names of saved cards and stickers (spec §5.5, §5.6), in [zone] time. */
object ShareGalleryNames {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    /** `bikeRecoder_card_<yyyyMMdd_HHmmss>.jpg` at [atMillis]. */
    fun card(atMillis: Long, zone: ZoneId = SEOUL): String = "bikeRecoder_card_${stamp(atMillis, zone)}.jpg"

    /** `bikeRecoder_sticker_<yyyyMMdd_HHmmss>.png` at [atMillis]. */
    fun sticker(atMillis: Long, zone: ZoneId = SEOUL): String = "bikeRecoder_sticker_${stamp(atMillis, zone)}.png"

    private fun stamp(atMillis: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMillis).atZone(zone).format(STAMP)
}

/**
 * [ShareGallery] through [MediaStoreWriter]: the cache file ([ShareFile.uri], read through [resolver]) is copied into a
 * pending gallery item of the file's mime type, which is published when whole and deleted when anything fails. A copy
 * failing for want of space throws [NoSpaceException].
 */
class MediaStoreShareGallery(
    private val resolver: ContentResolver,
    private val writer: MediaStoreWriter,
) : ShareGallery {

    override suspend fun saveJpeg(file: ShareFile, displayName: String) = save(file, displayName, JPEG)

    override suspend fun savePng(file: ShareFile, displayName: String) = save(file, displayName, PNG)

    private suspend fun save(file: ShareFile, displayName: String, mimeType: String) = withContext(Dispatchers.IO) {
        val name = MediaNaming.unique(
            displayName.substringBeforeLast('.'),
            displayName.substringAfterLast('.', ""),
            writer.existingNames(MediaKind.IMAGE),
        )
        val target = writer.createPending(MediaKind.IMAGE, name, mimeType)
        try {
            val input = resolver.openInputStream(Uri.parse(file.uri)) ?: throw IOException("cannot read ${file.uri}")
            input.use { from ->
                val output = resolver.openOutputStream(target) ?: throw IOException("cannot write $target")
                output.use { from.copyTo(it) }
            }
            writer.publish(target)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                try {
                    writer.delete(target)
                } catch (d: Exception) {
                    e.addSuppressed(d)
                }
            }
            throw if (e is IOException) noSpaceOr(e) else e
        }
    }

    private companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
    }
}
