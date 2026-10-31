package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.importing.GpxReadException
import com.cowork.bikerecoder.core.importing.GpxReader
import com.cowork.bikerecoder.core.importing.GpxTrack
import com.cowork.bikerecoder.core.importing.ImportItem
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportRules
import com.cowork.bikerecoder.core.importing.ImportStore
import com.cowork.bikerecoder.core.importing.ImportedRide
import com.cowork.bikerecoder.core.importing.ReplayedRide
import com.cowork.bikerecoder.core.importing.RideReplay
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.model.RouteProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the one import job is doing (spec §1.3, §3.4). */
sealed interface ImportJobState {
    data object Idle : ImportJobState

    /** "가져오는 중 12/35": [done] rides handled (saved or skipped) of [total] picked. */
    data class Importing(val done: Int, val total: Int) : ImportJobState

    /** "35개를 가져왔습니다 · 건너뜀 3개 · 멈춤". */
    data class Finished(val imported: Int, val skipped: Int, val stopped: Boolean) : ImportJobState
}

/**
 * The one import job (spec §3.3, §3.4), outside any screen, in [scope]: the picked rides are read again and saved.
 *
 * The files with picked rides are kept readable for the run ([ImportReader.hold] / [ImportReader.release]). Each file
 * is opened once and only its picked items are handled: read → replay → skipped when its start or end differs from the
 * review row (the file changed) → skipped when it now overlaps a segment of the app ([ImportStore.segmentSpans], which
 * includes the rides saved earlier in this run) → [ImportStore.saveImported]. One ride's failure is logged and counted
 * as skipped. A picked ride whose item is gone is skipped too. [stop] ends the run after the ride being saved. When at
 * least one ride was saved, [postProcessor] is asked once at the end.
 */
class GpxImportJob(
    private val reader: ImportReader,
    private val store: ImportStore,
    private val postProcessor: () -> Unit,
    /** The trip's route profile: the default of the settings, read once per run. */
    private val profile: suspend () -> RouteProfile,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val log: WarnLog,
    private val limits: ImportLimits = ImportLimits(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val parse: (ByteArray) -> GpxTrack = GpxReader::read,
) {
    private val _state = MutableStateFlow<ImportJobState>(ImportJobState.Idle)
    val state: StateFlow<ImportJobState> = _state.asStateFlow()

    private val lock = Any()
    private var running = false
    @Volatile private var stopRequested = false

    /**
     * Starts importing [picks] (rows of a scan of [sources]). False, and nothing happens, while a run is going on.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun start(sources: List<String>, picks: List<ScanRow>): Boolean {
        synchronized(lock) {
            if (running) return false
            running = true
            stopRequested = false
            _state.value = ImportJobState.Importing(0, picks.size)
        }
        // Before the screen that received the files can go away; released at the end of the run.
        try {
            reader.hold(pickedFiles(sources, picks))
        } catch (e: Exception) {
            log.warn("Could not keep the picked files readable", e)
        }
        // ATOMIC: even a run cancelled at once reaches its `finally`, which releases the files, ends the state and
        // frees the job.
        scope.launch(start = CoroutineStart.ATOMIC) { run(sources, picks) }
        return true
    }

    /** [멈추기]: the run ends after the ride being saved now. */
    fun stop() {
        stopRequested = true
    }

    /** After the UI has shown the result: [ImportJobState.Finished] → [ImportJobState.Idle] (a run is not touched). */
    fun acknowledge() {
        val s = _state.value
        if (s is ImportJobState.Finished) _state.compareAndSet(s, ImportJobState.Idle)
    }

    /** The files with at least one picked ride: only those are held and released (each grant is a binder call). */
    private fun pickedFiles(sources: List<String>, picks: List<ScanRow>): List<String> =
        picks.map { it.key.sourceIndex }.distinct().mapNotNull(sources::getOrNull)

    private class Counts(val total: Int) {
        var done = 0
        var imported = 0
    }

    private suspend fun run(sources: List<String>, picks: List<ScanRow>) {
        val counts = Counts(picks.size)
        var stopped = false
        try {
            val routeProfile = profile()
            for ((sourceIndex, rows) in picks.groupBy { it.key.sourceIndex }.toSortedMap()) {
                if (stopRequested) break
                importSource(sources.getOrNull(sourceIndex), rows, routeProfile, counts)
            }
            stopped = stopRequested && counts.done < counts.total
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } catch (e: Exception) {
            log.warn("The import run failed", e)
        } finally {
            withContext(NonCancellable) {
                try {
                    withContext(io) { reader.release(pickedFiles(sources, picks)) }
                } catch (e: Exception) {
                    log.warn("Could not release the picked files", e)
                }
            }
            if (counts.imported > 0) {
                try {
                    postProcessor()
                } catch (e: Exception) {
                    log.warn("Could not request the post-processing of the imported rides", e)
                }
            }
            // Not stopped: every picked ride not saved was skipped (including any the failure left out).
            val skipped = if (stopped) counts.done - counts.imported else counts.total - counts.imported
            synchronized(lock) {
                _state.value = ImportJobState.Finished(counts.imported, skipped, stopped)
                running = false
            }
        }
    }

    /** Opens [uri] once and handles the picked [rows] in it; the ones not found there are skipped. */
    private suspend fun importSource(uri: String?, rows: List<ScanRow>, routeProfile: RouteProfile, counts: Counts) {
        val wanted = rows.associateBy { it.key.itemIndex }.toMutableMap()
        if (uri != null) {
            val name = withContext(io) {
                try {
                    reader.displayName(uri)
                } catch (_: Exception) {
                    uri.substringAfterLast('/')
                }
            }
            reader.walk(uri, name, limits, io) { item ->
                // Checked on every item, picked or not: a stop never waits for a later pick of a big ZIP.
                if (stopRequested) return@walk false
                if (item is ImportItem.TooManyEntries) return@walk true
                val row = wanted.remove(item.index) ?: return@walk true
                if (stopRequested) return@walk false
                if (importOne(item, row, routeProfile)) counts.imported++
                counts.done++
                _state.value = ImportJobState.Importing(counts.done, counts.total)
                !stopRequested && wanted.isNotEmpty()
            }
        }
        if (stopRequested) return
        // Gone from the file (or the file is gone): skipped.
        counts.done += wanted.size
        if (wanted.isNotEmpty()) _state.value = ImportJobState.Importing(counts.done, counts.total)
    }

    /** True when [row]'s ride was saved. Any failure is logged and makes it skipped. */
    private suspend fun importOne(item: ImportItem, row: ScanRow, routeProfile: RouteProfile): Boolean {
        if (item !is ImportItem.Gpx) return false
        try {
            val ride = withContext(compute) { replay(item) } ?: return false
            if (ride.startedAt != row.startedAt || ride.endedAt != row.endedAt) return false
            val span = TimeSpan(ride.startedAt, ride.endedAt)
            if (store.segmentSpans(clock()).any { ImportRules.overlaps(span, it) }) return false
            store.saveImported(ImportedRide(ride, routeProfile, item.name, clock()))
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not import ride ${row.key}", e)
            return false
        }
    }

    /**
     * Null when the file no longer reads as a ride, or is too big ([OutOfMemoryError]) or too deep
     * ([StackOverflowError]) to parse and replay: that ride is skipped and the run goes on.
     */
    private fun replay(item: ImportItem.Gpx): ReplayedRide? = try {
        RideReplay.replay(parse(item.bytes).fixes)
    } catch (_: GpxReadException) {
        null
    } catch (e: OutOfMemoryError) {
        log.warn("A picked ride is too large to import", e)
        null
    } catch (e: StackOverflowError) {
        log.warn("A picked ride is too deep to import", e)
        null
    }
}
