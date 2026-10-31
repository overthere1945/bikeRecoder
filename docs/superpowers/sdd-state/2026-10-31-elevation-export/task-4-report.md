# Task 4 report — app: elevation chart, gradient route line, "고도" map screen

Status: DONE_WITH_CONCERNS
Commit: `bf4e547` app: elevation — chart, gradient route line and elevation map screen (base 4dfa583)

## Files
New (main):
- `app/.../ui/elevation/ElevationText.kt`: `NO_ELEVATION`, `SHOW_ON_MAP`, `TITLE`, `CLOSE`(`닫기`), `scrubLine`, `chartDescription`, `usualSpeedLine`, `aheadLine(AheadState)`, `legend(band)`, `kmTick(km)`; and `internal fun kmTickStepKm(totalM)`.
- `app/.../ui/elevation/ElevationChart.kt`: `ElevationChart`, `ElevationStrip`, `ElevationTags` (and the `ElevationTags.Dimmed` semantics key), `GradeBand.color()`.
- `app/.../ui/elevation/ElevationHandoff.kt`: `ElevationSource` (Trip/Segment/Plan) and `ElevationHandoff` (AtomicReference, `take()` = getAndSet(null), like `ImportHandoff`).
- `app/.../ui/elevation/ElevationMapViewModel.kt`: `ElevationMapUiState` (Loading / Ready(profile, paths, colors, scrubM) / Closed), VM, `factory(container)`, `kindAndId`/`sourceOf`.
- `app/.../ui/elevation/ElevationMapScreen.kt`: `ElevationMapScreen`, `ElevationMap`, `ElevationMapContent`.
- `app/.../map/RouteColors.kt`: `routeColors(route, profile, flatHex)`, `pathColors(paths, profile, flatHex = null)`, `bandHex(band, flatHex)`.

Modified:
- `map/BikeMap.kt`: `MapOverlay.routeColors`, `marker`, `endpoints`; the route source now uses `colorRuns` (fallback = route blue) and the route layer colours by `coalesce(get("color"), ROUTE_COLOR)`; new `bike-marks` source + circle layer (above photos, below the user dot); `ROUTE_COLOR` is now `internal` (Task 6 needs it as `flatHex`); `routeGeoJson` built off the main thread; `colorRuns` got a `fallback` param.
- `ui/BikeNavHost.kt`: `Routes.ELEVATION = "elevation/{kind}/{id}"`, `Routes.elevation(source)`, public `fun NavController.openElevation(source)`, the composable (pop guarded by `isCurrent`).
- `AppContainer.kt`: `val elevationHandoff = ElevationHandoff()`.

Tests:
- JVM: `map/RouteColorsTest` (`flatUsesOverride`, `climbPointsColored`, `nullProfileGivesNull`, + `pathColorsFollowTheProfileAxisAcrossPaths`), `ui/elevation/ElevationMapViewModelTest` (`tripLoadsProfile`, `segmentLoadsProfile`, `planTakesHandoffOnce`, `planWithoutHandoffCloses`, `scrubStoresDistance`), extra `ui/elevation/ElevationTextTest` (5: copy + km-axis step), 2 extra cases in `map/BikeMapGeoJsonTest` (route colour runs, marks).
- S22: `ui/elevation/ElevationChartTest` (`noProfileShowsText`, `smallChartOpens`, `scrubReportsDistanceAndShowsLine`, `descriptionReadsMinMaxClimbs`, `stripDimmed`), `ui/elevation/ElevationMapScreenTest` (`legendHasSevenBands`, `scrubShowsLine`, `closeGoesBack`, extra `planWithoutHandoffGoesBackOnce`, `screenshot` → `v170-elevation-map`).

## Decisions
- `ElevationStrip(profile, reachesDestination, dimmed, modifier)` exactly, no `flatColor` (ruling); always table colours. `profile == null` draws nothing.
- Colour format: `"#RRGGBB"` uppercase (`String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)`), the same strings `colorRuns`/`MapLine` already use.
- Big map lines: drawn through `MapOverlay.lines` (`MapLine(path, colors)`, the existing `colorRuns` track source), one line per segment; `routeColors` + the route layer is the shared mechanism Task 6 uses on the guidance map. FLAT on the big map = table blue (`pathColors` without `flatHex`).
- `pathColors` lays each point on the profile axis with `GeoMath.distanceM` within a path and nothing across gaps (same as `ElevationProfile.fromSegments`), so trip colours line up with the chart.
- VM: trip = `rideStore.segments(tripId).filterNot { isEmpty }.sortedBy { startedAt }` then `rideStore.points(id)` each (same store calls as TripDetailViewModel/SegmentDetailViewModel); segment = `rideStore.points(segmentId)`; profile + colours on the injected `compute` dispatcher (default `Dispatchers.Default`). Plan: `handoff.take()` in `init` (synchronously, so a second VM never sees it). Closed also when the profile is null or the store throws (logged) — the screen then goes back once.
- `scrub(d)` clamps to `[0, totalM]`.
- Chart: everything drawn in one Canvas (`drawWithCache`, sampled every 2dp, each column the band at its middle; text via `TextMeasurer`), so the chart is one accessible node with `contentDescription = chartDescription(min, max, ClimbFinder.find(profile).size)`; small chart with `onOpen` is `clickable(role = Role.Button)` plus a [지도에 표시] pill (map icon + text) bottom right above the axis. Big chart: `awaitEachGesture` tap/drag → white 2dp vertical line (a real tagged Box, `SCRUB_LINE`) + white dot with dark grey ring; position `rememberSaveable(profile)` so it survives lifting the finger. Y range has 0.75/0.65 span headroom and a 20 m minimum span so flat rides look flat.
- km axis: of 1/2/5/10 km the first giving 3–6 ticks (0 included); outside that range the 1-2-5 series 10 m…500 km, else the closest. Labels `"{n}km"` (same style as the old LineChart / SummaryFormatter, no space).
- Map screen: map `fillMaxHeight(0.6f)`, framed once on all points (`CameraFit`), FREE camera, start green `#43A047` / end black `#212121` (white rings), scrub marker white with `#424242` ring; panel = title `고도`, [×] (`닫기`), big chart 160dp, then legend (7 cells, `-6% 미만`/`평지·완만` given wider weights so they fit) or `scrubLine`.
- `ElevationMapScreen` gets a `map` slot (default = real `ElevationMap`) like `SegmentDetailScreen`, so device tests can check the marker/endpoints given to the map.

## Deviations
1. `ElevationMapScreen(viewModel, tileSource, onClose, map = …)` — brief said `(viewModel, onClose)`; the tile source is needed for `BikeMap` and the slot follows the detail screens' test pattern.
2. `MapOverlay.endpoints: Pair<GeoPoint, GeoPoint>?` added besides the brief's `routeColors`/`marker`: the existing stops layer draws orange/red, the spec wants start green / end black.
3. `ElevationText.CLOSE = "닫기"` (spec §5 has `닫기` in the export dialog; used as [×]'s content description) and `ElevationText.kmTick` (axis labels) added to keep all elevation copy in one file.
4. Extra tests beyond the brief list (ElevationTextTest, 2 GeoJSON cases, pathColors case, planWithoutHandoffGoesBackOnce).
5. TDD order: tests were written first, but I wrote the implementations right after without a separate "see it fail" run (the first test run had one real failure — a too-tight distance tolerance in the VM test — fixed in the test).

## Results
- PC `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL — core 520/520, app 1143/1143 (was 1127; +16), 0 failures.
- S22 `R5CT43TFS9A` (woken first, one class per run, `</dev/null`):
  - `ElevationChartTest` 5/5 passed.
  - `ElevationMapScreenTest` 5/5 passed; `screenshot` re-run twice alone (`#screenshot`) with a 10 s wait.
  - Screenshot saved: `Download/bikeRecoderTest/v170-elevation-map.png` on the S22.
- S25 not touched; no phone settings changed; `secrets.json` not read or committed.

## Concerns
- The screenshot shows the gradient line, green/black ends, white marker, panel, chart and scrub line, but **no base-map tiles**: in the instrumentation process MapLibre's requests for `tiles.openfreemap.org/planet` and the sprites stayed pending for the whole 10 s and were cancelled at teardown (logcat), although `curl` from the phone gets 200 in ~1 s. This looks like an environment/MapLibre-in-test issue, not this task's code (the overlay layers render); worth a glance in the S25 field check (§7.3 item 1).
- `BikeMap` now builds the route GeoJSON on `Dispatchers.Default` (was inline on main): route updates become asynchronous by one hop; a newer route cancels an older build. Plan/guidance screens should be unaffected, but Task 6's device tests are the first to exercise colour runs on the route layer.
- `ElevationChart` keeps its own scrub position (`rememberSaveable`); the VM's `scrubM` drives the panel line and the map dot. After a configuration change both survive, but they are two copies of the same value.

## Fix round 1

Commit: `d7438ee` app: elevation — Task 4 review fixes (on bf4e547)

1. **km tick float error**: `ElevationText.kmTick` now does `BigDecimal.valueOf(km).setScale(2, HALF_UP).stripTrailingZeros().toPlainString()`, rounding to 2 decimals (10 m, the finest axis step). New `ElevationTextTest.kmTickHasNoFloatError`: `3*0.2 → "0.6km"`, `3*0.1 → "0.3km"`, `3*0.05 → "0.15km"`, plus `0km`, `10km`, `150km`.
2. **Stable colours / cache**: `ChartColors` is an `@Immutable data class`, built in `remember(colorScheme)`. The chart's `drawWithCache` modifier is held in `remember(profile, colors, labelStyle, measurer)`, so scrub recompositions keep the cached band areas. The scrub line's x is read only inside its `offset {}` lambda.
3. **One scrub position**: `ElevationChart` gets a new last parameter `scrubM: Double? = null`. The chart keeps no scrub state of its own (the `rememberSaveable` is gone). It reports touches through `onScrub` and draws the line and dot at `scrubM`. The big screen passes `state.scrubM` from the VM. Other parameters and defaults are unchanged, so the small-chart calls in Tasks 5–6 stay the same. The pointer input moved from the inner Canvas to the chart root, so the overlays never block touches.
4. **Dot above the line**: the order is now the white line `Box`, then a separate overlay `Canvas` that draws the dot. The screenshot shows the ringed dot on top of the line.
5. **Strip test**: replaced the semantics key `Dimmed` with `ElevationTags.StripAlpha`. Its value is the same variable passed to `Modifier.alpha`. `stripDimmed` checks `0.4f` and then `1f`, and it also checks the drawn pixels. It captures the strip both ways and asserts that the dimmed fill pixel equals `lerp(surface, full, 0.4)` within ±0.04 per channel, and that it clearly differs from the full fill. New `stripWithoutDestinationHasNoFlag`. `scrubReportsDistanceAndShowsLine` now holds the position the way a caller does. It also checks that the line sits at 75 % of the width and disappears when the caller clears it.
6. **VM**: the code already returned `Closed` in both cases. Added `tripWithoutAltitudesCloses` (points with null altitude, so no profile, so `Closed`) and `storeFailureCloses` (a `RideStore` delegate whose `points()` throws: trip and segment are both `Closed` and each logs one warning).

Results:
- PC `./gradlew :core:test :app:testDebugUnitTest`: core 520/520, app 1146/1146 (+3), 0 failures.
- S22 `R5CT43TFS9A` (woken first, one class per run): `ElevationChartTest` 6/6, `ElevationMapScreenTest` 5/5.

Note: the screenshot helper (copied from the existing tests) cannot delete a PNG that an earlier install created, because MediaStore ownership is lost on reinstall. So the S22 now has `v170-elevation-map.png` and `(1)`–`(3)` in `Download/bikeRecoderTest/`. The latest is `v170-elevation-map (3).png`. I left them in place; the existing screenshot tests behave the same way.
