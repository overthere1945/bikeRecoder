package com.cowork.bikerecoder.ui.pick

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.GeoPoint

/**
 * The "지도에서 선택" result hand-off through the calling screen's back stack entry [SavedStateHandle]: the pick screen
 * [send]s it to `previousBackStackEntry` and pops; the caller reads it once with [MapPickResultEffect] (or [take]),
 * which clears it, so a rotation or a later return does not deliver it again.
 */
object MapPick {
    /** `[lat, lon]`; set last, so whoever observes it finds the address already there. */
    const val RESULT = "map-pick-result"
    private const val RESULT_ADDRESS = "map-pick-result-address"

    fun send(handle: SavedStateHandle, picked: PickedPoint) {
        handle[RESULT_ADDRESS] = picked.address
        handle[RESULT] = doubleArrayOf(picked.point.lat, picked.point.lon)
    }

    /** The result left on [handle], cleared as it is read; null when there is none. */
    fun take(handle: SavedStateHandle): PickedPoint? {
        val latLon = handle.get<DoubleArray>(RESULT)?.takeIf { it.size == 2 } ?: return null
        val address = handle.get<String>(RESULT_ADDRESS)
        // Set to null rather than removed: removing a key also drops the StateFlow [MapPickResultEffect] observes.
        handle[RESULT] = null
        handle[RESULT_ADDRESS] = null
        return PickedPoint(GeoPoint(latLon[0], latLon[1]), address)
    }
}

/**
 * Hands each map-pick result left on [handle] (the hosting screen's own back stack entry) to [onResult], once. Shared
 * by the normal and the register-mode search.
 */
@Composable
fun MapPickResultEffect(handle: SavedStateHandle, onResult: (PickedPoint) -> Unit) {
    val currentOnResult by rememberUpdatedState(onResult)
    LaunchedEffect(handle) {
        handle.getStateFlow<DoubleArray?>(MapPick.RESULT, null).collect { latLon ->
            if (latLon != null) MapPick.take(handle)?.let(currentOnResult)
        }
    }
}
