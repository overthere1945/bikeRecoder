package com.cowork.bikerecoder.core.trip

import com.cowork.bikerecoder.core.model.RouteProfile
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val STALE_THRESHOLD_MILLIS = 3L * 24 * 60 * 60 * 1000

/**
 * 당일/다일 여행의 생명주기를 관리한다.
 *
 * ACTIVE 여행은 한 번에 하나만 존재한다고 가정한다. 새 여행을 시작하면 기존 ACTIVE 여행을
 * 먼저 완료 처리하며([complete]와 동일하게 오프라인 지도도 삭제한다).
 */
class TripManager(
    private val store: TripStore,
    private val offline: OfflineMapController,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {

    /** 다일 여행이 ACTIVE일 때만 이어가기를 묻는다. 그 외(없음/당일 ACTIVE)는 종류를 묻는다. */
    suspend fun startPrompt(): StartPrompt {
        val active = store.activeTrip() ?: return StartPrompt.AskType
        return if (active.type == TripType.MULTI_DAY) {
            StartPrompt.AskContinue(active)
        } else {
            StartPrompt.AskType
        }
    }

    /** 기존 ACTIVE 여행을 먼저 완료 처리한 뒤 새 여행을 시작하고, 저장된 결과를 반환한다. */
    suspend fun startTrip(type: TripType, profile: RouteProfile, stops: List<TripStop>): Trip {
        store.activeTrip()?.let { complete(it.id) }

        val now = clock()
        val trip = Trip(
            id = 0,
            type = type,
            status = TripStatus.ACTIVE,
            profile = profile,
            createdAt = now,
            lastActiveAt = now,
            completedAt = null,
        )
        val tripId = store.insertTrip(trip)
        store.replaceStops(tripId, renumbered(tripId, stops))

        return trip.copy(id = tripId)
    }

    /** 경유지를 교체한다(0..n-1로 재번호). 경로가 바뀌므로 기존 오프라인 지도를 지운다. */
    suspend fun changeStops(tripId: Long, stops: List<TripStop>) {
        requireTrip(tripId)
        store.replaceStops(tripId, renumbered(tripId, stops))
        offline.deleteForTrip(tripId)
        touch(tripId)
    }

    /** 경로 선호를 바꾼다(이어서 진행할 때 계획 화면에서 고른 값). 바뀌면 경로도 바뀌므로 기존 오프라인 지도를 지운다. */
    suspend fun changeProfile(tripId: Long, profile: RouteProfile) {
        val trip = requireTrip(tripId)
        if (trip.profile == profile) return
        store.updateTrip(trip.copy(profile = profile, lastActiveAt = clock()))
        offline.deleteForTrip(tripId)
    }

    suspend fun markVisited(stopId: Long) {
        store.markVisited(stopId, clock())
    }

    /** 다일 여행 전용: 오늘은 종료하지만 여행 자체는 ACTIVE로 유지한다. */
    suspend fun endToday(tripId: Long) {
        val trip = requireTrip(tripId)
        check(trip.type == TripType.MULTI_DAY) { "endToday는 다일 여행에만 사용할 수 있습니다: $tripId" }
        store.updateTrip(trip.copy(lastActiveAt = clock()))
    }

    /** 여행을 완료 처리하고 오프라인 지도를 삭제한다. */
    suspend fun complete(tripId: Long) {
        val trip = requireTrip(tripId)
        val now = clock()
        store.updateTrip(trip.copy(status = TripStatus.COMPLETED, completedAt = now, lastActiveAt = now))
        offline.deleteForTrip(tripId)
    }

    /** 완료된 당일 여행을 다일 여행으로 전환해 다시 ACTIVE로 만든다. */
    suspend fun convertToMultiDay(tripId: Long) {
        val trip = requireTrip(tripId)
        check(trip.status == TripStatus.COMPLETED) { "convertToMultiDay는 완료된 여행에만 사용할 수 있습니다: $tripId" }
        store.updateTrip(
            trip.copy(
                type = TripType.MULTI_DAY,
                status = TripStatus.ACTIVE,
                completedAt = null,
                lastActiveAt = clock(),
            ),
        )
    }

    suspend fun touch(tripId: Long) {
        val trip = requireTrip(tripId)
        store.updateTrip(trip.copy(lastActiveAt = clock()))
    }

    /** ACTIVE·다일 여행이 3일 이상 방치됐으면 그 여행을 반환한다. */
    suspend fun stalePrompt(): Trip? {
        val active = store.activeTrip() ?: return null
        if (active.type != TripType.MULTI_DAY) return null
        return if (clock() - active.lastActiveAt >= STALE_THRESHOLD_MILLIS) active else null
    }

    /** 생성일부터 오늘까지(zone 기준 달력 날짜, 양끝 포함) 며칠째인지를 1부터 세어 반환한다. */
    fun dayNumber(trip: Trip): Int {
        val startDate = Instant.ofEpochMilli(trip.createdAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(startDate, today).toInt() + 1
    }

    suspend fun remainingStops(tripId: Long): List<TripStop> =
        store.stops(tripId).filter { it.visitedAt == null }

    private suspend fun requireTrip(tripId: Long): Trip =
        store.trip(tripId) ?: error("Trip not found: $tripId")

    /** order를 0..n-1로 재부여하고 tripId를 맞춘다. id는 저장소가 새로 배정하므로 비운다. */
    private fun renumbered(tripId: Long, stops: List<TripStop>): List<TripStop> =
        stops.mapIndexed { index, stop -> stop.copy(id = 0, tripId = tripId, order = index) }
}
