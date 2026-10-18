package com.cowork.bikerecoder.ui

import com.cowork.bikerecoder.favorite.FavoriteKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which screen a [com.cowork.bikerecoder.ui.favorite.FavoriteEvent.Registered] closes. */
class RegisteredPopTest {

    @Test
    fun theRegisterSearchOfThatKindGoesBack() {
        FavoriteKind.entries.forEach { kind ->
            assertEquals(true, popsAfterRegistered(Routes.SEARCH_PATTERN, kind.name, kind), kind.name)
        }
    }

    @Test
    fun aRegisterSearchOfAnotherKindStays() {
        // E.g. a late home save (its write was still running when the rider left) must not close the custom one.
        assertEquals(false, popsAfterRegistered(Routes.SEARCH_PATTERN, "CUSTOM", FavoriteKind.HOME))
        assertEquals(false, popsAfterRegistered(Routes.SEARCH_PATTERN, "HOME", FavoriteKind.WORK))
        assertEquals(false, popsAfterRegistered(Routes.SEARCH_PATTERN, "WORK", FavoriteKind.CUSTOM))
    }

    @Test
    fun otherScreensStay() {
        assertEquals(false, popsAfterRegistered(Routes.SEARCH_PATTERN, null, FavoriteKind.HOME))
        assertEquals(false, popsAfterRegistered(Routes.SEARCH_PATTERN, "nonsense", FavoriteKind.HOME))
        assertEquals(false, popsAfterRegistered(Routes.FAVORITES, null, FavoriteKind.CUSTOM))
        assertEquals(false, popsAfterRegistered(Routes.MAP_PICK, "HOME", FavoriteKind.HOME))
        assertEquals(false, popsAfterRegistered(null, null, FavoriteKind.HOME))
    }
}
