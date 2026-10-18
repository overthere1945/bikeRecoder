package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TrackLineTest {

    private fun p(i: Int) = GeoPoint(37.5 + i * 0.0001, 127.0)

    @Test
    fun aSnapshotKeepsItsPointsWhileTheLineGrows() {
        val line = TrackLine()
        repeat(3) { line.append(p(it)) }
        val before = line.snapshot()

        repeat(100) { line.append(p(3 + it)) } // grows past the first capacity

        assertEquals(listOf(p(0), p(1), p(2)), before)
        assertEquals(103, line.snapshot().size)
        assertEquals((0 until 103).map(::p), line.snapshot())
        assertEquals(p(102), line.last)
    }

    @Test
    fun snapshotsOfTheSameLengthAreEqualAndTheSameWhileNothingWasAdded() {
        val line = TrackLine()
        line.append(p(0))
        line.append(p(1))

        assertSame(line.snapshot(), line.snapshot(), "no copy while nothing was added")
        assertEquals(line.snapshot(), listOf(p(0), p(1)))
        val two = line.snapshot()
        line.append(p(2))
        assertNotEquals(two, line.snapshot())
        assertEquals(two, line.snapshot().subList(0, 2))
    }

    @Test
    fun anEmptyLine() {
        val line = TrackLine()
        assertEquals(emptyList<GeoPoint>(), line.snapshot())
        assertEquals(null, line.last)
    }
}
