# Task 5 보고 — 시트 화면, 공유 단추 세 곳, BikeNavHost 연결

**상태:** DONE_WITH_CONCERNS (가벼운 것만)
**커밋:** `app: share — sheet, share buttons on card, trip and summary` (44ce364 위, push 안 함)

## 바꾼 파일
- **새 파일**
  - `ui/share/ShareSheet.kt`: `ShareSheet`, `ShareTags`
  - `ui/share/ShareHost.kt`: `ShareHost`, `ShareActions`(이벤트 처리를 화면에서 떼어 낸 클래스), `rememberPackageShareApps()`, 알림 문구 상수
  - 시험: `androidTest/.../ui/share/ShareSheetTest.kt`(13개), `androidTest/.../ui/share/ShareActionsTest.kt`(6개), `test/.../ui/ShareGateTest.kt`(2개)
- **고친 파일**
  - `ui/share/ShareState.kt`: `ShareEvent.Send.made`(기본 false), `canShare(segments)`, `shareTripOf(segmentId, rideStore)`
  - `ui/share/ShareViewModel.kt`: `finish`가 내는 Send에 `made = true`
  - `ui/feed/FeedViewModel.kt`(`FeedCardState.canShare` = `StatsCalculator.rides`로 거른 주행이 있음), `FeedCard.kt`(숫자 줄 끝 아이콘), `FeedScreen.kt`(`onShare`), `WeekStrip.kt`(`FeedTags.share`)
  - `ui/history/TripDetailViewModel.kt`(`canShare`), `TripDetailScreen.kt`(`TripTitleRow`의 ✎ 앞 아이콘, `onShare`)
  - `ui/record/SummaryExtras.kt`(`shareTripId`, `shareTrip` 생성자 인자, `SummaryExtrasContent.onShare`, [공유] 단추)
  - `ui/record/RecordViewModel.kt`, `ui/navigate/NavigateViewModel.kt`(`shareTrip` 연결), `RecordScreen.kt`, `NavigateScreen.kt`(`onShare` 전달)
  - `ui/BikeNavHost.kt`: activity 범위 `ShareViewModel`, `ShareHost` 한 번, 세 곳 `onShare`, 미리보기 게이트(`hostOutcome`)
  - 시험: `FeedScreenTest`(+2), `TripDetailScreenTest`(+1), `RecordScreenTest`(+1), `FeedViewModelTest`(+1), `TripDetailMediaTest`(+1), `SummaryExtrasTest`(+1), `ShareViewModelTest`(기대값 2곳에 `made = true`)

## 설계 메모
- **시트 배치(§1.2):** 제목 → 감정 칩 `LazyRow`(`FilterChip`, 설명 "감정: 상쾌") → `OutlinedTextField`(minLines 4, maxLines 8)와 오른쪽 아래 [처음 글로] → stale 줄 → "보낼 것" 또는 blocked(오류 색) + `canRetry`면 [다시 시도] → making이면 "요약 이미지를 만드는 중…" + `LinearProgressIndicator` + [취소], 아니면 단추 두 줄.
  - 시트 전체를 `verticalScroll`로 감쌌다. `ModalBottomSheet`(M3 1.4.0)의 기본 insets가 `safeDrawing`이라 IME 여백은 따로 두지 않았다.
  - [처음 글로]는 글이 자동 글과 같으면 흐리게 둔다(누를 일이 없다).
- **단추:** 4칸 격자 두 줄이다. 숨긴 단추는 왼쪽부터 채우고 남는 칸은 비운다. 동그라미 56dp 안에 페이스북·인스타그램·스토리는 `apps.icon()`(비트맵으로 바꿔 시트마다 한 번 `remember`), 나머지는 Material 아이콘(Email, ContentCopy, Download, MoreHoriz)이다. 앱 아이콘을 못 읽으면 종이비행기로 대신한다. 흐림은 `alpha 0.38` + `clickable(enabled = false)`.
  - `apps.available()`도 시트가 열릴 때마다 한 번 묻는다(`remember(apps)`; 시트가 닫히면 composition에서 빠지므로 다음 열기에 다시 묻는다).
- **글 상자와 VM:** 상태는 VM이 들고 있다(`value = state.text`, `onValueChange = viewModel::edit`). `ShareHost`는 `sheet`를 `Dispatchers.Main.immediate`로 모아서, 입력한 글이 같은 프레임 안에 돌아오게 했다(늦게 오면 다음 글자가 지워지는 Compose 문제를 피함). 회전 뒤에도 글이 남는다(activity 범위 VM).
- **ShareActions:** `ShareHost`의 이벤트 처리를 `ShareActions(apps, launch, clip, tell, resultSeen, close, sdk)`로 떼어 내 기기에서 가짜 시작기·가짜 클립보드로 시험했다. 실제 SNS는 열지 않았다.
- **알림 방식:** 앱이 이미 쓰는 짧은 `Toast`(BikeNavHost의 FavoriteEvent, `startOrTell`)와 맞췄다. 시트는 따로 창이라 화면의 snackbar는 시트 아래로 가려지기 때문이다. `ShareHost(tell = …)`로 바꿀 수 있다.
- **공유 아이콘:** `Icons.AutoMirrored.Filled.Send`, 설명 "공유", `IconButton` 48dp(카드·여행 화면). 구간 요약은 `OutlinedButton` [공유](아이콘 + 글, 높이 48dp 이상, 폭 전체)를 `SummaryExtrasContent` 끝(영상 단추 아래)에 둔다.
- **`shareTripId`:** `shareTripOf`는 구간의 여행에 10 m 이상 끝난 구간이 하나라도 있으면 그 여행 id를 준다. 진행 중 여행이어도 된다. `mediaTrip`과 같이 구간마다 한 번 읽는다.

## 판정(ruling)별 구현
1. **흐림:** 단추의 enabled는 `state.enabled(target)` 그대로다. 변이 확인: `enabled = true`로 바꾸자 `blockedDimsFileButtonsAndShowsReason`, `unfinishedTripDimsOnlyImageButtons`가 실패했다(되돌림).
2. **미리보기 게이트:** `val shareMaking = shareViewModel.sheet…making == true`, `LaunchedEffect(videoOutcome, shareMaking)`, 그 안에서 `hostOutcome(videoOutcome, shareMaking) ?: return`. making 중에는 미리보기도 `outcomeShown()`도 하지 않는다. making이 끝났는데 outcome이 남아 있으면(시트를 닫았거나 Done 전에 버린 경우) 키가 바뀌어 기존 흐름이 처리한다.
   - **시험:** `hostOutcome`만 JVM으로 시험했다(`ShareGateTest`). BikeNavHost 전체는 기존 시험 틀이 없고(실제 AppContainer·job 필요) 싸게 만들 수 없어서, 효과의 키 두 개와 재실행은 코드 검토로만 확인했다. 실제 흐름(시트에서 만들고 미리보기가 안 뜸)은 사용자 S25 점검표 몫이다.
3. **결과 알림:** `ShareEvent.Send(made: Boolean = false)`. VM의 `finish` 경로만 `true`. ShareActions가 `made`면 인텐트 전에 `VideoNotifications.cancelResult(context)`를 부른다. `ShareViewModelTest`의 두 기대값(`storyWithLandscapeOnlyMakesPortraitImage`, `retryAfterFailedMakesAndSends`)에 `made = true`를 넣었다.
4. **보낸 뒤:** 인텐트를 띄운 뒤 `viewModel.close()`. `ActivityNotFoundException`이면 "열 수 있는 앱이 없습니다"를 띄우고 시트는 둔다(다른 단추를 고를 수 있게). COPY·SAVE(Notice)는 시트를 둔다.
5. **클립보드:** FACEBOOK·INSTAGRAM·STORY는 먼저 `ClipData.newPlainText("활동", text)`를 넣고 "글을 복사했습니다. 붙여 넣으세요"(버전 무관). COPY는 label "활동", "복사했습니다"는 API 33 미만에서만.
6. **making 중 글 상자:** 편집 가능하게 두었다(VM은 누른 때의 글을 보낸다).
7. **시트:** `ModalBottomSheet`(skipPartiallyExpanded), `ShareHost`는 `VideoHost` 다음에 한 번 그린다. VM은 activity 범위.

## 시험 결과
**JVM:** `./gradlew :core:test :app:testDebugUnitTest` 통과.

| 모듈 | 시험 | 실패 | 오류 |
|---|---|---|---|
| core | 387 | 0 | 0 |
| app | 1058 (+5) | 0 | 0 |

- RED 증거: 시험을 먼저 쓰고 컴파일했을 때 `hostOutcome`, `canShare`, `shareTripId`, `shareTrip`, `shareTripOf`, `made` 없음으로 실패했다.

**S22 `R5CT43TFS9A`** (Android 16, 클래스 하나씩, 깨운 뒤 `ANDROID_SERIAL`·`</dev/null`):

| 클래스 | 시험 | 실패 |
|---|---|---|
| ShareSheetTest | 13 | 0 |
| ShareActionsTest | 6 | 0 |
| FeedScreenTest | 22 | 0 |
| TripDetailScreenTest | 5 | 0 |
| RecordScreenTest | 17 | 0 |
| MainTabsTest | 11 | 0 |

건너뛴(assumption) 시험은 없었다. R3CY903ZYQV에는 아무것도 하지 않았다.

**스크린샷** (S22 `/sdcard/Download/bikeRecoderTest/`):
- `v150-share-sheet.png`
- `v150-share-making.png`
- 변이 확인 때 ShareSheetTest를 한 번 더 돌려 `v150-share-sheet (1).png`, `v150-share-making (1).png`도 생겼다(내용은 같다). 기기 파일은 지우지 않았다.
- S22에는 페이스북·인스타그램이 없어 그 세 단추는 대체 아이콘(종이비행기)으로 찍혔다. 시험은 단추를 모두 보이게 하는 가짜 apps를 쓴다.

## Concerns
1. **미리보기 게이트의 통합 시험 없음:** 위 2번. 순수 함수만 시험했다.
2. **SAVE 뒤의 결과 알림:** [저장]이 이미지를 만든 경우 VM은 `Notice("갤러리에 저장했습니다")`만 내므로 "영상을 만들었습니다" 알림이 남는다(판정은 Send만 다룸). 필요하면 `Notice`에도 같은 표시를 더하면 된다.
3. **Navigate 요약의 [공유] 위치:** `SummaryExtrasContent` 안에 있어서 안내 요약에서는 "여행을 완료했습니다"와 [영상 만들기]보다 **위**에 온다(기록 요약에서는 영상 단추 아래). 브리프대로 `SummaryExtrasContent`에 두었다.
4. **여행 화면 아이콘 위치:** 스펙 §1.1은 "위쪽 막대 오른쪽"이라 하지만 브리프대로 제목 줄 ✎ 앞에 두었다.
5. **blocked일 때 "보낼 것" 숨김:** 진행 중 여행에서 스토리만 이미지가 필요해도 `MSG_UNFINISHED`가 "보낼 것" 줄들을 대신한다(브리프의 "fileLines 또는 blocked"). 페이스북 등에 보낼 파일 줄은 그동안 보이지 않는다.

## Fix round 1

**커밋:** `4cae699 app: share — events only while started, gate test, summary order` (4ee7113 위, push 안 함)

### 1. 이벤트는 STARTED일 때만 (IMPORTANT)
- `ShareHost.kt`에 `internal suspend fun handleWhileStarted(lifecycle, events, handle)`를 더했다. `lifecycle.repeatOnLifecycle(STARTED) { events.collect(handle) }`이다.
- `ShareHost`는 `LocalLifecycleOwner.current.lifecycle`을 `LaunchedEffect(viewModel, apps, lifecycle)`의 키로 두고 이 함수로 모은다.
- 앱이 뒤에 있는 동안 온 `Send(made = true)`는 VM의 BUFFERED 채널에서 기다린다. 앱이 돌아오면 그때 `cancelResult` → 인텐트 → `close()`가 차례로 일어난다.
- **시험:** `test/.../ui/share/ShareEventsLifecycleTest.aSendWhileStoppedIsHandledOnceStarted` (JVM, `LifecycleRegistry.createUnsafe`, VM과 같은 모양의 `Channel(BUFFERED).receiveAsFlow()`)
  - CREATED 동안 보낸 Send는 처리되지 않는다.
  - STARTED가 되면 한 번 처리된다.
  - 그 뒤의 Copy는 바로 처리된다.
  - **변이 확인:** 본문을 `events.collect(handle)`로 바꾸자 이 시험이 실패했다(되돌림).

### 2. BikeNavHost가 `making`만 읽음 (Minor)
- `remember(shareViewModel) { sheet.map { it?.making == true }.distinctUntilChanged() }.collectAsStateWithLifecycle(초기값)`으로 바꿨다. 글자를 칠 때마다 최상위 host가 다시 그려지지 않는다.
- 초기값은 `false`가 아니라 `sheet.value?.making == true`다. 만드는 중에 activity가 다시 만들어지면 첫 프레임에 게이트가 열리는 틈을 막기 위해서다.

### 3. VM 수준 게이트 시험 (Minor)
- `ShareViewModelTest.doneWhileMakingIsTakenByTheSheetAndSentOnce`
  - Done 직후, 시트의 감시가 돌기 전에 host가 보는 outcome(`outcomeOf(job.state.value)`, VideoViewModel.outcome이 쓰는 함수)이 있다. 하지만 `hostOutcome(…, making = true)`는 null이다.
  - 그 뒤에는 outcome이 null(소비됨)이고, acknowledge는 1번이며, `Send(FACEBOOK, image, AUTO, made = true)`가 정확히 하나 나온다.
- 선택 항목: 기존 `cancelAfterDoneBeforeRefreshDoesNotSend`에 `assertNull(outcomeOf(job.state.value))`를 더했다. Done 뒤 refreshPicks 중에 [취소]해도 outcome은 이미 소비되어 미리보기가 뜨지 않고, Send도 없다.
- VideoViewModel 자체는 연결하지 않았다. outcome의 원천인 `outcomeOf`와 fake job의 acknowledge 수로 확인했다.

### 4. 안내 요약의 [공유] 순서 (Minor)
- `SummaryShareButton(state, onShare)`를 공개 composable로 뺐다.
- `SummaryExtrasContent.onShare`는 이제 nullable(기본 null)이다.
  - 기록 요약은 `onShare`를 넘겨 영상 단추 바로 아래에 그린다.
  - 안내 요약은 `onShare`를 넘기지 않고, "여행을 완료했습니다" → 영상 단추 → `SummaryShareButton` → [여러 날로 바꾸기] 순서로 직접 그린다.
- 두 요약 모두 영상 단추 다음에 [공유]가 온다.

### 5. 버리는 중에는 [공유] 끔 (Minor)
- `SummaryShareButton`의 `enabled = !state.discarding`

### 시험
**JVM:** `:core:test :app:testDebugUnitTest` 통과.

| 모듈 | 시험 | 실패 | 오류 |
|---|---|---|---|
| core | 387 | 0 | 0 |
| app | 1060 (+2) | 0 | 0 |

**S22 `R5CT43TFS9A`** (클래스 하나씩):

| 클래스 | 시험 | 실패 |
|---|---|---|
| ShareActionsTest | 6 | 0 |
| RecordScreenTest | 17 | 0 |

- `RecordScreenTest.summaryShareButton`을 넓혔다. 이제 [공유]가 영상 단추 아래에 있는지와 `discarding`이면 흐린지도 본다.
- 이번 회차에서 바뀐 androidTest 클래스는 RecordScreenTest뿐이다. ShareSheet·Feed·TripDetail은 바뀌지 않아 다시 돌리지 않았다.

### 남은 것
- **안내 요약 순서:** NavigateScreen 요약 화면 시험 틀이 없어 기기 시험이 없다. 코드로만 확인했다.
- **게이트 통합 시험:** BikeNavHost 전체를 띄우는 시험은 여전히 없다. 위 VM 수준 시험으로 대신했다.
