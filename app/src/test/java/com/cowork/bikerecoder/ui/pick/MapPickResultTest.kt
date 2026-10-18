package com.cowork.bikerecoder.ui.pick

import androidx.lifecycle.SavedStateHandle
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MapPickResultTest {
    private val point = GeoPoint(35.115, 129.041)

    @Test
    fun `a sent result is taken once, then it is gone`() {
        val handle = SavedStateHandle()
        MapPick.send(handle, PickedPoint(point, "부산 동구 중앙대로 206"))
        assertEquals(PickedPoint(point, "부산 동구 중앙대로 206"), MapPick.take(handle))
        assertNull(MapPick.take(handle))
    }

    @Test
    fun `a result without an address keeps it null`() {
        val handle = SavedStateHandle()
        MapPick.send(handle, PickedPoint(point, null))
        assertEquals(PickedPoint(point, null), MapPick.take(handle))
    }

    @Test
    fun `a later result replaces an earlier one, address included`() {
        val handle = SavedStateHandle()
        MapPick.send(handle, PickedPoint(GeoPoint(37.0, 127.0), "첫 주소"))
        MapPick.send(handle, PickedPoint(point, null))
        assertEquals(PickedPoint(point, null), MapPick.take(handle))
    }

    @Test
    fun `nothing sent is nothing taken`() {
        assertNull(MapPick.take(SavedStateHandle()))
    }
}
