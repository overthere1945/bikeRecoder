# 기록 코어 V1.1.0 검증 기록

설계 스펙 §5(검증)와 §5.3(실주행 체크리스트), §5.4(완료 기준)에 따른 기록 문서입니다.

## 자동 검증

실행일: 2026-10-12 (브랜치 `feature/recording-v1.1.0`, 최종 리뷰 수정 반영 후)

### PC 단위 테스트

명령:

```bash
./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
```

결과: BUILD SUCCESSFUL (`--rerun-tasks`로 전부 다시 실행)

| 모음 | 클래스 수 | 테스트 수 | 실패 | 건너뜀 |
|---|---|---|---|---|
| `:core:test` | 21 | 176 | 0 | 0 |
| `:routing-brouter:test` | 4 | 16 | 0 | 0 |
| `:app:testDebugUnitTest` | 35 | 309 | 0 | 0 |
| 합계 | 60 | 501 | 0 | 0 |

빌드 확인: `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 성공(기기 테스트 코드 컴파일 포함).

### 기기 테스트 (Galaxy S22 Ultra (SM-S908N), Android 16 / API 36)

실행일: 2026-10-13. S25 Ultra(`R3CY903ZYQV`)가 연결되어 있지 않아, 사용자가 허락한 S22 Ultra(`R5CT43TFS9A`)에서 실행했습니다. 첫 전체 실행에서는 휴대폰이 잠겨 있어 화면 테스트 28개가 "No compose hierarchies found"로 실패했습니다. 잠금을 푼 뒤 화면 테스트만 다시 실행했고, `SettingsScreenTest`의 스크롤 누락(작은 화면에서 버튼이 화면 밖) 1건을 테스트 코드에서 고친 뒤 모두 통과했습니다.

아래 명령의 시리얼은 connected 테스트를 허락받은 S22 Ultra(`R5CT43TFS9A`)입니다. 아래 「주행 사용성 개선」 절의 경고를 꼭 읽습니다.

전체 실행:

```bash
ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest
```

클래스별 실행(전체 대신 하나씩 확인할 때). 모두 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스>` 형식입니다.

| 클래스 | 테스트 수 | 확인하는 것 | 결과 |
|---|---|---|---|
| `com.cowork.bikerecoder.ui.settings.SettingsScreenTest` | 6 | 설정 화면(기록 설정 두 가지 포함) | 6/6 통과 |
| `com.cowork.bikerecoder.data.RoomRideStoreTest` | 7 | 구간·위치 저장, 날짜 합계, 빈 구간은 목록에서 제외, 삭제 CASCADE | 7/7 통과 |
| `com.cowork.bikerecoder.data.RideDatabaseMigrationTest` | 1 | DB v1 → v2 후 기존 여행 유지 (스펙 ⑥) | 1/1 통과 |
| `com.cowork.bikerecoder.data.RoomTripStoreTest` | 6 | 여행 저장소(DB v2에서 재확인) | 6/6 통과 |
| `com.cowork.bikerecoder.nav.NavigationScenarioTest` | 6 | 안내 GPX 시나리오, 공유 위치 스트림·옮긴 도우미 위에서 다시 실행 | 6/6 통과 |
| `com.cowork.bikerecoder.ui.record.RecordScreenTest` | 10 | 기록 화면 2쪽, 자동 일시정지 칩, 정지 확인, 쪽 점 48dp, 서비스 경고 | 10/10 통과 |
| `com.cowork.bikerecoder.ui.trip.EndNavigationDialogTest` | 3 | 안내 종료 대화상자(기록과 함께 끝내기) | 3/3 통과 |
| `com.cowork.bikerecoder.ui.history.HistoryScreenTest` | 3 | 목록 → 상세, 길게 눌러 삭제 | 3/3 통과 |
| `com.cowork.bikerecoder.ride.RideScenarioTest` | 5 | GPX 시나리오 ①~⑤ (아래) | 5/5 통과 |

`RideScenarioTest` 시나리오(20배속 재생, in-memory Room): ① 기록만 3km(시작 1회, 1km 문장 3회, 저장 거리 3,010m ±1) ② 신호 대기 2회(일시정지·재개 각 2회, 이동 시간 < 경과 시간, 거리 1,470m ±1) ③ 안내+기록(합친 문장 km 1·2, 안내만의 1km 문장 없음) ④ 강제 종료 후 [이어서 기록](구간 2개, 앞 구간 닫힘) ⑤ 도착(안내·기록 함께 종료, 거리 2,370m ±15, 여행 완료). 스펙 ⑥(마이그레이션)은 `RideDatabaseMigrationTest`입니다.

이 외에 같은 명령으로 이미 있던 클래스(`GpxLocationSourceTest`, `MapLibreOfflineControllerTest`, `PlanScreenTest`, `SettingsSegmentActionsTest`)도 전체 실행에 포함됩니다. 기기 전체 테스트 수는 61개입니다.

| 기기 전체 실행 | 테스트 수 | 실패 | 결과 |
|---|---|---|---|
| `:app:connectedDebugAndroidTest` | 61 | 0 | 61/61 통과 (화면 테스트 28개는 잠금 해제 후 재실행) |

### 수동 확인 (기기 검증 대기)

휴대폰에 설치한 앱에서 직접 확인하고 결과를 표시합니다.

| 확인 | 방법 | 결과 |
|---|---|---|
| 기록 흐름 | 지도의 [기록 시작] → 기록 화면 2쪽(지도/요약) 넘기기 → 일시정지·재개 → [종료] → 구간 요약. 기록 화면 캡처 1장을 남깁니다 | [ ] 미실시 |
| 안내 화면의 기록 | 안내 시작 시 기록 자동 시작(지도가 다시 그려지지 않고 그대로 이어지는지), 안내 화면 하단 페이저 넘기기, 안내 종료 대화상자에서 기록과 함께/안내만 종료, 요약 화면, 저장 실패 안내문 | [ ] 미실시 |
| 안내 중 기록 조작 | 안내 화면 숫자 쪽의 [일시정지]/[재개], [자동 일시정지 ON/OFF], [기록 끝내기](안내는 계속, 요약은 안내가 끝날 때 또는 메인 화면에서) | [ ] 미실시 |
| 음소거 | 안내 중 [음소거] → [안내만 끝내기] 뒤 기록의 1km·일시정지 문장이 다시 들리는지 | [ ] 미실시 |
| 기록 목록 | 기록 목록 → 여행 상세 → 구간 상세, 고도·속도 그래프가 코스와 맞는지 | [ ] 미실시 |
| 삭제 | 목록·상세에서 길게 눌러 삭제, 확인 후 사라지는지 | [ ] 미실시 |

## 실주행 체크리스트

### 기기 정보

| 항목 | 내용 |
|---|---|
| 기기 | ______ (모델명: ______ ) |
| Android 버전 | ______ (스펙 기준 Android 17) |
| 앱 버전 | V1.1.0 (설정 화면 맨 아래 표기와 같은지 확인: ______ ) |
| 비교 기기 | Galaxy Watch7 + 삼성 헬스 (항목 1, 4) |
| 실주행 날짜 | ______ |
| 날씨·네트워크 | ______ |

### 준비

1. 카카오 REST API 키를 프로젝트 루트 `secrets.json`에 넣습니다: `{ "kakaoRestApiKey": "..." }` (Git에 올라가지 않음).
2. 승인된 휴대폰에 USB 디버깅으로 연결하고 설치합니다.

   ```bash
   adb devices
   ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug
   ```

3. 앱을 처음 실행했다면 권한(정밀 위치, 백그라운드 위치, 알림, 배터리 최적화 제외)을 허용하고 경로 데이터를 내려받습니다(항목 6·7, 안내를 쓰는 주행).
4. 설정 화면에서 다음을 확인합니다.
   - **안내 시작 시 기록 자동 시작**: 켜짐 (항목 7, 항목 6 안내 구간). 안내 없이 기록만 하는 항목에는 영향이 없습니다.
   - **자동 일시정지**: 켜짐 (항목 2). 항목 3은 기록 화면에서 이 칩을 끄고 시작합니다.
5. 항목 1과 4는 같은 코스를 **삼성 헬스(Galaxy Watch7)** 로도 함께 기록해, 주행 뒤 두 앱의 거리·누적 상승을 비교합니다. 두 기록의 시작·종료 지점을 같게 맞춥니다.

### 측정 방법

- **거리 (항목 1)**: 앱 구간 요약의 거리와 삼성 헬스의 거리를 비교해 차이를 %로 적습니다. 합격 기준은 ±3%입니다.
- **이동 시간 (항목 2)**: 신호마다 일시정지·재개 안내가 나왔는지 세고, 구간 요약의 이동 시간을 스톱워치로 잰 실제 이동 시간과 비교합니다.
- **누적 상승 (항목 4)**: 앱 구간 요약의 누적 상승과 삼성 헬스를 비교해 차이를 %로 적습니다. 합격 기준은 ±15%입니다.
- **최고 속도 (항목 5)**: 구간 요약의 최고 속도가 실제 내리막 체감 속도와 비슷한지 보고, 70km/h 이상 값이 나왔다면 구간 상세의 속도 그래프에서 튄 곳을 확인합니다.
- **배터리 (항목 6)**: 시작 직전의 배터리 %와 종료 직후의 %를 메모하고 시간(분)으로 시간당 소모를 계산합니다. 화면은 끈 상태로 둡니다.
- **1km 안내 (항목 6·7)**: 1km마다 한 문장이 한 번씩 들리는지 세고, 안내 중에는 남은 거리·도착 예정까지 한 문장인지 확인합니다.
- **다음 날 이어서 (항목 8)**: 첫날 [오늘은 여기까지]로 마친 여행을 다음 날 열어 [이어서 기록]/[이어서 안내]를 누르고, 기록 목록에서 날짜별로 나뉘어 보이는지 확인합니다.
- **강제 종료 (항목 10)**: 기록 중 최근 앱에서 밀어서 종료하고 앱을 다시 열어 [이어서 기록]을 누릅니다. 같은 여행의 새 구간이 시작되고 앞 구간은 마지막 저장 지점까지 남는지 봅니다.
- **페이지 넘기기**: 지도 위 끌기는 지도가 받으므로 기록·안내 화면의 페이지 넘기기는 하단 요약 영역에서 합니다. 지도에서 끌었을 때 페이지가 넘어가지 않고, 하단 요약에서 좌우로 밀면 넘어가는지 함께 확인하고 메모에 적습니다.

### 항목

결과 칸 표기: `[ ]` 미실시(아직 하지 않음), `[x]` 합격, `[!]` 불합격. 불합격이면 메모에 원인을 적습니다.

| # | 항목 | 합격 기준 | 결과 (`[ ]` 미실시 / `[x]` 합격 / `[!]` 불합격) | 메모 |
|---|---|---|---|---|
| 1 | 기록만 30분 | 거리 삼성 헬스(Watch7) 대비 ±3% | [ ] 미실시 | 앱 ___km / 삼성 헬스 ___km / 차이 ___% |
| 2 | 신호 많은 도심 | 신호마다 일시정지·재개 안내, 이동 시간 실제와 일치 | [ ] 미실시 | 신호 ___회 / 안내 일시정지 ___회·재개 ___회 / 이동 시간 앱 ___ 실제 ___ |
| 3 | 수동 모드 | 자동 일시정지 없음, 버튼으로만 | [ ] 미실시 | |
| 4 | 오르막 코스 | 누적 상승 삼성 헬스 대비 ±15% | [ ] 미실시 | 앱 ___m / 삼성 헬스 ___m / 차이 ___% |
| 5 | 최고 속도 | 실제와 비슷, 70km/h 이상 터무니없는 값 없음 | [ ] 미실시 | 앱 최고 ___km/h / 체감 ___ |
| 6 | 화면 꺼짐 1시간 | 1km 안내 모두 들림, 배터리 시간당 12% 이하(안내+기록) | [ ] 미실시 | 시작 ___% / 종료 ___% / ___분 / 시간당 ___% / 1km 안내 ___회 중 ___회 |
| 7 | 안내와 함께 | 1km 안내 한 문장 1회 | [ ] 미실시 | |
| 8 | 여러 날 이어서 | 다음 날 같은 여행에 이어짐, 목록에서 날짜별 표시 | [ ] 미실시 | |
| 9 | 구간 상세 | 지도·고도·속도 그래프가 실제 코스와 일치 | [ ] 미실시 | |
| 10 | 강제 종료 | 재실행 후 [이어서 기록]으로 계속 | [ ] 미실시 | |

### 판정

- 스펙 §5.4 완료 기준: 5.1(PC 테스트)·5.2(휴대폰 GPX 테스트)가 통과하고 5.3의 전 항목이 합격이면 `CHANGELOG.md`의 V1.1.0을 확정하고(「개발 중」 표기 제거) 태그 `v1.1.0`을 붙입니다.
- 불합격 항목은 원인 수정과 회귀 테스트 추가 뒤 해당 항목만 다시 확인하고, 여기에 재검증 결과를 덧붙입니다.

## 주행 사용성 개선

설계 스펙 `docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md` §6(검증)과 §6.3(실주행 확인), 이번 판정 U1에 따른 기록입니다. 즐겨찾기, 검색 화면 키보드, 안내 중 [안내 취소 · 기록 계속]·[주행 종료], 기록 중 새 안내(이어서/나누기/묻기), 경로 계획 길게 누르기를 다룹니다.

### 자동 검증

실행일: 2026-10-18 (브랜치 `feature/recording-v1.1.0`)

#### PC 단위 테스트

명령:

```bash
./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
```

결과: BUILD SUCCESSFUL (`--rerun-tasks`로 전부 다시 실행, 101개 작업 모두 실행). 수는 `build/test-results`의 XML에서 모음별로 센 값입니다.

| 모음 | 클래스 수 | 테스트 수 | 실패 | 건너뜀 |
|---|---|---|---|---|
| `:core:test` | 21 | 177 | 0 | 0 |
| `:routing-brouter:test` | 4 | 16 | 0 | 0 |
| `:app:testDebugUnitTest` | 40 | 380 | 0 | 0 |
| 합계 | 65 | 573 | 0 | 0 |

앞선 기록 코어 검증(2026-10-12)의 501개(60클래스)에서 72개(5클래스)가 늘었습니다(`:core` +1, `:app` +71).

새 클래스:

| 클래스 | 테스트 수 | 확인하는 것 |
|---|---|---|
| `favorite.FavoriteRepositoryTest` | 10 | 즐겨찾기 규칙(집·회사 각 1개, 직접 입력 이름 1~20자, 같은 이름 거절, 최대 20개, 정렬) |
| `data.FavoriteMappingsTest` | 1 | 즐겨찾기 Room 엔티티 ↔ 모델 변환 |
| `ui.common.PlaceLookupTest` | 5 | 길게 누른 지점의 주소 찾기("주소를 확인하는 중…") |
| `ui.favorite.FavoritesViewModelTest` | 8 | 칩 목록, 저장·바꾸기·삭제 흐름과 오류 문구 |
| `ui.navigate.GuidanceButtonsTest` | 2 | 기록 중 [안내 취소 · 기록 계속]·[주행 종료] 버튼 구성 |

늘어난 클래스(이번 작업에서 테스트를 더한 클래스, 모두 통과):

| 클래스 | 테스트 수(이전 → 현재) | 늘어난 내용 |
|---|---|---|
| `ride.RideControllerTest` | 20 → 36 (+16) | 구간 나누기(자동 일시정지 칩 유지 포함), 화면 알림(시작이 실패하면 지움), 기록 중 시작한 안내의 도착 처리(U1) |
| `ride.RideNavigationTest` | 13 → 15 (+2) | 기록이 먼저 돌던 여행에 안내가 붙어도 같은 구간 유지, 다시 안내한 도착은 안내만 끝내고 기록 계속(U1) |
| `nav.NavigationControllerTest` | 24 → 27 (+3) | 기록 중 시작한 안내의 도착은 안내만 끝냄(음소거 중에도 화면 문구, 음소거는 풀림), 기록이 뒤에 시작됐거나 먼저 끝났으면 둘 다 완료(U1) |
| `ui.trip.TripStartViewModelTest` | 23 → 36 (+13) | 새 안내의 기록 선택(이어서/나누기/묻기). 동작이 의도적으로 바뀐 기존 6개를 고침 |
| `ui.plan.PlanViewModelTest` | 25 → 29 (+4) | 길게 눌러 경유지·목적지·즐겨찾기, 즐겨찾기 칩 선택 |
| `ui.navigate.NavigateViewModelTest` | 24 → 28 (+4) | 안내 취소 · 기록 계속, 주행 종료, 도착 문구 |
| `ui.record.RecordViewModelTest` | 6 → 8 (+2) | 기록 화면은 기록 중에만 알림을 받음, 구간 나누기 알림 표시 |
| `ui.settings.SettingsViewModelTest` | 12 → 13 (+1) | 「안내를 새로 시작할 때 기록」 설정 |
| `core.voice.KoreanPhrasesTest` | 9 → 10 (+1) | "안내를 마칩니다. 기록은 계속됩니다"·"목적지에 도착했습니다" 문구 |

빌드 확인: `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 성공(기기 테스트 코드 컴파일 포함). Room 스키마 `app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json`이 커밋되어 있습니다.

#### 기기 테스트

실행일: 2026-10-18. `adb devices`에 연결된 휴대폰이 없어(목록이 비어 있음) 기기 실행은 **기기 검증 대기**입니다. 아래 표의 결과는 모두 대기이고, 테스트 수는 코드의 `@Test` 개수입니다.

> **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다.
> connected 테스트는 `R5CT43TFS9A`에서만, 실제 기록이 있는 `R3CY903ZYQV`에는 `installDebug`만 씁니다.

시리얼 없이 실행하면 연결된 모든 휴대폰에서 돌고 앱을 지우므로 항상 `ANDROID_SERIAL=R5CT43TFS9A`를 붙입니다. 휴대폰 잠금을 푼 상태에서 실행합니다(잠겨 있으면 화면 테스트가 "No compose hierarchies found"로 실패). 한 번에 클래스 하나씩 실행합니다(쉼표 목록은 첫 클래스만 돕니다).

```bash
ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스>
```

| 클래스 | 테스트 수 | 확인하는 것 | 결과 |
|---|---|---|---|
| `com.cowork.bikerecoder.data.RideDatabaseMigrationTest` | 2 | DB v1 → v2, v2 → v3 후 기존 여행 유지 | 기기 검증 대기 |
| `com.cowork.bikerecoder.data.RoomFavoriteStoreTest` | 1 | 즐겨찾기 저장소 추가·관찰·수정·삭제 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.plan.PlanScreenTest` | 4 | 경유지 추가·순서·삭제, 길게 눌러 경유지로 추가·목적지로 설정 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.favorite.FavoriteChipsTest` | 4 | 칩 순서·선택, 직접 입력 칩 메뉴(이름 바꾸기·삭제), 집 칩 메뉴(삭제만, U4) | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.favorite.FavoriteDialogsTest` | 4 | 이름 고르기(집·회사·직접 입력), 오류 문구, 삭제 확인 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.search.SearchScreenTest` | 3 | 검색 결과가 키보드 위에서 끝남, 목록을 끌면 키보드 내림, 즐겨찾기가 없으면 여백 없음 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.settings.SettingsScreenTest` | 7 | 설정 화면, 「안내를 새로 시작할 때 기록」 선택지 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.trip.TripStartDialogsTest` | 1 | "기록을 어떻게 할까요?" 대화상자(이어서 기록·새 구간으로 나누기·취소) | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.navigate.GuidanceButtonRowTest` | 3 | 기록 중 [안내 취소 · 기록 계속]·[주행 종료], 기록 중이 아니면 V1 버튼 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.common.NoticeSnackbarHostTest` | 3 | 알림 문구를 한 번 보이고 표시했다고 알림, 새 알림은 다시 보임 | 기기 검증 대기 |
| `com.cowork.bikerecoder.ui.record.RecordScreenTest` | 12 | 기록 화면 2쪽·정지 확인·서비스 경고, [목적지 정하기] 표시(안내 중에는 숨김) | 기기 검증 대기 |

위 11개 클래스의 합은 44개입니다. 기기 전체 `@Test` 개수는 86개(20개 클래스)이며 아직 실행하지 않았습니다.

#### 설치 확인 (기기 검증 대기)

DB가 v2에서 v3으로 올라가도 기존 기록이 남는지 실제 휴대폰에서 봅니다. 기록이 있는 `R3CY903ZYQV`에는 설치만 합니다.

```bash
ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug
```

설치 뒤 앱을 열어 기록 목록이 설치 전과 그대로인지(실제 v2 → v3 마이그레이션) 확인합니다. 결과: 기기 검증 대기.

### 실주행 체크리스트 (주행 사용성 개선)

기기 정보·준비·결과 칸 표기는 아래 「실주행 체크리스트」와 같습니다. 설정 화면의 「안내를 새로 시작할 때 기록」은 항목 3에서 세 가지를 모두 바꿔 가며 확인합니다.

| # | 항목 | 합격 기준 | 결과 (`[ ]` 미실시 / `[x]` 합격 / `[!]` 불합격) | 메모 |
|---|---|---|---|---|
| 1 | 집 즐겨찾기로 안내 시작 | 집 칩을 누르면 목적지가 바로 정해지고 안내 시작이 바로 됨 | [ ] 미실시 | |
| 2 | 안내 취소 · 기록 계속 | 안내 중 [안내 취소 · 기록 계속] 뒤 기록이 끊기지 않음. 음성과 화면 문구 "안내를 마칩니다. 기록은 계속됩니다" | [ ] 미실시 | |
| 3 | 기록 중 새 목적지로 안내 시작 | 설정대로(이어서/나누기/묻기) 동작하고, 기록 목록에 같은 여행으로 보임 | [ ] 미실시 | 이어서 ___ / 나누기 ___ / 묻기 ___ |
| 4 | 경로 계획에서 길게 눌러 경유지 추가 | 경로 계획 화면에서 지도를 길게 누르면 [경유지로 추가]로 경유지가 더해짐 | [ ] 미실시 | |
| 5 | 기록 중 붙인 안내로 도착 (U1) | 안내만 끝나고 기록은 계속됨(음성과 화면 문구 "목적지에 도착했습니다"). 여행은 진행 중으로 남음 | [ ] 미실시 | |
