package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState

/** What an end-of-guidance button does. [END_GUIDANCE_ONLY]: the guidance stops, the trip and its recording go on. */
enum class EndAction { STOP_TODAY, COMPLETE_TRIP, END_GUIDANCE_ONLY }

data class EndChoice(val label: String, val action: EndAction)

const val TRIP_TYPE_QUESTION = "오늘 하루 일정인가요, 여러 날 일정인가요?"
const val END_QUESTION = "안내를 끝낼까요?"
const val TRIP_COMPLETED = "여행을 완료했습니다"

/** The name of a trip without stops (started from [● 기록 시작]) where a destination name would be shown. */
const val RIDE_RECORD_NAME = "주행 기록"

fun continueQuestion(destinationName: String) = "$destinationName 여행을 이어서 진행할까요?"

/**
 * [종료] on the guidance screen (spec §3.3): a single-day trip just ends; a multi-day trip can pause for today. While
 * [recording], the guidance alone can end first (the recording goes on), and ending the trip ends the recording too.
 */
fun endChoices(type: TripType, recording: Boolean = false): List<EndChoice> = when (type) {
    TripType.SINGLE_DAY -> if (recording) {
        listOf(
            EndChoice("안내만 끝내기 (기록 계속)", EndAction.END_GUIDANCE_ONLY),
            EndChoice("모두 끝내기", EndAction.COMPLETE_TRIP),
        )
    } else {
        listOf(EndChoice("끝내기", EndAction.COMPLETE_TRIP))
    }
    TripType.MULTI_DAY -> listOfNotNull(
        EndChoice("안내만 끝내기", EndAction.END_GUIDANCE_ONLY).takeIf { recording },
        EndChoice("오늘은 여기까지", EndAction.STOP_TODAY),
        EndChoice("여행 완료", EndAction.COMPLETE_TRIP),
    )
}

/** The completion screen offers [여러 날로 바꾸기] for a single-day trip that was completed. */
fun canConvertToMultiDay(finished: NavUiState.Finished): Boolean =
    finished.type == TripType.SINGLE_DAY && finished.reason != FinishReason.STOPPED_TODAY && finished.error == null
