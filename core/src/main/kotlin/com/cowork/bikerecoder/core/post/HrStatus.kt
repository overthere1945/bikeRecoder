package com.cowork.bikerecoder.core.post

/** 구간의 심박 후처리 상태. */
enum class HrStatus { PENDING, READY, NONE, FAILED }

/** 심박 소스를 한 번 읽은 결과. */
sealed interface HrRead {
    /** 샘플이 있을 때만 쓴다([samples]는 비어 있지 않음). 샘플이 없으면 [Empty]. */
    data class Samples(val samples: List<HeartRateSample>, val calories: Double?) : HrRead
    data object Empty : HrRead
    data object Failed : HrRead
}

object HrRetryPolicy {

    /** 구간 끝에서 이 시간이 지나면 심박을 더 기다리지 않는다. */
    const val GIVE_UP_MS = 24 * 60 * 60_000L

    /**
     * PENDING은 언제나 읽는다(한도를 넘겨 [HrRead.Empty]면 NONE이 되도록 — 업그레이드 뒤 처음 보는 오래된 구간 포함).
     * FAILED는 [failedRetryUntil](포함) 안일 때만 다시 읽는다. 자세하지 않은 READY([detailed] = false, 평소 심박 몇 개)는
     * 구간 끝 + [GIVE_UP_MS](포함)까지 다시 읽는다 — 워치 운동의 자세한 심박이 늦게 들어온다(스펙 §6.1).
     * 자세한 READY·NONE은 읽지 않는다. 저장소의 `segmentsNeedingWork`도 같은 조건을 쓴다.
     * [firstTriedAt]: 처음 실제로 읽은 시각(`hrFirstTriedAt`), 아직 없으면 null.
     */
    fun shouldTry(status: HrStatus, detailed: Boolean, endedAt: Long, firstTriedAt: Long?, now: Long): Boolean =
        when (status) {
            HrStatus.PENDING -> true
            HrStatus.FAILED -> now <= failedRetryUntil(endedAt, firstTriedAt)
            HrStatus.READY -> !detailed && now <= endedAt + GIVE_UP_MS
            HrStatus.NONE -> false
        }

    /**
     * FAILED를 다시 읽는 마지막 시각(판정 R14): max(구간 끝, 처음 읽은 시각) + [GIVE_UP_MS]. 업그레이드 뒤 처음 읽다
     * 실패한 오래된 구간도 그때부터 하루 동안 다시 읽는다. 저장소의 `segmentsNeedingWork`도 같은 식을 쓴다.
     */
    fun failedRetryUntil(endedAt: Long, firstTriedAt: Long?): Long = maxOf(endedAt, firstTriedAt ?: endedAt) + GIVE_UP_MS

    /** Samples → READY, Empty → (한도를 넘겼으면 NONE, 아니면 PENDING), Failed → FAILED. */
    fun after(read: HrRead, endedAt: Long, now: Long): HrStatus = when (read) {
        is HrRead.Samples -> HrStatus.READY
        HrRead.Empty -> if (now > endedAt + GIVE_UP_MS) HrStatus.NONE else HrStatus.PENDING
        HrRead.Failed -> HrStatus.FAILED
    }
}
