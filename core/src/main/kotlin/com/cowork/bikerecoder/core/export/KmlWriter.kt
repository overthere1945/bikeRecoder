package com.cowork.bikerecoder.core.export

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.Stop

/**
 * KML 2.2 작성기(스펙 §4.4). 좌표는 `경도,위도,고도`(KML 순서)이고 고도를 모르는 점은 `경도,위도`만 쓴다.
 * 좌표가 유한하지 않은 점은 건너뛴다. 선은 점이 2개 이상일 때만 쓴다.
 */
object KmlWriter {

    private const val LINE_STYLE_ID = "ride"

    /** KML 색은 aabbggrr. 파랑 `ffff0000`(불투명 파랑). */
    private const val LINE_COLOR = "ffff0000"
    private const val LINE_WIDTH = 4

    /** 여행 KML. 구간마다 `{yyyy-MM-dd} 주행` 선, 첫 구간 시작점 `출발`, 마지막 구간 끝점 `도착`. */
    fun trip(title: String, segments: List<ExportSegment>): String {
        val sb = StringBuilder(1024)
        header(sb, title)
        val lines = segments.map { segment ->
            segment to segment.points.filter { it.point.isFinitePoint() }
        }
        for ((segment, points) in lines) {
            if (points.size < 2) continue
            line(sb, ExportLabels.rideName(segment.localDate), points.map { it.point to it.altitudeM })
        }
        val first = lines.firstOrNull { it.second.isNotEmpty() }?.second?.first()
        val last = lines.lastOrNull { it.second.isNotEmpty() }?.second?.last()
        if (first != null) pointMark(sb, ExportLabels.START, first.point)
        if (last != null) pointMark(sb, ExportLabels.END, last.point)
        footer(sb)
        return sb.toString()
    }

    /**
     * 경로 KML. 선 하나와 경유지·목적지 `Point`. [name]은 그대로 문서와 선의 이름이 된다(`ExportLabels.routeName`으로 만든 글).
     * `route.elevationsM`가 점 수와 같을 때만 고도를 쓴다.
     */
    fun route(name: String, route: Route, stops: List<Stop>): String {
        val sb = StringBuilder(1024)
        header(sb, name)
        val elevations = route.elevationsM.takeIf { it.size == route.points.size }
        val points = route.points.mapIndexedNotNull { i, p ->
            if (p.isFinitePoint()) p to elevations?.get(i) else null
        }
        if (points.size >= 2) line(sb, name, points)
        for (stop in stops) {
            if (stop.point.isFinitePoint()) pointMark(sb, stop.name, stop.point)
        }
        footer(sb)
        return sb.toString()
    }

    private fun header(sb: StringBuilder, name: String) {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>").append(escapeXml(name)).append("</name>\n")
        sb.append("<Style id=\"").append(LINE_STYLE_ID).append("\"><LineStyle><color>").append(LINE_COLOR)
            .append("</color><width>").append(LINE_WIDTH).append("</width></LineStyle></Style>\n")
    }

    private fun footer(sb: StringBuilder) {
        sb.append("</Document></kml>\n")
    }

    private fun line(sb: StringBuilder, name: String, points: List<Pair<GeoPoint, Double?>>) {
        sb.append("<Placemark><name>").append(escapeXml(name)).append("</name><styleUrl>#").append(LINE_STYLE_ID)
            .append("</styleUrl><LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>")
        points.forEachIndexed { i, (point, altitude) ->
            if (i > 0) sb.append(' ')
            coordinate(sb, point, altitude)
        }
        sb.append("</coordinates></LineString></Placemark>\n")
    }

    private fun pointMark(sb: StringBuilder, name: String, point: GeoPoint) {
        sb.append("<Placemark><name>").append(escapeXml(name)).append("</name><Point><coordinates>")
        coordinate(sb, point, null)
        sb.append("</coordinates></Point></Placemark>\n")
    }

    private fun coordinate(sb: StringBuilder, point: GeoPoint, altitude: Double?) {
        sb.append(formatCoordinate(point.lon)).append(',').append(formatCoordinate(point.lat))
        altitude?.takeIf { it.isFinite() }?.let { sb.append(',').append(formatElevation(it)) }
    }
}
