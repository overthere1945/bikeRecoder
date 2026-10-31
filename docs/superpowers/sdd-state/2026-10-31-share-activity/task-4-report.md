# Task 4 보고 — ShareViewModel

**상태:** DONE (concerns 있음)
**커밋:** `29a6f52 app: share — sheet state, make-then-send, saved text` (push 안 함)

## 바꾼 파일
- 새 파일 `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareState.kt`: `ShareSheetState`, `ShareEvent`
- 새 파일 `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareViewModel.kt`
- 새 파일 `app/src/test/java/com/cowork/bikerecoder/ui/share/ShareViewModelTest.kt` (시험 20개)
- `AppContainer.kt`: `val shareCandidates: ShareCandidates by lazy { ShareCandidates(mediaStoreWriter) }`

## State·event 설계
- **`ShareSheetState`:** 브리프의 13개 필드를 그대로 둔다. 다음 도우미를 더했다(Task 5용).
  - `canMakeImage` = `tripFinished && blocked == null`
  - `pick(target)`: STORY→story, EMAIL→email, 나머지(COPY 포함)→general
  - `enabled(target)`: FACEBOOK·INSTAGRAM·STORY·SAVE는 `pick이 File || canMakeImage`일 때만 true다. EMAIL·COPY·MORE는 늘 true다. 화면의 흐림은 이 값으로 정하면 된다.
- **`ShareEvent`:**
  - `Send(target, file?, text)`: file이 null이면 글만 보낸다.
  - `Copy(text)`
  - `Notice(message)`
  - `Channel.BUFFERED`와 `receiveAsFlow`로 만들었다. FavoritesViewModel과 같은 모양이다.
- **생성자:**
  - 의존성: `tripStore`, `rideStore`, `tripLabels`, `tripMedia`, `defaultHashtags: suspend () -> String?`, `candidates: suspend (List<TripMedia>) -> List<ShareCandidate>`, `job: VideoJobControl`
  - 기본값이 있는 것: `log`, `compute=Default`, `io=IO`, `zone=Asia/Seoul`
  - factory는 `container.settings.settings.first().shareHashtags`, `container.shareCandidates::of`, `container.videoJob`을 연결한다.
- **open:**
  - 여행, 구간, 이름표, 해시태그, 후보를 IO에서 읽는다. 계산은 compute에서 한다.
  - 10 m 이상 끝난 구간이 없으면 시트를 열지 않는다(null).
  - 읽기 실패: 여행·구간·이름표를 못 읽으면 log만 남기고 시트를 열지 않는다. 해시태그를 못 읽으면 기본값을 쓰고, 후보를 못 읽으면 빈 목록으로 본다.
  - 다시 `open`하면 먼저 `close()`한다.
- **기록 표시(Record):** open 때의 `shareStamp(rides, distance)`와 `lastEndedAt`를 기억한다. press 때의 저장과 Done 뒤의 pick 재계산이 이 값을 쓴다.
- **press:**
  - 시트가 null이거나 `making`이면 무시한다.
  - **COPY:** 저장한 뒤 `Copy`를 낸다.
  - **pick이 File:** 저장한 뒤 `Send`를 낸다. SAVE는 `Notice("갤러리에 저장되어 있습니다")`를 낸다.
  - **pick이 MakeImage이고 `canMakeImage`:** 저장한 뒤 만든다.
  - **만들 수 없음:** EMAIL·MORE는 저장한 뒤 `Send(target, null, text)`를 낸다. 나머지(흐린 단추)는 아무것도 하지 않고 저장도 하지 않는다.
- **만들기:**
  1. `making = true`는 `startImage` 결과를 기다리기 **전에** 둔다. 시작 검사(위성 probe)가 10초까지 걸릴 수 있어서, 그동안 "만드는 중…"과 [취소]를 보이게 하려는 것이다.
  2. `startImage(tripId, Aspect.PORTRAIT)`를 부른다.
  3. 결과가 STARTED가 아니거나 예외가 나면 `blocked = refusal(result)`, `canRetry = true`, `making = false`로 두고 대기 단추를 버린다.
  4. STARTED면 `job.state.first { Done(같은 여행 IMAGE) | Failed | Cancelled | Idle }`를 기다린다.
     - **Done:**
       1. `acknowledge()`를 부른다.
       2. 후보를 다시 읽고 세 pick과 fileLines를 갱신한다.
       3. `making = false`로 둔다.
       4. SAVE면 `Notice("갤러리에 저장했습니다")`, 아니면 `Send(target, 새 pick의 파일, 누른 때의 글)`을 낸다.
       5. 새 pick이 File이 아니면(갤러리를 못 읽은 경우) Done.media로 만든 후보로 보낸다.
     - **Failed:** `acknowledge()`, `blocked = msg`, `canRetry = true`
     - **Cancelled 또는 Idle:** 다른 곳에서 취소했거나 확인한 경우다. `making = false`, pick 갱신만 하고 보내지 않는다. Cancelled는 acknowledge하지 않는다. VideoViewModel이 init에서 하기 때문이다.
- **`refusal`(§2.5 문구):**

  | 결과 | 문구 |
  |---|---|
  | BUSY | "영상을 만드는 중이라 지금은 이미지를 만들 수 없습니다" |
  | NO_INTERNET | "인터넷이 없어 이미지를 만들 수 없습니다" |
  | RECORDING, NO_SPACE | `result.message(IMAGE_BYTES)` |
  | 예외, 그 밖의 null | `VideoJob.MSG_IMAGE_FAILED` |

  BUSY와 NO_INTERNET은 `StartResult.message`의 문구("이미 영상을 만들고 있습니다", "지도를 받으려면 인터넷이 필요합니다")가 스펙과 달라서 따로 바꿔 쓴다.
- **cancelMaking:**
  - 시작된 뒤라면 `job.cancel()`을 부르고 감시를 멈춘다.
  - 시작 검사 중이라면 표시만 해 두고, STARTED가 오는 즉시 `job.cancel()`을 부른다.
  - 어느 쪽이든 `making = false`로 두고 대기 단추를 버린다.
- **close:**
  - open을 취소하고 대기 단추를 버린다.
  - 감시 코루틴은 취소한다(시작 뒤인 경우). `job.cancel()`과 `acknowledge()`는 부르지 않는다.
  - 시작 검사 중이었다면 결과가 와도 무시한다.
  - `sheet = null`로 둔다.
- **retry:** `canRetry`일 때만 동작한다. `blocked = null`, `canRetry = false`로 두고, 마지막으로 이미지를 요청한 단추로 `press`를 다시 한다.
- **글:**
  - `edit`: `RideTitle.truncateCodePoints(text, 2000)`로 자른다.
  - `reset`: `text = autoText`
  - `toggle`: `toggleMood`. 결과가 2000 코드 포인트를 넘으면 바꾸지 않는다. 잘라 버리면 칩 상태가 어긋나기 때문이다.
  - `moods`는 글이 바뀔 때마다 `moodOn`으로 다시 계산한다.
- **저장:**
  - `text == autoText`이면 `setShareText(id, null, null)`, 아니면 `(text, record.stamp)`로 저장한다.
  - IO에서 하고, 실패하면 log만 남긴다.

## 시험 (TDD)
- **RED:** 시험 파일을 먼저 썼다. `ShareEvent`·`ShareViewModel` 등이 없다는 컴파일 오류(Unresolved reference)로 실패했다.
- **GREEN:** 구현한 뒤 `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.share.*'`가 20/20으로 통과했다.
- **변이 확인:** 구현을 일부러 망가뜨려 시험이 잡는지 확인한 뒤 되돌렸다.

  | 망가뜨린 것 | 실패한 시험 |
  |---|---|
  | 저장할 때 늘 글을 저장 | `pressSavesNullWhenUnchanged` |
  | `Aspect.LANDSCAPE`로 만들기 | `storyWithLandscapeOnlyMakesPortraitImage`, `saveMakesThenNotices` |
- **시험 이름:**
  - 브리프의 18개를 그대로 썼다.
  - 더한 것 두 개:
    - `noInternetBlocksWithShareWording`: §2.5 문구 확인
    - `cancelWhileStartingCancelsOnceStarted`: 시작 검사 중 [취소]
  - `busyBlocksWithMessageAndRetry`는 다음도 확인한다: 흐린 INSTAGRAM이 무시된다, MORE는 글만 보낸다, retry 뒤 두 번째 `startImage`가 불린다.
- **재사용한 fake:** `FakeTripStore`(nav), `FakeRideStore`(ride), `FakeTripLabelStore`(nav), `FakeFeedMedia`(ui.feed)
- **새로 만든 fake:** `VideoJobControl` fake는 VideoViewModelTest의 것이 private이라 같은 모양으로 새로 만들었다. `acknowledges` 수를 세고, STARTED면 `Preparing`으로 바뀐다.

## JVM 결과
`./gradlew :core:test :app:testDebugUnitTest`가 BUILD SUCCESSFUL이고 새 경고는 없다.

| 모듈 | 시험 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|
| core | 387 | 0 | 0 | 0 |
| app | 1040 | 0 | 0 | 0 |

기기와 adb는 쓰지 않았다.

## Concerns / Task 5가 알아야 할 것
1. **[중요] BikeNavHost의 결과 처리와 겹친다.**
   - `BikeNavHost`의 `LaunchedEffect(videoOutcome)`는 `Done`이면 미리보기를 열고 `outcomeShown()`(= acknowledge)를 부른다(BikeNavHost.kt 약 316행).
   - 시트가 만드는 이미지도 같은 job이므로, 그대로 두면 이어서 보내기와 함께 **미리보기 화면도 열릴 수 있다**.
   - 순서상 ShareViewModel의 collector가 보통 먼저 acknowledge하므로(그러면 outcome이 null로 돌아간다) 대개는 괜찮을 것이다. 하지만 보장은 없다.
   - Task 5(ShareHost)는 시트가 만드는 중(`sheet.value?.making == true`)이면 그 결과를 건너뛰는 게이트를 두어야 한다. 또는 미리보기를 열기 직전에 `job.state.value`가 여전히 `Done`인지 확인해야 한다.
   - 반대로 BikeNavHost가 먼저 acknowledge하면, VM은 `Idle`을 보고 `making = false`로 돌아가며 보내지 않는다. 멈춰 있지는 않는다.
   - "영상을 만들었습니다" 결과 알림도 뜬다. 이어서 보냈으면 `VideoNotifications.cancelResult(context)`를 고려할 것.
2. **Failed도 같다:** host가 먼저 `outcomeShown`하면 대화 상자가 뜬다. VM이 먼저면 시트에 blocked로 보인다. 게이트로 함께 처리할 것.
3. **`lastEndedAt`(해석):** 10 m 이상 끝난 구간(주행)의 `endedAt` 최댓값을 쓴다. 10 m 미만 짧은 구간까지 넣으면, stamp에도 영상 내용에도 영향이 없는데 파일이 낡은 것으로 처리되기 때문이다. 스펙 §2.1의 "마지막 끝난 구간"을 모든 끝난 구간으로 읽어야 한다면 `load()`의 한 줄만 바꾸면 된다.
4. **open 실패 때 사용자 알림이 없다:** 여행·구간을 못 읽거나 10 m 이상 주행이 없으면 시트가 열리지 않고 log만 남는다. 단추는 그런 여행에서 숨겨지므로 실제로는 드물다.
5. **시트를 여는 중:** open은 비동기다. 읽는 동안 `sheet`는 null이다(필드가 모두 non-null이라 빈 상태를 두지 않았다). 보통 짧다.
6. **making 중의 글:** Done 뒤 보내는 글은 **누른 때의 글**(저장된 글과 같음)이다. Task 5는 making 중 글 상자를 막거나 그대로 두어도 된다. 다만 고친 내용은 그 보내기에 들어가지 않는다.
7. **보낸 뒤 시트 닫기:** §1.2 "앱을 연 뒤 시트를 닫는다"는 VM이 하지 않는다. `Send`를 처리한 화면(Task 5)이 `close()`를 불러야 한다. COPY·SAVE는 시트를 그대로 둔다.
8. **[페이스북]·[인스타그램]·[스토리]의 글 자동 복사와 "글을 복사했습니다. 붙여 넣으세요" 알림:** 화면 몫이다(`Send` 처리 때).
9. **[복사] 알림:** "복사했습니다"(API 33 미만)도 화면 몫이다. VM은 `Copy(text)`만 낸다.

## Fix round 1

**커밋:** `44ce364 app: share — drop the action after Done, ordered saves, stale line` (29a6f52 위에 새로 쌓음. amend하지 않았고 push하지 않음)

### 1. Done 뒤 취소·닫기·다른 여행 열기 경쟁 (IMPORTANT)
- **`making` 해제 시점:**
  - 전에는 Done을 받자마자 `making = null`로 두었다.
  - 이제는 `making = m`을 `finish`가 끝날 때까지 둔다.
  - 끝내는 일은 모두 `end(m) { … }`가 한다. 이 함수는 `making === m`일 때만 `making = null`로 두고 시트를 바꾼다. 다른 시도나 다른 여행의 시트는 건드리지 않는다.
- **`Making.done` 필드:** Done을 받으면 true가 된다. 이미지가 이미 있다는 뜻이다.
- **`cancelMaking`의 세 경우:**

  | 경우 | 동작 |
  |---|---|
  | `done` | `dropped = true`, 스피너만 닫는다. `job.cancel()`을 부르지 않는다(이미지를 남긴다). 감시도 멈추지 않으므로, pick 갱신이 끝나 새 이미지가 시트의 pick에 반영된다. |
  | 시작 전 | 전처럼 STARTED가 오면 `job.cancel()`을 부른다. |
  | 시작 뒤 | 전처럼 감시를 멈추고 `job.cancel()`을 부른다. |
- **close와 open(다른 여행):** `drop()`이 `dropped = true`로 두고 감시를 취소한다. pick 갱신 중이었다면 그 갱신도 취소된다. Done 뒤에는 `job.cancel()`도 `acknowledge()`도 더 하지 않는다. acknowledge는 Done을 받는 즉시 이미 했다.
- **`finish`:** `refreshPicks` 바로 뒤, 그리고 SAVE Notice와 Send를 내기 전에 `m.dropped`를 다시 확인한다.

### 2. 저장 순서 (MINOR)
- `save()`를 `Mutex`(`saveLock.withLock`)로 차례대로 실행한다. 누른 순서대로 쓰인다.

### 3. 기록 바뀜 줄 (MINOR)
- **기억하는 값:** `Record`에 `savedText`와 `savedStamp`를 더했다. open 때 이름표에서 읽는다.
- **판정:** `stale = savedText != null && text == savedText && savedStamp != stamp`
- **다시 계산하는 때:**
  - 글이 바뀔 때마다(`edit`·`reset`·`toggle`)
  - press가 저장할 때: `record`를 새 저장 값으로 바꾸므로 stale이 false가 된다.

### 더한 시험 (13개, 모두 33개)
- **경쟁** (`candidates`가 늦어지는 창):
  - `cancelAfterDoneBeforeRefreshDoesNotSend`: Send가 없고, `cancels = 0`, ack 1, general이 새 이미지가 된다.
  - `closeAfterDoneBeforeRefreshDoesNotSend`
  - `openOtherTripAfterDoneBeforeRefreshDoesNotSend`
- **규칙:**
  - `doneOfAnotherTripOrVideoDoesNotSend`: 다른 여행의 IMAGE Done과 같은 여행의 VIDEO Done. 보내지 않고, ack 0, 계속 making이다.
  - `openSecondTripWhileMakingThenFirstDoneDoesNotSend`
  - `closeDuringStartChecksDoesNotSendOrCancel`
  - `idleElsewhereEndsMakingWithoutSend`
  - `retryAfterFailedMakesAndSends`
  - `facebookWithFileSavesText`
  - `unfinishedTripMoreTextOnlyOthersDimmed`
- **MINOR:**
  - `savesInPressOrder`: 첫 저장을 5초 늦춘다.
  - `staleHiddenAfterPressSaves`
  - `staleHiddenAfterReset`: 저장한 글로 다시 고치면 줄이 다시 보이고, 다르면 숨는다.
- **도구:**
  - 시험의 `TripLabelStore`는 `FakeTripLabelStore`에 위임하고, 첫 `setShareText`만 늦출 수 있게 했다.
  - fake job의 Done은 `state.value`를 직접 바꿔서 낸다.

### TDD 증거
- **RED:** 새 시험을 수정 전 VM(29a6f52)으로 돌렸다. 33개 중 6개가 실패했다: `cancelAfterDone…`, `closeAfterDone…`, `openOtherTripAfterDone…`, `savesInPressOrder`, `staleHiddenAfterPressSaves`, `staleHiddenAfterReset`.
- 나머지 7개는 규칙을 확인하는 시험이라 수정 전에도 통과했다. 원래 구현이 이미 그 규칙을 지켰다는 뜻이다.
- **GREEN:** 수정 후 `--tests 'com.cowork.bikerecoder.ui.share.*'`가 33/33 통과했다.

### JVM
`./gradlew :core:test :app:testDebugUnitTest`가 BUILD SUCCESSFUL이고 새 경고는 없다. 기기와 adb는 쓰지 않았다.

| 모듈 | 시험 | 실패 | 오류 |
|---|---|---|---|
| core | 387 | 0 | 0 |
| app | 1053 | 0 | 0 |

### 남은 것 (지시대로 그대로 둠)
- **시작 검사 중 취소 직후의 BUSY:** 취소 직후 다시 누르면 BUSY가 날 수 있다. [다시 시도]로 회복된다.
- **취소와 Done 사이의 미리보기 틈:** 아주 짧은 틈에 미리보기가 열릴 수 있다. Task 5의 미리보기 게이트가 처리한다.
- **Task 5 주의:** Done 뒤에 [취소]하면 이미지는 남는다. 그러면 `VideoViewModel`이나 BikeNavHost 쪽에서 결과 알림이 남을 수 있다.
