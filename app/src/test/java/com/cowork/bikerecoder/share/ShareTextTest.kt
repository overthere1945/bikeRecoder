package com.cowork.bikerecoder.share

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 활동 공유 글(스펙 §3): 자동 글 네 줄, 해시태그 정리, 감정 칩, 저장 표시, 메일 제목. */
class ShareTextTest {

    private val oct25 = LocalDate.of(2026, 10, 25)

    private fun facts(
        distanceM: Double = 32_140.0,
        movingTimeMs: Long = 6_300_000,
        ascentM: Double = 123.0,
        region: String? = "서울 강서구",
    ) = ShareFacts("저녁 라이딩 · 서울 강서구", oct25, oct25, distanceM, movingTimeMs, ascentM, region)

    private fun auto(f: ShareFacts = facts(), tags: String = DEFAULT_SHARE_HASHTAGS) = autoShareText(f, tags)

    @Test
    fun weekdaysMatchTheCalendar() {
        assertEquals(DayOfWeek.SUNDAY, oct25.dayOfWeek)
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2026, 10, 27).dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2026, 10, 31).dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 11, 2).dayOfWeek)
        assertEquals(DayOfWeek.THURSDAY, LocalDate.of(2026, 12, 31).dayOfWeek)
        assertEquals(DayOfWeek.FRIDAY, LocalDate.of(2027, 1, 1).dayOfWeek)
    }

    @Test
    fun oneDay() {
        assertEquals("2026년 10월 25일 (일)", shareDateLine(oct25, oct25))
    }

    @Test
    fun sameMonth() {
        assertEquals("2026년 10월 25일 (일) ~ 27일 (화) · 3일", shareDateLine(oct25, LocalDate.of(2026, 10, 27)))
    }

    @Test
    fun monthChanges() {
        assertEquals(
            "2026년 10월 31일 (토) ~ 11월 2일 (월) · 3일",
            shareDateLine(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2)),
        )
    }

    @Test
    fun yearChanges() {
        assertEquals(
            "2026년 12월 31일 (목) ~ 2027년 1월 1일 (금) · 2일",
            shareDateLine(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)),
        )
    }

    @Test
    fun autoTextFourLines() {
        assertEquals(
            "저녁 라이딩 · 서울 강서구\n" +
                "2026년 10월 25일 (일)\n" +
                "32.1 km · 1시간 45분 · 상승 고도 123 m · 평균 18.4 km/h\n" +
                "#자전거 #라이딩 #서울 #강서구",
            auto(),
        )
    }

    @Test
    fun ascentUnderOneMeterIsLeftOut() {
        val numbers = auto(facts(ascentM = 0.6)).lines()[2]
        assertFalse("상승 고도" in numbers)
        assertTrue("평균" in numbers)
    }

    @Test
    fun noMovingTimeHasNoAverage() {
        assertFalse("평균" in auto(facts(movingTimeMs = 0)).lines()[2])
    }

    @Test
    fun noTagsNoFourthLine() {
        assertEquals(3, auto(facts(region = null), tags = "").lines().size)
    }

    @Test
    fun cleanTagStripsSymbols() {
        assertEquals("#한강", cleanTag("한강!"))
        assertEquals("#자출", cleanTag("#자출"))
        assertNull(cleanTag("!!"))
        assertNull(cleanTag("#"))
        assertEquals("#a_1", cleanTag("a_1"))
    }

    @Test
    fun regionTagsSplit() {
        assertEquals(listOf("#서울", "#강서구"), regionTags("서울 강서구"))
        assertEquals(emptyList<String>(), regionTags(null))
    }

    @Test
    fun duplicatesKeepFirst() {
        assertEquals("#서울 #라이딩 #강서구", auto(tags = "#서울 #라이딩").lines()[3])
    }

    @Test
    fun normalizeHashtagsInput() {
        assertEquals("#자출 #한강", normalizeHashtags("자출 한강"))
        assertEquals("#자출", normalizeHashtags("#자출  #자출"))
        assertEquals("", normalizeHashtags(""))
        assertEquals("", normalizeHashtags("  "))
    }

    @Test
    fun toggleMoodAddsToLastTagLine() {
        val text = auto()
        val on = toggleMood(text, Mood.SANGKWAE)
        assertEquals("#자전거 #라이딩 #서울 #강서구 #상쾌", on.lines()[3])
        assertEquals(4, on.lines().size)
        assertTrue(moodOn(on, Mood.SANGKWAE))
        assertFalse(moodOn(text, Mood.SANGKWAE))
    }

    @Test
    fun toggleMoodRemoves() {
        val text = auto()
        assertEquals(text, toggleMood(toggleMood(text, Mood.SANGKWAE), Mood.SANGKWAE))
    }

    @Test
    fun toggleMoodRemovesInTheMiddleAndAtTheStart() {
        assertEquals("a #x", toggleMood("a #상쾌 #x", Mood.SANGKWAE))
        assertEquals("#x", toggleMood("#상쾌 #x", Mood.SANGKWAE))
    }

    @Test
    fun moodWordBoundary() {
        assertFalse(moodOn("달렸다 #상쾌함", Mood.SANGKWAE))
        assertTrue(moodOn("달렸다 #상쾌", Mood.SANGKWAE))
    }

    @Test
    fun moodWithoutTagLine() {
        val on = toggleMood("그냥 한 줄", Mood.PUDEUT)
        assertEquals("그냥 한 줄\n#뿌듯", on)
        assertEquals("그냥 한 줄", toggleMood(on, Mood.PUDEUT))
    }

    @Test
    fun userDeletedMoodTag() {
        val on = toggleMood(auto(), Mood.BI)
        assertFalse(moodOn(on.replace("#비", ""), Mood.BI))
    }

    @Test
    fun moodTags() {
        assertEquals("#상쾌", Mood.SANGKWAE.tag)
        assertEquals(8, Mood.entries.size)
    }

    @Test
    fun shareStampFormat() {
        assertEquals("3:32140", shareStamp(3, 32_140.4))
    }

    @Test
    fun emailSubjectCutsAtCodePoints() {
        val subject = emailSubject("😊".repeat(100) + "\n둘째 줄")
        assertEquals(80, subject.codePointCount(0, subject.length))
        assertEquals("😊".repeat(80), subject)
    }

    @Test
    fun emailSubjectIsTheFirstLine() {
        assertEquals("저녁 라이딩", emailSubject("저녁 라이딩\n2026년"))
    }
}
