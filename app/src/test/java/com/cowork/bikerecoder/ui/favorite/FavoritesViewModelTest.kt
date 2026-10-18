package com.cowork.bikerecoder.ui.favorite

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.favorite.FakeFavoriteStore
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.favorite.FavoriteRepository
import com.cowork.bikerecoder.favorite.FavoriteStore
import com.cowork.bikerecoder.nav.RecordingLog
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchError
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.pick.PickedPoint
import kotlinx.coroutines.CompletableDeferred
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

    /** Addresses and nearby places by point; [nearbyGate] holds every nearby answer until completed. */
    private val addresses = mutableMapOf<GeoPoint, String?>()
    private val nearbyPlaces = mutableMapOf<GeoPoint, SearchResult<List<Place>>>()
    private var nearbyGate: CompletableDeferred<Unit>? = null
    private var nearbyThrows = false
    private val nearbyAsked = mutableListOf<GeoPoint>()
    private val addressAsked = mutableListOf<GeoPoint>()

    private val search = object : PlaceSearch {
        override suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>> = SearchResult.Ok(emptyList())

        override suspend fun addressOf(point: GeoPoint): SearchResult<String?> {
            addressAsked += point
            return SearchResult.Ok(addresses[point])
        }

        override suspend fun nearby(point: GeoPoint): SearchResult<List<Place>> {
            nearbyAsked += point
            nearbyGate?.await()
            if (nearbyThrows) throw IOException("offline")
            return nearbyPlaces[point] ?: SearchResult.Ok(emptyList())
        }
    }

    private fun near(vararg names: String) = SearchResult.Ok(names.mapIndexed { i, n -> Place(n, "", p1, 10 * (i + 1)) })

    private fun fix(point: GeoPoint, accuracyM: Float = 8f) = LocationFix(point, accuracyM, null, null, 0L)

    /** A loaded name dialog: [initial] in the field, the nearby names then the address as suggestions. */
    private fun named(
        place: FavoritePlace,
        initial: String = place.name,
        suggestions: List<String> = listOfNotNull(place.address),
        error: String? = null,
        register: Boolean = true,
        warning: String? = null,
    ) = FavoriteDialog.NameFavorite(
        place = place,
        initialName = initial,
        suggestions = suggestions,
        loadingSuggestions = false,
        error = error,
        register = register,
        accuracyWarning = warning,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: FakeFavoriteStore) =
        FavoritesViewModel(FavoriteRepository(store) { now++ }, search, log)

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
        val vm = FavoritesViewModel(FavoriteRepository(store) { now++ }, search, log)
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
        val vm = FavoritesViewModel(FavoriteRepository(failing) { now++ }, search, log)
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
        assertEquals("즐겨찾기에 저장했습니다", FavoriteEvent.Registered(FavoriteKind.CUSTOM).message)
    }

    @Test
    fun saveAsCustomAsksForANameFirst() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        assertEquals(named(place1), vm.dialog.value)
        assertEquals(emptyList<Favorite>(), store.current)
        assertEquals(emptyList<FavoriteEvent>(), events)
    }

    @Test
    fun namingACustomRegistrationSavesAndTells() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("  카페  ")
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.CUSTOM)), events)
        val cafe = store.current.single()
        assertEquals(FavoriteKind.CUSTOM, cafe.kind)
        assertEquals("카페", cafe.name)
        assertEquals(p1, cafe.point)
        assertEquals("주소1", cafe.address)
    }

    @Test
    fun customRegistrationRefusesADuplicateName() = runTest {
        val store = FakeFavoriteStore(listOf(custom(1, "카페")))
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("카페")
        settle()
        assertEquals(named(place1, error = FavoriteRepository.MSG_DUPLICATE), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(1, store.current.size)

        // Still open: a new name saves.
        vm.saveCustom("카페2")
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.CUSTOM)), events)
    }

    @Test
    fun customRegistrationRefusesABlankOrLongName() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("   ")
        settle()
        assertEquals(named(place1, error = FavoriteRepository.MSG_NAME_LENGTH), vm.dialog.value)

        vm.saveCustom("가".repeat(21))
        settle()
        assertEquals(named(place1, error = FavoriteRepository.MSG_NAME_LENGTH), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(emptyList<Favorite>(), store.current)
    }

    @Test
    fun customRegistrationWhenFullIsRefused() = runTest {
        val store = FakeFavoriteStore((1L..20L).map { custom(it, "이름$it") })
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("새")
        settle()
        assertEquals(named(place1, error = FavoriteRepository.MSG_LIMIT), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(20, store.current.size)
    }

    @Test
    fun customRegistrationStoreFailureShowsSaveFailed() = runTest {
        val vm = viewModel(FakeFavoriteStore().apply { failWrites = true })
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("카페")
        settle()
        assertEquals(named(place1, error = "즐겨찾기를 저장하지 못했습니다"), vm.dialog.value)
        assertEquals(emptyList<FavoriteEvent>(), events)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun saveCustomOutsideItsDialogDoesNothing() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        vm.saveCustom("카페")
        vm.startSave(place1)
        vm.saveCustom("카페")
        settle()
        assertEquals(FavoriteDialog.ChooseName(place1), vm.dialog.value)
        assertEquals(emptyList<Favorite>(), store.current)
    }

    @Test
    fun startAddIsAllowedBelowTheLimit() = runTest {
        val vm = viewModel(FakeFavoriteStore((1L..19L).map { custom(it, "이름$it") }))
        settle()
        assertEquals(true, vm.startAdd())
        assertNull(vm.dialog.value)
    }

    @Test
    fun startAddWhenFullShowsTheLimit() = runTest {
        val full = (1L..18L).map { custom(it, "이름$it") } +
            Favorite(19, FavoriteKind.HOME, "집", p1, null, 19) + Favorite(20, FavoriteKind.WORK, "회사", p1, null, 20)
        val vm = viewModel(FakeFavoriteStore(full))
        settle()
        assertEquals(false, vm.startAdd())
        assertEquals(FavoriteDialog.Message("즐겨찾기는 20개까지 저장할 수 있습니다"), vm.dialog.value)
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

    // --- Task 1 minor (b): nothing is decided on the placeholder list ---

    @Test
    fun startAddBeforeTheListHasLoadedDoesNothing() = runTest {
        val store = SlowFavoriteStore(FakeFavoriteStore((1L..20L).map { custom(it, "이름$it") }))
        val vm = FavoritesViewModel(FavoriteRepository(store) { now++ }, search, log)
        settle()
        assertEquals(false, vm.favoritesLoaded.value)
        // Neither "one more fits" (the list only looks empty) nor the limit message.
        assertEquals(false, vm.startAdd())
        assertNull(vm.dialog.value)

        store.send(store.all())
        settle()
        assertEquals(false, vm.startAdd())
        assertEquals(FavoriteDialog.Message("즐겨찾기는 20개까지 저장할 수 있습니다"), vm.dialog.value)
    }

    // --- Spec §8.3: [📍 현재 위치] ---

    @Test
    fun currentLocationWithoutAFixAsksToTryAgain() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        vm.registerCurrent(FavoriteKind.HOME, null)
        settle()
        assertEquals(FavoriteDialog.Message("현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요"), vm.dialog.value)
        assertEquals(emptyList<GeoPoint>(), addressAsked)
        assertEquals(emptyList<Favorite>(), store.current)

        vm.registerCurrent(FavoriteKind.CUSTOM, null)
        assertEquals(FavoriteDialog.Message("현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요"), vm.dialog.value)
    }

    @Test
    fun currentLocationAsHomeConfirmsThenSavesAndTells() = runTest {
        addresses[p1] = "부산 해운대구 우동 1"
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.registerCurrent(FavoriteKind.HOME, fix(p1))
        settle()
        val here = FavoritePlace("부산 해운대구 우동 1", p1, "부산 해운대구 우동 1")
        assertEquals(
            FavoriteDialog.ConfirmCurrent(FavoriteKind.HOME, here, "현재 위치를 집으로 저장할까요?", accuracyWarning = null),
            vm.dialog.value,
        )
        assertEquals(emptyList<Favorite>(), store.current)

        vm.confirmCurrent()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.HOME)), events)
        val home = store.current.single()
        assertEquals(FavoriteKind.HOME, home.kind)
        assertEquals(p1, home.point)
        assertEquals("부산 해운대구 우동 1", home.address)
    }

    @Test
    fun currentLocationAsWorkWithAnExistingWorkAsksToReplace() = runTest {
        val store = FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.WORK, "회사", p2, null, 0)))
        val vm = viewModel(store)
        val events = events(vm)
        vm.registerCurrent(FavoriteKind.WORK, fix(p1))
        settle()
        val confirm = vm.dialog.value as FavoriteDialog.ConfirmCurrent
        assertEquals("현재 위치를 회사로 저장할까요?", confirm.question)

        vm.confirmCurrent()
        settle()
        assertEquals(
            FavoriteDialog.ConfirmReplace(confirm.place, FavoriteKind.WORK, "기존 회사를 이 장소로 바꿀까요?", register = true),
            vm.dialog.value,
        )
        vm.confirmReplace()
        settle()
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.WORK)), events)
        assertEquals(p1, store.current.single().point)
    }

    @Test
    fun currentLocationWithoutAnAddressIsNamedByItsCoordinates() = runTest {
        val vm = viewModel(FakeFavoriteStore())
        vm.registerCurrent(FavoriteKind.HOME, fix(GeoPoint(37.56, 126.829)))
        settle()
        val confirm = vm.dialog.value as FavoriteDialog.ConfirmCurrent
        assertEquals(FavoritePlace("37.56000, 126.82900", GeoPoint(37.56, 126.829), null), confirm.place)
    }

    @Test
    fun aLargeAccuracyIsWarnedRounded() = runTest {
        val vm = viewModel(FakeFavoriteStore())
        vm.registerCurrent(FavoriteKind.HOME, fix(p1, accuracyM = 87.5f))
        settle()
        assertEquals("위치 오차가 큽니다(약 88m)", (vm.dialog.value as FavoriteDialog.ConfirmCurrent).accuracyWarning)

        vm.dismiss()
        vm.registerCurrent(FavoriteKind.HOME, fix(p1, accuracyM = 50f))
        settle()
        assertNull((vm.dialog.value as FavoriteDialog.ConfirmCurrent).accuracyWarning)

        vm.dismiss()
        vm.registerCurrent(FavoriteKind.WORK, fix(p1, accuracyM = 50.4f))
        settle()
        assertEquals("위치 오차가 큽니다(약 50m)", (vm.dialog.value as FavoriteDialog.ConfirmCurrent).accuracyWarning)
    }

    @Test
    fun dismissingTheCurrentLocationQuestionSavesNothing() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.registerCurrent(FavoriteKind.HOME, fix(p1))
        settle()
        vm.dismiss()
        vm.confirmCurrent()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(emptyList<Favorite>(), store.current)
        assertEquals(emptyList<FavoriteEvent>(), events)
    }

    @Test
    fun currentLocationAsAFavoriteIsNamedAfterTheNearestPlace() = runTest {
        addresses[p1] = "부산 해운대구 우동 1"
        nearbyPlaces[p1] = near("GS25 우동점", "스타벅스", "우동초등학교")
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.registerCurrent(FavoriteKind.CUSTOM, fix(p1, accuracyM = 120f))
        settle()
        val here = FavoritePlace("부산 해운대구 우동 1", p1, "부산 해운대구 우동 1")
        assertEquals(
            named(
                here,
                initial = "GS25 우동점",
                suggestions = listOf("GS25 우동점", "스타벅스", "우동초등학교", "부산 해운대구 우동 1"),
                warning = "위치 오차가 큽니다(약 120m)",
            ),
            vm.dialog.value,
        )
        assertEquals(listOf(p1), nearbyAsked)

        vm.saveCustom("우리 동네 편의점")
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.CUSTOM)), events)
        assertEquals("우리 동네 편의점", store.current.single().name)
        assertEquals(p1, store.current.single().point)
    }

    @Test
    fun withNoPlaceNearbyTheNameIsTheAddressElseEmpty() = runTest {
        addresses[p1] = "부산 해운대구 우동 1"
        val vm = viewModel(FakeFavoriteStore())
        vm.registerCurrent(FavoriteKind.CUSTOM, fix(p1))
        settle()
        val here = FavoritePlace("부산 해운대구 우동 1", p1, "부산 해운대구 우동 1")
        assertEquals(named(here, initial = "부산 해운대구 우동 1"), vm.dialog.value)

        vm.dismiss()
        vm.registerCurrent(FavoriteKind.CUSTOM, fix(p2))
        settle()
        val nowhere = FavoritePlace("35.20000, 129.10000", p2, null)
        assertEquals(named(nowhere, initial = "", suggestions = emptyList()), vm.dialog.value)
    }

    @Test
    fun theNameDialogOpensAtOnceAndFillsWhenTheSuggestionsArrive() = runTest {
        val gate = CompletableDeferred<Unit>().also { nearbyGate = it }
        nearbyPlaces[p1] = near("스타벅스")
        val picked = PickedPoint(p1, "부산 해운대구 우동 1")
        val vm = viewModel(FakeFavoriteStore())
        vm.registerPicked(FavoriteKind.CUSTOM, picked)
        settle()
        val place = FavoritePlace("부산 해운대구 우동 1", p1, "부산 해운대구 우동 1")
        assertEquals(
            FavoriteDialog.NameFavorite(place, initialName = "", loadingSuggestions = true, register = true),
            vm.dialog.value,
        )

        gate.complete(Unit)
        settle()
        assertEquals(named(place, initial = "스타벅스", suggestions = listOf("스타벅스", "부산 해운대구 우동 1")), vm.dialog.value)
    }

    @Test
    fun failingSuggestionsLeaveTheAddressOnly() = runTest {
        val vm = viewModel(FakeFavoriteStore())
        nearbyPlaces[p1] = SearchResult.Err(SearchError.NETWORK)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        assertEquals(named(place1, suggestions = listOf("주소1")), vm.dialog.value)

        vm.dismiss()
        nearbyThrows = true
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        assertEquals(named(place1, suggestions = listOf("주소1")), vm.dialog.value)
    }

    // --- Spec §8.3: [🗺 지도에서 선택] ---

    @Test
    fun aPointPickedAsHomeIsSavedAtOnce() = runTest {
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.registerPicked(FavoriteKind.HOME, PickedPoint(p1, "부산 해운대구 우동 1"))
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.HOME)), events)
        val home = store.current.single()
        assertEquals(FavoriteKind.HOME, home.kind)
        assertEquals(p1, home.point)
        assertEquals("부산 해운대구 우동 1", home.address)
        assertEquals(emptyList<GeoPoint>(), nearbyAsked)
    }

    @Test
    fun aPointPickedAsWorkOverAnExistingWorkAsksToReplace() = runTest {
        val vm = viewModel(FakeFavoriteStore(listOf(Favorite(1, FavoriteKind.WORK, "회사", p2, null, 0))))
        vm.registerPicked(FavoriteKind.WORK, PickedPoint(p1, null))
        settle()
        assertEquals(
            FavoriteDialog.ConfirmReplace(
                FavoritePlace("지도에서 선택한 위치", p1, null),
                FavoriteKind.WORK,
                "기존 회사를 이 장소로 바꿀까요?",
                register = true,
            ),
            vm.dialog.value,
        )
    }

    @Test
    fun aPointPickedAsAFavoriteAsksItsName() = runTest {
        nearbyPlaces[p1] = near("해운대역", "카페")
        val vm = viewModel(FakeFavoriteStore())
        vm.registerPicked(FavoriteKind.CUSTOM, PickedPoint(p1, "부산 해운대구 우동 1"))
        settle()
        val place = FavoritePlace("부산 해운대구 우동 1", p1, "부산 해운대구 우동 1")
        assertEquals(
            named(place, initial = "해운대역", suggestions = listOf("해운대역", "카페", "부산 해운대구 우동 1")),
            vm.dialog.value,
        )
    }

    // --- Spec §8.4: the name dialog ---

    @Test
    fun aSearchResultKeepsItsOwnNameAndGetsSuggestions() = runTest {
        nearbyPlaces[p1] = near("장소1", "옆 가게")
        val vm = viewModel(FakeFavoriteStore())
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        assertEquals(named(place1, suggestions = listOf("장소1", "옆 가게", "주소1")), vm.dialog.value)
        assertEquals(listOf(p1), nearbyAsked)
    }

    @Test
    fun aLongNameIsCutToTwentyCharacters() = runTest {
        val long = FavoritePlace("가".repeat(25), p2, null)
        val vm = viewModel(FakeFavoriteStore())
        vm.saveAs(long, FavoriteKind.CUSTOM)
        settle()
        assertEquals("가".repeat(20), (vm.dialog.value as FavoriteDialog.NameFavorite).initialName)
    }

    @Test
    fun aDuplicateNameKeepsTheDialogWithItsSuggestions() = runTest {
        nearbyPlaces[p1] = near("카페")
        val store = FakeFavoriteStore(listOf(custom(1, "카페")))
        val vm = viewModel(store)
        vm.registerPicked(FavoriteKind.CUSTOM, PickedPoint(p1, "주소"))
        settle()
        vm.saveCustom("카페")
        settle()
        val place = FavoritePlace("주소", p1, "주소")
        assertEquals(
            named(place, initial = "카페", suggestions = listOf("카페", "주소"), error = FavoriteRepository.MSG_DUPLICATE),
            vm.dialog.value,
        )
        assertEquals(1, store.current.size)
    }

    @Test
    fun savingWhileSuggestionsLoadStillCloses() = runTest {
        val gate = CompletableDeferred<Unit>().also { nearbyGate = it }
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        vm.saveCustom("카페")
        settle()
        assertNull(vm.dialog.value)
        assertEquals(listOf(FavoriteEvent.Registered(FavoriteKind.CUSTOM)), events)

        gate.complete(Unit)
        settle()
        assertNull(vm.dialog.value)
        assertEquals("카페", store.current.single().name)
    }

    @Test
    fun aRefusalWhileSuggestionsLoadKeepsLoadingThem() = runTest {
        val gate = CompletableDeferred<Unit>().also { nearbyGate = it }
        nearbyPlaces[p1] = near("옆 가게")
        val vm = viewModel(FakeFavoriteStore(listOf(custom(1, "장소1"))))
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        vm.saveCustom("장소1")
        settle()
        assertEquals(
            FavoriteDialog.NameFavorite(place1, "장소1", loadingSuggestions = true, error = FavoriteRepository.MSG_DUPLICATE),
            vm.dialog.value,
        )
        gate.complete(Unit)
        settle()
        assertEquals(
            named(place1, suggestions = listOf("옆 가게", "주소1"), error = FavoriteRepository.MSG_DUPLICATE),
            vm.dialog.value,
        )
    }

    @Test
    fun dismissedWhileLoadingStaysClosed() = runTest {
        val gate = CompletableDeferred<Unit>().also { nearbyGate = it }
        val vm = viewModel(FakeFavoriteStore())
        vm.saveAs(place1, FavoriteKind.CUSTOM)
        settle()
        vm.dismiss()
        gate.complete(Unit)
        settle()
        assertNull(vm.dialog.value)
    }

    @Test
    fun theSheetsCustomEntryUsesTheNameDialogWithoutARegistration() = runTest {
        nearbyPlaces[p1] = near("옆 가게")
        val store = FakeFavoriteStore()
        val vm = viewModel(store)
        val events = events(vm)
        vm.startSave(place1)
        vm.startCustom()
        settle()
        assertEquals(named(place1, suggestions = listOf("옆 가게", "주소1"), register = false), vm.dialog.value)

        vm.saveCustom("카페")
        settle()
        assertNull(vm.dialog.value)
        assertEquals("카페", store.current.single().name)
        // A sheet save is not a register-mode save: no Toast, no going back.
        assertEquals(emptyList<FavoriteEvent>(), events)
    }

    @Test
    fun startCustomOutsideTheChooseDialogDoesNothing() = runTest {
        val vm = viewModel(FakeFavoriteStore())
        vm.startCustom()
        settle()
        assertNull(vm.dialog.value)
        assertEquals(emptyList<GeoPoint>(), nearbyAsked)
    }
}
