# Story: Filestorage Deletion-Scheduler TOCTOU & Outbox Scheduler-Lock Consistency

**Story Key:** `skillars-deferred-119-filestorage-deletion-scheduler-toctou-and-outbox-lock-consistency`
**Epic:** Deferred Work
**Priority:** Medium (AC1 is a genuine claim-then-process bug whose consequence today is bounded to duplicate/orphan outbox rows, not data loss — see AC1's failure scenario for why it isn't Critical)
**Status:** ready-for-dev
**Created:** 2026-09-17

---

## Provenance & Scoping (read before starting)

Per the pattern `skillars-deferred-115` through `-118` established (ledger dry → fresh ad-hoc audit
applying the transaction-boundary/TOCTOU/scheduler-lock-parity lens to a not-yet-covered part of the
codebase), this story's research (2026-09-17) applied that identical lens to every `@Scheduled` class
in `platform.filestorage`, `platform.outbox`, `platform.notification.service` (the two cache/eval
classes, not `EmailRetryScheduler` — see below), `platform.messaging.MessagingEmitterRegistry`, and
`platform.config` — the modules `-115` through `-118` had not yet touched. `deferred-work.md` was
grepped for every class named below; zero open bullets reference any of them (this story's findings
are new, not mined from the ledger).

**One genuine bug found and independently re-verified against HEAD before this story was drafted:**
AC1. **Two low-risk consistency gaps found alongside it, bundled into AC2** per this project's own
established bundling convention (`skillars-deferred-117`'s "relaxed the bundling bar to include real
architectural hardening items previously left as 'recorded, not actionable'").

**Cleared, not picked up (confirmed safe or out of scope, so the next audit does not re-investigate):**
- `MessagingEmitterRegistry.sendHeartbeats` — in-memory, per-instance SSE emitter map; a distributed
  lock would be *wrong* here (each instance must heartbeat its own local emitters), not missing.
- `AlertEvaluationService.evaluate` — reads local Micrometer counters only, publishes local events; no
  DB read, no shared mutable state, no lock possible or needed.
- `AlertRuleCache.refresh` — idempotent read-only DB→cache refresh via `AtomicReference` swap; matches
  `ConfigService`'s already-accepted pattern.
- `ConfigService`'s scheduled refresh — its dual-refresh/stale-read behavior is already recorded in
  `deferred-work.md` as an accepted design tradeoff (confirmed still present, not touched by this story).
- `ConfigStartupAssertion` — confirmed **not** `@Scheduled` at all (`ApplicationListener<ApplicationReadyEvent>`,
  fires once at boot); out of this audit's scope by definition.
- `EmailRetryScheduler` — confirmed via git log that `@SchedulerLock` was added under this *exact* lens
  (transaction boundary / TOCTOU / scheduler-lock-parity, not just health-check concerns) in commit
  `4a3f218d` (`skillars-deferred-111`, PR #190) — already fixed, not a fresh candidate.
- `OutboxService.drain()` / `OutboxChunkProcessor` / `OutboxRowProcessor` — row-per-`REQUIRES_NEW`-
  transaction claim+process shape, already 3-layer-reviewed across `skillars-deferred-90`/`-91`; no new
  issue beyond AC2's documentation-only nit on `sweep()`'s existing lock.
- No entity touched by this audit (`FileStorageObject`, `OutboxReplicationJob`, `AlertRule`,
  `OutboxMessage`) has more than one `List`-typed `@OneToMany`/`@ManyToMany` — the N+1/`MultipleBagFetchException`
  checks do not apply to this surface.

**Owner decision needed before implementation:** none — both ACs are direct fixes with an established
sibling pattern already in this codebase to mirror.

---

## Acceptance Criteria

### AC1 — `DeletionSchedulerService.processDeletions` no longer relies on a `FOR UPDATE SKIP LOCKED` claim whose lock is released before any row is processed

**The bug, traced end to end:**

`FileStorageObjectRepository.findEligibleForPhysicalDeletion` (`FileStorageObjectRepository.java:27-30`)
is a native `SELECT ... FOR UPDATE SKIP LOCKED` query, annotated `@Transactional` **at the repository
interface method itself**. `DeletionSchedulerService.processDeletions()` (`DeletionSchedulerService.java:31-63`)
— the caller — is **not** `@Transactional` and carries **no `@SchedulerLock`**. Spring Data JPA opens a
brand-new transaction for that repository call since none is already open, and that transaction
**commits as soon as the query returns** — meaning the row locks `FOR UPDATE SKIP LOCKED` took are
released **before the `for (FileStorageObject fso : eligible)` loop even starts**. Nothing marks the
rows "claimed" in the interim (no status/claimed-by/claimed-at column on `FileStorageObject`), so the
`SKIP LOCKED` syntax provides zero protection to the processing that follows it. Contrast with the
sibling in the same module, `OutboxPollerScheduler`, which correctly wraps its own `FOR UPDATE SKIP
LOCKED` claim (`pollPending`) *and* the status flip (`markAsProcessing`) inside one
`transactionTemplate.execute(...)` block (`OutboxPollerScheduler.java:36-47`) — the lock is held exactly
until the claim becomes durable. `DeletionSchedulerService` is the only one of the two filestorage
pollers that gets this wrong, and it is also the only `@Scheduled` method in `platform.filestorage`
with **no test file at all** (confirmed by search — `DeletionSchedulerServiceTest` does not exist).

**Failure scenario:** Two ticks — either the same instance if one run takes longer than the 5-second
`fixedDelay` (`app.storage.poller.fixed-delay-ms`), or, once this deployment scales horizontally, two
instances (34 of the 36 total `@Scheduled` methods in this codebase already carry `@SchedulerLock` for
exactly this reason) — both call `findEligibleForPhysicalDeletion` within the same eligibility window.
Because each call's own transaction commits (releasing the lock) before either caller's loop body runs,
both selects can return overlapping rows — nothing has been written yet to exclude them. Both then
independently call `storageService.delete(fso.getKey())`: idempotent (confirmed via
`S3StorageService.delete` — an unconditional single `DeleteObject` call with no existence check; AWS S3
returns success deleting an already-missing key). Both then independently `outboxReplicationJobRepository.save(...)`
a new `OutboxReplicationJob` row (confirmed no unique constraint on `(storage_object_id, job_type)` in
`V138__baseline_schema.sql`) and call `markPhysicallyDeleted`, which is itself an **unconditional**
`UPDATE FileStorageObject f SET f.physicalDeletedAt = :ts WHERE f.id = :id` — no `AND physicalDeletedAt
IS NULL` re-check. Net effect: duplicate `OutboxReplicationJob` rows for the same object, each later
independently drained by `OutboxPollerScheduler` into a wasted (but idempotent) `backupStorageService.delete(key)`
call, permanent duplicate rows in `outbox_replication_jobs`, and doubled log/metric noise. Underlying
storage idempotency and the object being gone either way keep this out of Critical (no data
corruption/loss) — but the claim mechanism does not do what its `FOR UPDATE SKIP LOCKED` syntax implies.

**Independently re-verified against HEAD (2026-09-17, story creation):** read `DeletionSchedulerService.java`
and `FileStorageObjectRepository.java` directly (confirmed the repository-interface-level `@Transactional`
placement and `markPhysicallyDeleted`'s unconditional `UPDATE`), `OutboxPollerScheduler.java` (confirmed
the sibling does this correctly), `S3StorageService.delete` (confirmed unconditional, no existence
check), and `V138__baseline_schema.sql`'s `outbox_replication_jobs` table definition (confirmed no
unique constraint beyond the primary key). Confirmed via `grep` that `markPhysicallyDeleted` has exactly
one call site in the whole codebase (inside `DeletionSchedulerService` itself), so its signature is safe
to change.

**Fix:** two changes, both scoped to `DeletionSchedulerService` + `FileStorageObjectRepository` — do
both, as belt-and-suspenders (the lock is what actually closes the concurrent-invocation window; the
conditional write is an independent backstop against any future call path that bypasses the lock):

1. Add `@SchedulerLock` to `processDeletions()`. Size `lockAtMostFor` from the real worst case:
   `BlobstoreProperties.Poller.batchSize` (default 10) × the per-item S3 delete's own worst-case retry
   cost (`S3StorageService.delete`'s `@Retryable`: `maxAttemptsExpression=3`,
   `backoff-initial-ms=1000`, `multiplier=2.0` → up to ~3s of backoff alone across retries, plus actual
   call latency) + the DB write. **`lockAtLeastFor` must be derived from this scheduler's own 5-second
   `fixedDelay` cadence, NOT copied from the 5-minute-sibling `PT2M` convention** — a `PT2M` floor here
   would suppress roughly 24 of every 25 legitimate ticks. Show the arithmetic in the Dev Agent Record,
   mirroring `skillars-deferred-118` AC3's precedent of scheduler-specific, non-copy-pasted sizing (see
   that story's Dev Notes for the reasoning shape, and `RadarCompositeDlqProcessor`'s `PT30S`
   `lockAtLeastFor` — sized below its own 60-second cadence — as the closest existing precedent for a
   sub-`PT2M` value).
2. Change `FileStorageObjectRepository.markPhysicallyDeleted` from `void` to a conditional `UPDATE
   FileStorageObject f SET f.physicalDeletedAt = :ts WHERE f.id = :id AND f.physicalDeletedAt IS NULL`
   returning `int` (mirror `softDeleteByKey` in the same interface, `FileStorageObjectRepository.java:23-26`
   — same conditional-`WHERE`-clause shape, already returns `int`). In `DeletionSchedulerService`, only
   call `outboxReplicationJobRepository.save(...)` when the affected-row count is `1`; skip (no error,
   this is an expected race outcome under concurrent claims) when it is `0`.

**Verified by:**
- New `DeletionSchedulerServiceTest` (first-ever coverage for this class — mirror
  `OutboxPollerSchedulerTest`'s Mockito shape and its `transactionTemplate.execute(any())` /
  `TransactionCallback` stubbing pattern, `OutboxPollerSchedulerTest.java:78-88`): (a) a single eligible
  row is deleted from storage, replicated (`OutboxReplicationJob` saved), and marked
  physically-deleted — happy path; (b) `markPhysicallyDeleted` returning `0` affected rows (simulating a
  losing race) skips the `OutboxReplicationJob` save entirely; (c) `storageService.delete` throwing for
  one row does not stop the loop from processing the next row (existing catch-and-continue behavior at
  `DeletionSchedulerService.java:43-45` — must not regress).
- Reflection-based `@SchedulerLock` presence test on `processDeletions`, matching
  `skillars-deferred-118`'s established pattern for the schedulers it added locks to.
- `FileStorageObjectRepository`'s other query methods (`sumSizeBytesByOwnerId`, `findByKey`,
  `findByKeyAndDeletedAtIsNull`, `softDeleteByKey`, `findAllByOwnerIdAndDeletedAtIsNull`) are untouched.

---

### AC2 — Scheduler-lock consistency: `OutboxPollerScheduler` gets `@SchedulerLock`; `OutboxService.sweep`'s existing lock gets a derivation comment

**Finding 1:** `OutboxPollerScheduler.pollAndProcess` is, after AC1 fixes `DeletionSchedulerService`,
the **only** `@Scheduled` method left in the codebase with no `@SchedulerLock`. Unlike
`DeletionSchedulerService`, its claim is **already correctly scoped** — `pollPending` (`FOR UPDATE SKIP
LOCKED`) and `markAsProcessing` both run inside one `transactionTemplate.execute(...)` block
(`OutboxPollerScheduler.java:36-47`), and the subsequent per-item loop processes the same list already
returned by the claim rather than re-querying by status. This is a **defense-in-depth/consistency gap,
not a live correctness bug**: DB-level `FOR UPDATE SKIP LOCKED` + the atomic status flip already
prevents double-claiming across concurrent instances today. The fix is adding the lock for consistency
with the rest of the codebase's now-near-universal convention (34 of 36 `@Scheduled` methods after AC1),
sized the same way as AC1 — same 5-second `fixedDelay` cadence, so `lockAtLeastFor` must **not** be the
5-minute-sibling `PT2M` here either.

**Finding 2:** `OutboxService.sweep()`'s existing `@SchedulerLock(lockAtMostFor = "PT10M", lockAtLeastFor
= "PT1M")` (`OutboxService.java:119-120`) has no derivation comment tying it to
`MAX_CHUNKS_PER_DRAIN` (200, `OutboxService.java:46`) × the outbox's chunk-size arithmetic, unlike the
codebase's now-established convention of showing the sizing math inline (e.g.
`skillars-deferred-118` AC3, `EmailRetryScheduler`'s Javadoc). Per the check-#4 guidance's own
"note as mitigation, not a free pass" framing: correctness here does **not** depend on this lock at
all — `OutboxMessageRepository.claimNextDue`'s row-level `PESSIMISTIC_WRITE` + `SKIP LOCKED` is
documented (`OutboxService.java:96-107`'s own Javadoc) as the actual mechanism that lets the
`AFTER_COMMIT` drain and `sweep()` run concurrently without double-dispatching a handler. This is a pure
documentation/consistency fix — **no value or behavior change**.

**Fix:**
1. Add `@SchedulerLock` to `OutboxPollerScheduler.pollAndProcess`, same sizing approach as AC1 (batch
   size from `BlobstoreProperties.Poller.batchSize` × per-item worst case — here each item is either a
   full object stream-copy on `REPLICATE` or a delete on `DELETE`, so use the `REPLICATE` branch's cost
   as the worst case — with `lockAtLeastFor` derived from the same 5-second cadence, not copy-pasted).
2. Add a one-line derivation comment to `OutboxService.sweep()`'s existing `@SchedulerLock` annotation
   citing `MAX_CHUNKS_PER_DRAIN` and the per-chunk size it multiplies against — **no value change**.

**Verified by:**
- Extend existing `OutboxPollerSchedulerTest` with a reflection-based `@SchedulerLock` presence test,
  matching AC1's and `skillars-deferred-118`'s pattern.
- Confirm whether an `OutboxServiceTest` exists; if so, re-run it green (only a comment is added, no
  assertion should need to change). If none exists, no new test is required for this documentation-only
  change.

---

### AC3 — Ledger hygiene closeout

This story's findings are new (surfaced by this story's own creation-time audit), not mined from an
existing `deferred-work.md` bullet — confirmed by grep at story-creation time: zero hits for
`DeletionSchedulerService`, `OutboxPollerScheduler`, `OutboxService`, `FileStorageObjectRepository`, or
`OutboxReplicationJobRepository` anywhere in the file. **No ledger deletion needed.** Before marking this
story done, re-run the same grep sweep against HEAD to confirm no bullet was added in the interim that
overlaps this story's scope.

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: `DeletionSchedulerService` claim-then-process fix**
  - [ ] Add `@SchedulerLock` to `processDeletions()`, sized from real worst-case arithmetic (batch size
        × per-item S3-retry cost), `lockAtLeastFor` re-derived from the 5-second cadence — show the
        math in the Dev Agent Record
  - [ ] Change `FileStorageObjectRepository.markPhysicallyDeleted` to a conditional `UPDATE ... WHERE
        id = ? AND physicalDeletedAt IS NULL` returning `int`, mirroring `softDeleteByKey`
  - [ ] Update `DeletionSchedulerService.processDeletions` to only save the `OutboxReplicationJob` when
        the affected-row count is `1`
  - [ ] Add `DeletionSchedulerServiceTest` (new file): happy path, race-skip (0 affected rows), and
        per-item exception continuation

- [ ] **Task 2 — AC2: scheduler-lock consistency**
  - [ ] Add `@SchedulerLock` to `OutboxPollerScheduler.pollAndProcess`, sized from its own worst-case
        arithmetic (batch size × per-item `REPLICATE`-branch cost), `lockAtLeastFor` re-derived from
        its own 5-second cadence
  - [ ] Add a reflection-based `@SchedulerLock` presence test to `OutboxPollerSchedulerTest`
  - [ ] Add a one-line derivation comment to `OutboxService.sweep()`'s existing `@SchedulerLock`
        (`MAX_CHUNKS_PER_DRAIN` × chunk-size arithmetic) — no value change; re-run any existing
        `OutboxServiceTest` green if one exists

- [ ] **Task 3 — AC3: ledger closeout**
  - [ ] Re-run the grep sweep against HEAD for the five touched classes; confirm still zero open
        bullets before marking done

- [ ] **Task 4 — Final validation**
  - [ ] Run every touched module's targeted test suites together; confirm zero regressions
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only: the new `DeletionSchedulerServiceTest`, `OutboxPollerSchedulerTest`, and (if it
  exists) `OutboxServiceTest`.
- **AC1/AC2 — do not copy `PT2M`/`PT15M`-style sizing from the 5-minute or daily siblings.** Both
  `DeletionSchedulerService` and `OutboxPollerScheduler` run on a 5-second `fixedDelay`
  (`app.storage.poller.fixed-delay-ms`, default `5000`) — the tightest cadence of any scheduler in this
  codebase. A `lockAtLeastFor` sized for a 5-minute cadence would silently drop the vast majority of
  legitimate ticks. Derive each value from this scheduler's own cadence and worst-case runtime; look at
  `RadarCompositeDlqProcessor`'s `PT30S` (`skillars-deferred-118`, sized below its own 60-second cadence)
  as the closest existing precedent for a sub-minute value, not the 5-minute siblings' `PT2M`.
- **AC1 — the fix is two independent layers, do both.** The `@SchedulerLock` is what actually prevents
  the concurrent-invocation window from opening at all; the conditional `UPDATE` on
  `markPhysicallyDeleted` is a second, independent backstop (matches this codebase's established
  preference for defense-in-depth — see `skillars-deferred-118` AC2's `UserAdminService` fix, which
  similarly added a guard even though the *primary* fix elsewhere already narrowed the race window).
  Do not treat the lock as sufficient reason to skip the conditional-write change, or vice versa.
- **AC1 — `SchedulingConfig.java`'s class Javadoc (lines 16-20, 26-28) cites stale counts** ("11
  `@SchedulerLock` jobs", "30 `@Scheduled` methods", "16 hard-code their delay") from before
  `skillars-deferred-115` through `-119` added locks incrementally. Updating that comment is **not**
  part of this story's scope (it documents a test-suite-consolidation concern, not a bug), but if you
  touch the file for any reason, consider flagging the staleness rather than compounding it — optional,
  not required.
- **AC1 — `SchedulingConfig`'s Javadoc also confirms both `DeletionSchedulerService` and
  `OutboxPollerScheduler` already run at a real 5-second delay inside the consolidated integration test
  suite, with ShedLock deliberately left enabled during tests** (`@EnableSchedulerLock` stays on
  `AsyncConfig`, not gated by `app.scheduling.enabled`). If any integration test invokes either
  scheduler's method directly through the Spring proxy and depends on back-to-back invocations not being
  suppressed by a lock, check whether `BasePaymentIT.releaseSchedulerLock` (or an equivalent helper) is
  needed — search for existing IT coverage of these two classes before assuming none exists.
- **AC2 — `OutboxService.sweep()`'s fix is comment-only.** Do not change `lockAtMostFor="PT10M"` or
  `lockAtLeastFor="PT1M"` — the audit that produced this story explicitly found the *values* were not
  wrong, only undocumented. Changing them is out of scope.
- **Project structure:** two independent modules touched (`platform.filestorage`, `platform.outbox`) —
  no cross-AC coupling; AC1 and AC2 can be implemented and tested in either order.
- **Testing approach:** `DeletionSchedulerServiceTest` (AC1, new file, mirror
  `OutboxPollerSchedulerTest`'s Mockito + `TransactionTemplate`-stubbing shape exactly — do not mock
  `TransactionTemplate.execute` with a bare `any()` that ignores the callback; the existing sibling
  test's `thenAnswer(invocation -> { ... cb.doInTransaction(...) ...})` pattern is required so the real
  callback logic executes under test); `OutboxPollerSchedulerTest` (AC2, extend existing file).

### Project Structure Notes

- AC1 touches `platform.filestorage.service` (`DeletionSchedulerService`) and
  `platform.filestorage.repo` (`FileStorageObjectRepository`) — no new module, no migration (the
  `WHERE ... AND physicalDeletedAt IS NULL` clause is JPQL against the existing mapped field, not a
  schema change).
- AC2 touches `platform.filestorage.service` (`OutboxPollerScheduler`) and `platform.outbox.service`
  (`OutboxService`) — no new module, no migration, no new dependency (ShedLock is already a project
  dependency, used by every other `@SchedulerLock` site).

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java`
  (AC1 — whole file, 64 lines)
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObjectRepository.java`
  (AC1 — whole file; `softDeleteByKey` is the conditional-`UPDATE` shape to mirror for
  `markPhysicallyDeleted`)
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObject.java` (AC1 —
  confirm `physicalDeletedAt` field name/type for the new JPQL predicate)
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/service/S3StorageService.java:105-120`
  (AC1 — `delete`'s `@Retryable` config, confirms idempotency and the retry-cost sizing inputs)
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/config/BlobstoreProperties.java` (AC1 —
  `Poller.batchSize` default, AC1/AC2 sizing input)
- `src/test/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerSchedulerTest.java`
  (AC1/AC2 — the Mockito + `TransactionTemplate`-stubbing pattern to copy for the new
  `DeletionSchedulerServiceTest`, and to extend directly for AC2's lock-presence test)
- `src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java` (AC1
  reference / AC2 — whole file, the already-correct claim-then-process shape and the fix target)
- `src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java:40-131` (AC2 —
  `MAX_CHUNKS_PER_DRAIN`, existing `sweep()` `@SchedulerLock`, and the `claimNextDue` mechanism the
  Javadoc already documents as the real correctness backstop)
- `src/main/java/com/softropic/skillars/infrastructure/config/SchedulingConfig.java` (AC1/AC2 — confirms
  both target schedulers already run at a real 5-second delay under the consolidated IT suite, and that
  ShedLock stays enabled during tests)
- `_bmad-output/implementation-artifacts/skillars-deferred-118-booking-expiry-transaction-race-user-cleanup-toctou-and-scheduler-lock-parity.md`
  (AC1/AC2 — Dev Notes section, the worst-case `@SchedulerLock`-sizing arithmetic pattern to reuse, and
  the reflection-based lock-presence test convention)

---

## Verification Checklist

- [ ] AC1: `processDeletions` carries `@SchedulerLock` sized from real worst-case arithmetic (shown in
      Dev Agent Record), not a copy-pasted constant; `markPhysicallyDeleted` is a conditional `UPDATE`
      returning the affected-row count; `DeletionSchedulerService` only saves the `OutboxReplicationJob`
      when that count is `1`; new `DeletionSchedulerServiceTest` covers happy path, race-skip, and
      per-item exception continuation
- [ ] AC2: `OutboxPollerScheduler.pollAndProcess` carries `@SchedulerLock` sized the same way;
      `OutboxService.sweep()`'s existing lock values are unchanged, with a derivation comment added
- [ ] AC3: grep sweep re-confirmed zero open `deferred-work.md` bullets for the five touched classes
- [ ] No regressions in any touched module's existing test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — this story's findings are new (surfaced by
  this story's own creation-time audit), not mined from an existing ledger bullet; no bullet to cite
- `_bmad-output/implementation-artifacts/skillars-deferred-118-booking-expiry-transaction-race-user-cleanup-toctou-and-scheduler-lock-parity.md`
  — the most recent story in this audit series; its `@SchedulerLock`-sizing arithmetic pattern and
  reflection-based presence-test convention are what this story's ACs mirror
- `.claude/skills/txn-and-concurrency-audit/SKILL.md` and `references/checks.md` — the audit methodology
  this story's findings were produced by (checks #2 and #3 specifically)

---

## Change Log

- 2026-09-17: Story created via `/bmad-create-story`. `deferred-work.md`'s ledger re-confirmed exhausted
  of genuine one-off bugs for this scope (zero hits on grep for any touched class). Following the
  established pattern set by `skillars-deferred-115` through `-118`, dispatched a fresh ad-hoc audit
  applying the transaction-boundary/TOCTOU/scheduler-lock-parity lens to `platform.filestorage`,
  `platform.outbox`, `platform.notification.service`, `platform.messaging.MessagingEmitterRegistry`, and
  `platform.config` — the modules not yet covered by `-115` through `-118`. Found and independently
  re-verified against HEAD: AC1, a genuine bug in `DeletionSchedulerService.processDeletions` — its
  `FOR UPDATE SKIP LOCKED` claim (`findEligibleForPhysicalDeletion`, `@Transactional` at the repository
  method level, no caller-side transaction or `@SchedulerLock`) commits and releases its row locks
  before the per-row processing loop even starts, so two concurrent ticks can claim overlapping rows;
  bounded to duplicate/orphan `outbox_replication_jobs` rows and wasted idempotent re-deletes, not data
  loss, since storage deletes are idempotent and `markPhysicallyDeleted`'s lack of a re-check predicate
  was the second half of the gap. AC2, two low-risk consistency gaps bundled together per this project's
  established bundling convention: `OutboxPollerScheduler.pollAndProcess` (already-correct claim,
  missing `@SchedulerLock` for consistency with 34 of 36 sibling schedulers) and `OutboxService.sweep()`'s
  existing lock (correctly configured values, no derivation comment). No owner decision needed — both
  ACs are direct fixes with an established sibling pattern already in this codebase. Everything else
  audited (`MessagingEmitterRegistry`, `AlertEvaluationService`, `AlertRuleCache`, `ConfigService`,
  `ConfigStartupAssertion`, `EmailRetryScheduler`) confirmed clean or already fixed under a prior story —
  see Provenance & Scoping for the per-class reasoning. Branch: `story/deferred-119-filestorage-scheduler-fixes`
  (to be created), off `master` post-`skillars-deferred-118`-merge (PR #206) and post-Dependabot-batch-merge
  (PRs #199-#205).

---

## Dev Agent Record

### Agent Model Used

_To be filled in by `/bmad-dev-story`._

### Debug Log References

### Completion Notes List

### File List
