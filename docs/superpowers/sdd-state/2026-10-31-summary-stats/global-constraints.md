## Global Constraints

- **버전:** 1.4.0, versionCode 10400(`verMinor = 4`, `verPatch = 0`), debug `1.4.0-dev`, 화면 `V1.4.0`. 올리는 것은 작업 10에서 한다.
- **글:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구 그대로 쓴다.
- **"주행 1회":** 거리 ≥ `MIN_RIDE_DISTANCE_M`(10 m)인 끝난 구간이다.
  - 날짜는 구간의 `localDate`다.
  - 주는 월요일부터 일요일까지다.
  - 오늘은 기기 시간대의 오늘이다. core 함수는 `today: LocalDate`를 인자로 받는다.
- **브이월드 키:** 로그·예외 메시지·커밋에 넣지 않는다.
  - 로그는 `VideoMapStyle.redacting`을 거친다.
  - 시험에는 가짜 키(`TESTKEY`)를 쓴다.
- **스레드 규칙(V1.3.0):** MapSnapshotter의 생성·start·cancel·투영은 Main에서 한다. 그림 Canvas는 소프트웨어 비트맵만 쓴다.
- **기기 규칙:**
  - connected 시험은 S22 `R5CT43TFS9A`에서만, 클래스 하나씩 돌린다. 화면을 깨운 뒤 `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스> </dev/null`로 실행한다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정을 바꾸지 않는다.
- **JVM 시험 명령:** `./gradlew :core:test --tests '<패턴>'`, `./gradlew :app:testDebugUnitTest --tests '<패턴>'`
- **커밋 꼬리말:** 그 커밋을 쓴 모델 이름으로 `Co-Authored-By: Claude <모델> <noreply@anthropic.com>`

## Rulings made while planning (spec silent or spec wording replaced)

- **D1 — 제목·지역은 `trip` 칸이 아니라 새 표 `trip_label`에 둔다.** 스펙 §4는 `trip` 표 칸 두 개였다.
  - 이유: `TripDao.updateTrip`는 `Trip` 복사본 전체를 쓰는 `@Update`라서, 나중에 다른 코드가 여행 상태를 저장할 때 제목을 NULL로 덮는다.
  - 새 표(`tripId` 기본 키, `trip(id)` 외래 키 ON DELETE CASCADE)는 그 경로와 따로 놀고, 여행을 지우면 같이 지워진다.
  - 옮기기는 지금 방식대로 `AutoMigration(from = 5, to = 6)`이다.
  - 틀렸을 때의 비용: 사용자에게 보이는 차이 없음.
- **D2 — 피드 그림의 경로 색은 요약 이미지처럼 늘 주황(`FrameStyle.ROUTE_ORANGE`)이다.** 스펙 §2.4의 "날짜별 색 규칙 그대로"는 요약 이미지를 따른다는 뜻으로 읽었다. 요약 이미지는 여러 날도 주황이다. 틀렸을 때의 비용: 여러 날 카드 그림 색만 다름.
- **D3 — 지역·그림의 "같은 앱 실행 동안 다시 안 함"은 프로세스 메모리의 집합으로 센다.**
- **D4 — 개인 기록 신기록(§3.4)은 그 항목에 해당하는 더 이른 주행이 있을 때만이다.**
  - 예: 5 km 이상 주행이 처음이면 "가장 빠른 평균 속도 신기록"이 아니다.
  - "더 이른"은 `startedAt`이 더 작은 것이다.
- **D5 — "이번 기간 같은 날까지" 비교의 앞 기간 끝날(§3.3):**
  - [주]: 오늘 − 7일
  - [월]: 앞 달의 min(오늘 일, 앞 달 길이)일
  - [년]: `today.minusYears(1)`(2월 29일 → 2월 28일)
  - 앞 기간 시작은 그 기간의 첫날이다.
- **D6 — 제목 30자는 코드 포인트 수로 센다.** 이모지 하나가 1자다.

## Rulings made while planning (spec silent or spec wording replaced)

- **D1 — 제목·지역은 `trip` 칸이 아니라 새 표 `trip_label`에 둔다.** 스펙 §4는 `trip` 표 칸 두 개였다.
  - 이유: `TripDao.updateTrip`는 `Trip` 복사본 전체를 쓰는 `@Update`라서, 나중에 다른 코드가 여행 상태를 저장할 때 제목을 NULL로 덮는다.
  - 새 표(`tripId` 기본 키, `trip(id)` 외래 키 ON DELETE CASCADE)는 그 경로와 따로 놀고, 여행을 지우면 같이 지워진다.
  - 옮기기는 지금 방식대로 `AutoMigration(from = 5, to = 6)`이다.
  - 틀렸을 때의 비용: 사용자에게 보이는 차이 없음.
- **D2 — 피드 그림의 경로 색은 요약 이미지처럼 늘 주황(`FrameStyle.ROUTE_ORANGE`)이다.** 스펙 §2.4의 "날짜별 색 규칙 그대로"는 요약 이미지를 따른다는 뜻으로 읽었다. 요약 이미지는 여러 날도 주황이다. 틀렸을 때의 비용: 여러 날 카드 그림 색만 다름.
- **D3 — 지역·그림의 "같은 앱 실행 동안 다시 안 함"은 프로세스 메모리의 집합으로 센다.**
- **D4 — 개인 기록 신기록(§3.4)은 그 항목에 해당하는 더 이른 주행이 있을 때만이다.**
  - 예: 5 km 이상 주행이 처음이면 "가장 빠른 평균 속도 신기록"이 아니다.
  - "더 이른"은 `startedAt`이 더 작은 것이다.
- **D5 — "이번 기간 같은 날까지" 비교의 앞 기간 끝날(§3.3):**
  - [주]: 오늘 − 7일
  - [월]: 앞 달의 min(오늘 일, 앞 달 길이)일
  - [년]: `today.minusYears(1)`(2월 29일 → 2월 28일)
  - 앞 기간 시작은 그 기간의 첫날이다.
- **D6 — 제목 30자는 코드 포인트 수로 센다.** 이모지 하나가 1자다.

## Review Focus

1. **그림을 만드는 중에 그 여행이 지워지면:** 파일이 남거나 앱이 죽으면 안 된다. 작업 5 `deleteDuringRenderLeavesNoFile`.
2. **연말을 넘는 주:** 예: 2025-12-29(월)~2026-01-04.
   - [주]에서는 한 주이고, ‹ ›가 해를 넘나든다.
   - [월]에서는 12월 막대에 12월 날짜만, 1월 막대에 1월 날짜만 들어간다.
   - 연속 주행은 끊기지 않는다.
   - 작업 1 `weekAcrossYearEnd`, 작업 2 `streakAcrossYearEnd`.
3. **그림을 아직 못 만드는 여행:** 단순화 경로가 없거나 지점이 없는 여행이다. 피드는 선 그림 또는 빈 바탕으로 보여 주고 대기 줄이 멈추지 않는다. 작업 5 `missingInputsSkipsAndContinues`.
4. **목표 칸에 붙여 넣은 "1,000"이나 앞뒤 빈칸:** 1000으로 받는다. "1.5", "-3", "0", "100001"은 거절한다. 작업 2 `goalParsing`.
5. **그림을 만드는 중에 기록이 시작되면:**
   - 대기 줄은 다음 그림을 시작하지 않는다.
   - 하던 그림은 끝까지 그리고 저장한다.
   - 기록이 끝나면 이어서 만든다.
   - 작업 5 `pausesWhileBusyAndResumes`.

