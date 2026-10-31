# SDD ledger — plan: docs/superpowers/plans/2026-10-31-elevation-export.md
Spec: docs/superpowers/specs/2026-10-31-elevation-export-design.md. Branch feature/elevation-export-v1.7.0 from main 7a0dcc4. Plan commit d75e307.

## Preflight scan
| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| T1→T2 | ElevationProfile(slice, pointAt, gradesPct), GradeBand | consistent |
| T1→T4 | bandAt, BandRun, GradeBand.argb/legend | consistent |
| T2→T4 | AheadState used by ElevationText.aheadLine (T4) | consistent (T4 after T2) |
| T2→T6 | NavState.profile/ahead, UsualSpeed, RideStore.recentSegments | consistent |
| T3→T7 | ExportSegment, GpxWriter/KmlWriter; PostStore.samples is app `post/PostStore.kt` (not core) | ok — T7 consumes app PostStore |
| T4→T5/T6 | ElevationChart, openElevation, ElevationHandoff, routeColors, ElevationStrip | consistent |
| T5↔T7 | both edit TripDetailScreen/VM/Test | sequential, ok |
| T6↔T7 | both edit PlanScreen/VM/Test | sequential, ok |
| T1 self | Route default param keeps old calls compiling | ok |
| T2 self | RideStore new abstract method → app fakes must be updated; EtaEstimator listed "modify" but unchanged | ok |
| T3 self | round-trip uses V1.6.0 GpxReader/RideReplay | ok |
| T4 self | ElevationStrip text mentions `flatColor` param but signature lacks it | Ruling below |
| T5 self | ok |
| T6 self | NavigateScreenTest does not exist yet → create | ok |
| T7 self | DownloadsWriter tests delete what they write | ok |
| T8 self | ok |

Ruling: ElevationStrip has no flatColor parameter; the strip always uses the GradeBand table colours (FLAT blue) — spec §3.1 limits the FLAT override to the guidance map line; cost if wrong: strip FLAT blue differs slightly from the nav line colour.
Task 1: dispatched implementer (opus), BASE d75e307
Task 1: implementer DONE (5973643) — 1605 tests green
Task 1: review Approved. Carry to T2: last grid cell / slice tail can be shorter than stepM → time & lengths must use distancesM differences.
Task 1: minor (deferred): grade window 100 m not pinned by a test (kink test 0%→8% at 500 m); bandsMergeRuns uses 8% = 6/8 boundary (use 7%); slice exactly at a segment seam starts at previous segment end (d >= from); slice NaN length not null; infinite elevations not filtered (isFinite); no exact-50 m boundary tests; slice-doesn't-re-smooth grades untested.
Task 1: complete (commits d75e307..5973643, review clean)
Task 2: dispatched implementer (opus), BASE 5973643
User (2026-11-01): S22 connected; run everything incl. all tests; no step reports, one final report. S25/merge/push still need asking.
Task 2: implementer DONE_WITH_CONCERNS (ccf60a2) — core+brouter 516, app 1127; S22 RoomRideStoreTest 12/12. Concerns: deferred climb dropped on reroute but marked announced; climb boundary model by implementer (findIn).
Task 3: dispatched implementer (sonnet), BASE ccf60a2 (in parallel with T2 read-only review; T2 fixes wait for T3 commit)
Task 2: review Approved (opus). Minors: (1) no session test that reroute swaps profile; (2) deferred-climb-dropped-after-start untested; (3) DOWN_K 0.03 not pinned (-5% → 1.15×); (4) avg grade = gain/length not distinguished from sample mean; (5) recentSegments SQL lacks movingTimeMs > 0; (6) RideTimeEstimator scans whole profile per fix (perf, defer to final).
Ruling: concern (a) accepted as-is + KDoc line — window ~1 s and new gap would be <300 anyway (spec §3.3) — cost if wrong: rare missed climb voice after reroute.
Ruling: T2 fix round 1 (after T3 commits, sequential): add tests for minors 1–4, add `movingTimeMs > 0` to recentSegments SQL, KDoc for (a). Minor 6 → final review.
Task 3: implementer DONE (114f92f) — core 516 (+19 new)
Task 3: reviewer dispatched (sonnet) on review-ccf60a2..114f92f.diff
Task 2: fix round 1 dispatched (resume implementer), base 114f92f
Task 3: review Approved. Minors deferred to final: UTC test TZ-dependent (set Asia/Seoul in test); no empty-segment/empty-trip test; KML tests lack stop-name escape, 1-point segment, NaN skip; escapeXml lone surrogates/FFFE; HR binary search duplicates HeartRateColor.matchedBpm; String.format per point (perf check in T7). Carry to T7: exportRoute must pass ExportLabels.routeName(destinationName).
Task 3: complete (commits ccf60a2..114f92f, review clean)
Task 2: fix round 1 DONE (4dfa583) — core 520, app 1127; S22 RoomRideStoreTest 12/12. Controller checked diff inline: 4 tests + SQL + KDoc as asked. All findings addressed.
Task 2: complete (commits 5973643..ccf60a2 + 4dfa583, review clean)
Task 4: dispatched implementer (opus), BASE 4dfa583
Task 4: implementer DONE_WITH_CONCERNS (bf4e547) — core 520, app 1143; S22 ElevationChartTest 5/5, ElevationMapScreenTest 5/5. Concerns: screenshot lacks base tiles (MapLibre requests stall in test process); route GeoJSON now built on Dispatchers.Default (one-frame lag, cancellation); scrub held twice (chart saveable + VM). Deviations: ElevationMapScreen(viewModel, tileSource, onClose, map slot); MapOverlay.endpoints added; ElevationText CLOSE, kmTick.
Task 4: review Needs fixes (opus). Important: km tick text float error (0.6000000000000001km). Minors: ChartColors no equals → drawWithCache invalidated; scrub double-held → mismatch after process death; scrub line covers dot; stripDimmed test weak, no no-flag test; VM tests lack null-profile/exception → Closed. Deviations 1–2 approved; Default-dispatcher GeoJSON approved; tiles not caused by diff.
Ruling: distance text uses SummaryFormatter.distance ("1.2km") even though spec §3.2 example shows "1.2 km" — §5 names the formatter, app-wide style — cost if wrong: one space.
Carry to T6: remember routeColors list; swap route and colors together.
Task 4: fix round 1 dispatched (resume implementer): Important 1 + Minors 2,3 (hoist scrub, single source),4,5,6.
Task 4: fix round 1 DONE (d7438ee) — core 520, app 1146; S22 ElevationChartTest 6/6, ElevationMapScreenTest 5/5. Controller checked diff inline: scrub hoisted (scrubM param, chart holds none), drawWithCache remembered, kmTick fixed + tests, alpha semantics, VM Closed tests.
Task 4: complete (commits 4dfa583..bf4e547 + d7438ee, review clean)
Task 5: dispatched implementer (sonnet), BASE d7438ee
Task 5: implementer DONE_WITH_CONCERNS (5d44535) — PC green; S22 TripDetailScreenTest 12/12, SegmentDetailScreenTest 8/8. Notes: descent/max via existing total.*; profileLoaded flag; SegmentDetailState altitude→profile.
Task 5: review Approved (sonnet). Deviations 1–4 OK. Minors deferred to final: trackOf set before profile compute → on failure profileLoaded stays false (set in finally); start-order test not discriminating (same-day case); descent/max test larger max in later segment; RideCharts.altitude dead code; import order; chart item appears after load (list shift).
Task 5: complete (commits d7438ee..5d44535, review clean)
Task 6: dispatched implementer (opus), BASE 5d44535
Task 6: implementer DONE_WITH_CONCERNS (6a1559b) — core 521, app 1166; S22 PlanScreenTest 8/8, NavigateScreenTest 5/5 (new). Concerns: field named elevation (profile taken); core SummaryFormatter.durationOf added; off-route dim via lateralOffset>=40 m (app OFF_ROUTE_M dup, earlier than 8 s confirm); routeColors String.format on main on route change; plan reserves third row + 100dp always; nav screenshot via TurnCard+BottomPanel internal.
Task 6: review Needs fixes (opus). Important: plan usual speed read once per Activity-scoped VM → stale after new rides. Minors: await without timeout; silent read failure; bandHex String.format per point; plan bottom panel no scroll; untested flatHex/plan nav wiring. Concerns 1–6 accepted; off-route 40 m → expose core OffRouteDetector.DEFAULT_THRESHOLD_M.
Task 6: fix round 1 dispatched (resume implementer): Important + Minors 1–4 + 40 m constant.
Task 6: fix round 1 DONE (58817e7) — core 521, app 1168; S22 PlanScreenTest 8/8, NavigateScreenTest 5/5. Controller checked diff inline: usual speed re-read per route w/ 2 s timeout + log; BAND_HEX cache; OffRouteDetector.DEFAULT_THRESHOLD_M; plan panel scroll.
Task 6: complete (commits 5d44535..6a1559b + 58817e7, review clean)
Task 7: dispatched implementer (opus), BASE 58817e7
Task 7: implementer DONE_WITH_CONCERNS (09c7408) — core 521, app 1184; S22 DownloadsWriterTest 2/2, ExportDialogTest 3/3, TripDetailScreenTest 15/15, PlanScreenTest 9/9; 30k-pt GPX 526 ms. Deviations: Saved(uri String, name, mime); exportTrip(title); ExportSink; ExportRunner.kt. Concerns: screenshot helper suffix (2); plan export dialog reappears later (Activity VM); trip export dialog lost on leave; untitled trip has no title row → no menu.
Task 7: review Needs fixes (opus). Important: untitled trip (no user title, no finished ≥10 m segment) has no title row → no [⋮] (§4.1). Minors: plan export result shows later (generation counter, discard on clear); whole file in memory (OOM risk, defer streaming to later version); TZ test non-discriminating; sort test non-discriminating; no double-run test; segments deleted mid-export → empty trkseg. Deviations 1,2,4 accepted; MIME octet-stream stored vs intent gpx+xml correct.
Task 7: fix round 1 dispatched (resume implementer): Important + Minors 2,4,6.
Task 7: fix round 1 DONE (5c9de4e) — core 521, app 1189; S22 TripDetailScreenTest 17/17, PlanScreenTest 9/9. Controller checked diff inline: title row drawn for [⋮] w/o title, fallbackTitle, ExportRunner generation/discard (PlanViewModel.clear), TZ test, double-run test.
Task 7: complete (commits 58817e7..09c7408 + 5c9de4e, review clean)
Task 8A: dispatched implementer (sonnet), BASE 5c9de4e
Task 8A: DONE (212e877) — JVM 1729 (core 521, app 1189, brouter 19); S22 SettingsScreenTest 20/20. Controller checked diff inline.
Task 8A: complete
Final review: dispatched (opus) on d75e307..212e877
Final review: Ready after fixes (opus). JVM 1729 green. Minors: M1 isFinite filter; M2 slice NaN length + bandsMergeRuns 7% + 100 m window kink test; M3 TripDetailVM profile guard → profileLoaded always; M4 README V1.9.0 row + CHANGELOG usual-speed wording; M5 small chart onClickLabel. Accepted list recorded in review (perf scan <1 ms/fix, seam slice, TZ, surrogates, OOM documented, etc.).
Final fix wave: dispatched (sonnet), BASE 212e877
Final fix wave DONE (4d3c1eb) — JVM 1733 (core 524, app 1190, brouter 19); S22 ElevationChartTest, TripDetailScreenTest 17/17. Scoped re-review by controller inline: all 5 items applied as asked; kink test tolerances adjusted to real behaviour (half slope at 500 m).
Ruling: OutOfMemoryError (Error) still not caught by guarded in TripDetailVM profile — guarded catches Exception app-wide; widening is out of scope — cost if wrong: crash only on an extreme multi-day trip (documented limit).
Final review: complete. Task 8B: S22 full androidTest run started.
S22 full run: 67 classes, 503/505 pass, 2 assumption skips; S22 install + real checks done (ff10480). SDD snapshot committed; workspace deleted.
