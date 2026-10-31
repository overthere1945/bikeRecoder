package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.gpx.secureDocumentBuilderFactory
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.model.LocationFix
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.parsers.ParserConfigurationException
import java.time.DateTimeException
import java.time.OffsetDateTime

/** GPX를 읽지 못했다. [problem]이 이유(NOT_GPX, NO_TIME, UNREADABLE). */
class GpxReadException(val problem: ImportProblem) : Exception("GPX를 읽지 못했습니다: $problem")

/**
 * 읽은 GPX 트랙.
 * @property fixes 시간순으로 정렬한 점. 같은 `timeMillis`는 처음 것만 남긴다.
 * @property type 첫 `trk`의 `<type>`(앞뒤 공백 제거, 비면 null)
 * @property name 첫 `trk`의 `<name>`(앞뒤 공백 제거, 비면 null)
 * @property droppedPoints 버린 점 수(시간·좌표를 읽을 수 없는 점, 같은 시간의 뒤쪽 점). `totalPoints - fixes.size`.
 * @property totalPoints 파일의 `trkpt` 수
 */
data class GpxTrack(
    val fixes: List<LocationFix>,
    val type: String?,
    val name: String?,
    val droppedPoints: Int,
    val totalPoints: Int,
)

/**
 * 가져오기용 GPX 읽기(스펙 §3.2의 시간·좌표 규칙). 모든 `trk`/`trkseg`의 `trkpt`를 문서 순서대로 모은다.
 * 시간은 ISO-8601(`Z`·`+09:00` 모두), 정확도는 5m, 속도·방향은 비우고 `<ele>`는 altitudeM에 넣는다.
 * 읽지 못하면 [GpxReadException]. DOCTYPE(외부 엔티티)이 있는 파일은 읽지 않는다.
 */
object GpxReader {

    private const val ACCURACY_M = 5f

    fun read(bytes: ByteArray): GpxTrack {
        val document = try {
            val builder = secureDocumentBuilderFactory().newDocumentBuilder()
            // 파서가 DOCTYPE 차단을 지원하지 않아도 외부 자원은 읽지 않는다.
            builder.setEntityResolver { _, _ -> throw SAXException("외부 엔티티는 읽지 않습니다") }
            // 기본 핸들러는 표준 오류에 "[Fatal Error]"를 찍는다. 조용히 다시 던지기만 한다.
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) = Unit
                override fun error(e: SAXParseException) = throw e
                override fun fatalError(e: SAXParseException) = throw e
            })
            val start = contentStart(bytes)
            builder.parse(InputSource(ByteArrayInputStream(bytes, start, bytes.size - start)))
        } catch (e: ParserConfigurationException) {
            throw GpxReadException(ImportProblem.UNREADABLE)
        } catch (e: SAXException) {
            // <gpx>처럼 보이는데 깨졌거나 DOCTYPE이 있으면 읽을 수 없는 파일, 아예 XML이 아니면 GPX가 아닌 파일.
            throw GpxReadException(if (ImportUnpacker.looksLikeGpx(bytes)) ImportProblem.UNREADABLE else ImportProblem.NOT_GPX)
        } catch (e: IOException) {
            throw GpxReadException(ImportProblem.UNREADABLE)
        }
        // 안드로이드 파서는 disallow-doctype-decl을 무시하고 DOCTYPE을 그냥 읽는다. 읽은 뒤에라도 DOCTYPE이 있으면 거부한다.
        if (document.doctype != null) throw GpxReadException(ImportProblem.UNREADABLE)
        if (document.documentElement?.localName != "gpx") throw GpxReadException(ImportProblem.NOT_GPX)

        val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
        val total = trackPoints.length
        var missingTime = 0
        val fixes = ArrayList<LocationFix>(total)
        for (i in 0 until total) {
            val element = trackPoints.item(i) as Element
            val time = element.childText("time")?.let(::parseTimeMillis)
            if (time == null) {
                missingTime++
                continue
            }
            val lat = element.getAttribute("lat").trim().toDoubleOrNull()
            val lon = element.getAttribute("lon").trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val altitude = element.childText("ele")?.toDoubleOrNull()?.takeIf { it.isFinite() }
            fixes += LocationFix(
                point = GeoPoint(lat, lon),
                accuracyM = ACCURACY_M,
                speedMps = null,
                bearingDeg = null,
                timeMillis = time,
                altitudeM = altitude,
            )
        }
        if (total == 0 || missingTime * 2 > total) throw GpxReadException(ImportProblem.NO_TIME)

        // 안정 정렬이라 같은 시간이면 파일에서 앞선 점이 남는다.
        val sorted = fixes.sortedBy { it.timeMillis }.distinctBy { it.timeMillis }
        val firstTrk = document.getElementsByTagNameNS("*", "trk").item(0) as Element?
        return GpxTrack(
            fixes = sorted,
            type = firstTrk?.childText("type")?.ifEmpty { null },
            name = firstTrk?.childText("name")?.ifEmpty { null },
            droppedPoints = total - sorted.size,
            totalPoints = total,
        )
    }

    /** 읽을 수 없거나 밀리초로 담을 수 없는 시간(예: `+999999999-12-31T00:00:00Z`)은 null. */
    private fun parseTimeMillis(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /**
     * XML 선언(`<?xml`) 앞의 UTF-8 BOM과 ASCII 공백을 건너뛴 시작 위치. 파서는 선언 앞의 공백을 받지 않는다.
     * UTF-16 BOM이면 공백이 2바이트라 건드리지 않는다.
     */
    private fun contentStart(bytes: ByteArray): Int {
        if (bytes.size >= 2 && (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() || bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())) return 0
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && bytes[i].let { it == 0x20.toByte() || it == 0x09.toByte() || it == 0x0A.toByte() || it == 0x0D.toByte() }) i++
        return i
    }

    /** 바로 아래 자식 중 첫 [localName] 요소의 글자(앞뒤 공백 제거). 없으면 null. */
    private fun Element.childText(localName: String): String? {
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element && child.localName == localName) return child.textContent.trim()
            child = child.nextSibling
        }
        return null
    }
}
