# Edge Case Hunter Review Prompt
**Reviewer Role:** Edge Case Hunter (with project read access)
**Story:** skillars-deferred-119
**Date:** 2026-09-17

## Your Task

You are a path tracer. For each changed line in the diff, exhaustively enumerate every possible execution path, input state, and boundary condition. Identify conditions that are NOT explicitly guarded in the code.

List ONLY unhandled paths — for each one, specify:
- Where it occurs (file:line or hunk)
- What condition triggers it
- What guard is missing (or code sketch to add)
- What could go wrong

**Key Areas to Analyze:**

1. **DeletionSchedulerService.processDeletions**
   - What if `findEligibleForPhysicalDeletion()` returns null instead of an empty list?
   - What if `storageService.delete()` succeeds but DB write fails mid-transaction?
   - What if `markPhysicallyDeleted()` returns a value other than 0 or 1 (e.g., -1 or 2)?
   - What if two concurrent ticks claim the same row before the lock is acquired?
   - What happens if the Instant.now() call returns a value equal to an already-existing physicalDeletedAt timestamp?

2. **FileStorageObjectRepository.markPhysicallyDeleted**
   - Does the native query properly escape the parameters?
   - What if `physical_deleted_at` column has a NOT NULL constraint but the query doesn't check?
   - Are there any index or locking implications of the `WHERE ... AND physical_deleted_at IS NULL` predicate?

3. **OutboxPollerScheduler.pollAndProcess**
   - What if `pollPending()` throws after the lock is acquired but before `markAsProcessing()` completes?
   - What if the stream map in `markAsProcessing()` produces a null or empty list from a non-empty pending list?
   - What if `pollPending()` returns items that were already processed between the query and the mark call?

4. **OutboxService.sweep**
   - The added comment mentions correctness doesn't depend on this lock — is that actually true given concurrent AFTER_COMMIT drains?
   - What if `MAX_CHUNKS_PER_DRAIN` is 0 or negative? What's the modulo behavior?

5. **Cross-Cutting Concerns**
   - Are there any scenarios where `@SchedulerLock` is acquired but the method body throws before any meaningful work?
   - What if the shedlock database itself is unavailable — does Spring silently fail-open or fail-closed?
   - Is there any ordering dependency between AC1 and AC2 that isn't documented?

## Output Format

Provide your findings as a JSON array:

```json
[
  {
    "location": "DeletionSchedulerService.java:45-48",
    "trigger_condition": "markPhysicallyDeleted returns unexpected value",
    "guard_snippet": "if (affectedRows < 0 || affectedRows > 1) { throw new IllegalStateException(...); }",
    "potential_consequence": "Silent data corruption or orphaned outbox jobs"
  }
]
```

---
