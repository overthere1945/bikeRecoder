package com.cowork.bikerecoder.core.post

import com.cowork.bikerecoder.core.recording.ChartPoint
import com.cowork.bikerecoder.core.recording.RideCharts
import com.cowork.bikerecoder.core.recording.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** 워치 심박 샘플 하나: 시각([timeMillis], epoch ms)과 분당 박동수. */
data class HeartRateSample(val timeMillis: Long, val bpm: Int)

/** 구간의 심박 요약. [detailed]: 샘플이 이동 시간 1분에 1개 이상(워치 운동 기록 수준). */
data class HeartRateStats(val avgBpm: Int, val maxBpm: Int, val detailed: Boolean)

object HeartRateSummary {

    /** 샘플이 없으면 null. 평균은 반올림. detailed = 샘플 수 ≥ 이동 시간(분, 소수 포함). */
    fun of(samples: List<HeartRateSample>, movingTimeMs: Long): HeartRateStats? {
        if (samples.isEmpty()) return null
        val avg = samples.sumOf { it.bpm.toLong() }.toDouble() / samples.size
        return HeartRateStats(
            avgBpm = avg.roundToInt(),
            maxBpm = samples.maxOf { it.bpm },
            detailed = samples.size * 60_000L >= movingTimeMs,
        )
    }

    /**
     * (구간 통계, 구간 이동 시간) 목록의 이동 시간 가중 평균 심박(반올림). 목록이 비면 null.
     * 이동 시간 합이 0이면 가중치를 줄 수 없으므로 구간 평균의 단순 평균.
     */
    fun tripAverage(parts: List<Pair<HeartRateStats, Long>>): Int? {
        if (parts.isEmpty()) return null
        val totalMs = parts.sumOf { it.second }
        if (totalMs <= 0L) return parts.map { it.first.avgBpm }.average().roundToInt()
        return (parts.sumOf { it.first.avgBpm.toDouble() * it.second } / totalMs).roundToInt()
    }
}

/** 심박 색 구간(스펙 §3.3). [NONE]은 연결된 샘플이 없는 지점. */
enum class HrBand(val colorHex: String) {
    BLUE("#1E88E5"),
    GREEN("#43A047"),
    YELLOW("#FDD835"),
    ORANGE("#FB8C00"),
    RED("#E53935"),
    NONE("#9E9E9E"),
}

object HeartRateColor {

    /** 경로 점과 샘플을 잇는 최대 시간 차(±). */
    const val MATCH_MS = 30_000L

    fun band(bpm: Int): HrBand = when {
        bpm < 100 -> HrBand.BLUE
        bpm < 120 -> HrBand.GREEN
        bpm < 140 -> HrBand.YELLOW
        bpm < 160 -> HrBand.ORANGE
        else -> HrBand.RED
    }

    /** [track]과 같은 길이. [samples]는 시간순. 점마다 시간이 가장 가까운 샘플이 ±[MATCH_MS] 안이면 그 심박의 색, 아니면 [HrBand.NONE]. */
    fun bands(track: List<TrackPoint>, samples: List<HeartRateSample>): List<HrBand> =
        track.map { p -> matchedBpm(samples, p.timeMillis)?.let(::band) ?: HrBand.NONE }

    /** [samples](시간순)에서 [timeMillis]에 가장 가까운 샘플(같은 거리면 이른 쪽)의 심박. ±[MATCH_MS] 밖이거나 샘플이 없으면 null. */
    internal fun matchedBpm(samples: List<HeartRateSample>, timeMillis: Long): Int? {
        if (samples.isEmpty()) return null
        // first index whose time >= timeMillis
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timeMillis < timeMillis) lo = mid + 1 else hi = mid
        }
        val before = samples.getOrNull(lo - 1)
        val after = samples.getOrNull(lo)
        val nearest = when {
            before == null -> after
            after == null -> before
            timeMillis - before.timeMillis <= after.timeMillis - timeMillis -> before
            else -> after
        } ?: return null
        return nearest.bpm.takeIf { abs(nearest.timeMillis - timeMillis) <= MATCH_MS }
    }
}

/** 구간 상세의 심박 그래프. 가로축은 속도·고도 그래프와 같은 누적 거리(km). */
object HeartRateCharts {

    /** 연결되는 샘플이 없는 점은 뺀다. 점이 [maxPoints]보다 많으면 [RideCharts]와 같은 방식으로 묶어 평균. */
    fun bpm(track: List<TrackPoint>, samples: List<HeartRateSample>, maxPoints: Int = 300): List<ChartPoint> {
        val xs = RideCharts.cumulativeKm(track)
        val values = track.indices.mapNotNull { i ->
            HeartRateColor.matchedBpm(samples, track[i].timeMillis)?.let { ChartPoint(xs[i], it.toDouble()) }
        }
        return RideCharts.downsample(values, maxPoints)
    }
}
