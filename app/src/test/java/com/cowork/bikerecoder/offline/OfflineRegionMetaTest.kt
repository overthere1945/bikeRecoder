package com.cowork.bikerecoder.offline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OfflineRegionMetaTest {

    @Test
    fun encodesTheDocumentedJsonShape() {
        val bytes = OfflineRegionMeta(tripId = 42, kind = RegionKind.CORRIDOR, truncated = true).encode()

        assertEquals("""{"tripId":42,"kind":"CORRIDOR","truncated":true}""", String(bytes, Charsets.UTF_8))
        assertEquals(
            """{"tripId":7,"kind":"OVERVIEW","truncated":false}""",
            String(OfflineRegionMeta(7, RegionKind.OVERVIEW, false).encode(), Charsets.UTF_8),
        )
    }

    @Test
    fun decodeRoundTrips() {
        for (kind in RegionKind.entries) for (truncated in listOf(true, false)) {
            val meta = OfflineRegionMeta(123_456_789_012L, kind, truncated)
            assertEquals(meta, OfflineRegionMeta.decode(meta.encode()))
        }
    }

    @Test
    fun decodeToleratesWhitespaceAndKeyOrder() {
        val json = """ { "truncated" : false, "kind" : "OVERVIEW", "tripId" : 5 } """
        assertEquals(OfflineRegionMeta(5, RegionKind.OVERVIEW, false), OfflineRegionMeta.decode(json.toByteArray()))
    }

    @Test
    fun decodeRejectsForeignOrMalformedMetadata() {
        assertNull(OfflineRegionMeta.decode(null))
        assertNull(OfflineRegionMeta.decode(ByteArray(0)))
        assertNull(OfflineRegionMeta.decode("""{"name":"someone else's region"}""".toByteArray()))
        assertNull(OfflineRegionMeta.decode("""{"tripId":1,"kind":"OTHER","truncated":false}""".toByteArray()))
        assertNull(OfflineRegionMeta.decode("""{"tripId":"x","kind":"CORRIDOR","truncated":false}""".toByteArray()))
        assertNull(OfflineRegionMeta.decode("not json".toByteArray()))
    }

    private fun info(kind: RegionKind, truncated: Boolean = false, complete: Boolean = true) =
        ExistingRegion(kind, truncated, complete)

    @Test
    fun noExistingRegionsMeansDownload() {
        assertEquals(DownloadDecision.DOWNLOAD, decideDownload(emptyList()))
    }

    @Test
    fun completeUntruncatedPairIsSkipped() {
        assertEquals(
            DownloadDecision.SKIP,
            decideDownload(listOf(info(RegionKind.CORRIDOR), info(RegionKind.OVERVIEW))),
        )
    }

    @Test
    fun truncatedRegionIsReplaced() {
        assertEquals(
            DownloadDecision.REPLACE,
            decideDownload(listOf(info(RegionKind.CORRIDOR, truncated = true), info(RegionKind.OVERVIEW, truncated = true))),
        )
        assertEquals(
            DownloadDecision.REPLACE,
            decideDownload(listOf(info(RegionKind.CORRIDOR, truncated = true), info(RegionKind.OVERVIEW))),
        )
    }

    @Test
    fun incompleteOrPartialSetIsReplaced() {
        assertEquals(
            DownloadDecision.REPLACE,
            decideDownload(listOf(info(RegionKind.CORRIDOR, complete = false), info(RegionKind.OVERVIEW))),
        )
        assertEquals(DownloadDecision.REPLACE, decideDownload(listOf(info(RegionKind.CORRIDOR))))
        assertEquals(
            DownloadDecision.REPLACE,
            decideDownload(listOf(info(RegionKind.CORRIDOR), info(RegionKind.CORRIDOR), info(RegionKind.OVERVIEW))),
        )
    }
}
