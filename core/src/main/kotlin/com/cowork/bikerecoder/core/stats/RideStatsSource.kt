package com.cowork.bikerecoder.core.stats

import kotlinx.coroutines.flow.Flow

interface RideStatsSource {
    /** 끝난 구간(`endedAt`이 있는 것) 전부, 거르지 않고. 구간이 바뀌면 다시 나온다. */
    fun observeRides(): Flow<List<RideStat>>
}
