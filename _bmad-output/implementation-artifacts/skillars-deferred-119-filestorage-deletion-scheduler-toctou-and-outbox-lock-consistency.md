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
pollers that gets this wrong. **Correction from an earlier draft of this story:** `processDeletions()`
is not untested — `FileStorageDeletionIT` (`FileStorageDeletionIT.java:96-147`) already exercises it
end-to-end against a real DB + storage backend (retention-window boundary, physical deletion, and
outbox-job creation), so the dev agent is not implementing this class's very first test. What is
genuinely missing is (a) a dedicated **unit** test (mock-isolated, fast, able to exercise the
`markPhysicallyDeleted`-returns-`0` branch this AC adds, which an IT can't easily force), and (b) any
coverage at all — unit or IT — of the **race** this AC fixes. See this AC's "Verified by" section below
for how the two are meant to fit together.

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
   sub-`PT2M` value). The `~3s` retry-backoff figure above is illustrative, derived from the
   `@Retryable` config alone — it does not include actual S3 round-trip latency, which this story does
   not measure. If the target environment's observed S3 delete latency is available (logs, metrics,
   local testing against the real provider), use that instead of guessing; if not, size generously
   above the illustrative figure rather than treating it as a tight bound — an oversized
   `lockAtMostFor` only delays detecting a truly stuck scheduler, while an undersized one reopens the
   exact race this AC closes.
2. Change `FileStorageObjectRepository.markPhysicallyDeleted` from `void` to a conditional `UPDATE
   FileStorageObject f SET f.physicalDeletedAt = :ts WHERE f.id = :id AND f.physicalDeletedAt IS NULL`
   returning `int` (mirror `softDeleteByKey` in the same interface, `FileStorageObjectRepository.java:23-26`
   — same conditional-`WHERE`-clause shape, already returns `int`). **Order matters — call
   `markPhysicallyDeleted` FIRST and inspect its return value BEFORE calling
   `outboxReplicationJobRepository.save(...)`, never the reverse.** Only call `save(...)` when the
   affected-row count is `1`; skip it entirely (no error, no rollback needed — this is an expected race
   outcome under concurrent claims) when the count is `0`. Doing the check first means `save()` is never
   called speculatively, so there is no scenario where an already-persisted outbox job needs to be undone
   — do not add `status.setRollbackOnly()` or a thrown exception for this case; that machinery is only
   needed if you get the ordering backwards.

**Verified by:**
- New `DeletionSchedulerServiceTest` — a **unit** test (mock-isolated, new file, mirror
  `OutboxPollerSchedulerTest`'s Mockito shape and its `transactionTemplate.execute(any())` /
  `TransactionCallback` stubbing pattern, `OutboxPollerSchedulerTest.java:78-88`), **complementing**
  `FileStorageDeletionIT`'s existing real-DB/real-storage coverage, not replacing it. Do not remove or
  weaken any `FileStorageDeletionIT` test. Cover: (a) a single eligible row is deleted from storage,
  replicated (`OutboxReplicationJob` saved), and marked physically-deleted — happy path; (b)
  `markPhysicallyDeleted` returning `0` affected rows (simulating a losing race) skips the
  `OutboxReplicationJob` save entirely, and `save()` is verified as never invoked in that case (this is
  the one branch `FileStorageDeletionIT` cannot easily force, since it needs a real second writer to hit
  the `0`-rows case) — this is also why item 2 of the Fix above requires `markPhysicallyDeleted` to run
  *before* `save()`, not after; (c) `storageService.delete` throwing for one row does not stop the loop
  from processing the next row (existing catch-and-continue behavior at `DeletionSchedulerService.java:43-45`
  — must not regress).
- **Recommended, not required:** extend `FileStorageDeletionIT` with a real concurrent-invocation test —
  two threads racing `deletionSchedulerService.processDeletions()` against the same eligible row via a
  `CountDownLatch` + `ExecutorService`, mirroring `ReliabilityStrikeConcurrencyIT`'s established pattern
  in this codebase (`ReliabilityStrikeConcurrencyIT.java` — same latch-release-both-at-once shape). This
  race's window is comparatively wide (it spans a full per-row S3 delete + DB write, not a single
  lock-then-insert), so unlike `PaymentPendingSweeper`'s previously-documented microsecond-window case
  (`deferred-work.md`, under "code review of skillars-uat-3-payment-capture-integrity-and-backup-retention",
  note D11 — a real IT proved unable to reliably exercise it), this one may actually be reproducible with
  real threads against the real Testcontainers Postgres `FileStorageDeletionIT` already uses. If it
  proves flaky after a genuine attempt, fall back to documenting the reasoning inline (per that same D11
  precedent) rather than landing a flaky test — do not spend excessive time forcing it.
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
- `OutboxServiceTest` (confirmed to exist — `src/test/java/.../platform/outbox/service/OutboxServiceTest.java`)
  re-run green; only a comment is added to `sweep()`'s existing annotation, no assertion should need to
  change.

---

### AC3 — Ledger hygiene closeout

This story's findings are new (surfaced by this story's own creation-time audit), not mined from an
existing `deferred-work.md` bullet — confirmed by grep at story-creation time: zero hits for
`DeletionSchedulerService`, `OutboxPollerScheduler`, `OutboxService`, `FileStorageObjectRepository`, or
`OutboxReplicationJobRepository` anywhere in the file. **No ledger deletion needed.** Before marking this
story done, re-run the same grep sweep against HEAD to confirm no bullet was added in the interim that
overlaps this story's scope:
```bash
grep -n "DeletionSchedulerService\|OutboxPollerScheduler\|OutboxService\|FileStorageObjectRepository\|OutboxReplicationJobRepository" _bmad-output/implementation-artifacts/deferred-work.md
```

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: `DeletionSchedulerService` claim-then-process fix**
  - [ ] Add `@SchedulerLock` to `processDeletions()`, sized from real worst-case arithmetic (batch size
        × per-item S3-retry cost, using measured latency if available), `lockAtLeastFor` re-derived
        from the 5-second cadence — show the math in the Dev Agent Record
  - [ ] Change `FileStorageObjectRepository.markPhysicallyDeleted` to a conditional `UPDATE ... WHERE
        id = ? AND physicalDeletedAt IS NULL` returning `int`, mirroring `softDeleteByKey`
  - [ ] Update `DeletionSchedulerService.processDeletions` to call `markPhysicallyDeleted` FIRST and
        only save the `OutboxReplicationJob` when its returned affected-row count is `1` (order matters
        — see AC1's Fix section)
  - [ ] Add `DeletionSchedulerServiceTest` (new **unit** test file, complementing — not replacing —
        `FileStorageDeletionIT`'s existing coverage): happy path, race-skip (`0` affected rows, `save()`
        verified never called), and per-item exception continuation
  - [ ] Attempt the recommended (not required) concurrency test extending `FileStorageDeletionIT`,
        mirroring `ReliabilityStrikeConcurrencyIT`'s `CountDownLatch`/`ExecutorService` pattern; fall
        back to documenting the reasoning inline if it proves flaky

- [ ] **Task 2 — AC2: scheduler-lock consistency**
  - [ ] Add `@SchedulerLock` to `OutboxPollerScheduler.pollAndProcess`, sized from its own worst-case
        arithmetic (batch size × per-item `REPLICATE`-branch cost), `lockAtLeastFor` re-derived from
        its own 5-second cadence
  - [ ] Add a reflection-based `@SchedulerLock` presence test to `OutboxPollerSchedulerTest`
  - [ ] Add the derivation comment to `OutboxService.sweep()`'s existing `@SchedulerLock` (suggested
        wording in Dev Notes) — no value change; re-run `OutboxServiceTest` green

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
  `AsyncConfig`, not gated by `app.scheduling.enabled`), and that a per-test reset already backdates
  every `main.shedlock` row before each test method. `FileStorageDeletionIT` (`BaseStorageIT` →
  `AbstractIntegrationTest`) already has `releaseSchedulerLock(String lockName)` available by inheritance
  (`AbstractIntegrationTest.java:141` — not `BasePaymentIT`-specific, despite that being the module
  where it's most visibly used) if you need it. Given the automatic per-test reset, `FileStorageDeletionIT`'s
  three *existing* tests (each calls `processDeletions()` exactly once per method) should keep passing
  unmodified once `@SchedulerLock` is added — no call to `releaseSchedulerLock` should be needed for
  them. The recommended new concurrency test is different: it deliberately wants the lock to stay held
  between its two racing calls, so it should **not** call `releaseSchedulerLock` either — that helper is
  only relevant if some *other* new test needs two sequential (not concurrent) invocations within one
  method.
- **AC1 — duplicate `OutboxReplicationJob` rows are a going-forward concern only, not a cleanup task.**
  No production deploy has ever happened (`docs/deployment/runbook.md`,
  `docs/deployment/migration-rebaseline.md:137` — the same fact `skillars-deferred-114`/`-117` cite for
  `main.pending_blob_deletions`), so there is no pre-existing accumulated duplicate data in any real
  environment to clean up. After this AC's fix (lock + conditional write), the race that creates
  duplicates cannot occur going forward. Do not add a deduplication job or migration for this — it would
  be solving a problem that does not exist.
- **AC1 — backup-storage semantics under the skip branch, stated explicitly:** when `markPhysicallyDeleted`
  returns `0` and the `OutboxReplicationJob` save is skipped, the object is already gone from *primary*
  storage (the earlier `storageService.delete()` call, unconditional and idempotent) — the skip only
  means *this* invocation does not enqueue a *backup*-storage delete. That is correct, not a gap: the
  invocation that actually won the race (affected-row count `1`) already enqueued its own
  `OutboxReplicationJob` for the same key, which `OutboxPollerScheduler` will drain into
  `backupStorageService.delete(key)` regardless of which invocation's job it was. No key is ever left
  undeleted in backup storage because of this skip.
- **AC1 — what happens if `lockAtMostFor` is undersized and a run genuinely exceeds it:** the lock's
  ceiling exists to bound how long a truly stuck/dead scheduler instance can block every other instance,
  not to guarantee no reprocessing ever happens. If a real run takes longer than `lockAtMostFor`, the
  next tick can acquire the lock and re-select rows the still-running prior tick hasn't finished with
  yet. This is safe, not a new bug: `storageService.delete()` is idempotent, and AC1's conditional
  `markPhysicallyDeleted` means at most one of the two overlapping runs' database writes actually lands
  — the loser's `save()` never happens. Size `lockAtMostFor` generously (per the note above) to make
  this rare, not to make it provably impossible; the conditional write is what makes it safe even when it
  does happen.
- **AC2 — `OutboxService.sweep()`'s fix is comment-only.** Do not change `lockAtMostFor="PT10M"` or
  `lockAtLeastFor="PT1M"` — the audit that produced this story explicitly found the *values* were not
  wrong, only undocumented. Changing them is out of scope. Suggested wording (verified against the real
  constants — do not reuse an unverified per-chunk timing guess):
  ```java
  // lockAtMostFor sized well above MAX_CHUNKS_PER_DRAIN (200) x OutboxChunkProcessor.CHUNK_SIZE (25)
  // = 5000 row-attempts worst case; correctness does not depend on this lock (see claimNextDue's
  // PESSIMISTIC_WRITE + SKIP LOCKED below), so PT10M's margin needs no tighter derivation than this.
  @SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
  ```
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
- `src/test/java/com/softropic/skillars/platform/filestorage/service/FileStorageDeletionIT.java` (AC1 —
  whole file; this is the **existing** IT coverage of `processDeletions()` that the new unit test
  complements, not replaces — read it before writing `DeletionSchedulerServiceTest` so the two don't
  duplicate each other's cases, and before extending it with the recommended concurrency test)
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java`
  (AC1 — only if attempting the recommended concurrency test; the `CountDownLatch`/`ExecutorService`
  dual-thread pattern to mirror)
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
      returning the affected-row count, called BEFORE the conditional `save()`; `DeletionSchedulerService`
      only saves the `OutboxReplicationJob` when that count is `1`; new `DeletionSchedulerServiceTest`
      (unit) covers happy path, race-skip (`save()` verified never called), and per-item exception
      continuation; existing `FileStorageDeletionIT` still passes unmodified
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
- 2026-09-17: Story revised post-review (`story-review.md`, 10 findings, reviewed for false positives
  before applying). One genuine factual error corrected: AC1's original draft claimed
  `DeletionSchedulerService` had "no test file at all" — false; `FileStorageDeletionIT` already exercises
  `processDeletions()` end-to-end (confirmed by direct read, not just the review's grep). AC1 revised to
  correctly scope the new `DeletionSchedulerServiceTest` as a complementary **unit** test (covering what
  the existing IT can't easily force — the race-skip branch), not first-ever coverage; a recommended
  (not required) concurrency test extending `FileStorageDeletionIT` was added, mirroring
  `ReliabilityStrikeConcurrencyIT`'s established latch pattern. Also fixed a real ordering ambiguity the
  review's "Flow 2" surfaced: the Fix section now states explicitly that `markPhysicallyDeleted` must be
  called *before* the conditional `save()`, which eliminates any need for rollback-after-save logic
  entirely (simpler than the review's own suggested fix, which was to add `status.setRollbackOnly()`
  after a speculative save). Added: a measured-vs-illustrative-latency caveat on the lock-sizing
  arithmetic; an explicit "no cleanup job needed" note (no production deploy has ever happened, so no
  duplicate data exists to clean up — the fix prevents new duplicates outright); explicit backup-storage
  and lock-exceeded-duration safety reasoning; verified suggested wording for `OutboxService.sweep()`'s
  derivation comment against the real `CHUNK_SIZE=25`/`MAX_CHUNKS_PER_DRAIN=200` constants rather than
  the review's unverified per-chunk timing guess; the exact `AC3` grep command; and a corrected,
  fact-checked note on `releaseSchedulerLock`'s actual location (`AbstractIntegrationTest`, inherited by
  `FileStorageDeletionIT` via `BaseStorageIT`, not `BasePaymentIT`-specific as the story's first draft
  implied). Declined from the review, with reasoning: prescribing an exact `lockAtMostFor` formula/value
  in the story (would contradict this series' established convention of leaving worst-case arithmetic to
  the Dev Agent Record); a separate outbox-duplicate cleanup job (no accumulated data exists pre-launch);
  generic ShedLock-availability/clock-skew caveats (not applied to any of the four prior stories in this
  series, which add locks under the same assumption ShedLock already works); and deployment-order/rollback
  guidance between AC1 and AC2 (both land in one squash-merged deploy together in this project's actual
  release model — there is no independent deployment order to sequence).

---

## Dev Agent Record

### Agent Model Used

_To be filled in by `/bmad-dev-story`._

### Debug Log References

### Completion Notes List

### File List
