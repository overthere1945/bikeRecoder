# 속도·심박 그래프 (V1.8.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 여행 화면에 속도·심박 그래프를 넣고 구간 화면 것을 같은 모양으로 바꾼다. 두 그래프 모두 튐을 지운 뒤 평균을 내고, 5구간 색으로 칠한다. 큰 지도 화면에 [고도][속도][심박] 탭을 두어 같은 지점을 오가며 훑을 수 있게 한다.

**Architecture:**
- **core `com.cowork.bikerecoder.core.series`** (새 패키지, JVM 시험)
  - 다듬기 `SeriesSmoother`
  - 계열 `MetricSeries`
  - 속도 색 `SpeedBand`
  - 심박 색은 기존 `core.post.HrBand`를 쓴다.
- **core `elevation/TrackAxis`**
  - 구간을 이어 붙인 거리 축이다.
  - V1.7.0 `ElevationProfile.fromSegments`의 이어 붙이기 규칙을 꺼내 둘이 같이 쓴다.
- **app `ui/elevation/`**
  - 공용 그리기를 `ElevationChart`와 나눈 `MetricChart`
  - 문구 `ChartText`
  - 큰 지도의 탭: `ChartTab`, `ElevationMapViewModel`, `ElevationMapScreen`
- **app 수정**
  - `TripDetailScreen`/VM: 그래프 제목 셋, 속도·심박 그래프
  - `SegmentDetailScreen`/VM: 기존 `LineChart`를 교체한다.
  - `BikeNavHost`: 주소의 `tab`, `openChartMap`
  - `map/RouteColors.pathColors`를 일반화한다.
- **DB는 바꾸지 않는다(8판).**

**Tech Stack:** Kotlin, Compose Material 3 Canvas, MapLibre(기존 `MapOverlay.routeColors` 색 조각 선), JUnit5(core·app JVM), Compose UI test(androidTest).

**Spec:** `docs/superpowers/specs/2026-11-01-speed-hr-charts-design.md`. 아래 "스펙 §"는 이 문서를 가리킨다.

## Global Constraints

- **문구**
  - 사용자에게 보이는 글은 모두 한국어이고, 스펙 §3 문구를 그대로 쓴다.
  - 새 문구는 `app/.../ui/elevation/ChartText.kt` 한 곳에 둔다. 기존 `ElevationText`는 그대로 둔다.
- **다듬기**
  - 먼저 5점 중앙값을 내고, 그다음 시간 평균을 낸다. 평균 폭은 속도 ±5,000 ms, 심박 ±10,000 ms다.
  - 두 단계 모두 다음 두 곳에서 창을 끊는다.
    - 이웃 점 사이가 10,000 ms를 넘는 곳
    - 구간 경계
  - null 점은 그대로 두고, 창에 넣지 않는다.
  - 그래프용으로 300점까지 줄인다.
- **속도 5구간**
  - `< 10` SLOW `#90CAF9` `10 미만`
  - `10 ≤ v < 15` EASY `#26A69A` `10~15`
  - `15 ≤ v < 20` STEADY `#66BB6A` `15~20`
  - `20 ≤ v < 25` FAST `#FFA726` `20~25`
  - `≥ 25` SPRINT `#E53935` `25 이상`
- **심박 5구간:** 기존 `HeartRateColor.band`와 `HrBand` 색을 쓴다.
  - 범례 문구: `100 미만`, `100~120`, `120~140`, `140~160`, `160 이상`
  - 표본은 ±30,000 ms(`HeartRateColor.MATCH_MS`) 안에서 붙인다.
- **거리 축:** 구간 시작 순으로, 구간 안의 이웃 거리를 모두 더한다. 구간 사이 거리는 0이다. 고도·속도·심박과 큰 지도가 같은 축을 쓴다.
- **숫자:**
  - 그래프 위·아래 끝 글은 다듬은 값의 최고·최저다.
  - 화면 낭독, 합계 카드, 평균·최고는 저장된 값 그대로다.
- **바꾸지 않는 것:**
  - DB 스키마(8판)
  - 기록 엔진
  - 평균·최고 계산
  - 고도 그래프 모양·경사 색, 길안내
  - 영상·공유·내보내기·가져오기
- **기기 시험:**
  - S22 `R5CT43TFS9A`에서만, 클래스 하나씩 돌린다.
  - 화면을 먼저 깨우고, `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`을 붙인다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.
- **스타일:**
  - 주석 언어는 파일마다 기존을 따른다. core는 한국어 KDoc, app `ui/`는 영어 KDoc이다.
  - core 시험은 `org.junit.jupiter` + `kotlin.test`로 쓴다.

## Review Focus

1. **일부 구간에만 심박이 있는 여행**(첫날만 워치를 참)
   - 기대: 심박이 없는 구간에서 선이 끊긴다. 앞뒤를 이어 그리지 않는다.
   - 기대: 거기서 훑으면 `{거리} · --bpm`이 나온다.
   - 시험: Task 1 `heartRateGapStaysNull`, Task 2 `nullRunBreaksTheLine`, Task 3 `scrubInHeartRateGapShowsDashes`
2. **GPS가 튀어 순간속도가 터무니없는 점**(100 km/h 넘는 튐이 두 점 이상 이어짐)
   - 기대: 그래프 위 끝이 수백 km/h로 늘어나지 않는다. 100 km/h를 넘는 속도 값은 null로 버린다.
   - 시험: Task 1 `absurdSpeedsAreDropped`
3. **아주 긴 여행**(여러 날, 점 10만 개)
   - 기대: 다듬기가 O(n)이고, 화면이 멈추지 않는다(계산은 기본 디스패처).
   - 시험: Task 1 `smoothsHundredThousandPointsQuickly`
4. **시각이 같은 점이나 거꾸로 가는 점**(가져온 GPX)
   - 기대: 구간 속도가 null이 되고 죽지 않는다.
   - 시험: Task 1 `sameTimePointsGiveNullSpeed`
5. **요청한 탭에 데이터가 없음**(심박 없는 여행에 `tab=heartRate`, 프로세스가 죽은 뒤 복원)
   - 기대: 있는 첫 탭으로 연다.
   - 시험: Task 3 `missingTabFallsBackToFirst`

---

### Task 1: core — 거리 축, 다듬기, 계열, 속도 색

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/elevation/TrackAxis.kt`
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/series/SeriesSmoother.kt`
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/series/MetricSeries.kt`
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/series/SpeedBand.kt`
- Modify: `core/.../elevation/ElevationProfile.kt`. `fromSegments`가 `TrackAxis`를 쓰게 바꾼다.
- Test: `core/src/test/.../elevation/TrackAxisTest.kt`
- Test: `core/src/test/.../series/SeriesSmootherTest.kt`
- Test: `core/src/test/.../series/MetricSeriesTest.kt`
- Test: `core/src/test/.../series/SpeedBandTest.kt`

**Interfaces:**
- Consumes:
  - `core.recording.TrackPoint`: `timeMillis`, `point`, `speedMps: Float?`
  - `core.post.HeartRateSample`
  - `core.post.HeartRateColor` (`MATCH_MS`, `band`, `matchedBpm` — internal이므로 같은 모듈에서 쓴다)
  - `core.post.HrBand`
  - `GeoMath.distanceM`
- Produces:
  - `class TrackAxis`
    - `val points: List<GeoPoint>`: 비지 않은 구간들의 점을 순서대로 이은 것
    - `val segmentOf: IntArray`: 점마다 구간 번호
    - `val timesMs: LongArray`
    - `val distancesM: DoubleArray`
    - `val boundariesM: List<Double>`
    - `val totalM: Double`
    - `val paths: List<List<GeoPoint>>`: 구간별 점
    - `companion fun of(segments: List<List<TrackPoint>>): TrackAxis?`: 빈 구간은 건너뛰고, 점이 2개 미만이면 null
  - `object SeriesSmoother`
    - `const val MEDIAN_POINTS = 5`
    - `const val GAP_MS = 10_000L`
    - `const val SPEED_WINDOW_MS = 5_000L`
    - `const val HEART_WINDOW_MS = 10_000L`
    - `fun smooth(timesMs: LongArray, segmentOf: IntArray, values: List<Double?>, windowMs: Long): List<Double?>`
  - `enum class SpeedBand(val argb: Long, val legend: String)`
    - 값: SLOW, EASY, STEADY, FAST, SPRINT
    - `companion fun of(kmh: Double): SpeedBand`
    - `val hex: String`
  - `enum class MetricKind { SPEED, HEART_RATE }`
  - `class MetricSeries` (private constructor)
    - `val kind: MetricKind`
    - `val distancesM: DoubleArray`, `val values: List<Double?>`: 300점 이하로 줄인 것
    - `val minValue: Double`, `val maxValue: Double`
    - `val totalM: Double`, `val boundariesM: List<Double>`
    - `fun valueAt(d: Double): Double?`: 이웃 두 점 사이 선형. 어느 한쪽이 null이면 null.
    - `fun colorHexAt(d: Double): String?`: SPEED는 `SpeedBand.of(v).hex`, HEART_RATE는 `HeartRateColor.band(v.roundToInt()).colorHex`
    - `companion fun speed(axis: TrackAxis, segments: List<List<TrackPoint>>): MetricSeries?`
    - `companion fun heartRate(axis: TrackAxis, segments: List<List<TrackPoint>>, samples: List<HeartRateSample>): MetricSeries?`
    - `companion const val MAX_POINTS = 300`
    - `companion const val MAX_SPEED_KMH = 100.0`
  - `ElevationProfile.fromSegments`는 시그니처와 결과를 그대로 둔다.

- [ ] **Step 1: 실패하는 시험 쓰기**
  - `TrackAxisTest`
    - `segmentsJoinWithoutGap`: 1 km 떨어진 두 구간의 `totalM`이 두 구간 길이의 합이고, `boundariesM`가 첫 구간 길이 하나다.
    - `emptySegmentsSkipped`
    - `fewerThanTwoPointsIsNull`
    - `profileUnchanged`: 같은 합성 입력에서 `ElevationProfile.fromSegments`의 `distancesM`·`elevationsM`이 리팩터링 전 값과 같다. 리팩터링 전에 값을 계산해 시험 안에 상수 배열로 고정한다.
  - `SeriesSmootherTest`
    - `singleNeedleRemoved`: 1 Hz로 [10,10,10,0,10,10,10] → 가운데가 `> 9`
    - `fiveSecondStopKept`: 10×10, 0×5, 10×10 → 멈춤 가운데가 `< 2.0`
    - `gapNotCrossed`: 10 s 넘게 끊긴 앞쪽 0 값들과 뒤쪽 20 값들이 서로 섞이지 않는다. 각 쪽의 첫 값이 자기 쪽 값이다.
    - `segmentBoundaryNotCrossed`
    - `nullsKeptAndSkipped`
    - `windowWidthsDiffer`: 같은 입력에서 5 s 창과 10 s 창의 결과가 다르다.
    - `smoothsHundredThousandPointsQuickly`: 100,000점이 1초 안에 끝난다(JVM).
  - `MetricSeriesTest`
    - `speedFallsBackToPairSpeed`
    - `sameTimePointsGiveNullSpeed`
    - `absurdSpeedsAreDropped`: 연속 두 점이 200 km/h면 `maxValue < 100`
    - `heartRateMatchedWithin30s`: 30.0 s는 붙고, 30.1 s는 null이다.
    - `heartRateGapStaysNull`: 둘째 구간에 표본이 없으면 그 구간 거리의 `valueAt`이 null이다.
    - `fewerThanTwoValuesIsNull`
    - `valueAtIsLinear`
    - `colorHexAt`
    - `downsampledToThreeHundred`
    - `minMaxAreSmoothed`
  - `SpeedBandTest`
    - `boundaries`: 9.99은 SLOW, 10은 EASY, 14.99은 EASY, 15은 STEADY, 19.99은 STEADY, 20은 FAST, 24.99은 FAST, 25은 SPRINT
    - `hexAndLegend`: 스펙 §1.4 표 그대로
- [ ] **Step 2:** `./gradlew :core:test`를 돌려 실패를 확인한다.
- [ ] **Step 3: 구현**
  - `SeriesSmoother`는 O(n)이다.
    - 중앙값은 같은 구간, 끊김 없는 이웃 5점 중 null이 아닌 것으로 낸다.
    - 평균은 두 포인터 누적합으로 낸다.
  - `MetricSeries`
    - 순서: 점별 원래 값 → 100 km/h 넘는 속도는 null → `smooth` → 300점으로 줄이기
    - 300점으로 줄일 때는 null을 넘어 묶지 않는다. null은 null로 남긴다.
  - `TrackAxis`의 거리 규칙은 `ElevationProfile.fromSegments` 안 코드를 그대로 옮긴다.
- [ ] **Step 4:** `./gradlew :core:test`를 돌려 모두 통과하는지 확인한다.
- [ ] **Step 5: 커밋** `core: series — track axis, smoothing, speed and heart-rate series`

### Task 2: app — `MetricChart`, `ChartText`, 지도 색 일반화

**Files:**
- Create: `app/.../ui/elevation/MetricChart.kt`
- Create: `app/.../ui/elevation/ChartText.kt`
- Modify: `app/.../ui/elevation/ElevationChart.kt`. 그리기·km 눈금·[지도에 표시]·훑기 공용 부분을 `internal`로 나눈다. 고도 모양은 그대로 둔다.
- Modify: `app/.../map/RouteColors.kt`. `pathColors`가 거리 → 색 함수를 받게 한다.
- Test: `app/src/test/.../ui/elevation/ChartTextTest.kt`
- Test: `app/src/test/.../map/RouteColorsTest.kt`(추가)
- Test: `app/src/androidTest/.../ui/elevation/MetricChartTest.kt`
- Test: 기존 `ElevationChartTest` 통과를 유지한다.

**Interfaces:**
- Consumes: Task 1의 `MetricSeries`, `MetricKind`, `SpeedBand`, `TrackAxis`. V1.7.0의 `ElevationChart`, `ElevationText`, `kmTickStepKm`, `SummaryFormatter.distance`.
- Produces:
  - `@Composable fun MetricChart(series: MetricSeries?, kind: MetricKind, description: String, modifier: Modifier = Modifier, height: Dp = 120.dp, scrubbable: Boolean = false, onOpen: (() -> Unit)? = null, onScrub: (Double) -> Unit = {}, scrubM: Double? = null)`
    - series가 null이면 SPEED는 `ChartText.NO_SPEED` 글을 보이고, HEART_RATE는 아무것도 그리지 않는다.
    - 화면 낭독은 `description`으로 한다.
    - null 구간에서는 면과 선을 끊는다.
  - `object ChartText`
    - `TITLE_ELEVATION = "고도"`, `TITLE_SPEED = "속도"`, `TITLE_HEART = "심박"`
    - `NO_SPEED = "속도 정보가 없습니다"`
    - `SPEED_LEGEND: List<String>`, `HEART_LEGEND: List<String>`
    - `fun scrubLine(kind: MetricKind, distanceM: Double, value: Double?): String`: `{거리} · {n}km/h`, `{거리} · {n}bpm`. 값이 null이면 `--km/h`, `--bpm`.
    - `fun speedDescription(avgKmh: Double, maxKmh: Double): String`
    - `fun heartDescription(avgBpm: Int, maxBpm: Int): String`
    - `fun valueLabel(kind: MetricKind, v: Double): String`: `34km/h`, `162bpm`
  - `fun pathColors(paths: List<List<GeoPoint>>, colorAt: (Double) -> String?, axis: TrackAxis): List<List<String>>`
    - 기존 고도용 오버로드는 이것을 부른다.
    - null 색은 회색 `#9E9E9E`로 칠한다.
- [ ] **Step 1: 실패하는 시험 쓰기**
  - `ChartTextTest`
    - `scrubLines`: 1,100 m·18.4 → `1.1km · 18km/h`. 145.4 → `1.1km · 145bpm`. null → `1.1km · --bpm`.
    - `descriptions`
    - `legends`
  - `RouteColorsTest`
    - `metricColorsFollowAxis`
    - `nullColorIsGray`
  - `MetricChartTest`(S22)
    - `speedNullShowsText`
    - `heartNullDrawsNothing`
    - `endLabelsAreMinMax`
    - `smallChartOpens`
    - `scrubReportsDistance`
    - `descriptionIsRead`
    - `nullRunBreaksTheLine`: null 구간 x 위치의 픽셀이 배경색이다. `captureToImage`로 확인한다.
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** JVM 전체를 돌린다. 이어서 S22에서 `MetricChartTest`, `ElevationChartTest`를 하나씩 돌린다.
- [ ] **Step 5: 커밋** `app: charts — metric chart, chart text and metric route colours`

### Task 3: app — 큰 지도 탭

**Files:**
- Create: `app/.../ui/elevation/ChartTab.kt`
- Modify: `app/.../ui/elevation/ElevationMapViewModel.kt`
- Modify: `app/.../ui/elevation/ElevationMapScreen.kt`
- Modify: `app/.../ui/BikeNavHost.kt`
- Modify: `AppContainer.kt`(필요하면 VM에 `PostStore`를 넘긴다)
- Test: `app/src/test/.../ui/elevation/ElevationMapViewModelTest.kt`(추가)
- Test: `app/src/androidTest/.../ui/elevation/ElevationMapScreenTest.kt`(추가)

**Interfaces:**
- Consumes: Task 1의 `TrackAxis`, `MetricSeries`. Task 2의 `MetricChart`, `ChartText`, `pathColors`. 기존 `PostStore.samples(segmentId)`.
- Produces:
  - `enum class ChartTab(val arg: String) { ELEVATION("elevation"), SPEED("speed"), HEART_RATE("heartRate") }`
  - `Routes.ELEVATION = "elevation/{kind}/{id}?tab={tab}"`, 기본 `elevation`
  - `fun NavController.openChartMap(source: ElevationSource, tab: ChartTab)`. 기존 `openElevation(source)`는 `openChartMap(source, ChartTab.ELEVATION)`을 부른다.
  - `ElevationMapUiState.Ready`
    - `profile: ElevationProfile?`
    - `speed: MetricSeries?`
    - `heartRate: MetricSeries?`
    - `tabs: List<ChartTab>`
    - `tab: ChartTab`
    - `paths`
    - `colors: List<List<String>>`: 지금 탭의 색
    - `scrubM: Double?`
  - 모두 없으면 `Closed`다.
  - `fun selectTab(tab: ChartTab)`
  - plan은 고도 탭만 둔다.
- [ ] **Step 1: 실패하는 시험 쓰기**
  - `ElevationMapViewModelTest`
    - `tabsFollowData`: 심박이 없으면 [고도][속도]다.
    - `missingTabFallsBackToFirst`
    - `scrubKeptAcrossTabs`
    - `colorsFollowTab`
    - `noElevationOpensOnSpeed`
    - `planHasOnlyElevation`
  - `ElevationMapScreenTest`(S22)
    - `tabsSwitchLegendAndScrubLine`
    - `scrubInHeartRateGapShowsDashes`
    - `singleTabShowsTitle`
    - `screenshot("v180-map-speed")`
    - `screenshot("v180-map-heart")`
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
  - 탭에는 Material 3 `SingleChoiceSegmentedButtonRow`를 쓴다.
  - 계열 계산은 주입한 기본 디스패처에서 한다.
  - 심박 표본 읽기에 실패하면 로그를 남기고 심박을 null로 둔다.
- [ ] **Step 4:** JVM 전체를 돌린다. 이어서 S22에서 `ElevationMapScreenTest`를 돌린다.
- [ ] **Step 5: 커밋** `app: chart map — elevation, speed and heart-rate tabs`

### Task 4: app — 여행 화면과 구간 화면

**Files:**
- Modify: `app/.../ui/history/TripDetailScreen.kt`, `TripDetailViewModel.kt`
- Modify: `app/.../ui/history/SegmentDetailScreen.kt`, `SegmentDetailViewModel.kt`
- Modify: `BikeNavHost.kt`(콜백 연결)
- Delete:
  - `app/.../ui/history/LineChart.kt`
  - `core/.../post/HeartRate.kt`의 `HeartRateCharts.bpm`
  - `core/.../recording/RideCharts.kt`의 `altitude`, `speedKmh`
  - 위 셋의 시험. `cumulativeKm`·`downsample`은 다른 곳에서 쓰면 남긴다.
- Test: `app/src/test/.../ui/history/TripDetailViewModelTest.kt`(추가), `FeedViewModelTest`(구간 상태)
- Test: `app/src/androidTest/.../ui/history/TripDetailScreenTest.kt`, `SegmentDetailScreenTest.kt`(추가)

**Interfaces:**
- Consumes: Task 1의 `TrackAxis`, `MetricSeries`. Task 2의 `MetricChart`, `ChartText`. Task 3의 `openChartMap`, `ChartTab`. 기존 `TripDetailViewModel.total`(평균·최고 속도), `heartRate: StateFlow<TripHeartRate>`(avgBpm, maxBpm), `PostStore.samples`.
- Produces:
  - `TripDetailViewModel`
    - `speedSeries: StateFlow<MetricSeries?>`
    - `heartSeries: StateFlow<MetricSeries?>`
    - 둘 다 고도 단면과 같은 계산 때 `profileOf` 옆에서 만든다.
    - 실패하면 null이 되고, `profileLoaded`는 true다.
  - `TripDetailContent(..., onOpenChart: ((ChartTab) -> Unit)?)`. 기존 `onOpenElevation`은 이것으로 합친다.
  - `SegmentDetailState.Loaded`
    - `speed: List<ChartPoint>`를 `speedSeries: MetricSeries?`로 바꾼다.
    - `heartSeries`는 VM의 `StateFlow<MetricSeries?>`로 둔다. `heartRateChart`를 이것으로 바꾼다.
- [ ] **Step 1: 실패하는 시험 쓰기**
  - `TripDetailViewModelTest`
    - `speedAndHeartSeries`
    - `noHeartRateGivesNullSeries`
    - `storedAveragesUnchanged`
    - `seriesFailureStillLoaded`
  - `TripDetailScreenTest`
    - `threeTitlesInOrder`: `고도`, `속도`, `심박` 순서다.
    - `noHeartRateHidesHeartChart`
    - `openChartPassesTab`
    - `screenshot("v180-trip-charts")`
  - `SegmentDetailScreenTest`
    - `metricChartsReplaceLineCharts`
    - `heartRateGateUnchanged`
    - `screenshot("v180-segment-charts")`
- [ ] **Step 2:** 실패를 확인한다.
- [ ] **Step 3:** 구현한다.
  - 여행 화면 순서: 합계 카드 → 그래프 셋. 각 그래프에 `titleSmall` 제목을 단다.
  - 속도 낭독은 `total`의 평균·최고 km/h다.
  - 심박 낭독은 `TripHeartRate.avgBpm`·`maxBpm`이다. 둘 중 하나라도 null이면 표본에서 구한 평균·최고를 쓴다.
- [ ] **Step 4:** JVM 전체를 돌린다. 이어서 S22에서 `TripDetailScreenTest`, `SegmentDetailScreenTest`를 하나씩 돌린다.
- [ ] **Step 5: 커밋** `app: trip and segment — speed and heart-rate charts`

### Task 5: 버전·문서 (part A). 기기 전체 시험·설치는 최종 수정 뒤(part B)

**Files:**
- Modify:
  - `app/build.gradle.kts`: verMinor 8, verPatch 0 → 10800, `1.8.0-dev`
  - `SettingsScreenTest`: `V1.8.0`
  - `AppVersionTest`
  - `CHANGELOG.md`: `## [1.8.0] - 개발 중`
  - `README.md`
    - 현재 버전 V1.8.0(개발 중)
    - 로드맵: V1.8.0 속도·심박 그래프 개발 중, V1.9.0 GPX 경로 따라가기 예정, V1.10.0 경로 주변 장소 예정
    - 문서 목록
  - `docs/VERSIONING.md`
- Create: `docs/verification/speed-hr-charts-field-test.md`
  - JVM 결과
  - S22 자리: "최종 수정 뒤 기록"
  - 스펙 §5.3 점검표 4항목
- [ ] **Step 1:** 버전과 시험 두 개를 고친 뒤 `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test`를 돌린다.
- [ ] **Step 2:** 문서를 고친다.
- [ ] **Step 3:** S22에서 `SettingsScreenTest`만 돌린다.
- [ ] **Step 4: 커밋** `docs: V1.8.0 version, changelog, README and verification`
- [ ] **(part B, 컨트롤러가 최종 검토와 수정 뒤에 함)**
  1. S22 androidTest 전체를 클래스 하나씩 돌린다(`DownloadsWriterTest.takenGpxNameKeepsItsExtension` 포함).
  2. `v180-*.png`를 받아 확인한다.
  3. S22에 `installDebug`하고 시험용 기록 사본을 다시 넣는다.
  4. 실제 여행 화면, 큰 지도 탭, 구간 화면을 확인한다.
  5. 검증 문서에 결과를 적고 커밋한다.
