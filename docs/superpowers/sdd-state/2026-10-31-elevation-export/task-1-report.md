# Task 1 보고 — core 경로 고도와 고도 단면

상태: DONE

## 만든 것
- `Route.elevationsM: List<Double?> = emptyList()` (마지막 매개변수, 기존 호출은 그대로 컴파일).
- `BRouterGeoJsonParser`: 좌표 세 번째 값을 읽어 `elevationsM` 채움. 값 없음/null/숫자 아님 = null, 전부 null이면 빈 목록.
- `GradeBand`(7단계, argb/legend, `of`), `BandRun` — `core/elevation/GradeBand.kt`.
- `ElevationProfile` — `core/elevation/ElevationProfile.kt`. 생성자는 private, 공개는 브리프의 속성·함수·companion(`fromSegments`, `fromRoute`, 상수).

## 파일
- 수정: `core/.../model/Route.kt`, `routing-brouter/.../BRouterGeoJsonParser.kt`, `routing-brouter/.../BRouterGeoJsonParserTest.kt`
- 신규: `core/.../elevation/GradeBand.kt`, `ElevationProfile.kt`, 시험 `GradeBandTest.kt`, `ElevationProfileTest.kt`

## 시험
- 새 시험: 파서 3, GradeBand 2, ElevationProfile 14 (브리프 목록 전부). 실패 확인(컴파일 실패) 뒤 구현.
- `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest` 전부 통과 (전체 1605건, 실패 0).

## 결정
- 거리 축: `fromSegments`는 모든 점(고도 null 포함)의 직선거리를 누적, 구간 이음매 거리는 더하지 않음. 이음매에서 두 점이 같은 거리를 가지며 `pointAt`은 원래 경로 점 사이만 보간하므로 이음매에서 좌표가 건너뜀.
- `boundariesM`(segments): 비어 있지 않은 둘째 구간부터 시작 거리. `fromRoute`: `stopPointIndices` 마지막 제외, 범위 밖 인덱스는 버림.
- 격자: 0, step, ..., 마지막은 totalM (끝 간격은 step보다 짧을 수 있음). 50,000 m 이하 10 m, 초과 20 m.
- 다듬기: 거리 기준 ±25 m 안 표본 평균(경계 포함). 경사: 중심 ±50 m를 양 끝에서 잘라 실제 폭으로 나눔, 다듬은 고도를 보간해 사용.
- `bands`: 표본마다 이웃과의 중간점까지를 칸으로 보고 같은 단계는 병합 -> 0~totalM 빈틈 없음. `bandAt`은 이 `bands`를 찾아 그림과 항상 일치(경계 근처에서 `GradeBand.of(gradeAt(d))`와 반 칸 어긋날 수 있음).
- `slice`: 전체 단면의 다듬은 고도·경사를 그대로 잘라 새 격자(0부터 stepM)로 보간(끝에서 다시 다듬지 않음), stepM 유지, 경계·경로선도 잘라 0부터 다시. 범위는 [0,totalM]에 맞추고 50 m 미만이면 null.
- `fromRoute`: `elevationsM` 크기가 `points`와 다르면 null.

## 우려
- 없음 큰 것 없음. 이음매 `pointAt`은 거리 동률(두 점이 같은 거리)이 있어 정확히 이음매 거리의 값은 앞 구간 끝 점을 반환.
- 파서 시험은 기존 fixture를 건드리지 않고 인라인 JSON 사용.
