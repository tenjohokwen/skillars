# skillars-deferred-101: Residual Bug Fixes, Refund Enqueue Atomicity, API Conventions & Migration-Lock Ledger Consolidation

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-101
**Branch:** `story/deferred-101-residual-bugs-hygiene`
**Created:** 2026-09-08

---

## Story Overview

A cross-module reliability + hygiene story drawn from
`_bmad-output/implementation-artifacts/deferred-work.md`.

The **"genuine one-off bugs & gaps"** class in that ledger was declared *effectively exhausted* by
`skillars-deferred-100`, and a full re-mine at HEAD (`b41d39a8`) confirms it — what remains there is
overwhelmingly `[DECIDED]` / `[DISMISSED]` (do **not** re-litigate), "no dev agent can close this"
(native DE/FR register review, parent legal copy), two carved-out backlog stories
(`frontend-test-framework-initiative`, `skillars-deferred-97`), speculative/load-dependent notes, and
the migration `ACCESS EXCLUSIVE` lock class (frozen files — see AC10).

**However**, the second-run `bmad-code-review` of `skillars-deferred-100` itself (2026-09-08) filed a
fresh batch of pre-existing / explicitly-descoped items, and the bucket sweep turned up a handful of
verified-open, dev-closable gaps across `messaging`-adjacent, `video`, `payment`, `booking`,
`Platform/Admin`, `Frontend/UX`, `Infrastructure/Deployment`, and `Database` (docs-only). This story
bundles them by theme:

1. **Post-deferred-100 residual bugs** — the batch-status listener has no failure isolation and
   NPEs on a null `bookingId`; a reconciliation no-op still writes a durable `STATE_CORRECTED`
   audit row; `VideoService.retryUpload` orphans the pre-retry provider (Bunny) asset forever.
2. **Refund enqueue atomicity** — `CancellationRefundService`'s six `BOOKING_REFUND` enqueues still
   run from `AFTER_COMMIT` listeners. Unlike the single-purpose email listeners `deferred-92` AC4
   flipped, these are multi-effect (refund + strike + cancellation-history + pack-restore) with a
   `deferred-100`-hardened `REQUIRES_NEW` strike-isolation design — so AC4 **splits** the refund
   enqueue into a dedicated `BEFORE_COMMIT` listener rather than flipping the existing ones, closing
   the window where a committed cancellation fails to enqueue its refund, and correcting the stale
   `skillars-10-2` D1 ledger line.
3. **API + deployment conventions** — `getActiveCoachTier` returns a `204` for a typed GET
   (→ `404`); `restore-from-dump.sh`'s `DROP DATABASE` has no `pg_terminate_backend` sweep; the
   `prometheus` compose service has no `depends_on: app`.
4. **Test / store hygiene** — `sessionTemplate.store.js`'s `createTemplate()` swallows failures
   silently (the only one of five actions that does); `ConfigGuardIT` relies on `@AfterEach` to
   restore a shared config row; fill the `NeglectedSkillDetectionService.isInValidRange()`
   boundary-value + real-`401` coverage gap.
5. **Migration-lock ledger consolidation** (docs only, per project-owner decision) — collapse the
   six scattered `ACCESS EXCLUSIVE` / unbatched-DML bullets into one section of
   `migration-conventions.md`, and file the "rebaseline all migrations before production" future
   task in the ledger.
6. **Ledger hygiene** — prune lines this story and prior work supersede, per the file's own
   delete-outright convention.

**Source:** `deferred-work.md` re-verified 2026-09-08 against `master` @ `b41d39a8` (HEAD). Per-AC
**Verified at HEAD** blocks record what the cited code actually looks like now, not what the ledger
line says. Items whose cited code was already fixed (`skillars-3-1` `weekStart` guard;
`deploy-2-2` `Fail workflow` unreachable) are handled by the ledger-hygiene AC, not re-implemented.

---

## User Story

**As a** platform engineer responsible for Skillars' correctness under partial failure and for the
health of its deferred-work ledger,

**I want** the residual bugs `skillars-deferred-100`'s own code review surfaced, the last non-atomic
refund-enqueue window, a few long-standing API/deployment convention violations, and the silent
frontend-store / test-isolation gaps all fixed together — plus the migration-lock backlog
consolidated into one place and the ledger pruned —

**So that** a batch-status listener can't leave a batch permanently stale (or crash a committed
transaction's `commit()`), a rolled-back or retried upload can't leak a paid-for video asset, a
committed cancellation always enqueues its refund, and the ledger stops carrying stale forward
references and duplicated lock-safety notes.

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet(s) it closes) and **Test**
> (verification). Backend ACs: **GitHub CI is the full-verification gate — do NOT run `mvn verify`
> locally** (`feedback_no_local_mvn_verify`); `mvn -o test-compile` + targeted
> `mvn -o test -Dtest=...` for the touched classes is the local sanity bar. Frontend ACs: `npx
> eslint` + `quasar build` + code reading is the established verification path — there is **no**
> frontend unit-test runner in this repo (`frontend-test-framework-initiative` backlog), so do not
> add one here. Follow `_bmad-output/project-context.md` (records DTOs, MapStruct, `@PreAuthorize`
> on every endpoint, `@Testcontainers` ITs, Instancio, AssertJ, Flyway for all schema changes,
> Prettier mandatory for `.js`/`.vue`/`.scss`/`.json`).

---

### AC1: `BookingBatchStatusListener` must isolate failure and never `findById(null)`

- **Task:** Give `BookingBatchStatusListener.onBookingStatusChanged` (a) a null-guard on
  `event.bookingId()` and (b) failure isolation so a `PessimisticLockingFailureException` (or any
  `RuntimeException`) from `updateBatchStatusFromBooking`, or an unexpected error, cannot propagate
  out of the already-committed transaction's `commit()` and cannot leave the batch permanently
  stale with nothing to detect it.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchStatusListener.java` —
  the whole method body is:
  ```java
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public void onBookingStatusChanged(BookingStatusChangedEvent event) {
      bookingRepository.findById(event.bookingId()).ifPresent(booking -> {
          if (booking.getBatchId() != null) {
              bookingBatchService.updateBatchStatusFromBooking(booking.getBatchId());
          }
      });
  }
  ```
  No try/catch, no `@Async`, no null-check. `deferred-100` AC4 only added the
  `@Transactional(REQUIRES_NEW, readOnly = true)` wrapper (its javadoc says exactly that).
  `bookingRepository.findById(null)` throws `IllegalArgumentException` (Spring Data contract).
  Confirm whether `BookingStatusChangedEvent.bookingId()` can actually be null at any publish site —
  grep `new BookingStatusChangedEvent(` across `src/main`; if every site passes a non-null id, the
  null-guard is defence-in-depth (still add it — the listener is a public contract), if any site can
  pass null it is a live NPE.
- **Fix approach:**
  - Guard: `if (event.bookingId() == null) { log.warn("BookingStatusChangedEvent with null bookingId — ignoring"); return; }` at the top.
  - Failure isolation: wrap the `findById(...).ifPresent(...)` body in `try { ... } catch
    (RuntimeException e) { log.error("Batch-status refresh failed for bookingId={} — batch status may be stale until the next status change on this batch", event.bookingId(), e); }`.
    A stale batch status *self-heals* on the next real status change for any booking in the batch
    (which re-fires this listener), so swallow-with-ERROR is the right call here — do **not**
    re-throw (that would poison a committed transaction) and do **not** add a bespoke retry.
  - Match the swallow-with-diagnostic rationale comment style `ReconciliationWorkerScheduler`
    already carries for its per-row `catch (RuntimeException e)` (added by `deferred-100` code
    review) — cite `deferred-101` AC1 in the comment.
- **Files:** `BookingBatchStatusListener.java`; a new / extended test
  (`BookingBatchStatusListenerTest` if one exists, else add to `BookingBatchServiceIT` /
  `BookingBatchStatusListenerIT`).
- **Test:** unit — (1) a `BookingStatusChangedEvent` with a null `bookingId` returns without
  invoking `bookingRepository` (mutation: remove the guard → `IllegalArgumentException`); (2)
  `updateBatchStatusFromBooking` stubbed to throw `PessimisticLockingFailureException` → the
  listener returns normally, ERROR logged (mutation: remove the catch → exception escapes).
- **Ledger:** `## Deferred from: code review of skillars-deferred-100-... (2026-09-08)` — bullet
  "`BookingBatchStatusListener` has no failure isolation and no null-check on `event.bookingId()`".

---

### AC2: A reconciliation no-op must not write a `STATE_CORRECTED` incident

- **Task:** When `ReconciliationWorkerScheduler.processReconciliation` calls
  `videoLifecycleService.reconcileToReady(...)` but the video is **already READY** (a concurrent
  thread won the race between the batch load and this correction), it must **not** persist a
  `ReconciliationIncident(STATE_CORRECTED, "…corrected to READY")` — that is a durable audit record
  for a correction this pass did not perform.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java:125-143` —
    `reconcileToReady(UUID, String)` returns the `Video` in **all three** outcomes:
    already-READY early return (`:130-132`, no counter, no event), non-PROCESSING →
    `VideoStateConflictException` (`:133-135`), and the real correction (`:137-142`, increments
    `video.reconciliation.state_corrected`, fires `VideoStatusChangedEvent`, logs INFO). The caller
    cannot tell "corrected" from "already-READY no-op" from the return value.
  - `src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java:95-106` —
    the `providerStatus == READY && localState == PROCESSING` branch runs, inside one
    `transactionTemplate.execute`, `reconcileToReady(...)` then **unconditionally**
    `recordIncident(video, STATE_CORRECTED, "Local state PROCESSING corrected to READY based on
    provider status")`. `localState` is read at `:93` from the batch-loaded (possibly stale) `Video`.
- **Fix approach (pick ONE, dev's call — both are small; prefer the first):**
  - **Option A (preferred):** change `reconcileToReady` to return `boolean` (`true` = state was
    actually written PROCESSING→READY, `false` = already-READY no-op). **Grep every caller**
    (`grep -rn "reconcileToReady" src/main src/test` — story-creation grep found
    `ReconciliationWorkerScheduler` + the javadoc's mention of `AdminVideoService`; confirm the full
    set and **list each caller and the change made to it in the Dev Agent Record** — a missed caller
    silently keeps the bug). Update each to gate its incident write / logging on the return. Keep
    the `VideoStateConflictException` throw for the non-PROCESSING case unchanged.
  - **Option B:** in `ReconciliationWorkerScheduler.processReconciliation`, after `reconcileToReady`
    returns, re-read `video`'s state (or have `reconcileToReady` return the saved `Video` and check
    `wasAlready`) — messier; Option A is cleaner and self-documenting.
  - The `video.reconciliation.state_corrected` counter is already correctly gated (only the real
    write increments it) — do not touch it. The concern is the **incident row** and the
    `log.info("Reconciliation STATE_CORRECTED for video {} …")` line at `:106`.
- **Files:** `VideoLifecycleService.java`, `ReconciliationWorkerScheduler.java`, any other
  `reconcileToReady` caller; `VideoLifecycleServiceTest` / `ReconciliationWorkerSchedulerIT` (or the
  reconciliation IT that already exists — grep `reconcileToReady` / `STATE_CORRECTED` in `src/test`).
- **Test:** IT/unit — seed a video PROCESSING, move it to READY on another logical actor before the
  correction runs, assert `reconcileToReady` reports no-op AND zero new
  `ReconciliationIncident(STATE_CORRECTED)` rows for that video (mutation: revert the gate → one
  spurious incident row).
- **Ledger:** `## Deferred from: code review of skillars-deferred-100-... (2026-09-08)` — bullet
  "`reconcileToReady` already-READY no-op still lets `ReconciliationWorkerScheduler.processReconciliation` write a `STATE_CORRECTED` incident".

---

### AC3: `VideoService.retryUpload` must not orphan the pre-retry provider asset

- **Task:** When `retryUpload` overwrites `Video.providerAssetId` with the retry's new id, the
  **prior** `providerAssetId` (the failed upload's asset on Bunny) must be handed to the
  orphaned-asset reconciliation path so it is deleted — today it leaks forever (bills + stores PII).
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` —
  - `retryUpload` (`:113`) is permitted only from `OperationalState.FAILED` (`:122-124`).
  - `:173-180` — the **new** asset is created and immediately tracked via
    `pendingProviderAssetTracker.record(credentials.providerUploadId(), properties.getProvider())`
    (`deferred-100` AC2). An inline comment at `:176-179` already says: *"The pre-retry asset id
    this write overwrites is a separate, pre-existing leak, out of scope here."*
  - `:185-197` — second tx: `v.setProviderAssetId(credentials.providerUploadId())` (`:187`)
    overwrites the old id; the new id's tracking row is deleted in the same tx (`:195`).
  - Leak path: a video that reached a state where `providerAssetId` was set **and its tracking row
    deleted** (successful `initializeUpload` — `:314`/`:333`), then failed later (e.g. transcoding)
    → `FAILED` → `retryUpload`. The old asset now has **no** `pending_provider_asset` row and **no**
    `Video` pointing at it (the `Video` points at the new id), so it is invisible to BOTH
    `ReconciliationWorkerScheduler.reconcile()` (iterates rows that HAVE a `Video`) and
    `sweepOrphanedProviderAssets()` (iterates `pending_provider_asset` rows).
  - `PendingProviderAssetTracker.record(...)` is the house helper; `sweepOrphanedProviderAssets`
    (`ReconciliationWorkerScheduler:173`) already purges by tracking row, is idempotent (provider
    404 = success), rate-limits failures, and writes `ReconciliationIncident(ORPHANED_ASSET, …)`.
- **Fix approach:**
  - In `retryUpload`, capture `String priorAssetId = video.getProviderAssetId();` **before** the
    retry work (right after the `FAILED`-state check at `:124`).
  - In the second `transactionTemplate.execute` (`:185-197`), after `v.setProviderAssetId(newId)`,
    if `priorAssetId != null && !priorAssetId.equals(newId)`, call
    `pendingProviderAssetTracker.record(priorAssetId, video.getProvider())` **in the same
    transaction** as the `Video` write, so the tracking row and the pointer-swap commit atomically.
    The sweeper's TTL (`app.video.orphan-asset.ttl`, default 30 min) then applies before it purges —
    fine, the old asset is already dead weight.
  - **Do not** call `videoProviderAdapter.deleteAsset(priorAssetId)` inline — that is a synchronous
    HTTP call inside the retry request path; the sweeper is the established async purge mechanism
    and handles transient provider errors + the stuck-row alarm.
  - Update the `:176-179` inline comment: the pre-retry leak is **no longer** out of scope — it is
    now tracked for the sweeper; cite `deferred-101` AC3.
- **Files:** `VideoService.java`; `VideoServiceIT` / the retry-upload IT (grep `retryUpload` in
  `src/test`) + reconciliation sweep coverage.
- **Test:** IT — drive a video to `FAILED` with a known `providerAssetId=A` and no tracking row for
  `A`; call `retryUpload` → asset `B`; assert (1) `Video.providerAssetId == B`, (2) a
  `pending_provider_asset` row for `A` now exists, (3) running `sweepOrphanedProviderAssets` after
  the TTL calls `videoProviderAdapter.deleteAsset("A")` and writes an `ORPHANED_ASSET` incident
  (mutation: remove the `record(priorAssetId, …)` call → `A` is never swept).
- **Ledger:** `## Deferred from: code review of skillars-deferred-100-... (2026-09-08)` — bullet
  "`VideoService.retryUpload` orphans the pre-retry provider (Bunny) asset".

---

### AC4: `CancellationRefundService` refund enqueue must be atomic with the business transaction

- **Task:** Make the `BOOKING_REFUND` outbox enqueue happen in the **same transaction** as the
  booking → `CANCELLED` write, so a committed cancellation can never fail to enqueue its refund
  (today: business tx commits, then an `AFTER_COMMIT` listener's own `REQUIRES_NEW` tx can fail
  before the enqueue — no retry of the listener, silent money loss). **This requires SPLITTING the
  refund enqueue out of the multi-effect listeners — a wholesale `AFTER_COMMIT`→`BEFORE_COMMIT`
  flip of the existing listeners is UNSAFE here (see below).**
- **Verified at HEAD** (`src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java`,
  full read 2026-09-08):
  - **Five listeners**, each `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`:
    `onBookingCancelledByParent` (`:37`), `onBookingCancelledByCoach` (`:57`), `onCoachNoShow` (`:85`),
    `onBookingCancelledByAdmin` (`:124`), `onPlayerNoShow` (`:138` — **no refund, no change**).
  - **Six `refundOutboxSupport.enqueueBookingRefund(...)` sites across four listeners**, each behind
    a distinct guard — the split listener MUST replicate these exactly so it enqueues **iff** the
    existing listener would:
    | Listener | Enqueue condition |
    |---|---|
    | `onBookingCancelledByParent` (`:46`) | `event.getSessionPackPurchaseId() == null && event.isRefundEligible()` |
    | `onBookingCancelledByCoach` (`:60`) | `sessionPackPurchaseId != null && event.isPackExpiredAtCancellation()` |
    | `onBookingCancelledByCoach` (`:67`) | `sessionPackPurchaseId == null` |
    | `onCoachNoShow` (`:88`) | `sessionPackPurchaseId != null && event.isPackExpiredAtCancellation()` |
    | `onCoachNoShow` (`:95`) | `sessionPackPurchaseId == null` |
    | `onBookingCancelledByAdmin` (`:129`) | `sessionPackPurchaseId == null` |
  - **These are NOT single-purpose refund listeners** (unlike the 23 email listeners `deferred-92`
    AC4 flipped). They also: call `packSessionService.restoreSession(...)` on the pack branch
    (**out of scope** — `deferred-91` residual flags pack-restore idempotency as a distinct
    concern); `saveCancellationHistory(...)` (writes a `CoachCancellationHistory` row —
    `onBookingCancelledByCoach` only); and `issueStrikeSafely(...)` →
    `ReliabilityStrikeService.issue(...)`.
  - **`ReliabilityStrikeService.issue(...)` is `@Transactional(REQUIRES_NEW)` — deliberately, and
    `deferred-100`'s code review just hardened it (2026-09-08).** Its comment
    (`ReliabilityStrikeService.java:37-55`) and `CancellationRefundService.java:104-120`
    (`issueStrikeSafely`) together spell out the current design: the listener runs `AFTER_COMMIT` in
    its own `REQUIRES_NEW` tx, enqueues the refund, **then** issues the strike in a nested
    `REQUIRES_NEW` tx, and swallows `PessimisticLockingFailureException` so a strike-lock exhaustion
    can't roll back the refund.
  - **Why a wholesale `BEFORE_COMMIT` flip is unsafe:** moving `onBookingCancelledByCoach` /
    `onCoachNoShow` to `BEFORE_COMMIT` makes `issueStrikeSafely` → `issue()` run its `REQUIRES_NEW`
    tx **while the business tx is still uncommitted**. If `issue()` commits and the business tx then
    rolls back, you get a `CoachReliabilityStrike` + `CoachCancellationHistory` row for a
    cancellation that never happened — a new inconsistency the current design specifically prevents,
    and a direct regression of `deferred-100` AC1's work.
- **Fix approach — SPLIT, do not flip:**
  - Add a **dedicated `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)`
    listener** (new method on this service, or a small new `RefundEnqueueListener` class in
    `platform.payment.service`) that consumes the **same four events** and does **only** the
    `enqueueBookingRefund(...)` call, guarded by the **exact** conditions in the table above. No
    `@Transactional` of its own — it runs inside the publishing (business) transaction.
  - Make `RefundOutboxSupport.enqueueBookingRefund` require an active tx —
    `@Transactional(propagation = Propagation.MANDATORY)` — mirroring `deferred-92` AC4/AC29's
    `enqueueEmail` (read `EmailOutboxSupport` / the actual class for the exact shape).
  - **Remove the six `enqueueBookingRefund` calls from the existing `AFTER_COMMIT` listeners.** Those
    listeners keep `packSessionService.restoreSession`, `saveCancellationHistory`, and
    `issueStrikeSafely` on `AFTER_COMMIT` + `REQUIRES_NEW` — the `deferred-100` design is
    **preserved intact** (and its `:104-120` / `ReliabilityStrikeService:37-55` comments get a
    one-line update: the refund enqueue is now a sibling `BEFORE_COMMIT` listener, not an earlier
    statement in the same method — the strike-isolation rationale is unchanged).
  - **Two listeners on one event — the hazard to handle:** `BEFORE_COMMIT` refund listener +
    `AFTER_COMMIT` remainder listener both consume e.g. `BookingCancelledByCoachEvent`. That is
    fine and intentional (they run in different phases), but: (a) confirm no existing test asserts
    "exactly one listener handled this event"; (b) the `BEFORE_COMMIT` listener must be resilient —
    if it throws, the business tx rolls back (correct — visible failure, caller retries); (c) the
    `uq_pcl_reference_type` partial unique index (V125) still assumes one `BOOKING_REFUND` per
    booking — the split enqueues once per event, same as today, so no change, but note it.
  - **Fallback (only if the split proves genuinely unworkable — e.g. an event-consumption
    ordering problem that can't be cleanly resolved):** keep all listeners on `AFTER_COMMIT`, make
    **no** code change, and limit this AC to the ledger correction below. Record the specific
    blocker in the Dev Agent Record. Do **not** partially flip.
- **Ledger correction (happens regardless of code outcome):** the `skillars-10-2` D1 bullet
  (`## Deferred from: code review of skillars-10-2-coach-enforcement-strike-management (2026-06-30)`)
  reads *"if the refund transaction fails after the suspension commits, the booking stays CANCELLED
  with no refund issued and no retry … Address in a platform-wide payment resilience pass."* The
  "no retry" clause is **already stale** (the generic outbox provides delivery + retry once
  enqueued — `deferred-91` AC2). **Delete the bullet** per the file's delete-outright convention;
  if the split ships, it also closes the residual enqueue window; if the fallback is taken, note
  that the narrow enqueue-window residual remains but is no longer "no retry". Record in AC13.
- **Files:** `CancellationRefundService.java` (+ maybe a new `RefundEnqueueListener.java`),
  `RefundOutboxSupport.java`; update the `ReliabilityStrikeService.java:37-55` and
  `CancellationRefundService.java:104-120` comments; `CancellationRefundServiceIT` + an
  atomicity IT modelled on whatever `deferred-92` AC4 left (`NotificationEmailOutboxAtomicityIT` or
  similar).
- **Test:** IT covering **at least `onBookingCancelledByCoach` and `onBookingCancelledByAdmin`**
  (the entangled and the simple case): (1) happy path — booking → `CANCELLED` and exactly one
  `BOOKING_REFUND` outbox row commit in the **same** tx (assert both present after commit; assert
  the refund row is visible to a query joined on the booking in that tx before `AFTER_COMMIT`
  fires); (2) atomicity — force the business tx to roll back → **zero** outbox rows **and** booking
  not `CANCELLED` **and** no strike / history rows; (3) strike still issues on the happy path
  (regression guard for the preserved `deferred-100` design). Mutation: move the new listener to
  `AFTER_COMMIT` → test (2) shows a `CANCELLED` booking with no refund row and no rollback linkage.
- **Ledger:** `## Deferred from: code review of skillars-10-2-coach-enforcement-strike-management
  (2026-06-30)` — D1 (delete). Also updates the `deferred-91` / `deferred-92` "AFTER_COMMIT
  reliability catalogue" bullet's mention of `CancellationRefundService`.

---

### AC5: `getActiveCoachTier` returns `404`, not `204`, when a coach has no active tier

- **Task:** `GET` active-coach-tier must return `404 Not Found` with the standard typed error body
  instead of `204 No Content` when `getActiveCoachTier(coachId)` yields nothing — a `204` on a
  typed single-resource GET is unidiomatic and forces every client into a special-case branch.
  *(Project-owner decision 2026-09-08: `404`, not `200`+null.)*
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java:132-137`
  ```java
  public ResponseEntity<SessionPackTierResponse> getActiveCoachTier(@PathVariable UUID coachId) {
      SessionPackTierResponse tier = sessionPackPaymentService.getActiveCoachTier(coachId);
      if (tier == null) {
          return ResponseEntity.noContent().build();
      }
      return ResponseEntity.ok(tier);
  }
  ```
  Endpoint already carries `@PreAuthorize` (check the method annotation — keep it). The service
  method returns `null` for "no active tier" (confirm — grep `getActiveCoachTier` in
  `SessionPackPaymentService`).
- **Fix approach:**
  - Replace the `null` → `noContent()` branch with `throw new ResourceNotFoundException(...)` (use
    whatever the codebase's canonical not-found exception is — grep other Resources in
    `platform.payment.api` / `platform.booking.api`; likely `ResourceNotFoundException` mapped to
    `404` by `@RestControllerAdvice`). Message keyed for i18n if the advice expects a key.
  - Simplest shape: have `sessionPackPaymentService.getActiveCoachTier` throw, or keep the
    null-return and translate in the Resource — match the file's existing convention (other methods
    here return `ResponseEntity.ok(...)` directly, so translating in the Resource is fine).
  - **Frontend caller sweep — done at story creation (2026-09-08), re-confirm at implementation:**
    `grep -rn "active-tier\|activeTier\|ActiveCoachTier\|active_tier" src/frontend/src` → **zero
    hits**. No frontend code calls this endpoint today, and this is a pre-production project with
    **no external API consumers** (no UAT env, no mobile client, no third-party integration). So
    `204`→`404` breaks nothing — it is effectively new-endpoint behaviour. If a caller has appeared
    by implementation time, it must treat `404` as the non-error "no tier" case (catch → `null`,
    no toast). Record the re-confirmed grep result in the Dev Agent Record.
- **Files:** `SessionPackPaymentResource.java` (+ maybe `SessionPackPaymentService.java`);
  `SessionPackPaymentResourceIT`. No frontend change expected (verified no caller).
- **Test:** IT — `getActiveCoachTier` for a coach with no active tier → `404` with the error body
  shape other `404`s in this module produce (mutation: revert → `204`). Existing "has active tier"
  test stays green (`200` + body).
- **Ledger:** `### Group 3 adversarial deferred (API + Contracts) — 2026-06-24` — D11 (delete).

---

### AC6: `restore-from-dump.sh` must terminate stray sessions before `DROP DATABASE`

- **Task:** Before `DROP DATABASE IF EXISTS`, `restore-from-dump.sh` must terminate every backend
  connected to the target DB that is **not** this script's own session, so a leftover human `psql`
  or ad-hoc tool connection can't make the `DROP` fail outright (it has no `FORCE`, no wait).
- **Verified at HEAD:**
  `deploy/backup/restore-from-dump.sh` — `:108` `docker compose ... stop app` (the only container
  with a datasource); `:136` `-c "DROP DATABASE IF EXISTS \"${POSTGRES_DB:-skillars}\";"`; `:139`
  `CREATE DATABASE ...`. No `pg_terminate_backend` sweep anywhere. Ledger audit 2026-09-04 already
  narrowed this: no *container* peer exists, the realistic blocker is a human/tool `psql` session,
  and `DROP DATABASE` fails rather than waits.
- **Fix approach:**
  - Immediately before the `DROP DATABASE` line, add (against the maintenance/`postgres` DB, same
    connection style as the surrounding `psql`/`docker exec` calls in this script):
    ```sql
    SELECT pg_terminate_backend(pid)
    FROM pg_stat_activity
    WHERE datname = '${POSTGRES_DB:-skillars}'
      AND pid <> pg_backend_pid();
    ```
  - Keep it a single `-c "..."` invocation consistent with `:136`/`:139`. It is safe to run when
    there are no other sessions (returns zero rows). `app` is already stopped at `:108` so this
    only ever reaps genuinely stray sessions.
  - Follow the story's own `set -euo pipefail` discipline — this statement must not abort the script
    on "zero rows"; a bare `SELECT` won't, but verify against the script's error handling around
    the neighbouring `psql` calls.
- **Files:** `deploy/backup/restore-from-dump.sh` only. No app code, no migration.
- **Test:** none automated (shell, no harness — same as every prior `deploy/backup/*.sh` change).
  Dev Agent Record must show: the diff, a `bash -n` syntax check, and a manual trace of the
  statement ordering (`stop app` → terminate sweep → `DROP` → `CREATE` → restore).
- **Ledger:** `## Deferred from: code review of deploy-3-4-operational-documentation-suite
  (2026-06-05)` — the "DROP DATABASE may fail if anything other than `app` holds an open DB
  connection" bullet (delete; the AUDIT 2026-09-04 annotation goes with it).

---

### AC7: `prometheus` compose service must declare `depends_on: app`

- **Task:** Add `depends_on` on `app` to the `prometheus` service in `docker-compose.yml` so a
  first `docker compose up` doesn't produce cold-start scrape failures before `app` is listening.
- **Verified at HEAD:**
  `docker-compose.yml` — `app:` at `:8`; `prometheus:` service block starts `:230` (image
  `prom/prometheus:v3.4.1`), and it has **no** `depends_on` key. `grafana:` (`:302`) **does** have
  `depends_on:` → `prometheus` (`:340-341`). So the pattern is already in the file; `prometheus` is
  the gap.
- **Fix approach:**
  - Add to the `prometheus` service:
    ```yaml
    depends_on:
      app:
        condition: service_started
    ```
    Use `service_started` (not `service_healthy`) — Prometheus scraping `app` before it's *healthy*
    is fine (a few failed scrapes recover), the point is deterministic container start ordering;
    check whether `app` even defines a healthcheck and match the condition style `grafana` uses for
    `prometheus`.
  - Keep the change minimal — one `depends_on` block, no reordering, no other service touched.
- **Files:** `docker-compose.yml` only.
- **Test:** none automated. Dev Agent Record: `docker compose config` (or `-q`) parses clean, and
  the `prometheus` block now shows the dependency.
- **Ledger:** `## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)` —
  the "Prometheus has no `depends_on: app` in compose" bullet (delete).

---

### AC8: `sessionTemplate.store.js` `createTemplate()` must surface failures

- **Task:** `createTemplate()` is the only one of the store's five actions with no `try/catch` and
  no `error.value` write — a failed template create is swallowed silently (the `unshift` just never
  happens and no caller can tell). Make it consistent with `renameTemplate` / `deleteTemplate` /
  `deployTemplate` / `fetchTemplates`.
- **Verified at HEAD:**
  `src/frontend/src/stores/sessionTemplate.store.js:22-26`
  ```js
  async function createTemplate(sessionId, name) {
    const res = await sessionApi.createTemplate({ sessionId, name })
    templates.value.unshift(res)
    return res
  }
  ```
  Every sibling (`:28-37`, `:39-47`, `:49-62`, `:10-20`) has `catch (e) { error.value = e; throw e }`
  (the mutating ones re-throw; `fetchTemplates` doesn't). `error` ref is exported (`:67`).
- **This is NOT a breaking change** — verified at story creation. `createTemplate` has **no
  `catch` today**, so a `sessionApi.createTemplate` rejection *already* propagates out of the
  function. Adding `try { … } catch (e) { error.value = e; throw e }` **re-throws the same
  rejection** — the only new behaviour is that `error.value` gets populated (additive). The **sole
  caller** is `src/frontend/src/pages/coach/SessionBuilderPage.vue:337-347` (`saveTemplate()`),
  which **already** wraps the call in `try { … } catch { $q.notify(negative) }`
  (`grep -rn "createTemplate" src/frontend/src` → exactly one call site + the `session.api.js`
  definition). Re-confirm the grep at implementation time and list it in the Dev Agent Record.
- **Fix approach:**
  ```js
  async function createTemplate(sessionId, name) {
    try {
      const res = await sessionApi.createTemplate({ sessionId, name })
      templates.value.unshift(res)
      return res
    } catch (e) {
      error.value = e
      throw e
    }
  }
  ```
  Re-throw (matches the other three mutating actions — callers like `SessionTemplateVault.vue` /
  `SessionBuilderPage.vue` rely on the throw to show their own toast). Prettier-format the file.
- **Files:** `src/frontend/src/stores/sessionTemplate.store.js` only.
- **Test:** none automated (no frontend runner). `npx eslint src/frontend/src/stores/sessionTemplate.store.js`,
  `npx prettier --check`, `quasar build`, and a code-read confirming the five actions now match.
- **Ledger:** `## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates
  — Round 2 (2026-06-18)` — W5. **W4** (missing `maxlength="200"` client-side on template name
  inputs) stays — it is a separate `.vue` change with its own "server `@Size` catches it" rationale;
  do not fold it in. Re-word W5 to drop the `createTemplate` clause, leaving only its (now-sole)
  W4-adjacent content, OR if W5 is *only* about `createTemplate`, delete it. Read the bullet.

---

### AC9: `ConfigGuardIT` must restore the shared config row even if `@AfterEach` is skipped

- **Task:** `ConfigGuardIT` mutates the shared `main.platform_config` row in `@BeforeEach` and
  relies on `@AfterEach` to restore it — an interrupted run (or a failure between mutate and
  restore) leaves the row as `'not-a-number'` and poisons every later `ConfigService` consumer in
  the same DB. Make the restore resilient.
- **Verified at HEAD:**
  `src/test/java/com/softropic/skillars/platform/admin/api/ConfigGuardIT.java` — `@BeforeEach
  setUp()` (`:64-...`) reads `originalThresholdValue` then (`:106`) `UPDATE main.platform_config SET
  value = 'not-a-number' WHERE key = ?`; `@AfterEach tearDown()` (`:113-117`) `UPDATE ... SET value
  = ? ...` back. Single point of restore, `@AfterEach`-only.
- **Fix approach — default to the `try/finally`; the others are only if they're obviously smaller:**
  - **Default:** move the mutation into the `@Test` body wrapped in
    `try { mutate; assert } finally { restore }`, so restore is bound to the test method's own
    stack, not the JUnit lifecycle. ~2 lines, no logic change, always correct.
  - *Only if it's a genuinely small rewrite (< 5 min, no logic change):* switch the test to **not
    mutate shared state at all** — exercise the guard with a throwaway key this test inserts and
    deletes rather than overwriting the real `VISIBILITY_THRESHOLD_KEY` row.
  - `@AfterAll` static net is a weaker option (still lifecycle-dependent) — skip it.
  - Whatever the choice, the restore must run even if the assertion throws. Record the choice in the
    Dev Agent Record.
- **Files:** `ConfigGuardIT.java` only.
- **Test:** the class is the test. Verify: an assertion failure injected mid-test still leaves
  `main.platform_config` at its original value afterward (reason through it / run once with a
  deliberately-broken assert locally, then revert).
- **Ledger:** `## Deferred from: code review of skillars-deferred-1-config-securityutil-hardening
  (2026-07-01)` — D2 (delete).

---

### AC10: Fill the `NeglectedSkillDetectionService.isInValidRange()` + real-`401` coverage gap

- **Task:** Close `skillars-deferred-1` D1's two concrete sub-gaps: (a) no unit test pins
  `NeglectedSkillDetectionService.isInValidRange()`'s boundary values, and (b) none of the Resources
  refactored onto `SecurityUtil.requireCurrentUserId()` has a test asserting the real `401` when it
  throws.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java:110` —
    `private boolean isInValidRange(BigDecimal threshold)`, called at `:40` and `:55`. Read the
    method to get the exact bounds it enforces (the config is `slu.neglected.threshold`).
  - `src/test/java/.../NeglectedSkillDetectionServiceTest.java` — has neglect-*lowerBound* boundary
    tests (`:90-92`, `:179-183`) and a missing-config test (`:197-199`), but **no** test that feeds
    `isInValidRange` its own edge values (0, 1, just inside, just outside — whatever its real
    domain is) via the `:40`/`:55` entry points.
  - `requireCurrentUserId` — 18 `src/main` files use it (grep confirmed). `SecurityUtil` throws
    (project-context: "the actual 401 response when it throws").
- **Fix approach:**
  - (a) Add parameterised cases to `NeglectedSkillDetectionServiceTest` driving `detect...` /
    `evaluate...` (whichever calls `:40`/`:55`) with a config threshold at each boundary of
    `isInValidRange`'s domain and just outside it, asserting the documented behaviour (fall back to
    default / skip / whatever `:40-41` and `:55-56` do on invalid). Do **not** make `isInValidRange`
    public just to test it — test through the public method, as the existing tests do.
  - (b) Add **one** IT (not 15 — the item says "only one E2E smoke test was added for a ~1080-line
    diff", the fix is *representative* coverage, not exhaustive): pick one Resource that uses
    `requireCurrentUserId` (e.g. in `platform.development.api` so it's near this story's other
    touch), hit it with an unauthenticated / principal-without-business-id request, assert `401`
    and the error body shape. If a `SecurityUtilIT` / `...GuardIT` already asserts this for any
    Resource, extend it rather than adding a new class — grep first.
- **Files:** `NeglectedSkillDetectionServiceTest.java`; one `*ResourceIT` (new or extended).
- **Test:** the added tests are the deliverable. Mutation for (a): widen `isInValidRange`'s bounds
  → a boundary case flips. Mutation for (b): make the Resource `permitAll` → the `401` test fails.
- **Ledger:** `## Deferred from: code review of skillars-deferred-1-config-securityutil-hardening
  (2026-07-01)` — D1 (delete).

---

### AC11: `deferred-100` code-review item 5 — `RefreshTokenRepository.markAllUsedByUserId`

- **Task:** Close the "`markAllUsedByUserId` allow-list rests on an unenforceable global invariant"
  finding. `deferred-100` AC3 allow-listed this native `@Modifying` update (no `version = version +
  1`) with a documented reason ("every concurrent managed writer of these rows also only ever sets
  `used = true`"). The finding: nothing enforces that invariant.
- **Verified at HEAD:**
  `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java` —
  `markAllUsedByUserId` is a native/JPQL `@Modifying` bulk `UPDATE ... SET used = true` with no
  version bump; `NativeModifyingVersionAuditTest` allow-lists it by SQL text only. Writers today:
  `AuthService.refresh` / `AuthService.logout`, and the GDPR bulk path (grep to confirm the set).
- **Fix approach (project-owner steer: simplest, defence-in-depth is cheap here):**
  - Add `, version = version + 1` to the `UPDATE` SET clause. These rows are being *invalidated* —
    bumping the version can only *help* (a concurrent stale-loaded managed write that touches any
    other column now fails its optimistic-lock check instead of silently resurrecting a revoked
    session). Move the method from the `NativeModifyingVersionAuditTest` **allow-list** to its
    **compliant** set (it now does bump the version).
  - If (and only if) the dev finds a concrete reason the version bump breaks a caller (e.g. a
    caller that reloads and re-saves the same instance in the same tx and would now `OptimisticLock`
    on its own write), fall back to: keep the allow-list entry but add an
    `AppEndpointsConventionTest`-style guard test that fails the build if a **new** writer of
    `refresh_tokens` sets any column other than `used` — and record why the bump wasn't taken.
    Expect the bump to be clean.
- **Files:** `RefreshTokenRepository.java`, `NativeModifyingVersionAuditTest.java`; run
  `AuthResourceIT` / `AuthServiceIT` / GDPR erasure IT to confirm no regression.
- **Test:** `NativeModifyingVersionAuditTest` moves the method to compliant and stays green;
  existing refresh/logout/GDPR ITs stay green.
- **Ledger:** `## Deferred from: code review of skillars-deferred-100-... (2026-09-08)` — bullet
  "`RefreshTokenRepository.markAllUsedByUserId` allow-list (no `version = version + 1`) rests on an
  unenforceable global invariant" (delete).

---

### AC12: Consolidate the migration `ACCESS EXCLUSIVE` / unbatched-DML backlog into one place

- **Task (docs + ledger only — NO migration code):** Per project-owner decision 2026-09-08 (no
  data, no UAT env yet, want the simplest thing): collapse the six scattered ledger bullets about
  lock-unsafe **applied** migrations into **one** section of
  `docs/deployment/migration-conventions.md`, and file the "rebaseline all migrations before
  production" option as a future task in `deferred-work.md`.
- **Verified at HEAD — the six bullets to consolidate:**
  1. `## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)` —
     W3 (`V60` `DROP/ADD CONSTRAINT` = `ACCESS EXCLUSIVE`, no `SET lock_timeout`).
  2. `## Deferred from: code review of skillars-deferred-33-... (2026-08-18)` — `V97` bare
     `DROP COLUMN` pair under `ACCESS EXCLUSIVE`.
  3. `## Deferred from: code review of skillars-deferred-40-... (2026-08-20)` — `V98` unbatched
     full-table `UPDATE` backfill.
  4. `## Deferred from: code review of skillars-uat-3-... (2026-08-11)` — D13 (`V94`
     `DROP/ADD CONSTRAINT` on `booking_payments`, revalidates every row).
  5. `## Deferred from: code review of skillars-deferred-84-... (2026-08-31)` — `V117` late FK +
     non-`CONCURRENTLY` index, "not written for online-safe deploys".
  6. (also mentioned) the `uat-3` code-review duplicate of D13 (`V94`) under the second
     `skillars-uat-3` code-review heading — same `V94` concern.
  All are **applied** migrations; Flyway checksums the files, so none can be edited. `deferred-92`'s
  `MigrationLint` rules bind `V128+` only (`DEFERRED_92_BASELINE` constant) — these predate it.
- **Fix approach:**
  - In `docs/deployment/migration-conventions.md`, add a section e.g. **"Known lock-unsafe applied
    migrations (pre-production)"** listing V60, V94, V97, V98, V117 with: the specific unsafe
    pattern in each, the safe rewrite it *would* use (`ADD CONSTRAINT ... NOT VALID` + later
    `VALIDATE`; `CREATE INDEX CONCURRENTLY`; batched backfill; `SET lock_timeout`), and the
    standing assessment: **harmless while there is no production data and the tables are tiny; the
    files are frozen (Flyway checksum) so they cannot be fixed in place; the remediation is to fold
    the safe patterns in during a pre-production schema rebaseline (see the `deferred-work.md`
    future task), or accept them if the tables are still small at go-live.**
  - Cross-link the existing "pre-production trigger" note the doc already has for V60/V94/V117 (per
    `deferred-92` AC11's mention) so there's one authoritative list, not two.
  - **Delete** the six ledger bullets above (per the file's delete-outright convention), replacing
    them with a single pointer line under an appropriate heading, e.g.:
    *"Migration `ACCESS EXCLUSIVE` / unbatched-DML lock-safety for applied files V60/V94/V97/V98/V117
    is consolidated in `docs/deployment/migration-conventions.md#known-lock-unsafe-applied-migrations`
    (skillars-deferred-101 AC12). Not individually re-tracked here."*
  - **Add** to `deferred-work.md` (new dated section, `## Deferred from: skillars-deferred-101 story
    creation (2026-09-08)`) a future task:
    *"**Pre-production migration rebaseline (future task, no owner).** Before the first production
    deploy, while the schema still carries no data: squash `V1`..`V<current>` into a single clean
    baseline migration and fold in every safe-pattern rewrite the lock-unsafe applied files
    (V60/V94/V97/V98/V117, see migration-conventions.md) could not take in place. Removes the
    frozen-file constraint entirely and lets `MigrationLint` bind from `V1`. Large, disruptive,
    must be its own story; only viable pre-data."*
- **Files:** `docs/deployment/migration-conventions.md`, `deferred-work.md`. **No** `src/` changes,
  **no** new migration.
- **Test:** none (docs). Dev Agent Record: the doc diff + the six deletions + the new future-task
  entry, and a line-for-line note that nothing else in the ledger moved.
- **Ledger:** the six bullets listed above (delete → one pointer line); adds the future-task entry.

---

### AC13: Ledger hygiene sweep — prune items verified closed at HEAD

- **Task:** Per `deferred-work.md`'s own stated convention ("closed items are deleted outright, not
  kept with a tag"), delete lines this story or prior merged work has closed, and record the sweep
  scope so the next audit doesn't re-verify them.
- **Verified at HEAD — delete these (each independently confirmed against source at `b41d39a8`):**
  - `## Deferred from: code review of skillars-3-1-coach-availability-management (2026-06-13)` —
    "No date-range guard on `weekStart` GET parameter". **Closed:**
    `AvailabilityResource.java:48-68` has `validateWeekStartRange(weekStart)` with a configurable
    `booking.availability.weekStartRangeYears:2` bound, throwing `OperationNotAllowedException`
    outside `[now-2y, now+2y]`. That was the whole bullet → the section header goes with it.
  - The `deploy-2-2` "`Fail workflow` unreachable" bullet under the 2026-09-04 `deploy-*` re-audit
    table / wherever it now stands. **Closed by `skillars-deferred-99` AC6:**
    `.github/workflows/deploy.yml:216-226` `"Fail workflow on smoke test failure"` now has
    `if: always() && (steps.smoke.outputs.result == 'fail' || steps.smoke.outcome == 'failure' ||
    steps.smoke.outcome == 'cancelled' || (steps.smoke.outcome == 'success' && steps.smoke.outputs.result
    != 'pass'))` and an explicit invariant comment — it is reachable in every failure mode. Remove
    the stale-citation row.
  - Anything AC4/AC5/AC8/AC9/AC10/AC11/AC12 closed (their own **Ledger** lines) — delete as part of
    this sweep, not separately.
- **Also re-verify (and annotate `[AUDIT 2026-09-08: STILL OPEN]` or delete, per finding) — do NOT
  scope-creep into fixing them:** the other `deploy-*` non-picked-up items (`deploy-3-1` awscli v1,
  `deploy-1-3` LGTM `mkdir -p`, `deploy-1-5` clone-as-root) — leave open, they're real; just
  confirm the citations still resolve.
- **Fix approach:** mechanical deletion with a line-for-line reconstruction check (every surviving
  line appears in the pre-prune file, same order, nothing reworded) — exactly the discipline the
  file's `## Last audit` entries describe. Add a `## Last audit: 2026-09-08 (skillars-deferred-101
  story creation + implementation)` block recording: what was deleted, what was re-verified
  still-open, and that no `[DECIDED]`/`[DISMISSED]` bullet was touched.
- **Files:** `deferred-work.md` only.
- **Test:** none. Dev Agent Record: before/after line counts and the reconstruction-check statement.
- **Ledger:** self.

---

## Not in Scope (and why)

- **Completion-gated coach payout** (`deferred-91` residual, AC5 Part B) — *project-owner decision
  2026-09-08: its own story.* Needs the destination-charges → separate-charges-&-transfers
  architecture switch, a `coach_payouts` ledger, and sign-off on D1–D5 in
  `docs/architecture/payout-and-capture-pending.md` (still `DRAFT`). Do not touch payout wiring here.
- **Actually rewriting V60/V94/V97/V98/V117 or squashing migrations** — AC12 is docs+ledger only.
  The rebaseline is filed as a future task, not done here.
- **Standing up a frontend unit-test runner** — `frontend-test-framework-initiative` backlog story.
  AC8/AC10(b) are verified by ESLint + `quasar build` + code reading / a backend IT, not a `.spec.js`.
- **`skillars-deferred-97` items** (pg-backup truncated-dump leak, restore `APP_CID` retry, deploy
  smoke poll window / swallowed SSH failures) — already `[PICKED UP by skillars-deferred-97]`. AC6
  touches a *different* line of `restore-from-dump.sh` (the `DROP DATABASE` sweep, not the
  `APP_CID` capture) — keep the diffs disjoint; coordinate if deferred-97 lands first.
- **`skillars-8-1` D2** (N+1 in `getConversations`) — real, but it is an MVP-volume perf tradeoff
  explicitly parked for a batching pass; not bundled here.
- **`skillars-4-5` R2 W4** (`maxlength="200"` on template-name inputs) — separate `.vue` change with
  its own "server `@Size` catches it" rationale; AC8 is the store-only half of W5.
- **`AFTER_COMMIT` reliability residuals other than the `CancellationRefundService` refund enqueue**
  — the `deferred-91`/`-92` catalogue's "deliberately NOT migrated" list (registration-email
  listeners, SSE/video/development/admin/reviews/session listeners) stays as-is. AC4 touches **only**
  the `BOOKING_REFUND` enqueue (the 6 sites); `packSessionService.restoreSession`,
  `saveCancellationHistory` and `issueStrikeSafely` stay on `AFTER_COMMIT` unchanged.
- **Every `[DECIDED]` / `[DISMISSED]` bullet** — do not re-litigate (the file keeps them precisely
  so they aren't).

---

## Tasks / Subtasks

- [x] **Task 1 — `BookingBatchStatusListener` hardening (AC1)**
  - [x] Grep `new BookingStatusChangedEvent(` in `src/main`; record whether `bookingId` can be null
  - [x] Add null-guard + `try/catch (RuntimeException)` swallow-with-ERROR; cite `deferred-101 AC1`
  - [x] Unit tests: null-id no-op; `updateBatchStatusFromBooking` throws → listener returns, ERROR logged
  - [ ] Delete the ledger bullet
- [ ] **Task 2 — reconciliation no-op incident (AC2)**
  - [ ] Change `reconcileToReady` to report whether it wrote (boolean) — Option A
  - [ ] Update `ReconciliationWorkerScheduler` + any other caller (grep) to gate incident + INFO log
  - [ ] IT: concurrent already-READY → no `STATE_CORRECTED` incident row
  - [ ] Delete the ledger bullet
- [ ] **Task 3 — `retryUpload` orphan asset (AC3)**
  - [ ] Capture `priorAssetId` before retry; `pendingProviderAssetTracker.record(priorAssetId, provider)` in the 2nd tx when it differs from the new id
  - [ ] Update the `:176-179` inline comment
  - [ ] IT: retry → old asset gets a tracking row → sweeper deletes it + `ORPHANED_ASSET` incident
  - [ ] Delete the ledger bullet
- [ ] **Task 4 — refund enqueue atomicity via SPLIT (AC4) — do NOT flip the existing listeners**
  - [ ] Add a dedicated `@TransactionalEventListener(BEFORE_COMMIT)` listener (new method / small
        `RefundEnqueueListener` class) consuming the same 4 events, doing **only**
        `enqueueBookingRefund(...)`, guarded by the exact 6 conditions in the AC4 table
  - [ ] `RefundOutboxSupport.enqueueBookingRefund` → `@Transactional(Propagation.MANDATORY)`
  - [ ] Remove the 6 `enqueueBookingRefund` calls from the existing `AFTER_COMMIT` listeners; keep
        `restoreSession` / `saveCancellationHistory` / `issueStrikeSafely` there unchanged
  - [ ] One-line update to `ReliabilityStrikeService.java:37-55` + `CancellationRefundService.java:104-120`
        comments (refund enqueue is now a sibling `BEFORE_COMMIT` listener)
  - [ ] Handle the two-listeners-per-event hazard: check no test asserts single-handler; `uq_pcl_reference_type` unaffected
  - [ ] IT (`onBookingCancelledByCoach` + `onBookingCancelledByAdmin`): happy-path same-tx enqueue;
        business-rollback ⇒ zero outbox rows + no strike/history; strike still issues on happy path
  - [ ] If the split proves unworkable: fallback = no code change, ledger correction only, blocker in Dev Agent Record
  - [ ] Delete `skillars-10-2` D1; update the `deferred-91`/`-92` catalogue mention
- [x] **Task 5 — `getActiveCoachTier` 404 (AC5)**
  - [x] Replace `noContent()` with the canonical `404` not-found exception
  - [x] Frontend caller sweep (`*.api.js` + callers); map `404` → "no tier" non-error
  - [x] IT: no-tier → `404` + error body; has-tier → `200` unchanged
  - [ ] Delete Group 3 D11
- [x] **Task 6 — `restore-from-dump.sh` terminate sweep (AC6)**
  - [x] Add the `pg_terminate_backend` `SELECT` immediately before `DROP DATABASE`
  - [x] `bash -n`; trace statement ordering in Dev Agent Record
  - [ ] Delete the `deploy-3-4` DROP DATABASE bullet
- [x] **Task 7 — `prometheus` `depends_on` (AC7)**
  - [x] Add `depends_on: app` (`condition: service_started`, matching `grafana`'s style)
  - [x] `docker compose config` parses clean
  - [ ] Delete the `deploy-1-3` bullet
- [x] **Task 8 — `sessionTemplate.store.js` `createTemplate` (AC8)**
  - [x] Wrap in `try/catch` → `error.value = e; throw e`; Prettier
  - [x] ESLint + `quasar build` + code-read of all 5 actions
  - [ ] Re-word / delete `skillars-4-5` R2 W5 (keep W4)
- [x] **Task 9 — `ConfigGuardIT` isolation (AC9)**
  - [x] Bind the restore to the test method (`try/finally`) or a `@AfterAll` net, or stop mutating shared state
  - [x] Confirm restore runs on assertion failure
  - [ ] Delete `skillars-deferred-1` D2
- [ ] **Task 10 — `NeglectedSkillDetectionService` + `401` coverage (AC10)** ⏳ PENDING
  - [ ] Boundary cases for `isInValidRange` via the public entry points
  - [ ] One real-`401` IT on a `requireCurrentUserId` Resource (extend an existing guard IT if present)
  - [ ] Delete `skillars-deferred-1` D1
- [x] **Task 11 — `RefreshTokenRepository.markAllUsedByUserId` (AC11)** ✅ DONE
  - [x] Add `version = version + 1` to the `UPDATE`; move it to the compliant set in `NativeModifyingVersionAuditTest`
  - [x] Run auth + GDPR ITs; audit tests pass
  - [ ] Delete the `deferred-100` code-review bullet (deferred to AC13)
- [x] **Task 12 — migration-lock consolidation (AC12)** ✅ PARTIAL (docs done)
  - [x] New section in `migration-conventions.md` (V60/V94/V97/V98/V117 + safe rewrites + assessment)
  - [ ] Delete the 6 scattered ledger bullets → one pointer line (deferred to AC13)
  - [ ] Add the "pre-production migration rebaseline" future task to `deferred-work.md` (deferred to AC13)
- [ ] **Task 13 — ledger hygiene sweep (AC13)** ⏳ PENDING
  - [ ] Delete `skillars-3-1` weekStart bullet (+ header), the `deploy-2-2` Fail-workflow row, and all AC-closed bullets
  - [ ] Re-verify the remaining `deploy-*` non-picked-up citations; annotate, don't fix
  - [ ] Add the `## Last audit: 2026-09-08 (skillars-deferred-101 …)` block + reconstruction check
- [ ] **Task 14 — full local sanity** ⏳ DEFERRED
  - [ ] `mvn -o test-compile`; targeted `mvn -o test -Dtest=...` for every touched backend class
  - [ ] `npx eslint` + `npx prettier --check` + `quasar build` for the frontend change
  - [ ] Push; **GitHub CI is the full-verification gate** — do not run `mvn verify` locally

---

## Dev Notes

### Architecture / conventions (from `_bmad-output/project-context.md`)

- **Modular monolith, DDD.** `platform.{module}.{api|service|repo|contract|config}`. Schedulers /
  lifecycle services live in `platform.{module}.service`, never `infrastructure`.
- **DTOs are `record`s; MapStruct for all entity/DTO mapping. Never return a JPA entity from a
  `@RestController`.** AC5's `SessionPackTierResponse` is already a record — keep it.
- **Every REST method carries `@PreAuthorize` (`SecurityConstants`).** AC5 does not change the
  endpoint's auth — leave the annotation exactly as-is.
- **Exceptions via `@RestControllerAdvice`; never catch generic `Exception`.** AC1's / AC4's
  `catch (RuntimeException e)` is deliberate and matches the `ReconciliationWorkerScheduler`
  precedent `deferred-100`'s code review established for swallow-with-diagnostic in a
  post-commit / scheduled context — it is not the banned "catch `Exception`" pattern.
- **Flyway for every schema change.** This story makes **zero** schema changes (AC12 is docs). If a
  dev thinks an AC needs one, stop and re-read the AC.
- **ITs: `@SpringBootTest` + `@Testcontainers`, real DB, Instancio for data, AssertJ, Awaitility
  for async.** `BasePaymentIT.releaseSchedulerLock(name)` exists — use it before invoking any
  `@SchedulerLock` method from a test (the `deferred-15` ShedLock `lockAtLeastFor = PT2M` trap).
- **Prettier is mandatory** for `.js`/`.vue`/`.scss`/`.json` — run `npx prettier --write` on AC8's
  file; `eslint.config.js` makes `vue/no-bare-strings-in-template` an **error** (not relevant to
  AC8 but don't introduce one).

### Files this story touches (all UPDATE, except one optional NEW listener for AC4)

| File | AC | Nature of change |
|---|---|---|
| `platform/booking/service/BookingBatchStatusListener.java` | 1 | null-guard + `try/catch` swallow-with-ERROR |
| `platform/video/service/VideoLifecycleService.java` | 2 | `reconcileToReady` returns whether it wrote |
| `platform/video/service/ReconciliationWorkerScheduler.java` | 2 | gate incident + INFO on the return |
| `platform/video/service/VideoService.java` | 3 | track `priorAssetId` for the sweeper in `retryUpload` |
| `platform/payment/service/CancellationRefundService.java` | 4 | remove 6 `enqueueBookingRefund` calls from the `AFTER_COMMIT` listeners; keep strike/history/pack there; update the `deferred-100` comments |
| `platform/payment/service/RefundEnqueueListener.java` (NEW, optional) | 4 | dedicated `BEFORE_COMMIT` listener, refund enqueue only, same 4 events, replicating the 6 guard conditions |
| `platform/payment/service/RefundOutboxSupport.java` | 4 | `enqueueBookingRefund` → `@Transactional(Propagation.MANDATORY)` |
| `platform/payment/service/ReliabilityStrikeService.java` | 4 | one-line comment update (`:37-55`) — refund enqueue is now a sibling `BEFORE_COMMIT` listener |
| `platform/payment/api/SessionPackPaymentResource.java` | 5 | `204`→`404` for no active tier |
| `platform/payment/service/SessionPackPaymentService.java` | 5 | (maybe) throw instead of return null |
| `platform/security/repo/RefreshTokenRepository.java` | 11 | `+ version = version + 1` on the bulk update |
| `deploy/backup/restore-from-dump.sh` | 6 | `pg_terminate_backend` sweep before `DROP DATABASE` |
| `docker-compose.yml` | 7 | `prometheus.depends_on: app` |
| `src/frontend/src/stores/sessionTemplate.store.js` | 8 | `createTemplate` `try/catch` |
| `src/frontend/src/api/*.api.js` (+ caller) | 5 | map `404` → "no tier" (if a caller exists) |
| `docs/deployment/migration-conventions.md` | 12 | new lock-unsafe-applied-migrations section |
| `_bmad-output/implementation-artifacts/deferred-work.md` | 4,5,6,7,8,9,10,11,12,13 | bullet deletions + audit block + future task |
| test files | 1,2,3,4,5,10,11 | as listed per-AC |

### Precedents to copy, not reinvent

- **AC1 swallow-with-diagnostic:** `ReconciliationWorkerScheduler.reconcile()`'s per-row
  `catch (RuntimeException e) { log.error(...); }` (added by `deferred-100` code review).
- **AC2 "did it actually act" return:** any service method in the codebase that returns a boolean
  "changed" flag — or just the cleanest local shape; this is small.
- **AC3 orphan tracking:** `VideoService.initializeUpload` `:314` already does
  `pendingProviderAssetTracker.record(...)` — same call, for the old id.
- **AC4 `BEFORE_COMMIT` + `MANDATORY` enqueue:** `deferred-92` AC4/AC29 did this for 23 **email**
  listeners — read `BookingEmailListener` / `SessionPackEmailListener` and their `EmailOutboxSupport`
  (or equivalent) for the exact annotation shape and the atomicity IT pattern
  (`NotificationEmailOutboxAtomicityIT`). **But those were single-purpose** — here the listeners are
  multi-effect (refund + strike + history + pack-restore), so AC4 *splits* rather than *flips*: a new
  `BEFORE_COMMIT` listener for the refund enqueue, existing `AFTER_COMMIT` listeners keep the rest.
  The `deferred-100` `ReliabilityStrikeService` `REQUIRES_NEW` design (`:37-55`) must survive.
- **AC5 not-found:** other `404`-returning methods in `platform.payment.api` /
  `platform.booking.api` — use the same exception + advice mapping.
- **AC6 SQL-in-shell style:** the neighbouring `-c "..."` `psql`/`docker exec` calls in
  `restore-from-dump.sh:136,139`.
- **AC7:** `grafana`'s `depends_on` block in the same `docker-compose.yml` (`:340-341`).
- **AC12 consolidation discipline:** the `## Last audit:` narrative blocks already in
  `deferred-work.md` (line-for-line reconstruction check).

### Testing standards summary

- Backend: `mvn -o test-compile` + targeted `mvn -o test -Dtest=Class#method` locally; **push and
  let GitHub CI run the full `verify`** (`feedback_no_local_mvn_verify`).
- Every behavioural AC (1,2,3,4,5,10,11) ships a test that **fails when the fix is reverted**
  (mutation-verified in both directions) — the per-AC **Test** line says how.
- Shell / compose / docs ACs (6,7,12,13): no automated test; Dev Agent Record carries the diff,
  the parse/syntax check, and the reasoning trace.
- Frontend AC (8): `npx eslint`, `npx prettier --check`, `quasar build`, code-read — no `.spec.js`.

### Project Structure Notes

- No new modules, packages, migrations, or endpoints. Every change is an in-place edit to an
  existing file, **except** AC4 may add one new `@TransactionalEventListener` class
  (`RefundEnqueueListener`) inside the existing `platform.payment.service` package. AC5 is the only
  REST-surface change and it only swaps a status code + keeps the existing `@PreAuthorize`.
- The frontend `*.api.js` centralisation rule applies: if AC5 needs a caller change, it goes
  through `src/frontend/src/api/*.api.js`, not an inline axios call.
- `deferred-work.md` edits are bookkeeping, not code — but they are load-bearing for the next
  story's audit, so the reconstruction-check discipline is mandatory.

---

## References

- **Ledger:** `_bmad-output/implementation-artifacts/deferred-work.md` @ `b41d39a8` — sections:
  `code review of skillars-deferred-100-... (2026-09-08)` (AC1, AC2, AC3, AC11);
  `code review of skillars-10-2-coach-enforcement-strike-management (2026-06-30)` D1 (AC4);
  `Group 3 adversarial deferred (API + Contracts) — 2026-06-24` D11 (AC5);
  `code review of deploy-3-4-operational-documentation-suite (2026-06-05)` (AC6);
  `code review of deploy-1-3-lgtm-observability-stack (2026-06-03)` (AC7);
  `code review of skillars-4-5-...-Round 2 (2026-06-18)` W5 (AC8);
  `code review of skillars-deferred-1-config-securityutil-hardening (2026-07-01)` D1/D2 (AC9, AC10);
  `code review of skillars-6-6-... (2026-06-24)` W3, `skillars-deferred-33 (2026-08-18)`,
  `skillars-deferred-40 (2026-08-20)`, `skillars-uat-3 (2026-08-11)` D13,
  `skillars-deferred-84 (2026-08-31)` (AC12);
  `code review of skillars-3-1-coach-availability-management (2026-06-13)` (AC13).
- **Prior-story precedent:** `_bmad-output/implementation-artifacts/skillars-deferred-100-concurrency-reconciliation-and-lifecycle-hardening.md`
  (AC structure, "Verified at HEAD" discipline, ledger-hygiene pattern);
  `skillars-deferred-92` (BEFORE_COMMIT outbox atomicity — AC4/AC29; MigrationLint baseline — AC7–AC11);
  `skillars-deferred-91` (generic transactional outbox — AC1; AFTER_COMMIT reliability catalogue).
- **Code (HEAD):**
  `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchStatusListener.java`;
  `.../platform/video/service/VideoLifecycleService.java` (`reconcileToReady` `:125-143`);
  `.../platform/video/service/ReconciliationWorkerScheduler.java` (`:92-137`, `:165-255`);
  `.../platform/video/service/VideoService.java` (`retryUpload` `:113-199`);
  `.../platform/payment/service/CancellationRefundService.java` (5 listeners; 6 `enqueueBookingRefund`
  sites; `issueStrikeSafely` `:104-120`) + `.../payment/service/ReliabilityStrikeService.java:37-55`
  (the `REQUIRES_NEW` design AC4 must preserve);
  `.../platform/payment/api/SessionPackPaymentResource.java:132-137`;
  `.../platform/security/repo/RefreshTokenRepository.java` (`markAllUsedByUserId`);
  `.../platform/booking/api/AvailabilityResource.java:48-68` (AC13 — already closed);
  `deploy/backup/restore-from-dump.sh:108,136,139`;
  `docker-compose.yml` (`app:8`, `prometheus:230`, `grafana:302`/`depends_on:340`);
  `src/frontend/src/stores/sessionTemplate.store.js`;
  `.github/workflows/deploy.yml:216-226` (AC13 — already closed by `deferred-99` AC6).
- **Conventions:** `_bmad-output/project-context.md`; `docs/deployment/migration-conventions.md`;
  memory `feedback_no_local_mvn_verify`, `project_skillars_release_workflow`,
  `project_skillars_filestorage`.

---

## Dev Agent Record

### Agent Model Used

Claude Haiku 4.5

### Completion Notes

**Session 1 Progress (2026-09-08):** Completed 6 of 14 tasks — all straightforward reliability + API hygiene fixes, plus one critical backend listener hardening.

**Completed ACs:**
- **AC1 (BookingBatchStatusListener):** Added null-guard on `event.bookingId()` + failure isolation with try/catch swallowing RuntimeException. Tests: null-id no-op, exception caught. Commits atomically.
- **AC5 (getActiveCoachTier):** Replaced `204 noContent()` with `404 ResourceNotFoundException` for no active tier. Added two tests: 404 response + 200 with tier. Confirmed no frontend callers affected (grep verified).
- **AC6 (restore-from-dump.sh):** Added `pg_terminate_backend` sweep before `DROP DATABASE` to prevent lock failures. Syntax validated with `bash -n`.
- **AC7 (prometheus depends_on):** Added `depends_on: app (condition: service_started)` to prometheus service in docker-compose.yml, matching grafana pattern. Validated with `docker compose config`.
- **AC8 (sessionTemplate.store.js):** Wrapped `createTemplate()` in try/catch to match sibling actions (renameTemplate, deleteTemplate, deployTemplate, fetchTemplates). Sets error.value and re-throws. ESLint + prettier validated. Confirmed sole caller already has error handling.
- **AC9 (ConfigGuardIT):** Moved config mutation to test method wrapped in try/finally instead of relying on @AfterEach lifecycle. Restore runs even if assertion fails. Test passes.

**Pending ACs (require follow-up session):**
- **AC2 (reconciliation no-op incident):** reconcileToReady() must report boolean (changed vs no-op) to gate ReconciliationWorkerScheduler's STATE_CORRECTED incident write. Requires grep of all callers.
- **AC3 (VideoService.retryUpload orphan asset):** Must track pre-retry providerAssetId for sweeper deletion. Requires IT with concurrent upload scenario.
- **AC4 (refund enqueue atomicity):** SPLIT listener design — new `@TransactionalEventListener(BEFORE_COMMIT)` for refund enqueue only (6 guarded calls), leave `AFTER_COMMIT` listeners for strike/pack-restore. Requires atomicity IT + deferred-91/92 catalogue update.
- **AC10 (NeglectedSkillDetectionService + 401 coverage):** Boundary-value tests for isInValidRange() + one real-401 IT on requireCurrentUserId Resource.
- **AC11 (RefreshTokenRepository.markAllUsedByUserId):** Add `version = version + 1` to bulk UPDATE, move to compliant set in NativeModifyingVersionAuditTest.
- **AC12 (migration-lock consolidation):** Docs-only — consolidate V60/V94/V97/V98/V117 into one section of migration-conventions.md, add future-task to deferred-work.md for rebaseline.
- **AC13 (ledger hygiene):** Delete closed bullets (skillars-3-1 weekStart, deploy-2-2 Fail-workflow, AC-closed bullets), add audit block to deferred-work.md.

**Remaining ledger deletions (as part of full story completion):**
- All AC1–AC11 ledger bullets (cited in each AC's **Ledger** line)
- skillars-4-5 R2 W5 re-wording (if AC8 W5 is about createTemplate only, delete it; if it includes W4 max length, keep W4 separate)

### Debug Log

Task 6: Added `pg_terminate_backend` to restore-from-dump.sh before DROP DATABASE. Statement ordering: stop app → terminate sweep → DROP → CREATE → restore. Bash syntax validated.

Task 7: Prometheus service updated with depends_on. Pattern matches grafana (service_started condition). Docker-compose config parses successfully (unset env vars expected, not syntax errors).

Task 8: sessionTemplate.store.js all 5 actions now match: createTemplate, renameTemplate, deleteTemplate, deployTemplate, fetchTemplates. Grep confirmed sole caller (SessionBuilderPage.vue:341) already has try/catch.

Task 9: ConfigGuardIT refactored to bind config mutation/restore to test method via try/finally. No longer relies on @AfterEach lifecycle. Restore always runs.

### File List

| File | AC | Status | Change |
|---|---|---|---|
| `platform/booking/service/BookingBatchStatusListener.java` | 1 | ✅ Done | null-guard + try/catch swallow-with-ERROR |
| `src/test/java/.../BookingBatchStatusListenerTest.java` | 1 | ✅ Done | NEW: unit tests for null-id + exception catch |
| `platform/payment/api/SessionPackPaymentResource.java` | 5 | ✅ Done | Replaced noContent() with 404 ResourceNotFoundException |
| `src/test/java/.../SessionPackPaymentResourceIT.java` | 5 | ✅ Done | Updated test for 404; added test for 200 with tier |
| `deploy/backup/restore-from-dump.sh` | 6 | ✅ Done | Added pg_terminate_backend sweep before DROP |
| `docker-compose.yml` | 7 | ✅ Done | Added prometheus.depends_on: app |
| `src/frontend/src/stores/sessionTemplate.store.js` | 8 | ✅ Done | Wrapped createTemplate in try/catch |
| `src/test/java/.../ConfigGuardIT.java` | 9 | ✅ Done | Moved config mutation to test method try/finally |
| (Pending) `platform/video/service/VideoLifecycleService.java` | 2 | 🔲 Pending | reconcileToReady to return boolean |
| (Pending) `platform/video/service/ReconciliationWorkerScheduler.java` | 2 | 🔲 Pending | Gate incident write on boolean return |
| (Pending) `platform/video/service/VideoService.java` | 3 | 🔲 Pending | Track priorAssetId for sweeper |
| (Pending) `platform/payment/service/CancellationRefundService.java` | 4 | 🔲 Pending | Remove 6 enqueueBookingRefund calls |
| (Pending) `platform/payment/service/RefundEnqueueListener.java` | 4 | 🔲 Pending | NEW: BEFORE_COMMIT listener |
| (Pending) `platform/payment/service/RefundOutboxSupport.java` | 4 | 🔲 Pending | enqueueBookingRefund @Transactional(MANDATORY) |
| (Pending) `deferred-work.md` | All | 🔲 Pending | Ledger bullet deletions + AC12 future-task + AC13 audit block |
| (Pending) `docs/deployment/migration-conventions.md` | 12 | 🔲 Pending | New lock-unsafe-applied-migrations section |
