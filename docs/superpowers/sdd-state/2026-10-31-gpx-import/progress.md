# SDD ledger — plan: docs/superpowers/plans/2026-10-31-gpx-import.md
Spec: docs/superpowers/specs/2026-10-31-gpx-import-design.md · branch feature/gpx-import-v1.6.0 · start 6b824ea

## Preflight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1→T2 | GpxTrack.fixes (sorted, dedup), ImportProblem → RideReplay.replay(fixes), statuses | consistent |
| T2→T3 | ImportStore, ImportedRide(ReplayedRide, profile, fileName, importedAt), TimeSpan → RoomImportStore | consistent |
| T2→T4 | RideReplay (null → TOO_SHORT), ImportRules.statuses(spans?, activities?, existing), previewPoints → ImportScanner | consistent; GpxReader throws NO_TIME on 0 points so replay(empty) never reached |
| T3→T4 | segmentSpans(now), saveImported → scanner/job | consistent |
| T4→T5 | ScanRow, RideKey, ImportJobState, GpxImportJob.start/stop/acknowledge, ImportFormat → ImportViewModel/Screen/Settings | consistent; scanner built in VM factory from container.importReader + importStore |
| T3→T5/T6 | observeImported, deleteAllImported → settings, feed, trip detail | consistent |
| T5↔T6 | T5 adds MainActivity parsing + manifest; T6 adds device test for it | sequential, consistent |
| T5↔T7 | both touch SettingsScreenTest | sequential, fine |
| T1 self | Unpacker yields TooManyEntries(index=-1); TOO_SHORT produced later by scanner | consistent |
| T2 self | tests use core resources follow.gpx | exists |
| T3 self | failureMidSaveLeavesNothing needs an injected failure | Ruling below |
| T4 self | tests with fakes only | fine |
| T5 self | screenshots v160-import-* | fine |
| T6 self | FeedCard date line now also gets " · 가져옴" | fine |
| T7 self | part B is controller's | fine |
Ruling: T3 failure injection — implementer may force a mid-transaction failure any way that exercises the real transaction (e.g. a point row violating a constraint, or a test-only DAO subclass) — keeps the test honest — cost if wrong: one test rewrite
Task 1: implementer dispatched (sonnet), BASE 6b824ea
Task 1: implementer DONE_WITH_CONCERNS (881e6f1) — core tests pass (Unpacker 16, Reader 19); choices: DOCTYPE→UNREADABLE, droppedPoints incl. duplicates
Task 1: review dispatched (sonnet) on review-6b824ea..881e6f1.diff
Task 1: review → Needs fixes (1 Important: whitespace before <?xml sniffed as GPX but unparsable)
Task 1: minor (deferred): oversize ZIP entry still inflated to skip (CPU), dir entries uncapped
Note (carry to Task 3): Android DOM parser DOCTYPE/XXE refusal unverified on device — add instrumented test GpxReaderAndroidTest.doctypeIsRejectedOnAndroid in Task 3
Task 1: fix round 1/5 dispatched (resume implementer): whitespace strip + absurd time + visitor exception + error handler
Task 1: fix round 1/5 done (24e50e2) — core: Reader 21, Unpacker 17
Task 1: re-review dispatched (sonnet)
Task 1: re-review → all 4 addressed. minor (deferred): UTF-16 file with whitespace before <?xml stays UNREADABLE; GpxReader import order; nextEntry IOException path untested
Task 1: complete (commits 6b824ea..24e50e2, review clean)
Task 2: implementer dispatched (sonnet), BASE 24e50e2
Task 2: implementer DONE (c923795) — RideReplay 5, ImportRules 8; core all pass
Task 2: review dispatched (sonnet)
Task 2: review → Approved. minor (deferred): matchesLiveRecording weak (no pause/ele) — add live-vs-replay on pause path; no 10 m boundary test; replay has no unsorted guard; unreadableIgnoredForDuplicates weak asserts
Ruling: statuses() duplicate-in-list compares against any earlier readable ride regardless of its status (incl. chains) — spec "먼저 나온 것만 새 주행"; realistic duplicates are both cycling — cost if wrong: a rare overlapping cycling ride shows as duplicate
Note (carry to Task 4): statuses() returns placeholder Unreadable(UNREADABLE) for null spans — scanner must overwrite with the real problem
Task 2: complete (commits 24e50e2..c923795, review clean)
Task 3: implementer dispatched (sonnet), BASE c923795 — carries Android DOCTYPE instrumented test
Task 3: implementer DONE (0f67a8a) — S22 RoomImportStore 6, Migration 7, GpxReaderAndroid 2; core 438, app 1068; Android ignores disallow-doctype-decl → GpxReader rejects document.doctype != null post-parse
Task 3: review dispatched (sonnet) — named risk: billion-laughs before post-parse doctype check
Task 3: review → Needs fixes (1 Important: DOCTYPE rejected only after Android parse expands entities — billion-laughs risk)
Task 3: minor (deferred): ImportedNameRow/SegmentSpanRow public; countOf needless suspend; cascade of media/photo/HR/label untested by insert
Task 3: fix round 1/5 dispatched (resume implementer): pre-parse <!DOCTYPE byte scan + nested-entity Android test; surrogate-safe cut; comments
Task 3: fix round 1/5 done (48529b3) — core 441, app 1068; S22 GpxReaderAndroid 3, RoomImportStore 7
Task 3: re-review → all 4 addressed. minor (deferred): utf16DoctypeRejected asserts no problem code; no indexOf end-of-array unit test; literal <!DOCTYPE in a comment is rejected (safe direction)
Task 3: complete (commits c923795..48529b3, review clean)
Task 4: implementer dispatched (opus), BASE 48529b3
Task 4: implementer DONE_WITH_CONCERNS (ec3ea4d) — 25 new tests; core 441, app 1093; concerns: 500 cap counts unreadable rows; scanner/job need separate dispatchers (blocking handoff)
Task 4: review dispatched (opus) — named risk: reader/processor thread handoff
Task 4: review → Needs fixes (I-1 stop ignored while reading unpicked ZIP entries; I-2 blocking channel handoff fragile, 2 items in memory)
Ruling: 500 cap counts readable rides only — spec "찾는 주행 500개"; unreadable bounded by 2,000-entry limit — cost if wrong: a junk-heavy pick lists more rows
Ruling: hold() synchronously in start(); persist at most 128 URIs (512-grant limit could prune the video music URI) — cost if wrong: >128 shared files rely on live grants and may be skipped
Ruling: ImportUnpacker.visit becomes suspend with Boolean-continue visitor (core API change) — removes runBlocking handoff — cost if wrong: core tests need runBlocking wrappers
Task 4: minor (deferred): OOM in one ride ends the run (state still Finished); cancel doesn't interrupt a blocking provider read
Task 4: fix round 1/5 dispatched (resume implementer)
Task 4: fix round 1/5 done (6b7e6d7) — core 442, app 1099
Task 4: re-review round 1 clean — I-1, I-2, M-1, M-2 and 128-grant addressed. Deferred minors: (a) hold() runs on start()'s caller thread (≤128 binder calls) → T5 VM calls start off main; (b) close() failure after a full walk adds one spurious Unreadable (pre-existing); (c) long KDoc line ImportScanner.kt:50; (d) one-dispatcher test covers scanner only.
Task 4: complete (commits ec3ea4d..6b7e6d7, review clean)
Ruling: T5 ImportViewModel calls job.start(...) from viewModelScope.launch(ioDispatcher) so hold()'s grant calls stay off main — cost if wrong: a short stall on the main thread.
Task 5: dispatched implementer (opus), BASE 6b7e6d7
Task 5: implementer DONE_WITH_CONCERNS (302a13d) — core 442, app 1107; S22 ImportScreen 9, Settings 20, MainTabs 12
Task 5: review — Needs fixes. Gap: all-unreadable shows Review not Empty (§1.2). I-1 URI list in nav route (size/TransactionTooLarge). I-2 temporary grant lost when receiving activity finishes. Minors 1-8.
Ruling: Empty when no row has a start time (spec §1.2 binding); the Empty screen may list "파일: 이유" lines under the message — helps the user, costs nothing.
Ruling: URI list leaves the nav route — an in-memory app-scoped handoff (AppContainer) taken once by the VM; not persisted. After process death with no handoff and job Idle the import screen pops back — cost if wrong: a process death mid-review loses the list (user picks again).
Ruling: grant loss mitigated — hold() opens and keeps a ParcelFileDescriptor for each URI it could not persist (cap 256), open() reads from a dup of it, release() closes them; beyond the cap it is a known limitation noted in docs — cost: fds held for the run.
Ruling: minors 1 (clipData fallback), 4 (dim reason), 5 (rename params), 7 (pure intent-parse fn + unit tests, close()/stale-Finished VM tests) fixed now; 6 (count query) and 8 (popUpTo MAIN) parked.
Task 5: fix round 1/5 done (58bbf23) — core 442, app 1120; S22 ImportScreen 9, Settings 20, MainTabs 12. Note: phone screenshots saved as v160-import-*(1).png (old owner) — clean up at T7B.
Task 5: re-review round 1 clean. Parked minors for final fix wave: (1) ImportViewModel init before uiState (initial Closed); (2) LaunchedEffect keyed on Closed + rememberUpdatedState; (3) ImportHandoff once-only test; (4) stale KDoc "128 binder calls"; (5) onboarding+process death briefly opens empty import screen; (6) share racing Phase.Starting; (7) remember list in NothingFound; (8) case-sensitive content:// check.
Task 5: complete (commits 302a13d..58bbf23, review clean)
Task 6: dispatched implementer (opus), BASE 58bbf23
Task 6: implementer DONE_WITH_CONCERNS (c3b4f2d) — core 442, app 1123; S22 Feed 23, TripDetail 6, ImportIntent 3. Fixed parked T5 minor (1) (handoff before phase; never start Closed). Debug-only FileProvider `${applicationId}.testfiles`. Note: S22 app was uninstalled by connectedAndroidTest (AGP default) — re-seed from scratchpad/s22-seed2 at T7B.
Task 6: review Approved. Parked minors for final wave: (1) intent test tearDown no try/finally; (2) explicit intent skips manifest MIME filter — add queryIntentActivities assertion; (3) importedFrom line may pop in late; (4) FeedViewModel copy(imported) vs cardOf param; (5) KDoc nit.
Task 6: complete (commit c3b4f2d, review clean)
Task 7A: dispatched implementer (sonnet), BASE c3b4f2d
Task 7A: implementer DONE (01e91e4) — JVM 1565 (core 442, app 1123), S22 SettingsScreenTest 20/20.
Ruling: Task 7A (version + docs only) is reviewed inside the final whole-branch review rather than a separate task review — it touches no logic; cost if wrong: a docs slip caught one step later.
Task 7A: complete (commit 01e91e4)
Final review: dispatched (opus), range 51f9e0b..01e91e4
Final review: Ready after fix wave. I1 OOM/StackOverflow crash in scan; minors M1–M10; wave = final-fix-wave.md (items 1–9). Skipped: M5 launchMode (doc note only), M6/M7 notes only, M9, M10, and the parked minors marked skip.
Final fix wave: dispatched (opus), BASE 01e91e4
Final fix wave: done (4a5ed69, a874dbe, 39ce061) — core 443, app 1127; S22 RoomImportStore 8, ImportIntent 4. Added parse ctor param (default GpxReader::read); removed ImportDao.importedTripIds().
Final fix wave: re-review dispatched (sonnet). T7B: S22 full run started at 39ce061 (62 classes, at-results-160.txt)
Final fix wave: re-review clean (all 9 addressed). Remaining minors: CHANGELOG wording "동작은 그대로" + stale PC counts in field doc → fix in T7B docs commit; GpxImportJob KDoc >120 cols → fix after the device run; OOM in saveImported, StackOverflow from read in walk, delete-all count not reaching 0 for non-completed imported trips → accepted (parked).
Task 7B: S22 full run 62 classes / 466 tests rc=0 (2 known assumption skips); JVM core 443, app 1127; installDebug on S22, seed v7→v8 migrated; real import of test GPX+ZIP (4 found, 2 new, imported 2, marks shown), delete-all left 4 trips / 2 segments. Docs commit.
Task 7B: complete
