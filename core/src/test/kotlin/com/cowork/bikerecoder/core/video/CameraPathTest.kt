package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.activity.ActivityProfile
import com.cowork.bikerecoder.core.activity.ActivityType
import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CameraPathTest {

    private val lat0 = 37.5
    private val lon0 = 127.0
    private val earthR = 6_371_008.8
    private val earthC = 40_075_016.686

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

    /** 1초에 [stepM] m씩, [legM]마다 북동 30° ↔ 북서 −30°로 꺾이는 [totalM] m 지그재그(굽은 곳과 곧은 곳이 번갈아 빠르기가 바뀐다). */
    private fun zigzag(totalM: Double, stepM: Double, legM: Double = 300.0): RideTimeline {
        val ne = ArrayList<Pair<Double, Double>>()
        ne += 0.0 to 0.0
        var leg = 0
        while (ne.size * stepM < totalM) {
            ne += walk(ne.last(), if (leg++ % 2 == 0) 30.0 else -30.0, (legM / stepM).roundToInt(), stepM)
        }
        return path(*ne.toTypedArray())
    }

    private fun signedDiff(from: Double, to: Double) = ((to - from) % 360 + 540) % 360 - 180
    private fun angleDiff(a: Double, b: Double) = abs(signedDiff(a, b))
    private fun normalize(deg: Double) = ((deg % 360) + 360) % 360
    private fun smoothstep(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** 주행 프레임 [k](0부터)의 도는 시점: 이동 장면 시간 k / [fps]초의 사인. */
    private fun orbit(k: Int, fps: Int = FPS) = CameraPath.ORBIT_DEG * sin(2 * PI * k / fps / CameraPath.ORBIT_PERIOD_S)

    /** 도는 시점을 뺀 방향(평활해 따라가는 진행 방향). */
    private fun heading(cam: Camera, k: Int) = normalize(cam.bearing - orbit(k))

    /** 주행 프레임 사이 방향이 바뀔 수 있는 최대량: 따라가기 상한 + 도는 시점의 가장 빠른 변화. */
    private val bearingStep = CameraPath.MAX_TURN_DEG_PER_S / FPS + CameraPath.ORBIT_DEG * 2 * PI / CameraPath.ORBIT_PERIOD_S / FPS + 1e-6

    /** 지도 1 m가 확대 [zoom]·위도 [lat]에서 몇 px인지. */
    private fun pxPerM(zoom: Double, lat: Double) = 512.0 * 2.0.pow(zoom) / (earthC * cos(Math.toRadians(lat)))

    private fun midLat(timeline: RideTimeline) = timeline.points.map { it.point.lat }.let { (it.min() + it.max()) / 2 }

    private fun plansAndCameras(
        timeline: RideTimeline,
        photos: List<PhotoCue> = emptyList(),
        outro: Camera? = null,
        width: Int = Aspect.PORTRAIT.width,
        height: Int = Aspect.PORTRAIT.height,
        activities: List<ActivityType> = emptyList(),
    ): Triple<List<FramePlan>, List<Camera>, Camera> {
        val plans = VideoTimeline.plan(timeline, photos, VideoOptions(length = VideoLength.SHORT), emptyList())
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        val cameras = CameraPath.cameras(plans, timeline, overview, outro ?: overview, widthPx = width, heightPx = height, segmentActivities = activities)
        assertEquals(plans.size, cameras.size)
        return Triple(plans, cameras, overview)
    }

    /**
     * 초당 [fps]장의 계획: 하늘 위·내려가기, 이동 장면 [seconds]초(장 k의 빠르기 = [speed](k / fps초), 거리는 평균 빠르기 × 빠르기로 쌓는다), 끝.
     * 장면 길이는 초로 같으니 장 수만 [fps]를 따른다.
     */
    private fun ridePlans(timeline: RideTimeline, seconds: Double, fps: Int = FPS, speed: (Double) -> Double = { 1.0 }): List<FramePlan> {
        val route = timeline.routeDistance
        val v = route.total / seconds
        val out = ArrayList<FramePlan>()
        fun add(kind: SceneKind, k: Int, of: Int, moveMs: Long = 0L, along: Double = 0.0, s: Double = 1.0) {
            val progress = if (of > 1) k.toFloat() / (of - 1) else 0f
            out += FramePlan(out.size, kind, moveMs, progress, k, of, null, null, 0f, false, alongM = along, speed = s.toFloat())
        }
        fun frames(s: Double) = (s * fps).roundToInt()
        repeat(frames(VideoTimeline.OVERVIEW_SECONDS)) { add(SceneKind.INTRO_OVERVIEW, it, frames(VideoTimeline.OVERVIEW_SECONDS)) }
        repeat(frames(VideoTimeline.DESCENT_SECONDS)) { add(SceneKind.INTRO_DESCENT, it, frames(VideoTimeline.DESCENT_SECONDS)) }
        val n = frames(seconds)
        var along = 0.0
        repeat(n) { k ->
            val s = speed(k.toDouble() / fps)
            add(SceneKind.RIDE, k, n, route.moveAt(along), along, s)
            along = (along + v * s / fps).coerceAtMost(route.total)
        }
        val outro = frames(VideoTimeline.OUTRO_SECONDS)
        repeat(outro) { add(SceneKind.OUTRO, it, outro, timeline.totalMoveMs, route.total) }
        return out
    }

    private fun camerasFor(timeline: RideTimeline, plans: List<FramePlan>, fps: Int = FPS): List<Camera> {
        val overview = CameraPath.overview(timeline.points.map { it.point }, Aspect.PORTRAIT)
        return CameraPath.cameras(plans, timeline, overview, overview, Aspect.PORTRAIT.width, Aspect.PORTRAIT.height, emptyList(), fps)
    }

    private fun List<FramePlan>.of(kind: SceneKind) = filter { it.kind == kind }

    /** [plans]의 영상 기준 확대 z0(스펙 §4.1): 이동 장면 길이 = 이동 장면 장 수 / FPS. */
    private fun baseZoomOf(timeline: RideTimeline, plans: List<FramePlan>, type: ActivityType = ActivityType.BIKE, shortSide: Int = 1080) =
        CameraPath.baseZoom(timeline.routeDistance.total, plans.of(SceneKind.RIDE).size.toDouble() / FPS, midLat(timeline), shortSide, type)

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

    // --- height (spec §4.1) ---

    @Test
    fun shortAndLongAreClamped() {
        // 스펙 §4.1의 예(위도 37.5°): 2 km 걷기 17.1, 5 km 라이딩 16.2는 범위 안, 1 km 걷기는 17.6, 50 km 라이딩은 14.8로 잘린다.
        fun z(km: Double, type: ActivityType) = CameraPath.baseZoom(km * 1000, VideoPace.rideSeconds(km), 37.5, 1080, type)
        assertEquals(17.1, z(2.0, ActivityType.WALK), 0.05)
        assertEquals(16.2, z(5.0, ActivityType.BIKE), 0.05)
        assertEquals(17.6, z(1.0, ActivityType.WALK), 1e-9)
        assertEquals(14.8, z(50.0, ActivityType.BIKE), 1e-9)
        // 종류마다 범위가 다르다: 같은 1 km라도 자전거는 16.8, 등산은 17.4에서 잘린다.
        assertEquals(16.8, z(1.0, ActivityType.BIKE), 1e-9)
        assertEquals(17.4, z(1.0, ActivityType.HIKE), 1e-9)
        assertEquals(15.6, z(50.0, ActivityType.RUN), 1e-9)
        // 움직이지 않은 기록(거리 0)은 가장 가깝게 본다.
        assertEquals(16.8, CameraPath.baseZoom(0.0, 4.0, 37.5, 1080, ActivityType.BIKE), 1e-9)
        assertEquals(16.8, CameraPath.baseZoom(100.0, 0.0, 37.5, 1080, ActivityType.BIKE), 1e-9)
    }

    @Test
    fun landscapeUsesShortSide() {
        // 2 km 걷기는 범위 안(17.1): 가로 1920×1080 영상도 세로 1080×1920과 같은 높이, 720p 대체 영상은 log2(1080/720)만큼 멀리.
        val timeline = zigzag(2_000.0, 1.4)
        val walk = listOf(ActivityType.WALK)
        val (plans, portrait) = plansAndCameras(timeline, activities = walk)
        val landscape = plansAndCameras(timeline, width = 1920, height = 1080, activities = walk).second
        val small = plansAndCameras(timeline, width = 720, height = 1280, activities = walk).second
        val rides = plans.of(SceneKind.RIDE)
        rides.forEach { assertEquals(portrait[it.index].zoom, landscape[it.index].zoom, 1e-9, "frame ${it.index}") }
        rides.forEach { assertEquals(portrait[it.index].zoom - log2(1080.0 / 720.0), small[it.index].zoom, 0.01, "frame ${it.index}") }
        val z0 = baseZoomOf(timeline, plans, ActivityType.WALK)
        assertEquals(z0 + CameraPath.zoomOffset(rides.first().speed.toDouble()), portrait[rides.first().index].zoom, 1e-9)
        assertEquals(z0 - log2(1080.0 / 720.0), baseZoomOf(timeline, plans, ActivityType.WALK, shortSide = 720), 1e-9)
    }

    @Test
    fun zoomWithinTheTypeRange() {
        for ((type, timeline) in listOf(ActivityType.WALK to zigzag(2_000.0, 1.4), ActivityType.BIKE to zigzag(5_000.0, 5.0))) {
            val profile = ActivityProfile.of(type)
            val (plans, cameras) = plansAndCameras(timeline, activities = listOf(type))
            plans.of(SceneKind.RIDE).forEach {
                val zoom = cameras[it.index].zoom
                assertTrue(
                    zoom >= profile.cameraZoomMin + CameraPath.FAST_ZOOM - 1e-9 && zoom <= profile.cameraZoomMax + CameraPath.SLOW_ZOOM + 1e-9,
                    "$type frame ${it.index}: zoom $zoom",
                )
            }
        }
        // 걷기 1 km는 걷기 범위 끝 17.6에서, 같은 경로를 자전거로 하면 16.8에서 잘린다: 걷기가 더 가깝다.
        val km = zigzag(1_000.0, 1.4)
        val (plans, walking) = plansAndCameras(km, activities = listOf(ActivityType.WALK))
        val biking = plansAndCameras(km, activities = listOf(ActivityType.BIKE)).second
        plans.of(SceneKind.RIDE).forEach { assertEquals(0.8, walking[it.index].zoom - biking[it.index].zoom, 1e-6) }
    }

    @Test
    fun mixedTypesUseTheLongest() {
        // 1 km를 두 구간으로(뒤 구간은 앞 구간 끝에서 이어진다). 가장 길게 간 종류의 범위(1 km 걷기 17.6, 자전거 16.8)를 영상 전체에 쓴다.
        fun split(firstSteps: Int): RideTimeline {
            val first = listOf(0.0 to 0.0) + walk(0.0 to 0.0, 0.0, firstSteps)
            val second = walk(first.last(), 0.0, 200 - firstSteps)
            return RideTimeline((first + second).mapIndexed { i, (n, e) -> RidePoint(i * 1_000L, at(n, e), null, i * 1_000L, if (i <= firstSteps) 0 else 1, 0) })
        }
        fun cams(timeline: RideTimeline, vararg types: ActivityType) = plansAndCameras(timeline, activities = types.toList()).second
        val walkLonger = split(60) // 앞 구간 300 m, 뒤 구간 약 700 m
        val walking = cams(walkLonger, ActivityType.WALK, ActivityType.WALK)
        val biking = cams(walkLonger, ActivityType.BIKE, ActivityType.BIKE)
        assertNotEquals(walking, biking)
        assertEquals(walking, cams(walkLonger, ActivityType.BIKE, ActivityType.WALK))
        assertEquals(biking, cams(walkLonger, ActivityType.WALK, ActivityType.BIKE))
        // 거리가 같으면(둘 다 제자리 0 m) 첫 구간의 종류.
        val even = RideTimeline(List(6) { RidePoint(it * 1_000L, at(0.0, 0.0), null, it * 1_000L, it / 3, 0) })
        assertEquals(cams(even, ActivityType.BIKE, ActivityType.BIKE), cams(even, ActivityType.BIKE, ActivityType.WALK))
        assertEquals(cams(even, ActivityType.WALK, ActivityType.WALK), cams(even, ActivityType.WALK, ActivityType.BIKE))
        // 종류를 안 넘기면 자전거.
        assertEquals(biking, cams(walkLonger))
    }

    @Test
    fun screenSpeedNear350() {
        // 2 km 걷기, 5 km 라이딩(세로 1080): 현재 위치가 1초에 화면에서 가는 거리의 평균이 약 350 px(±30 %).
        for ((type, timeline) in listOf(ActivityType.WALK to zigzag(2_000.0, 1.4), ActivityType.BIKE to zigzag(5_000.0, 5.0))) {
            val (plans, cameras) = plansAndCameras(timeline, activities = listOf(type))
            val perSecond = plans.of(SceneKind.RIDE).zipWithNext().map { (a, b) ->
                val p = timeline.at(a.moveMs).point
                GeoMath.distanceM(p, timeline.at(b.moveMs).point) * pxPerM(cameras[a.index].zoom, p.lat) * FPS
            }
            val mean = perSecond.average()
            assertTrue(mean in 350.0 * 0.7..350.0 * 1.3, "$type mean $mean px/s")
        }
    }

    // --- pace (spec §4.2) ---

    @Test
    fun fastPartsHigherSlowPartsLower() {
        // 6초는 빠르기 0.5(느린 곳), 이어 3초는 2.0(빠른 곳). 상한에 걸려 따라간 뒤에는 느린 곳 z0 + 0.3·60°, 빠른 곳 z0 − 0.4·55°.
        val timeline = zigzag(3_000.0, 5.0)
        val plans = ridePlans(timeline, 9.0) { if (it < 6.0) 0.5 else 2.0 }
        val cameras = camerasFor(timeline, plans)
        val rides = plans.of(SceneKind.RIDE)
        val z0 = baseZoomOf(timeline, plans)
        val slow = cameras[rides[framesOf(6.0) - 1].index]
        val fast = cameras[rides.last().index]
        assertEquals(z0 + CameraPath.SLOW_ZOOM, slow.zoom, 1e-9)
        assertEquals(CameraPath.TILT, slow.tilt, 1e-9)
        assertEquals(z0 + CameraPath.FAST_ZOOM, fast.zoom, 1e-9)
        assertEquals(CameraPath.FAST_TILT, fast.tilt, 1e-9)
        assertTrue(fast.zoom < slow.zoom && fast.tilt < slow.tilt)
        // 그 사이(빠르기 1.125)는 직선으로 잇는다.
        assertEquals(-0.05, CameraPath.zoomOffset(1.125), 1e-9)
        assertEquals(57.5, CameraPath.tiltFor(1.125), 1e-9)
        assertEquals(CameraPath.SLOW_ZOOM, CameraPath.zoomOffset(0.1), 1e-9)
        assertEquals(CameraPath.FAST_TILT, CameraPath.tiltFor(4.0), 1e-9)
    }

    @Test
    fun zoomAndTiltRateLimited() {
        // 빠르기가 0.5 → 2.0 → 0.5로 한 번에 바뀌어도 확대는 초당 0.3, 기울기는 초당 4°까지만 바뀌고, 그 상한에 걸린다.
        val timeline = zigzag(3_000.0, 5.0)
        val plans = ridePlans(timeline, 9.0) { if (it in 3.0..6.0) 2.0 else 0.5 }
        assertRateLimited(plans, camerasFor(timeline, plans), engaged = true)
        // 실제 계획(굽은 곳·곧은 곳이 번갈아 빠르기가 바뀜)에서도 넘지 않는다.
        val (realPlans, realCameras) = plansAndCameras(zigzag(5_000.0, 5.0))
        assertRateLimited(realPlans, realCameras, engaged = false)
    }

    /** 이어진 두 주행 장 사이 |Δzoom| ≤ 0.3 / FPS, |Δtilt| ≤ 4 / FPS. [engaged]면 둘 다 그 상한에 닿는다(검사가 헛돌지 않음). */
    private fun assertRateLimited(plans: List<FramePlan>, cameras: List<Camera>, engaged: Boolean) {
        val zoomStep = CameraPath.ZOOM_RATE_PER_S / FPS
        val tiltStep = CameraPath.TILT_RATE_PER_S / FPS
        var largestZoom = 0.0
        var largestTilt = 0.0
        plans.zipWithNext().filter { (a, b) -> a.kind == SceneKind.RIDE && b.kind == SceneKind.RIDE }.forEach { (a, b) ->
            val dz = abs(cameras[b.index].zoom - cameras[a.index].zoom)
            val dt = abs(cameras[b.index].tilt - cameras[a.index].tilt)
            assertTrue(dz <= zoomStep + 1e-9, "frame ${b.index}: Δzoom $dz")
            assertTrue(dt <= tiltStep + 1e-9, "frame ${b.index}: Δtilt $dt")
            largestZoom = max(largestZoom, dz)
            largestTilt = max(largestTilt, dt)
        }
        if (engaged) {
            assertEquals(zoomStep, largestZoom, 1e-9)
            assertEquals(tiltStep, largestTilt, 1e-9)
        }
    }

    // --- direction (spec §4.3) ---

    @Test
    fun orbitSwingsTwentyFive() {
        // 북쪽으로 15 km 곧게, 이동 장면 25초: 방향 − 경로 방향(0°)이 ±25°로, 12초 주기로 돈다(3초에 +25°, 9초에 −25°, 15초에 +25°).
        val straight = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 0.0, 1500, 10.0)).toTypedArray())
        val plans = ridePlans(straight, 25.0)
        val cameras = camerasFor(straight, plans)
        val off = plans.of(SceneKind.RIDE).take(framesOf(24.0)).map { signedDiff(0.0, cameras[it.index].bearing) }
        assertTrue(off.max() in 23.0..27.0, "max ${off.max()}")
        assertTrue(off.min() in -27.0..-23.0, "min ${off.min()}")
        fun peakSecond(from: Double, to: Double, sign: Double) =
            (framesOf(from) until framesOf(to)).maxBy { off[it] * sign }.toDouble() / FPS
        assertEquals(3.0, peakSecond(0.0, 6.0, 1.0), 0.3)
        assertEquals(9.0, peakSecond(6.0, 12.0, -1.0), 0.3)
        assertEquals(15.0, peakSecond(12.0, 18.0, 1.0), 0.3)
    }

    @Test
    fun sameTimeSameBearingAt24And30() {
        // 굽은 길: 따라가기·초당 상한·도는 시점이 모두 시간 기준이라 초당 24장과 30장의 방향이 같은 때(1/6초마다)에 같다(< 2°).
        // 0.5° 안이어야 한다: 따라가기를 장마다 15%로 두면(시간 기준이 아니면) 약 1.5° 어긋난다.
        val timeline = zigzag(6_000.0, 5.0, legM = 400.0)
        val seconds = 12.0
        val at24 = ridePlans(timeline, seconds, fps = 24).let { plans -> plans.of(SceneKind.RIDE).map { camerasFor(timeline, plans, 24)[it.index] } }
        val at30 = ridePlans(timeline, seconds, fps = 30).let { plans -> plans.of(SceneKind.RIDE).map { camerasFor(timeline, plans, 30)[it.index] } }
        val headings = ArrayList<Double>()
        for (sixth in 0 until (seconds * 6).toInt()) {
            val a = at24[sixth * 4]
            val b = at30[sixth * 5]
            assertTrue(angleDiff(a.bearing, b.bearing) < 0.5, "${sixth / 6.0} s: ${a.bearing} vs ${b.bearing}")
            assertEquals(a.zoom, b.zoom, 1e-6, "${sixth / 6.0} s zoom")
            headings += normalize(b.bearing - orbit(sixth * 5))
        }
        // 길이 꺾여 방향이 실제로 바뀐다(따라가기를 거친다).
        assertTrue(headings.maxOf { angleDiff(headings.first(), it) } > 20.0, "headings $headings")
    }

    @Test
    fun turnsAreCapped() {
        // 직각: 북쪽 300 m 뒤 동쪽 300 m.
        val north = listOf(0.0 to 0.0) + walk(0.0 to 0.0, 0.0, 60)
        val corner = path(*(north + walk(north.last(), 90.0, 60)).toTypedArray())
        val (plans, cameras) = plansAndCameras(corner)
        plans.of(SceneKind.RIDE).zipWithNext().forEach { (a, b) ->
            val d = angleDiff(cameras[a.index].bearing, cameras[b.index].bearing)
            assertTrue(d <= bearingStep, "프레임 ${b.index}: $d° > $bearingStep°")
        }
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
    fun rideCameraLooksAheadTwoSeconds() {
        // 북쪽 50 m 뒤 동쪽 400 m. 첫 주행 장은 경로를 따라 max(60 m, 2초 동안 갈 거리) 앞을 본다(모퉁이를 지난 점).
        val timeline = path(*(List(11) { (it * 5.0) to 0.0 } + List(80) { 50.0 to (it + 1) * 5.0 }).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.of(SceneKind.RIDE)
        val first = ride.first()
        val cam = cameras[first.index]
        assertEquals(0L, first.moveMs)
        val v = timeline.routeDistance.total / (ride.size.toDouble() / FPS)
        val aheadM = max(CameraPath.LOOK_AHEAD_MIN_M, CameraPath.LOOK_AHEAD_S * v * first.speed)
        assertTrue(aheadM > 50.0 && aheadM < 450.0, "ahead $aheadM m")
        // 첫 장은 도는 시점 0.
        assertEquals(Math.toDegrees(atan2(aheadM - 50.0, 50.0)), cam.bearing, 0.5)
        assertEquals(baseZoomOf(timeline, plans) + CameraPath.zoomOffset(first.speed.toDouble()), cam.zoom, 1e-9)
        assertEquals(CameraPath.tiltFor(first.speed.toDouble()), cam.tilt, 1e-9)

        // 동쪽 직선에 들어가 한참 지난 뒤에는 (도는 시점을 빼면) 동쪽(90°)을 본다.
        val k = ride.indices.minBy { abs(ride[it].moveMs - 60_000L) }
        assertEquals(90.0, heading(cameras[ride[k].index], k), 1.0)
    }

    @Test
    fun bearingChangeIsRateLimited() {
        // 북쪽 300 m 뒤 그대로 남쪽으로 300 m(180° 꺾임). 도는 시점을 뺀 진행 방향은 프레임마다 45/30°까지만 돈다.
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
        // 평활했다면 북쪽에서 조금만 돌았을 방향.
        assertTrue(angleDiff(0.0, heading(before, k - 1)) < 1.0, "before ${before.bearing}")
        // 뛰어넘은 첫 장: 그 자리의 목표 방향(동쪽 + 도는 시점)과 그 빠르기의 확대·기울기 그대로.
        val speed = ride[k].speed.toDouble()
        assertEquals(normalize(90.0 + orbit(k)), jumped.bearing, 0.1)
        assertEquals(baseZoomOf(timeline, plans) + CameraPath.zoomOffset(speed), jumped.zoom, 1e-9)
        assertEquals(CameraPath.tiltFor(speed), jumped.tilt, 1e-9)
        assertLeads(jumped, timeline.at(ride[k].moveMs).point)
    }

    /**
     * 판정 J2: 한 구간으로 이어진 긴 주행을 짧게 만들면 주행 프레임 사이 직선거리가 500 m를 넘어도, 그만큼 경로를 따라
     * 지나왔으므로 건너뜀이 아니다. 방향·확대·기울기 평활이 끝까지 이어진다(판정 P7: 확대·기울기 검사는 상한에 닿아 헛돌지 않는다).
     */
    @Test
    fun longContinuousRideKeepsSmoothing() {
        // 50 km마다 ±60° 꺾이는 지그재그(북동 30° ↔ 북서 −30°) 104칸 = 5,200 km. 칸마다 초속 5 m와 10 m를 번갈아, 10초에 한 점.
        // 거리를 이만큼 키워야 빠르기 곡선의 천천히 가는 곳에서도 주행 프레임이 500 m 넘게 떨어진다. 꺾이는 곳마다 빠르기가 바뀐다.
        val points = ArrayList<RidePoint>()
        var n = 0.0
        var e = 0.0
        var t = 0L
        points += RidePoint(0L, at(n, e), null, 0L, 0, 0)
        repeat(104) { leg ->
            val r = Math.toRadians(if (leg % 2 == 0) 30.0 else -30.0)
            val step = if (leg % 2 == 0) 50.0 else 100.0
            repeat((50_000 / step).toInt()) {
                n += step * cos(r)
                e += step * sin(r)
                t += 10_000L
                points += RidePoint(t, at(n, e), null, t, 0, 0)
            }
        }
        val timeline = RideTimeline(points)
        val (plans, cameras) = plansAndCameras(timeline)
        val ride = plans.of(SceneKind.RIDE)
        ride.zipWithNext().forEach { (a, b) ->
            val d = GeoMath.distanceM(timeline.at(a.moveMs).point, timeline.at(b.moveMs).point)
            assertTrue(d > CameraPath.JUMP_M, "주행 프레임 ${b.index}: 직선 $d m")
        }

        val maxTurn = CameraPath.MAX_TURN_DEG_PER_S / FPS + 1e-9
        ride.zipWithNext().forEachIndexed { k, (a, b) ->
            val turn = angleDiff(heading(cameras[a.index], k), heading(cameras[b.index], k + 1))
            assertTrue(turn <= maxTurn, "주행 프레임 ${k + 1}: ${turn}° > $maxTurn°")
        }
        assertRateLimited(plans, cameras, engaged = true)
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

    // --- intro, photo, outro (spec §4.4) ---

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
        assertSameCamera(high.last(), descent.first(), "descent first")
        assertSameCamera(firstRide, descent.last(), "descent last")
        descent.zipWithNext().forEach { (a, b) ->
            assertTrue(b.tilt >= a.tilt, "tilt ${a.tilt} → ${b.tilt}")
            assertTrue(b.zoom >= a.zoom, "zoom ${a.zoom} → ${b.zoom}")
        }
        val mid = descent[descent.size / 2]
        assertTrue(mid.zoom > overview.zoom - 1 && mid.zoom < firstRide.zoom, "zoom ${mid.zoom}")
        assertTrue(mid.tilt > 0.0 && mid.tilt < firstRide.tilt, "tilt ${mid.tilt}")
        assertTurnsTheShortWay(high.last(), plans.of(SceneKind.INTRO_DESCENT), descent, firstRide)
    }

    @Test
    fun descentSpirals() {
        // 내려가기 한가운데는 정북 → 첫 방향의 짧은 쪽 보간에서 sin(π·½) × 30° = 30° 더 돌아 있다.
        val timeline = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, 45.0, 400)).toTypedArray())
        val (plans, cameras) = plansAndCameras(timeline)
        val descent = plans.of(SceneKind.INTRO_DESCENT)
        val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
        val mid = descent[descent.size / 2]
        val straight = normalize(signedDiff(0.0, firstRide.bearing) * smoothstep(mid.sceneProgress.toDouble()))
        assertEquals(CameraPath.DESCENT_SPIRAL_DEG, signedDiff(straight, cameras[mid.index].bearing), 3.0)
    }

    /** 첫 방향이 170°(+170° 돌기)나 200°(−160° 돌기)여도 내려가기는 짧은 쪽으로 한 방향으로만 돈다(규칙 F4, 감아 도는 몫을 빼고). */
    @Test
    fun descentTurnsTheShortWayToAnyHeading() {
        for (heading in listOf(170.0, 200.0)) {
            val timeline = path(*(listOf(0.0 to 0.0) + walk(0.0 to 0.0, heading, 400)).toTypedArray())
            val (plans, cameras) = plansAndCameras(timeline)
            val high = plans.of(SceneKind.INTRO_OVERVIEW).map { cameras[it.index] }
            val descentPlans = plans.of(SceneKind.INTRO_DESCENT)
            val descent = descentPlans.map { cameras[it.index] }
            val firstRide = cameras[plans.first { it.kind == SceneKind.RIDE }.index]
            assertEquals(heading, firstRide.bearing, 0.5)
            high.forEach { assertEquals(0.0, it.bearing, "$heading overview") }
            assertSameCamera(high.last(), descent.first(), "$heading descent first")
            assertSameCamera(firstRide, descent.last(), "$heading descent last")
            assertTurnsTheShortWay(high.last(), descentPlans, descent, firstRide)
        }
    }

    /**
     * [descent]의 방향에서 감아 도는 몫(sin(π·t) × 30°, t = 진행의 smoothstep)을 빼면 [from]에서 [to]까지 짧은 쪽(|돌기| ≤ 180°)으로,
     * 되돌아가지 않고 한 방향으로만 바뀐다.
     */
    private fun assertTurnsTheShortWay(from: Camera, plans: List<FramePlan>, descent: List<Camera>, to: Camera) {
        val total = signedDiff(from.bearing, to.bearing)
        assertTrue(abs(total) <= 180.0, "turn $total")
        // 지나온 각도(출발 방향 기준, 짧은 쪽 부호)가 0에서 total까지 단조롭게 간다.
        val turned = descent.mapIndexed { i, cam ->
            val spiral = CameraPath.DESCENT_SPIRAL_DEG * sin(PI * smoothstep(plans[i].sceneProgress.toDouble()))
            signedDiff(from.bearing, cam.bearing - spiral) * sign(total)
        }
        assertEquals(0.0, turned.first(), 1e-6)
        turned.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-6, "bearing went back: $a → $b (turn $total)") }
        assertEquals(abs(total), turned.last(), 1e-6)
    }

    @Test
    fun photoPushesIn() {
        val timeline = lPath()
        val (plans, cameras) = plansAndCameras(timeline, listOf(PhotoCue(7, 30_000L, 30_000L)))
        val photo = plans.of(SceneKind.PHOTO)
        assertEquals(VideoTimeline.PHOTO_FRAMES, photo.size)
        val before = plans[photo.first().index - 1]
        assertEquals(SceneKind.RIDE, before.kind)
        val beforeCam = cameras[before.index]
        val first = cameras[photo.first().index]
        val last = cameras[photo.last().index]
        // 그 자리의 주행 카메라에서 40° 돌며 확대 +0.3까지 다가간다(smoothstep), 기울기는 그대로.
        assertEquals(CameraPath.PHOTO_ORBIT_DEG, signedDiff(first.bearing, last.bearing), 0.5)
        assertEquals(beforeCam.zoom, first.zoom, 1e-9)
        assertEquals(first.zoom + CameraPath.PHOTO_PUSH_ZOOM, last.zoom, 0.01)
        val mid = photo[photo.size / 2]
        assertEquals(
            beforeCam.zoom + CameraPath.PHOTO_PUSH_ZOOM * smoothstep(mid.sceneProgress.toDouble()), cameras[mid.index].zoom, 1e-9,
        )
        photo.forEach {
            val cam = cameras[it.index]
            assertEquals(beforeCam.tilt, cam.tilt)
            assertLeads(cam, timeline.at(it.moveMs).point, "photo ${it.index}")
        }
        photo.zipWithNext().forEach { (a, b) -> assertTrue(cameras[b.index].zoom >= cameras[a.index].zoom) }
        // 사진이 끝나면 그 방향·확대에서 이어서 따라간다(되돌아 튀지 않음).
        val next = cameras[photo.last().index + 1]
        assertTrue(angleDiff(last.bearing, next.bearing) <= bearingStep, "${last.bearing} → ${next.bearing}")
        assertTrue(abs(next.zoom - last.zoom) <= CameraPath.ZOOM_RATE_PER_S / FPS + 1e-9, "${last.zoom} → ${next.zoom}")
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
        val bike = ActivityProfile.of(ActivityType.BIKE)
        for ((name, timeline) in listOf("single" to single, "80 m" to short, "stopped" to stopped)) {
            val (plans, cameras, overview) = plansAndCameras(timeline)
            assertTrue(overview.zoom <= CameraPath.OVERVIEW_MAX_ZOOM, "$name overview ${overview.zoom}")
            val lowest = minOf(overview.zoom - 1, bike.cameraZoomMin + CameraPath.FAST_ZOOM)
            cameras.forEachIndexed { i, cam ->
                val where = "$name 프레임 $i ${plans[i].kind}: $cam"
                listOf(cam.center.lat, cam.center.lon, cam.zoom, cam.bearing, cam.tilt).forEach { assertTrue(it.isFinite(), where) }
                assertTrue(cam.zoom >= lowest - 1e-9 && cam.zoom <= CameraPath.OVERVIEW_MAX_ZOOM + 1e-9, where)
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
        assertEquals(CameraPath.OVERVIEW_MAX_ZOOM, tiny.zoom)
        assertEquals(CameraPath.OVERVIEW_MAX_ZOOM, CameraPath.overview(listOf(at(0.0, 0.0)), Aspect.LANDSCAPE).zoom)
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
    fun overviewInsetKeepsTheMarginInPixels() {
        val tall = listOf(at(0.0, 0.0), at(20_000.0, -3_000.0), at(8_000.0, 5_000.0), at(-1_000.0, 1_000.0))
        val wide = listOf(at(0.0, 0.0), at(2_000.0, 30_000.0), at(-3_000.0, 12_000.0))
        val (w, h, margin) = Triple(1280, 720, 64)
        for (points in listOf(tall, wide)) {
            val cam = CameraPath.overviewInset(points, w, h, margin)
            assertEquals(0.0, cam.tilt)
            assertEquals(0.0, cam.bearing)
            val world = 512.0 * 2.0.pow(cam.zoom)
            val xs = points.map { (mercX(it.lon) - mercX(cam.center.lon)) * world + w / 2.0 }
            val ys = points.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + h / 2.0 }
            val eps = 1e-6
            xs.forEach { assertTrue(it >= margin - eps && it <= w - margin + eps, "x $it") }
            ys.forEach { assertTrue(it >= margin - eps && it <= h - margin + eps, "y $it") }
            val fill = max((xs.max() - xs.min()) / (w - 2.0 * margin), (ys.max() - ys.min()) / (h - 2.0 * margin))
            assertEquals(1.0, fill, 1e-6)
        }
        // A tiny route is not zoomed in past the riding zoom, as with [overview].
        assertEquals(CameraPath.OVERVIEW_MAX_ZOOM, CameraPath.overviewInset(listOf(at(0.0, 0.0), at(3.0, 0.0)), w, h, margin).zoom)
        // 10 % of a 500 px square is 50 px: the same camera as the ratio overview.
        assertEquals(CameraPath.overview(tall, 500, 500).zoom, CameraPath.overviewInset(tall, 500, 500, 50).zoom, 1e-9)
    }

    @Test
    fun overviewInBoxCentresRouteInBox() {
        val tall = listOf(at(0.0, 0.0), at(20_000.0, -3_000.0), at(8_000.0, 5_000.0), at(-1_000.0, 1_000.0))
        val wide = listOf(at(0.0, 0.0), at(2_000.0, 30_000.0), at(-3_000.0, 12_000.0))
        val (w, h) = 1080 to 1350
        val boxes = listOf(Box(65f, 250f, 950f, 690f), Box(0f, 0f, 1080f, 1350f), Box(300f, 800f, 400f, 400f))
        for (points in listOf(tall, wide)) for (box in boxes) {
            val cam = CameraPath.overviewInBox(points, w, h, box)
            assertEquals(0.0, cam.tilt)
            assertEquals(0.0, cam.bearing)
            val world = 512.0 * 2.0.pow(cam.zoom)
            val xs = points.map { (mercX(it.lon) - mercX(cam.center.lon)) * world + w / 2.0 }
            val ys = points.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + h / 2.0 }
            // The route's centre lands on the box centre ...
            assertEquals(box.x + box.w / 2.0, (xs.max() + xs.min()) / 2, 1.0)
            assertEquals(box.y + box.h / 2.0, (ys.max() + ys.min()) / 2, 1.0)
            // ... and the route fills the box without a margin (touching it on the limiting axis).
            val eps = 1e-6
            xs.forEach { assertTrue(it >= box.x - eps && it <= box.x + box.w + eps, "x $it") }
            ys.forEach { assertTrue(it >= box.y - eps && it <= box.y + box.h + eps, "y $it") }
            val fill = max((xs.max() - xs.min()) / box.w, (ys.max() - ys.min()) / box.h)
            assertEquals(1.0, fill, 1e-6)
        }
        // A tiny route is not zoomed in past the riding zoom, and still lands on the box centre.
        val tiny = listOf(at(0.0, 0.0), at(3.0, 0.0))
        val box = Box(65f, 250f, 950f, 690f)
        val cam = CameraPath.overviewInBox(tiny, w, h, box)
        assertEquals(CameraPath.OVERVIEW_MAX_ZOOM, cam.zoom)
        val world = 512.0 * 2.0.pow(cam.zoom)
        val y = tiny.map { (mercY(it.lat) - mercY(cam.center.lat)) * world + h / 2.0 }
        assertEquals(box.y + box.h / 2.0, (y.max() + y.min()) / 2, 1.0)
        // The full-canvas box is the plain fit without margin.
        assertEquals(CameraPath.overviewInset(tall, w, h, 0).zoom, CameraPath.overviewInBox(tall, w, h, Box(0f, 0f, 1080f, 1350f)).zoom, 1e-9)
    }
}
