# Story: Strike Lock-Contention, Outbox Error-Isolation & Schema-Width Reconciliation Fixes

**Story Key:** `skillars-deferred-124-strike-lock-contention-outbox-resilience-and-schema-fixes`
**Epic:** Deferred Work
**Priority:** High (one genuine mutual-lock-out bug in the strike-issue path, one error-isolation gap
that abandons whole outbox/DLQ batches on a single row's exception, one schema-width divergence
between two live environments, one brittle claim-identity mechanism, and one audit-trail durability
gap) — plus two documentation-only closures and the standard ledger closeout.
**Status:** ready-for-dev
**Created:** 2026-09-19

---

## Provenance & Scoping (read before starting)

This story mines the **two most recent, still-open code-review deferrals** in `deferred-work.md` per
this project's established "read same-day code-review deferrals before drawing scope" convention:

- **`## Deferred from: code review of skillars-deferred-123-strike-timing-scheduler-lock-config-and-
  envers-audit-gap-fixes (2026-09-18)`** — 7 bullets, all freshly surfaced by that story's own
  four-layer `/bmad-code-review` and never picked up by any implementation (deferred-123 closed its
  *own* review findings via Decisions/Patches during that story's response pass; these 7 are a
  *second*, separate batch the review recorded as genuinely out of that story's scope — pre-existing
  behaviour or systemic risk, not caused by deferred-123's changes, but real).
- **`## Deferred from: code review of skillars-deferred-122-coach-enforcement-round-2-and-user-
  cleanup-fixes (2026-09-18)`** — 1 bullet remains open (the `main."user"` schema-width divergence);
  its sibling bullet in the same section was already closed at source by deferred-123's own review.

**All line numbers below were re-verified against `master@3bba9f8d`** (skillars-deferred-123's merge,
PR #211 — the current tip at story-creation time) **on 2026-09-19, not copied from the ledger.**

**Ledger `[CLOSED by ...]` / `[DECIDED]` sweep:** grepped the whole file for `CLOSED by` before
selecting items — no hits touch any file or symbol this story scopes. Two `[DECIDED]` bullets sit
immediately adjacent to this story's mined sections and are **correctly not picked up again**:
`ModerationSlaMonitorService`'s no-`@SchedulerLock` note (re-confirmed out of scope by deferred-123
already) and `reinstateCoach`'s stale-vs-fresh-suspension note (`[DECIDED: skillars-deferred-122]`,
also re-confirmed by deferred-123). Neither is touched by this story.

**Pre-implementation reminder:** diff every cited line range against HEAD again immediately before
starting each AC — this project's own history (deferred-121→122→123 in sequence) shows citations
shifting by dozens of lines between story creation and dev start whenever an intervening story merges
first. No intervening merge is expected here, but re-check anyway; it costs nothing and this ledger's
own bullets have twice previously been drafted against stale citations.

**Three owner decisions taken live with the user (AskUserQuestion) before drafting** — see AC5, AC7
and AC4 below for the full reasoning:

1. **AC5 (audit-trail durability gap):** give `AdminActionLog` its own `REQUIRES_NEW` write via the
   established self-proxy pattern, rather than accepting the gap as documented risk.
2. **AC7 (migration-vs-active-poller race):** document only, in `migration-conventions.md`. No
   migration-retry infrastructure — that would be materially larger scope than this story's other
   items and the risk is currently theoretical (no production deploy has ever happened per
   skillars-deferred-117's owner decision, re-confirmed still true).
3. **AC4 (`claimed_by` hardening):** include now, while the claim mechanism is already fresh context
   from deferred-123's own AC3 — rather than deferring the brittleness as accepted-for-now.

Everything else below is a direct fix or a direct documentation closure with an established in-repo
precedent to mirror — no further decision needed.

**8 ACs, bundled per this project's "do not create small stories" convention** (mirrors
deferred-118 through deferred-123's own bundling of unrelated fixes into one story): AC1
(`ReliabilityStrikeService.issue` mutual lock-out), AC2 (per-row exception isolation on both outbox/
DLQ processors), AC3 (`main."user"` column-width reconciliation), AC4 (`claimed_by` UUID hardening),
AC5 (audit-trail `REQUIRES_NEW` fix), AC6 (`deleteStrike` comment correction), AC7
(migration-conventions.md documentation), AC8 (standard ledger closeout — folds in the Envers-null
item as a `[DECIDED]` annotation rather than a ninth AC, since it is documentation-only and has no
task list of its own beyond the one comment).

---

## AC1 — Fix `ReliabilityStrikeService.issue`'s INSERT-before-lock mutual lock-out

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 1.

**Current code** (`ReliabilityStrikeService.java`, re-verified at HEAD):

```java
// :50  public CoachReliabilityStrike issue(UUID coachId, UUID bookingId, String reason) {
// :51-55  strike = new CoachReliabilityStrike(); ... strike.setAcknowledged(false);
// :62  CoachReliabilityStrike saved = strikeRepository.save(strike);
// :64-67  read suspensionThreshold / visibilityThreshold
// :77  OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);   // AC2 from deferred-123
// :98  CoachProfile coach = lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)...);
```

**The bug.** `coach_reliability_strikes_coach_id_fkey` (`V138__baseline_schema.sql:4427`) makes
PostgreSQL take `FOR KEY SHARE` on the parent `coach_profiles` row for the child INSERT at `:62`,
held until the enclosing transaction commits or rolls back. `CoachProfileRepository.findByIdForUpdate`
is `PESSIMISTIC_WRITE` with `jakarta.persistence.lock.timeout = 0` — `FOR UPDATE NOWAIT` — which
conflicts with `FOR KEY SHARE`. `PessimisticLockRetryer.java:132` flushes the persistence context
*before* taking its savepoint at `:133-134` (deliberately — see that class's own Javadoc,
`:31-35` — a pending write must be visible to the DB before the savepoint boundary, or a
rollback-to-savepoint could silently undo a flush Hibernate never re-issues). Two `issue()` calls for
*different bookings of the same coach* that both reach `:62`'s flush before either reaches `:98`'s
lock attempt are then **permanently mutually blocked**: each holds `FOR KEY SHARE` via its own
uncommitted strike INSERT, and each retry of `FOR UPDATE NOWAIT` fails against the other's
`FOR KEY SHARE` identically, exhausting `PessimisticLockRetryer`'s ~3.2s budget and discarding the
loser's strike with a 409 — not a race that resolves in the winner's favour, a genuine deadlock-shaped
mutual exclusion that only time-limited retry saves from hanging outright.

**Fix.** Move the strike-object construction and `strikeRepository.save(strike)` call to **after**
`withBoundedRetry` succeeds (i.e., after `:98-99`, before the `alreadyOffMarketplace` computation that
already sits there). This is safe and preserves every documented invariant:

- **The count at `:115` (`strikeRepository.countByCoachIdAndCreatedAtAfter`) still includes this
  call's own just-created strike.** Hibernate auto-flushes pending writes against a table a query is
  about to read before running that query (default `FlushMode.AUTO`), and the save and the count both
  touch `coach_reliability_strikes` — the ordering constraint the original `:56-61` comment describes
  ("the row is durable before the locked read and is counted by the query below") is satisfied by
  auto-flush-before-count regardless of whether the save happens before or after the *lock* step, as
  long as it happens before the *count* step.
- **"A full retry exhaustion still rolls the whole transaction back, strike included" stays true and
  becomes unconditionally true**, not just true-in-effect: today, if `withBoundedRetry` exhausts, the
  strike was already flushed at `:62` but the whole `REQUIRES_NEW` transaction still rolls back on the
  propagated exception — net effect, no strike persisted, identical to the new ordering where `save()`
  is never reached at all in that branch. No caller-observable behaviour changes in the exhaustion
  path.
- **The strike remains unconditionally recorded whenever `issue()` returns normally** — including for
  an `alreadyOffMarketplace` coach (deferred-123 AC1's own guarantee) — since the save now happens
  strictly before that branch, not conditioned on it.
- **Once `findByIdForUpdate` has won `FOR UPDATE` on the coach row, this transaction's own subsequent
  `FOR KEY SHARE` request on the same row (from the strike INSERT's FK check) is self-compatible** —
  Postgres never blocks a transaction on a lock it already holds a stronger mode of. The mutual-lock
  scenario above is structurally impossible once the INSERT can only happen after this transaction
  already owns the coach-row lock.

**Do not** change `PessimisticLockRetryer`'s flush-before-savepoint ordering to fix this — that class
is shared by every other `withBoundedRetry` caller in the codebase (`AdminCoachEnforcementService`,
`CoachProfileService`, others) and its own Javadoc explains why flush-before-savepoint is correct
*there*; the bug here is specific to *this* caller doing an unrelated flush-inducing write before ever
attempting the lock, not to the retrier's own mechanics.

### Tasks

1. Move strike construction (`:51-55`) and `strikeRepository.save(strike)` (`:62`) to immediately
   after the `withBoundedRetry` block (after `:98-99`), before the `alreadyOffMarketplace` computation.
   Rename the local from `saved` to whatever reads cleanly at the new call site; update the `return
   saved;` at the end accordingly.
2. Rewrite the `:56-61` comment block in place at the new location — it currently justifies
   *save-before-lock*; it must now justify *save-after-lock, before-count*, citing the auto-flush
   mechanism above (do not just delete it: a future reader needs to know why the ordering here is not
   "the obvious" construct-then-return-immediately shape).
3. Add a new comment at the deleted call site's former location (or at the top of the lock block)
   explaining what used to be there and why it moved — a one-line pointer is enough (mirrors this
   codebase's own convention of leaving a breadcrumb rather than a silent diff, e.g.
   `ConfigStartupAssertion`'s and `AdminCoachEnforcementService`'s own review-response comments).
4. **New regression test** proving the fix: two concurrent `issue()` calls against the *same coach,
   different bookings*, both starting their transaction before either reaches the lock — assert both
   ultimately succeed (no `PessimisticLockingFailureException`/409 from either side), mirroring
   `ReliabilityStrikeConcurrencyIT`'s existing latch-based concurrency pattern if that class still
   exists at implementation time (re-check — it may have been renamed/absorbed since story creation).
5. **Mutation-check by hand**: temporarily revert the ordering (git stash the fix) and confirm the new
   test fails deterministically (both sides time out / one or both get a 409) before restoring the fix
   — this bug is genuinely hard to reproduce without a deliberately interleaved two-thread test, so a
   green test alone does not prove it exercises the right code path.
6. Re-run `ReliabilityStrikeServiceTest`, `ManualStrikeIT`, and any existing concurrency IT for this
   service together — zero regressions expected; the ordering change should be invisible to every
   test that does not deliberately interleave two coach-scoped calls.

---

## AC2 — Per-row exception isolation on both outbox/DLQ processor loops

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 3.

**Current shape, re-verified at HEAD (both processors, structurally identical):**

`VideoDeletionOutboxProcessor.process()` (`:126-156`) — the `for` loop at `:144-155` calls
`processRow(row, runClaimedAt)` at `:153` with **no try/catch around the call**. Inside
`processRow` (`:158-211`), only the `videoProviderAdapter.deleteAsset` + `completeRowWithNullAsset`
pair (`:205-210`) is wrapped. Everything else in the method — `drillVideoRefRepository.findByVideoId`
(`:169`), the drill-ref branch's `transactionTemplate.execute` block (`:173-187`),
`videoRepository.findById` (`:198`), and `completeRow`'s own `transactionTemplate.execute`
(`:213-221`) — runs **unguarded**. An exception from any of those propagates out of `processRow`,
out of the `for` loop, out of `process()` itself, abandoning every remaining row in the batch.

`RadarCompositeDlqProcessor.process()` (`:105-129`) — the `for` loop at `:118-128` calls `processRow`
at `:126`, also unwrapped at the loop level. `processRow` (`:131-145`) *does* wrap its own body in a
try/catch (`:132-144`) that routes failures to `handleFailure` — but `handleFailure` itself
(`:147-165`+) does its own `transactionTemplate.execute` reading `configService.getBoundedLong` — if
*that* throws, the exception escapes the outer `catch` (which already returned control to
`handleFailure`, not a second guard around it) and propagates identically to the video side.

**Why this got strictly worse under deferred-123's own AC3.** Before AC3, a claimed row that never
got processed (loop aborted) stayed re-eligible via `next_retry_at` on essentially the next tick.
After AC3 rekeyed staleness recovery onto `claimed_at`, an abandoned row is invisible until a full
`STALE_CLAIM_WINDOW` elapses (20 min video / 10 min radar) — the fix that closed one double-processing
path made this pre-existing error-isolation gap materially slower to self-heal, not just cosmetically
worse.

**Fix.** Wrap the per-row call **at the loop level** in both `process()` methods — not by patching
each internal nested try/catch individually, which would need three separate additions on the video
side and still would not protect `RadarCompositeDlqProcessor.handleFailure`'s own failure-handling
transaction. A single `try { processRow(row, runClaimedAt); } catch (Exception e) { log.error(...);
}` around the loop's existing call closes every one of the cited call sites uniformly, and is a true
safety net *underneath* the existing nested try/catches (which still route the expected/handled
failure shape to `handleFailure`'s attempt-counting and backoff logic — this new outer catch only
fires for something those inner mechanisms did not anticipate, e.g. a bug inside `handleFailure`
itself).

### Tasks

1. In `VideoDeletionOutboxProcessor.process()`, wrap the `processRow(row, runClaimedAt);` call
   (currently `:153`) in a try/catch. On catch: log at `ERROR` including `row.getId()` and
   `row.getVideoId()` for correlation, and continue the loop (do not `return` — the deadline check on
   the next iteration is still the correct place to bail out early, not this catch).
2. Apply the identical change to `RadarCompositeDlqProcessor.process()`'s `processRow(row,
   runClaimedAt);` call (currently `:126`), logging `row.getId()`, `row.getPlayerId()`.
3. Update both methods' class/method-level Javadoc that currently states or implies every row's
   outcome is handled by the inner try/catch alone — note explicitly that this new outer guard exists
   specifically so a bug in `handleFailure`/the completion path cannot itself abandon the rest of the
   batch.
4. **Do not** attempt to eagerly release the failing row's own claim back to `PENDING` from the new
   catch block — leave its recovery to the existing `resetStaleClaimed` stale-window mechanism
   (already correct, per AC3's own design) rather than introducing a second, narrower release path
   with its own edge cases. State this explicitly in a comment so a future reader does not "complete
   the pattern" by adding one.
5. **New test per processor** proving isolation: seed a batch of 3+ claimed rows where the *middle*
   row's processing throws an unexpected `RuntimeException` from a mocked/stubbed dependency (not the
   already-tested `deleteAsset`/`recalculateComposite` failure path, which already routes to
   `handleFailure` correctly) — assert the rows *before and after* the failing one still complete
   normally, and the failing row is left `CLAIMED` for `resetStaleClaimed` to recover later (not
   silently completed, not immediately released).
6. **Mutation-check by hand**: temporarily remove the new try/catch and confirm the new test fails
   (the rows after the throwing one no longer complete) before finalizing.
7. Re-run `VideoDeletionOutboxProcessorIT`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
   `RadarCompositeDlqProcessorTest`, `RadarCompositeDlqRepositoryIT` together — zero regressions.

---

## AC3 — Reconcile `main."user"` `skillars_role`/`verification_status` column width

**Source:** `deferred-work.md`, "code review of skillars-deferred-122…", the one remaining open
bullet (its sibling bullet in the same ledger section — the audit-table CHECK-constraint divergence —
was already closed by `V145`'s own code-review Patch pass; do not re-touch that half).

**The divergence, re-verified at HEAD:** `V138__baseline_schema.sql:1289-1290` declares
`main."user".skillars_role character varying(20)` and `verification_status character varying(20)
DEFAULT 'UNVERIFIED'`. `User.java:159-165`'s `@Column` annotations for both fields carry no explicit
`length`, so Hibernate's own default for a `String`-backed column (both are `@Enumerated(STRING)`
enums per `User.java` — re-confirm the exact annotation at implementation time) is `varchar(255)`.
Per deferred-123's own investigation (now corrected and documented in `V145`'s header, `:12-22`), the
now-removed `spring.jpa.generate-ddl: true` had Hibernate silently issuing `alter table ... alter
column ... set data type varchar(255)` against these two columns at every boot, in every environment
— so every database that has ever booted this application carries `varchar(255)` on `main."user"`,
while `V138`'s own migration declares `varchar(20)`. A fresh, Flyway-only database (any CI run, any
new environment) gets the `varchar(20)` the migration actually specifies — the two shapes disagree.

**Why this needs its own migration, not a drive-by fix alongside `V145`.** `V145` widened
`main.user_aud`'s copies of these same two column names because that table's rows are empty-safe
(audit history, additive). `main."user"` itself is the live, populated table — widening it is safe
(a widen never fails against existing data) but the *risk framing* deferred-123 recorded (`deferred-
work.md`, "code review of skillars-deferred-122…") explicitly asked for "its own migration and
evidence pass," not a bundled fix. This story is that pass.

**Fix.** A new migration widening `main."user".skillars_role`/`verification_status` from
`varchar(20)` to `varchar(255)`, mirroring `V145`'s own established shape for the identical situation:
match the width every already-booted environment already has (a widen, not a narrow — the only
direction that cannot fail against live data), and reconcile the enum `CHECK` constraint the same way
`V145` did.

**Constraint check needed before writing the migration**, not assumed: confirm whether
`main."user".skillars_role`/`verification_status` currently carry an unnamed inline `CHECK` from
Hibernate's own DDL (the same mechanism `V145`'s header describes for the audit table) by inspecting
`pg_constraint` against a database that has booted pre-fix, or by re-deriving it from
`HibernateJpaVendorAdapter`'s DDL generation for `@Enumerated(STRING)` the same way deferred-123's own
investigation did. If a CHECK exists, follow `V145`'s exact `DO $$ IF NOT EXISTS ... END $$` pattern
(named `user_skillars_role_check`/`user_verification_status_check`, matching Postgres's own default
unnamed-constraint naming) with `NOT VALID`, for the same reasons `V145`'s header gives
(`:45-49`) — every existing row was written through the entity's own enum setters, never bulk SQL, so
no row can violate it, and `NOT VALID` avoids a full-table scan on `main."user"`, which is a
meaningfully larger table than `main.user_aud`.

### Tasks

1. Confirm the next free Flyway version number against the actual `src/main/resources/db/migration/`
   directory at implementation time (this story's own audit found `V148` as the current tip; a
   concurrent story could have claimed `V149`/`V150` since — do not assume).
2. Write the widening migration: `ALTER TABLE main."user" ALTER COLUMN skillars_role TYPE character
   varying(255)`, same for `verification_status`, under `SET lock_timeout = '5s'` per this repo's
   migration-lint convention. Confirm via `MigrationLint`'s own rules whether a bare `ALTER COLUMN ...
   TYPE` on a widen-only change needs any special handling (it should not trigger
   `VALIDATING_CONSTRAINT`/`INLINE_FK_ADD_COLUMN`, but verify against the lint's actual rule
   implementations rather than assuming, per this project's own stated method).
3. Add the CHECK-constraint reconciliation block per the investigation above, if warranted.
4. Update `User.java`'s field-level Javadoc for both fields to note the width is now explicit/
   Flyway-tracked, closing the "one divergence remains" note `V145`'s own header and the ledger
   currently carry.
5. **New test or extension of an existing one** proving a fresh database now matches an
   already-patched one: assert the column width via `information_schema.columns` (mirroring however
   `MigrationConventionLintTest` or an existing schema-shape IT already queries `information_schema`,
   if such a precedent exists — check `RescheduleResourceIT`/similar for a schema-assertion pattern
   before inventing one) rather than only trusting the migration text compiles and applies.
6. Re-run `MigrationConventionLintTest` and any test that seeds a `skillars_role`/`verification_status`
   value near or above 20 characters (there should be none today — confirm) — zero regressions
   expected; this is a pure widen with a matching existing-environment shape.

---

## AC4 — Replace `claimed_at`-equality run-identity with a `claimed_by` UUID column

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 6. **Owner decision:
include now** (see Provenance).

**Current mechanism, re-verified at HEAD (both repositories, identical shape):**
`VideoDeletionOutboxRepository`/`RadarCompositeDlqRepository` each expose `claimPendingBatch`,
`findClaimedBatch`, `releaseClaimed`, `completeClaimed`, `failClaimed` — **every one of the latter
four uses `claimed_at = :claimedAt` as the "rows this invocation owns" identity predicate**, reusing
the same `Instant` that also drives `resetStaleClaimed`'s *staleness* check. This is correct today
because both processors generate exactly one `Instant runClaimedAt = Instant.now()` per tick and pass
it through every call in that tick, so the equality always matches genuinely-owned rows. It is brittle
because the column is doing two structurally different jobs (a timestamp for staleness math, and a
value-equality token for run identity) with no enforcement that they stay coupled — any future change
to how `runClaimedAt` is threaded through (a retry that re-derives it, a refactor that captures it
twice) would silently break every one of these five call sites at once, and the failure mode is
silent: rows claimed, batch fetched empty, rows stranded until the stale window recovers them — not an
exception, not a wrong-but-visible result.

**Fix.** Add a `claimed_by` UUID column to both `main.video_deletion_outbox` and
`development.radar_composite_dlq`. `claimPendingBatch` stamps `claimed_by` with a fresh
`UUID.randomUUID()` generated once per tick (alongside the existing `runClaimedAt` Instant, which
**must be kept** — it still drives `resetStaleClaimed`'s time-based staleness check, a genuinely
different concern from run identity). `findClaimedBatch`, `releaseClaimed`, `completeClaimed`, and
`failClaimed` switch their identity predicate from `claimed_at = :claimedAt` to `claimed_by = :runId`.
`resetStaleClaimed` is **unchanged** — it correctly stays keyed on `claimed_at < :deadline`, a time
comparison, not an identity one.

### Tasks

1. Confirm the next free Flyway version at implementation time (see AC3 Task 1 — this migration and
   AC3's may claim adjacent numbers in either order; whichever lands first takes the lower number, no
   ordering dependency between them).
2. New migration: `ALTER TABLE main.video_deletion_outbox ADD COLUMN IF NOT EXISTS claimed_by uuid`,
   same for `development.radar_composite_dlq`, under `SET lock_timeout = '5s'`. No backfill needed —
   any row currently `CLAIMED` with a `claimed_by` of `NULL` is handled correctly by the identity
   predicate simply never matching `NULL = :runId` (Postgres's three-valued logic), which is the
   *safe* direction (an old-shape claimed row is treated as unowned by any new-shape run, not
   incorrectly claimed) — confirm this reasoning holds and document it in the migration header,
   mirroring `V144`'s own backfill-necessity reasoning for why *that* column needed a backfill and
   this one does not.
3. Add `claimedBy` (`UUID`) fields to `VideoDeletionOutbox`/`RadarCompositeDlqEntry` entities,
   mirroring `claimedAt`'s existing Javadoc-and-`@Column` shape.
4. Update `claimPendingBatch` in both repositories to also `SET claimed_by = :runId`, adding a
   `@Param("runId") UUID runId` parameter.
5. Update `findClaimedBatch`, `releaseClaimed`, `completeClaimed`, `failClaimed` in both repositories:
   replace the `claimed_at = :claimedAt` predicate with `claimed_by = :runId`; rename the `claimedAt`
   parameter to `runId` (type `UUID`) on all four. **Leave `resetStaleClaimed` untouched** (still
   `claimed_at < :deadline` / `claimed_at IS NULL`).
6. Update both processors (`VideoDeletionOutboxProcessor.process`/`RadarCompositeDlqProcessor.process`)
   to generate `UUID runId = UUID.randomUUID();` alongside `Instant runClaimedAt = Instant.now();` at
   the top of each tick, and thread `runId` through to `claimPendingBatch`, `findClaimedBatch`,
   `releaseClaimed`, and (via `processRow`) `completeClaimed`/`failClaimed` — `runClaimedAt` keeps
   flowing only to `resetStaleClaimed` and to `claimPendingBatch`'s own `claimed_at` stamp.
7. Update the four test files that currently pass an `Instant` for identity to these methods
   (`VideoDeletionOutboxProcessorIT`, `VideoDeletionOutboxProcessorSchedulerLockTest`,
   `RadarCompositeDlqProcessorTest`, `RadarCompositeDlqRepositoryIT` — re-confirm this list against
   `grep -rl "findClaimedBatch\|completeClaimed\|failClaimed\|releaseClaimed" src/test` at
   implementation time, it may have grown) to pass a `UUID` instead, generating a fresh one per
   simulated "run" the same way the production code now does.
8. Re-run all four updated test files plus `MigrationConventionLintTest` together — zero regressions
   expected; the identity mechanism changes but no observable behaviour should, since a single tick
   still generates exactly one `runId` used consistently across its own calls.

---

## AC5 — Close `issueManualStrike`'s audit-trail durability gap via `REQUIRES_NEW`

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 5. **Owner decision:
fix via the established self-proxy `REQUIRES_NEW` pattern** (see Provenance).

**Current code** (`AdminCoachEnforcementService.java`, re-verified at HEAD, `:276-311`):

```java
// :276  @Transactional
// :277  public UUID issueManualStrike(UUID coachId, UUID bookingId, String reason, Long adminId) {
//        ... validation, existence checks ...
// :300  CoachReliabilityStrike strike = reliabilityStrikeService.issue(coachId, bookingId, reason);
// :302-306  AdminActionLog actionLog = new AdminActionLog(); ... setReason(...);
// :307  adminActionLogRepository.save(actionLog);
// :309  log.info(...);
// :310  return strike.getId();
```

**The gap.** `reliabilityStrikeService.issue` (line 49 of that class) is
`@Transactional(propagation = REQUIRES_NEW)` — it commits durably, on its own connection, the moment
it returns, *independent* of whatever `issueManualStrike`'s own (default-propagation) transaction does
afterward. The `AdminActionLog` write at `:302-307` happens in `issueManualStrike`'s own transaction,
which is still open at that point. If anything causes that outer transaction to roll back after
`:300` returns — a deferred constraint check firing at commit, a connection drop, a pod kill, or
(more mundanely) an exception thrown by the audit-log save itself for an unrelated reason — the strike
(and any status change/events it triggered) is already durably committed via `issue()`'s own
`REQUIRES_NEW`, but the record of *which admin issued it* is rolled back with the outer transaction.
Net result: a real, visible enforcement action with zero audit trail.

**Fix.** Mirror `UserAdminService`'s own established self-proxy pattern
(`UserAdminService.java:91-92`, `@Autowired @Lazy private UserAdminService self;`, used at `:237` as
`self.deleteUserInTransaction(...)`) exactly: add the identical field to
`AdminCoachEnforcementService`, and give the audit-log write its own `REQUIRES_NEW` method called
through that self-reference immediately after `issue()` returns. This closes the gap to the narrowest
possible window — the only remaining risk is the audit-log insert's own commit failing for a reason
unrelated to the outer transaction, not "anything at all happening later in the outer transaction."

**Why field injection, not constructor injection**, per `UserAdminService`'s own documented reasoning
(mirror this exactly in the new comment): `AdminCoachEnforcementService` uses
`@RequiredArgsConstructor`, and a constructor-injected self-reference would create an immediate
circular dependency at bean-construction time — `@Autowired @Lazy` on a separate field defers
resolution until first use, breaking the cycle.

### Tasks

1. Add `@Autowired @Lazy private AdminCoachEnforcementService self;` to `AdminCoachEnforcementService`,
   with a comment citing `UserAdminService.self`'s identical precedent and reasoning (do not
   re-derive the justification from scratch — point back to the established explanation).
2. Add a new `public` method (e.g. `recordManualStrikeAudit(UUID coachId, Long adminId, String
   reason)`) annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)` that constructs and
   saves the `AdminActionLog` row currently built inline at `:302-307`. **Note:** this class currently
   imports `org.springframework.transaction.annotation.Isolation` and `.Transactional` but not
   `.Propagation` (re-verify at implementation time) — add that import; `ReliabilityStrikeService.java`
   already has the identical import if a copy-paste reference is useful. Must be `public` — Spring's
   default proxy-mode AOP silently ignores `@Transactional` on a non-public method regardless of how
   it is invoked, the exact trap `UserAdminService.deleteUserInTransaction` was already fixed for
   (deferred-122 AC8) and must not be repeated here.
3. Replace the inline `AdminActionLog` construction/save at `:302-307` in `issueManualStrike` with a
   call to `self.recordManualStrikeAudit(coachId, adminId, "Manual strike: " + reason)`, placed
   immediately after `:300`'s `issue()` call.
4. **Do not** apply this same treatment to `deleteStrike`'s or `reinstateCoach`'s own inline
   `AdminActionLog` writes (`:355`/`:263`/`:152` per the earlier grep) — this AC is scoped to the one
   finding the review raised against `issueManualStrike` specifically; those methods were not flagged
   and widening scope here is not this story's call to make silently. If a future audit wants the same
   treatment elsewhere, that is its own finding to raise.
5. **New test** proving durability: simulate the outer transaction failing *after* `issue()` returns
   but *after* the new `self.recordManualStrikeAudit` call has also returned (e.g. by throwing from a
   point after both calls, inside the same test method, and asserting via a fresh read *outside* the
   test's own transaction — mirroring `ManualStrikeIT`'s established pattern of asserting via raw
   `jdbcTemplate` queries against committed state) — assert the strike AND the audit log both survive
   the outer rollback. A second test should confirm the *existing* happy path (`AdminActionLog` row
   present with the correct `adminId`/`reason` on a normal successful call) still passes unchanged.
6. **Mutation-check by hand**: temporarily revert to the inline non-`REQUIRES_NEW` write and confirm
   the new durability test fails (the audit row is lost on the simulated outer rollback) before
   finalizing.
7. Re-run `ManualStrikeIT` in full — zero regressions expected; this changes only the audit write's
   transaction boundary, not `issueManualStrike`'s observable contract for any existing test.

---

## AC6 — Correct `deleteStrike`'s Java/SQL precision-mismatch comment overclaim

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 7. Documentation-only,
no functional change — mirrors this project's own established pattern for a negligible-probability
finding (e.g. deferred-123's own AC7 accepted-risk closure).

**Current text** (`AdminCoachEnforcementService.java:342-343`, re-verified at HEAD): "Matches
`countByCoachIdAndCreatedAtAfter`'s own strict `>` (a derived `...After` query), so a boundary strike
is judged identically wherever this local is used below." This overclaims: `:364`'s
`strikeCreatedAt.isAfter(cutoff)` is evaluated at nanosecond precision inside the JVM, while `:365`'s
`countByCoachIdAndCreatedAtAfter(coachId, cutoff)` binds the same `cutoff` object through pgjdbc,
which rounds to microsecond precision before the database ever sees it. A strike whose `createdAt`
falls in the sub-microsecond gap between those two roundings is judged in-window by the Java guard and
out-of-window by the SQL count (or vice versa), so the REDUCED/no-change tiering decision can land one
tier away from what a single, consistently-rounded comparison would produce. Probability is
~1e-9 per call (the width of representable sub-microsecond nanosecond values relative to a 30-day
window) — recorded only because the comment's own wording asserts exact identical treatment, which
does not hold across the Java/SQL boundary.

### Tasks

1. Rewrite `:342-343`'s comment to state the actual guarantee: the two comparisons use the same
   logical cutoff and the same `>`-style strictness, but are evaluated at different underlying
   precisions (JVM nanoseconds vs. pgjdbc-rounded microseconds), so a boundary strike in the
   sub-microsecond gap is negligibly (~1e-9 per call) likely to be judged differently by the two
   checks — accepted, not fixed, given the probability and the absence of any `Clock`-seam-based test
   infrastructure to even deterministically exercise it (mirrors this story's AC2 Task 3 in
   deferred-123: "no `Clock` seam exists today and adding one solely for this is a larger refactor
   than this fix warrants").
2. No test change required — this is a comment-accuracy fix for an already-negligible, undecided
   finding, not a new behaviour to cover.

---

## AC7 — Document the migration-vs-active-poller collision risk

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 2. **Owner decision:
document only, no migration-retry infrastructure** (see Provenance).

**The risk, generalized beyond `V144`:** any `ALTER TABLE`-shaped migration against a table an active
`@Scheduled` poller reads/writes (today: `main.video_deletion_outbox`, polled every 60s by default;
`development.radar_composite_dlq`, same cadence) takes `ACCESS EXCLUSIVE` and can collide with an
in-flight polling transaction. `docs/deployment/migration-conventions.md`'s existing item 7 (re-verify
its exact current numbering/line range at implementation time) already explains *why* a bounded
`lock_timeout` turns an unbounded hang into a failed-and-recoverable migration — this AC adds the
missing half: that a migration on an actively-polled table should *expect* to occasionally lose that
bounded race, that this is by design (the documented philosophy already prefers "a failed migration"
over "an unbounded hang"), and what the concrete recovery step is (locate and delete the failed
`flyway_schema_history` row, then retry — optionally scheduling the retry for a lower-traffic window
if repeated collisions are disruptive). This closes the gap without inventing new infrastructure: per
skillars-deferred-117's owner decision (re-confirmed still true — no production deploy has ever
happened), the risk is currently theoretical, and a migration-retry wrapper would be a materially
larger, speculative investment against a risk that has not yet had a single real occurrence.

### Tasks

1. Locate `migration-conventions.md`'s item 7 (the `SET lock_timeout` discussion) at implementation
   time — re-verify it is still the right home for this addition; it was chosen because the new note
   is a direct corollary of that item's own "a bounded wait turns an outage into a failed migration,
   which is the far better outcome" framing.
2. Add a sub-point (not a new top-level numbered item, to avoid renumbering the whole list) naming: (a)
   which tables in this codebase are actively polled today and at what cadence (grep `@Scheduled`
   classes touching `video_deletion_outbox`/`radar_composite_dlq` at implementation time to confirm
   the cadence figures above are still accurate — they may have changed since story creation); (b) the
   expected failure mode (migration times out against `ACCESS EXCLUSIVE`, aborts, leaves a failed
   `flyway_schema_history` row); (c) the recovery step (delete the failed history row, retry); (d) that
   this is accepted as-is, not a bug to fix, per the reasoning above — and that it should be revisited
   if/when this project's own "no production deploy has ever happened" premise changes.
3. No code change, no test change — pure documentation.

---

## AC8 — Envers null-`verificationStatus` reconstruction: document as accepted risk, plus ledger closeout

**Source:** `deferred-work.md`, "code review of skillars-deferred-123…", bullet 4 (folded into this
AC rather than given its own, since it is a single documentation comment with no task list of its
own) — plus the standard ledger-hygiene closeout every story in this series performs.

**The finding (re-verified, latent only):** `V145` added `main.user_aud.verification_status` with no
backfill, and that audit column is nullable while `main."user".verification_status` is `NOT NULL
DEFAULT 'UNVERIFIED'` — so a pre-`V145` audit revision reconstructed via Envers would return `null`
for a field `User.java` initializes to a non-null default (`:355-357` per the ledger's citation —
re-verify against HEAD, `User.java`'s line numbers have shifted before). Confirmed still latent: `grep
-rn "AuditReader\|AuditQuery" src/main` returns nothing — no code path in this application actually
performs Envers historical reconstruction today, so this cannot currently be observed in production,
only reasoned about.

**Disposition: `[DECIDED: accepted risk — skillars-deferred-124]`, documented, not fixed.** A backfill
is not meaningfully possible (there is no historical `verificationStatus` value to backfill for
revisions that predate the field's own existence), and building `AuditReader` usage solely to test a
code path nothing in `src/main` exercises would be manufacturing coverage for a feature that does not
exist yet, not closing a real gap. Revisit if/when this codebase ever adds a real Envers-reconstruction
call site — at that point the null-vs-non-null-default mismatch becomes a genuine bug to fix, not a
latent one to document.

### Tasks

1. Add a code comment at `User.java`'s `verificationStatus` field (near its existing deferred-123 AC5
   Javadoc, re-verify the exact current line) documenting the accepted-risk disposition above, with a
   corrected citation for the field-initializer line range if it has moved.
2. **Ledger closeout** — apply this disposition table to `deferred-work.md` exactly (re-verify every
   citation against HEAD immediately before editing, not against this story's draft):

   | Ledger bullet | Disposition |
   |---|---|
   | "code review of skillars-deferred-123…": `ReliabilityStrikeService` mutual lock-out | Delete (AC1) |
   | "code review of skillars-deferred-123…": V144-shaped migrations race active pollers | Delete (AC7 — now documented in `migration-conventions.md`, not left as an untracked ledger risk) |
   | "code review of skillars-deferred-123…": neither processor loop has a per-row try/catch | Delete (AC2) |
   | "code review of skillars-deferred-123…": Envers null `verificationStatus` reconstruction | Annotate `[DECIDED: accepted risk — skillars-deferred-124]` with the `User.java` citation from AC8 Task 1 — do not delete |
   | "code review of skillars-deferred-123…": `issueManualStrike` commits strike before audit log | Delete (AC5) |
   | "code review of skillars-deferred-123…": `findClaimedBatch` exact-timestamp-equality brittleness | Delete (AC4) |
   | "code review of skillars-deferred-123…": `deleteStrike` Java/SQL precision-mismatch comment | Delete (AC6) |
   | "code review of skillars-deferred-122…": `main."user"` `skillars_role`/`verification_status` width divergence | Delete (AC3) |

   Net: 7 deletions, 1 `[DECIDED]` annotation. **Do not** touch the two `[DECIDED]` bullets this
   story's own Provenance section explicitly confirmed out of scope
   (`ModerationSlaMonitorService`/`reinstateCoach`'s stale-vs-fresh-suspension note) — verify after
   editing that both are still present, unchanged, exactly as `skillars-deferred-118` through `-123`'s
   own post-edit reconstruction-check convention requires.
3. Add a `## Last audit: <implementation date> (skillars-deferred-124 story creation)` narrative block
   matching this file's existing convention, summarizing what was checked/closed and cross-referencing
   this story rather than duplicating its content.
4. Grep-sweep the whole ledger for any other stale hit against every file this story touches
   (`ReliabilityStrikeService.java`, `VideoDeletionOutboxProcessor.java`,
   `RadarCompositeDlqProcessor.java`, `VideoDeletionOutboxRepository.java`,
   `RadarCompositeDlqRepository.java`, `AdminCoachEnforcementService.java`, `User.java`) before
   finishing — confirm no other historical bullet describes something this story's changes affect,
   per this series' own "reconfirm the ledger is exhausted for this scope" convention.

---

## Tasks / Subtasks (top-level)

- [ ] AC1 — fix `ReliabilityStrikeService.issue`'s INSERT-before-lock mutual lock-out; new concurrency
  test; mutation-checked
- [ ] AC2 — per-row exception isolation on both outbox/DLQ processor loops; new tests per processor;
  mutation-checked
- [ ] AC3 — widen `main."user"` `skillars_role`/`verification_status` to `varchar(255)` (new
  migration, mirrors `V145`); schema-shape test
- [ ] AC4 — `claimed_by` UUID column on both outbox/DLQ tables, replacing `claimed_at`-equality
  identity (new migration, entity/repository/processor/test updates across 4+ test files)
- [ ] AC5 — `AdminActionLog` `REQUIRES_NEW` self-proxy write for `issueManualStrike`; new durability
  test; mutation-checked
- [ ] AC6 — correct `deleteStrike`'s Java/SQL precision-mismatch comment (doc-only)
- [ ] AC7 — document migration-vs-active-poller collision risk in `migration-conventions.md` (doc-only)
- [ ] AC8 — Envers null-`verificationStatus` accepted-risk comment; ledger closeout per the disposition
  table

---

## Dev Notes

**Cross-AC dependencies:** AC1 and AC5 both touch code paths inside/adjacent to
`ReliabilityStrikeService.issue`/`AdminCoachEnforcementService.issueManualStrike` but are independent
fixes to independent bugs (AC1 reorders a write inside `issue()`; AC5 adds a new `REQUIRES_NEW` call
site inside `issueManualStrike`, a different method in a different class) — sequence as separate
commits so either can be reverted without the other, mirroring this series' established convention
(e.g. deferred-123's own AC1/AC2 sequencing note). AC3 and AC4 both add new Flyway migrations and may
claim adjacent version numbers — no ordering dependency between them; whichever is implemented first
takes the lower number. AC2's fix is independent of AC4's — do not conflate "add a per-row try/catch"
with "replace the claim-identity mechanism"; a reviewer should be able to revert either without
touching the other, even though both touch the same two processor classes. AC6, AC7, AC8 are
documentation-only and have no dependency on anything else in this story or on each other.

**Migration numbering:** confirm the next free Flyway version at implementation time against the
actual `src/main/resources/db/migration/` directory, not this story's assumption (`V148` was the
confirmed tip at story-creation time, per skillars-deferred-123's own final state) — a concurrent
story could have claimed `V149` since this story was drafted.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project (re-confirmed by this story's own immediately-preceding PR #211, which needed one CI-only
fix-up round after the dev-side suite had reported green). Run each AC's own targeted test class(es)
locally during implementation as needed; `mvn compile`/`mvn test-compile` are fine to run locally,
`mvn verify` is not.

**Migration-lint:** every new/changed migration must include `SET lock_timeout = '...'` per
`docs/deployment/migration-conventions.md`'s rule and `MigrationConventionLintTest`'s enforcement.

**CI context worth knowing before starting** (from this story's immediately-preceding PR #211):
`assert-context-count.sh`'s Spring-context ceiling is currently 43 (`pr-build.yml`'s call site). If any
new test class in this story introduces its own `@MockitoSpyBean`/`@MockitoBean`/`@TestPropertySource`
set not already used by an existing test class, it will very likely fork a new Spring context and
trip this gate — check the CI failure message's own guidance (`python3 scratchpad/ctxkeys.py`) if it
fires, and bump the ceiling with a dated justification comment mirroring the existing history in that
script, exactly as PR #211's own fix-up commit did. This is a real, not hypothetical, risk for AC1's
and AC2's new concurrency/isolation-style tests specifically, since those are the shapes most likely to
need a distinct bean-override set.

**Precision-truncation gotcha worth knowing before starting** (also from PR #211's own CI fix-up): any
new test that captures a raw `Instant.now()`/`OffsetDateTime.now()` and later asserts exact equality
against the same value round-tripped through a raw JDBC query must truncate to `ChronoUnit.MICROS`
first — Postgres `timestamptz` has microsecond precision, a bare `Instant.now()` may carry non-zero
nanosecond-level digits below that, and the mismatch is not a flake, it is a near-certainty over
enough runs. Mirrors `RescheduleResourceIT`/`RescheduleServiceConcurrencyIT`/`SoftDeleteIT`'s existing
precedent — and this story's own AC1/AC2 new concurrency tests are exactly the shape likely to need
this if they capture and later re-compare a timestamp.

---

## File List

**Production code (expected; confirm exact set during implementation):**
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
  (AC5, AC6)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (AC3 Javadoc, AC8 comment)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutbox.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
  (AC2, AC4)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqEntry.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java`
  (AC4)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC2, AC4)
- new migration for AC3 (widen `main."user"` columns) — number TBD at implementation time
- new migration for AC4 (`claimed_by` columns) — number TBD at implementation time

**Tests (expected):**
- new/extended concurrency test for AC1 (exact class TBD — extend an existing `*ConcurrencyIT` if one
  still fits, per Task 4's own re-check instruction)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC2 new test, AC4 signature updates)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorSchedulerLockTest.java`
  (AC4 signature updates)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
  (AC2 new test, AC4 signature updates)
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java`
  (AC4 signature updates)
- new schema-shape test for AC3 (exact class TBD)
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` (AC5 new tests)
- `src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeServiceTest.java`
  (AC1, if the new concurrency proof lands here rather than in an IT)

**Documentation / tracking:**
- `docs/deployment/migration-conventions.md` (AC7)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC8)
- `_bmad-output/implementation-artifacts/skillars-deferred-124-strike-lock-contention-outbox-resilience-and-schema-fixes.md`
  (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

*This File List is a starting expectation from story-creation-time analysis, not a closed set — the
dev agent must reconcile it against the actual final diff before marking the story done, per this
series' own established practice (deferred-123's own File List needed exactly this kind of
reconciliation after its code-review response added several files the original list had missed).*

## Change Log

- 2026-09-19: Story created via `/bmad-create-story`. Mined the two most recent, still-open
  code-review deferrals in `deferred-work.md` per the established convention; three owner decisions
  taken live with the user (AskUserQuestion) before drafting (AC5 REQUIRES_NEW fix over
  documented-risk; AC7 document-only over migration-retry infrastructure; AC4 fix-now over
  defer-as-accepted). All line numbers verified against `master@3bba9f8d` (skillars-deferred-123's
  merge, PR #211) on 2026-09-19. 8 ACs bundled per this project's "do not create small stories"
  convention.

## Dev Agent Record

_To be completed during `/bmad-dev-story`._
