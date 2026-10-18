# SDD ledger — plan: docs/superpowers/plans/2026-10-18-search-favorites-polish.md
Spec: docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md §7
Branch: feature/recording-v1.1.0
Authority: user approved the 4 recommendations in chat (2026-10-18 "네, 추천대로 진행해줘"); plan written by controller; method = subagent-driven.
Rulings U1–U4 from 2026-10-18-ride-ux-improvements remain in force.
## Progress
Task 1 base: e7833fc
Task 1: complete (commits e7833fc..d1e8498, controller-reviewed: small diff, clean) — device GuidanceButtonRowTest pending
Task 2: review → fix round 1 (Important: RECENT_HEADER Row lacks merged semantics so device assertTextContains fails; minors 1 (.catch ends flow after one error), 4 (duplicate catch) folded in)
Task 3: implemented 91ea86a; ruling V2 (Message dialog with [확인]) accepted; review running
Task 2: complete (commits d1e8498..d1a31dc + fix 5e4ba4b, controller-checked fix) — device SearchScreenTest pending; deferred minors 2,3,5,6
Task 3: complete (commits d1a31dc..91ea86a, review clean) — device FavoriteChipsTest, SearchRegisterModeTest pending; verify bare navigate("search") on device
Task 3: minor (deferred, final-wave candidates): no double-tap guard on register chip / main search bar (BikeNavHost.kt:260, :234); pop guard inline untested; 3 untested VM branches; RegisterChip onClickLabel; pre-existing GlyphIcon invisibleToUser deprecation
Task 4: complete (91ea86a..adb9fb2 docs) — JVM 67 classes/595 tests
Final review: 0 Critical, 1 Important (register chips flash before favorites load) + FIX-NOW 2–4; device run on R5CT43TFS9A 57/59 (SettingsScreenTest scroll; SearchScreenTest keyboard = test artifact: semantics scroll counts as user input)
Fix wave: bb70402 (controller-checked) — JVM :app 406; device reruns SearchScreenTest 10/10, SettingsScreenTest 7/7, FavoriteChipsTest 7/7, SearchRegisterModeTest 4/4
PLAN COMPLETE (code). Pending: manual double-tap / bare search route checks on phone; field checklist.
