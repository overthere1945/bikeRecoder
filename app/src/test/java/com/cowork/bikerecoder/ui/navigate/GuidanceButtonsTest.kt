package com.cowork.bikerecoder.ui.navigate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The guidance screen's button row (spec §3): V1.0.0 without a recording, [안내 취소] [주행 종료] with one. */
class GuidanceButtonsTest {

    @Test
    fun withoutRecordingTheV1Buttons() {
        assertEquals(listOf("음소거", "전체 경로", "종료"), guidanceButtons(recording = false).map { it.label })
    }

    @Test
    fun whileRecordingCancelAndEndRide() {
        assertEquals(listOf("음소거", "안내 취소", "주행 종료"), guidanceButtons(recording = true).map { it.label })
    }
}
