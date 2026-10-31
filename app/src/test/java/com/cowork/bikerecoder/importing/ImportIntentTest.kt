package com.cowork.bikerecoder.importing

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImportIntentTest {

    private val a = "content://com.android.providers.downloads.documents/document/1"
    private val b = "content://media/external/file/2"

    private fun parse(
        action: String?,
        stream: String? = null,
        streams: List<String> = emptyList(),
        data: String? = null,
        clip: List<String> = emptyList(),
        fromHistory: Boolean = false,
    ) = importUris(action, stream, streams, data, clip, fromHistory)

    @Test
    fun sendTakesTheStream() {
        assertEquals(listOf(a), parse(Intent.ACTION_SEND, stream = a, clip = listOf(b)))
    }

    @Test
    fun sendMultipleTakesTheStreamsWithoutRepeats() {
        assertEquals(listOf(a, b), parse(Intent.ACTION_SEND_MULTIPLE, streams = listOf(a, b, a)))
    }

    @Test
    fun viewTakesTheData() {
        assertEquals(listOf(a), parse(Intent.ACTION_VIEW, data = a))
    }

    @Test
    fun onlyContentUris() {
        assertEquals(
            listOf(b),
            parse(Intent.ACTION_SEND_MULTIPLE, streams = listOf("file:///sdcard/a.gpx", b, "https://x/y.gpx", "ftp://x/c")),
        )
        assertEquals(emptyList<String>(), parse(Intent.ACTION_VIEW, data = "file:///sdcard/a.gpx"))
    }

    @Test
    fun noStreamFallsBackToTheClip() {
        assertEquals(listOf(a, b), parse(Intent.ACTION_SEND, clip = listOf(a, b)))
        assertEquals(listOf(b), parse(Intent.ACTION_SEND_MULTIPLE, clip = listOf(b)))
    }

    @Test
    fun fromHistoryOrOtherActionsGiveNothing() {
        assertEquals(emptyList<String>(), parse(Intent.ACTION_SEND, stream = a, fromHistory = true))
        assertEquals(emptyList<String>(), parse(Intent.ACTION_MAIN, stream = a, data = a))
        assertEquals(emptyList<String>(), parse(null, data = a))
    }
}
