package com.cowork.bikerecoder.ride

import com.cowork.bikerecoder.core.model.GeoPoint

/**
 * The recording's map track, append-only: [append] adds a point without copying the line, and [snapshot] is an
 * immutable view of the points so far that stays valid (and unchanged) while the line grows — the points it covers
 * are never written again. Snapshots taken with nothing added in between are the same object. Main thread only;
 * a snapshot may be read on any thread.
 */
class TrackLine {
    private var points = arrayOfNulls<GeoPoint>(INITIAL_CAPACITY)
    private var size = 0
    private var latest: Snapshot? = null

    val last: GeoPoint? get() = if (size == 0) null else points[size - 1]

    fun append(point: GeoPoint) {
        if (size == points.size) points = points.copyOf(size * 2)
        points[size++] = point
    }

    fun snapshot(): List<GeoPoint> {
        latest?.takeIf { it.size == size }?.let { return it }
        return Snapshot(points, size).also { latest = it }
    }

    private class Snapshot(private val array: Array<GeoPoint?>, override val size: Int) : AbstractList<GeoPoint>() {
        override fun get(index: Int): GeoPoint {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
            return array[index]!!
        }

        // A snapshot of the same line and length has the same points: no element-by-element comparison.
        override fun equals(other: Any?): Boolean =
            (other is Snapshot && other.array === array && other.size == size) || super.equals(other)

        override fun hashCode(): Int = super.hashCode()
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
