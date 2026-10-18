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
- 기기 테스트(사용자 허락, 2026-10-18): 먼저 `adb devices`. **`connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 주행 기록이 사라지므로 S22 Ultra `R5CT43TFS9A`에서만 실행한다**: `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`(한 번에 클래스 하나 — 쉼표 목록은 첫 클래스만 돈다). S25 Ultra `R3CY903ZYQV`(사용자의 실제 기록)에는 `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug`만 허용. 각 Task의 기기 단계는 `ANDROID_SERIAL=R5CT43TFS9A`를 붙인 전체 명령을 그대로 쓴다(시리얼 없이 돌리면 연결된 모든 휴대폰에서 돌고 앱을 지운다). `R5CT43TFS9A`가 없으면 `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 컴파일만 확인하고 기기 실행을 "기기 검증 대기"로 남긴다. Compose UI 테스트는 휴대폰 잠금을 푼 상태에서, 화면 밖일 수 있는 항목은 `performScrollTo()` 뒤에 확인한다. AVD를 만들지 않는다
- 기존 테스트는 모두 그대로 통과해야 한다. 동작이 의도적으로 바뀌어 고치는 테스트는 그 Task가 이름을 밝힌다(Task 7의 `TripStartViewModelTest` 6개뿐)
- 커밋 끝에 `Co-Authored-By:` 트레일러로 커밋을 쓴 모델을 밝힌다(R10). 각 Task의 Commit 단계는 `git commit -m "<제목>" -m "Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>"` 형식이다


# Controller rulings in force
- U1–U4 are in the Global Constraints above.
- Commit trailer names the model that wrote the commit.
