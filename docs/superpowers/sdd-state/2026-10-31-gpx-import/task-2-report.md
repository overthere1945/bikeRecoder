# Task 2 보고 — 다시 계산과 판단 규칙

## 파일 (모두 core, 패키지 `com.cowork.bikerecoder.core.importing`)
- `core/src/main/kotlin/.../importing/RideReplay.kt` — `ReplayedRide`, `RideReplay.replay`
- `core/src/main/kotlin/.../importing/ImportRules.kt` — `Activity`, `TimeSpan`, `RideStatus`, `ImportRules`(activityOf, overlaps, existingSpans, statuses, previewPoints)
- `core/src/main/kotlin/.../importing/ImportStore.kt` — `ImportedRide`, `ImportStore`(Android 타입 없음, kotlinx Flow만)
- 시험: `RideReplayTest`(5), `ImportRulesTest`(8)

## 설계 메모
- replay: `start()` → 각 fix `onFix(fix, fix.timeMillis)`의 non-null `point` 수집 → `stop(마지막 시간).stats.toTotals()`. `onTick` 없음. 채택 점 < 2 또는 거리 < `MIN_RIDE_DISTANCE_M`이면 null.
- `statuses`: 앞선 읽힌 주행과의 겹침은 앞선 것의 상태(기존과 겹침/자전거 아님 등)와 상관없이 비교한다(읽힌 모든 앞선 span을 `seen`에 누적). 스펙의 "먼저 나온 것만 새 주행"을 단순·결정적으로 구현한 해석.
- null span(읽을 수 없음)은 `Unreadable(UNREADABLE)` 자리표시자를 돌려주고 중복 비교에서 빠진다. 호출자가 실제 사유로 덮어쓴다. 활동이 null이면 자전거로 취급.
- `statuses`는 `spans.size == activities.size`를 `require`한다.
- `matchesLiveRecording`은 `GpxParser.parse(follow.gpx)`로 같은 호출 순서의 직접 실행과 totals·points를 비교. `shuffledPointsGiveSameResult`는 trkpt 줄을 섞고 1/5을 복제한 GPX 텍스트를 `GpxReader.read`에 통과시킨 뒤 replay 결과 전체(`ReplayedRide`)가 같음을 검사.

## 시험
- `./gradlew :core:test --tests '*importing*'`: importing 4개 클래스 51개(Unpacker 17, Rules 8, Replay 5, Reader 21) 모두 통과.
- `./gradlew :core:test` 전체 통과.

## 우려
- 시험을 구현 뒤에 같이 작성해 "먼저 실패 확인" 단계는 건너뜀(첫 실행에 통과). 시험 내용은 브리프의 이름·조건 그대로.
- `pauseIsNotMovingTime`은 합성 경로(1분/5분 정지/1분)에서 이동 시간이 105~135초 범위에 들어옴을 확인(허용 ±15초).
