# SDD ledger — plan: docs/superpowers/plans/2026-10-31-summary-stats.md
Spec: docs/superpowers/specs/2026-10-31-summary-stats-design.md
Branch: feature/summary-stats-v1.4.0 (start e76a4fa spec, cce94a0 plan; merge-base main 7a1cd19)

## Pre-flight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | RideStat, StatsCalculator.rides/periodOf | consistent |
| T1→T3 | RideStat (RideStatsSource emits it) | consistent; RideStatsSource lives in core.stats after T1 |
| T2→T7,T9 | Streak, RideRecords.*, Goals.parse/crossed/weekBeforeAfter/yearBeforeAfter, GoalInput | consistent |
| T3→T4 | TripLabel, TripLabelStore | consistent |
| T3→T6,T7,T9 | RideStatsSource, AppSettings goals, setGoals | consistent |
| T4→T5 | VideoInputsLoader(+TripLabelStore).load | T5 renders through the loader T4 changed; consistent |
| T4→T6 | RideTitle.shown, TripRegions.ensure | consistent |
| T5→T6 | FeedThumbnails.want/unwant/files/delete, FeedThumbKey.of(ThumbSegment) | consistent |
| T6→T8 | FeedScreen(onSetGoals, onOpenStatsWeek, scrollToTop, onBack?) | T6 has no GoalDialog yet (T7) — see ruling P1 |
| T7→T8 | StatsScreen, StatsViewModel.show, GoalDialog | consistent |
| T6/T8 HistoryScreen | T6 makes HISTORY render FeedScreen; T8 deletes HistoryScreen.kt + route | no conflict (file unused between) |
| T1 self | tests vs signatures | agree |
| T2 self | tests vs signatures | agree |
| T3 self | migration via AutoMigration 5→6, schema 6.json | agree (D1) |
| T4 self | TripRegions in app feed pkg; VideoJob uses ensure | agree |
| T5 self | renderer androidTest + queue JVM test | agree |
| T6 self | moves HistoryViewModel(+Test) → FeedViewModel | agree |
| T7 self | GoalDialog/StatsScreen tests | agree |
| T8 self | Routes.cumulative args; SettingsScreen onBack nullable | agree |
| T9 self | SummaryExtras new ctor params with defaults | agree |
| T10 self | version bump + SettingsScreenTest V1.4.0 | agree |

Ruling P1: in Task 6 the temporary HISTORY route passes onSetGoals = {} and onOpenStatsWeek = {} (no stats tab yet); Task 8 wires both — T6 cannot reach T7's dialog — cost if wrong: none (route removed in T8).
Planning rulings D1–D6 live in the plan header (trip_label table, orange thumb route, process-memory retries, new-record needs earlier ride of that kind, same-day comparison ends, 30 code points).

## Tasks
Task 1: dispatched (BASE cce94a0, implementer sonnet)
Task 1: Ruling: Bucket.upcoming = bucket.start > today (the plan's example 'today 10-28 → last October bucket upcoming' is wrong: that bucket holds today and its rides) — the spec says '오지 않은 칸' — cost if wrong: none
Task 1: minor (deferred): StatsCalculator.kt:80-87 inner `ranges` shadows outer; ALL buckets omit future-dated rides (undocumented); bounds(ALL) inconsistent with previous/next throwing; test gaps (segmentId tiebreak, future-period comparison, Oct31→Sep30, ALL upcoming); test helper startedAt order-dependent
Task 1: complete (commits cce94a0..2aaefa6, review clean)
Task 2: dispatched (BASE 2aaefa6, implementer sonnet)
Task 2: Ruling: a ride record whose best value is 0 (e.g. MOST_ASCENT with 0 m everywhere) is not a record — no row, no trophy, never 'new' — a '가장 많이 오른 주행 0 m' row reads as broken — cost if wrong: one missing row on flat-only histories
Task 2: review → Needs fixes (1 Important: zero-valued record ruling not implemented)
Task 2: minor (deferred): Goals.kt:92 before = after − target (float at exact boundary); yearProjectionKm can be 0 (UI decides); parse accepts leading zeros; RideRecordsTest:589 weak assertNotNull
Task 2: fix round 1/5 (1 addressed, 0 open — zero-valued records; commits ffc2df6..5ba33ad)
Task 2: Ruling: after rides whose value was 0 for a kind (e.g. all flat), the first positive value counts as new ("가장 많이 오른 주행 신기록") — an earlier ride exists and the value beats the earlier best; consistent with D4 — cost if wrong: one extra 🏆 line on a first hilly ride
Task 2: minor (deferred): zeroValuedRecordsAreNotRecords trophy assertions don't discriminate pre-fix code; newRecords guard at RideRecords.kt:102 redundant
Task 2: complete (commits 2aaefa6..5ba33ad, review clean)
Task 3: dispatched (BASE 5ba33ad, implementer sonnet)
Task 3: minor (deferred): observeLabelsEmitsOnChange / observeRidesEmitsEndedSegmentsOnly re-query with .first() instead of proving re-emission; SettingsRepositoryGoalsTest clears device goals (could save/restore)
Task 3: complete (commits 5ba33ad..11db2d6, review clean)
Task 4: dispatched (BASE 11db2d6, implementer sonnet)
Task 4: Ruling: the automatic title uses the Asia/Seoul zone everywhere (as VideoInputsLoader already does), not the device zone of spec §2.5 — card, detail and video titles must agree; the user rides in Korea — cost if wrong: a trip ridden abroad gets a Korean-time "아침/저녁" word
Task 4: Ruling: a failed region lookup is not retried for the rest of the process, video jobs included (D3) — previously each video job retried — cost if wrong: a video made offline-then-online in one app run lacks the region
Task 4: review → Approved with 1 Important (rename dialog placeholder shows custom title, not auto title) → fix loop
Task 4: minor (deferred): paste over selection at 30 cp ignored; optional VM params silently disable rename (factory untested); possible 10 s wait video vs screen lookup; locks map never pruned; setRegionName failure re-looks-up; dialog field not rememberSaveable; title/askRegion don't require points; RenameTripDialogTest import order; limited() no JVM test; VideoInputsLoader doesn't guard labels.label()
Task 4: fix round 1/5 (1 addressed, 0 open — placeholder now autoTitle; commits 5324e37..80045e8). Re-review's "missing assertDoesNotExist import" checked by controller: assertDoesNotExist is a SemanticsNodeInteraction member; :app:compileDebugAndroidTestKotlin passes at 80045e8 — not a defect.
Task 4: minor (deferred): TripDetailScreen autoTitle wiring has no screen test
Task 4: complete (commits 11db2d6..80045e8, review clean)
Task 5: dispatched (BASE 80045e8, implementer sonnet)
Task 5: review → Needs fixes (2 Important: worker dies on Error/OOM; readDirectory !! NPE kills worker)
Task 5: Ruling: the feed picture's end dot is MapMarks' black end (as in the video and summary image), not the "red" of spec §2.4 — same marks everywhere — cost if wrong: one dot colour
Task 5: Ruling: startup prune runs on first access of the lazy feedThumbnails (Task 6 must touch it when the feed opens) — housekeeping only — cost if wrong: orphan PNGs linger until the feed is opened
Task 5: minor (deferred): Stale exception as control flow (FeedThumbnails.kt:492-500); BitmapPng public only for tests; prune vs DB snapshot race; test gaps (rename→publish delete window, unwanted in-flight saved, NetworkWaiter.online); device test Log.i noise; renderer test-only satellite factory param; online() no guard if registerDefaultNetworkCallback throws; stale failed entry after forget
Task 5: fix round 1/5 (2 addressed, 0 open — Throwable guards, mapNotNull; commits 7329e4e..5608b45)
Task 5: minor (deferred): throw outside make's guards leaves pair unmarked (retried on next wake); over-long stray files never pruned
Task 5: complete (commits 80045e8..5608b45, review clean)
Task 6: dispatched (BASE 5608b45, implementer sonnet)
Task 6: controller screenshot check (v140-feed): layout matches spec; the strip draws under the status bar (no top inset) — carry to Task 8 (tab scaffold must apply system-bar insets); month header "32.10km" (old HistoryFormat) vs card "32.1 km" — note for final review
Task 6: review → Needs fixes (3 Important: week strip today frozen; thumbKey withheld for ACTIVE trips w/o open segment; N+1 segments reads per load)
Task 6: minor (deferred): buildFeed/weekStrip on Main + allRides collected twice; feedThumbName duplicates FeedThumbnails.nameOf; mixed km formats (header 32.10km vs card 32.1 km; ring truncates 99/100 while strip says 100.0); header totals include <10 m/open segments unlike cards; cardsUnderMonthHeaders coverage lost (order, Role.Button, 48dp); trip/segment detail VM tests live in FeedViewModelTest; cardBadgeText/cardLine2Text dead; rememberPicture decodes full PNG uncached; test nits (stripShowsAMinus… name, trophy test single-holder, Thread.sleep screenshot, region start may come from <10 m segment); stale v140-feed.png on phone
Task 6: fix round 1/5 (3 addressed, 0 open — today state, open-segment key, RideStat.endedAt bulk; commits ef75a67..1b141e2)
Task 6: minor (deferred): RideStat.endedAt default 0L (required param safer); ride-less trips now fall back to lastStartedAt; weekStrip lambda shadows `today`
Task 6: complete (commits 5608b45..1b141e2, review clean)
Task 7: dispatched (BASE 1b141e2, implementer sonnet)
Task 7: Ruling: a past week's comparison reads "전 주보다 …" (not "지난주보다", which means the week before this one and is wrong when viewing older weeks); this week keeps "지난주 이맘때보다" — spec example only covered last-vs-previous — cost if wrong: wording
Task 7: review → Needs fixes (2 Important: past-week "전 주보다" ruling not applied; tap elsewhere doesn't clear bar value)
Task 7: minor (deferred): derivation on Main recomputing all-time parts per tap; first frame shows empty-state content before Room/DataStore emit (flicker); page() reads state.value (lost double tap); GoalDialog text remember not saveable; PERIOD_ITEM hard-coded index; ring text clipping with large goals; duplicate mondayOf/WEEKDAYS; screenshot test can pass without writing + Thread.sleep; "+0.0 km (+0%)" tie wording; week 9/28~10/4 named "9월 5주" in [주] but "10월 1주" bar in [월]
Task 7: fix round 1/5 (2 addressed, 0 open — 전 주보다, outside tap clears; commits 32aa5fd..2289557)
Task 7: minor (deferred): pointerInput keyed on inline lambda
Task 7: complete (commits 1b141e2..2289557, review clean)
Task 8: dispatched (BASE 2289557, implementer opus; carries Task 6 inset finding)
Task 8: Ruling (implementer's, accepted): a week-only cumulative filter also carries year+month so the map's chips show the week (today's month if this week, else the Monday's month); filtering unchanged — cost if wrong: chip label only
Task 8: controller screenshot check (v140-band): insets fixed (strip below status bar, band above tab bar). Tab icons are mixed colour emoji (🗺 📰 📊) + monochrome ⚙ — looks unpolished against the '세련' goal; for final review: consistent monochrome icons
Task 8: Ruling: the band uses kmText ("12.4 km") + RideFormat.duration ("48분"), not the record screen's distance/clock formatters — spec §1.4's own example strings need this format — cost if wrong: band reads differently from the record screen
Task 8: minor (deferred): map rebuilt and camera snaps back to user on every tab switch (user-visible; fix: keep camera in MainViewModel or keep MAP composed); showThisWeek pins a non-null anchor (doesn't follow day rollover; plan-mandated wording); stats-tab goal dialog seeds from stateIn initial nulls (race could wipe other goal), feed dialog produceState .first() unguarded; MainTabsTest copies BikeNavHost glue (strip→stats, feed goal dialog, cumulative wiring untested); map-tab inset not asserted; final JVM run before last tidy; v140-feed (1)/(2).png leftovers; README ≡ mentions; settings map size measured twice; StatsViewModel created on FEED tab show
Task 8: complete (commits 2289557..fb03bd1, review clean)
Task 9: dispatched (BASE fb03bd1, implementer sonnet)
Task 9: minor (deferred): FASTEST_AVG copy untested (4-line max); discarding gate untested + confusing comment; no A→B segment reset test; goals flow lacks distinctUntilChanged (any setting change recomputes; goal edit can add a 달성 line on an open summary); import order / double blank line
Task 9: complete (commits fb03bd1..7f199db, review clean)
Ruling T10: Task 10 is split — now: version 1.4.0, docs, JVM full run, docs placeholder for device results; the full S22 run (Step 3), screenshots (Step 4) and S22 install (Step 6) run after the final review's fix wave, so they test the final code (as in V1.3.1) — cost if wrong: none
Task 10: part A dispatched (BASE 7f199db, implementer sonnet: version, docs, JVM run)
Task 10: part A done (c1fc97b) — version 1.4.0/10400, docs, AppVersionTest; S22 full run + install deferred until after the final fix wave
Task 10: part A review dispatched (sonnet); final whole-branch review dispatched (opus, 7a1cd19..c1fc97b → final-review.md)
Task 10: part A review → Approved (spec ✅). minor (for final fix wave): CHANGELOG line 47 reads as if goals are stored in DB (they're in settings); VERSIONING.md roadmap lacks V1.3.1/V1.4.0 rows; spec §1.4 "same formatter as record screen" contradicts band ruling (amend spec); check table check 1 dense
Final review → With fixes (0 Critical; Important I1 map rebuilt on tab switch, I2 mixed emoji tab icons, I3 feed picture flash on scroll (uncached decode), I4 PNG files may be ~2 MB each; fix-before-merge minors M1 km formats/header totals, M2 first-frame empty state (+goal dialog seed), M3 ring text overflow, M4 stats/feed rebuild every 5 s while recording + Main-thread derivation, M5 showThisWeek fixed anchor). M6 (▶ 영상 stays after gallery video deleted) stays deferred. Full review: final-review.md
Ruling: I4 decided without asking — the fix implementer measures PNG vs WebP-lossy size of a real 1280×720 render on the S22; if WebP is at least 2× smaller, pictures are stored as WebP lossy q90 (`.webp`), FeedThumbKey.VERSION bumps to 2, and prune also deletes legacy `.png` files — storage on a phone with hundreds of trips outweighs a lossless picture the user can't tell apart — cost if wrong: slightly softer card pictures; file name differs from spec §2.4 ".png"
Ruling: M6 stays deferred (feed ▶ 영상 not rechecked against the gallery) — the trip screen already checks; fixing needs MediaStore queries per card — cost if wrong: a dead ▶ 영상 tap shows the trip screen's 'video gone' state
Final fix wave: dispatched (BASE c1fc97b, implementer opus) — brief final-fix-brief.md
Final fix wave: done (c1fc97b..05a7b1f, 9 commits; I4 measured PNG 813 KB vs WebP 106 KB → WebP q90, VERSION 2; JVM 375+946 green; S22 6 classes green)
Final fix wave: scoped re-review dispatched (opus, c1fc97b..05a7b1f)
Final fix wave: re-review → all findings ADDRESSED, no new Critical/Important. Residual minors: (a) feed empty-state/[기록 시작] could flash one frame above real trips on first [요약] open (M4 hop) — Ruling: fixed by controller (ready + hiddenShortCount now derived from the built feed) — misleading button on every cold open — cost if wrong: none; (b) page() reads state.value one hop behind (two very fast ‹ taps move one period) — Ruling: stays deferred — cosmetic; (c) leaving [지도] within <1 s of first centring may restore all-of-Korea — Ruling: stays deferred — rare, 내 위치 fixes it. Out-of-scope: cache holds ~8 pictures at 32 MB (RGB_565 would double) — deferred; FeedThumbnailRendererTest size test needs internet — note in verification doc.
Task 10: part B done — S22 full run 53 classes / 367 tests green (2 screenshot-gated assumption skips), screenshots pulled, S22 installDebug 1.4.0-dev/10400; docs commit 616dbf0
Task 10: complete (commits 7f199db..616dbf0)
