# SDD ledger — plan: docs/superpowers/plans/2026-10-27-v1.2.1-fixes-and-cumulative-map.md
Spec: docs/superpowers/specs/2026-10-18-trips-postprocess-design.md §6 (approved 2026-10-27; §6.3 amended to ≥ 10 m; week label example fixed)
Branch: feature/trips-v1.2.0. Method: subagent-driven (user chose 2026-10-27). Device: S22 R5CT43TFS9A only; S25 install at the end only.

## Pre-flight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1 self | new shouldTry(status, detailed, …) replaces old calls in PostProcessor, SummaryExtras, FakePostStore; DAO condition | consistent |
| T2→T3 | MIN_RIDE_DISTANCE_M, PeriodFilter(weekStart), WeekOption, rides(), weeks(), thisWeek/thisMonth, RideColors → VM/screen | consistent |
| T2 self | PeriodFilter gains weekStart with default; existing callers compile | consistent |
| T3 self | MapOverlay.rides replaces faintLines (only cumulative used it) | consistent |
| T1,T3 | both touch nothing in common except FakePostStore? (T3 doesn't) | consistent |
| T4 | version tests AppVersionTest, SettingsScreenTest named as changing | consistent |
Scan clean; no rulings needed.

## Progress
Task 1 base: acb9c7e
Task 1: implemented 7eadc9b — core 246, app 630; S22 RoomPostStoreTest 14/14, SegmentDetailScreenTest 7/7; concerns: same-count refetch doesn't update newly available calories; HR_NOT_DETAILED copy still says 자전거 운동
Task 2 base: 7eadc9b
Task 1: review → fix round 1 (Important 1: same-count re-read drops newly available calories; Important 2: overlapping workouts double-count calories; Minor 1 copy; Minor 2 pass-path Failed/Empty test)
Ruling R17: on a READY re-read that does not replace samples, keep stored samples but take calories if stored calories are null (`calories = current.calories ?: read.calories`); when samples are replaced, `calories = read.calories ?: current.calories` — calories come from workout records, independent of HR sample count; [다시 가져오기] must still be able to fill calories — cost if wrong: none (only fills nulls).
Ruling R18: calories from overlapping workouts are not double counted — each workout counts only the part of the ride not already covered by an earlier-starting workout — any workout type now counts (§6.2), so simultaneous sessions become reachable — cost if wrong: slightly lower kcal when Samsung writes genuinely separate overlapping activities.
Ruling R19: HR_NOT_DETAILED copy becomes "워치에서 운동을 시작하면 자세한 심박이 기록됩니다" (no "자전거") — §6.2 makes any workout count; old copy misleads — spec §3.1 copy amended — cost if wrong: one string.
Task 2: implemented e1fba99 (reordered existing expectations newest-first; jitter fixture 1 m → 100 m) — review running
Task 2: complete (commits 7eadc9b..e1fba99, review clean)
Task 2: minor (deferred → carry to Task 3): VM load() fit uses distanceM > 0 → must use CumulativeSummary.rides(); compute rides once in stateOf. Test gaps (jitter-only year/month, month-end/Feb/year-crossing weeks, Monday start inclusion); filter() doesn't validate weekStart is Monday.
Task 1: fix round 1 commit f0bb1b8 (R17, R18, R19, pass Failed/Empty test) — core 259, app 636; S22 HeartRateRowTest 4/4, SegmentDetailScreenTest 7/7 — re-review running
Task 3 base: f0bb1b8
Task 1: fix round 1/5 (2 Important + 2 minors addressed, 0 open; commits e4b89ee..f0bb1b8)
Task 1: complete (commits acb9c7e..7eadc9b + f0bb1b8, review clean)
Task 1: minor (deferred): samples().size instead of COUNT query; detailed READY with null calories not auto re-read (manual [다시 가져오기] fills it)
Task 3: implemented 0c2c31b — JVM 647; S22 CumulativeMapScreenTest 11/11, HistoryScreenTest 4/4, SegmentDetailScreenTest 7/7; screenshots cum-1/cum-2 checked by controller (colours, list, highlight + [기록 보기], chips)
Task 4 base: 0c2c31b
Task 3: complete (commits f0bb1b8..0c2c31b, review clean)
Task 3: minor (deferred, FINAL-WAVE candidates): [주] chip label falls back to "주 ▾" when the selected week has no rides (e.g. [이번 주] on Monday morning) — compute N from weekStart; chip label parsed from core label string; select() KDoc mismatch + test can't distinguish; fast double tap toggle uses state.value; VM thisWeek test doesn't cover month-spanning ride; no automated test for map line tap; full GeoJSON rebuild on selection
Task 4: complete (0c2c31b..c62df84, controller-checked: version 10201, CHANGELOG [1.2.1], doc warning intact) — PC 93 classes / 922 tests; S22 SettingsScreenTest 11/11
Final review: 0 Critical, 0 Important; FIX-NOW 3 (week chip label from weekStart via weekOf/WeekOption.number; select() KDoc + test; field doc item 9 how-to-verify); rest LEAVE
Pre-fix-wave S22 full run (HEAD c62df84): 33/33 classes pass
Final fix wave: c74198a (weekOf/WeekOption.number, state.week; select() KDoc+test; doc item 9) — PC 925; S22 CumulativeMapScreenTest 12/12 — re-review running
Final re-review: CLEAN. PLAN COMPLETE.
