# Final whole-plan review: 주행 사용성 개선 (d668ec4..88c9163, Tasks 1–9)

## Verdict

**Ready to merge after one small fix wave.**

- No Critical findings.
- One Important finding, which the controller had already chosen: the guidance-screen notice covers [전체 경로].
- The rest of the FIX-NOW list is cheap polish and test hardening.
- The integration across Tasks 6, 7 and 8 holds up:
  - notice routing between the record and guidance screens
  - the U1 arrival
  - the attach → split → begin order
  - the U2 safety net
- I found no regression to V1.0.0 or V1.1.0 behaviour.

## Verification I ran (U3: no connected tests, no device attached)

`./gradlew :core:test --rerun :routing-brouter:test --rerun :app:testDebugUnitTest --rerun :app:assembleDebugAndroidTest` gave **BUILD SUCCESSFUL**. I counted the results from the `build/test-results` XML:

| Suite | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `:core:test` | 177 | 0 | 0 | 0 |
| `:routing-brouter:test` | 16 | 0 | 0 | 0 |
| `:app:testDebugUnitTest` | 378 | 0 | 0 | 0 |
| Total | 571 | 0 | 0 | 0 |

- The total matches `docs/verification/recording-field-test.md` (571 tests, 65 classes).
- The device test code compiles: 20 classes, 86 `@Test`, which also matches the doc.
- The only output noise is the existing JDK `sun.misc.Unsafe` warning from `datastore-preferences-external-protobuf`.
- Other checks:
  - The working tree is clean.
  - All 10 commits have a `Co-Authored-By` trailer.
  - `3.json` differs from `2.json` only by the new `favorite_place` table; the other five tables are byte-identical. So AutoMigration 2→3 only adds a table.

## Cross-task integration (checked, sound)

- **Notice routing.**
  - Every notice is posted *after* the guidance has gone Idle:
    - cancel: `NavigateViewModel.onEndChoice` calls `close()` and then `announce`
    - U1: `arriveKeepingRecording` sets Idle and then calls back
  - So the guidance screen's filter (`isGuiding`) drops these notices, and the record screen (`ride is Active`) shows them exactly once.
  - The split notice is posted while the plan screen is showing. It waits in `ride.notice` and is shown by the guidance screen once the new guidance is Starting or Active.
  - The double-show during a transition, which the Task 8 review raised, cannot happen in practice. During attach the RECORD entry has already been popped (`openPlan` uses popUpTo MAIN).
- **U1 against the V1.1 rule.**
  - `attachedToRecording = kmSource != null` is computed at `begin`.
  - In a guidance-first start, the recording starts only on Active (`followNavigation`), after `begin`, so the guidance stays unattached and the V1.1 rule applies.
  - A routing failure starts no recording, so a retried `begin` stays unattached too.
  - A START_STICKY resume after a kill begins with no recording, so the V1.1 rule applies.
  - [이어서 안내] on the main banner is hidden while riding (`MainViewModel.kt:81`), so it cannot attach a guidance of another trip to a live recording.
- **Attach order (TripStartViewModel.kt:174–184).** The order is closeGuidanceOf → profile/stops (only if they differ, so offline maps are kept) → split awaited → Ready → `begin` on the NAVIGATE screen.
  - The split runs while the guidance is closed, so Task 6's "arrival during the split window" edge is unreachable in the app.
  - `kmSource` is set once.
- **Foreground service.** During a split the ride goes Active → Starting → Active, and `NavNotifications.rideNotice` is non-null for Starting. So `NavigationService` never sees null and never stops in the middle of a split.
- **Threading (R12).**
  - `closeGuidanceOf` and `splitSegment` are called from `viewModelScope` (Main.immediate), which is the same thread as `rideScope`.
  - The split's finish and its new run are launched in the ride scope, so leaving the plan screen mid-split still completes the split.
- **Back stack.** RECORD → SEARCH → PLAN (popUpTo MAIN) → NAVIGATE (openOnMain), and the end of guidance goes back to RECORD. If the user backs out of the plan, they land on MAIN, and the riding banner leads back to RECORD. This is acceptable.

## Spec coverage

| § | Item | Code | Tests |
|---|---|---|---|
| 1.1 | Chips on main and search, tap → `setDestination` + plan, long-press menu (U4) | ✅ | JVM `PlanViewModelTest`, `FavoritesViewModelTest`; device `FavoriteChipsTest` pending |
| 1.2 | [즐겨찾기에 저장] in all three sheets; [집]/[회사]/직접 입력; replace confirmation; duplicates; 20-item limit | ✅ | `FavoriteRepositoryTest`, `FavoritesViewModelTest`; device `FavoriteDialogsTest` pending |
| 1.3 | Room v3, AutoMigration 2→3, sort order | ✅ | `FavoriteMappingsTest`; device `RideDatabaseMigrationTest` (2→3) and `RoomFavoriteStoreTest` pending |
| 2 | `imePadding`, drag hides the keyboard | ✅ | device `SearchScreenTest` pending |
| 3 | Recording button set, cancel without confirmation plus voice and notice, [전체 경로] on the map, V1 set when not recording | ✅ | `GuidanceButtonsTest`, `NavigateViewModelTest`; device `GuidanceButtonRowTest` pending |
| 4.1 | [목적지 정하기] → search | ✅ | device `RecordScreenTest` pending |
| 4.2 | Attach to the recorded trip, no new trip, U2 | ✅ | `TripStartViewModelTest` |
| 4.3 | Three-way setting (default ASK), dialog, continue, split with the location bridge, voice and notice | ✅ | `TripStartViewModelTest`, `RideControllerTest`, `RideNavigationTest`; device `SettingsScreenTest`, `TripStartDialogsTest` pending |
| 5 | Plan-map long-press sheet, shared `PlaceLookup` | ✅ | `PlaceLookupTest`, `PlanViewModelTest`; device `PlanScreenTest` pending |
| 6.3 | Field checklist (4 items + U1) | ✅ in doc | pending (ride) |

Interpretation, LEAVE: §4.1 says "지도 길게 누르기도 사용할 수 있다". From [목적지 정하기] the search screen has no map, so map long-press is available on the plan map after a destination is picked, or on the main map. This is the plan's documented decision (plan line 336).

## Findings

### Critical
None.

### Important
1. **The guidance-screen notice blocks [전체 경로] and the page dots for about 4 s** (`NavigateScreen.kt:118`, `:257-261`). This is the controller's item (a), and I confirm it.
   - The bottom column under the pager is about 230 dp tall when recording: padding, page dots at 48 dp, the panel whose cancel label wraps in its 1/3-width button, and padding.
   - A snackbar whose bottom sits 220 dp above the nav bar therefore lands on the map's bottom edge. That is where the BottomEnd [전체 경로] button (`:337-346`) and the page dots are.
   - The M3 Snackbar surface consumes the touches. The magic number also breaks with font scale. The fix is FIX-NOW 1.

### Minor (new in this review)
2. **A split resets the recording's auto-pause chip to the setting's default.**
   - `RideController.kt:405`: `RecordingSession(now, current.autoPauseEnabled, …)` is used for the new segment of `splitSegment` (`:307`).
   - Failure case: the rider turned the chip off for this ride, then picks a new destination with "새 구간으로 나누기". Auto-pause silently comes back on mid-ride.
   - Fix: FIX-NOW 3.
   - A manual pause at split time also resumes recording. LEAVE: the spec is silent, and the rider sees [일시정지] again.
3. **A notice can surface on a later recording.**
   - `RideController.fail(r)` (`:714`) does not clear `_notice`, and only `showSummary` does.
   - The record screen shows a notice only while the ride is Active.
   - Failure case: an `announce` happens while a start is in progress, the start then fails, and the next recording to become Active in the same process shows the stale "안내를 마칩니다. 기록은 계속됩니다". This is Task 6's deferred "fail() doesn't clear notice", promoted because it crosses into Task 8's screens.
4. **The serial warning is incomplete outside the new section.**
   - `docs/verification/navigation-field-test.md:32` still shows `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:connectedDebugAndroidTest`. This is the controller's item (b), and I confirm it.
   - **Additional:** `docs/superpowers/plans/2026-10-12-recording-core.md:24, :540, :1025, :1030` give `connectedDebugAndroidTest` commands on `R3CY903ZYQV`.
   - Plans are instructions that agents execute. Re-running any of those steps would uninstall the app on the user's S25 and delete their ride data.

## Triage of the deferred minors in progress.md

| Task | Item | Decision | Reason |
|---|---|---|---|
| 1 | Name length counts UTF-16 units (emoji = 2) | LEAVE | Only emoji-heavy names are affected, and the dialog's `take(20)` is consistent with the check |
| 1 | Duplicate check is case-sensitive | LEAVE | Names are Korean; case rarely matters |
| 1 | Extra coverage (WORK replace, rename HOME throws, rename >20, id tie-break, lock after IOException) | LEAVE | The behaviour is simple and verified by reading |
| 1 | `twentyIsTheLimit` could be split | LEAVE | Test style only |
| 2 | `observeAll` re-emission untested | LEAVE | This is Room's behaviour; the device `RoomFavoriteStoreTest` observes it |
| 2 | `valueOf` throws on an unknown kind | LEAVE | Same as `RoomTripStore`; `FavoritesViewModel` catches it and shows an empty list |
| 3 | `MainViewModel` pressed-mirror untested | LEAVE | The existing `MainViewModelTest` long-press tests pass unchanged through the mirror |
| 3 | Point-equality stale guard not exercised | LEAVE | A one-line guard |
| 3 | Address failures not logged | LEAVE | Same as V1.0.0; the coordinate fallback is visible |
| 3 | Duplicate catch (`PlaceLookup` + `addressOrNull`) | LEAVE | Harmless redundancy |
| 4 | A failed delete closes the dialog silently | LEAVE | A local Room delete failing is near-impossible; it is logged and the chip stays |
| 4 | Untested failure branches / write guard | LEAVE | Coverage only |
| 4 | ~16 dp blank gap on search with no favorites | RESOLVED | Fixed in 55f0aa3 (padding only in the search slot; device test added) |
| 4 | Chip shadow clipped by `horizontalScroll` | LEAVE | Cosmetic; check in the device pass |
| 4 | Chips <48 dp, no `Role.Button` | FIX-NOW (7) | One modifier each. Riders tap with gloves, and the project already enforces 48 dp for the page dots |
| 4 | Chip row may capture map drags on main | FIX-NOW (7) | The `fillMaxWidth` row swallows map pans and long-presses across the empty strip; dropping it costs nothing |
| 6 | F1 assertion in the U1 test cannot fail | FIX-NOW (5) | `NavigationControllerTest.kt:562` asserts unmuted without muting first. Muting first also tests the reason for the notice: no sound, but the text still shows |
| 6 | `splitSegment` returns null on a failed new start | LEAVE | `attach` decides from the observed state, not the return value (Task 7 review) |
| 6 | `fail()` doesn't clear the notice | FIX-NOW (4) | See Minor 3 |
| 6 | `announce` during Starting ignores the voice setting | LEAVE | Starting lasts only for `prepare`'s DB reads (no GPS wait), so the window is milliseconds |
| 6 | Split coverage gaps | LEAVE | The core paths, stop during split, and failed new start are covered |
| 6 | `onArrivedKeepingRecording` is a single assignable callback | LEAVE | It is assigned only in `RideController.init`, and `container.ride` is a lazy singleton |
| 7 | Split silently skipped if a stop/split is in flight | LEAVE | Needs [정지] pressed at the same instant as [안내 시작] |
| 7 | A failed split leaves the plan applied and drops the save notice | LEAVE | A narrow failure path; the user sees the error |
| 7 | `askedRecording` stale on CONTINUE/SPLIT | LEAVE | Harmless: `answerRecording` requires `AskRecording` |
| 7 | Blank-line nits in `TripStartViewModelTest` | LEAVE | Style only |
| 7 | CONTINUE window (the recording ends before `begin`) | LEAVE | Accepted: the user ended the recording, so the U1 condition is literally false |
| 7 | Split on-screen notice not tested end to end | LEAVE | `RecordViewModelTest.aSplitShowsItsNoticeOnTheRecordScreen` plus the routing argument above |
| 8 | Guidance snackbar at a fixed 220 dp | FIX-NOW (1) | Important 1 |
| 8 | Record-screen 120 dp offset | LEAVE (device check) | By layout estimate (bottom column ≈128 dp, summary line ≈52 dp) a one-line snackbar sits over the summary line and clears [목적지 정하기]. Look again only if it overlaps at large font scale |
| 8 | `NoticeSnackbarHostTest` "same notice again" step is a no-op | FIX-NOW (6) | It assigns an equal value, so no state changes and the step asserts nothing |
| 8 | No test for `OVERVIEW_ON_MAP` | LEAVE | Needs a NavigateScreen-level device test; add one in the device pass |
| 8 | Cosmetic double-show during a transition | LEAVE | Unreachable in practice (see Cross-task integration) |

## FIX-NOW list (one wave, one implementer)

All items must keep `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` green. Device classes stay "기기 검증 대기" (U3: only `ANDROID_SERIAL=R5CT43TFS9A`, never `R3CY903ZYQV`).

1. **Anchor the guidance notice to the map** (`NavigateScreen.kt`).
   - Remove the screen-level `NoticeSnackbarHost` (`:256-261`) and `NOTICE_BOTTOM_PADDING` (`:117-118`).
   - Pass `notice: ScreenNotice?` and `onNoticeShown: (Long) -> Unit` into `ActiveContent`.
   - Inside the map page `Box` (`:326`), after the [전체 경로] button, add `NoticeSnackbarHost(notice, onNoticeShown, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))`. The 64 dp clears the 12 dp + 48 dp BottomEnd button.
   - Notices that arrive while the screen is Starting wait in `ride.notice` and show once Active, which is acceptable.
   - Update the KDoc ("The recording's notices sit above the bottom panel").
2. **Fix the serial in the docs** (`docs/verification/navigation-field-test.md:32`).
   - Change the command to `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest`.
   - Add the warning line from `recording-field-test.md:195`: "`connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다. connected 테스트는 `R5CT43TFS9A`에서만, 실제 기록이 있는 `R3CY903ZYQV`에는 `installDebug`만 씁니다".
   - Add one sentence saying the results table below was recorded on the S25 before ruling U3. The section heading says S25, so the history must stay truthful.
   - In `docs/superpowers/plans/2026-10-12-recording-core.md`, replace the serial in the four connected commands (`:24`, `:540`, `:1025`, `:1030`) with `R5CT43TFS9A`, and add the same warning under `:24`.
   - Check: `grep -rn 'R3CY903ZYQV' docs | grep connectedDebugAndroidTest` returns nothing.
3. **Keep the auto-pause chip across a split** (`RideController.kt`).
   - Give `Run` an `autoPause: Boolean? = null`, and add it as a parameter of `launchRun`.
   - In `splitSegment`, capture `r.session!!.autoPause` before `finish` and pass it to `launchRun` (`:307`).
   - In `prepare` (`:405`), use `r.autoPause ?: current.autoPauseEnabled`.
   - Add a `RideControllerTest`: settings default `true`, `setAutoPause(false)`, `splitSegment()`, then assert the new segment's `RideUiState.Active.autoPause == false`.
4. **Clear the notice when a start fails** (`RideController.kt:714`).
   - In `fail(r)`, set `_notice.value = null`, next to `run = null`.
   - Add a `RideControllerTest`: `announce` while Starting with a start that fails (trip not active), then assert `notice.value == null`.
5. **Make the F1 check in the U1 test real** (`NavigationControllerTest.kt:542-565`).
   - Start with `begin` and `awaitActive()`, then call `setMuted(true)` before the arrival.
   - Keep `assertFalse(voice.muted)` after the arrival.
   - Assert `arrived == listOf("목적지에 도착했습니다")`: the screen text still comes when muted.
   - Adjust the spoken-arrival count to whatever the fake voice does when muted.
6. **Make the repeat step real** (`NoticeSnackbarHostTest.kt:42-45`).
   - Set `notice.value = null`, advance the clock, then set `ScreenNotice(1, …)` again.
   - Assert `shown == listOf(1L)`.
   - This only needs to compile now; it runs in the device pass.
7. **Chip touch target and map gestures** (`FavoriteChips.kt`).
   - `:51`: drop `.fillMaxWidth()` from the row. It still scrolls sideways when it overflows, and the empty strip on the main map no longer swallows map pans or long-presses.
   - `:78`: add `role = Role.Button` to `combinedClickable`.
   - `:76`: add `Modifier.minimumInteractiveComponentSize()` (before `combinedClickable`) to reach 48 dp.
   - Check that `FavoriteChipsTest` still compiles.
