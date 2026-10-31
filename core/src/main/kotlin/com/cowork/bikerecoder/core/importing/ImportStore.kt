package com.cowork.bikerecoder.core.importing

import com.cowork.bikerecoder.core.model.RouteProfile
import kotlinx.coroutines.flow.Flow

/**
 * 저장할 가져온 주행 하나.
 * @property profile 여행의 경로 성향(설정의 기본값)
 * @property fileName 목록에 보여 줄 파일 이름(ZIP 안이면 "묶음.zip / activities/123.gpx")
 * @property importedAt 가져온 시각(epoch 밀리초)
 */
data class ImportedRide(
    val ride: ReplayedRide,
    val profile: RouteProfile,
    val fileName: String,
    val importedAt: Long,
)

/** 가져온 주행의 저장소. 안드로이드 타입 없이 core에서 정의하고 앱이 Room으로 구현한다. */
interface ImportStore {
    /** 여행·구간·점·가져옴 기록을 한 트랜잭션으로 저장하고 여행 id를 돌려준다. */
    suspend fun saveImported(ride: ImportedRide): Long

    /** 앱에 있는 모든 구간의 시간 구간. 기록 중인 구간은 끝을 [now]로 본다. */
    suspend fun segmentSpans(now: Long): List<TimeSpan>

    /** 가져온 여행의 id → 파일 이름. */
    fun observeImported(): Flow<Map<Long, String>>

    /** 가져온 여행을 모두 지우고 지운 수를 돌려준다. */
    suspend fun deleteAllImported(): Int
}
