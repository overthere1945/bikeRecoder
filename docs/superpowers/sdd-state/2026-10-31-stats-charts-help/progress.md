# SDD ledger — plan: docs/superpowers/plans/2026-10-31-stats-charts-help.md
Spec: docs/superpowers/specs/2026-10-31-summary-stats-design.md (§3.3, §5); changes approved by user in chat 2026-10-31 (B안 + ? 설명). Scan: Task 2 consumes Task 1's StatMetric/barShortText/metricUnitText/HelpTip/StatsHelp — consistent; Task 1 Step 6 graph copy is replaced in Task 2 Step 4 — intended. Clean.
Task 1: dispatched (BASE 848888c, implementer sonnet)
Task 1: done (c70e58f) DONE_WITH_CONCERNS — JVM core 379 / app 959 green; S22 StatsScreenTest 27/27, GoalDialogTest 7/7
Ruling: open help tip state is plain remember (not rememberSaveable) — Review Focus 5 wants rotation/tab leave to close it; saveable would reopen it — cost if wrong: none
Ruling: with an open bubble, a finger tap on another ? only closes the first (popup takes the outside tap) — standard popup behaviour — cost if wrong: one extra tap
Ruling: 13+ bars — tallest/selected value may span up to 3 columns (strict one-column never fits at 24 bars) — cost if wrong: none
Note: empty bikerecoder.db in repo root was made by the controller's failed zsh backup command at 08:28 — deleted
Task 1: review → Needs fixes (2 Important: ? squeezed to 0 dp beside long text/large font; axis names collide in monthly 전체)
Task 1: fix round 1/5 dispatched (resume implementer): I1 weight(1f, fill=false) + fontScale 1.5 test; I2 measured prioritised axis names; minors fixed with it: measured non-overlap values for ≤12 bars, bubble scroll, bar clearAndSetSemantics
Ruling: stay as is — lone ? row above cells (brief says 위), tiny values "0.0", tip reappearing after scroll-back — cosmetic — cost if wrong: small polish
Task 1: fix round 1/5 done (feaf298) — JVM core 379 / app 966; S22 StatsScreenTest 28/28, GoalDialogTest 7/7
Task 1: re-review → all 5 ADDRESSED, no new Critical/Important. minor (deferred): edge value dropped if 0.5 dp past chart edge (12:30 on first/last of 12 bars); chips squeeze before ? at big font; axis names not bounded to chart edges (≈9 dp spill, cosmetic)
Task 1: complete (commits 848888c..feaf298, review clean)
Task 2: dispatched (BASE feaf298, implementer sonnet)
Task 2: done (21e8d18, c3663f1) DONE_WITH_CONCERNS — JVM core 387 / app 980; S22 StatsScreenTest 34/34; screenshot v140-stats-cumulative.png
Ruling: tags CUMULATIVE_CHART/PLOT/LEGEND (CUMULATIVE already used by the map entry); previousText added; end value 0 shows "0"/"0:00"; onPrimary end text — accepted
Ruling (controller, for fix round): screen-reader sentence uses metricText ("10시간 7분"), not "{short}{unit}" ("10:07시간") — plan wording was mine and reads badly — cost if wrong: none
Ruling (controller, plan correction): y top tick = smallest of {1,1.2,1.5,2,2.5,3,4,5,6,8}×10^k ≥ max×1.05, mid = top/2 — the plan's 1·2·5 rule left a 186 km line in the lower 40% (top 500) — cost if wrong: tick numbers less round
Ruling (controller, plan correction): goal pace line reaches the goal at the last day of the CURRENT period, not the longer axis end — otherwise the pace is days off in a short month — cost if wrong: none
Task 2: review → Approved pending controller items a–c (confirmed). minor fixed in round: first-day dot, h:mm time ticks, duplicate tick labels, middle axis name at its real day. minor (deferred): finished-period previousText is same-day (brief wording), layouts re-measured per recomposition, test import order
Task 2: fix round 1/5 dispatched (resume implementer): a–c + minors + docs
Task 2: fix round 1/5 done (8395813, 9c8d867) — JVM core 387 / app 984; S22 StatsScreenTest 34/34; plan amended with the tick/goal rulings (cc73c61)
Task 2: re-review → all ADDRESSED, no new breakage
Task 2: complete (commits feaf298..e64e8fd, review clean)
Final review: dispatched (opus, 848888c..e64e8fd)
Final review → With fixes (0 Critical/Important). Fix-before-merge minors M1 finished-period screen-reader previous value, M2 thousands grouping in barShortText, M3 chip row wraps "상승 고도" at large font, M4 CHANGELOG "상승 칸" + plan rememberSaveable line
Ruling: M5 (goal pace on past periods uses today's goal) stays deferred — a past goal isn't stored; showing today's goal is a reasonable reference — cost if wrong: a past week's dashed line uses the current goal. M6–M10 and ledger minors stay deferred (cosmetic)
Final fix wave: dispatched (resume Task 2 implementer, BASE e64e8fd)
Final fix wave: done (af63681, f1f3ec2) — JVM core 387 / app 985; S22 StatsScreenTest 36/36
Final fix wave: re-review → all ADDRESSED, no new breakage. S22 GoalDialogTest 7/7, MainTabsTest 11/11, FeedScreenTest 20/20; S22 installDebug
