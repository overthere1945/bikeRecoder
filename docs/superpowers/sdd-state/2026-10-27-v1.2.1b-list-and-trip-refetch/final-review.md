# Final review — V1.2.1 follow-up (plan 2026-10-27-v1.2.1b-list-and-trip-refetch)

Scope: whole branch range 2ee3c2b..1fd0dec (075f0a9 Task 2, 80bf76e Task 1, 1fd0dec Task 2 fix round 1), spec §6.8 / §6.9.

## Verdict

**Ready to merge after one docs-only FIX-NOW wave.**
- No Critical findings.
- No code defect was found.
- Two Important findings, both in `docs/verification/trips-field-test.md`:
  - stale test counts;
  - the acceptance wording of field-test item 11.
- The FIX-NOW list also has three cheap Minors: two doc lines and one wrong test comment.
- No device re-run is needed for this wave, because no device test file changes.

## Verification run (this review)

`./gradlew :core:test :app:testDebugUnitTest --rerun-tasks` gave BUILD SUCCESSFUL in 1m 53s, with 46 of 46 tasks executed. Counts from the JUnit XML:

| Suite | Classes | Tests | Fail | Skip |
|---|---|---|---|---|
| `:core:test` | 28 | 261 | 0 | 0 |
| `:routing-brouter:test` | 4 | 16 | 0 | 0 |
| `:app:testDebugUnitTest` | 61 | **655** | 0 | 0 |
| Total | 93 | **932** | 0 | 0 |

Notes on the counts:
- **routing-brouter** was not re-run here. Its XML is from 23:17 today. The module is untouched since 2ee3c2b, so 16 still holds.
- **DetailHeartRateTest** has 14 tests, up from 8 before Task 2 (+6).
- **CumulativeMapViewModelTest** has 27 tests (+1, `mapTapSelectsAndShowsCard`).
- 655 = 648 + 6 + 1.

Compiler warnings: the run printed `w:` lines only for GlyphIcon.kt and PostProcessorTest.kt. Both predate this branch (last touched in f0bb1b8). No changed file produces a warning.

Device `@Test` count (`git grep -c @Test -- app/src/androidTest`):
- 200 tests in 33 files (196 at 2ee3c2b).
- CumulativeMapScreenTest has 14, HistoryScreenTest has 6.
- The other four classes in the V1.2.1 table are unchanged: RoomPostStoreTest 14, HeartRateRowTest 4, SegmentDetailScreenTest 7, SettingsScreenTest 11.

## 1. Task 2 fix-round findings — all addressed

| Finding | Status | Evidence |
|---|---|---|
| Important 1: the gate is pure and tested | ✅ | `tripRefetchAction` at TripDetailScreen.kt:117 is used at :107. JVM `theRefetchButtonIsOfferedOnlyWhenHeartRateMayBeRead` (DetailHeartRateTest.kt:325) covers NO_PERMISSION, UNAVAILABLE and CHECKING → null, and GRANTED → invokes the callback. The vacuous device test was renamed `tripWithoutRefetchCallbackHasNoButton`. Its doc row points to the JVM test. |
| Minor 1: CHANGELOG contradicted itself | ✅ | CHANGELOG.md:15 now describes only the map-line highlight. CHANGELOG.md:18 holds the §6.8 split. They no longer contradict. |
| Minor 2: unused overload | ✅ | Only `HeartRateNotice(gate, access)` (HeartRateSection.kt:124) and the private `(access, card)` (:130) remain. A grep of `app/src` finds no 1-argument caller. |
| Minor 3: failure test | ✅ | `tripRefetchSurvivesAFailingSegmentRead` (DetailHeartRateTest.kt:297) wraps `RideStore.segments` so it throws. This exercises the VM's own `guarded` path, not PostProcessor's catch. It checks that the flag resets and that the next press reads both segments. `tripRefetchContinuesPastAFailingSegment` (:244) is kept for the per-segment chain. |
| Minor 4: exact totals | ✅ | `tripTotalsFollowRefetchedExtras` (:265) asserts 145 → 156, max 175, kcal 200 → 242 and avgByDate {143, 160}. I checked the arithmetic: (143·1 + 160·3)/4 = 155.75 → 156. |

## 2. Integration (trip refetch ↔ cumulative / segment screens)

- **The trip refetch writes extras the same way the segment button does.** `TripDetailViewModel.refetchHeartRate` (TripDetailViewModel.kt:200-213) calls `PostProcessor.refetchHeartRate(id)` for every segment with `endedAt != null`. That function holds the per-segment extras read-modify-write lock (PostProcessor.kt:51). It does not race the post-processing cycle.
- **The segment detail follows the same stored extras.** It observes them, so after a trip refetch, opening a segment shows the new values. Its own [다시 가져오기] (SegmentDetailViewModel.kt:142, SegmentDetailScreen.kt:164) is unchanged, as §6.9 requires.
- **Cumulative map: no coupling.** It reads simplified routes plus segment distance and time only, so a heart-rate refetch cannot change it.
  - The new row tap goes to `Routes.segmentDetail` (BikeNavHost.kt:548), which is the existing path.
  - Selection lives in the VM and a row tap no longer touches it. Coming back from the record keeps any map selection, which is consistent.
- **Access hoisting.** There is one `rememberHeartRateAccess` per trip screen (TripDetailScreen.kt:70). It feeds both the card or notice and the button gate, so `HeartRateGate.onGranted` (= `postProcessor::request`, BikeNavHost.kt:170) fires once.
  - `HeartRateAccessState.refresh` never resets to CHECKING on resume (HeartRateSection.kt:53-75), so the button does not flicker.
  - With a null gate the state is GRANTED. Production always passes the gate (BikeNavHost.kt:563).
- **No leftover references.** A grep for `selectRide`, `rideRowText` and the old `HeartRateNotice(` shows no callers of removed APIs.

## 3. Docs accuracy

The table below lists the stale lines in `docs/verification/trips-field-test.md`. The exact corrections are in FIX-NOW.

| Line | Now | Correct |
|---|---|---|
| 137 | "커밋 `c62df84` 위. 아래 표는 여행 기록 [다시 가져오기] 커밋 때 다시 센 수" | recounted on `1fd0dec` |
| 145 | "3m 53s" | the time of the full re-run in FIX-NOW 1 |
| 151 | app 653 | **655** |
| 152 | total 930 | **932** |
| 154 | "`DetailHeartRateTest`에 4개(…)" | **6개** (add `tripRefetchSurvivesAFailingSegmentRead`, `theRefetchButtonIsOfferedOnlyWhenHeartRateMayBeRead`), plus `CumulativeMapViewModelTest` +1 |
| 165 | CumulativeMapScreenTest 12, old §6.5 description, 12/12 | **14**, §6.8 description, **14/14** (progress.md: S22 14/14) |
| 169 | "여섯 클래스 54개", "196개(33개 클래스)" | **56개** (14+4+7+14+6+11), **200개(33개 클래스)** |

Field-test items:
- **Item 10 (line 191) is correct.** The row tap goes to the record; a map-line tap gives the highlight plus the card's [기록 보기 ›]; the period and 10 m rules are kept. It does not ask about the heading and hint, or about how many rows are visible. The deferred layout Minor (0.7/0.3) should be judged here, so FIX-NOW 4 adds those two checks.
- **Item 11 (line 192) is inaccurate. See Important 2.**

## Critical

None.

## Important

1. **Stale PC and device counts** in docs/verification/trips-field-test.md:137, 145, 151, 152, 154, 165, 169. This was deferred Important 2, now with the full numbers above. The doc presents them as XML-verified, so wrong numbers make the verification record untrustworthy.
2. **Item 11's pass criterion contradicts R7/R17** (trips-field-test.md:192). "끝나면 여행 합계(평균·최고·칼로리)와 날짜별 평균이 새로 읽은 값으로 바뀜" implies the totals always change.
   - By design (spec §6.9, R7, R17) they change only when a re-read has more samples, or fills empty calories. Otherwise the stored values are kept.
   - A tester refetching an already-complete trip would see no change and could mark it `[!]` wrongly.
   - "구간 하나가 읽히지 않아도 나머지는 바뀜" also cannot be set up in a field test. It is covered by the PC tests.

## Minor

- **FIX-NOW (cheap, docs and comments only):**
  - M1. trips-field-test.md:179 (the "6.4–6.5" bullet) still says that tapping a row highlights it and that the highlighted row has [기록 보기]. This is the same self-contradiction that was fixed in the CHANGELOG. Line 180 says it was replaced, but line 179 still reads as current behaviour.
  - M2. trips-field-test.md:180 gives the info-card example as "10월 25일 (토) · 1.59km · 15분". 2026-10-25 is a Sunday, and spec §6.8's example is "10월 25일 (일) · 1.58km · 12분". The code computes the weekday correctly, so only the example is wrong.
  - M3. CumulativeMapViewModelTest.kt:524 has the comment "A period without the ride: no card". `selectYear(2026)` keeps ride 2 in the period, so the null comes from "a period change clears the selection". This is Task 1 review Minor 3, still open.
- **Deferred or accepted (triage):**
  - M4. **List shows about 2 rows** (CumulativeMapScreen.kt:169, 0.7/0.3). Defer to the S22/S25 field check through item 10's new memo field. If it is too few, use 0.6/0.4, or make the heading the first LazyColumn item. Not a spec issue: §6.8 sets no ratio.
  - M5. **TalkBack reads the "›" glyph.** Defer to the a11y backlog. Fixing it with `clearAndSetSemantics` would break the device assertion at CumulativeMapScreenTest.kt:195, so the test must change and S22 must re-run. Not worth it in this wave.
  - M6. **The device test name `mapTapShowsTheCardAndTappingAgainHidesIt` overstates what it proves.** The JVM tests carry the rules. Defer: a rename needs a device re-run, and that has no value now.
  - M7. **Selected and unselected row contrast** (secondaryContainer vs surfaceVariant). Accept.
  - M8. **A trip refetch runs in `viewModelScope`, so leaving the screen mid-pass stops the remaining segments.** This matches the segment-level button (SegmentDetailViewModel.kt:143), and the next press or the automatic cycle covers the rest. Accept.
  - M9. **Empty (jitter) segments are re-read too.** This is per spec ("모든 구간") and consistent with `process`. Accept.
  - M10. **CHANGELOG.md:14 example "10월 25일 (토) · 1.59km · 15분" predates this branch.** It matches 2025's calendar, and V1.2.1's row is now two lines. This is optional. If you touch it, write "(최신부터 10색 … )와 지도 아래 주행 목록" without the one-line example.
  - Pre-existing compiler warnings (GlyphIcon.kt:18, PostProcessorTest.kt:790+) are out of scope.

## Regressions

None found.
- Full JVM suites pass: 932/932.
- Device: S22 CumulativeMapScreenTest 14/14 and HistoryScreenTest 6/6 after the fix (progress.md). The other device classes are untouched by the diff.
- The segment-detail and summary heart-rate paths (`HeartRateSection`, HeartRateSection.kt:95-117) are unchanged.
- The cumulative empty and settled branches, the `select` rules (CumulativeMapViewModel.kt `select`) and `choose` clearing are unchanged.

## FIX-NOW (one wave, docs plus one test comment; no device re-run)

1. **Re-run the documented PC command** `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`. Recount from the XML. Expected: core 28/261, routing-brouter 4/16, app 61/655, total 93/932. Then in trips-field-test.md:
   - line 137 → "실행일: 2026-10-27 (버전 1.2.1과 최종 리뷰 수정 뒤, 커밋 `1fd0dec` 위에서 다시 센 수입니다)";
   - line 145 → the new "BUILD SUCCESSFUL in …" time;
   - line 151 → `| \`:app:testDebugUnitTest\` | 61 | 655 | 0 | 0 |`;
   - line 152 → `| 합계 | 93 | 932 | 0 | 0 |`.
2. **trips-field-test.md:154**: replace "`DetailHeartRateTest`에 4개(`tripRefetchReadsEverySegment`, `tripRefetchRunsOnceWhileBusy`, `tripRefetchContinuesPastAFailingSegment`, `tripTotalsFollowRefetchedExtras`)가 늘었고, 누적 지도 목록·선 동작(6.8)의 JVM 테스트 변경도 이 실행에 함께 들어 있습니다" with "`DetailHeartRateTest`에 6개(`tripRefetchReadsEverySegment`, `tripRefetchRunsOnceWhileBusy`, `tripRefetchContinuesPastAFailingSegment`, `tripRefetchSurvivesAFailingSegmentRead`, `tripTotalsFollowRefetchedExtras`, `theRefetchButtonIsOfferedOnlyWhenHeartRateMayBeRead`), 누적 지도 목록·선 동작(6.8)으로 `CumulativeMapViewModelTest`에 1개(`mapTapSelectsAndShowsCard`)가 늘었습니다".
3. **Device table** (trips-field-test.md:165 and 169):
   - Line 165 → `| \`ui.cumulative.CumulativeMapScreenTest\` | 14 | 주행별 색·목록 머리 "이 기간 주행 n회"와 "누르면 기록을 봅니다"·줄(색 막대·날짜·거리·시간·›)을 누르면 기록으로(강조 없음)·지도 선 강조와 정보 카드 [기록 보기 ›]·[주 ▾]·[이번 주](기록 없는 주도 "5주 ▾")·[이번 달]·"이 기간에 기록이 없습니다" | 14/14 통과 |`.
   - Line 169 → "이 여섯 클래스 56개가 통과했습니다(`CumulativeMapScreenTest`는 6.8을 넣은 뒤, `HistoryScreenTest`는 여행 기록 [다시 가져오기]를 넣은 뒤 2026-10-27에 다시 실행). … 기기 전체 `@Test`는 지금 200개(33개 클래스)입니다. …". Keep the rest of the sentence.
4. **Item 10** (line 191): add after "…목록(날짜·거리·시간)이 맞음." the text "목록 위에 "이 기간 주행 n회"와 "누르면 기록을 봅니다"가 보임. 지도 선을 누르면 목록의 그 줄도 표시되고 보이게 스크롤됨." In the memo cell, add " / 한 화면에 보이는 줄 수 ___". This carries the deferred layout Minor M4.
5. **Item 11** (line 192): change the pass criterion to "여행 기록 화면의 여행 합계 카드에서 심박 줄 오른쪽 [다시 가져오기]를 누르면 도는 동안 버튼이 눌리지 않고, 끝나면 다시 눌림. 더 자세한 심박이나 비어 있던 칼로리가 새로 읽히면 여행 합계(평균·최고·칼로리)와 날짜별 평균이 바로 바뀌고, 새로 읽은 것이 없거나 더 적으면 기존 값이 그대로 남음(R7·R17). 앱이 멈추지 않음". Change the memo to "여행 ___ / 구간 수 ___ / 걸린 시간 ___초 / 합계 전→후 ___ (바뀌지 않았으면 그 이유: 이미 자세함 / 새 기록 없음)".
6. **Line 179** (the "6.4–6.5" bullet): replace "목록 줄이나 지도 위 선을 누르면" with "지도 위 선을 누르면". Replace "강조된 줄의 [기록 보기]는 그 구간 상세로 갑니다." with "(목록 줄을 누르는 동작과 [기록 보기]는 아래 6.8로 바뀌었습니다.)". Optionally drop the one-line row example "("10월 25일 (토) · 1.59km · 15분")", since rows are now two-line cards.
7. **Line 180**: change the info-card example to "10월 25일 (일) · 1.58km · 12분" (spec §6.8).
8. **CumulativeMapViewModelTest.kt:524**: change the comment to `// A period change (even one still holding the ride): no card.` Re-run `:app:testDebugUnitTest --tests '*CumulativeMapViewModelTest'` (27/27).
9. **Commit** as `docs: V1.2.1 field-test counts and item 10/11 wording; test comment`, with the model's Co-Authored-By trailer.
