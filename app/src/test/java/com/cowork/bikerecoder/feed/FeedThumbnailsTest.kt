package com.cowork.bikerecoder.feed

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream

/**
 * 피드 위성 그림의 대기 줄(스펙 §2.4, §4): 한 번에 하나씩 `want` 순서로, 기록·안내·영상이 돌면 쉬고, 실패는 이 프로세스에서
 * 다시 안 하고(연결이 돌아오면 지운다), 저장은 임시 파일을 거치고, 지워진 여행의 그림은 남지 않는다.
 * 가짜 그림 만들기·가짜 WebP 쓰기(이 JVM에는 Bitmap이 없다), 임시 폴더, 시험용 시계.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedThumbnailsTest {

    @TempDir
    lateinit var dir: File

    private val busy = MutableStateFlow(false)
    private val online = MutableStateFlow(true)
    private val warnings = mutableListOf<Pair<String, Throwable?>>()

    private val calls = mutableListOf<Long>()
    private var inFlight = 0
    private var maxInFlight = 0
    private val gates = HashMap<Long, CompletableDeferred<Unit>>()
    private val missing = HashSet<Long>()
    private val broken = HashSet<Long>()
    private val outOfMemory = HashSet<Long>()
    private val recycled = mutableListOf<Bitmap>()

    /** What a "picture" is here: an allocated Bitmap that is never touched (its methods are stubs in this JVM). */
    private fun fakeBitmap(): Bitmap {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    private val writer = object : BitmapWriter {
        override fun write(bitmap: Bitmap, out: OutputStream) = out.write("webp".toByteArray())
        override fun recycle(bitmap: Bitmap) {
            recycled += bitmap
        }
    }

    private suspend fun render(tripId: Long): Bitmap? {
        calls += tripId
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            gates[tripId]?.await() ?: delay(100)
            if (tripId in outOfMemory) throw OutOfMemoryError("bitmap $tripId")
            if (tripId in broken) throw IllegalStateException("render of $tripId failed")
            return if (tripId in missing) null else fakeBitmap()
        } finally {
            inFlight--
        }
    }

    /** Not [TestScope.backgroundScope]: [advanceUntilIdle] does not run a background scope's work. */
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.thumbs() = FeedThumbnails(
        dir, ::render, busy, online, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }, { m, e -> warnings += m to e },
        io = StandardTestDispatcher(testScheduler), writer = writer,
    )

    private fun names() = dir.list().orEmpty().sorted()

    private fun FeedThumbnails.names() = files.value.mapValues { it.value.name }

    @Test
    fun rendersOneAtATimeInWantOrder() = runTest {
        val thumbs = thumbs()

        thumbs.want(3, "c")
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        advanceUntilIdle()

        assertEquals(listOf(3L, 1L, 2L), calls)
        assertEquals(1, maxInFlight)
        assertEquals(mapOf(3L to "3-c.webp", 1L to "1-a.webp", 2L to "2-b.webp"), thumbs.names())
        assertEquals(listOf("1-a.webp", "2-b.webp", "3-c.webp"), names())
        assertEquals(3, recycled.size)
    }

    @Test
    fun unwantRemovesFromQueue() = runTest {
        busy.value = true
        val thumbs = thumbs()
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        thumbs.unwant(1)
        runCurrent()

        busy.value = false
        advanceUntilIdle()

        assertEquals(listOf(2L), calls)
        assertEquals(mapOf(2L to "2-b.webp"), thumbs.names())
    }

    @Test
    fun reWantKeepsItsPlaceAndTakesTheNewKey() = runTest {
        busy.value = true
        val thumbs = thumbs()
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        thumbs.want(1, "a2")
        runCurrent()

        busy.value = false
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), calls)
        assertEquals(mapOf(1L to "1-a2.webp", 2L to "2-b.webp"), thumbs.names())
    }

    @Test
    fun failureNotRetriedUntilOnlineAgain() = runTest {
        val thumbs = thumbs()
        missing += 1
        thumbs.want(1, "a")
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)

        // Scrolled away and back: not tried again in this process.
        thumbs.unwant(1)
        thumbs.want(1, "a")
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)

        // Offline and back online: forgotten, so tried again (and made this time).
        missing.clear()
        online.value = false
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)
        online.value = true
        advanceUntilIdle()
        assertEquals(listOf(1L, 1L), calls)
        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())
    }

    @Test
    fun exceptionIsLoggedAndRememberedPerKey() = runTest {
        val thumbs = thumbs()
        broken += 1
        thumbs.want(1, "a")
        advanceUntilIdle()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().second is IllegalStateException)

        thumbs.want(1, "a")
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)

        // Another key (the trip changed) is another picture: tried.
        thumbs.want(1, "b")
        advanceUntilIdle()
        assertEquals(listOf(1L, 1L), calls)
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun newKeyReplacesOldFile() = runTest {
        val thumbs = thumbs()
        thumbs.want(1, "a")
        thumbs.want(2, "x")
        advanceUntilIdle()
        assertEquals(listOf("1-a.webp", "2-x.webp"), names())

        thumbs.want(1, "b")
        advanceUntilIdle()

        assertEquals(listOf("1-b.webp", "2-x.webp"), names())
        assertEquals(mapOf(1L to "1-b.webp", 2L to "2-x.webp"), thumbs.names())
    }

    @Test
    fun theOldPictureStaysUntilTheNewOneIsMade() = runTest {
        val thumbs = thumbs()
        thumbs.want(1, "a")
        advanceUntilIdle()
        gates[1] = CompletableDeferred()

        thumbs.want(1, "b")
        runCurrent()

        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())
        gates.getValue(1).complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(1L to "1-b.webp"), thumbs.names())
    }

    @Test
    fun deleteDuringRenderLeavesNoFile() = runTest {
        val thumbs = thumbs()
        gates[1] = CompletableDeferred()
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        runCurrent()
        assertEquals(listOf(1L), calls)

        val deleting = async { thumbs.delete(1) }
        runCurrent()
        gates.getValue(1).complete(Unit)
        advanceUntilIdle()
        deleting.await()

        // The picture drawn after the delete is thrown away; the next trip goes on.
        assertEquals(listOf(2L), thumbs.files.value.keys.toList())
        assertEquals(listOf("2-b.webp"), names())
        assertEquals(listOf(1L, 2L), calls)
        assertEquals(2, recycled.size)
    }

    @Test
    fun deleteRemovesTheFileAndForgetsTheTrip() = runTest {
        val thumbs = thumbs()
        thumbs.want(1, "a")
        advanceUntilIdle()

        thumbs.delete(1)

        assertEquals(emptyList<String>(), names())
        assertEquals(emptyMap<Long, File>(), thumbs.files.value)
    }

    @Test
    fun missingInputsSkipsAndContinues() = runTest {
        val thumbs = thumbs()
        missing += 1
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        thumbs.want(3, "c")
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), calls)
        assertEquals(mapOf(2L to "2-b.webp", 3L to "3-c.webp"), thumbs.names())
    }

    @Test
    fun pausesWhileBusyAndResumes() = runTest {
        val thumbs = thumbs()
        gates[1] = CompletableDeferred()
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        runCurrent()
        assertEquals(listOf(1L), calls)

        // A recording starts while picture 1 is being drawn: it is finished and saved, 2 is not started.
        busy.value = true
        gates.getValue(1).complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)
        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())

        // The recording ends: on with 2.
        busy.value = false
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), calls)
        assertEquals(mapOf(1L to "1-a.webp", 2L to "2-b.webp"), thumbs.names())
    }

    @Test
    fun nothingStartsWhileBusyFromTheStart() = runTest {
        busy.value = true
        val thumbs = thumbs()
        thumbs.want(1, "a")
        advanceUntilIdle()
        assertEquals(emptyList<Long>(), calls)

        busy.value = false
        advanceUntilIdle()
        assertEquals(listOf(1L), calls)
    }

    @Test
    fun pruneRemovesFilesOfGoneTrips() = runTest {
        File(dir, "1-a.webp").writeText("x")
        File(dir, "2-b.webp").writeText("x")
        File(dir, "3-c.webp").writeText("x")
        File(dir, "2-b.webp.tmp").writeText("x")
        val thumbs = thumbs()

        thumbs.prune(setOf(1L, 3L))

        assertEquals(listOf("1-a.webp", "3-c.webp"), names())
        assertEquals(mapOf(1L to "1-a.webp", 3L to "3-c.webp"), thumbs.names())
    }

    @Test
    fun startupTakesTheLatestFilePerTripAndClearsTemporaryFiles() = runTest {
        File(dir, "1-old.webp").apply { writeText("x"); setLastModified(1_000_000) }
        File(dir, "1-new.webp").apply { writeText("x"); setLastModified(2_000_000) }
        File(dir, "2-z.webp.tmp").writeText("x")
        File(dir, "notes.txt").writeText("x")
        val thumbs = thumbs()

        advanceUntilIdle()

        assertEquals(mapOf(1L to "1-new.webp"), thumbs.names())
        assertEquals(listOf("1-new.webp", "notes.txt"), names())
    }

    @Test
    fun aFileOfTheWantedKeyOnDiskIsNotRenderedAgain() = runTest {
        File(dir, "1-a.webp").writeText("x")
        val thumbs = thumbs()

        thumbs.want(1, "a")
        advanceUntilIdle()

        assertEquals(emptyList<Long>(), calls)
        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())
    }

    @Test
    fun anErrorInARenderFailsThatPictureAndTheQueueGoesOn() = runTest {
        val thumbs = thumbs()
        outOfMemory += 1
        thumbs.want(1, "a")
        thumbs.want(2, "b")
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), calls)
        assertEquals(mapOf(2L to "2-b.webp"), thumbs.names())
        assertTrue(warnings.single().second is OutOfMemoryError)

        // Remembered as failed, and the worker is still alive for a later trip.
        thumbs.want(1, "a")
        thumbs.want(3, "c")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), calls)
        assertEquals(mapOf(2L to "2-b.webp", 3L to "3-c.webp"), thumbs.names())
    }

    @Test
    fun aStrayOverLongNumberedFileAtStartupIsIgnoredAndTheQueueStillRenders() = runTest {
        File(dir, "99999999999999999999-x.webp").writeText("x")
        File(dir, "99999999999999999999-x.webp.tmp").writeText("x")
        val thumbs = thumbs()

        thumbs.want(1, "a")
        advanceUntilIdle()

        assertEquals(listOf(1L), calls)
        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())
        assertTrue(File(dir, "99999999999999999999-x.webp").exists())
    }

    /** Ruling I4: the PNG pictures of the first V1.4.0 builds go at the start; their trips are drawn again as WebP. */
    @Test
    fun legacyPngPicturesAreDeletedAtStartAndDrawnAgainAsWebp() = runTest {
        File(dir, "1-a.png").writeText("x")
        File(dir, "2-b.png.tmp").writeText("x")
        File(dir, "3-c.webp").writeText("x")
        File(dir, "99999999999999999999-x.png").writeText("x")
        val thumbs = thumbs()

        thumbs.want(1, "a")
        advanceUntilIdle()

        assertEquals(listOf(1L), calls)
        assertEquals(mapOf(1L to "1-a.webp", 3L to "3-c.webp"), thumbs.names())
        assertEquals(listOf("1-a.webp", "3-c.webp", "99999999999999999999-x.png"), names())
    }

    @Test
    fun pruneAlsoRemovesLegacyPngFiles() = runTest {
        File(dir, "1-a.webp").writeText("x")
        val thumbs = thumbs()
        advanceUntilIdle()
        // PNG files that showed up after the start's sweep: of a kept trip and of a gone one.
        File(dir, "1-a.png").writeText("x")
        File(dir, "2-b.png").writeText("x")
        File(dir, "2-b.png.tmp").writeText("x")

        thumbs.prune(setOf(1L))

        assertEquals(listOf("1-a.webp"), names())
        assertEquals(mapOf(1L to "1-a.webp"), thumbs.names())
    }
}
