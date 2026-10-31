# 요약·통계 탭 (V1.4.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아래쪽 탭 [지도] [요약] [통계] [설정]을 만들고, 요약 탭(위성 지도 큰 카드 피드, 이번 주 띠, 제목 바꾸기)과 통계 탭(목표, 연속 주행, 기간 통계, 개인 기록, 누적 지도 입구)을 더한다.

**Architecture:**
- **`:core`:** 순수 계산을 둔다.
  - `core.stats`: 기간·합계·막대·비교, 연속·개인 기록·목표
  - `core.feed`: 그림 키
  - `RideTitle.shown`: 보이는 제목
- **`:app` 데이터:**
  - 새 표 `trip_label`(직접 제목·지역)을 둔다. DB v6, AutoMigration.
  - 끝난 구간을 흘려보내는 `RideStatsSource`(Room Flow)를 둔다.
  - 설정에 목표 두 개를 둔다.
- **`:app` 화면:**
  - `MAIN` 경로가 탭 틀이 되어 지금 `MainScreen`, 새 `FeedScreen`, 새 `StatsScreen`, 지금 `SettingsScreen`을 담는다.
  - 다른 경로와 화면 이동 규칙은 그대로다.
- **피드 위성 그림:** 영상의 `MapSnapshots`·`RouteOverlay`·`SatelliteTileSource`를 다시 써서 1280×720 PNG로 만든다. 한 번에 하나씩 만드는 대기 줄(`FeedThumbnails`)이 맡는다.

**Tech Stack:** Kotlin 2.4.20, Compose Material 3(`NavigationBar`), Navigation Compose, Room(v6, AutoMigration), DataStore, MapLibre Native 13.6.1(MapSnapshotter), JUnit5(:core)/JUnit4(:app), Compose UI test(androidTest).

**Spec:** `docs/superpowers/specs/2026-10-31-summary-stats-design.md`. 각 작업의 수치와 문구는 스펙이 기준이며, 여기서는 § 번호로 가리킨다.

## Global Constraints

- **버전:** 1.4.0, versionCode 10400(`verMinor = 4`, `verPatch = 0`), debug `1.4.0-dev`, 화면 `V1.4.0`. 올리는 것은 작업 10에서 한다.
- **글:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구 그대로 쓴다.
- **"주행 1회":** 거리 ≥ `MIN_RIDE_DISTANCE_M`(10 m)인 끝난 구간이다.
  - 날짜는 구간의 `localDate`다.
  - 주는 월요일부터 일요일까지다.
  - 오늘은 기기 시간대의 오늘이다. core 함수는 `today: LocalDate`를 인자로 받는다.
- **브이월드 키:** 로그·예외 메시지·커밋에 넣지 않는다.
  - 로그는 `VideoMapStyle.redacting`을 거친다.
  - 시험에는 가짜 키(`TESTKEY`)를 쓴다.
- **스레드 규칙(V1.3.0):** MapSnapshotter의 생성·start·cancel·투영은 Main에서 한다. 그림 Canvas는 소프트웨어 비트맵만 쓴다.
- **기기 규칙:**
  - connected 시험은 S22 `R5CT43TFS9A`에서만, 클래스 하나씩 돌린다. 화면을 깨운 뒤 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스> </dev/null`로 실행한다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정을 바꾸지 않는다.
- **JVM 시험 명령:** `./gradlew :core:test --tests '<패턴>'`, `./gradlew :app:testDebugUnitTest --tests '<패턴>'`
- **커밋 꼬리말:** 그 커밋을 쓴 모델 이름으로 `Co-Authored-By: Claude <모델> <noreply@anthropic.com>`

## Rulings made while planning (spec silent or spec wording replaced)

- **D1 — 제목·지역은 `trip` 칸이 아니라 새 표 `trip_label`에 둔다.** 스펙 §4는 `trip` 표 칸 두 개였다.
  - 이유: `TripDao.updateTrip`는 `Trip` 복사본 전체를 쓰는 `@Update`라서, 나중에 다른 코드가 여행 상태를 저장할 때 제목을 NULL로 덮는다.
  - 새 표(`tripId` 기본 키, `trip(id)` 외래 키 ON DELETE CASCADE)는 그 경로와 따로 놀고, 여행을 지우면 같이 지워진다.
  - 옮기기는 지금 방식대로 `AutoMigration(from = 5, to = 6)`이다.
  - 틀렸을 때의 비용: 사용자에게 보이는 차이 없음.
- **D2 — 피드 그림의 경로 색은 요약 이미지처럼 늘 주황(`FrameStyle.ROUTE_ORANGE`)이다.** 스펙 §2.4의 "날짜별 색 규칙 그대로"는 요약 이미지를 따른다는 뜻으로 읽었다. 요약 이미지는 여러 날도 주황이다. 틀렸을 때의 비용: 여러 날 카드 그림 색만 다름.
- **D3 — 지역·그림의 "같은 앱 실행 동안 다시 안 함"은 프로세스 메모리의 집합으로 센다.**
- **D4 — 개인 기록 신기록(§3.4)은 그 항목에 해당하는 더 이른 주행이 있을 때만이다.**
  - 예: 5 km 이상 주행이 처음이면 "가장 빠른 평균 속도 신기록"이 아니다.
  - "더 이른"은 `startedAt`이 더 작은 것이다.
- **D5 — "이번 기간 같은 날까지" 비교의 앞 기간 끝날(§3.3):**
  - [주]: 오늘 − 7일
  - [월]: 앞 달의 min(오늘 일, 앞 달 길이)일
  - [년]: `today.minusYears(1)`(2월 29일 → 2월 28일)
  - 앞 기간 시작은 그 기간의 첫날이다.
- **D6 — 제목 30자는 코드 포인트 수로 센다.** 이모지 하나가 1자다.

## Review Focus

1. **그림을 만드는 중에 그 여행이 지워지면:** 파일이 남거나 앱이 죽으면 안 된다. 작업 5 `deleteDuringRenderLeavesNoFile`.
2. **연말을 넘는 주:** 예: 2025-12-29(월)~2026-01-04.
   - [주]에서는 한 주이고, ‹ ›가 해를 넘나든다.
   - [월]에서는 12월 막대에 12월 날짜만, 1월 막대에 1월 날짜만 들어간다.
   - 연속 주행은 끊기지 않는다.
   - 작업 1 `weekAcrossYearEnd`, 작업 2 `streakAcrossYearEnd`.
3. **그림을 아직 못 만드는 여행:** 단순화 경로가 없거나 지점이 없는 여행이다. 피드는 선 그림 또는 빈 바탕으로 보여 주고 대기 줄이 멈추지 않는다. 작업 5 `missingInputsSkipsAndContinues`.
4. **목표 칸에 붙여 넣은 "1,000"이나 앞뒤 빈칸:** 1000으로 받는다. "1.5", "-3", "0", "100001"은 거절한다. 작업 2 `goalParsing`.
5. **그림을 만드는 중에 기록이 시작되면:**
   - 대기 줄은 다음 그림을 시작하지 않는다.
   - 하던 그림은 끝까지 그리고 저장한다.
   - 기록이 끝나면 이어서 만든다.
   - 작업 5 `pausesWhileBusyAndResumes`.

---

### Task 1: 기간 통계 계산 (`:core` `core.stats`)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/stats/StatsModels.kt`, `StatsCalculator.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/stats/StatsCalculatorTest.kt`

**Interfaces:**
- Consumes: `MIN_RIDE_DISTANCE_M`(`core.post`).
- Produces (작업 2·3·6·7·9가 씀):
  - `data class RideStat(val segmentId: Long, val tripId: Long, val startedAt: Long, val localDate: LocalDate, val distanceM: Double, val movingTimeMs: Long, val ascentM: Double)`: 끝난 구간 하나. 거르지 않은 상태로 들어온다.
  - `enum class PeriodKind { WEEK, MONTH, YEAR, ALL }`
  - `data class StatsPeriod(val kind: PeriodKind, val start: LocalDate, val endInclusive: LocalDate)`
  - `data class StatTotals(val distanceM: Double, val rides: Int, val movingTimeMs: Long, val ascentM: Double)`
  - `data class Bucket(val start: LocalDate, val endInclusive: LocalDate, val totals: StatTotals, val upcoming: Boolean)`
  - `data class Comparison(val current: StatTotals, val previous: StatTotals, val sameDay: Boolean)`
  - `object StatsCalculator`:
    - `fun rides(all: List<RideStat>): List<RideStat>`: ≥ 10 m만, 시작 순
    - `fun periodOf(kind: PeriodKind, date: LocalDate, rides: List<RideStat>, today: LocalDate): StatsPeriod`: ALL은 첫 주행 날(없으면 today)부터 today까지
    - `fun previous(period: StatsPeriod): StatsPeriod`, `fun next(period: StatsPeriod): StatsPeriod`: ALL에는 쓰지 않는다
    - `fun bounds(kind: PeriodKind, rides: List<RideStat>, today: LocalDate): ClosedRange<LocalDate>`: 첫 기간 시작부터 이번 기간 시작까지. 기록이 없으면 이번 기간만
    - `fun totals(rides: List<RideStat>, from: LocalDate, to: LocalDate): StatTotals`
    - `fun buckets(rides: List<RideStat>, period: StatsPeriod, today: LocalDate): List<Bucket>`
    - `fun comparison(rides: List<RideStat>, period: StatsPeriod, today: LocalDate): Comparison?`: ALL이면 null

- [ ] **Step 1: 실패하는 시험 쓰기.** 모두 `StatsCalculatorTest`:
  - `ridesDropsShortOnes`: 9.9 m는 빠지고 10.0 m는 남는다.
  - `periods`:
    - `periodOf(WEEK, 2026-10-31(토))`는 10-26~11-01.
    - MONTH는 10-01~10-31.
    - YEAR는 2026-01-01~12-31.
    - 주행이 2025-03-02부터 있을 때 ALL은 2025-03-02~today.
  - `weekAcrossYearEnd`(Review Focus 2):
    - `periodOf(WEEK, 2026-01-02)`는 2025-12-29~2026-01-04다. `previous`는 12-22 주, `next`는 01-05 주다.
    - 2025년 12월의 [월] 막대 마지막 칸은 12-29~12-31만이다.
    - 2026년 1월의 첫 칸은 01-01~01-04만이다.
  - `bucketsPerKind`:
    - WEEK는 7칸(월~일)이다.
    - 2026년 10월(MONTH)은 칸 5개다: 10-01~04, 05~11, 12~18, 19~25, 26~31.
    - YEAR는 12칸이다.
    - ALL은 2025년, 2026년 2칸이다.
    - today가 10-28이면 10월 마지막 칸과 11·12월 칸이 `upcoming`이다.
  - `totalsSumFourValues`: 거리·횟수·이동 시간·상승 합. 빈 기간은 0이다.
  - `comparisonPastPeriodsFull`: 9월 전체 대 8월 전체이고 `sameDay == false`다.
  - `comparisonCurrentSameDay`(D5):
    - today 2026-10-12, MONTH: 10-01~10-12 대 09-01~09-12
    - today 2027-03-31: 03-01~31 대 02-01~02-28
    - today 2028-02-29, YEAR: 2028-01-01~02-29 대 2027-01-01~02-28
    - today 2026-10-31(토), WEEK: 10-26~31 대 10-19~24
  - `comparisonAllIsNull`
  - `boundsStopAtFirstRideAndThisPeriod`
- [ ] **Step 2: 실행해 실패 확인.** `./gradlew :core:test --tests '*StatsCalculatorTest*'`. 컴파일 오류로 끝나야 한다.
- [ ] **Step 3: 구현.** `java.time`만 쓴다. 주 시작은 `TemporalAdjusters.previousOrSame(MONDAY)`다. [월] 칸은 그 달 1일이 든 주부터 그 달에 걸친 주마다, 그 달 날짜로 잘라 만든다.
- [ ] **Step 4: 통과 확인.** 같은 명령으로 PASS.
- [ ] **Step 5: 커밋.** `core: V1.4.0 period statistics — periods, buckets, totals, same-day comparison`

### Task 2: 연속 주행·개인 기록·목표 (`:core` `core.stats`)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/stats/RideRecords.kt`, `Goals.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/stats/RideRecordsTest.kt`, `GoalsTest.kt`

**Interfaces:**
- Consumes: 작업 1의 `RideStat`, `StatsCalculator.rides`.
- Produces:
  - `data class Streak(val weeks: Int, val includesThisWeek: Boolean)`
  - `data class StreakRun(val weeks: Int, val firstWeek: LocalDate, val lastWeek: LocalDate)`: 주 시작(월요일)
  - `enum class RideRecordKind { LONGEST_DISTANCE, LONGEST_TIME, MOST_ASCENT, FASTEST_AVG }`
  - `data class RideRecord(val kind: RideRecordKind, val ride: RideStat, val value: Double)`: FASTEST_AVG 값은 m/s
  - `data class PeriodRecord(val start: LocalDate, val distanceM: Double)`
  - `object RideRecords`:
    - `const val FASTEST_MIN_DISTANCE_M = 5_000.0`
    - `fun streak(rides: List<RideStat>, today: LocalDate): Streak`
    - `fun longestStreak(rides: List<RideStat>): StreakRun?`
    - `fun rideRecords(rides: List<RideStat>): List<RideRecord>`: 주행이 2회 미만이면 빈 목록
    - `fun bestWeek(rides: List<RideStat>): PeriodRecord?`, `fun bestMonth(rides: List<RideStat>): PeriodRecord?`
    - `fun recordHolderTrips(rides: List<RideStat>): Set<Long>`
    - `fun newRecords(rides: List<RideStat>, segmentId: Long): List<RideRecordKind>`: D4
  - `sealed interface GoalInput { data object Empty; data class Valid(val km: Int); data object Invalid }`
  - `object Goals`:
    - `const val MIN_KM = 1`, `const val MAX_KM = 100_000`
    - `fun parse(text: String): GoalInput`
    - `fun crossed(goalKm: Int?, beforeM: Double, afterM: Double): Boolean`
    - `fun yearProjectionKm(yearDistanceM: Double, today: LocalDate): Int?`
    - `fun weekBeforeAfter(rides: List<RideStat>, segmentId: Long): Pair<Double, Double>`: 그 구간 `localDate`가 든 주의 거리를 그 구간 빼고/넣고 구한다
    - `fun yearBeforeAfter(rides: List<RideStat>, segmentId: Long): Pair<Double, Double>`: 같은 방식으로 해 기준

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `RideRecordsTest`:
    - `streakCountsBackFromThisOrLastWeek`:
      - 이번 주와 앞 3주에 주행이 있으면 (4, true)다.
      - 이번 주에 없고 지난주부터 3주면 (3, false)다.
      - 지난주에도 없으면 (0, false)다.
    - `streakAcrossYearEnd`(Review Focus 2): 2025-12-22 주, 2025-12-29 주, 2026-01-05 주에 주행이 있고 today가 2026-01-07이면 (3, true)다.
    - `longestStreakAndItsWeeks`: 가장 긴 연속의 주 수와 첫 주·마지막 주. 같은 길이면 먼저 것.
    - `recordsNeedTwoRides`: 1회면 빈 목록이다.
    - `fourRideRecords`: 각 항목의 최대값을 고른다. 같은 값이면 `startedAt`이 작은 것이다. FASTEST_AVG는 4,999 m 주행을 빼고 5,000 m부터 넣는다. 이동 시간 0은 뺀다.
    - `bestWeekAndMonth`: 같은 거리면 먼저 기간이다.
    - `recordHolderTripsOnlyCurrentHolders`
    - `newRecordsNeedAnEarlierRideOfThatKind`(D4):
      - 첫 주행은 빈 목록이다.
      - 앞 최고와 같으면 없다.
      - 크면 그 항목이 나온다.
      - 앞에 5 km 이상 주행이 없으면 FASTEST_AVG는 없다.
  - `GoalsTest`:
    - `goalParsing`(Review Focus 4):
      - `""`와 `"  "`는 Empty다.
      - `"1,000"`, `" 1000 "`은 Valid(1000)다.
      - `"1"`과 `"100000"`은 Valid다.
      - `"0"`, `"100001"`, `"1.5"`, `"-3"`, `"abc"`는 Invalid다.
    - `crossedOnlyOnTheWayUp`:
      - (100, 90 km, 100 km)는 true다.
      - (100, 100 km, 120 km)는 false다.
      - (null, …)은 false다.
      - (100, 50 km, 99.9 km)는 false다.
    - `yearProjection`:
      - 2026-01-06이면 null이다(6일째).
      - 0 m이면 null이다.
      - 2026-03-01(60일째)에 200 km면 round(200/60·365/10)·10 = 1220이다.
      - 윤년 2028-03-01(61일째)에는 366일로 계산한다.
    - `weekAndYearBeforeAfter`: 같은 주의 다른 주행은 더하고, 다른 주는 뺀다. 10 m 미만은 넣지 않는다.
- [ ] **Step 2: 실행해 실패 확인.** `./gradlew :core:test --tests '*RideRecordsTest*' --tests '*GoalsTest*'`
- [ ] **Step 3: 구현.** 모든 함수는 `StatsCalculator.rides`로 거른 주행만 본다.
- [ ] **Step 4: 통과 확인.**
- [ ] **Step 5: 커밋.** `core: V1.4.0 streaks, personal records and goals`

### Task 3: 저장소 — `trip_label`(DB v6), 주행 흐름, 목표 설정

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`(version 6, `AutoMigration(from = 5, to = 6)`, 엔터티 추가), `SettingsRepository.kt`, `AppContainer.kt`
- Create:
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/trip/TripLabel.kt`
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/stats/RideStatsSource.kt`
  - `app/src/main/java/com/cowork/bikerecoder/data/RoomTripLabelStore.kt`, `RoomRideStatsSource.kt`
  - `app/schemas/com.cowork.bikerecoder.data.AppDatabase/6.json`(빌드가 만든 것을 커밋)
- Test:
  - `app/src/androidTest/java/com/cowork/bikerecoder/data/RideDatabaseMigrationTest.kt`(`v5DataSurvivesMigrationTo6` 추가)
  - `app/src/androidTest/java/com/cowork/bikerecoder/data/RoomTripLabelStoreTest.kt`
  - `app/src/androidTest/java/com/cowork/bikerecoder/data/SettingsRepositoryGoalsTest.kt`(DataStore가 기기 Context를 쓰므로 S22)

**Interfaces:**
- Produces:
  - `data class TripLabel(val customTitle: String? = null, val regionName: String? = null)`
  - `interface TripLabelStore`:
    - `suspend fun label(tripId: Long): TripLabel`: 줄이 없으면 빈 `TripLabel()`
    - `fun observeLabels(): Flow<Map<Long, TripLabel>>`
    - `suspend fun setCustomTitle(tripId: Long, title: String?)`
    - `suspend fun setRegionName(tripId: Long, name: String)`
    - 두 set은 다른 칸을 지키는 upsert다. DAO `@Transaction`으로 한다.
  - `interface RideStatsSource { fun observeRides(): Flow<List<RideStat>> }`: `endedAt`이 있는 모든 구간(거르지 않음). Room `Flow` 쿼리라 구간이 바뀌면 다시 나온다.
  - `AppSettings`에 `weeklyGoalKm: Int? = null`, `yearlyGoalKm: Int? = null`을 더한다.
  - `SettingsRepository.setGoals(weeklyKm: Int?, yearlyKm: Int?)`: 키는 `intPreferencesKey("weekly_goal_km")`, `intPreferencesKey("yearly_goal_km")`, null이면 키를 지운다.
  - `AppContainer.tripLabels: TripLabelStore`, `AppContainer.rideStats: RideStatsSource`
- 엔터티: `TripLabelEntity(@PrimaryKey val tripId: Long, val customTitle: String?, val regionName: String?)`, 표 이름 `trip_label`, `ForeignKey(TripEntity, ["id"], ["tripId"], onDelete = CASCADE)`

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `v5DataSurvivesMigrationTo6`: v5에 여행·구간·`trip_media` 한 줄씩 넣고 6으로 옮긴다. 기존 값이 그대로이고, `trip_label`이 비어 있고, 외래 키 검사(`PRAGMA foreign_key_check`)가 깨끗하다.
  - `RoomTripLabelStoreTest`:
    - `setTitleKeepsRegion`
    - `setRegionKeepsTitle`
    - `nullTitleClears`
    - `deletingTripDeletesLabel`
    - `observeLabelsEmitsOnChange`
  - `RoomRideStatsSource`: 같은 androidTest 파일이나 `RoomRideStoreTest`에 `observeRidesEmitsEndedSegmentsOnly`를 둔다. 열린 구간은 빠지고, 닫으면 새 목록이 나온다.
  - `SettingsRepositoryGoalsTest`:
    - `goalsRoundTrip`: 저장 → 읽기, null 저장 → 키 없음
- [ ] **Step 2: 실패 확인.** 컴파일 오류가 나면 된다.
- [ ] **Step 3: 구현.**
  - 버전을 6으로 올리고 빌드해 `6.json`이 생겼는지 확인한다.
  - `AppContainer`에서 Room 구현을 만든다.
- [ ] **Step 4: 통과 확인.**
  - JVM: `./gradlew :app:testDebugUnitTest`
  - S22: `RideDatabaseMigrationTest`, `RoomTripLabelStoreTest`, `RoomRideStoreTest`를 한 클래스씩 돌린다.
- [ ] **Step 5: 커밋.** `data: DB v6 trip_label, ride stats flow, weekly and yearly goals`

### Task 4: 보이는 제목, 지역 저장, 제목 바꾸기, 영상 제목

**Files:**
- Modify:
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/video/RideTitle.kt`
  - `app/src/main/java/com/cowork/bikerecoder/video/VideoInputs.kt`, `VideoJob.kt`
  - `app/src/main/java/com/cowork/bikerecoder/AppContainer.kt`
  - `app/src/main/java/com/cowork/bikerecoder/ui/history/TripDetailViewModel.kt`, `TripDetailScreen.kt`
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/feed/TripRegions.kt`
  - `app/src/main/java/com/cowork/bikerecoder/ui/history/RenameTripDialog.kt`
- Test:
  - `core/.../video/RideTitleTest.kt`
  - `app/src/test/.../feed/TripRegionsTest.kt`
  - `app/src/test/.../video/VideoJobTest.kt`, `VideoInputsLoaderTest.kt`(있는 것에 더함)
  - `app/src/test/.../ui/history/TripDetailViewModelTest.kt`
  - `app/src/androidTest/.../ui/history/RenameTripDialogTest.kt`

**Interfaces:**
- Consumes: 작업 3 `TripLabel`, `TripLabelStore`. 지금 쓰는 `RideTitle.base`/`withRegion`/`shortRegion`, `KakaoLocalClient.regionOf`.
- Produces:
  - `RideTitle.MAX_CUSTOM_CODE_POINTS = 30`
  - `RideTitle.cleanCustom(input: String): String?`: trim, 빈 문자열이면 null, 30 코드 포인트까지만
  - `RideTitle.shown(label: TripLabel, firstStartedAt: Long, rideDays: Int, zone: ZoneId): String`: `customTitle ?: withRegion(base(...), regionName)`
  - `class TripRegions(labels: TripLabelStore, lookup: suspend (GeoPoint) -> String?, log: WarnLog)`:
    - `suspend fun ensure(tripId: Long, start: GeoPoint): String?`
    - 동작: 저장된 지역이 있으면 바로 준다. 없으면 이 프로세스에서 처음일 때만 묻는다(D3). 5초(`REGION_TIMEOUT_MS`)를 넘기면 포기하고, 찾으면 저장한다.
  - `AppContainer.tripRegions`
  - `VideoInputs`에 `titleIsCustom: Boolean = false`를 더한다.
    - `VideoInputsLoader`는 `TripLabelStore`를 받는다.
    - `rideTitle = label.customTitle ?: RideTitle.base(...)`
  - `VideoJob`:
    - 직접 제목이면 지역을 붙이지 않는다.
    - 아니면 `regionOf` 대신 `TripRegions.ensure(inputs.tripId, inputs.start())`를 쓴다(저장된 값 먼저).
  - `TripDetailViewModel`:
    - `title: StateFlow<String?>`: 보이는 제목
    - `customTitle: StateFlow<String?>`
    - `fun rename(input: String)`: `cleanCustom` 결과를 `setCustomTitle`로 저장
    - 처음 열릴 때 `tripRegions.ensure`를 부른다.
  - `RenameTripDialog(current: String?, placeholder: String, onSave: (String) -> Unit, onDismiss: () -> Unit)`
- 화면: 여행 기록 화면 내용 맨 위(지도 위)에 제목 줄을 둔다.
  - 제목은 `titleLarge`이고 오른쪽에 ✎ `IconButton`(설명 "제목 바꾸기")이 있다.
  - 창 문구: 제목 "제목 바꾸기", [저장] [취소]
  - 입력은 30 코드 포인트를 넘으면 더 받지 않는다(§2.5).

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `RideTitleTest`:
    - `shownPrefersCustomThenRegion`: 직접 제목 "한강 한 바퀴"면 지역이 있어도 "한강 한 바퀴"다. 없으면 "저녁 라이딩 · 서울 강서구"이고, 지역도 없으면 "저녁 라이딩"이다.
    - `thirtyCodePointsIncludingEmoji`(D6): `cleanCustom("  한강 ")`는 "한강"이다. 이모지 30개는 그대로 남고, 31개는 30개(코드 포인트)로 줄어든다. `"   "`는 null이다.
  - `TripRegionsTest`:
    - `storedRegionSkipsLookup`
    - `failureNotRetriedThisProcess`: 두 번째 `ensure`는 lookup을 부르지 않는다.
    - `successIsStored`
    - `slowLookupGivesUpAfterFiveSeconds`: 가상 시간 사용
  - `VideoJobTest`:
    - `customTitleIsUsedWithoutRegion`
    - `cachedRegionIsUsedWithoutLookup`
  - `TripDetailViewModelTest`:
    - `renameSavesCleanedTitle`
    - `blankRenameRestoresAutoTitle`
  - `RenameTripDialogTest`(S22):
    - 31자를 넣으면 30자만 남는다.
    - [저장]은 입력을 넘긴다.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.** `AppContainer.videoRegion`의 카카오 호출은 `TripRegions`의 lookup으로 옮긴다.
- [ ] **Step 4: 통과 확인.** core, app JVM 시험을 돌리고, S22에서 `RenameTripDialogTest`와 `DetailMapPhotosTest`(여행 기록 화면을 그림)를 돌린다.
- [ ] **Step 5: 커밋.** `app: trip titles — custom title, stored region, rename dialog, video uses them`

### Task 5: 피드 위성 그림 (키, 그리기, 대기 줄)

**Files:**
- Create:
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/feed/FeedThumbKey.kt`
  - `app/src/main/java/com/cowork/bikerecoder/video/FeedThumbnailRenderer.kt`
  - `app/src/main/java/com/cowork/bikerecoder/feed/FeedThumbnails.kt`
- Modify:
  - `app/src/main/java/com/cowork/bikerecoder/offline/NetworkWaiter.kt`(`online(): Flow<Boolean>` 추가)
  - `AppContainer.kt`
- Test:
  - `core/.../feed/FeedThumbKeyTest.kt`
  - `app/src/test/.../feed/FeedThumbnailsTest.kt`
  - `app/src/androidTest/.../video/FeedThumbnailRendererTest.kt`

**Interfaces:**
- Consumes: `VideoInputsLoader.load(tripId)`(작업 4 이후), `VideoBackend.probeSatellite`, `SatelliteTileSource`, `MapSnapshots`, `RouteOverlay`(`RouteStyle.FULL`), `MapMarks.startAndEnd`, `CameraPath.overview`, `VideoMapStyle.ATTRIBUTION`, `VideoJobControl.runningTripId`, `RideUiState`, `NavUiState`.
- Produces:
  - `data class ThumbSegment(val id: Long, val endedAt: Long, val distanceM: Double)`
  - `object FeedThumbKey`:
    - `const val VERSION = 1`
    - `fun of(segments: List<ThumbSegment>): String`: id 순으로 정렬한 "id:endedAt:거리(0.1 m 반올림)"와 판을 이어 SHA-256, 앞 12 hex
  - `class FeedThumbnailRenderer(context: Context, backend: VideoBackend, plainTiles: TileSource, satelliteKey: String)`:
    - `suspend fun render(inputs: VideoInputs): Bitmap?`: 1280×720, 위성 확인이 OK가 아니면 null(§2.4, D2)
    - 그림 내용: 여백 64 px로 맞춘 `CameraPath.overview` 한 장, 주황 `RouteStyle.FULL`, 출발·도착 점, 오른쪽 아래 출처 한 줄
  - `class FeedThumbnails(dir: File, render: suspend (tripId: Long) -> Bitmap?, busy: Flow<Boolean>, online: Flow<Boolean>, scope: CoroutineScope, log: WarnLog)`:
    - `val files: StateFlow<Map<Long, File>>`: 여행마다 가장 최근 `key`의 파일이 있으면 넣는다
    - `fun want(tripId: Long, key: String)`: 카드가 화면에 들어옴
    - `fun unwant(tripId: Long)`: 화면에서 나감
    - `suspend fun delete(tripId: Long)`
    - `suspend fun prune(keep: Set<Long>)`
  - 동작:
    - 한 번에 하나씩, `want` 순서로 만든다.
    - `busy`가 true인 동안 새 그림을 시작하지 않는다.
    - 실패(null·예외)한 (tripId, key)는 이 프로세스에서 다시 안 한다. `online`이 false→true로 바뀌면 그 기억을 지운다.
    - 저장은 `<tripId>-<key>.png.tmp`에 쓴 뒤 이름을 바꾼다. 그다음 같은 여행의 다른 파일을 지운다.
    - 쓰는 중에 `delete`가 오면 저장된 파일을 지운다.
  - `AppContainer.feedThumbnails`:
    - `dir = filesDir/feed_thumbs`
    - `busy` = 기록 `Active`/`Starting` 또는 안내 `Active`/`Starting` 또는 `videoJob.runningTripId != null`
    - 시작할 때 남은 여행 id로 `prune`한다(§4).

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `FeedThumbKeyTest`:
    - `sameSegmentsSameKeyInAnyOrder`
    - `changedEndOrDistanceOrSetChangesKey`
    - `twelveHexChars`
  - `FeedThumbnailsTest`(가짜 render, 임시 폴더, `StandardTestDispatcher`):
    - `rendersOneAtATimeInWantOrder`
    - `unwantRemovesFromQueue`
    - `failureNotRetriedUntilOnlineAgain`
    - `newKeyReplacesOldFile`
    - `deleteDuringRenderLeavesNoFile`(Review Focus 1)
    - `missingInputsSkipsAndContinues`(Review Focus 3): render가 null을 주는 여행 뒤의 여행도 만든다.
    - `pausesWhileBusyAndResumes`(Review Focus 5)
    - `pruneRemovesFilesOfGoneTrips`
  - `FeedThumbnailRendererTest`(S22, 위성 확인 OK를 돌려주는 가짜 backend와 MockWebServer 타일):
    - 1280×720 비트맵이고, 경로가 있는 화소가 주황 계열이다.
    - 위성 확인 FAILED이면 null이다.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.** 그리기는 `SummaryImageRenderer.drawMap`의 순서를 따른다.
- [ ] **Step 4: 통과 확인.** JVM 시험을 돌리고, S22에서 `FeedThumbnailRendererTest`를 돌린다.
- [ ] **Step 5: 커밋.** `app: feed satellite thumbnails — key, renderer, one-at-a-time queue`

### Task 6: 요약 탭 화면 (`FeedScreen`, `FeedViewModel`)

**Files:**
- Move: `ui/history/HistoryViewModel.kt` → `ui/feed/FeedViewModel.kt`(`git mv` 뒤 이름 바꿈), 시험 `HistoryViewModelTest.kt` → `ui/feed/FeedViewModelTest.kt`
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/ui/feed/FeedScreen.kt`, `FeedCard.kt`, `WeekStrip.kt`, `FeedFormat.kt`
  - `app/src/androidTest/.../ui/feed/FeedScreenTest.kt`
- Modify:
  - `ui/history/HistoryScreen.kt`: 작업 8에서 지운다. 이 작업에서는 `HISTORY` 경로가 `FeedScreen`을 그리게 바꾼다(`onBack` 있음).
  - `HistoryFormat.kt`: 달 머리글 등 재사용 함수는 그대로 둔다.

**Interfaces:**
- Consumes:
  - 작업 1·2: `StatsCalculator`, `RideRecords.streak`, `RideRecords.recordHolderTrips`
  - 작업 3: `RideStatsSource`, `TripLabelStore.observeLabels`, `SettingsRepository.settings`(목표)
  - 작업 4: `RideTitle.shown`, `TripRegions`
  - 작업 5: `FeedThumbnails`, `FeedThumbKey`
  - 기존: `PostStore.segmentCards`, `PostStore.observePhotos`, `TripMediaStore.observe`, `PhotoThumbnails`, 지금 `HistoryViewModel`의 삭제·[정리]·짧은 기록 규칙
- Produces:
  - `FeedViewModel`(지금 기능 + 아래)
    - `val weekStrip: StateFlow<WeekStripState>`
    - `val months: StateFlow<List<FeedMonth>>`
    - `fun onCardShown(tripId: Long)`, `fun onCardHidden(tripId: Long)`: `FeedThumbnails.want/unwant`와 `TripRegions.ensure`로 넘긴다
  - `data class WeekStripState(val distanceM: Double, val rides: Int, val diffM: Double?, val weeklyGoalKm: Int?, val streak: Streak)`: `diffM`은 지난주 같은 날까지 비교, D5
  - `data class FeedMonth(val header: String, val cards: List<FeedCardState>)`
  - `data class FeedCardState`:
    - 카드 글: `tripId`, `title`, `dateLine`, `inProgress`
    - 숫자: `distanceM`, `movingTimeMs`, `ascentM`, `avgSpeedMps`, `avgBpm: Int?`
    - 사진: `photoUris: List<…>`(최대 3), `morePhotos: Int`
    - 영상·표시: `videoUri: String?`, `trophy: Boolean`
    - 그림: `thumbKey: String?`(그릴 수 없으면 null), `thumbFile: File?`, `routes`(선 그림용, 지금 카드와 같은 형태)
  - `FeedScreen(viewModel, thumbnails: PhotoThumbnails, onOpenTrip: (Long) -> Unit, onOpenVideo: (TripMedia) -> Unit, onOpenStatsWeek: () -> Unit, onStartRecording: () -> Unit, onSetGoals: () -> Unit, scrollToTop: Int, onBack: (() -> Unit)? = null)`
- 문구와 배치는 §2.1~2.3, §5다.
  - 날짜 줄: 하루는 "10월 25일 (토) 오후 6:12", 여러 날은 "10월 18일~20일"이다.
  - 그림이 없으면 `RoutePreview` 선 그림을 어두운 바탕(`Color(0xFF1E2A22)`) 16:9로 그린다.
  - 카드가 화면에 들어오고 나가는 것은 `LazyListState.layoutInfo.visibleItemsInfo`로 안다.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `FeedViewModelTest`(기존 시험은 이름만 옮기고 그대로 통과해야 한다):
    - `weekStripSumsThisWeekAndComparesSameDay`
    - `weekStripGoalAndStreak`
    - `cardTitleUsesLabel`
    - `cardNumbersSumRidesOfTenMetresOrMore`
    - `cardPhotosMaxThreePlusMore`
    - `cardVideoIsNewestVideo`
    - `trophyOnRecordHolderTripsOnly`
    - `inProgressCardHasNoThumbKey`
    - `shownCardRequestsThumbAndRegion`
    - `deleteAlsoDeletesThumbnail`
  - `FeedScreenTest`(S22, `HistoryFixtures`를 넓혀 씀):
    - 카드 제목·날짜·숫자 세 개·"+N"·▶·🏆가 보인다.
    - 카드를 누르면 `onOpenTrip`이다.
    - ▶를 누르면 `onOpenVideo`다.
    - 길게 누르면 삭제 확인이 나온다.
    - 기록이 없으면 "첫 주행을 기록해 보세요"와 [기록 시작]이 보인다.
    - 띠를 누르면 `onOpenStatsWeek`다.
    - 목표가 없으면 [목표 정하기]가 보인다.
    - 스크린샷 한 장을 `Download/bikeRecoderTest/v140-feed.png`에 남긴다(지금 스크린샷 시험 방식).
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.** JVM 시험, S22 `FeedScreenTest`. 지금의 `HistoryScreenTest` 단언 중 피드에 남는 것(삭제 확인, 짧은 기록 [정리])은 이 작업에서 `FeedScreenTest`로 옮기고 `HistoryScreenTest`를 지운다.
- [ ] **Step 5: 커밋.** `app: summary tab — satellite feed cards, this-week strip`

### Task 7: 통계 탭 화면 (`StatsScreen`, `StatsViewModel`, 목표 창)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/stats/StatsViewModel.kt`, `StatsScreen.kt`, `GoalCard.kt`, `GoalDialog.kt`, `BarChart.kt`, `StatsFormat.kt`
- Test:
  - `app/src/test/.../ui/stats/StatsViewModelTest.kt`, `StatsFormatTest.kt`
  - `app/src/androidTest/.../ui/stats/StatsScreenTest.kt`, `GoalDialogTest.kt`

**Interfaces:**
- Consumes: 작업 1·2 전부, 작업 3 `RideStatsSource`, `SettingsRepository.settings`/`setGoals`, `PeriodFilter`(누적 지도).
- Produces:
  - `StatsViewModel(rides: RideStatsSource, settings: SettingsRepository, today: () -> LocalDate = LocalDate::now)`:
    - `val state: StateFlow<StatsState>`
    - `fun selectKind(kind: PeriodKind)`
    - `fun previous()`, `fun next()`
    - `fun show(kind: PeriodKind, start: LocalDate)`
    - `fun selectMetric(metric: StatMetric)`
    - `fun selectBar(index: Int?)`
    - `fun saveGoals(weekly: GoalInput, yearly: GoalInput)`
  - `enum class StatMetric { DISTANCE, TIME, ASCENT }`
  - `StatsState`:
    - `period`, `title`
    - `canPrevious`, `canNext`
    - `totals`, `comparisonText: String?`
    - `bars: List<BarState>`, `selectedBar: Int?`, `metric`
    - `weeklyGoal: GoalProgress?`, `yearlyGoal: GoalProgress?`
    - `projectionText: String?`, `streakText: String`
    - `records: List<RecordRow>`(주행 2회 미만이면 빈 목록)
    - `cumulativeRides: Int`, `cumulativeFilter: PeriodFilter`
  - `data class RecordRow(val label: String, val value: String, val target: RecordTarget?)`
  - `sealed interface RecordTarget { data class Segment(val id: Long); data class Period(val kind: PeriodKind, val start: LocalDate) }`
  - `StatsScreen(viewModel, onOpenSegment: (Long) -> Unit, onOpenCumulative: (PeriodFilter) -> Unit, scrollToTop: Int)`
  - `GoalDialog(weeklyKm: Int?, yearlyKm: Int?, onSave: (GoalInput, GoalInput) -> Unit, onDismiss: () -> Unit)`
- 처음 기간은 [월]·이번 달, 처음 값은 [거리]다(§3.3).
- 문구(`StatsFormat`, §3.1~3.5·§5):
  - 비교 문장
    - 지난 기간: "9월보다 +21.3 km (+33%)", "지난주보다 −4.0 km (−12%)"(빼기 기호 U+2212), "2025년보다 …"
    - 이번 기간: "지난달 이맘때보다", "지난주 이맘때보다", "작년 이맘때보다"
    - 퍼센트는 앞 값 > 0일 때만 붙이고, 둘 다 0이면 null이다.
  - 연속 문구 셋(§3.2)
  - 연말 예상 "지금 속도면 연말에 약 1,180 km"
  - 막대 값 "10월 3주 · 24.1 km"
  - 기록 줄 값 "42.1 km · 2026년 9월 14일", 주 "10월 3주", 연속 "6주 (8월 4주~10월 1주)"
  - 기간 제목: 주는 누적 지도 `WeekOption.label`과 같은 규칙이다.
- 그래프는 Compose `Canvas`로 직접 그린다. 새 라이브러리는 없다.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `StatsFormatTest`:
    - `comparisonTexts`(위 예 그대로, U+2212, 퍼센트 규칙)
    - `streakTexts`
    - `projectionText`
    - `recordRowTexts`
    - `periodTitles`: "10월 4주 (10/19~10/25)", "2026년 10월", "2026년", "전체"
  - `StatsViewModelTest`:
    - `startsOnThisMonthDistance`
    - `previousNextStopAtBounds`
    - `showJumpsToPeriod`
    - `selectBarTogglesAndClearsOnPeriodChange`
    - `recordsHiddenUnderTwoRides`
    - `saveGoalsWritesValidAndClearsEmpty`
    - `invalidGoalNotSaved`
    - `cumulativeFilterMatchesPeriod`: 주 → `weekStart`, 월 → `year`·`month`, 년 → `year`, 전체 → `PeriodFilter()`
  - `GoalDialogTest`(S22):
    - 추천 칩을 누르면 칸이 채워진다.
    - "0"을 넣으면 "1~100,000 사이 숫자를 넣어 주세요"가 보이고 [저장]이 막힌다.
    - 칸을 비우고 저장하면 Empty다.
  - `StatsScreenTest`(S22):
    - 기간 칩과 ‹ › 끝이 흐리다.
    - 막대를 누르면 값이 뜬다.
    - 기록 줄을 누르면 `onOpenSegment`다.
    - 누적 지도 줄을 누르면 `onOpenCumulative(filter)`다.
    - 스크린샷 두 장(`v140-stats-goals.png`, `v140-stats-nogoals.png`)을 남긴다.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.** JVM 시험, S22 `GoalDialogTest`·`StatsScreenTest`.
- [ ] **Step 5: 커밋.** `app: stats tab — goals, streak, period statistics, personal records`

### Task 8: 탭 틀, 기록·안내 띠, 경로 정리

**Files:**
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/ui/main/MainTabs.kt`(`enum class MainTab { MAP, FEED, STATS, SETTINGS }`, `MainTabsScaffold`)
  - `app/src/main/java/com/cowork/bikerecoder/ui/main/RideBand.kt`
- Modify:
  - `ui/BikeNavHost.kt`: MAIN이 탭 틀이 된다. `HISTORY` 경로를 지우고, `CUMULATIVE`에 기간 인자를 더한다.
  - `ui/main/MainScreen.kt`: ≡ 버튼과 메뉴, `onOpenHistory`·`onOpenCumulative`·`onOpenSettings`를 지운다.
  - `ui/settings/SettingsScreen.kt`: `onBack: (() -> Unit)?`로 바꾸고, null이면 뒤로 화살표가 없다.
  - `ui/cumulative/CumulativeMapViewModel.kt`: `SavedStateHandle`의 처음 기간을 쓴다.
- Delete: `ui/history/HistoryScreen.kt`
- Test:
  - `app/src/test/.../ui/main/RideBandTest.kt`, `MainTabsBackTest.kt`
  - `app/src/test/.../ui/cumulative/CumulativeMapViewModelTest.kt`(처음 기간)
  - `app/src/androidTest/.../ui/main/MainTabsTest.kt`

**Interfaces:**
- Consumes: 작업 6 `FeedScreen`/`FeedViewModel`, 작업 7 `StatsScreen`/`StatsViewModel`, 기존 `rideRoute(guidance, ride)`, `RideUiState.Active`(`state`, `stats.distanceM`, `stats.movingTimeMs`), `NavUiState.Active`(남은 거리).
- Produces:
  - `Routes.cumulative(filter: PeriodFilter): String`과 `Routes.CUMULATIVE_PATTERN`
    - 선택 인자: `year`(Int, −1 = 없음), `month`(Int, −1), `weekStart`(Long epochDay, `Long.MIN_VALUE` = 없음)
    - 인자가 없으면 `PeriodFilter()`
  - `fun rideBandText(ride: RideUiState, guidance: NavUiState): String?`: §1.4 표 그대로, 기록이 안내보다 먼저다
  - `MainTabsScaffold(selected: MainTab, onSelect: (MainTab) -> Unit, band: String?, onBand: () -> Unit, content: @Composable (MainTab) -> Unit)`
    - 탭 이름: "지도" "요약" "통계" "설정"
    - 띠는 `selected != MAP`이고 `band != null`일 때만 보인다.
- 동작(§1.1):
  - 고른 탭은 `rememberSaveable`로 지킨다. 새로 열면 MAP이다.
  - [요약]·[통계]·[설정]에서 뒤로 버튼(`BackHandler`)을 누르면 MAP으로 간다.
  - 같은 탭을 다시 누르면 `scrollToTop` 숫자를 올린다.
  - 요약 띠를 누르면 STATS로 바꾸고 `statsViewModel.show(WEEK, 이번 주 월요일)`을 부른다.
  - 요약의 [기록 시작]은 MAP으로 바꾼다.
  - 요약·통계의 [목표 정하기]는 같은 `GoalDialog`를 띄운다.
  - `FeedViewModel`·`StatsViewModel`은 MAIN 항목 범위로 만든다.
  - 지금 MAIN의 다른 동작(시작 경로, `openOnMain`, `isCurrent` 겹 누름 방지)은 그대로다.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `RideBandTest`:
    - 기록 중이면 "● 기록 중 · 12.4 km · 48분"이다.
    - 일시정지면 "Ⅱ 일시정지 · 12.4 km · 48분"이다.
    - 안내만 돌면 "▲ 안내 중 · 남은 거리 3.2 km"다.
    - 둘 다 돌면 기록 띠다.
    - 아무것도 없으면 null이다.
  - `CumulativeMapViewModelTest.startsOnGivenPeriod`
  - `MainTabsTest`(S22):
    - 네 탭을 오간다.
    - [요약]에서 뒤로 가면 [지도]다.
    - 여행 기록에서 뒤로 가면 [요약]이다.
    - 화면을 돌린 뒤에도 탭이 남는다.
    - 띠가 [지도]에서는 숨고 [요약]에서는 보인다(가짜 상태). 띠를 누르면 기록 경로로 간다.
    - ≡ 버튼이 없다.
    - 스크린샷 `v140-band.png`를 남긴다.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.**
  - JVM 전체를 돌린다.
  - S22에서 `MainTabsTest`, `CumulativeMapScreenTest`, `SettingsScreenTest`를 돌린다.
  - 지도 탭 안의 `MainScreen`을 쓰는 다른 androidTest가 있으면 ≡ 단언을 지우고 돌린다.
- [ ] **Step 5: 커밋.** `app: bottom tabs — map, summary, stats, settings; ride band; cumulative period argument`

### Task 9: 구간 요약의 목표 달성·신기록 줄

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/ui/record/SummaryExtras.kt`(상태, 계산, 그리기), 그것을 만드는 곳(기록·안내 ViewModel 팩토리)
- Test: `app/src/test/.../ui/record/SummaryExtrasTest.kt`, `app/src/androidTest/.../ui/record/RecordScreenTest.kt`(요약 화면 단언 추가)

**Interfaces:**
- Consumes: 작업 2 `RideRecords.newRecords`, `Goals.crossed`, `Goals.weekBeforeAfter`, `Goals.yearBeforeAfter`. 작업 3 `RideStatsSource`, `SettingsRepository.settings`.
- Produces:
  - `SummaryExtrasState.achievements: List<String> = emptyList()`
  - `SummaryExtras` 생성자에 `rideStats: Flow<List<RideStat>> = emptyFlow()`, `goals: Flow<Pair<Int?, Int?>> = flowOf(null to null)`를 더한다.
- 줄 규칙(§3.1, §3.4):
  - **순서:** 주간 달성, 연간 달성, 신기록(항목 순서 `RideRecordKind` 순)
  - **문구:**
    - "이번 주 목표 100 km 달성"
    - "올해 목표 1,000 km 달성"
    - "🏆 가장 긴 주행 신기록", "🏆 가장 오래 탄 주행 신기록", "🏆 가장 많이 오른 주행 신기록", "🏆 가장 빠른 평균 속도 신기록"
  - **보이지 않을 때:**
    - `askShort` 또는 `discarding`일 때
    - 구간이 10 m 미만일 때
    - 요약 구간이 `rideStats`에 아직 없을 때(닫히기 전)
  - **위치:** `SummaryExtrasContent`에서 "사진 N장" 아래에 `titleSmall` 줄로 그린다.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `SummaryExtrasTest`:
    - `weeklyGoalCrossedShowsLine`
    - `alreadyAboveGoalNoLine`
    - `newLongestRideLine`
    - `firstRideNoRecordLine`
    - `shortRideNoLines`
    - `linesOrder`
  - 화면 시험: 줄 두 개가 사진 줄 아래에 보인다.
- [ ] **Step 2: 실패 확인.**
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.** JVM 시험, S22 해당 클래스.
- [ ] **Step 5: 커밋.** `app: ride summary shows goal reached and new personal records`

### Task 10: 버전, 문서, S22 전체 시험과 설치

**Files:**
- Modify:
  - `app/build.gradle.kts`(`verMinor = 4`, `verPatch = 0`)
  - `app/src/androidTest/.../ui/settings/SettingsScreenTest.kt`("V1.4.0")
  - `CHANGELOG.md`(`## [1.4.0] - 개발 중`, 추가/변경/문서)
  - `README.md`(현재 버전 V1.4.0 개발 중, 로드맵 V1.4.0 행, 탭 설명)
- Create: `docs/verification/summary-stats-field-test.md`(자동 검증 결과, 실사용 점검표)

**Interfaces:**
- Consumes: 작업 1~9 전부.

- [ ] **Step 1: 버전과 시험 문자열을 바꾼다.** `./gradlew :app:assembleDebug`가 성공하고, 생성된 versionName이 `1.4.0-dev`, versionCode가 10400이다.
- [ ] **Step 2: JVM 전체.** `./gradlew :core:test :app:testDebugUnitTest` 모두 PASS. 개수를 문서에 적는다.
- [ ] **Step 3: S22 전체 시험.** androidTest 클래스를 하나씩 모두 돌린다.
  - 화면을 깨우고, 결과 XML 경로의 빈칸은 따옴표로 감싼다.
  - 실패가 있으면 혼자 다시 돌려 흔들리는 시험인지 가린다. `SearchScreenTest` 키보드 시험은 알려진 흔들림이다.
  - 결과(클래스 수, 시험 수)를 문서에 적는다.
- [ ] **Step 4: 스크린샷을 모은다.** `Download/bikeRecoderTest/`의 `v140-*.png`를 받아 사용자에게 보낸다. 컨트롤러가 SendUserFile로 보낸다.
- [ ] **Step 5: 문서.**
  - CHANGELOG `[1.4.0]`에 스펙 §1~§3 기능을 사용자 말투로 적고, 문서 줄에 스펙·계획·검증 경로를 적는다.
  - README를 갱신한다.
  - 검증 문서에는 자동 결과와 실사용 점검표를 넣는다. 점검표 항목:
    - 탭 이동·뒤로
    - 기록 중 띠
    - 위성 카드 그림과 데이터
    - 비행기 모드 선 그림
    - 제목 바꾸기와 영상 제목
    - 목표 달성 줄
    - 신기록 줄
    - 통계 비교 숫자 손계산 한 번
    - 누적 지도 기간 맞춤
- [ ] **Step 6: S22에 설치한다.** `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:installDebug </dev/null`. 앱을 띄워 탭 네 개가 보이는지 확인한다.
- [ ] **Step 7: 커밋.** `docs: V1.4.0 version, changelog, README and verification`
