# 통계 탭 그래프·설명 보강 (V1.4.0 추가) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 통계 탭을 사용자 시험 의견대로 고친다.
- "상승"을 "상승 고도"로 바꾼다.
- 막대 위에 값을 적는다.
- [전체]는 2년 이내면 달 막대로 그린다.
- 누적 꺾은선 그래프를 넣는다.
- ? 설명 말풍선 6곳을 넣는다.

**Architecture:**
- 계산은 `:core`의 `StatsCalculator`에 순수 함수로 더한다. `buckets`를 바꾸고 `cumulative`를 새로 만든다.
- 화면 상태는 `StatsViewModel.build`가 만든다.
- 그리기는 `ui/stats`의 Compose `Canvas`로 한다. `BarChart`를 고치고 `CumulativeChart`, `HelpTip`을 새로 만든다.

**Tech Stack:** Kotlin, Compose Material 3, JUnit5 (core), JUnit4 + Compose UI test (app).

**Spec:** `docs/superpowers/specs/2026-10-31-summary-stats-design.md` §3.3, §5. 이 계획의 변경은 사용자가 2026-10-31 채팅에서 승인했다(B안 + ? 설명). Task 2 마지막 단계에서 스펙을 고쳐 적는다.

## Global Constraints

- **글:** 사용자에게 보이는 글은 모두 한국어다. 아래에 적힌 문구는 그대로 쓴다.
- **숫자 모양:** 기존 V1.4.0 규칙을 따른다(`kmText` "32.1 km", `meterText` "123 m", `RideFormat.duration` "1시간 45분").
- **주행으로 치는 것:** 10 m 이상 끝난 구간만 센다(`StatsCalculator.rides`). 주는 월~일, 날짜는 Asia/Seoul 기준이다.
- **계산 위치:** 계산은 메인 스레드 밖(`StatsViewModel`의 `compute` 디스패처)에서 한다. 이미 그렇게 되어 있으니 새 계산도 `build` 안에서 한다.
- **기기:**
  - connected 테스트는 S22 `R5CT43TFS9A`에서만 돌린다. 클래스 하나씩, 화면을 먼저 깨우고, `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`을 붙인다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정은 바꾸지 않는다.
- **비밀 파일:** `secrets.json`은 출력하지도 커밋하지도 않는다.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.

## Review Focus

1. **주행이 한 번뿐이거나 하나도 없을 때:** 막대·선·눈금이 0으로 나누지 않아야 하고, 아무것도 없는 기간도 깨지지 않고 그려져야 한다.
2. **[전체]가 정확히 24달일 때와 25달일 때의 경계:** 24달까지는 달 막대, 25달부터는 해 막대다.
3. **오늘이 기간 첫날일 때:** 이번 기간 선은 점 하나다. 앞 기간이 이번 기간보다 길 때(1월 31일 대 2월 28일)에는 가로축을 더 긴 쪽에 맞춘다.
4. **값 글씨 넘침:** 막대가 13개 이상이면 값 글씨는 가장 큰 막대와 고른 막대에만 쓴다. 큰 글꼴 배율에서도 글자가 막대 칸을 넘지 않는다.
5. **말풍선:** 하나만 열린다. 다른 곳을 누르면 닫히고, 화면을 돌리거나 탭을 바꾸면 닫힌다. 화면 읽기 프로그램이 ?를 "도움말: <주제>"로 읽는다.

---

### Task 1: 이름·막대 값·[전체] 달 막대·? 설명

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/stats/StatsCalculator.kt` (`buckets`의 ALL)
- Modify: `core/src/test/kotlin/com/cowork/bikerecoder/core/stats/StatsCalculatorTest.kt`
- Modify: `app/src/main/java/com/cowork/bikerecoder/ui/stats/StatsFormat.kt`, `StatsViewModel.kt`, `BarChart.kt`, `StatsScreen.kt`, `GoalCard.kt`
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/stats/HelpTip.kt`
- Modify tests: `app/src/test/.../ui/stats/StatsFormatTest.kt`, `StatsViewModelTest.kt`, `app/src/androidTest/.../ui/stats/StatsScreenTest.kt`

**Interfaces:**
- Produces:
  - `BarState`에 `shortText: String`를 더한다. 막대 위에 쓰는 짧은 값이고, 0이거나 아직 오지 않은 칸이면 `""`이다.
  - `StatsState`에 `unitText: String`를 더한다. 값은 "km" / "시간" / "m"이다.
  - `@Composable fun HelpTip(topic: String, text: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier)`
  - `object StatsHelp`는 문구 상수 6개를 담는다.

- [ ] **Step 1: core — [전체] 막대 단위 (TDD)**
  - **규칙:** `buckets`의 `PeriodKind.ALL`은 `period.start`의 달부터 `period.endInclusive`의 달까지의 달 수를 센다. 24 이하면 달마다 한 칸(그 달 1일~말일), 25 이상이면 지금처럼 해마다 한 칸이다.
  - **시험:**
    - `allWithin24MonthsHasMonthBuckets`: 첫 주행 2025-11-03, 오늘 2026-10-31이면 12칸이고, 첫 칸은 2025-11-01~2025-11-30이다.
    - `allOf24MonthsIsMonthly`: 2024-11-xx ~ 2026-10-31이면 24칸이다.
    - `allOf25MonthsIsYearly`: 2024-10-xx ~ 2026-10-31이면 3칸(2024, 2025, 2026)이다.
    - `allWithNoRidesIsOneMonth`: 기록이 없으면 이번 달 1칸이다.
  - **KDoc:** 함수 설명을 고친다.
- [ ] **Step 2: app 형식 (TDD, `StatsFormatTest`)**
  - **이름:** `StatMetric` 칩 이름과 작은 칸 이름 "상승"을 "상승 고도"로 바꾼다. 다른 이름은 그대로 둔다.
  - **`barShortText(metric, totals)`:**
    - 거리: 100 km 미만이면 소수 한 자리 "24.1", 100 km 이상이면 정수 "124"
    - 시간: "h:mm" ("1:45", "0:19")
    - 상승 고도: 정수 m "120"
    - 0이면 `""`
  - **`metricUnitText(metric)`:** "km" / "시간" / "m"
  - **[전체]가 달 막대일 때:**
    - 축 이름은 "M월"이다. 단 1월과 첫 칸은 "’YY.M"(예: "’26.1", "’25.11")이다.
    - 누른 값 머리말은 "2026년 10월 · 24.1 km"이다.
- [ ] **Step 3: ViewModel**
  - `barOf`가 `shortText`를 채운다.
  - `StatsState.unitText`를 채운다.
  - `StatsViewModelTest`에 시험 두 개를 더한다.
    - [전체] 달 막대: 12칸과 그 이름
    - 칩 [시간]: `shortText` "0:19"
- [ ] **Step 4: BarChart 그리기**
  - **단위:** 그래프 왼쪽 위에 `unitText`를 작은 글씨(labelSmall, onSurfaceVariant)로 쓴다.
  - **막대 값 (`shortText`):** 막대 바로 위 같은 칸 안에 labelSmall로 한 줄 쓴다. 넘치면 글자를 줄이지 말고 생략한다(`maxLines = 1`, `softWrap = false`, 칸 폭 안에서 잘리면 안 씀). 막대 높이가 칸을 다 차지하면 글자가 들어갈 위쪽 여백 16dp를 둔다.
  - **막대가 13개 이상이면:**
    - 값 글씨는 가장 큰 막대(같으면 앞의 것)와 고른 막대에만 쓴다.
    - 축 이름은 `ceil(n/12)`개마다 하나만 쓴다(첫 칸 포함). 고른 칸의 이름은 늘 쓴다.
  - **그대로 둘 것:** 막대 누르기와 "· 값" 머리말
- [ ] **Step 5: HelpTip**
  - **아이콘:** `Icons.AutoMirrored`가 없으면 `Icons.Outlined.HelpOutline`을 쓴다. 18dp, onSurfaceVariant 색이고 누르는 자리는 48dp다. `contentDescription`은 "도움말: $topic"이다.
  - **말풍선:** `open`이면 아이콘 아래에 `Popup`을 띄운다.
    - 모양: Surface, 테두리 1dp outlineVariant, 둥근 모서리 12dp, 안쪽 여백 16dp, 최대 폭 320dp, bodyMedium, 가운데 정렬
    - 닫기: `onDismissRequest`가 `onToggle`을 부른다. 바깥을 누르거나 뒤로를 누르면 닫힌다.
  - **열림 상태:** 화면(`StatsScreen`)이 `rememberSaveable { mutableStateOf<String?>(null) }`로 "열린 주제" 하나만 갖는다. 탭을 떠나면 닫힌다(탭 전환 때 null).
- [ ] **Step 6: 설명 6곳과 문구** (`StatsHelp`, 그대로 쓴다)
  - **목표 카드 제목 옆** — 주제 "목표":
    "주간 목표는 월요일부터 일요일까지, 연간 목표는 1월 1일부터 탄 거리입니다. '연말 예상'은 올해 하루 평균 거리에 올해 날 수를 곱한 값으로, 1월 7일부터 보입니다."
  - **연속 주행 줄 옆** — 주제 "연속 주행":
    "한 번 이상 탄 주가 끊기지 않고 이어진 주 수입니다. 주는 월요일부터 일요일까지입니다. 이번 주에 아직 안 탔다면 지난주까지 센 수를 보여 줍니다."
  - **작은 칸 셋 위(주행·이동 시간·상승 고도)** — 주제 "주행·이동 시간·상승 고도":
    "주행은 10 m 이상 달리고 끝난 기록 하나를 1회로 셉니다. 이동 시간은 멈춰 있던 시간(일시정지)을 뺀, 실제로 달린 시간입니다. 1시간 중 15분을 쉬었다면 45분입니다. 상승 고도는 해발 높이가 아니라 오르막에서 올라간 높이를 모두 더한 값입니다. 10 m를 올랐다가 내려와 다시 20 m를 오르면 30 m입니다."
  - **비교 문장 옆** — 주제 "비교":
    "이번 기간은 오늘까지 지난 날 수만큼만, 앞 기간의 같은 날까지와 비교합니다. 이미 끝난 기간은 앞 기간 전체와 비교합니다."
  - **그래프 칩 줄 옆** — 주제 "그래프":
    Task 1에서는 다음 문구다.
    "막대 하나는 그 칸(요일·주·달·해)에 탄 합계입니다. 위의 [거리] [시간] [상승 고도]로 보는 값을 바꿉니다. 막대를 누르면 값이 크게 보입니다."
    Task 2에서 다음 문구로 바꾼다.
    "막대 하나는 그 칸(요일·주·달·해)에 탄 합계입니다. 아래 선은 기간 첫날부터 쌓인 양으로, 진한 선은 이번 기간, 회색 선은 앞 기간, 점선은 목표를 날마다 고르게 나눠 탔을 때입니다. 위의 [거리] [시간] [상승 고도]로 보는 값을 바꿉니다."
  - **"개인 기록" 제목 옆** — 주제 "개인 기록":
    "처음 기록부터 지금까지 전체에서 가장 좋은 기록입니다. 평균 속도는 5 km 이상 탄 주행만 셉니다. 같은 기록이면 먼저 세운 것을 남깁니다."
  - **"연말 예상" 문구:** `Goals.yearProjectionKm`의 실제 시작일(7일째)과 맞는지 코드로 확인한다. 다르면 문구를 코드에 맞춘다.
- [ ] **Step 7: 기기 시험 (`StatsScreenTest`)**
  - **이름:** "상승 고도" 칩과 칸 이름이 보인다.
  - **칩을 바꾸면:** 막대 값 글씨가 바뀐다(거리 "3.5" → 시간 "0:19").
  - **[전체] 1년 이내:** 달 막대가 나온다.
  - **? 말풍선:** 누르면 문구가 보이고, 바깥을 누르면 사라진다. 둘째 ?를 누르면 첫째가 닫힌다.
  - **큰 글꼴:** 글꼴 배율 2에서 값 글씨가 칸을 넘지 않는다(잘리면 안 그려짐).
  - **실행:** `StatsScreenTest`, `GoalDialogTest`를 S22에서 하나씩 돌린다.
- [ ] **Step 8: JVM 전체와 커밋**
  - `./gradlew :core:test :app:testDebugUnitTest`를 돌려 모두 통과해야 한다.
  - 커밋 메시지: `app: stats — 상승 고도, bar values, monthly bars for a short 전체, help tips`

### Task 2: 누적 꺾은선 그래프

**Files:**
- Modify: `core/.../stats/StatsModels.kt`, `StatsCalculator.kt` (+ test)
- Modify: `app/.../ui/stats/StatsViewModel.kt`, `StatsScreen.kt`, `StatsFormat.kt` (+ tests)
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/stats/CumulativeChart.kt`
- Modify: `docs/superpowers/specs/2026-10-31-summary-stats-design.md` §3.3·§5, `CHANGELOG.md` `[1.4.0]`, `docs/verification/summary-stats-field-test.md` (점검 항목 추가)

**Interfaces:**
- Consumes (Task 1): `StatMetric`, `metricValue`, `barShortText`, `metricUnitText`, `HelpTip`, `StatsHelp`
- Produces:
  - `data class CumulativeSeries(val current: List<StatTotals>, val previous: List<StatTotals>, val length: Int)` (core)
  - `fun StatsCalculator.cumulative(rides: List<RideStat>, period: StatsPeriod, today: LocalDate): CumulativeSeries?`
    - [전체]면 null이다.
    - `current[i]`는 기간 첫날부터 i번째 날까지의 누적이다. 길이는 이번 기간이면 오늘까지(첫날이면 1), 끝난 기간이면 기간 길이다.
    - `previous`는 바로 앞 기간 전체의 날마다 누적이다(길이 = 앞 기간 날 수).
    - `length`는 이번 기간 날 수와 앞 기간 날 수 중 큰 값이다.
  - `data class CumulativeChartState(val current: List<Double>, val previous: List<Double>, val goal: Double?, val length: Int, val endText: String, val currentName: String, val previousName: String)` (app)
  - `StatsState.cumulative: CumulativeChartState?`

- [ ] **Step 1: core `cumulative` (TDD)**
  - 주 하나에서 수요일이 오늘이면 `current` 3개, `previous` 7개다.
  - 2026-03-01이 오늘이면 `current` 1개, `previous` 28개, `length` 31이다.
  - 끝난 달이면 `current`가 그 달 날 수만큼이다.
  - 기록이 없으면 모두 0이다.
  - 10 m 미만 구간과 끝나지 않은 구간은 빠진다(`rides()`를 거친다).
  - [전체]면 null이다.
- [ ] **Step 2: ViewModel**
  - **값:** 고른 칩의 값으로 Double 목록을 만든다. 단위는 거리 km, 시간 시간(hour), 상승 고도 m다.
  - **goal (목표 페이스선의 끝값):** 거리 칩이고 [주]이며 주간 목표가 있으면 그 km, [년]이고 연간 목표가 있으면 그 km다. 그 밖에는 null이다([월]에는 목표 선이 없다).
  - **`endText`:** 이번 기간 마지막 값의 `barShortText` 형식이다.
  - **이름:**
    - [주]: "이번 주"/"지난주". 지난 기간을 볼 때는 "이 주"/"전 주"다.
    - [월]: "이번 달"/"지난달". 지난 기간을 볼 때는 "M월"/"M-1월"이다.
    - [년]: "올해"/"작년". 지난 기간을 볼 때는 "YYYY년"/"YYYY-1년"이다.
  - **시험:** `StatsViewModelTest`에 [월]·[주]·[년]과 칩 [시간] 경우를 하나씩 더한다.
- [ ] **Step 3: CumulativeChart 그리기**
  - **자리와 크기:** 막대 그래프 아래, 높이 160dp다. 제목 "누적"(titleSmall)을 쓴다.
  - **가로축:** 0..length-1이다. 아래에 첫날·가운데·끝날 이름을 쓴다. [주]는 "월"·"목"·"일", [월]은 "1일"·"15일"·"말일 숫자일", [년]은 "1월"·"7월"·"12월"이다.
  - **세로축:** 0, 가운데, 위 눈금 3개(labelSmall)와 옅은 가로 격자선을 둔다.
    - 위 눈금은 세 선의 최댓값 ×1.05 이상인 {1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8}×10^k 중 가장 작은 값이다(실행 중 판정으로 고침: 1·2·5 규칙은 186 km에 500을 주어 선이 아래로 몰렸다). 시간 칩 눈금은 h:mm이다.
    - 최댓값이 0이면 위 눈금은 1이다.
  - **선:**
    - 회색 선(onSurfaceVariant 40%, 2dp): `previous`
    - 진한 선(primary, 3dp): `current`
    - 점선(primary 60%, 2dp, 6dp/4dp): 0에서 보고 있는 기간의 마지막 날(`currentDays − 1`)에 `goal`에 닿는 직선(실행 중 판정으로 고침)
  - **끝 표시:** 진한 선 끝에 원(primary, 지름 32dp 안쪽)을 그리고 `endText`를 흰 글씨로 쓴다. 원이 그래프 밖으로 나가지 않게 안쪽으로 붙인다.
  - **범례:** 아래 한 줄에 "━ {currentName}  ━ {previousName}  ┅ 목표"를 쓴다. 목표가 없으면 목표는 빼고, 색은 선과 같게 한다.
  - **기타:**
    - 화면 읽기 설명: "누적 {currentName} {endText}{unit}, {previousName} {앞 기간 같은 날까지 값}{unit}"
    - [전체]에서는 이 그래프를 그리지 않는다.
    - 테스트 태그: `StatsTags.CUMULATIVE`
- [ ] **Step 4: 문구와 문서**
  - `StatsHelp`의 "그래프" 문구를 Task 1 Step 6의 Task 2 문구로 바꾼다.
  - 스펙 §3.3을 고친다. 막대 값 글씨, [전체] 24달 규칙, 누적 그래프, ? 설명 6곳과 문구를 적는다. "상승" 칩은 "상승 고도"로 고친다.
  - 스펙 §5의 문구 목록을 고친다.
  - CHANGELOG `[1.4.0]`의 추가·변경에 한 줄씩 넣는다.
  - 검증 문서 점검표에 다음을 넣는다.
    - 칩을 바꾸면 막대 값과 누적선이 함께 바뀌는지
    - ? 6곳의 말풍선
    - [전체] 달 막대
- [ ] **Step 5: 기기 시험**
  - `StatsScreenTest`에 다음을 더한다.
    - [월]에서 누적 그래프가 보이고 [전체]에서는 없음
    - 범례 이름
    - 칩을 바꾸면 끝 값 글씨가 바뀜
  - `screenshot("v140-stats-cumulative")`를 하나 더한다.
  - S22에서 `StatsScreenTest`를 돌린다.
- [ ] **Step 6: JVM 전체와 커밋**
  - `./gradlew :core:test :app:testDebugUnitTest`를 돌려 모두 통과해야 한다.
  - 커밋 메시지: `app: stats — cumulative line chart (this vs previous period, goal pace)`, `docs: stats charts and help in spec, changelog, verification`
