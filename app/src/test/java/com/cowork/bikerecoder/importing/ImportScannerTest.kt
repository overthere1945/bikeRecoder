package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.importing.Activity
import com.cowork.bikerecoder.core.importing.ImportLimits
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.core.importing.TimeSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ImportScannerTest {

    private val reader = FakeImportReader()
    private val store = FakeImportStore()
    private val hour = 3_600_000L

    private fun scanner(limits: ImportLimits = ImportLimits()) =
        ImportScanner(reader, store, clock = { T0 + 1_000 * hour }, limits = limits)

    private fun scan(uris: List<String>, limits: ImportLimits = ImportLimits()) =
        runBlocking { withTimeout(20_000) { scanner(limits).scan(uris) { _, _ -> } } }

    @Test
    fun readsFilesAndZipIntoRows() {
        val a = reader.add("a.gpx", gpx(T0))
        val b = reader.add("b.zip", zip("r2.gpx" to gpx(T0 + hour), "dir/r3.gpx" to gpx(T0 + 2 * hour, type = "cycling")))

        val result = scan(listOf(a, b))

        assertEquals(listOf(a, b), result.sources)
        assertEquals(emptyList<String>(), result.notices)
        assertEquals(
            listOf(RideKey(1, 1), RideKey(1, 0), RideKey(0, 0)),
            result.rows.map { it.key },
        )
        assertEquals(listOf("b.zip / dir/r3.gpx", "b.zip / r2.gpx", "a.gpx"), result.rows.map { it.fileName })
        val first = result.rows.last()
        assertEquals(T0, first.startedAt)
        assertEquals(gpxEnd(T0), first.endedAt)
        assertTrue(first.distanceM > 250.0, "distance ${first.distanceM}")
        assertTrue(first.movingTimeMs > 0)
        assertEquals(Activity.CYCLING, first.activity)
        assertEquals(RideStatus.New, first.status)
        assertTrue(first.preview.size >= 2)
        assertTrue(result.rows.all { it.status == RideStatus.New })
    }

    @Test
    fun rowsSortedNewestFirstUnreadableLast() {
        val text = reader.add("notes.txt", "hello".toByteArray())
        val old = reader.add("old.gpx", gpx(T0))
        val noTime = reader.add("notime.gpx", gpx(T0 + 5 * hour).toString(Charsets.UTF_8).replace(Regex("<time>[^<]*</time>"), "").toByteArray())
        val new = reader.add("new.gpx", gpx(T0 + 3 * hour))
        val gone = "content://x/gone.gpx"

        val rows = scan(listOf(text, old, noTime, new, gone)).rows

        assertEquals(listOf("new.gpx", "old.gpx", "notes.txt", "notime.gpx", "gone.gpx"), rows.map { it.fileName })
        assertEquals(
            listOf(
                RideStatus.New,
                RideStatus.New,
                RideStatus.Unreadable(ImportProblem.NOT_GPX),
                RideStatus.Unreadable(ImportProblem.NO_TIME),
                RideStatus.Unreadable(ImportProblem.UNREADABLE),
            ),
            rows.map { it.status },
        )
        val unreadable = rows.last()
        assertNull(unreadable.startedAt)
        assertNull(unreadable.activity)
        assertTrue(unreadable.preview.isEmpty())
    }

    @Test
    fun statusesFromExistingAndDuplicates() {
        store.spans += TimeSpan(T0 + 10_000, T0 + 20_000)
        val overlapping = reader.add("existing.gpx", gpx(T0))
        val first = reader.add("first.gpx", gpx(T0 + hour))
        val same = reader.add("same.zip", zip("copy.gpx" to gpx(T0 + hour + 20_000)))
        val running = reader.add("run.gpx", gpx(T0 + 2 * hour, type = "running"))
        // Touching the existing span's end is not an overlap.
        val touching = reader.add("touch.gpx", gpx(T0 + 3 * hour))
        store.spans += TimeSpan(T0 + 3 * hour - 60_000, T0 + 3 * hour)

        val byName = scan(listOf(overlapping, first, same, running, touching)).rows.associate { it.fileName to it.status }

        assertEquals(RideStatus.OverlapsExisting, byName["existing.gpx"])
        assertEquals(RideStatus.New, byName["first.gpx"])
        assertEquals(RideStatus.DuplicateInList, byName["same.zip / copy.gpx"])
        assertEquals(RideStatus.NotCycling, byName["run.gpx"])
        assertEquals(RideStatus.New, byName["touch.gpx"])
    }

    @Test
    fun stopsAt500RidesWithNotice() {
        val junk = (0 until 3).map { reader.add("junk$it.txt", "not a ride".toByteArray()) }
        val rides = (0 until 3).map { reader.add("r$it.gpx", gpx(T0 + it * hour)) }
        val later = reader.add("later.gpx", gpx(T0 + 9 * hour))
        // Unreadable rows around the rides do not count toward the cap.
        val uris = listOf(junk[0], rides[0], junk[1], rides[1], junk[2], rides[2], later)

        val result = scan(uris, ImportLimits(maxRides = 2))

        assertEquals(
            listOf("r1.gpx", "r0.gpx", "junk0.txt", "junk1.txt", "junk2.txt"),
            result.rows.map { it.fileName },
        )
        assertEquals(listOf("주행이 너무 많아 처음 2개만 보여 줍니다"), result.notices)
        assertTrue(later !in reader.opened, "reading stops at the limit")

        // Exactly at the limit (with unreadable rows besides): no notice.
        val atLimit = scan(listOf(junk[0], rides[0], junk[1], rides[1]), ImportLimits(maxRides = 2))
        assertEquals(emptyList<String>(), atLimit.notices)
        assertEquals(4, atLimit.rows.size)
    }

    @Test
    fun zipEntryLimitNotice() {
        val bundle = reader.add(
            "묶음.zip",
            zip("1.gpx" to gpx(T0), "2.gpx" to gpx(T0 + hour), "3.gpx" to gpx(T0 + 2 * hour)),
        )
        val next = reader.add("next.gpx", gpx(T0 + 5 * hour))

        val result = scan(listOf(bundle, next), ImportLimits(maxZipEntries = 2))

        assertEquals(listOf("묶음.zip: 항목이 너무 많아 처음 2개만 읽었습니다"), result.notices)
        assertEquals(listOf("next.gpx", "묶음.zip / 2.gpx", "묶음.zip / 1.gpx"), result.rows.map { it.fileName })
    }

    @Test
    fun tooShortRowIsUnreadable() {
        val short = reader.add("short.gpx", gpx(T0, points = 3, stepM = 1.0))
        val single = reader.add("single.gpx", gpx(T0 + 3_600_000, points = 1))

        val rows = scan(listOf(short, single)).rows

        assertEquals(
            listOf(RideStatus.Unreadable(ImportProblem.TOO_SHORT), RideStatus.Unreadable(ImportProblem.TOO_SHORT)),
            rows.map { it.status },
        )
        assertEquals(listOf("short.gpx", "single.gpx"), rows.map { it.fileName })
        assertTrue(rows.all { it.startedAt == null && it.endedAt == null && it.distanceM == 0.0 })
    }

    @Test
    fun cancelStopsReading() = runBlocking {
        val uris = (0 until 10).map { reader.add("r$it.gpx", gpx(T0 + it * hour)) }
        val firstDone = CompletableDeferred<Unit>()
        val scan = async {
            scanner().scan(uris) { done, _ -> if (done == 1) firstDone.complete(Unit) }
        }
        withTimeout(20_000) { firstDone.await() }
        scan.cancelAndJoin()
        val openedAtCancel = reader.opened.size
        delay(300)

        assertTrue(openedAtCancel <= 3, "opened $openedAtCancel")
        assertEquals(openedAtCancel, reader.opened.size, "nothing is opened after the cancel")
        assertThrows<CancellationException> { runBlocking { scan.await() } }
        Unit
    }

    @Test
    fun progressCountsSources() {
        val uris = listOf(
            reader.add("a.gpx", gpx(T0)),
            reader.add("b.zip", zip("1.gpx" to gpx(T0 + hour), "2.gpx" to gpx(T0 + 2 * hour))),
            reader.add("c.txt", "x".toByteArray()),
        )
        val calls = mutableListOf<Pair<Int, Int>>()

        runBlocking { scanner().scan(uris) { done, total -> synchronized(calls) { calls += done to total } } }

        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), calls)
    }

    @Test
    fun runsOnOneTestDispatcher() = runTest {
        val one = StandardTestDispatcher(testScheduler)
        val uris = listOf(
            reader.add("a.gpx", gpx(T0)),
            reader.add("b.zip", zip("1.gpx" to gpx(T0 + hour), "2.txt" to "x".toByteArray())),
        )

        val result = ImportScanner(reader, store, clock = { T0 + 1_000 * hour }, compute = one, io = one).scan(uris) { _, _ -> }

        assertEquals(listOf("b.zip / 1.gpx", "a.gpx", "b.zip / 2.txt"), result.rows.map { it.fileName })
    }
}
