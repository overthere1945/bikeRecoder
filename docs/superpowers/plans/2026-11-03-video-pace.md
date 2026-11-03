# 영상 다듬기 2 — 박진감 (V1.11.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Videos get their length from the distance, pass straight roads fast and slow down at curves, photos and highlights, run at 30 fps under a higher, slowly orbiting camera, and snap scene changes to the beat of the music (three bundled bright tracks).

**Architecture:** All timing is pure Kotlin in `core/video`: `VideoPace` turns a `RideTimeline` into a distance-over-time curve, `VideoTimeline` lays frames on it (and onto a `Beats` grid when music has one), `BeatFinder` finds the grid from an energy envelope, `CameraPath` follows the frames. The app only decodes the music to an envelope before planning (`AudioTrackMaker.analyze`), starts the music at the planned offset, renders, and offers the bundled tracks in the options sheet.

**Tech Stack:** Kotlin, Jetpack Compose M3, Android Canvas, MapLibre `MapSnapshotter`, MediaExtractor/MediaCodec (existing), JUnit 5 (JVM), AndroidX test + Compose UI test (S22).

**Spec:** `docs/superpowers/specs/2026-11-03-video-pace-design.md` (amended while planning, see "Spec amendments" below).

## Global Constraints

- Branch `feature/video-polish-v1.11.0`; version 1.11.0, versionCode 11100, debug `1.11.0-dev`, settings `V1.11.0`.
- `FPS = 30`. Scene lengths in seconds: sky 1.2, descent 1.8, photo 2.0, outro 4.0 (rise 1.5, card from 1.2 over 0.25, fade last 1.0), day caption 1.5. Frames = `(seconds × FPS).roundToInt()`.
- Ride length `R0 = 6 + 5·√km` s (`< 0.05 km` → 4 s). `R = min(R0, max length − 3 − 4 − 2 × photos, moving seconds)`, at least `min(4, moving seconds)`.
- Photos in a video = `min(length cap 4/8/15, max(1, ⌊R0 / 4⌋), available)`.
- Camera zoom ranges: bike 14.8–16.8, walk/run 15.6–17.6, hike 15.2–17.4. Target 350 px/s on the frame's short side scaled to 1080. Tilt 55°–60°, max 60°.
- All user-facing text is Korean, verbatim: `최대 30초`, `최대 1분`, `최대 2분`, `없음`, `햇살 페달`, `바람길`, `언덕 너머`, `파일에서 고르기`; settings attribution `기본 배경음악: bikeRecoder가 직접 만든 곡`. Existing strings (`음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?` …) unchanged.
- No DB change (Room stays 9). No new permission. No network for music.
- Device tests run only on S22 `R5CT43TFS9A`, one class per run (`ANDROID_SERIAL=R5CT43TFS9A`, `</dev/null`); the controller re-seeds the S22 afterwards. Never run connected tests on the S25.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Match surrounding code: core KDoc in Korean citing "스펙 §N" (this spec) or "다듬기 설계 §N" (V1.3.1); app KDoc in English citing "spec §N".

## Spec amendments (made while planning; already written into the spec, marked "(계획에서 고침)")

1. **§1.1 ride length never exceeds the moving time** (the current "늘이지 않음" rule, test `shortMoveIsNotStretched`): `R = min(R0, available, moving seconds)`, at least `min(4, moving seconds)`.
2. **§2.2 highlights come from the timeline**, not `segment_extras`: `VideoInputs` carries no extras, so `VideoHighlights` finds the fastest 10 s, the tops of climbs (≥ 30 m from the last low) and the highest point from `RideTimeline.points`.
3. **§2.4 interface:** `PaceInput` folds into `VideoPace.profile(timeline, seconds, photoAlongM, highlightAlongM)`.
4. **§3 frame plans carry their own scene frame counts** (`sceneFrame`, `sceneFrames`, `cardFrom`), because with music the photo and outro lengths change (5); the renderer stops reading `VideoTimeline.*_FRAMES` for them.
5. **§5.3 a 2 s photo cannot start and end on beats unless 2 s is whole beats.** With beats: the photo lasts the whole number of beats nearest 2.0 s (at least 3 beats); the outro lasts the whole number of bars nearest 4.0 s (at least 3.0 s), so the video ends on a bar end; the card appears on the beat nearest 1.2 s after the outro start. Ride start (3 s) is put on a bar by choosing the music offset. Only photo starts and the outro start move the ride (each by at most half a beat, no ride piece below 70 %).
6. **§6.2 retry keeps the bundled track:** the unfinished-job retry drops a picked file (as now) but keeps a bundled track.

## Review Focus

1. A multi-day trip with gaps between segments: the gap is 0 m, the pace spends no time there, distance never goes back, and the "2일째" caption starts on the first frame at or past that day's start distance. (Task 3 test `multiDayCaptionAtDayStartAlong`.)
2. A walk with long stops and GPS jitter in one place: the pace is not stuck on the jitter (distance-based), and the ride is not longer than the moving time. (Task 2 test `jitterInPlaceGetsNoTime`, Task 3 test `shortMoveIsNotStretched`.)
3. A picked music file shorter than the video, with a clear beat: planning snaps as usual, the audio simply ends early with its own fade. (Task 5 test `shortMusicStillSnaps`.)
4. Several photos at the same place (one along value) or taken during a pause: they stay one group in time order and are shown back to back. (Task 3 test `photosAtSameAlongStayGrouped`.)
5. Landscape and the 720p encoder fallback: the camera height uses the frame's short side, so landscape is not zoomed out by 16/9. (Task 6 test `landscapeUsesShortSide`.)

---

## File Structure

**core** (`core/src/main/kotlin/com/cowork/bikerecoder/core/…`)
- `video/VideoOptions.kt` — `FPS = 30`, `framesOf(seconds)`, `BuiltInMusic`, `VideoOptions.builtInMusic`.
- `video/VideoTimeline.kt` — seconds constants, `FramePlan` fields, distance-paced `plan`, `planToBeats`.
- `video/VideoPace.kt` (new) — `VideoPace`, `PaceProfile`.
- `video/VideoHighlights.kt` (new) — highlight distances from a timeline.
- `video/BeatFinder.kt` (new) — `Beats`, `BeatFinder`.
- `video/Hud.kt` — `RouteDistance.total`, `RouteDistance.moveAt`.
- `video/AudioPlan.kt` — start offset.
- `video/CameraPath.kt` — new following camera.
- `activity/ActivityProfile.kt` — `cameraZoomMin`/`cameraZoomMax` replace `cameraSlowKmh`/`cameraFastKmh`.

**app** (`app/src/main/java/com/cowork/bikerecoder/…`)
- `video/FrameStyle.kt`, `video/FrameRenderer.kt` — seconds-based fades, scene frames from the plan.
- `video/AudioTrackMaker.kt` — `analyze(uri)`, `make(uri, durationUs, startUs)`.
- `video/VideoBackend.kt`, `video/VideoJob.kt` — analyse → plan → render → music.
- `video/VideoJobStore.kt` — `builtInMusic` in the options JSON.
- `ui/video/VideoViewModel.kt`, `ui/video/VideoOptionsSheet.kt` — length copy, music tiles, defaults.
- `ui/settings/SettingsScreen.kt` — attribution line.
- `res/raw/music_sunny_pedal.m4a`, `music_wind_road.m4a`, `music_over_the_hill.m4a` — moved from `tools/music/tracks/`.

---

### Task 1: 30 fps and scene lengths in seconds

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoOptions.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoTimeline.kt`
- Modify: `app/src/main/java/com/cowork/bikerecoder/video/FrameStyle.kt`, `app/src/main/java/com/cowork/bikerecoder/video/FrameRenderer.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/VideoTimelineTest.kt`, `VideoOptionsTest.kt`, `VideoEstimateTest.kt`; `app/src/androidTest/java/com/cowork/bikerecoder/video/FrameRendererTest.kt`

**Interfaces:**
- Produces: `const val FPS = 30`; `fun framesOf(seconds: Double): Int` (in `VideoOptions.kt`); in `VideoTimeline`: `OVERVIEW_SECONDS = 1.2`, `DESCENT_SECONDS = 1.8`, `PHOTO_SECONDS = 2.0`, `OUTRO_SECONDS = 4.0`, `OUTRO_RISE_SECONDS = 1.5`, `OUTRO_CARD_FROM_SECONDS = 1.2`, `OUTRO_CARD_SECONDS = 0.25`, `FADE_SECONDS = 1.0`, `CAPTION_SECONDS = 1.5`, and the existing `*_FRAMES` names kept as `val … = framesOf(…)` (36, 54, 60, 120, 45, 36, 8, 30, 45).
- Produces: `FramePlan` gains `val sceneFrame: Int` (0-based frame inside its scene) and `val sceneFrames: Int` (that scene's frame count) and `val cardFrom: Int = VideoTimeline.OUTRO_CARD_FROM` (outro frame where the card starts; used only on OUTRO). `sceneProgress` stays (= `sceneFrame / (sceneFrames − 1)`).

- [ ] **Step 1: Update the tests to 30 fps.** In `VideoTimelineTest` replace every `24` frame factor with 30 and photo `60` frames with `2.0 s × 30 = 60` (unchanged count, now 2 s): e.g. `frameCountPerLength` asserts `60 * 30`, intro `90` = `36 + 54`, outro `120`; `photosShortenRide` asserts ride `(60 − 7 − 8 × 2) * 30` = `37 * 30` and photos `8 * 60`. Add:

```kotlin
@Test
fun plansCarryTheirSceneFrames() {
    val plans = VideoTimeline.plan(simpleTimeline(600), emptyList(), VideoOptions(), emptyList())
    val outro = plans.filter { it.kind == SceneKind.OUTRO }
    assertEquals((0 until 120).toList(), outro.map { it.sceneFrame })
    assertTrue(outro.all { it.sceneFrames == 120 && it.cardFrom == 36 })
    assertEquals(54, plans.first { it.kind == SceneKind.INTRO_DESCENT }.sceneFrames)
}
```
In `VideoOptionsTest` add `assertEquals(30, FPS)` and `assertEquals(8, framesOf(0.25))`. Fix `VideoEstimateTest` frame counts the same way.

- [ ] **Step 2: Run** `./gradlew :core:test --tests '*VideoTimelineTest' --tests '*VideoOptionsTest' --tests '*VideoEstimateTest' </dev/null` — expected FAIL (24 fps, missing fields).

- [ ] **Step 3: Implement.** Constants and `FramePlan` fields as in Interfaces; `plan` fills `sceneFrame`/`sceneFrames` from its `emit(k, of)`. `FrameStyle`: `TITLE_FADE_FRAMES = 4`, `PHOTO_IN_FRAMES = 7`, `PHOTO_OUT_FRAMES = 7` become `TITLE_FADE_SECONDS = 1.0 / 6`, `PHOTO_IN_SECONDS = 7.0 / 24`, `PHOTO_OUT_SECONDS = 7.0 / 24` (same time as now), used through `framesOf`. `FrameRenderer`: `frameOf(plan, VideoTimeline.X_FRAMES)` → `plan.sceneFrame`, the scene totals → `plan.sceneFrames`, the card start → `plan.cardFrom`; the pulse already uses `FPS` (keep). The music length in `VideoJob.startVideo` already divides by `FPS` (keep).

- [ ] **Step 4: Run** the core tests again (PASS), then `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin </dev/null` — PASS (fix `VideoJobTest`/`VideoViewModelTest` frame-count expectations the same way if they assert 24).

- [ ] **Step 5: Device test** `FrameRendererTest` on the S22 (one class): update its frame indices that assumed 24 fps (e.g. "frame 29 of the outro") to the new counts; PASS.

- [ ] **Step 6: Commit** `feat(video): 30 fps with scene lengths in seconds`.

---

### Task 2: The pace curve `VideoPace`

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoPace.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoHighlights.kt`
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/Hud.kt` (`RouteDistance`)
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/VideoPaceTest.kt`, `VideoHighlightsTest.kt` (use `VideoFixtures.kt` builders: `straightPoints`, `segment`, `simpleTimeline`)

**Interfaces:**
- Produces in `RouteDistance`: `val total: Double`; `fun moveAt(alongM: Double): Long` — the first move time at which the distance reaches `alongM` (interpolated inside a step; clamped to `[0, total]`; a zero-length step returns its first point's `moveMs`).
- Produces `internal object VideoHighlights { fun alongM(timeline: RideTimeline): List<Double> }` — sorted distances of: the middle of the fastest 10 s of moving time (needs ≥ 10 s), each climb top whose rise from the lowest point since the previous top is ≥ 30 m (altitude through `AscentCounter`'s 3 m threshold), the highest point. Points without altitude are skipped; no altitude → only the fastest stretch.
- Produces:

```kotlin
object VideoPace {
    const val MIN_RIDE_SECONDS = 4.0
    fun rideSeconds(distanceKm: Double): Double
    fun profile(timeline: RideTimeline, seconds: Double, photoAlongM: List<Double>, highlightAlongM: List<Double>): PaceProfile
}
class PaceProfile {
    val seconds: Double
    val totalM: Double
    fun alongAt(second: Double): Double        // clamped; 0 → 0, seconds → totalM; never decreases
    fun secondAt(alongM: Double): Double       // inverse
    fun speedFactor(second: Double): Double     // (m/s at that second) / (totalM / seconds)
    fun warped(anchors: List<Pair<Double, Double>>): PaceProfile
}
```
`warped`: anchors are `(oldSecond, newSecond)` pairs, strictly increasing in both, `(0, 0)` implied; the last anchor's old value must be `seconds` and its new value is the warped profile's `seconds`. Time between anchors is remapped linearly; `alongAt(new)` = old `alongAt(mapped back)`.

Algorithm for `profile` (spec §2.2–2.3, values verbatim): bins `N = clamp(totalM / 5, 60, 300)` of equal distance; per bin score = max of applicable {1.0; curve `1.0 + 0.8 × min(θ / 90°, 1)` with θ the bearing change over ±50 m; 2.0 within 150 m before a photo; 1.6 within ±100 m of a highlight}; 0.6 for a straight bin only when no other condition (straight = bearing changes ≤ 20° over ±150 m, or ±totalM/20 if shorter). Bearings between bin edges; edges closer than 15 m reuse the previous bearing. Bin time ∝ score × bin length; Gaussian smoothing σ = 0.35 s; clamp max/min speed to 4 (raise the slow side); smoothstep ease over the first and last 1.5 s (`R/3` each when `R < 4`); scale to `seconds`. Totals below 50 m: uniform.

- [ ] **Step 1: Write failing tests** (`VideoPaceTest`):

```kotlin
@Test fun rideSecondsFollowsTheSquareRoot() {
    assertEquals(11.0, VideoPace.rideSeconds(1.0), 0.05)
    assertEquals(21.8, VideoPace.rideSeconds(10.0), 0.1)
    assertEquals(41.4, VideoPace.rideSeconds(50.0), 0.1)
    assertEquals(4.0, VideoPace.rideSeconds(0.01), 1e-9)
}
@Test fun alongStartsAtZeroEndsAtTotalAndNeverDecreases()      // straight 2 km, 15 s: alongAt(0)=0, alongAt(15)=total, 300 samples non-decreasing
@Test fun speedRatioAtMostFour()                               // L-shaped 3 km with photos: max/min speedFactor over the middle ≤ 4.0 + 1e-6
@Test fun straightIsFasterThanACurve()                         // 1 km straight then a 1 km zig-zag (90° every 100 m): mean speed on straight ≥ 1.5 × on zig-zag
@Test fun slowsBeforeAPhoto()                                  // straight 2 km, photo at 1,000 m: mean speedFactor over 860..990 m ≤ 0.6 × over 300..600 m
@Test fun easesInAndOut()                                      // speedFactor at 0.2 s and seconds − 0.2 s < 0.5 × at seconds / 2
@Test fun warpPutsAnchorsExactly()                             // warped(listOf(5.0 to 5.3, 15.0 to 15.0)): secondAt(oldAlongAt(5.0)) == 5.3 ±1e-6, alongAt non-decreasing
@Test fun jitterInPlaceGetsNoTime()                            // 1 km straight, then 200 points jittering ±3 m in place over 10 min, then 1 km straight: time spent while along is within the jitter < 0.3 s
```
`VideoHighlightsTest`: `fastestStretchFound` (slow 1 m/s then 30 s at 8 m/s: the highlight lies inside the fast part), `climbTopFound` (up 40 m then down: one highlight at the top ±20 m), `smallClimbIgnored` (up 20 m: no climb top, only highest point and fastest), `noAltitudeOnlyFastest`. In `HudTest` (or a new `RouteDistanceTest`): `moveAtInvertsAtMove` (for 20 distances along a two-segment timeline, `atMove(moveAt(d)) == d ± 0.5 m`; a gap between segments maps to the first point of the next segment).

- [ ] **Step 2: Run** `./gradlew :core:test --tests '*VideoPaceTest' --tests '*VideoHighlightsTest' --tests '*HudTest' </dev/null` — FAIL (unresolved).
- [ ] **Step 3: Implement** `RouteDistance.total/moveAt`, `VideoHighlights`, `VideoPace`, `PaceProfile` (store the curve as cumulative seconds per bin edge; interpolate linearly).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(video): distance pace curve with highlight scores`.

---

### Task 3: Frames laid on the pace

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoTimeline.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/VideoTimelineTest.kt`

**Interfaces:**
- Consumes: `VideoPace`, `PaceProfile`, `VideoHighlights.alongM`, `RouteDistance.moveAt/total` (Task 2); `FramePlan` scene fields (Task 1).
- Produces: `FramePlan` gains `val alongM: Double = 0.0` (route distance of the frame) and `val speed: Float = 1f` (`PaceProfile.speedFactor` on RIDE frames, 1 elsewhere). `VideoTimeline.plan(timeline, photos, options, dayStarts)` keeps its signature. `choosePhotos(cues: List<PhotoCue>, cap: Int, totalM: Double, alongOf: (PhotoCue) -> Double): List<PhotoCue>` replaces the move-time version (same bin rule on the distance axis). `internal fun paceFor(timeline, photos, options): Pair<PaceProfile, List<PhotoCue>>` (used by Task 5).

Rules: `R0 = VideoPace.rideSeconds(total / 1000)`; photos = `min(cap, max(1, ⌊R0 / 4⌋), cues)` chosen by distance; `R` per Global Constraints; ride frames `n = framesOf(R)`, frame k at `second = k / (n − 1) × R` (`n == 1` → 0); `alongM = pace.alongAt(second)`, `moveMs = route.moveAt(alongM)`; a photo group (±1 s move time, as now) is emitted when the ride frame's along first reaches the group's along; a day caption when the along first reaches `route.atMove(dayStartMoveMs)`, for `CAPTION_FRAMES`.

- [ ] **Step 1: Rewrite the time-based tests for the distance axis.** `speedProportional` is replaced by `paceIsByDistanceNotTime` (slow 600 m then fast 3 km: the first 600 m take about 600/3600 of the ride frames ± 5 %). `frameCountPerLength` → `lengthFollowsDistance`: a 1 km, 10 min trip makes `90 + framesOf(11.0) + 120` frames with `VideoOptions()` (NORMAL) and the same with LONG; a 50 km trip with SHORT is capped at `30 * 30` frames. `photosShortenRide` → `photoCountFollowsRideLength`: 1 km with 5 photos and NORMAL → 2 photo scenes (`2 * 60` PHOTO frames). Keep `shortMoveIsNotStretched` (a 20 s, 100 m trip: ride frames = `framesOf(min(R0, 20.0))`). Add:

```kotlin
@Test fun multiDayCaptionAtDayStartAlong()      // two segments on two days with a 5 km jump: first caption frame's alongM is the first ≥ day-2 start along; alongM never decreases across the jump
@Test fun photosAtSameAlongStayGrouped()         // 3 photos within 1 s of move time at one spot: 3 × 60 PHOTO frames back to back, takenAt order
@Test fun rideFramesCarryAlongAndSpeed()         // RIDE frames: alongM from 0 to total, speed > 0; non-RIDE speed == 1f
@Test fun neverLongerThanMaxLength()             // for SHORT/NORMAL/LONG and 0.5/5/50/300 km with 0/20 photos: size ≤ seconds × 30
```

- [ ] **Step 2: Run** `./gradlew :core:test --tests '*VideoTimelineTest' </dev/null` — FAIL.
- [ ] **Step 3: Implement** per the rules above.
- [ ] **Step 4: Run** `./gradlew :core:test :app:testDebugUnitTest </dev/null` — PASS (`VideoViewModelTest` estimates change with the new frame counts: update expected minutes from the new plan, not by hand).
- [ ] **Step 5: Commit** `feat(video): lay ride frames on the distance pace`.

---

### Task 4: `BeatFinder`

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/BeatFinder.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/BeatFinderTest.kt`

**Interfaces:**
- Produces:

```kotlin
/** 박자(스펙 §5.2). [startUs] = 첫 박(음악 파일 시각), [beatUs] = 박 간격. 마디 = 4박, [startUs]부터. */
data class Beats(val startUs: Long, val beatUs: Long) {
    val barUs: Long get() = beatUs * 4
}
object BeatFinder {
    const val HOP = 512
    const val WINDOW = 1024
    const val SAMPLE_RATE = 44_100
    /** [energy] = 창 [WINDOW]·간격 [HOP]의 RMS(0~1, 모노). 박이 뚜렷하지 않으면 null. */
    fun find(energy: FloatArray): Beats?
    /** 앞에서부터 처음으로 −40 dBFS를 넘는 창의 시각(µs). 없으면 0. */
    fun firstSoundUs(energy: FloatArray): Long
}
```
Method (spec §5.2): onset = positive part of the log-energy difference; tempo = the autocorrelation peak over 70–180 BPM, preferring 90–160 when the double/half also peaks within 90 %; confidence = peak / mean over 70–180, `< 2.0` → null; phase = the comb offset with the largest onset sum; `startUs` = the first beat at or after `firstSoundUs`.

- [ ] **Step 1: Failing tests.** A helper `clicks(bpm, seconds, silenceS, noise)` builds the energy array directly (a 10 ms burst of 0.8 every beat after `silenceS`, plus seeded uniform noise of amplitude `noise`):

```kotlin
@Test fun findsTempoAndPhase() {
    for (bpm in listOf(90, 124, 150)) {
        val b = BeatFinder.find(clicks(bpm, 40.0, silenceS = 2.0, noise = 0.05))!!
        assertEquals(60_000_000.0 / bpm, b.beatUs.toDouble(), 60_000_000.0 / bpm - 60_000_000.0 / (bpm + 1))
        assertEquals(2_000_000.0, b.startUs.toDouble(), 20_000.0)
    }
}
@Test fun noiseOnlyHasNoBeat()          // seeded noise 0.3, 40 s → null
@Test fun slowTempoDoubled()            // 62 BPM clicks → beat of 124 BPM ±1
@Test fun firstSoundSkipsSilence()      // 3 s of 0.001 then clicks → firstSoundUs ≈ 3 s ±12 ms
```
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*BeatFinderTest' </dev/null` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(video): find the beat of the music`.

---

### Task 5: Scenes on the beat

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoTimeline.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/video/AudioPlan.kt`
- Test: `VideoTimelineTest.kt`, `AudioPlanTest.kt`

**Interfaces:**
- Consumes: `Beats` (Task 4), `PaceProfile.warped` (Task 2), `paceFor` (Task 3).
- Produces:

```kotlin
/** 박에 맞춘 계획: [frames]와, 영상 시각 0에 틀 음악 파일 시각 [musicStartUs]. */
data class Planned(val frames: List<FramePlan>, val musicStartUs: Long)
fun VideoTimeline.planToBeats(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions,
                              dayStarts: List<Pair<Long, String>>, beats: Beats?): Planned
```
`beats == null` → `Planned(plan(...), 0)`. Otherwise (spec §5.3 as amended): `musicStartUs` = the smallest `beats.startUs + m·barUs − 3 s` that is `≥ max(0, startUs − barUs / 2)`; photo length = `max(3, round(2.0 s / beat))` beats; outro length = the whole number of bars nearest 4.0 s, at least 3.0 s (`OUTRO_RISE` stays 1.5 s, fade stays the last 1.0 s); `cardFrom` = frames of the beat nearest 1.2 s after the outro start; each photo start moves to the nearest beat and the outro start to the nearest bar by warping the ride piece before it (`PaceProfile.warped`), only when the move is ≤ half a beat and the piece stays ≥ 70 % of its length, else that event keeps its time.
- `AudioPlanner.plan(musicDurationUs: Long, videoDurationUs: Long, startUs: Long = 0)`: take from `startUs` for `min(musicDurationUs − startUs, videoDurationUs)`; fade the last 2 s of what is taken.

- [ ] **Step 1: Failing tests** (video seconds = frame / 30; music time = video time + `musicStartUs`):

```kotlin
@Test fun rideStartsOnABar()                  // 124 BPM beats from 0.4 s: (3 s + musicStartUs − startUs) % barUs < one frame
@Test fun photosStartOnBeatsAndLastWholeBeats()  // 10 km with 4 photos: each PHOTO run starts within 1 frame of a beat and lasts framesOf(4 × beat) (124 BPM: 4 beats)
@Test fun outroOnABarAndEndsOnABar()          // outro start within 1 frame of a bar; total length ends within 1 frame of a bar end
@Test fun cardOnTheBeatNearest1_2s()          // OUTRO cardFrom == framesOf(beat × round(1.2 s / beat))
@Test fun unreachableEventKeepsItsTime()      // a photo 0.3 s after the ride start (piece would drop under 70 %): its start equals the unsnapped plan's
@Test fun noBeatsIsThePlainPlan()             // planToBeats(…, null).frames == plan(…), musicStartUs == 0
@Test fun shortMusicStillSnaps()              // beats from a 20 s track for a 40 s video: still Planned with snapped events
```
`AudioPlanTest`: `startOffsetTakesFromThere` (`plan(60 s, 30 s, 5 s)` → take 30 s from 5 s, fade from 33 s of the file… expressed as the plan's fields), `offsetPastShortMusic` (`plan(20 s, 30 s, 5 s)` → take 15 s).

Represent the offset in `AudioPlan` as `data class AudioPlan(val startUs: Long, val takeUs: Long, val fadeStartUs: Long)` where `takeUs`/`fadeStartUs` are relative to `startUs`.

- [ ] **Step 2: Run** `./gradlew :core:test --tests '*VideoTimelineTest' --tests '*AudioPlanTest' </dev/null` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :core:test </dev/null` — PASS.
- [ ] **Step 5: Commit** `feat(video): snap scene changes to the beat`.

---

### Task 6: The moving camera

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CameraPath.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/activity/ActivityProfile.kt`
- Modify (call site): `app/src/main/java/com/cowork/bikerecoder/video/VideoJob.kt` (pass `widthPx`)
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/CameraPathTest.kt`, `core/src/test/kotlin/com/cowork/bikerecoder/core/activity/ActivityProfileTest.kt`

**Interfaces:**
- Consumes: `FramePlan.alongM`, `FramePlan.speed` (Task 3).
- Produces: `ActivityProfile.cameraZoomMin`/`cameraZoomMax` (bike 14.8/16.8, run and walk 15.6/17.6, hike 15.2/17.4) replacing `cameraSlowKmh`/`cameraFastKmh`. `CameraPath.cameras(plans, timeline, overview, outro, widthPx: Int, heightPx: Int, segmentActivities)` (adds `widthPx`). `fun baseZoom(totalM: Double, rideSeconds: Double, latDeg: Double, shortSidePx: Int, type: ActivityType): Double` (§4.1; `shortSidePx` scales the 350 px target by `shortSidePx / 1080`). Removed: `zoomForSpeed`, `ZOOM_SLOW`, `ZOOM_FAST`, `SLOW_KMH`, `FAST_KMH`, `SPEED_WINDOW_MS`, `ZOOM_RATE_PER_S`, `SWAY_DEG`, `SWAY_PERIOD_S`, `LOOK_AHEAD_M`.
- New constants (spec §4): `ORBIT_DEG = 25.0`, `ORBIT_PERIOD_S = 12.0`, `LOOK_AHEAD_S = 2.0`, `LOOK_AHEAD_MIN_M = 60.0`, `FAST_ZOOM = −0.4`, `SLOW_ZOOM = 0.3`, `FAST_TILT = 55.0`, `TILT = 60.0`, `ZOOM_RATE_PER_S = 0.3`, `TILT_RATE_PER_S = 4.0`, `DESCENT_SPIRAL_DEG = 30.0`, `PHOTO_PUSH_ZOOM = 0.3`, follow factor per frame `1 − 0.85^(30 / FPS)`, `MAX_TURN_DEG_PER_S = 45.0` (kept), `JUMP_M`, `MARKER_FROM_TOP`, `PHOTO_ORBIT_DEG` kept. The dominant type for the range = the type with the longest distance (`segmentActivities` by segment, distance from `RouteDistance`).

- [ ] **Step 1: Failing tests** (`CameraPathTest`, replacing the speed-zoom and sway tests):

```kotlin
@Test fun zoomWithinTheTypeRange()             // every RIDE camera zoom ∈ [min − 0.4, max + 0.3] of its type for a walk and a ride
@Test fun screenSpeedNear350()                 // 2 km walk and 5 km ride, portrait 1080: mean |Δcenter| per second in px at that zoom ∈ 350 ± 30 %
@Test fun shortAndLongAreClamped()             // baseZoom(1 km walk) == 17.6, baseZoom(50 km ride) == 14.8
@Test fun landscapeUsesShortSide()             // baseZoom with shortSidePx 1080 for 1920×1080 equals portrait 1080×1920; 720p fallback (shortSidePx 720) is log2(1080/720) lower ±0.01
@Test fun orbitSwingsTwentyFive()              // long straight north road: bearing − 0 over 24 s spans [−25 − 2, 25 + 2], period ≈ 12 s
@Test fun sameTimeSameBearingAt24And30()       // plans built with FPS-independent helper at 24 and 30 frames/s: bearings at the same ride second differ < 2°
@Test fun zoomAndTiltRateLimited()             // consecutive RIDE frames: |Δzoom| ≤ 0.3 / FPS + 1e-9, |Δtilt| ≤ 4 / FPS + 1e-9
@Test fun fastPartsHigherSlowPartsLower()      // a frame with speed 2.0 has lower zoom and tilt 55 than one with speed 0.5 (tilt 60), after the rate limit settles
```
Keep the existing descent/outro/jump tests (descent first = sky camera, last = first ride camera, J2 jump resets). Add `descentSpirals` (mid-descent bearing differs from the straight interpolation by 30° ± 3°) and `photoPushesIn` (last PHOTO frame zoom = first + 0.3 ± 0.01). `ActivityProfileTest`: the new zoom fields per type.

For `sameTimeSameBearingAt24And30`, make the frame rate a parameter of an internal overload `cameras(…, fps: Int = FPS)` and build plans with frame times scaled accordingly.

- [ ] **Step 2: Run** `./gradlew :core:test --tests '*CameraPathTest' --tests '*ActivityProfileTest' </dev/null` — FAIL.
- [ ] **Step 3: Implement**, then update the `VideoJob` call (`widthPx = w`).
- [ ] **Step 4: Run** `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin </dev/null` — PASS.
- [ ] **Step 5: Commit** `feat(video): a higher, slowly orbiting camera that follows the pace`.

---

### Task 7: Music analysed before planning

**Files:**
- Move: `tools/music/tracks/sunny_pedal.m4a` → `app/src/main/res/raw/music_sunny_pedal.m4a`, `wind_road.m4a` → `music_wind_road.m4a`, `over_the_hill.m4a` → `music_over_the_hill.m4a` (`git mv`; update `tools/music/README.md` paths)
- Modify: `app/src/main/java/com/cowork/bikerecoder/video/AudioTrackMaker.kt`, `video/VideoBackend.kt`, `video/VideoJob.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/video/VideoJobTest.kt`; `app/src/androidTest/java/com/cowork/bikerecoder/video/AudioTrackMakerTest.kt`; create `app/src/androidTest/java/com/cowork/bikerecoder/video/BuiltInMusicBeatsTest.kt`

**Interfaces:**
- Consumes: `BeatFinder`, `Beats` (Task 4); `VideoTimeline.planToBeats`, `Planned`, `AudioPlanner.plan(…, startUs)` (Task 5).
- Produces: `data class MusicAnalysis(val beats: Beats?, val firstSoundUs: Long)`; `AudioTrackMaker.analyze(uri: Uri): MusicAnalysis` (decode up to 180 s to mono energy at `BeatFinder.WINDOW`/`HOP`, then `BeatFinder.find` and `BeatFinder.firstSoundUs`; throws `Unreadable` like `make`); `AudioTrackMaker.make(uri: Uri, videoDurationUs: Long, startUs: Long = 0)` (uses `AudioPlanner.plan(…, startUs)`).
- Produces in `VideoBackend`: `suspend fun analyzeMusic(uri: String): MusicAnalysis?` (null = unreadable) and `suspend fun loadMusic(uri: String, durationUs: Long, startUs: Long): MusicTrack?`.
- `VideoJob.startVideo` order: inputs → space → **analyse the music** (if any; null → `StartResult.MUSIC_UNREADABLE`) → `planToBeats(…, analysis.beats)` → map and satellite checks → `loadMusic(uri, frames × 1_000_000 / FPS, startUs)` → launch, where `startUs = planned.musicStartUs` when there are beats, else `analysis.firstSoundUs`.

- [ ] **Step 1: Failing tests.** `VideoJobTest` (fake backend): `musicAnalysedBeforePlanning` (the fake records the call order: `analyzeMusic` before `probeSatellite`; `loadMusic` gets the planned `musicStartUs`), `unreadableMusicRefusedBeforeAnything` (`analyzeMusic` → null gives `StartResult.MUSIC_UNREADABLE`, no pending job), `noBeatMusicStartsAtFirstSound` (`MusicAnalysis(null, 1_500_000)` → `loadMusic(…, startUs = 1_500_000)`). Device `BuiltInMusicBeatsTest`: for each `R.raw.music_*` (`android.resource://${packageName}/raw/…`), `AudioTrackMaker(context).analyze(uri)` gives `beatUs` within ±1 BPM of 124, 112, 132 and `startUs < 100_000`. `AudioTrackMakerTest`: `makeFromOffset` (a test tone file, `startUs = 1 s`: the encoded duration is `min(file − 1 s, video)`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*VideoJobTest' </dev/null` — FAIL.
- [ ] **Step 3: Implement** (reuse the existing decode loop; the analysis keeps only the energy array, not the PCM).
- [ ] **Step 4: Run** the JVM tests (PASS), then on the S22 `BuiltInMusicBeatsTest` and `AudioTrackMakerTest`, one class each (PASS).
- [ ] **Step 5: Commit** `feat(video): read the music's beat before planning; bundle three tracks`.

---

### Task 8: Bundled tracks in the options sheet

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/VideoOptions.kt`, `app/src/main/java/com/cowork/bikerecoder/video/VideoJobStore.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/video/VideoViewModel.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/video/VideoOptionsSheet.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/settings/SettingsScreen.kt`, `app/src/main/java/com/cowork/bikerecoder/video/VideoJob.kt` (URI for a bundled track)
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/VideoOptionsTest.kt`, `app/src/test/java/com/cowork/bikerecoder/video/VideoJobStoreTest.kt` (or the existing JSON test), `app/src/test/java/com/cowork/bikerecoder/ui/video/VideoViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/video/VideoOptionsSheetTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/settings/SettingsScreenTest.kt`

**Interfaces:**
- Produces: `enum class BuiltInMusic(val key: String, val title: String, val rawName: String) { SUNNY_PEDAL("sunny_pedal", "햇살 페달", "music_sunny_pedal"), WIND_ROAD("wind_road", "바람길", "music_wind_road"), OVER_THE_HILL("over_the_hill", "언덕 너머", "music_over_the_hill") }`; `VideoOptions.builtInMusic: BuiltInMusic? = BuiltInMusic.SUNNY_PEDAL` (`null` with `musicUri == null` = 없음; a picked file sets `musicUri` and `builtInMusic = null`). `fun VideoOptions.musicSource(packageName: String): String?` = `musicUri ?: builtInMusic?.let { "android.resource://$packageName/raw/${it.rawName}" }` — `VideoJob` uses it instead of `musicUri`.
- JSON (`VideoOptionsJson`): writes `"music": "<key>" | "none"`; reading without the key → `SUNNY_PEDAL`; `"none"` → `null`; `musicUri` still never written.
- View model: the sheet starts from the last options with `musicUri = null` (as now) and keeps `builtInMusic`; saving keeps `builtInMusic` (a picked file is saved as `builtInMusic = null` → next time 없음). `onClearMusic` → both null; new `onPickBuiltIn(music: BuiltInMusic)`. The unfinished-job retry keeps `builtInMusic`.
- Sheet: length tiles `최대 30초` `최대 1분` `최대 2분`; music tiles `없음` `햇살 페달` `바람길` `언덕 너머` `파일에서 고르기` (wrapping rows, the existing `ChoiceTile`); the picked file's name row as now. Test tags: `VideoTags.MUSIC_NONE`, `MUSIC_BUILT_IN_PREFIX + key`, `MUSIC_FILE`.
- Settings `ATTRIBUTIONS` gains `기본 배경음악: bikeRecoder가 직접 만든 곡`.

- [ ] **Step 1: Failing tests.** JSON: `missingMusicMeansSunnyPedal`, `noneRoundTrips`, `builtInRoundTrips`, `pickedFileNotWritten`. View model: `firstSheetHasSunnyPedal`, `builtInRemembered`, `pickedFileForgottenNextTime` (→ 없음), `retryKeepsBuiltIn`. `VideoOptionsTest`: `musicSourcePrefersPickedFile`. Device `VideoOptionsSheetTest`: the five music tiles and three length labels by text, tapping `바람길` selects it. `SettingsScreenTest`: the new attribution line and `V1.11.0` (version from Task 9 — assert the attribution only here).
- [ ] **Step 2: Run** `./gradlew :core:test --tests '*VideoOptionsTest' :app:testDebugUnitTest --tests '*VideoViewModelTest' --tests '*VideoJobStore*' </dev/null` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** JVM (PASS); on the S22 `VideoOptionsSheetTest` then `SettingsScreenTest` (PASS).
- [ ] **Step 5: Commit** `feat(video): three bundled tracks to pick, length as a maximum`.

---

### Task 9: Version, documents, comparison

**Files:**
- Modify: `app/build.gradle.kts` (`verMinor = 11`, `verPatch = 0`), `CHANGELOG.md`, `README.md`, `docs/VERSIONING.md`
- Create: `docs/verification/video-pace-field-test.md`
- Test: `app/src/test/…/AppVersionTest.kt` (11100, `1.11.0-dev`), `SettingsScreenTest` (`V1.11.0`)

- [ ] **Step 1:** Version tests to 11100 / `1.11.0-dev` / `V1.11.0`; run — FAIL; bump; run — PASS.
- [ ] **Step 2:** CHANGELOG `## [1.11.0] - 미출시` with 「추가」(bundled tracks, beat matching, orbiting camera, 30 fps), 「변경」(length from distance with the maximum labels, pace by distance with highlights, photo 2 s and count rule, camera height per video, 버전 1.11.0(versionCode 11100, debug `1.11.0-dev`)); README roadmap: V1.11.0 "영상 다듬기 2 — 박진감" 개발 중, a new row V1.11.1 "이름 팻말·시작 카드·고도 표시" 예정; VERSIONING the same two rows.
- [ ] **Step 3:** `docs/verification/video-pace-field-test.md`: PC/device results sections (filled by the controller), the S25 checklist of spec §9.3 (five rows, 결과 (대기)).
- [ ] **Step 4:** Full PC run `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test :app:compileDebugAndroidTestKotlin </dev/null` — PASS.
- [ ] **Step 5: Commit** `docs: V1.11.0 version, changelog, field checklist`.

The controller then runs the S22 device classes touched (`FrameRendererTest`, `VideoJobDeviceTest`, `VideoFlowDeviceTest`, `AudioTrackMakerTest`, `BuiltInMusicBeatsTest`, `VideoOptionsSheetTest`, `SettingsScreenTest`) one at a time, and makes the comparison videos (spec §9.2) on the S22 from copies of the S25 walk of 11-03 00:08 and ride of 10-25.
