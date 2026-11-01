package com.cowork.bikerecoder.core.series

import com.cowork.bikerecoder.core.elevation.TrackAxis
import com.cowork.bikerecoder.core.model.GeoPoint
import com.cowork.bikerecoder.core.post.HeartRateSample
import com.cowork.bikerecoder.core.post.HeartRateSummary
import com.cowork.bikerecoder.core.recording.TrackPoint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricSeriesTest {

    /** 위도 1도 ≈ 111.2 km. 북쪽으로 [meters]만큼 간 점. */
    private fun north(startLat: Double, meters: Double) = GeoPoint(startLat + meters / 111_195.0, 127.0)

    /** 북쪽으로 [count]점. 점 i는 [startMs] + i·[stepMs], [spacingM] 간격, 속도는 [speed](i) m/s. */
    private fun segment(
        count: Int,
        startLat: Double = 37.0,
        startMs: Long = 0L,
        stepMs: Long = 1_000L,
        spacingM: Double = 5.0,
        speed: (Int) -> Float? = { null },
    ): List<TrackPoint> = (0 until count).map { i ->
        TrackPoint(startMs + i * stepMs, north(startLat, i * spacingM), null, speed(i), 5f)
    }

    private fun speedOf(vararg segs: List<TrackPoint>): MetricSeries? {
        val list = segs.toList()
        return TrackAxis.of(list)?.let { MetricSeries.speed(it, list) }
    }

    /** 모든 구간을 자세한 심박으로 친 심박 계열. */
    private fun heartOf(samples: List<HeartRateSample>, vararg segs: List<TrackPoint>): MetricSeries? {
        val list = segs.toList()
        return TrackAxis.of(list)?.let { MetricSeries.heartRate(it, list, samples, List(list.size) { true }) }
    }

    /** 앱처럼 구간마다 [HeartRateSummary.of]의 detailed(구간 시간 안 표본 수 ≥ 이동 분)로 거른 심박 계열. */
    private fun heartByRuleOf(samples: List<HeartRateSample>, vararg segs: List<TrackPoint>): MetricSeries? {
        val list = segs.toList()
        val detailed = list.map { seg ->
            val from = seg.first().timeMillis
            val to = seg.last().timeMillis
            HeartRateSummary.of(samples.filter { it.timeMillis in from..to }, movingTimeMs = to - from)?.detailed ?: false
        }
        return TrackAxis.of(list)?.let { MetricSeries.heartRate(it, list, samples, detailed) }
    }

    @Test
    fun speedFallsBackToPairSpeed() {
        // speedMps 없음: 5 m / 1 s = 18 km/h. 첫 점은 앞 점이 없어 null.
        // 둘째 구간은 첫 구간 끝에서 5 m·1 s 뒤에 시작한다. 구간을 넘어 짝을 지으면 18 km/h가 나올 자리다.
        val second = segment(21, startLat = 37.0 + 105.0 / 111_195.0, startMs = 21_000L)
        val pair = assertNotNull(speedOf(segment(21), second))
        assertEquals(MetricKind.SPEED, pair.kind)
        assertNull(pair.values[0])
        for (i in 1..20) assertEquals(18.0, pair.values[i]!!, 0.05, "index $i")
        assertNull(pair.values[21]) // 둘째 구간 첫 점: 구간을 넘어 구간 속도를 만들지 않는다
        for (i in 22..41) assertEquals(18.0, pair.values[i]!!, 0.05, "index $i")

        // speedMps가 있으면 그것을 쓴다: 2 m/s = 7.2 km/h(구간 속도 18이 아니라).
        val reported = assertNotNull(speedOf(segment(21) { 2f }))
        for (i in 0..20) assertEquals(7.2, reported.values[i]!!, 1e-4, "index $i")
    }

    @Test
    fun sameTimePointsGiveNullSpeed() {
        val points = segment(10).mapIndexed { i, p -> if (i == 5) p.copy(timeMillis = 4_000L) else p }
        val s = assertNotNull(speedOf(points))
        assertNull(s.values[0])
        assertNull(s.values[5])
        assertEquals(18.0, s.values[6]!!, 0.05)

        val backwards = segment(10).mapIndexed { i, p -> if (i == 5) p.copy(timeMillis = 2_000L) else p }
        assertNull(assertNotNull(speedOf(backwards)).values[5])

        val allSameTime = segment(10).map { it.copy(timeMillis = 0L) }
        assertNull(speedOf(allSameTime))
    }

    @Test
    fun absurdSpeedsAreDropped() {
        val s = assertNotNull(speedOf(segment(30) { i -> if (i == 10 || i == 11) (200.0 / 3.6).toFloat() else 5f }))
        assertTrue(s.maxValue < 100, "max ${s.maxValue}")
        assertNull(s.values[10])
        assertNull(s.values[11])
    }

    @Test
    fun heartRateMatchedWithin30s() {
        // 점은 0.1 s 간격(0, 100, …, 900 ms). 표본은 30.1 s 시각 하나.
        val track = segment(10, stepMs = 100L, spacingM = 1.0)
        val s = assertNotNull(heartOf(listOf(HeartRateSample(30_100L, 140)), track))
        assertEquals(MetricKind.HEART_RATE, s.kind)
        assertNull(s.values[0]) // 30.1 s 차이
        assertEquals(140.0, s.values[1]) // 30.0 s 차이
        assertEquals(140.0, s.values[9])
    }

    @Test
    fun heartRateGapStaysNull() {
        val a = segment(60, spacingM = 10.0)
        val b = segment(60, startLat = 37.01, startMs = 3_600_000L, spacingM = 10.0)
        val samples = (0 until 60).map { HeartRateSample(it * 1_000L, 130) }
        val s = assertNotNull(heartOf(samples, a, b))
        val boundary = s.boundariesM.single()
        assertEquals(130.0, s.valueAt(boundary / 2)!!, 1e-9)
        val midB = boundary + (s.totalM - boundary) / 2
        assertNull(s.valueAt(midB))
        assertNull(s.colorHexAt(midB))
        assertNull(s.valueAt(s.totalM))
    }

    @Test
    fun fewerThanTwoValuesIsNull() {
        // 속도: 첫 점은 값이 없으니 값 하나뿐.
        assertNull(speedOf(segment(2)))
        assertNotNull(speedOf(segment(3)))
        // 심박: 표본 없음, 또는 붙은 점 하나.
        val track = segment(10, stepMs = 60_000L)
        assertNull(heartOf(emptyList(), track))
        assertNull(heartOf(listOf(HeartRateSample(0L, 120)), track))
        assertNotNull(heartOf(listOf(HeartRateSample(0L, 120), HeartRateSample(60_000L, 125)), track))
    }

    @Test
    fun valueAtIsLinear() {
        // 속도가 점마다 0.5 m/s씩 오른다. 5번 점은 speedMps가 없고 4번과 시각이 같아 null.
        val points = segment(20, spacingM = 10.0) { i -> if (i == 5) null else i * 0.5f }
            .mapIndexed { i, p -> if (i == 5) p.copy(timeMillis = 4_000L) else p }
        val s = assertNotNull(speedOf(points))
        val d = s.distancesM
        val v = s.values
        val mid = (d[10] + d[11]) / 2
        assertEquals((v[10]!! + v[11]!!) / 2, s.valueAt(mid)!!, 1e-9)
        val quarter = d[12] + (d[13] - d[12]) / 4
        assertEquals(v[12]!! + (v[13]!! - v[12]!!) / 4, s.valueAt(quarter)!!, 1e-9)
        assertEquals(v[10]!!, s.valueAt(d[10])!!, 1e-12)
        assertNull(s.valueAt((d[4] + d[5]) / 2)) // 한쪽이 null
        assertNull(s.valueAt((d[5] + d[6]) / 2))
        assertEquals(v.last()!!, s.valueAt(s.totalM + 100)!!, 1e-12) // 범위 밖은 끝 값
    }

    @Test
    fun colorHexAt() {
        val speed = assertNotNull(speedOf(segment(30) { 5f })) // 18 km/h
        assertEquals("#66BB6A", speed.colorHexAt(speed.totalM / 2))
        val fast = assertNotNull(speedOf(segment(30) { 7f })) // 25.2 km/h
        assertEquals("#E53935", fast.colorHexAt(fast.totalM / 2))

        val track = segment(30)
        val heart = assertNotNull(heartOf((0 until 30).map { HeartRateSample(it * 1_000L, 145) }, track))
        assertEquals("#FB8C00", heart.colorHexAt(heart.totalM / 2))
        val low = assertNotNull(heartOf((0 until 30).map { HeartRateSample(it * 1_000L, 99) }, track))
        assertEquals("#1E88E5", low.colorHexAt(low.totalM / 2))
    }

    @Test
    fun downsampledToThreeHundred() {
        // 500~519번 점은 터무니없는 속도라 null이 된다.
        val points = segment(1_000) { i -> if (i in 500..519) 60f else 5f }
        val axis = assertNotNull(TrackAxis.of(listOf(points)))
        val s = assertNotNull(MetricSeries.speed(axis, listOf(points)))
        assertEquals(MetricSeries.MAX_POINTS, s.values.size)
        assertEquals(MetricSeries.MAX_POINTS, s.distancesM.size)
        assertEquals(0.0, s.distancesM.first())
        assertEquals(axis.totalM, s.distancesM.last(), 1e-9)
        assertEquals(axis.totalM, s.totalM, 1e-9)
        for (i in 1 until s.distancesM.size) assertTrue(s.distancesM[i] >= s.distancesM[i - 1])
        assertTrue(s.values.any { it == null })
        assertNull(s.valueAt(axis.distancesM[510]))
        s.values.filterNotNull().forEach { assertEquals(18.0, it, 1e-4) } // null 너머와 섞이지 않는다
    }

    @Test
    fun downsampleMajorityRule() {
        // 8점 → 4점: 첫·끝 점과, 사이 6점을 [1..3], [4..6] 두 묶음으로.
        val d = DoubleArray(8) { it * 10.0 }
        val (outD, outV) = MetricSeries.downsample(d, listOf(1.0, null, 10.0, 20.0, null, null, 30.0, 2.0), 4)
        assertEquals(listOf(1.0, 15.0, null, 2.0), outV) // 3점 중 null 하나 → 나머지 평균, null 둘 → null
        assertEquals(listOf(0.0, 20.0, 50.0, 70.0), outD.toList()) // 거리는 묶음 전체 평균

        // 묶음 크기 4에 null이 꼭 절반이면 값이 남는다.
        val (_, half) = MetricSeries.downsample(DoubleArray(10) { it * 1.0 }, listOf(0.0, null, null, 4.0, 8.0, 1.0, 1.0, 1.0, 1.0, 0.0), 4)
        assertEquals(6.0, half[1])
    }

    @Test
    fun isolatedNullKeepsBucketValue() {
        // 500번 점: speedMps 없고 499번과 시각이 같아 null. 줄인 뒤에도 그 묶음은 값이 있다.
        val points = segment(1_000) { i -> if (i == 500) null else 5f }
            .mapIndexed { i, p -> if (i == 500) p.copy(timeMillis = 499_000L) else p }
        val axis = assertNotNull(TrackAxis.of(listOf(points)))
        val s = assertNotNull(MetricSeries.speed(axis, listOf(points)))
        assertEquals(MetricSeries.MAX_POINTS, s.values.size)
        assertTrue(s.values.none { it == null })
        assertEquals(18.0, s.valueAt(axis.distancesM[500])!!, 1e-4)
    }

    @Test
    fun sparseHeartRateIsNull() {
        // 5시간, 1 Hz. 표본은 10분마다 하나(±30 s만 붙는다).
        val track = segment(5 * 3_600, spacingM = 5.0)
        val samples = (0 until 30).map { HeartRateSample(300_000L + it * 600_000L, 130) }
        assertNull(heartByRuleOf(samples, track)) // 자세하지 않은 구간
        assertNull(heartOf(samples, track)) // 자세하다고 쳐도 줄인 뒤 이웃한 두 값이 없다
    }

    @Test
    fun shortSparseHeartRateIsNull() {
        // 1시간, 1 Hz. 표본은 10분마다 하나(6개 < 60분). 줄여도 표본마다 이웃한 값 몇 개가 남지만 그리지 않는다.
        val track = segment(3_600, spacingM = 5.0)
        val samples = (0 until 6).map { HeartRateSample(300_000L + it * 600_000L, 130) }
        assertNull(heartByRuleOf(samples, track))
    }

    @Test
    fun sparseSegmentNextToDenseOneIsNull() {
        // 10분 자세한 구간(5 s마다 표본) 뒤 1시간 드문 구간(10분마다 표본).
        val dense = segment(600, spacingM = 5.0)
        val sparse = segment(3_600, startLat = 37.1, startMs = 1_000_000L, spacingM = 5.0)
        val samples = (0 until 120).map { HeartRateSample(it * 5_000L, 140) } +
            (0 until 6).map { HeartRateSample(1_300_000L + it * 600_000L, 130) }
        val s = assertNotNull(heartByRuleOf(samples, dense, sparse))
        val boundary = s.boundariesM.single()
        assertEquals(140.0, s.valueAt(boundary / 2)!!, 1e-9)
        // 경계에 걸친 묶음 하나를 넘어서는 모두 null: 드문 표본 자리에도 값이 없다.
        val inSparse = s.distancesM.indices.filter { s.distancesM[it] > boundary + 100.0 }
        assertTrue(inSparse.isNotEmpty())
        assertTrue(inSparse.all { s.values[it] == null }, "sparse part ${inSparse.mapNotNull { s.values[it] }}")
        assertTrue(s.maxValue < 140.0 + 1e-9)

        assertFailsWith<IllegalArgumentException> {
            MetricSeries.heartRate(TrackAxis.of(listOf(dense))!!, listOf(dense), samples, listOf(true, false))
        }
    }

    @Test
    fun denseHeartRateIsCharted() {
        // 1시간, 1 Hz. 표본은 30 s마다(120개 ≥ 60분): 모든 점에 붙는다.
        val track = segment(3_600, spacingM = 5.0)
        val samples = (0 until 121).map { HeartRateSample(it * 30_000L, 150) }
        val s = assertNotNull(heartByRuleOf(samples, track))
        assertEquals(MetricSeries.MAX_POINTS, s.values.size)
        assertTrue(s.values.none { it == null })
        assertEquals(150.0, s.maxValue, 1e-9)
    }

    @Test
    fun minMaxAreSmoothed() {
        // 18 km/h에 한 점짜리 바늘(60 km/h)과 구덩이(0 km/h).
        val s = assertNotNull(speedOf(segment(40) { i -> if (i == 10) (60 / 3.6).toFloat() else if (i == 20) 0f else 5f }))
        assertEquals(18.0, s.maxValue, 0.01)
        assertEquals(18.0, s.minValue, 0.01)
    }
}
