# Senior Dev Audit — `skillars-deferred-120`

**Story under review:** `_bmad-output/implementation-artifacts/skillars-deferred-120-video-subscription-outbox-double-processing-and-remaining-scheduler-lock-gaps.md`
**Reviewed against:** HEAD of `story/deferred-120-scheduler-lock-gaps` (post-`deferred-119` merge, `cba3964d`)
**Date:** 2026-09-17
**Verdict:** **Changes requested before dev.** The three fixes are the right fixes and land on real gaps — every line reference, every entity/`@Version` claim, the 35/21/14 codebase counts, and all nine "cleared, not picked up" entries were independently re-verified and hold. But three items are blocking: one false premise that inflates AC1/AC2's stated severity, one explicitly-stated assumption that is demonstrably wrong and will break three existing integration tests, and one standing codebase instruction the story trips over without noticing. Five further items are factual errors or incomplete analysis in the failure scenarios.

---

## What holds up (verified, no action needed)

Recorded so the next reader does not re-audit it:

- **Counts are exact.** `grep -rE "^\s*@Scheduled\("` over `src/main/java` = **35**; `^\s*@SchedulerLock\("` = **21**; 14 unlocked. The claimed 5-fixed (`VideoSubscriptionLifecycleListener` 1, `VideoDeletionOutboxProcessor` 1, `UserAdminService` 1, `AuthCleanupService` 2) + 9-cleared enumeration reconciles precisely against those 14. No inflation.
- **All nine "cleared" entries hold on direct read.** Spot-verified in depth: `WebhookEventProcessorScheduler.processPending` — `VideoWebhookEventRepository.findPendingForUpdate` really is `FOR UPDATE SKIP LOCKED` (`VideoWebhookEventRepository.java:36-43`), not just a Javadoc claim, and the per-item transaction re-fetches by id and re-checks `status == PENDING` before flipping to `PROCESSING`; `UploadSessionExpiryScheduler.processExpired` re-fetches and re-checks `PENDING` inside its own transaction; `ReconciliationWorkerScheduler.reconcile` matches the described short-claim shape. **No false clears found.**
- **Entity claims.** `SubscriptionLifecycleOutbox` has no `@Version` ✓. `VideoDeletionOutbox` has no `@Version` ✓. `SessionPackPurchase` does, at `SessionPackPurchase.java:67-69` ✓ (exact lines).
- **AC2's fix precedent is real.** `RadarCompositeDlqProcessor` carries the `skillars-deferred-118` AC3 comment at `:22-25`, got `@SchedulerLock`, and deliberately left `findClaimedBatch()` unscoped — so "mirror that decision" is legitimate, not invented.
- **"Do not touch `SessionPackForfeitureScheduler`" is correct.** It re-fetches fresh and re-checks all three predicates inside the per-item transaction (`:43-62`), returning `null` inside the `try` — it genuinely cannot reach the catch clause the way `SessionPackExpiryNotifier` can.
- **Line references** spot-checked and accurate throughout (`VideoSubscriptionLifecycleListener` `:58-70`/`:73-93`/`:109-119`; `VideoDeletionOutboxProcessor` `:42-51`; `VideoDeletionOutboxRepository` `:17-30`/`:33-38`; `AuthCleanupService` `:23-37`; `UserAdminService` `:84-116`; `SessionPackExpiryNotifier` `:71-113`/`:91-92`/`:111-112`). Cadences correct: `UserAdminService` daily `0 0 1 * * ?`, `AuthCleanupService` hourly `0 0 * * * *` / 30-min `0 */30 * * * *`.

---

## BLOCKERS

### B1 — `fixedDelay` cannot overlap itself. The "same instance" half of both AC1's and AC2's failure scenarios is false, and nothing else in them is reachable today.

AC1 is `@Scheduled(fixedDelay = 60_000)`; AC2 is `@Scheduled(fixedDelayString = "${…:60000}")`. Spring schedules the next `fixedDelay` execution `delay` ms after the **previous execution completes** — a single `@Scheduled` task never runs concurrently with itself, regardless of scheduler pool size. That only holds for `fixedRate`.

The story asserts the opposite twice:

> AC1: *"Two ticks — the same instance if a run takes longer than the 60-second `fixedDelay`…"*
> AC2: *"Two ticks — the same instance if a run exceeds the 60-second `app.video.deletion.outbox_poll_delay_ms` default…"*

Strike both. What remains in each scenario is the multi-instance case, which the story itself flags as prospective (*"once this deployment scales horizontally"*, *"a future horizontal scale-out"*) — and no `docker-compose*.yml` in this repo declares `replicas` anywhere, so the deployment is single-instance today.

**Consequence for the story, not for the code:** AC1 and AC2 are **not reachable in the current deployment**, past or present. The Priority block's framing ("genuine claim-free/claim-unscoped double-processing gaps whose consequence today is bounded to wasted duplicate work") overstates it — the consequence *today* is nil. The "two genuine bugs found / one consistency bundle" split collapses: all three ACs are forward-looking hardening of the same kind. Keep all three fixes, rewrite the Priority rationale and both failure scenarios so an owner reading this is not told a live defect exists.

### B2 — Adding `@SchedulerLock` breaks three existing integration tests. The story explicitly promises it will not.

Both AC1 and AC2 say under **Verified by**:

> *"Existing tests covering … re-run green — no behavior change to the method bodies, only the added annotation."*

`AbstractIntegrationTest.releaseSchedulerLock`'s own Javadoc (`:125-141`) refutes this directly:

> *"Not optional housekeeping: calling a scheduled bean method from a test goes through the Spring proxy, so ShedLock applies. With `lockAtLeastFor` set (PT2M across this codebase), a second invocation inside the same test class is silently SKIPPED — logged as 'held by another instance' — and the assertions that follow then pass or fail for reasons unrelated to the code under test. Call this before every invocation."*

Three existing tests invoke the target method **twice inside one test method**, which is exactly the case that breaks:

| Test | Lines | What breaks |
|---|---|---|
| `VideoSubscriptionLifecycleListenerIT.processOutbox_failureOnFirstAttempt_retriedOnSecondCall` | `:200`, `:209` | 2nd call no-ops → retry assertion fails |
| `VideoSubscriptionLifecycleListenerIT.processOutbox_maxAttemptsReached_markedDeadLetter` | `:226`, `:229` | 2nd call no-ops → `DEAD_LETTER` never reached |
| `VideoDeletionOutboxProcessorIT.process_failThenSucceed_completesOnRetry` | `:122`, `:138` | 2nd call no-ops → row never `COMPLETED` |

Cross-*method* invocations are safe — `DatabaseResetTestExecutionListener.backdateShedLock` (`:387-389`) runs inside `beforeTestMethod` (`:102`, `:122`), backdating every `main.shedlock` row before each test. But the convention in this codebase is a `releaseSchedulerLock(...)` call before **every** invocation, and these sites need it: `VideoSubscriptionLifecycleListenerIT:98,116,132,200,209,226,229`; `SimultaneousExpiryIT:84,109`; `YearlyExemptionRenewalIT:76,93`; `VideoDeletionOutboxProcessorIT:54,73,86,108,122,138`.

**Required story changes:**
1. Delete the "no behavior change / existing tests re-run green" claim from AC1 and AC2; replace it with an explicit task to add `releaseSchedulerLock(...)` at all 15 call sites above.
2. **Specify the lock `name` strings in the ACs** — the test edits need them verbatim and the story never states them. Follow the codebase convention `<ClassName>_<shortMethod>`; `main.shedlock.name` is `varchar(64)` (`V138__baseline_schema.sql:1203`), and the longest new name (`VideoSubscriptionLifecycleListener_processOutbox`, 48 chars) fits.
3. Correct two AC3 test claims: `UserAdminServiceTest` is a plain Mockito test that builds the bean with `new UserAdminService(...)` (`:41-45`) — **no Spring proxy, so ShedLock does not apply and it is unaffected**. `AuthCleanupService` has **no tests at all** (`grep -rl AuthCleanupService src/test` → empty), so "re-run existing `AuthCleanupService` test suites green" is vacuous; either say so or add first-coverage as a task.

### B3 — AC3 Finding 2 re-introduces a `@SchedulerLock` + `@Transactional` stack, which this codebase has a written standing instruction about.

`SchedulerLockTransactionOrderingIT`'s class Javadoc:

> *"With no scheduler left in this codebase stacking both annotations, the advisor-ordering assertion this class used to make … no longer has a bean to exercise it against… **If a future scheduler legitimately needs to stack both annotations again, reintroduce the advisor-ordering assertion alongside it** rather than resurrecting this comment."*

AC3 does precisely that, twice over, and says nothing about it:
- `AuthCleanupService.purgeExpiredRefreshTokens` / `purgeOldLoginAttempts` — both carry method-level `@Transactional` (`:24`, `:33`).
- `UserAdminService.removeNotActivatedUsers` — method-level `@Transactional(propagation = NOT_SUPPORTED)` (`:86`) under a **type-level** `@Transactional` (`UserAdminService.java:32`).

The ordering is in fact correct — `AsyncConfig`'s `@EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)` puts the ShedLock advisor outside the bare-`LOWEST_PRECEDENCE` transaction advisor, so the lock is taken before the transaction opens and released after commit. So this is **not a bug**; it is an un-followed instruction. Add to AC3:
1. A task to restore the advisor-ordering assertion in `SchedulerLockTransactionOrderingIT` against one of the newly-stacked beans, per that Javadoc.
2. An explicit note that keeping `@Transactional` on these two is correct — the batch-wide-rollback hazard that motivated stripping it from `BookingExpiryScheduler`/`BookingReminderScheduler`/`BandwidthResetService` does not apply to a single-statement bulk delete. Without that note, the next reader tidying for consistency strips it.

---

## HIGH — factual errors and incomplete analysis

### H1 — AC1's Path B duplicate-work claim is wrong. `findActiveReadyByOwner` is already `FOR UPDATE SKIP LOCKED`, and the lock is held for the whole entry.

The story's stated impact:

> *"…and (for `Path B`, the non-YEARLY branch) doubling every downstream DB write and `videoLifecycleService` call for every affected video"*

`VideoRepository.findActiveReadyByOwner` (`:94-103`) is `SELECT * FROM main.videos WHERE owner_id = … AND operational_state = 'READY' AND access_state = 'ACTIVE' ORDER BY created_at ASC LIMIT :batchSize **FOR UPDATE SKIP LOCKED**`. It runs inside `processAndSaveEntry`'s `@Transactional(REQUIRES_NEW)` — `processEntry` is a private call, and `blockForSubscriptionExpiry` is `@Transactional` (REQUIRED) so it joins rather than opening its own — therefore the row locks are **held until that entry's transaction commits**. A second concurrent run on the same subscriber skips every locked row, gets a short or empty page, and exits its `do/while` early. The doubled `blockForSubscriptionExpiry` calls the story predicts do not occur. Path A (`resetLifecycleLockedAt`) is an idempotent bulk `UPDATE`, as the story says.

**What the story understates in exchange, and should argue instead** — this is a stronger case for the same fix: `processOutbox` runs with **no transaction**, so each `entry` is detached, and `outboxRepository.save(entry)` in `processAndSaveEntry` is a **`merge`** that writes back the whole stale snapshot of every mutable column (`status`, `attempts`, `last_error`, `processed_at`; the rest are `updatable = false`). With no `@Version` to arbitrate, the later of two concurrent writes wins outright — so a `DEAD_LETTER` can be overwritten with `PROCESSED` (a real, max-attempts failure silently erased, with its `last_error` and the operator alert it exists to raise) or a `PROCESSED` overwritten with `DEAD_LETTER` (a false "manual remediation required" alert). That is a status-correctness problem, not merely the `attempts` undercount the story describes. Rewrite AC1's failure scenario on this basis.

### H2 — AC2 misses a second concurrent path: `resetStaleClaimed` defeats `claimPendingBatch` for any backlog older than 10 minutes.

`resetStaleClaimed` (`VideoDeletionOutboxRepository.java:43-48`) is `WHERE status = 'CLAIMED' AND next_retry_at < :deadline`, and `claimPendingBatch` (`:19-30`) **never stamps `next_retry_at` when it claims**. So `next_retry_at` is the row's *eligibility* timestamp, not its claim timestamp, and the "crashed run recovery" deadline at `VideoDeletionOutboxProcessor.java:44` effectively means *"this row has been eligible for more than 10 minutes"*, not *"this row has been claimed for more than 10 minutes"*.

For any row with `next_retry_at` older than 10 minutes — any real backlog, or a row that waited behind a full 50-row batch — a concurrent instance's tick runs `resetStaleClaimed` first, flips the row straight back to `PENDING` **while the other instance is mid-flight on it**, and then re-claims it on the very next line. That is genuinely concurrent processing of the *same* row, defeating the `FOR UPDATE SKIP LOCKED` claim the story explicitly calls *"sound, and prevents two concurrent invocations from claiming the same row."*

`@SchedulerLock` closes this too, so the fix does not change. But name it in AC2, because (a) it is a much more likely trigger than the disjoint-batch story, which needs two same-tick ticks and a full batch; and (b) **the identical defect exists in the already-locked `RadarCompositeDlqProcessor`** (`:60`, same `resetStaleClaimed`/`claimPendingBatch` pair) — so it is a genuine weakness in the shared claim idiom and belongs in `deferred-work.md` rather than going unrecorded.

### H3 — AC2's severity conclusion is right for a reason it never states; and one real duplicate-write is missing.

The load-bearing fact the story omits: `BunnyVideoProviderAdapter.deleteAsset` (`:242-258`) treats a provider 404 as success — *"asset not found on Bunny (already deleted?); treating as success"* — and does **not** throw. So a duplicate `deleteAsset` on an already-deleted asset never reaches `handleFailure`, and cannot flip an already-`COMPLETED` row back to `PENDING`/`DEAD`. That is what makes the story's "bounded to wasted provider-API calls, not data corruption" conclusion correct. Without it, a reader has to assume the 404 throws, in which case the row would be resurrected, re-claimed, and eventually dead-lettered with a false operator alert. State it.

Missing on the other side: the story says *"the terminal `outboxRepository.save(row)` writes are idempotent in outcome (`status = "COMPLETED"` either way)"*. True of `status`, false of the audit trail — `appendDeletionLog` (`:141-147`) is an **unconditional `INSERT`** called on all three completion paths (`:74`, `:102`, `:115`), so every cross-processed row produces a **second `video_deletion_log` row**. Add it to the failure scenario.

### H4 — The `lockAtLeastFor` sizing guidance points at precedents that don't match or don't exist.

Dev Notes:

> *"`VideoSubscriptionLifecycleListener` (60s) and `VideoDeletionOutboxProcessor` (60s default) are the same cadence class as `ReconciliationWorkerScheduler`/`OutboxPollerScheduler`…"*

Both named precedents are wrong at HEAD:
- `ReconciliationWorkerScheduler.reconcile` (60 s) has **no `@SchedulerLock`** — the only locked method in that class is `sweepOrphanedProviderAssets`, at `${app.video.orphan-asset.sweep-delay-ms:300000}` = **5 minutes** (`PT10M` / `PT0S`).
- `OutboxPollerScheduler.pollAndProcess` is `${app.storage.poller.fixed-delay-ms:5000}` = **5 seconds** (`PT10M` / `PT2S`).

AC1 compounds it: *"see those two schedulers' own `@SchedulerLock` values, once AC2 adds one"* — circular, pointing at a value AC2 is itself creating plus one that does not exist.

The genuine 60-second-cadence precedents at HEAD, none of which the story cites:

| Scheduler | Cadence | `lockAtMostFor` / `lockAtLeastFor` |
|---|---|---|
| `RadarCompositeDlqProcessor` (AC2's exact structural twin) | 60 s default | `PT10M` / `PT30S` |
| `QuotaReservationTimeoutService` | 60 s | `PT10M` / `PT1M` |
| `EmailRetryScheduler` | 60 s | `PT10M` / `PT10S` |

Replace that Dev Notes paragraph with this table. Note for B2: a `PT30S`-class `lockAtLeastFor` is exactly what makes the three same-method double-invocation tests fail.

### H5 — AC3 Finding 1: `removeNotActivatedUsers`'s loop is not merely "unbounded in iteration count" — it can spin forever, and a generous `lockAtMostFor` makes that worse.

`findExpiredUsers` (`:122-128`) returns non-activated users created before the cutoff, capped at `batchSize`. The `while (hasMore)` loop exits **only** on an empty result (`:97-98`). Per-user `deleteUserInTransaction` failures are caught and swallowed to keep the sweep going (`:104-111`). So if every user in a batch fails to delete for a deterministic reason (an FK from a row the cascade does not cover, a trigger, a constraint), the identical batch is returned on every iteration and **the method never returns**. Nothing in production guarantees progress; the existing test terminates only because Mockito is stubbed `thenReturn(List.of(batchUser), List.of())` (`UserAdminServiceTest`, `:88-100` — including the deliberate `doThrow` delete-failure case, which still terminates only via that stub).

This matters specifically because of what the story prescribes:

> *"`lockAtMostFor` should be sized generously to accommodate a plausible worst-case expired-user backlog rather than a tight estimate"*

Under ShedLock, `lockAtMostFor` is a **hard lock-release deadline, not a run cap**. Once it elapses the job is still running and a second node acquires the lock and starts its own non-terminating spin. Generous `lockAtMostFor` + a loop with no progress guarantee is the single worst combination available here: it delays the failure, then multiplies it.

AC3 must pick one and say so:
- **(a)** Add the `MAX_BATCHES_PER_RUN`-style cap the story explicitly declines. Precedent already exists in this codebase, including for exactly this purpose: `SluSnapshotAppliedRetentionService.pruneOlderThanRetention`, and `OutboxService.java:120` — *"lockAtMostFor sized well above MAX_CHUNKS_PER_DRAIN (200) x OutboxChunkProcessor.CHUNK_SIZE (25)"*. This is the recommended option; it is a handful of lines and it is what makes any `lockAtMostFor` value defensible.
- **(b)** Exclude failed logins from the next iteration's selection so the loop provably advances.
- **(c)** If neither, surface it as an explicit owner-visible risk in the story — do **not** add the lock and a generous `lockAtMostFor` to a loop with no progress guarantee while calling it out only as a sizing nuance.

Related, and the story should stop citing it approvingly: `findExpiredUsers` builds a `PageRequest` it never uses and calls `findAllByActivatedIsFalseAndCreatedDateBefore(cutoffDate)` — a full unpaginated load — then trims with `.stream().limit(batchSize)` in memory. The method Javadoc's *"Uses pagination to limit fetched amount (configurable batch size)"* is false. Out of this story's scope to fix; in scope to not quote as established fact.

---

## MEDIUM

### M1 — AC3 Finding 3 is a real finding with an adequate fix, but its stated justification is partly wrong, and it sends the dev to a test that does not exist.

**Verified sound:** `SessionPackPurchase` carries `@Version` at `:67-69` ✓. The batch load commits before the loop (`:65-66`), so each `pack` is detached and `sessionPackPurchaseRepository.save(pack)` at `:92` is a **version-checked `merge`** — Hibernate loads the current row, compares versions, and raises `StaleObjectStateException` (translated to an `OptimisticLockingFailureException` subclass) ✓. Today that lands in the generic `catch (Exception)` and logs `ERROR` at `:112` ✓. `PaymentPendingSweeper`'s INFO special-case is at `:129` with the `log.info` at `:134` and the rationale comment in between ✓.

**Wrong:** the reason given for why the skipped warning is harmless —

> *"the pack's `extendedAt IS NULL` predicate will simply stop selecting it once the extension is visible"*

covers only `extendPack`, which does set `extendedAt` and pushes `expiresAt` +30 days (`SessionPackPaymentService.java:172-173`), taking the pack out of the 14-day window anyway. The *most likely* concurrent writer against a pack at 08:00 is ordinary session consumption or `pausePack` — neither sets `extendedAt`. For those the pack is simply **re-selected the next morning and warned then**, still well inside a 14-day window, and that is the actual reason nothing is lost. The conclusion survives; the wording needs replacing. (The remaining branch is also benign: if the concurrent write consumed the *last* session, `remainingSessions > 0` excludes it — correctly, since there is nothing left to warn about.)

**Dead-end instruction:** the story says to *"mirror whatever pattern `PaymentPendingSweeper`'s own test suite uses to prove its `OptimisticLockingFailureException` handling (check for a `PaymentPendingSweeperTest` case…)"*. There is no such test — `grep OptimisticLocking` over `PaymentPendingSweeperTest.java` and `PaymentPendingSweeperIT.java` returns nothing. The dev writes this from scratch; say so rather than sending them looking.

### M2 — Finding 3 declines the module's own established fix for this shape without saying so.

`skillars-deferred-117` AC3 established re-fetch-and-re-check as this module's answer to exactly this batch-load-then-stale-write shape, on the sibling method reading the same entity, with an explicit comment: *"using the freshly-fetched entity, never the stale `staleFromBatch` snapshot, for every subsequent read/write"* (`SessionPackForfeitureScheduler.java:47-50`). `notifyExpiringPacks` is the last holdout in the module.

To be clear, this is **not** a correctness bug and the log downgrade is a legitimate minimal fix: because `@Version` is present, the stale merge either matches (nothing changed, so writing the snapshot back is a no-op on the other columns) or throws. There is no silent stale overwrite, and no stale `remainingSessions` can reach the outgoing email. But the story is silent on a pattern it is actively choosing not to apply, three paragraphs after citing the story that established it. Add one sentence recording the choice and why — the `@Version` argument above is the reason, and it belongs in the story so the next auditor does not re-open it.

---

## LOW / nits

1. **Wrong property name.** AC2 and its failure scenario both say `app.video.deletion.outbox_poll_delay_ms`. Actual: `platform.video.deletion.outbox_poll_delay_ms` (`VideoDeletionOutboxProcessor.java:40`). The `app.` prefix would silently fall through to the 60000 default, so a dev grepping for it finds nothing.
2. **AC4's grep does not match AC4's prose.** The prose claims a sweep over five classes (`VideoSubscriptionLifecycleListener`, `VideoDeletionOutboxProcessor`, `UserAdminService`, `AuthCleanupService`, `SessionPackExpiryNotifier`); the command covers two. Extend the command.
3. **Off-by-one.** The `UserAdminService` ShedLock TODO is at `:80-82`, not `:79-82` (`:79` is the bare `<p>`).
4. **`Files to Read` cites `PaymentPendingSweeper.java:124-138`** for the INFO/ERROR split; the catch is at `:129` and the `log.info` at `:134`, so the range is right but the AC3 body's bare `:129` is the more useful pointer. Harmless.
5. **`ModerationSlaMonitorService` clear is right but its reasoning is worth a second line.** The story defers to `skillars-deferred-115`'s accepted duplicate-alert tradeoff, which is correct — but that method is also `@Scheduled` with no lock and is one of the nine cleared items, so it now carries a *permanent* accepted-risk status. If that is the intent, it should be recorded in `deferred-work.md` as accepted rather than only in two stories' prose.

---

## Recommended disposition

| AC | Finding stands? | Fix correct? | Action |
|---|---|---|---|
| AC1 | Yes, but severity overstated (B1) and the Path B mechanism is wrong (H1) | Yes — `@SchedulerLock` | Rewrite the failure scenario around the detached-`merge`/status-overwrite argument; drop the Path B doubling claim; fix the "existing tests green" promise (B2) |
| AC2 | Yes, but severity overstated (B1); analysis incomplete (H2, H3) | Yes — `@SchedulerLock` | Add the `resetStaleClaimed` path and the duplicate `video_deletion_log` insert; state the 404-as-success fact; fix the property name; fix the "existing tests green" promise (B2) |
| AC3 F1 | Yes | Incomplete — see H5 | Add a `MAX_BATCHES_PER_RUN` cap (option a) before sizing `lockAtMostFor`, or record the risk explicitly |
| AC3 F2 | Yes | Yes | Add the `SchedulerLockTransactionOrderingIT` advisor-ordering task and the keep-`@Transactional` note (B3); drop the vacuous "existing tests green" line |
| AC3 F3 | Yes | Adequate | Fix the `extendedAt`-only justification; drop the non-existent `PaymentPendingSweeper` test pointer; record why re-fetch was declined (M2) |
| AC4 | Yes | Yes | Extend the grep to all five classes |

**Also add across the story:** the lock `name` strings (B2.2), the corrected 60-second sizing precedent table (H4), and a Dev Notes entry that every direct test invocation of a newly-locked method needs `releaseSchedulerLock(...)` first.

**Not found:** no false-positive finding. Every bug the story names exists in the code as described, modulo the mechanism correction in H1 and the severity correction in B1. The three fixes are the right ones and the bundling convention is applied consistently with `-117`/`-118`/`-119`.
