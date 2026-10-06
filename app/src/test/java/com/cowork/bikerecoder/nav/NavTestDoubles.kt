package com.cowork.bikerecoder.nav

import com.cowork.bikerecoder.core.gpx.GpxParser
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.routing.RouteRequest
import com.cowork.bikerecoder.core.routing.RouteResult
import com.cowork.bikerecoder.core.routing.Router
import com.cowork.bikerecoder.core.trip.OfflineMapController
import com.cowork.bikerecoder.core.trip.Trip
import com.cowork.bikerecoder.core.trip.TripStatus
import com.cowork.bikerecoder.core.trip.TripStop
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.location.LocationSource
import com.cowork.bikerecoder.routing.BRouterGeoJsonParser
import com.cowork.bikerecoder.tts.VoiceOutput
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Reads a scenario fixture (shared with androidTest via the test resources dir). */
fun fixture(name: String): String =
    requireNotNull(NavTestDoubles::class.java.getResourceAsStream("/$name")) { "missing fixture $name" }
        .use { it.readBytes().decodeToString() }

fun gpxFixture(name: String): List<LocationFix> = GpxParser.parse(fixture(name))

object NavTestDoubles

/** Returns the parsed GeoJSON assets in call order (the last one repeats); records every request. */
class AssetRouter(private val jsons: List<String>) : Router {
    val requests = mutableListOf<RouteRequest>()
    var failure: RouteResult.Failure? = null

    override suspend fun route(request: RouteRequest): RouteResult {
        requests += request
        failure?.let { return it }
        val json = jsons[minOf(requests.size - 1, jsons.lastIndex)]
        return RouteResult.Success(BRouterGeoJsonParser.parse(json, request.profile, request.stops))
    }
}

class RecordingVoiceOutput : VoiceOutput {
    val spoken = mutableListOf<String>()
    override val available: StateFlow<Boolean> = MutableStateFlow(true)
    override var muted: Boolean = false

    override fun speak(text: String) {
        if (!muted) spoken += text
    }

    override fun shutdown() = Unit
}

/**
 * Replays [fixes] like GpxLocationSource (gap / [speedup]) and then, unlike it, stays open until cancelled
 * (a real GPS never "ends"). [collecting] tells whether someone is collecting right now.
 */
class ScriptedLocationSource(private val fixes: List<LocationFix>, private val speedup: Double = 20.0) : LocationSource {
    var collecting = false
        private set
    var collections = 0
        private set

    override fun fixes(): Flow<LocationFix> = flow {
        collecting = true
        collections++
        try {
            var previous: LocationFix? = null
            for (fix in fixes) {
                previous?.let { delay(((fix.timeMillis - it.timeMillis) / speedup).toLong()) }
                emit(fix)
                previous = fix
            }
            awaitCancellation()
        } finally {
            collecting = false
        }
    }
}

class FakeTripStore : TripStore {
    val trips = mutableMapOf<Long, Trip>()
    val stopsByTrip = mutableMapOf<Long, MutableList<TripStop>>()

    override suspend fun activeTrip(): Trip? =
        trips.values.filter { it.status == TripStatus.ACTIVE }.maxByOrNull { it.lastActiveAt }

    override suspend fun trip(id: Long): Trip? = trips[id]

    override suspend fun insertTrip(trip: Trip): Long {
        val id = (trips.keys.maxOrNull() ?: 0L) + 1
        trips[id] = trip.copy(id = id)
        return id
    }

    override suspend fun updateTrip(trip: Trip) {
        trips[trip.id] = trip
    }

    override suspend fun stops(tripId: Long): List<TripStop> = stopsByTrip[tripId].orEmpty().sortedBy { it.order }

    override suspend fun replaceStops(tripId: Long, stops: List<TripStop>) {
        var nextId = (stopsByTrip.values.flatten().maxOfOrNull { it.id } ?: 0L) + 1
        stopsByTrip[tripId] = stops.map { it.copy(id = nextId++, tripId = tripId) }.toMutableList()
    }

    override suspend fun markVisited(stopId: Long, at: Long) {
        for (list in stopsByTrip.values) {
            val i = list.indexOfFirst { it.id == stopId }
            if (i >= 0) list[i] = list[i].copy(visitedAt = at)
        }
    }
}

class RecordingOfflineMaps : OfflineMapController {
    val downloaded = mutableListOf<Long>()
    val deleted = mutableListOf<Long>()

    override suspend fun deleteForTrip(tripId: Long) {
        deleted += tripId
    }

    override suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double) {
        downloaded += tripId
    }
}
