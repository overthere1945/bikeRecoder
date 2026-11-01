# SDD ledger — plan: docs/superpowers/plans/2026-11-01-speed-hr-charts.md
Spec: docs/superpowers/specs/2026-11-01-speed-hr-charts-design.md. Branch feature/speed-hr-charts-v1.8.0 from main c3c9d2b.

## Pre-flight scan
| Pair / task | Produces → consumes | Finding |
|---|---|---|
| T1→T2 | MetricSeries, MetricKind, SpeedBand, TrackAxis → MetricChart, pathColors(colorAt, axis) | ok |
| T1→T3 | TrackAxis.paths, MetricSeries.speed/heartRate → VM | ok |
| T1→T4 | same + delete RideCharts.altitude/speedKmh, HeartRateCharts.bpm; HeartRateCharts.bpm uses RideCharts.cumulativeKm/downsample | T4 keeps cumulativeKm/downsample only if other users remain — ok |
| T2→T3 | MetricChart(scrubbable, scrubM), ChartText.scrubLine, pathColors | ok |
| T2→T4 | MetricChart small(onOpen), ChartText titles/descriptions | ok |
| T3→T4 | openChartMap(source, tab), ChartTab | ok; T4 merges TripDetailContent.onOpenElevation into onOpenChart(tab) |
| T2/T3/T4 share ElevationChart.kt, ElevationMapScreen, BikeNavHost | sequential | ok |
| T1 self | profileUnchanged test pins pre-refactor values — must compute before refactor | ok |
| T2 self | ok |
| T3 self | spec §2.4 tab-less title when single tab — test singleTabShowsTitle | ok |
| T4 self | LineChart delete — SegmentDetailScreen only user (checked 2026-11-01) | ok |
| T5 self | ok |
| Spec vs plan | plan adds 100 km/h speed drop and `--` scrub value for null (spec silent) | Ruling below |

Ruling: speeds > 100 km/h dropped (null) and null-region scrub shows `{거리} · --km/h|--bpm` — spec silent; prevents runaway y-axis and false interpolation; user told in chat 2026-11-01 — cost if wrong: copy tweak.
Task 1: dispatched implementer (opus), BASE cfd4e5f
Task 1: implementer DONE_WITH_CONCERNS (7eebdde) — core 547, app 1197; 100k smoothing 0.079 s. Concerns: window = full width (±windowMs/2) to satisfy fiveSecondStopKept; downsample bucket with any null → null; segmentOf counts non-empty segments; SpeedBand.legend in core.
Ruling: mean window is the FULL width windowMs (speed ±2.5 s, heart ±5 s), not ±windowMs — spec §1.2 says both "앞뒤 windowMs" and "3초 이상 멈춤은 그대로 보인다"; ±5 s leaves a 5 s stop at 5.45 km/h, contradicting the stated outcome the user chose (C: keep real stops) — spec text to be corrected in Task 5 docs and told to user — cost if wrong: lines a bit less smooth; one constant to change.
Task 1: review Needs fixes (opus). I1 gapNotCrossed not discriminating; I2 downsample any-null bucket enlarges holes; I3 sparse (all-day ~10 min) HR yields a non-null series with nothing drawable. Minors (deferred): smoothing sum may go -1e-14 (use rounded ints in labels); buckets average across segment boundaries (accepted, as old RideCharts); speedFallsBackToPairSpeed lacks per-segment pairing assert; valueAt at equal-distance points looks at first only.
Ruling: downsample uses majority rule — bucket null when more than half its points are null, else mean of non-null values — matches spec "RideCharts.downsample 방식, 묶음 평균" better; real gaps longer than ~1.5 buckets still show — cost if wrong: holes shorter than 0.5% of the axis are drawn over.
Ruling: MetricSeries.build returns null when no two neighbouring downsampled values are both non-null (nothing drawable) — avoids empty chart/tab for all-day ~10 min heart rate — cost if wrong: sparse heart rate never charted.
Task 1: fix round 1 dispatched (resume implementer): I1, I2 (majority), I3 (a), minor pairing assert.
Task 1: fix round 1 → 53a90d3 (core 550 pass; mutation-checked I1/I2/I3 tests). Controller scoped re-review: I1, I2 (majority rule, internal downsample), I3 (hasDrawableStep → null), pair-speed segment assert all addressed.
Task 1: complete (HEAD 53a90d3)
Task 2: dispatched implementer (opus), BASE 53a90d3. Rulings carried: SPEED_LEGEND from SpeedBand; labels roundToInt (no -0); null breaks fill+line; elevation look unchanged.
Task 2: DONE_WITH_CONCERNS (6128a48): core 550, app 1204, S22 MetricChartTest 7/7, ElevationChartTest 6/6. Concerns: HEART_LEGEND own strings (HR_LEGEND differs); elevation pathColors shares private colorsAlong instead of calling new overload; MetricChart reuses ElevationTags test tags.
Task 2: review dispatched (opus) on review-53a90d3..6128a48.diff
Task 2: review Approved (opus). Concerns 1-3 judged sound. Minors: (1) ChartFrame x = d/totalM NaN → roundToInt throws when totalM==0 (pre-existing in elevation); (2) MetricChart kind vs series.kind may disagree; (3) pathColors require checks sum only, not per-path sizes; (4) shared ElevationTags → multi-match on 3-chart screens; (5) HEART_LEGEND order tied to HrBand only by KDoc; (6) endLabels test semi-tautological (accepted); (7) lone value between nulls invisible (documented, accepted).
Ruling: Minors 1-3 folded into Task 3 dispatch as small extra fixes with tests (Task 3 drives scrubbing on the big map) — cheap and 1 is a crash — cost if wrong: Task 3 diff slightly larger. 4-5 carried to Task 3 as guidance (ancestor-scoped tags; legend colour pairing test). 6-7 accepted.
Task 2: complete (HEAD 6128a48)
Task 3: dispatched implementer (opus), BASE 6128a48, with Task 2 minors 1-5 folded in. Ruling: trip HR = concat PostStore.samples per segment; read failure → log, HR null.
Task 3: DONE_WITH_CONCERNS (623c247): core 552, app 1216, S22 ElevationMapScreenTest 10/10, MetricChartTest 9/9, ElevationChartTest 6/6. Concerns: big-map heart description from raw samples not stored avgBpm; added TrackAxis.pointAt; extra Ready fields; ElevationText.TITLE unused; extra segment read.
Task 3: review dispatched (opus) on review-6128a48..623c247.diff; asked to judge raw-vs-stored heart description against constraint.
Task 3: review Needs fixes (opus). Important: big-map heart description from raw sample mean, not stored (trip: tripHeartRate moving-time-weighted; segment: extras avgBpm/maxBpm) — violates constraint. Minors: (1) sequential sample reads block Ready; (2) no route-string test; (3) missing segment totals → "0km/h" read-out; (4) tab not saved across process death.
Ruling: fix Important via tripHeartRate(extras) with sample fallback (same rule as Task 4 brief) + tests; add Minor 2 route test. Minor 1 accepted (local Room reads, would complicate tab rule) — cost if wrong: spinner a bit longer on huge trips. Minor 3 accepted (deletion race only). Minor 4 accepted (consistent with existing scrub).
Task 3: fix round 1 dispatched (resume implementer).
Task 3: fix round 1 → a76328d (core 552, app 1220, S22 ElevationMapScreenTest 10/10; trip/segment tests RED on old code). Controller scoped re-review: heart read-out now tripHeartRate(extras) with sample fallback; route pinned. Addressed.
Task 3: complete (HEAD a76328d)
Task 4: dispatched implementer (opus), BASE a76328d. Rulings carried: trip order card→고도→속도→심박; onOpenElevation merged into onOpenChart; heart read-out same rule as Task 3; segment HR gate pinned as-is.
Task 4: DONE_WITH_CONCERNS (d29dd2a): core 539 (13 old chart tests deleted), app 1227, S22 TripDetail 21/21, SegmentDetail 11/11, DetailMapPhotos 2/2, RenameTripDialog 8/8, ElevationMapScreen 10/10. Concerns: trip series recomputed only on segment change (re-import HR needs reopen); ElevationMapViewModel switched to shared ChartText helpers; HR gate duplicated VM+screen; tests in DetailHeartRateTest; whole RideCharts.kt deleted.
Task 4: review dispatched (opus) on review-a76328d..d29dd2a.diff
Task 4: review Needs fixes (opus). Important: trip heartSeries recomputed only on segment change; samples arrive after load() (postProcessor.request on open, re-import) → card/map colour/big map show HR but trip chart missing or stale. Minors: threeTitlesInOrder uses clipped bounds (use positionInRoot); charts lambda rebuilt per recomposition (accepted); imports clean. Gate change (1 sample → no chart) accepted per Task 1 rule.
Ruling: fix Important — key trip heart series on existing readyAt flow (combine axis input + readyAt, mapLatest, guarded, stateIn) — spec §2.2 "고도와 같다" read as "same timing at least", consistency with card/map colours outweighs — cost if wrong: small extra recompute on HR arrival. Fix Minor 1 (positionInRoot). Minor 2 accepted.
Task 4: fix round 1 dispatched (resume implementer).
Task 4: fix round 1 → d8dc5cf (core 539, app 1228, S22 TripDetailScreenTest 21/21; heartSeriesFollowsNewSamples RED on d29dd2a). Controller scoped re-review: heartChart = combine(chartInput, readyAt).mapLatest{guarded read+compute}.stateIn; keeps last value while recomputing (no flicker); threeTitlesInOrder uses positionInRoot. Addressed. Accepted: heart chart appears a moment after the others on first open.
Task 4: complete (HEAD d8dc5cf)
Task 5: dispatched implementer (sonnet), BASE d8dc5cf, part A + spec §1.2 correction (full-width window, majority bucket, no-drawable-step null).
Task 5: DONE (56721ae): core 539, app 1228, brouter 19; S22 SettingsScreenTest 20/20; spec §1.2 and §5.1 corrected. Controller review of docs diff: OK.
Task 5: complete (part A, HEAD 56721ae)
Final review dispatched (opus) on review-c3c9d2b..56721ae.diff
Final review: With fixes (opus). I1 sparse HR still charted on short rides (B<30 → majority buckets around each sample) and trip/segment disagree; I2 stops averaged away by 300-pt buckets on long trips (checklist #1 risk). Minors: SpeedBand.hex getter formats per call; TrackAxis built twice; OOM comment wrong + speed failure drops heart; selected tab not saved; one-frame empty heart description; ElevationText.TITLE unused; docs miss 100 km/h drop, `--` scrub, stale names (speed(segments), bandAt, RideCharts.downsample).
Ruling: I1 — gate heart rate per segment by sample density using the existing HeartRateSummary.detailed rule (same as [심박] map colour), raw null for non-detailed segments; test 1 h and 5 h — consistent across screens — cost if wrong: a segment with borderline density loses its chart.
Ruling: I2 — keep the mean buckets (user asked for less jagged graphs; min-in-bucket would bring back spikes on long city trips); state in spec/CHANGELOG/checklist that stops show as dips on segment and short-trip charts, long trips show overall flow — cost if wrong: user wants visible stops on long trips → later tweak.
Ruling: fix Minors 1, 3 (comment + split speed failure from axis), 4, 6, 7; accept 2 (memory fine) and 5 (one frame).
Final fix wave dispatched (fresh opus implementer).
Final fix wave: DONE (96134c0): core 542, app 1233, S22 SegmentDetail 11/11, ElevationMapScreen 10/10, DetailMapPhotos 2/2. Beyond-scope: trip map [심박] colour greys non-detailed segments (to match charts); renamed androidTest heartRateGateUnchanged → heartRateChartOnlyWhenDetailed.
Final fix wave: scoped re-review dispatched (sonnet). Part B S22 full run started on 96134c0 in parallel (68 classes).
Final fix wave: re-review Approved (sonnet). All items addressed; beyond-scope grey trip-map colours judged consistent. Minors: verification doc :65 old reason; README :32 "신호등 멈춤은 남김" overstated; canColor vs detailedIds differ when avg/max null (accepted, post-processing always stores them).
Ruling: controller fixes the two doc lines with the part B verification commit.
Final fix wave: complete (HEAD 96134c0; doc lines fixed in working tree, to commit with part B).
Part B: S22 full run 68 classes 525/527 (2 opt-in screenshot skips), JVM fresh core 542 app 1233 brouter 19, installDebug + re-seed, field checks recorded (cca24c3).
