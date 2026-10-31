# Final fix wave — V1.5.0 활동 공유하기 (one dispatch)

Base: 321c2e3 on feature/share-v1.5.0. Fix exactly these items, each with the tests named, in ONE commit:
`app: share — final review fixes (Instagram lookup, hashtag dialog, subject, tags, docs)`

## I1. Instagram receiver choice and crash path
- `app/src/main/java/com/cowork/bikerecoder/share/ShareApps.kt` (`receivers()`, `instagramFeed()`, `instagramStory()` or their equivalents):
  - Keep only receivers with `activityInfo.exported == true`; sort by `activityInfo.name` (class name) so the choice is deterministic.
  - Feed: prefer the receiver whose simple class name (after the last '.') is exactly `ShareHandlerActivity`; otherwise the first whose class name contains neither "story" nor "direct" (ignoreCase). Falls back as today (setPackage `com.instagram.android`).
  - Story: first whose class name contains "story" (ignoreCase) AND not "direct" (ignoreCase). Falls back as today.
  - Make the selection a pure function over (className, exported) pairs if it is not already, and add JVM tests: feed prefers `...ShareHandlerActivity` over a Reels/Clips handler regardless of input order; Direct and Story excluded from feed; Story excludes `DirectStoryShare…`-style names; non-exported dropped; ignoreCase.
- `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareHost.kt` (`ShareActions.send`): treat `SecurityException` like `ActivityNotFoundException` → the existing "열 수 있는 앱이 없습니다", sheet stays open. Add a ShareActionsTest case with a launcher that throws SecurityException.
- `app/src/androidTest/java/com/cowork/bikerecoder/share/ShareIntentsTest.kt`: in the MORE (chooser) case assert the wrapper intent carries `clipData` with the file URI and `FLAG_GRANT_READ_URI_PERMISSION`.

## M1. Default-hashtags dialog truncation
- `ui/settings/ShareHashtagsDialog.kt`: initial value is `current` unmodified (no `take(100)`). `onValueChange = { if (it.length <= MAX_HASHTAGS_INPUT || it.length <= text.length) text = it }` — blocks growth past 100, allows deleting.
- `ui/settings/SettingsScreen.kt` (~:309, the hashtags value Text): `maxLines = 1, overflow = TextOverflow.Ellipsis`.
- Test where the existing settings tests make it cheap (a stored 133-char value reopens intact); else explain in the report.

## M2. emailSubject first non-blank line
- `share/ShareText.kt` `emailSubject`: `text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()` then the existing 80-code-point cap. JVM test: leading blank lines → subject is the first non-blank line.

## M3. cleanTag keeps supplementary-plane letters
- `share/ShareText.kt` `cleanTag`: filter by code points (`Character.isLetterOrDigit(cp) || cp == '_'.code`), rebuild with `StringBuilder.appendCodePoint`. JVM test with a supplementary CJK letter (e.g. "𠀀") kept and an emoji dropped.

## M4. Docs must match the code
- `docs/verification/share-field-test.md` #8 (~:95): the "인터넷이 없어 이미지를 만들 수 없습니다" message appears AFTER pressing a file-needing button on a trip with no usable file (from the startImage result), and a trip whose area map is downloaded can still make the image offline. Rewrite to: on a trip with no usable file whose area map is not downloaded, press [페이스북] in airplane mode → the message shows, then the file-needing buttons dim.
- Same file #4 (~:91): "20 MB를 넘는 영상은 붙지 않고 가장 최근 이미지(없으면 새로 만든 세로 요약 이미지)가 붙음".
- `CHANGELOG.md` 1.5.0:
  - "자동 글과 같으면 저장하지 않아" → "자동 글과 같으면 저장한 글을 지워(null) 제목을 바꾸거나 지역을 찾은 것이 자동으로 따라감".
  - "[다시 시도] 단추" → "(여행을 마치지 않은 경우 말고는) [다시 시도] 단추"; also mention the storage-full and make-failed messages exactly as the code shows them (look them up in ShareViewModel / StartResult.message).
  - After "끝났을 때 미리보기 화면이 뜨지 않고" add "(시트를 닫지 않았을 때; 닫으면 지금처럼 미리보기가 뜸)".
- Update the JVM counts in the verification doc to the new totals after your run.

Not in scope (ruled skip): withTag trailing "\n", toggleMood trim, T4/T5 parked items, `#`/`,` as hashtag separators, unused setShareHashtags.
