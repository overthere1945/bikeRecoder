# Final fix wave — report (V1.4.0, BASE c1fc97b)

Status: **DONE**. Commits `c1fc97b..05a7b1f` (9 commits, branch `feature/summary-stats-v1.4.0`, not pushed).

| Commit | Item |
|---|---|
| 60812ed | I4 WebP pictures, key version 2 |
| c53dd00 | I3 picture cache |
| 0215312 | I2 Material tab icons |
| e86d553 | I1 map camera kept |
| 47fa66a | M1 number formats |
| f7a8dfa | M3 ring text |
| 1215777 | M2 loading state + M5 this week follows the date |
| f369864 | M4 distinctUntilChanged + off-Main derivation |
| 05a7b1f | Docs |

## Important

### I1 – Map kept across tab switches
- Chose **save/restore of the camera**, not keeping the map composed: safer with the MapView lifecycle (one MapView, driven by the
  existing DisposableEffect), and a hidden map neither renders nor takes touches because it is not composed at all. Cost: the
  style/tiles redraw briefly when [지도] shows again (position is kept). Documented as a ruling in the field-test doc.
- `map/BikeMap.kt`: new `MapCamera(center, zoom, bearing)`; BikeMap takes `initialBearing` and reports `onCameraStopped(MapCamera)`
  from the camera-idle listener; the initial move uses `newCameraPosition` (target, zoom, bearing).
- `ui/main/MainScreen.kt`: `camera` (`rememberSaveable` with `MapCameraSaver`) and `centeredOnce` (`rememberSaveable`) live in the
  tab's `SaveableStateProvider`, so they survive the tab switch, rotation and screens on top. `mainMapStart(saved, user)` picks
  where the map starts.
- Tests: JVM `ui/main/MainMapStartTest` (start choice, saver round trip); device `MainTabsTest.theMapKeepsItsPlaceAcrossTabs`
  (moves the real MapView to 35.1/129.0, zoom 15.5, bearing 42, goes to 요약 and back, reads the new MapView's camera). Verified
  it fails without the fix (camera back at Korea centre). `MapPickScreenTest` 6/6 re-run for the BikeMap change.

### I2 – Tab icons
- `material-icons-extended` was already a dependency. `ui/main/MainTabs.kt`: `MainTab` keeps only the label; `MainTab.icon(selected)`
  gives Filled/Outlined `Map`, `DynamicFeed`, `BarChart`, `Settings`, drawn with `Icon` (takes the bar's tint).
- Optional part done too: stats ‹ › and the record-row › are `Icons.AutoMirrored.Filled.KeyboardArrowLeft/Right` (same content
  descriptions "이전 기간"/"다음 기간"). The text "›" of the cumulative entry string is unchanged.
- Test: JVM `MainTabsBackTest.tabIconsAreFilledWhenSelectedAndOutlinedOtherwise` (ImageVector names). MainTabsTest 11/11.

### I3 – No picture flash
- New `ui/feed/PictureCache.kt`: generic `PictureCache` over `androidx.collection.LruCache` sized in bytes (a picture larger than the
  whole cache is not kept), `pictureCacheBytes(maxMemory)` = maxMemory/8 capped at 32 MB, `pictureSampleSize(width, cardWidth)`,
  and the process-wide `FeedPictureCache` (key = file path; the name holds the key, so a path never stands for another picture).
  `decode()` reads bounds first and decodes with `inSampleSize` for the screen width (1 on the S22: 1280 vs ~1080 px).
- `FeedCard.rememberPicture`: `remember(file) { mutableStateOf(FeedPictureCache[file]) }` gives a cached picture on the first
  composition; a miss decodes on IO in a `LaunchedEffect(file)`. The Image got tag `FeedTags.picture(tripId)`.
- Tests: JVM `PictureCacheTest` (LRU by bytes, oversize, 1/8 capped, sample size); device
  `FeedScreenTest.aDecodedPictureShowsOnTheFirstFrame` (file not on disk, only the cache has it; asserted with the clock paused right
  after setContent; a missing, never-decoded file shows the line drawing).

### I4 – Picture file size (measured, ruling taken)
- Measured on the S22 with a **real VWorld satellite render** (build key, device internet) of the 1280×720 test trip (Yeouido), via
  the renderer device test:
  - PNG 100: **813,228 B**
  - WebP lossy 90: **106,060 B** (≈7.7× smaller)
  - (WebP lossy 85: 80,762 B, for reference)
- WebP is far more than 2× smaller → stored as `Bitmap.CompressFormat.WEBP_LOSSY` q90, name `<tripId>-<key>.webp`.
- `feed/FeedThumbnails.kt`: `BitmapPng` renamed `BitmapWriter`, `AndroidBitmapWebp`; `PICTURE` = `.webp`, `ANY` matches webp or png
  (+.tmp), new `LEGACY` (png/png.tmp). Legacy files are deleted at the start's directory read and by `prune` (a stranger's file with
  an over-long number is still left alone). `FeedThumbKey.VERSION` = 2. `feedThumbName` → `.webp`.
- Tests: JVM `FeedThumbnailsTest` (all names .webp; new `legacyPngPicturesAreDeletedAtStartAndDrawnAgainAsWebp`,
  `pruneAlsoRemovesLegacyPngFiles`), `FeedThumbKeyTest.versionTwoSinceWebp` (pins the v2 hash), `FeedViewModelTest` names; device
  `FeedThumbnailRendererTest.aRealSatellitePictureIsStoredSmall` (kept: asserts stored ≤ PNG/2 and < 300 KB, logs both sizes; skipped
  by assumption on a build without the key).
- Numbers written into the spec §2.4 (amended file name, ruling I4), CHANGELOG and the field-test doc rulings.

## Minors

### M1 – Number formats
- `FeedFormat.kt`: `meterText` ("123 m", "1,234 m", half up) and `feedMonthHeaderText` ("2026년 10월 · 32.1 km · 7회").
- Feed month header now sums the **cards' distances** (ended rides ≥ 10 m), no longer `summary.distanceM`.
- "123 m" on: card 상승, stats 상승 cell, bar value (ASCENT metric), record "가장 많이 오른 주행".
- Checked the band (kmText + duration), week strip (kmText), ring/yearly ("64 / 100 km"), projection, average line: no 2-decimal km
  or space-less m left on V1.4.0 surfaces. Left alone: the map tab's V1.2 riding banner "● 주행 중 · 12.40km · 0:10:00" (pre-existing
  map banner, not a V1.4.0 surface) and record-screen formats (accepted ruling T8).
- Tests: `FeedViewModelTest.monthHeadersAndTotals` (now has a 9 m ride that would round the old sum to 3.5 km), `StatsFormatTest`
  (`meterTextHasASpaceAndRoundsHalfUp`, `feedMonthHeaderUsesTheCardsKilometres`, updated record/metric texts), `StatsViewModelTest`
  bar text; device fixtures updated.

### M2 – No empty first frame
- `StatsViewModel.state: StateFlow<StatsState?>`, null until rides and goals have both emitted; `page()`/`selectBar` ignore taps
  until then. `StatsScreen` returns early while null, so the goal card (and its dialog) can only open once loaded → the dialog
  always seeds from stored goals.
- `FeedViewModel.weekStrip: StateFlow<WeekStripState?>`, null until rides and the goal emitted; `FeedContent` draws no strip item
  while null (the ring is part of the strip).
- Tests: JVM `StatsViewModelTest.noStateUntilRidesAndGoalsAreRead`, `FeedViewModelTest.weekStripWaitsForTheRidesAndTheGoal`; device
  `StatsScreenTest.nothingShowsBeforeTheGoalsAreRead`, `FeedScreenTest.noStripBeforeItIsRead`.

### M3 – Goal ring text
- `WeekStrip.GoalRing`: big kilometres on line 1, "/ 100 km" small on line 2, "달성" on line 3 when reached. Sized in dp (part of
  the drawing, not scaled by font scale) and auto-shrunk (`TextAutoSize.StepBased`) to a 52 dp column; read out as one line via
  `clearAndSetSemantics { contentDescription = "64 / 100 km[ 달성]" }`. New `goalDoneText`/`goalOfText` (goalProgressText built from them).
- Tests: JVM `StatsFormatTest.goalProgressTextGroupsThousands` (two parts); device
  `FeedScreenTest.ringTextStaysInsideTheRingWithALargeGoalAndFont` (10,234 / 10,000 km at fontScale 2: text bounds inside the stroke),
  ring assertions in FeedScreenTest/StatsScreenTest switched to the content description. Visual check of the S22 screenshot: "64" over
  "/ 100 km" centred in the ring.

### M4 – Recording
- `RoomRideStatsSource.observeRides()` ends with `.distinctUntilChanged()`.
- `FeedViewModel` (feed and weekStrip combines) and `StatsViewModel` (state combine) use `.flowOn(compute)`, `compute:
  CoroutineDispatcher = Dispatchers.Default` (constructor param; JVM tests pass the test dispatcher, StatsScreenTest passes
  `Dispatchers.Main` so each check sees the newest state).
- Goals flow: `.distinctUntilChanged()` added in the Record and Navigate view-model factories (SummaryExtras' goals); the stats and
  feed goal flows already had it.
- Tests: device `RoomRideStoreTest.observeRidesIgnoresAGrowingOpenSegment` (appendPoints on the open segment does not re-emit; verified
  it fails without the fix: `[[1],[1,2],[1,2]]`); JVM `StatsViewModelTest.theStateIsBuiltOnTheComputeDispatcher` (upstream runs on the
  injected dispatcher).

### M5 – This week follows the date
- `showThisWeek()` sets `kind = WEEK, anchor = null, bar = null` (after re-reading the day).
- Test: JVM `StatsViewModelTest.showThisWeekFollowsTheDate` (Saturday 10/31 → resume Tuesday 11/3 → period 11/2); the existing
  `showThisWeekJumpsToTodaysWeek` still passes.

## Docs
- `CHANGELOG.md`: DB v6 only adds `trip_label`; goals are kept in settings (DataStore). Also: WebP pictures with sizes and the legacy
  sweep, the in-memory picture cache, Material tab icons, map position kept, month header = sum of the cards, no zero first frame.
- `docs/VERSIONING.md`: rows `V1.3.1 | 영상 다듬기(위성 지도·카메라·꾸밈) — 출시` and `V1.4.0 | 요약·통계 탭 — 개발 중`.
- Spec §1.4: band uses `kmText` + `RideFormat.duration` (ruling T8 noted). Spec §2.4: `.webp`, quality 90, ruling I4 with the
  measurement, picture version 2, legacy sweep.
- `docs/verification/summary-stats-field-test.md`: rulings I4 (numbers), I1, month header/metre format; this wave's JVM counts and S22
  classes; new manual checks 12–16 (strip tap → [통계][주] this week; strip [목표 정하기] keeps both goals; 누적 지도 entry period;
  map position kept; no picture flash); check 3 notes the on-disk size; verdict now says 16 items.

## JVM tests
`./gradlew :core:test :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL.
- `:core:test`: 44 classes, 375 tests, 0 failures, 0 skipped (was 374).
- `:app:testDebugUnitTest`: 82 classes, 946 tests, 0 failures, 0 skipped (was 80 / 930).
- Warnings: only the pre-existing ones in files not touched here (`GlyphIcon.kt` invisibleToUser ×1, `PostProcessorTest.kt` opt-in ×11).
  None in changed files (main, test or androidTest compile).

## Device (S22 `R5CT43TFS9A` only, one class per run, screen woken first)
| Class | Result |
|---|---|
| `video.FeedThumbnailRendererTest` | 6/6 |
| `ui.feed.FeedScreenTest` | 20/20 (final run after M2; M4 does not touch it) |
| `ui.stats.StatsScreenTest` | 19/19 (after M4) |
| `ui.main.MainTabsTest` | 11/11 (final, after all app commits) |
| `data.RoomRideStoreTest` | 11/11 |
| `ui.pick.MapPickScreenTest` | 6/6 (BikeMap initial camera change) |

Two deliberate red runs (fix reverted / not yet applied) confirmed the I1 and M4 device tests discriminate; both were restored and
re-run green. Nothing was run on R3CY903ZYQV; no phone settings changed; `secrets.json` not read or printed (the real-satellite test
only uses `BuildConfig.VWORLD_API_KEY`, never logs it). The screenshot tests left new `v140-feed (n).png` files in
`Download/bikeRecoderTest` on the S22 (existing behaviour of FeedScreenTest.screenshot).

## Not fixed / concerns
- Nothing from the brief left undone.
- I1 trade-off: the map is rebuilt on return (brief accepted either option); the style/tiles redraw briefly. Edge case kept as before:
  if no fix had arrived before leaving and the user had panned, the first fix later still centres on the user once.
- `StatsScreenTest` builds its view model with `Dispatchers.Main` (production uses Default), so the device test of the stats tab does
  not exercise the off-Main path; the JVM test pins `flowOn`.
- The I4 device test needs the VWorld key and internet; without the key it is skipped (assumption), without internet it would fail
  like `SatelliteMapTest`.
- M6 (▶ 영상 after gallery delete) was not in the brief and is untouched.
