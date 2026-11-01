package com.cowork.bikerecoder.core.places

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** 테스트용 places.bin 쓰기. 공유 카드 설계 §4.2를 바이트 단위로 따른다. */
object PlaceDataWriter {

    fun write(data: PlaceData, out: OutputStream) {
        val buf = ByteArrayOutputStream()
        buf.write("PLC1".toByteArray(Charsets.US_ASCII))
        buf.write(data.version and 0xFF)
        buf.write((data.version shr 8) and 0xFF)
        string(buf, data.builtOn)
        varint(buf, data.places.size)
        for (place in data.places) {
            buf.write(place.kind.ordinal)
            string(buf, place.tag)
            string(buf, place.group ?: "")
            if (place.kind == PlaceKind.CENTER) varint(buf, place.routeIndex ?: 0)
            varint(buf, place.lines.size)
            for (line in place.lines) {
                varint(buf, line.size)
                var prevLat = 0
                var prevLon = 0
                for ((i, p) in line.withIndex()) {
                    val lat = Math.round(p.lat * 1e5).toInt()
                    val lon = Math.round(p.lon * 1e5).toInt()
                    if (i == 0) {
                        varint(buf, zigzag(lat))
                        varint(buf, zigzag(lon))
                    } else {
                        varint(buf, zigzag(lat - prevLat))
                        varint(buf, zigzag(lon - prevLon))
                    }
                    prevLat = lat
                    prevLon = lon
                }
            }
        }
        out.write(buf.toByteArray())
        out.flush()
    }

    private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)

    private fun varint(out: OutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun string(out: OutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        varint(out, bytes.size)
        out.write(bytes)
    }
}
