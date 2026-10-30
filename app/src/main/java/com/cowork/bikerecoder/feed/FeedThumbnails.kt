package com.cowork.bikerecoder.feed

import android.graphics.Bitmap
import com.cowork.bikerecoder.WarnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** How a finished picture is written and let go (a seam: the JVM tests have no Bitmap to compress). */
interface BitmapPng {
    fun write(bitmap: Bitmap, out: OutputStream)

    fun recycle(bitmap: Bitmap)
}

internal object AndroidBitmapPng : BitmapPng {
    override fun write(bitmap: Bitmap, out: OutputStream) {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG not written" }
    }

    override fun recycle(bitmap: Bitmap) = bitmap.recycle()
}

/**
 * The satellite pictures of the feed's trip cards (V1.4.0 spec §2.4, §4), made one at a time in the background and kept
 * as `<tripId>-<key>.png` in [dir] (the key says what the picture was drawn from, [com.cowork.bikerecoder.core.feed.FeedThumbKey]).
 *
 * - A card on screen [want]s its trip's picture and [unwant]s it when it leaves; the wanted ones are made in the order they
 *   were first wanted, by a single worker in [scope], through [render] (null = it cannot be made now: no satellite map, no route).
 * - While [busy] (a recording, guidance or a video is running) no new picture is started; one already under way is finished and
 *   saved.
 * - A picture that failed (null, an exception) is not tried again in this process, until [online] turns from false to true.
 * - A picture is written to `….png.tmp` and renamed, then the trip's other files are deleted. [files] holds the newest picture of
 *   each trip (read from [dir] at the start, so earlier runs' pictures show at once). [delete] and [prune] leave no file behind
 *   even when a picture of that trip is being made at that moment.
 */
class FeedThumbnails internal constructor(
    private val dir: File,
    private val render: suspend (tripId: Long) -> Bitmap?,
    busy: Flow<Boolean>,
    online: Flow<Boolean>,
    scope: CoroutineScope,
    private val log: WarnLog,
    private val io: CoroutineDispatcher,
    private val png: BitmapPng,
) {
    constructor(
        dir: File,
        render: suspend (tripId: Long) -> Bitmap?,
        busy: Flow<Boolean>,
        online: Flow<Boolean>,
        scope: CoroutineScope,
        log: WarnLog,
    ) : this(dir, render, busy, online, scope, log, Dispatchers.IO, AndroidBitmapPng)

    private val lock = Any()

    /** Trips on screen, in the order they were first wanted (a new key for a trip keeps its place). Under [lock]. */
    private val wanted = LinkedHashMap<Long, String>()

    /** (trip, key) pairs that failed in this process. Under [lock]. */
    private val failed = HashSet<Pair<Long, String>>()

    /** Bumped by every [delete]/[prune] of a trip: a picture whose drawing started under an older number is thrown away. Under [lock]. */
    private val generation = HashMap<Long, Int>()

    private val _files = MutableStateFlow<Map<Long, File>>(emptyMap())
    val files: StateFlow<Map<Long, File>> = _files.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The start's read of [dir] is done. */
    private val started = CompletableDeferred<Unit>()

    /** Not started until [busy] has said it is free. */
    @Volatile
    private var paused = true

    init {
        scope.launch {
            busy.collect { now ->
                paused = now
                if (!now) wake.trySend(Unit)
            }
        }
        scope.launch {
            var before: Boolean? = null
            online.collect { now ->
                if (before == false && now) {
                    synchronized(lock) { failed.clear() }
                    wake.trySend(Unit)
                }
                before = now
            }
        }
        scope.launch {
            try {
                readDirectory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Feed pictures directory cannot be read", e)
            } finally {
                started.complete(Unit)
            }
            for (ignored in wake) {
                // Whatever goes wrong in a pass (an Error such as OutOfMemoryError included), the worker lives on.
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn("Feed picture queue pass failed", e)
                }
            }
        }
    }

    /** The card of [tripId] is on screen and wants the picture [key]. */
    fun want(tripId: Long, key: String) {
        synchronized(lock) { wanted[tripId] = key }
        wake.trySend(Unit)
    }

    /** The card left the screen: a picture not yet started is not made. */
    fun unwant(tripId: Long) {
        synchronized(lock) { wanted.remove(tripId) }
    }

    /** The trip is gone: its files are deleted, also the one being drawn right now when it is saved. */
    suspend fun delete(tripId: Long) {
        started.await()
        forget(tripId)
        withContext(io) { deleteFilesOf(tripId) }
    }

    /** Deletes the files of every trip not in [keep] (the startup sweep after trips were deleted, spec §4). */
    suspend fun prune(keep: Set<Long>) {
        started.await()
        val onDisk = withContext(io) { dir.listFiles().orEmpty().mapNotNull { tripOf(it.name) } }
        val gone = (files.value.keys + onDisk).toSet() - keep
        for (id in gone) {
            forget(id)
            withContext(io) { deleteFilesOf(id) }
        }
    }

    private fun forget(tripId: Long) {
        synchronized(lock) {
            wanted.remove(tripId)
            failed.removeAll { it.first == tripId }
            generation[tripId] = (generation[tripId] ?: 0) + 1
            _files.value = _files.value - tripId
        }
    }

    private fun deleteFilesOf(tripId: Long) {
        dir.listFiles().orEmpty().filter { tripOf(it.name) == tripId }.forEach { it.delete() }
    }

    /** Stray `.tmp` files go; the newest picture of each trip is shown and its older ones go. */
    private suspend fun readDirectory() = withContext(io) {
        val all = dir.listFiles().orEmpty()
        all.filter { it.name.endsWith(TMP) }.forEach { it.delete() }
        // A name that looks like a picture but whose number is no trip id (too long for a Long) is a stranger: left alone.
        val pictures = all.filter { PICTURE.matches(it.name) }.mapNotNull { file -> tripOf(file.name)?.let { it to file } }
            .groupBy({ it.first }, { it.second })
        val newest = pictures.mapValues { (_, list) -> list.maxWith(compareBy<File> { it.lastModified() }.thenBy { it.name }) }
        pictures.values.flatten().filter { file -> newest.values.none { it == file } }.forEach { it.delete() }
        synchronized(lock) { _files.value = _files.value + newest }
    }

    /** Makes the wanted pictures one after the other until none is left or [paused]. */
    private suspend fun work() {
        while (!paused) {
            val job = synchronized(lock) { next() } ?: return
            make(job.tripId, job.key, job.number)
        }
    }

    private class Job(val tripId: Long, val key: String, val number: Int)

    /** The first wanted trip whose wanted picture is neither on disk nor failed. Under [lock]. */
    private fun next(): Job? {
        for ((id, key) in wanted) {
            if (_files.value[id]?.name == nameOf(id, key)) continue
            if (id to key in failed) continue
            return Job(id, key, generation[id] ?: 0)
        }
        return null
    }

    private suspend fun make(tripId: Long, key: String, number: Int) {
        val bitmap = try {
            render(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Feed picture of trip $tripId cannot be drawn", e)
            fail(tripId, key)
            return
        }
        if (bitmap == null) {
            log.warn("Feed picture of trip $tripId not drawn (no satellite map or no route)", null)
            fail(tripId, key)
            return
        }
        val tmp = File(dir, nameOf(tripId, key) + TMP)
        var saved: File? = null
        try {
            saved = withContext(io) {
                dir.mkdirs()
                tmp.outputStream().use { png.write(bitmap, it) }
                val target = File(dir, nameOf(tripId, key))
                if (!tmp.renameTo(target)) throw IOException("cannot rename ${tmp.name}")
                target
            }
            val current = synchronized(lock) {
                (generation[tripId] ?: 0) == number && run { _files.value = _files.value + (tripId to saved); true }
            }
            if (current) {
                withContext(io) {
                    dir.listFiles().orEmpty().filter { tripOf(it.name) == tripId && it != saved }.forEach { it.delete() }
                }
            }
            if (!current) throw Stale()
        } catch (e: CancellationException) {
            withContext(NonCancellable + io) { tmp.delete() }
            throw e
        } catch (e: Stale) {
            // The trip was deleted while its picture was drawn: the picture goes with it.
            withContext(NonCancellable + io) {
                saved?.delete()
                tmp.delete()
            }
        } catch (e: Throwable) {
            val deleted = synchronized(lock) { (generation[tripId] ?: 0) != number }
            if (!deleted) {
                log.warn("Feed picture of trip $tripId cannot be saved", e)
                fail(tripId, key)
            }
            withContext(NonCancellable + io) { tmp.delete() }
        } finally {
            png.recycle(bitmap)
        }
    }

    private class Stale : Exception()

    private fun fail(tripId: Long, key: String) {
        synchronized(lock) { failed += tripId to key }
    }

    private companion object {
        const val TMP = ".tmp"
        val PICTURE = Regex("""^(\d+)-.+\.png$""")
        val ANY = Regex("""^(\d+)-.+\.png(\.tmp)?$""")

        fun nameOf(tripId: Long, key: String) = "$tripId-$key.png"

        /** The trip a file in the directory belongs to; null for a stranger. */
        fun tripOf(name: String): Long? = ANY.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    }
}
