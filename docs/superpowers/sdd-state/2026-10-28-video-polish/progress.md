# SDD ledger — plan: docs/superpowers/plans/2026-10-28-video-polish.md
Spec: docs/superpowers/specs/2026-10-28-video-polish-design.md (approved). Branch feature/video-polish-v1.3.1 from 24a6a70; plan commit 83b1873.

## Preflight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1↔T4 | CameraPath constants, VideoTimeline OUTRO_RISE_FRAMES/OUTRO_CARD_FROM/OUTRO_CARD_FRAMES → FrameRenderer | FrameRenderer.kt:267 uses VideoTimeline.OUTRO_FRAMES (kept, 96) — fine; T4 switches card fade to OUTRO_CARD_* |
| T1 self | removes ZOOM | CameraPath.overview caps single-point zoom at ZOOM (CameraPathTest:180-181) → Ruling P1 |
| T2↔T4 | RideTitle.base, ElevationGraph → VideoInputs loader, HUD | consistent |
| T2↔T6/T7 | RideTitle.shortRegion/withRegion → job; satelliteMb → VM | consistent |
| T3↔T4/T5/T6 | SatelliteTileSource, VideoMapStyle.ATTRIBUTION, SatelliteProbe | consistent; T6 wraps probe as SatelliteCheck incl. NO_KEY |
| T3↔T7 | NetworkWaiter.isCellular → VM | consistent |
| T3 self | verPatch=1 | SettingsScreenTest.kt:109 asserts "V1.3.0" → Ruling P2 |
| T4↔T5 | RouteStyle/FrameStyle, VideoInputs.rideTitle → SummaryImageRenderer; T5 deletes badge | T4 must stop using inputs.badge in FrameRenderer; T5 deletes field — consistent |
| T6↔T7 | Done.satelliteFallback, EXTRA_PREVIEW_SATELLITE_FALLBACK → LaunchRequest/VideoOutcome/MainActivity | consistent |
| T7 self | ffmpeg frame extraction | host-side step after adb pull — fine |
| T8 | docs only | fine |
Ruling P1: CameraPath.overview's single-point/tiny cap becomes ZOOM_SLOW (17.4) — spec silent; closest equivalent of old ZOOM — cost if wrong: a tiny route's overview 0.2 zoom closer.
Ruling P2: Task 3 updates SettingsScreenTest's "V1.3.0" to "V1.3.1" and runs it on the S22 — version bump lives in Task 3 — cost if wrong: none.
Task 1 base: 83b1873 — implementer dispatched (opus)
Task 1: implemented c475ced — core 314 pass; review running
Ruling C1: CameraPath.cameras gains trailing `heightPx: Int = 1920`; Task 4 device tests and Task 6 VideoJob MUST pass the real frame height (sink.height, incl. 720 fallback and landscape) — cost if wrong: marker below 2/3 on landscape/720p.
Task 1: complete (commits 83b1873..c475ced, review approved)
Ruling C2: the reviewer's Important (androidTest FrameRendererTest.reusedFramesTakeOneSnapshot breaks on the new reuseMap; FrameRendererTest:44 cameras must pass H) is carried into Task 4, which rewrites FrameRendererTest anyway — cost if wrong: one broken device test until Task 4.
Task 1: minor (deferred): no test for a trailing photo before the outro, or for back-to-back same-place photo orbits; the outro test cannot tell smoothstep from linear; the sway period has no independent check (back to 0 after 16 s); a stray assertion in the outro test; JUMP_M per frame may disable smoothing on very long multi-day trips in short videos (check in Task 7); after Task 6, remove the heightPx default so callers must pass it.
Task 2: implemented fed0430 — core 328 pass; review running
Task 2: complete (commits c475ced..fed0430, review approved)
Ruling V2-1: the elevation graph's vertical range is the data range max(actual, 40 m) plus a 4 m floor margin below it (total = range + 4), so the top is never clipped — this is the reviewer-endorsed reading of spec §3.4 — cost if wrong: the graph is 4/44 shorter on flat rides.
Task 2: minor (deferred): unreachable coerceIn in ElevationGraph ys; no test for a missing first-point altitude (xs[0] > 0); near-duplicate test setup; satelliteMb floating point may be 1 MB off at boundaries.
Task 3 base: fed0430 — implementer dispatched (opus)
Task 3: implemented 0749637 — JVM app 799/core 328; S22 SatelliteMapTest 3/3 (bright-grey plain 0.493 vs satellite 0.018), SettingsScreenTest 11/11; review running. Controller look: satellite frame looks good; road labels look small (~12 px?) vs the intended size 20 — reviewer to check.
Ruling K1: app code that logs map/snapshot errors (MapSnapshots warn, probe, job) must redact the VWorld key (replace with "***") before logging; MapLibre native's own logcat lines cannot be controlled and are accepted (local device log only) — cost if wrong: key in local logcat.
Task 3: complete (commits fed0430..0749637, review approved)
Ruling L1: road labels read small at a 60° tilt (map-aligned line text is squashed by perspective). Task 4 sets v-road text-size 24 and v-place "text-pitch-alignment": "viewport" (road keeps line placement, adding viewport pitch alignment) and updates VideoMapStyleTest — this serves spec §1.1's intent (readable, upright labels about 1.6×) over the plan's fixed 20 — cost if wrong: slightly larger road names.
Task 3: minor (deferred): the probe's image/ check is case-sensitive; no test asserts symbol-placement line; SatelliteMapTest leaves PNGs in the external files dir; isCellular has no test (Task 7 covers it); an empty key yields a broken URL (callers must branch on a blank key — Task 6 NO_KEY).
Task 4 base: 0749637 — implementer dispatched (opus)
Task 4: implemented 0a67cd7 — JVM 805; S22 FrameRendererTest 20/20, SummaryImageRendererTest 4/4, SatelliteMapTest 3/3; review running. Controller look: frames look good; outro attribution pill overlaps a map label at the top right; the intro overview is label-busy.
Task 4: review → Needs fixes (Important: outro attribution pill over labels; intro label clutter; landscape HUD band 37% collides with marker/tail). Deviations ①–⑦ accepted.
Ruling F1: the outro attribution moves into the card footer and the pill alpha is about 200 elsewhere; label density via minzoom (suburb ≥ 13, quarter/neighbourhood ≥ 15, roads ≥ 15) and the title shade at 0.6 — serves spec §0's "not a map-app recording" — cost if wrong: fewer labels on the overview.
Ruling F2: the landscape HUD becomes one row (numbers about 60% | graph about 40%), and the route fades out under the HUD band in both orientations — fixes the collision without moving the camera (Task 1's marker maths stays) — cost if wrong: the route is invisible under the band.
Task 4: fix round 1 dispatched (+ card units, landscape 3×2 card, saveLayer bounds, pinning tests, a C2 exact count, no PNGs left on the device).
Task 4: minor (deferred): no blur cost measurement per frame; Paint/gradient reallocated per frame; K1 misses a key only in an exception cause; "주행 N회" duplicated with the summary image (Task 5 should merge).
Task 4: fix round 1/5 implemented 0457fb1 — JVM app 806; S22 FrameRendererTest 24/24, SatelliteMapTest 3/3, SummaryImageRendererTest 4/4; re-review running
Ruling F3: in landscape the intro date line overlaps the route start. Fix in Task 4 round 2: add FrameRenderer.introCamera(inputs, w, h) = the CameraPath.outro fit to the box above the title block's top, minus 1 zoom (spec §2.2 "overview − 1"); Task 6 passes it as `overview` — the same pattern as outroCamera — cost if wrong: the intro route sits a little higher than centre.
Task 4: fix round 1/5 (3 addressed, 0 open; commits 0a67cd7..0457fb1). Fix round 2 dispatched for ruling F3 (introCamera).
Task 4: fix round 2 implemented 103d203 (introCamera; S22 FrameRendererTest 25/25). Deviation accepted: introCamera does not subtract 1 because cameras() already applies overview − 1.
Ruling F4: no turn during INTRO_OVERVIEW (bearing 0 while the title shows). INTRO_DESCENT turns from 0 to the first heading the shortest way with smoothstep. This replaces spec §2.2's overview turn of north → first−90° and descent turn of first−90 → first, because turning about the screen centre swings the off-centre route over the title — cost if wrong: less spin when the first heading is near north.
Task 4: fix round 3 implemented 208358e (F4; core 329, app 806, S22 FrameRendererTest 25/25); re-review of rounds 2–3 running
Task 4: fix rounds 2–3/5 (F3, F4 addressed, 0 open; commits 0457fb1..208358e)
Task 4: complete (commits 0749637..208358e, review clean after 3 rounds)
Task 4: minor (deferred): the outroCamera KDoc is orphaned above introCamera (FrameRenderer.kt:524-528); the title KDoc wording is stale; the plan's introLooksDownThenSpiralsIn text is outdated (F4); a fit zoom < 1 edge case; the route and glow are drawn twice per ride frame because of the fade (check sec/frame in Task 7).
Task 5 base: 208358e — implementer dispatched (opus)
Task 5: implemented f055ac2 — S22 SummaryImageRendererTest 7/7, FrameRendererTest 24/24; JVM 806; review running
Task 5: review → Approved with 1 Important (landscape attribution room = width/2 can overflow the white column with the long plain attribution). Fix round 1 dispatched (+ segmentColors removal, ATTRIBUTION_GREY #757575, units at 60% in the image cells).
Ruling S1: the summary image attribution sits at the top right of the white header rather than as a pill on the map — spec §1.1 says only "top right, small", and a pill overlapped place names (same reason as F1) — cost if wrong: none.
Task 5: minor (deferred): the glow test threshold is loose; fill() is duplicated three times; stats() depends on the FrameRenderer companion; ascentCell has no KDoc.
Task 5: fix round 1 implemented f395a56 (S22 SummaryImageRendererTest 8/8, FrameRendererTest 24/24); re-review running
Task 5: fix round 1/5 (4 addressed, 0 open; commits f055ac2..f395a56)
Task 5: complete (commits 208358e..f395a56, review clean)
Task 6 base: f395a56 — implementer dispatched (opus)
Task 6: implemented e4dde4f — JVM core 329/app 819; S22 VideoJobDeviceTest 4/4 (wrong key → fallback; Kakao region 서울특별시 영등포구), FrameRendererTest 24; measured about 26 KB/frame (short video about 19 MB); review running
Task 6: complete (commits f395a56..e4dde4f, review approved)
Ruling J1: offline with cached trip maps → the probe fails fast → plain map with the "위성 지도를 받지 못해…" notice. Spec §1.3 has no row for this case; the notice is factual because satellite tiles are never cached — cost if wrong: a notice the user may find redundant offline.
Task 6: minor (deferred, final-wave candidates): MapSnapshots and VideoMapStyle redaction duplicated, and the MapSnapshots copy misses the cause chain (merge into redacting); re-check recording right before launch, since the probe can take up to 10 s (return RECORDING cleanly); the video scope's handler Log.e is unredacted (pre-existing); the fake backend's default job builds an unused job; camerasAt is a mirror test with no landscape case.
Task 7 base: e4dde4f — implementer dispatched (opus)
Task 7: implemented 8d3202e — JVM core 329/app 822; S22 brief classes + regressions pass; real app-flow video 74.2 s, about 23.9 MB satellite data, ffprobe 1080x1920 24fps 30.000s 720 frames; marker at 0.67 height; title '아침 라이딩 · 서울 영등포구'; review running. Controller look: the photo-scene caption '오전 9:02' overlaps the route tail and marker.
Task 7: complete (commits e4dde4f..8d3202e, review approved)
Ruling FW1: the photo-scene caption over the cut route tail (Important, Task 4/5 FrameRenderer.drawPhoto) goes into the final fix wave: a dark rounded pill behind the caption (alpha 150 × photoAlpha, padding 14u × 7u), route overlay alpha 1 − 0.6·photoAlpha during photo scenes, plus a test on a bright map — cost if wrong: one more fix commit.
Ruling FW2: the final wave lowers VideoJob's DEFAULT_SECONDS_PER_FRAME from 0.25 to 0.15, because the S22 measured about 0.10 s/frame twice (74 s / 720) and the user was told "약 3분" for a 74 s job. This deviates from V1.3.0 spec §3.6's 0.25; it is still conservative, and the estimate self-corrects after the first video — cost if wrong: a slow phone's first estimate is low.
Task 7: minor (deferred): the cellular notice is read once per sheet; a layout jump before the first estimate; no nav-arg/extra round-trip test; low contrast of the grey route ahead on bright land.
Task 8 base: 8d3202e — implementer dispatched (sonnet)
Task 8: complete (2460dc2, controller-checked docs; no key values anywhere in branch history)
Final review dispatched on 24a6a70..2460dc2 (opus)
Final review: merge after FIX-NOW 1–8 (no Critical). Important: FW1 caption, I2 JUMP_M per-frame on long trips disables smoothing, I3 a cache-hit bytes/frame understates the cellular notice, FW2, I5 MapSnapshots redaction misses the cause.
Ruling J2: a jump = straight-line distance > JUMP_M + route distance travelled between the two frames (gaps between segments count 0), so long continuous rides keep smoothing while day/segment jumps still reset — cost if wrong: a rare GPS-gap glitch is smoothed instead of cut.
Ruling J3: bytes/frame below 8,000 is not recorded (likely a cache hit), so the default or previous value stays — cost if wrong: a genuinely tiny-data area keeps a higher estimate.
Final fix wave dispatched (one fixer, FIX-NOW 1–8).
Final fix wave: d8c0122 (core 330/app 825; S22 FrameRendererTest 26/26, VideoJobDeviceTest 4/4, SatelliteMapTest 3/3, SummaryImageRendererTest 8); re-review running
Final re-review: all FIX-NOW 1–8 addressed, no new Critical/Important. Out of scope (left): the core VideoEstimate.minutes default argument is still 0.25 (the app always passes 0.15); copyRedacted recursion on a cyclic cause; the field-test JVM counts (to update after the full run).
Full S22 device run: 45 classes PASS (286 tests: 284 pass + 2 screenshot tests skipped); docs 14fa4cb
