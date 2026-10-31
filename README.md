# bikeRecoder

Bicycle navigation, route tracking, and photo video generator app based on travel routes.

자전거 주행을 기록하고, 자전거도로를 우선하는 길안내를 하며, 주행 경로를 사진과 MP4 영상으로 만들어 주는 개인용 안드로이드 앱입니다. 서버 없이 모든 처리를 휴대폰 안에서 합니다.

앱 아래쪽 탭(V1.4.0부터):

| 탭 | 내용 |
|---|---|
| 지도 | 장소 검색, 길안내, 기록 시작 (앱을 열면 늘 이 탭부터) |
| 요약 | 이번 주 띠와 위성 지도 큰 카드로 지난 여행을 훑는 피드, 제목 바꾸기 |
| 통계 | 주간·연간 목표, 연속 주행, 주·월·년·전체 통계, 개인 기록, 누적 지도 입구 |
| 설정 | 기본 경로 성향, 기록 옵션, 경로 데이터, 내려받은 지도, 사진·심박 연결, 공유(기본 해시태그), 기록 가져오기(GPX·ZIP), 앱 버전 |

- 현재 버전: **V1.6.0** · V1.0.0~V1.6.0 출시, 모두 실주행 검증 대기 — 버전 규칙: [docs/VERSIONING.md](docs/VERSIONING.md), 변경 이력: [CHANGELOG.md](CHANGELOG.md)
- 대상 기기: Samsung Galaxy S26 (Android 17), Galaxy Watch7 (심박, Health Connect 경유)

## 로드맵

| 버전 | 단계 | 주요 기능 | 상태 |
|---|---|---|---|
| V1.0.0 | 내비게이션 | 자전거도로 우선 오프라인 경로 탐색, 경유지, 음성 안내, 이탈 재탐색, 여러 날 여행 유지, 경로 주변 지도 오프라인 저장 | 출시(`v1.0.0`), 실주행 검증 대기 |
| V1.1.0 | 기록 코어 | GPS 기록, 속도·고도·평균·최고 속도, 자동/수동 일시정지, 음성 안내(시작·1km·일시정지·재개) | 출시(`v1.1.0`), 실주행 검증 대기 |
| V1.2.0 | 여행과 후처리 | 여러 날 기록 묶기, 갤러리 사진 자동 매칭, Watch7 심박, 누적 경로 지도 (V1.2.1: 주행별 색·기록 카드·짧은 기록 정리) | 출시(`v1.2.0`·`v1.2.1`), 실주행 검증 대기 |
| V1.3.0 | 영상과 이미지 | 요약 이미지, Relive 스타일 3D 영상(속도 비례, 사진 삽입), 기기 내 인코딩 | 출시(`v1.3.0`), 실주행 검증 대기 |
| V1.3.1 | 영상 다듬기 | 위성 지도(브이월드), 가까운 움직이는 카메라, 빛나는 경로·숫자 띠·제목 카드, 자동 제목(시간대·지역) | 출시(`v1.3.1`), 실주행 검증 대기 |
| V1.4.0 | 요약·통계 | 아래쪽 탭 [지도] [요약] [통계] [설정], 위성 지도 카드 피드와 이번 주 띠, 제목 바꾸기, 목표·연속 주행·기간 통계·개인 기록, 구간 요약의 목표 달성·신기록 줄 | 출시(`v1.4.0`), 실주행 검증 대기 |
| V1.5.0 | 활동 공유하기 | 요약 카드·여행 기록 화면·구간 요약의 [공유] 단추, 감정 칩과 해시태그가 든 자동 글(여행마다 기억), 페이스북·인스타그램·스토리·이메일·복사·저장·더 보기로 바로 보내기, 파일이 없으면 세로 요약 이미지를 그 자리에서 만들기, 설정의 기본 해시태그 | 출시(`v1.5.0`), 실주행 검증 대기 |
| V1.6.0 | GPX 가져오기 | 다른 앱(Relive·Strava 등)에서 받은 GPX·ZIP 파일을 설정 또는 파일 앱의 공유로 받아 확인 목록에서 고른 주행을 여행으로 가져오기(앱 기록 엔진으로 다시 계산), 카드·여행 화면의 가져옴 표시, [모두 지우기] | 출시(`v1.6.0`), 실제 Relive 파일 확인 대기 |

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 / UI | Kotlin, Jetpack Compose |
| 지도 | MapLibre Native Android 13.6.1, OpenFreeMap 벡터 타일, 영상·요약 이미지는 브이월드 위성 사진 |
| 경로 탐색 | BRouter 1.7.10 (MIT, 앱 내장, 오프라인) |
| 장소 검색 | 카카오 로컬 REST API |
| 저장 | Room, DataStore |
| 음성 | Android TextToSpeech (한국어) |

## 프로젝트 구조

```
app/                  Android 앱 (화면, 서비스, 지도, DB, 검색)
core/                 순수 Kotlin 로직 (모델, 진행 위치·이탈 판정, 음성 문구)
routing-brouter/      BRouter 연동과 경로 성향 프로필
third_party/brouter/  BRouter 원본 소스
docs/                 설계 스펙, 버전 규칙, 검증 기록
```

## 빌드 준비

1. Android Studio 최신 버전 (AGP 9.x), JDK 17 이상.
2. 카카오 REST API 키 발급
   - https://developers.kakao.com/ → 앱 생성 → **앱 > 플랫폼 키**에서 REST API 키 확인
   - **제품 설정 > 카카오맵 > 사용 설정 ON** (필수, 꺼져 있으면 검색 시 403 오류)
3. 브이월드 인증키 발급 (영상과 요약 이미지의 위성 지도용)
   - https://www.vworld.kr/ → 오픈API → 인증키 발급. 서비스 유형은 **APP**, 사용할 API는 **WMTS/TMS**와 **배경지도**를 고릅니다.
   - 키가 비어 있어도 빌드와 영상 만들기는 됩니다. 이때 영상과 이미지는 위성 대신 일반 지도로 만들어집니다.
4. 프로젝트 루트에 `secrets.json` 생성 (`secrets.example.json` 참고, Git에 올라가지 않음)

   ```json
   {
     "kakaoRestApiKey": "발급받은_REST_API_키",
     "vworldApiKey": "발급받은_브이월드_키"
   }
   ```

5. 빌드·설치

   ```bash
   ./gradlew :app:installDebug
   ```

6. 첫 실행 시 권한을 허용하고 경로 데이터(약 80MB)를 내려받습니다. 와이파이를 권장합니다.

## 테스트

```bash
./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest   # 단위 테스트 (PC)
./gradlew :routing-brouter:integrationTest                           # 실제 서울 경로 계산 테스트
./gradlew :app:connectedDebugAndroidTest                             # 기기 테스트 (Compose UI, Room, GPX 재생, 오프라인 지도)
```

기기 테스트는 실제 기기 또는 에뮬레이터가 필요합니다. 둘 중 하나를 고르세요.

> **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다. 실제 주행 기록이 있는 휴대폰에서는 돌리지 말고(`installDebug`만 사용), 테스트용 기기나 에뮬레이터에서 `ANDROID_SERIAL`로 지정해 실행합니다.

**방법 1: USB로 연결한 휴대폰 (기본)** — 개발자 옵션에서 USB 디버깅을 켜고 연결한 뒤 `adb devices`에 `device`로 표시되는지 확인합니다. 기기가 둘 이상이면 시리얼을 지정합니다.

```bash
adb devices
ANDROID_SERIAL=<시리얼> ./gradlew :app:connectedDebugAndroidTest
```

테스트 클래스 하나만 실행하려면 `connectedDebugAndroidTest`에는 `--tests`가 없으므로 인스트루먼테이션 인자를 씁니다.

```bash
ANDROID_SERIAL=<시리얼> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.bikerecoder.ui.settings.SettingsScreenTest
```

일부 테스트(오프라인 지도 등)는 인터넷이 필요합니다. 기기에 설치된 앱을 직접 지우거나 USB를 뽑으면 실행이 실패합니다.

**방법 2: 에뮬레이터 (선택)** — 시스템 이미지를 받고 AVD를 만들어 띄웁니다.

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-37.0;google_apis;x86_64"
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n bike37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_8
$ANDROID_HOME/emulator/emulator -avd bike37 -no-snapshot &
```

에뮬레이터가 뜨면 방법 1과 같은 `connectedDebugAndroidTest` 명령을 `ANDROID_SERIAL` 없이(기기가 하나일 때) 실행합니다.

자동 검증 결과와 실주행 체크리스트는 [docs/verification/navigation-field-test.md](docs/verification/navigation-field-test.md)에 기록합니다.

## 문서

- [내비게이션 설계 스펙](docs/superpowers/specs/2026-10-05-navigation-design.md)
- [버전 관리 규칙](docs/VERSIONING.md)
- [내비게이션 검증·실주행 체크리스트](docs/verification/navigation-field-test.md)
- [기록 코어 설계 스펙](docs/superpowers/specs/2026-10-12-recording-core-design.md)
- [기록 코어 검증·실주행 체크리스트](docs/verification/recording-field-test.md)
- [여행과 후처리 설계 스펙](docs/superpowers/specs/2026-10-18-trips-postprocess-design.md)
- [여행과 후처리 검증·실주행 체크리스트](docs/verification/trips-field-test.md)
- [영상과 요약 이미지 설계 스펙](docs/superpowers/specs/2026-10-28-video-image-design.md)
- [영상과 이미지 검증·실주행 체크리스트](docs/verification/video-field-test.md) (V1.3.1 절 포함)
- [영상 다듬기 설계 스펙 (V1.3.1)](docs/superpowers/specs/2026-10-28-video-polish-design.md)
- [요약·통계 탭 설계 스펙 (V1.4.0)](docs/superpowers/specs/2026-10-31-summary-stats-design.md)
- [요약·통계 검증·실사용 점검표](docs/verification/summary-stats-field-test.md)
- [활동 공유하기 설계 스펙 (V1.5.0)](docs/superpowers/specs/2026-10-31-share-activity-design.md)
- [활동 공유하기 검증·실사용 점검표](docs/verification/share-field-test.md)
- [GPX 가져오기 설계 스펙 (V1.6.0)](docs/superpowers/specs/2026-10-31-gpx-import-design.md)
- [GPX 가져오기 검증·실사용 점검표](docs/verification/gpx-import-field-test.md)
- [변경 이력](CHANGELOG.md)

## 라이선스와 출처 표기

- 앱 코드: MIT License ([LICENSE](LICENSE))

- 지도: OpenFreeMap © OpenMapTiles Data from OpenStreetMap
- 경로 탐색: BRouter © BRouter contributors (MIT License)
- 지도 데이터: © OpenStreetMap contributors (ODbL)
- 영상·요약 이미지의 위성 사진: © 브이월드
