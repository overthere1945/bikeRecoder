### Finding Verdicts
- **FIX-NOW 1 (문서 I1: "기록 목록은 그대로" 세 곳)** — ADDRESSED. 스펙 §6.3, CHANGELOG, trips-field-test 6.3 판정 세 곳 모두 끝에 "(6.10에서 바뀜: 기록 목록은 합계 10 m 미만이고 진행 중이 아닌 여행을 숨기고 [정리]로 지움)"이 붙었다(diff의 해당 세 헝크). 6.11의 "알려진 한계" 문장은 지워지고 "후처리가 끝나면(또는 경로를 저장할 때마다) … 바로 바뀝니다"로 대체됐다.
- **FIX-NOW 2 (기록 카드 바로 갱신, M1)** — ADDRESSED. `HistoryViewModel.kt` init이 `combine(finishedRuns, routesSaved).conflate().collect { mutex.withLock { load() }; delay(500) }`로 바뀌었고, 생성자·factory(`container.postProcessor`)·테스트 도우미도 반영됐다. 테스트 `cardsRefreshWhenAPassFinishes`가 routes·📷·♥ 갱신과 "바퀴 끝 전에는 다시 읽지 않음"을 단언한다. 확인한 세부:
  - 두 플로우는 `MutableStateFlow`(PostProcessor.kt:65, 81)라 `combine`이 시작 즉시 첫 값을 낸다. 첫 값이 예전 `refresh()`를 대신하므로 첫 읽기는 한 번이다(ON_RESUME `refresh()`와 겹치는 것은 예전과 같고 LEAVE 항목).
  - 이중 읽기·누수 없음: 수집은 `viewModelScope.launch` 하나이고 VM 취소와 함께 끝난다. StateFlow 수집은 취소되면 구독이 풀린다. 읽기는 `delay`(락 밖)로 500 ms 이상 벌어진다. `request()`를 부르지 않으므로 목록이 바퀴를 시작하지 않는다.
  - 마지막 갱신을 잃지 않음: `conflate()`는 느린 수집자 앞에서 최신 값 하나를 남긴다. 읽는 중이나 500 ms 대기 중에 카운터가 늘면 그 값이 대기 뒤에 처리된다. 카운터는 단조 증가이고 `combine`은 중복 제거를 하지 않아 같은 값으로 삼켜지지 않는다. 바퀴가 끝나는 마지막 `finishedRuns` 증가도 마찬가지로 대기 뒤 읽기를 일으킨다.
  - [정리]와 교착 없음: `load()` 자체는 mutex를 잡지 않아(HistoryViewModel.kt:211) 재진입 문제가 없다. 수집자는 `mutex.withLock`에서 [정리]/삭제가 끝나기를 기다릴 뿐이고, [정리]는 수집자나 PostProcessor를 기다리지 않는다. PostProcessor의 `StateFlow` 갱신은 수집자 때문에 막히지 않는다. 지도 삭제가 다운로드를 기다리며 락을 오래 잡는 것은 LEAVE로 분류된 기존 동작이고, 수집자는 그동안 하나의 코루틴만 대기한다. `delay`가 락 밖이라 락을 쥔 채 잠자지도 않는다.
- **FIX-NOW 3 (TripDeleter 지도 단계 NonCancellable, M2)** — ADDRESSED. `TripDeleter.kt`의 `deleteForTrip` try/catch(로그 유지, `CancellationException` 재던짐 유지)가 `withContext(NonCancellable)`로 감싸졌다. 이것은 행 삭제 성공 뒤에만 실행된다. 세 호출자(ShortRides, 기록 삭제, [정리])가 모두 같은 동작이 된다. 테스트 `mapDeleteFinishesWhenTheListIsLeft`가 `deleteForTrip`이 막힌 동안 `viewModelScope.cancel()` 뒤에도 지도 삭제가 실행됨을 단언한다. 보고된 RED(`expected [1] but was []`)는 수정의 필요성과 일치한다. 취소된 뒤 mutex 해제와 후속 `load()`는 일반 취소 규칙으로 정리되어 문제 없다.
- **FIX-NOW 4 ([정리] 문장이 구간 0개 여행을 직접 거절)** — ADDRESSED. `Daos.kt`의 `deleteShortCompletedNotRecording`에 `AND EXISTS (SELECT 1 FROM ride_segment WHERE tripId = :id)`가 추가됐다(구문·바인딩 정상). 가짜 `FakeRideStore.deleteTripIf`에 `segments.isNotEmpty() &&`, KDoc에 "At least one segment", 기기 테스트 `conditionalTripDeleteChecksStatusAndSegments`에 단언 하나(그 뒤 `NO_SEGMENTS`로 지워지므로 여행이 남아 있었음을 확인), JVM 테스트 `aTripWithoutSegmentsIsNeitherCountedNorCleanedUp`, R21 문서의 "지우지도 않음"이 모두 들어갔다. 기기 테스트는 실행하지 않았고 보고(S22 9/9)는 검증하지 못한 주장이다. 다만 변경 SQL은 단순하다.

검증: 직접 `./gradlew :core:test :app:testDebugUnitTest`를 실행했다. 통과했고 JUnit XML은 core 261, app 686, 실패·오류·건너뜀 0으로 보고서(686/963)와 일치한다. adb는 쓰지 않았다.

### New Breakage in the Fix Diff
None.

### Out-of-Scope Observations
- 처음 열 때 목록을 두 번 읽는다(init 첫 값 + ON_RESUME). 예전과 같고 LEAVE로 분류된 항목이다.
- 기록 목록이 열려 있는 동안 어느 화면에서든 후처리 바퀴가 돌면 읽기가 일어난다(뒤 스택에 있어도). 500 ms 간격으로 제한되고 누적 지도와 같은 패턴이라 영향은 작다.
- `routesSaved` 경로만 따로 검사하는 테스트는 없다(보고서도 언급). `combine` 구조상 위험은 낮다.

### Verdict
**Fix round:** All findings addressed, no new Critical/Important breakage — CLEAN
