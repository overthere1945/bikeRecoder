package com.cowork.bikerecoder.ui.elevation

import com.cowork.bikerecoder.core.elevation.ElevationProfile
import com.cowork.bikerecoder.core.model.GeoPoint
import java.util.concurrent.atomic.AtomicReference

/** What the "고도" map screen shows the profile of (V1.7.0 spec §2.2). */
sealed interface ElevationSource {
    data class Trip(val tripId: Long) : ElevationSource

    data class Segment(val segmentId: Long) : ElevationSource

    /** The plan preview's current route, handed over through [ElevationHandoff] (it is not in the nav route). */
    data object Plan : ElevationSource
}

/**
 * The plan preview's profile and route line on their way to the "고도" map screen (in memory only, like the import's
 * handoff): the screen opens on top of the plan preview, so the route cannot change meanwhile (spec §2.2).
 */
class ElevationHandoff {
    private val value = AtomicReference<Pair<ElevationProfile, List<GeoPoint>>?>(null)

    fun put(profile: ElevationProfile, path: List<GeoPoint>) {
        value.set(profile to path)
    }

    /** What was handed over, once; null when nothing was (or it was taken). */
    fun take(): Pair<ElevationProfile, List<GeoPoint>>? = value.getAndSet(null)
}
