# Story Review: Deferred-56 — Drill-Upload Error-Code Assertions & Pack-Deduction Exception Safety

Senior-dev audit of `skillars-deferred-56-drill-upload-error-code-assertions-and-pack-deduction-exception-safety.md`
(status `ready-for-dev`) against live code, before implementation. Every finding below was verified by reading
the actual production/test files and, where cited, the actual `deferred-work.md` lines — nothing here is taken
on the story's word alone.

---

## Finding 1 (High) — AC1a's fixture posts to the wrong drill: `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` will fail, not stay green, once the specified assertion is added

**What's wrong:** AC1a instructs adding a `.satisfies(...)` block asserting
`"\"errorKey\":\"security.featureGated\""` to `initiateUpload_scoutCoach_returns403WithFeatureGatedCode`. That
assertion is correct **only if the request actually reaches `checkDrillUploadGate`**. It does not.

The test logs in as `SCOUT_EMAIL` and POSTs to `coachDrillId`
(`DrillUploadResourceIT.java:165-166`). But `coachDrillId` is created in `setUp()` as:

```java
// Coach drill owned by instrCoach
coachDrillId = UUID.randomUUID();
insertDrill(coachDrillId, "Coach Test Drill", "COACH", instrCoachId, "ACTIVE");
```

(`DrillUploadResourceIT.java:86-88`, `insertDrill(id, name, libraryType, ownerCoachId, status)` per
`BaseSessionIT.java:136`) — owned by **`instrCoachId`**, not `scoutCoachId`. `scoutCoachId` is created for the
coach profile/subscription and used only in teardown; it is never assigned as the owner of any drill anywhere
in this file (confirmed by grep — its only other references are profile creation and cleanup).

`DrillUploadService.initiateUpload` checks ownership **before** the feature gate:

```java
if (!"COACH".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())) {
    throw new OperationNotAllowedException("Drill upload not allowed", SessionErrorCode.DRILL_NOT_OWNED);
}
checkDrillUploadGate(coachId);   // never reached for this test
```

(`DrillUploadService.java:61-65`). Since the authenticated coach is `scoutCoachId` and the drill's owner is
`instrCoachId`, this throws `OperationNotAllowedException(DRILL_NOT_OWNED)` — mapped to `403` by
`ApiAdvice.operationDeniedHandler` (`ApiAdvice.java:267-277`) with `errorKey = "DRILL_NOT_OWNED"` (the exact
same handler and errorKey the file's own already-hardened `initiateUpload_platformDrill_returns403` and
`initiateUpload_otherCoachDrill_returns403` tests already assert). `checkDrillUploadGate` — and therefore
`FeatureGatedException`/`security.featureGated` — is never reached.

**Why it matters:** today the test's only assertion is `.isInstanceOf(HttpClientErrorException.Forbidden.class)`
(`DrillUploadResourceIT.java:171`), which passes coincidentally — `DRILL_NOT_OWNED` and `security.featureGated`
are both mapped to HTTP 403, so the test can't tell them apart. Adding AC1a's specified `.contains("\"errorKey\":
\"security.featureGated\"")` check, exactly as written, will make this test **fail** (actual body contains
`DRILL_NOT_OWNED`, not `security.featureGated`) — directly contradicting the story's own Task 1.4 / AC1 "Test
coverage" instruction to run the suite and "confirm all tests remain green." The SCOUT-tier gate itself is real
and correctly configured (`V42__drill_video_upload_config.sql:2`: `feature.drillVideoUpload.enabled.SCOUT =
'false'`) — the story's intent is right, only the fixture wiring is wrong.

**Recommendation:** add a drill fixture actually owned by the scout coach before applying AC1a's assertion, e.g.
in `setUp()`:

```java
scoutCoachDrillId = UUID.randomUUID();
insertDrill(scoutCoachDrillId, "Scout Test Drill", "COACH", scoutCoachId, "ACTIVE");
```

(with matching cleanup added to `tearDown()`'s existing `DELETE ... WHERE drill_id IN (...)` statements), and
point `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` at `scoutCoachDrillId` instead of `coachDrillId`.
This is a one-line-of-setup fix, but it is required for AC1a to test what its own name and AC text claim it
tests — without it, the story ships a hardened assertion against a test that structurally can't reach the code
path it's named for.

---

## Finding 2 (Medium) — AC2's widened catch may not survive its own motivating scenario: a `DataAccessException` from `deductSession` marks the shared `REQUIRES_NEW` transaction rollback-only before `persistPaymentFailure` ever runs, and no test in the story can observe this

**What's wrong:** AC2's stated rationale for choosing `RuntimeException` over a narrower type is explicit:
`deductSession`'s `sessionPackPurchaseRepository.save(purchase)` "can throw an unchecked
`org.springframework.dao.DataAccessException` subtype on a real persistence failure" — that's the scenario the
widened catch is built to survive.

But `deductSession` is itself `@Transactional` (`PackSessionService.java:51`, default `REQUIRED`), called from
`handlePackBasedBooking`, which runs inside `onBookingAccepted`'s `@Transactional(propagation =
Propagation.REQUIRES_NEW)` `AFTER_COMMIT` listener (`PaymentLifecycleService.java:138-139`) — so `deductSession`
joins that same physical transaction rather than starting its own. Under Spring's default `@Transactional`
rollback rule, when an unchecked exception propagates out of a `@Transactional`-proxied method that is
*participating* in an existing transaction (not a new one), Spring's `TransactionInterceptor` marks that shared
transaction `rollback-only` at the AOP boundary — **before the exception ever reaches the caller's `catch`
block.** This happens for any `RuntimeException`, not only ones tied to an actual failed SQL statement.

`persistenceService.persistPaymentFailure(...)` (the recovery write AC2's new catch branch calls) is itself
`@Transactional` (default `REQUIRED` — `BookingPaymentPersistenceService.java:206-207`), so it joins that same
now-rollback-only transaction. Its `INSERT`/`UPDATE` statements will very likely still execute against Postgres
(marking rollback-only is Spring bookkeeping, not a DB-level lock), but when `onBookingAccepted`'s outer
`REQUIRES_NEW` transaction reaches its own commit point, Spring sees `isRollbackOnly() == true` and rolls back
the whole thing instead — silently discarding the very failure record this code exists to guarantee, for
exactly the `DataAccessException` scenario the AC names as its reason for existing.

This is corroborated by a comment already in this exact file, describing the team having been bitten by a
closely related "nested `@Transactional` write inside an `AFTER_COMMIT` listener quietly loses data" class of
bug before (`PaymentLifecycleService.java:224-229`: *"the nested `@Transactional` calls in
`PackSessionService`/`BookingPaymentPersistenceService` would join a completed transaction and lose their
writes... found while proving Deferred-12 AC6 end-to-end; `BatchPaymentIT` never caught it because it invokes
this listener directly, with no surrounding transaction"*) — the failure mode is different in mechanism
(rollback-only marking vs. joining an already-completed transaction) but identical in shape: writes made inside
this listener silently vanish.

**Why it matters:** the only test AC2 adds — `CreditRoutingTest`'s new
`packBasedBooking_deductSessionFailsWithNonPaymentGatewayException_...` — is a Mockito unit test where
`persistenceService` is a plain `@Mock` (confirmed: `CreditRoutingTest.java:49`, with an existing comment
explaining `@InjectMocks` needs it mocked or every path NPEs). A mocked bean has no `@Transactional` AOP
behavior at all, so this test can only prove "the right method was called with the right arguments" — it cannot
observe whether that call's write actually survives a real Spring transaction commit. The story's own Dev Notes
say AC2 "does not alter any currently-tested behavior," which is true, but also means the one new behavior this
AC adds (recovering from a real persistence failure) is untested at the only level that could catch this class
of bug.

To be clear about scope: this rollback-only mechanism is **not introduced by this story** — it already applies
identically to the existing, already-shipped `PaymentGatewayException` catch branch. It has caused no observed
harm there only because both of `deductSession`'s current `PaymentGatewayException` throw sites fire *before*
any DB write (`orElseThrow` on a read, or a check before `.save()`) — so there's no write-in-flight for the
rollback to silently discard in the pre-existing case. AC2 is the first change to make this mechanism
consequential, specifically because it's designed around a scenario (`.save(purchase)` failing) that, by
definition, means a write was attempted before the transaction got marked rollback-only.

**Recommendation:** before treating this AC as fully closing the gap it targets, either (a) add a real
`*IT`-level test that forces `sessionPackPurchaseRepository.save(...)` to fail with a genuine `DataAccessException`
inside the real `AFTER_COMMIT`/`REQUIRES_NEW` flow and asserts a `BookingPayment` row with a failure status
actually exists in the database afterward (this would either prove my analysis wrong or catch the bug for real,
unlike the mocked unit test), or (b) explicitly document this as a known, accepted residual risk in Dev Notes
(matching this project's own established convention of documenting rather than silently shipping unverified
defensive code) rather than the current framing, which asserts unqualified that this AC "closes the gap" and
"can no longer crash an `AFTER_COMMIT` event listener uncaught" — the crash is prevented, but the recovery
write it was meant to guarantee may not be.

---

## Summary

| # | Severity | Area | One-line issue |
|---|----------|------|-----------------|
| 1 | High | AC1a / `DrillUploadResourceIT` | `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` posts to a drill owned by the wrong coach — the ownership check fires first, so AC1a's specified assertion will fail, not stay green |
| 2 | Medium | AC2 / `PaymentLifecycleService` | Widened `RuntimeException` catch may not survive its own motivating `DataAccessException` scenario, due to Spring's rollback-only marking on the shared `REQUIRES_NEW` transaction — untestable by the story's Mockito-only new test |

**Everything else independently re-verified as accurate, no changes needed:**

- **AC1b/AC1c** (`initiateUpload_fileSizeTooLarge_...`, `initiateUpload_durationTooLong_...`): both use
  `INSTR_EMAIL` + `coachDrillId`, which *is* owned by `instrCoachId` — ownership passes, `checkDrillUploadGate`
  passes (INSTRUCTOR tier is enabled per `V42__drill_video_upload_config.sql:3`, and the file's own
  `initiateUpload_instructorCoach_returns201WithUploadUrl` already proves this tier clears the gate today), and
  the file-size/duration payloads (`600_000_000` bytes, `150`s) correctly reach `VideoTypeConstraints.validate`
  before any "already linked" check can interfere (confirmed call order in `DrillUploadService.java:61-83`). No
  fixture bug here — these two are safe to harden exactly as specified.
- The `security.featureGated` and `video.constraintViolated` errorKey claims are both correct: `ErrorMsg` is a
  record `(String errorKey, String message)` (`ErrorMsg.java:6`) nested inside `ErrorDto.errorMsg`, so Jackson
  serializes `"errorMsg":{"errorKey":"...","message":"..."}` — a substring `.contains("\"errorKey\":\"...\"")`
  check matches regardless of the nesting, exactly as the file's own already-passing hardened tests
  (`DRILL_NOT_OWNED`, `DRILL_VIDEO_ALREADY_LINKED`) already prove.
- All cited production line numbers checked and accurate: `DrillUploadService.java:144` (`FeatureGatedException`
  throw), `:72` (`VideoValidationException` catch), `SessionApiAdvice.java:18-24`, `ApiAdvice.java:326-331`,
  `PaymentLifecycleService.java:162-175`, `PackSessionService.java:51-61`.
- AC2's import-removal hedge ("check whether `PaymentGatewayException` is still needed elsewhere before
  removing it") is correct to be cautious: the import is still used at two other catch sites in the same file
  (`PaymentLifecycleService.java:207,328`), so it must stay.
- AC2's new `CreditRoutingTest` snippet compiles cleanly against the live file: `doThrow`, `eq`, `anyString`,
  `any(Instant.class)`, `PARENT_ID`, `BOOKING_ID`, the `event(...)` helper, and all referenced mocks are already
  present and imported, and the snippet is a structurally exact mirror of the existing sibling test
  `packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal` (`CreditRoutingTest.java:164-179`).
- AC3's ledger hygiene: both `[PICKED UP by skillars-deferred-56 ACn]` tags (line 762, lines 1702-1713) and all
  five `[STALE — verified... by skillars-deferred-56 story creation]` annotations (lines 863, 964, 1141, 1687,
  and the acknowledged-but-intentionally-untagged `skillars-8-2` D1/D2 mention) were independently re-checked
  against the live ledger text and are accurate as described — no premature `[CLOSED]` tags, no
  misrepresentation of what each stale item actually verified.
- The "why AC2 was previously left un-annotated by `skillars-deferred-55`, picked up now on different grounds"
  framing is self-consistent and doesn't overclaim reachability that isn't there — the story is honest that this
  remains defensive hardening for unreachable code, not a live-bug fix.
