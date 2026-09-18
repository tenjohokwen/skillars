# Story: Strike-Timing, Scheduler-Lock-Config & Envers Audit-Gap Fixes

**Story Key:** `skillars-deferred-123-strike-timing-scheduler-lock-config-and-envers-audit-gap-fixes`
**Epic:** Deferred Work
**Priority:** High (one enforcement-suppression gap and one narrow timing nondeterminism in the
coach-strike path, one genuine double-processing exposure shared by two outbox processors, one
codebase-wide scheduler-lock-floor misconfiguration that is silently un-correctable by an operator, and
one Envers schema/annotation mismatch of unknown runtime severity — plus one owner-decided
documentation-only closure and one test-coverage gap closure)
**Status:** done
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
1. Four items get direct fixes (AC1, AC2, AC3, AC6); one gets investigate-then-fix rather than
   document-only (AC5); one gets an explicit accepted-risk documentation closure, no functional code
   change (AC7).

**Senior-dev audit correction (2026-09-18, post-creation, see `story-review.md`):** the story as
originally drafted contained one false premise (AC4) and one incomplete fix (AC3) that were caught
before implementation started and corrected below, plus several smaller fixes. Independently
re-verified against HEAD, including decompiling the resolved `shedlock-spring:7.10.1` artifact:
1. **AC4's entire original premise was wrong.** ShedLock *does* resolve `${...}` properties in
   `lockAtLeastFor`/`lockAtMostFor` — `SpringLockConfigurationExtractor.getValue()` calls
   `embeddedValueResolver.resolveStringValue(...)` on the annotation string before converting it to a
   `Duration`, and that resolver is non-null in a Spring app (`LockConfigurationExtractorConfiguration`
   implements `EmbeddedValueResolverAware`). AC4 is rewritten from scratch below: **property-ize the
   lock floors** at the declaration site instead of building a boot-time detector for an
   un-detectable-because-fixable problem. This also folds in three more exposed schedulers the
   original audit missed (cron-tunable, not just `fixedDelayString`-tunable).
2. **AC3 as originally drafted did not close the bug it exists to close.** `findClaimedBatch()` in
   both repositories is globally scoped (`WHERE status = 'CLAIMED'`, no per-run filter), so a second
   instance's tick would still pick up and duplicate-process the first instance's still-in-flight rows
   even after `claimed_at` lands. AC3 now also scopes `findClaimedBatch` to the current run's own
   claim.
3. **AC1's guard replaced with record-and-suppress**, resolving both an internal deny-list/allow-list
   contradiction and an asymmetry where the automatic (cancellation/no-show) strike path had no guard
   at all while the manual path would have hard-rejected.
4. **AC6's test design replaced** with an isolation-probe that reads the actual transaction isolation
   level from inside the real service call, since the originally-specified two-connection JDBC test
   drove hand-written SQL and could not have detected the failure mode (Spring silently discarding the
   isolation request) that AC6 exists to catch.
5. Smaller corrections throughout: `claimed_at`'s column type (`timestamptz`, matching both tables'
   existing convention, not `timestamp without time zone`), a self-contradictory task in AC3, stale
   citations in AC7/AC8/Group D, and scope gaps in AC5/AC8. See each AC below.

### Group A — `AdminCoachEnforcementService` / `ReliabilityStrikeService` timing gaps

Two of the three still-open findings from `skillars-deferred-121`'s code review (see
`deferred-work.md`, "code review of skillars-deferred-121…") are genuine, narrowly-scoped bugs with no
schema change and no product ambiguity. The third (`PessimisticLockRetryer`'s DELETE-wait) is real but
narrow and self-bounding in practice — the owner decided to document it, not add new locking
mechanism.

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `ReliabilityStrikeService.issue` applies no coach-status guard, so a `SUSPENDED`/`DEACTIVATED` coach still escalates on new strikes exactly like an active one, on both the manual (`AdminCoachEnforcementService.issueManualStrike:253`) and automatic (`CancellationRefundService:94`) call paths | `ReliabilityStrikeService.java:88-110` | AC1 |
| The 30-day strike-count window origin is evaluated *after* the coach-row lock wait, so it slides under contention | `AdminCoachEnforcementService.java:293,303`, `ReliabilityStrikeService.java:88,91` | AC2 |
| The strike DELETE (`AdminCoachEnforcementService.deleteStrike:285`) executes immediately, eight lines before `deleteStrike:293`'s `withBoundedRetry`/`Timer.start` — its wait sits outside the retry/savepoint guarantee and is invisible to the `persistence.lock_retry` metric | `AdminCoachEnforcementService.java:285,293`, `CoachReliabilityStrikeRepository.java:45-47` (`deleteByIdAndCoachId`) | AC7 (document only) |

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
only schedulers pairing a **Spring-property-tunable cadence** (`fixedDelayString` *or* `cron`) with a
**hardcoded** `@SchedulerLock(lockAtLeastFor = ...)` are exposed — a scheduler with no
`@SchedulerLock`, or one whose `lockAtLeastFor` is `PT0S`, cannot be undercut by lowering its delay.

**Corrected premise (see the sweep note above):** ShedLock *does* resolve `${...}` properties in
`lockAtLeastFor`/`lockAtMostFor` (verified by decompiling `shedlock-spring:7.10.1`'s
`SpringLockConfigurationExtractor`). The fix is therefore not a boot-time detector but making the
floors themselves properties, at the same 10 sites (7 from the original audit + 3 more found
correcting the audit's `fixedDelayString`-only filter to the real criterion, property-tunable
cadence).

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| 10 schedulers hardcode `lockAtLeastFor`/`lockAtMostFor` against a separately tunable cadence property, with no way for an operator to raise the floor to match a lowered cadence | see AC4's table | AC4 |

### Group D — Envers audit-schema mismatch (unrelated module, surfaced by `skillars-deferred-122` AC9's
investigation, not fixed there)

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `User.skillarsRole`/`verificationStatus` are `@Audited` (no `@NotAudited`) but `main.user_aud` has no matching columns | `User.java:138-144`, `V138__baseline_schema.sql:1289-1290` (entity columns), `:1311-1346` (`user_aud` definition, no match) | AC5 |

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

## AC1 — Suppress strike escalation for a coach already off the marketplace, on both strike paths

**Problem (corrected 2026-09-18 — see the story-level correction note):** the original draft of this
AC guarded only `AdminCoachEnforcementService.issueManualStrike`'s unlocked existence check
(`:244-245`) and proposed rejecting the manual call with `409 CONFLICT` if the coach was already
`SUSPENDED`/`DEACTIVATED`. Two problems with that shape, both caught before implementation:
- **It missed the automatic path entirely.** `ReliabilityStrikeService.issue` (`:50-113`) has exactly
  two callers — the manual path this AC guarded (`AdminCoachEnforcementService.java:253`) and the
  cancellation/no-show listener path (`CancellationRefundService.java:94`, `Propagation.REQUIRES_NEW`,
  no retry). A `SUSPENDED` coach would still silently escalate — and could even be knocked back from
  `SUSPENDED` toward `PENDING_REVIEW` — via a no-show recorded after suspension, while the identical
  manual call was rejected. Same "no enforcement value" rationale, guard in only one of two places.
- **A strike is also a compliance record, not only an enforcement trigger.** Rejecting the call outright
  means a no-show processed after a coach is suspended can never be recorded at all, including for the
  rolling 30-day window if the coach is later reinstated.

**Fix:** move the check into `ReliabilityStrikeService.issue` itself — the one place both call paths
already converge, and which already takes the authoritative locked read (`coachProfileRepository
.findByIdForUpdate`, `:88-89`). The strike row is still written unconditionally, exactly as today
(`:62`, before the lock); only the two escalation blocks (`:94-101` promoting to `PENDING_REVIEW`,
`:102-109` promoting to `REDUCED`) are skipped when the coach is already `SUSPENDED` or `DEACTIVATED`
(a **deny-list** — every other status, including `DRAFT`, keeps today's escalation behavior; a
deny-list fails open for any status added later, the safer default for an admin/automated-enforcement
action). This also fixes a latent regression the current code has for `SUSPENDED` specifically: today,
a `SUSPENDED` coach hitting the suspension-count threshold again satisfies `coach.getStatus() !=
PENDING_REVIEW` and would be *demoted* back to `PENDING_REVIEW` — the new guard prevents that as a
side effect.

With the check living in `issue()`, `issueManualStrike`'s existing unlocked existence check
(`:244-245`) is unchanged — it still exists purely for the 404, no new guard or comment is needed
there, and the self-deadlock note on why that read must stay unlocked still applies to it unchanged.

**Task:**
1. In `ReliabilityStrikeService.issue`, after the locked `coach` read (`:88-89`), compute
   `boolean alreadyOffMarketplace = coach.getStatus() == CoachProfileStatus.SUSPENDED
   || coach.getStatus() == CoachProfileStatus.DEACTIVATED;` and gate both escalation blocks
   (`:94-101`, `:102-109`) on `!alreadyOffMarketplace`. The strike `save()` at `:62` is untouched.
2. Add a code comment at the guard recording: why suppression (not rejection) was chosen — the strike
   remains a durable compliance record even when it can't change a coach's status — and that this is
   the single enforcement point for both the manual and automatic strike paths, so a future third
   caller of `issue()` gets the same behavior for free.
3. Add tests: a strike issued against a `SUSPENDED` coach is persisted (readable via
   `getCoachStrikes`/repository) but the coach's status and `statusChangedAt` are unchanged and no
   `StrikeThresholdReachedEvent`/`CoachVisibilityReducedEvent` is published; same for `DEACTIVATED`.
   Cover both the manual path (`ManualStrikeIT` — issuing against `SUSPENDED`/`DEACTIVATED` now
   succeeds and the strike is recorded, not a 409) and the automatic path (whatever existing test
   exercises `CancellationRefundService`'s no-show/cancellation listeners calling `issue()`, or a new
   unit test directly against `ReliabilityStrikeService.issue` if none does). Regression guard: strikes
   against `DRAFT`/`ACTIVE`/`PENDING_REVIEW`/`REDUCED` still escalate exactly as before.

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

**Severity note (corrected 2026-09-18):** this is real nondeterminism worth removing, but narrower than
originally framed — the exposure is a window-origin skew bounded by the ~3.2s retry budget against a
30-day window, so it changes an outcome only for a strike whose `created_at` falls in that
sub-second-to-3-second band, 30 days back. It is not a live bug for the general case, just a source of
non-repeatable disagreement in that narrow band.

**Residual scope, stated explicitly rather than implied:** hoisting the capture above
`withBoundedRetry` reduces the variance, it does not eliminate it — the cutoff is still
`OffsetDateTime.now()` at an arbitrary point in the method. After this fix there remain three separate
capture points for the same conceptual window: `deleteStrike` (pre-lock, this fix),
`getEnforcementProfile:91` (inline, deliberately left alone — read-only, no lock wait), and
`getCoachesUnderEnforcement:389` (inline, deliberately left alone, same reason). The count an admin
reads off the enforcement screen can therefore still disagree with the count `deleteStrike` acts on
moments later. That is an accepted, defensible gap — not eliminated by this AC — because introducing a
shared clock/seam across all three for a sub-3-second window is a larger refactor than this fix
warrants.

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
3. No test currently pins the *ordering* of these two operations (only the eventual count). Decision
   (stated here, not left as an option): do not introduce a new test seam (e.g. an injectable `Clock`)
   solely for this AC — no such seam exists today, and adding one would be a larger refactor than this
   fix warrants. The ordering is covered by a code comment anchored at the moved cutoff-capture line in
   both methods (stating why it must stay before `withBoundedRetry`), and the regression guard is the
   existing concurrency ITs (`AdminCoachEnforcementConcurrencyIT`,
   `AdminCoachEnforcementServiceIsolationTest`) continuing to pass. No new test is added for ordering
   specifically.

---

## AC3 — `claimed_at` column: key stale-claim recovery *and* the claimed-batch fetch on claim time, not eligibility time

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

**Correction (2026-09-18, before implementation — see the story-level correction note):** adding
`claimed_at` and rekeying `resetStaleClaimed` alone does **not** close the double-processing path, and
would have replaced an accurate warning with a false one. Both repositories'
`findClaimedBatch()` (the method each processor calls right after `claimPendingBatch` to fetch what it
just claimed) is globally scoped — `SELECT * ... WHERE status = 'CLAIMED'`, no per-run filter, no
`LIMIT`. Walk the scenario this AC exists to fix, **with only the `claimed_at`/`resetStaleClaimed`
change applied**: Instance A holds rows `CLAIMED` with a recent `claimed_at`, still processing past
`lockAtMostFor`. A's lock expires; Instance B's tick fires. B's `resetStaleClaimed` correctly matches
nothing (`claimed_at` is recent) — the rekeyed check works. But B's `findClaimedBatch()` still returns
**A's in-flight rows**, because their status is `CLAIMED` and that query has no notion of whose run
claimed them. B processes them: duplicate `deleteAsset`/`recalculateComposite`, duplicate
`video_deletion_log` rows. `claimed_at` alone removes one path into that duplicate processing but
leaves the unscoped fetch as a second, independent one. This AC therefore also scopes the fetch.

**Fix:** Add a `claimed_at timestamp with time zone` column to both `main.video_deletion_outbox` and
`development.radar_composite_dlq` — `timestamptz`, matching every existing timestamp column on both
tables (`next_retry_at`, `created_at`; both entities already map these as `Instant`). `claimPendingBatch`
stamps `claimed_at = :now` on every row it claims (same UPDATE, one more `SET` clause), and the
processor passes a single `Instant runClaimedAt` for the whole tick — the *same* value used for both
the claim UPDATE and the subsequent fetch, so a run's own claim is identifiable. `findClaimedBatch` is
scoped to `status = 'CLAIMED' AND claimed_at = :claimedAt`, with a `LIMIT :batchSize`, making the fetch
genuinely this run's own claim and closing the duplicate-processing path completely, not just the
`resetStaleClaimed` half of it. (A `claimed_by` run-token is the more conventional shape if a future
change could ever make two runs share a timestamp; a single `Instant.now()` per tick is sufficient here
and cheaper — do not over-build this.) `resetStaleClaimed` keys its staleness check on `claimed_at <
:deadline` instead of `next_retry_at < :deadline`, and only for rows where `claimed_at IS NOT NULL` (a
row that has never been claimed cannot be a stale claim). On successful completion
(`completeRow`/`completeRowWithNullAsset`/`processRow`'s success paths and `RadarCompositeDlqProcessor
.processRow`'s success path), clear `claimed_at` back to `NULL` — a completed row is no longer
"claimed" in any sense the staleness check should consider, and leaving a stale non-null value behind
would give a false claim-age reading if the row were ever re-queued by an unrelated path.

**Task:**
1. New Flyway migration `V144__outbox_dlq_claimed_at.sql`: `SET lock_timeout = '5s';` then two additive
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS claimed_at timestamp with time zone` statements (one per
   table), per `docs/deployment/migration-conventions.md` rule 1 (expand/contract) — mirror
   `V143__user_cleanup_failed_at.sql`'s header-comment style (explain what and why), but note the
   timestamp type here matches these two tables' own `timestamptz` convention, not V143's
   `main."user"`-specific rationale (that table's columns are plain `timestamp`; these two are not —
   don't copy V143's sentence verbatim).
   **Rollout:** immediately after this migration every existing row has `claimed_at IS NULL`. Under the
   scoped `findClaimedBatch` in this AC, a row that was `CLAIMED` at migration time (e.g. left behind by
   an instance that crashed before the deploy) would never match any run's `claimed_at` and would be
   stranded forever. Backfill in the same migration:
   `UPDATE ... SET claimed_at = now() WHERE status = 'CLAIMED' AND claimed_at IS NULL` for both tables,
   so those rows become reclaimable via the normal `resetStaleClaimed` path on the next stale-window
   pass. Both tables are expected to have zero or very few `CLAIMED` rows at any given moment in
   practice, but check current row counts before assuming the backfill needs no batching — if
   `MigrationConventionLintTest`'s `UNBATCHED_DML` rule flags it regardless of expected volume, use a
   bounded form or a documented `-- migration-lint: allow-*` opt-out.
2. Add `claimedAt` (`Instant`) to `VideoDeletionOutbox` and `RadarCompositeDlqEntry` entities.
3. `VideoDeletionOutboxRepository.claimPendingBatch` / `RadarCompositeDlqRepository.claimPendingBatch`:
   add `claimed_at = :now` to the `UPDATE ... SET` clause (both already take `:now` as a parameter for
   the batch-claim's own bookkeeping — verify whether they already do or need it added; re-check
   against current source before assuming).
4. Both repositories' `findClaimedBatch`: change the signature to
   `findClaimedBatch(@Param("claimedAt") Instant claimedAt, @Param("batchSize") int batchSize)` and the
   `WHERE` clause to `status = 'CLAIMED' AND claimed_at = :claimedAt`, with `ORDER BY next_retry_at ASC
   LIMIT :batchSize` (same `batchSize` the tick's `claimPendingBatch` call already uses). Update both
   processors' call sites to pass the same `runClaimedAt`/`batchSize` used for the claim.
5. Both repositories' `resetStaleClaimed`: change the `WHERE` clause to `status = 'CLAIMED' AND
   claimed_at IS NOT NULL AND claimed_at < :deadline`.
6. Both processors: clear `claimed_at` to `null` on **every** transition out of `CLAIMED` — not only
   `COMPLETED`. Concretely: the `COMPLETED` success paths
   (`VideoDeletionOutboxProcessor.completeRow`, `.completeRowWithNullAsset`, and the drill-refCount
   short-circuit branch in `.processRow`; `RadarCompositeDlqProcessor.processRow`'s success branch), and
   both `handleFailure` outcomes in each class — `PENDING` (backoff, retries remain) and `DEAD` (retries
   exhausted). A `DEAD` row keeping a stale non-null `claimed_at` would give exactly the false
   claim-age reading this AC's fix is meant to prevent, so `DEAD` must clear it too, not just `PENDING`.
7. Update both classes' existing Javadoc on `STALE_CLAIM_WINDOW`/the `resetStaleClaimed` call site: the
   `resetStaleClaimed` staleness check now runs off a correct clock (claim time, not eligibility time)
   and the fetch is now scoped to this run's own claim — together these make the invariant structural.
   The `skillars-deferred-120` 20-minute-buffer reasoning becomes a secondary safety margin on top of
   that, not the primary fix — it does not become *unnecessary*; keep `STALE_CLAIM_WINDOW >
   lockAtMostFor` as-is.
8. Index coverage: `resetStaleClaimed`'s predicate moves from `next_retry_at < :deadline` (served by
   `idx_radar_composite_dlq_status_retry ON (status, next_retry_at)`) to `claimed_at < :deadline`, which
   that index no longer covers past its `status` prefix (`idx_vdoutbox_status_claimed ON (status) WHERE
   status = 'CLAIMED'` still covers the video side's status predicate). Both tables are expected to be
   small enough that this is very likely fine — state that as an accepted call in the migration header
   rather than leaving it unexamined; add a matching partial index only if row-count evidence at
   implementation time says otherwise.
9. Tests: extend or add IT coverage proving (a) `resetStaleClaimed` no longer reclaims a row whose
   `next_retry_at` (eligibility) predates the stale window but whose `claimed_at` does not — a
   genuinely-just-claimed but long-backlogged row must **not** be reclaimed; and (b) a second
   instance's `findClaimedBatch` does **not** return rows claimed by a different `claimed_at`/run — the
   actual double-processing scenario this AC exists to close. Cover both processors (mirror whatever
   existing IT already exercises `claimPendingBatch`/`resetStaleClaimed` for each, if one exists — check
   for `VideoDeletionOutboxProcessorIT`/`RadarCompositeDlqProcessorIT` or similar before writing new IT
   scaffolding from scratch).

---

## AC4 — Make hardcoded `lockAtLeastFor`/`lockAtMostFor` scheduler-lock floors operator-tunable properties

**Problem, corrected 2026-09-18 (see the story-level correction note — this AC was rewritten from
scratch before any code was written):** the original draft asserted "ShedLock does not resolve `${...}`
placeholders in `lockAtLeastFor`/`lockAtMostFor`" and built a boot-time cross-check on top of that
premise. **That premise is false, verified by decompiling the resolved artifact**
(`shedlock-spring:7.10.1`), not by documentation or memory:
- `SpringLockConfigurationExtractor` holds a `private final StringValueResolver embeddedValueResolver`
  field.
- Both `getLockAtMostFor(AnnotationData)` and `getLockAtLeastFor(AnnotationData)` route through
  `private Duration getValue(long, String, Duration, String)`, whose bytecode calls
  `embeddedValueResolver.resolveStringValue(...)` on the annotation's string attribute whenever the
  resolver is non-null, then hands the result to `durationConverter.convert(...)`
  (`StringToDurationConverter`, which accepts both ISO-8601 `PT30S` and Spring-style `30s` forms).
- `LockConfigurationExtractorConfiguration` implements `EmbeddedValueResolverAware`, so that resolver
  is non-null in every Spring Boot app.

A `${...}` string is a legal compile-time constant expression, so this already works exactly where the
current hardcoded literal sits:
```java
@SchedulerLock(name = "...", lockAtMostFor = "${some.property.lock_at_most:PT15M}",
               lockAtLeastFor = "${some.property.lock_at_least:PT30S}")
```
The real gap was never "ShedLock can't do this" — it's that **nobody made it a property**, so an
operator who lowers a scheduler's cadence has no matching knob to raise the lock floor, and the
mismatch fails silently (dropped ticks, no error, no warning, no metric). This AC fixes that at the
declaration site instead of building a runtime detector for it. There is deliberately **no boot-time
check, no `failFast`, no new record type, and no second source of truth** in this AC — the fix is the
annotation literal itself becoming the tunable value, so there is nothing to drift out of sync with.

**Scope, corrected:** the original audit filtered to `fixedDelayString`-tunable schedulers only and
found 7 sites. That filter was too narrow — the real criterion is *any Spring-property-tunable
cadence*, `fixedDelayString` **or** `cron`, paired with a hardcoded lock-duration literal. Re-auditing
against that criterion adds 3 more sites, for **10 total**:

| Scheduler.method | Cadence property (default) | Hardcoded `lockAtLeastFor` / `lockAtMostFor` today |
|---|---|---|
| `OutboxService.sweep` | `app.outbox.sweep-ms` (300000ms) | `lockAtLeastFor = PT1M` |
| `QuotaReservationTimeoutService.expireStaleReservations` | `app.video.reservation-check-interval-ms` (60000ms) | `lockAtLeastFor = PT1M` — zero margin at defaults today |
| `VideoDeletionOutboxProcessor.process` | `platform.video.deletion.outbox_poll_delay_ms` (60000ms) | `lockAtLeastFor = PT30S` |
| `RadarCompositeDlqProcessor.process` | `platform.development.radar_composite_dlq.poll_delay_ms` (60000ms) | `lockAtLeastFor = PT30S` |
| `EmailRetryScheduler.retryFailedEmails` | `email.retry.interval-ms` (60000ms) | `lockAtLeastFor = PT10S` |
| `OutboxPollerScheduler.pollAndProcess` | `app.storage.poller.fixed-delay-ms` (5000ms) | `lockAtLeastFor = PT2S` |
| `DeletionSchedulerService.processDeletions` | `app.storage.poller.fixed-delay-ms` (5000ms, **same property** as the row above) | `lockAtLeastFor = PT2S` |
| `VideoLifecycleScheduler.runLifecycleJob` | `app.video.lifecycle.cron` (`0 0 3 * * *`) | `lockAtMostFor = PT12H`, `lockAtLeastFor = PT30S` |
| `SluSnapshotAppliedRetentionService.prune` | `app.slu.snapshot-applied.prune-cron` (`0 30 3 * * *`) | `lockAtMostFor = PT15M`, `lockAtLeastFor = PT1M` |
| `NeglectedSkillDetectionService.detect` | `app.development.neglected-detection-cron` (`0 0 6 * * MON`) | `lockAtMostFor = PT30M`, `lockAtLeastFor = PT5M` |

Confirmed excluded (structurally safe, do not touch): `ReconciliationWorkerScheduler
.sweepOrphanedProviderAssets` (`lockAtLeastFor = "PT0S"`, cannot be undercut by any cadence value) and
its sibling `.reconcile` (no `@SchedulerLock` at all); `UploadSessionExpiryScheduler`,
`WebhookEventProcessorScheduler`, `ModerationSlaMonitorService`, `ConfigService.scheduledRefresh`,
`AlertRuleCache.refresh`, `AlertEvaluationService.evaluate` (no `@SchedulerLock` at all).

**Fix:** for each of the 10 rows, convert the hardcoded `lockAtLeastFor`/`lockAtMostFor` literal to a
`${...:currentLiteral}` property expression, using a dedicated property key per scheduler (do not reuse
the cadence property key for the lock value — they are different knobs). Default value is the current
hardcoded literal, so behavior is unchanged until an operator deliberately sets the new property.
Residual risk — an operator lowers the cadence property without also raising the matching lock-floor
property — is accepted as a documentation/runbook concern, not a boot-time gate: the operator now *has*
the knob to fix it in the same file/property source as the cadence change, which is the actual gap this
AC closes.

**Task:**
1. For each of the 10 scheduler methods in the table above, change the `@SchedulerLock` annotation's
   `lockAtLeastFor` (and `lockAtMostFor` where noted) from a bare literal to
   `"${<scheduler>.lock_at_least:<currentLiteral>}"` (and `.lock_at_most` where applicable), choosing
   property key names consistent with each file's existing property-naming convention (`app.*`,
   `platform.*`, `email.*` as already used by that scheduler's own cadence property).
2. No production-code check, no new class, no new test class. This AC's only surface is annotation
   literals — verify via existing scheduler tests (if any assert on the annotation's literal value,
   e.g. via reflection) that they now assert against the *resolved* value instead, or against the
   default the property falls back to.
3. Add or extend one short doc note (e.g. in `docs/deployment/` or the relevant scheduler's class
   Javadoc) naming the new properties, so an operator changing a cadence property knows the paired lock
   property exists.
4. Regression check: confirm each changed scheduler still boots and locks correctly with the property
   unset (falls back to the literal default) — a quick IT run or manual property-resolution smoke test
   is enough; this is a mechanical annotation change, not new logic.

---

## AC5 — Envers `user_aud` schema/annotation mismatch: investigate and resolve

**Problem:** `User.java:138-144` declares `skillarsRole` (`@Column(name = "skillars_role")`) and
`verificationStatus` (`@Column(name = "verification_status")`) with **no** `@NotAudited`, on a class
annotated `@Audited` (`User.java:45`). `main.user_aud` (`V138__baseline_schema.sql:1311-1346`) has no
`skillars_role` or `verification_status` column. Both fields are set via ordinary entity setters
(`ParentRegistrationService`, `PlayerRegistrationService`, `CoachRegistrationService`,
`AdminBootstrapRunner`, `AccountSuspensionEventListener` — confirmed by grep, not bulk/native updates
that would bypass Hibernate's dirty-checking and therefore bypass Envers entirely), so every user
registration and every verification-status/role transition is a candidate for Envers to attempt writing
an audit row referencing these fields.

**This story does not yet know which of three things is true**, and `skillars-deferred-122`'s own
investigation deliberately did not run it down further:
- (a) Envers actually attempts to write `skillars_role`/`verification_status` into `user_aud` and this
  fails at runtime (a `SQLGrammarException`/similar) on every registration or role/status change — which
  would be a severe, currently-masked production bug (masked by something: caught and swallowed
  somewhere, an `@Async`/event-listener boundary eating the exception, or similar), or
- (b) Envers tolerates the mismatch in some way not yet understood (e.g. these fields are somehow
  excluded from the generated audit DML despite lacking `@NotAudited` — verify this isn't explained by
  inheritance: `User extends Customer`, confirm which class's fields these actually are and whether
  `@Audited` propagates as expected), or
- (c) **the intended fix is `@NotAudited`, not a matching `user_aud` column** — this repository already
  chose exactly this for the same entity: `V143__user_cleanup_failed_at.sql`'s header records that the
  four `cleanup_*` columns on `main."user"` are `@NotAudited` "because they are operational marker
  state, not user-facing auditable history." Whether `skillars_role`/`verification_status` are
  auditable history is a real product question (a role change plausibly *is* history worth keeping) —
  this AC must decide it explicitly, not default to (a) or (b) just because those were the two outcomes
  originally considered.

**Task:**
1. Investigate first — do not guess. Write (or run, if one already exists) a targeted IT that persists
   a new `User` with `skillarsRole`/`verificationStatus` set (mirroring `CoachRegistrationServiceIT` or
   similar) and directly queries `main.user_aud` afterward to observe actual behavior. Check application
   logs for swallowed exceptions around registration flows if the IT surprisingly passes.
2. **If (a)** — Envers genuinely fails or silently drops these fields, **and** the resulting audit
   history for these two fields is wanted: add the missing `skillars_role`/`verification_status`
   columns to `main.user_aud` via a new Flyway migration (`V145__user_aud_role_verification_status.sql`
   or the next free number after AC3's migration), matching the entity's actual column types/lengths.
   Re-run the investigation IT to confirm the audit row now round-trips correctly. State explicitly in
   the migration header whether the resulting `NULL` values on every pre-existing revision are accepted
   (they almost certainly are) — a newly-added `user_aud` column is `NULL` for every historical
   revision, indistinguishable from "was genuinely null at that revision," and the next reader should
   not mistake those NULLs for data.
3. **If (b) or the product decision is "not auditable history" regardless of (a)/(b)** — add
   `@NotAudited` to both fields on `User.java`, mirroring `V143`'s precedent and rationale, and confirm
   via the investigation IT that no audit-row write is attempted for these fields afterward.
4. Document the actual mechanism found (not a restated guess) in a code comment on `User.java:138-144`
   and in this story's completion notes, so the next person who greps for this doesn't re-open the same
   investigation from zero.
5. Either way, delete the `deferred-work.md` bullet under "code review of
   skillars-deferred-121-coach-enforcement-status-toctou-lock-gap" that raised this (see AC8).

---

## AC6 — Genuine runtime isolation-level test for `getEnforcementProfile`/`getCoachesUnderEnforcement`

**Problem:** `AdminCoachEnforcementServiceIsolationTest` (whole file, added by `skillars-deferred-122`
AC3) proves via reflection that `@Transactional(isolation = Isolation.REPEATABLE_READ)` is present on
both methods. Its own Javadoc is explicit that this is "unverified behavior, annotation only" — it
cannot catch the torn-read AC3 exists to prevent if Spring's `validateExistingTransaction = false`
default ever silently discards the isolation request (documented risk: a future caller wrapping either
method in an ambient `@Transactional`/`TransactionTemplate` block).

**Fix, corrected 2026-09-18 (see the story-level correction note):** the originally-specified fix drove
two raw JDBC connections with hand-written `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ` and
hand-written SELECTs, never calling `getEnforcementProfile`/`getCoachesUnderEnforcement` or going
through Spring's transaction manager at all. That test can only prove PostgreSQL implements
`REPEATABLE READ` (which it does, unconditionally) — it cannot observe whether *this service's*
requested isolation was honored or silently discarded, which is the one thing AC6 exists to check.
Two of its query pair's own reads are non-trivial to reproduce faithfully by hand: `getEnforcementProfile`
reads a single-id derived query, but `getCoachesUnderEnforcement` reads a **paged**
`findByStatusInOrderByStatusChangedAtAsc` plus an `IN`-list `GROUP BY` `@Query` returning
`List<Object[]>` — reproducing Spring Data's generated SQL by hand for that pair is not
"near-identical" work to the first, and it is exactly the part most likely to silently rot out of sync.

**Fix:** replace the two-connection JDBC design with a probe that observes the real thing — the
*effective* isolation level the transaction actually got, read from inside the real service call:
```java
// Fails exactly when validateExistingTransaction=false silently drops the requested isolation.
int level = DataSourceUtils.getConnection(dataSource).getTransactionIsolation();
assertThat(level).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
```
Wire it via a test-scoped hook that needs no production-code change — e.g. a
`TransactionSynchronization` registered from the test, or a spy on one of the method's injected
repositories that records the isolation level on first call. This is method-agnostic: the same probe
covers `getEnforcementProfile` and `getCoachesUnderEnforcement` as genuinely two near-identical tests
(dissolving the hand-written-query-pair problem above entirely — the probe never needs to reproduce
either method's SQL). Then add the negative case that gives the test its actual value: invoke the
method from inside an ambient `TransactionTemplate`/`@Transactional` block (simulating a future caller
that wraps it, the documented hazard) and assert the observed isolation is **not**
`REPEATABLE_READ` when `validateExistingTransaction` behaves as documented — pinning the hazard as
observed behavior instead of prose. This is strictly more coverage than the original two-connection
design, at a fraction of the cost, and requires no second raw connection.

**Task:**
1. New test (e.g. `AdminCoachEnforcementIsolationRuntimeIT` or extend the existing isolation test class
   if that fits this codebase's convention better) that registers the isolation-probe hook and calls
   `getEnforcementProfile`, asserting the effective `Connection.TRANSACTION_REPEATABLE_READ`.
2. Add the same probe-and-assert for `getCoachesUnderEnforcement` — two near-identical tests (or one
   parameterized test), genuinely sharing the same probe mechanism this time.
3. Add the negative case: call one of the two methods from inside an ambient `TransactionTemplate`
   block and assert the observed isolation is not `REPEATABLE_READ`, documenting in the test what this
   pins (the `validateExistingTransaction = false` hazard named in the existing Javadoc).
4. Keep the existing `AdminCoachEnforcementServiceIsolationTest` — it still usefully pins the annotation
   against silent removal; this AC adds runtime-behavior coverage, it does not replace the
   annotation-presence check. Update its Javadoc's "Why an annotation-presence test" section to point at
   the new test as the runtime-behavior proof it was waiting on.

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

**Citation correction (2026-09-18):** the deferred-121 ledger bullet this AC closes cites
`PessimisticLockRetryer.java:132-134` as the mechanism — that described the pre-`skillars-deferred-122`
code path. At HEAD, `deleteStrike` uses the bulk `@Modifying` JPQL delete, executed immediately at
`:285`, **eight lines before** `:293`'s `lockRetryer.withBoundedRetry` (and therefore before that
call's `Timer.start`) — nothing is folded into the `persistence.lock_retry` timer any more; the correct
citation is `AdminCoachEnforcementService.java:285,293` and
`CoachReliabilityStrikeRepository.java:45-47`. Use the corrected citation in both the code comment
below and the AC8 ledger annotation — do not carry the stale `PessimisticLockRetryer` citation forward.

**Task:**
1. Add a code comment at `CoachReliabilityStrikeRepository.deleteByIdAndCoachId` (`:45-47`, near the
   existing `skillars-deferred-122 AC2` comment) recording: the DELETE has no `NOWAIT`, why that's
   accepted (the self-bounding argument above), and what would invalidate the acceptance (any future
   change making the winning transaction's own work unbounded).
2. Update the `deferred-work.md` bullet (see AC8) to a `[DECIDED: accepted risk — skillars-deferred-123]`
   annotation rather than deleting it outright — the underlying gap still exists, only the decision not
   to fix it is now closed.

---

## AC8 — `deferred-work.md` ledger closeout

Update `_bmad-output/implementation-artifacts/deferred-work.md`:

| Ledger item | Disposition |
|---|---|
| `code review of skillars-deferred-121…`: "The strike DELETE's wait is outside the retry/savepoint guarantee" | Annotate `[DECIDED: accepted risk — skillars-deferred-123]` with the **corrected** citation (`AdminCoachEnforcementService.java:285,293` + `CoachReliabilityStrikeRepository.java:45-47`, not the stale `PessimisticLockRetryer.java:132-134`) — do not delete (AC7) |
| `code review of skillars-deferred-121…`: "`issueManualStrike` applies no coach-status guard" | Delete — superseded by AC1's record-and-suppress fix in `ReliabilityStrikeService.issue`, which also covers the automatic strike path this bullet didn't name |
| `code review of skillars-deferred-121…`: "The 30-day count window origin slides with contention" | Delete (AC2) |
| `code review of skillars-deferred-121…`: "Pre-existing `main.user_aud` Envers-coverage gap" | Delete (AC5) |
| `code review of skillars-deferred-120…`: "`lockAtLeastFor` values are hardcoded against tunable `fixedDelayString` cadences" | Delete (AC4) — note in the closing narrative that this bullet's premise was corrected, not just closed: ShedLock does resolve `${...}` in these attributes, so the fix landed as property-ization at the declaration site, not a boot-time check |
| `code review of skillars-deferred-120…`: "`resetStaleClaimed`/`claimPendingBatch`'s shared claim idiom keys stale-claim recovery on eligibility time, not claim time" (both occurrences — the finding is stated twice in that section) | Delete (AC3) — AC3 also scoped `findClaimedBatch`, so the *duplicate-processing* path is closed, not just the *eligibility-vs-claim-time* framing this bullet named |
| `code review of skillars-deferred-122…`: "AC3's REPEATABLE_READ coverage is annotation reflection only" | Delete (AC6) |
| `code review of skillars-deferred-122…`: "`main."user"` has no index supporting the cleanup sweep predicate" | Add to "Explicitly out of scope" with reason: needs production `EXPLAIN` evidence and its own `CREATE INDEX CONCURRENTLY` migration — this story's scope is the strike-timing/scheduler-lock/Envers-audit findings, not this one, and it must not be silently dropped from the disposition table |
| `code review of skillars-deferred-120…`: `ModerationSlaMonitorService` no-`@SchedulerLock` `[DECIDED]` note | Leave — confirmed still correctly out of scope, untouched by this story |

While editing this section, also correct the deferred-121 section's own preamble: it currently says
"the three remaining un-annotated bullets below are still open" when there are (or were, before this
story) four — this story picks up all four (AC7, AC1, AC2, AC5), so fix the stale count in the same
edit rather than leaving it for the next reader to notice.

Add a `## Last audit: 2026-09-18 (skillars-deferred-123 story creation)` narrative block, matching this
file's existing convention, summarizing what was checked and closed (link back to this story's
Provenance section rather than duplicating it) — and explicitly listing the corrected-premise items
(AC3, AC4) so the next story doesn't quote the original (wrong) framing from git history.

---

## Tasks / Subtasks

- [x] AC1 — `ReliabilityStrikeService.issue` suppresses escalation for `SUSPENDED`/`DEACTIVATED` coaches (strike still recorded); tests for both manual and automatic paths, regression guard for other statuses
- [x] AC2 — hoist the 30-day cutoff capture above the lock wait in `deleteStrike` and `issue`; code comments anchoring why
- [x] AC3 — `claimed_at` column (V144) on both outbox/DLQ tables; `claimPendingBatch` stamps it; `findClaimedBatch` scoped to it; `resetStaleClaimed` keyed on it; clear on every CLAIMED exit; IT coverage for both processors
- [x] AC4 — property-ize the 10 hardcoded `lockAtLeastFor`/`lockAtMostFor` scheduler-lock floors; update the 4 reflection tests that parse the literal; doc note on the new properties
- [x] AC5 — investigate the Envers `user_aud` schema/annotation mismatch empirically (IT), then resolve per the actual finding
- [x] AC6 — genuine runtime isolation-level test for `getEnforcementProfile`/`getCoachesUnderEnforcement` (probe + negative case), keep existing annotation-presence test
- [x] AC7 — document the strike-DELETE unbounded-wait as accepted risk (code comment only)
- [x] AC8 — `deferred-work.md` ledger closeout per the disposition table


### Review Findings

_`/bmad-code-review` 2026-09-18 — four parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor, `txn-and-concurrency-audit`). Every claim below was independently re-verified against the working tree before being recorded; layer claims disproved on verification are listed at the bottom._

**Decision-needed**

- [x] [Review][Decision — RESOLVED 2026-09-18, option (a): fixed at source] AC5's documented mechanism is wrong — the real cause is `spring.jpa.generate-ddl: true`, and production is running effective `hbm2ddl.auto=update` — Verified at bytecode level in this project's resolved artifacts: `HibernateProperties.getAdditionalProperties` (spring-boot-autoconfigure 3.5.16, offsets 72-80) *removes* the `hibernate.hbm2ddl.auto` key when `ddl-auto` is `none` rather than setting it; `HibernateJpaVendorAdapter.getJpaPropertyMap` (spring-orm 6.2.19, offsets 58-69) then puts `hibernate.hbm2ddl.auto = "update"` because `isGenerateDdl()` is true; `AbstractEntityManagerFactoryBean` merges vendor properties with `if (!containsKey(key)) put(...)`, so the vendor value wins. `application.yaml:65-66` sets both. Net: Hibernate auto-DDL is live in every profile, which is what AC5 actually observed — not "Hibernate issues DDL independent of `ddl-auto`", which is not a real Hibernate behaviour. The misdiagnosis is now written into `V145__user_aud_role_verification_status.sql:6-8` and `:28-32`, `User.java:138-150`, `UserEnversAuditGapIT`'s class Javadoc, and a **new** `deferred-work.md` bullet that sends the next reader hunting for "a property beyond simple `none`, if one exists". Also means the codebase has been silently violating its own project-context rule ("Do not use DDL statements in Java code; use Flyway migrations") in every environment. Options: (a) delete `generate-ddl: true` and correct all five documentation sites now; (b) correct the docs now and split the YAML fix into its own story — removing it may expose columns Hibernate has been silently adding that no Flyway migration creates, which needs its own schema-reconciliation pass; (c) accept and document as-is.
  **Resolution — option (a) applied.** A pre-removal drift audit was run first, since the risk that motivated option (b) was that a Flyway-only database might be missing columns auto-DDL had been supplying: every `@Table` entity was confirmed to have a `CREATE TABLE` in a migration, `User`'s four `cleanup_*` fields were confirmed `@NotAudited` (correctly absent from `user_aud`), and `main.user_aud`'s only two genuinely missing columns are the ones `V145` adds — so nothing depended on auto-DDL to boot. `generate-ddl: true` was then removed from `application.yaml:65` and replaced with a comment carrying the decompiled three-step mechanism so it is not reinstated. All five documentation sites corrected: the `V145` header (cause paragraph and the broader-finding paragraph), `User.java`'s `skillarsRole` Javadoc, `UserEnversAuditGapIT`'s class Javadoc, and the `deferred-work.md` bullet (rewritten — it previously directed the next reader to hunt for "a schema-generation property beyond simple `none`, if one exists", which does not exist and is not needed). YAML re-parsed to confirm `generate-ddl` is gone and `ddl-auto: none` survives.
  **Two consequences worth noting.** (1) The test profile inherits the root JPA block and declares no override, so every Testcontainers IT had also been running with `hbm2ddl.auto=update` — Hibernate was patching the schema before assertions ran, which is why no IT had ever detected Flyway/entity drift. CI is now the first real test of Flyway/entity parity for this codebase. (2) Two divergences are now frozen between databases built before and after the fix — enum `CHECK` constraints, and `main."user".skillars_role`/`verification_status` at `varchar(255)` vs the migration's `varchar(20)`. Both are recorded in `deferred-work.md` and make the V145 CHECK-reconciliation patch below more pointed than when it was first raised. Per `skillars-deferred-117`'s owner decision no production deploy has ever happened, so the only pre-fix databases in existence are development and CI ones.
  **ADDENDUM — the pre-removal audit was incomplete, and removing the property did break things before it was made right.** The audit recorded above (every `@Table` entity has a `CREATE TABLE`; `user_aud`'s two gaps covered by V145) checked TABLES and one table's columns. It did not column-audit every entity against its live table, and it did not check for missing tables at all. Re-running `ReinstateIT` after the change surfaced the consequence immediately: **every login failed** with `column u1_0.provider does not exist`. `PhoneNumber` (embedded into `User` via `Customer`) declares `@Enumerated(EnumType.STRING) private Provider provider` under an `@AttributeOverrides` block that names phone/iso2Country/phoneType but not provider, so it maps to a `provider` column no migration has ever created. This is a long-standing latent gap the property was masking — not something the change introduced — but it was the change that would have shipped it as a hard failure.
  **Full extent then established empirically rather than by inspection.** The application was booted against a Flyway-only Testcontainers database with `hbm2ddl.auto=update` and `org.hibernate.SQL` at DEBUG, and every DDL statement Hibernate emitted was captured — that set IS exactly what the property had been masking. Three migrations close it: **V146** (`provider` on `main."user"` and `main.user_aud`), **V147** (the seven `AbstractAuditingEntity` columns on `main.file_storage_objects` — `FileStorageObject` extends it and the baseline declared none of them), **V148** (`main.audit_log` and `main.user_authority_aud`, two tables present in the entity model and in no migration).
  **A second process lesson worth recording.** The first sweep grepped only for `add column` and so was blind to missing TABLES; `main.audit_log` surfaced afterwards as a *logged but non-fatal* `relation "main.audit_log" does not exist` on the auditing write path — the HTTP request still succeeded, so the tests stayed green and it would have shipped silently. The sweep was re-run capturing `create table`, `add column` and `create sequence`, which produced V148's two tables and nothing else. Re-run once more after all three migrations: **zero remaining DDL**, i.e. Flyway now fully describes every entity mapping in the application, which has not previously been true.
  **Verification.** `MigrationConventionLintTest` 13/13 (V144–V148 all lint-clean; V148 declares its PK/FK/CHECK inline in `CREATE TABLE` specifically because `VALIDATING_CONSTRAINT` flags a bare `ADD CONSTRAINT ... FOREIGN KEY|CHECK` and `INLINE_FK_ADD_COLUMN` flags `ADD COLUMN ... REFERENCES`, while neither rule applies to a constraint inside a CREATE TABLE). Combined sweep of 16 unit and integration test classes: **111/111 green, zero `does not exist` errors**.
- [x] [Review][Decision — RESOLVED 2026-09-18, option (a): re-evaluate in reinstateCoach] AC1's suppression drops the escalation permanently and silently removes an alert that previously fired — `ReliabilityStrikeService.issue` saves the strike unconditionally but `alreadyOffMarketplace` short-circuits both escalation branches, and `StrikeThresholdReachedEvent` is published *inside* the `if (coach.getStatus() != PENDING_REVIEW)` block. Pre-AC1 a SUSPENDED coach crossing the threshold satisfied that condition, so an alert fired (while also wrongly demoting them to PENDING_REVIEW — the bug AC1 correctly fixes). Post-AC1 no event, no `AdminAlert`. `reinstateCoach` (`AdminCoachEnforcementService.java:211-215`) then sets ACTIVE unconditionally and calls `resolveOpenStrikeAlert`, and `getCoachesUnderEnforcement:384` lists only PENDING_REVIEW/SUSPENDED — so a reinstated coach returns to full marketplace visibility carrying in-window strikes at or above `suspensionThreshold` with no alert and no enforcement-list presence until the next strike lands, which at a 30-day window may be never. Final status is also interleaving-dependent: `issue()` then `reinstateCoach` lands ACTIVE; the reverse order lands PENDING_REVIEW. Options: (a) re-evaluate the in-window count inside `reinstateCoach` under the lock it already holds, reusing `deleteStrike:337-365`'s three-tier pattern; (b) keep the suppression but still publish the event/alert so the signal survives; (c) accept as intended.
  **Resolution — option (a) applied, with one deliberate narrowing.** A literal port of `deleteStrike`'s three tiers to `reinstateCoach`'s *status* would collide with the binding `[DECIDED 2026-09-18, skillars-deferred-122]` note in that same method ("explicit admin intent always wins"): tier 1 leaves the status untouched, so reinstating a coach who still has >= `suspensionThreshold` in-window strikes would become a silent no-op — the admin presses reinstate and nothing happens, with no feedback. So the status write stays unconditional, and the re-evaluated count drives the **alert** instead. That is the half of the three-tier pattern that transfers cleanly: `deleteStrike` calls `resolveOpenStrikeAlert` only in the tier landing on a genuinely clean ACTIVE (`:352-365`) and deliberately withholds it while the count is still elevated, because "a coach still ... remains at an elevated strike count worth the admin's attention" (`:349-351`).
  **Implementation.** `reinstateCoach` now captures the 30-day cutoff before the lock wait (per AC2's own rule, matching both sibling methods), and after the ACTIVE write re-reads `countByCoachIdAndCreatedAtAfter` under the lock it already holds. Below `visibilityThreshold` it resolves the alert exactly as before; at or above it, it withholds the resolve, re-publishes `StrikeThresholdReachedEvent` so the signal exists even though AC1 suppressed the one that would have fired, logs at WARN, and appends the live count to the `admin_action_log` reason. Re-publishing is idempotent — `AdminAlertEventListener.insertAlert` short-circuits on an existing OPEN alert for the same `(referenceId, type)` and a unique index covers the concurrent case (both verified).
  **Verification.** `mvn compile` and `mvn test-compile` both BUILD SUCCESS, zero Java errors. The two existing `ReinstateIT` tests seed no strikes, so `count = 0 < 3` keeps them on the resolve branch — checked explicitly rather than assumed, no regression. New `reinstateCoach_withStrikesStillInWindow_setsActiveButLeavesAlertOpen` seeds 3 in-window strikes and pins: status still ACTIVE (admin intent preserved), alert still OPEN with `resolved_at` null, exactly one OPEN alert (idempotency), and the count recorded on the audit row. `tearDown` extended to delete strikes before `coach_profiles` (FK ordering) and to clear alerts by `reference_id` rather than only the seeded `alert_id`, so a re-published alert cannot leak into a sibling test.
- [x] [Review][Decision — RESOLVED 2026-09-18, option (b): bound both loops] Stale-claim windows are undersized on both processors, and AC3 makes the sizing load-bearing for the first time — `RadarCompositeDlqProcessor.java:59` has `lockAtMostFor = "PT10M"` against `resetStaleClaimed(runClaimedAt.minus(10, MINUTES))` at `:65` — **exactly equal, zero buffer**, while its structural twin documents the opposite as mandatory (`VideoDeletionOutboxProcessor.java:35-44`: "`STALE_CLAIM_WINDOW` MUST stay strictly greater than `LOCK_AT_MOST_FOR`", honoured there as 20 min vs `PT15M`). Separately, the video side's 20-min window is sized off a self-described optimistic "~10s/item" figure; the adapter's real budget is `VideoProviderConfig.java:35-36` connect 10 s + read 30 s with no retry at that layer, so `BATCH_SIZE=50 x 30s = 25 min > 20 min window > 15 min lock` — and the case that makes every item time out is a provider outage, i.e. correlated, not independent. Before AC3, staleness keyed on `next_retry_at`, so the lock-vs-window relationship was not load-bearing; keying on `claimed_at` makes it the only thing preventing a hung tick's own rows being reclaimed underneath it. Options: (a) re-derive both windows from real worst case (Radar gains a real buffer; video window >= ~35 min); (b) bound both loops with a self-terminating deadline, the pattern `QuotaReservationTimeoutService.java:23` (`MAX_RUN_DURATION = 8m` under `PT10M`) already establishes in-repo; (c) accept and genuinely record it — `VideoDeletionOutboxProcessor.java:39-42` and `:90-92` claim the Radar equality is "recorded in `deferred-work.md`", and it is not (verified across all 2396 lines).
  **Resolution — option (b) applied to both processors.** Each `process()` now computes `deadline = runClaimedAt.plus(MAX_RUN_DURATION)` and self-terminates, so the run cannot still be in flight when its own `@SchedulerLock` expires. Budgets mirror `QuotaReservationTimeoutService`'s ~80% ratio: video 12m under `PT15M`, radar 8m under `PT10M` (same lock duration as the precedent, so the same 8 minutes). This fixes the radar equality without touching the window — with the run bounded, the window no longer has to outlast a possibly-overrunning run, and lock expiry now only ever means a genuine crash, which is exactly what `resetStaleClaimed` is for.
  **One necessary addition beyond the cited pattern.** `QuotaReservationTimeoutService` claims per-iteration via `SKIP LOCKED`, so bailing out leaves nothing claimed. These two claim the whole batch upfront, so a bare `break` would strand the remainder as `CLAIMED` for a full stale window (20m/10m) despite the rows being known-good. Added `releaseClaimed(:claimedAt)` to both repositories — `SET status='PENDING', claimed_at=NULL WHERE status='CLAIMED' AND claimed_at = :claimedAt`. Scoped to this run's own stamp so it can never touch a concurrent instance's rows, and since every finished row has already had `claimed_at` nulled, "still CLAIMED with this run's stamp" is exactly the unprocessed remainder. It also nulls `claimed_at`, keeping the field's documented invariant true on this path.
  **Also corrected.** `VideoDeletionOutboxProcessor.STALE_CLAIM_WINDOW`'s Javadoc claimed the radar equality was "an accepted, unfixed weakness recorded in `deferred-work.md`" — it was recorded nowhere (whole ledger checked); that sentence is replaced with the real position. Its sizing rationale, which rested on a self-described optimistic "~10s/item" against the adapter's actual 30s read timeout (`50 x 30s = 25 min`, exceeding both window and lock), now states that the bound — not the estimate — is the guarantee. Radar's stale window was extracted from an inline `minus(10, ChronoUnit.MINUTES)` literal into a named `STALE_CLAIM_WINDOW` constant so the invariant is expressible there too.
  **Verification.** `mvn test-compile` BUILD SUCCESS. Two new invariant tests pin `MAX_RUN_DURATION < lockAtMostFor < STALE_CLAIM_WINDOW` (video) and `MAX_RUN_DURATION < lockAtMostFor` plus `< STALE_CLAIM_WINDOW` (radar) by reflection, matching the codebase's existing reflection-based scheduler-test idiom. Mutation-checked by hand: raising radar's budget to 20 minutes makes `runtimeBudget_staysStrictlyInsideLock` fail (`BUILD FAILURE`, 1 failure), restored and green again — the assertions can genuinely fail. Regression sweep across the six touched unit-test classes: 35/35 green, zero regressions. The bail-out branch itself is not directly exercised (it needs a clock seam at a 12-/8-minute budget; this story deliberately declined to add one, see AC2 Task 3) — stated plainly rather than implied to be covered.
- [x] [Review][Decision — RESOLVED 2026-09-18, option (b): boot check, DELIBERATELY OVERRIDES AC4] A lock floor set above its still-hardcoded ceiling silently kills the job forever, and AC4 explicitly decided against a boot check — `shedlock-core:7.10.1`'s `LockConfiguration.<init>` throws `IllegalArgumentException` when `lockAtLeastFor > lockAtMostFor`, and `SpringLockConfigurationExtractor` builds a fresh `LockConfiguration` **per invocation**, not at startup. Eight of the ten converted sites expose only `lock-at-least` while `lockAtMostFor` stays a literal, and `docs/deployment/scheduler-lock-tuning.md` names neither the ceilings nor the ordering constraint. An operator setting `email.retry.lock-at-least=PT15M` (ceiling `PT10M`) boots fine, then every tick throws before the method body runs; Spring's `LOG_AND_SUPPRESS_ERROR_HANDLER` swallows it and failed emails are never retried again — no metric, no health signal. Previously impossible: both sides were compile-time literals. AC4's text explicitly rules out the obvious guard ("no boot-time check, no `failFast`, no new record type"). Related: `VideoLifecycleScheduler.java:77` makes `lockAtMostFor` operator-settable on a job whose own Javadoc (`:63-68`) calls that value correctness-load-bearing ("a genuine double-archive/double-delete attempt, not just a missed tick") and sizes it off the `batch_size` ceiling — while the new doc frames both attributes as *cadence* knobs, which is inverted for a ceiling. Options: (a) honour AC4 and fix by documentation only — add ceiling + ordering constraint to the tuning table; (b) extend `ConfigStartupAssertion` (the established in-repo cross-field pattern, cited at `AdminCoachEnforcementService.java:336`) to validate each pair, overriding AC4's stated decision; (c) revert the three `lock-at-most` property-izations and keep only the `lock-at-least` knobs that have a cadence rationale.
  **Resolution — option (b) applied. This is a deliberate, owner-approved override of AC4's own text** ("There is deliberately no boot-time check, no `failFast`, no new record type, and no second source of truth in this AC"), recorded here so it does not later read as drift. AC4's reasoning held while both attributes were compile-time literals; AC4 itself invalidated it by making the floor operator-settable against a mostly-hardcoded ceiling, creating a reachable misconfiguration whose failure mode — ShedLock throws per invocation, Spring's `LOG_AND_SUPPRESS_ERROR_HANDLER` swallows it, the job silently never runs again — clears `ConfigStartupAssertion`'s existing failFast bar ("a bad value causes data loss or halts a core flow entirely").
  **AC4's actual objection was answered, not ignored.** It objected to a second source of truth that could drift from the annotations. The check does not introduce one: it reads the `@SchedulerLock` annotations off the live beans via `ApplicationContext` and resolves their `${...}` expressions through the same `Environment` ShedLock uses, so the annotation remains the only place the values are written. A consequence worth noting: it therefore covers all 26 `@SchedulerLock` pairs in the application, not just the 10 AC4 converted, and any added later for free. Durations are parsed with `DurationStyle.detectAndParse`, matching ShedLock's own acceptance of both `PT30S` and `30s`; negative and unparseable values are caught too, since they fail the same way for the same reason. ERROR + `config.value.misconfigured{key=scheduler.lock.<name>}` in all profiles, boot blocked only outside `dev` — matching this class's established split exactly.
  **Verification.** 6 new fixture-driven tests (inverted pair blocks boot with both resolved values in the message; valid pair does not; Spring shorthand parsed and compared; unparseable blocks; a property override that inverts the pair blocks — proving the check sees the RESOLVED value, which is the whole point of AC4; the same property left at its default does not). `ConfigStartupAssertionTest` 21/21 green (15 pre-existing unaffected — the mock context has no bean definitions, so the new check is a no-op for them and they keep testing only what they were written to test). Full sweep across 7 touched unit-test classes: 56/56 green.
  **False-positive risk checked before shipping, not assumed.** Because `ApplicationReadyEvent` fires in every `@SpringBootTest`, a bad shipped default would now fail every IT at boot. All 26 real pairs were statically resolved and compared (including embedded `${...}` defaults and constant-referenced literals like `LOCK_AT_MOST_FOR`): zero violations. That same property is what gives the real shipped defaults permanent coverage for free.
  **Also added** (consequence of this decision, not option (a) by the back door): `docs/deployment/scheduler-lock-tuning.md` gained a ceiling column and the ordering constraint — a boot that is now blocked needs the operator to have the ceiling documented somewhere. The note there also corrects the doc's framing for the three settable `lock-at-most` values: unlike the floors, a ceiling is a crash-recovery bound sized against worst-case runtime, not a cadence knob, and `VideoLifecycleScheduler`'s is sized off `batch_size`'s ceiling because setting it too tight means a real double-archive/double-delete against the storage provider.
- [x] [Review][Decision — RESOLVED 2026-09-18, option (a): conditional claim-guarded UPDATE] Both processors merge a detached entity unconditionally, so any claim overlap is a lost update — `process()` carries no `@Transactional` (verified) and `findClaimedBatch` is a native query run outside a transaction, so every `row` is detached; `outboxRepository.save(row)` inside `transactionTemplate.execute` is therefore `em.merge()`, writing **all** columns with no `@DynamicUpdate`, no `WHERE status='CLAIMED'`, no `claimed_at` predicate, and no `@Version` on either entity (verified: zero occurrences in both). If instance B re-claims and commits `attempts=1, status='PENDING', nextRetryAt=T+2m` while A still holds its stale copy, A's completion merge rewrites `attempts=0, lastError=null`, erasing B's backoff; in the reverse order B's `handleFailure` merge resurrects a COMPLETED row to PENDING with its attempt counter reset, so `max_attempts` can never be reached and the row is dispatched indefinitely. `RadarCompositeDlqProcessor.java:22-26` states the risk and claims `@SchedulerLock` closes it — true only while the lock is held, and `lockAtMostFor` expiry is the documented end of that guarantee. Options: (a) conditional `@Modifying` UPDATE guarded on `id = ? AND status='CLAIMED' AND claimed_at = :runClaimedAt` with an affected-row check — the pattern `DeletionSchedulerService.java:84-86` already uses correctly in this same blast radius; (b) add `@Version` plus a skip-on-`OptimisticLockException` path; (c) accept as bounded by the scheduler lock.
  **Resolution — option (a) applied to both processors.** Added `completeClaimed(id, claimedAt)` and `failClaimed(id, claimedAt, status, attempts, lastError, nextRetryAt)` to both repositories, each guarded by `WHERE id = :id AND status = 'CLAIMED' AND claimed_at = :claimedAt`, and replaced every `save(row)` in both classes (4 sites in the video processor, 2 in the radar one) with them. Each write now touches only the columns its own transition owns, so nothing else can be clobbered. All six unconditional detached merges are gone.
  **Side effects were moved behind the guard, not just the write.** `decrementRefCount`, `appendDeletionLog` and the `providerAssetId = null` write are non-idempotent, so the claim check runs FIRST inside each transaction and the side effects are skipped entirely on a lost race — the same "check before, skip rather than roll back" shape `DeletionSchedulerService.markPhysicallyDeleted` already uses. A lost claim logs at INFO via a shared `logClaimLost`, matching the benign-race level `PaymentPendingSweeper`/`SessionPackExpiryNotifier` already established, because it means another instance legitimately owns the row now.
  **Honest bound on what this fixes.** It closes the bookkeeping corruption (erased backoff, resurrected COMPLETED rows, attempt counters reset so `max_attempts` could never be reached). It does NOT retroactively prevent a duplicate `deleteAsset` that already happened before the guard is reached — that is Decision 3's job, and the two compose.
  **Verification.** 3 new/updated unit tests on the radar side (guarded completion; claim-lost writes nothing and does not throw; failure path carries the same values through the guard), 8/8 green. `VideoDeletionOutboxProcessorIT` 7/7 green against real Postgres — which also empirically validates the native SQL and that `claimed_at = :claimedAt` genuinely matches through real JDBC binding, the brittleness two review layers flagged. Full sweep 111/111 green.

**Patch**

- [x] [Review][Patch] Rows claimed by an old instance during a rolling deploy are permanently unrecoverable — `resetStaleClaimed` requires `claimed_at IS NOT NULL`, but pre-change `claimPendingBatch` never stamped it; V144's one-shot backfill cannot cover rows an old instance claims *after* the migration commits. Such rows match neither `resetStaleClaimed` nor the now claim-scoped `findClaimedBatch`, and `resetStaleClaimed` is the only recovery path for CLAIMED rows. Fix: `WHERE status='CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)` in both repositories. Found independently by three layers. [`VideoDeletionOutboxRepository.java:55`, `RadarCompositeDlqRepository.java:47`]
  **Fixed.** Both repositories' `resetStaleClaimed` now match `status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)`. Verified via `RadarCompositeDlqRepositoryIT`/`VideoDeletionOutboxProcessorIT`'s new positive-recovery tests (below) which also exercise the `claimed_at IS NOT NULL` branch; the `IS NULL` branch is the rolling-deploy case itself, not independently exercised by a new test (no seam in this suite simulates "an old JAR version's claim" short of hand-writing a row with a NULL `claimed_at`, which the existing stale-window tests already cover the query shape for).
- [x] [Review][Patch] AC8 deleted two `[DECIDED]` ledger bullets the disposition table said to keep — `ModerationSlaMonitorService` mentions went 4 -> 2 and `reinstateCoach` 2 -> 0 against HEAD (verified). The disposition table says "Leave — confirmed still correctly out of scope, untouched by this story"; the Provenance section says the `reinstateCoach` bullet is "correctly **not** picked up again". The `-120` section header was removed wholesale rather than pruned, and the surviving `-121` preamble (`deferred-work.md:2370-2374`) still refers to a `[DECIDED]` bullet that no longer exists. Restore both bullets and reconcile the preamble. [`_bmad-output/implementation-artifacts/deferred-work.md`]
  **Fixed.** Both bullets restored — `ModerationSlaMonitorService`'s under a new `## Deferred from: code review of skillars-deferred-120 (2026-09-17) — remaining item` section (with a note explaining the other three original `-120` bullets were genuinely superseded and correctly deleted), `reinstateCoach`'s back into the `-121` section. The `-121` preamble is rewritten to state what actually happened (three bullets closed by this story, one always meant to survive untouched) instead of the incorrect "closed all four".
- [x] [Review][Patch] Completion Notes assert the opposite of what shipped — AC8's note claims "the `ModerationSlaMonitorService` `[DECIDED]` note left untouched" and "applied the disposition table exactly"; both are false per the finding above. AC4's note claims boot-level regression was "confirmed implicitly: every full-`@SpringBootTest` IT run ... boots every `@Scheduled` bean", but `src/test/resources/application-test.yaml:126-127` sets `app.scheduling.enabled: false` and `SchedulingConfig` is `@ConditionalOnProperty(havingValue = "true")`, so `@Scheduled` registration never happens under test and ShedLock resolves the placeholders only on actual invocation. Practical risk is nil (every expression carries a `:default`), but the verification claim is not what happened. [story file, Completion Notes]
  **Fixed.** Both Completion Notes corrected in place (AC8's and AC4's, above) to describe what actually happened rather than restate the disproven claims.
- [x] [Review][Patch] AC3 Task 7 was not performed — both processors' Javadoc still describes pre-fix behaviour as current, and cites a ledger bullet this same change deleted [`VideoDeletionOutboxProcessor.java:75-80`, `:91-92`, `:40-41`; `RadarCompositeDlqProcessor.java:22`]
  **Fixed.** `VideoDeletionOutboxProcessor`'s stale Javadoc block (the one still describing `findClaimedBatch` as globally-scoped and `resetStaleClaimed` as eligibility-keyed) is rewritten to state the AC3-fixed behavior, and its "recorded in `deferred-work.md`" claim about Radar's equal window/lock is corrected to point at Decision 3's actual fix instead. `RadarCompositeDlqProcessor`'s class-level comment is rewritten to state that `@SchedulerLock` alone does not close the gap once a run outlives `lockAtMostFor` — AC3's fetch-scoping and Decision 5's claim-guarded writes are what actually close it.
- [x] [Review][Patch] `OutboxService.java:126` points at `docs/deployment/scheduler-lock-config.md`, which does not exist — the file shipped is `scheduler-lock-tuning.md`, and the other nine converted sites all redirect to this one comment, so the entire AC4 documentation chain dead-ends. Flagged by all four layers. [`OutboxService.java:126`]
  **Fixed.** Corrected to `scheduler-lock-tuning.md`, the filename that was actually shipped.
- [x] [Review][Patch] `resetStaleClaimed` transitions out of CLAIMED without clearing `claimed_at`, contradicting the field's own Javadoc ("cleared back to `null` on **every** transition out of `CLAIMED`") and AC3 Task 6's stated rule. Harmless today, but it is the same invariant `handleFailure` reasons from when justifying the DEAD-path clear. Add `claimed_at = NULL` to the reset UPDATE, or amend the Javadoc. [`VideoDeletionOutboxRepository.java:53-55`, `RadarCompositeDlqRepository.java:45-47`]
  **Fixed** (same edit as the rolling-deploy patch above — both repositories' `resetStaleClaimed` now also `SET claimed_at = NULL`). Verified by the new positive-recovery tests asserting `getClaimedAt()` is null after a reset.
- [x] [Review][Patch] `scheduler-lock-tuning.md`'s exclusion paragraph is factually wrong and reads as exhaustive — it says the listed schedulers are excluded because "none of these carry `@SchedulerLock` at all", but at least 15 other schedulers also absent from the table *do* carry hardcoded floors (`BandwidthResetService`, `AuthCleanupService` x2, `UserAdminService`, `VideoSubscriptionLifecycleListener`, `QuickCompleteTimeoutService`, `BookingExpiryScheduler`, `BookingReminderScheduler`, `SubscriptionGracePeriodChecker`, `SubscriptionChangeApplicator`, `PaymentPendingSweeper`, `SessionPackForfeitureScheduler`, `SessionPackExpiryNotifier`, `MessageModerationSweeper`, `MessageRetentionScheduler`). Their exclusion from the table is correct — their cadence is a hardcoded literal — but the stated reason is not. [`docs/deployment/scheduler-lock-tuning.md`]
  **Verified independently before fixing** (grepped every listed class's actual `@Scheduled`/`@SchedulerLock` annotations rather than trusting the finding) — confirmed true, including a self-contradiction the finding didn't call out: the paragraph's own first item, `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`, *does* carry `@SchedulerLock` (`lockAtLeastFor = "PT0S"`), contradicting "none of these carry `@SchedulerLock` at all" in the very sentence naming it. **Fixed.** Split into the two genuinely different exclusion reasons (zero-floor vs. no-lock-at-all) plus a new paragraph naming the 14 hardcoded-cadence schedulers and why they're a third, different case.
- [x] [Review][Patch] `AdminCoachEnforcementIsolationRuntimeIT`'s ambient-transaction test passes vacuously — `observedIsolation` is seeded to `-1` and the assertion is `isNotEqualTo(TRANSACTION_REPEATABLE_READ)`, so if the spy answer never fires the test reports green having observed nothing. Its two sibling tests use `isEqualTo` and are self-guarding; only this one, the one that pins the hazard, is not. Add `assertThat(observedIsolation.get()).isNotEqualTo(-1)` first. Separately it asserts the defect as the expected outcome, so a later `validateExistingTransaction = true` fix would read as a regression — relabel it explicitly as a characterization test. [`AdminCoachEnforcementIsolationRuntimeIT.java:143-157`]
  **Fixed.** Added the `isNotEqualTo(-1)` guard before the existing assertion, and relabeled the test's Javadoc as a characterization test explicitly expected to flip if a future fix makes the method honor `REPEATABLE_READ` from inside an ambient transaction. Re-ran the class: 5/5 green (3 → 5 after this patch's own additions).
- [x] [Review][Patch] Four scheduler-lock tests lost their semantic assertion — the pre-existing `assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive()` was replaced with plain string equality against the raw `${...}` expression, so nothing verifies the shipped default is a valid, positive duration. `RadarCompositeDlqProcessorTest.java:130-133`'s own comment claims it "assert[s] the embedded default resolves to a positive duration" — it does not. `VideoLifecycleSchedulerTest:290-291`'s `defaultOf()` helper is the correct in-repo shape; apply it to the other four and fix the contradicting comment. [`RadarCompositeDlqProcessorTest.java:134`, `VideoDeletionOutboxProcessorSchedulerLockTest.java:44`, `OutboxPollerSchedulerTest.java:243`, `DeletionSchedulerServiceTest.java:155`]
  **Fixed.** Added `VideoLifecycleSchedulerTest`'s `defaultOf()` helper (copy) to all four files and an `assertThat(Duration.parse(defaultOf(...))).isPositive()` assertion alongside each existing string-equality check. Re-ran all four: `RadarCompositeDlqProcessorTest` 8/8, `VideoDeletionOutboxProcessorSchedulerLockTest` 2/2, `OutboxPollerSchedulerTest` 6/6, `DeletionSchedulerServiceTest` 4/4 — all green.
- [x] [Review][Patch] `claimed_at` stamping and clearing is untested — `grep -rn "getClaimedAt" src/test` returns nothing, so no test asserts it is nulled on COMPLETED, PENDING or DEAD. On the Radar side no test calls `claimPendingBatch` at all (`RadarCompositeDlqRepositoryIT` hand-stamps via `save()`, `RadarCompositeDlqProcessorTest` mocks the repository), so deleting `, claimed_at = :now` from the claim query leaves the suite green while production fetches zero rows forever. Both new `resetStaleClaimed` ITs assert only `isZero()`, so a predicate that matches nothing in all cases also passes. Add the claim -> fetch round-trip through the real query plus a positive recovery case. [`RadarCompositeDlqRepositoryIT.java`, `VideoDeletionOutboxProcessorIT.java`]
  **Fixed.** Added `claimPendingBatch_stampsClaimedAtSoFindClaimedBatchReturnsIt` (real claim → fetch round trip, both sides) and `resetStaleClaimed_reclaimsGenuinelyStaleClaim` (positive recovery case, both sides) to `RadarCompositeDlqRepositoryIT` and `VideoDeletionOutboxProcessorIT`. Also added `getClaimedAt()` assertions to the existing COMPLETED/PENDING/DEAD tests in `VideoDeletionOutboxProcessorIT` (previously zero assertions anywhere on that getter). `RadarCompositeDlqRepositoryIT` re-run: 4/4 green (2 → 4).
- [x] [Review][Patch] V145 leaves fresh and already-booted environments structurally divergent — existing databases carry Hibernate's `check (skillars_role in (...))` constraint; V145 creates the bare column with no CHECK, so a fresh DB gets a different shape. Adding a new enum constant would then succeed in CI/local and fail in production with a check-constraint violation, and `UserEnversAuditGapIT` runs against a fresh DB so it can never reproduce it. Add a matching CHECK or a `DROP CONSTRAINT IF EXISTS` to normalise. Note: the correct resolution depends on the `generate-ddl` decision above. [`V145__user_aud_role_verification_status.sql:34-36`]
  **Fixed.** `V145` now adds `user_aud_skillars_role_check`/`user_aud_verification_status_check` as `NOT VALID` CHECK constraints inside a name-guarded `DO $$` block, named to match Postgres's own default naming for Hibernate's unnamed inline CHECK — a no-op on an already-Hibernate-patched database (name already exists), a genuine fix on a Flyway-only one. `NOT VALID` avoids a full-table scan against existing data (safe regardless, since every row was written through the entity's own enum setters); a later migration can `VALIDATE CONSTRAINT` once that's worth the scan. `deferred-work.md`'s corresponding "still open" note updated to reflect this divergence is now closed, leaving only `main."user"`'s own (separate, riskier) width/CHECK divergence open.
- [x] [Review][Patch] `ManualStrikeIT.issueManualStrike_againstActiveCoach_stillEscalatesAtThreshold` cleans up after its assertion, so a failure leaks an `admin.admin_alerts` row that a sibling test's `alertCount == 0` assertion then trips over. Move to `@AfterEach`/try-finally. [`ManualStrikeIT.java`]
  **Disproved on verification — false positive, not fixed.** `DatabaseResetTestExecutionListener` truncates every application table (including `admin.admin_alerts`) in `beforeTestMethod`, before *every* test method in the suite runs (confirmed by reading its class Javadoc and `truncateApplicationTables` implementation) — this is the exact mechanism the project introduced specifically to replace hand-written per-test cleanup like this one. A row left behind by a failing assertion here cannot survive to affect a sibling test regardless of this test's own cleanup ordering, since the next test's `beforeTestMethod` wipes it first. Left as-is rather than "fixed" for a scenario the test harness already makes unreachable.
- [x] [Review][Patch] `AdminCoachEnforcementIsolationRuntimeIT.java:50` derives ids from `System.nanoTime() % 900_000L` — `nanoTime()`'s origin is unspecified and may be negative, and `%` preserves the dividend's sign, so ids can fall outside the intended band; two tests whose values differ by an exact multiple of 900 000 collide on the PK or unique constraints. `tearDown` also guards on `coachId != null`, leaking the raw user row if `seedCoach()` throws after the insert. [`AdminCoachEnforcementIsolationRuntimeIT.java:50`]
  **First half fixed, second half disproved on verification.** Switched to `Math.floorMod(System.nanoTime(), 900_000L)`, which is always in `[0, 900_000)` regardless of `nanoTime()`'s sign. The teardown-leak half does not hold: `seedCoach()` wraps both the raw user insert and the `CoachProfile` save in one `transactionTemplate.execute` block (added earlier in this same story's own AC6 debug pass, per the Debug Log), so an exception from `coachProfileRepository.save(...)` rolls back the whole block, including the user insert — there is no window where the user row commits but `coachId` stays unset.
- [x] [Review][Patch] AC8's `main."user"` index bullet was annotated in place rather than moved to an "Explicitly out of scope" section as the disposition table specifies — intent is met (not silently dropped), letter is not; no such section exists in the file. [`deferred-work.md:2394`]
  **Fixed.** Created `## Explicitly out of scope (skillars-deferred-123, 2026-09-18)` and moved the `main."user"` index bullet into it, alongside a cross-reference to the restored `ModerationSlaMonitorService` bullet (the disposition table's other "Leave" item).

**Deferred (pre-existing, not caused by this change)**

- [x] [Review][Defer] `ReliabilityStrikeService`'s pre-lock strike INSERT takes `FOR KEY SHARE` on the coach row via the FK, so two concurrent `issue()` calls can lock each other out of the `FOR UPDATE NOWAIT` for the whole retry budget [`ReliabilityStrikeService.java:62` vs `:98`] — deferred, pre-existing
- [x] [Review][Defer] V144's `ALTER TABLE ... ADD COLUMN` under `lock_timeout = '5s'` targets two tables polled every 60 s, so a single overlap aborts the migration and fails the deploy with a failed `flyway_schema_history` row [`V144__outbox_dlq_claimed_at.sql:37-43`] — deferred, pre-existing convention
- [x] [Review][Defer] Neither processor loop has a per-row try/catch, so an exception outside the inner `try` abandons the whole claimed batch; AC3 raises the re-eligibility latency for that case from ~one tick to a full stale window [`VideoDeletionOutboxProcessor.java:119-121`, `RadarCompositeDlqProcessor.java:68-70`] — deferred, pre-existing loop shape
- [x] [Review][Defer] Envers reconstruction of pre-V145 revisions returns `null` for `verificationStatus`, defeating the field initialiser; latent only — no `AuditReader`/`AuditQuery` exists in `src/main` today [`User.java:355-357`] — deferred, latent
- [x] [Review][Defer] `AdminActionLog` is written in the outer transaction after `issue()`'s `REQUIRES_NEW` has already committed the strike and its events, so an outer rollback leaves a durable enforcement action with no audit trail of which admin issued it [`AdminCoachEnforcementService.java:253-260`] — deferred, pre-existing
- [x] [Review][Defer] `findClaimedBatch` uses exact timestamp equality as a run-identity token; correct today because both statements bind the same `Instant` through the same path, but a `claimed_by UUID` is the robust shape and the failure mode is a silent no-op tick [`VideoDeletionOutboxRepository.java:37`, `RadarCompositeDlqRepository.java:36`] — deferred, works today
- [x] [Review][Defer] `deleteStrike`'s cutoff is compared with nanosecond precision in Java (`strikeCreatedAt.isAfter(cutoff)`) and microsecond precision in SQL (pgjdbc rounds the bound value), so the new comment's claim that "a boundary strike is judged identically wherever this local is used" does not hold across the Java/SQL boundary; probability ~1e-9 per call [`AdminCoachEnforcementService.java:300`, `:317`] — deferred, negligible probability

**Disproved on verification (not carried forward)**

- Blind Hunter claimed the five schedulers without test updates now have *broken* tests (`Duration.parse` against a `${...}` string). Verified false: `grep` across all of `src/test` shows `OutboxService`, `QuotaReservationTimeoutService`, `EmailRetryScheduler`, `SluSnapshotAppliedRetentionService` and `NeglectedSkillDetectionService` have **no** `@SchedulerLock` assertions at all. No test breaks; the change to those five is simply untested, which is covered by the test-coverage patch above.
- Blind Hunter claimed "the entire AC4 premise is now unverified" because no test proves ShedLock resolves `${...}`. Overstated: the premise was verified by decompilation in the story and independently reconfirmed here (`SpringLockConfigurationExtractor` routes both attributes through `StringValueResolver.resolveStringValue`). The residual test-coverage gap survives as a patch.
- The transaction/concurrency layer assessed V144 as clean, specifically crediting the `CLAIMED`-row backfill with preventing stranded rows. Superseded: three other layers plus direct verification against `master` show the backfill cannot cover rows claimed by an old instance after the migration commits.
- Both migrations were checked against `MigrationLint`'s rule implementations rather than assumed: `UNBATCHED_DML` fires only on a missing or tautological top-level `WHERE` (V144's backfill carries a real bounding predicate) and `MISSING_LOCK_TIMEOUT` is satisfied by `SET lock_timeout = '5s'` in both. No lint violation.

**Scope note on rolling-deploy findings:** `skillars-deferred-117` recorded the owner decision that no production deploy has ever happened. The rolling-deploy hazards above (the stranding patch and the V144 lock-timeout defer) are therefore currently theoretical, but both become live at first deploy and the stranding fix is a three-word predicate change.
## Dev Notes

**Cross-AC dependencies:** AC1 now touches `ReliabilityStrikeService.issue` (not
`AdminCoachEnforcementService.issueManualStrike`, which is unchanged by this story), and AC2 touches
`AdminCoachEnforcementService.deleteStrike`'s neighborhood plus `ReliabilityStrikeService.issue` (a
different part of the same file AC1 touches) — implement and test independently, but note both land in
`ReliabilityStrikeService.issue`: AC1 guards the escalation blocks, AC2 hoists the cutoff capture above
the lock wait; sequence them as separate commits so a reviewer can revert either without the other. AC3
(video/radar claim time, now including the `findClaimedBatch` scoping fix) and AC4 (scheduler lock
config, now a pure annotation-literal property-ization with **no** production check code) are both in
the scheduler/outbox space but are independent fixes to independent bugs — do not conflate "add
`claimed_at`" with "property-ize `lockAtLeastFor`"; a reviewer should be able to revert either without
touching the other. AC5 (Envers) is standalone and must be **investigated before any migration or
`@NotAudited` annotation is written** — do not pre-emptively guess which of the three outcomes applies;
confirm first. AC6 adds test coverage only, no production code change, and has no dependency on any
other AC. AC7 is documentation-only, and its ledger citation must use the corrected file:line (see
AC7's citation-correction note), not the original ledger bullet's stale one.

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

## File List

**Production code:**
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java` (AC1, AC2)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java` (AC2; Review Decision 2 — `reinstateCoach` re-evaluates the in-window strike count before deciding whether to resolve the open alert)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java` (AC7)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutbox.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC3; Review Patch — rolling-deploy `resetStaleClaimed` predicate + `claimed_at` clear; Review Decision 5 — guarded `completeClaimed`/`failClaimed`)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC3, AC4; Review Decision 3 — self-terminating `MAX_RUN_DURATION` + `releaseClaimed`; Review Decision 5 — claim-guarded writes)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqEntry.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java` (AC3; Review Patch — rolling-deploy `resetStaleClaimed` predicate + `claimed_at` clear; Review Decision 5 — guarded `completeClaimed`/`failClaimed`)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java` (AC3, AC4; Review Decision 3 — self-terminating `MAX_RUN_DURATION` + `releaseClaimed`; Review Decision 5 — claim-guarded writes)
- `src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java` (AC4; Review Patch — doc-link filename fix)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/EmailRetryScheduler.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/development/service/SluSnapshotAppliedRetentionService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java` (Review Decision 4, new check — cross-checks each `@SchedulerLock` pair's resolved `lockAtLeastFor`/`lockAtMostFor`, overriding AC4's original no-boot-check text)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (AC5, comment only)
- `src/main/resources/application.yaml` (Review Decision 1 — `spring.jpa.generate-ddl: true` removed, replaced with a decompiled-mechanism comment)
- `src/main/resources/db/migration/V144__outbox_dlq_claimed_at.sql` (AC3, new)
- `src/main/resources/db/migration/V145__user_aud_role_verification_status.sql` (AC5, new; CHECK constraints added by Review Patch)
- `src/main/resources/db/migration/V146__user_phone_provider_column.sql` (Review Decision 1 follow-up, new — `PhoneNumber.provider` column Hibernate had been silently creating)
- `src/main/resources/db/migration/V147__file_storage_objects_auditing_columns.sql` (Review Decision 1 follow-up, new — `AbstractAuditingEntity` columns on `main.file_storage_objects`)
- `src/main/resources/db/migration/V148__audit_log_and_user_authority_aud_tables.sql` (Review Decision 1 follow-up, new — `main.audit_log` and `main.user_authority_aud` tables)
- `docs/deployment/scheduler-lock-tuning.md` (AC4, new; ceiling column + ordering constraint added by Review Decision 4; exclusion-paragraph fix by Review Patch)

**Tests:**
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` (AC1, +3 tests; CI fix — nanosecond-vs-microsecond precision truncation, see Change Log)
- `src/test/java/com/softropic/skillars/platform/admin/api/ReinstateIT.java` (Review Decision 2 — `reinstateCoach_withStrikesStillInWindow_setsActiveButLeavesAlertOpen` + teardown hardening)
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java` (AC1, +2 tests)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertionTest.java` (Review Decision 4, new — 6 fixture-driven tests for the scheduler-lock floor/ceiling cross-check)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java` (AC3, +2 tests; Review Patch — claim/fetch round-trip + `getClaimedAt()` assertions)
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java` (AC3, new file; Review Patch — claim/fetch round-trip + positive-recovery tests)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java` (AC3 mock-signature fix + AC4 assertion fix; Review Decision 3/5 coverage)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorSchedulerLockTest.java` (AC4 assertion fix; Review Decision 3 invariant test)
- `src/test/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerServiceTest.java` (AC4 assertion fix)
- `src/test/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerSchedulerTest.java` (AC4 assertion fix)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java` (AC4 assertion fix)
- `src/test/java/com/softropic/skillars/platform/security/repo/UserEnversAuditGapIT.java` (AC5, new file)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementIsolationRuntimeIT.java` (AC6, new file, 3 tests; Review Patch — vacuous-pass guard + characterization-test relabel)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementServiceIsolationTest.java` (AC6, Javadoc only)

**CI (found by running the actual GitHub Actions gate, not locally):**
- `.github/workflows/pr-build.yml` (Spring-context ceiling call site bumped 42 -> 43)
- `.github/scripts/assert-context-count.sh` (ceiling history comment + default bumped to match)

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC8; Review Patch — restored 2 wrongly-deleted `[DECIDED]` bullets, added the new Hibernate-DDL-bypass and `main."user"` cleanup-index items)
- `_bmad-output/implementation-artifacts/story-review.md` (senior-dev pre-implementation audit — 18 findings, incorporated into the ACs above during dev rather than left as a separate response pass)
- `_bmad-output/implementation-artifacts/skillars-deferred-123-strike-timing-scheduler-lock-config-and-envers-audit-gap-fixes.md` (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-09-18: Dev complete (`/bmad-dev-story`). All 8 ACs implemented and independently verified —
  see Dev Agent Record → Completion Notes for the full summary. No `mvn verify` run locally per project
  convention — GitHub CI is the sole full-verification gate.
- 2026-09-18: Code-review Decision response (`/bmad-code-review`, four parallel layers). All 5
  `[Review][Decision]` findings resolved with owner-approved options applied (see Review Findings above
  for the full write-up of each): (1) `spring.jpa.generate-ddl: true` removed from `application.yaml`
  after AC5's documented mechanism was found wrong — three follow-up migrations (V146-V148) close the
  schema drift the property had been silently masking, discovered via a real Testcontainers boot with
  `org.hibernate.SQL` at DEBUG, not guessed; (2) `reinstateCoach` now re-evaluates the in-window strike
  count under its existing lock and withholds the alert resolve (not the status write) when still
  elevated; (3) both outbox/DLQ processors gained a self-terminating `MAX_RUN_DURATION` deadline plus
  `releaseClaimed` so a bail-out cannot strand rows for a full stale window; (4) `ConfigStartupAssertion`
  gained a new boot-time cross-check of every `@SchedulerLock` pair's resolved floor/ceiling — a
  deliberate, recorded override of AC4's own "no boot-time check" text, since AC4 itself made the floor
  operator-settable against a mostly-hardcoded ceiling; (5) both processors' unconditional detached-entity
  merges replaced with claim-guarded `UPDATE`s (`completeClaimed`/`failClaimed`) closing a real lost-update
  path between concurrent claim windows.
- 2026-09-18: Code-review Patch response. All 11 `[Review][Patch]` findings addressed — 10 fixed, 1
  (`ManualStrikeIT` cleanup ordering) disproved on verification and left as-is (see that item's
  resolution note for why). Each fix's targeted test class re-run against real Postgres/Testcontainers:
  `RadarCompositeDlqRepositoryIT` 4/4, `RadarCompositeDlqProcessorTest` 8/8,
  `VideoDeletionOutboxProcessorSchedulerLockTest` 2/2, `OutboxPollerSchedulerTest` 6/6,
  `DeletionSchedulerServiceTest` 4/4, `VideoDeletionOutboxProcessorIT` 9/9,
  `AdminCoachEnforcementIsolationRuntimeIT` 3/3, `ManualStrikeIT` 13/13,
  `MigrationConventionLintTest` 13/13, `UserEnversAuditGapIT` 1/1 — 0 failures, 0 errors across all ten
  classes. `mvn compile`/`mvn test-compile` both BUILD SUCCESS. No `mvn verify` run locally per project
  convention.
- 2026-09-18: 7 `[Review][Defer]` findings recorded as pre-existing/out-of-scope (see Review Findings
  above), 3 review-layer claims independently disproved and not carried forward. File List and Change
  Log reconciled against the final diff (added `application.yaml`, `ConfigStartupAssertion`/
  `ConfigStartupAssertionTest`, `ReinstateIT`, and migrations V146-V148, none of which were captured when
  the Decision resolutions above first landed).
- 2026-09-18: PR #211 CI run (`pr-build.yml`) surfaced 2 issues neither local runs nor the review layers
  caught, since both are properties of the real GitHub Actions environment rather than the code path
  under review:
  1. **`ManualStrikeIT.issueManualStrike_againstSuspendedCoach_recordsStrikeWithoutEscalation` failed**
     (`expected: ...440412Z but was: ...440000Z`) — not a production bug. The test's own
     `originalStatusChangedAt` was captured via bare `Instant.now()` (nanosecond precision) and later
     compared for exact equality against the same value read back through a raw-JDBC round trip through
     Postgres `timestamptz`, which only stores microsecond precision — an assertion that was never
     reliably true regardless of environment, not a flake specific to CI. Fixed by truncating the
     captured instant to `ChronoUnit.MICROS` before use, matching this codebase's own
     `RescheduleResourceIT`/`RescheduleServiceConcurrencyIT`/`SoftDeleteIT` precedent for the same class
     of Postgres-precision mismatch. `ReliabilityStrikeService.issue`'s production logic that this test
     exercises (the `alreadyOffMarketplace` guard added by AC1) was independently re-verified correct —
     it never calls `coachProfileRepository.save()` at all when the coach is already
     `SUSPENDED`/`DEACTIVATED`, so `statusChangedAt` genuinely cannot move; the assertion was failing on
     its own precision, not on a real regression.
  2. **`assert-context-count.sh`'s Spring-context ceiling gate failed** (`missCount` 43 > ceiling 42) —
     `AdminCoachEnforcementIsolationRuntimeIT` (AC6) is the first and only class in the suite to
     `@MockitoSpyBean` `CoachProfileRepository`, which forks a new Spring context by the same
     one-new-config-forks-one-context mechanism `assert-context-count.sh`'s own header already documents
     for `AccountDeletionCascadeIT`/`SmtpTransportBootIT`/`SesCutoverPreflightResourceIT`. Ceiling bumped
     42 -> 43 at both the `pr-build.yml` call site and the script's own default/history comment, with a
     dated justification matching the file's established convention. In passing, noted (but did not
     re-investigate) that the prior 41 -> 42 bump from `skillars-deferred-121` had never been appended to
     that history comment — not this story's regression to fix, flagged only.
  Both fixes are test/CI-infrastructure only; no production code changed as a result. `ManualStrikeIT`
  re-run locally against real Testcontainers Postgres after the fix: 13/13 green (56.09s). `mvn
  test-compile` (`-DskipFrontend`) BUILD SUCCESS. Status → done.

## Dev Agent Record

### Implementation Plan

Implemented in dependency-aware order, each AC's own targeted tests run green before moving to the
next (red-green-refactor per AC, not per file):

1. **AC1 + AC2 together** (`ReliabilityStrikeService.issue`, `AdminCoachEnforcementService.deleteStrike`)
   — both land in `ReliabilityStrikeService.issue` (AC1 guards the escalation blocks, AC2 hoists the
   cutoff capture), landed as one coherent change per method since they touch adjacent lines, but kept
   conceptually separable per the Dev Notes' "sequence them as separate commits" guidance (reviewable
   independently by diff hunk). AC7's doc-only comment landed alongside AC2 since both touch
   `deleteStrike`'s neighborhood and AC7 needed AC2's own corrected citation.
2. **AC3** (`claimed_at` column + both repositories + both processors) — migration first, then entity
   fields, then repository query changes, then processor call-site changes (stamp/scope/clear), then
   tests — mirrored across the video and radar sides together since they're structurally identical.
3. **AC4** (10 scheduler annotations) — mechanical property-ization, then fixed the 4 existing reflection
   tests whose `Duration.parse(lock.lockAtLeastFor())` broke once the literal became a property
   expression, then a documentation note.
4. **AC5** (Envers investigation) — investigated empirically before writing any fix, per the story's own
   explicit instruction not to guess; the investigation IT's result contradicted the story's own
   candidate framing (see Completion Notes), so the actual fix follows the empirical finding, not the
   original (a)/(b)/(c) options.
5. **AC6** (isolation runtime test) — implemented the probe-via-repository-spy design from the story,
   working around two Mockito/Spring-Data-proxy interop issues found only by running it (see Debug Log).
6. **AC8** (ledger closeout) — last, since it needed AC7's corrected citation and AC5's actual finding
   in hand.

### Debug Log

- **AC5 investigation (empirical, see Completion Notes for the full write-up):** the initial IT
  (`UserEnversAuditGapIT`, first draft) asserted `main.user_aud` would NOT contain `skillars_role`/
  `verification_status` keys (matching the story's premise, read from `V138__baseline_schema.sql`
  alone) — it failed, revealing the columns already exist at runtime. Traced the cause by raising
  `org.hibernate.SQL` to DEBUG on a second run: Hibernate itself issues `alter table ... add column`
  DDL for both columns (with a `CHECK` constraint) at boot, confirmed independent of
  `hibernate.ddl-auto: none`, and confirmed independent of any Flyway migration by inspecting
  `main.flyway_schema_history` directly (only V138–V143 applied). Rewrote the IT to assert the actual,
  now-understood behavior (a permanent regression check, not a one-off investigation artifact).
- **AC6 isolation-probe Mockito issues (two, found only by running the test, not by inspection):**
  (1) `invocation.callRealMethod()` throws `MockitoException: Cannot call abstract real method on java
  object` for a `@MockitoSpyBean` of a Spring Data repository — it's a JDK dynamic proxy (interface),
  not a concrete class, so there's no "real method" bytecode for Mockito to invoke directly. Fixed by
  reusing the spy's own `defaultAnswer` (`mockingDetails(...).getMockCreationSettings()
  .getDefaultAnswer()`) inside the `doAnswer` block instead — Spring's `@MockitoSpyBean` already wires
  *some* delegation mechanism as the default answer regardless of proxy type, so replaying it avoids
  needing to know which mechanism it is. (2) The seeding/teardown helper's raw `JdbcTemplate` insert
  and the JPA repository's own delete needed to share one `TransactionTemplate.execute` block, not run
  separately — a bare `JdbcTemplate` call with no active Spring transaction acquires a fresh,
  autocommit-false (per this project's `hikari.auto-commit: false`) connection and never commits it
  before it's returned to the pool, so an unwrapped raw insert is invisible to a subsequent JPA call on
  a different pooled connection. Mirrors `ManualStrikeIT`'s established `setUp`/`tearDown` pattern.
- **AC3 test scaffolding:** `RadarCompositeDlqProcessorTest`'s four `findClaimedBatch()` mock stubs
  needed updating to the new two-arg signature (`any(), anyInt()`) — a mechanical fallout of the
  repository signature change, not a design issue.

### Completion Notes

All 8 ACs implemented and independently verified green — 145 tests across every touched/regression-relevant
suite run together (`ReliabilityStrikeServiceTest` 8/8, `ManualStrikeIT` 13/13, `VideoDeletionOutboxProcessorIT`
7/7, `RadarCompositeDlqRepositoryIT` 2/2 new, `RadarCompositeDlqProcessorTest` 5/5, `VideoDeletionOutboxProcessorSchedulerLockTest`
1/1, `MigrationConventionLintTest` 13/13, `UserEnversAuditGapIT` 1/1 new, `VideoLifecycleSchedulerTest` 9/9,
`DeletionSchedulerServiceTest` 4/4, `OutboxPollerSchedulerTest` 6/6, `AdminCoachEnforcementServiceIsolationTest`
2/2, `AdminCoachEnforcementIsolationRuntimeIT` 3/3 new, `AdminCoachEnforcementConcurrencyIT` 4/4, `OutboxServiceTest`
6/6, `OutboxServiceAsyncWiringTest` 3/3, `QuotaReservationTimeoutServiceTest` 3/3, `NeglectedSkillDetectionServiceTest`
16/16, `NeglectedSkillDetectionServiceIT` 1/1, `SluSnapshotAppliedRetentionServiceIT` 2/2, `EmailRetrySchedulerTest`
9/9, `EmailRetrySchedulerIT` 9/9, `CoachSuspensionIT` 5/5, `CoachEnforcementListIT` 3/3, `ReinstateIT` 3/3,
`SchedulerLockTransactionOrderingIT` 5/5, `FileStorageDeletionIT` 5/5) — 0 failures, 0 errors, 0 regressions.
No local `mvn verify` per project convention — GitHub CI is the sole full-verification gate.

**AC1 — strike-escalation suppression for an already-off-marketplace coach:**
`ReliabilityStrikeService.issue` now computes `alreadyOffMarketplace` (`SUSPENDED`/`DEACTIVATED`) right
after the locked coach read and gates both escalation blocks on it — a deny-list, so any other status
(including any added later) keeps today's escalation behavior. The strike `save()` is unconditional and
untouched, so a strike against an already-suspended coach is still recorded as a compliance record, just
without a status-change side effect. This is the single point both the manual
(`AdminCoachEnforcementService.issueManualStrike`) and automatic (`CancellationRefundService`'s listener
callers) strike paths converge on. New tests: `ManualStrikeIT` (+3 — SUSPENDED, DEACTIVATED, and an
ACTIVE-coach regression guard proving escalation is unaffected for every other status) and
`ReliabilityStrikeServiceTest` (+2 — SUSPENDED/DEACTIVATED unit coverage of the automatic path).

**AC2 — 30-day cutoff captured before the lock wait:** both `ReliabilityStrikeService.issue` and
`AdminCoachEnforcementService.deleteStrike` now capture `OffsetDateTime.now().minusDays(30)` into a
local *before* `lockRetryer.withBoundedRetry`, removing the window-origin skew a contended call could
previously introduce (bounded by `PessimisticLockRetryer`'s ~3.2s retry budget). `getCoachesUnderEnforcement`
and `getEnforcementProfile`'s own inline `now()` calls are deliberately left alone — read-only, no lock
wait, not subject to this skew. No new test for the ordering itself per the story's own explicit
decision (no `Clock` seam exists today; the existing concurrency/isolation suites are the regression
guard) — verified by re-running them.

**AC3 — `claimed_at` column closes the outbox/DLQ double-processing path:** `V144__outbox_dlq_claimed_at.sql`
adds `claimed_at timestamptz` to both `main.video_deletion_outbox` and `development.radar_composite_dlq`,
with a backfill for any row already `CLAIMED` at migration time. `claimPendingBatch` now stamps it;
`findClaimedBatch` is now scoped to `WHERE claimed_at = :claimedAt` (closing the fetch-side half of the
path the original `V138`-era `WHERE status = 'CLAIMED'` left open even after `resetStaleClaimed` alone
would have been fixed); `resetStaleClaimed` now keys staleness on `claimed_at`, not `next_retry_at`
(closing the eligibility-vs-claim-time half). Both processors clear `claimed_at` back to `null` on every
transition out of `CLAIMED` — completion and *both* failure outcomes (`PENDING` and `DEAD` — a `DEAD`
row keeping a stale `claimed_at` would give a false claim-age reading if ever re-queued). New/updated
tests: `VideoDeletionOutboxProcessorIT` (+2 — stale-claim-vs-claim-time discrimination,
run-scoped-fetch discrimination), `RadarCompositeDlqRepositoryIT` (new file, mirrors the same two tests
for the Radar side, which had no prior IT coverage of its claim/reset queries against a real database).

**AC4 — scheduler-lock floors made operator-tunable:** all 10 sites the story's corrected audit
identified now property-ize their `lockAtLeastFor` (and, for the 3 cron-scheduled jobs, `lockAtMostFor`
too) — `${<key>:<original-literal>}`, default unchanged. Confirmed via decompiling
`shedlock-spring:7.10.1` (cited in the story) that ShedLock resolves these placeholders; this is a pure
annotation-literal change with no new production class or boot-time check. `DeletionSchedulerService`
gets its own independent lock-floor key (`app.storage.deletion.lock-at-least`) rather than reusing
`OutboxPollerScheduler`'s, even though both currently share the same cadence property — they are two
independent schedulers/locks. Updated the 4 existing reflection tests whose `Duration.parse(lock
.lockAtLeastFor())` assertions would otherwise throw on the now-non-ISO-8601 property-expression string
(`VideoDeletionOutboxProcessorSchedulerLockTest`, `RadarCompositeDlqProcessorTest`,
`DeletionSchedulerServiceTest`, `OutboxPollerSchedulerTest`, `VideoLifecycleSchedulerTest` — 5 files, the
story's own audit undercounted this at "4"). Added `docs/deployment/scheduler-lock-tuning.md` naming all
10 new properties and their defaults. **Correction (2026-09-18, code review Patch — this note originally
overclaimed what the test suite proves):** `src/test/resources/application-test.yaml` sets
`app.scheduling.enabled: false`, and `SchedulingConfig` is `@ConditionalOnProperty(havingValue = "true")`
— `@Scheduled` registration, and therefore ShedLock's own property resolution of these annotations,
never happens under any `@SpringBootTest` in this codebase. No IT run exercised boot-level resolution of
the 10 new expressions; that gap is closed separately by `ConfigStartupAssertion`'s own boot-time check
(added by this story's Decision 4, which resolves the same `${...}` expressions via `Environment` and is
covered by real fixture-driven tests — see that decision's write-up above) and by the AC4 test-coverage
patch item, not by IT boot behavior. Practical risk was always low regardless (every expression carries
a `:default`, so a malformed key would only matter once a value override was actually set), but the
verification claim itself was not what the test suite does.

**AC5 — Envers `user_aud` gap: empirical finding differs from all three original candidates.**
Investigated via a real IT (`UserEnversAuditGapIT`) before writing any fix, per the story's explicit
instruction. Actual mechanism, confirmed by raising `org.hibernate.SQL` to DEBUG at boot: Hibernate/Envers
itself issues `alter table ... add column ... check (...)` DDL for both missing audit columns —
*independent of `hibernate.ddl-auto: none`* — and separately widens the *entity's own*
`main."user".skillars_role`/`verification_status` columns from their declared `varchar(20)` to
Hibernate's default `varchar(255)`, on every boot, in every environment, entirely outside Flyway's
tracking (`main.flyway_schema_history` shows no migration responsible). This is none of the story's
three candidate outcomes (a)/(b)/(c) — it's a distinct, more concerning mechanism (silent Hibernate
schema drift bypassing `ddl-auto`), and the columns already round-trip real values correctly in every
environment that has booted the app since these fields were added. Fix: `V145__user_aud_role_verification_status.sql`
makes the two audit columns explicit and Flyway-tracked (matching Hibernate's own already-applied
`varchar(255)` width, not fighting an ALTER on a populated column for no functional benefit) — a no-op
everywhere Hibernate already patched it, a genuine fix anywhere it hasn't yet booted since these fields
were added. No `@NotAudited` added — a role/verification-status change is plausibly meaningful history,
unlike `V143`'s operational-marker precedent. `User.java`'s field Javadoc documents the actual finding
so a future reader doesn't re-open the investigation from zero. The broader Hibernate-DDL-bypass finding
(any enum column, codebase-wide) is recorded as a new `deferred-work.md` item, out of this AC's scope to
fix wholesale.

**AC6 — genuine runtime isolation-level test:** `AdminCoachEnforcementIsolationRuntimeIT` (new, 3 tests)
reads the *effective* Postgres transaction isolation from inside a real `getEnforcementProfile`/
`getCoachesUnderEnforcement` call via a `CoachProfileRepository` `@MockitoSpyBean` that captures
`DataSourceUtils.getConnection(dataSource).getTransactionIsolation()` before delegating — no
production-code change, no hand-written SQL reproducing either method's own query shape (the story's own
correction of the originally-specified two-JDBC-connection design, which could not have observed
Spring's `validateExistingTransaction` behavior at all). Covers both methods at a fresh top-level call
(asserts `TRANSACTION_REPEATABLE_READ`) and the documented hazard — invoked from inside an ambient
`TransactionTemplate` (asserts the isolation is silently *not* `REPEATABLE_READ`, pinning the hazard as
observed behavior). `AdminCoachEnforcementServiceIsolationTest` (the existing annotation-presence test)
is kept, its Javadoc updated to point at the new test as the runtime-behavior proof it was waiting on.

**AC7 — strike-DELETE unbounded wait documented as accepted risk:** code comment added at
`CoachReliabilityStrikeRepository.deleteByIdAndCoachId` with the corrected citation
(`AdminCoachEnforcementService.java:285,293` — the bulk delete now runs before the lock-retry timer
starts, not folded into `persistence.lock_retry` as the original ledger bullet's stale citation
described). No functional change, no new test — documentation-only per the story.

**AC8 — ledger closeout:** initial pass deleted 5 bullets (superseded by direct fixes), annotated 1
`[DECIDED: accepted risk — skillars-deferred-123]` with corrected citation (not deleted), added 1
new bullet (the broader Hibernate-DDL-bypass finding AC5's investigation surfaced), and added a
`## Last audit: 2026-09-18` narrative block summarizing what was checked/closed and explicitly listing
the two corrected-premise items (AC3, AC4) so a future reader doesn't quote the original framing from
git history.

**Correction (2026-09-18, code review Patch — this note originally claimed the disposition table was
applied exactly; it was not).** The `-120` section header was removed wholesale rather than pruned
bullet-by-bullet, which deleted the `ModerationSlaMonitorService` `[DECIDED]` note along with the three
genuinely-superseded bullets it sat beside — the disposition table said "Leave", not delete. The same
wholesale-removal mistake took the `-121` section's `reinstateCoach` `[DECIDED: skillars-deferred-122]`
bullet down with it. Both are restored (`## Deferred from: code review of skillars-deferred-120 (2026-
09-17) — remaining item` and the `-121` section respectively), and the `-121` preamble is corrected to
explain what actually happened rather than restate "closed all four" (only three were this story's to
close; the fourth was always meant to survive untouched). The `main."user"` cleanup-sweep-index item was
also moved into a new `## Explicitly out of scope (skillars-deferred-123, 2026-09-18)` section, matching
the disposition table's literal instruction (it had only been annotated in place before).

No ACs were removed or added during implementation. File List and Change Log above are complete.
