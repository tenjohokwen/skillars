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

Four of the nine pre-existing findings `skillars-deferred-121`'s own code review surfaced in this
file are genuine, narrowly-scoped bugs with no schema change and no owner ambiguity about the fix
shape — `deleteStrike`'s two revert-logic gaps plus the concurrent-duplicate-delete and
inconsistent-view findings, addressed via AC1–AC3 below (AC1 covers two of the four). Two more needed
an explicit product/config decision, both resolved with the user directly before this story was
drafted (AC4, AC5). The remaining three ledger items (`issueManualStrike`'s missing coach-status guard,
the strike-DELETE's unbounded-wait-outside-retry gap in `PessimisticLockRetryer`, and the
30-day-window-slides-with-contention timing issue shared with `ReliabilityStrikeService.issue`) are
**left deferred** — see "Explicitly out of scope" below.

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
already-not-counted strike deleted gets a spurious revert-to-`ACTIVE`/`REDUCED` (whichever tier the fresh
count happens to land in), plus alert resolution and a `COACH_REINSTATE`/`COACH_STRIKE_DELETED`
action-log entry — even though deleting that strike changed nothing about the qualifying count (it was
never counted before deletion either).

**Fix:**

1. Read `suspensionThreshold` in `deleteStrike` — it currently reads only `visibilityThreshold`
   (`:254-255`), and the corrected tiering below needs both, independently of whether AC4's config
   validation ever runs. Do not rely on AC4 to make this correct at runtime; AC1's own logic must be
   self-sufficient.
2. Hoist a single `OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);` local and use it for
   both the `count` computation (already at `:253`) and the new out-of-window guard below — do not call
   `now()` twice, which can judge a boundary strike differently between the two checks. Match
   `countByCoachIdAndCreatedAtAfter`'s own comparison exactly: it is a derived `...After` query, which
   Spring Data renders as strict `createdAt > :since` — the guard's own "is this strike in-window" check
   must use the same strict `>`, not `>=`.
3. Capture the strike's own `createdAt` into a **local variable** before deleting it (the entity is
   already loaded at `:238` for the ownership check — no extra query needed). Do not re-read or mutate
   the `strike` entity after the delete call from AC2 — see AC2's own note on why the entity becomes a
   stale, still-managed object once that fix lands.
4. Only evaluate a status transition at all if the deleted strike's `createdAt` is after `cutoff` —
   otherwise skip straight to the existing "no status change" `else` branch, regardless of what `count`
   happens to be. **This branch is unchanged in shape and still writes its existing
   `COACH_STRIKE_DELETED` action-log row** (`:275-278`) — the out-of-window guard only ever suppresses a
   *status* transition, never the log write every `deleteStrike` call already makes today. Do not
   suppress the log row; see Task list below, which previously (incorrectly) asked for zero rows.
5. When the deleted strike *was* in-window, replace the binary `reverted` decision with three-tier logic
   that is a true reverse mirror of `ReliabilityStrikeService.issue`'s tiers (`:93-110`), **evaluated
   top-tier-first**, gated to `PENDING_REVIEW`/`REDUCED` source statuses only (unchanged scope —
   `deleteStrike` still never touches `SUSPENDED`, that stays `reinstateCoach`'s job):
   ```java
   if (count >= suspensionThreshold) {
       // no change — stay PENDING_REVIEW; still too many in-window strikes to de-escalate at all
   } else if (count >= visibilityThreshold) {
       // PENDING_REVIEW -> REDUCED (no-op if already REDUCED)
   } else {
       // -> ACTIVE (existing behavior, unchanged)
   }
   ```
   **Get the top tier right — this is the part the first draft of this AC got backwards.** Gating the
   `REDUCED` transition on `count >= visibilityThreshold` *alone*, without also checking
   `count < suspensionThreshold` first, de-escalates a coach who is still above the suspension bar: at
   seeded defaults (`suspensionThreshold=5`, `visibilityThreshold=3`,
   `V139__baseline_seed_data.sql:137-138`), a `PENDING_REVIEW` coach with 7 in-window strikes whose
   admin deletes one (fresh `count=6`) must **stay `PENDING_REVIEW`** (`6 >= suspensionThreshold`), not
   become `REDUCED`. `count >= visibilityThreshold` never implies `count < suspensionThreshold` — those
   are two independent facts about `count`, and AC4's config-ordering guarantee (`visibilityThreshold <=
   suspensionThreshold`) constrains the *thresholds* relative to each other, not where a given `count`
   falls between them. Always check the `suspensionThreshold` tier first.
6. **`resolveOpenStrikeAlert` continues to fire only on the full `ACTIVE` clear, not on the new
   `PENDING_REVIEW`→`REDUCED` transition** — a coach still `REDUCED` remains at an elevated strike
   count worth the admin's attention; only a full clear to `ACTIVE` should resolve the
   `STRIKE_THRESHOLD` alert. This preserves the existing alert-resolution semantics exactly; only the
   coach-status side gains the new middle tier.
7. The `REDUCED` transition reuses the existing `COACH_STRIKE_DELETED` `AdminActionType` with a reason
   string that distinguishes it from the plain no-change case (e.g. "Strike deleted (coach reduced to
   REDUCED): " + reason) — do **not** add a new enum constant; `AdminCoachEnforcementConcurrencyIT`'s
   existing `deleteStrike_concurrentStrikesPushCountAboveThreshold_doesNotRevertOnStaleCount` test
   (see Task list below) already asserts `action_type = 'COACH_STRIKE_DELETED'` on the no-revert path,
   and `COACH_REINSTATE` stays reserved for the full-`ACTIVE` case exactly as today.
8. **Behavior-change note, not a defect to fix here:** today, deleting *any* strike (even one already
   outside the 30-day window, which changes nothing about the qualifying count) still triggers a full
   re-evaluation, so it can accidentally self-heal a coach whose real strikes have all aged out. Step 4's
   out-of-window guard removes that accidental path — after this fix, an admin must use `reinstateCoach`
   explicitly to clear a coach whose elevated status has become stale purely from strike ageout, not
   from any deletion. This is the correct behavior (a status change should reflect the strike actually
   being acted on, not an unrelated old record), but it is a real, user-visible behavior change for
   admins — call it out in the PR description / any admin-facing runbook note, not just bury it in a
   commit.

#### Tasks

- [ ] Update `ManualStrikeIT.deleteStrike_noStatusChange_doesNotResolveAlert` (rename to reflect the
      corrected expectation, e.g. `deleteStrike_countDropsIntoReducedBand_revertsToReducedAlertStaysOpen`):
      it seeds 5 strikes then deletes 1 → fresh count 4, which is `>= visibilityThreshold(3)` and
      `< suspensionThreshold(5)` — assert coach status becomes `REDUCED` (not unchanged `PENDING_REVIEW`)
      and the alert **stays `OPEN`** (unaffected — do not resolve on a `REDUCED` transition)
- [ ] Add a new test for the corrected top tier: seed a `PENDING_REVIEW` coach with enough in-window
      strikes that the fresh post-delete count is still `>= suspensionThreshold` (e.g. 8 strikes, delete
      1 → count 7) — assert the coach **stays `PENDING_REVIEW`**, not `REDUCED`. This is the case finding
      1 of the story-review caught as missing and is the single most important new test in this AC.
- [ ] Add a new test seeding a coach at `PENDING_REVIEW` with an in-window count that, after deletion,
      lands below `visibilityThreshold` — asserts full `ACTIVE` revert + alert resolved (confirms the
      existing `ACTIVE` path still works unchanged after the refactor)
- [ ] Add a new test proving the out-of-window guard: seed 2 in-window strikes (count already `< 3`,
      coach manually set to `PENDING_REVIEW` to isolate this case) plus one strike created
      `now().minusDays(40)`; delete the 40-day-old one; assert **no** status change, **no** alert
      resolution, and **a `COACH_STRIKE_DELETED` action-log row** (not zero rows — every `deleteStrike`
      call writes exactly one action-log row today, on both branches; the out-of-window guard only
      suppresses the status/alert side effects)
- [ ] Implement the fix in `AdminCoachEnforcementService.deleteStrike` per the steps above
- [ ] **Redesign, as a first-class task, not a sanity check:**
      `AdminCoachEnforcementConcurrencyIT.deleteStrike_concurrentStrikesPushCountAboveThreshold_doesNotRevertOnStaleCount`
      (`:299-375`). As written it seeds `visibilityThreshold-1` (2) + the target strike (3), the holder
      inserts 2 more while holding the lock (5 total), and the target delete leaves a fresh `count=4` —
      which lands *inside* the new `REDUCED` band under the corrected tiering too, so the test's current
      `status == "PENDING_REVIEW"` assertion goes red regardless of which tiering (buggy or corrected) is
      implemented. Fix the test's own seeding, not just the assertion: have the holder insert enough
      strikes that the fresh post-delete count lands `>= suspensionThreshold` (e.g. insert 3 more instead
      of 2, so fresh count = 6 `>= suspensionThreshold(5)`) so "no status change" remains the *correct*
      fresh-count outcome and the test still discriminates a fresh read from the stale
      below-threshold count a pre-fix bug would have acted on. Keep the existing
      `COACH_STRIKE_DELETED` action-log assertion (`:369-374`) — it stays orthogonal to this change.

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

**Interaction with AC1 (implement together, read both before touching this method):** the bulk
`@Modifying` `DELETE` executes directly against the database and bypasses the persistence context —
the `strike` entity `findById` already loaded at `:238` stays **managed but now stale** (its row no
longer exists). Nothing in `deleteStrike` today re-reads or saves `strike` after deleting it, so this is
safe as currently shaped, but AC1 needs `strike.getCreatedAt()` for its out-of-window guard — that value
**must** be captured into a local variable before the bulk delete call, never read from the `strike`
entity reference afterward. Do not reach for `clearAutomatically = true` on the new
`@Modifying` query as a defensive habit — it detaches every managed entity in the persistence context,
not just this one, for no benefit here.

#### Tasks

- [ ] Add `deleteByIdAndCoachId` to `CoachReliabilityStrikeRepository` per the `@Modifying @Query` above
- [ ] Update `deleteStrike` to use it and throw `ResourceNotFoundException` on a `0` return
- [ ] Add a test proving the fix without needing real concurrency: seed a strike, delete it once via the
      repository method directly (asserts `1` returned), call `deleteStrike` again for the same
      `strikeId` and assert it throws `ResourceNotFoundException` (simulates the race loser
      deterministically — no threads needed, since the bug is really about 0-affected-row handling, not
      the race itself)

---

### AC3 — `getEnforcementProfile` and `getCoachesUnderEnforcement` read a consistent status + strike-count snapshot

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

**`getCoachesUnderEnforcement` (`:284-313`) has the identical torn-read shape**, feeding the identical
admin decision from the enforcement list view instead of the single-profile view: `coach.getStatus()`
(via the `:298` query) and `strikeRepository.countByCoachIdInAndCreatedAtAfter` (`:304`) are two separate
statements under the same plain `@Transactional(readOnly = true)`. Fix both in this AC — closing one and
leaving the other would close only half of the underlying inconsistent-view defect the ledger names.

**Fix:** raise both methods' isolation to `REPEATABLE_READ` (Postgres's snapshot isolation — a single
transaction's every statement sees one consistent snapshot taken at its first statement):

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public CoachEnforcementProfileDto getEnforcementProfile(UUID coachId) { ... }

@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public Page<CoachEnforcementListItemDto> getCoachesUnderEnforcement(String statusParam, int page) { ... }
```

No other line changes in either method — this is a pure isolation-level fix, not a locking or
query-shape change. `readOnly = true` means no write-skew/serialization-failure risk from either method
(Postgres's `REPEATABLE READ` can only throw a serialization failure on a transaction that performs a
write conflicting with a concurrent snapshot; a read-only transaction never triggers one).

**Isolation warning — this affects both production correctness and how the test for this AC must be
written.** Spring's transaction manager defaults `validateExistingTransaction` to `false` (not
overridden anywhere in this codebase — confirmed). That means an `@Transactional(isolation=...)` method
that *participates* in an already-open ambient transaction has its isolation request **silently
ignored**, no error, and simply runs at whatever isolation the outer transaction already established.
Production is unaffected — `AdminCoachEnforcementResource.java:47` is the only caller of
`getEnforcementProfile` and opens no enclosing transaction, so a fresh top-level `REPEATABLE_READ`
transaction is genuinely created every call; `getCoachesUnderEnforcement`'s caller is equally
transaction-free. But **the test for this AC must not invoke either method from inside an `@Transactional`
test method or a `TransactionTemplate.execute(...)` block** — doing so would silently exercise
`READ_COMMITTED` and the test would prove nothing while still going green. Drive the test through HTTP
(as `CoachEnforcementListIT`/`ReinstateIT` already do), not via a directly-injected, transaction-wrapped
service call.

#### Tasks

- [ ] Add `isolation = Isolation.REPEATABLE_READ` to both `getEnforcementProfile`'s and
      `getCoachesUnderEnforcement`'s `@Transactional`
      (`import org.springframework.transaction.annotation.Isolation;`)
- [ ] Add a test proving the snapshot consistency for `getEnforcementProfile`: mirror
      `AdminCoachEnforcementConcurrencyIT`'s raw-JDBC holder-thread mechanism — start
      `getEnforcementProfile` (via HTTP, per the isolation warning above) inside a controllable point
      after its first statement (status read) but before its second (strike count), commit a concurrent
      `deleteStrike`/strike-insert from a holder thread, then let `getEnforcementProfile` continue and
      assert the strike count it returns matches the status it returned (both from the pre-holder-commit
      snapshot), not a mix of before/after values. If instrumenting a precise pause point inside the
      method proves impractical, a lighter-weight test asserting the isolation level itself is
      configured (e.g. reflection on the `@Transactional` annotation's `isolation` attribute) is an
      acceptable fallback — **if taken, mark it explicitly in the Verification Checklist as "unverified
      behavior, annotation only", not as "AC3 verified"**; note which approach was used and why in the
      Dev Agent Record
- [ ] Add the equivalent annotation-presence (or, if practical, snapshot-consistency) test for
      `getCoachesUnderEnforcement`

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

**This boot-time check alone does not close the gap it claims to — add a read-time clamp too.**
`ConfigService` caches `platform_config` in a `ConcurrentHashMap` refreshed every
`app.config.cache-ttl-seconds` (default 300s) via a `@Scheduled` job (`ConfigService.java:38,56`) — an
operator can `UPDATE` the misconfigured pair on a running system and it takes effect within minutes, no
restart. `ConfigStartupAssertion` fires exactly once, on `ApplicationReadyEvent`
(`ConfigStartupAssertion.java:64`), so it only ever catches "boot with a bad pair already stored," not
"an operator tunes a threshold live and creates the bad pair while the app is running" — which is the
more likely real-world path to this misconfiguration. AC1 already adds a `suspensionThreshold` read to
`deleteStrike` (Fix step 1), so closing this for real is nearly free: clamp there too —

```java
visibilityThreshold = Math.min(visibilityThreshold, suspensionThreshold);
```

right after both are read in `deleteStrike`, before the tiering logic runs. This makes the boot check a
pure operator-visibility aid (it still fires and still blocks boot on a bad *stored* value, which is
worth keeping) while the read-time clamp is what actually, permanently prevents the wrongful-revert bug
regardless of when or how the misconfiguration arose. Do not let AC10 delete the ledger bullet on the
strength of the boot check alone — the clamp is the part that actually closes it.

#### Tasks

- [ ] Add the cross-field check to `ConfigStartupAssertion` per the sketch above — ERROR log, metric
      increment, and a fail-fast violation added to the existing `failFastViolations` list (so it
      participates in the existing "throws in non-dev, logs-only in dev" branching, not a separate
      code path)
- [ ] Add the `Math.min(visibilityThreshold, suspensionThreshold)` read-time clamp in `deleteStrike`
      immediately after both thresholds are read, before the tiering logic
- [ ] Add `ConfigStartupAssertionTest` cases: `visibilityThreshold > suspensionThreshold` in non-dev
      throws `AppSetupException` naming both keys; same misconfiguration in dev logs but does not throw;
      `visibilityThreshold <= suspensionThreshold` (including the equal-values boundary) does not flag
- [ ] Add a `deleteStrike`-level test proving the clamp: configure `visibilityThreshold > suspensionThreshold`
      directly (bypassing the boot check, simulating a live runtime misconfiguration) and confirm the
      revert decision still behaves as if `visibilityThreshold == suspensionThreshold`
- [ ] Update `deferred-work.md`'s "code review of skillars-deferred-121" section: mark this bullet
      closed (delete it), noting both fix locations (boot check + read-time clamp)

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

**Fix:** add a genuinely paged repository method. **Do not also push the in-run `failedLogins` set into
this query as a server-side `NOT IN` list** — `failedLogins` accumulates across the whole run and can
reach `batchSize × maxBatches` (up to 10,000 entries under AC7's ceiling); rendering a differently-sized
bind list on every call adds real cost (large `IN`/`NOT IN` lists, Hibernate query-plan-cache churn from
varying arity) that does not exist today (the Java-side filter is free by comparison). This would also
create two overlapping exclusion mechanisms once AC9 lands its own persisted `cleanup_failed_at`
predicate — implement this AC assuming AC9's persisted marker does the *cross-run* exclusion (the job
`failedLogins` was never well-suited for anyway, since it resets every run), and keep an **in-run only**,
Java-side `failedLogins` filter over the small, already-paged batch as a light belt-and-braces for the
window before the marker itself can be persisted:

```java
// UserRepository — paginated, no excludeLogins parameter
List<User> findByActivatedFalseAndCreatedDateBeforeOrderByIdAsc(ZonedDateTime cutoffDate, Pageable pageable);
```

```java
// findExpiredUsers — server-side pagination; failedLogins filtered client-side over one page, not the whole table
return userRepository.findByActivatedFalseAndCreatedDateBeforeOrderByIdAsc(cutoffDate, PageRequest.of(0, batchSize))
    .stream()
    .filter(u -> !excludeLogins.contains(u.getLogin()))
    .toList();
```

This keeps the Java-side filter (now bounded to one page, not the whole expired set) rather than an
unbounded bind list, while still closing this AC's actual premise: the query itself is now paged at the
database, not materializing every expired user on every call. **Note:** filtering post-page can return
fewer than `batchSize` rows in a batch that contains excluded logins — acceptable (the loop's `hasMore`
check already tolerates a short/empty batch), and is a pre-existing property of the batch shape, not a
regression this AC introduces.

Optional, not required for this AC: there is no index on `main."user"(activated, created_date)` today
(only `user_pkey`/`user_login_key`), so the paged query still walks the PK index and filters — still a
large win over materializing the full expired set, but if this fix is being measured as a performance
win rather than a correctness fix, consider a partial index (`WHERE activated = false`) alongside AC9's
migration.

#### Tasks

- [ ] Add the new paged repository method (no `excludeLogins` parameter — see above)
- [ ] Update `findExpiredUsers` to use it, keeping the Java-side `failedLogins` filter over the returned
      page only (not the whole table)
- [ ] Add/update `UserAdminServiceTest` coverage proving the query is now actually paged (e.g. seed more
      than `batchSize` expired users, assert the repository call itself returns at most `batchSize` rows)
      and that the in-run `failedLogins` filter still excludes already-failed logins within a page
- [ ] Correct the method's Javadoc claim if it still says something no longer accurate after the fix

---

### AC7 — `UserAdminService.MAX_BATCHES_PER_RUN` scales with the configured batch size

**The bug:** `MAX_BATCHES_PER_RUN = 100` (`:49`) is a hardcoded constant multiplied by
`SecurityProperties.getUserCleanupBatchSize()` (default 100) to form the run's real ceiling
(10,000 delete attempts, per the constant's own Javadoc derivation). Lowering the configured batch size
to 10 silently caps the daily sweep at `100 × 10 = 1,000` users — a 10x throughput cut with no error, no
warning, and no relationship enforced between the two numbers.

**Fix:** replace the fixed batch-count cap with a fixed *total-attempts* cap, deriving the batch-count
ceiling from the configured batch size each run. `SecurityProperties.userCleanupBatchSize` is a bare
`int` with no `@Min`/`@Validated` (`SecurityProperties.java:32`) — an operator-set `0` must not reach a
division, and an operator-set batch size above the total-attempts ceiling must not silently produce a
`maxBatches` of `0` or `1` that itself already exceeds the ceiling it exists to enforce:

```java
private static final int MAX_DELETE_ATTEMPTS_PER_RUN = 10_000; // unchanged worst-case ceiling, see Javadoc
...
int effectiveBatchSize = Math.max(1, batchSize); // guard against an unvalidated 0/negative config value
int maxBatches = Math.max(1, MAX_DELETE_ATTEMPTS_PER_RUN / effectiveBatchSize);
while (hasMore && batches < maxBatches) { ... }
```

Use `effectiveBatchSize` for the `maxBatches` derivation, but keep passing the *raw* `batchSize` to
`findExpiredUsers`/`PageRequest.of(...)` unchanged — `PageRequest.of(0, 0)` already throws today for a
misconfigured `0`, which is an existing, unrelated failure mode this AC does not need to also fix, only
avoid compounding with a second, earlier crash. Document (comment, not a hard clamp) that a configured
`batchSize` above `MAX_DELETE_ATTEMPTS_PER_RUN` yields `maxBatches == 1`, so a single batch in that
configuration can itself exceed the nominal ceiling — the cap is best-effort above that size, and an
operator setting a batch size that large should size it deliberately.

This keeps the exact same 10,000-attempt/`PT1H` `lockAtMostFor` worst-case arithmetic the existing
Javadoc already derives and documents, but makes it invariant to the operator's chosen batch size
instead of silently degrading with it. Update the class Javadoc's sizing-basis note (`:95-108`) to
describe the new derivation.

#### Tasks

- [ ] Replace `MAX_BATCHES_PER_RUN` with a total-attempts-based derivation per the sketch above,
      including the `effectiveBatchSize` zero/negative guard
- [ ] Update the Javadoc at `:39-49` and `:95-108` to describe the new derivation, not the old fixed cap,
      and note the best-effort caveat for a batch size above the ceiling
- [ ] Add/update `UserAdminServiceTest` coverage: a smaller configured batch size still allows the same
      total-attempts ceiling (not a proportionally smaller one); a `0`-configured batch size does not
      throw `ArithmeticException` computing `maxBatches` (it may still throw downstream in
      `findExpiredUsers`/`PageRequest.of` — that is out of scope for this AC, just don't add a second,
      earlier crash)

---

### AC8 — `UserAdminService.deleteUserInTransaction`'s `REQUIRES_NEW` actually applies (visibility fix + self-proxy)

**The bug, and why a self-proxy field alone does not fix it:** `removeNotActivatedUsers` calls
`deleteUserInTransaction(user.getLogin())` (`:153`) as a plain `this`-call, bypassing the Spring AOP
proxy — already documented in the method's own Javadoc (`:212-225`) added by `skillars-deferred-120`'s
code review response, which correctly diagnosed the self-invocation problem. But routing the call
through a proxy is **not sufficient by itself**: `deleteUserInTransaction` is **`protected`**
(`UserAdminService.java:239`), and `DataSourceConfig.java:20`'s bare `@EnableTransactionManagement`
(no `mode`/`proxyTargetClass` override) uses Spring's default `PROXY` mode, which builds an
`AnnotationTransactionAttributeSource` with `publicMethodsOnly = true`. Under that setting,
`AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` returns `null` — no
transactional advice at all — for **any non-public method, regardless of whether the call arrives
through the proxy**. This is standard, documented Spring behavior (Spring Framework reference,
"Method visibility and `@Transactional`": *"you should apply the `@Transactional` annotation only to
methods with public visibility"*), not specific to self-invocation. Adding only a self-reference field
changes nothing on its own — `@Transactional` is still silently ignored.

The precedent this story originally cited, `VideoSubscriptionLifecycleListener.processAndSaveEntry`
(`:120-121`), works for a **different reason than the self-proxy field alone**: that method is
**`public`**. The self-proxy pattern only becomes correct once combined with public visibility — copy
both, not just the field declaration.

**Fix — two changes, both required:**

1. Make `deleteUserInTransaction` **`public`** (from `protected`).
2. Inject a lazy self-reference and call through it, mirroring
   `VideoSubscriptionLifecycleListener.java:59-60,116` exactly:

```java
@Autowired @Lazy
private UserAdminService self;
...
self.deleteUserInTransaction(user.getLogin());  // was: deleteUserInTransaction(user.getLogin())
```

Note: this class currently uses constructor injection via `@RequiredArgsConstructor` for its two
`final` fields; the self-reference must be field-injected (`@Autowired @Lazy`, not a constructor
parameter, which would create an unsatisfiable circular-dependency-at-construction-time requirement).
Update the method's own Javadoc (`:208-225`) to describe the fix instead of describing why the old code
was harmless-but-inert.

**Knock-on to check, not necessarily fix here:** `findExpiredUsers` is *also* `protected` with its own
`@Transactional(readOnly = true)` (`:198-199`) — equally inert today for the identical reason, and its
returned `User` entities are therefore already detached the moment the repository call's own transaction
closes (relevant context for AC9's stamping mechanism below). Decide explicitly whether to make it
`public` too for consistency, or drop its misleading `@Transactional` annotation since it currently does
nothing; either is acceptable, but leaving it silently inert and unremarked is not.

**Verification bar — do not accept a test that cannot fail on the un-fixed code.** A
`verify(self).deleteUserInTransaction(...)`-style Mockito check only proves the call site changed; it
would pass green even if visibility were still `protected` and the annotation were still inert. Prefer
an assertion that actually exercises the transaction boundary (e.g. an IT-level test observing that a
failure partway through one user's delete does not affect a sibling user's already-committed delete, or
a `TransactionSynchronizationManager`-based assertion inside a test hook).

**Scope honesty for the Verification Checklist:** even with `REQUIRES_NEW` genuinely applied, the
`findOneByLogin` read followed by `delete` inside `deleteUserInTransaction` is a plain `SELECT` then
`DELETE` at READ COMMITTED with no row lock — this fix **narrows** the TOCTOU window sharply (from a
whole batch's worth of processing time down to the gap between two statements in one small method), it
does not **close** it. Say "narrows" in the Verification Checklist, not "closes". A fully-closing fix
(a conditional `DELETE ... WHERE login = ? AND activated = false`, or a locked re-read) is a reasonable
future hardening, not required by this AC.

#### Tasks

- [ ] Change `deleteUserInTransaction`'s visibility from `protected` to `public`
- [ ] Add the `@Autowired @Lazy` self-reference field
- [ ] Change the call site at `:153` to go through `self`
- [ ] Decide and act on `findExpiredUsers`'s equally-inert `protected` `@Transactional` (make it
      `public` too, or drop the misleading annotation) — do not leave it silently unaddressed
- [ ] Update `deleteUserInTransaction`'s Javadoc to reflect that `REQUIRES_NEW` now genuinely applies,
      and that it narrows (not closes) the residual TOCTOU window
- [ ] Add/update `UserAdminServiceTest` (or a new lightweight IT) proving the transaction boundary is
      genuinely real, not just that the call site changed — a bare
      `verify(self).deleteUserInTransaction(...)` check is insufficient on its own (see above); note in
      the Dev Agent Record which approach was used and why

---

### AC9 — Persist a `cleanup_failed_at` marker for users that fail non-activated cleanup every run

**The bug:** `removeNotActivatedUsers`'s per-user `catch` block (`:151-166`) adds a failed login to the
in-memory `failedLogins` set (cleared every run) and logs an ERROR, but nothing persists which users are
stuck. A deterministically-undeletable user (an uncovered FK, a trigger, a constraint) produces the
identical recurring ERROR log on every subsequent daily run with no operator-queryable record of what is
actually stuck versus merely slow.

**Four things the first draft of this AC got wrong or omitted — all four must be resolved before writing
the migration, not discovered mid-implementation:**

1. **`User` is Envers-audited; a bare column addition is not enough.** `User` carries `@Audited`
   (`User.java:44`), Envers is on the classpath (`pom.xml:370-372`, `hibernate-envers` 6.6.57.Final) and
   configured (`application.yaml:79-80`, `default_schema: main`), and `main.user_aud` already exists
   (`V138__baseline_schema.sql:1314`). `hibernate.ddl-auto` is `none`
   (`application.yaml:66`) — nothing creates a matching audit column automatically. Adding a new
   `@Column` to `User` with no corresponding `user_aud` column risks a missing-column SQL error the
   first time an audited `User` write actually persists a revision. **Decision for this story: mark the
   new field `@NotAudited`** — it is an operational marker, not user-facing auditable state, so it does
   not need revision history, and this sidesteps the question entirely without touching `user_aud` or
   its migration.
   **Worth recording, not fixing here:** `user_aud` already lacks `skillars_role` and
   `verification_status`, both declared on `User` with **no** `@NotAudited`
   (`V138:1314-1346` vs. `User.java:92-96`) — a pre-existing mismatch this story's investigation
   surfaced, not introduced. Something about this project's actual Envers runtime behavior does not
   match the naive "every `@Audited` field needs a `user_aud` column" reading, and it was not run down
   further here (out of scope). File it as a new `deferred-work.md` item during AC10's ledger update —
   see Task list.
2. New Flyway migration `V143__user_cleanup_failed_at.sql` adding a nullable
   `cleanup_failed_at timestamp without time zone` column to `main."user"` — **not** `timestamptz`.
   Every existing nullable timestamp column in that table (`activation_date`, `reset_expiration`,
   `account_expiration`, `created_date`, `last_modified_date` — `V138:1281-1296`) is
   `timestamp without time zone`; with `hibernate.jdbc.time_zone: UTC` and `Instant`-typed fields
   throughout, matching that existing convention is the lower-risk, consistent choice. The migration
   must also satisfy this repo's migration-lint (`src/test/resources/migration-lint/invalid/V912__missing_lock_timeout.sql`,
   `V919__lock_timeout_zero.sql` show a bare `ALTER TABLE` with no valid `SET lock_timeout` fails the
   lint outright) — mirror `V140__envelope_entity_recipients_delivered_flag.sql`'s exact shape: a
   rationale comment header, then `SET lock_timeout = '5s';`, then
   `ALTER TABLE main."user" ADD COLUMN IF NOT EXISTS cleanup_failed_at timestamp without time zone;`.
   (`V143` is confirmed the next free migration number as of story-creation time — re-confirm immediately
   before writing it, per the Dev Notes.)
3. **The naive "stamp it in the catch block" mechanism is a no-op — say so explicitly, do not leave this
   to a dev's first instinct.** `removeNotActivatedUsers` is
   `@Transactional(propagation = Propagation.NOT_SUPPORTED)` (`:132`) — there is no ambient persistence
   context, so the `User` objects `findExpiredUsers` returns are **detached** the moment that repository
   call's own transaction closes. A plain `user.setCleanupFailedAt(Instant.now())` on a detached entity
   dirty-checks nothing and persists nothing; this AC would ship green (compiles, no exception) and
   deliver an always-empty column. The catch-block fix **must** be an explicit
   `@Modifying @Query` update issued through `UserRepository` (which carries `SimpleJpaRepository`'s own
   per-call transaction), e.g. `UserRepository.markCleanupFailed(String login, Instant failedAt)`,
   called inside its own `try/catch` so a stamp failure cannot itself abort the sweep.
   **Positive property worth stating in the code, not just here, so nobody "fixes" it away later:**
   because the sweep is `NOT_SUPPORTED`, a failed delete never marks an ambient transaction
   rollback-only, so this stamp update *can* commit independently even when the delete it is recording
   failed — that property depends on `removeNotActivatedUsers` staying `NOT_SUPPORTED` and on AC8's fix
   keeping `deleteUserInTransaction` at `REQUIRES_NEW` (not `REQUIRED`); if either changes in a future
   story, re-verify this stamp still commits.
4. **Overlaps with AC6 — resolve the overlap, do not implement both mechanisms independently.** Per
   AC6's own note, `cleanup_failed_at` supersedes the *cross-run* half of the exclusion problem;
   `findExpiredUsers`'s query gains a `cleanup_failed_at IS NULL` predicate (a fixed-cost condition, not
   a growing bind list) alongside the paged query AC6 introduces, while the in-run `failedLogins`
   Java-side filter stays as the belt-and-braces for the (small) window before a stamp has actually
   committed.
5. This is deliberately a one-way marker for this story (no automatic retry/clear mechanism) — "give
   operators something to query" was the stated requirement; a stuck row is now findable via
   `SELECT * FROM main."user" WHERE cleanup_failed_at IS NOT NULL`, which operators can act on manually
   (fix the underlying FK/trigger/constraint issue, or clear the marker to let the sweep retry).

#### Tasks

- [ ] Write `V143__user_cleanup_failed_at.sql`: rationale header + `SET lock_timeout = '5s';` +
      `ALTER TABLE main."user" ADD COLUMN IF NOT EXISTS cleanup_failed_at timestamp without time zone;`
      (re-confirm `V143` is still free immediately before writing it)
- [ ] Add the `cleanupFailedAt` field to the `User` entity as `Instant`, annotated `@NotAudited`
      (`org.hibernate.envers.NotAudited`) and matching existing nullable-timestamp `@Column` conventions
      in that class
- [ ] Add a `@Modifying @Query` update method to `UserRepository` (e.g. `markCleanupFailed(String login,
      Instant failedAt)`) — do **not** rely on a setter call against the (detached) entities
      `findExpiredUsers` returns
- [ ] Call it from `removeNotActivatedUsers`'s catch block, in its own `try/catch` so a stamp failure
      cannot abort the sweep
- [ ] Add `cleanup_failed_at IS NULL` to the paged query from AC6 (fixed-cost predicate, not a
      server-side push of the whole `failedLogins` set — see AC6's note)
- [ ] Add/update `UserAdminServiceTest`/an IT proving: (a) a user whose deletion fails gets the marker
      genuinely persisted (query it back, do not just assert the setter was called), and (b) a subsequent
      call to `findExpiredUsers` (simulating the next scheduled run, in-memory `failedLogins` reset)
      excludes that user via the persisted marker
- [ ] File a new `deferred-work.md` item (during AC10) recording the pre-existing `user_aud`
      `skillars_role`/`verification_status` Envers-coverage gap discovered while investigating this AC —
      worth a future audit, out of scope to fix here

---

### AC10 — Ledger hygiene closeout

Nine bullets close by direct fix (delete outright); one closes by decision only (annotate, do not
delete); five remain correctly out of scope (leave untouched). This table is the authoritative
disposition — do not re-derive counts from prose elsewhere in this story:

| Ledger bullet (section) | Disposition | Closed by |
|---|---|---|
| `deleteStrike` has no path back to `REDUCED` (-121 review) | **Delete** | AC1 |
| Deleting an out-of-window strike still fires a full revert (-121 review) | **Delete** | AC1 |
| Concurrent duplicate `deleteStrike` surfaces `StaleStateException`, not 404 (-121 review) | **Delete** | AC2 |
| `getEnforcementProfile` composes an inconsistent view (-121 review) | **Delete** | AC3 |
| `visibilityThreshold > suspensionThreshold` accepted config (-121 review) | **Delete** | AC4 |
| `reinstateCoach` cannot distinguish stale vs. fresh suspension (-121 review) | **Annotate `[DECIDED]`, do not delete** | AC5 |
| `findExpiredUsers` unused `Pageable`/full-table load (-120 review) | **Delete** | AC6 |
| `MAX_BATCHES_PER_RUN` hardcoded vs. configurable batch size (-120 review) | **Delete** | AC7 |
| `deleteUserInTransaction`'s `REQUIRES_NEW` narrowed, not closed (-120 review) | **Delete** | AC8 |
| No cross-run record of undeletable users (-120 review) | **Delete** | AC9 |
| `issueManualStrike`'s unlocked `findById` / no coach-status guard (-121 review) | Leave — out of scope | — |
| Strike-DELETE unbounded wait outside retry/savepoint guarantee (-121 review) | Leave — out of scope | — |
| 30-day count-window origin slides with contention (-121 review) | Leave — out of scope | — |
| `resetStaleClaimed`/`claimPendingBatch` eligibility-vs-claim-time gap (-120 review) | Leave — owner decision, out of scope | — |
| `lockAtLeastFor` hardcoded vs. tunable cadences (-120 review) | Leave — out of scope | — |

**New bullet to add, not close:** the pre-existing `user_aud` `skillars_role`/`verification_status`
Envers-coverage gap discovered while investigating AC9 (see AC9's own note) — a genuine, newly-found
latent issue worth a future audit, not fixed by this story.

#### Tasks

- [ ] Re-run the grep sweep this story's Provenance section already ran (`AdminCoachEnforcementService`,
      `deleteStrike`, `getEnforcementProfile`, `reinstateCoach`, `visibilityThreshold`,
      `suspensionThreshold`, `UserAdminService`, `findExpiredUsers`, `MAX_BATCHES_PER_RUN`,
      `deleteUserInTransaction`) against HEAD immediately before marking this story done, confirming no
      bullet added by another story in the interim overlaps this one
- [ ] Delete the nine bullets marked **Delete** in the table above
- [ ] Annotate (not delete) the one bullet marked **Annotate** — AC5's `reinstateCoach` item, per AC5's
      own Task
- [ ] Confirm the five bullets marked **Leave** remain correctly present and untouched
- [ ] Add the new `user_aud` Envers-coverage-gap bullet per AC9's note

---

## Dev Notes

- **No local `mvn verify`** per project convention (`docs/validation-strategy.md`) — GitHub CI is the
  sole full-verification gate. Run targeted suites only.
- **Two independent problem domains, deliberately bundled per this project's "do not create small
  stories" convention** — Group A (`AdminCoachEnforcementService`, AC1-AC5) and Group B
  (`UserAdminService`, AC6-AC9) share no code and can be implemented in either order. Within each group,
  though, several ACs are **not** independent — do not treat the AC numbering as an implementation
  order:
  - **AC1 and AC4 are independent of each other, and AC1 must not rely on AC4 for correctness.** AC1's
    own tiering logic reads `suspensionThreshold` directly and is self-sufficient; AC4's cross-field
    validation (plus its own AC4 read-time clamp) is a separate, defense-in-depth safeguard against a
    misconfigured pair, not something AC1's logic is permitted to assume has already run.
  - **AC6 and AC9 both touch `findExpiredUsers`'s query** — implement AC6 first (real pagination, no
    server-side `failedLogins` push-down), then layer AC9's `cleanup_failed_at IS NULL` predicate onto
    the same paged query. See AC6's and AC9's own notes on why the two exclusion mechanisms must not be
    implemented independently of each other.
  - **AC9's stamping mechanism depends on understanding AC8's transaction-boundary analysis, not on
    implementing AC8 first.** Whether the per-user delete runs in a genuinely separate transaction
    determines whether AC9's catch-block stamp can commit independently of a failed delete — the answer
    is "yes, because `removeNotActivatedUsers` is `NOT_SUPPORTED`" (see AC9's own note), but that is a
    conclusion to verify while implementing AC9, not an assumption to skip.
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
  (AC8 — the exact `@Autowired @Lazy self` pattern to copy; note its target method is `public`, which is
  the part of the pattern that actually matters, not just the field)
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` (AC8 — confirm
  `@EnableTransactionManagement`'s default `PROXY` mode / `publicMethodsOnly` behavior before assuming a
  self-proxy field alone fixes anything on a non-public method)
- `src/main/java/com/softropic/skillars/platform/security/contract/SecurityProperties.java` (AC7 —
  confirm `userCleanupBatchSize` has no `@Min`/`@Validated` guard before writing the division)
- `src/main/resources/db/migration/V140__envelope_entity_recipients_delivered_flag.sql` (AC9 — the exact
  migration shape to mirror: rationale header, `SET lock_timeout`, `ADD COLUMN IF NOT EXISTS`)
- `src/test/resources/migration-lint/invalid/V912__missing_lock_timeout.sql` and
  `V919__lock_timeout_zero.sql` (AC9 — confirm what the migration-lint actually rejects before assuming
  a bare `ALTER TABLE` is enough)
- `src/main/resources/db/migration/V138__baseline_schema.sql:1258-1290` (`main."user"`'s column
  definitions — timestamp column type convention) and `:1311-1345` (`main.user_aud`'s columns, for the
  Envers-coverage check) (AC9)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (AC9 — confirm `@Audited`,
  and the existing `skillars_role`/`verification_status` fields' lack of `@NotAudited` before deciding
  how to annotate the new field)
- `src/main/resources/db/migration/` (AC9 — confirm `V143` is still the next free migration number
  immediately before writing it)
- `_bmad-output/implementation-artifacts/deferred-work.md` — the two source sections named in Provenance,
  and the `ConfigBounds.java`-referenced historical rationale

---

## Verification Checklist

- [ ] AC1: `deleteStrike`'s tiering checks `suspensionThreshold` **before** `visibilityThreshold` (a
      coach whose fresh count is still `>= suspensionThreshold` stays `PENDING_REVIEW`, does **not**
      become `REDUCED`); `REDUCED` fires only for `PENDING_REVIEW` coaches whose fresh count is in
      `[visibilityThreshold, suspensionThreshold)`; out-of-window strike deletions produce no
      status/alert change but still write the existing `COACH_STRIKE_DELETED` log row (not zero rows);
      `ManualStrikeIT`'s existing test updated to the corrected expectation; the redesigned
      `AdminCoachEnforcementConcurrencyIT` `deleteStrike` test seeds a fresh count `>= suspensionThreshold`
      so "no change" is still the correct outcome under the new tiering
- [ ] AC2: concurrent-duplicate `deleteStrike` semantics verified deterministically (0-affected-row →
      `ResourceNotFoundException`/404), not merely "no longer throws `StaleStateException`" by absence;
      `strike.getCreatedAt()` captured to a local variable before the bulk delete, not read from the
      entity afterward
- [ ] AC3: both `getEnforcementProfile` and `getCoachesUnderEnforcement` run at `REPEATABLE_READ`; the
      test drives the call through HTTP (or otherwise outside any enclosing transaction) so the isolation
      request is not silently dropped; if the annotation-presence fallback was used instead of a true
      snapshot-consistency test, it is marked explicitly as "unverified behavior, annotation only"
- [ ] AC4: `ConfigStartupAssertion` fails fast (non-dev) when `visibilityThreshold > suspensionThreshold`;
      logs-only in dev; boundary (`visibility == suspension`) does not flag; **and** `deleteStrike` itself
      clamps `visibilityThreshold` to `suspensionThreshold` at read time, so a live runtime
      misconfiguration (not just a bad value already stored at boot) cannot produce a wrongful revert
- [ ] AC5: `reinstateCoach` comment and `deferred-work.md` bullet both annotated `[DECIDED]`; zero
      production behavior change
- [ ] AC6: `findExpiredUsers` proven to query with real server-side pagination (not a post-hoc Java
      `.limit()`); the in-run `failedLogins` filter is applied only over the returned page, not pushed
      server-side as a growing `NOT IN` list
- [ ] AC7: lowering the configured batch size no longer reduces the total per-run delete-attempt ceiling;
      a `0`-configured batch size does not throw `ArithmeticException` computing the batch-count ceiling
- [ ] AC8: `deleteUserInTransaction` is `public` **and** called through the `self`-proxy — both changes
      present, not just one; `REQUIRES_NEW` genuinely applies (test proves the transaction boundary
      itself, not just the call-site change); the Checklist and code Javadoc say the residual TOCTOU
      window is **narrowed**, not closed
- [ ] AC9: `V143` migration (with the required `SET lock_timeout` + `ADD COLUMN IF NOT EXISTS` +
      `timestamp without time zone` column type) applied; the new `User` field is `@NotAudited`; the
      catch-block stamp is a genuine `@Modifying @Query` update (verified by querying the column back,
      not by asserting a setter was called); a user whose deletion fails is excluded from every
      subsequent run's candidate set via the persisted marker, not just the run it first failed in
- [ ] AC10: ledger sweep re-run against HEAD; nine bullets deleted, one annotated `[DECIDED]`, five
      confirmed still correctly out of scope, one new bullet added (the `user_aud` Envers-coverage gap)
- [ ] No regressions in `ManualStrikeIT`, `AdminCoachEnforcementConcurrencyIT`, `ReinstateIT`,
      `CoachSuspensionIT`, `CoachEnforcementListIT`, `UserAdminServiceTest`, `ConfigStartupAssertionTest`,
      or any other test touching `AdminCoachEnforcementService`, `UserAdminService`, or
      `ConfigStartupAssertion`

---

## File List

_To be filled in during implementation._

---

## Change Log

- 2026-09-18: Story-review response applied (`story-review.md`, a senior-dev pre-implementation audit).
  Independently re-verified all 18 findings against actual source before applying anything (per explicit
  instruction to watch for false positives) — **zero false positives; all 18 confirmed genuine and
  fixed.** Four were blockers that would have shipped materially wrong or non-functional behavior:
  (1) AC1's prescribed tiering checked `visibilityThreshold` alone and could de-escalate a coach still
  above `suspensionThreshold` — corrected to check `suspensionThreshold` first, added the missing test
  case, and added the missing `suspensionThreshold` read `deleteStrike` never had; (2) AC1's Fix text and
  its own Task list contradicted each other on whether the out-of-window guard suppresses the
  `admin_action_log` row it does not (every `deleteStrike` call writes one on both branches today) —
  corrected the Task; (3) AC1's claim that `AdminCoachEnforcementConcurrencyIT`'s existing `deleteStrike`
  test was unaffected was false — traced its actual seeded counts (fresh count lands at 4, squarely
  inside the new `REDUCED` band under either the buggy or corrected tiering) and turned the test's
  redesign into a first-class AC1 task, not a trailing sanity check; (4) AC8's self-proxy fix alone does
  nothing — `deleteUserInTransaction` is `protected`, and Spring's default `PROXY`-mode
  `AnnotationTransactionAttributeSource` silently ignores `@Transactional` on any non-public method
  regardless of proxy invocation; the precedent story cited (`VideoSubscriptionLifecycleListener`) works
  because its method is `public`, a detail the story copied the field for but not the visibility for —
  AC8 now requires both. Four Major findings on AC9 also required correction before any code should have
  been written: `User` is Envers-`@Audited` with a real `user_aud` table this AC's new field would have
  needed to reckon with (resolved via `@NotAudited`, sidestepping a genuinely ambiguous pre-existing
  Envers-coverage gap this investigation surfaced and filed as a new deferred-work.md item rather than
  silently fixing or ignoring); the described catch-block "stamp" was a no-op against a detached entity
  under `NOT_SUPPORTED` propagation (now an explicit `@Modifying @Query` update); the column type
  contradicted both the table's own convention and the AC's own instruction to mirror it (`timestamptz`
  → `timestamp without time zone`); and the migration omitted the `SET lock_timeout` this repo's
  migration-lint requires (confirmed against the lint's own invalid-fixture files) — added, mirroring
  `V140`'s exact precedent shape. One Major finding on AC4 (a boot-only check does not close a
  runtime-mutable config gap — `ConfigService` refreshes its cache every ~5 minutes with no restart) was
  resolved by adding a `Math.min` read-time clamp in `deleteStrike` alongside the boot check, made nearly
  free by AC1's own new `suspensionThreshold` read. Moderate/Minor findings resolved: AC7's unguarded
  division by an unvalidated `0` batch-size config value (clamped); AC6's originally-proposed
  server-side `NOT IN` push-down of an unboundedly-growing `failedLogins` set (redesigned to keep that
  filter in-run/page-scoped only, letting AC9's persisted marker carry the cross-run exclusion instead of
  two overlapping mechanisms); AC1's two separate `now()` calls for the same nominal 30-day window
  (hoisted to one `cutoff`, matched to the repository's own strict `>` comparison); AC1's out-of-window
  guard removing the system's only (accidental) de-escalation-on-ageout path (kept as the correct fix,
  now explicitly documented as a real, intentional behavior change rather than a silent side effect);
  AC2's bulk JPQL delete leaving the already-loaded `strike` entity managed-but-stale (documented as
  safe today, with an explicit instruction that AC1's `createdAt` capture must go into a local variable,
  not a later read of that entity); AC3's isolation level being silently dropped inside any ambient
  transaction per Spring's `validateExistingTransaction=false` default (added explicit test-authoring
  guidance: drive the test through HTTP, not a wrapping `@Transactional`/`TransactionTemplate`); AC3's
  sibling `getCoachesUnderEnforcement` sharing the identical torn-read shape and not originally covered
  (folded into AC3's scope — fixing one and not the other would have closed only half the underlying
  ledger item); AC10's bullet-count arithmetic not reconciling with its own prose, and its "annotate two
  bullets" instruction immediately contradicting itself by also saying to delete one of those two
  (rewritten as an explicit bullet → disposition table: nine deletions, one annotation, five left alone,
  one new bullet added); and the Dev Notes' "no cross-AC dependency except AC6/AC9" claim, which missed
  that AC1 must not lean on AC4 for correctness and that AC9's stamping mechanism depends on
  understanding (not necessarily sequencing after) AC8's transaction-boundary analysis. No ACs were
  removed or added by this pass — all four Group A tables/counts and Group B's four findings stand;
  only the prescribed fixes and task lists changed.
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
