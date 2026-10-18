package com.cowork.bikerecoder.ui.common

import androidx.compose.runtime.Composable
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.FavoritePlace
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** The map point the user long-pressed, with its address lookup. */
data class PressedPlace(val point: GeoPoint, val address: String?, val loading: Boolean) {
    /** Address when known, else the coordinates (also the fallback when the lookup failed). */
    val title: String get() = address ?: String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)
}

/**
 * Long press → [PressedPlace] (loading) → its address. A new press cancels the previous lookup, and a late
 * result of an older one is dropped. Shared by the main map and the plan map.
 *
 * @param addressOf the address of a point, or null when there is none (see [addressOrNull]).
 */
class PlaceLookup(
    private val addressOf: suspend (GeoPoint) -> String?,
    private val scope: CoroutineScope,
) {
    private val _pressed = MutableStateFlow<PressedPlace?>(null)
    val pressed: StateFlow<PressedPlace?> = _pressed.asStateFlow()

    private var job: Job? = null

    fun onLongPress(point: GeoPoint) {
        job?.cancel()
        _pressed.value = PressedPlace(point, address = null, loading = true)
        job = scope.launch {
            val address = try {
                addressOf(point)?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _pressed.update { p -> p?.takeIf { it.point == point }?.copy(address = address, loading = false) }
        }
    }

    fun dismiss() {
        job?.cancel()
        _pressed.value = null
    }
}

/** `Ok(non-blank)` → the address; `Ok(null/blank)`, `Err` and any exception → null (cancellation is rethrown). */
suspend fun PlaceSearch.addressOrNull(point: GeoPoint): String? = try {
    (addressOf(point) as? SearchResult.Ok)?.value?.takeIf { it.isNotBlank() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * The sheet for a long-pressed map point, shared by the map and the plan screens: the title is
 * "주소를 확인하는 중…" while the address loads, then [PressedPlace.title].
 *
 * [onSaveFavorite] null hides [즐겨찾기에 저장]; it is disabled while the address loads (the coordinates would
 * become the name).
 */
@Composable
fun PressedPlaceSheet(
    pressed: PressedPlace,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
    onSaveFavorite: ((FavoritePlace) -> Unit)?,
    onDismiss: () -> Unit,
) {
    PlaceActionSheet(
        title = if (pressed.loading) "주소를 확인하는 중…" else pressed.title,
        subtitle = null,
        onSetDestination = { onSetDestination(pressed.title, pressed.point) },
        onAddWaypoint = { onAddWaypoint(pressed.title, pressed.point) },
        onDismiss = onDismiss,
        onSaveFavorite = onSaveFavorite?.let { save -> { save(FavoritePlace(pressed.title, pressed.point, pressed.address)) } },
        saveEnabled = !pressed.loading,
    )
}
