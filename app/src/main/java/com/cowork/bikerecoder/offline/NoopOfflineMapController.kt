package com.cowork.bikerecoder.offline

import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.trip.OfflineMapController

/** Placeholder until the real MapLibre offline controller replaces it (Task 20). */
class NoopOfflineMapController : OfflineMapController {
    override suspend fun deleteForTrip(tripId: Long) = Unit
    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) = Unit
}
