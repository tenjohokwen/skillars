# Story Deferred-56: Drill-Upload Error-Code Assertions & Pack-Deduction Exception Safety

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want three `DrillUploadResourceIT` tests whose names already promise an error-code check to actually
assert that code in the response body, and `PaymentLifecycleService.handlePackBasedBooking` to treat any
`RuntimeException` from `packSessionService.deductSession(...)` the same way it already treats
`PaymentGatewayException` (log it, record a payment failure, return cleanly) instead of only the latter,
so that a future regression in either spot is actually caught by its existing safety net rather than
passing green by coincidence, and a future change to `deductSession`'s throw contract can no longer crash
an `AFTER_COMMIT` event listener uncaught.

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
   - **1a. `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` (`:155-172`).** Current shape:
     ```java
     assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
         baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
         HttpMethod.POST, payload, authenticatedHeaders(cookies), Map.class
     )).isInstanceOf(HttpClientErrorException.Forbidden.class);
     ```
     Change to:
     ```java
     assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
         baseUrl() + DRILLS_BASE + "/" + coachDrillId + "/video/initiate",
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
     resolveMinUploadTier())` (`DrillUploadService.java:144`) when a `SCOUT`-tier coach hits this endpoint.
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
   - Once AC2 ships: flip the line-~1702 tag to `` `[CLOSED by skillars-deferred-56 AC2]` `` the same way.
   - **If a partial implementation lands**, flip only the tag for the AC that actually shipped — leave the
     other at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [ ] Task 1: `DrillUploadResourceIT` error-code assertions (AC: #1)
  - [ ] 1.1 Add the `.satisfies(...)` block to `initiateUpload_scoutCoach_returns403WithFeatureGatedCode`,
    per AC1a's snippet.
  - [ ] 1.2 Add the `.satisfies(...)` block to
    `initiateUpload_fileSizeTooLarge_returns422WithConstraintViolatedCode`, per AC1b.
  - [ ] 1.3 Add the `.satisfies(...)` block to `initiateUpload_durationTooLong_returns422WithConstraintViolatedCode`,
    per AC1c.
  - [ ] 1.4 Run `mvn -o integration-test -Dit.test=DrillUploadResourceIT` and confirm green.
- [ ] Task 2: `PaymentLifecycleService` pack-deduction exception widening (AC: #2)
  - [ ] 2.1 Widen `handlePackBasedBooking`'s catch clause from `PaymentGatewayException` to
    `RuntimeException`, and add `e` to the log call, per AC2's snippet.
  - [ ] 2.2 Add `packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_callsPersistFailureWithZeroReversal`
    to `CreditRoutingTest.java`, per AC2's snippet.
  - [ ] 2.3 Run `mvn -o test -Dtest=CreditRoutingTest` and confirm all tests green (existing plus the new one).
- [ ] Task 3: Ledger hygiene (AC: #3) — flip the two `PICKED UP` tags applied at story creation to `CLOSED`
  once AC1/AC2 land, per AC3.

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
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` — three existing
  tests gain a `.satisfies(...)` block each, no new imports needed (AC1).
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

_To be filled by dev agent._

### Debug Log References

_To be filled by dev agent._

### Completion Notes List

_To be filled by dev agent._

### File List

_To be filled by dev agent._

## Change Log

| Date | Change |
|---|---|
| 2026-08-22 | Story created via story-creation process, bundling two items re-mined from `deferred-work.md`: one from its most-recently-active tail (post-`skillars-deferred-49`), one from a previously-never-revisited old section (2026-06-17), after the tail alone was confirmed to hold only a single fresh candidate. AC1 closes a test-assertion gap in `DrillUploadResourceIT` (`skillars-4-3`'s own code review, W7) — bundled with two previously-untracked sibling tests carrying the identical gap, found while fixing W7 itself. AC2 closes `skillars-deferred-54`'s own deferred finding (`handlePackBasedBooking`'s narrow catch clause) — previously investigated and explicitly left un-annotated by `skillars-deferred-55`'s own creation on reachability grounds; picked up this pass on different grounds (small, mechanical, theme-consistent defensive hardening, not a reachability change — see "Why this story exists" for the full reasoning). AC3 is ledger hygiene for both. Five additional stale items found and tagged during the re-mine (none picked up as an AC): `refresh_alreadyUsedToken`'s entirely-commented-out test (line 863), `restore-from-snapshot.sh`'s deletion (line 964), the `BookingExpiredEvent`/`BookingReminderEvent`/`BookingConfirmedEvent` builder already existing (line 1141), a project-wide `.distinct()` audit closing out the `GdprExportService` item definitively (line 1687), and a `getConversations()` messaging-module fix already shipped unannotated (`skillars-8-2` D1/D2, embedded audit prose, not independently tagged). Two items considered and explicitly not picked up: `skillars-10-1 patches`' D1/D2 (test-fixture-only risk; intentional spec asymmetry). |
