# skillars-deferred-103: Payment Reliability + Pack-Pause Hardening + Cross-Module Cleanup

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** medium
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
- **Fix approach:**
  - Annotate `createTier` `@Transactional` — without it the deactivate loop and the insert are not
    atomic even single-threaded (a failure mid-loop leaves some tiers deactivated and no new active
    tier).
  - Wrap the `save`/flush in a `try/catch (DataIntegrityViolationException e)`; if the constraint
    name is `idx_spt_one_active_per_coach` (use the same
    `ApiAdvice.CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` convention the rest of the codebase uses —
    prefer registering the constraint there over a local catch if that is how sibling races are
    handled) throw an `OperationNotAllowedException` with a `CONFLICT`-class error so the client gets
    409 + a retryable message. Any other `DataIntegrityViolationException` rethrows unchanged.
  - Do **not** add an application-level pre-check "does an active tier exist" — the DB index is the
    correct serialization point; the fix is to classify its failure, not to race it in Java.
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

- **Task:** Replace the unbounded `List<ReliabilityStrikeResponse>` return with a `Page`
  (or `Slice`) driven by a `Pageable`, matching how other list endpoints in this codebase paginate.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/api/ReliabilityStrikeResource.java:30-44`
  — `getMyStrikes()` takes no arguments, calls `reliabilityStrikeService.getCoachStrikes(currentCoachUserId())`
  and returns `ResponseEntity<List<ReliabilityStrikeResponse>>` with no bound. `ReliabilityStrikeService.getCoachStrikes`
  returns a plain `List`. `CoachReliabilityStrike` rows accrue over a coach's lifetime and are never
  pruned.
- **Fix approach:**
  - Add a `@PageableDefault(size = 20, sort = "createdAt", direction = DESC)` `Pageable` parameter
    (use the project's established default page size — check a sibling paginated resource such as a
    revenue or booking list — and match its `PagedResponse` / `Page` wrapper shape exactly; do not
    invent a new envelope).
  - Push the `Pageable` through `ReliabilityStrikeService.getCoachStrikes` into a
    `Page<CoachReliabilityStrike> findByCoachId(UUID coachId, Pageable pageable)` repository method
    (Spring Data derives it). Map to `Page<ReliabilityStrikeResponse>`.
  - Keep `@PreAuthorize` and `@Observed` unchanged. Preserve the existing default ordering
    (most-recent-first) as the default sort.
- **Ledger:** `## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes
  (2026-06-25)` — delete the **D3** bullet. Leave D1 (`buildSort` identical branches) and D5
  (`CoachCancellationHistory.createdAt`) — both untouched by this change.
- **Test:** `ReliabilityStrikeResourceIT` — seed >1 page of strikes for one coach; assert page 0
  returns exactly `size` items newest-first, `page=1` returns the remainder, and the total-count /
  `hasNext` metadata is correct. Assert the JSON envelope matches the sibling paginated endpoint's
  shape (guard against an accidental new response format).

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
  - Identify `deductSession`'s declared / reachable business exceptions (grep
    `PackSessionService.deductSession` — expect a domain exception such as
    `PackExhaustedException` / `ResourceNotFoundException` / a `SessionPackException` supertype).
  - Catch that supertype for the `persistPaymentFailure` path. Let anything else propagate — the
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
- **Fix approach:**
  - If the expiry-warning email still publishes on `AFTER_COMMIT`: move its enqueue to a
    `@TransactionalEventListener(BEFORE_COMMIT)` listener calling the generic-outbox
    `enqueueEmail` on `Propagation.MANDATORY`, exactly mirroring
    `deferred-92`'s `RefundEnqueueListener` / the 23 email listeners. The outbox drainer already
    handles retry/backoff.
  - `expiryWarnedAt` continues to be stamped in the producing transaction — that is now correct
    because the enqueue commits atomically with it.
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
- **Test:** IT in the pattern of `NotificationEmailOutboxAtomicityIT` — with the outbox drainer
  paused, run the expiry-warning path; assert an outbox row exists **and** `expiryWarnedAt` is set,
  in the same committed transaction. Simulate a drainer send failure; assert the outbox row is
  retained (not deleted) and re-attempted, i.e. the warning is not lost. If the "already migrated"
  branch applies, no new test — record the verification.

---

### AC5: `PackSessionService.pausePack` — timezone-correct "pause start is in the past" check (11-1 D3)

- **Task:** Replace the UTC-day-truncated past-date comparison with one that reflects the actor's
  (coach's or parent's) local day, so a pause legitimately starting "today" is not rejected as past
  for actors ahead of UTC (and vice-versa).
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:146-148`
  — `Instant pauseStart = req.pauseStartDate();` then
  `if (pauseStart.isBefore(Instant.now().truncatedTo(ChronoUnit.DAYS))) { …reject… }`.
  `truncatedTo(ChronoUnit.DAYS)` truncates to **UTC** midnight.
- **Fix approach:**
  - Resolve the relevant zone. The pack purchase links a coach; `CoachProfile.canonicalTimezone`
    exists and is IANA-validated on write (`skillars-deferred-63` AC6 backfill + `@IanaTimezone`).
    Use `coach.getCanonicalTimezone()` (fallback `"UTC"` with a WARN, matching every other
    read-side zone fallback in the codebase).
  - Compare `LocalDate.ofInstant(pauseStart, zone)` against `LocalDate.now(zone)` — reject only when
    strictly before. Keep the existing error key.
  - Do **not** change `req.pauseStartDate()`'s type or the wire contract; only the comparison.
- **Ledger:** `## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)`
  — delete the **D3** bullet. (D1, D5, D7, D8, D9 stay — project-owner decision D1.)
- **Test:** `PackSessionServiceTest` — fixed clock; coach zone `Pacific/Kiritimati` (UTC+14);
  `pauseStartDate` = the coach's "today" but still "yesterday" in UTC → assert **accepted**.
  `pauseStartDate` = the coach's "yesterday" → assert **rejected**. Reverting to the UTC-truncated
  check must flip the first assertion.

---

### AC6: `pack.pause.maxDays` config read — defensive default (11-1 D4)

- **Task:** Give `configService.getLong("pack.pause.maxDays")` a defensive default so a missing or
  non-numeric config value degrades to a sane bound instead of throwing out of `pausePack`.
- **Verified at HEAD:** `PackSessionService.java:141` — `long maxDays = configService.getLong("pack.pause.maxDays");`
  with no default overload. Confirm `ConfigService` exposes a `getLong(String key, long default)`
  overload (grep — sibling call sites such as `pack.pause` neighbours or `slu.*` reads may already
  use one); if it does not, add it following the existing `getLong` implementation, or guard at the
  call site.
- **Fix approach:**
  - Prefer `configService.getLong("pack.pause.maxDays", DEFAULT_PACK_PAUSE_MAX_DAYS)` where the
    default is a named constant in `PackSessionService` (pick the value the seed migration inserts —
    grep `pack.pause.maxDays` in `db/migration` — so the default matches production config exactly).
  - If `getLong` currently throws `NumberFormatException` on a non-numeric stored value, the
    overload must catch that and fall back too (log WARN with the offending value).
- **Ledger:** same section as AC5 — delete the **D4** bullet.
- **Test:** `PackSessionServiceTest` / `ConfigServiceTest` — key absent → `getLong` returns the
  default; key present but `"abc"` → returns the default + WARN logged; key present and numeric →
  returns the stored value.

---

### AC7: `pausePack` + `SessionPackForfeitureScheduler` — do not silently proceed on a missing coach/parent record (11-1 D2)

- **Task:** Replace the silent `.orElse(null)` (coach) / `.orElse("")` (parent email) fallbacks
  with an explicit outcome: log ERROR naming the missing id and either skip the notification cleanly
  or fail the operation — never send a "Dear , your pack…" email with a blank recipient or a null
  coach reference downstream.
- **Verified at HEAD:**
  - `PackSessionService.java:107` — `.orElse(null)` (context: confirm which lookup — likely a
    coach/profile resolve inside the conflict-handling path).
  - `PackSessionService.java:192-193` —
    `CoachProfile coach = coachProfileRepository.findById(coachId).orElse(null);`
    `String parentEmail = userRepository.findById(parentId).map(u -> u.getEmail()).orElse("");`
  - `SessionPackForfeitureScheduler.java:44,46` — identical shape
    (`coachProfileRepository.findById(purchase.getCoachId()).orElse(null)` and
    `…map(User::getEmail).orElse("")`).
- **Fix approach:**
  - For each site, decide skip-vs-fail by context: an interactive `pausePack` call should fail fast
    with a clear error (a pack whose coach/parent row has vanished is a data-integrity fault the
    caller must see); the scheduler should `log.error(… missing coach/parent id=… — skipping
    notification for purchase=…)` and continue the loop (do not abort the batch).
  - A blank `parentEmail` must never reach the mail layer — guard before `enqueueEmail`.
  - Match the codebase's precedent for orphaned-profile handling (`skillars-deferred-83` /
    `-81` AC4 established "explicit error, not a placeholder" for the messaging module) — reference
    that in the Dev Agent Record.
- **Ledger:** same section as AC5 — delete the **D2** bullet.
- **Test:** `PackSessionServiceTest` — stub the coach lookup empty → `pausePack` throws the
  data-integrity error, no email enqueued. `SessionPackForfeitureSchedulerTest` — one purchase with
  a missing coach among several → that one logs ERROR and is skipped, the others still process.

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
  - Replace the message with something like `"Booking status changed — please reload and try
    again"` (the internal exception message is a developer/log string; the *user-facing* text is the
    i18n value, so the priority is the bundle key). Keep it identical across all sites — consider a
    `BookingError`-level constant or a small helper so they cannot drift again (the ledger's own
    `skillars-deferred-66` note says "consistency… is the point").
  - Update `booking.concurrentModification` in all three frontend bundles
    (`src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`) to the reworded, non-imperative text.
    `de-DE` stays formal `Sie`; `fr-FR` stays formal — match the register the rest of each bundle
    uses (do not introduce informal forms; `MessageBundleParityTest` / the frontend parity check
    must stay green).
  - `eslint.config.js`'s `vue/no-bare-strings-in-template` does not apply (these are JS message
    values), but run `prettier --check` on the touched bundle files.
- **Ledger:** `## Deferred from: code review of skillars-deferred-66 (2026-08-25)` — delete the
  "imperative 'retry' language" bullet. That is the section's only bullet → remove the header too.
- **Test:** no new backend test for a copy change; assert (existing IT or a new tiny one) that a
  `CONCURRENT_MODIFICATION` response still carries `errorKey = "booking.concurrentModification"`
  (guard against an accidental key rename). Frontend: `prettier --check` + the bundle parity check.

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
  - Extract the `onMounted` init body into a named `async function loadStripe()` (or similar);
    call it from `onMounted` and from the new retry button.
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
  - **(a) Tighten:** if a reusable method-security bean/expression for "is a party to this
    conversation" already exists (grep `@messagingSecurity` / a `PermissionEvaluator` / a
    `hasPermission` usage), apply it. Do **not** invent a new security infrastructure for two
    endpoints.
  - **(b) Document:** if no such expression exists, add a 2-line comment at both annotations
    ("Party check is enforced in `MessagingReportService.verifyIsParty`; consistent with every
    other endpoint in this resource — `IS_AUTHENTICATED` here is deliberate, not an oversight") and
    treat the item as closed-by-decision.
  - This is defense-in-depth only — the service-layer 403 is not changing. Keep scope minimal.
- **Ledger:** `## Deferred from: code review of skillars-8-4 (2026-06-27)` — delete the **W5**
  bullet (it is the section's only bullet → remove the header).
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
- **Fix approach (pick based on what the grep finds — record the choice):**
  - **If a clean "all parents" anchor exists** (e.g. a `parents` view, or `player_profiles` /
    `users` filtered to the parent role): ship a new migration (`> V132`, follows
    `docs/deployment/migration-conventions.md` — a `CREATE OR REPLACE VIEW` is metadata-only, no
    lock concern, but still add the `-- migration-lint:` context if the linter flags it) that
    `LEFT JOIN`s the ledger so every parent yields `(parent_id, COALESCE(SUM(amount), 0))`.
  - **If there is no clean anchor** (likely — the ledger schema has no parent master table):
    do **not** contort the view. Instead (1) add an SQL comment to a new no-op-safe migration *or*
    a `docs/` note stating the view is "present-parents only; consumers must `COALESCE` the scalar
    or treat absence as zero", and (2) add a repository/IT regression test that pins the JPQL
    read-path's zero-for-absent guarantee so a future refactor that drops the `COALESCE` fails
    the build. This is the minimum that makes the trap non-silent.
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
    `skillars-3-7` D2, `skillars-4-1` D6, `skillars-3-9` W3, `skillars-7-2` G2 D1 — confirm each
    and delete; some carry `[DECIDED]` and stay by the file's convention, so **verify tag status
    first**). Do **not** touch `[DECIDED]` / `[DISMISSED]` / `[PICKED UP]` bullets.
  - The bullets AC1–AC11 close are deleted by their own ACs; this AC covers everything *else*.
  - Reconstruction check: every surviving non-blank line must match the pre-edit file in order,
    nothing reworded — record it in the audit block, matching the `deferred-101` / `-102` audit
    blocks' style.
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
| Payment — pack-pause hardening | `src/main/java/.../platform/payment/service/PackSessionService.java`, `.../payment/service/SessionPackForfeitureScheduler.java`; possibly `infrastructure` `ConfigService` (getLong overload) |
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
- **`de-DE` stays formal `Sie`, `fr-FR` stays formal** (AC8, AC9). The frontend bundle parity check
  and `MessageBundleParityTest` must stay green; do not add informal forms or leave `{placeholder}`
  drift.
- **Migrations `> V121` must follow `docs/deployment/migration-conventions.md`** and pass
  `MigrationConventionLintTest`. AC11's view change (if any) is metadata-only but still gets the
  lint context comment if flagged.
- **Anti-abstraction convention:** this codebase has repeatedly dismissed DRY nits against small
  near-identical guard blocks (`skillars-deferred-48`, `-49`). AC8's "one constant / helper for the
  concurrency message" is in scope because the ledger item explicitly asks for consistency; do not
  extend it into a broader refactor.

### Project Structure Notes

- All backend work stays inside `com.softropic.skillars.platform.{payment,booking,messaging}` per
  the module layer rules (`api` / `service` / `repo` / `contract` / `config`). No `infrastructure`
  change except a possible `ConfigService.getLong(key, default)` overload (AC6) — that is a
  business-agnostic technical capability and belongs there if added.
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
- [Source: `docs/architecture/payout-and-capture-pending.md`] — the DRAFT doc that gates
  `skillars-7-1` D4 / `deferred-91` AC5 Part B (decision D2 — out of scope here).

## Dev Agent Record

### Agent Model Used

_(to be filled by the dev agent)_

### Debug Log References

### Completion Notes List

### File List

---

## Change Log

| Date | Change |
|------|--------|
| 2026-09-09 | Story created from `deferred-work.md` @ `ef37c039`. 12 ACs across payment reliability (tier race, strikes pagination, pack-based-booking catch narrowing, expiry-warning outbox atomicity), pack-pause hardening (timezone-correct past check, config default, non-silent record resolution — project-owner decision D1 = safe subset only), booking exception-message copy + i18n, a frontend Stripe-retry affordance, a messaging authorization decision, a credit-balance-view native-consumer trap, and a ledger-hygiene re-mine. Project-owner decisions D1–D5 captured. The "genuine one-off bugs & gaps" class is confirmed exhausted; seven ledger bullets verified stale/fixed at HEAD during creation and slated for deletion in AC12. Status: ready-for-dev. |
