# 장소 데이터 만들기 (`tools/places/`)

공유 카드의 "지나간 길·산" 해시태그(설계 `docs/superpowers/specs/2026-11-01-share-cards-design.md` §4)에 쓰는
`app/src/main/assets/places/places.bin`과 `places-summary.tsv`를 만든다. Python 3 표준 라이브러리만 쓴다.

## 파일

| 파일 | 내용 | 저장소 |
|---|---|---|
| `build_places.py` | 받기(`--download`)와 만들기 | 넣음 |
| `places.tsv` | 길 목록: `id, kind, tag, group, name_regex[, bbox]` | 넣음 |
| `sources/100-mountains.csv` | 산림청 100대 명산: `name, sido, height_m, tag` | 넣음 |
| `sources/peak-overrides.tsv` | 이름 규칙으로 못 찾는 산의 정상 좌표(OSM 점 번호와 까닭) | 넣음 |
| `sources/overpass/*.ql` | Overpass 질의(길 relation, 인증센터 점, 정상 점) | 넣음 |
| `sources/osm/*.json` | Overpass 응답 원본(약 12 MB) | 안 넣음(`.gitignore`) |

## 명령

```sh
# 저장소 맨 위에서. OSM 원본을 새로 받고 만든다(Overpass에 질의 3번, 몇 분 걸린다).
python3 -I tools/places/build_places.py --download

# 받아 둔 원본으로 다시 만든다. 같은 원본이면 같은 바이트가 나온다.
python3 -I tools/places/build_places.py

# 확인
./gradlew :app:testDebugUnitTest --tests '*PlaceAssetTest*'
```

- 받기는 `https://overpass-api.de/api/interpreter`(안 되면 `overpass.kumi.systems`)에 `User-Agent: bikeRecoder-dev/1.0 (personal app)`을 붙여 보낸다. 이 머리가 없으면 406이 온다. 429/5xx면 기다렸다 다시 한다.
- 결과는 `app/src/main/assets/places/`에 바로 쓴다(`--out`으로 바꿀 수 있다). 만든 날은 OSM 원본의 `timestamp_osm_base` 날짜다.
- 끝에 길·인증센터·정상 수와 파일 크기, 뺀 장소(`DROPPED`)를 찍는다. 1,000,000바이트를 넘으면 쓰지 않고 실패한다.

## 만드는 규칙

- **길:** `places.tsv`의 `name_regex`가 OSM 길 relation(`route=bicycle|hiking|foot`) 이름에 맞으면 모두 합친다(같은 way는 한 번). way들을 끝점이 맞닿는 대로 이어 선을 만들고 Douglas–Peucker 20 m로 줄인다. 맞는 relation이 없으면 빼고 요약표에 `DROPPED`로 적는다. `bbox`(위도0,위도1,경도0,경도1)를 주면 그 안에 든 relation만 쓴다(예: 대구 신천과 경기 신천을 가른다).
- **인증센터:** 이름에 "인증센터"가 든 OSM 점을 가장 가까운 국토종주 길(`#국토종주`) 2 km 안에 붙인다. 2 km 안에 길이 없거나, 이미 넣은 인증센터에서 300 m 안이면 뺀다(도장 부스 `checkpoint=cycling`을 먼저 넣는다).
- **정상:** OSM `natural=peak` 가운데 이름이 같고(`북한산`, `북한산(백운대)`, `덕유산 향적봉` 꼴도 같다고 본다) 시·도 상자 안이며 높이가 목록과 30 m 안인 점이 꼭 하나면 그것을 쓴다. 없거나 둘 이상이면 `peak-overrides.tsv`를 쓰고, 거기도 없으면 산 이름을 찍고 실패한다.
- **순서:** 길(`places.tsv` 순서) → 인증센터(소속 길, 위도, 경도 순) → 정상(csv 순서).

## 장소 더하기

- **길:** `places.tsv`에 줄을 더한다. 먼저 OSM에서 relation 이름을 확인하고, 필요하면 `sources/overpass/routes.ql`의 이름 정규식에도 더한 뒤 `--download`로 다시 받는다.
- **산:** `100-mountains.csv`에 더한다. 이름이 같은 산이 있으면 태그에 지역을 붙인다(`#백운산광양`). 스크립트가 못 찾는다고 하면 OSM에서 정상 점을 찾아 `peak-overrides.tsv`에 `tag, lat, lon, osm_node, note`로 적는다.

## 출처와 라이선스

- 길·인증센터·정상 좌표: © OpenStreetMap 기여자, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/). 앱의 설정 화면에 출처를 밝힌다.
- 100대 명산 목록(이름·소재지·높이): 산림청 "한국의 100대 명산"(2002 선정). 같은 이름의 산 표기는 산림청 목록(백운산 3곳, 사량도 지리산은 `지이망산`)을 따른다.
