package com.cowork.bikerecoder.core.activity

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HikeAnnouncerTest {

    private val min = 60_000L
    private val start = 1_000_000_000L

    private fun at(minutes: Long, seconds: Long = 0) = start + minutes * min + seconds * 1_000

    /** 시작 후 [from]분부터 [to]분까지 1초마다 [HikeAnnouncer.due]를 부르며 나온 안내를 모은다. */
    private fun HikeAnnouncer.run(from: Long, to: Long): List<Pair<Long, HikeCue>> {
        val out = mutableListOf<Pair<Long, HikeCue>>()
        var t = at(from)
        while (t <= at(to)) {
            due(t)?.let { out += (t - start) / 1_000 to it }
            t += 1_000
        }
        return out
    }

    @Test
    fun everyThirtyMinutes() {
        val a = HikeAnnouncer(start)
        assertNull(a.due(at(10)))
        assertNull(a.due(at(29, 59)))
        assertEquals(HikeCue.Periodic(30 * min), a.due(at(30)))
        assertNull(a.due(at(30, 1)))
        assertNull(a.due(at(45)))
        assertEquals(HikeCue.Periodic(60 * min), a.due(at(60, 5)))
        assertNull(a.due(at(60, 6)))
    }

    @Test
    fun onlyLastSkippedSlotIsAnnounced() {
        val a = HikeAnnouncer(start)
        assertNull(a.due(at(10)))
        assertEquals(HikeCue.Periodic(90 * min), a.due(at(100)))
        assertNull(a.due(at(101)))
        assertEquals(HikeCue.Periodic(120 * min), a.due(at(120)))
    }

    @Test
    fun sunsetStagesOnce() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(300), at(0))
        val sunset = a.run(0, 305).filter { it.second is HikeCue.Sunset }
        assertEquals(
            listOf(
                180 * 60L to HikeCue.Sunset(SunsetStage.H2),
                240 * 60L to HikeCue.Sunset(SunsetStage.H1),
                270 * 60L to HikeCue.Sunset(SunsetStage.M30),
                300 * 60L to HikeCue.Sunset(SunsetStage.NOW),
            ),
            sunset,
        )
    }

    @Test
    fun startedFortyMinutesBeforeSunset() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(40), at(0))
        val cues = a.run(0, 45).map { it.second }.filterIsInstance<HikeCue.Sunset>()
        assertEquals(listOf(SunsetStage.M30, SunsetStage.NOW), cues.map { it.stage })
    }

    @Test
    fun startedAfterSunset() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(-10), at(0))
        assertEquals(emptyList(), a.run(0, 200).filter { it.second is HikeCue.Sunset })
    }

    @Test
    fun noSunsetNoSunsetCues() {
        val a = HikeAnnouncer(start)
        a.setSunset(null, at(0))
        assertEquals(emptyList(), a.run(0, 200).filter { it.second is HikeCue.Sunset })
    }

    @Test
    fun changedSunsetDoesNotRepeatDoneStages() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(130), at(0))
        assertEquals(HikeCue.Sunset(SunsetStage.H2), a.due(at(10)))
        a.setSunset(at(125), at(12))
        val stages = a.run(12, 130).map { it.second }.filterIsInstance<HikeCue.Sunset>().map { it.stage }
        assertEquals(listOf(SunsetStage.H1, SunsetStage.M30, SunsetStage.NOW), stages)
    }

    @Test
    fun mergesWithinTwoMinutes() {
        // M30은 31분, 30분 차례는 그 1분 앞이다. NOW는 61분, 60분 차례는 그 1분 앞이다.
        val a = HikeAnnouncer(start)
        a.setSunset(at(61), at(29))
        assertEquals(
            listOf(31 * 60L to HikeCue.Sunset(SunsetStage.M30), 61 * 60L to HikeCue.Sunset(SunsetStage.NOW)),
            a.run(29, 62),
        )
    }

    @Test
    fun mergesSunsetFirst() {
        // M30은 29분이고 30분 차례는 그 1분 뒤다. 그 30분 안내는 한 것으로 친다.
        val a = HikeAnnouncer(start)
        a.setSunset(at(59), at(0))
        assertEquals(listOf(29 * 60L to HikeCue.Sunset(SunsetStage.M30)), a.run(0, 35))
    }

    @Test
    fun periodicStaysWhenSunsetIsFarAway() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(100), at(0))
        // H1 40분, M30 70분. 30·60분 차례와 2분보다 멀다.
        assertEquals(
            listOf(
                30 * 60L to HikeCue.Periodic(30 * min),
                40 * 60L to HikeCue.Sunset(SunsetStage.H1),
                60 * 60L to HikeCue.Periodic(60 * min),
                70 * 60L to HikeCue.Sunset(SunsetStage.M30),
            ),
            a.run(0, 75),
        )
    }

    @Test
    fun sunsetComesBeforePeriodicWhenBothArePending() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(45), at(0))
        // 첫 호출이 50분이라 M30(15분)·NOW(45분)·30분 차례가 모두 밀려 있다. 일몰 안내가 먼저, 그 단계는 가장 늦은 것 하나.
        assertEquals(HikeCue.Sunset(SunsetStage.NOW), a.due(at(50)))
        assertEquals(HikeCue.Periodic(30 * min), a.due(at(50, 1)))
        assertNull(a.due(at(50, 2)))
    }

    @Test
    fun noSunsetNoMerging() {
        val a = HikeAnnouncer(start)
        a.setSunset(at(61), at(0))
        a.setSunset(null, at(0))
        assertEquals(HikeCue.Periodic(30 * min), a.due(at(30)))
    }

    @Test
    fun announcesWhilePaused() {
        // 이 클래스는 일시정지 여부를 모른다. 호출만 계속 받으면 제때 나온다.
        val a = HikeAnnouncer(start)
        val out = a.run(0, 61).map { it.first }
        assertEquals(listOf(30 * 60L, 60 * 60L), out)
    }
}
