## Global Constraints

- 패키지: core 신규 `com.cowork.bikerecoder.core.recording`, app 신규 `com.cowork.bikerecoder.ride`(컨트롤러), `ui.record`, `ui.history`. 기존 패키지 구조 유지
- 버전: `versionName = "1.1.0"`, `versionCode = 10100`, debug `versionNameSuffix = "-dev"` → `1.1.0-dev`, 화면 표기 `V1.1.0` (Task 1에서 올린다)
- minSdk 30, targetSdk/compileSdk 37, JVM 17, MapLibre 13.6.1(공식 Compose 래퍼 없이 `AndroidView`), 의존성 주입 라이브러리 금지(`AppContainer` 수동 구성), 차트 라이브러리 금지(Compose `Canvas`)
- 모든 사용자 노출 문구는 한국어. 음성 문구는 스펙 §4.5 그대로: "기록을 시작합니다" / "일시정지되었습니다" / "기록을 다시 시작합니다" / "{n}킬로미터 이동. 평균 속도 {x.x}킬로미터, 이동 시간 {시간}입니다." / "{n}킬로미터 이동. 평균 속도 {x.x}킬로미터, 이동 시간 {시간}. 남은 거리 {거리}, 도착 예정 {시각}입니다." ({시간}: 1시간 미만 "{m}분", 이상 "{h}시간 {m}분")
- 수치(스펙 §4): 정확도 > 30m 제외 · 직전 채택 위치 대비 구간 속도 > 80km/h 제외 / 일시정지: 속도 < 3km/h 5초 연속 / 재개: 속도 ≥ 5km/h 2초 연속 또는 정지 지점에서 ≥ 15m / 현재 속도 약 3초 이동 평균 · 최고 = 3초 창 중간값들의 최댓값 / 기압 고도 `44330·(1−(p/1013.25)^(1/5.255))`(= `SensorManager.getAltitude(1013.25, p)`), 5초 평균, 수직 정확도 ≤ 10m GPS와의 차이 중간값으로 오프셋(처음 60초, 이후 5분마다 EMA 0.1) / 기압계 없음: GPS 고도 중간값 필터(5) + 평활 / 누적 상승·하강 기준점 대비 ≥ 3m(기압) / ≥ 5m(GPS) / GPS 없음 10초 / 위치 5초마다 일괄 저장 / km 기준 Asia/Seoul 날짜
- 스레드: `RecordingSession`과 `NavigationSession`은 같은 단일 스레드 스코프(`Dispatchers.Main.immediate`, AppContainer의 `rideScope`)에서만 호출한다. 위치·기압·tick 수집은 컨트롤러가 그 스코프 안에서 하며 UI 생명주기 수집기에 맡기지 않는다(V1.0.0 R12)
- GPS 감시는 tick 시각만 쓴다(V1.0.0 R11): 위치가 오면 "봤음"만 표시, `onTick(now)`이 마지막 활동 후 10초를 판정. 이동 시간·자동 일시정지는 `fix.timeMillis` 차이, 경과 시간·날짜·고도 보정 창은 컨트롤러 `clock()`(tick 시각)으로 계산한다
- MapLibre 호출은 메인 스레드에서만
- 테스트: JUnit5 모듈은 `testRuntimeOnly(junit-platform-launcher)`(이미 있음). core는 `./gradlew :core:test --tests '<FQCN>'`, app JVM은 `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`
- 기기 테스트: USB 휴대폰 SM-S938N(Android 16, serial R3CY903ZYQV)에서 `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>` (`--tests` 없음). AVD를 만들지 않는다. 휴대폰이 빠져 있으면 그 Task는 JVM 테스트 + `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 끝내고 기기 실행을 "기기 검증 대기"로 남긴다(모든 Task는 JVM으로 확인 가능한 부분을 가진다). 기기 테스트는 in-memory DB를 쓰고 만든 파일·DB를 지운다
- 테스트 좌표는 `GeoMath`(R = 6_371_008.8)와 같은 반경으로 만든다: 북쪽 직진 `lat = 37.5 + Math.toDegrees(m / 6_371_008.8)`(경선 위라 haversine 거리 = m, V1.0.0 R9). 기록 테스트는 `TestRoutes.pointAt`을 쓰지 않는다(Task 7은 안내 테스트라 기존 `TestRoutes.straightRoute`를 그대로 쓰며, 거리는 가짜 소스가 정하므로 영향이 없다). 경계값 시험은 부동소수 오차를 피해 경계 ±0.1m로 한다
- Room: DB 버전 2, `exportSchema = true`, `app/schemas/.../2.json`을 커밋한다. 1→2는 테이블 추가만(`AutoMigration(from = 1, to = 2)`)
- 설정 기본값: `autoStartRecordingWithNavigation = true`, `autoPauseEnabled = true`
- 컨트롤러 판정(사전 점검 후 확정): **P1** 스펙 밖 확장을 받아들인다 — 연속 5번 속도로 버려지면 재기준, 10초 넘는 공백은 3km/h 이상이면 이어 붙이고(터널) 아니면 빼기, 안내만 할 때 배너 "● 주행 중 · 안내 중". **P2** `closeInterrupted`는 구간을 마지막 저장 시각으로 닫고 여행도 끝낸다(당일 → 완료, 여러 날 → 오늘은 여기까지). **P3** 자정을 넘긴 구간은 시작한 날의 `localDate`에 남는다(다음 날 첫 기록의 그날 합계가 자정 이후 거리만큼 적게 잡히는 것을 받아들인다). **P4** 공유 위치 스트림은 마지막 위치 1개를 다시 준다(`replay = 1`) — 늦게 구독한 기록도 안내가 출발점으로 쓴 위치부터 받는다


# Controller rulings in force
- P1–P4 are in the Global Constraints above.
- Commit trailer names the model that wrote the commit (R10 from V1.0.0).
- R1: never run connected tests on any device other than R3CY903ZYQV; if it is absent, compile androidTest and report device runs as pending (another phone, R5CT43TFS9A, may be attached — do NOT use it).
