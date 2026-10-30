package com.cowork.bikerecoder.ui.feed

import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.stats.RideStat
import com.cowork.bikerecoder.core.stats.RideStatsSource
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

/** [RideStatsSource] over the fake ride store's closed segments, read again whenever [tick] is bumped. */
class FakeRideStats(private val segments: suspend () -> List<RideSegment>) : RideStatsSource {
    val tick = MutableStateFlow(0)

    override fun observeRides(): Flow<List<RideStat>> = tick.map {
        segments().filter { it.endedAt != null }.map {
            RideStat(it.id, it.tripId, it.startedAt, it.localDate, it.totals.distanceM, it.totals.movingTimeMs, it.totals.ascentM)
        }
    }
}

/** Records what the feed asks of the picture queue. */
class FakeFeedPictures : FeedPictures {
    val filesFlow = MutableStateFlow<Map<Long, File>>(emptyMap())
    override val files: Flow<Map<Long, File>> get() = filesFlow

    val wanted = mutableListOf<Pair<Long, String>>()
    val unwanted = mutableListOf<Long>()
    val deleted = mutableListOf<Long>()

    override fun want(tripId: Long, key: String) {
        wanted += tripId to key
    }

    override fun unwant(tripId: Long) {
        unwanted += tripId
    }

    override suspend fun delete(tripId: Long) {
        deleted += tripId
    }
}

/** Made videos and images; [observeVideos] hands them out in no particular order. */
class FakeFeedMedia : TripMediaStore {
    val items = MutableStateFlow<List<TripMedia>>(emptyList())

    override fun observe(tripId: Long): Flow<List<TripMedia>> =
        items.map { list -> list.filter { it.tripId == tripId }.sortedByDescending { it.createdAt } }

    override fun observeVideos(): Flow<List<TripMedia>> = items

    override suspend fun add(m: TripMedia): Long = error("not used")

    override suspend fun remove(ids: Collection<Long>) = error("not used")
}
