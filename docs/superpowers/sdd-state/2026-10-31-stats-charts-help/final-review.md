# Final review — 통계 탭 그래프·설명 보강 (848888c..e64e8fd)

Reviewer: final cross-task review (read-only; no Gradle, no adb). Inputs: the plan with its Global Constraints, Review Focus and rulings; spec §3.3, §5 and §6; the ledger; the full diff; the device screenshot `v140-stats-cumulative-2.png`; and the current sources of `ui/stats/*` and `core/stats/*`.

## Strengths

- **Core logic is small and pure.**
  - `StatsCalculator.allIsMonthly` and `monthsSpanned` count months from the first day of each month, which gives the 24/25-month boundary exactly (2024-11..2026-10 = 24 → monthly; 2024-10.. = 25 → yearly).
  - `cumulative` uses one `runningTotals` helper for both series.
  - The boundary cases I was asked to check are all correct:
    - first day: `current` has 1 point;
    - previous period longer: `length = max(currentDays, previous.size)`;
    - leap year: `currentDays` 366, and `cumulativeMiddleIndex` 182 for July 1;
    - a future period cannot occur, because the anchor is coerced into `bounds`.
  - Tests cover the plan's cases (`allOf24MonthsIsMonthly`, `allOf25MonthsIsYearly`, `cumulativeOnTheFirstDayIsOnePointAndKeepsTheLongerPeriod`, 2028-03-01 in the VM test).
- **The ViewModel computes everything on the compute dispatcher.** `cumulativeOf` runs inside `build`, under `flowOn(compute)`. The UI only measures text and draws. No 0-division paths:
  - `niceTop(0)` returns 1;
  - `largest` falls back to 1.0;
  - `lastDay` and the goal end are clamped to at least 1;
  - `series.previous` is never empty.
- **Units and colours agree across the two charts.**
  - Units are the same: km, 시간 as h:mm, and m. The bubble reuses `barShortText`. Time is floored in both `barShortText` and `RideFormat.duration`, so "0:19" and "19분" agree.
  - Colours are coherent: primary for this period, primary 40 % for dimmed bars, onSurfaceVariant 40 % for the previous line, primary 60 % dashed for the goal.
- **Overlap handling is measured, not guessed.** The values use a priority order and `keepApart` with a 4 dp gap, and the double-font test asserts that no two boxes overlap.
- **Help tips are well contained.**
  - One `openTip` lives in `StatsScreen`. Because it is plain `remember` (ruled), a rotation or leaving the tab closes it.
  - The focusable popup takes the outside tap and Back. The bubble is capped at 60 % of the screen height and scrolls.
  - The icon reads "도움말: 주제".
  - All six texts match plan Step 6 word for word, including the Task 2 graph text. "1월 7일부터" matches `Goals.yearProjectionKm` (`dayOfYear < 7` → null).
- **Docs mostly track the code, including the three mid-run rulings.**
  - Spec §3.3 documents the tick rule, the goal end on the period's last day and the screen-reader sentence.
  - The verification doc adds rows 17–20 and updates the pass count to 20.
- **The device screenshot of [월] reads cleanly.** Values sit right above the bars, the unit is in the corner, the ticks are 0/100/200 for 186 km, the bubble is pushed inside the plot, and the grey September line stops at day 30 on a 31-day axis.

## Issues

### Critical

None.

### Important

None. Three Minors below are marked **fix-before-merge**. Each is small, but a user would notice it in normal use.

### Minor

**M1 — fix-before-merge. A finished period's screen-reader sentence contradicts the comparison sentence and the legend (and is off by one day in leap years).**
- Where: `app/src/main/java/com/cowork/bikerecoder/ui/stats/StatsViewModel.kt:278-279, 294`.
- What is wrong: `previousAt = series.previous.getOrElse(last) { series.previous.last() }` takes the previous period at the same day index, even when the shown period is finished.
  - Example: viewing a finished 2월 against 1월, TalkBack says "누적 2월 X km, 1월 Y km", where Y is January 1–28 only.
  - On the same screen, the comparison sentence says "1월보다 …" against all of January. The grey line also ends at January's full total.
  - This happens for every finished month after a longer one (2, 4, 6, 9, 11월), and for a finished non-leap year after a leap year (Dec 31 is lost).
  - In an in-progress leap-year [년], the index differs by one day from `comparison`'s `today.minusYears(1)` after February. So the spoken "작년 X" and "작년 이맘때보다" can disagree when a ride falls on that day.
- Why it matters: a screen-reader user hears a number that matches nothing visible: not the grey line's end, not the comparison, and not what the name "1월" implies.
- Fix: reuse the comparison, which already has exactly the right semantics (same day while in progress, the whole period once finished, date-based for leap years):
  - In `build`, compute `val comparison = StatsCalculator.comparison(rides, period, today)` once.
  - Pass it to `cumulativeOf`.
  - Write the sentence with `metricText(metric, comparison.previous)`.
  - Update the spec §3.3 "화면 읽기" sentence: "지난 기간 값은 이번 기간이면 같은 날까지, 끝난 기간이면 그 기간 전체".
  - Add one VM test: finished 2월 with a ride on 1월 30일.

**M2 — fix-before-merge. Thousands are grouped in the ticks but not in the end bubble or the bar values.**
- Where: `app/src/main/java/com/cowork/bikerecoder/ui/stats/StatsFormat.kt:40-51` (`barShortText`) against `CumulativeChart.kt:228-231` (`tickTexts` uses `groupedInt`).
- What is wrong: the cumulative chart can show two number formats at once:
  - [년] + 상승 고도: ticks "5,000" "2,500", bubble "4370". A year of climbing passes 1,000 m quickly; the October fixture alone is 945 m.
  - [년] + 거리 past 1,000 km: ticks "1,500", bubble "1124".
  - [전체] yearly bars show "4370" and "1124" too.
  - The rest of the app groups these numbers: `meterText` "1,234 m", and the screen-reader sentence says "4,370 m".
- Why it matters: Global Constraint "숫자 모양" asks for one number style. The seam between the two tasks shows it inside a single chart.
- Fix: in `barShortText`, format the whole-number branches (km ≥ 100 and m) with `groupedInt(...)`. The decimal branch is under 100 and needs no grouping.
  - Update the format and VM tests.
  - `keepApart` already measures the wider text, so overlap stays safe.

**M3 — fix-before-merge. The metric chip row breaks at a big font (deferred Task 1 minor, made worse by the rename).**
- Where: `app/src/main/java/com/cowork/bikerecoder/ui/stats/StatsScreen.kt:311-324`.
- What is wrong: three `FilterChip`s plus the 48 dp "?" share one `Row`. The chips' inner `Row` is `weight(1f, fill = false)`, so the last chip absorbs the shortfall.
- The arithmetic on a 360–384 dp phone, from M3 chip padding of about 16 dp per side; I computed this and did not see it on a device:
  - At font scale 2, the chips need about 88 + 88 + 150 + 16 dp. Only about 272 dp remain beside the "?".
  - "상승 고도" (renamed from the 2-glyph "상승") then gets about 50 dp for its label, so it wraps to several lines and makes a tall, broken chip.
  - At 1.5 it is already about 15 dp short.
  - The existing 1.5× test asserts only that the "?" is displayed, not the chip.
- Why it matters: the brief asks for the whole layout to work at font scales 1 and 2, and the chip is the control the user complained about in the first place.
- Fix, either of:
  - Move the "그래프" `HelpTip` out of the chip row (for example, beside the "km" unit line or the "누적" title). Then the chips keep the full width; three chips fit at 2× (about 342 dp on a 352 dp content width on the S22, about 328 dp on 360 dp phones, which is borderline).
  - Better: lay the chips out in a `FlowRow`.
- Also add a 2× check that `metric(ASCENT)`'s height equals `metric(DISTANCE)`'s.

**M4 — fix-before-merge (docs, trivial). One stale "상승" is left in CHANGELOG.**
- Where: `CHANGELOG.md:23`, "거리 합계와 주행·이동 시간·상승 칸".
- What is wrong: it should say "상승 고도 칸". The "변경" line 34 itself says the cells were renamed.
- Why it matters: the CHANGELOG contradicts itself in the release that makes the rename.
- Fix: one word.
- Also, if the plan is kept as the record: `docs/superpowers/plans/2026-10-31-stats-charts-help.md:97` still says `rememberSaveable`, but the ruling chose plain `remember`. Add the same "(실행 중 판정으로 고침)" note used for the tick and goal rulings. The spec and CHANGELOG already describe the ruled behaviour.

**M5 — stay-deferred (user decision). The goal pace is drawn on past weeks and years using today's goal.**
- Where: `StatsViewModel.kt:280-285`.
- What is wrong: the goal line appears for any [주] or [년] period, not only the in-progress one.
  - A user who set a 3,000 km goal this year sees a "목표" line on 2024, a year that had no goal.
  - Because `niceTop` includes the goal, a past low-mileage year is squashed into the bottom few percent of the plot.
- Why it matters: the line can mislead and flatten the actual data. It is also arguably useful as a reference, and the spec wording ("[년]에 연간 목표가 있을 때") allows it.
- Fix, if wanted: add `&& period.start <= today && today <= period.endInclusive` to the goal condition, and add a sentence in the spec.

**M6 — stay-deferred. The legend's line glyphs are read aloud.**
- Where: `CumulativeChart.kt:167-172`.
- What is wrong: the legend `Text` keeps its semantics, so TalkBack reads "━ 이번 달 ━ 지난달 ┅ 목표". The box-drawing glyphs are spoken as symbol names, or skipped, depending on the TTS.
- Why it matters: it adds noise right after the plot's own sentence.
- Fix: `Modifier.clearAndSetSemantics { contentDescription = "범례: 진한 선 $currentName, 회색 선 $previousName" + (goal?.let { ", 점선 목표" } ?: "") }`, or hide it, since the plot sentence already names both periods.

**M7 — stay-deferred. Time ticks are inexact below about 20 minutes.**
- Where: `CumulativeChart.kt:212-226`.
- What is wrong: `niceTop` uses decimal steps of hours, and then `tickTexts` rounds to whole minutes.
  - Example: a 6-minute maximum gives top 0.12 h = 7.2 min, labelled "0:07", and a middle of 3.6 min labelled "0:04".
  - The labels sit up to 0.5 min off their gridlines.
- Why it matters: it is cosmetic, and only for nearly empty periods.
- Fix, if ever: give TIME its own steps in minutes (2, 4, 6, 10, 12, 20, 30, 40, 60, then hours × the decimal steps).

**M8 — stay-deferred. The end bubble is large at font scale 2 in a fixed 160 dp plot.**
- Where: `CumulativeChart.kt:142-146, 243`.
- What is wrong: at 2×, `plotTop`/`plotBottom` leave about 108 dp of plot. A bubble for "12,345" is about 70 dp across and covers about two thirds of the plot height near the end of both lines.
- Why it matters: cosmetic, and only at the largest font. Nothing breaks.
- Fix, if ever: scale `PLOT_HEIGHT` with `fontScale` (for example `160.dp + 2 × (lineHeight − 16sp)`).

**M9 — stay-deferred. The [월] axis end name follows the longer period.**
- Where: `StatsFormat.kt:75`.
- What is wrong: viewing 2월 against 1월, the right end of the axis says "31일".
- Why it matters: it is accurate for the axis, since the grey line runs to January 31. But "2월 … 31일" can puzzle a reader.
- Fix, if wanted: write the current period's own last day ("28일") at `xOf(currentDays − 1)`, which is also where the goal pace ends.

**M10 — pre-existing, out of scope, not blocking. A long total value is clipped in the totals cells.**
- Where: `StatsScreen.kt:330-344` (`TotalCell`, `maxLines = 1`).
- What is wrong: a cell is about 88 dp of text on the S22. In [년]/[전체], "123시간 45분" (about 97 dp in titleMedium) is clipped at font scale 1. At 2× the "상승 고도" label also wraps to 2 lines, so the three cells have uneven heights.
- Why it matters: it predates this branch, but the rename touches the label.
- Fix, later: `Modifier.height(IntrinsicSize.Min)` on the row, and an autosize or 2-line value.

## Deferred-minor triage

| Deferred minor (ledger) | Triage | Why |
|---|---|---|
| T1: edge value dropped if 0.5 dp past the chart edge (12:30 on the first/last of 12 bars) | stay-deferred | Only 4–5-character times on Jan/Dec in [년]. Dropping is the specified overflow rule ("넘치면 안 씀"), and tapping the bar still shows it. |
| T1: chips squeeze before "?" at big font | **fix-before-merge** (M3) | The rename to "상승 고도" makes the last chip break at 1.5–2×, and it is the very control the user tested. |
| T1: axis names not bounded to chart edges (≈9 dp spill) | stay-deferred | The 16 dp list padding absorbs it, and nothing is clipped. |
| T1 ruling "stay as is": lone "?" row above the cells, "0.0" for tiny values, tip reappearing after scroll-back | stay (agree) | Cosmetic. Reappearing needs a scroll while the bubble is open, and the focusable popup makes that unlikely. |
| T2: finished-period previousText is same-day (brief wording) | **fix-before-merge** (M1) | It contradicts the comparison sentence and the legend name on 5 of 12 finished months. The fix is to reuse `comparison.previous`, which also fixes the leap-year off-by-one. |
| T2: layouts re-measured per recomposition | stay-deferred | Bars about 24–48 measures (more than `TextMeasurer`'s 8-entry cache, so they miss), cumulative 7. That is a few ms at most, only on a state change. The paths (366 points per line) are rebuilt in the draw phase only. Acceptable for a chip tap. |
| T2: test import order | stay-deferred | Style only. |

## Declined to judge

- **Real rendering at font scale 2 on the S22:** I can't run adb. M3 is computed from M3 chip metrics and the S22's about 384 dp width, not observed.
- **TalkBack traversal into the focusable popup, and inside the clickable GoalCard:** this needs a device. The test's use of `useUnmergedTree` for the goal tip suggests merging, but `IconButton` stays its own focus target.
- **Frame-time numbers for [년] chip changes:** no profiler was allowed. The reasoning above (draw-phase paths, cached measures, compute-dispatcher build) suggests it is fine.
- **Dark-theme contrast of the 40 % grey previous line:** no dark screenshot was provided.

## Assessment

**Ready to merge: With fixes.**

No Critical or Important issues were found. The two tasks fit together: units, rounding, colours and dispatcher use are consistent, and the core boundary cases are correct. Fix M1–M4 before merging:
- M1 and M2 are a few lines each, plus one test.
- M3 is a layout change plus one 2× assertion.
- M4 is one word, plus a plan note.

The other Minors can stay deferred, or be decided by the user (M5).
