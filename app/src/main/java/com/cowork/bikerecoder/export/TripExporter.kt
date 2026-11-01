package com.cowork.bikerecoder.export

import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.core.export.ExportLabels
import com.cowork.bikerecoder.core.export.ExportSegment
import com.cowork.bikerecoder.core.export.GpxWriter
import com.cowork.bikerecoder.core.export.KmlWriter
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.post.PostStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two export formats (spec §4.2): file extension and MIME type. */
enum class ExportFormat(val extension: String, val mime: String) {
    GPX("gpx", "application/gpx+xml"),
    KML("kml", "application/vnd.google-earth.kml+xml"),
}

private val FILE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.ROOT).withZone(ZoneId.of("Asia/Seoul"))

/** "{prefix}_yyyyMMdd_HHmm.{gpx|kml}" with [epochMillis] in Asia/Seoul (spec §4.2). */
fun exportFileName(prefix: String, epochMillis: Long, format: ExportFormat): String =
    "${prefix}_${FILE_TIME.format(Instant.ofEpochMilli(epochMillis))}.${format.extension}"

/** What an export came to. */
sealed interface ExportResult {
    /**
     * The file is saved: its content [uri] (as a string, like the app's other MediaStore URIs), the name the phone gave
     * it (it may have added " (1)"), and its MIME type for [공유] and [파일 열기].
     */
    data class Saved(val uri: String, val displayName: String, val mime: String) : ExportResult

    /** Nothing was saved (the cause is logged). */
    data object Failed : ExportResult

    /** The trip has an open segment: it can be exported once the recording ends. */
    data object Recording : ExportResult
}

/** Where the exported text goes: [DownloadsWriter] in the app, a fake in tests. Blocking; called on the io dispatcher. */
fun interface ExportSink {
    fun save(displayName: String, mime: String, content: String): ExportResult.Saved
}

/**
 * GPX/KML export of a trip or a planned route (V1.7.0 spec §4). Reads the trip's segments, points and heart-rate
 * samples, builds the text with core [GpxWriter] / [KmlWriter] and hands it to [writer] — all on [io]. It only reads the
 * app's records, never changes them. A failure is logged to [log] and comes back as [ExportResult.Failed].
 *
 * @param clock the export time (GPX `metadata/time`, the route's file name).
 * @param creator the GPX `creator` attribute, "bikeRecoder {version}" ([creator]).
 */
class TripExporter(
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val writer: ExportSink,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val creator: String,
    private val log: WarnLog,
) {

    /**
     * The trip [tripId] as one file titled [title] (the title the trip screen shows): one track segment per listed
     * segment (empty ones are left out, as on the screen) in start order, named after the first one's start. Each
     * segment carries its activity: GPX starts a new track where it changes, KML names the line after it. A trip with
     * an open segment is [ExportResult.Recording]; one without any ride is [ExportResult.Failed].
     */
    suspend fun exportTrip(tripId: Long, format: ExportFormat, title: String): ExportResult = guarded("trip $tripId") {
        val all = rideStore.segments(tripId)
        if (all.any { it.endedAt == null }) return@guarded ExportResult.Recording
        val segments = all.filterNot { it.isEmpty }.sortedWith(compareBy({ it.startedAt }, { it.id }))
        check(segments.isNotEmpty()) { "trip $tripId has no ride to export" }
        val parts = segments.map { segment ->
            ExportSegment(
                startedAt = segment.startedAt,
                localDate = segment.localDate,
                points = rideStore.points(segment.id),
                // KML carries no heart rate.
                heartRate = if (format == ExportFormat.GPX) heartRate(segment.id) else emptyList(),
                activity = segment.activity,
            )
        }
        val content = when (format) {
            ExportFormat.GPX -> GpxWriter.trip(title, parts, creator, clock())
            ExportFormat.KML -> KmlWriter.trip(title, parts)
        }
        writer.save(exportFileName(TRIP_PREFIX, segments.first().startedAt, format), format.mime, content)
    }

    /**
     * The planned [route] with its [stops] (waypoints, then the destination) as one file named
     * "bikeRecoder 경로 · {[destinationName]}" ([ExportLabels.routeName]), the file named after the export time.
     */
    suspend fun exportRoute(route: Route, stops: List<Stop>, destinationName: String, format: ExportFormat): ExportResult =
        guarded("route to $destinationName") {
            val name = ExportLabels.routeName(destinationName)
            val now = clock()
            val content = when (format) {
                ExportFormat.GPX -> GpxWriter.route(name, route, stops, creator, now)
                ExportFormat.KML -> KmlWriter.route(name, route, stops)
            }
            writer.save(exportFileName(ROUTE_PREFIX, now, format), format.mime, content)
        }

    /** A segment's heart rate; none (logged) when it cannot be read — the track is still worth saving. */
    private suspend fun heartRate(segmentId: Long): List<HeartRateSample> = try {
        postStore.samples(segmentId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read the heart rate of segment $segmentId; exported without it", e)
        emptyList()
    }

    private suspend fun guarded(what: String, block: suspend () -> ExportResult): ExportResult = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not export the $what", e)
        ExportResult.Failed
    }

    companion object {
        const val TRIP_PREFIX = "bikeRecoder"
        const val ROUTE_PREFIX = "bikeRecoder_route"

        /** The GPX `creator` (spec §4.3). */
        fun creator(versionName: String): String = "bikeRecoder $versionName"
    }
}
