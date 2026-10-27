package com.cowork.bikerecoder.core.video

import java.time.ZonedDateTime
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MediaNamingTest {

    @Test
    fun nameAndUnique() {
        val start = ZonedDateTime.of(2026, 10, 25, 20, 47, 59, 0, SEOUL).toInstant().toEpochMilli()
        val base = MediaNaming.baseName(start, SEOUL)
        assertEquals("bikeRecoder_20261025_2047", base)
        // 같은 순간도 서울 시간으로 적는다(UTC 11:47).
        assertEquals("bikeRecoder_20261025_1147", MediaNaming.baseName(start, java.time.ZoneOffset.UTC))

        assertEquals("$base.mp4", MediaNaming.unique(base, "mp4", emptySet()))
        assertEquals("${base}_2.mp4", MediaNaming.unique(base, "mp4", setOf("$base.mp4")))
        assertEquals("${base}_3.mp4", MediaNaming.unique(base, ".mp4", setOf("$base.mp4", "${base}_2.mp4")))
        // 다른 확장자는 겹치지 않는다.
        assertEquals("$base.png", MediaNaming.unique(base, "png", setOf("$base.mp4")))
    }
}
