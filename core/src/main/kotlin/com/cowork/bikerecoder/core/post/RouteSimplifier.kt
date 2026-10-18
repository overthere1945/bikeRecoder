package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.model.GeoMath
import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.roundToLong

object RouteSimplifier {
    const val TOLERANCE_M = 10.0

    /**
     * Douglas–Peucker 단순화. 첫 점과 끝 점은 항상 남는다.
     * 긴 구간에서도 스택이 넘치지 않도록 명시적 스택으로 반복한다.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double = TOLERANCE_M): List<GeoPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last - first < 2) continue
            var worst = -1.0
            var worstIdx = -1
            for (i in first + 1 until last) {
                val d = GeoMath.project(points[i], points[first], points[last]).distanceM
                if (d > worst) {
                    worst = d
                    worstIdx = i
                }
            }
            if (worst > toleranceM) {
                keep[worstIdx] = true
                stack.addLast(intArrayOf(first, worstIdx))
                stack.addLast(intArrayOf(worstIdx, last))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/** Google Encoded Polyline (정밀도 1e5). */
object PolylineCodec {
    private const val SCALE = 1e5

    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * SCALE).roundToLong()
            val lon = (p.lon * SCALE).roundToLong()
            appendValue(sb, lat - prevLat)
            appendValue(sb, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    fun decode(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

            out.add(GeoPoint(lat / SCALE, lon / SCALE))
        }
        return out
    }

    private fun appendValue(sb: StringBuilder, delta: Long) {
        var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
        while (v >= 0x20) {
            sb.append((((v and 0x1f) or 0x20) + 63).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }
}
