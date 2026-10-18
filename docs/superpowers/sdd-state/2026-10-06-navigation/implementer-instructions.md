# Implementer contract (applies to every task)

- Repo: /home/hyungchul/AndroidStudioProjects/bikeRecoder, branch feature/navigation-v1.0.0. Commit there.
- Your brief file is your requirements, with exact values to use verbatim. Controller rulings in /home/hyungchul/AndroidStudioProjects/bikeRecoder/.superpowers/sdd/2026-10-06-navigation/global-constraints.md amend the brief where they mention your task. Do not read the whole plan file. The spec (docs/superpowers/specs/2026-10-05-navigation-design.md) is background only if needed.
- If something is unclear, return NEEDS_CONTEXT with the question before guessing.
- TDD: write the failing test, run it and see the expected failure, implement, run and see it pass. While iterating run focused tests; run the task's full verification command once before committing.
- Every commit message ends with the line: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
- Never commit build outputs or secrets.json. Check `git status` before committing; add a module-level `.gitignore` with `/build` for any new module.
- You do not dispatch subagents — no helpers, no reviewers. Review is the controller's job after you report.
- Follow the plan's file structure; one responsibility per file. If a file grows beyond the plan's intent or you must restructure beyond the task, stop and report DONE_WITH_CONCERNS.
- It is always OK to stop with BLOCKED/NEEDS_CONTEXT and specifics.
- Self-review before reporting: completeness, YAGNI, tests assert real behavior, test output pristine (call out unavoidable pre-existing warnings).
- After review findings: you may be resumed with findings. Fix, re-run covering tests, APPEND a fix report (changes, covering tests, command, output) to the same report file, reply with the same short contract.

## Report
Write the full report to your report file: what you implemented; tests and results; TDD evidence (RED command + failing output + why expected; GREEN command + passing output); files changed; self-review findings; concerns.
Then reply with ONLY (under 15 lines): Status (DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT), commits (short SHA + subject), one-line test summary, concerns, report file path.
