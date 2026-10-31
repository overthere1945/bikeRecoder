# Task 6 report: plan preview time and chart, guidance strip and gradient line

Status: DONE_WITH_CONCERNS (small deviations below, none blocking) — commit 6a1559b

## Files
- core main: `format/SummaryFormatter.kt` — new `durationOf(seconds)` ("약 1시간 13분", minutes rounded, min 1); `duration(m, speed)` now delegates to it.
- core test: `format/SummaryFormatterTest.kt` (+1).
- app main:
  - `ui/plan/PlanState.kt` — `PlanUiState.usualSpeedMps` (default 15 km/h), `elevation: ElevationProfile?`, `durationSeconds: Double?`.
  - `ui/plan/PlanViewModel.kt` — new params `usualSpeed: suspend () -> Double`, `handoff: ElevationHandoff`, `compute: CoroutineDispatcher`.
    Usual speed read once at creation (`async`; a failure → `UsualSpeed.DEFAULT_MPS`), written to the state. On a route success the profile is built
    on `compute` (`ElevationProfile.fromRoute`), the time is `RideTimeEstimator.seconds(profile, 0, summary.totalDistanceM, usual)` (waits for the
    usual speed, so the time is shown once), published together with Ready; any input change clears both. `openElevation()` puts
    `(profile, route.points)` into the handoff and returns true, false without a ready route with elevation. Factory: usual speed =
    `UsualSpeed.of(container.rideStore.recentSegments(UsualSpeed.RECENT))` on `Dispatchers.IO`; handoff = `container.elevationHandoff`.
  - `ui/plan/PlanScreen.kt` — `onOpenElevation` param; summary line 1 uses `durationOf(durationSeconds)`, a third line `평소 속도 n km/h 기준`
    (bodySmall, onSurfaceVariant); under the summary a 100dp chart slot: `ElevationChart(elevation, height = 100.dp, onOpen)` when Ready
    (null profile → "고도 정보가 없습니다"), empty 100dp otherwise. Tags `PlanTags.USUAL_SPEED`, `PlanTags.CHART_SLOT`.
  - `ui/BikeNavHost.kt` — plan `onOpenElevation = { if (isCurrent()) navController.openElevation(ElevationSource.Plan) }` (→ `elevation/plan/0`).
  - `nav/NavigationController.kt` — new param `recentSegments: suspend (Int) -> List<RideSegment>` (container: `rideStore.recentSegments` on IO);
    `prepare()` computes `UsualSpeed.of(recentSegments(UsualSpeed.RECENT))` (failure logged → default) and passes it as `usualSpeedMps`.
  - `ui/navigate/NavigateViewModel.kt` — top-level `AheadPanel` + `aheadPanel(nav)` (null without profile or `ahead`), `OFF_ROUTE_M = 40.0`.
  - `ui/navigate/NavigateScreen.kt` — `BottomPanel` (now internal): above the numbers row, when `aheadPanel` is non-null, the line (titleSmall,
    tag `NavigateTags.AHEAD_LINE`) and `ElevationStrip(strip, reachesDestination, dimmed)`; `TurnCard` internal (for the screenshot).
    Map: `remember(nav.route, nav.profile) { routeColors(nav.route, nav.profile, flatHex = ROUTE_COLOR) }` → `MapOverlay.routeColors`.
- tests:
  - JVM `PlanViewModelTest` (+7: `durationUsesUsualSpeedAndClimbs`, `noElevationUsesUsualSpeedOnly`, `usualSpeedLine`, `aFailedUsualSpeedReadUsesTheDefault`,
    `elevationAndDurationAreClearedWhileRecomputing`, `openElevationPutsHandoff`, `openElevationWithoutElevationPutsNothing`); the existing tests pass
    `compute = Dispatchers.Main` (the test main dispatcher).
  - JVM `NavigationControllerTest` (+2: `sessionGetsUsualSpeed` — asks for `RECENT`, first ETA = `RideTimeEstimator.seconds(profile, d, total, 5 m/s)`;
    `aFailedUsualSpeedReadStartsWithTheDefault`).
  - JVM new `ui/navigate/AheadPanelTest` (6: three lines, 2 km strip, destination flag at d + 2 km ≥ total, dimmed rerouting / offset ≥ 40 m, null without elevation).
  - androidTest `PlanScreenTest` (+4: `summaryShowsUsualSpeedAndChart`, `noElevationText`, `noRouteLeavesTheChartSlotEmpty`, `screenshot` → `v170-plan`).
  - androidTest new `ui/navigate/NavigateScreenTest` (5: `aheadLineClimbAhead`, `aheadLineInClimb`, `aheadLineFlat`, `stripHiddenWithoutElevation`, `screenshot` → `v170-navigate`).

## Decisions
- **State field name:** `PlanUiState.profile` already is the `RouteProfile`, so the brief's `profile: ElevationProfile?` is named `elevation`.
- **Time format:** the existing `SummaryFormatter.duration` takes metres + speed; added `durationOf(seconds)` in core with the same rounding/format, `duration` delegates (old tests unchanged and green).
- **Total for the time:** `route.summary.totalDistanceM` (the distance shown next to it).
- **Navigation line colour:** the guidance route line is the single `ROUTE_COLOR` (#1E88E5) layer, so `flatHex = ROUTE_COLOR`. Route and colours come from the same `NavState` in the same composition, so a reroute swaps both together; the list is remembered on (route, profile).
- **Off-route flag:** `NavState` only has `rerouting`; there is no off-route flag. Dimmed = `rerouting || progress.lateralOffsetM >= 40 m` (the session's off-route distance, an app constant `OFF_ROUTE_M` since core's is private). This dims at once when 40 m off, before the session's 8 s confirmation.
- **reachesDestination** uses the route's total (`cumulativeM.last()`), per the brief's `d + 2_000 ≥ total`.
- **Plan layout:** idle/loading/error keep a blank third summary line and an empty 100dp chart slot so nothing jumps (same idea as the existing blank second line).
- **Screenshots:** both use stateless content with a grey placeholder map (as Task 5); the navigate one is `TurnCard` + `BottomPanel`, not the full `ActiveContent` (that needs the NavigationController and a real MapLibre map).

## Results
- PC: `./gradlew :core:test :app:testDebugUnitTest` green — core 521, app 1166 tests, 0 failures.
- S22 (R5CT43TFS9A), one class per run: `PlanScreenTest` 8/8, `NavigateScreenTest` 5/5.
  Screenshots `Download/bikeRecoderTest/v170-plan.png` and `v170-navigate.png` checked visually (plan: "20.0km · 약 1시간 13분", "평소 속도 18 km/h 기준", 100dp chart with [지도에 표시]; navigate: "300m 앞 오르막 · 1.2km · 평균 6%" above the coloured strip with the rider dot, numbers and buttons below).
- S25 not touched.

## Deviations / concerns
- `elevation` instead of `profile` in `PlanUiState` (name clash, see above).
- Core touched for `SummaryFormatter.durationOf` (not in the brief's file list).
- Off-route dimming is the 40 m offset heuristic (no off-route state exists).
- `routeColors` on a reroute runs on the main thread inside `remember` (one `String.format` per point; ~10k points on a long route). Only on route/profile change, but could be precomputed per band if it shows up as jank.
- The plan screen loses ~120dp of map height to the third line and the chart slot (spec §2.5 asks for both).

## Fix round 1
Commit: 58817e7 (`app: elevation — Task 6 review fixes`).

1. **Usual speed re-read per route (Important):** `PlanViewModel` no longer reads the usual speed once. Every `recompute()` Success reads it again (`readUsualSpeed()`), and `publish` sets `usualSpeedMps`, `elevation` and `durationSeconds` together. A Ready state's speed is the one its time was computed with. `usualSpeed` is now a stored param. New JVM test `everyRouteReadsTheUsualSpeedAgain`: the recent segments change after the first read, and the next recompute shows the new speed and time (2 reads). `usualSpeedLine` now waits for a Ready route.
2. **Timeout:** the read runs in `withTimeoutOrNull(USUAL_SPEED_TIMEOUT_MS = 2_000)`. On timeout it uses the default speed and logs. New test `aHungUsualSpeedReadTimesOutToTheDefault`: still Loading after the debounce, then Ready at the default speed after 2 s.
3. **Log:** new `log: WarnLog = androidWarnLog(TAG)` param. A failed read logs `log.warn(..., e)` and a timeout logs too. `aFailedUsualSpeedReadUsesTheDefault` now asserts one log message. Tests pass `RecordingLog`.
4. **RouteColors:** `bandHex` reads `BAND_HEX`, which is `GradeBand.entries.associateWith { String.format(...) }` computed once. The output is unchanged.
5. **Off-route constant:** core `OffRouteDetector` has `companion object { const val DEFAULT_THRESHOLD_M = 40.0 }` and uses it as the constructor default. `NavigationSession.OFF_ROUTE_THRESHOLD_M` now refers to it. The app's `aheadPanel` uses it, and the duplicated `OFF_ROUTE_M` is gone. The rules are still 40 m / 8 s / 15 s.
6. **Plan layout:** the plan screen's bottom Column has `verticalScroll(rememberScrollState())`, so many stops or a large font can't push [안내 시작] off-screen. The stop list's LazyColumn keeps its `heightIn(max = 232.dp)` bound, so nesting it is legal. Nothing moves when the content fits: the screenshot looks the same.

Results: PC `:core:test :app:testDebugUnitTest` green: core 521, app 1168, 0 failures. On the S22, one class at a time: `PlanScreenTest` 8/8, `NavigateScreenTest` 5/5. The S25 was not touched.
