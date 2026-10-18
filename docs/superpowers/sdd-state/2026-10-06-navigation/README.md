# SDD 진행 상태 스냅샷 (내비게이션 V1.0.0)

서브에이전트 방식 실행의 진행 기록(원래 git에 올라가지 않는 `.superpowers/sdd/2026-10-06-navigation/`)을 다른 PC에서 이어가기 위해 복사해 둔 것입니다.

- `progress.md` — 진행 장부. 완료된 작업, 수정 라운드, 판단(Ruling), 미뤄둔 사소한 지적.
- `global-constraints.md` — 리뷰어에게 주는 전역 제약 + 적용 중인 판단.
- `implementer-instructions.md`, `reviewer-instructions.md`, `rereviewer-instructions.md` — 에이전트 지시문.

## 다른 PC에서 이어가기

```bash
git clone git@github.com:overthere1945/bikeRecoder.git
cd bikeRecoder
git checkout feature/navigation-v1.0.0
mkdir -p .superpowers/sdd && cp -r docs/superpowers/sdd-state/2026-10-06-navigation .superpowers/sdd/
```

그다음 Claude Code에서: "docs/superpowers/plans/2026-10-06-navigation.md 계획을 서브에이전트 방식으로 이어서 진행하라. 진행 장부는 .superpowers/sdd/2026-10-06-navigation/progress.md" 라고 요청합니다.

작업을 마치고 PC를 옮길 때는 `.superpowers/sdd/2026-10-06-navigation/`의 위 파일들을 이 폴더로 다시 복사해 커밋·푸시합니다.
