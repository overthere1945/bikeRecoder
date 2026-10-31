# Final fix wave — V1.4.0 (branch feature/summary-stats-v1.4.0, BASE c1fc97b)

The whole-branch review is at `final-review.md` in this directory: read it first. It holds the file:line evidence and the suggested fixes for every item below. The spec is `docs/superpowers/specs/2026-10-31-summary-stats-design.md`, and the global constraints are in `global-constraints.md` (same directory).

Fix ALL of the following. Use TDD where there is a JVM-testable unit.

## Important
- **I1 – Keep the map across tab switches.** Today the camera snaps back to the user and the map reloads every time [지도] is shown again.
  - Keep the map tab composed while other tabs show, or save and restore the camera (center, zoom, bearing) and `centeredOnce`.
  - Choose whichever is safer with MapView lifecycle. Hidden tabs must not intercept touches. While the map is hidden, it must not keep rendering at full cost.
- **I2 – Consistent tab icons.** Replace the emoji with Material icons: outlined when not selected, filled when selected (Map / DynamicFeed or Article / BarChart / Settings). Check whether material-icons-extended is already a dependency. If it is not, use whatever icons are available without a heavy new dependency, or add it only if it is already in the version catalog.
- **I3 – No picture flash on scroll.** Add a small in-memory LruCache sized in bytes (about 1/8 of max memory, capped) for decoded feed pictures.
  - On the first frame, a picture already in the cache is shown synchronously, with no fall-back to the line drawing.
  - Decode at the size the card shows, using inSampleSize, not the full 1280×720, where that is easy.
- **I4 – Picture file size (ruling in the ledger).**
  - Measure on the S22 the file size of a real 1280×720 render saved as PNG 100 and as WebP lossy quality 90, for example from the existing FeedThumbnailRenderer device test with a log line or a temporary assertion.
  - If WebP is at least 2× smaller:
    - store as `Bitmap.CompressFormat.WEBP_LOSSY` q90 with the extension `.webp`;
    - bump `FeedThumbKey.VERSION` to 2;
    - make the prune also delete legacy `.png` and `.png.tmp` files;
    - update the regexes and tests.
  - Write the measured numbers in your report and in `docs/verification/summary-stats-field-test.md`.

## Minors to fix
- **M1 – Number formats.**
  - The feed month header uses `kmText` ("32.1 km") and the same totals the cards use: ended rides of at least 10 m only.
  - Metres are written with a space ("123 m") on the new screens (FeedCard, StatsScreen).
  - Check the band, the week strip, the stats tab and the cards for any other "km"/"m" without the space or with 2 decimals, and make them consistent.
- **M2 – No empty first frame.** The stats tab and the week strip (and the goal ring) must not show zeros or the no-goal state before Room and DataStore have emitted.
  - Use a nullable or "loading" state, and render nothing (or the previous content) until both have emitted.
  - The stats-tab goal dialog must seed from loaded values, so saving one goal can never wipe the other.
- **M3 – Goal ring text.** Fix the ring text overflow (WeekStrip): the big number on one line, then "/ 100 km" smaller on a second line. It must not clip at large font scale or with large goals (for example 1,234 / 10,000 km).
- **M4 – Recording.**
  - `RoomRideStatsSource` flow: add `.distinctUntilChanged()`.
  - Move the feed and stats view models' derivation (buildFeed, weekStrip, stats computation) off Main, using `flowOn(Dispatchers.Default)` or equivalent.
  - The goals flow gets `distinctUntilChanged` too.
- **M5 – This week follows the date.** `showThisWeek` uses anchor = null with WEEK, so the view follows day and week rollover.

## Docs
- `CHANGELOG.md` around line 47: say that goals are kept in settings, not in DB v6. DB v6 holds only `trip_label`.
- `docs/VERSIONING.md`: add roadmap rows for V1.3.1 (released) and V1.4.0 (개발 중), matching the existing row style.
- Spec §1.4 says the band uses "the same format functions as the record screen". Amend it to match the ruling: the band uses `kmText` + `RideFormat.duration`.
- `docs/verification/summary-stats-field-test.md`: add manual checks for:
  - a strip tap opens [통계] [주] at this week;
  - the strip's [목표 정하기] saves both goals;
  - the stats tab's 누적 지도 entry opens the chosen period;
  - the map position is kept across tab switches;
  - pictures already made show with no flash.
- Keep all user-facing text in Korean.
