# SDD ledger — plan: docs/superpowers/plans/2026-10-27-v1.2.1b-list-and-trip-refetch.md
Spec §6.8, §6.9 (user approved 2026-10-27). Branch feature/trips-v1.2.0. Method: subagent-driven. Device: S22 only; S25 install at end.
Pre-flight: T1 (cumulative screen/VM) and T2 (trip detail VM/screen + docs) share no files except docs (T2 only) — clean.
## Progress
Task 1 base: 2ee3c2b
Task 2: implemented 075f0a9 — PC 930; S22 HistoryScreenTest 6/6; review running
Task 1: implemented 80bf76e — JVM CumulativeMapViewModelTest 27/27, app 653; S22 CumulativeMapScreenTest 14/14; screenshots cum-3/cum-4 checked by controller; concern: list shows ~2 cards (map weight 0.7)
Task 1: complete (commits 2ee3c2b..80bf76e, review clean); minors: list shows ~2 cards (0.7/0.3, acceptable); harness test name overstates; wrong comment in mapTapSelectsAndShowsCard; "›" read by TalkBack
Task 2: fix round 1 → 1fd0dec (pure gate tripRefetchAction + JVM test; changelog wording; removed unused overload; failing-segments test; exact totals) — S22 HistoryScreenTest 6/6; re-check folded into final review
Final fix wave (docs only): f32b848 — controller-checked counts 655/932, device 200/33, item 10/11 wording. PLAN COMPLETE.
