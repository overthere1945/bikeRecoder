# 여행과 후처리 V1.2.0 검증 기록

설계 스펙(`docs/superpowers/specs/2026-10-18-trips-postprocess-design.md`) §5.3(검증)에 따른 기록 문서입니다.

> **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다.
> connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 `installDebug`만 씁니다.

## 자동 검증

### PC 단위 테스트

실행일: 2026-10-18 (브랜치 `feature/trips-v1.2.0`, 최종 검토 뒤 마지막 수정 묶음(R13–R15) 뒤)

명령:

```bash
./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
```

결과: BUILD SUCCESSFUL in 2m 11s (`--rerun-tasks`로 전부 다시 실행)

| 모음 | 클래스 수 | 테스트 수 | 실패 | 건너뜀 |
|---|---|---|---|---|
| `:core:test` | 27 | 244 | 0 | 0 |
| `:routing-brouter:test` | 4 | 16 | 0 | 0 |
| `:app:testDebugUnitTest` | 61 | 618 | 0 | 0 |
| 합계 | 92 | 878 | 0 | 0 |

수는 이 실행이 만든 JUnit XML(`*/build/test-results/*/TEST-*.xml`)에서 셌습니다. 빌드 확인: `:app:assembleDebug`, `:app:assembleDebugAndroidTest` 성공(기기 테스트 코드 컴파일 포함).

스펙 §5.3의 PC 확인 범위: 사진 위치(EXIF, 시각 보간, 30초 넘는 간격, 구간 앞뒤, 지점 없음), 사진 한 장은 한 구간에만(R13), "선택한 사진만"이면 사진 찾기를 끝내지 않음(R15), 심박 요약(평균·최고·자세함 판정), 색 구간·30초 연결, 심박 상태 전이(PENDING → READY/NONE/FAILED, 24시간, 처음 읽은 때부터 하루 다시 읽기(R14), 다시 가져오기), 권한 없을 때 한 바퀴에 한 번만 확인하고 쓰지 않음, 요약 화면은 자세한 심박이 들어올 때까지 다시 읽음, 단순화(10 m), 누적 요약·기간 거르기, 후처리 단일 실행·기록 중 구간 건너뜀, 사진 다시 찾기 시 숨김 유지, 지운 사진 정리, DB 3→4.

### 기기 테스트 (Galaxy S22 Ultra `R5CT43TFS9A`, SM-S908N, Android 16 / API 36)

connected 테스트는 S22 `R5CT43TFS9A`에서만 실행합니다(위 경고). 시리얼 없이 실행하면 연결된 모든 휴대폰에서 돌고 앱을 지우므로 항상 `ANDROID_SERIAL=R5CT43TFS9A`를 붙입니다. 실행 전 `adb devices`로 확인하고 화면을 깨웁니다(잠겨 있으면 화면 테스트가 "No compose hierarchies found"로 실패). 한 번에 클래스 하나씩 실행합니다. 휴대폰 설정은 바꾸지 않습니다. 갤러리와 Health Connect는 테스트용 가짜(`FakePhotoSource`, `FakeHeartRateSource`)를 씁니다. 테스트는 시스템 권한 창을 열지 않습니다.

```bash
adb devices
adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP
ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스> </dev/null
```

결과는 2026-10-18 V1.2.0 개발 중 그 클래스를 마지막으로 실행한 결과입니다. 「마지막 실행」 칸은 그때 만들던 기능입니다. 그 뒤에 다른 기능이 들어왔으므로 출시 전에 한 번 더 돌려 보는 것이 좋습니다. 클래스 이름은 `com.cowork.bikerecoder.` 뒤부터 적었습니다.

| 클래스 | 테스트 수 | 확인하는 것 | 마지막 실행 | 결과 |
|---|---|---|---|---|
| `data.RideDatabaseMigrationTest` | 3 | DB v1→v2, v2→v3, v3→v4(표 추가만) 뒤 기존 기록 유지 | 최종 수정 묶음(R13–R15) | 3/3 통과 |
| `data.RoomPostStoreTest` | 11 | 후처리 저장소: 사진 저장(숨김 유지·중복 건너뜀)·관찰·지운 사진 삭제, 한 구간의 사진만 지우기(R13), 처리할 구간 목록(기록 중·끝난 구간 건너뜀, 최신순, FAILED는 처음 읽은 때부터 하루(R14)), 부가 정보 저장·관찰, 심박 샘플 시간순, 구간 삭제 CASCADE, 누적 경로 읽기(읽을 수 없는 경로는 그 줄만 뺌), 없는 구간에 쓰기 거절 | 최종 수정 묶음(R13–R15) | 11/11 통과 |
| `data.RoomRideStoreTest` | 7 | 구간·위치 저장, 삭제가 새 표까지 CASCADE | DB v4 저장소 | 7/7 통과 |
| `data.RoomTripStoreTest` | 6 | 여행 저장소(DB v4에서 재확인) | DB v4 저장소 | 6/6 통과 |
| `data.RoomFavoriteStoreTest` | 1 | 즐겨찾기 저장소(DB v4에서 재확인) | DB v4 저장소 | 1/1 통과 |
| `post.MediaStorePhotoSourceTest` | 5 | 카메라 폴더 사진만 창 안에서(양끝 포함, 찍은 시각 순), 사진 위치 권한으로 EXIF 위치, 갤러리에서 지운 사진, 커서 null은 빈 갤러리가 아니라 실패 | 사진·심박 출처 | 5/5 통과 |
| `ui.photo.PhotoStripTest` | 4 | "사진 N장" 줄, "숨긴 사진 N장", 숨긴 사진만 있을 때 | 사진 화면 | 4/4 통과 |
| `ui.photo.PhotoViewerTest` | 6 | 전체 화면 보기: 찍은 시각 "10월 18일 오후 2:31", [숨기기]/[다시 보이기], 좌우 넘기기, [뒤로] | 사진 화면 | 6/6 통과 |
| `ui.post.PermissionCardsTest` | 4 | 사진 카드(허용 안 함 / 선택한 사진만 / 모든 사진), 심박 카드 | 설정 「사진·심박 연결」 | 4/4 통과 |
| `ui.history.DetailMapPhotosTest` | 2 | 상세 지도에는 보이는 사진만 | 최종 수정 묶음(R13–R15) | 2/2 통과 |
| `ui.history.HistoryScreenTest` | 4 | 목록 → 여행 → 구간, 목록 위 누적 지도 버튼, 길게 눌러 삭제 | 누적 지도 | 4/4 통과 |
| `ui.health.HeartRateRowTest` | 4 | 심박 줄 상태 문구, "– kcal", 자세하지 않을 때 안내, 권한 없음·Health Connect 없음(R8) | 설정 「사진·심박 연결」 | 4/4 통과 |
| `ui.health.HeartRateLegendTest` | 2 | 색 설명 여섯 줄, [기본]/[심박] 선택 | 심박 화면 | 2/2 통과 |
| `ui.history.SegmentDetailScreenTest` | 7 | 구간 상세: [심박] 색·색 설명·심박 그래프, 권한 카드와 [나중에], Health Connect 없음, [다시 가져오기] | 최종 수정 묶음(R13–R15) | 7/7 통과 |
| `ui.record.RecordScreenTest` | 14 | 기록 화면과 구간 요약(사진 수, 심박 줄) | 최종 수정 묶음(R13–R15) | 14/14 통과 |
| `ui.cumulative.CumulativeMapScreenTest` | 5 | 누적 지도 요약, 기간 거르기, 빈 화면 문구, 후처리가 경로를 더할 수 있는 동안·읽는 동안에는 빈 문구 없음 | 최종 수정 묶음(R13–R15) | 5/5 통과 |
| `ui.settings.SettingsScreenTest` | 11 | 설정 화면, 「사진·심박 연결」(허용 안 함 + [허용] / 모든 사진 허용·허용됨 / 선택한 사진만 + [설정 열기]·Health Connect를 사용할 수 없습니다, 스크롤 뒤 확인), 버튼마다 맞는 동작(가짜 동작으로, 실제 시스템 화면은 열지 않음) | 설정 「사진·심박 연결」 | 11/11 통과 |
| `ui.settings.SettingsSegmentActionsTest` | 4 | 경로 데이터 버튼(설정 화면 구조가 바뀌어 다시 확인) | 설정 「사진·심박 연결」 | 4/4 통과 |

위 18개 클래스 100개가 통과했습니다. 기기 전체 `@Test`는 186개(33개 클래스)이고, 나머지 15개 클래스 86개는 V1.2.0 개발 중 휴대폰에서 실행하지 않아 **기기 검증 대기**입니다. 이 가운데 `ride.RideScenarioTest`와 `nav.NavigationScenarioTest`는 Room 저장소를 쓰므로 DB v4(표 추가)의 영향을 받을 수 있어 먼저 확인합니다:
`location.GpxLocationSourceTest` 1, `nav.NavigationScenarioTest` 6, `offline.MapLibreOfflineControllerTest` 7, `ride.RideScenarioTest` 5, `ui.common.NoticeSnackbarHostTest` 4, `ui.favorite.FavoriteChipsTest` 7, `ui.favorite.FavoriteDialogsTest` 10, `ui.favorite.FavoritesScreenTest` 6, `ui.navigate.GuidanceButtonRowTest` 3, `ui.pick.MapPickScreenTest` 6, `ui.plan.PlanScreenTest` 4, `ui.search.SearchRegisterModeTest` 10, `ui.search.SearchScreenTest` 13, `ui.trip.EndNavigationDialogTest` 3, `ui.trip.TripStartDialogsTest` 1.

### 수동 확인 (기기 검증 대기)

| 확인 | 방법 | 결과 |
|---|---|---|
| 권한 카드의 실제 시스템 창 | 사진 카드 [허용] → 사진 권한 창 → (허용하면) 사진 위치 권한 창. 심박 카드 [허용] → Health Connect 권한 창(심박·운동·총 칼로리 세 가지). 거절하면 카드가 다시 뜨지 않는지 | [ ] 미실시 |
| 설정 「사진·심박 연결」 | 상태 문구가 실제 권한과 맞는지("모든 사진 허용"/"선택한 사진만"/"허용 안 함", "허용됨"/"허용 안 함"/"Health Connect를 사용할 수 없습니다"). [허용]은 시스템 창을 띄우고, 허용하면 상태가 바뀌고 카드도 다시 판단됨. "선택한 사진만"의 [설정 열기]는 앱 정보 화면으로 감. 두 번 거절해 Android가 사진 창을 더 띄우지 않으면 [허용]이 앱 정보 화면을 엶. 심박 [허용]에서 허용하지 않고 돌아오면 Health Connect 설정 화면이 열림(앱 권한에서 허용) | [ ] 미실시 |
| Health Connect 권한 이유 화면 | Health Connect 앱의 이 앱 권한 화면에서 이유 보기 → "심박은 주행 기록에만 쓰고 휴대폰 밖으로 보내지 않습니다" | [ ] 미실시 |

## 사용자에게 보이는 동작을 바꾼 판정

스펙과 계획 위에서 컨트롤러가 정한 판정 가운데 화면이나 동작이 달라지는 것입니다. 실주행 때 이 기준으로 봅니다.

- **R6 심박 권한은 심박 하나면 됨**: 심박은 `READ_HEART_RATE`만 있으면 읽습니다. 운동(`READ_EXERCISE`)·총 칼로리(`READ_TOTAL_CALORIES_BURNED`)가 없으면 칼로리만 비고("· – kcal") 심박은 보입니다. 요청할 때는 세 가지를 모두 묻습니다.
- **R8 권한 없음은 실패가 아니라 대기**: Health Connect가 없거나 심박 권한이 없으면 심박은 "가져오는 중"(PENDING)으로 남고, 권한을 주면 지난 기록까지 다시 읽습니다. "심박을 가져오지 못했습니다"는 권한이 있는데 읽기가 실패했을 때만 나옵니다. 그동안 심박 줄 자리에는 "심박 가져오는 중" 대신 권한 카드나 "Health Connect를 사용할 수 없습니다"가 보입니다.
- **R9 구간 요약 두 곳 모두**: 기록 화면의 구간 요약과 안내 주행 뒤 안내 화면의 구간 요약 둘 다 사진 수·사진 카드와 심박 줄·심박 카드를 보이고, 떠 있는 동안 1분마다(최대 15분) 심박을 다시 읽습니다.
- **R10 여행 지도는 구간마다 따로 그린 선**: 여행 상세 지도에서 구간(날) 사이를 직선으로 잇지 않습니다. [심박]이면 구간마다 그 구간의 심박으로 칠합니다.
- **R11 누적 요약은 값을 채운 한 줄**: "총 거리 · 주행 n회 · 총 이동 시간"은 예를 들어 "35.00km · 주행 3회 · 1시간 45분"처럼 값으로 채워 보입니다.
- **R12 주행 횟수는 거리가 있는 구간 수**: "주행 n회"는 거리가 0보다 큰 구간 수입니다(기록 목록과 같음).
- **R13 사진 한 장은 한 구간에만**: 한 여행 안에서 사진은 찍은 시각이 들어 있는 구간에 붙습니다. 어느 구간에도 들지 않으면(구간 사이 쉬는 동안) 시작이나 끝이 더 가까운 구간에, 똑같이 가까우면 앞 구간에 붙습니다. 그래서 「새 구간으로 나누기」 앞뒤에 찍은 사진이 두 구간에 겹쳐 두 번 보이지 않습니다. 이전 개발판이 두 구간에 함께 넣어 둔 사진은 아직 사진을 다시 찾는 구간(끝난 지 24시간 안이거나 사진 찾기가 끝나지 않은 구간)에서 다시 찾을 때 한쪽만 남깁니다.
- **R14 심박 읽기 실패는 하루 동안 다시 시도**: "심박을 가져오지 못했습니다"(FAILED)는 구간이 끝난 때와 처음 읽은 때 가운데 늦은 쪽부터 24시간 동안 자동으로 다시 읽습니다. 업데이트 뒤 처음 읽다 실패한 지난 기록도 그때부터 하루 동안 다시 읽습니다. 그 뒤에는 [다시 가져오기]로만 읽습니다.
- **R15 "선택한 사진만"이면 사진 찾기를 끝내지 않음**: 모든 사진을 허용한 때만 지난 구간의 사진 찾기를 끝냅니다. "선택한 사진만"인 동안에는 계속 다시 찾으므로, 나중에 모든 사진을 허용하면 오래된 기록의 나머지 사진도 자동으로 붙습니다(카드 문구 "모든 사진을 허용하면 자동으로 찾습니다" 그대로).

## 실주행 체크리스트

### 기기 정보

| 항목 | 내용 |
|---|---|
| 기기 | Galaxy S25 Ultra `R3CY903ZYQV` (모델명: ______ ) |
| 워치 | Galaxy Watch7 + 삼성 헬스 |
| Android 버전 | ______ |
| 앱 버전 | V1.2.0 (설정 화면 맨 아래 표기와 같은지 확인: ______ ) |
| 실주행 날짜 | ______ |

### 준비

1. S25에는 connected 테스트를 돌리지 않습니다(기록이 지워짐). 설치만 합니다.

   ```bash
   adb devices
   ANDROID_SERIAL=R3CY903ZYQV ./gradlew :app:installDebug
   ```

2. 앱을 열어 V1.1.0 기록이 그대로 있는지 봅니다(DB 3 → 4는 표 추가만).
3. 구간 요약이나 상세에 뜨는 카드로, 또는 설정 「사진·심박 연결」의 [허용]으로 사진·Health Connect 권한을 줍니다. 항목 1의 위치 태그 끔 확인은 카메라 앱 설정에서 위치 태그를 끄고 찍습니다(휴대폰 쪽 설정이며 앱 설정이 아님).
4. 항목 2·3은 워치에서 **자전거 운동**을 시작하고 주행합니다.

### 항목

결과 칸 표기: `[ ]` 미실시(아직 하지 않음), `[x]` 합격, `[!]` 불합격. 불합격이면 메모에 원인을 적습니다.

| # | 항목 | 합격 기준 | 결과 (`[ ]` 미실시 / `[x]` 합격 / `[!]` 불합격) | 메모 |
|---|---|---|---|---|
| 1 | 주행 중 사진 몇 장 | 경로 위 위치가 찍은 곳과 맞음(위치 태그 켬/끔 각각) | [ ] 미실시 | 켬 ___장 / 끔 ___장 / 어긋난 사진 ___ |
| 2 | 워치 자전거 운동 | 몇 분 안에 심박이 들어오고, 구간 요약 화면에 바로 보임 | [ ] 미실시 | 들어온 시간 ___분 뒤 |
| 3 | 오르막 심박 색 | [심박]에서 오르막 구간 색이 붉어짐 | [ ] 미실시 | |
| 4 | 숨긴 사진 | 숨긴 사진이 지도·사진 줄에서 빠지고 "숨긴 사진 N장"에서 [다시 보이기]로 돌아옴 | [ ] 미실시 | |
| 5 | 누적 지도 | 지난 기록이 다 보이고 [전체]/연도/월 거르기와 요약이 맞음 | [ ] 미실시 | 주행 ___회 / 총 거리 ___km |
| 6 | 지난(V1.1.0) 기록 소급 | 업데이트 전 기록에 사진과 심박이 붙음 | [ ] 미실시 | |
| 7 | 주행 중 홈으로 나간 뒤 심박 | 주행을 끝내고 바로 홈으로 나갔다가 돌아와도 심박이 "심박을 가져오지 못했습니다"로 남지 않음(R14: 실패해도 하루 동안 다시 읽음) | [ ] 미실시 | |
| 8 | 희박한 심박 뒤 운동 심박 | 요약 화면에 평소 심박(몇 개)만 먼저 들어와도, 워치 운동 심박이 들어오면 요약에 자세한 심박으로 바뀌고 [심박]이 보임 | [ ] 미실시 | 바뀐 시간 ___분 뒤 |

### 판정

- PC 테스트와 기기 테스트가 통과하고 위 8개 항목이 모두 합격이면 `CHANGELOG.md`의 V1.2.0을 확정하고(「개발 중」 표기 제거) 태그 `v1.2.0`을 붙입니다.
- 불합격 항목은 원인 수정과 회귀 테스트 추가 뒤 그 항목만 다시 확인하고, 여기에 재검증 결과를 덧붙입니다.
