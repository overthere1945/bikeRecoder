package com.cowork.bikerecoder.core.places

import com.cowork.bikerecoder.core.model.GeoPoint
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** 장소 종류. 파일에는 u8(0, 1, 2)로 저장한다. */
enum class PlaceKind { ROUTE, CENTER, PEAK }

/**
 * 장소 하나(공유 카드 설계 §4.2).
 * [group]은 파일의 빈 문자열이면 null, [routeIndex]는 인증센터([PlaceKind.CENTER])만 가지며
 * 소속 길의 `places` 안 순번이다. 인증센터·정상은 선 1개에 점 1개다.
 */
data class Place(
    val kind: PlaceKind,
    val tag: String,
    val group: String?,
    val routeIndex: Int?,
    val lines: List<List<GeoPoint>>,
)

/** places.bin 전체: 판 번호, 만든 날(yyyy-MM-dd), 장소 목록. */
data class PlaceData(val version: Int, val builtOn: String, val places: List<Place>) {

    companion object {
        private const val MAGIC = "PLC1"
        private const val UNIT = 1e5 // 좌표는 1e-5도 정수로 저장

        /** @throws IllegalArgumentException 머리가 "PLC1"이 아니거나 내용이 맞지 않을 때 */
        fun read(input: InputStream): PlaceData {
            val din = DataInputStream(BufferedInputStream(input))
            val magic = ByteArray(4)
            try {
                din.readFully(magic)
            } catch (e: EOFException) {
                throw IllegalArgumentException("places.bin: header too short", e)
            }
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "places.bin: bad header" }

            val version = din.readUnsignedByte() or (din.readUnsignedByte() shl 8) // u16, 작은 끝
            val builtOn = readString(din)
            val count = readVarint(din)
            val places = ArrayList<Place>(count)
            repeat(count) {
                val kindByte = din.readUnsignedByte()
                require(kindByte < PlaceKind.entries.size) { "places.bin: bad kind $kindByte" }
                val kind = PlaceKind.entries[kindByte]
                val tag = readString(din)
                val group = readString(din).ifEmpty { null }
                val routeIndex = if (kind == PlaceKind.CENTER) readVarint(din) else null
                val lineCount = readVarint(din)
                val lines = ArrayList<List<GeoPoint>>(lineCount)
                repeat(lineCount) {
                    val pointCount = readVarint(din)
                    val points = ArrayList<GeoPoint>(pointCount)
                    var lat = 0
                    var lon = 0
                    repeat(pointCount) { i ->
                        val a = unzigzag(readVarint(din))
                        val b = unzigzag(readVarint(din))
                        if (i == 0) {
                            lat = a
                            lon = b
                        } else {
                            lat += a
                            lon += b
                        }
                        points.add(GeoPoint(lat / UNIT, lon / UNIT))
                    }
                    lines.add(points)
                }
                places.add(Place(kind, tag, group, routeIndex, lines))
            }
            return PlaceData(version, builtOn, places)
        }

        /** 부호 없는 LEB128. 32비트를 넘으면 잘못된 파일이다. */
        private fun readVarint(din: DataInputStream): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = din.readUnsignedByte()
                require(shift < 35) { "places.bin: varint too long" }
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

        private fun readString(din: DataInputStream): String {
            val bytes = ByteArray(readVarint(din))
            din.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
