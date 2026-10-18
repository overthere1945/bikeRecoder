# Final whole-plan review — 검색·즐겨찾기 보완 (V1.1.0), e7833fc..adb9fb2

Scope: commits d1e8498, d1a31dc, 91ea86a, 5e4ba4b, adb9fb2. I reviewed statically, from the combined diff plus `git show adb9fb2:<path>` for named risks. I did not run gradle, did not use adb and did not change the working tree, because device tests were running in this checkout. Line numbers refer to files at adb9fb2.

## Verdict

**Approve after one small fix wave.** Spec §7.1–§7.4 is fully covered and rulings V1, V2 and U1–U4 are respected. The existing search, plan and record flows are not broken. I found no Critical issues and one Important issue (a cold-start flash of the register chips). The controller's double-tap candidate is **confirmed**, and it should cover one more spot (see FIX-NOW 2). The docs are accurate except for one stale sentence about the device run.

## Spec §7 coverage

- §7.1: `CANCEL_GUIDANCE("안내 취소")`. It is an OutlinedButton like [음소거], with `maxLines = 1` (GuidanceButtons.kt:20, :64-67). The behaviour is unchanged and only the comments were updated. ✅
- §7.2: `sort=accuracy`, and x/y are still sent when a location is known (KakaoLocalClient.kt:66-70). With no location, no sort is sent, the same as before. ✅
- §7.3: Places are added only from the shared `onPlaceTap` (SearchScreen.kt:97). Same place means same name and ≤ 10 m. At most 10 are kept, newest first, and no distance is stored or shown. [X] / [전체 삭제] work without a confirmation, and nothing is drawn when the list is empty. The list uses its own DataStore file with corruption handling and a read retry (5e4ba4b). ✅
- §7.4:
  - Register chips appear on main only (BikeNavHost.kt:260). The search screen passes `null` (:304), and the register-mode search has no chip row. The placeholders are "집 검색" / "회사 검색".
  - A pick goes through `FavoriteRepository.save` with no bypass. An existing home or work brings up the §1.2 replace question. A refusal or failure shows `Message` + [확인] (V2).
  - On success there is a Toast, and the screen pops only if a register search is on top. The register chip has no menu. ✅

## Cross-task integration (checked)

- **Recent × register mode.** A register pick also records the place as recent (`select()` runs first, with no sheet in register mode, SearchViewModel.kt `select`). A recent row in register mode saves directly. Both behaviours are tested (SearchRegisterModeTest).
- **Register × favorites dialogs.**
  - `saveAs` → `ConfirmReplace(register = true)` → `confirmReplace()` → event plus dialog close.
  - Cancel keeps the user on the register search. A failure shows `Message`.
  - The dialogs are drawn at BikeNavHost level, so they show over the search screen.
  - `write()`'s single-write guard blocks a second tap while a save is running.
- **Navigation.** `composable(SEARCH_PATTERN)` has a nullable `register` arg with a null default. Bare `navigate("search")` resolves to it; this is the documented optional-query-arg pattern. `git grep` finds no `route == "search"` comparison and no `popUpTo(Routes.SEARCH)`.
  - The callers are unchanged: the main search bar (:234), banner NEXT_DESTINATION (via `onOpenSearch`), plan [+ 경유지 추가] (:318), and record [목적지 정하기] (:409, still `isCurrent()`-guarded). All of them resolve to `register == null`, which gives the normal sheet → `setDestinationAndOpenPlan` / `addWaypointAndOpenPlan`. **No regression found.** The device check that the controller already noted is still to do.
- **Event plumbing.** A `Channel(BUFFERED)` with one collector at NavHost level (:449-461). The pop guard re-checks the route pattern and the arg, so a late event only toasts.

## Issues

### Critical
None.

### Important
1. **The register chips flash on every cold start for riders who already have a home or work.** `FavoritesViewModel.favorites` starts as `emptyList()` (FavoritesViewModel.kt:74-80, `stateIn(..., emptyList())`). The Room flow emits a frame or more later, so until then `FavoriteChips` sees "no home, no work" and draws [🏠 집 등록] [🏢 회사 등록] (FavoriteChips.kt:59-64). It then jumps to [🏠 집] [🏢 회사] once Room answers.
   - Before this plan, an empty list drew nothing, so the placeholder was invisible. Task 3 turned it into a visible, wrong state.
   - It also changes the row width and height, so the banners below it jump.
   - A tap during that window opens a register search for a home that already exists. That ends in the replace question, so no data is wrong, but it is confusing.
   - This is cross-task: Task 3's register chips combined with the pre-existing VM initial value. Each task review was correct for its own scope and missed it.

### Minor (triaged below)
- **Double-tap guards (controller candidate): CONFIRMED.**
  - The register chip (BikeNavHost.kt:260) and the main search bar (:234) navigate with no guard, whereas record already uses the `isCurrent()` pattern (:399-409).
  - Register-chip double tap: two register searches are pushed. After the save, only the top one is popped. The rider is left on a second "집 검색" for a kind that is now registered, and a pick there asks to replace.
  - Same root cause, one more spot: the register pick itself (`onRegister = favoritesViewModel::saveAs`, :307).
    - `write()` blocks a second tap only while the first save is still running. A Room insert takes milliseconds.
    - The pop then starts the default NavHost exit fade (≈700 ms, NavHost(:213) has no transition overrides). Pointer input most likely still reaches the fading search screen.
    - So a second tap on the result row would call `saveAs` again, get `ConfirmReplace` (the home now exists), and show "기존 집을 이 장소로 바꿀까요?" over main.
    - Guarding it with the screen entry's `isCurrent()` closes this. The guard is cheap whether or not the exit fade turns out to take input.
- The Kakao accuracy-sort wording: CHANGELOG.md:18 and recording-field-test.md:252 say "현재 위치 근처 우선 유지". In Kakao's keyword API, x/y without `radius` only feed the `distance` field and `sort=distance`. With `sort=accuracy`, "근처 우선" is likely not kept. The implementation follows spec §7.2's wording, so this is spec-mandated. **LEAVE.** Treat it as a field-test observation (checklist item 6); if nearby bias turns out to matter, a follow-up could add `radius`.
- The RegisterChip ripple is not clipped to the chip shape. `clickable` sits on the Surface `modifier` outside its shape (FavoriteChips.kt:91-94); `Surface(onClick=…)` would clip it. Cosmetic. **LEAVE.**
- `FavoriteDialogsTest` has no case for the new `FavoriteDialog.Message` [확인] (FavoriteDialogs.kt:42-46). It is trivial. **LEAVE.**

## Deferred-minor triage

| Origin | Item | Decision |
|---|---|---|
| T2-2 | The VM's handling of a failing `places` flow is untested | LEAVE (the store already retries; the VM catch is a last resort) |
| T2-3 | No boundary test at exactly 10 m | LEAVE |
| T2-5 | `writeRecent` swallows exceptions without logging | LEAVE (brief-mandated; the list is non-critical) |
| T2-6 | The drag test checks the shared `RESULTS_AREA` | LEAVE |
| T3-1 | No double-tap guard on the register chip and search bar | **FIX-NOW** (extended to the register pick) |
| T3-2 | The inline pop guard is untested | LEAVE (two conditions; the race is sub-frame) |
| T3-3 | 3 untested VM register branches | **FIX-NOW** for (b) a register `confirmReplace` store failure → `Message(MSG_SAVE_FAILED)`, the one new code path never exercised, and (a) a register `ConfirmReplace` → `dismiss()` emits no event. (c) a Rejected on replace is effectively unreachable (replace never hits the limit): LEAVE |
| T3-4 | RegisterChip has no `onClickLabel`; no Role/48 dp assertions | LEAVE |
| T3-5 | A long-press test that asserts the tap explicitly | LEAVE |
| T3-6 | `GlyphIcon` `invisibleToUser` deprecation (pre-existing) | LEAVE (track separately) |

## Docs accuracy

- CHANGELOG 1.1.0: the label fix, the three new bullets and the plan path are correct.
- recording-field-test.md:
  - PC counts 42/402 and 67/595 add up: 501 + 94, 60 + 7, and :app 380 + 22.
  - Device table: I re-counted `@Test` at adb9fb2. There are 21 classes and 101 tests in total, and the 12 listed classes add up to 59. FavoriteChipsTest 7, SearchScreenTest 10 and SearchRegisterModeTest 4 are correct.
  - Checklist items 6–8 are present and the old label is gone outside the spec and plans.
  - Every connected-test command uses `ANDROID_SERIAL=R5CT43TFS9A`. `R3CY903ZYQV` only gets `installDebug` (:95, :236) (U3 ✅).
- **Stale sentence:** recording-field-test.md:203 says `adb devices` listed no phone. The Task 4 report says R5CT43TFS9A *was* attached, and the device run is happening now.

## FIX-NOW list (one wave)

1. **(Important) No register chips until favorites have loaded.**
   - Add `val favoritesLoaded: StateFlow<Boolean>` to `FavoritesViewModel`. It turns true on the first emission of `repository.favorites`, and also in the `catch` path. Keep `favorites`' type so the existing tests stay as they are.
   - In BikeNavHost.kt:260, pass the register lambda only when it is loaded (`favoriteChips(Modifier, if (loaded) { kind -> … } else null)`).
   - JVM test: with a store that has not emitted, `favoritesLoaded` is false; after the first emission it is true.
2. **Double-tap guards with the existing `isCurrent()` pattern (BikeNavHost.kt:377/:399).**
   - `composable(Routes.MAIN) { entry -> val isCurrent = { navController.currentBackStackEntry?.id == entry.id } … }`.
   - Guard `onOpenSearch` (:234; this also covers banner NEXT_DESTINATION) and the register chip lambda (:260).
   - In `composable(SEARCH_PATTERN) { entry -> … }`, guard `onRegister = { place, kind -> if (isCurrent()) favoritesViewModel.saveAs(place, kind) }` (:307).
   - Optional, same pattern: plan `onAddStop` (:318). It is pre-existing and harmless because `openPlan` pops to MAIN.
   - Verification: compile plus a manual double-tap on the device (no automated test is expected).
3. **FavoritesViewModelTest:** add register-path tests (b) `confirmReplace()` store failure → `Message(MSG_SAVE_FAILED)` with no event, and (a) a register `ConfirmReplace` → `dismiss()` → dialog null with no event.
4. **Docs, after the running device tests finish:**
   - In recording-field-test.md, fill the 결과 column for the classes that ran on R5CT43TFS9A.
   - Rewrite the :203 sentence (date, serial, which classes ran and which are still 기기 검증 대기).
   - Note whether bare `navigate("search")` was checked on the device from the main search bar, plan [+ 경유지 추가] and record [목적지 정하기].
   - If items 1–3 add JVM tests, update the PC counts (402/595 and the FavoritesViewModelTest row).
