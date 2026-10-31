# Task 5 report: trip totals, trip and segment charts

Status: DONE_WITH_CONCERNS (minor deviations below)

## Files
- app main: `ui/history/TripDetailViewModel.kt` (`profile`, `profileLoaded`), `TripDetailScreen.kt` (totals rows, chart item, `onOpenElevation`),
  `SegmentDetailViewModel.kt` (`Loaded.altitude` -> `Loaded.profile`), `SegmentDetailScreen.kt` (ElevationChart replaces the altitude LineChart),
  `ui/record/RideNumbers.kt` (1- and 3-cell `NumberRow`), `ui/BikeNavHost.kt` (`openElevation` for Trip / Segment).
- tests: `TripDetailViewModelTest` (+4), `FeedViewModelTest` (segment detail asserts profile; +1 test), androidTest `TripDetailScreenTest` (+6 incl. screenshot),
  `SegmentDetailScreenTest` (+1), `HistoryFixtures.ReadOnlyRides` (optional `elevationAt`).

## Decisions
- No schema change: `SegmentTotals` already has `descentM` and `maxAltitudeM`; `tripTotals()` already sums descent and takes the max of non-null
  max altitudes (null when all are null, shown `--` by `RideFormat.altitude`).
- Profile computed in `load()` on the injected `compute` dispatcher from segments sorted by `startedAt` (empty tracks dropped); recomputed when the segment list changes.
- Added `profileLoaded` so the screen does not flash "고도 정보가 없습니다" before the profile is computed.
- Totals card: row 2 = `평균/최고` (single centered cell), row 3 = `누적 상승 / 누적 하강 / 최고 고도`. Chart is a LazyColumn item right under the card.

## Results
- PC: `./gradlew :core:test :app:testDebugUnitTest` green.
- S22 (R5CT43TFS9A): `TripDetailScreenTest` 12/12, `SegmentDetailScreenTest` 8/8. Screenshot `Download/bikeRecoderTest/v170-elevation-trip.png` checked visually.

## Deviations / concerns
- The brief says the VM state gets `descentM` and `maxAltitudeM`; they are not separate flows because `total.descentM` / `total.maxAltitudeM` already carry them (tests assert on `total`).
- `SegmentDetailState.Loaded.altitude` was replaced by `profile`; the old FeedViewModelTest assertion was updated accordingly.
- The screenshot test uses the stateless content with a grey placeholder instead of the real map.
