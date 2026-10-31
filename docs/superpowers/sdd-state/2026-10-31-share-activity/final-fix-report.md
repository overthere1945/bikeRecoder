# Final fix wave report — V1.5.0 활동 공유하기

## Items
- **I1 Instagram lookup**: `ShareApps.kt` now has a pure `InstagramReceiver(package, className, exported)` plus `pickInstagramFeed` / `pickInstagramStory`. Only exported receivers are kept, sorted by class name. Feed prefers the exact simple name `ShareHandlerActivity`, else the first name with neither "story" nor "direct" (ignoreCase). Story takes the first with "story" and not "direct" (ignoreCase). Fallbacks (setPackage) unchanged. `ShareActions.send` catches `SecurityException` like `ActivityNotFoundException` (MSG_NO_APP, sheet stays).
  - Tests: new JVM `ShareAppsTest` (6): order independence, Reels vs feed, Direct/Story excluded, DirectStory excluded from story, non-exported dropped, ignoreCase. `ShareActionsTest.securityExceptionReadsAsNoApp` (androidTest). `ShareIntentsTest.moreIsChooserWithTitle` now asserts the chooser's clipData URI and FLAG_GRANT_READ_URI_PERMISSION.
- **M1 hashtags dialog**: initial value is `current` whole; `onValueChange` blocks growth past 100 but allows deleting; Settings value Text is `maxLines = 1` with ellipsis. Test: `SettingsScreenTest.longStoredHashtagsReopenIntact` (133-char stored value reopens intact and saves unchanged). Growth blocking has no test (not cheap with performTextReplacement semantics).
- **M2 emailSubject**: first non-blank trimmed line. JVM `emailSubjectSkipsLeadingBlankLines` (also all-blank gives "").
- **M3 cleanTag**: code-point filter with `appendCodePoint`. JVM `cleanTagKeepsSupplementaryLetters` ("𠀀" kept, emoji dropped, emoji-only gives null).
- **M4 docs**: `share-field-test.md` #4 and #8 rewritten as briefed; JVM counts and S22 table updated; `CHANGELOG.md` 1.5.0 three edits. Messages quoted from code: "저장 공간이 부족합니다(약 20 MB 필요)" (VideoJob.noSpaceMessage with IMAGE_BYTES) and "이미지를 만들지 못했습니다" (VideoJob.MSG_IMAGE_FAILED).

## Test counts
- `./gradlew :core:test :app:testDebugUnitTest --rerun-tasks`: core 44 classes / 387 tests; app 90 classes / 1068 tests (was 1060: +8); 0 failures/skips. Total 134 / 1455.
- S22 (R5CT43TFS9A): ShareIntentsTest 10/10, ShareActionsTest 7/7, SettingsScreenTest 17/17; no failures, no skips, no AssumptionViolated.

## Concerns
- The CHANGELOG failure sentence names "이미지를 만들지 못했습니다" as the make-failed message; VideoJob can also surface other `Stop` messages (e.g. no internet from the map download), which are described separately.
- The dialog growth cap is untested.
- A first attempt at a monitoring shell loop hung on a stray `grep` reading stdin; no device or app state was affected. All connected runs were redone cleanly.
