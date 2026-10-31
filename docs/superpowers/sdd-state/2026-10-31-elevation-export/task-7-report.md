# Task 7 report — app: GPX·KML 내보내기

Status: **DONE_WITH_CONCERNS** (concerns are small; see the end)
Commit: `09c7408 app: export — GPX and KML to Download/bikeRecoder with share and open` (base 58817e7)

## Files

New, `app/src/main/java/com/cowork/bikerecoder/export/`:
- `ExportText.kt`: every user-visible Korean string from spec §5 (menu, recording note, saved dialog, errors), plus `savedPath(name)` and `menuItem(format)`.
- `TripExporter.kt`:
  - `ExportFormat` (GPX/KML, extension and MIME exactly as in the brief).
  - `exportFileName(prefix, epochMillis, format)`, Asia/Seoul `yyyyMMdd_HHmm`.
  - `ExportResult` (Saved / Failed / Recording).
  - `fun interface ExportSink`.
  - `TripExporter` with `exportTrip` and `exportRoute`. Constants `TRIP_PREFIX`/`ROUTE_PREFIX` and `creator(versionName)`.
- `DownloadsWriter.kt`:
  - Implements `ExportSink`, with `write(displayName, mime, content): Uri` and `displayName(uri)`.
  - `MediaStore.Downloads`, `RELATIVE_PATH = "Download/bikeRecoder/"`, `IS_PENDING` 1 → UTF-8 write → `IS_PENDING` 0.
  - On any failure it deletes the inserted row and rethrows (a cleanup error is added as suppressed).
  - `save()` reads the real `DISPLAY_NAME` back. If that read fails, it falls back to the requested name, because the file is already saved.
- `ExportRunner.kt`: `ExportState` (Idle/Running/Done/Failed/Recording) and `ExportRunner`, which runs one export at a time in a VM scope. Both view models use it.
- `ExportDialog.kt`, the UI pieces:
  - `ExportTags`.
  - `ExportDialog(saved, onShare, onOpen, onDismiss)`: title `내보냈습니다`, body `Download/bikeRecoder/{name}`, buttons [공유] [파일 열기] [닫기] in the dialog's flow row.
  - `ExportMenu`: [⋮] `더 보기` with the two items. When disabled, the items are dimmed and `기록을 마친 뒤 내보낼 수 있습니다` is shown. The button is disabled while an export runs.
  - `ExportProgress`: a small spinner overlay.
  - `ExportOutcome`: shows the dialog for Done, and the Toast `저장하지 못했습니다` for Failed or `기록을 마친 뒤 내보낼 수 있습니다` for Recording, then clears.
  - `ExportIntents.share`: `Intent.createChooser` over `ACTION_SEND` with type = MIME, `EXTRA_STREAM`, ClipData and `FLAG_GRANT_READ_URI_PERMISSION`. Same pattern as V1.5.0 `ShareIntents` and `shareMedia`.
  - `ExportIntents.view`: `ACTION_VIEW` with (URI, MIME) and the read grant.
  - `startExport` catches `ActivityNotFoundException` and shows the Toast `열 수 있는 앱이 없습니다`.

Modified:
- `AppContainer.kt`: lazy `tripExporter`, which uses `DownloadsWriter(contentResolver)`, `Dispatchers.IO`, the system clock, `creator = "bikeRecoder ${BuildConfig.VERSION_NAME}"` and `androidWarnLog("TripExporter")`.
- `TripDetailViewModel.kt`:
  - New ctor param `exportTrip` (a lambda; the factory passes `container.tripExporter::exportTrip`).
  - `recording` is true when any segment, empty ones included, has `endedAt == null`.
  - Adds `exportState`, `canExport`, `export(format)` (uses the shown `title.value`) and `clearExport()`.
- `TripDetailScreen.kt`:
  - `TripDetailContent` gets `onExport`, `exportEnabled` and `exporting`.
  - [⋮] sits at the right end of the title row, after [✎].
  - The progress overlay sits in the scaffold box over the content.
  - `ExportOutcome` is hosted on the screen.
- `PlanViewModel.kt`:
  - New ctor param `exportRoute`; the factory passes `container.tripExporter::exportRoute`.
  - `export(format)` maps `PlannedStop` to core `Stop` (key → id, last stop is the destination) and passes the destination name. `exportRoute` itself wraps it with `ExportLabels.routeName(...)`, as the T3 carry note requires.
  - Adds `exportState`, `canExport` and `clearExport()`.
- `PlanScreen.kt`:
  - The summary sits in a Row with [⋮] on its right, shown only while `route is Ready`.
  - The panel is wrapped in a Box so the spinner overlays it.
  - `ExportOutcome` is hosted on the screen.

Tests:
- New JVM tests:
  - `export/ExportFileNameTest`: `tripNameUsesSeoulStart`, `routeNamePrefix`, `formatsCarryTheirMime`.
  - `export/TripExporterTest`: `tripGpxHasAllSegmentsAndHeartRate` (also checks that empty segments are excluded and the stores are unchanged), `tripKmlHasEverySegment`, `openSegmentIsRecording`, `writerFailureIsFailed`, `tripWithoutRidesIsFailed`, `exportRunsOffMainAndWritesWholeFile`, `routeKml`, `routeGpxNamedAfterDestination`, `creatorCarriesTheVersion`.
- Added JVM tests:
  - `TripDetailViewModelTest`: `exportUsesTheShownTitle`, `openSegmentIsRecording`.
  - `PlanViewModelTest`: `export hands the ready route and its stops over`, `export failure is failed and without an exporter there is none`.
- New device tests:
  - `export/DownloadsWriterTest`: `writesUnderDownloadBikeRecoder`, `sameNameGetsSuffix`.
  - `export/ExportDialogTest`: `showsPathAndButtons`, `intentsCarryTheFile` (builds the intents only), `screenshot` → `v170-export`.
- Added device tests:
  - `TripDetailScreenTest`: `overflowMenuExports`, `recordingTripMenuDisabled`, `noExporterNoMenu`.
  - `PlanScreenTest`: `overflowOnlyWhenReady`, which uses a fake exporter lambda, so no file is written and nothing is shared.

## Decisions
- Title: `exportTrip` exports under the title the trip screen shows (`TripDetailViewModel.title`). The VM passes that title in. [⋮] lives in the title row, which only exists when there is a title.
- Segments exported: the non-empty segments (`!isEmpty`) in start order, the same set the screen lists. The file name uses the first of them. Any open segment, listed or not, gives `Recording`. A trip with no ride left gives `Failed` (logged).
- Heart rate is read only for GPX, since KML carries none. A failed sample read is logged and the segment is exported without HR instead of failing the whole file.
- One `ExportRunner` is shared by both VMs. A second tap while running is ignored, and [⋮] is disabled during the run.
- The Recording outcome (a race where the menu was enabled but a segment opened meanwhile) shows the spec's `기록을 마친 뒤 내보낼 수 있습니다` as a Toast.
- Chooser title is `null`, as in `shareMedia`, so no extra Korean string was needed.

## Deviations from the brief
1. `ExportResult.Saved(uri: String, displayName: String, mime: String)` instead of `Saved(uri: Uri, displayName)`.
   - App JVM tests have no Robolectric, and `android.net.Uri` cannot be constructed there. The app already keeps MediaStore URIs as strings (`ShareCandidate.uri`, `TripMedia.uri`, `viewMedia(uri: String)`).
   - `mime` was added so [공유] and [파일 열기] carry the right type.
   - `DownloadsWriter.write` still returns `Uri`, as the brief says.
2. `exportTrip(tripId, format, title)`: there is an extra `title` parameter so the file uses the exact shown title (custom title or "저녁 라이딩 · 서울 강서구") without the exporter needing the label store or regions.
3. The `TripExporter` ctor also takes `creator` and `log`. `writer` is typed as the `ExportSink` interface, which `DownloadsWriter` implements, so a fake writer can be used.
4. One extra file, `ExportRunner.kt`, holds the shared state machine.
5. In `DownloadsWriterTest`, MediaStore records `application/octet-stream` for a `.gpx` file on the S22 because the platform type map doesn't know gpx. The display name is unchanged. The test therefore checks the name, path, published state and contents rather than the stored MIME. The share and view intents carry `application/gpx+xml` themselves.

## Results
- PC: `./gradlew :core:test :app:testDebugUnitTest` → **core 521/521, app 1184/1184** (was 1168; +16), 0 failures.
- 30k-point timing: `exportRunsOffMainAndWritesWholeFile` built and wrote the 30,000-point GPX in **~526 ms** on the JVM.
  - This is well under the ~2 s threshold, so `GpxWriter` number formatting was **not** changed. Core is untouched.
  - The test asserts:
    - The sink ran on the injected io thread.
    - The file ends with `</trk>\n</gpx>\n`.
    - There are 30,000 `<trkpt `.
    - It took under 5 s.
- S22 `R5CT43TFS9A`, one class per run, screen woken first:
  - `export.DownloadsWriterTest` 2/2. The first run failed only on the stored-MIME assertion (see deviation 5) and was re-run green.
  - `export.ExportDialogTest` 3/3.
  - `ui.history.TripDetailScreenTest` 15/15.
  - `ui.plan.PlanScreenTest` 9/9.
- After the runs, `content query` on `Download/bikeRecoder/` returned **No result found**: no test file was left behind. `DownloadsWriterTest` deletes exactly the URIs it created.
- Screenshots in `Download/bikeRecoderTest/`:
  - `v170-export.png` shows the dialog with the path and the three buttons on one row. Checked visually.
  - The plan screenshot this run landed as `v170-plan (2).png`; it shows [⋮] right of the summary.
- The S25 was not touched. No phone settings were changed. No real share target or viewer was launched. `secrets.json` was not read or committed.

## Concerns
- The existing per-test `screenshot()` helper cannot delete a PNG left by an earlier install, so MediaStore adds a suffix. For example, this run's plan shot is `v170-plan (2).png`, and an older `v170-plan.png` exists. This was already the case before this task and is not changed here.
- The plan VM is Activity-scoped. If the user leaves the plan screen while a route export runs, the saved dialog appears the next time the plan screen is shown. On the trip screen, leaving cancels the coroutine, but a blocking MediaStore write already under way still finishes, so a file may be saved without a dialog. Both cases are rare.
- A trip whose title is null has no title row, so it gets no [⋮]. That happens with no custom title and no finished ride ≥ 10 m, e.g. while the first segment is still recording. The dimmed menu therefore only appears for trips that already have a finished ride and are recording again.

## Fix round 1

Commit: `5c9de4e app: export — Task 7 review fixes` (on 09c7408). Deviations 1, 2 and 4 were accepted by the review.

1. **Untitled trips get the [⋮] (spec §4.1, Important).**
   - `TripDetailContent` now draws the title row whenever `onExport != null`, even when `title == null`. In that case the title area is an empty `Spacer` and [⋮] sits at the right end.
   - ✎ and the paper plane behave as before: they show only when there is a title and `onRename` is set.
   - `TripDetailViewModel.export()` no longer returns when `title.value` is null. It uses `fallbackTitle()` instead: `RideTitle.shown(label, earliest start, distinct days, zone)` over all listed (non-empty) segments, without the 10 m rule. If no segment is listed, it uses the custom title, and failing that `"bikeRecoder"`.
2. **A late plan dialog (Minor).**
   - `ExportRunner` has a generation counter. `discard()` bumps it and sets the state to Idle. A job that finishes under an older generation drops its outcome.
   - `PlanViewModel.clear()`, which runs when guidance starts, calls `discard()`.
   - Nothing is cleared in `onDispose`, so rotation is unaffected.
3. **Zone-sensitive test (Minor).** `ExportFileNameTest.tripNameUsesSeoulStart` sets `TimeZone.setDefault(UTC)` and restores it in `finally`.
4. **Double run (Minor).** `TripDetailViewModelTest.exportTwiceWhileRunningExportsOnce` holds the first export on a gate, calls `export` again, and asserts there is exactly one export.

New and changed tests:
- JVM:
  - New `export/ExportRunnerTest`: `secondRunWhileRunningIsIgnored`, `discardDropsTheRunningOutcome`.
  - `PlanViewModelTest`: `clear discards a running export`.
  - `TripDetailViewModelTest`: `shortTripExportsWithAFallbackTitle` (a 5 m trip has a null title and exports under "저녁 라이딩"), `exportTwiceWhileRunningExportsOnce`.
- Device (`TripDetailScreenTest`):
  - `untitledTripHasMenu`: `더 보기` is shown with `title = null`, there is no ✎, and the KML item exports.
  - `untitledRecordingTripMenuDisabled`: no title and recording, so both items are disabled and the note is shown.

Results:
- PC: `./gradlew :core:test :app:testDebugUnitTest` → core 521/521, app 1189/1189, 0 failures.
- S22 `R5CT43TFS9A`, one class per run, screen woken first, `ANDROID_SERIAL` and `</dev/null`:
  - `TripDetailScreenTest` 17/17.
  - `PlanScreenTest` 9/9.
- The S25 was not touched (it was not even connected).
