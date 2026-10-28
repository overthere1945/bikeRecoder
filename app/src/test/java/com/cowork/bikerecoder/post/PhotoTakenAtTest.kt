package com.cowork.bikerecoder.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhotoTakenAtTest {
    @Test
    fun `date taken is used when present`() {
        assertEquals(1_700_000_000_123L, photoTakenAt(dateTaken = 1_700_000_000_123L, dateAddedSeconds = 5L))
    }

    @Test
    fun `missing or zero date taken falls back to date added in milliseconds`() {
        assertEquals(1_700_000_000_000L, photoTakenAt(dateTaken = null, dateAddedSeconds = 1_700_000_000L))
        assertEquals(1_700_000_000_000L, photoTakenAt(dateTaken = 0L, dateAddedSeconds = 1_700_000_000L))
    }
}
