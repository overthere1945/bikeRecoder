# Task 3 report — app: big-map tabs

**Status:** DONE_WITH_CONCERNS
**Commit:** 623c247 `app: chart map — elevation, speed and heart-rate tabs` (BASE 6128a48)

## What was implemented

- **`ChartTab.kt`** (new): `enum class ChartTab(val arg)` with ELEVATION/SPEED/HEART_RATE (`elevation`/`speed`/`heartRate`).
  It has `title` (from `ChartText.TITLE_*`), `tag` (test tag), `ROW_TAG` and `of(arg)` (null or unknown → ELEVATION).
  It also has `internal fun metricLegend(kind)`, which pairs each legend text with its colour:
  - speed: `SPEED_LEGEND` zip `SpeedBand.entries.hex`;
  - heart: `HEART_LEGEND` zip `HrBand` without NONE.
- **`ElevationMapViewModel`**
  - New constructor: `(source, rideStore, postStore, handoff, requestedTab = ELEVATION, log, compute)`.
  - `Ready` holds `profile?`, `speed?`, `heartRate?`, `tabs`, `tab`, `paths`, `colors` (the current tab's colours), `scrubM`, plus three fields the brief does not list:
    - `marker`: the dot on the map;
    - `speedDescription`;
    - `heartDescription`.
  - **Tracks:** `TrackAxis.of(kept)`, then `ElevationProfile.fromSegments`, `MetricSeries.speed` and `MetricSeries.heartRate`. Every tab's colours are worked out once on `compute`:
    - elevation uses the old `pathColors(paths, profile)`;
    - speed and heart use `pathColors(axis.paths, series::colorHexAt, axis)`.
  - **Tabs** are the kinds that have data, in a fixed order. The screen opens on the requested tab if it is there, otherwise on the first one. If there are no tabs, the state is `Closed`.
  - **Heart rate for a trip:** `PostStore.samples(id)` is read for each non-empty segment and the results are joined. A failed read is logged and leaves heart rate null.
  - **Plan:** elevation only (`tabs == [ELEVATION]`).
  - `selectTab(tab)` swaps `tab` and `colors`. A tab that is not in `tabs` is ignored. `scrubM` and `marker` stay as they are.
  - `scrub(d)` clamps to the line length (profile or series `totalM`) and sets `marker` from `TrackAxis.pointAt` (tracks) or `profile.pointAt` (plan).
  - `factory` reads the `tab` nav argument and passes `container.postStore` (no change to AppContainer was needed).
- **`ElevationMapScreen`**
  - If there is more than one tab, a Material 3 `SingleChoiceSegmentedButtonRow` of [고도][속도][심박] sits where the title was. A lone tab shows its title text instead (`tab.title`).
  - The chart follows the tab: `ElevationChart` or `MetricChart`, all 160dp and scrubbable.
  - The legend follows the tab: 7 grade cells, or 5 metric cells built from `metricLegend`. All cells use the tag `ElevationTags.LEGEND_CELL`.
  - The scrub text follows the tab: `ElevationText.scrubLine`, or `ChartText.scrubLine` (shows `--` where there is no value).
  - The map marker comes from the state.
- **`BikeNavHost`**
  - `Routes.ELEVATION = "elevation/{kind}/{id}?tab={tab}"`, with the nav argument defaulting to `elevation`.
  - `Routes.elevation(source, tab)`.
  - New `NavController.openChartMap(source, tab)`. `openElevation(source)` now calls `openChartMap(source, ChartTab.ELEVATION)`.
- **Extra controller items**
  1. **Crash guard.** In `ChartFrame`, the scrub-line x is now computed with `xOf(...)`, so x = 0 when `totalM <= 0`.
  2. **Kind mismatch.** `MetricChart` has `require(series == null || series.kind == kind)`, and `kind` is added to the `drawChart` remember keys.
  3. **Path sizes.** `pathColors(paths, colorAt, axis)` now requires `paths.map{size} == axis.paths.map{size}`.
  4. **Duplicate tags.** Device tests find the scrub line by chart, with `hasTestTag(SCRUB_LINE) and hasAnyAncestor(hasTestTag(chart))`. No tags were renamed.
  5. **Legend order.** `ChartTabTest` pins each legend text to its band colour, both as an explicit list and against `SpeedBand.of` and `HeartRateColor.band` for a value in each range.
- **Core addition (not in the brief's file list):** `TrackAxis.pointAt(distanceM)`, a linear position on the axis that matches `ElevationProfile.pointAt`. A trip with speed but no elevation needs it for the map dot, because there is no profile in that case. It has two core tests.

## Commands and results

- **RED (JVM):** `./gradlew :core:test --tests '*TrackAxisTest' :app:testDebugUnitTest --tests '*ElevationMapViewModelTest' --tests '*ChartTabTest' --tests '*RouteColorsTest'`
  - Failed to compile: `pointAt`, `ChartTab` and `metricLegend` were unresolved.
- **RED (RouteColors):** with the old `require` stashed, `RouteColorsTest > metricColorsRejectOtherPaths() FAILED`.
- **GREEN (JVM targeted):**

  | Class | Tests | Result |
  |---|---|---|
  | TrackAxisTest | 6 | pass |
  | ElevationMapViewModelTest | 15 | pass |
  | ChartTabTest | 3 | pass |
  | RouteColorsTest | 7 | pass |

- **Full JVM suite** `./gradlew :core:test :app:testDebugUnitTest`: core 552/552, app 1216/1216, no failures. It was run twice: before the final rename and after it. The known flake did not appear.
- **S22 `R5CT43TFS9A`**, one class per run, after `KEYCODE_WAKEUP`:

  | Run | Result |
  |---|---|
  | `ElevationMapScreenTest` | 10/10 (run twice; the second run was after the final rename) |
  | `MetricChartTest`, with the `ElevationChart.kt` fix stashed (RED) | 1 failure, `scrubOnLineOfNoLength`: `IllegalArgumentException: Cannot round NaN value` at ElevationChart.kt:224 |
  | `MetricChartTest`, with the `require` removed (RED) | 1 failure, `seriesOfAnotherKindIsRejected` (no exception) |
  | `MetricChartTest` (GREEN) | 9/9 |
  | `ElevationChartTest` | 6/6 |

- **Screenshots:** `Download/bikeRecoderTest/v180-map-speed.png` and `v180-map-heart.png` were saved on the S22 and checked by eye:
  - the tabs row with a check on the selected tab;
  - the coloured line and the marker dot;
  - the band-coloured chart, with the heart gap shown as a break in the chart and grey on the map;
  - the scrub line text.
  - The basemap tiles are blank, the same as the v170 screenshot in that setup.

## TDD evidence

- **New JVM tests**
  - `ElevationMapViewModelTest`:
    - `tabsFollowData`
    - `missingTabFallsBackToFirst`
    - `scrubKeptAcrossTabs`
    - `colorsFollowTab`
    - `noElevationOpensOnSpeed`
    - `planHasOnlyElevation`
    - `tripHeartRateJoinsSegments`
    - `heartRateReadFailureLeavesTheRest`
  - `ChartTabTest`: `argNamesTab`, `speedLegendPairsBands`, `heartLegendPairsBands`.
  - `RouteColorsTest.metricColorsRejectOtherPaths`.
  - `TrackAxisTest`: `pointAtFollowsAxis`, `pointAtOnOneSpot`.
  - All were RED (compile, or the assertion for RouteColors), then GREEN.
- **New device tests**
  - `ElevationMapScreenTest`:
    - `tabsSwitchLegendAndScrubLine`
    - `scrubInHeartRateGapShowsDashes`
    - `singleTabShowsTitle`
    - `screenshotSpeed` (v180-map-speed)
    - `screenshotHeart` (v180-map-heart)
  - `MetricChartTest`: `scrubOnLineOfNoLength` and `seriesOfAnotherKindIsRejected`. Both showed real RED on device (above).
  - The screen tests' RED was compile-level only (`ChartTab` and the new VM signature did not exist yet).
- **Updated existing tests**
  - VM signature in the existing tests.
  - `tripWithoutAltitudesCloses` became `tripWithNothingToDrawCloses` (a one-point track). Points without altitude now open on the speed tab, which is the intended behaviour.

## Files changed

- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ChartTab.kt` (new)
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ElevationMapViewModel.kt`
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ElevationMapScreen.kt`
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/ElevationChart.kt`
- `app/src/main/java/com/cowork/bikerecoder/ui/elevation/MetricChart.kt` (also `colorOf`: private → internal)
- `app/src/main/java/com/cowork/bikerecoder/map/RouteColors.kt`
- `app/src/main/java/com/cowork/bikerecoder/ui/BikeNavHost.kt`
- `core/src/main/kotlin/com/cowork/bikerecoder/core/elevation/TrackAxis.kt`
- Tests:
  - `app/src/test/.../ui/elevation/ElevationMapViewModelTest.kt`
  - `app/src/test/.../ui/elevation/ChartTabTest.kt` (new)
  - `app/src/test/.../map/RouteColorsTest.kt`
  - `app/src/androidTest/.../ui/elevation/ElevationMapScreenTest.kt`
  - `app/src/androidTest/.../ui/elevation/MetricChartTest.kt`
  - `core/src/test/.../elevation/TrackAxisTest.kt`

## Self-review

- **Complete:** every interface in the brief is present. These are the deviations, all small and noted:
  - the extra `Ready` fields (`marker`, `speedDescription`, `heartDescription`);
  - `TrackAxis.pointAt` in core;
  - the constructor parameter is named `requestedTab`, which avoids shadowing `selectTab(tab)`.
- **Names and style:** KDoc is in English in app `ui/` and in Korean in core, as in the existing files. No new user-visible copy; titles and legends come from `ChartText`.
- **YAGNI:**
  - Colours for every tab are computed once at load (three lists at most), so switching tabs needs no new work.
  - No AppContainer change was needed.
- **Tests check real behaviour:**
  - colours per tab are checked against the expected band hex;
  - the trip test checks that each segment's own samples colour its own path;
  - scrub and marker are checked to persist across tabs;
  - the dashes are checked inside a real heart-rate gap.
- **Output:** the test output is clean. The one compiler warning (an always-true condition) was removed.

## Concerns

1. **Read-out text on the big map.**
   - Speed uses the stored segment or trip totals (`tripTotals`), as spec §2.1 says.
   - Heart uses the average and maximum of the raw samples (`HeartRateSummary.of`), not the stored `SegmentExtras.avgBpm` with a moving-time-weighted trip average. That would need extra `PostStore.extras` reads.
   - For a single segment the two agree. For a trip, the average can differ by a bpm or so.
   - Task 4's brief allows a samples fallback. Say if the big map should read extras instead.
2. **`ElevationText.TITLE` is unused in main code now.** The title comes from `ChartTab.title`, which is `ChartText.TITLE_ELEVATION`, the same "고도". It was left in place because of the "do not change `ElevationText`" constraint.
3. **The segment source makes one more `rideStore.segment(id)` read**, to get its totals for the speed read-out.
4. **Minor carry-over from Task 1:** at a segment joint, `valueAt` and `pointAt` return the earlier segment's value. On the map, the next path's first point takes the previous segment's colour. The test `tripHeartRateJoinsSegments` documents this.

## Fix round 1

**Commit:** a76328d `app: chart map — Task 3 review fixes`.

1. **The heart read-out on the big map now uses the stored values.**
   - A new `heartInput(segmentIds, segments)` reads the samples and `postStore.extras(id)` for each segment, in the same try. A failure is logged and leaves heart rate null.
   - The stored values go through `tripHeartRate(segments.groupBy { it.localDate }, extras)`:
     - for a trip, this gives the moving-time-weighted average and the highest value over the READY segments;
     - for a segment, it is the same function called with that one segment (`listOfNotNull(rideStore.segment(id))`).
   - The read-out is built by the private `heartDescription(HeartInput)`. If the stored `avgBpm` or `maxBpm` is null, it falls back to `HeartRateSummary.of(samples)`, the same rule as Task 4.
   - The old `samples()` helper and the plain sample mean are gone.
2. **Route test:** the new `ChartMapRouteTest.routeNamesTab` checks three things:
   - `Routes.elevation(Trip(3), SPEED) == "elevation/trip/3?tab=speed"`, plus the segment and plan forms;
   - `Routes.ELEVATION` ends with `?tab={tab}`.

### Tests

- **New in `ElevationMapViewModelTest`:**
  - `tripHeartDescriptionUsesStoredAverage`:
    - setup: one segment of 30 s with 30 samples at 110 bpm, one of 60 s with 15 samples at 150 bpm; READY extras 110/124 and 150/171;
    - expects `heartDescription(137, 171)` (the plain sample mean would be 123).
  - `segmentHeartDescriptionUsesStoredValues`:
    - setup: samples all at 130 bpm; stored values 128/171;
    - expects `(128, 171)`.
  - `heartDescriptionFallsBackToSamples`:
    - a trip where one segment has no extras and the other has READY extras with a null max; expects `(130, 150)`;
    - a segment with no extras; expects `(110, 110)`.
- `segment()` in the test gained a `samplesEvery` parameter.
- The heart-description assertion was removed from `tabsFollowData`; the fallback test covers it now.

### RED, then GREEN

- **RED against the round-0 code:**
  - `tripHeartDescriptionUsesStoredAverage`: expected 137/171, got 123/150.
  - `segmentHeartDescriptionUsesStoredValues`: expected 128/171, got 130/130.
- **Not RED, by design:**
  - `heartDescriptionFallsBackToSamples` passes on the old code too, because the fallback is the old behaviour.
  - `ChartMapRouteTest` also passed straight away, because it pins routes that already existed.
- **GREEN:**
  - `./gradlew :core:test :app:testDebugUnitTest`: core 552/552, app 1220/1220.
  - S22 `ElevationMapScreenTest`: 10/10, re-run because the VM's load path changed.
