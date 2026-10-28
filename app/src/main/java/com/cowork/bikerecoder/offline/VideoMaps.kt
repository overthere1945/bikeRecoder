package com.cowork.bikerecoder.offline

import com.cowork.bikerecoder.core.model.GeoPoint

/** The map around a finished trip's track while its video or image is made (spec §3.1①); the job deletes it after. */
interface VideoMaps {
    /**
     * Downloads the map around [points] for trip [tripId] and returns when it is there (or stalled, spec §3.4).
     * False only when there is no internet and the trip has no map of its own ("지도를 받으려면 인터넷이 필요합니다").
     */
    suspend fun downloadForTrack(tripId: Long, points: List<GeoPoint>): Boolean

    /** Deletes what [downloadForTrack] downloaded for [tripId]; the trip's navigation map stays. */
    suspend fun deleteVideoRegions(tripId: Long)
}
