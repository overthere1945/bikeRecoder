# Task 2 보고 — core 오르막, 평소 속도, 시간, 길안내 연결

상태: DONE_WITH_CONCERNS (작은 우려만, 아래)
커밋: ccf60a2 `core: elevation — climbs, usual speed, climb-aware ETA and climb voice` (BASE 5973643, push 안 함)

## 만든 것
- `core/.../elevation/ClimbFinder.kt` — `Climb`(lengthM = endM - startM), `ClimbFinder.find(profile)`, 상수 3.0 / 50.0 / 200.0 / 4.0.
  시험용 `internal fun findIn(distancesM, elevationsM, gradesPct)`(본체).
- `core/.../elevation/UsualSpeed.kt` — `DEFAULT_MPS = 15/3.6`, `RECENT = 10`, `MIN_DISTANCE_M = 1000`, `of(segments)`.
- `core/.../elevation/RideTimeEstimator.kt` — 상수 0.12 / 0.03 / 6 km/h / 1.3, `seconds(profile, fromM, toM, baseMps)`, 공개 `speedMps(g, base)`.
- `core/.../navigation/AheadOfRider.kt` — `AheadState`(InClimb / ClimbAhead / Flat), `state`, `strip`, `WINDOW_M = 2000`.
- `core/.../navigation/ClimbAnnouncer.kt` — `reset()`, `onProgress(climbs, profile, d)`.
- `KoreanPhrases.climb(distanceM, lengthM, avgGradePct)`.
- `RideStore.recentSegments(limit)`. 구현은 Room DAO `recentSegments(minDistanceM, limit)`(`endedAt IS NOT NULL AND distanceM >= 1000 ORDER BY startedAt DESC, id DESC LIMIT`)와 `RoomRideStore`. 가짜 저장소 `FakeRideStore`(app test)와 `ReadOnlyRides`(androidTest)도 같은 규칙으로 맞췄다. `by` 위임 객체들은 손대지 않았다.
- `NavigationSession`:
  - 생성자에 `usualSpeedMps: Double = UsualSpeed.DEFAULT_MPS`(마지막 매개변수)를 넣고 `EtaEstimator(defaultSpeedMps = usualSpeedMps)`.
  - `init`과 재탐색 성공 때 `setElevation(route)`: `ElevationProfile.fromRoute`, `ClimbFinder.find`.
  - 도착 예정 = `fix.timeMillis + RideTimeEstimator.seconds(profile, d, route.cumulativeM.last(), eta.baseSpeedMps(now)) × 1000`.
  - 오르막 음성 `withClimbVoice`: announcer가 고른 오르막은 `pendingClimb`에 둔다. 이번 결과에 TURN이 있으면 미루고, 없으면 `Utterance(phrases.climb(gap, …), PERIODIC)`를 끝에 붙인다. gap < 0이면 버린다. 도착하면 `reset()`.
  - `NavState`에 `profile`, `ahead` 추가(기본 null). profile이 없으면 둘 다 null.
- 앱 `NavigationController`는 손대지 않았다(기본 평소 속도로 컴파일). `EtaEstimator`는 바꾸지 않았다.

## 결정
- **오르막 묶음은 칸 모형으로 정했다.** `bands`와 같이 표본마다 이웃 표본과의 중간점까지를 칸으로 본다(첫 칸은 0부터, 마지막 칸은 끝까지).
  - startM/endM은 칸 경계이다.
  - 끼는 3% 미만 구간의 길이는 다음 묶음 시작에서 앞 묶음 끝을 뺀 값이다.
  - 길이와 평균은 모두 실제 거리 차이로 잰다. 그래서 짧은 마지막 칸도 맞다.
  - 경계 비교에는 1e-9 여유를 둔다(200 m, 4.0%, 50 m 미만).
- **오른 높이는 칸 경계에서 보간한다.** gainM = `elevationAt(endM) - elevationAt(startM)`(다듬은 고도의 선형 보간)이고, maxGradePct는 묶음 안 표본의 100 m 경사 최댓값이다.
- **ClimbFinder 경계 시험은 배열로 했다.** 49/50 m, 199/200 m, 3.9/4.0%는 `findIn`에 표본 배열을 직접 넣어 시험한다. 10 m 격자와 다듬기를 거치면 이 경계를 정확히 만들 수 없기 때문이다. 49 m 끼는 칸은 불규칙 격자(300, 349, 398)로 만들었다. `steadyClimbFound`와 `descentsIgnored`는 실제 단면(`fromRoute`)으로 시험한다.
- **RideTimeEstimator는 칸마다 계산한다.** 격자 칸과 [from, to]가 겹치는 부분마다 그 가운데의 `gradeAt`로 속도를 정한다. 단면 밖 거리(경로 길이와 단면 길이 차이)는 base로 계산한다. `toM ≤ fromM`이면 0을 돌려준다(기존 `remainingM`의 0 하한과 같다).
- **ClimbAnnouncer:**
  - 단면이 바뀐 것은 객체 동일성(`!==`)으로 알아낸다. 세션이 경로마다 새 단면을 만든다.
  - 처음 본 gap은 오르막 startM을 열쇠로 기억한다.
  - 말한 오르막의 시작 좌표 `profile.pointAt(startM)`는 길안내가 끝날 때까지 기억한다. 50 m 안이면 같은 오르막이다.
  - 한 번에 하나만 돌려준다.
- `AheadOfRider.state`: InClimb은 `startM ≤ d < endM`이다. ClimbAhead는 `0 < startM - d ≤ 2000` 중 가장 가까운 오르막이다.

## 시험
- 새 시험과 추가 시험:
  - ClimbFinder 9, UsualSpeed 5, RideTimeEstimator 7(브리프 6 + 짧은 마지막 칸 `shortTailCellCounted`), AheadOfRider 5, ClimbAnnouncer 5, KoreanPhrases `climbPhrase` 1.
  - NavigationSession 6: 브리프 5 + `climbVoiceSpokenOnceAtThreeHundred`.
  - RoomRideStore `recentSegmentsNewestFirstOverOneKm` 1.
- 시험 도우미:
  - `core/src/test/.../elevation/ElevationTestRoutes.kt`(북쪽 경로 + 고도).
  - `navigation/TestRoutes.kt`에 `eastRouteWithElevation`.
- 실패 확인: 구현 전 컴파일 실패를 확인했다.
- `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` → 통과. core+brouter는 516건, app은 1127건이고 실패는 0이다.
- S22 `R5CT43TFS9A`에서 `RoomRideStoreTest` 12/12 통과(새 시험 포함). S25에는 손대지 않았다.

## 우려 (작음)
1. **미룬 오르막 음성은 재탐색되면 버린다.** 거리 축이 이전 경로 기준이라서다. 그런데 announcer는 그 오르막을 이미 "말함"으로 기억하므로, 새 경로에서도 말하지 않는다. TURN과 겹친 바로 다음 위치에서 재탐색이 끝나는 드문 경우에만 생긴다.
2. **오르막 시작이 스펙 그림보다 조금 이르게 잡힌다.** 100 m 경사 창 때문에 3% 이상 구간이 실제 경사 시작보다 앞뒤로 조금 퍼진다. 예를 들어 1,100 m에서 시작하는 6% 오르막은 startM이 약 1,095 m로 잡혔다. 스펙 정의(100 m 경사 기준)대로라서 고치지 않았다.
3. **`descentM`은 이번 작업에서 쓰지 않는다.** 스펙 §1.5는 표시(Task 5) 몫이다.

## Fix round 1

커밋: 4dfa583 `core: elevation — Task 2 review fixes` (BASE 114f92f, `core/.../export`는 손대지 않음)

1. `NavigationSessionTest.profileFollowsReroutedRoute`:
   - 고도 있는 처음 경로(300~900 m, 6%)에서 deviate GPX로 재탐색하면, 고도 있는 새 경로(1,000~1,600 m, 5%)로 바뀐다.
   - `state.profile.totalM == newRoute.cumulativeM.last()`이고, `ahead == AheadOfRider.state(새 경로 오르막, d)`(ClimbAhead)인지 확인한다.
   - 변이 확인: `setElevation(newRoute)`를 주석 처리하면 이 시험이 실패했다. 확인 뒤 원래대로 되돌렸다.
2. `NavigationSessionTest.deferredClimbDroppedOncePassed`:
   - 회전 안내 때문에 오르막 음성을 미룬다.
   - 다음 위치가 `startM + 10`, 그다음이 `+30`일 때 오르막 음성이 0개인지 확인한다.
3. `RideTimeEstimatorTest.downhillFivePercentIsOnePointOneFive`: -5%에서 `speedMps = base × 1.15`(5.75 m/s)이고, 1 km가 1000/5.75초인지 확인한다.
4. `ClimbFinderTest.averageIsGainOverLength`:
   - 4% 표본 3개(100 m 간격)와 10% 표본 10개를 쓴다. 표본 평균은 약 8.6%이다.
   - gainM 17.7, avgGradePct 5.9(= 오른 높이 ÷ 길이), maxGradePct 10인지 확인한다.
5. **`recentSegments` 거르기:**
   - DAO에 `AND movingTimeMs > 0`을 더했다. `FakeRideStore`와 `ReadOnlyRides`도 같게 맞추고, `RideStore` KDoc도 고쳤다.
   - `RoomRideStoreTest.recentSegmentsNewestFirstOverOneKm`에 이동 시간 0인 구간(3 km, 가장 최근 쪽)이 빠지는지 확인하는 줄을 더했다.
   - 스키마는 바꾸지 않았다.
6. `ClimbAnnouncer` KDoc: 회전 안내로 미룬 음성이 곧이은 재탐색으로 버려져도 말한 것으로 남는다는 것, 그리고 그래도 되는 이유(새 경로의 첫 gap이 300 m 미만, §3.3)를 적었다.

시험:
- `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 통과: core 520건, app 1127건, 실패 0.
- S22 `RoomRideStoreTest` 12/12 통과.
