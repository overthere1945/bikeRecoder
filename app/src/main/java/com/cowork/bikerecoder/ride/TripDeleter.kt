package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.recording.TripDeleteCondition
import com.cowork.bikerecoder.core.trip.OfflineMapController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Deletes a trip the one way every caller does (ruling R20): the trip row goes in one statement only if [condition]
 * still holds then ([RideStore.deleteTripIf] — COMPLETED, plus the condition on its segments), so a trip that became
 * ACTIVE or started recording meanwhile is never deleted; only after that, its offline maps (their refs have no
 * foreign key to the trip; a failure there is logged and leaves a map region behind), which run to the end even if
 * the caller is cancelled meanwhile.
 *
 * Callers: [ShortRides.discard] ([TripDeleteCondition.NO_SEGMENTS]), the history's delete
 * ([TripDeleteCondition.NOT_RECORDING]) and its [정리] ([TripDeleteCondition.SHORT_NOT_RECORDING]).
 */
class TripDeleter(
    private val rideStore: RideStore,
    private val offlineMaps: OfflineMapController,
    private val log: WarnLog,
) {
    /** True if the trip was deleted; a store failure is thrown. */
    suspend fun delete(tripId: Long, condition: TripDeleteCondition): Boolean {
        if (!rideStore.deleteTripIf(tripId, condition)) return false
        // The row is gone: finish its maps even if the caller is cancelled now (e.g. the list is left while they wait
        // for a running download), or their region would be left with no trip to delete it from.
        withContext(NonCancellable) {
            try {
                offlineMaps.deleteForTrip(tripId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not delete the offline maps of trip $tripId", e)
            }
        }
        return true
    }
}
