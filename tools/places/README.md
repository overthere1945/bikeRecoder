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
| `sources/overpass/*.ql` | Overpass 질의. `routes.ql`은 `places.tsv`의 `name_regex`로 스크립트가 만든다(손으로 고치지 않는다) | 넣음 |
| `sources/osm/*.json`, `sources/osm/*.ql` | Overpass 응답 원본(약 12 MB)과 그때 보낸 질의 | 안 넣음(`.gitignore`) |

## 명령

```sh
# 저장소 맨 위에서. OSM 원본을 새로 받고 만든다(Overpass에 질의 3번, 몇 분 걸린다).
python3 -I tools/places/build_places.py --download

# 길 목록(places.tsv)만 바꿨으면 길 원본만 다시 받는다.
python3 -I tools/places/build_places.py --download --only routes

# 받아 둔 원본으로 다시 만든다. 같은 원본이면 같은 바이트가 나온다.
python3 -I tools/places/build_places.py

# 확인
./gradlew :app:testDebugUnitTest --tests '*PlaceAssetTest*'
```

- 받기는 `https://overpass-api.de/api/interpreter`(안 되면 `overpass.kumi.systems`)에 `User-Agent: bikeRecoder-dev/1.0 (personal app)`을 붙여 보낸다. 이 머리가 없으면 406이 온다. 429/5xx면 기다렸다 다시 한다.
- 결과는 `app/src/main/assets/places/`에 바로 쓴다(`--out`으로 바꿀 수 있다). 만든 날은 OSM 원본의 `timestamp_osm_base` 날짜다.
- 끝에 길·인증센터·정상 수와 파일 크기, 뺀 장소(`DROPPED`)를 찍는다. 1,000,000바이트를 넘으면 쓰지 않고 실패한다.

## 만드는 규칙

- **길:** `places.tsv`의 `name_regex`가 OSM 길 relation(`route=bicycle|hiking|foot`)의 `name` 또는 `name:en`에 맞으면 모두 합친다(같은 way는 한 번). 받는 질의 `routes.ql`도 같은 정규식으로 만들어지므로 둘이 어긋날 수 없다. 받아 둔 원본의 질의가 지금 `places.tsv`와 다르면 스크립트가 멈추고 다시 받으라고 한다. 정규식은 Python과 Overpass(POSIX ERE) 양쪽에서 같은 뜻이어야 하고, 한글을 `[...]` 안에 넣지 않는다. way들을 끝점이 맞닿는 대로 이어 선을 만들고 Douglas–Peucker 20 m로 줄인다. 맞는 relation이 없으면 빼고 요약표에 `DROPPED`로 적는다. `bbox`(위도0,위도1,경도0,경도1)를 주면 그 안에 든 relation만 쓴다(예: 대구 신천과 경기 신천을 가른다).
- **인증센터:** 이름에 "인증센터"가 든 OSM 점을 가장 가까운 국토종주 길(`#국토종주`) 2 km 안에 붙인다. 2 km 안에 길이 없거나, 이미 넣은 인증센터에서 300 m 안이면 뺀다(도장 부스 `checkpoint=cycling`을 먼저 넣는다).
- **정상:** OSM `natural=peak` 가운데 이름이 같고(`북한산`, `북한산(백운대)`, `덕유산 향적봉` 꼴도 같다고 본다) 시·도 상자 안이며 높이가 목록과 30 m 안인 점이 꼭 하나면 그것을 쓴다. 없거나 둘 이상이면 `peak-overrides.tsv`를 쓰고, 거기도 없으면 산 이름을 찍고 실패한다.
- **순서:** 길(`places.tsv` 순서) → 인증센터(소속 길, 위도, 경도 순) → 정상(csv 순서).

## 뺀 장소와 찾아본 내용 (OSM 2026-11-01 기준)

`places-summary.tsv`의 `DROPPED` 줄과 같다. 길 원본 질의(`routes.ql`)는 정규식을 이름 첫머리뿐 아니라 빈칸이나 `[` 뒤에서도 찾게 넓혔고 `name`과 `name:en`을 모두 본다. 그와 별도로 아래 두 가지를 손으로 찾아봤다.
1. 한국 안의 `route=bicycle|hiking|foot` relation 1,196개의 태그 전부.
2. relation 종류를 가리지 않고 이름(`name`, `name:en`)에 해파랑·남파랑·서해랑·DMZ·평화의 길·코리아둘레·올레·신천·금호강·온천천·수영강(영문 Haeparang, Namparang, Seohaerang, Peace, Olle 등)이 든 것.

| 태그 | 찾은 것 | 결과 |
|---|---|---|
| `#해파랑길` `#남파랑길` `#서해랑길` | 어떤 relation에도, 어떤 이름 태그에도 없음 | 뺌 |
| `#DMZ평화의길` | "DMZ 평화의 길" relation 없음. 경기 "평화누리길 N코스"(걷기 10개)와 "평화누리자전거길 N코스"(자전거 7개)는 있으나 같은 길이 아니라서 넣지 않음 | 뺌 |
| `#신천` | 길 relation은 경기 양주·동두천의 "신천 자전거도로"(10327246) 하나뿐이다. 대구 신천이 아니므로 `bbox`로 거른다 | 뺌 |
| `#금호강` | 강 자체(waterway·multipolygon)만 있고 길 relation 없음 | 뺌 |
| `#온천천` `#수영강` | 강변 공원 multipolygon만 있고 길 relation 없음 | 뺌 |

넣었지만 OSM에 일부만 있는 길:
- `#제주올레`: relation 22개, 약 253 km. 코스 1, 2, 3(A·B), 4, 5, 6(A·B), 7, 8, 9, 10, 11, 14, 16, 18, 19, 20이 있다. 실제 올레길 전체는 약 430 km이다. 코스 21(5539069)은 `route` 태그가 없어 빠진다.

## 장소 더하기

- **길:** OSM에서 relation 이름을 확인하고 `places.tsv`에 줄을 더한 뒤 `--download --only routes`로 다시 받는다(`routes.ql`은 저절로 바뀐다).
- **산:** `100-mountains.csv`에 더한다. 이름이 같은 산이 있으면 태그에 지역을 붙인다(`#백운산광양`). 사량도 산은 목록 표기 `지이망산`을 이름으로 두되 태그는 흔히 쓰는 `#지리망산`이다(지리산과 이름이 다르므로 지역을 붙이지 않는다). 스크립트가 못 찾는다고 하면 OSM에서 정상 점을 찾아 `peak-overrides.tsv`에 `tag, lat, lon, osm_node, note`로 적는다.

## 출처와 라이선스

- 길·인증센터·정상 좌표: © OpenStreetMap 기여자, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/). 앱의 설정 화면에 출처를 밝힌다.
- 100대 명산 목록(이름·소재지·높이): 산림청 "한국의 100대 명산"(2002 선정). **산림청에서 직접 받은 것이 아니다.** 산림청 표를 옮겨 올린 인터넷 카페 글(<https://m.cafe.daum.net/koreahoney/JNuM/141>, 2026-11-02 확인)에서 가져왔고, 산림청 공식 PDF(`forest.go.kr/images/data/fonDown/100_mountain_ebook.pdf`)는 10 MB가 넘어 받아 맞춰 보지 못했다. 높이에 옮겨 적은 잘못이 있을 수 있다(예: 천성산 812 m, OSM 920 m). 정상 좌표는 OSM에서 높이를 맞춰 보고 정했다.
