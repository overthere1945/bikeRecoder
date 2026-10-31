# Task 3 report — Android boundary (commit f182ca8)

Status: DONE.

## Built
- `share/ShareApps.kt`: `ShareApps` interface, `PackageShareApps` (package manager lookup, per brief), constants `FACEBOOK_PACKAGE`, `INSTAGRAM_PACKAGE`.
- `share/ShareIntents.kt`: `file()` and `textOnly()` per spec §4.2. File intents always carry EXTRA_STREAM, type, ClipData, GRANT_READ. EXTRA_TEXT is added only for EMAIL and MORE (brief); other buttons send the file only. textOnly always has EXTRA_TEXT. COPY/SAVE return the plain send (never launched).
- `share/ShareCandidates.kt`: runs on IO, drops entries whose gallery file is gone, aspect from `VideoOptionsJson.decode` (its fallback is the PORTRAIT default), `sizeBytes = writer.sizeOf`.
- `MediaStoreWriter.sizeOf(uri)`: reads SIZE (pending included), null when no row or 0.
- Manifest `<queries>`: two packages, SEND `*/*`, SENDTO `mailto`. No new permission.

## TDD
Tests written first; `:app:compileDebugAndroidTestKotlin` failed with unresolved references (ShareApps, ShareIntents, ShareCandidates, sizeOf). Then implemented.

## Results
- JVM `:core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL, 1407 tests, 0 failures, 0 errors, no new warnings.
- S22 (R5CT43TFS9A), one class per run: ShareIntentsTest 10/10, ShareCandidatesTest 4/4, MediaStoreWriterTest 3/3. Tests use a fake ShareApps and launch nothing.
- Gallery cleanup: tearDown deletes and asserts; afterwards a MediaStore query for `bikeRecoder_test%` on images and video found nothing.
- Extra tests beyond the brief: instagramFallsBackToInstagramPackage, textOnlyEmail/More, unreadable options default PORTRAIT, sizeOf null for gone/empty.

## S22 app evidence
Facebook and Instagram are NOT installed on the S22 (`pm list packages` shows neither; `pm query-activities` ACTION_SEND image/png with -p returns none). So no Instagram ACTION_SEND class names could be observed; the Story/Feed class-name lookup is untested against the real app. (PackageShareApps itself was not exercised on a device with the apps; the user's S25 checklist covers it.)

## Concerns
- Story/feed selection relies on the "Story"/"Direct" class-name convention, unverified on a device. Fallback is the package, per spec.
- The one-off `--rerun-tasks` run was slow (about 90 s per class); no effect on results.
