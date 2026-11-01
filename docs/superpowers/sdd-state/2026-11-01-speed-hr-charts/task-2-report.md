# Task 2 보고 — app: MetricChart, ChartText, 지도 색 일반화

**Status:** DONE_WITH_CONCERNS
**Commit:** 6128a48 `app: charts — metric chart, chart text and metric route colours` (BASE 53a90d3)

## 구현한 것

### `ui/elevation/ChartText.kt` (새 파일)
- `TITLE_ELEVATION`/`TITLE_SPEED`/`TITLE_HEART`, `NO_SPEED`: 브리프 값 그대로.
- `SPEED_LEGEND = SpeedBand.entries.map { it.legend }` (판정 1).
- `HEART_LEGEND = listOf("100 미만", "100~120", "120~140", "140~160", "160 이상")`. 이 문자열은 ChartText에 직접 적었다. 근거는 아래 "우려 1".
- `scrubLine(kind, d, value)`: `{SummaryFormatter.distance} · {n}km/h|bpm`, null이면 `--`.
- `speedDescription(avg, max)`, `heartDescription(avg, max)`, `valueLabel(kind, v)`.
- 정수 반올림은 모두 private `whole(v) = v.roundToInt().coerceAtLeast(0)`을 거친다. 그래서 "-0"이 나오지 않는다(판정 2). `heartDescription`은 Int를 받으므로 그대로 쓴다.

### `ui/elevation/ElevationChart.kt` (공용 부분을 `internal`로 분리, 판정 4)
- 새 `internal` composable 두 개:
  - `NoChartData(text, tag, modifier, height)`: 예전의 "고도 정보가 없습니다" Box.
  - `ChartFrame(totalM, description, tag, modifier, height, scrubbable, onOpen, onScrub, scrubM, gestureKey, drawChart, dotY)`: 예전 `ElevationChart`의 BoxWithConstraints를 그대로 옮겼다. 클릭/Role.Button, 훑기 제스처, 낭독, 훑기 세로선과 점, [지도에 표시]가 여기 있다. 고도 전용이던 부분은 매개변수로 뺐다. `profile.totalM`은 `totalM`, 점의 y는 `dotY`(null이면 점 없음), `pointerInput` 키는 `gestureKey`, 태그는 `tag`다.
- 다음은 `private`에서 `internal`로만 바꿨다: `AXIS_HEIGHT`, `ChartColors`/`chartColors()`, `ProfileShapes`, `drawProfile`.
- 다음은 `internal`로 바꾸면서 `ElevationProfile` 대신 `totalM`·`boundariesM`을 받게 했다: `drawBoundaries`, `drawKmAxis`, `xOf`.
- `drawHeightLabel`은 `drawEndLabel`로 이름을 바꿨다. 본문은 같다.
- `yOf`의 범위 계산은 `internal yIn(value, min, max, minSpan, plot)`으로 뺐다. 고도 `yOf`는 이것을 `MIN_SPAN_M`으로 부른다. 식은 같다.
- `ShowOnMapButton`, `profileShapes`, `ElevationStrip`, 태그, 색은 바꾸지 않았다. 고도의 모양과 동작은 같다(`ElevationChartTest` 6/6 통과).

### `ui/elevation/MetricChart.kt` (새 파일)
- `MetricChart(series, kind, description, modifier, height = 120.dp, scrubbable, onOpen, onScrub, scrubM)`: 브리프의 시그니처 그대로다.
  - series가 null이면 SPEED는 `NoChartData(NO_SPEED)`를 보이고, HEART_RATE는 아무것도 내보내지 않는다(높이 0).
  - 위·아래 끝 글은 `ChartText.valueLabel(kind, series.maxValue/minValue)`, 즉 다듬은 값이다.
  - y 범위는 `yIn`으로 정한다. 최소 폭은 속도 10 km/h, 심박 20 bpm이다. 고도의 `MIN_SPAN_M`처럼 고른 주행이 평평하게 보이게 하려는 값이다.
- `seriesShapes`는 2dp 열마다 `valueAt`을 샘플한다(고도와 같은 방식). 색은 열 가운데의 `colorHexAt`이고, 가운데에 값이 없으면 열 시작의 색이다.
  - `valueAt`이 null인 열에서는 면을 닫고 선을 뗀다. 값이 다시 나오면 `moveTo`로 새로 시작한다(판정 3).
  - **null 사이에 홀로 있는 값은 아무것도 그리지 않는다.** 선은 `moveTo`만 있고, 면은 폭이 0이다. KDoc에 적어 두었다.
  - 훑기 위치에 값이 없으면 세로선만 그리고 점은 그리지 않는다.
- `object MetricTags`:
  - 태그 `SPEED_CHART`, `HEART_CHART`, `NO_SPEED`와 `chart(kind)`
  - 시험용 semantics 키 `EndLabels`(그리는 끝 글 두 개). 기존 `ElevationTags.StripAlpha`와 같은 방식이다.
  - [지도에 표시]와 훑기 선은 `ElevationTags.SHOW_ON_MAP`·`SCRUB_LINE`을 그대로 쓴다.

### `map/RouteColors.kt`
- 새 `pathColors(paths, colorAt: (Double) -> String?, axis: TrackAxis)`:
  - 점마다 `axis.distancesM`의 거리를 `colorAt`에 넣는다.
  - null은 `#9E9E9E`(private `NO_VALUE_HEX`)로 칠한다.
  - 점 개수가 축과 다르면 `require`에서 실패한다.
- 기존 고도용 `pathColors(paths, profile, flatHex)`:
  - 거리 배열은 예전과 같은 GeoMath 누적으로, 경로 사이 0으로 만든다.
  - 그다음 새 함수와 같은 private `colorsAlong(paths, distancesM, colorAt)`을 부른다. 근거는 아래 "우려 2".

## 시험

### RED (구현 전)
`./gradlew :app:testDebugUnitTest --tests '*ChartTextTest' --tests '*RouteColorsTest' </dev/null`
```
> Task :app:compileDebugUnitTestKotlin FAILED
e: .../RouteColorsTest.kt:89:45 Argument type mismatch: actual type is '(???) -> ???', but 'ElevationProfile' was expected.
e: .../RouteColorsTest.kt:89:97 Argument type mismatch: actual type is 'TrackAxis', but 'String?' was expected.
e: .../ChartTextTest.kt:12:40 Unresolved reference 'ChartText'.
...
```
예상한 실패다. `ChartText`가 없었고, `pathColors`에는 고도용 오버로드만 있었다. `MetricChartTest`도 `MetricChart`/`MetricTags`가 없어 같은 이유로 컴파일되지 않는 상태에서 썼다.

**`nullRunBreaksTheLine` 변이 검사.** 구현을 일부러 망가뜨려 시험이 잡는지 확인했다. 변이는 null을 `minValue`로 그리는 것이다(`valueAt(d) ?: series.minValue`).
- 첫 시험은 이미지 위 3/4만 보았기 때문에 변이를 **놓쳤다**. minValue 선은 그림 영역의 약 89% 높이에 있다.
- 그래서 시험을 고쳤다. 이제 그림 영역 바닥(`AXIS_HEIGHT + 1dp` 위)까지 본다.
- 고친 시험은 변이를 잡는다:
  `AssertionError: x=495 y=259: Color(0.62, 0.6, 0.63) is not the background Color(0.996, 0.969, 1.0)`
- 그 뒤 원래 코드로 되돌렸다. 되돌린 것은 grep으로 확인했다.

### GREEN
- `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin </dev/null`: BUILD SUCCESSFUL.
  - core: 550개, 실패 0
  - app: 1204개, 실패 0
  - 새 시험: ChartTextTest 5, RouteColorsTest 6(기존 4 + 2)
- S22 `R5CT43TFS9A`, 클래스 하나씩 돌렸다. 매번 `KEYCODE_WAKEUP`을 먼저 보냈다.
  - `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.bikerecoder.ui.elevation.MetricChartTest </dev/null`: 7/7 통과. 마지막 손질 뒤에도 다시 7/7.
    - speedNullShowsText, heartNullDrawsNothing, endLabelsAreMinMax, smallChartOpens, scrubReportsDistance, descriptionIsRead, nullRunBreaksTheLine
  - `...class=com.cowork.bikerecoder.ui.elevation.ElevationChartTest`: 6/6 통과.
- S25에는 아무것도 하지 않았다.

## 바뀐 파일
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ChartText.kt` (새 파일)
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/MetricChart.kt` (새 파일)
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ElevationChart.kt`
- `app/src/main/java/com/cowork/bikerecoder/map/RouteColors.kt`
- `app/src/test/java/com/cowork/bikerecoder/ui/elevation/ChartTextTest.kt` (새 파일)
- `app/src/test/java/com/cowork/bikerecoder/map/RouteColorsTest.kt` (+2개)
- `app/src/androidTest/java/com/cowork/bikerecoder/ui/elevation/MetricChartTest.kt` (새 파일)

## 자체 검토
- 위 변이 검사에서 `nullRunBreaksTheLine`의 결함(아래 1/4를 보지 않음)을 찾아 고쳤다.
- 처음 짠 색 계산에는 결함이 두 가지 있었고, 시험 전에 고쳤다.
  - 마지막 열이 직전 열이 null일 때 오래된 `areaColor`를 쓸 수 있었다.
  - nullable hex를 `parseColor`에 넘겼다.
  - 지금은 열 시작에 값이 있으면 색도 반드시 있으므로 `!!`를 쓰고, 그 이유를 주석에 적었다.
- 쓰지 않는 import는 없다. 새 파일에 컴파일 경고도 없다.
- 시험 파일의 import 순서를 정리했다.
- 요청에 없는 것으로는 `MetricTags`(태그와 `EndLabels`)를 더했다. 시험에 필요하고, 기존 `ElevationTags` 방식을 따른다.

## 우려
1. **HEART_LEGEND의 출처.** 기존 심박 범례 `ui/health/HeartRateLegend.kt`의 `HR_LEGEND`가 있지만 문구가 스펙 §3과 다르다. 그쪽은 `"100–119"`, `"120–139"`, `"140–159"`이고 en dash를 쓰며 "기록 없음" 칸까지 있다. 이 목록을 바꾸면 지금 지도 범례가 바뀌므로 손대지 않았다. 대신 global constraints의 "새 문구는 ChartText 한 곳"에 따라 스펙 문구를 ChartText에 직접 적고, `HrBand` BLUE→RED 순서라고 KDoc에 밝혔다. core의 `HrBand`에 `legend` 필드를 두는 방법도 있지만 core 모델을 바꾸게 되어 고르지 않았다.
2. **"기존 고도용 오버로드는 이것(새 pathColors)을 부른다"는 글자 그대로는 할 수 없었다.** 고도용 오버로드에는 `TrackAxis`가 없다. 특히 plan(경로 미리보기)은 `Route`에서 오고, `TrackAxis`는 `TrackPoint`로만 만들 수 있다. 그래서 두 오버로드가 같은 private `colorsAlong`을 부르게 했다. 고도용은 예전과 같은 거리를 계산하고, 기존 `pathColorsFollowTheProfileAxisAcrossPaths`도 통과한다. 글자 그대로를 원하면 고도용에도 축을 넘기도록 VM을 바꿔야 한다.
3. 큰 지도의 [지도에 표시]·훑기 선 태그는 고도와 같은 `ElevationTags` 태그를 쓴다. 여행 화면에 그래프 셋이 함께 있을 때 Task 3 시험은 `hasAnyAncestor(hasTestTag(MetricTags.SPEED_CHART))` 같은 방법으로 구분해야 한다.
