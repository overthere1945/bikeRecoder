# bikeRecoder

자전거 주행을 기록하고, 자전거도로를 우선하는 길안내를 하며, 주행 경로를 사진과 MP4 영상으로 만들어 주는 개인용 안드로이드 앱입니다. 서버 없이 모든 처리를 휴대폰 안에서 합니다.

- 현재 버전: **V1.0.0 (개발 중)** — 버전 규칙: [docs/VERSIONING.md](docs/VERSIONING.md), 변경 이력: [CHANGELOG.md](CHANGELOG.md)
- 대상 기기: Samsung Galaxy S26 (Android 17), Galaxy Watch7 (심박, Health Connect 경유)

## 로드맵

| 버전 | 단계 | 주요 기능 | 상태 |
|---|---|---|---|
| V1.0.0 | 내비게이션 | 자전거도로 우선 오프라인 경로 탐색, 경유지, 음성 안내, 이탈 재탐색, 여러 날 여행 유지, 경로 주변 지도 오프라인 저장 | 설계 완료 |
| V1.1.0 | 기록 코어 | GPS 기록, 속도·고도·평균·최고 속도, 자동/수동 일시정지, 음성 안내(시작·1km·일시정지·재개) | 예정 |
| V1.2.0 | 여행과 후처리 | 여러 날 기록 묶기, 갤러리 사진 자동 매칭, Watch7 심박, 누적 경로 지도 | 예정 |
| V1.3.0 | 영상과 이미지 | 요약 이미지, Relive 스타일 3D 영상(속도 비례, 사진 삽입), 기기 내 인코딩 | 예정 |

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 / UI | Kotlin, Jetpack Compose |
| 지도 | MapLibre Native Android 13.6.1, OpenFreeMap 벡터 타일 |
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
3. 프로젝트 루트에 `secrets.json` 생성 (`secrets.example.json` 참고, Git에 올라가지 않음)

   ```json
   { "kakaoRestApiKey": "발급받은_REST_API_키" }
   ```

4. 빌드·설치

   ```bash
   ./gradlew :app:installDebug
   ```

5. 첫 실행 시 권한을 허용하고 경로 데이터(약 80MB)를 내려받습니다. 와이파이를 권장합니다.

## 테스트

```bash
./gradlew :core:test :routing-brouter:test          # 단위 테스트 (PC)
./gradlew :routing-brouter:integrationTest           # 실제 서울 경로 계산 테스트
./gradlew :app:connectedAndroidTest                  # 기기/에뮬레이터 GPX 재생 테스트
```

실주행 체크리스트는 `docs/verification/`에 기록합니다.

## 문서

- [내비게이션 설계 스펙](docs/superpowers/specs/2026-10-05-navigation-design.md)
- [버전 관리 규칙](docs/VERSIONING.md)
- [변경 이력](CHANGELOG.md)

## 라이선스와 출처 표기

- 지도: OpenFreeMap © OpenMapTiles Data from OpenStreetMap
- 경로 탐색: BRouter © BRouter contributors (MIT License)
- 지도 데이터: © OpenStreetMap contributors (ODbL)
