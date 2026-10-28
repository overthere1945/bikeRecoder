package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.recording.north
import java.time.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoTimelineTest {

    private fun cue(id: Long, moveMs: Long) = PhotoCue(mediaId = id, moveMs = moveMs, takenAt = moveMs)

    private fun List<FramePlan>.count(kind: SceneKind) = count { it.kind == kind }

    @Test
    fun frameCountPerLength() {
        val timeline = simpleTimeline(600)
        val normal = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), emptyList())
        assertEquals(60 * 24, normal.size)
        assertEquals(normal.indices.toList(), normal.map { it.index })
        assertEquals(72, normal.count(SceneKind.INTRO_OVERVIEW) + normal.count(SceneKind.INTRO_DESCENT))
        assertEquals(29, normal.count(SceneKind.INTRO_OVERVIEW))
        assertEquals(43, normal.count(SceneKind.INTRO_DESCENT))
        assertEquals(96, normal.count(SceneKind.OUTRO))
        assertEquals(30 * 24, VideoTimeline.plan(timeline, emptyList(), VideoOptions(length = VideoLength.SHORT), emptyList()).size)
        assertEquals(120 * 24, VideoTimeline.plan(timeline, emptyList(), VideoOptions(length = VideoLength.LONG), emptyList()).size)
    }

    @Test
    fun photosShortenRide() {
        val timeline = simpleTimeline(600)
        val photos = List(8) { cue(it.toLong(), 30_000L + it * 70_000L) }
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(), emptyList())
        assertEquals(1440, frames.size)
        assertEquals(33 * 24, frames.count(SceneKind.RIDE))
        assertEquals(8 * 60, frames.count(SceneKind.PHOTO))
    }

    @Test
    fun photosOffIgnoresPhotos() {
        val timeline = simpleTimeline(600)
        val photos = List(8) { cue(it.toLong(), 30_000L + it * 70_000L) }
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(photos = false), emptyList())
        assertEquals(0, frames.count(SceneKind.PHOTO))
        assertEquals(53 * 24, frames.count(SceneKind.RIDE))
    }

    @Test
    fun shortMoveIsNotStretched() {
        val frames = VideoTimeline.plan(simpleTimeline(20), emptyList(), VideoOptions(), emptyList())
        assertEquals((3 + 20 + 4) * 24, frames.size)
        assertEquals(20 * 24, frames.count(SceneKind.RIDE))
    }

    @Test
    fun speedProportional() {
        // 앞 10분은 초당 1 m, 뒤 10분은 초당 5 m.
        val slow = straightPoints(0, 601, stepM = 1.0)
        val fast = straightPoints(601_000, 600, startM = 605.0, stepM = 5.0)
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to slow + fast), SEOUL)
        assertEquals(1_200_000L, timeline.totalMoveMs)

        val ride = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), emptyList()).filter { it.kind == SceneKind.RIDE }
        assertEquals(0L, ride.first().moveMs)
        val half = ride[ride.size / 2]
        assertEquals(timeline.totalMoveMs / 2, half.moveMs)
        // 영상 절반 = 이동 시간 절반 = 600 m 지점(거리 절반 1,800 m가 아님)
        assertEquals(north(600.0).lat, timeline.at(half.moveMs).point.lat, 1e-9)
        assertTrue(ride.zipWithNext().all { (a, b) -> b.moveMs > a.moveMs })
    }

    @Test
    fun choosePhotosEvenly() {
        val cues = List(20) { cue(it.toLong(), it * 30_000L + 1_000L) }.shuffled(kotlin.random.Random(7))
        val chosen = VideoTimeline.choosePhotos(cues, 8, 600_000)
        assertEquals(8, chosen.size)
        assertEquals(chosen.sortedBy { it.moveMs }, chosen)
        // 칸(75초)마다 하나씩, 칸 가운데(37.5초 + 75초·k)에 가장 가까운 것
        assertEquals(
            listOf(31_000L, 121_000L, 181_000L, 271_000L, 331_000L, 421_000L, 481_000L, 571_000L),
            chosen.map { it.moveMs },
        )
    }

    @Test
    fun choosePhotosKeepsAllUnderCap() {
        val cues = listOf(cue(1, 500_000), cue(2, 10_000), cue(3, 200_000))
        assertEquals(listOf(2L, 3L, 1L), VideoTimeline.choosePhotos(cues, 8, 600_000).map { it.mediaId })
    }

    @Test
    fun choosePhotosFillsCapWhenClustered() {
        val cues = List(20) { cue(it.toLong(), 300_000L + it * 100L) }
        val chosen = VideoTimeline.choosePhotos(cues, 8, 600_000)
        assertEquals(8, chosen.size)
        assertEquals(8, chosen.map { it.mediaId }.toSet().size)
        assertEquals(chosen.sortedBy { it.moveMs }, chosen)
    }

    @Test
    fun samePlacePhotosConsecutive() {
        val timeline = simpleTimeline(600)
        val photos = listOf(cue(1, 300_000), cue(2, 300_500), cue(3, 300_900), cue(4, 302_000))
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(), emptyList())
        val firstPhoto = frames.indexOfFirst { it.kind == SceneKind.PHOTO }
        val group = frames.subList(firstPhoto, firstPhoto + 180)
        assertTrue(group.all { it.kind == SceneKind.PHOTO && it.moveMs == 300_000L })
        assertEquals(listOf(1L, 2L, 3L), group.map { it.photo!!.mediaId }.distinct())
        // 2초 떨어진 사진은 그 사이 주행을 지나 따로 나온다.
        assertEquals(SceneKind.RIDE, frames[firstPhoto + 180].kind)
        val fourth = frames.filter { it.photo?.mediaId == 4L }
        assertEquals(60, fourth.size)
        assertEquals(302_000L, fourth.first().moveMs)
        val before = frames[fourth.first().index - 1]
        assertEquals(SceneKind.RIDE, before.kind)
        assertTrue(before.moveMs < 302_000L)
        assertTrue(frames[fourth.last().index + 1].moveMs >= 302_000L)
    }

    @Test
    fun multiDayCaption() {
        val morning = 1_792_278_000_000L // 2026-10-18 08:00 KST
        val timeline = RideTimelineBuilder.build(
            listOf(
                segment(1, 0, LocalDate.of(2026, 10, 17)) to straightPoints(0, 301),
                segment(2, morning, LocalDate.of(2026, 10, 18)) to straightPoints(morning, 301, startM = 2_000.0),
            ),
            SEOUL,
        )
        val dayStart = timeline.dayStarts.single().first
        assertEquals(300_000L, dayStart)
        val frames = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), timeline.dayStarts)
        val captioned = frames.filter { it.caption != null }
        assertEquals(36, captioned.size)
        assertTrue(captioned.all { it.kind == SceneKind.RIDE && it.caption == "2일째 · 10월 18일" })
        assertEquals((captioned.first().index until captioned.first().index + 36).toList(), captioned.map { it.index })
        assertTrue(captioned.first().moveMs >= dayStart)
        assertTrue(frames[captioned.first().index - 1].moveMs < dayStart)
    }

    @Test
    fun onlyTheOutroHoldReusesTheMap() {
        val timeline = simpleTimeline(600)
        val frames = VideoTimeline.plan(timeline, listOf(cue(1, 300_000)), VideoOptions(), emptyList())
        val overview = frames.filter { it.kind == SceneKind.INTRO_OVERVIEW }
        assertEquals(29, overview.size)
        // The camera turns over the overview, orbits during a photo and rises at the start of the outro: a new map every frame there.
        assertTrue(overview.all { !it.reuseMap })
        assertTrue(overview.all { it.moveMs == 0L })
        assertTrue(frames.filter { it.kind == SceneKind.INTRO_DESCENT }.all { !it.reuseMap })
        assertTrue(frames.filter { it.kind == SceneKind.RIDE }.all { !it.reuseMap })
        val photo = frames.filter { it.kind == SceneKind.PHOTO }
        assertEquals(60, photo.size)
        assertTrue(photo.all { !it.reuseMap })
        val outro = frames.filter { it.kind == SceneKind.OUTRO }
        assertEquals(List(36) { false } + List(60) { true }, outro.map { it.reuseMap })
        assertTrue(outro.all { it.moveMs == timeline.totalMoveMs })
        assertEquals(0f, overview.first().sceneProgress)
        assertEquals(1f, outro.last().sceneProgress)
    }

    @Test
    fun fadeOnLastSecond() {
        val frames = VideoTimeline.plan(simpleTimeline(600), emptyList(), VideoOptions(), emptyList())
        val last = frames.takeLast(24)
        assertEquals(0f, last.first().fade)
        assertEquals(1f, last.last().fade)
        assertTrue(last.zipWithNext().all { (a, b) -> b.fade > a.fade })
        assertTrue(frames.dropLast(24).all { it.fade == 0f })
    }

    @Test
    fun longTripTimelineIsFast() {
        val points = straightPoints(0, 50_000)
        val cues = List(200) { cue(it.toLong(), it * 200_000L) }
        val started = System.nanoTime()
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)
        val chosen = VideoTimeline.choosePhotos(cues, VideoLength.LONG.photoCap, timeline.totalMoveMs)
        val frames = VideoTimeline.plan(timeline, chosen, VideoOptions(length = VideoLength.LONG), timeline.dayStarts)
        frames.forEach { timeline.at(it.moveMs) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(120 * 24, frames.size)
        assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
    }
}
