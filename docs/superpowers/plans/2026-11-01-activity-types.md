# 활동 종류 (V1.9.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자전거 기록 앱에 걷기·달리기·등산을 더한다. 구간마다 종류가 붙고, 종류마다 기록 기준·숫자·음성·색·통계·영상·공유·GPX가 따로 맞춰진다. 자전거 기록과 기능은 V1.8.0과 똑같이 남는다.

**Architecture:**
- **core `com.cowork.bikerecoder.core.activity`** (새 패키지, JVM 시험)
  - `ActivityType`, `ActivityProfile`, `ActivityMix`(대표 종류·배지 순서)
  - `ActivityFormat`(페이스), `BestEfforts`, `SunTimes`, `HikeAnnouncer`, `ActivityChange`(종류 바꾸기 다시 계산)
- **core 수정**
  - `recording/*`: `RecordingSession`이 `ActivityProfile`을 받아 하위 엔진에 기준값을 넘긴다. 하위 엔진 생성자 기본값은 자전거 값 그대로다.
  - `RideSegment.activity`, `RideReplay`, `UsualSpeed`, `SpeedBand`, `KoreanPhrases`, `RideTitle`, `stats/*`, `importing/*`, `export/*`
- **app**
  - `data/`: DB 9판(`ride_segment.activity`, `segment_best_effort`)
  - `ui/activity/`: `ActivityStyle`(색·아이콘·배지 컴포저블), `ActivityText`(새 문구 전부)
  - `ride/RideController`, `ui/main`, `ui/record`, `ui/feed`, `ui/history`, `ui/elevation`, `ui/stats`, `video/`, `share/`, `ui/settings`, `importing/`, `ui/importing`, `export/`

**Tech Stack:** Kotlin, Room(AutoMigration 8→9), DataStore, Compose Material 3(Material Icons Extended: `DirectionsBike`, `DirectionsWalk`, `DirectionsRun`, `Hiking`), MapLibre, JUnit5(core·app JVM), Compose UI test(androidTest).

**Spec:** `docs/superpowers/specs/2026-11-01-activity-types-design.md`. 아래 "스펙 §"는 이 문서를 가리킨다. 문구·색·기준값은 스펙 표가 정본이다. 이 계획은 스펙에 없는 결정만 새로 적는다(아래 "계획에서 정한 것").

## Global Constraints

- **자전거는 그대로:**
  - `BIKE`의 기록 기준값은 V1.8.0 엔진 기본값과 같다(스펙 §1.3 자전거 열).
  - 같은 위치 점을 `BIKE`로 기록하면 V1.8.0과 같은 합계·이벤트가 나와야 한다.
  - 기존 시험(`RecordingSessionTest`, `AutoPauseDetectorTest`, `TrackFilterTest`, `RideStatsCalculatorTest`, `RideReplayTest`)은 고치지 않고 통과해야 한다. 이벤트 필드 이름이 바뀌어 고칠 때만 그 줄을 바꾼다.
  - 자전거 문구는 바뀌지 않는다. 예외는 스펙이 고친 두 곳이다: 1 km 문장의 `평균 시속`(§3.3), 내보내기 이름(§2.3, 자전거는 "주행" 그대로).
- **저장 문자열:** `BIKE`, `WALK`, `RUN`, `HIKE`. 모르는 문자열은 `BIKE`로 읽는다.
- **기본값:** 종류가 없는 곳(기존 행, 길안내 기록, `OTHER`·없는 GPX `<type>`)은 모두 `BIKE`다.
- **값 출처:**
  - 기록 기준(§1.3), 색·배지 크기(§2.1), 카드 칸(§2.2), 숫자 화면(§3.2), 음성 문장(§3.3, §3.4)
  - `SpeedBand` 경계(§3.5), 통계 칸(§4.1), 목표 키(§4.2), 기록(§4.3)
  - 영상 칸·카메라(§5.1), 공유 숫자 줄·해시태그 키와 처음 값(§5.2)
  - 모두 스펙 표의 값과 글자를 그대로 쓴다.
- **문구 위치:**
  - 새 화면 문구는 `app/.../ui/activity/ActivityText.kt` 한 곳에 둔다.
  - 새 음성 문구는 core `KoreanPhrases`에 둔다.
  - 색은 `ActivityStyle` 한 곳(app)에만 둔다. core는 색을 모른다. 예외는 `SpeedBand`(기존 그대로 core)다.
- **앱 전체의 다른 "주행" 문구는 바꾸지 않는다**(V2.0.0).
- **DB:** 9판. AutoMigration 8→9만 쓰고, 다른 표·칸은 바꾸지 않는다. `app/schemas/.../9.json`을 커밋한다.
- **기기 시험:**
  - S22 `R5CT43TFS9A`에서만, 클래스 하나씩 돌린다.
  - 화면을 먼저 깨운다: `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`.
  - `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`을 붙인다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.
- **스타일:**
  - 주석 언어는 파일마다 기존을 따른다. core는 한국어 KDoc, app은 영어 KDoc이다.
  - core 시험은 `org.junit.jupiter` + `kotlin.test`로 쓴다.
- **시험 명령:**
  - core: `./gradlew :core:test --tests '<클래스>'`
  - app: `./gradlew :app:testDebugUnitTest --tests '<클래스>'`
  - 각 Task 끝: `./gradlew :core:test :app:testDebugUnitTest`가 모두 통과해야 한다.

## 계획에서 정한 것 (스펙이 열어 둔 곳)

| # | 결정 | 까닭 |
|---|---|---|
| P1 | `RecordingEvent.KmReached(km, distanceM, movingTimeMs, lapMovingTimeMs)`. 거리·시간은 프로필의 `kmBasis` 기준 합계다(자전거=그날, 달리기·걷기=이 구간). `lapMovingTimeMs`는 앞 km 표시부터의 이동 시간이다. `KmBasis.NONE`(등산)이면 이 이벤트를 내지 않는다 | 이번 1 km 페이스를 엔진이 알아야 한다 |
| P2 | 자전거의 "그날 합계"(`RideStore.dayTotals`, `initialDay`)는 그날 그 여행의 **BIKE 구간만** 더한다 | 걷고 나서 탈 때 걷기 거리가 자전거 km 안내에 섞이지 않게 한다. 자전거만 탄 날은 V1.8.0과 같다 |
| P3 | 페이스 음성은 `{m}분 {s}초`이고, s=0이면 `{m}분`이다 | "5분 0초"를 피한다 |
| P4 | 등산 음성의 시간(`산행 {시간}`, `일몰까지 {시간}`)은 `KoreanPhrases.spokenDuration`을 쓴다: `{h}시간`, `{h}시간 {m}분`, `{m}분`(분 내림, 0분은 빼기). 자전거 문장은 지금의 `RideFormat.duration` 그대로다 | "산행 1시간 0분"을 피한다 |
| P5 | 등산 30분 안내에서 고도를 모르면 `현재 고도 {h}미터, ` 부분을 뺀다 | 고도는 null일 수 있다 |
| P6 | 배지 순서: 대표 종류가 먼저이고, 나머지는 종류별 이동 시간 합이 큰 순서다. 최대 3개다 | 대표 종류(가장 긴 한 구간)와 합계 순이 다를 수 있다 |
| P7 | 여행 그래프가 `페이스`가 되는 경우: 모든 구간이 `RUN`이거나 모든 구간이 `WALK`일 때다. 그 밖에 두 종류 이상이 섞이면 `속도`(km/h)이고, 범례 자리에 `종류마다 색 기준이 다릅니다`를 둔다 | "이 종류만 있는 여행"의 뜻을 하나로 정한다 |
| P8 | GPX 내보내기는 이웃한 같은 종류 구간을 한 `<trk>`로 묶는다. 종류가 바뀔 때마다 새 `<trk>`(이름은 같은 제목)다. 한 종류뿐인 여행은 지금처럼 `<trk>` 하나다 | 자전거만 있는 여행의 출력이 V1.8.0과 같다 |
| P9 | 가져오기 확인 목록에서 종류를 바꿔도 그 줄의 숫자(거리·시간)는 읽을 때의 값 그대로 둔다. 가져오기 작업이 고른 종류로 다시 계산한다 | 읽기 단계는 요약만 들고 있다 |
| P10 | 피드 맨 위 이번 주 줄의 목표 막대는 자전거 목표와 자전거 거리를 쓴다. 횟수·거리는 모든 종류를 센다 | 스펙에 없음. 지금 목표는 자전거 목표다(§4.2) |
| P11 | 영상 등산 숫자 띠의 `누적 상승`은 타임라인 고도를 처음부터 차례로 `AscentCounter`에 넣어 미리 구한다. `산행 시간`은 그 점 시각 − 그 구간 첫 점 시각이다 | 프레임마다 다시 세지 않는다 |
| P12 | 하프는 저장 키 `distanceM = 21098`, 계산 목표 21,097.5 m다 | 칸이 INTEGER다 |
| P13 | 일몰 안내 겹침: 30분 안내 차례에 아직 안 한 일몰 안내가 2분 안에 있으면 30분 안내를 건너뛴다. 일몰 안내 차례에 30분 안내가 2분 안에 있으면 그 30분 안내를 한 것으로 친다 | 스펙 §3.4 "2분 안에 함께"를 양쪽에서 정한다 |
| P14 | 길안내 중인 여행의 `startRecording`은 넘겨받은 종류와 관계없이 `BIKE`로 기록한다. 판단은 `RideController` 한 곳에서 한다 | 모든 시작 경로를 한 곳에서 막는다 |
| P15 | 기록 화면 요약 줄: 자전거는 그대로다. 달리기·걷기는 `{거리} · {이동 시간} · 평균 {페이스}/km`이고, 지도 페이지 앞머리가 `{현재 페이스}/km · `다. 등산은 `산행 {H:MM:SS} · {고도} · ↑ {상승}`이고, 지도 페이지 앞머리가 없다 | 스펙 §3.2 "같은 규칙"을 문장으로 정한다 |
| P16 | 기록을 마친 요약의 목표 줄(`Goals.crossed`)은 그 구간 종류의 목표와 그 종류의 구간만 쓴다 | 종류별 목표(§4.2) |

## Review Focus

1. **업데이트 뒤의 기존 자전거 기록**
   - 기대: 모든 구간이 `BIKE`가 되고, 숫자·제목·통계·기록·목표·평소 속도가 업데이트 전과 같다.
   - 시험:
     - Task 1 `bikeProfileMatchesV180Engine`
     - Task 4 `migrate8To9`(기기)
     - Task 9 `bikeRecordsUnchangedWithOtherTypes`
2. **같은 날 자전거 → 걷기 → 자전거 (한 여행)**
   - 기대: 걷기의 1 km 안내는 그 구간의 1 km부터 센다.
   - 기대: 다시 탄 자전거의 km 안내는 자전거 구간 합계에서 이어진다(P2).
   - 기대: 평소 속도는 걷기를 세지 않는다.
   - 시험:
     - Task 1 `segmentBasisCountsFromSegmentStart`
     - Task 4 `dayTotalsCountOnlyBike`
     - Task 1 `usualSpeedIgnoresWalking`
3. **등산 중 오래 쉬거나 GPS가 끊김**(정상에서 30분 쉬기, 숲속)
   - 기대: 일시정지 중에도 30분 안내와 일몰 안내가 제때 한 번씩 나온다.
   - 기대: 위치를 몰라 일몰을 계산하지 못하면 일몰 안내 없이 30분 안내만 나온다.
   - 시험:
     - Task 3 `announcesWhilePaused`, `noSunsetNoSunsetCues`
     - Task 5 `hikeCuesComeOnTicksWhilePaused`
4. **종류 바꾸기를 하면 안 되거나 할 게 없는 구간**
   - 대상: 기록 중인 구간, 점이 1개뿐인 구간, 같은 종류를 다시 고름.
   - 기대:
     - 기록 중이면 메뉴가 없다.
     - 최소 조건을 못 넘으면 종류만 바뀌고 합계는 그대로다.
     - 같은 종류를 고르면 아무것도 쓰지 않는다.
   - 시험:
     - Task 1 `tooShortKeepsTotals`
     - Task 8 `changeActivityHiddenWhileRecording`, `sameActivityWritesNothing`
5. **일몰 앞뒤에 시작한 등산, 일몰이 없는 날**
   - 대상: 일몰 40분 전 시작, 일몰 뒤 시작, 극지방(null).
   - 기대:
     - 일몰 40분 전 시작: 2시간·1시간 안내는 하지 않고, 시작 안내에 남은 시간을 붙이고, 30분 전 안내는 한다.
     - 일몰 뒤 시작: 일몰 안내가 없고, 화면은 `일몰 hh:mm · 지남`이다.
     - 극지방(null): 줄도 안내도 없다.
   - 시험:
     - Task 3 `startedFortyMinutesBeforeSunset`, `startedAfterSunset`
     - Task 2 `polarNightHasNoSunset`
     - Task 6 `sunsetLineAfterSunset`

---

### Task 1: core — 종류, 기록 기준, 엔진 연결, 종류 바꾸기 계산

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/activity/ActivityType.kt`
  - `ActivityType`
  - `ActivityMix`
- Create: `core/.../activity/ActivityProfile.kt`
- Create: `core/.../activity/ActivityChange.kt`
- Modify:
  - `core/.../recording/RecordingSession.kt`
  - `RideStatsCalculator.kt`
  - `RideModels.kt`
  - `RideStore.kt`
  - `core/.../importing/RideReplay.kt`
  - `core/.../elevation/UsualSpeed.kt`
- Test (create):
  - `core/src/test/.../activity/ActivityTypeTest.kt`
  - `ActivityProfileTest.kt`
  - `ActivityChangeTest.kt`
- Test (modify):
  - `core/src/test/.../recording/RecordingSessionTest.kt`: 새 시험만 더하고, 이벤트 필드 이름만 바꾼다
  - `elevation/UsualSpeedTest.kt`

**Interfaces:**
- Consumes: 기존 `RecordingSession`, `AutoPauseDetector`, `TrackFilter`, `RideStatsCalculator`, `RideReplay`, `TrackPoint`, `LocationFix`
- Produces:
  - `enum class ActivityType { BIKE, WALK, RUN, HIKE }`
    - `val stored: String`
    - `val gpxType: String`: `cycling`, `walking`, `running`, `hiking`
    - `companion fun fromStored(s: String?): ActivityType`: 모르면 `BIKE`
  - `enum class KmBasis { DAY, SEGMENT, NONE }`
  - `data class ActivityProfile`
    - 칸: `pauseBelowMps`, `pauseAfterMs`, `resumeAtMps: Double`(등산은 `Double.POSITIVE_INFINITY`), `resumeAfterMs`, `resumeDistanceM`, `maxSpeedMps`, `minGapSpeedMps`, `kmBasis`, `cameraSlowKmh`, `cameraFastKmh`
    - `companion fun of(type: ActivityType): ActivityProfile`
    - 값은 스펙 §1.3과 §5.1(카메라)이다.
    - 등산의 `resumeAfterMs`는 쓰이지 않으므로 0이다.
  - `data class ActivityMix(val dominant: ActivityType, val badges: List<ActivityType>)`
    - `companion fun of(segments: List<RideSegment>): ActivityMix`
    - 대표 종류는 이동 시간이 가장 긴 한 구간이고, 같으면 `startedAt`이 앞선 구간이다. 배지는 P6을 따른다.
    - 구간이 없으면 `ActivityMix(BIKE, listOf(BIKE))`다.
  - `RideSegment(..., activity: ActivityType = ActivityType.BIKE)`: 마지막 인자다
  - `RecordingSession(startMillis, autoPause, initialDay = DayTotals.ZERO, zone, gpsTimeoutMs, profile: ActivityProfile = ActivityProfile.of(BIKE))`
  - `RecordingEvent.KmReached(km: Int, distanceM: Double, movingTimeMs: Long, lapMovingTimeMs: Long)`(P1)
  - `RideStats`에 칸 두 개를 더한다. `ZERO`에서는 둘 다 null이다.
    - `recentSpeedMps: Double?`: 기록 중 사슬의 최근 30초 이동 거리 ÷ 시간. 창 안 점이 2개 미만이면 null이다.
    - `lastLapMovingTimeMs: Long?`: 이 세션의 마지막 `KmReached.lapMovingTimeMs`. 없으면 null이다.
  - `RideReplay.replay(fixes, zone, activity: ActivityType = BIKE)`
  - `object ActivityChange`
    - `fun recompute(startedAt: Long, endedAt: Long, points: List<TrackPoint>, activity: ActivityType, zone: ZoneId): SegmentTotals?`
    - 점을 `LocationFix`로 바꿔 `RecordingSession(startedAt, autoPause = true, profile = of(activity))`에 `onFix(fix, fix.timeMillis)`로 넣고, `stop(endedAt)`의 합계를 돌려준다.
    - 채택 점 < 2 또는 거리 < `MIN_RIDE_DISTANCE_M`이면 null이다.
  - `UsualSpeed.of`는 `activity == BIKE`인 구간만 센다.
  - `RideStore.openSegment(tripId, startedAt, localDate, activity: ActivityType = BIKE)`: 인터페이스만 바꾼다. 구현은 Task 4다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `ActivityTypeTest`
    - `storedRoundTrip`
    - `unknownStoredIsBike`: `fromStored("SKI")`, `fromStored(null)` → `BIKE`
    - `gpxTypes`
    - `mixDominantIsLongestSingleSegment`: 걷기 20분 + 걷기 20분 + 자전거 30분 → 대표 `BIKE`, 배지 `[BIKE, WALK]`
    - `mixTieGoesToEarlier`
    - `mixBadgesCapAtThree`
  - `ActivityProfileTest`: 종류마다 다음을 시험한다.
    - `pausesJustBelowThreshold`: 0.1 km/h 아래로 `pauseAfterMs` 동안 머물면 Paused(AUTO)
    - `staysJustAboveThreshold`: 0.1 km/h 위면 계속 Recording
    - `resumesBySpeed`: 등산은 10 km/h로 10초 달려도 그 자리(15 m 안)면 재개하지 않는다
    - `resumesByDistance`
    - `filterRejectsAboveMaxSpeed`: 달리기 41 km/h 튐은 버리고, 39 km/h는 받는다
    - `gapCountsAboveMinGapSpeed`: 등산 12초 틈 + 0.8 km/h → 거리에 들어간다. 0.6 km/h → 들어가지 않는다
  - `RecordingSessionTest`
    - `bikeProfileMatchesV180Engine`: 이 시험 파일에 있는 긴 자전거 고정 경로(또는 시험용으로 만든 5 km, 정지 2번)를 넣는다. 인자 없는 생성과 `profile = of(BIKE)` 생성의 이벤트·`stats`·점이 모두 같아야 한다.
    - `segmentBasisCountsFromSegmentStart`: 달리기, `initialDay = DayTotals(5_000.0, 1_800_000)` → 첫 `KmReached`의 km=1이고 `distanceM` < 1,100이다.
    - `hikeHasNoKmEvents`
    - `lapTimeIsSinceLastKm`: 두 번째 km 이벤트의 `lapMovingTimeMs` = 두 이벤트 사이 이동 시간
    - `recentSpeedNeedsTwoPoints`: 30초 창, 일시정지 뒤 null로 돌아간다
  - `ActivityChangeTest`: 1.5 km/h로 2시간 오르는 등산 점(1초 간격, 바람에 0.5~2.5 km/h로 흔들림)으로 시험한다.
    - `bikeRecomputeCutsMovingTime`: 자전거로 다시 계산하면 이동 시간 < 원래의 50%
    - `hikeRecomputeKeepsMostMovingTime`: 등산으로 다시 계산하면 이동 시간 > 경과의 90%
    - `doesNotTouchPoints`: 넘긴 목록이 그대로다
    - `tooShortKeepsTotals`: 점 1개 → null
  - `UsualSpeedTest.usualSpeedIgnoresWalking`: 자전거 18 km/h 구간과 걷기 5 km/h 구간 → 18 km/h
- [ ] **Step 2:** `./gradlew :core:test --tests '*Activity*' --tests '*RecordingSessionTest' --tests '*UsualSpeedTest'`를 돌려 실패를 확인한다.
- [ ] **Step 3:** 위 인터페이스를 구현한다.
  - `RecordingSession`은 `profile`로 하위 엔진을 만든다.
    - `TrackFilter(maxSpeedMps = …)`
    - `RideStatsCalculator(minGapSpeedMps = …)`
    - `AutoPauseDetector(pauseBelowMps, pauseAfterMs, resumeAtMps, resumeAfterMs, resumeDistanceM)`
  - km 세기:
    - `DAY`는 지금 코드 그대로다.
    - `SEGMENT`는 `calc.distanceM` 기준이다. 시작은 0이고, 날짜가 바뀌어도 다시 세지 않는다.
    - `NONE`은 세지 않는다.
  - `recentSpeedMps`는 `RideStatsCalculator`가 (시각, 누적 거리) 창으로 구한다. `breakChain`에서 비운다.
  - 기존 `KmReached` 사용처(app `RideController.speech`)는 필드 이름만 바꿔 컴파일되게 둔다. 동작은 Task 5에서 바꾼다.
- [ ] **Step 4:** Step 2 명령과 `./gradlew :core:test :app:testDebugUnitTest`를 돌린다. 기존 시험은 모두 통과해야 한다.
- [ ] **Step 5: 커밋** `feat(core): activity types, per-type recording profiles and recompute`

---

### Task 2: core — 페이스, 종류별 속도 색, 최고 기록, 일몰

**Files:**
- Create:
  - `core/.../activity/ActivityFormat.kt`
  - `core/.../activity/BestEfforts.kt`
  - `core/.../activity/SunTimes.kt`
- Modify: `core/.../series/SpeedBand.kt`
- Test (create):
  - `core/src/test/.../activity/ActivityFormatTest.kt`
  - `BestEffortsTest.kt`
  - `SunTimesTest.kt`
- Test (modify): `core/src/test/.../series/SpeedBandTest.kt`

**Interfaces:**
- Consumes: `ActivityType`(Task 1), `TrackPoint`, `GeoMath`
- Produces:
  - `object ActivityFormat`
    - `fun pace(speedMps: Double?): String`: `5'32"` 또는 `--'--"`
    - `fun paceOf(distanceM: Double, timeMs: Long): String`
    - `fun paceSeconds(speedMps: Double?): Int?`: 반올림한 초/km. 1 km/h 미만, null, 3,600초 초과면 null
    - `const val NO_PACE = "--'--\""`
  - `SpeedBand.of(kmh: Double, activity: ActivityType = BIKE): SpeedBand`
  - `SpeedBand.legend(activity: ActivityType): String`
    - 자전거는 지금 `legend` 그대로다.
    - 달리기: `7'00" 넘게`, `6'~7'`, `5'~6'`, `4'~5'`, `4'00" 미만`
    - 걷기: `3 미만`, `3~4`, `4~5`, `5~6`, `6 이상`
    - 등산: `1 미만`, `1~2`, `2~3`, `3~4`, `4 이상`
    - 기존 `legend` 프로퍼티는 자전거 문구로 남긴다.
  - `enum class BestEffortDistance(val keyM: Int, val targetM: Double, val label: String)`
    - `ONE_K(1000, 1000.0, "1 km")`
    - `FIVE_K(5000, 5000.0, "5 km")`
    - `TEN_K(10000, 10000.0, "10 km")`
    - `HALF(21098, 21097.5, "하프")`(P12)
    - `companion fun ofKey(keyM: Int): BestEffortDistance?`
  - `data class BestEffort(val distance: BestEffortDistance, val timeMs: Long, val startIndex: Int)`
  - `object BestEfforts`
    - `fun find(points: List<TrackPoint>, gapMs: Long = 10_000): List<BestEffort>`: 거리마다 하나, 없으면 뺀다.
  - `object SunTimes`
    - `fun sunset(lat: Double, lon: Double, date: LocalDate, zone: ZoneId): Long?`: epoch ms다. 해가 지지 않거나 뜨지 않는 날은 null이다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `ActivityFormatTest`
    - `formatsPace`: 10.84 km/h → `5'32"`
    - `roundsUpToNextMinute`: 359.6초/km → `6'00"`
    - `belowOneKmhIsDashes`: 0.99 km/h, null, 0 → `--'--"`
    - `overSixtyMinutesIsDashes`
  - `SpeedBandTest`
    - `runBoundsGoUp`: 60/7 km/h → 2번째 구간(EASY), 10 → STEADY, 12 → FAST, 15 → SPRINT
    - `walkBounds`: 3, 4, 5, 6
    - `hikeBounds`: 1, 2, 3, 4
    - `bikeUnchanged`: 기존 시험 그대로
  - `BestEffortsTest`
    - `steadyFiveKGivesOneKTime`: 1초 간격, 3 m/s 직선 5 km → 1 km 333,333 ms ± 1,000
    - `findsFastMiddleKilometre`: 4 m/s 구간이 2~3 km에 있음 → 1 km 250,000 ms ± 1,000, `startIndex`가 그 구간 안
    - `interpolatesOvershoot`: 10초 간격 점, 1 km 경계가 점 사이 → 보간한 시간
    - `doesNotCrossGap`: 0.6 km + 15초 틈 + 0.6 km → 1 km 기록 없음
    - `tooShortHasNone`: 4.9 km → 5 km 없음
    - `hundredThousandPointsUnderOneSecond`
  - `SunTimesTest`(±2분)
    - 서울(37.5663, 126.9779)
      - 2026-06-21 19:57: 한국천문연구원 값
      - 2026-12-22 17:18: 한국천문연구원 값
      - 2026-03-20 18:43: USNO 계산식 교차값. 천문연구원 출몰시각 화면에서 확인되면 그 값으로 바꾼다
    - 제주(33.4996, 126.5312)
      - 2026-06-21 19:47: 교차값
      - 2026-12-22 17:30: 교차값
    - `polarNightHasNoSunset`: 트롬쇠(69.6492, 18.9553) 2026-12-21, `Europe/Oslo` → null
    - `midnightSunHasNoSunset`: 같은 곳 2026-06-21 → null
- [ ] **Step 2:** 시험이 실패하는지 확인한다(`./gradlew :core:test --tests '*ActivityFormatTest' --tests '*SpeedBandTest' --tests '*BestEffortsTest' --tests '*SunTimesTest'`).
- [ ] **Step 3:** 구현한다.
  - `BestEfforts`
    - 점을 틈(이웃 점 사이 > `gapMs` 또는 시각이 거꾸로)에서 끊어 조각으로 나눈다.
    - 조각마다 누적 거리 배열을 만든다.
    - 시작점 i마다 `cum[j] − cum[i] ≥ target`인 가장 작은 j를 찾는다. j는 i와 함께 앞으로만 간다(두 포인터).
    - 끝 시각 = `t[j−1] + (t[j] − t[j−1]) × (target − (cum[j−1] − cum[i])) / (cum[j] − cum[j−1])`
    - 시간 = 끝 시각 − `t[i]`이고, 가장 작은 것을 고른다.
  - `SunTimes`
    - NOAA 태양 위치식(율리우스 세기 → 적위·균시차 → 시간각)을 쓴다. 천정 거리는 90.833°다.
    - `cos(H)`가 [−1, 1] 밖이면 null이다. 결과는 그 날짜 `zone`의 일몰 시각이다.
- [ ] **Step 4:** 시험이 통과하는지 확인한다.
- [ ] **Step 5: 커밋** `feat(core): pace format, per-type speed bands, best efforts, sunset`

---

### Task 3: core — 음성 문장과 등산 안내 시점

**Files:**
- Create: `core/.../activity/HikeAnnouncer.kt`
- Modify: `core/.../voice/KoreanPhrases.kt`
- Test (create): `core/src/test/.../activity/HikeAnnouncerTest.kt`
- Test (modify): `core/src/test/.../voice/KoreanPhrasesTest.kt`

**Interfaces:**
- Consumes: `ActivityType`, `RideFormat`
- Produces:
  - `KoreanPhrases`
    - `fun activityName(type: ActivityType): String`: 자전거, 걷기, 달리기, 등산
    - `fun recordingStarted(type: ActivityType, sunsetInMs: Long?): String`
      - 자전거는 `기록을 시작합니다`다.
      - 다른 종류는 `{이름} 기록을 시작합니다`다.
      - 등산이고 `0 < sunsetInMs < 2h`면 ` 일몰까지 {spokenDuration} 남았습니다.`를 붙인다.
    - `fun spokenDuration(ms: Long): String`(P4)
    - `fun pace(seconds: Int): String`(P3)
    - `fun rideKmReport(km, avgSpeedMps, movingTimeMs)`: 문장을 `평균 시속 …킬로미터`로 고친다. `rideKmReportWithNav`도 같이 고친다.
    - `fun paceKmReport(km: Int, lapSeconds: Int?, avgSeconds: Int?): String`
      - 형식: `{n}킬로미터. 이번 1킬로미터 {pace}, 평균 페이스 {pace}입니다.`
      - 값이 null인 부분은 뺀다.
    - `fun hikeReport(elapsedMs: Long, altitudeM: Double?, ascentM: Double, distanceM: Double, sunsetInMs: Long?): String`
      - 스펙 §3.3과 P5를 따른다. 미터는 반올림 정수, 거리는 km 소수 첫째 자리 HALF_UP이다.
      - `0 < sunsetInMs < 3h`면 끝에 ` 일몰까지 {spokenDuration} 남았습니다.`를 붙인다.
    - `fun sunsetCue(stage: SunsetStage): String`: 스펙 §3.4 네 문장
  - `enum class SunsetStage(val beforeMs: Long) { H2(7_200_000), H1(3_600_000), M30(1_800_000), NOW(0) }`
  - `sealed interface HikeCue`
    - `data class Periodic(val elapsedMs: Long) : HikeCue`
    - `data class Sunset(val stage: SunsetStage) : HikeCue`
  - `class HikeAnnouncer(private val segmentStartMillis: Long, periodMs: Long = 1_800_000, mergeMs: Long = 120_000)`
    - `fun setSunset(sunsetMillis: Long?, nowMillis: Long)`
      - 처음 정할 때 `sunsetMillis − beforeMs ≤ nowMillis`인 단계는 한 것으로 친다.
      - 바뀐 값에서도 이미 한 단계는 다시 하지 않는다.
    - `fun due(nowMillis: Long): HikeCue?`
      - 한 번에 하나를 돌려준다. 일몰 단계가 30분 안내보다 먼저다.
      - 겹침은 P13을 따른다.
      - 한 번에 여러 30분 차례가 밀렸으면(시계가 건너뜀) 마지막 하나만 돌려준다.
    - 시계는 읽지 않는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `KoreanPhrasesTest`
    - `bikeKmSaysSiSok`: `5킬로미터 이동. 평균 시속 18.0킬로미터, 이동 시간 16분입니다.`
    - `runKmReport`: `3킬로미터. 이번 1킬로미터 5분 32초, 평균 페이스 5분 40초입니다.`
    - `paceDropsZeroSeconds`: 300 → `5분`
    - `hikeReportWithSunset`: `산행 1시간 30분. 현재 고도 820미터, 누적 상승 412미터, 거리 4.3킬로미터입니다. 일몰까지 2시간 남았습니다.`
    - `hikeReportWithoutAltitude`
    - `startPhrases`: 자전거 그대로, `등산 기록을 시작합니다`, 일몰 1시간 30분 전이면 꼬리가 붙는다
  - `HikeAnnouncerTest`
    - `everyThirtyMinutes`: 30분과 60분에 Periodic이 나오고, 같은 차례는 한 번만 나온다
    - `sunsetStagesOnce`
    - `startedFortyMinutesBeforeSunset`: H2와 H1은 없고, M30과 NOW는 나온다
    - `startedAfterSunset`: 일몰 안내가 없다
    - `mergesWithinTwoMinutes`: 30분 차례 + 1분 뒤 M30 → Periodic은 없고 M30만 나온다
    - `mergesSunsetFirst`: 일몰 단계 + 1분 뒤 30분 차례 → 그 30분 안내는 건너뛴다
    - `noSunsetNoSunsetCues`
    - `announcesWhilePaused`: 이 클래스는 상태를 모르므로, 그 사이에 `due`를 1초마다 부르기만 하면 나온다는 것을 시험한다
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** 시험이 통과하는지 확인한다. 기존 `VoiceSchedulerTest`와 길안내 시험의 문장 기대값은 `평균 시속`으로만 고친다.
- [ ] **Step 5: 커밋** `feat(core): per-type voice sentences and hike announcer`

---

### Task 4: app — DB 9판, 저장소, 통계 원천

**Files:**
- Modify:
  - `app/.../data/Entities.kt`
    - `RideSegmentEntity.activity: String`에 `@ColumnInfo(defaultValue = "BIKE")`를 단다.
    - 새 `SegmentBestEffortEntity`
  - `app/.../data/AppDatabase.kt`: `version = 9`, `AutoMigration(from = 8, to = 9)`
  - `app/.../data/Daos.kt`
  - `app/.../data/RoomRideStore.kt`
  - `app/.../data/RoomRideStatsSource.kt`
  - `core/.../recording/RideStore.kt`
  - `core/.../stats/StatsModels.kt`, `RideStatsSource.kt`
  - 시험용 가짜 저장소들(`app/src/test/.../ride/RideTestDoubles.kt` 등): 새 메서드
- Create: `app/schemas/com.cowork.bikerecoder.data.AppDatabase/9.json`(빌드가 만든다)
- Test:
  - `app/src/androidTest/.../data/RideDatabaseMigrationTest.kt`: `migrate8To9`
  - `RoomRideStoreTest.kt`

**Interfaces:**
- Consumes: `ActivityType`, `BestEffort`, `BestEffortDistance`(Task 1·2)
- Produces:
  - `segment_best_effort`
    - 칸: `segmentId` FK CASCADE, `distanceM INTEGER`, `timeMs INTEGER`, `startIndex INTEGER`
    - 기본 키: (`segmentId`, `distanceM`)
    - `segmentId` 색인
  - `RideStore`
    - `openSegment(tripId, startedAt, localDate, activity)`(Task 1)
    - `dayTotals(tripId, localDate)`: BIKE 구간만 센다(P2)
    - `recentSegments(limit)`: BIKE만
    - `suspend fun saveBestEfforts(segmentId: Long, efforts: List<BestEffort>)`: 그 구간 행을 모두 바꾼다
    - `suspend fun changeActivity(segmentId: Long, activity: ActivityType, totals: SegmentTotals?, efforts: List<BestEffort>)`
      - 한 트랜잭션으로 한다.
      - totals가 null이면 합계는 그대로 둔다.
      - efforts는 바꾸고, `RUN`이 아니면 빈 목록이다.
  - `RideStat`에 칸을 더한다. 모두 기본값이 있다.
    - `activity: ActivityType = BIKE`
    - `elapsedMs: Long = 0`
    - `maxAltitudeM: Double? = null`
  - `data class StoredBestEffort(val segmentId: Long, val distance: BestEffortDistance, val timeMs: Long)`(core stats)
  - `RideStatsSource.observeBestEfforts(): Flow<List<StoredBestEffort>>`
    - 끝난 구간 것만 낸다.
    - 같은 목록은 다시 보내지 않는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `RoomRideStoreTest`(androidTest)
    - `openSegmentStoresActivity`
    - `dayTotalsCountOnlyBike`
    - `recentSegmentsOnlyBike`
    - `changeActivityReplacesTotalsAndEfforts`
    - `changeActivityNullTotalsKeepsTotals`
    - `deletingSegmentDeletesEfforts`
  - `RideDatabaseMigrationTest.migrate8To9`
    - 8판 DB에 구간 2개와 위치 점을 넣고 9판으로 옮긴다.
    - `activity`가 모두 `BIKE`다.
    - 다른 칸 값이 같다.
    - `segment_best_effort`가 비어 있다.
  - JVM 시험은 저장소 가짜를 쓰는 기존 시험이 컴파일되게만 맞춘다.
- [ ] **Step 2:** `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 9.json을 만든다. 9.json에서 `ride_segment.activity`의 기본값이 `'BIKE'`인지 확인한다.
- [ ] **Step 3:** 구현한다. DAO 쿼리에 `AND activity = 'BIKE'`를 더하는 곳은 `recentSegments`와 `dayTotals` 두 곳뿐이다.
- [ ] **Step 4:** JVM 전체를 돌린다. S22에서 `RideDatabaseMigrationTest`와 `RoomRideStoreTest`를 하나씩 돌린다.
- [ ] **Step 5: 커밋** `feat(data): DB 9 with segment activity and running best efforts`

---

### Task 5: app — 종류 고르기와 기록 엔진 연결, 안내, 알림

**Files:**
- Create:
  - `app/.../ui/activity/ActivityStyle.kt`
    - 색·배지 색(밝은/어두운)·아이콘·이름
    - `@Composable ActivityBadge(type, size: Dp)`
    - `@Composable ActivityBadges(types, size: Dp)`: 겹침 8dp
    - `@Composable ActivityChips(selected, onSelect)`
  - `app/.../ui/activity/ActivityText.kt`
    - 이 Task의 문구
    - `sunsetLine(sunsetMillis, nowMillis, zone)`
    - 뒤 Task가 문구를 더한다.
- Modify:
  - `app/.../data/SettingsRepository.kt`: `lastActivity: ActivityType = BIKE`, 키 `last_activity`
  - `app/.../ui/main/MainScreen.kt`, `MainViewModel.kt`
    - [● 기록 시작] 위에 칩을 둔다.
    - 띠 문구(스펙 §2.4)
  - `app/.../ui/record/RecordViewModel.kt`: `start(tripId)`가 `lastActivity`를 넘긴다
  - `app/.../ride/RideController.kt`, `RideUiState.kt`
  - `app/.../nav/NavNotifications.kt`
- Test:
  - `app/src/test/.../ride/RideControllerTest.kt`
  - `app/src/test/.../ui/main/MainViewModelTest.kt`, `RideBandTest.kt`
  - `app/src/test/.../ui/activity/ActivityTextTest.kt`(새)
  - androidTest: `app/src/androidTest/.../ui/main/ActivityChipsTest.kt`(새)

**Interfaces:**
- Consumes:
  - `ActivityProfile`, `RecordingSession(profile)`, `KmReached`(P1)
  - `KoreanPhrases`의 새 문장
  - `HikeAnnouncer`, `SunTimes`, `BestEfforts`, `RideStore.saveBestEfforts`
- Produces:
  - `RideController.startRecording(tripId: Long, activity: ActivityType = BIKE): Job?`: P14
  - 구간 나누기(`splitSegment`)는 앞 구간의 종류를 넘긴다(`Run.activity`).
  - `RideUiState.Active`에 칸을 더한다.
    - `activity: ActivityType`
    - `sunsetMillis: Long?`: 등산이고 계산됐을 때만 있다
  - `ActivityStyle`
    - `fun color(type): Color`
    - `fun badgeColors(type, dark: Boolean): Pair<Color, Color>`
    - `fun icon(type): ImageVector`
    - `fun hex(type): String`: 지도 선용 `#RRGGBB`
  - 값은 스펙 §2.1 그대로다.
  - `ActivityText.sunsetLine`
    - `일몰 17:32 · 1시간 40분 남음`. 남은 시간은 `RideFormat.duration`이다.
    - 일몰 뒤는 `일몰 17:32 · 지남`이다.
  - `fun sunsetWarning(sunsetMillis, nowMillis): Boolean`: 1시간 안 또는 지남

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `RideControllerTest`
    - `startUsesChosenActivity`: `startRecording(trip, RUN)` → `openSegment(…, RUN)`, 세션이 달리기 기준으로 자동 일시정지한다
    - `guidedRecordingIsAlwaysBike`
    - `splitKeepsActivity`
    - `runKmSpeaksPaceSentence`
    - `bikeKmSentenceUnchangedButSiSok`
    - `hikeStartPhraseWithSunset`
    - `hikeCuesComeOnTicksWhilePaused`: 수동 일시정지 중 30분 tick → `hikeReport` 발화
    - `voiceOffSilencesHikeCues`
    - `finishSavesBestEffortsForRun`
    - `finishSavesNothingForBike`
    - 일몰 다시 계산:
      - `sunsetRecomputedAfterOneKm`: 위치가 1 km 넘게 바뀌면 다시 계산한다
      - 날짜가 바뀌면 다시 계산한다
      - 그 밖에는 다시 계산하지 않는다(계산 함수 호출 수를 센다)
  - `MainViewModelTest`
    - `chipRemembersLastActivity`
    - `bannerSaysActivityForNonBike`: `● 등산 기록 중 · …`이고 숫자는 산행 시간이다
    - 자전거는 그대로다.
  - `ActivityTextTest`: `sunsetLineBeforeAfter`, `warningFromOneHour`
  - `NavNotifications`(기존 시험 파일)
    - `titleSaysActivity`: 자전거는 `주행 기록 중`이다
    - `hikeBodyShowsSunsetFromOneHour`
  - `ActivityChipsTest`(기기)
    - 칩 4개가 순서대로 보인다.
    - 고른 칩이 선택 상태다.
    - 낭독 이름이 맞다.
    - 다시 열어도 기억한다.
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
  - `RideController`
    - `prepare`: `ActivityProfile.of(r.activity)`로 세션을 만든다.
    - `speech`
      - `KmReached`는 종류별 문장이다.
        - 자전거는 `rideKmReport(event.km, avg(distanceM, movingTimeMs), movingTimeMs)`다.
        - 달리기·걷기는 `paceKmReport`다.
      - `Started`는 `recordingStarted(type, sunsetIn)`이다. 구간 나누기의 `startPhrase`는 지금처럼 그것이 이긴다.
    - 등산: tick마다 `HikeAnnouncer.due(now)`를 불러 `voiceOn`일 때 말한다.
      - Periodic: `hikeReport(stats.elapsedMs, stats.altitudeM, stats.ascentM, stats.distanceM, sunsetIn)`
      - Sunset: `sunsetCue`
    - 일몰
      - 첫 채택 위치에서 `SunTimes.sunset(lat, lon, localDate, zone)`로 계산하고 `setSunset`한다.
      - 그 뒤에는 1 km 넘게 움직였거나 날짜가 바뀌었을 때만 다시 계산한다.
      - 시작 안내가 위치보다 먼저 나오면 꼬리 없이 말한다.
    - `finish`: `RUN`이면 저장된 점(`rideStore.points`)으로 `BestEfforts.find`를 한 뒤 `saveBestEfforts`를 한다. 실패는 로그만 남긴다.
    - 길안내 km 원천(`becomeKmSource`)은 `BIKE`일 때만 맡는다. 다른 종류의 기록이 진행 중이면 길안내가 자기 km 안내를 한다(그런 경우는 P14로 생기지 않지만 지켜 둔다).
  - 기록 화면 위쪽 상태 줄의 배지와 이름은 Task 6에서 한다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 `ActivityChipsTest`와 `MainScreen` 관련 기존 클래스를 하나씩 돌린다.
- [ ] **Step 5: 커밋** `feat: choose activity before recording, per-type engine, voice and sunset alerts`

---

### Task 6: app — 기록 화면 숫자와 기록 요약

**Files:**
- Modify:
  - `app/.../ui/record/RideNumbers.kt`
    - `rideSummaryLine(stats, activity)`
    - `rideMapSummaryLine(stats, activity)`
    - `RideNumbersPage(stats, activity, sunsetMillis, nowMillis, dimmed)`
  - `app/.../ui/record/RecordScreen.kt`, `RidePager.kt`: 상태 줄 배지+이름. `RideUiState.Active.activity`를 넘긴다.
  - `app/.../ui/record/SegmentSummary.kt`: 위에 숫자 3칸(스펙 §2.2 표), 그 아래 지금의 나머지 칸
  - 길안내 화면의 기록 줄(`rideSummaryLine` 사용처)
- Test:
  - `app/src/test/.../ui/record/RideNumbersTextTest.kt`(새)
  - androidTest `app/src/androidTest/.../ui/record/RideNumbersPageTest.kt`(새)
    - 스크린샷 `v190-hike-numbers`
    - 스크린샷 `v190-run-numbers`

**Interfaces:**
- Consumes:
  - `RideStats.recentSpeedMps`, `lastLapMovingTimeMs`(Task 1)
  - `ActivityFormat`
  - `ActivityText.sunsetLine`, `sunsetWarning`, `ActivityBadge`(Task 5)
- Produces:
  - `fun cardCells(activity: ActivityType, distanceM: Double, movingTimeMs: Long, elapsedMs: Long, ascentM: Double, maxAltitudeM: Double?): List<Pair<String, String>>` in `ActivityText`
    - 스펙 §2.2 표의 3칸이다.
    - 칸 이름: `거리`, `이동 시간`, `평균 속도`, `평균 페이스`, `산행 시간`, `누적 상승`, `최고 고도`
    - 값의 단위는 지금 카드 형식을 따른다. 페이스는 `5'32"/km`다.
    - Task 7·9가 같이 쓴다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `RideNumbersTextTest`
    - `bikeLinesUnchanged`
    - `runSummaryLine`: `5.00km · 0:27:40 · 평균 5'32"/km`
    - `runMapLineStartsWithCurrentPace`: `recentSpeedMps` null → `--'--"/km · …`
    - `hikeSummaryLine`(P15)
    - `cardCellsPerType`: 4종류
    - `hikeCardUsesElapsed`
  - `RideNumbersPageTest`(기기)
    - 달리기: `현재 페이스`, `평균 페이스`, `직전 1 km`
    - 등산
      - `산행 시간`과 `현재 고도`가 같은 글자 크기다.
      - 일몰 줄이 보인다.
      - 일몰 50분 전이면 경고 색이다(`semantics` 확인 또는 색 비교).
      - 위치를 모르면 줄이 없다.
    - `sunsetLineAfterSunset`: `· 지남`
    - 스크린샷 2장
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
  - 칸 구성은 스펙 §3.2 표를 따른다.
  - 산행 시간은 `stats.elapsedMs`이고, `RideFormat.clock`으로 보인다.
  - 걷기의 `평균 속도`는 km/h다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 `RideNumbersPageTest`와 기존 기록 화면 클래스를 돌린다.
- [ ] **Step 5: 커밋** `feat(record): per-type numbers, sunset line and summary cells`

---

### Task 7: app — 자동 제목, 피드 카드, 기록 목록 카드

**Files:**
- Modify:
  - `core/.../video/RideTitle.kt`: `base(firstStartedAt, rideDays, zone, activity: ActivityType = BIKE)`, `shown(…, activity = BIKE)`
  - `core/.../export/ExportModels.kt`: `ExportLabels.rideName(date, activity = BIKE)`
  - `RideTitle.base`·`shown`·`rideName`을 부르는 곳 모두(피드, 여행 화면, 영상, 공유, 내보내기): 그 여행의 `ActivityMix.of(segments).dominant`를 넘긴다
  - `app/.../ui/feed/FeedViewModel.kt`
    - `FeedCardState`에 칸을 더한다.
      - `activities: List<ActivityType>`: 배지 순서
      - `dominant: ActivityType`
      - `hikeElapsedMs: Long`
      - `maxAltitudeM: Double?`
    - `routes` 색을 구간 종류의 `ActivityStyle.hex`로 바꾼다.
  - `app/.../ui/feed/FeedCard.kt`, `FeedFormat.kt`
    - 배지(34dp)
    - 숫자 3칸(`cardCells`)
    - 아래 줄(`averageLineText(activity, …)`)
  - `app/.../ui/feed/WeekStrip.kt`·VM의 목표 막대(P10)
  - `app/.../ui/history/HistoryFormat.kt` 등 기록 목록 카드·줄: 배지 24dp
  - `app/.../video/FeedThumbnailRenderer.kt`(카드 그림이 경로 색을 그리면): 종류 색
- Test:
  - `core/src/test/.../video/RideTitleTest.kt`
  - `core/src/test/.../export/ExportModelsTest.kt`(또는 기존 `ExportLabels` 시험)
  - `app/src/test/.../ui/feed/FeedViewModelTest.kt`
  - `app/src/test/.../ui/history/HistoryFormatTest.kt`
  - androidTest `app/src/androidTest/.../ui/feed/FeedCardActivityTest.kt`(새): 스크린샷 `v190-feed-cards`

**Interfaces:**
- Consumes: `ActivityMix`, `cardCells`, `ActivityBadges`, `ActivityStyle.hex`
- Produces:
  - `RideTitle.base(…, activity)`
    - 종류 낱말은 `라이딩`, `걷기`, `달리기`, `등산`이다.
    - 여러 날은 `N일 여행` 그대로다.
  - `ExportLabels.rideName`: `$date 주행`(자전거), `$date 걷기`, `$date 달리기`, `$date 등산`
  - `fun averageLineText(activity: ActivityType, avgSpeedMps: Double, avgBpm: Int?, ascentM: Double): String`
    - 자전거는 지금 결과 그대로다.
    - 달리기·걷기: `평균 5'32"/km · ♥ 150`
    - 등산: `상승 520 m · 평균 2.1 km/h`. ♥가 있으면 뒤에 ` · ♥ n`을 붙인다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `RideTitleTest`
    - `morningRun`: `아침 달리기`
    - `morningHike`: 09:00 출발 등산 → `아침 등산`(시간대 낱말은 지금 규칙 그대로)
    - `multiDayUnchanged`
  - `rideNamePerType`
  - `FeedViewModelTest`
    - `mixedTripBadgesAndDominant`
    - `hikeCardCells`
    - `routeColorsFollowSegmentActivity`
    - `weekGoalUsesBikeOnly`
  - `HistoryFormatTest`: 목록 줄 문구가 바뀌지 않는다(배지만 더해진다)
  - `FeedCardActivityTest`(기기)
    - 자전거·달리기·등산·섞인 카드 4장
    - 배지 낭독 이름
    - 겹친 배지 3개 상한
    - 스크린샷
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 `FeedCardActivityTest`와 피드 관련 기존 클래스를 돌린다.
- [ ] **Step 5: 커밋** `feat(feed): activity badges, per-type card numbers and titles`

---

### Task 8: app — 구간·여행 화면, 종류 바꾸기, 그래프

**Files:**
- Modify:
  - `app/.../ui/history/SegmentDetailScreen.kt`, `SegmentDetailViewModel.kt`
    - 배지
    - 종류별 합계 표
    - [⋮] `활동 종류 바꾸기`
  - `app/.../ui/history/TripDetailScreen.kt`, `TripDetailViewModel.kt`
    - 배지
    - 합계 표: 섞이면 자전거 줄
    - 그래프 이름·색(P7)
  - `app/.../ui/elevation/ChartText.kt`, `MetricChart.kt`, `ElevationMapViewModel.kt`, `ElevationMapScreen.kt`
    - 속도 탭의 페이스 표시
    - 종류별 색
    - 섞인 여행 범례 문구
  - `core/.../series/MetricSeries.kt`: 점마다 구간 종류로 `SpeedBand.of(kmh, activity)`를 칠한다(`segmentActivities: List<ActivityType>` 인자)
  - `app/.../ui/activity/ActivityText.kt`
    - `활동 종류 바꾸기`
    - 창 안내 `일시정지 중에 저장되지 않은 위치는 되살릴 수 없습니다.`
    - `종류마다 색 기준이 다릅니다`
- Test:
  - `core/src/test/.../series/MetricSeriesTest.kt`
  - `app/src/test/.../ui/history/SegmentDetailViewModelTest.kt`(없으면 새로)
  - `TripDetailViewModelTest.kt`
  - `app/src/test/.../ui/elevation/ChartTextTest.kt`, `ElevationMapViewModelTest.kt`
  - androidTest `app/src/androidTest/.../ui/history/ChangeActivityTest.kt`(새): 스크린샷 `v190-change-activity`

**Interfaces:**
- Consumes:
  - `ActivityChange.recompute`, `BestEfforts.find`, `RideStore.changeActivity`
  - `SpeedBand.of/legend(activity)`, `ActivityFormat`
- Produces:
  - `SegmentDetailViewModel`
    - `val canChangeActivity: StateFlow<Boolean>`: 끝난 구간(`endedAt != null`)일 때만 true
    - `fun changeActivity(type: ActivityType)`
      - 같은 종류면 아무것도 하지 않는다.
      - 아니면 점을 읽고, `Dispatchers.Default`에서 `recompute`와 (달리기면) `BestEfforts`를 돌린다.
      - 그 결과로 `changeActivity`를 부른다.
      - 화면은 저장소 흐름으로 다시 그려진다.
  - `ChartText`
    - 그래프 이름 `페이스`
    - 훑는 줄 `1.1km · 5'32"/km`
    - 낭독 `페이스 그래프, 평균 5'32", 최고 4'48"`
    - 이 문구는 스펙 §3.5 그대로다.
    - 위·아래 끝 글은 빠른 쪽이 위다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `MetricSeriesTest`: `bandsFollowSegmentActivity`(같은 9 km/h가 자전거 구간에서는 SLOW, 걷기 구간에서는 SPRINT)
  - `SegmentDetailViewModelTest`
    - `changeActivityHiddenWhileRecording`
    - `sameActivityWritesNothing`
    - `changeToRunSavesEfforts`
    - `changeFromRunClearsEfforts`
    - `tooShortChangesOnlyType`
  - `TripDetailViewModelTest`
    - `allRunTripShowsPaceChart`
    - `mixedTripShowsSpeedWithNote`
    - `mixedTotalsUseBikeRows`
    - `hikeTotalsStartWithElapsed`
  - `ChartTextTest`: `paceScrubLine`, `paceAnnouncement`
  - `ElevationMapViewModelTest`: `speedTabLegendForRun`, `mixedLegendNote`
  - `ChangeActivityTest`(기기)
    - [⋮] → 창
    - 안내 문구
    - 걷기 → 달리기 → 합계·제목·배지가 바뀐다
    - 스크린샷
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다. 합계 표의 줄 구성은 스펙 §3.2 마지막 묶음을 따른다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 `ChangeActivityTest`와 구간·여행·큰 지도 기존 클래스를 돌린다.
- [ ] **Step 5: 커밋** `feat(history): change activity, per-type totals and pace charts`

---

### Task 9: app — 통계 탭, 종류별 목표와 기록

**Files:**
- Modify:
  - `core/.../stats/StatsModels.kt`: `StatTotals.elapsedMs: Long = 0`
  - `core/.../stats/StatsCalculator.kt`: totals·buckets가 `elapsedMs`도 더한다
  - `core/.../stats/RideRecords.kt`
    - `RideRecordKind`에 `activity`와 `lowerIsBetter`를 단다.
    - `rideRecords(rides, efforts, activity)`
    - `newRecords(rides, efforts, segmentId)`
  - `core/.../stats/Goals.kt`: 필요하면 종류 인자만
  - `app/.../data/SettingsRepository.kt`
    - `goals: Map<ActivityType, GoalKm>`
    - `statsActivity: ActivityType?`(null = 전체)
    - 키는 스펙 §4.1·§4.2
    - `setGoals(activity, weekly, yearly)`
  - `app/.../ui/stats/StatsViewModel.kt`, `StatsScreen.kt`, `StatsFormat.kt`, `GoalCard.kt`, `GoalDialog.kt`, `BarChart.kt`(쌓은 막대)
  - `app/.../ui/cumulative/*`: 칩 거르기, 선 색
  - `app/.../ui/record/SummaryExtras.kt`: 신기록 줄·목표 줄(P16)
  - `app/.../ui/activity/ActivityText.kt`: 통계·기록 문구
- Test:
  - `core/src/test/.../stats/RideRecordsTest.kt`, `StatsCalculatorTest.kt`
  - `app/src/test/.../ui/stats/StatsViewModelTest.kt`
  - `app/src/androidTest/.../data/SettingsRepositoryGoalsTest.kt`
  - `app/src/test/.../ui/record/SummaryExtras*Test.kt`(있는 것)
  - androidTest `app/src/androidTest/.../ui/stats/StatsActivityTest.kt`(새): 스크린샷 `v190-stats-hike`

**Interfaces:**
- Consumes:
  - `RideStat.activity/elapsedMs/maxAltitudeM`
  - `RideStatsSource.observeBestEfforts`(Task 4)
  - `ActivityStyle`, `cardCells`
- Produces:
  - `RideRecordKind`
    - 자전거(지금 넷): `LONGEST_DISTANCE`, `LONGEST_TIME`, `MOST_ASCENT`, `FASTEST_AVG`
    - 달리기: `RUN_LONGEST_DISTANCE`, `RUN_BEST_1K`, `RUN_BEST_5K`, `RUN_BEST_10K`, `RUN_BEST_HALF`
    - 걷기: `WALK_LONGEST_DISTANCE`, `WALK_LONGEST_TIME`
    - 등산: `HIKE_MOST_ASCENT`, `HIKE_HIGHEST`, `HIKE_LONGEST_ELAPSED`
  - 기록은 같은 종류 구간끼리만 비교한다. `RUN_BEST_*`는 시간이 작을수록 좋다.
  - 문구는 `ActivityText`에 둔다.
    - 목록 이름: 스펙 §4.3 문구다. 달리기 최고 기록은 `1 km 최고 기록`, `5 km 최고 기록`, `10 km 최고 기록`, `하프 최고 기록`이다.
    - 신기록 줄
      - 자전거는 지금 넷 그대로다.
      - 달리기: `🏆 가장 긴 달리기 신기록`, `🏆 5 km 최고 기록 24'51"`
      - 걷기: `🏆 가장 긴 걷기 신기록`, `🏆 가장 오래 걸은 걷기 신기록`
      - 등산: `🏆 가장 많이 오른 등산`, `🏆 가장 높이 오른 등산`, `🏆 가장 긴 산행`
  - 달리기 최고 기록 시간 형식: 1시간 미만 `24'51"`(분'초"), 이상 `1:52'10"`

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `RideRecordsTest`
    - `bikeRecordsUnchangedWithOtherTypes`: 기존 자전거 구간에 더 긴 걷기를 더해도 자전거 기록이 같다
    - `runBestEffortRecord`
    - `bestEffortLowerIsBetter`
    - `hikeRecords`
    - `newRecordComparesSameTypeOnly`
  - `StatsCalculatorTest`: `elapsedSummed`
  - `StatsViewModelTest`
    - `chipFiltersRides`
    - `chipRemembered`
    - `allChipStacksBarsByActivity`
    - `streakAllCountsAnyActivity`
    - `streakHikeOnlyCountsHikes`
    - `runCellsShowPace`
    - `hikeCellsShowElapsedAndAscent`
    - `allChipShowsGoalRowPerActivityWithGoal`
    - `noGoalsShowsSetGoal`
    - `ridesWordPerChip`: `라이딩 N회`, `걷기 N회`, `달리기 N회`, `등산 N회`, `활동 N회`
  - `SettingsRepositoryGoalsTest`(기기)
    - `bikeGoalKeepsOldKeys`
    - `walkGoalUsesOwnKeys`
  - `StatsActivityTest`(기기)
    - 칩
    - 등산 칸
    - 목표 줄
    - 기록 소제목([전체])
    - 스크린샷
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
  - 거르기는 `StatsCalculator` 밖(VM)에서 한다. `StatsCalculator`는 받은 목록만 계산한다.
  - 누적 지도도 같은 칩으로 거르고, 선 색은 종류 색이다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 `StatsActivityTest`, `SettingsRepositoryGoalsTest`, 기존 통계 클래스를 돌린다.
- [ ] **Step 5: 커밋** `feat(stats): activity chips, per-type goals and records`

---

### Task 10: app — 영상·요약 이미지, 공유 글, 해시태그 설정

**Files:**
- Modify:
  - `core/.../video/CameraPath.kt`: `zoomForSpeed(kmh, slowKmh = 10.0, fastKmh = 30.0)`, `cameras(…, profile)`
  - `core/.../video/Hud.kt`
    - `HudValues`에 `elapsedMs: Long`, `ascentM: Double`를 더한다(P11).
    - `segmentActivity`를 넘긴다.
  - `app/.../video/VideoInputs.kt`
    - `VideoInputs`에 `activity: ActivityType`(대표), `segmentActivities: List<ActivityType>`를 더한다.
    - `TripTotals`에 `hikeElapsedMs`, `maxAltitudeM`, `fastestKmMs: Long?`를 더한다.
  - `app/.../video/FrameRenderer.kt`
    - 숫자 띠
    - `rideCells(totals, activity)`
    - 제목 옆 배지
  - `app/.../video/SummaryImageRenderer.kt`
  - `app/.../share/ShareText.kt`
    - `ShareFacts`에 `activity`, `elapsedMs`, `maxAltitudeM`를 더한다.
    - 숫자 줄은 종류별이다.
  - `app/.../ui/share/ShareViewModel.kt`: 종류의 기본 해시태그
  - `app/.../data/SettingsRepository.kt`
    - `shareHashtagsWalk/Run/Hike: String?`
    - `fun AppSettings.hashtagsFor(type): String`: 처음 값은 스펙 §5.2
  - `app/.../ui/settings/SettingsScreen.kt`, `ShareHashtagsDialog.kt`, `SettingsViewModel.kt`: 네 줄
- Test:
  - `core/src/test/.../video/CameraPathTest.kt`, `HudTest.kt`
  - `app/src/test/.../video/*`(rideCells 시험이 있는 곳)
  - `app/src/test/.../share/ShareTextTest.kt`
  - `app/src/androidTest/.../data/SettingsRepositoryShareHashtagsTest.kt`
  - `app/src/androidTest/.../ui/settings/*`(해시태그 줄)

**Interfaces:**
- Consumes: `ActivityProfile.cameraSlowKmh/FastKmh`, `ActivityFormat`, `ActivityMix`, `ActivityBadge` 그리기 값(`ActivityStyle`)
- Produces:
  - `FrameRenderer.rideCells(totals, activity)`: 스펙 §5.1 표. 자전거는 그대로다.
  - 숫자 띠
    - 그 프레임 점의 구간 종류를 따른다.
    - 달리기·걷기는 `페이스`(`5'32"`, 단위 `/km`)다.
    - 등산은 `산행 시간`(`H:MM:SS`), `고도`, `누적 상승`이다.
  - `autoShareText(facts, defaultTags)`: 호출자가 `hashtagsFor(dominant)`를 넘긴다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `CameraPathTest`
    - `bikeZoomUnchanged`
    - `walkZoomUsesTwoToSix`: 4 km/h → 가운데 확대
  - `HudTest`
    - `hikeElapsedFromSegmentStart`
    - `ascentAccumulates`
  - `rideCells`
    - `runCellsWithFastestKm`
    - `runWithoutFastestKmDropsCell`
    - `walkCells`
    - `hikeCells`
    - `bikeCellsUnchanged`
  - `ShareTextTest`
    - `runNumbersLine`: `5.00 km · 27분 · 평균 5'32"/km`. 거리·시간 형식은 지금 공유 글 규칙이다
    - `hikeNumbersLine`: `산행 3시간 20분 · 상승 520 m · 최고 836 m`
    - `bikeLineUnchanged`
  - `SettingsRepositoryShareHashtagsTest`(기기)
    - `defaultsPerType`
    - `bikeKeyUnchanged`
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
  - 섞인 여행은 숫자 띠만 그 순간 구간 종류를 따른다.
  - 마지막 카드·요약 이미지·공유 글은 대표 종류다.
- [ ] **Step 4:** JVM 전체를 돌린다. S22에서 영상·공유·설정 기존 클래스를 하나씩 돌린다(`ui/video`, `video`, `ui/share`, `share`, `ui/settings`).
- [ ] **Step 5: 커밋** `feat(video,share): per-type numbers, camera speeds and default hashtags`

---

### Task 11: GPX 가져오기·내보내기의 종류

**Files:**
- Modify:
  - `core/.../importing/ImportRules.kt`
    - `enum Activity`를 없앤다.
    - `activityOf(type): ActivityType`
    - `RideStatus.NotCycling`을 없앤다.
    - `statuses(spans, existing)`
  - `core/.../importing/ImportStore.kt`: `ImportedRide.activity`
  - `app/.../importing/ImportScanner.kt`: `ScanRow.activity: ActivityType`, 그 종류로 `RideReplay.replay(…, activity)`
  - `app/.../importing/GpxImportJob.kt`
    - 고른 종류로 다시 계산한다.
    - `RUN`이면 `BestEfforts.find` → `saveBestEfforts`
  - `app/.../data/RoomImportStore.kt`: 종류를 저장한다
  - `app/.../ui/importing/ImportViewModel.kt`, `ImportScreen.kt`
    - 줄마다 배지
    - 눌러서 종류를 고르는 창
    - `fun setActivity(index: Int, type: ActivityType)`(P9)
  - `core/.../export/ExportModels.kt`: `ExportSegment.activity: ActivityType = BIKE`
  - `core/.../export/GpxWriter.kt`: P8
  - `app/.../export/TripExporter.kt`: 구간 종류를 넘긴다
- Test:
  - `core/src/test/.../importing/ImportRulesTest.kt`
  - `core/src/test/.../export/GpxWriterTest.kt`
  - `app/src/test/.../importing/*`
  - `app/src/test/.../ui/importing/ImportViewModelTest.kt`
  - androidTest
    - `app/src/androidTest/.../data/RoomImportStoreTest.kt`
    - `app/src/androidTest/.../ui/importing/*`: 배지와 바꾸기

**Interfaces:**
- Consumes: `ActivityType.gpxType`, `RideReplay.replay(activity)`, `BestEfforts`, `RideStore.saveBestEfforts`
- Produces:
  - `ImportRules.activityOf`
    - 지금의 자전거 판단은 그대로다.
    - `running`/`run` 포함 → `RUN`
    - `walk` 포함 → `WALK`
    - `hik` 포함 → `HIKE`
    - 그 밖(빈 값·숫자·모름) → `BIKE`
  - 지금 `Activity`를 정하던 글자 규칙을 옮긴다.
  - `isSelectable()`은 `status == RideStatus.New`다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `ImportRulesTest`
    - `readsTypes`: running, walking, hiking, 없음, `9`
    - `noNotCyclingStatus`
  - `GpxWriterTest`
    - `bikeOnlyTripUnchanged`: V1.8.0 출력과 같다
    - `typeChangesStartNewTrk`: 자전거, 자전거, 걷기 → `<trk>` 2개, `cycling`/`walking`
  - `ImportViewModelTest`
    - `rowStartsWithFileType`
    - `setActivityChangesRow`
    - `hikingFileIsSelectable`
  - `GpxImportJob` 시험
    - `importsWithChosenActivity`
    - `runImportSavesEfforts`
  - `RoomImportStoreTest`(기기): `savesActivity`
- [ ] **Step 2:** 시험이 실패하는지 확인한다.
- [ ] **Step 3:** 구현한다.
- [ ] **Step 4:** JVM 전체를 돌리고, S22에서 가져오기·내보내기 기존 클래스를 하나씩 돌린다.
- [ ] **Step 5: 커밋** `feat(gpx): import and export activity types`

---

### Task 12: 버전·문서 (part A). 기기 전체 시험·설치는 최종 수정 뒤(part B)

**Files:**
- Modify:
  - `app/build.gradle.kts`: verMinor 9, verPatch 0 → 10900, `1.9.0-dev`
  - `AppVersionTest`
  - `SettingsScreenTest`: `V1.9.0`
  - `CHANGELOG.md`: `## [1.9.0] - 개발 중`
  - `README.md`
    - 현재 버전 V1.9.0(개발 중)
    - 로드맵: 스펙 §8 순서
    - 문서 목록
  - `docs/VERSIONING.md`: 로드맵 표
- Create: `docs/verification/activity-types-field-test.md`
  - JVM 결과
  - S22 자리: "최종 수정 뒤 기록"
  - 스펙 §7.3 점검표 5항목

- [ ] **Step 1:** 버전과 시험 두 개를 고친 뒤 `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test`를 돌린다.
- [ ] **Step 2:** 문서를 고친다.
- [ ] **Step 3:** S22에서 `SettingsScreenTest`만 돌린다.
- [ ] **Step 4: 커밋** `docs: V1.9.0 version, changelog, README and verification`
- [ ] **(part B, 컨트롤러가 최종 검토와 수정 뒤에 함)**
  1. S22 androidTest 전체를 클래스 하나씩 돌린다.
  2. `v190-*.png` 6장을 받아 확인한다.
  3. S22에 `installDebug`하고 시험용 기록 사본(`scratchpad/s22-seed2/bikerecoder.db`)을 다시 넣는다. 9판으로 옮겨지는지 본다.
  4. 지도 칩, 피드 카드, 구간 화면 종류 바꾸기, 통계 칩을 확인한다.
  5. 검증 문서에 결과를 적고 커밋한다.
