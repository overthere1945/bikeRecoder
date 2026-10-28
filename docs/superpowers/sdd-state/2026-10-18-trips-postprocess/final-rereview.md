# Final fix wave re-review (476a5f8..aaddca7, FIX-NOW 1–6, R13–R15)

Scope: final-review.md FIX-NOW 1–6, final-fix-report.md, review-476a5f8..aaddca7.diff, and the current files the diff touches. Tests: `./gradlew :core:test :app:testDebugUnitTest` ran BUILD SUCCESSFUL (up to date at HEAD aaddca7 with a clean tree). The JUnit XML shows core 27 classes / 244 tests and app 61 classes / 618 tests, 0 failures. Those counts match the fix report. No adb was used and nothing was changed except this file.

### Finding Verdicts

- **FIX-NOW 1 / I1 / R13: one owning segment per photo within a trip.** ADDRESSED.
  - **Rule** (`core/.../post/PhotoOwnership.kt:16-30`): `minWithOrNull(compareBy(distance, startedAt, id))`. The distance is 0 inside `[startedAt, endedAt]`, or for an open segment from its start on. Outside a segment it is the gap to the nearest boundary. A tie goes to the earlier start, then the lower id. This is R13 exactly. A shared boundary (a split at T) goes to the earlier segment (`PhotoOwnershipTest.backToBackSegmentsShareTheirBoundaryWithTheEarlierOne`).
  - **Other trips are excluded.** Only `rideStore.segments(segment.tripId)` is considered (`PostProcessor.kt:219-221`).
  - **Open segments.** An open segment is never processed, because `segmentsNeedingWork` filters `endedAt IS NOT NULL`. It still counts as an owner candidate, so a photo inside a still-recording B is not stored under A. This is the intended reading of the FIX-NOW test, and it is tested by `aPhotoInsideAnOpenSegmentIsNotPutUnderTheOneBefore`, which also checks that B has no extras.
  - **Interrupted open segments.** I checked whether an interrupted (never-closed) open segment could swallow later segments' photos. It cannot: `RideController.prepare` → `closeOpenSegments()` (RideController.kt:409, :709-715) closes every open segment before a new one opens.
  - **Dedupe cannot orphan a photo.** A row is deleted only from this segment, and only when `ownerOf` names a *different* segment of the same trip (`PostProcessor.kt:223-224`, DAO `DELETE … WHERE segmentId = :segmentId AND mediaId IN …`, Daos.kt). Rows on other segments are untouched (`deletePhotosRemovesOnlyThatSegmentsRows`).
    - When segments do not overlap, the owner is at least as near as this segment, so it is within 5 min. The photo is therefore inside the owner's own scan window, and the owner stores it on its own scan if that scan is not done yet.
    - The owner cannot already be done without having stored it. Being done needs a FULL scan more than 24 h after the owner's end. Segments are only added later in time, so an owner's set of owned photos can only shrink after its final scan.
    - The residual cases are deletion-driven and Minor: the owning segment is deleted by the rider, or the owner has an empty track. See the out-of-scope notes.
  - **Hidden flag not copied to the kept row.** Acceptable, Minor.
    - **Legacy dev duplicates:** the rider already saw the other, visible copy, so the view does not change.
    - **The one real path:** A stores X, the rider hides X on A's summary, then a new segment B of the same trip starts within about 10 min of A's end and nearer to X. A's rescan drops the hidden row, and B inserts X visible. The photo reappears and can be hidden again. No data is lost.
    - "새 구간으로 나누기" cannot cause this. B exists, open, from the instant A closes, so A never stores the photos after the split.
  - **Tests:**
    - PostProcessorTest: split at T±2 min, gap and tie, open B, and the legacy plus later-sibling drop;
    - core PhotoOwnershipTest 9/9;
    - device RoomPostStoreTest `deletePhotosRemovesOnlyThatSegmentsRows`, including more than 999 ids (the chunked path, `RoomPostStore.kt:171-176`).
- **FIX-NOW 2 / I2 / R15: "선택한 사진만" never finishes a scan.** ADDRESSED.
  - The access read at `PostProcessor.kt:182-183` is passed into `scanPhotos`.
  - `photoScanDone = access == PhotoAccess.FULL && now > endedAt + PHOTO_RESCAN_MS` (`:233`). PARTIAL keeps the segment listed and scanning.
  - `selectedPhotosOnlyNeverFinishesTheScan` checks that it stays not done and still listed past 24 h, and that the later FULL scan finds the rest and finishes. (Its new cost is in New Breakage N1.)
- **FIX-NOW 3 / I3 / R14: FAILED retried until max(endedAt, hrFirstTriedAt) + 24 h.** ADDRESSED.
  - **Policy:** `HrRetryPolicy.failedRetryUntil = maxOf(endedAt, firstTriedAt ?: endedAt) + GIVE_UP_MS`, and `shouldTry` FAILED is `now <= …`, inclusive (HrStatus.kt:26, :34).
  - **DAO SQL** (Daos.kt:186) matches: `:now <= MAX(s.endedAt, COALESCE(e.hrFirstTriedAt, s.endedAt)) + :giveUpMs`. It is inclusive, and `s.endedAt` is non-null under the WHERE, so the scalar `MAX` never sees NULL. RoomPostStore.kt:37 passes `GIVE_UP_MS` positionally.
  - **FakePostStore** uses the same function.
  - **First-read timestamp:** `hrFirstTriedAt` is set only by a real read (`tried()`, PostProcessor.kt:290). The R8 null path writes only `hrLastTriedAt`, so "first read" means the first real read and the window does not slide (asserted).
  - **Tests:** core boundary tests (inclusive, then +1 ms), PostProcessorTest backlog give-up and recovery, and device `failedIsListedForADayFromItsFirstRead`.
- **FIX-NOW 4 / I4 (+T5-1, T5-2): heart-rate access checked once per pass, with no no-op write.** ADDRESSED.
  - **At most one check per pass:** a memoised lazy `readable` lives in `cycle()` (PostProcessor.kt:147-148). It is evaluated only after `shouldTry(...)` is true, through `&&` short-circuit (`:186`), so a pass with nothing to read checks nothing.
  - **No write when unreadable:** the heart-rate step is skipped entirely, so there is no `hrLastTriedAt` write (`:186`). Tested with counting fakes: 3 PENDING rides give 1 availability check, 1 permission check, 0 `saveExtras` and unchanged extras. With NOT_INSTALLED there is no permission IPC.
  - **Exception skips the pass:** `heartRateReadable()` (`:241-247`) catches the exception, calls `ensureActive`, logs once and returns false. The pass is skipped, not marked FAILED, and the status stays PENDING (or FAILED stays as it was). `anAccessCheckThatThrowsSkipsThePassNotFailed` shows two throwing passes give 2 warnings, 0 writes, PENDING, and then READY once readable.
  - **R8 interaction:** PENDING rows are untouched and stay listed, and R3 always reads PENDING. A later grant (`HeartRateGate.onGranted` → `request()`) therefore resolves old rides. See `oldPendingSegmentWithoutPermissionStaysPendingThenResolvesWhenGranted`, now asserting `hrLastTriedAt == null`.
  - **Refetch:** `refetchHeartRate` keeps its own check (`:115`), and a throwing check there now keeps the status (`aRefetchWhoseAccessCheckThrowsKeepsTheStatus`).
- **FIX-NOW 5 / I5: the summary refetch continues on a sparse READY.** ADDRESSED.
  - `isDetailedReady` = `hrStatus == READY && hrDetailed` (SummaryExtras.kt:108, :120-126).
  - The loop is still bounded by `refetched[id] < REFETCH_TIMES` (15) (`:104`).
  - `hrDetailed` is recomputed on every Samples read (PostProcessor.kt:272-276), and R7 keeps the sparse data on Empty or Failed.
  - Tests:
    - sparse READY keeps reading, then stops at the detailed read (with kcal);
    - a sparse READY followed by Empty gives exactly 15 reads, and READY with avg 95 is kept.
- **FIX-NOW 6 / T8-1: the cumulative totals test discriminates.** ADDRESSED.
  - The simplified distances are 1 500 + 3 000, asserting 4 500.0. The fixture's segment totals are 2 000 each (CumulativeMapViewModelTest.kt:100), which would give 4 000.
  - A single entry gives 1 500 or 3 000, and the 4-point tracks are about 0.26 km. No plausible wrong source equals 4 500, so the test is no longer coincidental, and the comment was corrected.

**Changed existing tests.** Each is justified and none is weakened.

- `noPermissionStaysPendingAndIsRetried` and `oldPendingSegmentWithoutPermission…`: `hrLastTriedAt` changed from `now` to `null`. This is the I4 behaviour itself, and every other assertion stays: no reads, PENDING, `hrFirstTriedAt` null, then READY/NONE after the grant.
- `failedPastLimitIsNotRetried`: "past the limit" moved to `END + 60 s + DAY + 1`. The first read at END + 60 s moves the limit under R14. It still asserts no retry and FAILED.
- HrStatusTest: the existing calls gained `firstTriedAt = null`, which reduces to the old `endedAt + DAY` formula, so their meaning is unchanged.
- DetailPhotosTest: the old fixture had overlapping segments a [100, 2100] and b [200, 2200], and planted photos at times outside, or shared with, the segment they were stored under. R13's dedupe correctly moved them.
  - The new fixture separates the segments (b at 10 000) and plants each photo inside its own segment (`start + 10·m`), keeping the same order. The assertions (trip union, hide, prune, R10 lines) are unchanged.
- SummaryExtrasTest `setup()` gained defaulted parameters only.

### New Breakage in the Fix Diff

- **N1 (Important): under "선택한 사진만" (PARTIAL), every pass now rescans and rewrites every segment in the history.**
  - **Where:** `PostProcessor.kt:182-183, 219-235` with Daos.kt `segmentsNeedingWork` (`photoScanDone = 0` stays listed).
  - **Before:** a PARTIAL pass marked segments older than 24 h done once.
  - **Now (R15):** they are never done, so every pass (every `MainActivity.onStart`, including returns to the app mid-ride) does, for each segment of the whole history:
    - a `segments(tripId)` read;
    - a stored-photos read;
    - a MediaStore query (IPC);
    - sometimes a points read and an `upsertPhotos`;
    - an **unconditional `updateExtras` write** that sets only `photosScannedAt = now`.
  - **`photosScannedAt` has no reader.** Grep: it is only mapped in RoomPostStore.kt:122/136 and written at PostProcessor.kt:233.
  - **Why it matters:** this is the cost pattern I4 was raised as Important for. There are N no-op write transactions on Room's single writer per pass, each re-emitting every open `observeExtras` collector (trip detail, segment detail, summary), and here N MediaStore IPCs instead of N Health Connect IPCs. On Android 14+ "Select photos", PARTIAL is a normal state, not an edge.
  - **Smallest fix (one line of logic):** in `scanPhotos`, skip the extras write when it would change nothing a reader uses. For example, write only when `photoScanDone` flips, when there is no extras row or `photosScannedAt` is null, or while inside the 24 h window.
  - **Optional further step:** limit old-segment PARTIAL rescans to passes where the access differs from the last pass's. That needs no schema change if it is kept in memory and run once per process. This is not required to close N1.
  - Add a counting-store test like `withoutPermissionAPassChecksOnceAndWritesNothing`: N old PARTIAL segments with nothing new give 0 `saveExtras`.
- Nothing Critical. Everything else in the diff is consistent with the rulings and is covered by tests.

### Out-of-Scope Observations

These are non-blocking and Minor.

- **R14 residual (a ruling-level limit, implemented as ruled).** `hrFirstTriedAt` is also set by an *Empty* read. A recent ride can be read Empty within its first day (PENDING, first set), then go unread until more than 24 h later (the app not opened). If that next read then fails transiently, it is FAILED with `max(end, first) + 24 h` already past, so it is never retried automatically. The rider sees "심박을 가져오지 못했습니다" where "심박 기록 없음" was due. Recovery is [다시 가져오기]. Keying the window on the first FAILED transition (or on `hrLastTriedAt` when the status becomes FAILED) would close it, but that would change R14's wording.
- **R13 with an empty-track owner.** A segment that owns a boundary photo but has no track points places nothing ("no points → skip"), so that photo is stored nowhere. Before R13 the neighbour would have kept it. An example is a later segment of the same trip started within about 10 min and stopped before any fix. This is rare.
- **R13 after deleting the owning segment.** If the rider deletes segment C, a boundary photo C owned is not picked up again by a neighbour that has already finished scanning. This is rare, and it follows the rider's own deletion.
- **Hidden flag on a disowned row** (above): a photo hidden on A before a later sibling claimed it reappears visible. It is cosmetic and can be hidden again.
- **Duplicates on segments already done** on pre-release dev installs are not cleaned (as reported). Reinstalling clears them, and no release install has them.

### Verdict

**Fix round:** Findings remain open.

- FIX-NOW 1–6 are all ADDRESSED.
- One new Important regression is introduced by the R15 change:
  - **N1:** under PARTIAL photo access, every pass rescans every segment in the history and writes a no-op `photosScannedAt` extras row for each (`PostProcessor.kt:232-234`). This is the same N-writes-per-`onStart` churn that I4 removed for heart rate. It needs the small "no write when nothing changed" guard and a counting test, or an explicit controller acceptance of the cost.
