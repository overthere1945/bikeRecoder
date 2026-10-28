package com.cowork.bikerecoder.post

import android.net.Uri
import com.cowork.bikerecoder.core.post.PhotoCandidate
import java.util.concurrent.CopyOnWriteArrayList

// Shared by the JVM tests (src/test) and the device tests (src/androidTest). Thread-safe.

/** Gallery in memory: [photos] is what the gallery holds (replace it to simulate deletion); records every query. */
class FakePhotoSource(
    @Volatile var access: PhotoAccess = PhotoAccess.FULL,
    photos: List<PhotoCandidate> = emptyList(),
) : PhotoSource {
    @Volatile var photos: List<PhotoCandidate> = photos

    /** When set, [photos] (the query) throws it. */
    @Volatile var failure: Exception? = null

    val queries: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()

    override fun access(): PhotoAccess = access

    override suspend fun photos(from: Long, to: Long): List<PhotoCandidate> {
        queries += from to to
        failure?.let { throw it }
        if (access == PhotoAccess.NONE) return emptyList()
        return photos.filter { it.takenAt in from..to }.sortedBy { it.takenAt }
    }

    override suspend fun existing(mediaIds: Collection<Long>): Set<Long> {
        if (access != PhotoAccess.FULL) return mediaIds.toSet()
        val present = photos.mapTo(HashSet()) { it.mediaId }
        return mediaIds.filterTo(HashSet()) { it in present }
    }

    /**
     * Device tests only. In JVM tests `android.net.Uri` is a stub and no instance can exist: it cannot be subclassed,
     * and with `unitTests.isReturnDefaultValues` `Uri.parse` would return null and fail Kotlin's non-null check anyway.
     * JVM-tested code should carry `mediaId` and turn it into a Uri in the UI layer.
     */
    override fun uri(mediaId: Long): Uri = Uri.parse("content://fake.photos/$mediaId")
}
