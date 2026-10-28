package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
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

    /** (북, 동)에서 [headingDeg] 방향으로 [stepM] m씩 [count]칸 간 점들(시작점 제외). */
    private fun walk(from: Pair<Double, Double>, headingDeg: Double, count: Int, stepM: Double = 5.0): List<Pair<Double, Double>> {
        val r = Math.toRadians(headingDeg)
        return List(count) { (from.first + (it + 1) * stepM * cos(r)) to (from.second + (it + 1) * stepM * sin(r)) }
    }

    /** 북쪽 20칸 뒤 동쪽 40칸(한 칸 [step] m, 기본 5 m: 북 100 m, 동 200 m). */
    private fun lPath(step: Double = 5.0) = path(
        *(List(21) { (it * step) to 0.0 } + List(40) { 20 * step to (it + 1) * step }).toTypedArray(),
    )

    private fun signedDiff(from: Double, to: Double) = ((to - from) % 360 + 540) % 360 - 180
    private fun angleDiff(a: Double, b: Double) = abs(signedDiff(a, b))
    private fun normalize(deg: Double) = ((deg % 360) + 360) % 360

    /** 주행 프레임 [k](0부터)의 흔들기: 영상 시간 k / 24초의 사인. */
    private fun sway(k: Int) = CameraPath.SWAY_DEG * sin(2 * PI * k / FPS / CameraPath.SWAY_PERIOD_S)

    /** 흔들기를 뺀 방향(평활해 따라가는 진행 방향). */
    private fun heading(cam: Camera, k: Int) = normalize(cam.bearing - sway(k))

    private fun plansAndCameras(
        timeline: RideTimeline,
        photos: List<PhotoCue> = emptyList(),
        outro: Camera? = null,
    ): Triple<List<FramePlan>, List<Camera>, Camera> {
        val plans = VideoTimeline.plan(timeline, photos, VideoOptions(length = VideoLength.SHORT), emptyList())
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        val cameras = CameraPath.cameras(plans, timeline, overview, outro ?: overview, heightPx = Aspect.PORTRAIT.height)
        assertEquals(plans.size, cameras.size)
        return Triple(plans, cameras, overview)
    }

    private fun List<FramePlan>.of(kind: SceneKind) = filter { it.kind == kind }

    /** 카메라 중심이 [point]에서 카메라 방향으로 [CameraPath.markerLeadM]만큼 앞(±1 m)에 있다. */
    private fun assertLeads(cam: Camera, point: GeoPoint, message: String = "") {
        val lead = CameraPath.markerLeadM(cam.zoom, cam.tilt, Aspect.PORTRAIT.height, point.lat)
        val r = Math.toRadians(cam.bearing)
        val expected = GeoPoint(
            point.lat + Math.toDegrees(lead * cos(r) / earthR),
            point.lon + Math.toDegrees(lead * sin(r) / (earthR * cos(Math.toRadians(point.lat)))),
        )
        val off = GeoMath.distanceM(expected, cam.center)
        assertTrue(off <= 1.0, "$message 중심이 앞당긴 자리에서 $off m 벗어남")
    }

    private fun assertSameCamera(expected: Camera, actual: Camera, message: String = "") {
        assertTrue(GeoMath.distanceM(expected.center, actual.center) <= 0.5, "$message center ${actual.center} vs ${expected.center}")
        assertEquals(expected.zoom, actual.zoom, 1e-6, "$message zoom")
        assertEquals(expected.tilt, actual.tilt, 1e-6, "$message tilt")
        assertEquals(0.0, angleDiff(expected.bearing, actual.bearing), 1e-6, "$message bearing")
    }

    // --- ride ---

    @Test
    fun rideZoomFollowsSpeed() {
        assertEquals(17.4, CameraPath.zoomForSpeed(5.0), 1e-9)
        assertEquals(17.4, CameraPath.zoomForSpeed(10.0), 1e-9)
        assertEquals(17.0, CameraPath.zoomForSpeed(20.0), 1e-9)
        assertEquals(16.6, CameraPath.zoomForSpeed(40.0), 1e-9)
        assertEquals(16.6, CameraPath.zoomForSpeed(30.0), 1e-9)
        assertEquals(17.4, CameraPath.zoomForSpeed(0.0), 1e-9)
    }

    @Test
    fun rideZoomChangesSlowly() {
        // 1분 동안 10 km/h로 북쪽, 이어서 2분 동안 30 km/h로 북쪽.
        val slow = walk(0.0 to 0.0, 0.0, 60, 10.0 / 3.6)
        val fast = walk(slow.last(), 0.0, 120, 30.0 / 3.6)
        val timeline = path(*(listOf(0.0 to 0.0) + slow + fast).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val zooms = plans.of(SceneKind.RIDE).map { cameras[it.index].zoom }
        val step = CameraPath.ZOOM_RATE_PER_S / FPS
        var largest = 0.0
        zooms.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue(abs(b - a) <= step + 1e-9, "주행 프레임 ${i + 1}: Δzoom ${b - a}")
            largest = max(largest, abs(b - a))
        }
        zooms.forEach { assertTrue(it >= CameraPath.ZOOM_FAST - 1e-9 && it <= CameraPath.ZOOM_SLOW + 1e-9, "zoom $it") }
        assertEquals(step, largest, 1e-9) // 빨라지는 곳에서 상한에 걸린다
        assertEquals(CameraPath.ZOOM_SLOW, zooms.first(), 1e-9)
        assertEquals(CameraPath.ZOOM_FAST, zooms.last(), 1e-3)
    }

    @Test
    fun turnsAreCappedAndSwayIsEightDegrees() {
        // 직각: 북쪽 300 m 뒤 동쪽 300 m.
        val north = listOf(0.0 to 0.0) + walk(0.0 to 0.0, 0.0, 60)
        val corner = path(*(north + walk(north.last(), 90.0, 60)).toTypedArray())
        val (plans, cameras) = plansAndCameras(corner)
        val limit = CameraPath.MAX_TURN_DEG_PER_S / FPS + CameraPath.SWAY_DEG * 2 * PI / CameraPath.SWAY_PERIOD_S / FPS + 1e-6
        plans.of(SceneKind.RIDE).zipWithNext().forEach { (a, b) ->
            val d = angleDiff(cameras[a.index].bearing, cameras[b.index].bearing)
            assertTrue(d <= limit, "프레임 ${b.index}: $d° > $limit°")
        }

        // 북쪽으로 1 km 곧게: 방향 − 경로 방향(0°)이 ±8°로 흔들린다(23초 주행 > 16초 주기).
        val straight = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 0.0, 200)).toTypedArray())
        val (sPlans, sCameras) = plansAndCameras(straight)
        val off = sPlans.of(SceneKind.RIDE).map { signedDiff(0.0, sCameras[it.index].bearing) }
        assertEquals(CameraPath.SWAY_DEG, off.max(), 0.3)
        assertEquals(-CameraPath.SWAY_DEG, off.min(), 0.3)
    }

    @Test
    fun markerSitsInTheLowerThird() {
        val lead = CameraPath.markerLeadM(17.2, 60.0, 1920, 37.5)
        assertTrue(lead in 200.0..245.0, "lead $lead m")
        // 멀리 볼수록(확대가 작을수록) 더 앞에 둔다.
        assertTrue(CameraPath.markerLeadM(16.6, 60.0, 1920, 37.5) > CameraPath.markerLeadM(17.4, 60.0, 1920, 37.5))

        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline)
        plans.of(SceneKind.RIDE).forEach { assertLeads(cameras[it.index], timeline.at(it.moveMs).point, "이동 ${it.moveMs} ms") }
    }

    @Test
    fun rideCameraLooksAheadOneTwentyMetres() {
        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.of(SceneKind.RIDE)
        val cam = cameras[ride.first().index]
        assertEquals(0L, ride.first().moveMs)
        assertEquals(CameraPath.ZOOM_SLOW, cam.zoom) // 출발 순간 속도 0
        assertEquals(CameraPath.TILT, cam.tilt)
        // 경로를 따라 120 m 앞 = 모퉁이(북 100)에서 동쪽 20 m. 첫 장은 흔들기 0.
        assertEquals(Math.toDegrees(atan2(20.0, 100.0)), cam.bearing, 0.5)

        // 동쪽 직선에 들어가 한참 지난 뒤에는 (흔들기를 빼면) 동쪽(90°)을 본다.
        val k = ride.indices.minBy { abs(ride[it].moveMs - 45_000L) }
        assertEquals(90.0, heading(cameras[ride[k].index], k), 1.0)
    }

    @Test
    fun bearingChangeIsRateLimited() {
        // 북쪽 300 m 뒤 그대로 남쪽으로 300 m(180° 꺾임). 흔들기를 뺀 진행 방향은 프레임마다 45/24°까지만 돈다.
        val timeline = path(*(List(61) { (it * 5.0) to 0.0 } + List(60) { (300.0 - (it + 1) * 5.0) to 0.0 }).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val headings = plans.of(SceneKind.RIDE).mapIndexed { k, plan -> heading(cameras[plan.index], k) }
        val limit = CameraPath.MAX_TURN_DEG_PER_S / FPS
        var largest = 0.0
        headings.zipWithNext().forEachIndexed { i, (a, b) ->
            val d = angleDiff(a, b)
            assertTrue(d <= limit + 1e-9, "주행 프레임 ${i + 1}: $d° > $limit°")
            largest = max(largest, d)
        }
        assertEquals(limit, largest, 1e-6) // 꺾이는 곳에서 상한에 걸린다
        assertEquals(0.0, headings.first(), 0.5)
        assertEquals(0.0, angleDiff(180.0, headings.last()), 1.0)
    }

    @Test
    fun bearingTurnsShortWayAcrossNorth() {
        // 350°로 300 m 뒤 10°로 300 m: 0/360을 지나 20°만 돈다.
        val first = listOf(0.0 to 0.0) + walk(0.0 to 0.0, 350.0, 60)
        val timeline = path(*(first + walk(first.last(), 10.0, 60)).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val headings = plans.of(SceneKind.RIDE).mapIndexed { k, plan -> heading(cameras[plan.index], k) }
        val limit = CameraPath.MAX_TURN_DEG_PER_S / FPS
        headings.zipWithNext().forEach { (a, b) -> assertTrue(angleDiff(a, b) <= limit + 1e-9, "$a → $b") }
        headings.forEach { assertTrue(angleDiff(0.0, it) <= 10.0 + 1e-6, "남쪽으로 돌아감: $it") }
        assertEquals(350.0, headings.first(), 0.5)
        assertEquals(10.0, headings.last(), 0.5)
        assertTrue(headings.any { it < 180.0 } && headings.any { it > 180.0 })
    }

    @Test
    fun lookAheadStopsAtSegmentEnd() {
        // 구간 0: 북쪽 100 m(0~20초). 구간 1: 동쪽 300 m 떨어진 곳에서 동쪽으로(20~60초). 500 m 안이라 평활은 이어진다.
        val seg0 = List(21) { RidePoint(it * 1_000L, at(it * 5.0, 0.0), null, it * 1_000L, 0, 0) }
        val seg1 = List(41) { RidePoint(20_000L + it * 1_000L, at(0.0, 300.0 + it * 5.0), null, 3_600_000L + it * 1_000L, 1, 0) }
        val timeline = RideTimeline(seg0 + seg1)
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.of(SceneKind.RIDE)
        // 구간 0에서는 다음 구간(동쪽)을 보지 않는다.
        ride.forEachIndexed { k, plan ->
            if (plan.moveMs < 20_000L) assertEquals(0.0, angleDiff(0.0, heading(cameras[plan.index], k)), 0.5)
        }
        // 경계를 지나면 카메라는 다음 구간 위치를 앞당겨 비추고, 방향은 동쪽으로 돈다.
        val after = ride.first { it.moveMs >= 20_000L }
        assertLeads(cameras[after.index], timeline.at(after.moveMs).point)
        assertEquals(90.0, heading(cameras[ride.last().index], ride.lastIndex), 1.0)
        // 경계 직후 방향은 끌려간다(평활을 다시 시작하지 않음).
        val k = ride.indexOf(after)
        assertTrue(angleDiff(heading(cameras[ride[k - 1].index], k - 1), heading(cameras[after.index], k)) <= CameraPath.MAX_TURN_DEG_PER_S / FPS + 1e-9)
    }

    @Test
    fun jumpsRestartSmoothing() {
        // 구간 0: 2 m/s로 북쪽 290초, 마지막 10초는 10 m/s(북 680 m). 구간 1: 그 끝에서 동쪽 600 m 떨어진 곳에서 10 m/s로 동쪽 60초.
        val seg0 = List(301) { i ->
            val n = if (i <= 290) i * 2.0 else 580.0 + (i - 290) * 10.0
            RidePoint(i * 1_000L, at(n, 0.0), null, i * 1_000L, 0, 0)
        }
        val seg1 = List(61) { RidePoint(300_000L + it * 1_000L, at(680.0, 600.0 + it * 10.0), null, 3_600_000L + it * 1_000L, 1, 0) }
        val timeline = RideTimeline(seg0 + seg1)
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.of(SceneKind.RIDE)
        val k = ride.indexOfFirst { it.moveMs >= 300_000L }
        val before = cameras[ride[k - 1].index]
        val jumped = cameras[ride[k].index]
        assertTrue(GeoMath.distanceM(timeline.at(ride[k - 1].moveMs).point, timeline.at(ride[k].moveMs).point) > CameraPath.JUMP_M)
        // 평활했다면 북쪽에서 조금만 돌았을 방향과, 막 빨라지기 시작해 아직 확대가 큰 상태.
        assertTrue(angleDiff(0.0, heading(before, k - 1)) < 1.0, "before ${before.bearing}")
        assertTrue(before.zoom > 17.0, "before zoom ${before.zoom}")
        // 뛰어넘은 첫 장: 그 자리의 목표 방향(동쪽 + 흔들기)과 그 속도(최근 8초 = 10 m/s = 36 km/h)의 확대 그대로.
        assertEquals(normalize(90.0 + sway(k)), jumped.bearing, 0.1)
        assertEquals(CameraPath.zoomForSpeed(36.0), jumped.zoom, 1e-6)
        assertLeads(jumped, timeline.at(ride[k].moveMs).point)
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
        plans.of(SceneKind.RIDE).forEachIndexed { k, plan ->
            val b = heading(cameras[plan.index], k)
            assertTrue(angleDiff(0.0, b) <= 5.0, "이동 ${plan.moveMs} ms 방향 $b")
        }
    }

    // --- intro, photo, outro ---

    @Test
    fun introLooksDownThenSpiralsIn() {
        // 북동(45°)으로 2 km: 전체 지도가 주행 확대보다 멀고, 첫 주행 방향이 북쪽이 아니다.
        val timeline = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 45.0, 400)).toTypedArray())
        val (plans, cameras, overview) = plansAndCameras(timeline)
        val high = plans.of(SceneKind.INTRO_OVERVIEW).map { cameras[it.index] }
        val descent = plans.of(SceneKind.INTRO_DESCENT).map { cameras[it.index] }
        val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
        assertEquals(45.0, firstRide.bearing, 0.5)

        // 하늘 위(규칙 F4): 제목이 떠 있는 동안 돌지 않는다. 전체 지도보다 한 단계 멀리, 정북, 위에서.
        high.forEach { assertEquals(Camera(overview.center, overview.zoom - 1, 0.0, 0.0), it) }

        // 내려가기: 하늘 위 장면에서 첫 주행 카메라로, 확대·기울기는 커지기만 한다.
        assertSameCamera(firstRide, descent.last(), "descent last")
        descent.zipWithNext().forEach { (a, b) ->
            assertTrue(b.tilt >= a.tilt, "tilt ${a.tilt} → ${b.tilt}")
            assertTrue(b.zoom >= a.zoom, "zoom ${a.zoom} → ${b.zoom}")
        }
        val mid = descent[descent.size / 2]
        assertTrue(mid.zoom > overview.zoom - 1 && mid.zoom < firstRide.zoom, "zoom ${mid.zoom}")
        assertTrue(mid.tilt > 0.0 && mid.tilt < CameraPath.TILT, "tilt ${mid.tilt}")
        assertTurnsTheShortWay(high.last(), descent, firstRide)
    }

    /** 첫 방향이 170°(+170° 돌기)나 200°(−160° 돌기)여도 내려가기는 짧은 쪽으로 한 방향으로만 돈다(규칙 F4). */
    @Test
    fun descentTurnsTheShortWayToAnyHeading() {
        for (heading in listOf(170.0, 200.0)) {
            val timeline = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, heading, 400)).toTypedArray())
            val (plans, cameras) = plansAndCameras(timeline)
            val high = plans.of(SceneKind.INTRO_OVERVIEW).map { cameras[it.index] }
            val descent = plans.of(SceneKind.INTRO_DESCENT).map { cameras[it.index] }
            val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
            assertEquals(heading, firstRide.bearing, 0.5)
            high.forEach { assertEquals(0.0, it.bearing, "$heading overview") }
            assertSameCamera(firstRide, descent.last(), "$heading descent last")
            assertTurnsTheShortWay(high.last(), descent, firstRide)
        }
    }

    /** [descent]의 방향이 [from]에서 [to]까지 짧은 쪽(|돌기| ≤ 180°)으로, 되돌아가지 않고 한 방향으로만 바뀐다. */
    private fun assertTurnsTheShortWay(from: Camera, descent: List<Camera>, to: Camera) {
        val total = signedDiff(from.bearing, to.bearing)
        assertTrue(abs(total) <= 180.0, "turn $total")
        // 지나온 각도(출발 방향 기준, 짧은 쪽 부호)가 0에서 total까지 단조롭게 간다.
        val turned = descent.map { signedDiff(from.bearing, it.bearing) * sign(total) }
        assertTrue(turned.first() >= 0.0, "first ${turned.first()}")
        turned.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-9, "bearing went back: $a → $b (turn $total)") }
        assertEquals(abs(total), turned.last(), 1e-6)
    }

    @Test
    fun photoSceneOrbitsFortyDegrees() {
        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline, listOf(PhotoCue(7, 30_000L, 30_000L)))
        val photo = plans.of(SceneKind.PHOTO)
        assertEquals(VideoTimeline.PHOTO_FRAMES, photo.size)
        val before = plans[photo.first().index - 1]
        assertEquals(SceneKind.RIDE, before.kind)
        val beforeCam = cameras[before.index]
        val first = cameras[photo.first().index]
        val last = cameras[photo.last().index]
        assertEquals(CameraPath.PHOTO_ORBIT_DEG, signedDiff(first.bearing, last.bearing), 0.5)
        photo.forEach {
            val cam = cameras[it.index]
            assertEquals(beforeCam.zoom, cam.zoom)
            assertEquals(beforeCam.tilt, cam.tilt)
            assertLeads(cam, timeline.at(it.moveMs).point, "photo ${it.index}")
        }
        // 사진이 끝나면 돈 방향에서 이어서 따라간다(되돌아 튀지 않음).
        val next = cameras[photo.last().index + 1]
        val step = CameraPath.MAX_TURN_DEG_PER_S / FPS + CameraPath.SWAY_DEG * 2 * PI / CameraPath.SWAY_PERIOD_S / FPS + 1e-6
        assertTrue(angleDiff(last.bearing, next.bearing) <= step, "${last.bearing} → ${next.bearing}")
    }

    @Test
    fun outroRisesToTheOutroCamera() {
        val timeline = lPath()
        val points = timeline.points.map { it.point }
        val outro = CameraPath.outro(points, 1080, 1920, OutroCardLayout.card(1080, 1920, 7).y)
        val (plans, cameras, overview) = plansAndCameras(timeline, outro = outro)
        val end = plans.of(SceneKind.OUTRO)
        assertEquals(VideoTimeline.OUTRO_FRAMES, end.size)
        val lastRide = cameras[plans.last { it.kind == SceneKind.RIDE }.index]
        assertEquals(lastRide, cameras[end[0].index])
        assertEquals(outro, cameras[end[VideoTimeline.OUTRO_RISE_FRAMES - 1].index])
        end.drop(VideoTimeline.OUTRO_RISE_FRAMES).forEach { assertEquals(outro, cameras[it.index]) }
        val mid = cameras[end[VideoTimeline.OUTRO_RISE_FRAMES / 2].index]
        assertTrue(mid.tilt > 0.0 && mid.tilt < lastRide.tilt, "tilt ${mid.tilt}")
        end.take(VideoTimeline.OUTRO_RISE_FRAMES).zipWithNext().forEach { (a, b) ->
            assertTrue(cameras[b.index].tilt <= cameras[a.index].tilt)
        }
        // 시작은 [overview] 쪽, 끝은 [outro] 쪽만 쓴다.
        assertEquals(overview.zoom - 1, cameras[plans.first().index].zoom)
    }

    @Test
    fun tinyAndStoppedRidesStayFinite() {
        val single = path(0.0 to 0.0)
        val short = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 30.0, 16)).toTypedArray()) // 80 m
        val stopped = path(*(List(11) { 0.0 to 0.0 } + walk(0.0 to 0.0, 0.0, 60)).toTypedArray()) // 첫 10초 정지
        for ((name, timeline) in listOf("single" to single, "80 m" to short, "stopped" to stopped)) {
            val (plans, cameras, overview) = plansAndCameras(timeline)
            assertTrue(overview.zoom <= CameraPath.ZOOM_SLOW, "$name overview ${overview.zoom}")
            cameras.forEachIndexed { i, cam ->
                val where = "$name 프레임 $i ${plans[i].kind}: $cam"
                listOf(cam.center.lat, cam.center.lon, cam.zoom, cam.bearing, cam.tilt).forEach { assertTrue(it.isFinite(), where) }
                assertTrue(cam.zoom >= overview.zoom - 1 - 1e-9 && cam.zoom <= CameraPath.ZOOM_SLOW + 1e-9, where)
                assertTrue(cam.tilt >= 0.0 && cam.tilt <= CameraPath.TILT, where)
                assertTrue(cam.bearing >= 0.0 && cam.bearing < 360.0, where)
            }
        }
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
        // 아주 짧은 경로는 가장 가까운 주행 확대보다 더 들어가지 않는다.
        val tiny = CameraPath.overview(listOf(at(0.0, 0.0), at(3.0, 0.0)), Aspect.PORTRAIT)
        assertEquals(CameraPath.ZOOM_SLOW, tiny.zoom)
        assertEquals(CameraPath.ZOOM_SLOW, CameraPath.overview(listOf(at(0.0, 0.0)), Aspect.LANDSCAPE).zoom)
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
}
