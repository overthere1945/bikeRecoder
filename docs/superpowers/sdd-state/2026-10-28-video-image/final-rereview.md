# V1.3.0 최종 리뷰 수정 재검토 (88dc8b4..d743389)

### Finding Verdicts

- **#1 (I1) VIDEO 지도를 받는 동안 오프라인 mutex를 몇 분씩 잡는다** — ADDRESSED
  - 우회 없음. `MapLibreOfflineController.kt`에 `mutex.`가 쓰이는 곳은 `locked` 안(302, 309행)뿐이다. `withLock` import는 지워졌다. mutex를 쓰는 곳 7개가 모두 `locked`를 거친다: `deleteForTrip` 111, `downloadForTrack` 126, `deleteVideoRegions` 156, `deleteAll` 190, `download()` 246·250, 실패 정리 284. `locked` 블록 안에서 다른 `locked`를 부르는 곳은 없다(`removeTripRegions`/`decide`/`findTripRegions`/`downloadRegion`은 잠그지 않는다). 그래서 다시 들어가는 교착은 없다.
  - 수는 모든 경로에서 맞다. `locked`(299-312)는 `incrementAndGet()` 뒤 `try { mutex.lock() } finally { decrementAndGet() }`이다. 대기 중 취소, `lock()`의 다른 예외, 정상 획득 모두 내린다. 잡은 뒤에는 `try { block() } finally { mutex.unlock() }`이라 non-local `return`(246, 252의 SKIP)에도 풀린다. 0보다 커지는 것은 `lock()`을 부르는 순간뿐이다. 그래서 VIDEO가 lock을 쥐고 있을 때 0보다 크면 실제 대기자가 있다는 뜻이다.
  - 굶주림이나 반복 진입은 없다. `yieldWhen`을 넘기는 곳은 `downloadForTrack`(142)뿐이다. `VideoJob`은 `downloadForTrack`을 run에서 한 번(`VideoJob.kt:272`), 시작 확인에서 오프라인일 때만(`:232`) 부른다. 다시 시도하는 반복문이 없다. 양보한 뒤 true가 돌아오면 job은 네트워크로 그린다. Mutex는 FIFO라서 대기자가 바로 잡는다.
  - Boolean 계약은 그대로다. 양보는 `awaitComplete`의 stall 루프 안에서 `break`할 뿐이다(376행, stall `break`와 같은 모양). finally가 `setDownloadState(INACTIVE)`(383)를 부르고, `downloadRegion`이 정상 반환한 뒤 블록 끝의 `true`가 나간다. 오프라인 경로(131-132)와 완성된 VIDEO를 다시 쓰는 경로(128-129)는 바뀌지 않았다.
  - 여행 지도의 동작은 같다. `download()`는 `downloadRegion(..., stallMs = null)`을 부르고 `yieldWhen`은 기본값 null이다. `stallMs == null`이면 `done.await()` 분기라서 폴링하지 않는다. `deleteForTrip`/`deleteAll`이 영역을 찾는 방식(`removeTripRegions` ALL_KINDS, `ownRegions`)도 바뀌지 않았다. 양보한 VIDEO 영역은 이미 ref가 기록되어 있고(`downloadRegion` NonCancellable 블록), mutex를 넘기기 전에 INACTIVE가 된다. 그래서 다음 대기자가 관찰자가 붙은 채 받는 중인 영역을 지울 일은 없고, 지우기는 그 영역을 찾는다.
  - 기기 테스트가 증명하는 것:
    - `aVideoDownloadYieldsToDeleteForTrip`(MapLibreOfflineControllerTest.kt:337)와 `aVideoDownloadYieldsToDeleteAll`(:367)은 먼저 VIDEO가 실제로 받는 중임을 단언한다(`awaitVideoDownloading`: 완료 0 초과, `!isComplete`). 그다음 지우기를 `withTimeout(5_000)`으로 감싸고, `downloadForTrack`이 5초 안에 true를 돌려주는지 단언한다. 폴링 주기가 1초이니 5초 상한은 판별력이 있다.
    - 보고서의 RED 기록에서 예전 코드는 두 테스트 모두 5000 ms 타임아웃이었다. GREEN은 1.24 s와 2.05 s였고, logcat의 yield 로그가 2회 나왔다.
    - 방금 양보한 영역의 삭제: `deleteAll` 테스트가 `ourRegions().isEmpty()`와 `refs.all().isEmpty()`로 확인한다. `deleteForTrip(같은 여행)`이 방금 양보한 VIDEO를 지우는 경우는 따로 시험하지 않았다. 다만 같은 `removeTripRegions(ALL_KINDS)` 경로이고, 기존 테스트(“여행을 지우면 영상 지도도 함께 지움”)가 덮는다.
- **#2 (I2) 출판 뒤의 실패가 저장된 영상을 실패로 바꾼다** — ADDRESSED
  - `VideoJob.kt:285` `published = true` 다음 289행에서 바로 `result = Done(media(id 0))`로 정한다.
  - 출판 뒤 세 단계는 각각 `attempt`로 감쌌다. `attempt`(384-392)는 Exception을 로그로만 남기고 Cancellation은 다시 던진다.
    - `setPending(null)`
    - `tripMedia.add`: 성공하면 id를 넣어 292행에서 Done을 다시 정한다.
    - `recordSpeed`
  - `catch (CancellationException)`(297)과 `catch (Throwable)`(302)은 모두 `if (!published)`일 때만 result를 바꾼다. 그래서 출판 뒤에 Error가 `attempt`를 뚫고 나와도 Done이 유지된다. 이때 남은 `recordSpeed`는 건너뛰지만 결과는 Done이다.
  - 테스트 `aPublishedVideoIsDoneEvenWhenItsRowCannotBeAdded`는 id 0, published, deleted 비어 있음, pending null, 속도 기록, 경고를 확인한다. `anErrorAfterPublishingStillEndsDone`은 OOM이 나도 Done인지 본다. 두 테스트가 판정과 맞다.
- **#3 (M1) cleanup 중 Error가 나면 claimed/runningTripId/state가 남는다** — ADDRESSED
  - `VideoJob.kt` finally에서 정리 블록을 `try { withContext(NonCancellable){…} } finally { … }`로 감쌌다. 바깥 finally는 `_state.value = result`(314), `_runningTripId = null`, `running = null`, `claimed.set(false)`(317)를 부른다. 그래서 정리 블록에서 Throwable이 나와도 이 네 줄은 반드시 실행된다. Error는 삼키지 않는다.
  - 테스트 `anErrorInTheCleanupStillReleasesTheJob`은 `deleteVideoRegions`에서 StackOverflowError를 낸다. 그 뒤 Done, `isRunning` false, `runningTripId` null, handler로 전파, 다음 `startImage` STARTED를 확인한다.
- **#4 (M2) 인코딩 마지막 순간에 기록이 시작되면 미리보기가 기록 화면 위로 열린다** — ADDRESSED
  - `VideoViewModel.kt:272` `previewToOpen`은 Preview이고 `!recording()`일 때만 미리보기를 돌려준다. `recording`은 `rideState is Starting || Active`이다(`VideoViewModel.kt:428` 주입).
  - `BikeNavHost.kt:294-300`는 그 값이 null이면 `openPreview`도 `cancelResult`도 부르지 않는다. `outcomeShown()`(300)은 그대로 부른다.
  - 결과 알림은 `VideoService` mirror가 Done을 보고 올린다(`VideoService.kt:73`). 앱은 그것을 거두지 않는다. 알림을 누르면 `LaunchRequest.Preview`를 거쳐 미리보기가 열린다(BikeNavHost 기존 경로). 남는 경합은 아래 Out-of-Scope에 적었다.
  - 테스트 `done while recording is not opened on screen`가 이 동작을 덮는다.
- **#5 (M3) 문서 `video-field-test.md:101` 실패 대화상자 문장과 변경된 판정 줄** — ADDRESSED
  - 101행이 리뷰 M3 문장과 일치한다.
  - "사용자에게 보이는 동작을 바꾼 판정"에 줄을 더했다.
    - 104: #4, 녹화 중에 끝나면 알림만
    - 105: #1, 영상 지도 받는 중에도 기다리지 않음
    - 106: #2, 만든 뒤 여행이 지워져도 미리보기
    - 107: #3, 정리 중 오류에도 작업이 풀림
  - 표의 수는 보고서와 맞는다: app 786, 합계 1110, MapLibreOfflineControllerTest 12, "18개 클래스 105개"(103+2).

### New Breakage in the Fix Diff

- Minor: `app/src/main/java/com/cowork/bikerecoder/ui/video/VideoViewModel.kt:262-272`
  - 원인: `previewToOpen`과 그 KDoc을 `outcomeShown`의 KDoc과 함수 사이에 넣었다.
  - 결과: 주석 블록 두 개가 연달아 있다. 첫 번째(`outcomeShown` 설명)는 어디에도 붙지 않는다. `outcomeShown`은 문서를 잃고, `previewToOpen` 앞에 엉뚱한 설명이 놓인다.
  - 고칠 방법: 첫 KDoc을 `fun outcomeShown()` 바로 위(274행)로 옮긴다.
- 그 밖에는 None.

### Out-of-Scope Observations

- 녹화 중 Done일 때 미리보기로 가는 길은 이제 결과 알림 하나뿐이다. 그 알림이 꼭 생긴다는 보장은 없다. 기존 m1·m5와 같은 성격이다.
  - (a) 경합: `VideoService` mirror(`VideoService.kt:64-73`)가 Done을 보기 전에 앱의 `outcomeShown()` → `acknowledge()`가 state를 Idle로 바꿀 수 있다. 그러면 `showResult(Idle)`이 되고, `resultText`가 null이라 알림이 생기지 않는다. 실제로는 mirror가 Main에서 바로 collect하고, 앱 쪽은 `stateIn` → 재구성 → `LaunchedEffect`를 거친다. 그래서 mirror가 먼저일 가능성이 높다.
  - (b) FGS 시작이 거절된 경우(m1)에는 mirror가 없어서 알림이 아예 없다.
  - 막는 방법: 미리보기를 열지 않았을 때 `BikeNavHost`가 `VideoNotifications.showResult(context, Done)`을 직접 한 번 부른다. 같은 RESULT_ID라서 중복되지 않는다.
  - 비차단으로 기록만 한다.
- VIDEO 다운로드는 대기자가 영역 생성 중에 와도 영역을 만들고 ACTIVE로 둔다. 그 뒤 최대 1초 동안 받다가 양보한다. 동작에는 문제가 없지만, `setDownloadState(ACTIVE)` 전에 `yieldWhen`을 한 번 보면 이 1초와 데이터 낭비를 줄일 수 있다(선택).

### Verdict

**Fix round:** All findings addressed, no new Critical/Important breakage. Minor 1건(VideoViewModel KDoc 위치)만 있다.
