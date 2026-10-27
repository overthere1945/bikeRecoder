package com.cowork.bikerecoder.data

import com.cowork.bikerecoder.video.MediaKind
import com.cowork.bikerecoder.video.TripMedia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TripMediaMappingTest {
    @Test
    fun `video round trip keeps every field and stores the kind by name`() {
        val media = TripMedia(7, 5, MediaKind.VIDEO, "content://media/external/video/media/42", 9_000L, "{\"aspect\":\"PORTRAIT\"}")

        val entity = media.toEntity()

        assertEquals(TripMediaEntity(7, 5, "VIDEO", media.uri, 9_000L, media.optionsJson), entity)
        assertEquals(media, entity.toModel())
    }

    @Test
    fun `image kind maps both ways`() {
        val media = TripMedia(0, 3, MediaKind.IMAGE, "content://media/external/images/media/8", 1_000L, "{}")

        assertEquals("IMAGE", media.toEntity().kind)
        assertEquals(media, media.toEntity().toModel())
    }
}
