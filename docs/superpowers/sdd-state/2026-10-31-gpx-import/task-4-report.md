# Task 4 report — app: import scanner and the import job

Status: DONE_WITH_CONCERNS (concerns are minor; see the end)
Commit: ec3ea4d `app: import — scanner and the import job` (BASE 48529b3). Not pushed.

## Files

Main (`app/src/main/java/com/cowork/bikerecoder/`):
- `importing/ImportReader.kt`:
  - `interface ImportReader` (`displayName`, `open`, `hold`, `release`). Signatures match the brief.
  - `ContentImportReader(context)`. `displayName` queries `OpenableColumns.DISPLAY_NAME` and falls back to the URI's last piece. `hold` tries `takePersistableUriPermission(READ)`, ignores failures and remembers what it held. `release` lets go only of those.
  - Internal helper `ImportReader.items(uri, displayName, limits, io): Flow<ImportItem>`. It is a `channelFlow` with a RENDEZVOUS buffer, `flowOn(io)`. `ImportUnpacker.visit` runs on `io`, and the visitor hands each item to the collector with `trySendBlocking`.
    - At most about 2 items' bytes are in memory at once.
    - When the collector stops (it finds what it wants, or is cancelled), the channel closes. The visitor then throws `CancellationException`, so the reading stops.
    - An open failure or a read failure (e.g. a ZIP `IllegalArgumentException` on a bad entry name, which `visit` does not catch) ends that source with one `Unreadable(UNREADABLE)`. Its index follows the items already given.
- `importing/ImportScanner.kt`: `RideKey`, `ScanRow`, `ScanResult` and `ImportScanner`, with exactly the brief's signatures.
- `importing/GpxImportJob.kt`: `ImportJobState` (Idle, Importing, Finished) and `GpxImportJob`.
  - Constructor: `reader`, `store`, `postProcessor`, `profile`, `clock`, `scope`, `log: WarnLog`, `limits`, `compute`, `io`.
  - Methods: `state`, `start(sources, picks): Boolean`, `stop()`, `acknowledge()`.
- `importing/ImportFormat.kt`: every spec §5 string as a constant or function.
  - `problemText`, `activityText`, `statusLine`, `rowDateText(startedAt, zone = Seoul)`.
  - `headerText`, `filesText`, `readingText`, `importingText`, `importButtonText`, `resultText`.
  - `importedCountText`, `deleteConfirmText`, `tooManyEntriesText(name, limit)`, `tooManyRidesText(limit)`, `importedFromText`.
  - Constants: `IMPORT_*` and `PROBLEM_*`, including `IMPORT_CARD_SUFFIX = " · 가져옴"` and `IMPORT_BUSY_SHARE`.
  - Counts use `groupedInt` (so "2,000"), distance uses `kmText`, and duration uses `RideFormat.duration`. The date is `"${year}년 " + cardDateText + " " + clockText`, the same pieces as `feedDateLine`.
- `AppContainer.kt`:
  - `val importReader: ImportReader = ContentImportReader(appContext)`.
  - `val gpxImportJob by lazy { GpxImportJob(..., postProcessor = postProcessor::request, profile = { settings.settings.first().defaultProfile }, clock = System::currentTimeMillis, scope = appScope, log = androidWarnLog("GpxImportJob")) }`.

Tests (`app/src/test/java/com/cowork/bikerecoder/importing/`):
- `ImportFakes.kt`:
  - `gpx(start, points, stepM, type)`: points every 2 s, moving 10 m east (5 m/s).
  - `gpxEnd`, `zip(...)`.
  - `FakeImportReader`: records opened, held and released URIs.
  - `FakeImportStore`: spans, saved rides, and an `onSave` hook that may block or throw.
  - `T0` = 2023-05-14 09:12 KST.
- `ImportScannerTest` (8), `GpxImportJobTest` (10), `ImportFormatTest` (7). Test names are as in the brief, and `ImportFormatTest` adds its own.

## Design notes

Scanner:
- It calls `onProgress(0, n)` first, then `(i+1, n)` after each source.
- For each source it gets the display name on `io` and collects `items()`:
  - `TooManyEntries` becomes a notice from `tooManyEntriesText(name, limits.maxZipEntries)`.
  - Every other item becomes a row. A Gpx item is read and replayed on `compute`. On `GpxReadException` the row is Unreadable with that problem. Any other parse exception gives UNREADABLE, and a null replay gives TOO_SHORT. Only the summary is kept, with `previewPoints(ride.points)`.
- `maxRides` counts rows, both readable and unreadable. This bounds the list. When the (max+1)-th row arrives, the scanner adds the `tooManyRidesText` notice and stops reading. No further files are opened, and exactly max rows give no notice.
- At the end it calls `statuses(spans, activities, store.segmentSpans(clock()))` in read order. Rows with a null span keep their own Unreadable problem instead of the placeholder.
- Sort order: readable rows by `startedAt` descending (stable), then unreadable rows in read order.
- Cancellation: `ensureActive` runs before each source, and cancelling the collector closes the channel, which stops `visit` mid-ZIP.

Job:
- `start()` claims the job under a lock. It returns false while running, otherwise sets `Importing(0, n)`, resets the stop flag and launches with `CoroutineStart.ATOMIC`, as `VideoJob` does.
- `run()`:
  - The try block covers `hold(sources)` on io and `profile()` (read once). Picks are grouped by `sourceIndex`, in ascending order.
  - For each source, `items()` is collected with `firstOrNull` until every wanted itemIndex has been handled or a stop was requested. Each file is opened once.
  - Per ride: replay on `compute`. The ride is skipped when replay is null or throws `GpxReadException`, when its start or end differs from the row, or when it overlaps `segmentSpans(clock())`, which already includes rides saved earlier in this run. Otherwise `saveImported(ImportedRide(ride, profile, item.name, clock()))` runs.
  - Any other exception is logged with `WarnLog` and counts as skipped. `CancellationException` is rethrown.
  - A wanted item missing from the file (the file is gone, changed, or not permitted) counts as skipped.
  - `stop()` sets a volatile flag. The flag is checked after each ride, so a save is never interrupted.
- `finally`:
  1. `release(sources)` (NonCancellable, guarded).
  2. `postProcessor()` once, only if `imported > 0` (guarded).
  3. `Finished` is set and the job freed under the lock.
- How `Finished` is counted:
  - `stopped` is true only when the stop flag was set and some picks were left unhandled. A stop during the last ride is not "멈춤".
  - When stopped, `skipped` is the rides handled but not saved.
  - Otherwise `skipped = total - imported`. This also covers an unexpected run-level failure, which is logged.
  - Scope cancellation (process teardown) counts as stopped.
- `acknowledge()` moves `Finished` to `Idle` by compareAndSet.

## Tests

- `./gradlew :app:testDebugUnitTest --tests '*importing*'`: 25/25 pass (ImportScannerTest 8, GpxImportJobTest 10, ImportFormatTest 7). I ran it 5 more times with `--rerun` and it stayed green; `cancelStopsReading` was not flaky.
- Mutation check:
  - Removing the job's overlap recheck fails `rechecksOverlapBeforeSave` and `noPostProcessorWhenNothingSaved`.
  - Letting the scanner keep the `statuses()` placeholder fails `tooShortRowIsUnreadable` and `rowsSortedNewestFirstUnreadableLast`.
  - Both mutations were reverted.
- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL. core 441, app 1093 (1068 before + 25), 0 failures, 0 skipped.
- No compiler warnings in the new files. No device test (as instructed).
- Step 2 ("see it fail") was not run separately: the tests and the code were written together. The mutation check above stands in for it.

## Concerns / notes for later tasks

1. **`maxRides` counts every row, unreadable ones included.** The spec wording is "주행이 너무 많아 처음 500개만 보여 줍니다". Counting only readable rides would let the list grow without bound on junk ZIPs. Cost if wrong: a one-line change.
2. **`ContentImportReader` is not JVM-tested.** It is thin. Persistable permission only works for SAF URIs picked with `ACTION_OPEN_DOCUMENT`, and the intent must carry the persistable grant flag (the default for OPEN_DOCUMENT). Shared `ACTION_SEND` URIs fail silently and stay readable only while the grant lives. This is spec-acknowledged: such rides are counted as skipped.
3. **The `io` dispatcher must not be the collector's single thread.** The visitor blocks in `trySendBlocking`, so a single-threaded test dispatcher for both would deadlock. This is documented in the KDoc. The Task 5 ViewModel should keep the defaults (Default / IO), not `Dispatchers.Main` or a test scheduler for `io`.
4. **The job has an extra `log: WarnLog` parameter.** The brief's `...` allows it. AppContainer passes `androidWarnLog("GpxImportJob")`. The log message holds only the `RideKey`, never file names.
5. **The job runs in `appScope`.** It has no foreground service, matching spec §3.4 ("앱 프로세스가 죽으면 거기서 멈춘다").
6. **Task 5 needs two extra copy helpers.** `ImportFormat.kt` also has `filesText`, `readingText`, `importingText`, `importButtonText`, `importedCountText`, `deleteConfirmText` and `importedFromText`. Task 5 should use these and not repeat the strings. `headerText(found, new)` takes counts, and the caller decides what "found" means.

## Fix round 1

### I-1: stop is ignored while unpicked entries are read
In `GpxImportJob.importSource`, the walk visitor checks `stopRequested` in two places:
- first, on every item, picked or not;
- again right before `importOne`.

Either check ends the source walk. Picks after the stop are not counted, and the result is `stopped = true`.

Test `stopSkipsLaterPicksInSameZip`:
- Setup: one ZIP whose entries are `first.gpx`, 40 text entries, then `last.gpx`. Both rides are picked.
- `FakeImportReader.wrap` paces the stream at 64 bytes per read. Once the first ride has been saved, the stream pauses among the unpicked text entries until the test presses stop.
- The test asserts `Finished(1, 0, true)` and that only the T0 ride was saved.
- Mutation check: with both stop checks removed (the old behavior), this test fails. With only the first check removed, the pre-`importOne` check still catches the stop, so the test passes.

### I-2: suspend visitor replaces the channel handoff
- Core `ImportUnpacker.visit` and `visitZip` are now `suspend`, with `visitor: suspend (ImportItem) -> Boolean`. A visitor that returns false stops the ZIP walk.
- The KDoc (Korean) says one item at a time and that the caller runs it on IO.
- `ImportUnpackerTest` now wraps calls in `runBlocking`, and the new test `visitorFalseStopsWalk` was added. ImportUnpackerTest: 18 tests.
- In app, `ImportReader.items()` (channelFlow + `trySendBlocking`) and its KDoc are removed. They are replaced by `internal suspend fun ImportReader.walk(uri, displayName, limits, io, visitor)`:
  - It runs `withContext(io) { open(uri).use { ImportUnpacker.visit(...) { ensureActive(); visitor(it) } } }`.
  - It holds one item at a time.
  - `use` closes the stream on every path.
  - An open or read failure (not one thrown by the visitor) gives one `Unreadable(UNREADABLE)` at the next index, unless the visitor had already stopped.
  - Exceptions thrown by the visitor propagate.
- The scanner and the job both call `walk`. The replay runs inside it via `withContext(compute)`. There is no channel and no `runBlocking`.
- New tests:
  - `ImportScannerTest.runsOnOneTestDispatcher`: `runTest` with `io = compute = StandardTestDispatcher(testScheduler)`.
  - `ImportReaderTest.walkStopsAndClosesTheStream`
  - `ImportReaderTest.walkOpenFailureIsOneUnreadableItem`
  - `ImportReaderTest.walkVisitorFailurePropagatesAndCloses`

### M-1: the 500 cap counts readable rides only
- The cap now counts only rows with a span. The first readable ride over the cap is dropped, gets the notice, and the reading stops.
- `stopsAt500RidesWithNotice` now mixes junk files in between the rides:
  - The cap stays at 2 rides, and every junk row is kept.
  - `later.gpx` is never opened.
  - At exactly 2 rides plus junk rows, there is no notice.

### M-2: hold in `start()`
- `start()` calls `reader.hold(sources)` synchronously, right after claiming the job and before the launch. It is guarded by `WarnLog`.
- `release` still runs in the run's `finally`.
- `holdsAndReleasesUris` now also asserts that the URIs are held when `start` returns.

### Grant cap (128)
- New `internal class UriGrants(take, drop, limit = MAX_PERSISTED_GRANTS = 128)`:
  - `hold` takes at most 128 grants, skips URIs already held and ignores take failures.
  - `release` drops only the URIs it actually took, and drops each once.
- `ContentImportReader` delegates `hold`/`release` to it, so the cap is JVM-testable.
- Test `ImportReaderTest.holdCapsAt128`: 201 URIs, the first one not persistable. It checks that exactly the next 128 are taken and that release drops exactly those, once.

### Tests
- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL. core 442 (441 + `visitorFalseStopsWalk`), app 1099, 0 failures and 0 errors in both.
- Importing tests: GpxImportJobTest 11, ImportScannerTest 9, ImportReaderTest 4, ImportFormatTest 7. Three more `--rerun` runs were green.
- No compiler warnings in the changed files. I removed one "Expression is unused".

### Notes
- `walk` blocks the `io` thread while a provider read blocks. Interrupting that on cancel is out of scope, as ruled.
- Not handled this round, as ruled: an OutOfMemoryError on one ride.
