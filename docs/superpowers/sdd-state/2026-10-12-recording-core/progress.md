# SDD ledger — plan: docs/superpowers/plans/2026-10-12-recording-core.md

Spec: docs/superpowers/specs/2026-10-12-recording-core-design.md
Branch: feature/recording-v1.1.0 (from feature/navigation-v1.0.0 e62470a), base for tasks: 85d38c2
Authority: user approved spec sections 1–4 in chat and delegated the rest ("나머지는 알아서 하고 보고 해줘", 2026-10-12) — spec/plan written without a separate user review gate; execution method = subagent-driven (same as V1.0.0).

## Progress
Pre-flight scan: preflight-scan.md — 18 mismatches + 14 warnings; plan author asked to fix the plan text before Task 1.
Ruling P1: accept plan extensions beyond the spec — re-anchor after 5 consecutive jump rejections, tunnel bridging (gap >10 s with ≥3 km/h average) and stationary-gap exclusion, guidance-only banner text — they protect against real GPS failure modes the spec is silent on — cost if wrong: small extra logic.
Ruling P2: closeInterrupted ([종료] on an interrupted recording) closes the segment at its last save AND ends the trip (single-day → completed, multi-day → "오늘은 여기까지") — matches the record-stop flow in spec §3.4 — cost if wrong: user re-opens a multi-day trip via [이어서].
Ruling P3: a segment spanning midnight stays under its start date (localDate) — matches V1.0.0 day-distance behaviour — cost if wrong: km count restarts at 1 after midnight.
Ruling P4: the shared location flow replays the latest fix (replay = 1) so recording does not miss the fix navigation used as its start.
Plan revised by author: c249f69 (all scan findings applied; P1–P4 in Global Constraints). Task 1 base: c249f69
Ruling R1: only phone R3CY903ZYQV (authorized in V1.0.0) is used for connected tests; a different phone SM-S908N (Galaxy S22 Ultra, Android 16, serial R5CT43TFS9A) is attached but the user has not authorized it — do not install test APKs on it; device runs are deferred to a batch for the user to approve — cost if wrong: device verification delayed.
Task 1: complete (commits c249f69..fcd6658, review clean) — device SettingsScreenTest pending (R1)
Task 1: minor (deferred): malformed <ele> silently null untested; duplicated GPX scaffolding in GpxParserTest
Task 2: Ruling: reviewer's Important #1 (re-anchor adds no distance only proven by a flag) is covered by the plan's Task 6 test `reanchored fix adds no distance` (RecordingSession calls breakChain on reanchored) — no Task 2 change; Task 6 dispatch must carry this invariant — cost if wrong: caught in Task 6 review.
Task 2: complete (commits fcd6658..169c079, review clean)
Task 2: minor (deferred): max speed never updates when fixes are ≥3 s apart (window needs 2 samples); no ±0.1 m test around the 3 km/h gap-bridge boundary; out-of-order timestamps advance prev; null speed → 0 after first/re-anchored fix
Task 3: fix round 1/5 (3 addressed, 0 open — continuity tests (mutation-checked); reset forgets stop point; KDoc; commits 78c7ece..aed1ace)
Task 3: complete (commits 169c079..aed1ace, review clean)
Task 6 carry: call AutoPauseDetector.reset() on every state transition; call calc.breakChain() before add when TrackFilter returns reanchored; detector relies on the caller for fix gaps.
Task 4: complete (commits aed1ace..5247a93, review clean)
Task 4: minor (deferred): baro window average allocates per event (Task 9/10 should feed pressure at ~1 Hz); second recalibration window untested; GPS-mode smoothing ignores vAcc
Task 6/10 carry: AscentCounter.rebase() on altitude-mode change and on resume; feed onGps/onPressure with tick-consistent times; BAROMETER mode with no offset → altitudeM null (show "--"), climb uses climbAltitudeM; decide which fixes feed onGps (accepted only); rate-limit pressure to ~1 Hz.
Task 5: complete (commits 5247a93..b041188, review clean)
Task 5: minor (deferred): RideFormat clock uses default Locale for %d (use Locale.ROOT); misnamed HALF_UP test; rideKmReport/WithNav duplicate first sentence; BigDecimal(NaN) would throw (callers pass guarded avg)
Task 6: Ruling: AutoPauseDetector.reset() is NOT called on the auto-pause transition itself (reset would clear the stop point needed by the ≥15 m resume rule; shouldPause already clears its timers); every other transition resets — my carried invariant was over-broad — cost if wrong: none, test 'auto pause resumes 15 m from the stop point' pins it.
Task 6: complete (commits b041188..ad127e5, review clean)
Task 6: minor (deferred): moving-time test asserts only < elapsed (exact 46_000 known); day rollover via tick/pressure untested; multi-km jump emits only the highest km (tunnel bridge); start() doesn't gate recording — controller must call start() first
Task 7: complete (commits ad127e5..ef54501, review clean)
Task 7: minor (deferred): source phrase args (remainingM/eta) not asserted; setKmSource not idempotent (redundant call drops a deferred report) — T11 should avoid redundant calls or add a guard; falling source distance (new day/new recording) requires setKmSource — T11 carry
SESSION INTERRUPTED during Task 8 implementer dispatch: partial uncommitted work found (AppDatabase/Entities/Daos/RoomTripStore/build files modified; 2.json, RoomRideStore.kt, 2 androidTests untracked); no report. Fresh implementer to verify and finish.
Task 8: complete (commits ef54501..c448749, review clean) — device runs pending (R1): RoomRideStoreTest, RideDatabaseMigrationTest, RoomTripStoreTest
Task 8: minor (deferred): tripSummaries does one trip query per row; no (segmentId,timeMillis) index; deleting a trip leaves offline_region_ref rows — T15 delete must also call OfflineRegionRefDao.deleteForTrip/offline delete; duplicated updateTotals calls
Device batch pending (R1): T1 SettingsScreenTest; T8 RoomRideStoreTest, RideDatabaseMigrationTest, RoomTripStoreTest
Task 9: complete (commits c448749..d2228e7, review clean) — device SettingsScreenTest.recordingSwitchesToggle pending (R1)
Task 9: minor (deferred): End replaces last Fix in replay; no extraBufferCapacity (recording handler must be non-blocking); redundant flow wrapper; AltitudeConverter created per fixes() call not per collection; silent MSL fallback; 900 ms gate untested; DataStore persistence of new keys untested
T12 carry: wire SharedLocationSource (rideLocation) + BarometerSource into AppContainer; NavigationController locationSource = container.rideLocation
Task 10: complete (commits d2228e7..61776cf, review clean)
Task 10: minor (deferred, final-wave candidates): closeInterrupted can race startRecording (shared Mutex/busy state); failed FINAL save at stop drops the retained buffer while closing the segment (Review Focus 5 — consider one retry or leave segment open for recovery); km-suppression-while-navigating untested (T11 owns); closeInterrupted failures only logged; track list copied per point (O(n²))
Task 11: Ruling: recording auto-start applies whenever the ride is not Starting/Active (Idle, Finished, Failed) — a lingering summary/error screen must not silently disable recording for a new guidance — cost if wrong: none.
Task 11: fix round 1/5 (4 addressed, 0 open — auto-start from Idle/Finished/Failed; re-check after settings read; failed-route test; KDoc; commits 4558f87..19d5a95)
Task 11: complete (commits 61776cf..19d5a95, review clean)
Task 11: minor (deferred): recording of trip A could run while guiding trip B (km counts A) — T13/T14 UI should prevent; deferred km sentence may be lost if guidance closes while deferred (accepted, documented)
Task 12: complete (commits 19d5a95..cc52936, review clean) — device NavigationScenarioTest re-run on shared stream pending (R1)
Task 12: minor (deferred): null-intent path reads 3 stores in one try (remainingStops failure hides interrupted notice); "ride exists before guidance begins" relies on NavigationService.start; missing notice tests (Paused while guiding, COMPLETED trip); stale "fresh locationSource" KDoc; recording-only notification tap → T13 should route to the record screen
T13 carry: recording-only notification tap should open the record screen; record-only start must also start the service from the visible Activity
Task 13: Ruling: record screen pager uses userScrollEnabled = false and turns pages only via explicit swipe areas (summary line, numbers page, top strip) + page dots — MapLibre (AndroidView) never calls requestDisallowInterceptTouchEvent, so the pager would steal map drags; deterministic and independent of interop — cost if wrong: swiping on the map itself won't turn pages (by design).
Task 13: fix round 1/5 (7 addressed, 0 open — pager swipe areas + interop test; beyondViewportPageCount 1; shared KeepScreenOn; permission-gated recovery dismiss; real disabled button; warning log; SegmentTotals.avgSpeedMps; commits 9765309..ff86b4f)
Task 13: complete (commits cc52936..ff86b4f, review clean) — device RecordScreenTest (8) + manual flow pending (R1)
T14 carry (from T13 review): (a) starting guidance for trip B while recording trip A must first stop A (SEGMENT_ONLY) or block — currently plan [안내 시작]→[새로 시작] completes A while it records; (b) after guidance arrival stops a recording, its summary stays in RideController and the main screen shows nothing — show the summary (or dismiss) when the ride finishes; a new [기록 시작] silently replaces a pending summary.
Task 14: fix round 1/5 (5 addressed, 0 open — dismiss only shown summary; save-problem notice "이전 기록 저장에 문제가 있습니다"; wait for in-flight stop; keep summary with auto-start off; pending summary before plan start; commits 15e739e..c0e9f86)
Task 14: complete (commits ff86b4f..c0e9f86, review clean) — device EndNavigationDialogTest + manual guidance pager/end/summary check pending (R1)
Task 14: minor (deferred): main-screen record-only Ready ignores recordNotice (near-unreachable); auto-start off ends A silently until main; MainViewModel.completeStaleTrip can complete a trip being recorded (whole-branch review); same-trip guidance completes the trip before recording stops (T11 design)
Task 15: complete (commits c0e9f86..66a07e2, review clean) — device HistoryScreenTest pending (R1)
Task 15: minor (deferred, final-wave candidates): trip delete not NonCancellable (offline maps gone, trip stays if cancelled); no user feedback on delete failure; confirm-time re-check untested; pair-speed fallback crosses pauses (false dip); charts' x scales differ when last altitude null (share maxKm); guarded helper copied 3×; guard runs full tripSummaries; nits
Task 16: complete (commits 66a07e2..aac9333, review clean) — RideScenarioTest compile-only (R1)
Task 16: minor (deferred, final-wave candidates): RideScenarioTest tickIntervalMs not scaled to 20× clock (pass 50 ms); fixes and ReplayClock don't share one t0; README "현재 버전 V1.1.0" vs unreleased V1.0.0 wording
ALL 16 TASKS COMPLETE. Next: final whole-branch review.
FINAL REVIEW (opus, e62470a..aac9333): With fixes — Important: (1) guidance mute leaks into recording and persists; (2) no recording controls while guiding (pause/auto-pause chip/end recording only); (3) stale-trip prompt can complete the recorded trip, recording never touches lastActiveAt. Minors 1–10; deferred triage: fix T16 tick scaling + shared t0, T10 final-save failure.
Ruling F1: guidance mute is reset whenever a guidance run ends (close/finish/endRun) — mute is a guidance-session control; recording outside guidance is never muted — cost if wrong: a rider who wanted silence must mute again.
Ruling F2: the guidance numbers page gets the record controls: [일시정지]/[재개], the auto-pause chip, [기록 끝내기] (SEGMENT_ONLY, guidance continues) — spec §3.2 "자동 모드에서도 수동 일시정지 가능" applies during guidance too — cost if wrong: extra buttons on the guidance numbers page.
Final fix wave: 15 findings addressed in aac9333..21ab1ef (15 commits); scoped re-review (opus): all addressed, no new Critical/Important. Controller re-ran JVM verification: core 176, routing 16, app 309, 0 failures; assembleDebug + assembleDebugAndroidTest OK.
Final residual minors (parked): plain-guidance layout now has the bottom panel/[현재 위치로] below the map (map shrinks when the button appears) — device check; open segment with zero distance hidden in history until it moves.
FINAL REVIEW CLEAN. Remaining before v1.1.0: ALL device tests (R1: authorized phone R3CY903ZYQV absent; R5CT43TFS9A deliberately not used) + field checklist docs/verification/recording-field-test.md.
Device batch 2026-10-13: user authorized S22 Ultra R5CT43TFS9A (supersedes R1). Full connectedDebugAndroidTest: 33/33 non-UI pass; 28 UI failed with phone locked; after unlock all UI classes pass; SettingsScreenTest scroll fix (test-only) → 61/61 pass.
