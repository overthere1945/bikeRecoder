package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState

/** What an end-of-guidance button does. */
enum class EndAction { STOP_TODAY, COMPLETE_TRIP }

data class EndChoice(val label: String, val action: EndAction)

const val TRIP_TYPE_QUESTION = "오늘 하루 일정인가요, 여러 날 일정인가요?"
const val END_QUESTION = "안내를 끝낼까요?"
const val TRIP_COMPLETED = "여행을 완료했습니다"

fun continueQuestion(destinationName: String) = "$destinationName 여행을 이어서 진행할까요?"

/** [종료] on the guidance screen: a single-day trip just ends; a multi-day trip can pause for today. */
fun endChoices(type: TripType): List<EndChoice> = when (type) {
    TripType.SINGLE_DAY -> listOf(EndChoice("끝내기", EndAction.COMPLETE_TRIP))
    TripType.MULTI_DAY -> listOf(
        EndChoice("오늘은 여기까지", EndAction.STOP_TODAY),
        EndChoice("여행 완료", EndAction.COMPLETE_TRIP),
    )
}

/** The completion screen offers [여러 날로 바꾸기] for a single-day trip that was completed. */
fun canConvertToMultiDay(finished: NavUiState.Finished): Boolean =
    finished.type == TripType.SINGLE_DAY && finished.reason != FinishReason.STOPPED_TODAY && finished.error == null
