package com.cowork.bikerecoder.core.places

import com.cowork.bikerecoder.core.model.GeoPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 트랙이 지나간 장소의 태그를 찾는다(공유 카드 설계 §4.4).
 *
 * 트랙을 [STEP_M] 간격으로 다시 뽑고, 장소의 점·선 조각을 [CELL_DEG] 격자에 넣어 두고 찾는다.
 * 점 장소(인증센터·정상)는 [POINT_RADIUS_M] 안에 다시 뽑은 점이 하나라도 있으면 맞고,
 * 길은 양 끝이 [LINE_RADIUS_M] 안인 조각의 길이 합이 [MIN_ALONG_M] 이상이면 맞다.
 */
class PlaceMatcher(private val data: PlaceData) {

    private class PointItem(val placeIndex: Int, val point: GeoPoint)

    private class PieceItem(val placeIndex: Int, val a: GeoPoint, val b: GeoPoint)

    private class Match(val key: Int, val tag: String, val group: String?)

    private val pointItems = ArrayList<PointItem>()
    private val pieceItems = ArrayList<PieceItem>()
    private val pointCells = HashMap<Long, MutableList<Int>>()
    private val pieceCells = HashMap<Long, MutableList<Int>>()

    init {
        data.places.forEachIndexed { placeIndex, place ->
            for (line in place.lines) {
                if (place.kind == PlaceKind.ROUTE) {
                    for (i in 0 until line.size - 1) {
                        val item = PieceItem(placeIndex, line[i], line[i + 1])
                        pieceItems.add(item)
                        addToCells(pieceCells, pieceItems.size - 1, item.a, item.b, LINE_RADIUS_M)
                    }
                } else {
                    for (p in line) {
                        pointItems.add(PointItem(placeIndex, p))
                        addToCells(pointCells, pointItems.size - 1, p, p, POINT_RADIUS_M)
                    }
                }
            }
        }
    }

    /** [tracks]: 구간마다 트랙 점 목록(시간 순서). 찾은 태그를 처음 맞은 순서로 돌려준다. */
    fun tags(tracks: List<List<GeoPoint>>): List<String> {
        val matches = ArrayList<Match>()
        val pointDone = BooleanArray(data.places.size)
        val alongM = DoubleArray(data.places.size)
        val firstStep = IntArray(data.places.size) { -1 }
        val routeDone = BooleanArray(data.places.size)

        var index = 0 // index of the resampled point across all tracks
        for (track in tracks) {
            var prevRoutes: Set<Int> = emptySet()
            var prev: GeoPoint? = null
            for (p in resample(track)) {
                // Point places: first resampled point within the radius.
                for (i in pointCells[cellKey(p)].orEmpty()) {
                    val item = pointItems[i]
                    if (pointDone[item.placeIndex]) continue
                    if (planeDistance(p, item.point) <= POINT_RADIUS_M) {
                        pointDone[item.placeIndex] = true
                        addPointMatch(matches, item.placeIndex, index)
                    }
                }
                // Routes within the radius of this point.
                val near = HashSet<Int>()
                for (i in pieceCells[cellKey(p)].orEmpty()) {
                    val item = pieceItems[i]
                    if (item.placeIndex in near) continue
                    if (distanceToPiece(p, item.a, item.b) <= LINE_RADIUS_M) near.add(item.placeIndex)
                }
                if (prev != null) {
                    val stepM = planeDistance(prev, p)
                    for (r in near) {
                        if (r !in prevRoutes || routeDone[r]) continue
                        if (firstStep[r] < 0) firstStep[r] = index - 1
                        alongM[r] += stepM
                        if (alongM[r] >= MIN_ALONG_M) {
                            routeDone[r] = true
                            val place = data.places[r]
                            matches.add(Match(firstStep[r], place.tag, place.group))
                        }
                    }
                }
                prevRoutes = near
                prev = p
                index++
            }
        }

        val kept = matches.sortedBy { it.key }.distinctBy { it.tag }.take(MAX_TAGS)
        val result = ArrayList<String>(kept.map { it.tag })
        val groups = LinkedHashSet<String>()
        kept.forEach { m -> m.group?.let { groups.add(it) } }
        for (g in groups) if (g !in result) result.add(g)
        return result
    }

    private fun addPointMatch(matches: MutableList<Match>, placeIndex: Int, key: Int) {
        val place = data.places[placeIndex]
        if (place.kind == PlaceKind.CENTER) {
            // A certification centre stands for its route; ignore a bad reference.
            val route = place.routeIndex?.let { data.places.getOrNull(it) }
            if (route == null || route.kind != PlaceKind.ROUTE) return
            matches.add(Match(key, route.tag, route.group ?: place.group))
        } else {
            matches.add(Match(key, place.tag, place.group))
        }
    }

    private fun addToCells(
        cells: MutableMap<Long, MutableList<Int>>,
        item: Int,
        a: GeoPoint,
        b: GeoPoint,
        radiusM: Double,
    ) {
        val midLat = (a.lat + b.lat) / 2
        val dLat = radiusM / M_PER_DEG_LAT
        val dLon = radiusM / (M_PER_DEG_LON_EQ * cos(Math.toRadians(midLat)))
        val lat0 = cell(min(a.lat, b.lat) - dLat)
        val lat1 = cell(max(a.lat, b.lat) + dLat)
        val lon0 = cell(min(a.lon, b.lon) - dLon)
        val lon1 = cell(max(a.lon, b.lon) + dLon)
        for (la in lat0..lat1) for (lo in lon0..lon1) {
            cells.getOrPut(key(la, lo)) { ArrayList(2) }.add(item)
        }
    }

    private fun cell(deg: Double): Int = floor(deg / CELL_DEG).toInt()

    private fun key(latCell: Int, lonCell: Int): Long = (latCell.toLong() shl 32) xor (lonCell.toLong() and 0xFFFFFFFFL)

    private fun cellKey(p: GeoPoint): Long = key(cell(p.lat), cell(p.lon))

    /** Resamples a track every [STEP_M] metres along the polyline; keeps the first and last point. */
    private fun resample(track: List<GeoPoint>): List<GeoPoint> {
        if (track.isEmpty()) return emptyList()
        val out = ArrayList<GeoPoint>()
        out.add(track[0])
        var last = track[0]
        var carry = 0.0 // distance already covered since the last emitted point
        for (i in 1 until track.size) {
            val a = track[i - 1]
            val b = track[i]
            val len = planeDistance(a, b)
            if (len <= 0.0) continue
            var pos = STEP_M - carry
            while (pos <= len) {
                val f = pos / len
                last = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                out.add(last)
                pos += STEP_M
            }
            carry = len - (pos - STEP_M)
        }
        val end = track.last()
        if (carry > 0.0 && end != last) out.add(end)
        return out
    }

    /** Distance on a local equirectangular plane. */
    private fun planeDistance(a: GeoPoint, b: GeoPoint): Double {
        val midLat = Math.toRadians((a.lat + b.lat) / 2)
        return hypot((b.lon - a.lon) * cos(midLat) * M_PER_DEG_LON_EQ, (b.lat - a.lat) * M_PER_DEG_LAT)
    }

    /** Shortest distance from [p] to the piece a-b, on a plane centred at [p]. */
    private fun distanceToPiece(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians(p.lat)) * M_PER_DEG_LON_EQ
        val ax = (a.lon - p.lon) * k
        val ay = (a.lat - p.lat) * M_PER_DEG_LAT
        val bx = (b.lon - p.lon) * k
        val by = (b.lat - p.lat) * M_PER_DEG_LAT
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val c = t.coerceIn(0.0, 1.0)
        return hypot(ax + dx * c, ay + dy * c)
    }

    companion object {
        const val POINT_RADIUS_M = 150.0
        const val LINE_RADIUS_M = 100.0
        const val MIN_ALONG_M = 1_000.0
        const val STEP_M = 50.0
        const val MAX_TAGS = 6
        const val CELL_DEG = 0.01
        private const val M_PER_DEG_LON_EQ = 111_320.0
        private const val M_PER_DEG_LAT = 110_540.0
    }
}
