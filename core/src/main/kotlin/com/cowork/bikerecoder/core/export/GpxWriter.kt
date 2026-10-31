package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.Stop
import com.cowork.bikerecoder.core.post.HeartRateSample
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GPX 1.1 작성기(스펙 §4.3). `StringBuilder`로 직접 쓰며 새 의존성이 없다.
 * 숫자는 [Locale.ROOT]로 찍어 쉼표 소수점 로케일에서도 `37.1234567`로 쓴다.
 */
object GpxWriter {

    /** 점 시각과 심박 표본 시각이 이 값 이내(≤)일 때만 심박을 붙인다. */
    const val HR_WINDOW_MS = 5_000L

    private val utcFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * 여행 GPX. 구간마다 `trkseg` 하나(넘겨 준 순서). 좌표가 유한하지 않은 점은 건너뛴다.
     * 심박은 점 시각 ±[HR_WINDOW_MS] 안에서 가장 가까운 표본(같은 거리면 앞 표본).
     */
    fun trip(title: String, segments: List<ExportSegment>, creator: String, exportedAt: Long): String {
        val sb = StringBuilder(1024)
        header(sb, title, creator, exportedAt)
        sb.append("<trk><name>").append(escapeXml(title)).append("</name><type>cycling</type>\n")
        for (segment in segments) {
            sb.append("<trkseg>\n")
            val samples = segment.heartRate.sortedBy { it.timeMillis }
            for (p in segment.points) {
                if (!p.point.isFinitePoint()) continue
                trkptOpen(sb, p.point)
                p.altitudeM?.takeIf { it.isFinite() }?.let { sb.append("<ele>").append(formatElevation(it)).append("</ele>") }
                sb.append("<time>").append(utcFormat.format(Instant.ofEpochMilli(p.timeMillis))).append("</time>")
                nearestBpm(samples, p.timeMillis)?.let {
                    sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it)
                        .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                }
                sb.append("</trkpt>\n")
            }
            sb.append("</trkseg>\n")
        }
        sb.append("</trk>\n</gpx>\n")
        return sb.toString()
    }

    /**
     * 경로 GPX. 경유지·목적지는 `wpt`, 경로 선은 시간 없는 `trk` 하나.
     * [name]은 그대로 `metadata`와 `trk`의 이름이 된다(`ExportLabels.routeName`으로 만든 글). `route.elevationsM`의 길이가 점 수와
     * 같을 때만 점마다 `ele`를 쓰고, 그 점의 값이 null이거나 유한하지 않으면 그 점만 뺀다.
     */
    fun route(name: String, route: Route, stops: List<Stop>, creator: String, exportedAt: Long): String {
        val sb = StringBuilder(1024)
        header(sb, name, creator, exportedAt)
        for (stop in stops) {
            if (!stop.point.isFinitePoint()) continue
            sb.append("<wpt lat=\"").append(formatCoordinate(stop.point.lat)).append("\" lon=\"")
                .append(formatCoordinate(stop.point.lon)).append("\"><name>")
                .append(escapeXml(stop.name)).append("</name></wpt>\n")
        }
        sb.append("<trk><name>").append(escapeXml(name)).append("</name><type>cycling</type>\n<trkseg>\n")
        val elevations = route.elevationsM.takeIf { it.size == route.points.size }
        route.points.forEachIndexed { i, point ->
            if (!point.isFinitePoint()) return@forEachIndexed
            trkptOpen(sb, point)
            elevations?.get(i)?.takeIf { it.isFinite() }?.let { sb.append("<ele>").append(formatElevation(it)).append("</ele>") }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg>\n</trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun header(sb: StringBuilder, name: String, creator: String, exportedAt: Long) {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(escapeXml(creator))
            .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
            .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("<metadata><name>").append(escapeXml(name)).append("</name><time>")
            .append(utcFormat.format(Instant.ofEpochMilli(exportedAt))).append("</time></metadata>\n")
    }

    private fun trkptOpen(sb: StringBuilder, point: GeoPoint) {
        sb.append("<trkpt lat=\"").append(formatCoordinate(point.lat)).append("\" lon=\"")
            .append(formatCoordinate(point.lon)).append("\">")
    }

    /**
     * [samples](시간 오름차순)에서 [timeMillis]에 가장 가까운 표본의 bpm. 차이가 [HR_WINDOW_MS]를 넘으면 null.
     * 이분 탐색이라 점마다 O(log m). 같은 거리면 앞 표본.
     */
    internal fun nearestBpm(samples: List<HeartRateSample>, timeMillis: Long): Int? {
        if (samples.isEmpty()) return null
        // 시간이 timeMillis 이상인 첫 표본의 위치
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timeMillis < timeMillis) lo = mid + 1 else hi = mid
        }
        val before = if (lo > 0) samples[lo - 1] else null
        val after = if (lo < samples.size) samples[lo] else null
        val best = when {
            before == null -> after
            after == null -> before
            timeMillis - before.timeMillis <= after.timeMillis - timeMillis -> before
            else -> after
        } ?: return null
        val gap = if (best.timeMillis >= timeMillis) best.timeMillis - timeMillis else timeMillis - best.timeMillis
        return if (gap <= HR_WINDOW_MS) best.bpm else null
    }
}

internal fun GeoPoint.isFinitePoint(): Boolean = lat.isFinite() && lon.isFinite()
