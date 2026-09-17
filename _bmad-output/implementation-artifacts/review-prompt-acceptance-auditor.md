# Acceptance Auditor Review Prompt
**Reviewer Role:** Acceptance Auditor (spec + diff validation)
**Story:** skillars-deferred-119-filestorage-deletion-scheduler-toctou-and-outbox-lock-consistency
**Date:** 2026-09-17

## Your Task

You are validating implementation against the spec. For each Acceptance Criterion, verify:
1. Is it fully implemented?
2. Does the code match the spec's intent?
3. Are there deviations or gaps?
4. Do the tests cover what the spec requires?

## Acceptance Criteria to Validate

### AC1: DeletionSchedulerService.processDeletions TOCTOU fix

**Spec Requirements:**
- `@SchedulerLock` added with `lockAtMostFor` sized from: batch size (10) × per-item S3 retry worst case (~10s) ≈ 100s → `lockAtMostFor="PT5M"`
- `lockAtLeastFor` derived from the 5-second cadence (NOT copy-pasted `PT2M`) → `lockAtLeastFor` must be < PT5S
- `markPhysicallyDeleted` changed from `void` to conditional `UPDATE ... WHERE id = ? AND physicalDeletedAt IS NULL` returning `int`
- **Order matters:** `markPhysicallyDeleted` called FIRST, only `save()` when affected-row count is 1
- New `DeletionSchedulerServiceTest` (unit test, mocking, not replacing `FileStorageDeletionIT`)
- Covers: happy path, race-skip (`save()` verified never called), exception continuation
- Reflection-based `@SchedulerLock` presence test
- Optional recommended: `FileStorageDeletionIT` concurrency test with `CountDownLatch`/`ExecutorService`

**Verify in diff:**
- [ ] `@SchedulerLock` present on `processDeletions()`?
- [ ] `lockAtMostFor` value correct (PT5M)?
- [ ] `lockAtLeastFor` value correct (PT2S, not PT2M)?
- [ ] Javadoc comment present with sizing arithmetic?
- [ ] `markPhysicallyDeleted` signature changed to return `int`?
- [ ] SQL query includes `AND physical_deleted_at IS NULL`?
- [ ] `@Transactional` removed from repository method?
- [ ] `processDeletions` calls `markPhysicallyDeleted` BEFORE `save()`?
- [ ] `save()` only called when `affectedRows == 1`?
- [ ] `DeletionSchedulerServiceTest` exists with 4+ tests?
- [ ] Test mocks `TransactionTemplate.execute()`?
- [ ] Test covers race-skip with `save()` never invoked?
- [ ] `FileStorageDeletionIT` still exists and has concurrency test?

### AC2: Scheduler-lock consistency

**Spec Requirements:**
- `@SchedulerLock` added to `OutboxPollerScheduler.pollAndProcess`
- Same sizing approach: batch (10) × per-item REPLICATE worst case (~20s) ≈ 200s → `lockAtMostFor="PT10M"`
- `lockAtLeastFor` derived from 5-second cadence (NOT `PT2M`) → `lockAtLeastFor` should be ~PT2S
- `OutboxService.sweep()` gets ONE-LINE derivation comment only (no value changes)
- Comment should cite `MAX_CHUNKS_PER_DRAIN` (200) × `CHUNK_SIZE` (25)
- Reflection-based `@SchedulerLock` presence test added to `OutboxPollerSchedulerTest`

**Verify in diff:**
- [ ] `@SchedulerLock` present on `pollAndProcess()`?
- [ ] `lockAtMostFor` value correct (PT10M)?
- [ ] `lockAtLeastFor` value correct (PT2S, not PT2M)?
- [ ] Javadoc comment present with sizing arithmetic?
- [ ] `OutboxService.sweep()` has exactly ONE comment line added?
- [ ] Comment cites `MAX_CHUNKS_PER_DRAIN` (200) and `CHUNK_SIZE` (25)?
- [ ] Lock values on `sweep()` UNCHANGED (PT10M / PT1M)?
- [ ] `OutboxPollerSchedulerTest` extended with lock-presence test?

### AC3: Ledger hygiene

**Spec Requirements:**
- Zero `deferred-work.md` bullets for touched classes at story creation
- Grep sweep confirms no new bullets added by story completion
- No ledger edit needed

**Verify:**
- [ ] No new bullets added to `deferred-work.md`?
- [ ] Grep command specified: `grep -n "DeletionSchedulerService|OutboxPollerScheduler|OutboxService|FileStorageObjectRepository|OutboxReplicationJobRepository" deferred-work.md` returns zero hits?

## Critical Implementation Assumptions (from spec)

1. **ShedLock mechanism**: Assumes ShedLock is already in the project and working for other schedulers
2. **Storage idempotency**: Assumes `S3StorageService.delete()` is idempotent
3. **No pre-launch data**: Assumes no production deploy has happened, so no duplicate `OutboxReplicationJob` cleanup needed
4. **Transaction isolation**: Assumes Spring Data JPA transactions are correctly scoped
5. **`markPhysicallyDeleted` call site count**: Spec claims exactly ONE call site in codebase (inside `DeletionSchedulerService`)

## Deviations to Flag

If any of these are found, note them as deviations:
- `@SchedulerLock` sizing constants copy-pasted from other schedulers instead of derived
- `markPhysicallyDeleted` order reversed (save before conditional update)
- Any changes to `OutboxService.sweep()` lock values
- Tests removed or weakened from `FileStorageDeletionIT`
- `DeletionSchedulerServiceTest` not present or incomplete

## Output Format

Provide findings as a Markdown list:

```
### AC1 Violations
- [ ] Finding: specific deviation from spec with code reference
  - Why it matters: consequence or contradiction

### AC2 Violations
- [ ] Finding: ...

### AC3 Violations
- [ ] Finding: ...

### Implementation Gaps (not violations but missing)
- [ ] Finding: ...

### Positive Notes (optional)
- Implementation goes beyond spec in a good way: ...
```

---
