# 최종 리뷰: V1.2.1 보완 2 (짧은 기록 · 기록 카드), 03072fb..b4b313e

## 판정

**작은 수정 한 번(아래 FIX-NOW)을 거치면 병합할 수 있다.**

- Critical: 없음.
- Important: 문서 1건(I1). 코드에는 Important가 없다.
- 실기기 데이터 삭제 안전성: 지켜진다.
  - 사용자가 아끼는 것은 [정리]로 지워지지 않는다. 10 m 이상 기록, 진행 중인 여행, 구간이 없는 안내 전용 여행이 그렇다.
  - 다만 구간이 없는 여행은 지금 "호출자가 id를 고르는 방식" 덕분에 안전한 것이고, SQL 문장 자체가 막아 주지는 않는다. 그래서 FIX-NOW 4로 SQL에서도 막는다.
- 테스트
  - `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL. 결과는 UP-TO-DATE였다.
  - b4b313e는 문서만 바꿨으므로 df9a350 위에서 만든 결과가 그대로 유효하다.
  - JUnit XML 기준 core 28/261, app 62/683, 실패 0, 건너뜀 0.
  - 문서에 적힌 클래스별 수도 XML과 맞다: SummaryExtrasTest 19, HistoryViewModelTest 26, RoutePreviewTest 4, HistoryFormatTest 3.
  - adb는 쓰지 않았다.

## 1. S25 실제 데이터 따라가기

데이터:
- 구간 6개: 1870 m, 1586.7 m, 0.27 m, 0.10 m, 0.08 m, 0 m.
- 여행 8개.

구간이 어느 여행에 속하는지는 저장소 밖 정보라서, 코드 경로를 기준으로 따라간다. 근거는 `RoomRideStore.kt:50-73`, `Daos.kt:30-48, 168-181`, `HistoryViewModel.kt:212-220`이다.

| 여행 종류 | `tripRideRows` (`distanceM > 0`) | `emptyTripIds` (`MAX ≤ 0`) | 기록 목록 | "짧은 기록 N개 숨김" | [정리] |
|---|---|---|---|---|---|
| 1870 m / 1586.7 m가 든 여행 (같은 여행이든 다른 여행이든) | 들어감, 합계 ≥ 10 m | 아님 | 카드로 보임 | 안 셈 | 대상 아님. SQL `SUM < 10`도 거절 |
| 0.27 / 0.10 / 0.08 m만 있는 여행 (0 m가 섞여도 같음) | 들어감, 합계 < 10 m | 아님 (MAX > 0) | 숨김 | COMPLETED이고 열린 구간이 없을 때만 셈 | 지움 |
| 0 m 구간만 있는 여행 (R21) | 없음 | 들어감 | 숨김 (예전에도 안 보였음) | COMPLETED이고 열린 구간이 없을 때만 셈 | 지움 |
| 짧은 구간이 있는 ACTIVE 여행 (다일 여행의 중간 등), 또는 아직 기록 중인 여행 | 들어감 또는 아님 | 들어감 또는 아님 | 보임 | 안 셈 (`finished()`) | 대상 아님. SQL `status='COMPLETED'`, `endedAt IS NULL`도 거절 |
| 구간이 하나도 없는 여행 (안내만 한 여행) | 없음 | 없음 | 안 보임 (§3.5, 예전과 같음) | 안 셈 | 대상 아님 (FIX-NOW 4 참고) |

### 예상 화면

- **카드:** 긴 기록이 든 여행 1개 또는 2개.
  - 둘이 다른 여행이고 같은 달이면 머리글은 "2026년 10월 · 3.46km · 2회"이다.
  - 짧은 구간이 긴 기록과 같은 여행에 있으면 그 여행 합계에 0.x m가 더해진다. 그 짧은 구간도 미리보기에 색 하나를 차지한다(아래 M3).
- **숨김 수 N:** COMPLETED이고, 열린 구간이 없고, 구간이 모두 {0.27, 0.10, 0.08, 0} 안에 있는 여행의 수.
  - 짧은 구간이 하나씩 다른 여행에 있으면 4.
  - 0 m 구간이 짧은 구간과 같은 여행에 있으면 3.
  - 짧은 구간 일부가 긴 기록 여행 안에 있으면 더 적다.
- **구간 없는 여행:** 8 − (구간이 있는 여행 수), 2개 이상이다. 목록에도, N에도, [정리]에도 들어가지 않는다.

### [정리]가 실제로 지우는 것 (`HistoryViewModel.kt:166-183`, `TripDeleter.kt:24-34`, `Daos.kt:43-48`)

1. VM mutex 안에서 `split()`을 다시 계산한다.
2. 그 결과를 확인 대화상자가 센 id와 교집합한다.
3. 여행마다 한 문장으로 지운다:
   `DELETE … WHERE status='COMPLETED' AND 열린 구간 없음 AND SUM(distanceM) < 10`
4. 행이 실제로 지워진 여행에만 `deleteForTrip`을 부른다.
   - COMPLETED 여행의 지도는 `complete()` 때 이미 지워지므로, 보통은 할 일이 없다.
5. CASCADE로 함께 지워지는 것: 경유지, 구간, 위치, `ride_photo` 참조, `segment_extras`, 심박 샘플, 단순화 경로.

### 사용자가 아끼는 것

- **경유지는 있고 구간이 없는 여행:** 두 조회 모두 `ride_segment`에서 출발하므로 id 후보가 될 수 없다. 안전하다.
  - 단, `SHORT_NOT_RECORDING` 문장 자체는 구간 0개 여행을 받아들인다(합계 `COALESCE` = 0 < 10). 가짜 저장소도 같다(`RideTestDoubles.kt:138-139`).
  - 지금 경로로는 도달할 수 없지만, 보장이 SQL이 아니라 호출자에 있다. FIX-NOW 4로 고친다.
- **계획·안내 여행:**
  - "계획만 된 여행" 상태는 없다. 여행은 시작할 때 ACTIVE로 만들어진다.
  - ACTIVE 여행(진행 중인 안내, 다일 여행의 중간)은 `split()`과 SQL 양쪽에서 막힌다.
  - 짧은 기록만 있는 COMPLETED 안내 여행은 스펙대로 짧은 기록으로 지워진다. 그 경유지도 함께 지워진다.
  - 끝난 여행의 경유지를 다시 쓰는 곳은 없다.
    - `tripStore.stops`를 읽는 곳은 `TripStartViewModel.kt:277`, `MainViewModel.kt:241` 둘뿐이고, 둘 다 진행 중인 여행의 이름을 읽는다.
    - 최근 장소는 DataStore에 따로 있다.
  - 그래서 잃는 것이 없다.
- **사진·심박이 있는 COMPLETED 짧은 여행:** 앱의 참조와 사본만 지워진다. 갤러리 파일과 Health Connect 기록은 건드리지 않는다.
  - 앱 코드에 MediaStore나 Health Connect 삭제 호출이 없다(grep 확인).
  - 그래도 숨겨진 여행이라 사용자가 지우기 전에 볼 수 없다. 이것은 스펙 §6.10이 정한 동작이다.
  - S25의 0.08~0.27 m 기록은 개발 중 시험 기록이라 해당할 가능성이 낮다. 사용자 판단으로 남긴다(관찰 O1).
- **구간 0개 여행을 "짧은 기록"으로 세어야 하는가: 아니다. 지금 동작이 맞다.**
  - 기록 목록은 "기록이 있는 여행"의 목록이다(§3.5). 구간 0개 여행은 처음부터 기록으로 보인 적이 없다.
  - 이것을 "짧은 기록 N개"로 세면 사용자가 기록이라고 본 적 없는 것을 그 이름으로 지우게 된다.
  - R21 문서도 "구간이 하나도 없는 여행은 세지 않음"이라고 적고 있다.
  - 이런 여행이 쌓이기만 하는 것은 이 작업 전부터 있던 일이며, 범위 밖이다.

## 2. 연동

- **누적 지도:** 숨겨지는 여행은 합계가 10 m 미만이므로, 그 안의 모든 구간이 10 m 미만이다. 누적 지도에 나오는 구간(10 m 이상)은 숨긴 여행에 속할 수 없다.
  - 그래서 [정리]를 해도 누적 지도는 바뀌지 않는다.
  - 누적 지도 → 구간 상세 경로도 숨긴 여행으로 가지 않는다.
  - `rideRowOf`는 `cardDateText`를 함께 쓰도록 바뀌었고, 결과 문자열은 같다.
- **여행 상세:**
  - 구간 삭제(`TripDetailViewModel.kt:181`)는 바뀌지 않았다.
  - 큰 구간을 지워 합계가 10 m 미만이 되면, 돌아왔을 때(ON_RESUME에 다시 읽음) 그 여행 카드가 "짧은 기록 1개 숨김"으로 옮겨 간다. 스펙과 맞다(M4).
- **구간 상세:** 바뀐 것이 없다.
- **후처리:**
  - 지워진 구간은 `SegmentGone`으로 처리된다(`PostProcessor.kt:117, 154, 211`).
  - 24시간 다시 읽기는 저장소에서 구간을 다시 고르므로, 지워진 구간은 빠진다.
  - 요약을 닫으면 1분마다 하는 다시 읽기도 멈춘다.
- **삭제 경로:** 앱 코드에서 무조건 `RideStore.deleteTrip`을 부르는 곳은 없다(grep 확인). 여행 삭제는 모두 `TripDeleter`를 거친다.

## 3. 회귀

새 회귀는 찾지 못했다.

- 기록 목록의 한 건 삭제(NOT_RECORDING)는 예전과 같다.
  - 예전 사전 검사(`inProgress`)와 SQL 조건이 같은 뜻이다.
  - 다일 COMPLETED 여행도 그대로 지워진다.
- 요약의 [확인]/[닫기]가 [버리기]와 겹쳐도, 두 VM의 동일성 검사 때문에 두 번 닫히지 않는다.
- 빈 목록 문구 "기록이 없습니다"는 그대로다.
  - 짧은 기록만 있을 때는 그 문구와 꼬리 줄이 함께 보인다.
- LazyColumn 키는 겹치지 않는다(`month:…`, tripId, `empty`, `hiddenShort`).

## Findings

### Critical
없음.

### Important

**I1. 문서 세 곳이 아직 "기록 목록은 그대로(짧은 기록도 보임)"라고 적고 있다.** §6.10과 반대다.

- 해당 위치:
  - `docs/superpowers/specs/2026-10-18-trips-postprocess-design.md:171` (§6.3: "기록 목록은 바꾸지 않는다(짧은 시험 기록도 보여 지울 수 있게).")
  - `CHANGELOG.md:11` ("… 기록 목록은 그대로")
  - `docs/verification/trips-field-test.md:180` (6.3 판정: "기록 목록은 짧은 시험 기록도 보여 지울 수 있게 그대로입니다.")
- 문제: 같은 실주행 문서의 항목 13과 6.10 판정(`:184`)은 이 기록들이 숨겨진다고 적는다. 실주행 확인자가 서로 반대인 기준을 받게 된다.
- 고칠 방법: 6.4→6.8과 같은 방식으로 "(6.10에서 바뀜: …)" 꼬리를 붙인다.

### Minor

- **M1. 열린 목록이 바로 갱신되지 않는다** (`HistoryViewModel.kt:111-118`, `HistoryScreen.kt:44`).
  - 후처리 바퀴가 끝나도 미리보기·📷·♥가 바뀌지 않는다.
  - 누적 지도는 이미 `finishedRuns`/`routesSaved`를 구독한다(`CumulativeMapViewModel.kt:142-152`). 같은 데이터를 보여 주는 두 화면이 다르게 움직인다.
  - FIX-NOW 2.
- **M2. 기록 목록 삭제에서 지도 단계가 취소될 수 있다** (`TripDeleter.kt:26-32`).
  - 행을 지운 뒤 화면을 떠나면 `offline_region_ref`가 고아로 남는다.
  - R20 순서 변경으로 새로 생긴 경우다. 예전에는 여행이 남아서 다시 지울 수 있었다.
  - 설정의 "전체 삭제"로 회수할 수 있고, COMPLETED 여행은 보통 지도가 없으니 영향은 작다. 그래도 고치는 데 한 줄이면 된다. FIX-NOW 3.
- **M3. 짧은 구간이 긴 기록과 같은 여행에 있으면 미리보기에 색 하나와 경계 상자를 차지한다** (`HistoryViewModel.kt:266`, `Daos.kt:278` `distanceM > 0`).
  - 다른 장소에서 생긴 흔들림이면 경로가 작게 그려진다.
  - 스펙 문구("그 여행 구간들")와는 맞다. LEAVE.
- **M4. 여행 상세에서 큰 구간을 지우면 돌아왔을 때 카드가 사라지고 "숨김" 쪽으로 옮겨 간다.** 스펙과 맞으니 LEAVE.

### 관찰 (결정은 사용자에게)

- **O1.** [정리]는 사진이나 심박이 붙은 짧은 여행도 확인 한 번으로 지운다. 숨겨져 있어서 사용자가 미리 볼 수 없다.
  - 원본(갤러리, Health Connect)은 남는다.
  - 원한다면 "사진이 있는 짧은 여행은 [정리] 대상에서 뺀다"는 규칙을 스펙에 더할 수 있다. 이번 웨이브에는 넣지 않는다.

## 미뤄 둔 Minor 분류

| 항목 | 결정 | 이유 |
|---|---|---|
| 후처리가 끝난 뒤 기록 카드가 바로 바뀌지 않음 | **FIX-NOW 2** | 같은 화면 묶음 안에서 일관성이 깨진다(누적 지도는 이미 구독). 구현 패턴이 이미 있어 싸다. 실주행 항목 14와 문서의 "알려진 한계"를 없앨 수 있다. |
| 빈 미리보기 배경색 (`RoutePreview.kt:32`) | LEAVE | 스펙("옅은 배경")에 맞는다. FIX-NOW 2가 들어가면 잠깐만 보인다. 꾸밈 문제다. |
| `TripDeleter` 지도 단계를 NonCancellable로 | **FIX-NOW 3** | 이 브랜치가 만든 퇴행이다. 한 줄이고, 세 호출자의 동작을 같게 만든다(ShortRides는 이미 NonCancellable). |
| [정리]가 지도 삭제를 기다리는 동안 VM 락을 잡고 있음 | LEAVE | 다운로드가 돌고 있는 순간에만 생긴다. 데이터는 안전하고 끝나면 풀린다. 고치려면 `TripDeleter`를 둘로 나눠야 해서 비용 대비 가치가 낮다. |
| 그 밖의 LEAVE 항목 | LEAVE | 이전 판정 그대로: 거절 문구(여행이 이미 없음), 변환 가드 테스트, 배지 "여러 날 · 1일", Path 재생성, `trips` 테스트 전용, TalkBack "›", `cardDateText` 의존 방향, 처음 열 때 두 번 읽음. |

## FIX-NOW (한 웨이브)

1. **문서 (I1).** 아래 세 곳 끝에 "(6.10에서 바뀜: 기록 목록은 합계 10 m 미만이고 진행 중이 아닌 여행을 숨기고 [정리]로 지움)"을 붙인다.
   - `docs/superpowers/specs/2026-10-18-trips-postprocess-design.md:171`
   - `CHANGELOG.md:11`
   - `docs/verification/trips-field-test.md:180`
2. **기록 카드를 바로 갱신한다 (M1).**
   - `HistoryViewModel`에 `PostProcessor`를 받는다(`factory`의 `container.postProcessor`).
   - `init`의 `refresh()` 대신, `CumulativeMapViewModel.kt:142-152`처럼 `combine(postProcessor.finishedRuns, postProcessor.routesSaved) { _, _ -> }.conflate().collect { mutex.withLock { load() }; delay(간격) }`을 둔다. 첫 값이 첫 읽기를 대신한다.
   - ON_RESUME `refresh()`와 삭제 뒤 `load()`는 그대로 둔다.
   - JVM 테스트: 경로·사진·심박을 저장한 뒤 `finishedRuns`(또는 `routesSaved`)가 늘면 `months`의 카드에 routes, 📷, ♥가 나타난다.
   - `HistoryViewModelTest.history()` 도우미에도 반영한다.
   - `trips-field-test.md:187`의 "알려진 한계" 문장을 지운다.
3. **`TripDeleter`의 지도 단계를 `withContext(NonCancellable)`로 감싼다 (M2, `TripDeleter.kt:26-32`).**
   - 행을 지운 뒤라면 화면을 떠나도 그 여행의 지도 삭제가 끝까지 간다.
   - JVM 테스트: `deleteForTrip`이 막혀 있는 동안 VM 범위를 취소해도, 막힘이 풀린 뒤 그 여행의 지도 삭제가 실행된다.
4. **[정리] 문장도 구간 0개 여행을 직접 거절하게 한다 (§1, 사용자 불변식).**
   - `Daos.kt:43-48`의 `deleteShortCompletedNotRecording`에 `AND EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id)`를 더한다.
   - 가짜 `RideTestDoubles.kt:138-139`에도 `segments.isNotEmpty() &&`를 더한다.
   - 테스트:
     - `RoomRideStoreTest.conditionalTripDeleteChecksStatusAndSegments`에 "구간 없는 COMPLETED 여행은 SHORT_NOT_RECORDING이 거절"을 단언 하나로 더한다(S22 `R5CT43TFS9A`에서만).
     - JVM `HistoryViewModelTest`: 구간 없는 COMPLETED 여행은 세지도 지우지도 않는다(이미 사실인 동작을 고정).
   - 문서: R21 문구 "구간이 하나도 없는 여행은 세지 않음" 뒤에 "지우지도 않음"을 붙인다.

웨이브가 끝나면 다시 센다.
- PC: `--rerun-tasks`로 XML을 다시 센다.
- 기기: S22에서 `HistoryScreenTest`와 `RoomRideStoreTest`를 돌린다.
- 그 결과로 `trips-field-test.md`의 수를 고친다.
- S25에는 아무것도 하지 않는다.
