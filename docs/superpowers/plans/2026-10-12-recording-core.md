# 기록 코어 (V1.1.0) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 내비게이션 V1.0.0 위에 GPS 주행 기록(거리·이동/경과 시간·현재/평균/최고 속도·고도·누적 상승/하강, 자동/수동 일시정지, 기록 음성 안내, 저장·복구, 기록 화면·목록·상세)을 더해 V1.1.0을 만든다.

**Architecture:** 판정·계산은 순수 Kotlin `:core`의 새 패키지 `core.recording`(TrackFilter·RideStatsCalculator·AutoPauseDetector·AltitudeFusion·RecordingSession·RideStore·RideCharts)에 두고 JVM에서 스펙 수치 그대로 테스트한다. `:app`에는 기록만 책임지는 새 `RideController`를 두고, 기존 `NavigationController`는 그대로 둔 채 두 가지만 연결한다 — ① 위치는 `SharedLocationSource`로 한 번만 받아 두 컨트롤러가 나눠 구독하고, ② 기록 중에는 `NavigationController.kmSource`로 안내 엔진의 1km 안내를 기록 쪽 거리·문장으로 바꾼다. 포그라운드 서비스는 기존 `NavigationService` 하나가 "안내 또는 기록 중"이면 유지된다.

**Tech Stack:** 기존과 동일 — Kotlin 2.4.20, AGP 9.4.1, Compose(BOM 2026.09.00, `HorizontalPager`·`Canvas`는 foundation), MapLibre 13.6.1, Room 2.8.5(+ `room-testing` 추가), DataStore 1.2.1, coroutines 1.11.0, JUnit 5(JVM) / AndroidX Test(기기). 새 외부 라이브러리 없음(차트 라이브러리 금지).

**Spec:** `docs/superpowers/specs/2026-10-12-recording-core-design.md` (실행자는 스펙과 이 계획을 함께 읽는다)

## Global Constraints

- 패키지: core 신규 `com.cowork.bikerecoder.core.recording`, app 신규 `com.cowork.bikerecoder.ride`(컨트롤러), `ui.record`, `ui.history`. 기존 패키지 구조 유지
- 버전: `versionName = "1.1.0"`, `versionCode = 10100`, debug `versionNameSuffix = "-dev"` → `1.1.0-dev`, 화면 표기 `V1.1.0` (Task 1에서 올린다)
- minSdk 30, targetSdk/compileSdk 37, JVM 17, MapLibre 13.6.1(공식 Compose 래퍼 없이 `AndroidView`), 의존성 주입 라이브러리 금지(`AppContainer` 수동 구성), 차트 라이브러리 금지(Compose `Canvas`)
- 모든 사용자 노출 문구는 한국어. 음성 문구는 스펙 §4.5 그대로: "기록을 시작합니다" / "일시정지되었습니다" / "기록을 다시 시작합니다" / "{n}킬로미터 이동. 평균 속도 {x.x}킬로미터, 이동 시간 {시간}입니다." / "{n}킬로미터 이동. 평균 속도 {x.x}킬로미터, 이동 시간 {시간}. 남은 거리 {거리}, 도착 예정 {시각}입니다." ({시간}: 1시간 미만 "{m}분", 이상 "{h}시간 {m}분")
- 수치(스펙 §4): 정확도 > 30m 제외 · 직전 채택 위치 대비 구간 속도 > 80km/h 제외 / 일시정지: 속도 < 3km/h 5초 연속 / 재개: 속도 ≥ 5km/h 2초 연속 또는 정지 지점에서 ≥ 15m / 현재 속도 약 3초 이동 평균 · 최고 = 3초 창 중간값들의 최댓값 / 기압 고도 `44330·(1−(p/1013.25)^(1/5.255))`(= `SensorManager.getAltitude(1013.25, p)`), 5초 평균, 수직 정확도 ≤ 10m GPS와의 차이 중간값으로 오프셋(처음 60초, 이후 5분마다 EMA 0.1) / 기압계 없음: GPS 고도 중간값 필터(5) + 평활 / 누적 상승·하강 기준점 대비 ≥ 3m(기압) / ≥ 5m(GPS) / GPS 없음 10초 / 위치 5초마다 일괄 저장 / km 기준 Asia/Seoul 날짜
- 스레드: `RecordingSession`과 `NavigationSession`은 같은 단일 스레드 스코프(`Dispatchers.Main.immediate`, AppContainer의 `rideScope`)에서만 호출한다. 위치·기압·tick 수집은 컨트롤러가 그 스코프 안에서 하며 UI 생명주기 수집기에 맡기지 않는다(V1.0.0 R12)
- GPS 감시는 tick 시각만 쓴다(V1.0.0 R11): 위치가 오면 "봤음"만 표시, `onTick(now)`이 마지막 활동 후 10초를 판정. 이동 시간·자동 일시정지는 `fix.timeMillis` 차이, 경과 시간·날짜·고도 보정 창은 컨트롤러 `clock()`(tick 시각)으로 계산한다
- MapLibre 호출은 메인 스레드에서만
- 테스트: JUnit5 모듈은 `testRuntimeOnly(junit-platform-launcher)`(이미 있음). core는 `./gradlew :core:test --tests '<FQCN>'`, app JVM은 `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`
- 기기 테스트: USB 휴대폰 S22 Ultra(serial R5CT43TFS9A)에서 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>` (`--tests` 없음). AVD를 만들지 않는다. 휴대폰이 빠져 있으면 그 Task는 JVM 테스트 + `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 끝내고 기기 실행을 "기기 검증 대기"로 남긴다(모든 Task는 JVM으로 확인 가능한 부분을 가진다). 기기 테스트는 in-memory DB를 쓰고 만든 파일·DB를 지운다
  > **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다.
  > connected 테스트는 `R5CT43TFS9A`에서만, 실제 기록이 있는 `R3CY903ZYQV`에는 `installDebug`만 씁니다. (판정 U3, 2026-10-18 — 이 계획을 처음 실행할 때는 S25 Ultra `R3CY903ZYQV`를 썼으나 지금은 connected 테스트를 돌리지 않는다)
- 테스트 좌표는 `GeoMath`(R = 6_371_008.8)와 같은 반경으로 만든다: 북쪽 직진 `lat = 37.5 + Math.toDegrees(m / 6_371_008.8)`(경선 위라 haversine 거리 = m, V1.0.0 R9). 기록 테스트는 `TestRoutes.pointAt`을 쓰지 않는다(Task 7은 안내 테스트라 기존 `TestRoutes.straightRoute`를 그대로 쓰며, 거리는 가짜 소스가 정하므로 영향이 없다). 경계값 시험은 부동소수 오차를 피해 경계 ±0.1m로 한다
- Room: DB 버전 2, `exportSchema = true`, `app/schemas/.../2.json`을 커밋한다. 1→2는 테이블 추가만(`AutoMigration(from = 1, to = 2)`)
- 설정 기본값: `autoStartRecordingWithNavigation = true`, `autoPauseEnabled = true`
- 컨트롤러 판정(사전 점검 후 확정): **P1** 스펙 밖 확장을 받아들인다 — 연속 5번 속도로 버려지면 재기준, 10초 넘는 공백은 3km/h 이상이면 이어 붙이고(터널) 아니면 빼기, 안내만 할 때 배너 "● 주행 중 · 안내 중". **P2** `closeInterrupted`는 구간을 마지막 저장 시각으로 닫고 여행도 끝낸다(당일 → 완료, 여러 날 → 오늘은 여기까지). **P3** 자정을 넘긴 구간은 시작한 날의 `localDate`에 남는다(다음 날 첫 기록의 그날 합계가 자정 이후 거리만큼 적게 잡히는 것을 받아들인다). **P4** 공유 위치 스트림은 마지막 위치 1개를 다시 준다(`replay = 1`) — 늦게 구독한 기록도 안내가 출발점으로 쓴 위치부터 받는다

## Review Focus

1. **움직이는 중 GPS 끊김(터널)과 서 있는 중 GPS 끊김** — 10초 넘게 위치가 없다가 돌아오면, 그 사이 평균 속도가 3km/h 이상이었으면(터널) 거리·시간을 이어 붙이고, 아니면(실내 정차) 거리도 이동 시간도 더하지 않아야 한다. → Task 2 `gap over 10 s while stationary adds nothing` / `moving gap over 10 s is bridged`.
2. **첫 위치가 엉뚱한 곳일 때 거르기가 영원히 막힘** — 기준점이 튄 위치면 이후 정상 위치가 모두 80km/h 초과로 버려진다. 연속 5번 버려지면 그 위치를 새 기준점으로 삼고(거리는 더하지 않음) 다시 기록돼야 한다. → Task 2 `five consecutive jumps re-anchor without adding distance` + Task 6 `reanchored fix adds no distance`.
3. **자동 일시정지 중에 칩으로 자동을 끔** — 수동 모드에는 자동 재개가 없으므로 그대로 두면 영원히 일시정지된다. 끄는 순간 재개(음성 포함)해야 한다. → Task 6 `turning auto pause off while auto paused resumes`.
4. **남아 있던 열린 구간 위에 새 기록 시작** — 강제 종료로 `endedAt == null` 구간이 남은 채 안내 자동 시작 등으로 새 기록이 시작되면 열린 구간이 둘이 된다. 새 기록은 남은 구간을 마지막 저장 시각으로 먼저 닫아야 한다. → Task 10 `starting closes a leftover open segment at its last save time`.
5. **저장이 계속 실패** — 저장 실패 시 위치를 버리면 기록이 사라진다. 경고는 한 번만 띄우고, 버퍼를 유지해 다음 주기에 다시 저장해야 한다. → Task 10 `save failure warns once and retries the buffered points`.

---

## 파일 구조

```
core/src/main/kotlin/com/cowork/bikerecoder/core/
  model/Geo.kt                     (수정) LocationFix에 altitudeM, verticalAccuracyM
  gpx/GpxParser.kt                 (수정) <ele> → altitudeM
  recording/RideModels.kt          RideStats, TrackPoint
  recording/TrackFilter.kt         TrackFilter, AcceptedFix
  recording/RideStatsCalculator.kt
  recording/AutoPauseDetector.kt
  recording/AltitudeFusion.kt      Barometric, AltitudeMode, AltitudeFusion, AscentCounter
  recording/RecordingSession.kt    PauseKind, RecordingState, RecordingEvent, RecordingUpdate, DayTotals, RecordingSession
  recording/RideStore.kt           SegmentTotals, RideSegment, TripRideSummary, RideStore, RideStats.toTotals()
  recording/RideCharts.kt          ChartPoint, RideCharts
  format/RideFormat.kt
  voice/KoreanPhrases.kt           (수정) 기록 문구
  voice/VoiceScheduler.kt          (수정) kmPhrase, resetKmBase
  navigation/NavigationSession.kt  (수정) KmAnnouncementSource, setKmSource
core/src/test/kotlin/.../recording/RecordingFixtures.kt + *Test.kt

app/src/main/java/com/cowork/bikerecoder/
  AppContainer.kt                  (수정) rideScope, rideStore, rideLocation, barometer, ride, rideUi
  data/Entities.kt, Daos.kt, AppDatabase.kt (수정)  data/RoomRideStore.kt (신규)
  data/SettingsRepository.kt       (수정) 설정 2개
  location/SharedLocationSource.kt, location/BarometerSource.kt (신규)  location/FusedLocationSource.kt (수정)
  ride/RideController.kt, ride/RideUiState.kt (신규)
  nav/NavigationController.kt      (수정) kmSource, locationSource = rideLocation
  nav/NavigationService.kt, nav/NavNotifications.kt (수정) 주행 서비스
  map/BikeMap.kt                   (수정) MapOverlay.track
  ui/BikeNavHost.kt                (수정) 기록·목록 경로
  ui/main/*, ui/navigate/*, ui/trip/*, ui/settings/* (수정)
  ui/record/{RecordScreen,RecordViewModel,RecordChoices,RideNumbers,SegmentSummary}.kt (신규)
  ui/history/{HistoryScreen,HistoryViewModel,TripDetailScreen,TripDetailViewModel,SegmentDetailScreen,SegmentDetailViewModel,LineChart,HistoryFormat}.kt (신규)
app/src/sharedTest/java/com/cowork/bikerecoder/ride/RideFixtures.kt (신규)
app/src/test/java/com/cowork/bikerecoder/ride/RideTestDoubles.kt (신규)
app/schemas/com.cowork.bikerecoder.data.AppDatabase/2.json (빌드가 생성, 커밋)
docs/verification/recording-field-test.md (신규), CHANGELOG.md, README.md (수정)
```

---

### Task 1: 버전 1.1.0과 위치 고도 필드

**Files:**
- Modify: `app/build.gradle.kts`(`verMinor = 1`), `app/src/test/java/com/cowork/bikerecoder/AppVersionTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/settings/SettingsScreenTest.kt`(69행 `onNodeWithText("V1.0.0")` → `"V1.1.0"`; 화면이 `AppVersion.display(BuildConfig.VERSION_NAME)`를 보이므로), `core/src/main/kotlin/com/cowork/bikerecoder/core/model/Geo.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/gpx/GpxParser.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/gpx/GpxParserTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class LocationFix(
    val point: GeoPoint, val accuracyM: Float, val speedMps: Float?, val bearingDeg: Float?, val timeMillis: Long,
    val altitudeM: Double? = null,          // 해발(MSL) 고도. 변환 실패 시 기기 고도, 없으면 null
    val verticalAccuracyM: Float? = null,
)
```
`GpxParser.parse`는 `<ele>`가 있으면 `altitudeM`에 넣는다(`verticalAccuracyM`은 null 그대로). 기존 호출부는 기본값 덕분에 바뀌지 않는다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// AppVersionTest
@Test fun `debug version name is 1_1_0-dev`() = assertEquals("1.1.0-dev", BuildConfig.VERSION_NAME)
@Test fun `version code follows rule`() = assertEquals(10100, BuildConfig.VERSION_CODE)
// (display 테스트에 assertEquals("V1.1.0", AppVersion.display("1.1.0-dev")) 추가)

// GpxParserTest
@Test fun `ele becomes altitude`()            // <trkpt><ele>123.4</ele><time>…</time></trkpt> → altitudeM == 123.4, verticalAccuracyM == null
@Test fun `missing ele leaves altitude null`()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.gpx.GpxParserTest' :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.AppVersionTest'` → FAIL
- [ ] **Step 3: 구현** — `verMinor = 1`, `LocationFix` 필드 2개(기본값 null), `GpxParser`에 `<ele>` 읽기.
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL(V1.0.0 테스트 전부 그대로 통과); 기기 `…class=com.cowork.bikerecoder.ui.settings.SettingsScreenTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "build: version 1.1.0; location fixes carry altitude"`

---

### Task 2: 위치 거르기와 주행 통계

**Files:**
- Create: `core/.../recording/RideModels.kt`, `core/.../recording/TrackFilter.kt`, `core/.../recording/RideStatsCalculator.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/recording/RecordingFixtures.kt`, `TrackFilterTest.kt`, `RideStatsCalculatorTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class RideStats(
    val distanceM: Double, val movingTimeMs: Long, val elapsedMs: Long,
    val currentSpeedMps: Double, val avgSpeedMps: Double, val maxSpeedMps: Double,
    val altitudeM: Double?, val ascentM: Double, val descentM: Double,
    val minAltitudeM: Double?, val maxAltitudeM: Double?,
) { companion object { val ZERO: RideStats } }
data class TrackPoint(val timeMillis: Long, val point: GeoPoint, val altitudeM: Double?, val speedMps: Float?, val accuracyM: Float)

/** 정확도 > [maxAccuracyM] 제외, 직전 채택 위치 대비 구간 속도 > [maxSpeedMps] 제외. 속도 때문에 연달아 버려지는 위치가 [reanchorAfter]번째가 되면 그 위치(5번째)를 버리지 않고 새 기준점으로 받는다(`reanchored = true`). */
class TrackFilter(maxAccuracyM: Float = 30f, maxSpeedMps: Double = 80.0 / 3.6, reanchorAfter: Int = 5) {
    fun accept(fix: LocationFix): AcceptedFix?          // null = 버림
}
/** [speedMps] = fix.speedMps ?: 직전 채택 위치와의 구간 속도(직전 없음·dt ≤ 0이면 0). [reanchored]면 거리 연결을 끊는다. */
data class AcceptedFix(val fix: LocationFix, val speedMps: Double, val reanchored: Boolean)

/** 기록 중 상태의 채택 위치만 받는다. */
class RideStatsCalculator(gpsGapMs: Long = 10_000, minGapSpeedMps: Double = 3.0 / 3.6, windowMs: Long = 3_000) {
    val distanceM: Double; val movingTimeMs: Long; val maxSpeedMps: Double
    val currentSpeedMps: Double          // (t − windowMs, t] 안 속도들의 평균
    val avgSpeedMps: Double              // distanceM / 이동 시간(초), 이동 시간 0이면 0
    fun add(fix: LocationFix, speedMps: Double)
    fun breakChain()                     // 일시정지·재개·재기준: 다음 위치와 거리·시간을 잇지 않고 속도 창도 비운다
}
```
- 규칙(결정): 연속된 두 위치의 간격 `dt ≤ gpsGapMs`면 거리·`dt`를 더한다. `dt > gpsGapMs`면 그 구간 평균 속도가 `minGapSpeedMps`(3km/h) 이상일 때만 더한다(터널), 아니면 둘 다 더하지 않는다.
- 최고 속도(결정): 위치마다 `(t − 3_000, t]` 창의 속도 중간값을 구해 최댓값을 갱신한다. 창에 2개 이상 있어야 하며 짝수 개면 **아래쪽 중간값**을 쓴다(1Hz면 3개의 진짜 중간값, 0.5Hz GPX면 2개 중 작은 값 → 튐 1회가 들어가지 않음).

- [ ] **Step 1: 테스트 헬퍼와 실패하는 테스트** — `RecordingFixtures.kt`: `fun north(m: Double) = GeoPoint(37.5 + Math.toDegrees(m / 6_371_008.8), 127.0)`, `fun fix(t: Long, m: Double, speedKmh: Double? = null, accuracyM: Float = 5f, altitudeM: Double? = null, verticalAccuracyM: Float? = null): LocationFix`.

```kotlin
// TrackFilterTest
@Test fun `accuracy over 30 m is excluded`()           // accuracyM 30.1f → null, 30.0f → 채택
@Test fun `jump over 80 km per h is excluded`()        // 1초에 25m(90km/h) → null; 1초에 22m(79.2km/h) → 채택
@Test fun `speed falls back to implied speed`()        // speedMps null, 1초 5m → speedMps == 5.0 ±1e-6
@Test fun `five consecutive jumps re-anchor without adding distance`()  // 기준점 m=0, 이후 m=500+5k(1초 간격) 5개 → 처음 4개 null, 5번째 AcceptedFix(reanchored = true)
// RideStatsCalculatorTest
@Test fun `distance sums accepted fixes`()             // 1Hz 5m/s 북쪽 1_000m → distanceM == 1_000.0 ±0.5, movingTimeMs == 200_000
@Test fun `break chain does not join across`()         // 0..100m, breakChain, 130..230m → distanceM == 200.0 ±0.5
@Test fun `gap over 10 s while stationary adds nothing`()   // t=0 m=0, t=60_000 m=10 → distanceM == 0.0, movingTimeMs == 0
@Test fun `moving gap over 10 s is bridged`()          // t=0 m=0, t=20_000 m=100(18km/h) → distanceM == 100.0 ±0.5, movingTimeMs == 20_000
@Test fun `average is distance over moving time`()     // 1_000m / 200s → avgSpeedMps == 5.0 ±1e-6; 위치 1개뿐이면 0.0
@Test fun `single spike does not raise max`()          // 1Hz 속도(km/h) 18,18,18,72,18,18 → maxSpeedMps*3.6 == 18.0 ±1e-6
@Test fun `max is the highest 3 s median`()            // 18,21.6,25.2,28.8,28.8,28.8 → 28.8
@Test fun `current speed is a 3 s moving average`()    // t=0,1000,2000 속도 3,6,9 m/s → currentSpeedMps == 6.0
@Test fun `two-sample window takes the lower median`() // 2초 간격 18, 72 → maxSpeedMps*3.6 == 18.0
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.recording.*'` → FAIL(미정의)
- [ ] **Step 3: 구현** — 위 시그니처와 규칙대로. 재기준된 위치는 이후 구간 속도의 직전 위치가 된다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: track filter and ride stats"`

---

### Task 3: 자동 일시정지 판정

**Files:**
- Create: `core/.../recording/AutoPauseDetector.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/recording/AutoPauseDetectorTest.kt`

**Interfaces:**
- Produces:
```kotlin
class AutoPauseDetector(
    pauseBelowMps: Double = 3.0 / 3.6, pauseAfterMs: Long = 5_000,
    resumeAtMps: Double = 5.0 / 3.6, resumeAfterMs: Long = 2_000, resumeDistanceM: Double = 15.0,
) {
    /** 기록 중 위치마다. 속도 < 3km/h가 시작된 시각부터 timeMillis까지 ≥ 5초면 true(그때 위치가 정지 지점). */
    fun shouldPause(timeMillis: Long, speedMps: Double, point: GeoPoint): Boolean
    /** 자동 일시정지 중 위치마다. 속도 ≥ 5km/h가 시작된 시각부터 ≥ 2초, 또는 정지 지점에서 ≥ 15m면 true. */
    fun shouldResume(timeMillis: Long, speedMps: Double, point: GeoPoint): Boolean
    fun reset()
}
```
- 입력 속도는 위치 한 건의 속도(`AcceptedFix.speedMps`)다. 3초 이동 평균이 아니다(결정: 스펙의 "5초 연속"을 그대로 시험할 수 있게). 경계: 3km/h 정확히는 느림이 아님, 5km/h 정확히는 빠름, 15m 정확히는 재개.
- 경계 정밀도(결정): 감지기는 Double로 비교하고 허용 오차를 두지 않는다. 그래서 Task 3 테스트는 `RecordingFixtures.fix()`(Float 속도)를 거치지 않고 감지기에 `kmh / 3.6`(Double)을 직접 넘긴다 — `3.0f/3.6f` 같은 Float 변환은 3km/h를 0.83333331 < 0.83333333으로 만들어 경계가 뒤집힌다. 위치는 `RecordingFixtures.north(m)`.

- [ ] **Step 1: 실패하는 테스트** (1Hz, 속도는 `kmh / 3.6` Double 그대로, 위치 `RecordingFixtures.north`)

```kotlin
@Test fun `under 3 km per h for 5 s pauses`()        // 2km/h로 t=0..5초 → t=4초까지 false, t=5초 true
@Test fun `4 s slow is not a pause`()                // 2km/h t=0..4초, t=5초 10km/h → 모두 false
@Test fun `exactly 3 km per h is not slow`()         // 3.0km/h로 10초 → 모두 false
@Test fun `5 km per h for 2 s resumes`()             // 일시정지 후 5.0km/h t=0,1,2초 → t=2초 true
@Test fun `rolling at 4 km per h does not resume`()  // 4km/h로 10초(11.1m 이동) → 모두 false
@Test fun `moving 15 m from the stop resumes`()      // 속도 0으로 보고되지만 위치가 정지 지점에서 14.9m → false, 15.1m → true
@Test fun `reset forgets slow and fast start times`()  // 2km/h t=0..4초 후 reset(), t=5..10초 2km/h → t=9초까지 false(느림 시작이 t=5초로 다시 잡힘), t=10초 true
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.recording.AutoPauseDetectorTest'` → FAIL
- [ ] **Step 3: 구현** — 느림/빠름 시작 시각을 기억하고 조건이 깨지면 지운다. `shouldPause`가 true를 반환하면 그 위치를 정지 지점으로 기억한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: auto pause detector"`

---

### Task 4: 고도 융합과 누적 상승·하강

**Files:**
- Create: `core/.../recording/AltitudeFusion.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/recording/AltitudeFusionTest.kt`

**Interfaces:**
- Produces:
```kotlin
object Barometric {
    const val SEA_LEVEL_HPA = 1013.25
    /** SensorManager.getAltitude와 같은 식: 44330 × (1 − (p / 1013.25)^(1 / 5.255)). */
    fun altitudeM(pressureHpa: Double): Double
}
enum class AltitudeMode { NONE, GPS, BAROMETER }

class AltitudeFusion(
    baroWindowMs: Long = 5_000, maxVerticalAccuracyM: Float = 10f,
    initialCalibrationMs: Long = 60_000, recalibrationMs: Long = 300_000, emaAlpha: Double = 0.1,
    baroStaleMs: Long = 10_000, gpsMedianSize: Int = 5, gpsSmoothingAlpha: Double = 0.25,
) {
    fun onPressure(pressureHpa: Double, nowMillis: Long)
    fun onGps(altitudeM: Double, verticalAccuracyM: Float?, nowMillis: Long)
    val mode: AltitudeMode            // 마지막 기압 표본이 baroStaleMs 이내면 BAROMETER, 아니면 GPS 고도가 있으면 GPS, 없으면 NONE
    val altitudeM: Double?            // 화면·저장용 해발 고도
    val climbAltitudeM: Double?       // 누적 상승 계산용: 기압 모드는 보정 전 기압 고도(오프셋 변화가 상승으로 잡히지 않게), GPS 모드는 평활 고도
    val climbThresholdM: Double       // BAROMETER 3.0, 그 외 5.0
}
class AscentCounter {
    val ascentM: Double; val descentM: Double
    fun onAltitude(altitudeM: Double, thresholdM: Double)   // 기준점 대비 |차| ≥ threshold면 누적하고 기준점 갱신
    fun rebase()                                            // 다음 고도를 새 기준점으로(일시정지 후 재개, 모드 전환)
}
```
- 기압 모드: 기압 고도 = 최근 5초 표본 평균. 보정 차이 = (수직 정확도 ≤ 10m인 GPS 고도) − (그때 기압 고도). 첫 차이가 들어온 뒤 60초 동안은 오프셋 = 지금까지 차이의 중간값(실시간), 60초가 되면 그 중간값으로 고정. 이후 5분마다 `offset += 0.1 × (지난 5분 차이 중간값 − offset)`(그 5분에 차이가 없으면 그대로). 오프셋이 아직 없으면 `altitudeM = null`.
- GPS 모드(결정 — 스펙의 "평활"): 최근 5개 GPS 고도의 중간값에 EMA α = 0.25.
- 테스트를 결정적으로 만드는 정의: 5초 평균 = `(now − 5_000, now]` 안 표본(1Hz면 5개)의 산술 평균. `AscentCounter`의 첫 기준점 = 처음 받은 고도. 테스트는 매초 `onPressure` 다음에 같은 `now`로 `climbAltitudeM`을 `AscentCounter`에 넣는다. 기울기 0.7m/s·0.1667m/s처럼 3m 경계에 정확히 걸리지 않는 값을 써서 반올림 오차로 결과가 바뀌지 않게 한다(아래 기대값은 python3로 계산, 1e-7m 흔들림을 넣어도 같음).

- [ ] **Step 1: 실패하는 테스트** (테스트 안에 역함수 `pressureFor(altM) = 1013.25 * (1 - altM / 44330).pow(5.255)`)

```kotlin
@Test fun `formula matches SensorManager`()          // altitudeM(1013.25) == 0.0 ±1e-9, altitudeM(900.0) == 988.65 ±0.01
@Test fun `barometer rise and fall accumulate`()      // 기압 고도 t=0..43 0.7t(0→30.1m), 10초 30.1m, 0.7m/s로 29초 내려 9.8m, 10초 9.8m
                                                      //   → ascentM == 28.0 ±0.01, descentM == 17.36 ±0.01 (참값 30.1/20.3에서 기준점 이력 3m만큼 덜 잡힘)
@Test fun `calibration uses the median gps difference over the first 60 s`()  // 기압 고도 100 고정, GPS 150,152,148…(vAcc 5f) + GPS 200(vAcc 15f) → 60초 후 altitudeM == 150.0 ±1.0
@Test fun `recalibrates every 5 min with ema 0_1`()   // 초기 오프셋 50 이후 5분 동안 GPS 160(vAcc 5f) → 그 5분 끝 altitudeM == 151.0 ±0.1
@Test fun `2 m wobble is not accumulated`()           // 기압 모드 100,102,98,100…(1Hz, 2분) → ascentM == 0.0, descentM == 0.0; GPS 모드 같은 값 → 0.0
@Test fun `50 m climb gives 50 plus minus 3 m`()      // 기압 모드, 고도 100 + 0.1667t (t=0..300, 50.01m 오름) + 결정적 노이즈(짝수 초 +0.83, 홀수 초 −0.83), 끝에 30초 평지(노이즈 계속)
                                                      //   → ascentM in 47.0..53.0 (계산값 48.01), descentM == 0.0
@Test fun `no barometer uses gps`()                   // onPressure 없이 GPS만 → mode == GPS, altitudeM != null, climbThresholdM == 5.0
@Test fun `stale barometer switches to gps`()         // 마지막 기압 t, onGps(t + 10_001) → mode == GPS
@Test fun `gps median rejects a single spike`()       // GPS 100,100,100,160,100 → altitudeM == 100.0 ±1.0
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.recording.AltitudeFusionTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: barometer and gps altitude fusion with ascent hysteresis"`

---

### Task 5: 기록 음성 문구와 표시 형식

**Files:**
- Create: `core/.../format/RideFormat.kt`
- Modify: `core/.../voice/KoreanPhrases.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/format/RideFormatTest.kt`, `core/src/test/kotlin/com/cowork/bikerecoder/core/voice/KoreanPhrasesTest.kt`

**Interfaces:**
- Produces:
```kotlin
object RideFormat {
    fun speedKmh(mps: Double): String       // km/h 소수 첫째 자리 HALF_UP: 5.0 → "18.0"
    fun duration(ms: Long): String          // 분 내림. 1시간 미만 "{m}분", 이상 "{h}시간 {m}분"
    fun clock(ms: Long): String             // "H:MM:SS" (초 내림)
    fun distance(m: Double): String         // km 소수 둘째 자리 내림 + "km": 3_009.9 → "3.00km"
    fun altitude(m: Double?): String        // 반올림 "123m", null → "--"
    fun date(d: LocalDate): String          // "2026년 10월 12일"
}
// KoreanPhrases 추가
val recordingStarted: String   // "기록을 시작합니다"
val recordingPaused: String    // "일시정지되었습니다"
val recordingResumed: String   // "기록을 다시 시작합니다"
fun rideKmReport(km: Int, avgSpeedMps: Double, movingTimeMs: Long): String
fun rideKmReportWithNav(km: Int, avgSpeedMps: Double, movingTimeMs: Long, remainingM: Double, etaMillis: Long): String
```
`rideKmReportWithNav`의 {거리}·{시각}은 기존 `distance()`·`clockTime()`을, {x.x}·{시간}은 `RideFormat.speedKmh`·`RideFormat.duration`을 쓴다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// KoreanPhrasesTest
@Test fun `recording phrases`() {
    assertEquals("기록을 시작합니다", p.recordingStarted)
    assertEquals("일시정지되었습니다", p.recordingPaused)
    assertEquals("기록을 다시 시작합니다", p.recordingResumed)
}
@Test fun `km report while recording only`() =
    assertEquals("3킬로미터 이동. 평균 속도 18.0킬로미터, 이동 시간 10분입니다.", p.rideKmReport(3, 5.0, 600_000))
@Test fun `km report combined with guidance`() = assertEquals(
    "1킬로미터 이동. 평균 속도 18.0킬로미터, 이동 시간 3분. 남은 거리 1.2킬로미터, 도착 예정 오후 3시 5분입니다.",
    p.rideKmReportWithNav(1, 5.0, 200_000, 1_200.0, Instant.parse("2026-10-12T06:05:00Z").toEpochMilli()),
)
// RideFormatTest
@Test fun `duration reading`() {
    assertEquals("0분", RideFormat.duration(59_999)); assertEquals("59분", RideFormat.duration(3_599_999))
    assertEquals("1시간 0분", RideFormat.duration(3_600_000)); assertEquals("1시간 30분", RideFormat.duration(5_400_000))
}
@Test fun `clock distance speed altitude date`() {
    assertEquals("0:05:07", RideFormat.clock(307_000)); assertEquals("12:00:00", RideFormat.clock(43_200_000))
    assertEquals("3.00km", RideFormat.distance(3_009.9)); assertEquals("12.34km", RideFormat.distance(12_345.0))
    assertEquals("18.0", RideFormat.speedKmh(5.0)); assertEquals("--", RideFormat.altitude(null)); assertEquals("124m", RideFormat.altitude(123.5))
    assertEquals("2026년 10월 12일", RideFormat.date(LocalDate.of(2026, 10, 12)))
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.voice.KoreanPhrasesTest' --tests 'com.cowork.bikerecoder.core.format.RideFormatTest'` → FAIL
- [ ] **Step 3: 구현** — 소수 처리는 `BigDecimal`(기존 `distance()`와 같은 방식)로 해 이진 부동소수 경계 오류를 피한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: recording phrases and ride formatting"`

---

### Task 6: 기록 엔진 RecordingSession

**Files:**
- Create: `core/.../recording/RecordingSession.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/recording/RecordingSessionTest.kt`

**Interfaces:**
- Consumes: Task 2 `TrackFilter`·`RideStatsCalculator`·`RideStats`·`TrackPoint`, Task 3 `AutoPauseDetector`, Task 4 `AltitudeFusion`·`AscentCounter`
- Produces:
```kotlin
enum class PauseKind { AUTO, MANUAL }
sealed interface RecordingState {
    data object Recording : RecordingState
    data class Paused(val kind: PauseKind) : RecordingState
    data object Stopped : RecordingState
}
sealed interface RecordingEvent {
    data object Started : RecordingEvent
    data class Paused(val kind: PauseKind) : RecordingEvent
    data object Resumed : RecordingEvent
    /** 그날 기록 거리가 [km]번째 킬로미터를 넘었다. day*는 그날(이 여행, Asia/Seoul) 합계. */
    data class KmReached(val km: Int, val dayDistanceM: Double, val dayMovingTimeMs: Long) : RecordingEvent
}
data class DayTotals(val distanceM: Double, val movingTimeMs: Long) {
    /** 그날 평균 속도 = distanceM / (movingTimeMs / 1000.0), 이동 시간 0이면 0. 1km 안내 문장의 {x.x}는 이것 하나만 쓴다(Task 10·11). */
    val avgSpeedMps: Double
    companion object { val ZERO: DayTotals }
}
/** [point]: 이번 위치가 기록 중 상태에서 채택됐으면 저장할 점. */
data class RecordingUpdate(val state: RecordingState, val stats: RideStats, val events: List<RecordingEvent>,
                           val point: TrackPoint?, val gpsWeak: Boolean)

class RecordingSession(
    startMillis: Long, autoPause: Boolean, initialDay: DayTotals = DayTotals.ZERO,
    zone: ZoneId = ZoneId.of("Asia/Seoul"), gpsTimeoutMs: Long = 10_000,
) {
    val state: RecordingState; val autoPause: Boolean; val stats: RideStats; val day: DayTotals
    fun start(): RecordingUpdate                                  // Started, 상태 Recording. 한 번만
    fun onFix(fix: LocationFix, nowMillis: Long): RecordingUpdate
    fun onPressure(pressureHpa: Double, nowMillis: Long)
    fun onTick(nowMillis: Long): RecordingUpdate                  // 경과 시간 갱신, GPS 감시
    fun pause(nowMillis: Long): RecordingUpdate                   // → Paused(MANUAL), 이미 일시정지면 무시
    fun resume(nowMillis: Long): RecordingUpdate                  // 일시정지(AUTO|MANUAL) → Recording
    fun setAutoPause(enabled: Boolean, nowMillis: Long): RecordingUpdate
    fun stop(nowMillis: Long): RecordingUpdate                    // → Stopped, 이후 호출은 무시(빈 events)
}
```
- `onFix` 순서: `TrackFilter.accept` → 고도(`fix.altitudeM`이 있으면 `onGps(…, nowMillis)`) → 상태별 처리:
  - Recording: `RideStatsCalculator.add`(재기준이면 먼저 `breakChain`), 상승은 `AscentCounter.onAltitude(climbAltitudeM, climbThresholdM)`(모드가 바뀌면 `rebase`), 최저·최고 고도 갱신, `TrackPoint` 생성, 자동 모드면 `shouldPause` → `Paused(AUTO)` 이벤트 + `breakChain`.
  - Paused(AUTO): `shouldResume`이면 `Resumed` + `breakChain` + `rebase`(정지 지점→재개 위치 거리는 더하지 않음). 그다음 **같은 위치를 Recording 처리에 넘긴다**: 계산기에 새 사슬의 첫 점으로 `add`, 상승 기준점이 되고, `point`로도 내보낸다. Paused(MANUAL): 자동 재개 없음.
- 상태가 바뀔 때마다(자동/수동 일시정지, `resume`, 자동 재개, `setAutoPause`) `AutoPauseDetector.reset()`을 부른다 — 이전 느림/빠름 시작 시각이 남아 수동 재개 직후 바로 다시 일시정지되는 일을 막는다.
- 그날 합계: `day = initialDay + 이 세션의 거리·이동 시간`. `nowMillis`의 `zone` 날짜가 바뀌면 `day`를 0에서 다시 시작하고 km도 1부터(결정: 날짜는 tick 시각 기준). km 이벤트는 `floor(day.distanceM / 1000)`이 커질 때 한 번.
- 일시정지 중 `stats.currentSpeedMps == 0.0`. 경과 시간 = 지금까지 본 가장 큰 `nowMillis − startMillis`.
- GPS 감시: R11과 같다 — `onFix`는 "봤음"만 표시, `onTick(now)`은 마지막 활동 tick 이후 `gpsTimeoutMs`가 지나면 `gpsWeak = true`, 새 위치가 오면 false.
- `setAutoPause(false)`가 `Paused(AUTO)` 중에 오면 즉시 `Resumed`(Review Focus 3). `resume()`은 AUTO 일시정지도 푼다.

- [ ] **Step 1: 실패하는 테스트** (1Hz `RecordingFixtures`, `now = fix.timeMillis`)

```kotlin
@Test fun `start emits Started`()                                  // events == [Started], state == Recording
@Test fun `manual mode never auto pauses`()                        // autoPause=false, 속도 0으로 30초 → state == Recording, Paused 이벤트 없음
@Test fun `buttons pause and resume in manual mode`()              // pause → [Paused(MANUAL)], resume → [Resumed]
@Test fun `manual pause is not auto resumed`()                     // autoPause=true, pause() 후 18km/h 10초 → 계속 Paused(MANUAL)
@Test fun `turning auto pause off while auto paused resumes`()     // Paused(AUTO) 중 setAutoPause(false) → [Resumed], state == Recording
@Test fun `manual resume does not pause at once on a slow fix`()  // autoPause=true, 2km/h로 4초 → pause() → resume() → 2km/h 위치 4개 더(4초) → state == Recording (reset으로 느림 시작이 재개 뒤로)
@Test fun `reanchored fix adds no distance`()                     // autoPause=false, m=0(t=0) 채택, t=1..5 m=505,510,515,520,525(속도 18km/h) → 5번째 재기준,
                                                                   //   이어서 t=6..25 m=530..625 → distanceM == 100.0 ±0.5 (525m 기준점부터만)
@Test fun `paused distance and time are not recorded`()            // 0→100m(18km/h), 100m에서 속도 0으로 20초(5초째 일시정지), 다시 18km/h로 215m까지
                                                                   //   (105·110·115m에서 115m 때 재개, 115m 위치가 새 사슬의 첫 점이며 point로 나옴) → distanceM == 200.0 ±1.0 (= 100 + (215−115)), movingTimeMs < elapsedMs
@Test fun `elapsed includes pauses`()                              // start 0, onTick(90_000) → elapsedMs == 90_000
@Test fun `speed is 0 while paused`()                              // Paused 상태 update.stats.currentSpeedMps == 0.0
@Test fun `track points only while recording`()                    // Paused 중 onFix → point == null; 재개시키는 위치 → point != null(그 위치)
@Test fun `km continues the day's distance`()                      // initialDay = DayTotals(2_500.0, 500_000), 600m 주행 → KmReached(3, …) 한 번만
@Test fun `km restarts at 1 the next day`()                        // 시작 23:58 KST, initialDay 2_900m, 200m(23:59) → KmReached(3); 0시 이후 1_050m → KmReached(1), 4는 없음
@Test fun `gps weak after 10 s on tick time`()                     // fix 뒤 onTick(t0), onTick(t0+10_001) → gpsWeak; 새 fix → false
@Test fun `ascent only while recording`()                          // 기압 모드, 매초 onPressure 후 onFix. pause() 후 t=0..39 기압 고도 min(0.7t, 20.3) → ascentM == 0.0;
                                                                   //   resume(40_000), t=40..68 20.3+0.7(t−39)(→40.6), 10초 평지 → ascentM == 17.36 ±0.01 (python3 계산)
@Test fun `stop freezes`()                                         // stop 후 state == Stopped, onFix → events 빈 목록, stats 그대로
@Test fun `day average speed`()                                    // DayTotals(1_000.0, 200_000).avgSpeedMps == 5.0, DayTotals(1_000.0, 0).avgSpeedMps == 0.0
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.recording.RecordingSessionTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test` → 전체 PASS
- [ ] **Step 5: Commit** — `git commit -m "core: recording session with auto pause, day km and gps watchdog"`

---

### Task 7: 안내 1km 안내를 기록 쪽으로 넘기기

**Files:**
- Modify: `core/.../voice/VoiceScheduler.kt`, `core/.../navigation/NavigationSession.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/voice/VoiceSchedulerTest.kt`, `core/src/test/kotlin/com/cowork/bikerecoder/core/navigation/NavigationSessionTest.kt`

**Interfaces:**
- Produces:
```kotlin
/** 기록 중일 때 안내 엔진의 1km 안내 기준 거리와 문장을 대신한다. null이면 V1.0.0 동작. */
interface KmAnnouncementSource {
    fun distanceM(): Double                                         // 그날 기록 거리
    fun phrase(km: Int, remainingM: Double, etaMillis: Long): String
}
// NavigationSession
fun setKmSource(source: KmAnnouncementSource?)   // 설정·해제할 때 km 기준을 그 거리로 다시 맞춘다(지난 km를 몰아서 말하지 않음)
// VoiceScheduler
fun onUpdate(progress: Progress, speedMps: Double, sessionDistanceM: Double, etaMillis: Long,
             kmPhrase: (km: Int) -> String = { phrases.kmReport(it, progress.remainingM, etaMillis) }): List<Utterance>
fun resetKmBase(distanceM: Double)               // lastKmFloor = floor(distanceM / 1000), 보류 중인 km 안내 버림
```
- `NavigationSession.onFix`는 소스가 있으면 `onUpdate(…, sessionDistanceM = source.distanceM(), …) { km -> source.phrase(km, progress.remainingM, etaMillis) }`, 없으면 지금처럼 `odometer.distanceM`과 기본 문장. 회전 임박 시 미루기 규칙은 그대로 적용된다. `sessionDistanceM` 프로퍼티(오늘 이동 거리 저장용)는 바뀌지 않는다.
- 해제(`null`)하면 `resetKmBase(odometer.distanceM)`.

- [ ] **Step 1: 실패하는 테스트** (`TestRoutes.straightRoute(5_000.0)`, 거리는 GeoMath로 계산; 가짜 소스는 `var d = 0.0`과 `"SRC $km"` 문장)

```kotlin
// NavigationSessionTest
@Test fun `km source replaces distance and phrase`()        // 소스 설정 후 경로 1.5km 주행, d=999 → km 안내 없음; d=1_000 → "SRC 1" 한 번; 기본 kmReport 문장 없음
@Test fun `setting a source does not announce past km`()   // 주행 2.5km 뒤 d=1_700으로 설정 → 안내 없음; d=2_000 → "SRC 2"
@Test fun `clearing the source resumes odometer km without repeats`()  // 소스 해제 시점 주행 2.5km → 3km 지점에서만 기본 kmReport(3, …)
@Test fun `source km waits for an imminent turn`()          // 회전 임박 상태에서 d가 1km 통과 → 그 업데이트엔 없음, near 안내 다음 업데이트에 "SRC 1"
// VoiceSchedulerTest
@Test fun `reset km base drops a pending report`()   // 회전 임박으로 km 1 안내가 보류된 상태에서 resetKmBase(1_500.0) → near 안내 뒤 업데이트에도 km 안내 없음;
                                                     //   sessionDistanceM 2_000 → kmReport(2, …) 한 번
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.navigation.NavigationSessionTest' --tests 'com.cowork.bikerecoder.core.voice.VoiceSchedulerTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test` → 전체 PASS(V1.0.0 안내 테스트 그대로)
- [ ] **Step 5: Commit** — `git commit -m "core: navigation km announcements can come from the recording"`

---

### Task 8: Room v2 — 주행 구간·위치 기록, 마이그레이션 (스펙 ⑥)

**Files:**
- Create: `core/.../recording/RideStore.kt`, `app/src/main/java/com/cowork/bikerecoder/data/RoomRideStore.kt`
- Modify: `app/.../data/Entities.kt`, `app/.../data/Daos.kt`(`RideDao` 추가), `app/.../data/AppDatabase.kt`, `app/.../data/RoomTripStore.kt`(`private fun TripEntity.toTrip()` → `internal`; `RoomRideStore.tripSummaries()`가 그대로 쓴다 — 복사 금지), `gradle/libs.versions.toml`(`androidx-room-testing`), `app/build.gradle.kts`(`androidTestImplementation(libs.androidx.room.testing)`)
- Create (생성물): `app/schemas/com.cowork.bikerecoder.data.AppDatabase/2.json`
- Test: `app/src/test/java/com/cowork/bikerecoder/data/RideMappingsTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/data/RoomRideStoreTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/data/RideDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: Task 2 `RideStats`·`TrackPoint`, Task 6 `DayTotals`, 기존 `Trip`
- Produces:
```kotlin
// core/recording/RideStore.kt
data class SegmentTotals(val distanceM: Double, val movingTimeMs: Long, val elapsedMs: Long, val maxSpeedMps: Double,
                         val ascentM: Double, val descentM: Double, val minAltitudeM: Double?, val maxAltitudeM: Double?) {
    companion object { val ZERO: SegmentTotals }
}
fun RideStats.toTotals(): SegmentTotals
data class RideSegment(val id: Long, val tripId: Long, val startedAt: Long, val endedAt: Long?,
                       val localDate: LocalDate, val totals: SegmentTotals)
/** 목록 카드 한 장: dayCount = 구간이 있는 서로 다른 날짜 수, firstDate = 가장 이른 구간 날짜. */
data class TripRideSummary(val trip: Trip, val firstDate: LocalDate, val dayCount: Int,
                           val distanceM: Double, val movingTimeMs: Long, val lastStartedAt: Long)
interface RideStore {
    suspend fun openSegment(tripId: Long, startedAt: Long, localDate: LocalDate): Long
    suspend fun appendPoints(segmentId: Long, points: List<TrackPoint>, totals: SegmentTotals)  // 한 트랜잭션
    suspend fun closeSegment(segmentId: Long, endedAt: Long, totals: SegmentTotals)
    suspend fun openSegments(): List<RideSegment>                         // endedAt == null, startedAt 내림차순
    suspend fun segment(id: Long): RideSegment?
    suspend fun segments(tripId: Long): List<RideSegment>                 // startedAt 오름차순
    suspend fun points(segmentId: Long): List<TrackPoint>                 // timeMillis 오름차순
    suspend fun dayTotals(tripId: Long, localDate: LocalDate): DayTotals  // 그 여행·그 날짜 구간 합
    suspend fun tripSummaries(): List<TripRideSummary>                    // 구간 ≥ 1인 여행, lastStartedAt 내림차순
    suspend fun deleteSegment(id: Long)                                   // 위치 기록도 CASCADE
    suspend fun deleteTrip(tripId: Long)                                  // trip 행 삭제 → 경유지·구간·위치 CASCADE
}
```
- 엔티티(스펙 §4.8 그대로): `RideSegmentEntity`(`ride_segment`: id, tripId FK→trip CASCADE + index, startedAt, endedAt?, localDate: String(ISO), distanceM, movingTimeMs, elapsedMs, maxSpeedMps, ascentM, descentM, minAltitudeM?, maxAltitudeM?), `TrackPointEntity`(`track_point`: id, segmentId FK→ride_segment CASCADE + index, timeMillis, lat, lon, altitudeM?, speedMps: Float?, accuracyM: Float).
- `@Database(version = 2, entities = [기존 3개, RideSegmentEntity, TrackPointEntity], autoMigrations = [AutoMigration(from = 1, to = 2)], exportSchema = true)`, `abstract fun rideDao(): RideDao`.
- 엔티티↔모델 변환은 `RoomRideStore.kt`의 `internal fun RideSegmentEntity.toModel()`, `internal fun TrackPoint.toEntity(segmentId: Long)` 등으로 두고 JVM에서 시험한다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// RideMappingsTest (JVM)
@Test fun `segment round trip keeps nulls and date`()     // endedAt=null, min/maxAltitude=null, localDate 2026-10-12 → 엔티티 "2026-10-12" → 같은 모델
@Test fun `track point round trip`()                // TrackPoint(1_000L, GeoPoint(37.5, 127.0), altitudeM = null, speedMps = null, accuracyM = 5f).toEntity(segmentId = 7)
                                                    //   → entity.segmentId == 7, altitudeM == null, speedMps == null; toModel() == 원래 TrackPoint
// RoomRideStoreTest (기기, in-memory, allowMainThreadQueries)
@Test fun appendUpdatesPointsAndTotalsTogether()        // appendPoints 2회 → points 순서, segment.totals == 마지막 totals
@Test fun closeAndOpenSegments()                        // 열린 2개 중 1개 close → openSegments == 나머지 1개
@Test fun dayTotalsSumOnlyThatTripAndDate()             // 같은 여행·같은 날 2구간 + 다른 날 + 다른 여행 → 앞 2구간 합
@Test fun tripSummariesNewestFirstWithDayCount()        // 여행 A(2개 날짜), B(최근) → [B, A], A.dayCount == 2
@Test fun deletingATripCascades()                       // deleteTrip → segments·points·waypoints 모두 0
@Test fun deletingASegmentCascadesPoints()
// RideDatabaseMigrationTest (기기, 스펙 ⑥)
@Test fun v1DataSurvivesMigrationTo2()  // MigrationTestHelper(instrumentation, AppDatabase::class.java)로 v1 생성, trip·waypoint를 SQL로 넣고
                                        // runMigrationsAndValidate(DB, 2, true) → Room으로 열어 RoomTripStore.trip(id)·stops(id) 동일,
                                        // RoomRideStore.tripSummaries() 빈 목록, openSegment 동작. @After에서 context.deleteDatabase(DB)
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.data.RideMappingsTest'` → FAIL
- [ ] **Step 3: 구현** — 엔티티·DAO·`RoomRideStore`. `appendPoints`는 DAO의 `@Transaction open suspend fun`에서 점 삽입 + 합계 UPDATE. 빌드 후 `2.json`이 생겼는지 확인. 기기 테스트가 `1.json`을 못 찾으면 `android.sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")`를 추가한다.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL; 기기: `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.bikerecoder.data.RoomRideStoreTest` 와 `…class=com.cowork.bikerecoder.data.RideDatabaseMigrationTest` → PASS(휴대폰 없으면 기기 검증 대기), 기존 `RoomTripStoreTest`도 다시 PASS
- [ ] **Step 5: Commit** — `git add app/schemas …` 포함, `git commit -m "data: room v2 with ride segments and track points"`

---

### Task 9: 공유 위치 스트림, 기압계, 해발 고도, 기록 설정

**Files:**
- Create: `app/.../location/SharedLocationSource.kt`, `app/.../location/BarometerSource.kt`
- Modify: `app/.../location/FusedLocationSource.kt`, `app/.../data/SettingsRepository.kt`, `app/.../ui/settings/SettingsViewModel.kt`, `app/.../ui/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/location/SharedLocationSourceTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/settings/SettingsViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Produces:
```kotlin
/** 위로는 [upstream]을 한 번만 수집하고 여러 구독자에게 나눠 준다. 늦게 온 구독자는 마지막 위치 1개부터 받는다(P4, replay = 1).
 *  구독자가 0이 되면 바로 멈추고 그 마지막 위치도 버리며(오래된 위치를 주지 않음), 다시 구독하면 새로 시작한다.
 *  위쪽이 끝나면 모든 구독자 flow가 정상 종료하고, 실패하면 모든 구독자 flow가 같은 예외로 끝난다. */
class SharedLocationSource(upstream: () -> LocationSource, scope: CoroutineScope) : LocationSource
interface PressureSource { fun pressures(): Flow<Float> }   // hPa
class BarometerSource(context: Context) : PressureSource { val available: Boolean }   // Sensor.TYPE_PRESSURE, 1초 주기, 없으면 available=false
data class AppSettings(…기존…, val autoStartRecordingWithNavigation: Boolean = true, val autoPauseEnabled: Boolean = true)
// SettingsViewModel
fun setAutoStartRecording(enabled: Boolean); fun setAutoPause(enabled: Boolean)
// SettingsTags
const val AUTO_START_RECORDING = "settings_auto_start_recording"; const val AUTO_PAUSE = "settings_auto_pause"
```
- `SharedLocationSource`: 위쪽을 `Fix | End | Failed(e)` 항목으로 감싸 `shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)`하고, 구독자 쪽에서 `End`면 종료, `Failed`면 예외를 던진다(실패를 scope로 흘려 보내지 않는다).
- `FusedLocationSource`: `altitudeM` = API 34+에서 `AltitudeConverter().addMslAltitudeToLocation(context, location)` 후 `mslAltitudeMeters`, 실패(예외)·API 33 이하면 `altitude`(`hasAltitude()`일 때), `verticalAccuracyM` = `hasVerticalAccuracy()`면 `verticalAccuracyMeters`. 변환은 디스크를 읽어 막힐 수 있으므로 `callbackFlow { … }.map { it.toFix() }.flowOn(Dispatchers.IO)`로 메인 스레드 밖에서 한다.
- 설정 화면: "기록" 구역에 스위치 2개 — "안내 시작 시 기록 자동 시작", "자동 일시정지(기본값)".

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// SharedLocationSourceTest (JVM, runTest). 앞 세 테스트의 위쪽은 NavTestDoubles의 ScriptedLocationSource(collections/collecting 확인용, 끝나지도 실패하지도 않음).
// 끝·실패 테스트는 테스트 안의 작은 LocationSource를 쓴다: object : LocationSource { override fun fixes() = flowOf(f1, f2) } / flow { emit(f1); throw IOException("gps") }
@Test fun `two subscribers share one upstream collection`()    // 두 구독자 모두 같은 fix 목록, source.collections == 1
@Test fun `upstream stops when the last subscriber leaves`()   // 둘 다 취소 → source.collecting == false; 다시 구독 → collections == 2, 첫 위치 == fixes[0](앞서 본 마지막 위치가 아님)
@Test fun `late subscriber gets the latest fix first`()        // A가 f0,f1,f2를 받은 뒤 B 구독 → B의 첫 위치 == f2, 다음 f3
@Test fun `upstream failure reaches every subscriber`()        // flow { emit(f1); throw IOException("gps") } → 두 구독자 모두 f1을 받은 뒤 IOException("gps")로 종료, scope.isActive == true
@Test fun `upstream end completes subscribers`()               // flowOf(f1, f2) → 두 구독자 모두 toList() == [f1, f2]로 정상 종료
// SettingsViewModelTest
@Test fun `recording settings default on and toggle`()         // 기본 true/true, setAutoStartRecording(false)·setAutoPause(false) 반영
// SettingsScreenTest (기기)
@Test fun recordingSwitchesToggle()                             // AUTO_START_RECORDING·AUTO_PAUSE 노드 클릭 → 콜백 값 false
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.location.SharedLocationSourceTest' --tests 'com.cowork.bikerecoder.ui.settings.SettingsViewModelTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.settings.SettingsScreenTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: shared location stream, barometer, msl altitude, recording settings"`

---

### Task 10: RideController — 기록 실행·저장·복구 (스펙 ①②④의 JVM판)

**Files:**
- Create: `app/.../ride/RideUiState.kt`, `app/.../ride/RideController.kt`, `app/src/sharedTest/java/com/cowork/bikerecoder/ride/RideFixtures.kt`, `app/src/test/java/com/cowork/bikerecoder/ride/RideTestDoubles.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ride/RideControllerTest.kt`

**Interfaces:**
- Consumes: Task 5 `KoreanPhrases` 기록 문구, Task 6 `RecordingSession`, Task 8 `RideStore`, Task 9 `PressureSource`·`AppSettings`, 기존 `NavigationController`·`TripManager`·`TripStore`·`VoiceOutput`·`WarnLog`
- Produces:
```kotlin
sealed interface RideUiState {
    data object Idle : RideUiState
    data class Starting(val tripId: Long) : RideUiState
    data class Active(
        val tripId: Long, val tripType: TripType, val segmentId: Long,
        val state: RecordingState, val stats: RideStats, val autoPause: Boolean,
        val track: List<GeoPoint>,        // 화면용: 직전 점에서 10m 이상 떨어진 채택 위치만
        val fix: LocationFix?, val gpsWeak: Boolean,
        val saveWarning: String?,         // "기록 저장에 문제가 있습니다" (한 기록에 한 번)
    ) : RideUiState
    data class Failed(val tripId: Long, val message: String) : RideUiState      // "기록을 시작하지 못했습니다"
    /** 구간이 닫혔다(요약 표시). [error]: 마지막 저장 실패 시 "기록 저장에 문제가 있습니다". */
    data class Finished(val segment: RideSegment, val track: List<GeoPoint>, val error: String?) : RideUiState
}
enum class RecordingEnd { SEGMENT_ONLY, STOP_TODAY, COMPLETE_TRIP }

class RideController(
    rideStore: RideStore, tripStore: TripStore, tripManager: TripManager, navigation: NavigationController,
    voice: VoiceOutput, location: LocationSource, pressure: PressureSource?, settings: Flow<AppSettings>,
    scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, phrases: KoreanPhrases = KoreanPhrases(),
    zone: ZoneId = ZoneId.of("Asia/Seoul"), tickIntervalMs: Long = 1_000, saveIntervalMs: Long = 5_000,
    log: WarnLog = androidWarnLog(TAG),
) {
    val ui: StateFlow<RideUiState>
    fun startRecording(tripId: Long): Job?     // 반환 시 ui == Starting(같은 여행이 Starting/Active면 null, 다른 여행 기록 중이면 무시하고 null)
    fun pause(); fun resume(); fun setAutoPause(enabled: Boolean)
    suspend fun stopRecording(end: RecordingEnd)
    fun dismissSummary()                       // Finished/Failed → Idle
    suspend fun interrupted(): RideSegment?    // ui == Idle일 때 가장 최근의 열린 구간
    suspend fun closeInterrupted()             // 열린 구간 모두 닫기 + 그 여행 끝내기(아래)
    companion object { const val MSG_START_FAILED = "기록을 시작하지 못했습니다"; const val MSG_SAVE_PROBLEM = "기록 저장에 문제가 있습니다" }
}
// sharedTest
object RideFixtures {
    data class StopAt(val atM: Double, val seconds: Int)
    fun north(m: Double): GeoPoint                      // GeoPoint(37.5 + Math.toDegrees(m / 6_371_008.8), 127.03)
    /** 1초 간격 북쪽 직진(정확도 5m, speedMps 설정, 고도 50m). stops 지점에서 seconds초 동안 같은 위치·속도 0. */
    fun straight(distanceM: Double, speedKmh: Double = 18.0, stops: List<StopAt> = emptyList(), startMillis: Long = 0L): List<LocationFix>
}
```
- 시작(`startRecording`): ① `rideStore.openSegments()`를 모두 닫는다(`endedAt = startedAt + totals.elapsedMs`, 합계는 마지막 저장값 — Review Focus 4) ② 여행이 ACTIVE가 아니면 `Failed` ③ `dayTotals(tripId, 오늘)`로 `RecordingSession(clock(), settings.autoPauseEnabled, initialDay)` ④ `openSegment` ⑤ `start()` → "기록을 시작합니다" ⑥ 위치·기압·tick(1초)·저장(5초) 수집을 이 scope에서 시작.
- 음성(설정 `voiceEnabled`가 false면 생략): `Started`/`Paused`/`Resumed` → 해당 문구. `KmReached` → `navigation.ui.value`가 `NavUiState.Active`가 아닐 때만 `rideKmReport(km, DayTotals(dayDistanceM, dayMovingTimeMs).avgSpeedMps, dayMovingTimeMs)`(결정: 평균·이동 시간도 km와 같은 "그날" 기준, 평균 계산은 Task 6의 `DayTotals.avgSpeedMps` 하나만 쓴다; 안내 중에는 Task 11의 합친 문장이 대신한다).
- 위치 흐름이 예외로 끝나거나 그냥 끝나면(권한 회수, Play 서비스 오류 — 스펙 §4.7): 로그를 남기고 세션은 그대로 둔 채 5초 뒤 다시 구독한다(기록 중인 동안 반복). 그 사이 GPS 감시가 10초 뒤 `gpsWeak = true`로 "GPS 신호 약함"을 띄우고 거리는 늘지 않는다.
- 저장: `saveIntervalMs`마다 `appendPoints(segmentId, 버퍼, stats.toTotals())`(일시정지 중에도 합계는 저장). 성공한 점만 버퍼에서 뺀다. 실패: 로그 + 처음 한 번 `saveWarning`, 버퍼 유지 후 다음 주기 재시도(Review Focus 5).
- 정지(`stopRecording`): `session.stop(clock())` → 마지막 저장 → `closeSegment(endedAt = clock())` → `end`에 따라 `SEGMENT_ONLY` 없음 / `STOP_TODAY` `tripManager.endToday`(MULTI_DAY만) / `COMPLETE_TRIP` `tripManager.complete` → 수집 중지 → `Finished(메모리의 구간 요약, track, error)`.
- `closeInterrupted`(컨트롤러 판정 P2 — 스펙 §4.6 [종료]): 열린 구간을 마지막 저장 시각으로 닫고, 그 여행이 ACTIVE이고 안내 중이 아니면 SINGLE_DAY → `complete`, MULTI_DAY → `endToday`.

- [ ] **Step 1: 실패하는 테스트** (`RideTestDoubles.kt`: `FakeRideStore(trips: FakeTripStore)` — 메모리 구현, `var failAppend: Boolean`, 호출 기록 `appendCalls: List<Pair<Long /*가상 시각*/, SegmentTotals>>`·`deletedTrips`·`deletedSegments`, `tripSummaries()`는 받은 `FakeTripStore`의 여행과 이어 붙여 계산(Task 15도 이 가짜를 쓴다), `deleteTrip`은 그 여행의 구간·점도 지움; `FakePressureSource`; 하네스는 `NavigationControllerTest`처럼 가상 시간·`backgroundScope`·`FakeTripStore`·`RecordingVoiceOutput`·idle `NavigationController`, `clock = { testScheduler.currentTime }`, 위치는 `ScriptedLocationSource(fixes, speedup = 1.0)`이라 fix 시각 = 가상 시각)

```kotlin
@Test fun recordOnlyThreeKmAnnouncesStartAndEachKm()     // ① straight(3_010.0), SINGLE_DAY, 끝까지 재생 후 stopRecording(COMPLETE_TRIP)
                                                         //   "기록을 시작합니다" 1회, "킬로미터 이동. 평균 속도" 문장의 km == [1, 2, 3],
                                                         //   저장 구간 distanceM == 3_010.0 ±1.0 (스펙 "3km ±1%" 안), endedAt != null, trip COMPLETED
@Test fun twoSignalStopsPauseAndResumeTwice()            // ② straight(1_500.0, stops = [StopAt(500.0, 30), StopAt(1_000.0, 30)])
                                                         //   "일시정지되었습니다" 2회, "기록을 다시 시작합니다" 2회, movingTimeMs < elapsedMs,
                                                         //   distanceM == 1_470.0 ±1.0 (= 500 + (1_000−515) + (1_500−1_015): 정지마다 정지 지점→재개 위치 15m 제외, 재개 위치부터 새로 셈)
@Test fun pointsAreSavedEvery5sWithTotals()              // 정지하지 않고 23초 진행 → appendCalls 시각 == [5_000, 10_000, 15_000, 20_000], totals.distanceM 단조 증가
                                                         //   (stopRecording의 마지막 저장은 5초 배수가 아니므로 이 테스트에서는 정지하지 않는다)
@Test fun saveFailureWarnsOnceAndRetriesTheBufferedPoints()   // failAppend=true 15초 → saveWarning == MSG_SAVE_PROBLEM(경고 상태 변화 1번), false로 바꾼 뒤 다음 저장에 앞서 못 넣은 점 모두 포함
@Test fun startingClosesALeftoverOpenSegmentAtItsLastSaveTime() // 열린 구간(startedAt 1_000, elapsed 60_000) 있음 → startRecording 후 그 구간 endedAt == 61_000, 열린 구간은 새 것 1개
@Test fun interruptedSegmentResumesAsANewSegmentOfTheSameTrip() // ④ 컨트롤러 A(자식 Job)로 1km 기록·저장 후 Job 취소(정지 없이) → 같은 store의 컨트롤러 B: interrupted() == A의 구간;
                                                         //   startRecording(같은 tripId) → A 구간 닫힘, 새 구간 tripId 같음·endedAt null
@Test fun closeInterruptedEndsTheTrip()                  // SINGLE_DAY → COMPLETED, MULTI_DAY → ACTIVE 유지(lastActiveAt 갱신)
@Test fun stopTodayKeepsMultiDayTripActive()             // STOP_TODAY → trip ACTIVE, Finished
@Test fun dayKmContinuesAcrossSegments()                 // 같은 날 1.5km 구간 닫고 새 구간 1km → 새 구간에서 "2킬로미터 이동…" 1회만
@Test fun autoPauseSettingIsTheDefaultAndTheChipTogglesIt()   // autoPauseEnabled=false → Active.autoPause false; setAutoPause(true) → true
@Test fun voiceOffSpeaksNothing()                        // voiceEnabled=false, straight(1_100.0) 재생 → voice.spoken.isEmpty(), ui는 Active(distanceM == 1_100 ±1)
@Test fun trackKeepsPointsAtLeast10mApart()              // 1초 5m 주행 → track 인접 점 거리 ≥ 10.0
@Test fun gpsWeakAfter10sWithoutFixes()                  // 위치 멈춤 후 12초(tick 1초라 10~11초 사이에 켜지므로 여유) → Active.gpsWeak, distanceM 증가 없음
@Test fun locationFailureKeepsRecordingAndRetries()      // 테스트 안 LocationSource: 1번째 구독 21개(100m) 뒤 IOException, 2번째 구독은 이어서 105m부터
                                                         //   → 실패 후 ui Active 유지, 12초 뒤 gpsWeak, 그때 distanceM == 100 ±1; 재구독(구독 수 2) 뒤 gpsWeak false·거리 다시 증가
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ride.RideControllerTest'` → FAIL
- [ ] **Step 3: 구현** — 모든 공개 메서드는 scope의 단일 스레드에서 호출한다. 수집 Job들은 V1.0.0 `NavigationController.Run`처럼 한 묶음으로 취소한다.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest` → 전체 PASS
- [ ] **Step 5: Commit** — `git commit -m "app: ride controller records, saves every 5 s and recovers"`

---

### Task 11: 안내와 기록 묶기 (스펙 ③⑤의 JVM판)

**Files:**
- Modify: `app/.../nav/NavigationController.kt`, `app/.../ride/RideController.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ride/RideNavigationTest.kt`, `app/src/test/java/com/cowork/bikerecoder/nav/NavigationControllerTest.kt`

**Interfaces:**
- Consumes: Task 7 `KmAnnouncementSource`·`setKmSource`, Task 9 `SharedLocationSource`, Task 10 `RideController`
- Produces:
```kotlin
// NavigationController
var kmSource: KmAnnouncementSource?    // 지금 세션과 이후 만들어질 세션에 적용(메인 스레드)
```
- `RideController`가 `navigation.ui`를 자기 scope에서 수집한다(결정: 안내 쪽은 `kmSource` 하나만 바뀐다):
  - `Active(tripId)`를 처음 보고 `autoStartRecordingWithNavigation`이 켜져 있고 기록이 Idle이면 `startRecording(tripId)`(`Starting`이 아니라 `Active`에서 — 경로 계산이 실패한 시작에는 기록을 남기지 않는다).
  - `Finished(tripId, ARRIVED|COMPLETED|STOPPED_TODAY)`이고 그 여행을 기록 중이면 `stopRecording(SEGMENT_ONLY)`(여행 상태는 안내 쪽이 이미 바꿨다) → `Finished` 요약.
  - `Idle`(안내만 끝내기 = `navigation.close()`)이면 기록은 계속된다.
- 기록이 Active가 되면 `navigation.kmSource = object : KmAnnouncementSource { distanceM() = session.day.distanceM; phrase(km, remainingM, eta) = phrases.rideKmReportWithNav(km, session.day.avgSpeedMps, session.day.movingTimeMs, remainingM, eta) }`, 기록이 끝나면 `null`.
- 공유 위치 스트림이 마지막 위치를 다시 주므로(P4) 안내가 Active가 된 뒤 구독한 기록도 안내가 출발점으로 쓴 위치부터 받는다. 기록 중에 안내가 시작되면 안내 쪽도 직전 위치(1초 이내)를 출발 후보로 받는데, V1.0.0 출발점 규칙(정확도 30m 이내 첫 위치)에 그대로 맞는다.
- 안내 중 [기록 시작](수동)은 그 안내의 여행에 붙는다(Task 13 화면이 tripId를 넘긴다).
- 하네스: `ScriptedLocationSource(gpxFixture("scenario_follow.gpx"), speedup = 1.0)`를 `SharedLocationSource`로 감싸 `NavigationController(locationSource = { shared })`와 `RideController(location = shared)`에 같이 준다. 라우터 `AssetRouter(listOf(fixture("route_follow.geojson")))`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// RideNavigationTest
@Test fun guidanceAutoStartsRecordingWhenEnabled()        // nav start → ride.ui Active(같은 tripId), "기록을 시작합니다" 1회
@Test fun guidanceDoesNotStartRecordingWhenDisabled()
@Test fun combinedKmSentenceOncePerKmAndNoGuidanceKmReport()   // ③ 발화 중 "킬로미터 이동. 평균 속도"를 포함하고 "남은 거리"·"도착 예정"도 포함한 문장의 km == [1, 2];
                                                          //   기본 안내 문장("킬로미터 이동. 남은 거리")은 0개
@Test fun arrivalEndsGuidanceAndRecordingWithSummary()    // ⑤ nav Finished(ARRIVED), ride.ui Finished(segment.endedAt != null, distanceM == 2_370.0 ±15.0), trip COMPLETED,
                                                          //   (python3: 도착은 위치 #237(목적지 30.0m, 누적 2_369.9m) 또는 #238(2_379.9m); replay로 기록은 #0 또는 #1부터 → 2_359.9..2_379.9)
                                                          //   "목적지에 도착했습니다" 발화
@Test fun endingGuidanceOnlyKeepsRecording()              // 1.2km에서 navigation.close() → ride.ui Active 유지, 이후 km 안내는 "…이동 시간 {시간}입니다." 기록 문장
@Test fun stopTodayEndsRecordingAndKeepsTheTrip()         // MULTI_DAY, navigation.stopToday() → ride Finished, trip ACTIVE
@Test fun kmSourceIsClearedWhenRecordingStops()           // 기록만 SEGMENT_ONLY로 정지 → navigation.kmSource == null, 이후 기본 안내 km 문장
// NavigationControllerTest
@Test fun kmSourceAppliesToTheRunningAndNextSession()   // 가짜 소스(var d, 문장 "SRC $km")를 start 전에 설정 → d = 1_000.0 뒤 다음 위치에서 "SRC 1" 1회, 기본 kmReport 0개;
                                                       //   close() 후 다시 start → d = 2_000.0 → "SRC 2"; kmSource = null 후에는 기본 kmReport만
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ride.RideNavigationTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest` → 전체 PASS(`NavigationControllerTest` 기존 테스트 그대로)
- [ ] **Step 5: Commit** — `git commit -m "app: recording follows guidance; one combined km sentence"`

---

### Task 12: 주행 서비스·알림·AppContainer 연결

**Files:**
- Modify: `app/.../AppContainer.kt`, `app/.../nav/NavigationService.kt`, `app/.../nav/NavNotifications.kt`, `app/.../nav/NavigationController.kt`(container 생성자: `locationSource = { container.rideLocation }`), `app/.../ride/RideController.kt`(container 생성자)
- Test: `app/src/test/java/com/cowork/bikerecoder/nav/NavNotificationsTest.kt`

**Interfaces:**
- Consumes: Task 8 `RoomRideStore`, Task 9 `SharedLocationSource`·`BarometerSource`, Task 10/11 `RideController`
- Produces:
```kotlin
// AppContainer
val rideStore: RideStore                         // RoomRideStore(db)
val barometer: PressureSource?                   // BarometerSource(appContext).takeIf { it.available }
val rideLocation: LocationSource                 // SharedLocationSource({ locationSourceFactory() }, rideScope)
val ride: RideController by lazy                 // 처음 쓸 때 생성(navigation도 생성)
val rideUi: Flow<RideUiState>                    // ride 생성 전엔 Idle
val rideState: RideUiState                       // 생성 없이 현재 값
// (기존 navigationScope → rideScope로 이름 변경, 같은 Main.immediate 단일 스레드)
// NavNotifications
data class RideNotice(val title: String, val text: String)
fun rideNotice(nav: NavUiState, ride: RideUiState): RideNotice?      // null = 진행 중인 것 없음 → 서비스 종료
data class InterruptedNotice(val resumeTripId: Long?)
fun interruptedNotice(activeTrip: Trip?, remainingStops: Int, openSegments: Int): InterruptedNotice?
fun showInterrupted(context: Context, notice: InterruptedNotice)     // 제목 "주행이 중단되었습니다. 눌러서 재개"
```
- `rideNotice`: 안내 Starting/Active + 기록 Active → ("길 안내 중 · 기록 중", 기존 `contentText(nav)`) / 안내만 → ("길 안내 중", 기존 `contentText`) / 기록 Starting → ("주행 기록 중", "기록을 준비하는 중…") / 기록 Active·Recording → ("주행 기록 중", "{RideFormat.distance} · 이동 {RideFormat.duration}") / 기록 Active·Paused → ("일시정지됨", 같은 text) / 그 외 null.
- `interruptedNotice`: 여행·열린 구간이 모두 없으면 null; 여행이 ACTIVE이고 남은 경유지 > 0이면 `resumeTripId = trip.id`(V1.0.0처럼 탭하면 안내 재개 → 안내 자동 기록이 남은 구간을 닫고 새 구간 시작), 아니면 `resumeTripId = null`(탭하면 지도 메인 → Task 13의 "기록이 중단되었습니다" 대화상자).
- `NavigationService`: `controller.ui` 대신 `combine(container.navigationUi, container.rideUi, ::rideNotice).distinctUntilChanged()`를 `collectLatest` — non-null이면 알림 갱신, null이면 기존처럼 음성 마무리(최대 5초) 후 종료. `onStartCommand`에서 `container.ride`를 먼저 만든다. 채널 이름 "길 안내" → "주행"(id `navigation` 유지). `intent == null`이면 `activeTrip`·`remainingStops`·`openSegments`로 `interruptedNotice`를 만들어 보이고 종료. 서비스 시작은 여전히 화면이 보일 때 `NavigationService.start(context)` → 바로 이어서 `begin`/`startRecording`(둘 다 동기적으로 Starting을 만든다).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `recording only notice`()       // 기록 Active(distance 3_010, moving 600_000) → RideNotice("주행 기록 중", "3.01km · 이동 10분")
@Test fun `paused notice`()               // → 제목 "일시정지됨"
@Test fun `guidance and recording notice`()   // 제목 "길 안내 중 · 기록 중", text == contentText(nav)
@Test fun `nothing running gives null`()  // Idle/Idle, Finished/Finished → null
@Test fun `interrupted notice resumes guidance only with stops left`()  // (ACTIVE, 2, 1) → resumeTripId == id; (ACTIVE, 0, 1) → InterruptedNotice(null); (null, 0, 1) → InterruptedNotice(null); (null, 0, 0) → null
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.nav.NavNotificationsTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL; 기기가 있으면 기존 `NavigationScenarioTest` 6/6 다시 PASS(공유 위치 스트림 회귀 확인)
- [ ] **Step 5: Commit** — `git commit -m "app: one ride foreground service for guidance and recording"`

---

### Task 13: 지도 메인 진입과 기록 화면

**Files:**
- Create: `app/.../ui/record/RecordViewModel.kt`, `RecordScreen.kt`, `RecordChoices.kt`, `RideNumbers.kt`, `SegmentSummary.kt`
- Modify: `app/.../ui/main/MainViewModel.kt`, `app/.../ui/main/MainScreen.kt`, `app/.../ui/trip/TripStartViewModel.kt`, `app/.../map/BikeMap.kt`, `app/.../ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/main/MainViewModelTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/trip/TripStartViewModelTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/record/RecordViewModelTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/record/RecordChoicesTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/record/RideLinesTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/InitialRoutesTest.kt`, `app/src/test/java/com/cowork/bikerecoder/map/BikeMapGeoJsonTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/record/RecordScreenTest.kt`

**Interfaces:**
- Consumes: Task 5 `RideFormat`, Task 10/11 `RideController`·`RideUiState`·`RecordingEnd`, Task 12 `AppContainer.ride/rideUi/rideState`
- Produces:
```kotlin
// RecordChoices.kt
const val RECORD_END_QUESTION = "기록을 끝낼까요?"
const val RECORDING_INTERRUPTED = "기록이 중단되었습니다"
data class RecordEndChoice(val label: String, val end: RecordingEnd)
fun recordEndChoices(type: TripType, navigating: Boolean): List<RecordEndChoice>
    // navigating → [기록 끝내기/SEGMENT_ONLY]; SINGLE_DAY → [끝내기/COMPLETE_TRIP]; MULTI_DAY → [오늘은 여기까지/STOP_TODAY, 여행 완료/COMPLETE_TRIP]
fun pauseBandText(kind: PauseKind): String      // "일시정지됨 (자동)" / "일시정지됨 (수동)"
fun autoPauseChipText(enabled: Boolean): String // "자동 일시정지 ON" / "자동 일시정지 OFF"
// RideNumbers.kt
@Composable fun RideNumbersPage(stats: RideStats, dimmed: Boolean, modifier: Modifier = Modifier)  // 현재 속도(크게)·거리·이동 시간·평균·최고·고도·누적 ↑/↓
fun rideSummaryLine(stats: RideStats): String     // 안내 화면용(스펙 §3.3): "{RideFormat.distance} · {RideFormat.clock(이동)} · 평균 {speedKmh}"
fun rideMapSummaryLine(stats: RideStats): String  // 기록 화면 지도 쪽 하단(스펙 §3.2, 현재 속도 포함): "{speedKmh(current)}km/h · {distance} · {clock(이동)} · 평균 {speedKmh(avg)}"
// SegmentSummary.kt
@Composable fun SegmentSummaryContent(segment: RideSegment, map: @Composable (Modifier) -> Unit, onConfirm: () -> Unit)  // 거리·이동 시간·평균/최고·누적 상승·지도
// RecordScreen.kt
object RecordTags { PAGER, CHIP, PAUSE, RESUME, STOP, BAND, NUMBERS_PAGE, SUMMARY /* 지도 쪽 하단 요약 줄 */ }
@Composable fun RecordStatusContent(state: RideUiState, onClose: () -> Unit)   // Starting/Failed 화면(아래 규칙)
@Composable fun RecordContent(state: RideUiState.Active, navigating: Boolean, onPause, onResume, onToggleAutoPause: (Boolean) -> Unit,
                              onEnd: (RecordingEnd) -> Unit, map: @Composable (Modifier) -> Unit)
// RecordViewModel
class RecordViewModel(ride: RideController, navigation: StateFlow<NavUiState>, keepScreenOn: Flow<Boolean>, savedState: SavedStateHandle) {
    val ui: StateFlow<RideUiState>; val navigating: StateFlow<Boolean>
    val hasPendingStart: Boolean; fun takePendingStart(): Long?      // ARG_TRIP, 항목당 한 번(재생성에도)
    fun start(tripId: Long); fun pause(); fun resume(); fun setAutoPause(enabled: Boolean)
    fun onEndChoice(end: RecordingEnd); fun dismissSummary()
    companion object { const val ARG_TRIP = "trip" }
}
// MainViewModel 생성자 뒤에 기본값 있는 인자 추가
ride: Flow<RideUiState> = flowOf(RideUiState.Idle), interrupted: suspend () -> RideSegment? = { null }, closeInterrupted: suspend () -> Unit = {}
data class RidingBanner(val text: String, val target: RideTarget); enum class RideTarget { NAVIGATE, RECORD }
data class InterruptedRide(val segmentId: Long, val tripId: Long)
// MainUiState: 기존 guiding 유지 + riding: RidingBanner?, interrupted: InterruptedRide? (visibleBanner는 riding != null일 때도 숨김)
// MainViewModel: fun closeInterruptedRide(); fun dismissInterrupted()
// TripStartViewModel
fun onRecordPressed()      // 기록 전용 진입: StartPlan(defaultProfile(), emptyList())와 recordOnly = true를 기억. 생성자에 defaultProfile: suspend () -> RouteProfile = { RouteProfile.CYCLEWAY_FIRST }
// 기존 onStartPressed의 `plan.stops.isEmpty()` 조기 반환을 거치지 않는 별도 진입이다(onStartPressed를 빈 목록으로 부르면 아무 일도 안 한다).
// continueTrip(): recordOnly면 changeProfile·changeStops(replaceStops)를 부르지 않고 바로 Ready(trip.id) — 여행의 경유지·성향을 지우지 않는다.
// chooseType(): recordOnly면 startTrip(type, defaultProfile(), emptyList()). consumeReady()/dismiss()에서 recordOnly를 지운다.
// BikeMap
data class MapOverlay(val route: Route?, val stops: List<Stop>, val user: LocationFix?, val track: List<GeoPoint> = emptyList())
internal fun trackGeoJson(points: List<GeoPoint>): String   // 2점 미만이면 빈 FeatureCollection
// BikeNavHost
Routes.RECORD = "record"; Routes.RECORD_PATTERN = "record?trip={trip}"(navArgument `trip` LongType, 기본 -1)
fun Routes.record(tripId: Long? = null): String   // "record?trip=${tripId ?: -1}"
fun initialRoutes(onboardingDone: Boolean, guidance: NavUiState, ride: RideUiState = RideUiState.Idle): List<String>
```
- 주행 배너(스펙 §3.1, V1.0.0 "안내 중 [보기]" 대체): 기록 Active면 "● 주행 중 · {RideFormat.distance} · {RideFormat.clock(이동)}", 안내만이면 "● 주행 중 · 안내 중"; [보기] 대상은 안내 중이면 NAVIGATE, 아니면 RECORD.
- [● 기록 시작](오른쪽 아래, 기존 [내 위치]는 그 위로): 위치 권한(`ACCESS_FINE_LOCATION`) 없으면 `RequestPermission` 런처, 거부되면 "위치 권한이 있어야 기록할 수 있습니다" [설정 열기]/[취소]. 안내 중이면 프롬프트 없이 `record(안내 tripId)`, 아니면 `tripStart.onRecordPressed()` → 기존 `TripStartDialogs` → `Ready(tripId)` → `record(tripId)`. 기록 중에는 버튼을 숨긴다.
- 기록용 여행(결정): 새 여행은 경유지 없이 `startTrip(type, 기본 성향, emptyList())`; [이어서]는 여행의 경유지·성향을 바꾸지 않는다. 경유지 없는 여러 날 여행 배너의 이름은 "주행 기록".
- 복구(스펙 §4.6): 지도 메인이 열릴 때 기록이 Idle이고 `interrupted()`가 있으면 "기록이 중단되었습니다" [이어서 기록] → `record(tripId)`(서비스 시작 + `startRecording`이 남은 구간을 닫고 같은 여행에 새 구간) / [종료] → `closeInterrupted()`.
- 기록 화면이 `Active`가 아닐 때: `Starting` → 가운데 진행 표시 + "기록을 준비하는 중…"; `Failed` → `message`("기록을 시작하지 못했습니다") + [닫기] → `dismissSummary()` 후 지도 메인; `Idle`이고 시작 대기도 없으면 지도 메인으로 닫는다(V1.0.0 안내 화면과 같은 방식).
- 기록 화면(스펙 §3.2): `HorizontalPager` 2쪽 — 지도(지나온 경로 `track` + 현재 위치, 하단 `rideMapSummaryLine`, 태그 SUMMARY) / `RideNumbersPage`. 지도 위 끌기는 지도가 받으므로 페이지 넘기기는 하단 요약·상단 띠에서 되고, 페이지 점을 눌러도 넘어간다. 상단 칩 `autoPauseChipText`, 일시정지 중 띠 `pauseBandText` + 숫자 흐리게, GPS 약함 띠 "GPS 신호 약함", `saveWarning` 스낵바 1회. 하단 [일시정지]/[재개], [정지] → `RECORD_END_QUESTION` + `recordEndChoices` + [취소]. `Finished`면 `SegmentSummaryContent` → [확인] → `dismissSummary()` 후 안내 중이면 안내 화면, 아니면 지도 메인. `keepScreenOn` 설정을 기록 중에도 적용. 시작은 V1.0.0 안내 화면처럼 `LifecycleResumeEffect`에서 `takePendingStart()` → `NavigationService.start(context)` → `start(tripId)`.
- `initialRoutes`: 안내 Starting/Active → [MAIN, navigate()] (기존), 아니면 기록 Starting/Active → [MAIN, record()], 아니면 [MAIN]. 알림 탭(`ShowNavigation`)도 같은 규칙으로 안내/기록 화면을 고른다.
- BikeMap: 소스 `bike-track` + 선 레이어(색 `#E53935`, 두께 5) — 사용자 점 레이어 아래.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// RecordChoicesTest
@Test fun `end choices`()                  // (SINGLE_DAY,false) → [("끝내기",COMPLETE_TRIP)]; (MULTI_DAY,false) → [("오늘은 여기까지",STOP_TODAY),("여행 완료",COMPLETE_TRIP)]; (*,true) → [("기록 끝내기",SEGMENT_ONLY)]
@Test fun `band and chip texts`()          // "일시정지됨 (자동)", "일시정지됨 (수동)", "자동 일시정지 ON", "자동 일시정지 OFF"
// MainViewModelTest
@Test fun ridingBannerShowsDistanceAndMovingTime()   // 기록 Active(3_010m, 600_000ms) → "● 주행 중 · 3.01km · 0:10:00", target RECORD
@Test fun guidanceOnlyBanner()                       // 안내 Active, 기록 Idle → "● 주행 중 · 안내 중", target NAVIGATE
@Test fun interruptedRideIsOfferedOnlyWhenIdle()     // interrupted() 결과 → state.interrupted; closeInterruptedRide → closeInterrupted 호출, null로
// TripStartViewModelTest
@Test fun recordNewTripHasNoStops()                  // onRecordPressed → AskType → chooseType(SINGLE_DAY) → Ready, stops 비어 있음
@Test fun recordContinueKeepsTheTripsStopsAndProfile()   // MULTI_DAY 여행(경유지 [남산, 여의도], BALANCED), 기본 성향 CYCLEWAY_FIRST → onRecordPressed → AskContinue → continueTrip
                                                       //   → Ready(같은 id), stops 이름 == [남산, 여의도], profile == BALANCED, offline.deleted 비어 있음
@Test fun planStartWithEmptyStopsStillDoesNothing()     // onStartPressed(StartPlan(…, emptyList())) → state == Idle (V1.0.0 동작 유지)
// RecordViewModelTest (Task 10 하네스 재사용)
@Test fun pendingStartIsTakenOnce()                     // SavedStateHandle(mapOf("trip" to 5L)) → takePendingStart() == 5L, 다시 → null, hasPendingStart == false
@Test fun endChoiceStopsTheRecording()               // onEndChoice(COMPLETE_TRIP) → ui Finished, trip COMPLETED
// InitialRoutesTest
@Test fun runningRecordingOpensTheRecordScreen()     // (true, Idle, Active…) → [MAIN, Routes.record()]
// BikeMapGeoJsonTest
@Test fun `track geojson`()                          // 0·1점 → 빈 FeatureCollection, 2점 → LineString 좌표 [lon,lat] 순서
// RideLinesTest (JVM)
@Test fun `summary lines`()   // stats(current 5.0, distance 3_010, moving 600_000, avg 5.0) → rideMapSummaryLine == "18.0km/h · 3.01km · 0:10:00 · 평균 18.0",
                              //   rideSummaryLine == "3.01km · 0:10:00 · 평균 18.0"
// RecordScreenTest (기기, RecordContent + 지도 슬롯은 끌기를 먹는 Box(`Modifier.pointerInput { detectDragGestures { _, _ -> } }`) — 실제 지도처럼)
@Test fun swipingTheSummaryShowsTheNumbersPage()      // SUMMARY 노드 swipeLeft → "최고" 표시
@Test fun draggingTheMapKeepsTheMapPage()             // 지도 슬롯 위 swipeLeft → "최고" 없음, SUMMARY 그대로
@Test fun startingAndFailedStates()                   // Starting → "기록을 준비하는 중…"; Failed → "기록을 시작하지 못했습니다" + [닫기] 클릭 → onClose
@Test fun autoPauseChipToggles()                     // CHIP 클릭 → onToggleAutoPause(false)
@Test fun stopAsksForConfirmation()                  // STOP 클릭 → "기록을 끝낼까요?" + "끝내기"; "끝내기" → onEnd(COMPLETE_TRIP)
@Test fun pausedShowsTheBand()                       // Paused(AUTO) → BAND "일시정지됨 (자동)", RESUME 표시
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.*' --tests 'com.cowork.bikerecoder.map.BikeMapGeoJsonTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.record.RecordScreenTest` → PASS(또는 기기 검증 대기). 휴대폰이 있으면 앱에서 [기록 시작] → 기록 화면 → [정지] → 요약까지 한 번 눈으로 확인하고 스크린샷을 남긴다
- [ ] **Step 5: Commit** — `git commit -m "app: start recording from the map; two-page record screen and summary"`

---

### Task 14: 안내 화면의 기록

**Files:**
- Modify: `app/.../ui/navigate/NavigateScreen.kt`, `app/.../ui/navigate/NavigateViewModel.kt`, `app/.../ui/trip/TripChoices.kt`, `app/.../ui/trip/TripDialogs.kt`, `app/.../ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/trip/TripChoicesTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/navigate/NavigateViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/trip/EndNavigationDialogTest.kt`

**Interfaces:**
- Consumes: Task 13 `RideNumbersPage`·`rideSummaryLine`·`SegmentSummaryContent`·`Routes.record`, Task 10 `RideUiState`
- Produces:
```kotlin
enum class EndAction { STOP_TODAY, COMPLETE_TRIP, END_GUIDANCE_ONLY }
fun endChoices(type: TripType, recording: Boolean = false): List<EndChoice>
    // recording=false → V1.0.0 그대로
    // SINGLE_DAY → [("안내만 끝내기 (기록 계속)", END_GUIDANCE_ONLY), ("모두 끝내기", COMPLETE_TRIP)]
    // MULTI_DAY  → [("안내만 끝내기", END_GUIDANCE_ONLY), ("오늘은 여기까지", STOP_TODAY), ("여행 완료", COMPLETE_TRIP)]
@Composable fun EndNavigationDialog(type: TripType, recording: Boolean, onChoose: (EndAction) -> Unit, onDismiss: () -> Unit)
// NavigateViewModel 생성자 뒤에 추가
rideUi: StateFlow<RideUiState> = MutableStateFlow(RideUiState.Idle), dismissRideSummary: () -> Unit = {}
// onEndChoice(END_GUIDANCE_ONLY) → controller.close() (기록은 계속)
// NavigateScreen
fun NavigateScreen(viewModel, tileSource, onClose: () -> Unit, onGuidanceEndedWhileRecording: () -> Unit)
```
- 안내 중 기록 Active면: 회전 카드 아래 한 줄 `rideSummaryLine(stats)`, 화면을 `HorizontalPager`로 감싸 2쪽 = 기존 안내 지도 / `RideNumbersPage`(넘기기는 기록 줄·하단 패널에서, 점 표시 탭으로도). 기록이 꺼져 있으면 V1.0.0 화면 그대로(페이저 없음).
- 끝난 화면: 안내 `Finished`일 때 `rideUi`가 `Finished`면 `SegmentSummaryContent`를 "여행을 완료했습니다"와 [여러 날로 바꾸기] 위에 보인다. `STOPPED_TODAY`는 요약이 있으면 자동으로 닫지 않고 요약 + [확인]. [확인] → `dismissRideSummary()` + `close()`.
- `EndAction`에 값이 늘었으므로 `when (action)`이 있는 곳(`NavigateViewModel.onEndChoice` 등)에 새 가지를 넣는다.
- `END_GUIDANCE_ONLY` 뒤 안내가 Idle이 되면 `onGuidanceEndedWhileRecording()` → BikeNavHost가 `record()`로 이동(`popUpTo(MAIN)`); 기록이 없으면 기존 `onClose`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// TripChoicesTest
@Test fun `end choices while recording`()     // 위 두 목록 그대로(라벨·순서), recording=false는 V1.0.0 목록 그대로
// NavigateViewModelTest
@Test fun endGuidanceOnlyKeepsTheTripAndGoesIdle()    // 안내 Active에서 onEndChoice(END_GUIDANCE_ONLY) → ui Idle, trip ACTIVE, COMPLETED 아님
@Test fun recordingFlagFollowsRideUi()              // rideUi Idle → recording == false; Active(…) → true; Finished(…) → false
// EndNavigationDialogTest (기기)
@Test fun recordingChoicesAreShown()          // SINGLE_DAY·recording=true → "안내만 끝내기 (기록 계속)", "모두 끝내기", "취소"
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.trip.TripChoicesTest' --tests 'com.cowork.bikerecoder.ui.navigate.NavigateViewModelTest'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.trip.EndNavigationDialogTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: recording on the guidance screen and guidance-only end"`

---

### Task 15: 기록 목록·여행 상세·구간 상세·삭제

**Files:**
- Create: `core/.../recording/RideCharts.kt`, `app/.../ui/history/HistoryFormat.kt`, `HistoryViewModel.kt`, `HistoryScreen.kt`, `TripDetailViewModel.kt`, `TripDetailScreen.kt`, `SegmentDetailViewModel.kt`, `SegmentDetailScreen.kt`, `LineChart.kt`
- Modify: `app/.../ui/main/MainScreen.kt`(⚙ → ☰ 메뉴 [기록] [설정]), `app/.../ui/BikeNavHost.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/recording/RideChartsTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/history/HistoryFormatTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/history/HistoryViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/history/HistoryScreenTest.kt`

**Interfaces:**
- Consumes: Task 8 `RideStore`·`RideSegment`·`TripRideSummary`, Task 5 `RideFormat`, Task 13 `MapOverlay.track`
- Produces:
```kotlin
data class ChartPoint(val distanceKm: Double, val value: Double)
object RideCharts {
    /** 가로축 = 첫 점부터의 누적 거리(km). 시간 간격 > 10초인 쌍(일시정지·GPS 끊김)은 거리를 더하지 않는다.
     *  점이 maxPoints보다 많으면 첫 점과 마지막 점은 그대로 두고, 그 사이 점들을 순서대로 maxPoints − 2개 묶음으로 나눠 묶음마다 x·값을 평균한다(결과 정확히 maxPoints개). */
    fun altitude(points: List<TrackPoint>, maxPoints: Int = 300): List<ChartPoint>    // altitudeM null인 점은 값 목록에서 빠지되 x 누적에는 쓰인다
    fun speedKmh(points: List<TrackPoint>, maxPoints: Int = 300): List<ChartPoint>    // speedMps ?: 바로 앞 점과의 구간 속도; 둘 다 없는 첫 점은 빠진다
}
fun tripCardText(s: TripRideSummary): String   // "{RideFormat.date(firstDate)} · {유형} · {RideFormat.distance} · {RideFormat.duration}"
                                               //   유형은 trip.type으로 정한다: SINGLE_DAY → "당일", MULTI_DAY → "여러 날({dayCount}일)" (하루만 기록한 여러 날 여행은 "여러 날(1일)")
const val MSG_ACTIVE_TRIP_NOT_DELETABLE = "진행 중인 여행은 삭제할 수 없습니다"
const val MSG_OPEN_SEGMENT_NOT_DELETABLE = "진행 중인 구간은 삭제할 수 없습니다"
sealed interface HistoryDialog {
    data class Message(val text: String) : HistoryDialog          // 삭제 불가 안내
    data class ConfirmDelete(val id: Long) : HistoryDialog         // 여행 또는 구간 id
}
class HistoryViewModel(rideStore: RideStore) { val trips: StateFlow<List<TripRideSummary>>; val dialog: StateFlow<HistoryDialog?>
    fun requestDelete(tripId: Long); fun confirmDelete(); fun dismiss() }
class TripDetailViewModel(rideStore: RideStore, tripId: Long) {  // 날짜별 구간 목록 + 여행 합계
    val segmentsByDate: StateFlow<Map<LocalDate, List<RideSegment>>>; val total: StateFlow<SegmentTotals>; val dialog: StateFlow<HistoryDialog?>
    fun requestDeleteSegment(segmentId: Long); fun confirmDelete(); fun dismiss() }
class SegmentDetailViewModel(rideStore: RideStore, segmentId: Long)  // 구간, track, altitude/speed ChartPoint
@Composable fun LineChart(points: List<ChartPoint>, unit: String, modifier: Modifier)   // Canvas, 가로축 거리(km), 세로축 최소/최대 표기
// 상태 없는 화면 본문(기기 테스트가 가짜 데이터로 그린다; *Screen은 ViewModel을 이것들에 잇기만 한다)
@Composable fun HistoryContent(trips: List<TripRideSummary>, dialog: HistoryDialog?, onOpenTrip: (Long) -> Unit,
                               onRequestDelete: (Long) -> Unit, onConfirmDelete: () -> Unit, onDismiss: () -> Unit, onBack: () -> Unit)
@Composable fun TripDetailContent(segmentsByDate: Map<LocalDate, List<RideSegment>>, total: SegmentTotals, dialog: HistoryDialog?,
                                  onOpenSegment: (Long) -> Unit, onRequestDelete: (Long) -> Unit, onConfirmDelete: () -> Unit, onDismiss: () -> Unit, onBack: () -> Unit)
@Composable fun SegmentDetailContent(segment: RideSegment, altitude: List<ChartPoint>, speed: List<ChartPoint>, map: @Composable (Modifier) -> Unit, onBack: () -> Unit)
Routes.HISTORY = "history"; Routes.TRIP_DETAIL = "history/trip/{tripId}"; Routes.SEGMENT_DETAIL = "history/segment/{segmentId}"
```
- 삭제 규칙(결정 — 스펙 "진행 중인 구간·여행"): 여행은 `status == ACTIVE`면 삭제 불가, 구간은 `endedAt == null`이면 삭제 불가(메시지 위 상수). 가능하면 확인 대화상자 "이 여행의 기록을 모두 삭제할까요?" / "이 구간을 삭제할까요?" [삭제]/[취소] → `deleteTrip`/`deleteSegment` 후 목록 새로 읽기.
- 여행 상세: 날짜(`RideFormat.date`)별 머리글 아래 구간 행 "{시작 시각 HH:mm} · {거리} · {이동 시간}", 맨 위 여행 합계. 구간 상세: 지도(track + 출발/도착 표시는 `stops`에 `Stop` 2개), 수치(거리, 이동 시간, 경과 시간, 평균·최고, 누적 상승·하강, 최고·최저 고도), 고도 그래프·속도 그래프.
- 지도 메인 오른쪽 위 ☰ 메뉴: [기록] → HISTORY, [설정] → SETTINGS.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// RideChartsTest (RecordingFixtures)
@Test fun `x skips gaps over 10 s`()            // 0..100m(1Hz), 60초 뒤 200m부터 100m → 마지막 distanceKm == 0.2 ±0.001
@Test fun `downsampled to at most maxPoints`()   // 1Hz 5m 간격 1_000점 → size == 300, 첫 점 == (0.0, 원래 값), 마지막 x == 4.995 ±1e-6
@Test fun `altitude skips nulls`()              // 0m 고도 100, 5m null, 10m 고도 110 → [(0.0, 100.0), (0.010, 110.0)] (±1e-9)
@Test fun `speed falls back to pair speed`()     // 0m·5m(1초 뒤) 모두 speedMps null → [(0.005, 18.0)] (첫 점은 앞 점이 없어 빠짐)
// HistoryFormatTest
@Test fun `card text`()   // SINGLE_DAY, 2026-10-12, 32_100m, 6_300_000ms → "2026년 10월 12일 · 당일 · 32.10km · 1시간 45분";
                          //   같은 값의 MULTI_DAY dayCount 3 → "2026년 10월 12일 · 여러 날(3일) · 32.10km · 1시간 45분"; MULTI_DAY dayCount 1 → "… · 여러 날(1일) · …"
// HistoryViewModelTest (FakeRideStore)
@Test fun activeTripCannotBeDeleted()            // dialog == Message(MSG_ACTIVE_TRIP_NOT_DELETABLE), deleteTrip 호출 없음
@Test fun completedTripIsDeletedAfterConfirm()   // COMPLETED 여행 requestDelete(id) → dialog == ConfirmDelete(id); confirmDelete() → FakeRideStore.deletedTrips == [id], trips에 id 없음, dialog == null
@Test fun openSegmentCannotBeDeleted()           // TripDetailViewModel.requestDeleteSegment(열린 구간) → dialog == Message(MSG_OPEN_SEGMENT_NOT_DELETABLE), deletedSegments 비어 있음
// HistoryScreenTest (기기, 상태 없는 HistoryContent/TripDetailContent에 가짜 데이터)
@Test fun listOpensTripThenSegment()             // 카드 클릭 → onOpenTrip(id); 구간 행 클릭 → onOpenSegment(id)
@Test fun longPressAsksBeforeDeleting()          // 카드 길게 누르기 → onRequestDelete(id); 확인 대화상자 [삭제] → onConfirmDelete
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.recording.RideChartsTest' :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.history.*'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.history.HistoryScreenTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: ride history, trip and segment detail with charts, delete"`

---

### Task 16: 휴대폰 GPX 시나리오 ①–⑤, 실주행 체크리스트, 문서

**Files:**
- Create: `app/src/androidTest/java/com/cowork/bikerecoder/ride/RideScenarioTest.kt`, `docs/verification/recording-field-test.md`
- Modify: `app/src/sharedTest/java/com/cowork/bikerecoder/nav/ScenarioDoubles.kt`(`NavigationScenarioTest`의 private `Replay`·`NoOffline`과 asset/GPX 읽기 도우미를 여기로 옮겨 공개 — 두 기기 테스트가 함께 쓰고 복사하지 않는다), `app/src/androidTest/java/com/cowork/bikerecoder/nav/NavigationScenarioTest.kt`(옮긴 것을 쓰도록), `CHANGELOG.md`, `README.md`

**Interfaces:**
- Consumes: Task 10 `RideFixtures`, `RideController`; Task 8 `RoomRideStore`; sharedTest `ScenarioDoubles.kt`의 `AssetRouter`·`RecordingVoiceOutput`·`Replay`·`NoOffline`(아래에서 옮김)
- 기기 시나리오 구성: in-memory Room, 컨트롤러 둘 다 메인 스레드 scope, `GpxLocationSource(fixes, speedup = 20.0)`를 `SharedLocationSource`로 감쌈, `pressure = null`, 설정 `flowOf(AppSettings())`(자동 기록·자동 일시정지 켜짐), `saveIntervalMs = 250`. 경과 시간이 재생 속도와 맞도록 `clock`은 테스트 안의 `ReplayClock(speedup = 20.0)`(시작 시각 `t0 = System.currentTimeMillis()` + `SystemClock.elapsedRealtime()` 경과 × 20). `RideFixtures.straight(…, startMillis = t0)`로 위치 시각도 같은 `t0`에서 시작한다(GPX 파일 시나리오 ③⑤는 파일 시각 그대로 — 이동 시간은 위치 시각 차이만 쓰므로 무관).

- [ ] **Step 1: 시나리오 테스트 작성** (동작은 Task 10–12에서 이미 구현됨 — 이 Task는 실기기 재생으로 확인한다)

```kotlin
@Test fun recordOnlyThreeKm()                       // ① straight(3_010.0): "기록을 시작합니다" 1회, 기록 km 문장 3회, 저장 distanceM == 3_010.0 ±1.0 (스펙 3km ±1% 안)
@Test fun twoSignalStops()                          // ② straight(1_500.0, stops = [StopAt(500.0, 30), StopAt(1_000.0, 30)]): 일시정지·재개 문구 각 2회, 저장 movingTimeMs < elapsedMs, distanceM == 1_470.0 ±1.0
@Test fun guidanceWithRecordingSpeaksOneSentencePerKm()   // ③ scenario_follow.gpx + route_follow.geojson: 합친 문장 km == [1, 2], 기본 안내 km 문장 0개
@Test fun killedRecordingResumesInANewSegment()     // ④ scope A 취소(정지 없이) → 같은 DB의 컨트롤러 B: interrupted() != null → startRecording(같은 여행) → 구간 2개, 앞 구간 endedAt != null
@Test fun arrivalEndsBothWithSummary()              // ⑤ 안내 Finished(ARRIVED), ride Finished, 저장 구간 endedAt != null·distanceM == 2_370.0 ±15.0(Task 11과 같은 근거), trip COMPLETED
```
(⑥ 마이그레이션은 Task 8 `RideDatabaseMigrationTest`.)

- [ ] **Step 2: 컴파일 확인** — `./gradlew :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL
- [ ] **Step 3: 기기 실행** — `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.bikerecoder.ride.RideScenarioTest` → 5/5 PASS. 실패하면 컨트롤러를 고치고(기대값은 스펙 수치이므로 바꾸지 않는다) 그 동작의 JVM 테스트를 먼저 추가해 실패를 재현한다. 휴대폰이 없으면 "기기 검증 대기"로 남기고 문서에 적는다
- [ ] **Step 4: 문서**
  - `docs/verification/recording-field-test.md`: `navigation-field-test.md`와 같은 형식 — "자동 검증"(명령, 모음별 클래스·테스트·실패 수 표: `:core:test`, `:app:testDebugUnitTest`, 기기 전체), "기기 검증 대기" 목록(있으면), 스펙 §5.3 체크리스트 10항목을 그대로 표로(항목·합격 기준·결과·메모 빈칸): ① 기록만 30분 — 거리 삼성 헬스(Watch7) 대비 ±3% ② 신호 많은 도심 — 신호마다 일시정지·재개 안내, 이동 시간 실제와 일치 ③ 수동 모드 — 자동 일시정지 없음, 버튼으로만 ④ 오르막 코스 — 누적 상승 삼성 헬스 대비 ±15% ⑤ 최고 속도 — 실제와 비슷, 70km/h 이상 터무니없는 값 없음 ⑥ 화면 꺼짐 1시간 — 1km 안내 모두 들림, 배터리 시간당 12% 이하(안내+기록) ⑦ 안내와 함께 — 1km 안내 한 문장 1회 ⑧ 여러 날 이어서 — 다음 날 같은 여행에 이어짐, 목록에서 날짜별 표시 ⑨ 구간 상세 — 지도·고도·속도 그래프가 실제 코스와 일치 ⑩ 강제 종료 — 재실행 후 [이어서 기록]으로 계속. 아래에 완료 기준(스펙 §5.4: 5.1·5.2 통과 + 5.3 전 항목 합격 → CHANGELOG V1.1.0 확정, 태그 `v1.1.0`)과 "지도 위 끌기는 지도가 받으므로 페이지 넘기기는 하단 요약에서" 확인 메모.
  - `CHANGELOG.md`: `[1.0.0]` 위에 `## [1.1.0] - 개발 중` / `### 추가 (예정)`(GPS 주행 기록·현재/평균/최고 속도·고도·누적 상승/하강, 자동/수동 일시정지, 기록 음성 안내(시작·일시정지·재개·1km, 안내 중에는 한 문장), 안내 시작 시 기록 자동 시작, 기록 화면 2쪽, 기록 목록·여행/구간 상세·고도/속도 그래프·삭제, 강제 종료 복구, DB v2) / `### 문서`(스펙, 이 계획, 검증 문서 경로).
  - `README.md`: "현재 버전" 줄 → **V1.1.0 (개발 중)**, 로드맵 표 V1.1.0 상태 → "개발 중", V1.0.0 상태 → "개발 완료 (실주행 검증 대기)".
- [ ] **Step 5: 전체 검증** — `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL; 기기 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest` → 전부 PASS(그동안 "기기 검증 대기"였던 것 포함). 결과 수치를 검증 문서 표에 적는다
- [ ] **Step 6: Commit** — `git commit -m "test: ride gpx scenarios; docs: recording field checklist, changelog 1.1.0"`
