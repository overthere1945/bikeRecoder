package com.cowork.bikerecoder.share

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [저장]·[스티커]가 갤러리에 넣는 파일 이름(공유 카드 설계 §5.5, §5.6). */
class ShareGalleryNamesTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val at = ZonedDateTime.of(2026, 11, 2, 9, 5, 7, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun cardName() {
        assertEquals("bikeRecoder_card_20261102_090507.jpg", ShareGalleryNames.card(at, seoul))
    }

    @Test
    fun stickerName() {
        assertEquals("bikeRecoder_sticker_20261102_090507.png", ShareGalleryNames.sticker(at, seoul))
    }
}
