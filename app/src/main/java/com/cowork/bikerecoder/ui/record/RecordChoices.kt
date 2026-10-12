package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RecordingEnd

const val RECORD_END_QUESTION = "기록을 끝낼까요?"
const val RECORDING_INTERRUPTED = "기록이 중단되었습니다"

/** One button of the [정지] confirmation. */
data class RecordEndChoice(val label: String, val end: RecordingEnd)

/**
 * [정지] on the record screen: while guiding only the recording ends (the guidance owns the trip); otherwise a
 * single-day trip completes and a multi-day trip can stop for today or complete.
 */
fun recordEndChoices(type: TripType, navigating: Boolean): List<RecordEndChoice> = when {
    navigating -> listOf(RecordEndChoice("기록 끝내기", RecordingEnd.SEGMENT_ONLY))
    type == TripType.SINGLE_DAY -> listOf(RecordEndChoice("끝내기", RecordingEnd.COMPLETE_TRIP))
    else -> listOf(
        RecordEndChoice("오늘은 여기까지", RecordingEnd.STOP_TODAY),
        RecordEndChoice("여행 완료", RecordingEnd.COMPLETE_TRIP),
    )
}

/** The band shown while paused. */
fun pauseBandText(kind: PauseKind): String = when (kind) {
    PauseKind.AUTO -> "일시정지됨 (자동)"
    PauseKind.MANUAL -> "일시정지됨 (수동)"
}

/** The auto-pause chip on top of the record screen. */
fun autoPauseChipText(enabled: Boolean): String = if (enabled) "자동 일시정지 ON" else "자동 일시정지 OFF"
