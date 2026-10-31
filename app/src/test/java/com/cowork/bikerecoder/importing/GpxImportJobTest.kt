package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.importing.TimeSpan
import com.cowork.bikerecoder.core.model.RouteProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GpxImportJobTest {

    private val reader = FakeImportReader()
    private val store = FakeImportStore()
    private val hour = 3_600_000L
    private val now = T0 + 1_000 * hour
    private val postRequests = AtomicInteger()
    private val profileReads = AtomicInteger()
    private val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = scope.cancel()

    private val job = GpxImportJob(
        reader = reader,
        store = store,
        postProcessor = { postRequests.incrementAndGet() },
        profile = {
            profileReads.incrementAndGet()
            RouteProfile.SHORTEST
        },
        clock = { now },
        scope = scope,
        log = WarnLog { message, _ -> warnings += message },
    )

    private fun scan(uris: List<String>): ScanResult =
        runBlocking { withTimeout(20_000) { ImportScanner(reader, store, clock = { now }).scan(uris) { _, _ -> } } }

    private fun finished(): ImportJobState.Finished = runBlocking {
        withTimeout(20_000) { job.state.first { it is ImportJobState.Finished } as ImportJobState.Finished }
    }

    /** a.gpx (T0), b.zip (T0+1h, T0+2h), c.gpx (T0+3h). */
    private fun threeFiles(): List<String> = listOf(
        reader.add("a.gpx", gpx(T0)),
        reader.add("b.zip", zip("1.gpx" to gpx(T0 + hour), "2.gpx" to gpx(T0 + 2 * hour))),
        reader.add("c.gpx", gpx(T0 + 3 * hour)),
    )

    @Test
    fun importsOnlyPicks() {
        val scanned = scan(threeFiles())
        val picks = scanned.rows.filter { it.key in setOf(RideKey(1, 1), RideKey(0, 0)) }

        assertTrue(job.start(scanned.sources, picks))

        assertEquals(ImportJobState.Finished(imported = 2, skipped = 0, stopped = false), finished())
        assertEquals(setOf(T0, T0 + 2 * hour), store.saved.map { it.ride.startedAt }.toSet())
        assertEquals(setOf("a.gpx", "b.zip / 2.gpx"), store.saved.map { it.fileName }.toSet())
        assertTrue(store.saved.all { it.profile == RouteProfile.SHORTEST && it.importedAt == now })
        assertEquals(1, profileReads.get(), "the profile is read once per run")
        // Each file once; c.gpx not at all.
        assertEquals(listOf(scanned.sources[0], scanned.sources[1]), reader.opened.drop(3))
    }

    @Test
    fun rechecksOverlapBeforeSave() {
        val scanned = scan(threeFiles())
        store.spans += TimeSpan(T0 + hour + 5_000, T0 + hour + 10_000)

        job.start(scanned.sources, scanned.rows)

        assertEquals(ImportJobState.Finished(imported = 3, skipped = 1, stopped = false), finished())
        assertFalse(store.saved.any { it.ride.startedAt == T0 + hour })
    }

    @Test
    fun changedFileIsSkipped() {
        val uris = threeFiles()
        val scanned = scan(uris)
        reader.files[uris[0]] = gpx(T0 + 30 * hour) // other times
        reader.files.remove(uris[2]) // gone

        job.start(scanned.sources, scanned.rows)

        assertEquals(ImportJobState.Finished(imported = 2, skipped = 2, stopped = false), finished())
        assertEquals(setOf(T0 + hour, T0 + 2 * hour), store.saved.map { it.ride.startedAt }.toSet())
    }

    @Test
    fun oneFailureContinues() {
        val scanned = scan(threeFiles())
        store.onSave = { if (it.ride.startedAt == T0 + hour) error("disk full") }

        job.start(scanned.sources, scanned.rows)

        assertEquals(ImportJobState.Finished(imported = 3, skipped = 1, stopped = false), finished())
        assertEquals(1, warnings.size, warnings.toString())
        assertEquals(1, postRequests.get())
    }

    @Test
    fun stopAfterCurrentRide() {
        val scanned = scan(threeFiles())
        val saving = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        store.onSave = {
            saving.complete(Unit)
            stopped.await()
        }

        job.start(scanned.sources, scanned.rows)
        runBlocking {
            withTimeout(20_000) { saving.await() }
            job.stop()
            stopped.complete(Unit)
        }

        assertEquals(ImportJobState.Finished(imported = 1, skipped = 0, stopped = true), finished())
        assertEquals(1, store.saved.size, "the ride being saved is kept")
        assertEquals(1, postRequests.get(), "a stopped run with a saved ride is post-processed")
    }

    @Test
    fun postProcessorOnceWhenSaved() {
        val scanned = scan(threeFiles())

        job.start(scanned.sources, scanned.rows)

        assertEquals(ImportJobState.Finished(imported = 4, skipped = 0, stopped = false), finished())
        assertEquals(1, postRequests.get())
    }

    @Test
    fun noPostProcessorWhenNothingSaved() {
        val scanned = scan(threeFiles())
        store.spans += TimeSpan(T0 - hour, T0 + 10 * hour)

        job.start(scanned.sources, scanned.rows)

        assertEquals(ImportJobState.Finished(imported = 0, skipped = 4, stopped = false), finished())
        assertEquals(0, postRequests.get())
    }

    @Test
    fun startWhileImportingReturnsFalse() {
        val scanned = scan(threeFiles())
        val gate = CompletableDeferred<Unit>()
        store.onSave = { gate.await() }

        assertTrue(job.start(scanned.sources, scanned.rows))
        assertFalse(job.start(scanned.sources, scanned.rows))
        gate.complete(Unit)
        finished()

        store.onSave = {}
        job.acknowledge()
        assertTrue(job.start(scanned.sources, emptyList()), "a finished job starts again")
        assertEquals(ImportJobState.Finished(0, 0, false), finished())
    }

    @Test
    fun holdsAndReleasesUris() {
        val scanned = scan(threeFiles())
        val heldWhileSaving = CompletableDeferred<Pair<Int, Int>>()
        store.onSave = { heldWhileSaving.complete(reader.held.size to reader.released.size) }

        job.start(scanned.sources, scanned.rows.take(1))
        assertEquals(listOf(scanned.sources), reader.held.toList(), "held when start returns")
        finished()

        assertEquals(1 to 0, runBlocking { heldWhileSaving.await() }, "held during the run, released after")
        assertEquals(listOf(scanned.sources), reader.held)
        assertEquals(listOf(scanned.sources), reader.released)
    }

    @Test
    fun stateFlowIdleImportingFinished() {
        val scanned = scan(threeFiles())
        val picks = scanned.rows.take(2)
        assertEquals(ImportJobState.Idle, job.state.value)
        val firstGate = CompletableDeferred<Unit>()
        val statesAtSave: MutableList<ImportJobState> = Collections.synchronizedList(mutableListOf())
        store.onSave = {
            statesAtSave += job.state.value
            firstGate.await()
        }

        job.start(scanned.sources, picks)
        assertEquals(ImportJobState.Importing(0, 2), job.state.value)
        firstGate.complete(Unit)

        assertEquals(ImportJobState.Finished(2, 0, false), finished())
        assertEquals(listOf(ImportJobState.Importing(0, 2), ImportJobState.Importing(1, 2)), statesAtSave)
        job.acknowledge()
        assertEquals(ImportJobState.Idle, job.state.value)
    }

    @Test
    fun stopSkipsLaterPicksInSameZip() {
        val entries = listOf("first.gpx" to gpx(T0)) +
            (1..40).map { "notes/$it.txt" to "not a ride".toByteArray() } +
            listOf("last.gpx" to gpx(T0 + hour))
        val bundle = reader.add("big.zip", zip(*entries.toTypedArray()))
        val scanned = scan(listOf(bundle))
        val picks = scanned.rows.filter { it.startedAt != null }
        assertEquals(listOf(RideKey(0, 41), RideKey(0, 0)), picks.map { it.key })
        // After the first ride is saved, the reading pauses among the unpicked notes until the stop is pressed.
        val walkingNotes = CountDownLatch(1)
        val resume = CountDownLatch(1)
        reader.wrap = { input ->
            object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (store.saved.isNotEmpty() && walkingNotes.count > 0) {
                        walkingNotes.countDown()
                        resume.await(20, TimeUnit.SECONDS)
                    }
                    return input.read(b, off, minOf(len, 64))
                }
            }
        }

        job.start(scanned.sources, picks)
        assertTrue(walkingNotes.await(20, TimeUnit.SECONDS))
        job.stop()
        resume.countDown()

        assertEquals(ImportJobState.Finished(imported = 1, skipped = 0, stopped = true), finished())
        assertEquals(listOf(T0), store.saved.map { it.ride.startedAt })
    }
}
