package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RecordingEnd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordChoicesTest {

    @Test
    fun `end choices`() {
        assertEquals(
            listOf(RecordEndChoice("끝내기", RecordingEnd.COMPLETE_TRIP)),
            recordEndChoices(TripType.SINGLE_DAY, navigating = false),
        )
        assertEquals(
            listOf(
                RecordEndChoice("오늘은 여기까지", RecordingEnd.STOP_TODAY),
                RecordEndChoice("여행 완료", RecordingEnd.COMPLETE_TRIP),
            ),
            recordEndChoices(TripType.MULTI_DAY, navigating = false),
        )
        TripType.entries.forEach { type ->
            assertEquals(
                listOf(RecordEndChoice("기록 끝내기", RecordingEnd.SEGMENT_ONLY)),
                recordEndChoices(type, navigating = true),
            )
        }
        assertEquals("기록을 끝낼까요?", RECORD_END_QUESTION)
        assertEquals("기록이 중단되었습니다", RECORDING_INTERRUPTED)
    }

    @Test
    fun `band and chip texts`() {
        assertEquals("일시정지됨 (자동)", pauseBandText(PauseKind.AUTO))
        assertEquals("일시정지됨 (수동)", pauseBandText(PauseKind.MANUAL))
        assertEquals("자동 일시정지 ON", autoPauseChipText(true))
        assertEquals("자동 일시정지 OFF", autoPauseChipText(false))
    }
}
