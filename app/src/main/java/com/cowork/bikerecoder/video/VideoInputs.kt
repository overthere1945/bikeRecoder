package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.activity.ActivityMix
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.activity.BestEffortDistance
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.recording.RideStore
import com.cowork.bikerecoder.core.trip.TripLabelStore
import com.cowork.bikerecoder.core.trip.TripStore
import com.cowork.bikerecoder.core.video.PhotoCue
import com.cowork.bikerecoder.core.video.RideTimeline
import com.cowork.bikerecoder.core.video.RideTimelineBuilder
import com.cowork.bikerecoder.core.video.RideTitle
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.ui.history.cardDateText
import com.cowork.bikerecoder.ui.history.tripHeartRate
import com.cowork.bikerecoder.ui.history.tripTotals
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * The trip's numbers on the video's last card and the summary image (ruling V1): sums over the target segments,
 * average speed = distance / moving time, the highest speed, the ascent, and the heart rate average / calories when
 * known (spec §2.4). The activity types add (V1.9.0 spec §5.1): [hikeElapsedMs] the hike segments' elapsed time (rests
 * included), [maxAltitudeM] the highest point of the segments that know one, and [fastestKmMs] the fastest 1 km among the
 * run segments' saved best efforts (null when none covered 1 km). [paceDistanceM] and [paceMovingTimeMs] are the sums of
 * the dominant activity's segments only, the average pace of a run or walk.
 */
data class TripTotals(
    val distanceM: Double,
    val movingTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val ascentM: Double,
    val avgBpm: Int?,
    val calories: Double?,
    val hikeElapsedMs: Long = 0L,
    val maxAltitudeM: Double? = null,
    val fastestKmMs: Long? = null,
    val paceDistanceM: Double = distanceM,
    val paceMovingTimeMs: Long = movingTimeMs,
)

/**
 * What a video or summary image of trip [tripId] is made from. [timeline] joins the target segments (finished,
 * ≥ [MIN_RIDE_DISTANCE_M], spec §1.1) on one moving-time axis; [photos] are the shown photos (hidden ones left out)
 * at their moving time; [heartRate] the target segments' samples in time order; [dayStarts] the "N일째 · M월 d일"
 * captions; [title] "10월 25일 (일)" or "10월 17일 ~ 18일"; [rideTitle] the automatic name
 * (spec §3.8: "저녁 라이딩" from the first start, "N일 여행" over several days; the job may add the region — unless it is the trip's
 * custom title, [titleIsCustom], which is used as it is, V1.4.0 spec §2.5); [canColorByHeart] some target
 * segment has detailed heart rate (the [심박] option); [firstStartedAt] when the first target segment was started (the file
 * name, spec §3.5 — not its first GPS fix). [segmentActivities] is the activity of each target segment by the timeline's
 * `segmentIndex` (the feed picture's line colours and the number band's per-frame numbers; empty = all bike);
 * [activity] the trip's dominant activity (the closing card, the summary image and the title badge).
 */
data class VideoInputs(
    val tripId: Long,
    val timeline: RideTimeline,
    val photos: List<PhotoCue>,
    val heartRate: List<HeartRateSample>,
    val dayStarts: List<Pair<Long, String>>,
    val title: String,
    val rideTitle: String,
    val totals: TripTotals,
    val canColorByHeart: Boolean,
    val firstStartedAt: Long,
    val titleIsCustom: Boolean = false,
    val segmentActivities: List<ActivityType> = emptyList(),
    val activity: ActivityType = ActivityType.BIKE,
)

/**
 * The activity of each target segment by the timeline's `segmentIndex`: [VideoInputs.segmentActivities], or, when the
 * inputs carry none, the dominant [VideoInputs.activity] for every segment.
 */
fun VideoInputs.activitiesBySegment(): List<ActivityType> =
    segmentActivities.ifEmpty { List((timeline.points.lastOrNull()?.segmentIndex ?: -1) + 1) { activity } }

class VideoInputsLoader(
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val tripStore: TripStore,
    private val labels: TripLabelStore,
    private val zone: ZoneId = SEOUL,
) {

    /** Null when the trip is gone or has no target segment with points. */
    suspend fun load(tripId: Long): VideoInputs? {
        tripStore.trip(tripId) ?: return null
        val segments = rideStore.segments(tripId)
            .filter { it.endedAt != null && it.totals.distanceM >= MIN_RIDE_DISTANCE_M }
            .map { it to rideStore.points(it.id) }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first.startedAt }
        if (segments.isEmpty()) return null
        val targets = segments.map { it.first }
        val timeline = RideTimelineBuilder.build(segments, zone)
        if (timeline.points.isEmpty()) return null

        val ids = targets.map { it.id }
        val photos = postStore.observePhotos(ids).first()
            .filterNot { it.hidden }
            .distinctBy { it.mediaId }
            .map { PhotoCue(it.mediaId, moveMsOf(timeline, it.takenAt), it.takenAt) }
        val heartRate = targets.flatMap { postStore.samples(it.id) }.sortedBy { it.timeMillis }
        val extras = targets.mapNotNull { postStore.extras(it.id) }
        val hr = tripHeartRate(targets.groupBy { it.localDate }, extras)
        val totals = tripTotals(targets)
        val days = targets.map { it.localDate }.distinct().sorted()
        val custom = labels.label(tripId).customTitle?.takeIf { it.isNotBlank() }
        val activity = ActivityMix.of(targets).dominant
        val fastestKmMs = targets.filter { it.activity == ActivityType.RUN }
            .flatMap { rideStore.bestEfforts(it.id) }
            .filter { it.distance == BestEffortDistance.ONE_K }
            .minOfOrNull { it.timeMs }

        return VideoInputs(
            tripId = tripId,
            timeline = timeline,
            photos = photos,
            heartRate = heartRate,
            dayStarts = timeline.dayStarts,
            title = titleOf(days.first(), days.last()),
            rideTitle = custom ?: RideTitle.base(targets.first().startedAt, days.size, zone, activity),
            totals = TripTotals(
                distanceM = totals.distanceM,
                movingTimeMs = totals.movingTimeMs,
                avgSpeedMps = totals.avgSpeedMps,
                maxSpeedMps = totals.maxSpeedMps,
                ascentM = totals.ascentM,
                avgBpm = hr.avgBpm,
                calories = hr.calories,
                hikeElapsedMs = targets.filter { it.activity == ActivityType.HIKE }.sumOf { it.totals.elapsedMs },
                maxAltitudeM = targets.mapNotNull { it.totals.maxAltitudeM }.maxOrNull(),
                fastestKmMs = fastestKmMs,
                // A run's or walk's average pace: the dominant activity's segments only.
                paceDistanceM = targets.filter { it.activity == activity }.sumOf { it.totals.distanceM },
                paceMovingTimeMs = targets.filter { it.activity == activity }.sumOf { it.totals.movingTimeMs },
            ),
            canColorByHeart = hr.canColor,
            firstStartedAt = targets.first().startedAt,
            titleIsCustom = custom != null,
            segmentActivities = targets.map { it.activity },
            activity = activity,
        )
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** Ruling V3: a photo before the first / after the last point goes to the start / end. */
        fun moveMsOf(timeline: RideTimeline, takenAt: Long): Long = timeline.moveMsAtTime(takenAt)
            ?: if (takenAt < timeline.points.first().timeMillis) 0L else timeline.totalMoveMs

        /** One day: "10월 25일 (일)"; several: "10월 17일 ~ 18일", or "10월 31일 ~ 11월 1일" across months. */
        fun titleOf(first: LocalDate, last: LocalDate): String = when {
            first == last -> cardDateText(first)
            first.year == last.year && first.month == last.month ->
                "${first.monthValue}월 ${first.dayOfMonth}일 ~ ${last.dayOfMonth}일"
            else -> "${first.monthValue}월 ${first.dayOfMonth}일 ~ ${last.monthValue}월 ${last.dayOfMonth}일"
        }
    }
}
