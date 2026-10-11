package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix

/** Recording test tracks, shared by the JVM tests and the device tests. Same Earth radius as GeoMath. */
object RideFixtures {

    /** Stand still [seconds] seconds at the first sample at or beyond [atM] metres. */
    data class StopAt(val atM: Double, val seconds: Int)

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** [m] metres north of the start, on a meridian, so the haversine distance between two of these is the difference in m. */
    fun north(m: Double): GeoPoint = GeoPoint(37.5 + Math.toDegrees(m / EARTH_RADIUS_M), 127.03)

    /**
     * One fix per second going straight north from 0 to [distanceM] at [speedKmh] (accuracy 5 m, speedMps set,
     * altitude 50 m); the last fix is at exactly [distanceM]. At each of [stops] the rider stays [seconds] seconds
     * at the same place with speed 0. Fix times start at [startMillis].
     */
    fun straight(
        distanceM: Double,
        speedKmh: Double = 18.0,
        stops: List<StopAt> = emptyList(),
        startMillis: Long = 0L,
    ): List<LocationFix> {
        val stepM = speedKmh / 3.6
        val positions = buildList {
            var i = 0
            while (i * stepM < distanceM - 1e-9) add(i++ * stepM)
            add(distanceM)
        }
        val pending = stops.sortedBy { it.atM }.toMutableList()
        val fixes = mutableListOf<LocationFix>()
        var t = startMillis
        for (m in positions) {
            fixes += fix(m, stepM.toFloat(), t)
            t += 1_000
            while (pending.isNotEmpty() && m >= pending.first().atM - 1e-9) {
                repeat(pending.removeAt(0).seconds) {
                    fixes += fix(m, 0f, t)
                    t += 1_000
                }
            }
        }
        return fixes
    }

    private fun fix(m: Double, speedMps: Float, t: Long) = LocationFix(
        point = north(m),
        accuracyM = 5f,
        speedMps = speedMps,
        bearingDeg = 0f,
        timeMillis = t,
        altitudeM = 50.0,
    )
}
