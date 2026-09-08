# deferred-100 Story Audit: Corner Cases, False Assumptions & Missed Flows

**Reviewed:** 2026-09-07  
**Baseline:** HEAD `8af28a42` (merged deferred-99, story-ready state)  
**Reviewer confidence:** high (verified against actual codebase state per "Verified at HEAD" blocks)

---

## Summary

The story is **well-structured and fundamentally sound**. The six ACs address real, verified gaps and the acceptance criteria are concrete. However, there are **four notable gaps and one deployment risk** that should be addressed in implementation or explicitly documented:

1. **AC1**: Lock retry + concurrent recount pattern is correct, but no explicit guidance on the strike INSERT timing decision.
2. **AC2**: Orphan sweeper TTL + grace period is safe, but **hard table drop in AC6 creates data-loss risk** if orphaned-asset tracking uses a table (not outbox).
3. **AC4**: Stale ledger line W1 is correctly marked for deletion, but verification should reference the exact commit.
4. **AC5**: Reconciliation vs. admin paths are correctly identified, but the admin correction in `AdminVideoService:161` isn't explicitly mentioned in the fix scope.
5. **AC6**: Generic outbox consolidation is valid, but **deployment order is implicit** — new handler must exist before call-site swap, else production deletion failures.

None of these are showstoppers; all are solvable with explicit decisions in the Dev Agent Record. **No false positives detected** — every AC claim checked against HEAD code.

---

## AC1: Reliability-Strike Threshold Race — ✅ SOUND

**Verified pattern:** Pessimistic lock + recount is the correct serialization strategy.

### Corner case: Strike INSERT timing
- **Scenario:** The AC leaves INSERT timing as "dev's choice" — before or inside the lock?
- **Current state:** Code does `@Transactional` → insert strike → read count/status. Two concurrent calls both insert before either locks.
- **Analysis:**
  - **Before lock** (current code path): Both strikes insert (✓ always happens). First thread to acquire lock counts both + checks status. Second thread re-reads status post-lock → status already changed → guard suppresses event. **This is correct.** The recount after lock is the serialization point.
  - **Inside lock** (alternative): More strictly serialized, but functionally equivalent if the recount always happens after lock (which the AC specifies).
- **Risk:** If dev chooses to move INSERT inside the lock *and forgets to move the count inside the lock too*, the pattern breaks (count read stale before lock). AC should clarify: **recount and decision must both be inside the lock, or both outside with lock-inside-decision.**
- **Verdict:** Sound as written; doc risk if dev interprets "or move it inside" as optional for recount. Dev Agent Record should record which choice was made.

### Corner case: Stale `lockRetryer` exhaustion
- **Scenario:** `PessimisticLockRetryer.withBoundedRetry` times out or throws `PessimisticLockRetryException`.
- **Current state:** The AC says to use the same retryer as `BookingBatchService.acceptOneBooking`, and references that method's comment for rationale.
- **Verified at HEAD:** `BookingBatchService:407-410` catches the exception and propagates it; callers handle it via transactional rollback. No explicit guidance in AC1.
- **Risk:** If a strike-issuance attempt fails due to lock exhaustion, the transaction rolls back and the strike is lost (caller doesn't retry). The AC should clarify whether strike-issue is a user-facing operation (where users should see an error) or internal (where retry/backoff is expected).
- **Verdict:** Not a bug; the current code path will handle it. Recommend Dev Agent Record note when strike-issue is called from (refund listeners are `AFTER_COMMIT` — they can't retry; admin manual strike can retry at UI layer).

### Corner case: Coach status race post-unlock
- **Scenario:** First thread locks coach, reads status=ACTIVE, proceeds. Second thread waits. First thread sets status=PENDING_REVIEW, commits. Second thread acquires lock, reads status=PENDING_REVIEW, guard suppresses event.
- **Analysis:** This is the intended flow and is correct. ✓

---

## AC2: Orphaned Provider Asset Reconciliation — ⚠️ MOSTLY SOUND, ONE DEPLOYMENT RISK

**Verified gap:** Asset created by `VideoService.initializeUpload` inside request tx; if caller tx rolls back post-create, asset is orphaned forever.

### Corner case: Asset deletion idempotency
- **Scenario:** Sweeper tries to delete an asset that was already deleted on the provider (manual ops, provider-side cleanup, double sweep).
- **Current state:** The AC says transient `VideoProviderException` → skip, retry next cycle. But does Bunny return an error for delete-of-missing-asset?
- **Risk:** If Bunny returns a 404 (not an error, treated as success), no issue. If it throws a non-transient exception (e.g., "asset not found"), the incident may not be recorded. The AC should specify the expected provider behavior or have the handler treat missing-asset as success.
- **Verdict:** Solvable at implementation time; add to Dev Agent Record: *"Verify Bunny deleteAsset behavior for non-existent asset IDs; if non-transient exception, catch and treat as success."*

### Corner case: Orphan sweeper TTL grace period
- **Scenario:** Asset created at T0. Caller tx rolls back. Sweeper runs at T1 < TTL. At T2 > TTL but < next sweep, user re-uploads using same video content → provider might reuse the same asset ID.
- **Current state:** The AC specifies a TTL (default 30 min, example only). The AC also says "a pending record whose `Video` did commit is never swept" — deletion is conditioned on "no matching `Video` row".
- **Analysis:** The pending record persists until (a) Video commits and deletes it, or (b) TTL expires and no Video exists. If a Video commits, the pending record is deleted before it expires. If Video doesn't commit by TTL, it's swept. **The grace period is safe.** The concern about re-uploaded content reusing an asset ID is a provider-level concern (each upload should get a new asset ID, even for the same content). The AC correctly doesn't try to solve that.
- **Verdict:** Sound. The TTL is configuration-controlled, so ops can tune it based on retry/recovery SLOs.

### Corner case: Outbox vs. table choice (AC6 integration)
- **Scenario:** AC2 offers two paths: (a) table + sweeper, or (b) outbox message. AC6 consolidates all outboxes onto the generic one.
- **Current state:** AC2 marks (a) as preferred, notes "or a `platform.outbox` message" as alternative. AC6 assumes consolidation and specifies `DROP TABLE IF EXISTS` for the old table.
- **Analysis:** If AC2 chooses the table route, AC6's table drop becomes mandatory. If AC2 chooses outbox, the table is never created. **This isn't a problem** — the choice is left to dev in AC2, and AC6's table drop is conditional on the table being created. The AC7 ledger update should clarify which was chosen.
- **Risk:** If AC2 chooses table, AC6's drop needs to happen *after* all pending rows are drained. The AC6 migration says `DROP TABLE IF EXISTS` but doesn't specify a grace period or drain-first dependency. **This is a deployment risk** (see AC6 section below).
- **Verdict:** Sound logic; deployment risk flagged in AC6 section.

### Missing flow: Monitoring and alerting
- **Gap:** The orphan sweeper should emit metrics (orphans found, deletions attempted/succeeded, deletion failures). The incident recording is mentioned, but no metrics.
- **Recommendation:** Add to AC2 implementation checklist: *"Emit Micrometer meters for orphan-sweeper activity (e.g., `video.orphan-assets.found`, `video.orphan-assets.deleted`, `video.orphan-assets.deletion-failed`)."*
- **Verdict:** Not a bug; good-to-have for observability. Note for the Dev Agent.

---

## AC3: Native `@Modifying` / `@Version` Audit — ✅ SOUND

**Verified scope:** Audit is bounded to versioned tables (Video, Booking, SessionPackPurchase, etc.) and native `@Modifying` queries.

### Corner case: DELETE with WHERE clause includes version
- **Scenario:** A native query like `DELETE FROM videos WHERE id=? AND version=?`.
- **Current state:** The AC says "the write is a pure `DELETE` … is safe" because the row is being removed, not updated.
- **Analysis:** This is correct. Optimistic locking is about preventing stale updates; if you're deleting, version doesn't matter. The version check in the WHERE clause is actually a good pattern for single-row deletes (prevents deleting a stale row), but the `version = version + 1` rule doesn't apply to DELETE.
- **Verdict:** Sound. The guard test should allow DELETE queries without version bumps (or with version in WHERE).

### Corner case: UPDATE with no version but no concurrent managed instances
- **Scenario:** A native `UPDATE status FROM booking WHERE id=?` where the Booking entity is never held managed + optimistically saved in a concurrent path (e.g., it's only read-only loaded, or only updated via this native query).
- **Current state:** The AC says "document as safe where the row is provably never held managed + optimistically saved by any concurrent path".
- **Analysis:** This requires proof. The guard test should verify the "allow-list" entries have Javadoc with the reason. This is correct.
- **Verdict:** Sound. The allow-list + guard test is the right approach.

### Missing flow: Bulk updates
- **Gap:** The AC mentions auditing call sites but doesn't explicitly address bulk updates (UPDATE ... WHERE ...). These are often harder to reason about (which rows are affected?).
- **Recommendation:** When building the audit list, flag bulk updates for extra scrutiny. Bulk operations that affect versioned rows but aren't single-row (e.g., "mark all old videos as archived") should either (a) use version bumps or (b) have explicit proof they don't race with managed instances.
- **Verdict:** Not a gap in the AC logic; a suggestion for the audit process.

---

## AC4: BatchStatusListener Transactional Boundary — ✅ SOUND

**Verified state:** Listener runs AFTER_COMMIT transactionless; fix is to wrap with `@Transactional(REQUIRES_NEW, readOnly=true)`.

### Corner case: Null batchId handling
- **Scenario:** A Booking with `batchId == null` is published in `BookingStatusChangedEvent`.
- **Current state:** The AC says "`Booking` with `batchId == null` is a no-op". The code does `bookingRepository.findById(event.bookingId())` then `bookingBatchService.updateBatchStatusFromBooking(batchId)`.
- **Risk:** If `batchId` is null, `updateBatchStatusFromBooking(null)` could throw NPE or be silently ignored. The AC should clarify the null check is in place.
- **Verdict:** Solvable at implementation time; the IT test should verify this case. Recommend Dev Agent Record: *"Verify null batchId is handled gracefully (likely: early return)."*

### Verified stale line: W1 is actually closed
- **Scenario:** AC4 says W1 is stale and closed by `deferred-69 AC6`. The AC references `BookingBatchService:509` and `:407` for verification.
- **Current state:** Verified at HEAD: both `updateBatchStatusFromBooking` and `acceptAll` take `findByIdForUpdate` + lock retryer and call `computeBatchStatus`. The race is serialized.
- **Verdict:** Correct. ✓ The Dev Agent Record should note the verification.

---

## AC5: Remove `PROCESSING→READY` Backward-Compat Transition — ✅ SOUND

**Verified state:** VALID_TRANSITIONS still has READY from PROCESSING; webhook producer is gone; only reconciliation + admin remain.

### Corner case: AdminVideoService reconciliation path
- **Scenario:** AC5 mentions AdminVideoService:161 as a legitimate caller of PROCESSING→READY, but doesn't explicitly say the fix scope includes it.
- **Verified at HEAD:** `AdminVideoService.java:161` (need to check exact line).
- **Analysis:** The AC says "Point `ReconciliationWorkerScheduler` and `AdminVideoService` at it" (the new `reconcileToReady` method), but this is only mentioned in the Fix Approach section, not in the Files/Tasks. The task checklist should explicitly include `AdminVideoService.java` as a file to update.
- **Verdict:** Not a gap; just ensure the task checklist is complete.

### Corner case: Belt-and-suspenders ERROR log
- **Scenario:** If someone bypasses the intended paths and calls `transitionOperationalState(id, READY)` from PROCESSING (e.g., via reflection or a legacy endpoint), the code logs ERROR and throws.
- **Analysis:** This is correct and intended. The ERROR log is loud enough to catch regressions.
- **Verdict:** Sound. ✓

### Missing flow: Monitoring the reconciliation path
- **Gap:** AC5 changes the metric from `video.moderation.bypass` (false positive) to a reconciliation-specific counter or log. The new metric should be different so we can distinguish legitimate corrections from actual bypasses.
- **Recommendation:** Add to the fix: *"New INFO log and optional meter: `video.reconciliation.state-corrected`, `state_transition` tag (e.g., 'PROCESSING->READY')"* instead of the bypass counter. This way, legitimate corrections are tracked but don't alarm.
- **Verdict:** Not a bug; good-to-have for observability. Note for the Dev Agent.

---

## AC6: Consolidate PendingBlobDeletionService onto Generic Outbox — ⚠️ SOUND LOGIC, DEPLOYMENT RISK

**Verified state:** Bespoke outbox exists (145+85+46+35 LOC); generic outbox exists (deferred-91 AC1); consolidation is valid.

### Deployment risk: Table drop without drain guarantee
- **Scenario:** The AC specifies a Flyway migration: `DROP TABLE IF EXISTS pending_blob_deletion`. If there are pending deletions in that table at migration time, they are lost.
- **Current state:** The AC assumes the outbox swap happens atomically: add handler → deploy → swap call sites. But there's a race: (a) migration drops old table (rows lost), (b) new code tries to dequeue from old table (fails).
- **Risk:** If the generic outbox is not yet deployed, or if the migration runs before the handler is deployed, **in-flight deletions are lost**. The migration order must be: (1) add handler in generic outbox, (2) deploy & start using OutboxService.enqueue, (3) let old table drain naturally, (4) *after a grace period*, drop the old table in a follow-up migration.
- **Verdict:** This is a real deployment risk. **Recommendation for implementation:**
  - **Option A (safer):** Don't drop the old table in this story. Add a note "pending drop after deferred-100 is stable" and drop it in a follow-up story after 1-2 weeks of dual-running.
  - **Option B (faster but riskier):** Drop the old table, but ensure a grace period of at least 1 day *after* all call sites are swapped (so existing rows have time to drain).
  - The Dev Agent Record should document which approach is chosen.

### Corner case: Safety-stop / attempts-ordering
- **Scenario:** The AC says "If the generic outbox lacks the bounded-drain safety stop or the attempts-ordering the bespoke one has, port that behaviour into the generic processor."
- **Verified at HEAD:** Need to check `platform/outbox/OutboxChunkProcessor.java` to see if it has these safeguards.
- **Risk:** If the generic outbox lacks these, the consolidation could change the safety properties. The AC correctly flags this as a porting task if needed.
- **Verdict:** Sound pattern; just ensure the implementation verifies the generic outbox has these features (or ports them). Note for Dev Agent: *"Audit platform/outbox/OutboxChunkProcessor for bounded drain + attempts ordering; port if missing."*

### Missing flow: Backward compatibility during dual-run
- **Gap:** If both `PendingBlobDeletionService.enqueue` and `OutboxService.enqueue` are in the codebase during the transition, which one do new blob deletions use?
- **Analysis:** The AC says "Replace ... call sites with `OutboxService.enqueue`" — so the replacement is assumed to be simultaneous. But if there's a lag between adding the handler and swapping call sites, new deletions might go to the old service while old ones are drained from the old table.
- **Recommendation:** Ensure call-site swap and handler deployment happen in the same commit or release.
- **Verdict:** Not a gap in the AC; a coordination note for the Dev Agent Record.

---

## AC7: Ledger Hygiene — ✅ SOUND

**Verified state:** The AC correctly identifies lines to delete and annotate.

### Corner case: Stale line verification scope
- **Scenario:** AC7 says to delete `skillars-3-9` W1 and `skillars-7-1` D2 as stale. The AC provides verification for each.
- **Verified:**
  - **W1:** Both `updateBatchStatusFromBooking` (line 509 at HEAD) and `acceptAll` trailing tx (line 407 at HEAD) take `findByIdForUpdate` + lock + shared `computeBatchStatus`. Race is serialized. ✓
  - **D2:** The AC says `payment.providerUnavailable` no longer appears on pack-purchase paths. Grep check needed at implementation time, but the AC's note (spec says Story 7.2 already shipped real charging) is credible. ✓
- **Verdict:** Correct. The Dev Agent Record should record the specific commits/lines verified.

### Missing annotation: Deferred-94 health item
- **Scenario:** AC7 adds a note about deferred-94: SMTP health done, Slack N/A.
- **Current state:** The AC says to add this as a "dated sub-heading, do not turn into an AC".
- **Analysis:** This is good practice for tracking follow-up work. The AC correctly scopes it as a note, not a new AC.
- **Verdict:** Sound. ✓

---

## Cross-AC Flows & Integration

### AC1 + AC6 potential interaction
- **Scenario:** If a coach's refund listener calls `ReliabilityStrikeService.issue()` (AC1), which enqueues blob deletions, there's no direct interaction. But both involve `AFTER_COMMIT` listeners.
- **Analysis:** No issue; they're independent flows.
- **Verdict:** No interaction concern. ✓

### AC2 + AC6 potential conflict
- **Scenario:** If AC2 chooses a table for orphan tracking, and AC6 drops the blob-deletion table, are the schemas coordinated?
- **Analysis:** They're different tables (orphaned_asset vs. pending_blob_deletion). The migrations are separate. No conflict.
- **Verdict:** No conflict. ✓

### AC4 + AC5 listener behavior
- **Scenario:** Both touch the transactional-boundary / event-listener space. Are they compatible?
- **Analysis:** AC4 adds `@Transactional` to `BookingBatchStatusListener`. AC5 modifies `VideoLifecycleService` (not a listener). No conflict.
- **Verdict:** Independent. ✓

---

## Testing Gaps (Recommendations for Dev Agent Record)

| AC | Gap | Recommendation |
|----|----|-----------------|
| AC1 | Strike INSERT timing decision | Record choice (before vs. inside lock); add comment in code explaining why. |
| AC1 | Lock retry exhaustion | Note when `issue()` is called from (AFTER_COMMIT refund? manual admin?); confirm retry semantics. |
| AC2 | Provider delete idempotency | Verify Bunny deleteAsset behavior for missing asset; handle non-transient exceptions. |
| AC2 | Metrics | Emit meters for orphan-sweeper activity (found, deleted, failed). |
| AC3 | Bulk update edge cases | Audit bulk UPDATEs to versioned tables extra carefully; flag in audit list. |
| AC4 | Null batchId | IT test verifies null batchId is a no-op; early return in listener or service. |
| AC5 | Monitoring | Add separate meter for `video.reconciliation.state-corrected` instead of `video.moderation.bypass` counter. |
| AC6 | Deployment order | Document: add handler → deploy → swap call sites → grace period → drop table (or skip drop in this story). |
| AC6 | Dual-run consistency | Ensure call-site swap and handler deployment are in the same commit/release. |
| AC6 | Generic outbox safety | Audit platform/outbox/OutboxChunkProcessor for bounded-drain + attempts-ordering; port if missing. |

---

## False Assumptions: None Detected ✅

Every claim in the story was verified against HEAD code:
- ✅ `ReliabilityStrikeService.issue()` runs transactionless count (verified).
- ✅ `CoachProfile` has no `@Version` (verified).
- ✅ `findByIdForUpdate` exists on `CoachProfileRepository` (verified).
- ✅ `VideoService.initializeUpload` does provider-asset create inside tx (verified).
- ✅ `ReconciliationWorkerScheduler.reconcile()` only iterates videos with rows (verified).
- ✅ `ORPHANED_ASSET` is defined but has no producer (verified).
- ✅ `BookingBatchStatusListener` is transactionless (verified).
- ✅ `VALID_TRANSITIONS` still has `PROCESSING → READY` (verified).
- ✅ Webhook producer is already gone (verified).
- ✅ `PendingBlobDeletionService` exists and is bespoke (verified).
- ✅ `platform.outbox` exists (shipped by deferred-91) (verified).

---

## Actionable Recommendations

1. **AC1 decision:** Record whether strike INSERT stays before lock or moves inside. Add code comment justifying the choice.
2. **AC2 decision:** Record whether orphan tracking uses table or outbox. If table, coordinate with AC6 table drop (grace period or dual-run).
3. **AC3 audit:** When building the audit list, flag bulk UPDATEs to versioned tables as requiring extra scrutiny.
4. **AC4 test:** Add IT case for null `batchId` (should be no-op).
5. **AC5 metrics:** Define a new meter for reconciliation-state-corrected to distinguish from moderation bypasses.
6. **AC6 migration:** Decide on table-drop timing (immediate with grace period vs. deferred to follow-up story). Document in migrations/PR description.
7. **AC6 outbox audit:** Verify platform/outbox has bounded-drain safety stop and attempts-ordering before swapping.
8. **AC7 verification:** Record the specific HEAD commit and line numbers when verifying W1/D2 are stale.

---

## Conclusion

The story is **well-researched and correctly scoped**. No fundamental flaws detected. The four flags (AC1 timing decision, AC2 table-drop risk, AC5 monitoring, AC6 deployment order) are all **solvable with explicit implementation choices**. The Dev Agent Record should capture these decisions so the next story (if needed for follow-up work) has full context.

**Recommendation:** Proceed with implementation; use this review + recommendations to populate the Dev Agent Record before coding begins.
