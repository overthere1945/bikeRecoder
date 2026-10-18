package com.cowork.bikerecoder.ui.favorite

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.FakeFavoriteStore
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.favorite.FavoriteRepository
import com.cowork.bikerecoder.favorite.FavoriteStore
import com.cowork.bikerecoder.nav.RecordingLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val p1 = GeoPoint(35.1, 129.0)
    private val p2 = GeoPoint(35.2, 129.1)
    private val place1 = FavoritePlace("장소1", p1, "주소1")
    private val place2 = FavoritePlace("장소2", p2, null)

    private var now = 1_000L
    private val log = RecordingLog()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: FakeFavoriteStore) =
        FavoritesViewModel(FavoriteRepository(store) { now++ }, log)

    private fun custom(id: Long, name: String, at: Long = id) =
        Favorite(id, FavoriteKind.CUSTOM, name, p1, null, at)

    private fun TestScope.settle() = runCurrent()

    @Test
    fun saveHomeTwiceAsksToReplaceThenReplaces() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        vm.startSave(place1)
        assertEquals(FavoriteDialog.ChooseName(place1), vm.dialog.value)
        vm.save(FavoriteKind.HOME)
        settle()
        assertNull(vm.dialog.value)

        vm.startSave(place2)
        vm.save(FavoriteKind.HOME)
        settle()
        assertEquals(
            FavoriteDialog.ConfirmReplace(place2, FavoriteKind.HOME, "기존 집을 이 장소로 바꿀까요?"),
            vm.dialog.value,
        )
        vm.confirmReplace()
        settle()
        assertNull(vm.dialog.value)
        val homes = vm.favorites.value.filter { it.kind == FavoriteKind.HOME }
        assertEquals(1, homes.size)
        assertEquals(p2, homes.single().point)
    }

    @Test
    fun refusalStaysInTheNameDialog() = runTest {
        val vm = viewModel(FakeFavoriteStore(listOf(custom(1, "카페"))))
        vm.startSave(place1)
        vm.save(FavoriteKind.CUSTOM, "카페")
        settle()
        assertEquals(FavoriteDialog.ChooseName(place1, error = "같은 이름의 즐겨찾기가 있습니다"), vm.dialog.value)
    }

    @Test
    fun limitIsShownInTheNameDialog() = runTest {
        val vm = viewModel(FakeFavoriteStore((1L..20L).map { custom(it, "이름$it") }))
        vm.startSave(place1)
        vm.save(FavoriteKind.CUSTOM, "새")
        settle()
        assertEquals(FavoriteDialog.ChooseName(place1, error = "즐겨찾기는 20개까지 저장할 수 있습니다"), vm.dialog.value)
    }

    @Test
    fun deleteAsksFirst() = runTest {
        val store = FakeFavoriteStore(listOf(custom(1, "카페")))
        val vm = viewModel(store)
        settle()
        val cafe = vm.favorites.value.single()
        vm.askDelete(cafe)
        assertEquals(FavoriteDialog.ConfirmDelete(cafe), vm.dialog.value)
        vm.dismiss()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(1, store.current.size)

        vm.askDelete(cafe)
        vm.confirmDelete()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(emptyList<Favorite>(), store.current)
        assertEquals(emptyList<Favorite>(), vm.favorites.value)
    }

    @Test
    fun renameRefusalKeepsTheDialog() = runTest {
        val store = FakeFavoriteStore(listOf(custom(1, "카페"), custom(2, "학교")))
        val vm = viewModel(store)
        settle()
        val school = vm.favorites.value.first { it.name == "학교" }
        vm.startRename(school)
        assertEquals(FavoriteDialog.Rename(school), vm.dialog.value)

        vm.rename("카페")
        settle()
        assertEquals(FavoriteDialog.Rename(school, error = FavoriteRepository.MSG_DUPLICATE), vm.dialog.value)

        vm.rename("대학교")
        settle()
        assertNull(vm.dialog.value)
        assertEquals(setOf("카페", "대학교"), store.current.map { it.name }.toSet())
    }

    @Test
    fun startRenameIgnoresHomeAndWork() = runTest {
        val home = Favorite(1, FavoriteKind.HOME, "집", p1, null, 0)
        val vm = viewModel(FakeFavoriteStore(listOf(home)))
        vm.startRename(home)
        assertNull(vm.dialog.value)
    }

    @Test
    fun storeFailureShowsSaveFailed() = runTest {
        val store = FakeFavoriteStore().apply { failWrites = true }
        val vm = viewModel(store)
        vm.startSave(place1)
        vm.save(FavoriteKind.CUSTOM, "카페")
        settle()
        assertEquals(FavoriteDialog.ChooseName(place1, error = "즐겨찾기를 저장하지 못했습니다"), vm.dialog.value)
        assertEquals(1, log.messages.size)
    }

    private fun TestScope.events(vm: FavoritesViewModel): List<FavoriteEvent> {
        val received = mutableListOf<FavoriteEvent>()
        backgroundScope.launch { vm.events.collect { received += it } }
        return received
    }

    @Test
    fun saveAsHomeWithoutHomeSavesAndTellsOnce() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.HOME)
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.HOME)), events)
        val home = store.current.single()
        assertEquals(FavoriteKind.HOME, home.kind)
        assertEquals(p1, home.point)

        settle()
        assertEquals(1, events.size)
    }

    @Test
    fun saveAsHomeWithHomeAsksToReplaceThenTells() = runTest {
        val store = FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.HOME, "집", p1, null, 0)))
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place2, FavoriteKind.HOME)
        settle()
        assertEquals(
            FavoriteDialog.ConfirmReplace(place2, FavoriteKind.HOME, "기존 집을 이 장소로 바꿀까요?", register = true),
            vm.dialog.value,
        )
        assertEquals(emptyList<FavoriteEvent>(), events)

        vm.confirmReplace()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.HOME)), events)
        assertEquals(p2, store.current.single().point)
    }

    @Test
    fun saveAsHomeWhenFullIsRefusedWithoutAnEvent() = runTest {
        val full = (1L..19L).map { custom(it, "이름$it") } + Favorite(20, FavoriteKind.WORK, "회사", p1, null, 20)
        val store = FakeFavoriteStore(full)
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.HOME)
        settle()
        assertEquals(FavoriteDialog.Message("즐겨찾기는 20개까지 저장할 수 있습니다"), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(20, store.current.size)

        vm.dismiss()
        assertNull(vm.dialog.value)
    }

    @Test
    fun saveAsStoreFailureShowsSaveFailed() = runTest {
        val vm = viewModel(FakeFavoriteStore().apply { failWrites = true })
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.WORK)
        settle()
        assertEquals(FavoriteDialog.Message("즐겨찾기를 저장하지 못했습니다"), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
    }

    @Test
    fun saveAsReplaceStoreFailureShowsSaveFailedWithoutAnEvent() = runTest {
        val store = FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.HOME, "집", p1, null, 0)))
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place2, FavoriteKind.HOME)
        settle()
        assertEquals(
            FavoriteDialog.ConfirmReplace(place2, FavoriteKind.HOME, "기존 집을 이 장소로 바꿀까요?", register = true),
            vm.dialog.value,
        )

        store.failWrites = true
        vm.confirmReplace()
        settle()
        assertEquals(FavoriteDialog.Message("즐겨찾기를 저장하지 못했습니다"), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(p1, store.current.single().point)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun saveAsReplaceDismissedTellsNothing() = runTest {
        val store = FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.HOME, "집", p1, null, 0)))
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place2, FavoriteKind.HOME)
        settle()
        assertEquals(true, (vm.dialog.value as FavoriteDialog.ConfirmReplace).register)

        vm.dismiss()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(p1, store.current.single().point)
    }

    /** A store whose list has not arrived yet: [observeAll] emits only when [send] is called. */
    private class SlowFavoriteStore(private val fake: FakeFavoriteStore = FakeFavoriteStore()) : FavoriteStore by fake {
        private val gate = MutableSharedFlow<List<Favorite>>()
        override fun observeAll(): Flow<List<Favorite>> = gate
        suspend fun send(list: List<Favorite>) = gate.emit(list)
    }

    @Test
    fun favoritesAreNotLoadedUntilTheFirstList() = runTest {
        val store = SlowFavoriteStore()
        val vm = FavoritesViewModel(FavoriteRepository(store) { now++ }, log)
        settle()
        assertEquals(false, vm.favoritesLoaded.value)

        store.send(listOf(Favorite(1, FavoriteKind.HOME, "집", p1, null, 0)))
        settle()
        assertEquals(true, vm.favoritesLoaded.value)
        assertEquals(listOf("집"), vm.favorites.value.map { it.name })
    }

    @Test
    fun aFailingFavoritesFlowCountsAsLoaded() = runTest {
        val failing = object : FavoriteStore by FakeFavoriteStore() {
            override fun observeAll(): Flow<List<Favorite>> = flow { throw IOException("read failed") }
        }
        val vm = FavoritesViewModel(FavoriteRepository(failing) { now++ }, log)
        assertEquals(false, vm.favoritesLoaded.value)
        settle()
        assertEquals(true, vm.favoritesLoaded.value)
        assertEquals(emptyList<Favorite>(), vm.favorites.value)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun registeredToastNamesTheKind() {
        assertEquals("집으로 저장했습니다", FavoriteEvent.Registered(FavoriteKind.HOME).message)
        assertEquals("회사로 저장했습니다", FavoriteEvent.Registered(FavoriteKind.WORK).message)
    }

    @Test
    fun sheetSavesAreNotRegistrations() = runTest {
        val vm = viewModel(FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.HOME, "집", p1, null, 0))))
        val events = events(vm)
        vm.startSave(place2)
        vm.save(FavoriteKind.HOME)
        settle()
        vm.confirmReplace()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
    }

    @Test
    fun favoritesAreSortedForTheChips() = runTest {
        val store = FakeFavoriteStore(
            listOf(
                custom(1, "가게", at = 10),
                Favorite(2, FavoriteKind.WORK, "회사", p1, null, 30),
                custom(3, "공원", at = 20),
                Favorite(4, FavoriteKind.HOME, "집", p1, null, 40),
            ),
        )
        val vm = viewModel(store)
        assertEquals(emptyList<Favorite>(), vm.favorites.value)
        settle()
        assertEquals(listOf("집", "회사", "가게", "공원"), vm.favorites.value.map { it.name })
    }
}
