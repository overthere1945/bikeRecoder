package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.importing.Activity
import com.cowork.bikerecoder.core.importing.GpxReadException
import com.cowork.bikerecoder.core.importing.GpxReader
import com.cowork.bikerecoder.core.importing.ImportItem
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.ImportRules
import com.cowork.bikerecoder.core.importing.ImportStore
import com.cowork.bikerecoder.core.importing.RideReplay
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One ride of the picked files: [sourceIndex] in the picked URIs, [itemIndex] the [ImportItem.index] inside it. */
data class RideKey(val sourceIndex: Int, val itemIndex: Int)

/**
 * One row of the review list (spec §1.2), a summary only (spec §3.3): the job reads the file again to save it.
 * An unreadable row has no times, no distance, no activity and no preview; its [fileName] is the first line.
 */
data class ScanRow(
    val key: RideKey,
    val fileName: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val distanceM: Double,
    val movingTimeMs: Long,
    val activity: Activity?,
    val status: RideStatus,
    val preview: List<GeoPoint>,
)

/**
 * What [ImportScanner.scan] found: [rows] newest first with the unreadable ones last (in reading order), [notices]
 * the limits that were hit (spec §5 "제한"), [sources] the picked URIs ([RideKey.sourceIndex] indexes them).
 */
data class ScanResult(val rows: List<ScanRow>, val notices: List<String>, val sources: List<String>)

/**
 * Reads the picked files into review rows (spec §1.2, §3.2, §3.3), for the import screen. Each file is read on [io]
 * ([walk]) one item at a time, each GPX parsed and replayed on [compute] and only its summary kept. At
 * [ImportLimits.maxRides] readable rides the reading stops with a notice (unreadable rows do not count). The statuses come last, against the app's segments at [clock]
 * ([ImportStore.segmentSpans]). Cancelling the calling coroutine stops the reading at once.
 */
class ImportScanner(
    private val reader: ImportReader,
    private val store: ImportStore,
    private val clock: () -> Long,
    private val limits: ImportLimits = ImportLimits(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** [onProgress] gets (files read, files picked): (0, n) first, then once per file. */
    suspend fun scan(uris: List<String>, onProgress: (done: Int, total: Int) -> Unit): ScanResult {
        val read = ArrayList<Summary>()
        val notices = ArrayList<String>()
        var rides = 0
        onProgress(0, uris.size)
        sources@ for ((sourceIndex, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            val name = withContext(io) { safeDisplayName(uri) }
            var full = false
            reader.walk(uri, name, limits, io) { item ->
                if (item is ImportItem.TooManyEntries) {
                    notices += tooManyEntriesText(item.name, limits.maxZipEntries)
                    return@walk true
                }
                val summary = summarize(RideKey(sourceIndex, item.index), item)
                // The cap counts rides found; unreadable rows are bounded by the ZIP entry limit.
                if (summary.span != null && rides >= limits.maxRides) {
                    full = true
                    return@walk false
                }
                if (summary.span != null) rides++
                read += summary
                true
            }
            if (full) {
                notices += tooManyRidesText(limits.maxRides)
                break@sources
            }
            onProgress(sourceIndex + 1, uris.size)
        }

        val existing = store.segmentSpans(clock())
        val statuses = ImportRules.statuses(read.map { it.span }, read.map { it.row.activity }, existing)
        val rows = read.mapIndexed { i, s ->
            // statuses() leaves a placeholder for unreadable rows: the row keeps its own reason.
            if (s.span == null) s.row else s.row.copy(status = statuses[i])
        }
        val (readable, unreadable) = rows.partition { it.startedAt != null }
        return ScanResult(readable.sortedByDescending { it.startedAt } + unreadable, notices, uris)
    }

    private class Summary(val row: ScanRow, val span: TimeSpan?)

    private fun safeDisplayName(uri: String): String = try {
        reader.displayName(uri)
    } catch (_: Exception) {
        uri.substringAfterLast('/')
    }

    /** The row of [item]; its bytes are dropped once this returns. */
    private suspend fun summarize(key: RideKey, item: ImportItem): Summary = when (item) {
        is ImportItem.Gpx -> withContext(compute) { summarizeGpx(key, item) }
        is ImportItem.Unreadable -> unreadable(key, item.name, item.problem)
        is ImportItem.TooManyEntries -> error("TooManyEntries is a notice, not a row")
    }

    private fun summarizeGpx(key: RideKey, item: ImportItem.Gpx): Summary {
        val track = try {
            GpxReader.read(item.bytes)
        } catch (e: GpxReadException) {
            return unreadable(key, item.name, e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return unreadable(key, item.name, ImportProblem.UNREADABLE)
        }
        val ride = RideReplay.replay(track.fixes) ?: return unreadable(key, item.name, ImportProblem.TOO_SHORT)
        val row = ScanRow(
            key = key,
            fileName = item.name,
            startedAt = ride.startedAt,
            endedAt = ride.endedAt,
            distanceM = ride.totals.distanceM,
            movingTimeMs = ride.totals.movingTimeMs,
            activity = ImportRules.activityOf(track.type),
            status = RideStatus.New,
            preview = ImportRules.previewPoints(ride.points),
        )
        return Summary(row, TimeSpan(ride.startedAt, ride.endedAt))
    }

    private fun unreadable(key: RideKey, name: String, problem: ImportProblem) = Summary(
        ScanRow(key, name, null, null, 0.0, 0L, null, RideStatus.Unreadable(problem), emptyList()),
        span = null,
    )
}
