package com.cowork.bikerecoder.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What each button really sends of the selected cards (spec §5). */
class SendPlanTest {
    private val mb = 1024L * 1024
    private fun file(n: Int, size: Long? = 100L) = ShareFile("content://cache/card$n.jpg", "image/jpeg", size)

    @Test
    fun storySendsFirstOnly() {
        val files = listOf(file(1), file(2), file(3))

        val plan = planSend(ShareTarget.STORY, files, instagramMultiple = true)

        assertEquals(listOf(files[0]), plan.files)
        assertNull(plan.notice)
    }

    @Test
    fun instagramSingleOnly() {
        val files = listOf(file(1), file(2), file(3))

        val plan = planSend(ShareTarget.INSTAGRAM, files, instagramMultiple = false)

        assertEquals(listOf(files[0]), plan.files)
        assertEquals(MSG_INSTAGRAM_ONE, plan.notice)
        assertEquals("인스타그램이 한 장만 받아 첫 장만 보냅니다", plan.notice)
    }

    @Test
    fun instagramSingleOnlyWithOneFileHasNoNotice() {
        val plan = planSend(ShareTarget.INSTAGRAM, listOf(file(1)), instagramMultiple = false)

        assertEquals(listOf(file(1)), plan.files)
        assertNull(plan.notice)
    }

    @Test
    fun instagramMultipleKeepsAll() {
        val files = listOf(file(1), file(2), file(3))

        val plan = planSend(ShareTarget.INSTAGRAM, files, instagramMultiple = true)

        assertEquals(files, plan.files)
        assertNull(plan.notice)
    }

    @Test
    fun emailTrimsFromEnd() {
        val files = listOf(file(1, 8 * mb), file(2, 8 * mb), file(3, 8 * mb))

        val plan = planSend(ShareTarget.EMAIL, files, instagramMultiple = true)

        assertEquals(files.take(2), plan.files)
        assertEquals("용량 때문에 2장만 붙였습니다", plan.notice)
    }

    @Test
    fun emailExactlyAtTheLimitIsKept() {
        val files = listOf(file(1, EMAIL_MAX_BYTES / 2), file(2, EMAIL_MAX_BYTES / 2))

        val plan = planSend(ShareTarget.EMAIL, files, instagramMultiple = true)

        assertEquals(files, plan.files)
        assertNull(plan.notice)
    }

    @Test
    fun emailUnknownSizeCountsAsOver() {
        val files = listOf(file(1, mb), file(2, null), file(3, mb))

        val plan = planSend(ShareTarget.EMAIL, files, instagramMultiple = true)

        assertEquals(files.take(1), plan.files)
        assertEquals(msgEmailTrimmed(1), plan.notice)
    }

    @Test
    fun emailNothingFitsIsTextOnly() {
        val files = listOf(file(1, EMAIL_MAX_BYTES + 1), file(2, mb))

        val plan = planSend(ShareTarget.EMAIL, files, instagramMultiple = true)

        assertEquals(emptyList<ShareFile>(), plan.files)
        assertNull(plan.notice)
    }

    @Test
    fun facebookKeepsOrder() {
        val files = listOf(file(3), file(1), file(2))

        val plan = planSend(ShareTarget.FACEBOOK, files, instagramMultiple = false)

        assertEquals(files, plan.files)
        assertNull(plan.notice)
    }
}
