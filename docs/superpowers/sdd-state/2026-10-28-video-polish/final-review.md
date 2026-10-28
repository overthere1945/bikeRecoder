# V1.3.1 영상 다듬기: 브랜치 전체 최종 검토 (24a6a70..2460dc2, 커밋 14개)

검토자: 최종 리뷰(opus). 읽기 전용으로 검토했습니다. 리뷰 패키지 diff(9,705줄)를 한 번에 나눠 읽었고, 확인이 필요한 곳만 저장소 파일을 직접 열었습니다. 기기는 쓰지 않았습니다.

증거: `./gradlew :core:test :app:testDebugUnitTest`를 한 번 돌렸습니다. **BUILD 성공, 1,151개 통과(core 329 + app 822), 실패 0, 건너뜀 0.** video-field-test.md에 적힌 수와 같습니다.

## 판정

**FIX-NOW를 고친 뒤 합칠 수 있습니다.**

Critical은 없습니다. DB, 사용자 기록, 저장 흐름은 안전합니다. 이미 결정된 FW1·FW2와 아래 Important 3건은 고치는 비용이 작습니다. 그런데 사용자가 직접 보게 되는 결과가 틀립니다. 경우는 셋입니다.
- 긴 여행을 짧은 영상으로 만들면 카메라가 프레임마다 홱홱 돕니다.
- 같은 경로로 영상을 다시 만들면, 다음 셀룰러 안내가 데이터 양을 10배쯤 적게 말합니다.
- 키 가리기에 빈틈이 하나 있습니다.

FIX-NOW는 8개입니다. 한 사람이 한 번에 처리할 수 있습니다.

## 잘된 점

- **키 안전성이 대체로 단단합니다.**
  - `SatelliteProbe`는 예외 클래스 이름만 로그에 남깁니다(SatelliteProbe.kt:76).
  - 작업 로그는 `VideoMapStyle.redacting`으로 감쌌고, 이 함수는 cause 사슬까지 가립니다(AppContainer.kt:277, VideoMapStyle.kt:55-64).
  - `AppVersionTest`는 실패해도 키가 출력되지 않게 `assertTrue`로 비교합니다.
  - 시험은 모두 `TESTKEY`를 씁니다. 실제 키는 기기 시험에서만 `BuildConfig`로 읽습니다.
- **시작 확인의 순서와 실패 처리가 정확합니다.**
  - 순서는 지도 → 위성 확인 → 음악입니다(VideoJob.kt:157-159). 인터넷이 없으면 확인하기 전에 `NO_INTERNET`을 돌려줍니다.
  - `NO_KEY`이면 요청을 보내지 않고 안내도 띄우지 않습니다.
  - `FAILED`이면 일반 지도로 만들고 `Done.satelliteFallback`을 켭니다. 이 값은 알림 둘째 줄과 미리보기 인텐트·내비 인자까지 그대로 전달됩니다.
- **취소가 실제로 통신을 멈춥니다.** 위성 확인과 카카오 조회는 코루틴이 취소되면 `call.cancel()`로 소켓 대기를 끊습니다. 그래서 `withTimeoutOrNull(5 s)`이 정말 5초에 끝나고, 취소도 바로 먹습니다(KakaoLocalClient.kt:171-215).
- **이음매 C1·C3이 맞게 연결됐습니다.** `makeVideo`는 인코더의 최종 w/h로 `introCamera`, `outroCamera`, `heightPx = h`를 넘깁니다(VideoJob.kt:392). `heightPx`의 기본값은 지웠고, 720p 거울 시험도 있습니다. `FrameRenderer`의 띠 배치, 경로가 사라지는 띠, 끝 카드는 모두 렌더러의 width/height에서 계산합니다. 이것도 인코더 크기입니다.
- **정리 경로를 보존했습니다.** V1.3.0의 NonCancellable 정리와 claim 해제 순서를 그대로 두었습니다. 지역 조회는 `setPending` 뒤 작업 안에서 하고, 실패하면 지역 없이 이어갑니다.
- **시험이 꼼꼼합니다.**
  - 기기 시험이 픽셀을 셉니다. 경로가 띠 밑에서 사라지는지 세로·가로 모두 보고, 제목과 경로가 겹치지 않는지, 720p 배치도 봅니다.
  - JVM 작업 시험은 위성 확인 OK/FAILED/NO_KEY, 위성 확인과 음악의 순서, 지역 조회 시간 초과(가상 시계), 프레임당 바이트 기록을 확인합니다.

## 횡단 점검 결과

### 1. 키 안전성
- 커밋, 문서, 시험에 실제 키 값이 없습니다(task-8 보고와 diff로 확인).
- 빈틈: `MapSnapshots.warn`(MapSnapshots.kt:135-143)은 `error.toString()`에만 키가 있는지 봅니다. 키가 cause 안에만 있으면 원래 예외를 cause째 로그에 넘깁니다. `VideoMapStyle.redacting`이 같은 일을 이미 더 정확하게 하므로 중복이기도 합니다. → FIX-NOW 3
- `videoScope`의 `CoroutineExceptionHandler`(AppContainer.kt:248)는 예외를 가리지 않고 `Log.e`로 남깁니다. 지금 코드에서는 지도 오류가 여기까지 오지 않습니다. `run()`이 Throwable을 모두 잡기 때문입니다. 그래도 한 줄이면 막을 수 있습니다. → FIX-NOW 3에 포함
- MapLibre 네이티브가 직접 쓰는 logcat 줄은 판정 K1에 따라 허용합니다.

### 2. 이음매 (인코더 최종 w/h, 720p와 가로 포함)
- **introCamera:** `FrameLayout.title(w,h).block.y − 6u` 위쪽 칸에 경로를 맞춘 뒤 `cameras()`가 확대를 1 줄입니다. 계산해 봤습니다.
  - 1920×1080에서 제목 블록 위쪽은 652 px입니다. 경로는 0~634 px 칸에 맞춰지고, 확대를 줄이면 아래 끝이 약 555 px로 올라옵니다. 제목과 겹치지 않습니다.
  - 기기 시험 `introRouteClearsTheTitle`이 세로·가로 모두 확인합니다. 정상입니다.
- **outroCamera와 끝 카드:** 같은 `OutroCardLayout.card(w, h, summaryCells.size = 6)`에서 나옵니다. 가로는 3열로 바뀌었습니다. 정상입니다.
- **heightPx:** `markerLeadM`은 높이에 대해 일반적으로 계산합니다. 720p는 기기 시험(0.62~0.72)으로 확인했습니다. 가로 현재 위치를 따로 보는 시험은 없습니다. 그러나 가로의 위치(약 723 px)가 띠 위쪽 끝(약 788 px)보다 위에 있어 충돌하지 않습니다. LEAVE.
- **띠 밑에서 경로가 사라지는 구간:** `hudLayout`을 렌더러의 w/h와 `maxCells`로 한 번 계산하고, `floor`/`ceil`로 픽셀 줄에 맞춥니다. 세로·가로 시험이 있습니다. 정상입니다.
- **사진 장면:** 세로 1080×1920에서 시각 글자의 기준선이 약 1,335 px입니다. 현재 위치(1,280)와 띠 위쪽 끝(약 1,402) 사이, 그러니까 꼬리 바로 위에 놓입니다. → FW1 (FIX-NOW 1)

### 3. 실패 경로 (반쯤 남는 상태가 없는지)
| 상황 | 동작 | 남는 것 |
|---|---|---|
| 인터넷 없음, 받아 둔 지도 없음 | 위성 확인 전에 `NO_INTERNET` | 없음(시작 전 반환, claim 해제) |
| 인터넷 없음, 받아 둔 지도 있음 | 위성 확인이 바로 실패해 일반 지도 + 안내(J1) | 없음 |
| 브이월드 실패 / 10초 초과 | `FAILED`이면 일반 지도, 알림 둘째 줄과 미리보기 안내 | 없음 |
| 키 없음 | `NO_KEY`이면 요청 없이 일반 지도, 안내 없음 | 없음 |
| 지역 조회가 느림 | 5초에 포기하고 "아침 라이딩"만 씀(가상 시계 시험 있음) | 없음 |
| 위성 확인 중에 기록 시작 | 작업이 시작되자마자 감시자가 취소해 `Failed("기록이 시작되어…")`. 정리는 깨끗함 | 없음. 다만 `StartResult.RECORDING`이 아니라 "멈췄습니다" 알림이 뜸 → FIX-NOW 6 |
| 위성 확인 중 취소 | 진행 카드가 아직 없어 취소할 수단이 없음(최대 10초, 버튼은 바쁨 표시) | 없음 |
| 지역 조회 중 취소 | 코드상 Cancelled. finally에서 `setPending(null)`과 VIDEO 지역을 지우고, 갤러리 항목은 아직 만들어지지 않음 | 없음. 단, 시험이 없음 → FIX-NOW 7 |

### 4. 실제 휴대폰(S25)의 데이터 안전성
- DB 스키마 변경이 없습니다. diff에 `schemas/`, `data/` 엔티티·DAO 변경이 없습니다. `TripMedia`도 그대로입니다.
- 삭제 경로도 새로 생긴 것이 없습니다. 지우는 것은 기존의 `deleteVideoRegions`(VIDEO 종류만)와, 실패·취소 시 작업이 만든 갤러리 항목뿐입니다. 주행, 여행, 사진, 여행 지도 지역은 건드리지 않습니다.
- 새 DataStore 키 `satellite_bytes_per_frame` 하나만 더했습니다(기존 키는 그대로).
- 버전은 `verPatch = 1`이라 10301 / `1.3.1-dev`입니다. AppVersionTest가 통과했습니다.
- 기기 시험(`VideoFlowDeviceTest` 등)은 시험용 여행·사진을 넣고 지웁니다. 문서에 S22에서만 돌리고 S25에서는 `installDebug`만 하라는 경고가 있습니다.

### 5. 프레임당 성능
- 측정값은 S22에서 약 0.10~0.11 s/프레임입니다(74 s / 720).
- 경로를 두 번 그리는 것(띠 밑에서 사라지게 하려고 saveLayer 안에서 한 번 더), BlurMaskFilter 번짐, 프레임마다 Paint·LinearGradient를 새로 만드는 것은 측정값으로 보아 실제 문제가 아닙니다. 고치지 않습니다.
- 보통·길게는 측정하지 않았습니다(문서에 적혀 있음).

## 발견 사항

### Critical
없습니다.

### Important

**I1. 사진 장면에서 시각 글자가 경로 꼬리 위에 겹침 (판정 FW1)**
- 위치: `app/src/main/java/com/cowork/bikerecoder/video/FrameRenderer.kt:136`(경로 그리기), `:144`(photoAlpha), `:419-420`(시각 글자)
- 문제: 세로에서 글자 기준선이 약 1,335 px로, 현재 위치와 띠 사이 꼬리 위에 놓입니다. 경로는 사진 장면에서도 알파 1로 그려집니다.
- 고치는 법: FIX-NOW 1

**I2. 긴 여행을 짧은 영상으로 만들면 프레임마다 "건너뜀"이 되어 평활이 꺼짐**
- 위치: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CameraPath.kt:159`
- 문제: 연속한 두 주행 프레임 사이 직선거리만 500 m와 비교합니다. 프레임당 이동 거리가 500 m를 넘는 경우가 실제로 있습니다.
  - 200 km 2일 여행을 짧게(사진 4장, 주행 13 s = 312장)로 만들면 프레임당 약 640 m입니다.
  - 서울–부산 633 km를 보통으로 만들면 프레임당 약 800 m입니다.
  - 이때는 모든 프레임이 건너뜀으로 판정됩니다. 방향은 그 프레임에서 120 m 앞을 보는 방향으로 즉시 바뀌어, 프레임마다 수십 도씩 홱 돕니다. 확대도 매번 목표값으로 튑니다.
- 왜 중요한가: 스펙 §2.1의 이 규칙은 "여러 날·구간 사이"를 위한 것입니다. 앱이 지원하는 여러 날 여행에서 가장 어지러운 영상이 나옵니다. Task 1에서 "Task 7에서 확인"으로 미뤄 둔 항목인데, Task 7의 시험 여행이 1.85 km라 확인되지 않았습니다.
- 고치는 법: FIX-NOW 4

**I3. 같은 곳을 다시 만들면 프레임당 바이트가 캐시 때문에 아주 작게 기록되어 다음 셀룰러 안내가 크게 틀림**
- 위치: `app/src/main/java/com/cowork/bikerecoder/video/VideoJob.kt:422`, `VideoJobStore.kt:76`
- 문제: 지금은 0만 버립니다. 같은 여행을 비율이나 옵션만 바꿔 다시 만들면, 위성 타일 대부분이 MapLibre 캐시(기본 50 MB)에서 오거나 304 재검증으로 끝날 가능성이 큽니다. 그러면 몇 KB/장 같은 작은 양수가 기록되고, 다음에 다른 여행의 안내가 "약 2 MB"처럼 나옵니다. 실제는 약 24 MB입니다.
- 왜 중요한가: §1.4 안내는 셀룰러 데이터 비용을 미리 알려 주려는 것입니다. 10배 적게 말하면 안내의 목적이 무너집니다.
- 신뢰도: 캐시 동작은 기기에서 확인하지 않았습니다. 다만 고치는 비용이 작고 부작용이 없습니다.
- 고치는 법: FIX-NOW 5

**I4. 처음 예상 시간이 2.4배 넉넉함 (판정 FW2)**
- 위치: `VideoJob.kt:469` `DEFAULT_SECONDS_PER_FRAME = 0.25`
- 문제: S22 실측은 약 0.10 s/프레임입니다. 고치는 법: FIX-NOW 2

**I5. K1 빈틈: MapSnapshots가 cause 안의 키를 놓침, 가리기 코드 중복**
- 위치: `app/src/main/java/com/cowork/bikerecoder/video/MapSnapshots.kt:135-143`
- 고치는 법: FIX-NOW 3

### Minor

**M1. 위성 확인(최대 10초)이나 음악 인코딩 중에 기록이 시작된 경우**
- 위치: `VideoJob.kt:157-161`, `:180-181`
- 문제: 정리는 깨끗하지만, "기록 중에는 만들 수 없습니다"(RECORDING) 대신 서비스가 잠깐 뜨고 "기록이 시작되어 영상 만들기를 멈췄습니다" 실패 알림이 남습니다.
- 고치는 법: FIX-NOW 6

**M2. 지역 조회 중 취소를 확인하는 시험이 없음**
- 위치: VideoJobTest
- 문제: 코드는 맞지만, 이 시험이 없으면 나중에 회귀해도 알 수 없습니다.
- 고치는 법: FIX-NOW 7

**M3. `secrets.example.json`의 `vworldApiKey` 자리표시자가 빈 값이 아님**
- 위치: `secrets.example.json:3`
- 문제: 예시 파일을 그대로 복사하면 "키 없음(조용한 일반 지도)"이 아니라 "틀린 키"가 됩니다. 영상마다 위성 확인이 실패하고 대신 만들기 안내가 뜹니다. 카카오 줄은 `""`라 서로 맞지도 않습니다.
- 고치는 법: FIX-NOW 8

**M4. 키 없는 빌드에서도 셀룰러면 위성 데이터 안내가 뜸**
- 위치: `VideoViewModel.kt:~385`
- 문제: 실제로는 일반 지도로 만드는데 위성 데이터를 경고합니다. 사용자 빌드에는 키가 있어 영향이 없습니다. LEAVE.

**M5. 끝 카드 불투명도가 255로 스펙 248과 다름**
- 위치: `FrameStyle.CARD_OPACITY`
- 상태: Task 4에서 편차 ②로 받아들였고, 이유가 KDoc에 있습니다. 판정 목록(global-constraints)에는 없습니다. 기록용으로만 적습니다. LEAVE.

**M6. 사진 장면 시험이 세로만 픽셀로 확인함**
- 문제: 가로의 사진 카드는 숫자 띠 그라데이션(위쪽 끝 약 788 px)에 걸칠 수 있습니다(카드 아래 한계를 그래프 y 954에서 계산). 가독성에는 문제가 없습니다. LEAVE.

## 스펙 대조 (절별)

| 스펙 절 | 상태 | 비고 |
|---|---|---|
| §0 범위, 바꾸지 않는 것 | 충족 | 장면 순서와 프레임 수 합계는 같음(29+43=72, 96). DB v5 그대로 |
| §1.1 영상 지도 스타일 | 충족 + 판정 | 래스터 256, 6~19, 레이어 두 개와 class 제한, 흰 글씨와 테두리 1.6, 출처 한 줄. L1(길 이름 24, viewport pitch), F1(minzoom: suburb 13, quarter/neighbourhood 15, 길 15) 적용. 출처는 끝 카드 아래쪽(F1)과 이미지 머리(S1). base style이 없으면 사진만 |
| §1.2 인증키 | 충족 | 키가 비면 `NO_KEY`로 일반 지도. 로그는 가림(빈틈은 I5) |
| §1.3 시작 전 확인 | 충족 + J1 | z17 출발 타일, 200 + image + JPEG/PNG 매직 바이트, 10초. VIDEO 벡터 미리 받기는 그대로 |
| §1.4 데이터 안내 | 충족(I3 보강 필요) | 셀룰러만, 20/35/60, TrafficStats 차이 / 프레임 수, DataStore, 이미지 시트에는 없음 |
| §2.1 주행 카메라 | 충족(I2 보강 필요) | 60°, 17.4~16.6, 0.25/s, 2/3 투영, 120 m, 45°/s, 25 m, 흔들기(영상 시간 기준), 500 m |
| §2.2 시작 | 판정 F4로 편차 | 하늘 위 장면은 정북 고정. 내려가기에서만 짧은 쪽으로 돎. 확대 − 1, 제목은 4장에 걸쳐 사라짐 |
| §2.3 사진 | 충족 | 사진 위치를 축으로 선형 40°. 사진 위치는 화면 2/3에 고정됨(중심이 앞으로 lead만큼). 다음 주행은 그 방향에서 이어감 |
| §2.4 끝 | 충족 | 솟아오르기 36장 smoothstep, 카드는 29장째부터 6장, 끝 24장 어두워짐, 경로 전체를 지나온 길로 |
| §3.1 경로 | 충족 | 20/10/7u, 0.55, 블러 8u, 꼬리 150 m 9u·0.8(6단계로 넓어짐), 남은 길 흰색 0.5·4u. 심박 색도 번짐 |
| §3.2 점 | 충족 | 11/8u, 맥동 14~30u 1초 0.45→0. 끝 장면은 출발·도착 9u |
| §3.3 안개 | 충족 | |
| §3.4 숫자 띠 | 충족 + F2, V2-1 | 가로는 한 줄, 띠 약 37%. 세로 범위는 max(범위, 40) + 4 |
| §3.5 제목 카드 | 충족 + F3 | 덮개 0.45, 경로 0.7배, 34/18/24u. 위치는 화면 아래쪽(F3) |
| §3.6 사진 카드 | 충족(FW1 추가) | 0.85→1.04→1, 0.95, 1.06, 테두리 3u, 모서리 6u, 그림자 |
| §3.7 끝 카드 | 충족(M5) | 6칸, 대체 칸(주행 N회 / 상승 고도), bikeRecoder |
| §3.8 자동 제목 | 충족 | 경계 12곳 시험, 줄임표, H 결과, 5초, 작업당 한 번, 기록 목록 제목은 그대로 |
| §3.9 요약 이미지 | 충족 + S1 | 위성, FULL 경로, 점, 머리 제목, 대신 만들기 안내 |
| §4 문구 | 충족 | 글자 그대로, 알림 둘째 줄과 미리보기 두 줄 |
| §5 검증 | 대체로 충족 | 셀룰러 실기기와 대신 만들기 알림 확인은 "수동 확인 대기"로 정직하게 적혀 있음 |

## 문서 정확성
- **CHANGELOG [1.3.1]:** 코드와 맞습니다. FW1·FW2 반영 줄과 I2·I3 판정 줄이 필요합니다(FIX-NOW 1, 2, 4, 5).
- **README:** 키 준비 순서, 예시 JSON, 버전 줄, 출처 줄이 맞습니다. 브이월드 발급 화면의 메뉴 이름은 외부 사이트라 확인하지 못했습니다.
- **video-field-test.md:**
  - 시험 수(329/822)는 이번 실행과 같습니다.
  - 239행의 "약 3분 … 0.25초"는 FW2 뒤 갱신해야 합니다.
  - 260행과 S6 행(275행)은 FW1 뒤 갱신해야 합니다.
  - 85행은 V1.3.0 기록이므로 그대로 둡니다.
- **secrets.example.json:** M3 (FIX-NOW 8)

## 시험 품질
- 좋은 점: JVM 시험은 실제 동작(순서, 상태, 저장값, 가상 시계)을 확인하고, 기기 시험은 픽셀을 셉니다. 키가 로그에 없는지 확인하는 단언이 들어 있습니다(SatelliteProbeTest의 tearDown 등).
- 빈틈 1: 기록 중 시작(M1), 지역 조회 중 취소(M2), 긴 연속 경로의 평활(I2), 캐시로 작게 잰 바이트(I3)를 보는 시험이 없습니다. FIX-NOW에 시험을 함께 넣었습니다.
- 빈틈 2: MapSnapshots의 가리기를 확인하는 시험이 없습니다. 통합하면 `redacting` 시험으로 덮입니다.
- 빈틈 3: VideoJobTest의 `camerasAt`은 구현을 그대로 따라 쓴 거울 시험이고 가로 경우가 없습니다. 그러나 720 시험이 "싱크 크기를 쓴다"는 것을 증명하므로 LEAVE.

## FIX-NOW 목록 (한 사람이 순서대로 처리)

각 항목은 JVM 시험을 함께 고칩니다. 1번만 기기 시험(S22 `R5CT43TFS9A`만, 클래스 하나씩, 화면을 깨운 뒤 `</dev/null`)이 필요합니다. 끝에 `./gradlew :core:test :app:testDebugUnitTest`를 돌립니다.

1. **FW1: 사진 장면의 시각 알약과 경로 흐리게** (`app/src/main/java/com/cowork/bikerecoder/video/FrameRenderer.kt`, `FrameStyle.kt`)
   - `render()`에서 `photoAlpha` 계산(현재 :144)을 경로 그리기(:136)보다 위로 옮깁니다.
   - `else` 분기의 RIDE 경로를 다음과 같이 감쌉니다.
     ```kotlin
     canvas.layered(1f - FrameStyle.PHOTO_ROUTE_DIM * photoAlpha, routeBounds(route)) {
         fadedUnderBand(this, hudShown(plan)) { overlay.draw(it, route, position, unit, marker, RouteStyle.RIDE) }
     }
     ```
     `PHOTO_ROUTE_DIM = 0.6f`. 알파가 1이면 `layered`는 `save`만 하므로 평소 프레임의 비용은 그대로입니다.
   - `drawPhoto`의 시각 글자(:419-420)를 그리기 전에 알약을 먼저 그립니다.
     - 글자 너비 `tw = label.measureText(text)`
     - 사각형 `RectF(cx − tw/2 − 14u, baseline − 0.8·textSize − 7u, cx + tw/2 + 14u, baseline + 0.22·textSize + 7u)`
     - 모서리 반지름 = 높이/2, 색 `Color.argb((150 * alpha).roundToInt(), 0, 0, 0)`
     - 상수: `FrameStyle.CAPTION_PILL_ALPHA = 150`, `CAPTION_PAD_X = 14f`, `CAPTION_PAD_Y = 7f`
   - 기기 시험: `FrameRendererTest`에 `photoCaptionOnDarkPillAndRouteDimmed`를 더합니다.
     - 밝은 일반 배경(offline source)에서 사진 장면 가운데 프레임을 그립니다.
     - 알약 안에서 글자를 뺀 픽셀(예: 알약 왼쪽 끝 안쪽 4u 띠)의 밝기가 어두운지(예: 평균 휘도 < 100) 봅니다.
     - 카드 밖의 진한 주황 픽셀(r − b > 120) 수가 사진 장면 첫 프레임(photoAlpha = 0)보다 확실히 적은지 봅니다.
     - S22에서 그 클래스만 돌립니다.
   - 문서를 고칩니다.
     - video-field-test.md:260 "사진 장면…" 줄에 "사진이 떠 있는 동안 경로는 40% 밝기로 흐려지고, 찍은 시각은 어두운 알약 위에 씁니다"를 더합니다.
     - S6 행(:275)도 같은 기준으로 고칩니다.
     - CHANGELOG [1.3.1] "사진 카드" 줄에 같은 내용을 한 구절 더합니다.

2. **FW2: 처음 예상의 장당 기본값 0.25 → 0.15**
   - `VideoJob.kt:469`를 `DEFAULT_SECONDS_PER_FRAME = 0.15`로 바꾸고, KDoc에 "V1.3.1 판정 FW2: S22 실측 약 0.10 s/장(74 s / 720). V1.3.0 스펙 §3.6의 0.25에서 낮춤"을 적습니다.
   - `app/src/test/java/com/cowork/bikerecoder/ui/video/VideoViewModelTest.kt:156-160`을 고칩니다.
     - 이름: `without a measured speed the estimate counts the default seconds a frame`
     - 값: `expectedMinutes(VideoOptions(), VideoJob.DEFAULT_SECONDS_PER_FRAME)`
     - `assertEquals(0.15, VideoJob.DEFAULT_SECONDS_PER_FRAME)` 단언을 하나 더합니다.
   - video-field-test.md:239를 고쳐 씁니다. 예: "이 흐름의 옵션 시트는 당시 기본값 0.25초/장으로 "약 3분 걸립니다"를 보여 줬습니다. 실제는 74초(약 0.10초/장)여서 기본값을 0.15초로 낮췄습니다(판정 FW2). 이제 처음 예상은 지도 660장 × 0.15 + 다시 쓰는 60장 × 0.03 ≈ 101초라 "약 2분"이고, 한 번 만든 뒤에는 실측값으로 바뀝니다."
   - 같은 파일의 「사용자에게 보이는 동작을 바꾼 판정 (V1.3.1)」에 FW2 줄을 더합니다.
   - CHANGELOG [1.3.1] `### 변경`에 "처음 예상 시간의 장당 기본값 0.25초 → 0.15초(S22 실측 약 0.10초)"를 더합니다.
   - 85행(V1.3.0 기록)은 그대로 둡니다.

3. **키 가리기를 하나로 합치기 (K1, cause 사슬)**
   - `VideoMapStyle.kt`: `redacted(error, key)`를 `internal fun redactedError(error: Throwable, key: String): Throwable`로 공개합니다. 키가 비었거나 `error.stackTraceToString()`에 키가 없으면 원래 객체를 그대로 돌려줍니다. `redacting`이 이 함수를 쓰게 합니다.
   - `MapSnapshots.kt`:
     - 생성자 매개변수를 `log: WarnLog = androidWarnLog(TAG)`로 두고, 필드를 `private val log: WarnLog = VideoMapStyle.redacting(log, secret)`로 만듭니다. `secret`을 그보다 먼저 선언합니다.
     - `warn()`과 `Redacted`(:135-143)를 지웁니다.
     - 호출부 :112, :117, :161을 `log.warn(...)`로 바꿉니다.
   - `AppContainer.kt:248`의 video scope 핸들러를 `Log.e(TAG, "Unhandled error in the video scope", VideoMapStyle.redactedError(e, BuildConfig.VWORLD_API_KEY))`로 바꿉니다.
   - 시험: `VideoMapStyleTest`에 `redactedErrorKeepsCleanErrorsAndHidesCauseOnlyKeys`를 더합니다.
     - 키가 없는 예외는 같은 인스턴스를 돌려주는지 봅니다.
     - 키가 cause에만 있는 예외에서 결과의 `stackTraceToString()`에 `TESTKEY`가 없는지 봅니다.

4. **I2: 이어진 긴 경로에서는 평활을 끄지 않기**
   - 위치: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CameraPath.kt:138, :159, :167`
   - `var lastRideAlong = 0.0`를 더하고, RIDE에서 `val along = route.at(located)`를 구합니다.
   - 판정을 `val jumped = previous != null && GeoMath.distanceM(previous, located.point) > JUMP_M + (along - lastRideAlong).coerceAtLeast(0.0)`로 바꾸고, 끝에 `lastRideAlong = along`을 둡니다.
   - 구간 사이 이동은 `RouteDistance`에서 0으로 세므로(Hud.kt:53), 날이 바뀌는 곳은 지금처럼 다시 시작합니다. 이어진 주행은 프레임당 이동이 커도 평활을 유지합니다.
   - KDoc(:~95)과 JUMP_M 주석에 "경로로 지나온 거리보다 500 m 넘게 떨어지면"이라고 쓰고, CHANGELOG의 카메라 줄 끝 "500 m 넘게 건너뛰면"을 "구간 사이에서 500 m 넘게 건너뛰면"으로 고칩니다.
   - 시험: `CameraPathTest`에 `longContinuousRideKeepsSmoothing`을 더합니다.
     - 한 구간짜리 약 300 km 지그재그 경로(예: 5 km마다 ±60° 꺾임)를 VideoLength.SHORT로 만듭니다. 주행 프레임 사이 직선거리가 500 m를 넘는지 먼저 단언합니다.
     - 연속한 RIDE 프레임의 흔들기를 뺀 heading 변화가 모두 `MAX_TURN_DEG_PER_S / FPS + 1e-9` 이하인지 봅니다.
     - 연속 확대 변화가 `ZOOM_RATE_PER_S / FPS + 1e-9` 이하인지 봅니다.
     - 기존 `jumpsRestartSmoothing`도 통과해야 합니다.
   - progress.md에 판정으로 기록하도록 컨트롤러에게 알립니다(스펙 §2.1의 의도인 "여러 날·구간 사이"를 따른 해석).

5. **I3: 캐시 때문에 작게 잰 프레임당 바이트는 기록하지 않기**
   - `VideoJobStore.kt:76` `recordBytesPerFrame`의 조건을 `if (!v.isFinite() || v < MIN_BYTES_PER_FRAME) return`으로 바꾸고, `const val MIN_BYTES_PER_FRAME = 8_000.0`을 둡니다. S22 실측 26~33 KB의 약 1/4입니다. 이보다 작으면 타일 대부분이 캐시에서 온 것으로 봅니다.
   - KDoc을 "0 또는 [MIN_BYTES_PER_FRAME] 미만은 캐시에서 다시 쓴 영상이라 다음 영상의 예상이 되지 못한다"로 고칩니다.
   - 시험: `VideoJobStoreTest.bytesPerFrameRoundTrip`에 `store.recordBytesPerFrame(500.0)`을 더하고, 결과가 여전히 50,000인지 봅니다.
   - Task 6 보고의 "0은 기록 안 함" 설명과 맞추어 video-field-test.md의 데이터 안내 줄(:241 부근)에 한 문장 더합니다.

6. **위성 확인과 음악 뒤에 기록 중인지 다시 확인** (`VideoJob.kt`)
   - `startVideo`에서 음악 로드(:159-160) 뒤 `launch` 직전에 `if (recording.first()) return StartResult.RECORDING`을 넣습니다.
   - `startImage`에서도 위성 확인(:180) 뒤 `launch` 직전에 같은 줄을 넣습니다.
   - 이 위치에서 돌려주면 시작 전이므로 `finally`가 claim을 풉니다. 온라인 경로는 VIDEO 지역을 만들지 않습니다. 오프라인 경로의 `downloadForTrack`도 받아 둔 지도가 있는지만 보고 지역을 만들지 않습니다(MapLibreOfflineController.kt:131-132).
   - 시험: `VideoJobTest.recordingThatStartsDuringTheProbeRefuses`를 더합니다.
     - FakeBackend에 `var onProbe: () -> Unit = {}`를 두고 `probeSatellite`에서 호출합니다.
     - `onProbe = { recording.value = true }`로 두고, 영상과 이미지 모두 `StartResult.RECORDING`인지, `assertNothingStarted()`(서비스 0, pending 없음, 생성 없음)인지 봅니다.
     - `recording`을 false로 되돌린 뒤 다시 시작하면 STARTED인지 봅니다(claim 해제 확인).
     - 테스트의 `recording`이 MutableStateFlow가 아니면 그렇게 바꿉니다.

7. **지역 조회 중 취소 시험** (`app/src/test/java/com/cowork/bikerecoder/video/VideoJobTest.kt`)
   - `cancelDuringTheRegionLookupLeavesNothing`을 더합니다.
     - `val entered = CompletableDeferred<Unit>()`
     - `regionOf = { entered.complete(Unit); awaitCancellation() }`
     - `startVideo` → `entered.await()` → `job.cancel()` → `awaitEnd()`가 `VideoJobState.Cancelled`인지 봅니다.
     - `store.pending()`이 null이고, `backend.created`가 비었고, `maps`가 TRIP의 VIDEO 지역을 지웠고, `job.runningTripId.value`가 null인지 봅니다.
     - 다시 `startVideo`가 STARTED인지 봅니다.
   - 가상 시계는 필요 없습니다.

8. **`secrets.example.json`**: `"vworldApiKey": ""`로 바꿉니다. 카카오 줄과 같아지고, 그대로 복사해도 "키 없음 → 일반 지도"가 됩니다. README의 예시 JSON은 설명용이므로 그대로 둡니다.

## 미뤄 둔 minor 판정

| 작업 | 미뤄 둔 minor | 판정 | 이유 |
|---|---|---|---|
| 1 | 끝 직전 사진 뒤 솟아오르기를 보는 시험 없음 | LEAVE | `riseFrom = out.last()`로 단순함. 사진 카메라도 같은 `riding()`이라 이어짐 |
| 1 | 같은 곳 사진 연속 궤도를 보는 시험 없음 | LEAVE | 둘째 사진이 앞 사진의 끝 방향에서 이어서 돎(`photoStart = before.bearing`). 위험 낮음 |
| 1 | 끝 시험이 smoothstep과 직선을 구분 못 함 | LEAVE | 화면 품질 문제가 아님 |
| 1 | 흔들기 주기를 따로 확인하지 않음 | LEAVE | 진폭 시험이 있고 식이 한 줄 |
| 1 | 끝 시험의 남는 단언 | LEAVE | 겉모양 |
| 1 | 긴 여러 날 여행에서 프레임마다 JUMP_M에 걸려 평활이 꺼짐 | **FIX-NOW 4** | 실제로 프레임당 500 m를 넘어 영상이 어지러움(I2) |
| 1 | Task 6 뒤 heightPx 기본값 제거 | 이미 처리 | `cameras(..., heightPx: Int)`에 기본값 없음 |
| 2 | ElevationGraph ys의 도달 불가 coerceIn | LEAVE | 무해 |
| 2 | 첫 점 고도가 없을 때(xs[0] > 0) 시험 없음 | LEAVE | `graphPath`가 `x(0)` 바닥에서 시작해 맞게 그림 |
| 2 | 시험 준비 중복 | LEAVE | 겉모양 |
| 2 | satelliteMb 경계에서 부동소수점 1 MB 차이 | LEAVE | 안내 값이고 올림이라 무해 |
| 3 | 위성 확인의 image/ 대소문자 구분 | LEAVE | 브이월드는 소문자 `image/jpeg`(S22 확인). 틀려도 대신 만들기로 안전 |
| 3 | symbol-placement line 단언 없음 | LEAVE | 기기 캡처로 확인, 위험 낮음 |
| 3 | SatelliteMapTest가 PNG를 남김 | LEAVE | S22 앱 전용 외부 폴더, 지우면 사라짐. S25는 해당 없음 |
| 3 | isCellular 시험 없음 | LEAVE | 안드로이드 API를 얇게 감싼 것. 조건 로직은 VM 시험이 확인 |
| 3 | 빈 키로 깨진 URL | LEAVE | 백엔드가 `NO_KEY`로 막아 그 URL을 만들지 않음 |
| 4 | 프레임당 번짐 비용 측정 없음 | LEAVE | 전체 0.10 s/장 실측으로 충분 |
| 4 | 프레임마다 Paint와 그라데이션을 새로 만듦 | LEAVE | 측정상 문제 없음 |
| 4 | K1이 예외 cause 안의 키를 놓침 | **FIX-NOW 3** | 키 안전 규칙 |
| 4 | "주행 N회" 중복 | 이미 처리 | Task 5에서 `heartRateOrRides`로 합침 |
| 4 | outroCamera KDoc이 introCamera 위에 떨어져 있음 | 이미 처리 | 지금 FrameRenderer.kt:517-532에서 각 함수 위에 제대로 있음 |
| 4 | 제목 KDoc 문구가 오래됨 | LEAVE | 지금 `drawTitle` KDoc은 동작과 맞음 |
| 4 | 계획서의 introLooksDownThenSpiralsIn 설명이 F4 전 내용 | LEAVE | 계획서는 기록용. 판정 F4가 progress와 field-test에 있음 |
| 4 | 맞춤 확대 < 1 경계 | LEAVE | `coerceAtLeast(0.0)`. 실제 경로에서 일어나지 않음 |
| 4 | 띠 밑에서 사라지게 하려고 경로와 번짐을 두 번 그림 | LEAVE | 0.10 s/장 실측, 실제 문제 아님 |
| 5 | 번짐 시험 문턱이 느슨함 | LEAVE | 다른 픽셀 시험이 보완 |
| 5 | fill() 세 번 중복 | LEAVE | 겉모양 |
| 5 | stats()가 FrameRenderer companion에 기댐 | LEAVE | 같은 칸을 같은 곳에서 계산하는 것이 오히려 의도에 맞음 |
| 5 | ascentCell KDoc 없음 | LEAVE | 사소 |
| 6 | MapSnapshots와 VideoMapStyle의 가리기 중복, cause 누락 | **FIX-NOW 3** | 위와 같음 |
| 6 | 출발 직전 기록 중 재확인(위성 확인이 최대 10초) | **FIX-NOW 6** | 한 줄. 정리는 이미 깨끗하나 사용자에게 맞는 문구가 나감 |
| 6 | video scope 핸들러의 Log.e를 가리지 않음 | **FIX-NOW 3에 포함** | 지금은 도달하지 않지만 한 줄로 막힘 |
| 6 | 가짜 백엔드의 기본 job이 쓰이지 않는 job을 만듦 | LEAVE | 시험 코드 겉모양 |
| 6 | camerasAt이 거울 시험이고 가로 경우 없음 | LEAVE | 720 시험이 "싱크의 w/h를 쓴다"를 증명. 가로 배치는 기기 시험(띠 밑 사라짐, 제목)이 확인 |
| 7 | 셀룰러 안내를 시트마다 한 번만 읽음 | LEAVE | 실제로는 옵션을 바꿀 때마다(`estimate()`) 다시 읽음. 시트를 연 채 망이 바뀌는 경우만 늦음 |
| 7 | 첫 예상 전 레이아웃이 튐 | LEAVE | 겉모양 |
| 7 | 내비 인자와 인텐트 extra 왕복 시험 없음 | LEAVE | `downscaled`와 같은 형태. VM과 미리보기 시험이 값을 확인 |
| 7 | 밝은 땅에서 회색 남은 길의 대비가 낮음 | LEAVE | 스펙 §3.1이 정한 값(흰색 0.5). 주로 일반 지도로 대신 만들 때만 해당 |

## 판단하지 않은 것 (실행자가 판정)
- 프레임의 시각 품질(멀미, 글자 밀도): 기기를 돌리지 않았으므로 캡처와 실주행 체크리스트 S1~S12에 맡깁니다.
- MapLibre 앰비언트 캐시가 브이월드 타일을 실제로 다시 쓰는지: I3은 이 가정 위에 있습니다. 고쳐도 부작용이 없어 그대로 FIX-NOW에 넣었습니다.
- README의 브이월드 발급 메뉴 이름(서비스 유형 APP, WMTS/TMS·배경지도): 외부 사이트라 확인하지 못했습니다.
- 보통·길게 영상의 시간과 데이터: 측정하지 않았습니다(문서에 명시).
- MapLibre 네이티브 logcat 안의 키: 판정 K1로 허용했습니다.
- 위성 확인 중 [취소] 수단이 없는 것: 최대 10초이고 V1.3.0도 시작 확인 중에는 취소가 없었습니다. 스펙도 다루지 않습니다.
