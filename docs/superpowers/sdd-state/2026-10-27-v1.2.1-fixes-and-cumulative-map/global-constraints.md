## Global Constraints

- 버전 `1/2/1` → versionCode 10201, versionName `1.2.1`(debug `1.2.1-dev`), 화면 `V1.2.1`. 브랜치 `feature/trips-v1.2.0`. CHANGELOG에 `## [1.2.1] - 개발 중`.
- 스레드·DI·MapLibre 규칙은 V1.2.0과 같다(`rideScope` 단일 스레드 R12, 후처리는 `postScope`, MapLibre는 `AndroidView`·메인 스레드, DI·이미지·차트 라이브러리 금지).
- 수치(스펙 §6 그대로): 성긴 심박 다시 읽기 한도 = 구간 끝 + 24시간(`HrRetryPolicy.GIVE_UP_MS`); 누적 지도 최소 거리 `MIN_RIDE_DISTANCE_M = 10.0`; 주행 선 두께 4·불투명도 0.8; 강조 선 두께 6·불투명도 1(맨 위); 흐린 선 불투명도 0.25; 주는 월요일 시작.
- 주행 색 10색(최신 주행부터 차례, 11번째부터 반복): `#E53935, #1E88E5, #43A047, #8E24AA, #FB8C00, #00ACC1, #D81B60, #3949AB, #6D4C41, #7CB342`.
- 문구(스펙 그대로): "[주 ▾]" 칩 이름 "주", "[이번 주]", "[이번 달]", 주 라벨 "M월 N주 (M/d~M/d)"(예 "10월 4주 (10/19~10/25)", "10월 1주 (9/28~10/4)"), 주행 목록 줄 "M월 d일 (E) · {거리} · {이동 시간}"(예 "10월 25일 (토) · 1.59km · 15분", 요일 한국어 한 글자), "기록 보기", 기간에 기록 없음 "이 기간에 기록이 없습니다". 기존 "누적 지도", "전체", "연도", "월", "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다"(기록이 하나도 없을 때)는 그대로.
- 테스트: core·app JVM JUnit5, 기기 JUnit4 + `createComposeRule`. **기기 규칙: connected 테스트는 S22 `R5CT43TFS9A`에서만** `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> </dev/null`(한 번에 클래스 하나, 먼저 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`, 휴대폰 설정 변경 금지). **S25 `R3CY903ZYQV`에는 아무것도 하지 않는다**(설치는 컨트롤러가 사용자 요청 때만). S22가 없으면 컴파일만 하고 "기기 검증 대기".
- 커밋 끝에 `Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>`. 기존 테스트는 모두 통과(동작이 바뀌어 고치는 테스트는 Task가 이름을 밝힌다).


# Controller rulings in force
- V1.2.0 rulings R1–R16, P1, P2 remain (see docs/superpowers/sdd-state/2026-10-18-trips-postprocess/progress.md and global-constraints.md there).
- User-facing text Korean only. Commit trailer names the model that wrote the commit.
- R17: READY re-read without sample replacement keeps samples but fills calories when stored calories are null; replacement keeps stored calories when the new read has none.
- R18: overlapping workout sessions are not double counted (each counts only ride time not already covered by an earlier-starting session).
- R19: HR_NOT_DETAILED copy = "워치에서 운동을 시작하면 자세한 심박이 기록됩니다".
