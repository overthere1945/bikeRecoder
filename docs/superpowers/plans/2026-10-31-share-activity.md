# 활동 공유하기 (V1.5.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 요약 카드·여행 화면·구간 요약에서 여는 "활동 공유하기" 시트를 만든다. 시트는 고칠 수 있는 자동 글과 감정 칩, 그리고 [페이스북] [인스타그램] [스토리] [이메일] [복사] [저장] [더 보기]로 이루어지며, 영상이나 요약 이미지를 바로 보낸다. 보낼 파일이 없으면 세로 요약 이미지를 그 자리에서 만든다.

**Architecture:**
- **순수 계산 (`app/.../share/`, JVM 시험):** 자동 글·해시태그·감정 칩을 다루는 `ShareText.kt`와 보낼 파일을 고르는 `ShareFiles.kt`.
- **안드로이드 경계 (`share/`, 기기 시험):**
  - 앱 찾기: `ShareApps`
  - 인텐트 만들기: `ShareIntents`
  - 갤러리 후보 읽기: `ShareCandidates`
- **화면 상태와 흐름 (`ui/share/`):** `ShareViewModel`이 맡는다.
  - 열기, 글 고치기, 단추 누르기
  - 이미지 만들기를 기다렸다가 이어서 보내기
  - 저장
  - 결과는 `ShareEvent`로 내보내고, `BikeNavHost`의 `ShareHost`가 이를 인텐트로 바꿔 띄운다.
- **저장:** `trip_label`에 칸 둘을 더하고 Room 7판으로 올린다. DataStore에는 `shareHashtags`를 더한다.

**Tech Stack:** Kotlin, Compose Material 3 (`ModalBottomSheet`, `FilterChip`), Room AutoMigration, DataStore, Android `Intent`/`PackageManager`/MediaStore, JUnit5(app JVM), Compose UI test(androidTest).

**Spec:** `docs/superpowers/specs/2026-10-31-share-activity-design.md`. 아래 "스펙 §"는 이 문서를 가리킨다.

## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어다. 스펙 §6 문구는 그대로 쓴다.
- **시각과 숫자:**
  - 날짜·시각은 Asia/Seoul 기준이다.
  - 주행은 "10 m 이상 끝난 구간"이다(`StatsCalculator.rides`, `MIN_RIDE_DISTANCE_M`).
  - 숫자 형식은 기존 함수를 쓴다: `kmText`, `meterText`, `RideFormat.duration`, `groupedInt`.
- **메타 연동 금지:** 메타(페이스북) SDK·앱 ID를 넣지 않는다. `com.instagram.share.ADD_TO_STORY`도 쓰지 않는다.
- **권한:** 새 권한을 추가하지 않는다. 패키지 보기는 `<queries>`만 쓴다.
- **바꾸지 않는 것:** `VideoJob`의 규칙·모양, 미리보기 화면의 [공유].
- **기기 시험:**
  - S22 `R5CT43TFS9A`에서만 돌린다.
  - 클래스 하나씩, 화면을 먼저 깨우고, `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`을 붙인다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정을 바꾸지 않는다.
  - 실제 SNS에 올리지 않는다(인텐트 내용만 확인).
- **비밀:** `secrets.json`은 출력·커밋 금지다.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(app `ui/`는 영어 KDoc, core는 한국어).

## Review Focus

1. **오래된 영상:** 여러 날 여행에서 영상을 만든 뒤 주행이 더 붙었다. 그 영상을 보내면 안 되고, 이미지를 만들거나 다른 최신 파일을 보내야 한다. → Task 1 `staleMediaIsNotSent`
2. **갤러리에서 지운 파일:** 사용자가 갤러리에서 영상을 지웠다. 지운 파일을 보내면 안 되고, 다음 순위로 넘어가야 한다. → Task 3 `deletedGalleryFileIsNotACandidate`
3. **만들다 멈춘 경우:** 이미지를 만드는 중에 시트를 닫거나 [취소]하거나 만들기가 실패했다. 닫은 뒤에 혼자 보내지면 안 되고, "만드는 중"에 갇히면 안 된다. → Task 4 `closingWhileMakingDoesNotSendLater`, `failedMakingReturnsToButtonsWithMessage`
4. **이모지가 든 긴 글:** 2,000자 제한과 이메일 제목 80자를 자를 때 이모지(서로게이트)를 반으로 쪼개면 안 된다. → Task 1 `emailSubjectCutsAtCodePoints`
5. **인스타 받는 곳이 바뀐 경우:** 인스타 앱 업데이트로 Story 받는 곳 이름이 바뀌었다. [스토리]가 사라지거나 실패하지 않고 인스타 일반 공유로 열려야 한다. → Task 3 `storyFallsBackToInstagramPackage`

---

### Task 1: 순수 계산 — 자동 글·해시태그·감정·보낼 파일 고르기

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/share/ShareText.kt`, `app/src/main/java/com/cowork/bikerecoder/share/ShareFiles.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/share/ShareTextTest.kt`, `ShareFilesTest.kt`

**Interfaces:**
- Consumes:
  - `RideTitle.shown`, `RideTitle.truncateCodePoints` (core)
  - `kmText`, `meterText` (ui/feed/FeedFormat.kt), `RideFormat.duration`
  - `MediaKind` (video/TripMedia.kt), `Aspect` (core/video)
- Produces (모두 `package com.cowork.bikerecoder.share`):
  - `data class ShareFacts(val title: String, val firstDate: LocalDate, val lastDate: LocalDate, val distanceM: Double, val movingTimeMs: Long, val ascentM: Double, val regionName: String?)`
  - `const val DEFAULT_SHARE_HASHTAGS = "#자전거 #라이딩"`, `const val MAX_SHARE_TEXT = 2000`, `const val MAX_HASHTAGS_INPUT = 100`, `const val MAX_SUBJECT = 80`
  - `fun shareDateLine(first: LocalDate, last: LocalDate): String`
  - `fun regionTags(regionName: String?): List<String>`
  - `fun cleanTag(word: String): String?`: `#` + 글자·숫자·`_`만 남긴다. 남는 것이 없으면 null.
  - `fun normalizeHashtags(input: String): String`: 설정 입력 정리. 겹침을 없애고 빈칸 하나로 잇는다.
  - `fun autoShareText(facts: ShareFacts, defaultTags: String): String`
  - `enum class Mood(val emoji: String, val word: String)`: SANGKWAE("😊","상쾌"), SINNAM("😄","신남"), PUDEUT("💪","뿌듯"), YEOYU("😌","여유"), HIMDEUM("🥵","힘듦"), PIGON("😴","피곤"), BI("🌧","비"), CHOEGO("🔥","최고"). `val tag get() = "#$word"`
  - `fun moodOn(text: String, mood: Mood): Boolean`
  - `fun toggleMood(text: String, mood: Mood): String`
  - `fun shareStamp(rides: Int, distanceM: Double): String`: "3:32140"
  - `fun emailSubject(text: String): String`: 첫 줄을 80 코드 포인트까지 자른다.
  - `data class ShareCandidate(val uri: String, val kind: MediaKind, val aspect: Aspect, val createdAt: Long, val sizeBytes: Long?)`
  - `enum class ShareTarget { FACEBOOK, INSTAGRAM, STORY, EMAIL, COPY, SAVE, MORE }`
  - `sealed interface SharePick { data class File(val file: ShareCandidate) : SharePick; data object MakeImage : SharePick }`
  - `fun pickFile(target: ShareTarget, candidates: List<ShareCandidate>, lastEndedAt: Long): SharePick`: COPY에는 쓰지 않는다.
  - `const val EMAIL_MAX_BYTES = 20L * 1024 * 1024`
  - `fun pickLine(pick: SharePick): String`
  - `fun fileLines(general: SharePick, story: SharePick, email: SharePick): List<String>`

- [ ] **Step 1: 실패하는 시험 — 글** (`ShareTextTest`)
  - `oneDay`: `shareDateLine(2026-10-25, 2026-10-25)` == "2026년 10월 25일 (일)"
  - 여러 날:
    - 같은 달: `(10-25, 10-27)` == "2026년 10월 25일 (일) ~ 27일 (화) · 3일"
    - 달이 바뀜: `(10-31, 11-02)` == "2026년 10월 31일 (토) ~ 11월 2일 (월) · 3일"
    - 해가 바뀜: `(2026-12-31, 2027-01-01)` == "2026년 12월 31일 (목) ~ 2027년 1월 1일 (금) · 2일"
  - `autoTextFourLines`: facts(title "저녁 라이딩 · 서울 강서구", 하루, 32_140.0 m, 6_300_000 ms, 123.0 m, region "서울 강서구"), tags `DEFAULT_SHARE_HASHTAGS` →
    ```
    저녁 라이딩 · 서울 강서구
    2026년 10월 25일 (일)
    32.1 km · 1시간 45분 · 상승 고도 123 m · 평균 18.4 km/h
    #자전거 #라이딩 #서울 #강서구
    ```
    평균은 32.14 km ÷ 1.75 h = 18.37이고, 소수 한 자리로 반올림한다.
  - 숫자 줄에서 빠지는 것:
    - `ascentUnderOneMeterIsLeftOut`: 0.6 m → 숫자 줄에 "상승 고도" 없음
    - `noMovingTimeHasNoAverage`: 0 ms → "평균" 없음
  - `noTagsNoFourthLine`: defaultTags "" + region null → 세 줄
  - 해시태그 정리:
    - `cleanTagStripsSymbols`: `cleanTag("한강!")` == "#한강", `cleanTag("#자출")` == "#자출", `cleanTag("!!")` == null
    - `regionTagsSplit`: `regionTags("서울 강서구")` == ["#서울","#강서구"], `regionTags(null)` == []
    - `duplicatesKeepFirst`: defaultTags "#서울 #라이딩" + region "서울 강서구" → 4줄째 "#서울 #라이딩 #강서구"
    - `normalizeHashtagsInput`: "자출 한강" → "#자출 #한강", "#자출  #자출" → "#자출", "" → "", "  " → ""
  - 감정 칩:
    - `toggleMoodAddsToLastTagLine`: autoText에 SANGKWAE → 4줄째 끝 " #상쾌", `moodOn` true
    - `toggleMoodRemoves`: 같은 칩을 다시 누르면 원래 글
    - `moodWordBoundary`: "… #상쾌함" → `moodOn(SANGKWAE)` false
    - `moodWithoutTagLine`: 해시태그 줄이 없는 글 → 맨 끝에 새 줄 "#상쾌"
    - `userDeletedMoodTag`: 글에서 "#상쾌"를 지우면 `moodOn` false
  - `shareStampFormat`: `shareStamp(3, 32_140.4)` == "3:32140"
  - `emailSubjectCutsAtCodePoints`: 첫 줄이 이모지 100개 → 결과 코드 포인트 수 80, 마지막이 온전한 이모지
- [ ] **Step 2: 실패하는 시험 — 파일** (`ShareFilesTest`)
  - **후보:** v(VIDEO, LANDSCAPE, t=200, 12 MB), i(IMAGE, PORTRAIT, t=150), old(VIDEO, PORTRAIT, t=50). lastEndedAt = 100.
  - `staleMediaIsNotSent`: STORY → File(i). old는 세로지만 오래되었다.
  - `generalPrefersVideo`: FACEBOOK·INSTAGRAM·MORE·SAVE → File(v)
  - `storyPrefersPortraitVideoThenImage`: 세로 영상(t=300)이 있으면 그것을 고른다.
  - `storyNeverSendsLandscape`: 가로 영상만 있으면 MakeImage
  - `emailSkipsBigVideo`:
    - v가 25 MB면 File(i)
    - v의 크기를 모르면(null) File(i)
    - v가 12 MB면 File(v)
  - `nothingFreshMakesImage`: 모두 오래되었으면 모든 target이 MakeImage
  - `newestWins`: 같은 종류가 둘이면 createdAt이 큰 것
  - `pickLineTexts`:
    - File(v 12 MB) → "영상 · 가로 · 12 MB"
    - File(3.4 MB 영상) → "영상 · 세로 · 3.4 MB"
    - File(i) → "요약 이미지 · 세로"
    - MakeImage → "요약 이미지 · 세로 (누르면 만듭니다)"
    - 크기를 모르면 크기를 빼고 쓴다.
    - MB = bytes / 1,048,576이다. 10 미만이면 소수 한 자리, 10 이상이면 정수다.
  - `fileLinesShowsDifferentStoryAndEmail`:
    - general = story = email이면 ["보낼 것: …"] 한 줄
    - 다르면 다음 줄을 더한다: "스토리: …", "이메일: …"
- [ ] **Step 3: 실행해서 실패 확인**
  `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.share.*'` → 컴파일 실패
- [ ] **Step 4: 구현**
  - 요일 표기: `Locale.KOREAN` short ("일")
  - 평균: km/h 소수 한 자리, `Locale.US`
  - 해시태그 줄 결합: 기본(`normalizeHashtags(defaultTags)`) → 장소 → 겹침 제거
  - `toggleMood`:
    - 켜기: 마지막으로 `#`로 시작하는 줄 끝에 " " + tag를 붙인다. 없으면 "\n" + tag.
    - 끄기: 정규식 `(^|\s)#상쾌(?=\s|$)`를 지우고, 줄 안의 겹친 빈칸을 하나로 줄인다.
- [ ] **Step 5: 시험 통과 확인** — 같은 명령, PASS
- [ ] **Step 6: 커밋** `app: share — auto text, hashtags, moods, file choice`

### Task 2: 저장 — Room 7판 공유 글, 기본 해시태그 설정과 [설정] 화면

**Files:**
- Modify:
  - `core/src/main/kotlin/com/cowork/bikerecoder/core/trip/TripLabel.kt`
  - `app/src/main/java/com/cowork/bikerecoder/data/Entities.kt` (`TripLabelEntity`)
  - `Daos.kt` (`TripLabelDao`)
  - `AppDatabase.kt` (version 7, `AutoMigration(6, 7)`)
  - `RoomTripLabelStore.kt`
  - `SettingsRepository.kt`
  - `ui/settings/SettingsScreen.kt`
- Create:
  - `app/schemas/com.cowork.bikerecoder.data.AppDatabase/7.json` (생성됨)
  - `ui/settings/ShareHashtagsDialog.kt`
- Test:
  - androidTest `data/RideDatabaseMigrationTest.kt` (6→7 추가), `data/RoomTripLabelStoreTest.kt`, `ui/settings/SettingsScreenTest.kt`
  - JVM: TripLabelStore를 흉내 내는 fake가 있으면 고친다.

**Interfaces:**
- Produces:
  - `data class TripLabel(val customTitle: String? = null, val regionName: String? = null, val shareText: String? = null, val shareStamp: String? = null)`
  - `TripLabelStore.setShareText(tripId: Long, text: String?, stamp: String?)`: text가 null이면 두 칸 모두 null이다.
  - `TripLabelDao.setShareText(...)` (`@Transaction`)
    - `setCustomTitle`·`setRegionName`도 공유 칸을 **보존**하게 고친다(지금은 새 엔티티로 덮어쓴다).
  - `AppSettings.shareHashtags: String?`: null이면 기본(`DEFAULT_SHARE_HASHTAGS`), ""이면 없음.
  - `SettingsRepository.setShareHashtags(value: String)`
  - `SettingsScreen` 새 인자 `onShareHashtags: (String) -> Unit`. 또는 기존 settings 콜백 묶음에 더한다.

- [ ] **Step 1: 실패하는 시험**
  - `RoomTripLabelStoreTest`:
    - `shareTextSavedAndCleared`
    - `settingTitleKeepsShareText`
    - `settingRegionKeepsShareText`
    - `shareTextCreatesRowForUnlabeledTrip`
  - `RideDatabaseMigrationTest.migrate6To7`: 6판에 이름표 있는 여행·없는 여행을 넣고 → 7판에서 행 수, customTitle/regionName이 같고, shareText/shareStamp가 null
  - `SettingsRepository` JVM 시험(기존 settings 시험 파일 옆):
    - 기본 null
    - `setShareHashtags("")` → ""
    - `setShareHashtags("#자출")` → "#자출"
- [ ] **Step 2: 구현**
  - `@ColumnInfo` 없이 `val shareText: String? = null, val shareStamp: String? = null` 기본값으로 둔다. AutoMigration이 열 둘을 더한다.
  - `./gradlew :app:kspDebugKotlin`으로 `7.json`을 만든다.
- [ ] **Step 3: [설정] "공유" 묶음**
  - **자리:** "앱 정보" 묶음 바로 위
  - **줄:** `SectionTitle("공유")`, 그 아래 한 줄
    - 왼쪽 "기본 해시태그"
    - 오른쪽 값: `null`이면 `DEFAULT_SHARE_HASHTAGS`, ""이면 "없음"
    - 끝에 ›
    - 테스트 태그 `SettingsTags.SHARE_HASHTAGS`
  - **`ShareHashtagsDialog`:**
    - 제목 "기본 해시태그"
    - 한 줄 칸, 처음 값은 지금 값
    - 100자를 넘는 입력은 받지 않는다(`take(MAX_HASHTAGS_INPUT)`).
    - [저장]은 `normalizeHashtags(입력)`을 넘긴다. [취소]도 있다.
  - **`SettingsScreenTest`:**
    - `shareHashtagsRowShowsDefault` ("#자전거 #라이딩")
    - `shareHashtagsDialogNormalizes`: "자출 한강" 입력 → 저장 콜백에 "#자출 #한강"
    - `emptyShowsNone`: "없음"
- [ ] **Step 4: 시험**
  - JVM 전체를 돌린다.
  - S22에서 다음 클래스를 하나씩 돌린다: `RideDatabaseMigrationTest`, `RoomTripLabelStoreTest`, `SettingsScreenTest`
- [ ] **Step 5: 커밋** `app: share — DB v7 share text, default hashtags setting`

### Task 3: 안드로이드 경계 — 앱 찾기, 인텐트, 갤러리 후보

**Files:**
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/share/ShareApps.kt`
  - `share/ShareIntents.kt`
  - `share/ShareCandidates.kt`
- Modify:
  - `app/src/main/AndroidManifest.xml` (`<queries>`)
  - `video/MediaStoreWriter.kt` (`fun sizeOf(uri: Uri): Long?` 추가)
- Test (androidTest): `share/ShareIntentsTest.kt`, `share/ShareCandidatesTest.kt`

**Interfaces:**
- Consumes: Task 1 `ShareTarget`, `ShareCandidate`, `emailSubject`, `MAX_SHARE_TEXT`; `MediaKind.mimeType`; `VideoOptionsJson.decode(optionsJson).aspect`
- Produces:
  - `interface ShareApps { fun available(target: ShareTarget): Boolean; fun instagramFeed(): ComponentName?; fun instagramStory(): ComponentName?; fun icon(target: ShareTarget): Drawable? }`
  - `class PackageShareApps(context: Context) : ShareApps`
    - `available`:
      - FACEBOOK: `com.facebook.katana`가 `ACTION_SEND image/png`를 받는다.
      - INSTAGRAM·STORY: `com.instagram.android`가 같은 것을 받는다.
      - EMAIL: `ACTION_SENDTO mailto:`를 받는 앱이 있다.
      - COPY·SAVE·MORE: 늘 true
    - `instagramFeed`: 인스타의 `ACTION_SEND image/png` 받는 곳 가운데 클래스 이름에 "Story"·"Direct"가 없는 첫 것
    - `instagramStory`: 클래스 이름에 "Story"가 든 첫 것
    - `icon`: FACEBOOK·INSTAGRAM·STORY에 그 앱 아이콘(`loadIcon`)
  - `object ShareIntents`:
    - `fun file(target: ShareTarget, file: ShareCandidate, text: String, apps: ShareApps): Intent`
    - `fun textOnly(target: ShareTarget, text: String): Intent`
    - 스펙 §4.2 표를 그대로 따른다.
      - 파일에는 `EXTRA_STREAM`, `type`, `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`
      - FACEBOOK: `setPackage("com.facebook.katana")`
      - INSTAGRAM: `instagramFeed()?.let(setComponent) ?: setPackage("com.instagram.android")`
      - STORY: `instagramStory()?.let(setComponent) ?: (INSTAGRAM과 같음)`
      - EMAIL: `selector = Intent(ACTION_SENDTO, "mailto:".toUri())`, `EXTRA_SUBJECT = emailSubject(text)`, `EXTRA_TEXT = text`
      - MORE: `Intent.createChooser(send(+EXTRA_TEXT), "활동 공유하기")`
      - textOnly: `type "text/plain"`, `EXTRA_TEXT`. EMAIL은 `EXTRA_SUBJECT`와 selector를 더하고, MORE는 chooser로 감싼다.
  - `class ShareCandidates(private val writer: MediaStoreWriter)`, `suspend fun of(media: List<TripMedia>): List<ShareCandidate>`
    - IO에서 돈다.
    - `writer.exists(uri)`가 false인 것은 뺀다.
    - `sizeBytes = writer.sizeOf(uri)`
    - aspect는 `optionsJson`에서 읽는다. 읽지 못하면 PORTRAIT이다.
  - `<queries>`: package `com.facebook.katana`, `com.instagram.android`; intent `ACTION_SEND` `*/*`; intent `ACTION_SENDTO` scheme `mailto`

- [ ] **Step 1: 실패하는 시험** (`ShareIntentsTest`, 가짜 `ShareApps`, 인텐트를 띄우지 않고 내용만 본다)
  - `facebookTargetsPackage`: package == "com.facebook.katana", type "video/mp4", `EXTRA_STREAM` == uri, flags에 GRANT_READ
  - `instagramUsesFeedComponent`: 가짜 feed 컴포넌트 → component 일치
  - `storyUsesStoryComponent`
  - `storyFallsBackToInstagramPackage`: story null → package "com.instagram.android", component null
  - `emailHasSelectorSubjectText`: selector.data == "mailto:", `EXTRA_SUBJECT` == 첫 줄, `EXTRA_TEXT` == 글
  - `moreIsChooserWithTitle`: action `ACTION_CHOOSER`, `EXTRA_TITLE` "활동 공유하기", 안쪽 `EXTRA_TEXT`·`EXTRA_STREAM`
  - `textOnlyHasNoStream`
  - `ShareCandidatesTest`:
    - `deletedGalleryFileIsNotACandidate`: MediaStore에 실제로 만든 항목을 지운 뒤 `of()` → 빠짐
    - `sizeAndAspectRead`: 만든 항목의 크기 > 0, `optionsJson` PORTRAIT → `Aspect.PORTRAIT`
    - 시험이 만든 갤러리 파일은 끝나면 지운다(기존 `MediaStoreWriterTest`처럼).
- [ ] **Step 2: 구현**
  - `sizeOf`: `MediaStore.MediaColumns.SIZE`를 읽는다. 행이 없거나 0이면 null.
- [ ] **Step 3: S22에서 시험** — `ShareIntentsTest`, `ShareCandidatesTest`, `MediaStoreWriterTest`를 하나씩 돌린다.
- [ ] **Step 4: 커밋** `app: share — app lookup, intents, gallery candidates`

### Task 4: ShareViewModel — 열기, 글, 단추, 만들고 이어서 보내기, 저장

**Files:**
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareViewModel.kt`
  - `ui/share/ShareState.kt` (state·event 타입)
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/share/ShareViewModelTest.kt`
  - 기존 VM 시험의 fake(`VideoJobControl`, `TripLabelStore`, `RideStore`, `TripStore`, `TripMediaStore`)를 재사용하거나 같은 모양으로 만든다.

**Interfaces:**
- Consumes:
  - Task 1 전부
  - Task 2 `TripLabel.shareText/shareStamp`, `setShareText`, `AppSettings.shareHashtags`
  - `VideoJobControl.startImage/state/cancel/acknowledge`, `StartResult.message`, `canMakeMedia`
  - `RideTitle.shown`
- Produces:
  - `data class ShareSheetState(val tripId: Long, val text: String, val autoText: String, val stale: Boolean, val moods: Set<Mood>, val general: SharePick, val story: SharePick, val email: SharePick, val fileLines: List<String>, val blocked: String?, val canRetry: Boolean, val making: Boolean, val tripFinished: Boolean)`
  - `sealed interface ShareEvent`:
    - `data class Send(val target: ShareTarget, val file: ShareCandidate?, val text: String)`: file이 null이면 글만 보낸다.
    - `data class Copy(val text: String)`
    - `data class Notice(val message: String)`
  - `class ShareViewModel(...)`
    - `val sheet: StateFlow<ShareSheetState?>`: null이면 닫힘
    - `val events: Flow<ShareEvent>`: `Channel` 기반, 한 번씩
    - `fun open(tripId: Long)`, `fun close()`
    - `fun edit(text: String)`: `MAX_SHARE_TEXT`에서 코드 포인트로 자른다.
    - `fun reset()`, `fun toggle(mood: Mood)`
    - `fun press(target: ShareTarget)`, `fun cancelMaking()`, `fun retry()`
    - `companion fun factory(container: AppContainer)`

**규칙** (스펙 §2·§3을 그대로 따른다):
- **`open`:**
  - 여행, 구간(10 m 이상 끝난 것), 이름표, 기본 해시태그, 후보(`ShareCandidates.of`)를 읽는다.
  - `autoText`를 만든다(`ShareFacts`: 제목 `RideTitle.shown`, 날짜는 첫·마지막 구간 `localDate`, 합계).
  - `text = label.shareText ?: autoText`
  - `stale = label.shareStamp != null && label.shareStamp != shareStamp(지금)`
  - `tripFinished = canMakeMedia(trip, segments)`
  - 세 pick을 계산한다. `lastEndedAt` = 끝난 구간 `endedAt`의 최댓값.
  - 여행이 끝나지 않았고 pick이 MakeImage이면 `blocked = "여행을 마치면 그림을 만들 수 있습니다"`, `canRetry = false`.
- **`press(target)`:**
  1. 저장: COPY 포함 모든 단추가 저장한다. `text == autoText`이면 `setShareText(tripId, null, null)`, 아니면 `(text, shareStamp(지금))`.
  2. **COPY:** `Copy(text)`
  3. **SAVE:** pick이 File이면 `Notice("갤러리에 저장되어 있습니다")`. MakeImage면 만든다. 만든 뒤 `Notice("갤러리에 저장했습니다")`.
  4. **나머지:** pick이 File이면 `Send(target, file, text)`. MakeImage면:
     - 여행이 끝나지 않았거나 blocked면 EMAIL·MORE는 `Send(target, null, text)`이고, 나머지는 무시한다(화면에서 흐림).
     - 그렇지 않으면 만든다.
- **만들기:**
  - `startImage(tripId, Aspect.PORTRAIT)`를 부른다.
  - `STARTED`가 아니면 `blocked = result.message(IMAGE_BYTES)`, `canRetry = true`, 대기 중인 단추는 버린다.
  - `STARTED`면 `making = true`, 대기 중인 단추를 기억하고 `job.state`를 본다.
    - **`Done`**(같은 여행의 IMAGE):
      1. `acknowledge()`를 부른다.
      2. 후보를 다시 읽고 pick을 다시 계산한다.
      3. `making = false`로 둔다.
      4. 대기 중인 단추의 동작을 한다. 새 이미지로 보내거나, SAVE면 Notice를 낸다.
    - **`Failed(msg)`:** `acknowledge()`, `blocked = msg`, `canRetry = true`, `making = false`
- **`cancelMaking`:** `job.cancel()`, `making = false`, 대기 중인 단추를 버린다.
- **`close`:**
  - 대기 중인 단추를 버린다. 만들기는 취소하지 않는다(스펙 §2.4).
  - `sheet = null`, state 구독을 멈춘다.
  - 나중의 `Done`은 이 VM이 `acknowledge`하지 않는다. 여행 화면 진행 카드가 지금처럼 처리한다.
- **`retry`:** `blocked = null`, 마지막에 누른 단추로 `press`를 다시 한다.

- [ ] **Step 1: 실패하는 시험** (`ShareViewModelTest`)
  - **열기와 글:**
    - `openBuildsAutoText`
    - `openUsesSavedText`
    - `staleWhenStampDiffers`
    - `pressSavesNullWhenUnchanged`
    - `pressSavesTextAndStampWhenEdited`
    - `resetGoesBackToAuto`
    - `toggleMoodUpdatesTextAndSet`
    - `editCapsAt2000CodePoints`
  - **단추:**
    - `copyEmitsCopy`
    - `facebookWithFreshVideoSendsVideo`
    - `storyWithLandscapeOnlyMakesPortraitImage`: `startImage(…, PORTRAIT)`가 불리고, Done 뒤에 `Send(STORY, 새 이미지)`
  - **만들기:**
    - `closingWhileMakingDoesNotSendLater`: close 뒤 Done이 와도 Send가 없고 `acknowledge`도 부르지 않는다.
    - `failedMakingReturnsToButtonsWithMessage`
    - `busyBlocksWithMessageAndRetry`: BUSY → blocked "영상을 만드는 중이라 지금은 이미지를 만들 수 없습니다"
    - `cancelStopsJob`
  - **끝나지 않은 여행:**
    - `unfinishedTripTextOnly`: ACTIVE 여행 → blocked "여행을 마치면 그림을 만들 수 있습니다", EMAIL → `Send(EMAIL, null, text)`, FACEBOOK → 아무것도 없음
  - **저장:**
    - `saveWithFileNotices`: "갤러리에 저장되어 있습니다"
    - `saveMakesThenNotices`: "갤러리에 저장했습니다"
- [ ] **Step 2: 구현**
  - 계산은 `Dispatchers.Default`, 읽기는 IO에서 한다.
  - `AppContainer`에 `shareCandidates`(lazy)를 더한다.
- [ ] **Step 3: 시험 통과**
  - `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.share.*'`
  - 그다음 JVM 전체를 돌린다.
- [ ] **Step 4: 커밋** `app: share — sheet state, make-then-send, saved text`

### Task 5: 시트 화면, 공유 단추 세 곳, BikeNavHost 연결

**Files:**
- Create:
  - `app/src/main/java/com/cowork/bikerecoder/ui/share/ShareSheet.kt` (`ShareSheet`, `ShareTags`)
  - `ui/share/ShareHost.kt`
- Modify:
  - `ui/feed/FeedCard.kt` (숫자 줄 끝 공유 아이콘), `ui/feed/FeedViewModel.kt` (`FeedCardState.canShare`)
  - `ui/feed/FeedScreen.kt` (`onShare`)
  - `ui/history/TripDetailScreen.kt` (`TripTitleRow`에 ✎ 앞 공유 아이콘), `TripDetailViewModel` (`canShare`)
  - `ui/record/SummaryExtras.kt` (`SummaryExtrasState.shareTripId`, `SummaryExtrasContent`의 `onShare`)
  - `ui/record/RecordScreen.kt`, `ui/navigate/NavigateScreen.kt` (`onShare` 전달)
  - `ui/BikeNavHost.kt` (activity 범위 `ShareViewModel`, `ShareHost` 한 번 그림, 세 곳의 `onShare = shareViewModel::open`)
- Test (androidTest):
  - `ui/share/ShareSheetTest.kt`
  - 기존 `FeedScreenTest`, `TripDetailScreenTest`, `RecordScreenTest`에 공유 단추 시험을 더한다.

**Interfaces:**
- Consumes: Task 3 `ShareApps`, `ShareIntents`; Task 4 `ShareViewModel`, `ShareSheetState`, `ShareEvent`
- Produces:
  - `@Composable fun ShareSheet(state: ShareSheetState, apps: ShareApps, onEdit: (String) -> Unit, onReset: () -> Unit, onMood: (Mood) -> Unit, onPress: (ShareTarget) -> Unit, onCancelMaking: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun ShareHost(viewModel: ShareViewModel, apps: ShareApps = rememberPackageShareApps(), launch: (Intent) -> Unit = defaultLaunch)`
    - `ShareEvent`를 모은다.
    - **Send:**
      - file이 있으면 `ShareIntents.file`, 없으면 `textOnly`로 인텐트를 만든다.
      - FACEBOOK·INSTAGRAM·STORY면 먼저 클립보드에 글을 넣고 "글을 복사했습니다. 붙여 넣으세요"를 띄운다.
      - 인텐트를 띄운 뒤 `viewModel.close()`를 부른다.
      - `ActivityNotFoundException`이 나면 "열 수 있는 앱이 없습니다"를 띄운다.
    - **Copy:** 클립보드에 넣는다(label "활동"). API 33 미만이면 "복사했습니다"를 띄운다.
    - **Notice:** 알림을 띄운다.
  - **공유 아이콘:** `Icons.AutoMirrored.Filled.Send`, contentDescription "공유", 48dp
  - **`FeedCardState.canShare`:** 10 m 이상 끝난 주행이 하나라도 있다.
  - **`SummaryExtrasState.shareTripId`:** 그 구간의 여행이다. 그 여행에 10 m 이상 끝난 주행이 있을 때만 있고, 진행 중 여행이어도 된다.

**시트 배치** (스펙 §1.2, 위에서 아래로):
1. 제목 "활동 공유하기"
2. 감정 칩 가로 줄(`LazyRow` `FilterChip` "😊 상쾌", selected = `mood in state.moods`, 설명 "감정: 상쾌")
3. 글 상자(`OutlinedTextField`, minLines 4, maxLines 8)와 오른쪽 아래 [처음 글로](`TextButton`). `stale`이면 상자 아래에 "기록이 바뀌어 숫자가 다를 수 있습니다"를 쓴다.
4. `fileLines` 또는 `blocked`(오류 색)와 `canRetry`면 [다시 시도]
5. `making`이면 "요약 이미지를 만드는 중…" + `LinearProgressIndicator` + [취소]. 아니면 단추 두 줄(4 + 3):
   - 동그란 아이콘(56dp) 아래 이름
   - `apps.available(target)`이 false면 숨긴다.
   - blocked이거나 여행이 끝나지 않았는데 pick이 MakeImage면 [페이스북]·[인스타그램]·[스토리]·[저장]을 `enabled = false`로 흐리게 한다.

- [ ] **Step 1: 실패하는 시험** (`ShareSheetTest`, 가짜 apps)
  - `allSevenButtonsWhenAppsPresent`
  - `missingAppsHidden`: FB·IG 없음 → [페이스북]·[인스타그램]·[스토리] 없음
  - `blockedDimsFileButtonsAndShowsReason`
  - `makingShowsProgressAndCancel`
  - `moodChipToggles`
  - `resetButton`
  - `staleLine`
  - `fileLinesShown`
  - `screenshot("v150-share-sheet")`, `screenshot("v150-share-making")`
  - `FeedScreenTest.shareIconOnCardWithRide` / `noShareIconWithoutRide`
  - `TripDetailScreenTest.shareIconOpens`
  - `RecordScreenTest.summaryShareButton`
- [ ] **Step 2: 구현**
  - 시트 상태는 VM이 들고 있다. 화면 회전 뒤에도 글이 남는다(activity 범위 VM).
  - `ShareHost`는 BikeNavHost 맨 위에서 한 번만 그린다.
- [ ] **Step 3: 시험**
  - S22에서 다음 클래스를 하나씩 돌린다: `ShareSheetTest`, `FeedScreenTest`, `TripDetailScreenTest`, `RecordScreenTest`, `MainTabsTest`
  - JVM 전체를 돌린다.
- [ ] **Step 4: 커밋** `app: share — sheet, share buttons on card, trip and summary`

### Task 6: 버전·문서 (part A). 기기 전체 시험·설치는 최종 수정 뒤 (part B)

**Files:**
- Modify:
  - `app/build.gradle.kts` (verMinor 5, verPatch 0 → 10500, `1.5.0-dev`)
  - `SettingsScreenTest` ("V1.5.0"), `AppVersionTest`
  - `CHANGELOG.md` (`## [1.5.0] - 개발 중`)
  - `README.md` (현재 버전, 로드맵 V1.5.0·V1.6.0 GPX 예정)
  - `docs/VERSIONING.md` (V1.5.0 — 개발 중, V1.6.0 GPX 가져오기 — 예정)
- Create: `docs/verification/share-field-test.md` (JVM 결과, S22 자리 "최종 수정 뒤 기록", 스펙 §7.3 점검표 10항목)

- [ ] **Step 1:** 버전과 시험 두 개를 고치고, `./gradlew :core:test :app:testDebugUnitTest`를 돌린다.
- [ ] **Step 2:** 문서를 쓴다. CHANGELOG 추가·변경(Room 7판, 설정 "공유")·문서 줄에 스펙·계획·검증 경로를 적는다.
- [ ] **Step 3:** S22에서 `SettingsScreenTest`만 돌린다.
- [ ] **Step 4:** 커밋 `docs: V1.5.0 version, changelog, README and verification`
- [ ] **(part B, 컨트롤러가 최종 검토와 수정 뒤에 함)**
  - S22에서 androidTest 전체를 클래스 하나씩 돌린다.
  - `v150-*.png`를 받아 사용자에게 보낸다.
  - S22에 `installDebug`한다.
  - 검증 문서에 결과를 적고 커밋한다.
