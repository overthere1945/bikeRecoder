# Final whole-plan review: favorites screen, current location, map pick (ae08918..9ccfaf3)

Scope: Tasks 1 to 4 plus fix round 1 (commits 1241003, 3cb4ada, e7ea67c, 984fe04, 9ccfaf3), spec §8, plan
`docs/superpowers/plans/2026-10-18-favorites-and-map-pick.md`. Line numbers are at 9ccfaf3.

Verification run by this review: `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebugAndroidTest`
→ BUILD SUCCESSFUL. The tasks were up to date at HEAD. The XML shows :core 177/21, :routing-brouter 16/4 and :app 469/45, with
0 failures and 0 errors, so 662 tests in 70 classes. The per-class counts match the doc: FavoritesViewModelTest 58,
KakaoLocalClientTest 23, SearchViewModelTest 9, RegisteredPopTest 3, MapPickResultTest 4, MapPickViewModelTest 10,
PlaceLookupTest 5, MainViewModelTest 16. A count of `@Test` in androidTest gives 101/21 at ae08918 and 128/23 at HEAD, as the doc
says. No adb was used and no connected tests were run.

## Verdict

**Ready to merge after one small docs wave (FIX-NOW 1).** No Critical findings. No Important findings in the code. One
Important finding in the docs: the field checklist would mislead the person testing on the phone.

## Spec §8 coverage

- §8.1 ✅ FavoriteChips.kt:68-76. Home and work, or their register chips on main once `favoritesLoaded`
  (BikeNavHost.kt:295), then [★ 즐겨찾기] (always shown, no menu). The normal search shows only saved home/work plus ★
  (BikeNavHost.kt:356). The register-mode search has no chip row (SearchScreen.kt:151).
- §8.2 ✅ FavoritesScreen.kt:53-99 with BikeNavHost.kt:399-415.
  - Shows custom favorites only, in creation order. The empty text appears only once the list has loaded.
  - At the limit, the Message appears and the screen stays (FavoritesViewModel.kt:417-424).
  - Add opens `searchRegister(CUSTOM)` with the hint "즐겨찾기 검색". Saving shows the Toast "즐겨찾기에 저장했습니다" and pops
    back to the favorites screen (BikeNavHost.kt:557-567, `popsAfterRegistered` :152).
- §8.3 ✅ Search: the two lines sit above the recent searches, also while a query is typed (SearchScreen.kt:155-166).
  - Current location: no fix, or a fix older than 30 s, shows the "찾는 중" message.
  - A rough fix shows the warning, and saving is still allowed.
  - Home/work: ConfirmCurrent, then `saveAs`. Custom: NameFavorite. All in FavoritesViewModel.kt:288-317.
  - Map pick: home/work are saved at once, custom asks for a name (:323-330).
  - Every path goes through `FavoriteRepository.save`.
- §8.4 ✅ FavoriteDialogs.kt:147-198 with FavoritesViewModel.kt:336-410.
  - Prefill: the search result's name, otherwise the nearest place, then the address, then empty.
  - Suggestions: up to 5 nearby names plus the address.
  - Late suggestions never overwrite what the rider typed.
  - The sheet's [직접 입력] uses the same dialog with `register = false`, so there is no Toast and no pop.
  - Kakao `nearby`: 10 category codes, `radius=50`, `sort=distance`, `size=5`, sent in parallel. A failed category is
    skipped; if all fail the list is empty (KakaoLocalClient.kt:118-143).
- §8.5 ✅ MapPickScreen/MapPickViewModel.
  - The pin tip is at the map centre.
  - Address lookup runs 300 ms after the camera goes idle, using `collectLatest`, and a late answer is ignored.
  - The button is disabled while the map moves or the address loads. A failed lookup enables it and shows the coordinates.
  - The normal search line opens the same sheet, named by the address or "지도에서 선택한 위치".
  - Accepted deviation: with no current location the map opens at Seoul City Hall, not "the last map position". Nothing
    in the app remembers a camera position.

## Cross-task integration (traced)

- **Chips → favorites → register search.**
  - Path: ★ (guarded by `isCurrent`) → FAVORITES → ＋ (`startAdd`) → `search?register=CUSTOM`.
  - From there: result or recent → NameFavorite(prefill); 📍 → lookup → NameFavorite(nearest); 🗺 → MAP_PICK →
    `MapPick.send(previousBackStackEntry)` → pop → `MapPickResultEffect` → `registerPicked(CUSTOM)` → NameFavorite.
  - [저장] → `saveCustom` → `Registered(CUSTOM)` → Toast → pop to FAVORITES.
  - Final back stack: MAIN, FAVORITES ✅.
  - Home and work from the main register chip end the same way, with a pop to MAIN ✅.
- **Normal search → map pick → sheet.** `onMapPicked` → `select` (sheet, and a recent entry) ✅.
  - Entry points: plan [+ 경유지 추가] (BikeNavHost.kt:425) and record [목적지 정하기] (:516). Both push plain `Routes.SEARCH`,
    so the result lands on the entry right below MAP_PICK.
  - The sheet actions are the existing `setDestinationAndOpenPlan` / `addWaypointAndOpenPlan`, with `popUpTo(MAIN)`.
    Unchanged from a tapped result ✅.
- **Exactly once.**
  - (1) MapPick → normal search and (2) MapPick → register search share `MapPick.send/take`. The address is written before the
    trigger key. `take` nulls the key rather than removing it, so the flow stays attached, and clears it before calling back.
    A rotation or a later return therefore reads null.
  - Confirm and back are guarded by `isCurrent`, so a second tap during the pop finds the search entry on top and does nothing.
  - (3) `Registered` goes through a BUFFERED channel collected at host level (each event once, even across recreation).
    The pop requires the top entry to be the register search of the same kind (`popsAfterRegistered`, with RegisteredPopTest).
    So a late home save cannot close a CUSTOM search or the map-pick screen.
  - The late current-location lookup is now cancelled by every other action and when the register search goes
    (BikeNavHost.kt:345-347, FavoritesViewModel.kt:164-168, :296-310). Its answer is shown only if it is still the current
    lookup and no dialog is open ✅.
- **Kakao request volume.** Bounded and only triggered by the rider:
  - Map pick: one reverse geocode per camera stop, after 300 ms. There are two at open, the init lookup and the first idle
    (Task 2 minor 2).
  - Name dialog: 10 category calls each time it opens, cancelled on dismiss or save.
  - Current location: one reverse geocode.
  - Nothing re-fires on recomposition or rotation, because FavoritesViewModel is activity-scoped. No loops.
- **Regressions.**
  - `BikeMap`'s new listeners are no-ops when the callback is null. Main, plan, navigate, record and history maps are unchanged,
    and so are long-press, gesture and FOLLOW handling.
  - The SearchScreen Box → Column change keeps `imePadding`; the keyboard tests pass 13/13 on the S22.
  - The `parsePlaces` extraction is behaviour-identical (KakaoLocalClientTest 23/23).
  - Trip start, guidance, recording, onboarding and Room are untouched. ★ in a search opened from plan [+ 경유지 추가] sets the
    destination, the same as the home/work chips did before, so this is not new.

## Findings

### Critical
None.

### Important
1. **The field checklist and the device table describe a favorites screen that does not exist**:
   - docs/verification/recording-field-test.md:303 (checklist 9) says the list is "집 → 회사 → 직접 입력 순" and that
     "[⋮] … (집·회사는 삭제만)". By spec §8.2 and BikeNavHost.kt:402 the screen lists **custom favorites only**. Home and work
     are chips, deleted with a long press.
   - :267 (FavoritesScreenTest row) repeats "집 → 회사 → 직접 입력 순". The test, FavoritesScreenTest.kt:26-27 and :54, uses
     two CUSTOM favorites.
   - :304 (checklist 10) says the 즐겨찾기 register search also asks "현재 위치를 집으로 저장할까요?". A custom favorite opens the
     「즐겨찾기 이름」 dialog instead (spec §8.3).
   - Effect: the tester would mark a correct build as failed, or look for home/work on that screen.

### Minor (for information; triage below)
- docs :249 still says SearchRegisterModeTest "이후 9개로 늘어"; it is now 10. :203 states the same sentence twice ("수는 …
  XML에서 …").
- To verify in the manual pass (checklist 11), not a fix: SearchScreen.kt:126 `LaunchedEffect(Unit) { focus.requestFocus() }`
  runs again when the search screen comes back from MAP_PICK, so the keyboard may rise together with the result sheet or the
  name dialog. This is the same as tapping a result while typing, which the S25 pass already accepted.

## Deferred minors: triage

| Source | Item | Decision |
|---|---|---|
| T1-1 | Pop rule should match the kind | Fixed in Task 3 (`popsAfterRegistered`) |
| T1-2 | `startAdd` and the empty text before load | Fixed in Task 3 |
| T1-3 | RenameDialog / name dialog duplication | LEAVE (NameCustom is gone; what remains is similar, not verbatim) |
| T1-4 | Stub-passing tests; SearchScreenTest ★ slot mirrors the host | LEAVE (layout tests; the host wiring is checklist 9) |
| T2-1 | `onMoveStarted` doesn't cancel the pending lookup | LEAVE (the guard drops the answer; at most one wasted request) |
| T2-2 | Double lookup at open (init plus the first idle) | LEAVE (one extra request per open) |
| T2-3 | Confirm while the address loads | Fixed in fix round 1 (`canConfirm` needs `!loading`) |
| T2-4 | No re-centre when the first fix arrives late | LEAVE (rare: the search screen keeps the location running, and the user dot is drawn) |
| T2-5 | Subtitle hidden when it equals the name | LEAVE (benign) |
| T2-6 | No device recreation test | LEAVE (MapPickResultTest plus code reasoning) |
| T2-7 | `heightIn` has no effect | LEAVE (cosmetic) |
| T3-1 | Stale fix | Fixed (30 s) |
| T3-2 | Address and nearby run one after the other | LEAVE (the loading line now gives feedback) |
| T3-3 | `saveCustom` repeats `write()` | LEAVE (internal; no rider effect) |
| T3-4 | Dead CUSTOM branch of `save(kind, name)` | LEAVE |
| T3-5 | `register` default | Fixed |
| T3-6 | No log when every category fails | LEAVE (the real key returned 200 for all ten) |
| T3-7 | Tests for `nearby` concurrency and cancellation | LEAVE (structured `async`/`awaitAll`, checked by reading) |
| T3-8 | Warning colour | Fixed (tertiary) |
| Fix-1 | GPX replay timestamps mean "찾는 중" forever | LEAVE (debug-only source) |
| Fix-1 | Rotation during a lookup cancels it | LEAVE (the rider taps again) |
| New | Pick confirm waits for Kakao up to the default OkHttp timeouts on a weak network | LEAVE (with no connection at all it fails fast; same exposure as keyword search) |
| New | The 30 s age check uses `Location.time` against the wall clock (a phone clock off by more than 30 s would always say "찾는 중") | LEAVE (phones use network time) |

## FIX-NOW (one wave, docs only)

1. `docs/verification/recording-field-test.md`:
   - :303 checklist 9: the list holds custom favorites only, in creation order. [⋮] and long press give [이름 바꾸기]
     [삭제]. Home and work stay chips, with long-press [삭제] only.
   - :267 FavoritesScreenTest row: "직접 입력 즐겨찾기를 만든 순".
   - :304 checklist 10: 집·회사 → 「현재 위치를 집으로/회사로 저장할까요?」 → [저장]. 즐겨찾기 → 「즐겨찾기 이름」 창. Add a note that the
     home/work register search is only reached from [🏠 집 등록]/[🏢 회사 등록], so delete the saved one first if needed.
   - While there: :249 "9개" → "10개", and drop the repeated sentence at :203.
   - Do not touch any device instruction. The doc already says connected tests run only on `R5CT43TFS9A`, and no instruction
     runs connected tests on `R3CY903ZYQV`.
