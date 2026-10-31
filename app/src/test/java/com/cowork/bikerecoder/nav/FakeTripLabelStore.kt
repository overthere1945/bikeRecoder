package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.trip.TripLabel
import com.cowork.bikerecoder.core.trip.TripLabelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [TripLabelStore]. */
class FakeTripLabelStore : TripLabelStore {
    val labels = MutableStateFlow<Map<Long, TripLabel>>(emptyMap())

    /** Region writes made, in order. */
    val regionWrites = mutableListOf<Pair<Long, String>>()

    override suspend fun label(tripId: Long): TripLabel = labels.value[tripId] ?: TripLabel()

    override fun observeLabels(): Flow<Map<Long, TripLabel>> = labels

    override suspend fun setCustomTitle(tripId: Long, title: String?) {
        val next = (labels.value[tripId] ?: TripLabel()).copy(customTitle = title)
        labels.value = if (next == TripLabel()) labels.value - tripId else labels.value + (tripId to next)
    }

    override suspend fun setRegionName(tripId: Long, name: String) {
        regionWrites += tripId to name
        labels.value = labels.value + (tripId to (labels.value[tripId] ?: TripLabel()).copy(regionName = name))
    }
}
