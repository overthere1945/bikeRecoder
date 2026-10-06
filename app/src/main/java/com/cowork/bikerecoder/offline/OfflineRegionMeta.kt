package com.cowork.bikerecoder.offline

enum class RegionKind { CORRIDOR, OVERVIEW }

/**
 * The metadata stored with every MapLibre offline region the app creates:
 * `{"tripId":N,"kind":"CORRIDOR"|"OVERVIEW","truncated":true|false}` (UTF-8). Regions without it
 * (or with anything else) are not ours and are never touched.
 */
data class OfflineRegionMeta(val tripId: Long, val kind: RegionKind, val truncated: Boolean) {

    fun encode(): ByteArray =
        """{"tripId":$tripId,"kind":"${kind.name}","truncated":$truncated}""".toByteArray(Charsets.UTF_8)

    companion object {
        private val TRIP_ID = Regex(""""tripId"\s*:\s*(-?\d+)""")
        private val KIND = Regex(""""kind"\s*:\s*"([A-Z]+)"""")
        private val TRUNCATED = Regex(""""truncated"\s*:\s*(true|false)""")

        /** Null for missing, foreign or malformed metadata. */
        fun decode(bytes: ByteArray?): OfflineRegionMeta? {
            if (bytes == null || bytes.isEmpty()) return null
            val text = String(bytes, Charsets.UTF_8)
            val tripId = TRIP_ID.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val kind = KIND.find(text)?.groupValues?.get(1)?.let { name -> RegionKind.entries.find { it.name == name } }
                ?: return null
            val truncated = TRUNCATED.find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: return null
            return OfflineRegionMeta(tripId, kind, truncated)
        }
    }
}

/** A region the trip already has, as found in MapLibre. */
data class ExistingRegion(val kind: RegionKind, val truncated: Boolean, val complete: Boolean)

enum class DownloadDecision {
    /** Nothing there yet: download. */
    DOWNLOAD,

    /** A complete, untruncated corridor + overview already cover the whole route. */
    SKIP,

    /** Truncated (next day's [이어서 안내] downloads the next stretch), partial or unfinished: delete, then download again. */
    REPLACE,
}

fun decideDownload(existing: List<ExistingRegion>): DownloadDecision = when {
    existing.isEmpty() -> DownloadDecision.DOWNLOAD
    existing.size == RegionKind.entries.size &&
        existing.map { it.kind }.toSet().size == RegionKind.entries.size &&
        existing.all { !it.truncated && it.complete } -> DownloadDecision.SKIP
    else -> DownloadDecision.REPLACE
}
