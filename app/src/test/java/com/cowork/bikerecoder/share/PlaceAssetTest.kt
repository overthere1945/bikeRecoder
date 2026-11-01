package com.cowork.bikerecoder.share

import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.places.PlaceData
import com.cowork.bikerecoder.core.places.PlaceKind
import com.cowork.bikerecoder.core.places.PlaceMatcher
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 앱에 넣는 장소 데이터(공유 카드 설계 §4.1-§4.3): tools/places/build_places.py가 만든 파일. */
class PlaceAssetTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private val file = File("src/main/assets/places/places.bin")

    private val data: PlaceData by lazy { file.inputStream().use { PlaceData.read(it) } }

    /** A straight track of [lengthM] metres running north-south with [centre] in the middle. */
    private fun trackThrough(centre: GeoPoint, lengthM: Double): List<GeoPoint> {
        val halfDeg = lengthM / 2 / 110_540.0
        return (0..60).map { i -> GeoPoint(centre.lat - halfDeg + 2 * halfDeg * i / 60, centre.lon) }
    }

    @Test
    fun opensAndCounts() {
        val national = data.places.count { it.kind == PlaceKind.ROUTE && it.group == "#국토종주" }
        val peaks = data.places.count { it.kind == PlaceKind.PEAK }
        val centres = data.places.count { it.kind == PlaceKind.CENTER }
        assertEquals(13, national)
        assertEquals(100, peaks)
        assertTrue(centres >= 80, "centres: $centres")
    }

    @Test
    fun coordinatesInKorea() {
        for (place in data.places) for (line in place.lines) for (p in line) {
            assertTrue(p.lat in 33.0..39.0 && p.lon in 124.0..132.0, "${place.tag}: $p")
        }
    }

    @Test
    fun fileUnder1MB() {
        assertTrue(file.isFile, "missing $file")
        assertTrue(file.length() < 1_000_000, "size: ${file.length()}")
    }

    @Test
    fun sangjuboGivesNakdong() {
        // OSM node 5542663082 "상주보 인증센터" (see places-summary.tsv).
        val sangjubo = GeoPoint(36.43117, 128.25008)
        val tags = PlaceMatcher(data).tags(listOf(trackThrough(sangjubo, 3_000.0)))
        assertTrue("#낙동강자전거길" in tags, "tags: $tags")
        assertTrue("#국토종주" in tags, "tags: $tags")
    }

    @Test
    fun bukhansanSummit() {
        val summit = GeoPoint(37.6586, 126.9779)
        val tags = PlaceMatcher(data).tags(listOf(trackThrough(summit, 1_000.0)))
        assertTrue("#북한산" in tags, "tags: $tags")
        assertTrue("#100대명산" in tags, "tags: $tags")
    }
}
