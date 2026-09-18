# Story: Coach Enforcement Hardening Round 2 & User-Cleanup Fixes

**Story Key:** `skillars-deferred-122-coach-enforcement-round-2-and-user-cleanup-fixes`
**Epic:** Deferred Work
**Priority:** High (six live, reachable-today bugs — three wrong-status-write bugs in `AdminCoachEnforcementService` and three real gaps in `UserAdminService`'s daily cleanup sweep — plus one owner-decided config-validation gap and one owner-decided documentation-only closure)
**Status:** ready-for-dev
**Created:** 2026-09-18

---

## Provenance & Scoping (read before starting)

This story mines the two most recent, still-open code-review deferrals in `deferred-work.md` —
**"Deferred from: code review of skillars-deferred-120" (2026-09-17)** and **"Deferred from: code
review of skillars-deferred-121-coach-enforcement-status-toctou-lock-gap" (2026-09-18)** — per the
established convention that a fresh code-review deferral is read before drawing a new story's scope,
rather than re-mining the older, already-exhausted sections of the ledger (`skillars-deferred-115`
through `-121` each independently confirmed the pre-2026-09-17 ledger sections closed or superseded;
see their own Provenance sections).

**All line numbers below were re-verified against `master@aa920c49`** (the just-merged
`skillars-deferred-121` PR #209) **at story-creation time, not copied from the ledger** — several of
the ledger's own citations against `AdminCoachEnforcementService.java` were already stale, because
`skillars-deferred-121`'s own code-review response added ~35 lines of comment to `reinstateCoach`
between when the review ran (citing pre-response line numbers) and when it merged, shifting every line
below it. The corrected line numbers are used throughout this story; do not trust the ledger's own
`:NNN` citations for this file without re-checking against HEAD yourself.

**Ledger `[CLOSED by ...]` / `[DECIDED]` sweep:** grepped every bullet under both source sections for
existing closure/decision annotations before selecting items — `ModerationSlaMonitorService`'s
no-`@SchedulerLock` bullet is `[DECIDED]` (accepted tradeoff, no code change) and is correctly **not**
picked up here; `resetStaleClaimed`/`claimPendingBatch`'s eligibility-vs-claim-time bullet and
`UserAdminService`'s original TOCTOU-narrowing bullet were confirmed still open (no closure note) and
their own text explicitly says the `claimed_at`-column fix is "deliberately out of scope for a
lock-parity story" — **owner decision below scopes this out of this story too** (see "Owner decisions"
below).

### Group A — `AdminCoachEnforcementService` round 2 (same file `skillars-deferred-121` just touched)

Three of the nine pre-existing findings `skillars-deferred-121`'s own code review surfaced in this
file are genuine, narrowly-scoped bugs with no schema change and no owner ambiguity about the fix
shape (AC1–AC3 below). Two more needed an explicit product/config decision, both resolved with the
user directly before this story was drafted (AC4, AC5). The remaining four ledger items
(`issueManualStrike`'s missing coach-status guard, the strike-DELETE's unbounded-wait-outside-retry
gap in `PessimisticLockRetryer`, and the 30-day-window-slides-with-contention timing issue shared with
`ReliabilityStrikeService.issue`) are **left deferred** — see "Explicitly out of scope" below.

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `deleteStrike` has no path back to `REDUCED`, only ACTIVE-or-nothing | `AdminCoachEnforcementService.java:261-263` | AC1 |
| Deleting an already-out-of-window strike still fires a full revert | `AdminCoachEnforcementService.java:238-268` | AC1 |
| Concurrent duplicate `deleteStrike` surfaces `StaleStateException`, not 404 | `AdminCoachEnforcementService.java:238-245` | AC2 |
| `getEnforcementProfile` composes an inconsistent status+strike-count view | `AdminCoachEnforcementService.java:74-98` | AC3 |
| `visibilityThreshold > suspensionThreshold` accepted config, no ordering guard | `AdminCoachEnforcementService.java:254-263`, `ReliabilityStrikeService.java:64-67` | AC4 |
| `reinstateCoach` cannot distinguish a stale suspension from a fresh one | `AdminCoachEnforcementService.java:150-197` | AC5 |

### Group B — `UserAdminService` hardening (unrelated module, bundled per this project's established "do not create small stories" convention)

Three genuine, still-open findings from `skillars-deferred-120`'s code review, none touched by any
story since:

| Finding | File:Lines (verified against HEAD) | This story |
|---|---|---|
| `findExpiredUsers` builds a `Pageable` it never uses and loads the whole expired set every call | `UserAdminService.java:198-206` | AC6 |
| `MAX_BATCHES_PER_RUN` hardcoded while the batch size it multiplies is configurable | `UserAdminService.java:49` | AC7 |
| `deleteUserInTransaction`'s `REQUIRES_NEW` is inert (self-invocation bypasses the proxy) | `UserAdminService.java:238-248` | AC8 |
| No cross-run record of users that fail deletion every run | `UserAdminService.java:151-166` (catch block) | AC9 |

### Owner decisions (taken live before this story was drafted)

1. **`reinstateCoach` vs. a fresh concurrent suspension (AC5): keep current behavior, document only.**
   "Explicit admin intent always wins" stays the rule — an admin's reinstate call proceeds to `ACTIVE`
   even if the locked re-read observes a `SUSPENDED` state a *different* admin's `suspendCoach` call
   committed moments ago. No code change; the ledger's item 9 is annotated `[DECIDED]` and the existing
   inline comment (already partially covers this) is extended to state the decision explicitly rather
   than leaving it phrased as an open question.
2. **`visibilityThreshold > suspensionThreshold` (AC4): add cross-field validation.** Reject/flag at
   config-read time via `ConfigStartupAssertion` (see AC4) rather than changing `deleteStrike`'s own
   revert condition or leaving it undocumented. `ConfigBounds.java`'s own class Javadoc already
   anticipated this: *"The pre-existing `ConfigService.getBoundedLong(...)` call sites that already
   predated this story (in ..., `ReliabilityStrikeService`, ..., `AdminCoachEnforcementService`) are
   deliberately not here ... **Add them if/when a follow-up wants boot-time coverage for them too.**"*
   This story is that follow-up, scoped narrowly to the ordering relationship, not full individual
   registration of both keys into `ConfigBounds.ALL` (that remains a separate, un-asked-for expansion).
3. **`resetStaleClaimed`/`claimPendingBatch` claim-time fix and the `UserAdminService` cross-run
   undeletable-user record: only the latter is in scope.** The `claimed_at`-column fix for
   `RadarCompositeDlqProcessor`/`VideoDeletionOutboxProcessor` stays deferred (its own ledger text
   already called it out-of-scope for a lock-parity story, and nothing about this story's theme changes
   that). The `UserAdminService` `cleanup_failed_at` marker (AC9) **is** in scope — smaller, single-file,
   single-migration, and thematically part of Group B's cleanup-sweep hardening already underway here.

### Explicitly out of scope (do not re-report these without new evidence)

- `issueManualStrike`'s unlocked `findById` / no coach-status guard (`:214-215`) — real gap, but its own
  fix is entangled with the `REQUIRES_NEW` self-deadlock risk `skillars-deferred-121`'s review already
  documented at that exact line; needs its own design, not a drive-by fix here.
- The strike-DELETE's unbounded wait outside `PessimisticLockRetryer`'s retry/savepoint guarantee
  (`PessimisticLockRetryer.java:132-134`) — infrastructure-wide, affects every `withBoundedRetry` call
  site that also performs a preceding non-retried write, not specific to this class.
- The 30-day count-window origin sliding with lock-wait contention (`AdminCoachEnforcementService.java:253`,
  `ReliabilityStrikeService.java:91`) — cosmetic timing skew shared by two classes, no reported incident,
  lowest priority of the nine findings.
- `resetStaleClaimed`/`claimPendingBatch` eligibility-vs-claim-time gap — owner decision above.
- `lockAtLeastFor` hardcoded against tunable `fixedDelayString` cadences (from the `skillars-deferred-120`
  review, cross-cutting sweep across `VideoDeletionOutboxProcessor`/`RadarCompositeDlqProcessor`/others)
  — a consistency sweep, not a live bug; not bundled here to keep Group A/B thematically coherent.

---

## Acceptance Criteria

### AC1 — `deleteStrike`'s revert decision gains a `REDUCED` path and ignores out-of-window deletions

**The bug, traced end to end (current code, `AdminCoachEnforcementService.java:237-282`):**

```java
long count = strikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30)); // :253
...
boolean reverted = count < visibilityThreshold                                                              // :261
    && (coach.getStatus() == CoachProfileStatus.PENDING_REVIEW
        || coach.getStatus() == CoachProfileStatus.REDUCED);

if (reverted) {
    coach.setStatus(CoachProfileStatus.ACTIVE);   // :266 — the ONLY outcome; no REDUCED branch exists
    ...
}
```

Compare `ReliabilityStrikeService.issue`'s three-tier escalation (`:93-110`, the automatic path that
walks the *same* two thresholds upward): `count >= suspensionThreshold` → `PENDING_REVIEW`;
`count >= visibilityThreshold` → `REDUCED`; else unchanged. `deleteStrike` is the corresponding
de-escalation path and should walk the identical tiers downward, but it only implements the top rung
(`ACTIVE`) — a coach at `PENDING_REVIEW` whose fresh count after a delete lands in
`[visibilityThreshold, suspensionThreshold)` (still elevated, no longer suspension-worthy) gets **no
status change at all** and stays stuck at `PENDING_REVIEW`, reporting a stricter status than their
current strike count justifies.

**This is not a hypothetical — an existing test already encodes the bug as if it were correct
behavior.** `ManualStrikeIT.deleteStrike_noStatusChange_doesNotResolveAlert` (`ManualStrikeIT.java:168-204`)
seeds 5 strikes (`PENDING_REVIEW`, default `suspensionThreshold=5`), deletes 1 (fresh count → 4, still
`>= visibilityThreshold(3)`), and asserts the coach **stays `PENDING_REVIEW`**. Under the corrected
tiering, count 4 is `>= visibilityThreshold(3)` and `< suspensionThreshold(5)` — the coach should
transition to `REDUCED`. **Task 1 below includes updating this test's expectation, not just adding new
tests** — do not treat its current green status as proof the old behavior is correct.

**Second bug, same method:** the revert condition never checks whether the *deleted* strike itself was
within the 30-day count window. `count` is always recomputed fresh over `now()-30d` regardless of the
deleted strike's own age (`:253` runs unconditionally). A coach with 2 in-window strikes (already below
`visibilityThreshold=3`, `PENDING_REVIEW` for some other historical reason) who has a 60-day-old,
already-not-counted strike deleted gets a spurious revert-to-`ACTIVE`/`REDUCED` (whichever the corrected
tiering picks), plus alert resolution and a `COACH_REINSTATE` action-log entry — even though deleting
that strike changed nothing about the qualifying count (it was never counted before deletion either).

**Fix:**

1. Capture the strike's own `createdAt` before deleting it (the entity is already loaded at `:238` for
   the ownership check — no extra query needed).
2. Only evaluate a status transition at all if the deleted strike's `createdAt` is within the same
   `now().minusDays(30)` window already used for `count` — otherwise skip straight to the existing
   "no status change" `else` branch, regardless of what `count` happens to be.
3. When the deleted strike *was* in-window, replace the binary `reverted` decision with three-tier
   logic mirroring `ReliabilityStrikeService.issue`'s tiers in reverse, gated to `PENDING_REVIEW`/
   `REDUCED` source statuses only (unchanged scope — `deleteStrike` still never touches `SUSPENDED`,
   that stays `reinstateCoach`'s job):
   - `count < visibilityThreshold` → `ACTIVE` (existing behavior, unchanged)
   - `count >= visibilityThreshold` (and, transitively, `< suspensionThreshold` once AC4's config
     validation is in place) and current status is `PENDING_REVIEW` → **new:** `REDUCED`
   - current status already `REDUCED` and count still `>= visibilityThreshold` → no change (already
     correct today — the existing `else` branch)
4. **`resolveOpenStrikeAlert` continues to fire only on the full `ACTIVE` clear, not on the new
   `PENDING_REVIEW`→`REDUCED` transition** — a coach still `REDUCED` remains at an elevated strike
   count worth the admin's attention; only a full clear to `ACTIVE` should resolve the
   `STRIKE_THRESHOLD` alert. This preserves the existing alert-resolution semantics exactly; only the
   coach-status side gains the new middle tier.
5. Give the `REDUCED` transition its own `AdminActionType`/reason text distinct from the existing
   `COACH_REINSTATE` (which should stay reserved for the full-`ACTIVE` case) — e.g. reuse
   `COACH_STRIKE_DELETED` with a reason noting the visibility-reduction, or add a narrowly-scoped new
   enum constant if `AdminActionType` conventions call for one. Check `AdminActionType.java`'s existing
   constants before deciding which.

#### Tasks

- [ ] Update `ManualStrikeIT.deleteStrike_noStatusChange_doesNotResolveAlert` (rename to reflect the
      corrected expectation, e.g. `deleteStrike_countDropsIntoReducedBand_revertsToReducedAlertStaysOpen`):
      assert coach status becomes `REDUCED` (not unchanged `PENDING_REVIEW`) and the alert **stays
      `OPEN`** (unaffected — do not resolve on a `REDUCED` transition)
- [ ] Add a new test seeding a coach at `PENDING_REVIEW` with an in-window count that, after deletion,
      lands below `visibilityThreshold` — asserts full `ACTIVE` revert + alert resolved (confirms the
      existing `ACTIVE` path still works unchanged after the refactor)
- [ ] Add a new test proving the out-of-window guard: seed 2 in-window strikes (count already `< 3`,
      coach manually set to `PENDING_REVIEW` to isolate this case) plus one strike created
      `now().minusDays(40)`; delete the 40-day-old one; assert **no** status change, **no** alert
      resolution, **no** new `admin_action_log` row — the deletion must be a pure no-op on coach state
- [ ] Implement the fix in `AdminCoachEnforcementService.deleteStrike` per the four steps above
- [ ] Re-run `ManualStrikeIT` and `AdminCoachEnforcementConcurrencyIT` together — the concurrency IT's
      `deleteStrike` tests assert on the `ACTIVE` path specifically; confirm the refactor does not
      change their outcome (they seed counts that land below `visibilityThreshold`, not in the new
      `REDUCED` band — verify this assumption against the actual seeded counts before assuming no
      change needed)

---

### AC2 — Concurrent duplicate `deleteStrike` returns 404, not an unhandled `StaleStateException`

**The bug:** `strikeRepository.findById(strikeId)` (`:238`) takes no lock — two concurrent delete
requests for the same `strikeId` both pass the existence/ownership check. The loser's subsequent
`strikeRepository.deleteById(strikeId)` (`:245`) issues Hibernate's entity-delete, which always expects
exactly one affected row regardless of whether the entity carries `@Version` (confirmed:
`CoachReliabilityStrike` has no `@Version` — this is not an optimistic-locking exception, it is
Hibernate's unconditional post-delete row-count check). Zero affected rows throws
`StaleStateException`/`ObjectOptimisticLockingFailureException` out of `flush()` — an unhandled 500,
not the 404 a "this strike is already gone" case should surface.

**Fix:** add a bulk `@Modifying @Query` delete keyed on both `id` and `coachId`, returning the affected
row count as `int`, replacing the entity-based `deleteById` call:

```java
@Modifying
@Query("DELETE FROM CoachReliabilityStrike s WHERE s.id = :id AND s.coachId = :coachId")
int deleteByIdAndCoachId(@Param("id") UUID id, @Param("coachId") UUID coachId);
```

(Same "derived row-by-row delete → genuine bulk `@Query` DELETE" pattern
`skillars-deferred-119`/`-120` already established for `LoginAttemptRepository` — precedent exists,
follow it exactly.) In `deleteStrike`, replace `:245`'s `strikeRepository.deleteById(strikeId)` with
`strikeRepository.deleteByIdAndCoachId(strikeId, coachId)`; if it returns `0`, throw the same
`ResourceNotFoundException("Strike not found", "coach_reliability_strike")` the initial `:238-239`
lookup already throws, instead of proceeding to the revert-decision logic. This makes the loser's
outcome a clean, expected 404 instead of an unhandled 500 — no actual concurrency/locking change, just
translating a 0-row bulk delete into the same exception the "never existed" case already produces.

**Note:** the *initial* `findById` ownership check at `:238-243` is still unlocked and still racy in
principle (two callers can both pass it before either deletes) — this fix targets the *delete step's*
error surface, not the check-then-act race itself, which is now provably harmless: whichever caller's
`deleteByIdAndCoachId` call executes second simply affects 0 rows and gets a 404, exactly as intended.

#### Tasks

- [ ] Add `deleteByIdAndCoachId` to `CoachReliabilityStrikeRepository` per the `@Modifying @Query` above
- [ ] Update `deleteStrike` to use it and throw `ResourceNotFoundException` on a `0` return
- [ ] Add a test proving the fix without needing real concurrency: seed a strike, delete it once via the
      repository method directly (asserts `1` returned), call `deleteStrike` again for the same
      `strikeId` and assert it throws `ResourceNotFoundException` (simulates the race loser
      deterministically — no threads needed, since the bug is really about 0-affected-row handling, not
      the race itself)

---

### AC3 — `getEnforcementProfile` reads a consistent status + strike-count snapshot

**The bug:** `getEnforcementProfile` (`:73-99`) reads `coach.getStatus()` (`:75-76`) and
`strikeRepository.countByCoachIdAndCreatedAtAfter` (`:78`) as two separate statements under the
method's current plain `@Transactional(readOnly = true)` (READ COMMITTED, Postgres's default) — each
statement sees the latest committed data *as of that statement*, not a shared snapshot. Every writer of
this pair (`deleteStrike`, `reinstateCoach`, `suspendCoach`, `ReliabilityStrikeService.issue`) now takes
`findByIdForUpdate` before writing both fields together in the same transaction — but a reader can still
observe a torn intermediate: `status=PENDING_REVIEW` with `activeStrikes=2` (post-delete-commit,
pre-status-commit is impossible within one writer's transaction, but a *second* writer committing
between this reader's two statements is not), or `status=ACTIVE` with `activeStrikes=5` — exactly the
pairing `CoachEnforcementProfileDto` exists to let an admin decide whether to reinstate.

**Fix:** raise this method's isolation to `REPEATABLE_READ` (Postgres's snapshot isolation — a single
transaction's every statement sees one consistent snapshot taken at its first statement):

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public CoachEnforcementProfileDto getEnforcementProfile(UUID coachId) {
```

No other line changes — this is a pure isolation-level fix, not a locking or query-shape change.
`readOnly = true` means no write-skew/serialization-failure risk from this method itself (Postgres's
`REPEATABLE READ` can only throw a serialization failure on a transaction that performs a write
conflicting with a concurrent snapshot; a read-only transaction never triggers one).

#### Tasks

- [ ] Add `isolation = Isolation.REPEATABLE_READ` to `getEnforcementProfile`'s `@Transactional`
      (`import org.springframework.transaction.annotation.Isolation;`)
- [ ] Add a test proving the snapshot consistency: mirror `AdminCoachEnforcementConcurrencyIT`'s
      raw-JDBC holder-thread mechanism — start `getEnforcementProfile` inside a controllable point after
      its first statement (status read) but before its second (strike count), commit a concurrent
      `deleteStrike`/strike-insert from a holder thread, then let `getEnforcementProfile` continue and
      assert the strike count it returns matches the status it returned (both from the pre-holder-commit
      snapshot), not a mix of before/after values. If instrumenting a precise pause point inside the
      method proves impractical, a lighter-weight test asserting the isolation level itself is
      configured (e.g. via a `TransactionSynchronizationManager` assertion inside a test-only listener,
      or reflection on the `@Transactional` annotation's `isolation` attribute) is an acceptable fallback
      — note which approach was used and why in the Dev Agent Record

---

### AC4 — Cross-field config validation: `visibilityThreshold` must not exceed `suspensionThreshold`

**The bug:** `ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY`/`SUSPENSION_THRESHOLD_KEY` are each
bounded independently via `configService.getBoundedLong(key, default, 1L, Long.MAX_VALUE)` at every
call site (`ReliabilityStrikeService.java:64-67`, `AdminCoachEnforcementService.java:254-255`) — nothing
validates their relative ordering. An operator setting `visibilityThreshold=10` with the default
`suspensionThreshold=5` (or any `visibility > suspension` pair) creates a state where `deleteStrike`'s
`count < visibilityThreshold` check (now `count < 10`) can revert a coach whose fresh count is still
`>= suspensionThreshold(5)` — wrongly clearing (or, post-AC1, wrongly reducing) a coach who should still
be `PENDING_REVIEW`.

**Owner decision (see Provenance):** close this via config-time validation, not by changing
`deleteStrike`'s own condition. `ConfigBounds.java`'s class Javadoc already names both keys as
deliberately excluded from `ConfigStartupAssertion`'s registry, pending "a follow-up [that] wants
boot-time coverage for them" — this is that follow-up, scoped to the ordering relationship specifically
(not full individual bounds registration for both keys, which stays out of scope).

**Fix:** add a dedicated cross-field check to `ConfigStartupAssertion.onApplicationEvent`, after the
existing per-`BoundedKey` loop (this relationship cannot be expressed as a single `BoundedKey`, which
only bounds one key against its own fixed range):

```java
long suspensionThreshold = configService.getBoundedLong(
    ReliabilityStrikeConfig.SUSPENSION_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_SUSPENSION_THRESHOLD, 1L, Long.MAX_VALUE);
long visibilityThreshold = configService.getBoundedLong(
    ReliabilityStrikeConfig.VISIBILITY_THRESHOLD_KEY, ReliabilityStrikeConfig.DEFAULT_VISIBILITY_THRESHOLD, 1L, Long.MAX_VALUE);
if (visibilityThreshold > suspensionThreshold) {
    // ERROR + config.value.misconfigured metric (new "reason" tag value, e.g. "cross_field_ordering")
    // + fail-fast violation (blocks boot in non-dev) — a wrongful ACTIVE/REDUCED revert is a real
    // coach-visibility data-integrity issue, matching this assertion's existing failFast bar ("halts
    // a core user flow" / causes incorrect state, not merely degraded).
}
```

Use `configService.getBoundedLong(...)` (the same call production call sites make), not a raw
`configService.find(...)` parse — it is simpler and exercises the exact runtime value these two
services will actually read, at the cost of the existing `ConfigStartupAssertionTest`'s established
"mock `configService.find(...)`" convention needing a second mocking style for this one check; document
that deviation briefly in the test file if it comes up in review. Reuse the existing
`increment(BoundedKey, String)` helper's `Counter` shape if practical, or add a small parallel one keyed
by a synthetic identifier (e.g. `"reliability.strike.threshold_ordering"`) since this check has no
single `BoundedKey` to hang the `{key, reason}` tags off.

#### Tasks

- [ ] Add the cross-field check to `ConfigStartupAssertion` per the sketch above — ERROR log, metric
      increment, and a fail-fast violation added to the existing `failFastViolations` list (so it
      participates in the existing "throws in non-dev, logs-only in dev" branching, not a separate
      code path)
- [ ] Add `ConfigStartupAssertionTest` cases: `visibilityThreshold > suspensionThreshold` in non-dev
      throws `AppSetupException` naming both keys; same misconfiguration in dev logs but does not throw;
      `visibilityThreshold <= suspensionThreshold` (including the equal-values boundary) does not flag
- [ ] Update `deferred-work.md`'s "code review of skillars-deferred-121" section: mark this bullet
      closed (delete it), noting the fix location

---

### AC5 — Document `reinstateCoach`'s fresh-suspension-override behavior as a decided, intentional tradeoff

**Owner decision (see Provenance):** keep current behavior — an explicit admin reinstate always
proceeds to `ACTIVE` even from a `SUSPENDED` state a concurrent `suspendCoach` call just committed. No
code change. This AC is documentation-only, closing the ledger item cleanly rather than leaving it
phrased as an open question a future audit might re-raise.

**Fix:**
1. Extend the existing comment at `AdminCoachEnforcementService.java:150-160` (already explains what the
   `skillars-deferred-121` lock does and does not close) with an explicit decision annotation, e.g.:
   `// [DECIDED 2026-09-18, skillars-deferred-122]: explicit admin intent always wins over a concurrent
   suspension — this is intentional, not a residual bug. No code change.`
2. Update `deferred-work.md`'s "code review of skillars-deferred-121" item 9 (the `reinstateCoach`
   stale-vs-fresh-suspension bullet) — annotate `[DECIDED: explicit admin intent wins —
   skillars-deferred-122]` per this file's own convention for a decision that changes no code (mirroring
   how `ses-1-4`'s `EmailTemplate.valueOf` poison-row bullet was annotated rather than deleted).

#### Tasks

- [ ] Extend the `reinstateCoach` comment with the decision annotation
- [ ] Annotate (not delete) the corresponding `deferred-work.md` bullet

---

### AC6 — `UserAdminService.findExpiredUsers` actually paginates instead of loading the whole expired set every call

**The bug:** `findExpiredUsers` (`:198-206`) constructs `PageRequest.of(0, batchSize)` (`:200`) and then
calls `userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(cutoffDate)` (`UserRepository:27`,
which takes no `Pageable` at all) — the constructed `Pageable` is a dead local variable, never passed
anywhere. Every call materializes **every** expired `User` entity, then applies `.limit(batchSize)` in
the Java stream. The method's own Javadoc ("Uses pagination to limit fetched amount") is false. This is
amplified by `skillars-deferred-120`'s own `MAX_BATCHES_PER_RUN = 100`: up to 100 full re-materializations
of the entire expired set per daily run.

**Fix:** add a genuinely paged repository method that also honors the `excludeLogins` set server-side
(currently filtered in Java after the full load) so the query itself does the work:

```java
// UserRepository
List<User> findByActivatedFalseAndCreatedDateBeforeAndLoginNotInOrderByIdAsc(
    ZonedDateTime cutoffDate, Collection<String> excludeLogins, Pageable pageable);
```

Update `findExpiredUsers` to call this instead of the unpaged method + Java-side `.filter().limit()`.
Confirm `excludeLogins` being empty (first batch, before any failures) works correctly with Spring
Data's `NotIn` against an empty collection — Spring Data JPA renders `NOT IN ()` unsafely for some
dialects; verify against Postgres directly (a real Testcontainers-backed test, not a mock) or guard with
a small `excludeLogins.isEmpty()` branch calling a plain `findByActivatedFalseAndCreatedDateBeforeOrderByIdAsc(...,
pageable)` overload if the empty-collection case proves problematic.

#### Tasks

- [ ] Add the new paged (and, if needed, no-exclude-set) repository method(s)
- [ ] Update `findExpiredUsers` to use them; remove the dead `Pageable`-then-Java-`.limit()` shape
- [ ] Add/update `UserAdminServiceTest` coverage proving the query is now actually paged (e.g. seed more
      than `batchSize` expired users, assert exactly `batchSize` are returned by the repository call
      itself, not by a post-hoc Java limit) and that `excludeLogins` filtering still works (including the
      empty-set case)
- [ ] Correct the method's Javadoc claim if it still says something no longer accurate after the fix

---

### AC7 — `UserAdminService.MAX_BATCHES_PER_RUN` scales with the configured batch size

**The bug:** `MAX_BATCHES_PER_RUN = 100` (`:49`) is a hardcoded constant multiplied by
`SecurityProperties.getUserCleanupBatchSize()` (default 100) to form the run's real ceiling
(10,000 delete attempts, per the constant's own Javadoc derivation). Lowering the configured batch size
to 10 silently caps the daily sweep at `100 × 10 = 1,000` users — a 10x throughput cut with no error, no
warning, and no relationship enforced between the two numbers.

**Fix:** replace the fixed batch-count cap with a fixed *total-attempts* cap, deriving the batch-count
ceiling from the configured batch size each run:

```java
private static final int MAX_DELETE_ATTEMPTS_PER_RUN = 10_000; // unchanged worst-case ceiling, see Javadoc
...
int maxBatches = Math.max(1, MAX_DELETE_ATTEMPTS_PER_RUN / batchSize);
while (hasMore && batches < maxBatches) { ... }
```

This keeps the exact same 10,000-attempt/`PT1H` `lockAtMostFor` worst-case arithmetic the existing
Javadoc already derives and documents, but makes it invariant to the operator's chosen batch size
instead of silently degrading with it. Update the class Javadoc's sizing-basis note (`:95-108`) to
describe the new derivation.

#### Tasks

- [ ] Replace `MAX_BATCHES_PER_RUN` with a total-attempts-based derivation per the sketch above
- [ ] Update the Javadoc at `:39-49` and `:95-108` to describe the new derivation, not the old fixed cap
- [ ] Add/update `UserAdminServiceTest` coverage: a smaller configured batch size still allows the same
      total-attempts ceiling (not a proportionally smaller one)

---

### AC8 — `UserAdminService.deleteUserInTransaction`'s `REQUIRES_NEW` actually applies (self-proxy fix)

**The bug:** `removeNotActivatedUsers` calls `deleteUserInTransaction(user.getLogin())` (`:153`) as a
plain `this`-call. `deleteUserInTransaction` is `protected` and Spring's AOP proxy is bypassed on a
self-invocation regardless of visibility, so its `@Transactional(propagation = REQUIRES_NEW)` (`:238`)
is inert — already documented in the method's own Javadoc (`:212-225`) added by `skillars-deferred-120`'s
code review response. What actually isolates each delete today is `SimpleJpaRepository`'s own per-call
transactionality, not this method's annotation — a real, if narrow, residual TOCTOU window between the
`findOneByLogin` read and the `delete` write (narrower than `skillars-deferred-118`'s original
whole-batch-window bug, but not zero).

**Fix:** mirror `VideoSubscriptionLifecycleListener`'s established self-proxy pattern
(`VideoSubscriptionLifecycleListener.java:59-60,116`) exactly — inject a lazy self-reference and call
through it so the proxy (and therefore `REQUIRES_NEW`) actually applies:

```java
@Autowired @Lazy
private UserAdminService self;
...
self.deleteUserInTransaction(user.getLogin());  // was: deleteUserInTransaction(user.getLogin())
```

Note: this class currently uses constructor injection via `@RequiredArgsConstructor` for its two
`final` fields; the self-reference must be field-injected (`@Autowired @Lazy`, not a constructor
parameter, which would create an unsatisfiable circular-dependency-at-construction-time requirement) —
exactly how `VideoSubscriptionLifecycleListener` does it. Update the method's own Javadoc (`:208-225`)
to describe the fix instead of describing why the old code was harmless-but-inert.

#### Tasks

- [ ] Add the `@Autowired @Lazy` self-reference field
- [ ] Change the call site at `:153` to go through `self`
- [ ] Update `deleteUserInTransaction`'s Javadoc to reflect that `REQUIRES_NEW` now genuinely applies
- [ ] Add/update `UserAdminServiceTest` (or a new lightweight IT if the fix's effect cannot be proven at
      the unit level with mocks) proving the proxy is actually invoked — e.g. a Mockito
      `verify(self).deleteUserInTransaction(...)` style check only proves the call site changed, not
      that the transaction boundary is real; prefer an IT-level assertion (e.g. via
      `TransactionSynchronizationManager` inside a test hook, or asserting an actual separate
      transaction commit is observable) if practical, and note in the Dev Agent Record which approach
      was used and why

---

### AC9 — Persist a `cleanup_failed_at` marker for users that fail non-activated cleanup every run

**The bug:** `removeNotActivatedUsers`'s per-user `catch` block (`:151-166`) adds a failed login to the
in-memory `failedLogins` set (cleared every run) and logs an ERROR, but nothing persists which users are
stuck. A deterministically-undeletable user (an uncovered FK, a trigger, a constraint) produces the
identical recurring ERROR log on every subsequent daily run with no operator-queryable record of what is
actually stuck versus merely slow.

**Fix:**
1. New Flyway migration `V143__user_cleanup_failed_at.sql` adding a nullable
   `cleanup_failed_at timestamptz` column to `main."user"` (mirror this project's existing nullable
   timestamp column conventions in that table, e.g. `activation_date`/`reset_expiration`).
2. On a per-user deletion failure inside `removeNotActivatedUsers`'s `catch` block, stamp
   `cleanup_failed_at = now()` on that user row (a small, separate, best-effort update — must not itself
   throw and abort the sweep; wrap defensively) in addition to (not instead of) the existing in-run
   `failedLogins` exclusion.
3. Update `findExpiredUsers`'s query (already being changed by AC6) to also exclude rows where
   `cleanup_failed_at IS NOT NULL`, so a persistently-stuck user stops consuming batch slots on
   *every* future run, not just the run it first failed in.
4. This is deliberately a one-way marker for this story (no automatic retry/clear mechanism) — "give
   operators something to query" was the stated requirement; a stuck row is now findable via
   `SELECT * FROM main."user" WHERE cleanup_failed_at IS NOT NULL`, which operators can act on manually
   (fix the underlying FK/trigger/constraint issue, or clear the marker to let the sweep retry).

#### Tasks

- [ ] Write `V143__user_cleanup_failed_at.sql` adding the nullable column
- [ ] Add the `cleanupFailedAt` field to the `User` entity (matching existing nullable-timestamp field
      conventions in that class)
- [ ] Stamp it in `removeNotActivatedUsers`'s catch block (defensively — do not let this update's own
      failure abort the sweep)
- [ ] Exclude `cleanup_failed_at IS NOT NULL` rows in the repository query from AC6
- [ ] Add/update `UserAdminServiceTest`/an IT proving: a user whose deletion fails gets the marker
      stamped, and a subsequent call to `findExpiredUsers` (simulating the next scheduled run) excludes
      that user even though the in-memory `failedLogins` set has been reset

---

### AC10 — Ledger hygiene closeout

- [ ] Re-run the grep sweep this story's Provenance section already ran (`AdminCoachEnforcementService`,
      `deleteStrike`, `getEnforcementProfile`, `reinstateCoach`, `visibilityThreshold`,
      `suspensionThreshold`, `UserAdminService`, `findExpiredUsers`, `MAX_BATCHES_PER_RUN`,
      `deleteUserInTransaction`) against HEAD immediately before marking this story done, confirming no
      bullet added by another story in the interim overlaps this one
- [ ] Delete the four closed bullets from "Deferred from: code review of skillars-deferred-121"
      (AC1's two, AC2's, AC3's) and "Deferred from: code review of skillars-deferred-120" (AC6's, AC7's,
      AC8's, AC9's) — seven bullets total closed by direct fix
- [ ] Annotate (not delete) the two decided-not-fixed bullets: AC4's cross-field-validation item (closed
      by a real fix — delete it, it is not merely decided) and AC5's `reinstateCoach` item
      (`[DECIDED]`, per AC5's own Task above)
- [ ] Confirm the four explicitly-out-of-scope items (see Provenance) remain correctly present and
      untouched

---

## Dev Notes

- **No local `mvn verify`** per project convention (`docs/validation-strategy.md`) — GitHub CI is the
  sole full-verification gate. Run targeted suites only.
- **Two independent problem domains, deliberately bundled per this project's "do not create small
  stories" convention** — Group A (`AdminCoachEnforcementService`, AC1-AC5) and Group B
  (`UserAdminService`, AC6-AC9) share no code. They can be implemented and tested in either order or in
  parallel; there is no cross-AC dependency except AC6 and AC9 both touching `findExpiredUsers`'s query
  shape (implement AC6 first, then layer AC9's additional exclusion predicate onto the same method).
- **AC1 is the highest-risk AC in this story** — it changes an existing test's asserted outcome, not
  just adds new coverage. Read `ManualStrikeIT.deleteStrike_noStatusChange_doesNotResolveAlert` in full
  before starting; do not assume "test still green" proves correctness the way it normally would.
- **`CoachReliabilityStrike` has no `@Version`** (confirmed by direct read) — AC2's bulk-delete fix does
  not need to account for optimistic-lock semantics, only Hibernate's unconditional post-delete
  row-count expectation.
- **`ConfigBounds.ALL` is deliberately not being extended with individual entries for
  `VISIBILITY_THRESHOLD_KEY`/`SUSPENSION_THRESHOLD_KEY`** (AC4) — only the cross-field relationship is
  in scope, per the owner decision. Do not interpret `ConfigBounds.java`'s own "add them if/when a
  follow-up wants boot-time coverage" invitation as license to also register both keys individually;
  that is a separate, un-asked-for expansion.
- **`self`-proxy injection precedent (AC8):** `VideoSubscriptionLifecycleListener.java:59-60` is the
  exact pattern to copy — `@Autowired @Lazy` field, not a constructor parameter (this class currently
  has none, only `@RequiredArgsConstructor`-injected `final` fields; do not convert those to also go
  through `self`, only the specific `REQUIRES_NEW` call site needs it).
- **Migration numbering (AC9):** confirm `V143` is still the next free number immediately before writing
  the migration — another merged story between this one's creation and its implementation could have
  claimed it.
- **Testing approach for AC3's isolation fix:** this is the hardest test in the story to make
  deterministic (proving a `REPEATABLE READ` snapshot, not merely that the annotation is present) — read
  `AdminCoachEnforcementConcurrencyIT`'s holder-thread mechanism first; if a precise mid-method pause
  point cannot be instrumented cleanly, the story explicitly accepts a lighter-weight
  annotation-presence test as a fallback (see AC3's own Tasks) rather than forcing a fragile test into
  existence.

### Project Structure Notes

- AC1-AC5 touch `platform.admin.service` (`AdminCoachEnforcementService`) and
  `platform.marketplace.repo` (`CoachReliabilityStrikeRepository`, AC2 only) plus
  `platform.config.service` (`ConfigStartupAssertion`, AC4 only) and `deferred-work.md` (AC5, AC10).
- AC6-AC9 touch `platform.security.service` (`UserAdminService`) and `platform.security.repo`
  (`UserRepository`, `User` entity, AC6/AC9) plus one new Flyway migration (AC9).
- No REST contract changes anywhere in this story — every fix is inside existing service/repository
  internals; `AdminCoachEnforcementResource`'s and any user-admin-facing endpoints' request/response
  shapes are unaffected.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java`
  (AC1-AC5 — whole file; re-verify every line number cited in this story against your own checkout
  before writing code, per the Provenance section's warning about stale ledger citations)
- `src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java:39-113`
  (AC1 reference — `issue()`'s three-tier escalation logic to mirror in reverse; AC4 reference — its own
  `getBoundedLong` call site for both threshold keys)
- `src/test/java/com/softropic/skillars/platform/admin/api/ManualStrikeIT.java` (AC1, AC2 — existing
  `deleteStrike` coverage, including the test whose assertion AC1 must change; seeding/teardown
  conventions to mirror for new tests)
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java`
  (AC1 sanity-check, AC3 — holder-thread mechanism to mirror for the isolation-level test)
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java`
  and `CoachReliabilityStrike.java` (AC2 — confirm no `@Version`, add the new bulk-delete method)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java` and
  `ConfigBounds.java` (AC4 — read the class Javadoc's own "add them if/when" note in full; understand
  the existing `BoundedKey`/`failFastViolations` mechanics before adding the cross-field check)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertionTest.java` (AC4 —
  existing mocking convention, `EVENT`/`meterRegistry` test fixture setup)
- `src/main/java/com/softropic/skillars/platform/security/service/UserAdminService.java` (AC6-AC9 —
  whole file; the Javadoc already documents the AC8 gap in detail, written by `skillars-deferred-120`'s
  own code review response)
- `src/main/java/com/softropic/skillars/platform/security/repo/UserRepository.java` and `User.java`
  (AC6, AC9 — existing query method conventions, nullable-timestamp field conventions)
- `src/test/java/com/softropic/skillars/platform/security/service/UserAdminServiceTest.java` (AC6-AC9 —
  existing mock/test conventions for this class)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java:55-116`
  (AC8 — the exact `@Autowired @Lazy self` pattern to copy)
- `src/main/resources/db/migration/` (AC9 — confirm `V143` is the next free migration number; review
  `User.java`'s existing nullable-timestamp `@Column` conventions in a recent migration for the new
  column's DDL style)
- `_bmad-output/implementation-artifacts/deferred-work.md` — the two source sections named in Provenance,
  and the `ConfigBounds.java`-referenced historical rationale

---

## Verification Checklist

- [ ] AC1: `deleteStrike` gains a `REDUCED` transition for `PENDING_REVIEW` coaches whose fresh in-window
      count lands in `[visibilityThreshold, suspensionThreshold)`; out-of-window strike deletions produce
      zero status/alert/action-log side effects; `ManualStrikeIT`'s existing test updated to the corrected
      expectation, not left asserting the old (buggy) outcome
- [ ] AC2: concurrent-duplicate `deleteStrike` semantics verified deterministically (0-affected-row →
      `ResourceNotFoundException`/404), not merely "no longer throws `StaleStateException`" by absence
- [ ] AC3: `getEnforcementProfile` runs at `REPEATABLE_READ`; test proves either actual snapshot
      consistency or, as an accepted fallback, the isolation level is genuinely configured
- [ ] AC4: `ConfigStartupAssertion` fails fast (non-dev) when `visibilityThreshold > suspensionThreshold`;
      logs-only in dev; boundary (`visibility == suspension`) does not flag
- [ ] AC5: `reinstateCoach` comment and `deferred-work.md` bullet both annotated `[DECIDED]`; zero
      production behavior change
- [ ] AC6: `findExpiredUsers` proven to query with real pagination (not a post-hoc Java `.limit()`) and
      correctly excludes both the in-run `failedLogins` set and (post-AC9) persistently-failed users
- [ ] AC7: lowering the configured batch size no longer reduces the total per-run delete-attempt ceiling
- [ ] AC8: `self`-proxy call site added; `REQUIRES_NEW` genuinely applies (test proves the transaction
      boundary, not just the call-site change)
- [ ] AC9: `V143` migration applied; a user whose deletion fails is excluded from every subsequent run's
      candidate set, not just the run it first failed in
- [ ] AC10: ledger sweep re-run against HEAD; seven bullets deleted, two annotated `[DECIDED]`, four
      confirmed still correctly out of scope
- [ ] No regressions in `ManualStrikeIT`, `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`,
      `CoachSuspensionIT`, `CoachEnforcementListIT`, `UserAdminServiceTest`, `ConfigStartupAssertionTest`,
      or any other test touching `AdminCoachEnforcementService`, `UserAdminService`, or
      `ConfigStartupAssertion`

---

## File List

_To be filled in during implementation._

---

## Change Log

- 2026-09-18: Story created via `/bmad-create-story`. Mined the two most recent, still-open code-review
  deferrals (`skillars-deferred-120` and `skillars-deferred-121`, both 2026-09-1{7,8}) per the
  established "read same-day code-review deferrals before drawing scope" convention. All cited line
  numbers re-verified against `master@aa920c49` at creation time — several of the ledger's own
  `AdminCoachEnforcementService.java` citations were already stale due to `skillars-deferred-121`'s own
  code-review-response comment growth, corrected throughout. Three owner decisions taken live with the
  user before drafting: (1) `reinstateCoach` vs. a fresh concurrent suspension — keep current behavior,
  document only; (2) `visibilityThreshold > suspensionThreshold` — close via `ConfigStartupAssertion`
  cross-field validation, not a `deleteStrike`-side fix; (3) of the two migration-sized items surfaced
  (`resetStaleClaimed` claim-time fix; `UserAdminService` cross-run undeletable-user record) — only the
  latter (`cleanup_failed_at` marker, AC9) is in scope; the former stays deferred, consistent with its
  own ledger text already calling it out of scope for this shape of story. Ten ACs across two unrelated
  modules (`AdminCoachEnforcementService` AC1-AC5, `UserAdminService` AC6-AC9, ledger closeout AC10),
  bundled per this project's established "do not create small stories" convention. Notable finding made
  during story drafting, not carried over from the ledger verbatim: `ManualStrikeIT`'s existing
  `deleteStrike_noStatusChange_doesNotResolveAlert` test currently asserts the AC1 bug's buggy outcome as
  if it were correct behavior — flagged explicitly in AC1 and its own Task list so the dev agent updates
  the test's expectation rather than treating "still green" as proof of correctness.

---

## Dev Agent Record

### Implementation Plan

_To be filled in during implementation._

### Debug Log

_To be filled in during implementation._

### Completion Notes

_To be filled in during implementation._
