# 자전거 내비게이션 (V1.0.0) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자전거도로를 우선하는 오프라인 경로 탐색과 한국어 음성 안내, 여러 날 여행 유지, 경로 주변 지도 오프라인 저장을 갖춘 안드로이드 내비게이션(V1.0.0)을 만든다.

**Architecture:** 순수 Kotlin `:core`(모델·진행 계산·음성 스케줄러·여행 상태·지도 범위 계산)와 순수 JVM `:routing-brouter`(BRouter 1.7.10 내장)에 로직을 몰아 JVM에서 테스트하고, `:app`은 Compose UI·MapLibre·Room·카카오 검색·포그라운드 서비스·TTS로 이들을 연결한다. 교체 지점은 `Router`, `TileSource`, `PlaceSearch`, `LocationSource`, `VoiceOutput`, `TripStore`, `OfflineMapController` 인터페이스다.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1(내장 Kotlin), Jetpack Compose(BOM 2026.09.00), MapLibre Android 13.6.1, BRouter 1.7.10(소스 포함), Room 2.8.5 + KSP 2.3.12, DataStore 1.2.1, OkHttp 5.5.0, kotlinx.serialization 1.11.0, coroutines 1.11.0, JTS 1.20.0, Play Services Location 21.4.0, JUnit 5(JVM) / AndroidX Test(기기).

**Spec:** `docs/superpowers/specs/2026-10-05-navigation-design.md` (실행자는 스펙과 이 계획을 함께 읽는다)

## Global Constraints

- 패키지 루트: `com.cowork.bikerecoder` (core: `com.cowork.bikerecoder.core`, routing: `com.cowork.bikerecoder.routing`)
- minSdk 30, targetSdk/compileSdk 37, Java/JVM 타깃 17 (BRouter 모듈만 `options.release = 11`)
- 버전: `versionName = "1.0.0"`, `versionCode = 10000` (= MAJOR×10000 + MINOR×100 + PATCH), debug 빌드는 `versionNameSuffix = "-dev"`, 화면 표기 `V1.0.0`
- MapLibre `org.maplibre.gl:android-sdk:13.6.1` 고정. 공식 Compose 래퍼는 사용하지 않고 `AndroidView`로 감싼다
- 지도 스타일 `https://tiles.openfreemap.org/styles/liberty`, 표기 문구 `OpenFreeMap © OpenMapTiles Data from OpenStreetMap`
- BRouter 데이터: `https://brouter.de/brouter/segments4/`, 필수 `E125_N35`, `E125_N30`, 선택 `E130_N35`, `E120_N35`
- 카카오: `https://dapi.kakao.com`, 헤더 `Authorization: KakaoAK {키}`, 키는 루트 `secrets.json`의 `kakaoRestApiKey` → `BuildConfig.KAKAO_REST_API_KEY`
- 수치(스펙 6장): 이탈 40m·8초·정확도 30m·재탐색 간격 15초·실패 재시도 30초 / 도착 30m·지나침 50m / ETA 창 15분·최소 5분·기본 15km/h·정지 2km/h 미만 / 회전 안내 200m·30m·25km/h 초과 시 300m·합치기 50m·1km 안내 연기 15초 / 지도 폭 2km·z10–14 회랑·z5–9 개요·15,000타일·150km·일반 캐시 100MB / 경로 계산 제한 60초 / GPS 없음 10초 / 방치 3일
- 의존성 주입 라이브러리 금지. `AppContainer` 수동 구성
- 모든 사용자 노출 문구는 한국어

## Review Focus

1. **정지 상태(신호 대기)의 위치 값** — 속도·방향이 null이거나 0인 위치가 계속 와도 ETA·음성 스케줄러가 0으로 나누거나 멈추지 않아야 한다. → Task 4 `eta falls back to default speed when only stationary fixes`.
2. **출발지 = 목적지인 순환 경로(한강 한 바퀴)** — 출발 직후 목적지 30m 이내라고 바로 도착 처리되면 안 된다. → Task 3 `loop route does not arrive at start`.
3. **재탐색 계산 중 계속 들어오는 위치** — 경로 계산(수 초) 동안 들어온 위치로 재탐색이 중복 실행되면 안 된다. → Task 9 `reroute is not started twice while in flight`.
4. **카카오 검색어 인코딩·빈 검색어** — 한글·`&`·공백 포함 검색어가 그대로 전달되고, 빈 검색어는 요청 없이 빈 결과여야 한다. → Task 14 `query is url encoded` / `blank query returns empty without request`.
5. **현재 위치와 너무 가까운 목적지** — 50m 이내 목적지로 안내를 시작하면 즉시 도착 처리되므로 계획 화면에서 막아야 한다. → Task 18 `destination within 50m shows error`.

---

## 파일 구조

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml      (Task 1 수정)
secrets.example.json                                                   (기존)

core/build.gradle.kts
core/src/main/kotlin/com/cowork/bikerecoder/core/
  model/Geo.kt                GeoPoint, LocationFix, GeoMath
  model/Route.kt              Route, Instruction, TurnType, RouteSummary, RouteProfile, Stop
  routing/Router.kt           Router, RouteRequest, RouteResult, RouteFailure
  navigation/RouteProgressTracker.kt
  navigation/OffRouteDetector.kt
  navigation/ArrivalDetector.kt
  navigation/EtaEstimator.kt  EtaEstimator, Odometer
  navigation/NavigationSession.kt  NavigationSession, NavState, NavEvent, NavUpdate
  voice/KoreanPhrases.kt
  voice/VoiceScheduler.kt     VoiceScheduler, Utterance, Priority
  trip/Trip.kt                Trip, TripType, TripStatus, TripStop, TripStore, OfflineMapController
  trip/TripManager.kt
  offline/CorridorPlanner.kt  CorridorPlanner, CorridorPlan, TileMath, BoundingBox
  gpx/GpxParser.kt
  format/SummaryFormatter.kt
core/src/test/kotlin/...      (각 파일별 *Test.kt)
core/src/test/resources/gpx/  테스트 GPX

third_party/brouter/{brouter-util,brouter-codec,brouter-expressions,brouter-mapaccess,brouter-core}/
  build.gradle.kts, src/main/java/** (v1.7.10 원본), LICENSE
routing-brouter/build.gradle.kts
routing-brouter/src/main/resources/brouter/profiles/{lookups.dat,cycleway-first.brf,balanced.brf,shortest.brf}
routing-brouter/src/main/kotlin/com/cowork/bikerecoder/routing/
  BRouterRouter.kt  BRouterGeoJsonParser.kt  CyclewayClassifier.kt  ProfileInstaller.kt
routing-brouter/src/test/kotlin/...  (단위 + @Tag("integration"))

app/src/main/java/com/cowork/bikerecoder/
  BikeApp.kt  AppContainer.kt  MainActivity.kt  AppVersion.kt
  data/        AppDatabase.kt  Entities.kt  Daos.kt  RoomTripStore.kt  SettingsRepository.kt
  search/      PlaceSearch.kt  KakaoLocalClient.kt
  offline/     SegmentRepository.kt  MapLibreOfflineController.kt  NetworkWaiter.kt
  map/         TileSource.kt  OpenFreeMapSource.kt  KoreanLabelStyle.kt  BikeMap.kt
  location/    LocationSource.kt  FusedLocationSource.kt  GpxLocationSource.kt
  tts/         VoiceOutput.kt  AndroidTtsVoiceOutput.kt
  nav/         NavigationController.kt  NavigationService.kt  NavNotifications.kt
  ui/          BikeNavHost.kt  onboarding/  main/  search/  plan/  navigate/  settings/  trip/
app/src/test/...           JVM 테스트 (BuildConfig, 카카오, 세그먼트, 스타일, 온보딩 단계)
app/src/androidTest/...    Room, Compose UI, GPX 시나리오, 오프라인 지역
app/src/androidTest/assets/  route_*.geojson, scenario_*.gpx
docs/verification/navigation-field-test.md
```

---

### Task 1: 빌드 구성, 모듈 골격, 버전, 비밀 키

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `.gitignore`(변경 없음 확인)
- Create: `core/build.gradle.kts`, `app/src/main/java/com/cowork/bikerecoder/AppVersion.kt`
- Delete: `app/src/main/res/layout/activity_main.xml`, `app/src/test/.../ExampleUnitTest.kt`, `app/src/androidTest/.../ExampleInstrumentedTest.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/AppVersionTest.kt`

**Interfaces:**
- Produces: 모듈 `:core`, `:app`; `AppVersion.display(versionName: String): String`; `BuildConfig.KAKAO_REST_API_KEY: String`

- [ ] **Step 1: 버전 카탈로그 작성**

`libs.versions.toml`에 다음 버전을 정의하고(기존 appcompat/constraintlayout/material은 제거) 라이브러리·플러그인 별칭을 만든다:
`kotlin = "2.4.20"`, `ksp = "2.3.12"`, `composeBom = "2026.09.00"`, `activityCompose = "1.13.0"`, `lifecycle = "2.11.0"`, `navigationCompose = "2.10.2"`, `room = "2.8.5"`, `datastore = "1.2.1"`, `okhttp = "5.5.0"`, `serialization = "1.11.0"`, `coroutines = "1.11.0"`, `maplibre = "13.6.1"`, `playLocation = "21.4.0"`, `jts = "1.20.0"`, `junit5 = "6.1.3"`, `androidxTest = "1.7.0"`, `androidxJunit = "1.3.0"`, `coreKtx`는 최신 안정판.
플러그인 별칭: `kotlin-jvm`, `kotlin-compose`(`org.jetbrains.kotlin.plugin.compose`), `kotlin-serialization`, `ksp`.

- [ ] **Step 2: 루트·settings 구성**

루트 `build.gradle.kts`에 `kotlin-jvm`, `kotlin-compose`, `kotlin-serialization`, `ksp`를 `apply false`로 추가한다(이것이 AGP 내장 KGP 2.2.10을 2.4.20으로 올린다). `settings.gradle.kts`에 `include(":core")`를 추가한다(BRouter·routing 모듈은 Task 10에서 추가).

- [ ] **Step 3: `core/build.gradle.kts`**

`kotlin-jvm` 플러그인, `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`(툴체인 다운로드를 피하려고 `jvmToolchain`은 쓰지 않는다), `java { sourceCompatibility = VERSION_17; targetCompatibility = VERSION_17 }`, 의존성 `kotlinx-coroutines-core`, `jts-core`, 테스트 `junit-jupiter`, `kotlin("test")`, `kotlinx-coroutines-test`, `tasks.test { useJUnitPlatform() }`.

- [ ] **Step 4: `app/build.gradle.kts`**

`kotlin-compose`, `kotlin-serialization`, `ksp` 플러그인 적용(**`kotlin-android`는 적용하지 않는다** — AGP 9 내장). `compileOptions` 17. `buildFeatures { compose = true; buildConfig = true }`.
버전: 파일 상단에 `val verMajor = 1; val verMinor = 0; val verPatch = 0` → `versionCode = verMajor*10000 + verMinor*100 + verPatch`, `versionName = "$verMajor.$verMinor.$verPatch"`, `buildTypes.debug { versionNameSuffix = "-dev" }`.
비밀 키: `rootProject.file("secrets.json")`가 있으면 `groovy.json.JsonSlurper`로 읽어 `kakaoRestApiKey`를, 없으면 `""`를 `buildConfigField("String", "KAKAO_REST_API_KEY", "\"$key\"")`로 넣는다.
의존성: compose BOM(ui, material3, ui-tooling-preview, ui-test-junit4), activity-compose, lifecycle-runtime-compose, lifecycle-viewmodel-compose, navigation-compose, `implementation(project(":core"))`, coroutines-android, 테스트 `junit-jupiter`(app JVM 테스트는 `testOptions.unitTests.all { it.useJUnitPlatform() }`), androidTest `androidx.test:runner/rules`, `androidx.test.ext:junit`.

- [ ] **Step 5: 실패하는 테스트 작성**

```kotlin
class AppVersionTest {
    @Test fun `debug version name is 1_0_0-dev`() = assertEquals("1.0.0-dev", BuildConfig.VERSION_NAME)
    @Test fun `version code follows rule`() = assertEquals(10000, BuildConfig.VERSION_CODE)
    @Test fun `display strips dev suffix and prefixes V`() {
        assertEquals("V1.0.0", AppVersion.display("1.0.0-dev"))
        assertEquals("V1.2.3", AppVersion.display("1.2.3"))
    }
    @Test fun `kakao key field exists`() = assertNotNull(BuildConfig.KAKAO_REST_API_KEY)
}
```

- [ ] **Step 6: 실행해 실패 확인** — Run: `./gradlew :app:testDebugUnitTest --tests '*AppVersionTest*'` Expected: FAIL (`AppVersion` 미정의)

- [ ] **Step 7: `object AppVersion { fun display(versionName: String): String }` 구현** — `-` 이후를 버리고 앞에 `V`.

템플릿 `MainActivity`는 임시로 `ComponentActivity` + `setContent { Text("bikeRecoder") }`로 바꾸고 레이아웃 XML·예제 테스트를 삭제한다. `material`·`appcompat` 라이브러리를 제거하므로 `themes.xml`(values, values-night)의 부모를 `android:Theme.Material.Light.NoActionBar`로 바꾼다(화면 테마는 Compose Material3가 담당).

- [ ] **Step 8: 통과 확인** — Run: `./gradlew :app:testDebugUnitTest --tests '*AppVersionTest*' :app:assembleDebug :core:test` Expected: BUILD SUCCESSFUL.
  - `buildEnvironment`에서 KGP 2.4.20이 쓰이지 않거나 AGP와 충돌하면: `kotlin = "2.2.10"`, `serialization = "1.9.0"`, `coroutines = "1.10.2"`로 내리고 다시 실행한다. 어느 쪽을 썼는지 커밋 메시지에 남긴다.

- [ ] **Step 9: Commit** — `git add -A && git commit -m "build: set up modules, Compose, versioning 1.0.0, secrets"`

---

### Task 2: core 기본 모델과 거리 계산

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/model/Geo.kt`, `.../model/Route.kt`, `.../routing/Router.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/model/GeoMathTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class GeoPoint(val lat: Double, val lon: Double)
data class LocationFix(val point: GeoPoint, val accuracyM: Float, val speedMps: Float?, val bearingDeg: Float?, val timeMillis: Long)
object GeoMath {
    fun distanceM(a: GeoPoint, b: GeoPoint): Double                 // haversine, R = 6_371_008.8
    fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection   // 선분 ab 위 최근접점
}
data class Projection(val point: GeoPoint, val fraction: Double, val distanceM: Double) // fraction ∈ [0,1]

enum class RouteProfile(val fileName: String) { CYCLEWAY_FIRST("cycleway-first"), BALANCED("balanced"), SHORTEST("shortest") }
enum class TurnType { STRAIGHT, LEFT, SLIGHT_LEFT, SHARP_LEFT, RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT, U_TURN, ROUNDABOUT }
data class Instruction(val pointIndex: Int, val type: TurnType, val roundaboutExit: Int, val distanceFromStartM: Double)
data class RouteSummary(val totalDistanceM: Double, val ascentM: Int, val cyclewayRatio: Double, val profile: RouteProfile)
data class Route(
    val points: List<GeoPoint>,
    val cumulativeM: List<Double>,          // points와 같은 길이, [0]=0.0
    val instructions: List<Instruction>,    // distanceFromStartM 오름차순
    val stopPointIndices: List<Int>,        // 각 Stop(경유지들…, 목적지)에 대응하는 points 인덱스
    val summary: RouteSummary,
)
data class Stop(val id: Long, val name: String, val point: GeoPoint, val isDestination: Boolean)

interface Router { suspend fun route(request: RouteRequest): RouteResult }
data class RouteRequest(val start: GeoPoint, val startBearingDeg: Float?, val stops: List<GeoPoint>, val profile: RouteProfile)
sealed interface RouteResult {
    data class Success(val route: Route) : RouteResult
    data class Failure(val reason: RouteFailure, val detail: String) : RouteResult
}
enum class RouteFailure { NO_SEGMENT_DATA, NO_ROUTE, TIMEOUT, OTHER }
```
- 헬퍼: `fun Route.Companion.fromPoints(points, instructions, stopPointIndices, summary)`는 두지 않는다. 대신 `fun cumulativeDistances(points: List<GeoPoint>): List<Double>`를 `GeoMath`에 둔다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `seoul city hall to gangnam station is about 8_9km`() {
    val d = GeoMath.distanceM(GeoPoint(37.5663, 126.9779), GeoPoint(37.4979, 127.0276))
    assertEquals(8_860.0, d, 60.0)
}
@Test fun `project clamps to segment ends`() {
    val a = GeoPoint(37.5, 127.0); val b = GeoPoint(37.5, 127.01)
    assertEquals(0.0, GeoMath.project(GeoPoint(37.5, 126.99), a, b).fraction, 1e-9)
    assertEquals(1.0, GeoMath.project(GeoPoint(37.5, 127.02), a, b).fraction, 1e-9)
}
@Test fun `project perpendicular distance`() {
    val p = GeoMath.project(GeoPoint(37.5004, 127.005), GeoPoint(37.5, 127.0), GeoPoint(37.5, 127.01))
    assertEquals(44.5, p.distanceM, 1.0); assertEquals(0.5, p.fraction, 0.01)
}
@Test fun `cumulative distances start at zero and are monotonic`() {
    val c = GeoMath.cumulativeDistances(listOf(GeoPoint(37.5,127.0), GeoPoint(37.5,127.01), GeoPoint(37.51,127.01)))
    assertEquals(0.0, c[0]); assertTrue(c[1] < c[2]); assertEquals(3, c.size)
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*GeoMathTest*'` → FAIL(미정의)
- [ ] **Step 3: 구현** — `project`는 위도 중심 등장방형(equirectangular) 근사로 평면 투영(짧은 선분에서 충분).
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -am "core: geo model, route model, router interface"` (새 파일은 `git add core`)

---

### Task 3: 진행 위치·이탈·도착 판정

**Files:**
- Create: `core/.../navigation/RouteProgressTracker.kt`, `OffRouteDetector.kt`, `ArrivalDetector.kt`
- Test: `core/src/test/.../navigation/RouteProgressTrackerTest.kt`, `OffRouteDetectorTest.kt`, `ArrivalDetectorTest.kt`, 공용 `TestRoutes.kt`(직선·곡선·왕복·순환 Route 생성 헬퍼)

**Interfaces:**
- Consumes: Task 2 모델
- Produces:
```kotlin
class RouteProgressTracker(route: Route, lookAheadM: Double = 500.0) {
    fun update(p: GeoPoint): Progress
}
data class Progress(
    val distanceAlongM: Double, val lateralOffsetM: Double, val remainingM: Double,
    val nextInstruction: Instruction?, val distanceToNextInstructionM: Double?,
)
class OffRouteDetector(thresholdM: Double = 40.0, durationMs: Long = 8_000, maxAccuracyM: Float = 30f, minRerouteIntervalMs: Long = 15_000) {
    fun onFix(fix: LocationFix, lateralOffsetM: Double): Boolean   // true = 지금 재탐색 시작
    fun onRerouteFinished(timeMillis: Long)                         // 성공/실패 모두 호출, 연속 판정 초기화
}
class ArrivalDetector(stops: List<Stop>, stopDistancesAlongM: List<Double>, firstUnvisited: Int = 0,
                      arriveRadiusM: Double = 30.0, passRadiusM: Double = 50.0, destinationMinProgress: Double = 200.0) {
    fun onFix(p: GeoPoint, remainingM: Double): Stop?   // 이번 위치로 도달 처리된 Stop(최대 1개)
    val nextIndex: Int
}
```

규칙:
- Tracker: 직전 매칭 지점부터 `distanceAlong + lookAheadM` 이내 선분만 검사(처음 update는 전체 검사). 진행 거리는 뒤로 가지 않는다(최소 직전값 − 20m 허용 없이 단조 증가). `nextInstruction` = `distanceFromStartM > distanceAlongM`인 첫 항목.
- OffRoute: `accuracyM > maxAccuracyM`인 위치는 무시(타이머 유지·초기화 안 함). `lateral ≥ threshold`가 처음 관측된 시각부터 `durationMs` 이상 지속되고 마지막 재탐색 종료 후 `minRerouteIntervalMs`가 지났으면 true를 **한 번** 반환하고, `onRerouteFinished` 전까지 다시 true를 내지 않는다. `lateral < threshold`면 타이머 초기화.
- Arrival: 다음 Stop과의 거리 ≤ 30m → 도달. 또는 한 번이라도 ≤ 50m였다가 이전 거리보다 커지기 시작하면 도달. **목적지**는 추가로 `remainingM ≤ destinationMinProgress`일 때만 도달(순환 경로 보호).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// RouteProgressTrackerTest
@Test fun `remaining distance within 1 percent on straight route`()      // 1km 직선, 중간점 → remaining 500±5
@Test fun `out and back route does not jump to return leg`()             // A→B→A, 출발 후 100m 지점 위치는 return leg 위에도 있음 → distanceAlong ≈ 100 (±10), 1900 아님
@Test fun `next instruction is first ahead`()                             // 지시 300m, 700m; 400m 지점 → next.distanceFromStartM == 700.0, distanceToNext ≈ 300
@Test fun `lateral offset reported when beside route`()                  // 직선 옆 60m → lateralOffsetM ≈ 60 (±2)

// OffRouteDetectorTest  (fix(t, acc) 헬퍼: 1초 간격)
@Test fun `off route after 8 seconds at 40m`()          // t=0..8s lateral 45 → t=8s에서 true, 그 전 false
@Test fun `7 seconds is not off route`()                // t=0..7s 45, t=8s 10 → 항상 false
@Test fun `single spike ignored`()                      // 0..20s 중 t=5 하나만 80m → 항상 false
@Test fun `poor accuracy fixes ignored`()               // acc=50 인 45m 위치 20초 → false
@Test fun `no second trigger before reroute finished`() // 8s true 이후 30s 동안 계속 45m → 추가 true 없음
@Test fun `respects 15s interval after reroute`()       // onRerouteFinished(t=10s) 후 다시 이탈: 첫 true 시각 ≥ 25s

// ArrivalDetectorTest
@Test fun `waypoint reached within 30m`()
@Test fun `waypoint passed within 50m counts as reached`()   // 최소 40m 접근 후 멀어짐 → 그 시점 Stop 반환
@Test fun `stops reached in order`()                          // 두 번째 경유지 근처를 먼저 지나도 첫 번째 미도달이면 반환 안 함
@Test fun `loop route does not arrive at start`()             // 목적지=출발점, remaining=9_800 → null
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*navigation*'` → FAIL
- [ ] **Step 3: 세 클래스 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git add core && git commit -m "core: route progress, off-route and arrival detection"`

---

### Task 4: 도착 예정 시각과 이동 거리

**Files:**
- Create: `core/.../navigation/EtaEstimator.kt`
- Test: `core/src/test/.../navigation/EtaEstimatorTest.kt`

**Interfaces:**
- Produces:
```kotlin
class EtaEstimator(windowMs: Long = 15 * 60_000L, minDataMs: Long = 5 * 60_000L,
                   defaultSpeedMps: Double = 15.0 / 3.6, stopSpeedMps: Double = 2.0 / 3.6) {
    fun onFix(fix: LocationFix)
    fun baseSpeedMps(nowMillis: Long): Double
    fun etaMillis(nowMillis: Long, remainingM: Double): Long
}
class Odometer(maxAccuracyM: Float = 30f) {
    fun onFix(fix: LocationFix)
    val distanceM: Double
}
```
- 이동 속도는 연속 위치 사이 거리/시간으로 계산(`speedMps`는 사용하지 않음 — null일 수 있다). 구간 속도 < `stopSpeedMps`인 구간은 시간·거리 모두 제외. 창 안의 이동 시간 합 < `minDataMs`면 기본 속도.
- Odometer는 `accuracyM > 30`인 위치를 버리고, 남은 위치 사이 거리를 합산한다. 날짜가 바뀌어도 초기화하지 않는다(세션 단위).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `uses default speed with under 5 minutes of data`()     // 3분 20km/h → base == 15/3.6
@Test fun `uses 15 minute moving average`()                       // 20분: 처음 5분 10km/h, 이후 15분 20km/h → base ≈ 20/3.6 (±0.1)
@Test fun `stops are excluded`()                                  // 10분 18km/h + 5분 정지 → base ≈ 18/3.6
@Test fun `eta falls back to default speed when only stationary fixes`() // 30분 같은 좌표, speed=null → eta == now + remaining/(15/3.6)*1000
@Test fun `eta adds remaining over base speed`()                  // remaining 15_000, base 15km/h → eta == now + 3_600_000 (±1000)
@Test fun `odometer ignores inaccurate fixes`()                   // 정상 1km + acc=80 튐 점 → distance ≈ 1000 (±5)
@Test fun `odometer continues across midnight`()                  // 23:59→00:01 경계 위치 → 합산 유지
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*EtaEstimatorTest*'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: eta estimator and odometer"`

---

### Task 5: 한국어 안내 문구

**Files:**
- Create: `core/.../voice/KoreanPhrases.kt`
- Test: `core/src/test/.../voice/KoreanPhrasesTest.kt`

**Interfaces:**
- Produces:
```kotlin
class KoreanPhrases(zone: java.time.ZoneId = ZoneId.of("Asia/Seoul")) {
    fun distance(m: Double): String
    fun clockTime(epochMillis: Long): String
    fun turn(type: TurnType, roundaboutExit: Int): String
    fun turnAhead(distanceM: Double, type: TurnType, exit: Int, then: TurnType?): String
    fun turnNow(type: TurnType, exit: Int, then: TurnType?): String
    fun kmReport(km: Int, remainingM: Double, etaMillis: Long): String
    val arrivedDestination: String   // "목적지에 도착했습니다"
    val arrivedWaypoint: String      // "경유지에 도착했습니다"
    val offRoute: String             // "경로를 벗어났습니다. 다시 탐색합니다"
    val rerouteFailed: String        // "경로를 다시 찾지 못했습니다"
    val gpsWeak: String              // "GPS 신호가 약합니다"
}
```
- 문구 표(정확히 이 문자열): STRAIGHT "직진", LEFT "좌회전", SLIGHT_LEFT "왼쪽 방향", SHARP_LEFT "왼쪽으로 급회전", RIGHT "우회전", SLIGHT_RIGHT "오른쪽 방향", SHARP_RIGHT "오른쪽으로 급회전", KEEP_LEFT "왼쪽 길로 계속", KEEP_RIGHT "오른쪽 길로 계속", U_TURN "유턴", ROUNDABOUT "회전교차로에서 {n}번째 출구".
- `turnAhead` = "{거리} 앞에서 {turn}입니다" / then 있으면 "{거리} 앞에서 {turn} 후 바로 {then}입니다". `turnNow` = "{turn}입니다" / "{turn} 후 바로 {then}입니다".
- `kmReport` = "{km}킬로미터 이동. 남은 거리 {distance}, 도착 예정 {clockTime}입니다."

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun distances() {
    assertEquals("800미터", p.distance(796.0)); assertEquals("200미터", p.distance(204.0))
    assertEquals("1.0킬로미터", p.distance(1_000.0)); assertEquals("42.1킬로미터", p.distance(42_080.0))
}
@Test fun `clock noon and midnight`() {
    assertEquals("오후 12시 0분", p.clockTime(at(12, 0))); assertEquals("오전 12시 5분", p.clockTime(at(0, 5)))
    assertEquals("오후 3시 40분", p.clockTime(at(15, 40))); assertEquals("오전 9시 7분", p.clockTime(at(9, 7)))
}
@Test fun turns() {
    assertEquals("200미터 앞에서 좌회전입니다", p.turnAhead(200.0, LEFT, 0, null))
    assertEquals("좌회전 후 바로 우회전입니다", p.turnNow(LEFT, 0, RIGHT))
    assertEquals("300미터 앞에서 회전교차로에서 2번째 출구입니다", p.turnAhead(300.0, ROUNDABOUT, 2, null))
}
@Test fun `km report`() = assertEquals(
    "5킬로미터 이동. 남은 거리 42.1킬로미터, 도착 예정 오후 3시 40분입니다.",
    p.kmReport(5, 42_080.0, at(15, 40)))
```
(`at(h, m)`은 2026-10-06 Asia/Seoul 기준 epochMillis 헬퍼)

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 미터는 10m 단위 반올림, 1,000m 이상은 소수 첫째 자리(`Locale.ROOT`).
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: korean voice phrases"`

---

### Task 6: 음성 안내 스케줄러

**Files:**
- Create: `core/.../voice/VoiceScheduler.kt`
- Test: `core/src/test/.../voice/VoiceSchedulerTest.kt`

**Interfaces:**
- Consumes: `Route`, `Progress`(Task 3), `KoreanPhrases`(Task 5)
- Produces:
```kotlin
enum class Priority { TURN, EVENT, PERIODIC }   // 선언 순서 = 우선순위
data class Utterance(val text: String, val priority: Priority)
class VoiceScheduler(route: Route, phrases: KoreanPhrases,
                     farM: Double = 200.0, farFastM: Double = 300.0, fastSpeedMps: Double = 25.0 / 3.6,
                     nearM: Double = 30.0, mergeWithinM: Double = 50.0, deferPeriodicSec: Double = 15.0) {
    fun enqueueEvent(text: String)
    fun onUpdate(progress: Progress, speedMps: Double, sessionDistanceM: Double, etaMillis: Long): List<Utterance>
    fun replaceRoute(route: Route)   // 재탐색 후: 이미 말한 지시 기록 초기화, km 카운터 유지
}
```
규칙:
- 각 Instruction마다 "멀리"(far) 안내와 "가까이"(near) 안내를 최대 1번씩. 멀리 거리 = 속도 > 25km/h면 300m, 아니면 200m. `distanceToNext ≤ 멀리거리`이고 `> nearM`이면 `turnAhead`, `≤ nearM`이면 `turnNow`. 멀리 안내 전에 이미 near 안이면 near만 말한다.
- 다음 지시 바로 뒤 지시가 `mergeWithinM` 이내면 `then`으로 합치고, 뒤 지시는 따로 말하지 않는다.
- 1km 정기: `floor(sessionDistanceM/1000)`이 증가하면 대기열에 `kmReport` 하나(여러 km를 건너뛰어도 최신 km 하나만). 다음 회전 안내 시점까지 남은 시간(= (distanceToNext − 멀리거리)/speed, speed ≤ 0.5m/s면 무한대)이 15초 미만이면 보류하고, 그 회전의 near 안내가 나간 다음 업데이트에 내보낸다.
- 반환은 우선순위 순서 정렬, 같은 우선순위는 발생 순.

- [ ] **Step 1: 실패하는 테스트** (`progressAt(route, along)` 헬퍼는 `RouteProgressTracker` 사용)

```kotlin
@Test fun `far and near prompt once each`()          // 지시 LEFT@1000; along 790 → ["200미터 앞에서 좌회전입니다"], 795 → [], 975 → ["좌회전입니다"], 980 → []
@Test fun `far prompt 300m when fast`()              // speed 8m/s, along 700 → "300미터 앞에서 좌회전입니다"
@Test fun `merges close consecutive turns`()         // LEFT@1000, RIGHT@1040; along 975 → ["좌회전 후 바로 우회전입니다"], 이후 RIGHT 단독 안내 없음
@Test fun `km report text`()                         // sessionDistance 1_000 → 정확히 kmReport(1, remaining, eta)
@Test fun `km report deferred near turn`()           // speed 5, 지시까지 250m(=far까지 10초) + km 경계 → 이번 []; near 안내 후 업데이트에서 kmReport
@Test fun `event outranks periodic and turn outranks event`() // 같은 업데이트에 셋 → 순서 TURN, EVENT, PERIODIC
@Test fun `replaceRoute keeps km counter`()          // 3km 안내 후 replaceRoute → 3.5km에서 추가 kmReport 없음, 4km에서 1번
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: voice prompt scheduler"`

---

### Task 7: 경로 주변 지도 범위 계산

**Files:**
- Create: `core/.../offline/CorridorPlanner.kt`
- Test: `core/src/test/.../offline/CorridorPlannerTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)
data class CorridorPlan(val polygon: List<List<GeoPoint>>,  // 외곽 링 목록(첫 점=끝 점), MultiPolygon 가능
                        val overview: BoundingBox, val estimatedTiles: Long, val truncated: Boolean)
object TileMath {
    fun tilesForPolygon(polygon: List<List<GeoPoint>>, minZoom: Int, maxZoom: Int): Long
    fun tilesForBox(box: BoundingBox, minZoom: Int, maxZoom: Int): Long
}
object CorridorPlanner {
    fun plan(route: Route, fromDistanceAlongM: Double = 0.0, halfWidthM: Double = 2_000.0,
             maxTiles: Long = 15_000, maxLengthM: Double = 150_000.0): CorridorPlan
}
```
- 방법: 경로 점을 경로 중심 위도 기준 등장방형 미터 좌표로 변환 → JTS `LineString.buffer(halfWidthM)` → `DouglasPeuckerSimplifier`(허용 50m) → 위경도로 역변환. 타일 수 = z10–14 회랑 타일(각 줌에서 다각형과 교차하는 XYZ 타일 수의 합, 교차 판정은 JTS) + z5–9 개요 상자 타일. 합계 > `maxTiles`면 `fromDistanceAlongM`부터 `maxLengthM`까지만 잘라 다시 계산하고 `truncated = true`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `corridor covers points 1_9km away but not 2_2km`()   // 동서 10km 직선; 북쪽 1.9km 점 inside, 2.2km 점 outside (JTS contains로 검증)
@Test fun `box tile count at zoom 14 for seoul box`()           // BoundingBox(37.45,126.85,37.65,127.15), 14..14 → 기대값은 XYZ 공식으로 계산한 상수(테스트 안에서 독립 계산)와 같음
@Test fun `short route is not truncated`()                      // 20km → truncated=false, estimatedTiles < 15_000
@Test fun `huge corridor is truncated to 150km`()                // maxTiles=500으로 300km 경로 → truncated=true, 폴리곤 경계가 출발+150km(+2km) 이내
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: offline corridor planner and tile estimation"`

---

### Task 8: 여행 상태 관리

**Files:**
- Create: `core/.../trip/Trip.kt`, `core/.../trip/TripManager.kt`
- Test: `core/src/test/.../trip/TripManagerTest.kt` (메모리 `FakeTripStore`, `FakeOfflineMapController` 포함)

**Interfaces:**
- Consumes: `GeoPoint`, `RouteProfile`, `Route`
- Produces:
```kotlin
enum class TripType { SINGLE_DAY, MULTI_DAY }
enum class TripStatus { ACTIVE, COMPLETED }
data class Trip(val id: Long, val type: TripType, val status: TripStatus, val profile: RouteProfile,
                val createdAt: Long, val lastActiveAt: Long, val completedAt: Long?)
data class TripStop(val id: Long, val tripId: Long, val order: Int, val name: String, val point: GeoPoint,
                    val isDestination: Boolean, val visitedAt: Long?)
interface TripStore {
    suspend fun activeTrip(): Trip?
    suspend fun trip(id: Long): Trip?
    suspend fun insertTrip(trip: Trip): Long            // id=0으로 넘기면 새 id 반환
    suspend fun updateTrip(trip: Trip)
    suspend fun stops(tripId: Long): List<TripStop>     // order 오름차순
    suspend fun replaceStops(tripId: Long, stops: List<TripStop>)
    suspend fun markVisited(stopId: Long, at: Long)
}
interface OfflineMapController {
    suspend fun deleteForTrip(tripId: Long)
    suspend fun downloadForTrip(tripId: Long, route: Route, fromDistanceAlongM: Double)
}
sealed interface StartPrompt { data object AskType : StartPrompt; data class AskContinue(val trip: Trip) : StartPrompt }
class TripManager(store: TripStore, offline: OfflineMapController, clock: () -> Long, zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    suspend fun startPrompt(): StartPrompt
    suspend fun startTrip(type: TripType, profile: RouteProfile, stops: List<TripStop>): Trip   // 기존 ACTIVE는 먼저 complete
    suspend fun changeStops(tripId: Long, stops: List<TripStop>)    // 오프라인 지도 삭제 + 저장(재다운로드는 호출자가 경로 계산 후 downloadForTrip)
    suspend fun markVisited(stopId: Long)
    suspend fun endToday(tripId: Long)                               // MULTI_DAY 전용: ACTIVE 유지, lastActiveAt 갱신
    suspend fun complete(tripId: Long)                               // COMPLETED + completedAt + deleteForTrip
    suspend fun convertToMultiDay(tripId: Long)                      // COMPLETED → ACTIVE(MULTI_DAY), completedAt=null
    suspend fun touch(tripId: Long)                                  // lastActiveAt = now
    suspend fun stalePrompt(): Trip?                                  // ACTIVE·MULTI_DAY이고 now − lastActiveAt ≥ 3일
    fun dayNumber(trip: Trip): Int                                   // createdAt 날짜부터 오늘까지 달력 일수 + 1
    suspend fun remainingStops(tripId: Long): List<TripStop>          // visitedAt == null
}
```

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `no active trip asks type`()                     // startPrompt() is AskType
@Test fun `active multi-day trip asks continue`()          // AskContinue(trip)
@Test fun `starting new trip completes previous`()          // 이전 ACTIVE → COMPLETED, offline.deleted에 이전 id
@Test fun `single day complete deletes offline maps`()
@Test fun `end today keeps multi-day trip active`()        // status ACTIVE, offline 삭제 없음
@Test fun `convert completed single day to multi day`()   // type MULTI_DAY, status ACTIVE, completedAt null
@Test fun `change stops deletes offline maps`()            // deleteForTrip 호출, stops 교체됨
@Test fun `stale after 3 days`()                           // lastActive = now − 3일 → trip; now − 2일23시간 → null; SINGLE_DAY는 null
@Test fun `day number counts calendar days`()              // 생성 10/04 23:00, 지금 10/06 01:00 → 3
@Test fun `remaining stops excludes visited`()
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "core: trip state management"`

---

### Task 9: 내비게이션 세션 (core 조립)

**Files:**
- Create: `core/.../navigation/NavigationSession.kt`, `core/.../gpx/GpxParser.kt`
- Test: `core/src/test/.../navigation/NavigationSessionTest.kt`, `core/src/test/.../gpx/GpxParserTest.kt`, `core/src/test/resources/gpx/*.gpx`

**Interfaces:**
- Consumes: Task 2–6
- Produces:
```kotlin
data class NavState(val route: Route, val progress: Progress, val speedMps: Double, val etaMillis: Long,
                    val gpsWeak: Boolean, val rerouting: Boolean)
sealed interface NavEvent {
    data class StopReached(val stop: Stop) : NavEvent       // isDestination=true면 목적지
    data class Rerouted(val route: Route) : NavEvent
    data object RerouteFailed : NavEvent
}
data class NavUpdate(val state: NavState, val utterances: List<Utterance>, val events: List<NavEvent>)
class NavigationSession(initialRoute: Route, stops: List<Stop>, profile: RouteProfile, router: Router,
                        phrases: KoreanPhrases, scope: CoroutineScope,
                        gpsTimeoutMs: Long = 10_000, rerouteRetryMs: Long = 30_000) {
    val updates: SharedFlow<NavUpdate>
    fun onFix(fix: LocationFix)          // 동기 처리 후 updates로 방출. 재탐색은 scope에서 비동기
    fun onTick(nowMillis: Long)          // GPS 공백 감시(1초마다 호출)
    val sessionDistanceM: Double
}
object GpxParser { fun parse(gpx: String): List<LocationFix> }   // trkpt lat/lon/time, accuracy 5f, speed/bearing null
```
- 재탐색: `OffRouteDetector`가 true → `enqueueEvent(offRoute)` + `rerouting=true` + `scope.launch { router.route(RouteRequest(현재위치, fix.bearingDeg, 미도달 stops, profile)) }`. 계산 중에는 새 재탐색을 시작하지 않는다. 성공 → tracker/arrival/scheduler 교체, `Rerouted` 이벤트. 실패 → `rerouteFailed` 안내 + `RerouteFailed`, `rerouteRetryMs` 후 이탈 상태가 계속이면 다시 시도. 끝나면 `onRerouteFinished`.
- GPS 약함: 마지막 fix 후 `gpsTimeoutMs` 지나면 `gpsWeak=true`, 안내 1회. 새 fix가 오면 false로 복귀(복귀 안내 없음).

- [ ] **Step 1: 실패하는 테스트** (`FakeRouter`: 미리 준 결과를 반환, 호출 횟수 기록, `CompletableDeferred`로 지연 제어; `TestScope` 사용)

```kotlin
@Test fun `follows route and arrives`()       // gpx/follow.gpx(직선 3km, 회전 2개) → utterances에 회전 안내 순서대로, kmReport 3개, 마지막 "목적지에 도착했습니다", StopReached(destination)
@Test fun `deviation triggers exactly one reroute`()   // gpx/deviate.gpx(100m 이탈 20초) → router.calls == 1, Rerouted 1개, "경로를 벗어났습니다. 다시 탐색합니다" 1회
@Test fun `gps spike does not reroute`()               // gpx/spike.gpx → router.calls == 0
@Test fun `reroute is not started twice while in flight`()  // 라우터 응답을 막아둔 채 이탈 위치 30초 공급 → calls == 1
@Test fun `reroute failure announces and retries after 30s`() // Failure 반환 → "경로를 다시 찾지 못했습니다", 30초 후 이탈 계속이면 calls == 2
@Test fun `gps weak after 10s silence`()               // 마지막 fix 후 onTick(+10_001) → gpsWeak true, "GPS 신호가 약합니다" 1회; onTick 반복해도 추가 안내 없음
@Test fun `gpx parser reads track points`()            // trkpt 3개 → 3 fixes, time 파싱
```

테스트 GPX는 Task 3 `TestRoutes`의 직선 경로 좌표로 손으로 만든다(1초 간격, 5m/s).

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test` → 전체 PASS
- [ ] **Step 5: Commit** — `git commit -m "core: navigation session with reroute and gps watchdog"`

---

### Task 10: BRouter 소스 포함과 경로 성향 프로필

**Files:**
- Create: `third_party/brouter/{brouter-util,brouter-codec,brouter-expressions,brouter-mapaccess,brouter-core}/build.gradle.kts`, 각 `src/main/java/**`(v1.7.10 원본 복사), `third_party/brouter/LICENSE`, `third_party/brouter/README.md`(출처·커밋 `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`·수정 없음 명시)
- Create: `routing-brouter/build.gradle.kts`, `routing-brouter/src/main/resources/brouter/profiles/{lookups.dat,cycleway-first.brf,balanced.brf,shortest.brf}`, `routing-brouter/src/main/kotlin/com/cowork/bikerecoder/routing/ProfileInstaller.kt`
- Modify: `settings.gradle.kts`
- Test: `routing-brouter/src/test/kotlin/com/cowork/bikerecoder/routing/EngineSmokeTest.kt`(@Tag("integration")), `ProfileInstallerTest.kt`

**Interfaces:**
- Produces: `object ProfileInstaller { fun install(targetDir: File): File }` — 클래스패스 `brouter/profiles/*` 4개 파일을 `targetDir`에 복사(내용 다를 때만 덮어씀)하고 `targetDir` 반환. 모듈 `:routing-brouter`, `:third_party:brouter-*`.

- [ ] **Step 1: 소스 복사**

```bash
git clone --depth 1 --branch v1.7.10 https://github.com/abrensch/brouter.git /tmp/brouter-src
for m in brouter-util brouter-codec brouter-expressions brouter-mapaccess brouter-core; do
  mkdir -p third_party/brouter/$m/src/main && cp -r /tmp/brouter-src/$m/src/main/java third_party/brouter/$m/src/main/
done
cp /tmp/brouter-src/LICENSE third_party/brouter/LICENSE
cp /tmp/brouter-src/misc/profiles2/lookups.dat routing-brouter/src/main/resources/brouter/profiles/
```

- [ ] **Step 2: 모듈 빌드 파일**

각 BRouter 모듈: `plugins { `java-library` }`, `tasks.withType<JavaCompile> { options.release = 11; options.encoding = "UTF-8" }`. 의존(`implementation(project(...))`): codec→util / expressions→util,codec / mapaccess→util,codec,expressions / core→mapaccess,util,expressions,codec.
`routing-brouter`: `kotlin-jvm`, JVM 17, `api(project(":core"))`, `implementation` 5개 BRouter 모듈 전부, `kotlinx-serialization-json`(+ serialization 플러그인), coroutines. 테스트: JUnit5; `tasks.test { useJUnitPlatform { excludeTags("integration") } }`, 별도 `tasks.register<Test>("integrationTest") { useJUnitPlatform { includeTags("integration") }; systemProperty("segmentsDir", rootProject.layout.buildDirectory.dir("brouter-segments").get().asFile.path); testClassesDirs/classpath = test의 것 }`.
`settings.gradle.kts`에 `:routing-brouter`와 5개 `:third_party:brouter-*` 포함(`project(":third_party:brouter-util").projectDir = file("third_party/brouter/brouter-util")` 형태).

- [ ] **Step 3: 프로필 3종 작성** — `misc/profiles2/trekking.brf`를 복사해 각각 만든다.
  - 공통: `assign turnInstructionMode = 1`, 접근 판정에서 `motorroad=yes`는 통행 불가 유지(trekking 138행 근처), `highway=motorway|motorway_link|trunk|trunk_link`의 costfactor는 `10000`(통행 불가).
  - `costfactor`의 "highway=" 분기(trekking 349–360행 부근)를 스펙 6.1 표 값으로 교체: cycleway·자전거 노선(`isbike`이고 노선 관계 있음) 1.0 / residential·service·living_street 1.5·1.2·1.0 / tertiary 2.5·1.5·1.1 / secondary 4.0·2.0·1.2 / primary 10·3.0·1.5 (순서: cycleway-first·balanced·shortest).
  - `cycleway-first.brf`: `assign stick_to_cycleroutes = true`.
  - `shortest.brf`: 오르막 비용(`uphillcost`, `downhillcost`) 0, `turncost` 0.
  - 각 파일 상단 주석에 "derived from BRouter trekking.brf v1.7.10".

- [ ] **Step 4: 실패하는 테스트**

```kotlin
class ProfileInstallerTest {
    @Test fun `installs four profile files`(@TempDir dir: File) {
        ProfileInstaller.install(dir)
        assertEquals(setOf("lookups.dat","cycleway-first.brf","balanced.brf","shortest.brf"), dir.list()!!.toSet())
    }
}
@Tag("integration") class EngineSmokeTest {
    // segmentsDir에 E125_N35.rd5가 없으면 https://brouter.de/brouter/segments4/E125_N35.rd5 를 내려받는다(테스트 헬퍼 SegmentFixture.ensure()).
    @Test fun `routes yeouido to banpo with every profile`() {
        for (p in RouteProfile.entries) {
            val e = RoutingEngine(null, null, segDir, listOf(node(37.5284,126.9327,"from"), node(37.5100,126.9960,"to")),
                RoutingContext().apply { localFunction = File(profiles, "${p.fileName}.brf").path })
            e.quite = true; e.doRun(60_000)
            assertNull(e.errorMessage, p.name); assertTrue(e.foundTrack.distance in 5_000..12_000)
            assertTrue(e.foundTrack.voiceHints.list.isNotEmpty())
        }
    }
}
```
(`node(lat, lon, name)` = `OsmNodeNamed().apply { ilat = ((lat+90)*1e6).toInt(); ilon = ((lon+180)*1e6).toInt(); this.name = name }`)

- [ ] **Step 5: 실패 확인** — `./gradlew :routing-brouter:test` → FAIL(ProfileInstaller 미정의)
- [ ] **Step 6: `ProfileInstaller` 구현** — `javaClass.getResourceAsStream("/brouter/profiles/$name")`.
- [ ] **Step 7: 통과 확인** — `./gradlew :routing-brouter:test :routing-brouter:integrationTest` → PASS (최초 실행 시 67MB 다운로드)
- [ ] **Step 8: Commit** — `git add third_party routing-brouter settings.gradle.kts && git commit -m "routing: vendor BRouter 1.7.10 and add bike profiles"`

---

### Task 11: BRouter 라우터 구현과 실제 경로 검증

**Files:**
- Create: `routing-brouter/src/main/kotlin/com/cowork/bikerecoder/routing/BRouterRouter.kt`, `BRouterGeoJsonParser.kt`, `CyclewayClassifier.kt`
- Test: `routing-brouter/src/test/kotlin/.../BRouterGeoJsonParserTest.kt`, `CyclewayClassifierTest.kt`, `RealRouteTest.kt`(@Tag("integration")), `src/test/resources/fixtures/small.geojson`

**Interfaces:**
- Consumes: `Router`, `Route`, `RouteRequest`, `RouteResult`(Task 2), `ProfileInstaller`(Task 10)
- Produces:
```kotlin
class BRouterRouter(segmentDir: File, profileDir: File, maxRunningTimeMs: Long = 60_000,
                    dispatcher: CoroutineDispatcher = Dispatchers.Default) : Router
object BRouterGeoJsonParser { fun parse(json: String, profile: RouteProfile, stops: List<GeoPoint>): Route }
object CyclewayClassifier { fun isCycleFriendly(wayTags: String): Boolean }
```
- `BRouterRouter.route`: `Mutex`로 직렬화. waypoints = start("from") + stops("via1".."to"). `startBearingDeg`가 있으면 `rc.startDirection = it.toInt(); rc.startDirectionValid = true`. `doRun(maxRunningTimeMs)` 후 `errorMessage` 매핑: `"datafile"` 포함 → `NO_SEGMENT_DATA`, `"operation killed"` 포함 → `TIMEOUT`, 그 외 non-null → `NO_ROUTE`; 예외 → `OTHER`. 성공 시 `FormatJson(rc).format(engine.foundTrack)`을 파서에 넘김.
- 파서: `geometry.coordinates`([lon, lat, ele?]) → points, `GeoMath.cumulativeDistances`. `properties."filtered ascend"` → ascentM. `voicehints` `[index, cmd, exit, distToNext, angle]` → cmd 매핑 1 STRAIGHT, 2 LEFT, 3 SLIGHT_LEFT, 4 SHARP_LEFT, 5 RIGHT, 6 SLIGHT_RIGHT, 7 SHARP_RIGHT, 8 KEEP_LEFT, 9 KEEP_RIGHT, 10/11/15 U_TURN, 13/14 ROUNDABOUT, 12·16 제외; `distanceFromStartM = cumulativeM[index]`. `messages`(첫 행 헤더) 의 `Distance`·`WayTags` 열로 cyclewayRatio = Σ(친화 구간 거리)/Σ거리. `stopPointIndices`: 각 stop에 대해 직전 인덱스 이후에서 가장 가까운 점.
- `CyclewayClassifier`: `highway=cycleway` 또는 `route_bicycle_(icn|ncn|rcn|lcn)=yes` 또는 `cycleway(:left|:right|:both)?=(lane|track)` 이면 true.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// CyclewayClassifierTest
@Test fun classifies() {
    assertTrue(isCycleFriendly("highway=cycleway surface=asphalt"))
    assertTrue(isCycleFriendly("highway=residential route_bicycle_ncn=yes"))
    assertTrue(isCycleFriendly("highway=secondary cycleway:right=track"))
    assertFalse(isCycleFriendly("highway=primary cycleway=no"))
}
// BRouterGeoJsonParserTest (small.geojson: 점 4개, voicehints [[1,2,0,100.0,-90],[2,13,2,50.0,0]], messages 2구간 cycleway 300m + primary 100m)
@Test fun `parses points instructions ratio`() {
    val r = BRouterGeoJsonParser.parse(fixture, RouteProfile.BALANCED, listOf(GeoPoint(…last…)))
    assertEquals(4, r.points.size); assertEquals(listOf(TurnType.LEFT, TurnType.ROUNDABOUT), r.instructions.map { it.type })
    assertEquals(2, r.instructions[1].roundaboutExit); assertEquals(0.75, r.summary.cyclewayRatio, 1e-9)
    assertEquals(listOf(3), r.stopPointIndices)
}
// RealRouteTest (@Tag("integration"))
@Test fun `yeouido to banpo cycleway-first is at least 70 percent cycle friendly`()
@Test fun `no motorroad motorway or trunk on any profile`()     // messages WayTags에 motorroad=yes, highway=motorway, highway=trunk 구간 거리 합 == 0
@Test fun `profile ordering`()   // distance: SHORTEST ≤ BALANCED ≤ CYCLEWAY_FIRST ; ratio: 역순 (각 비교 5% 허용 오차)
@Test fun `instructions converted`()  // instructions.isNotEmpty() && 모든 pointIndex in points.indices
@Test fun `seoul to yeoju within 60s`()  // (37.5663,126.9779)→(37.2982,127.6372), 측정 시간 < 60_000ms, Success
@Test fun `missing segment returns NO_SEGMENT_DATA`()  // 빈 segmentDir → Failure(NO_SEGMENT_DATA)
```
`no motorroad…`와 `profile ordering`을 검증하려고 `BRouterRouter`는 내부 함수 `internal suspend fun routeRaw(request): Pair<RouteResult, String?>`(GeoJSON 원문)를 테스트에 노출한다.

- [ ] **Step 2: 실패 확인** — `./gradlew :routing-brouter:test` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :routing-brouter:test :routing-brouter:integrationTest` → PASS. 비율 70% 미달이나 순서 위반이면 프로필 계수(Task 10)를 조정하고 조정 내용을 `.brf` 주석에 남긴다.
- [ ] **Step 5: Commit** — `git commit -m "routing: BRouter router with geojson parsing and real-route tests"`

---

### Task 12: Room 저장소와 설정

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `RoomTripStore.kt`, `SettingsRepository.kt`
- Modify: `app/build.gradle.kts`(room-runtime, room-ktx, `ksp(room-compiler)`, datastore-preferences, `room { schemaDirectory("$projectDir/schemas") }`)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/data/RoomTripStoreTest.kt`

**Interfaces:**
- Consumes: `TripStore`, `Trip`, `TripStop`(Task 8)
- Produces:
```kotlin
@Entity(tableName = "trip") data class TripEntity(@PrimaryKey(autoGenerate = true) val id: Long, val type: String, val status: String, val profile: String, val createdAt: Long, val lastActiveAt: Long, val completedAt: Long?)
@Entity(tableName = "waypoint", foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = CASCADE)], indices = [Index("tripId")])
data class WaypointEntity(@PrimaryKey(autoGenerate = true) val id: Long, val tripId: Long, val order: Int, val name: String, val lat: Double, val lon: Double, val isDestination: Boolean, val visitedAt: Long?)
@Entity(tableName = "offline_region_ref", indices = [Index("tripId")])
data class OfflineRegionRefEntity(@PrimaryKey(autoGenerate = true) val id: Long, val tripId: Long, val mapLibreRegionId: Long, val kind: String, val createdAt: Long)
class RoomTripStore(db: AppDatabase) : TripStore
interface OfflineRegionRefDao { insert, forTrip(tripId): List<…>, deleteForTrip(tripId), all(): List<…> }
data class AppSettings(val defaultProfile: RouteProfile = CYCLEWAY_FIRST, val voiceEnabled: Boolean = true,
                       val keepScreenOn: Boolean = true, val wifiOnlyOfflineMaps: Boolean = false)
class SettingsRepository(context: Context) { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }
```
DB 이름 `bikerecoder.db`, version 1, `exportSchema = true`.

- [ ] **Step 1: 실패하는 테스트** (in-memory DB)

```kotlin
@Test fun insertAndReadActiveTrip()       // insertTrip(ACTIVE) → activeTrip()?.id == 반환 id
@Test fun replaceStopsKeepsOrder()        // order 2,0,1로 넣어도 stops()는 0,1,2
@Test fun markVisitedPersists()
@Test fun completedTripIsNotActive()
@Test fun deletingTripCascadesStops()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:connectedDebugAndroidTest --tests '*RoomTripStoreTest*'` → FAIL (기기/에뮬레이터 필요 — Task 21 Step 1의 AVD를 먼저 만든다)
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: room trip store and settings"`

---

### Task 13: 경로 데이터 파일 관리

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/offline/SegmentRepository.kt`
- Modify: `app/build.gradle.kts`(okhttp, 테스트 `mockwebserver3`/`mockwebserver3-junit5` 5.5.0, coroutines-test)
- Test: `app/src/test/java/com/cowork/bikerecoder/offline/SegmentRepositoryTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class SegmentInfo(val name: String, val required: Boolean, val installed: Boolean, val sizeBytes: Long?)
class SegmentRepository(dir: File, client: OkHttpClient, baseUrl: HttpUrl = "https://brouter.de/brouter/segments4/".toHttpUrl()) {
    companion object { val REQUIRED = listOf("E125_N35", "E125_N30"); val OPTIONAL = listOf("E130_N35", "E120_N35") }
    fun list(): List<SegmentInfo>
    fun hasRequired(): Boolean
    suspend fun download(name: String, onProgress: (read: Long, total: Long) -> Unit): Result<File>
    suspend fun hasUpdate(name: String): Boolean        // HEAD Last-Modified > 로컬 lastModified
    fun delete(name: String)
}
```
파일명 `{name}.rd5`, 임시 `{name}.rd5.part`. 받은 바이트 수 ≠ `Content-Length`면 `.part` 삭제 후 실패. 성공 시 rename하고 `lastModified`를 서버 `Last-Modified`로 설정.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `download renames part file on success`()   // body 1024B, Content-Length 1024 → E125_N35.rd5 존재, .part 없음
@Test fun `truncated download leaves nothing`()        // Content-Length 2048, body 1024 후 연결 끊김(SocketPolicy) → failure, 두 파일 모두 없음
@Test fun `has required only when both present`()
@Test fun `update detected from last-modified`()       // 로컬 lastModified 2026-10-01, 서버 2026-10-05 → true
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests '*SegmentRepositoryTest*'` → FAIL
- [ ] **Step 3: 구현** (`Dispatchers.IO`, 64KB 버퍼)
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: brouter segment download and update check"`

---

### Task 14: 카카오 장소 검색

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/search/PlaceSearch.kt`, `KakaoLocalClient.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/search/KakaoLocalClientTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class Place(val name: String, val address: String, val point: GeoPoint, val distanceM: Int?)
enum class SearchError(val messageKo: String) {
    KEY_MISSING("카카오 API 키가 설정되지 않았습니다. secrets.json을 확인하세요"),
    INVALID_KEY("API 키가 올바르지 않습니다. secrets.json을 확인하세요"),
    MAP_NOT_ENABLED("카카오맵 사용 설정(제품 설정)이 꺼져 있습니다"),
    NETWORK("인터넷에 연결되어 있지 않습니다. 지도를 길게 눌러 목적지를 정할 수 있습니다"),
    OTHER("검색 중 오류가 발생했습니다"),
}
sealed interface SearchResult<out T> { data class Ok<T>(val value: T) : SearchResult<T>; data class Err(val error: SearchError) : SearchResult<Nothing> }
interface PlaceSearch {
    suspend fun keyword(query: String, near: GeoPoint?): SearchResult<List<Place>>
    suspend fun addressOf(point: GeoPoint): SearchResult<String?>
}
class KakaoLocalClient(client: OkHttpClient, apiKey: String, baseUrl: HttpUrl = "https://dapi.kakao.com".toHttpUrl()) : PlaceSearch
```
- keyword: `GET /v2/local/search/keyword.json?query=..&x=lon&y=lat&sort=distance&size=15` (near 없으면 x·y·sort 생략). 응답 `documents[].{place_name, road_address_name, address_name, x, y, distance}` → address는 road_address_name이 비면 address_name. `distance`는 빈 문자열이면 null.
- addressOf: `GET /v2/local/geo/coord2address.json?x=..&y=..` → `documents[0].road_address.address_name ?: documents[0].address.address_name`.
- 오류: 키 빈 문자열 → `KEY_MISSING`(요청 없음), 401 → `INVALID_KEY`, 403 → `MAP_NOT_ENABLED`, `IOException` → `NETWORK`, 그 외 → `OTHER`. 빈/공백 검색어 → `Ok(emptyList())`(요청 없음).

- [ ] **Step 1: 실패하는 테스트** (MockWebServer)

```kotlin
@Test fun `parses keyword results`()            // 2개 문서 → Place 2개, point=(y,x), distance 1234
@Test fun `sends KakaoAK header`()              // request.headers["Authorization"] == "KakaoAK test-key"
@Test fun `query is url encoded`()              // "서울 시청&역" → requestUrl.queryParameter("query") == "서울 시청&역"
@Test fun `blank query returns empty without request`()   // "  " → Ok(empty), server.requestCount == 0
@Test fun `401 maps to INVALID_KEY`()
@Test fun `403 maps to MAP_NOT_ENABLED`()
@Test fun `missing key maps to KEY_MISSING`()
@Test fun `address prefers road address`()
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests '*KakaoLocalClientTest*'` → FAIL
- [ ] **Step 3: 구현** (kotlinx.serialization `ignoreUnknownKeys = true`)
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: kakao local place search"`

---

### Task 15: 지도 표시 (MapLibre, 한국어 라벨, 교체 가능한 타일)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/map/TileSource.kt`, `OpenFreeMapSource.kt`, `KoreanLabelStyle.kt`, `BikeMap.kt`
- Modify: `app/build.gradle.kts`(`org.maplibre.gl:android-sdk:13.6.1`)
- Test: `app/src/test/java/com/cowork/bikerecoder/map/KoreanLabelStyleTest.kt`

**Interfaces:**
- Produces:
```kotlin
interface TileSource { val styleUrl: String; val attribution: String; suspend fun styleJson(): String }
class OpenFreeMapSource(client: OkHttpClient, override val styleUrl: String = "https://tiles.openfreemap.org/styles/liberty") : TileSource
// styleJson(): 원본 스타일을 받아 KoreanLabelStyle.apply 적용, 메모리 캐시
object KoreanLabelStyle { fun apply(styleJson: String): String }
data class MapOverlay(val route: Route?, val stops: List<Stop>, val user: LocationFix?)
enum class CameraMode { FREE, FOLLOW }   // FOLLOW: zoom 17, tilt 50, bearing = 진행 방향
@Composable fun BikeMap(tileSource: TileSource, overlay: MapOverlay, cameraMode: CameraMode,
                        onLongPress: (GeoPoint) -> Unit, onUserGesture: () -> Unit, modifier: Modifier = Modifier)
```
- `KoreanLabelStyle.apply`: `layers[]` 중 `layout["text-field"]`가 `name`/`name:latin`/`name:nonlatin`/`name_en`을 참조하는 경우에만 `["coalesce", ["get","name:ko"], ["get","name"]]`로 교체. `{ref}` 등 다른 필드는 유지.
- `BikeMap`: `AndroidView`로 `MapView` 생성, 수명주기(onStart/onResume/onPause/onStop/onDestroy)를 `LocalLifecycleOwner`에 연결. `Style.Builder().fromJson(styleJson)`. 경로선은 GeoJsonSource `route` + LineLayer(색 `#1E88E5`, 폭 6), 경유지는 SymbolLayer/CircleLayer, 사용자 위치는 CircleLayer(파랑 원 + 흰 테두리). 길게 누르기 `addOnMapLongClickListener`. 지도 제스처 시작(`addOnMoveListener.onMoveBegin`) → `onUserGesture()`. 화면 하단에 `attribution` 텍스트 표시.
- `BikeApp`(Application)에서 `MapLibre.getInstance(this)`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `replaces name based text field`()   // {"layers":[{"id":"place","layout":{"text-field":["get","name:latin"]}}]} → text-field == ["coalesce",["get","name:ko"],["get","name"]]
@Test fun `keeps ref text field`()              // "text-field":"{ref}" 유지
@Test fun `leaves layers without layout`()
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** (`kotlinx.serialization.json.JsonElement` 트리 변환)
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest --tests '*KoreanLabelStyleTest*' :app:assembleDebug` → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: maplibre map with korean labels and tile source abstraction"`

---

### Task 16: 위치·음성 출력

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/location/LocationSource.kt`, `FusedLocationSource.kt`, `GpxLocationSource.kt`, `app/src/main/java/com/cowork/bikerecoder/tts/VoiceOutput.kt`, `AndroidTtsVoiceOutput.kt`
- Modify: `app/build.gradle.kts`(play-services-location)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/location/GpxLocationSourceTest.kt`

**Interfaces:**
- Consumes: `LocationFix`, `GpxParser`(Task 9)
- Produces:
```kotlin
interface LocationSource { fun fixes(): Flow<LocationFix> }
class FusedLocationSource(context: Context) : LocationSource       // PRIORITY_HIGH_ACCURACY, 1_000ms, minUpdateDistance 0
class GpxLocationSource(fixes: List<LocationFix>, speedup: Double = 1.0, clock: () -> Long = System::currentTimeMillis) : LocationSource
// 원래 시간 간격 / speedup 만큼 delay, 방출하는 fix의 timeMillis는 원래 값 유지
interface VoiceOutput { val available: StateFlow<Boolean>; var muted: Boolean; fun speak(text: String); fun shutdown() }
class AndroidTtsVoiceOutput(context: Context) : VoiceOutput
```
- TTS: 엔진 `"com.samsung.SMT"`로 초기화 → `isLanguageAvailable(Locale.KOREAN) >= LANG_AVAILABLE`가 아니면 `"com.google.android.tts"` → 둘 다 실패면 `available=false`. `setAudioAttributes(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH)`. `speak`마다 `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` 요청 후 `QUEUE_ADD`, 대기열이 비면(`onDone`) 포커스 반환. `muted`면 버린다(모아두지 않음).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun gpxSourceEmitsAllFixesInOrderWithSpeedup()  // 3점 10초 간격, speedup 100 → 400ms 안에 3개, timeMillis 원본 유지
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** (Fused는 `callbackFlow` + `requestLocationUpdates`, `Location.accuracy/hasSpeed/hasBearing` → null 처리)
- [ ] **Step 4: 통과 확인** — `./gradlew :app:connectedDebugAndroidTest --tests '*GpxLocationSourceTest*'` → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: location sources and korean tts output"`

---

### Task 17: 앱 뼈대, 의존성 구성, 온보딩

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/BikeApp.kt`, `AppContainer.kt`, `ui/BikeNavHost.kt`, `ui/onboarding/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/onboarding/OnboardingSteps.kt`
- Modify: `MainActivity.kt`, `AndroidManifest.xml`(권한, `android:name=".BikeApp"`)
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/onboarding/OnboardingStepsTest.kt`

**Interfaces:**
- Consumes: Task 11–16의 구현체
- Produces:
```kotlin
class AppContainer(context: Context) {
    val okHttp: OkHttpClient; val db: AppDatabase; val settings: SettingsRepository
    val segments: SegmentRepository            // dir = filesDir/segments4
    val router: Router                         // BRouterRouter(filesDir/segments4, ProfileInstaller.install(filesDir/brouter-profiles))
    val placeSearch: PlaceSearch               // KakaoLocalClient(okHttp, BuildConfig.KAKAO_REST_API_KEY)
    val tileSource: TileSource
    val tripStore: TripStore; val offline: OfflineMapController; val tripManager: TripManager
    val voice: VoiceOutput
    var locationSourceFactory: () -> LocationSource     // 기본 FusedLocationSource, 테스트에서 교체
    val navigation: NavigationController                 // Task 19
}
enum class OnboardingStep { FINE_LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY, SEGMENTS, DONE }
data class PermissionState(val fine: Boolean, val background: Boolean, val notifications: Boolean, val batteryExempt: Boolean, val segmentsReady: Boolean)
fun nextOnboardingStep(s: PermissionState): OnboardingStep
```
- 매니페스트 권한: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- 온보딩 화면: 단계마다 제목·설명 한 줄·[허용] 버튼. 백그라운드 위치는 정밀 위치 허용 후 별도 요청(시스템이 "항상 허용" 설정 화면으로 보냄). 배터리는 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. SEGMENTS 단계: 필수 2개 파일 다운로드 버튼, 진행률(MB), 와이파이 권장 문구, [나중에]를 누르면 DONE으로 가되 경로 계산 버튼 비활성화.
  - 설명 문구: 정밀 위치 "현재 위치와 길안내에 필요합니다" / 백그라운드 "화면이 꺼져도 길안내를 계속하려면 '항상 허용'이 필요합니다" / 알림 "길안내 중 상태를 알림으로 보여줍니다" / 배터리 "장시간 안내 중 앱이 종료되지 않도록 합니다" / 경로 데이터 "자전거 경로 계산용 지도 데이터(약 80MB)를 받습니다. 와이파이를 권장합니다".
- 내비 그래프: `onboarding`, `main`, `search`, `plan`, `navigate`, `settings`. 시작 목적지 = `nextOnboardingStep(...) == DONE`이면 `main`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun `order of steps`() {
    assertEquals(FINE_LOCATION, nextOnboardingStep(PermissionState(false,false,false,false,false)))
    assertEquals(BACKGROUND_LOCATION, nextOnboardingStep(PermissionState(true,false,false,false,false)))
    assertEquals(NOTIFICATIONS, nextOnboardingStep(PermissionState(true,true,false,false,false)))
    assertEquals(BATTERY, nextOnboardingStep(PermissionState(true,true,true,false,false)))
    assertEquals(SEGMENTS, nextOnboardingStep(PermissionState(true,true,true,true,false)))
    assertEquals(DONE, nextOnboardingStep(PermissionState(true,true,true,true,true)))
}
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 이 Task의 `AppContainer`에는 `navigation`을 넣지 않고(Task 19에서 추가), `offline`은 아무 일도 하지 않는 `NoopOfflineMapController`로 둔다(Task 20에서 교체).
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS. 에뮬레이터에 설치해 온보딩 5단계가 차례로 나오는지 눈으로 확인.
- [ ] **Step 5: Commit** — `git commit -m "app: app container, nav graph, permission onboarding"`

---

### Task 18: 지도 메인, 검색, 경로 계획 화면

**Files:**
- Create: `ui/main/MainScreen.kt`, `ui/main/MainViewModel.kt`, `ui/search/SearchScreen.kt`, `ui/search/SearchViewModel.kt`, `ui/plan/PlanScreen.kt`, `ui/plan/PlanViewModel.kt`, `ui/plan/PlanState.kt`, `core/.../format/SummaryFormatter.kt`
- Test: `core/src/test/.../format/SummaryFormatterTest.kt`, `app/src/test/.../ui/plan/PlanViewModelTest.kt`, `app/src/androidTest/.../ui/plan/PlanScreenTest.kt`

**Interfaces:**
- Consumes: `PlaceSearch`, `Router`, `SettingsRepository`, `TripManager`, `BikeMap`
- Produces:
```kotlin
object SummaryFormatter {
    fun distance(m: Double): String            // "87.4km"
    fun duration(m: Double, speedKmh: Double = 15.0): String   // "약 5시간 50분" / 1시간 미만 "약 25분"
    fun ascentAndRatio(ascentM: Int, ratio: Double): String     // "오르막 420m · 자전거도로 78%"
}
data class PlannedStop(val key: Long, val name: String, val point: GeoPoint)   // 마지막 항목 = 목적지
sealed interface RouteUiState { data object Idle; data object Loading; data class Ready(val route: Route); data class Error(val message: String) }
data class PlanUiState(val stops: List<PlannedStop>, val profile: RouteProfile, val route: RouteUiState, val canStart: Boolean)
class PlanViewModel(router: Router, settings: SettingsRepository, currentLocation: () -> GeoPoint?, segmentsReady: () -> Boolean) : ViewModel {
    val state: StateFlow<PlanUiState>
    fun setDestination(name: String, p: GeoPoint); fun addWaypoint(name: String, p: GeoPoint)
    fun remove(key: Long); fun move(from: Int, to: Int); fun setProfile(p: RouteProfile)
}
```
- 변경할 때마다 300ms 디바운스 후 재계산, 이전 계산은 취소. 경유지는 목적지 앞에 추가.
- 오류 문구: 경로 데이터 없음 → "경로 데이터가 없습니다. 설정에서 내려받으세요", `NO_ROUTE` → "경로를 찾을 수 없습니다", `TIMEOUT` → "경로 계산 시간이 너무 깁니다. 경유지를 추가해 주세요", 목적지가 현재 위치 50m 이내 → "목적지가 현재 위치와 너무 가깝습니다", 현재 위치 없음 → "현재 위치를 확인하는 중입니다".
- 메인 화면: 상단 검색창(누르면 `search`), 지도 길게 누르기 → 하단 시트(`placeSearch.addressOf`로 주소, [목적지로 설정] [경유지로 추가]) → `plan`. 진행 중 여러 날 여행 배너 "🚩 {목적지 이름} · 여러 날 {dayNumber}일차 [이어서 안내]". 우측 상단 설정 아이콘. 앱 시작 시 `tripManager.stalePrompt()`가 있으면 대화상자 "{목적지} 여행이 3일 이상 멈춰 있습니다. 완료 처리할까요?" [완료] [계속 유지].
- 검색 화면: 입력 400ms 디바운스, 결과 목록(이름, 주소, 거리), 오류는 `SearchError.messageKo`, 결과 선택 → 메인의 하단 시트와 같은 선택지.
- 계획 화면: 스펙 4.4 레이아웃. 끌어서 순서 변경은 길게 눌러 드래그(`LazyColumn` + `detectDragGesturesAfterLongPress`), 성향 칩 3개(`자전거도로 최우선`, `균형`, `최단거리`), 요약 2줄, [안내 시작](`canStart` = Ready && segmentsReady).

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// SummaryFormatterTest
@Test fun formats() {
    assertEquals("87.4km", SummaryFormatter.distance(87_400.0))
    assertEquals("약 5시간 50분", SummaryFormatter.duration(87_400.0))
    assertEquals("약 20분", SummaryFormatter.duration(5_000.0))
    assertEquals("오르막 420m · 자전거도로 78%", SummaryFormatter.ascentAndRatio(420, 0.781))
}
// PlanViewModelTest (FakeRouter, StandardTestDispatcher)
@Test fun `recomputes after profile change`()          // setProfile → 300ms 후 router.lastRequest.profile == SHORTEST
@Test fun `waypoint inserted before destination`()     // setDestination(A), addWaypoint(B) → stops == [B, A]
@Test fun `move reorders`()                            // [B, C, A] move(1,0) → [C, B, A]
@Test fun `destination within 50m shows error`()       // 현재 위치에서 30m → Error("목적지가 현재 위치와 너무 가깝습니다"), router 호출 없음
@Test fun `no segments disables start`()               // Ready여도 segmentsReady=false → canStart false
// PlanScreenTest (Compose, FakeRouter)
@Test fun addReorderDeleteAndSwitchProfile()           // 텍스트 노드로 목록 순서 확인, 칩 클릭 후 요약 텍스트 갱신
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test :app:testDebugUnitTest` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest --tests '*PlanScreenTest*'` → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: main, search and route planning screens"`

---

### Task 19: 안내 실행 (서비스, 화면, 여행 흐름)

**Files:**
- Create: `nav/NavigationController.kt`, `nav/NavigationService.kt`, `nav/NavNotifications.kt`, `ui/navigate/NavigateScreen.kt`, `ui/navigate/NavigateViewModel.kt`, `ui/trip/TripDialogs.kt`
- Modify: `AndroidManifest.xml`(`<service android:name=".nav.NavigationService" android:foregroundServiceType="location" android:exported="false"/>`), `AppContainer.kt`, `ui/plan/PlanScreen.kt`([안내 시작] 연결), `ui/main/MainScreen.kt`([이어서 안내] 연결)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/nav/NavigationScenarioTest.kt`, `app/src/androidTest/assets/{route_follow.geojson, route_reroute.geojson, scenario_follow.gpx, scenario_deviate.gpx, scenario_spike.gpx, scenario_waypoint.gpx}`

**Interfaces:**
- Consumes: `NavigationSession`(Task 9), `TripManager`(Task 8), `Router`, `LocationSource`, `VoiceOutput`, `OfflineMapController`(Task 20에서 실제 구현, 이 Task에서는 AppContainer에 임시 no-op 구현 `NoopOfflineMapController`를 두고 Task 20에서 교체)
- Produces:
```kotlin
sealed interface NavUiState { data object Idle; data class Active(val tripId: Long, val state: NavState, val muted: Boolean); data class Finished(val tripId: Long, val type: TripType, val reason: FinishReason) }
enum class FinishReason { ARRIVED, STOPPED_TODAY, COMPLETED }
class NavigationController(container: AppContainer, scope: CoroutineScope) {
    val ui: StateFlow<NavUiState>
    suspend fun start(tripId: Long)          // 미도달 stops로 현재 위치에서 경로 계산 → 세션 시작 → offline.downloadForTrip
    fun stopToday()                          // MULTI_DAY: tripManager.endToday, 세션 종료
    suspend fun completeTrip()               // tripManager.complete, 세션 종료
    fun setMuted(m: Boolean)
}
```
- 흐름:
  - [안내 시작] → `tripManager.startPrompt()`: `AskType` → 대화상자 "오늘 하루 일정인가요, 여러 날 일정인가요?" [당일](기본) [여러 날]; `AskContinue(trip)` → "{목적지} 여행을 이어서 진행할까요?" [이어서] → `changeStops`(계획이 바뀐 경우) 후 그 trip으로 / [새로 시작] → `startTrip`(이전 trip 자동 완료).
  - 그 뒤 `ContextCompat.startForegroundService`(**액티비티가 보이는 상태에서만**) → 서비스 `onStartCommand`에서 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)` 후 `controller.start(tripId)`.
  - 세션 업데이트마다: utterances → `voice.speak`(설정 `voiceEnabled`가 false면 생략), `StopReached` → `tripManager.markVisited`; 목적지면 `completeTrip()` 후 `Finished(ARRIVED)`; `touch` 1분마다.
  - 안내 화면 [종료]: SINGLE_DAY → "안내를 끝낼까요?" [끝내기] → `completeTrip()`; MULTI_DAY → [오늘은 여기까지] → `stopToday()` / [여행 완료] → `completeTrip()`.
  - 완료 화면(SINGLE_DAY): "여행을 완료했습니다" + [여러 날로 바꾸기](`convertToMultiDay`) + [확인].
  - 서비스가 `intent == null`로 재시작(START_STICKY)되면: 알림 "안내가 중단되었습니다. 눌러서 재개"(PendingIntent → `MainActivity`, extra `resume_trip_id`) 후 `stopSelf()`. 액티비티가 extra를 받으면 `navigate`로 이동해 `start(tripId)`.
  - 알림 채널 `navigation`(중요도 LOW), 진행 알림 내용 "{다음 안내} · 남은 {거리}".
- 안내 화면: 스펙 4.5 레이아웃. `CameraMode.FOLLOW` 기본, 제스처 시 FREE + [현재 위치로]. `keepScreenOn` 설정이면 `view.keepScreenOn = true`. 회전 아이콘은 `TurnType`별 Material 아이콘(`TurnLeft`, `TurnRight`, `TurnSlightLeft`, `TurnSlightRight`, `TurnSharpLeft`, `TurnSharpRight`, `UTurnLeft`, `Roundabout`, `Straight`, `ForkLeft`, `ForkRight`). GPS 약함이면 상단 배너 "GPS 신호 약함". `voice.available == false`면 최초 1회 "한국어 음성(TTS)을 사용할 수 없습니다. 설정 > 일반 관리 > 텍스트 음성 변환에서 한국어를 설치하세요" 스낵바.

- [ ] **Step 1: 실패하는 테스트** — `AppContainer`의 `locationSourceFactory`를 `GpxLocationSource(speedup=20)`로, `voice`를 발화 기록용 `RecordingVoiceOutput`으로, `router`를 assets GeoJSON을 `BRouterGeoJsonParser`로 읽어 돌려주는 `AssetRouter`로 교체; DB는 in-memory.

```kotlin
@Test fun followScenarioAnnouncesTurnsKmAndArrival()      // ① 발화 목록: 회전 안내 순서 일치, kmReport 수 == floor(거리 km), 마지막 "목적지에 도착했습니다", trip COMPLETED
@Test fun deviationScenarioReroutesExactlyOnce()          // ② "경로를 벗어났습니다. 다시 탐색합니다" 1회, AssetRouter 호출 2회(최초+재탐색)
@Test fun spikeScenarioDoesNotReroute()                   // ③ AssetRouter 호출 1회
@Test fun waypointScenarioMarksVisited()                  // ④ 경유지 visitedAt != null
@Test fun resumeAfterRecreateUsesRemainingStops()         // ⑤ 컨트롤러 폐기·재생성 후 start(tripId) → 마지막 RouteRequest.stops == 미방문 2개
@Test fun tripTransitions()                               // ⑥ 당일→도착 COMPLETED / 여러 날→stopToday ACTIVE→start→completeTrip COMPLETED / convertToMultiDay ACTIVE
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:connectedDebugAndroidTest --tests '*NavigationScenarioTest*'` → FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. 에뮬레이터 Extended Controls → Location → Routes에 `scenario_follow.gpx`를 넣고 실제 앱으로 안내를 따라가 화면·음성을 눈으로 확인.
- [ ] **Step 5: Commit** — `git commit -m "app: navigation service, screen and trip flow"`

---

### Task 20: 경로 주변 지도 오프라인 다운로드

**Files:**
- Create: `offline/MapLibreOfflineController.kt`, `offline/NetworkWaiter.kt`
- Modify: `AppContainer.kt`(Noop 교체, `OfflineManager.setMaximumAmbientCacheSize(100L * 1024 * 1024)`), `ui/navigate/NavigateScreen.kt`(상단 다운로드 진행 표시)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/offline/MapLibreOfflineControllerTest.kt`

**Interfaces:**
- Consumes: `OfflineMapController`(Task 8), `CorridorPlanner`(Task 7), `TileSource`, `OfflineRegionRefDao`(Task 12), `SettingsRepository`
- Produces:
```kotlin
class MapLibreOfflineController(context: Context, tileSource: TileSource, refs: OfflineRegionRefDao,
                                settings: SettingsRepository, network: NetworkWaiter, clock: () -> Long) : OfflineMapController {
    val progress: StateFlow<OfflineProgress?>
}
data class OfflineProgress(val tripId: Long, val completed: Long, val required: Long, val estimatedBytes: Long)
class NetworkWaiter(context: Context) { suspend fun awaitUnmetered() }   // NET_CAPABILITY_NOT_METERED 콜백
```
- `downloadForTrip`: 해당 trip의 지역이 이미 있고 그 지역이 잘리지 않은(`truncated=false`) 것이면 생략한다. 잘린 지역이었다면 기존 지역을 지우고 이번 경로·현재 위치 기준으로 다시 받는다(다음 날 [이어서 안내] 때 다음 150km를 받는 동작). `CorridorPlanner.plan(route, fromDistanceAlongM)` → `OfflineGeometryRegionDefinition(tileSource.styleUrl, polygon→org.maplibre.geojson MultiPolygon, 10.0, 14.0, pixelRatio, false)` + `OfflineTilePyramidRegionDefinition(styleUrl, overview→LatLngBounds, 5.0, 9.0, pixelRatio)`. 메타데이터 `{"tripId":N,"kind":"CORRIDOR"|"OVERVIEW","truncated":true|false}`(UTF-8 바이트). 생성 후 `setDownloadState(STATE_ACTIVE)`, 완료 시 `STATE_INACTIVE`. `OfflineRegionRefEntity` 저장. 시작 전 예상 용량(타일 수 × 15KB)을 progress로 노출. `wifiOnlyOfflineMaps`면 `awaitUnmetered()` 후 시작.
- `deleteForTrip`: refs의 regionId로 `OfflineManager.listOfflineRegions`에서 찾아 `delete`, refs 삭제.

- [ ] **Step 1: 실패하는 테스트** (네트워크 필요, 짧은 1km 경로)

```kotlin
@Test fun downloadCreatesTwoRegionsAndRefs()     // CORRIDOR + OVERVIEW, refs 2개, 지역 상태 complete
@Test fun deleteRemovesRegionsAndRefs()           // deleteForTrip 후 listOfflineRegions에 tripId 메타데이터 없음, refs 0
@Test fun secondDownloadForSameTripIsSkipped()    // 두 번 호출해도 지역 2개
@Test fun truncatedRegionIsReplacedOnNextDownload() // maxTiles를 낮춰 truncated로 받은 뒤 다시 호출 → 이전 지역 삭제, 새 지역 2개
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:connectedDebugAndroidTest --tests '*MapLibreOfflineControllerTest*'` → FAIL
- [ ] **Step 3: 구현** (MapLibre 콜백을 `suspendCancellableCoroutine`으로 감쌈)
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: corridor offline map download and cleanup"`

---

### Task 21: 설정 화면, 전체 검증, 실주행 체크리스트

**Files:**
- Create: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `docs/verification/navigation-field-test.md`
- Modify: `README.md`(검증 절차·AVD 준비), `CHANGELOG.md`(검증 통과 시)
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository`, `SegmentRepository`, `MapLibreOfflineController`, `AppVersion`

- [ ] **Step 1: 테스트 기기 준비** (Task 12보다 먼저 필요하면 그때 수행)

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-37.0;google_apis;x86_64"
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n bike37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_8
$ANDROID_HOME/emulator/emulator -avd bike37 -no-snapshot &
```
또는 Galaxy S26을 USB 디버깅으로 연결(`adb devices`에 표시). README의 테스트 절차에 같은 내용을 추가한다.

- [ ] **Step 2: 실패하는 테스트**

```kotlin
@Test fun showsVersionAndTogglesVoice()   // "V1.0.0" 텍스트 표시, [음성 안내] 스위치 토글 → settings.voiceEnabled 반전
```

- [ ] **Step 3: 설정 화면 구현** — 스펙 4.8 항목: 기본 경로 성향(3개 중 선택), 음성 안내, 안내 중 화면 켜짐 유지, 와이파이에서만 지도 받기, 경로 데이터(파일별 설치 여부·크기·[업데이트 확인]·[삭제]·선택 파일 [받기]), 내려받은 지도 용량(`OfflineManager` 지역 합계)과 [모두 삭제], 앱 버전 `AppVersion.display(BuildConfig.VERSION_NAME)`, 출처 표기(OpenFreeMap·OpenStreetMap·BRouter MIT).

- [ ] **Step 4: 통과 확인** — `./gradlew :app:connectedDebugAndroidTest --tests '*SettingsScreenTest*'` → PASS

- [ ] **Step 5: 전체 자동 검증 실행 후 출력 보관**

```bash
./gradlew :core:test :routing-brouter:test :routing-brouter:integrationTest :app:testDebugUnitTest :app:connectedDebugAndroidTest
```
Expected: BUILD SUCCESSFUL, 실패 0. 요약(테스트 수·실패 수)을 `docs/verification/navigation-field-test.md` 상단 "자동 검증" 절에 날짜와 함께 기록.

- [ ] **Step 6: 실주행 체크리스트 문서 작성** — 스펙 7.4의 10개 항목을 표(번호·항목·합격 기준·결과[ ]·메모)로 옮기고, 기기 정보(Galaxy S26, Android 17, 앱 버전) 칸과 측정 방법(배터리: 시작/종료 % 기록, ETA: 1km 안내 시 예정 시각과 실제 도착 시각 기록)을 적는다.

- [ ] **Step 7: Commit** — `git commit -m "app: settings screen and verification docs"`

- [ ] **Step 8: 사용자 실주행 → 확정** (사용자 수행) — 체크리스트 전 항목 합격 시 `CHANGELOG.md`의 `[1.0.0] - 개발 중`을 `[1.0.0] - YYYY-MM-DD`로 바꾸고 "추가 (예정)"을 "추가"로, 릴리스 빌드 확인 후:

```bash
git commit -am "release: v1.0.0" && git tag v1.0.0
```
실패 항목은 원인 수정 + 회귀 테스트 추가 후 해당 항목 재검증(스펙 7.5).
