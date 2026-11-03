# 기본 배경음악 (V1.11.0)

영상 만들기의 기본 음악 세 곡. 모두 `make_music.py`가 소리를 처음부터 합성해 만든 것이라(샘플·내려받은 음원 없음) 앱에 넣어 배포할 수 있다. 곡마다 150초(가장 긴 영상 120초보다 김)이고, 첫 박부터 소리가 나며 빠르기가 일정해 박자 찾기 시험의 정답 자료로도 쓴다.

| 파일 | 제목 | 빠르기 | 느낌 |
|---|---|---|---|
| `app/src/main/res/raw/music_sunny_pedal.m4a` | 햇살 페달 | 124 BPM, C장조 | 네 박 킥, 뜯는 아르페지오, 밝은 리드 |
| `app/src/main/res/raw/music_wind_road.m4a` | 바람길 | 112 BPM, G장조 | 우쿨렐레풍 스트럼, 셰이커, 통통 튀는 베이스 |
| `app/src/main/res/raw/music_over_the_hill.m4a` | 언덕 너머 | 132 BPM, D장조 | 달리는 북, 신스 스탭, 높은 리드 |

## 다시 만들기

```bash
python3 -m venv /tmp/musicenv && /tmp/musicenv/bin/pip install numpy
/tmp/musicenv/bin/python make_music.py /tmp/music
for f in sunny_pedal wind_road over_the_hill; do
  ffmpeg -y -i /tmp/music/$f.wav -af loudnorm=I=-14:TP=-1.5:LRA=11 -ar 44100 -c:a aac -b:a 128k /tmp/music/$f.norm.m4a
done
```

그 뒤 제목 태그를 붙여 앱의 `app/src/main/res/raw/`에 `music_` 이름으로 둔다(`ffmpeg -i X.norm.m4a -c copy -metadata title="햇살 페달" -metadata artist=bikeRecoder app/src/main/res/raw/music_sunny_pedal.m4a`, 저장소 맨 위에서). 앱이 이 파일을 그대로 넣어 배포한다. 크기는 곡마다 약 2.6 MB(AAC 128 kbps), 소리 크기는 −14 LUFS로 맞춘다.
