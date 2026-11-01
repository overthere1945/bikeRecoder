# SDD ledger — plan: docs/superpowers/plans/2026-11-01-activity-types.md
Spec: docs/superpowers/specs/2026-11-01-activity-types-design.md (reachable). Branch feature/activity-types-v1.9.0, start 9b31f7b (main 80a62ac).

## Pre-flight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1↔T4 RideStore.openSegment | T1 changes interface w/ default `activity`; T4 implements | Kotlin overrides must repeat the param → T1 would have to touch RoomRideStore/test doubles before the column exists. Ruling R1 |
| T1↔T5 KmReached | T1 renames fields, keeps app compiling; T5 changes speech | ok |
| T1 RideStats new fields ↔ T5/T6/app tests constructing RideStats | — | Ruling R2 (defaults) |
| T2↔T8 SpeedBand.of(kmh, activity) | T2 produces, T8 MetricSeries consumes | ok |
| T2↔T5 BestEfforts/SunTimes | T2 produces, T5 consumes | ok |
| T3↔T5 KoreanPhrases/HikeAnnouncer | T3 produces, T5 consumes; T3 must fix app tests expecting old bike sentence | ok (T3 step 4 covers) |
| T4↔T9 RideStat fields, observeBestEfforts | T4 adds to RideStatsSource; all fakes must implement in T4 | ok |
| T4 dayTotals BIKE-only ↔ T5 P2 | consistent | ok |
| T5↔T6 RideUiState.Active.activity/sunsetMillis, ActivityText.sunsetLine | T5 produces, T6 consumes | Ruling R3 (defaults) |
| T6↔T7/T9 cardCells | T6 produces in ActivityText | ok |
| T7↔T10 RideTitle.base(activity) callers incl. video/share | T7 updates all callers | ok |
| T7↔T11 ExportModels (rideName T7, ExportSegment.activity T11) | sequential | ok |
| T5/T9/T10 SettingsRepository | sequential additions | ok |
| T1 self | `bikeProfileMatchesV180Engine` compares default vs of(BIKE) — tautological after change | Ruling R4 |
| T1 self | ActivityChangeTest needs deterministic hike wobble that never stays <0.7 km/h ≥20 s | note in dispatch |
| T2..T12 self | tests vs code agree (run 5'32" = 332 s/km for 5 km/27:40; hike sunset 2h <3h) | ok |
| All device steps | S22 not connected at start (adb devices empty) | Ruling R5 |

Ruling R1: Move the `RideStore.openSegment(..., activity)` signature change from Task 1 to Task 4 — avoids a placeholder override before the column exists — cost if wrong: none (T5 is first consumer).
Ruling R2: New `RideStats` fields `recentSpeedMps`/`lastLapMovingTimeMs` default to null in the constructor — keeps existing constructions compiling — cost if wrong: none.
Ruling R3: New `RideUiState.Active` fields default (`activity = BIKE`, `sunsetMillis = null`) — keeps existing tests compiling — cost: a caller could forget to pass activity; T5 tests pin the controller path.
Ruling R4: Task 1's bike regression test must use golden values captured from the unmodified V1.8.0 engine (run at BASE, hardcode events/totals), not compare default vs of(BIKE) — otherwise it proves nothing — cost: a little extra work.
Ruling R5: S22 not attached. Implementers run JVM tests and `:app:assembleDebugAndroidTest` (compile) for their androidTest; device runs of each task's classes are deferred to the controller's part B full run (or earlier if the S22 appears) — cost: device-only bugs surface later, all at once.
Task 1: dispatched (base 9b31f7b)
Task 1: implementer agent a097c88ccb911474f (sonnet)
Task 1: implementer DONE ee7e227; review package review-9b31f7b..ee7e227.diff
Task 1: review Approved, spec ✅. ⚠️ golden-at-base: accepted — reviewer verified BIKE profile constants are bit-identical to the engine defaults and the old DAY code path is unchanged; implementer reports capture at 9b31f7b.
Task 1: minor (deferred → T7): ActivityMix.of counts isEmpty segments toward badges/dominant — T7 callers must pass non-empty segments or ActivityMix should skip isEmpty (carry into T7 dispatch).
Task 1: minor (deferred → T5): a fix that jumps >1 km emits one KmReached whose lapMovingTimeMs spans several km — T5 pace sentence should use lap/(km-delta) or drop "이번 1킬로미터" then.
Task 1: minor (deferred → T6): recentSpeedMps only updates on add; stale if fixes stop without pause.
Task 1: minor (deferred): UsualSpeed.kt:19 long line; RecordingSessionTest import order.
Task 1: complete (commits 9b31f7b..ee7e227, review clean)
Task 2: dispatched (base ee7e227)
Task 2: implementer agent a3fc499747ea59013 (sonnet)
Task 2: implementer DONE 636faab, trailer amended by controller → 5eb6d00 (user rule: Opus trailer). Reviewer a6d2b003695867a8a dispatched.
Task 2: review Approved, spec ✅. ⚠️ KASI values: Seoul 06-21/12-22 come from a KASI-citing source found during planning; others are USNO cross-values (noted in plan) — accepted, open item for the user.
Task 2: minor (deferred): SunTimesTest farFromZoneMeridian test does not hit the date-shift branch; BestEfforts perf wall-clock assertion could flake under load; pace exactly at 1 km/h relies on FP equality.
Task 2: complete (commits ee7e227..5eb6d00, review clean)
Task 3: dispatched (base 5eb6d00)
Task 3: implementer agent ad36b209467f465b0 (sonnet). Ruling: spokenDuration(<60s) = "0분" — callers only pass ≥1 min — cost: none visible.
Task 3: implementer DONE fca7532
Task 3: review Approved, spec ✅. ⚠️ callers poll due() every second incl. pause → Task 5 (carry).
Task 3: minor (deferred): bare 2h/3h literals in KoreanPhrases; HikeAnnouncer skipped slot lost if sunset later changes; setSunset ~1 s window; import order; extra pace(<60s)="{s}초" and paceKmReport both-null fallback (not in spec, sensible).
Task 3: complete (commits 5eb6d00..fca7532, review clean)
Task 4: dispatched (base fca7532)
Task 4: implementer agent a8c6ac83a3fbdd5ca (sonnet)
Task 4: implementer DONE_WITH_CONCERNS e9c13d5 (no RED evidence; androidTests unrun per R5; import still BIKE → T11)
Task 4: review Approved, spec ✅. ⚠️ androidTests (RideDatabaseMigrationTest, RoomRideStoreTest) unrun → part B (R5). ⚠️ RoomImportStore saves BIKE → Task 11 (carry).
Task 4: minor (deferred): FakeRideStore.deleteTrip leaves bestEfforts; ReadOnlyRides.recentSegments no BIKE filter; no distinctUntilChanged test for observeBestEfforts; no rollback test; no RED evidence.
Task 4: complete (commits fca7532..e9c13d5, review clean)
Task 5: dispatched (base e9c13d5)
Task 5: implementer agent a2beeca5f7c76fd78 (opus). Dispatch rulings:
Ruling: pace km lap = lapMovingTimeMs / (km − previousKm) — a multi-km jump must not report several km as "이번 1킬로미터" — cost: slightly smoothed lap after GPS gaps.
Ruling: hike notification body appends " · 일몰까지 {duration}" from 1 h before sunset, " · 일몰 지남" after — spec §3.4 gives only "일몰까지 {시간}" — cost: wording tweak.
Ruling: banner for non-bike "● {이름} 기록 중 · {거리} · {clock}" (hike clock = elapsed) — spec §2.4 — cost: none.
Task 5: implementer DONE_WITH_CONCERNS 9464635. Implementer decisions: D1 hike start sentence waits ≤5 s for first fix (to add sunset tail); D2 chip read synchronously from container-held settings value (BIKE if not loaded); Starting also carries activity; hike paused from 1st second → no sunset until a point; guided-bike test lives in RideNavigationTest.
Task 5: review Needs fixes — Important: badge palette from isSystemInDarkTheme while app has only a light theme. Fix round 1 sent to implementer (FIX_BASE 9464635).
Task 5: minor (deferred): sunset recompute can drop a just-due stage (call due() before setSunset on recompute); chip quick taps may flip back (keep latest save job); no sunset until first recorded point (could fall back to lastFix); RideController growing — consider HikeTracker; ActivityStyle.name forwards to ActivityText.name; redundant HIKE takeIf in NavNotifications; D2 residual: chip tap then immediate [이어서] within DataStore write latency.
Task 5: fix round 1/5 (1 addressed, 0 open — badge palette now from app theme; commits 9464635..23a24b0)
Task 5: minor (deferred): darkness JVM test doesn't exercise the composable's surface read; ActivityChips selectedLabelColor hard-coded Color.White.
Task 5: complete (commits e9c13d5..23a24b0, review clean)
Task 6: dispatched (base 23a24b0)
Task 6: implementer agent a6270910dbcb4863c (sonnet).
Ruling: current pace shows --'--" while paused (recentSpeedMps isn't clock-aged) — handles Task 1 minor — cost: none.
Task 6: implementer DONE_WITH_CONCERNS 52f4cb1.
Ruling: SegmentSummary's top 3 cells use cardCells (feed-card formats) for every type incl. bike — spec §3.2 mandates the §2.2 3 cells on the summary for all types, so the bike summary's top row changes visibly; the remaining V1.8.0 cells (e.g. 최고) must still appear below — cost: bike summary looks different from V1.8.0 (spec-driven).
Task 6: review Needs fixes — Important: SegmentSummary rest cells drop speed cells for run/hike/walk.
Ruling: rest cells — run adds 평균 속도 (no 최고, per §3.2 run/walk replace 평균/최고 with 평균 페이스+평균 속도); walk unchanged; hike adds V1.8.0 평균/최고 cell (§3.2 keeps km/h for hike) — cost: small layout difference if user wanted 최고 for walk/run.
Task 6: ⚠️ elapsedMs refresh every second — resolved by controller: RideController tick loop calls session.onTick(clock()) → observe(now) → publish every 1 s, also while paused.
Task 6: minor (deferred): RecordContent reads `now` at top → whole screen recomposes each second for hikes; HikeBigCell fixed headlineLarge may clip at large font scale (check S22 screenshot); same-size test trivially true; RideNumbersPage activity default BIKE.
Task 6: fix round 1 sent (FIX_BASE 52f4cb1)
Task 6: fix round 1/5 (1 addressed, 0 open — run/hike rest cells restored; commits 52f4cb1..c569571)
Task 6: minor (deferred): test KDoc "no V1.8.0 number lost" inaccurate for run/walk 최고; bike summary mixes "10.8 km/h" (card) and "18.0km/h" (rest) spacing.
Task 6: complete (commits 23a24b0..c569571, review clean)
Ruling: ActivityMix.of ignores isEmpty segments (fallback to all if every segment is empty) — Task 1 minor; trip lists already hide empty segments — cost: none.
Task 7: dispatched (base c569571)
Task 7: implementer agent a125bec929bcbb990 (sonnet)
Task 7: implementer DONE_WITH_CONCERNS 2fd4997.
Ruling: bike feed card 3rd cell becomes 평균 속도 (was 상승) — spec §2.2 table explicitly fixes bike cells as 거리/이동 시간/평균 속도 and the spec outranks the plan's "bike copy unchanged" constraint — cost: bike card shows speed twice (cell + bottom line); revert = special-case BIKE.
Ruling: feed thumbnail route colour follows activity (bike orange → #D85A30), FeedThumbKey VERSION 2→3 forces a one-time redraw — spec §2.2 "지도 위 경로 선: 종류 색" — cost: one-time thumbnail regeneration on update.
Task 7: review Approved, spec ✅. ⚠️ device tests compile-only (R5) → part B; history rows = trip-detail segment rows (only live list rows) — accepted.
Task 7: minor (deferred): feed dominant from rides vs video from targets (empty-point edge); weekStripOf extra comparison; long lines/import order; FeedThumbnailRenderer imports Compose toArgb via ActivityStyle; mixed-trip bottom line averages all segments in dominant style.
Task 7: complete (commits c569571..2fd4997, review clean)
Task 8: dispatched (base 2fd4997)
Task 8: implementer agent add9f5c6d789f1286 (sonnet)
Task 8: implementer DONE_WITH_CONCERNS f4fc7e3 (app side not strict TDD).
Ruling: walk pace-chart legend keeps the km/h band labels ("3 미만"…) — spec §3.5 band table states walk bands in km/h while run's are in pace; title/end labels/scrub/read-out are pace — cost: mixed units in the walk legend; switch to pace labels if the user prefers.
Task 8: review Approved, spec ✅. ⚠️ (a) recompute auto-pause/minimum — verified in Task 1 review; (b)(c) device-only → part B.
Ruling: keep the change-activity failure Toast ("CHANGE_FAILED" copy) even though trip rename/delete failures are log-only — telling the user a recompute failed is better than silence — cost: one extra string not in spec.
Task 8: minor (deferred): MetricChart remember keys miss pace/top/bottom (latent stale labels); pace bottom end label often --'--"/km after stops; 산행 시간 duration on trip vs clock on segment; changeActivity captures stale segment before mutex; badges computed in composable; app side not test-first; no UI test for hidden [⋮] on open segment.
Task 8: complete (commits 2fd4997..f4fc7e3, review clean)
Task 9: dispatched (base f4fc7e3)
Task 9: implementer agent a35960f2089486d38 (sonnet)
Task 9: implementer DONE_WITH_CONCERNS a596b99 (hero distance kept on every chip; hike bars use elapsed; bike-worded streak/best-week rows on all chips; cumulative map route arg &activity=)
Task 9: review Approved but spec ❌ (copy outside ActivityText; hike chip keeps distance).
Ruling: hike chip keeps the period distance headline and DISTANCE bar metric — §4.1 lists cells; the headline is not a cell — cost: one more number than spec's hike cells; drop it if the user wants.
Ruling: implementer calls #2 (hike time bars use elapsed) and #3 (bike-worded streak/best-week rows on all chips, "전체" group for those rows) accepted — consistent with 산행 시간 and the V2.0.0 wording rule — cost: wording.
Task 9: minor (deferred): stale totals help topic for non-bike chips; SummaryExtras effort time firstOrNull vs core min; chip change waits on DataStore write; data layer imports ui.stats.GoalKm; weak recordHolderTrips assertion; duplicate chip test tags in goal dialog.
Task 9: fix round 1 sent (FIX_BASE a596b99) — copy into ActivityText.
Task 9: minor (deferred): RideNumbers.kt/SegmentSummary.kt still hold the 3 label literals (Task 6 files) — final fix wave.
Task 9: fix round 1/5 (1 addressed, 0 open — stats/goal copy into ActivityText; commits a596b99..ef3216b)
Task 9: minor (deferred): "목표" also in GoalDialog/CumulativeChart (likely pre-V1.9.0); fix report lacked output counts.
Task 9: complete (commits f4fc7e3..ef3216b, review clean)
Task 10: dispatched (base ef3216b)
Task 10: implementer agent af051444ee44779d7 (sonnet).
Ruling: fastestKmMs comes from saved segment_best_effort ONE_K rows of the trip's RUN segments (may add RideStore read method) — avoids recomputing over all points in the video job — cost: needs best efforts saved (finish/import/change paths do).
Task 10: implementer DONE_WITH_CONCERNS 5290da6 (added RideStore.bestEfforts(segmentId)).
Ruling: share run/walk distance uses kmText (one decimal, "5.0 km") as the bike share line does, not the dispatch example "5.00 km" — keeps share lines consistent — cost: none.
Task 10: review Approved, spec ✅. ⚠️ pixels/device → part B.
Ruling: bike video title card gets the activity badge too (name shifts/shrinks) — spec §5.1 "제목 옆에 종류 배지" applies to every type and outranks the plan's bike-unchanged constraint — cost: bike title card looks different from V1.8.0.
Ruling: closing-card count cell says "활동 N회" for non-bike dominants (bike keeps "주행 N회") — "주행" is wrong for a run/hike — cost: one unrequested label.
Ruling: HUD hike ascent uses a 3 m AscentCounter threshold — P11 names none — cost: HUD ascent can differ slightly from the card's saved ascent on GPS-only hikes.
Task 10: minor (deferred): tautological bikeZoomUnchanged assertion; SettingsRepository four parallel when-blocks and data→share import; app-side RED not captured.
Task 10: complete (commits ef3216b..5290da6, review clean)
Task 11: dispatched (base 5290da6)
Task 11: implementer agent a57e6c264c5e84a71 (sonnet)
Task 11: implementer DONE_WITH_CONCERNS f1b42c1 (ImportStore.saveBestEfforts(tripId, efforts); ChangeActivityDialog note param reused for import picker)
Task 11: review Approved, spec ✅. ⚠️ device runs → part B.
Ruling: accept ImportStore.saveBestEfforts(tripId, efforts) instead of injecting RideStore into the import job — same DAO, no AppContainer change — cost: a second entry point for the same write.
Ruling: running/walking/hiking GPX rows start checked like bike rows — spec §5.3 removes the NotCycling block — cost: user must uncheck unwanted non-bike files.
Task 11: minor (deferred): replay with a chosen type may skip a ride silently as "skipped"; import order/long KDoc lines; inline FQNs in tests; unreadable ScanRow carries BIKE.
Task 11: complete (commits 5290da6..f1b42c1, review clean)
Task 12: dispatched part A (base f1b42c1)
Task 12: implementer agent afedfbc78b411026d (sonnet). Step 3 skipped (R5).
Task 12: implementer DONE_WITH_CONCERNS 2377185 (v190-chips screenshot has no test; sunset wording)
Task 12: review Needs fixes — Important: sunset provenance wording wrong (controller's note was wrong); v190-chips screenshot has no producer. Fix round 1 sent (FIX_BASE 2377185).
Task 12: minor (deferred): verification doc pins f1b42c1 — restate commit in part B record.
Task 12: fix round 1/5 (2 addressed, 0 open — sunset provenance; v190-chips screenshot test; commits 2377185..2df3731)
Task 12: minor (deferred): v190-chips shot shows chips alone, not the whole map screen; screenshot helpers pass silently if insert/takeScreenshot null (check files in part B); SunTimesTest values lack provenance comments.
Task 12: complete part A (commits f1b42c1..2df3731, review clean). Part B (device run/install) pending S22.
Final review: dispatched a09a9fe4242377ced (opus), range 80a62ac..2df3731
Part B-pre: S22 attached (user). Running 25 changed/affected androidTest classes one by one (run-at-190a.sh → at-results-190a.txt) while final review runs; failures feed the single final fix wave. Full run after fixes.
Final review: With fixes. Important: (1) splitSegment (only caller = guidance start "새 구간") inherits non-bike activity → P14 bypass; (2) change-activity recompute shrinks ascent/descent permanently (stored fused altitude re-fed as GPS, 5 m threshold; −30% measured); (3) mixed trips show dominant-type pace computed over all segments. Minors: interrupted RUN never gets best efforts; sunset recompute drops a just-due stage (safety); last-1km screen vs voice multi-km; sunset line uses systemDefault zone; change dialog stale read; CHANGELOG lacks cumulative-map bike colour change. Recommends fixing T9 leftover label literals and T6 km/h spacing in the same wave.
Ruling: splitSegment always opens BIKE (its only caller is the guidance start) and spec §1.4's split sentence is corrected — P14 is the binding intent — cost: none (day change never splits).
Ruling: change activity recomputes only distance, moving time, max speed (and avg); ascent, descent, min/max altitude and elapsed keep the stored values; spec §1.5 text amended — altitude processing is type-independent (§1.3) and re-feeding fused altitude corrupts it — cost: none of the type-dependent numbers lost.
Ruling: mixed trips keep the dominant type's cells (§2.2) but pace values (card cell, bottom line, share line, video closing card) are computed from the dominant-type segments only; 산행 시간 already hike-only — cost: pace and the trip-total distance cell describe different subsets.
Device pre-run: 25 classes; FeedScreenTest 2 fail, ImportScreenTest 2 fail (details pending re-run).
Device pre-run details: StatsScreenTest 3 (stats-bar-value text blank after bar tap: tappingABarShowsItsValueAndAgainHidesIt, tappingElsewhereHidesTheBarValue, barsOfDaysThatHaveNotComeCannotBeTapped); FeedThumbnailRendererTest 1 (picture1280x720WithTheRouteInOrange: route 0); FrameRendererTest 2 real (titleCardBadgeFollowsTheActivity: x must be >= 0 in count(); introRouteClearsTheTitle: route inside title words 773) + screenshots assumption (not a failure, gradle skips); SummaryImageRendererTest screenshots assumption only (rc 0); FeedScreenTest 2 (goal ring '100 / 100 km 달성' and '10,234 / 10,000 km 달성' not displayed); ImportScreenTest 2 (import_badge_2_0 only in unmerged tree).
Final fix wave: dispatched (FIX_BASE 2df3731), brief final-fix-brief.md
Final fix wave: DONE_WITH_CONCERNS 708dc95..a30b426 (11 commits). core 665/0, app 1386/0. C-items diagnosed: stats taps need scroll (chips push bars down; test fix), thumbnail bike colour #D85A30 (test), video badge tests (test bugs), week ring tests lacked bike-only distance (test), import badge unmerged (test).
Ruling: A3 dominant = longest single segment (existing ActivityMix rule), brief's example adjusted — cost: none.
Ruling: walk closing-card avg speed and mixed-trip bike avg speed stay trip totals (A3 covers pace only) — cost: walk-led mixed trip video can show pace and speed from different subsets; parked minor.
Final fix re-review: Approved (16/16 addressed). Minors: spec §2.2/§3.2 not amended → fixed by controller (docs commit); A3 speed cells trip totals (parked, ruled); boundsOf peach bbox laxity (parked); WeekStripState.goalDistanceM copy() trap (parked). C12 diagnosis to confirm on S22.
Device re-run of 5 failing classes after fix: all pass (FrameRendererTest 1 = screenshots assumption skip). Full run 73 classes started at 8626282.
Ruling: stats streak/record copy keeps "주행/탄" under non-bike chips — spec §1 line 40 and §2 line 151 defer app-wide "주행" copy to V2.0.0 — cost: hike chip reads "1주 연속 주행 중" until 2.0.
Part B: full S22 run 73/582/580 pass/2 skip at a30b426; 6 screenshots checked; seed migrated 7→9; on-device change-activity WALK and back checked; verification doc 9253351. Final review clean.
