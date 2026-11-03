package com.cowork.bikerecoder.core.video

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** 박자(스펙 §5.2). [startUs] = 첫 박(음악 파일 시각), [beatUs] = 박 간격. 마디 = 4박, [startUs]부터. */
data class Beats(val startUs: Long, val beatUs: Long) {
    val barUs: Long get() = beatUs * 4
}

/**
 * 박 찾기(스펙 §5.2): 음악의 소리 크기(RMS)에서 빠르기와 첫 박의 자리를 찾는다.
 *
 * 순서: 소리가 커지는 정도(onset) → 자기상관으로 대략의 빠르기 → 그 근처에서 곡 전체에 빗(comb)을 대어 빠르기와 박 위치를
 * 정밀하게 맞춤. 자기상관의 한 칸(11.6 ms)만으로는 빠르기 오차가 박마다 쌓여 1분 뒤에는 한 프레임을 넘기 때문이다.
 */
object BeatFinder {

    /** 한 창의 표본 수와 창 사이 간격(표본). 앱이 푼 소리에서 이 규칙으로 RMS를 잰다. */
    const val HOP = 512
    const val WINDOW = 1024
    const val SAMPLE_RATE = 44_100

    /** 소리가 있다고 보는 크기: −40 dBFS. */
    const val SOUND_LEVEL = 0.01f

    /** 이 값 미만이면 "박 없음"(가장 강한 값 / 70~180박 범위의 평균). */
    const val MIN_CONFIDENCE = 2.0

    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val PREFERRED_MIN_BPM = 90.0
    private const val PREFERRED_MAX_BPM = 160.0

    /** 두 배·절반 빠르기가 이만큼 이상 강하면 90~160박 안쪽을 고른다. */
    private const val ALTERNATIVE_RATIO = 0.9

    /** 소리 크기의 로그 압축 `ln(1 + LOG_GAIN·rms)`. 아주 작은 잡음의 오르내림은 무시하고 큰 소리의 변화만 본다. */
    private const val LOG_GAIN = 100.0

    /** 증가분이 평균 + 표준편차×이 값을 넘는 부분만 onset으로 본다(드문 뚜렷한 소리만 남김). */
    private const val ONSET_SD = 1.0

    /** 분석할 소리가 이보다 짧으면 박을 찾지 않는다(초). */
    private const val MIN_SECONDS = 6.0

    /** 정밀 맞춤: 대략의 박 간격(프레임) 앞뒤 [REFINE_SPAN]을 [PERIOD_STEP] 간격으로, 위상은 [PHASE_STEP] 간격으로 대어 본다. */
    private const val REFINE_SPAN = 0.75
    private const val PERIOD_STEP = 0.01
    private const val PHASE_STEP = 0.25

    /** 첫 박은 처음 소리가 난 창보다 이만큼(창 하나) 앞이어도 된다(프레임). */
    private const val START_SLACK_FRAMES = WINDOW / HOP

    private const val FRAME_HZ = SAMPLE_RATE.toDouble() / HOP

    /** 프레임 [index](소수 가능)의 창 가운데 시각(µs). */
    private fun frameUs(index: Double): Double = (index * HOP + WINDOW / 2.0) * 1_000_000.0 / SAMPLE_RATE

    /** [energy] = 창 [WINDOW]·간격 [HOP]의 RMS(0~1, 모노). 박이 뚜렷하지 않으면 null. */
    fun find(energy: FloatArray): Beats? {
        val first = firstSoundFrame(energy)
        if (first < 0) return null
        // 처음 소리가 시작하는 변화도 onset이라 한 프레임 앞부터 본다.
        val from = max(first - 1, 0)
        val n = energy.size - from
        val minLag = ceil(FRAME_HZ * 60.0 / MAX_BPM).toInt()
        val maxLag = floor(FRAME_HZ * 60.0 / MIN_BPM).toInt()
        if (n < MIN_SECONDS * FRAME_HZ || n < 4 * maxLag) return null

        val onset = onsets(energy, from)
        if (onset.all { it == 0.0 }) return null

        // 자기상관. 점수 = ac(L) + ac(2L): 박의 두 배 간격에만 소리가 있는 느린 곡도 절반 간격(빠른 박)으로 잡는다.
        // 2L은 간격이 정수 프레임이 아닌 탓에 ±1칸 어긋나므로 가장 센 값을 쓴다.
        // onset을 [1 2 1]/4로 살짝 번지게 해서 ac 정점이 ±1칸 어긋나도 정점의 90 % 안팎으로 남게 한다(두 배·절반 비교용).
        val soft = DoubleArray(onset.size) { (onset[max(it - 1, 0)] + 2 * onset[it] + onset[min(it + 1, onset.size - 1)]) / 4 }
        val ac = DoubleArray(2 * maxLag + 4) { lag -> if (lag < minLag - 1) 0.0 else autoCorrelation(soft, lag) }
        fun score(lag: Int) = ac[lag] + max(ac[2 * lag - 1], max(ac[2 * lag], ac[2 * lag + 1]))
        val scores = DoubleArray(maxLag + 2) { lag -> if (lag < minLag - 1) 0.0 else score(lag) }

        var best = (minLag..maxLag).maxByOrNull { scores[it] }!!
        val mean = (minLag..maxLag).sumOf { scores[it] } / (maxLag - minLag + 1)
        if (mean <= 0.0 || scores[best] / mean < MIN_CONFIDENCE) return null

        best = preferred(best, scores, minLag, maxLag)

        // 포물선으로 정수 칸 사이를 보간해 대략의 박 간격을 구한 뒤 곡 전체에 빗을 대어 정밀하게 맞춘다.
        val coarse = best + parabolicOffset(scores[best - 1], scores[best], scores[best + 1])
        val (period, phase) = combFit(onset, coarse)

        // 첫 박: 처음 소리가 난 프레임보다 [START_SLACK_FRAMES] 앞(소리 시작 직전의 박 포함) 이후의 첫 빗살.
        // 박은 소리 시작에 맞춰 올 수도 있어서(곡이 킥으로 시작) 바로 앞 박을 건너뛰면 마디가 한 박 어긋난다.
        val earliest = (first - START_SLACK_FRAMES - from).toDouble()
        val startFrame = from + phase + ceil((earliest - phase) / period) * period
        val startUs = max(0.0, frameUs(startFrame)).roundToLong()
        val beatUs = (period * HOP * 1_000_000.0 / SAMPLE_RATE).roundToLong()
        return Beats(startUs = startUs, beatUs = beatUs)
    }

    /** 앞에서부터 처음으로 −40 dBFS를 넘는 창의 시각(µs, 창 가운데). 없으면 0. */
    fun firstSoundUs(energy: FloatArray): Long {
        val frame = firstSoundFrame(energy)
        return if (frame < 0) 0L else frameUs(frame.toDouble()).roundToLong()
    }

    private fun firstSoundFrame(energy: FloatArray): Int = energy.indexOfFirst { it > SOUND_LEVEL }

    /**
     * [from]부터의 onset: 로그 압축한 소리 크기가 앞 프레임보다 커진 만큼(양수 부분)에서 평균 + [ONSET_SD]×표준편차를 넘는 부분.
     */
    private fun onsets(energy: FloatArray, from: Int): DoubleArray {
        val n = energy.size - from
        val level = DoubleArray(n + 1) { ln1p(LOG_GAIN * energy[max(from + it - 1, 0)]) }
        val rise = DoubleArray(n) { max(0.0, level[it + 1] - level[it]) }
        val mean = rise.average()
        val sd = sqrt(rise.sumOf { (it - mean) * (it - mean) } / n)
        val threshold = mean + ONSET_SD * sd
        return DoubleArray(n) { max(0.0, rise[it] - threshold) }
    }

    private fun autoCorrelation(x: DoubleArray, lag: Int): Double {
        var sum = 0.0
        for (i in 0 until x.size - lag) sum += x[i] * x[i + lag]
        return sum / (x.size - lag)
    }

    /**
     * 가장 센 간격이 90~160박 밖이면, 두 배·절반 빠르기의 정점(±2칸)이 90 % 넘게 강할 때 그쪽(90~160박 안쪽)을 쓴다.
     */
    private fun preferred(best: Int, scores: DoubleArray, minLag: Int, maxLag: Int): Int {
        fun bpm(lag: Int) = FRAME_HZ * 60.0 / lag
        if (bpm(best) in PREFERRED_MIN_BPM..PREFERRED_MAX_BPM) return best
        val alternatives = listOf(best / 2, best * 2).mapNotNull { target ->
            (max(minLag, target - 2)..min(maxLag, target + 2)).maxByOrNull { scores[it] }
        }.filter { bpm(it) in PREFERRED_MIN_BPM..PREFERRED_MAX_BPM && scores[it] >= ALTERNATIVE_RATIO * scores[best] }
        return alternatives.maxByOrNull { scores[it] } ?: best
    }

    /** 세 점(y0, y1=정점, y2)을 지나는 포물선의 꼭짓점이 정점에서 얼마나 떨어졌나(−0.5~0.5). */
    private fun parabolicOffset(y0: Double, y1: Double, y2: Double): Double {
        val denominator = y0 - 2 * y1 + y2
        if (abs(denominator) < 1e-12) return 0.0
        return (0.5 * (y0 - y2) / denominator).coerceIn(-0.5, 0.5)
    }

    /**
     * 박 간격 [coarse] 앞뒤로 간격과 위상을 바꿔 가며 빗살 위치의 onset 합이 가장 큰 쌍을 찾는다.
     * 반환: (박 간격, 첫 박의 위치) — 모두 프레임 단위, 위치는 [0, 간격).
     */
    private fun combFit(onset: DoubleArray, coarse: Double): Pair<Double, Double> {
        var bestScore = -1.0
        var bestPeriod = coarse
        var bestPhase = 0.0
        val steps = (REFINE_SPAN / PERIOD_STEP).toInt()
        for (s in -steps..steps) {
            val period = coarse + s * PERIOD_STEP
            var phase = 0.0
            while (phase < period) {
                val score = combSum(onset, period, phase)
                if (score > bestScore) {
                    bestScore = score
                    bestPeriod = period
                    bestPhase = phase
                }
                phase += PHASE_STEP
            }
        }
        return bestPeriod to bestPhase
    }

    private fun combSum(onset: DoubleArray, period: Double, phase: Double): Double {
        var sum = 0.0
        var x = phase
        val last = onset.size - 1
        while (x < last) {
            val i = x.toInt()
            val f = x - i
            sum += onset[i] * (1 - f) + onset[i + 1] * f
            x += period
        }
        return sum
    }
}
