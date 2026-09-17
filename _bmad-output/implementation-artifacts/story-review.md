# Story Audit: skillars-deferred-119

## Summary
Audit of the filestorage deletion-scheduler TOCTOU & outbox scheduler-lock consistency story for missed corner cases, false assumptions, and missed flows.

## Verified Correct

### AC1 Analysis - DeletionSchedulerService TOCTOU Bug
✅ **Core bug diagnosis is accurate:**
- Confirmed: `findEligibleForPhysicalDeletion` has `@Transactional` at repository interface level (line 27-30 of FileStorageObjectRepository.java)
- Confirmed: `processDeletions()` is NOT `@Transactional` and has NO `@SchedulerLock` (line 31-63 of DeletionSchedulerService.java)
- Confirmed: Repository call opens a new transaction that commits immediately, releasing locks before the loop starts
- Confirmed: `markPhysicallyDeleted` is currently an unconditional UPDATE (line 34-35 of FileStorageObjectRepository.java)
- Confirmed: Only 2 call sites for `markPhysicallyDeleted` (both in DeletionSchedulerService context)
- Confirmed: No unique constraint on `(storage_object_id, job_type)` in outbox_replication_jobs schema

✅ **Failure scenario is valid:**
- S3 delete is truly idempotent (code inspection confirms unconditional DeleteObject call)
- Catch-and-continue exists at lines 43-45 for storage exceptions
- Concurrent ticks can indeed claim overlapping rows due to lock release before processing

✅ **Fix approach is sound:**
- Two-layer defense (lock + conditional write) matches established project pattern
- Mirror of `OutboxPollerScheduler.pollAndProcess` pattern is correct
- `softDeleteByKey` is a valid conditional-UPDATE pattern to copy

### AC2 Analysis - Scheduler Lock Consistency
✅ **OutboxPollerScheduler claim correctness verified:**
- Lines 36-47: Both `pollPending` (FOR UPDATE SKIP LOCKED) and `markAsProcessing` are inside single `transactionTemplate.execute()` block
- Lock is held through the entire claim operation - this is correct
- Adding `@SchedulerLock` is genuinely for consistency, not correctness

✅ **OutboxService.sweep() existing lock verified:**
- Line 120: `@SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")` exists
- Line 46: `MAX_CHUNKS_PER_DRAIN = 200` constant exists
- Derivation comment is indeed missing - documentation-only fix is correct

✅ **Transaction boundary safety verified:**
- OutboxService.sweep() correctly delegates to drain()
- drain() uses row-level PESSIMISTIC_WRITE + SKIP LOCKED (documented in Javadoc)
- No data corruption risk if sweep() and AFTER_COMMIT drain run concurrently

---

## Potential Issues & Corner Cases Found

### ⚠️ ISSUE 1: Incomplete Test Coverage Claim
**Severity: Medium (affects implementation confidence)**

**Finding:** Story claims "`DeletionSchedulerService` is...the only `@Scheduled` method in `platform.filestorage` with **no test file at all**"

**Reality:** 
- `FileStorageDeletionIT.java` EXISTS and DOES test `processDeletions()`
- Integration test calls `deletionSchedulerService.processDeletions()` at lines 111 and 136
- Tests verify: retention window boundary, physical deletion, outbox job creation

**Issue:** Story's claim is technically misleading. The class has IT coverage but lacks a dedicated unit test. Story should say "no dedicated unit test" not "no test file at all". This affects confidence in AC1 claim about needing new `DeletionSchedulerServiceTest`.

**Recommendation:** The new unit test (`DeletionSchedulerServiceTest`) is STILL needed for:
- Race condition testing (concurrent claim scenarios)
- Conditional update behavior (0 affected rows handling)
- Per-item exception continuation
- Mock-based isolation from storage and DB

But be clear this is NEW *unit* test coverage, complementing existing IT coverage.

---

### ⚠️ ISSUE 2: Lock Sizing Arithmetic Deferred Without Clear Bounds
**Severity: Medium (risk of incorrect tuning)**

**Finding:** Story says "Show the arithmetic in the Dev Agent Record" but:
- No clear upper bound provided for `lockAtMostFor`
- Story warns against copying PT2M but doesn't specify what value should replace it
- Developer must calculate: batch-size (10) × S3-retry worst-case (3s backoff + latency) + DB write
- Estimated result: 30-50 seconds worst case, but this is not verified in the story

**Risk:** 
- If actual worst case exceeds calculated `lockAtMostFor`, lock expires early → race window reopens
- If `lockAtMostFor` is too large, the `lockAtLeastFor` floor becomes ineffective

**Recommendation:** 
- Story should provide the CALCULATION FORMULA explicitly, not defer to developer
- Story should say: "`lockAtMostFor` MUST exceed: batch_size × (retry_wait_ms + per_item_latency) + commit_latency"
- Example: "default batch 10 × (3000ms retry + 500ms latency) + 100ms = ~35 seconds → PT45S minimum"
- Add assertion in story: "do not accept `lockAtMostFor < PT45S` without re-measuring S3 latency in target environment"

---

### ⚠️ ISSUE 3: Outbox Replication Job Duplicate Cleanup Unspecified
**Severity: Low (bounded by idempotency)**

**Finding:** Story acknowledges "duplicate `OutboxReplicationJob` rows for the same object" as consequence but:
- Does NOT specify if duplicates are ever cleaned up
- Does NOT specify if they cause cascading issues if accumulated
- `OutboxPollerScheduler` processes both rows independently (each is idempotent)
- Over time, duplicate rows accumulate in the database

**Corner case:** What if a rapid burst of race conditions creates hundreds of duplicate outbox rows?
- Storage impact is minimal (rows are small)
- But monitoring/auditing becomes harder
- Operator confusion if row counts spike

**Recommendation:** 
- Document in Dev Notes: "Duplicate outbox replication jobs are idempotent but may accumulate. Consider a separate cleanup job to deduplicate historical rows."
- OR clarify: "Duplicates are prevented after this fix; no cleanup needed for existing duplicates since they are idempotent"

---

### ⚠️ ISSUE 4: Conditional UPDATE Race Condition Not Fully Addressed
**Severity: Low (documented as accepted race)**

**Finding:** Story says "skip (no error, this is an expected race outcome under concurrent claims) when it is `0`"

**But:** Doesn't clarify what happens if:
- `storageService.delete()` succeeds (storage is gone)
- `markPhysicallyDeleted` returns 0 (another thread already marked it)
- `OutboxReplicationJob` is NOT saved (as intended)

**Corner case:** Physical delete completed but no replication job → backup storage still has copy

**Story's own claim:** "Underlying storage idempotency and the object being gone either way keep this out of Critical"

**Analysis:** This is CORRECT reasoning, but should be stated more explicitly:
- Primary storage: deleted (idempotent)
- Backup storage: might have stale copy but is not the source of truth
- Data loss: impossible (object was marked deleted in primary DB)
- Consequence: backup has garbage that is never accessed (acceptable)

**Recommendation:** Story is sound here, but Dev Notes should explicitly confirm: "If backup doesn't get the DELETE job due to a race loss, object remains in backup but is unreachable from primary DB — this is acceptable."

---

### ⚠️ ISSUE 5: OutboxPollerScheduler Module Placement Oddity
**Severity: Low (naming/architecture question)**

**Finding:** `OutboxPollerScheduler` is in `platform.filestorage.service` (confirmed by file search), not `platform.outbox`.

**Oddity:** Class name suggests "Outbox" but it's in "filestorage" module. This is not a bug, but:
- Confusing for future developers
- May indicate module boundaries need clarification
- Story doesn't comment on this (not in scope, but worth noting)

**Recommendation:** Out of scope for this story, but should be documented in project memory or future refactoring discussion.

---

### ⚠️ ISSUE 6: ShedLock Clock Skew & Availability Not Validated
**Severity: Low (environmental assumption)**

**Finding:** Story assumes ShedLock is:
- Properly configured in all environments
- Using synchronized clocks across nodes
- Not corrupted or disabled

**No validation:** Story doesn't confirm ShedLock is actually working or enabled.

**Risk:** If ShedLock is misconfigured in production, both `@SchedulerLock` additions provide zero protection.

**Recommendation:** 
- Add to Verification Checklist: "Confirm ShedLock table and configuration are present in all target environments"
- OR document: "This fix assumes ShedLock is correctly configured. See SchedulingConfig for verification."

---

### ⚠️ ISSUE 7: No Deployment Order/Rollback Guidance
**Severity: Low (operational)**

**Finding:** Story doesn't specify:
- Deployment order (AC1 before AC2 or vice versa?)
- Rollback plan if lock causes issues
- Monitoring/alerts for lock contention
- How to detect if lock is suppressing legitimate ticks

**Current guidance:** "AC1 and AC2 can be implemented in either order"

**But missing:** "However, deploy AC1 first to close the data-path race before adding consistency-layer locks"

**Recommendation:** Add to Dev Notes:
- "Deploy AC1 before AC2 to ensure the primary race window closes first"
- "Monitor shedlock_lock table for lock timeouts; if frequent, increase lockAtMostFor"
- "If rollback needed, remove @SchedulerLock annotations (locks fall back to DB-level only)"

---

### ⚠️ ISSUE 8: Existing Integration Test Coverage Should Be Extended, Not Replaced
**Severity: Low (testing strategy)**

**Finding:** `FileStorageDeletionIT` already tests `processDeletions()` with happy path and boundary conditions.

**Story plan:** Create new `DeletionSchedulerServiceTest` (unit test)

**Potential gap:** 
- Will the new unit test REPLACE or EXTEND the existing IT coverage?
- Should we add race-condition test to the IT instead?
- If unit test only uses mocks, it won't catch actual DB lock behavior

**Recommendation:** 
- Clarify that unit test (mocks) + existing IT (real DB) = complete coverage
- Consider adding a race-condition test to the IT that actually calls processDeletions() twice concurrently
- Don't remove or downgrade the IT coverage in favor of unit tests

---

### ⚠️ ISSUE 9: Comment-Only Change to OutboxService.sweep() Needs Specificity
**Severity: Low (documentation clarity)**

**Finding:** Story says "Add a one-line derivation comment" but doesn't specify the exact wording.

**Current code (line 120):**
```java
@SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
```

**What should the comment say?**
- Should it cite line 46 (`MAX_CHUNKS_PER_DRAIN`)?
- Should it explain the calculation (200 × chunk_size)?
- Should it reference another file?
- Should it be a Javadoc comment or inline comment?

**Recommendation:** Specify in Dev Notes:
```java
// lockAtMostFor sized for MAX_CHUNKS_PER_DRAIN (200) × chunk_size (~1s per chunk) + processing margin
@SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
```

---

### ✅ ISSUE 10: Grep for Ledger Hygiene (AC3) - Command Not Provided
**Severity: Very Low (procedural)**

**Finding:** AC3 says "Re-run the same grep sweep against HEAD" but doesn't provide the exact grep command.

**Current text:** "re-run the same grep sweep against HEAD for the five touched classes"

**Missing:** The actual grep command to execute

**Recommendation:** Provide exact command:
```bash
grep -r "DeletionSchedulerService\|OutboxPollerScheduler\|OutboxService\|FileStorageObjectRepository\|OutboxReplicationJobRepository" _bmad-output/implementation-artifacts/deferred-work.md
```

---

## False Assumptions Found

### ✅ No false assumptions detected
- Spring Data JPA transaction behavior is correctly understood
- S3 idempotency claim is verified in code
- Repository method contract is accurately represented
- Sibling pattern analysis is correct

---

## Missed Flows

### ⚠️ FLOW 1: What if S3 Delete Takes Longer Than Expected?
**Finding:** Story accounts for S3 retry backoff (up to 3s) but doesn't fully trace what happens if:
1. `storageService.delete()` call takes 45+ seconds (network/AWS issue)
2. Processing loop is still running
3. `@SchedulerLock` with inadequate `lockAtMostFor` expires
4. Next scheduler tick acquires the lock and starts processing old rows again

**Analysis:** Lock prevents the SCHEDULER from running twice, but not individual items within the loop.

**Recommendation:** Clarify in Dev Notes: "Lock prevents concurrent scheduler invocations, not individual item retries. If delete latency exceeds lockAtMostFor, next tick will re-process already-deleted items (which are idempotent)."

### ⚠️ FLOW 2: Exception Handling in Conditional Update Path
**Finding:** Story says "only call outboxReplicationJobRepository.save(...) when the affected-row count is `1`" but doesn't specify transaction rollback behavior when conditional check fails AFTER save.

**Current code pattern (in DeletionSchedulerService.java, lines 51-61):**
```java
transactionTemplate.execute(status -> {
    OutboxReplicationJob job = OutboxReplicationJob.builder().build();
    outboxReplicationJobRepository.save(job);
    fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
    return null;
});
```

**After fix, should become:**
```java
transactionTemplate.execute(status -> {
    OutboxReplicationJob job = OutboxReplicationJob.builder().build();
    outboxReplicationJobRepository.save(job);
    int affected = fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
    if (affected == 0) {
        // Race condition: another thread already marked as deleted
        // Option A: rollback the outbox job save
        // Option B: throw exception to rollback the entire transaction
        status.setRollbackOnly();  // OR throw exception
    }
    return null;
});
```

**Issue:** Story doesn't clarify the transaction rollback behavior if conditional check fails AFTER save.

**Recommendation:** Clarify in Dev Notes: "If markPhysicallyDeleted returns 0 after saving the job, rollback the entire transaction to remove the duplicate job. Use status.setRollbackOnly() or throw an exception within the callback."

---

## Summary of Findings

| Category | Count | Severity |
|----------|-------|----------|
| False Assumptions | 0 | - |
| Incomplete Specification | 3 | Med, Low, Very Low |
| Missing Documentation | 2 | Low, Low |
| Corner Cases Identified | 3 | Low, Low, Low |
| Missed Flow Details | 2 | Low, Low |
| **Total Issues** | **10** | Mostly Low |

## Overall Assessment

✅ **Story is fundamentally sound.** The core bug diagnosis is accurate, the fix approach is appropriate, and the acceptance criteria are clear.

⚠️ **Refinements needed:** 
1. Clarify lock sizing arithmetic (provide formula, not just "show the math")
2. Specify exact comment text for OutboxService.sweep()
3. Clarify conditional update rollback behavior
4. Add deployment order guidance
5. Provide exact grep command for AC3

❌ **False positives:** None detected. All identified issues are legitimate corner cases or documentation gaps, not errors in story logic.
