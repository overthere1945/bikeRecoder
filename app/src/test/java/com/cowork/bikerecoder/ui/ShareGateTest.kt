package com.cowork.bikerecoder.ui

import com.cowork.bikerecoder.ui.video.VideoOutcome
import com.cowork.bikerecoder.video.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The made file's preview over every screen waits while the share sheet makes its own image (V1.5.0 spec §2.4). */
class ShareGateTest {
    private val preview = VideoOutcome.Preview("content://media/external/images/media/9", MediaKind.IMAGE, false, false)
    private val failed = VideoOutcome.Message("이미지를 만들지 못했습니다")

    @Test
    fun theSheetTakesTheOutcomeWhileItMakes() {
        assertNull(hostOutcome(preview, shareMaking = true))
        assertNull(hostOutcome(failed, shareMaking = true))
    }

    /** Making ended (the sheet closed, or [취소] after Done) with the outcome still there: shown here as usual. */
    @Test
    fun otherwiseTheHostShowsIt() {
        assertEquals(preview, hostOutcome(preview, shareMaking = false))
        assertEquals(failed, hostOutcome(failed, shareMaking = false))
        assertNull(hostOutcome(null, shareMaking = false))
    }
}
