# Task 1 보고 — core: 거리 축, 다듬기, 계열, 속도 색

- 상태: DONE_WITH_CONCERNS (아래 "편차 1" — 평균 창 폭 해석)
- 커밋: `7eebdde core: series — track axis, smoothing, speed and heart-rate series` (BASE cfd4e5f)

## 파일
- 새로 만듦
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/elevation/TrackAxis.kt`
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/series/SeriesSmoother.kt`
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/series/MetricSeries.kt` (`MetricKind` 포함)
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/series/SpeedBand.kt`
  - 시험: `core/src/test/.../elevation/TrackAxisTest.kt`, `core/src/test/.../series/{SeriesSmootherTest,MetricSeriesTest,SpeedBandTest}.kt`
- 고침
  - `core/.../elevation/ElevationProfile.kt`: `fromSegments`가 `TrackAxis.of`를 쓴다(시그니처 그대로). 쓰지 않게 된 `GeoMath` import를 지웠다.
- 건드리지 않음: `RideCharts`, `HeartRateCharts` (Task 4에서 지움)

## 결정
1. **`TrackAxis.segmentOf`**: 비지 않은 구간만 0부터 센 번호다(`paths` 번호와 같다). 원래 `segments` 목록의 번호가 아니다. 점 배열은 `segments.flatten()`과 한 점씩 맞는다. `MetricSeries.speed/heartRate`는 이것을 `require`로 확인한다(축과 다른 segments를 넘기면 IllegalArgumentException).
2. **`profileUnchanged`**: 빈 구간 둘, 고도 null 점, 서로 떨어진 구간 셋으로 된 입력을 만들었다. 리팩터링 전 코드로 `distancesM`(38개), `elevationsM`(38개), `boundariesM`(2개)를 뽑아 상수로 고정했다. 비교 허용 오차는 1e-9이다. JVM마다 `Math.sin` 등의 마지막 ulp가 다를 수 있어서다.
3. **`SeriesSmoother`**
   - 묶음(run)은 다음 경우에 끊는다: 구간 번호가 바뀔 때, 시각 차가 `GAP_MS`(10,000)를 넘을 때, 시각이 거꾸로 갈 때. 시각이 거꾸로 가는 경우는 스펙에 없지만 넣었다. 두 포인터가 시간 순서를 전제하기 때문이다(가져온 GPX 대비).
   - 중앙값: 같은 묶음 안에서 번호 i−2..i+2 자리의 null이 아닌 값으로 낸다. 짝수 개면 가운데 둘의 평균이다. 크기 5 배열을 정렬하므로 점마다 O(1)이다.
   - 평균: 묶음마다 두 포인터와 누적 합(sum, count)으로 낸다. 전체 O(n)이다. 10만 점이 0.08 s 걸린다.
   - null 점은 결과에서도 null이고, 두 단계 모두 창에 넣지 않는다.
4. **`MetricSeries`**
   - 속도 대체값(fallback)은 `RideCharts.speedKmh`와 같은 규칙이다(`speedMps`, 없으면 바로 앞 점과의 구간 속도, 시각 차 ≤ 0이면 null). 다만 "앞 점"은 **같은 구간 안**에서만 찾는다. 그래서 둘째 구간의 첫 점은 앞 구간 끝 점과 짝을 짓지 않는다.
   - 100 km/h를 넘는 값과 유한하지 않은 값(NaN/∞)은 다듬기 전에 null로 만든다. 100.0 정확히는 남긴다.
   - 심박: 표본을 시각순으로 정렬한 뒤 `HeartRateColor.matchedBpm`(±30,000 ms, 경계 포함)으로 붙인다.
   - 300점으로 줄이기: `RideCharts.downsample`과 같은 묶음 나누기(첫 점·끝 점 고정, 사이를 298묶음)를 쓴다. **null이 하나라도 든 묶음의 값은 null**이고, 거리는 묶음 평균이다. null을 넘어 평균하지 않는다.
   - `minValue`/`maxValue`는 줄인 뒤 값(그래프에 그려지는 값)의 최저·최고다.
   - 값 있는 원래 점이 2개 미만이면 null이다. 안전장치로 줄인 뒤 null 아닌 값이 하나도 없어도 null이다.
   - `valueAt`: 범위 밖은 끝 값(null일 수 있음), NaN이면 null, 정확히 점 위면 그 값이다. 경계에서 거리가 겹치면 앞 구간 끝 점의 값이다.
5. **`SpeedBand.hex`**: `argb`에서 `#RRGGBB`로 만든다(Locale.ROOT).

## 편차 / 우려
1. **평균 창 폭 해석 (중요).** 스펙 §1.2와 global constraints는 "점마다 시각이 앞뒤 `windowMs` 안"(±5,000 ms)이라 하는데, 같은 스펙 §5.1과 brief의 `fiveSecondStopKept`(10×10, 0×5, 10×10 → 멈춤 가운데 `< 2.0`)는 그 정의로는 성립하지 않는다.
   - 1 Hz에서 ±5 s 창은 11점이고, 이 가운데 6점이 10이다. 그래서 값이 60/11 ≈ 5.45가 된다.
   - 시험이 실제로 깨지는 것을 변형 시험(mutation)으로 확인했다.
   - **택한 것:** `windowMs`를 창의 **전체 폭**으로 보고, 점마다 앞뒤 `windowMs/2`를 본다. 속도는 ±2.5 s, 심박은 ±5 s다.
   - 이유: brief의 시험 값을 그대로 지킬 수 있다. 사용자가 승인한 "실제 멈춤은 남긴다"는 의도와도 맞는다.
   - 상수 값(`SPEED_WINDOW_MS = 5_000`, `HEART_WINDOW_MS = 10_000`)과 이름은 brief 그대로다. KDoc에 "전체 폭, 앞뒤 절반씩"이라고 적었다.
   - ±windowMs로 되돌리려면 `SeriesSmoother.kt`의 `val reach = windowMs / 2`를 `windowMs`로 한 줄 바꾸면 된다. 다만 그러면 `fiveSecondStopKept`의 기준(<2.0)을 고쳐야 한다(약 5.45가 나온다). `windowWidthsDiffer`의 `speed[17] == 0` 단언도 고쳐야 한다.
   - 컨트롤러 판단이 필요하다.
2. **null 섞인 묶음은 통째로 null이다.** 점이 300보다 많을 때, 외딴 null 하나가 있으면 그 묶음(약 n/298점) 자리에서 그래프가 짧게 끊긴다. "null을 넘어 묶지 않는다"를 엄격하게 따른 결과다.
3. 속도 범례 문구가 `SpeedBand.legend`(core)에 있다. brief 인터페이스가 그렇게 정했기 때문이다. Task 2의 `ChartText.SPEED_LEGEND`와 중복될 수 있으니, Task 2에서 `SpeedBand.entries.map { it.legend }`를 쓰면 한 곳으로 모인다.

## 시험 결과
- `./gradlew :core:test`: 547개 모두 통과 (새 시험 23개: TrackAxis 4, SeriesSmoother 7, MetricSeries 10, SpeedBand 2. 기존 ElevationProfileTest 17개 통과)
- `./gradlew :app:testDebugUnitTest`: 1197개 모두 통과
- TDD: 시험을 먼저 써서 컴파일 실패(빨강)를 확인한 뒤 구현했다.
- 변형 확인: 창을 ±windowMs로 바꾸거나 구간 경계 끊기를 빼면 `fiveSecondStopKept`, `segmentBoundaryNotCrossed`, `windowWidthsDiffer`가 실패한다(확인 뒤 원래대로 되돌림).
- `smoothsHundredThousandPointsQuickly`: 0.079 s
- 기기·adb는 쓰지 않았다. `secrets.json`은 건드리지 않았다.

## Fix round 1

- 커밋: `core: series — Task 1 review fixes` (7eebdde 위)
- 컨트롤러 판정: 전체 폭 창(속도 ±2.5 s, 심박 ±5 s)을 받아들인다. `SpeedBand.legend`는 core에 둔다. 우려 2·3은 아래 수정으로 끝났다.

### 고친 것
1. **(I1) `SeriesSmootherTest.gapNotCrossed`**
   - 입력을 바꿨다: 앞쪽 0 두 점(0, 1 s), 10.001 s 끊김, 뒤쪽 20 열 점.
   - `out[1] == 0.0`과 `out[2] == 20.0`을 확인한다.
   - 변형 확인: `SeriesSmoother.kt:40`의 `timesMs[i] - timesMs[i - 1] > GAP_MS` 조건을 지우면 `gapNotCrossed`가 FAILED다. 확인 뒤 조건을 되돌렸다.
2. **(I2) 300점 줄이기를 다수결로 바꿨다(`MetricSeries.downsample`)**
   - 묶음의 절반을 **넘는** 점이 null일 때만 null이다(`nulls * 2 > size`).
   - 그 밖에는 null이 아닌 값들만으로 평균을 낸다. 거리는 묶음 전체의 평균 그대로다.
   - 함수를 시험할 수 있게 `internal`로 바꿨다.
   - 새 시험
     - `downsampleMajorityRule`: 3점 중 null 하나는 나머지 평균, null 둘은 null, 4점 중 꼭 절반이 null이면 값이 남는다. 거리는 묶음 전체 평균이다.
     - `isolatedNullKeepsBucketValue`: 1,000점 속도에서 외딴 null 하나가 있어도 줄인 뒤 null이 없다. `valueAt`은 18 km/h다.
   - 기존 `downsampledToThreeHundred`(null 20점이 이어진 곳)는 그대로 통과한다.
   - 변형 확인: 예전 규칙(null 하나라도 → null)으로 바꾸면 두 새 시험이 FAILED다.
3. **(I3) 그릴 선분이 없으면 null**
   - `build`가 줄인 값에서 이웃한 두 값이 모두 null이 아닌 곳이 없으면 null을 낸다(`hasDrawableStep`).
   - 새 시험 `sparseHeartRateIsNull`: 5시간 1 Hz 기록에 표본이 10분마다 하나씩(30개) 있으면 `heartRate`가 null이다.
   - 변형 확인: 예전 검사(`v.all { it == null }`)로 바꾸면 FAILED다.
4. **(Minor) `speedFallsBackToPairSpeed`**
   - 둘째 구간을 첫 구간 끝에서 5 m, 1 s 뒤에 시작하게 했다.
   - 둘째 구간 첫 점(`values[21]`)이 null임을 확인한다. 구간을 넘어 구간 속도를 만들지 않는다는 뜻이다.
   - 둘째 구간의 나머지 점은 18 km/h다.

### 명령과 결과
- `./gradlew :core:test --tests '*SeriesSmootherTest*'`를 변형한 코드로 돌렸다: `gapNotCrossed` FAILED(의도한 결과). 원래대로 되돌렸다.
- `./gradlew :core:test --tests '*MetricSeriesTest*'`를 변형 두 가지로 돌렸다: 해당 시험이 FAILED(의도한 결과). 원래대로 되돌렸다.
- `./gradlew :core:test`: BUILD SUCCESSFUL, 550개 모두 통과(새 시험 3개 더함)
