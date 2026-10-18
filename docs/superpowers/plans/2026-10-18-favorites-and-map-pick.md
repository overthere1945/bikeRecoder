# 즐겨찾기 화면·현재 위치·지도에서 선택 (V1.1.0 포함) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스펙 §8 — 칩 줄을 [집][회사][★ 즐겨찾기]로, 즐겨찾기 화면, 등록 방법(검색·현재 위치·지도에서 선택), 즐겨찾기 이름 정하기(추천 이름), 지도에서 선택 화면(등록과 목적지·경유지 공통).

**Architecture:** `FavoriteChips`는 집·회사 칩 + [★ 즐겨찾기] 하나. 새 화면 `ui.favorite.FavoritesScreen`(경로 `favorites`). 새 화면 `ui.pick.MapPickScreen`(경로 `map-pick`)은 `BikeMap`의 새 콜백 `onCameraIdle`로 중심점을 받고 주소를 조회해 결과를 이전 화면의 `SavedStateHandle`(키 `MapPick.RESULT`)로 돌려준다. 근처 장소 이름은 `PlaceSearch.nearby(point)`(카카오 카테고리 검색)로. 저장 규칙은 기존 `FavoriteRepository` 그대로, 흐름은 `FavoritesViewModel`에 더한다.

**Tech Stack:** 기존과 동일. 새 외부 라이브러리 없음. DB 버전 3 그대로.

**Spec:** `docs/superpowers/specs/2026-10-18-ride-ux-improvements-design.md` §8 (§1, §7 위에 더함)

## Global Constraints

- `docs/superpowers/plans/2026-10-18-ride-ux-improvements.md`의 Global Constraints와 `2026-10-18-search-favorites-polish.md`의 판정(V1 [안내 취소], V2 [확인] 메시지)이 그대로 적용된다(버전 1.1.0, 스레드 규칙, U1~U4, 테스트 프레임워크, 커밋 트레일러).
- **기기 규칙(이번 작업, 사용자 지시 2026-10-18):** 기기 검증과 설치는 **S22 `R5CT43TFS9A`에서만**. connected 테스트는 `ANDROID_SERIAL=R5CT43TFS9A`로 한 번에 클래스 하나, `</dev/null`을 붙여 실행. **S25 `R3CY903ZYQV`에는 이번 작업에서 아무것도 하지 않는다**(테스트·설치 모두). 실행 전 `adb devices`에 `R5CT43TFS9A`가 있는지 확인하고 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`. 휴대폰 설정은 바꾸지 않는다. 없거나 잠겨 있으면 컴파일만 하고 "기기 검증 대기".
- 문구(스펙 §8 그대로): "★ 즐겨찾기" / "즐겨찾기" / "＋ 즐겨찾기 추가" / "등록한 즐겨찾기가 없습니다" / "즐겨찾기 검색" / "즐겨찾기에 저장했습니다" / "📍 현재 위치" / "🗺 지도에서 선택" / "현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요" / "위치 오차가 큽니다(약 {n}m)" / "현재 위치를 집으로 저장할까요?" / "현재 위치를 회사로 저장할까요?" / "저장" / "취소" / "즐겨찾기 이름" / "추천 이름" / "지도에서 선택" / "이 위치로 설정" / "주소를 확인하는 중…" / "지도에서 선택한 위치". 기존 문구("이름 바꾸기", "삭제", "즐겨찾기는 20개까지 저장할 수 있습니다", "같은 이름의 즐겨찾기가 있습니다", "이름은 1~20자로 입력하세요", "집으로 저장했습니다", "회사로 저장했습니다", "집 검색", "회사 검색")는 그대로.
- 수치: 위치 오차 경고 > 50 m, 근처 장소 반경 50 m, 추천 장소 이름 최대 5개(가까운 순, 같은 이름 하나), 지도에서 선택 주소 조회는 카메라가 멈춘 뒤(`onCameraIdle`) 300 ms 디바운스, 이전 조회는 취소.
- 카카오 카테고리 코드(스펙 §8.4): `SW8, CE7, FD6, CS2, MT1, BK9, PO3, AT4, CT1, HP8` — 코드마다 한 요청(`v2/local/search/category.json`, `x`, `y`, `radius=50`, `sort=distance`, `size=5`), 동시에 보내고 합친다. 하나가 실패해도 나머지로. 모두 실패하면 빈 목록(이름 입력은 계속 가능).
- 동작이 바뀌어 고치는 기존 테스트는 Task가 이름을 밝힌다: Task 1의 `FavoriteChipsTest`(직접 입력 칩 → [★ 즐겨찾기]), 관련 `SearchScreenTest`/`SearchRegisterModeTest` 칩 단언.

## Review Focus
- 등록 경로(검색·현재 위치·지도에서 선택 × 집·회사·즐겨찾기)가 모두 `FavoriteRepository` 규칙을 지나는가. 실패·거부 시 화면 유지와 메시지.
- 지도에서 선택: 결과 전달이 정확히 한 번(회전·뒤로가기·두 번 누르기), 지도 움직이는 중 버튼 비활성, 오래된 주소 응답이 새 위치를 덮지 않음, MapLibre는 메인 스레드·`AndroidView`만.
- 근처 장소 요청 수와 실패 처리, 키 없음/잘못된 키에서 크래시 없음.
- 기존 흐름 회귀 없음: 일반 검색 결과 시트, 경로 계획 [+ 경유지 추가], 기록 화면 [목적지 정하기], 지도 메인 길게 누르기.

---

### Task 1: 칩 줄 [★ 즐겨찾기]와 즐겨찾기 화면 (스펙 §8.1, §8.2)

**Files:** Modify `ui/favorite/FavoriteChips.kt`, `ui/BikeNavHost.kt`(경로 `favorites`), `ui/favorite/FavoritesViewModel.kt`; Create `ui/favorite/FavoritesScreen.kt`; tests: JVM `FavoritesViewModelTest`, androidTest `FavoriteChipsTest`(바뀜), new `FavoritesScreenTest`.

- `FavoriteChips`: 집·회사(등록 칩 규칙 §7.4·`favoritesLoaded` 그대로) 다음에 **[★ 즐겨찾기]** 하나(`FavoriteTags.ALL`, `onOpenFavorites`). 직접 입력 칩은 더 이상 그리지 않는다. 검색 화면(일반)도 [★ 즐겨찾기]를 보인다(`onOpenFavorites` 전달). 등록 모드 검색은 칩 줄 없음 그대로.
- `FavoritesScreen(favorites: List<Favorite>(CUSTOM만, 생성 순), onBack, onAdd, onSelect, onRename, onDelete)`: 상단 바 "즐겨찾기"·뒤로; 첫 줄 "＋ 즐겨찾기 추가"(`FavoritesTags.ADD`); 항목 행(이름, 주소 없으면 생략, 오른쪽 [⋮] `FavoritesTags.menu(id)` → [이름 바꾸기] [삭제]; 길게 누르기도 같은 메뉴); 비면 "등록한 즐겨찾기가 없습니다". 이름 바꾸기·삭제는 기존 `FavoritesViewModel.startRename`/`askDelete` 대화상자.
- 항목 누르기 = 칩 누르기와 같은 `setDestinationAndOpenPlan`. [＋ 즐겨찾기 추가]: 20개 찼으면 `FavoriteDialog.Message(MSG_LIMIT)`(화면 유지), 아니면 `Routes.searchRegister(FavoriteKind.CUSTOM)` — 이 Task에서는 등록 모드 CUSTOM의 검색 결과 누르기 → 이름 창(Task 3 전까지는 기존 Rename 스타일의 이름 입력 대화상자를 장소 이름을 미리 넣어 쓰고, Task 3이 §8.4 창으로 바꾼다) → 저장 → Toast "즐겨찾기에 저장했습니다" → `popBackStack()`으로 즐겨찾기 화면. 등록 모드 CUSTOM 검색창 안내 "즐겨찾기 검색". `Registered(kind)` 이벤트와 pop 규칙은 §7.4 구현 그대로 확장(CUSTOM은 즐겨찾기 화면으로 돌아감).
- `searchRegister`의 `registerKind`가 CUSTOM도 받도록(기존에 CUSTOM 거부 — 이제 허용).
- 두 번 누르기 방지: 새 navigate 호출은 기존 `isCurrent()` 패턴.
- [ ] Step 1: JVM 테스트 먼저(VM: CUSTOM 등록 저장·이름 중복/길이 거부·20개 거부·Registered(CUSTOM)), 실패 확인 → 구현 → 통과.
- [ ] Step 2: 화면·경로. androidTest: `FavoriteChipsTest` 갱신(직접 입력 칩 없음, [★ 즐겨찾기] 항상, 순서 집→회사→★), `FavoritesScreenTest`(추가 줄 맨 위, 목록 순서, 빈 문구, 누르기/메뉴 콜백).
- [ ] Step 3: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 규칙대로 바뀐/새 기기 클래스 실행(S22만).
- [ ] Step 4: Commit `app: one favorites chip and a favorites screen`

### Task 2: 지도에서 선택 화면과 일반 검색 진입 (스펙 §8.5)

**Files:** Modify `map/BikeMap.kt`(`onCameraIdle: ((GeoPoint) -> Unit)? = null`, `onCameraMoveStarted`), `ui/BikeNavHost.kt`(경로 `map-pick`), `ui/search/SearchScreen.kt`·`SearchViewModel.kt`; Create `ui/pick/MapPickScreen.kt`, `ui/pick/MapPickViewModel.kt`; tests JVM `MapPickViewModelTest`, androidTest `MapPickScreenTest`(지도 없이 상태 렌더링 부분), `SearchScreenTest` 추가.

- `MapPickViewModel(placeSearch, initialCenter)`: `state = MapPickState(center: GeoPoint?, moving: Boolean, address: String?, loading: Boolean)`; `onMoveStarted()` → moving=true(버튼 비활성); `onCameraIdle(p)` → moving=false, 300 ms 뒤 `addressOrNull(p)`(collectLatest로 이전 조회 취소, 늦은 응답이 새 중심을 덮지 않음). `result(): PickedPoint(point, address)`.
- `MapPickScreen`: `BikeMap`(FREE 카메라, `initialCenter` = 현재 위치 → 없으면 마지막 지도 위치(기존 MainViewModel이 기억하는 값이 있으면) → 서울시청) 위 한가운데 고정 📍(핀 끝이 정확히 중심: 아이콘 하단을 중심에 맞춤, `MapPickTags.PIN`), 상단 바 "지도에서 선택"·뒤로, 하단 카드(주소/"주소를 확인하는 중…"/좌표 `%.5f, %.5f`, [이 위치로 설정] `MapPickTags.CONFIRM`, moving·center 없음이면 비활성).
- 결과 전달: [이 위치로 설정] → `navController.previousBackStackEntry?.savedStateHandle?.set(MapPick.RESULT, PickedPoint...)`(Parcelable 또는 lat/lon/address 세 키) 후 `popBackStack()`. 받는 쪽은 `currentBackStackEntry.savedStateHandle`에서 한 번 읽고 지운다(회전에도 한 번).
- 일반 검색 화면: 검색창 아래(칩 줄 아래, 최근 검색 위) **[🗺 지도에서 선택]** 한 줄(`SearchTags.MAP_PICK`). 결과를 받으면 `Place(name = address ?: "지도에서 선택한 위치", address = address.orEmpty(), point, distanceM = null)`로 기존 `select()`와 같은 장소 시트(최근 검색에도 추가). 검색어가 있을 때도 보인다.
- [ ] Step 1: `MapPickViewModelTest`(디바운스·취소·늦은 응답 무시·moving 동안 비활성·주소 실패 → null) 먼저, 실패 → 구현 → 통과.
- [ ] Step 2: BikeMap 콜백(`addOnCameraIdleListener`/`addOnCameraMoveStartedListener`, 메인 스레드), 화면, 경로, 검색 진입. androidTest: 하단 카드 상태별 렌더링(지도 컴포저블은 주입 가능한 슬롯으로 두어 테스트에서 빈 Box), 검색 화면 [🗺 지도에서 선택] 표시·결과 주입 시 시트.
- [ ] Step 3: 빌드·테스트, 기기(S22만): 새/바뀐 기기 클래스. 그리고 S22에서 수동 확인용으로 `installDebug` 후 adb `screencap`으로 지도에서 선택 화면 한 장(핀이 중심, 카드 주소) — 화면 캡처 경로를 보고서에 적는다.
- [ ] Step 4: Commit `app: pick a point on the map for destinations and stops`

### Task 3: 등록 방법(현재 위치·지도에서 선택)과 즐겨찾기 이름 정하기 (스펙 §8.3, §8.4)

**Files:** Modify `search/PlaceSearch.kt`·`search/KakaoLocalClient.kt`(`nearby`), `ui/favorite/FavoritesViewModel.kt`·`FavoriteDialogs.kt`, `ui/search/SearchScreen.kt`(등록 모드 두 줄), `ui/BikeNavHost.kt`; tests JVM `KakaoLocalClientTest`(nearby), `FavoritesViewModelTest`, androidTest `FavoriteDialogsTest`·`SearchRegisterModeTest` 추가.

- `PlaceSearch.nearby(point: GeoPoint): SearchResult<List<Place>>` — Global Constraints의 10개 카테고리를 동시에, 반경 50 m, 합쳐 거리 순·같은 이름 하나·최대 5. 가짜 PlaceSearch들(테스트 fakes)도 구현(기본 빈 목록).
- 등록 모드 검색 화면 맨 위 두 줄 **[📍 현재 위치]**(`SearchTags.CURRENT_LOCATION`) **[🗺 지도에서 선택]**(`SearchTags.MAP_PICK`, Task 2 화면 재사용).
- `FavoritesViewModel`:
  - `registerCurrent(kind, fix: LocationFix?)`: fix 없음 → `Message("현재 위치를 찾는 중입니다. 잠시 후 다시 눌러 주세요")`. 있으면 주소 조회 후 HOME/WORK → `ConfirmCurrent(kind, place, question, accuracyWarning: String?)` 대화상자([저장] → `saveAs`, [취소]); CUSTOM → `NameFavorite`(§8.4).
  - 지도에서 선택 결과(`registerPicked(kind, PickedPoint)`): HOME/WORK → 바로 `saveAs`; CUSTOM → `NameFavorite`.
  - 검색 결과(CUSTOM) → `NameFavorite`(장소 이름 미리 넣음).
  - `NameFavorite(place, initialName, suggestions: List<String>, loadingSuggestions, error)`: 열 때 `nearby(point)`를 불러 추천 이름(장소 이름들 + 주소) 채움; 현재 위치/지도에서 선택이면 initialName = 가장 가까운 장소 이름 ?: 주소 ?: "". [저장] → `repository.save(CUSTOM, name, place)`; 거부(중복·길이·20개)는 창 안의 error로 보이고 창 유지.
  - 위치 오차 경고: `fix.accuracyM > 50` → "위치 오차가 큽니다(약 {n}m)"(정수 반올림).
  - 장소 하단 시트 [즐겨찾기에 저장] → [직접 입력]도 `NameFavorite`(장소 이름 미리 넣음, 추천 이름 포함).
- `FavoriteDialogs`: `ConfirmCurrent`(제목 질문, 본문 주소 또는 좌표, 경고 줄, [저장] [취소]), `NameFavorite`(제목 "즐겨찾기 이름", 입력란, "추천 이름" 칩 줄(가로 스크롤, 누르면 입력란 교체), error, [저장] [취소]).
- [ ] Step 1: JVM 먼저 — `KakaoLocalClientTest.nearby`(MockWebServer: 10개 요청 경로·인자, 일부 실패해도 합침, 정렬·중복·5개, 키 없음 KEY_MISSING), `FavoritesViewModelTest`(fix 없음 메시지, HOME 현재 위치 → 확인 → 저장·Registered, 오차 경고 문구, CUSTOM 현재 위치 → 이름 창 initialName = 가까운 장소 이름/주소, 추천 이름, 지도 선택 HOME 바로 저장, 이름 중복 거부 시 창 유지). 실패 → 구현 → 통과.
- [ ] Step 2: 화면 연결. androidTest: `FavoriteDialogsTest`(두 새 창 렌더링·추천 칩 누르면 입력란 바뀜·[취소]), `SearchRegisterModeTest`(두 줄 표시·현재 위치 누르면 콜백).
- [ ] Step 3: 빌드·테스트, 기기(S22만).
- [ ] Step 4: Commit `app: register by current location or map pick; favorite names with nearby suggestions`

### Task 4: 문서

**Files:** `CHANGELOG.md`, `docs/verification/recording-field-test.md`.

- [ ] Step 1: `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → 모음별 수(XML).
- [ ] Step 2: field-test 「주행 사용성 개선」 절: PC 표·기기 표(새/바뀐 클래스, 이번 작업 기기 = S22 `R5CT43TFS9A`, 결과 칸), 체크리스트 추가: 9. [★ 즐겨찾기] → 즐겨찾기 화면에서 [＋ 즐겨찾기 추가]·목록·[⋮] 메뉴 / 10. 집·회사·즐겨찾기를 [📍 현재 위치]로 등록 / 11. [🗺 지도에서 선택]으로 등록, 목적지·경유지 설정 / 12. 즐겨찾기 이름 창의 추천 이름(상호) 선택과 직접 입력.
- [ ] Step 3: CHANGELOG 1.1.0 「추가 (예정)」: 즐겨찾기 항목을 "[🏠 집] [🏢 회사] [★ 즐겨찾기] 칩, 즐겨찾기 화면(추가·목록·이름 바꾸기·삭제)"로 고치고, 추가: "등록 방법: 검색·현재 위치·지도에서 선택", "즐겨찾기 이름: 장소 이름(상호) 추천 또는 직접 입력", "목적지·경유지를 지도에서 선택". 「문서」에 이 계획 경로.
- [ ] Step 4: Commit `docs: favorites screen and map pick verification and changelog`
