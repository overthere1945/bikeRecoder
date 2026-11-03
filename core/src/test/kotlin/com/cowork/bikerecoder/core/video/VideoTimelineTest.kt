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

    // --- 박에 맞춘 계획(스펙 §5.3) ---

    /** 124 BPM, 첫 박 0.4초. */
    private val beats124 = Beats(startUs = 400_000, beatUs = 483_871)

    private val oneFrame = 1.0 / 30 + 1e-9

    /** 영상 장 [frame]의 시각이 음악 격자([stepUs] 간격, [beats]의 첫 박 기준)에서 떨어진 초. 음악 시각 = 영상 시각 + musicStartUs. */
    private fun offGrid(frame: Int, planned: Planned, beats: Beats, stepUs: Long): Double {
        val x = (frame / 30.0 * 1e6 + planned.musicStartUs - beats.startUs) / stepUs
        return abs(x - Math.round(x)) * stepUs / 1e6
    }

    private fun photoStarts(frames: List<FramePlan>) = frames.filter { it.kind == SceneKind.PHOTO && it.sceneFrame == 0 }

    private val tenKm by lazy { rideOf(10.0, 3600) }
    private val fourPhotos = List(4) { cue(it.toLong(), 600_000L * (it + 1)) }

    private fun planned(timeline: RideTimeline = tenKm, photos: List<PhotoCue> = fourPhotos, beats: Beats? = beats124,
                        options: VideoOptions = VideoOptions()) =
        VideoTimeline.planToBeats(timeline, photos, options, emptyList(), beats)

    @Test
    fun rideStartsOnABar() {
        val p = planned()
        assertEquals(90, p.frames.indexOfFirst { it.kind == SceneKind.RIDE })
        val bar = beats124.barUs
        assertTrue((3_000_000 + p.musicStartUs - beats124.startUs) % bar < 1_000_000 / 30)
        // 가장 이른 마디: 곡 시작점 − 반 마디보다 이르지 않은 것 가운데.
        val lower = maxOf(0L, beats124.startUs - bar / 2)
        assertTrue(p.musicStartUs >= lower && p.musicStartUs - bar < lower, "${p.musicStartUs}")
    }

    @Test
    fun photosStartOnBeatsAndLastWholeBeats() {
        val frames = planned().frames
        val starts = photoStarts(frames)
        assertEquals(4, starts.size)
        val photoFrames = framesOf(4 * beats124.beatUs / 1e6)
        for (start in starts) {
            assertTrue(offGrid(start.index, planned(), beats124, beats124.beatUs) <= oneFrame, "photo at ${start.index}")
            assertEquals(photoFrames, start.sceneFrames)
            val run = frames.drop(start.index).takeWhile { it.kind == SceneKind.PHOTO && it.photo == start.photo }
            assertEquals(photoFrames, run.size)
        }
    }

    @Test
    fun outroOnABarAndEndsOnABar() {
        for (km in listOf(3.0, 10.0, 25.0)) {
            val p = planned(timeline = rideOf(km, 3600))
            val bar = beats124.barUs
            val outro = p.frames.indexOfFirst { it.kind == SceneKind.OUTRO }
            assertTrue(offGrid(outro, p, beats124, bar) <= oneFrame, "$km km: outro at $outro")
            assertTrue(offGrid(p.frames.size, p, beats124, bar) <= oneFrame, "$km km: ends at ${p.frames.size}")
            // 끝 장면 = 4초에 가장 가까운 마디 수(124 BPM: 2마디), 솟아오르기 1.5초, 어두워짐 마지막 1초.
            val frames = p.frames.drop(outro)
            assertEquals(framesOf(2 * bar / 1e6), frames.size)
            assertTrue(frames.all { it.sceneFrames == frames.size })
            assertEquals(List(45) { false } + List(frames.size - 45) { true }, frames.map { it.reuseMap })
            assertEquals(0f, frames[frames.size - 30].fade)
            assertEquals(1f, frames.last().fade)
            assertTrue(frames.dropLast(30).all { it.fade == 0f })
        }
    }

    @Test
    fun cardOnTheBeatNearest1_2s() {
        val beat = beats124.beatUs / 1e6
        val outro = planned().frames.filter { it.kind == SceneKind.OUTRO }
        assertTrue(outro.all { it.cardFrom == framesOf(beat * Math.round(1.2 / beat)) })
    }

    @Test
    fun unreachableEventKeepsItsTime() {
        // 이동 장면 시작 0.12~0.2초 뒤의 사진: 가장 가까운 박은 이동 장면 시작(3초)이라 앞 토막이 0이 되므로(70 % 미만) 맞추지 않는다.
        val timeline = tenKm
        val photo = (1..120).map { cue(1, it * 1_000L) }.first {
            val pace = VideoTimeline.paceFor(timeline, listOf(it), VideoOptions()).first
            pace.secondAt(timeline.routeDistance.atMove(it.moveMs)) >= 0.12
        }
        val second = VideoTimeline.paceFor(timeline, listOf(photo), VideoOptions()).first
            .secondAt(timeline.routeDistance.atMove(photo.moveMs))
        assertTrue(second <= 0.2, "photo at $second s")
        val plain = photoStarts(VideoTimeline.plan(timeline, listOf(photo), VideoOptions(), emptyList())).single()
        val p = planned(photos = listOf(photo))
        val snapped = photoStarts(p.frames).single()
        assertEquals(plain.index, snapped.index)
        assertTrue(offGrid(snapped.index, p, beats124, beats124.beatUs) > oneFrame)
    }

    @Test
    fun noBeatsIsThePlainPlan() {
        val p = planned(beats = null)
        assertEquals(VideoTimeline.plan(tenKm, fourPhotos, VideoOptions(), emptyList()), p.frames)
        assertEquals(0L, p.musicStartUs)
    }

    @Test
    fun shortMusicStillSnaps() {
        // 20초 곡의 박으로 약 36초 영상: 곡 길이와 상관없이 격자는 이어지고, 음악은 곡 끝까지만 깐다.
        val beats = Beats(startUs = 250_000, beatUs = 483_871)
        val p = planned(beats = beats)
        assertTrue(p.frames.size > 35 * 30)
        val starts = photoStarts(p.frames)
        assertEquals(4, starts.size)
        assertTrue(starts.all { offGrid(it.index, p, beats, beats.beatUs) <= oneFrame })
        val outro = p.frames.indexOfFirst { it.kind == SceneKind.OUTRO }
        assertTrue(offGrid(outro, p, beats, beats.barUs) <= oneFrame)
        val audio = AudioPlanner.plan(20_000_000, p.frames.size * 1_000_000L / 30, p.musicStartUs)
        assertEquals(20_000_000 - p.musicStartUs, audio.takeUs)
    }

    @Test
    fun beatLengthsStayWithinMaxLength() {
        // 112 BPM: 사진 4박 = 2.14초, 끝 2마디 = 4.29초라 짧게(30초)를 꽉 채운 50 km는 박 길이만으로 30초를 넘는다.
        val far = rideOf(50.0, 3600)
        val photos = List(4) { cue(it.toLong(), 600_000L * (it + 1)) }
        val beats112 = Beats(startUs = 400_000, beatUs = 535_714)
        val short = VideoOptions(length = VideoLength.SHORT)
        val plain = VideoTimeline.plan(far, photos, short, emptyList())
        assertEquals(30 * 30, plain.size)
        assertTrue(90 + plain.count(SceneKind.RIDE) + 4 * framesOf(4 * 0.535714) + framesOf(8 * 0.535714) > 30 * 30)
        assertTrue(planned(far, photos, beats112, short).frames.size <= 30 * 30)

        for (length in VideoLength.values()) {
            for (km in listOf(0.5, 5.0, 50.0, 300.0)) {
                for (bpmBeatUs in listOf(535_714L, 483_871L, 454_545L)) {
                    for (count in listOf(0, 20)) {
                        val cues = List(count) { cue(it.toLong(), 100_000L + it * 150_000L) }
                        val frames = planned(rideOf(km, 3600), cues, Beats(400_000, bpmBeatUs), VideoOptions(length = length)).frames
                        assertTrue(frames.size <= length.seconds * 30, "$length $km km $count photos $bpmBeatUs: ${frames.size}")
                        assertEquals(frames.indices.toList(), frames.map { it.index })
                    }
                }
            }
        }
    }

    @Test
    fun maxBlockedOutroFallsBackToTheEarlierBar() {
        // 판정 P9: 짧게(30초)를 꽉 채운 영상은 뒤 마디로 가면 30초를 넘으므로 앞 마디로 당겨 마디 끝에서 끝난다.
        val short = VideoOptions(length = VideoLength.SHORT)
        val cases = listOf(12.0, 20.0, 50.0).map { it to 0 } + listOf(5.0, 8.0, 12.0, 20.0, 50.0).map { it to 4 }
        for ((km, count) in cases) {
            run {
                val timeline = rideOf(km, 3600)
                // 사진은 앞 1/3에: 끝 앞 토막이 길어야 한 마디 가까이 당겨도 70 % 이상 남는다.
                val photos = List(count) { cue(it.toLong(), 300_000L * (it + 1)) }
                assertEquals(30 * 30, VideoTimeline.plan(timeline, photos, short, emptyList()).size, "$km km $count photos")
                val p = planned(timeline, photos, beats124, short)
                assertTrue(p.frames.size <= 30 * 30, "$km km $count photos: ${p.frames.size}")
                assertTrue(offGrid(p.frames.size, p, beats124, beats124.barUs) <= oneFrame, "$km km $count photos: ends at ${p.frames.size}")
            }
        }
    }

    @Test
    fun capIsOnTheUnroundedGridDistance() {
        // 117 BPM: 박 15.385장. 장 113은 박(105.38장)까지 7.62장으로 반 박(7.69장) 안이다. 반올림한 105장까지는 8장이지만 맞춘다.
        val beatFrames = 512_821 / 1e6 * 30
        assertEquals(-8, VideoTimeline.gridMove(113, beatFrames, piece = 100, room = 100))
    }

    @Test
    fun slowTempoLengths() {
        // 70 BPM(박 0.857초): 사진 = 2초에 가장 가까운 2박이 3박보다 적어 3박, 끝 = 1마디(3.43초), 카드 = 1박(26장).
        val beats70 = Beats(startUs = 400_000, beatUs = 857_143)
        val frames = planned(beats = beats70).frames
        assertTrue(photoStarts(frames).all { it.sceneFrames == framesOf(3 * 0.857143) })
        val outro = frames.filter { it.kind == SceneKind.OUTRO }
        assertEquals(framesOf(3.428572), outro.size)
        assertEquals(103, outro.size)
        assertTrue(outro.all { it.cardFrom == 26 })
    }
}
