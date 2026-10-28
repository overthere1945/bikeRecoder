package com.cowork.bikerecoder.ui.photo

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.PhotoPositionSource
import com.cowork.bikerecoder.map.MapPhoto
import com.cowork.bikerecoder.post.RidePhoto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which photos reach the map, and which the full-screen view shows (spec §2.3). */
class PhotoSelectionTest {
    private fun photo(id: Long, hidden: Boolean = false) = RidePhoto(
        id = id, segmentId = 1, mediaId = 100 + id, takenAt = id * 1_000, point = GeoPoint(37.5 + id * 0.001, 127.0),
        source = PhotoPositionSource.TRACK, hidden = hidden,
    )

    private val all = RidePhotos.of(listOf(photo(1), photo(2, hidden = true), photo(3), photo(4, hidden = true)))

    @Test
    fun onlyVisiblePhotosReachTheMap() {
        assertEquals(
            listOf(MapPhoto(1, photo(1).point, null), MapPhoto(3, photo(3).point, null)),
            mapPhotosOf(all, thumbnails = emptyMap()),
        )
        assertEquals(emptyList<MapPhoto>(), mapPhotosOf(RidePhotos.of(listOf(photo(2, hidden = true))), emptyMap()))
    }

    @Test
    fun theViewerKeepsItsPhotosInOrderHiddenOrNot() {
        // Opened on the visible photos 3 and 1; photo 1 was then hidden (and photo 3 shown again elsewhere).
        val now = RidePhotos.of(listOf(photo(1, hidden = true), photo(2, hidden = true), photo(3)))

        assertEquals(listOf(3L, 1L), viewerPhotos(listOf(3L, 1L), now).map { it.id })
        assertEquals(true, viewerPhotos(listOf(3L, 1L), now)[1].hidden)
    }

    @Test
    fun aPhotoGoneMeanwhileDropsOutOfTheViewer() {
        val now = RidePhotos.of(listOf(photo(3)))

        assertEquals(listOf(3L), viewerPhotos(listOf(1L, 3L), now).map { it.id })
        assertEquals(emptyList<RidePhoto>(), viewerPhotos(listOf(1L), now))
    }

    @Test
    fun aMapTapOpensThatPhoto() {
        val state = PhotoViewerState()

        state.openPhoto(all.visible, photoId = 3)

        assertEquals(listOf(1L, 3L), state.openIds)
        assertEquals(1, state.startIndex)
    }

    @Test
    fun aMapTapOnAPhotoNoLongerListedDoesNothing() {
        val state = PhotoViewerState()

        state.openPhoto(all.visible, photoId = 2)

        assertNull(state.openIds)

        state.open(all.visible, 1)
        state.openPhoto(all.visible, photoId = 42)
        assertEquals(1, state.startIndex, "an open viewer stays as it was")
    }

    @Test
    fun noPhotosOpenNothing() {
        val state = PhotoViewerState()

        state.open(emptyList(), 0)

        assertNull(state.openIds)
    }
}
