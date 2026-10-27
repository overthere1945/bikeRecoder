package com.cowork.bikerecoder.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VideoNotificationsTest {

    private val video = TripMedia(3, 7, MediaKind.VIDEO, "content://v/1", 0, "{}")

    @Test
    fun `progress while making`() {
        assertEquals("영상 만드는 중 42% · 약 2분 남음", VideoNotifications.progressText(VideoJobState.Rendering(42, 2)))
        assertEquals("영상 만드는 중 0%", VideoNotifications.progressText(VideoJobState.Preparing(MediaKind.VIDEO)))
        assertEquals("영상 만드는 중 100%", VideoNotifications.progressText(VideoJobState.Encoding))
        assertEquals("이미지 만드는 중", VideoNotifications.progressText(VideoJobState.Preparing(MediaKind.IMAGE)))
    }

    @Test
    fun `no progress once it ended`() {
        assertNull(VideoNotifications.progressText(VideoJobState.Idle))
        assertNull(VideoNotifications.progressText(VideoJobState.Done(video, downscaled = false)))
        assertNull(VideoNotifications.progressText(VideoJobState.Failed("x")))
        assertNull(VideoNotifications.progressText(VideoJobState.Cancelled))
    }

    @Test
    fun `what is said at the end`() {
        assertEquals("영상을 만들었습니다", VideoNotifications.resultText(VideoJobState.Done(video, downscaled = true)))
        assertEquals("이미지를 만들었습니다", VideoNotifications.resultText(VideoJobState.Done(video.copy(kind = MediaKind.IMAGE), false)))
        assertEquals(
            "기록이 시작되어 영상 만들기를 멈췄습니다",
            VideoNotifications.resultText(VideoJobState.Failed("기록이 시작되어 영상 만들기를 멈췄습니다")),
        )
        assertNull(VideoNotifications.resultText(VideoJobState.Cancelled))
        assertNull(VideoNotifications.resultText(VideoJobState.Idle))
        assertNull(VideoNotifications.resultText(VideoJobState.Rendering(1, 1)))
    }
}
