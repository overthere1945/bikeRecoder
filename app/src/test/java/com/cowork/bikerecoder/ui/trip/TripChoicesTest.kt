package com.cowork.bikerecoder.ui.trip

import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.nav.FinishReason
import com.cowork.bikerecoder.nav.NavUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripChoicesTest {

    @Test
    fun singleDayEndAsksToFinish() {
        assertEquals(listOf(EndChoice("끝내기", EndAction.COMPLETE_TRIP)), endChoices(TripType.SINGLE_DAY))
    }

    @Test
    fun multiDayEndOffersStopTodayOrComplete() {
        assertEquals(
            listOf(EndChoice("오늘은 여기까지", EndAction.STOP_TODAY), EndChoice("여행 완료", EndAction.COMPLETE_TRIP)),
            endChoices(TripType.MULTI_DAY),
        )
    }

    @Test
    fun `end choices while recording`() {
        assertEquals(
            listOf(
                EndChoice("안내만 끝내기 (기록 계속)", EndAction.END_GUIDANCE_ONLY),
                EndChoice("모두 끝내기", EndAction.COMPLETE_TRIP),
            ),
            endChoices(TripType.SINGLE_DAY, recording = true),
        )
        assertEquals(
            listOf(
                EndChoice("안내만 끝내기", EndAction.END_GUIDANCE_ONLY),
                EndChoice("오늘은 여기까지", EndAction.STOP_TODAY),
                EndChoice("여행 완료", EndAction.COMPLETE_TRIP),
            ),
            endChoices(TripType.MULTI_DAY, recording = true),
        )
        // Not recording: the V1.0.0 choices.
        assertEquals(listOf(EndChoice("끝내기", EndAction.COMPLETE_TRIP)), endChoices(TripType.SINGLE_DAY, recording = false))
        assertEquals(
            listOf(EndChoice("오늘은 여기까지", EndAction.STOP_TODAY), EndChoice("여행 완료", EndAction.COMPLETE_TRIP)),
            endChoices(TripType.MULTI_DAY, recording = false),
        )
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
