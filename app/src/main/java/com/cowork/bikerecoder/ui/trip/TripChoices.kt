package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ui.common.ChoiceIcon
import com.cowork.bikerecoder.ui.common.SheetChoice

/**
 * What an end-of-guidance button does. [END_GUIDANCE_ONLY]: the guidance stops, the trip and its recording go on.
 * [CONTINUE_TOMORROW]: a single-day trip becomes a multi-day trip and stays active, ended for today like [STOP_TODAY].
 */
enum class EndAction { STOP_TODAY, COMPLETE_TRIP, END_GUIDANCE_ONLY, CONTINUE_TOMORROW }

/** One card of the "안내를 끝낼까요?" sheet. */
data class EndChoice(val action: EndAction, val card: SheetChoice)

const val END_QUESTION = "안내를 끝낼까요?"
const val TRIP_COMPLETED = "여행을 완료했습니다"
const val RECORDING_QUESTION = "기록을 어떻게 할까요?"

/**
 * Which sheet shows the end cards; what a card promises depends on it: the record screen's [정지] ends the recording,
 * the guidance's [종료] ends the guidance and, while it records ([GUIDANCE_RECORDING]), the recording with it.
 */
enum class EndSheet { RECORD, GUIDANCE_RECORDING, GUIDANCE_ONLY }

/** The copy of the end and start choice sheets (V1.9.1): card titles, their one-line notes and the bottom buttons. */
object TripChoiceText {
    const val FINISH_TODAY = "오늘로 끝내기"
    const val CONTINUE_TOMORROW = "내일 이어서 하기"
    const val STOP_TODAY = "오늘은 여기까지"
    const val COMPLETE_TRIP = "여행 완료"

    /** While guiding, [정지] ends only the recording. */
    const val END_RECORDING = "기록 끝내기"
    const val END_RECORDING_NOTE = "안내는 계속됩니다"
    const val END_GUIDANCE_ONLY = "안내만 끝내기"
    const val END_GUIDANCE_ONLY_NOTE = "기록은 계속됩니다"

    /** The continue sheet ([TripStartState.AskContinue]). */
    const val CONTINUE_QUESTION = "어떻게 기록할까요?"
    const val START_NEW = "새 기록 시작"
    const val START_NEW_NOTE = "지난 여행은 완료로 정리됩니다"

    /** The recording sheet ([TripStartState.AskRecording]). */
    const val KEEP_RECORDING = "이어서 기록"
    const val KEEP_RECORDING_NOTE = "지금 기록에 이어서 안내를 시작합니다"
    const val SPLIT_RECORDING = "새 구간으로 나누기"
    const val SPLIT_RECORDING_NOTE = "지금까지를 한 구간으로 저장하고 새 구간을 기록합니다"

    /** The bottom button of the record end sheet, and of every other sheet. */
    const val KEEP_GOING = "계속 기록하기"
    const val CANCEL = "취소"

    fun finishTodayNote(sheet: EndSheet) = when (sheet) {
        EndSheet.RECORD -> "이 기록을 마치고 요약을 봅니다"
        EndSheet.GUIDANCE_RECORDING -> "안내와 기록을 마치고 요약을 봅니다"
        EndSheet.GUIDANCE_ONLY -> "안내를 마치고 여행을 완료합니다"
    }

    fun continueTomorrowNote(sheet: EndSheet) = when (sheet) {
        EndSheet.RECORD, EndSheet.GUIDANCE_RECORDING -> "여러 날 여행으로 남겨 두고, 다음에 기록을 시작하면 이어서 쌓입니다"
        EndSheet.GUIDANCE_ONLY -> "여러 날 여행으로 남겨 두고, 다음에 시작할 때 이어 갑니다"
    }

    fun stopTodayNote(day: Int, sheet: EndSheet = EndSheet.RECORD) = when (sheet) {
        EndSheet.RECORD, EndSheet.GUIDANCE_RECORDING -> "${day}일째 기록을 마치고 여행은 이어 갑니다"
        EndSheet.GUIDANCE_ONLY -> "${day}일째 안내를 마치고 여행은 이어 갑니다"
    }

    fun completeTripNote(sheet: EndSheet) = when (sheet) {
        EndSheet.RECORD, EndSheet.GUIDANCE_RECORDING -> "여러 날 여행을 끝내고 오늘 기록의 요약을 봅니다"
        EndSheet.GUIDANCE_ONLY -> "여러 날 여행을 완료합니다"
    }

    fun continueTitle(day: Int) = "여행 이어서 · ${day}일째"

    /** "{name} · 지난 기록 {d}일, {거리}"; only [name] when nothing was recorded yet (or that is unknown). */
    fun continueNote(name: String, past: PastRecords?): String =
        if (past == null || past.days == 0) name else "$name · 지난 기록 ${past.days}일, ${RideFormat.distance(past.distanceM)}"
}

/** The cards shared by the record screen's [정지] and the guidance's [종료] sheets; [recommended] fills the card. */
object TripCards {
    fun finishToday(sheet: EndSheet = EndSheet.RECORD, recommended: Boolean = true) = SheetChoice(
        "finish-today", TripChoiceText.FINISH_TODAY, TripChoiceText.finishTodayNote(sheet), ChoiceIcon.FINISH, recommended,
    )

    fun continueTomorrow(sheet: EndSheet = EndSheet.RECORD) = SheetChoice(
        "continue-tomorrow", TripChoiceText.CONTINUE_TOMORROW, TripChoiceText.continueTomorrowNote(sheet), ChoiceIcon.TOMORROW,
    )

    fun stopToday(day: Int, sheet: EndSheet = EndSheet.RECORD, recommended: Boolean = true) = SheetChoice(
        "stop-today", TripChoiceText.STOP_TODAY, TripChoiceText.stopTodayNote(day, sheet), ChoiceIcon.TOMORROW, recommended,
    )

    fun completeTrip(sheet: EndSheet = EndSheet.RECORD) = SheetChoice(
        "complete-trip", TripChoiceText.COMPLETE_TRIP, TripChoiceText.completeTripNote(sheet), ChoiceIcon.FINISH,
    )

    fun endRecording() = SheetChoice(
        "end-recording", TripChoiceText.END_RECORDING, TripChoiceText.END_RECORDING_NOTE, ChoiceIcon.STOP_RECORDING,
        recommended = true,
    )

    fun guidanceOnly() = SheetChoice(
        "guidance-only", TripChoiceText.END_GUIDANCE_ONLY, TripChoiceText.END_GUIDANCE_ONLY_NOTE, ChoiceIcon.GUIDANCE_ONLY,
    )

    fun continueTrip(ask: TripStartState.AskContinue) = SheetChoice(
        "continue-trip",
        TripChoiceText.continueTitle(ask.day),
        TripChoiceText.continueNote(ask.destinationName, ask.past),
        ChoiceIcon.CONTINUE,
        recommended = true,
    )

    fun startNew() = SheetChoice("start-new", TripChoiceText.START_NEW, TripChoiceText.START_NEW_NOTE, ChoiceIcon.NEW)

    fun keepRecording() = SheetChoice(
        "keep-recording", TripChoiceText.KEEP_RECORDING, TripChoiceText.KEEP_RECORDING_NOTE, ChoiceIcon.CONTINUE,
        recommended = true,
    )

    fun splitRecording() = SheetChoice(
        "split-recording", TripChoiceText.SPLIT_RECORDING, TripChoiceText.SPLIT_RECORDING_NOTE, ChoiceIcon.SPLIT,
    )
}

/**
 * [종료] on the guidance screen (spec §3.3, V1.9.1): a single-day trip ends today ([오늘로 끝내기], suggested) or is kept
 * for tomorrow ([내일 이어서 하기]: it becomes a multi-day trip); a multi-day trip stops for today ([오늘은 여기까지] on its
 * [day]-th day, suggested) or completes. While [recording], the guidance alone can end first (the recording goes on),
 * and ending the trip ends the recording too; the cards' notes say which of the two they end.
 */
fun endChoices(type: TripType, recording: Boolean = false, day: Int = 1): List<EndChoice> {
    val sheet = if (recording) EndSheet.GUIDANCE_RECORDING else EndSheet.GUIDANCE_ONLY
    return listOfNotNull(EndChoice(EndAction.END_GUIDANCE_ONLY, TripCards.guidanceOnly()).takeIf { recording }) + when (type) {
        TripType.SINGLE_DAY -> listOf(
            EndChoice(EndAction.COMPLETE_TRIP, TripCards.finishToday(sheet)),
            EndChoice(EndAction.CONTINUE_TOMORROW, TripCards.continueTomorrow(sheet)),
        )
        TripType.MULTI_DAY -> listOf(
            EndChoice(EndAction.STOP_TODAY, TripCards.stopToday(day, sheet)),
            EndChoice(EndAction.COMPLETE_TRIP, TripCards.completeTrip(sheet)),
        )
    }
}

/** The completion screen offers [여러 날로 바꾸기] for a single-day trip that was completed. */
fun canConvertToMultiDay(finished: NavUiState.Finished): Boolean =
    finished.type == TripType.SINGLE_DAY && finished.reason != FinishReason.STOPPED_TODAY && finished.error == null
