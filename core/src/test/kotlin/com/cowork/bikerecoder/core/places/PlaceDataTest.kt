package com.cowork.bikerecoder.core.places

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceDataTest {

    private fun roundTrip(data: PlaceData): PlaceData {
        val out = ByteArrayOutputStream()
        PlaceDataWriter.write(data, out)
        return PlaceData.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun rounded(p: GeoPoint) =
        GeoPoint(Math.round(p.lat * 1e5) / 1e5, Math.round(p.lon * 1e5) / 1e5)

    private fun rounded(data: PlaceData) = PlaceData(
        data.version,
        data.builtOn,
        data.places.map { pl -> pl.copy(lines = pl.lines.map { line -> line.map(::rounded) }) },
    )

    @Test
    fun roundTripKeepsEverything() {
        val original = PlaceData(
            version = 3,
            builtOn = "2026-11-01",
            places = listOf(
                Place(
                    PlaceKind.ROUTE, "#낙동강자전거길", "#국토종주", null,
                    listOf(
                        listOf(GeoPoint(35.12345678, 128.98765432), GeoPoint(35.13, 128.99), GeoPoint(35.14, 129.0)),
                        listOf(GeoPoint(36.5, 127.5), GeoPoint(36.50001, 127.50002)),
                    ),
                ),
                Place(PlaceKind.ROUTE, "#새재자전거길", null, null, listOf(listOf(GeoPoint(36.0, 128.0), GeoPoint(36.1, 128.1)))),
                Place(PlaceKind.CENTER, "#양산인증센터", "#국토종주", 0, listOf(listOf(GeoPoint(35.3, 129.0)))),
                Place(PlaceKind.PEAK, "#북한산", "#100대명산", null, listOf(listOf(GeoPoint(37.658, 126.9779)))),
            ),
        )
        val read = roundTrip(original)
        assertEquals(rounded(original), read)
        assertEquals(0, read.places[2].routeIndex)
        assertNull(read.places[0].routeIndex)
        assertNull(read.places[1].group)
        assertEquals("#100대명산", read.places[3].group)
    }

    @Test
    fun negativeDeltasSurvive() {
        val line = listOf(
            GeoPoint(37.5, 127.0),
            GeoPoint(37.49, 126.98),
            GeoPoint(37.4, 126.5),
            GeoPoint(37.39999, 126.49998),
        )
        val original = PlaceData(1, "2026-11-01", listOf(Place(PlaceKind.ROUTE, "#길", null, null, listOf(line))))
        val read = roundTrip(original)
        assertEquals(rounded(original), read)
    }

    @Test
    fun badHeaderThrows() {
        val bytes = "PLC2".toByteArray() + ByteArray(16)
        assertThrows<IllegalArgumentException> { PlaceData.read(ByteArrayInputStream(bytes)) }
    }
}
