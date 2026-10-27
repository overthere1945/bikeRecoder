package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraPathTest {

    private val lat0 = 37.5
    private val lon0 = 127.0
    private val earthR = 6_371_008.8

    /** 기준점에서 북쪽 [n] m, 동쪽 [e] m. */
    private fun at(n: Double, e: Double) =
        GeoPoint(lat0 + Math.toDegrees(n / earthR), lon0 + Math.toDegrees(e / (earthR * cos(Math.toRadians(lat0)))))

    /** 1초에 한 점, (북, 동) 미터 목록으로 한 구간짜리 경로. */
    private fun path(vararg ne: Pair<Double, Double>) = RideTimeline(
        ne.mapIndexed { i, (n, e) -> RidePoint(i * 1_000L, at(n, e), null, i * 1_000L, 0, 0) },
    )

    /** (북, 동)에서 [headingDeg] 방향으로 5 m씩 [count]칸 간 점들(시작점 제외). */
    private fun walk(from: Pair<Double, Double>, headingDeg: Double, count: Int): List<Pair<Double, Double>> {
        val r = Math.toRadians(headingDeg)
        return List(count) { (from.first + (it + 1) * 5.0 * cos(r)) to (from.second + (it + 1) * 5.0 * kotlin.math.sin(r)) }
    }

    /** 북쪽 20칸 뒤 동쪽 40칸(한 칸 [step] m, 기본 5 m: 북 100 m, 동 200 m). */
    private fun lPath(step: Double = 5.0) = path(
        *(List(21) { (it * step) to 0.0 } + List(40) { 20 * step to (it + 1) * step }).toTypedArray(),
    )

    private fun angleDiff(a: Double, b: Double) = abs(((b - a) % 360 + 540) % 360 - 180)

    private fun plansAndCameras(timeline: RideTimeline, photos: List<PhotoCue> = emptyList()): Pair<List<FramePlan>, List<Camera>> {
        val plans = VideoTimeline.plan(timeline, photos, VideoOptions(length = VideoLength.SHORT), emptyList())
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        val cameras = CameraPath.cameras(plans, timeline, overview)
        assertEquals(plans.size, cameras.size)
        return plans to cameras
    }

    @Test
    fun rideCameraLooksAheadOneFiftyMetres() {
        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline)
        val firstRide = plans.first { it.kind == SceneKind.RIDE }
        val cam = cameras[firstRide.index]
        assertEquals(0L, firstRide.moveMs)
        assertEquals(CameraPath.ZOOM, cam.zoom)
        assertEquals(CameraPath.TILT, cam.tilt)
        assertEquals(timeline.at(0).point, cam.center)
        // 경로를 따라 150 m 앞 = 모퉁이(북 100)에서 동쪽 50 m.
        val expected = Math.toDegrees(atan2(50.0, 100.0))
        assertEquals(expected, cam.bearing, 0.5)

        // 동쪽 직선에 들어가 한참 지난 뒤에는 동쪽(90°)을 본다.
        val later = plans.filter { it.kind == SceneKind.RIDE }.minBy { abs(it.moveMs - 45_000L) }
        assertEquals(90.0, cameras[later.index].bearing, 1.0)
        assertEquals(timeline.at(later.moveMs).point, cameras[later.index].center)
    }

    @Test
    fun bearingChangeIsRateLimited() {
        // 북쪽 300 m 뒤 그대로 남쪽으로 300 m(180° 꺾임).
        val timeline = path(*(List(61) { (it * 5.0) to 0.0 } + List(60) { (300.0 - (it + 1) * 5.0) to 0.0 }).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.filter { it.kind == SceneKind.RIDE }
        val limit = CameraPath.MAX_TURN_DEG_PER_S / FPS
        var largest = 0.0
        ride.zipWithNext().forEach { (a, b) ->
            val d = angleDiff(cameras[a.index].bearing, cameras[b.index].bearing)
            assertTrue(d <= limit + 1e-9, "프레임 ${b.index}: $d° > $limit°")
            largest = max(largest, d)
        }
        assertEquals(limit, largest, 1e-6) // 꺾이는 곳에서 상한에 걸린다
        assertEquals(0.0, cameras[ride.first().index].bearing, 0.5)
        assertEquals(0.0, angleDiff(180.0, cameras[ride.last().index].bearing), 1.0)
    }

    @Test
    fun descentInterpolates() {
        // 북동(45°)으로 2 km: 전체 지도가 주행 확대보다 멀고, 첫 주행 방향이 북쪽이 아니다.
        val timeline = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 45.0, 400)).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }.forEach { assertEquals(overview, cameras[it.index]) }

        val descent = plans.filter { it.kind == SceneKind.INTRO_DESCENT }
        val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
        assertEquals(overview, cameras[descent.first().index])
        assertEquals(firstRide, cameras[descent.last().index])
        // 방향도 북(0°)에서 첫 주행 방향(45°)으로 돌아 끝에서 같아진다.
        assertEquals(0.0, cameras[descent.first().index].bearing)
        assertEquals(45.0, firstRide.bearing, 0.5)
        assertEquals(firstRide.bearing, cameras[descent.last().index].bearing)

        val mid = cameras[descent[descent.size / 2].index]
        assertTrue(mid.zoom > overview.zoom && mid.zoom < CameraPath.ZOOM, "zoom ${mid.zoom}")
        assertTrue(mid.tilt > 0.0 && mid.tilt < CameraPath.TILT, "tilt ${mid.tilt}")
        assertTrue(mid.bearing > 0.0 && mid.bearing < firstRide.bearing, "bearing ${mid.bearing}")
        descent.zipWithNext().forEach { (a, b) ->
            assertTrue(cameras[b.index].tilt >= cameras[a.index].tilt)
            assertTrue(cameras[b.index].zoom >= cameras[a.index].zoom)
        }
    }

    @Test
    fun photoFreezesCamera() {
        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline, listOf(PhotoCue(7, 30_000L, 30_000L)))
        val photo = plans.filter { it.kind == SceneKind.PHOTO }
        assertEquals(VideoTimeline.PHOTO_FRAMES, photo.size)
        val before = plans[photo.first().index - 1]
        assertEquals(SceneKind.RIDE, before.kind)
        photo.forEach { assertEquals(cameras[before.index], cameras[it.index]) }

        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        plans.filter { it.kind == SceneKind.OUTRO }.forEach { assertEquals(overview, cameras[it.index]) }
    }

    // --- overview ---

    private fun mercX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    /** 카메라 화면에서 점들이 차지하는 픽셀 범위가 여백 10% 안에 들고, 한 방향은 꽉 차는지. */
    private fun assertFits(points: List<GeoPoint>, aspect: Aspect) {
        val cam = CameraPath.overview(points, aspect)
        assertEquals(CameraPath.overview(points, aspect.width, aspect.height), cam)
        assertFits(points, cam, aspect.width, aspect.height)
    }

    private fun assertFits(points: List<GeoPoint>, width: Int, height: Int) =
        assertFits(points, CameraPath.overview(points, width, height), width, height)

    private fun assertFits(points: List<GeoPoint>, cam: Camera, width: Int, height: Int) {
        val aspect = "${width}x$height"
        assertEquals(0.0, cam.tilt)
        assertEquals(0.0, cam.bearing)
        val world = 512.0 * 2.0.pow(cam.zoom)
        val xs = points.map { (mercX(it.lon) - mercX(cam.center.lon)) * world + width / 2.0 }
        val ys = points.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + height / 2.0 }
        val eps = 1e-6
        xs.forEach { assertTrue(it >= 0.1 * width - eps && it <= 0.9 * width + eps, "$aspect x $it") }
        ys.forEach { assertTrue(it >= 0.1 * height - eps && it <= 0.9 * height + eps, "$aspect y $it") }
        val fill = max((xs.max() - xs.min()) / (0.8 * width), (ys.max() - ys.min()) / (0.8 * height))
        assertEquals(1.0, fill, 1e-6)
    }

    @Test
    fun overviewFitsAllPoints() {
        val tall = listOf(at(0.0, 0.0), at(20_000.0, -3_000.0), at(8_000.0, 5_000.0), at(-1_000.0, 1_000.0))
        val wide = listOf(at(0.0, 0.0), at(2_000.0, 30_000.0), at(-3_000.0, 12_000.0))
        for (aspect in Aspect.entries) {
            assertFits(tall, aspect)
            assertFits(wide, aspect)
        }
        // 실제로 찍는 지도 크기(규칙 V5): 요약 이미지 세로 지도 칸, 가로 지도 칸, 720p 세로 영상.
        for ((w, h) in listOf(1080 to 960, 1152 to 1080, 720 to 1280)) {
            assertFits(tall, w, h)
            assertFits(wide, w, h)
        }
        // 같은 경로라도 칸이 작으면 덜 확대한다(세로 화면 1920 대신 지도 칸 960).
        assertTrue(CameraPath.overview(tall, 1080, 960).zoom < CameraPath.overview(tall, Aspect.PORTRAIT).zoom)
        // 아주 짧은 경로는 주행 확대보다 더 들어가지 않는다.
        val tiny = CameraPath.overview(listOf(at(0.0, 0.0), at(3.0, 0.0)), Aspect.PORTRAIT)
        assertEquals(CameraPath.ZOOM, tiny.zoom)
        assertEquals(CameraPath.ZOOM, CameraPath.overview(listOf(at(0.0, 0.0)), Aspect.LANDSCAPE).zoom)
    }

    @Test
    fun outroFitsTheRouteAboveTheCard() {
        val tall = listOf(at(0.0, 0.0), at(20_000.0, -3_000.0), at(8_000.0, 5_000.0), at(-1_000.0, 1_000.0))
        val wide = listOf(at(0.0, 0.0), at(2_000.0, 30_000.0), at(-3_000.0, 12_000.0))
        for ((w, h) in listOf(1080 to 1920, 1920 to 1080, 720 to 1280)) for (points in listOf(tall, wide)) {
            val cardTop = OutroCardLayout.card(w, h, 7).y
            val cam = CameraPath.outro(points, w, h, cardTop)
            assertEquals(0.0, cam.tilt)
            assertEquals(0.0, cam.bearing)
            // Zoom fitted to the box above the card, the route centred in that box (10 % margins inside it).
            assertEquals(CameraPath.overview(points, w, cardTop.toInt()).zoom, cam.zoom, 1e-9)
            val world = 512.0 * 2.0.pow(cam.zoom)
            val xs = points.map { (mercX(it.lon) - mercX(cam.center.lon)) * world + w / 2.0 }
            val ys = points.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + h / 2.0 }
            val eps = 1e-6
            xs.forEach { assertTrue(it >= 0.1 * w - eps && it <= 0.9 * w + eps, "${w}x$h x $it") }
            ys.forEach { assertTrue(it >= 0.1 * cardTop - 1 && it <= 0.9 * cardTop + 1, "${w}x$h y $it above $cardTop") }
        }
    }

    @Test
    fun outroCameraIsUsedForTheOutroOnly() {
        val timeline = lPath()
        val plans = VideoTimeline.plan(timeline, emptyList(), VideoOptions(length = VideoLength.SHORT), emptyList())
        val points = timeline.points.map { it.point }
        val overview = CameraPath.overview(points, Aspect.PORTRAIT)
        val outro = CameraPath.outro(points, 1080, 1920, OutroCardLayout.card(1080, 1920, 7).y)
        val cameras = CameraPath.cameras(plans, timeline, overview, outro)

        plans.filter { it.kind == SceneKind.OUTRO }.forEach { assertEquals(outro, cameras[it.index]) }
        plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }.forEach { assertEquals(overview, cameras[it.index]) }
    }

    @Test
    fun bearingTurnsShortWayAcrossNorth() {
        // 350°로 300 m 뒤 10°로 300 m: 0/360을 지나 20°만 돈다.
        val first = listOf(0.0 to 0.0) + walk(0.0 to 0.0, 350.0, 60)
        val timeline = path(*(first + walk(first.last(), 10.0, 60)).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.filter { it.kind == SceneKind.RIDE }.map { cameras[it.index].bearing }
        val limit = CameraPath.MAX_TURN_DEG_PER_S / FPS
        ride.zipWithNext().forEach { (a, b) -> assertTrue(angleDiff(a, b) <= limit + 1e-9, "$a → $b") }
        ride.forEach { assertTrue(angleDiff(0.0, it) <= 10.0 + 1e-6, "남쪽으로 돌아감: $it") }
        assertEquals(350.0, ride.first(), 0.5)
        assertEquals(10.0, ride.last(), 0.5)
        assertTrue(ride.any { it < 180.0 } && ride.any { it > 180.0 })
    }

    @Test
    fun lookAheadStopsAtSegmentEnd() {
        // 구간 0: 북쪽 100 m(0~20초). 구간 1: 동쪽 300 m 떨어진 곳에서 동쪽으로(20~60초).
        val seg0 = List(21) { RidePoint(it * 1_000L, at(it * 5.0, 0.0), null, it * 1_000L, 0, 0) }
        val seg1 = List(41) { RidePoint(20_000L + it * 1_000L, at(0.0, 300.0 + it * 5.0), null, 3_600_000L + it * 1_000L, 1, 0) }
        val timeline = RideTimeline(seg0 + seg1)
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.filter { it.kind == SceneKind.RIDE }
        // 구간 0에서는 다음 구간(동쪽)을 보지 않는다.
        ride.filter { it.moveMs < 20_000L }.forEach { assertEquals(0.0, angleDiff(0.0, cameras[it.index].bearing), 0.5) }
        // 경계를 지나면 중심은 다음 구간 위에 있고, 방향은 동쪽으로 돈다.
        val after = ride.first { it.moveMs >= 20_000L }
        assertEquals(timeline.at(after.moveMs).point, cameras[after.index].center)
        assertEquals(90.0, cameras[ride.last().index].bearing, 1.0)
    }

    @Test
    fun standingStillJitterKeepsBearing() {
        // 북쪽 100 m → 그 자리에서 2 m 안팎으로 80번 흔들림(경로상 약 226 m) → 다시 북쪽 200 m.
        val north = List(21) { (it * 5.0) to 0.0 }
        val offsets = listOf(2.0 to 0.0, 0.0 to 2.0, -2.0 to 0.0, 0.0 to -2.0)
        val jitter = List(80) { offsets[it % 4].let { (n, e) -> (100.0 + n) to e } }
        val onward = List(40) { (100.0 + (it + 1) * 5.0) to 0.0 }
        val timeline = path(*(north + jitter + onward).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        plans.filter { it.kind == SceneKind.RIDE }.forEach {
            val b = cameras[it.index].bearing
            assertTrue(angleDiff(0.0, b) <= 5.0, "이동 ${it.moveMs} ms 방향 $b")
        }
    }
}
