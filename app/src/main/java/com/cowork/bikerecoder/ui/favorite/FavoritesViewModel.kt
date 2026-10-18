package com.cowork.bikerecoder.ui.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.favorite.FavoriteRepository
import com.cowork.bikerecoder.favorite.FavoriteResult
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import com.cowork.bikerecoder.ui.common.addressOrNull
import com.cowork.bikerecoder.ui.pick.PickedPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** The favorite dialog on screen (at most one). */
sealed interface FavoriteDialog {
    /**
     * "즐겨찾기에 저장": [집] [회사] [직접 입력] [취소]. [직접 입력]은 [NameFavorite](장소 이름을 미리 넣음, spec §8.4).
     * [error]는 마지막 거절 문구(대화상자는 열린 채).
     */
    data class ChooseName(val place: FavoritePlace, val error: String? = null) : FavoriteDialog

    /** [register]: asked by [FavoritesViewModel.saveAs]; a confirmed replace then tells [FavoriteEvent.Registered]. */
    data class ConfirmReplace(
        val place: FavoritePlace,
        val kind: FavoriteKind,
        val question: String,
        val register: Boolean = false,
    ) : FavoriteDialog

    /** A refused or failed [FavoritesViewModel.saveAs]: [text] [확인]. */
    data class Message(val text: String) : FavoriteDialog

    data class Rename(val favorite: Favorite, val error: String? = null) : FavoriteDialog

    /**
     * [📍 현재 위치] for home or work (spec §8.3): [question] ("현재 위치를 집으로 저장할까요?"), the address or the
     * coordinates ([FavoritePlace.name] of [place]), [accuracyWarning] when the fix is rough, [저장] [취소].
     * [FavoritesViewModel.confirmCurrent] saves it like a tapped result.
     */
    data class ConfirmCurrent(
        val kind: FavoriteKind,
        val place: FavoritePlace,
        val question: String,
        val accuracyWarning: String?,
    ) : FavoriteDialog

    /**
     * "즐겨찾기 이름" (spec §8.4): the name of a custom favorite for [place], [저장] [취소];
     * [FavoritesViewModel.saveCustom] saves it. [initialName] goes into the field (the place name for a search result;
     * the nearest place, else the address, for the current location or a map pick — known once [loadingSuggestions]
     * ends). [suggestions]: "추천 이름" chips (nearby place names, then the address). [error] is the last refusal (the
     * dialog stays open). [register]: asked by the register-mode search, so a save tells [FavoriteEvent.Registered]
     * (the sheet's [직접 입력] does not). [accuracyWarning]: the current location was rough.
     */
    data class NameFavorite(
        val place: FavoritePlace,
        val initialName: String,
        val suggestions: List<String> = emptyList(),
        val loadingSuggestions: Boolean = false,
        val error: String? = null,
        val register: Boolean,
        val accuracyWarning: String? = null,
    ) : FavoriteDialog {
        /** The same request (place and origin), whatever its field state: a save or the suggestions may land on it. */
        fun sameRequest(other: FavoriteDialog?): Boolean =
            other is NameFavorite && other.place == place && other.register == register
    }

    /** "즐겨찾기에서 삭제할까요?" [삭제] [취소] */
    data class ConfirmDelete(val favorite: Favorite) : FavoriteDialog
}

sealed interface FavoriteEvent {
    /** The register-mode search saved its place as home, work or (named) a custom favorite ([kind]). */
    data class Registered(val kind: FavoriteKind) : FavoriteEvent {
        /** The Toast: "집으로 저장했습니다" / "회사로 저장했습니다" / "즐겨찾기에 저장했습니다". */
        val message: String
            get() = when (kind) {
                FavoriteKind.HOME -> "집으로 저장했습니다"
                FavoriteKind.WORK -> "회사로 저장했습니다"
                FavoriteKind.CUSTOM -> "즐겨찾기에 저장했습니다"
            }
    }
}

/**
 * The favorites list and the save / rename / delete dialogs. Activity-scoped, so the chips and the place sheets of
 * every screen share one dialog. One write runs at a time: a second press while it runs is ignored.
 */
class FavoritesViewModel(
    private val repository: FavoriteRepository,
    private val placeSearch: PlaceSearch,
    private val log: WarnLog = androidWarnLog(TAG),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    // Declared before [favorites]: its eager collection may set this while the view model is still being built.
    private val _favoritesLoaded = MutableStateFlow(false)

    /**
     * False until the store has answered once (a list, or a failure). Until then [favorites] is a placeholder empty
     * list, so "no home, no work" is not known yet: the register chips wait for this.
     */
    val favoritesLoaded: StateFlow<Boolean> = _favoritesLoaded.asStateFlow()

    /** Home, work, then custom ones in creation order. A failing flow is logged and shows an empty list. */
    val favorites: StateFlow<List<Favorite>> = repository.favorites
        .onEach { _favoritesLoaded.value = true }
        .catch { e ->
            if (e is CancellationException) throw e
            log.warn("favorites flow failed", e)
            _favoritesLoaded.value = true
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dialog = MutableStateFlow<FavoriteDialog?>(null)
    val dialog: StateFlow<FavoriteDialog?> = _dialog.asStateFlow()

    private val _events = Channel<FavoriteEvent>(Channel.BUFFERED)

    /** One-shot events; each is received by one collector only. */
    val events: Flow<FavoriteEvent> = _events.receiveAsFlow()

    private var work: Job? = null

    /** The address of the current location being looked up ([registerCurrent]); a second press waits for it. */
    private var locating: Job? = null

    private val _locatingCurrent = MutableStateFlow(false)

    /** True while [registerCurrent] looks up the address ([📍 현재 위치] says "현재 위치를 확인하는 중…"). */
    val locatingCurrent: StateFlow<Boolean> = _locatingCurrent.asStateFlow()

    /**
     * Drops a running [registerCurrent] lookup: its answer is no longer wanted (another dialog opened, the dialog was
     * dismissed, or the register-mode search was left).
     */
    fun cancelCurrentLocation() {
        locating?.cancel()
        locating = null
        _locatingCurrent.value = false
    }

    /** The "추천 이름" of the open [FavoriteDialog.NameFavorite]. */
    private var suggesting: Job? = null

    fun startSave(place: FavoritePlace) {
        cancelCurrentLocation()
        _dialog.value = FavoriteDialog.ChooseName(place)
    }

    /** From [FavoriteDialog.ChooseName] only. [customName] is used for [FavoriteKind.CUSTOM]. */
    fun save(kind: FavoriteKind, customName: String? = null) {
        val current = _dialog.value as? FavoriteDialog.ChooseName ?: return
        write(current) {
            when (val result = repository.save(kind, customName, current.place)) {
                FavoriteResult.Saved -> null
                is FavoriteResult.ConfirmReplace -> FavoriteDialog.ConfirmReplace(current.place, kind, result.question)
                is FavoriteResult.Rejected -> current.copy(error = result.message)
            }
        }
    }

    /**
     * The register-mode search (spec §7.4): save [place] as home or work with no name dialog, under the same rules as
     * [save]. Saved → [FavoriteEvent.Registered]; an existing one → [FavoriteDialog.ConfirmReplace]; refused or
     * failed → [FavoriteDialog.Message]. A custom favorite (spec §8.2) first asks its name: [FavoriteDialog.NameFavorite]
     * prefilled with the place name, then [saveCustom].
     */
    fun saveAs(place: FavoritePlace, kind: FavoriteKind) {
        cancelCurrentLocation()
        if (kind == FavoriteKind.CUSTOM) {
            if (work?.isActive != true) openName(place, prefill = place.name, register = true)
            return
        }
        val current = _dialog.value
        write(current, onFailure = FavoriteDialog.Message(MSG_SAVE_FAILED)) {
            when (val result = repository.save(kind, null, place)) {
                FavoriteResult.Saved -> registered(kind)
                is FavoriteResult.ConfirmReplace -> FavoriteDialog.ConfirmReplace(place, kind, result.question, register = true)
                is FavoriteResult.Rejected -> FavoriteDialog.Message(result.message)
            }
        }
    }

    fun confirmReplace() {
        val current = _dialog.value as? FavoriteDialog.ConfirmReplace ?: return
        val onFailure = if (current.register) {
            FavoriteDialog.Message(MSG_SAVE_FAILED)
        } else {
            FavoriteDialog.ChooseName(current.place, MSG_SAVE_FAILED)
        }
        write(current, onFailure = onFailure) {
            when (val result = repository.save(current.kind, null, current.place, replace = true)) {
                FavoriteResult.Saved -> if (current.register) registered(current.kind) else null
                is FavoriteResult.ConfirmReplace -> current
                is FavoriteResult.Rejected -> if (current.register) {
                    FavoriteDialog.Message(result.message)
                } else {
                    FavoriteDialog.ChooseName(current.place, result.message)
                }
            }
        }
    }

    /** Tells the save and closes the dialog. */
    private fun registered(kind: FavoriteKind): FavoriteDialog? {
        _events.trySend(FavoriteEvent.Registered(kind))
        return null
    }

    /** Only custom favorites can be renamed. */
    fun startRename(favorite: Favorite) {
        if (favorite.kind != FavoriteKind.CUSTOM) return
        cancelCurrentLocation()
        _dialog.value = FavoriteDialog.Rename(favorite)
    }

    fun rename(name: String) {
        val current = _dialog.value as? FavoriteDialog.Rename ?: return
        write(current) {
            when (val result = repository.rename(current.favorite.id, name)) {
                FavoriteResult.Saved -> null
                is FavoriteResult.ConfirmReplace -> current
                is FavoriteResult.Rejected -> current.copy(error = result.message)
            }
        }
    }

    fun askDelete(favorite: Favorite) {
        cancelCurrentLocation()
        _dialog.value = FavoriteDialog.ConfirmDelete(favorite)
    }

    fun confirmDelete() {
        val current = _dialog.value as? FavoriteDialog.ConfirmDelete ?: return
        // The dialog has no place for an error: a failed delete is logged and the chip simply stays.
        write(current, onFailure = null) {
            repository.delete(current.favorite.id)
            null
        }
    }

    /** [직접 입력] in [FavoriteDialog.ChooseName] (spec §8.4): the name dialog, prefilled with the place name. */
    fun startCustom() {
        val current = _dialog.value as? FavoriteDialog.ChooseName ?: return
        if (work?.isActive == true) return
        cancelCurrentLocation()
        openName(current.place, prefill = current.place.name, register = false)
    }

    /**
     * [📍 현재 위치] in the register-mode search (spec §8.3). No [fix] yet → [MSG_NO_LOCATION]. Else its address is
     * looked up, then home or work asks [FavoriteDialog.ConfirmCurrent] and a custom favorite its name
     * ([FavoriteDialog.NameFavorite]). A fix rougher than [MAX_ACCURACY_M] carries [accuracyWarning]; one older than
     * [MAX_FIX_AGE_MS] (the last one kept after the location stopped) counts as none.
     *
     * While the address is looked up [locatingCurrent] is true. Its answer is shown only if this lookup is still the
     * current one and no dialog opened meanwhile: anything else the rider does first ([saveAs], [registerPicked],
     * another dialog, [dismiss], leaving the screen) cancels it ([cancelCurrentLocation]).
     */
    fun registerCurrent(kind: FavoriteKind, fix: LocationFix?) {
        if (work?.isActive == true || locating?.isActive == true) return
        if (fix == null || clock() - fix.timeMillis > MAX_FIX_AGE_MS) {
            _dialog.value = FavoriteDialog.Message(MSG_NO_LOCATION)
            return
        }
        val warning = accuracyWarning(fix.accuracyM)
        _locatingCurrent.value = true
        locating = viewModelScope.launch {
            val self = coroutineContext[Job]
            val address = placeSearch.addressOrNull(fix.point)
            if (locating !== self) return@launch
            // Done: from here on this lookup is no longer "running" (opening the name dialog must not cancel it).
            locating = null
            _locatingCurrent.value = false
            if (_dialog.value != null) return@launch
            val place = FavoritePlace(address ?: coordinates(fix.point), fix.point, address)
            when (kind) {
                FavoriteKind.HOME, FavoriteKind.WORK ->
                    _dialog.value = FavoriteDialog.ConfirmCurrent(kind, place, currentQuestion(kind), warning)
                FavoriteKind.CUSTOM -> openName(place, prefill = null, register = true, warning = warning)
            }
        }
    }

    /** [저장] in [FavoriteDialog.ConfirmCurrent]: saved like a tapped result ([saveAs]). */
    fun confirmCurrent() {
        val current = _dialog.value as? FavoriteDialog.ConfirmCurrent ?: return
        saveAs(current.place, current.kind)
    }

    /**
     * [이 위치로 설정] on "지도에서 선택" for the register-mode search (spec §8.3): home or work is saved at once (that
     * button was the confirmation; [saveAs]), a custom favorite asks its name.
     */
    fun registerPicked(kind: FavoriteKind, picked: PickedPoint) {
        cancelCurrentLocation()
        val place = FavoritePlace(picked.address ?: PickedPoint.FALLBACK_NAME, picked.point, picked.address)
        when (kind) {
            FavoriteKind.HOME, FavoriteKind.WORK -> saveAs(place, kind)
            FavoriteKind.CUSTOM -> if (work?.isActive != true) openName(place, prefill = null, register = true)
        }
    }

    /**
     * Opens [FavoriteDialog.NameFavorite] for [place] and loads its "추천 이름". [prefill] (a search result's name) is the
     * field's name; null (current location, map pick) takes the nearest place, else the address, else empty.
     */
    private fun openName(place: FavoritePlace, prefill: String?, register: Boolean, warning: String? = null) {
        cancelCurrentLocation()
        suggesting?.cancel()
        val opened = FavoriteDialog.NameFavorite(
            place = place,
            initialName = prefill.orEmpty().take(FavoriteRepository.MAX_NAME_LENGTH),
            loadingSuggestions = true,
            register = register,
            accuracyWarning = warning,
        )
        _dialog.value = opened
        suggesting = viewModelScope.launch {
            val names = nearbyNames(place.point)
            val initial = prefill ?: names.firstOrNull() ?: place.address.orEmpty()
            val suggestions = (names + listOfNotNull(place.address)).filter { it.isNotBlank() }.distinct()
            _dialog.update {
                if (opened.sameRequest(it) && (it as FavoriteDialog.NameFavorite).loadingSuggestions) {
                    it.copy(
                        initialName = initial.take(FavoriteRepository.MAX_NAME_LENGTH),
                        suggestions = suggestions,
                        loadingSuggestions = false,
                    )
                } else {
                    it
                }
            }
        }
    }

    /** Nearby place names, nearest first; none when the search fails (the name can still be typed). */
    private suspend fun nearbyNames(point: GeoPoint): List<String> = try {
        (placeSearch.nearby(point) as? SearchResult.Ok)?.value.orEmpty().map { it.name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("nearby places failed", e)
        emptyList()
    }

    /**
     * From [FavoriteDialog.NameFavorite] only: save its place as the custom favorite [name]. Saved → closed, and
     * [FavoriteEvent.Registered] when the register-mode search asked; refused (name, limit) or failed → the same dialog
     * with the reason. Suggestions arriving meanwhile are kept.
     */
    fun saveCustom(name: String) {
        val current = _dialog.value as? FavoriteDialog.NameFavorite ?: return
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val error = try {
                when (val result = repository.save(FavoriteKind.CUSTOM, name, current.place)) {
                    FavoriteResult.Saved -> {
                        if (current.register) _events.trySend(FavoriteEvent.Registered(FavoriteKind.CUSTOM))
                        null
                    }
                    // Never for a custom favorite.
                    is FavoriteResult.ConfirmReplace -> MSG_SAVE_FAILED
                    is FavoriteResult.Rejected -> result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("favorite write failed", e)
                MSG_SAVE_FAILED
            }
            val saved = error == null
            _dialog.update {
                when {
                    !current.sameRequest(it) -> it
                    saved -> null
                    else -> (it as FavoriteDialog.NameFavorite).copy(error = error)
                }
            }
            if (saved) suggesting?.cancel()
        }
    }

    /**
     * [＋ 즐겨찾기 추가] (spec §8.2): true when one more fits (the caller opens the register-mode search); at the limit
     * (home and work included) shows [FavoriteRepository.MSG_LIMIT] and returns false; before [favoritesLoaded] it
     * does nothing and returns false.
     */
    fun startAdd(): Boolean {
        // The placeholder list before the first answer says nothing about the count: the press is ignored.
        if (!favoritesLoaded.value) return false
        if (favorites.value.size < FavoriteRepository.MAX_COUNT) return true
        cancelCurrentLocation()
        _dialog.value = FavoriteDialog.Message(FavoriteRepository.MSG_LIMIT)
        return false
    }

    fun dismiss() {
        cancelCurrentLocation()
        suggesting?.cancel()
        _dialog.value = null
    }

    /**
     * Runs [action] for the dialog [from] (null: started with no dialog); its result replaces that dialog (null closes
     * it) unless the dialog was changed or closed meanwhile. A store exception is logged and becomes [onFailure]
     * (default: [from] with [MSG_SAVE_FAILED]).
     */
    private fun write(
        from: FavoriteDialog?,
        onFailure: FavoriteDialog? = failed(from),
        action: suspend () -> FavoriteDialog?,
    ) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            val next = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("favorite write failed", e)
                onFailure
            }
            _dialog.update { if (it == from) next else it }
        }
    }

    private fun failed(from: FavoriteDialog?): FavoriteDialog? = when (from) {
        is FavoriteDialog.ChooseName -> from.copy(error = MSG_SAVE_FAILED)
        is FavoriteDialog.Rename -> from.copy(error = MSG_SAVE_FAILED)
        else -> null
    }

    companion object {
        private const val TAG = "Favorites"
        const val MSG_SAVE_FAILED = "즐겨찾기를 저장하지 못했습니다"
        const val MSG_NO_LOCATION = "현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요"

        /** A fix rougher than this (m) is warned about (spec §8.3); saving is still allowed. */
        const val MAX_ACCURACY_M = 50f

        /** A fix older than this (ms, by its wall-clock time) is not "the current location". */
        const val MAX_FIX_AGE_MS = 30_000L

        /** "현재 위치를 집으로 저장할까요?" / "현재 위치를 회사로 저장할까요?" */
        fun currentQuestion(kind: FavoriteKind): String = when (kind) {
            FavoriteKind.HOME -> "현재 위치를 집으로 저장할까요?"
            FavoriteKind.WORK -> "현재 위치를 회사로 저장할까요?"
            FavoriteKind.CUSTOM -> throw IllegalArgumentException("a custom favorite asks its name instead")
        }

        /** "위치 오차가 큽니다(약 {n}m)" above [MAX_ACCURACY_M], n rounded; else null. */
        fun accuracyWarning(accuracyM: Float): String? =
            if (accuracyM > MAX_ACCURACY_M) "위치 오차가 큽니다(약 ${accuracyM.roundToInt()}m)" else null

        /** The name of a point with no address, as on the pick screen. */
        private fun coordinates(point: GeoPoint) = String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FavoritesViewModel(container.favorites, container.placeSearch) }
        }
    }
}
