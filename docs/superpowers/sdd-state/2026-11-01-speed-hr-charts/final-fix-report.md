# V1.8.0 최종 리뷰 수정 보고

- 커밋: `96134c0` fix: V1.8.0 final review — sparse heart rate per segment, docs and small fixes (HEAD 56721ae 위)
- 상태: DONE

## 항목별 변경

### 1. 드문 심박은 구간마다 걸러 그리지 않음 (세 화면 같은 기준)
- 방식: 저장된 구간 플래그를 쓴다. `SegmentExtras.hrDetailed`는 PostProcessor가 `HeartRateSummary.of(samples, segment.totals.movingTimeMs).detailed`로 저장한다(이동 시간 1분에 표본 1개 이상). core에서 다시 계산하지 않는다(이동 시간 기준이 달라 [심박] 토글과 어긋날 수 있으므로).
- core `MetricSeries.heartRate(axis, segments, samples, detailed: List<Boolean>)`: `detailed`는 구간마다 하나(크기가 다르면 `require` 실패). 자세하지 않은 구간은 모든 점이 null. 자세한 구간이 없으면 값이 2개 미만이라 null. KDoc에 규칙과 이유(표본 하나가 ±30초 점들에 붙어 짧은 주행에서 토막이 남음)를 적음.
- app `SegmentExtras.detailedHeartRate` (= READY && hrDetailed) 추가, 세 VM이 이것을 쓴다.
  - `SegmentDetailViewModel.heartSeries`: 자세하지 않으면 null. `canColorByHeartRate`도 같은 속성.
  - `TripDetailViewModel`: `detailedIds` 흐름 → `heartChart`가 구간 순서대로 마스크를 넘김. 여행 지도 `lines`([심박] 모드)도 자세하지 않은 구간은 표본을 읽지 않아 회색(`#9E9E9E`) — 큰 지도·그래프와 맞춤.
  - `ElevationMapViewModel`: `HeartInput.detailed`(구간 순서), 빈 트랙을 뺄 때 같은 자리로 마스크도 거름. 심박 탭 색은 계열의 `colorHexAt`이라 자동으로 회색.

### 2. 긴 여행의 멈춤 (문서만)
- 스펙 §1.2에 "멈춤과 긴 여행" 항목, §5.3 점검표 1번, CHANGELOG 알려진 한계, 검증 문서 점검표 1번·판정에 기대치 명시: 구간 그래프와 짧은 여행은 움푹 들어감, 긴 여행은 300점 평균으로 전체 흐름만.

### 3. 작은 수정
- a. `SpeedBand.hex`를 한 번만 만드는 `val`로.
- b. `TripDetailViewModel.load()`: 주석을 "Exception만 잡고 OOM 같은 Error는 못 잡음"으로 고침. 축(`ChartInput`) 만들기와 `speedOf`를 따로 guard → 속도 실패해도 `chartInput`이 남아 심박이 그려짐.
- c. `ElevationMapViewModel(savedState: SavedStateHandle? = null)`; `selectTab`이 실제로 바뀐 탭을 `ARG_TAB`에 `tab.arg`로 저장. factory가 handle을 넘김.
- d. `ElevationText.TITLE` 삭제(main/test/androidTest 참조 없음 grep 확인).
- e. 스펙: §1.3 100 km/h 버림, `speed(axis, segments)`, `heartRate(axis, segments, samples, detailed)`, `bandAt`→`colorHexAt`(§1.3, §1.4, §5.1), §1.2·§4의 `RideCharts.downsample`→`MetricSeries` 자체 `downsample`(RideCharts는 모두 지워짐), §2.4·§3 값 없는 자리 `{거리} · --km/h` / `--bpm`. CHANGELOG 변경과 검증 문서 "이 버전에서 정한 판정"에 100 km/h 버림과 구간별 심박 밀도 규칙 추가.

## 시험

### 새 시험
- core `MetricSeriesTest`
  - `shortSparseHeartRateIsNull`: 1시간 1 Hz, 10분마다 표본 → null
  - `sparseHeartRateIsNull`(보강): 5시간 → 규칙으로 null, 자세하다고 쳐도 줄인 뒤 null(기존 보호 유지)
  - `sparseSegmentNextToDenseOneIsNull`: 10분 자세한 구간 + 1시간 드문 구간 → 계열은 있고 드문 부분은 모두 null, 마스크 크기 불일치는 IllegalArgumentException
  - `denseHeartRateIsCharted`: 1시간, 30초마다 표본 → 300점 모두 값
  - 시험 도우미 `heartByRuleOf`는 앱처럼 구간마다 `HeartRateSummary.of(...).detailed`로 마스크를 만든다.
- app JVM
  - `DetailHeartRateTest.heartRateThatIsNotDetailedIsNotCharted`: 점마다 표본이 있어도 자세하지 않으면 구간·여행 화면 모두 심박 그래프 없음
  - `DetailHeartRateTest.aTripChartsAndColoursOnlyItsDetailedSegments`: 여행 계열은 자세한 구간만 값, 여행 지도에서 드문 구간은 회색
  - `ElevationMapViewModelTest.heartRateThatIsNotDetailedIsNotCharted`: 드문 구간 하나면 탭 [고도][속도], 여행이면 그 구간 경로가 회색
  - `ElevationMapViewModelTest.selectedTabIsSaved` / `missingTabIsNotSaved`: handle의 `tab`이 저장되고 새 VM이 그 탭으로 열림, 없는 탭은 저장 안 함
  - `TripDetailViewModelTest.seriesFailureStillLoaded`(변경): 속도 실패해도 심박 계열 있음
- 기존 시험 조정: 표본만 있고 extras가 없던 고정값에 READY·detailed extras를 더함(실제로는 PostProcessor가 함께 저장). `heartReadOutFallsBackToSamples`는 평균·최고 없는 READY로. androidTest `SegmentDetailScreenTest.heartRateGateUnchanged` → `heartRateChartOnlyWhenDetailed`(자세하지 않으면 그래프 없음, 자세해지면 생김), `metricChartsReplaceLineCharts`는 detailed=true로 하고 제목 찾을 때 [심박] 칩 제외. `ElevationMapScreenTest` 고정값에 READY·detailed extras.

### RED / GREEN
- RED(core): `MetricSeries`의 거르기를 잠시 끈 상태(`if (false && !detailed[s])` = 이전 동작)로 `:core:test --tests MetricSeriesTest` → 16개 중 2개 실패
  - `shortSparseHeartRateIsNull`: `expected: <null> but was: MetricSeries@…`
  - `sparseSegmentNextToDenseOneIsNull`: `sparse part [130.0, 130.0, … 26개]`
  - (`sparseHeartRateIsNull` 5시간, `denseHeartRateIsCharted`는 이전 코드에서도 통과 — 회귀 보호용)
- RED(app): 같은 상태로 `DetailHeartRateTest`, `ElevationMapViewModelTest` → 38개 중 3개 실패(`aTripChartsAndColoursOnlyItsDetailedSegments`, `DetailHeartRateTest.heartRateThatIsNotDetailedIsNotCharted`(여행 부분), `ElevationMapViewModelTest.heartRateThatIsNotDetailedIsNotCharted`). 구간 화면은 VM이 먼저 걸러 따로 실패하지 않음.
- RED(3b): `load()`를 이전처럼 축과 속도를 함께 guard하게 잠시 되돌려 `seriesFailureStillLoaded` 실행 → 실패. 되돌린 뒤 통과.
- 3c 저장 시험은 이전 코드에 `savedState` 인자가 없어 컴파일 단계에서 실패(따로 실행하지 않음).
- GREEN: 아래 전체 실행.

### 명령과 결과
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL. core 61클래스 542개, app 107클래스 1233개, 실패·건너뜀 0(JUnit XML로 셈). 이전 기록 core 539 / app 1228에서 core +3, app +5.
- 기기(S22 `R5CT43TFS9A`만, 화면 깨운 뒤 클래스 하나씩, `ANDROID_SERIAL=R5CT43TFS9A … </dev/null`):
  - `ui.history.SegmentDetailScreenTest` 11/11 통과
  - `ui.elevation.ElevationMapScreenTest` 10/10 통과
  - `ui.history.DetailMapPhotosTest` 2/2 통과(여행·구간 VM을 실제로 씀)
  - `TripDetailScreenTest`, `MetricChartTest`는 계열을 `detailed = listOf(true)`로 만드는 컴파일 수정뿐이라(결과 같음) 실행하지 않음.
- S25 `R3CY903ZYQV`는 건드리지 않음. 휴대폰 설정 변경 없음. `secrets.json` 읽지 않음.

## 바뀐 파일
- core: `series/MetricSeries.kt`, `series/SpeedBand.kt`, 시험 `series/MetricSeriesTest.kt`
- app main: `post/PostStore.kt`, `ui/history/TripDetailViewModel.kt`, `ui/history/SegmentDetailViewModel.kt`, `ui/elevation/ElevationMapViewModel.kt`, `ui/elevation/ElevationText.kt`
- app test: `ui/history/TripDetailViewModelTest.kt`, `ui/history/DetailHeartRateTest.kt`, `ui/elevation/ElevationMapViewModelTest.kt`
- androidTest: `ui/history/SegmentDetailScreenTest.kt`, `ui/history/TripDetailScreenTest.kt`, `ui/elevation/ElevationMapScreenTest.kt`, `ui/elevation/MetricChartTest.kt`
- 문서: `docs/superpowers/specs/2026-11-01-speed-hr-charts-design.md`, `CHANGELOG.md`, `docs/verification/speed-hr-charts-field-test.md`

## 참고
- 지시를 조금 넘은 부분: 여행 화면 지도 [심박] 색에서 자세하지 않은 구간을 회색으로 바꿈(전에는 드문 표본 자리만 색이 있었음). "모든 화면이 같은 기준"을 맞추려고 함. 큰 지도 심박 탭과 같은 모양.
- 여행 심박 낭독의 표본 대체값(저장된 평균·최고가 없을 때만 씀)은 여전히 모든 구간 표본으로 계산함. 저장값이 있으면 쓰이지 않으므로 그대로 둠.
- 검증 문서의 PC 테스트 수 표(539/1228)는 갱신하지 않음. 최종 전체 기록 때 다시 적는 자리임.
- `.superpowers/sdd/`는 gitignore 대상이라 이 보고서는 커밋되지 않음.
