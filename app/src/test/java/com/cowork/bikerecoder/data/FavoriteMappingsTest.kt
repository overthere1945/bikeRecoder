package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.favorite.Favorite
import com.cowork.bikerecoder.favorite.FavoriteKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FavoriteMappingsTest {
    @Test
    fun `favorite round trip keeps kind and null address`() {
        val favorite = Favorite(3, FavoriteKind.WORK, "회사", GeoPoint(37.5, 127.0), null, 1_000L)

        val entity = favorite.toEntity()

        assertEquals("WORK", entity.kind)
        assertEquals(favorite, entity.toFavorite())
    }
}
