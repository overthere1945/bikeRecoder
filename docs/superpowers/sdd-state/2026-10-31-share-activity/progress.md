# SDD ledger — plan: docs/superpowers/plans/2026-10-31-share-activity.md
Spec: docs/superpowers/specs/2026-10-31-share-activity-design.md (user-approved 2026-10-31). Branch feature/share-v1.5.0 from main 3a4ac48.
Pre-flight scan:
| pair / task | produces → consumes | finding |
|---|---|---|
| T1→T3 | ShareTarget, ShareCandidate, emailSubject, MAX_SHARE_TEXT → ShareIntents/ShareCandidates | consistent |
| T1→T4 | ShareFacts, autoShareText, Mood, toggleMood, moodOn, shareStamp, pickFile, fileLines, SharePick → VM | consistent |
| T2→T4 | TripLabel.shareText/shareStamp, setShareText, AppSettings.shareHashtags → VM | consistent |
| T2 internal | setCustomTitle/setRegionName rewrite whole row → must preserve share cols | plan Step covers it |
| T3→T5 | ShareApps, ShareIntents → ShareHost/ShareSheet | consistent |
| T4→T5 | ShareViewModel/ShareSheetState/ShareEvent → ShareSheet/ShareHost | consistent |
| T5 | FeedCardState.canShare, SummaryExtrasState.shareTripId new fields | T5 owns both |
| T6 | version/docs; part B after final review | as V1.4.0 |
| T1 self | test avg 18.4 (32.14 km/1.75 h) vs spec example 18.3 (32.1 km) — both correct for their inputs | none |
| T4 self | close() must not acknowledge a later Done (trip screen card handles it) | stated |
Ruling: none needed; scan clean.
Task 1: dispatched (BASE 12b347e, implementer sonnet)
Task 1: done (1d08bf4) — 35 new tests; JVM core 387 / app 1020 green
Ruling: pickLine adds size only to videos (spec examples have none on images) — cost if wrong: one line text
Task 1: review → Approved. minor (deferred): cleanTag drops supplementary-plane letters; emailSubject should take first non-blank line (user may delete the title line); withTag after trailing "\n"; toggleMood off trims the line
Task 1: complete (commits 12b347e..1d08bf4, review clean)
Task 2: dispatched (BASE 1d08bf4, implementer sonnet)
Task 2: done (e9e830b) — JVM core 387 / app 1020; S22 RideDatabaseMigrationTest 6/6, RoomTripLabelStoreTest 10/10, SettingsScreenTest 16/16, SettingsRepositoryShareHashtagsTest 4/4
Ruling: settings repository tests are a device class (DataStore needs Context, no Robolectric) — like SettingsRepositoryGoalsTest — cost if wrong: none
Ruling: wiring via SettingsViewModel.setShareHashtags / SettingsContent onSetShareHashtags instead of a new SettingsScreen arg — follows existing pattern — cost if wrong: none
Task 2: review → Approved. minor (deferred): SettingsRepository.setShareHashtags unused in production (UI via edit/update); migration test name differs; normalized value may exceed 100 chars (input-only limit per spec)
Task 2: complete (commits 1d08bf4..e9e830b, review clean)
Task 3: dispatched (BASE e9e830b, implementer sonnet)
Task 3: done (f182ca8) — JVM 1407 green; S22 ShareIntentsTest 10/10, ShareCandidatesTest 4/4, MediaStoreWriterTest 3/3; no test files left in gallery
Note: Facebook/Instagram not installed on the S22 — Story/feed class-name lookup unverified against the real app; carry to S25 field checklist (and, if S25 is connected later, a read-only `pm query-activities` is allowed evidence)
Task 3: review → Approved. minor (for final fix wave — real-phone robustness): Instagram lookup hardening (Story filter excludes Direct; ignoreCase; prefer *ShareHandlerActivity for feed; sort by class name; require exported); chooser test should pin clipData+grant on the wrapper. minor (deferred): ComponentName.contains naming; double MediaStore query per file
Task 3: complete (commits e9e830b..f182ca8, review clean)
Task 4: dispatched (BASE f182ca8, implementer opus — state machine with make-then-send)
Task 4: done (29a6f52) — focused 20/20; JVM core 387 / app 1040
Ruling: BUSY / NO_INTERNET use spec §2.5 wording (not StartResult.message) — spec is binding — cost if wrong: wording
Ruling: making shown from press (before STARTED); cancel during start cancels once started — start checks can take ~10 s — cost if wrong: none
Ruling: lastEndedAt = max endedAt of rides ≥ 10 m (a later <10 m stub doesn't change the media's content) — cost if wrong: a tiny stub after the video wouldn't mark it stale
Ruling (carry to Task 5): BikeNavHost's video-outcome effect must NOT open the preview / acknowledge while the share sheet is open and making (share VM owns that Done/Failed); if the sheet was closed, the existing preview flow handles it. After a continued send, clear the result notification if VideoNotifications offers it.
Task 4: review → Needs fixes (1 Important: cancel/close/open-other in the window after Done while refreshing picks still sends)
Task 4: fix round 1/5 dispatched (resume implementer): keep making until finish, re-check dropped before emit, race tests + missing-rule tests; minors: serialize save, recompute stale
Ruling: BUSY right after cancel-during-start and the tiny cancel-vs-Done preview gap stay — retry recovers / Task 5 gate — cost if wrong: one extra tap
Task 4: fix round 1/5 done (44ce364) — focused 33/33; JVM core 387 / app 1053
Task 4: re-review → all 4 findings addressed, no new Critical/Important. Parked minors (final wave): shareStamp-null stale edge; stale hidden before failed DB write; re-press during Done-window refresh may start a 2nd image.
Task 4: complete (commits 29a6f52..44ce364, review clean)
Task 5: implementer dispatched (opus), BASE 44ce364 — rulings carried: enabled() for dimming; preview gate keyed on (outcome, shareMaking); cancelResult after made-send (Send.made); close() after Send
Task 5: implementer DONE_WITH_CONCERNS (4ee7113) — JVM core 387 / app 1058; S22 ShareSheet 13, ShareActions 6, Feed 22, TripDetail 5, Record 17, MainTabs 11
Ruling: trip-screen icon stays in TripTitleRow (plan) over spec's "top bar" — plan was approved with it — cost if wrong: move one icon
Ruling: [저장] after make leaves "영상을 만들었습니다" notification — it opens the made image, harmless — cost if wrong: one extra notification
Task 5: review dispatched (opus) on review-44ce364..4ee7113.diff
Task 5: review → Needs fixes (1 Important: events collected while STOPPED — made-send silently blocked, notification cleared, sheet closed)
Task 5: fix round 1/5 dispatched (resume implementer): repeatOnLifecycle(STARTED) + test; minors: making-only collect, VM-level gate test, Navigate summary order, disable share while discarding
Task 5: fix round 1/5 done (4cae699) — JVM core 387 / app 1060; S22 ShareActions 6, Record 17
Task 5: re-review → all 5 addressed. Parked minors (final wave): STOP-boundary element loss in receiveAsFlow (microsecond window); lifecycle test exercises helper not ShareHost wiring; Navigate summary order device-untested.
Task 5: complete (commits 4ee7113..4cae699, review clean)
Task 6A: implementer dispatched (sonnet), BASE 4cae699 — S25 checklist additions: IG feed/story, FB, paste, preview gate, background make-then-send, navigate summary order
Task 6A: done (321c2e3) — JVM 387+1060, S22 SettingsScreenTest 16/16; checklist 15 items
Ruling: Task 6A reviewed inline by controller (docs + 2-line version bump); CHANGELOG/verification claims vs code go to the final review — cost if wrong: a doc line fixed in the final wave
Task 6A: complete (321c2e3)
Final review: dispatched (opus) on review-3a4ac48..321c2e3.diff with parked items T1–T6A
Final review: Needs fixes — 0 Critical, 1 Important (I1 Instagram lookup nondeterminism + SecurityException crash), Minors M1 dialog truncation, M2 subject, M3 cleanTag, M4 five doc sentences
Ruling: skip withTag "\n", toggleMood trim, T4 three parked, T5 two parked, M5 (#/, separators — spec says spaces), M6 unused setter — per reviewer reasoning — cost if wrong: small polish later
Final fix wave: dispatched (fresh implementer, sonnet) with final-fix-brief.md, BASE 321c2e3
Final fix wave: done (f56a7e8) — JVM 387 / 1068; S22 ShareIntents 10, ShareActions 7, SettingsScreen 17
Final fix wave: re-review dispatched (sonnet); Task 6B: S22 full run started in parallel (58 classes, one per run) on f56a7e8
Final fix wave: re-review → all addressed; residual Minor: CHANGELOG missing sentence break (fix in 6B docs commit); paste past cap silently dropped (accepted)
Task 6B: S22 full run 58 classes / 435 tests pass (2 known assumption skips confirmed); installDebug on S22 (10500, 1.5.0-dev); docs commit
Task 6: complete
