# Global Constraints (verbatim from plan)

- 패키지 루트: `com.cowork.bikerecoder` (core: `com.cowork.bikerecoder.core`, routing: `com.cowork.bikerecoder.routing`)
- minSdk 30, targetSdk/compileSdk 37, Java/JVM 타깃 17 (BRouter 모듈만 `options.release = 11`)
- 버전: `versionName = "1.0.0"`, `versionCode = 10000` (= MAJOR×10000 + MINOR×100 + PATCH), debug 빌드는 `versionNameSuffix = "-dev"`, 화면 표기 `V1.0.0`
- MapLibre `org.maplibre.gl:android-sdk:13.6.1` 고정. 공식 Compose 래퍼는 사용하지 않고 `AndroidView`로 감싼다
- 지도 스타일 `https://tiles.openfreemap.org/styles/liberty`, 표기 문구 `OpenFreeMap © OpenMapTiles Data from OpenStreetMap`
- BRouter 데이터: `https://brouter.de/brouter/segments4/`, 필수 `E125_N35`, `E125_N30`, 선택 `E130_N35`, `E120_N35`
- 카카오: `https://dapi.kakao.com`, 헤더 `Authorization: KakaoAK {키}`, 키는 루트 `secrets.json`의 `kakaoRestApiKey` → `BuildConfig.KAKAO_REST_API_KEY`
- 수치(스펙 6장): 이탈 40m·8초·정확도 30m·재탐색 간격 15초·실패 재시도 30초 / 도착 30m·지나침 50m / ETA 창 15분·최소 5분·기본 15km/h·정지 2km/h 미만 / 회전 안내 200m·30m·25km/h 초과 시 300m·합치기 50m·1km 안내 연기 15초 / 지도 폭 2km·z10–14 회랑·z5–9 개요·15,000타일·150km·일반 캐시 100MB / 경로 계산 제한 60초 / GPS 없음 10초 / 방치 3일
- 의존성 주입 라이브러리 금지. `AppContainer` 수동 구성
- 모든 사용자 노출 문구는 한국어

# Controller rulings in force (from ledger)
- R2: T17 adds `implementation(project(":routing-brouter"))` to app.
- R3: AVD/device setup happens in T12 before first instrumented test.
- R4: T1 key test asserts BuildConfig.KAKAO_REST_API_KEY equals secrets.json's kakaoRestApiKey if present, else "".
- R5: JUnit5 modules add `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`.
- R6: T2 distance expectation = 8_778 ± 20 m.
- R7: T3 distanceAlongM is monotonic non-decreasing.
- R8: T6 test uses along 800 → far prompt, 805 → [], 975 → near, 980 → [].
- R9: TestRoutes.pointAt (111_320 m/deg) vs GeoMath (R=6_371_008.8): derive expected distances via GeoMath; do not rewrite pointAt.
- R11: NavigationSession GPS watchdog runs on tick time only (onTick clock), independent of fix.timeMillis.
- R12: session.updates is MutableSharedFlow(extraBufferCapacity=64) with tryEmit — the Android side (T19) must collect it inside the foreground service, never a UI-lifecycle collector.
