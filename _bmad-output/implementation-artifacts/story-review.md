# Story Review — skillars-deferred-157

**Story key:** `skillars-deferred-157-booking-erasure-reason-codes-availability-block-scrub-and-test-tooling-hygiene`

**Audit date:** 2026-10-21

**Verification HEAD**
- The audit started at `b8c81fc5` ("docs(story): create skillars-deferred-157 …").
- HEAD moved during the audit to `5d89ce82` ("docs(ledger): record owner decision — non-Latin PDF glyph support out of scope").
- `git diff --stat b8c81fc5 5d89ce82` touches only three docs files: `deferred-work.md` (+1), the story file (2 lines) and `sprint-status.yaml`.
- No source file changed. Every code citation was therefore checked against source that is identical at both SHAs.
- The story's own claim, "`master` @ `56803337`", was **not** trusted. It is still accurate for source, because `56803337..5d89ce82` is docs-only.

**Method.** Four parallel read-only verification layers ran first:
- Layer 1: citations;
- Layer 2: ledger and precedent;
- Layer 3: mechanistic claims;
- Layer 4: corner cases.

Then every finding was re-read skeptically against the source in a separate pass. That pass is called Step 4b throughout this report.

---

## 1. Citation verification (Layer 1)

Bare `:NNN` citations below refer to `GdprErasureService.java` unless another file is named.

| Citation (as written in story) | Verdict | Evidence / corrected location |
|---|---|---|
| `:1411-1418` (two booking statements) | MATCH | 1411 `setStatementLockTimeout(lockTimeoutSeconds);`, 1412 `sessionCompletionDataRepository.anonymizeByPlayerId(playerId);`, 1417–1418 the same pair for `bookingRepository.anonymizeNotesAndPlayerRequestByPlayerId` |
| `:1361` "the same try" | **DRIFTED → `:1357`** | 1357 is `try {`. Line 1361 is `List<String> childBlobKeys = new ArrayList<>();`, which is inside the try. Re-read in Step 4b |
| `:1456-1457` | MATCH | `catch (PessimisticLockingFailureException e)` → `throw new DeleteStatementLockTimeoutException(e);` |
| `:1448-1455` | MATCH | `catch (CannotAcquireLockException e) { … throw e; }` |
| `:1448-1458` | MATCH | Both outer catches |
| `:575/:590` (PLAYER) | MATCH | 575 is the multi-catch; 590 is `? CHILD_DELETE_LOCK_TIMEOUT : CHILD_CONTENDED` |
| `:936/:945` (PARENT) | MATCH | Same shape |
| `:182-194` CHILD_* reasons | MINOR DRIFT → `:182-192` | 193–196 is `UNCLASSIFIED_FAILURE` |
| `:177-194` | MINOR DRIFT → `:177-192` | |
| `:1566` | MATCH | `DeleteStatementLockTimeoutException` (Javadoc 1543–1565) |
| `:575-598`, `:592-597` | MATCH | The WARN text says "development-data deletion" |
| `:936-957` | MATCH | |
| `:990-994` | MATCH | The reason list is DEADLINE_EXCEEDED, CHILD_VANISHED, CHILD_CONTENDED, CHILD_DELETE_LOCK_TIMEOUT. It is already incomplete; see §5 F3 |
| `:996-1007` | MATCH | Reason-blind dedup, `admin_alerts_unique_open_per_ref` |
| `:240` | MATCH | `private enum CoachRecapGroup { SCD, TIMELINE, REPORT }` |
| `:446-466` | MATCH | The lambda. REPORT is at 460–465 |
| `:484-488` | MATCH | A switch **expression** with no `default` |
| `:196-221`, `:196-243` | MINOR DRIFT → `:197-221`, `:197-243` | |
| `:489-493` | MATCH | "session-recap/report scrub failed" |
| `:408-431` | MATCH (runs to 432) | |
| `:433-497` coach block | ≈MATCH | The block really runs 434–501; `skipped.set(true)` is at 500 |
| `:476-483` null guard | MINOR DRIFT → `:474-484` | |
| `:379` | MATCH | `coachProfileRepository.save(cp);` |
| `:226-232` (L7 comment) | MATCH | "…neither can actually trip a lock_timeout/contention error on its own…" |
| `:1462`, `:1400-1410` | MATCH | |
| `:553-620`, `:920-975`, `:1340-1460`, `:1537-1571` | ≈MATCH | Each sits inside the real method span |
| `AdminQueueService.java:173-179` | MATCH (comment is 173–178) | |
| `CoachAvailabilityBlock.java:37-38` | MATCH | `@Column(name = "reason") private String reason;`. No `@Version`/`@Audited` |
| `V138__baseline_schema.sql:258` | MATCH | `reason character varying(500),`. `chk_block_end_after_start` at :260 |
| `V138:3214` | MATCH | `CREATE INDEX idx_availability_blocks_coach_id … (coach_id)` |
| `AvailabilityService.java:317-318` | MATCH | `block.setCoachId(lockedProfile.getId());` |
| `AvailabilityService` `createBlock` `:300-301` | **WRONG NAME** | The method is `addBlock` (`:299`). The line numbers are correct. Re-read in Step 4b: `grep` finds no `createBlock(` in `src/main` |
| `AvailabilityService` `deleteBlock` `:327-329` | MATCH | |
| `PlayerDevelopmentDashboardPage.vue:192/:106/:191` | MATCH | |
| `PerformanceReportsPanel.vue:75`, `:7` | MATCH | |
| `GenerateReportDialog.vue:6` | MATCH | en-US `generateTitle: 'Generate Report for {name}'` |
| `routes.js:283-287` | MATCH | `role: 'PLAYER'` |
| `GdprErasureServiceTest.java:579-652` | MATCH | Only `SCD_CONTENDED` among the `*_CONTENDED` codes is tested |
| `:607-619`, `:640-641` | OFF-BY-ONE → `:608-620`, `:641-642` | |
| `:579-587` | MATCH | `erase_coachUser_allThreeGroupsSucceed_completesWithNoAlert` |
| `:146-152` | MINOR DRIFT → `:146-154` | |
| `:655-667` | MATCH | `stubParentErasurePreamble` |
| `:342-437` | MINOR DRIFT → `:342-426` | |
| `:130-162`, `:556-666` | ≈MATCH | |
| `GdprErasureIT.java:829`, `:1795` | MATCH | `insertBookingForCompletionData` exists (:802/806). `setGdprEraseStatementLockTimeoutSecondsConfig` exists (:2162) |
| `AvailabilityResourceIT.java:320` "INSERT … (id, coach_id, start_datetime, end_datetime, reason)" | **DRIFTED (content)** | The real line is `INSERT INTO booking.coach_availability_blocks (id, coach_id, start_datetime, end_datetime)`, with **no `reason`**. No test in `src/test` inserts `reason`. Re-read in Step 4b |
| `localeKeyParitySpec.js:16`, `i18nTemplateKeyResolutionSpec.js:38` | MATCH | `import { collectKeyPaths } from '../collectKeyPaths.js'` |
| `i18nTemplateKeyResolutionSpec.js:45-155`, `:166-170`, `:245-251`, `:24-26`, `:275-283` | MATCH (±1) | |
| `vitest.config.mjs:44-55`, `:49-55` | MATCH | |
| `package.json:10` | MATCH | `eslint … "./src*/**/*.{js,cjs,mjs,vue}"` |
| brainstorm `:206-214`, `:213`, `:214` | MATCH | |
| `assert-context-count.sh` ceiling 45 | MATCH | `CEILING="${2:-45}"` (:144) |
| "2 .ttf, 4 .png, 1 .ico", "No `.gitattributes`" | MATCH | No other binary extensions are tracked |

## 2. Ledger & precedent attribution (Layer 2)

For each claim, Layer 2 checked the ledger, the in-code comments and `git log`, and opened the referenced story files where relevant.

| Claim | Verdict | Notes |
|---|---|---|
| deferred-156 code-review section is newest | CONFIRMED | `deferred-work.md:3410`, the last `##` heading |
| L1–L8 bullets exist with quoted wording | CONFIRMED | `:3416-3423`. L4 really says "`core.autocrlf` is unset". The story's correction to `input` is accurate (global `~/.gitconfig`) |
| "Unknown Player" `Decided 2026-10-20` | CONFIRMED | The story omits the parenthetical "(code review of skillars-deferred-156)", which is harmless |
| PDF-glyph bullet | CONFIRMED (and now DECIDED at `5d89ce82`) | The story was updated in the same commit |
| `[CLOSED by skillars-deferred-156 AC2 …]` precedent markers | CONFIRMED | |
| deferred-155 Decision 2(a) scrub-and-keep | CONFIRMED | deferred-155 story :192 and code comments at :245/326/396 |
| deferred-156 post-PR CI fix (`version = version + 1`) | CONFIRMED | `BookingRepository.java:325-341`, `NativeModifyingVersionAuditTest:55,165` |
| deferred-154 accepted access-JWT residual | CONFIRMED, as same **class** | The accepted item was specifically post-erasure *wrap-ups*. The story correctly calls this "same class" |
| deferred-135 AC1: `raiseErasureAlert` stays outside the boundary | CONFIRMED | `:434-435`, Javadoc `:1015` |
| "1571 statically-resolvable keys" | PARTIAL (terminology) | deferred-156's Completion Notes say "call sites", and the floor counts `literalCalls.length`, i.e. call sites |
| PR #282 flake (48 → 44), ceiling 45 | CONFIRMED | Layer 2 pulled the CI logs for run 39604201626, both attempts |
| `chk_bkg_end_after_start` CI-flake precedent | CONFIRMED | `ReviewSubmissionIT:401,452`, `ReviewUpdateIT:344`, `BookingCompletionServiceTest:86` |
| `docs/validation-strategy.md`: no local `mvn verify` | CONFIRMED | :20 |
| "Grepped: no frontend or docs file maps these codes" | CONFIRMED | |
| Brainstorm-doc mapping (12-1…12-5, deferred-155 key, created 2026-10-19) | CONFIRMED | The deferred-155 key matches `sprint-status.yaml:384` |
| "Only … lines 213–214 is stale" | **PARTIAL** | See §5 F6 |
| "Everything older still open … investigation-led: `VideoModerationEmailListener` `persisted == null`, `MailManager` circuit breaker" | **WRONG for both items** | See §5 F7 |

## 3. Mechanistic claims (Layer 3, re-read in Step 4b)

**M1. Exception-type mapping in AC1/AC2 (`CannotAcquireLockException` → contended, `PessimisticLockingFailureException` → lock_timeout). Verdict: CONFIRMED, not backwards.**
- Layer 3 checked the bundled jars with javap:
  - Hibernate 6.6.42 `PostgreSQLDialect` maps `40P01` → `LockAcquisitionException` and `55P03` → `PessimisticLockException`.
  - Spring-orm 6.2.19 `HibernateJpaDialect` maps `LockAcquisitionException` → `CannotAcquireLockException` and `PessimisticLockException` → plain `PessimisticLockingFailureException`.
- Existing code agrees:
  - `:1448-1457` rethrows `CannotAcquireLockException` raw;
  - the coach block computes `contended = e instanceof CannotAcquireLockException` (`:473`);
  - existing tests use `PessimisticLockingFailureException("lock_timeout")` → `*_LOCK_TIMEOUT` and `CannotAcquireLockException("deadlock")` → `*_CONTENDED`.
- The catch order the story requires is necessary, because `CannotAcquireLockException` is a subclass of `PessimisticLockingFailureException`.

**M2. A non-PLFE `RuntimeException` passes untouched through the outer catches and the `requiresNewTemplate` rollback. Verdict: CONFIRMED.**
- `:1341` `requiresNewTemplate.executeWithoutResult(status -> {`. The only catches in the lambda are the two PLFE types. There is no `finally` and no `catch (RuntimeException)`.
- `CumulativeLockBudgetExhaustedException` (`:1537`) already relies on exactly this path; its Javadoc at `:1531-1535` says "Uncaught by both catch clauses … propagates out … as-is".
- The wrapper `deletePlayerDevelopmentData` (`:1303-1316`) has only `finally { RoutingDataSourceContext.clear(); }`.

**M3. Transaction scope: a booking trip rolls back everything in the child's call. Verdict: CONFIRMED.**
- One REQUIRES_NEW transaction wraps:
  - the `player_profiles` lock;
  - dev groups 1–3;
  - the two booking statements;
  - the outbox enqueue (`:1431`);
  - the tombstone save + flush (`:1438-1447`).
- A `BookingSchemaLockException` therefore rolls back all of it, the dev-schema deletes and the tombstone included.
- This is desired: the PARENT re-drive filters on `developmentDataErasedAt == null`. It does affect what the alert *means*; see §5 F4.

**M4. Only two call sites exist. Verdict: CONFIRMED.**
- `deletePlayerDevelopmentDataInDedicatedPool` is called only by `deletePlayerDevelopmentData` (`:1312`).
- That method is called only at `:553` (PLAYER) and `:924` (PARENT).
- An uncaught new type at either call site would escape to `markFailed` as `UNCLASSIFIED_FAILURE` with a full-erasure rollback, so the story is right to require both catches.

**M5. `set_config('lock_timeout', ?, true)` is transaction-scoped. Verdict: CONFIRMED.**
- See `:1462` and `:410-421`.
- Unrelated stale text noticed: the method Javadoc at `:1247` still says "per-STATEMENT", and `:1276-1277` corrects it.

**M6. "Adding the enum value without the case is a compile error". Verdict: CONFIRMED.**
- `:484` is a switch *expression* (`String reason = switch (…) { case … -> … };`) with no `default`.
- A non-exhaustive enum switch expression does not compile on Java 17.

**M7. Lock ordering for the new availability-block UPDATE. Verdict: CONFIRMED.**
- `eraseTransactional` is `@Transactional(REQUIRES_NEW)` (`:338`).
- The only query between `:379` and the inner block is a possible `configRepository.findAll()`. Hibernate AUTO flush only fires on overlapping query spaces, so `coach_profiles` is not flushed early.
- `coach_availability_blocks` has **no FK** at all (V138; the FK at :4399 belongs to `coach_availability_windows`), so the UPDATE takes no `KEY SHARE` lock on `coach_profiles`.
- `addBlock`/`deleteBlock` lock `coach_profiles` first, then touch blocks. No cycle is possible, and no REQUIRES_NEW self-deadlock either, since the outer transaction never writes `coach_availability_blocks`.

**M8. `CoachAvailabilityBlock` has no `@Version`/`@Audited`, and there are no `booking.*_aud` tables. Verdict: CONFIRMED.**
- The JPQL field names `coachId`/`reason` are valid.
- `CoachAvailabilityBlockRepository` exists and has no `@Modifying` imports yet.

**M9. Alert dedup is reason-blind per `requestId`. Verdict: CONFIRMED.** `insertErasureAlertIfAbsent` (`:1088`), index at V138 `:3166`.

**M10. `AdminQueueService.buildSummary` prefixes `reason` verbatim. Verdict: CONFIRMED.**
- `:179-182`: `prefix = reason != null ? reason + ": " : ""`.
- `admin_alerts.reason` is `varchar(64)`. The longest new code, `COACH_AVAILABILITY_BLOCK_LOCK_TIMEOUT`, is 37 characters.

**M11. `new GdprErasureService(` appears only in `GdprErasureServiceTest.setUp()`. Verdict: CONFIRMED.** The constructor is Lombok-generated in field order, so the new mock must go in the matching position.

**M12. AC8 claim that the moved file "falls out of ESLint coverage". Verdict: WRONG for CI; correct only for local `npm run lint`.** See §5 F2.

**M13. AC1 Task 1.6 IT will block on the booking UPDATE. Verdict: CONFIRMED.**
- `anonymizeNotesAndPlayerRequestByPlayerId` filters only on `b.playerId = :playerId`, so the locked row is always touched.
- `session_completion_data` is `@Table(schema="booking")`. Its UPDATE does not change its FK column, so it takes no lock on `bookings`.
- The fixture's start and end are an hour apart, so there is no CHECK-flake risk.

## 4. Corner cases / false assumptions / missed flows (survived Step 4b)

Ranked most important first. None blocks implementation. All are fixable by editing the story text before `/bmad-dev-story`.

### F1 (MED). AC11(b)'s whole-file `/* … */` blanking regex is a latent false-negative trap
- **Evidence (re-read):** `accept="video/*"` appears at `DrillDetailPanel.vue:128`, `DrillDetailPanel.vue:299` and `VideoManagementPage.vue:38`. Both files currently contain **zero** `*/` (verified with `grep -c`).
- **Why it is safe today:** the proposed `/\/\*[\s\S]*?\*\//g` matches nothing there. Layer 4 simulated the transform over the real tree: literalCalls stay at 1571, there are 26 dynamic sites, and no deltas. The story's before/after check will pass.
- **Why it will break:** the first `/* */` comment anyone adds to either file's `<script>`/`<style>` will start a "comment" at `video/*"`. That would blank every `t(`/`$t(` call in between, about 30 in `DrillDetailPanel`, and the spec would not fail. The spec would silently stop guarding those keys.
- **Suggested AC11(b) amendment:**
  - in `.vue` files, blank `/* */` only inside `<script>`/`<style>` and `<!-- -->` only inside `<template>`;
  - or require `/*` to be preceded by whitespace or a line start;
  - add a regression `it(...)` with an inline fixture string containing `accept="video/*"` … `/* c */`.

### F2 (MED). AC8's "accepted, disclosed tradeoff" about ESLint coverage is factually wrong
- **Evidence (re-read):** `.github/actions/frontend-quality/action.yml:52` runs `run: npx eslint .`, not `npm run lint`. Quasar's ignore list covers only `dist/*`, `src-capacitor/*`, `src-cordova/*`, `.quasar/*` and compiled config, so `test/vitest/*.js` **is** linted in CI.
- **What the story gets wrong:**
  - It tells the dev to record a coverage loss that does not exist in CI.
  - It also cites the Prettier glob as `**/*`. The real one is `"**/*.{js,vue,scss,json}" --ignore-path ../../.gitignore` (`:36`). It still covers the moved file.
- **Fix:** reword the Completion-Notes instruction. Only the local `npm run lint` glob misses `test/`; CI's `eslint .` still lints it.

### F3 (LOW). AC2/AC4 reason-code prose lists are already stale, and the story only appends to them
- **Evidence:**
  - `AdminQueueService.java:173-178` lists only DEADLINE_EXCEEDED, CHILD_VANISHED, CHILD_CONTENDED, CHILD_DELETE_LOCK_TIMEOUT and UNCLASSIFIED_FAILURE.
  - `raiseErasureAlert`'s Javadoc (`:989-994`) lists four codes.
  - Both omit `CHILD_CUMULATIVE_LOCK_BUDGET_EXCEEDED` and all six `COACH_RECAP_*`.
- **Other places with the same narrow list of skip causes:**
  - the `skipped` comment at `:533` ("true only for CHILD_CONTENDED/CHILD_DELETE_LOCK_TIMEOUT skips");
  - the terminal-status comment at `:639-640`;
  - `eraseParentChildren`'s `@return` Javadoc.
- **Fix:** make AC2 say "make each list complete", including the AC4 `COACH_AVAILABILITY_BLOCK_*` codes, rather than "add the two new codes".

### F4 (LOW). The new booking reason under-describes what survived
- **Evidence:** M3. A booking-statement trip rolls back the whole child call, including the already-run dev-schema deletes and the tombstone.
- **Why it matters:** an operator reading `CHILD_BOOKING_LOCK_TIMEOUT` could infer "only booking data survives", when all of the child's development data also survives.
- **Fix:** AC2's WARN text should say the failing statement was `booking.*` **and** that the child's whole development-data deletion was rolled back. The constant's comment should say the same.

### F5 (LOW). AC6's verification step expects output git never prints
- **Evidence (re-run in Step 4b, with a scratch `core.attributesFile` and the repo untouched):** `git ls-files --eol` shows `attr/-text` for the TTFs. It never shows `attr/-text -diff -merge` or `attr/binary`, because the attr column reports only text/eol attributes.
- **Fix:** expect `attr/-text`. Confirm the full macro with `git check-attr binary text diff merge -- src/main/resources/fonts/NotoSans-Bold.ttf`, which should print `binary: set`, `text: unset`, `diff: unset` and `merge: unset`.

### F6 (LOW). AC10 misses other stale status lines in the brainstorm doc
- **Evidence (re-read):** besides :213–214, these still describe the items as open:
  - `:202` "Deferred / Parked: Player/Parent-Initiated Flag";
  - `:252` "Fast-Follow: Recap -> Report Generation" and its "Next steps";
  - `:255` "Deferred: Player/Parent-Initiated Flag" and its "Next steps";
  - `:273` "4. Revisit the deferred player/parent-initiated flag…".
- Priorities 1–4 in the same Action Planning section carry ✅ Covered notes; these two headings do not.
- **Why it matters:** the story's stated goal for AC10 is that "future mining passes skip these items". Leaving these lines would re-surface the items.

### F7 (LOW). "Explicitly NOT in this story" lists two closed items as still-open
- **`VideoModerationEmailListener` `persisted == null`:** `deferred-work.md:146-155` says "…filed as closed on that basis" (deferred-113, Option B, throws `IllegalStateException`).
- **`MailManager` circuit breaker:** deleted as "factually wrong" (`:56-77`), and re-closed at `:162-166` ("Not touched … closed it again").
- **Likely source of the error:** the stale 2026-09-14 audit text at `:2031-2033` ("confirmed still open").
- **Scope impact:** none, because both are on the exclusion list. The characterization should still be corrected so that this list doesn't spread the error to the next mining pass.

### F8 (LOW). Task 2.4 cites a fixture shape that doesn't exist
- **Evidence:** `AvailabilityResourceIT.java:320` inserts `(id, coach_id, start_datetime, end_datetime)` with no `reason`. Nothing in `src/test` inserts `reason`.
- **Implementation impact:** small. Add the column, and note there is no FK on `coach_id`, so the fixture `coach_id` only needs to match the real coach profile for the scrub. The story's claim is still inaccurate.

### F9 (LOW). AC4's "three groups" wording list is incomplete
Besides `:196-221` and the WARN at `:489-493`, these also need updating:
- `:438-440` "identifies which of the three groups below … All three groups still run";
- the `eraseTransactional` Javadoc at `:318-321` ("three copies of the coach's own authored narrative"). This one describes narrative copies, so it may warrant a separate clause rather than a count change.

### F10 (LOW). AC4's known-residual description should bound the window
- **Evidence (re-read):** `JWTAuthorizationFilter.java` Javadoc :78-86 and code :205-207. Every `DB_REFRESH_TOKEN_INTERVAL` (5 min), `daoAuthProvider.authorize` re-loads the account and rejects a locked one. Erasure sets `locked = true`.
- **What to record:** the post-erasure block-creation window is therefore at most about 5 minutes. Put that in Completion Notes rather than an open-ended "still-valid JWT" lifetime.

### F11 (LOW, cosmetic). Citation drift
- `:1361` should be `:1357`, since `:1361` is inside the try, not the try.
- `createBlock` should be `addBlock`.
- Test `:607-619`/`:640-641` are one line early.
- See also the other ±1–4 minor drifts in §1.

### F12 (LOW). AC11(a)'s rationale for the `at`-uniqueness check is wrong
- **Evidence (re-read):** the inverse check at `:279-281` maps *every* entry's `at` against `detectedSites`. If a duplicated `at` is stale, both copies are reported. If it is live, both match. Nothing is masked.
- **Fix:** the assertion is harmless and still worth keeping as hygiene. Drop the "silently mask a stale one" justification so the dev doesn't write a misleading comment.

### F13 (LOW). AC11(c) terminology
"1571 statically-resolvable keys" should say **call sites**. deferred-156's Completion Notes :237 say "call sites", and `literalCalls.length` counts calls. Use the right noun in the new constant's comment.

## 5. What did not survive re-verification (Step 4b)

| Raised by | Claim | Why it was dropped or downgraded |
|---|---|---|
| Layer 4 (LOW-5a) | "PDF-glyph bullet now DECIDED; the story's exclusion text is stale" | **Killed.** Commit `5d89ce82` updated the story's own exclusion bullet and AC10 in the same commit, so the story is already consistent with the ledger at current HEAD |
| Layer 3 (prompted check A) | "AC1's `CannotAcquireLockException` → contended mapping may be backwards" | **Killed.** Confirmed correct against the Hibernate/Spring jars, the existing code (`:1448-1457`, `:473`) and the existing tests (M1) |
| Layer 1 | "GdprErasureIT already has `@MockitoBean`/`@MockitoSpyBean` (:101/:108)" as a contradiction of the Testing note | **Killed as a finding.** The story says not to *add* them, which is correct. Existing ones are already part of the context-cache key |
| Layer 4 (LOW-8) | "Add `AND b.reason IS NOT NULL` to the AC3 UPDATE" | **Downgraded to an optional note**, not a defect. It would reduce row locks on already-null rows, but the story's UPDATE is correct and idempotent as written |
| Layer 2 | "'Steady state 44' unverifiable" | **Killed.** Layer 2's own CI-log pull showed attempt 2 of PR #282 at 44, which matches the claim |
| Layer 2 | "Ledger section says 'Eight items' but holds 10 bullets" | **Out of scope.** This is a ledger-internal count inconsistency, not a claim this story makes |
| Layer 3 | "Javadoc `:1247` still says lock_timeout is per-STATEMENT" | **Out of scope.** Pre-existing stale text this story doesn't cite. Optional for the dev to fix while editing nearby |

## 6. Recommendation

**Verdict:** ready to implement after a short text amendment. No finding invalidates the design.

**What holds, with high confidence because it was independently re-checked:**
- the AC1/AC2 exception design (non-PLFE wrapper, catch order, two call sites, rollback scope);
- the AC3/AC4 availability-block scrub (no `@Version`/`@Audited`/FK, lock ordering, compile-checked enum switch, placement after REPORT);
- the AC5 dead-computed deletion (PLAYER-only route, generate button unreachable);
- the Task 1.6 IT blocking assumption.

**Amend before dev, medium confidence that each matters:**
- **F1:** constrain AC11(b)'s comment blanking. Today's before/after check *will* pass, which is exactly why this would ship unnoticed.
- **F2:** correct AC8's ESLint/Prettier claims.

**Low-cost text fixes, high confidence each is factually right but low impact:** F3–F13.

**Not independently re-verified by me in Step 4b:**
- the javap jar-mapping inspection (Layer 3). I re-checked only the in-code comment and tests that agree with it;
- the PR #282 CI-log contents (Layer 2);
- Layer 4's full-tree simulation of the AC11(b) regex. I re-checked only that the two `video/*` files contain no `*/` today.
