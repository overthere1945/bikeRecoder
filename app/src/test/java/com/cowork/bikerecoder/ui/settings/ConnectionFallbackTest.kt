package com.cowork.bikerecoder.ui.settings

import com.cowork.bikerecoder.post.PhotoAccess
import com.cowork.bikerecoder.ui.health.HeartRateAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [허용] in "사진·심박 연결" is never a dead end: when the system will not ask, the row opens the settings instead. */
class ConnectionFallbackTest {

    @Test
    fun `photos still not allowed and Android will not ask again open the app settings`() {
        assertEquals(true, opensAppSettingsAfterPhotoRequest(PhotoAccess.NONE, androidMayAskAgain = false))
    }

    @Test
    fun `an ordinary first refusal stays on the screen`() {
        assertEquals(false, opensAppSettingsAfterPhotoRequest(PhotoAccess.NONE, androidMayAskAgain = true))
    }

    @Test
    fun `photos allowed in any way open nothing`() {
        for (access in listOf(PhotoAccess.PARTIAL, PhotoAccess.FULL)) {
            assertEquals(false, opensAppSettingsAfterPhotoRequest(access, androidMayAskAgain = false), "$access")
            assertEquals(false, opensAppSettingsAfterPhotoRequest(access, androidMayAskAgain = true), "$access")
        }
    }

    @Test
    fun `heart rate still not allowed opens the Health Connect settings`() {
        assertEquals(true, opensHealthConnectSettingsAfterRequest(HeartRateAccess.NO_PERMISSION))
    }

    @Test
    fun `heart rate allowed, Health Connect gone or still unknown opens nothing`() {
        for (access in listOf(HeartRateAccess.GRANTED, HeartRateAccess.UNAVAILABLE, HeartRateAccess.CHECKING)) {
            assertEquals(false, opensHealthConnectSettingsAfterRequest(access), "$access")
        }
    }
}
