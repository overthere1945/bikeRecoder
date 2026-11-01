# 공유 카드 (V1.10.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The share sheet sends Instagram/Facebook multi-image posts whose first image is a satellite card or a photo+numbers card rendered on the spot, offers 9:16 story cards and a transparent sticker, and adds hashtags for the national bike paths, 100 famous mountains and trails the trip passed.

**Architecture:** Pure logic lives in `core` (place data + matcher, card geometry) and in plain-Kotlin app classes (selection, send plan, cache, text) with JVM tests. Android drawing is one renderer class (`ShareCardRenderer`) reusing `MapSnapshots`/`RouteOverlay`/`TitleBadge`, wrapped by `AndroidShareCardMaker`, which writes cards into `cacheDir/share/` exposed by a FileProvider. `ShareViewModel` is rewritten around a selection of `ShareItem`s and talks to the card maker, the place tags and the gallery through small interfaces so its JVM test uses fakes.

**Tech Stack:** Kotlin, Jetpack Compose M3, Android Canvas, MapLibre `MapSnapshotter` (existing `MapSnapshots`), Room 9 (unchanged), JUnit 5 (JVM), AndroidX test + Compose UI test (S22), Python 3 stdlib (data build script), Overpass API (one-time data download).

**Spec:** `docs/superpowers/specs/2026-11-01-share-cards-design.md` (amended in planning: certification centres come from OSM, §4.1/§7/§9.1).

## Global Constraints

- Branch `feature/share-cards-v1.10.0`; version 1.10.0, versionCode 11000, debug `1.10.0-dev`, settings `V1.10.0`.
- All user-facing text is Korean and copied verbatim from spec §7 (and §1, §5 where quoted).
- Card sizes: FEED 1080×1350, STORY 1080×1920, STICKER 1080×1080. Cards and photo slides JPEG quality 92; sticker PNG.
- Cache dir `cacheDir/share/<tripId>/`; FileProvider authority `${applicationId}.share`; files older than 24 h deleted when the sheet opens.
- At most 10 items per post; email attachments ≤ `EMAIL_MAX_BYTES` (20 MB).
- Place matching: points 150 m, lines 100 m with ≥ 1,000 m along; resample 50 m; max 6 place tags + group tags.
- No DB change (Room stays at version 9). No new runtime permission.
- Device tests run only on S22 `R5CT43TFS9A`, one class per run (`ANDROID_SERIAL=R5CT43TFS9A`, `</dev/null`), the controller re-seeds the S22 afterwards. Never run connected tests on the S25. Never press "post/share" inside Instagram or Facebook.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Match surrounding code: KDoc on public types referencing the spec section ("spec §N"), English code comments, Korean UI strings as `const val MSG_…` next to their user.

## Review Focus

1. A trip whose photos were all deleted from the gallery (or no photo permission) → no photo card, no photo items, default selection = satellite card only; nothing crashes. (Task 9 test `photosGoneMeansSatelliteOnly`, Task 11 test `openWithNoReadablePhotos`.)
2. Satellite tiles unreachable (no key / offline) → dark `#1E2A22` card still renders and sends, no notice. (Task 6 device test `noMapGivesDarkCard`.)
3. A button pressed while the needed card is still rendering, then the sheet is closed → nothing is sent later, no crash. (Task 11 test `closeWhileWaitingSendsNothing`.)
4. Instagram build without a `SEND_MULTIPLE` receiver → first item only with notice "인스타그램이 한 장만 받아 첫 장만 보냅니다". (Task 10 test `instagramSingleOnly`.)
5. A saved (user-edited) text must not get place tags appended automatically, while chips still toggle them. (Task 4 test `savedTextKeepsPlaceTagsOut`, Task 11 test `savedTextOpensUnchanged`.)

---

## File Structure

**core** (`core/src/main/kotlin/com/cowork/bikerecoder/core/…`)
- `places/PlaceData.kt` — place model + `places.bin` reader (spec §4.2).
- `places/PlaceMatcher.kt` — tags from a track (spec §4.4).
- `video/CardSize.kt` — `CardSize` enum.
- `video/ShareCardLayout.kt` — boxes of the card bands, safe zones, map box, photo crop/fit rects (spec §2).
- `video/CameraPath.kt` (modify) — `overviewInBox`.

**tools** — `tools/places/build_places.py`, `places.tsv` (place → OSM name regex), `sources/100-mountains.csv`, `sources/peak-overrides.tsv`, `sources/overpass/*.ql`, `README.md`, `.gitignore` for `sources/osm/`.

**app** (`app/src/main/java/com/cowork/bikerecoder/…`)
- `share/ShareText.kt` (modify) — place tags in auto text, generic tag toggle.
- `share/ShareItems.kt` — `ShareMode`, `ShareItem`, `ShareSelection`.
- `share/SendPlan.kt` — `ShareFile`, `planSend` (which files, single/multiple, notices).
- `share/ShareIntents.kt` (modify) — multi-file intents.
- `share/ShareApps.kt` (modify) — `instagramTakesMultiple()`.
- `share/ShareCache.kt` — cache files and cleanup.
- `share/ShareFiles.kt` (modify) — keep `ShareCandidate`, `ShareTarget` (+ `STICKER`), `EMAIL_MAX_BYTES`; delete `SharePick`, `pickFile`, `pickLine`, `fileLines`.
- `share/PlaceTags.kt` — loads the asset once, matches a trip.
- `share/cards/CardFacts.kt` — texts drawn on a card (title, date line, cells, small line).
- `share/cards/ShareCardRenderer.kt` — Canvas drawing of the four kinds.
- `share/cards/ShareCardMaker.kt` — interface + `AndroidShareCardMaker` (inputs cache, render, write).
- `share/ShareGallery.kt` — saving cards/sticker into `Pictures/bikeRecoder/`.
- `video/MediaStoreWriter.kt` (modify) — `createPending(kind, displayName, mimeType)`.
- `ui/share/ShareState.kt`, `ShareViewModel.kt`, `ShareHost.kt`, `ShareSheet.kt` (rewrite parts), `ui/share/SharePreview.kt` (new dialog).
- `AppContainer.kt`, `ui/settings/SettingsScreen.kt` (attribution), `AndroidManifest.xml`, `res/xml/share_paths.xml`.
- `assets/places/places.bin`, `assets/places/places-summary.tsv`.

---

### Task 1: Place data format (core)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/places/PlaceData.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/places/PlaceDataTest.kt` (+ test helper `PlaceDataWriter.kt` in the same test dir)

**Interfaces:**
- Produces:
  ```kotlin
  enum class PlaceKind { ROUTE, CENTER, PEAK }            // stored u8: 0, 1, 2
  data class Place(val kind: PlaceKind, val tag: String, val group: String?, val routeIndex: Int?, val lines: List<List<GeoPoint>>)
  class PlaceData(val version: Int, val builtOn: String, val places: List<Place>) {
      companion object { fun read(input: java.io.InputStream): PlaceData }  // throws IllegalArgumentException on a bad header
  }
  ```
  `group` is null for an empty string in the file; `routeIndex` (index into `places`) only for CENTER.

- [ ] **Step 1: Write the failing tests** — `PlaceDataWriter.write(data: PlaceData, out: OutputStream)` in test sources implements spec §4.2 byte for byte (little-endian, `"PLC1"`, u16 version, varint-length UTF-8 strings, zigzag varint 1e-5° coordinates, first point absolute then deltas). Tests:
  - `roundTripKeepsEverything`: two routes (one with two lines), a centre with `routeIndex = 0`, a peak with group `"#100대명산"`; write → read → `assertEquals(original, read)` with coordinates compared after rounding to 1e-5.
  - `negativeDeltasSurvive`: a line going south-west keeps its points.
  - `badHeaderThrows`: bytes `"PLC2…"` → `assertThrows<IllegalArgumentException>`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*PlaceDataTest*'` → FAIL (unresolved `PlaceData`).
- [ ] **Step 3:** Implement `PlaceData.read` (use `DataInputStream` over a `BufferedInputStream`; varints are LEB128).
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(places): place data file reader`.

### Task 2: PlaceMatcher (core)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/places/PlaceMatcher.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/places/PlaceMatcherTest.kt`

**Interfaces:**
- Consumes: `PlaceData`, `Place`, `PlaceKind` (Task 1); `GeoPoint` (`core.model`).
- Produces: `class PlaceMatcher(data: PlaceData) { fun tags(tracks: List<List<GeoPoint>>): List<String> }` — `tracks` = one list per segment in time order. Constants `POINT_RADIUS_M = 150.0`, `LINE_RADIUS_M = 100.0`, `MIN_ALONG_M = 1_000.0`, `STEP_M = 50.0`, `MAX_TAGS = 6`, grid `CELL_DEG = 0.01`.

- [ ] **Step 1: Write the failing tests** (synthetic places around 36.0 N, 128.0 E; build tracks with a helper `east(from, metres)` / `north(...)`):
  - `peakWithin150mTags`: track passing 140 m from a peak → `["#북한산", "#100대명산"]`.
  - `peakAt160mDoesNot`: 160 m → `[]`.
  - `parallelLineTagsAfter1km`: 1.2 km parallel at 90 m from a route → `["#낙동강자전거길", "#국토종주"]`.
  - `bridgeCrossingDoesNot`: track crossing the route line perpendicularly, 300 m within 100 m → `[]`.
  - `farParallelDoesNot`: 2 km parallel at 120 m → `[]`.
  - `centreTagsItsRoute`: a single track point 150 m... use 140 m from a centre whose `routeIndex` points at route "#금강자전거길" (track far from the line itself) → `["#금강자전거길", "#국토종주"]`.
  - `orderIsFirstMatchAndTagsAreUnique`: track hits peak A, then route "#동해안자전거길" (강원 place), then the second "#동해안자전거길" place (경북) → `["#A", "#동해안자전거길", "#100대명산", "#국토종주"]`.
  - `atMostSixPlusGroups`: 8 peaks passed → first 6 peak tags then `"#100대명산"` once (size 7).
  - `allTracksCount`: two tracks (two days), each passing a different peak → both tags.
  - `emptyTrackIsEmpty`.
- [ ] **Step 2:** `./gradlew :core:test --tests '*PlaceMatcherTest*'` → FAIL.
- [ ] **Step 3:** Implement. Algorithm (spec §4.4): resample each track at `STEP_M` along the polyline (keep the first point; interpolate); index place points and line pieces (consecutive point pairs) into `CELL_DEG` cells by their bounding box expanded by the radius; distances on a local equirectangular plane (`x = Δlon·cos(lat)·111_320`, `y = Δlat·110_540`), point-to-piece shortest distance. A resampled step (pair of consecutive resampled points in one track) adds its length to a route's along-sum when both ends are within `LINE_RADIUS_M` of that route; the route matches at the step where the sum reaches `MIN_ALONG_M` — its order key is the index of the first in-radius step. A point place matches at the first resampled point within `POINT_RADIUS_M`; a CENTER contributes its route's tag with that order key. Sort matches by key, distinct by tag, take `MAX_TAGS`, then append distinct non-null groups of the kept places in order.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(places): match a track against the place data`.

### Task 3: Place data build script and asset

**Files:**
- Create: `tools/places/build_places.py`, `tools/places/places.tsv`, `tools/places/README.md`, `tools/places/.gitignore` (`sources/osm/`), `tools/places/sources/100-mountains.csv`, `tools/places/sources/peak-overrides.tsv`, `tools/places/sources/overpass/routes.ql`, `centers.ql`, `peaks.ql`
- Create (generated): `app/src/main/assets/places/places.bin`, `app/src/main/assets/places/places-summary.tsv`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/PlaceAssetTest.kt`

**Interfaces:**
- Consumes: file format of Task 1 (the Python writer must produce what `PlaceData.read` reads), `PlaceMatcher` (Task 2).
- Produces: the asset `places/places.bin` read by Task 8.

- [ ] **Step 1: Download sources.** Overpass (`https://overpass-api.de/api/interpreter`, POST `data=`, header `User-Agent: bikeRecoder-dev/1.0 (personal app)` — without it the server answers 406):
  - `routes.ql`: `relation["route"~"bicycle|hiking|foot"](area KR)` with members' geometry (`out geom;`), restricted by name regex to the places in `places.tsv`.
  - `centers.ql`: `node["name"~"인증센터"](area KR); out;` (96 on 2026-11-01).
  - `peaks.ql`: `node["natural"="peak"]["name"](area KR); out;`
  - Save responses to `tools/places/sources/osm/*.json` (git-ignored).
- [ ] **Step 2: Write `places.tsv`** — columns `id, kind(ROUTE), tag, group, name_regex`: the 13 national routes of spec §4.1 with group `#국토종주` (both 동해안 rows tag `#동해안자전거길`), and the trails/rivers of spec §4.1 with empty group. Regexes match OSM relation names as found, e.g. `^낙동강 ?자전거길`, `^동해안 ?자전거길 ?\(강원\)`, `^\[?서울둘레길`, `^\[?북한산둘레길`, `^불광천`. A route made of several relations (서울둘레길 코스들) unions their ways. Write `100-mountains.csv` (`name,sido,height_m,tag`) from 산림청's official 100 list; `tag` is `#`+name, with the region suffix only for duplicate names.
- [ ] **Step 3: Write `build_places.py`** (stdlib only): reads the OSM JSON + TSV/CSV; route lines = member ways' geometry joined where endpoints touch, simplified with Douglas–Peucker 20 m; peaks = OSM peak with the same name inside the 시·도 bbox (table in the script) and `|ele − height| ≤ 30` m, else `peak-overrides.tsv`, else exit 1 naming the peak; centres attach to the nearest ROUTE with group `#국토종주` within 2 km, others dropped; a `places.tsv` row with no OSM match is dropped and listed as `DROPPED` in the summary. Writes `places.bin` (spec §4.2) and `places-summary.tsv` (`kind, tag, points, km, source`). Prints totals; exits 1 if the file exceeds 1,000,000 bytes.
- [ ] **Step 4: Run** `python3 tools/places/build_places.py` → prints 13 national routes, 100 peaks, ≥ 80 centres, size < 1 MB; copy outputs to `app/src/main/assets/places/`.
- [ ] **Step 5: Write `PlaceAssetTest`** (JVM, reads `src/main/assets/places/places.bin` relative to the module dir):
  - `opensAndCounts`: ROUTE places with group `#국토종주` → 13; PEAK → 100; CENTER → ≥ 80.
  - `coordinatesInKorea`: every point lat in 33.0..39.0, lon in 124.0..132.0.
  - `fileUnder1MB`.
  - `sangjuboGivesNakdong`: a 3 km track through the 상주보 centre point (taken from the data by its OSM name in the summary, hard-code its coordinate) → contains `"#낙동강자전거길"` and `"#국토종주"`.
  - `bukhansanSummit`: track through 북한산 summit (37.6586, 126.9779) → contains `"#북한산"`, `"#100대명산"`.
- [ ] **Step 6:** `./gradlew :app:testDebugUnitTest --tests '*PlaceAssetTest*'` → PASS.
- [ ] **Step 7: Write `tools/places/README.md`**: what is downloaded, the exact commands, licences (OSM ODbL; 산림청 list), how to add a place. Commit `feat(places): build script and bundled place data`.

### Task 4: Share text with place tags

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/share/ShareText.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/ShareTextTest.kt`

**Interfaces:**
- Produces:
  - `fun autoShareText(facts: ShareFacts, defaultTags: String, placeTags: List<String> = emptyList()): String` — hashtag line = default → region → place, distinct, first kept.
  - `fun tagOn(text: String, tag: String): Boolean` and `fun toggleTag(text: String, tag: String): String` — the existing mood logic generalised; `moodOn`/`toggleMood` become one-line wrappers.

- [ ] **Step 1: Failing tests:** `placeTagsFollowRegion` (`"#자전거 #라이딩 #경북 #상주시 #낙동강자전거길 #국토종주"`), `placeTagEqualToRegionOnce`, `toggleTagAddsAndRemoves` (`#낙동강자전거길` add to last `#` line / remove), `tagOnIsWholeWord` (`#북한산둘레길` does not turn `#북한산` on), existing mood tests unchanged. (`savedTextKeepsPlaceTagsOut` lives in Task 11.)
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ShareTextTest*'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS. **Step 5:** Commit `feat(share): place hashtags in the auto text`.

### Task 5: Card geometry (core)

**Files:**
- Create: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CardSize.kt`, `core/src/main/kotlin/com/cowork/bikerecoder/core/video/ShareCardLayout.kt`
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/video/CameraPath.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/video/ShareCardLayoutTest.kt`, `CameraPathTest.kt` (add)

**Interfaces:**
- Produces:
  ```kotlin
  enum class CardSize(val width: Int, val height: Int) { FEED(1080, 1350), STORY(1080, 1920), STICKER(1080, 1080) }
  data class CardBoxes(val top: Box, val bottom: Box, val map: Box)   // Box from SummaryImageLayout.kt
  object ShareCardLayout {
      const val STORY_SAFE = 0.14f; const val MAP_INSET = 0.06f
      fun satellite(size: CardSize): CardBoxes      // FEED/STORY only
      fun photoCard(size: CardSize): CardBoxes      // map = mini-route square in the bottom band
      fun cover(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box   // source crop rect (centre crop)
      fun fitWidth(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box // destination rect, centred
  }
  fun CameraPath.overviewInBox(points: List<GeoPoint>, widthPx: Int, heightPx: Int, box: Box): Camera
  ```
  Band heights (as fractions of height): FEED top 0.13, bottom 0.24; STORY top 0.09 placed at y = 0.14·h, bottom 0.20 ending at 0.86·h. `map` = the area between the bands inset by `MAP_INSET` of width/height on each side; for STORY the area is between `0.14h + top` and `0.86h − bottom`.
  `overviewInBox`: zoom fitted to `box` (no extra margin), centre moved so the route's mercator centre lands on the box centre in a `widthPx×heightPx` snapshot (shift by `(boxCentre − imageCentre) / (512·2^zoom)` in mercator units).

- [ ] **Step 1: Failing tests:** `feedBandsAndMapDoNotOverlap`, `storyKeepsTextOutOfSafeZones` (every box within `0.14h..0.86h`), `mapInsideBetweenBands`, `coverCropsPortraitCentre` (4000×3000 into 1080×1350 → crop w = 2400, x = 800), `fitWidthLandscape` (4000×3000 into 1080×1350 → 1080×810 at y = 270), `overviewInBoxCentresRouteInBox` (project route centre with the camera's zoom/centre → lands within 1 px of the box centre).
- [ ] **Step 2:** `./gradlew :core:test --tests '*ShareCardLayoutTest*' --tests '*CameraPathTest*'` → FAIL.
- [ ] **Step 3:** Implement. **Step 4:** → PASS. **Step 5:** Commit `feat(video): share card geometry`.

### Task 6: ShareCardRenderer — satellite card

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/share/cards/CardFacts.kt`, `app/src/main/java/com/cowork/bikerecoder/share/cards/ShareCardRenderer.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/cards/CardFactsTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/share/cards/ShareCardRendererTest.kt`

**Interfaces:**
- Consumes: `VideoInputs`, `TripTotals`, `ActivityText.cardCells`, `FeedThumbnailRenderer.lineColors`, `RouteOverlay`, `MapSnapshots`, `MapMarks`, `TitleBadge`, `CameraPath.overviewInBox`, `ShareCardLayout`, `CardSize` (Task 5).
- Produces:
  ```kotlin
  data class CardFacts(val activity: ActivityType, val title: String, val region: String?, val dateLine: String,
                       val cells: List<Pair<String, String>>, val smallLine: String?)
  fun cardFacts(inputs: VideoInputs, region: String?): CardFacts
  fun cardDateLine(first: LocalDate, last: LocalDate, days: Int): String
  class ShareCardRenderer(context: Context, satellite: TileSource?, plain: TileSource?) {
      suspend fun satellite(inputs: VideoInputs, facts: CardFacts, size: CardSize): Bitmap
  }
  ```
  `satellite == null` means "no satellite" (no key / probe failed); `plain == null` only in tests (no map at all). Snapshot order: satellite → plain → none, each with an 8 s limit (`withTimeoutOrNull(8_000)` around `MapSnapshots.take`; a null or throwing take moves on). No map → fill `0xFF1E2A22`, no attribution line.

- [ ] **Step 1: Failing JVM tests (`CardFactsTest`):** `oneDayDateLine` → `"2026년 11월 3일 (화)"`; `multiDaySameMonth` → `"3일 · 11월 1일–3일"`; `multiDayAcrossMonths` → `"3일 · 10월 31일–11월 2일"`; `bikeCellsAndSmallLine` (cells = `cardCells(BIKE…)`, small line `"상승 410 m · ♥ 128"`); `hikeSmallLineDropsAscent` (`"♥ 128"`); `noAscentNoHeartNoSmallLine` (null). (Ascent under 1 m counts as none, as in the auto text.)
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*CardFactsTest*'` → FAIL; implement `CardFacts.kt`; → PASS.
- [ ] **Step 3: Device test `ShareCardRendererTest`** with a fixed `VideoInputs` (a 5 km loop near 36.4 N 128.2 E, one BIKE segment) and the `offline` `TileSource` of `FrameRendererTest` (its `styleJson()` throws, so the snapshot draws over a plain colour — deterministic) as `plain`, `satellite = null`:
  - `feedSize` → 1080×1350; `storySize` → 1080×1920.
  - `routeColourInMap`: some pixel inside `ShareCardLayout.satellite(FEED).map` equals the bike colour within ±8 per channel.
  - `bandsAreDark`: mean luminance of the top and bottom band < 0.45.
  - `storySafeZonesHaveNoWhiteText`: with no map (`plain = null`), no pixel with all channels > 240 in rows `0..268` and `1652..1919`.
  - `noMapGivesDarkCard`: `satellite = null, plain = null` → corner pixel `0xFF1E2A22`, route colour present.
  - Screenshot opt-in: when the instrumentation arg `screenshots=true`, write PNGs to the app's external files dir `share-cards/` (as `FrameRendererTest` does).
- [ ] **Step 4: Implement `ShareCardRenderer.satellite`** per spec §2.1–§2.2 (unit = 1080/360 = 3 px; top band black 35%: badge via `TitleBadge.draw`, `"$title · $region"`, date line; bottom band black 50%: three cells — label 11 unit, value 20 unit bold, FEED one row, STORY first cell 32 unit then a row of the other two + ascent; small line; `"bikeRecoder"` 10 unit white 85% bottom right; attribution 7 unit white 70% under the top band, right aligned; route via `RouteOverlay(timeline, lineColors(...))` `RouteStyle.FULL` and `MapMarks.startAndEnd`).
- [ ] **Step 5:** Controller runs `ShareCardRendererTest` on the S22 (implementer: `./gradlew :app:compileDebugAndroidTestKotlin` must pass). Commit `feat(share): satellite share card`.

### Task 7: ShareCardRenderer — photo card, photo slide, sticker

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/share/cards/ShareCardRenderer.kt`
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/share/cards/ShareCardRendererTest.kt` (add)

**Interfaces:**
- Produces (on `ShareCardRenderer`):
  ```kotlin
  fun photoCard(inputs: VideoInputs, facts: CardFacts, size: CardSize, photo: Bitmap, takenAt: GeoPoint?): Bitmap
  fun photoSlide(photo: Bitmap, size: CardSize): Bitmap
  fun sticker(inputs: VideoInputs, facts: CardFacts): Bitmap   // CardSize.STICKER, transparent
  ```
- [ ] **Step 1: Device tests:** `photoCardSizeAndBand` (1080×1350, bottom band dark, top-left pixel equals the photo's colour); `photoSlidePortraitFills` (portrait red photo → all four corners red); `photoSlideLandscapeBlurBehind` (landscape photo with a blue top half / green bottom half → the row at y = 100 is a blurred mix, the centre row shows the photo); `stickerTransparentCorners` (four corners alpha 0, a route pixel alpha 255); `stickerPngKeepsAlpha` (compress PNG → decode → corner alpha 0).
- [ ] **Step 2: Implement** per spec §2.3–§2.5: photo card = `ShareCardLayout.cover` fill, bottom band black 45% with the mini route (white 2.4 unit line, no map, fitted to `photoCard(size).map`) and a dot in the activity colour at `takenAt`, title line without badge, date, cells; photo slide = portrait (h ≥ w) cover, landscape = background cover scaled down to 1/16 and back up with filtering (blur), then `fitWidth`; sticker = transparent bitmap, route in the top 60% (white 4 unit over black 55% 7 unit), cells below with two shadow layers (`setShadowLayer` 3 and 6 unit, black).
- [ ] **Step 3:** Compile android tests; controller runs the class on the S22. Commit `feat(share): photo card, photo slides and sticker`.

### Task 8: Cache, FileProvider, card maker, place tags, gallery

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/share/SendPlan.kt` (only `ShareFile` for now), `app/src/main/java/com/cowork/bikerecoder/share/ShareCache.kt`, `app/src/main/java/com/cowork/bikerecoder/share/cards/ShareCardMaker.kt`, `app/src/main/java/com/cowork/bikerecoder/share/PlaceTags.kt`, `app/src/main/java/com/cowork/bikerecoder/share/ShareGallery.kt`, `app/src/main/res/xml/share_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` (provider + `<queries>` `ACTION_SEND_MULTIPLE image/*`), `app/src/main/java/com/cowork/bikerecoder/video/MediaStoreWriter.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/ShareCacheTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ShareFile(val uri: String, val mimeType: String, val sizeBytes: Long?)   // share/SendPlan.kt; Task 10 adds planSend there
  class ShareCache(private val root: File, private val uriOf: (File) -> String, private val now: () -> Long) {
      fun fileFor(tripId: Long, name: String): File            // root/<tripId>/<name>, dirs made
      fun share(file: File, mimeType: String): ShareFile
      fun sweep()                                              // deletes files last modified > 24 h ago, then empty dirs
  }
  enum class CardKind { SATELLITE, PHOTO }
  interface ShareCardMaker {
      suspend fun card(tripId: Long, kind: CardKind, size: CardSize, photoMediaId: Long?): ShareFile
      suspend fun slide(tripId: Long, mediaId: Long, size: CardSize): ShareFile
      suspend fun sticker(tripId: Long): ShareFile
  }
  class AndroidShareCardMaker(...) : ShareCardMaker   // names: feed-satellite.jpg, story-photocard-<mediaId>.jpg, feed-photo-<mediaId>.jpg, sticker.png
  class PlaceTags(private val open: () -> InputStream, private val rideStore: RideStore, private val log: WarnLog) {
      suspend fun of(tripId: Long): List<String>   // finished segments ≥ MIN_RIDE_DISTANCE_M, points per segment; data read once (Mutex); failure → empty + warn
  }
  interface ShareGallery { suspend fun saveJpeg(file: ShareFile, displayName: String); suspend fun savePng(file: ShareFile, displayName: String) }
  fun MediaStoreWriter.createPending(kind: MediaKind, displayName: String, mimeType: String): Uri   // the old overload delegates
  ```
  `AndroidShareCardMaker` keeps the `VideoInputs` + `CardFacts` of the last trip (one-entry cache), decides satellite like `FeedThumbnailRenderer` (`backend.probeSatellite(first point) == OK` → `SatelliteTileSource(plain, key)`, else null), decodes photos with `PhotoThumbnails.full(mediaId, 2048, software = true)` (photo card position = `inputs.timeline.at(cue.moveMs).point`), writes JPEG 92 / PNG through `ShareCache`, a write failing with `IOException` whose message or cause is `ENOSPC` is rethrown as `NoSpaceException`. Display names: `bikeRecoder_card_<yyyyMMdd_HHmmss>.jpg`, `bikeRecoder_sticker_<…>.png`.

- [ ] **Step 1: Failing tests (`ShareCacheTest`, JUnit `@TempDir`, `now` injected):** `sweepDeletesOnlyOlderThan24h` (files at now−25 h deleted, now−23 h kept), `sweepRemovesEmptyTripDirs`, `fileForMakesDirs`, `shareCarriesSizeAndMime`.
- [ ] **Step 2:** FAIL → implement `ShareCache` → PASS.
- [ ] **Step 3:** Implement the rest; manifest provider:
  ```xml
  <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.share"
      android:exported="false" android:grantUriPermissions="true">
      <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/share_paths"/>
  </provider>
  ```
  `share_paths.xml`: `<paths><cache-path name="share" path="share/"/></paths>`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest --tests '*ShareCacheTest*' :app:assembleDebug` → PASS / BUILD SUCCESSFUL. Commit `feat(share): card cache, maker, place tags and gallery saving`.

### Task 9: Selection model

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/share/ShareItems.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/ShareSelectionTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ShareMode(val size: CardSize) { FEED(CardSize.FEED), STORY(CardSize.STORY) }
  sealed interface ShareItem {
      data object SatelliteCard : ShareItem
      data object PhotoCard : ShareItem
      data class Photo(val mediaId: Long, val index: Int) : ShareItem   // index from 1, photo order
      data class Video(val candidate: ShareCandidate) : ShareItem
  }
  fun shareItems(photoIds: List<Long>, video: ShareCandidate?): List<ShareItem>
  data class ShareSelection(val mode: ShareMode, val chosen: List<ShareItem>) {
      fun toggle(item: ShareItem): Pair<ShareSelection, String?>   // second = notice
      companion object { const val MAX_ITEMS = 10; const val MSG_MAX = "최대 10장까지 고를 수 있습니다"
          fun initial(mode: ShareMode, items: List<ShareItem>, cardsAllowed: Boolean): ShareSelection }
  }
  ```
- [ ] **Step 1: Failing tests:**
  - `tapNumbersInOrder`, `tapAgainRemovesAndRenumbers` (chosen list order is the numbering).
  - `eleventhIsRefused` → selection unchanged, notice `MSG_MAX`.
  - `videoIsAlone` (choosing video clears others; choosing a photo while video is chosen drops the video).
  - `storyKeepsOne` (tap replaces).
  - `initialFeed` for 0, 3, 4, 9 photos: `[Satellite]`, `[Satellite, P1, P2, P3]`, `[Satellite, P1..P4]`, `[Satellite, P1, P4, P6, P9]` (indices `round(i·(n−1)/3)` for i = 0..3, 0-based → P1,P4,P6,P9).
  - `initialStory` → `[Satellite]`.
  - `cardsNotAllowed` → feed: the photos only by the same rule; story: first photo; no photos → empty.
  - `photosGoneMeansSatelliteOnly`: `shareItems(emptyList(), null)` → `[SatelliteCard]` only (no PhotoCard).
- [ ] **Step 2:** FAIL → implement → PASS. **Step 3:** Commit `feat(share): share item selection`.

### Task 10: Send plan and intents

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/share/SendPlan.kt` (created by Task 8 with `ShareFile`)
- Modify: `app/src/main/java/com/cowork/bikerecoder/share/ShareIntents.kt`, `app/src/main/java/com/cowork/bikerecoder/share/ShareApps.kt`, `app/src/main/java/com/cowork/bikerecoder/share/ShareFiles.kt` (add `ShareTarget.STICKER`; delete `SharePick`, `pickFile`, `pickLine`, `fileLines` and `ShareFilesTest`'s tests of them)
- Test: `app/src/test/java/com/cowork/bikerecoder/share/SendPlanTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/share/ShareIntentsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class SendPlan(val files: List<ShareFile>, val notice: String?)
  fun planSend(target: ShareTarget, files: List<ShareFile>, instagramMultiple: Boolean): SendPlan
  const val MSG_INSTAGRAM_ONE = "인스타그램이 한 장만 받아 첫 장만 보냅니다"
  fun msgEmailTrimmed(n: Int) = "용량 때문에 ${n}장만 붙였습니다"
  object ShareIntents { fun files(target: ShareTarget, files: List<ShareFile>, text: String, apps: ShareApps): Intent; fun textOnly(...) /* unchanged */ }
  interface ShareApps { /* existing */ fun instagramTakesMultiple(): Boolean }
  ```
  `planSend`: STORY → first file; INSTAGRAM with `!instagramMultiple` and > 1 → first + `MSG_INSTAGRAM_ONE`; EMAIL → drop from the end while the summed size (null = over the limit) > `EMAIL_MAX_BYTES`; dropped > 0 and kept ≥ 1 → `msgEmailTrimmed(kept)`; kept = 0 → empty files (text only). `ShareIntents.files`: 1 file → `ACTION_SEND` (`EXTRA_STREAM`, `type` = mime, `ClipData` uri, read grant); more → `ACTION_SEND_MULTIPLE` (`EXTRA_STREAM` = `ArrayList<Uri>`, `type` = `image/jpeg`, `ClipData` with every uri, read grant); targeting as today (Instagram multiple uses the feed-receiver rule over `SEND_MULTIPLE` receivers). `PackageShareApps.instagramTakesMultiple()` queries `ACTION_SEND_MULTIPLE` `image/jpeg` in the Instagram package.

- [ ] **Step 1: Failing JVM tests (`SendPlanTest`):** `storySendsFirstOnly`, `instagramSingleOnly` (3 files, false → 1 file + notice), `instagramMultipleKeepsAll`, `emailTrimsFromEnd` (8 MB ×3 → 2 files + `"용량 때문에 2장만 붙였습니다"`), `emailUnknownSizeCountsAsOver`, `emailNothingFitsIsTextOnly`, `facebookKeepsOrder`.
- [ ] **Step 2:** FAIL → implement `planSend` → PASS.
- [ ] **Step 3: Device test `ShareIntentsTest`:** `oneFileIsSend`, `manyFilesIsSendMultiple` (action, `EXTRA_STREAM` list order, `clipData.itemCount`, `FLAG_GRANT_READ_URI_PERMISSION`), `moreWrapsChooser`, `emailHasSelector`.
- [ ] **Step 4:** Implement intents + `ShareApps`; update `ShareAppsTest` fakes for the new method; `./gradlew :app:testDebugUnitTest` → PASS. Commit `feat(share): multi-image send plan and intents`.

### Task 11: ShareViewModel and state

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareState.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareViewModel.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/share/ShareViewModelTest.kt` (rewrite the file-pick/making cases; keep text/mood/save cases)

**Interfaces:**
- Consumes: Tasks 4, 8, 9, 10.
- Produces:
  ```kotlin
  sealed interface CardStatus { data object Rendering; data class Ready(val file: ShareFile); data class Failed(val noSpace: Boolean) }
  data class CardKey(val kind: CardKind, val mode: ShareMode, val photoMediaId: Long?)
  data class ShareSheetState(
      val tripId: Long, val text: String, val autoText: String, val stale: Boolean, val moods: Set<Mood>,
      val mode: ShareMode, val items: List<ShareItem>, val selection: ShareSelection,
      val cards: Map<CardKey, CardStatus>, val photoCardMediaId: Long?, val placeTags: List<String>,
      val tripFinished: Boolean, val waiting: Boolean, val error: String?,
  ) { fun enabled(target: ShareTarget): Boolean; fun placeOn(tag: String): Boolean }
  sealed interface ShareEvent {
      data class Send(val target: ShareTarget, val files: List<ShareFile>, val text: String) : ShareEvent
      data class Copy(val text: String) : ShareEvent
      data class Notice(val message: String, val long: Boolean = false) : ShareEvent
  }
  class ShareViewModel(tripStore, rideStore, tripLabels, tripMedia, defaultHashtags, candidates,
      photoIds: suspend (Long) -> List<Long>, placeTags: suspend (Long) -> List<String>,
      cards: ShareCardMaker, gallery: ShareGallery, cache: ShareCache?, log, compute, io, zone)
  // new public functions:
  fun setMode(mode: ShareMode); fun tap(item: ShareItem); fun setPhotoCard(mediaId: Long); fun togglePlace(tag: String); fun retryCards()
  // kept: open, close, edit, reset, toggle(mood), press(target), cancelMaking (= stop waiting)
  ```
  Messages (companion): `MSG_UNFINISHED = "여행을 마치면 카드를 만들 수 있습니다"`, `MSG_CARD_FAILED = "카드를 만들지 못했습니다"`, `MSG_NO_SPACE = "저장 공간이 부족해 카드를 만들지 못했습니다"`, `msgSavedCards(n) = "카드 ${n}장을 갤러리에 저장했습니다"`, `MSG_ALREADY_IN_GALLERY = "사진과 영상은 이미 갤러리에 있습니다"`, `MSG_ALREADY_SAVED = "이미 저장했습니다"`, `MSG_STICKER_SAVED = "스티커를 저장했습니다. 인스타 스토리에서 사진 스티커로 붙이세요"`.
  Behaviour (spec §1–§6): `open` sweeps the cache (io), loads the trip as today, place tags (`autoShareText(..., placeTags)`; saved text used as is), photo ids, the newest video candidate made after the last ride (`ShareCandidate` with `kind == VIDEO` and `createdAt > lastEndedAt`), items, initial selection, then starts rendering this mode's satellite card and (photos > 0) photo card with the first photo. `setMode` resets the selection to `initial` and renders missing cards. `press`: COPY as today; STICKER → `cards.sticker` then `gallery.savePng`, notice long, second press `MSG_ALREADY_SAVED`; SAVE → cards among chosen saved with `saveJpeg` (key remembered per sheet), none → `MSG_ALREADY_IN_GALLERY`; sending targets → nothing chosen: EMAIL/MORE send text only (`files = emptyList()`), others ignored; otherwise wait (`waiting = true`) until every needed card is Ready, render slides (`cards.slide`), video → its gallery uri, then emit `Send(target, files, text)` (the sheet's `ShareActions` applies `planSend`). A Failed card → `error = MSG_CARD_FAILED`/`MSG_NO_SPACE`, `waiting = false`. `cancelMaking`/`close` drop the waiting send.

- [ ] **Step 1: Failing tests** (fakes for `ShareCardMaker` with controllable `CompletableDeferred`s, `ShareGallery`, place tags):
  - `openRendersSatelliteAndPhotoCard`, `openWithNoReadablePhotos` (photoIds empty → items `[SatelliteCard]`, selection `[SatelliteCard]`).
  - `autoTextHasPlaceTags`, `savedTextOpensUnchanged` (saved text without place tags stays; `placeOn` false; `togglePlace` adds).
  - `pressWaitsForCardThenSends` (Send carries files in selection order: satellite file then slides).
  - `closeWhileWaitingSendsNothing` (complete the card after `close` → no event).
  - `cardFailureShowsErrorAndRetry` (`retryCards` re-renders).
  - `noSpaceMessage`.
  - `storyModeSendsOneAndResets`.
  - `videoSendsAlone`.
  - `saveCardsOnlyAndOnce` (`"카드 1장을 갤러리에 저장했습니다"`, then `"이미 저장했습니다"`), `saveWithOnlyPhotos` → `MSG_ALREADY_IN_GALLERY`.
  - `stickerSavesOnce`.
  - `unfinishedTripHasNoCards` (`tripFinished = false` → no card render calls, selection = photos rule, SAVE/STICKER disabled).
  - `nothingChosenEmailSendsText`.
  - Keep existing text/mood/stale/save-text tests passing.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests '*ShareViewModelTest*'` → FAIL.
- [ ] **Step 3:** Implement. Remove `VideoJobControl` from the view model (spec §8).
- [ ] **Step 4:** → PASS. **Step 5:** Commit `feat(share): share view model with cards and selection`.

### Task 12: Share sheet UI and host

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareSheet.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareHost.kt`
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/share/SharePreview.kt`
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/ui/share/ShareSheetTest.kt`, `ShareActionsTest.kt` (update)

**Interfaces:**
- Consumes: Task 11 state/functions; `planSend`, `ShareIntents.files`, `ShareApps.instagramTakesMultiple` (Task 10).
- Produces: `ShareTags` additions: `MODE_FEED = "share-mode-feed"`, `MODE_STORY = "share-mode-story"`, `fun item(i: Int) = "share-item-$i"`, `PREVIEW = "share-preview"`, `fun place(tag: String) = "share-place-$tag"`. `ShareSheet` gets `thumbnail: suspend (ShareItem, ShareSheetState) -> ImageBitmap?` (host: card files decoded from the cache uri at 216 px, photos via `PhotoThumbnails.thumbnail(mediaId, 216)`, video via its gallery thumbnail).
  Layout per spec §1.1–§1.3: segmented row; strip (72 dp squares, number badge / check, warning icon on Failed, progress on Rendering, content description `"위성 카드"`, `"사진 카드"`, `"사진 N"`, `"영상"` + `", N번째"` when chosen); long-press → `SharePreview` dialog (`[닫기]`; for the photo card a row of photos calling `setPhotoCard`); mood chips; text box; "지나간 길" chips (hidden when empty); buttons: FEED row 1 `[페이스북][인스타그램][이메일][더 보기]`, STORY row 1 `[페이스북][스토리][이메일][더 보기]`, row 2 `[복사][저장][스티커]` (`Icons.Filled.AutoAwesome`); waiting → "카드를 만드는 중…" + progress + [취소]; `error` line in the error colour with [다시 시도] → `retryCards`; `tripFinished == false` → "여행을 마치면 카드를 만들 수 있습니다" above the strip.
  `ShareActions.send`: `planSend(event.target, event.files, apps.instagramTakesMultiple())`, tell its notice, paste text for FB/IG/STORY as today, `ShareIntents.files` (empty files → `textOnly`), close on success. `ShareEvent.Notice(long = true)` uses `Toast.LENGTH_LONG`.
- [ ] **Step 1: Update device tests:** `ShareSheetTest` — `modeSwapsInstagramAndStory`, `stripNumbersAndRemoves`, `longPressOpensPreview`, `noPhotoCardWithoutPhotos`, `placeChipTogglesText`, `nothingChosenDimsButtons`, `unfinishedTripLine`; `ShareActionsTest` — `multipleFilesLaunchSendMultiple`, `instagramSingleOnlyTells`, `emailTrimTells`, `stickerNoticeIsLong`.
- [ ] **Step 2:** Implement. `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 3:** Controller runs both classes on the S22. Commit `feat(share): share sheet with modes, strip, preview and place chips`.

### Task 13: Wiring, attribution, version, docs

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/AppContainer.kt`, `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareViewModel.kt` (factory), `app/src/main/java/com/cowork/bikerecoder/ui/settings/SettingsScreen.kt`, `README.md`, `CHANGELOG.md`, `docs/VERSIONING.md`, `app/build.gradle.kts`, `app/src/test/.../AppVersionTest.kt`, `app/src/androidTest/.../SettingsScreenTest.kt`
- Create: `docs/verification/share-cards-field-test.md`

- [ ] **Step 1:** `AppContainer`: `shareCache` (`File(cacheDir, "share")`, `FileProvider.getUriForFile(appContext, "${appContext.packageName}.share", it).toString()`), `shareCardMaker` (`AndroidShareCardMaker`), `placeTags` (`PlaceTags({ appContext.assets.open("places/places.bin") }, rideStore, androidWarnLog("PlaceTags"))`), `shareGallery` over `mediaStoreWriter`, `photoIds` = non-hidden `ride_photo` media ids of the trip's finished segments in `takenAt` order that still exist (`MediaStoreWriter.exists` on the image content uri) and only when `MediaStorePhotoSource.access() != NONE`. Factory passes them.
- [ ] **Step 2:** Settings attribution list gets `"길·산 데이터: © OpenStreetMap contributors (ODbL), 산림청 100대 명산 목록"`; README 「라이선스와 출처 표기」 the same line.
- [ ] **Step 3:** `verMinor = 10`, `verPatch = 0`; `AppVersionTest` expects 11000 / `1.10.0-dev`; `SettingsScreenTest` expects `V1.10.0`. CHANGELOG `[1.10.0] - 미출시` (Korean bullets of spec §0 items 1–5), VERSIONING row V1.10.0 개발 중, README current version V1.10.0.
- [ ] **Step 4:** `docs/verification/share-cards-field-test.md`: S22 checks (IG/FB compose shows N images then back out without posting; [저장] card appears in gallery; sticker PNG transparent) and the user's S25 checklist (spec §9.3).
- [ ] **Step 5:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL. Commit `feat(share): wire share cards, V1.10.0 version and docs`.

---

## Controller verification after Task 13 (not a subagent task)

1. S22 device classes one at a time: `ShareCardRendererTest`, `ShareIntentsTest`, `ShareSheetTest`, `ShareActionsTest`, `SettingsScreenTest`; then re-seed the S22.
2. S22 real flow: open a seeded trip's share sheet, look at the cards (screenshots), open Instagram/Facebook compose with 3 images and back out without posting, [저장], [스티커].
3. Whole-branch final review, then the release pattern on the user's go-ahead.
