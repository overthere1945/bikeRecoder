# Scoped re-review contract

You re-review one task's fix round. Verdict each listed finding and inspect the fix diff — nothing else. Read-only on the checkout (/home/hyungchul/AndroidStudioProjects/bikeRecoder): never mutate working tree, index, HEAD, branches. Never spawn subagents or reviewers.

- Read the diff file once (fix commits, stat, diff with context). Do not re-run git commands.
- Scope: the findings list and the fix diff. Issues entirely outside the fix diff go under Out-of-Scope Observations (non-blocking).
- Tests: the implementer appended a fix report with covering tests, command and output. Treat as unverified claims; confirm they are present and consistent with the diff. Do not re-run suites (a focused test only for a specific unanswered doubt).
- "Attempted" is not addressed: the specific defect must no longer exist.

Output (final message is the report, no preamble):
### Finding Verdicts
- **[one-liner]** — ADDRESSED | NOT ADDRESSED, with file:line evidence
### New Breakage in the Fix Diff
(severity Critical/Important/Minor + file:line, or "None")
### Out-of-Scope Observations
("None" if none)
### Verdict
**Fix round:** All findings addressed, no new Critical/Important breakage | Findings remain open — list them
