package com.cowork.bikerecoder.core.voice

import com.cowork.bikerecoder.core.model.Instruction
import com.cowork.bikerecoder.core.model.Route
import com.cowork.bikerecoder.core.model.TurnType.LEFT
import com.cowork.bikerecoder.core.model.TurnType.RIGHT
import com.cowork.bikerecoder.core.navigation.Progress
import com.cowork.bikerecoder.core.navigation.RouteProgressTracker
import com.cowork.bikerecoder.core.navigation.pointAt
import com.cowork.bikerecoder.core.navigation.straightRoute
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VoiceSchedulerTest {

    private val phrases = KoreanPhrases()

    /** route 위 along(m) 지점에서의 Progress를 새 RouteProgressTracker로 계산한다. */
    private fun progressAt(route: Route, along: Double): Progress =
        RouteProgressTracker(route).update(pointAt(along, 0.0))

    /**
     * pointAt(along, 0)이 실제로 GeoMath 기준 경로상에서 몇 m 지점으로 매칭되는지 구한다.
     * TestRoutes의 평면 근사와 GeoMath.distanceM(haversine)의 반경 상수가 달라 along 값 그대로를
     * 쓸 수 없으므로(약 0.11% 축소), 안내 지점(Instruction.distanceFromStartM)을 이 값 기준으로 잡는다.
     */
    private val scaleProbeRoute = straightRoute(10_000.0)
    private fun scaledM(along: Double): Double =
        RouteProgressTracker(scaleProbeRoute).update(pointAt(along, 0.0)).distanceAlongM

    @Test
    fun `far and near prompt once each`() {
        val leftAt = scaledM(800.0) + 198.0
        val route = straightRoute(1_200.0, listOf(Instruction(1, LEFT, 0, leftAt)))
        val scheduler = VoiceScheduler(route, phrases)

        val at800 = scheduler.onUpdate(progressAt(route, 800.0), speedMps = 5.0, sessionDistanceM = 800.0, etaMillis = 0L)
        assertEquals(listOf(Utterance("200미터 앞에서 좌회전입니다", Priority.TURN)), at800)

        val at805 = scheduler.onUpdate(progressAt(route, 805.0), speedMps = 5.0, sessionDistanceM = 805.0, etaMillis = 0L)
        assertEquals(emptyList(), at805)

        val at975 = scheduler.onUpdate(progressAt(route, 975.0), speedMps = 5.0, sessionDistanceM = 975.0, etaMillis = 0L)
        assertEquals(listOf(Utterance("좌회전입니다", Priority.TURN)), at975)

        val at980 = scheduler.onUpdate(progressAt(route, 980.0), speedMps = 5.0, sessionDistanceM = 980.0, etaMillis = 0L)
        assertEquals(emptyList(), at980)
    }

    @Test
    fun `far prompt 300m when fast`() {
        val leftAt = scaledM(700.0) + 298.0
        val route = straightRoute(1_200.0, listOf(Instruction(1, LEFT, 0, leftAt)))
        val scheduler = VoiceScheduler(route, phrases)

        val at700 = scheduler.onUpdate(progressAt(route, 700.0), speedMps = 8.0, sessionDistanceM = 700.0, etaMillis = 0L)
        assertEquals(listOf(Utterance("300미터 앞에서 좌회전입니다", Priority.TURN)), at700)
    }

    @Test
    fun `merges close consecutive turns`() {
        val leftAt = scaledM(975.0) + 25.0
        val rightAt = leftAt + 40.0
        val route = straightRoute(
            1_200.0,
            listOf(
                Instruction(1, LEFT, 0, leftAt),
                Instruction(1, RIGHT, 0, rightAt),
            ),
        )
        val scheduler = VoiceScheduler(route, phrases)

        val at975 = scheduler.onUpdate(progressAt(route, 975.0), speedMps = 5.0, sessionDistanceM = 975.0, etaMillis = 0L)
        assertEquals(listOf(Utterance("좌회전 후 바로 우회전입니다", Priority.TURN)), at975)

        // LEFT를 지나 RIGHT가 next가 되어도 합쳐졌으므로 단독 안내가 없다(km 경계는 건드리지 않는다).
        val at1010 = scheduler.onUpdate(progressAt(route, 1_010.0), speedMps = 5.0, sessionDistanceM = 985.0, etaMillis = 0L)
        assertEquals(emptyList(), at1010)
    }

    @Test
    fun `km report text`() {
        val route = straightRoute(5_000.0)
        val scheduler = VoiceScheduler(route, phrases)
        val progress = progressAt(route, 0.0)

        val result = scheduler.onUpdate(progress, speedMps = 5.0, sessionDistanceM = 1_000.0, etaMillis = 1_700_000_000_000L)

        assertEquals(
            listOf(Utterance(phrases.kmReport(1, progress.remainingM, 1_700_000_000_000L), Priority.PERIODIC)),
            result,
        )
    }

    @Test
    fun `km report deferred near turn`() {
        val route = straightRoute(1_200.0, listOf(Instruction(1, LEFT, 0, 1_000.0)))
        val scheduler = VoiceScheduler(route, phrases)
        val eta = 1_700_000_000_000L

        // 지시까지 250m, 속도 5m/s → far(200m)까지 10초 → deferPeriodicSec(15s) 미만이라 보류. km 경계도 넘는다.
        val at750 = scheduler.onUpdate(progressAt(route, 750.0), speedMps = 5.0, sessionDistanceM = 1_000.0, etaMillis = eta)
        assertEquals(emptyList(), at750)

        // near 안내가 나가지만 km 보류는 계속된다(같은 업데이트에는 내보내지 않는다).
        val at975 = scheduler.onUpdate(progressAt(route, 975.0), speedMps = 5.0, sessionDistanceM = 1_975.0, etaMillis = eta)
        assertEquals(listOf(Utterance("좌회전입니다", Priority.TURN)), at975)

        // near 안내 다음 업데이트에서 비로소 kmReport가 나간다.
        val progressAt990 = progressAt(route, 990.0)
        val at990 = scheduler.onUpdate(progressAt990, speedMps = 5.0, sessionDistanceM = 1_990.0, etaMillis = eta)
        assertEquals(
            listOf(Utterance(phrases.kmReport(1, progressAt990.remainingM, eta), Priority.PERIODIC)),
            at990,
        )
    }

    @Test
    fun `event outranks periodic and turn outranks event`() {
        val route = straightRoute(1_200.0, listOf(Instruction(1, LEFT, 0, 1_000.0)))
        val scheduler = VoiceScheduler(route, phrases)
        val eta = 1_700_000_000_000L

        scheduler.enqueueEvent("경로를 벗어났습니다. 다시 탐색합니다")

        // 속도가 0.5m/s 이하이면 far까지의 시간이 무한대로 취급되어 임박하지 않으므로, near 안내와 kmReport가 같은 업데이트에 함께 나간다.
        val progress = progressAt(route, 985.0)
        val result = scheduler.onUpdate(progress, speedMps = 0.3, sessionDistanceM = 1_000.0, etaMillis = eta)

        assertEquals(
            listOf(
                Utterance("좌회전입니다", Priority.TURN),
                Utterance("경로를 벗어났습니다. 다시 탐색합니다", Priority.EVENT),
                Utterance(phrases.kmReport(1, progress.remainingM, eta), Priority.PERIODIC),
            ),
            result,
        )
    }

    @Test
    fun `seeded distance does not repeat announced kilometres`() {
        val route = straightRoute(10_000.0)
        val scheduler = VoiceScheduler(route, phrases, initialDistanceM = 3_400.0)
        val eta = 1_700_000_000_000L

        assertEquals(emptyList(), scheduler.onUpdate(progressAt(route, 0.0), 5.0, sessionDistanceM = 3_450.0, etaMillis = eta))
        val at4km = scheduler.onUpdate(progressAt(route, 0.0), 5.0, sessionDistanceM = 4_000.0, etaMillis = eta)
        assertEquals(listOf(Utterance(phrases.kmReport(4, progressAt(route, 0.0).remainingM, eta), Priority.PERIODIC)), at4km)
    }

    @Test
    fun `replaceRoute keeps km counter`() {
        val route1 = straightRoute(5_000.0)
        val scheduler = VoiceScheduler(route1, phrases)
        val eta = 1_700_000_000_000L

        val at1km = scheduler.onUpdate(progressAt(route1, 0.0), speedMps = 5.0, sessionDistanceM = 1_000.0, etaMillis = eta)
        assertEquals(1, at1km.size)
        val at2km = scheduler.onUpdate(progressAt(route1, 0.0), speedMps = 5.0, sessionDistanceM = 2_000.0, etaMillis = eta)
        assertEquals(1, at2km.size)
        val at3km = scheduler.onUpdate(progressAt(route1, 0.0), speedMps = 5.0, sessionDistanceM = 3_000.0, etaMillis = eta)
        assertEquals(1, at3km.size)

        val route2 = straightRoute(5_000.0)
        scheduler.replaceRoute(route2)

        val at3_5km = scheduler.onUpdate(progressAt(route2, 0.0), speedMps = 5.0, sessionDistanceM = 3_500.0, etaMillis = eta)
        assertEquals(emptyList(), at3_5km)

        val at4km = scheduler.onUpdate(progressAt(route2, 0.0), speedMps = 5.0, sessionDistanceM = 4_000.0, etaMillis = eta)
        assertEquals(1, at4km.size)
        assertEquals(Priority.PERIODIC, at4km[0].priority)
    }
}
