package com.cowork.bikerecoder.core.gpx

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * GPX 트랙(`trkpt`)을 [LocationFix] 목록으로 읽는다(테스트·GPX 재생용).
 * 각 점은 lat/lon 속성과 ISO-8601 `<time>`을 가져야 한다. 정확도는 5m로 두고 속도·방향은 비운다.
 */
object GpxParser {

    private const val ACCURACY_M = 5f

    fun parse(gpx: String): List<LocationFix> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // 외부 엔티티(XXE) 차단. 지원하지 않는 파서(예: Android)에서는 건너뛴다.
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: ParserConfigurationException) {
            }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(gpx)))
        val trackPoints = document.getElementsByTagNameNS("*", "trkpt")

        return (0 until trackPoints.length).map { i ->
            val element = trackPoints.item(i) as Element
            val time = element.getElementsByTagNameNS("*", "time").item(0)?.textContent?.trim()
                ?: throw IllegalArgumentException("trkpt #$i has no <time>")
            LocationFix(
                point = GeoPoint(element.getAttribute("lat").toDouble(), element.getAttribute("lon").toDouble()),
                accuracyM = ACCURACY_M,
                speedMps = null,
                bearingDeg = null,
                timeMillis = Instant.parse(time).toEpochMilli(),
            )
        }
    }
}
