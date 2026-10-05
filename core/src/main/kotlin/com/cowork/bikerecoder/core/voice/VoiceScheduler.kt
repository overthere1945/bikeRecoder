package com.cowork.bikerecoder.core.voice

import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.TurnType
import com.cowork.bikerecoder.core.navigation.Progress
import kotlin.math.floor

/** 발화 우선순위. 선언 순서가 우선순위이며, 같은 우선순위 내에서는 발생 순서를 유지한다. */
enum class Priority { TURN, EVENT, PERIODIC }

/** 한 번의 [VoiceScheduler.onUpdate] 호출로 말해야 할 문구 하나. */
data class Utterance(val text: String, val priority: Priority)

/**
 * 위치 업데이트마다 어떤 한국어 문구를 말할지 결정한다.
 *
 * 회전 지시마다 "멀리"(far)와 "가까이"(near) 안내를 각각 최대 1번씩 내보내고, 서로 가까운 연속 회전은
 * `then`으로 합친다. 1km마다 정기 안내를 큐에 쌓되, 다음 회전 안내가 임박하면 그 회전의 near 안내가
 * 끝난 다음 업데이트로 미룬다. 스레드 안전하지 않으며 코루틴을 쓰지 않는다.
 */
class VoiceScheduler(
    private var route: Route,
    private val phrases: KoreanPhrases,
    private val farM: Double = 200.0,
    private val farFastM: Double = 300.0,
    private val fastSpeedMps: Double = 25.0 / 3.6,
    private val nearM: Double = 30.0,
    private val mergeWithinM: Double = 50.0,
    private val deferPeriodicSec: Double = 15.0,
) {
    private val farSpoken = mutableSetOf<Int>()
    private val nearSpoken = mutableSetOf<Int>()
    private val pendingEvents = mutableListOf<String>()

    private var lastKmFloor: Int = 0
    private var pendingKmReport: Int? = null

    /** 다음 업데이트에서 말할 이벤트성 문구를 큐에 넣는다. */
    fun enqueueEvent(text: String) {
        pendingEvents.add(text)
    }

    /** 재탐색 후 새 경로로 교체한다. 이미 말한 회전 지시 기록은 초기화하고, km 카운터와 보류 중인 km 안내는 유지한다. */
    fun replaceRoute(route: Route) {
        this.route = route
        farSpoken.clear()
        nearSpoken.clear()
    }

    fun onUpdate(progress: Progress, speedMps: Double, sessionDistanceM: Double, etaMillis: Long): List<Utterance> {
        val emitted = mutableListOf<Utterance>()

        // km 보류 여부는 이번 업데이트에서 회전 상태를 바꾸기 전, 이전까지의 상태로 판단한다.
        val imminent = isImminent(progress, speedMps)

        processTurn(progress, speedMps)?.let { emitted.add(Utterance(it, Priority.TURN)) }

        pendingEvents.forEach { emitted.add(Utterance(it, Priority.EVENT)) }
        pendingEvents.clear()

        val currentKmFloor = floor(sessionDistanceM / 1_000.0).toInt()
        if (currentKmFloor > lastKmFloor) {
            pendingKmReport = currentKmFloor
            lastKmFloor = currentKmFloor
        }
        val km = pendingKmReport
        if (km != null && !imminent) {
            emitted.add(Utterance(phrases.kmReport(km, progress.remainingM, etaMillis), Priority.PERIODIC))
            pendingKmReport = null
        }

        return emitted.sortedBy { it.priority.ordinal }
    }

    private fun farDistanceFor(speedMps: Double): Double = if (speedMps > fastSpeedMps) farFastM else farM

    /** [idx]로 합쳐질 다음 지시가 있으면 그 타입을 반환하고, 해당 지시를 단독 안내 대상에서 제외한다. */
    private fun mergeThenType(idx: Int): TurnType? {
        val mergeIdx = idx + 1
        if (mergeIdx >= route.instructions.size) return null
        val current = route.instructions[idx]
        val candidate = route.instructions[mergeIdx]
        if (candidate.distanceFromStartM - current.distanceFromStartM > mergeWithinM) return null
        farSpoken.add(mergeIdx)
        nearSpoken.add(mergeIdx)
        return candidate.type
    }

    private fun indexOfInstruction(instruction: Instruction): Int =
        route.instructions.indexOfFirst { it === instruction }

    private fun processTurn(progress: Progress, speedMps: Double): String? {
        val next = progress.nextInstruction ?: return null
        val distanceToNext = progress.distanceToNextInstructionM ?: return null
        val idx = indexOfInstruction(next)
        if (idx < 0) return null

        val thenType = mergeThenType(idx)

        if (distanceToNext <= nearM) {
            if (idx in nearSpoken) return null
            nearSpoken.add(idx)
            farSpoken.add(idx)
            return phrases.turnNow(next.type, next.roundaboutExit, thenType)
        }

        val farDistance = farDistanceFor(speedMps)
        if (distanceToNext <= farDistance) {
            if (idx in farSpoken) return null
            farSpoken.add(idx)
            return phrases.turnAhead(distanceToNext, next.type, next.roundaboutExit, thenType)
        }

        return null
    }

    /**
     * 다음 회전 안내가 "임박"한지 판단한다: far 안내 전이면서 far까지 남은 시간이 [deferPeriodicSec] 미만이거나
     * (속도가 0.5m/s 이하면 무한대로 취급해 임박하지 않음), far는 말했지만 near는 아직 말하지 않은 경우다.
     */
    private fun isImminent(progress: Progress, speedMps: Double): Boolean {
        val next = progress.nextInstruction ?: return false
        val distanceToNext = progress.distanceToNextInstructionM ?: return false
        val idx = indexOfInstruction(next)
        if (idx < 0) return false

        val farAlreadySpoken = idx in farSpoken
        if (!farAlreadySpoken) {
            if (speedMps <= 0.5) return false
            val farDistance = farDistanceFor(speedMps)
            val timeToFarSec = (distanceToNext - farDistance) / speedMps
            return timeToFarSec < deferPeriodicSec
        }
        return idx !in nearSpoken
    }
}
