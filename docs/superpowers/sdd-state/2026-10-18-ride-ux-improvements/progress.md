# SDD ledger — plan: docs/superpowers/plans/2026-10-18-ride-ux-improvements.md

Spec: docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md
Branch: feature/recording-v1.1.0, base for tasks: 17e4597
Authority: user approved the design in chat (2026-10-18) incl. on-screen text for voice notices and the 3-option setting; "나머지는 너의 계획대로 진행한다" → plan executed without a separate plan review; method = subagent-driven.
Ruling U1: when guidance is started while a recording is already running (re-guide, spec §4), arrival at that destination ends ONLY the guidance — the recording continues and the trip stays ACTIVE (voice/text "목적지에 도착했습니다"); guidance-started recordings keep the V1.1 rule (arrival ends both and completes the trip) — the rider started recording independently, so a re-guided destination is likely an intermediate stop — cost if wrong: rider presses [정지] to end.
Ruling U2: spec §4.2 "endForAnotherTrip only when not recording" is read as: endForAnotherTrip remains only as a fallback for a recording that starts while a question dialog is open.
Ruling U3: connected tests ONLY on R5CT43TFS9A (S22); R3CY903ZYQV (S25, user's real rides) gets installDebug only — connectedAndroidTest uninstalls the app and would delete ride data.

## Progress
Ruling U4: 집·회사 chips' long-press menu offers [삭제] only (their name is their role); custom favorites get [이름 바꾸기] [삭제] — spec §1.1 amended — cost if wrong: user deletes and re-saves to change.
Plan revised by author: f692684 (C1–C7 + notes applied). Task 1 base: d668ec4
Task 1: complete (commits d668ec4..de6f521, review clean)
Task 1: minor (deferred): name length counts UTF-16 units (emoji = 2); duplicate check case-sensitive; extra coverage (WORK replace, rename HOME throws, rename >20, id tie-break, lock release after IOException); twentyIsTheLimit could split
Task 2: complete (commits de6f521..17b32fa, review clean) — device RoomFavoriteStoreTest + RideDatabaseMigrationTest.v2DataSurvivesMigrationTo3 pending (U3, no phone)
Task 2: minor (deferred): observeAll re-emission untested; valueOf throws on unknown kind (same as RoomTripStore)
Task 3: complete (commits 17b32fa..3f85068, review clean) — device PlanScreenTest pending
Task 3: minor (deferred): MainViewModel pressed-mirror untested; point-equality stale guard not exercised; address failures not logged; duplicate catch
Task 4: complete (commits 3f85068..c9eb6d9, review clean) — device FavoriteChipsTest, FavoriteDialogsTest pending
Task 4: minor (deferred, final-wave candidates): failed delete closes dialog silently (add error to ConfirmDelete); untested failure branches/write guard; ~16dp blank gap on search screen with no favorites; chip shadow clipped by horizontalScroll; chips <48dp, no Role.Button; chip row may capture map drags on main
Task 5: fix round 1/5 (3 addressed, 0 open — chip padding only on search slot; dead state/imports; no-blank-strip device test; commits 765d282..55f0aa3)
Task 5: complete (commits c9eb6d9..55f0aa3, review clean) — device SearchScreenTest (3) pending
Task 6: complete (commits 55f0aa3..6ca0466, review clean)
Task 6: minor (deferred, final-wave candidates): F1 assertion in U1 test can't fail (mute first); splitSegment returns null on failed new start (indistinguishable) and fail() doesn't clear notice; announce during Starting ignores voice setting; split coverage gaps; onArrivedKeepingRecording single assignable callback (document)
T7 carry: attach() must await splitSegment() completion BEFORE starting the new guidance (otherwise an arrival/begin in the null-kmSource window loses U1); keep closeGuidanceOf → split → begin order.
Task 7: complete (commits 6ca0466..6f115ec, review clean) — device SettingsScreenTest.guidanceRecordingChoices, TripStartDialogsTest pending
Task 7: minor (deferred, final-wave candidates): split silently skipped if stop/split already in flight (attach returns Ready unsplit); failed split leaves plan applied + closed-segment save notice dropped; askedRecording stale on CONTINUE/SPLIT; blank-line nits in TripStartViewModelTest; CONTINUE window (recording ends before begin → V1.1 arrival rule) accepted; split on-screen notice not tested end-to-end
Task 8: complete (commits 6f115ec..427a41d, review clean) — device GuidanceButtonRowTest, NoticeSnackbarHostTest, RecordScreenTest (+2) pending
Task 8: minor (deferred, FINAL-WAVE FIX): guidance-screen snackbar fixed 220dp straddles map edge, blocks [전체 경로]/page dots ~4s → anchor to map box bottom (~64dp) (NavigateScreen.kt:503,610-614); record-screen 120dp offset over summary + [목적지 정하기] (RecordScreen.kt:169) — check; NoticeSnackbarHostTest "same notice again" step is a no-op (set null then id 1), rotation untested; no test for OVERVIEW_ON_MAP; cosmetic double-show during transition
Task 9: complete (commit 427a41d..88c9163, docs; controller-checked: CHANGELOG items, no remaining R3CY903ZYQV connected example) — JVM 65 classes / 571 tests pass; all device classes "기기 검증 대기"
Final review: 1 Important (guidance snackbar placement) + 6 FIX-NOW; fix wave 7d0bc57, 855d4f9, bcd02f3 (notice waits while numbers page shows); re-review CLEAN; README data-loss warning added by controller. JVM 573 tests pass; device classes (21 classes / 87 tests) pending R5CT43TFS9A.
PLAN COMPLETE (code). Pending: device batch on R5CT43TFS9A; installDebug on R3CY903ZYQV (real v2→v3 migration); field checklist.
