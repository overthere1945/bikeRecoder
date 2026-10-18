# 주행 사용성 개선 (V1.1.0 포함) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기록 코어 V1.1.0 위에 즐겨찾기(집·회사·직접 입력 칩), 검색 결과 키보드 여백, 기록 중 안내 화면 버튼([안내 취소 · 기록 계속]), 기록 중 새 목적지 안내(같은 여행에 붙이기, 이어서/나누기/묻기 설정), 경로 계획 화면 길게 누르기를 더한다.

**Architecture:** 즐겨찾기 규칙은 새 패키지 `favorite`의 `FavoriteRepository`(저장소 인터페이스 위, JVM 테스트)에 두고 Room v3 `favorite_place`로 저장한다. 길게 누르기·주소 조회는 `ui.common.PlaceLookup` 하나를 지도 메인(`MainViewModel`)과 경로 계획(`PlanViewModel`)이 함께 쓴다. 기록 중 새 안내는 `TripStartViewModel`이 기록 중인 여행에 `changeStops` 후 그 여행으로 안내를 시작하고, "새 구간"은 `RideController.splitSegment()`가 같은 위치 흐름 위에서 구간을 닫고 바로 새 구간을 연다. 음성과 같은 문구의 화면 표시는 `RideController.notice`(한 번 보이는 알림) 하나로 기록·안내 화면이 나눠 받는다.

**Tech Stack:** 기존과 동일 — Kotlin 2.4.20, AGP 9.4.1, Compose(BOM 2026.09.00), MapLibre 13.6.1, Room 2.8.5(+ room-testing), DataStore 1.2.1, coroutines 1.11.0, JUnit 5(JVM) / AndroidX Test + Compose UI test(기기, JUnit4). 새 외부 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md` (실행자는 스펙과 이 계획을 함께 읽는다)

## Global Constraints

- 버전은 그대로 `1.1.0`(versionCode 10100, debug `1.1.0-dev`, 화면 `V1.1.0`). 이 작업은 V1.1.0에 포함된다
- 패키지: 신규 `com.cowork.bikerecoder.favorite`(모델·규칙·저장소 인터페이스), `ui.favorite`(칩·대화상자·뷰모델). 공통 부품은 `ui.common`. 기존 패키지 구조 유지
- minSdk 30, targetSdk/compileSdk 37, JVM 17, MapLibre는 `AndroidView`로만·메인 스레드에서만, 의존성 주입 라이브러리 금지(`AppContainer` 수동 구성), 차트 라이브러리 금지
- 모든 사용자 노출 문구는 한국어, 스펙 문구 그대로: "🏠 집" / "🏢 회사" / "★ {이름}" / "즐겨찾기에 저장" / "즐겨찾기에서 삭제할까요?" / "기존 집을 이 장소로 바꿀까요?" / "같은 이름의 즐겨찾기가 있습니다" / "즐겨찾기는 20개까지 저장할 수 있습니다" / "이름 바꾸기" / "삭제" / "직접 입력" / "안내 취소 · 기록 계속" / "주행 종료" / "안내를 마칩니다. 기록은 계속됩니다" / "목적지 정하기" / "안내를 새로 시작할 때 기록" / "자동으로 이어서 기록" / "자동으로 새 구간 나누기" / "매번 물어보기" / "기록을 어떻게 할까요?" / "이어서 기록" / "새 구간으로 나누기" / "취소" / "새 구간으로 기록을 시작합니다" / "경유지로 추가" / "목적지로 설정" / "주소를 확인하는 중…"
- 수치(스펙 §1): 직접 입력 이름 1~20자(앞뒤 공백 제외), 즐겨찾기 최대 20개(집·회사 포함), 집·회사 각 1개, 정렬 집 → 회사 → 직접 입력(생성 순). 스낵바 약 4초 = `SnackbarDuration.Short`
- 스레드(V1.0.0 R12, 기록 코어): `RideController`·`NavigationController`의 공개 메서드와 세션은 `AppContainer.rideScope`(`Dispatchers.Main.immediate`) 한 스레드에서만. 구간 나누기의 위치 다리(bridge) 수집도 그 스코프에서. GPS 감시는 tick 시각만(R11)
- 기록 코어 판정 유지: P1~P4(재기준·터널·배너, closeInterrupted, 자정 구간, `replay = 1`), F1(음소거는 안내 실행 하나의 것 — 안내가 끝나면 풀림), F2(안내 숫자 쪽의 기록 조작)
- Room: DB 버전 3, `exportSchema = true`, `autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]`(2→3은 테이블 추가만), 빌드가 만드는 `app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json`을 커밋한다
- 설정 기본값: `recordingOnNewGuidance = GuidanceRecording.ASK`("매번 물어보기"). 기존 `autoStartRecordingWithNavigation = true`, `autoPauseEnabled = true` 그대로
- 테스트: JVM은 JUnit5(`org.junit.jupiter`), 기기는 JUnit4 + `androidx.compose.ui.test.junit4.v2.createComposeRule`(IME 테스트만 `createAndroidComposeRule<ComponentActivity>()`). core `./gradlew :core:test --tests '<FQCN>'`, app JVM `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`
- 기기 테스트(사용자 허락, 2026-10-18): 먼저 `adb devices`. **`connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 주행 기록이 사라지므로 S22 Ultra `R5CT43TFS9A`에서만 실행한다**: `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`(한 번에 클래스 하나 — 쉼표 목록은 첫 클래스만 돈다). S25 Ultra `R3CY903ZYQV`(사용자의 실제 기록)에는 `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug`만 허용. `R5CT43TFS9A`가 없으면 `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`로 컴파일만 확인하고 기기 실행을 "기기 검증 대기"로 남긴다. Compose UI 테스트는 휴대폰 잠금을 푼 상태에서, 화면 밖일 수 있는 항목은 `performScrollTo()` 뒤에 확인한다. AVD를 만들지 않는다
- 기존 테스트는 모두 그대로 통과해야 한다. 동작이 의도적으로 바뀌어 고치는 테스트는 그 Task가 이름을 밝힌다(Task 7의 `TripStartViewModelTest` 6개뿐)
- 커밋 끝에 `Co-Authored-By:` 트레일러로 커밋을 쓴 모델을 밝힌다(R10)

## Review Focus

1. **구간을 나눈 뒤 1km 안내가 1부터 다시 세거나 같은 km를 또 말함** — 새 구간은 오늘 거리(닫은 구간 포함)로 시작해 km가 이어져야 한다. → Task 6 `splitContinuesTheDayKmCount`.
2. **나누는 동안 위치를 잃거나 GPS를 다시 켬** — 공유 위치 흐름은 구독자가 0이 되면 위쪽을 멈추고 마지막 위치도 버린다(`replayExpirationMillis = 0`). 구간을 닫고 새로 여는 동안(DB 쓰기) 받은 위치는 새 구간에 들어가야 하고 위쪽 구독은 한 번뿐이어야 한다. → Task 6 `splitKeepsTheFixesThatArriveWhileTheSegmentsChange`, `splitKeepsTheLocationStreamSubscribed`.
3. **나누는 동안 잠깐 Idle/Finished가 보임** — 주행 서비스는 "아무것도 안 달림"이면 바로 멈추고, 메인 화면은 Finished를 보면 요약 화면을 연다. 나누기는 Active → Starting → Active로만 바뀌어야 한다. → Task 6 `splitNeverShowsASummaryOrIdle`.
4. **[안내 취소 · 기록 계속]의 화면 문구를 안내 화면이 먼저 삼킴** — 안내가 Idle이 된 뒤 기록 화면으로 넘어가기 전에 안내 화면이 알림을 "보였음"으로 처리하면 문구가 사라진다. 안내 화면은 안내가 Starting/Active일 때만 알림을 받는다. → Task 8 `guidanceScreenTakesNoticesOnlyWhileGuiding`.
5. **같은 여행을 안내하는 중에 다시 [안내 시작]** — `NavigationController.begin`은 같은 여행이 이미 안내 중이면 아무것도 하지 않아 새 목적지가 조용히 무시된다. 붙이기 전에 그 여행의 안내를 닫아야 한다. → Task 7 `attachingClosesAGuidanceOfThatTripFirst`.

---

## 파일 구조

```
core/src/main/kotlin/com/cowork/bikerecoder/core/voice/KoreanPhrases.kt   (수정) 문구 2개

app/src/main/java/com/cowork/bikerecoder/
  favorite/Favorite.kt               FavoriteKind, Favorite, FavoritePlace, FavoriteStore, FavoriteResult, FavoriteRepository
  data/Entities.kt, Daos.kt, AppDatabase.kt (수정)  data/RoomFavoriteStore.kt (신규)
  data/SettingsRepository.kt         (수정) GuidanceRecording, recordingOnNewGuidance
  AppContainer.kt                    (수정) favorites
  ride/RideNotice.kt (신규), ride/RideControls.kt, ride/RideController.kt (수정)
  ui/common/PlaceLookup.kt (신규)    PressedPlace(ui.main에서 옮김), PlaceLookup, PlaceSearch.addressOrNull, PressedPlaceSheet
  ui/common/PlaceActionSheet.kt      (수정) [즐겨찾기에 저장]
  ui/favorite/{FavoritesViewModel,FavoriteChips,FavoriteDialogs}.kt (신규)
  ui/main/{MainScreen,MainViewModel}.kt, ui/search/SearchScreen.kt, ui/plan/{PlanMap,PlanScreen,PlanViewModel}.kt (수정)
  ui/navigate/GuidanceButtons.kt (신규), ui/navigate/{NavigateScreen,NavigateViewModel}.kt (수정)
  ui/record/{RecordScreen,RecordViewModel}.kt, ui/trip/{TripStartViewModel,TripChoices,TripDialogs}.kt (수정)
  ui/settings/{SettingsScreen,SettingsViewModel}.kt, ui/BikeNavHost.kt (수정)
app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json (빌드가 생성, 커밋)
app/src/test/.../favorite/{FakeFavoriteStore,FavoriteRepositoryTest}.kt, data/FavoriteMappingsTest.kt,
  ui/common/PlaceLookupTest.kt, ui/favorite/FavoritesViewModelTest.kt, ui/navigate/GuidanceButtonsTest.kt (신규)
app/src/androidTest/.../data/RoomFavoriteStoreTest.kt, ui/favorite/{FavoriteChipsTest,FavoriteDialogsTest}.kt,
  ui/search/SearchScreenTest.kt, ui/navigate/GuidanceButtonRowTest.kt, ui/trip/TripStartDialogsTest.kt (신규)
docs/verification/recording-field-test.md, CHANGELOG.md (수정)
```

---

### Task 1: 즐겨찾기 규칙 (JVM)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/favorite/Favorite.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/favorite/FakeFavoriteStore.kt`, `app/src/test/java/com/cowork/bikerecoder/favorite/FavoriteRepositoryTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class FavoriteKind { HOME, WORK, CUSTOM }

data class Favorite(
    val id: Long, val kind: FavoriteKind, val name: String,
    val point: GeoPoint, val address: String?, val createdAt: Long,
) {
    /** "🏠 집" / "🏢 회사" / "★ {name}" */
    val chipLabel: String
}

/** 저장하려는 장소: 시트 제목(검색 결과 이름, 길게 누르기는 주소 또는 좌표), 위치, 주소(없으면 null). */
data class FavoritePlace(val name: String, val point: GeoPoint, val address: String?)

interface FavoriteStore {
    fun observeAll(): Flow<List<Favorite>>      // 순서 없음
    suspend fun all(): List<Favorite>
    suspend fun insert(favorite: Favorite): Long // id = 0 → 새 id
    suspend fun update(favorite: Favorite)
    suspend fun delete(id: Long)
}

sealed interface FavoriteResult {
    data object Saved : FavoriteResult
    /** 집·회사가 이미 있음: [question]을 묻고 save(..., replace = true)로 다시 부른다. */
    data class ConfirmReplace(val question: String) : FavoriteResult
    data class Rejected(val message: String) : FavoriteResult
}

class FavoriteRepository(private val store: FavoriteStore, private val clock: () -> Long) {
    /** 집 → 회사 → 직접 입력(createdAt, 같으면 id 순). */
    val favorites: Flow<List<Favorite>>
    /** HOME/WORK는 [customName]을 무시하고 이름 "집"/"회사". */
    suspend fun save(kind: FavoriteKind, customName: String?, place: FavoritePlace, replace: Boolean = false): FavoriteResult
    /** CUSTOM만(require). 없는 id면 아무것도 하지 않고 Saved. */
    suspend fun rename(id: Long, name: String): FavoriteResult
    suspend fun delete(id: Long)
    companion object {
        const val MAX_COUNT = 20
        const val MAX_NAME_LENGTH = 20
        const val HOME_NAME = "집"
        const val WORK_NAME = "회사"
        const val MSG_DUPLICATE = "같은 이름의 즐겨찾기가 있습니다"
        const val MSG_LIMIT = "즐겨찾기는 20개까지 저장할 수 있습니다"
        const val MSG_NAME_LENGTH = "이름은 1~20자로 입력하세요"
        const val DELETE_QUESTION = "즐겨찾기에서 삭제할까요?"
        /** HOME → "기존 집을 이 장소로 바꿀까요?", WORK → "기존 회사를 이 장소로 바꿀까요?" */
        fun replaceQuestion(kind: FavoriteKind): String
    }
}
```
- 규칙(스펙 §1.2, 결정 표시):
  - HOME/WORK: 같은 종류가 있고 `replace = false` → `ConfirmReplace`, 아무것도 쓰지 않음. `replace = true` → 기존 행의 `point`·`address`만 바꾼다(id·createdAt 유지 — 결정: 칩 순서가 바뀌지 않게). 없으면 개수 ≥ 20 → `Rejected(MSG_LIMIT)`, 아니면 새로 넣는다(createdAt = clock()). 교체는 개수를 늘리지 않으므로 20개여도 된다(결정).
  - CUSTOM: 이름은 `trim()`. 길이가 1..20이 아니면 `Rejected(MSG_NAME_LENGTH)`(결정: 스펙에 문구 없음) → 다른 CUSTOM과 같은 이름이면 `Rejected(MSG_DUPLICATE)`(중복 검사는 직접 입력끼리만 — 스펙 그대로) → 개수 ≥ 20이면 `Rejected(MSG_LIMIT)` → 넣는다. 검사 순서는 이대로.
  - rename: 같은 규칙(자기 자신은 중복 검사에서 뺀다). 이름이 그대로면 쓰지 않고 Saved.
  - save·rename·delete는 `Mutex` 하나로 차례로 실행한다(두 번 누른 집 저장이 집 두 개를 만들지 않게).
- `FakeFavoriteStore`: `MutableStateFlow<List<Favorite>>` 기반, `var failWrites = false`(true면 insert/update/delete가 `IOException`).

- [ ] **Step 1: 실패하는 테스트** (`p = FavoritePlace("스타벅스 역삼점", GeoPoint(37.50, 127.03), "서울 강남구 역삼동")`, clock은 호출마다 1씩 증가)

```kotlin
@Test fun homeAndWorkAreOneEach()          // HOME 저장 → Saved; 다른 위치로 HOME → ConfirmReplace("기존 집을 이 장소로 바꿀까요?"), 저장소 그대로; replace=true → HOME 1개, 새 위치, id 그대로
@Test fun workReplaceQuestion()            // WORK 두 번째 → ConfirmReplace("기존 회사를 이 장소로 바꿀까요?")
@Test fun duplicateCustomNameIsRejected()  // "카페" 저장 후 " 카페 " → Rejected("같은 이름의 즐겨찾기가 있습니다"), 1개 그대로
@Test fun customNameMustBe1To20Chars()     // "", "   ", "가".repeat(21) → Rejected("이름은 1~20자로 입력하세요"); "가".repeat(20) → Saved
@Test fun twentyIsTheLimit()               // CUSTOM 20개 후 21번째 CUSTOM·새 HOME → Rejected("즐겨찾기는 20개까지 저장할 수 있습니다"); HOME 포함 20개에서 HOME 교체(replace=true) → Saved, 20개 그대로
@Test fun sortedHomeWorkThenCustomByCreation() // CUSTOM "B", WORK, CUSTOM "A", HOME 순으로 저장 → favorites.first() 이름 ["집", "회사", "B", "A"]
@Test fun chipLabels()                     // "🏠 집", "🏢 회사", "★ 카페"
@Test fun renameKeepsTheRules()            // "A","B" 있을 때 B→"A" Rejected(MSG_DUPLICATE); B→"B" Saved(쓰기 없음); B→"C" 이름 C
@Test fun deleteRemoves()
@Test fun concurrentHomeSavesKeepOneHome() // 같은 테스트 스코프에서 save(HOME) 두 개를 launch → 하나 Saved, 하나 ConfirmReplace, HOME 1개
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.favorite.FavoriteRepositoryTest'` → FAIL(미정의)
- [ ] **Step 3: 구현** — 위 시그니처와 규칙대로 `favorite/Favorite.kt`.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "app: favorite place rules"`

---

### Task 2: Room v3 `favorite_place`와 마이그레이션

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/data/Entities.kt`, `data/Daos.kt`, `data/AppDatabase.kt`, `AppContainer.kt`
- Create: `app/src/main/java/com/cowork/bikerecoder/data/RoomFavoriteStore.kt`, `app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json`(빌드 생성)
- Test: `app/src/test/java/com/cowork/bikerecoder/data/FavoriteMappingsTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/data/RoomFavoriteStoreTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/data/RideDatabaseMigrationTest.kt`(테스트 추가)

**Interfaces:**
- Consumes: Task 1 `Favorite`, `FavoriteKind`, `FavoriteStore`, `FavoriteRepository`
- Produces:
```kotlin
/** kind는 FavoriteKind의 name. */
@Entity(tableName = "favorite_place")
data class FavoritePlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val kind: String, val name: String, val lat: Double, val lon: Double, val address: String?, val createdAt: Long,
)
@Dao interface FavoriteDao {
    @Query("SELECT * FROM favorite_place") fun observeAll(): Flow<List<FavoritePlaceEntity>>
    @Query("SELECT * FROM favorite_place") suspend fun all(): List<FavoritePlaceEntity>
    @Insert suspend fun insert(entity: FavoritePlaceEntity): Long
    @Update suspend fun update(entity: FavoritePlaceEntity)
    @Query("DELETE FROM favorite_place WHERE id = :id") suspend fun delete(id: Long)
}
internal fun FavoritePlaceEntity.toFavorite(): Favorite   // FavoriteKind.valueOf(kind) (RoomTripStore와 같은 방식)
internal fun Favorite.toEntity(): FavoritePlaceEntity
class RoomFavoriteStore(db: AppDatabase) : FavoriteStore
// AppDatabase: entities += FavoritePlaceEntity, version = 3, autoMigrations += AutoMigration(from = 2, to = 3), abstract fun favoriteDao(): FavoriteDao
// AppContainer
val favorites: FavoriteRepository = FavoriteRepository(RoomFavoriteStore(db), clock = System::currentTimeMillis)
```

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// FavoriteMappingsTest (JVM)
@Test fun `favorite round trip keeps kind and null address`()   // Favorite(3, WORK, "회사", GeoPoint(37.5, 127.0), null, 1_000) → toEntity().toFavorite() 같음; kind 문자열 "WORK"
// RoomFavoriteStoreTest (기기, in-memory DB)
@Test fun insertObserveUpdateDelete()    // insert 2개 → observeAll().first() 2개; update 위치 → 반영; delete → 1개
// RideDatabaseMigrationTest (기기)
@Test fun v2DataSurvivesMigrationTo3()   // v2에 trip(id 5) + ride_segment(tripId 5, endedAt 있음) INSERT → runMigrationsAndValidate(DB, 3, true)
                                         // → RoomTripStore.trip(5) 그대로, RoomRideStore.segments(5) 1개, RoomFavoriteStore.all() 빈 목록, insert 동작
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.data.FavoriteMappingsTest'` → FAIL
- [ ] **Step 3: 구현** — 엔티티·DAO·매핑·저장소, DB v3. `./gradlew :app:assembleDebug`로 `3.json` 생성 확인(`git status`에 새 파일).
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL(기존 테스트 그대로); 기기(R5CT43TFS9A만) `…class=com.cowork.bikerecoder.data.RideDatabaseMigrationTest`, `…class=com.cowork.bikerecoder.data.RoomFavoriteStoreTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git add app/schemas/com.cowork.bikerecoder.data.AppDatabase/3.json …` 후 `git commit -m "data: favorite_place table, database v3"`

---

### Task 3: 공통 길게 누르기와 경로 계획 화면 길게 누르기 (스펙 §5)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/common/PlaceLookup.kt`
- Modify: `ui/common/PlaceActionSheet.kt`, `ui/main/MainViewModel.kt`(PressedPlace 제거, PlaceLookup 사용), `ui/main/MainScreen.kt`, `ui/plan/PlanViewModel.kt`, `ui/plan/PlanMap.kt`, `ui/plan/PlanScreen.kt`, `ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/common/PlaceLookupTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/plan/PlanViewModelTest.kt`(추가), `app/src/androidTest/java/com/cowork/bikerecoder/ui/plan/PlanScreenTest.kt`(추가)

**Interfaces:**
- Consumes: Task 1 `FavoritePlace`
- Produces:
```kotlin
// ui/common/PlaceLookup.kt
/** 길게 누른 지점과 주소 조회(ui.main에서 옮김, 내용 그대로). title = 주소, 없으면 "%.5f, %.5f"(Locale.ROOT). */
data class PressedPlace(val point: GeoPoint, val address: String?, val loading: Boolean) { val title: String }
/** 길게 누르기 → PressedPlace(loading) → 주소. 새로 누르면 이전 조회를 취소하고, 늦게 온 이전 결과는 버린다. */
class PlaceLookup(private val addressOf: suspend (GeoPoint) -> String?, private val scope: CoroutineScope) {
    val pressed: StateFlow<PressedPlace?>
    fun onLongPress(point: GeoPoint)
    fun dismiss()
}
/** Ok(비어 있지 않음) → 주소, Ok(null/공백)·Err·예외 → null(취소는 다시 던진다). */
suspend fun PlaceSearch.addressOrNull(point: GeoPoint): String?
/** 지도 메인과 경로 계획이 함께 쓰는 시트: 제목 = 조회 중 "주소를 확인하는 중…", 끝나면 title. */
@Composable fun PressedPlaceSheet(
    pressed: PressedPlace,
    onSetDestination: (name: String, point: GeoPoint) -> Unit,
    onAddWaypoint: (name: String, point: GeoPoint) -> Unit,
    onSaveFavorite: ((FavoritePlace) -> Unit)?,   // FavoritePlace(pressed.title, point, pressed.address)
    onDismiss: () -> Unit,
)
// PlaceActionSheet: 끝에 onSaveFavorite: (() -> Unit)? = null 추가 → null이 아니면 두 버튼 아래 전체 폭 OutlinedButton "즐겨찾기에 저장"
// (결정: 세 곳 모두 같은 시트·같은 버튼 배치 [목적지로 설정][경유지로 추가] / [즐겨찾기에 저장])
// PlanViewModel: 생성자 끝에 addressOf: suspend (GeoPoint) -> String? = { null }
val pressed: StateFlow<PressedPlace?>
fun onLongPress(point: GeoPoint)
fun dismissPressed()
// PlanMap(state, user, tileSource, modifier, onLongPress: (GeoPoint) -> Unit = {})
// PlanScreen(viewModel, map, onBack, onAddStop, onStartNavigation, onSaveFavorite: ((FavoritePlace) -> Unit)? = null)
```
- 동작: 경로 계획 시트의 [경유지로 추가] → `dismissPressed()` + `addWaypoint(name, point)`(목적지 바로 앞), [목적지로 설정] → `dismissPressed()` + `setDestination(name, point)`(목적지만 교체). 화면은 경로 계획에 머문다(재계산은 기존 debounce). `MainViewModel`은 `PlaceLookup({ placeSearch.addressOrNull(it) }, viewModelScope)`를 쓰고 `lookup.pressed`를 `MainUiState.pressed`로 옮겨 담는다(`onLongPress`·`dismissPressed` 이름 그대로). `MainScreen`의 인라인 시트는 `PressedPlaceSheet(onSaveFavorite = null)`로 바꾼다(Task 4에서 연결). PlanViewModel factory: `addressOf = { container.placeSearch.addressOrNull(it) }`. BikeNavHost PLAN: `PlanMap(…, onLongPress = planViewModel::onLongPress)`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// PlaceLookupTest (StandardTestDispatcher, addressOf는 CompletableDeferred로 막을 수 있는 가짜)
@Test fun longPressShowsLoadingThenTheAddress()   // onLongPress(p) → PressedPlace(p, null, loading = true); 주소 "부산 해운대구 우동" 도착 → (address, loading = false), title == 주소
@Test fun failedLookupFallsBackToCoordinates()   // addressOf → null → title == "37.50000, 127.00000", loading false
@Test fun aNewerLongPressWinsOverAnOlderLookup() // p1 조회 중 p2 → p1 결과가 늦게 와도 pressed.point == p2
@Test fun dismissCancelsTheLookup()              // dismiss → null, 이후 결과 와도 null
@Test fun addressOrNullMapsEveryFailureToNull()  // Ok("  ") / Ok(null) / Err(NETWORK) / IOException → null; Ok("역삼동") → "역삼동"
// PlanViewModelTest
@Test fun `long press then add waypoint puts it right before the destination`() // stops [남산, 여의도], onLongPress(p) → addWaypoint(pressed.title, p) → 이름 [남산, title, 여의도], pressed null
@Test fun `long press then set destination keeps the waypoints`()               // [남산, 여의도] → setDestination → [남산, title]
@Test fun `long press looks the address up`()                                   // addressOf 가짜 "부산 해운대구 우동" → pressed.address
// PlanScreenTest (기기)
@Test fun longPressSheetAddsAWaypointBeforeTheDestination() // 계획 [A, B]; runOnIdle { viewModel.onLongPress(p) } → "주소를 확인하는 중…" 표시(조회를 막아 둠) → 주소 도착 → "경유지로 추가" 클릭 → stopNames() == [A, 주소, B], 시트 사라짐; "즐겨찾기에 저장" 표시(onSaveFavorite 주었을 때)
@Test fun longPressSetDestinationKeepsWaypoints()           // [A, B] → "목적지로 설정" → [A, 주소]
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.common.PlaceLookupTest' --tests 'com.cowork.bikerecoder.ui.plan.PlanViewModelTest'` → FAIL
- [ ] **Step 3: 구현** — 위 인터페이스대로. `PlanViewModel`은 `PlaceLookup(addressOf, viewModelScope)`를 가진다.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL(`MainViewModelTest` 그대로); 기기 `…class=com.cowork.bikerecoder.ui.plan.PlanScreenTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: shared long-press place lookup; long-press on the plan map"`

---

### Task 4: 즐겨찾기 화면 — 칩 줄, 저장 대화상자, 길게 누르기 메뉴 (스펙 §1)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/favorite/FavoritesViewModel.kt`, `ui/favorite/FavoriteChips.kt`, `ui/favorite/FavoriteDialogs.kt`
- Modify: `ui/main/MainScreen.kt`, `ui/search/SearchScreen.kt`, `ui/plan/PlanScreen.kt`(이미 매개변수 있음), `ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/favorite/FavoritesViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/favorite/FavoriteChipsTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/favorite/FavoriteDialogsTest.kt`

**Interfaces:**
- Consumes: Task 1 `FavoriteRepository`·`FavoriteResult`·`Favorite`·`FavoritePlace`, Task 2 `AppContainer.favorites`, Task 3 `PressedPlaceSheet`·`PlaceActionSheet(onSaveFavorite)`·`PlanScreen(onSaveFavorite)`
- Produces:
```kotlin
sealed interface FavoriteDialog {
    /** "즐겨찾기에 저장": [집] [회사] / "직접 입력" 칸(place.name.take(20)로 채움) [저장] [취소]. [error]는 마지막 거절 문구(대화상자는 열린 채). */
    data class ChooseName(val place: FavoritePlace, val error: String? = null) : FavoriteDialog
    data class ConfirmReplace(val place: FavoritePlace, val kind: FavoriteKind, val question: String) : FavoriteDialog
    data class Rename(val favorite: Favorite, val error: String? = null) : FavoriteDialog
    data class ConfirmDelete(val favorite: Favorite) : FavoriteDialog   // "즐겨찾기에서 삭제할까요?" [삭제] [취소]
}
class FavoritesViewModel(private val repository: FavoriteRepository, private val log: WarnLog = androidWarnLog(TAG)) : ViewModel() {
    val favorites: StateFlow<List<Favorite>>   // Eagerly, 처음 emptyList; 흐름 실패는 로그 + 빈 목록
    val dialog: StateFlow<FavoriteDialog?>
    fun startSave(place: FavoritePlace)
    fun save(kind: FavoriteKind, customName: String? = null)   // ChooseName에서만
    fun confirmReplace()
    fun startRename(favorite: Favorite)
    fun rename(name: String)
    fun askDelete(favorite: Favorite)
    fun confirmDelete()
    fun dismiss()
    companion object {
        const val MSG_SAVE_FAILED = "즐겨찾기를 저장하지 못했습니다"   // 결정: 저장소 예외
        fun factory(container: AppContainer): ViewModelProvider.Factory
    }
}
object FavoriteTags { const val ROW = "favorite-row"; fun chip(id: Long) = "favorite-chip-$id" }
/** 등록된 것만, 없으면 아무것도 그리지 않는다. 탭 = onSelect, 길게 누르기 = 메뉴 [이름 바꾸기](CUSTOM만) [삭제]. 가로 스크롤. */
@Composable fun FavoriteChips(
    favorites: List<Favorite>, onSelect: (Favorite) -> Unit,
    onRename: (Favorite) -> Unit, onDelete: (Favorite) -> Unit, modifier: Modifier = Modifier,
)
@Composable fun FavoriteDialogs(
    dialog: FavoriteDialog?, onSave: (FavoriteKind, String?) -> Unit, onConfirmReplace: () -> Unit,
    onRename: (String) -> Unit, onConfirmDelete: () -> Unit, onDismiss: () -> Unit,
)
// MainScreen 끝에: favoriteChips: @Composable () -> Unit = {}, onSaveFavorite: ((FavoritePlace) -> Unit)? = null
// SearchScreen 끝에: favoriteChips: @Composable () -> Unit = {}, onSaveFavorite: ((FavoritePlace) -> Unit)? = null
```
- 결정: 집·회사 칩의 메뉴는 [삭제]만(이름이 곧 역할). 칩 탭의 경유지 이름은 즐겨찾기 이름("집"/"회사"/직접 입력). 즐겨찾기 칩은 검색 화면에도 검색어가 비어 있을 때 보인다(스펙 §4.1 "검색 화면으로 가며, 즐겨찾기 칩·지도 길게 누르기도 사용할 수 있다"). 검색 결과 시트의 저장 장소는 `FavoritePlace(place.name, place.point, place.address.ifBlank { null })`.
- 흐름: `save` → `Saved`면 닫힘, `ConfirmReplace`면 `ConfirmReplace` 대화상자, `Rejected`면 `ChooseName(error = message)`. `confirmReplace` → `save(kind, null, place, replace = true)`. `rename` 거절 → `Rename(error = message)`. 저장소 예외 → 같은 대화상자에 `MSG_SAVE_FAILED`.
- 연결(BikeNavHost): 활동 범위 `val favoritesViewModel: FavoritesViewModel`(PlanViewModel처럼 `viewModelStoreOwner = context.findActivity()`), `FavoriteDialogs`는 `NavHost` 뒤에 한 번. 칩 탭 → `setDestinationAndOpenPlan(favorite.name, favorite.point)`, 메뉴 → `favoritesViewModel::startRename`/`::askDelete`. 지도 메인 칩 줄은 검색창 줄 바로 아래(배너 위). 세 시트(지도 메인·검색 결과·경로 계획)의 `onSaveFavorite = favoritesViewModel::startSave`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// FavoritesViewModelTest (FakeFavoriteStore + FavoriteRepository, StandardTestDispatcher)
@Test fun saveHomeTwiceAsksToReplaceThenReplaces()  // startSave(p1) → ChooseName; save(HOME) → null(닫힘); startSave(p2), save(HOME) → ConfirmReplace(question = "기존 집을 이 장소로 바꿀까요?"); confirmReplace → null, favorites 집 1개 at p2
@Test fun refusalStaysInTheNameDialog()             // "카페" 있음 → save(CUSTOM, "카페") → ChooseName(error = "같은 이름의 즐겨찾기가 있습니다")
@Test fun limitIsShownInTheNameDialog()             // 20개 → save(CUSTOM, "새") → ChooseName(error = "즐겨찾기는 20개까지 저장할 수 있습니다")
@Test fun deleteAsksFirst()                         // askDelete(f) → ConfirmDelete(f); dismiss → 그대로 있음; askDelete, confirmDelete → 없어짐
@Test fun renameRefusalKeepsTheDialog()             // Rename에서 중복 이름 → Rename(error = MSG_DUPLICATE); 새 이름 → null, 이름 바뀜
@Test fun storeFailureShowsSaveFailed()             // failWrites → ChooseName(error = "즐겨찾기를 저장하지 못했습니다")
@Test fun favoritesAreSortedForTheChips()           // favorites.value 이름 순서 집 → 회사 → 직접 입력(생성 순)
// FavoriteChipsTest (기기)
@Test fun noFavoritesHidesTheRow()                  // 빈 목록 → onNodeWithTag(FavoriteTags.ROW).assertDoesNotExist()
@Test fun chipsShowInOrderAndATapSelects()          // "🏠 집", "🏢 회사", "★ 카페" 표시(performScrollTo 후); "★ 카페" 클릭 → onSelect(카페)
@Test fun longPressOnACustomChipOffersRenameAndDelete() // longClick → "이름 바꾸기", "삭제"; "삭제" → onDelete(카페)
@Test fun homeChipMenuHasOnlyDelete()               // 집 longClick → "삭제" 있음, "이름 바꾸기" 없음
// FavoriteDialogsTest (기기)
@Test fun chooseNameOffersHomeWorkAndCustom()       // "즐겨찾기에 저장", "집", "회사", "직접 입력"; 칸에 21자 입력 → 20자만 남음; 칸을 비우면 [저장] 비활성
@Test fun errorAndDeleteTexts()                     // ChooseName(error) → 문구 표시; ConfirmDelete → "즐겨찾기에서 삭제할까요?"
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.favorite.FavoritesViewModelTest'` → FAIL
- [ ] **Step 3: 구현** — 칩은 `Surface` + `combinedClickable(onClick, onLongClick)` + `DropdownMenu`(FilterChip은 길게 누르기를 받지 않음).
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.favorite.FavoriteChipsTest`, `…class=com.cowork.bikerecoder.ui.favorite.FavoriteDialogsTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: favorite chips, save dialog and chip menu"`

---

### Task 5: 검색 결과와 키보드 (스펙 §2)

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/ui/search/SearchScreen.kt`
- Test: `app/src/androidTest/java/com/cowork/bikerecoder/ui/search/SearchScreenTest.kt`(신규)

**Interfaces:**
- Produces:
```kotlin
object SearchTags { const val QUERY = "search-query"; const val RESULTS = "search-results" }
/** 사용자가 끌어서(NestedScrollSource.UserInput) 목록을 스크롤하기 시작하면 키보드를 내리고 포커스를 푼다. */
@Composable internal fun rememberHideKeyboardOnDrag(): NestedScrollConnection
```
- 결과 영역(`Box(weight(1f))`)에 `.imePadding()`(바깥 `systemBarsPadding()`이 내비게이션 바를 이미 소비하므로 키보드 높이만큼만 더해진다). `LazyColumn`에 `rememberLazyListState()`, `.nestedScroll(rememberHideKeyboardOnDrag())`, `testTag(RESULTS)`. 검색칸에 `testTag(QUERY)`.
- 결정: 키보드는 **사용자가 끌 때만** 내린다(코드가 하는 스크롤·`performScrollToNode`로는 내리지 않음) — 스펙 "스크롤하기 시작하면"을 손가락 스크롤로 읽었고, 그래야 "키보드가 떠 있어도 마지막 결과까지" 를 테스트로 보일 수 있다.

- [ ] **Step 1: 실패하는 테스트** — `createAndroidComposeRule<ComponentActivity>()`; `@Before`에서 `activity.enableEdgeToEdge()` + `window.setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)`(앱 MainActivity와 같은 조건); 가짜 `PlaceSearch`가 "장소 1".."장소 30"을 준다; `SearchViewModel(fake) { null }`. 키보드 높이는 `ViewCompat.getRootWindowInsets(decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom`, 키보드 위쪽 = `decorView.height - ime`.

```kotlin
@Test fun resultsEndAboveTheKeyboard()        // QUERY 클릭·"카페" 입력 → waitUntil(10_000){ ime > 0 && "장소 1" 보임 }
                                              // → RESULTS boundsInWindow.bottom ≤ 키보드 위쪽 + 1
                                              // → performScrollToNode(hasText("장소 30")) → "장소 30" assertIsDisplayed, bottom ≤ 키보드 위쪽 + 1, ime 아직 > 0
@Test fun draggingTheResultsHidesTheKeyboard() // 위와 같이 연 뒤 RESULTS performTouchInput { swipeUp() } → waitUntil(5_000){ ime == 0 }, QUERY assertIsNotFocused
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:assembleDebugAndroidTest` 성공 후 기기 `…class=com.cowork.bikerecoder.ui.search.SearchScreenTest` → FAIL(첫 테스트: 결과 아래가 키보드 뒤). 기기가 없으면 이 단계는 컴파일 확인으로 대신하고 "기기 검증 대기"
- [ ] **Step 3: 구현** — 위대로.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`(`SearchViewModelTest` 그대로); 기기 같은 클래스 → PASS(휴대폰 잠금 해제, 화면 키보드 사용 중이어야 함)
- [ ] **Step 5: Commit** — `git commit -m "app: search results clear the keyboard; drag hides it"`

---

### Task 6: 기록 알림 문구와 구간 나누기 — RideController (스펙 §3, §4.3의 JVM판)

**Files:**
- Modify: `core/src/main/kotlin/com/cowork/bikerecoder/core/voice/KoreanPhrases.kt`, `app/src/main/java/com/cowork/bikerecoder/ride/RideControls.kt`, `ride/RideController.kt`
- Create: `app/src/main/java/com/cowork/bikerecoder/ride/RideNotice.kt`
- Test: `core/src/test/kotlin/com/cowork/bikerecoder/core/voice/KoreanPhrasesTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ride/RideControllerTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ride/RideNavigationTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ride/RideTestDoubles.kt`(`FakeRideStore.openGate` 추가)

**Interfaces:**
- Produces:
```kotlin
// KoreanPhrases
val guidanceEndedRecordingGoesOn: String = "안내를 마칩니다. 기록은 계속됩니다"
val newSegmentStarted: String = "새 구간으로 기록을 시작합니다"
// ride/RideNotice.kt
/** 음성과 같은 문구를 화면에 한 번 보인다(스낵바). [id]는 알림마다 새 값. */
data class RideNotice(val id: Long, val text: String)
// RideControls (기본 구현이 있어 기존 구현·테스트 가짜는 그대로 컴파일된다)
fun announce(text: String) = Unit
fun noticeShown(id: Long) = Unit
// RideController
val notice: StateFlow<RideNotice?>
/** 기록 중이고 그 기록의 음성 설정이 켜져 있으면 말하고, 음성과 상관없이 [notice]에 올린다. */
override fun announce(text: String)
/** [notice]가 그 [id]일 때만 지운다. */
override fun noticeShown(id: Long)
/**
 * "새 구간으로 나누기": 기록 중인 구간을 SEGMENT_ONLY로 닫고(요약 없음) 같은 여행에 새 구간을 열어 바로 기록한다.
 * 시작 음성은 "기록을 시작합니다" 대신 [KoreanPhrases.newSegmentStarted]이고 같은 문구를 [notice]에 올린다.
 * 닫은 구간의 저장 문제면 [MSG_SAVE_PROBLEM]. 기록이 없거나 이미 멈추는 중이면 아무것도 하지 않고 null.
 */
suspend fun splitSegment(): String?
```
- `FakeRideStore`: `var openGate: CompletableDeferred<Unit>? = null` — 설정되어 있으면 `openSegment`가 그것을 기다린다.
- 내부 변경(가장 작은 범위):
  - `startRecording`의 본문을 `private fun launchRun(tripId: Long, startPhrase: String? = null, bridge: Bridge? = null): Job`로 옮긴다(`startRecording` = 기존 거부 검사 + `launchRun(tripId)`).
  - `Run`에 `startPhrase`, `bridge`, `skipUpTo: Long`(이미 넣은 마지막 위치 시각). `speech(Started)` → `r.startPhrase ?: phrases.recordingStarted`, `startPhrase`가 있으면 그 문구로 `notice`도 올린다(음성 꺼짐이어도).
  - `finish(r, end, publishSummary: Boolean = true): RideUiState.Finished?` — 만든 Finished를 돌려주고 `publishSummary`일 때만 `_ui`에 둔다. `stopRecording`은 그대로 `publishSummary = true`.
  - 알고리즘(시그니처가 정하지 않는 부분):
    1. `splitSegment`: `r = run ?: return null`; `r.startJob?.join()`; `run !== r || r.session == null || r.stopping`이면 null.
    2. 다리: `Bridge(fixes = mutableListOf(), job)`를 먼저 띄운다 — `scope.launch { location.fixes().collect { … } }`(실패는 로그만), 이전 구간이 이미 받은 위치(시간 ≤ `r.lastFix?.timeMillis`)는 넣지 않는다. 이렇게 공유 흐름의 구독자가 0이 되지 않는다.
    3. 닫기: `r.finishJob = scope.launch { finished = finish(r, SEGMENT_ONLY, publishSummary = false) }`를 기다린다(그사이 `_ui`는 이전 Active 그대로 — Idle/Finished를 거치지 않는다).
    4. 열기: `launchRun(r.tripId, phrases.newSegmentStarted, bridge).join()`; `finished?.error`를 돌려준다.
    5. 새 run의 `prepare`: 세션 시작·km 원천 설정 뒤 `bridge.fixes`를 시간순으로 `session.onFix`에 넣고 `skipUpTo`를 기록, 다리 job은 새 run의 `jobs`에 넣는다(시작 실패·정지 시 함께 취소). `collectLocation`은 첫 위치를 받으면 다리에 더 쌓인 위치(시간 > `skipUpTo`)를 먼저 넣고 다리를 멈춘 뒤, 시간이 `skipUpTo` 이하인 위치는 버린다.
  - km: 새 세션은 기존처럼 `rideStore.dayTotals`(닫은 구간 포함)로 시작하므로 km가 이어진다. 이전 run의 km 원천은 `finish`가 풀고 새 run이 다시 건다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// KoreanPhrasesTest
@Test fun rideUxPhrases()   // p.guidanceEndedRecordingGoesOn == "안내를 마칩니다. 기록은 계속됩니다", p.newSegmentStarted == "새 구간으로 기록을 시작합니다"
// RideControllerTest (straight(3_000.0) 18km/h, speedup 1; 나누기 테스트의 위치는 SharedLocationSource({ scripted }, backgroundScope)로 앱과 같게)
@Test fun splitClosesTheSegmentAndRecordsANewOneOfTheSameTrip() // 100m 뒤 splitSegment() == null → segments(trip) 2개, 첫째 endedAt != null, 둘째 열림, active.segmentId == 둘째, 같은 tripId, 여행 ACTIVE, trips 1개
@Test fun splitNeverShowsASummaryOrIdle()        // 나누는 동안 ui를 모두 모음 → Idle·Finished 없음, 순서 Active → Starting → Active
@Test fun splitAnnouncesTheNewSegmentOnceByVoiceAndOnScreen() // spoken: "기록을 시작합니다" 1회(처음), "새 구간으로 기록을 시작합니다" 1회; notice.text == "새 구간으로 기록을 시작합니다"
@Test fun splitKeepsTheFixesThatArriveWhileTheSegmentsChange() // openGate 설정 → split launch → play(3_000)(위치 3개) → gate 완료 → play(5_000) → stop: 둘째 구간 points 시각에 그 3개 포함, 두 구간에 같은 시각 점 없음
@Test fun splitKeepsTheLocationStreamSubscribed() // 위쪽 fixes() 시작 횟수(onStart 세는 가짜로 감쌈) == 1
@Test fun splitContinuesTheDayKmCount()          // 1_200m(1km 1회) → split → 2_100m → kmReports() == [1, 2]
@Test fun splitReportsASaveProblem()             // failAppend = true → splitSegment() == "기록 저장에 문제가 있습니다", 그래도 새 구간 Active
@Test fun splitWithoutARecordingDoesNothing()    // Idle에서 null, 구간 없음
@Test fun announceSpeaksAndShowsTheSameTextOnce() // 기록 중 announce("안내를 마칩니다. 기록은 계속됩니다") → spoken에 1회, notice.text 같음; noticeShown(다른 id) → 그대로; noticeShown(id) → null
@Test fun announceWithVoiceOffOnlyShows()        // settings voiceEnabled=false → spoken에 없음, notice 있음
// RideNavigationTest
@Test fun recordingFirstThenGuidanceOfTheSameTripKeepsTheSegment() // ride.startRecording(trip) → play → navigation.start(trip) → 구간 1개 그대로(열림), "기록을 시작합니다" 1회, 안내 뒤 km는 combinedKms()로만(guidanceKms() 빈 목록)  ← "이어서 기록"
```

- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.voice.KoreanPhrasesTest' :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ride.*'` → FAIL(미정의)
- [ ] **Step 3: 구현** — 위대로. `endForAnotherTrip`·`stopRecording`·`closeInterrupted`의 동작은 바꾸지 않는다.
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :app:testDebugUnitTest` → PASS(기존 `RideControllerTest`·`RideNavigationTest`·`NavigateViewModelTest` 그대로)
- [ ] **Step 5: Commit** — `git commit -m "ride: on-screen notices and splitting a recording into a new segment"`

---

### Task 7: 기록 중 새 안내 — 같은 여행에 붙이기와 설정 (스펙 §4.2, §4.3)

**Files:**
- Modify: `app/src/main/java/com/cowork/bikerecoder/data/SettingsRepository.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/trip/TripStartViewModel.kt`, `ui/trip/TripChoices.kt`, `ui/trip/TripDialogs.kt`, `ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/trip/TripStartViewModelTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/settings/SettingsViewModelTest.kt`, `app/src/androidTest/java/com/cowork/bikerecoder/ui/settings/SettingsScreenTest.kt`(추가), `app/src/androidTest/java/com/cowork/bikerecoder/ui/trip/TripStartDialogsTest.kt`(신규)

**Interfaces:**
- Consumes: Task 6 `RideController.splitSegment(): String?`
- Produces:
```kotlin
// data/SettingsRepository.kt
/** 기록 중에 안내를 새로 시작할 때 기록을 어떻게 할지(스펙 §4.3). */
enum class GuidanceRecording(val label: String) { CONTINUE("자동으로 이어서 기록"), SPLIT("자동으로 새 구간 나누기"), ASK("매번 물어보기") }
// AppSettings 끝에
val recordingOnNewGuidance: GuidanceRecording = GuidanceRecording.ASK
// 키 stringPreferencesKey("recording_on_new_guidance"), 모르는 값 → ASK
// SettingsViewModel
fun setRecordingOnNewGuidance(mode: GuidanceRecording)
// SettingsContent 끝에: onSetRecordingOnNewGuidance: (GuidanceRecording) -> Unit = {}  ("기록" 절, 라디오 3개, 제목 "안내를 새로 시작할 때 기록")
// SettingsTags
fun guidanceRecording(mode: GuidanceRecording) = "settings_guidance_recording_${mode.name}"
// TripChoices.kt
const val RECORDING_QUESTION = "기록을 어떻게 할까요?"
// TripStartState
/** "기록을 어떻게 할까요?" [이어서 기록] [새 구간으로 나누기] [취소] */
data object AskRecording : TripStartState
// TripStartViewModel 생성자 끝에
private val recordingMode: suspend () -> GuidanceRecording = { GuidanceRecording.ASK },
private val splitSegment: suspend () -> String? = { null },
/** 그 여행을 안내하는 중이면 닫는다(begin이 같은 여행이면 아무것도 하지 않으므로 새 경유지가 반영되도록). */
private val closeGuidanceOf: (tripId: Long) -> Unit = {},
fun keepRecording()    // AskRecording에서만
fun splitRecording()   // AskRecording에서만
// TripStartDialogs 끝에: onKeepRecording: () -> Unit = {}, onSplitRecording: () -> Unit = {}
```
- 흐름(스펙 §4.2):
  - `onStartPressed(plan)`: 요약 대기 검사 뒤 `recordingTrip()`이 있으면 붙이기 경로 — 일정 유형·이어서 진행을 묻지 않는다. `work { when (recordingMode()) { ASK -> AskRecording; CONTINUE -> attach(id, split = false); SPLIT -> attach(id, split = true) } }`. 없으면 기존 흐름 그대로.
  - `attach(tripId, split)`: `closeGuidanceOf(tripId)` → `changeProfile(tripId, plan.profile)`(결정: `continueTrip`처럼 계획 화면의 경로 성향을 쓴다) → 남은 경유지가 계획과 다르면 `changeStops(tripId, plan.stops.toTripStops())` → `notice = if (split) splitSegment() else null` → `Ready(tripId, notice)`.
  - `keepRecording`/`splitRecording`: 답할 때 `recordingTrip()`이 물을 때의 여행이 아니면(그사이 기록이 끝남) 기존 흐름(`prompt()`)으로 간다(결정). `dismiss`는 `AskRecording`도 닫는다 — 아무것도 바꾸지 않는다.
  - `chooseType`/`continueTrip`의 `endRecording` 분기는 그대로 둔다: 이제는 질문 중에 시작된 기록에만 쓰이는 안전망이다(스펙 "endForAnotherTrip 경로는 기록 중이 아닐 때만 쓰인다"의 해석).
  - factory: `recordingMode = { container.settings.settings.first().recordingOnNewGuidance }`, `splitSegment = { container.ride.splitSegment() }`, `closeGuidanceOf = { id -> if (container.navigationState.let { (it as? NavUiState.Active)?.tripId == id || (it as? NavUiState.Starting)?.tripId == id }) container.navigation.close() }`.
  - BikeNavHost PLAN: `TripStartDialogs(…, onKeepRecording = tripStart::keepRecording, onSplitRecording = tripStart::splitRecording)`. Ready 처리는 그대로(`Routes.navigate(tripId, fromPlan = true, recordNotice = …)`).
- **의도적으로 바뀌는 기존 테스트**(`TripStartViewModelTest`): 기록이 [안내 시작] 때 이미 있으면 이제 붙이기 경로로 가므로, 아래 6개는 `r.record(old)`를 `vm.onStartPressed(plan); runCurrent()` **뒤**(질문 중)로 옮겨 안전망 경로를 계속 확인한다. 단언은 그대로: `guidingANewTripFirstEndsTheRunningRecordingAsASegmentWithoutSummary`, `startNewOnARecordedMultiDayTripEndsItsRecordingFirst`, `continuingTheRecordedTripKeepsItsRecording`, `aRecordingThatCouldNotBeSavedIsNoticedWithTheStart`, `aCleanStopHasNoNotice`, `withoutAutoStartTheEndedRecordingsSummaryStays`. `Recording` 도우미에 `voice`(필드로 꺼냄)와 `viewModel(recordedTrip, mode = ASK, closed = mutableListOf())`를 더해 `recordingMode`·`splitSegment = { controller.splitSegment() }`·`closeGuidanceOf = { closed += it }`를 넘긴다.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// TripStartViewModelTest (기록 중인 여행 old = tripManager.startTrip(SINGLE_DAY, CYCLEWAY_FIRST, emptyList()) — [● 기록 시작]으로 만든 경유지 없는 여행; r.record(old) 뒤 onStartPressed(plan))
@Test fun planStartWhileRecordingAsksHowToRecord()      // ASK: onStartPressed → AskRecording; store.trips 1개, stops(old) 그대로, 구간 1개 열림
@Test fun askCancelDoesNothing()                        // AskRecording에서 dismiss → Idle; stops·구간·trips 그대로, closed 빈 목록
@Test fun keepRecordingAttachesThePlanToTheRecordedTrip() // keepRecording → Ready(old, null); stops(old) 이름 [남산, 여의도]; profile BALANCED; trips 1개; 구간 1개 같은 id 열림; voice에 "새 구간으로 기록을 시작합니다" 없음
@Test fun splitRecordingOpensANewSegmentOfTheSameTrip() // splitRecording → Ready(old, null); segments(old) 2개(첫째 닫힘), ui Active.segmentId == 둘째; trips 1개; voice "새 구간으로 기록을 시작합니다" 1회
@Test fun continueSettingAttachesWithoutAsking()        // CONTINUE: onStartPressed → 바로 Ready(old), 구간 1개
@Test fun splitSettingSplitsWithoutAsking()             // SPLIT: 바로 Ready(old), 구간 2개
@Test fun splitSaveProblemComesWithTheStart()           // SPLIT + rideStore.failAppend → Ready.recordNotice == "기록 저장에 문제가 있습니다"
@Test fun attachingClosesAGuidanceOfThatTripFirst()     // CONTINUE → closed == [old]
@Test fun answerAfterTheRecordingEndedFallsBackToTheUsualQuestions() // AskRecording 중 controller.stopRecording(SEGMENT_ONLY)+dismissSummary → keepRecording → AskType(old가 당일)
// SettingsViewModelTest
@Test fun recordingOnNewGuidanceDefaultsToAskAndIsSaved() // 처음 ASK; setRecordingOnNewGuidance(SPLIT) → settings.value.recordingOnNewGuidance == SPLIT
// SettingsScreenTest (기기)
@Test fun guidanceRecordingChoices()     // performScrollTo 후 "안내를 새로 시작할 때 기록", "자동으로 이어서 기록", "자동으로 새 구간 나누기", "매번 물어보기" 표시, ASK 선택됨; "자동으로 새 구간 나누기" 클릭 → SPLIT
// TripStartDialogsTest (기기)
@Test fun recordingQuestionOffersKeepSplitCancel() // AskRecording → "기록을 어떻게 할까요?", "이어서 기록"/"새 구간으로 나누기"/"취소" 클릭이 각 콜백
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.trip.TripStartViewModelTest' --tests 'com.cowork.bikerecoder.ui.settings.SettingsViewModelTest'` → FAIL
- [ ] **Step 3: 구현** — 위대로. `TripStartDialogs`의 `AskRecording`은 `EndNavigationDialog`처럼 세로 버튼 열.
- [ ] **Step 4: 통과 확인** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 `…class=com.cowork.bikerecoder.ui.settings.SettingsScreenTest`, `…class=com.cowork.bikerecoder.ui.trip.TripStartDialogsTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: new guidance while recording joins the recorded trip (continue/split/ask)"`

---

### Task 8: 안내 화면 버튼, 기록 화면 [목적지 정하기], 화면 문구 (스펙 §3, §4.1)

**Files:**
- Create: `app/src/main/java/com/cowork/bikerecoder/ui/navigate/GuidanceButtons.kt`
- Modify: `ui/navigate/NavigateViewModel.kt`, `ui/navigate/NavigateScreen.kt`, `ui/record/RecordViewModel.kt`, `ui/record/RecordScreen.kt`, `ui/BikeNavHost.kt`
- Test: `app/src/test/java/com/cowork/bikerecoder/ui/navigate/GuidanceButtonsTest.kt`, `app/src/test/java/com/cowork/bikerecoder/ui/navigate/NavigateViewModelTest.kt`(추가), `app/src/test/java/com/cowork/bikerecoder/ui/record/RecordViewModelTest.kt`(추가), `app/src/androidTest/java/com/cowork/bikerecoder/ui/navigate/GuidanceButtonRowTest.kt`(신규), `app/src/androidTest/java/com/cowork/bikerecoder/ui/record/RecordScreenTest.kt`(추가)

**Interfaces:**
- Consumes: Task 6 `RideNotice`, `RideControls.announce`/`noticeShown`, `RideController.notice`, `KoreanPhrases.guidanceEndedRecordingGoesOn`
- Produces:
```kotlin
// ui/navigate/GuidanceButtons.kt
enum class GuidanceButton(val label: String) {
    MUTE("음소거"), OVERVIEW("전체 경로"), END("종료"), CANCEL_GUIDANCE("안내 취소 · 기록 계속"), END_RIDE("주행 종료"),
}
/** 기록 중이 아니면 V1.0.0 그대로 [MUTE, OVERVIEW, END]; 기록 중이면 [MUTE, CANCEL_GUIDANCE, END_RIDE]. */
fun guidanceButtons(recording: Boolean): List<GuidanceButton>
object GuidanceTags { const val OVERVIEW_ON_MAP = "navigate-overview-map"; fun button(b: GuidanceButton) = "navigate-button-${b.name}" }
/** MUTE는 muted면 "음소거 해제". END와 END_RIDE는 onEnd(종료 대화상자), CANCEL_GUIDANCE는 onCancelGuidance(확인 없음). */
@Composable internal fun GuidanceButtonRow(
    recording: Boolean, muted: Boolean, onMute: (Boolean) -> Unit, onOverview: () -> Unit,
    onEnd: () -> Unit, onCancelGuidance: () -> Unit, modifier: Modifier = Modifier,
)
// NavigateViewModel 생성자 끝에
val rideNotice: StateFlow<RideNotice?> = MutableStateFlow(null),
/** 안내 화면이 보일 알림: 안내가 Starting/Active일 때만(그 밖에는 기록 화면 몫). */
val noticeForScreen: StateFlow<RideNotice?>
fun noticeShown(id: Long)         // → rideControls.noticeShown(id)
fun cancelGuidance()              // = onEndChoice(EndAction.END_GUIDANCE_ONLY)
// onEndChoice(END_GUIDANCE_ONLY): controller.close() 뒤 rideControls.announce("안내를 마칩니다. 기록은 계속됩니다")
//   (결정: 대화상자의 "안내만 끝내기 (기록 계속)"도 같은 동작이므로 같은 음성·문구)
// RecordViewModel
/** 기록 화면이 보일 알림: 기록이 Active일 때만. */
val noticeForScreen: StateFlow<RideNotice?>
fun noticeShown(id: Long)         // → ride.noticeShown(id)
// RecordScreen 끝에: onSetDestination: () -> Unit = {}
// RecordContent 끝에: onSetDestination: (() -> Unit)? = null  → null이 아니고 !navigating이면 지도 쪽 요약 줄 바로 위에 OutlinedButton "목적지 정하기"(전체 폭)
// RecordTags.SET_DESTINATION = "record-set-destination"
```
- NavigateScreen: `ActiveContent`에 `recording = viewModel.recording`(Starting 포함)과 `onCancelGuidance = viewModel::cancelGuidance`를 넘기고, `BottomPanel`의 버튼 줄을 `GuidanceButtonRow`로 바꾼다. 기록 중이면 [전체 경로]는 지도 쪽 오른쪽 아래의 작은 `FilledTonalButton("전체 경로")`(`GuidanceTags.OVERVIEW_ON_MAP`). [주행 종료]는 기존 `EndNavigationDialog`(recording = true) 그대로. 알림: `noticeForScreen`이 오면 먼저 `noticeShown(id)` 후 `snackbar.showSnackbar(text, duration = SnackbarDuration.Short)`.
- RecordScreen: 알림 전용 `SnackbarHost`를 `Alignment.BottomCenter`에 `navigationBarsPadding().padding(bottom = 120.dp)`(버튼 위)로 두고 `noticeForScreen`을 같은 방식으로 보인다(Short ≈ 4초). 기존 가운데 저장 경고 스낵바는 그대로. BikeNavHost RECORD: `onSetDestination = { if (isCurrent()) navController.navigate(Routes.SEARCH) }`. 결정: 안내 중에 보이는 기록 화면에는 [목적지 정하기]가 없다(그 여행은 이미 안내 중).
- factory: NavigateViewModel `rideNotice = container.ride.notice`.

- [ ] **Step 1: 실패하는 테스트**

```kotlin
// GuidanceButtonsTest
@Test fun withoutRecordingTheV1Buttons()      // guidanceButtons(false).map { it.label } == ["음소거", "전체 경로", "종료"]
@Test fun whileRecordingCancelAndEndRide()    // guidanceButtons(true).map { it.label } == ["음소거", "안내 취소 · 기록 계속", "주행 종료"]
// NavigateViewModelTest (rideControls 가짜에 announce 기록 추가, rideNotice는 MutableStateFlow)
@Test fun cancelGuidanceEndsGuidanceOnlyAndAnnouncesOnce() // 기록 중 안내 Active → cancelGuidance() → ui Idle, trip ACTIVE, announce 기록 == ["안내를 마칩니다. 기록은 계속됩니다"], summaryDismissals 0
@Test fun endGuidanceOnlyFromTheDialogAnnouncesToo()       // onEndChoice(END_GUIDANCE_ONLY) → 같은 announce 1회
@Test fun guidanceScreenTakesNoticesOnlyWhileGuiding()     // rideNotice = RideNotice(1, …): 안내 Active → noticeForScreen == 그것; close() 뒤(Idle) → null
// RecordViewModelTest
@Test fun recordScreenTakesNoticesOnlyWhileRecording()     // 기록 Active에서 ride.announce(…) → noticeForScreen.text 같음; noticeShown(id) → null; Idle에서 announce → noticeForScreen null
// GuidanceButtonRowTest (기기)
@Test fun recordingShowsCancelAndEndRide()    // recording=true → "안내 취소 · 기록 계속", "주행 종료" 표시, "전체 경로" 없음; 클릭 → onCancelGuidance 1회, onEnd 1회
@Test fun notRecordingShowsTheV1Buttons()     // "음소거", "전체 경로", "종료"; muted=true → "음소거 해제"
// RecordScreenTest (기기)
@Test fun setDestinationShowsWhenNotGuiding() // onSetDestination 줌, navigating=false → RecordTags.SET_DESTINATION "목적지 정하기" 표시, 클릭 → 1회
@Test fun setDestinationHiddenWhileGuiding()  // navigating=true → assertDoesNotExist
```

- [ ] **Step 2: 실패 확인** — `./gradlew :app:testDebugUnitTest --tests 'com.cowork.bikerecoder.ui.navigate.*' --tests 'com.cowork.bikerecoder.ui.record.RecordViewModelTest'` → FAIL
- [ ] **Step 3: 구현** — 위대로.
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL(기존 `endGuidanceOnlyKeepsTheTripAndGoesIdle`·`EndNavigationDialogTest` 그대로); 기기 `…class=com.cowork.bikerecoder.ui.navigate.GuidanceButtonRowTest`, `…class=com.cowork.bikerecoder.ui.record.RecordScreenTest` → PASS(또는 기기 검증 대기)
- [ ] **Step 5: Commit** — `git commit -m "app: guidance buttons while recording, set a destination from the record screen, notice snackbars"`

---

### Task 9: 검증 문서와 변경 이력

**Files:**
- Modify: `docs/verification/recording-field-test.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 1–8의 테스트 클래스 이름과 결과

- [ ] **Step 1: 전체 JVM 검증** — `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → BUILD SUCCESSFUL; 모음별 클래스·테스트 수를 적는다.
- [ ] **Step 2: 기기 실행(가능하면)** — `adb devices`에 `R5CT43TFS9A`가 있으면 Task 2·3·4·5·7·8의 기기 클래스를 하나씩(`ANDROID_SERIAL=R5CT43TFS9A … -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`), 잠금 해제 상태로 실행해 결과를 적는다. 없으면 "기기 검증 대기"로 적는다. `R3CY903ZYQV`에는 connected 테스트를 돌리지 않는다.
- [ ] **Step 3: `recording-field-test.md`에 「주행 사용성 개선」 절 추가** — (a) 실행일·PC 테스트 표(새 클래스: `FavoriteRepositoryTest`, `FavoriteMappingsTest`, `PlaceLookupTest`, `FavoritesViewModelTest`, `GuidanceButtonsTest` + 추가된 `RideControllerTest`·`RideNavigationTest`·`TripStartViewModelTest`·`PlanViewModelTest`·`NavigateViewModelTest`·`RecordViewModelTest`·`SettingsViewModelTest`·`KoreanPhrasesTest`); (b) 기기 클래스 표(`RideDatabaseMigrationTest`(2→3 포함), `RoomFavoriteStoreTest`, `PlanScreenTest`, `FavoriteChipsTest`, `FavoriteDialogsTest`, `SearchScreenTest`, `SettingsScreenTest`, `TripStartDialogsTest`, `GuidanceButtonRowTest`, `RecordScreenTest`)와 명령; (c) 경고 한 줄 — "`connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다. connected 테스트는 `R5CT43TFS9A`에서만, 실제 기록이 있는 `R3CY903ZYQV`에는 `installDebug`만 씁니다" — 그리고 이 문서의 기존 connected 명령 예시의 시리얼을 `R5CT43TFS9A`로 바꾼다; (d) 설치 확인: `ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug` 뒤 기존 기록 목록이 그대로인지(실제 v2 → v3 마이그레이션); (e) 실주행 체크리스트 4항목(스펙 §6.3 그대로) — 1. 집 즐겨찾기로 안내 시작이 바로 되는가 / 2. 안내 중 [안내 취소 · 기록 계속] 후 기록이 끊기지 않는가(음성과 화면 문구) / 3. 기록 중 새 목적지로 안내 시작 시 설정대로(이어서/나누기/묻기) 동작하고, 기록 목록에 같은 여행으로 보이는가 / 4. 경로 계획 화면에서 길게 눌러 경유지 추가 — 결과 칸 `[ ] 미실시` 형식은 기존 표와 같게.
- [ ] **Step 4: `CHANGELOG.md` 1.1.0 「추가 (예정)」에 항목 추가** — 즐겨찾기(집·회사·직접 입력 칩, 길게 눌러 이름 바꾸기·삭제, 최대 20개) / 검색 결과가 키보드에 가리지 않음, 목록을 끌면 키보드 내림 / 기록 중 안내 화면 [안내 취소 · 기록 계속]·[주행 종료](음성과 화면 문구) / 기록 화면 [목적지 정하기]: 기록 중인 여행에 안내를 붙이고 설정 "안내를 새로 시작할 때 기록"(이어서/새 구간/매번 물어보기) / 경로 계획 화면 길게 눌러 경유지·목적지·즐겨찾기 / 저장소 DB v3(즐겨찾기 테이블 추가, 기존 기록 유지). 「문서」에 스펙·계획 경로 추가.
- [ ] **Step 5: Commit** — `git commit -m "docs: ride UX improvements verification and changelog"`
