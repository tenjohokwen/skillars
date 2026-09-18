# Story: Strike-Timing, Scheduler-Lock-Config & Envers Audit-Gap Fixes

**Story Key:** `skillars-deferred-123-strike-timing-scheduler-lock-config-and-envers-audit-gap-fixes`
**Epic:** Deferred Work
**Priority:** High (two live timing/consistency bugs in the coach-strike path, one genuine
double-processing exposure shared by two outbox processors, one codebase-wide scheduler
misconfiguration that fails silently, and one Envers schema/annotation mismatch of unknown runtime
severity — plus one owner-decided documentation-only closure and one test-coverage gap closure)
**Status:** ready-for-dev
**Created:** 2026-09-18

---

## Provenance & Scoping (read before starting)

This story mines the two most recent, still-open code-review deferrals in `deferred-work.md` —
**"Deferred from: code review of skillars-deferred-121-coach-enforcement-status-toctou-lock-gap"
(2026-09-18)** and **"Deferred from: code review of skillars-deferred-122-coach-enforcement-round-2-
and-user-cleanup-fixes" (2026-09-18)** — per the established "read same-day code-review deferrals
before drawing scope" convention, plus two older still-open items the deferred-121 review explicitly
cross-referenced (the `lockAtLeastFor`-vs-tunable-cadence gap from `code review of
skillars-deferred-120`, and the `claimed_at`/eligibility-vs-claim-time gap it names as "the next time
either class's claim mechanism is touched").

**All line numbers below were re-verified against `master@6ce2827c`** (the just-merged
`skillars-deferred-122` PR #210) **at story-creation time, not copied from the ledger.**
`AdminCoachEnforcementService.java`'s own comment growth during `skillars-deferred-122`'s code-review
response shifted several of the ledger's `:NNN` citations again — `issueManualStrike`'s unlocked
`findById` is now at `:244-245` (the ledger's stale `:192-193`), and `deleteStrike` now spans
`:267-366`. Do not trust the ledger's own citations against this file without re-checking HEAD.

**Ledger `[CLOSED by ...]` / `[DECIDED]` sweep:** grepped the whole file for `CLOSED by` before
selecting items — none of the hits touch any file or symbol this story scopes (`deploy-*`/CI items
only). `ModerationSlaMonitorService`'s no-`@SchedulerLock` bullet is `[DECIDED]` (accepted tradeoff)
and correctly **not** picked up. `reinstateCoach`'s stale-vs-fresh-suspension bullet is also
`[DECIDED: skillars-deferred-122]` and correctly **not** picked up again.

**Owner decisions taken live with the user (AskUserQuestion) before drafting** — see each AC for the
full reasoning:
1. Four items get direct fixes (AC1, AC2, AC3, AC6); one gets a boot-time validation sweep at full
   codebase width, not narrowed to the two ledger-named processors (AC4); one gets
   investigate-then-fix rather than document-only (AC5); one gets an explicit accepted-risk
   documentation closure, no functional code change (AC7).

### Group A — `AdminCoachEnforcementService` / `ReliabilityStrikeService` timing gaps

Two of the three still-open findings from `skillars-deferred-121`'s code review (see
`deferred-work.md`, "code review of skillars-deferred-121…") are genuine, narrowly-scoped bugs with no
schema change and no product ambiguity. The third (`PessimisticLockRetryer`'s DELETE-wait) is real but
narrow and self-bounding in practice — the owner decided to document it, not add new locking
mechanism.

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `issueManualStrike` applies no coach-status guard between its unlocked existence check and the strike write | `AdminCoachEnforcementService.java:244-253` | AC1 |
| The 30-day strike-count window origin is evaluated *after* the coach-row lock wait, so it slides under contention | `AdminCoachEnforcementService.java:293,303`, `ReliabilityStrikeService.java:88,91` | AC2 |
| The strike DELETE's wait sits outside the retry/savepoint guarantee and is invisible to the `persistence.lock_retry` metric | `PessimisticLockRetryer.java:132-134`, `CoachReliabilityStrikeRepository.java:44` (`deleteByIdAndCoachId`) | AC7 (document only) |

### Group B — Outbox/DLQ processor claim-time correctness (unrelated module, bundled per this
project's "do not create small stories" convention)

`skillars-deferred-120`'s 2026-09-17 code review confirmed this shape exists in both
`VideoDeletionOutboxProcessor` and `RadarCompositeDlqProcessor`, and explicitly deferred the real fix
("a Flyway migration, deliberately out of scope for a lock-parity story") to "the next time either
class's claim mechanism is touched." This story is that next time.

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `resetStaleClaimed` keys on `next_retry_at` (eligibility time), not claim time — a real double-processing path for backlogged rows once a run legitimately overruns its lock | `VideoDeletionOutboxRepository.java:43-48`, `RadarCompositeDlqRepository.java:38-45` | AC3 |

### Group C — Scheduler-lock/cadence config safety (codebase-wide, unrelated module)

`skillars-deferred-120`'s review flagged this as "worth one sweep rather than a per-site fix." This
story's own audit (below) found the real risk surface is narrower than "all `@Scheduled` methods":
only schedulers pairing a **Spring-property-tunable cadence** (`fixedDelayString`) with a **hardcoded**
`@SchedulerLock(lockAtLeastFor = ...)` are exposed — a scheduler with no `@SchedulerLock`, or one whose
`lockAtLeastFor` is `PT0S`, cannot be undercut by lowering its delay.

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| 7 schedulers hardcode `lockAtLeastFor` against a separately tunable `fixedDelayString` property with no boot-time cross-check | see AC4's table | AC4 |

### Group D — Envers audit-schema mismatch (unrelated module, surfaced by `skillars-deferred-122` AC9's
investigation, not fixed there)

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `User.skillarsRole`/`verificationStatus` are `@Audited` (no `@NotAudited`) but `main.user_aud` has no matching columns | `User.java:139-146`, `V138__baseline_schema.sql:1289-1290` (entity columns), `:1311-1346` (`user_aud` definition, no match) | AC5 |

### Group E — Test-coverage closure (same file `skillars-deferred-122` AC3 just added a test for)

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `AdminCoachEnforcementServiceIsolationTest` proves the `@Transactional(isolation = REPEATABLE_READ)` annotation is present, not that a concurrent writer's torn read is actually prevented at runtime | `AdminCoachEnforcementServiceIsolationTest.java` (whole file), `AdminCoachEnforcementService.java:86-112,373-402` | AC6 |

**Explicitly out of scope:** `ModerationSlaMonitorService`'s no-`@SchedulerLock` decision (already
`[DECIDED]`); `reinstateCoach` vs. concurrent suspension (already `[DECIDED]`); any further
`AdminCoachEnforcementService` refactor beyond AC1/AC2; the `Reconciliation­WorkerScheduler
.sweepOrphanedProviderAssets` scheduler, whose `lockAtLeastFor = "PT0S"` structurally cannot be
undercut (confirmed, excluded from AC4's table below).

---

## AC1 — `issueManualStrike` coach-status guard

**Problem:** `AdminCoachEnforcementService.issueManualStrike` (`:230-264`) checks only that the coach
exists (`:244-245`, deliberately unlocked — see the existing comment on why it must stay unlocked, a
self-deadlock risk against `ReliabilityStrikeService.issue`'s `REQUIRES_NEW`). Nothing rejects the call
if the coach is already `SUSPENDED` or `DEACTIVATED`: an admin can issue a manual strike against a
coach who is already suspended (no enforcement value — the coach is already off the marketplace) or
deactivated (the account is gone).

**Fix:** After the existence check, read `coach.getStatus()` from the same unlocked read (no new
query) and reject with `ResponseStatusException(HttpStatus.CONFLICT, ...)` if the status is
`SUSPENDED` or `DEACTIVATED`. Allow `ACTIVE`, `PENDING_REVIEW`, and `REDUCED` (a coach already in one
of these still legitimately accrues strikes — that is how they got there, and `deleteStrike`'s own
tiering logic already assumes strikes can land on a `PENDING_REVIEW`/`REDUCED` coach). Keep this read
unlocked — do not add `findByIdForUpdate` here (the existing comment's self-deadlock warning applies
identically to a status read as to an existence read; verify the warning's reasoning still holds and
extend the comment to cover the new check, don't duplicate it).

**Task:**
1. Add the status guard in `issueManualStrike`, immediately after the existence check.
2. Update the class-level warning comment on the unlocked read (`:235-243`) to note it now also backs
   this status guard, not only the existence check.
3. Add tests to `ManualStrikeIT`: issuing a manual strike against a `SUSPENDED` coach → 409; against a
   `DEACTIVATED` coach → 409; against `PENDING_REVIEW`/`REDUCED`/`ACTIVE` → still succeeds (regression
   guard — don't accidentally over-restrict).

---

## AC2 — 30-day strike-count window timestamp capture before lock wait

**Problem:** Three call sites compute the 30-day rolling-strike-count window as
`OffsetDateTime.now().minusDays(30)` **after** waiting on a pessimistic lock:
- `AdminCoachEnforcementService.deleteStrike:303` (cutoff computed after `:293`'s
  `lockRetryer.withBoundedRetry`)
- `ReliabilityStrikeService.issue:91` (window computed after `:88`'s `lockRetryer.withBoundedRetry`)

Under lock contention (the `PessimisticLockRetryer` budget is up to ~3.2s worst-case), the window's
start silently slides by however long the wait took. A strike created seconds before the 30-day
boundary can be counted for an uncontended call and excluded for a contended one on the *same*
coach — the escalation/de-escalation decision becomes a function of incidental lock-wait duration, not
just wall-clock time and strike history.

**Fix:** In both methods, capture the cutoff (`OffsetDateTime.now().minusDays(30)`) **before** calling
`lockRetryer.withBoundedRetry`, into a local variable, and use that local in the count query. This
mirrors `skillars-deferred-122` AC1's own "one cutoff local, captured once" fix for `deleteStrike`'s
*other* `now()` call (`:303`) — that fix hoisted two separate `now()` calls into one; this AC hoists
the remaining one across the lock-wait boundary instead of just across the two query call sites.

**Note on `getCoachesUnderEnforcement:389`:** this method takes no lock (its `REPEATABLE_READ`
transaction is read-only, no `findByIdForUpdate`), so its `since = OffsetDateTime.now().minusDays(30)`
is not subject to the same lock-wait-slide bug. Leave it unchanged — do not move it "for consistency"
without a reason; a no-op change invites review churn.

**Task:**
1. `AdminCoachEnforcementService.deleteStrike`: move the cutoff capture (currently `:303`) to before
   `:293`'s `withBoundedRetry` call. Verify this does not change the out-of-window-guard's semantics —
   `strikeCreatedAt` (captured at `:279`, before either) is unaffected either way.
2. `ReliabilityStrikeService.issue`: introduce a cutoff local before `:88`'s `withBoundedRetry` call,
   replacing the inline `OffsetDateTime.now().minusDays(30)` at `:91`.
3. No test currently pins the *ordering* of these two operations (only the eventual count). Add a unit
   or IT-level assertion (whichever this codebase's existing coverage for these two methods makes
   cheaper) that the cutoff is computed before the lock wait — e.g. a Mockito `InOrder` verification on
   a clock/lock seam if one exists, or, if no such seam exists, a code-comment-anchored regression note
   plus reliance on the existing concurrency ITs (`AdminCoachEnforcementConcurrencyIT`,
   `AdminCoachEnforcementServiceIsolationTest`) continuing to pass. Do not introduce a new test seam
   (e.g. an injectable `Clock`) solely for this AC if the existing test suite already exercises the
   changed code path — that would be a larger refactor than this fix warrants; use judgment and note
   the choice in dev notes.

---

## AC3 — `claimed_at` column: key stale-claim recovery on claim time, not eligibility time

**Problem:** Both `VideoDeletionOutboxRepository.resetStaleClaimed` (`:43-48`) and
`RadarCompositeDlqRepository.resetStaleClaimed` (`:38-45`) reclaim rows via
`WHERE status = 'CLAIMED' AND next_retry_at < :deadline`. Neither `claimPendingBatch` (`VideoDeletion
OutboxRepository:15-29`, `RadarCompositeDlqRepository:16-29`) stamps `next_retry_at` when it claims a
row — so `next_retry_at` is the row's *eligibility* timestamp, not its *claim* timestamp. A row that
was already backlogged (eligible well before this run started, e.g. behind a full batch, or building
up during an outage) is judged "stale" — and un-claimed out from under a still-in-flight processor —
based on how long it sat in the queue, not how long it has actually been claimed. A concurrent tick's
`resetStaleClaimed` can then free it while the first instance is still processing it, and
`claimPendingBatch` immediately re-claims it: genuine duplicate `deleteAsset`/`recalculateComposite`
work, not merely a disjoint-batch race. `@SchedulerLock` prevents concurrent *invocations* of the same
scheduled method but does not close this path once a run legitimately overruns `lockAtMostFor` (crash
recovery, a genuinely slow batch) — the exact scenario `resetStaleClaimed` exists to recover from.

**Fix:** Add a `claimed_at timestamp without time zone` column to both `main.video_deletion_outbox` and
`development.radar_composite_dlq`. `claimPendingBatch` stamps `claimed_at = :now` on every row it
claims (same UPDATE, one more `SET` clause). `resetStaleClaimed` keys its staleness check on
`claimed_at < :deadline` instead of `next_retry_at < :deadline`, and only for rows where `claimed_at IS
NOT NULL` (a row that has never been claimed cannot be a stale claim). On successful completion
(`completeRow`/`completeRowWithNullAsset`/`processRow`'s success paths and `RadarCompositeDlqProcessor
.processRow`'s success path), clear `claimed_at` back to `NULL` — a completed row is no longer
"claimed" in any sense the staleness check should consider, and leaving a stale non-null value behind
would give a false claim-age reading if the row were ever re-queued by an unrelated path.

**Task:**
1. New Flyway migration `V144__outbox_dlq_claimed_at.sql`: `SET lock_timeout = '5s';` then two additive
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS claimed_at timestamp without time zone` statements (one per
   table), per `docs/deployment/migration-conventions.md` rule 1 (expand/contract) — mirror
   `V143__user_cleanup_failed_at.sql`'s own header-comment style (explain what, why, and the timestamp
   type choice matching this table's existing convention).
2. Add `claimedAt` (`Instant`) to `VideoDeletionOutbox` and `RadarCompositeDlqEntry` entities.
3. `VideoDeletionOutboxRepository.claimPendingBatch` / `RadarCompositeDlqRepository.claimPendingBatch`:
   add `claimed_at = :now` to the `UPDATE ... SET` clause (both already take `:now` as a parameter for
   the batch-claim's own bookkeeping — verify whether they already do or need it added; re-check
   against current source before assuming).
4. Both repositories' `resetStaleClaimed`: change the `WHERE` clause to `status = 'CLAIMED' AND
   claimed_at IS NOT NULL AND claimed_at < :deadline`.
5. Both processors: clear `claimed_at` to `null` in every success-path `save()` call
   (`VideoDeletionOutboxProcessor.completeRow`, `.completeRowWithNullAsset`, and the drill-refCount
   short-circuit branch in `.processRow`; `RadarCompositeDlqProcessor.processRow`'s success branch).
   Leave `handleFailure` paths alone in both classes — a row still legitimately `PENDING`/retrying
   should keep `claimed_at` cleared too (it is no longer claimed once `handleFailure` sets status back
   to `PENDING`) — apply the same clear-on-non-CLAIMED-transition rule there as well, not just on
   `COMPLETED`.
6. Update both classes' existing Javadoc on `STALE_CLAIM_WINDOW`/the `resetStaleClaimed` call site to
   state the invariant now holds structurally (claim-time-keyed), not just "restored via a buffer" —
   the `skillars-deferred-120` 20-minute-buffer reasoning becomes a secondary safety margin, not the
   primary fix, once this lands.
7. Tests: extend or add IT coverage proving `resetStaleClaimed` no longer reclaims a row whose
   `next_retry_at` (eligibility) predates the stale window but whose `claimed_at` does not — i.e., a
   genuinely-just-claimed but long-backlogged row must **not** be reclaimed. Cover both processors
   (mirror whatever existing IT already exercises `claimPendingBatch`/`resetStaleClaimed` for each, if
   one exists — check for `VideoDeletionOutboxProcessorIT`/`RadarCompositeDlqProcessorIT` or similar
   before writing new IT scaffolding from scratch).

---

## AC4 — Boot-time validation: `lockAtLeastFor` vs. tunable scheduler cadence

**Problem:** A `@Scheduled(fixedDelayString = "${some.property:default}")` method's cadence is
operator-tunable via a Spring property, but its paired `@SchedulerLock(lockAtLeastFor = "PTxxS")` is a
Java-constant-expression string, unrelated to that property (ShedLock does not resolve `${...}`
placeholders in `lockAtLeastFor`/`lockAtMostFor` — verified: neither
`MethodProxyScheduledLockAdvisor` nor `SchedulerProxyScheduledLockAdvisor` in `shedlock-spring:7.10.1`
performs any property-placeholder or `Environment` resolution on these annotation attributes). If an
operator lowers the cadence property below the hardcoded `lockAtLeastFor` floor, ShedLock silently
holds the lock past the next tick(s), and those runs are dropped with **no error, no warning, no config
validation** — for a deletion/DLQ-processing scheduler, this means a real backlog with no operator
signal.

**Scope, decided with the owner:** a full sweep, not narrowed to the two ledger-named processors. This
story's own audit found the true risk surface is 7 sites (not "all `@Scheduled` methods" — a scheduler
with no `@SchedulerLock`, or a `lockAtLeastFor` of `PT0S`, cannot be undercut by any cadence value):

| Scheduler.method | Cadence property (default) | Hardcoded `lockAtLeastFor` |
|---|---|---|
| `OutboxService.sweep` | `app.outbox.sweep-ms` (300000ms) | `PT1M` |
| `QuotaReservationTimeoutService.expireStaleReservations` | `app.video.reservation-check-interval-ms` (60000ms) | `PT1M` — **zero margin at defaults** |
| `VideoDeletionOutboxProcessor.process` | `platform.video.deletion.outbox_poll_delay_ms` (60000ms) | `PT30S` |
| `RadarCompositeDlqProcessor.process` | `platform.development.radar_composite_dlq.poll_delay_ms` (60000ms) | `PT30S` |
| `EmailRetryScheduler.retryFailedEmails` | `email.retry.interval-ms` (60000ms) | `PT10S` |
| `OutboxPollerScheduler.pollAndProcess` | `app.storage.poller.fixed-delay-ms` (5000ms) | `PT2S` |
| `DeletionSchedulerService.processDeletions` | `app.storage.poller.fixed-delay-ms` (5000ms, **same property** as the row above — one operator change affects both) | `PT2S` |

Confirmed excluded: `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`
(`lockAtLeastFor = "PT0S"`, structurally safe); `UploadSessionExpiryScheduler`,
`WebhookEventProcessorScheduler`, `ModerationSlaMonitorService`, `ConfigService.scheduledRefresh`,
`AlertRuleCache.refresh`, `AlertEvaluationService.evaluate` (no `@SchedulerLock` at all — not exposed
to this specific gap, whatever their other properties).

**Fix:** Extend `ConfigStartupAssertion` (already injects `Environment env`, already runs on
`ApplicationReadyEvent`, already has a `failFastViolations` list and the `dev`-profile-gated throw) with
a second check block: for each row in the table above, read the property via
`env.getProperty(key, Long.class, defaultMs)`, parse the corresponding `lockAtLeastFor` as a
`Duration` via `Duration.parse(...)`, and if the configured value is less than the duration's
milliseconds, log an ERROR (naming both the property and the scheduler) and add a
`config.value.misconfigured` counter increment (tag `key` = the scheduler name, `reason` =
`"cadence_below_lock_floor"`) — following this class's existing `increment`-style pattern. Decide
per-row whether the violation is `failFast` (blocks boot outside `dev`) using the same judgment
`ConfigBounds.BoundedKey.failFast()` already applies elsewhere in this class: a dropped run that only
delays non-time-critical background work is a WARN-tier ERROR-log-only finding; a dropped run whose
effect is a growing backlog with a compliance/cost dimension (video/DLQ deletion, retry processing)
should block boot. Document the reasoning per row in code comments, the way `ConfigStartupAssertion`'s
existing cross-field check documents its own severity call.

**Task:**
1. Add a small internal record/holder type (e.g. `record SchedulerLockConfig(String schedulerName,
   String propertyKey, long defaultMs, Duration lockAtLeastFor, boolean failFast)`) and a
   `private static final List<SchedulerLockConfig>` literal for the 7 rows above.
2. Add the check loop to `ConfigStartupAssertion.onApplicationEvent`, following the existing
   structure (log, metric, conditionally add to `failFastViolations`).
3. Update the class Javadoc's "Known limitation" section — this new check has the identical
   `@Scheduled` tasks are registered during context refresh, before `ApplicationReadyEvent`" limitation
   already documented there for the `platform_config` checks; extend the existing paragraph rather than
   duplicating it.
4. Tests: extend `ConfigStartupAssertionTest` with cases for at least one `failFast` and one
   non-`failFast` row — a configured value at/above the floor (no violation), below the floor
   (violation, correct `failFast` behavior), and the shared-property pair (`OutboxPollerScheduler` /
   `DeletionSchedulerService`) both firing off one property change.

---

## AC5 — Envers `user_aud` schema/annotation mismatch: investigate and resolve

**Problem:** `User.java:139-146` declares `skillarsRole` (`@Column(name = "skillars_role")`) and
`verificationStatus` (`@Column(name = "verification_status")`) with **no** `@NotAudited`, on a class
annotated `@Audited` (`User.java:45`). `main.user_aud` (`V138__baseline_schema.sql:1311-1346`) has no
`skillars_role` or `verification_status` column. Both fields are set via ordinary entity setters
(`ParentRegistrationService`, `PlayerRegistrationService`, `CoachRegistrationService`,
`AdminBootstrapRunner`, `AccountSuspensionEventListener` — confirmed by grep, not bulk/native updates
that would bypass Hibernate's dirty-checking and therefore bypass Envers entirely), so every user
registration and every verification-status/role transition is a candidate for Envers to attempt writing
an audit row referencing these fields.

**This story does not yet know which of two things is true**, and `skillars-deferred-122`'s own
investigation deliberately did not run it down further:
- (a) Envers actually attempts to write `skillars_role`/`verification_status` into `user_aud` and this
  fails at runtime (a `SQLGrammarException`/similar) on every registration or role/status change — which
  would be a severe, currently-masked production bug (masked by something: caught and swallowed
  somewhere, an `@Async`/event-listener boundary eating the exception, or similar), or
- (b) Envers tolerates the mismatch in some way not yet understood (e.g. these fields are somehow
  excluded from the generated audit DML despite lacking `@NotAudited` — verify this isn't explained by
  inheritance: `User extends Customer`, confirm which class's fields these actually are and whether
  `@Audited` propagates as expected).

**Task:**
1. Investigate first — do not guess. Write (or run, if one already exists) a targeted IT that persists
   a new `User` with `skillarsRole`/`verificationStatus` set (mirroring `CoachRegistrationServiceIT` or
   similar) and directly queries `main.user_aud` afterward to observe actual behavior. Check application
   logs for swallowed exceptions around registration flows if the IT surprisingly passes.
2. **If (a)** — Envers genuinely fails or silently drops these fields: add the missing
   `skillars_role`/`verification_status` columns to `main.user_aud` via a new Flyway migration
   (`V145__user_aud_role_verification_status.sql` or the next free number after AC3's migration),
   matching the entity's actual column types/lengths. Re-run the investigation IT to confirm the audit
   row now round-trips correctly.
3. **If (b)** — genuinely harmless: document the actual mechanism found (not a restated guess) in a
   code comment on `User.java:139-146` and in this story's completion notes, so the next person who
   greps for this doesn't re-open the same investigation from zero.
4. Either way, delete the `deferred-work.md` bullet under "code review of
   skillars-deferred-121-coach-enforcement-status-toctou-lock-gap" that raised this (see AC8).

---

## AC6 — Genuine multi-connection isolation test for `getEnforcementProfile`/`getCoachesUnderEnforcement`

**Problem:** `AdminCoachEnforcementServiceIsolationTest` (whole file, added by `skillars-deferred-122`
AC3) proves via reflection that `@Transactional(isolation = Isolation.REPEATABLE_READ)` is present on
both methods. Its own Javadoc is explicit that this is "unverified behavior, annotation only" — it
cannot catch the torn-read AC3 exists to prevent if Spring's `validateExistingTransaction = false`
default ever silently discards the isolation request (documented risk: a future caller wrapping either
method in an ambient `@Transactional`/`TransactionTemplate` block).

**Fix, decided with the owner as worth the investment:** a genuine two-connection concurrency test,
not an instrumentation seam inside the production method. Rather than pausing execution mid-method
(which the prior story's Dev Notes correctly identified as needing a bespoke test-only hook), drive two
real JDBC connections directly at `REPEATABLE_READ` isolation against the same query pair
`getEnforcementProfile` issues:
- Connection A: `BEGIN; SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;` then run the coach-status
  SELECT `getEnforcementProfile` uses (`coachProfileRepository.findById`'s underlying SQL).
- Connection B (separate connection, separate transaction): update the same coach's `status` and
  strike table, `COMMIT`.
- Connection A: run the strike-count SELECT (`countByCoachIdAndCreatedAtAfter`'s underlying SQL) —
  assert it still reflects the **pre-B-commit** state (proving the snapshot), then `COMMIT`.

This proves the actual PostgreSQL `REPEATABLE READ` guarantee holds for this exact query pair without
requiring any change to production code — the guarantee is the database's, not the service method's;
the service method's only job (which the existing reflection test already covers) is to actually
request that isolation level.

**Task:**
1. New IT (e.g. `AdminCoachEnforcementSnapshotConsistencyIT`) using two raw `DataSource`/`Connection`
   instances (or `JdbcTemplate` against two independently-obtained connections — check how other
   concurrency ITs in this codebase obtain a second raw connection, e.g.
   `AdminCoachEnforcementConcurrencyIT` or `BookingServiceConcurrencyIT`, and follow the established
   pattern rather than inventing a new one).
2. Cover both `getEnforcementProfile` and `getCoachesUnderEnforcement`'s shared torn-read shape — a
   single parameterized test or two near-identical tests, whichever matches this codebase's existing
   convention for near-duplicate coverage.
3. Keep the existing `AdminCoachEnforcementServiceIsolationTest` — it still usefully pins the annotation
   against silent removal; this AC adds coverage, it does not replace it. Update its Javadoc's "Why an
   annotation-presence test" section to point at the new IT as the runtime-behavior proof it was
   waiting on.

---

## AC7 — Strike DELETE wait: document as accepted risk (no functional change)

**Problem:** `AdminCoachEnforcementService.deleteStrike`'s `strikeRepository.deleteByIdAndCoachId`
call (`:285`) is a bulk `@Modifying` JPQL `DELETE` with no `NOWAIT`/lock-timeout. If two admins call
`deleteStrike` for the *same* strike concurrently, the second `DELETE` blocks on Postgres's ordinary row
lock until the first transaction commits or rolls back, then affects 0 rows (translated to a clean 404
by `skillars-deferred-122` AC2 — already correct). The wait itself is real but narrow: it only
manifests for a genuine same-strike concurrent-delete race, and its duration is naturally bounded by
the winning transaction's own work, which is itself lock-retry-bounded (`PessimisticLockRetryer`'s
~3.2s worst-case budget for the coach-row lock inside that same transaction) — so in practice this wait
cannot run unboundedly long under this codebase's current call shape.

**Decided with the owner:** document, no new locking mechanism. The residual risk (a future change to
`deleteStrike` or its callees making the winning transaction's own work open-ended) is real but
speculative, and the `persistence.lock_retry` metric's blind spot here (this wait isn't measured by
it) is a metric-completeness gap, not a functional bug.

**Task:**
1. Add a code comment at `CoachReliabilityStrikeRepository.deleteByIdAndCoachId` (near the existing
   `skillars-deferred-122 AC2` comment) recording: the DELETE has no `NOWAIT`, why that's accepted (the
   self-bounding argument above), and what would invalidate the acceptance (any future change making
   the winning transaction's own work unbounded).
2. Update the `deferred-work.md` bullet (see AC8) to a `[DECIDED: accepted risk — skillars-deferred-123]`
   annotation rather than deleting it outright — the underlying gap still exists, only the decision not
   to fix it is now closed.

---

## AC8 — `deferred-work.md` ledger closeout

Update `_bmad-output/implementation-artifacts/deferred-work.md`:

| Ledger item | Disposition |
|---|---|
| `code review of skillars-deferred-121…`: "The strike DELETE's wait is outside the retry/savepoint guarantee" | Annotate `[DECIDED: accepted risk — skillars-deferred-123]`, do not delete (AC7) |
| `code review of skillars-deferred-121…`: "`issueManualStrike` applies no coach-status guard" | Delete (AC1) |
| `code review of skillars-deferred-121…`: "The 30-day count window origin slides with contention" | Delete (AC2) |
| `code review of skillars-deferred-121…`: "Pre-existing `main.user_aud` Envers-coverage gap" | Delete (AC5) |
| `code review of skillars-deferred-120…`: "`lockAtLeastFor` values are hardcoded against tunable `fixedDelayString` cadences" | Delete (AC4) |
| `code review of skillars-deferred-120…`: "`resetStaleClaimed`/`claimPendingBatch`'s shared claim idiom keys stale-claim recovery on eligibility time, not claim time" (both occurrences — the finding is stated twice in that section) | Delete (AC3) |
| `code review of skillars-deferred-122…`: "AC3's REPEATABLE_READ coverage is annotation reflection only" | Delete (AC6) |
| `code review of skillars-deferred-120…`: `ModerationSlaMonitorService` no-`@SchedulerLock` `[DECIDED]` note | Leave — confirmed still correctly out of scope, untouched by this story |

Add a `## Last audit: 2026-09-18 (skillars-deferred-123 story creation)` narrative block, matching this
file's existing convention, summarizing what was checked and closed (link back to this story's
Provenance section rather than duplicating it).

---

## Dev Notes

**Cross-AC dependencies:** AC1 and AC2 both touch `AdminCoachEnforcementService.issueManualStrike`'s
neighborhood and `deleteStrike`'s neighborhood respectively but do not interact — implement and test
independently. AC3 (video/radar claim time) and AC4 (scheduler lock config) are both in the
scheduler/outbox space but are independent fixes to independent bugs — do not conflate "add
`claimed_at`" with "fix `lockAtLeastFor`"; a reviewer should be able to revert either without touching
the other. AC5 (Envers) is standalone and must be **investigated before any migration is written** —
do not pre-emptively add `user_aud` columns on the assumption Envers is broken; confirm first. AC6 adds
test coverage only, no production code change, and has no dependency on any other AC. AC7 is
documentation-only.

**Migration numbering:** confirm `V144` is the next free Flyway version at implementation time (verify
against the actual `src/main/resources/db/migration/` directory, not this story's assumption) — a
concurrent story could have claimed it since this story was drafted. AC5's migration (if needed per
its investigation outcome) takes the next number after AC3's.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for this
project. Run each AC's own targeted test class(es) locally during implementation as needed.

**Migration-lint:** every new/changed migration must include `SET lock_timeout = '...'` per this repo's
`migration-conventions.md` rule and `MigrationConventionLintTest`'s enforcement — confirmed via
`V143`'s and `V140`'s precedent; do not omit it the way `skillars-deferred-122`'s code review caught
happening once already.

---

## Story Completion Status

Ultimate context engine analysis completed - comprehensive developer guide created.
