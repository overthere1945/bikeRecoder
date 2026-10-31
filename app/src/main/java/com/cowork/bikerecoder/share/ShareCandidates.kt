package com.cowork.bikerecoder.share

import androidx.core.net.toUri
import com.cowork.bikerecoder.video.MediaStoreWriter
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.VideoOptionsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the gallery files of a trip that may still be shared. */
class ShareCandidates(private val writer: MediaStoreWriter) {

    /** The entries of [media] whose gallery file still exists, with size and aspect; files that are gone are left out. */
    suspend fun of(media: List<TripMedia>): List<ShareCandidate> = withContext(Dispatchers.IO) {
        media.mapNotNull { m ->
            val uri = m.uri.toUri()
            if (!writer.exists(uri)) return@mapNotNull null
            ShareCandidate(
                uri = m.uri,
                kind = m.kind,
                aspect = VideoOptionsJson.decode(m.optionsJson).aspect,
                createdAt = m.createdAt,
                sizeBytes = writer.sizeOf(uri),
            )
        }
    }
}
