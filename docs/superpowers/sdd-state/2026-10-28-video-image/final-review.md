# V1.3.0 최종 브랜치 리뷰 (d4ee71e..88dc8b4, 14커밋)

검토 범위: 리뷰 패키지 diff 전체(코드는 전부, 테스트는 훑어 읽음), 스펙·계획·ledger·global-constraints. 작업별 리뷰가 이미 정리한 것은 다시 다루지 않았다.
증거: `./gradlew :core:test :app:testDebugUnitTest` 한 번 실행. core 38클래스 308개, app 70클래스 782개, 실패 0. `docs/verification/video-field-test.md`의 숫자와 같다. 작업 트리는 건드리지 않았다(clean).

## 판정: **FIX-NOW를 고친 뒤 머지**

치명적인(Critical) 문제는 없다. 데이터 안전성은 확인했다.
- Room 4→5는 표 추가만 한다. 4.json과 5.json을 비교하면 `trip_media`만 다르다.
- 기록·구간·사진·여행 지도를 지우는 경로는 없다.
- VIDEO 지도 정리는 CORRIDOR/OVERVIEW를 건드리지 않는다. 처음부터 ref.kind는 늘 enum 이름이라 "kind를 모르는 영역" 경로도 탈 수 없다.

다만 오프라인 지도 mutex를 몇 분씩 잡는 문제(Important)와 몇 가지 작은 정확성 문제를 머지 전에 고쳐야 한다.

### 잘된 점
- 작업 사이 이음매가 맞는다. 인코더의 최종 크기(720p 대체 포함)로 renderer, overview, outro 카메라를 만들고, `addAudio`를 첫 프레임 전에 부르고, `close()`는 job의 finally에서만 부른다. `openVideo`와 `createPending`은 NonCancellable로 감싸 취소 경계에서 uri와 인코더를 잃지 않는다.
- 출판 전에 pending에서 uri를 빼 둔다. 그래서 강제 종료 뒤에도 완성 파일을 지우는 경로가 없다.
- `decideDownload`와 `removeTripRegions`의 kind 필터가 정확하다. `deleteForTrip`/`deleteAll`의 여행 지도 동작은 예전과 같다(ALL_KINDS).
- 기록이 시작되면 job이 멈춘다(watcher). 기록 중에 실패해도 대화상자는 띄우지 않는다. 진행 카드는 만드는 여행에만 보인다.
- 매니페스트: `mediaProcessing|dataSync`, 권한 2개(normal), 새 런타임 권한 없음. `startForeground`가 API 35 이상에서 MEDIA_PROCESSING, 그 아래에서 DATA_SYNC를 쓴다. ServiceCompat 마스킹을 피한 점도 맞다.
- 한국어 문구는 global-constraints 목록과 모두 일치한다. 파생 문구("이미지 만드는 중", "이미지를 만들었습니다", "영상을 열 수 없습니다", "열 수 있는 앱이 없습니다", "고른 음악")도 한국어다.
- 테스트가 동작을 검증한다. 특히 VideoJobTest의 취소 경계, 출판 순서, 720p 카메라 테스트가 그렇다.

---

## 발견 사항

### Critical
없음.

### Important

**I1. VIDEO 지도를 받는 동안 오프라인 지도 mutex를 몇 분씩 잡는다.** (ledger에서 FINAL WAVE로 넘긴 항목 → FIX-NOW)
- 위치: `app/src/main/java/com/cowork/bikerecoder/offline/MapLibreOfflineController.kt:113-138`. `downloadForTrack`이 `mutex.withLock` 안에서 `downloadRegion(..., stallMs = VIDEO_STALL_MS)`(129행)를 다운로드가 끝나거나 30초 멈출 때까지 기다린다.
- 영향: 그동안 같은 mutex를 쓰는 다음 작업이 모두 대기한다. 시간은 경로 길이에 비례한다(줌 13~16, maxTiles 무제한; 당일 1분 안팎, 여러 날이면 몇 분).
  - 기록 목록의 여행 삭제와 [정리]: `TripDeleter` → `deleteForTrip`. `HistoryViewModel`이 자기 mutex 안에서 기다려서(`HistoryViewModel.kt:154,162`) 지운 여행이 목록에 계속 보이고, 뒤 삭제도 줄을 선다.
  - 기록 없이 안내만 할 때(설정 `autoStartRecordingWithNavigation` 끔)의 도착이나 완료: `NavigationController.finish` → `TripManager.complete` → `deleteForTrip`. rideScope(Main) 위에서 `NavUiState.Finished`가 늦게 나온다.
  - `MainViewModel`의 방치 여행 완료, `TripStartViewModel.changePlan`의 `changeProfile`/`changeStops`(안내 시작이 늦어짐), 설정의 [지도 모두 지우기](`deleteAll`), 새 여행의 corridor 다운로드.
- 고칠 방법(둘 중 하나. 앞의 것을 권장):
  - (a) VIDEO 다운로드를 다른 mutex 사용자가 중단할 수 있게 한다. 컨트롤러에 `@Volatile private var videoAbort: CompletableDeferred<Unit>?`를 둔다. `downloadForTrack`이 기다리기 전에 이것을 만든다. `awaitComplete`의 stall 루프는 `videoAbort`가 완료되어도 stall처럼 `break`한다("받은 만큼 쓰고 계속"). `deleteForTrip`, `deleteAll`, `download()`의 `mutex.withLock` 직전에는 `videoAbort?.complete(Unit)`를 부른다. 영상 job은 받은 타일과 네트워크로 계속 그리고, finally의 `deleteVideoRegions`가 정리한다.
  - (b) mutex는 "확인·VIDEO 정리·영역 생성+ref 기록"에만 잡고, `awaitComplete`는 mutex 밖에서 한다. `downloadRegion`을 생성과 대기로 나눠야 한다. 이 경우 대기 중에 다른 쪽이 그 영역을 지울 수 있으니, 지워진 `OfflineRegion`에 `setDownloadState(INACTIVE)`를 불러도 안전한지 확인해야 한다.
- 테스트: `MapLibreOfflineControllerTest`(S22)에 다음을 넣는다. 영상 다운로드가 진행 중일 때 `deleteForTrip(다른 여행)`과 `deleteAll()`이 몇 초 안에 돌아오고, `downloadForTrack`은 true를 돌려준다.

**I2. 출판 뒤의 실패가 이미 저장된 영상을 "영상을 만들지 못했습니다"로 바꾼다.** (ledger Task 6 "Throwable catch lacks `published` guard" → FIX-NOW)
- 위치: `app/src/main/java/com/cowork/bikerecoder/video/VideoJob.kt:281-303`. `published = true`(285행) 다음의 `tripMedia.add`나 `store.recordSpeed`에서 예외가 나면 `catch (e: Throwable)`(294행)가 `Failed(...)`를 넣는다.
- 실제로 일어나는 경로: 영상을 만드는 동안 사용자가 기록 목록에서 그 여행을 삭제한다(완료 여행이라 삭제할 수 있다). 그러면 출판 뒤 `trip_media` 삽입이 FK 제약으로 실패한다. 갤러리에는 영상이 있는데 화면은 실패라고 말하고 미리보기도 열리지 않는다.
- 고칠 방법: 출판 뒤 단계(`setPending(null)`, `tripMedia.add`, `recordSpeed`)는 각각 `attempt`로 감싸 로그만 남긴다. `add`가 실패하면 id를 0으로 두고 `Done(media, downscaled)`으로 끝낸다. 또는 `catch (Throwable)`에서 `if (published)`이면 `result`를 바꾸지 않는다. 그러려면 `result`를 `published` 직후 `Done`으로 먼저 정해 둬야 한다.
- 테스트: VideoJobTest에서 `FakeTripMedia.add`가 예외를 던져도 `Done`이고, `published`에는 uri가 있고, `deleted`는 비어 있다.

### Minor (FIX-NOW에 넣은 것)

**M1. cleanup 중 Error가 나면 `claimed`/`runningTripId`/state가 영원히 남는다.** (ledger Task 7 → FIX-NOW, 3줄)
- 위치: `VideoJob.kt:298-309`. finally 안의 `withContext(NonCancellable) { attempt(...) }`는 `Exception`만 잡는다. 여기서 `Error`(OOM 등)가 나면 그 아래 `_state.value = result`, `_runningTripId.value = null`, `claimed.set(false)`가 실행되지 않는다. 그러면 진행 카드가 영구히 남고, [취소]는 아무 일도 하지 않으며, "이미 영상을 만들고 있습니다"가 앱을 다시 시작할 때까지 나온다.
- 고칠 방법: cleanup 블록을 `try { withContext(NonCancellable) {...} } finally { _state.value = result; _runningTripId.value = null; if (running === ...) running = null; claimed.set(false) }`로 감싼다.

**M2. 인코딩 마지막 순간에 기록이 시작되면 미리보기가 기록 화면 위로 열린다.**
- 위치: `app/src/main/java/com/cowork/bikerecoder/ui/BikeNavHost.kt:292-300`. `sink.finish()`(`VideoJob.kt:351`, 최대 30초 블로킹, 취소를 보지 않음) 동안 기록이 시작되면, 출판은 NonCancellable로 끝나 `Done`이 된다. 그러면 `LaunchedEffect(videoOutcome)`가 녹화 중인데도 `openPreview`를 한다.
- 고칠 방법: `if (outcome is Preview && !onboarding && !recordingNow)`일 때만 연다. `recordingNow`는 `container.rideState`가 Starting 또는 Active인지다. 열지 않을 때는 `cancelResult`도 부르지 않는다(알림으로 나중에 연다). `outcomeShown()`은 그대로 부른다.

**M3. 문서 오류: `docs/verification/video-field-test.md:101`.**
- "그 밖의 실패는 '영상을 만들지 못했습니다. 다시 만들까요?' 대화상자"라고 적혀 있지만 코드는 다르다. 일반 실패는 `VideoDialog.Message("영상을 만들지 못했습니다")`(또는 저장 공간 문구)에 [확인]이다. "다시 만들까요?"는 앱이 강제 종료된 작업을 다음 실행 때 제안할 때만 나온다.
- 고칠 방법: 문장을 "그 밖의 실패는 그 이유('영상을 만들지 못했습니다', '저장 공간이 부족합니다(약 N MB 필요)' 등)를 [확인] 대화상자로. '다시 만들까요?'는 앱이 강제 종료된 작업에만"으로 바꾼다.

### Minor (LEAVE. 기록만)

- **m1. 음악을 인코딩하는 동안 앱을 나가면 FGS를 시작하지 못할 수 있다.** `VideoJob.kt:147`의 음악 인코딩(수 초)이 `startForeground()`(153행)보다 먼저다. 그 사이 홈으로 나가면 `startForegroundService`가 백그라운드 시작 제한으로 거절된다. 예외는 잡지만 job은 FGS 없이 돈다(Android 14+ 프리저가 멈출 수 있음). 가능성이 낮고 앱으로 돌아오면 이어진다. 원하면 서비스를 음악 인코딩 전에 시작한다(실패한 시작은 서비스가 Idle을 보고 바로 멈춘다).
- **m2. 시작 확인 경합 때 VIDEO 영역이 남을 수 있다.** `mapReachable`(`VideoJob.kt:231`)은 오프라인일 때만 `downloadForTrack`을 부른다. 그 사이 네트워크가 돌아오면 시작 확인 안에서 VIDEO 지도를 실제로 받는다. 그 뒤 음악을 못 읽거나 화면을 떠나 시작이 거절되면 VIDEO 영역이 남는다. 창이 ms 단위이고, 다음 영상·여행 삭제·강제 종료 정리 때 지워진다.
- **m3. 경로 전체가 보이는 프레임이 Main에서 JNI를 많이 부른다.** 요약 이미지, 시작 0~3초, 끝 장면이 보이는 경로 점 전부를 `MapSnapshot.pixelForLatLng`로 Main에서 하나씩 투영한다(`RouteOverlay.kt:51`, `MapSnapshots.kt:56`). 5만 점이 넘는 여러 날 여행이면 한 번에 수백 ms씩 Main이 막힐 수 있다(UI 버벅임, 기록과는 무관). 나중에 줌에 맞춰 점을 솎은 뒤 투영하면 된다.
- **m4. 스펙 본문의 산수가 맞지 않는다(코드 문제 아님).** §2.5의 "R이 T의 50%보다 작아지지 않도록"은 짧게(4장)에서 R = 13초(43%)라 틀리다(`docs/superpowers/specs/2026-10-28-video-image-design.md:68`). 코드는 스펙의 상한 4/8/15를 따른다. 스펙 문장을 고칠지만 정하면 된다.
- **m5. 결과 알림과 앱 안 대화상자가 함께 나온다.** 실패를 앱 안 대화상자로 보여도 서비스가 실패 알림을 함께 올린다. `Done`은 `cancelResult`로 거두는데, 그 순서도 경합이다. 영향은 작다.
- **m6. 같은 오프라인 mutex에 대기하는 시작 확인.** I1과 같은 성격으로, 오프라인 시작 확인이 corridor 다운로드 뒤에서 기다릴 수 있다. I1을 (a)로 고쳐도 남지만 오프라인일 때만이다.

---

## FIX-NOW 목록 (fixer 한 명이 차례로)

1. **오프라인 mutex를 놓아주는 VIDEO 다운로드** (`app/src/main/java/com/cowork/bikerecoder/offline/MapLibreOfflineController.kt`)
   - `@Volatile private var videoAbort: CompletableDeferred<Unit>? = null`을 추가한다.
   - `downloadForTrack`이 VIDEO 영역을 기다리기 직전에 `videoAbort = CompletableDeferred()`를 넣고, 끝나면(finally) null로 되돌린다.
   - `awaitComplete`에 `abort: Deferred<Unit>?` 인자를 더한다. `stallMs` 루프의 각 회차에서 `abort?.isCompleted == true`이면 로그 "video map download yielded"를 남기고 `break`한다.
   - `deleteForTrip`, `deleteAll`, `download()`(두 군데의 `mutex.withLock` 직전)에서 `videoAbort?.complete(Unit)`를 부른다.
   - `downloadForTrack`의 반환값은 그대로 둔다(중단되어도 true. 스냅샷이 네트워크로 받는다).
   - S22 `MapLibreOfflineControllerTest`에 다음 테스트를 더해 그 클래스만 실행한다: 영상 다운로드 중 `deleteForTrip(다른 여행)`이 5초 안에 끝나고, `downloadForTrack`이 true를 돌려주고, 다른 여행의 CORRIDOR/OVERVIEW는 그대로이며, 정리 뒤 VIDEO ref가 남지 않는다.
2. **출판 뒤 실패를 성공으로 처리** (`app/src/main/java/com/cowork/bikerecoder/video/VideoJob.kt:281-303`)
   - `published = true` 다음 단계를 각각 `attempt`로 감싼다: `store.setPending(null)`, `tripMedia.add`(실패하면 id 0), `store.recordSpeed`.
   - `result = Done(...)`은 publish 직후에 정한다.
   - `catch (e: Throwable)`은 `if (!published)`일 때만 `Failed`로 바꾼다.
   - VideoJobTest에 "add가 던져도 Done이고 파일을 지우지 않는다"를 추가한다.
3. **cleanup이 상태와 claim을 반드시 풀게** (`VideoJob.kt:298-309`): finally의 정리 블록을 `try { … } finally { _state.value = result; _runningTripId.value = null; if (running === currentCoroutineContext()[Job]) running = null; claimed.set(false) }`로 바꾼다.
4. **녹화 중에는 미리보기를 자동으로 열지 않기** (`app/src/main/java/com/cowork/bikerecoder/ui/BikeNavHost.kt:292-300`): `container.rideState`가 `RideUiState.Starting`이나 `Active`이면 `openPreview`와 `cancelResult`를 건너뛴다. `outcomeShown()`은 그대로 부른다.
5. **문서 수정** (`docs/verification/video-field-test.md:101`): M3의 문장으로 바꾼다. 1~4의 수정이 동작을 바꾸는 부분도 "사용자에게 보이는 동작을 바꾼 판정"에 한 줄씩 더한다.
   - 1: 영상 지도 받는 중 여행 삭제나 안내 시작이 기다리지 않음.
   - 2: 만든 뒤 여행이 지워져도 미리보기.
   - 4: 녹화 중 끝나면 알림만.
   - 그다음 `./gradlew :core:test :app:testDebugUnitTest`를 돌리고, 1은 S22에서 그 클래스만 실행한다.

---

## 넘겨진 minor 분류

| 출처 | 항목 | 판정 | 이유 |
|---|---|---|---|
| T1 | 마지막 주행 프레임이 totalMoveMs보다 1장 짧음 | LEAVE | 끝 장면이 경로 전체를 그리므로 보이지 않는다 |
| T1 | 이동 시간 0 테스트 없음 | LEAVE | `rideFrames = 0`일 때 나눗셈 경로가 없다(코드 확인). 테스트 추가는 선택 |
| T1 | 몰린 사진 채우기 규칙 미검증 | LEAVE(해결됨) | `choosePhotosFillsCapWhenClustered`가 있다 |
| T1 | 자막 날짜가 첫 점 시각 기준(segment.localDate 아님) | LEAVE | 자정을 넘긴 시작 같은 경계에서만 다르다 |
| T1 | 끝 근처 자막이 잘림 | LEAVE | 드문 시각 표현 차이 |
| T1 | 타이밍 테스트 1초 기준이 불안정할 수 있음 | LEAVE | 이번 실행 통과. CI가 느리면 그때 완화 |
| T1 | 사진 묶음 안 사진마다 첫 장 재스냅샷 | LEAVE | 사진당 스냅샷 1장, 비용 작음 |
| T2 | 25 m 기준이 짧은 굽이에서 방향을 고정 | LEAVE | 실주행 확인 항목 6에서 본다 |
| T2 | 내려가기에 회전 상한 없음 | LEAVE | 스펙이 주행 장면에만 상한을 둔다. smoothstep 1.5초 |
| T3 | 깨진 pending 테스트가 kind가 아닌 빠진 필드로 실패 | LEAVE | 테스트 품질만. 코드 경로(`firstOrNull` → null)는 맞다 |
| T3 | KDoc(DISPLAY_NAME 차이, SecurityException) | LEAVE(해결됨) | MediaStoreWriter에 반영됨 |
| T3 | VideoOptionsJson.Entry 수동 동기화 | LEAVE | 필드 5개. 옵션을 추가할 때 같이 고친다 |
| T4 | close()는 job finally에서만 / 실패 시 공간 확인 | LEAVE(해결됨) | `makeVideo` finally, `failureMessage` |
| T4 | 720p 대체가 모든 IllegalStateException에서 동작 | LEAVE | 잘못 대체돼도 720p 영상과 안내가 나올 뿐 |
| T4 | close() 단계 안전성 | LEAVE | 생성에 성공한 뒤에만 부르고 state로 막는다 |
| T4 | OOM 때 HandlerThread 누수 | LEAVE | 프로퍼티 초기화 순서상 frame 할당 OOM일 때 스레드 1개. 드묾 |
| T4 | 프레임 수 불일치를 로그만 남김 | LEAVE | 기기 테스트에서 48/48 확인 |
| T4 | EGL release가 스레드 컨텍스트 초기화 | LEAVE | 그 스레드에 다른 EGL 사용자가 없다 |
| T4 | 5.1 다운믹스가 센터 채널 버림 | LEAVE | 배경음악으로는 드묾 |
| T4 | 색 메타데이터(BT.709) 없음 | LEAVE | 실주행 공유(항목 8)에서 색이 어긋나면 그때 |
| T4 | 테스트 공백(4초 페이드, finish 없는 close) | LEAVE | 위험이 작다 |
| T5 | 영상 다운로드가 mutex를 몇 분 잡음 | **FIX-NOW (#1)** | 여행 삭제, 안내 도착·시작, 지도 지우기가 기다린다(I1) |
| T5 | maxTiles 무제한, Wi-Fi 전용 무시 | LEAVE | 컨트롤러 판단(사용자가 요청한 작업). 실주행에서 데이터량 메모 권장 |
| T5 | 강제 종료 뒤 완료된 VIDEO 영역 재사용 | LEAVE | 다음 새 시작의 `unfinished()`가 지우고, 재사용해도 타일은 유효 |
| T5 | video → ui.history 의존 | LEAVE | 구조 문제일 뿐 동작 영향 없음 |
| T5 | renderer 사이 작은 중복 | LEAVE | 같음 |
| T5 | 테스트 공백(HEART_RATE 색, offline-with-cache=true) | LEAVE | 실주행 항목 5·12에서 확인 |
| T5 | 끝 카드 기기 테스트 "카드 안 0" 단정이 공허 | LEAVE | JVM `outroFitsTheRouteAboveTheCard`가 기하를 검증하고, 기기 쪽 `above > 1000`은 유효 |
| T6 | `finally{opened?.close()}`가 원래 예외를 가림 | LEAVE | 결과가 Failed로 같다. 녹화 중이면 대화상자도 억제 |
| T6 | setPending(uri 없음)과 publish 사이 고아 IS_PENDING | LEAVE | ms 단위 창. MediaStore가 오래된 pending을 자동 정리 |
| T6 | Throwable catch에 `published` 가드 없음 | **FIX-NOW (#2)** | 저장된 영상이 실패로 표시된다(I2) |
| T6 | mirror StateFlow 병합으로 A의 완료 알림 누락 | LEAVE | B가 곧바로 시작하려면 앱이 화면에 있어야 하고, 그러면 A는 앱 안에서 보인다 |
| T6 | M6 부분 파일 삭제 전 공간 판정 | LEAVE | 부분 파일이 아직 있으면 오히려 "공간 부족"으로 기울 뿐 |
| T6 | 경계 테스트가 launch 뒤 `running` 설정에 의존 | LEAVE | 테스트 구조만 |
| T7 | NumberRow 자동 축소가 기록·구간 화면에도 적용 | LEAVE | 넘칠 때만 줄어든다. 실주행 때 눈으로 확인 |
| T7 | run finally에서 Error 시 claimed/runningTripId 남음 | **FIX-NOW (#3)** | 3줄 수정. 걸리면 앱을 다시 시작할 때까지 영상을 못 만든다 |
| T7 | M2 재개 뒤 ▶ 아이콘 | LEAVE | 미리보기 화면의 외관 |
| T7 | M4 persistable grant가 쌓임 | LEAVE | 실패는 잡힌다. 한도(512)에 닿아도 기능 영향 없음. 나중에 take를 빼도 됨(시작 때만 읽음) |
| T7 | M5 시트 닫힘 애니메이션 | LEAVE | 외관 |
| T7 | M6 테스트 공백 | LEAVE | 작업 7 재검토에서 핵심 경로는 다룸 |

---

## 스펙 구간별 대조
- §0~§1.3: 진입 위치(요약 2곳, 기록 화면 위쪽), 대상 구간 조건, 옵션과 기본값, [심박] 조건, 예상 시간, 옵션 기억(음악 제외), 이미지는 비율만. 모두 있다.
- §2.1~§2.5: 시작 3초(제목 사라짐 포함), 카메라 55°/15.5/150 m/60°/s 평활, 선 두께·불투명도, 흰 테두리 점, 이동 시간 비례, 숫자 띠(±10초, ±30초, 빈 칸 생략), N일째 자막 1.5초, 사진 2.5초(0.4/1.7/0.4, 80%, 둥근 모서리, 흰 테두리, "오후 8:49", 상한, 고르게, ±1초 연속), 끝 4초 카드와 어두워짐, R 계산. 있다. 단 §2.5의 50% 문장은 m4 참고.
- §3.1~§3.6: 지도(VIDEO, 13~16, 끝나면 삭제), 스냅샷 재사용, H.264 8 Mbps/키프레임 1초, 음악 AAC, FGS 종류, 알림, 하나만, 기록 우선, 배터리, 취소·실패 시 삭제, 강제 종료 제안, 저장 공간(사전·중간), 음악 오류, 720p, 일부 지도, 저장 경로·이름·IS_PENDING, 미리보기·공유·갤러리, 예상 시간. 있다.
- §4: 세로·가로 배치, 사진 위치 동그라미, 출발·도착 점, "주행 N회" 대체. 있다.
- §5.1~§5.3: trip_media, CASCADE, 줄과 정리, 갤러리 파일 보존, DataStore pending, 권한, 테스트 범위. 있다. 기기 수동 항목 4개는 문서에 "미실시"로 정직하게 표시되어 있다.
- 빠지거나 어긋난 것: 없음. m4의 스펙 문장 산수 오류만 있다.

## 판단을 보류한 것 (사용자 결정 필요)
- **기록 없이 안내만 할 때(`autoStartRecordingWithNavigation` 끔)는 영상 job이 멈추지 않는다.** 스펙 §3.2는 "기록 중"만 막는다. 안내 중에도 영상 렌더링(CPU, 스냅샷 콜백과 투영의 Main 사용)이 같이 돈다. rideScope도 Main.immediate이므로 안내 반응이 조금 느려질 수 있다. "GPS 우선" 취지로 막으려면 `recording` 조건에 `navigationState`의 Starting/Active를 더하고 문구를 정해야 한다(예: "안내 중에는 만들 수 없습니다"). 스펙 변경이라 FIX-NOW에서 뺐다.
- **영상을 만드는 동안 그 여행을 지우면 job을 함께 취소할지**: 스펙에 없다. FIX-NOW #2는 "만든 영상은 저장하고 미리보기"로 정리했다. 취소를 원하면 TripDeleter에서 `videoJob.runningTripId == tripId`일 때 `cancel()`을 부른다.
- **화면 복원(savedInstanceState 있음) 실행에서는 강제 종료된 작업 정리(`unfinished()`)가 돌지 않는다**: 다음 새 시작까지 VIDEO 영역과 IS_PENDING 항목이 남는다. 의도된 동작(R V6)과 맞고 해가 작아 보류했다.
