package com.cowork.bikerecoder.ui.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.favorite.FavoriteRepository
import com.cowork.bikerecoder.favorite.FavoriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The favorite dialog on screen (at most one). */
sealed interface FavoriteDialog {
    /**
     * "즐겨찾기에 저장": [집] [회사] / "직접 입력" 칸(place.name.take(FavoriteRepository.MAX_NAME_LENGTH)로 채움, 입력도 그
     * 길이까지) [저장] [취소]. [error]는 마지막 거절 문구(대화상자는 열린 채).
     */
    data class ChooseName(val place: FavoritePlace, val error: String? = null) : FavoriteDialog

    data class ConfirmReplace(val place: FavoritePlace, val kind: FavoriteKind, val question: String) : FavoriteDialog

    data class Rename(val favorite: Favorite, val error: String? = null) : FavoriteDialog

    /** "즐겨찾기에서 삭제할까요?" [삭제] [취소] */
    data class ConfirmDelete(val favorite: Favorite) : FavoriteDialog
}

/**
 * The favorites list and the save / rename / delete dialogs. Activity-scoped, so the chips and the place sheets of
 * every screen share one dialog. One write runs at a time: a second press while it runs is ignored.
 */
class FavoritesViewModel(
    private val repository: FavoriteRepository,
    private val log: WarnLog = androidWarnLog(TAG),
) : ViewModel() {

    /** Home, work, then custom ones in creation order. A failing flow is logged and shows an empty list. */
    val favorites: StateFlow<List<Favorite>> = repository.favorites
        .catch { e ->
            if (e is CancellationException) throw e
            log.warn("favorites flow failed", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dialog = MutableStateFlow<FavoriteDialog?>(null)
    val dialog: StateFlow<FavoriteDialog?> = _dialog.asStateFlow()

    private var work: Job? = null

    fun startSave(place: FavoritePlace) {
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

    fun confirmReplace() {
        val current = _dialog.value as? FavoriteDialog.ConfirmReplace ?: return
        write(current, onFailure = FavoriteDialog.ChooseName(current.place, MSG_SAVE_FAILED)) {
            when (val result = repository.save(current.kind, null, current.place, replace = true)) {
                FavoriteResult.Saved -> null
                is FavoriteResult.ConfirmReplace -> current
                is FavoriteResult.Rejected -> FavoriteDialog.ChooseName(current.place, result.message)
            }
        }
    }

    /** Only custom favorites can be renamed. */
    fun startRename(favorite: Favorite) {
        if (favorite.kind != FavoriteKind.CUSTOM) return
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

    fun dismiss() {
        _dialog.value = null
    }

    /**
     * Runs [action] for the dialog [from]; its result replaces that dialog (null closes it) unless the dialog was
     * changed or closed meanwhile. A store exception is logged and becomes [onFailure] (default: [from] with
     * [MSG_SAVE_FAILED]).
     */
    private fun write(
        from: FavoriteDialog,
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

    private fun failed(from: FavoriteDialog): FavoriteDialog? = when (from) {
        is FavoriteDialog.ChooseName -> from.copy(error = MSG_SAVE_FAILED)
        is FavoriteDialog.Rename -> from.copy(error = MSG_SAVE_FAILED)
        else -> null
    }

    companion object {
        private const val TAG = "Favorites"
        const val MSG_SAVE_FAILED = "즐겨찾기를 저장하지 못했습니다"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FavoritesViewModel(container.favorites) }
        }
    }
}
