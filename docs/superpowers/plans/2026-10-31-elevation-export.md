# 고도와 내보내기 (V1.7.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지난 여행과 계획한 경로의 고도를 경사 7단계 색 그래프·지도로 보여 주고(큰 지도 화면에서 훑기), 길안내 중 앞으로 나올 오르막을 띠·선 색·음성으로 알리며, 여행 합계에 하강·최고 고도를 넣고, 평소 속도와 오르막 보정으로 시간을 예상하고, 여행·경로를 GPX·KML로 `Download/bikeRecoder/`에 내보낸다.

**Architecture:**
- **core `com.cowork.bikerecoder.core.elevation` (JVM 시험):** 고도 단면 `ElevationProfile`, 경사 `GradeBand`, 오르막 `ClimbFinder`, 평소 속도 `UsualSpeed`, 오르막 보정 시간 `RideTimeEstimator`, 앞길 상태 `AheadOfRider`, 오르막 음성 판단 `ClimbAnnouncer`.
- **core `com.cowork.bikerecoder.core.export`:** `GpxWriter`, `KmlWriter`(문자열만).
- **core 수정:** `Route.elevationsM`, `NavigationSession`(평소 속도, 오르막 보정 도착 예정, 오르막 음성, `NavState`에 단면·앞길 상태), `KoreanPhrases.climb`, `RideStore.recentSegments`.
- **app:** 공용 `ElevationChart`·`ElevationStrip`, 지도 `MapOverlay` 경사 색 경로·훑기 점, 큰 지도 화면 `Routes.ELEVATION`, 여행·구간·경로 미리보기·길안내 화면 수정, 내보내기(`export/`).
- **DB 변경 없음(8판).**

**Tech Stack:** Kotlin, Compose Material 3 Canvas, MapLibre(기존 `BikeMap`/`MapOverlay` 색 조각 선), MediaStore `Downloads`, BRouter GeoJSON, JUnit5(core·app JVM), Compose UI test(androidTest).

**Spec:** `docs/superpowers/specs/2026-10-31-elevation-export-design.md`. 아래 "스펙 §"는 이 문서를 가리킨다.

## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구를 그대로 쓴다. 앱 화면 문구는 `app/.../ui/elevation/ElevationText.kt`(고도·띠·경로 미리보기)와 `app/.../export/ExportText.kt`(내보내기) 두 곳, 음성 문구는 core `KoreanPhrases`, GPX·KML 안의 글은 core 작성기 안 상수.
- **경사 7단계:** 스펙 §1.3 표의 경계·색·범례 글 그대로(`GradeBand`). 경계: `< -6` STEEP_DOWN, `-6 ≤ g ≤ 2` FLAT, `2 < g ≤ 4` UP_2, `4 < g ≤ 6` UP_4, `6 < g ≤ 8` UP_6, `8 < g ≤ 10` UP_8, `> 10` UP_10.
- **상수:** 다시 고르기 10 m(전체 50 km 이하)/20 m, 다듬기 50 m, 경사 폭 100 m, 단면 최소 50 m·고도 점 2개, 오르막 묶음 3%·끼는 평지 50 m 미만·최소 200 m·평균 4.0%, 평소 속도 최근 10구간·1 km 이상·기본 15 km/h, 보정 0.12/0.03·하한 6 km/h·상한 1.3배, 앞길 2,000 m, 음성 300 m(250~350 m), 심박 ±5초.
- **시각:** 파일 이름·KML 날짜는 Asia/Seoul, GPX `<time>`은 UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`.
- **바꾸지 않는 것:** DB 스키마(8판), 기록 엔진 `RecordingSession`·`AscentCounter`, 회전 안내·1 km 안내 규칙, 영상·공유·가져오기.
- **권한:** 새 권한 없음(MediaStore Downloads, minSdk 30).
- **기기 시험:** S22 `R5CT43TFS9A`에서만, 클래스 하나씩, 화면을 먼저 깨우고 `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`. S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다. 시험이 만든 다운로드 파일은 시험이 지운다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(core는 한국어 KDoc, app `ui/`는 영어 KDoc). core 시험은 `org.junit.jupiter` + `kotlin.test`.

## Review Focus

1. **GPS 고도가 튀거나 빠진 기록(터널, 고도 null, 실내 시작)** → 그래프가 톱니처럼 튀지 않고, null 점은 건너뛰며, 점이 부족하면 "고도 정보가 없습니다". → Task 1 `nullAltitudesAreSkipped`, `spikeIsSmoothed`, `tooFewAltitudesIsNull`
2. **여러 날 여행(구간 사이 수십 km 떨어짐)** → 거리가 구간 사이 빈 거리 없이 이어지고 경계 눈금이 생긴다; 내보낸 GPX는 구간마다 `trkseg`. → Task 1 `segmentsJoinWithoutGap`, Task 3 `multiDayTripHasOneTrksegPerSegment`
3. **경로 다시 탐색 직후·출발 직후 오르막이 이미 가까움** → 음성을 말하지 않고, 같은 오르막을 다시 말하지 않는다. → Task 2 `alreadyCloseIsNotAnnounced`, `sameClimbAfterRerouteIsNotRepeated`
4. **고도가 없는 경로(BRouter가 고도를 주지 않음)** → 길안내 판·선·도착 예정이 지금과 같고 앱이 죽지 않는다. → Task 1 `missingElevationsGiveEmptyList`, Task 2 `noProfileKeepsPlainEta`, Task 6 `stripHiddenWithoutElevation`
5. **긴 여행(점 3만 개) 내보내기·그래프** → 화면이 멈추지 않는다(계산·쓰기는 메인 스레드 밖), 파일이 끝까지 써진다. → Task 1 `longTripUsesTwentyMetreStep`, Task 7 `exportRunsOffMainAndWritesWholeFile`

---

### Task 1: core — 경로 고도와 고도 단면

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/model/Route.kt` (`Route`에 마지막 매개변수 `val elevationsM: List<Double?> = emptyList()`), `routing-brouter/src/main/kotlin/com/cowork/bikerecoder/routing/BRouterGeoJsonParser.kt`
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/elevation/GradeBand.kt`, `ElevationProfile.kt`
- Test: `routing-brouter/src/test/.../BRouterGeoJsonParserTest.kt`(기존 파일에 추가), `core/src/test/kotlin/com/cowork/bikerecoder/core/elevation/GradeBandTest.kt`, `ElevationProfileTest.kt`

**Interfaces:**
- Produces:
  - `enum class GradeBand(val argb: Long, val legend: String) { STEEP_DOWN(0xFF4FC3F7, "-6% 미만"), FLAT(0xFF1E63D6, "평지·완만"), UP_2(0xFFF9C80E, "2%"), UP_4(0xFFF57C00, "4%"), UP_6(0xFFE53935, "6%"), UP_8(0xFFA31515, "8%"), UP_10(0xFF7B1FA2, "10%"); companion object { fun of(gradePct: Double): GradeBand } }`
  - `data class BandRun(val startM: Double, val endM: Double, val band: GradeBand)`
  - `class ElevationProfile` — 읽기 속성 `stepM: Double`, `distancesM: DoubleArray`(0부터 stepM 간격, 마지막은 전체 거리), `elevationsM: DoubleArray`(다듬은 값), `gradesPct: DoubleArray`(100 m 폭), `boundariesM: List<Double>`, `totalM`, `minElevationM`, `maxElevationM`, `bands: List<BandRun>`; 함수 `elevationAt(distanceM: Double): Double`, `gradeAt(distanceM: Double): Double`, `bandAt(distanceM: Double): GradeBand`, `pointAt(distanceM: Double): GeoPoint`(원래 경로 선 위 거리 보간, 0 미만·전체 초과는 끝점), `slice(fromM: Double, toM: Double): ElevationProfile?`(그 구간만, 거리는 0부터 다시, 길이 50 m 미만이면 null).
  - `companion object { fun fromSegments(segments: List<List<TrackPoint>>): ElevationProfile?; fun fromRoute(route: Route): ElevationProfile?; const val SHORT_STEP_M = 10.0; const val LONG_STEP_M = 20.0; const val LONG_TRIP_M = 50_000.0; const val SMOOTH_WINDOW_M = 50.0; const val GRADE_WINDOW_M = 100.0; const val MIN_LENGTH_M = 50.0 }` — 스펙 §1.2. `fromSegments`의 바깥 목록은 시작 시각 순 구간, 안쪽은 시간 순 점(`core.recording.TrackPoint`). `fromRoute`는 `elevationsM`이 비면 null, `boundariesM`은 `stopPointIndices`의 누적 거리(마지막 목적지 제외).
- Parser: 좌표 배열의 세 번째 값을 읽어 `elevationsM`을 채운다. 모든 점이 null이면 빈 목록.

- [ ] **Step 1: 실패하는 시험**
  - `BRouterGeoJsonParserTest`: `readsElevations`(`[lon,lat,12.5]` → 12.5), `missingElevationsGiveEmptyList`(2값 좌표 → `emptyList()`), `partialElevationsKeepNulls`
  - `GradeBandTest`: `boundaries`(-6.01→STEEP_DOWN, -6.0→FLAT, 2.0→FLAT, 2.01→UP_2, 4.0→UP_2, 6.0→UP_4, 8.0→UP_6, 10.0→UP_8, 10.01→UP_10), `legendsAndColorsMatchSpec`
  - `ElevationProfileTest`: `flatLineHasZeroGrade`, `steadyClimbGradeIsFivePercent`(1 km에 50 m → gradesPct 가운데 ≈ 5.0 ± 0.1), `nullAltitudesAreSkipped`, `spikeIsSmoothed`(한 점만 +30 m → 다듬은 최대 차 < 10 m), `tooFewAltitudesIsNull`(고도 점 1개), `shorterThanFiftyMetresIsNull`, `segmentsJoinWithoutGap`(두 구간 각 1 km, 30 km 떨어짐 → totalM ≈ 2,000, boundariesM == [≈1,000]), `longTripUsesTwentyMetreStep`(51 km → stepM 20), `fiftyKmUsesTenMetreStep`, `bandsMergeRuns`, `pointAtInterpolates`, `sliceRestartsAtZero`, `fromRouteUsesStopBoundaries`, `fromRouteWithoutElevationsIsNull`
- [ ] **Step 2:** `./gradlew :core:test --tests '*elevation*' :routing-brouter:test` → 실패 확인
- [ ] **Step 3: 구현** — 다시 고르기는 고도 있는 점끼리 거리 선형 보간, 다듬기는 앞뒤 25 m 이동 평균(양 끝은 있는 만큼), 경사는 앞뒤 50 m의 고도 차 ÷ 실제 폭 × 100.
- [ ] **Step 4:** `./gradlew :core:test :routing-brouter:test` → 모두 통과(기존 `Route(...)` 호출은 기본값으로 그대로 컴파일)
- [ ] **Step 5: 커밋** `core: elevation — route elevations, profile and grade bands`

### Task 2: core — 오르막, 평소 속도, 시간, 길안내 연결

**Files:**
- Create: `core/.../elevation/ClimbFinder.kt`, `UsualSpeed.kt`, `RideTimeEstimator.kt`, `core/.../navigation/AheadOfRider.kt`, `core/.../navigation/ClimbAnnouncer.kt`
- Modify: `core/.../voice/KoreanPhrases.kt`(`climb`), `core/.../navigation/NavigationSession.kt`(생성자 `usualSpeedMps`, `NavState` 필드 둘, 도착 예정, 오르막 음성), `core/.../navigation/EtaEstimator.kt`(그대로 — 생성자 `defaultSpeedMps`로 평소 속도를 넣는다), `core/.../recording/RideStore.kt`(`recentSegments`), 이를 구현하는 앱 `RoomRideStore`와 시험용 가짜 저장소들(컴파일만 맞춤)
- Test: `core/src/test/.../elevation/ClimbFinderTest.kt`, `UsualSpeedTest.kt`, `RideTimeEstimatorTest.kt`, `core/src/test/.../navigation/AheadOfRiderTest.kt`, `ClimbAnnouncerTest.kt`, `NavigationSessionTest.kt`(추가), `KoreanPhrasesTest.kt`(추가), app `RoomRideStoreTest`(androidTest, 추가)

**Interfaces:**
- Consumes: Task 1 `ElevationProfile`, `GradeBand`
- Produces:
  - `data class Climb(val startM: Double, val endM: Double, val gainM: Double, val avgGradePct: Double, val maxGradePct: Double) { val lengthM: Double get() = endM - startM }`
  - `object ClimbFinder { fun find(profile: ElevationProfile): List<Climb> }` — 스펙 §1.4, 상수 `START_GRADE_PCT = 3.0`, `MAX_DIP_M = 50.0`(끼는 3% 미만 구간이 50 m **미만**이면 묶음), `MIN_LENGTH_M = 200.0`, `MIN_AVG_GRADE_PCT = 4.0`.
  - `object UsualSpeed { const val DEFAULT_MPS = 15.0 / 3.6; const val RECENT = 10; const val MIN_DISTANCE_M = 1_000.0; fun of(segments: List<RideSegment>): Double }` — 끝난(`endedAt != null`) 구간 중 거리 1 km 이상·이동 시간 > 0을 시작 시각 내림차순 10개, 거리 합 ÷ 이동 시간 합.
  - `RideStore`: `suspend fun recentSegments(limit: Int): List<RideSegment>` — 끝난 구간, 거리 1 km 이상, 시작 시각 내림차순 `limit`개(가져온 구간 포함).
  - `object RideTimeEstimator { const val UP_K = 0.12; const val DOWN_K = 0.03; const val MIN_UP_MPS = 6.0 / 3.6; const val MAX_DOWN_FACTOR = 1.3; fun seconds(profile: ElevationProfile?, fromM: Double, toM: Double, baseMps: Double): Double }` — 스펙 §1.7. `profile == null`이면 `(toM - fromM) / baseMps`. 오르막 하한: `max(base / (1 + UP_K × g), min(base, MIN_UP_MPS))`.
  - `sealed interface AheadState { data class InClimb(val remainingM: Double, val avgGradePct: Double); data class ClimbAhead(val inM: Double, val lengthM: Double, val avgGradePct: Double); data object Flat }` 와 `object AheadOfRider { const val WINDOW_M = 2_000.0; fun state(climbs: List<Climb>, distanceAlongM: Double): AheadState; fun strip(profile: ElevationProfile, distanceAlongM: Double): ElevationProfile? }` — strip = `profile.slice(d, min(d + WINDOW_M, profile.totalM))`.
  - `class ClimbAnnouncer { fun reset(); fun onProgress(climbs: List<Climb>, profile: ElevationProfile, distanceAlongM: Double): Climb? }` — 스펙 §3.3: 각 오르막의 남은 거리 `gap = startM - d`. 그 오르막을 이 경로에서 처음 볼 때 `gap < 300`이면 그 오르막은 말하지 않음으로 표시. 처음 볼 때 `gap ≥ 300`이었고 지금 `250 ≤ gap ≤ 350`이며 아직 말하지 않았으면 그 오르막을 돌려준다. 말한 오르막은 시작 위치 `profile.pointAt(startM)`로 기억하고, 새 경로(다른 profile)에서 시작 위치가 50 m 안인 오르막은 말한 것으로 본다. `reset()`은 길안내 끝.
  - `KoreanPhrases.climb(distanceM: Double, lengthM: Double, avgGradePct: Double): String` = `"${distance(distanceM)} 앞 오르막, ${distance(lengthM)}, 평균 ${avg 반올림 정수}퍼센트입니다"`.
  - `NavigationSession(..., usualSpeedMps: Double = UsualSpeed.DEFAULT_MPS)`: `EtaEstimator(defaultSpeedMps = usualSpeedMps)`; 경로가 정해지거나 바뀔 때마다 `profile = ElevationProfile.fromRoute(route)`, `climbs = profile?.let(ClimbFinder::find).orEmpty()`; `etaMillis = now + RideTimeEstimator.seconds(profile, d, routeTotal, eta.baseSpeedMps(now)) × 1000`; 오르막 음성은 `scheduler.onUpdate` 결과에 `Priority.TURN`이 있으면 다음 업데이트로 미루고, 아니면 `Utterance(phrases.climb(...), Priority.PERIODIC)`를 덧붙인다(음소거는 기존 방식대로 바깥에서).
  - `NavState`에 `val profile: ElevationProfile? = null`, `val ahead: AheadState? = null`(profile이 없으면 null) 추가.

- [ ] **Step 1: 실패하는 시험**
  - `ClimbFinderTest`: `steadyClimbFound`, `dipShorterThanFiftyJoins`(49 m), `dipOfFiftySplits`(50 m), `length199IsNotClimb`, `length200IsClimb`, `avg3point9IsNotClimb`, `avg4IsClimb`, `maxGradeReported`, `descentsIgnored`
  - `UsualSpeedTest`: `noSegmentsIsDefault`(→ 15/3.6), `shortSegmentsExcluded`, `onlyTenMostRecent`(11개 중 가장 오래된 것 빠짐), `weightedByTime`, `openSegmentExcluded`
  - `RideTimeEstimatorTest`: `flatIsDistanceOverSpeed`, `fivePercentAtEighteen`(base 18 km/h → 5% 1 km ≈ 320 s), `floorSixKmh`(20%), `slowBaseBelowFloorKeepsBase`(base 5 km/h), `downhillCappedAtOnePointThree`(-15%), `nullProfileIsPlain`
  - `AheadOfRiderTest`: `inClimbRemaining`, `climbWithinTwoKm`, `climbBeyondTwoKmIsFlat`, `stripStopsAtDestination`, `stripIsTwoKm`
  - `ClimbAnnouncerTest`: `announcedOnceAtThreeHundred`, `alreadyCloseIsNotAnnounced`(처음 gap 280), `sameClimbAfterRerouteIsNotRepeated`, `differentClimbAfterRerouteIsAnnounced`, `resetForgets`
  - `NavigationSessionTest` 추가: `etaUsesUsualSpeedFirstFiveMinutes`, `etaAddsClimbTime`, `noProfileKeepsPlainEta`, `climbVoiceAfterTurnWaits`, `navStateCarriesProfileAndAhead`
  - `KoreanPhrasesTest`: `climbPhrase`(300, 800, 5.6 → `"300미터 앞 오르막, 800미터, 평균 6퍼센트입니다"`)
  - `RoomRideStoreTest`(S22): `recentSegmentsNewestFirstOverOneKm`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** `./gradlew :core:test :app:testDebugUnitTest` → 통과, S22에서 `RoomRideStoreTest`
- [ ] **Step 5: 커밋** `core: elevation — climbs, usual speed, climb-aware ETA and climb voice`

### Task 3: core — GPX·KML 작성기

**Files:**
- Create: `core/.../export/ExportModels.kt`, `GpxWriter.kt`, `KmlWriter.kt`
- Test: `core/src/test/.../export/GpxWriterTest.kt`, `KmlWriterTest.kt`, `ExportRoundTripTest.kt`

**Interfaces:**
- Consumes: `core.recording.TrackPoint`, `core.post.HeartRateSample`, `Route`, `Stop`, V1.6.0 `GpxReader`·`RideReplay`(시험만)
- Produces:
  - `data class ExportSegment(val startedAt: Long, val localDate: LocalDate, val points: List<TrackPoint>, val heartRate: List<HeartRateSample>)`
  - `object GpxWriter { const val HR_WINDOW_MS = 5_000L; fun trip(title: String, segments: List<ExportSegment>, creator: String, exportedAt: Long): String; fun route(name: String, route: Route, stops: List<Stop>, creator: String, exportedAt: Long): String }` — 스펙 §4.3 그대로(이름공간 두 개, `metadata`, `trk/type cycling`, 구간마다 `trkseg`, `ele` 소수 1자리, 좌표 소수 7자리, UTC 시간, 심박 ±5초 안 가장 가까운 값(같은 거리면 앞 것), 경로는 `wpt` + 시간 없는 `trk`).
  - `object KmlWriter { fun trip(title: String, segments: List<ExportSegment>): String; fun route(name: String, route: Route, stops: List<Stop>): String }` — 스펙 §4.4(`출발`, `도착`, `{yyyy-MM-dd} 주행`, 스타일 파랑 폭 4, `clampToGround`, 좌표 `경도,위도,고도`).
  - 문자열 이스케이프 `& < > " '`.

- [ ] **Step 1: 실패하는 시험**
  - `GpxWriterTest`: `headerAndNamespaces`, `multiDayTripHasOneTrksegPerSegment`, `pointHasEleAndUtcTime`, `missingAltitudeOmitsEle`, `heartRateWithinFiveSeconds`(4.9 s 들어감, 5.1 s 안 들어감), `escapesTitle`(`A&B <x>`), `routeHasWaypointsAndNoTime`
  - `KmlWriterTest`: `tripLinePerSegmentWithStartEnd`, `routeLineAndStopPoints`, `coordinatesAreLonLatEle`
  - `ExportRoundTripTest`: `exportedTripReadsBackWithSameNumbers` — 1 Hz 합성 주행 20분을 `GpxWriter.trip` → `GpxReader.read` → `RideReplay.replay`: 시작·끝 시각 같음, 거리·이동 시간이 원래 `RideReplay` 결과와 1% 안
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현** — `StringBuilder`로 직접 쓴다(새 의존성 없음).
- [ ] **Step 4:** `./gradlew :core:test` → 통과
- [ ] **Step 5: 커밋** `core: export — GPX and KML writers`

### Task 4: app — 고도 그래프 부품, 지도 경사 색, 큰 지도 화면

**Files:**
- Create: `app/.../ui/elevation/ElevationChart.kt`(`ElevationChart`, `ElevationStrip`, `ElevationTags`), `ElevationText.kt`, `ElevationMapScreen.kt`, `ElevationMapViewModel.kt`, `ElevationHandoff.kt`
- Modify: `app/.../map/BikeMap.kt`(`MapOverlay`에 `routeColors: List<String>? = null`, `marker: GeoPoint? = null`; 경로 선을 기존 `colorRuns` 방식 색 조각으로, 마커는 흰 점·진한 회색 테두리 레이어), `ui/BikeNavHost.kt`(`Routes.ELEVATION`), `AppContainer.kt`(`elevationHandoff`)
- Test: `app/src/androidTest/.../ui/elevation/ElevationChartTest.kt`, `ElevationMapScreenTest.kt`; `app/src/test/.../ui/elevation/ElevationMapViewModelTest.kt`; `app/src/test/.../map/RouteColorsTest.kt`

**Interfaces:**
- Consumes: Task 1 `ElevationProfile`, `GradeBand`, `BandRun`; Task 2 `ClimbFinder`(낭독 문구의 오르막 수)
- Produces:
  - `@Composable fun ElevationChart(profile: ElevationProfile?, modifier: Modifier = Modifier, height: Dp = 140.dp, scrubbable: Boolean = false, onOpen: (() -> Unit)? = null, onScrub: (distanceM: Double) -> Unit = {})` — 스펙 §2.1. `profile == null`이면 `고도 정보가 없습니다`. 작은 그래프(`scrubbable = false`)는 `onOpen` 있으면 누를 수 있음(Role.Button) + 오른쪽 아래 [지도에 표시]. 큰 그래프는 누르기·끌기로 세로선·점, `onScrub`.
  - `@Composable fun ElevationStrip(profile: ElevationProfile?, reachesDestination: Boolean, dimmed: Boolean, modifier: Modifier = Modifier)` — 56dp, 축 글 없음, 왼쪽 끝 점, 목적지면 오른쪽 끝 깃발, `dimmed`면 alpha 0.4. `FLAT`은 `flatColor` 매개변수(기본 `GradeBand.FLAT`)로.
  - `fun routeColors(route: Route, profile: ElevationProfile?, flatHex: String?): List<String>?` (`map/` 안) — 경로 점마다 `profile.bandAt(cumulativeM[i])`의 `#RRGGBB`, `FLAT`은 `flatHex`가 있으면 그것; profile null이면 null.
  - `ElevationText`: 스펙 §5의 고도 문구(`NO_ELEVATION`, `SHOW_ON_MAP`, `TITLE = "고도"`, `fun scrubLine(distanceM, elevationM, gradePct)`, `fun chartDescription(min, max, climbs)`, `fun usualSpeedLine(mps)`, `fun aheadLine(state: AheadState)`) — 거리 `SummaryFormatter.distance`, 고도 `RideFormat.altitude`, 경사·km/h 반올림 정수.
  - `sealed interface ElevationSource { data class Trip(val tripId: Long); data class Segment(val segmentId: Long); data object Plan }`, `class ElevationHandoff { fun put(profile: ElevationProfile, path: List<GeoPoint>); fun take(): Pair<ElevationProfile, List<GeoPoint>>? }`(경로 미리보기 → 큰 지도, 메모리만).
  - `Routes.ELEVATION` = `"elevation/{kind}/{id}"`(kind `trip`|`segment`|`plan`, plan은 id 0). `fun NavController.openElevation(source: ElevationSource)`.
  - `ElevationMapViewModel`: `uiState: StateFlow<ElevationMapUiState>` = `Loading` / `Ready(profile, paths: List<List<GeoPoint>>, colors: List<List<String>>, scrubM: Double?)` / `Closed`(plan인데 handoff 없음 → 화면이 뒤로). `fun scrub(distanceM: Double)`. 단면 계산은 기본 디스패처 밖(`Dispatchers.Default` 주입).
  - `ElevationMapScreen(viewModel, onClose)` — 스펙 §2.2(지도 60%, 경로 전체 맞춤, 시작 초록·끝 검은 점, 훑기 점, 판: 제목·[×]·큰 그래프·범례 7칸 또는 훑기 줄). 큰 지도에서는 `FLAT`도 표의 파랑.
- [ ] **Step 1: 실패하는 시험**
  - `RouteColorsTest`: `flatUsesOverride`, `climbPointsColored`, `nullProfileGivesNull`
  - `ElevationMapViewModelTest`: `tripLoadsProfile`, `segmentLoadsProfile`, `planTakesHandoffOnce`, `planWithoutHandoffCloses`, `scrubStoresDistance`
  - `ElevationChartTest`(S22): `noProfileShowsText`, `smallChartOpens`, `scrubReportsDistanceAndShowsLine`, `descriptionReadsMinMaxClimbs`, `stripDimmed`
  - `ElevationMapScreenTest`(S22): `legendHasSevenBands`, `scrubShowsLine`, `closeGoesBack`, `screenshot("v170-elevation-map")`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** JVM 전체, S22에서 `ElevationChartTest`, `ElevationMapScreenTest`를 하나씩 → 통과
- [ ] **Step 5: 커밋** `app: elevation — chart, gradient route line and elevation map screen`

### Task 5: app — 여행 화면과 구간 상세

**Files:**
- Modify: `app/.../ui/history/TripDetailScreen.kt`·`TripDetailViewModel.kt`(합계 셋째 줄, 작은 그래프, 단면 계산), `SegmentDetailScreen.kt`·VM(고도 선 그래프 → `ElevationChart`)
- Test: `app/src/test/.../ui/history/TripDetailViewModelTest.kt`(추가), `app/src/androidTest/.../ui/history/TripDetailScreenTest.kt`(추가), `SegmentDetailScreenTest`(추가)

**Interfaces:**
- Consumes: Task 1 `ElevationProfile.fromSegments`; Task 4 `ElevationChart`, `openElevation`
- Produces: `TripDetailViewModel`의 상태에 `profile: ElevationProfile?`, `descentM: Double`, `maxAltitudeM: Double?`(스펙 §1.5, 모두 null이면 표시 `--`). 합계 카드 둘째 줄 `평균/최고`, 셋째 줄 `누적 상승` · `누적 하강` · `최고 고도`.
- [ ] **Step 1: 실패하는 시험**
  - `TripDetailViewModelTest`: `descentIsSumMaxAltitudeIsMax`, `allMaxAltitudesNullIsNull`, `profileFromSegmentsInStartOrder`, `importedTripHasProfile`
  - `TripDetailScreenTest`: `totalsThirdRowAscentDescentMax`, `chartAndShowOnMapOpenElevation`, `noAltitudeShowsText`, `screenshot("v170-elevation-trip")`
  - `SegmentDetailScreenTest`: `elevationChartReplacesLineChart`(속도·심박 그래프는 그대로)
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현** — 단면 계산은 VM에서 `Dispatchers.Default`.
- [ ] **Step 4:** JVM 전체, S22에서 `TripDetailScreenTest`, `SegmentDetailScreenTest` → 통과
- [ ] **Step 5: 커밋** `app: elevation — trip totals, trip and segment charts`

### Task 6: app — 경로 미리보기와 길안내

**Files:**
- Modify: `app/.../ui/plan/PlanScreen.kt`·`PlanViewModel.kt`(시간·`평소 속도` 줄·그래프 100dp·핸드오프), `app/.../nav/NavigationController.kt`(세션 시작 때 `UsualSpeed.of(rideStore.recentSegments(UsualSpeed.RECENT))`를 `usualSpeedMps`로), `app/.../ui/navigate/NavigateScreen.kt`·`NavigateViewModel.kt`(띠·안내 줄·경로 선 색)
- Test: `app/src/test/.../ui/plan/PlanViewModelTest.kt`(추가), `app/src/test/.../nav/NavigationControllerTest.kt`(추가), `app/src/androidTest/.../ui/plan/PlanScreenTest.kt`(추가), `app/src/androidTest/.../ui/navigate/NavigateScreenTest.kt`(추가)

**Interfaces:**
- Consumes: Task 2 `UsualSpeed`, `RideTimeEstimator`, `NavState.profile`·`ahead`, `AheadOfRider.strip`; Task 4 `ElevationChart`, `ElevationStrip`, `routeColors`, `ElevationHandoff`, `ElevationText`
- Produces:
  - `PlanViewModel` 상태에 `usualSpeedMps: Double`, `profile: ElevationProfile?`, `durationSeconds: Double?`(Ready일 때 `RideTimeEstimator.seconds(profile, 0, total, usual)`). 요약 첫 줄 시간은 이 값으로(분 반올림, 기존 시간 표기와 같은 꼴).
  - 길안내 판: `NavState.ahead`가 있으면 숫자 줄 위에 `ElevationText.aheadLine` + `ElevationStrip(AheadOfRider.strip(profile, d), reachesDestination = d + 2_000 ≥ total, dimmed = rerouting || offRoute)`. 없으면 지금 판 그대로.
  - 길안내 지도: `MapOverlay.routeColors = routeColors(route, profile, flatHex = 지금 길안내 선 색)`.
- [ ] **Step 1: 실패하는 시험**
  - `PlanViewModelTest`: `durationUsesUsualSpeedAndClimbs`, `noElevationUsesUsualSpeedOnly`, `usualSpeedLine`(18.4 → `평소 속도 18 km/h 기준`), `openElevationPutsHandoff`
  - `NavigationControllerTest`: `sessionGetsUsualSpeed`
  - `PlanScreenTest`(S22): `summaryShowsUsualSpeedAndChart`, `noElevationText`, `screenshot("v170-plan")`
  - `NavigateScreenTest`(S22): `aheadLineClimbAhead`, `aheadLineInClimb`, `aheadLineFlat`, `stripHiddenWithoutElevation`, `screenshot("v170-navigate")`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** JVM 전체, S22에서 `PlanScreenTest`, `NavigateScreenTest` → 통과
- [ ] **Step 5: 커밋** `app: elevation — plan preview time and chart, guidance strip and gradient line`

### Task 7: app — GPX·KML 내보내기

**Files:**
- Create: `app/.../export/ExportText.kt`, `DownloadsWriter.kt`, `TripExporter.kt`, `ExportDialog.kt`(`ExportTags` 포함)
- Modify: `TripDetailScreen.kt`·VM(제목 줄 [⋮] 메뉴), `PlanScreen.kt`·VM(요약 옆 [⋮]), `AppContainer.kt`
- Test: `app/src/test/.../export/TripExporterTest.kt`, `ExportFileNameTest.kt`; `app/src/androidTest/.../export/DownloadsWriterTest.kt`, `ExportDialogTest.kt`; `TripDetailScreenTest`·`PlanScreenTest`(추가)

**Interfaces:**
- Consumes: Task 3 `GpxWriter`, `KmlWriter`, `ExportSegment`; `RideStore.segments/points`, `PostStore.samples(segmentId)`
- Produces:
  - `enum class ExportFormat(val extension: String, val mime: String) { GPX("gpx", "application/gpx+xml"), KML("kml", "application/vnd.google-earth.kml+xml") }`
  - `fun exportFileName(prefix: String, epochMillis: Long, format: ExportFormat): String` — 여행 `bikeRecoder_yyyyMMdd_HHmm`, 경로 `bikeRecoder_route_yyyyMMdd_HHmm`(Asia/Seoul).
  - `class DownloadsWriter(resolver: ContentResolver) { fun write(displayName: String, mime: String, content: String): Uri }` — `MediaStore.Downloads`, `RELATIVE_PATH = "Download/bikeRecoder/"`, pending → 쓰기 → 공개, 실패하면 만든 항목을 지우고 예외. 실제 저장된 이름은 `Uri`에서 다시 읽어 창에 보인다.
  - `class TripExporter(rideStore, postStore, writer, io: CoroutineDispatcher, clock) { suspend fun exportTrip(tripId: Long, format: ExportFormat): ExportResult; suspend fun exportRoute(route: Route, stops: List<Stop>, destinationName: String, format: ExportFormat): ExportResult }`, `sealed interface ExportResult { data class Saved(val uri: Uri, val displayName: String); data object Failed; data object Recording }` — 열린 구간이 있으면 `Recording`.
  - `ExportDialog(saved: ExportResult.Saved, onShare, onOpen, onDismiss)` — 스펙 §4.2 문구, [공유] `ACTION_SEND`(URI, `FLAG_GRANT_READ_URI_PERMISSION`), [파일 열기] `ACTION_VIEW`(없으면 Toast).
  - 여행 화면 제목 줄 [⋮](`더 보기`) 메뉴 두 항목, 기록 중이면 흐림 + `기록을 마친 뒤 내보낼 수 있습니다`; 경로 미리보기 `Ready`일 때 [⋮].
- [ ] **Step 1: 실패하는 시험**
  - `ExportFileNameTest`: `tripNameUsesSeoulStart`, `routeNamePrefix`
  - `TripExporterTest`(가짜 저장소·가짜 writer): `tripGpxHasAllSegmentsAndHeartRate`, `openSegmentIsRecording`, `writerFailureIsFailed`, `exportRunsOffMainAndWritesWholeFile`(점 3만 개, io 디스패처에서 쓰고 끝 태그까지), `routeKml`
  - `DownloadsWriterTest`(S22): `writesUnderDownloadBikeRecoder`(실제로 쓰고 읽어 같은지, 끝나면 그 Uri 삭제), `sameNameGetsSuffix`(둘 다 삭제)
  - `ExportDialogTest`(S22): `showsPathAndButtons`, `screenshot("v170-export")`
  - `TripDetailScreenTest`: `overflowMenuExports`, `recordingTripMenuDisabled`; `PlanScreenTest`: `overflowOnlyWhenReady`
- [ ] **Step 2:** 실패 확인
- [ ] **Step 3: 구현**
- [ ] **Step 4:** JVM 전체, S22에서 `DownloadsWriterTest`, `ExportDialogTest`, `TripDetailScreenTest`, `PlanScreenTest`를 하나씩 → 통과
- [ ] **Step 5: 커밋** `app: export — GPX and KML to Download/bikeRecoder with share and open`

### Task 8: 버전·문서 (part A). 기기 전체 시험·설치는 최종 수정 뒤 (part B)

**Files:**
- Modify: `app/build.gradle.kts`(verMinor 7, verPatch 0 → 10700, `1.7.0-dev`), `SettingsScreenTest`(`V1.7.0`), `AppVersionTest`, `CHANGELOG.md`(`## [1.7.0] - 개발 중`), `README.md`(현재 버전 V1.7.0(개발 중), 로드맵 줄 V1.7.0 개발 중·V1.8.0 GPX 경로 따라가기 예정·V1.9.0 경로 주변 장소 예정, 문서 목록), `docs/VERSIONING.md`
- Create: `docs/verification/elevation-export-field-test.md`(JVM 결과, S22 자리 "최종 수정 뒤 기록", 스펙 §7.3 점검표 6항목)
- [ ] **Step 1:** 버전과 시험 두 개, `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test`
- [ ] **Step 2:** 문서
- [ ] **Step 3:** S22에서 `SettingsScreenTest`만
- [ ] **Step 4: 커밋** `docs: V1.7.0 version, changelog, README and verification`
- [ ] **(part B, 컨트롤러가 최종 검토와 수정 뒤에 함)** S22 androidTest 전체를 클래스 하나씩, `v170-*.png`를 받아 사용자에게, S22 `installDebug`와 시험용 기록 사본 다시 넣기, S22에서 실제 여행 그래프·경로 미리보기·내보내기 한 번, 검증 문서에 결과, 커밋
