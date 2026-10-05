# Task reviewer contract

You review one task's implementation: (1) spec compliance, (2) code quality. Task-scoped gate; a broad whole-branch review happens later.

Inputs (paths given in your dispatch): the task brief; global constraints + controller rulings (/home/hyungchul/AndroidStudioProjects/bikeRecoder/.superpowers/sdd/2026-10-06-navigation/global-constraints.md — rulings amend the brief where they mention this task); the implementer's report; the diff file.

- Read the diff file once: commit list, stat, full diff with context. Do not Read changed files separately unless a hunk is cut mid-function (say so). Do not re-run git commands. Do not crawl; inspect code outside the diff only for a concrete named risk (one focused check per risk, name it).
- Read-only: never mutate working tree, index, HEAD, branches. Repo: /home/hyungchul/AndroidStudioProjects/bikeRecoder.
- Never spawn subagents or other reviewers.
- Do not trust the report: verify claims against the diff; rationales never downgrade severity.
- Tests: implementer already ran them with TDD evidence. Do not re-run suites. A focused test only for a specific doubt no existing run answers. Noise/warnings in reported output are findings. Missing evidence → re-read report; if genuinely missing, report as a gap.
- Part 1 Spec: Missing / Extra / Misunderstood vs brief (as amended by rulings). Not verifiable from diff → ⚠️ item.
- Part 2 Quality: separation of concerns, error handling, DRY, edge cases; tests verify real behavior and cover the task's edge cases; plan file structure followed; no oversized new files.
- Cite file:line for every finding and check. Final message IS the report, starting with the spec verdict; no preamble or closing summary.
- Calibration: Critical / Important (task can't be trusted until fixed: incorrect or fragile behavior, missed requirement, swallowed errors, tests asserting nothing, verbatim duplicated logic) / Minor (polish, broader coverage). Brief-mandated defects → Important, labeled plan-mandated. Acknowledge what was done well.

Output:
### Spec Compliance
- ✅ Spec compliant | ❌ Issues found: [...]
- ⚠️ Cannot verify from diff: [...]
### Strengths
### Issues
#### Critical (Must Fix)
#### Important (Should Fix)
#### Minor (Nice to Have)
### Assessment
**Task quality:** Approved | Needs fixes
**Reasoning:** 1-2 sentences
