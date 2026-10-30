package com.cowork.bikerecoder.ui.main

import androidx.compose.runtime.saveable.SaverScope
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.map.MapCamera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Review I1: the map tab starts where its camera was left; the first time on the user, or on all of Korea. */
class MainMapStartTest {

    private val user = GeoPoint(37.55, 126.85)
    private val left = MapCamera(GeoPoint(35.1, 129.0), zoom = 15.5, bearing = 42.0)

    @Test
    fun whereItWasLeftWinsOverTheUser() {
        assertEquals(left, mainMapStart(left, user))
        assertEquals(left, mainMapStart(left, null))
    }

    @Test
    fun theFirstTimeOnTheUserElseOnKorea() {
        assertEquals(MapCamera(user, 14.0), mainMapStart(null, user))
        assertEquals(MapCamera(GeoPoint(36.5, 127.8), 7.0), mainMapStart(null, null))
    }

    @Test
    fun theCameraIsSavedAndRestoredWhole() {
        val scope = SaverScope { true }
        val saved = with(MapCameraSaver) { scope.save(left) }!!

        assertEquals(left, MapCameraSaver.restore(saved))
        assertNull(with(MapCameraSaver) { scope.save(null) })
    }
}
