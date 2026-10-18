## Global Constraints

- 버전은 그대로 `1.1.0`(versionCode 10100, debug `1.1.0-dev`, 화면 `V1.1.0`). 이 작업은 V1.1.0에 포함된다
- 패키지: 신규 `com.cowork.bikerecoder.favorite`(모델·규칙·저장소 인터페이스), `ui.favorite`(칩·대화상자·뷰모델). 공통 부품은 `ui.common`. 기존 패키지 구조 유지
- minSdk 30, targetSdk/compileSdk 37, JVM 17, MapLibre는 `AndroidView`로만·메인 스레드에서만, 의존성 주입 라이브러리 금지(`AppContainer` 수동 구성), 차트 라이브러리 금지
- 모든 사용자 노출 문구는 한국어, 스펙 문구 그대로: "🏠 집" / "🏢 회사" / "★ {이름}" / "즐겨찾기에 저장" / "즐겨찾기에서 삭제할까요?" / "기존 집을 이 장소로 바꿀까요?" / "같은 이름의 즐겨찾기가 있습니다" / "즐겨찾기는 20개까지 저장할 수 있습니다" / "이름 바꾸기" / "삭제" / "직접 입력" / "안내 취소 · 기록 계속" / "주행 종료" / "안내를 마칩니다. 기록은 계속됩니다" / "목적지 정하기" / "안내를 새로 시작할 때 기록" / "자동으로 이어서 기록" / "자동으로 새 구간 나누기" / "매번 물어보기" / "기록을 어떻게 할까요?" / "이어서 기록" / "새 구간으로 나누기" / "취소" / "새 구간으로 기록을 시작합니다" / "경유지로 추가" / "목적지로 설정" / "주소를 확인하는 중…" / "목적지에 도착했습니다"(U1 화면 문구, 기존 `KoreanPhrases.arrivedDestination`)
- 수치(스펙 §1): 직접 입력 이름 1~20자(앞뒤 공백 제외), 즐겨찾기 최대 20개(집·회사 포함), 집·회사 각 1개, 정렬 집 → 회사 → 직접 입력(생성 순). 스낵바 약 4초 = `SnackbarDuration.Short`
- 스레드(V1.0.0 R12, 기록 코어): `RideController`·`NavigationController`의 공개 메서드와 세션은 `AppContainer.rideScope`(`Dispatchers.Main.immediate`) 한 스레드에서만. 구간 나누기의 위치 다리(bridge) 수집도 그 스코프에서. GPS 감시는 tick 시각만(R11)
- 기록 코어 판정 유지: P1~P4(재기준·터널·배너, closeInterrupted, 자정 구간, `replay = 1`), F1(음소거는 안내 실행 하나의 것 — 안내가 끝나면 풀림), F2(안내 숫자 쪽의 기록 조작)
- 이번 판정(사전 점검 후 확정): **U1** 기록 중에 시작한 안내(시작할 때 이미 기록이 돌고 있던 안내)가 목적지에 도착하면 안내만 끝난다 — 기록은 계속, 여행은 ACTIVE 그대로, 음성과 화면 문구 "목적지에 도착했습니다". 안내가 먼저 시작해 기록이 따라 시작한 경우는 V1.1 규칙 그대로(도착하면 둘 다 끝나고 여행 완료). **U2** `endForAnotherTrip`은 질문 대화상자가 열린 사이에 시작된 기록에만 쓰이는 안전망. **U3** connected 테스트는 `R5CT43TFS9A`에서만, `R3CY903ZYQV`에는 절대 돌리지 않는다. **U4** 집·회사 칩 메뉴는 [삭제]만, 직접 입력 칩은 [이름 바꾸기] [삭제](스펙 §1.1 수정됨)
- 새 화면 알림 클래스 이름은 `ScreenNotice`(기존 `nav.RideNotice(title, text)` 알림 내용과 헷갈리지 않게)
- Room: DB 버전 3, `exportSchema = true`, `autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]`(2→3은 테이블 추가만), 빌드가 만드는 `app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json`을 커밋한다
- 설정 기본값: `recordingOnNewGuidance = GuidanceRecording.ASK`("매번 물어보기"). 기존 `autoStartRecordingWithNavigation = true`, `autoPauseEnabled = true` 그대로
- 테스트: JVM은 JUnit5(`org.junit.jupiter`), 기기는 JUnit4 + `androidx.compose.ui.test.junit4.v2.createComposeRule`(IME 테스트만 `createAndroidComposeRule<ComponentActivity>()`). core `./gradlew :core:test --tests '<FQCN>'`, app JVM `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`
- 기기 테스트(사용자 허락, 2026-10-18): 먼저 `adb devices`. **`connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 주행 기록이 사라지므로 S22 Ultra `R5CT43TFS9A`에서만 실행한다**: `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`(한 번에 클래스 하나 — 쉼표 목록은 첫 클래스만 돈다).
  S25 Ultra `R3CY903ZYQV`(사용자의 실제 기록)에는 `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug`만 허용. 각 Task의 기기 단계는 `ANDROID_SERIAL=R5CT43TFS9A`를 붙인 전체 명령을 그대로 쓴다(시리얼 없이 돌리면 연결된 모든 휴대폰에서 돌고 앱을 지운다). `R5CT43TFS9A`가 없으면 `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 컴파일만 확인하고 기기 실행을 "기기 검증 대기"로 남긴다. Compose UI 테스트는 휴대폰 잠금을 푼 상태에서, 화면 밖일 수 있는 항목은 `performScrollTo()` 뒤에 확인한다. AVD를 만들지 않는다
- 기존 테스트는 모두 그대로 통과해야 한다. 동작이 의도적으로 바뀌어 고치는 테스트는 그 Task가 이름을 밝힌다(Task 7의 `TripStartViewModelTest` 6개뿐)
- 커밋 끝에 `Co-Authored-By:` 트레일러로 커밋을 쓴 모델을 밝힌다(R10). 각 Task의 Commit 단계는 `git commit -m "<제목>" -m "Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>"` 형식이다


# This plan's additions

- `docs/superpowers/plans/2026-10-18-ride-ux-improvements.md`의 Global Constraints가 모두 그대로 적용된다(버전 1.1.0, 스레드 규칙, 판정 U1~U4, 기기 규칙 U3, 테스트 프레임워크, 커밋 트레일러). 특히 **connected 테스트는 `ANDROID_SERIAL=R5CT43TFS9A`로만, `R3CY903ZYQV`에는 절대 돌리지 않는다**. 기기가 없으면 `:app:assembleDebugAndroidTest`로 컴파일만 하고 "기기 검증 대기".
- 문구(스펙 §7 그대로): "안내 취소" / "최근 검색" / "전체 삭제" / "🏠 집 등록" / "🏢 회사 등록" / "집 검색" / "회사 검색" / "집으로 저장했습니다" / "회사로 저장했습니다". 기존 "안내를 마칩니다. 기록은 계속됩니다"·"기존 집을 이 장소로 바꿀까요?"는 그대로.
- 수치: 최근 검색 최대 10개, 같은 장소 = 이름이 같고 좌표 거리 ≤ 10 m.
- 동작이 바뀌어 고치는 기존 테스트는 Task가 이름을 밝힌다: Task 1의 `GuidanceButtonsTest`·`GuidanceButtonRowTest`(문구), `KakaoLocalClientTest`(정렬 인자). Task 3의 `FavoriteChipsTest`의 "없으면 줄 없음" 단언(→ 등록 칩 단언).


# Controller rulings in force (this plan)
- V1: the guidance button label "안내 취소 · 기록 계속" in the copy list above is REPLACED by "안내 취소" (spec §7.1). All other copy stays.
- U1–U4 unchanged. Commit trailer names the model that wrote the commit.
- V2 (Task 3): a refused register-mode save shows `FavoriteDialog.Message(text)` with a single [확인] button (same pattern as HistoryDialog.Message / TripStartState.Error). "확인" is accepted copy.

# This plan's additions (2026-10-18-favorites-and-map-pick)

- `docs/superpowers/plans/2026-10-18-ride-ux-improvements.md`의 Global Constraints와 `2026-10-18-search-favorites-polish.md`의 판정(V1 [안내 취소], V2 [확인] 메시지)이 그대로 적용된다(버전 1.1.0, 스레드 규칙, U1~U4, 테스트 프레임워크, 커밋 트레일러).
- **기기 규칙(이번 작업, 사용자 지시 2026-10-18):** 기기 검증과 설치는 **S22 `R5CT43TFS9A`에서만**. connected 테스트는 `ANDROID_SERIAL=R5CT43TFS9A`로 한 번에 클래스 하나, `</dev/null`을 붙여 실행. **S25 `R3CY903ZYQV`에는 이번 작업에서 아무것도 하지 않는다**(테스트·설치 모두). 실행 전 `adb devices`에 `R5CT43TFS9A`가 있는지 확인하고 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`. 휴대폰 설정은 바꾸지 않는다. 없거나 잠겨 있으면 컴파일만 하고 "기기 검증 대기".
- 문구(스펙 §8 그대로): "★ 즐겨찾기" / "즐겨찾기" / "＋ 즐겨찾기 추가" / "등록한 즐겨찾기가 없습니다" / "즐겨찾기 검색" / "즐겨찾기에 저장했습니다" / "📍 현재 위치" / "🗺 지도에서 선택" / "현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요" / "위치 오차가 큽니다(약 {n}m)" / "현재 위치를 집으로 저장할까요?" / "현재 위치를 회사로 저장할까요?" / "저장" / "취소" / "즐겨찾기 이름" / "추천 이름" / "지도에서 선택" / "이 위치로 설정" / "주소를 확인하는 중…" / "지도에서 선택한 위치". 기존 문구("이름 바꾸기", "삭제", "즐겨찾기는 20개까지 저장할 수 있습니다", "같은 이름의 즐겨찾기가 있습니다", "이름은 1~20자로 입력하세요", "집으로 저장했습니다", "회사로 저장했습니다", "집 검색", "회사 검색")는 그대로.
- 수치: 위치 오차 경고 > 50 m, 근처 장소 반경 50 m, 추천 장소 이름 최대 5개(가까운 순, 같은 이름 하나), 지도에서 선택 주소 조회는 카메라가 멈춘 뒤(`onCameraIdle`) 300 ms 디바운스, 이전 조회는 취소.
- 카카오 카테고리 코드(스펙 §8.4): `SW8, CE7, FD6, CS2, MT1, BK9, PO3, AT4, CT1, HP8` — 코드마다 한 요청(`v2/local/search/category.json`, `x`, `y`, `radius=50`, `sort=distance`, `size=5`), 동시에 보내고 합친다. 하나가 실패해도 나머지로. 모두 실패하면 빈 목록(이름 입력은 계속 가능).
- 동작이 바뀌어 고치는 기존 테스트는 Task가 이름을 밝힌다: Task 1의 `FavoriteChipsTest`(직접 입력 칩 → [★ 즐겨찾기]), 관련 `SearchScreenTest`/`SearchRegisterModeTest` 칩 단언.

