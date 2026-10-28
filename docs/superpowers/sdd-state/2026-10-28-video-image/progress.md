# SDD ledger — plan: docs/superpowers/plans/2026-10-28-video-image.md
Spec: docs/superpowers/specs/2026-10-28-video-image-design.md (approved 2026-10-28). Branch feature/video-v1.3.0. Method: subagent-driven (user chose). Device: S22 R5CT43TFS9A only; S25 install on user request.

## Pre-flight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1→T2,T5,T6 | Aspect, VideoLength, VideoOptions, FPS, RidePoint/RideTimeline(+Builder), PhotoCue, FramePlan, VideoTimeline, VideoEstimate | consistent |
| T2→T5,T6 | Camera/CameraPath, Hud, SummaryImageLayout, MediaNaming, AudioPlanner | consistent |
| T3→T6,T7 | TripMedia(Store), VideoJobStore, PendingJob, MediaStoreWriter | consistent |
| T4→T6 | VideoEncoder.open(fd, aspect) → (encoder, downscaled), AudioTrackMaker.make, Unreadable | consistent |
| T5→T6 | VideoInputs(Loader), FrameRenderer.render, SummaryImageRenderer, downloadForTrack/deleteVideoRegions | consistent |
| T6→T7 | VideoJob.state/startVideo/startImage/cancel/unfinished, StartResult incl. MUSIC_UNREADABLE | consistent |
| T1 self | RideTimelineBuilder needs RideSegment+TrackPoint (core types) and ZoneId | consistent |
| T3 self | version bump touches AppVersionTest/SettingsScreenTest (named) | consistent |
| T5 self | VideoInputs.totals type TripTotals not defined elsewhere | Ruling V1 |
| T6 self | "기록 시작 시 멈춤" via observing rideUi (RideController unchanged preferred) | consistent |
| T7 self | battery check in UI; StartResult messages | consistent |

Ruling V1: TripTotals is defined in Task 5 (app video/VideoInputs.kt): distanceM, movingTimeMs, avgSpeedMps, maxSpeedMps, ascentM, avgBpm?, calories? — reuse existing trip totals / trip heart-rate helpers from ui/history (move shared helpers if needed) — cost if wrong: duplicate code.

## Progress
Task 1 base: a103f28
Task 1: implemented 4ec043f — core 285 (24 new), 50k-point plan 0.16 s; review running
Ruling V2: choosePhotos fills empty slots with the nearest remaining photo so up to `cap` photos appear even when photos cluster — spec says "고르게 퍼지도록"; filling only matters when slots are empty — cost if wrong: several photo pauses close together.
Task 2 base: 4ec043f
Task 1: complete (commits a103f28..4ec043f, review clean)
Task 1: minor (deferred, final-wave candidates): last ride frame stops 1 frame short of totalMoveMs (divide by rideFrames-1 + test); no zero-move-time test; clustered-fill rule untested; caption date from first-point time instead of segment.localDate; caption truncated near end; timing-test 1 s threshold flaky risk; per-photo first frame re-snapshots within a group
Ruling V3: photos whose moveMsAtTime is null (taken before the first / after the last point, within the ±5 min window) attach to moveMs 0 / totalMoveMs — matches V1.2 PhotoPlacement start/end rule — carry to Task 5 (VideoInputsLoader).
Ruling V4: "N일째" counts ride days (same as history badge "여러 날 · N일"), not calendar days since trip creation — spec silent; consistent with the badge — cost if wrong: caption number differs from the in-ride banner after a rest day.
Task 2: implemented 041b2d5 — core 298 (13 new); review running
Carry to Task 5: CameraPath.overview assumes 512-px tiles and pixel ratio 1 over aspect width×height — the MapSnapshotter must use pixelRatio 1 at the output size, or lower the overview zoom by log2(ratio).
Task 3 base: 041b2d5
Task 2: review → fix round 1 (Important: overview only fits full Aspect size → summary image map box (1080×960 / 1152×1080) and 720p fallback don't fit)
Ruling V5: CameraPath gains overview(points, widthPx, heightPx); the Aspect version delegates — Task 5 uses the box/encoder size — cost if wrong: none.
Task 2: fix round 1 commit 7976c14 — core 301 — re-review running
Task 2: fix round 1/5 (1 Important + 4 minors addressed, 0 open; commits 041b2d5..7976c14)
Task 2: complete (commits 4ec043f..041b2d5 + 7976c14, review clean)
Task 2: minor (deferred, final-wave candidate): 25 m threshold freezes bearing on loops/switchbacks with short legs — fallback look-ahead 75 m then 40 m before keeping previous bearing; descent has no turn-rate limit (spec limits ride only)
Task 3: implemented 05e061b — app 699; S22 MediaStoreWriterTest 3/3, RoomTripMediaStoreTest 2/2, RideDatabaseMigrationTest 4/4, SettingsScreenTest 11/11; extra files video/TripMedia.kt and VideoOptionsJson accepted; delete of already-gone item ignored; review running
Task 4 base: 05e061b
Task 3: complete (commits 7976c14..05e061b, review clean)
Task 3: minor (deferred): broken-pending-job test fails on missing fields not on unknown kind; KDoc notes (DISPLAY_NAME may differ; delete swallows SecurityException for not-owned); VideoOptionsJson.Entry hand-synced
Ruling V6: PendingJob gains `outputUri: String?` (Task 6 adds it, default null for old data) so a half-written IS_PENDING gallery item left by a forced kill is deleted when the unfinished job is offered again/dismissed — spec §3.3 "만들던 파일은 지운다" — cost if wrong: none. Task 7: prune trip_media only on exists()==false, never on exception; read DISPLAY_NAME back if shown.
Task 4: implemented 87dca29 — S22 VideoEncoderTest 3/3 (all 48 frames' colour, pts, keyframes, 1080/720), AudioTrackMakerTest 4/4; review running
Ruling V7: VideoEncoder draws via EGL (software Bitmap Canvas → texture → eglSwapBuffers + eglPresentationTimeANDROID) instead of lockHardwareCanvas — lockHardwareCanvas dropped 30–38 of 48 frames on the S22 — signature drawFrame((Canvas)->Unit) unchanged — cost if wrong: some CPU per frame.
Carry: Task 5 must use software Bitmaps only (ImageDecoder.ALLOCATOR_SOFTWARE / BitmapFactory) on the frame Canvas; Task 6 must call AudioTrackMaker.make + addAudio before the first drawFrame.
Task 5 base: 87dca29
Task 4: complete (commits 05e061b..87dca29, review clean)
Task 4: minor (deferred; carry to Task 6: close() only in the job coroutine's finally after cancel — never concurrently with drawFrame; on encoder failure check free space to choose the "저장 공간이 부족합니다" message). FINAL-WAVE candidates: 720 fallback triggers on any IllegalStateException (narrow to size/config failures); close() not stage-safe; HandlerThread leak on OOM; frame-count mismatch only logged; EGL release resets thread context; 5.1 downmix drops centre; colour metadata (BT.709) unset; test gaps (4 s fade test, close without finish).
Task 5: implemented 31557a8 — S22 FrameRendererTest 7/7, SummaryImageRendererTest 3/3, MapLibreOfflineControllerTest 10/10; JVM 711; review running. Controller note: outro card is semi-transparent over a busy map and hides part of the route — overview must fit the area above the card.
Task 5: review → Needs fixes (Important: outro card hides route + semi-transparent; snapshotter.cancel() off-Main in invokeOnCancellation). Fix round 1 dispatched to implementer (also Minor 1,3,4,5,7,10).
Task 5: minor deferred to FINAL WAVE: video download holds mutex for minutes (block trip delete during video job, or make cancellable); maxTiles unbounded + ignores Wi-Fi-only (controller judgement: user-initiated, acceptable); stale complete VIDEO region reused after forced kill; video→ui.history dependency; small renderer duplication; missing tests (HEART_RATE colour, offline-with-cache=true).
Carry (Task 6): VideoMaps from container.offlineMaps; call deleteVideoRegions in finally + NonCancellable; FrameRenderer loadPhoto = PhotoThumbnails.full(id, 2048, software=true); downloadForTrack false → NO_INTERNET; build OUTRO camera from fix-round interface.
Task 5: complete (commits 87dca29..a44b86f, fix round 1 re-review clean)
Task 5: minor (FINAL WAVE): outro device test's "0 route px inside card" is vacuous (card drawn after route) — assert a band just above cardTop or check pre-card bitmap.
Task 6 base: a44b86f — implementer dispatched (extra: VideoJobDeviceTest real 30 s video + image on S22; renderer built at encoder's final size)
Task 6: implemented 5d04419 — JVM core 307/app 738; S22 VideoJobDeviceTest 2/2 (real 30 s 1080x1920 video in ~75 s, image ~1 s); review running
Task 6: review → Needs fixes (I1 cancel at IO boundary loses pending uri / sink; M1 name time = first segment startedAt; M2 publish/pending order). Fix round 1 dispatched (+M3,M4,M5,M7,M8,M9). Accepted: suspend start*, message(neededBytes), extra files, derived Korean strings. Deferred: M6 space classification before deleting partial; M10 documented for Task 7.
Task 6: complete (commits a44b86f..d500d35, fix round 1 re-review clean)
Task 6: minor (FINAL WAVE): finally{opened?.close()} masks original exception (use checkNotNull(opened).use{}); orphan IS_PENDING window between setPending(no uri) and publish (safer: keep outputUri, clearLeftovers deletes only IS_PENDING=1); Throwable catch lacks `published` guard (published file but Failed); mirror StateFlow conflation may skip A's done notification; M6 space classification before partial delete; boundary test relies on `running` set after launch.
Task 7 base: d500d35 — implementer dispatched (UI polish per user taste; screenshots ui-*.png, video-preview.png, image-preview.png)
Task 7: implemented 4567721 — JVM app 779; S22 new UI tests 16/16 + regressions; real app-flow video (ffprobe h264 1080x1920 24fps 30.000s) + image; screenshots ui-*.png. Controller notes: portrait image without photos leaves a big blank band; trip-total '14.5 / 23.4km/h' wraps 'h' (pre-existing); estimate '약 3분' vs measured 75 s.
Task 7: review → Needs fixes (I1 progress card/cancel shown on every trip after recreation; I2 recording-start Failed shown as modal over record screen). Fix round 1 dispatched (+M1,M3,M7,M8,M9, no-photo summary image layout, trip-total km/h wrap). Deferred FINAL WAVE: M2 ▶ icon after resume, M4 persistable grants, M5 sheet hide anim, M6 test gaps. Estimate default 0.25 s kept (spec §3.6; self-corrects after first video).
Task 7: complete (commits d500d35..f386b1c, fix round 1 re-review clean)
Task 7: minor (FINAL WAVE): NumberRow auto-shrink now also applies on record/segment screens (check visually); run finally Error leaves claimed/runningTripId set (pre-existing); M2 ▶ after resume; M4 persistable grants; M5 sheet hide anim; M6 test gaps.
Task 8: complete (88dc8b4, controller-reviewed docs diff: consistent)
Final review dispatched on d4ee71e..88dc8b4 (opus)
Final review: merge after FIX-NOW (I1 video download holds offline mutex for minutes; I2 post-publish trip_media FK failure reported as Failed; M1 Error in finally leaves claimed; M2 preview auto-opens over record screen; M3 doc line). Data safety confirmed (4→5 adds trip_media only; no path deletes rides/trips/photos/trip-map regions).
Ruling: navigation-only (no recording) does not block video jobs — spec §3.2 blocks only recording; changing it needs new copy/spec change — cost if wrong: video CPU during guidance-only rides; surfaced to user.
Ruling: deleting the trip mid-job keeps the finished video in the gallery and shows it as Done (no trip_media row) — cost if wrong: an orphan gallery file the user can delete.
Ruling: process-restore relaunch skips unfinished-job cleanup until the next cold start — cost if wrong: an IS_PENDING item lingers a little longer.
Final fix wave dispatched (one fixer, FIX-NOW 1–5).
Final fix wave: d743389 (yield via waiter count; JVM app 786; S22 MapLibreOfflineControllerTest 12/12, VideoJobDeviceTest 2/2, VideoFlowDeviceTest 2/2); scoped re-review running
Final re-review: all FIX-NOW addressed, no new Critical/Important. Residual Minor: orphan KDoc in VideoViewModel.kt:262-272 (controller will move it after the device run).
Ruling: when a video finishes during a recording, the only path to the preview is the result notification, which can be skipped if acknowledge() wins the race with the service mirror — parked (rare; file is in gallery and media row) — cost if wrong: user opens it from the trip detail instead.
Full S22 device run: 44 classes; 43 pass first time; SearchScreenTest keyboard test flaky (2 fail, then 3 pass; passes at base d4ee71e) — unrelated. KDoc fixed + doc updated bbfe8c0.
