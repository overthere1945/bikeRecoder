package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ui.activity.ActivityText
import com.cowork.bikerecoder.ui.common.SheetChoice
import com.cowork.bikerecoder.ui.trip.TripCards

const val RECORD_END_QUESTION = "기록을 끝낼까요?"
const val RECORDING_INTERRUPTED = "기록이 중단되었습니다"

/** One card of the [정지] sheet. */
data class RecordEndChoice(val end: RecordingEnd, val card: SheetChoice)

/**
 * [정지] on the record screen (V1.9.1): while guiding only the recording ends (the guidance owns the trip); otherwise a
 * single-day trip ends today ([오늘로 끝내기], suggested) or is kept for tomorrow ([내일 이어서 하기]: it becomes a
 * multi-day trip), and a multi-day trip stops for today ([오늘은 여기까지] on its [day]-th day, suggested) or completes.
 */
fun recordEndChoices(type: TripType, navigating: Boolean, day: Int = 1): List<RecordEndChoice> = when {
    navigating -> listOf(RecordEndChoice(RecordingEnd.SEGMENT_ONLY, TripCards.endRecording()))
    type == TripType.SINGLE_DAY -> listOf(
        RecordEndChoice(RecordingEnd.COMPLETE_TRIP, TripCards.finishToday()),
        RecordEndChoice(RecordingEnd.CONTINUE_TOMORROW, TripCards.continueTomorrow()),
    )
    else -> listOf(
        RecordEndChoice(RecordingEnd.STOP_TODAY, TripCards.stopToday(day)),
        RecordEndChoice(RecordingEnd.COMPLETE_TRIP, TripCards.completeTrip()),
    )
}

/**
 * The record end sheet's summary line beside the activity badge: "{종류 이름} · {거리} · {이동 시간}" so far; a hike counts
 * its hiking (elapsed) time, as everywhere else it is shown.
 */
fun recordEndSummary(stats: RideStats, activity: ActivityType): String {
    val time = if (activity == ActivityType.HIKE) stats.elapsedMs else stats.movingTimeMs
    return "${ActivityText.name(activity)} · ${RideFormat.distance(stats.distanceM)} · ${RideFormat.clock(time)}"
}

/** The band shown while paused. */
fun pauseBandText(kind: PauseKind): String = when (kind) {
    PauseKind.AUTO -> "일시정지됨 (자동)"
    PauseKind.MANUAL -> "일시정지됨 (수동)"
}

/** The auto-pause chip on top of the record screen. */
fun autoPauseChipText(enabled: Boolean): String = if (enabled) "자동 일시정지 ON" else "자동 일시정지 OFF"
