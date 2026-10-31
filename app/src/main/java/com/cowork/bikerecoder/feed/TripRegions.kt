package com.cowork.bikerecoder.feed

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.trip.TripLabelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The region name of a trip ("서울 강서구", spec §2.5), looked up once with [lookup] and stored in [labels]. A trip whose
 * lookup failed, timed out or found nothing is not asked again until the app restarts (ruling D3: the set is in
 * process memory); a found name is in the label table and never looked up again. Callers for one trip take turns, so
 * two at once cost one lookup.
 */
class TripRegions(
    private val labels: TripLabelStore,
    /** "서울 강서구" for the point, or null (no key, failure, nothing found). */
    private val lookup: suspend (GeoPoint) -> String?,
    private val log: WarnLog,
    private val timeoutMs: Long = REGION_TIMEOUT_MS,
) {
    private val triedWithoutLuck: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /**
     * The stored region of [tripId]; else, the first time in this process, the region of [start] (the ride's first
     * point), stored. Null when there is none: the lookup failed or took over [timeoutMs] (then given up), or found nothing.
     */
    suspend fun ensure(tripId: Long, start: GeoPoint): String? {
        stored(tripId)?.let { return it }
        if (tripId in triedWithoutLuck) return null
        return locks.getOrPut(tripId) { Mutex() }.withLock {
            // Whoever held the lock before may have found it (or given up).
            stored(tripId)?.let { return@withLock it }
            if (tripId in triedWithoutLuck) return@withLock null
            val found = ask(tripId, start)
            if (found == null) {
                triedWithoutLuck += tripId
                return@withLock null
            }
            try {
                labels.setRegionName(tripId, found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not store the region of trip $tripId", e)
            }
            found
        }
    }

    private suspend fun stored(tripId: Long): String? = try {
        labels.label(tripId).regionName?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the region of trip $tripId", e)
        null
    }

    private suspend fun ask(tripId: Long, start: GeoPoint): String? = withTimeoutOrNull(timeoutMs) {
        try {
            lookup(start)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not look up the region of trip $tripId", e)
            null
        }
    }

    companion object {
        /** A lookup slower than this is given up (spec §3.8). */
        const val REGION_TIMEOUT_MS = 5_000L
    }
}
