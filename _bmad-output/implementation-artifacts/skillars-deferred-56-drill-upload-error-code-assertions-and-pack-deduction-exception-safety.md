# Story Deferred-56: Drill-Upload Error-Code Assertions & Pack-Deduction Exception Safety

Status: done

## Story

As an engineer operating this platform,
I want three `DrillUploadResourceIT` tests whose names already promise an error-code check to actually
assert that code in the response body, and `PaymentLifecycleService.handlePackBasedBooking` to treat any
`RuntimeException` from `packSessionService.deductSession(...)` the same way it already treats
`PaymentGatewayException` (log it, record a payment failure, return cleanly) instead of only the latter,
so that a future regression in either spot is actually caught by its existing safety net rather than
passing green by coincidence, and a future change to `deductSession`'s throw contract can no longer crash
an `AFTER_COMMIT` event listener uncaught (though, per story review, its recovery write is not guaranteed to
survive every scenario that reaches the widened catch — see AC2's "Known residual risk" note).

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1716 lines at the time this story was created)
was re-mined end to end. The most recently active tail (post-`skillars-deferred-49`, the section every one
of `skillars-deferred-50` through `-55`'s own creation notes already re-verified) yielded exactly two
candidates once every item needing a design/product decision, matching a standing accepted convention, or
already closed-but-unannotated was excluded — the same conclusion `skillars-deferred-55`'s own creation
notes reached for this same tail:

- `1591`/`1598`/`1599` (video-quota dedup-rule design question, async-recalculation race widening, `V98`
  unchunked backfill) and `1603` (`DisputeService`'s `FROZEN`-status filter gap) all still explicitly need a
  coordinated design/product decision spanning multiple services.
- `1611` (no frontend test coverage for `playerStore.js`'s caching logic) is the standing accepted
  frontend-test-infrastructure gap this ledger has left alone across dozens of prior stories.
- `1630`/`1634`/`1635`/`1636`/`1640` (booking-module validation-duplication/locking-strategy/DST items) are
  all explicitly out-of-scope-by-their-own-text — the same class of decision `skillars-deferred-49`/`-50`/`-53`
  already declined for sibling items.
- `1662` (whether a release-call failure should retry) is its own text's "real, undecided design question,
  not a mechanical fix."
- `1696`/`1697` (currency config format validation; `platform_config`'s hand-assigned-PK schema) both match
  an already-established project convention this ledger has repeatedly declined to redesign ad hoc.

That left two items in the tail, both genuinely bounded and decision-light:

- **D1 (this story's AC2)** — sourced from `## Deferred from: code review of
  skillars-deferred-54-pack-deduction-failure-path-unit-coverage` (line 1702):
  `PaymentLifecycleService.handlePackBasedBooking` only catches `PaymentGatewayException` from
  `packSessionService.deductSession(purchaseId)`; any other `RuntimeException` (e.g. a repository-layer
  `DataAccessException` from `deductSession`'s own `sessionPackPurchaseRepository.save(purchase)`) would
  propagate uncaught out of the `AFTER_COMMIT` `REQUIRES_NEW` `onBookingAccepted` listener. **This exact
  item was already investigated once and left un-annotated by `skillars-deferred-55`'s own story creation**
  (2026-08-22), on the grounds that neither of `deductSession`'s two current throw sites
  (`payment.packNotFound`, `payment.packExhausted`) is anything other than `PaymentGatewayException`, so the
  gap is not reachable today. That fact hasn't changed — re-verified live at `PackSessionService.java:51-61`,
  both throw sites are still `PaymentGatewayException`. This story picks it up anyway, on different grounds
  than reachability: it is a small, mechanical, one-line catch-clause widening that mirrors the exact
  defensive-hardening shape `skillars-deferred-55` AC4 already shipped one file over in the same payment
  module (`StripePaymentGateway.chargeAndCapture`'s config-lookup `try`/`catch` — "fail predictably instead
  of throwing an uncaught exception"), and the tail otherwise held only a single other item (below), too
  thin alone to justify a story per this session's explicit instruction not to ship another single-item
  story.

Since the tail held only one truly fresh item, this story's creation additionally re-mined the *oldest*,
least-recently-touched sections of the ledger (2026-06-11 through 2026-06-25 — code reviews of the original
Epic 1–7 stories, none re-verified by any of the 55 prior `skillars-deferred-*` passes) rather than force a
weak pick from an already-thin tail. Most of that territory turned out to be either explicitly
spec-intentional/accepted-by-design (dozens of items) or already fixed by later, unrelated work and simply
never tagged (five such stale items found and tagged during this pass — see "Ledger hygiene" below). One
genuine, still-open, previously-untouched item survived:

- **D2 (this story's AC1)** — sourced from `## Deferred from: code review of
  skillars-4-3-custom-drill-uploads (2026-06-17)`, item W7 (line 762): IT test
  `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` asserts only the HTTP status
  (`HttpClientErrorException.Forbidden`), not the `errorKey` its own name promises to prove. Re-verified
  live: `DrillUploadResourceIT.java:155-172` is unchanged since W7 was filed. **Two sibling tests in the
  same file carry the identical, previously-untracked gap**, found while reading the file to fix W7:
  `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode` (`:270-287`) and
  `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode` (`:289-306`) — both method names
  promise a "WithConstraintViolatedCode" check and both assert only
  `HttpClientErrorException.UnprocessableEntity`, nothing about the response body. All three are bundled
  into one AC, following the same "found while verifying, filed into the same AC" precedent
  `skillars-deferred-53` set for its own independently-found sibling test.

**Ledger hygiene — five stale items found and tagged during this re-mine, none picked up as an AC** (each
already fixed by separate, unrelated, previously-unannotated work):
- Line 863 (Group-B-era `skillars-1-5` review): `refresh_alreadyUsedToken` test coverage gap — the entire
  test it names is commented out (`AuthResourceIT.java:281-323`, behind a "single JWT refresh mechanism"
  TODO). Nothing to strengthen.
- Line 964 (`skillars-uat-3` backup review): `DOMAIN` unused in `restore-from-snapshot.sh` — that script no
  longer exists, deleted by `skillars-uat-6` AC5-AC7 (this file's own D1 closure note under
  `## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention` already records the
  deletion; this specific bullet just hadn't been re-tagged).
- Line 1141 (`skillars-deferred-2` review): `BookingExpiredEvent`/`BookingReminderEvent`/
  `BookingConfirmedEvent` positional-constructor risk — all three classes already have a `Builder` (private
  constructor + fluent setters), confirmed no positional constructor exists to invoke and no test file
  invokes one.
- Line 1687 (`skillars-deferred-52` review): possible additional `.stream().distinct()`-on-entity issues in
  other `GdprExportService` builder methods — `skillars-deferred-55` already narrowed this to "only one
  `.distinct()` call exists in that file." This pass widened the check to every `.distinct()` call site in
  `src/main/java` (10 total, across 9 services): every one operates on a `String`/`UUID`/`Instant` already
  extracted via `.map(...)`, never on a raw JPA entity. Closed out definitively.
- `skillars-8-2` D1/D2 (deleted-player `UserNotFoundException` crashing `getConversations()`, tracked only
  as embedded prose inside the `## Last audit: 2026-08-05 (skillars-deferred-15 story creation)` block, not
  as its own bulleted item — not re-tagged, since that audit-note prose format doesn't carry the
  `[PICKED UP]`/`[CLOSED]` convention, but recorded here for the next pass): `getConversations`' `PARENT`
  branch (`MessagingService.java:106-119`) now calls `agePolicyService.findMessagingPolicy(...)` (the
  `Optional`-returning variant) with an explicit `log.error` + graceful-exclude fallback, not the throwing
  `getMessagingPolicy(...)` the item named — already fixed, unrelated to this story's scope.

Two items considered but explicitly **not** picked up, beyond the tail's own already-excluded set:
`## Deferred from: code review of skillars-10-1 patches (2026-06-30)` D1/D2 (`findBeforePivot`/
`findAfterPivot` null-pivot and soft-delete-context-window gaps, lines 604-605) — D1 is DB-`NOT NULL`-guarded
in production (test-fixture-only risk), D2 is explicitly "intentional spec asymmetry between views," neither
a live bug.

## Acceptance Criteria

1. **AC1 — Three `DrillUploadResourceIT` tests whose names promise an error-code check assert the actual
   `errorKey` in the response body, not just the HTTP status.**
   - File: `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java`.
   - Mirror the pattern the sibling test `initiateUpload_platformDrill_returns403` (`:174-199`) already
     uses: chain `.isInstanceOf(...)` with a `.satisfies(e -> { HttpClientErrorException ex = (...) e; ... })`
     block asserting on `ex.getResponseBodyAsString()`. No new imports are needed —
     `HttpClientErrorException`, `assertThat`, and `assertThatThrownBy` are already imported in this file.
   - **1a. `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` (`:155-172`).** **Fixture bug found
     during story review, must be fixed first**: this test authenticates as `SCOUT_EMAIL` but posts to
     `coachDrillId`, which `setUp()` creates as `insertDrill(coachDrillId, "Coach Test Drill", "COACH",
     instrCoachId, "ACTIVE")` (`:87-88`) — owned by `instrCoachId`, not `scoutCoachId`.
     `DrillUploadService.initiateUpload` checks drill ownership *before* the feature gate
     (`DrillUploadService.java:61-65`), so as currently wired this test would throw
     `OperationNotAllowedException(DRILL_NOT_OWNED)`, not `FeatureGatedException`, before
     `checkDrillUploadGate` is ever reached — both map to HTTP 403, which is why today's status-only
     assertion passes coincidentally, but asserting `errorKey = "security.featureGated"` against this
     fixture would fail with `DRILL_NOT_OWNED` in the body instead. Add a scout-owned drill fixture first:
     in `setUp()`, alongside the existing `coachDrillId`/`otherCoachDrillId` inserts:
     ```java
     scoutCoachDrillId = UUID.randomUUID();
     insertDrill(scoutCoachDrillId, "Scout Test Drill", "COACH", scoutCoachId, "ACTIVE");
     ```
     (new `private UUID scoutCoachDrillId;` field alongside the existing `coachDrillId`/`otherCoachDrillId`
     fields), and extend `tearDown()`'s existing cleanup statements (`:107-116`) to also include
     `scoutCoachDrillId` in each `DELETE ... WHERE drill_id IN (...)`/`WHERE ... coach_id IN (...)` list
     alongside `coachDrillId`/`otherCoachDrillId`. Current test shape:
     ```java
     assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
         baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
         HttpMethod.POST, payload, authenticatedHeaders(cookies), Map.class
     )).isInstanceOf(HttpClientErrorException.Forbidden.class);
     ```
     Change to (note: points at the new `scoutCoachDrillId`, not `coachDrillId`):
     ```java
     assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
         baseUrl() + DRILLS_BASE + "/" + scoutCoachDrillId + "/video/initiate",
         HttpMethod.POST, payload, authenticatedHeaders(cookies), Map.class
     ))
         .isInstanceOf(HttpClientErrorException.Forbidden.class)
         .satisfies(e -> {
             HttpClientErrorException ex = (HttpClientErrorException) e;
             assertThat(ex.getResponseBodyAsString())
                 .contains("\"errorKey\":\"security.featureGated\"");
         });
     ```
     The `security.featureGated` errorKey is confirmed at `SessionApiAdvice`-adjacent mapping —
     specifically `ApiAdvice.java:326-331`'s `@ExceptionHandler(FeatureGatedException.class)` handler, which
     `DrillUploadService.checkDrillUploadGate` reaches via `throw new FeatureGatedException("drill_video_upload",
     resolveMinUploadTier())` (`DrillUploadService.java:144`) when a `SCOUT`-tier coach hits this endpoint —
     reachable now that the fixture's drill is actually owned by the authenticated scout coach, so the
     ownership check at `DrillUploadService.java:61-65` passes and `checkDrillUploadGate` is actually
     exercised.
   - **1b. `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode` (`:270-287`).** Current
     shape asserts only `.isInstanceOf(HttpClientErrorException.UnprocessableEntity.class)`. Add the same
     `.satisfies(...)` shape, asserting `"\"errorKey\":\"video.constraintViolated\""`. Trace: an oversized
     `fileSizeBytes` fails `VideoTypeConstraints.validate` (`VideoTypeConstraints.java:32-35`), which throws
     `VideoValidationException`; `DrillUploadService.java:70-74` catches that and re-throws
     `DrillConstraintViolationException("video", e.getMessage())`; `SessionApiAdvice.java:18-24` maps it to
     `422` with `ErrorDto("video.constraintViolated", ...)` — this is the exact same handler and error code
     `initiateUpload_readyVideoAlreadyLinked_returns403WithVideoAlreadyLinkedKey` and the other already-hardened
     tests in this file already exercise for sibling codes, just not this one yet.
   - **1c. `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode` (`:289-306`).** Identical
     gap and identical fix — same `video.constraintViolated` errorKey, same `VideoTypeConstraints.validate`
     duration branch (`VideoTypeConstraints.java:40-43`) feeding the same `DrillConstraintViolationException`
     → `SessionApiAdvice` path.
   - **Why this closes the gap**: each of these three tests' own name already promises to prove a specific
     error code reaches the client — today all three would still pass if the production code returned the
     right HTTP status with the *wrong* (or no) `errorKey`, silently breaking whatever frontend branch keys
     off that code. `initiateUpload_platformDrill_returns403` and its neighbours in the same file already
     prove this exact assertion shape works against live endpoints; this AC only extends it to the three
     tests that were left behind.
   - **Test coverage**: this AC's change *is* the test hardening — no new test method needed. Run
     `mvn -o integration-test -Dit.test=DrillUploadResourceIT` (Failsafe — this is an `*IT` class, not a
     `*Test` class; see Dev Notes' IT-execution gotcha) and confirm all tests remain green.

2. **AC2 — `PaymentLifecycleService.handlePackBasedBooking` catches any `RuntimeException` from
   `packSessionService.deductSession(...)`, not only `PaymentGatewayException`.**
   - File: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:162-175`.
   - Current shape:
     ```java
     private void handlePackBasedBooking(UUID bookingId, UUID purchaseId, Long parentId,
                                         String parentEmail, String coachDisplayName,
                                         Instant requestedStartTime, String canonicalTimezone) {
         try {
             packSessionService.deductSession(purchaseId);
         } catch (PaymentGatewayException e) {
             log.error("Pack session deduction failed: bookingId={} purchaseId={}", bookingId, purchaseId);
             persistenceService.persistPaymentFailure(bookingId, BigDecimal.ZERO,
                 parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
             return;
         }
         persistenceService.persistPaymentSuccess(bookingId, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
             parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
     }
     ```
   - Widen the catch clause from `PaymentGatewayException` to `RuntimeException`, and add the exception
     itself to the log call (the current log line drops `e` entirely — neither the message nor the stack
     trace is recorded today, for either the existing or the widened case):
     ```java
     private void handlePackBasedBooking(UUID bookingId, UUID purchaseId, Long parentId,
                                         String parentEmail, String coachDisplayName,
                                         Instant requestedStartTime, String canonicalTimezone) {
         try {
             packSessionService.deductSession(purchaseId);
         } catch (RuntimeException e) {
             log.error("Pack session deduction failed: bookingId={} purchaseId={} error={}",
                 bookingId, purchaseId, e.getMessage(), e);
             persistenceService.persistPaymentFailure(bookingId, BigDecimal.ZERO,
                 parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
             return;
         }
         persistenceService.persistPaymentSuccess(bookingId, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
             parentId, parentEmail, coachDisplayName, requestedStartTime, canonicalTimezone);
     }
     ```
     No new imports needed — `PaymentGatewayException`'s import can be left in place if still referenced
     elsewhere in the file (it is not used elsewhere in this method; check whether it's still needed by
     other methods in `PaymentLifecycleService.java` before removing the import — do not remove it
     speculatively if unsure, an unused-import warning is harmless and safer than a wrong removal).
   - **Why `RuntimeException`, not a narrower type**: `deductSession` is `@Transactional` and calls
     `sessionPackPurchaseRepository.findByIdForUpdate(...)` and `.save(purchase)`
     (`PackSessionService.java:51-61`) — both Spring Data calls that can throw an unchecked
     `org.springframework.dao.DataAccessException` subtype on a real persistence failure, entirely separate
     from the two `PaymentGatewayException` throw sites this method already handles. `RuntimeException` is
     the narrowest common supertype that covers both today's known throw sites and any future unchecked
     throw from this call, without catching `Error` (which should still propagate). This mirrors the exact
     shape `skillars-deferred-55` AC4 shipped for `StripePaymentGateway.chargeAndCapture`'s config-lookup
     calls one file over in this same package — "fail predictably... instead of throwing an uncaught
     exception," applied to this method's own already-established graceful-failure path rather than
     inventing a new one.
   - **What this does NOT change**: the behavior for `PaymentGatewayException` itself is identical — same
     branch, same `persistPaymentFailure` call, same return. This AC only adds coverage for the
     previously-uncaught case; it does not alter any currently-tested behavior.
   - **Known residual risk (story review, not fixed by this AC — documented per this project's convention
     of surfacing rather than silently shipping unverified defensive code)**: `deductSession` is
     `@Transactional` and joins the same physical transaction as `onBookingAccepted`'s enclosing
     `@Transactional(propagation = Propagation.REQUIRES_NEW)` `AFTER_COMMIT` listener
     (`PaymentLifecycleService.java:138-139`), rather than starting its own. Under Spring's default rollback
     rule, an unchecked exception propagating out of a *participating* (non-new) `@Transactional` method
     marks that shared transaction rollback-only at the AOP boundary, before the exception ever reaches this
     method's `catch` block. `persistenceService.persistPaymentFailure(...)` — also `@Transactional`,
     default `REQUIRED` — joins that same now-rollback-only transaction; its writes will likely still
     execute against Postgres, but when the outer `REQUIRES_NEW` transaction reaches its own commit point,
     Spring rolls the whole thing back instead, silently discarding the failure record this catch branch
     exists to guarantee. This mechanism is **not introduced by this AC** — it already applies identically
     to the pre-existing `PaymentGatewayException` branch — but has caused no observed harm there only
     because both of `deductSession`'s current `PaymentGatewayException` throw sites fire before any DB
     write. AC2 is the first change built around a scenario (`.save(purchase)` failing) where a write may
     already be in flight when the exception hits, making this mechanism consequential for the first time.
     The one new test this AC adds uses a `@Mock`-ed `persistenceService`
     (`CreditRoutingTest.java:49`), so it can only prove the right method is *called* with the right
     arguments — it cannot observe whether that call's write survives a real Spring transaction commit, and
     no test at any level in this story closes that gap. A real fix (a `*IT`-level test forcing a genuine
     `DataAccessException` inside the actual `AFTER_COMMIT`/`REQUIRES_NEW` flow, and/or a redesign of how
     failure recording survives a rollback-only transaction) is out of scope for this small, mechanical
     hardening story and is left as a fresh `deferred-work.md` item once this AC ships (see AC3).
   - **Test coverage**: `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java`.
     Add one new test directly below the existing `packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal`
     (`:164-179`), mirroring it exactly with the exception type swapped:
     ```java
     @Test
     void packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal() {
         UUID packId = UUID.randomUUID();
         doThrow(new IllegalStateException("simulated repository failure"))
             .when(packSessionService).deductSession(packId);

         service.onBookingAccepted(event(packId));

         verify(creditWalletService, never()).getBalance(any());
         verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
         verify(persistenceService, never()).persistPaymentSuccess(
             any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
         verify(persistenceService).persistPaymentFailure(
             eq(BOOKING_ID), eq(BigDecimal.ZERO),
             eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
     }
     ```
     `IllegalStateException` is used here only as a convenient, already-imported `RuntimeException` stand-in
     for "some non-`PaymentGatewayException` failure" — it is not claiming `deductSession` actually throws
     `IllegalStateException` in production; the point is proving the catch clause's *type*, not its specific
     production trigger (which remains, as the ledger item itself says, not reachable via any current throw
     site). No new imports needed — `doThrow`, `UUID`, `Instant`, `BigDecimal` are all already imported in
     this file (used by the sibling test immediately above). Run `mvn -o test -Dtest=CreditRoutingTest` and
     confirm all tests green (existing tests plus this one new test).

3. **AC3 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for `deferred-49` through `-55`) is: at **story-creation** time, tag an item this story is about
   to fix as `` `[PICKED UP by skillars-deferred-56 ACn]` `` — appended after the item's existing
   text/citation, without rewriting the body to describe a fix that hasn't happened yet.
   `` `[CLOSED by ...]` `` is reserved for items **verified already fixed by separate, completed work**.
   Only flip a `PICKED UP` tag to `CLOSED` in the **implementation** commit, once the corresponding code
   change actually lands — never at story-creation time. This was already applied correctly at this story's
   creation:
   - `deferred-work.md` line 762 (the `initiateUpload_scoutCoach_...` error-code-assertion item) tagged
     `` `[PICKED UP by skillars-deferred-56 AC1 — two sibling tests ... bundled into the same AC.]` ``.
   - `deferred-work.md` lines 1702-1713 (the `handlePackBasedBooking` narrow-catch item) tagged
     `` `[PICKED UP by skillars-deferred-56 AC2 — ...]` ``.
   - Five stale items (lines 863, 964, 1141, 1687, and `skillars-8-2` D1/D2's embedded-prose mention) were
     also tagged/noted `` `[STALE — verified against current code by skillars-deferred-56 story creation,
     2026-08-22: ...]` `` during this same pass — see "Ledger hygiene" in "Why this story exists" above for
     what each one covers. These are **not** part of AC1/AC2's implementation scope; no code change
     corresponds to them, only ledger annotations, already applied.
   This AC's job during **implementation** is to flip the two `PICKED UP` tags to `CLOSED` once AC1/AC2
   actually land — one commit, matching the code:
   - Once AC1 ships: flip line 762's tag to `` `[CLOSED by skillars-deferred-56 AC1]` `` with a one-line
     closure note naming all three tests fixed.
   - Once AC2 ships: flip the line-~1702 tag to `` `[CLOSED by skillars-deferred-56 AC2]` `` the same way,
     and additionally file a **new** `deferred-work.md` item (not `[PICKED UP]`, just a fresh untagged
     bullet) capturing AC2's "Known residual risk" note verbatim in summary: the widened catch's
     `persistPaymentFailure` recovery write is not guaranteed to survive Spring's rollback-only marking on
     the shared `REQUIRES_NEW` transaction when the triggering exception fires after a DB write was already
     attempted inside `deductSession` — needs either a real `*IT`-level test or a design decision on how
     failure recording should survive a rollback-only transaction.
   - **If a partial implementation lands**, flip only the tag for the AC that actually shipped — leave the
     other at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [x] Task 1: `DrillUploadResourceIT` error-code assertions (AC: #1)
  - [x] 1.1 Add the `scoutCoachDrillId` fixture to `setUp()`/`tearDown()` and the `.satisfies(...)` block to
    `initiateUpload_scoutCoach_returns403WithFeatureGatedCode`, pointing it at `scoutCoachDrillId` instead
    of `coachDrillId`, per AC1a's snippet (story-review Finding 1: the original `coachDrillId` fixture is
    owned by `instrCoachId`, so the ownership check fires before the feature gate does).
  - [x] 1.2 Add the `.satisfies(...)` block to
    `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode`, per AC1b.
  - [x] 1.3 Add the `.satisfies(...)` block to `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode`,
    per AC1c.
  - [x] 1.4 Run `mvn -o integration-test -Dit.test=DrillUploadResourceIT` and confirm green.
- [x] Task 2: `PaymentLifecycleService` pack-deduction exception widening (AC: #2)
  - [x] 2.1 Widen `handlePackBasedBooking`'s catch clause from `PaymentGatewayException` to
    `RuntimeException`, and add `e` to the log call, per AC2's snippet.
  - [x] 2.2 Add `packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal`
    to `CreditRoutingTest.java`, per AC2's snippet.
  - [x] 2.3 Run `mvn -o test -Dtest=CreditRoutingTest` and confirm all tests green (existing plus the new one).
- [x] Task 3: Ledger hygiene (AC: #3) — flip the two `PICKED UP` tags applied at story creation to `CLOSED`
  once AC1/AC2 land, and file the new rollback-only residual-risk item once AC2 ships, per AC3.

### Review Findings

Code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) of the implementation diff, 2026-08-22.
Acceptance Auditor: 0 AC violations — all three ACs independently re-verified against live code (ownership
check ordering, errorKey mapping strings, `PaymentGatewayException` import still needed at two other call
sites, `deductSession`'s own throw sites untouched, exact file scope matches the story's declared File List).

- [x] [Review][Patch] **Resolved by user decision (2026-08-22), applied:** wrapped the `persistenceService.persistPaymentFailure(...)`
  call inside `handlePackBasedBooking`'s new `catch (RuntimeException e)` block in its own inner
  `try`/`catch (RuntimeException pfe)` that logs-and-swallows, so a secondary failure there can no longer crash
  the `AFTER_COMMIT` listener uncaught (failure mode 2 below). This fixes the loud/uncaught failure mode only —
  the silent rollback-only discard (failure mode 1) is explicitly accepted as a documented, unfixed residual
  risk per this same decision; no further code change for that half. Original finding, for context:
  AC2's own newly-filed `deferred-work.md` residual-risk item — that
  `handlePackBasedBooking`'s `RuntimeException` catch may not survive its own motivating scenario — was
  independently re-derived by both Blind Hunter and Edge Case Hunter from the diff alone, and elaborated with
  one additional concrete failure mode neither the story nor my own pre-implementation review named: if
  `persistenceService.persistPaymentFailure(...)` itself throws for *any* reason inside the new catch block
  (unrelated to the rollback-only mechanism — no `try`/`catch` wraps that call), the exception propagates
  straight out of `handlePackBasedBooking` uncaught, out of the `AFTER_COMMIT` listener — the exact outcome
  this AC's own title ("Exception Safety") claims to prevent (`PaymentLifecycleService.java:167-172`, flagged
  by Edge Case Hunter). Combined with the already-documented silent-discard mechanism (rollback-only marking
  on the shared `REQUIRES_NEW` transaction), this AC has two distinct, still-open failure modes for its one
  motivating scenario — one silent (write discarded), one loud (crashes the listener) — and the only new test
  (`CreditRoutingTest`'s mocked unit test) can observe neither, since `persistenceService` is a plain `@Mock`
  with no real Spring transaction behavior. The team already made a considered choice to document rather than
  fix this (matching this project's established convention), and the scenario remains unreachable via any
  current `deductSession` throw site — but three independent review passes converging on the same open gap in
  a payment-integrity safety net warrants an explicit call: **ship as documented-and-accepted, or add a real
  `*IT`-level test (and/or wrap the inner `persistPaymentFailure` call in its own `try`/`catch` for the loud
  failure mode) before closing this story?**
- [x] [Review][Defer] `handlePackBasedBooking`'s catch clause widening from `PaymentGatewayException` to bare
  `RuntimeException` (rather than a narrower type, e.g. `DataAccessException` specifically) will also silently
  absorb unrelated programming bugs from `deductSession` (NPE, `IllegalStateException`, etc.), funneling them
  into the same "expected business failure" `persistPaymentFailure` + `log.error` path already used for
  pack-exhausted/not-found — collapsing the distinction between an expected business failure and an unexpected
  system defect into one code path and one log signature, which could make future regressions harder to triage
  from logs/alerts alone [`PaymentLifecycleService.java:167`] — deferred, pre-existing design trade-off the
  story's own Dev Notes already explicitly reasoned through and defended (narrowest common supertype covering
  both known throw sites and any future unchecked throw, deliberately excluding `Error`); revisit only if a
  future pass needs finer-grained handling of this call's failure categories.
- [x] [Review][Defer] `sprint-status.yaml`'s `last_updated` field has grown into a single, unbounded YAML
  comment line spanning the cumulative history of 56+ stories — effectively unreviewable in normal diff/PR
  tooling and a guaranteed merge-conflict/diff-noise hotspot on every future story [`sprint-status.yaml`] —
  deferred, pre-existing repo-wide bookkeeping convention predating this story by dozens of prior stories, not
  something this one story should unilaterally restructure.

**Dismissed as noise (7, all independently re-verified against the live repo before dismissal):** the
`.satisfies(...)` raw-JSON-substring error-code assertions (`.contains("\"errorKey\":\"...\"")`) were claimed
fragile versus deserializing to a structured field — matches the exact, already-established pattern this same
file's three pre-existing hardened tests (`initiateUpload_platformDrill_returns403`,
`initiateUpload_otherCoachDrill_returns403`, `initiateUpload_readyVideoAlreadyLinked_returns403WithVideoAlreadyLinkedKey`)
already use, not a new pattern this diff introduces; the duplicated `HttpClientErrorException` cast-and-assert
block across the three hardened tests was claimed missing a shared helper — same reasoning, mirrors the
identical inline shape those same three pre-existing tests already use, and the story's own AC1 text explicitly
instructs mirroring that pattern; the new log line's `e.getMessage()` alongside a trailing `e` argument was
claimed redundant, and a null `e.getMessage()` was claimed to produce an ugly `error=null` — both real but
trivial, harmless, and a standard SLF4J idiom (message summary + full throwable for the stack trace); the
fixture-ownership fix (new `scoutCoachDrillId`) was claimed "asserted, not demonstrated" by a
no-project-access reviewer — independently re-verified true by the Acceptance Auditor directly against
`DrillUploadService.java:61-65`'s live ownership-then-gate check ordering; and widening only
`handlePackBasedBooking`'s catch (not the two other, unrelated `PaymentGatewayException` catch sites in the
same file at lines 208 and 329) was claimed inconsistent scope — those two sites guard entirely different call
chains (Stripe charge, batch booking) with no corresponding ledger item, not sibling instances of the same gap,
and expanding into them would be exactly the kind of scope creep this ledger's stories consistently decline.

## Dev Notes

- **This story bundles two independent, decision-light findings — it is not a single coherent feature.**
  AC1 is test-only hardening in one file (`DrillUploadResourceIT.java`); AC2 is one production code change
  plus its own test, fully independent of AC1.
- **AC1 touches an `*IT` class run under Failsafe** (`mvn -o integration-test -Dit.test=DrillUploadResourceIT`,
  bound to `integration-test`/`verify`, **not** `mvn -o test`) — this gotcha has tripped up prior stories in
  this same ledger and is worth restating every time. **AC2's test file, `CreditRoutingTest.java`, is a
  `*Test` class run under Surefire** (`mvn -o test -Dtest=CreditRoutingTest`). Do not swap these two commands.
- **AC2's fix is deliberately narrow: it widens one catch clause and adds one mirrored test. It does not**:
  add retry logic, change what happens on the existing `PaymentGatewayException` path, touch
  `deductSession`'s own throw sites, or attempt to make the new branch reachable in production today (it
  remains, as the source ledger item says, not reachable via any current throw site — this is defensive
  hardening for a future signature change, not a live-bug fix).
- **AC2's history is worth knowing before touching this code**: this exact item was investigated once
  already by `skillars-deferred-55`'s own story creation and explicitly left un-annotated/not-picked-up, on
  reachability grounds. This story picks it up anyway on different grounds (a small, mechanical,
  theme-consistent hardening, not a reachability change) — see "Why this story exists" for the full
  reasoning. If a future pass finds this AC's own tag but the code was never actually touched, that is a
  signal the story was abandoned mid-flight, not that the item is closed.
- **No frontend changes in this story.** Both ACs are backend-only (test code + one production method).
- **AC1a requires a new fixture, not just an assertion**: `DrillUploadResourceIT`'s existing `coachDrillId`
  is owned by `instrCoachId`, not `scoutCoachId` — asserting `security.featureGated` against it would fail
  the build (ownership check fires first, per `DrillUploadService.java:61-65`). AC1a's snippet now includes
  the required `scoutCoachDrillId` fixture in `setUp()`/`tearDown()`; do not skip it.
- **AC2 prevents a crash but does not guarantee its recovery write survives** — see AC2's "Known residual
  risk" note. This is a known, accepted gap for this story's scope, not an oversight.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` — new
  `scoutCoachDrillId` field + `setUp()`/`tearDown()` fixture wiring, three existing tests gain a
  `.satisfies(...)` block each, no new imports needed (AC1).
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java` —
  `handlePackBasedBooking`'s catch clause widened, log call gains the exception argument (AC2). No new
  imports.
- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` — one new test
  method, no new imports needed (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `PICKED UP`→`CLOSED` tag flips (AC3); five
  stale-item annotations already applied at story creation, not part of implementation scope.
- No database migrations, no frontend files, no changes to any `*Resource`/`*Controller` class in this
  story.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 762, section `## Deferred from:
  code review of skillars-4-3-custom-drill-uploads (2026-06-17)` — this story's AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 1702-1713, section `## Deferred
  from: code review of skillars-deferred-54-pack-deduction-failure-path-unit-coverage (2026-08-22)` — this
  story's AC2 source]
- [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:65-74,144`
  — `checkDrillUploadGate`'s `FeatureGatedException` throw and the `VideoValidationException`→
  `DrillConstraintViolationException` translation, AC1's target's production logic]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoTypeConstraints.java:25-44` —
  `validate`, the file-size/duration checks feeding AC1b/AC1c's `video.constraintViolated` errorKey]
- [Source: `src/main/java/com/softropic/skillars/platform/session/api/SessionApiAdvice.java:18-24` —
  `drillConstraintViolationHandler`, mapping `DrillConstraintViolationException` to
  `video.constraintViolated`]
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:326-331` —
  `featureGatedHandler`, mapping `FeatureGatedException` to `security.featureGated`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:162-175`
  — `handlePackBasedBooking`, AC2's target]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:51-61` —
  `deductSession`, cited in AC2's rationale for why `RuntimeException` is the right catch type]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java:164-179` —
  `packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal`, the sibling test AC2's new test
  mirrors]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (`claude-sonnet-5`), via the `bmad-dev-story` workflow.

### Debug Log References

- Initial `mvn -o integration-test -Dit.test=DrillUploadResourceIT` run collided with a concurrently-run
  `mvn -o test -Dtest=CreditRoutingTest` against the same `target/classes` directory, producing a spurious
  `NoClassDefFoundError: com/softropic/skillars/infrastructure/message/ErrorDto` / `ApplicationContext
  failure threshold exceeded` cascade across all 13 tests in the class. Root-caused to the two concurrent
  Maven processes racing on shared build output, not a real code or classpath issue (`ErrorDto.class`
  existed on disk and was well-formed). Re-ran `DrillUploadResourceIT` alone once the other process
  finished — 13/13 green.

### Completion Notes List

- AC1: Added a new `scoutCoachDrillId` fixture (`setUp()`/`tearDown()`) owned by the scout coach, per
  story-review Finding 1, and repointed `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` at it.
  Added `.satisfies(...)` `errorKey` assertions to all three target tests
  (`initiateUpload_scoutCoach_returns403WithFeatureGatedCode`,
  `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode`,
  `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode`). `mvn -o integration-test
  -Dit.test=DrillUploadResourceIT`: 13/13 green.
- AC2: Widened `PaymentLifecycleService.handlePackBasedBooking`'s catch clause from
  `PaymentGatewayException` to `RuntimeException`, added the exception to the log call. Added
  `packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal`
  to `CreditRoutingTest.java`. `PaymentGatewayException` import left in place (still used at two other
  catch sites in the same file). `mvn -o test -Dtest=CreditRoutingTest`: 11/11 green (10 existing + 1 new).
- AC3: Flipped both `deferred-work.md` `PICKED UP` tags to `CLOSED` (line 762 for AC1, line ~1713 for AC2)
  with closure notes naming the actual fixes and test results. Filed a fresh, untagged `deferred-work.md`
  item under a new `## Deferred from: story review of skillars-deferred-56-...` section capturing AC2's
  "Known residual risk" note (rollback-only transaction may discard the recovery write) verbatim in
  summary, per the story's own AC3 instruction.
- No deviations from the story spec. No new dependencies. `mvn verify` not run locally per
  `docs/validation-strategy.md` — only the two targeted commands above were run.

### File List

- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` (modified)
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java` (modified)
- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)

## Change Log

| Date | Change |
|---|---|
| 2026-08-22 | Story created via story-creation process, bundling two items re-mined from `deferred-work.md`: one from its most-recently-active tail (post-`skillars-deferred-49`), one from a previously-never-revisited old section (2026-06-17), after the tail alone was confirmed to hold only a single fresh candidate. AC1 closes a test-assertion gap in `DrillUploadResourceIT` (`skillars-4-3`'s own code review, W7) — bundled with two previously-untracked sibling tests carrying the identical gap, found while fixing W7 itself. AC2 closes `skillars-deferred-54`'s own deferred finding (`handlePackBasedBooking`'s narrow catch clause) — previously investigated and explicitly left un-annotated by `skillars-deferred-55`'s own creation on reachability grounds; picked up this pass on different grounds (small, mechanical, theme-consistent defensive hardening, not a reachability change — see "Why this story exists" for the full reasoning). AC3 is ledger hygiene for both. Five additional stale items found and tagged during the re-mine (none picked up as an AC): `refresh_alreadyUsedToken`'s entirely-commented-out test (line 863), `restore-from-snapshot.sh`'s deletion (line 964), the `BookingExpiredEvent`/`BookingReminderEvent`/`BookingConfirmedEvent` builder already existing (line 1141), a project-wide `.distinct()` audit closing out the `GdprExportService` item definitively (line 1687), and a `getConversations()` messaging-module fix already shipped unannotated (`skillars-8-2` D1/D2, embedded audit prose, not independently tagged). Two items considered and explicitly not picked up: `skillars-10-1 patches`' D1/D2 (test-fixture-only risk; intentional spec asymmetry). |
| 2026-08-22 | story-review adjustments applied, status remains ready-for-dev. `story-review.md` filed 2 findings against the draft, both fixed. Finding 1/High: AC1a's fixture bug — `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` posted to `coachDrillId`, owned by `instrCoachId`, so `DrillUploadService`'s ownership check would fire before the feature gate, making AC1a's specified `errorKey` assertion fail (actual body `DRILL_NOT_OWNED`, not `security.featureGated`) rather than stay green — added a new `scoutCoachDrillId` fixture (owned by `scoutCoachId`) to `setUp()`/`tearDown()` and repointed the test at it, per Finding 1's recommendation. Finding 2/Medium: AC2's widened `RuntimeException` catch may not survive its own motivating scenario — Spring marks the shared `REQUIRES_NEW` transaction rollback-only at the AOP boundary when `deductSession` (itself `@Transactional`, participating not new) throws, before the `catch` block runs, so `persistPaymentFailure`'s recovery write can be silently discarded on commit; the story's own new test uses a mocked `persistenceService` and cannot observe this. Per Finding 2's recommendation (b), documented as a known, accepted residual risk in AC2's own text and Dev Notes rather than expanding this small hardening story's scope into a real `*IT`-level transactional test — AC3 now also files a fresh (untagged) `deferred-work.md` item for this risk once AC2 ships, so it isn't lost. Both findings independently re-verified against live code (`DrillUploadResourceIT.java`, `DrillUploadService.java:61-65`, `PackSessionService.java:51-61`, `PaymentLifecycleService.java:138-139,224-229`, `BookingPaymentPersistenceService.java:206-207`) before applying. Everything else in the draft (AC1b/AC1c fixtures, errorKey serialization shape, AC2's import-removal hedge, AC2's test snippet compiling cleanly, AC3's ledger-tag state) was independently re-verified as accurate — no changes needed there. |
| 2026-08-22 | dev-story implementation complete, status review. AC1 added the `scoutCoachDrillId` fixture and `.satisfies(...)` `errorKey` assertions to all three target `DrillUploadResourceIT` tests, `mvn -o integration-test -Dit.test=DrillUploadResourceIT` 13/13 green. AC2 widened `PaymentLifecycleService.handlePackBasedBooking`'s catch clause to `RuntimeException` and added the mirrored `CreditRoutingTest` unit test, `mvn -o test -Dtest=CreditRoutingTest` 11/11 green. AC3 flipped both `deferred-work.md` `PICKED UP` tags to `CLOSED` and filed a fresh ledger item for AC2's known residual risk (rollback-only transaction may discard the recovery write). No deviations from spec; no new dependencies; `mvn verify` not run per `docs/validation-strategy.md`. |
| 2026-08-22 | code review complete (Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor: 0 AC violations. 1 decision-needed finding resolved by user: AC2's already-documented rollback-only residual risk was independently re-derived by both adversarial layers and elaborated with a second, previously-unnamed failure mode — `persistPaymentFailure`'s call inside the new catch block had no inner `try`/`catch`, so a secondary failure there would crash the `AFTER_COMMIT` listener uncaught rather than degrade gracefully. User decided: add the inner `try`/`catch` (fixes the crash/loud mode) but explicitly accept the silent rollback-only-discard mode as documented, unfixed risk — no `*IT`-level test added. Applied: `handlePackBasedBooking`'s `persistPaymentFailure(...)` call now wrapped in its own `catch (RuntimeException pfe)` that logs and swallows; `mvn -o test -Dtest=CreditRoutingTest` 11/11 green post-patch (no regression). 2 findings deferred to `deferred-work.md` (bare-`RuntimeException` catch may mask unrelated programming bugs, collapsing expected-business-failure vs. unexpected-system-defect log signatures — pre-existing, already-reasoned design trade-off; `sprint-status.yaml`'s `last_updated` field has grown into an unbounded, unreviewable single-line audit trail — pre-existing repo-wide convention, out of this story's scope). 7 findings dismissed as noise after independent re-verification — notably JSON-substring `errorKey` assertions and duplicated cast-and-assert test boilerplate, both claimed fragile/needing a helper by a no-project-access reviewer but confirmed to exactly match this same file's three pre-existing, already-hardened sibling tests' established pattern. |
