package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import com.cowork.bikerecoder.ui.activity.ActivityText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripChoicesTest {

    /** (title, subtitle, action, recommended) of each card, in order. */
    private fun table(choices: List<EndChoice>) =
        choices.map { listOf(it.card.title, it.card.subtitle, it.action, it.card.recommended) }

    @Test
    fun singleDayEndFinishesTodayOrKeepsTheTripForTomorrow() {
        assertEquals(
            listOf(
                listOf("오늘로 끝내기", "안내를 마치고 여행을 완료합니다", EndAction.COMPLETE_TRIP, true),
                listOf(
                    "내일 이어서 하기", "여러 날 여행으로 남겨 두고, 다음에 시작할 때 이어 갑니다",
                    EndAction.CONTINUE_TOMORROW, false,
                ),
            ),
            table(endChoices(TripType.SINGLE_DAY)),
        )
    }

    @Test
    fun multiDayEndOffersStopTodayOnItsDayOrComplete() {
        assertEquals(
            listOf(
                listOf("오늘은 여기까지", "3일째 안내를 마치고 여행은 이어 갑니다", EndAction.STOP_TODAY, true),
                listOf("여행 완료", "여러 날 여행을 완료합니다", EndAction.COMPLETE_TRIP, false),
            ),
            table(endChoices(TripType.MULTI_DAY, day = 3)),
        )
    }

    @Test
    fun `end choices while recording`() {
        val guidanceOnly = listOf("안내만 끝내기", "기록은 계속됩니다", EndAction.END_GUIDANCE_ONLY, false)
        assertEquals(
            listOf(
                guidanceOnly,
                listOf("오늘로 끝내기", "안내와 기록을 마치고 요약을 봅니다", EndAction.COMPLETE_TRIP, true),
                listOf(
                    "내일 이어서 하기", "여러 날 여행으로 남겨 두고, 다음에 기록을 시작하면 이어서 쌓입니다",
                    EndAction.CONTINUE_TOMORROW, false,
                ),
            ),
            table(endChoices(TripType.SINGLE_DAY, recording = true)),
        )
        assertEquals(
            listOf(
                guidanceOnly,
                listOf("오늘은 여기까지", "2일째 기록을 마치고 여행은 이어 갑니다", EndAction.STOP_TODAY, true),
                listOf("여행 완료", "여러 날 여행을 끝내고 오늘 기록의 요약을 봅니다", EndAction.COMPLETE_TRIP, false),
            ),
            table(endChoices(TripType.MULTI_DAY, recording = true, day = 2)),
        )
    }

    @Test
    fun `end choices while not recording have no guidance-only card and name only the guidance`() {
        assertEquals(2, endChoices(TripType.SINGLE_DAY, recording = false).size)
        assertEquals(2, endChoices(TripType.MULTI_DAY, recording = false, day = 2).size)
        assertEquals(3, endChoices(TripType.SINGLE_DAY, recording = true).size)
        val notes = (endChoices(TripType.SINGLE_DAY) + endChoices(TripType.MULTI_DAY, day = 2)).map { it.card.subtitle }
        assertTrue(notes.none { it.contains("기록") }, "no recording runs, so no card talks about one: $notes")
    }

    @Test
    fun everyCardHasItsOwnTestId() {
        val ids = (endChoices(TripType.SINGLE_DAY, recording = true) + endChoices(TripType.MULTI_DAY, recording = true))
            .map { it.card.id }
        assertEquals(
            listOf("guidance-only", "finish-today", "continue-tomorrow", "guidance-only", "stop-today", "complete-trip"),
            ids,
        )
    }

    @Test
    fun startSheetsCopy() {
        assertEquals("어떻게 기록할까요?", TripChoiceText.CONTINUE_QUESTION)
        assertEquals("새 기록 시작", TripCards.startNew().title)
        assertEquals("지난 여행은 완료로 정리됩니다", TripCards.startNew().subtitle)
        assertEquals("기록을 어떻게 할까요?", RECORDING_QUESTION)
        assertEquals(listOf("이어서 기록", "새 구간으로 나누기"), listOf(TripCards.keepRecording(), TripCards.splitRecording()).map { it.title })
        assertEquals("주행 기록 · 지난 기록 1일, 3.00km", TripChoiceText.continueNote(ActivityText.recordName(ActivityType.BIKE), PastRecords(1, 3_009.9)))
        assertEquals("남산", TripChoiceText.continueNote("남산", PastRecords(0, 0.0)), "nothing recorded yet")
        assertEquals("남산", TripChoiceText.continueNote("남산", null))
    }

    @Test
    fun onlyACompletedSingleDayTripCanBecomeMultiDay() {
        assertTrue(canConvertToMultiDay(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED)))
        assertTrue(canConvertToMultiDay(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.COMPLETED)))
        assertFalse(canConvertToMultiDay(NavUiState.Finished(1, TripType.MULTI_DAY, FinishReason.ARRIVED)))
        assertFalse(canConvertToMultiDay(NavUiState.Finished(1, TripType.MULTI_DAY, FinishReason.COMPLETED)))
        assertFalse(canConvertToMultiDay(NavUiState.Finished(1, TripType.SINGLE_DAY, FinishReason.ARRIVED, error = "x")))
    }
}
