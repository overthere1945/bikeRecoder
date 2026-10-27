package com.cowork.bikerecoder.offline

/** CORRIDOR + OVERVIEW are a trip's navigation map; VIDEO is the map around a finished trip's track for its video (spec §3.1①). */
enum class RegionKind {
    CORRIDOR,
    OVERVIEW,
    VIDEO,
    ;

    companion object {
        /** The kinds that make up a trip's navigation map ([decideDownload]). */
        val TRIP_MAP: Set<RegionKind> = setOf(CORRIDOR, OVERVIEW)
    }
}

/**
 * The metadata stored with every MapLibre offline region the app creates:
 * `{"tripId":N,"kind":"CORRIDOR"|"OVERVIEW"|"VIDEO","truncated":true|false}` (UTF-8). Regions without it
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

/** The trip's navigation map only: VIDEO regions are left out (they come and go with a video). */
fun decideDownload(existing: List<ExistingRegion>): DownloadDecision {
    val tripMap = existing.filter { it.kind in RegionKind.TRIP_MAP }
    return when {
        tripMap.isEmpty() -> DownloadDecision.DOWNLOAD
        tripMap.size == RegionKind.TRIP_MAP.size &&
            tripMap.map { it.kind }.toSet() == RegionKind.TRIP_MAP &&
            tripMap.all { !it.truncated && it.complete } -> DownloadDecision.SKIP
        else -> DownloadDecision.REPLACE
    }
}
