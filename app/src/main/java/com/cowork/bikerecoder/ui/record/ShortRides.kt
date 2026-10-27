package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Short rides at the summary (spec §6.10): a finished segment under [MIN_RIDE_DISTANCE_M] (GPS jitter while standing
 * still) is asked about once — [answered] is app-wide, so the record screen's and the guidance screen's summaries of the
 * same segment do not ask twice — and [discard] removes it.
 */
class ShortRides(
    private val rideStore: RideStore,
    private val tripStore: TripStore,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
) {
    private val _answered = MutableStateFlow<Set<Long>>(emptySet())

    /** Segments whose question was answered ([버리기], [저장] or dismissed); never asked again. */
    val answered: StateFlow<Set<Long>> = _answered.asStateFlow()

    fun answer(segmentId: Long) {
        _answered.update { it + segmentId }
    }

    /** True for a finished segment shorter than [MIN_RIDE_DISTANCE_M]. */
    suspend fun isShort(segmentId: Long): Boolean = rideStore.segment(segmentId)?.isShortAndFinished() == true

    /**
     * [버리기]: deletes the segment, if it is still a finished short one, and then its trip when no segment of it is left
     * (an open one counts) and it is not ACTIVE — with the trip's offline maps, as the history's delete does. Nothing
     * else is ever deleted. Failures are logged.
     */
    suspend fun discard(segmentId: Long) {
        try {
            val segment = rideStore.segment(segmentId) ?: return
            if (!segment.isShortAndFinished()) return
            rideStore.deleteSegment(segmentId)
            val tripId = segment.tripId
            if (rideStore.segments(tripId).isNotEmpty()) return
            val trip = tripStore.trip(tripId) ?: return
            if (trip.status == TripStatus.ACTIVE) return
            try {
                offlineMaps.deleteForTrip(tripId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not delete the offline maps of trip $tripId", e)
            }
            rideStore.deleteTrip(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not discard segment $segmentId", e)
        }
    }

    private fun RideSegment.isShortAndFinished() = endedAt != null && totals.distanceM < MIN_RIDE_DISTANCE_M
}
