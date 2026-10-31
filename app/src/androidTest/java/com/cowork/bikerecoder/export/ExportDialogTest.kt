package com.cowork.bikerecoder.export

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The dialog after saving (spec §4.2). Buttons call back only; no share target or viewer is ever started. */
class ExportDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val saved = ExportResult.Saved(
        uri = "content://media/external_primary/downloads/1234",
        displayName = "bikeRecoder_20261031_0705 (1).gpx",
        mime = ExportFormat.GPX.mime,
    )

    private var shares = 0
    private var opens = 0
    private var closes = 0

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFCFD8DC)))
                ExportDialog(saved, onShare = { shares++ }, onOpen = { opens++ }, onDismiss = { closes++ })
            }
        }
    }

    @Test
    fun showsPathAndButtons() {
        show()

        composeRule.onNodeWithText("내보냈습니다").assertIsDisplayed()
        composeRule.onNodeWithText("Download/bikeRecoder/bikeRecoder_20261031_0705 (1).gpx").assertIsDisplayed()
        val share = composeRule.onNodeWithText("공유").assertIsDisplayed()
        val open = composeRule.onNodeWithText("파일 열기").assertIsDisplayed()
        val close = composeRule.onNodeWithText("닫기").assertIsDisplayed()
        // [공유] [파일 열기] [닫기] in reading order (one row, or wrapped onto the next).
        fun before(a: DpRect, b: DpRect) =
            (a.top == b.top && a.right <= b.left) || a.bottom <= b.top
        assertTrue(before(share.getBoundsInRoot(), open.getBoundsInRoot()))
        assertTrue(before(open.getBoundsInRoot(), close.getBoundsInRoot()))

        share.performClick()
        open.performClick()
        close.performClick()
        assertEquals(Triple(1, 1, 1), Triple(shares, opens, closes))
    }

    /** [공유] is the chooser over ACTION_SEND with the file and a read grant; [파일 열기] is ACTION_VIEW of (URI, MIME). */
    @Test
    fun intentsCarryTheFile() {
        val chooser = ExportIntents.share(saved)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        send!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/gpx+xml", send.type)
        assertEquals(saved.uri.toUri(), IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val view = ExportIntents.view(saved)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(saved.uri.toUri(), view.data)
        assertEquals("application/gpx+xml", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** Download/bikeRecoderTest/v170-export.png: the saved dialog. */
    @Test
    fun screenshot() {
        show()
        composeRule.onNodeWithText("내보냈습니다").assertIsDisplayed()
        screenshot("v170-export")
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
        const val TAG = "ExportDialogTest"
    }
}
