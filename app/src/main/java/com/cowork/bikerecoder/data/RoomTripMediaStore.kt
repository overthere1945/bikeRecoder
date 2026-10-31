package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import com.cowork.bikerecoder.video.TripMediaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTripMediaStore(db: AppDatabase) : TripMediaStore {
    private val dao = db.tripMediaDao()

    override fun observe(tripId: Long): Flow<List<TripMedia>> =
        dao.observe(tripId).map { list -> list.map { it.toModel() } }

    override fun observeVideos(): Flow<List<TripMedia>> =
        dao.observeVideos().map { list -> list.map { it.toModel() } }

    override suspend fun add(m: TripMedia): Long = dao.insert(m.toEntity().copy(id = 0))

    override suspend fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        dao.delete(ids.toList())
    }
}

internal fun TripMediaEntity.toModel() = TripMedia(
    id = id,
    tripId = tripId,
    kind = MediaKind.valueOf(kind),
    uri = uri,
    createdAt = createdAt,
    optionsJson = optionsJson,
)

internal fun TripMedia.toEntity() = TripMediaEntity(
    id = id,
    tripId = tripId,
    kind = kind.name,
    uri = uri,
    createdAt = createdAt,
    optionsJson = optionsJson,
)
