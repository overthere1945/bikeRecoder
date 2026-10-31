package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.importing.Activity
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.RideStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ImportFormatTest {

    private fun row(status: RideStatus, distanceM: Double = 32_100.0, movingMs: Long = 105 * 60_000L, activity: Activity? = Activity.CYCLING) =
        ScanRow(RideKey(0, 0), "a.gpx", T0, T0 + movingMs, distanceM, movingMs, activity, status, emptyList())

    @Test
    fun problemTexts() {
        assertEquals("GPX 파일이 아닙니다", problemText(ImportProblem.NOT_GPX))
        assertEquals("시간이 없는 기록입니다", problemText(ImportProblem.NO_TIME))
        assertEquals("너무 짧은 기록입니다", problemText(ImportProblem.TOO_SHORT))
        assertEquals("너무 큰 파일입니다", problemText(ImportProblem.TOO_LARGE))
        assertEquals("파일을 읽을 수 없습니다", problemText(ImportProblem.UNREADABLE))
    }

    @Test
    fun statusLines() {
        assertEquals("32.1 km · 1시간 45분", statusLine(row(RideStatus.New)))
        assertEquals("12.0 km · 40분", statusLine(row(RideStatus.New, distanceM = 12_000.0, movingMs = 40 * 60_000L)))
        assertEquals(
            "달리기 · 6.2 km — 자전거가 아님",
            statusLine(row(RideStatus.NotCycling, distanceM = 6_200.0, activity = Activity.RUNNING)),
        )
        assertEquals("걷기 · 1.0 km — 자전거가 아님", statusLine(row(RideStatus.NotCycling, 1_000.0, activity = Activity.WALKING)))
        assertEquals("등산 · 1.0 km — 자전거가 아님", statusLine(row(RideStatus.NotCycling, 1_000.0, activity = Activity.HIKING)))
        assertEquals("다른 활동 · 1.0 km — 자전거가 아님", statusLine(row(RideStatus.NotCycling, 1_000.0, activity = Activity.OTHER)))
        assertEquals("이미 있는 주행과 겹칩니다", statusLine(row(RideStatus.OverlapsExisting)))
        assertEquals("목록에 같은 주행이 있습니다", statusLine(row(RideStatus.DuplicateInList)))
        assertEquals("너무 짧은 기록입니다", statusLine(row(RideStatus.Unreadable(ImportProblem.TOO_SHORT))))
    }

    @Test
    fun resultTexts() {
        assertEquals("35개를 가져왔습니다", resultText(ImportJobState.Finished(35, 0, false)))
        assertEquals("35개를 가져왔습니다 · 건너뜀 3개", resultText(ImportJobState.Finished(35, 3, false)))
        assertEquals("12개를 가져왔습니다 · 멈춤", resultText(ImportJobState.Finished(12, 0, true)))
        assertEquals("12개를 가져왔습니다 · 건너뜀 1개 · 멈춤", resultText(ImportJobState.Finished(12, 1, true)))
    }

    @Test
    fun headerAndProgressTexts() {
        assertEquals("찾은 주행 38개 · 새로 35개", headerText(38, 35))
        assertEquals("파일 38개에서", filesText(38))
        assertEquals("파일을 읽는 중… 12/38", readingText(12, 38))
        assertEquals("가져오는 중 12/35", importingText(12, 35))
        assertEquals("35개 가져오기", importButtonText(35))
        assertEquals("가져온 기록 3개", importedCountText(3))
        assertEquals("가져온 기록 3개를 지울까요? 이 휴대폰에서 기록한 주행은 남습니다.", deleteConfirmText(3))
        assertEquals("activity_1.gpx에서 가져옴", importedFromText("activity_1.gpx"))
    }

    @Test
    fun limitNotices() {
        assertEquals("묶음.zip: 항목이 너무 많아 처음 2,000개만 읽었습니다", tooManyEntriesText("묶음.zip", 2_000))
        assertEquals("주행이 너무 많아 처음 500개만 보여 줍니다", tooManyRidesText(500))
    }

    @Test
    fun rowDates() {
        assertEquals("2023년 5월 14일 (일) 오전 9:12", rowDateText(T0))
        val evening = OffsetDateTime.parse("2024-12-31T18:05:00+09:00").toInstant().toEpochMilli()
        assertEquals("2024년 12월 31일 (화) 오후 6:05", rowDateText(evening))
        // 23:30 UTC is the next morning in Seoul.
        val utcNight = OffsetDateTime.parse("2023-05-13T23:30:00Z").toInstant().toEpochMilli()
        assertEquals("2023년 5월 14일 (일) 오전 8:30", rowDateText(utcNight))
    }

    @Test
    fun constants() {
        assertEquals("기록 가져오기", IMPORT_GROUP_TITLE)
        assertEquals("기록 가져오기", IMPORT_SCREEN_TITLE)
        assertEquals("지난 기록 가져오기 (GPX)", IMPORT_SETTINGS_ROW)
        assertEquals("Relive·Strava 등에서 받은 GPX·ZIP 파일", IMPORT_SETTINGS_ROW_SUB)
        assertEquals("모두 지우기", IMPORT_DELETE_ALL)
        assertEquals("가져온 기록을 지웠습니다", IMPORT_DELETED)
        assertEquals("전체 선택", IMPORT_SELECT_ALL)
        assertEquals(listOf("멈추기", "요약 보기", "닫기", "다른 파일 고르기"), listOf(IMPORT_STOP, IMPORT_VIEW_SUMMARY, IMPORT_CLOSE, IMPORT_PICK_OTHER))
        assertEquals("가져올 주행을 찾지 못했습니다", IMPORT_NOTHING_FOUND)
        assertEquals("가져오기가 끝난 뒤 다시 보내 주세요", IMPORT_BUSY_SHARE)
        assertEquals(" · 가져옴", IMPORT_CARD_SUFFIX)
    }
}
