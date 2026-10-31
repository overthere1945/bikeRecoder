# GPX 가져오기 (V1.6.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Relive·Strava 등에서 받은 GPX·ZIP 파일을 설정 또는 파일 앱(공유·열기)에서 받아, 확인 목록에서 고른 주행을 앱의 기록 엔진으로 다시 계산해 여행으로 저장하고, "가져옴" 표시와 [모두 지우기]를 둔다.

**Architecture:**
- **core `com.cowork.bikerecoder.core.importing` (JVM 시험):** 파일 종류 판단·ZIP/gzip 풀기(`ImportUnpacker`), GPX 읽기(`GpxReader`), 기록 엔진으로 다시 계산(`RideReplay`), 판단 규칙(`ImportRules`), 저장소 인터페이스(`ImportStore`).
- **app 저장:** Room 8판 `trip_import` 표와 `RoomImportStore`(한 트랜잭션 저장, 모두 지우기).
- **app 흐름:** `ImportScanner`(화면 범위, 읽어서 확인 목록 줄 만들기)와 `GpxImportJob`(앱 범위 단일 작업, 다시 읽어 저장).
- **app 화면:** "기록 가져오기" 화면·ViewModel, 설정 묶음, 인텐트 입구(`LaunchRequest.Import`), 카드·여행 화면 표시.

**Tech Stack:** Kotlin, `javax.xml` DOM(기존 `GpxParser`와 같은 XXE 설정), `java.util.zip`, Compose Material 3, Room AutoMigration, Storage Access Framework(`OpenMultipleDocuments`), JUnit5(core·app JVM), Compose UI test·Room 시험(androidTest).

**Spec:** `docs/superpowers/specs/2026-10-31-gpx-import-design.md`. 아래 "스펙 §"는 이 문서를 가리킨다.

## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구를 그대로 쓴다. 문구 상수는 app `importing/ImportFormat.kt` 한 곳에 둔다.
- **시각:** 날짜·시각·"아침/저녁" 구분은 Asia/Seoul. GPX 시간은 ISO-8601(`Z`, `+09:00` 모두)을 `OffsetDateTime`/`Instant`로 읽는다.
- **주행 기준:** 10 m 이상(`MIN_RIDE_DISTANCE_M`), 채택 점 2개 이상.
- **제한 값:** 파일·항목 하나 50,000,000 바이트, ZIP 항목 2,000개, 한 번에 주행 500개(`ImportLimits` 기본값).
- **다시 계산:** `RecordingSession(startMillis = 첫 점 시간, autoPause = true)`, `onFix(fix, nowMillis = fix.timeMillis)`, `stop(마지막 점 시간)`. 사용자 자동 일시정지 설정은 보지 않는다.
- **바꾸지 않는 것:** `GpxParser.parse`의 동작(기존 시험·재생 호출), `RecordingSession`, `PostProcessor`의 규칙, 영상·공유 기능.
- **권한:** 새 권한 없음. 파일은 SAF URI와 공유 grant로만 읽는다.
- **기기 시험:** S22 `R5CT43TFS9A`에서만, 클래스 하나씩, 화면을 먼저 깨우고 `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`. S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(core는 한국어 KDoc, app `ui/`는 영어 KDoc). core 시험은 `org.junit.jupiter` + `kotlin.test`.

## Review Focus

1. **Relive 파일이 `.bin` 이름이고 `<type>`이 없다** → GPX로 읽히고 자전거로 체크된다. → Task 1 `binNamedFileIsSniffedAsGpx`, Task 2 `missingTypeIsCycling`
2. **10월 18·25일처럼 Relive와 앱 둘 다로 기록한 날** → "이미 있는 주행과 겹칩니다". 앞뒤로 딱 맞닿는 주행은 겹침이 아니다. → Task 2 `touchingSpansDoNotOverlap`, `openSegmentCountsUntilNow`
3. **Strava 전체 ZIP(FIT·폴더·ZIP 안 ZIP·3,000 항목)** → 멈추거나 죽지 않고 GPX만 읽으며 제한 알림을 낸다. → Task 1 `stravaLikeZipYieldsOnlyGpxAndNotices`
4. **저장 중 앱이 꺼짐/한 단계 실패** → 반쪽 여행이 남지 않는다. → Task 3 `failureMidSaveLeavesNothing`
5. **점 순서가 섞이거나 같은 시각이 반복, `+09:00` 시간** → 정렬·중복 제거 후 같은 결과, 날짜는 한국 날짜. → Task 1 `offsetTimesParse`, Task 2 `shuffledPointsGiveSameResult`, `localDateIsSeoulDate`

---

### Task 1: core — 파일 풀기와 GPX 읽기

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/importing/ImportUnpacker.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/importing/GpxReader.kt`
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/gpx/GpxParser.kt` (DocumentBuilderFactory 설정을 `internal fun secureDocumentBuilderFactory()`로 빼서 둘이 함께 쓴다. `parse` 동작은 그대로)
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/importing/ImportUnpackerTest.kt`, `GpxReaderTest.kt`

**Interfaces:**
- Produces:
  - `enum class ImportProblem { NOT_GPX, NO_TIME, TOO_SHORT, TOO_LARGE, UNREADABLE }`
  - `data class ImportLimits(val maxItemBytes: Long = 50_000_000, val maxZipEntries: Int = 2_000, val maxRides: Int = 500)`
  - `sealed interface ImportItem { val name: String; val index: Int }` — `index`는 그 원본 안에서 0부터 매기는 항목 번호(ZIP이 아니면 0). `data class Gpx(name, index, val bytes: ByteArray)`, `data class Unreadable(name, index, val problem: ImportProblem)`, `data class TooManyEntries(name, index = -1)`
  - `object ImportUnpacker { fun visit(displayName: String, input: InputStream, limits: ImportLimits = ImportLimits(), visitor: (ImportItem) -> Unit) }` — 스펙 §3.1. ZIP 항목 이름은 `"$displayName / ${entry.name}"`. gzip은 한 번만 푼다. 크기 초과는 읽는 도중 끊고 `Unreadable(TOO_LARGE)`.
  - `data class GpxTrack(val fixes: List<LocationFix>, val type: String?, val name: String?, val droppedPoints: Int, val totalPoints: Int)` — `fixes`는 시간순 정렬, 같은 `timeMillis`는 처음 것만.
  - `object GpxReader { fun read(bytes: ByteArray): GpxTrack }` — 실패는 `class GpxReadException(val problem: ImportProblem) : Exception()`: XML이 아니거나 루트가 `gpx`가 아님 → `NOT_GPX`, 시간 없는 점이 절반 초과(또는 점 0개) → `NO_TIME`, 그 밖의 파싱 실패 → `UNREADABLE`. 좌표 범위 밖·숫자 아님은 그 점만 버린다(`droppedPoints`에 셈). `type`·`name`은 첫 `trk`의 것(앞뒤 공백 제거, 비면 null). 정확도 5f, 속도·방향 null, `<ele>`는 altitudeM.

- [ ] **Step 1: 실패하는 시험**
  - `ImportUnpackerTest`: `binNamedFileIsSniffedAsGpx`(이름 `track.bin`, 내용 GPX → `Gpx`), `bomAndWhitespaceBeforeGpx`, `htmlIsNotGpx`(→ `Unreadable(NOT_GPX)`), `gzipGpxIsUnpacked`, `zipYieldsEachGpxWithIndexAndName`(이름 `"a.zip / activities/1.gpx"`, index 순서), `zipSkipsFolders`, `nestedZipIsNotGpx`, `oversizeItemIsTooLarge`(limits.maxItemBytes = 1_000), `zipOverEntryLimitStopsWithNotice`(maxZipEntries = 3, 항목 5개 → Gpx 3개 + `TooManyEntries`), `stravaLikeZipYieldsOnlyGpxAndNotices`(`.gpx`, `.gpx.gz`, `.fit.gz`(임의 바이트), `activities.csv`, 폴더 → Gpx 2, NOT_GPX 2)
  - `GpxReaderTest`: `readsPointsTypeAndName`, `offsetTimesParse`(`2023-05-14T09:12:00+09:00` → 그 Instant), `sortsAndDropsDuplicateTimes`, `fewMissingTimesAreDropped`(10점 중 2점 시간 없음 → 8점, droppedPoints 2), `mostlyMissingTimesIsNoTime`(10점 중 6점), `badCoordinatesDropped`, `missingElevationIsNull`, `multipleTrkAndTrksegMerged`, `prefixedNamespaceReads`(`<g:gpx xmlns:g=...>`), `doctypeIsRejected`(DOCTYPE/엔티티 → `GpxReadException`, 외부 파일을 읽지 않음), `nonGpxXmlIsNotGpx`
  - `GpxParserTest` 기존 시험은 그대로 통과해야 한다.
- [ ] **Step 2:** `./gradlew :core:test --tests '*importing*'` → 컴파일 실패 확인
- [ ] **Step 3: 구현** (위 Interfaces, 스펙 §3.1·§3.2 시간·좌표 규칙)
- [ ] **Step 4:** `./gradlew :core:test` → 전부 통과
- [ ] **Step 5: 커밋** `core: import — sniff and unpack files, read GPX tracks`

### Task 2: core — 다시 계산과 판단 규칙

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/importing/RideReplay.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/importing/ImportRules.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/importing/ImportStore.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/importing/RideReplayTest.kt`, `ImportRulesTest.kt`

**Interfaces:**
- Consumes: Task 1 `GpxTrack`, `ImportProblem`; 기존 `RecordingSession`, `RecordingUpdate.point`, `RideStats.toTotals()`, `SegmentTotals`, `TrackPoint`, `MIN_RIDE_DISTANCE_M`, `RouteSimplifier.simplify`
- Produces:
  - `data class ReplayedRide(val startedAt: Long, val endedAt: Long, val localDate: LocalDate, val points: List<TrackPoint>, val totals: SegmentTotals)` — `startedAt`/`endedAt`는 첫/마지막 fix 시간, `localDate`는 `startedAt`의 Asia/Seoul 날짜.
  - `object RideReplay { fun replay(fixes: List<LocationFix>, zone: ZoneId = ZoneId.of("Asia/Seoul")): ReplayedRide? }` — fixes가 비면 null. 채택 점이 2개 미만이거나 `totals.distanceM < MIN_RIDE_DISTANCE_M`이면 null(호출자가 `TOO_SHORT`).
  - `enum class Activity { CYCLING, RUNNING, WALKING, HIKING, OTHER }`
  - `data class TimeSpan(val start: Long, val end: Long)`
  - `sealed interface RideStatus { data object New; data object NotCycling; data object OverlapsExisting; data object DuplicateInList; data class Unreadable(val problem: ImportProblem) }`
  - `object ImportRules`:
    - `fun activityOf(type: String?): Activity` — null·공백 → CYCLING; 소문자에 `bik`/`cycl`/`ride` 포함 또는 정확히 `"1"` → CYCLING; `run` 포함 → RUNNING; `walk` → WALKING; `hik` → HIKING; 그 밖 OTHER.
    - `fun overlaps(a: TimeSpan, b: TimeSpan): Boolean` — `a.start < b.end && b.start < a.end`(맞닿음은 false).
    - `fun existingSpans(segments: List<RideSegment>, now: Long): List<TimeSpan>` — `endedAt == null`이면 `now`.
    - `fun statuses(spans: List<TimeSpan?>, activities: List<Activity?>, existing: List<TimeSpan>): List<RideStatus>` — 입력은 읽은 순서대로이고 null은 읽을 수 없음(호출자가 Unreadable로 바꿔 둔다). 우선순위: 기존과 겹침 → `OverlapsExisting`; 앞서 나온 읽힌 주행과 겹침 → `DuplicateInList`; 자전거 아님 → `NotCycling`; 그 밖 `New`.
  - `fun previewPoints(points: List<TrackPoint>): List<GeoPoint>` = `RouteSimplifier.simplify(points.map { it.point })` (ImportRules.kt에)
  - `ImportStore.kt`:
    - `data class ImportedRide(val ride: ReplayedRide, val profile: RouteProfile, val fileName: String, val importedAt: Long)`
    - `interface ImportStore { suspend fun saveImported(ride: ImportedRide): Long /* tripId, 한 트랜잭션 */; suspend fun segmentSpans(now: Long): List<TimeSpan>; fun observeImported(): Flow<Map<Long, String>> /* tripId → fileName */; suspend fun deleteAllImported(): Int }`

- [ ] **Step 1: 실패하는 시험**
  - `RideReplayTest`:
    - `matchesLiveRecording` — 기존 `core/src/test/resources/gpx/follow.gpx`의 fix들을 `RecordingSession`에 직접 넣고(`onFix(fix, fix.timeMillis)`, `stop`) 얻은 `stats.toTotals()`와 채택 점이 `RideReplay.replay` 결과와 같다.
    - `pauseIsNotMovingTime` — 1분 달리고(5 m/s) 5분 같은 자리, 1분 달린 합성 경로: `movingTimeMs`가 약 2분(±15초), `elapsedMs`가 약 7분.
    - `shuffledPointsGiveSameResult` — 같은 fix를 섞고 일부를 복제해 `GpxReader`를 거친 결과가 원래와 같다.
    - `localDateIsSeoulDate` — `2023-05-13T16:30:00Z` 시작 → `LocalDate.of(2023, 5, 14)`.
    - `tooShortIsNull` — 9 m 경로 → null, 단일 점 → null.
  - `ImportRulesTest`: `missingTypeIsCycling`, `activityTable`(`"cycling"`,`"Ride"`,`"road_biking"`,`"1"`→CYCLING, `"running"`→RUNNING, `"Hiking"`→HIKING, `"swimming"`,`"9"`→OTHER), `touchingSpansDoNotOverlap`, `overlapCases`(앞·뒤·안·밖), `openSegmentCountsUntilNow`, `duplicateInListKeepsFirst`, `existingBeatsDuplicateAndNotCycling`, `unreadableIgnoredForDuplicates`
- [ ] **Step 2:** `./gradlew :core:test --tests '*importing*'` → 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** `./gradlew :core:test` → 통과
- [ ] **Step 5: 커밋** `core: import — replay with the recording engine, overlap and activity rules`

### Task 3: app — DB 8판과 `RoomImportStore`

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/data/Entities.kt` (`TripImportEntity`), `Daos.kt` (`ImportDao`), `AppDatabase.kt` (entities, `version = 8`, `AutoMigration(from = 7, to = 8)`, `abstract fun importDao()`), `AppContainer.kt` (`val importStore: ImportStore = RoomImportStore(db)`)
- Create: `app/src/main/java/com/cowork/bikerecoder/data/RoomImportStore.kt`, `app/schemas/.../8.json`(빌드가 내보냄)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/data/RoomImportStoreTest.kt`; `RideDatabaseMigrationTest`에 7→8 시험

**Interfaces:**
- Consumes: Task 2 `ImportStore`, `ImportedRide`, `TimeSpan`; 기존 `TripEntity`, `RideSegmentEntity`, `TrackPointEntity`과 변환 함수, `TripDao.deleteTrip`
- Produces: `RoomImportStore(db: AppDatabase) : ImportStore`; 표 `trip_import(tripId INTEGER PK FK→trip.id ON DELETE CASCADE, fileName TEXT NOT NULL, importedAt INTEGER NOT NULL)`
- `saveImported`: `db.withTransaction { trip(SINGLE_DAY, COMPLETED, profile, createdAt = startedAt, lastActiveAt = completedAt = endedAt) → segment(startedAt, endedAt, localDate, totals) → points → trip_import(fileName.take(200), importedAt) }`.
- `deleteAllImported`: 가져온 tripId마다 `tripDao.deleteTrip`(기존 CASCADE), 지운 수를 돌려준다.
- `segmentSpans(now)`: 모든 구간의 `(startedAt, endedAt ?: now)`.

- [ ] **Step 1: 실패하는 시험**
  - `RoomImportStoreTest`: `savesTripSegmentPointsAndImportRow`(trip COMPLETED·SINGLE_DAY, 구간 1, 점 수, 합계, `observeImported`에 tripId→fileName), `failureMidSaveLeavesNothing`(점 삽입 단계에서 예외를 내는 시험용 저장(예: 같은 PK 점 두 번) → trip·segment·trip_import 모두 0), `deleteAllImportedKeepsRecordedTrips`(기록 여행 1 + 가져온 여행 2 → 2를 돌려주고 기록 여행·구간은 남음), `deletingOneTripCascadesImportRow`, `segmentSpansUseNowForOpenSegment`, `longFileNameIsCut`(250자 → 200자)
  - `RideDatabaseMigrationTest.migrate7To8KeepsDataAndAddsEmptyImportTable`(7판에 여행·구간·`trip_label.shareText` 넣고 8로 → 그대로, `trip_import` 0행)
- [ ] **Step 2:** `./gradlew :app:compileDebugAndroidTestKotlin` 실패 확인
- [ ] **Step 3: 구현** 후 `./gradlew :app:assembleDebug`로 `8.json` 생성 확인
- [ ] **Step 4:** S22에서 `RoomImportStoreTest`, `RideDatabaseMigrationTest`를 하나씩 → 통과. `./gradlew :app:testDebugUnitTest` 통과
- [ ] **Step 5: 커밋** `app: import — DB v8 trip_import and RoomImportStore`

### Task 4: app — 읽기(`ImportScanner`)와 가져오기 작업(`GpxImportJob`)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/importing/ImportReader.kt`, `ImportScanner.kt`, `GpxImportJob.kt`, `ImportFormat.kt`
- Modify: `AppContainer.kt` (`val importReader`, `val gpxImportJob by lazy`)
- Test: `app/src/test/java/com/cowork/bikerecoder/importing/ImportScannerTest.kt`, `GpxImportJobTest.kt`, `ImportFormatTest.kt`

**Interfaces:**
- Consumes: Task 1 `ImportUnpacker`, `GpxReader`, `GpxReadException`, `ImportLimits`, `ImportItem`; Task 2 `RideReplay`, `ImportRules`, `RideStatus`, `ImportStore`, `ImportedRide`, `previewPoints`; 기존 `PostProcessor.request()`, `SettingsRepository`(기본 경로 성향)
- Produces:
  - `interface ImportReader { fun displayName(uri: String): String; fun open(uri: String): InputStream; fun hold(uris: List<String>); fun release(uris: List<String>) }` + `ContentImportReader(context)` (`OpenableColumns.DISPLAY_NAME`, 없으면 URI 마지막 조각; `hold`는 `takePersistableUriPermission(READ)`을 시도하고 실패는 무시, `release`는 잡은 것만 놓음)
  - `data class RideKey(val sourceIndex: Int, val itemIndex: Int)`
  - `data class ScanRow(val key: RideKey, val fileName: String, val startedAt: Long?, val endedAt: Long?, val distanceM: Double, val movingTimeMs: Long, val activity: Activity?, val status: RideStatus, val preview: List<GeoPoint>)`
  - `data class ScanResult(val rows: List<ScanRow>, val notices: List<String>, val sources: List<String>)` — rows 정렬: 읽힌 줄은 `startedAt` 내림차순, `Unreadable`은 맨 아래(읽은 순서).
  - `class ImportScanner(reader: ImportReader, store: ImportStore, clock: () -> Long, limits: ImportLimits = ImportLimits(), compute: CoroutineDispatcher = Dispatchers.Default, io: CoroutineDispatcher = Dispatchers.IO) { suspend fun scan(uris: List<String>, onProgress: (done: Int, total: Int) -> Unit): ScanResult }` — 원본마다 `visit`, 주행 500개에서 멈추고 notice, 각 Gpx는 `read`→`replay`(null이면 TOO_SHORT), 끝에 `segmentSpans(clock())`로 `statuses`. 취소 가능(코루틴 취소 시 즉시 멈춤).
  - `sealed interface ImportJobState { data object Idle; data class Importing(val done: Int, val total: Int); data class Finished(val imported: Int, val skipped: Int, val stopped: Boolean) }`
  - `class GpxImportJob(reader, store, postProcessor: () -> Unit, profile: suspend () -> RouteProfile, clock: () -> Long, scope: CoroutineScope, ...) { val state: StateFlow<ImportJobState>; fun start(sources: List<String>, picks: List<ScanRow>): Boolean /* 진행 중이면 false */; fun stop(); fun acknowledge() /* Finished → Idle */ }`
    - 주행마다(원본별로 묶어 한 번 열고 `visit`로 그 itemIndex 항목을 찾음): read → replay → `startedAt`/`endedAt`가 ScanRow와 다르면 건너뜀 → `segmentSpans`와 다시 겹침 확인 → `saveImported`. 실패는 로그 + 건너뜀.
    - `stop()`은 지금 주행 저장 뒤 멈춤. 끝(멈춤 포함)에 저장 수 > 0이면 `postProcessor()` 한 번. `hold`/`release`로 URI 권한.
  - `ImportFormat.kt`: 스펙 §5 문구 상수와 `fun problemText(p: ImportProblem): String`, `fun statusLine(row: ScanRow): String`(새/자전거 아님 `"달리기 · 6.2 km — 자전거가 아님"`/겹침 문구), `fun resultText(s: ImportJobState.Finished): String`(`"35개를 가져왔습니다"` + `" · 건너뜀 3개"` + `" · 멈춤"`), `fun headerText(found: Int, new: Int)`, `fun rowDateText(startedAt: Long, zone)`(`"2023년 5월 14일 (일) 오전 9:12"`, 기존 `feedDateLine` 형식과 맞춤).

- [ ] **Step 1: 실패하는 시험** (가짜 `ImportReader`·가짜 `ImportStore`, 시험용 GPX 문자열 만드는 도우미)
  - `ImportScannerTest`: `readsFilesAndZipIntoRows`, `rowsSortedNewestFirstUnreadableLast`, `statusesFromExistingAndDuplicates`, `stopsAt500RidesWithNotice`(limits.maxRides = 2), `zipEntryLimitNotice`, `tooShortRowIsUnreadable`, `cancelStopsReading`, `progressCountsSources`
  - `GpxImportJobTest`: `importsOnlyPicks`, `rechecksOverlapBeforeSave`(scan 뒤 store에 겹치는 구간 추가 → 건너뜀 1), `changedFileIsSkipped`, `oneFailureContinues`, `stopAfterCurrentRide`, `postProcessorOnceWhenSaved`, `noPostProcessorWhenNothingSaved`, `startWhileImportingReturnsFalse`, `holdsAndReleasesUris`, `stateFlowIdleImportingFinished`
  - `ImportFormatTest`: 스펙 §5 문구와 형식(위 예시 값 그대로)
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*importing*'` 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** `./gradlew :core:test :app:testDebugUnitTest` 통과
- [ ] **Step 5: 커밋** `app: import — scanner and the import job`

### Task 5: app — "기록 가져오기" 화면, 설정 묶음, 입구(인텐트)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/importing/ImportViewModel.kt`, `ImportScreen.kt`
- Modify:
  - `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt` (묶음 "기록 가져오기"를 "공유" 아래 "앱 정보" 위에; 줄·진행 설명·"가져온 기록 N개"·[모두 지우기]·확인 창)
  - `ui/BikeNavHost.kt` (`Routes.IMPORT`, `LaunchRequest.Import(val uris: List<String>)`, 온보딩 중이면 끝난 뒤 열기, 진행 중 들어오면 Toast "가져오기가 끝난 뒤 다시 보내 주세요")
  - `MainActivity.kt` (`ACTION_SEND`의 `EXTRA_STREAM`, `ACTION_SEND_MULTIPLE`의 목록, `ACTION_VIEW`의 data → `LaunchRequest.Import`)
  - `AndroidManifest.xml` (MainActivity 인텐트 필터 3개, 스펙 §1.1 MIME 목록)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/ui/importing/ImportScreenTest.kt`; `SettingsScreenTest`에 줄 시험; `app/src/test/.../ui/importing/ImportViewModelTest.kt`

**Interfaces:**
- Consumes: Task 4 `ImportScanner`, `GpxImportJob`, `ImportJobState`, `ScanRow`, `ImportFormat`; Task 3 `ImportStore.observeImported`, `deleteAllImported`
- Produces:
  - `ImportViewModel`: `uiState: StateFlow<ImportUiState>`; `ImportUiState`은 `Reading(done, total)` / `Review(rows, checked: Set<RideKey>, notices, header)` / `Empty` / `Job(ImportJobState)`. 함수 `scan(uris)`, `toggle(key)`, `toggleAll()`, `importChecked()`, `stop()`, `close()`. 체크 규칙: `New`는 처음 켜짐, `NotCycling`은 꺼짐·바꿀 수 있음, 나머지는 고를 수 없음. 화면 회전에 남음(VM). 뒤로 가기 중 읽기면 scan 코루틴 취소.
  - `ImportScreen(viewModel, onBack, onOpenSummary, onPickAgain)` — 스펙 §1.2·§1.3 배치, 줄의 경로 그림은 `RouteDrawing`(44dp), 단추 "N개 가져오기"(0이면 흐림).
  - 설정: [지난 기록 가져오기] → `rememberLauncherForActivityResult(OpenMultipleDocuments())`에 `arrayOf("*/*")` → `navController.navigate(import with uris)`.
  - 시험 태그는 `ImportTags` 객체에 모은다.
- [ ] **Step 1: 실패하는 시험**
  - `ImportViewModelTest`: `newRowsStartChecked`, `notCyclingStartsUncheckedButToggles`, `blockedRowsCannotToggle`, `toggleAllOnlySelectable`, `importCheckedStartsJobWithPicks`, `backWhileReadingCancels`, `emptyWhenNoRows`
  - `ImportScreenTest`: `reviewShowsRowsStatusesAndCount`, `buttonDisabledWhenNoneChecked`, `progressAndStop`, `doneShowsResultAndOpensSummary`, `emptyShowsPickAgain`, `screenshot("v160-import-review")`, `screenshot("v160-import-progress")`, `screenshot("v160-import-done")`
  - `SettingsScreenTest`: `importSectionBetweenShareAndAppInfo`, `importedCountAndDeleteAllConfirm`, `importingShowsProgressAndDisablesDelete`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** JVM 전체, S22에서 `ImportScreenTest`, `SettingsScreenTest`, `MainTabsTest`를 하나씩 → 통과
- [ ] **Step 5: 커밋** `app: import — review screen, settings section and share/view entry`

### Task 6: app — "가져옴" 표시와 인텐트 기기 시험

**Files:**
- Modify: `ui/feed/FeedViewModel.kt` (`FeedCardState.imported: Boolean`, `importStore.observeImported()` 결합), `ui/feed/FeedCard.kt` (날짜 줄 끝 `" · 가져옴"`; `" · 진행 중"`과 함께일 일은 없다 — 가져온 여행은 늘 완료), `ui/history/TripDetailViewModel.kt` (`importedFrom: String?`), `ui/history/TripDetailScreen.kt` (제목 줄 아래 `"{fileName}에서 가져옴"`, bodySmall, onSurfaceVariant)
- Test: `FeedViewModelTest`(JVM) `importedCardIsMarked`; `FeedScreenTest.importedMarkOnCard`; `TripDetailScreenTest.importedFromLine`; `app/src/androidTest/.../MainActivityImportIntentTest.kt`(`sendIntentOpensImport`, `viewIntentOpensImport`, `sendMultipleOpensImportWithAll` — 시험 APK 안 파일을 FileProvider URI로)
- [ ] **Step 1: 실패하는 시험** (위 이름)
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** JVM 전체, S22에서 `FeedScreenTest`, `TripDetailScreenTest`, `MainActivityImportIntentTest`를 하나씩 → 통과
- [ ] **Step 5: 커밋** `app: import — imported mark on card and trip, intent entry tests`

### Task 7: 버전·문서 (part A). 기기 전체 시험·설치는 최종 수정 뒤 (part B)

**Files:**
- Modify: `app/build.gradle.kts` (verMinor 6, verPatch 0 → 10600, `1.6.0-dev`), `SettingsScreenTest`("V1.6.0"), `AppVersionTest`, `CHANGELOG.md`(`## [1.6.0] - 개발 중`: 추가·변경(DB 8판 `trip_import`, 설정 "기록 가져오기", 매니페스트 인텐트 필터)·문서), `README.md`(현재 버전 V1.6.0(개발 중), 로드맵 줄 상태 "개발 중", 설정 탭 설명에 "기록 가져오기", 문서 목록에 스펙·검증), `docs/VERSIONING.md`(V1.6.0 — 개발 중)
- Create: `docs/verification/gpx-import-field-test.md` (JVM 결과, S22 자리 "최종 수정 뒤 기록", 스펙 §7.3 점검표 9항목)
- [ ] **Step 1:** 버전과 시험 두 개, `./gradlew :core:test :app:testDebugUnitTest`
- [ ] **Step 2:** 문서
- [ ] **Step 3:** S22에서 `SettingsScreenTest`만
- [ ] **Step 4: 커밋** `docs: V1.6.0 version, changelog, README and verification`
- [ ] **(part B, 컨트롤러가 최종 검토와 수정 뒤에 함)** S22 androidTest 전체를 클래스 하나씩, `v160-*.png`를 받아 사용자에게, S22 `installDebug`, 시험용 GPX로 S22에서 실제 가져오기 한 번(설정 → 파일 고르기), 검증 문서에 결과, 커밋
