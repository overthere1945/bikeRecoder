package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.places.PlaceData
import com.cowork.bikerecoder.core.places.PlaceMatcher
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The share sheet's "지나간 길" tags of a trip (spec §4.4, §4.5): its finished segments of at least [MIN_RIDE_DISTANCE_M]
 * (the video's target segments), one track per segment in start order, matched by [PlaceMatcher] against the place data
 * that [open] gives (`assets/places/places.bin`). The data is read once, on the first [of], behind a [Mutex]; data that
 * cannot be read stays unread (no row, spec §6) and is reported to [log] once. Any failure gives no tags and a warning.
 */
class PlaceTags(
    private val open: () -> InputStream,
    private val rideStore: RideStore,
    private val log: WarnLog,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var matcher: PlaceMatcher? = null

    /** The tags trip [tripId] passed, in the order first met; empty when none or on failure. */
    suspend fun of(tripId: Long): List<String> {
        val matcher = matcher() ?: return emptyList()
        return try {
            val tracks = rideStore.segments(tripId)
                .filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }
                .sortedBy { it.startedAt }
                .map { segment -> rideStore.points(segment.id).map { it.point } }
                .filter { it.isNotEmpty() }
            if (tracks.isEmpty()) emptyList() else withContext(Dispatchers.Default) { matcher.tags(tracks) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not match places of trip $tripId", e)
            emptyList()
        }
    }

    private suspend fun matcher(): PlaceMatcher? = mutex.withLock {
        if (!loaded) {
            matcher = try {
                withContext(Dispatchers.IO) { open().use { PlaceMatcher(PlaceData.read(it)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not read the place data", e)
                null
            }
            loaded = true
        }
        matcher
    }
}
