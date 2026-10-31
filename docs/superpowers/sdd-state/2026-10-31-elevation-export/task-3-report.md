# Task 3 report: core GPX/KML writers

Status: DONE. Commit message `core: export — GPX and KML writers`.

## Files (core/.../core/export/)
- main: `ExportModels.kt` (ExportSegment, ExportLabels 출발/도착/경로 접두/주행 이름, formatFixed/formatCoordinate/formatElevation with Locale.ROOT, escapeXml), `GpxWriter.kt`, `KmlWriter.kt`
- test: `GpxWriterTest` (13), `KmlWriterTest` (5), `ExportRoundTripTest` (1)

## Decisions
- `name` of `route(...)` is used verbatim (metadata/trk/KML Document names). The caller builds it with `ExportLabels.routeName(destinationName)` = "bikeRecoder 경로 · {목적지}". Task 4 must pass the composed string, not just the destination.
- `Route.elevationsM` is `List<Double?>`; ele is written only if size == points.size, and per point only if non-null/finite.
- HR: sorted copy of samples + binary search (O(n log m)); tie -> earlier; window <= 5000 ms inclusive. Unsorted samples tolerated.
- Formatting: Locale.ROOT; "-0.0"/"-0.0000000" normalised to "0.0"; test sets Locale.GERMANY default.
- escapeXml escapes & < > " ' and drops XML-1.0-illegal control chars.
- Non-finite lat/lon points skipped (GPX trip/route, KML). GPX writes a trkseg per segment even if it has no valid points.
- KML: line only when >= 2 valid points per segment; 출발 = first valid point of first non-empty segment, 도착 = last valid point of last non-empty segment; coordinates omit the altitude component when null/non-finite (valid KML 2-tuple); style colour ffff0000 (aabbggrr = blue), width 4, plus tessellate and styleUrl. Route KML includes elevation per point when elevationsM matches.
- Round trip: original RideReplay -> GpxWriter.trip(points) -> GpxReader -> RideReplay; start/end equal, distance and moving time within 1%.

## Tests
TDD red confirmed (test compile failed: unresolved ExportSegment with implementation moved away), then green. `./gradlew :core:test`: 516 tests total, 0 failures (new: 19).

## Deviations
None from the brief. Extra tests beyond the named ones (locale, unsorted HR, tie, non-finite, negative zero, mismatched elevations, empty KML).
