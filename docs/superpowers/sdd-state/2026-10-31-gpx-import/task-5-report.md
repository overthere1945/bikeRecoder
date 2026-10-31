# Task 5 보고 — "기록 가져오기" 화면, 설정 묶음, 입구

- 상태: DONE_WITH_CONCERNS
- 커밋: `302a13d` app: import — review screen, settings section and share/view entry (BASE 6b7e6d7)

## 만든 것

- **ImportViewModel** (`ui/importing/ImportViewModel.kt`)
  - `ImportUiState`: `Reading(done, total)` / `Review(rows, checked, notices, header, files)` / `Empty` / `Job(ImportJobState)`. `Review`에는 `selectable`, `allChecked` 계산 속성을 더했다.
  - 함수: `scan(uris)`, `toggle(key)`, `toggleAll()`, `importChecked()`, `stop()`, `close()`.
  - 체크 규칙: `New`는 처음 켜짐. `NotCycling`은 꺼짐이지만 바꿀 수 있음. 나머지는 바꿀 수 없음(`ScanRow.isSelectable()`).
  - 머리 줄 수: "찾은 주행"은 읽을 수 있는 줄 수, "새로"는 `New` 수, "파일 N개에서"는 고른 URI 수.
  - `importChecked()`: 화면은 곧바로 `Job(Importing(0, n))`을 보인다. `job.start`는 주입한 `io` 디스패처에서 `CoroutineStart.ATOMIC`으로 부른다(바로 화면을 떠나도 시작됨).
  - `close()`: 읽기 코루틴을 취소한다. 결과(Finished)를 보고 있었으면 `acknowledge()`. 진행 중인 가져오기는 그대로 둔다.
  - `scan()` 시작 때 화면이 못 본 이전 결과를 `acknowledge()`한다.
  - 팩토리: `ImportScanner`를 `container.importReader`·`importStore`로 VM 팩토리 안에서 만든다(AppContainer는 바꾸지 않음). URI는 `SavedStateHandle`의 `uris` 인자(줄바꿈으로 이은 글)에서 읽는다. 인자가 없으면 진행 중인 작업을 따라간다(설정 줄에서 들어올 때).
- **ImportScreen / ImportContent** (`ui/importing/ImportScreen.kt`)
  - 스펙 §1.2·§1.3 배치를 따랐다. 줄마다 `RouteDrawing` 44dp(RideColors 0번 색)를 그린다.
  - 고를 수 없는 줄은 흐리게(0.38) 보인다. 읽을 수 없는 줄은 첫 줄이 파일 이름이고, 이유는 오류 색으로 흐리지 않는다.
  - `BackHandler`와 ← 모두 `close()` 뒤에 `onBack`을 부른다.
  - 테스트 태그는 `ImportTags`에 모았다(설정 줄의 태그 3개 포함).
- **설정**
  - `SettingsViewModel`에 기본값이 있는 인자 3개를 더했다: `importedCount: Flow<Int>`, `importState: Flow<ImportJobState>`, `deleteAllImported`.
  - `SettingsUiState`에 `importedCount`, `importJob`, `deletingImported`, `importedDeleted`, `importing`을 더했다.
  - `deleteAllImported()`는 가져오는 중이거나 지우는 중이면 무시한다.
  - `SettingsScreen`: `OpenMultipleDocuments(arrayOf("*/*"))` 런처, 지운 뒤 Toast "가져온 기록을 지웠습니다".
  - `SettingsContent`: "공유"와 "앱 정보" 사이에 `ImportSection`을 두었다. 가져오는 중에는 설명이 "가져오는 중 12/35"가 되고, 줄을 누르면 진행 화면이 열리며, [모두 지우기]는 흐리다.
  - 확인 창: `deleteConfirmText(N)`, [지우기] [취소].
- **BikeNavHost**
  - 라우트: `Routes.IMPORT` / `IMPORT_PATTERN` / `importFiles(uris)`.
  - `LaunchRequest.Import(uris)`.
  - `openImport`: `ImportJobState.Importing`이면 Toast `IMPORT_BUSY_SHARE`만 띄운다. 아니면 `openOnMain`으로 연다.
  - 온보딩 중에 들어오면 `pendingImport`(rememberSaveable)에 두었다가 온보딩 `onDone` 뒤에 연다.
  - [요약 보기]는 MAIN 항목의 `savedStateHandle["openTab"] = FEED`로 요약 탭을 고르고 MAIN까지 돌아간다.
  - [다른 파일 고르기]는 화면 자리의 런처 결과를 `viewModel.scan`으로 넘긴다.
- **MainTabs**: `openTab: MainTab?`과 `onOpenTabHandled`를 더했다(기본값 있음, 한 번만 적용).
- **MainActivity**
  - `ACTION_SEND`는 `EXTRA_STREAM`, `ACTION_SEND_MULTIPLE`은 그 목록, `ACTION_VIEW`는 `data`를 읽는다(`IntentCompat`).
  - content 스킴만 받고 중복을 없앤다.
  - 최근 앱 목록에서 다시 연 인텐트(`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`)는 무시한다.
  - 기존 `onCreate`/`onNewIntent` 흐름을 그대로 쓴다.
- **매니페스트**: MainActivity에 SEND, SEND_MULTIPLE, VIEW 인텐트 필터 3개를 더했다(DEFAULT 카테고리, §1.1 MIME 7개).

## 파일

새 파일:
- `app/src/main/java/com/cowork/bikerecoder/ui/importing/ImportViewModel.kt`
- `app/src/main/java/com/cowork/bikerecoder/ui/importing/ImportScreen.kt`
- `app/src/test/java/com/cowork/bikerecoder/ui/importing/ImportViewModelTest.kt`
- `app/src/androidTest/java/com/cowork/bikerecoder/ui/importing/ImportScreenTest.kt`

수정:
- `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`
- `ui/BikeNavHost.kt`, `ui/main/MainTabs.kt`
- `MainActivity.kt`, `AndroidManifest.xml`
- 시험: `SettingsViewModelTest.kt`, `SettingsScreenTest.kt`, `MainTabsTest.kt`

## 시험

- **실패 확인(Step 2):** 구현 전 `compileDebugUnitTestKotlin`이 `ImportViewModel`·`ImportUiState`를 찾지 못해 실패했다.
- **JVM 전체(`./gradlew :core:test :app:testDebugUnitTest`):** core 442, app 1107, 실패 0. app은 이전 1099에 8개를 더했다.
  - `ImportViewModelTest` 7개: brief의 이름 그대로.
  - `SettingsViewModelTest` 1개: `imported count and job state reach the state, delete all tells once`.
- **S22 `R5CT43TFS9A`** (클래스 하나씩, 깨우기, `ANDROID_SERIAL`, `</dev/null`):
  - `ImportScreenTest` 9/9.
    - brief의 5개: reviewShowsRowsStatusesAndCount, buttonDisabledWhenNoneChecked, progressAndStop, doneShowsResultAndOpensSummary, emptyShowsPickAgain.
    - 더한 것 1개: readingShowsProgress.
    - 스크린샷 3개: screenshotReview, screenshotProgress, screenshotDone.
  - `SettingsScreenTest` 20/20. 새로 3개: importSectionBetweenShareAndAppInfo, importedCountAndDeleteAllConfirm, importingShowsProgressAndDisablesDelete.
  - `MainTabsTest` 12/12. 새로 1개: openTabRequestSelectsItOnce.
- S25에는 아무것도 하지 않았다. `adb devices`에는 S22만 있었다.

## 스크린샷

- 기기: `/sdcard/Download/bikeRecoderTest/v160-import-{review,progress,done}.png`
- 복사본: `.superpowers/sdd/2026-10-31-gpx-import/v160-import-review.png`, `v160-import-progress.png`, `v160-import-done.png`
- 직접 확인했다. review에는 상태별 줄, 흐림, 오류 색, "2개 가져오기"가 보인다. done에는 "35개를 가져왔습니다 · 건너뜀 3개"와 [닫기] [요약 보기]가 보인다.

## 결정

1. **URI 넘기기:** 내비게이션 쿼리 인자 하나에 `Uri.encode(URI들을 줄바꿈으로 이은 글)`을 넣었다. VM이 `SavedStateHandle`로 읽으므로 프로세스가 다시 만들어지면 다시 읽는다.
2. **찾은 주행이 0이고 읽을 수 없는 줄만 있을 때:** `Empty`가 아니라 `Review`로 보인다("찾은 주행 0개 · 새로 0개"와 이유). 이유를 보여 주기 위해서다. `Empty`는 줄이 하나도 없을 때만이다(예: 폴더만 든 ZIP).
3. **scan 예외:** DB 오류 등 취소가 아닌 예외는 `Empty`로 보인다.
4. **[요약 보기] 탭 전환:** `MainTabs`에 `openTab` 요청 인자를 더하고, MAIN 항목의 savedStateHandle로 넘긴다.
5. **설정 줄을 누를 때의 경로:** 가져오는 중이면 진행 화면(`Routes.importFiles(emptyList())` = `"import"`)이다. 아니면 파일 고르기 창이고, 고른 결과도 `openImport`의 진행 중 검사를 거친다.
6. **"가져온 기록을 지웠습니다":** Toast로 보인다(설정에 Snackbar가 없다). 지우기에 실패할 때의 문구는 스펙에 없어 조용히 끝낸다.
7. **설정 줄의 테스트 태그:** brief의 "시험 태그는 ImportTags에"에 따라 `SettingsTags`가 아니라 `ImportTags`에 두었다.

## 걱정

1. **공유 인텐트와 MainActivity 인스턴스.** MainActivity는 standard launchMode다. 다른 앱의 '공유'로 들어오면 그 앱의 태스크에 MainActivity가 새로 생길 수 있다. 앱 내비게이션은 그 안에서 정상으로 돌지만, 인스턴스가 둘이 될 수 있다. launchMode는 기존 동작이라 바꾸지 않았다. Task 6 기기 시험에서 확인하면 좋다.
2. **공유 grant의 수명.** 공유로 받은 URI는 persistable이 아닌 경우가 많다. 그때는 그 액티비티의 일시 grant에 의존한다. 가져오는 중에 그 액티비티가 끝나면 다시 읽기에 실패해 "건너뜀"이 될 수 있다(Task 4 설계 그대로, grant 로직은 더하지 않음).
3. **결과를 놓칠 수 있음.** 가져오는 중에 화면을 떠났다가 끝난 뒤 설정 줄에서 다시 들어오면, 줄은 "가져오는 중"이 아니므로 고르기 창이 열린다. 결과(Finished)는 다음 `scan` 때 조용히 지워진다. 스펙상 설정 줄은 진행 중일 때만 진행 화면으로 가므로 스펙과는 맞지만, 결과를 못 볼 수 있다.
4. **SEND의 clipData만 있는 경우.** `EXTRA_STREAM` 없이 clipData만 주는 보내는 앱은 받지 않는다(무시됨).
5. **인텐트 → 확인 목록의 기기 시험.** 이 부분은 Task 6 몫이라 이번에는 단위·기기 시험이 없다.

## Fix round 1

- 커밋: `58bbf23` app: import — fix round 1: handoff, empty details, kept descriptors. 302a13d 위에 더했고, 302a13d는 그대로다.

### 고친 것

1. **찾은 주행이 없을 때(§1.2)**
   - 읽을 수 있는 줄이 하나도 없으면(`rows.none { it.startedAt != null }`) `Empty`를 보인다.
   - `ImportUiState.Empty(notices, unreadable)`는 이제 data class다.
   - 화면: "가져올 주행을 찾지 못했습니다"와 [다른 파일 고르기] 아래에 제한 알림과 "파일 이름: 이유" 줄을 작은 글씨로 보인다(스크롤됨).
   - 새 도우미: `ImportFormat.unreadableText(fileName, problem)`.
   - 줄은 소스마다가 아니라 읽을 수 없는 항목마다 하나다. ZIP 안의 항목마다 이유가 다를 수 있어서다.
2. **URI 목록을 라우트에서 뺐다**
   - 새 `ImportHandoff`(`importing/ImportIntent.kt`, `AppContainer.importHandoff`, `AtomicReference`, 메모리에만 있다).
   - 라우트는 인자 없는 `Routes.IMPORT = "import"`다. `ARG_URIS`, `urisArg`, `parseUris`, `importFiles`는 지웠다.
   - VM은 만들어질 때 `take()`를 한 번 부르고, 목록이 있으면 읽는다.
   - 받은 것이 없고 작업이 Idle이면 `ImportUiState.Closed`이고, 화면이 `onBack()`을 부른다. 결과를 본 뒤 Idle이 된 `Job`도 `Closed`로 바뀐다(빈 화면이 없어짐).
   - BikeNavHost의 import `onBack`과 `onOpenSummary`는 그 항목이 맨 위일 때만 움직인다. 두 번 뒤로 가지 않게 하기 위해서다.
   - 온보딩 중에 들어온 파일: 목록은 handoff에 두고, `pendingImport`(rememberSaveable)는 Boolean만 저장한다.
   - 가져오는 중에 들어오면 handoff를 비우고 Toast를 띄운다.
3. **임시 grant가 끝나도 읽을 수 있게(Task 4 코드)**
   - `UriGrants`에 `keep`(주입), `keepLimit = 256`(`MAX_KEPT_DESCRIPTORS`), `onKeepFailed`, `kept(uri)`, `forget(uri)`를 더했다.
   - `hold()`: persist하지 못한 URI(take 실패, 또는 128 한도 넘음)는 열린 디스크립터로 들고 있는다. 실패는 기록하고 넘어간다.
   - `release()`: 들고 있던 것을 닫는다.
   - `ContentImportReader.open()`: 들고 있는 디스크립터가 있으면 `AutoCloseInputStream(pfd.dup())`를 처음 위치로 돌려서 준다. pipe라서 위치를 못 바꾸면 그대로 한 번만 쓰고 `forget`한다. dup 자체가 실패하면 `openInputStream`으로 연다.
   - `ContentImportReader`가 `WarnLog`를 받는다(기본 `androidWarnLog("ImportReader")`).
4. **작은 것들**
   - (a)(b) 순수 함수 `importUris(action, stream, streams, data, clip, fromHistory)`를 만들었다. 스트림이 없는 SEND·SEND_MULTIPLE은 ClipData URI로 대신하고, content만 받고, 중복을 없앤다. MainActivity는 각 extra를 해당 action일 때만 읽는다(타입이 안 맞을 때의 경고 로그를 피하려고).
   - (c) 읽을 수 없는 줄의 이유도 줄과 같은 흐림 값으로 흐리게 했다(오류 색은 유지).
   - (d) `SettingsViewModel` 생성자 인자를 `deleteImportedTrips`로, 작업 변수를 `deleteImportedRun`으로 바꿨다.
   - (e) VM 시험을 더했다.

### 시험

- **JVM:** core 442/0, app 1120/0. 바뀐 것을 확인하려고 `--rerun`으로 한 번 더 돌려 1120/0이었다.
- **ImportViewModelTest 12개** (7개에 5개를 더함): `emptyWhenNoRideOnlyUnreadable`, `nothingHandedOverWhileIdleCloses`, `nothingHandedOverWhileImportingShowsTheJob`, `closeLeavesARunningImportAlone`, `scanAcknowledgesAStaleResult`.
  - 시험이 만든 VM의 `viewModelScope`를 `tearDown`에서 Main을 되돌리기 전에 취소한다.
  - 한 번은 실행 중인 작업이 시험이 끝난 뒤 Main에 닿아 다음 클래스(MainViewModelTest)가 실패했다. 고친 뒤에는 다시 나오지 않았다.
- **ImportReaderTest 6개** (2개를 더함): `holdKeepsDescriptorsOfWhatCannotBePersisted`(들고 있기, 한도, 실패 기록, 다시 hold, 한 번만 닫기), `forgetClosesOneDescriptor`.
- **ImportIntentTest 6개** (새 파일): SEND, SEND_MULTIPLE, VIEW, content만, ClipData 대신 쓰기, from-history와 다른 action.
- **S22**, 클래스 하나씩: ImportScreenTest 9/9(emptyShowsPickAgain에 줄 검사를 더함), SettingsScreenTest 20/20, MainTabsTest 12/12.

### 스크린샷

- 다시 찍었다(review의 이유 줄이 흐려짐).
- 앱을 다시 설치하면서 이전 파일의 주인이 바뀌어, 시험의 "지우고 다시 쓰기"에서 지우기가 실패했다. 그래서 기기에는 `v160-import-*(1).png`로 새로 저장되었다. 이전 파일(20:27)은 휴대폰에 남아 있다(지우지 않음).
- `.superpowers/sdd/2026-10-31-gpx-import/v160-import-{review,progress,done}.png`는 새 파일(20:47)로 바꿨다.

### 남은 한계

- 한 번에 공유한 파일 중 persist할 수 없는 것이 256개를 넘으면, 넘은 파일은 받은 액티비티의 임시 grant에만 기댄다. 그 액티비티가 끝나면 그 파일들은 다시 읽지 못해 건너뜀이 된다. 128개를 넘는 SAF 파일 가운데 디스크립터를 받지 못한 것도 같다.
- pipe 디스크립터(드묾)는 한 번만 읽을 수 있다. 처음 읽기가 끝난 뒤에는 원래 grant로 연다.
- 프로세스가 다시 시작되면 handoff가 사라진다. 그러면 화면이 스스로 닫히고 사용자가 다시 골라야 한다(목록을 저장하지 않는 쪽을 골랐다).
- 보류된 것은 손대지 않았다: 개수 쿼리, `popUpTo(MAIN)`.
