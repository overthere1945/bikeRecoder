### Finding Verdicts
- **FIX-NOW 1: [주] chip number from the week itself (T3-1, T3-2)** — ADDRESSED
  - CumulativeSummary.kt `weekOf`: N = WEEKS.between(mondayOf(1st of month), start) + 1. This is the rule `weeks()` used before (week containing the 1st is week 1, Monday start). `weeks()` now calls `weekOf`, so label and order are unchanged. `WeekOption.number` is the same N.
  - Hand check: 2026-10-01 is a Thursday, so the Monday is 9/28. 10/26 is 28 days later, which gives 4+1 = 5, "10월 5주". For 12/28/2026 seen as 2027-01: the Monday of 1/1/2027 (Friday) is 12/28, so N = 1, "1월 1주 (12/28~1/3)". Seen as 2026-12 it is 5.
  - Test `weekOfAWeekWithoutRides` (CumulativeSummaryTest) asserts exactly these three cases, plus `weeksCarryTheirNumber` ([1,4]).
  - The state gets `week` from `period.weekStart` plus year and month (CumulativeMapViewModel.kt, `cumulativeStateOf`). It does not depend on whether the week has rides, and `weeks` (the menu) still lists only weeks with rides. `thisWeek()` supplies today's year and month (:167-169). `selectWeek` keeps the chosen year and month (:160-163).
  - `weekChipLabel` (CumulativeMapScreen.kt) returns `"${week.number}주 ▾"`, or "주 ▾" with no week. The `substringAfter`/`substringBefore` label parsing is gone. A grep of app/src/main finds none left, and no other `WeekOption(` constructor call sites are broken.
  - Tests: VM `thisWeekWithoutRidesStillHasItsNumber` (today 10/27, only ride 10/25, so periodEmpty, week = 10월 5주 with number 5, menu [4], selectWeek(10/19) gives 4, selectWeek(null) gives null). Device test `thisWeekWithoutRidesStillShowsItsNumber` expects "5주 ▾" and "이 기간에 기록이 없습니다" together and no "주 ▾".
  - I did not run the device test. The report says S22 12/12 passed. The test code is present and consistent with the diff.
- **FIX-NOW 2: `select()` KDoc and test (T3-3)** — ADDRESSED
  - CumulativeMapViewModel.kt KDoc now reads "A ride not listed lets go of the highlight too", matching the behaviour (:180-182 of c62df84).
  - `selectAndDeselect` now selects ride 1, asserts `selected == 1L`, then `select(99)` and asserts null. It distinguishes "ignored" from "lets go".
- **FIX-NOW 3: field test item 9 and counts (T3-?)** — ADDRESSED
  - Item 9's pass criterion and memo now say: the summary only re-reads up to 15 times; reopen the app or open the segment detail; for rides older than 24 h press [다시 가져오기] (10/25). There is a four-line "항목 9 확인 방법" block (including the late-calories LEAVE case) and a sentence added to the 6.6 explanation.
  - Counts, checked against the diff and against the current test-result XML (28 classes with 261 tests and 61 classes with 648 tests, 0 failures, errors or skips):
    - core 259→261 (+2 new tests, matching the 2 added)
    - app 647→648 (+1 new VM test; `selectAndDeselect` was only extended)
    - total 922→925 (261+16+648)
    - CumulativeMapScreenTest 11→12 (+1 device test)
    - the six-class total 51→52 (14+4+7+12+4+11 = 52)
    - device total 195→196
  - The run date and commit line is updated too.
  - I ran `./gradlew :core:test :app:testDebugUnitTest`. BUILD SUCCESSFUL. Tasks were UP-TO-DATE, so these are the implementer's results still on disk.

### New Breakage in the Fix Diff
None. Minor note only: with a week set but no year or month in the period, `week` is null and the chip falls back to "주 ▾". The VM never builds such a period (the fix report says the same).

### Out-of-Scope Observations
None.

### Verdict
**Fix round:** All findings addressed, no new Critical/Important breakage
