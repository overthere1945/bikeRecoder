# V1.7.0 final review fix wave

Status: DONE (all green)

## Changes
1. ElevationProfile.kt: elevation filter `a.isFinite()`; `slice` guard `!(length >= MIN_LENGTH_M)`.
   Tests: infiniteAltitudesAreSkippedLikeNull (+/-Infinity equals null), sliceWithNaNBoundIsNull,
   bandsMergeRuns slope 0.07, gradeWindowIsFiftyMetresEachSide (kink at 500 m, 7%).
   Kink test note: with a +/-50 m grade window and +/-25 m smoothing the real values are ~0 at 400 m, ~0.4 at 450 m
   (smoothing leak only), ~3.5 at 500 m (half), ~7 at 550 m. So the "half slope" point is 500 m, not 450/550 m;
   the test pins 400/450/500/550/700 m with tolerances 0.05/0.3/0.4/0.6/0.05, which a +/-25 m or +/-100 m window would break.
2. TripDetailViewModel.kt: profile computation wrapped in its own `guarded(...)` (null fallback), `_profileLoaded = true` always.
   New ctor seam `profileOf` (default `ElevationProfile.Companion::fromSegments`); test failingProfileComputationStillEndsLoading.
   Caveat: `guarded` catches `Exception` only, so a real `OutOfMemoryError` (an Error) still escapes to the outer guard's
   caller; the fix covers thrown Exceptions. Not widened, as the task said to use `guarded`.
3. ElevationChart.kt: small chart clickable has `onClickLabel = ElevationText.SHOW_ON_MAP`, keeps Role.Button.
4. README V1.9.0 row and CHANGELOG usual-speed wording updated.
5. docs/verification/elevation-export-field-test.md JVM counts updated.

## Tests
- PC `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test --rerun-tasks`: BUILD SUCCESSFUL in 2m 39s.
  core 58 classes / 524 tests, app 103 / 1190, routing-brouter 4 / 19; 0 failures, 0 skipped (total 1733).
- S22 (R5CT43TFS9A): ElevationChartTest BUILD SUCCESSFUL; TripDetailScreenTest 17 tests, 0 failures.
