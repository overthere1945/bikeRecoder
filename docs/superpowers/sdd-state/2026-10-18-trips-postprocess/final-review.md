# Final whole-branch review: V1.2.0 "여행과 후처리" (ef6426a..476a5f8)

## Verdict

**Needs one fix wave, then it can merge.** There are no Critical findings. Five Important findings break spec promises a rider can hit, or cost every app start on a large history. They are small and independent, and one implementer can do them in a single wave (see the FIX-NOW list). Everything else is sound:

- R12 holds.
- The 3→4 migration only adds tables and is tested.
- Health data stays on the device.
- The manifest is minimal.
- The exported entry points are inert.
- The main thread does no decoding.

Scope read: the spec §0–§5, the plan, global-constraints (R1–R12, P1, P2), progress.md, task reviews and rereviews, and the combined diff by file. I also read the current PostProcessor, PostStore/RoomPostStore/Daos, both sources, SummaryExtras, the Record/Navigate/Trip/Segment/Cumulative view models, PermissionCards, HeartRateSection, Settings, BikeMap/TrackThrottle and the manifest. I checked RideController and NavigationController for R12; neither file is changed on this branch. I ran no gradle and no adb.

## Cross-task integration (traced)

| Path | Result |
|---|---|
| Ride ends → summary (record and guided) → pass | `finish()` closes the segment (endedAt set) before `showSummary` (RideController.kt:676-692). `SummaryExtras` (shared by RecordViewModel and NavigateViewModel, R9) calls `request()` when a summary segment appears (SummaryExtras.kt:84). Photo count and heart rate are live through `observePhotos`/`observeExtras` (SummaryExtras.kt:76-77). The 1-min ×15 refetch runs only while the summary is on screen. ✅ |
| Pass → detail screens and cumulative map live | Trip and segment detail observe photos and extras. Samples are re-read on a `hrLastTriedAt` change of a READY row. The cumulative map re-reads on `finishedRuns`/`routesSaved` (CumulativeMapViewModel.kt:106). ✅ |
| Permission granted later → old rides resolve | Heart rate: `HeartRateGate.onGranted` → `request()`. R8 kept old rides PENDING, and R3 reads PENDING always, so they become READY or NONE. ✅ Photos: access NONE keeps `photoScanDone=false`, so a grant finds them. ✅ **But** PARTIAL → FULL does not (I2), and a failed first read of an old ride is final (I3). |
| Ride deleted mid-pass | Every write runs behind `writing{}` → `SegmentGone` (RoomPostStore.kt:80-85). An empty track gives no photos, then the `saveExtras` FK fails → `SegmentGone` → skipped (PostProcessor.kt:148). Refetch: `segment()==null` → return. Cascades cover all four tables. ✅ |
| First launch after upgrade | DB 3→4: AutoMigration that only adds tables, 4.json committed, `v3DataSurvivesMigrationTo4` checks the old rows and that the backlog is listed. ✅ Backlog newest first (Daos.kt:183, R4). ✅ The cumulative map shows no false empty state: `request()` is called in the VM `init` before the first read, `busy` is set synchronously, and `empty` needs `settled` (CumulativeMapViewModel.kt:58, 104, 136). ✅ No permanent FAILED: **not guaranteed**, see I3. |

## Spec coverage §1–§5

- **§1.1**
  - Triggers ① (both summaries), ② (`MainActivity.onStart`) and ③ (trip detail, segment detail, cumulative map) are present.
  - **The 기록 목록 trigger is missing.** HistoryViewModel never calls `request()`. That screen shows nothing post-processed, so a rider sees no difference (Minor, LEAVE).
  - Single flight, open segments skipped, and per-segment/per-step isolation are all in place.
- **§1.2:** all four tables and every column are present, and V1.1.0 segments are processed.
- **§2.1/§2.2:** the window, `DCIM/Camera/`, images only, the 24 h rescan, EXIF behind ACCESS_MEDIA_LOCATION, interpolation, the 30 s snap, the edges, and "no points → skip" are all implemented as specified.
- **§2.3**
  - Previews are 40 dp with a white border, plus the strip, the viewer, hide/show, the hidden-photo entry, the summary count, and pruning on open.
  - Pruning only runs with FULL access, which is correct.
  - Duplicates across adjacent segments are a defect (I1).
- **§2.4:** the cards, the order READ_MEDIA_IMAGES → ACCESS_MEDIA_LOCATION, the PARTIAL card, and "dismissed for good" are present. The PARTIAL card promises "모든 사진을 허용하면 자동으로 찾습니다", which is false for rides older than 24 h (I2).
- **§3.1:** samples come from the Samsung Health origin, calories are the overlap share of BIKING sessions, and `hrDetailed` with its hint is present.
- **§3.2:** PENDING, READY, NONE and FAILED (as amended by R8), the summary 1-min ×15 refetch, and [다시 가져오기] at any time are present. A sparse READY stops the summary refetch (I5).
- **§3.3:** the row, the chart on the distance axis (P1), [기본]/[심박] (not remembered, hidden without detail), the legend, the colours, the ±30 s match, and the trip average/max/kcal/per-date are present.
- **§3.4:** the card, the three permissions, the rationale activity plus the Android 14+ alias, and dismissal are present.
- **§4:** both entry points, the 0.35 faint lines, the fit, the summary (R11/R12), year/month filters, the empty state and 10 m simplification are present. The "1–2 s" target can only be checked on a device.
- **§5:** onboarding is untouched, the settings "사진·심박 연결" row works (with the fallback), the errors are handled, and the migration test exists.

**Can only be checked on a device (S25 + Watch7 field list, or S22):**

- Real Health Connect behaviour:
  - the 2–11 min delivery delay;
  - whether `TimeRangeFilter` matches by overlap or by start;
  - **whether reads fail while the app is in the background** (affects I3);
  - whether sparse all-day samples can land before the workout record (affects I5);
  - the ~30-day history limit without `READ_HEALTH_DATA_HISTORY`.
- Samsung MediaStore: `RELATIVE_PATH`/`DATE_TAKEN` values, and EXIF with location tagging on and off.
- The real permission dialogs, Android 14 "Select photos", and the HC rationale screen.
- Cumulative map draw time with a large history, and how dark the overlaps look.
- The 15 device classes still pending, `RideScenarioTest` and `NavigationScenarioTest` first (the controller is running these now).

## Regressions to V1.0/V1.1

- **R12 holds.** RideController.kt and NavigationController are not in the diff. `PostProcessor` is only reached from MainActivity, the view models and the cards. It runs on its own `postScope` (SupervisorJob + IO, AppContainer.kt:202) and catches everything except real cancellation (PostProcessor.kt:140-163). rideScope never waits on post work. The only shared resource is Room's single writer connection, made of tiny transactions; I4 removes most of these.
- **BikeMap throttle.**
  - `atMostEvery` became `atMostEveryUnless`, and `TrackDrawing(track, colors, lines)` is compared by value.
  - Record and navigate pass only `track` (colors null, lines empty), so `redrawAtOnce` is false and growth is throttled exactly as before (TrackThrottle.kt, BikeMap.kt:308-314). TrackThrottleTest was updated.
  - New difference: `transformLatest` buffers rather than conflates. Only immediate values (colour toggles) can queue (Minor, LEAVE).
  - The new sources and layers are added in `installOverlayLayers` in an order that keeps the user dot on top.
- **Onboarding:** unchanged. No onboarding files are in the diff, and the permissions are "처음 쓸 때" only.
- **Settings:** additive section. Prompt-dismissed flags use the existing `edit` path. The existing tests were extended (11/11 on S22).
- **Trip/segment detail:**
  - Trip map lines are separate per R10.
  - `remember(all)` keeps the fit from re-framing on a colour toggle (equal points).
  - The open-segment delete guard and the dialogs are unchanged.
  - The resume refresh is kept.
- **Room v4:** AutoMigration 3→4 only adds tables. Every new FK has an index (a composite or the PK). The builder has no destructive fallback. The migration test covers v3 data and the backlog listing.

## Privacy and security

- **No network path carries heart rate or photo data.** None of the new code touches the network. The rationale text matches the promise.
- **Logging:** all new logs are WARN with segment, media or trip ids only. No bpm, coordinates or EXIF values are logged. Exceptions from Health Connect and MediaStore are logged as they are; they do not carry sample values.
- **Manifest:** READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION, READ_EXTERNAL_STORAGE (maxSdk 32, R5), and the three HC reads. `<queries>` lists only the HC package. This is minimal. There is no background-read and no history permission; see the I3 and Minor notes.
- **Exported entry points:**
  - `HealthRationaleActivity` is exported without a permission, as the HC contract for API ≤ 33 requires. It reads no extras and only shows static text and [닫기], so it is safe.
  - The `ViewPermissionUsageActivity` alias is guarded by `START_VIEW_PERMISSION_USAGE`, a system permission. It is safe.

## Performance

- **Main thread:**
  - Thumbnails and full images are decoded on IO (PhotoThumbnails.kt:213).
  - Polylines are decoded on `compute` (RoomPostStore.kt:76).
  - Track and line GeoJSON is built on Default (BikeMap.kt:314, 320).
  - Heart-rate colours and the chart run on `compute`.
  - The map markers, small 40 dp canvases, are drawn on main (Task 6 deferred; fine).
  - `heartRate.availability()` (a PackageManager call) runs on main in `refetchHeartRate` and `HeartRateAccessState.refresh` (Minor, LEAVE).
- **Pass cost, steady state:**
  - With FULL photos and HC granted, a segment leaves the list 24 h after it ends, so a pass is O(recent).
  - **Without HC permission or HC, every PENDING segment is listed on every pass forever.** Each one costs `availability()`, a `getGrantedPermissions` IPC and a `saveExtras` write, so N writes per `onStart`, each invalidating every `observeExtras` collector (I4).
  - Without photo access, every segment is listed too, but it costs only an extras read and `checkSelfPermission`, which is acceptable.
- **Backlog pass:** per segment, one points read, one Douglas–Peucker run (iterative, degenerate-safe) and up to four HC calls. This is fine for V1.1.0-sized histories. Health Connect rate limits are only a concern for very large backlogs, and I3 covers their effect.
- **IN-lists:** `deletePhotosByMediaId` and `existing()` are chunked at 500. `observePhotos`/`observeExtras` take trip-sized lists. `segmentsNeedingWork` has no IN-list. This is fine.

## Findings

### Critical

None.

### Important

**I1. A photo near a segment boundary is stored under both segments, so trip detail shows it twice, and hiding one copy leaves the other.**

- **Where:** PostProcessor.kt:197-205 scans `[start−5 min, end+5 min]` per segment, RidePhotoEntity is unique only per `(segmentId, mediaId)`, TripDetailViewModel.kt:161-163 shows the union, and Daos.kt:203-204 hides by row id.
- **How a rider hits it:** "새 구간으로 나누기" (RideController.kt:290-301) and a guidance ending as SEGMENT_ONLY followed by a new recording (RideController.kt:73) put segments back to back. Every photo within 5 min of the boundary then gets two rows, one interpolated on A and one at B's start.
- **What the rider sees:**
  - Two map previews.
  - The strip and "사진 N장" counted twice.
  - Viewer pages repeated.
  - [숨기기] removes only one copy.
  - Both segment summaries count the photo.
- **Impact:** V1.3.0 video would inherit the duplicates.

**I2. Rides scanned under "선택한 사진만" are marked done and are never re-scanned after the rider allows all photos.**

- **Where:** PostProcessor.kt:174 scans whenever access is not NONE, and :204 sets `photoScanDone = now > endedAt + 24h` whatever the access.
- **How a rider hits it:** on the first pass under PARTIAL, every V1.1.0 ride (and any ride older than 24 h) becomes done with only the selected photos. The rider then follows the card's own advice, "모든 사진을 허용하면 자동으로 찾습니다" (PermissionCards.kt:49), grants FULL, and those rides never get their photos. This fails field item 6 on the Android 14 "Select photos" path.

**I3. An old (V1.1.0) segment whose first real heart-rate read fails becomes FAILED for good.**

- **Where:** HrStatus.kt:25 and Daos.kt:182 retry FAILED only while `now ≤ endedAt + 24h`. Backlog segments are past that on their first read.
- **How a rider hits it:** after the rider grants HC, the backlog pass reads each old ride once. Any `HrRead.Failed` makes it FAILED and it is never retried automatically:
  - a transient IPC error;
  - Health Connect's rate limit;
  - the app going to the background mid-pass, since Health Connect refuses reads from background apps without `READ_HEALTH_DATA_IN_BACKGROUND` (to confirm on device).
- **Recovery:** only [다시 가져오기] on each segment's detail screen; trip detail has no button. The controller listed "no permanent FAILED" for the upgrade path, and R1's cost note did not consider the backlog.

**I4. Every pass does N Health Connect IPCs and N no-op writes when heart rate cannot be read.**

- **Where:** PostProcessor.kt:212-229. For each PENDING segment, the pass calls `availability()` and `hasPermissions()`, an IPC through `granted()`, and then `saveExtras(hrLastTriedAt = now)`.
- **Who hits it:** a rider who declined HC or has no HC, on every `MainActivity.onStart`, including returns to the app mid-ride.
- **Cost:** a whole history of segments means hundreds of IPCs and hundreds of write transactions on Room's single writer. Each write re-emits every open `observeExtras` (TripDetailViewModel.kt:165, SegmentDetailViewModel.kt:87, SummaryExtras.kt:77).
- **Why it can be removed:** the write has no reader. Nothing uses `hrLastTriedAt` for a PENDING row.
- **Same function, Task 5 deferred minor:** an exception from `availability()` becomes `HrRead.Failed`, which means FAILED instead of R8's PENDING.

**I5. The summary's 1-minute refetch stops at a sparse READY, so the watch workout's detailed heart rate can be missed.**

- **Where:** SummaryExtras.kt:108 and :116 stop as soon as `hrStatus == READY`. Passes never read a READY row again.
- **How a rider hits it:** spec §0.1 says all-day samples (~10 min apart) exist as well as the workout's ~1 s samples, and that the workout record arrives 2–11 min after the end. If any all-day sample in the ride window is already in HC at the first read, the segment becomes READY with `hrDetailed=false`. The dense workout data then never arrives without a manual [다시 가져오기].
- **Impact:** this would fail field item 2 ("몇 분 안에 심박이 들어오고 요약 화면에 바로 보임") and item 3 ([심박] is hidden when the data is not detailed).
- **Certainty:** whether HC can hold sparse samples before the workout record must be confirmed on a device. The guard is one line and stays within spec §3.2's "1분마다 다시 읽어 들어오면 바로 보인다".

### Minor (LEAVE)

- **History list does not trigger a pass** (spec §1.1 ③, a plan gap). No rider effect.
- **Photos shared between adjacent segments of different trips** (trip A completed, trip B started within 5 min) remain in both trips after FIX-NOW 1. This is rare.
- **No `READ_HEALTH_DATA_HISTORY`.** HC gives roughly 30 days before the first grant, so older V1.1.0 rides become NONE. This is acceptable given V1.1.0's age and matches §1.2's "남아 있으면".
- **Photo positions:** a ride scanned with ACCESS_MEDIA_LOCATION refused keeps TRACK positions after a later grant, once it is done. That follows the rider's choice.
- **`availability()` on the main thread** in refetch and the access check (one PackageManager call).
- **`transformLatest` buffers** immediate redraws instead of conflating them.

## Deferred minors from progress.md: triage

| # | Deferred minor | Call | Why |
|---|---|---|---|
| T1-1 | zigzag DP test ~4.7 s, no timeout | LEAVE | Only slows the test run; does not mislead. |
| T1-2 | GeoMath.project allocations, O(n²) worst case | LEAVE | Real GPS tracks are far from the worst case; runs on IO, once per segment. |
| T1-3 | PolylineCodec lives in RouteSimplifier.kt | LEAVE | File placement only. |
| T2-1 | `Samples(emptyList())` forbidden only by KDoc | LEAVE | `hrReadOf` enforces it at the real source. |
| T2-2 | nearest-after and tie-break untested | LEAVE | The code is correct by inspection (HeartRate.kt:79-85). |
| T2-3 | HC reader must sort samples | DONE | `HealthConnectMapping.samples` sorts, and `hrReadOf` sorts again. |
| T3-1 | no photo access → every segment listed each run | LEAVE | Extras reads only. The heart-rate half is I4 (FIX-NOW 4). |
| T3-2 | corrupt polyline fails the map; decode on caller dispatcher | DONE | Fixed in Task 8 (`toEntries` skips the row; decode on `compute`). |
| T3-3 | bbox written but not read; empty points | LEAVE | No reader; harmless. |
| T3-4 | IN-list > 999 on API 30 | LEAVE | Callers are trip-sized; deletes are chunked. |
| T3-5 | no mediaId index | LEAVE | Small table, delete only. |
| T3-6 | every constraint error → SegmentGone | LEAVE | With IGNORE/REPLACE and typed entities, only an FK error can occur. |
| T3-7 | DAO parameter name `failedUntil` | LEAVE | Naming. FIX-NOW 3 touches the line anyway. |
| T4-1 | no paging test of the real ClientRecordReader | LEAVE | Can only be tested on a device. |
| T4-2 | no positive DATE_ADDED device test | LEAVE | Covered by the JVM test `PhotoTakenAtTest`. |
| T4-3 | 60 s HR margin under start-time filtering | LEAVE | Workout samples are covered by the widened session window. Check on device. |
| T4-4 | `FakePhotoSource.uri()` device-only | LEAVE | Test fake. |
| T5-1 | `hrLastTriedAt` churn while permission is missing | **FIX-NOW** | I4 / FIX-NOW 4. |
| T5-2 | availability/hasPermissions exceptions → FAILED | **FIX-NOW** (folded) | Fixed by the same per-pass check in FIX-NOW 4 (exception → not readable, R8). |
| T5-3 | queued flag on cancel before the lock | LEAVE | `postScope` is never cancelled. |
| T5-4 | `running` false while waiting for the lock | LEAVE | The screens use `busy`. |
| T6-1 | thumbnail in-flight dedup | LEAVE | At worst a duplicate decode on IO. |
| T6-2 | duplicated SEOUL zone | LEAVE | Polish. |
| T6-3 | markers drawn on the main thread | LEAVE | Small 40 dp canvases; no decoding. |
| T6-4 | both SummaryExtras may `request()` | LEAVE | The requests coalesce. |
| T7-1 | 15-read cap per view model | LEAVE | At most a few extra reads after re-entering. |
| T7-2 | READY line wraps beside the button | LEAVE | Cosmetic. |
| T7-3 | legend cells 64 dp may clip at large font | LEAVE | Only at large accessibility fonts; the rider uses the default. |
| T7-4 | throttle helper closure state | LEAVE | A new flow per `LaunchedEffect(style)`, so the state is never shared. |
| T7-5 | test gaps (grant → request once, `onScreen(false)`) | LEAVE | The behaviour is simple and reviewed. |
| T8-1 | `drawsTheStoredSimplifiedRoutesNotTheTracks` totals coincidental | **FIX-NOW** | Misleading test. Its comment says segment totals "would not match", but 1500 + 2500 = 2 × 2000 (CumulativeMapViewModelTest.kt:110-126). |
| T8-2 | busy hand-off untested | LEAVE | Correct by inspection (PostProcessor.kt:160). |
| T8-3 | idle sampled before the read | LEAVE | Errs toward "not settled"; the next tick fixes it. |
| T8-4 | 500 ms delay | LEAVE | Design choice. |
| T8-5 | no ☰ menu/nav device test | LEAVE | Trivial wiring; the field test covers it. |
| T9-1 | no test of the resume re-read | LEAVE | Simple. |
| T9-2 | card reappears after a PARTIAL grant from the settings row | LEAVE | That is the spec's PARTIAL card, and [닫기] dismisses it. |
| T9-3 | near-vacuous `assertEquals(emptyList(), opened)` | LEAVE | An extra assertion; the real `calls` assertion stands. |
| T9-4 | first-dialog back-out opens app settings once | LEAVE | Rare, and it lands where access can be given. |

## FIX-NOW (one wave, one implementer)

1. **Each photo has one owning segment within its trip (I1).**
   - In `PostProcessor.scanPhotos`, before `upsertPhotos`, read `rideStore.segments(segment.tripId)` and keep a candidate only if this segment owns its `takenAt`:
     - the segment whose `[startedAt, endedAt ?: +∞]` contains it wins;
     - otherwise, the segment with the nearest start or end boundary;
     - a tie goes to the earlier segment.
   - No schema change.
   - JVM tests in PostProcessorTest:
     - a split at T with photos at T−2 min and T+2 min → one row each, under A and B;
     - a gap case;
     - B still open → a photo inside B is not put under A.
   - This refines the §2.1 window and needs a controller ruling line in progress.md.
2. **"선택한 사진만" never finishes a scan (I2).**
   - At PostProcessor.kt:204, set `photoScanDone = access == PhotoAccess.FULL && now > endedAt + PHOTO_RESCAN_MS`. Pass the access read at :174.
   - Test: PARTIAL past 24 h keeps `photoScanDone=false`; switching to FULL later finds the rest.
3. **FAILED is retried for 24 h from the first real read, not only from the ride's end (I3).**
   - `HrRetryPolicy.shouldTry(status, endedAt, firstTriedAt, now)`: FAILED → `now <= max(endedAt, firstTriedAt ?: endedAt) + GIVE_UP_MS`.
   - DAO (Daos.kt:182): `(e.hrStatus = 'FAILED' AND :now <= MAX(s.endedAt, COALESCE(e.hrFirstTriedAt, s.endedAt)) + :failedUntil)`.
   - Update the PostStore KDoc and R1's wording.
   - Tests:
     - HrStatusTest: a backlog segment that failed on its first read is retried for 24 h, then given up;
     - PostProcessorTest;
     - RoomPostStoreTest needs an S22 re-run because of the DAO change.
4. **Check heart-rate access once per pass and skip the R8 no-op write (I4, T5-1, T5-2).**
   - In `cycle()`, compute `hrReadable = availability()==AVAILABLE && hasPermissions()` once, treating any exception as false.
   - Pass it to `process`. When it is false, do not call `readHeartRate` and do not write.
   - `refetchHeartRate` keeps its own check.
   - Test with a counting fake: N PENDING segments without permission → `hasPermissions` called once, zero `saveExtras` from the heart-rate step.
5. **The summary refetch goes on while the heart rate is READY but not detailed (I5).**
   - SummaryExtras.kt:116: `isReady` = `hrStatus == READY && hrDetailed`.
   - `refetchHeartRate` already overwrites READY with new samples, and R7 keeps the stored data on Empty or Failed.
   - Test in SummaryExtrasTest: a sparse READY keeps reading and stops once a detailed read arrives.
6. **Make the cumulative totals test discriminate (T8-1).**
   - In CumulativeMapViewModelTest.kt:115-126, use simplified distances whose sum differs from 2 × 2000 (for example 1 500 and 3 000 → 4 500.0).

After the wave:

- Re-run on S22 one class at a time: RoomPostStoreTest, RecordScreenTest and CumulativeMapScreenTest.
- Run the 15 pending device classes, `RideScenarioTest` and `NavigationScenarioTest` first.
- Add one line to the field-test doc: "주행 중 홈으로 나간 뒤 심박이 FAILED로 남지 않는지" for I3's device question, and "희박한 심박 뒤 운동 심박이 요약에 들어오는지" for I5.
