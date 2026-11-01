package com.cowork.bikerecoder.ui.record

import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.recording.PauseKind
import com.cowork.bikerecoder.core.recording.RideStats
import com.cowork.bikerecoder.core.trip.TripType
import com.cowork.bikerecoder.ride.RecordingEnd
import com.cowork.bikerecoder.ui.trip.TripChoiceText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordChoicesTest {

    /** (title, subtitle, end, recommended) of each card, in order. */
    private fun table(choices: List<RecordEndChoice>) =
        choices.map { listOf(it.card.title, it.card.subtitle, it.end, it.card.recommended) }

    @Test
    fun `end choices`() {
        assertEquals(
            listOf(
                listOf("오늘로 끝내기", "이 기록을 마치고 요약을 봅니다", RecordingEnd.COMPLETE_TRIP, true),
                listOf(
                    "내일 이어서 하기", "여러 날 여행으로 남겨 두고, 다음에 기록을 시작하면 이어서 쌓입니다",
                    RecordingEnd.CONTINUE_TOMORROW, false,
                ),
            ),
            table(recordEndChoices(TripType.SINGLE_DAY, navigating = false)),
        )
        assertEquals(
            listOf(
                listOf("오늘은 여기까지", "2일째 기록을 마치고 여행은 이어 갑니다", RecordingEnd.STOP_TODAY, true),
                listOf("여행 완료", "여러 날 여행을 끝내고 전체 요약을 봅니다", RecordingEnd.COMPLETE_TRIP, false),
            ),
            table(recordEndChoices(TripType.MULTI_DAY, navigating = false, day = 2)),
        )
        TripType.entries.forEach { type ->
            assertEquals(
                listOf(listOf("기록 끝내기", "안내는 계속됩니다", RecordingEnd.SEGMENT_ONLY, true)),
                table(recordEndChoices(type, navigating = true)),
            )
        }
        assertEquals("기록을 끝낼까요?", RECORD_END_QUESTION)
        assertEquals("기록이 중단되었습니다", RECORDING_INTERRUPTED)
        assertEquals("계속 기록하기", TripChoiceText.KEEP_GOING)
    }

    @Test
    fun `the end sheet's summary line`() {
        val stats = RideStats.ZERO.copy(distanceM = 12_345.0, movingTimeMs = 3_725_000, elapsedMs = 4_000_000)

        assertEquals("자전거 · 12.34km · 1:02:05", recordEndSummary(stats, ActivityType.BIKE))
        assertEquals("달리기 · 12.34km · 1:02:05", recordEndSummary(stats, ActivityType.RUN))
        // A hike counts its hiking (elapsed) time.
        assertEquals("등산 · 12.34km · 1:06:40", recordEndSummary(stats, ActivityType.HIKE))
    }

    @Test
    fun `band and chip texts`() {
        assertEquals("일시정지됨 (자동)", pauseBandText(PauseKind.AUTO))
        assertEquals("일시정지됨 (수동)", pauseBandText(PauseKind.MANUAL))
        assertEquals("자동 일시정지 ON", autoPauseChipText(true))
        assertEquals("자동 일시정지 OFF", autoPauseChipText(false))
    }
}
