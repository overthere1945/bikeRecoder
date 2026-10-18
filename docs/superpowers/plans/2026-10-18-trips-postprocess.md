# 여행과 후처리 (V1.2.0) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 끝난 주행 구간에 갤러리 사진(경로 위 위치), Galaxy Watch7 심박(평균·최고·칼로리·그래프·지도 색)을 붙이고, 모든 경로를 겹친 누적 지도를 더한다.

**Architecture:** 계산 규칙은 `:core`의 `core.post` 패키지(순수 Kotlin, JUnit5). 앱은 `post` 패키지에 저장소(`PostStore`, Room v4), 출처(`PhotoSource` = MediaStore, `HeartRateSource` = Health Connect), 그리고 이들을 묶는 `PostProcessor`를 둔다. `PostProcessor`는 앱이 화면에 있을 때만(앱 시작·구간 요약·기록 화면 열기) 돌고, 한 번에 하나만 실행되며 기록 중인 구간은 건드리지 않는다. 화면은 기존 기록 화면(구간 요약·구간 상세·여행 상세)에 사진·심박을 더하고 새 누적 지도 화면을 만든다.

**Tech Stack:** 기존(Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00, MapLibre 13.6.1, Room 2.8.5, DataStore 1.2.1, coroutines 1.11.0, JUnit5/AndroidX Test) + **Health Connect `androidx.health.connect:connect-client:1.1.0`**(새 의존성은 이것 하나).

**Spec:** `docs/superpowers/specs/2026-10-18-trips-postprocess-design.md` (실행자는 스펙과 이 계획을 함께 읽는다)

## Global Constraints

- 버전: `verMajor/verMinor/verPatch = 1/2/0` → versionCode 10200, versionName `1.2.0`(debug `1.2.0-dev`), 화면 `V1.2.0`. 브랜치 `feature/trips-v1.2.0`.
- minSdk 30, target/compileSdk 37, JVM 17. 수동 DI(`AppContainer`), DI·이미지 로딩·차트 라이브러리 금지(썸네일은 `ContentResolver.loadThumbnail`). MapLibre는 `AndroidView`·메인 스레드에서만.
- 스레드: `RideController`·`NavigationController`와 그 세션은 `rideScope` 한 스레드(R12) 그대로. 후처리는 새 `postScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`에서 돌고 Room·MediaStore·Health Connect만 만진다. 기록 중인 구간(`endedAt == null`)은 절대 처리하지 않는다.
- Room: DB 버전 4, `exportSchema = true`, `autoMigrations = [1→2, 2→3, 3→4]`(3→4는 표 추가만), `app/schemas/.../4.json` 커밋.
- 수치(스펙 그대로): 사진 창 = [구간 시작 − 5분, 구간 끝 + 5분], 카메라 폴더 상대 경로 `DCIM/Camera/`, 사진 다시 찾기·심박 다시 읽기 한도 = 구간 끝 + 24시간, 보간 간격 한도 30초, 심박 자세함 = 샘플 수 ≥ 이동 시간(분), 심박 색 연결 ±30초, 요약 화면 심박 다시 읽기 1분마다 최대 15분, 단순화 허용 오차 10 m, 누적 선 불투명도 0.35, 미리보기 지름 40 dp.
- 심박 색(스펙 §3.3): `< 100` 파랑 `#1E88E5`, `100–119` 초록 `#43A047`, `120–139` 노랑 `#FDD835`, `140–159` 주황 `#FB8C00`, `≥ 160` 빨강 `#E53935`, 샘플 없음 회색 `#9E9E9E`.
- Health Connect: 출처 패키지 `com.sec.android.app.shealth`, 권한 `READ_HEART_RATE`, `READ_EXERCISE`, `READ_TOTAL_CALORIES_BURNED`, 운동 종류 BIKING. 사진 권한 `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`.
- 문구(스펙 그대로): "사진 N장" / "숨긴 사진 N장" / "숨기기" / "다시 보이기" / "주행 중 찍은 사진을 경로에 넣으려면 사진 접근을 허용해 주세요" / "선택한 사진만 찾을 수 있습니다. 모든 사진을 허용하면 자동으로 찾습니다" / "허용" / "나중에" / "설정 열기" / "닫기" / "심박 가져오는 중" / "심박 기록 없음" / "심박을 가져오지 못했습니다" / "다시 가져오기" / "평균 심박 {avg} · 최고 {max} bpm · {kcal} kcal"(칼로리 없으면 "· – kcal") / "워치에서 자전거 운동을 시작하면 자세한 심박이 기록됩니다" / "워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요" / "Health Connect를 사용할 수 없습니다" / "심박은 주행 기록에만 쓰고 휴대폰 밖으로 보내지 않습니다" / "기본" / "심박" / "누적 지도" / "총 거리 · 주행 {n}회 · 총 이동 시간"(숫자 채움) / "전체" / "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다" / "사진·심박 연결" / "가져오지 못했습니다" / "다시 시도". 날짜 표시 "10월 18일 오후 2:31"(`M월 d일 a h:mm`, 한국어).
- 테스트: core·app JVM은 JUnit5(`org.junit.jupiter`), 기기는 JUnit4 + `androidx.compose.ui.test.junit4.v2.createComposeRule`. core `./gradlew :core:test --tests '<FQCN>'`, app `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`.
- **기기 규칙**: connected 테스트는 앱을 지워 그 휴대폰의 기록이 사라진다 → **S22 `R5CT43TFS9A`에서만**, `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> </dev/null`(한 번에 클래스 하나), 실행 전 `adb devices` 확인과 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`, 휴대폰 설정 변경 금지. **S25 `R3CY903ZYQV`에는 아무것도 하지 않는다**(설치도 컨트롤러가 사용자 요청 때만). S22가 없거나 잠겨 있으면 `:app:assembleDebugAndroidTest`로 컴파일만 하고 "기기 검증 대기". 기기 테스트는 진짜 갤러리·Health Connect 대신 가짜 `PhotoSource`·`HeartRateSource`.
- 커밋 끝에 `Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>`. 기존 테스트는 모두 그대로 통과(동작이 바뀌어 고치는 테스트는 Task가 이름을 밝힌다).
- 스펙 보정(컨트롤러 판정 P2): 사진 찾기 실패는 화면에 오류 줄을 두지 않고 다음 실행 때 조용히 다시 찾는다(`photoScanDone`을 false로 둠). "가져오지 못했습니다"는 심박(FAILED)에만 쓴다(스펙 §5.2 보정).
- 스펙 보정(컨트롤러 판정 P1): 심박 그래프는 기존 속도·고도 그래프와 같은 **거리 축**(`ChartPoint(distanceKm, value)`)을 쓴다(스펙 §3.3 "시간 축"을 대신함 — 세 그래프를 같은 축으로 비교).

## Review Focus

- **일시정지가 긴 구간·터널(지점 간격 > 30초)에서 찍은 사진**: 직선 보간으로 엉뚱한 곳에 놓이지 않고 시간상 가까운 지점에 놓인다 → Task 1 `photoInLongGapSnapsToNearerPoint`.
- **같은 사진이 다시 찾기에서 또 나올 때**: 숨김이 풀리지 않고 중복되지 않는다 → Task 3 `upsertPhotosKeepsHiddenAndSkipsDuplicates`.
- **후처리 도중 사용자가 새 기록을 시작하거나 구간을 삭제할 때**: 기록 중 구간은 건너뛰고, 지워진 구간의 결과는 쓰지 않으며(FK 실패를 잡아 그 구간만 건너뜀) 앱이 죽지 않는다 → Task 5 `skipsOpenSegment`, `segmentDeletedMidRunIsSkipped`.
- **Health Connect 권한 없음/앱 없음/예외**: 상태가 FAILED가 되고 다음 실행 때 다시 시도하며, 사진 처리와 다른 구간은 계속된다 → Task 5 `heartRateFailureDoesNotStopPhotos`.
- **기록이 아주 많을 때(구간 수백 개, 지점 수백만)**: 누적 지도는 저장된 단순화 경로만 읽어 한 번에 그린다 → Task 1 `simplifyKeepsShapeWithinTolerance`(점 수 감소 확인), Task 8 `loadsOnlySimplifiedRoutes`.

## 파일 구조

- `core/.../core/post/`: `PhotoPlacement.kt`, `RouteSimplifier.kt`(+ `PolylineCodec`), `CumulativeSummary.kt`, `HeartRate.kt`(샘플·요약·색·그래프), `HrStatus.kt`(상태·다시 읽기 규칙)
- `app/.../data/`: `Entities.kt`·`Daos.kt`·`AppDatabase.kt` 수정, 새 `RoomPostStore.kt`
- `app/.../post/`: `PostStore.kt`(모델·인터페이스), `PhotoSource.kt` + `MediaStorePhotoSource.kt`, `HeartRateSource.kt` + `HealthConnectHeartRateSource.kt`, `PostProcessor.kt`, `PhotoThumbnails.kt`
- `app/.../ui/photo/`: `PhotoStrip.kt`, `PhotoViewer.kt`; `app/.../ui/health/`: `HeartRateRow.kt`, `HeartRateLegend.kt`, `HealthRationaleActivity.kt`; `app/.../ui/post/PermissionCards.kt`; `app/.../ui/cumulative/`: `CumulativeMapScreen.kt`, `CumulativeMapViewModel.kt`
- 수정: `map/BikeMap.kt`(사진 표시·색 경로·누적 선), `ui/history/*`, `ui/record/RecordScreen.kt`, `ui/main/MainScreen.kt`(메뉴), `ui/settings/*`, `ui/BikeNavHost.kt`, `AppContainer.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `data/SettingsRepository.kt`

---

### Task 1: 사진 위치·경로 단순화·누적 요약 규칙 (core, 스펙 §2.2, §4)

**Files:** Create `core/src/main/kotlin/com/cowork/bikerecoder/core/post/PhotoPlacement.kt`, `RouteSimplifier.kt`, `CumulativeSummary.kt`; Test `core/src/test/kotlin/com/cowork/bikerecoder/core/post/PhotoPlacementTest.kt`, `RouteSimplifierTest.kt`, `CumulativeSummaryTest.kt`

**Interfaces — Produces:**
```kotlin
data class PhotoCandidate(val mediaId: Long, val takenAt: Long, val exif: GeoPoint?)
enum class PhotoPositionSource { EXIF, TRACK }
data class PlacedPhoto(val mediaId: Long, val takenAt: Long, val point: GeoPoint, val source: PhotoPositionSource)
object PhotoPlacement {
    const val WINDOW_MS = 5 * 60_000L
    const val GAP_MS = 30_000L
    fun windowStart(startedAt: Long): Long          // startedAt - WINDOW_MS
    fun windowEnd(endedAt: Long): Long              // endedAt + WINDOW_MS
    fun place(c: PhotoCandidate, track: List<TrackPoint>): PlacedPhoto?   // track 시간순; 비면 null
}
object RouteSimplifier { const val TOLERANCE_M = 10.0; fun simplify(points: List<GeoPoint>, toleranceM: Double = TOLERANCE_M): List<GeoPoint> }
object PolylineCodec { fun encode(points: List<GeoPoint>): String; fun decode(encoded: String): List<GeoPoint> }  // Google polyline, 1e5
data class CumulativeEntry(val segmentId: Long, val localDate: LocalDate, val distanceM: Double, val movingTimeMs: Long, val points: List<GeoPoint>)
data class PeriodFilter(val year: Int? = null, val month: Int? = null)   // month는 year가 있을 때만
data class CumulativeTotals(val distanceM: Double, val rides: Int, val movingTimeMs: Long)
object CumulativeSummary {
    fun filter(entries: List<CumulativeEntry>, period: PeriodFilter): List<CumulativeEntry>
    fun totals(entries: List<CumulativeEntry>): CumulativeTotals
    fun years(entries: List<CumulativeEntry>): List<Int>               // 내림차순
    fun months(entries: List<CumulativeEntry>, year: Int): List<Int>   // 오름차순
}
```

- [ ] **Step 1: 실패하는 테스트**
  - `PhotoPlacementTest`: `exifWins`(exif 있으면 그 점, EXIF) / `interpolatesBetweenNeighbours`(t=10s 점 A, t=20s 점 B, 사진 15s → 중점, TRACK) / `photoInLongGapSnapsToNearerPoint`(점 간격 120 s, 사진이 앞점 +20 s → 앞점, 뒤점 −20 s → 뒤점) / `beforeStartUsesFirstPoint`·`afterEndUsesLastPoint` / `emptyTrackGivesNull` / `windowIsFiveMinutesEachSide`.
  - `RouteSimplifierTest`: `straightLineKeepsEnds`(일직선 100점 → 2점) / `simplifyKeepsShapeWithinTolerance`(지그재그 1,000점: 결과 점 수 < 입력의 20%, 모든 원래 점에서 결과 선까지 거리 ≤ 10 m + 0.5) / `polylineRoundTrip`(encode→decode 각 좌표 오차 ≤ 1e-5) / `tinyInputUnchanged`(0·1·2점).
  - `CumulativeSummaryTest`: 2025-12-31·2026-01-05·2026-01-20·2026-03-02 네 항목으로 `filterByYear`, `filterByYearAndMonth`, `totalsSum`(rides = 항목 수), `yearsDescending`, `monthsOfYear`(2026 → [1, 3]), `monthWithoutYearIgnored`.
- [ ] **Step 2:** `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.post.*'` → 컴파일 실패/FAIL 확인.
- [ ] **Step 3:** 구현. 단순화는 반복형 Douglas–Peucker(재귀 금지 — 긴 구간 스택), 거리는 `Geo.project`로 점–선분 거리.
- [ ] **Step 4:** 같은 명령 → PASS.
- [ ] **Step 5:** Commit `core: photo placement, route simplifier and cumulative summary`

### Task 2: 심박 규칙 (core, 스펙 §3.1–§3.3)

**Files:** Create `core/.../core/post/HeartRate.kt`, `HrStatus.kt`; Test `HeartRateTest.kt`, `HrStatusTest.kt`

**Interfaces — Produces:**
```kotlin
data class HeartRateSample(val timeMillis: Long, val bpm: Int)
data class HeartRateStats(val avgBpm: Int, val maxBpm: Int, val detailed: Boolean)
object HeartRateSummary {
    fun of(samples: List<HeartRateSample>, movingTimeMs: Long): HeartRateStats?   // 비면 null; avg 반올림; detailed = samples.size >= movingTimeMs / 60_000
    fun tripAverage(parts: List<Pair<HeartRateStats, Long>>): Int?                 // (구간 통계, 이동 시간) → 이동 시간 가중 평균, 반올림
}
enum class HrBand(val colorHex: String) { BLUE("#1E88E5"), GREEN("#43A047"), YELLOW("#FDD835"), ORANGE("#FB8C00"), RED("#E53935"), NONE("#9E9E9E") }
object HeartRateColor {
    const val MATCH_MS = 30_000L
    fun band(bpm: Int): HrBand
    fun bands(track: List<TrackPoint>, samples: List<HeartRateSample>): List<HrBand>   // track과 같은 길이; samples 시간순, 이진 탐색
}
object HeartRateCharts { fun bpm(track: List<TrackPoint>, samples: List<HeartRateSample>, maxPoints: Int = 300): List<ChartPoint> }  // 거리 축(P1), 연결 안 되는 지점은 뺌
enum class HrStatus { PENDING, READY, NONE, FAILED }
sealed interface HrRead { data class Samples(val samples: List<HeartRateSample>, val calories: Double?) : HrRead; data object Empty : HrRead; data object Failed : HrRead }
object HrRetryPolicy {
    const val GIVE_UP_MS = 24 * 60 * 60_000L
    fun shouldTry(status: HrStatus, endedAt: Long, now: Long): Boolean   // PENDING·FAILED이고 now <= endedAt + GIVE_UP_MS
    fun after(read: HrRead, endedAt: Long, now: Long): HrStatus          // Samples→READY, Empty→(한도 지났으면 NONE 아니면 PENDING), Failed→FAILED
}
```

- [ ] **Step 1: 실패하는 테스트** — `HeartRateTest`: `averageAndMax`([100,110,121] → 110, 121) / `detailedWhenOnePerMinute`(이동 10분·샘플 10 → true, 9 → false) / `emptyIsNull` / `tripAverageIsTimeWeighted`((100, 10분), (160, 30분) → 145) / `bandEdges`(99 BLUE, 100 GREEN, 119 GREEN, 120 YELLOW, 139 YELLOW, 140 ORANGE, 159 ORANGE, 160 RED) / `bandsMatchWithinThirtySeconds`(샘플 +29 s 연결, +31 s NONE, 가장 가까운 샘플 선택) / `chartSkipsUnmatchedPoints`. `HrStatusTest`: `pendingRetriesUntilTwentyFourHours` / `emptyBeforeLimitStaysPending` / `emptyAfterLimitIsNone` / `failedIsRetried` / `readyIsNotRetried` / `samplesMakeReady`.
- [ ] **Step 2:** `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.post.*'` → FAIL.
- [ ] **Step 3:** 구현(`RideCharts`의 누적 거리·다운샘플 방식과 같게; 필요하면 그 private 도우미를 `internal`로 공유).
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** Commit `core: heart-rate summary, colour bands, chart and retry policy`

### Task 3: 저장소 v4와 버전 1.2.0 (스펙 §1.2)

**Files:** Modify `app/.../data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `app/build.gradle.kts`(1.2.0); Create `app/.../post/PostStore.kt`, `app/.../data/RoomPostStore.kt`, `app/schemas/com.cowork.bikerecoder.data.AppDatabase/4.json`; Test JVM `app/src/test/.../post/PostMappingsTest.kt`, 기기 `app/src/androidTest/.../data/RoomPostStoreTest.kt`, `RideDatabaseMigrationTest`에 `v3DataSurvivesMigrationTo4` 추가.

**Interfaces — Consumes:** Task 1·2 모델. **Produces:**
```kotlin
// post/PostStore.kt
data class RidePhoto(val id: Long, val segmentId: Long, val mediaId: Long, val takenAt: Long, val point: GeoPoint, val source: PhotoPositionSource, val hidden: Boolean)
data class SegmentExtras(
    val segmentId: Long, val photosScannedAt: Long? = null, val photoScanDone: Boolean = false,
    val hrStatus: HrStatus = HrStatus.PENDING, val hrFirstTriedAt: Long? = null, val hrLastTriedAt: Long? = null,
    val hrDetailed: Boolean = false, val avgBpm: Int? = null, val maxBpm: Int? = null, val calories: Double? = null,
    val simplifiedDone: Boolean = false,
)
interface PostStore {
    suspend fun segmentsNeedingWork(): List<RideSegment>          // 끝난 구간 중 extras 없음 / !photoScanDone / hrStatus ∈ {PENDING, FAILED} / !simplifiedDone
    suspend fun extras(segmentId: Long): SegmentExtras?
    fun observeExtras(segmentIds: List<Long>): Flow<List<SegmentExtras>>
    suspend fun saveExtras(extras: SegmentExtras)
    fun observePhotos(segmentIds: List<Long>): Flow<List<RidePhoto>>   // 찍은 순
    suspend fun upsertPhotos(segmentId: Long, photos: List<PlacedPhoto>)   // (segmentId, mediaId) 있으면 그대로(숨김 유지)
    suspend fun setHidden(photoId: Long, hidden: Boolean)
    suspend fun deletePhotosByMediaId(mediaIds: Collection<Long>)
    suspend fun replaceSamples(segmentId: Long, samples: List<HeartRateSample>)
    suspend fun samples(segmentId: Long): List<HeartRateSample>
    suspend fun saveSimplified(segmentId: Long, entry: CumulativeEntry)
    suspend fun cumulativeEntries(): List<CumulativeEntry>
}
```
- 표·열은 스펙 §1.2 그대로(`ride_photo`, `segment_extras`, `heart_rate_sample`, `segment_route_simplified`; 모든 `segmentId`는 `ride_segment` FK `onDelete = CASCADE`; `points`는 `PolylineCodec` 문자열; bbox 4열). `RoomPostStore`는 FK 위반(`SQLiteConstraintException`)을 `SegmentGone` 예외로 바꿔 던진다(Task 5가 잡음).
- [ ] **Step 1: 실패하는 테스트** — `PostMappingsTest`(엔티티 ↔ 모델 왕복, 상태 문자열). 기기 `RoomPostStoreTest`: `upsertPhotosKeepsHiddenAndSkipsDuplicates`, `segmentsNeedingWorkSkipsOpenAndDone`, `deletingSegmentCascades`, `cumulativeEntriesDecodePoints`, `writeForMissingSegmentThrowsSegmentGone`. `RideDatabaseMigrationTest.v3DataSurvivesMigrationTo4`(v3 여행·구간·지점·즐겨찾기 → v4 그대로, 새 표 비어 있음).
- [ ] **Step 2:** JVM 실패 확인 → 구현(엔티티·DAO·`version = 4`·`AutoMigration(3, 4)`, 빌드가 만든 `4.json` 확인).
- [ ] **Step 3:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 통과; 기기 규칙대로 `RoomPostStoreTest`, `RideDatabaseMigrationTest`.
- [ ] **Step 4:** Commit `data: Room v4 — photos, segment extras, heart-rate samples, simplified routes; version 1.2.0`

### Task 4: 사진·심박 출처 (MediaStore, Health Connect) (스펙 §2.1, §2.4, §3.1, §3.4)

**Files:** Modify `gradle/libs.versions.toml`(`healthConnect = "1.1.0"`, `androidx-health-connect = { group = "androidx.health.connect", name = "connect-client", version.ref = "healthConnect" }`), `app/build.gradle.kts`, `AndroidManifest.xml`(권한 6개, `<queries><package android:name="com.google.android.apps.healthdata"/></queries>`, `HealthRationaleActivity`에 `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent-filter, `activity-alias` `ViewPermissionUsageActivity`에 `android.intent.action.VIEW_PERMISSION_USAGE` + category `android.intent.category.HEALTH_PERMISSIONS`); Create `post/PhotoSource.kt`, `post/MediaStorePhotoSource.kt`, `post/HeartRateSource.kt`, `post/HealthConnectHeartRateSource.kt`, `ui/health/HealthRationaleActivity.kt`; Test JVM `post/FakeSourcesTest.kt`(가짜의 계약), 기기 `post/MediaStorePhotoSourceTest.kt`(직접 넣은 테스트 이미지로).

**Interfaces — Produces:**
```kotlin
enum class PhotoAccess { FULL, PARTIAL, NONE }
interface PhotoSource {
    fun access(): PhotoAccess
    suspend fun photos(from: Long, to: Long): List<PhotoCandidate>   // DCIM/Camera/, DATE_TAKEN(없으면 DATE_ADDED*1000) in [from, to], 시간순; ACCESS_MEDIA_LOCATION 있으면 setRequireOriginal + ExifInterface 위치
    suspend fun existing(mediaIds: Collection<Long>): Set<Long>
    fun uri(mediaId: Long): Uri
}
enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }
interface HeartRateSource {
    fun availability(): HealthAvailability
    suspend fun hasPermissions(): Boolean
    val permissions: Set<String>                         // HealthPermission.getReadPermission(HeartRateRecord/ExerciseSessionRecord/TotalCaloriesBurnedRecord)
    suspend fun read(from: Long, to: Long): HrRead       // 출처 com.sec.android.app.shealth만; 칼로리 = 겹치는 BIKING 세션의 TotalCaloriesBurned × 겹친 비율 합; 예외·권한 없음 → HrRead.Failed
}
```
- `HealthRationaleActivity`: 제목은 앱 이름("bikeRecoder"), 본문 "심박은 주행 기록에만 쓰고 휴대폰 밖으로 보내지 않습니다", [닫기].
- 가짜(기존 Fake 패턴처럼 `app/src/test`와 `app/src/androidTest`에 각각 하나): `FakePhotoSource(access, photos)`, `FakeHeartRateSource(availability, permitted, reads: (from, to) -> HrRead)`.
- [ ] **Step 1:** 가짜 계약 JVM 테스트 실패 확인 → 인터페이스·가짜 구현 → 통과.
- [ ] **Step 2:** MediaStore·Health Connect 구현과 매니페스트. 기기 `MediaStorePhotoSourceTest`: 테스트가 `DCIM/Camera/`와 `Pictures/Screenshots/`에 이미지 하나씩을 `MediaStore`로 넣고(끝나면 지움) 카메라 것만, 시간 창 밖 제외, `existing`이 지운 것을 빼는지(권한은 `GrantPermissionRule`로 `READ_MEDIA_IMAGES` — 테스트 앱 권한이며 휴대폰 설정 변경 아님).
- [ ] **Step 3:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 규칙대로 `MediaStorePhotoSourceTest`. Health Connect 구현은 S22에서 `HealthConnectClient.getSdkStatus` 호출이 예외 없이 끝나는지만 보고서에 적는다(실제 심박 읽기는 실주행 확인).
- [ ] **Step 4:** Commit `post: MediaStore photo source and Health Connect heart-rate source`

### Task 5: 후처리 관리자 (스펙 §1.1, §2.1, §2.3 지운 사진, §3.2)

**Files:** Create `post/PostProcessor.kt`; Modify `AppContainer.kt`(`postScope`, `postStore`, `photoSource`, `heartRateSource`, `postProcessor`), `MainActivity.kt`(`onStart` → `postProcessor.request()`); Test `app/src/test/.../post/PostProcessorTest.kt`(가짜 RideStore·PostStore·출처, 가상 시계).

**Interfaces — Consumes:** Task 1–4. **Produces:**
```kotlin
class PostProcessor(
    private val rideStore: RideStore, private val store: PostStore,
    private val photos: PhotoSource, private val heartRate: HeartRateSource,
    private val clock: () -> Long, private val scope: CoroutineScope,
) {
    fun request()                                   // 실행 중이면 끝난 뒤 한 번 더(겹침 없음, Mutex + 대기 플래그)
    suspend fun runOnce()                           // 테스트용: 한 바퀴를 끝까지
    suspend fun refetchHeartRate(segmentId: Long)   // [다시 가져오기]: 한도와 상관없이 지금 읽기
    suspend fun pruneDeletedPhotos(segmentIds: List<Long>)   // 화면 열 때: existing()에 없는 mediaId 삭제
    val running: StateFlow<Boolean>
}
```
- 한 바퀴: `store.segmentsNeedingWork()`의 구간마다(끝난 구간만 — `endedAt == null`이면 건너뜀) ① 사진: `access() != NONE`이고 `!photoScanDone`이면 창 안 후보를 `PhotoPlacement.place`(지점 = `rideStore.points`)로 놓아 `upsertPhotos`; `now > endedAt + 24h`면 `photoScanDone = true` ② 심박: `HrRetryPolicy.shouldTry`면 `availability()·hasPermissions()` 확인(아니면 FAILED) 후 `read(startedAt, endedAt)` → `after(...)`, READY면 `replaceSamples` + 요약(`HeartRateSummary.of`, 칼로리); `hrFirstTriedAt`/`hrLastTriedAt` 기록 ③ 단순화: `!simplifiedDone`이면 `RouteSimplifier.simplify` → `saveSimplified`. 각 ①②③은 따로 try — 하나의 실패(예외)는 그 일만 실패로 남기고 계속. `SegmentGone`이면 그 구간을 건너뛴다. `CancellationException`은 다시 던진다.
- [ ] **Step 1: 실패하는 테스트** — `skipsOpenSegment` / `placesPhotosAndKeepsScanningForTwentyFourHours` / `photoScanStopsAfterTwentyFourHours` / `noPhotoAccessLeavesScanUndone` / `heartRatePendingThenReady` / `heartRateEmptyAfterLimitIsNone` / `noPermissionIsFailedAndRetried` / `heartRateFailureDoesNotStopPhotos` / `refetchIgnoresLimitAndOverwrites` / `simplifiesOnce` / `segmentDeletedMidRunIsSkipped` / `requestsDoNotOverlap`(실행 중 3번 request → 실행 2번, 동시 실행 0) / `pruneDeletesMissingPhotos`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.post.PostProcessorTest'` → FAIL → 구현 → PASS.
- [ ] **Step 3:** 연결(`AppContainer`, `MainActivity.onStart`). `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- [ ] **Step 4:** Commit `post: post-processor for photos, heart rate and simplified routes`

### Task 6: 사진 화면 — 지도 미리보기, 사진 줄, 전체 화면, 숨기기, 권한 카드 (스펙 §2.3, §2.4)

**Files:** Modify `map/BikeMap.kt`(`MapOverlay.photos: List<MapPhoto> = emptyList()`, `onPhotoClick: ((Long) -> Unit)? = null`), `ui/history/TripDetailScreen.kt`·`TripDetailViewModel.kt`·`SegmentDetailScreen.kt`·`SegmentDetailViewModel.kt`, `ui/record/RecordScreen.kt`(요약에 "사진 N장"), `data/SettingsRepository.kt`(`photoPromptDismissed: Boolean = false`, `heartRatePromptDismissed: Boolean = false`); Create `post/PhotoThumbnails.kt`, `ui/photo/PhotoStrip.kt`, `ui/photo/PhotoViewer.kt`, `ui/post/PermissionCards.kt`; Test JVM VM 테스트 추가, 기기 `ui/photo/PhotoStripTest.kt`, `PhotoViewerTest.kt`, `ui/post/PermissionCardsTest.kt`.

**Interfaces — Produces:**
```kotlin
data class MapPhoto(val photoId: Long, val point: GeoPoint, val thumbnail: ImageBitmap?)   // thumbnail null이면 회색 원
class PhotoThumbnails(contentResolver: ContentResolver, photoSource: PhotoSource) { suspend fun thumbnail(mediaId: Long, sizePx: Int): ImageBitmap?; suspend fun full(mediaId: Long, maxPx: Int = 2048): ImageBitmap? }  // LruCache, IO, 실패 null
@Composable fun PhotoStrip(photos: List<RidePhoto>, hiddenCount: Int, thumbnails: PhotoThumbnails, onOpen: (index: Int) -> Unit, onOpenHidden: () -> Unit)
@Composable fun PhotoViewer(photos: List<RidePhoto>, startIndex: Int, thumbnails: PhotoThumbnails, onToggleHidden: (RidePhoto) -> Unit, onDismiss: () -> Unit)   // 전체 화면 Dialog + HorizontalPager
@Composable fun PhotoPermissionCard(access: PhotoAccess, onAllow: () -> Unit, onLater: () -> Unit, onOpenSettings: () -> Unit)
@Composable fun HeartRatePermissionCard(onAllow: () -> Unit, onLater: () -> Unit)   // Task 7이 씀
```
- 지도 미리보기: MapLibre `SymbolLayer` + 사진마다 `style.addImage("photo-<id>", 원형 40 dp 비트맵, 흰 테두리 2 dp)`; 누르면 `queryRenderedFeatures`로 photoId → `onPhotoClick`. 상세 화면은 숨기지 않은 사진만 지도에.
- 상세 VM: `postStore.observePhotos(segmentIds)`를 상태에 합치고, 화면을 열 때 `postProcessor.request()`와 `pruneDeletedPhotos(segmentIds)`. 숨기기/다시 보이기 = `setHidden`.
- 권한 카드: `access == NONE`이고 `!photoPromptDismissed`면 [허용] [나중에]; [허용] → `RequestMultiplePermissions(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED)` → 허용되면 `ACCESS_MEDIA_LOCATION` → `postProcessor.request()`; [나중에]/거절 → `photoPromptDismissed = true`. `PARTIAL`이면 두 번째 문구 + [설정 열기](앱 정보 화면 인텐트) [닫기](= dismissed).
- [ ] **Step 1: 실패하는 테스트** — VM: 사진·숨김 수 상태, 숨기기 호출, 화면 열 때 request·prune 1번. 기기: `PhotoStripTest`(사진 N장 문구, 숨긴 사진 N장, 누르면 index), `PhotoViewerTest`(찍은 시각 "10월 18일 오후 2:31", [숨기기]↔[다시 보이기], 넘기기), `PermissionCardsTest`(NONE/PARTIAL 문구와 버튼 콜백).
- [ ] **Step 2:** 구현 → `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 규칙대로 새 클래스와 기존 `RecordScreenTest`(요약 회귀). S22에서 사진 표시 화면 캡처 1장(가짜 사진은 테스트로 넣은 MediaStore 이미지 사용 가능) — `adb -s R5CT43TFS9A exec-out screencap -p`, 경로를 보고서에.
- [ ] **Step 3:** Commit `app: ride photos on the map, photo strip, viewer and hide`

### Task 7: 심박 화면 — 요약 줄, 그래프, [기본/심박] 색, 권한, 요약 화면 다시 읽기 (스펙 §3.2–§3.4)

**Files:** Modify `map/BikeMap.kt`(`MapOverlay.trackColors: List<String>? = null` — track과 같은 길이의 색; 같은 색이 이어지는 구간을 한 피처로, 라인 레이어 `line-color = get("color")`), `ui/history/*`(구간 상세: 줄·그래프·[기본][심박]·색 설명; 여행 상세: 여행 평균·최고·칼로리 합, 날짜별 평균), `ui/record/RecordScreen.kt`·`RecordViewModel.kt`(요약에 심박 줄, 떠 있는 동안 1분마다 `refetchHeartRate` 최대 15번, 화면을 나가면 멈춤); Create `ui/health/HeartRateRow.kt`, `ui/health/HeartRateLegend.kt`; Test JVM VM 테스트, 기기 `ui/health/HeartRateRowTest.kt`, `HeartRateLegendTest.kt`, `SegmentDetailScreenTest` 추가 사례.

**Interfaces — Consumes:** `PostStore.observeExtras`, `samples`, `PostProcessor.refetchHeartRate`, `HeartRatePermissionCard`(Task 6), `HeartRateSource.permissions`. **Produces:**
```kotlin
@Composable fun HeartRateRow(extras: SegmentExtras?, onRefetch: () -> Unit)   // 상태별: PENDING "심박 가져오는 중", NONE "심박 기록 없음", FAILED "심박을 가져오지 못했습니다", READY 스펙 문구(+ !hrDetailed면 안내 줄); 언제나 [다시 가져오기]
@Composable fun HeartRateLegend()        // 5색 + 회색 설명("100 미만" … "160 이상", "기록 없음")
enum class TrackColorMode { DEFAULT, HEART_RATE }
```
- 구간 상세: `hrDetailed`일 때만 [기본][심박](기본값 DEFAULT, 저장하지 않음); HEART_RATE면 `HeartRateColor.bands(track, samples).map { it.colorHex }` + 색 설명; 그래프 3개(속도·고도·심박 — 심박은 `HeartRateCharts.bpm`, 단위 "bpm"). 여행 상세 지도 색은 구간마다 이어 붙인 track/색.
- 권한: `hasPermissions() == false`이고 `!heartRatePromptDismissed`면 카드; [허용] → `PermissionController.createRequestPermissionResultContract()` with `heartRateSource.permissions` → `postProcessor.request()`; 거절/[나중에] → dismissed. `availability() != AVAILABLE`이면 "Health Connect를 사용할 수 없습니다"(카드 대신 한 줄).
- [ ] **Step 1: 실패하는 테스트** — VM: 요약 화면 다시 읽기 횟수(가상 시간 15분 → 15번, READY가 되면 멈춤, 화면 닫으면 멈춤), 여행 평균 가중, 색 모드 전환 시 색 목록 길이 = track 길이. 기기: `HeartRateRowTest`(네 상태 문구, 칼로리 없음 "– kcal", 안내 줄, [다시 가져오기] 콜백), `HeartRateLegendTest`, `SegmentDetailScreenTest`(자세하지 않으면 [심박] 없음).
- [ ] **Step 2:** 구현 → 빌드·JVM·기기(S22만) 통과. S22 화면 캡처 1장(가짜 심박으로 [심박] 색).
- [ ] **Step 3:** Commit `app: heart-rate row, chart, colour-by-heart-rate track and permission`

### Task 8: 누적 지도 화면 (스펙 §4)

**Files:** Modify `map/BikeMap.kt`(`MapOverlay.lines: List<List<GeoPoint>> = emptyList()` — 한 소스 MultiLineString, 색 `#E53935`, 두께 3, 불투명도 0.35), `ui/BikeNavHost.kt`(경로 `cumulative`), `ui/main/MainScreen.kt`(메뉴 "누적 지도"), `ui/history/HistoryScreen.kt`(위 [누적 지도]); Create `ui/cumulative/CumulativeMapViewModel.kt`, `CumulativeMapScreen.kt`; Test JVM `CumulativeMapViewModelTest.kt`, 기기 `ui/cumulative/CumulativeMapScreenTest.kt`(지도 슬롯 주입).

**Interfaces — Consumes:** `PostStore.cumulativeEntries()`, Task 1 `CumulativeSummary`·`PeriodFilter`, `PostProcessor.request()`.
- VM 상태: `entries`(필터 적용), `totals`, `years`, `months`(연도 골랐을 때), `period`, `fit: CameraFit?`(처음 한 번 — 모든 bbox 합), `empty`. 화면을 열 때 `request()`; 처리 끝(`running` true→false)에 다시 읽기.
- 화면: 상단 바 "누적 지도"·뒤로, 요약 줄(`RideFormat`의 거리·시간 형식 재사용), [전체] [연도 ▾] [월 ▾](연도 없으면 월 비활성), 빈 문구.
- [ ] **Step 1: 실패하는 테스트** — VM: `loadsOnlySimplifiedRoutes`(store의 cumulativeEntries만 부르고 rideStore.points는 부르지 않음), 기간 바꾸면 요약·목록 변경, 연도 목록 내림차순, 빈 상태, 처리 끝나면 다시 읽음. 기기: 요약 문구, 거르기 메뉴, 빈 문구.
- [ ] **Step 2:** 구현 → 빌드·JVM·기기(S22만). S22 화면 캡처 1장(테스트 데이터로).
- [ ] **Step 3:** Commit `app: cumulative route map`

### Task 9: 설정 "사진·심박 연결"과 문서 (스펙 §5.1, §5.3)

**Files:** Modify `ui/settings/SettingsScreen.kt`·`SettingsViewModel.kt`(줄 "사진·심박 연결": 사진 상태 "모든 사진 허용"/"선택한 사진만"/"허용 안 함" + [허용]/[설정 열기], 심박 상태 "허용됨"/"허용 안 함"/"Health Connect를 사용할 수 없습니다" + [허용]; [허용]은 dismissed 플래그를 풀고 Task 6·7의 요청 흐름 재사용), `CHANGELOG.md`(`## [1.2.0] - 개발 중` / `### 추가 (예정)` 사진·심박·심박 색·누적 지도·DB v4, `### 문서` 스펙·계획), `README.md`(로드맵 표 V1.2.0 "개발 중", 버전 줄), 새 `docs/verification/trips-field-test.md`(PC 표·기기 표·실주행 체크리스트 6항목 = 스펙 §5.3, 결과 칸 `[ ] 미실시`, 경고 "connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 `installDebug`만"); Test 기기 `SettingsScreenTest`에 `photoAndHeartRateConnectionRow`(스크롤 후 확인).
- [ ] **Step 1:** 테스트 → 구현 → `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` 통과, 모음별 수(XML)를 문서에.
- [ ] **Step 2:** 기기(S22만) `SettingsScreenTest`.
- [ ] **Step 3:** Commit `app: photo and heart-rate connection settings; V1.2.0 docs`
