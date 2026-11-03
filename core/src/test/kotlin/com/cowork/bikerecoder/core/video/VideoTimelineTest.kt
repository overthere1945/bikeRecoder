package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.recording.north
import java.time.LocalDate
import kotlin.math.abs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoTimelineTest {

    private fun cue(id: Long, moveMs: Long) = PhotoCue(mediaId = id, moveMs = moveMs, takenAt = moveMs)

    /** 초당 1 m로 가는 여정의 사진 거리(= 이동 시각 초). */
    private val alongPerSecond: (PhotoCue) -> Double = { it.moveMs / 1000.0 }

    private fun List<FramePlan>.count(kind: SceneKind) = count { it.kind == kind }

    /** [km] km를 [seconds]초 동안 같은 빠르기로 북쪽으로 가는 한 구간짜리 여정. */
    private fun rideOf(km: Double, seconds: Int): RideTimeline =
        RideTimelineBuilder.build(listOf(segment(1, 0) to straightPoints(0, seconds + 1, stepM = km * 1000 / seconds)), SEOUL)

    private fun ride(frames: List<FramePlan>) = frames.filter { it.kind == SceneKind.RIDE }

    @Test
    fun lengthFollowsDistance() {
        val timeline = rideOf(1.0, 600)
        val expected = 90 + framesOf(11.0) + 120
        for (length in listOf(VideoLength.NORMAL, VideoLength.LONG)) {
            val frames = VideoTimeline.plan(timeline, emptyList(), VideoOptions(length = length), emptyList())
            assertEquals(expected, frames.size)
            assertEquals(frames.indices.toList(), frames.map { it.index })
            assertEquals(framesOf(11.0), frames.count(SceneKind.RIDE))
        }
        val normal = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), emptyList())
        assertEquals(36, normal.count(SceneKind.INTRO_OVERVIEW))
        assertEquals(54, normal.count(SceneKind.INTRO_DESCENT))
        assertEquals(120, normal.count(SceneKind.OUTRO))
        // 50 km는 이동 장면이 41.4초지만 짧게는 30초가 최대다.
        val far = rideOf(50.0, 3600)
        assertEquals(30 * 30, VideoTimeline.plan(far, emptyList(), VideoOptions(length = VideoLength.SHORT), emptyList()).size)
    }

    @Test
    fun neverLongerThanMaxLength() {
        for (length in VideoLength.values()) {
            for (km in listOf(0.5, 5.0, 50.0, 300.0)) {
                val timeline = rideOf(km, 3600)
                for (count in listOf(0, 20)) {
                    val photos = List(count) { cue(it.toLong(), 100_000L + it * 150_000L) }
                    val frames = VideoTimeline.plan(timeline, photos, VideoOptions(length = length), emptyList())
                    assertTrue(frames.size <= length.seconds * 30, "$length $km km $count photos: ${frames.size}")
                }
            }
        }
    }

    @Test
    fun plansCarryTheirSceneFrames() {
        val plans = VideoTimeline.plan(simpleTimeline(600), emptyList(), VideoOptions(), emptyList())
        val outro = plans.filter { it.kind == SceneKind.OUTRO }
        assertEquals((0 until 120).toList(), outro.map { it.sceneFrame })
        assertTrue(outro.all { it.sceneFrames == 120 && it.cardFrom == 36 })
        assertEquals(54, plans.first { it.kind == SceneKind.INTRO_DESCENT }.sceneFrames)
    }

    @Test
    fun photoCountFollowsRideLength() {
        // 1 km(R0 = 11초)는 사진 많아야 2장: 5장이 있어도 2장면.
        val timeline = rideOf(1.0, 600)
        val photos = List(5) { cue(it.toLong(), 60_000L + it * 100_000L) }
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(), emptyList())
        assertEquals(2 * 60, frames.count(SceneKind.PHOTO))
        assertEquals(2, VideoTimeline.paceFor(timeline, photos, VideoOptions()).second.size)
        // 이동 장면은 R0에서 사진 2초씩 빼지 않는다: 11초 그대로(최대 60초 안).
        assertEquals(framesOf(11.0), frames.count(SceneKind.RIDE))
        assertEquals(90 + framesOf(11.0) + 120 + 120, frames.size)
    }

    @Test
    fun photosShortenRideWhenLengthIsTheLimit() {
        // 50 km(R0 = 41.4초) 짧게(30초): 사진 4장이면 R = 30 − 7 − 8 = 15초.
        val timeline = rideOf(50.0, 3600)
        val photos = List(8) { cue(it.toLong(), 200_000L + it * 400_000L) }
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(length = VideoLength.SHORT), emptyList())
        assertEquals(4 * 60, frames.count(SceneKind.PHOTO))
        assertEquals(framesOf(15.0), frames.count(SceneKind.RIDE))
        assertEquals(30 * 30, frames.size)
    }

    @Test
    fun photosOffIgnoresPhotos() {
        val timeline = rideOf(1.0, 600)
        val photos = List(8) { cue(it.toLong(), 30_000L + it * 70_000L) }
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(photos = false), emptyList())
        assertEquals(0, frames.count(SceneKind.PHOTO))
        assertEquals(framesOf(11.0), frames.count(SceneKind.RIDE))
    }

    @Test
    fun shortMoveIsNotStretched() {
        // 20초·100 m: R0 = 6 + 5 × √0.1 = 7.58초 < 이동 시간.
        val shortRide = VideoTimeline.plan(simpleTimeline(20), emptyList(), VideoOptions(), emptyList())
        assertEquals(framesOf(VideoPace.rideSeconds(0.1)), shortRide.count(SceneKind.RIDE))
        // 3초·15 m는 이동 시간이 R0(4초)보다 짧아 이동 시간만큼만.
        val tiny = VideoTimeline.plan(simpleTimeline(3), emptyList(), VideoOptions(), emptyList())
        assertEquals(framesOf(3.0), tiny.count(SceneKind.RIDE))
        // 이동 시간이 200초인 8 m(R0 = 4초, 최소 4초)는 4초.
        val still = VideoTimeline.plan(rideOf(0.008, 200), emptyList(), VideoOptions(), emptyList())
        assertEquals(framesOf(4.0), still.count(SceneKind.RIDE))
    }

    @Test
    fun noMovingTimeMakesNoRideFrames() {
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to straightPoints(0, 1)), SEOUL)
        assertEquals(0L, timeline.totalMoveMs)
        val frames = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), emptyList())
        assertEquals(0, frames.count(SceneKind.RIDE))
        assertEquals(90 + 120, frames.size)
    }

    @Test
    fun paceIsByDistanceNotTime() {
        // 앞 10분은 초당 1 m(600 m), 다음 10분은 초당 5 m(3 km), 마지막 1분은 초당 6 m(360 m, 가장 빠른 곳이 끝에 오게).
        // 영상은 거리로 폈으므로 앞 600 m는 이동 장면의 약 600/3960. (가장 빠른 곳이 600 m 근처면 거기가 천천히 지나가 몫이 커진다.)
        val slow = straightPoints(0, 601, stepM = 1.0)
        val fast = straightPoints(601_000, 600, startM = 605.0, stepM = 5.0)
        val sprint = straightPoints(1_201_000, 60, startM = 3_606.0, stepM = 6.0)
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to slow + fast + sprint), SEOUL)
        assertEquals(1_260_000L, timeline.totalMoveMs)
        val total = timeline.routeDistance.total

        val rideFrames = ride(VideoTimeline.plan(timeline, emptyList(), VideoOptions(), emptyList()))
        assertEquals(0L, rideFrames.first().moveMs)
        val firstShare = rideFrames.count { it.alongM < 600.0 }.toDouble() / rideFrames.size
        assertEquals(600.0 / total, firstShare, 0.05)
        // 시간 기준이었다면 앞 600 m가 이동 시간의 거의 절반이다. 영상 절반의 이동 시각은 그보다 한참 뒤다.
        val half = rideFrames[rideFrames.size / 2]
        assertTrue(half.moveMs > 900_000L, "moveMs ${half.moveMs}")
        assertTrue(rideFrames.zipWithNext().all { (a, b) -> b.alongM >= a.alongM && b.moveMs >= a.moveMs })
    }

    @Test
    fun rideFramesCarryAlongAndSpeed() {
        val timeline = rideOf(3.0, 600)
        val photos = listOf(cue(1, 300_000))
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(), emptyList())
        val rideFrames = ride(frames)
        val total = timeline.routeDistance.total
        assertEquals(0.0, rideFrames.first().alongM, 1e-9)
        assertEquals(total, rideFrames.last().alongM, 1e-6)
        assertTrue(rideFrames.zipWithNext().all { (a, b) -> b.alongM >= a.alongM })
        assertTrue(rideFrames.all { it.speed > 0f })
        // 천천히 가속해서 처음 장은 평균보다 느리다.
        assertTrue(rideFrames.first().speed < 1f)
        assertTrue(frames.filter { it.kind != SceneKind.RIDE }.all { it.speed == 1f })
        assertTrue(frames.filter { it.kind == SceneKind.PHOTO }.all { abs(it.alongM - total / 2) < 1e-6 })
        assertTrue(frames.filter { it.kind == SceneKind.OUTRO }.all { it.alongM == total })
        assertTrue(frames.filter { it.kind == SceneKind.INTRO_OVERVIEW || it.kind == SceneKind.INTRO_DESCENT }.all { it.alongM == 0.0 })
    }

    @Test
    fun choosePhotosEvenly() {
        val cues = List(20) { cue(it.toLong(), it * 30_000L + 1_000L) }.shuffled(kotlin.random.Random(7))
        val chosen = VideoTimeline.choosePhotos(cues, 8, 600.0, alongPerSecond)
        assertEquals(8, chosen.size)
        assertEquals(chosen.sortedBy { it.moveMs }, chosen)
        // 칸(75초)마다 하나씩, 칸 가운데(37.5초 + 75초·k)에 가장 가까운 것
        assertEquals(
            listOf(31_000L, 121_000L, 181_000L, 271_000L, 331_000L, 421_000L, 481_000L, 571_000L),
            chosen.map { it.moveMs },
        )
    }

    @Test
    fun choosePhotosByDistanceNotTime() {
        // 시간 칸으로는 [1, 2], 거리 칸으로는 [2, 3].
        val cues = listOf(cue(1, 10_000), cue(2, 400_000), cue(3, 580_000))
        val along = mapOf(1L to 100.0, 2L to 300.0, 3L to 950.0)
        val chosen = VideoTimeline.choosePhotos(cues, 2, 1000.0) { along.getValue(it.mediaId) }
        assertEquals(listOf(2L, 3L), chosen.map { it.mediaId })
    }

    @Test
    fun choosePhotosKeepsAllUnderCap() {
        val cues = listOf(cue(1, 500_000), cue(2, 10_000), cue(3, 200_000))
        assertEquals(listOf(2L, 3L, 1L), VideoTimeline.choosePhotos(cues, 8, 600.0, alongPerSecond).map { it.mediaId })
    }

    @Test
    fun choosePhotosFillsCapWhenClustered() {
        val cues = List(20) { cue(it.toLong(), 300_000L + it * 100L) }
        val chosen = VideoTimeline.choosePhotos(cues, 8, 600.0, alongPerSecond)
        assertEquals(8, chosen.size)
        assertEquals(8, chosen.map { it.mediaId }.toSet().size)
        assertEquals(chosen.sortedBy { it.moveMs }, chosen)
    }

    @Test
    fun samePlacePhotosConsecutive() {
        // 9 km(R0 = 21초)는 사진 5장까지.
        val timeline = rideOf(9.0, 600)
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
        // 사진 장면의 경로 거리는 사진 묶음의 거리이고, 앞뒤 이동 장면 사이에서 줄지 않는다.
        assertTrue(fourth.all { it.alongM == fourth.first().alongM })
        assertTrue(frames[fourth.first().index - 1].alongM <= fourth.first().alongM)
        assertTrue(frames[fourth.last().index + 1].alongM >= fourth.first().alongM)
    }

    @Test
    fun photosAtSameAlongStayGrouped() {
        val timeline = rideOf(3.0, 600)
        val photos = listOf(cue(1, 200_000), cue(2, 200_400), cue(3, 200_800))
        val frames = VideoTimeline.plan(timeline, photos, VideoOptions(), emptyList())
        val photoFrames = frames.filter { it.kind == SceneKind.PHOTO }
        assertEquals(3 * 60, photoFrames.size)
        assertEquals((photoFrames.first().index until photoFrames.first().index + 180).toList(), photoFrames.map { it.index })
        assertEquals(listOf(1L, 2L, 3L), photoFrames.map { it.photo!!.mediaId }.distinct())
        assertEquals(photoFrames.sortedBy { it.photo!!.takenAt }.map { it.photo!!.mediaId }, photoFrames.map { it.photo!!.mediaId })
    }

    @Test
    fun multiDayCaptionAtDayStartAlong() {
        val morning = 1_792_278_000_000L // 2026-10-18 08:00 KST
        val timeline = RideTimelineBuilder.build(
            listOf(
                segment(1, 0, LocalDate.of(2026, 10, 17)) to straightPoints(0, 301),
                // 둘째 날은 5 km 떨어진 곳에서 시작한다(건너뛰는 5 km는 경로 거리에 세지 않는다).
                segment(2, morning, LocalDate.of(2026, 10, 18)) to straightPoints(morning, 301, startM = 6_500.0),
            ),
            SEOUL,
        )
        val dayStart = timeline.dayStarts.single().first
        assertEquals(300_000L, dayStart)
        assertEquals(3_000.0, timeline.routeDistance.total, 1.0)
        val dayAlong = timeline.routeDistance.atMove(dayStart)
        val frames = VideoTimeline.plan(timeline, emptyList(), VideoOptions(), timeline.dayStarts)
        val captioned = frames.filter { it.caption != null }
        assertEquals(45, captioned.size)
        assertTrue(captioned.all { it.kind == SceneKind.RIDE && it.caption == "2일째 · 10월 18일" })
        assertEquals((captioned.first().index until captioned.first().index + 45).toList(), captioned.map { it.index })
        assertTrue(captioned.first().alongM >= dayAlong)
        assertTrue(frames[captioned.first().index - 1].alongM < dayAlong)
        assertTrue(ride(frames).zipWithNext().all { (a, b) -> b.alongM >= a.alongM && b.moveMs >= a.moveMs })
    }

    @Test
    fun onlyTheOutroHoldReusesTheMap() {
        val timeline = simpleTimeline(600)
        val frames = VideoTimeline.plan(timeline, listOf(cue(1, 300_000)), VideoOptions(), emptyList())
        val overview = frames.filter { it.kind == SceneKind.INTRO_OVERVIEW }
        assertEquals(36, overview.size)
        // The camera turns over the overview, orbits during a photo and rises at the start of the outro: a new map every frame there.
        assertTrue(overview.all { !it.reuseMap })
        assertTrue(overview.all { it.moveMs == 0L })
        assertTrue(frames.filter { it.kind == SceneKind.INTRO_DESCENT }.all { !it.reuseMap })
        assertTrue(frames.filter { it.kind == SceneKind.RIDE }.all { !it.reuseMap })
        val photo = frames.filter { it.kind == SceneKind.PHOTO }
        assertEquals(60, photo.size)
        assertTrue(photo.all { !it.reuseMap })
        val outro = frames.filter { it.kind == SceneKind.OUTRO }
        assertEquals(List(45) { false } + List(75) { true }, outro.map { it.reuseMap })
        assertTrue(outro.all { it.moveMs == timeline.totalMoveMs })
        assertEquals(0f, overview.first().sceneProgress)
        assertEquals(1f, outro.last().sceneProgress)
    }

    @Test
    fun fadeOnLastSecond() {
        val frames = VideoTimeline.plan(simpleTimeline(600), emptyList(), VideoOptions(), emptyList())
        val last = frames.takeLast(30)
        assertEquals(0f, last.first().fade)
        assertEquals(1f, last.last().fade)
        assertTrue(last.zipWithNext().all { (a, b) -> b.fade > a.fade })
        assertTrue(frames.dropLast(30).all { it.fade == 0f })
    }

    @Test
    fun longTripTimelineIsFast() {
        val points = straightPoints(0, 50_000)
        val cues = List(200) { cue(it.toLong(), it * 200_000L) }
        val started = System.nanoTime()
        val timeline = RideTimelineBuilder.build(listOf(segment(1, 0) to points), SEOUL)
        val chosen = VideoTimeline.choosePhotos(cues, VideoLength.LONG.photoCap, timeline)
        val frames = VideoTimeline.plan(timeline, chosen, VideoOptions(length = VideoLength.LONG), timeline.dayStarts)
        frames.forEach { timeline.at(it.moveMs) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(120 * 30, frames.size)
        assertTrue(elapsedMs < 1_000, "took $elapsedMs ms")
    }
}
