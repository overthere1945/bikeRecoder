# V1.6.0 final fix wave (from the whole-branch review of 51f9e0b..01e91e4)

Do exactly these items, nothing more:

1. **I1, OOM and stack-overflow guards.** In `ImportScanner.summarizeGpx` and in `GpxImportJob.importOne`, around `GpxReader.read` and `RideReplay.replay`:
   - catch `OutOfMemoryError` → `ImportProblem.TOO_LARGE`, or skipped in the job;
   - catch `StackOverflowError` → `UNREADABLE`, or skipped.

   In `ImportReader.walk`, an `OutOfMemoryError` thrown by the read itself (not by the visitor) becomes one `Unreadable(TOO_LARGE)`.

   Add JVM tests in which a fake stream or parse throws OOM: one for the scan (it gives an unreadable TOO_LARGE row and the scan goes on to the next file) and one for the job (that ride is skipped and the run continues).
2. **M1, grants only for files with checked rows.** In `GpxImportJob.start` and `run`, call hold and release only on the sources that have checked rows: `picks.map { it.key.sourceIndex }.distinct().mapNotNull(sources::getOrNull)`. Update `holdsAndReleasesUris`.
3. **M2, a safer delete-all.** Make `RoomImportStore.deleteAllImported` a single conditional `DELETE` that removes only trips whose id is in `trip_import`, whose status is `'COMPLETED'` (check the real enum column and value) and which have no open segment (`endedAt IS NULL`). It returns the count. Children cascade. `RoomImportStoreTest` must keep passing; add a case where an imported trip with an open segment, or one not COMPLETED, is NOT deleted.
4. **M4, short-ride boundary.** Add a `RideReplayTest` case showing that a ride of 10 m or more is kept (`MIN_RIDE_DISTANCE_M`).
5. **Docs, in Korean.**
   - `CHANGELOG.md:22`: a new `GpxReader` was added; `GpxParser` only shares `secureDocumentBuilderFactory`, and its `parse` is unchanged.
   - Add these known limits to both the CHANGELOG and `docs/verification/gpx-import-field-test.md` (알려진 한계):
     - A very large GPX of tens of MB can show "너무 큰 파일입니다".
     - An interrupted recording that has not been closed with [종료] makes every later ride show as "이미 있는 주행과 겹칩니다". The fix is to press [종료] first.
   - Field-test item 3: add a note to check whether bikeRecoder opens as a separate window on top of the file app, and how it looks in the recent-apps list.
6. **KDoc fixes.**
   - `ImportViewModel.kt` around line 79: change "128 binder calls" to "up to 128 grant calls and 256 file opens".
   - Wrap the overlong line at `ImportScanner.kt:50`.
   - In the ImportViewModel class KDoc, say that `uiState` never starts `Closed` when files were handed over.
7. **`MainActivityImportIntentTest`.**
   - Put `tearDown` in try/finally, or `runCatching` per step, so the onboarding setting and the files are always restored and cleaned.
   - Add a test that resolves SEND and VIEW intents with `application/gpx+xml` and `application/octet-stream` (data = a content URI) through `PackageManager.queryIntentActivities`, and asserts that MainActivity is among the results.
8. **`ImportHandoff`.** Add a JVM test that `take()` returns the list once, then null.
9. **Re-verify.**
   - Run `./gradlew :core:test :app:testDebugUnitTest`.
   - On the S22 only, one class per run: `com.cowork.bikerecoder.data.RoomImportStoreTest`, then `com.cowork.bikerecoder.importing.MainActivityImportIntentTest`.
