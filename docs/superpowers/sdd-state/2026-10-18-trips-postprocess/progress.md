# SDD ledger — plan: docs/superpowers/plans/2026-10-18-trips-postprocess.md
Spec: docs/superpowers/specs/2026-10-18-trips-postprocess-design.md (approved 2026-10-18; rulings P1 chart distance axis, P2 silent photo retry already in spec+plan)
Branch: feature/trips-v1.2.0. Method: subagent-driven (user chose 2026-10-18). Device: S22 R5CT43TFS9A only; S25 untouched.

## Pre-flight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1→T3 | PolylineCodec, CumulativeEntry → PostStore.saveSimplified/cumulativeEntries | consistent |
| T2→T3,T4,T5 | HeartRateSample, HrStatus, HrRead, HrRetryPolicy → PostStore, HeartRateSource.read, PostProcessor | consistent |
| T3→T5 | segmentsNeedingWork(), SegmentGone → PostProcessor | FAILED stays "needing work" forever after 24 h → Ruling R1 |
| T4→T5 | PhotoSource/HeartRateSource → PostProcessor | consistent (Samples with empty list: source must return Empty → Ruling R2) |
| T5→T6,T7,T8 | request(), pruneDeletedPhotos(), refetchHeartRate(), running → VMs | consistent |
| T6→T7,T9 | HeartRatePermissionCard, photo/heartRatePromptDismissed → T7 card, T9 settings | consistent |
| T6,T7,T8 | each adds a MapOverlay field (photos / trackColors / lines) to BikeMap.kt | distinct fields, sequential tasks — ok |
| T1 self | tests vs interfaces | consistent |
| T2 self | tests vs interfaces | consistent |
| T3 self | creates RoomPostStore + migration test | consistent |
| T4 self | fakes in test + androidTest | consistent |
| T5 self | MainActivity.onStart trigger, no new lifecycle-process dep | consistent |
| T6 self | SettingsRepository flags added here (used later) | consistent |
| T7 self | summary polling uses refetchHeartRate | consistent |
| T8 self | fit from bboxes | consistent |
| T9 self | docs + settings | consistent |

Ruling R1: PostStore.segmentsNeedingWork takes `now: Long` — hr PENDING always counts; hr FAILED counts only while now <= endedAt + HrRetryPolicy.GIVE_UP_MS (after that only [다시 가져오기] retries) — spec §3.2 retry window; avoids re-listing old failed segments every run — cost if wrong: an old FAILED segment needs a manual [다시 가져오기].
Ruling R2: HeartRateSource.read returns HrRead.Empty (never Samples(emptyList())) when no samples — keeps HrRetryPolicy.after total — cost if wrong: none.

## Progress
Task 1 base: 6e0ec4d
Task 1: fix round 1/5 (1 addressed + 3 minors, 0 open — EXIF on empty track; polyline decode; boundary tests; commits e4afdec..5b5ccb7)
Task 1: complete (commits 6e0ec4d..5b5ccb7, review clean)
Task 1: minor (deferred): zigzag DP test ~4.7 s, no timeout; GeoMath.project allocations in DP loop (O(n²) worst case); PolylineCodec lives in RouteSimplifier.kt
Task 2 base: 5b5ccb7
Task 2: complete (commits 5b5ccb7..155eded, review clean)
Task 2: minor (deferred, final-wave candidates): HrRead.Samples(emptyList()) only forbidden by KDoc (after() → READY with no data); nearest-after & tie-break untested. Carry to Task 4: Health Connect reader must sort samples by time (matchedBpm assumes sorted).
Task 3 base: 155eded
Task 4 base: 73d4437
Task 3: complete (commits 155eded..73d4437, review clean) — S22 RoomPostStoreTest 8/8, RideDatabaseMigrationTest 3/3 (v3→v4 additive verified via 3.json/4.json + generated migration)
Task 3: minor (deferred): no photo access → all segments listed each run (N+1 extras reads); cumulativeEntries: one corrupt polyline fails whole map + decode on caller dispatcher (→ Task 8); bbox written but not read, empty points (→ Task 8); IN-list >999 on API 30 (callers trip-sized); no mediaId index; all constraint errors → SegmentGone; DAO param name failedUntil
Ruling R3: PENDING heart rate is always read (even past 24 h) so it can become NONE; FAILED only within 24 h — Task 3 review minor 1: V1.1.0 segments (no extras, >24 h old) would otherwise stay PENDING forever — Task 5 changes HrRetryPolicy.shouldTry accordingly (+ test) — cost if wrong: one extra HC read per old segment.
Ruling R4: segmentsNeedingWork orders newest first (startedAt DESC) — first run after upgrade shows the latest ride's results first — Task 5 changes the DAO ORDER BY — cost if wrong: none.
Ruling R5: add `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"` and treat it as FULL photo access on API 30–32 — minSdk is 30; without it Android 11–12 finds no photos (Task 4 concern 2) — folded into Task 5 dispatch to avoid a parallel edit while Task 4 is reviewed — cost if wrong: one extra manifest permission on old Android.
Task 4: implemented 52fd1df — JVM 494; S22 MediaStorePhotoSourceTest 4/4; getSdkStatus=3 on S22; review running
Task 5 base: 52fd1df
Task 4: review → fix round 1 (Important: null cursor in existing()/photos() treated as deleted/empty; + minors 2 (from==to → TimeRangeFilter throws → FAILED forever), 3 (HR read margin), 4 → R6, 5 (fake clipping), 6 (FakePhotoSource Uri.parse in JVM))
Ruling R6: heart rate needs only READ_HEART_RATE; READ_EXERCISE / READ_TOTAL_CALORIES_BURNED missing → calories null, samples still read; hasPermissions() = READ_HEART_RATE granted; the request still asks all three — spec §3.4 lists three permissions but §3.1 treats calories as optional — cost if wrong: none (more data shown).
Ruling R7: [다시 가져오기] on a READY segment that fails/returns empty keeps the stored heart rate (only hrLastTriedAt changes) — never destroy data the rider already has — cost if wrong: no on-screen failure note for that rare case.
Task 5: implemented d8239fe (R3, R4, R5 included) — JVM app 517, core 233; S22 RoomPostStoreTest 8/8; review running
Task 5: review → needs fix (Important: permission/availability missing → FAILED, past 24 h never retried → all V1.1.0 rides FAILED forever after first launch)
Ruling R8: Health Connect unavailable or READ_HEART_RATE missing → hr status stays PENDING (only hrLastTriedAt updated), never FAILED; FAILED only for an actual read error — permission is asked "처음 쓸 때" after the first pass, and R3 keeps PENDING retried so old rides resolve once permission is granted — Task 7 must show the permission card / "Health Connect를 사용할 수 없습니다" instead of "심박 가져오는 중" when permission or HC is missing — cost if wrong: one cheap permission check per pending segment per pass.
Task 4: fix round 1 commit 673624d (re-review running)
Task 4: fix round 1/5 (1 Important + 5 minors addressed, 0 open; commits d8239fe..673624d)
Task 4: complete (commits 73d4437..52fd1df + 673624d, review clean) — S22 MediaStorePhotoSourceTest 5/5
Task 4: minor (deferred): no paging test of real ClientRecordReader; no positive DATE_ADDED device test; 60 s HR margin may miss a pre-ride record if HC filters by start time; FakePhotoSource.uri() device-only
Task 5: fix round 1 commit fee59a7 (re-review running)
Task 6 base: fee59a7
Task 5: fix round 1/5 (1 Important + 5 minors addressed, 0 open; commits 673624d..fee59a7)
Task 5: complete (commits 52fd1df..d8239fe + fee59a7, review clean)
Task 5: minor (deferred): every pass writes hrLastTriedAt for every PENDING segment while permission missing (observer churn); availability()/hasPermissions() exceptions → FAILED; queued flag on cancel before lock (theoretical); running false while waiting for lock
Task 6: implemented b25c6a1 — JVM 548; S22 PhotoStripTest 4/4, PhotoViewerTest 6/6, PermissionCardsTest 4/4, RecordScreenTest 13/13, HistoryScreenTest 3/3; controller saw screenshot: map shows 4 previews while strip says 3 + 1 hidden → suspect hidden photo drawn on map
Task 6: review → fix round 1 (Important: guided-ride summary (NavigateScreen SegmentSummaryContent) lacks photo count/card/request — plan gap → Ruling R9)
Ruling R9: every segment summary — recording screen AND guided-ride summary in NavigateScreen — gets the same photo count, photo permission card and postProcessor.request() on appear (Task 6 fix), and likewise the heart-rate row/card/1-min refetch (Task 7) — spec §1.1①/§2.3/§2.4/§3.3 say "구간 요약" without exception; plan named only RecordScreen — cost if wrong: one extra pass when a guided ride ends.
Task 6: fix round 1 commit ce7339c (SummaryExtras shared helper; visible-only map test; stale tap; screenshot photo-ui-2.png verified by controller: 3 visible on map, strip 3 + 숨긴 1) — re-review running
Ruling R10: the trip-detail map draws each segment's track as its own line (no straight connector between segments/days) — Task 6 review minor 4; folded into Task 7 because Task 7 reworks the trip track for heart-rate colours — cost if wrong: none.
Task 7 base: ce7339c
Task 6: fix round 1/5 (1 Important + 4 minors addressed, 0 open; commits b25c6a1..ce7339c)
Task 6: complete (commits fee59a7..ce7339c, review clean) — S22 DetailMapPhotosTest 2/2, RecordScreenTest 13/13, PhotoViewerTest 6/6, HistoryScreenTest 3/3, PhotoStripTest 4/4, PermissionCardsTest 4/4
Task 6: minor (deferred): thumbnail in-flight dedup; duplicated SEOUL zone; markers drawn on main thread (many photos); both SummaryExtras may request() when RecordViewModel alive (request coalesces)
Task 7: implemented cfaa67f — JVM 574; S22 HeartRateRowTest 4/4, HeartRateLegendTest 2/2, SegmentDetailScreenTest 5/5, RecordScreenTest 14/14, DetailMapPhotosTest 2/2, HistoryScreenTest 3/3, PermissionCardsTest 4/4; screenshot hr-ui.png checked by controller: colours/legend/row OK, legend box covers the track end + end marker; MapOverlay.lines: List<MapLine(points, colors?)> added for Task 8
Task 8 base: cfaa67f
Task 7: review → fix round 1 (Important: legend + toggle cover track end; fit padding symmetric 120 px) + minors 1 (trip lines coloured with no toggle), 2 (1-min wait restarts on stop/start), 3 (cancelled refetch between replaceSamples/saveExtras), 5 (empty chart flash) + Task 8 concern 5 (trip lines through 5 s track throttle → [기본]/[심박] switch redraws late)
Task 7: concern "dismissed card + PENDING shows no row" accepted (reviewer agreed; Task 9 settings row is the way back)
Task 8: implemented f6b936b — JVM 820 total; S22 CumulativeMapScreenTest 4/4, HistoryScreenTest 4/4, RoomPostStoreTest 9/9; screenshot cumulative-ui.png checked by controller (overlaps darker, all routes framed); MapOverlay.faintLines separate source; 0-distance segments excluded; summary "35.00km · 주행 3회 · 1시간 45분"
Task 8: review → needs fix (Important: "아직 기록이 없습니다" shown to riders with history during the first post-upgrade pass; minors 1 (first read throws → blank, no retry), 3 (vacuous test), 4 (no menu/route test), 5, 6 (cap/join beads))
Ruling R11: cumulative summary fills the labels with values ("35.00km · 주행 3회 · 1시간 45분") — plan copy says "(숫자 채움)", matches trip-card style — cost if wrong: one-line copy change.
Ruling R12: "주행 n회" counts segments with distanceM > 0 (same as RideSegment.isEmpty / history list) — spec "주행 = 구간 수" read as listed segments — cost if wrong: count differs by empty segments.
Task 7: fix round 1 commit 6796af3 (hr-ui-2.png checked by controller: whole track + end marker visible, legend 2×3 top-left) — re-review running
Task 7: fix round 1/5 (1 Important + 4 minors + throttle addressed, 0 open; commits f6b936b..6796af3)
Task 7: complete (commits ce7339c..cfaa67f + 6796af3, review clean) — S22 SegmentDetailScreenTest 7/7, HeartRateLegendTest 2/2, CumulativeMapScreenTest 4/4, DetailMapPhotosTest 2/2, HeartRateRowTest 4/4, RecordScreenTest 14/14
Task 7: minor (deferred): 15-read cap per VM; READY line wraps beside button; legend cells 64 dp may clip at large font; throttle helper closure state; test gaps (grant → request once, onScreen(false))
Task 8: fix round 1 commit 0acb752 (busy/routesSaved signals, settled empty, fit until user moves, butt/miter) — re-review running
Task 9 base: 0acb752
Task 8: fix round 1/5 (1 Important + 4 minors addressed, 0 open; commits 6796af3..0acb752)
Task 8: complete (commits cfaa67f..f6b936b + 0acb752, review clean) — S22 CumulativeMapScreenTest 5/5, HistoryScreenTest 4/4, SegmentDetailScreenTest 7/7
Task 8: minor (deferred): totals assertion in drawsTheStoredSimplifiedRoutesNotTheTracks coincidental (1500+2500 = 2×2000); busy hand-off untested; idle sampled before read (one-tick flash for rider with no routes); 500 ms delay; no ☰ menu/nav device test
Task 9: implemented 0e0e50e — PC 90 classes / 848 tests; S22 SettingsScreenTest 10/10 + regressions; concern: settings [허용] does nothing if Android/HC stopped showing the dialog (final-wave candidate: fall back to app settings / HC settings)
Task 9: review → fix round 1 (Important: settings [허용] silent dead end after repeated refusals → fallback to app details / HC settings; minors 4 (doc overclaim), 5 (README V1.0.0 row), 6 (SDD wording in doc))
Task 9: fix round 1 commit 476a5f8 (settings fallback to app details / HC settings; doc wording) — re-review running
Task 9: fix round 1/5 (1 Important + 3 minors addressed, 0 open; commits 0e0e50e..476a5f8)
Task 9: complete (commits 0acb752..0e0e50e + 476a5f8, review clean) — PC 91 classes / 853 tests; S22 SettingsScreenTest 11/11
Task 9: minor (deferred): no test of resume re-read; card reappears after PARTIAL grant from the row; near-vacuous assertEquals(emptyList(), opened); first-dialog back-out opens app settings once
Final review: 0 Critical, 5 Important (I1 photos duplicated across back-to-back segments; I2 PARTIAL access ends photo scan for good; I3 backlog ride FAILED on first read is permanent; I4 N HC calls + N writes per pass without permission; I5 summary refetch stops at sparse READY) + FIX-NOW 6 (misleading Task 8 test)
Ruling R13: within one trip a photo belongs to exactly one segment — the segment whose [startedAt, endedAt] contains takenAt, else the segment with the nearest boundary (tie → earlier segment); scans of a segment skip photos owned by a sibling — spec §2.1 windows overlap for back-to-back segments (split, SEGMENT_ONLY) — cost if wrong: a boundary photo attaches to the neighbouring day/segment.
Ruling R14: the FAILED retry window is 24 h from max(endedAt, hrFirstTriedAt) — "no permanent FAILED" for backlog rides whose first real read fails — cost if wrong: up to 24 h of retries per failed ride.
Ruling R15: photoScanDone only when access == FULL (PARTIAL keeps rescanning) — the card promises automatic search after granting all photos — cost if wrong: one MediaStore query per old segment per pass under PARTIAL.
Pre-fix-wave S22 full device run (HEAD 476a5f8): 33/33 classes pass, 0 failures (results file scratchpad/v12-device-results.txt)
Final fix wave: aaddca7 (R13 PhotoOwnership + scan dedupe; R15; R14 failedRetryUntil + DAO; I4 one access check/pass, no write; I5 stop at READY&&detailed; test 6) — PC 92/878; S22 RoomPostStoreTest 11/11, RecordScreenTest 14/14, CumulativeMapScreenTest 5/5, DetailMapPhotosTest 2/2, SegmentDetailScreenTest 7/7, RideDatabaseMigrationTest 3/3 — re-review running
Final re-review: FIX-NOW 1–6 ADDRESSED; new N1 (Important): under PARTIAL photo access every pass rescans every segment and writes photosScannedAt for each (N MediaStore queries + N writes per onStart)
Final: parked — N1 — Ruling: accepted for this release and surfaced to the user as a V1.2.1 candidate (skip the no-op extras write; re-scan old PARTIAL segments at most once a day) — only riders who chose "선택한 사진만" pay it, work is background IO on postScope, no data harm — cost if wrong: extra battery/IO per app open for those riders.
Final: parked (minor) — R14 edge: an Empty first-day read sets hrFirstTriedAt, a later single failure after 24 h stays FAILED; boundary photo lost if owning segment has no track points; deleted owner not re-picked by finished neighbour; hidden flag not copied on dedupe; old dev duplicates not cleaned.
