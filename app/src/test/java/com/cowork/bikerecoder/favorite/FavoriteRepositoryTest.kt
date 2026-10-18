package com.cowork.bikerecoder.favorite

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FavoriteRepositoryTest {
    private val store = FakeFavoriteStore()
    private var now = 0L
    private val repo = FavoriteRepository(store) { ++now }
    private val p = FavoritePlace("스타벅스 역삼점", GeoPoint(37.50, 127.03), "서울 강남구 역삼동")
    private val q = FavoritePlace("다른 곳", GeoPoint(37.60, 127.10), null)

    @Test fun homeAndWorkAreOneEach() = runTest {
        assertEquals(FavoriteResult.Saved, repo.save(FavoriteKind.HOME, null, p))
        val first = store.current.single()
        assertEquals("집", first.name)

        val second = repo.save(FavoriteKind.HOME, null, q)
        assertEquals(FavoriteResult.ConfirmReplace("기존 집을 이 장소로 바꿀까요?"), second)
        assertEquals(listOf(first), store.current)

        assertEquals(FavoriteResult.Saved, repo.save(FavoriteKind.HOME, null, q, replace = true))
        val replaced = store.current.single()
        assertEquals(first.id, replaced.id)
        assertEquals(first.createdAt, replaced.createdAt)
        assertEquals(q.point, replaced.point)
        assertEquals(null, replaced.address)
    }

    @Test fun workReplaceQuestion() = runTest {
        repo.save(FavoriteKind.WORK, null, p)
        assertEquals(
            FavoriteResult.ConfirmReplace("기존 회사를 이 장소로 바꿀까요?"),
            repo.save(FavoriteKind.WORK, null, q),
        )
    }

    @Test fun duplicateCustomNameIsRejected() = runTest {
        assertEquals(FavoriteResult.Saved, repo.save(FavoriteKind.CUSTOM, "카페", p))
        assertEquals(
            FavoriteResult.Rejected("같은 이름의 즐겨찾기가 있습니다"),
            repo.save(FavoriteKind.CUSTOM, " 카페 ", q),
        )
        assertEquals(1, store.current.size)
    }

    @Test fun customNameMustBe1To20Chars() = runTest {
        val msg = FavoriteResult.Rejected("이름은 1~20자로 입력하세요")
        assertEquals(msg, repo.save(FavoriteKind.CUSTOM, "", p))
        assertEquals(msg, repo.save(FavoriteKind.CUSTOM, "   ", p))
        assertEquals(msg, repo.save(FavoriteKind.CUSTOM, null, p))
        assertEquals(msg, repo.save(FavoriteKind.CUSTOM, "가".repeat(21), p))
        assertTrue(store.current.isEmpty())
        assertEquals(FavoriteResult.Saved, repo.save(FavoriteKind.CUSTOM, "가".repeat(20), p))
    }

    @Test fun twentyIsTheLimit() = runTest {
        val limit = FavoriteResult.Rejected("즐겨찾기는 20개까지 저장할 수 있습니다")
        repeat(20) { assertEquals(FavoriteResult.Saved, repo.save(FavoriteKind.CUSTOM, "c$it", p)) }
        assertEquals(limit, repo.save(FavoriteKind.CUSTOM, "extra", p))
        assertEquals(limit, repo.save(FavoriteKind.HOME, null, p))
        assertEquals(20, store.current.size)

        val store2 = FakeFavoriteStore()
        val repo2 = FavoriteRepository(store2) { ++now }
        repo2.save(FavoriteKind.HOME, null, p)
        repeat(19) { repo2.save(FavoriteKind.CUSTOM, "c$it", p) }
        assertEquals(20, store2.current.size)
        assertEquals(FavoriteResult.Saved, repo2.save(FavoriteKind.HOME, null, q, replace = true))
        assertEquals(20, store2.current.size)
        assertEquals(q.point, store2.current.single { it.kind == FavoriteKind.HOME }.point)
    }

    @Test fun sortedHomeWorkThenCustomByCreation() = runTest {
        repo.save(FavoriteKind.CUSTOM, "B", p)
        repo.save(FavoriteKind.WORK, null, p)
        repo.save(FavoriteKind.CUSTOM, "A", p)
        repo.save(FavoriteKind.HOME, null, p)
        assertEquals(listOf("집", "회사", "B", "A"), repo.favorites.first().map { it.name })
    }

    @Test fun chipLabels() = runTest {
        repo.save(FavoriteKind.HOME, null, p)
        repo.save(FavoriteKind.WORK, null, p)
        repo.save(FavoriteKind.CUSTOM, "카페", p)
        assertEquals(listOf("🏠 집", "🏢 회사", "★ 카페"), repo.favorites.first().map { it.chipLabel })
    }

    @Test fun renameKeepsTheRules() = runTest {
        repo.save(FavoriteKind.CUSTOM, "A", p)
        repo.save(FavoriteKind.CUSTOM, "B", p)
        val b = store.current.single { it.name == "B" }

        assertEquals(FavoriteResult.Rejected("같은 이름의 즐겨찾기가 있습니다"), repo.rename(b.id, "A"))
        val writes = store.writeCount
        assertEquals(FavoriteResult.Saved, repo.rename(b.id, "B"))
        assertEquals(writes, store.writeCount)
        assertEquals(FavoriteResult.Saved, repo.rename(b.id, " C "))
        assertEquals("C", store.current.single { it.id == b.id }.name)
        assertEquals(FavoriteResult.Rejected("이름은 1~20자로 입력하세요"), repo.rename(b.id, " "))
        assertEquals(FavoriteResult.Saved, repo.rename(999L, "Z"))
    }

    @Test fun deleteRemoves() = runTest {
        repo.save(FavoriteKind.CUSTOM, "A", p)
        repo.save(FavoriteKind.CUSTOM, "B", p)
        repo.delete(store.current.first { it.name == "A" }.id)
        assertEquals(listOf("B"), store.current.map { it.name })
    }

    @Test fun concurrentHomeSavesKeepOneHome() = runTest {
        val a = async { repo.save(FavoriteKind.HOME, null, p) }
        val b = async { repo.save(FavoriteKind.HOME, null, q) }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it == FavoriteResult.Saved })
        assertEquals(1, results.count { it is FavoriteResult.ConfirmReplace })
        assertEquals(1, store.current.count { it.kind == FavoriteKind.HOME })
    }
}
