package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.video.Aspect
import com.cowork.bikerecoder.video.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 보낼 파일 고르기(스펙 §2.2, §2.3). */
class ShareFilesTest {

    private val mb = 1024L * 1024

    private val v = ShareCandidate("v", MediaKind.VIDEO, Aspect.LANDSCAPE, 200, 12 * mb)
    private val i = ShareCandidate("i", MediaKind.IMAGE, Aspect.PORTRAIT, 150, null)
    private val old = ShareCandidate("old", MediaKind.VIDEO, Aspect.PORTRAIT, 50, 5 * mb)
    private val all = listOf(v, i, old)
    private val lastEndedAt = 100L

    private fun pick(target: ShareTarget, list: List<ShareCandidate> = all) = pickFile(target, list, lastEndedAt)

    @Test
    fun staleMediaIsNotSent() {
        assertEquals(SharePick.File(i), pick(ShareTarget.STORY))
    }

    @Test
    fun generalPrefersVideo() {
        for (t in listOf(ShareTarget.FACEBOOK, ShareTarget.INSTAGRAM, ShareTarget.MORE, ShareTarget.SAVE)) {
            assertEquals(SharePick.File(v), pick(t), t.name)
        }
    }

    @Test
    fun generalFallsBackToImage() {
        assertEquals(SharePick.File(i), pick(ShareTarget.FACEBOOK, listOf(i, old)))
    }

    @Test
    fun storyPrefersPortraitVideoThenImage() {
        val portraitVideo = ShareCandidate("pv", MediaKind.VIDEO, Aspect.PORTRAIT, 300, 8 * mb)
        assertEquals(SharePick.File(portraitVideo), pick(ShareTarget.STORY, all + portraitVideo))
    }

    @Test
    fun storyNeverSendsLandscape() {
        assertEquals(SharePick.MakeImage, pick(ShareTarget.STORY, listOf(v)))
        val landscapeImage = ShareCandidate("li", MediaKind.IMAGE, Aspect.LANDSCAPE, 300, null)
        assertEquals(SharePick.MakeImage, pick(ShareTarget.STORY, listOf(v, landscapeImage)))
    }

    @Test
    fun emailSkipsBigVideo() {
        assertEquals(SharePick.File(i), pick(ShareTarget.EMAIL, listOf(v.copy(sizeBytes = 25 * mb), i)))
        assertEquals(SharePick.File(i), pick(ShareTarget.EMAIL, listOf(v.copy(sizeBytes = null), i)))
        assertEquals(SharePick.File(v), pick(ShareTarget.EMAIL, listOf(v, i)))
        assertEquals(SharePick.File(v.copy(sizeBytes = EMAIL_MAX_BYTES)), pick(ShareTarget.EMAIL, listOf(v.copy(sizeBytes = EMAIL_MAX_BYTES), i)))
    }

    @Test
    fun emailWithOnlyABigVideoMakesImage() {
        assertEquals(SharePick.MakeImage, pick(ShareTarget.EMAIL, listOf(v.copy(sizeBytes = 25 * mb))))
    }

    @Test
    fun nothingFreshMakesImage() {
        for (t in ShareTarget.entries.filter { it != ShareTarget.COPY }) {
            assertEquals(SharePick.MakeImage, pick(t, listOf(old)), t.name)
            assertEquals(SharePick.MakeImage, pick(t, emptyList()), t.name)
        }
    }

    @Test
    fun newestWins() {
        val newer = v.copy(uri = "v2", createdAt = 250)
        assertEquals(SharePick.File(newer), pick(ShareTarget.FACEBOOK, listOf(v, newer)))
        val newerImage = i.copy(uri = "i2", createdAt = 160)
        assertEquals(SharePick.File(newerImage), pick(ShareTarget.STORY, listOf(i, newerImage)))
    }

    @Test
    fun pickLineTexts() {
        assertEquals("영상 · 가로 · 12 MB", pickLine(SharePick.File(v)))
        val small = ShareCandidate("s", MediaKind.VIDEO, Aspect.PORTRAIT, 200, (3.4 * mb).toLong())
        assertEquals("영상 · 세로 · 3.4 MB", pickLine(SharePick.File(small)))
        assertEquals("요약 이미지 · 세로", pickLine(SharePick.File(i)))
        assertEquals("요약 이미지 · 세로 (누르면 만듭니다)", pickLine(SharePick.MakeImage))
        assertEquals("영상 · 가로", pickLine(SharePick.File(v.copy(sizeBytes = null))))
    }

    @Test
    fun megabyteRounding() {
        fun line(bytes: Long) = pickLine(SharePick.File(v.copy(sizeBytes = bytes)))
        assertEquals("영상 · 가로 · 0.0 MB", line(0))
        assertEquals("영상 · 가로 · 10 MB", line((9.96 * mb).toLong()))
        assertEquals("영상 · 가로 · 25 MB", line(25 * mb))
    }

    @Test
    fun fileLinesShowsDifferentStoryAndEmail() {
        val fileV = SharePick.File(v)
        val fileI = SharePick.File(i)
        assertEquals(listOf("보낼 것: 영상 · 가로 · 12 MB"), fileLines(fileV, fileV, fileV))
        assertEquals(
            listOf("보낼 것: 영상 · 가로 · 12 MB", "스토리: 요약 이미지 · 세로 (누르면 만듭니다)"),
            fileLines(fileV, SharePick.MakeImage, fileV),
        )
        assertEquals(
            listOf("보낼 것: 영상 · 가로 · 12 MB", "스토리: 요약 이미지 · 세로", "이메일: 요약 이미지 · 세로"),
            fileLines(fileV, fileI, fileI),
        )
    }
}
