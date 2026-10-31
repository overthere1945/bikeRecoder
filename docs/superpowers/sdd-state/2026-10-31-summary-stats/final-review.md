# Final whole-branch review — V1.4.0 요약·통계 (7a1cd19..c1fc97b)

Reviewer: Opus, read-only. Reviewed in passes: (1) spec, plan header D1–D6, ledger rulings and deferred minors, global constraints;
(2) data layer, AppContainer wiring, FeedThumbnails / renderer / NetworkWaiter / TripRegions; (3) BikeNavHost, MainTabs, RideBand,
MainScreen, CumulativeMap args; (4) FeedViewModel/Screen/Card/WeekStrip/Format; (5) StatsViewModel/Screen/Format/GoalCard/GoalDialog/BarChart;
(6) core stats/goals/records/title/key; (7) SummaryExtras + Record/Navigate wiring, TripDetail rename, VideoInputs/VideoJob; (8) schema 5→6,
migration test, docs. Tests were read selectively (not every test file line by line); no Gradle run, no device.

## Strengths

- **Spec coverage is essentially complete.** Every §1–§5 behaviour is present: 4-tab shell with saveable tab, back→[지도], re-tap scroll-to-top,
  ride band with the §1.4 strings and record-wins rule, feed with strip/month headers/cards/hidden-short [정리]/empty state, 16:9 satellite
  pictures with ▶ 영상 and 🏆, rename with 30-code-point limit and auto-title placeholder, goals (ring, bar, projection, dialog with chips and
  validation), streak sentences, period stats with same-day comparison and bar tap/outside-tap, personal records with navigation, cumulative
  entry with period filter, summary 달성/신기록 lines. Rulings D1–D6 and the task rulings are applied consistently and documented in the
  field-test doc.
- **Core is clean and pure.** `StatsCalculator`, `RideRecords`, `Goals`, `FeedThumbKey`, `RideTitle` take `today` as an argument, are
  well-named, small, and match D4/D5/D6 exactly (e.g. `comparison` prevEnd per D5, `newRecords` requiring an earlier ride and strict `>`).
- **D1 was the right call.** `trip_label` with FK CASCADE avoids the `@Update`-overwrites-title trap; schema diff 5→6 is purely additive
  (only `trip_label` added, no other entity changed), the AutoMigration is trivially correct, and `v5DataSurvivesMigrationTo6` seeds a real
  trip/segment/media and checks `foreign_key_check`. No `REPLACE` on `trip` exists that could cascade-delete labels.
- **FeedThumbnails is carefully engineered**: single worker, CONFLATED wake channel, per-trip generation numbers so delete/prune during a
  render leaves no file, `.tmp` + rename, Throwable guards so an OOM never kills the worker, offline→online clears failures. Threading is
  correct: `MapSnapshots.take`/`Shot.project` hop to Main; drawing is on software bitmaps on Default (same order as `SummaryImageRenderer`).
- **Good seams for testing** (`FeedPictures`, `GoalSettings`, `BitmapPng`, injectable clocks/zones) and the busy gate (ride/guidance/video).
- `SummaryExtras` subscribes to the ride-stats flow only while a summary is shown (`flatMapLatest` on the segment), so the record/navigate
  screens do not churn during a ride.

## Issues

### Critical (Must Fix)

None found. No data-loss path, no crash path I could construct, migration is additive and tested.

### Important (Should Fix)

**I1. The map tab is torn down on every tab switch; camera snaps back to the user and the style/tiles reload.**
- `app/src/main/java/com/cowork/bikerecoder/ui/BikeNavHost.kt:371` (`when (tab) { MainTab.MAP -> MainScreen(...) }`) +
  `ui/main/MainScreen.kt:105-124` (`fit`/`centeredOnce` are `remember`, `initialCenter = user ?: KOREA_CENTER`) +
  `map/BikeMap.kt:181` (`remember { MapView(context) }`).
- Only the selected tab is composed, so leaving [지도] disposes the MapView. Coming back creates a new MapView (blank map while the style and
  tiles load) and `centeredOnce` is false again, so the camera jumps to the user. Before V1.4.0 this only happened on the rare ≡ → 기록 round
  trip; now tabs are the primary navigation and the user will hit it constantly (look at a place on the map → peek at 요약 → back: the place is
  gone). Any tabbed app (Strava included) keeps the map where it was. Reasonable-person expectation → Important.
- Fix (smallest): make `centeredOnce` `rememberSaveable` and save the camera (center, zoom, bearing) when the map leaves composition —
  `rememberSaveable` state inside the tab's `SaveableStateProvider` survives the switch — and pass it as `initialCenter/initialZoom` (extend
  `onCameraIdle` to report zoom, or read `map.cameraPosition` in `onDispose`). Better UX (no reload flash): keep the MAP tab composed under the
  others (draw it always, put the other tab on top, disable its gestures while hidden). Either way, add a device check "pan the map → 요약 →
  지도: same place".

**I2. Tab bar icons are mixed colour emoji (🗺 📰 📊) and a monochrome ⚙.**
- `ui/main/MainTabs.kt:26-31`, rendered via `GlyphIcon` at `MainTabs.kt:117`.
- This is the app's permanent chrome on every main screen and the user's stated goal is a polished Relive/Strava-level app. Colour emoji
  ignore the NavigationBar's selected/unselected tint (only the pill shows selection), render differently per OEM font, and 📰 ("newspaper") is
  an odd metaphor for 요약. `material-icons-extended` is already a dependency and `Icons.*` is already used (`TripDetailScreen`, video screens).
- Fix: `MainTab` carries `ImageVector`s — outlined when unselected, filled when selected — e.g. `Map`, `DynamicFeed`/`ViewAgenda`,
  `BarChart`/`Insights`, `Settings`; `Icon(if (selected) filled else outlined, contentDescription = null)`. While there, the stats ‹ › and the
  `›` chevrons can become `Icons.AutoMirrored.*.KeyboardArrowLeft/Right` (optional).

**I3. Feed pictures flash back to the line drawing every time a card scrolls into view (decode uncached, full size).**
- `ui/feed/FeedCard.kt:212-225` (`rememberPicture`: `value = null`, then `BitmapFactory.decodeFile` of the full 1280×720 PNG on IO).
- LazyColumn disposes/reuses off-screen items, so the `produceState` restarts on every re-entry: the card shows the dark line drawing for a
  few frames, then pops to the satellite image — on every scroll back and forth, on the headline feature ("보기 좋게 훑는 피드"). Ledger
  parked it as a minor; user-visible on every scroll → Important.
- Fix: a small process-wide `LruCache<String /*path*/, ImageBitmap>` sized in bytes (~1/8 of `maxMemory`), read synchronously in
  `remember(file)` so a cached picture is drawn on the first frame; decode only on a miss (keep the IO hop), and don't reset `value` to null
  when the file is unchanged. Optionally decode with `inPreferredConfig = RGB_565` or to the card width to halve memory.

**I4 (verify first). Picture cache size on disk is probably far above what the spec implies; PNG of satellite imagery is large.**
- `feed/FeedThumbnails.kt:31` (`Bitmap.CompressFormat.PNG, 100`), file name `<tripId>-<key>.png` (spec §2.4 mandates the name).
- A 1280×720 ARGB satellite photo compresses poorly as lossless PNG; expect roughly 1.5–2.5 MB per trip (the spec's "약 0.3 MB" is the
  *download*, which arrives as JPEG tiles). With ~100–300 trips that is hundreds of MB in `filesDir`, and each decode in I3 is slower. Nobody
  measured it (task 5/6 reports and the field-test doc record data use, not file size).
- Action: in the S22 run, render a few pictures and `ls -l files/feed_thumbs` (debug `run-as`). If files are > ~0.5 MB, propose to the user a
  ruling to store `WEBP_LOSSY` (q≈85, typically 100–200 KB, visually identical) — this changes the spec's `.png` name, so it needs the user's
  OK — bump `FeedThumbKey.VERSION` to 2 so old PNGs are replaced, and update `PICTURE`/`ANY` regexes. If small, close as no-op and record the
  number in the field-test doc.

### Minor (Nice to Have)

**M1. Mixed number formats in the same view (fix-before-merge, trivial).**
- `ui/feed/FeedViewModel.kt:479` uses V1.2's `monthHeaderText` → "2026년 10월 · 32.10km · 7회" directly above cards that say "32.1 km".
  The header also sums `summary.distanceM` (all segments, incl. < 10 m and the open one) while the cards sum ended rides ≥ 10 m, so the header
  differs from the sum of the cards below it (ledger T6 minor).
- Fix: a feed-local header `"${y}년 ${m}월 · ${kmText(sum of the month's card distances)} · ${n}회"`. Also new screens put "32.1 km" (with a
  space) next to "123m" (no space, `RideFormat.altitude`) in the same row (`FeedCard.kt:97`, `StatsScreen.kt:270`, record rows); add a
  feed/stats `meterText` → "123 m" for the new V1.4.0 surfaces. Leave the band-vs-record-screen difference (accepted ruling T8).

**M2. First frames show empty/zero content before Room/DataStore emit (fix-before-merge, cheap).**
- `ui/stats/StatsViewModel.kt:128` seeds `build(emptyList(), GoalKm(null, null), …)`; `ui/feed/FeedViewModel.kt:206` seeds a zero strip.
  Opening [통계] cold flashes "목표를 정하면 …" + [목표 정하기], "0.0 km", "이번 주에 타면 연속 주행이 시작됩니다" before the real values; the
  feed strip flashes "이번 주 0.0 km · 0회" + [목표 정하기]. A polished app shows nothing (or a skeleton) for those frames.
- Fix: `StateFlow<StatsState?>` (null until both sources have emitted) and render the list only when non-null; same for the strip (a
  `loaded` flag). This also removes the "goal dialog seeded from stateIn nulls" hazard (`StatsScreen.kt:131`): gate the goal card tap until
  loaded.

**M3. Weekly goal ring text overflows the ring (fix-before-merge, cheap).**
- `ui/feed/WeekStrip.kt:100,110`: "150 / 300 km" in `labelSmall` bold on one line inside an 84 dp ring (inner ≈ 68 dp) already touches the
  stroke at font scale 1.0 and is clipped at Samsung's larger font sizes. Fix: two lines — "64" big and "/ 100 km" small (Strava/Garmin
  style), or shrink-to-fit; that is also the more polished look.

**M4. Stats/feed recompute every 5 s during a recording (fix in the wave, one line).**
- `data/RoomRideStatsSource.kt:12`: the query watches `ride_segment`, which `RideController.save` updates every 5 s (`appendPoints` →
  `updateTotals`) for the open segment. The query excludes open segments, so the list is identical, but Room re-emits and the always-alive
  `FeedViewModel`/`StatsViewModel` (Eagerly, scoped to MAIN) rebuild on Main (all cards incl. a SHA-256 per card, all records) for the whole
  ride. Cheap per run, but pointless battery/Main work during the app's core activity.
- Fix: `.distinctUntilChanged()` after the `map` in `RoomRideStatsSource` (RideStat is a data class), and `flowOn(Dispatchers.Default)` on
  the two VM `combine`s (or build off Main).

**M5. `showThisWeek` pins a fixed anchor (fix-before-merge, trivial).**
- `ui/stats/StatsViewModel.kt:157`: `show(WEEK, mondayOf(today))` sets a non-null anchor, so after a day/week rollover the tab keeps showing
  the old week. `selection.update { it.copy(kind = WEEK, anchor = null, bar = null) }` means "this week" and follows the date.

**M6. ▶ 영상 stays on the card after the video was deleted from the gallery.**
- `ui/feed/FeedViewModel.kt:467`, `FeedCard.kt:148`: the feed shows the newest `trip_media` VIDEO without the gallery-existence prune that
  TripDetail does; tapping opens the gallery on a missing item. Fix later: prune via `mediaExists` when the card is shown, or on tap fall back
  to the next video / a toast. Can stay deferred.

## Deferred-minor triage

Fix before merge:
- T6/T8 "month header 32.10km vs card 32.1 km" + "header totals include <10 m/open segments" → **fix** (M1; trivial; adjacent on screen).
- T8 "tab icons mixed emoji/⚙" → **fix** (I2, Important).
- T8 "map rebuilt and camera snaps back on every tab switch" → **fix** (I1, Important).
- T6 "rememberPicture decodes full PNG uncached" → **fix** (I3, Important — visible flash on every scroll).
- T7 "first frame shows empty-state content before Room/DataStore emit" → **fix** (M2, cheap polish).
- T8 "stats-tab goal dialog seeded from stateIn initial nulls" → **fixed by M2** (gate tap until loaded); no separate work.
- T7 "ring text clipping with large goals" → **fix** (M3; clips already at realistic goals under larger font scale).
- T8 "showThisWeek pins a non-null anchor" → **fix** (M5; one line, same file as M2).

Stay deferred (with reason):
- T9 "goals flow lacks distinctUntilChanged" → stay (only effect: an edited goal can add a line to an open summary; add the operator if
  touching those factories anyway).
- T6 "ring truncates 99/100 while strip says 100.0" → stay (50 m window; revisit if M1 changes the strip format).
- T7 "+0.0 km (+0%) tie wording" → stay (needs |diff| < 50 m with a non-zero previous; cosmetic).
- T7 "week 9/28~10/4 named 9월 5주 in [주] but 10월 1주 bar in [월]" → stay (same convention as the cumulative map).
- T8 "MainTabsTest copies BikeNavHost glue (strip→stats, feed goal dialog, cumulative wiring untested)" → stay, but add these three to the
  S22 manual pass (strip tap lands on [주]·이번 주; strip [목표 정하기] saves both goals; 누적 지도 entry opens the period).
- T8 "feed goal dialog produceState .first() unguarded" → stay (DataStore has a corruption handler; same pattern elsewhere).
- T8 "README ≡ mentions" → resolved (no ≡/☰ in README at HEAD; only the field-test "≡ 버튼이 없음" check, which is correct).
- T8 "final JVM run before last tidy" → stay; the fix wave re-runs the suites anyway.
- T8 "StatsViewModel created on FEED tab show", "settings map size measured twice", "map-tab inset not asserted", leftover v140-feed PNGs on
  the phone → stay (housekeeping; delete stale PNGs before the screenshot step).
- T6 "buildFeed/weekStrip on Main + allRides collected twice", T7 "derivation on Main recomputing all-time parts per tap" → stay, mitigated by M4.
- T4 "dialog field not rememberSaveable" (rename), T7 "GoalDialog text remember not saveable" → stay (typing lost on rotation only).
- T5 all (Stale-as-control-flow, BitmapPng public, prune vs snapshot race, test gaps, Log.i noise, test-only factory, `online()` no guard,
  stale failed entry, throw outside guards, over-long stray files) → stay (robust in practice; race window is milliseconds at first open).
- T1 all (shadowed `ranges`, ALL omits future-dated rides, bounds(ALL), test gaps) → stay.
- T2 all (float boundary in `before = after − target`, projection can be "약 0 km" for < ~4 km/year, leading zeros, weak asserts, redundant
  guard) → stay.
- T3 (re-query tests, device goals cleared by test) → stay.
- T4 remaining (paste over selection at 30 cp, optional VM params, 10 s wait, locks map never pruned, setRegionName failure re-lookup,
  title/askRegion point requirements, import order, `limited()` JVM test, `labels.label()` unguarded in loader, autoTitle screen test) → stay.
- T6 remaining (feedThumbName duplicate, cardsUnderMonthHeaders coverage, detail VM tests location, dead `cardBadgeText`/`cardLine2Text`
  in `HistoryFormat.kt:30,36` — delete if convenient —, test nits, `RideStat.endedAt` default 0L, ride-less fallback, lambda shadowing) → stay.
- T7 remaining (`page()` reads state.value, PERIOD_ITEM hard-coded, duplicate mondayOf/WEEKDAYS, screenshot test, pointerInput keyed on
  inline lambda) → stay.
- T9 (FASTEST_AVG copy untested, discarding gate untested, no A→B reset test, import order) → stay.

## Declined to judge

- Weeks/"today" in Asia/Seoul instead of the device zone (spec §3.0 says device zone; ruling T4 covered titles only) — identical for a user in
  Korea, and `localDate` is already stored per recording; no visible effect.
- Kakao region lookups now sent for every trip's start point as cards appear — mandated by spec §2.5.
- After process death, the system-restored activity reopens on the last tab rather than [지도] — spec "새로 열면" read as a fresh launch.
- Month header counting trips ("7회") while stats count rides — spec §2.2 says "지금처럼".
- Card numbers exclude the open segment of an in-progress trip — spec §2.3 defines them as ended rides ≥ 10 m.
- Making pictures on mobile data without notice — spec §2.4.
- Black end dot, always-orange route, "전 주보다", band number format, Seoul titles, week-only cumulative chips — accepted rulings.
- Light-only colours (`GOAL_REACHED_TEXT` on secondaryContainer) — the app has no dark theme.

## Recommendations

1. One fix wave: I1, I2, I3, M1, M2 (+ goal-dialog gate), M3, M4, M5. All are local; none touches core or the schema.
2. In the S22 run, measure `feed_thumbs` file sizes (I4) and bring a WebP ruling to the user only if they are large.
3. Add to the S22 manual checks: map position kept across tab switches; feed scroll shows no line-drawing flash for already-made pictures;
   the three BikeNavHost glue paths listed above.
4. Then run the full JVM suites, the S22 class-by-class run, screenshots and install as planned in the T10 ruling.

## Assessment

**Ready to merge?** With fixes

**Reasoning:** Functionally complete against the spec, well-structured, correctly threaded, with a safe additive migration and no
critical defects. What remains is user-visible polish at the seams between tasks — the map tab resetting on every switch, emoji tab icons,
thumbnail flashing while scrolling, first-frame zero states, an inconsistent month header and a clipping ring — which matter because the
release's whole point is a polished Relive/Strava-like summary experience. All are small, local fixes; I4 needs a measurement before any
decision.
