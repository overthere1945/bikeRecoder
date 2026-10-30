package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/** 지도 카메라 한 장. [bearing]은 0~360°(0 = 북), [tilt]는 0°(위에서) ~ 60°. */
data class Camera(val center: GeoPoint, val zoom: Double, val bearing: Double, val tilt: Double)

/** 프레임별 카메라(스펙 §2). */
object CameraPath {

    const val TILT = 60.0

    /** 주행 확대: [SLOW_KMH] 이하면 [ZOOM_SLOW], [FAST_KMH] 이상이면 [ZOOM_FAST], 그 사이는 직선. */
    const val ZOOM_SLOW = 17.4
    const val ZOOM_FAST = 16.6
    const val SLOW_KMH = 10.0
    const val FAST_KMH = 30.0

    /** 주행 확대가 영상 1초에 바뀌는 최대량. */
    const val ZOOM_RATE_PER_S = 0.25

    /** 속도를 재는 최근 이동 시간. */
    const val SPEED_WINDOW_MS = 8_000L
    const val LOOK_AHEAD_M = 120.0
    const val MAX_TURN_DEG_PER_S = 45.0

    /** 흔들기: 따라가는 방향에 ±[SWAY_DEG]°, 영상 시간 [SWAY_PERIOD_S]초 주기의 사인. */
    const val SWAY_DEG = 8.0
    const val SWAY_PERIOD_S = 16.0

    /**
     * 연속한 두 주행 프레임의 직선거리가 그 사이 경로로 지나온 거리보다 이만큼 넘게 길면(구간 사이를 건너뜀) 확대·방향 평활을
     * 다시 시작한다(판정 J2: 구간 사이 이동은 경로 거리 0으로 센다. 이어진 긴 주행은 프레임당 이동이 커도 평활을 유지).
     */
    const val JUMP_M = 500.0
    const val PHOTO_ORBIT_DEG = 40.0

    /** 현재 위치가 놓일 화면 높이(위에서부터의 비율). */
    const val MARKER_FROM_TOP = 2.0 / 3

    /** 지수 평활: 프레임마다 목표 방향과의 차이 중 이만큼만 따라간다(그 뒤 초당 [MAX_TURN_DEG_PER_S] 상한). */
    private const val SMOOTHING = 0.15

    /** 전체 경로 지도의 바깥 여백(각 변, 화면 크기 비율). */
    private const val OVERVIEW_MARGIN = 0.1

    /** MapLibre 타일 크기: zoom z에서 세계 폭 = 512·2^z 픽셀. */
    private const val TILE_SIZE = 512.0

    /** MapLibre: 카메라에서 화면 중심까지 거리 = 화면 높이 × 1.5(세로 시야 약 36.87°). */
    private const val CAMERA_DISTANCE_PER_HEIGHT = 1.5

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 앞을 보는 점까지 직선거리가 이보다 짧으면(경로 끝·멈춰 선 곳의 GPS 흔들림) 방향을 바꾸지 않는다. */
    private const val MIN_LOOK_M = 25.0

    /** 속도(km/h)에 맞는 주행 확대(스펙 §2.1). */
    fun zoomForSpeed(kmh: Double): Double {
        val t = ((kmh - SLOW_KMH) / (FAST_KMH - SLOW_KMH)).coerceIn(0.0, 1.0)
        return ZOOM_SLOW + (ZOOM_FAST - ZOOM_SLOW) * t
    }

    /**
     * 높이 [heightPx]인 화면에서 확대 [zoom]·기울기 [tiltDeg]일 때, 화면 위에서 [markerFromTop] 높이의 땅이 화면 중심의 땅보다
     * 카메라 쪽(아래)으로 몇 m인지. 그만큼 카메라 중심을 진행 방향으로 앞에 두면 현재 위치가 그 높이에 온다.
     * 카메라는 중심에서 1.5h 떨어져 있고 초점 거리도 1.5h다: 픽셀 = 1.5h·(sin p − cos p·tan(p − atan((m − 0.5)h / 1.5h))).
     */
    fun markerLeadM(zoom: Double, tiltDeg: Double, heightPx: Int, latDeg: Double, markerFromTop: Double = MARKER_FROM_TOP): Double {
        val distance = CAMERA_DISTANCE_PER_HEIGHT * heightPx
        val p = Math.toRadians(tiltDeg)
        val below = atan((markerFromTop - 0.5) * heightPx / distance)
        val px = distance * (sin(p) - cos(p) * tan(p - below))
        return px * EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latDeg)) / (TILE_SIZE * 2.0.pow(zoom))
    }

    /**
     * [plans]와 같은 길이(스펙 §2). [heightPx] = 찍는 지도 높이(현재 위치를 화면 아래 1/3에 두는 데 쓴다).
     * - RIDE: 그 이동 시각 위치에서 경로를 따라 120 m 앞을 보는 방향을 평활·초당 45° 상한으로 따라가고(첫 주행 프레임은 바로 그 방향),
     *   흔들기(±8°, 영상 시간 = 지금까지의 주행 프레임 수 / 24초, 16초 주기)를 더한다. 확대는 최근 8초 속도의 [zoomForSpeed]를 초당 0.25까지만
     *   따라간다. 앞 주행 프레임과의 직선거리가 경로로 지나온 거리보다 500 m 넘게 길면(구간 사이를 건너뜀, 판정 J2) 방향·확대를
     *   바로 목표값으로 둔다. 중심은 [markerLeadM]만큼 진행 방향 앞.
     * - INTRO_OVERVIEW: [overview] 중심, 확대 − 1, 기울기 0, 정북 그대로(규칙 F4: 제목 카드가 떠 있는 동안 돌지 않는다).
     * - INTRO_DESCENT: 그 카메라에서 첫 주행 카메라로 smoothstep 보간. 방향은 정북에서 첫 주행 방향까지 짧은 쪽(|돌기| ≤ 180°).
     * - PHOTO: 그 이동 시각의 주행 카메라를 (사진 시작 방향 + 40° × 진행)으로, 확대·기울기는 직전 장 그대로. 끝나면 그 방향에서 다시 따라간다.
     * - OUTRO: 끝 직전 카메라(마지막 주행 장, 끝에 사진이 있으면 그 사진 장)에서 [outro]로 [VideoTimeline.OUTRO_RISE_FRAMES]장 동안
     *   smoothstep, 그 뒤 [outro] 그대로.
     */
    fun cameras(
        plans: List<FramePlan>,
        timeline: RideTimeline,
        overview: Camera,
        outro: Camera = overview,
        heightPx: Int,
    ): List<Camera> {
        val route = timeline.routeDistance
        val segmentEnd = segmentEnds(timeline)
        val maxTurn = MAX_TURN_DEG_PER_S / FPS
        val maxZoomStep = ZOOM_RATE_PER_S / FPS

        fun lookBearing(located: Located, previous: Double): Double {
            val ahead = pointAt(timeline, route.cumulative, route.at(located) + LOOK_AHEAD_M, segmentEnd[located.doneIndex])
            return if (GeoMath.distanceM(located.point, ahead) < MIN_LOOK_M) previous else bearing(located.point, ahead)
        }

        fun speedKmh(moveMs: Long): Double {
            val from = max(0L, moveMs - SPEED_WINDOW_MS)
            if (moveMs <= from) return 0.0
            return (route.atMove(moveMs) - route.atMove(from)) / ((moveMs - from) / 1000.0) * 3.6
        }

        fun sway(rideFrames: Int) = SWAY_DEG * sin(2 * PI * rideFrames / FPS / SWAY_PERIOD_S)

        fun riding(at: GeoPoint, zoom: Double, bearing: Double) =
            Camera(ahead(at, bearing, markerLeadM(zoom, TILT, heightPx, at.lat)), zoom, bearing, TILT)

        val firstRideMs = plans.firstOrNull { it.kind == SceneKind.RIDE }?.moveMs ?: 0L
        val firstLocated = timeline.at(firstRideMs)
        val firstBearing = lookBearing(firstLocated, overview.bearing)
        val firstRide = riding(firstLocated.point, zoomForSpeed(speedKmh(firstRideMs)), firstBearing)
        val high = Camera(overview.center, (overview.zoom - 1).coerceAtLeast(0.0), bearing = 0.0, tilt = 0.0)

        val out = ArrayList<Camera>(plans.size)
        var heading: Double? = null // 평활해 따라가는 진행 방향(흔들기 전)
        var zoom = firstRide.zoom
        var rideFrames = 0
        var lastRidePoint: GeoPoint? = null
        var lastRideAlong = 0.0 // 앞 주행 프레임의 경로상 누적 거리(구간 사이는 0으로 셈)
        var photoStart = firstBearing
        var outroFrame = 0
        var riseFrom = outro
        for (plan in plans) {
            out += when (plan.kind) {
                // 규칙 F4: 제목 카드가 떠 있는 동안은 돌지 않는다(화면 가운데를 축으로 돌면 경로가 제목 위로 쓸려 온다).
                SceneKind.INTRO_OVERVIEW -> high
                // 도는 것은 내려가기만: 정북에서 첫 주행 방향까지 짧은 쪽으로(signedDelta), 확대·기울기·중심과 같은 smoothstep.
                SceneKind.INTRO_DESCENT -> interpolate(high, firstRide, smoothstep(plan.sceneProgress.toDouble()))
                SceneKind.PHOTO -> {
                    val before = out.lastOrNull() ?: firstRide
                    if (plan.sceneProgress == 0f) photoStart = before.bearing
                    val bearing = normalize(photoStart + PHOTO_ORBIT_DEG * plan.sceneProgress)
                    // 사진 뒤 첫 주행 장이 이 방향에서 이어지도록(흔들기는 다시 더해진다).
                    heading = normalize(bearing - sway(rideFrames))
                    riding(timeline.at(plan.moveMs).point, before.zoom, bearing)
                }
                SceneKind.RIDE -> {
                    val located = timeline.at(plan.moveMs)
                    val previous = lastRidePoint
                    val along = route.at(located)
                    val travelled = (along - lastRideAlong).coerceAtLeast(0.0)
                    val jumped = previous != null && GeoMath.distanceM(previous, located.point) > JUMP_M + travelled
                    val current = heading // 첫 주행 장 앞에 사진이 있었으면 그 사진이 돈 방향
                    val wanted = lookBearing(located, current ?: firstBearing)
                    val next = if (current == null || jumped) wanted
                    else normalize(current + (signedDelta(current, wanted) * SMOOTHING).coerceIn(-maxTurn, maxTurn))
                    val wantedZoom = zoomForSpeed(speedKmh(plan.moveMs))
                    zoom = if (previous == null || jumped) wantedZoom else zoom + (wantedZoom - zoom).coerceIn(-maxZoomStep, maxZoomStep)
                    heading = next
                    lastRidePoint = located.point
                    lastRideAlong = along
                    riding(located.point, zoom, normalize(next + sway(rideFrames++)))
                }
                SceneKind.OUTRO -> {
                    if (outroFrame == 0) riseFrom = out.lastOrNull() ?: outro
                    val t = outroFrame++.toDouble() / max(1, VideoTimeline.OUTRO_RISE_FRAMES - 1)
                    interpolate(riseFrom, outro, smoothstep(t))
                }
            }
        }
        return out
    }

    /** 영상 화면 전체([aspect] 크기)에 맞춘 [overview]. */
    fun overview(points: List<GeoPoint>, aspect: Aspect): Camera = overview(points, aspect.width, aspect.height)

    /**
     * 전체 경로가 [widthPx]×[heightPx] 지도(기울기 0, 북쪽 위)에 각 변 10% 여백을 두고 들어오는 카메라. 중심은 메르카토르 상의 가운데.
     * zoom은 지도 1픽셀 = 스냅샷 1픽셀(pixel ratio 1) 기준이고 [ZOOM_SLOW]보다 크지 않다. 요약 이미지의 지도 칸·720p 영상처럼
     * 실제로 찍는 지도 크기를 넘긴다(규칙 V5).
     */
    fun overview(points: List<GeoPoint>, widthPx: Int, heightPx: Int): Camera =
        overview(points, widthPx, heightPx, marginX = OVERVIEW_MARGIN * widthPx, marginY = OVERVIEW_MARGIN * heightPx)

    /**
     * [overview]이되 여백이 비율이 아니라 각 변 [marginPx] 픽셀이다(피드 그림: 1280×720에 64 px). 여백이 크기의 절반 이상이면
     * 쓸 수 있는 칸이 1 px로 남는 것으로 본다.
     */
    fun overviewInset(points: List<GeoPoint>, widthPx: Int, heightPx: Int, marginPx: Int): Camera =
        overview(points, widthPx, heightPx, marginX = marginPx.toDouble(), marginY = marginPx.toDouble())

    private fun overview(points: List<GeoPoint>, widthPx: Int, heightPx: Int, marginX: Double, marginY: Double): Camera {
        require(points.isNotEmpty()) { "빈 경로" }
        require(widthPx > 0 && heightPx > 0) { "지도 크기 ${widthPx}x$heightPx" }
        val xs = points.map { mercatorX(it.lon) }
        val ys = points.map { mercatorY(it.lat) }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        fun fit(span: Double, usablePx: Double) =
            if (span <= 0.0) Double.POSITIVE_INFINITY else log2(max(1.0, usablePx) / (span * TILE_SIZE))
        val zoom = min(min(fit(spanX, widthPx - 2 * marginX), fit(spanY, heightPx - 2 * marginY)), ZOOM_SLOW).coerceAtLeast(0.0)
        val center = GeoPoint(latitudeOf((ys.max() + ys.min()) / 2), longitudeOf((xs.max() + xs.min()) / 2))
        return Camera(center, zoom, bearing = 0.0, tilt = 0.0)
    }

    /**
     * 끝 장면 카메라: [widthPx]×[heightPx] 화면에서 요약 카드 위쪽 칸(0 ~ [cardTopPx])에 전체 경로가 [overview]처럼
     * 각 변 10% 여백을 두고 들어오게 한다. 확대는 그 칸 크기로 맞추고, 중심은 카드 높이만큼(픽셀 → 메르카토르) 남쪽으로
     * 옮겨 경로가 칸 가운데 오게 한다. 기울기 0, 북쪽 위.
     */
    fun outro(points: List<GeoPoint>, widthPx: Int, heightPx: Int, cardTopPx: Float): Camera {
        val boxHeight = cardTopPx.toInt().coerceIn(1, heightPx)
        val fitted = overview(points, widthPx, boxHeight)
        val world = TILE_SIZE * 2.0.pow(fitted.zoom)
        val shiftPx = (heightPx - boxHeight) / 2.0
        val center = GeoPoint(latitudeOf(mercatorY(fitted.center.lat) + shiftPx / world), fitted.center.lon)
        return fitted.copy(center = center)
    }

    /** 지점마다 그 지점이 속한 구간의 마지막 지점 인덱스(앞을 볼 때 다음 구간으로 건너가지 않는다). */
    private fun segmentEnds(timeline: RideTimeline): IntArray {
        val points = timeline.points
        val ends = IntArray(points.size)
        for (i in points.indices.reversed()) {
            ends[i] = if (i < points.lastIndex && points[i + 1].segmentIndex == points[i].segmentIndex) ends[i + 1] else i
        }
        return ends
    }

    /** 누적 거리 [distanceM] 지점(인덱스 [lastIndex]까지만, 넘으면 그 지점). */
    private fun pointAt(timeline: RideTimeline, cumulative: DoubleArray, distanceM: Double, lastIndex: Int): GeoPoint {
        val points = timeline.points
        if (distanceM >= cumulative[lastIndex]) return points[lastIndex].point
        var lo = 0
        var hi = lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= distanceM) lo = mid else hi = mid
        }
        val a = points[lo].point
        val b = points[hi].point
        val span = cumulative[hi] - cumulative[lo]
        val f = if (span <= 0.0) 0.0 else ((distanceM - cumulative[lo]) / span).coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun interpolate(a: Camera, b: Camera, t: Double): Camera = if (t <= 0.0) a else if (t >= 1.0) b else Camera(
        center = GeoPoint(a.center.lat + (b.center.lat - a.center.lat) * t, a.center.lon + (b.center.lon - a.center.lon) * t),
        zoom = a.zoom + (b.zoom - a.zoom) * t,
        bearing = normalize(a.bearing + signedDelta(a.bearing, b.bearing) * t),
        tilt = a.tilt + (b.tilt - a.tilt) * t,
    )

    private fun smoothstep(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** a → b 초기 방위각(0~360°). */
    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** [from]에서 방위 [bearingDeg]로 [distanceM] 간 점(대원). */
    private fun ahead(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val dLon = atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(Math.toDegrees(lat2), from.lon + Math.toDegrees(dLon))
    }

    /** from에서 to로 가는 가장 짧은 회전(-180~180°). */
    private fun signedDelta(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180

    private fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360

    private fun mercatorX(lon: Double) = (lon + 180.0) / 360.0
    private fun mercatorY(lat: Double): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }
    private fun longitudeOf(x: Double) = x * 360.0 - 180.0
    private fun latitudeOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
}
