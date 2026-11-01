# Task 4 report — app: trip screen and segment screen

**Status:** DONE_WITH_CONCERNS
**Commit:** d29dd2a `app: trip and segment — speed and heart-rate charts` (BASE a76328d)

## What was implemented

### Trip screen (`TripDetailViewModel`, `TripDetailScreen`)
- VM: `speedSeries: StateFlow<MetricSeries?>`, `heartSeries: StateFlow<MetricSeries?>`, `heartDescription: StateFlow<String>`.
  - Computed in `load()` next to `profileOf`, in the same `segments != trackOf` block, on `compute` (Dispatchers.Default).
  - Axis: `TrackAxis.of(byStart)` of the non-empty segments in start order (same list as the profile).
  - Samples: `PostStore.samples(id)` per segment in start order, concatenated (ruling for Task 3). A read failure is logged and gives no heart rate; speed is unaffected.
  - Series computation is in its own `guarded` block, separate from the profile: failure → both series null, `profileLoaded` still true, profile kept.
  - New seam `speedOf: (TrackAxis, List<List<TrackPoint>>) -> MetricSeries?` (like `profileOf`) for the failure test.
  - `heartDescription` = `combine(heartRate, chartSamples)`, so it uses `TripHeartRate.avgBpm`/`maxBpm` and falls back to `HeartRateSummary.of(samples, 0)` when either is null. Runs `flowOn(compute)`.
- Screen: `TripDetailContent(..., onOpenChart: ((ChartTab) -> Unit)?, speedSeries, heartSeries, heartDescription)`. `onOpenElevation` is merged into `onOpenChart`, and `TripDetailScreen(onOpenChart: (ChartTab) -> Unit)` is wired in `BikeNavHost` to `openChartMap(Trip(id), tab)`.
  - Order: totals card, then one LazyColumn item `"charts"` holding 고도 → 속도 → 심박. Each has a `titleSmall` title (`ChartText.TITLE_*`). The 심박 title and chart appear only when `heartSeries != null`. Nothing is shown while `!profileLoaded`.
  - Speed read-out: `ChartText.speedDescription(total)` (the stored avg/max). Heart read-out: VM `heartDescription`.
  - I put all three charts in one item on purpose, so they lay out together (no lazy half-composition) and the order test is reliable.

### Segment screen (`SegmentDetailViewModel`, `SegmentDetailScreen`)
- `SegmentDetailState.Loaded.speed: List<ChartPoint>` → `speedSeries: MetricSeries?` (`MetricSeries.speed` on `TrackAxis.of(listOf(points))`, computed in the existing `withContext(compute)`).
- VM `heartRateChart` → `heartSeries: StateFlow<MetricSeries?>` (same `combine(trackPoints, samples).flowOn(compute)`), plus `heartDescription` (stored extras avg/max, with a sample fallback).
- Screen: the `LineChart`s are replaced by `MetricChart`, with the same place and the same `titleMedium` titles. `onOpenElevation` is replaced by `onOpenChart(ChartTab)`, wired to `openChartMap(Segment(id), tab)`. Speed is read out with `ChartText.speedDescription(segment.totals)`.
- Heart gate: the old gate was `hrStatus == READY && chart not empty`; it is now `hrStatus == READY && heartSeries != null`. The samples are only read while READY, so the visible outcome is the same. One expected difference: very sparse samples now give a null series (Task 1 rule), so no chart.

### Shared read-out helpers
- `ChartText.speedDescription(totals: SegmentTotals)` and `ChartText.heartDescription(avgBpm: Int?, maxBpm: Int?, samples)`. `ElevationMapViewModel` now uses them instead of its private `heartDescription(HeartInput)` and `MPS_TO_KMH`, so one rule serves the big map, trip and segment.

### Deletions
- `app/.../ui/history/LineChart.kt`
- `core/.../recording/RideCharts.kt`: the whole file. Once `altitude` and `speedKmh` were gone, `cumulativeKm` and `downsample` were used only by `HeartRateCharts.bpm`, and `ChartPoint` by nothing. I grepped before deleting.
- `HeartRateCharts` object, removed from `core/.../post/HeartRate.kt` together with its imports.
- Tests: `RideChartsTest.kt` (8 tests) and the 5 `chart*` tests in `HeartRateTest`.

## Tests (TDD)

### RED
I wrote the tests first. `./gradlew :app:compileDebugUnitTestKotlin` failed to compile, with these errors among others:
- `Unresolved reference 'speedSeries' on receiver of type 'SegmentDetailState.Loaded'`
- `Unresolved reference 'heartSeries' on receiver of type 'SegmentDetailViewModel'`
- `No parameter with name 'speedOf' found`
- `Unresolved reference 'heartSeries'/'speedSeries'/'heartDescription' on receiver of type 'TripDetailViewModel'`

The androidTests likewise reference `onOpenChart`, `speedSeries`, `heartSeries` and `heartDescription`, which did not exist at BASE.

### Mutation checks after GREEN
- Making the trip `heartDescription` ignore stored values (`heartDescription(null, null, samples)`) → `storedAveragesUnchanged` FAILED (1 of 21).
- Gate: removing only the screen's `READY` check → still 11/11, because the VM reads samples only when READY. Removing the VM's READY check as well → `heartRateGateUnchanged` FAILED ("found 'heart_chart'"). The test pins the visible gate.

### New and changed tests
- `TripDetailViewModelTest`, all new: `speedAndHeartSeries`, `noHeartRateGivesNullSeries`, `storedAveragesUnchanged`, `seriesFailureStillLoaded`, plus `heartReadOutFallsBackToSamples` and `unreadableSamplesDropOnlyHeartRate`.
- Segment state is tested in `FeedViewModelTest` as the brief says. `segmentDetailLoadsTrackAndCharts` now checks `speedSeries`: kind SPEED, 10 m axis, first value null, others 18 km/h.
- Segment heart-rate VM tests live in `DetailHeartRateTest`, not FeedViewModelTest. `theHeartRateChartUsesTheDistanceAxis` became `theHeartRateSeriesUsesTheTrackAxis`; it compares against `MetricSeries.heartRate` on the same axis, checks that speed and heart share the axis, and checks that the description uses stored values. I added `noSamplesNoHeartRateSeries`, and the refetch test now checks `heartSeries.values`.
- `TripDetailScreenTest`:
  - new: `threeTitlesInOrder`, which also checks that the read-outs come from the stored totals; `noHeartRateHidesHeartChart`; `openChartPassesTab`, which uses ancestor-scoped `SHOW_ON_MAP`; `screenshotCharts` → `v180-trip-charts`.
  - extended: `profileNotLoadedShowsNothing` now covers the speed slot and titles.
  - updated: `chartAndShowOnMapOpenElevation` now checks the `ChartTab.ELEVATION` payload.
- `SegmentDetailScreenTest`:
  - new: `metricChartsReplaceLineCharts`, which checks order, read-outs, that no km/h or bpm range text is left, and the SPEED and HEART_RATE tabs; `heartRateGateUnchanged` (PENDING with samples → no chart; becomes READY non-detailed → chart); `screenshotCharts` → `v180-segment-charts`.
  - updated: `elevationChartReplacesLineChart` uses `onOpenChart`; `detailedHeartRate…` checks the `HEART_CHART` tag instead of the old "165bpm" text.

## Commands and results
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL. core 539/539 (was 552; minus 13 deleted chart tests). app 1227/1227 (was 1220; plus 7). No `w:` lines. No flake hit.
- `./gradlew :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL, no warnings.
- S22 `R5CT43TFS9A`, woken first, one class per run, `</dev/null`:
  - `ui.history.TripDetailScreenTest` 21/21. An earlier run had 22/22, before I merged the redundant `notLoadedShowsNoCharts` into `profileNotLoadedShowsNothing`.
  - `ui.history.SegmentDetailScreenTest` 11/11. Two earlier runs failed on my own order assertion in `metricChartsReplaceLineCharts`: `performScrollTo` between reads, then `getBoundsInRoot` clipped to the scroll viewport. Fixed with unclipped `positionInRoot`.
  - `ui.history.DetailMapPhotosTest` 2/2: real VMs on both changed screens.
  - `ui.history.RenameTripDialogTest` 8/8: uses `TripDetailContent`.
  - `ui.elevation.ElevationMapScreenTest` 10/10: `ElevationMapViewModel` read-out refactor.
- androidTest grep for `LineChart`, `onOpenElevation`, `speedKmh`, `HeartRateCharts` and `RideCharts`: only `PlanScreenTest` (`PlanScreen.onOpenElevation`, unchanged API) and the `RideFixtures` parameter name `speedKmh` remain. Neither is affected.
- S25 untouched (only the S22 was attached; every command used the S22 serial). No settings changed. `secrets.json` was not read.
- Screenshots `v180-trip-charts.png` and `v180-segment-charts.png` were saved on the S22. I pulled and viewed them: three titled charts in order, each with its [지도에 표시], and the band colours look right.

## Files
- Modified:
  - `ui/history/TripDetailViewModel.kt`, `TripDetailScreen.kt`, `SegmentDetailViewModel.kt`, `SegmentDetailScreen.kt`
  - `ui/BikeNavHost.kt`
  - `ui/elevation/ChartText.kt`, `ElevationMapViewModel.kt`
  - `core/.../post/HeartRate.kt`
  - tests: `TripDetailViewModelTest`, `FeedViewModelTest`, `DetailHeartRateTest`, `HeartRateTest`, `TripDetailScreenTest`, `SegmentDetailScreenTest`
- Deleted: `ui/history/LineChart.kt`, `core/.../recording/RideCharts.kt`, `core/.../recording/RideChartsTest.kt`

## Self-review
- Completeness: every brief item is covered, including the produced interfaces and the deletions. Every caller of the old callbacks is updated (nav host, androidTests).
- Naming: `speedSeries`/`heartSeries`/`heartDescription` match the brief and the big map's state. `onOpenChart` is the same on both screens.
- YAGNI: one seam (`speedOf`), needed for the brief's `seriesFailureStillLoaded`. The read-out helpers are shared rather than copied a third time.
- Tests check real behaviour: stored-versus-sample read-outs, the series against core's builder on the same axis, the gate pinned and mutation-checked, tab payloads.
- Test output is clean: no warnings, and every run except my own two corrected iterations was green.

## Concerns
1. **Trip heart series refresh timing:** following spec §2.2 ("다시 계산하는 때도 고도와 같다"), the trip's series are recomputed only when the listed segments change. After [다시 가져오기] on the trip, or a post-processing pass that brings new samples while the screen is open, the totals and the read-out update (`heartRate` flow), but the heart chart only appears or changes when the trip screen's VM is recreated. The segment screen does follow new samples live, as before. If live updates are wanted on the trip, the series block could also key on `readyAt`.
2. **ElevationMapViewModel changed:** it was not in the brief's file list. The change is behaviour-neutral and swaps its private read-out code for the shared `ChartText` helpers. ElevationMapViewModelTest (JVM) and ElevationMapScreenTest (S22) are green.
3. **The screen-level `READY` check on the segment heart chart is redundant** with the VM, which reads samples only when READY. I kept it to mirror the old gate exactly.
4. **Spec §2.3 says segment titles stay the same,** so the segment screen keeps `titleMedium` while the trip uses `titleSmall`, as the brief says.
5. **`RenameTripDialogTest` was run and `VideoFlowDeviceTest` was not:** VideoFlowDeviceTest renders `TripDetailScreen` but uses none of the changed parameters. It compiles.

## Fix round 1 (commit d8dc5cf `app: trip and segment — Task 4 review fixes`)

### 1. (Important) The trip heart series follows sample arrival
- `load()` now publishes only `chartInput: MutableStateFlow<ChartInput?>`, which holds the `TrackAxis`, the non-empty tracks in start order and their segment ids. It is built in the same guarded `withContext(compute)` block as `speedSeries` (the `speedOf` seam). If that block fails, there is no input, so both series are null. `profileLoaded` still becomes true right after.
- `heartChart` = `combine(chartInput, readyAt) { input, _ -> input }.mapLatest { guarded { read samples per id; withContext(compute) { MetricSeries.heartRate(...) } } }.stateIn(Eagerly)`.
  - `heartSeries` = `heartChart.map { it?.series }`.
  - `heartDescription` = `combine(heartRate, heartChart)`, with the fallback samples taken from `heartChart`.
  - A failed read or computation gives null and is logged. `guarded` rethrows `CancellationException`, and `mapLatest` cancels a stale read.
- The `profileLoaded` KDoc now covers only the profile and speed. The heart chart appears when its series arrives; it is last in the charts item, so only the segment list below shifts.
- New test `TripDetailViewModelTest.heartSeriesFollowsNewSamples`:
  - setup: open with no samples;
  - then `replaceSamples` plus READY `saveExtras` → the series appears;
  - then a refetch-like replacement with `hrLastTriedAt` 2 → the series equals the new samples' series.
  - RED on d29dd2a: `heartSeriesFollowsNewSamples() FAILED` with `expected: not <null>` at the first `assertSameSeries`. GREEN after the fix.
- `seriesFailureStillLoaded`, `unreadableSamplesDropOnlyHeartRate`, `speedAndHeartSeries` and the other VM tests are unchanged and green.

### 2. (Minor) `threeTitlesInOrder`
- It now compares unclipped `positionInRoot` spans through a `span()` helper, the same as `SegmentDetailScreenTest`. It also asserts that the titles and the elevation chart have a non-zero height.

### Commands
- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL, no warnings. core 539/539, app 1228/1228 (1 new).
- S22 `R5CT43TFS9A` (woken first, `</dev/null`, the only device attached): `ui.history.TripDetailScreenTest` 21/21.
