# skillars-deferred-100: Concurrency, Reconciliation & Lifecycle Hardening

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** high
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
  - The count read, the threshold comparison, and the status write **must all sit under the lock**
    (that is the serialization point). Only the strike `INSERT` is flexible: keep it before the lock
    (it always happens regardless of outcome) or move it inside — dev's call, document which.
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
    2. Drain any **residual** `pending_blob_deletion` rows so nothing is lost: either a one-shot
       `ApplicationRunner` that re-enqueues them through `OutboxService`, **or** keep
       `PendingBlobDeletionChunkProcessor`'s scheduled drain running for this one release. Verify the
       table is empty in every environment first — if it always is, the one-shot is optional. Record
       which path was taken.
    3. Delete `PendingBlobDeletionService`, `BlobDeletionsEnqueuedEvent`, and (if path 2a was taken)
       `PendingBlobDeletionChunkProcessor`. **Keep `PendingBlobDeletion` (entity) +
       `PendingBlobDeletionRepository`** so the one-shot / residual drain can still read the table.
    4. The `DROP TABLE IF EXISTS pending_blob_deletion` + the `PendingBlobDeletion`/repo deletion are
       a **follow-up for a later release** (AC7 adds the ledger line), once this release is confirmed
       deployed and the table is provably empty.
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

- [ ] **AC1** — strike race
  - [ ] `ReliabilityStrikeService.issue()`: `findByIdForUpdate` + `PessimisticLockRetryer`, count/threshold under the lock
  - [ ] concurrent-`issue()` IT (one event), mutation check, happy-path unit unchanged
- [ ] **AC2** — orphaned provider asset
  - [ ] durable pre-create record (table or `platform.outbox`) + ack on `Video` persist
  - [ ] sweeper (extend `ReconciliationWorkerScheduler` or sibling) → `deleteAsset` + `ORPHANED_ASSET` incident, HTTP outside tx
  - [ ] rollback IT (orphan purged), normal-upload IT (untouched), committed-`Video` IT (never swept)
- [ ] **AC3** — native `@Modifying` / `@Version` audit
  - [ ] produce the audit list (table, statement, entity, verdict) in Dev Agent Record
  - [ ] `version = version + 1` where required; per-query Javadoc where safe
  - [ ] `NativeModifyingVersionAuditTest` source/reflection guard
- [ ] **AC4** — batch-status listener boundary
  - [ ] `@Transactional(REQUIRES_NEW, readOnly = true)` on the listener (or move the lookup into the service)
  - [ ] IT: recompute with no transactionless lookup; `batchId == null` no-op
  - [ ] delete stale `skillars-3-9` W1 (record the `deferred-69 AC6` verification)
- [ ] **AC5** — `PROCESSING→READY` removal
  - [ ] drop `READY` from `PROCESSING` in `VALID_TRANSITIONS`
  - [ ] `reconcileToReady(id, reason)` path (no bypass counter/WARN) for `ReconciliationWorkerScheduler` + `AdminVideoService`
  - [ ] belt-and-suspenders ERROR + counter + throw in the plain method
  - [ ] update `VideoLifecycleServiceTest:121-125` + video ITs
- [ ] **AC6** — blob-deletion outbox consolidation
  - [ ] `OutboxMessageHandler` for blob-key delete; swap `enqueue` call sites to `OutboxService.enqueue`
  - [ ] port bounded-drain safety stop / attempts-ordering into the generic processor if missing
  - [ ] drain residual `pending_blob_deletion` rows (one-shot re-enqueue, or keep the bespoke drain one release) — verify empty first
  - [ ] delete `PendingBlobDeletionService` + `BlobDeletionsEnqueuedEvent` (+ `ChunkProcessor` if one-shot); **keep** `PendingBlobDeletion` entity + repo for the drain
  - [ ] **no** `DROP TABLE` this release — AC7 adds the follow-up ledger line
  - [ ] retarget `PendingBlobDeletion*IT`; add a residual-row IT
- [ ] **AC7** — ledger hygiene
  - [ ] delete the closed + stale bullets; annotate the AC6 ones; add the dated `deferred-94`-health note

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

### Debug Log References

### Completion Notes List

### File List
