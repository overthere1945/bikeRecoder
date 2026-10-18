package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoriteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFavoriteStore(db: AppDatabase) : FavoriteStore {
    private val dao = db.favoriteDao()

    override fun observeAll(): Flow<List<Favorite>> = dao.observeAll().map { list -> list.map { it.toFavorite() } }

    override suspend fun all(): List<Favorite> = dao.all().map { it.toFavorite() }

    override suspend fun insert(favorite: Favorite): Long = dao.insert(favorite.toEntity())

    override suspend fun update(favorite: Favorite) = dao.update(favorite.toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)
}

internal fun FavoritePlaceEntity.toFavorite() = Favorite(
    id = id,
    kind = FavoriteKind.valueOf(kind),
    name = name,
    point = GeoPoint(lat, lon),
    address = address,
    createdAt = createdAt,
)

internal fun Favorite.toEntity() = FavoritePlaceEntity(
    id = id,
    kind = kind.name,
    name = name,
    lat = point.lat,
    lon = point.lon,
    address = address,
    createdAt = createdAt,
)
