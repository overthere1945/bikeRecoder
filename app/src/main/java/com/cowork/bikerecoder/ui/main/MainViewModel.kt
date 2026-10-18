package com.cowork.bikerecoder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cowork.bikerecoder.AppContainer
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.trip.TripManager
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.search.PlaceSearch
import com.cowork.bikerecoder.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** The trip banner's button. */
enum class BannerAction(val label: String) {
    /** Guide the trip's remaining stops. */
    RESUME("이어서 안내"),

    /** Nothing left to guide (e.g. converted after arriving): pick the next destination for the same trip (R21). */
    NEXT_DESTINATION("다음 목적지 정하기"),
}

/** "🚩 {destinationName} · 여러 날 {dayNumber}일차" banner for the active multi-day trip. */
data class TripBanner(val tripId: Long, val destinationName: String, val dayNumber: Int, val action: BannerAction) {
    val text: String get() = "🚩 $destinationName · 여러 날 ${dayNumber}일차"
}

/** A trip left idle for 3+ days, asked about once when the main screen first opens. */
data class StaleTrip(val tripId: Long, val destinationName: String)

/** The map point the user long-pressed, with its address lookup. */
data class PressedPlace(val point: GeoPoint, val address: String?, val loading: Boolean) {
    /** Address when known, else the coordinates (also the fallback when the lookup failed). */
    val title: String get() = address ?: String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)
}

data class MainUiState(
    val banner: TripBanner? = null,
    val staleTrip: StaleTrip? = null,
    val pressed: PressedPlace? = null,
    /** Guidance is starting or running (any trip type): the "안내 중 [보기]" banner is shown. */
    val guiding: Boolean = false,
) {
    /** The trip banner gives way to the guidance banner while guiding. */
    val visibleBanner: TripBanner? get() = banner.takeUnless { guiding }
}

class MainViewModel(
    private val tripManager: TripManager,
    private val tripStore: TripStore,
    private val placeSearch: PlaceSearch,
    guidance: Flow<NavUiState>,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var addressJob: Job? = null

    init {
        viewModelScope.launch {
            val stale = runCatchingNonCancel { tripManager.stalePrompt() }
            _state.update { it.copy(staleTrip = stale?.let { t -> StaleTrip(t.id, destinationName(t.id)) }) }
        }
        refreshBanner()
        viewModelScope.launch {
            guidance
                .map { it is NavUiState.Starting || it is NavUiState.Active }
                .distinctUntilChanged()
                .collect { guiding ->
                    val wasGuiding = _state.value.guiding
                    _state.update { it.copy(guiding = guiding) }
                    // Guidance ended (arrived, stopped for today, ...): the trip may have changed.
                    if (wasGuiding && !guiding) refreshBanner()
                }
        }
    }

    /** Re-reads the active multi-day trip (e.g. after returning from navigation or completing a trip). */
    fun refreshBanner() {
        viewModelScope.launch {
            val banner = runCatchingNonCancel {
                tripStore.activeTrip()?.takeIf { it.type == TripType.MULTI_DAY }?.let { trip ->
                    // Never offer [이어서 안내] for a trip with nothing left to guide.
                    val action = if (tripManager.remainingStops(trip.id).isEmpty()) {
                        BannerAction.NEXT_DESTINATION
                    } else {
                        BannerAction.RESUME
                    }
                    TripBanner(trip.id, destinationName(trip.id), tripManager.dayNumber(trip), action)
                }
            }
            _state.update { it.copy(banner = banner) }
        }
    }

    /** [완료] on the stale-trip dialog. */
    fun completeStaleTrip() {
        val stale = _state.value.staleTrip ?: return
        _state.update { it.copy(staleTrip = null) }
        viewModelScope.launch {
            runCatchingNonCancel { tripManager.complete(stale.tripId) }
            refreshBanner()
        }
    }

    /** [계속 유지]. */
    fun dismissStaleTrip() {
        _state.update { it.copy(staleTrip = null) }
    }

    fun onLongPress(point: GeoPoint) {
        addressJob?.cancel()
        _state.update { it.copy(pressed = PressedPlace(point, address = null, loading = true)) }
        addressJob = viewModelScope.launch {
            val address = try {
                (placeSearch.addressOf(point) as? SearchResult.Ok)?.value?.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { s -> s.copy(pressed = s.pressed?.takeIf { it.point == point }?.copy(address = address, loading = false)) }
        }
    }

    fun dismissPressed() {
        addressJob?.cancel()
        _state.update { it.copy(pressed = null) }
    }

    private suspend fun destinationName(tripId: Long): String {
        val stops = runCatchingNonCancel { tripStore.stops(tripId) }.orEmpty()
        return (stops.firstOrNull { it.isDestination } ?: stops.lastOrNull())?.name ?: "목적지"
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(container.tripManager, container.tripStore, container.placeSearch, container.navigationUi)
            }
        }
    }
}
