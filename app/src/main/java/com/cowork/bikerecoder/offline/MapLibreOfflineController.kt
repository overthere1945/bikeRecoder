package com.cowork.bikerecoder.offline

import android.content.Context
import com.cowork.bikerecoder.WarnLog
import com.cowork.bikerecoder.androidWarnLog
import com.cowork.bikerecoder.core.model.Route
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Corridor download progress. [completed]/[required] count MapLibre resources of the corridor region
 * (before the first status arrives, [required] is the planner's tile estimate). A non-null [error] means the
 * download failed and its partial regions were removed; the UI shows nothing for it (it is logged).
 */
data class OfflineProgress(
    val tripId: Long,
    val completed: Long,
    val required: Long,
    val estimatedBytes: Long,
    val error: String? = null,
) {
    /** 0..100, or 0 while nothing is known. */
    val percent: Int
        get() = if (required <= 0) 0 else (completed * 100 / required).coerceIn(0, 100).toInt()
}

class OfflineException(message: String) : Exception(message)

/**
 * Downloads the map around a trip's route (spec §6.7): a corridor region (z10-14, 2 km either side) and an
 * overview region (z5-9, bounding box), each tagged with [OfflineRegionMeta] and recorded in [refs].
 *
 * [downloadForTrip] only starts the work on [scope] and returns, so navigation never waits for it; calls are
 * serialized by a mutex and a trip with a download already running is not started twice.
 * [deleteForTrip] cancels that trip's running download first. All MapLibre calls happen on the main thread.
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
) : OfflineMapController {

    private val appContext: Context = context.applicationContext
    private val _progress = MutableStateFlow<OfflineProgress?>(null)

    /** Progress of the corridor download currently running (or failed); null when idle or complete. */
    val progress: StateFlow<OfflineProgress?> = _progress.asStateFlow()

    private val mutex = Mutex()
    private val lock = Any()
    private val inFlight = HashMap<Long, Job>()

    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) {
        synchronized(lock) {
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
        mutex.withLock { removeTripRegions(tripId) }
        _progress.compareAndSetIf(tripId) { null }
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
            mutex.withLock {
                when (decide(tripId)) {
                    DownloadDecision.SKIP -> return
                    DownloadDecision.REPLACE -> removeTripRegions(tripId)
                    DownloadDecision.DOWNLOAD -> Unit
                }
                val estimatedBytes = plan.estimatedTiles * ESTIMATED_BYTES_PER_TILE
                _progress.value = OfflineProgress(tripId, 0, plan.estimatedTiles, estimatedBytes)
                if (settings.settings.first().wifiOnlyOfflineMaps) network.awaitUnmetered()

                val pixelRatio = appContext.resources.displayMetrics.density
                val corridor = OfflineGeometryRegionDefinition(
                    tileSource.styleUrl, corridorGeometry(plan), CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM, pixelRatio, false,
                )
                downloadRegion(tripId, RegionKind.CORRIDOR, plan.truncated, corridor) { status ->
                    _progress.value = OfflineProgress(
                        tripId, status.completedResourceCount, status.requiredResourceCount, estimatedBytes,
                    )
                }
                val box = plan.overview
                val overview = OfflineTilePyramidRegionDefinition(
                    tileSource.styleUrl,
                    LatLngBounds.from(box.north, box.east, box.south, box.west),
                    OVERVIEW_MIN_ZOOM, OVERVIEW_MAX_ZOOM, pixelRatio, false,
                )
                downloadRegion(tripId, RegionKind.OVERVIEW, plan.truncated, overview) { }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            log.warn("Offline map download for trip $tripId failed; removing its partial regions", e)
            withContext(NonCancellable) {
                try {
                    mutex.withLock { removeTripRegions(tripId) }
                } catch (cleanup: Exception) {
                    log.warn("Could not remove the partial offline regions of trip $tripId", cleanup)
                }
            }
            _progress.compareAndSetIf(tripId) { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } finally {
            if (!failed) _progress.compareAndSetIf(tripId) { null }
        }
    }

    /** Creates the region, records its ref at once (so a cancel never leaves an unreferenced region), downloads it. */
    private suspend fun downloadRegion(
        tripId: Long,
        kind: RegionKind,
        truncated: Boolean,
        definition: OfflineRegionDefinition,
        onStatus: (OfflineRegionStatus) -> Unit,
    ) {
        val region = withContext(NonCancellable) {
            val created = createRegion(definition, OfflineRegionMeta(tripId, kind, truncated).encode())
            refs.insert(OfflineRegionRefEntity(0, tripId, created.id, kind.name, clock()))
            created
        }
        awaitComplete(region, onStatus)
    }

    private suspend fun awaitComplete(region: OfflineRegion, onStatus: (OfflineRegionStatus) -> Unit) {
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    onStatus(status)
                    if (status.isComplete) done.complete(Unit)
                }

                override fun onError(error: OfflineRegionError) {
                    done.completeExceptionally(OfflineException("${error.reason}: ${error.message}"))
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    done.completeExceptionally(OfflineException("tile count limit exceeded ($limit)"))
                }
            })
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            try {
                done.await()
            } finally {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        }
    }

    // ---- existing regions ----

    private class Found(val region: OfflineRegion, val meta: OfflineRegionMeta?)

    private suspend fun findTripRegions(tripId: Long): List<Found> {
        val refIds = refs.forTrip(tripId).map { it.mapLibreRegionId }.toSet()
        return listRegions().mapNotNull { region ->
            val meta = OfflineRegionMeta.decode(region.metadata)
            if (meta?.tripId == tripId || region.id in refIds) Found(region, meta) else null
        }
    }

    private suspend fun decide(tripId: Long): DownloadDecision {
        val found = findTripRegions(tripId)
        if (found.isEmpty()) return DownloadDecision.DOWNLOAD
        if (found.any { it.meta == null }) return DownloadDecision.REPLACE
        val existing = found.map { ExistingRegion(it.meta!!.kind, it.meta.truncated, isComplete(it.region)) }
        val decision = decideDownload(existing)
        val refIds = refs.forTrip(tripId).map { it.mapLibreRegionId }.toSet()
        return if (decision == DownloadDecision.SKIP && refIds != found.map { it.region.id }.toSet()) {
            DownloadDecision.REPLACE
        } else {
            decision
        }
    }

    /** Deletes the trip's regions (by ref or by metadata) and their refs. Failures are logged and their refs kept. */
    private suspend fun removeTripRegions(tripId: Long) {
        var allDeleted = true
        for (found in findTripRegions(tripId)) {
            try {
                deleteRegion(found.region)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allDeleted = false
                log.warn("Could not delete offline region ${found.region.id} of trip $tripId", e)
            }
        }
        if (allDeleted) refs.deleteForTrip(tripId)
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

    private suspend fun isComplete(region: OfflineRegion): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            region.getStatus(
                object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status?.isComplete == true)
                    override fun onError(error: String?) = cont.resumeWithException(OfflineException(error ?: "status"))
                },
            )
        }
    }

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
        private const val CORRIDOR_MIN_ZOOM = 10.0
        private const val CORRIDOR_MAX_ZOOM = 14.0
        private const val OVERVIEW_MIN_ZOOM = 5.0
        private const val OVERVIEW_MAX_ZOOM = 9.0
        private const val TAG = "MapLibreOffline"
    }
}
