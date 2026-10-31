package com.cowork.bikerecoder.importing

import com.cowork.bikerecoder.core.format.RideFormat
import com.cowork.bikerecoder.core.importing.Activity
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.ui.feed.clockText
import com.cowork.bikerecoder.ui.feed.groupedInt
import com.cowork.bikerecoder.ui.feed.kmText
import com.cowork.bikerecoder.ui.history.cardDateText
import java.time.Instant
import java.time.ZoneId

// The copy of the GPX import (V1.6.0 spec §5), all in one place.

/** The settings group, and the import screen's title. */
const val IMPORT_GROUP_TITLE = "기록 가져오기"
const val IMPORT_SCREEN_TITLE = "기록 가져오기"
const val IMPORT_SETTINGS_ROW = "지난 기록 가져오기 (GPX)"
const val IMPORT_SETTINGS_ROW_SUB = "Relive·Strava 등에서 받은 GPX·ZIP 파일"
const val IMPORT_DELETE_ALL = "모두 지우기"
const val IMPORT_DELETE = "지우기"
const val IMPORT_CANCEL = "취소"
const val IMPORT_DELETED = "가져온 기록을 지웠습니다"
const val IMPORT_SELECT_ALL = "전체 선택"
const val IMPORT_STOP = "멈추기"
const val IMPORT_VIEW_SUMMARY = "요약 보기"
const val IMPORT_CLOSE = "닫기"
const val IMPORT_PICK_OTHER = "다른 파일 고르기"
const val IMPORT_NOTHING_FOUND = "가져올 주행을 찾지 못했습니다"
const val IMPORT_OVERLAPS_EXISTING = "이미 있는 주행과 겹칩니다"
const val IMPORT_DUPLICATE_IN_LIST = "목록에 같은 주행이 있습니다"
const val IMPORT_NOT_CYCLING_SUFFIX = " — 자전거가 아님"
const val IMPORT_BUSY_SHARE = "가져오기가 끝난 뒤 다시 보내 주세요"

/** Appended to an imported trip's card date line. */
const val IMPORT_CARD_SUFFIX = " · 가져옴"

const val PROBLEM_NOT_GPX = "GPX 파일이 아닙니다"
const val PROBLEM_NO_TIME = "시간이 없는 기록입니다"
const val PROBLEM_TOO_SHORT = "너무 짧은 기록입니다"
const val PROBLEM_TOO_LARGE = "너무 큰 파일입니다"
const val PROBLEM_UNREADABLE = "파일을 읽을 수 없습니다"

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** The reason shown on an unreadable row (in the error colour). */
fun problemText(p: ImportProblem): String = when (p) {
    ImportProblem.NOT_GPX -> PROBLEM_NOT_GPX
    ImportProblem.NO_TIME -> PROBLEM_NO_TIME
    ImportProblem.TOO_SHORT -> PROBLEM_TOO_SHORT
    ImportProblem.TOO_LARGE -> PROBLEM_TOO_LARGE
    ImportProblem.UNREADABLE -> PROBLEM_UNREADABLE
}

/** A file that could not be read, under "가져올 주행을 찾지 못했습니다": "notes.txt: GPX 파일이 아닙니다". */
fun unreadableText(fileName: String, problem: ImportProblem): String = "$fileName: ${problemText(problem)}"

/** "달리기", "걷기", "등산", else "다른 활동". */
fun activityText(a: Activity?): String = when (a) {
    Activity.RUNNING -> "달리기"
    Activity.WALKING -> "걷기"
    Activity.HIKING -> "등산"
    else -> "다른 활동"
}

/**
 * The second line of a row (spec §1.2): a new ride "32.1 km · 1시간 45분" (moving time), not cycling
 * "달리기 · 6.2 km — 자전거가 아님", the overlap texts, or the reason it cannot be read.
 */
fun statusLine(row: ScanRow): String = when (val s = row.status) {
    RideStatus.New -> "${kmText(row.distanceM)} · ${RideFormat.duration(row.movingTimeMs)}"
    RideStatus.NotCycling -> "${activityText(row.activity)} · ${kmText(row.distanceM)}$IMPORT_NOT_CYCLING_SUFFIX"
    RideStatus.OverlapsExisting -> IMPORT_OVERLAPS_EXISTING
    RideStatus.DuplicateInList -> IMPORT_DUPLICATE_IN_LIST
    is RideStatus.Unreadable -> problemText(s.problem)
}

/** The first line of a readable row: "2023년 5월 14일 (일) 오전 9:12" in [zone]. */
fun rowDateText(startedAt: Long, zone: ZoneId = SEOUL): String {
    val date = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
    return "${date.year}년 ${cardDateText(date)} ${clockText(startedAt, zone)}"
}

/** "찾은 주행 38개 · 새로 35개". */
fun headerText(found: Int, new: Int): String = "찾은 주행 ${groupedInt(found)}개 · 새로 ${groupedInt(new)}개"

/** "파일 38개에서", under the header. */
fun filesText(files: Int): String = "파일 ${groupedInt(files)}개에서"

/** "파일을 읽는 중… 12/38". */
fun readingText(done: Int, total: Int): String = "파일을 읽는 중… ${groupedInt(done)}/${groupedInt(total)}"

/** "가져오는 중 12/35" (the import screen and the settings row). */
fun importingText(done: Int, total: Int): String = "가져오는 중 ${groupedInt(done)}/${groupedInt(total)}"

/** "N개 가져오기". */
fun importButtonText(count: Int): String = "${groupedInt(count)}개 가져오기"

/** "35개를 가져왔습니다", then " · 건너뜀 3개" when any was skipped, then " · 멈춤" when stopped. */
fun resultText(s: ImportJobState.Finished): String =
    "${groupedInt(s.imported)}개를 가져왔습니다" +
        (if (s.skipped > 0) " · 건너뜀 ${groupedInt(s.skipped)}개" else "") +
        (if (s.stopped) " · 멈춤" else "")

/** "가져온 기록 N개" (the settings row). */
fun importedCountText(count: Int): String = "가져온 기록 ${groupedInt(count)}개"

/** "가져온 기록 N개를 지울까요? 이 휴대폰에서 기록한 주행은 남습니다." */
fun deleteConfirmText(count: Int): String =
    "가져온 기록 ${groupedInt(count)}개를 지울까요? 이 휴대폰에서 기록한 주행은 남습니다."

/** "묶음.zip: 항목이 너무 많아 처음 2,000개만 읽었습니다". */
fun tooManyEntriesText(name: String, limit: Int): String = "$name: 항목이 너무 많아 처음 ${groupedInt(limit)}개만 읽었습니다"

/** "주행이 너무 많아 처음 500개만 보여 줍니다". */
fun tooManyRidesText(limit: Int): String = "주행이 너무 많아 처음 ${groupedInt(limit)}개만 보여 줍니다"

/** "{파일 이름}에서 가져옴" (the trip screen). */
fun importedFromText(fileName: String): String = "${fileName}에서 가져옴"
