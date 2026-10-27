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
        val timeline = lPath(step = 50.0) // 북 1 km, 동 2 km: 전체 지도가 주행 확대보다 멀다
        val (plans, cameras) = plansAndCameras(timeline)
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        plans.filter { it.kind == SceneKind.INTRO_OVERVIEW }.forEach { assertEquals(overview, cameras[it.index]) }

        val descent = plans.filter { it.kind == SceneKind.INTRO_DESCENT }
        val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
        assertEquals(overview, cameras[descent.first().index])
        assertEquals(firstRide, cameras[descent.last().index])

        val mid = cameras[descent[descent.size / 2].index]
        assertTrue(mid.zoom > overview.zoom && mid.zoom < CameraPath.ZOOM, "zoom ${mid.zoom}")
        assertTrue(mid.tilt > 0.0 && mid.tilt < CameraPath.TILT, "tilt ${mid.tilt}")
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
        assertEquals(0.0, cam.tilt)
        assertEquals(0.0, cam.bearing)
        val world = 512.0 * 2.0.pow(cam.zoom)
        val xs = points.map { (mercX(it.lon) - mercX(cam.center.lon)) * world + aspect.width / 2.0 }
        val ys = points.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + aspect.height / 2.0 }
        val eps = 1e-6
        xs.forEach { assertTrue(it >= 0.1 * aspect.width - eps && it <= 0.9 * aspect.width + eps, "$aspect x $it") }
        ys.forEach { assertTrue(it >= 0.1 * aspect.height - eps && it <= 0.9 * aspect.height + eps, "$aspect y $it") }
        val fill = max((xs.max() - xs.min()) / (0.8 * aspect.width), (ys.max() - ys.min()) / (0.8 * aspect.height))
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
        // 아주 짧은 경로는 주행 확대보다 더 들어가지 않는다.
        val tiny = CameraPath.overview(listOf(at(0.0, 0.0), at(3.0, 0.0)), Aspect.PORTRAIT)
        assertEquals(CameraPath.ZOOM, tiny.zoom)
        assertEquals(CameraPath.ZOOM, CameraPath.overview(listOf(at(0.0, 0.0)), Aspect.LANDSCAPE).zoom)
    }
}
