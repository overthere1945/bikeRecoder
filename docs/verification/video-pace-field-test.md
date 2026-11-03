# 영상 다듬기 2 — 박진감 V1.11.0 검증 기록

설계 스펙(`docs/superpowers/specs/2026-11-03-video-pace-design.md`) §9(검증)에 따른 기록 문서입니다. 영상 길이를 거리로 정하는 것, 거리 기준 빠르기와 볼거리 강약, 더 높고 천천히 도는 카메라, 초당 30장, 음악 박 읽기와 장면 맞추기, 앱에 든 기본 곡 세 곡(햇살 페달·바람길·언덕 너머)과 옵션 창의 길이 문구(최대 30초/1분/2분)를 다룹니다. DB는 바꾸지 않았습니다(9판 그대로).

> **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다.
> connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 아무것도 하지 않습니다(설치는 사용자가 요청할 때 DB·DataStore 백업과 표 개수 비교 뒤에).

## 자동 검증

### PC 단위 테스트

실행일: 2026-11-04, 브랜치 마지막 커밋: `ca3c557`

명령:

```bash
./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test :app:compileDebugAndroidTestKotlin </dev/null
```

결과: BUILD SUCCESSFUL — core 744개, app 1508개, brouter 19개 통과(실패·건너뜀 0), 기기 테스트 컴파일 통과

### 기기 테스트 (Galaxy S22 Ultra `R5CT43TFS9A`)

시리얼 없이 실행하면 연결된 모든 휴대폰에서 돌고 앱을 지우므로 항상 `ANDROID_SERIAL=R5CT43TFS9A`를 붙입니다. 실행 전 `adb devices`로 확인하고 화면을 깨웁니다. 한 번에 클래스 하나씩 실행합니다.

```bash
adb devices
adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP
ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스> </dev/null
```

| 클래스 | 테스트 수 | 결과 |
|---|---|---|
| `video.FrameRendererTest` | 27 | 26 통과, `screenshots` 1개는 켤 때만 도는 사진 찍기라 건너뜀 |
| `video.VideoJobDeviceTest` | 4 | 4 통과(길이가 계획과 2장 안, 최대 30초 이하) |
| `ui.video.VideoFlowDeviceTest` | 2 | 2 통과(기본 곡 햇살 페달의 AAC 소리 트랙 있음) |
| `video.AudioTrackMakerTest` | 5 | 5 통과(시작점 1초부터 자르기 포함) |
| `video.BuiltInMusicBeatsTest` | 3 | 3 통과(124·112·132박 ±1, 곡마다 분석 약 5초) |
| `ui.video.VideoOptionsSheetTest` | 10 | 10 통과 |
| `ui.settings.SettingsScreenTest` | 22 | 22 통과 (`V1.11.0`) |
| `video.VideoEncoderTest` | 3 | 3 통과 (30 fps 인코딩) |

### release 빌드

- `:app:assembleRelease`의 `resources.txt`에 `raw:music_*` 세 곡이 "reachable from keep xml file"로 남고, `aapt2 dump resources`에 `raw/music_*` 세 이름이 있음(`res/raw/keep.xml`, `BuiltInMusicResourcesTest`)
- 휴대폰에서 release로 기본 곡 영상 만들기는 확인하지 못함: release 빌드가 시작하자마자 멈춤(`IllegalAccessError`, R8). V1.10.0 release도 똑같이 멈춰 이번 변경과 무관한 이전부터의 문제로, 따로 고침

## S22 비교 영상 (진행자, 스펙 §9.2)

기기: Galaxy S22 Ultra `R5CT43TFS9A`. S25에서 가져온 11월 3일 00:08 걷기와 10월 25일 라이딩 기록의 복사본으로 V1.10.0과 V1.11.0 영상을 같은 옵션으로 만들어 견줍니다. 만드는 시간은 진행 표시를 몇 초 간격으로 확인해 어림한 값입니다. 나란히 붙인 비교 영상(왼쪽 V1.10.0, 오른쪽 V1.11.0, 소리는 V1.11.0)은 PC의 작업 폴더에 둡니다.

| 기록 | 버전 | 길이 | 만드는 시간 | 메모 |
|---|---|---|---|---|
| 11월 3일 걷기 (1.17 km) | V1.10.0 | 30.0초(24장/초) | 약 1분 | 짧게(약 30초), 음악 없음 |
| 11월 3일 걷기 (1.17 km) | V1.11.0 | 18.5초(30장/초) | 약 1분(음악 분석 약 6초 포함) | 최대 30초, 햇살 페달. 옛 저장값(음악 키 없음)을 햇살 페달로 읽음 |
| 10월 25일 라이딩 (1.6 km) | V1.10.0 | 30.0초(24장/초) | 약 1분 | 짧게(약 30초), 음악 없음. 12초에 0.64 km |
| 10월 25일 라이딩 (1.6 km) | V1.11.0 | 23.1초(30장/초) | 약 1분 | 최대 30초, 햇살 페달. 12초에 1.00 km, 카메라가 더 넓게 봄 |

## 실사용 점검표 (V1.11.0, 스펙 §9.3)

기기: Galaxy S25, 앱 버전은 `V1.11.0`(설정 탭 맨 아래 표기와 같은지). 설치 전 DB·DataStore를 백업하고, 기록 중이 아닌 것을 확인하고, 설치 전후 표 개수와 내용이 같은지 봅니다.

| # | 항목 | 방법 | 합격 기준 | 결과 | 메모 |
|---|---|---|---|---|---|
| 1 | 짧은 걷기 영상 | 1 km 남짓 걷기 기록으로 영상을 만들어 봄 | 1 km 남짓 걷기의 영상이 20초 안팎이고, 점이 화면을 시원하게 지나감 | (대기) | |
| 2 | 긴 라이딩 영상 | 10 km 넘는 라이딩(또는 가져온 GPX)으로 영상을 만들어 봄 | 곧은 길은 빠르고 굽은 길·사진 앞은 느려짐 | (대기) | |
| 3 | 카메라 | 위 영상들에서 카메라 움직임을 봄 | 천천히 도는 비스듬한 시점, 내려오며 감아 돎, 어지럽지 않음 | (대기) | |
| 4 | 음악 박자 | 기본 곡 세 곡을 하나씩 골라 영상을 만들어 봄. 박이 약한 내 음악 파일도 하나 | 기본 곡 세 곡 각각에서 사진·끝 장면이 박에 맞아 들림. 박이 약한 내 음악 파일은 그냥 깔림 | (대기) | |
| 5 | 만드는 시간 | 1 km 걷기와 10 km 이상 기록의 만드는 시간을 잼 | 1 km 걷기 영상이 V1.10.0보다 빨리 만들어지고, 10 km 이상도 지나치게 늘지 않음 | (대기) | |
| 6 | 오프라인 영상 | 여행 지도를 받아 둔 긴 라이딩을 인터넷 없이 영상으로 만들어 봄 | 높아진 카메라에서도 화면 위쪽이 비지 않음(여행 지도 범위는 경로 좌우 2 km) | (대기) | 최종 검토에서 나온 확인 항목 |

## 판정

- PC 테스트와 기기 테스트가 통과하고 S22 비교 영상이 V1.10.0보다 나으며 점검표 6개 항목이 모두 합격이면 `CHANGELOG.md`의 V1.11.0을 확정하고(「미출시」 표기 제거) 태그를 붙입니다.
- 불합격 항목은 원인 수정과 회귀 테스트 추가 뒤 그 항목만 다시 확인하고, 여기에 재검증 결과를 덧붙입니다.
- S25는 사용자가 요청할 때 DB 백업과 기록 개수 비교 뒤에 설치합니다.
