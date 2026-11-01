package com.cowork.bikerecoder.core.activity

import kotlin.math.abs

/** 일몰 안내 단계. [beforeMs]는 일몰 몇 ms 전에 말하는지(NOW는 일몰 시각). */
enum class SunsetStage(val beforeMs: Long) {
    H2(7_200_000),
    H1(3_600_000),
    M30(1_800_000),
    NOW(0),
}

/** 등산 중 음성으로 말할 안내 하나. */
sealed interface HikeCue {
    /** 구간 시작 뒤 [elapsedMs]째(30분의 배수) 정기 안내. */
    data class Periodic(val elapsedMs: Long) : HikeCue

    /** 일몰 안내. */
    data class Sunset(val stage: SunsetStage) : HikeCue
}

/**
 * 등산 구간의 30분 안내와 일몰 안내가 언제 나가야 하는지 정한다(스펙 §3.3, §3.4).
 *
 * 시계를 읽지 않고, 일시정지 여부도 모른다: 호출하는 쪽이 [due]를 (일시정지 중에도) 주기적으로 부르기만 하면 된다.
 * 한 구간에 하나씩 만들고, 각 안내는 한 번씩만 나간다.
 *
 * 겹침(P13): 30분 차례에 아직 안 한 일몰 안내가 [mergeMs] 안에 있으면 30분 안내를 건너뛰고,
 * 일몰 안내를 할 때 그 [mergeMs] 안의 30분 차례는 한 것으로 친다.
 *
 * @property segmentStartMillis 구간 시작 시각(epoch ms). 30분 차례의 기준이다.
 */
class HikeAnnouncer(
    private val segmentStartMillis: Long,
    private val periodMs: Long = 1_800_000,
    private val mergeMs: Long = 120_000,
) {
    private var sunsetMillis: Long? = null
    private val doneStages = mutableSetOf<SunsetStage>()

    /** 일몰 시각을 처음(null 아닌 값으로) 정했는지. */
    private var sunsetKnown = false

    /** 안내를 살핀 마지막 시각: 첫 [setSunset]의 시각과 [due]를 부른 시각 중 가장 늦은 것. 아직 없으면 null. */
    private var checkedUpTo: Long? = null

    /** 이미 처리한 가장 큰 30분 차례 번호(1이 첫 30분). */
    private var lastSlot = 0L

    /** 일몰 안내와 겹쳐 건너뛰기로 한 차례 번호. */
    private val skippedSlots = mutableSetOf<Long>()

    /**
     * 일몰 시각을 정한다. null이면 일몰 안내를 하지 않는다. 한 단계는 값이 바뀌어도 다시 하지 않는다.
     * 처음 정할 때는 [nowMillis]까지 이미 지난 단계를 한 것으로 친다. 다시 정할 때(위치·날짜가 바뀌어 다시 계산)는
     * 마지막 [due] 때까지 지난 단계만 한 것으로 친다: 그 뒤 [nowMillis]까지 막 차례가 된 단계는 남겨 두어 다음
     * [due]가 말한다(다시 계산이 막 차례가 된 안내를 말없이 지우지 않게).
     */
    fun setSunset(sunsetMillis: Long?, nowMillis: Long) {
        this.sunsetMillis = sunsetMillis
        if (sunsetMillis == null) return
        val passedUpTo = if (sunsetKnown) {
            checkedUpTo ?: nowMillis
        } else {
            sunsetKnown = true
            maxOf(checkedUpTo ?: nowMillis, nowMillis).also { checkedUpTo = it }
        }
        SunsetStage.entries.filter { sunsetMillis - it.beforeMs <= passedUpTo }.forEach { doneStages += it }
    }

    /**
     * [nowMillis]에 말할 안내를 하나 돌려준다. 없으면 null.
     * 일몰 안내가 30분 안내보다 먼저다. 시계가 건너뛰어 같은 종류가 여럿 밀렸으면 가장 늦은 하나만 말하고 앞의 것은 한 것으로 친다.
     */
    fun due(nowMillis: Long): HikeCue? {
        checkedUpTo = maxOf(checkedUpTo ?: nowMillis, nowMillis)
        val sunset = sunsetMillis
        if (sunset != null) {
            val pending = SunsetStage.entries.filter { it !in doneStages && sunset - it.beforeMs <= nowMillis }
            val latest = pending.lastOrNull()
            if (latest != null) {
                doneStages += pending
                skipSlotsNear(sunset - latest.beforeMs)
                return HikeCue.Sunset(latest)
            }
        }

        val slot = (nowMillis - segmentStartMillis).floorDiv(periodMs)
        if (slot < 1 || slot <= lastSlot) return null
        lastSlot = slot
        if (slot in skippedSlots) return null
        val slotTime = segmentStartMillis + slot * periodMs
        if (sunset != null && SunsetStage.entries.any { it !in doneStages && abs(sunset - it.beforeMs - slotTime) <= mergeMs }) {
            return null
        }
        return HikeCue.Periodic(slot * periodMs)
    }

    /** [stageTime]에서 [mergeMs] 안에 있는 30분 차례를 건너뛸 것으로 표시한다. */
    private fun skipSlotsNear(stageTime: Long) {
        val nearest = Math.floorDiv(stageTime - segmentStartMillis + periodMs / 2, periodMs)
        for (slot in nearest - 1..nearest + 1) {
            if (slot >= 1 && abs(segmentStartMillis + slot * periodMs - stageTime) <= mergeMs) skippedSlots += slot
        }
    }
}
