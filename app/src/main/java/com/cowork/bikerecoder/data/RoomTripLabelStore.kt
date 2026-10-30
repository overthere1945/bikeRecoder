package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTripLabelStore(db: AppDatabase) : TripLabelStore {
    private val dao = db.tripLabelDao()

    override suspend fun label(tripId: Long): TripLabel = dao.label(tripId)?.toModel() ?: TripLabel()

    override fun observeLabels(): Flow<Map<Long, TripLabel>> =
        dao.observeAll().map { rows -> rows.associate { it.tripId to it.toModel() } }

    override suspend fun setCustomTitle(tripId: Long, title: String?) = dao.setCustomTitle(tripId, title)

    override suspend fun setRegionName(tripId: Long, name: String) = dao.setRegionName(tripId, name)
}

internal fun TripLabelEntity.toModel() = TripLabel(customTitle = customTitle, regionName = regionName)
