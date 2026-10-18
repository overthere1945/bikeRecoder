package com.cowork.bikerecoder.favorite

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FavoriteKind { HOME, WORK, CUSTOM }

data class Favorite(
    val id: Long,
    val kind: FavoriteKind,
    val name: String,
    val point: GeoPoint,
    val address: String?,
    val createdAt: Long,
) {
    /** "🏠 집" / "🏢 회사" / "★ {name}" */
    val chipLabel: String
        get() = when (kind) {
            FavoriteKind.HOME -> "🏠 $name"
            FavoriteKind.WORK -> "🏢 $name"
            FavoriteKind.CUSTOM -> "★ $name"
        }
}

/** 저장하려는 장소: 시트 제목(검색 결과 이름, 길게 누르기는 주소 또는 좌표), 위치, 주소(없으면 null). */
data class FavoritePlace(val name: String, val point: GeoPoint, val address: String?)

interface FavoriteStore {
    fun observeAll(): Flow<List<Favorite>> // 순서 없음
    suspend fun all(): List<Favorite>
    suspend fun insert(favorite: Favorite): Long // id = 0 → 새 id
    suspend fun update(favorite: Favorite)
    suspend fun delete(id: Long)
}

sealed interface FavoriteResult {
    data object Saved : FavoriteResult

    /** 집·회사가 이미 있음: [question]을 묻고 save(..., replace = true)로 다시 부른다. */
    data class ConfirmReplace(val question: String) : FavoriteResult

    data class Rejected(val message: String) : FavoriteResult
}

class FavoriteRepository(private val store: FavoriteStore, private val clock: () -> Long) {
    private val writeLock = Mutex()

    /** 집 → 회사 → 직접 입력(createdAt, 같으면 id 순). */
    val favorites: Flow<List<Favorite>> = store.observeAll().map { list ->
        list.sortedWith(compareBy<Favorite> { it.kind.ordinal }.thenBy { it.createdAt }.thenBy { it.id })
    }

    /** HOME/WORK는 [customName]을 무시하고 이름 "집"/"회사". */
    suspend fun save(
        kind: FavoriteKind,
        customName: String?,
        place: FavoritePlace,
        replace: Boolean = false,
    ): FavoriteResult = writeLock.withLock {
        val existing = store.all()
        when (kind) {
            FavoriteKind.HOME, FavoriteKind.WORK -> {
                val same = existing.firstOrNull { it.kind == kind }
                if (same != null) {
                    if (!replace) return@withLock FavoriteResult.ConfirmReplace(replaceQuestion(kind))
                    store.update(same.copy(point = place.point, address = place.address))
                    return@withLock FavoriteResult.Saved
                }
                if (existing.size >= MAX_COUNT) return@withLock FavoriteResult.Rejected(MSG_LIMIT)
                val name = if (kind == FavoriteKind.HOME) HOME_NAME else WORK_NAME
                store.insert(Favorite(0, kind, name, place.point, place.address, clock()))
                FavoriteResult.Saved
            }
            FavoriteKind.CUSTOM -> {
                val name = customName.orEmpty().trim()
                checkName(name, existing, selfId = null)?.let { return@withLock it }
                if (existing.size >= MAX_COUNT) return@withLock FavoriteResult.Rejected(MSG_LIMIT)
                store.insert(Favorite(0, FavoriteKind.CUSTOM, name, place.point, place.address, clock()))
                FavoriteResult.Saved
            }
        }
    }

    /** CUSTOM만(require). 없는 id면 아무것도 하지 않고 Saved. */
    suspend fun rename(id: Long, name: String): FavoriteResult = writeLock.withLock {
        val existing = store.all()
        val target = existing.firstOrNull { it.id == id } ?: return@withLock FavoriteResult.Saved
        require(target.kind == FavoriteKind.CUSTOM) { "only custom favorites can be renamed" }
        val trimmed = name.trim()
        checkName(trimmed, existing, selfId = id)?.let { return@withLock it }
        if (trimmed != target.name) store.update(target.copy(name = trimmed))
        FavoriteResult.Saved
    }

    suspend fun delete(id: Long) {
        writeLock.withLock { store.delete(id) }
    }

    private fun checkName(name: String, existing: List<Favorite>, selfId: Long?): FavoriteResult.Rejected? {
        if (name.length !in 1..MAX_NAME_LENGTH) return FavoriteResult.Rejected(MSG_NAME_LENGTH)
        val duplicate = existing.any { it.kind == FavoriteKind.CUSTOM && it.id != selfId && it.name == name }
        return if (duplicate) FavoriteResult.Rejected(MSG_DUPLICATE) else null
    }

    companion object {
        const val MAX_COUNT = 20
        const val MAX_NAME_LENGTH = 20
        const val HOME_NAME = "집"
        const val WORK_NAME = "회사"
        const val MSG_DUPLICATE = "같은 이름의 즐겨찾기가 있습니다"
        const val MSG_LIMIT = "즐겨찾기는 20개까지 저장할 수 있습니다"
        const val MSG_NAME_LENGTH = "이름은 1~20자로 입력하세요"
        const val DELETE_QUESTION = "즐겨찾기에서 삭제할까요?"

        /** HOME → "기존 집을 이 장소로 바꿀까요?", WORK → "기존 회사를 이 장소로 바꿀까요?" */
        fun replaceQuestion(kind: FavoriteKind): String = when (kind) {
            FavoriteKind.HOME -> "기존 집을 이 장소로 바꿀까요?"
            FavoriteKind.WORK -> "기존 회사를 이 장소로 바꿀까요?"
            FavoriteKind.CUSTOM -> throw IllegalArgumentException("custom favorites are never replaced")
        }
    }
}
