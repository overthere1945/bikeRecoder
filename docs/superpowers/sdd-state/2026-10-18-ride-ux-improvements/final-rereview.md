# Final re-review: fix wave 88c9163..bcd02f3 (7d0bc57, 855d4f9, bcd02f3)

Checked against final-review.md FIX-NOW 1–7 plus the controller's extra points. Read-only; no connected tests (U3).

## Verification run

- `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL (all tasks up-to-date against HEAD; clean tree).
  XML counts: core 21/177, routing-brouter 4/16, app 40/380 → 573 tests, 0 failures/errors/skipped. Matches the fix report and `recording-field-test.md`.
- Focused `--rerun` of the two changed JVM classes: `RideControllerTest` 36/0, `NavigationControllerTest` 27/0.
- `grep -rn 'R3CY903ZYQV' docs | grep connectedDebugAndroidTest` → empty (exit 1).
- All three commits carry `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Finding Verdicts
- **1. Guidance notice covers [전체 경로] / page dots** — ADDRESSED. Screen-level host and `NOTICE_BOTTOM_PADDING` removed; host now inside the map-page Box, `BottomCenter` + 64 dp (clears 12 + 48 dp BottomEnd button) (`NavigateScreen.kt:352-358`). `notice`/`onNoticeShown` threaded into `ActiveContent` (:212-213, :294-295). Only two `NoticeSnackbarHost` call sites remain (record screen, guidance map page) → no double host on NAVIGATE. KDoc updated.
- **2. Docs serials** — ADDRESSED. `navigation-field-test.md:33` now `R5CT43TFS9A`, warning (:35-36) and the historical note (:38); `2026-10-12-recording-core.md` :24/:542/:1027/:1032 now `R5CT43TFS9A` with warning under :24; old sdd-state `global-constraints.md` R1/R14 marked superseded by U3 and their command prefixes changed. Required grep empty. The soft-wrap of the warning lines keeps rendered Markdown identical (blockquote / list continuation).
- **3. Split resets auto-pause chip** — ADDRESSED. `Run.autoPause` (RideController.kt:145), captured from `r.session!!.autoPause` after the null guard and before `finish` (:307), passed via `launchRun` (:321), used in `prepare` as `r.autoPause ?: current.autoPauseEnabled` (:419). Non-split starts pass null → setting default unchanged. `RecordingSession.autoPause` is the live `var` updated by `setAutoPause` (core RecordingSession.kt:72, :189). Test `splitKeepsTheAutoPauseChipOfTheRecording` asserts new segment id and `autoPause == false`.
- **4. fail() leaves a stale notice** — ADDRESSED. `_notice.value = null` in `fail(r)` (:733), only when `run === r`, so no other live recording's notice can be wiped. Test `aFailedStartClearsANoticePostedWhileStarting`.
- **5. F1 assertion in U1 test could not fail** — ADDRESSED. `begin` → `awaitActive` → `setMuted(true)` → `assertTrue(voice.muted)` before arrival; `assertFalse(voice.muted)` after; arrival text still delivered; spoken count 0 (fake drops muted speech) (`NavigationControllerTest.kt:548-565`). Implementer reports a mutation check (removing `unmute()` fails it).
- **6. Repeat step in NoticeSnackbarHostTest was a no-op** — ADDRESSED. Sets null, advances, re-sets an equal `ScreenNotice(1, …)`, asserts `shown == [1]` (`NoticeSnackbarHostTest.kt:42-46`). Compiles; device run pending (U3).
- **7. Chip touch target / map gestures** — ADDRESSED. `.fillMaxWidth()` dropped from the row (FavoriteChips.kt:53; callers pass only `Modifier` or padding, BikeNavHost.kt:250/:284); `.minimumInteractiveComponentSize()` placed before `combinedClickable` (standard M3 pattern: reserves 48 dp layout, hit-test expansion does the rest); `role = Role.Button`.

### Extra points (bcd02f3 and others)
- **Notice appears on return to the map page** — yes. `LaunchedEffect(host, active)` restarts when `mapPageSeen` turns true; `snapshotFlow { currentNotice }` emits the waiting `ride.notice` at once (it was never reported shown while inactive, so the controller still holds it). `mapPageSeen = settledPage == RIDE_MAP_PAGE && !isScrollInProgress` (`NavigateScreen.kt:307-310`); page dots / swipe use animated scrolls, so the term blocks a start on a sliding page.
- **No double show** — the only consumer on NAVIGATE is this one host; `lastShown` + `distinctUntilChangedBy` stop a re-show of the same id after an inactive→active cycle; the record screen is a different destination, and the `isGuiding` filter in `NavigateViewModel.guidanceNotice` is unchanged.
- **Guidance-only always active** — pageCount 1 → `settledPage` is 0 = `RIDE_MAP_PAGE`, no scroll possible (`userScrollEnabled = false`, no dots) → always true.
- **Remembered id vs. a genuinely new notice** — ids come from `ScreenNotice(++noticeIds, text)` (RideController.kt:705) on the `container.ride` lazy singleton: strictly increasing for the process, never reused. `ShownId` is plain `remember` (not saveable), so process death resets both. No suppression path.
- **Mid-snackbar page change** — cancels `showSnackbar` (snackbar taken down); already reported shown. Documented in KDoc; acceptable.
- **Split carries `session.autoPause`** — confirmed above (item 3).
- **Docs** — no doc gives a `connectedDebugAndroidTest` command for `R3CY903ZYQV`; the remaining mentions are warnings, the `installDebug`-only commands, the historical note, and the two progress logs below.

### New Breakage in the Fix Diff
None.

### Out-of-Scope Observations (non-blocking, Minor)
1. **README test section** (`README.md:65`, :94; not touched by this wave): the bare `./gradlew :app:connectedDebugAndroidTest` (and "기기가 둘 이상이면 시리얼을 지정") runs on every attached phone, which with both phones connected includes `R3CY903ZYQV`, and README carries no data-loss warning. It does not name the S25, so the required grep stays empty. Suggest adding the same one-line warning under the test block (or `ANDROID_SERIAL=R5CT43TFS9A` in the example).
2. **Historical progress logs** — `docs/superpowers/sdd-state/2026-10-06-navigation/progress.md:82` (R14) and `docs/superpowers/sdd-state/2026-10-12-recording-core/progress.md:14` (R1), :66 still state the old "connected tests on R3CY903ZYQV" rulings without a superseded note. The sibling `global-constraints.md` files now mark them superseded, so risk is low; a one-word "(superseded by U3)" would close it fully.

### Verdict
**Fix round:** All findings addressed, no new Critical/Important breakage. **CLEAN** (two optional Minor doc follow-ups above).
