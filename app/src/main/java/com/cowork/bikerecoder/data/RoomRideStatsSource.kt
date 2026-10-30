package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class RoomRideStatsSource(db: AppDatabase) : RideStatsSource {
    private val dao = db.rideStatsDao()

    /**
     * The ended segments. The query follows `ride_segment`, which a recording writes every 5 s (its open segment's totals):
     * the same list is not sent again (review M4).
     */
    override fun observeRides(): Flow<List<RideStat>> = dao.observeEnded().map { rows ->
        rows.map {
            RideStat(
                segmentId = it.segmentId,
                tripId = it.tripId,
                startedAt = it.startedAt,
                localDate = LocalDate.parse(it.localDate),
                distanceM = it.distanceM,
                movingTimeMs = it.movingTimeMs,
                ascentM = it.ascentM,
                endedAt = it.endedAt,
            )
        }
    }.distinctUntilChanged()
}
