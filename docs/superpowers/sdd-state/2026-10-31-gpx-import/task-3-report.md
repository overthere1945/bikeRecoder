# Task 3 report: DB v8 and RoomImportStore

Status: DONE (one core fix, see below).

## Files
- app main: `data/Entities.kt` (TripImportEntity), `data/Daos.kt` (ImportDao, ImportedNameRow, SegmentSpanRow), `data/AppDatabase.kt` (version 8, AutoMigration 7->8, importDao()), `data/RoomImportStore.kt` (new), `data/RoomTripStore.kt` (`Trip.toEntity` private -> internal, reused), `AppContainer.kt` (`importStore`).
- app schema: `app/schemas/.../8.json` new; 7.json unchanged.
- core: `importing/GpxReader.kt` (DOCTYPE fix).
- androidTest: `data/RoomImportStoreTest.kt` (6), `RideDatabaseMigrationTest` (+migrate7To8KeepsDataAndAddsEmptyImportTable), `importing/GpxReaderAndroidTest.kt` (2).

## Design notes
- saveImported: one `db.withTransaction`: trip (SINGLE_DAY, COMPLETED, profile, createdAt=startedAt, lastActiveAt=completedAt=endedAt, no stops) -> segment (existing `RideSegment.toEntity`) -> points (existing `TrackPoint.toEntity`) -> trip_import (fileName.take(200), importedAt).
- deleteAllImported: runs in a transaction, calls `TripDao.deleteTrip` per imported id (CASCADE), returns the count.
- segmentSpans: `COALESCE(endedAt, :now)` in SQL. Column aliases are `spanStart/spanEnd` because `end` is an SQL keyword (KSP rejected it).
- failureMidSaveLeavesNothing: a point with `lat = NaN`. SQLite stores NaN as NULL, so `track_point.lat NOT NULL` fails after the trip and segment were inserted; the test asserts a SQLiteConstraintException and 0 rows in trip, ride_segment, track_point, trip_import.

## Core change (carry-over from Task 1 review)
The Android XML parser ignores `disallow-doctype-decl`, so a DOCTYPE with an entity was parsed, not rejected (`doctypeIsRejectedOnAndroid` failed on the device first). Fix: `GpxReader.read` now throws `GpxReadException(UNREADABLE)` when `document.doctype != null`. This is a no-op on the JVM where the factory already rejects it. `:core:test` stays green. Caveat: the check runs after parsing, so it proves the file is refused, not that Android never touched the external URI. Android's pull-parser based DOM builder does not fetch external entities, and the entity resolver in `GpxReader` still throws, but the test cannot observe an actual file read.

## Tests
- RoomImportStoreTest (S22): 6 tests, 0 failures.
- RideDatabaseMigrationTest (S22): 7 tests (6 existing + new), 0 failures.
- GpxReaderAndroidTest (S22): 2 tests, 0 failures (after the fix).
- `:core:test`: 438 tests, 0 failures. `:app:testDebugUnitTest`: 1068 tests, 0 failures.

## Concerns
- Step 2 (watch the tests fail before the implementation) was not run as a separate step; the compile and tests were done after writing both.
- The "no external file is read" half of the DOCTYPE test is not directly observable (see above).

## Fix round 1
Changes:
- `GpxReader.read` scans the bytes for `<!DOCTYPE` (US_ASCII, UTF_16LE, UTF_16BE) before parsing and throws `GpxReadException(UNREADABLE if looksLikeGpx else NOT_GPX)`, the same classification as the SAXException path. The post-parse `doctype` check stays as a second layer, with its comment reworded (disallow-doctype-decl is not supported on Android; ParserConfigurationException is swallowed).
- `RoomImportStore`: file name cut drops a trailing lone high surrogate; `deleteAllImported` KDoc explains why the DAO delete is used directly (imported trips are COMPLETED, no offline maps, no stops).

Tests (output):
- `:core:test`: 441 tests, 0 failures (3 new in GpxReaderTest: doctypeRejectedBeforeParsing, utf16DoctypeRejected [LE/BE, with/without BOM], doctypeInNonGpxXmlIsNotGpx). On the JVM these also passed before the pre-scan, because the factory already rejects DOCTYPE; the pre-scan matters on Android.
- `:app:testDebugUnitTest`: 1068 tests, 0 failures.
- S22 GpxReaderAndroidTest: 3 tests, 0 failures (plainGpxReadsOnAndroid, doctypeIsRejectedOnAndroid, nestedEntitiesRejectedQuickly: 10 levels x 10 references, throws GpxReadException, test time about 0 ms, limit 2 s).
- S22 RoomImportStoreTest: 7 tests, 0 failures (new: fileNameCutKeepsSurrogatePairs).
