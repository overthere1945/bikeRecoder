package com.cowork.bikerecoder.ui.importing

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.cowork.bikerecoder.core.importing.Activity
import com.cowork.bikerecoder.core.importing.ImportProblem
import com.cowork.bikerecoder.core.importing.RideStatus
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.importing.ImportJobState
import com.cowork.bikerecoder.importing.RideKey
import com.cowork.bikerecoder.importing.ScanRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The "기록 가져오기" screen (V1.6.0 spec §1.2, §1.3) on fixed states: the review list with every row status, the
 * button, progress with [멈추기], the result, and nothing found.
 */
class ImportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    /** 2023-05-14 09:12 KST. */
    private val t0 = 1_684_023_120_000L
    private val day = 86_400_000L

    private fun route(shift: Double) = listOf(
        GeoPoint(37.50 + shift, 127.00),
        GeoPoint(37.52 + shift, 127.03),
        GeoPoint(37.51 + shift, 127.08),
        GeoPoint(37.54 + shift, 127.10),
    )

    private val newRide = ScanRow(
        RideKey(0, 0), "Relive_0514.gpx", t0, t0 + 6_900_000, 32_100.0, 6_300_000, Activity.CYCLING, RideStatus.New,
        route(0.0),
    )
    private val running = ScanRow(
        RideKey(1, 0), "run.gpx", t0 - day, t0 - day + 2_400_000, 6_200.0, 2_100_000, Activity.RUNNING,
        RideStatus.NotCycling, route(0.01),
    )
    private val overlap = ScanRow(
        RideKey(2, 0), "today.gpx", t0 - 2 * day, t0 - 2 * day + 3_600_000, 15_000.0, 3_000_000, Activity.CYCLING,
        RideStatus.OverlapsExisting, route(0.02),
    )
    private val duplicate = ScanRow(
        RideKey(3, 0), "copy.gpx", t0 - 3 * day, t0 - 3 * day + 3_600_000, 15_000.0, 3_000_000, Activity.CYCLING,
        RideStatus.DuplicateInList, route(0.03),
    )
    private val unreadable = ScanRow(
        RideKey(4, 0), "notes.bin", null, null, 0.0, 0, null, RideStatus.Unreadable(ImportProblem.NOT_GPX), emptyList(),
    )
    private val rows = listOf(newRide, running, overlap, duplicate, unreadable)

    private fun review(checked: Set<RideKey> = setOf(newRide.key), notices: List<String> = emptyList()) =
        ImportUiState.Review(rows, checked, notices, header = "찾은 주행 4개 · 새로 1개", files = "파일 5개에서")

    private fun show(state: ImportUiState) {
        composeRule.setContent {
            MaterialTheme {
                ImportContent(
                    state = state,
                    onBack = { calls += "back" },
                    onToggle = { calls += "toggle ${it.sourceIndex}" },
                    onToggleAll = { calls += "toggleAll" },
                    onImport = { calls += "import" },
                    onStop = { calls += "stop" },
                    onOpenSummary = { calls += "summary" },
                    onClose = { calls += "close" },
                    onPickAgain = { calls += "pickAgain" },
                )
            }
        }
    }

    @Test
    fun reviewShowsRowsStatusesAndCount() {
        show(review())

        composeRule.onNodeWithText("기록 가져오기").assertIsDisplayed()
        composeRule.onNodeWithText("찾은 주행 4개 · 새로 1개").assertIsDisplayed()
        composeRule.onNodeWithText("파일 5개에서").assertIsDisplayed()
        composeRule.onNodeWithText("2023년 5월 14일 (일) 오전 9:12").assertIsDisplayed()
        composeRule.onNodeWithText("32.1 km · 1시간 45분").assertIsDisplayed()
        composeRule.onNodeWithText("달리기 · 6.2 km — 자전거가 아님").assertIsDisplayed()
        composeRule.onNodeWithText("이미 있는 주행과 겹칩니다").assertIsDisplayed()
        composeRule.onNodeWithText("목록에 같은 주행이 있습니다").assertIsDisplayed()
        composeRule.onNodeWithText("notes.bin").assertIsDisplayed()
        composeRule.onNodeWithText("GPX 파일이 아닙니다").assertIsDisplayed()

        composeRule.onNodeWithTag(ImportTags.row(newRide.key)).assertIsOn().assertIsEnabled()
        composeRule.onNodeWithTag(ImportTags.row(running.key)).assertIsOff().assertIsEnabled()
        composeRule.onNodeWithTag(ImportTags.row(overlap.key)).assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithTag(ImportTags.row(duplicate.key)).assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithTag(ImportTags.row(unreadable.key)).assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithTag(ImportTags.SELECT_ALL).assertIsOff()
        composeRule.onNodeWithText("전체 선택").assertIsDisplayed()

        composeRule.onNodeWithTag(ImportTags.IMPORT).assertTextEquals("1개 가져오기").assertIsEnabled()

        composeRule.onNodeWithTag(ImportTags.row(running.key)).performClick()
        composeRule.onNodeWithTag(ImportTags.SELECT_ALL).performClick()
        composeRule.onNodeWithTag(ImportTags.IMPORT).performClick()
        assertEquals(listOf("toggle 1", "toggleAll", "import"), calls)
    }

    @Test
    fun buttonDisabledWhenNoneChecked() {
        show(review(checked = emptySet(), notices = listOf("주행이 너무 많아 처음 500개만 보여 줍니다")))

        composeRule.onNodeWithText("주행이 너무 많아 처음 500개만 보여 줍니다").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.IMPORT).assertTextEquals("0개 가져오기").assertIsNotEnabled()
    }

    @Test
    fun progressAndStop() {
        show(ImportUiState.Job(ImportJobState.Importing(12, 35)))

        composeRule.onNodeWithText("가져오는 중 12/35").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.PROGRESS).assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.STOP).assertTextEquals("멈추기").performClick()
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun doneShowsResultAndOpensSummary() {
        show(ImportUiState.Job(ImportJobState.Finished(imported = 35, skipped = 3, stopped = false)))

        composeRule.onNodeWithText("35개를 가져왔습니다 · 건너뜀 3개").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.CLOSE).assertTextEquals("닫기").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.VIEW_SUMMARY).assertTextEquals("요약 보기").performClick()
        composeRule.onNodeWithTag(ImportTags.CLOSE).performClick()
        composeRule.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(listOf("summary", "close", "back"), calls)
    }

    @Test
    fun emptyShowsPickAgain() {
        show(
            ImportUiState.Empty(
                notices = listOf("묶음.zip: 항목이 너무 많아 처음 2,000개만 읽었습니다"),
                unreadable = listOf("notes.txt: GPX 파일이 아닙니다", "묶음.zip / a.fit.gz: GPX 파일이 아닙니다"),
            ),
        )

        composeRule.onNodeWithText("가져올 주행을 찾지 못했습니다").assertIsDisplayed()
        composeRule.onNodeWithText("묶음.zip: 항목이 너무 많아 처음 2,000개만 읽었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("notes.txt: GPX 파일이 아닙니다").assertIsDisplayed()
        composeRule.onNodeWithText("묶음.zip / a.fit.gz: GPX 파일이 아닙니다").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.PICK_AGAIN).assertTextEquals("다른 파일 고르기").performClick()
        assertEquals(listOf("pickAgain"), calls)
    }

    @Test
    fun readingShowsProgress() {
        show(ImportUiState.Reading(12, 38))

        composeRule.onNodeWithText("파일을 읽는 중… 12/38").assertIsDisplayed()
        composeRule.onNodeWithTag(ImportTags.PROGRESS).assertIsDisplayed()
    }

    /** Download/bikeRecoderTest/v160-import-review.png. */
    @Test
    fun screenshotReview() {
        show(review(checked = setOf(newRide.key, running.key)))
        screenshot("v160-import-review")
    }

    /** Download/bikeRecoderTest/v160-import-progress.png. */
    @Test
    fun screenshotProgress() {
        show(ImportUiState.Job(ImportJobState.Importing(12, 35)))
        screenshot("v160-import-progress")
    }

    /** Download/bikeRecoderTest/v160-import-done.png. */
    @Test
    fun screenshotDone() {
        show(ImportUiState.Job(ImportJobState.Finished(imported = 35, skipped = 3, stopped = false)))
        screenshot("v160-import-done")
    }

    /** The whole screen into Download/bikeRecoderTest/[name].png (an older file of the name is deleted first). */
    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val resolver = instrumentation.targetContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/bikeRecoderTest/"
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("$name.png", relative),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot $name → $uri")
    }

    private companion object {
        const val TAG = "ImportScreenTest"
    }
}
