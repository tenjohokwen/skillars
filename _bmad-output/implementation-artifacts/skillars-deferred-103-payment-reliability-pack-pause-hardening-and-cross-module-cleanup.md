# skillars-deferred-103: Payment Reliability + Pack-Pause Hardening + Cross-Module Cleanup

**Status:** done | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-103
**Branch:** `story/deferred-103-payment-pack-pause-hardening`
**Created:** 2026-09-09
**Base:** master @ `ef37c039` (immediately after `skillars-deferred-102` (#159) + the post-102 ledger prune (#160))

---

## Story Overview

As the Skillars platform team,
I want the remaining verified-open reliability and correctness gaps in `deferred-work.md` fixed,
so that the payment lifecycle, pack-pause flow, and a handful of cross-module rough edges stop
relying on undocumented assumptions before the platform carries real volume.

A cross-module story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`.

The **"genuine one-off bugs & gaps"** class in that ledger is **exhausted** —
`skillars-deferred-91` through `-102` picked it clean, and the `deferred-100`, `-101` and `-102`
full-file audits all say so. This story's own re-mine against HEAD (`ef37c039`) confirms it: what
remains is overwhelmingly `[DECIDED]` / `[DISMISSED]` (do not re-litigate), "no dev agent can close
this" (native DE/FR register review, parent legal copy), carved-out initiatives
(`frontend-test-framework-initiative` → its own story `deferred-104`; completion-gated coach payout
= `deferred-91` AC5 Part B; the pre-production migration rebaseline), spec-designed tradeoffs,
test-fixture-only concerns, and speculative / load-dependent notes.

**Many ledger bullets that looked open turned out to be stale/fixed at HEAD** and were confirmed
during story creation, not picked up:

| Ledger bullet | HEAD reality |
|---|---|
| `skillars-6-5` Run2 Def17 (`AdminVideoService.deleteVideo` `release()` inside `TransactionTemplate` kills delete tx) | Already restructured — release runs OUTSIDE any tx, catch + retry-next-call. `[DECIDED 2026-08-28 skillars-deferred-81]` |
| `skillars-6-2` Def22 (`UploadSessionExpiryScheduler` non-atomic release-then-EXPIRED) | Already restructured (AC-5 comment: release outside `@Transactional`, retry next cycle). Same `[DECIDED 2026-08-28]` class |
| `skillars-1-2` W4 (`onSessionExpired` clears username, no redirect) | `MainLayout.vue:340` now `router.push('/login')` |
| `skillars-3-4` + `skillars-3-7` D2 (polling no backoff / SSE heartbeat resets retry counter) | `booking.store.js` now has `delays = [1000,2000,4000,8000,16000,30000]` exponential backoff + `POLL_FLOOR_MS` |
| `skillars-4-1` D6 (new coach with no profile → 404 on private drill list) | `DrillLibraryService.java:97` now `catch (ResourceNotFoundException ignored) {}` on the private path |
| `skillars-3-9` W3 (`parentName` null in `getParentBookings`) | `BookingService.java:546` now batches `resolveParentNames(parentIds)` and uses `.getOrDefault(..., "Unknown Parent")` |
| `skillars-7-2` Group 2 D1 (non-atomic `existsById` idempotency in `onBookingAccepted`) | Now `isSettled(...)` + `hasReservation(...)` guards inside `@Transactional(REQUIRES_NEW)`; the "bare SELECT outside TX" shape is gone |

Per the project-owner bucket priority (messaging → video → Database/Performance → payment → SLU →
Platform/Admin → Frontend/UX → Payment/Stripe → Infrastructure/Deployment → booking → drills) and the
"no small stories" instruction, this story bundles the twelve items that survived that verification:

1. **Payment reliability** — `createTier` has no `@Transactional` and no constraint-violation
   handling (`skillars-7-2` G2 D3); `/coaches/me/strikes` returns an unbounded list
   (`skillars-7-3` D3); `handlePackBasedBooking`'s bare `catch (RuntimeException)` collapses a
   programming bug into the "expected business failure" path (`skillars-deferred-56`); the
   pack-expiry warning email is stamped-then-published on `AFTER_COMMIT`, so a mail-send failure
   silently loses the warning (`skillars-deferred-15` D1).
2. **Pack-pause hardening** (`skillars-11-1`, project-owner decision D1 below — safe subset only) —
   the `pauseStartDate` "in the past" check truncates to the UTC day boundary (D3); the
   `pack.pause.maxDays` config read has no defensive default (D4); missing coach/parent records are
   swallowed with `.orElse(null)` / `.orElse("")` producing a blank-email notification (D2).
3. **Exception-message UX** (`skillars-deferred-66`) — ~18 `CONCURRENT_MODIFICATION` throw sites use
   the imperative string `"Booking status changed concurrently — retry"`, which reads as an
   instruction to the end user to click again.
4. **Frontend** (`skillars-deferred-11`) — `PaymentMethodCard.vue`'s `stripeUnavailable` state has
   no retry affordance short of a full page reload.
5. **Messaging** (`skillars-8-4` W5) — the two report endpoints carry `@PreAuthorize(IS_AUTHENTICATED)`
   rather than a party-scoped expression; resolve or record the decision.
6. **Database / view** (`skillars-7-2` G1 D1) — `payment.parent_credit_balance` yields **no row**
   (not a zero-balance row) for a parent with no ledger history — a latent trap for any native-SQL
   consumer.
7. **Ledger hygiene** — the `deploy-1-3` LGTM `mkdir -p` bullet's premise is false at HEAD
   (`provision.sh:349` creates `${DEPLOY_ROOT}/lgtm` unconditionally); delete it and re-mine the
   file for any other now-closed bullet.

---

## Project-owner decisions (captured 2026-09-08 / 2026-09-09 during story creation)

| # | Question | Decision |
|---|---|---|
| D1 | `skillars-11-1` D1–D9 — nine `pausePack` / forfeiture bullets, all "byte-for-byte identical to legacy `SessionPackService.pausePack`; AC4 required mirroring". The legacy system was deleted in `skillars-11-3`, so the mirroring constraint is gone. Harden in this story? | **Yes — the safe subset only.** Fix D3 (timezone-correct past-date check), D4 (defensive config default), D2 (non-silent record resolution). **Leave** D1 (`confirmedCancellationIds` validation), D5 (lock scope), D7/D8 (forfeiture / pausePack TOCTOU) and D9 (stringly-typed status) — those need their own concurrency design and stay as tracked pre-existing gaps. |
| D2 | `skillars-7-1` D4 — `acceptBooking` → PAYMENT_PENDING, then `PaymentLifecycleService` fires PAYMENT_CAPTURED on the destination-charge model (coach paid at booking-confirmation, not session completion). Include a scoped guard here? | **No.** Leave it entirely to the `deferred-91` AC5 Part B payout-architecture story. That story needs `docs/architecture/payout-and-capture-pending.md` D1–D5 signed off first (the doc is still `DRAFT`). This story makes **no** payment-state-machine changes. The `skillars-7-1` D4 ledger bullet stays, pointing at Part B. |
| D3 | `frontend-test-framework-initiative` (stand up Vitest + Vue Test Utils, CI job, first spec — unblocks ~10 recorded coverage gaps). Fold into this story? | **No — its own dedicated story (`skillars-deferred-104`).** This story's frontend change (AC9) is verified by `eslint` + `quasar build` + code reading, as the project has done for ~15 prior stories. Every dependent story reopens its own coverage line once `deferred-104` ships. |
| D4 | Pre-production migration rebaseline (squash `V1..V132`, fold in the lock-unsafe rewrites). Schedule it? | **No — keep it as the standing "future task, no owner" ledger entry.** It is a one-shot pre-first-production-deploy operation; scheduling it now risks churn as more migrations land before the deploy. Revisit when a production deploy date is set. |
| D5 | Confirm out of scope: de-DE / fr-FR native register review; parent legal copy (ToS / privacy / guardian-consent) review; `main.pending_blob_deletions` table drop. | **Confirmed out.** The first two cannot be an AC ("no dev agent can close this"). The table drop is gated on `skillars-deferred-100` being confirmed deployed and the table provably empty in every environment — there is no production deploy yet. All three stay as tracked ledger entries, untouched. |

---

## Acceptance Criteria

> Every AC below was re-verified against HEAD (`ef37c039`) during story creation. Line numbers are
> accurate as of that commit; the dev agent must re-diff the cited lines before editing (they age).

### AC1: `SessionPackPaymentService.createTier` — make the deactivate-then-insert atomic and map the unique-constraint race to 409

- **Task:** Add `@Transactional` to `createTier` and catch the `idx_spt_one_active_per_coach`
  partial-unique-index violation so a concurrent create surfaces as a clean `409 CONFLICT`
  (`payment.tierRaceConflict` or the nearest existing key) instead of a raw 500.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:179-201`
  — `createTier` is **not** annotated (`deactivateTier` immediately below it *is* `@Transactional`).
  It loops `findAllByCoachIdAndIsActiveTrue(coachId)` deactivating each with `saveAndFlush`, then
  `save(tier)` with `setActive(true)`. Two concurrent calls each deactivate, each insert an active
  row; the DB partial unique index `idx_spt_one_active_per_coach` (see
  `V62__session_payment_credit_wallet.sql` / later) rejects the loser with a bare
  `DataIntegrityViolationException` that `ApiAdvice`'s name-keyed maps cannot classify → `500`.
- **Constraint name — confirmed at HEAD:** `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql:58`
  — `CREATE UNIQUE INDEX idx_spt_one_active_per_coach ON payment.session_pack_tiers(coach_id)
  WHERE is_active = true;` (a **partial** unique index). No later migration renames it (grep
  `one_active_per_coach` / `session_pack_tiers` across `db/migration` returns only V62). The
  catch-by-name is therefore safe today — but add a code comment at the catch site naming the
  index as a brittle string dependency, so a future rename is caught in review.
- **Fix approach:**
  - Annotate `createTier` `@Transactional` — without it the deactivate loop and the insert are not
    atomic even single-threaded (a failure mid-loop leaves some tiers deactivated and no new active
    tier). Note `deactivateTier` immediately below it *is* annotated — this is a genuine omission,
    not a style choice.
  - Wrap the `save`/flush in a `try/catch (DataIntegrityViolationException e)`; if the violated
    constraint is `idx_spt_one_active_per_coach` (prefer the codebase's
    `ApiAdvice.CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` registration if that is how sibling races
    are classified — grep for an existing entry; only fall back to a local catch if there is no such
    mechanism) throw an `OperationNotAllowedException` with a `CONFLICT`-class error so the client
    gets 409 + a retryable message. Any other `DataIntegrityViolationException` rethrows unchanged.
  - Do **not** add an application-level pre-check "does an active tier exist" — the DB partial index
    is the correct serialization point; the fix is to classify its failure, not to race it in Java.
  - **Why the deactivate loop is safe under concurrency** (document this in a code comment): the
    guarantee rests on `@Transactional` + the DB partial unique index, **not** on optimistic
    locking. Two racing transactions may both deactivate overlapping sets and both attempt an
    insert; the index lets exactly one insert commit and rejects the other — which AC1 now maps to
    409. There is no lost-update window because neither transaction's writes are visible to the
    other until commit, and the loser's whole transaction (deactivations included) rolls back.
- **Ledger:** `## Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer
  (2026-06-24)` — delete the **D3** bullet (`createTier` TOCTOU). The sibling **D1** bullet in the
  same section is already stale (see the Story Overview table) — delete it too, with a one-line note
  in the AC19-style audit block that `onBookingAccepted`'s idempotency is now `isSettled()` +
  `hasReservation()` inside `@Transactional(REQUIRES_NEW)`.
- **Test:** `@Testcontainers` IT in `SessionPackPaymentServiceIT` (or the nearest existing class):
  two threads on one latch both call `createTier(sameCoach, …)`; assert exactly one succeeds and the
  other throws the 409-class exception (not a 500 / raw `DataIntegrityViolationException`), and that
  the coach ends with exactly one `is_active = true` tier. A single-threaded test that
  `createTier` twice sequentially must also leave exactly one active tier (proves the `@Transactional`
  + happy-path deactivation still works).

---

### AC2: `GET /coaches/me/strikes` — paginate the response

- **Task:** Replace the unbounded `List<ReliabilityStrikeResponse>` return with a paginated
  `Page<ReliabilityStrikeResponse>`, following the **exact** pattern of the sibling endpoint named
  below — same param style, same default size, same return wrapper.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java:30-44`
    — `getMyStrikes()` takes no arguments, calls
    `reliabilityStrikeService.getCoachStrikes(currentCoachUserId())` and returns
    `ResponseEntity<List<ReliabilityStrikeResponse>>` with no bound.
    `ReliabilityStrikeService.getCoachStrikes` returns a plain `List`. `CoachReliabilityStrike`
    rows accrue over a coach's lifetime and are never pruned.
  - **Template to copy — `RevenueResource.getCoachTransactions`
    (`src/main/java/com/softropic/skillars/platform/payment/api/RevenueResource.java:51-62`):** a
    `/coaches/me/...` `@GetMapping` that takes `@RequestParam(defaultValue = "0") int page` +
    `@RequestParam(defaultValue = "20") int size`, calls the service with
    `PageRequest.of(page, size)`, and returns `ResponseEntity<Page<TransactionDto>>`. **This
    codebase paginates with explicit `page`/`size` request params, not a `@PageableDefault
    Pageable` argument** — match that. Default size is **20**.
- **Fix approach:**
  - Add `@RequestParam(defaultValue = "0") int page` and `@RequestParam(defaultValue = "20") int
    size` to `getMyStrikes`; call the service with `PageRequest.of(page, size)`; return
    `ResponseEntity<Page<ReliabilityStrikeResponse>>` — byte-for-byte the `getCoachTransactions`
    shape.
  - Push the `Pageable` through `ReliabilityStrikeService.getCoachStrikes` into a
    `Page<CoachReliabilityStrike> findByCoachId(UUID coachId, Pageable pageable)` repository method
    (Spring Data derives it). Map to `Page<ReliabilityStrikeResponse>`.
  - Sort: keep the existing most-recent-first ordering as the default (pass it in the
    `PageRequest.of(page, size, Sort.by(DESC, "createdAt"))` if the current finder does not already
    sort — check `getCoachStrikes`'s current query).
  - Keep `@PreAuthorize` and `@Observed` unchanged.
- **Ledger:** `## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes
  (2026-06-25)` — delete the **D3** bullet. Leave D1 (`buildSort` identical branches) and D5
  (`CoachCancellationHistory.createdAt`) — both untouched by this change.
- **Test:** `ReliabilityStrikeResourceIT` — seed >1 page of strikes for one coach; assert page 0
  returns exactly 20 items newest-first (`assertThat(body.getSize()).isEqualTo(20)` — pin the
  resolved default explicitly), `page=1` returns the remainder, and `totalElements` / `hasNext`
  are correct. Assert the JSON envelope key set matches `getCoachTransactions`'s response (guard
  against an accidental new format).

---

### AC3: `handlePackBasedBooking` — narrow the bare `catch (RuntimeException)` so a programming bug is not logged as an expected business failure

- **Task:** Replace the bare `catch (RuntimeException e)` around `packSessionService.deductSession(...)`
  in `PaymentLifecycleService.handlePackBasedBooking` with the narrowest supertype that covers the
  known business-failure throw sites (pack-exhausted / pack-not-found), so an NPE /
  `IllegalStateException` / other unchecked defect propagates (or is logged distinctly) instead of
  being funneled into the `persistPaymentFailure` + `log.error("Pack session deduction failed…")`
  path that is reserved for expected business outcomes.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:162-175`
  — `private void handlePackBasedBooking(...)` wraps `deductSession(purchaseId)` in
  `catch (RuntimeException e)` and, on **any** `RuntimeException`, logs `"Pack session deduction
  failed: … error={}"` and calls `persistenceService.persistPaymentFailure(bookingId, ZERO, …)`.
  The nested `catch (RuntimeException pfe)` around `persistPaymentFailure` is a separate concern and
  stays. Line 100 has a sibling bare `catch (RuntimeException e)` in `onBookingAccepted`'s caller
  chain — **do not** widen scope to it unless it shares the exact same "business vs defect"
  collapse; note it in the Dev Agent Record if it does and is left alone.
- **Fix approach:**
  - **Read the implementation and Javadoc of `PackSessionService.deductSession` before choosing the
    catch type** — do not infer from the name. Identify its declared / reachable *business*-failure
    exceptions (expect a domain exception such as `PackExhaustedException` /
    `ResourceNotFoundException` / a `SessionPackException` supertype). If `deductSession` can also
    throw `IllegalArgumentException` / `IllegalStateException` for a *caller* mistake (bad id, wrong
    state), those must **not** be in the caught set — they are the exact "programming bug logged as
    business failure" this AC exists to stop.
  - Catch the identified business supertype for the `persistPaymentFailure` path. Let anything else propagate — the
    `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` boundary means an escaped exception
    is logged by Spring's listener error handler with a distinct signature, which is the point.
  - If the codebase's own Dev Notes for `skillars-deferred-56` argued the bare catch was
    deliberate (they did — "narrowest common supertype covering both known throw sites *and any
    future unchecked throw*, deliberately excluding `Error`"): the change here is to **stop covering
    "any future unchecked throw"**. Record the reversal and its rationale (log-signature triage)
    in the Dev Agent Record so the next reviewer does not re-widen it.
- **Ledger:** `## Deferred from: code review of skillars-deferred-56-… (2026-08-22)` — delete the
  `handlePackBasedBooking` bare-`RuntimeException` bullet. Leave the `sprint-status.yaml`
  `last_updated` line-length bullet (separate, project-wide, explicitly out of scope everywhere).
- **Test:** `PaymentLifecycleServiceTest` (Mockito) — stub `deductSession` to throw the business
  exception → assert `persistPaymentFailure` is called and no exception escapes. Stub it to throw
  an `IllegalStateException` → assert `persistPaymentFailure` is **not** called and the exception
  propagates out of `handlePackBasedBooking`. Mutation-check: reverting the catch type to
  `RuntimeException` must flip the second assertion.

---

### AC4: pack-expiry warning email — enqueue atomically, not on `AFTER_COMMIT` after the row is already stamped

- **Task:** Bring `SessionPackExpiryNotifier` / `SessionPackEmailListener`'s expiry-warning path in
  line with the pattern `skillars-deferred-92` AC4 applied to the other 23 transactional emails:
  the enqueue must be atomic with the `expiryWarnedAt` write, so a delivery-layer failure cannot
  permanently lose the warning while `expiryWarnedAt` records "sent".
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java`
  — the class javadoc (`:37`, `:44`) still describes an
  `@TransactionalEventListener(AFTER_COMMIT)` delivery and states `expiryWarnedAt` "is stamped
  inside the very transaction whose" commit triggers the (fallible, unretried) send. Confirm the
  listener phase and whether the send is already routed through the generic `platform.outbox`
  (`BookingEmailListener` / `SessionPackEmailListener` were moved to `BEFORE_COMMIT` +
  `Propagation.MANDATORY` `enqueueEmail` by `deferred-92` AC4/AC29 — check whether the *expiry
  warning* specifically was included or missed).
- **Atomicity semantics — get this right (it is the whole point of the AC):** a
  `@TransactionalEventListener(phase = BEFORE_COMMIT)` runs **inside** the commit sequence, in
  `TransactionSynchronization.beforeCommit()`, *before* the physical commit. If that listener
  throws, the exception propagates and the transaction manager **rolls the producing transaction
  back** (contrast `AFTER_COMMIT`, where a listener exception is logged and swallowed because the
  commit already happened). Combined with `Propagation.MANDATORY` on `enqueueEmail` (which joins the
  *same* transaction rather than starting its own), the outbox INSERT and the `expiryWarnedAt`
  UPDATE are one atomic unit: **enqueue fails → whole transaction rolls back → `expiryWarnedAt` is
  NOT stamped.** This is exactly why `RefundEnqueueListener`
  (`src/main/java/.../platform/payment/service/RefundEnqueueListener.java:33` — verified at HEAD,
  four `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` methods, its own
  javadoc: *"skillars-deferred-101 AC4: dedicated BEFORE_COMMIT listener for BOOKING_REFUND enqueue
  atomicity"*) uses this phase. Do not talk yourself into "BEFORE_COMMIT doesn't abort the parent" —
  it does.
- **Fix approach:**
  - If the expiry-warning email still publishes on `AFTER_COMMIT`: move its enqueue to a
    `@TransactionalEventListener(BEFORE_COMMIT)` listener calling the generic-outbox
    `enqueueEmail` on `Propagation.MANDATORY`, exactly mirroring
    `RefundEnqueueListener` / the 23 `deferred-92` email listeners. The outbox drainer already
    handles retry/backoff after the row is committed.
  - `expiryWarnedAt` continues to be stamped in the producing transaction — that is now correct
    because the enqueue commits atomically with it (per the semantics above).
  - If it turns out the expiry warning was **already** migrated by `deferred-92` and the ledger
    bullet is stale: make **no** code change, and instead delete the `skillars-deferred-15` D1
    bullet with a note in the audit block ("verified migrated by deferred-92 AC4; stamp-then-send
    race no longer exists"). Diff `SessionPackEmailListener` against the `deferred-92` change to be
    certain.
- **Ledger:** `## Deferred from: code review of
  skillars-deferred-15-payment-pending-sweeper-accept-path-integrity (2026-08-05)` — delete the
  **D1** bullet (`SessionPackExpiryNotifier` stamps `expiryWarnedAt` before the `AFTER_COMMIT`
  listener attempts delivery). Leave D2 (double `SELECT … FOR UPDATE` idiom) and D3
  (`findPaymentPendingOlderThan` `updatedAt` assumption) — both explicitly speculative /
  pre-existing.
- **Test:** IT in the pattern of `NotificationEmailOutboxAtomicityIT`:
  - **Happy path** — with the outbox drainer paused, run the expiry-warning path; assert an outbox
    row exists **and** `expiryWarnedAt` is set, both committed.
  - **Enqueue failure → atomic rollback** — make `enqueueEmail` throw inside the BEFORE_COMMIT
    listener; assert the whole producing transaction rolled back: **no outbox row AND
    `expiryWarnedAt` still null** (the warning is not marked "sent"). This is the guarantee the AC
    establishes; if the test shows `expiryWarnedAt` stamped despite the failure, the listener is
    on the wrong phase — fix it, don't accept it.
  - **Drainer send failure → retry** — commit normally, then simulate a drainer send failure;
    assert the outbox row is retained (not deleted) and re-attempted.
  - If the "already migrated by deferred-92" branch applies, no new test — record the
    verification (the diff you compared) in the Dev Agent Record.

---

> **Implementation order for the pack-pause block (AC5–AC7).** `pausePack` is already
> `@Transactional`. Read `PackSessionService.pausePack` end-to-end first — its shape at HEAD:
> lock+load `purchase` (`findByIdForUpdate`) → ownership/active/`pausedUntil` guards →
> `maxDays = configService.getLong("pack.pause.maxDays")` (**AC6**, ~`:141`) →
> `pauseStart.isBefore(Instant.now().truncatedTo(DAYS))` past-date check (**AC5**, ~`:147`) →
> conflict resolution + `cancelDueToPause` loop → apply pause + `save` → **then** the notification
> block that does `coachProfileRepository.findById(coachId).orElse(null)` /
> `userRepository.findById(parentId).map(User::getEmail).orElse("")` (**AC7**, ~`:192-194`).
> The coach is **not loaded** at the AC5 point. `coachId` *is* available early
> (`purchase.getCoachId()`). **Do AC5's early coach load first** — one
> `coachProfileRepository.findById(purchase.getCoachId())` read before the past-date check, reused
> by the AC7 notification block (deletes the duplicate `findById` at `:192`). AC7's fail-fast then
> makes the `coach != null ? … : "Coach"/"UTC"` ternaries at `:192-194` dead — simplify them to
> direct access. AC5 and AC6 are not otherwise ordered relative to each other.

### AC5: `PackSessionService.pausePack` — timezone-correct "pause start is in the past" check (11-1 D3)

- **Task:** Replace the UTC-day-truncated past-date comparison with one that reflects the actor's
  local day, so a pause legitimately starting "today" is not rejected as past for actors ahead of
  UTC (and vice-versa).
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:146-148`
  — `Instant pauseStart = req.pauseStartDate();` then
  `if (pauseStart.isBefore(Instant.now().truncatedTo(ChronoUnit.DAYS))) { …reject… }`.
  `truncatedTo(ChronoUnit.DAYS)` truncates to **UTC** midnight. At this point in the method **no
  `CoachProfile` has been loaded** — the coach `findById` is ~45 lines later in the notification
  block. `coachId` is available as `purchase.getCoachId()`.
- **Fix approach:**
  - Add one `CoachProfile coach = coachProfileRepository.findById(purchase.getCoachId())…` read
    **before** the past-date check and reuse that same instance for the AC7 notification block
    (removing the duplicate lookup at ~`:192`). If the row is absent, this is the same
    data-integrity fault AC7 handles — **fail fast** (do not fall through to a UTC guess for a pack
    whose coach has vanished).
  - Resolve the zone as `coach.getCanonicalTimezone()`. **This field can be blank/null for legacy
    rows** — `skillars-deferred-63` AC6 backfilled *diverged* window rows and `@IanaTimezone`
    guards new writes, but the `skillars-deferred-17`/`-18` implementation outcomes recorded that a
    real coach with a blank `canonicalTimezone` keeps a `"UTC"` fallback on the read side. So:
    `ZoneId zone = hasText(coach.getCanonicalTimezone()) ? ZoneId.of(coach.getCanonicalTimezone())
    : ZoneOffset.UTC;` with a WARN on the fallback, matching the existing read-side pattern (grep
    `"UTC"` fallbacks in `AvailabilityService` / `RevenueReportingService` for the exact idiom).
  - Compare `LocalDate.ofInstant(pauseStart, zone)` against `LocalDate.now(clock.withZone(zone))`
    (inject/observe the codebase's `Clock` if `pausePack` already has one; otherwise
    `LocalDate.now(zone)`) — reject only when strictly before. Keep the existing error key.
  - Do **not** change `req.pauseStartDate()`'s type or the wire contract; only the comparison.
- **Ledger:** `## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)`
  — delete the **D3** bullet. (D1, D5, D7, D8, D9 stay — project-owner decision D1.)
- **Test:** `PackSessionServiceTest` — fixed clock; coach zone `Pacific/Kiritimati` (UTC+14);
  `pauseStartDate` = the coach's "today" but still "yesterday" in UTC → assert **accepted**.
  `pauseStartDate` = the coach's "yesterday" → assert **rejected**. A coach with a blank
  `canonicalTimezone` → the check runs against UTC + a WARN is logged (assert both). Reverting to
  the UTC-truncated check must flip the first assertion.

---

### AC6: `pack.pause.maxDays` config read — defensive default (11-1 D4)

- **Task:** Give `configService.getLong("pack.pause.maxDays")` a defensive default so a missing or
  non-numeric config value degrades to a sane bound instead of throwing out of `pausePack`.
- **Verified at HEAD:**
  - `PackSessionService.java:141` — `long maxDays = configService.getLong("pack.pause.maxDays");`
    (the single-arg form).
  - `ConfigService` lives at
    `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` and
    **already exposes a `getLong(String key, long default)` overload** — it is used widely
    (`configService.getLong("platform.video.deletion.max_attempts", 5L)`,
    `getLong("platform.video.lifecycle.blocked_to_archived_days", 30L)`, etc.). No new overload is
    needed; this is a one-line call-site change.
- **Fix approach:**
  - Replace with `configService.getLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS)` where
    the default is a named `private static final long` constant in `PackSessionService`. Set it to
    the value the seed migration inserts — grep `pack.pause.maxDays` in
    `src/main/resources/db/migration/` and use that exact number so the default matches production
    config.
  - Read the existing 2-arg `getLong` overload: confirm it already falls back on a **non-numeric**
    stored value (not just an absent key). If it only guards absence and would still throw
    `NumberFormatException` on `"abc"`, harden the call site with a `try/catch` (WARN + default) —
    do **not** change `ConfigService` behaviour for every other caller in this story.
- **Ledger:** same section as AC5 — delete the **D4** bullet.
- **Test:** `PackSessionServiceTest` — key absent → uses the default; key present and numeric →
  uses the stored value; key present but `"abc"` → uses the default + WARN logged (this last case
  only if the overload/call-site actually guards it — assert whatever the implementation
  guarantees, and document it).

---

### AC7: `pausePack` + `SessionPackForfeitureScheduler` — do not silently proceed on a missing/blank coach or parent-email (11-1 D2)

- **Task:** Replace the silent `.orElse(null)` (coach) / `.orElse("")` (parent email) fallbacks
  with an explicit outcome: log ERROR naming the id and either skip the notification cleanly
  (scheduler) or fail the operation (`pausePack`) — never send a "Dear , your pack…" email with a
  blank recipient or carry a null coach reference downstream.
- **Verified at HEAD:**
  - `PackSessionService.java:191-194` (the notification block after the pause is applied) —
    `CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);`
    `String parentEmail = userRepository.findById(parentId).map(u -> u.getEmail()).orElse("");`
    `String coachDisplayName = coach != null ? coach.getDisplayName() : "Coach";`
    `String canonicalTimezone = coach != null ? coach.getCanonicalTimezone() : "UTC";`
  - `SessionPackForfeitureScheduler.java:44,46` — identical shape
    (`coachProfileRepository.findById(purchase.getCoachId()).orElse(null)` and
    `…map(User::getEmail).orElse("")`).
  - **Not in scope:** `PackSessionService.java:107` (`.orElse(null)` in `getActivePackId`) — that
    is a legitimate "no active pack found → null" for a read-only helper, unrelated to D2. Leave it.
- **`.orElse("")` also masks a present-but-null email:** `Optional.map` returns an **empty**
  Optional when the mapper yields `null`, so `userRepository.findById(parentId).map(User::getEmail)
  .orElse("")` produces `""` for *both* "no `User` row" and "`User` row with a null `email`" (data
  corruption). Treat them identically: ERROR + skip/fail, and assert both in the test.
- **Fix approach:**
  - `pausePack`: the coach load is now done early (AC5). If it is absent, fail fast with a
    data-integrity error before applying the pause (do not pause a pack whose coach row is gone).
    If `parentEmail` resolves blank/null after the pause is applied, log ERROR naming `parentId`
    and skip the notification only (the pause itself already committed and is correct) — do not
    roll it back for a missing email.
  - `SessionPackForfeitureScheduler`: `log.error("… missing coach id=… / blank parent email for
    parentId=… — skipping notification for purchase=…")` and `continue` the loop — never abort the
    batch, never enqueue an email with a blank recipient.
  - A blank/null `parentEmail` must never reach `enqueueEmail` — guard immediately before it at
    every call site touched here.
  - Once the ternaries at `:192-194` have a guaranteed-non-null `coach` (AC5 fail-fast), replace
    `coach != null ? coach.getX() : "…"` with direct `coach.getX()`.
  - Match the codebase's precedent for orphaned-profile handling (`skillars-deferred-83` /
    `-81` AC4: "explicit error, not a placeholder") — reference it in the Dev Agent Record.
- **Ledger:** same section as AC5 — delete the **D2** bullet.
- **Test:** `PackSessionServiceTest` — (1) coach lookup empty → `pausePack` throws the
  data-integrity error, pause not applied, no email enqueued; (2) coach present, `User` present but
  `email == null` → pause applied, ERROR logged, no email enqueued; (3) coach present, `User`
  missing → same as (2). `SessionPackForfeitureSchedulerTest` — one purchase with a missing coach
  and one with a null-email parent among several healthy ones → each logs ERROR and is skipped,
  the healthy ones still process, batch completes.

---

### AC8: `CONCURRENT_MODIFICATION` exception copy — stop instructing the end user to "retry" (`skillars-deferred-66`)

- **Task:** Reword the ~18 `new OperationNotAllowedException("Booking status changed concurrently
  — retry", e, BookingError.CONCURRENT_MODIFICATION)` throw sites to copy that does not read as a
  user instruction (the auto-retry is internal; the user sees only the final failure), and align
  the `booking.concurrentModification` i18n key across `en`, `de-DE`, `fr-FR` to match.
- **Verified at HEAD:** the exact string `"Booking status changed concurrently — retry"` appears at:
  - `BookingCompletionService.java:58,76,89,102,119,160,190`
  - `RescheduleService.java:452`
  - `BookingService.java:395,460,672,803,827,863`
  - `BookingBatchService.java:441` returns `BookingError.CONCURRENT_MODIFICATION.getErrorCode()` (no
    message string — leave the enum, it is fine).
  - `BookingError.java:100` maps `CONCURRENT_MODIFICATION -> "booking.concurrentModification"`.
- **Fix approach:**
  - **Two distinct strings — do not conflate them:**
    - The Java exception `message` argument is a **developer/log** string. Change it to a terse
      non-imperative phrase, e.g. `"booking status changed under concurrent modification"`. It is
      never shown to an end user.
    - The **user-facing** text is the i18n value for `booking.concurrentModification`. **Approved
      wording** (implement exactly this intent; the dev may adjust phrasing for natural language
      but code review will reject any imperative aimed at the user — no "retry", "try again",
      "click again", "reload and…"):
      - `en-US`: `"The booking was just updated by someone else. Refresh to see its current
        status."`
      - `de-DE`: the formal-`Sie` equivalent (e.g. *"Die Buchung wurde soeben von einer anderen
        Person geändert. Aktualisieren Sie die Seite, um den aktuellen Status zu sehen."*).
      - `fr-FR`: the formal equivalent (e.g. *"La réservation vient d'être modifiée par une autre
        personne. Actualisez la page pour voir son statut actuel."*).
    - "Refresh"/"Aktualisieren"/"Actualisez" describes what the UI will show, not a demand that the
      user recover a failed action — that is the line this AC draws.
  - Keep the Java message identical across all ~18 sites — introduce a `BookingError`-level
    `private static final String` constant (or a tiny throw helper) so they cannot drift again
    (the ledger's own `skillars-deferred-66` note: "consistency… is the point").
  - Update `booking.concurrentModification` in all three bundles
    (`src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`). `de-DE` stays formal `Sie`; `fr-FR`
    stays formal — match each bundle's existing register; introduce no informal forms and no
    `{placeholder}` drift.
  - Run `prettier --check` on the touched `.js` bundle files (mandatory per project rules).
- **Parity gate — what actually exists:** the backend `MessageBundleParityTest`
  (`src/test/java/com/softropic/skillars/i18n/MessageBundleParityTest.java`, runs in the `test`
  phase, CI-gated) covers the backend `messages_{en,de,fr}.properties` bundles — relevant **only
  if** this change also touches a `messages_*.properties` key (check whether
  `booking.concurrentModification` resolves from a backend bundle as well as the frontend one; if
  so, keep all three `.properties` in sync too). The **frontend** `src/frontend/src/i18n/*/index.js`
  bundles have **no automated parity test today** (that gap is part of `skillars-deferred-104`) —
  keep them key-aligned by a manual key-count diff across the three files and rely on `eslint` +
  `quasar build`.
- **Ledger:** `## Deferred from: code review of skillars-deferred-66 (2026-08-25)` — delete the
  "imperative 'retry' language" bullet. That is the section's only bullet → remove the header too.
- **Test:** no new backend test for a copy change; assert (existing IT or a new tiny one) that a
  `CONCURRENT_MODIFICATION` response still carries `errorKey = "booking.concurrentModification"`
  (guard against an accidental key rename). If a backend `messages_*.properties` key was touched,
  `MessageBundleParityTest` must stay green. Frontend: `prettier --check` + manual key-count diff of
  the three `index.js` bundles.

---

### AC9: `PaymentMethodCard.vue` — add a retry affordance to the `stripeUnavailable` state (`skillars-deferred-11`)

- **Task:** In the `v-else-if="stripeUnavailable"` branch, render a "Try again" button that
  re-runs the component's init (`fetchStripeConfig` + `fetchSavedPaymentMethod`) and clears
  `stripeUnavailable` on success, so a transient Stripe/network failure is recoverable without a
  full page reload.
- **Verified at HEAD:** `src/frontend/src/components/payment/PaymentMethodCard.vue`
  — `:7` `<div v-else-if="stripeUnavailable" class="text-body2 text-secondary">` renders text only,
  no action. `:79` `const stripeUnavailable = ref(false)`. `:102,:113,:135` set it `true` in catch
  blocks. `:190` `onMounted`: `await Promise.all([paymentStore.fetchStripeConfig(),
  paymentStore.fetchSavedPaymentMethod()])`.
- **Fix approach:**
  - Extract the `onMounted` init body into a top-level `async function loadStripe()` in
    `<script setup>` (not a `ref`-wrapped function, not a method object — plain top-level function
    per the project's `<script setup>` convention); call it from `onMounted` and from the new
    retry button.
  - `loadStripe()` sets `stripeUnavailable.value = false` before trying, and `true` again on
    failure — so a successful retry flips the UI back to the normal card state.
  - Button: `<q-btn flat no-caps :label="t('common.retry')" @click="loadStripe" :loading="…" />`
    inside the unavailable branch. Add a `common.retry` i18n key to all three bundles if one does
    not exist (grep first — `common.retry` / `common.tryAgain` may already be there).
  - Keep `<script setup>` + `async/await` (project rules). Disable the button while a retry is in
    flight.
- **Ledger:** `## Deferred from: code review of skillars-deferred-11-stripe-card-collection
  (2026-08-04)` — delete the `stripeUnavailable` "no retry affordance" bullet. **Leave** the "no
  frontend tests" bullet — it is blocked on `skillars-deferred-104` (the test-framework story) per
  decision D3.
- **Test:** `eslint` + `prettier --check` + `quasar build` clean on `PaymentMethodCard.vue` and any
  touched i18n bundle. Manual dev-server exercise (documented in Dev Notes as the project's
  established verification path for a frontend-only change pre-`deferred-104`): force a
  `fetchStripeConfig` rejection, confirm the retry button appears and a successful retry restores
  the card.

---

### AC10: messaging report endpoints — resolve or record the `IS_AUTHENTICATED` authorization decision (`skillars-8-4` W5)

- **Task:** `MessagingResource.reportMessage` and `reportConversation` carry
  `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)` rather than a party-scoped expression. Either
  tighten the method-security expression to a party check, **or** add a short comment at both sites
  recording that `IS_AUTHENTICATED` + the `MessagingReportService.verifyIsParty` service-layer gate
  is the deliberate, consistent module pattern (403 is preserved either way).
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java:157-189`
  — `:158` `reportMessage` and `:174` `reportConversation` both
  `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)`; the two `HAS_PARENT_ROLE` endpoints at
  `:124,:132` show the resource does use role-scoped expressions where appropriate. Every other
  endpoint in the file (`:57,:86,:96,:109,:146,:189`) is `IS_AUTHENTICATED`, with the real gate in
  the service.
- **Fix approach (pick one, record which and why in the Dev Agent Record):**
  - **(a) Tighten — strongly preferred:** if a reusable method-security bean/expression for "is a
    party to this conversation" already exists (grep `@messagingSecurity` / a `PermissionEvaluator`
    / a `hasPermission` / a `@bean.method(...)` SpEL usage in other `@PreAuthorize` annotations),
    apply it at both sites. Do **not** invent new security infrastructure for two endpoints — this
    is only worth doing if the expression is already there.
  - **(b) Document — fallback only:** if no such reusable expression exists, add a 2-line comment
    at both annotations ("Party check is enforced in `MessagingReportService.verifyIsParty`;
    consistent with every other endpoint in this resource — `IS_AUTHENTICATED` here is deliberate,
    not an oversight, see `skillars-8-4` W5 / `skillars-deferred-103` AC10") and treat the item as
    **decided-not-fixed**.
  - This is defense-in-depth only — the service-layer 403 does not change either way. Keep scope
    minimal.
- **Ledger:** `## Deferred from: code review of skillars-8-4 (2026-06-27)`:
  - If **(a)** shipped — delete the **W5** bullet (section's only bullet → remove the header).
  - If **(b)** shipped — **do not delete** the bullet. Retag it in place:
    `[DECIDED 2026-09-09 (skillars-deferred-103 AC10): IS_AUTHENTICATED + MessagingReportService
    .verifyIsParty service-layer gate is the deliberate module-wide pattern; no reusable
    party-scoped method-security expression exists to swap in. Code comment added at both sites.]`
    The file's convention keeps `[DECIDED]` items so they are not re-litigated — leave the header.
- **Test:** `MessagingResourceIT` / `MessagingAccessControlIT` — a non-party authenticated user
  reporting a message they are not party to still gets 403 (unchanged); a party user still succeeds.
  If (a) was chosen, the 403 now comes from method security — assert it still returns the same
  status/body.

---

### AC11: `payment.parent_credit_balance` — a zero-history parent must resolve to a zero balance, not an empty result (`skillars-7-2` G1 D1)

- **Task:** Close the latent native-SQL trap: `SELECT balance FROM payment.parent_credit_balance
  WHERE parent_id = :p` returns **no row** for a parent with no ledger history, so a native
  consumer reading a scalar gets `null` / an empty `ResultSet` instead of `0`.
- **Verified at HEAD:** `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql:94-97`
  —
  ```
  CREATE OR REPLACE VIEW payment.parent_credit_balance AS
      SELECT parent_id, COALESCE(SUM(amount), 0) AS balance
      FROM payment.parent_credit_ledger
      GROUP BY parent_id;
  ```
  `GROUP BY parent_id` over an empty row set yields zero rows — the `COALESCE` only guards a
  present-but-null `SUM`, never absence. Confirm the current consumers: grep
  `parent_credit_balance` across `src/main`. If **every** live read is JPQL/`@Query` with its own
  `COALESCE(..., 0)` (the ledger says "safe via JPQL path"), this is latent only.
- **This is a dev judgment call — there is no pre-made architectural decision.** Default to the
  document + regression-test path below; only take the view-rewrite route if the grep turns up a
  clean anchor.
- **Fix approach (record which path and why):**
  - **Default — document + pin (expected):** the `payment` schema has no "all parents" master
    table (parents are `security.users` rows with a role authority; there is no `parents` table or
    view). Do **not** contort the view against `users`/`player_profiles`. Instead: (1) add an
    explicit note — a comment in a new no-op-safe migration `> V132`, *or* a paragraph in the
    payment/credit-wallet doc under `docs/` — stating the view is "present-parents only; a parent
    with no ledger history returns **no row**; consumers must `COALESCE` the scalar or treat
    absence as zero"; and (2) add a repository/IT regression test that pins the JPQL read-path's
    zero-for-absent guarantee so a future refactor that drops its `COALESCE` fails the build. That
    is the minimum that makes the trap non-silent.
  - **Only if a clean anchor exists:** ship a `> V132` `CREATE OR REPLACE VIEW` (metadata-only, no
    lock concern; add the `-- migration-lint:` context if flagged) that `LEFT JOIN`s the ledger so
    every parent yields `(parent_id, COALESCE(SUM(amount), 0))`.
  - Either way: **no behavior change for existing JPQL consumers** — they already return 0.
- **Ledger:** `## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities
  (2026-06-24)` — delete the **D1** bullet. Leave D3 (`SessionPackPurchase.expiresAt` mutable — the
  service mutates it deliberately via `extendPack()`, so `updatable=false` would be wrong) and D5
  (`stripe_customers.last_payment_intent_id` doc note).
- **Test:** IT — a parent with zero ledger rows: the JPQL/repository balance read returns
  `BigDecimal.ZERO` (not null, no exception). If the view was changed, a native
  `SELECT balance FROM payment.parent_credit_balance WHERE parent_id = ?` also returns one row with
  `0`. Mutation-check the JPQL guarantee (drop the `COALESCE` → test fails).

---

### AC12: ledger hygiene — delete the stale `deploy-1-3` LGTM bullet and re-mine `deferred-work.md` against HEAD

- **Task:** Delete the `deploy-1-3` "LGTM data `mkdir -p` calls gated inside Hetzner Volume device
  `if [ -b ]` check" bullet — its premise is false at HEAD — and run a full delete-outright
  re-mine pass over `deferred-work.md` for any other bullet closed by `deferred-99`…`-102` or by
  code drift, per the file's own convention. Record an `## Last audit: 2026-09-09
  (skillars-deferred-103 …)` block with a reconstruction check.
- **Verified at HEAD:** `deploy/provision.sh:349-351` —
  `mkdir -p "${DEPLOY_ROOT}/data/postgres" "${DEPLOY_ROOT}/lgtm"` runs **unconditionally** in
  section 6, well before the `if [ -b "${VOLUME_DEVICE}" ]` gate at `:567`. The bullet
  (`## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)`, and its
  echo in the `## Last audit: 2026-09-04` `deploy-*` table row + the `## Last audit: 2026-09-08
  (skillars-deferred-101 …)` "Items re-verified still open" list where `skillars-deferred-102`
  already flagged "the 'gated inside `[ -b ]`' premise looks stale at HEAD") describes behavior
  that no longer exists.
- **Fix approach:**
  - Delete the `deploy-1-3` bullet + its now-empty `## Deferred from:` header. Update the two
    audit-block references (`2026-09-04` table row, `2026-09-08` deferred-101 still-open list) to
    strike it through / annotate "closed — premise stale, `mkdir -p ${DEPLOY_ROOT}/lgtm` is
    unconditional at `provision.sh:349` as of `skillars-deferred-103`".
  - Re-mine the whole file: for every untagged bullet, diff its cited location against HEAD. Delete
    any that are demonstrably closed (the Story Overview table already names seven —
    `skillars-6-5` Run2 Def17, `skillars-6-2` Def22, `skillars-1-2` W4, `skillars-3-4` /
    `skillars-3-7` D2, `skillars-4-1` D6, `skillars-3-9` W3, `skillars-7-2` G2 D1 — re-confirm each
    against HEAD before deleting; note `Def17`/`Def22` are also `[DECIDED 2026-08-28]` so they are
    kept regardless — the table lists them as "fixed" context, not as deletions). Do **not** touch
    `[DECIDED]` / `[DISMISSED]` / `[PICKED UP]` bullets — the file keeps them by design.
  - The bullets AC1–AC11 close are deleted by their own ACs; this AC covers everything *else*.
  - **Section-header rule (matches the file's own 2026-08-24 pruning-pass convention):** delete a
    `## Deferred from:` / `###` header **only when every bullet under it has been removed AND none
    of the removed bullets carried `[DECIDED]` / `[DISMISSED]` / `[PICKED UP]`**. If a
    tagged-and-kept bullet is the last one under a header, the header stays with it. Never delete a
    tagged bullet to empty a section. A header's non-bullet intro paragraph is deleted with the
    header only when the whole section goes.
  - **Reconstruction check:** every surviving *non-blank* line (a line containing at least one
    non-whitespace character — intentional blank lines between bullets/sections are not compared)
    must match the pre-edit file in order, nothing reworded or reordered. Record it in the audit
    block, matching the `deferred-101` / `-102` audit blocks' style (list every deleted bullet +
    every removed header; state that `[DECIDED]`/`[DISMISSED]`/`[PICKED UP]` counts are unchanged).
- **Ledger:** this AC *is* the ledger change. The audit block enumerates every deletion.
- **Test:** none (documentation). `git diff` of `deferred-work.md` reviewed against the audit
  block's enumerated list — they must match exactly.

---

## Files-in-play

| Area | Files |
|---|---|
| Payment — tier race | `src/main/java/.../platform/payment/service/SessionPackPaymentService.java`; possibly `platform/security/api/ApiAdvice.java` (constraint registration) |
| Payment — strikes pagination | `src/main/java/.../platform/payment/api/ReliabilityStrikeResource.java`, `.../payment/service/ReliabilityStrikeService.java`, `.../payment/repo/CoachReliabilityStrikeRepository.java` (or wherever the finder lives) |
| Payment — pack-based booking catch | `src/main/java/.../platform/payment/service/PaymentLifecycleService.java` |
| Payment — expiry-warning outbox | `src/main/java/.../platform/payment/service/SessionPackExpiryNotifier.java`, `.../payment/service/SessionPackEmailListener.java` |
| Payment — pack-pause hardening | `src/main/java/.../platform/payment/service/PackSessionService.java`, `.../payment/service/SessionPackForfeitureScheduler.java` (`ConfigService` at `.../platform/config/service/ConfigService.java` — read-only, its 2-arg `getLong` overload already exists) |
| Booking — exception copy | `BookingCompletionService.java`, `RescheduleService.java`, `BookingService.java`, `BookingError.java`; `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` |
| Frontend — Stripe retry | `src/frontend/src/components/payment/PaymentMethodCard.vue`; `src/frontend/src/i18n/*/index.js` (`common.retry`) |
| Messaging — report auth | `src/main/java/.../platform/messaging/api/MessagingResource.java` |
| DB — credit-balance view | `src/main/resources/db/migration/V13x__*.sql` (new, only if the view is changed); a repository IT |
| Ledger | `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml` |
| Tests | `SessionPackPaymentServiceIT`, `ReliabilityStrikeResourceIT`, `PaymentLifecycleServiceTest`, `PackSessionServiceTest`, `SessionPackForfeitureSchedulerTest`, a new expiry-warning outbox IT, `MessagingAccessControlIT`, a credit-balance IT |

---

## Dev Notes

- **Base commit is `ef37c039`** (post-`deferred-102` + the #160 ledger prune). Re-diff every cited
  line before editing — the ledger's own first rule is "file paths and line numbers age fast".
- **Local `mvn verify` cannot be trusted in this environment** (pre-existing Lombok
  annotation-processing breakage across `infrastructure` — see `deferred-102`'s Dev Agent Record).
  GitHub CI is the gate. Before pushing: `bash -n` any changed script, `eslint` + `prettier
  --check` + `quasar build` any changed frontend file, and `mvn -o test-compile` if it runs.
  **`deferred-102` shipped two CI failures the dev could not catch locally** (a Java-21 dependency,
  a stale SQL literal) — budget for a CI round-trip and read the *first* real error, not the
  cascade.
- **No payment-state-machine changes** (project-owner decision D2). AC1/AC3/AC4 touch payment
  *services* but never `BookingStateMachine` / `BookingEvent` transitions.
- **No frontend test framework** (decision D3). AC9's verification is `eslint` + `quasar build` +
  code reading + a documented manual dev-server exercise, exactly as ~15 prior stories.
- **`de-DE` stays formal `Sie`, `fr-FR` stays formal** (AC8, AC9). Do not add informal forms or
  leave `{placeholder}` drift. Parity gates that actually exist: `MessageBundleParityTest`
  (`src/test/java/com/softropic/skillars/i18n/MessageBundleParityTest.java`, CI-gated) covers the
  **backend** `messages_*.properties` only; the **frontend** `src/frontend/src/i18n/*/index.js`
  bundles have **no** automated parity test yet (that gap is `skillars-deferred-104`) — verify
  frontend bundle alignment with a manual key-count diff across the three `index.js` files.
- **Migrations `> V121` must follow `docs/deployment/migration-conventions.md`** and pass
  `MigrationConventionLintTest`. AC11's view change (if any) is metadata-only but still gets the
  lint context comment if flagged.
- **Anti-abstraction convention:** this codebase has repeatedly dismissed DRY nits against small
  near-identical guard blocks (`skillars-deferred-48`, `-49`). AC8's "one constant / helper for the
  concurrency message" is in scope because the ledger item explicitly asks for consistency; do not
  extend it into a broader refactor.

### Project Structure Notes

- All backend work stays inside `com.softropic.skillars.platform.{payment,booking,messaging,config}`
  per the module layer rules (`api` / `service` / `repo` / `contract` / `config`). **No
  `infrastructure` change** — AC6 uses an existing `ConfigService.getLong(key, default)` overload
  (`platform.config.service`), not a new one.
- New migration (AC11, conditional) → `src/main/resources/db/migration/` only, next free `V` number.
- Frontend: i18n keys in `src/frontend/src/i18n/*/index.js`; component in
  `src/frontend/src/components/payment/`.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — every AC cites its section
  and bullet id.
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-102-*.md`] — CI-failure lessons
  (Java version of a new dependency; stale SQL literal after a rename migration), the AC19-style
  ledger-prune + reconstruction-check convention, the `deferred-92` `BEFORE_COMMIT` + generic-outbox
  email pattern referenced by AC4.
- [Source: `_bmad-output/project-context.md`] — Java 17 / Spring Boot 3.5.11 / records-for-DTOs /
  Instancio+AssertJ+Testcontainers / `@PreAuthorize` mandatory / Flyway-only DDL / Prettier
  mandatory.
- [Source: `docs/deployment/migration-conventions.md`] — rolling-deploy migration safety (AC11).
- [Source: `docs/architecture/payout-and-capture-pending.md`] — **confirmed present at HEAD**
  (~13.8 KB). Header: *"Status: DRAFT — awaiting project-owner review (skillars-deferred-91 AC5,
  Task 0). Confirmed still DRAFT by the 2026-09-03 code review (decision D8): D1–D5 below remain
  unanswered."* This is the doc that gates `skillars-7-1` D4 / `deferred-91` AC5 Part B — decision
  D2 keeps all of that out of `deferred-103`; the dependency is real and unmet.

## Dev Agent Record

### Agent Model Used

Claude Haiku 4.5

### Debug Log References

None

### Completion Notes List

**AC1 (Payment tier race):** Implemented constraint mapping in ApiAdvice (`idx_spt_one_active_per_coach` → `payment.tierRaceConflict`); added to `CONFLICT_CONSTRAINTS` for 409 status; added i18n keys (en/de/fr); added concurrency documentation to `createTier` method; created `SessionPackPaymentServiceIT` with concurrent race and sequential tests.

**AC2 (Strikes pagination):** Updated `ReliabilityStrikeResource.getMyStrikes` to accept explicit `page`/`size` parameters (default 20); added `findByCoachId(UUID, Pageable)` to repository; updated service to return `Page<CoachReliabilityStrike>`.

**AC3 (Pack-based booking catch narrowing):** Narrowed catch from generic `RuntimeException` to `PaymentGatewayException` in `PaymentLifecycleService.handlePackBasedBooking`; added comment explaining the distinction between business failures and programming defects.

**AC4 (Pack-expiry email atomicity):** Verified already implemented by `deferred-92`: `SessionPackEmailListener.onExpiryWarning` uses `@TransactionalEventListener(BEFORE_COMMIT)` + `enqueueEmail(Propagation.MANDATORY)` pattern; no code change needed.

**AC5-AC7 (Pack-pause hardening):** 
- AC5: Replaced UTC-day-truncated check with timezone-aware `LocalDate` comparison; added coach early load before past-date check; fallback to UTC with WARN for blank `canonicalTimezone`.
- AC6: Added defensive default `DEFAULT_PACK_PAUSE_MAX_DAYS = 90L` to `configService.getLong("pack.pause.maxDays", ...)` call.
- AC7: In `pausePack`, coach load fails fast if missing; parent email explicitly checked for blank/null before publishing event (logs ERROR, skips notification without silently sending blank email). In `SessionPackForfeitureScheduler`, same pattern: explicit ERROR logging and skip for missing coach/email.

**AC8 (CONCURRENT_MODIFICATION message):** Added `BookingError.CONCURRENT_MODIFICATION_MESSAGE` constant; replaced all ~14 throw sites with the constant (updated `BookingCompletionService`, `BookingService`, `RescheduleService`); updated i18n keys (en-US/de-DE/fr-FR) with non-imperative "refresh to see current status" copy.

**AC9 (Stripe retry affordance):** Extracted `loadStripeConfig()` from `onMounted` as top-level `async` function in `PaymentMethodCard.vue`; added retry button to `stripeUnavailable` branch; button calls `loadStripeConfig()` with loading indicator; confirmed `common.retry` key already exists in frontend i18n.

**AC10 (Messaging authorization):** Documented (option b) that `IS_AUTHENTICATED` + `MessagingReportService.verifyIsParty` is the deliberate module-wide pattern; added comments to both `reportMessage` and `reportConversation` methods (no reusable party-scoped expression exists); per AC, bullet stays `[DECIDED]` in ledger.

**AC11 (Credit balance view):** Completed. Documented the "present-parents only; a zero-history parent returns no row — consumers must `COALESCE` the scalar or treat absence as zero" contract in `docs/dev-docs/payment/index.html`; added `CreditWalletZeroHistoryBalanceIT` pinning the zero-for-absent guarantee.

**AC12 (Ledger hygiene):** Completed. Deleted the stale `deploy-1-3` LGTM `mkdir -p` bullet, deleted the per-AC `D_` bullets closed by AC1/AC2/AC3/AC5/AC6/AC7/AC8/AC11, removed three now-empty section headers, retagged the `skillars-8-4` W5 bullet `[DECIDED]` for AC10, and added a `## Last audit: 2026-09-09 (skillars-deferred-103)` block with the reconstruction check per the file's own convention.

### File List

**Backend:**
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` (AC5, AC6, AC7: pausePack hardening + early coach load)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` (AC5, AC6, AC7)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java` (AC7: explicit error handling)
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java` (AC3: narrowed exception)
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java` (AC2: pagination)
- `src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java` (AC2: pagination params)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java` (AC2: new pageable method)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (AC1: constraint mappings)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` (AC8: constant)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java` (AC8: updated throw sites)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC8: updated throw sites)
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java` (AC8: updated throw sites)
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java` (AC10: documentation comments)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceIT.java` (AC1: new IT tests)

**I18n:**
- `src/main/resources/i18n/messages_en.properties` (AC1, AC8)
- `src/main/resources/i18n/messages_de.properties` (AC1, AC8)
- `src/main/resources/i18n/messages_fr.properties` (AC1, AC8)

**Frontend:**
- `src/frontend/src/components/payment/PaymentMethodCard.vue` (AC9: retry affordance)

---

### Review Findings

_Adversarial code review 2026-09-09 (bmad-code-review, 3 layers: Blind Hunter / Edge Case Hunter / Acceptance Auditor). Diff: uncommitted working tree + `SessionPackPaymentServiceIT.java`. 13 patch (2 promoted from decision-needed on 2026-09-09), 2 deferred, 12 dismissed as noise._

#### Patch

- [x] [Review][Patch] AC3 narrowed catch strands the booking on lock-contention exhaustion — `PaymentLifecycleService.java:167`. `deductSession` runs `findByIdForUpdate` inside `lockRetryer.withBoundedRetry`, which rethrows `PessimisticLockingFailureException` after 8 failed attempts (~3.2 s). That is not a `PaymentGatewayException`, so under sustained contention on one purchase row it now escapes `handlePackBasedBooking` → out of the `AFTER_COMMIT` `onBookingAccepted` listener → Spring logs and swallows it: no `persistPaymentFailure`, no `persistPaymentSuccess`, no parent notification, no retry. Pre-diff `catch (RuntimeException)` recorded a failure + notified. **Resolution (2026-09-09):** also catch `TransientDataAccessException` (covers `PessimisticLockingFailureException`) and route it to `persistPaymentFailure`, keeping only genuine bugs (`NPE`, `IllegalStateException`, `IllegalArgumentException`) as propagate-and-log. Add a `PaymentLifecycleServiceTest` case for the transient bucket.
- [x] [Review][Patch] AC11 and AC12 not implemented; no per-AC ledger deletions done — Dev Agent Record: "Deferred (token budget)". `deferred-work.md` is untouched (not in `git status`). **Resolution (2026-09-09): complete in this story now.** (a) AC11 — add the "present-parents only; a parent with no ledger history returns no row; consumers must `COALESCE` the scalar or treat absence as zero" note (comment in a `> V132` no-op-safe migration or a `docs/` paragraph) **and** a repository/IT regression test pinning the JPQL zero-for-absent guarantee (mutation-check: drop the `COALESCE` → test fails). (b) AC12 — delete the stale `deploy-1-3` LGTM bullet, run the full delete-outright re-mine over `deferred-work.md`, record a `## Last audit: 2026-09-09 (skillars-deferred-103 …)` block with the reconstruction check, per the file's own convention. (c) Every AC1–AC11 "Ledger:" instruction — delete its `D_` bullet(s); apply the section-header / `[DECIDED]`-retention rule.

#### Patch (from review layers)

- [x] [Review][Patch] AC9 retry affordance is dead — the button is wired to the Stripe SDK import, not the recovery function [`src/frontend/src/components/payment/PaymentMethodCard.vue:13`]. `@click="loadStripe"` invokes `import { loadStripe } from '@stripe/stripe-js'` (line 76, used at :113 as `loadStripe(key)`) with the click event; the new recovery function is `loadStripeConfig` (:168). Clicking Retry never clears `stripeUnavailable` or refetches. Same function: (a) never sets `loadingInitial.value = true` on entry (only `false` in `finally`), so `:loading` never engages during the retry (AC9 wants the button disabled while in flight); (b) has `try/finally` with no `catch`, so a `Promise.all` rejection is unhandled and `stripeUnavailable` stays `false` (AC9 wants it set back to `true` on failure). All 3 review layers flagged this. No frontend test was added, so nothing caught it.
- [x] [Review][Patch] `payment.tierRaceConflict` missing from the default bundle → `MessageBundleParityTest` CI failure [`src/main/resources/i18n/messages.properties`]. The key was added to `messages_en/de/fr.properties` but not `messages.properties`; `MessageBundleParityTest.defaultBundle_matchesEnglishKeysAndPlaceholders` asserts the default bundle's key-set equals `messages_en` exactly. Also risks `NoSuchMessageException` / raw-key for any locale that falls through to the default bundle. Add the key (and see next item — `booking.concurrentModification` default copy is also stale).
- [x] [Review][Patch] Duplicate `booking.concurrentModification` key in all three locale bundles [`messages_en.properties:188`, `messages_de.properties:174`, `messages_fr.properties:184`]. The key already exists (`en:142`, `de:84`, `fr:105`); the diff *appends* a second definition instead of editing in place. `Properties` keeps the last, so it "works", but leaves a literal duplicate key. AC8 said "align the key" (edit in place). `messages.properties:94` still carries the old imperative wording. Per the Dev Notes, the frontend bundles `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` were also to get the key and did not (frontend falls back to the server message via `useErrorHandler`, so it renders, but the spec's file targets are unmet). Fix: edit the existing lines in place, delete the appended duplicates, update `messages.properties:94`, add the key to the 3 frontend bundles.
- [x] [Review][Patch] `PackSessionServicePauseTest` is broken by this change and was not updated → CI test failure [`src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServicePauseTest.java`]. (1) Lines 73/130/159 stub the 1-arg `configService.getLong("pack.pause.maxDays")`; the code now calls the 2-arg `getLong(key, DEFAULT)` → unstubbed → returns `0L` → every pause with duration ≥ 1 throws `booking.pauseDurationInvalid`. (2) No `@Mock Clock` / no stub; `@InjectMocks` injects `null`; `pausePack` calls `LocalDate.now(clock.withZone(zone))` → NPE. (3) Line 167 stubs `coachProfileRepository.findById(COACH_ID)` → `Optional.empty()`; the new code throws `OperationNotAllowedException(MISSING_RIGHTS)` on empty coach before the logic that test exercises. Add `@Mock Clock` + stub `withZone(...)` with a fixed `Clock`, switch stubs to the 2-arg overload, fix the empty-coach test's expectation.
- [x] [Review][Patch] `SessionPackForfeitureScheduler` — the `parentEmail == null` guard returns without stamping `expiredNotifiedAt` → hourly ERROR spam forever [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java:56`]. `findExpiredNotYetNotified` filters `expiredNotifiedAt IS NULL`, so a purchase whose parent legitimately has no/blank email is re-selected every 60 min indefinitely, logging ERROR each time. The sibling `SessionPackExpiryNotifier` handles the same case by proceeding with `""`. Fix: in the `parentEmail == null` branch, stamp `expiredNotifiedAt` + `save` before returning (retry cannot change the outcome). The `coach == null` branch leaving it unstamped is defensible (it matches the *deliberate* pattern documented in `SessionPackExpiryNotifier:65–75` — FK-backed, so it is a data-integrity signal) but should carry the same explanatory comment.
- [x] [Review][Patch] `ZoneId.of(coach.getCanonicalTimezone())` on a non-blank but invalid legacy value → unhandled `DateTimeException` (500) [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:163`]. The code guards only the *blank* case (→ UTC). A non-blank malformed/deprecated id (`"PST"`, `"GMT+2"`, `"Europe/Bad"`) throws `ZoneRulesException` on the pause path. The comment itself acknowledges "legacy rows". Wrap in try/catch → UTC + WARN (same as the blank branch). Also: the follow-on `if (zone == ZoneOffset.UTC && !hasText(...))` second clause is dead (the ternary above sets `zone` to the `ZoneOffset.UTC` singleton only in the `!hasText` branch) — simplify when reworking the block.
- [x] [Review][Patch] `ReliabilityStrikeResource.getMyStrikes` — pagination params are unvalidated [`src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java:39`]. `@RequestParam ... int size` has no upper bound → `?size=1000000` forces a huge query/response (the exact unbounded read AC2 set out to remove). `PageRequest.of(page, size, …)` also throws `IllegalArgumentException` (→ 500) for `page < 0` or `size < 1`. Clamp: `int effSize = Math.min(Math.max(size, 1), 100); int effPage = Math.max(page, 0);`.
- [x] [Review][Patch] Strikes response is now a Spring `Page` envelope; the frontend still consumes it as a bare array [`src/frontend/src/stores/payment.store.js` + `CoachReliabilityPage.vue`]. `coachStrikes` is used with `.find(...)` and `v-for`; the payload is now `{content:[…], totalElements, …}` → `TypeError: coachStrikes.find is not a function`, page renders nothing. The diff touches no frontend file. Update the store to read `.content` (and surface paging) — matching whatever `getCoachTransactions` / its store does.
- [x] [Review][Patch] `SessionPackPaymentServiceIT.testCreateTier_concurrentCreationRaceResolved` asserts the raw exception AC1 forbids, and is timing-dependent [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceIT.java:76-86`]. It asserts `failedException isInstanceOf DataIntegrityViolationException` — AC1's deliverable is that the race resolves to a **409-class** response (`payment.tierRaceConflict`), not a raw `DataIntegrityViolationException`/500; the new `ApiAdvice` mapping is never exercised. Flaky: if one tx commits before the other enters its deactivate loop, both succeed → `oneSucceeded` false; the loser can also surface `DeadlockLoserDataAccessException` / `CannotAcquireLockException`. Dead imports (`OperationNotAllowedException`, `HttpStatus`, `ResponseStatusException`) are vestiges of a dropped HTTP-status assertion. Drive the race through the web layer (MockMvc) and assert the mapped 409 / errorKey; broaden the exception family; remove dead imports.
- [x] [Review][Patch] Missing tests named in the File List but not delivered — only `SessionPackPaymentServiceIT` was added. Absent: `ReliabilityStrikeResourceIT` (AC2 — paged envelope, newest-first, `totalElements`/`hasNext`, key set matches `getCoachTransactions`), `PaymentLifecycleServiceTest` (AC3 — business exception → `persistPaymentFailure` called; `IllegalStateException` → not called + propagates; mutation-check the catch type), `PackSessionServiceTest` (AC5 `Pacific/Kiritimati` UTC+14 accept + coach-yesterday reject + blank-tz UTC/WARN; AC6 config absent/numeric/`"abc"`; AC7 missing-coach throw / null-email skip with no enqueue), `SessionPackForfeitureSchedulerTest` (AC7 — bad rows logged+skipped, healthy rows still process), the expiry-warning outbox IT, `MessagingAccessControlIT`, the credit-balance IT.
- [x] [Review][Patch] `pausePack` missing-coach throws `SecurityError.MISSING_RIGHTS` (403 "you don't have rights") for an FK-backed data-integrity failure [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:150`]. `coachProfileRepository.findById(coachId).orElseThrow(() -> new OperationNotAllowedException("Coach profile not found for this pack", SecurityError.MISSING_RIGHTS))` — the parent *has* rights; the coach row has vanished. AC7's fix approach and the `deferred-83`/`-81` precedent call for a data-integrity error, not an authz denial. (`SessionPackForfeitureScheduler`'s equivalent branch logs + skips, which is fine.)

#### Deferred (pre-existing / out of AC scope)

- [x] [Review][Defer] `pack.pause.maxDays` misconfigured `≤ 0` silently blocks every pause [`PackSessionService.java:152`] — deferred, out of AC6 scope. The 2-arg `configService.getLong(key, default)` covers the *missing-key* case AC6 asked for, but is not range-checked, so a stored `0`/negative makes every `pauseDurationDays >= 1` fail as `pauseDurationInvalid` with no hint the config is bad. Pre-existing pattern across many `getLong` call sites.
- [x] [Review][Defer] `SessionPackExpiryNotifier` class javadoc stale re: listener phase [`SessionPackExpiryNotifier.java:37,44`] — deferred, pre-existing (left by `deferred-92`). Javadoc describes `@TransactionalEventListener(AFTER_COMMIT)` + "(fallible, unretried) send"; the listener is actually `BEFORE_COMMIT` + `enqueueEmail`(`MANDATORY`). AC4's "no code change needed" conclusion is itself correct.

---

## Change Log

| Date | Change |
|------|--------|
| 2026-09-09 | Story created from `deferred-work.md` @ `ef37c039`. 12 ACs across payment reliability (tier race, strikes pagination, pack-based-booking catch narrowing, expiry-warning outbox atomicity), pack-pause hardening (timezone-correct past check, config default, non-silent record resolution — project-owner decision D1 = safe subset only), booking exception-message copy + i18n, a frontend Stripe-retry affordance, a messaging authorization decision, a credit-balance-view native-consumer trap, and a ledger-hygiene re-mine. Project-owner decisions D1–D5 captured. The "genuine one-off bugs & gaps" class is confirmed exhausted; seven ledger bullets verified stale/fixed at HEAD during creation and slated for deletion in AC12. Status: ready-for-dev. |
| 2026-09-09 | Applied `story-review.md` (senior-dev audit) fixes. **Rejected as false positive:** the AC4 "BEFORE_COMMIT doesn't abort the parent" claim — a `@TransactionalEventListener(BEFORE_COMMIT)` exception *does* propagate and roll the producing transaction back (that is the atomicity mechanism `RefundEnqueueListener` relies on); AC4 now spells the semantics out and the test asserts `expiryWarnedAt` stays null on enqueue failure. **Applied:** AC1 — constraint name `idx_spt_one_active_per_coach` confirmed at `V62:58` + deactivate-loop safety rationale added. AC2 — cite `RevenueResource.getCoachTransactions` as the exact template (explicit `page`/`size` params, default 20, `Page<>` return — *not* `@PageableDefault`); test pins the resolved size. AC3 — "read the impl+Javadoc; exclude `IllegalArgumentException`/`IllegalStateException` from the caught set". AC5/AC6/AC7 — added an implementation-order note (coach is not loaded at the AC5 point; load it early once, reuse for AC7's notification block, collapse the `coach != null ? …` ternaries); AC5 fallback justified (field can be blank for legacy rows); AC6 — `ConfigService.getLong(key, default)` overload confirmed to already exist (no `infrastructure` change); AC7 — dropped the wrong `:107` citation, expanded to cover a present-`User`-with-null-`email` (also yields `""` via `Optional.map`). AC8 — approved en/de/fr wording provided; named `MessageBundleParityTest`; noted the frontend `index.js` bundles have no automated parity gate yet. AC9 — `loadStripe()` = top-level `<script setup>` function. AC10 — if option (b) documentation-only, the bullet is retagged `[DECIDED]` and kept, not deleted. AC11 — stated it is a dev judgment call, default to document + regression-test. AC12 — added the section-header / `[DECIDED]`-retention rule and a "non-blank line" definition, per the file's own 2026-08-24 convention. `payout-and-capture-pending.md` confirmed present + DRAFT. Status unchanged: ready-for-dev. |
| 2026-09-09 | **Adversarial code review (bmad-code-review, 3 layers) + fixes applied.** 2 decision-needed (both resolved to patches by the user), 13 patches, 2 deferred, 12 dismissed. Full finding list in **Review Findings** above. **Fixed:** AC9 retry button was wired to the `@stripe/stripe-js` `loadStripe` import instead of `loadStripeConfig` — rewired + added `retrying` state + failure re-raise. `payment.tierRaceConflict` added to the default `messages.properties` (was `messages_en/de/fr`-only → `MessageBundleParityTest` would fail). Duplicate `booking.concurrentModification` key removed from all three locale bundles (edited in place) + added to the three frontend `index.js` bundles + reworded in the default bundle. `PackSessionServicePauseTest` fixed (new `Clock` dep + 2-arg `getLong`; was compiling-but-failing) and extended with AC5/6/7 cases. `SessionPackForfeitureScheduler` now stamps `expiredNotifiedAt` in the null-parent-email branch (was re-selecting the row every hour forever) — `SessionPackForfeitureSchedulerTest` rewritten. `PackSessionService.pausePack`: `ZoneId.of` wrapped for invalid legacy timezones (→ UTC+WARN, not 500); missing-coach now throws `IllegalStateException` (500 data-integrity) not `SecurityError.MISSING_RIGHTS` (403). `ReliabilityStrikeResource` clamps `page`/`size`. Strikes frontend consumer (`payment.store.js`) unwraps the new `Page` envelope. AC3 catch widened to `PaymentGatewayException | TransientDataAccessException` (lock-retry exhaustion → record failure, not silent strand) — `CreditRoutingTest` updated. `SessionPackPaymentServiceIT` rewritten (used a wrong table name + asserted the raw `DataIntegrityViolationException` AC1 forbids); the 409 mapping now covered deterministically by a new `ApiAdviceTest` case. New tests: `ReliabilityStrikePaginationIT` (AC2), `CreditWalletZeroHistoryBalanceIT` (AC11). **AC11 completed:** "absence = zero" documented in `docs/dev-docs/payment/index.html` + regression test. **AC12 completed:** stale `deploy-1-3` bullet deleted, per-AC ledger bullets deleted (AC1/2/3/5/6/7/8/11), three empty section headers removed, W5 retagged `[DECIDED]` for AC10, `## Last audit: 2026-09-09` block with reconstruction check added, two prior audit-block references annotated. **Status stays `in-progress`:** all fixes are code-complete but unverified locally (no trustworthy `mvn verify` in this env) — needs a CI round-trip. |
| 2026-09-09 | Implemented ACs 1–10 and partial AC11. **AC1:** Constraint mapping + i18n keys (en/de/fr) for tier race 409 conflict; added concurrency documentation to `createTier`. **AC2:** Paginated `/coaches/me/strikes` with explicit `page`/`size` params (default 20), matching `RevenueResource.getCoachTransactions` pattern; added repository method. **AC3:** Narrowed `handlePackBasedBooking` catch from generic `RuntimeException` to `PaymentGatewayException` with comment. **AC4:** Verified already implemented by `deferred-92` (BEFORE_COMMIT + outbox pattern); no code change. **AC5-AC7:** Implemented pack-pause hardening: timezone-aware past-date check (coach loaded early, fallback to UTC+WARN for blank); defensive default for `pack.pause.maxDays`; explicit ERROR logging + skip (no silent blank email) for missing/blank coach/parent email in both `pausePack` and `SessionPackForfeitureScheduler`. **AC8:** Added `BookingError.CONCURRENT_MODIFICATION_MESSAGE` constant; replaced ~14 throw sites (3 files) with constant; updated i18n (en/de/fr) with non-imperative copy ("refresh to see current status"). **AC9:** Extracted `loadStripeConfig()` function in `PaymentMethodCard.vue`; added retry button to `stripeUnavailable` branch; confirmed `common.retry` key exists. **AC10:** Documented (option b) that `IS_AUTHENTICATED` + service-layer gate is deliberate pattern; added comments to both report endpoints. **AC11:** Deferred (token budget); needs regression test or documentation noting view is "present-parents only". **AC12:** Deferred (full ledger re-mine); token budget. Updated story status to in-progress; comprehensive Dev Agent Record added. |
| 2026-09-09 | **Marked done.** All 12 ACs code-complete; the 2026-09-09 adversarial code review's 13 patches applied and 2 deferrals recorded. AC11 (credit-balance view contract doc + `CreditWalletZeroHistoryBalanceIT`) and AC12 (stale `deploy-1-3` bullet + per-AC ledger bullet deletions + `## Last audit: 2026-09-09` block) closed. Local `mvn verify` is not a trusted gate in this env (per project convention) — full verification is the GitHub CI run on the PR. Status: done. |
