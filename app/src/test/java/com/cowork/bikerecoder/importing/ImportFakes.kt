package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.importing.ImportStore
import com.cowork.bikerecoder.core.importing.ImportedRide
import com.cowork.bikerecoder.core.importing.TimeSpan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.Instant
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos

/** 2023-05-14 09:12 KST. */
const val T0 = 1_684_023_120_000L

/**
 * A ride as GPX: [points] fixes every 2 s from [startMillis], [stepM] metres east each (5 m/s by default), with an
 * optional `<type>`.
 */
fun gpx(startMillis: Long, points: Int = 30, stepM: Double = 10.0, type: String? = null): ByteArray {
    val lat = 37.5
    val lonStep = stepM / (111_320.0 * cos(Math.toRadians(lat)))
    val pts = (0 until points).joinToString("\n") { i ->
        val time = Instant.ofEpochMilli(startMillis + i * 2_000L)
        """<trkpt lat="$lat" lon="${127.0 + i * lonStep}"><ele>20</ele><time>$time</time></trkpt>"""
    }
    val typeTag = type?.let { "<type>$it</type>" } ?: ""
    return """<?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
        <trk>$typeTag<trkseg>
        $pts
        </trkseg></trk></gpx>""".trimIndent().toByteArray()
}

/** The end of a [gpx] ride with [points] points. */
fun gpxEnd(startMillis: Long, points: Int = 30): Long = startMillis + (points - 1) * 2_000L

fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((name, bytes) in entries) {
            z.putNextEntry(ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

/** Files by URI ("content://x/<name>"); a missing one cannot be opened. */
class FakeImportReader : ImportReader {
    val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val held: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val released: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    /** Adds a file and returns its URI. */
    fun add(name: String, bytes: ByteArray): String = "content://x/$name".also { files[it] = bytes }

    override fun displayName(uri: String): String = uri.substringAfterLast('/')

    /** Wraps every opened stream (to pace or pause the reading). */
    @Volatile var wrap: (InputStream) -> InputStream = { it }

    override fun open(uri: String): InputStream {
        opened += uri
        return wrap(ByteArrayInputStream(files[uri] ?: throw FileNotFoundException(uri)))
    }

    override fun hold(uris: List<String>) {
        held += uris
    }

    override fun release(uris: List<String>) {
        released += uris
    }
}

/** Segments as spans; saving adds the ride's span. [onSave] runs before each save and may throw. */
class FakeImportStore : ImportStore {
    val spans: MutableList<TimeSpan> = Collections.synchronizedList(mutableListOf())
    val saved: MutableList<ImportedRide> = Collections.synchronizedList(mutableListOf())
    var onSave: suspend (ImportedRide) -> Unit = {}

    override suspend fun saveImported(ride: ImportedRide): Long {
        onSave(ride)
        spans += TimeSpan(ride.ride.startedAt, ride.ride.endedAt)
        saved += ride
        return saved.size.toLong()
    }

    override suspend fun segmentSpans(now: Long): List<TimeSpan> = synchronized(spans) { spans.toList() }

    override fun observeImported(): Flow<Map<Long, String>> = flowOf(emptyMap())

    override suspend fun deleteAllImported(): Int = 0
}
