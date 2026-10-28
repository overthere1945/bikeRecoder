# V1.2.1 최종 리뷰 (브랜치 전체: acb9c7e..c62df84)

이번 리뷰는 정적 리뷰다. gradle과 adb는 실행하지 않았고(컨트롤러가 S22 기기 테스트를 돌리는 중), 이 파일 말고는 아무것도 고치지 않았다.

읽은 것:
- reviewer-instructions, global-constraints(R17–R19), progress(미뤄 둔 사소한 항목 전부)
- 계획, 스펙 §3·§4·§6, V1.2.0 판정(R1–R16, I4·I5)
- 작업 리뷰 4개(task-1 리뷰와 재리뷰, task-2, task-3)
- 합친 diff와 그 주변 코드: PostProcessor, HrStatus, Daos, HealthConnectHeartRateSource, SummaryExtras, HeartRateRow, Segment/TripDetailViewModel, MainActivity, CumulativeMap*, BikeMap, CumulativeSummary

줄 번호는 c62df84 기준이다.

## 판정

**머지 가능. 단, 아래 FIX-NOW 3건을 한 번에 처리한 뒤에.**
- Critical 0, Important 0.
- 스펙 §6.1–§6.7, 판정 R17–R19, V1.2.0 판정 R3·R7·R8·I4·I5·R13–R16과 어긋나는 곳이 없다.
- 실제 S25 시나리오와 S25 누적 지도 데이터도 코드로 끝까지 따라가 보니 기대대로 나온다.
- FIX-NOW는 둘이다. 하나는 자주 보이는 화면 다듬기(주 칩 이름)이고, 다른 하나는 문서와 테스트 정리다.

---

## 1. S25 시나리오 따라가기

전제:
- 15분 주행(이동 15분)이 21:02에 끝난다.
- 워치는 그 전부터 "걷기" 운동 중이고, 운동은 22:30에 끝난다.
- 자세한 심박과 칼로리는 22:35에 Health Connect에 들어온다.

**21:02 — 구간이 끝나고 요약 화면이 뜬다**
- `SummaryExtras`(SummaryExtras.kt:84)가 한 바퀴를 요청한다.
- 요약 화면이 떠 있는 동안 1분마다 최대 15번 다시 읽는다(SummaryExtras.kt:103-113).
- 이 시점의 상태는 extras 없음 또는 PENDING이고, 화면에는 "심박 가져오는 중"이 보인다.

**21:03 — 첫 읽기**
- `HealthConnectHeartRateSource.read`가 운동 기록을 ±12시간 범위로 읽는다(:57-65).
- 걷기 운동은 아직 끝나지 않아 Health Connect에 없다. 그래서 읽기 범위가 [시작−1분, 끝+1분]에 머문다(:161-166).
- 평소 심박 1개만 읽힌다. 겹치는 운동이 없으므로 칼로리는 null이다(:68-81).
- 저장 결과(PostProcessor.kt:292-301): `Samples(1)` → READY, `hrDetailed = false`(1 < 15분), calories null.
- 화면에는 "평균 심박 N · 최고 N bpm · – kcal"과 안내 "워치에서 운동을 시작하면 자세한 심박이 기록됩니다"(HeartRateRow.kt:40-46, R19)가 보인다.

**21:04–21:17 — 요약 화면의 1분 다시 읽기**
- 매번 1개가 읽힌다. 같은 수라서 지키는 분기(PostProcessor.kt:289-291)로 가고, `hrLastTriedAt`만 바뀐다.
- 칼로리는 계속 null이다(R17: null ?: null).
- 15번을 다 쓰면 이 반복은 멈춘다. 스펙 §3.2의 "최대 15분" 그대로다.

**22:35 이후 — 다음 바퀴**
바퀴를 부르는 곳은 넷이다.
- 앱이 화면에 다시 올 때: `MainActivity.onStart`(MainActivity.kt:34-38). 화면을 껐다 켜기만 해도 된다.
- 구간 상세를 열 때(SegmentDetailViewModel.kt:131-134)
- 여행 상세를 열 때(TripDetailViewModel.kt:205-209)
- 누적 지도를 열 때(CumulativeMapViewModel.kt:134)

그 바퀴에서 일어나는 일:
1. `segmentsNeedingWork`가 이 구간을 고른다. 조건은 `READY AND hrDetailed = 0 AND now <= endedAt + 24h`(Daos.kt:183-188)이고, `HrRetryPolicy.shouldTry`(HrStatus.kt:26-32)와 같은 식이다.
2. 접근 확인은 한 바퀴에 한 번만 한다(PostProcessor.kt:148-149, I4).
3. 권한이 없으면(R8) 읽지도 쓰지도 않는다. READY와 그 값은 그대로 남는다.
4. 읽기:
   - 걷기 운동이 [from, to]와 겹치므로 심박 읽기 범위가 운동 전체로 넓어진다(:161-166).
   - 운동의 자세한 심박이 들어오고, [from, to] 안의 것만 남긴다(:149-155).
   - 칼로리: 운동 종류를 거르지 않는다(:171-172). 운동 칼로리 × 15분 / 운동 길이를 쓰고, 겹친 운동은 한 번만 센다(:181-209, R18).
5. 저장: 샘플이 많아졌으므로 덮어쓰는 분기(PostProcessor.kt:292-301)로 간다. READY, `hrDetailed = true`, 새 평균·최고, `calories = read.calories ?: current.calories`가 저장된다.
6. 그 뒤에는 자세한 READY라서 바퀴(HrStatus.kt:30)도 요약 반복(SummaryExtras.kt:122-128)도 더 읽지 않는다.

**라이더가 보는 것**(모두 Room Flow로 바로 바뀐다)
- 요약: 줄이 "평균 · 최고 bpm · ○○ kcal"로 바뀌고 안내 문구가 사라진다.
- 구간 상세:
  - 줄이 같은 방식으로 바뀐다.
  - 저장 샘플은 `hrLastTriedAt`이 바뀔 때마다 다시 읽고(SegmentDetailViewModel.kt:96-100), 심박 그래프가 나온다.
  - [기본]/[심박] 선택이 나타난다(:109-111).
- 여행 상세:
  - 시간 가중 평균·최고·칼로리 합계와 날짜별 평균이 새 값으로 바뀐다(TripDetailViewModel.kt:107-125, 245-269).
  - 칼로리가 처음 생기면 줄에 kcal이 붙는다.
- 기록 목록: 심박 표시가 없으므로 바뀌는 것이 없다.

**결론: 다음 바퀴(앱을 다시 열기, 상세 화면 열기) 한 번으로 자세한 심박과 칼로리가 채워진다.**

남는 경계 둘(코드 결함은 아님):
- (a) 22:35부터 21:02+24h 사이에 앱을 한 번도 열지 않으면 자동으로는 채워지지 않는다. [다시 가져오기]는 언제든 된다. 한도 없이 읽고(PostProcessor.kt:111-123), 샘플이 더 많으면 덮어쓴다.
  - 실제 10/25 S25 주행이 바로 이 경우다. 이미 24시간이 지나서 V1.2.1을 설치해도 자동으로 다시 읽지 않는다. 그 기록은 **[다시 가져오기]를 눌러야** 자세한 심박과 걷기 칼로리로 바뀐다.
  - 사용자에게 알려 줄 것(FIX-NOW 3에서 문서에 적는다).
- (b) 자세한 심박이 먼저 들어오고 칼로리가 더 늦게 들어오는 경우가 있다. 그러면 자세한 READY인데 calories가 null인 채로 멈춘다(Task 1 미뤄 둔 항목). 아래 분류는 LEAVE다.

### V1.2.0 판정과 맞물리는 곳
- **R3**: PENDING은 언제나 다시 읽는다. `shouldTry`와 DAO에서 PENDING 분기가 그대로다. 성긴 READY 조건은 OR로 더해졌을 뿐이다.
- **R7**:
  - READY에 Failed나 Empty가 오면 `current.tried(now)`만 저장한다(PostProcessor.kt:304).
  - 같은 수나 더 적은 수가 오면 샘플을 지키고 칼로리만 채운다(:289-291).
  - 이미 있는 칼로리는 지우지 않는다(:300).
  - 바퀴 경로 테스트 `aPassKeepsSparseHeartRateOnFailedOrEmpty`도 있다.
- **R8**:
  - 권한이나 Health Connect가 없으면 바퀴에서 읽지도 쓰지도 않는다(:190).
  - [다시 가져오기]와 요약 반복은 `hrLastTriedAt`만 바꾼다(:288).
  - READY는 FAILED로도 PENDING으로도 바뀌지 않는다.
- **I4**: 할 일 목록에 성긴 READY가 들어와도 `readable` 캐시(:148-149) 덕분에 한 바퀴에 접근 확인은 많아야 한 번이다.
- **I5**: 요약 반복은 "READY && detailed"에서 멈춘다(SummaryExtras.kt:122-128). 성긴 READY는 15번까지 계속 읽는다. 바퀴의 24시간 조건과 서로 어긋나지 않는다.
- **R13–R16(사진)**:
  - 성긴 READY 구간이 할 일 목록에 다시 올라와도 사진 단계는 `!photoScanDone`과 R16 간격(:181-186, :203-206)을 그대로 지난다.
  - FULL 접근이면 이미 끝난 구간은 건너뛰고, PARTIAL 접근이면 하루 간격이 그대로 지켜진다. 새로 생기는 사진 IO는 없다.
- **R14**: FAILED 창(HrStatus.kt:29)과 DAO의 MAX 식은 바뀌지 않았다.
- **비용**: 성긴 READY 구간마다, 구간 끝 + 24시간 동안, 바퀴마다 Health Connect 읽기 3번과 extras 쓰기 1번이 든다.
  - 쓰기가 일어나면 열린 상세 화면이 샘플을 다시 읽는다.
  - 앱이 화면에 있을 때만 돌고 하루로 끝나므로 받아들일 만하다.

## 2. 누적 지도와 S25 데이터

데이터: 1870 m와 1586.7 m 구간, 그리고 0.27 / 0.10 / 0.08 / 0 m 흔들림 구간.

- `CumulativeSummary.rides`(CumulativeSummary.kt:38-40)는 `>= 10.0`인 두 구간만 남기고, 최신 순으로 정렬한다.
- `load()`가 그 목록 하나만 만들고(CumulativeMapViewModel.kt:211), 맞춤(fit)도 이 목록으로 만든다(:212-216). 그래서 흔들림 구간의 점은 카메라 맞춤에 들어가지 않는다.
  - Task 2 미뤄 둔 항목 "fit이 `distanceM > 0`을 쓴다"는 해결됐다.
- `cumulativeStateOf`(:251-276)
  - 목록·선·합계·연·월·주 메뉴가 모두 이 목록 위에서 계산된다.
  - 요약은 "3.46km · 주행 2회 · …"이다.
  - 목록은 2줄이고 색은 `#E53935`(최신), `#1E88E5` 두 가지다. 줄은 "10월 25일 (토) · 1.59km · 15분" 형식이다.
  - 선도 2개다(점이 2개 이상인 것만, BikeMap.kt:593-617).
- 기록 목록은 흔들림 구간도 계속 보여준다(스펙 §6.3).

**결론: "주행 2회", 2색·2줄, 흔들림을 뺀 맞춤. 모두 기대대로다.**

## 3. 회귀 점검
- **여행·구간 상세 지도**
  - `MapOverlay.rides`의 기본값이 비어 있어서 그 화면들에서는 새 레이어가 빈 채로 남는다.
  - 지도 탭 처리(BikeMap.kt:259-275)는 `onRideClick`이 null이면 예전과 같은 값을 돌려준다. 사진을 맞히면 true, 아니면 false다. 사진 탭이 먼저다.
  - `faintLines`와 `FAINT_*`는 저장소에 남은 것이 없다.
  - 트랙과 심박 색 선(`trackGeoJson`, `MapLine`)은 바뀌지 않았다.
- **주행 기록 중 갱신 간격**: 트랙의 `atMostEveryUnless(TRACK_UPDATE_MS…)`(BikeMap.kt:344)는 그대로다. 주행 선 효과(:348)는 `rides`와 `selectedRide`가 바뀔 때만 다시 돌고, 누적 지도 말고는 둘 다 늘 빈 값이다.
- **설정**: 바뀐 것은 버전 문자열뿐이다(build.gradle.kts verPatch 1, `SettingsScreenTest`의 `V1.2.1`).
- **문서**: CHANGELOG `[1.2.1] - 개발 중`, README 버전 줄, 실주행 문서 V1.2.1 절이 모두 맞다. 커밋 서명도 커밋을 쓴 모델(Opus/Sonnet 5.5)과 맞다.

## 발견 사항

### Critical
없음.

### Important
없음.

### Minor (아래 분류에 반영)
1. **[이번 주]처럼 주행이 없는 주를 고르면 [주] 칩 이름이 "주 ▾"로 돌아간다.**
   - 위치: CumulativeMapScreen.kt:209-213
   - `weeks()`(CumulativeSummary.kt:77-91)는 주행이 있는 주만 준다. 그래서 칩은 선택된 모양인데 이름이 "주 ▾"다.
   - 주말에만 타는 라이더라면 월요일부터 첫 주행 전까지, 즉 한 주의 대부분 동안 [이번 주]가 이렇게 보인다.
   - 이름은 라벨 문자열을 잘라서 만든다(:212, `substringAfter("월 ")`). core 라벨 형식에 숨은 의존이 생긴다.
2. **`select()`의 KDoc이 실제 동작과 다르다.**
   - 위치: CumulativeMapViewModel.kt:173-176
   - KDoc은 "A ride not listed is ignored"라고 하지만, 실제로는 강조를 푼다(:180-182).
   - 테스트 `selectAndDeselect`(CumulativeMapViewModelTest.kt:486)는 `select(99)`를 선택이 없는 상태에서 불러서 두 해석을 구별하지 못한다.
3. **실주행 문서 9번 항목이 언제 바뀌는지를 말하지 않는다.**
   - 위치: docs/verification/trips-field-test.md(V1.2.1 체크리스트 9번)
   - 합격 기준은 "몇 분~수십 분 안에 자세해짐"이다. 그런데 요약 반복은 15분에서 멈추고, 그 뒤에는 **앱을 다시 열거나 상세 화면을 열 때** 바뀐다.
   - 이것을 모르고 요약만 보고 있으면 "불합격"으로 잘못 적을 수 있다.
   - 이미 24시간이 지난 10/25 기록은 [다시 가져오기]로 확인해야 한다는 것도 빠져 있다.

## 미뤄 둔 사소한 항목 분류 (progress.md 전부)

| 출처 | 항목 | 판정 | 이유 |
|---|---|---|---|
| T1 | `store.samples(id).size` 대신 COUNT 쿼리 | LEAVE | 성긴 READY는 샘플이 몇 개뿐이고, 자세한 READY는 수동으로 누를 때만 읽는다. 비용이 작다. |
| T1 | 자세한 READY인데 calories가 null이면 자동으로 다시 읽지 않음 | LEAVE (사용자에게 알림) | 스펙 §6.1이 "자세해지면 멈춘다"고 정했다. 자동으로 다시 읽으려면 스펙 판정이 필요하다. 칼로리 권한이 없는 라이더(R6)는 하루 내내 읽게 된다. [다시 가져오기]로 채울 수 있다(R17). 실주행 9번이 실제로 생기는지 드러낸다. |
| T2 | VM fit이 `distanceM > 0`을 씀, `rides()`를 한 번만 계산 | 해결됨 | T3에서 고쳤다(CumulativeMapViewModel.kt:211-217). |
| T2 | 테스트 빈틈(흔들림만 있는 해·달, 달 끝·2월·해를 넘는 주, 월요일 시작 포함) | LEAVE | 구현은 손으로 따져 맞다. 회귀 방지만 빠졌다. |
| T2 | `filter()`가 weekStart가 월요일인지 확인하지 않음 | LEAVE | 부르는 곳이 모두 월요일을 넘긴다. |
| **T3-1** | 주행 없는 주에서 [주] 칩이 "주 ▾"로 돌아감(예: 월요일 아침 [이번 주]) | **FIX-NOW** | 흔히 보이고, 화면 상태(선택됨)와 이름이 어긋난다. 고치기 쉽다. |
| **T3-2** | 칩 이름을 라벨 문자열에서 잘라 냄 | **FIX-NOW (T3-1에 포함)** | 번호를 직접 계산하면 함께 사라진다. |
| **T3-3** | `select()` KDoc 불일치와 구별하지 못하는 테스트 | **FIX-NOW** | 문서 한 줄과 단언 하나다. 위험이 없다. |
| T3-4 | 빠른 두 번 탭이 `state.value`로 토글을 판단 | LEAVE | 아주 드물고, 다시 누르면 된다. 데이터 피해가 없다. |
| T3-5 | VM [이번 주] 테스트가 달을 넘는 주행을 넣지 않음 | LEAVE | core 테스트와 `selectWeek`(9/28 주)가 이미 확인한다. |
| T3-6 | 지도 선 탭을 확인하는 자동 테스트가 없음 | LEAVE | `pickRide`는 JVM에서 검증했고, 실주행 10번이 다룬다. |
| T3-7 | 선택할 때마다 GeoJSON 전체를 다시 만듦 | LEAVE | 스펙이 갱신 간격 없음을 요구했고, Default 스레드에서 만들며 새 값이 오면 취소된다. 지금 규모에서는 문제없다. |

## FIX-NOW (한 번에 처리)

1. **[주] 칩 이름을 weekStart에서 계산한다**(T3-1, T3-2)
   - core `CumulativeSummary`:
     - `fun weekOf(start: LocalDate, year: Int, month: Int): WeekOption`을 꺼낸다. 번호 N = `WEEKS.between(mondayOf(그 달 1일), start) + 1`이고, 라벨은 지금과 같다.
     - `WeekOption`에 `number: Int`를 더한다.
     - `weeks()`는 이 함수로 만든다.
   - 앱:
     - `cumulativeStateOf`가 `period.weekStart`와 연·월이 있으면 `week = weekOf(...)`를 상태에 넣는다.
     - `weekChipLabel`(CumulativeMapScreen.kt:209-213)은 `"${week.number}주 ▾"`를 쓰고, 문자열을 자르지 않는다.
   - 테스트(TDD):
     - core `weekOfAWeekWithoutRides`: 2026-10-26 → 10월 5주. 2027-01-01이 든 주(12/28)는 1월에서 1주다.
     - JVM `cumulativeStateOf`(또는 VM): 주행 없는 [이번 주]의 번호.
     - 기기 `CumulativeMapScreenTest`: 주행 없는 주를 고르면 "N주 ▾"와 "이 기간에 기록이 없습니다"가 함께 보인다. S22에서만, 이 클래스만 돌린다.
2. **`select()` KDoc을 동작에 맞추고 테스트로 고정한다**(T3-3)
   - CumulativeMapViewModel.kt:175를 "A ride not listed lets go of the highlight"로 고친다.
   - `selectAndDeselect`에서 선택이 있는 상태로 `select(99)`를 불러 `selected == null`을 단언한다.
3. **실주행 문서 9번에 확인 방법을 적는다**(문서만)
   - docs/verification/trips-field-test.md 9번 합격 기준과 메모에 둘을 더한다.
     - 요약 화면은 15분까지만 스스로 다시 읽는다. 그 뒤에는 운동이 끝나고 몇 분 뒤 **앱을 다시 열거나(화면을 껐다 켜도 됨) 구간 상세를 열면** 바뀐다.
     - 10/25처럼 24시간이 지난 기록은 [다시 가져오기]를 눌러 자세한 심박과 칼로리가 나오는지 확인한다.
