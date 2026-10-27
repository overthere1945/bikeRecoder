package com.cowork.bikerecoder.offline

import android.content.Context
import android.os.SystemClock
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.core.model.RouteSummary
import com.cowork.bikerecoder.core.offline.CorridorPlan
import com.cowork.bikerecoder.core.offline.CorridorPlanner
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.data.OfflineRegionRefDao
import com.cowork.bikerecoder.data.OfflineRegionRefEntity
import com.cowork.bikerecoder.data.SettingsRepository
import com.cowork.bikerecoder.map.TileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineException(message: String) : Exception(message)

/**
 * Downloads the map around a trip's route (spec §6.7): a corridor region (z10-14, 2 km either side) and an
 * overview region (z5-9, bounding box), each tagged with [OfflineRegionMeta] and recorded in [refs].
 *
 * [downloadForTrip] only starts the work on [scope] and returns, so navigation never waits for it; calls are
 * serialized by a mutex and a trip with a download already running is not started twice.
 * [deleteForTrip] cancels that trip's running download first. All MapLibre calls happen on the main thread.
 *
 * A VIDEO download ([downloadForTrack]) can take minutes, so it yields (final review I1): every user of the mutex
 * goes through [locked], which counts the coroutines waiting for it, and the VIDEO download stops as soon as one
 * waits — within [STALL_POLL_MS] — keeping what arrived, as it does when it stalls. So trip delete, [정리], the end of
 * a navigation-only ride, a guidance change, [지도 모두 지우기] and a new trip's map never wait behind a video. A
 * yield flag was chosen over taking the region's wait out of the mutex: the region is never deleted under a running
 * observer, and every other path stays exactly as it was.
 */
class MapLibreOfflineController(
    context: Context,
    private val tileSource: TileSource,
    private val refs: OfflineRegionRefDao,
    private val settings: SettingsRepository,
    private val network: NetworkWaiter,
    private val clock: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Planner limit; lowered by tests to get a truncated plan from a short route. */
    private val maxTiles: Long = DEFAULT_MAX_TILES,
    private val log: WarnLog = androidWarnLog(TAG),
) : OfflineMapController, OfflineMapStorage, VideoMaps {

    private val appContext: Context = context.applicationContext
    private val _progress = MutableStateFlow<OfflineProgress?>(null)

    /** Progress of the corridor download currently running (or failed); null when idle or complete. */
    val progress: StateFlow<OfflineProgress?> = _progress.asStateFlow()

    private val mutex = Mutex()

    /** Coroutines waiting in [locked]; a running VIDEO download yields to them. */
    private val waiting = AtomicInteger(0)
    private val lock = Any()
    private val inFlight = HashMap<Long, Job>()

    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) {
        synchronized(lock) {
            // A download already running for this trip is left alone, even if called with another route. That is
            // safe because a route change goes through TripManager.changeStops, which calls deleteForTrip (it
            // cancels the running download) before the new route is downloaded.
            if (inFlight[tripId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) { download(tripId, route, fromDistanceAlongM) }
            inFlight[tripId] = job
            job.invokeOnCompletion { synchronized(lock) { if (inFlight[tripId] === job) inFlight.remove(tripId) } }
            job.start()
        }
    }

    override suspend fun deleteForTrip(tripId: Long) {
        val running = synchronized(lock) { inFlight.remove(tripId) }
        running?.cancelAndJoin()
        locked { removeTripRegions(tripId, strict = false) }
        _progress.compareAndSetIf(tripId) { null }
    }

    /**
     * The map around [points] for a video (spec §3.1①): the trip corridor plan of a route through the points, at
     * zoom 13–16, kind VIDEO, downloaded to the end (or until it stalls, spec §3.4). Unlike [downloadForTrip] this
     * waits, ignores the Wi-Fi-only setting (the user asked for the video now) and never touches the trip's
     * navigation map. A complete VIDEO region of the trip is used again; an unfinished one is replaced; a download
     * that fails is logged (the snapshots fetch tiles themselves while online). Returns false only when there is no
     * internet and the trip has no complete map of its own to draw from. Holds the download mutex meanwhile, but gives
     * it up as soon as another map task waits for it (the class comment): it then returns true with what arrived.
     */
    override suspend fun downloadForTrack(tripId: Long, points: List<GeoPoint>): Boolean {
        require(points.isNotEmpty()) { "no points" }
        return locked {
            val video = findTripRegions(tripId, setOf(RegionKind.VIDEO))
            if (video.isNotEmpty() && video.all { it.meta?.kind == RegionKind.VIDEO && isComplete(it.region) }) {
                return@locked true
            }
            if (!network.isOnline()) {
                return@locked findTripRegions(tripId).any { it.meta != null && isComplete(it.region) }
            }
            try {
                removeTripRegions(tripId, strict = true, kinds = setOf(RegionKind.VIDEO))
                val plan = CorridorPlanner.plan(routeThrough(points), maxTiles = Long.MAX_VALUE)
                val definition = OfflineGeometryRegionDefinition(
                    tileSource.styleUrl, corridorGeometry(plan), VIDEO_MIN_ZOOM, VIDEO_MAX_ZOOM, VIDEO_PIXEL_RATIO, false,
                )
                downloadRegion(
                    tripId, RegionKind.VIDEO, truncated = false, definition, stallMs = VIDEO_STALL_MS,
                    yieldWhen = { waiting.get() > 0 },
                ) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Online anyway: the snapshots fetch what they need themselves.
                log.warn("Video map download for trip $tripId failed; drawing from the network", e)
            }
            true
        }
    }

    /** Deletes the trip's VIDEO regions and their refs only; the navigation map stays. */
    override suspend fun deleteVideoRegions(tripId: Long) {
        locked { removeTripRegions(tripId, strict = false, kinds = setOf(RegionKind.VIDEO)) }
    }

    /** A [Route] through [points] (one point is doubled: a line needs two) for [CorridorPlanner]. */
    private fun routeThrough(points: List<GeoPoint>): Route {
        val line = if (points.size == 1) points + points else points
        val cumulative = DoubleArray(line.size)
        for (i in 1 until line.size) cumulative[i] = cumulative[i - 1] + GeoMath.distanceM(line[i - 1], line[i])
        return Route(
            points = line,
            cumulativeM = cumulative.toList(),
            instructions = emptyList(),
            stopPointIndices = listOf(line.lastIndex),
            summary = RouteSummary(cumulative.last(), 0, 0.0, RouteProfile.BALANCED),
        )
    }

    /**
     * Deliberately not under [mutex] (a running download holds it for its whole duration and the settings screen
     * must not wait for that). A region deleted while we ask for its size counts as 0 instead of failing the sum.
     */
    override suspend fun totalBytes(): Long = ownRegions().sumOf { region ->
        try {
            status(region)?.completedResourceSize ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun deleteAll() {
        val running = synchronized(lock) { inFlight.values.toList().also { inFlight.clear() } }
        running.forEach { it.cancelAndJoin() }
        locked {
            val deleted = mutableListOf<Long>()
            var failures = 0
            for (region in ownRegions()) {
                try {
                    deleteRegion(region)
                    deleted += region.id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    log.warn("Could not delete offline region ${region.id}", e)
                }
            }
            if (failures == 0) {
                refs.deleteAll()
            } else {
                deleted.forEach { refs.deleteByRegionId(it) }
            }
            _progress.value = null
            if (failures > 0) throw OfflineException("could not delete $failures offline region(s)")
        }
    }

    /** Suspends until every download started so far has finished (successfully or not). */
    suspend fun awaitDownloads() {
        val jobs = synchronized(lock) { inFlight.values.toList() }
        jobs.forEach { it.join() }
    }

    /** Sets the general (ambient) tile cache size; call once at startup. */
    suspend fun configureAmbientCache(bytes: Long = AMBIENT_CACHE_BYTES) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                manager().setMaximumAmbientCacheSize(
                    bytes,
                    object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() = cont.resume(Unit)
                        override fun onError(message: String) = cont.resumeWithException(OfflineException(message))
                    },
                )
            }
        }
    }

    // ---- download ----

    private suspend fun download(tripId: Long, route: Route, fromDistanceAlongM: Double) {
        var failed = false
        try {
            val plan = CorridorPlanner.plan(route, fromDistanceAlongM, maxTiles = maxTiles)

            // Wi-Fi only: wait for the network BEFORE taking the mutex or deleting anything, so a long wait
            // blocks neither other trips' downloads nor deleteForTrip, and an existing map stays until a
            // replacement can really be downloaded. Nothing is shown while waiting.
            if (settings.settings.first().wifiOnlyOfflineMaps) {
                if (locked { decide(tripId) } == DownloadDecision.SKIP) return
                network.awaitUnmetered()
            }

            locked {
                when (decide(tripId)) {
                    DownloadDecision.SKIP -> return
                    DownloadDecision.REPLACE -> removeTripRegions(tripId, strict = true, kinds = RegionKind.TRIP_MAP)
                    DownloadDecision.DOWNLOAD -> Unit
                }
                val estimatedBytes = plan.estimatedTiles * ESTIMATED_BYTES_PER_TILE
                _progress.value = OfflineProgress(tripId, 0, plan.estimatedTiles, estimatedBytes)

                val pixelRatio = appContext.resources.displayMetrics.density
                val corridor = OfflineGeometryRegionDefinition(
                    tileSource.styleUrl, corridorGeometry(plan), CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM, pixelRatio, false,
                )
                downloadRegion(tripId, RegionKind.CORRIDOR, plan.truncated, corridor, stallMs = null) { status ->
                    _progress.value = OfflineProgress(
                        tripId, status.completedResourceCount, status.requiredResourceCount, estimatedBytes,
                        started = true,
                    )
                }
                val box = plan.overview
                val overview = OfflineTilePyramidRegionDefinition(
                    tileSource.styleUrl,
                    LatLngBounds.from(box.north, box.east, box.south, box.west),
                    OVERVIEW_MIN_ZOOM, OVERVIEW_MAX_ZOOM, pixelRatio, false,
                )
                downloadRegion(tripId, RegionKind.OVERVIEW, plan.truncated, overview, stallMs = null) { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            log.warn("Offline map download for trip $tripId failed; removing its partial regions", e)
            withContext(NonCancellable) {
                try {
                    locked { removeTripRegions(tripId, strict = false, kinds = RegionKind.TRIP_MAP) }
                } catch (cleanup: Exception) {
                    log.warn("Could not remove the partial offline regions of trip $tripId", cleanup)
                }
            }
            _progress.compareAndSetIf(tripId) { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } finally {
            if (!failed) _progress.compareAndSetIf(tripId) { null }
        }
    }

    /**
     * [mutex] for [block]. While this waits, [waiting] counts it, so a running VIDEO download stops and lets go
     * (the class comment). Inline like `withLock`, so [block] may return from the caller.
     */
    private suspend inline fun <T> locked(block: () -> T): T {
        waiting.incrementAndGet()
        try {
            mutex.lock()
        } finally {
            waiting.decrementAndGet()
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /** Creates the region, records its ref at once (so a cancel never leaves an unreferenced region), downloads it. */
    private suspend fun downloadRegion(
        tripId: Long,
        kind: RegionKind,
        truncated: Boolean,
        definition: OfflineRegionDefinition,
        stallMs: Long?,
        yieldWhen: (() -> Boolean)? = null,
        onStatus: (OfflineRegionStatus) -> Unit,
    ) {
        val region = withContext(NonCancellable) {
            val created = createRegion(definition, OfflineRegionMeta(tripId, kind, truncated).encode())
            refs.insert(OfflineRegionRefEntity(0, tripId, created.id, kind.name, clock()))
            created
        }
        awaitComplete(region, stallMs, yieldWhen, onStatus)
    }

    /**
     * Downloads [region] to the end. With [stallMs], gives up quietly (keeping what arrived) once no resource has
     * completed for that long: MapLibre retries a failing resource forever; and, checked as often, once [yieldWhen]
     * says another map task waits for the mutex.
     */
    private suspend fun awaitComplete(
        region: OfflineRegion,
        stallMs: Long? = null,
        yieldWhen: (() -> Boolean)? = null,
        onStatus: (OfflineRegionStatus) -> Unit,
    ) {
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            var lastCount = -1L
            var lastChangeAt = SystemClock.elapsedRealtime()
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    onStatus(status)
                    if (status.completedResourceCount != lastCount) {
                        lastCount = status.completedResourceCount
                        lastChangeAt = SystemClock.elapsedRealtime()
                    }
                    // An early 0/0 status can already say "complete"; only a precise count is final.
                    if (status.isComplete && status.isRequiredResourceCountPrecise) done.complete(Unit)
                }

                /** Per resource (404 for empty tiles, network errors MapLibre retries): log and keep going. */
                override fun onError(error: OfflineRegionError) {
                    log.warn("Offline region ${region.id}: ${error.reason}: ${error.message}", null)
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    done.completeExceptionally(OfflineException("tile count limit exceeded ($limit)"))
                }
            })
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            try {
                if (stallMs == null) {
                    done.await()
                } else {
                    while (withTimeoutOrNull(STALL_POLL_MS) { done.await() } == null) {
                        if (SystemClock.elapsedRealtime() - lastChangeAt > stallMs) {
                            log.warn("Offline region ${region.id} stalled at $lastCount resources; using what arrived", null)
                            break
                        }
                        if (yieldWhen?.invoke() == true) {
                            log.warn("Video map download yielded to another map task at $lastCount resources; using what arrived", null)
                            break
                        }
                    }
                }
            } finally {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        }
    }

    // ---- existing regions ----

    private class Found(val region: OfflineRegion, val meta: OfflineRegionMeta?)

    /**
     * The trip's regions (by metadata or by ref) of [kinds]. A region whose kind is unknown (no metadata and a ref
     * with an unknown kind) counts as every kind, so it is always replaced or deleted.
     */
    private suspend fun findTripRegions(tripId: Long, kinds: Set<RegionKind> = ALL_KINDS): List<Found> {
        val refKinds = refs.forTrip(tripId).associate { it.mapLibreRegionId to it.kind }
        return listRegions().mapNotNull { region ->
            val meta = OfflineRegionMeta.decode(region.metadata)
            if (meta?.tripId != tripId && region.id !in refKinds) return@mapNotNull null
            val kind = meta?.kind ?: refKinds[region.id]?.let(::kindOf)
            if (kind != null && kind !in kinds) null else Found(region, meta)
        }
    }

    private fun kindOf(name: String): RegionKind? = RegionKind.entries.find { it.name == name }

    /** Every region this app created: identified by its metadata, or by a stored ref. */
    private suspend fun ownRegions(): List<OfflineRegion> {
        val refIds = refs.all().map { it.mapLibreRegionId }.toSet()
        return listRegions().filter { OfflineRegionMeta.decode(it.metadata) != null || it.id in refIds }
    }

    private suspend fun decide(tripId: Long): DownloadDecision {
        val found = findTripRegions(tripId, RegionKind.TRIP_MAP)
        if (found.isEmpty()) return DownloadDecision.DOWNLOAD
        if (found.any { it.meta == null }) return DownloadDecision.REPLACE
        val existing = found.map { ExistingRegion(it.meta!!.kind, it.meta.truncated, isComplete(it.region)) }
        val decision = decideDownload(existing)
        val refIds = refs.forTrip(tripId).filter { kindOf(it.kind) in RegionKind.TRIP_MAP }.map { it.mapLibreRegionId }.toSet()
        return if (decision == DownloadDecision.SKIP && refIds != found.map { it.region.id }.toSet()) {
            DownloadDecision.REPLACE
        } else {
            decision
        }
    }

    /**
     * Deletes the trip's regions of [kinds] (by ref or by metadata) and their refs. A region that cannot be deleted
     * is logged and its refs are kept; with [strict] that also throws, so a download never creates new regions
     * next to stale ones.
     */
    private suspend fun removeTripRegions(tripId: Long, strict: Boolean, kinds: Set<RegionKind> = ALL_KINDS) {
        var allDeleted = true
        val tripRegions = findTripRegions(tripId, kinds)
        for (found in tripRegions) {
            try {
                deleteRegion(found.region)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allDeleted = false
                log.warn("Could not delete offline region ${found.region.id} of trip $tripId", e)
            }
        }
        if (allDeleted) {
            if (kinds == ALL_KINDS) {
                refs.deleteForTrip(tripId)
            } else {
                val gone = tripRegions.map { it.region.id }.toSet()
                refs.forTrip(tripId).filter { it.mapLibreRegionId in gone || kindOf(it.kind) in kinds }
                    .forEach { refs.deleteByRegionId(it.mapLibreRegionId) }
            }
        } else if (strict) {
            throw OfflineException("could not delete the previous offline regions of trip $tripId")
        }
    }

    // ---- MapLibre callbacks as suspend functions (main thread) ----

    private fun manager(): OfflineManager {
        MapLibre.getInstance(appContext)
        return OfflineManager.getInstance(appContext)
    }

    private suspend fun listRegions(): List<OfflineRegion> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            manager().listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) =
                        cont.resume(offlineRegions?.toList().orEmpty())

                    override fun onError(error: String) = cont.resumeWithException(OfflineException(error))
                },
            )
        }
    }

    private suspend fun createRegion(definition: OfflineRegionDefinition, metadata: ByteArray): OfflineRegion =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                manager().createOfflineRegion(
                    definition,
                    metadata,
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)
                        override fun onError(error: String) = cont.resumeWithException(OfflineException(error))
                    },
                )
            }
        }

    private suspend fun deleteRegion(region: OfflineRegion) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            region.delete(
                object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) = cont.resumeWithException(OfflineException(error))
                },
            )
        }
    }

    private suspend fun status(region: OfflineRegion): OfflineRegionStatus? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.getStatus(
                object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)

                    override fun onError(error: String?) = cont.resumeWithException(OfflineException(error ?: "status"))
                },
            )
        }
    }

    private suspend fun isComplete(region: OfflineRegion): Boolean =
        status(region)?.let { it.isComplete && it.isRequiredResourceCountPrecise } == true

    private fun corridorGeometry(plan: CorridorPlan): MultiPolygon =
        MultiPolygon.fromLngLats(
            plan.polygon.map { ring -> listOf(ring.map { Point.fromLngLat(it.lon, it.lat) }) },
        )

    /** Applies [transform] only while the shown progress belongs to [tripId]. */
    private inline fun MutableStateFlow<OfflineProgress?>.compareAndSetIf(
        tripId: Long,
        transform: (OfflineProgress) -> OfflineProgress?,
    ) {
        while (true) {
            val current = value ?: return
            if (current.tripId != tripId) return
            if (compareAndSet(current, transform(current))) return
        }
    }

    companion object {
        const val DEFAULT_MAX_TILES = 15_000L
        const val AMBIENT_CACHE_BYTES = 100L * 1024 * 1024
        const val ESTIMATED_BYTES_PER_TILE = 15_000L
        private val ALL_KINDS: Set<RegionKind> = RegionKind.entries.toSet()
        /** How often a waiting download checks for a stall and (VIDEO) for a map task waiting for the mutex. */
        private const val STALL_POLL_MS = 1_000L

        /** A video map download that has made no progress for this long is used as it is (spec §3.4). */
        const val VIDEO_STALL_MS = 30_000L

        /** Pixel ratio of the VIDEO regions: the same as the video's map snapshots (sprites are per ratio). */
        const val VIDEO_PIXEL_RATIO = 1f
        private const val VIDEO_MIN_ZOOM = 13.0
        private const val VIDEO_MAX_ZOOM = 16.0
        private const val CORRIDOR_MIN_ZOOM = 10.0
        private const val CORRIDOR_MAX_ZOOM = 14.0
        private const val OVERVIEW_MIN_ZOOM = 5.0
        private const val OVERVIEW_MAX_ZOOM = 9.0
        private const val TAG = "MapLibreOffline"
    }
}
