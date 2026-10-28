package com.cowork.bikerecoder.video

import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.MIN_RIDE_DISTANCE_M
import com.cowork.bikerecoder.core.post.RideColors
import com.cowork.bikerecoder.core.recording.RideStore
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
 * known (spec §2.4).
 */
data class TripTotals(
    val distanceM: Double,
    val movingTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val ascentM: Double,
    val avgBpm: Int?,
    val calories: Double?,
)

/**
 * What a video or summary image of trip [tripId] is made from. [timeline] joins the target segments (finished,
 * ≥ [MIN_RIDE_DISTANCE_M], spec §1.1) on one moving-time axis; [photos] are the shown photos (hidden ones left out)
 * at their moving time; [heartRate] the target segments' samples in time order; [dayStarts] the "N일째 · M월 d일"
 * captions; [segmentColors] one colour per target segment (its day's [RideColors] colour, spec §2.2);
 * [title] "10월 25일 (일)" or "10월 17일 ~ 18일"; [rideTitle] the automatic name
 * (spec §3.8: "저녁 라이딩" from the first start, "N일 여행" over several days; the job may add the region); [canColorByHeart] some target
 * segment has detailed heart rate (the [심박] option); [firstStartedAt] when the first target segment was started (the file
 * name, spec §3.5 — not its first GPS fix).
 */
data class VideoInputs(
    val tripId: Long,
    val timeline: RideTimeline,
    val photos: List<PhotoCue>,
    val heartRate: List<HeartRateSample>,
    val dayStarts: List<Pair<Long, String>>,
    val segmentColors: List<String>,
    val title: String,
    val rideTitle: String,
    val totals: TripTotals,
    val canColorByHeart: Boolean,
    val firstStartedAt: Long,
)

class VideoInputsLoader(
    private val rideStore: RideStore,
    private val postStore: PostStore,
    private val tripStore: TripStore,
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
        val dayIndex = days.withIndex().associate { it.value to it.index }

        return VideoInputs(
            tripId = tripId,
            timeline = timeline,
            photos = photos,
            heartRate = heartRate,
            dayStarts = timeline.dayStarts,
            segmentColors = targets.map { RideColors.colorAt(dayIndex.getValue(it.localDate)) },
            title = titleOf(days.first(), days.last()),
            rideTitle = RideTitle.base(targets.first().startedAt, days.size, zone),
            totals = TripTotals(
                distanceM = totals.distanceM,
                movingTimeMs = totals.movingTimeMs,
                avgSpeedMps = totals.avgSpeedMps,
                maxSpeedMps = totals.maxSpeedMps,
                ascentM = totals.ascentM,
                avgBpm = hr.avgBpm,
                calories = hr.calories,
            ),
            canColorByHeart = hr.canColor,
            firstStartedAt = targets.first().startedAt,
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
