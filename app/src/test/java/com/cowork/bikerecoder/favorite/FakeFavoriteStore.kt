package com.cowork.bikerecoder.favorite

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield

/** 메모리 즐겨찾기 저장소. all()·insert()는 yield로 양보해, 잠금 없는 구현이면 동시 저장이 실제로 끼어든다. */
class FakeFavoriteStore(initial: List<Favorite> = emptyList()) : FavoriteStore {
    private val state = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    var failWrites = false
    var writeCount = 0
        private set

    val current: List<Favorite> get() = state.value

    override fun observeAll(): Flow<List<Favorite>> = state

    override suspend fun all(): List<Favorite> {
        yield()
        return state.value
    }

    override suspend fun insert(favorite: Favorite): Long {
        yield()
        if (failWrites) throw IOException("insert failed")
        writeCount++
        val id = if (favorite.id == 0L) nextId++ else favorite.id
        state.value = state.value + favorite.copy(id = id)
        return id
    }

    override suspend fun update(favorite: Favorite) {
        if (failWrites) throw IOException("update failed")
        writeCount++
        state.value = state.value.map { if (it.id == favorite.id) favorite else it }
    }

    override suspend fun delete(id: Long) {
        if (failWrites) throw IOException("delete failed")
        writeCount++
        state.value = state.value.filterNot { it.id == id }
    }
}
