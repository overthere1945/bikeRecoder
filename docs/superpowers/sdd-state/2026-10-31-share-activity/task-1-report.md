# Task 1 report — 순수 계산 (V1.5.0 공유하기)

Status: DONE. Commit: 1d08bf4 `app: share — auto text, hashtags, moods, file choice` (not pushed).

## Built
- app/src/main/java/com/cowork/bikerecoder/share/ShareText.kt: ShareFacts, constants, shareDateLine, regionTags, cleanTag,
  normalizeHashtags, autoShareText, Mood, moodOn, toggleMood, shareStamp, emailSubject (names exactly as in the brief).
- app/src/main/java/com/cowork/bikerecoder/share/ShareFiles.kt: ShareCandidate, ShareTarget, SharePick, EMAIL_MAX_BYTES,
  pickFile, pickLine, fileLines.

## Tests / TDD
- ShareTextTest (23) and ShareFilesTest (12) written first; first run failed to compile (Unresolved reference 'ShareCandidate'), then
  implementation made all 35 pass.
- Includes a calendar check of the weekdays used (2026-10-25 Sun ... 2027-01-01 Fri).
- Full suite `./gradlew :core:test :app:testDebugUnitTest --rerun-tasks`: BUILD SUCCESSFUL.
  app 1020 tests, core 387 tests, 0 failures, 0 skipped. No warnings from the new files (existing warnings in GlyphIcon.kt and PostProcessorTest.kt only).

## Decisions / concerns
- Average speed follows the brief (32.14 km / 1.75 h = 18.4), BigDecimal HALF_UP.
- pickLine shows a size only for videos (spec examples show images without size). If images should show sizes too, it is a one-line change.
- pickFile(COPY, ...) is not meant to be called; it behaves like the general buttons.
- shareStamp rounds the distance half up (32_140.4 -> 32140).
- toggleMood off works line by line: a line that becomes empty after removal is dropped, so a mood appended on a new line round-trips to the original text.
- Hashtags are letters/digits/_ via Char.isLetterOrDigit (Korean, Latin included; emoji removed).
