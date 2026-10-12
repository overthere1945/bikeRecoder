package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import com.cowork.bikerecoder.core.recording.RecordingState
import com.cowork.bikerecoder.core.recording.RideSegment
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType

/** What the recording screen and the service notification show. */
sealed interface RideUiState {
    data object Idle : RideUiState

    /** Closing leftover segments and opening the new one. */
    data class Starting(val tripId: Long) : RideUiState

    data class Active(
        val tripId: Long,
        val tripType: TripType,
        val segmentId: Long,
        val state: RecordingState,
        val stats: RideStats,
        val autoPause: Boolean,
        /** For the map: accepted locations only, each at least 10 m from the previous one. */
        val track: List<GeoPoint>,
        /** The latest location (accepted or not). */
        val fix: LocationFix?,
        val gpsWeak: Boolean,
        /** "기록 저장에 문제가 있습니다" from the first failed save on (once per recording). */
        val saveWarning: String?,
    ) : RideUiState

    /** The start failed ("기록을 시작하지 못했습니다"); nothing records. */
    data class Failed(val tripId: Long, val message: String) : RideUiState

    /**
     * The segment was closed (the summary is shown). [error]: "기록 저장에 문제가 있습니다" when a write at the stop
     * (the last points, the segment or the trip state) failed.
     */
    data class Finished(val segment: RideSegment, val track: List<GeoPoint>, val error: String?) : RideUiState
}

/** The trip of a recording that is starting or running; null when none is. */
val RideUiState.recordingTripId: Long?
    get() = when (this) {
        is RideUiState.Starting -> tripId
        is RideUiState.Active -> tripId
        else -> null
    }
