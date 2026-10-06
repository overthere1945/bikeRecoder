package com.cowork.bikerecoder.routing

import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.routing.RouteFailure
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Router] backed by the vendored BRouter engine. The engine is blocking and uses process-wide
 * caches, so calls are serialized and run on [dispatcher].
 */
class BRouterRouter(
    private val segmentDir: File,
    private val profileDir: File,
    private val maxRunningTimeMs: Long = 60_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Router {

    private val mutex = Mutex()

    override suspend fun route(request: RouteRequest): RouteResult = routeRaw(request).first

    /** Like [route], additionally returning BRouter's GeoJSON text (null on failure). */
    internal suspend fun routeRaw(request: RouteRequest): Pair<RouteResult, String?> =
        mutex.withLock {
            withContext(dispatcher) {
                try {
                    compute(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Pair(RouteResult.Failure(RouteFailure.OTHER, e.message ?: e.toString()), null)
                }
            }
        }

    private fun compute(request: RouteRequest): Pair<RouteResult, String?> {
        val waypoints = buildList {
            add(node(request.start, "from"))
            request.stops.forEachIndexed { i, stop ->
                add(node(stop, if (i == request.stops.lastIndex) "to" else "via${i + 1}"))
            }
        }

        val rc = RoutingContext().apply {
            localFunction = File(profileDir, "${request.profile.fileName}.brf").path
            // The profiles declare turnInstructionMode = 1 ("auto"), which BRouter never resolves
            // outside its HTTP parameter collector, leaving voice hints off. Use osmand style (3):
            // JSON command codes 1..16, with EL/ER mapped to 8/9 (KEEP_LEFT/KEEP_RIGHT).
            turnInstructionMode = 3
            request.startBearingDeg?.let {
                startDirection = it.toInt()
                startDirectionValid = true
            }
        }

        val engine = RoutingEngine(null, null, segmentDir, waypoints, rc)
        engine.quite = true
        engine.doRun(maxRunningTimeMs)

        engine.errorMessage?.let { message ->
            val reason = when {
                "datafile" in message -> RouteFailure.NO_SEGMENT_DATA
                "operation killed" in message -> RouteFailure.TIMEOUT
                else -> RouteFailure.NO_ROUTE
            }
            return Pair(RouteResult.Failure(reason, message), null)
        }

        val track = engine.foundTrack
            ?: return Pair(RouteResult.Failure(RouteFailure.NO_ROUTE, "no track found"), null)
        val json = FormatJson(rc).format(track)
        val route = BRouterGeoJsonParser.parse(json, request.profile, request.stops)
        return Pair(RouteResult.Success(route), json)
    }

    private fun node(point: GeoPoint, name: String) = OsmNodeNamed().apply {
        ilat = ((point.lat + 90.0) * 1e6).toInt()
        ilon = ((point.lon + 180.0) * 1e6).toInt()
        this.name = name
    }
}
