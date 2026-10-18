# 검색·즐겨찾기 보완 (V1.1.0 포함) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S25 실기기 피드백 4건: 안내 화면 [안내 취소] 문구, 카카오 검색 정확도 순, 최근 검색 장소, 집·회사 등록 칩.

**Architecture:** 문구·정렬은 기존 파일만 고친다. 최근 검색은 `search` 패키지에 순수 규칙(`RecentPlaceRules`, JVM 테스트)과 저장소 인터페이스(`RecentPlaceStore`) + DataStore 구현을 두고 `SearchViewModel`이 쓴다. 등록 칩은 `FavoriteChips`에 등록 칩을 더하고, 검색 화면에 등록 모드(`Routes.SEARCH` 인자 `register=HOME|WORK`)를 둔다. 저장은 기존 `FavoriteRepository.save`/`FavoritesViewModel` 흐름(교체 확인 포함)을 그대로 쓴다.

**Tech Stack:** 기존과 동일. 새 외부 라이브러리 없음. DB 버전은 3 그대로(최근 검색은 DataStore).

**Spec:** `docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md` §7 (§1·§2·§3 위에 더함)

## Global Constraints

- `docs/superpowers/plans/2026-10-18-ride-ux-improvements.md`의 Global Constraints가 모두 그대로 적용된다(버전 1.1.0, 스레드 규칙, 판정 U1~U4, 기기 규칙 U3, 테스트 프레임워크, 커밋 트레일러). 특히 **connected 테스트는 `ANDROID_SERIAL=R5CT43TFS9A`로만, `R3CY903ZYQV`에는 절대 돌리지 않는다**. 기기가 없으면 `:app:assembleDebugAndroidTest`로 컴파일만 하고 "기기 검증 대기".
- 문구(스펙 §7 그대로): "안내 취소" / "최근 검색" / "전체 삭제" / "🏠 집 등록" / "🏢 회사 등록" / "집 검색" / "회사 검색" / "집으로 저장했습니다" / "회사로 저장했습니다". 기존 "안내를 마칩니다. 기록은 계속됩니다"·"기존 집을 이 장소로 바꿀까요?"는 그대로.
- 수치: 최근 검색 최대 10개, 같은 장소 = 이름이 같고 좌표 거리 ≤ 10 m.
- 동작이 바뀌어 고치는 기존 테스트는 Task가 이름을 밝힌다: Task 1의 `GuidanceButtonsTest`·`GuidanceButtonRowTest`(문구), `KakaoLocalClientTest`(정렬 인자). Task 3의 `FavoriteChipsTest`의 "없으면 줄 없음" 단언(→ 등록 칩 단언).

## Review Focus
- 등록 모드에서 저장이 기존 규칙(집·회사 하나, 교체 확인, 20개 제한)을 우회하지 않는가. 저장 실패 시 화면에 머무르고 메시지를 보이는가.
- 최근 검색이 결과를 고를 때만 쌓이고(입력 글자 아님), 중복·최대 10개·최신 순이 지켜지는가. DataStore 읽기 실패가 검색을 막지 않는가.
- 등록 칩이 지도 메인에서 지도 끌기·길게 누르기를 막지 않는가(칩 줄은 칩 너비만).

## 파일 구조
- 수정 `app/.../ui/navigate/GuidanceButtons.kt`(문구), 주석의 옛 문구(`NavigateScreen.kt`, `NavigateViewModel.kt`, `RecordScreen.kt`)
- 수정 `app/.../search/KakaoLocalClient.kt`(`sort=accuracy`)
- 생성 `app/.../search/RecentPlaces.kt`(`RecentPlaceRules`, `RecentPlaceStore`), `app/.../data/DataStoreRecentPlaces.kt`; 수정 `AppContainer.kt`, `ui/search/SearchViewModel.kt`, `ui/search/SearchScreen.kt`
- 수정 `ui/favorite/FavoriteChips.kt`, `ui/favorite/FavoritesViewModel.kt`, `ui/BikeNavHost.kt`(검색 경로 인자), `ui/search/*`(등록 모드)

---

### Task 1: [안내 취소] 문구와 검색 정확도 순 (스펙 §7.1, §7.2)

**Files:** Modify `ui/navigate/GuidanceButtons.kt`, `search/KakaoLocalClient.kt`, tests `GuidanceButtonsTest`, `KakaoLocalClientTest`, androidTest `GuidanceButtonRowTest`; 주석 속 "안내 취소 · 기록 계속"을 "안내 취소"로(`NavigateScreen.kt`, `NavigateViewModel.kt`, `RecordScreen.kt`).

- [ ] **Step 1:** 테스트 먼저 바꾼다: `GuidanceButtonsTest`/`GuidanceButtonRowTest`의 기대 문구를 "안내 취소"로, `KakaoLocalClientTest`의 위치 있는 요청이 `sort=accuracy`(그리고 x, y 유지)를 보내는지, 위치 없는 요청은 `sort`를 보내지 않는지(기존 동작) 단언. 실패 확인.
- [ ] **Step 2:** `GuidanceButton.CANCEL_GUIDANCE` 문구 "안내 취소". 버튼 한 줄(`maxLines = 1`)인지 확인 — 세 버튼 모양이 같아야 한다(다른 두 버튼과 같은 Button 종류·높이; 가운데만 다른 모양이면 맞춘다: 음소거와 같은 OutlinedButton). 접근성 설명(contentDescription/onClickLabel)이 있으면 "안내 취소, 기록은 계속"처럼 뜻을 남겨도 된다.
- [ ] **Step 3:** `KakaoLocalClient.keyword`: 위치가 있으면 `x`, `y`, `sort=accuracy`. 주석에 이유 한 줄(거리 순은 가까운 가게가 원하는 장소를 묻음).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest` 통과. 기기 `GuidanceButtonRowTest`는 R5CT43TFS9A가 있으면 실행, 없으면 대기.
- [ ] **Step 5:** Commit `app: [안내 취소] label; Kakao keyword search by accuracy`

### Task 2: 최근 검색 장소 (스펙 §7.3)

**Files:** Create `search/RecentPlaces.kt`, `data/DataStoreRecentPlaces.kt`, tests `search/RecentPlaceRulesTest.kt`; Modify `AppContainer.kt`, `ui/search/SearchViewModel.kt`(+ `SearchViewModelTest`), `ui/search/SearchScreen.kt`(+ androidTest `SearchScreenTest` 새 테스트).

**Interfaces:**
```kotlin
// search/RecentPlaces.kt
object RecentPlaceRules {
    const val MAX = 10
    const val SAME_PLACE_M = 10.0
    /** place를 맨 앞에, 같은 장소(이름 같고 ≤ 10 m)는 빼고, 최대 10개. distanceM은 null로 저장. */
    fun add(list: List<Place>, place: Place): List<Place>
    fun remove(list: List<Place>, place: Place): List<Place>
}
interface RecentPlaceStore {
    val places: Flow<List<Place>>      // 최신 순
    suspend fun add(place: Place)
    suspend fun remove(place: Place)
    suspend fun clear()
}
```
- `DataStoreRecentPlaces(context)`: 별도 preferences 파일 `recent_places`, 문자열 키 하나에 kotlinx.serialization JSON 목록(이름, 주소, lat, lon). 읽기 실패·깨진 JSON은 빈 목록(검색을 막지 않음). 쓰기는 `edit` 안에서 `RecentPlaceRules`로.
- `SearchViewModel`: 생성자에 `recent: RecentPlaceStore`. `state.recent: List<Place>`(검색창이 비었을 때 화면이 씀). `select(place)`가 `recent.add(place)`도 한다(실패는 무시). `removeRecent(place)`, `clearRecent()`.
- `SearchScreen`: 검색어가 비었으면 머리 "최근 검색" + 오른쪽 [전체 삭제](TextButton), 아래 행(이름·주소, 거리 없음, 오른쪽 [X] 아이콘 버튼 contentDescription "지우기"). 행 누르기 = 결과 누르기와 같음. 목록이 비면 머리도 없음. 기존 키보드 여백·끌면 키보드 내림(§2)이 최근 목록에도 적용. 태그: `SearchTags.RECENT_HEADER`, `SearchTags.recent(index)`, `SearchTags.recentRemove(index)`, `SearchTags.RECENT_CLEAR`.

- [ ] **Step 1:** `RecentPlaceRulesTest`(JVM): 맨 앞 추가, 같은 장소 다시 → 맨 위로 하나만, 이름 같아도 10 m 넘으면 다른 장소, 11번째 → 가장 오래된 것 빠짐, distanceM null, remove. 실패 확인 → 구현 → 통과.
- [ ] **Step 2:** `SearchViewModelTest`: 가짜 `RecentPlaceStore`로 select가 add를 부르고 state.recent가 store를 따라감, removeRecent/clearRecent, store 실패해도 select는 sheet를 연다. 구현 → 통과.
- [ ] **Step 3:** DataStore 구현 + `AppContainer.recentPlaces` + 팩토리 연결. 화면. androidTest `SearchScreenTest`에 최근 목록 표시·[X]·[전체 삭제]·누르면 시트 테스트(가짜 store).
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 통과. 기기 실행은 규칙대로.
- [ ] **Step 5:** Commit `app: recent places on the empty search screen`

### Task 3: 집·회사 등록 칩과 검색 등록 모드 (스펙 §7.4)

**Files:** Modify `ui/favorite/FavoriteChips.kt`(+ androidTest `FavoriteChipsTest`), `ui/favorite/FavoritesViewModel.kt`(+ JVM test), `ui/BikeNavHost.kt`, `ui/search/SearchScreen.kt`, `ui/search/SearchViewModel.kt`(등록 모드 상태), 필요 시 `ui/main/MainScreen.kt`.

- `FavoriteChips(…, onRegister: ((FavoriteKind) -> Unit)? = null)`: `onRegister`가 있으면 집이 없을 때 [🏠 집 등록], 회사가 없을 때 [🏢 회사 등록] 칩을 그 자리(집 → 회사 → 직접 입력 순)에 그린다. 등록 칩은 길게 누르기 없음, `Role.Button`, 48 dp, 태그 `FavoriteTags.register(kind)`. 모양은 등록된 칩과 구분되게(예: 테두리만, 글자 색 `onSurfaceVariant`). `onRegister`가 없거나(검색 화면) 그릴 칩이 하나도 없으면 지금처럼 아무것도 그리지 않는다. 칩 줄 너비는 칩만큼(지도 끌기 유지).
- 지도 메인만 `onRegister`를 넘긴다 → `navController.navigate(Routes.searchRegister(kind))`. 경로: `search?register={register}`(nullable 문자열 인자, 기존 `Routes.SEARCH` 호출은 인자 없이 그대로 동작).
- 등록 모드 검색 화면: 검색창 안내 문구 "집 검색"/"회사 검색", 칩 줄 없음, 결과·최근 행을 누르면 하단 시트 대신 `FavoritesViewModel.saveAs(place, kind)`. 최근 검색에도 추가된다(select와 같은 기록).
- `FavoritesViewModel.saveAs(place: FavoritePlace, kind: FavoriteKind)`: `repository.save(kind, place)`와 같은 길. 결과 Saved → 이벤트 `Registered(kind)`(한 번 소비) ; ConfirmReplace → 기존 `ConfirmReplace` 대화상자(확인하면 저장 후 같은 이벤트) ; Rejected → 기존처럼 메시지 대화상자, 화면 유지. 저장 화면에서 이벤트를 받으면 Toast "집으로 저장했습니다"/"회사로 저장했습니다"를 띄우고 `popBackStack()`으로 메인.
- [ ] **Step 1:** JVM `FavoritesViewModelTest`: saveAs 집 없음 → 저장·Registered(HOME) 한 번; 집 있음 → ConfirmReplace → confirm → 교체·Registered; 20개 꽉 참(직접 입력 19 + 회사) → 집 saveAs는 기존 규칙대로 Rejected("즐겨찾기는 20개까지 저장할 수 있습니다"), 이벤트 없음, 화면 유지. 실패 확인 → 구현 → 통과.
- [ ] **Step 2:** 칩·경로·등록 모드 화면. androidTest `FavoriteChipsTest`: 즐겨찾기 없음 + onRegister → 등록 칩 둘, 누르면 kind 전달; 집만 있음 → [🏠 집] + [🏢 회사 등록]; onRegister 없음 + 빈 목록 → 줄 없음(기존 단언 대체). `SearchScreenTest`(또는 새 클래스): 등록 모드 안내 문구, 칩 줄 없음, 결과 누르면 시트 없이 저장 콜백.
- [ ] **Step 3:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 통과. 기기는 규칙대로.
- [ ] **Step 4:** Commit `app: home/work register chips and a register-mode search`

### Task 4: 문서

**Files:** Modify `CHANGELOG.md`, `docs/verification/recording-field-test.md`, 옛 문구가 남은 계획/스펙 외 문서(`docs/verification/*`의 "안내 취소 · 기록 계속" → "안내 취소").

- [ ] **Step 1:** `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → 모음별 테스트 수(build/test-results XML에서).
- [ ] **Step 2:** `recording-field-test.md` 「주행 사용성 개선」 절: PC 표에 새/바뀐 클래스(`RecentPlaceRulesTest`, `SearchViewModelTest`, `FavoritesViewModelTest`, `KakaoLocalClientTest`, `GuidanceButtonsTest`)와 수, 기기 표에 바뀐 클래스(`SearchScreenTest`, `FavoriteChipsTest`, `GuidanceButtonRowTest`) "기기 검증 대기", 실주행 체크리스트에 추가: 6. "발산역"처럼 역·장소 이름 검색 시 그 장소가 맨 위에 나오는가 / 7. 검색창을 비우면 최근 고른 장소가 보이고 [X]·[전체 삭제]가 되는가 / 8. 첫 화면 [🏠 집 등록]으로 집을 등록하면 Toast 후 [🏠 집] 칩이 되는가. 1번 항목 등 옛 문구 "안내 취소 · 기록 계속" → "안내 취소".
- [ ] **Step 3:** `CHANGELOG.md` 1.1.0 「추가 (예정)」: 안내 화면 항목 문구를 "[안내 취소]"로 고치고, 추가: "장소 검색 정확도 순(현재 위치 근처 우선 유지)", "검색창이 비었을 때 최근 고른 장소 10개(지우기·전체 삭제)", "첫 화면 [🏠 집 등록]·[🏢 회사 등록] 칩". 「문서」에 이 계획 경로.
- [ ] **Step 4:** Commit `docs: search and favorites polish verification and changelog`
