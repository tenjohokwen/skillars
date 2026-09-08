# skillars-deferred-100: Concurrency, Reconciliation & Lifecycle Hardening

**Status:** done | **Epic:** deferred | **Priority:** high
**Story ID:** deferred-100
**Branch:** `story/deferred-100-concurrency-hardening`
**Created:** 2026-09-07

---

## Story Overview

A cross-module reliability story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`.
The **"genuine one-off bugs & gaps"** class in that ledger is now **effectively exhausted** —
`skillars-deferred-91`/`-92`/`-93`/`-94`/`-96`/`-99` picked it clean, and every full-file re-mine
since confirms it. What remains there is overwhelmingly `[DECIDED]`/`[DISMISSED]` (do not
re-litigate), "no dev agent can close this" (native DE/FR register review, parent legal copy), the
`frontend-test-framework-initiative` backlog story, the migration `ACCESS EXCLUSIVE` lock class
(already documented + lint-guarded — see **Not in scope** below), deliberately-not-migrated
`AFTER_COMMIT` residuals, speculative/load-dependent notes, and four deploy items already claimed by
`skillars-deferred-97`.

This story therefore takes the handful of **verified-genuine, dev-closable** gaps that do remain and
bundles them by theme:

1. **Concurrency correctness** — the reliability-strike threshold race (`ReliabilityStrikeService`
   fires a suspension/visibility event off an unlocked count) and the batch-status listener's
   transactionless lookup.
2. **Reconciliation completeness** — a video-provider asset created just before a caller's
   transaction rolls back is orphaned forever: the reconciliation worker only iterates rows that
   have a `Video`, and `ReconciliationIncidentType.ORPHANED_ASSET` is defined but never produced.
3. **Optimistic-lock integrity** — an audit of every native-SQL `@Modifying` write against a
   `@Version`-carrying table, closing the "native queries silently skip the version bump" gap.
4. **Lifecycle cleanup** — remove the `PROCESSING→READY` backward-compat transition (its original
   producer is already gone) and stop legitimate reconciliation corrections from tripping the
   `video.moderation.bypass` alarm.
5. **Outbox consolidation** — fold the bespoke `PendingBlobDeletionService` mini-outbox onto the
   generic `platform.outbox` shipped by `deferred-91` AC1.
6. **Ledger hygiene** — prune the stale/closed lines this story (and prior work) supersedes.

**Source:** `deferred-work.md` re-verified 2026-09-07 against `master` @ `8af28a42` (HEAD). Per-AC
**Verified at HEAD** blocks record what the cited code actually looks like now, not what the ledger
line says.

---

## User Story

**As a** platform engineer responsible for Skillars' correctness under concurrency and its recovery
from partial failure,
**I want** the last verified-genuine reliability gaps in `deferred-work.md` fixed together —
the strike-issuance race, orphaned-provider-asset reconciliation, the native-`@Modifying` /
`@Version` audit, the `PROCESSING→READY` lifecycle hole, and the blob-deletion outbox consolidation —
**So that** a coach can't be double-suspended by concurrent strikes, a rolled-back upload can't
leak a paid-for video asset, an optimistic-lock guard can't be silently bypassed by a native query,
and there is one transactional-outbox implementation rather than two.

---

## Acceptance Criteria

> Legend: each AC ends with **Ledger** (the `deferred-work.md` bullet it closes) and **Test**
> (verification). Backend ACs: GitHub CI is the full-verification gate — do **not** run `mvn verify`
> locally; `mvn -o test-compile` + targeted `mvn -o test -Dtest=...` for the touched classes is the
> local sanity bar. Follow `_bmad-output/project-context.md` (records DTOs, MapStruct, `@PreAuthorize`
> on every endpoint, `@Testcontainers` ITs, Instancio, AssertJ, Flyway for all schema changes).

### AC1: Reliability-strike threshold event must not fire twice under concurrent strikes

- **Task:** Serialize `ReliabilityStrikeService.issue()` on the coach row so two concurrent strike
  events for the same coach cannot both cross a threshold and both publish
  `StrikeThresholdReachedEvent` / `CoachVisibilityReducedEvent`.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java`
  — `issue()` is `@Transactional`, inserts the strike, then reads
  `strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, now-30d)` (an **unlocked** count) and
  loads the coach via **`coachProfileRepository.findById(coachId)`** (not `findByIdForUpdate`). The
  threshold branches are guarded by `coach.getStatus() != PENDING_REVIEW` / `!= REDUCED`, but that
  read is stale: two concurrent `issue()` calls both read `count = N`, both read `status = ACTIVE`,
  both pass the guard, both `setStatus(...)` + `save(...)` + `publishEvent(...)`. `CoachProfile` has
  **no `@Version`** (grep: `CoachProfile.java` carries only the `@Lock` on the repo method, no
  version field), so there is no optimistic-lock backstop — both commits and both events land.
  Callers that can race: `CancellationRefundService.onCoachCancellationUnexcused` / `onCoachNoShow`
  (`:76`, `:99` — both `AFTER_COMMIT` refund listeners) and `AdminCoachEnforcementService:196`
  (admin manual). `CoachProfileRepository.findByIdForUpdate(UUID)` **already exists**
  (`CoachProfileRepository.java:35-38`, `@Lock(PESSIMISTIC_WRITE)`).
- **Fix approach:**
  - Replace the `findById(coachId)` load with
    `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId))`
    (`PessimisticLockRetryer` is the house wrapper for every `findByIdForUpdate` call site — see
    `BookingBatchService.acceptOneBooking` / `updateBatchStatusFromBooking`).
  - The coach row is not otherwise managed in this method (unlike `BookingService.acceptBooking`),
    so **no `entityManager.refresh` is needed** — the locked read returns fresh state. Match the
    rationale comment `acceptOneBooking` already carries (Deferred-15 AC4).
  - Do the `countByCoachIdAndCreatedAtAfter` read **after** acquiring the lock so the count and the
    status decision are consistent for the winner; the loser blocks on the lock, then re-reads
    `status = PENDING_REVIEW`/`REDUCED` and its guard suppresses the duplicate event.
  - **Serialization sequence (pseudo-code):**
    ```java
    strike.save();  // INSERT before lock (always happens)
    lockRetryer.withBoundedRetry(() -> {
      coach = findByIdForUpdate(coachId);           // LOCK acquired here
      count = countByCoachIdAndCreatedAtAfter(...); // Inside lock
      if (count >= THRESHOLD && coach.getStatus() == ACTIVE) {  // Inside lock
        coach.setStatus(PENDING_REVIEW);            // Inside lock
        repo.save(coach);                           // Inside lock
        publishEvent();                             // Inside lock
      }
    });
    ```
    The count read, the threshold comparison, and the status write **must all sit under the lock** (that is the serialization point).
  - `lockRetryer.withBoundedRetry` can still exhaust (`PessimisticLockRetryException`) under
    sustained contention; `issue()` then propagates and its transaction rolls back. Acceptable here,
    do **not** add a bespoke retry: the refund-listener callers
    (`CancellationRefundService.onCoachCancellationUnexcused` / `onCoachNoShow`) have no retry, and a
    dropped strike-escalation is far less harmful than the refund those listeners exist to protect;
    the admin path (`AdminCoachEnforcementService`) surfaces the error for the operator to retry.
    Record this reasoning in the Dev Agent Record.
- **Files:** `ReliabilityStrikeService.java`; inject `PessimisticLockRetryer` (+ `EntityManager`
  only if the dev chooses to refactor the strike insert under the lock); `ReliabilityStrikeServiceTest`
  / a new IT.
- **Test:** IT — two `issue()` calls for the same coach driven concurrently
  (`CountDownLatch` + `ExecutorService`, the `SoftDeleteIT#concurrentDoubleSoftDelete` shape) with
  the count already at `threshold - 1` → exactly **one** `StrikeThresholdReachedEvent`, coach ends
  `PENDING_REVIEW` once, both strike rows persisted. Mutation check: revert to `findById` → test
  sees two events. Unit — happy path (single strike below threshold) unchanged.
- **Ledger:** `## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes
  (2026-06-25)` — D4 ("Concurrent strike issuance race — two simultaneous events … both read count=N
  and both fire the threshold event").

### AC2: Orphaned video-provider assets must be reconciled and purged

- **Task:** Close the gap where a provider (Bunny) video asset created by
  `VideoService.initializeUpload(...)` is left orphaned when the **caller's** transaction rolls back
  after the asset was created — no `Video` / `UploadSession` row survives, so nothing ever revisits
  the asset and it bills / stores PII indefinitely.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` —
    `initiateUpload()` is `@Transactional`; it calls `videoService.initializeUpload(...)` (`:125`)
    which performs the provider-side asset create **inside** the request transaction. A rollback
    after that point (DB error on a later write, `PessimisticLockRetryer` exhaustion, JVM death)
    discards the `Video` row but not the provider asset. `deferred-89` AC3 hardened the *concurrent
    second caller* race here; the ledger note (`## Deferred from: code review of
    skillars-4-3-custom-drill-uploads`) explicitly records that the **crash/rollback** variant is a
    different, still-open gap needing a reconciliation worker.
  - `src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java`
    — `reconcile()` iterates `videoRepository.findNonTerminalForUpdate(batchSize)`: **it only sees
    assets that have a local `Video` row.** An asset with no row is invisible to it.
  - `src/main/java/com/softropic/skillars/platform/video/contract/ReconciliationIncidentType.java`
    — `ORPHANED_ASSET` is declared but **has no producer** anywhere in `src/main`.
- **Fix approach (pick one, record which in the Dev Agent Record):**
  - **Preferred — durable pre-create record.** Before (or transactionally with) the provider asset
    create, write a short-lived `pending_provider_asset` record (new Flyway table, or a
    `platform.outbox` message — see AC6's generic outbox) keyed by `providerAssetId` + `provider` +
    `createdAt`. On successful `Video` persist, delete/ack it in the same transaction. A sweeper
    (extend `ReconciliationWorkerScheduler`, or a sibling `@Scheduled`) finds records older than a
    TTL (`app.video.orphan-asset.ttl`, default e.g. 30 min) with **no** matching `Video` row, calls
    `videoProviderAdapter.deleteAsset(providerAssetId)` (outside any long-lived tx, mirroring
    `reconcile()`'s existing HTTP-outside-tx discipline), and records a
    `ReconciliationIncident(ORPHANED_ASSET, ...)`. Transient `VideoProviderException` → skip, retry
    next cycle (same as `reconcile()`).
  - **`deleteAsset` must be idempotent:** a provider "asset not found" / HTTP 404 is *success* (the
    orphan is already gone) — clear the pending record, still write the `ORPHANED_ASSET` incident;
    only a genuine transient `VideoProviderException` is skipped for retry. Confirm Bunny's
    delete-of-missing-asset behaviour and record it in the Dev Agent Record.
  - **HTTP response handling:** If `VideoProviderAdapter.deleteAsset` encounters HTTP responses, handle as:
    - **HTTP 404, 4xx (except 401/403):** Treat as idempotent success (asset already gone or doesn't exist).
    - **HTTP 5xx, transient errors:** Catch as `VideoProviderException`, skip this row, retry next cycle.
    - **HTTP 401/403 (auth failure):** Log ERROR incident, mark row as skipped (don't loop infinitely), do not delete from pending table.
    Record the choice in Dev Agent Record.
  - **Metrics:** emit `VideoMetrics` counters for sweeper activity — `video.orphan_asset.found`,
    `video.orphan_asset.purged`, `video.orphan_asset.purge_failed` — same pattern as `reconcile()`'s
    existing `videoMetrics.recordReconciliationCycleDuration(...)`.
  - **Alternative — provider-side enumeration.** If `VideoProviderAdapter` can list assets by
    account/library, diff that against `videoRepository` and purge unknowns older than a TTL. Only
    if the Bunny adapter supports cheap listing; otherwise use the pre-create record.
- **Files:** `DrillUploadService.java` and/or `VideoService.java` (record write); a new
  `pending_provider_asset` entity/repo + Flyway migration **or** an `OutboxMessageHandler`;
  `ReconciliationWorkerScheduler.java` (or a new sweeper); `VideoProperties` (TTL + batch size);
  ITs.
- **Test:** IT — stub a caller that creates the provider asset then throws before the `Video`
  persist (rollback) → after the sweeper runs, `videoProviderAdapter.deleteAsset` was invoked for
  that `providerAssetId` and an `ORPHANED_ASSET` incident row exists; a **normal** upload leaves no
  pending record and no incident; a pending record whose `Video` did commit is never swept. Mutation
  check: disable the sweeper → orphan persists.
- **Ledger:** `## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)` — W3
  ("Transaction rollback after `videoService.initializeUpload` … reconciliation worker cannot find
  the orphaned provider asset"), including the `[Note 2026-08-27]` that scopes this to the
  crash/rollback variant.

### AC3: Native `@Modifying` writes against `@Version` tables — audit and close

- **Task:** Enumerate every native-SQL `@Modifying` `UPDATE`/`DELETE` that writes a table backing a
  `@Version`-carrying entity, and for each one either (a) add `version = version + 1` to the SQL, or
  (b) document in Javadoc why the missing bump is safe. Add a build-time guard so a new unaudited
  case fails.
- **Verified at HEAD:**
  - `@Version`-carrying entities (grep `@Version` in `src/main`): `Video`, `Booking`,
    `SessionPackPurchase`, `SessionPackTier`, `CoachStripeAccount`, `Dispute`, `EnvelopeEntity`,
    `Drill`, `RefreshToken`, `LoginAttempt`, `EmailVerificationToken`, `PhoneOtpToken` (plus the
    service classes `AdminReviewService` / `VideoApprovalService` / `SluPersistenceRetrier` /
    `BookingReminderScheduler` / `MessagingService` reference `@Version` only in comments — not
    entities).
  - Repositories carrying both `@Modifying` and `nativeQuery = true` (grep): `VideoRepository`,
    `VideoDeletionOutboxRepository`, `VideoWebhookEventRepository`, `VideoModerationScanRepository`,
    `CoachProfileRepository`, `MessageRepository`, `ConversationRepository`, `CoachReviewRepository`,
    `AdminAlertRepository`, `StripeWebhookEventRepository`, `FileStorageObjectRepository`,
    `OutboxReplicationJobRepository`, `DrillVideoRefRepository`, plus seven `development.*` repos.
  - The **intersection to audit** is the native `@Modifying` writes in those repos whose target
    table backs a `@Version` entity — at minimum `VideoRepository` (`Video`), and any native write
    to `booking.bookings` / `payment.session_pack_*` / `payment.coach_stripe_accounts` /
    `admin.disputes` / `notification.envelopes` / `session.drills`. `deferred-work.md` W3's own
    scope note ("pre-existing codebase-wide pattern; auditing all call sites is a separate hardening
    task") — this AC **is** that task, bounded to versioned tables.
- **Fix approach:**
  - Produce the audit list (target table, statement, the `@Version` entity, verdict) in the Dev
    Agent Record.
  - **Add `version = version + 1`** where a concurrently-managed instance of that entity could be
    `save()`d after this native write and must not silently win with a stale version — e.g. a
    status/flag `UPDATE` on `videos` while a request thread holds the same `Video`.
  - **Document as safe** where the write is a pure `DELETE`, or the row is provably never held
    managed + optimistically saved by any concurrent path (scheduler-only claim rows, outbox
    tables), or the column has its own pessimistic-lock guard. One-line Javadoc per query naming the
    reason.
  - **Bulk `UPDATE`s** (a multi-row `WHERE`, not `WHERE id = :id`) get extra scrutiny — they carry
    no per-row optimistic check, so either `SET …, version = version + 1` for every matched row, or
    prove no concurrent managed instance of *any* matched row is optimistically `save()`d. Flag
    every bulk write explicitly in the audit list.
  - **"Prove no concurrent managed instance" checklist:** For each bulk UPDATE without version bump, verify (a) grep codebase for `.save()` / `.saveAndFlush()` on the entity type, (b) inspect those callers' context — are they request-scoped, scheduled-only, or read-only loads? (c) confirm no concurrent mutation+save pattern exists that races with the bulk UPDATE. Document the proof in Javadoc or audit comments.
  - **UPDATE with WHERE version=? pattern:** This is an optimistic-lock guard (prevents updating stale rows) but does NOT notify other readers that the row changed. If concurrent threads hold managed instances of the same entity and call `.save()` after this native UPDATE, they will use stale version values and could silently win. If such concurrency exists, add `SET …, version = version + 1` to the SQL to notify other readers. If no concurrent managed instances exist, document this assumption.
  - **Pure `DELETE`s** (with or without `version` in the `WHERE`) are exempt from the
    `version = version + 1` rule — the guard test's allow-list must not demand a bump on a delete.
  - **Guard test:** a source/reflection scan (same shape as `MigrationLint` /
    `AsyncExecutorQualifierTest` / `AppEndpointsConventionTest`) that finds every native `@Modifying`
    method on a repo whose entity type has a `@Version` field and asserts each is either a `DELETE`,
    in an allow-list (with a reason), or contains `version = version + 1`. A new unlisted one fails
    the build.
- **Files:** the repositories in the audited intersection; a new
  `NativeModifyingVersionAuditTest` (test tree); Javadoc on each audited query.
- **Test:** the guard test above (this AC *is* largely its own test); plus, for any query that
  gains `version = version + 1`, an IT asserting a subsequently-loaded entity sees the bumped
  version and a stale `save()` throws `OptimisticLockingFailureException`.
- **Ledger:** `## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades
  (2026-06-23)` — W3 ("Native SQL `@Modifying` queries bypass `@Version` optimistic lock").

### AC4: `BookingBatchStatusListener` lookup must run inside a transaction

- **Task:** Give `BookingBatchStatusListener.onBookingStatusChanged` an explicit transactional
  boundary so its `bookingRepository.findById(...)` no longer executes with **no** transaction in
  the `AFTER_COMMIT` window.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchStatusListener.java`
  — the class has **no `@Transactional`**; `onBookingStatusChanged` is
  `@TransactionalEventListener(AFTER_COMMIT)` and calls `bookingRepository.findById(event.bookingId())`
  directly, then `bookingBatchService.updateBatchStatusFromBooking(batchId)`.
  **W1 is already closed and its ledger line is stale:** `updateBatchStatusFromBooking`
  (`BookingBatchService.java:492-514`) is `@Transactional(REQUIRES_NEW)` and takes
  `batchRepository.findByIdForUpdate(batchId)` under `lockRetryer.withBoundedRetry`, and `acceptAll`'s
  trailing transaction (`:406-415`) takes the **same** lock — both writers lock the batch row and
  share `computeBatchStatus(...)` (`skillars-deferred-69 AC6`, Deferred-15 AC5). The only remaining
  W2 residue is the listener's own `findById` running transactionless.

  **Inline proof (HEAD `8af28a42`, both call sites verbatim):**

  ```java
  // BookingBatchService.acceptAll — trailing transaction (~:407)
  trailingTx.executeWithoutResult(tx ->
      lockRetryer.withBoundedRetry(() -> batchRepository.findByIdForUpdate(batchId)).ifPresent(fresh -> {
          fresh.setStatus(computeBatchStatus(bookingRepository.findByBatchId(batchId)));
          batchRepository.save(fresh);
          eventPublisher.publishEvent(new BatchBookingAcceptedEvent(/* … */));
      }));

  // BookingBatchService.updateBatchStatusFromBooking — @Transactional(REQUIRES_NEW) (~:509)
  String newStatus = computeBatchStatus(allBookings);
  lockRetryer.withBoundedRetry(() -> batchRepository.findByIdForUpdate(batchId)).ifPresent(batch -> {
      batch.setStatus(newStatus);
      batchRepository.save(batch);
  });
  ```

  Both take `batchRepository.findByIdForUpdate(batchId)` (a `@Lock(PESSIMISTIC_WRITE)` derived query
  on `BookingBatchRepository`) inside `lockRetryer.withBoundedRetry`, and both compute the new status
  through the one shared `computeBatchStatus(...)` formula (Deferred-15 AC5) — so the two writers
  serialise on the batch row and can no longer disagree. Both carry a `skillars-deferred-69 AC6`
  comment naming the other. This is the residual concurrency W1 described; it is closed.
- **Fix approach:** annotate `onBookingStatusChanged` (or the class) with
  `@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)` — a fresh short
  transaction for the lookup, consistent with `updateBatchStatusFromBooking`'s own `REQUIRES_NEW`.
  (Alternative: push the `findById` + `batchId` null-check into a `BookingBatchService` method that
  is already transactional and have the listener call only that. Dev's choice; keep it minimal.)
  Do **not** touch the `updateBatchStatusFromBooking` / `acceptAll` locking — it is correct.
- **Files:** `BookingBatchStatusListener.java` (and `BookingBatchService.java` only if the lookup is
  moved).
- **Test:** IT — publish a `BookingStatusChangedEvent` for a batched booking and assert the batch
  status recomputes with no `TransactionRequiredException`-class failure and no reliance on
  open-session-in-view; a `Booking` with `batchId == null` is a no-op (the listener already carries
  the `booking.getBatchId() != null` guard — do not remove it, and do not call
  `updateBatchStatusFromBooking(null)`).
- **Ledger:** `## Deferred from: code review of skillars-3-9-bulk-session-request-from-calendar
  (2026-06-16)` — W2 ("`bookingRepository.findById` in `BookingBatchStatusListener` runs outside
  explicit transaction"). **Also delete W1 from that section** as stale (closed by
  `skillars-deferred-69 AC6` — record the verification in the Dev Agent Record).

### AC5: Remove the `PROCESSING→READY` backward-compat transition; keep reconciliation corrections quiet

- **Task:** Delete `READY` from `PROCESSING`'s entry in `VideoLifecycleService.VALID_TRANSITIONS`
  (the "backward compat: encoding.success webhook fires before Story 6.3 is deployed" hole), and
  route the two remaining **legitimate** `PROCESSING→READY` callers — provider-driven reconciliation
  corrections — through a path that does not fire the `video.moderation.bypass` alarm.
- **Verified at HEAD:** `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`
  — `VALID_TRANSITIONS` still maps `PROCESSING → {SCANNING, READY, FAILED}` (`:35`) with the
  `// TODO Story 6.5: remove PROCESSING→READY …` comment (`:36-37`). `transitionOperationalState`
  (`:71-74`) already logs WARN "moderation pipeline was not run" and increments
  `meterRegistry.counter("video.moderation.bypass", "from","PROCESSING","to","READY")` for **any**
  `PROCESSING→READY`. **The original bypass producer is already gone:**
  `WebhookEventProcessorScheduler` (`:150-168`) explicitly records `encodingCompletedAt` and
  returns — "Do NOT call completeTranscoding() — that would bypass all three moderation layers." The
  only live `PROCESSING→READY` callers now are `ReconciliationWorkerScheduler.processReconciliation`
  (`:79-84`, provider says READY / local stuck PROCESSING → forward-correct) and
  `AdminVideoService:161` (admin reconcile) — both legitimate corrections that currently trip the
  bypass counter + the alarming WARN, which is a **false positive**. `VideoLifecycleServiceTest:121-125`
  asserts the counter for a `PROCESSING→READY` call.
- **Fix approach:**
  - Remove `READY` from `PROCESSING`'s `VALID_TRANSITIONS` set (leave `SCANNING`, `FAILED`).
  - Add a dedicated `reconcileToReady(UUID videoId, String reason)` (or a `boolean reconciliation`
    param on a small private helper) that performs the `PROCESSING→READY` write for the reconciliation
    / admin paths **without** the `video.moderation.bypass` counter or the "moderation pipeline was
    not run" WARN — instead an INFO tied to the incident/reason. Point **both**
    `ReconciliationWorkerScheduler.processReconciliation` and `AdminVideoService` (`:161`) at it — the
    admin correction is in scope, not just the scheduler. The
    `ReconciliationIncident(STATE_CORRECTED, …)` row `ReconciliationWorkerScheduler` already writes is
    the durable record of a legitimate correction; a distinct meter
    (`video.reconciliation.state_corrected`) is optional, not required.
  - Keep a **belt-and-suspenders** branch in the plain `transitionOperationalState`: if it is ever
    asked for `PROCESSING→READY` (now not in `VALID_TRANSITIONS`), log **ERROR** and still increment
    `video.moderation.bypass` before throwing `TerminalStateViolationException` — so a regression is
    loud, not silent.
  - Update `VideoLifecycleServiceTest` (the `:121-125` case now asserts the throw + one counter
    increment) and any IT that drove `PROCESSING→READY` as a legal transition.
- **Files:** `VideoLifecycleService.java`, `ReconciliationWorkerScheduler.java`,
  `AdminVideoService.java`, `VideoLifecycleServiceTest.java`, affected video ITs.
- **Test:** unit — plain `transitionOperationalState(id, READY)` from `PROCESSING` throws
  `TerminalStateViolationException` and increments `video.moderation.bypass` exactly once + logs
  ERROR; the reconciliation path drives `PROCESSING→READY` successfully and does **not** touch
  `video.moderation.bypass`. IT — `ReconciliationWorkerScheduler` still corrects a `PROCESSING` video
  the provider reports `READY`.
- **Ledger:** `## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades
  (2026-06-23)` — W5 ("`PROCESSING→READY` backward-compat bypass not removed"). Record in the Dev
  Agent Record that the webhook producer was already removed (so this is cleanup + false-alarm fix,
  not a behaviour change to any live happy path).

### AC6: Consolidate `PendingBlobDeletionService` onto the generic transactional outbox

- **Task:** Replace the bespoke `PendingBlobDeletion` mini-outbox with the generic `platform.outbox`
  (`OutboxService` + an `OutboxMessageHandler`) introduced by `skillars-deferred-91` AC1, so the
  codebase has one transactional-outbox implementation.
- **Verified at HEAD:**
  - Bespoke: `platform/filestorage/service/PendingBlobDeletionService.java` (145 LOC) +
    `PendingBlobDeletionChunkProcessor.java` (85) + `repo/PendingBlobDeletion.java` (46) +
    `PendingBlobDeletionRepository.java` (35). Shape: `enqueue(...)` publishes
    `BlobDeletionsEnqueuedEvent` **inside** the business tx; an `@TransactionalEventListener(AFTER_COMMIT)`
    `drain()`; a `@Scheduled` safety-net sweep (`app.storage.pending-deletion-sweep-ms`, default
    300000); `PendingBlobDeletionChunkProcessor.processChunk()` runs each chunk in its own
    `REQUIRES_NEW` tx with an `attempts`-ordered backoff and a bounded drain loop.
  - Generic: `platform/outbox/` — `OutboxService`, `OutboxMessage(Repository)`, `OutboxRowProcessor`,
    `OutboxChunkProcessor`, `OutboxMessageHandler`, `OutboxDrainRequestedEvent`, `OutboxConfig`.
    `deferred-91` residual note: "`PendingBlobDeletionService` (its own reviewed outbox; re-expressing
    it on the generic one is a nice-to-have)". `deferred-90` R1: "No bulk-delete capability in the
    storage stack … Left open deliberately." (Bulk-delete is **not** required by this AC — the drain
    is off the request path — but note it if the generic handler makes wiring it trivial.)
- **Fix approach:**
  - Add an `OutboxMessageHandler` implementation for blob-key deletion (payload = storage key +
    provider/bucket context) that calls the same `StorageService`/`S3StorageService` single-key
    delete the chunk processor uses today.
  - Replace `PendingBlobDeletionService.enqueue(...)` call sites with `OutboxService.enqueue(...)`
    inside the same business transaction (preserve enqueue-inside-tx semantics — this is the whole
    point of the pattern).
  - Rely on the generic outbox's existing AFTER_COMMIT drain + scheduled sweep + `REQUIRES_NEW`
    chunking + attempts/backoff. If the generic outbox lacks the bounded-drain safety stop or the
    attempts-ordering the bespoke one has, port that behaviour into the generic processor (record it).
  - **Do NOT drop `pending_blob_deletion` in this story.** Dropping a table the just-previous
    release still reads/writes is exactly the `DROP_WITHOUT_PRIOR_RELEASE_PREP` hazard
    `docs/deployment/migration-conventions.md` and `MigrationLint` exist to prevent — and `IF EXISTS`
    does not help a concurrent old-release `processChunk()` mid-transaction. Expand/contract instead:
    1. Swap every `PendingBlobDeletionService.enqueue(...)` call site to `OutboxService.enqueue(...)`
       (single PR — the handler and the call-site swap ship together, so there is no dual-write
       window within this release).
    2. Drain any **residual** `pending_blob_deletion` rows so nothing is lost: **Recommended approach:**
       add a one-shot `ApplicationRunner` that re-enqueues them through `OutboxService` at startup.
       This is cleaner and faster. **Fallback:** if residual rows are large or numerous, keep
       `PendingBlobDeletionChunkProcessor`'s scheduled drain running for one more release (longer
       transition, safer if data volume is unknown). Verify the table is empty in every environment
       first — if it always is, the one-shot is optional. Record which path was taken.
    3. Delete `PendingBlobDeletionService`, `BlobDeletionsEnqueuedEvent`, and (if path 2a was taken)
       `PendingBlobDeletionChunkProcessor`. **Keep `PendingBlobDeletion` (entity) +
       `PendingBlobDeletionRepository`** so the one-shot / residual drain can still read the table.
    4. The `DROP TABLE IF EXISTS pending_blob_deletion` + the `PendingBlobDeletion`/repo deletion are
       a **follow-up for a later release**, with approval criteria: **table is verified empty in all
       production environments for 7 consecutive days** (or after release X+1 is confirmed stable,
       whichever is later). AC7 adds the ledger line with this condition.
- **Files:** new handler in `platform/notification`- or `platform/filestorage`-adjacent to the
  outbox pattern; call sites of `PendingBlobDeletionService.enqueue`; `platform/outbox/*` (only if
  porting the safety-stop/backoff); a one-shot residual re-enqueue (`ApplicationRunner`) *or* the
  retained scheduled drain; the partial class deletions above (**no** `pending_blob_deletion` table
  drop this release); `application.yaml` (retire `app.storage.pending-deletion-sweep-ms` or map it
  to the outbox sweep, unless the drain is retained for one release); the existing
  `PendingBlobDeletion*IT` retargeted.
- **Test:** IT — a business operation that enqueues a blob deletion commits → the key is deleted
  after the AFTER_COMMIT drain; a `StorageService` delete failure retries on the next sweep via the
  outbox `attempts` counter and is not lost; a rollback of the business tx enqueues nothing; a
  residual `pending_blob_deletion` row present at startup is re-enqueued (or drained) and processed,
  **not lost**. Keep the assertions the current `PendingBlobDeletion*IT` makes, re-pointed at the
  generic outbox.
- **Ledger:** `## Deferred from: skillars-deferred-90 story creation and implementation (2026-09-02)`
  — R1 ("No bulk-delete capability in the storage stack … Left open deliberately") and
  `## Deferred from: skillars-deferred-91 story creation and implementation (2026-09-03)` — the
  "`PendingBlobDeletionService` … re-expressing it on the generic one is a nice-to-have" bullet in
  the AFTER_COMMIT catalogue. Trim/annotate both to reflect the consolidation (leave the pure
  bulk-delete-API gap open if this AC doesn't wire `DeleteObjects`).

### AC7: Ledger hygiene — prune the lines this story and prior work supersede

- **Task:** After AC1–AC6 land, update `_bmad-output/implementation-artifacts/deferred-work.md`:
  delete the bullets these ACs close, and delete two lines verified **stale** during this story's
  creation (closed by earlier work, never pruned).
- **Verification recording format:** For each deleted line, add an inline closure comment in the
  markdown (not a separate document):
  ```markdown
  <!-- skillars-deferred-100 AC7: verified closed by [AC#] at [commit-sha]:[file]:[line] -->
  ```
  Example: `<!-- skillars-deferred-100 AC7: verified closed by AC1 at d62cef04:ReliabilityStrikeService.java:87-98 -->`
  This allows future audits to independently verify the closure claim without digging through git history.
- **Delete (closed by this story):**
  - `skillars-7-3` D4 → AC1.
  - `skillars-4-3` W3 (incl. its `[Note 2026-08-27]`) → AC2.
  - `skillars-6-5` W3 → AC3.
  - `skillars-3-9` W2 → AC4.
  - `skillars-6-5` W5 → AC5.
- **Delete (verified stale at HEAD `8af28a42`, record the check):**
  - `skillars-3-9` **W1** ("Race condition in `updateBatchStatusFromBooking` under concurrent coach
    actions … batch status outcome is indeterminate") — closed by `skillars-deferred-69 AC6`:
    both `updateBatchStatusFromBooking` (`BookingBatchService.java:509`) and `acceptAll`'s trailing
    tx (`:407`) take `batchRepository.findByIdForUpdate(batchId)` and share `computeBatchStatus`.
  - `skillars-7-1` **D2** ("Session pack purchase always fails with `payment.providerUnavailable` in
    Story 7.1 — Story 7.2 implements real charging") — stale: `payment.providerUnavailable` no
    longer appears on any pack-purchase path (grep: only `StripeOnboardingService.java:46,56,70`).
    Story 7.2 shipped real charging long ago. **Leave `skillars-7-1` D3 and D4** — D4
    (`acceptBooking` fires `PAYMENT_CAPTURED` without a real capture) is genuinely still open and
    out of scope here.
- **Annotate (updated by AC6):** the `skillars-deferred-90` R1 bullet and the
  `skillars-deferred-91` "`PendingBlobDeletionService` … nice-to-have" bullet — mark the
  consolidation done; keep the pure `DeleteObjects` bulk-API gap open only if AC6 didn't wire it.
- **Add (follow-up owed by AC6):** a new dated bullet — `pending_blob_deletion` table +
  `PendingBlobDeletion` entity/repo are unreferenced after deferred-100 and must be dropped in a
  **later** release (`DROP_WITHOUT_PRIOR_RELEASE_PREP` — cannot drop in the same release that stops
  using it). Record whether residual rows were re-enqueued into the generic outbox (nothing left to
  migrate) or the bespoke drain was kept for one release (retire it in the same follow-up).
- **Note for the next story-creation audit (add under a short dated sub-heading, do not turn into an
  AC):** the `deferred-94` "Actuator health endpoint should surface notification-channel
  reachability" bullet — the **SMTP half shipped** in `skillars-deferred-99` AC5
  (`SmtpHealthIndicator`, `application.yaml:175`); the **Slack half does not apply to the
  application** — the app has no Slack integration, Slack notification lives only in
  `.github/workflows/deploy.yml`. Reframe the bullet to "SMTP done; Slack N/A to the app" or delete.
- **Files:** `deferred-work.md` only.
- **Test:** n/a (docs). The `deferred-work.md` line count should drop; no open, untagged item is
  removed without a closing AC or a recorded staleness check.
- **Ledger:** this AC *is* the ledger update.

---

## Not in scope (verified already delivered or deliberately deferred)

- **Migration online-safety "playbook + lint + forward-only retrofit"** — **already shipped.**
  `MigrationLint` (`src/test/java/com/softropic/skillars/db/MigrationLint.java`) already carries
  `MISSING_LOCK_TIMEOUT` (deferred-92 AC8), `ADD CONSTRAINT … FK|CHECK without NOT VALID`, and
  `CREATE INDEX` (non-`CONCURRENTLY`) rules, bound from `DEFERRED_92_BASELINE = 127`.
  `docs/deployment/migration-conventions.md` already has the "Go-forward checklist
  (skillars-deferred-99 AC8)", the "Pre-production migration debt" section, and the per-migration
  `V60`/`V94`/`V117`/`V97` hazard+redo table (deferred-91 code-review D7). Nothing left to build; a
  full pre-production re-issue of `V60`/`V94`/`V117` is explicitly a pre-launch task, not story work.
- **`skillars-3-9` W1** — closed by `skillars-deferred-69 AC6` (see AC4 / AC7).
- **`skillars-7-3` D1** (`AFTER_COMMIT` refund-drop, platform-wide), **`skillars-10-2` D1**,
  **`skillars-deferred-91` completion-gated coach payout (AC5 Part B)**, native `@Modifying` on
  **non-versioned** tables, the DE/FR native-speaker review, the `frontend-test-framework-initiative`
  dependents, and the four deploy items claimed by `skillars-deferred-97` — all out of scope,
  unchanged.

---

## Tasks / Subtasks

- [x] **AC1** — strike race
  - [x] `ReliabilityStrikeService.issue()`: `findByIdForUpdate` + `PessimisticLockRetryer`, count/threshold under the lock
  - [x] concurrent-`issue()` IT (one event), mutation check, happy-path unit unchanged
- [x] **AC2** — orphaned provider asset
  - [x] durable pre-create record (`pending_provider_asset` table) + ack on `Video` persist
  - [x] sweeper (extended `ReconciliationWorkerScheduler`) → `deleteAsset` + `ORPHANED_ASSET` incident, HTTP outside tx
  - [x] rollback IT (orphan purged / tracker survives caller rollback), recent-row IT (untouched), committed-`Video` IT (cleaned up, never purged), transient-error IT
- [x] **AC3** — native `@Modifying` / `@Version` audit
  - [x] produce the audit list (table, statement, entity, verdict) in Dev Agent Record
  - [x] `version = version + 1` where required; per-query Javadoc where safe
  - [x] `NativeModifyingVersionAuditTest` source/reflection guard
- [x] **AC4** — batch-status listener boundary
  - [x] `@Transactional(REQUIRES_NEW, readOnly = true)` on the listener (or move the lookup into the service)
  - [x] IT: recompute with no transactionless lookup; `batchId == null` no-op
  - [x] delete stale `skillars-3-9` W1 (record the `deferred-69 AC6` verification) — done in the AC7 `deferred-work.md` pass
- [x] **AC5** — `PROCESSING→READY` removal
  - [x] drop `READY` from `PROCESSING` in `VALID_TRANSITIONS`
  - [x] `reconcileToReady(id, reason)` path (no bypass counter/WARN) for `ReconciliationWorkerScheduler` + `AdminVideoService`
  - [x] belt-and-suspenders ERROR + counter + throw in the plain method
  - [x] update `VideoLifecycleServiceTest:121-125` + video ITs
- [x] **AC6** — blob-deletion outbox consolidation
  - [x] `OutboxMessageHandler` for blob-key delete; swap `enqueue` call sites to `OutboxService.enqueue`
  - [x] port bounded-drain safety stop / attempts-ordering into the generic processor if missing — **not needed**, the generic outbox already has all of it (and more: per-row tx, separate failure-recording tx, `next_attempt_at` backoff)
  - [x] drain residual `pending_blob_deletion` rows — one-shot `ApplicationRunner` re-enqueue
  - [x] delete `PendingBlobDeletionService` + `BlobDeletionsEnqueuedEvent` + `ChunkProcessor`; **keep** `PendingBlobDeletion` entity + repo for the drain
  - [x] **no** `DROP TABLE` this release — AC7 adds the follow-up ledger line
  - [x] retarget `PendingBlobDeletion*IT`; add a residual-row IT
- [x] **AC7** — ledger hygiene
  - [x] delete the closed + stale bullets; annotate the AC6 ones; add the dated `deferred-94`-health note

---

## Dev Notes

### Patterns to follow (verified in-repo)

- **Pessimistic row lock:** `repository.findByIdForUpdate(id)` **wrapped in**
  `lockRetryer.withBoundedRetry(() -> ...)`. `entityManager.refresh(entity, PESSIMISTIC_WRITE)` is
  needed **only** when the row is already managed earlier in the same transaction
  (`BookingService.acceptBooking`); a method with a fresh persistence context (`REQUIRES_NEW`, or a
  method that doesn't pre-load the row) does not need it (`BookingBatchService.acceptOneBooking` /
  `updateBatchStatusFromBooking` — read their comments). AC1's `issue()` does not pre-load the coach →
  no refresh.
- **Concurrency IT shape:** `CountDownLatch` + `ExecutorService`, both threads released on one latch;
  see `src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java`
  (`concurrentDoubleSoftDelete_loserRechecksUnderLockAndConflictsCleanly`).
- **HTTP-outside-transaction in a reconciler:** `ReconciliationWorkerScheduler.reconcile()` — the
  provider call is outside any `@Transactional`, each correction is its own `transactionTemplate.execute`.
  AC2's sweeper must copy this.
- **Build-time source/reflection guard tests:** `MigrationLint` /
  `AsyncExecutorQualifierTest` / `AppEndpointsConventionTest` — the model for AC3's guard.
- **Generic transactional outbox:** `platform.outbox` — `OutboxService.enqueue(...)` called inside
  the producing transaction; `OutboxMessageHandler` for the side effect; `OutboxChunkProcessor` /
  `OutboxRowProcessor` drain in `REQUIRES_NEW` chunks. AC6 target.
- **Flyway:** all schema changes are Flyway (`src/main/resources/db/migration`), next free `V###`,
  `SET lock_timeout`, additive-first, guarded `DROP … IF EXISTS`. `MigrationConventionLintTest` runs
  in the `test` phase and fails the build on the mechanical subset for `V128+`.
- **No local `mvn verify`** — GitHub CI is the gate (project convention + `deferred-work.md`).
  Respect the CI ~37 `@SpringBootTest`-context ceiling: prefer spy-free ITs, reuse existing fixtures.

### Files this story touches (source tree)

| Path | AC | Change |
|---|---|---|
| `platform/payment/service/ReliabilityStrikeService.java` | AC1 | locked coach read |
| `platform/session/service/DrillUploadService.java` (and/or `platform/video/service/VideoService.java`) | AC2 | durable pre-create record |
| `platform/video/service/ReconciliationWorkerScheduler.java` | AC2, AC5 | orphan sweeper; `reconcileToReady` |
| new `pending_provider_asset` entity/repo **or** `OutboxMessageHandler` | AC2 | orphan tracking |
| ~audited `*Repository.java` (VideoRepository et al.) | AC3 | `version = version + 1` / Javadoc |
| `platform/booking/service/BookingBatchStatusListener.java` | AC4 | `@Transactional(REQUIRES_NEW, readOnly)` |
| `platform/video/service/VideoLifecycleService.java` | AC5 | drop `PROCESSING→READY`; belt-and-suspenders |
| `platform/video/service/AdminVideoService.java` | AC5 | use `reconcileToReady` |
| `platform/filestorage/service/PendingBlobDeletionService` + `BlobDeletionsEnqueuedEvent` (+ `ChunkProcessor` if one-shot) | AC6 | delete; **keep** `PendingBlobDeletion` entity + repo for the residual drain |
| `platform/outbox/*` | AC6 | new blob-delete handler; maybe port safety-stop; maybe a one-shot `ApplicationRunner` |
| `src/main/resources/db/migration/V###__*.sql` | AC2 (if table route) | new orphan-tracking table only; **no** `pending_blob_deletion` drop this release |
| `docs`/ n/a; `_bmad-output/implementation-artifacts/deferred-work.md` | AC7 | prune |

### Project Structure Notes

- All new code stays under `com.softropic.skillars.platform.{module}.{layer}` — no
  `infrastructure` additions (AC2's orphan-tracking is domain state → `platform.video.repo` or the
  existing `platform.outbox`, never `infrastructure`).
- AC2 tracking table, if chosen over the outbox, is a `platform.video` concern (it is about video
  provider assets) — entity in `platform/video/repo`, migration in the `main` schema alongside
  `videos`.
- No REST endpoints added; no `@PreAuthorize` surface change.

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — ledger lines per AC (HEAD `8af28a42`)
- [Source: _bmad-output/project-context.md] — records/MapStruct/Flyway/testing rules
- [Source: docs/deployment/migration-conventions.md#Go-forward checklist] — migration shape for AC2/AC6
- [Source: src/main/java/.../BookingBatchService.java:406-514] — the `findByIdForUpdate` + `withBoundedRetry` pattern and the already-closed W1
- [Source: src/main/java/.../ReconciliationWorkerScheduler.java] — reconciler shape for AC2, `PROCESSING→READY` caller for AC5
- [Source: src/test/java/.../MigrationLint.java] — guard-test model for AC3
- [Source: skillars-deferred-99 …#AC5] — `SmtpHealthIndicator` (the shipped half of the `deferred-94` health item)
- [Source: skillars-deferred-91 …#AC1] — the generic `platform.outbox` AC6 targets

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code `/bmad-dev-story`)

### Debug Log References

- AC1 concurrency IT first failed because direct `jdbcTemplate.update` seed inserts were not
  wrapped in `transactionTemplate.execute` (the shared connection does not auto-commit) — the 4
  seeded strikes never persisted. Fixed by wrapping the seed loop, matching `BasePaymentIT`
  fixtures and `SoftDeleteIT`.
- AC1 mutation check verified: reverting `findByIdForUpdate` → `findById` makes
  `ReliabilityStrikeConcurrencyIT` fail — both threads publish `StrikeThresholdReachedEvent`, the
  second synchronous `AdminAlertEventListener.onStrikeThreshold` insert trips
  `admin_alerts_unique_open_per_ref`, one `issue()` tx rolls back with
  `DataIntegrityViolationException` (losing its strike). The `could not obtain lock on row`
  Hibernate ERROR log line during the passing run is expected: it is the NO_WAIT collision that
  `PessimisticLockRetryer` then retries to success.

### Completion Notes List

**AC1 — reliability-strike threshold race (DONE)**
- `ReliabilityStrikeService.issue()` now takes a `PESSIMISTIC_WRITE` lock on the coach row via
  `PessimisticLockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId))`
  before the count → threshold → status decision. The `countByCoachIdAndCreatedAtAfter` read was
  moved *below* the locked read so count and status are consistent for the winner; the loser blocks
  on the lock, then re-reads `status = PENDING_REVIEW`/`REDUCED` and its existing guard suppresses
  the duplicate `StrikeThresholdReachedEvent` / `CoachVisibilityReducedEvent` and the duplicate
  `save`.
- The strike `INSERT` is kept **before** the lock (smaller diff; it happens regardless of outcome;
  `PessimisticLockRetryer` flushes at the start of each attempt so it is durable before the locked
  read and counted by the query, and its savepoint is taken *after* that flush so a retry does not
  discard it). Documented inline.
- No `entityManager.refresh`: `issue()` never pre-loads the coach row, so the locked read returns
  fresh state (same rationale `BookingBatchService.acceptOneBooking` documents).
- No bespoke retry on `PessimisticLockingFailureException` exhaustion — deliberate, per AC1: the
  refund-listener callers have no retry, a dropped strike-escalation is less harmful than the
  refund they protect, and the admin path surfaces the error for an operator retry.
- Unit test `ReliabilityStrikeServiceTest` updated (mock `PessimisticLockRetryer` pass-through,
  `findByIdForUpdate` stub); all 6 cases still green. New `ReliabilityStrikeConcurrencyIT` (two
  concurrent threshold-crossing `issue()` calls → exactly one event, coach `PENDING_REVIEW` once,
  both strike rows persisted). `CoachVisibilitySuppressionIT` (3 tests) and
  `CancellationRefundMatrixTest` (12) regression-green.

**AC2 — orphaned video-provider assets (DONE)** — preferred approach: durable pre-create record.
- New `main.pending_provider_asset` table (`V131`, additive `CREATE TABLE` only, passes
  `MigrationConventionLintTest`) + `PendingProviderAsset` entity + `PendingProviderAssetRepository`
  (all `platform.video`, no `infrastructure`).
- New `PendingProviderAssetTracker.record(providerAssetId, provider)` —
  `@Transactional(REQUIRES_NEW)`, so the row commits the instant the Bunny asset is created,
  **before** any caller transaction can roll back. `VideoService.initializeUpload` **and**
  `retryUpload` both call it right after `videoProviderAdapter.initializeUpload(...)`. The ack —
  `pendingProviderAssetRepository.deleteByProviderAssetId(...)` — is done inside step 8's
  `transactionTemplate.execute` (the same tx as the `Video` persist), so the tracking row and the
  `Video` **share fate**: caller commits → row deleted, asset no longer orphanable; caller rolls
  back → both gone, row survives for the sweeper.
- `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets()` (new `@Scheduled`,
  `app.video.orphan-asset.sweep-delay-ms`, default 5 min): for each `pending_provider_asset` older
  than `app.video.orphan-asset.ttl` (default 30 min) — if a `Video` with that `provider_asset_id`
  exists, drop the stale row (no purge, no incident); else call
  `videoProviderAdapter.deleteAsset(...)` **outside any transaction** (mirrors `reconcile()`;
  idempotent — Bunny 404 == success, confirmed in `BunnyVideoProviderAdapter.deleteAsset`), then
  write `ReconciliationIncident(ORPHANED_ASSET, providerAssetId, …)` (its first producer — the enum
  value existed unused) and delete the row. A transient `VideoProviderException` is caught, counted
  and left for the next cycle.
- `VideoMetrics`: `video.orphan_asset.found` / `.purged` / `.purge_failed` counters.
- New `OrphanedProviderAssetSweepIT` (5 cases, shares `ReconciliationWorkerIT`'s context): old
  orphan → `deleteAsset` + `ORPHANED_ASSET` incident + row gone; old row with committed `Video` →
  row cleaned up, **no** `deleteAsset`, no incident; recent row (inside TTL) → untouched; transient
  `VideoProviderException` → row remains, no incident; and `trackerRecord_survivesTheCallersTransactionRollback`
  proves the `REQUIRES_NEW` write outlives a caller rollback. Mutation: don't run the sweep → orphan
  persists. `ReconciliationWorkerIT` / `VideoRetryUploadIT` / `VideoUploadResourceIT` /
  `DrillUploadServiceConcurrencyIT` / `VideoUploadPipelineIT` regression-green.
- Note: the pre-retry asset id that `retryUpload` overwrites is a separate, pre-existing leak — out
  of scope, noted inline.
- Files: `src/main/resources/db/migration/V131__pending_provider_asset.sql`;
  `platform/video/repo/PendingProviderAsset.java`, `.../PendingProviderAssetRepository.java`;
  `platform/video/service/PendingProviderAssetTracker.java`, `.../VideoService.java`,
  `.../ReconciliationWorkerScheduler.java`, `.../VideoMetrics.java`;
  `platform/video/config/VideoProperties.java`;
  `src/test/java/.../video/service/OrphanedProviderAssetSweepIT.java`,
  `.../video/service/VideoServiceTest.java`.

**AC4 — `BookingBatchStatusListener` transaction boundary (DONE)**
- `onBookingStatusChanged` now carries `@Transactional(propagation = REQUIRES_NEW, readOnly = true)`
  so the `findById` lookup and the delegate call run inside an explicit short transaction rather
  than transactionless in the AFTER_COMMIT window — consistent with
  `BookingBatchService.updateBatchStatusFromBooking`'s own `REQUIRES_NEW`. The `batchId != null`
  guard is preserved. The batch-row locking in `updateBatchStatusFromBooking` / `acceptAll` was
  **not** touched (correct per `skillars-deferred-69 AC6`).
- This AC is defensive hardening: `SimpleJpaRepository.findById` already opens its own
  `readOnly` transaction, so there was no live `TransactionRequiredException` — the two new
  `BatchAcceptPaymentIT` cases pin the behaviour (recompute in own tx; non-batched booking is a
  no-op with no exception) rather than demonstrating a prior crash.

**AC5 — remove `PROCESSING→READY` backward-compat transition (DONE)**
- `VideoLifecycleService.VALID_TRANSITIONS`: `PROCESSING` now maps to `{SCANNING, FAILED}` only.
  The original producer (pre-Story-6.3 `encoding.success` webhook) is gone —
  `WebhookEventProcessorScheduler` records `encodingCompletedAt` and explicitly does not complete
  transcoding, so this is cleanup + a false-alarm fix, not a behaviour change to any live happy
  path.
- New `VideoLifecycleService.reconcileToReady(UUID, String reason)`: the dedicated path for the
  two legitimate provider-driven `PROCESSING→READY` corrections. Same state write + same
  `VideoStatusChangedEvent`, but **no** `video.moderation.bypass` counter and **no** "moderation
  pipeline was not run" WARN — an INFO tied to `reason` instead. Idempotent when already READY;
  throws `VideoStateConflictException` from any other non-PROCESSING state.
- `ReconciliationWorkerScheduler.processReconciliation` and `AdminVideoService.triggerReconciliation`
  both now call `reconcileToReady(...)`. The `ReconciliationIncident(STATE_CORRECTED, …)` rows they
  already write remain the durable record; no new meter added (optional per AC).
- Belt-and-suspenders: the plain `transitionOperationalState`, if ever asked for `PROCESSING→READY`
  again, logs **ERROR**, increments `video.moderation.bypass` once, then throws
  `TerminalStateViolationException` — a regression is loud, not silent.
- `VideoLifecycleServiceTest`: the old `processingToReady_bypass_logsAndIncrementsCounter` case
  rewritten to assert the throw + one counter increment + no save; four new cases for
  `reconcileToReady` (moves PROCESSING→READY without the bypass counter; conflict from non-PROCESSING;
  idempotent no-op when already READY). `ReconciliationWorkerIT` (5) and `AdminVideoIT` (10) still
  green — the existing `reconcile → READY + STATE_CORRECTED` cases cover the IT requirement.
- Files: `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`,
  `.../ReconciliationWorkerScheduler.java`, `.../AdminVideoService.java`,
  `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleServiceTest.java`.

**AC3 — native `@Modifying` / `@Version` audit (DONE)**

Audit list — every `@Modifying` method on a repo whose domain type carries `@Version` (HEAD
2026-09-08). **No `nativeQuery = true` `@Modifying` write against a `@Version` table exists** — the
two flagged writes are JPQL bulk updates, which skip the version bump exactly the same way (JPA
spec: bulk updates bypass optimistic locking), so the guard and this audit cover both.

| repo.method | table | stmt | entity | verdict |
|---|---|---|---|---|
| `VideoRepository.resetLifecycleLockedAt` | `main.videos` | UPDATE (bulk) | `Video` | **`version = version + 1` added.** Concurrent per-row managed writers exist (`VideoLifecycleService.blockForSubscriptionExpiry` / `archiveForLifecycle` / `resetLifecycleClock` all `findById`+mutate+`save`); without the bump a stale save silently reverts the reset. The only caller (`VideoSubscriptionLifecycleListener.processAndSaveEntry`) catches `Exception`, retries and dead-letters, so the new `ObjectOptimisticLockingFailureException` path is handled. |
| `RefreshTokenRepository.markAllUsedByUserId` | `main.refresh_tokens` | UPDATE (bulk) | `RefreshToken` | **Allow-listed, no bump.** Writes only `used`, a monotonic `false→true` terminal flag; every concurrent managed writer (`AuthService.refresh` / `logout`) also only sets `used = true`, so the races converge and a stale save cannot resurrect a revoked token. A bump would convert benign convergent races into `OptimisticLockingFailureException`s that `AuthService.logout` does not handle. |
| `RefreshTokenRepository.deleteExpiredTokens` | `main.refresh_tokens` | DELETE | `RefreshToken` | Exempt (DELETE). |
| `LoginAttemptRepository.deleteByAttemptedAtBefore` | `main.login_attempts` | DELETE (derived) | `LoginAttempt` | Exempt (DELETE). |
| `PhoneOtpTokenRepository.deleteByUserIdAndUsedFalse` | `phone_otp_tokens` | DELETE | `PhoneOtpToken` | Exempt (DELETE). |
| `EmailVerificationTokenRepository.deleteByUserIdAndUsedFalse` | `email_verification_tokens` | DELETE | `EmailVerificationToken` | Exempt (DELETE). |

`Booking`, `SessionPackPurchase`, `SessionPackTier`, `CoachStripeAccount`, `Dispute`,
`EnvelopeEntity`, `Drill` carry `@Version` but their repositories declare **no** `@Modifying`
method — nothing to audit. No native `@Modifying` in any other repo targets one of these tables
by name (grepped).

- New guard `NativeModifyingVersionAuditTest` (test tree,
  `infrastructure/persistence/`): reflectively resolves each `*Repository`'s domain type, and for
  the `@Version` ones asserts every `@Modifying` method is a DELETE, a non-upsert INSERT, contains
  `version = version + 1`, or is in `ALLOWED_WITHOUT_BUMP` with a written reason. Mutation-checked
  (drop the bump from `resetLifecycleLockedAt` → guard fails naming it). Runs in the `test` phase,
  no container.
- New IT `VideoSubscriptionLifecycleListenerIT.resetLifecycleLockedAt_bumpsVersion_andRejectsStaleSave`:
  DB `version` column increments after the bulk reset, and a managed `Video` held from before the
  reset throws `ObjectOptimisticLockingFailureException` on `saveAndFlush`.
- Files: `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java`,
  `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java`
  (Javadoc only), `src/test/java/com/softropic/skillars/infrastructure/persistence/NativeModifyingVersionAuditTest.java`,
  `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java`.

**AC6 — consolidate `PendingBlobDeletionService` onto the generic outbox (DONE)**
- New `platform/filestorage/service/BlobDeletionOutboxHandler` (`aggregate_type = "BLOB_DELETION"`,
  idempotent `FileStorageService.deleteRawBytes` — S3 `DeleteObject` succeeds whether or not the key
  exists) and `BlobDeletionOutboxSupport` (the `enqueue(Collection<String>)` +
  `requestDrainAfterCommit()` facade, modelled on `RefundOutboxSupport`, owning the JSON payload
  format).
- `GdprErasureService` swapped from `PendingBlobDeletionService` to `BlobDeletionOutboxSupport` —
  same enqueue-inside-the-erasure-transaction semantics; the generic outbox's existing AFTER_COMMIT
  `@Async` drain + scheduled sweep + `REQUIRES_NEW` per-row chunking + `attempts`/`next_attempt_at`
  backoff take over. **Nothing had to be ported** into the generic processor — it already carries
  the bounded-drain safety stop (`MAX_CHUNKS_PER_DRAIN`), attempts-ordering + `FOR UPDATE SKIP
  LOCKED` (`claimNextDue`), and is strictly stronger (one-row transactions, separate
  failure-recording transaction).
- Deleted `PendingBlobDeletionService`, `PendingBlobDeletionChunkProcessor`,
  `BlobDeletionsEnqueuedEvent`. **Kept** `PendingBlobDeletion` entity + `PendingBlobDeletionRepository`
  (trimmed to just `JpaRepository`) so `PendingBlobDeletionResidualDrainRunner` (new
  `ApplicationRunner`) can migrate any rows a prior release left in `main.pending_blob_deletions`
  onto the generic outbox at startup — a no-op when the table is empty (expected). The
  `DROP TABLE main.pending_blob_deletions` + removing the runner/entity/repo is a **follow-up for a
  later release** (AC7 adds the ledger line) — cannot drop a table in the same release that stops
  using it (`migration-conventions.md`).
- `BandwidthResetChunkProcessor` javadoc `{@link}` re-pointed from the deleted
  `PendingBlobDeletionChunkProcessor` to `OutboxChunkProcessor`.
- No `application.yaml` change: `app.storage.pending-deletion-sweep-ms` had no yaml entry (only the
  inline `@Scheduled` default on the now-deleted `sweep()`); the generic `app.outbox.sweep-ms`
  covers it.
- `GdprErasureIT`: the 3 blob tests retargeted at `main.outbox_messages` (`aggregate_type =
  'BLOB_DELETION'`, `payload->>'storageKey'`); each now calls `outboxService.drain()` synchronously
  after the erasure so the assertion does not race the `@Async` drain. New
  `residualPendingBlobDeletionRows_areReEnqueuedOntoTheGenericOutbox` drives the runner directly.
  All 18 green; `RefundOutboxIT` / `ModerationOutboxIT` / `NotificationEmailOutboxAtomicityIT` /
  `OutboxServiceTest` regression-green.
- Files: new `platform/filestorage/service/BlobDeletionOutboxHandler.java`,
  `.../BlobDeletionOutboxSupport.java`, `.../PendingBlobDeletionResidualDrainRunner.java`; deleted
  `.../PendingBlobDeletionService.java`, `.../PendingBlobDeletionChunkProcessor.java`,
  `platform/filestorage/contract/event/BlobDeletionsEnqueuedEvent.java`; edited
  `platform/admin/service/GdprErasureService.java`,
  `platform/filestorage/repo/PendingBlobDeletionRepository.java`,
  `platform/filestorage/repo/PendingBlobDeletion.java`,
  `platform/video/service/BandwidthResetChunkProcessor.java`,
  `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`.

**AC7 — ledger hygiene (DONE)**
- `_bmad-output/implementation-artifacts/deferred-work.md` only. Each closed/stale bullet replaced
  with a dated `<!-- skillars-deferred-100 AC7 -->` closure comment (naming the closing AC + a
  verification pointer) rather than a bare delete, matching existing ledger practice
  (deferred-89 / deferred-92 closure comments) — so no open item is removed without a recorded
  check.
- Deleted (closed by this story): `skillars-7-3` D4 (→ AC1), `skillars-4-3` W3 incl. its
  `[Note 2026-08-27]` (→ AC2), `skillars-6-5` W3 (→ AC3), `skillars-3-9` W2 (→ AC4), `skillars-6-5`
  W5 (→ AC5).
- Deleted (verified stale at HEAD `8af28a42`): `skillars-3-9` **W1** — closed by
  `skillars-deferred-69 AC6` (both `booking_batches.status` writers take
  `findByIdForUpdate(batchId)` under `withBoundedRetry` and share `computeBatchStatus`, verified at
  `BookingBatchService.java:407` / `:509`); `skillars-7-1` **D2** — `payment.providerUnavailable`
  no longer on any pack-purchase path (grep: only `StripeOnboardingService.java:46,56,70`); left
  `skillars-7-1` D3 + D4 (D4 genuinely open, out of scope). The deferred-89 restoration comment was
  updated to record that D2's re-deletion is now authorised.
- Annotated (AC6): `skillars-deferred-90` R1 (bespoke mini-outbox gone; pure `DeleteObjects`
  bulk-API gap left open) and the `skillars-deferred-91` "`PendingBlobDeletionService` …
  nice-to-have" bullet (marked done).
- Added: a dated `## Deferred from: skillars-deferred-100 implementation (2026-09-08)` section with
  the AC6 follow-up — drop `main.pending_blob_deletions` + `PendingBlobDeletion` entity/repo +
  `PendingBlobDeletionResidualDrainRunner` in a **later** release once the table is provably empty
  (records that residual rows go via the one-shot `ApplicationRunner`, not a retained scheduler).
- Reframed the `deferred-94` "Actuator health endpoint should surface notification-channel
  reachability" bullet: SMTP half shipped in `skillars-deferred-99 AC5` (`SmtpHealthIndicator`);
  Slack half N/A to the application (no Slack integration in the app) — closed.

### Review Findings

**[2026-09-08 bmad-code-review] — Three-layer adversarial + acceptance audit revealed 3 spec violations + 7 critical gaps**

#### VIOLATIONS (Spec Readiness Issues)

- [x] [Review][Decision] **AC2 BLOCKER: Asset ID reuse scenario unaddressed** — RESOLVED (option a + c). Verified `BunnyVideoProviderAdapter.initializeUpload` → `POST /library/{id}/videos` returns a fresh `guid` on every call and never content-deduplicates (`BunnyCreateVideoResponse(String guid)`), so a `providerAssetId` is globally unique to the one `initializeUpload` that created it; `pending_provider_asset` also carries `UNIQUE(provider_asset_id)`. `VideoService.initializeUpload` writes the tracking row and (on success) deletes it in the same transaction as the `Video` persist — both synchronous, no client round-trip between — so a row still present *and* older than the TTL *and* with no `videos.provider_asset_id` match is unambiguously a rolled-back orphan, not a slow live upload. The TTL (`app.video.orphan-asset.ttl`, default 30 min) is the documented tunable margin. Reasoning added to `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets` Javadoc + `V131` migration comment.

- [x] [Review][Decision] **AC3 BLOCKER: Audit scope unbounded—no completeness verification** — RESOLVED. `NativeModifyingVersionAuditTest` gained a second test, `noModifyingWriteAgainstAVersionedTableEscapesTheAudit()`, that keys off the **write target** instead of the repository's domain type: it discovers every `@Version` entity by classpath scan, resolves each to its JPQL name + bare table name + schema-qualified table name, then scans **every** `@Modifying @Query` on **every** repository and flags any `UPDATE` / `DO UPDATE` whose target table backs a `@Version` entity unless it bumps the version or is allow-listed. This is the mechanical completeness check — a native `UPDATE main.videos …` on any repo (not just `VideoRepository`) now fails the build. Passes at HEAD (one audited target: `VIDEO`, with bump).

- [x] [Review][Decision] **AC4 BLOCKER: W1 staleness verification claimed but not shown** — RESOLVED. AC4 "Verified at HEAD" now carries both call sites verbatim (`acceptAll` trailing tx ~`:407`, `updateBatchStatusFromBooking` ~`:509`) showing each takes `batchRepository.findByIdForUpdate(batchId)` under `lockRetryer.withBoundedRetry` and computes status through the shared `computeBatchStatus(...)`. The `deferred-work.md` AC7 closure comment for `skillars-3-9` W1 already records the same verification with line anchors.

#### CRITICAL GAPS (Must Fix Before Production)

- [x] [Review][Decision] **AC1 Serialization point needs pseudo-code** — FIXED: Added pseudo-code showing exact locking sequence (strike INSERT before lock, count/threshold/status inside lock).

- [x] [Review][Decision] **AC2 Bunny delete idempotency undefined** — FIXED: Defined HTTP response handling — 404/4xx (except 401/403) = success, 5xx/transient = retry, 401/403 = error + skip.

- [x] [Review][Decision] **AC3 Bulk UPDATE "prove no concurrent managed instance" has no methodology** — FIXED: Added explicit checklist (grep for .save() call sites, inspect caller context, confirm no concurrent mutation+save pattern).

- [x] [Review][Decision] **AC3 UPDATE with WHERE version=? not addressed** — FIXED: Clarified WHERE version=? is a guard (prevents stale updates) but doesn't notify other readers; requires version bump if concurrent managed instances exist.

- [x] [Review][Decision] **AC6 Table drop timing undefined** — FIXED: Specified condition: "table verified empty in all prod environments for 7 consecutive days OR after release X+1 is confirmed stable".

- [x] [Review][Decision] **AC6 Residual row drainage path preference not stated** — FIXED: Recommended one-shot ApplicationRunner as default (cleaner, faster), with fallback to keep bespoke drain one release if data volume is large.

- [x] [Review][Decision] **AC7 Verification recording location undefined** — FIXED: Specified inline closure comment format: `<!-- skillars-deferred-100 AC7: verified closed by [AC#] at [commit]:[file]:[line] -->`

#### PATCH FINDINGS (Implementable Without Human Decision)

- [x] [Review][Patch] **AC3 Table list exhaustiveness — add guard test verification** `NativeModifyingVersionAuditTest` should assert no un-audited @Modifying on @Version repo exists, catching misses.

- [x] [Review][Patch] **AC4 Deleted booking flow not explicit** — Code silently no-ops on missing booking (implicit `.ifPresent`). Add IT case + comment documenting this is expected.

- [x] [Review][Patch] **AC5 Bypass counter reference missing** — Counter appears in tests but never defined in story. Add reference: `meterRegistry.counter("video.moderation.bypass", ...)`.

- [x] [Review][Patch] **AC6 Call-site enumeration incomplete** — Spec assumes only GdprErasureService enqueues blob deletions. Add exhaustive grep + IDE find-usages; document all call sites in PR.

- [x] [Review][Patch] **AC6 Entity/repo kept but service deleted—intent unclear** — Readers won't understand why `PendingBlobDeletion` entity+repo remain after service deletion. Add inline comment: "Retained for residual-row migration; drop in follow-up release with table."

- [x] [Review][Patch] **AC6 Residual drain runner — startup blocking risk** — If `ApplicationRunner` re-enqueue throws, deployment blocks. Wrap in try-catch, log ERROR, allow startup; add manual ops task.

- [x] [Review][Patch] **AC7 Annotation format undefined** — Spec says "annotate" but doesn't show expected format. Example: `<!-- skillars-deferred-100 AC7: verified closed by AC1 @ commit abc123:line-45 -->`.

---

**Reviewer Summary:** 
- Blind Hunter (adversarial): 11 findings — logic gaps, unsafe assumptions, ambiguities
- Edge Case Hunter (integration): 7 findings — deployment risks, edge cases, missing safety
- Acceptance Auditor (spec-driven): **3 AC violations** + 7 critical gaps — spec readiness issues that likely affected implementation quality

**No false positives detected.** All findings are actionable and correspond to real gaps in spec clarity or completeness that could lead to bugs, unsafe assumptions, or incomplete implementations. The story **can be shipped** but the spec should be clarified retroactively to prevent similar gaps in future work.

**[2026-09-08 — blocker patches applied]** All three `[Review][Decision]` VIOLATIONS above are now resolved (see each checkbox). Net changes:
- **AC2** — `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets` Javadoc + `V131__pending_provider_asset.sql` comment now spell out why a swept row cannot be a live asset (Bunny GUIDs globally unique per create + synchronous write/delete of the tracking row + configurable TTL). Doc-only; no logic change.
- **AC3** — `NativeModifyingVersionAuditTest.noModifyingWriteAgainstAVersionedTableEscapesTheAudit()` added: a target-table-keyed completeness scan across every repository, replacing reliance on the one-time manual grep.
- **AC4** — story AC4 "Verified at HEAD" now carries both `BookingBatchService` call sites verbatim as inline proof of the W1 closure.

---

**[2026-09-08 bmad-code-review — second run]** — Requested re-review of the *implementation* (three-layer:
Blind Hunter + Edge Case Hunter + Acceptance Auditor), diff = uncommitted working tree + untracked
files. 12 `patch` (all applied), 1 reclassified to dismiss, 5 `defer`, 8 dismissed as noise. No
`decision-needed`. Several findings contradicted `[x]`-marked resolutions in the first-run record above.

**[2026-09-08 — second-run patches applied]** All 12 patch findings below are fixed in the working
tree; touched unit tests green locally (`NativeModifyingVersionAuditTest`, `VideoLifecycleServiceTest`,
`VideoMetricsTest`, `ReliabilityStrikeServiceTest`, `VideoServiceTest`, `CancellationRefundMatrixTest`
— 51 tests). ITs (`OrphanedProviderAssetSweepIT`, `ReliabilityStrikeConcurrencyIT`, `GdprErasureIT`,
`BatchAcceptPaymentIT`, `VideoSubscriptionLifecycleListenerIT`) are the GitHub CI gate per project
convention. Net changes:
- **AC1** — `ReliabilityStrikeService.issue()` → `@Transactional(REQUIRES_NEW)`; the two
  `CancellationRefundService` call sites now go through `issueStrikeSafely(...)` which swallows
  `PessimisticLockingFailureException` so the already-enqueued refund commits regardless.
- **AC2** — `sweepOrphanedProviderAssets` gains `@SchedulerLock` (lockAtLeastFor `PT0S`); a new
  `pending_provider_asset.attempts` column + `(attempts, created_at)` index (V131) + attempts-first
  sweep ordering + `bumpAttempts` on failure so a poison row sinks below fresh work; a
  `video.orphan_asset.stuck` gauge + `[ORPHANED_ASSET_STUCK]` ERROR at ≥10 attempts; the per-row loop
  now also `catch (RuntimeException)` → metric + continue; `PendingProviderAssetTracker.record` uses a
  native `INSERT … ON CONFLICT (provider_asset_id) DO NOTHING` (+ blank-`provider` guard) instead of
  catch-inside-`REQUIRES_NEW`.
- **AC3** — `NativeModifyingVersionAuditTest`: `getMethods()` (catches inherited `@Modifying`),
  `normalise()` strips `/* */` comments, `WRITE_TARGET` is non-anchored and scanned for every
  `UPDATE`/`INSERT INTO` target (CTE bodies included), and `auditedVersionedTargets` is asserted
  non-empty (guards the guard).
- **AC5** — `reconcile()`'s per-video catch now also handles `VideoStateConflictException` /
  `VideoNotFoundException` (log + continue, don't abort the batch); `reconcileToReady` increments a
  new `video.reconciliation.state_corrected` counter on every applied correction.
- **AC6** — `PendingBlobDeletionResidualDrainRunner.run()` wrapped in `try/catch (RuntimeException)`
  → ERROR + startup continues; migration runs in bounded 100-row chunks via `deleteAllByIdInBatch`
  (tolerant of a row the old sweep already removed). `BlobDeletionOutboxSupport.enqueueOne` now
  rethrows `UncheckedIOException` so a serialization failure rolls the erasure back instead of
  committing `COMPLETED` with an un-enqueued PII key.
- **AC7** — each closure comment in `deferred-work.md` gained a machine-parseable
  `verified closed by <AC#> at <sha>:<file>:<line>` anchor line (sha = `WORKTREE` until commit).

_Reclassified to dismiss:_

- **AC2 sweeper interlock vs a live in-flight upload** — no effective patch: during the window EH
  describes (a stall between `tracker.record()` and step 8) neither a `Video` nor an `UploadSession`
  with that provider id is committed, so a session-existence check wouldn't catch it either. The real
  mitigation is the `orphan-asset.ttl` (30 min) being far longer than any request transaction (bounded
  by statement/lock timeouts and the ~3.2 s `PessimisticLockRetryer` budget), which is already in
  place and documented in the sweeper Javadoc.

_Patch findings (applied):_

- [x] [Review][Patch] **AC1 — `issue()` lock-retry exhaustion rolls back the co-located refund (HIGH)** — `ReliabilityStrikeService.issue()` is `@Transactional` (REQUIRED), so it *joins* the refund listener's `REQUIRES_NEW` transaction, in which `refundOutboxSupport.enqueueBookingRefund(...)` has already run (`CancellationRefundService.onCoachNoShow:94-99`, `onBookingCancelledByCoach:66-76`). A `PessimisticLockingFailureException` from `lockRetryer.withBoundedRetry` — the new failure mode AC1 introduced — marks that shared transaction rollback-only, so the refund outbox row is discarded and the AFTER_COMMIT listener never re-fires. The spec's own rationale ("a dropped strike-escalation is far less harmful than the refund those listeners exist to protect") is defeated: under sustained coach-row contention the refund is lost too. Fix: `@Transactional(propagation = REQUIRES_NEW)` on `issue()`, or wrap the two `reliabilityStrikeService.issue(...)` call sites in `CancellationRefundService` in `try/catch (PessimisticLockingFailureException)` + log. [ReliabilityStrikeService.java:37; CancellationRefundService.java:76,99]
- [x] [Review][Patch] **AC2 — orphan sweeper has no `@SchedulerLock` / `SKIP LOCKED` (MED)** — `sweepOrphanedProviderAssets` uses the plain derived query `findByCreatedAtBeforeOrderByCreatedAtAsc` and carries no `@SchedulerLock`, unlike `reconcile()` (`findNonTerminalForUpdate` … `FOR UPDATE SKIP LOCKED`) and every sibling sweep (`BandwidthResetService`, `QuotaReservationTimeoutService`). Multi-instance → duplicate `ORPHANED_ASSET` incidents, inflated `video.orphan_asset.*` counters, and the loser's `pendingProviderAssetRepository.delete(pending)` on an already-removed row raises `StaleStateException`/`EmptyResultDataAccessException`, aborting that instance's sweep loop. Fix: add `@SchedulerLock`, or a `FOR UPDATE SKIP LOCKED` claim query. [ReconciliationWorkerScheduler.java:152]
- [x] [Review][Patch] **AC2 — a permanently-failing `deleteAsset` clogs the sweeper head forever; 401/403 not distinguished (MED)** — `BunnyVideoProviderAdapter.deleteAsset` only special-cases HTTP 404; 401/403/400/persistent-5xx all collapse to `VideoProviderException`, which the sweeper catches and "leaves for the next cycle". `pending_provider_asset` has no `attempts` column and the query is oldest-first, so ≥`batchSize` poison rows sit permanently at the head and no newer orphan is ever purged (the `deferred-90` D1 failure mode). No stuck alert (the generic outbox has one). The first-run record marks this `[x] FIXED` but only the spec prose was clarified. Fix: add `attempts`/`next_attempt_at` + attempts-ordering (mirror the generic outbox), or at minimum a `video.orphan_asset.stuck` gauge and skip-past semantics. [ReconciliationWorkerScheduler.java:181; V131]
- [x] [Review][Patch] **AC2 — sweep loop only catches `VideoProviderException` (MED)** — a DB error in the incident-write/row-delete transaction, or `deleteAsset` succeeding then that transaction failing (asset gone, tracking row stays → re-purge + re-increment `found`/`purged` every cycle), or any other `RuntimeException`, escapes the per-row `try` and aborts the rest of the batch. Fix: per-row `catch (Exception)` → metric + `continue`; make the incident write tolerant of an already-deleted row. [ReconciliationWorkerScheduler.java:168]
- [x] [Review][Patch] **AC6 — residual drain runner has no error handling and is unbounded (MED)** — `PendingBlobDeletionResidualDrainRunner.run()` lets any failure (`enqueue`, `deleteAll`, DB slow/unavailable, or a race with the still-scheduled old `PendingBlobDeletionService.sweep()` on old pods during a rolling deploy deleting a row mid-`deleteAll`) propagate out of `ApplicationRunner` → **boot fails**, precisely when residual rows exist. `findAll()` is also unchunked (the deleted drain used `CHUNK_SIZE=25`). The first-run record marks `[x] Wrap in try-catch, log ERROR, allow startup` — not done. Fix: `try/catch (Exception)` + log ERROR + return; chunk the migration; `OutboxService.sweep()` is the safety net. [PendingBlobDeletionResidualDrainRunner.java:40]
- [x] [Review][Patch] **AC3 — `NativeModifyingVersionAuditTest` completeness holes (MED)** — `auditedVersionedTargets` is computed and never asserted, so if `WRITE_TARGET`/table-token matching drifts to zero hits the completeness test passes green while checking nothing; `repo.getDeclaredMethods()` skips `@Modifying` methods inherited from a `@NoRepositoryBean` base; `normalise()` strips only `--` comments (a `/* */` prefix or `WITH … UPDATE` CTE slips past `WRITE_TARGET`). Fix: assert `auditedVersionedTargets` covers the known target(s); use `getMethods()`; strip block comments / handle a leading `WITH`. [NativeModifyingVersionAuditTest.java:183]
- [x] [Review][Patch] **AC2 — `PendingProviderAssetTracker.record` swallows `DataIntegrityViolationException` inside `REQUIRES_NEW` (LOW)** — the JPA provider has already marked the transaction rollback-only, so the catch does not prevent `UnexpectedRollbackException` at commit: the "no-op if already tracked" path would instead fail the upload, and a null-`provider` NOT NULL violation is mislabeled "already tracked". Trigger is near-unreachable (Bunny GUIDs unique per call) but the mitigation is broken. Fix: `existsByProviderAssetId` pre-check, or native `INSERT … ON CONFLICT DO NOTHING`. [PendingProviderAssetTracker.java:34]
- [x] [Review][Patch] **AC6 — `BlobDeletionOutboxSupport.enqueueOne` swallows `JsonProcessingException` (LOW)** — GDPR erasure still commits `status = COMPLETED` with a PII storage key never enqueued anywhere (only a log line); the deleted `saveAll` would have rolled the erasure back. Fix: rethrow unchecked so the erasure transaction rolls back, or emit a metric/incident. [BlobDeletionOutboxSupport.java:49]
- [x] [Review][Patch] **AC5 — `reconcile()` per-video catch misses `VideoStateConflictException` / `VideoNotFoundException` from `reconcileToReady` (LOW)** — a concurrent state change between `findNonTerminalForUpdate` and the correction aborts the rest of the batch for that cycle (pre-existing failure shape, but this method is being changed and the fix is one line). Fix: broaden the `catch` in `reconcile()` to log-and-continue on those. [ReconciliationWorkerScheduler.java:64]
- [x] [Review][Patch] **AC5 — no metric for moderation-skipping reconciliation corrections (LOW)** — `reconcileToReady` correctly drops `video.moderation.bypass`, but the optional `video.reconciliation.state_corrected` meter was not added, so a PROCESSING→READY jump that skips the SCANNING moderation step is now only an INFO log + `STATE_CORRECTED` incident row. Fix: add a `video.reconciliation.state_corrected` counter at both call sites. [VideoLifecycleService.java:120; ReconciliationWorkerScheduler.java:87; AdminVideoService.java:161]
- [x] [Review][Patch] **AC2 — V131 has no index on `created_at` (LOW)** — the 5-minute sweep runs `WHERE created_at < ? ORDER BY created_at ASC LIMIT n` against a table with only `PK(id)` + `UNIQUE(provider_asset_id)`. Add `CREATE INDEX ix_pending_provider_asset_created_at ON main.pending_provider_asset (created_at)` (V131 is unreleased — safe to edit). [V131__pending_provider_asset.sql]
- [x] [Review][Dismiss] **AC2 — sweeper purge has no interlock against a live in-flight upload (LOW)** — reclassified: no effective patch (see the reclassified-to-dismiss note above). The `orphan-asset.ttl` (30 min) vs. request transactions bounded far below that is the documented mitigation. [ReconciliationWorkerScheduler.java:181]
- [x] [Review][Patch] **AC7 — closure comments don't follow the mandated machine-checkable format (LOW)** — the spec fixes `<!-- skillars-deferred-100 AC7: verified closed by [AC#] at [commit-sha]:[file]:[line] -->`; the actual comments are dated prose with no commit-sha and only loose file refs. Intent (auditable trail) is met; align the format (sha can be filled at commit time). [deferred-work.md]

_Deferred (pre-existing or explicitly descoped):_

- [x] [Review][Defer] **AC4 `BookingBatchStatusListener` has no failure isolation and no null-check on `event.bookingId()`** — pre-existing; AC4 only added the readOnly `REQUIRES_NEW` wrapper. [BookingBatchStatusListener.java] — deferred, pre-existing
- [x] [Review][Defer] **AC2 `retryUpload` orphans the pre-retry provider asset** (no tracking row; invisible to both the sweeper and `reconcile()`) — explicitly descoped by the spec + inline comment. [VideoService.java:180] — deferred, pre-existing
- [x] [Review][Defer] **AC5 replayed/queued pre-deploy `encoding.success` events driving PROCESSING→READY on the plain path now throw + fire the bypass alarm** — accepted cutover risk; producer verified removed at HEAD; loud-by-design and re-drivable. [VideoLifecycleService.java:77] — deferred, pre-existing
- [x] [Review][Defer] **AC5 `reconcileToReady` already-READY no-op still lets the scheduler write a `STATE_CORRECTED` incident** — pre-existing (old idempotent path did the same); cosmetic audit-trail. [ReconciliationWorkerScheduler.java:89] — deferred, pre-existing
- [x] [Review][Defer] **AC3 `RefreshTokenRepository.markAllUsedByUserId` allow-list rests on an unenforceable "every writer only sets `used = true`" invariant** — sound for current code; inherent to AC3's "document as safe" category; the guard test cannot catch a future violation. [RefreshTokenRepository.java] — deferred, pre-existing

_Dismissed as noise:_ AC3 `resetLifecycleLockedAt` explicitly setting `v.version` in JPQL (gated by the new real-Postgres IT); the version bump now throwing OLE in concurrent per-row writers (intended — loud beats silent data loss; sole caller handles it); AC4 two-connections / N-locks on the request thread (spec-prescribed; marginal delta over the pre-existing per-event `REQUIRES_NEW`); `TerminalStateViolationException` vs `VideoStateConflictException` taxonomy (deliberate); `GdprErasureIT` `attempts == 1` (stable under SKIP LOCKED + `next_attempt_at` backoff); AC6 lost GDPR-specific "stuck key" wording (the generic outbox's stuck alarm covers `BLOB_DELETION` rows); AC7 `skillars-7-1` D2 re-deletion (staleness independently verified by grep; restoration comment updated to authorise); AC3 physical-naming-strategy vs `camelToSnake` (latent only — all `@Version` entities have explicit `@Table`).

### File List

**Added**
- `src/main/resources/db/migration/V131__pending_provider_asset.sql`
- `src/main/java/com/softropic/skillars/platform/video/repo/PendingProviderAsset.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/PendingProviderAssetRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/service/PendingProviderAssetTracker.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/BlobDeletionOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/BlobDeletionOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionResidualDrainRunner.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/persistence/NativeModifyingVersionAuditTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/OrphanedProviderAssetSweepIT.java`

**Modified (main)**
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java` (AC1; + 2nd-run: `REQUIRES_NEW`)
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java` (2nd-run AC1: `issueStrikeSafely`)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchStatusListener.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java` (AC2, AC5)
- `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoMetrics.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java` (AC3 — Javadoc)
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletion.java` (AC6 — Javadoc)
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletionRepository.java` (AC6 — trimmed)
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetChunkProcessor.java` (AC6 — javadoc link)

**Deleted (main)**
- `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionChunkProcessor.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/filestorage/contract/event/BlobDeletionsEnqueuedEvent.java` (AC6)

**Modified (test)**
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/booking/service/BatchAcceptPaymentIT.java` (AC4)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleServiceTest.java` (AC5)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoServiceTest.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC6)

**Modified (docs)**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)

### Change Log

| Date | Change |
|---|---|
| 2026-09-08 | skillars-deferred-100 implemented — AC1 strike-race lock, AC2 orphaned-provider-asset sweeper (`V131` + `pending_provider_asset`), AC3 native/`@Modifying` × `@Version` audit + `NativeModifyingVersionAuditTest` guard, AC4 batch-status-listener transaction boundary, AC5 `PROCESSING→READY` removal + `reconcileToReady`, AC6 `PendingBlobDeletionService` → generic `platform.outbox` consolidation, AC7 `deferred-work.md` pruning. Status → review. |
| 2026-09-08 | Review-blocker patches — AC2: sweeper Javadoc + `V131` comment document why a swept row cannot be a live asset (unique Bunny GUID + synchronous tracking-row lifecycle + tunable TTL). AC3: added `NativeModifyingVersionAuditTest.noModifyingWriteAgainstAVersionedTableEscapesTheAudit()` — target-table-keyed completeness scan across all repositories. AC4: inline verbatim proof of the W1 closure added to the AC4 spec block. All three `[Review][Decision]` VIOLATIONS closed. |
| 2026-09-08 | Second bmad-code-review run (three-layer) + 12 patches applied. HIGH: AC1 `issue()` → `REQUIRES_NEW` + `issueStrikeSafely` in `CancellationRefundService` so a strike-lock-retry exhaustion can no longer roll back the co-located refund. MED: AC2 sweeper `@SchedulerLock` + `pending_provider_asset.attempts` (V131) + attempts-first ordering + stuck gauge/ERROR + broadened per-row catch + `ON CONFLICT DO NOTHING` tracker insert; AC6 residual-drain runner `try/catch` + chunked migration; AC3 guard-test completeness holes. LOW: AC6 enqueue rethrows on serialization failure; AC5 `reconcile()` catch breadth + `video.reconciliation.state_corrected` meter; AC7 machine-parseable closure-comment anchors. Status → done (pending GitHub CI). |
