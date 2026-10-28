# 영상 다듬기 (V1.3.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V1.3.0 영상과 요약 이미지를 브이월드 위성 지도, 움직이는 가까운 카메라, 빛나는 경로·큰 숫자·고도 그래프·새 카드로 다시 그린다.

**Architecture:**
- 순수 계산(카메라, 자동 제목, 고도 그래프, 데이터 추정)은 `:core` `core.video`에 둔다.
- 앱 `video` 패키지는 세 가지를 한다.
  - 전용 지도 스타일(위성 래스터 + OpenFreeMap 글자)을 `TileSource`로 감싸 지금의 `MapSnapshots`에 넘긴다.
  - 렌더러의 그리기를 바꾼다.
  - `VideoJob`이 시작 검사에서 위성 한 조각을 시험한 뒤 위성 또는 일반 지도를 고르고, 지역 이름을 붙이고, 받은 데이터를 잰다.
- 저장, 서비스, DB는 바꾸지 않는다.

**Tech Stack:** Kotlin 2.4.20, MapLibre Native 13.6.1 (MapSnapshotter), Android Canvas, OkHttp, kotlinx.serialization, DataStore, JUnit5(:core)/JUnit4(:app), MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-28-video-polish-design.md`. 각 작업의 수치와 문구는 스펙이 기준이며, 여기서는 § 번호로 가리킨다.

## Global Constraints

- **버전:** 1.3.1, versionCode 10301(`verPatch = 1`), debug `1.3.1-dev`, 화면 `V1.3.1`.
- **사용자에게 보이는 글은 모두 한국어**이고 스펙 문구 그대로 쓴다.
  - "위성 지도를 받지 못해 일반 지도로 만들었습니다"
  - "위성 지도 데이터 약 N MB가 듭니다 (Wi-Fi 권장)"
  - 출처 "© 브이월드 · OpenStreetMap"
- **브이월드 키:** `secrets.json`의 `vworldApiKey` → `BuildConfig.VWORLD_API_KEY`. 키를 로그·예외 메시지·커밋에 넣지 않는다. 시험은 가짜 키(`TESTKEY`)를 쓴다.
- **브이월드 주소:** `https://api.vworld.kr/req/wmts/1.0.0/{key}/Satellite/{z}/{y}/{x}.jpeg`, 타일 256 px, 확대 6~19.
- **픽셀 비율:** 1을 유지한다(`MapLibreOfflineController.VIDEO_PIXEL_RATIO`).
- **스레드 규칙(V1.3.0):** MapSnapshotter 생성·start·cancel·투영은 Main에서, 프레임 Canvas에는 소프트웨어 비트맵만 쓴다(V7).
- **크기 단위:** 1u = 짧은 변 / 360. 720p 대체에서도 같은 공식을 쓴다.
- **기기 규칙:**
  - connected 시험은 S22 `R5CT43TFS9A`에서만, 클래스 하나씩, 화면을 깨운 뒤 `ANDROID_SERIAL=R5CT43TFS9A ... </dev/null`로 돌린다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정을 바꾸지 않는다.
- **커밋 꼬리말:** 그 커밋을 쓴 모델 이름으로 `Co-Authored-By: Claude <모델> <noreply@anthropic.com>`.

## Review Focus

1. **아주 짧은 주행**(점 1개, 120 m 미만, 일시정지만 길게)에서도 카메라·앞당김·속도 창이 NaN 없이 정해진 범위 안에 있어야 한다. 작업 1 `tinyAndStoppedRidesStayFinite`.
2. **브이월드가 200인데 이미지가 아닌 응답**(키 오류 안내 XML/HTML)을 보내도 위성으로 판정하면 안 된다. 일반 지도로 대신 만든다. 작업 3 `probeRejectsNonImage200`.
3. **카카오 지역 조회가 느리거나 실패**해도 작업이 5초 넘게 멈추거나 실패하면 안 된다. 제목은 지역 없이 나온다. 작업 6 `slowRegionLookupGivesTitleWithoutRegion`.
4. **심박 색 경로**에서도 번짐과 꼬리가 각 지점 색을 따라야 한다(주황 고정이면 안 됨). 작업 4 `heartRateColoursGlowInTheirOwnColour`.
5. **720p 대체 크기**에서도 숫자 띠, 고도 그래프, 카드가 화면 안에 들어가고 겹치지 않아야 한다. 작업 4 `layoutFitsAt720`.

---

### Task 1: 움직이는 카메라 (`:core`)

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CameraPath.kt`, `VideoTimeline.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/CameraPathTest.kt`, `VideoTimelineTest.kt`

**Interfaces:**
- Consumes: `RideTimeline`(`points`, `at`, `routeDistance`, `totalMoveMs`), `FramePlan`(`kind`, `moveMs`, `sceneProgress`), `Camera`.
- Produces:
  - `CameraPath.cameras(plans, timeline, overview: Camera, outro: Camera = overview): List<Camera>`: 시그니처는 그대로, 동작만 §2.
  - 새 상수: `TILT = 60.0`, `ZOOM_SLOW = 17.4`, `ZOOM_FAST = 16.6`, `SLOW_KMH = 10.0`, `FAST_KMH = 30.0`, `ZOOM_RATE_PER_S = 0.25`, `SPEED_WINDOW_MS = 8_000L`, `LOOK_AHEAD_M = 120.0`, `MAX_TURN_DEG_PER_S = 45.0`, `SWAY_DEG = 8.0`, `SWAY_PERIOD_S = 16.0`, `JUMP_M = 500.0`, `PHOTO_ORBIT_DEG = 40.0`, `MARKER_FROM_TOP = 2.0 / 3`.
  - `ZOOM`는 지운다. 쓰던 곳은 `ZOOM_SLOW`로 바꾼다.
  - `fun zoomForSpeed(kmh: Double): Double`
  - `fun markerLeadM(zoom: Double, tiltDeg: Double, heightPx: Int, latDeg: Double, markerFromTop: Double = MARKER_FROM_TOP): Double`
  - `VideoTimeline`: `OVERVIEW_FRAMES = 29`, `DESCENT_FRAMES = 43`, 새 `OUTRO_RISE_FRAMES = 36`, `OUTRO_CARD_FROM = 29`, `OUTRO_CARD_FRAMES = 6`. 시작 합계 72장은 그대로다.

- [ ] **Step 1: 실패하는 시험 쓰기.** 모두 `CameraPathTest`:
  - `rideZoomFollowsSpeed`: `zoomForSpeed(5.0) == 17.4`, `zoomForSpeed(10.0) == 17.4`, `zoomForSpeed(20.0) == 17.0`(±1e-9), `zoomForSpeed(40.0) == 16.6`.
  - `rideZoomChangesSlowly`: 10 km/h 구간 뒤 30 km/h 구간이 이어지는 경로에서, 연속한 주행 프레임의 `abs(Δzoom) <= 0.25 / 24 + 1e-9`이고 모든 주행 확대가 [16.6, 17.4] 안이다.
  - `turnsAreCappedAndSwayIsEightDegrees`: 직각으로 꺾이는 경로에서 프레임 사이 방향 변화가 `45.0/24 + 흔들기 변화 최대치(8·2π/16/24) + 1e-6` 이하다. 긴 직선에서 (방향 − 경로 방향)의 최대가 8±0.3°, 최소가 −8±0.3°다.
  - `markerSitsInTheLowerThird`: `markerLeadM(17.2, 60.0, 1920, 37.5)`가 200~245 m다(식: 픽셀 앞당김 = 1.5h·(sin p − cos p·tan(p − atan((m − 0.5)·h / 1.5h))), 미터 = 픽셀 × 40075016.686·cos(lat) / (512·2^zoom)). 주행 카메라 중심이 현재 위치에서 방위 방향으로 그 거리만큼 앞에 있다(±1 m).
  - `introLooksDownThenSpiralsIn`(규칙 F4: 제목이 떠 있는 동안 돌지 않고, 내려가기만 짧은 쪽으로 돈다. `INTRO_TURN_DEG`는 없다):
    - 하늘 위 장면의 모든 장: 기울기 0, 방향 0(정북 그대로), 확대 = overview.zoom − 1
    - 내려가기: 방향은 정북에서 첫 주행 방향까지 짧은 쪽(|돌기| ≤ 180°)으로 한 방향으로만 smoothstep으로 돈다
    - 내려가기 마지막 장: 첫 주행 카메라와 같음(중심 ±0.5 m, 확대·기울기·방향 ±1e-6)
    - 내려가기 중 기울기·확대는 줄지 않음
  - `photoSceneOrbitsFortyDegrees`: 사진 장면 첫 장과 마지막 장의 방향 차이가 40±0.5°이고 확대·기울기는 직전 주행 장과 같다.
  - `outroRisesToTheOutroCamera`: 끝 장면 35번째(0부터)는 `outro`와 같고, 36번째 이후 모두 `outro`다. 0번째는 마지막 주행 카메라에서 smoothstep(0)이다.
  - `jumpsRestartSmoothing`: 점이 600 m 떨어진 두 구간 경계 뒤 첫 주행 장의 방향이 그 위치의 목표 방향과 같고(평활 없음), 확대가 그 속도의 `zoomForSpeed`와 같다.
  - `tinyAndStoppedRidesStayFinite`(Review Focus 1): 점 1개 경로, 80 m 경로, 첫 10초 정지 경로 모두 모든 카메라 값이 유한하고 확대가 [overview.zoom − 1, 17.4], 기울기가 [0, 60] 안이다.
  - `VideoTimelineTest`의 프레임 수 단언은 29/43으로 고친다.
- [ ] **Step 2: 실행해 실패 확인.** `./gradlew :core:test --tests '*CameraPathTest*' --tests '*VideoTimelineTest*'`. 새 시험이 컴파일 오류나 단언 실패로 끝나야 한다.
- [ ] **Step 3: 구현.**
  - **속도:** (현재 moveMs의 경로 거리 − max(0, moveMs − 8000)의 경로 거리) / 창 길이(초).
  - **흔들기 시간:** 지금까지의 주행 프레임 수 / FPS.
  - **사진:** 그 moveMs의 주행 카메라를 (사진 시작 방향 + 40·sceneProgress)로 만든다. 사진이 끝나면 평활 기준 방향을 그 값으로 둔다.
  - **끝:** `interpolate(lastRide, outro, smoothstep(i / 35.0))`.
- [ ] **Step 4: 통과 확인.** `./gradlew :core:test`가 모두 PASS여야 한다.
- [ ] **Step 5: 커밋.** `core: closer, moving video camera — speed zoom, sway, spiral descent, photo orbit, rise`

### Task 2: 자동 제목, 고도 그래프, 위성 데이터 추정 (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/RideTitle.kt`, `ElevationGraph.kt`
- Modify: `VideoEstimate.kt`
- Test: `RideTitleTest.kt`, `ElevationGraphTest.kt`, `VideoEstimateTest.kt`

**Interfaces:**
- Produces:
  - `object RideTitle`
    - `fun base(firstStartedAt: Long, rideDays: Int, zone: ZoneId): String`: "저녁 라이딩" / "2일 여행"
    - `fun shortRegion(depth1: String, depth2: String): String?`: "서울 강서구". 둘 다 비면 null
    - `fun withRegion(base: String, region: String?): String`: "저녁 라이딩 · 서울 강서구"
  - `class ElevationGraph(val xs: DoubleArray, val ys: DoubleArray)`
    - xs: 0..1, 경로 거리 비율
    - ys: 0..1, 1 = 위
    - `companion fun of(timeline: RideTimeline): ElevationGraph?`
    - `fun doneFraction(located: Located, timeline: RideTimeline): Double`
  - `VideoEstimate.satelliteMb(length: VideoLength, frames: Int, bytesPerFrame: Double?): Int`: 실측이 없으면 SHORT 20 / NORMAL 35 / LONG 60, 있으면 ceil(bytesPerFrame × frames / 1_000_000).

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `RideTitleTest.timeOfDayBoundaries`: 서울 시각 03:59 → "밤 라이딩", 04:00 → "새벽", 06:59 → "새벽", 07:00 → "아침", 10:59 → "아침", 11:00 → "점심", 13:59 → "점심", 14:00 → "오후", 17:59 → "오후", 18:00 → "저녁", 20:59 → "저녁", 21:00 → "밤". 모두 "… 라이딩"이다.
  - `RideTitleTest.multiDay`: `base(t, 2, SEOUL) == "2일 여행"`.
  - `RideTitleTest.regionShortening`: 스펙 §3.8 표의 각 행을 확인한다. 예:
    - `("서울특별시","강서구") → "서울 강서구"`
    - `("경기도","성남시 분당구") → "경기 성남시 분당구"`
    - `("세종특별자치시","") → "세종"`
    - `("강원특별자치도","춘천시") → "강원 춘천시"`
    - `("전북특별자치도","전주시 완산구") → "전북 전주시 완산구"`
    - `("어딘가","") → "어딘가"`
    - `("","") → null`
  - `RideTitleTest.withRegion`: `withRegion("저녁 라이딩", null) == "저녁 라이딩"`.
  - `ElevationGraphTest`:
    - `flatRideIsLow`: 1 m 차이면 ys 최대 − 최소 < 0.1
    - `hundredMetreClimbFills`: 0→100 m면 최소 ≤ 0.05, 최대 ≥ 0.95
    - `missingAltitudeGivesNull`: 고도 있는 점이 1개면 null
    - `multiDayIsOneLine`: xs가 0에서 1까지 줄지 않음
    - 세로 범위 = max(실제, 40 m), 아래 여유 4 m
  - `VideoEstimateTest.satelliteMb`: 실측 null이면 20/35/60, `bytesPerFrame = 50_000.0`, 720장이면 36.
- [ ] **Step 2: 실패 확인.** `./gradlew :core:test`
- [ ] **Step 3: 구현.** 시각은 `Instant.ofEpochMilli(...).atZone(zone).hour`로 구한다.
- [ ] **Step 4: 통과 확인.** `./gradlew :core:test`가 PASS여야 한다.
- [ ] **Step 5: 커밋.** `core: ride title with region, elevation graph, satellite data estimate`

### Task 3: 위성 지도 스타일, 키, 시험 조각 (`:app`)

**Files:**
- Modify:
  - `app/build.gradle.kts`: `verPatch = 1`, `VWORLD_API_KEY` buildConfigField(카카오와 같은 읽기)
  - `offline/NetworkWaiter.kt`: `open fun isCellular(): Boolean`
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/video/VideoMapStyle.kt`
  - `video/SatelliteProbe.kt`
- Test:
  - JVM: `app/src/test/.../video/VideoMapStyleTest.kt`, `SatelliteProbeTest.kt`
  - 기기: `app/src/androidTest/.../video/SatelliteMapTest.kt`

**Interfaces:**
- Consumes: `TileSource`(`styleUrl`, `attribution`, `styleJson()`).
- Produces:
  - `object VideoMapStyle`
    - `fun build(baseStyleJson: String?, vworldKey: String): String`: 위성 래스터 레이어(소스 id `vworld`) + 글자 레이어 `v-place`, `v-road`
    - `const val ATTRIBUTION = "© 브이월드 · OpenStreetMap"`
  - `class SatelliteTileSource(private val base: TileSource, private val key: String) : TileSource`
    - `styleJson()` = `VideoMapStyle.build(runCatching { base.styleJson() }.getOrNull(), key)`
    - `attribution` = `VideoMapStyle.ATTRIBUTION`
    - `styleUrl` = `base.styleUrl`(오프라인 미리 받기용)
  - `class SatelliteProbe(client: OkHttpClient, key: String, baseUrl: HttpUrl = "https://api.vworld.kr".toHttpUrl())`
    - `suspend fun check(at: GeoPoint): Boolean`
    - 확대 17 타일 하나를 받아 10초 안에 200이고 `Content-Type`이 `image/`로 시작하고 본문 첫 두 바이트가 JPEG(`FF D8`) 또는 PNG(`89 50`)이면 true. 그 밖은 모두 false다(예외도 false, 로그에 키 없이).
  - `NetworkWaiter.isCellular()`: 활성 네트워크가 TRANSPORT_CELLULAR이고 TRANSPORT_WIFI가 아님.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `VideoMapStyleTest`:
    - `satelliteSourceCarriesTheKey`: 소스 `vworld`의 tiles[0] == 위 주소에 `TESTKEY`, tileSize 256, minzoom 6, maxzoom 19.
    - `onlyPlaceAndBigRoadLabels`: symbol 레이어가 `v-place`(source-layer `place`, filter class ∈ city/town/village/suburb/quarter/neighbourhood)와 `v-road`(source-layer `transportation_name`, class ∈ motorway/trunk/primary/secondary)뿐이다.
    - `labelPaint`: text-color `#FFFFFF`, halo `rgba(0,0,0,0.75)`, 폭 1.6.
    - `textSize`: place는 `["interpolate",["linear"],["zoom"],12,18,17,26]`, road는 20. 1.6배를 고정값으로 정한 것이다.
    - `fontsAndGlyphsFromBase`: 글꼴은 기본 스타일에서 처음 나오는 `text-font`의 Bold 변형(없으면 첫 값). glyphs와 벡터 소스는 기본 스타일 것이다.
    - `withoutBaseStillSatellite`: base null이면 래스터와 background만 있다.
  - `SatelliteProbeTest`(MockWebServer):
    - `jpeg200IsOk`
    - `probeRejectsNonImage200`(Review Focus 2): 200 `text/xml`, 그리고 200 `image/jpeg`인데 본문이 HTML인 경우 모두 false
    - `serverErrorAndTimeoutAreFalse`
    - `requestPathUsesTileOfThePoint`: 37.5585, 126.8280 → `/req/wmts/1.0.0/TESTKEY/Satellite/17/{y}/{x}.jpeg`. 기대값 x, y는 시험 안에서 메르카토르 식으로 계산한다.
  - 기기 `SatelliteMapTest`(실제 키, 인터넷 필요):
    - `satelliteFrameLooksLikeAPhoto`: 강서구 지점, `MapSnapshots(SatelliteTileSource)`로 1080×1920 카메라(17.2, 60°) 스냅샷을 찍는다. 밝은 회색·흰색(R,G,B 모두 > 200, 최대−최소 < 20) 픽셀 비율이 같은 카메라의 OpenFreeMap 스냅샷보다 25%p 이상 낮다(두 값을 보고서에 적는다).
    - `wrongKeyProbeFalse`: 잘못된 키 → `check` false.
    - 스크린샷은 scratchpad `sat-plain.png`, `sat-satellite.png`.
- [ ] **Step 2: 실패 확인.** `./gradlew :app:testDebugUnitTest --tests '*VideoMapStyleTest*' --tests '*SatelliteProbeTest*'`
- [ ] **Step 3: 구현.** 스타일은 kotlinx.serialization `JsonObject`로 만든다. 레이어 순서는 background `#1A1F1A` → raster `vworld` → `v-road` → `v-place`.
- [ ] **Step 4: 통과 확인.** JVM 시험, 그리고 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.bikerecoder.video.SatelliteMapTest </dev/null`
- [ ] **Step 5: 커밋.** `video: VWorld satellite style with upright place and road labels, satellite probe; version 1.3.1`

### Task 4: 영상 프레임 꾸밈 (`FrameRenderer`, `RouteOverlay`)

**Files:**
- Modify:
  - `app/src/main/java/com/cowork/bikerecoder/video/FrameRenderer.kt`, `RouteOverlay.kt`
  - `VideoInputs.kt`: `rideTitle: String` 추가. 로더가 `RideTitle.base(firstStartedAt, 탄 날 수, zone)`로 채운다.
- Test:
  - 기기 `FrameRendererTest.kt`(기존 + 새)
  - JVM `VideoInputsTest.kt`(rideTitle)
  - JVM `FrameRendererCellsTest.kt`(summaryCells, 순수)

**Interfaces:**
- Consumes: Task 1 카메라, Task 2 `ElevationGraph`·`RideTitle`, Task 3 `SatelliteTileSource`, `VideoMapStyle.ATTRIBUTION`.
- Produces:
  - `FrameRenderer`의 생성자·`render`·`outroCamera`·`outroCard`는 그대로다.
  - `summaryCells(totals, segmentCount)`: 6칸. 평균 심박이 없으면 "주행 N회", 칼로리가 없으면 상승 고도.
  - `RouteOverlay.draw(..., style: RouteStyle)`
    - `enum class RouteStyle { RIDE, TITLE, FULL }`: RIDE = 지나온·남은·꼬리, TITLE = 전체 0.7배 폭, FULL = 전체 지나온 길
  - `internal object FrameStyle`(그리기 상수)
    - `ROUTE_ORANGE = 0xFFFF7A1A`, `START_GREEN = 0xFF3CC85A`, `END_BLACK = 0xFF1E1E1E`, `HAZE = 0xFFBED2EB`, `HUD_DARK = 0xFF080C12`
    - 폭(u): 번짐 20, 테두리 10, 선 7, 꼬리 9, 남은 길 4, 꼬리 길이 150 m

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - JVM `FrameRendererCellsTest`:
    - `sixCellsWithFallbacks`: 심박 없음 → 5번째가 ("주행", "2회"), 칼로리 없음 → 6번째가 ("상승 고도", …), 둘 다 있으면 ("평균 심박", …), ("칼로리", …).
  - JVM `VideoInputsTest.rideTitleFromFirstStart`: 18:30 출발 당일 → "저녁 라이딩", 2일 → "2일 여행".
  - 기기 `FrameRendererTest`(OpenFreeMap 또는 위성. 시험은 `SatelliteTileSource`에 실제 키를 쓰고, 키가 비면 plain으로 건너뛴다):
    - `rideFrameHasGlowTailAndPulse`: 주행 프레임에서 주황(`#FF7A1A` ±24) 픽셀 수가, 구현 전에 같은 장면을 V1.3.0 렌더러로 그려 센 값(RED 단계에서 재서 상수로 적음)의 2배 이상이다. 현재 위치 주변 40u 안에 흰 픽셀 고리가 있다.
    - `markerInLowerThird`: 실제 `CameraPath.cameras`로 만든 주행 프레임에서 현재 위치 투영 y / 높이 ∈ [0.62, 0.72].
    - `hudGradientAndElevationGraph`:
      - 아래 27% 영역 맨 아래 줄은 평균 밝기 < 60, 그 영역 맨 위 줄은 지도와 비슷하다(밝기 차 < 30).
      - 고도 그래프 줄에 주황 채움이 있다.
      - 고도 없는 경로면 그래프 줄에 주황 채움이 없다.
    - `heartRateColoursGlowInTheirOwnColour`(Review Focus 4): 심박 색 옵션 프레임에서 번짐 영역의 색상이 주황 고정이 아니라 지점 색과 같은 색상 계열이다.
    - `titleCardThreeLines`: 하늘 위 장면 첫 장에서 가운데 아래 영역에 흰 글자와 주황 글자가 있다(픽셀 확인). 내려가기 5번째 장에는 없다.
    - `photoPopsInAndZoomsSlowly`: 사진 장면 3번째 장 카드 너비 > 0.85·최종, 30번째 장 카드 너비 = 최종(±2 px).
    - `outroCardSixCellsAndStartEndDots`: 끝 장면 40번째 장에서 카드 영역은 흰색 위주, 카드 위에 초록·검정 점 픽셀이 있다.
    - `hazeOnlyWhenTilted`: 기울기 60° 주행 장 맨 위 줄은 하늘색에 가깝고, 하늘 위 장면(기울기 0) 맨 위 줄은 아니다.
    - `layoutFitsAt720`(Review Focus 5): 720×1280에서 끝 카드·숫자 띠가 화면 안에 있고 서로 겹치지 않는다(박스 계산 단언).
    - 스크린샷: `v131-intro.png`, `v131-descent.png`, `v131-ride.png`, `v131-photo.png`, `v131-outro.png`를 scratchpad에 저장하고 직접 확인한다.
- [ ] **Step 2: 실패 확인.** JVM 시험이 실패하고, 기기 `FrameRendererTest`가 새 시험에서 실패해야 한다.
- [ ] **Step 3: 구현.**
  - **꾸밈:** §3.1~§3.7. 번짐은 `BlurMaskFilter(8u, NORMAL)`(소프트웨어 Canvas). 숨 쉬는 빛의 시간 = `plan.index / 24f`.
  - **출처:** 오른쪽 위 한 줄. 위성이면 `VideoMapStyle.ATTRIBUTION`, 아니면 `tileSource.attribution`.
  - **사진:** 나타남 7장, 사라짐 7장. 안쪽 확대는 소스 사각형을 줄여서 한다.
  - **제목 카드:** 내려가기 첫 4장 동안 알파 1→0.
  - **끝 카드:** `OUTRO_CARD_FROM`부터 `OUTRO_CARD_FRAMES`에 걸쳐 나타난다.
- [ ] **Step 4: 통과 확인.**
  - `./gradlew :app:testDebugUnitTest`
  - S22: `FrameRendererTest`
- [ ] **Step 5: 커밋.** `video: glowing route with tail, pulsing marker, haze, gradient HUD with elevation graph, title and outro cards`

### Task 5: 요약 이미지 꾸밈 (`SummaryImageRenderer`)

**Files:**
- Modify:
  - `app/src/main/java/com/cowork/bikerecoder/video/SummaryImageRenderer.kt`
  - `VideoInputs.kt`: 이제 쓰는 곳이 없는 `badge`를 지운다(`HistoryScreen`의 `card.badge`는 다른 클래스라 그대로 둔다).
- Test: 기기 `SummaryImageRendererTest.kt`

**Interfaces:**
- Consumes: Task 4 `RouteOverlay` `RouteStyle.FULL`·`FrameStyle`, Task 3 스타일.
- Produces: `render(inputs, aspect, photos)` 시그니처는 그대로다. 어느 스타일인지는 생성자가 받은 `TileSource`가 정한다.

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `headerUsesRideTitle`: 머리 두 번째 줄 영역에 회색 글자가 있다. 시험 입력의 `rideTitle = "저녁 라이딩 · 서울 강서구"`를 그린 너비와 같은지 `Paint.measureText`로 비교한다(±4 px).
  - `routeGlowAndStartEndDots`: 지도 박스 안에 주황, 초록, 검정 점 픽셀이 있다.
  - 기존 4개(세로·가로·사진 없음·가로 가운데)는 그대로 통과해야 한다.
  - 스크린샷: `v131-image-portrait.png`, `v131-image-landscape.png`.
- [ ] **Step 2: 실패 확인.** S22 `SummaryImageRendererTest`
- [ ] **Step 3: 구현.** §3.9를 따른다.
- [ ] **Step 4: 통과 확인.**
  - S22 `SummaryImageRendererTest`
  - `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`
- [ ] **Step 5: 커밋.** `video: satellite summary image with glowing route, start/end dots and ride title`

### Task 6: 작업 연결 — 위성 판정, 대신 만들기, 지역 제목, 데이터 측정

**Files:**
- Modify:
  - `video/VideoJob.kt`, `video/VideoBackend.kt`, `video/VideoJobStore.kt`, `video/VideoNotifications.kt`
  - `search/KakaoLocalClient.kt`
  - `AppContainer.kt`
- Test:
  - JVM `VideoJobTest.kt`, `KakaoLocalClientTest.kt`, `VideoJobStoreTest.kt`, `VideoNotificationsTest.kt`
  - 기기 `VideoJobDeviceTest.kt`

**Interfaces:**
- Consumes: Task 2 `RideTitle`, `VideoEstimate.satelliteMb`. Task 3 `SatelliteProbe`, `SatelliteTileSource`.
- Produces:
  - `VideoJobState.Done(media: TripMedia, downscaled: Boolean, satelliteFallback: Boolean = false)`
  - `VideoBackend`
    - `suspend fun probeSatellite(at: GeoPoint): SatelliteCheck`
      - `enum class SatelliteCheck { OK, FAILED, NO_KEY }`. NO_KEY = `BuildConfig.VWORLD_API_KEY` 비어 있음
    - `fun receivedBytes(): Long?`: `TrafficStats.getUidRxBytes(myUid)`, UNSUPPORTED면 null
    - `openVideo(uri, inputs, options, satellite: Boolean)`, `writeImage(uri, inputs, aspect, satellite: Boolean)`: satellite면 `SatelliteTileSource(tileSource, key)`, 아니면 `tileSource`
  - `VideoJob` 생성자에 `regionOf: suspend (GeoPoint) -> String?` 추가
    - AppContainer가 `KakaoLocalClient.regionOf` + `RideTitle.shortRegion`으로 만든다
  - `KakaoLocalClient.regionOf(point: GeoPoint): SearchResult<Pair<String, String>?>`
    - `/v2/local/geo/coord2regioncode.json?x=&y=`
    - `region_type == "H"`인 문서의 (`region_1depth_name`, `region_2depth_name`)
  - `VideoJobStore.bytesPerFrame(): Double?`, `recordBytesPerFrame(v: Double)`
  - `VideoNotifications`
    - Done 알림 둘째 줄 = `VideoJob.MSG_SATELLITE_FALLBACK`("위성 지도를 받지 못해 일반 지도로 만들었습니다")
    - 새 extra `EXTRA_PREVIEW_SATELLITE_FALLBACK`(Boolean)

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `VideoJobTest`(가짜 백엔드):
    - `satelliteOkMakesSatellite`: probe OK면 openVideo가 satellite = true를 받고, Done.satelliteFallback = false다.
    - `satelliteFailureFallsBack`: probe FAILED면 satellite = false, Done.satelliteFallback = true다.
    - `noKeyUsesPlainWithoutNotice`: probe NO_KEY면 satellite = false, satelliteFallback = false다.
    - `offlineStillNoInternet`: online false이고 받아 둔 지도도 없으면 `NO_INTERNET`이고 probe는 부르지 않는다.
    - `imageUsesTheSameCheck`: 이미지도 probe 결과로 writeImage(satellite)를 부른다.
    - `rideTitleGetsRegion`: regionOf가 "서울 강서구"이면 openVideo가 받은 inputs.rideTitle이 "저녁 라이딩 · 서울 강서구"다.
    - `slowRegionLookupGivesTitleWithoutRegion`(Review Focus 3): regionOf가 10초 지연이면 5초(가상 시간) 뒤 지역 없이 진행하고, 예외면 지역 없이 진행한다. 작업은 Done이다.
    - `bytesPerFrameRecordedOnlyForSatellite`: receivedBytes가 1_000_000 → 37_000_000이고 720장이면 50_000.0을 기록한다. 대신 만들기면 기록하지 않는다. null이면 기록하지 않는다.
  - `KakaoLocalClientTest.regionOfPicksAdministrativeRegion`: B와 H 두 문서가 오면 H의 이름을 고른다. 401·빈 문서면 실패나 null이다.
  - `VideoJobStoreTest.bytesPerFrameRoundTrip`
  - `VideoNotificationsTest.fallbackLineAndExtra`
  - 기기 `VideoJobDeviceTest`:
    - 기존 2개는 실제 키라 위성으로 만들고 `satelliteFallback == false`다.
    - 새 `wrongKeyFallsBackToPlain`: 시험용 백엔드에 잘못된 키를 넣으면 Done.satelliteFallback == true이고 파일이 정상이다.
- [ ] **Step 2: 실패 확인.** `./gradlew :app:testDebugUnitTest`
- [ ] **Step 3: 구현.**
  - **시작 검사 순서:** 기존 검사 → `mapReachable` → `probeSatellite`(NO_INTERNET이 아닐 때만) → (영상이면) 음악.
  - **run:** setPending 뒤에 `withTimeoutOrNull(5_000) { runCatching { regionOf(start) }.getOrNull() }`로 inputs.copy(rideTitle = …)를 만든다.
  - make 앞뒤로 receivedBytes를 잰다.
- [ ] **Step 4: 통과 확인.**
  - JVM 전체
  - S22: `VideoJobDeviceTest`. 갤러리 시험 파일은 지운다.
- [ ] **Step 5: 커밋.** `video: job picks satellite or plain map by a probe, titles with region, measures map data`

### Task 7: 화면 — 데이터 안내, 대신 만들기 안내, 실제 영상 확인

**Files:**
- Modify:
  - `ui/video/VideoViewModel.kt`, `ui/video/VideoOptionsSheet.kt`, `ui/video/MediaPreviewScreen.kt`
  - `ui/BikeNavHost.kt`: `LaunchRequest.Preview`에 `satelliteFallback`
  - `MainActivity.kt`: extra 읽기
- Test:
  - JVM `VideoViewModelTest.kt`
  - 기기 `VideoOptionsSheetTest.kt`, `MediaPreviewScreenTest.kt`, `VideoFlowDeviceTest.kt`

**Interfaces:**
- Consumes: Task 6 `Done.satelliteFallback`, `EXTRA_PREVIEW_SATELLITE_FALLBACK`, `VideoJobStore.bytesPerFrame()`, Task 2 `satelliteMb`, Task 3 `NetworkWaiter.isCellular()`.
- Produces:
  - `VideoViewModel`
    - sheet 상태에 `dataNoticeMb: Int?`. 셀룰러이고 영상 시트일 때만 값이 있다.
    - `VideoOutcome.Preview(uri, kind, downscaled, satelliteFallback)`

- [ ] **Step 1: 실패하는 시험 쓰기.**
  - `VideoViewModelTest`:
    - `dataNoticeOnlyOnCellular`: Wi-Fi면 null. 셀룰러, 실측 없음, 짧게면 20. 실측 50_000.0, 720장이면 36. 이미지 시트면 null.
    - `previewCarriesFallback`
  - `VideoOptionsSheetTest.dataNoticeText`: "위성 지도 데이터 약 20 MB가 듭니다 (Wi-Fi 권장)"가 [만들기] 위에 있다. null이면 없다.
  - `MediaPreviewScreenTest.fallbackNotice`: satelliteFallback이면 "위성 지도를 받지 못해 일반 지도로 만들었습니다"가 보이고, downscaled도 참이면 두 줄 모두 보인다.
  - `VideoFlowDeviceTest`(S22, 실제 키): 앱 흐름으로 짧게·세로 영상과 세로 이미지를 만든다.
    - MP4를 `adb pull`로 꺼내 ffprobe로 1080×1920, 24 fps, 30초를 확인한다.
    - 걸린 시간과 receivedBytes 차이(MB)를 보고서에 적는다.
    - 미리보기 캡처 `v131-video-preview.png`, `v131-image-preview.png`.
    - 영상에서 프레임 6장을 뽑아 scratchpad에 둔다: 시작 0.5초, 내려가기 2초, 주행 12초, 사진 장면, 끝 28초, 그리고 이미지. 이름은 `v131-flow-*.png`. ffmpeg로 뽑는다.
    - 갤러리 시험 파일은 지운다.
- [ ] **Step 2: 실패 확인.** JVM과 기기 새 시험이 실패해야 한다.
- [ ] **Step 3: 구현.**
  - **안내 위치:** 시트의 [만들기] 바로 위, 예상 시간 줄 아래.
  - **미리보기 안내:** 720p 안내와 같은 자리에 줄을 쌓는다.
- [ ] **Step 4: 통과 확인.**
  - JVM 전체
  - S22에서 `VideoOptionsSheetTest`, `MediaPreviewScreenTest`, `VideoFlowDeviceTest`
  - 회귀: `VideoEncoderTest`, `AudioTrackMakerTest`, `TripMediaRowTest`, `HistoryScreenTest`, `RecordScreenTest`. 클래스 하나씩 돌린다.
- [ ] **Step 5: 커밋.** `app: satellite data notice on cellular, fallback notice in preview and notification`

### Task 8: 문서

**Files:** `CHANGELOG.md`(`## [1.3.1] - 개발 중`), `README.md`(버전 줄 V1.3.1), `docs/verification/video-field-test.md`(V1.3.1 절)

- [ ] **Step 1: 전체 JVM 실행.** `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`. 테스트 수는 XML에서 센다.
- [ ] **Step 2: 문서 쓰기.**
  - V1.3.1 절에는 다음을 넣는다.
    - 위성/대신 만들기/데이터 안내/카메라 확인 항목
    - 작업 4·5·7의 S22 결과와 걸린 시간·데이터(MB)
    - 실주행 체크리스트 칸: 위성 사진이 선명한지, 글자가 바로 서 있는지, 어지럽지 않은지, 데이터 사용량
  - 경고 "connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 `installDebug`만"은 그대로 둔다.
- [ ] **Step 3: 커밋.** `docs: V1.3.1 video polish changelog and verification`
