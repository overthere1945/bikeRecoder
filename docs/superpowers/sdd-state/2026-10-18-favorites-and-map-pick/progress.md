# SDD ledger — plan: docs/superpowers/plans/2026-10-18-favorites-and-map-pick.md
Spec: §8
Authority: user approved in chat (2026-10-18), rest per controller recommendations; device = S22 R5CT43TFS9A only (S25 untouched this plan).
## Progress
Task 1 base: ae08918
Task 1: complete (commits ae08918..1241003, review clean) — S22: FavoriteChipsTest 7/7, FavoritesScreenTest 5/5, FavoriteDialogsTest 6/6, SearchRegisterModeTest 6/6, SearchScreenTest 10/10
Task 1: minors → carried into Task 3: pop rule must match registerKind(arg)==event.kind (BikeNavHost.kt:493); startAdd/empty text before favoritesLoaded (FavoritesViewModel.kt:226, BikeNavHost.kt:337)
Task 2: implemented 3cb4ada — S22 MapPickScreenTest 6/6, SearchScreenTest 13/13, SearchRegisterModeTest 6/6; screenshot verified by controller (pin tip at center, address shown); review running
Task 2: complete (commits 1241003..3cb4ada, review clean); minors: onMoveStarted doesn't cancel lookup; init+first idle double lookup; [이 위치로 설정] enabled while address loading (register CUSTOM prefill may get null address) — FINAL-WAVE CANDIDATE; no re-centre after late fix; address hidden when == name; heightIn no-op
Task 3: implemented e7ea67c — S22 FavoriteDialogsTest 10/10 (1 flaky "No compose hierarchies" in 6 runs), SearchRegisterModeTest 9/9, FavoritesScreenTest 6/6, SearchScreenTest 13/13, FavoriteChipsTest 7/7, MapPickScreenTest 6/6; Task 1 minors a,b fixed; FavoriteDialogsTest.chooseNameOffersHomeWorkAndCustom changed (accepted: follows §8.4)
Task 3: fix round 1 → 9ccfaf3 (Important: late current-location lookup cancel/guard + loading row; stale fix >30 s; warning colour; MapPick confirm waits for address; register explicit) — S22 FavoriteDialogsTest 10/10, SearchRegisterModeTest 10/10, MapPickScreenTest 6/6. Complete (controller accepted fix report).
Task 4: complete (984fe04 docs; counts updated in 9ccfaf3: :app 469, total 662)
Final review: docs-only Important → e10837b. Installed on S22 (installDebug). PLAN COMPLETE.
