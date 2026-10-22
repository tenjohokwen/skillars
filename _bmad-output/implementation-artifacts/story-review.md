# Story Review — skillars-12-7-recap-lifecycle-edit-resume-nudge-and-report-visibility

- **Story file:** `_bmad-output/implementation-artifacts/skillars-12-7-recap-lifecycle-edit-resume-nudge-and-report-visibility.md` (status `ready-for-dev`)
- **Audit date:** 2026-10-22
- **Real HEAD (checked at audit time):** `d06a0b0e build(deps): bump actions/setup-java from 6.0.1 to 6.1.0 (#285)`
- Every citation was checked against this HEAD. The story's own "Citations verified against master @ d06a0b0e" note was not trusted. In this case the SHA happens to be the same, but many line ranges still drifted (see §2).
- **Method:**
  - Four parallel read-only layers ran first: citations, ledger/precedents, mechanistic claims, and corner cases.
  - The orchestrator then re-verified every HIGH/MED finding against source (Step 4b).

---

## 1. Summary of what must change before dev

| # | Severity | Finding | Story location |
|---|---|---|---|
| F1 | HIGH | The coach-erasure guard rests on a false premise: an erased coach's profile still resolves. | AC4 (GDPR tombstones) |
| F2 | HIGH | The epic's "no way back into an abandoned wrap-up" (G7) is not delivered. A cancelled quick-complete wizard leaves a `COMPLETED_PENDING_CONFIRMATION` booking with no Command Center action. | AC2, AC7, AC8 |
| F3 | HIGH | Erased players' sessions match the missing-recap definition. They nag the coach, and the edit then always fails. | AC8 |
| F4 | HIGH | The GET `editable` flag and the PUT guard disagree for an erased player. This breaks AC3's own "never disagree" rule. | AC1/AC3/AC4 |
| F5 | MED | The batch player-name lookup is attributed to `getCoachWeekSchedule`, which is actually N+1. | AC8 |
| F6 | MED | The `NativeModifyingVersionAuditTest` instruction is wrong: the test only covers `@Version` tables. | AC4 |
| F7 | MED | The edit window can be shorter than the stuck-completion timeout, so a swept placeholder can be born un-editable and un-nudged. | AC3 / AC8 |
| F8 | MED | The timeline UPDATE keyed on `reference_id` has no usable index. | AC4 |
| F9 | MED | The "dedicated message" for a closed window is not achievable with `OperationNotAllowedException`'s message. It needs an `ErrorCode`. | AC3 |
| F10 | MED | Two edits can be applied to the timeline out of order (async multi-thread listener, blind overwrite). | AC4/AC5 |
| F11 | MED | Unattended/recovered recaps are ordered by `occurred_at`, which is the async-write time (sweep time for placeholders). | AC16 |
| F12 | LOW | The `ConfigBounds` key must also be added to `ConfigBounds.ALL`, and `ConfigBoundsTest` does not exist. | AC3, Task 3.1 |
| F13 | LOW | The voice step also has a free-text input, so "stores only the literal placeholder" is false. | AC12 |
| F14 | LOW | The report also reaches the player, not only the parent, so the notice wording should cover both. | AC15 |
| F15 | LOW | `ReportGenerationService` glyph-scan loop (`:536-539`) is missing from the checklist. | AC11 / AC14 |
| F16 | LOW | GDPR has five groups, not four (`AVAILABILITY_BLOCK` is missing). The REPORT scrub needs no extension, so the story's hedge resolves to "nothing to do". | Dev Notes |
| F17 | LOW | AC7 cannot choose the "Add" vs "Edit" label: `ScheduleBookingItem` has no recap flag. | AC7 |
| F18 | LOW | There is no `GenerateReportDialog` spec, so "add to the dialog spec" means creating one. | AC15, testing |
| F19 | LOW | Attribution fixes: the ordering bug came from 12-3's review (not 12-2's); `ClockProvider` is a 12-6 precedent (not project-context); the Jackson flag is false because of `CommonConfig`'s builder (not a "Spring Boot default"). | Dev Notes, AC13 |

---

## 2. Citation verification (Layer 1)

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `ConfigBounds.java:185` `DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS` | MATCH | `:185-187` `new BoundedKey("development.roster.dormantAfterDays", 1L, 3650L, false, …, 30L)`. It is registered in `HAS_CODE_DEFAULT` (`:435`) **and in `ALL` (`:459`)**. The story mentions only the former (F12). |
| `Booking.java:116` `isPrepNoteEditable` | MATCH | `:116-119` `return statusOk && (requestedEndTime == null \|\| now.isBefore(requestedEndTime));`. Note the null-`requestedEndTime` arm: the new predicate must decide what null means. |
| `TimelineEventListener.java:42-73` | MATCH | `:42` AFTER_COMMIT, `:43` `@Async("taskExecutor")`, `:70-72` `log.error`, self-proxy `:38-40`. |
| `TimelineEventListener.java:46-54` (Map.of guard) | MATCH | `:46` "skillars-12-2 AC3: Map.of(...) rejects null values", `:54` `new HashMap<>()`. |
| `PlayerTimelineEvent.java:48` | MATCH | `@Column(name = "metadata", columnDefinition = "jsonb", updatable = false)`. All columns are `updatable=false` (`:31,35,38,41,44,48,51`). There is no `@Version`. |
| `PlayerTimelineRepository.java:57-61` (jsonb_exists comment) | DRIFTED | Actually at `:62-65`. `:54-61` is an unrelated ORDER BY/LIMIT review comment. |
| `PlayerTimelineRepository.java:66-73` `findSessionNotesForReport` | MATCH | `:67` `metadata->>'coachId' = CAST(:coachId AS text)`. |
| `PlayerTimelineRepository.java:88-100` `findLatestWhatsNextSuggestion` | DRIFTED | Query at `:84-92` (`:85` `coach_id = :coachId`); comment `:75-83`. |
| `PlayerTimelineRepository.java:84-86` (stale G13 comment) | DRIFTED | Actually at `:80-81`: "the unattended-recap inconsistency is G13 / story 12.7, not touched here". |
| `PlayerTimelineRepository.java:114-120` `anonymizeMetadataByCoachId` | MATCH | `:116` `metadata - 'whatWeDid' - 'whatsNext'`, `:118` jsonb_exists guard. |
| `V138:3280` `idx_scd_booking_id` | MATCH | `V138__baseline_schema.sql:3280`. No `coach_id` index on SCD exists. |
| `CoachCommandCenterPage.vue:131-140` | MATCH | `:132` `v-if="booking.status === 'COMPLETED'"`, `:136` repeatNextWeek. |
| `CoachCommandCenterPage.vue:100-140` (per-status buttons) | DRIFTED (minor) | Buttons run `:102-162` (report no-show `:141-151`, cancel `:152-162`). |
| `CoachCommandCenterPage.vue:320-322`; "977 lines" | MATCH | `:321` `<q-dialog v-model="showSessionPrep">`; `wc -l` = 977. |
| `TimelineQueryService.java:71-90` | MATCH | `:76` method, `:87-88` the two `remove` calls. |
| `SessionCompletionDataRepository.java:40-50` | MATCH | `:42` / `:49` are JPQL `SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL`. |
| `QuickCompleteTimeoutService:65-70` | MATCH | `:65` `new BookingCompletedEvent(`, `:69` recap args. |
| `WrapUpSequence.vue:119-176` | MATCH | `:119` "Step 3: Voice Note"; the block closes at `:177`. |
| `WrapUpSequence.vue:305-355` (recorder/upload) | DRIFTED | `startRecording` `:321-358` (`:325` MediaRecorder, `:331` `signUpload({ entity: 'booking'…`, `:346` confirmUpload). Also missed: the import `:274`, refs `:298-300`, payload `:386`. |
| Step jumps `:32, :103, :114, :221`; `n in 5` | MATCH (incomplete) | Also `:141` and `:175` (`step = 4`), which are inside step 3 and get deleted with it. `n in 5` is at `:6`. |
| "final-step check" | CANNOT_LOCATE | Searched `step === 5`, `step.value`, `watch`, `finalStep`. The only match is the render branch `v-else-if="step === 5"` (`:225`). |
| `BookingCompletionService.java:325-330` `isSweeperPlaceholder` | MATCH | `:325-330` (re-checked by orchestrator). |
| `BookingCompletionService.java:130-211` `submitWrapUp` | DRIFTED | Actually `:129-203`. |
| `BookingCompletionService.java:142-153` ("no update path" comment) | DRIFTED | Comment is at `:135-141` ("there is no update path for an existing row", `:140`); the guard is at `:142-149`. |
| `forceCompleteWithPlaceholderData :~215-260` | DRIFTED | Javadoc `:205-238`, method `:239-270`. |
| `ReportGenerationService.java:638-662` / `:652-659` | DRIFTED (minor) | Block `:637-665`; field discipline `:654-662`. Glyph-scan loop `:536-539` was not cited (F15). |
| `GdprErasureService :405-492` (groups SCD, TIMELINE, REPORT, BOOKING_PLAN_NOTE) | DRIFTED + FACT ERROR | Coach block `:407`, transaction `:478-513`. The enum at `:261` has **five** groups (adds `AVAILABILITY_BLOCK`, `:505-506`). |
| V159 shape; V164 last; V165 free | MATCH | V159 `SET LOCAL lock_timeout = '5s'; ALTER TABLE … ADD COLUMN IF NOT EXISTS …`. The last migration is `V164__booking_bookings_coach_plan_note.sql`. |
| `BookingCompletedEvent` 12-arg; 3+1 production callers | MATCH | `BookingCompletionService:186, :265, :289` and `QuickCompleteTimeoutService:65`. The test callers that also construct the event are SluCalculationServiceIT, SessionPlanServiceTest and HomeworkAssignmentServiceTest. |
| `CoachPlanNoteResource` shape | MATCH | `@Observed(name = "booking.coachPlanNote")`, `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)`, `securityUtil.requireCurrentUserId()`. |
| `verifyCoachOwnership` | MATCH (but see §4) | `:313-317`, private, throws `OperationNotAllowedException(… MISSING_RIGHTS)`, which is a **403**, not a not-found. |
| Test classes named | Mostly exist | Missing: `ConfigBoundsTest` (closest equivalents are `ConfigBoundsEnumCoverageTest` and `ConfigStartupAssertionTest`) and any `GenerateReportDialog` spec. All others exist. |
| Frontend files; `booking.wrapUp.step3*` keys | MATCH | The keys are in en-US `:1267-1272`, de-DE `:435-440` and fr-FR `:1559-1564`, and are referenced only from `WrapUpSequence.vue`. Do **not** remove the unrelated `step3*` pricing keys (en-US `:111-131`). |

---

## 3. Ledger & precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| Epic 12 gap map G7-G13 → 12.7; owner decisions | `skillars-epics.md:3471-3514, :3548-3582` | SUPPORTED. AC9's explicit roster scope-out matches the epic's "Command Center **or** roster". |
| "Decision #8 is coach↔coach only" | epic `:3478`, brainstorm `:92-94, :234`, 12-2 M1 `:101` | PARTIALLY. This is the epic's option-(a) text. Earlier 12-2 / Priority 2 read #8 as also hiding recaps from parents. D1(a) is a real narrowing and is correctly flagged as a decision. |
| G25 denylist; 12.10 owns allowlist / roster / resolver / fan-out cap | epic `:3484, :3514`, 12.10 AC | SUPPORTED |
| 12-3 AC3 consistency rule | 12-3 story `:28`; `CoachPlayerLastSessionResolver.java:15-18` | SUPPORTED |
| 12-5 triple-guarded recap block | 12-5 AC3 `:40`; `PlayerTimelinePanel.vue:46-64` | SUPPORTED. The outer guard is OR(whatWeDid, whatsNext), so a recap containing only whatWentWell would not render. That is safe while both fields stay required. |
| 12-6 lessons and the 455/455 baseline | 12-6 `:117, :120-127, :206` | PARTIALLY. The ObjectMapper lesson is really "use the Spring-configured mapper (`objectMapper.copy()`)", not just "a real ObjectMapper". |
| "12.2's review found ordering bugs → hence 12.3 booking correlation" | 12-2 and 12-3 stories; `CoachPlayerLastSessionResolver.java:43-45` | PARTIALLY / misattributed. The clock-skew/ordering finding is from **12-3's** own review (12-3 `:74`; resolver comment cites "skillars-12-3 code review"). The Map.of pitfall and redaction are correctly attributed to 12-2. |
| deferred-155 AC2/AC3/AC9 | deferred-155 `:69, :71, :92`; `GdprErasureService.java:418-437` | SUPPORTED. AC9's REPORT scrub is not text-keyed (see §4 item 10). |
| deferred-153/154/155 GDPR "standing obligation" | deferred-153 `:57`, -154 `:55`, -155 AC9 | SUPPORTED (paraphrase, not a quote) |
| "Since 12-2 both fields required"; "pre-V159 legacy" | `WrapUpRequest` `@NotBlank`; V159 | SUPPORTED. Placeholders also come from admin manual force-complete (same method). |
| Sweeper placeholder origin | deferred-153 AC1, commit 6b798c34; `StuckCompletionSweeperService.java:76` | SUPPORTED |
| project-context rules | `project-context.md:75, :98, :123`; migration-conventions; frontend-unit-tests.md | PARTIALLY. **`ClockProvider` is not in project-context.md**. It is a 12-6 precedent, and `CommonConfig.java:20` even has `//TODO consider using clockProvider`. |
| openpdf pinned 2.0.5 | `pom.xml:525-532` | SUPPORTED |
| Brainstorm file, Pattern #4 / Priority 2 / Converge #15-16 | brainstorm `:76-77, :173, :177, :229-235, :285-294` | SUPPORTED |
| "Spring Boot default FAIL_ON_UNKNOWN_PROPERTIES=false, no override in src/main/resources (verified)" | `application.yaml:131-134`; `CommonConfig.java:32-37`; `AbstractClient.java:86` | PARTIALLY. The conclusion holds, but the reason is the app's own `Jackson2ObjectMapperBuilder` bean (the framework builder disables the flag), not Boot's auto-config. HTTP uses Jackson 2 on Boot 3.5.16; Jackson 3 is only a transitive logging dependency. Keep the 204 IT. |

---

## 4. Mechanistic claims (Layer 3)

1. **Listener shape (AFTER_COMMIT + `@Async("taskExecutor")`, failures logged, REQUIRES_NEW via `self`)**: TRUE. See `TimelineEventListener.java:38-44, :70-71, :91, :102`. `writeTimelineEvent` is overloaded and the 5-arg calls the 6-arg directly; any new method must go through `self`. The `taskExecutor` bean exists (`AsyncConfig.java:34-35`).
2. **`PlayerTimelineEvent` is non-updatable, so `save()` is a silent no-op**: TRUE (§2).
3. **`NativeModifyingVersionAuditTest` "version/audit hygiene… or the documented opt-out as the other timeline native updates do"**: FALSE (F6). The test only checks UPDATEs on tables backing an `@Version` entity: `if (entitySimple == null) continue; // not a versioned table` (`NativeModifyingVersionAuditTest.java:263-264`). `ALLOWED_WITHOUT_BUMP = Map.of()` (`:83`) is empty. `player_timeline_events` has no version column, so the new UPDATE needs nothing. A literal reading (`version = version + 1`) would fail at runtime. Re-verified by the orchestrator.
4. **Redaction is a two-key denylist, authoring coach only**: TRUE. `TimelineQueryService.java:78, :81-82, :86-88`. The comparison is on the coach **profile** UUID.
5. **`getPrep` sources**: TRUE. `SessionPrepService.java:71, :104, :108-117` (sanitise + truncate to 500).
6. **Suggestion filters the `coach_id` column, report filters `metadata->>'coachId'`**: TRUE. `PlayerTimelineRepository.java:85` vs `:67`. The column is `event.isPlayerAttended() ? event.getCoachId() : null` (`TimelineEventListener.java:61`).
7. **Sweeper placeholder → SESSION_COMPLETED row with column NULL and metadata `{coachId}` only**: TRUE. `BookingCompletionService.java:259-268` plus listener `:55-61`.
8. **"Exactly COMPLETED" catches every completed path, including swept**: TRUE. `QUICK_COMPLETE` is an event, not a status, and all four paths end at `COMPLETED`. Caveat: `DISPUTED`/`REFUNDED` exist in the state machine but have no production transition callers today (re-verified: no `transition(…DISPUTE)` call), so a refunded session stays `COMPLETED` and stays editable/nudged (LOW).
9. **No `completed_at`; `requestedEndTime` indexed; `isPrepNoteEditable` precedent**: TRUE/PARTIAL. `requestedEndTime` is the 4th column of `idx_bkg_coach_status_time` (`V138:3244`), which serves a coach+status-driven query. `BookingCompletionService` and `QuickCompleteTimeoutService` use bare `Instant.now()` today. `ClockProvider` is a static ThreadLocal driven by `TestClockProvider` in tests.
10. **GDPR**:
    - **"After erasure the coach profile no longer resolves"**: FALSE (F1). `GdprErasureService.java:407-411` keeps the profile (`cp.setBio(null); cp.setCity(null); cp.setDistrict(null); coachProfileRepository.save(cp);`). `resolveCoach` (`BookingCompletionService.java:308-311`) is `findByUserId(...).orElseThrow`, so it still succeeds. The only block is `user.setLocked(true)` (`:392`), and `JWTAuthorizationFilter.java:70-90` says the fast path does no DB check, so a lock is noticed only on the 5-minute re-auth cycle. Re-verified by the orchestrator.
    - The group count is five, not four (F16). REPORT scrubs `next_steps` / `storage_key` and deletes the blob (`PerformanceReportRepository.java:64-66`); it does not match on recap text, so `whatWentWell` needs **no** REPORT extension.
    - `PlayerProfile.developmentDataErasedAt` is real (`PlayerProfile.java:69-70`, set at `GdprErasureService.java:1556`). `PlayerProfileRepository` is already injected in `BookingCompletionService` (`:23`, `:49`).
11. **`verifyCoachOwnership` "same not-found/forbidden shape"**: PARTIAL. It returns 403 with the fixed default message (`ApiAdvice.java:329-337`), not a not-found. A 404 only comes from `getBookingOrThrow` / `resolveCoach`.
12. **Recap-source rules, `SessionNoteEntry`, limit**: TRUE. The resolver keys on the attended column plus an SLU same-booking check (`CoachPlayerLastSessionResolver.java:29-38, :53-60`). `SessionNoteEntry(Instant, String, String)` is at `:446`, `REPORT_SESSION_NOTES_LIMIT = 20` at `:137`.
13. **`getCoachWeekSchedule` batch name lookup**: FALSE (F5). `BookingService.java:634` calls `resolvePlayerName(b.getPlayerId())` per row, and that does `findById` (`:1069-1070`). The batch helper is `resolvePlayerNames(Collection<Long>)` (`:1091-1094`, "Deferred-81 AC1"), used by `getParentBookings` / `getCoachBookingRequests`. Re-verified by the orchestrator.
14. **`BoundedKey` constructor arity/order**: TRUE. `BoundedKey(String key, long min, long max, boolean failFast, String note, long defaultValue)` (`ConfigBounds.java:82`). The 4-arg `getBoundedLong` falls back to the default (it does not clamp) when out of range (`ConfigService.java:115`, consumer `CoachRosterService.java:102-106`). The key **must** also go into `ALL`, or `ConfigBoundsEnumCoverageTest.hasCodeDefaultKeysAreAllRealBoundedKeys` fails (F12).
15. **`taskExecutor` bean**: TRUE.
16. **`OperationNotAllowedException` "with a dedicated message"**: PARTIAL (F9). `ApiAdvice.operationDeniedHandler` (`:330-337`) always returns `defaultMsg = "The operation is not granted…"` plus the `ErrorCode`'s key, so the exception's message string never reaches the client. A dedicated message therefore needs a new `BookingError` code. Precedent: `SESSION_ALREADY_ENDED` / `BOOKING_NOT_EDITABLE` (`BookingService.java:786-793`). Re-verified by the orchestrator.
17. **Voice step "otherwise stores only the literal placeholder"**: FALSE (F13). `WrapUpSequence.vue:159-168` has an always-visible free-text `q-input v-model="voiceNoteText"` (when not recording), and `:386` sends it. Real coach-typed text exists in legacy `voice_note_text` rows. That doesn't change the plan (column kept, GDPR scrubs kept), but the story's justification and the ledger note should say so.

---

## 5. Corner cases / false assumptions / missed flows (survived Step 4b)

**F1 (HIGH): an erased coach can still edit (AC4).**
- The profile persists (§4.10), and the access token stays valid up to about 5 minutes.
- `anonymizeMetadataByCoachId` keeps `coachId` in metadata (`PlayerTimelineRepository.java:116` only removes the two text keys), so AC4's `metadata->>'coachId'` match still hits.
- **Scenario:** the erasure completes, and within the fast-path window the erased coach's token issues `PUT /recap`. Text is rewritten into the scrubbed SCD row, the async listener re-adds the metadata keys, and the GDPR request is already `COMPLETED`.
- The story's prescribed IT ("edit after coach erasure is rejected") will fail against the code AC4 describes.
- **Fix:** an explicit guard in `updateRecap` (e.g. reject if the coach's `User` is locked/erased). The same guard should feed `editable` (see F4).
- Related, narrower and downgraded to LOW: an in-flight async listener that runs after the erasure commit can re-add keys. The listener should re-check the tombstone, or the edit's SCD row should be locked `FOR UPDATE` before the tombstone check, which also closes the player-direction TOCTOU against `GdprErasureService.java:1524`.

**F2 (HIGH): the "resume an abandoned wrap-up" part of G7 is not covered.**
- Epic `skillars-epics.md:3496`: "no way back into an abandoned wrap-up". The 12.7 AC lists "abandoned wizard" as a case.
- `handleQuickComplete` calls `handleInitiateQuickComplete`, which moves the booking to `COMPLETED_PENDING_CONFIRMATION`, and then opens the wizard (`CoachCommandCenterPage.vue:583-596`). Cancel only does `showWrapUp = false` (`:22`).
- The grid has no button for `COMPLETED_PENDING_CONFIRMATION` (only `UPCOMING`, `CONFIRMED/UPCOMING` and `COMPLETED`), and no coach-facing frontend file handles that status (it appears only in `BookingStateChip.vue` and `ParentBookingsPage.vue`).
- AC2 and AC8 both require exactly `COMPLETED`. So the coach can't resume, edit or be nudged until the sweeper fires (default 72h, max 720h; `ConfigBounds.java:121-123`). After the sweep, attendance, ratings and homework are lost for good (placeholder, and `updateRecap` doesn't set them).
- **The story title promises "Resume".** Either add a "Resume wrap-up" button on CPC blocks (it re-opens `WrapUpSequence` in non-LIVE mode; `submitWrapUp` already accepts CPC via `verifyStatus`), or explicitly scope resume out with owner sign-off and a ledger entry.

**F3 (HIGH): erased players pollute the nudge (AC8).**
- Player erasure runs `anonymizeByPlayerId` (`SessionCompletionDataRepository.java:42`, called at `GdprErasureService.java:1524`), which nulls `what_we_did` / `whats_next`. The booking stays `COMPLETED`.
- Those sessions therefore satisfy AC8's definition, and every "Add recap" is then rejected by AC4's `developmentDataErasedAt` check. The banner shows uncloseable items for up to the window length.
- **Fix:** exclude `player_profiles.development_data_erased_at IS NOT NULL` in the missing-recaps query, and add an IT case.

**F4 (HIGH): `editable` and the write guard disagree (AC1/AC3 vs AC4).**
- AC3 mandates one predicate `Booking.isRecapEditable(now, windowDays)` for both. But AC4 adds write-only rejections (player erased; F1's coach guard).
- GET would return `editable=true` and the dialog would open in edit mode, then PUT fails.
- **Fix:** the read side must compute `editable` from the same composite check (window ∧ status ∧ not-erased), not just the `Booking` predicate.

**F5 (MED): the name-lookup precedent is wrong (AC8).** See §4.13. Copying the cited method reintroduces the N+1 that AC8 forbids. Use `resolvePlayerNames` (Deferred-81 AC1).

**F7 (MED): the window can close before the placeholder exists.**
- The window is 1-365 days from `requestedEndTime`. The sweeper fires on `b.updatedAt < :cutoff` (`BookingRepository.java:310`) with a 6-720h timeout.
- With `editWindowDays` < the stuck timeout plus a margin (e.g. window 1d with the 72h default, or 720h timeout with a 30d window minus slack), a swept session is born outside the window. It is uneditable, and because AC8 also filters on the window, it is never nudged.
- **Fix:** raise the min bound, or validate the coupling, or at least document it with a test.

**F8 (MED): no index for the AC4 update.**
- There is no index on `player_timeline_events.reference_id` (only `(player_id, occurred_at)` at `V138:3334` and `(player_id, coach_id, event_type, occurred_at)` at `V161`; the orchestrator's grep confirmed no `reference_id` index).
- **Fix:** add `playerId` to `SessionRecapUpdatedEvent` and `player_id = :playerId` to the WHERE clause to use the existing index.

**F9 (MED): the dedicated closed-window error needs an `ErrorCode`.** See §4.16. Add e.g. `BookingError.RECAP_EDIT_WINDOW_CLOSED` (i18n x3 on the frontend error mapping). Note it still maps to HTTP 403 via `ApiAdvice`. Tests must assert the `errorKey`, per the 12-6 lesson.

**F10 (MED, lower probability): out-of-order timeline edits.**
- Each `SessionRecapUpdatedEvent` carries full values. The listener does a blind overwrite on the multi-thread `taskExecutor`, and `SessionCompletionData` has no `@Version` or `updatedAt`.
- Two saves seconds apart can leave the timeline, report and prep on the older text while the SCD row holds the newer one. AC5 only covers the missing-row case.
- **Fix:** carry a monotonic marker (e.g. an SCD `updated_at`, or a re-read of the SCD row inside the listener) and apply only if newer. Alternatively, have the listener re-read the current SCD values instead of trusting event payload values.

**F11 (MED): AC16 ordering uses `occurred_at`, which is the write time.**
- `TimelineEventListener.java:111` does `evt.setOccurredAt(Instant.now())`, so a swept session's event is stamped at sweep time (at least 72h after the session).
- AC16 makes those events eligible for the suggestion (`ORDER BY occurred_at DESC`), and `SessionPrepService` shows `suggestion.getOccurredAt()` as the session date (`:120`).
- **Scenario:** Monday's session is abandoned and swept Thursday, and Wednesday's session is completed normally. After recovery, Monday's "what's next" outranks Wednesday's and is labelled with Thursday's date. The report's Session Notes date has the same skew.
- **Fix:** acknowledge it in the story, or order and label by the booking's session time (e.g. put `sessionEndTime` into metadata).

**F12 (LOW):** add the key to `ConfigBounds.ALL` (`:443+`). The real tests are `ConfigBoundsEnumCoverageTest` and `ConfigStartupAssertionTest`.

**F13 (LOW):** correct AC12's rationale. Real typed voice-note text exists in legacy rows. The `deferred-work.md` entry should say the column may hold coach free text (relevant for the contract migration and GDPR).

**F14 (LOW):** reports are listable by parent **and player** (`ReportGenerationService.java:300-306` comment), and `notifyParent` skips players without a parent email (`:409-414`). The AC15 notice should say "the player and their parent", not only "the parent".

**F15 (LOW):** add `whatWentWell` to the glyph-scan loop at `ReportGenerationService.java:536-539`. It does not appear in a `METADATA_KEY_WHATS_NEXT` grep, so the story's own checklist method misses it.

**F16 (LOW):** five GDPR coach groups, not four. The REPORT group deletes the PDF and does not match on text, so the Dev Note's "verify… extend it if it keys on text fragments" resolves to "no change".

**F17 (LOW):** `ScheduleBookingItem` (`booking/contract/ScheduleBookingItem.java`) has no recap flag, so AC7's "Add recap"/"Edit recap" label needs either a new field (`hasRecap`) or a neutral label.

**F18 (LOW):** no `GenerateReportDialog` spec exists today, so AC15 and the testing list need "create".

**F19 (LOW):** attribution fixes, see §3 (12-3 vs 12-2, ClockProvider source, Jackson reason, ObjectMapper lesson wording).

---

## 6. What did not survive re-verification (killed / downgraded)

- **"Recap text bypasses contact-detail sanitising" (Layer 4 L5).** This was downgraded to an out-of-scope note. Recaps were already stored raw before this story (`BookingCompletionService.java:162-163`), and D1(a) only documents an existing parent-facing channel. It is worth a ledger line, but it is not a defect introduced or missed by 12.7.
- **In-flight async listener re-adding keys after coach erasure (Layer 4 M3).** This was downgraded from MED to a LOW sub-point of F1. It needs an edit committed in the milliseconds before erasure; F1's guard plus a listener-side re-check covers it.
- **"Rolling-deploy reasoning contradicts 12-2's 'app not released' stance" (Layer 2 observation).** This was dropped. Being conservative about rolling deploys is not a defect.
- **"`QuickCompleteTimeoutServiceTest` builds the event — update" is moot because the test uses an `ArgumentCaptor` (Layer 4 L7).** This is not independently re-verified by the orchestrator and is treated as PLAUSIBLE. At worst the dev finds the test needs only an extra assertion.
- **`COMPLETED → DISPUTED` locking edits during a dispute (Layer 3).** This was killed: there are no production `transition(…DISPUTE…)` callers (orchestrator grep). It was replaced by the LOW note that refunded sessions stay `COMPLETED` (§4.8).
- **Minor line-range drift in §2.** The drift is real, but none of it changes what a developer would edit. It is listed for correction only, not as a finding.

---

## 7. Recommendation

The story should be **revised before dev**, but it doesn't need restructuring. Per-claim confidence:

- **High confidence (re-read by the orchestrator against source):**
  - F1 (coach profile survives erasure; JWT fast path)
  - F2 (no CPC action on the grid; wizard cancel just closes; epic G7 text)
  - F3 (player anonymize nulls both recap columns)
  - F5 (per-row `resolvePlayerName`)
  - F6 (audit test skips unversioned tables)
  - F9 (`ApiAdvice` fixed default message)
  - F11 (`setOccurredAt(Instant.now())`)
  - F12 (`ALL` list)
  - F13 (free-text input `:159-168`)
  - F14 (`notifyParent` / player listing comment)
  - F15 (glyph loop)
  - F16 (five-group enum)
  - absence of a `reference_id` index (F8)
- **Medium confidence:**
  - F4 is a logical consequence of F1/F3 plus AC3's wording, not a code defect today.
  - F7 depends on how config is tuned; the default 30d/72h is safe.
  - F10 is real in mechanism but has a low-probability timing window.
- **Not independently re-checked by the orchestrator:** the per-key i18n line numbers, the test-file inventory beyond the spot checks, and the `QuickCompleteTimeoutServiceTest` captor detail. These rely on a single layer's report.
- **Confirmed sound:** the overall architecture (event + AFTER_COMMIT async listener + native UPDATE, no second event, `updatable=false` reasoning), V165 being free, `BookingCompletedEvent` callers, the Jackson unknown-property outcome, the week grid including `COMPLETED`, precedents 12-3 AC3, 12-5 triple guard and deferred-155 AC2/AC3/AC9, and the `coach-private` grep being a no-op.

**Owner decision needed:** F2 (implement "resume" for the `COMPLETED_PENDING_CONFIRMATION` state, or descope it from the title and AC with a ledger entry). Every other finding is a mechanical story correction.
