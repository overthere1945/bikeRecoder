# SDD ledger — plan: docs/superpowers/plans/2026-10-27-v1.2.1c-history-cards-and-short-rides.md
Spec §6.10, §6.11 (user approved 2026-10-27; user away — controller decides). Branch feature/trips-v1.2.0. Method: subagent-driven. Device: S22 only; S25 install when user returns.
Pre-flight: T1 and T2 both touch HistoryViewModel/HistoryScreen → run sequentially; T2 builds on T1's hidden-trip rule.
## Progress
Task 1 base: 03072fb
Task 1: implemented 3dbf975 — core 261, app 665; S22 HistoryScreenTest 8/8, RecordScreenTest 15/15; review running
Task 2 base: 3dbf975
Task 1: review → needs fix (Important 1: ACTIVE check → offlineMaps.deleteForTrip (can block on download mutex) → deleteTrip race; [여러 날로 바꾸기] on guided summary can make trip ACTIVE in between → ACTIVE trip deleted. Important 2: no test for open sibling segment). Minors: same map→trip order in [정리] and single delete (DRY), open-target refusal untested, delete-failure untested, VM close wiring untested, outside-dismiss device test, ShortRides in ui.record, all-0 m trips never listed nor cleaned.
Ruling R20: trip deletion for short rides is an atomic conditional DAO delete (status COMPLETED and no ride_segment left) — offline maps are deleted only after the row is actually gone; the same helper serves [버리기], [정리] and the existing single delete where applicable; [여러 날로 바꾸기] is disabled while a discard runs — never delete an ACTIVE trip — cost if wrong: a map region left for one extra pass.
Ruling R21: trips whose segments are all exactly 0 m count as short for hiding and [정리] (they were silently unlisted forever) — cost if wrong: none (still confirm-before-delete).
Task 1 fix deferred until Task 2 lands (both touch HistoryViewModel).
Task 2: implemented bf1e584 — app 676; S22 HistoryScreenTest 9/9, RoomPostStoreTest 16/16; screenshot history-1.png checked by controller (month headers, cards with previews, badges, 📷/♥, footer)
Task 2: complete (commits 3dbf975..bf1e584, review clean)
Task 2: minor (deferred, FINAL-WAVE candidates): no live refresh of previews/📷/♥ after a pass finishes (refresh on postProcessor finishedRuns); empty preview white hole (use onSurface ~6% alpha); cold open reads list twice. LEAVE: badge by trip type ("여러 날 · 1일"), Path rebuilt per draw, trips read only by tests, TalkBack "›", cumulative imports ui.history.cardDateText, test gaps.
Task 1: fix round 1 → df9a350 (TripDeleter atomic conditional deletes, maps after row, [여러 날로 바꾸기] disabled during discard, R21, ShortRides→ride) — core 261, app 683; S22 RoomRideStoreTest 9/9, HistoryScreenTest 9/9, RecordScreenTest 15/15; re-review running
Task 3 base: df9a350
Task 1: fix round 1/5 (2 Important + R21 + 2 minors addressed, 0 open; commits bf1e584..df9a350)
Task 1: complete (commits 03072fb..3dbf975 + df9a350, review clean)
Task 1: minor (deferred, FINAL-WAVE candidates): map delete step cancellable after row delete → orphan map region if user leaves during a download (wrap map step in NonCancellable); [정리] holds VM lock while waiting on map deletes (list looks frozen). LEAVE: misleading refusal msg when trip already gone; no test for conversion guard.
Task 3: complete (df9a350..b4b313e docs) — PC 94 classes / 960; device total 208/33
Final review: 0 Critical, 1 Important (docs I1); FIX-NOW 4 (docs tails; live history refresh via finishedRuns/routesSaved; TripDeleter map step NonCancellable; [정리] SQL AND EXISTS segment). LEAVE: preview tint, [정리] VM lock, M3, M4.
Final fix wave: 70b846e — PC 94/963; S22 RoomRideStoreTest 9/9, HistoryScreenTest 9/9 — re-review running
Final re-review: CLEAN. PLAN COMPLETE.
