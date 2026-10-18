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
     * FAILED는 한도(구간 끝 + [GIVE_UP_MS], 포함) 안일 때만 다시 읽는다. READY·NONE은 읽지 않는다.
     */
    fun shouldTry(status: HrStatus, endedAt: Long, now: Long): Boolean = when (status) {
        HrStatus.PENDING -> true
        HrStatus.FAILED -> now <= endedAt + GIVE_UP_MS
        HrStatus.READY, HrStatus.NONE -> false
    }

    /** Samples → READY, Empty → (한도를 넘겼으면 NONE, 아니면 PENDING), Failed → FAILED. */
    fun after(read: HrRead, endedAt: Long, now: Long): HrStatus = when (read) {
        is HrRead.Samples -> HrStatus.READY
        HrRead.Empty -> if (now > endedAt + GIVE_UP_MS) HrStatus.NONE else HrStatus.PENDING
        HrRead.Failed -> HrStatus.FAILED
    }
}
