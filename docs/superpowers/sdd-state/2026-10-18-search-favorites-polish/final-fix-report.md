# Final fix wave report — 검색·즐겨찾기 보완

Status: DONE_WITH_CONCERNS. The concern is that the manual device checks are still open; see Concerns.
Commit: `bb70402` app: final fixes — register chips wait for favorites, double-tap guards, device test fixes (trailer: Co-Authored-By: Claude Opus 5.5)

## FIX-NOW 1: no register chips until favorites have loaded (Important)

- `FavoritesViewModel.kt:75-81`: new `favoritesLoaded: StateFlow<Boolean>`. Its private backing field is declared before `favorites`, because the eager `stateIn` collection can write to it during construction.
- `FavoritesViewModel.kt:85`: `onEach { loaded = true }` is placed before `catch`.
- `FavoritesViewModel.kt:89`: `catch` also sets loaded to true. The type of `favorites` is unchanged.
- `BikeNavHost.kt:176`: collects `favoritesLoaded`.
- `BikeNavHost.kt:264-271`: the main chips get the register lambda only when the list has loaded, and `null` before that. Before the first load an empty list therefore draws nothing, which is the pre-plan behaviour.
- Tests (`FavoritesViewModelTest`):
  - `favoritesAreNotLoadedUntilTheFirstList` (:274) uses `SlowFavoriteStore`, a gated `MutableSharedFlow`. It checks false before the first emission, then true.
  - `aFailingFavoritesFlowCountsAsLoaded` (:287): the catch path gives true, an empty list and 1 warning.
- TDD:
  - RED: `./gradlew :app:testDebugUnitTest --tests '...FavoritesViewModelTest'` failed with `Unresolved reference 'favoritesLoaded'`, as expected.
  - GREEN: the same command ran 18 tests with 0 failures.

## FIX-NOW 2: double-tap guards (existing `isCurrent()` pattern)

| Where | Line | Guard |
|---|---|---|
| `composable(Routes.MAIN) { entry -> … }` | `BikeNavHost.kt:229` | defines `isCurrent` |
| Main search bar `onOpenSearch` (also covers banner NEXT_DESTINATION) | `:237` | guarded |
| Register chip lambda | `:267` | guarded |
| `composable(SEARCH_PATTERN) { entry -> … }` | `:309` | defines `isCurrent` |
| Register pick: `onRegister = { place, kind -> if (isCurrent()) favoritesViewModel.saveAs(place, kind) }` | `:320` | guarded |
| Plan `onAddStop` (optional item, done) | `:324`, `:332` | guarded |

- Verification: it compiles (`:app:assembleDebugAndroidTest` succeeded). There is no automated test, as the review expected.
- The manual double-tap check on the phone has not been done. It is recorded as 기기 검증 대기.

## FIX-NOW 3: FavoritesViewModelTest register-path tests

- (b) `saveAsReplaceStoreFailureShowsSaveFailedWithoutAnEvent` (:230):
  - Setup: an existing home, `saveAs` → `ConfirmReplace(register = true)`, then `failWrites = true` and `confirmReplace()`.
  - Checks: `Message("즐겨찾기를 저장하지 못했습니다")`, no event, the home point unchanged, and 1 log line.
- (a) `saveAsReplaceDismissedTellsNothing` (:251): `ConfirmReplace(register = true)` → `dismiss()` leaves the dialog null, sends no event, and the store is unchanged.
- These tests cover existing code, so they passed on the first run.
- Mutation check: I temporarily changed the register `onFailure` in `confirmReplace` to `null`. The run then reported "18 tests completed, 1 failed", and I restored the file.

## FIX-NOW 4 + A: docs and device results (`docs/verification/recording-field-test.md`)

- `:154-161`: PC counts.
  - `:app` went from 402 to 406 tests, and the total from 595 to 599. Both come from the 42 XML files of the run below.
  - The growth from the 2026-10-12 baseline is now +98 (`:app` +97).
- `:172`: the FavoritesViewModelTest row now reads 18 (8 → 18, +6 보완, +4 final fix).
- `:201-213`: the stale "no phone" sentence is replaced. The new text gives:
  - date 2026-10-18, serial R5CT43TFS9A (SM-S908N, Android 16), the 12 classes run one at a time by the user, and 57/59;
  - the two failures and their causes;
  - the 9 classes not run (42 tests: RoomRideStoreTest, RoomTripStoreTest, GpxLocationSourceTest, NavigationScenarioTest, MapLibreOfflineControllerTest, RideScenarioTest, HistoryScreenTest, SettingsSegmentActionsTest, EndNavigationDialogTest), which stay 기기 검증 대기.
- `:221-232`: the 결과 column is filled for all 12 classes, with the user's numbers and the re-runs.
- `:236`: the bare `navigate("search")` check from the main search bar, plan [+ 경유지 추가] and record [목적지 정하기], and the double-tap check, are both marked "not yet checked on the phone" (기기 검증 대기).

### A.1 SettingsScreenTest.showsSegmentsMapSizeAndAttribution

- `SettingsScreenTest.kt:124`: the step is now `onNodeWithText("설치됨 · 2 KB").performScrollTo().assertIsDisplayed()`.
- I checked the rest of that test. Every other display assertion already scrolls first. The `"사용 중인 저장 공간 5.0 MB"` wait only checks that the node exists, not that it is displayed, so it is fine.
- Device: 7/7.

### A.2 SearchScreenTest.resultsEndAboveTheKeyboard: a test artifact, not an app bug

Root cause (systematic debugging):
1. Source: foundation 1.12.1, `Scrollable.kt:984-990`. `ScrollingLogic.semanticsScrollBy` dispatches every delta with `source = NestedScrollSource.UserInput`.
2. Source: ui-test 1.12.1, `Actions.kt:233-266`. For a lazy list, `performScrollToNode` scrolls one viewport at a time through the semantics `ScrollBy` action.
3. As a result, `rememberHideKeyboardOnDrag` (`SearchScreen.kt`, `onPreScroll` with `UserInput`) treats the test's scroll as a user drag: it hides the IME and clears focus.
4. Device evidence: a temporary log ran on R5CT43TFS9A with a single method, and I removed it afterwards. It showed:
   - Before the scroll: `imeBefore=1008`, `resultsBefore=…1308.0`.
   - After the scroll: `imeAfter=0`, `resultsAfter=…2181.0`, `keyboardTopAfter=2316.0`.
   - So the keyboard went down, the list grew to full height, and the `keyboardTop` of 1308 captured earlier was stale. The last row at 2178 is inside the full-height list, above the navigation bar.
5. Real user behaviour is correct and meets spec §2:
   - While the keyboard is up, the results container ends above it (the first assertion passed on the device).
   - Any scroll that keeps the keyboard up therefore keeps the last row above it.
   - A finger drag, or an accessibility scroll, hides the keyboard by design, and the list then fills the screen.
   - There is no scroll path where the last result ends under a visible keyboard.

Fix:
- `SearchScreenTest.kt:148-153`: `performScrollToNode(hasText("장소 30"))` became `performScrollToIndex(29)`. `ScrollToIndex` → `LazyListState.scrollToItem`, `LazyLayoutSemantics.kt:213`, with no nested-scroll dispatch, so the keyboard stays up.
- The test then asserts that "장소 30" is displayed, that its bottom is ≤ keyboardTop + 1, and that the IME is still up. The "IME still up" assertion is now meaningful.
- A comment in the test explains the reason.
- `SearchScreen.kt:63-66`: corrected the KDoc of `rememberHideKeyboardOnDrag`. The old "code-driven scrolls leave it up" was inaccurate: semantics/accessibility scrolls hide it, and only state-driven `scrollToItem` scrolls leave it up.
- Device: 10/10.

## B: accuracy wording (no radius added)

- `CHANGELOG.md:18`: "장소 검색 정확도 순(현재 위치까지의 거리 표시는 그대로)".
- `docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md:87` §7.2, amended in place: "현재 위치(x, y)는 거리 표시를 위해 계속 보낸다."
- `recording-field-test.md:259`, checklist item 6: "거리 표시는 그대로". It also notes that x/y sent without a radius only feed the distance display, so nearby places may not come first; a far place on top for a common name should go in the memo.
- `recording-field-test.md:197`: the KakaoLocalClientTest row had the same "근처 우선 유지" phrase and now says "거리 표시용 현재 위치 x·y는 계속 보냄".
- `KakaoLocalClient.kt:70`: the existing comment did not claim a bias. I added one clarifying line: "x/y (no radius) only fill each place's distance; they do not favor nearby places." No behaviour changed.
- After the change, `git grep 근처` outside the plans and the workspace finds nothing.

## Verification (final)

- `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest </dev/null`: BUILD SUCCESSFUL, 42 classes, 406 tests, 0 failures, 0 errors, 0 skipped.
- Device R5CT43TFS9A, one class per invocation, `ANDROID_SERIAL=R5CT43TFS9A` and `</dev/null`. Before each run I checked `adb devices` and sent the wake key, and `isKeyguardShowing=false`.

| Class | Result |
|---|---|
| `ui.search.SearchScreenTest` | 10/10 |
| `ui.settings.SettingsScreenTest` | 7/7 |
| `ui.favorite.FavoriteChipsTest` | 7/7 |
| `ui.search.SearchRegisterModeTest` | 4/4 |

- I also ran the single diagnostic method `SearchScreenTest#resultsEndAboveTheKeyboard` twice. One run failed to compile; the other produced the evidence above.
- Nothing ran on R3CY903ZYQV. No phone settings were changed.

## Concerns

- These manual checks have not been done on the phone: the double-tap guards (FIX-NOW 2) and the bare `navigate("search")` from the three entry points. Both are recorded as 기기 검증 대기 in the doc (:236).
- After this commit, the other 8 classes from the user's run were not re-run. None of them touch the changed files (BikeNavHost and FavoritesViewModel are not used by device tests).
- The 9 classes that were never run in this wave (42 tests) are still 기기 검증 대기.
- The PC table's `:core` and `:routing-brouter` counts are from the earlier `--rerun-tasks` run. Those modules are unchanged, and only `:app` was re-run, as instructed.
