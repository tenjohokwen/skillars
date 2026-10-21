# Story deferred-157: Booking-Schema Erasure Reason Codes, Availability-Block Reason Scrub & Test-Tooling Hygiene Sweep

Status: ready-for-dev

## Story

As an **operator triaging GDPR erasure alerts, and as a developer maintaining this codebase's test tooling**,
I want **the remaining open items from `skillars-deferred-156`'s code review closed in one pass**: the player-branch alert labeling that sends booking-schema lock trips to the wrong module, the last unscrubbed coach-authored free-text column in the `booking` schema, a dead minor's-name-from-URL computed, two untested coach-erasure reason codes, a misleading lock comment, and a set of i18n test-tooling and repo-hygiene nits,
so that **every GDPR alert names the schema that actually failed, a coach's erasure leaves no authored free text behind in `booking.*`, and the i18n guard specs enforce what their own docs claim**.

## Source

This is a **deliberately bundled, multi-origin story**. Every item comes from `_bmad-output/implementation-artifacts/deferred-work.md`, section **"Deferred from: code review of skillars-deferred-156-booking-notes-gdpr-coverage-pdf-font-encoding-player-privacy-and-hardening-sweep (2026-10-20)"**, the most recently appended ledger section.

**Companion brainstorming doc is exhausted. Re-verified 2026-10-21 at story creation.** Every item in `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` traces to a shipped story:
- Priorities 1–4 and 4b → `skillars-12-1` … `skillars-12-5`.
- Converge #16 (recap → report generation) and Deferred #17 (player/parent-initiated flag) → `skillars-deferred-155`.
- The flagged `/development` relationship-check gap → `skillars-12-4`.

Only the doc's own summary list (lines 213–214) is stale: it still says "still open, no story yet" and "still parked". AC10 fixes that.

**Every citation below was re-verified against real HEAD** (`master` @ `56803337`, the `skillars-deferred-156` merge, PR #282) at story-creation time. The ledger's own line numbers were written against a pre-merge working tree and have drifted. Where they drifted, the corrected line is used below.

### Ledger item → AC map (so future mining passes do not re-pick these)

| # | Ledger bullet (deferred-156 code-review section) | Verified at HEAD | AC |
|---|---|---|---|
| L1 | "A lock trip on the `booking.bookings` erasure UPDATE is reported under the development-branch `CHILD_*` reason codes" | `GdprErasureService.java:1411-1418` (two `booking`-schema statements inside `deletePlayerDevelopmentDataInDedicatedPool`'s try at `:1361`). Mapped to `CHILD_DELETE_LOCK_TIMEOUT` by the wrap at `:1456-1457`. A deadlock is rethrown raw at `:1448-1455` and lands as `CHILD_CONTENDED` at the call sites `:575/:590` (PLAYER) and `:936/:945` (PARENT) | AC1, AC2 |
| L2 | "`booking.coach_availability_blocks.reason` is coach-authored free text that no GDPR erasure branch scrubs" | `CoachAvailabilityBlock.java:37-38`, `varchar(500)` (`V138__baseline_schema.sql:258`). Zero references in `GdprErasureService` (grepped) | AC3, AC4 |
| L3 | "`PlayerDevelopmentDashboardPage.vue:192` still reads a player name from the URL query string" | `:192` `route.query.playerName ?? ''`, bound at `:106` | AC5 |
| L4 | "No `.gitattributes` exists, so the repo's first binary assets … carry no `binary` marker" | No `.gitattributes`. Tracked binaries: 2 `.ttf`, 4 `.png`, 1 `.ico` | AC6 |
| L5 | "Two of the six new coach-erasure reason codes have no test coverage" | `GdprErasureServiceTest.java:579-652`. Only `SCD_CONTENDED` is tested among the `*_CONTENDED` codes | AC7 |
| L6 | "`collectKeyPaths.js` ships in production app source rather than test-only source" | `src/frontend/src/i18n/collectKeyPaths.js`, imported by 2 specs only | AC8 |
| L7 | "An in-code comment asserts a plain SELECT cannot trip a lock timeout" | Now at `GdprErasureService.java:226-232` (ledger said `:217-222`) | AC9 |
| L8 | "i18n template-key spec hardening nits" (a)/(b)/(c) | `i18nTemplateKeyResolutionSpec.js:45-155` (list), `:166-170` (`stripLineComment`), `:245-251` (floor) | AC11 |

### Explicitly NOT in this story (and why)

- **"The backend's `"Unknown Player"` fallback …"**: owner-`Decided 2026-10-20: accept, do not change`. Not re-litigated.
- **"PDF reports still silently drop glyphs outside Noto Sans latin-greek-cyrillic coverage"**: owner-**decided 2026-10-21: out of scope**. Non-Latin/Greek/Cyrillic scripts are not supported in PDF reports, and the existing unrenderable-code-point warning log is the accepted end state. The ledger bullet now carries a `[DECIDED]` marker. Do not touch the font code or that bullet in this story.
- **Everything older in the ledger that is still open** was re-checked and is one of three kinds:
  - owner-blocked on production evidence: the `main."user"` cleanup-sweep index, re-confirmed deferred by `skillars-deferred-125`;
  - investigation-led: the `VideoModerationEmailListener` `persisted == null` root cause, the `MailManager` circuit breaker;
  - human-only: fr/de native review, the deferred-140 browser walkthrough;
  - deliberately scoped "revisit only if it reaches production" (the deferred-154 route-constraint specs), or a distinct feature (the `/api/disputes` UI).

  None is a bundleable mechanical fix.

## Acceptance Criteria

### Player-branch GDPR alerts name the `booking` schema when that is what failed (L1)

1. **The two `booking`-schema statements in `deletePlayerDevelopmentDataInDedicatedPool` get their own reason-code pair.** Today `sessionCompletionDataRepository.anonymizeByPlayerId` (`GdprErasureService.java:1411-1412`) and `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId` (`:1417-1418`) sit inside the same try (`:1361`) as the development-schema groups, so a trip on either is reported as:
   - `CHILD_DELETE_LOCK_TIMEOUT` when it is a `lock_timeout`, wrapped at `:1456-1457`;
   - `CHILD_CONTENDED` when it is a deadlock, rethrown raw at `:1448-1455`.

   An operator then investigates `development.*` while the failing statement was in `booking.*`.

   Fix:
   - Add two constants next to the existing `CHILD_*` reasons (`:182-194`): `CHILD_BOOKING_LOCK_TIMEOUT = "CHILD_BOOKING_LOCK_TIMEOUT"` and `CHILD_BOOKING_CONTENDED = "CHILD_BOOKING_CONTENDED"`. Keep the `CHILD_` prefix: these still mean "one of N child player profiles", exactly like their siblings.
   - Add a new private static nested exception beside `DeleteStatementLockTimeoutException` (`:1566`), e.g. `BookingSchemaLockException extends RuntimeException`. It carries a `boolean contended` and the cause.
     - It must **NOT** extend `PessimisticLockingFailureException`. That way it passes untouched through the method's own outer `catch (CannotAcquireLockException)` / `catch (PessimisticLockingFailureException)` (`:1448-1458`), and through the `requiresNewTemplate` boundary, which rolls the inner transaction back on any `RuntimeException`.
   - Wrap **only** the two `booking`-schema calls (including their `setStatementLockTimeout` calls) in an inner try:
     - `catch (CannotAcquireLockException e)` → `throw new BookingSchemaLockException(e, true)`;
     - then `catch (PessimisticLockingFailureException e)` → `throw new BookingSchemaLockException(e, false)`.

     Order matters: `CannotAcquireLockException` is a subclass of `PessimisticLockingFailureException`.
   - Leave the development-schema groups, the outbox enqueue and the tombstone flush in the outer try **exactly as they are**. A trip there must still produce `CHILD_DELETE_LOCK_TIMEOUT` / `CHILD_CONTENDED` / `CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED` unchanged.

2. **Both call sites classify the new exception with the same skip semantics as their existing lock catches.**
   - **PLAYER branch** (`:575-598`): add `catch (BookingSchemaLockException e)`. Pick `CHILD_BOOKING_CONTENDED` if `e.contended`, else `CHILD_BOOKING_LOCK_TIMEOUT`, then:
     - log a WARN that names `booking.*` (not "development-data deletion");
     - `raiseErasureAlert(requestId, reason)`;
     - `skipped.set(true)`.

     Data survives this run, so the request must read `FAILED` and the daily retry must re-drive it. Same Article 17 reasoning as the existing catch (`:592-597`).
   - **PARENT loop** (`:936-957`): add the equivalent catch. It does `skipped++`, `anyUnrecoverableSkip = true`, a WARN naming `booking.*`, and `raiseErasureAlert(requestId, reason)`.
   - `CHILD_VANISHED` semantics are untouched.
   - Update the reason-code lists in prose so they stay accurate:
     - `raiseErasureAlert`'s Javadoc (`:990-994`);
     - the comment in `AdminQueueService.buildSummary` (`AdminQueueService.java:173-179`).

     `AdminQueueService` only prefixes `reason` verbatim, so no code change is needed there. Grepped: no frontend or docs file maps these codes.

### Coach erasure scrubs `coach_availability_blocks.reason` (L2)

3. **A coach's erasure nulls `booking.coach_availability_blocks.reason` for every block they own.**
   - Add to `CoachAvailabilityBlockRepository.java`:
     ```java
     @Modifying
     @Query("UPDATE CoachAvailabilityBlock b SET b.reason = NULL WHERE b.coachId = :coachId")
     void anonymizeReasonByCoachId(@Param("coachId") UUID coachId);
     ```
   - `coach_id` holds the `marketplace.coach_profiles.id` UUID (`AvailabilityService.java:317-318`, `block.setCoachId(lockedProfile.getId())`), so pass `cp.getId()`, the same id the three existing coach-scrub groups use.
   - The predicate is indexed (`idx_availability_blocks_coach_id`, `V138__baseline_schema.sql:3214`).
   - `CoachAvailabilityBlock` has **no `@Version`** and **no `@Audited`** (verified: no `booking.*_aud` tables exist). So a plain bulk `UPDATE` is correct here:
     - `NativeModifyingVersionAuditTest` will not flag it;
     - no stale-managed-entity resurrection race exists;
     - no audit table keeps a copy.

     Do **not** copy `BookingRepository`'s `version = version + 1` idiom (`skillars-deferred-156`'s post-PR CI fix). That exists only because `Booking` *is* `@Version`.
   - **Scrub and keep the rows; do not delete them.** `reason` is nullable (`V138:258`) and is the only authored free text in the row. `start_datetime`/`end_datetime` are not personal data about anyone but the (now-anonymised) coach's own calendar. This matches the codebase's scrub-and-keep precedent for coach-side erasure (`performance_reports`, skillars-deferred-155 Decision 2(a)).

4. **Placement: a fourth group inside the existing coach-scrub `requiresNewTemplate` block, not inline in `eraseTransactional`.**
   - Add `AVAILABILITY_BLOCK` as a fourth value of `CoachRecapGroup` (`:240`). Renaming the enum is optional; keeping the name minimises diff.
   - Add a fourth reason-code pair: `COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT` / `COACH_AVAILABILITY_BLOCK_CONTENDED`.
   - Add the group **after** `REPORT` in the lambda (`:446-466`): `coachRecapCurrentGroup.set(CoachRecapGroup.AVAILABILITY_BLOCK); coachAvailabilityBlockRepository.anonymizeReasonByCoachId(cp.getId());`.
   - Extend the exhaustive switch (`:484-488`) with its case. Adding the enum value without the case is a compile error, which is the point of that enum.
   - Update the block's comments to match:
     - the constants' Javadoc (`:196-221`) says "three groups";
     - the WARN at `:489-493` says "session-recap/report scrub".

   **Why not inline?** An inline statement would run with no `lock_timeout` bound. That is the exact hazard the comment at `:408-431` documents for this block. A trip would also roll back the *entire* erasure into `markFailed`'s generic `UNCLASSIFIED_FAILURE`. Inside the block, a trip is bounded by the already-set transaction-scoped `lock_timeout`, alerted with its own label, and gates `skipped` so the daily retry re-drives it. The UPDATE is idempotent.

   **Lock ordering (checked).** The new statement takes row locks on `coach_availability_blocks` in a `REQUIRES_NEW` transaction, while the outer `eraseTransactional` has not flushed its own `coach_profiles` UPDATE (`coachProfileRepository.save(cp)`, `:379`, is flushed at outer commit). `AvailabilityService.createBlock`/`deleteBlock` lock `coach_profiles` `FOR UPDATE` first, then touch blocks (`:300-301`, `:327-329`). The inner transaction holds no `coach_profiles` lock, so it cannot form a cycle with those writers. It commits before the outer transaction ever flushes `coach_profiles`. This also does not trip `REQUIRES_NEW` self-deadlock: the outer transaction never wrote a `coach_availability_blocks` row.

   **Known residual, same class as the owner-accepted one.** An erased coach's still-valid access JWT can create a *new* block with a reason after erasure commits. This is the residual recorded under "code review of skillars-deferred-154" and accepted there (`markAllUsedByUserId` revokes refresh tokens only). Record it in Completion Notes; do not fix it here.

### Frontend: delete the dead `playerName`-from-query computed (L3)

5. **`PlayerDevelopmentDashboardPage.vue` stops reading `route.query.playerName`.**
   - Delete `const playerName = computed(() => route.query.playerName ?? '')` (`:192`) and the `:player-name="playerName"` binding (`:106`). `PerformanceReportsPanel`'s prop defaults to `''` (`PerformanceReportsPanel.vue:75`).
   - The ledger's "inert" claim was **re-verified, with one nuance**. `PerformanceReportsPanel` forwards `playerName` into `GenerateReportDialog`'s title (`GenerateReportDialog.vue:6`, `generateTitle: 'Generate Report for {name}'`). On *this* page that dialog is unreachable:
     - the route is `meta.role: 'PLAYER'` (`routes.js:283-287`);
     - the generate button is `v-if="isCoach && canGenerate"` (`PerformanceReportsPanel.vue:7`).

     So deleting is the right fix. Wiring the name from `store.exposure?.playerName` would fill a title nobody can open.
   - Nothing in the repo pushes `playerName` onto this route. `PlayerDevelopmentDashboardPageSpec.js` has no `playerName` reference (grepped).
   - `route` stays in use for `playerId` (`:191`).

### Repo hygiene (L4)

6. **Add a root `.gitattributes` that marks the tracked binary types as `binary`.** Minimum lines: `*.ttf binary`, `*.png binary`, `*.ico binary`. Preemptive `*.otf`, `*.woff`, `*.woff2`, `*.jpg`, `*.jpeg`, `*.gif`, `*.pdf` lines are fine.
   - **Do NOT add `* text=auto`** or any `eol=` rule. That would mark every text file for renormalisation and turn this one-line change into a repo-wide diff.
   - Verify with `git ls-files --eol src/main/resources/fonts/`: the `.ttf` rows must show `attr/-text -diff -merge` (or `attr/binary`), and no tracked file shows as modified afterwards (`git status`).
   - Note for the record: local `core.autocrlf` is `input` on this machine (the ledger said "unset"). Git's NUL-byte heuristic already classifies the TTFs as `-text`, so this remains insurance, not a live-defect fix.

### Test coverage for two untested coach-erasure reason codes (L5)

7. **Add `GdprErasureServiceTest` cases for `COACH_RECAP_TIMELINE_CONTENDED` and `COACH_RECAP_REPORT_CONTENDED`,** mirroring `erase_coachUser_sessionCompletionDataGroupContended_raisesScdContendedReason` (`:607-619`):
   - throw `new CannotAcquireLockException("deadlock")` from `playerTimelineRepository.anonymizeMetadataByCoachId(COACH_PROFILE_ID)`;
   - then, separately, throw it from `performanceReportRepository.anonymizeByCoachId(eq(COACH_PROFILE_ID), anyString())` (a `when(...).thenThrow`, as `:640-641` does, because that method is not `void`);
   - each test asserts the request is `FAILED` and the single alert's reason.

   Also add the AC3/AC4 equivalents:
   - `COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT`, with `PessimisticLockingFailureException`;
   - `COACH_AVAILABILITY_BLOCK_CONTENDED`, with `CannotAcquireLockException`.

   These two tests assert that groups 1–3 genuinely ran first (`verify(...)` on each). Then extend `erase_coachUser_allThreeGroupsSucceed_completesWithNoAlert` (`:579-587`) to `verify(coachAvailabilityBlockRepository).anonymizeReasonByCoachId(COACH_PROFILE_ID)`. Renaming it to "allFourGroups…" is fine.

   `GdprErasureService` gains a constructor dependency (`CoachAvailabilityBlockRepository`), so update the hand-built constructor call in `GdprErasureServiceTest.setUp()` (`:146-152`) with a new `@Mock`. No other test constructs this service by hand (grep `new GdprErasureService(` to confirm).

### `collectKeyPaths.js` moves to test-only source (L6)

8. **Move `src/frontend/src/i18n/collectKeyPaths.js` to `src/frontend/test/vitest/collectKeyPaths.js`**, next to `setup-file.js`.
   - Update both imports: `localeKeyParitySpec.js:16` and `i18nTemplateKeyResolutionSpec.js:38` become `../../../test/vitest/collectKeyPaths.js`.
   - Rewrite the file's header comment for its new location. `vitest.config.mjs:49-55`'s two `include` globs (`src/**/*.{spec,test}.{js,mjs}` and `src/**/__tests__/**/*.{js,mjs}`) match nothing under `test/`, so it will not be collected as a spec.
   - **Accepted, disclosed tradeoff:** `npm run lint`'s glob is `./src*/**/*.{js,cjs,mjs,vue}` (`package.json:10`), so the moved file falls out of ESLint coverage. That is exactly `setup-file.js`'s existing status. Prettier (`**/*`) still covers it, and so does the CI Prettier gate (`.github/actions/`). Record this in Completion Notes; do not widen the lint glob in this story.
   - Use `git mv` so history follows the file.

### Comment accuracy (L7)

9. **Correct the absolute claim in `GdprErasureService.java:226-232`.** It says `findStorageKeysByCoachId` (a plain SELECT) and the outbox INSERTs "neither can actually trip a lock_timeout/contention error on its own". That is false as an absolute: a plain SELECT takes `ACCESS SHARE`, which conflicts with `ACCESS EXCLUSIVE` (concurrent `ALTER TABLE`, `VACUUM FULL`, `REINDEX`, …), and will trip `lock_timeout`.
   - Reword it to say that under normal DML-only contention only the `anonymizeByCoachId` UPDATE takes row locks that can conflict, while the SELECT can still block on a concurrent `ACCESS EXCLUSIVE` DDL/maintenance lock.
   - Keep the conclusion: the grouping is correct either way, because the SELECT must share the UPDATE's transaction for `BlobDeletionOutboxSupport`'s reasons. A trip in it is labelled `REPORT`, which is correct.
   - Comment-only change.

### Ledger and brainstorm-doc bookkeeping

10. **Annotate both source documents so future mining passes skip these items.**
    - In `deferred-work.md`, append a `**[CLOSED by skillars-deferred-157 ACn: …]**` marker to each of L1–L8, following the established in-place style (e.g. the `[CLOSED by skillars-deferred-156 AC2 …]` markers in the "code review of skillars-deferred-155" section). Do not delete the bullets.
    - Do **not** touch the "Unknown Player" `[Decided]` bullet or the PDF-glyph `[DECIDED 2026-10-21]` bullet.
    - In `brainstorming-session-2026-10-14-19-55-07.md`, replace the stale "still open, no story yet" (item 5, line 213) and "still parked" (item 6, line 214) with `covered by skillars-deferred-155-recap-report-tie-in-player-session-request-gdpr-metadata-scrub-and-hardening-sweep (created 2026-10-19)`, matching the format of items 1–4b above them.

### i18n template-key spec hardening (L8)

11. **`i18nTemplateKeyResolutionSpec.js` enforces what its own module doc promises.**
    - **(a) Reasons are asserted.** Add an `it(...)` asserting every `DYNAMIC_KEY_CALL_SITES` entry has a `reason` that is a non-empty string after `.trim()`. The module doc (`:24-26`) says "with a one-line reason, or this spec fails", and today nothing checks it. Also assert `at` values are unique. A duplicate entry would silently mask a stale one in the inverse check (`:275-283`).
    - **(b) Block comments are not scanned as live code.** Before splitting into lines, blank out the contents of `/* … */` and `<!-- … -->` spans **while preserving every `\n`**. For example: `content.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))`, and the same for `<!--[\s\S]*?-->`.
      - Line numbers must not move: `DYNAMIC_KEY_CALL_SITES` is keyed by `file:line`.
      - **Verify there is no collateral:** run the spec before and after and confirm `literalCalls.length`, the `dynamicCalls` set and the `wrappedCalls` set are unchanged. A `/*` inside a string literal or a glob such as `'src/**/*.vue'` would otherwise eat real code. If any set changes, investigate each delta rather than accepting it.
      - Keep `stripLineComment` for `//`.
    - **(c) The `> 500` floor is documented, not magic.**
      - Replace the bare `500` with a named constant whose comment records:
        - the measured count at HEAD: 1571 statically-resolvable keys, per skillars-deferred-156's Completion Notes. Re-measure and record the real number;
        - that it is an order-of-magnitude "scan found something" guard, not a precise count;
        - what to do if a legitimate i18n consolidation trips it: lower the constant, with a note.
      - Pass a failure message to `expect` so a trip explains itself.
      - Do **not** add an upper bound. The ledger notes the lack of one, but an upper bound would make every new translated string a spec edit, which is worse than the problem.

## Tasks / Subtasks

- [ ] **Task 1: booking-schema reason codes, PLAYER/PARENT** (AC1, AC2)
  - [ ] 1.1 Add the `CHILD_BOOKING_LOCK_TIMEOUT` / `CHILD_BOOKING_CONTENDED` constants and the `BookingSchemaLockException` nested class (non-PLFE `RuntimeException`, `contended` flag).
  - [ ] 1.2 Wrap the two booking-schema calls (`:1411-1418`, including their `setStatementLockTimeout` lines) in the inner try. `CannotAcquireLockException` is caught before `PessimisticLockingFailureException`.
  - [ ] 1.3 Add the catch at the PLAYER call site (`:575`) and the PARENT call site (`:936`), with identical skip/alert/`FAILED` semantics and WARNs that name `booking.*`.
  - [ ] 1.4 Update the reason lists in `raiseErasureAlert`'s Javadoc and the `AdminQueueService.buildSummary` comment.
  - [ ] 1.5 `GdprErasureServiceTest` unit tests (deterministic, mocked):
    - PLAYER, `PessimisticLockingFailureException` from `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId` → `CHILD_BOOKING_LOCK_TIMEOUT`, `FAILED`, tombstone **not** saved (`verify(playerProfileRepository, never()).save(...)`);
    - PLAYER, `CannotAcquireLockException` from `sessionCompletionDataRepository.anonymizeByPlayerId` → `CHILD_BOOKING_CONTENDED`;
    - PARENT with one child, `PessimisticLockingFailureException` from the booking statement → `CHILD_BOOKING_LOCK_TIMEOUT`, `FAILED`;
    - **regression pin:** PLAYER, `PessimisticLockingFailureException` from a development-schema statement (e.g. `playerTimelineRepository.deleteByPlayerId`) → still `CHILD_DELETE_LOCK_TIMEOUT`.

    Use `stubParentErasurePreamble()` (`:655-667`) for PARENT. It is already used by the `erase_parentUser_*` tests (`:342-437`).
  - [ ] 1.6 `GdprErasureIT`: one real-lock IT mirroring `erase_selfRegisteredPlayer_downstreamDeleteStatementLockTimeout_boundedNotHanging_marksFailedWithDistinguishedAlert` (`:1795`).
    - Hold a raw `SELECT id FROM booking.bookings WHERE player_id = ? FOR UPDATE` on a booking row owned by `SELF_PLAYER_PROFILE_ID`. Use the existing `insertBookingForCompletionData` fixture. Use `setGdprEraseStatementLockTimeoutSecondsConfig(2)`.
    - Assert the alert reason is `CHILD_BOOKING_LOCK_TIMEOUT` and the request is `FAILED`.

    This proves the real JPQL bulk-UPDATE lock-timeout exception type maps through the new wrapper, not just a mock. Same class, same context, so it adds no Spring-context fork (see Testing).
- [ ] **Task 2: availability-block reason scrub** (AC3, AC4)
  - [ ] 2.1 `CoachAvailabilityBlockRepository.anonymizeReasonByCoachId` (plain bulk `UPDATE`, no version bump).
  - [ ] 2.2 Inject `CoachAvailabilityBlockRepository` into `GdprErasureService` (constructor). Add the `AVAILABILITY_BLOCK` enum value, the reason-code pair, the fourth group after `REPORT`, the switch case, and the comment/WARN text updates.
  - [ ] 2.3 Update `GdprErasureServiceTest.setUp()`'s constructor call and add the `@Mock`.
  - [ ] 2.4 `GdprErasureIT`: `erase_coachUser_scrubsOwnAvailabilityBlockReasonsButNotAnotherCoachs`.
    - Mirror `erase_coachUser_anonymizesOwnSessionCompletionRecapForThatCoachOnly` (`:829`): a second real `marketplace.coach_profiles` + `main.user` fixture for the unrelated coach.
    - Insert one block per coach with a non-null `reason`. The `INSERT INTO booking.coach_availability_blocks (id, coach_id, start_datetime, end_datetime, reason)` shape is in `AvailabilityResourceIT.java:320`. Obey `chk_block_end_after_start`, and use **one** `Instant` base for start/end (see the `chk_bkg_end_after_start` CI-flake precedent).
    - Wrap fixture writes in `transactionTemplate` (bare `jdbcTemplate` writes in an IT body never commit under this project's Hikari `auto-commit=false`).
    - Assert: own `reason` IS NULL **and** the row still exists with unchanged start/end; the other coach's `reason` is intact.
- [ ] **Task 3: frontend dead computed** (AC5)
  - [ ] 3.1 Delete `PlayerDevelopmentDashboardPage.vue:192` and the `:106` binding. Run `PlayerDevelopmentDashboardPageSpec.js` plus `npm run lint`.
- [ ] **Task 4: `.gitattributes`** (AC6)
  - [ ] 4.1 Create it and verify with `git ls-files --eol` + `git status`, per AC6.
- [ ] **Task 5: coach CONTENDED test coverage** (AC7). The four tests are listed in AC7. Task 2's AVAILABILITY_BLOCK tests may be written here.
- [ ] **Task 6: move `collectKeyPaths.js`** (AC8)
  - [ ] 6.1 `git mv`, then fix the two imports and rewrite the header.
  - [ ] 6.2 Run both i18n specs.
- [ ] **Task 7: comment fix** (AC9)
- [ ] **Task 8: i18n spec hardening** (AC11)
  - [ ] 8.1 Capture the pre-change counts/sets, apply (a)/(b)/(c), and re-run.
  - [ ] 8.2 Record the before/after numbers in Completion Notes.
- [ ] **Task 9: ledger + brainstorm-doc annotations** (AC10). Do this last, so the CLOSED markers describe what actually shipped.

## Dev Notes

### Why this story bundles unrelated things

This is the standing "do not create small stories" instruction for ledger mining. Each item alone is a <1h fix. Together they close the entire actionable remainder of the newest ledger section. They touch three surfaces:
- `GdprErasureService` and its tests (L1, L2, L5, L7);
- frontend i18n test tooling (L6, L8);
- two one-liners (L3, L4).

### GDPR erasure: the shape you are extending

- **Two classification mechanisms coexist; do not mix them.**
  - The PLAYER/PARENT branch classifies by **exception type at the call site**. Read `DeleteStatementLockTimeoutException`'s Javadoc (`:1566`) for why cause inspection cannot work: both failure modes share SQLSTATE 55P03.
  - The COACH branch classifies by **a group label tracked across one transaction** plus catch type (`:433-497`).

  AC1/AC2 extend the first mechanism, and AC3/AC4 extend the second. Do not port either onto the other.
- **`set_config('lock_timeout', ?, true)` is transaction-scoped**, not statement-scoped (`setStatementLockTimeout`'s Javadoc, `:1462`). The existing per-statement calls at `:1411`/`:1417` re-issue the same value. That is harmless, and they must stay so that the booking statements keep their full, unshrunk bound. See the comment at `:1400-1410` for why they are deliberately outside the cumulative budget.
- **`raiseErasureAlert` must stay outside every `requiresNewTemplate` boundary** (skillars-deferred-135 AC1, its Javadoc). All new catches are at the existing call sites, which already satisfy this.
- **Alert dedup is reason-blind per `requestId`** (`admin_alerts_unique_open_per_ref`, `:996-1007`). If a PARENT erasure skips two children for different reasons, only the first alert row persists. This is unchanged, so do not write tests expecting two alerts.
- **The coach block's null-label guard** (`:476-483`): `case null` is unavailable on Java 17. Keep the `group == null ? SCD : group` coalesce when you extend the switch.
- **Post-PR CI lesson from skillars-deferred-156:** `NativeModifyingVersionAuditTest` audits every `@Modifying @Query` against a `@Version` entity. `CoachAvailabilityBlock` is not `@Version`, so AC3's plain UPDATE passes. If you ever change it to target a versioned entity, write `x.version = x.version + 1` explicitly; the audit does not recognise Hibernate's `UPDATE VERSIONED`.

### Testing

- **No new Spring-context fork.** All new IT methods go into the existing `GdprErasureIT` class. Do not add `@MockitoBean`/`@MockitoSpyBean`/`@TestPropertySource`/`@DynamicPropertySource` to it.
  - CI's AC3 context-count gate (`.github/scripts/assert-context-count.sh`, ceiling 45, steady state 44) fails on a new fork.
  - It also flaked once on test-order eviction alone (PR #282: 48 with zero config change, 44 on re-run). If it fires with no config change, re-run before touching the ceiling.
- **Do not run `mvn verify` locally** (`docs/validation-strategy.md`, project convention). Run the targeted classes:
  - `mvn -o test -Dtest='GdprErasureServiceTest,NativeModifyingVersionAuditTest'`;
  - and, if Docker is available, `-Dit.test=GdprErasureIT` via failsafe.

  GitHub CI is the full-suite gate.
- Frontend: `npx vitest run src/i18n src/pages/player`, `npm run lint`, `npx prettier --check` on touched files. CI runs frontend unit tests in two timezones (UTC, America/New_York); nothing here is TZ-sensitive.

### Project Structure Notes

- Backend files touched:
  - `platform/admin/service/GdprErasureService.java`;
  - `platform/admin/service/AdminQueueService.java` (comment only);
  - `platform/booking/repo/CoachAvailabilityBlockRepository.java`.

  `admin` already depends on `booking.repo` (`BookingRepository`, `SessionCompletionDataRepository` are injected today), so no new module edge.
- Frontend files touched:
  - `src/pages/player/PlayerDevelopmentDashboardPage.vue`;
  - `src/i18n/__tests__/{localeKeyParitySpec,i18nTemplateKeyResolutionSpec}.js`;
  - `src/i18n/collectKeyPaths.js` → `test/vitest/collectKeyPaths.js`.
- New root file: `.gitattributes`.
- No Flyway migration. No new column, no index. The `coach_id` index exists.
- PR must carry the `frontend-tests` label (frontend logic and spec changes).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md`: section "Deferred from: code review of skillars-deferred-156-… (2026-10-20)"; L1–L8 above.
- `_bmad-output/implementation-artifacts/skillars-deferred-156-booking-notes-gdpr-coverage-pdf-font-encoding-player-privacy-and-hardening-sweep.md`: AC1 (booking erasure placement), AC6 (i18n spec), AC7 (coach group labels), Post-PR CI fix note.
- `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md:206-214` (prioritisation list).
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`: `:177-194` CHILD reasons; `:196-243` coach group constants/enum; `:433-497` coach scrub block; `:553-620` PLAYER call site; `:920-975` PARENT call site; `:1340-1460` `deletePlayerDevelopmentDataInDedicatedPool`; `:1537-1571` nested exceptions.
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java:130-162, 556-666`.
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java:829, 1795`.
- `src/frontend/vitest.config.mjs:44-55`; `src/frontend/package.json:10-11`.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-10-21: Created via `/bmad-create-story`, mined from `deferred-work.md`'s newest section (deferred-156 code review). Companion brainstorm doc re-verified exhausted. All citations re-verified against `master` @ `56803337`. One ledger nuance corrected (L3: the `playerName` prop is not fully inert, but the only consumer is unreachable on that route). Status backlog → ready-for-dev.
