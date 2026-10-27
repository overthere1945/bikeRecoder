package com.cowork.bikerecoder.core.video

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/** 지도 카메라 한 장. [bearing]은 0~360°(0 = 북), [tilt]는 0°(위에서) ~ 55°. */
data class Camera(val center: GeoPoint, val zoom: Double, val bearing: Double, val tilt: Double)

/** 프레임별 카메라(스펙 §2.1, §2.2, §2.4). */
object CameraPath {

    const val TILT = 55.0
    const val ZOOM = 15.5
    const val LOOK_AHEAD_M = 150.0
    const val MAX_TURN_DEG_PER_S = 60.0

    /** 지수 평활: 프레임마다 목표 방향과의 차이 중 이만큼만 따라간다(그 뒤 초당 60° 상한). */
    private const val SMOOTHING = 0.15

    /** 전체 경로 지도의 바깥 여백(각 변, 화면 크기 비율). */
    private const val OVERVIEW_MARGIN = 0.1

    /** MapLibre 타일 크기: zoom z에서 세계 폭 = 512·2^z 픽셀. */
    private const val TILE_SIZE = 512.0

    /** 앞을 보는 점까지 직선거리가 이보다 짧으면(경로 끝·멈춰 선 곳의 GPS 흔들림) 방향을 바꾸지 않는다. */
    private const val MIN_LOOK_M = 25.0

    /**
     * [plans]와 같은 길이. INTRO_OVERVIEW·OUTRO = [overview], INTRO_DESCENT = overview → 첫 주행 카메라를 부드럽게(smoothstep) 보간,
     * RIDE = 그 이동 시각의 위치에서 경로를 따라 150 m 앞을 보는 방향을 평활·초당 60° 상한으로 따라감(첫 주행 프레임은 바로 그 방향),
     * PHOTO = 바로 앞 프레임 카메라 그대로.
     */
    fun cameras(plans: List<FramePlan>, timeline: RideTimeline, overview: Camera): List<Camera> {
        val route = timeline.routeDistance
        val segmentEnd = segmentEnds(timeline)
        val maxStep = MAX_TURN_DEG_PER_S / FPS

        fun target(moveMs: Long, previous: Double): Pair<GeoPoint, Double> {
            val located = timeline.at(moveMs)
            val ahead = pointAt(timeline, route.cumulative, route.at(located) + LOOK_AHEAD_M, segmentEnd[located.doneIndex])
            val bearing = if (GeoMath.distanceM(located.point, ahead) < MIN_LOOK_M) previous else bearing(located.point, ahead)
            return located.point to bearing
        }

        val firstRideMs = plans.firstOrNull { it.kind == SceneKind.RIDE }?.moveMs ?: 0L
        val (firstCenter, firstBearing) = target(firstRideMs, overview.bearing)
        val firstRide = Camera(firstCenter, ZOOM, firstBearing, TILT)

        val out = ArrayList<Camera>(plans.size)
        var bearing: Double? = null
        for (plan in plans) {
            out += when (plan.kind) {
                SceneKind.INTRO_OVERVIEW, SceneKind.OUTRO -> overview
                SceneKind.INTRO_DESCENT -> interpolate(overview, firstRide, smoothstep(plan.sceneProgress.toDouble()))
                SceneKind.PHOTO -> out.lastOrNull() ?: overview
                SceneKind.RIDE -> {
                    val (center, wanted) = target(plan.moveMs, bearing ?: firstBearing)
                    val next = bearing?.let { b ->
                        normalize(b + (signedDelta(b, wanted) * SMOOTHING).coerceIn(-maxStep, maxStep))
                    } ?: wanted
                    bearing = next
                    Camera(center, ZOOM, next, TILT)
                }
            }
        }
        return out
    }

    /** 영상 화면 전체([aspect] 크기)에 맞춘 [overview]. */
    fun overview(points: List<GeoPoint>, aspect: Aspect): Camera = overview(points, aspect.width, aspect.height)

    /**
     * 전체 경로가 [widthPx]×[heightPx] 지도(기울기 0, 북쪽 위)에 각 변 10% 여백을 두고 들어오는 카메라. 중심은 메르카토르 상의 가운데.
     * zoom은 지도 1픽셀 = 스냅샷 1픽셀(pixel ratio 1) 기준이고 [ZOOM]보다 크지 않다. 요약 이미지의 지도 칸·720p 영상처럼
     * 실제로 찍는 지도 크기를 넘긴다(규칙 V5).
     */
    fun overview(points: List<GeoPoint>, widthPx: Int, heightPx: Int): Camera {
        require(points.isNotEmpty()) { "빈 경로" }
        require(widthPx > 0 && heightPx > 0) { "지도 크기 ${widthPx}x$heightPx" }
        val xs = points.map { mercatorX(it.lon) }
        val ys = points.map { mercatorY(it.lat) }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        val usable = 1.0 - 2 * OVERVIEW_MARGIN
        fun fit(span: Double, size: Int) = if (span <= 0.0) Double.POSITIVE_INFINITY else log2(usable * size / (span * TILE_SIZE))
        val zoom = min(min(fit(spanX, widthPx), fit(spanY, heightPx)), ZOOM).coerceAtLeast(0.0)
        val center = GeoPoint(latitudeOf((ys.max() + ys.min()) / 2), longitudeOf((xs.max() + xs.min()) / 2))
        return Camera(center, zoom, bearing = 0.0, tilt = 0.0)
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
