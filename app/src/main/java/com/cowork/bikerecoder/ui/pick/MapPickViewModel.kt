package com.cowork.bikerecoder.ui.pick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.search.Place
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.ui.common.addressOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** What [이 위치로 설정] hands back: the point under the pin and its address (null when unknown). */
data class PickedPoint(val point: GeoPoint, val address: String?) {
    /** As a search result (spec §8.5): named by its address, else "지도에서 선택한 위치". */
    fun toPlace() = Place(name = address ?: FALLBACK_NAME, address = address.orEmpty(), point = point, distanceM = null)

    companion object {
        const val FALLBACK_NAME = "지도에서 선택한 위치"
    }
}

/**
 * The "지도에서 선택" screen (spec §8.5). [center]: the map point under the pin once the camera stopped (null before
 * the first stop when there was no initial center). [loading]: its address is being looked up.
 */
data class MapPickState(
    val center: GeoPoint? = null,
    val moving: Boolean = false,
    val address: String? = null,
    val loading: Boolean = false,
) {
    /**
     * [이 위치로 설정] works only on a stopped map with a known point whose address lookup has finished (found or not: a
     * failed lookup still enables it, with the coordinates).
     */
    val canConfirm: Boolean get() = !moving && !loading && center != null

    /** The card's line: "주소를 확인하는 중…" while looking up, then the address, else the coordinates. */
    val title: String?
        get() = when {
            loading -> "주소를 확인하는 중…"
            address != null -> address
            center != null -> String.format(Locale.ROOT, "%.5f, %.5f", center.lat, center.lon)
            else -> null
        }
}

class MapPickViewModel(
    private val placeSearch: PlaceSearch,
    initialCenter: GeoPoint?,
) : ViewModel() {

    private val _state = MutableStateFlow(MapPickState(center = initialCenter, loading = initialCenter != null))
    val state: StateFlow<MapPickState> = _state.asStateFlow()

    /** Every stop of the camera, even at the same point as before (a StateFlow would drop that repeat). */
    private val stops = Channel<GeoPoint>(Channel.CONFLATED)

    init {
        initialCenter?.let { stops.trySend(it) }
        viewModelScope.launch {
            // collectLatest cancels the pending/in-flight lookup on every stop; the delay is the debounce.
            stops.receiveAsFlow().collectLatest { point ->
                delay(DEBOUNCE_MS)
                val address = placeSearch.addressOrNull(point)
                // A late answer must not land on a map that moved since.
                _state.update { if (it.center == point && !it.moving) it.copy(address = address, loading = false) else it }
            }
        }
    }

    /** The camera started moving (a gesture or an animation): no point to set until it stops. */
    fun onMoveStarted() {
        _state.update { it.copy(moving = true, address = null, loading = true) }
    }

    /** The camera stopped with [point] under the pin. */
    fun onCameraIdle(point: GeoPoint) {
        _state.update { it.copy(center = point, moving = false, address = null, loading = true) }
        stops.trySend(point)
    }

    /** The point to hand back, or null while the map moves or before it has a point. */
    fun result(): PickedPoint? {
        val s = _state.value
        val center = s.center ?: return null
        return if (!s.canConfirm) null else PickedPoint(center, s.address)
    }

    companion object {
        const val DEBOUNCE_MS = 300L

        /** Seoul City Hall: the first center when the current location is not known yet. */
        val DEFAULT_CENTER = GeoPoint(37.5665, 126.9780)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MapPickViewModel(container.placeSearch, container.currentLocation.value?.point ?: DEFAULT_CENTER)
            }
        }
    }
}
