# Story Deferred-55: Test-Assertion Hardening & Payment Config-Lookup Safety

Status: done

## Story

As an engineer operating this platform,
I want three existing tests to assert the actual outcomes they claim to verify instead of weaker proxies
(un-asserted call arguments, mock-call ordering alone, an overly broad lock-wait query), and
`StripePaymentGateway.chargeAndCapture` to fail predictably when its two required platform-config values
are missing or stale instead of throwing an uncaught `IllegalStateException`,
so that a future regression in any of these four spots is actually caught by its existing safety net
rather than passing green by coincidence.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1713 lines at the time this story was created)
was re-mined end to end, focusing on the most recently active tail of the ledger
(post-`skillars-deferred-49`, since `skillars-deferred-53`'s own creation notes already confirmed
everything before that point either needs a design decision, is a standing accepted frontend-test-gap,
or is stale). Every untagged item in that tail was re-checked against live code:

- `1591`/`1598`/`1599` (video-quota dedup-rule design question, async-recalculation race widening, `V98`
  unchunked backfill) and `1603` (`DisputeService`'s `FROZEN`-status filter gap) all explicitly need a
  coordinated design/product decision spanning multiple services — not a mechanical fix a bundled
  small-fix story should make ad hoc.
- `1611`/`1620`/`1621` (`playerStore.js`/`session.store.js` frontend items) are either a standing accepted
  frontend-test-infrastructure gap (matching the reasoning `skillars-deferred-35`–`38` already established)
  or a "needs a design decision" generation-guard gap already explicitly deferred by
  `skillars-deferred-46`'s own creation.
- `1630`/`1634`/`1635`/`1636`/`1640` (booking-module duplication/locking-strategy/DST items) are all
  explicitly out-of-scope-by-their-own-text — the same class of locking-strategy or established-convention
  decision `skillars-deferred-49`/`50`/`53` already declined for sibling items.
- `1687` (possible additional `.stream().distinct()` issues in other `GdprExportService` builder methods)
  is explicitly "unconfirmed, worth a follow-up grep" — an open-ended investigation, not a bounded fix; a
  quick grep of `GdprExportService.java` during this story's creation found `.distinct()` used in exactly
  one place (the method `skillars-deferred-52` already fixed), so there is nothing else to act on today.
  Left un-annotated, not picked up.
- `1700` (`skillars-deferred-54`'s own deferred finding — `handlePackBasedBooking` only catches
  `PaymentGatewayException` from `deductSession`) is **not reachable via any current throw site** per its
  own text (both of `deductSession`'s throw sites are already `PaymentGatewayException`) — widening the
  catch clause today would be defensive coding against a hypothetical future signature change, not a real
  gap; left un-annotated, not picked up.

Four items survived re-verification as genuine, bounded, decision-light fixes — three test-assertion gaps
and one small, mechanical production fix explicitly scoped to "both call sites together" by its own ledger
text:

- **D1 (this story's AC1)** — sourced from `## Deferred from: code review of skillars-deferred-50-...`
  (line 1641): `BookingDuplicationServiceTest#duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable`
  stubs `findOverlappingBookings(any(), any(), any(), any(), any())` and never verifies what it was called
  with. Re-verified live: `BookingDuplicationServiceTest.java:199` still stubs with five `any()` matchers,
  no `verify(...)` call on `findOverlappingBookings` exists anywhere in the file.
- **D2 (this story's AC2)** — sourced from `## Deferred from: code review of skillars-deferred-52-...`
  (line 1686): `VideoServiceTest`/`AdminVideoServiceTest`'s new unit tests verify call ordering only, not
  actual resulting state. Re-verified live and **re-scoped during this story's creation**:
  `VideoServiceTest.failTranscoding_transitionsToFailedBeforeReleasingQuota` (`VideoServiceTest.java:124`)
  already asserts the exact `OperationalState.FAILED` argument passed to the mocked
  `videoLifecycleService.transitionOperationalState(...)` via its `InOrder` check — the strongest assertion
  a mockist test of this method can make, since the real state mutation happens inside the mocked
  collaborator, not on an inspectable object here. `AdminVideoServiceTest.deleteVideo_pendingSession_...`
  (`AdminVideoServiceTest.java:57-73`) is the genuine gap: it operates on a **real** `Video`/`UploadSession`
  object (mutated in place by the production code, not by a mock), yet only verifies
  `videoRepository.save(video)`/`quotaProvider.release(...)` were called via `InOrder` — never asserting
  `video.getOperationalState()` or `session.getStatus()` directly. A regression that forgot
  `v.setOperationalState(OperationalState.DELETED)` before `save(v)` would still pass `verify(...).save(video)`
  (same object reference, called regardless of its field values), so this AC scopes to
  `AdminVideoServiceTest` only.
- **D3 (this story's AC3)** — sourced from `## Deferred from: code review of skillars-deferred-53-...`
  (line 1698): `BookingServiceConcurrencyIT`'s `awaitAnotherSessionBlockedOnCoachProfileLock` helper's
  `pg_locks` query matches on *any* lock blocked against the suspender transaction's xid, not specifically
  a lock on `coach_profiles`. Re-verified live at `BookingServiceConcurrencyIT.java:410-414`: the query is
  unchanged since flagged.
- **D4 (this story's AC4)** — sourced from the same section (line 1695): `StripePaymentGateway`'s two
  `configService.getString(...)` calls (`commissionRate`, `currency`) both throw an uncaught
  `IllegalStateException` on a missing/stale config key, unlike every other failure path in
  `chargeAndCapture`. The item's own text says a fix "needs to address both call sites... together" — this
  story does exactly that, closing the item in full rather than patching one line in isolation (the thing
  its own text warned against). Re-verified live at `StripePaymentGateway.java:42,48`: both lines unchanged.

**Deliberately not picked up in this pass** (found while re-mining, out of this story's scope): see the
bullet list above — `1591`/`1598`/`1599`/`1603` (design decisions spanning services), `1611`/`1620`/`1621`
(frontend items already covered by standing conventions or prior explicit deferrals), `1630`/`1634`/`1635`/
`1636`/`1640` (locking-strategy/established-convention decisions already declined by prior stories), `1687`
(investigated, nothing further to act on), and `1700` (not reachable via any current throw site).

## Acceptance Criteria

1. **AC1 — `BookingDuplicationServiceTest#duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable`
   verifies the actual arguments passed to `findOverlappingBookings`.**
   - File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java:190-206`.
   - Current shape stubs the call with five `any()` matchers and never verifies invocation arguments:
     ```java
     when(bookingRepository.findOverlappingBookings(any(), any(), any(), any(), any()))
         .thenReturn(List.of(new Booking()));
     ```
   - Add a `verify(...)` call after the existing `assertThatThrownBy(...)` assertion, asserting the exact
     arguments the production code computes (`BookingDuplicationService.java:80-82`): the coach id, the
     original booking's start/end times each advanced by 7 days (computable directly from this test's own
     `originalStart` local variable — do not depend on wall-clock time at assertion time), the shared
     `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` status list, and a `null` exclude-id:
     ```java
     verify(bookingRepository).findOverlappingBookings(
         eq(COACH_ID),
         eq(originalStart.plus(7, ChronoUnit.DAYS)),
         eq(originalStart.plus(1, ChronoUnit.HOURS).plus(7, ChronoUnit.DAYS)),
         eq(BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED),
         isNull());
     ```
     No new imports needed: `eq`/`isNull` are already reachable in this file via the existing
     `import static org.mockito.Mockito.*;` wildcard (`Mockito.java` is declared `public class Mockito
     extends ArgumentMatchers`, so every `ArgumentMatchers` static method is inherited — confirmed live:
     `eq(...)` is already called unqualified at `BookingDuplicationServiceTest.java:111` with no dedicated
     `eq` import). Leave the `any()`-based `when(...)` stub as-is — only the new `verify(...)` needs precise
     matchers; keeping the stub loose avoids over-specifying two independent concerns in one line.
   - **Why this closes the gap**: without this, an argument-swap regression (e.g. passing the *original*
     booking's start/end instead of the +7-day proposed slot, or the wrong status list) would still pass
     this test, since `any()` matches anything. The precise `verify(...)` fails if any argument drifts from
     what the AC1/AC2 spec of `skillars-deferred-50` actually required.
   - **Test coverage**: this AC's change *is* the test hardening — no new test method needed. Run
     `mvn -o test -Dtest=BookingDuplicationServiceTest` and confirm all tests remain green (this is a
     `*Test`/Surefire class, not an `*IT`/Failsafe class — see this file's naming, unlike
     `BookingServiceConcurrencyIT` in AC3 below).

2. **AC2 — `AdminVideoServiceTest#deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits` asserts
   the actual resulting state of the real `Video`/`UploadSession` objects it holds, not just mock call
   ordering.**
   - File: `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java:57-73`.
   - Current shape only checks mock interactions via `InOrder`:
     ```java
     service.deleteVideo(videoId);

     InOrder inOrder = inOrder(videoRepository, quotaProvider);
     inOrder.verify(videoRepository).save(video);
     inOrder.verify(quotaProvider).release("handle-1");
     ```
   - Add two direct state assertions after the existing `InOrder` block, using AssertJ (already the
     project's mandated assertion library — see `_bmad-output/project-context.md`'s Testing Rules):
     ```java
     assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);
     assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
     ```
     Both `video` and `session` are already local variables in this test method (`AdminVideoServiceTest.java:59,63`),
     the exact same object references the production code mutates in place
     (`AdminVideoService.deleteVideo`'s inner transaction re-fetches `videoId` via the same mocked
     `videoRepository.findById(...)` stub, returning this same `video` instance — confirmed by reading
     `AdminVideoService.java:50,62`). Add `import com.softropic.skillars.platform.video.contract.OperationalState;`
     and `import static org.assertj.core.api.Assertions.assertThat;` (neither currently imported in this
     file). `UploadSessionStatus` is already imported.
   - **Also add the video-state assertion to the sibling test, `deleteVideo_noPendingSession_neverReleasesQuota`
     (`AdminVideoServiceTest.java:75-83`)**: append
     `assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);` there too (no
     session-status assertion needed — that test's fixture has no session at all). `video` is already a
     local variable in that method.
   - **Why this closes the gap**: `verify(videoRepository).save(video)` only proves `save()` was called
     with this object *reference* — it says nothing about what fields that object held at the time. A
     regression that dropped `v.setOperationalState(OperationalState.DELETED)` (`AdminVideoService.java:64`)
     before the `save(v)` call would still pass the existing `InOrder` check unchanged, since `save(video)`
     matches by reference regardless of field state. The two new assertions inspect the actual object
     after the call completes, closing that gap. Backstopped by `AdminVideoIT`'s existing 10-test
     end-to-end coverage of `deleteVideo` (per the ledger item's own severity note) — this AC raises a
     low-severity gap to fully covered, it does not fix a live bug. The sibling test's own assertion matters
     independently: `v.setOperationalState(DELETED)` executes unconditionally, before the session lookup
     that determines whether a session gets expired — a future refactor that accidentally moved the
     state-transition inside the session-lookup's `.map(...)` callback would still pass
     `deleteVideo_pendingSession_...` (a session exists there) but would leave a session-less video silently
     un-deleted, undetected by `deleteVideo_noPendingSession_...` unless that test also asserts the state.
   - **`VideoServiceTest` is deliberately left unchanged** — see "Why this story exists" above:
     `failTranscoding_transitionsToFailedBeforeReleasingQuota`'s existing `InOrder` check already asserts
     the exact `OperationalState.FAILED` argument passed to the *mocked* `videoLifecycleService`, which is
     the strongest assertion available in a test where the real state transition happens inside a mocked
     collaborator rather than an object this test can inspect. Adding a redundant assertion there would
     not close any additional gap.
   - **Test coverage**: this AC's change *is* the test hardening — no new test method needed. Run
     `mvn -o test -Dtest=AdminVideoServiceTest` and confirm both existing tests remain green.

3. **AC3 — `BookingServiceConcurrencyIT`'s `awaitAnotherSessionBlockedOnCoachProfileLock` helper's
   `pg_locks` query specifically confirms the blocked session is waiting on a `coach_profiles` lock, not
   just any lock against the suspender's transaction id.**
   - File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:407-423`.
   - Current query:
     ```java
     Integer blockedCount = jdbcTemplate.queryForObject(
         "SELECT count(*) FROM pg_locks " +
         "WHERE locktype = 'transactionid' AND granted = false " +
         "AND pid != pg_backend_pid() AND transactionid = pg_current_xact_id()::text::xid",
         Integer.class);
     ```
   - Add an `EXISTS` clause requiring the same blocked `pid` to also hold a `pg_locks` entry whose
     `relation` resolves to `coach_profiles` — a session executing `SELECT ... FOR UPDATE` against that
     table always acquires an `AccessShareLock` on the table itself (granted immediately, alongside the
     row-level wait), so this is a safe, always-present signal specific to this table:
     ```java
     Integer blockedCount = jdbcTemplate.queryForObject(
         "SELECT count(*) FROM pg_locks waiting " +
         "WHERE waiting.locktype = 'transactionid' AND waiting.granted = false " +
         "AND waiting.pid != pg_backend_pid() " +
         "AND waiting.transactionid = pg_current_xact_id()::text::xid " +
         "AND EXISTS (" +
         "  SELECT 1 FROM pg_locks rel" +
         "  WHERE rel.pid = waiting.pid AND rel.relation = 'marketplace.coach_profiles'::regclass" +
         ")",
         Integer.class);
     ```
     `marketplace.coach_profiles` is confirmed the entity's schema-qualified table name
     (`CoachProfile.java:25`: `@Table(schema = "marketplace", name = "coach_profiles")`) — use it exactly
     as shown; do not use the unqualified `coach_profiles`.
   - **Why this closes the gap**: the current query matches on *any* lock blocked against this
     transaction's xid — correct today only because the suspender transaction happens to hold exactly one
     lock. The `EXISTS` clause makes the check specific to `coach_profiles`, so a future change that adds a
     second lock-acquiring statement to either race test's suspender setup block cannot silently start
     matching the wrong backend's wait.
   - **Test coverage**: this AC's change *is* the test hardening — no new test method needed. Run
     `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` (Failsafe, not `mvn -o test` — see
     Dev Notes' IT-execution gotcha) and confirm all 4 tests remain green, including both hardened race
     tests. Additionally run the two race tests standalone 3-5 times each to confirm the narrowed query
     still reliably detects the blocked session (it must not introduce new flakiness).

4. **AC4 — `StripePaymentGateway.chargeAndCapture`'s two `configService.getString(...)` calls fail with a
   caught, mapped `PaymentGatewayException` instead of an uncaught `IllegalStateException` when a config
   key is missing or the cache hasn't refreshed since a rolling deploy.**
   - File: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:40-48`.
   - Current shape (both calls unguarded, unlike the `try`/`catch (StripeException e)` block later in the
     same method):
     ```java
     public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
         String coachStripeAccountId = resolveCoachStripeAccountId(coachId);
         BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
         long amountCents = toCents(amount);
         long feeCents = toCents(amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));

         PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
             .setAmount(amountCents)
             .setCurrency(configService.getString("platform.payment.currency"))
             ...
     ```
   - Wrap both `configService.getString(...)` calls in one `try`/`catch (IllegalStateException e)`, mapping
     to a new `PaymentGatewayException` error code, before any other logic in the method depends on their
     values:
     ```java
     public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
         String coachStripeAccountId = resolveCoachStripeAccountId(coachId);

         String commissionRateRaw;
         String currency;
         try {
             commissionRateRaw = configService.getString("platform.commission.rate");
             currency = configService.getString("platform.payment.currency");
         } catch (IllegalStateException e) {
             log.error("Payment configuration unavailable: error={}", e.getMessage());
             throw new PaymentGatewayException("payment.configurationUnavailable", e);
         }
         BigDecimal commissionRate = new BigDecimal(commissionRateRaw);
         long amountCents = toCents(amount);
         long feeCents = toCents(amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP));

         PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
             .setAmount(amountCents)
             .setCurrency(currency)
             ...
     ```
     `PaymentGatewayException` already has an `(errorCode, Throwable cause)` constructor
     (`PaymentGatewayException.java:12-15`) — no change needed to the exception class itself. No new
     import needed: `PaymentGatewayException` is already imported in this file.
   - **Why this is the right, bounded scope**: the ledger item explicitly says a fix must "address both
     call sites... together" rather than patch the new currency line in isolation — this AC does exactly
     that in one `try` block, matching the existing method's established pattern of wrapping external
     failure sources (`StripeException` further down) into `PaymentGatewayException` rather than letting a
     technical exception type leak out of the gateway's contract. Not in scope: format validation of the
     currency value, or `ConfigService.getString`'s contract generally, or `main.platform_config`'s
     hand-assigned-PK schema design — each of those remains a separate, already-tracked ledger item this
     story does not touch (see "Why this story exists").
   - **Test coverage**: `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java`.
     Add two new tests, following this file's existing `stubCoachAndCommission()`/`assertThatThrownBy`
     conventions (the latter used by other test classes in this package, e.g.
     `CreditRoutingTest`/`BookingDuplicationServiceTest` — not yet imported in this file, add
     `import static org.assertj.core.api.Assertions.assertThatThrownBy;` and
     `import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;`):
     ```java
     @Test
     void chargeAndCapture_missingCommissionRateConfig_throwsPaymentGatewayException() {
         CoachStripeAccount account = new CoachStripeAccount();
         account.setStripeAccountId("acct_test");
         account.setOnboardingStatus("COMPLETE");
         account.setChargesEnabled(true);
         when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
         when(configService.getString("platform.commission.rate"))
             .thenThrow(new IllegalStateException("Missing platform config key: platform.commission.rate"));

         assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
             .isInstanceOf(PaymentGatewayException.class)
             .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                 .isEqualTo("payment.configurationUnavailable"));
     }

     @Test
     void chargeAndCapture_missingCurrencyConfig_throwsPaymentGatewayException() {
         CoachStripeAccount account = new CoachStripeAccount();
         account.setStripeAccountId("acct_test");
         account.setOnboardingStatus("COMPLETE");
         account.setChargesEnabled(true);
         when(coachStripeAccountRepository.findById(COACH_ID)).thenReturn(Optional.of(account));
         when(configService.getString("platform.commission.rate")).thenReturn("0.10");
         when(configService.getString("platform.payment.currency"))
             .thenThrow(new IllegalStateException("Missing platform config key: platform.payment.currency"));

         assertThatThrownBy(() -> stripePaymentGateway.chargeAndCapture(PACK_TIER_ID, 1001L, COACH_ID, AMOUNT))
             .isInstanceOf(PaymentGatewayException.class)
             .satisfies(e -> assertThat(((PaymentGatewayException) e).getErrorCode())
                 .isEqualTo("payment.configurationUnavailable"));
     }
     ```
     Do not reuse `stubCoachAndCommission()` for either test — it stubs both config keys to succeed, which
     is the opposite of what these two tests need; stub the coach account directly as shown, mirroring
     the fixture setup `stubCoachAndCommission()` itself uses for the account half. `MockitoExtension`'s
     strict-stubbing mode means no unused stub is left dangling in either test (each stubs exactly what its
     own path through `chargeAndCapture` reaches). Run
     `mvn -o test -Dtest=StripePaymentGatewayTest` and confirm all tests green (existing tests plus these
     two new ones).

5. **AC5 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for `deferred-49` through `-54`) is: at **story-creation** time, tag an item this story is
   about to fix as `` `[PICKED UP by skillars-deferred-55 ACn]` `` — appended after the item's existing
   text/citation, without rewriting the body to describe a fix that hasn't happened yet.
   `` `[CLOSED by ...]` `` is reserved for items **verified already fixed by separate, completed work**.
   Only flip a `PICKED UP` tag to `CLOSED` in the **implementation** commit, once the corresponding code
   change actually lands — never at story-creation time. This was already applied correctly at this
   story's creation:
   - `deferred-work.md` line 1641 (the `findOverlappingBookings` argument-verification item) tagged
     `` `[PICKED UP by skillars-deferred-55 AC1]` ``.
   - `deferred-work.md` line 1686 (the `InOrder`-only state-assertion item) tagged
     `` `[PICKED UP by skillars-deferred-55 AC2 — re-scoped to AdminVideoServiceTest only...]` `` with the
     re-scoping rationale from "Why this story exists" inlined.
   - `deferred-work.md` line 1695 (the uncaught `IllegalStateException` config-lookup item) tagged
     `` `[PICKED UP by skillars-deferred-55 AC4 — both call sites addressed together...]` ``.
   - `deferred-work.md` line 1698 (the `pg_locks` query broad-match item) tagged
     `` `[PICKED UP by skillars-deferred-55 AC3]` ``.
   This AC's job during **implementation** is to flip those four `PICKED UP` tags to `CLOSED` once
   AC1–AC4 actually land — one commit, matching the code:
   - Once AC1 ships: flip line 1641's tag to `` `[CLOSED by skillars-deferred-55 AC1]` `` with a one-line
     closure note describing the actual fix, keeping the original text below it.
   - Once AC2 ships: flip line 1686's tag to `` `[CLOSED by skillars-deferred-55 AC2]` `` the same way —
     note explicitly that only the `AdminVideoServiceTest` half closed, not `VideoServiceTest` (which was
     never a real gap, per AC2's rationale).
   - Once AC3 ships: flip line 1698's tag to `` `[CLOSED by skillars-deferred-55 AC3]` `` the same way.
   - Once AC4 ships: flip line 1695's tag to `` `[CLOSED by skillars-deferred-55 AC4]` `` the same way.
   - **If a partial implementation lands**, flip only the tags for the ACs that actually shipped — leave
     the rest at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [x] Task 1: `BookingDuplicationServiceTest` argument verification (AC: #1)
  - [x] 1.1 Add the `verify(bookingRepository).findOverlappingBookings(...)` call to
    `duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable`, per AC1's snippet. No new imports
    needed — `eq`/`isNull` already resolve via the file's existing `import static org.mockito.Mockito.*;`.
  - [x] 1.2 Run `mvn -o test -Dtest=BookingDuplicationServiceTest` and confirm green.
- [x] Task 2: `AdminVideoServiceTest` state assertions (AC: #2)
  - [x] 2.1 Add `import com.softropic.skillars.platform.video.contract.OperationalState;` and
    `import static org.assertj.core.api.Assertions.assertThat;` to `AdminVideoServiceTest.java`.
  - [x] 2.2 Add the two `assertThat(...)` lines to
    `deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits`, per AC2's snippet.
  - [x] 2.3 Add `assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);` to
    `deleteVideo_noPendingSession_neverReleasesQuota` too (no session-status assertion needed there — no
    session exists in that test's fixture). `video` is already a local variable in that test method.
  - [x] 2.4 Run `mvn -o test -Dtest=AdminVideoServiceTest` and confirm both tests green.
- [x] Task 3: `BookingServiceConcurrencyIT` lock-query precision (AC: #3)
  - [x] 3.1 Replace `awaitAnotherSessionBlockedOnCoachProfileLock`'s query with the narrowed version, per
    AC3's snippet.
  - [x] 3.2 Run `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` and confirm all 4 tests
    green. Run both race tests standalone 3-5 times each to confirm no new flakiness.
- [x] Task 4: `StripePaymentGateway` config-lookup safety (AC: #4)
  - [x] 4.1 Wrap both `configService.getString(...)` calls in `chargeAndCapture` in the shared
    `try`/`catch (IllegalStateException e)` block, per AC4's snippet.
  - [x] 4.2 Add the two new tests to `StripePaymentGatewayTest`, per AC4's snippets.
  - [x] 4.3 Run `mvn -o test -Dtest=StripePaymentGatewayTest` and confirm all tests green (existing plus
    the two new ones).
- [x] Task 5: Ledger hygiene (AC: #5) — apply all annotations described in AC5 to `deferred-work.md`.

### Review Findings

Code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) of the implementation diff, 2026-08-22.

- [x] [Review][Patch] `StripePaymentGateway.chargeAndCapture`'s `new BigDecimal(commissionRateRaw)` still sits
  outside AC4's new `try`/`catch (IllegalStateException e)` block — a malformed (non-numeric)
  `platform.commission.rate` config value throws an unwrapped `NumberFormatException`, undermining AC4's own
  stated goal ("fail predictably... instead of throwing an uncaught `IllegalStateException`") for a directly
  adjacent failure mode of the same two config reads. Independently found by both Blind Hunter and Edge Case
  Hunter. [`src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:43-53`]
  **Resolved:** `new BigDecimal(...)` moved inside the `try` block; the `catch` now catches
  `IllegalStateException | NumberFormatException`, so a malformed value maps to the same
  `PaymentGatewayException("payment.configurationUnavailable", e)` as a missing key. New regression test
  `StripePaymentGatewayTest#chargeAndCapture_malformedCommissionRateConfig_throwsPaymentGatewayException`
  added (9/9 green in the file).
- [x] [Review][Patch] `sprint-status.yaml`'s `skillars-deferred-55` entry was correctly flipped to status
  `review`, but its own inline comment (and the file's top `last_updated` line) still narrate only
  "story created... status ready-for-dev" with no mention of the dev-story implementation that just
  landed — stale bookkeeping, not a design decision. [`_bmad-output/implementation-artifacts/sprint-status.yaml:2,1267`]
  **Resolved:** both comments rewritten to narrate the dev-story-implementation-complete + code-review-complete
  state, with the prior story-creation text preserved as `Prior: ...`.
- [x] [Review][Patch] This story's own Change Log lists "dev-story implementation complete, status review"
  before "story-review adjustments applied, status remains ready-for-dev" — backwards, since the
  story-review pass (against the pre-implementation draft) necessarily happened before the implementation
  it fixed could be built. Every other story in this ledger orders these created → reviewed → implemented.
  [this file's own Change Log table]
  **Resolved:** the two rows swapped; a new final row documents this code-review pass and its resolutions.
- [x] [Review][Patch] (Low priority) The two new `StripePaymentGatewayTest` failure-path tests
  (`chargeAndCapture_missingCommissionRateConfig_...`, `chargeAndCapture_missingCurrencyConfig_...`) assert
  only the thrown exception's type and error code — neither asserts `.getCause()` to prove the causal chain
  is preserved (the actual point of `PaymentGatewayException`'s `(errorCode, Throwable)` constructor), and
  neither verifies `stripeClient` is never reached once config resolution fails.
  [`src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java`]
  **Resolved:** both tests now capture the stubbed `IllegalStateException` and assert
  `e.getCause()).isSameAs(configError)`, plus `verify(stripeClient, never()).createPaymentIntent(any(), any())`.

**Dismissed as noise (11, all independently re-verified against the live repo before dismissal):**
`PaymentGatewayException`'s `(String, Throwable)` constructor was claimed possibly-missing by a
no-project-access reviewer — confirmed present at `PaymentGatewayException.java:12-15`, compiles fine; a
claimed disagreement between `deferred-work.md`'s "header" and `sprint-status.yaml` about this story's
stage — `deferred-work.md` has no such header text at all, the reviewer conflated two different files (the
real, narrower issue is captured as the patch above); the four `[CLOSED by skillars-deferred-55 ACn]` ledger
tags were claimed premature/matching a past mistake — independently re-verified each closure note accurately
describes code that is actually present in the diff, and flipping `PICKED UP`→`CLOSED` at dev-story-implementation
time (not waiting for code review) matches this project's own explicit, established convention
(`skillars-deferred-53`'s AC4 precedent); the narrowed `pg_locks` `EXISTS` clause was claimed to lack a
`locktype` predicate and match "any lock on the table" — true in the abstract but inapplicable here, since
`skillars-deferred-53`'s own prior story-review already confirmed `findByIdForUpdate` is the only lock
either race test's suspender ever acquires; the hardcoded `'marketplace.coach_profiles'` string literal was
claimed a new "silent" brittleness — a bad `::regclass` cast throws a loud SQL error, not a silent failure,
and this same file already hardcodes identical schema-qualified table names throughout its `@BeforeEach`;
referencing `BookingService.ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` in the new `verify(...)` call was
claimed a cross-class coupling smell — backwards, since that constant is the actual shared value the
production code passes, and referencing it (not duplicating it as a literal) is the correct DRY choice; the
two new `StripePaymentGatewayTest` tests' duplicated 4-line `CoachStripeAccount` setup was flagged as
missing a shared helper — matches this project's own repeatedly-documented convention of not abstracting
duplication across only two test methods (`skillars-deferred-49`/`-53` precedent); the new
`payment.configurationUnavailable` error code was flagged as missing an i18n/locale message — re-confirmed
no `PaymentGatewayException` error code in this file has an i18n entry, this is the established, accepted
pattern for this exception class; the new state assertions' dependency on `video`/`session` being the exact
mutated object references was flagged as unconfirmable from a diff hunk alone — independently re-verified
(both at story-creation time and now) that `AdminVideoService.deleteVideo` re-fetches via the same mocked
`videoRepository.findById` stub, returning the same instance; the new `log.error(...)` before rethrow was
flagged as a double-logging risk — matches the pre-existing, unchanged `catch (StripeException e) {
log.error(...); throw ...; }` pattern later in the same method, not a new risk; and a claimed gap that no
test proves `currency` is never queried once `commissionRate` throws first — Java's sequential control flow
already guarantees this trivially (an exception on the first statement aborts the block before the second
runs), so there is no realistic regression such a test could catch.

## Dev Notes

- **This story bundles four independent, decision-light findings — it is not a single coherent feature.**
  AC1, AC2, and AC3 are each a test-only hardening in a different file/module; AC4 is the one production
  code change, fully independent of the other three.
- **AC1/AC2/AC4 touch `*Test` classes run under Surefire (`mvn -o test`)**; **AC3 touches an `*IT` class
  run under **Failsafe**, bound to `integration-test`/`verify`, **not** `mvn -o test`.** Use
  `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` for AC3 and confirm a
  `target/failsafe-reports/...txt` report was actually written — this gotcha has tripped up prior stories
  in this same ledger and is worth restating every time a story touches both kinds of test class.
- **AC2 is deliberately narrower than the ledger item's literal text.** The item names both
  `VideoServiceTest` and `AdminVideoServiceTest`; this story's creation re-verified both and found only the
  latter has a real, closable gap (see "Why this story exists" and AC2's own rationale for the full
  argument). Do not add a redundant assertion to `VideoServiceTest` under this AC — there is nothing there
  to close.
- **AC4's new error code, `payment.configurationUnavailable`, is not wired into any frontend error-key
  mapping.** Checked: only `payment.coachStripeNotConfigured` has a dedicated frontend branch
  (`BookingRequestPage.vue:500`); every other `PaymentGatewayException` error code in this file
  (`payment.lifecycleFailure`, `payment.refundFailed`, etc.) has no dedicated frontend handling either, and
  none of them appear in any backend i18n properties file (`grep` of `src/main/resources/i18n/*.properties`
  for any of these keys returns zero hits — error codes are returned raw and either handled by a specific
  frontend branch or fall through to a generic fallback). This new code follows the exact same,
  already-established pattern; no frontend change is needed or in scope.
- **Do not touch `ConfigService.getString`'s contract itself, or add format validation on the currency
  config value.** Both are separately-tracked, already-considered-and-declined scope per the ledger item's
  own text and `skillars-deferred-53`'s prior review — this story only changes how `StripePaymentGateway`
  reacts to `getString`'s existing, unchanged `IllegalStateException` contract.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.
- **No frontend changes in this story.** All four ACs are backend-only (test code + one production method).

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` —
  one new `verify(...)` call in an existing test, no new imports needed (AC1).
- `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java` — two new
  imports, two new assertion lines in one existing test plus one more assertion line in its sibling test
  (AC2). `VideoServiceTest.java` is **not** modified.
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` — one
  SQL query string changed inside the existing private helper (AC3).
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` —
  `chargeAndCapture`'s two `configService.getString(...)` calls wrapped in a new `try`/`catch` (AC4). No
  new imports (`PaymentGatewayException` already imported).
- `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java` — two new
  imports, two new test methods (AC4).
- `_bmad-output/implementation-artifacts/deferred-work.md` — four annotations (AC5).
- No database migrations, no frontend files, no changes to any `*Resource`/`*Controller` class in this
  story.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1641, section `## Deferred from:
  code review of skillars-deferred-50-...` — this story's AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1686, section `## Deferred from:
  code review of skillars-deferred-52-...` — this story's AC2 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1698, section `## Deferred from:
  code review of skillars-deferred-53-...` — this story's AC3 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1695, same section — this story's
  AC4 source]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:56-88`
  — `duplicateNextWeek`, AC1's target's production logic]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:45-88` —
  `deleteVideo`, AC2's target's production logic]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:392-415` —
  `failTranscoding`, cited in AC2's rationale for why `VideoServiceTest` is left unchanged]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:407-423`
  — `awaitAnotherSessionBlockedOnCoachProfileLock`, AC3's target]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:34-102`
  — `chargeAndCapture`, AC4's target]
- [Source: `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:59-66` —
  `getString`'s `IllegalStateException` contract, unchanged by this story]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/contract/exception/PaymentGatewayException.java`
  — the `(errorCode, Throwable cause)` constructor AC4 reuses]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]
- [Source: `_bmad-output/project-context.md` — AssertJ-for-assertions testing rule, cited in AC2]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -o test -Dtest=BookingDuplicationServiceTest` — 7/7 green (AC1).
- `mvn -o test -Dtest=AdminVideoServiceTest` — 2/2 green (AC2).
- `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` — 4/4 green, `target/failsafe-reports/...BookingServiceConcurrencyIT.txt` written (AC3).
- `mvn -o integration-test -Dit.test='BookingServiceConcurrencyIT#createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable+acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable'` run standalone 3 times — 2/2 green each round, no flakiness observed (AC3).
- `mvn -o test -Dtest=StripePaymentGatewayTest` — 8/8 green, including the two new config-failure tests (AC4).

### Completion Notes List

- AC1: added the precise `verify(bookingRepository).findOverlappingBookings(...)` call to
  `BookingDuplicationServiceTest#duplicateNextWeek_overlapsAnotherBooking_throwsSlotUnavailable`, matching
  the exact arguments `BookingDuplicationService.duplicateNextWeek` computes. No new imports needed. Ledger
  line 1641 flipped `[PICKED UP]` → `[CLOSED by skillars-deferred-55 AC1]`.
- AC2: added `assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED)` and
  `assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED)` to
  `AdminVideoServiceTest#deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits`, and the
  video-state assertion alone to the sibling `deleteVideo_noPendingSession_neverReleasesQuota`.
  `VideoServiceTest` deliberately left unchanged, per AC2's own rationale. Ledger line 1686 flipped
  `[PICKED UP]` → `[CLOSED by skillars-deferred-55 AC2]`, noting the `VideoServiceTest` half was never a
  real gap.
- AC3: narrowed `BookingServiceConcurrencyIT`'s `awaitAnotherSessionBlockedOnCoachProfileLock` `pg_locks`
  query with an `EXISTS` clause requiring the blocked pid to also hold a lock on
  `marketplace.coach_profiles::regclass`. All 4 IT tests green; the two race tests that exercise this
  helper additionally re-run standalone 3 times with no new flakiness. Ledger line 1698 flipped
  `[PICKED UP]` → `[CLOSED by skillars-deferred-55 AC3]`.
- AC4: wrapped `StripePaymentGateway.chargeAndCapture`'s two `configService.getString(...)` calls
  (`commissionRate`, `currency`) in one `try`/`catch (IllegalStateException e)`, mapping to a new
  `PaymentGatewayException("payment.configurationUnavailable", e)`. Both call sites addressed together per
  the ledger item's own instruction; no change to `PaymentGatewayException` or `ConfigService.getString`
  needed. Two new `StripePaymentGatewayTest` tests added (missing commission-rate config, missing currency
  config), both asserting the mapped error code. Ledger line 1695 flipped `[PICKED UP]` → `[CLOSED by
  skillars-deferred-55 AC4]`.
- AC5: all four ledger annotations applied as described above — no still-unfixed item left claiming
  `CLOSED` (all four ACs shipped in full).
- No production behavior changed except AC4 (`StripePaymentGateway.chargeAndCapture`'s config-lookup error
  handling); AC1–AC3 are test-only hardening. No frontend changes, no database migrations. `mvn verify` not
  run, per `docs/validation-strategy.md` — targeted test/IT commands above sufficed.
- **Code review follow-up (Blind Hunter + Edge Case Hunter + Acceptance Auditor):** 4 patch findings, all
  fixed — see "Review Findings" above for each finding's resolution. Net code change: `StripePaymentGateway`'s
  `new BigDecimal(...)` parse moved inside AC4's `try`/`catch`, now also catching `NumberFormatException`.
  Net test changes: `StripePaymentGatewayTest`'s two config-failure tests gained `.getCause()` +
  `verify(..., never())` assertions, plus one new test
  (`chargeAndCapture_malformedCommissionRateConfig_throwsPaymentGatewayException`) proving the newly-widened
  catch. `mvn -o test -Dtest=StripePaymentGatewayTest` — 9/9 green. `sprint-status.yaml` bookkeeping and this
  story's own Change Log ordering corrected (non-code).

### File List

- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` (AC4)
- `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java` (AC4)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)

## Change Log

| Date | Change |
|---|---|
| 2026-08-22 | Story created via story-creation process, bundling four items re-mined from the recently-active tail of `deferred-work.md` (post-`skillars-deferred-49`) after confirming everything before that point is already accounted for by prior stories' own creation notes. Three test-assertion-hardening items (`BookingDuplicationServiceTest` argument verification, `AdminVideoServiceTest`/`VideoServiceTest` resulting-state assertions, `BookingServiceConcurrencyIT`'s lock-query precision) plus one small production fix explicitly scoped by its own ledger text to "address both call sites together" (`StripePaymentGateway`'s two unguarded `configService.getString(...)` calls). All four re-verified against live code at creation time (exact line numbers cited in each AC). AC2 was re-scoped narrower than its source ledger item's literal text during creation: `VideoServiceTest`'s half of that item was found, on inspection, to already be as strong as a mockist unit test of that method can be — only `AdminVideoServiceTest`'s half is a genuine, closable gap. One item (`1687`, possible additional `.distinct()` issues in other `GdprExportService` builder methods) was investigated per its own "worth a follow-up grep" text — a grep found `.distinct()` used in exactly one place in that file, already fixed by `skillars-deferred-52` — and left un-annotated, not picked up, since there was nothing further to act on. `skillars-deferred-54`'s own deferred finding (`handlePackBasedBooking`'s narrow catch clause) was checked and confirmed still not reachable via any current throw site — left un-annotated, not picked up, per this project's established convention of not defending against a hypothetical future signature change. |
| 2026-08-22 | story-review adjustments applied, status remains ready-for-dev. `story-review.md` filed 2 findings against the draft, both fixed. AC3's lock-query-narrowing premises (Postgres always acquiring a table-level lock before a row-level wait becomes visible, `marketplace.coach_profiles` as the exact schema-qualified name, `deferred-53`'s prior confirmation that `findByIdForUpdate` is the only lock acquired before the coach-row lock in both race tests), AC4's config-safety fix (neither of `chargeAndCapture`'s two callers catches `IllegalStateException` specifically, and the existing `catch (PaymentGatewayException e)` at the `PaymentLifecycleService` call site already does the right thing for a charge that never reached Stripe), and AC5's ledger-tag state were all independently re-verified and confirmed accurate — no changes needed there. Finding 1/Medium: AC2 added state assertions to `deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits` only, leaving its sibling `deleteVideo_noPendingSession_neverReleasesQuota` with no assertion on the video's resulting state even though `v.setOperationalState(DELETED)` executes unconditionally in production, before the session lookup — a refactor that moved the state-transition inside the session-lookup branch would silently break the no-session path undetected — added the same `video.getOperationalState()` assertion to that sibling test (Task 2.3). Finding 2/Low: AC1 instructed adding `import static org.mockito.ArgumentMatchers.eq;`/`isNull;`, but both already resolve via the file's existing `import static org.mockito.Mockito.*;` wildcard (`Mockito extends ArgumentMatchers`; `eq(...)` is already called unqualified elsewhere in the same file) — dropped the unnecessary import instruction from AC1 and Task 1. |
| 2026-08-22 | dev-story implementation complete, status review. AC1 added the exact-argument `verify(...)` call to `BookingDuplicationServiceTest` (7/7 green). AC2 added resulting-state assertions to both `AdminVideoServiceTest` tests (2/2 green), `VideoServiceTest` left unchanged as scoped. AC3 narrowed `BookingServiceConcurrencyIT`'s lock-query with an `EXISTS`-on-`coach_profiles` clause (4/4 IT green; the two race tests additionally re-run standalone 3 times with no flakiness). AC4 wrapped `StripePaymentGateway.chargeAndCapture`'s two config-lookup calls in one `try`/`catch(IllegalStateException)` mapped to a new `PaymentGatewayException("payment.configurationUnavailable", e)`, with two new `StripePaymentGatewayTest` tests (8/8 green). AC5 flipped all four ledger tags from `[PICKED UP by skillars-deferred-55 ACn]` to `[CLOSED by skillars-deferred-55 ACn]`. No production behavior changed besides AC4; no frontend or database changes. `mvn verify` not run per `docs/validation-strategy.md`. |
| 2026-08-22 | code review complete (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 4 patch findings, all fixed: (1) `StripePaymentGateway.chargeAndCapture`'s `new BigDecimal(commissionRateRaw)` moved inside AC4's `try`/`catch`, now also catching `NumberFormatException`, so a malformed (non-numeric) `platform.commission.rate` value fails the same predictable way as a missing key instead of throwing unwrapped; (2) `sprint-status.yaml`'s stale bookkeeping (top `last_updated` comment and this story's own `development_status` inline comment) updated to narrate the dev-story implementation, not just story creation; (3) this Change Log reordered to created → reviewed → implemented, matching this ledger's established convention; (4, low priority) the two new `StripePaymentGatewayTest` config-failure tests strengthened with a `.getCause()` assertion proving the causal chain is preserved and a `verify(stripeClient, never()).createPaymentIntent(any(), any())` proving Stripe is never reached once config resolution fails; a third new test, `chargeAndCapture_malformedCommissionRateConfig_throwsPaymentGatewayException`, added to prove finding (1)'s fix (`StripePaymentGatewayTest` now 9/9 green). 11 further findings dismissed as noise after independent re-verification against the live repo — see the story's own "Review Findings" section for the full list. |
| 2026-08-22 | marked done, all changes committed and pushed for PR. |
