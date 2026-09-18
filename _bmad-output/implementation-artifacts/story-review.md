# Senior-Dev Story Audit — `skillars-deferred-122-coach-enforcement-round-2-and-user-cleanup-fixes`

**Reviewed:** 2026-09-18
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-122-coach-enforcement-round-2-and-user-cleanup-fixes.md`
**Baseline:** working tree on `story/deferred-122-...`, source files identical to `master@aa920c49`
**Method:** every claim in the story was re-checked against the actual source. Findings below are only
those I could reproduce from code I read; a "Verified accurate" section at the end lists the story
claims I checked and found **correct**, so the absence of a finding is a deliberate result, not a gap
in coverage.

**Verdict:** the story is well-researched and its *diagnoses* are almost all correct. The problems are
in the *prescriptions*: four of them are wrong or incomplete in ways that will either produce
incorrect behavior, silently not work at all, or break CI. **Do not start AC1 or AC8 as written.**

| # | AC | Severity | Summary |
|---|----|----------|---------|
| 1 | AC1 | **Blocker** | Prescribed tiering is not a mirror of `issue()`; de-escalates coaches still above `suspensionThreshold` |
| 2 | AC1 | **Blocker** | Fix step 2 and Task 3 contradict each other on the `admin_action_log` row |
| 3 | AC1 | **Blocker** | Stated assumption about `AdminCoachEnforcementConcurrencyIT` is factually false — that test will go red |
| 4 | AC8 | **Blocker** | Prescribed fix does not work: `@Transactional` is ignored on non-public methods under proxy AOP |
| 5 | AC9 | Major | `User` is `@Audited`; migration/entity change omits `user_aud` entirely |
| 6 | AC9 | Major | The catch-block stamp as described is a no-op — the `User` is detached |
| 7 | AC9 | Major | `timestamptz` contradicts the table's own convention and the story's own instruction |
| 8 | AC9 | Major | Migration as specified will fail the repo's migration-lint suite |
| 9 | AC4 | Major | A boot-only check does not close a runtime-mutable config gap; the AC claims it does |
| 10 | AC7 | Moderate | Divide-by-zero on an unvalidated config property |
| 11 | AC6 | Moderate | Pushing `excludeLogins` server-side creates an unbounded `NOT IN` list |
| 12 | AC1 | Moderate | 30-day window computed from two separate `now()` calls |
| 13 | AC1 | Moderate | Out-of-window guard silently removes the system's only de-escalation-on-ageout path |
| 14 | AC2 | Moderate | Bulk JPQL delete leaves the already-loaded strike managed |
| 15 | AC3 | Minor | Isolation is silently dropped inside an ambient transaction — directly affects AC3's own test |
| 16 | AC3 | Minor | Sibling reader `getCoachesUnderEnforcement` has the identical torn-read shape and is not covered |
| 17 | AC10 | Minor | Bullet arithmetic and instructions contradict themselves |
| 18 | Dev Notes | Minor | "No cross-AC dependency except AC6/AC9" is wrong |

---

## Blockers

### 1. AC1 — The prescribed three-tier logic is *not* a mirror of `issue()`; it de-escalates coaches who should stay `PENDING_REVIEW`

**Evidence.** `ReliabilityStrikeService.issue`'s escalation (`ReliabilityStrikeService.java:93-110`):

```java
if (count >= suspensionThreshold)      { ... PENDING_REVIEW ... }
else if (count >= visibilityThreshold) { ... REDUCED ... }
```

The top tier is gated on `suspensionThreshold`. AC1's prescribed de-escalation (Fix step 3) is gated
only on `visibilityThreshold`:

> `count >= visibilityThreshold` (and, transitively, `< suspensionThreshold` once AC4's config
> validation is in place) and current status is `PENDING_REVIEW` → **new:** `REDUCED`

The parenthetical is a non sequitur. AC4 enforces `visibilityThreshold <= suspensionThreshold`. That
says nothing about where `count` falls. `count >= visibilityThreshold` does **not** imply
`count < suspensionThreshold`.

**Concrete failure.** Seeded defaults are `suspensionThreshold=5`, `visibilityThreshold=3`
(`V139__baseline_seed_data.sql:137-138`). A coach at `PENDING_REVIEW` with 7 in-window strikes; an
admin deletes one → `count = 6`. AC1's rule fires (`6 >= 3`, status `PENDING_REVIEW`) and writes
`REDUCED`. But `issue()` would have written `PENDING_REVIEW` for a count of 6. The coach is
de-escalated out of admin review while still two strikes past the suspension bar — the exact class of
wrong-status-write this story exists to fix, newly introduced by its own fix.

**Also note** `deleteStrike` currently reads *only* `visibilityThreshold`
(`AdminCoachEnforcementService.java:254-255`). It has no `suspensionThreshold` read at all, and AC1
never tells the dev to add one.

**Required change to AC1.** Add the `suspensionThreshold` read and make the tiering a true reverse
mirror, top tier first:

```java
if (count >= suspensionThreshold)       -> no change (stay PENDING_REVIEW)
else if (count >= visibilityThreshold)  -> REDUCED (from PENDING_REVIEW; no-op if already REDUCED)
else                                    -> ACTIVE (existing behaviour)
```

and add a test for the `PENDING_REVIEW` + `count still >= suspensionThreshold` case, which the current
task list does not cover.

---

### 2. AC1 — Fix step 2 and Task 3 contradict each other on the audit-log row

Fix step 2 routes an out-of-window deletion to "the existing 'no status change' `else` branch". That
branch **writes an action-log row** (`AdminCoachEnforcementService.java:275-278`):

```java
} else {
    actionLog.setActionType(AdminActionType.COACH_STRIKE_DELETED);
    actionLog.setReason("Strike deleted (no status change): " + reason);
```

Task 3 asserts the opposite:

> assert **no** status change, **no** alert resolution, **no** new `admin_action_log` row — the
> deletion must be a pure no-op on coach state

The test as written will fail against the implementation as written. Beyond the contradiction,
suppressing the row is the wrong call: an admin destroying a strike record with no audit-log entry is
an audit regression, and `AdminCoachEnforcementConcurrencyIT:367-372` already asserts exactly one
`COACH_STRIKE_DELETED` row on the no-revert path.

**Resolution:** keep the log row, fix Task 3's wording to "no status change, no alert resolution, and
a `COACH_STRIKE_DELETED` (not `COACH_REINSTATE`) action-log row".

---

### 3. AC1 — The stated assumption about `AdminCoachEnforcementConcurrencyIT` is false; that test will break

AC1's last task says:

> the concurrency IT's `deleteStrike` tests assert on the `ACTIVE` path specifically … (they seed
> counts that land below `visibilityThreshold`, not in the new `REDUCED` band …)

That is not what the test seeds. `deleteStrike_concurrentStrikesPushCountAboveThreshold_doesNotRevertOnStaleCount`
(`AdminCoachEnforcementConcurrencyIT.java:300-372`):

- coach seeded `PENDING_REVIEW` (`:105`)
- `visibilityThreshold - 1` = **2** strikes, plus the target strike = 3 (`:308-312`)
- holder thread inserts **2** more while holding the lock (`:328-329`) → 5
- target strike deleted → **`count = 4`**

With `visibilityThreshold=3`, `suspensionThreshold=5`, a count of 4 lands **squarely inside the new
`REDUCED` band**. Post-AC1 the coach becomes `REDUCED`; the test asserts `PENDING_REVIEW` (`:357-361`)
and goes red. If Fix step 5's "new enum constant" option is taken, the `COACH_STRIKE_DELETED` count
assertion (`:367-372`) goes red too.

Worse, the test's *premise* is invalidated, not just its expectation: it proves "the fresh post-lock
count was used" by observing *no status change*. Under AC1 there is always a status change in that
scenario, so flipping the expectation to `REDUCED` would no longer distinguish a fresh count from a
stale one. The test needs a redesign — e.g. have the holder insert enough strikes that the fresh count
lands `>= suspensionThreshold`, so "no change" remains the correct fresh-count outcome.

This must be a first-class task in AC1, not a sanity check appended to the end.

---

### 4. AC8 — The prescribed fix does not work; `@Transactional` is ignored on non-public methods

`deleteUserInTransaction` is **`protected`** (`UserAdminService.java:239`). `DataSourceConfig.java:20`
uses `@EnableTransactionManagement` in the default `PROXY` mode, which builds an
`AnnotationTransactionAttributeSource` with `publicMethodsOnly = true`.
`AbstractFallbackTransactionAttributeSource.computeTransactionAttribute` returns `null` for any
non-public method under that setting, so no `TransactionInterceptor` advice is ever applied —
**regardless of whether the call arrives through the proxy**. Adding an `@Autowired @Lazy self` field
changes nothing on its own.

The precedent the story tells the dev to copy "exactly" —
`VideoSubscriptionLifecycleListener.processAndSaveEntry` (`:120`) — is **`public`**. The story copied
the field declaration but not the method visibility, which is the part that actually makes the pattern
work.

**Required change to AC8:** make `deleteUserInTransaction` `public` *and* route the call through
`self`. Both, or the AC delivers nothing.

Two knock-ons:

- `findExpiredUsers` is also `protected` with `@Transactional(readOnly = true)` (`:198-199`) — equally
  inert today. Relevant to AC6/AC9: entities it returns are detached (see finding 6). Decide
  explicitly whether to make it public too, or drop its misleading annotation.
- AC8's verification bar ("test proves the transaction boundary, not just the call-site change") is
  correct and should be treated as mandatory here: a `verify(self).deleteUserInTransaction(...)` test
  would pass green on a fix that still does nothing.

Separately, note that even with `REQUIRES_NEW` genuinely applied, the read-then-delete inside
`deleteUserInTransaction` is a `SELECT` followed by a `DELETE` at READ COMMITTED with no row lock — the
TOCTOU window narrows sharply but does not close. AC8's Verification Checklist line should say
"narrows", not imply closure; the closing fix would be a conditional delete
(`DELETE ... WHERE login = ? AND activated = false`) or a locked re-read.

---

## Major

### 5. AC9 — `User` is Envers-audited; the migration and entity change omit `user_aud`

`User` carries `@Audited` (`User.java:44`), `AbstractAuditingEntity` too (`:34`), Envers is on the
classpath (`pom.xml:370-372`, 6.6.57.Final) and configured (`application.yaml:79-80`), and
`main.user_aud` exists (`V138__baseline_schema.sql:1314`). `hibernate.ddl-auto: none`, so nothing
creates the audit column for you.

AC9's task list mentions only `main."user"`. Adding an audited field without a matching `user_aud`
column is the classic way to break every `User` write at runtime with a missing-column SQL error.

**Caveat worth resolving first, not a reason to skip this:** `user_aud` already lacks `skillars_role`
and `verification_status`, which are declared on `User` with no `@NotAudited`
(`V138:1314-1346` vs `User.java:92-96`). Something about this project's actual Envers behavior does
not match the naive reading, and that should be established before writing the migration. Either way
AC9 must make an explicit decision — add the `user_aud` column, or annotate the new field
`@NotAudited` — and it currently makes none.

### 6. AC9 — The catch-block stamp as described is a no-op

`removeNotActivatedUsers` is `@Transactional(propagation = Propagation.NOT_SUPPORTED)` (`:132`). There
is no ambient persistence context, so the `User` objects returned by `findExpiredUsers` are **detached**
the moment the repository call's own transaction closes. AC9 step 2 says "stamp
`cleanup_failed_at = now()` on that user row" without naming a mechanism; the natural JPA reflex —
`user.setCleanupFailedAt(Instant.now())` — dirty-checks nothing and silently does nothing. The whole AC
would ship green and deliver an always-empty column.

**Required:** an explicit `@Modifying @Query` update on `UserRepository` (which carries
`SimpleJpaRepository`'s own transaction), called inside its own `try/catch` so a stamp failure cannot
abort the sweep. Spell this out in the AC.

Positive note, worth stating in the story so nobody "fixes" it later: because the sweep is
`NOT_SUPPORTED`, a failed delete never marks an ambient transaction rollback-only, so the stamp *can*
commit. That property is load-bearing for AC9 and depends on AC8 keeping `REQUIRES_NEW` (not
`REQUIRED`).

### 7. AC9 — `timestamptz` contradicts the table's own convention and the story's own instruction

AC9 step 1 prescribes `cleanup_failed_at timestamptz` while in the same sentence saying to "mirror this
project's existing nullable timestamp column conventions in that table, e.g.
`activation_date`/`reset_expiration`". Those columns — and `account_expiration`, `created_date`,
`last_modified_date` — are all `timestamp without time zone` (`V138:1281-1296`). Pick one. Given
`hibernate.jdbc.time_zone: UTC` and `Instant`-typed fields, matching the existing
`timestamp without time zone` is the lower-risk choice.

### 8. AC9 — The migration as specified will fail the repo's migration-lint suite

`src/test/resources/migration-lint/invalid/V912__missing_lock_timeout.sql` and
`V919__lock_timeout_zero.sql` show the lint rejects an `ALTER TABLE` migration without a valid
`SET lock_timeout`. The story's own nearest precedent, `V140__envelope_entity_recipients_delivered_flag.sql`,
opens with a rationale header and `SET lock_timeout = '5s';`. AC9's task says only "adding the nullable
column". Add the header + `SET lock_timeout` + `ADD COLUMN IF NOT EXISTS` to the task, or CI fails on a
detail nobody will think to look for.

(`V143` is confirmed the next free number as of now — V142 is HEAD.)

### 9. AC4 — A boot-only check does not close a runtime-mutable config gap

`ConfigService` holds a `ConcurrentHashMap` cache refreshed by
`@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")` (`ConfigService.java:38,56`). An
operator `UPDATE`ing `platform_config` takes effect on a running system within ~5 minutes, no restart.
`ConfigStartupAssertion` fires once, on `ApplicationReadyEvent` (`:64`).

So AC4 catches exactly one path — "boot with a bad pair already stored" — and leaves untouched the path
that actually produces this bug in practice: an operator tuning a threshold live.
`ConfigStartupAssertion`'s own Javadoc already concedes the analogous timing hole (`:47-51`).

This is an owner decision and I am not relitigating the choice of venue. But AC4's text claims it
"close[s] this", and AC10 will delete the ledger bullet on that basis. Either:

- **(a)** add a two-line read-time guard in `deleteStrike` alongside the boot check —
  `visibilityThreshold = Math.min(visibilityThreshold, suspensionThreshold)` — which you need the
  `suspensionThreshold` read for anyway once finding 1 is fixed; or
- **(b)** keep AC4 as-is but state plainly in the AC and in the `deferred-work.md` annotation that the
  runtime-mutation path is knowingly left open.

(a) is essentially free given finding 1. Silently deleting the bullet is the option to avoid.

Two smaller AC4 notes, both fine as written but worth pinning down:
- `configService.getBoundedLong(key, default, min, max)` never throws (`:108-117`) — absent, blank and
  non-numeric all fall back to the default. The sketch is safe.
- The existing `ConfigStartupAssertionTest` mocks `configService`, so unstubbed `getBoundedLong` calls
  return `0L` for both keys; `0 > 0` is false, so no existing test spuriously trips. Good.
- Keep the metric's tag keys as `{key, reason}` exactly — `ConfigStartupAssertion:134-140` documents
  that `PrometheusMeterRegistry` rejects a second registration of `config.value.misconfigured` under a
  different tag-key set.

---

## Moderate

### 10. AC7 — Divide-by-zero on an unvalidated config property

`SecurityProperties.userCleanupBatchSize` is a bare `int` with no `@Min`/`@Validated`
(`SecurityProperties.java:32`, class has only `@ConfigurationProperties` + `@Data`). AC7's
`MAX_DELETE_ATTEMPTS_PER_RUN / batchSize` throws `ArithmeticException` at `app.security.user-cleanup-batch-size: 0`.
(`PageRequest.of(0, 0)` already throws today, so this failure mode is pre-existing in kind — but AC7
should not add a second one.)

Clamp once and use it everywhere:

```java
int effectiveBatch = Math.max(1, batchSize);
int maxBatches = Math.max(1, MAX_DELETE_ATTEMPTS_PER_RUN / effectiveBatch);
```

Also: `batchSize > 10_000` yields `maxBatches == 1` while that single batch already exceeds the
10,000-attempt ceiling the cap exists to enforce. Clamp the batch size upward too, or document that
the ceiling is best-effort above that size.

### 11. AC6 — Pushing `excludeLogins` into the query creates an unbounded `NOT IN` list

`failedLogins` accumulates across the whole run and can reach `batchSize × maxBatches` (10,000) entries.
Every `findExpiredUsers` call would then render a differently-sized `IN` list — large bind lists plus
Hibernate query-plan-cache churn from the varying arity. Today the set costs nothing (Java-side filter),
so this is a new cost that AC6 introduces while claiming to be a pure efficiency win.

Note also that AC9's `cleanup_failed_at` exclusion largely supersedes the need for a server-side
`failedLogins` filter. Cleanest shape: let the persisted marker do the cross-call exclusion, and keep
`failedLogins` as the in-run, Java-side belt-and-braces for the case where the stamp itself failed.
Make that an explicit decision in AC6 rather than leaving both mechanisms fighting for the same job.

Separately, AC6's empty-collection worry is worth keeping but is likely moot under the above shape.
Hibernate 6 renders an empty `IN ()` safely, but the `excludeLogins.isEmpty()` overload branch the
story already suggests is cheap insurance.

**Index coverage:** there is no index on `main."user"(activated, created_date)` — only `user_pkey` and
`user_login_key` (`V138:2716-2734`). `ORDER BY id ASC … LIMIT n` will walk the PK index and filter,
stopping after `n` matches. That is still a large win over materializing the full expired set, but if
AC6 is being sold as a performance fix, either measure it or add a partial index
(`WHERE activated = false`) as part of the same migration AC9 already introduces.

### 12. AC1 — The 30-day window is computed from two separate `now()` calls

AC1 Fix step 2 compares the deleted strike's `createdAt` against `now().minusDays(30)`; `count` is
computed from its own `OffsetDateTime.now().minusDays(30)` at `:253`. A strike sitting on the boundary
can be judged out-of-window by the guard while still being included in `count`, or vice versa. Hoist a
single `OffsetDateTime cutoff` local and use it for both. `countByCoachIdAndCreatedAtAfter` uses strict
`>` (`CoachReliabilityStrikeRepository.java:16`), so match that comparison exactly in the guard.

### 13. AC1 — The out-of-window guard removes the system's only de-escalation-on-ageout path

Nothing in the codebase re-evaluates a coach's status when strikes age out of the 30-day window:
`ReliabilityStrikeService.issue` runs only on a *new* strike, and `deleteStrike` is the only other
writer of that status pair. Today, deleting any stale strike accidentally triggers a re-evaluation and
can clear a coach whose strikes have all aged out. After AC1, that accidental self-heal is gone and
`reinstateCoach` becomes the only remedy.

That is probably the right design call — but it is a real behavior change for admins and should be an
explicit, documented consequence in the AC (and a line for whoever maintains the admin runbook), not a
silent side effect of a bug fix.

### 14. AC2 — The bulk JPQL delete leaves the already-loaded strike managed

`findById` at `:238` puts the entity in the persistence context; a `@Modifying` JPQL delete bypasses it,
so the managed instance survives the row. Nothing in `deleteStrike` re-reads it today, so the fix is
safe **as currently shaped** — but AC1 is simultaneously editing this method, and "capture `createdAt`
before deleting" (AC1 Fix step 1) must be into a **local variable**, not a later read of the entity.
State the constraint in the AC so a future edit does not accidentally flush a resurrected row. Do not
reach for `clearAutomatically = true` as a blanket fix — it would detach everything mid-method for no
benefit here.

---

## Minor

### 15. AC3 — The isolation level is silently dropped inside an ambient transaction

Production is fine: the only caller is `AdminCoachEnforcementResource.java:47`, with no enclosing
transaction. But Spring's `validateExistingTransaction` defaults to `false`, so a *participating* call
silently keeps `READ_COMMITTED` instead of failing loudly.

This matters directly for AC3's own test. If the test invokes `getEnforcementProfile` from an
`@Transactional` test method or inside a `TransactionTemplate`, it will be exercising `READ_COMMITTED`
and its result will mean nothing. Drive it through HTTP (as `CoachEnforcementListIT` does) or via a
plainly non-transactional call.

Also, AC3's accepted fallback — "assert the annotation is present" — verifies nothing about behavior.
If that path is taken, the Verification Checklist should record it as *unverified behavior, annotation
only*, not as "AC3 verified".

### 16. AC3 — The sibling reader with the identical defect is not covered

`getCoachesUnderEnforcement` (`:284-313`) reads `coach.getStatus()` (via `:298`) and the strike counts
(`:304`) as two separate statements under plain `@Transactional(readOnly = true)` — the exact torn-read
shape AC3 describes, feeding the exact same admin decision from the list view instead of the profile
view. Either include it in AC3 or state why it is excluded; otherwise AC10 deletes a ledger bullet that
is only half closed.

### 17. AC10 — The bullet arithmetic and the instructions contradict themselves

- "Delete the four closed bullets from … `skillars-deferred-121` (AC1's two, AC2's, AC3's)" names
  **three** ACs covering four bullets, then adds four more from `-120`, then concludes "seven bullets
  total". The numbers do not reconcile.
- The next line says "Annotate (not delete) the **two** decided-not-fixed bullets: AC4's … (closed by a
  real fix — **delete it**, it is not merely decided) and AC5's". It instructs annotating two items and
  then immediately says one of them should be deleted. Only AC5 is decided-not-fixed.

Rewrite AC10 as an explicit bullet → action table. As written, a dev will either miscount or annotate
the wrong item.

### 18. Dev Notes — "No cross-AC dependency except AC6 and AC9" is wrong

Two more couplings exist and both affect ordering:

- **AC9 depends on AC8's analysis.** Whether the per-user delete runs in its own transaction determines
  whether the catch-block stamp can commit (see finding 6). The answer happens to be "yes, because the
  sweep is `NOT_SUPPORTED`" — but that is a conclusion the dev has to reach, not an absence of
  dependency.
- **AC9 largely supersedes AC6's `excludeLogins` push-down** (see finding 11). Implementing AC6's
  server-side `NOT IN` first and then layering AC9 on top produces two overlapping exclusion mechanisms.

Also, once finding 1 is fixed, AC1 needs a `suspensionThreshold` read — which is independent of AC4, but
the story's current text implies AC1 is relying on AC4 for correctness. It must not.

---

## Verified accurate — checked, no action needed

Listed so the absence of a finding is legible as a result.

- **`CoachReliabilityStrike` has no `@Version`, no `@Audited`, no cascades** (`CoachReliabilityStrike.java:16-40`).
  AC2's `StaleStateException` diagnosis and its "this is not optimistic locking, it is Hibernate's
  unconditional post-delete row-count check" caveat are both correct, and the bulk-delete fix carries no
  hidden audit/cascade loss.
- **`ManualStrikeIT.deleteStrike_noStatusChange_doesNotResolveAlert`** (`:167-204`) does seed 5 strikes,
  delete 1, and assert `PENDING_REVIEW` + alert `OPEN`. The story's characterization is exact, and its
  warning that "still green" proves nothing here is the right call-out.
- **`deleteStrike_thatWasFinalStrike_revertsStatusToActiveAndResolvesAlert`** (`:127-165`) seeds 3 and
  deletes 1 → count 2 < visibility 3 → unchanged `ACTIVE` path. Genuinely unaffected by AC1.
- **`deleteStrike_prolongedContentionOnCoachRow_…`** (`:215-278`) asserts the strike row survives
  rollback. AC2's bulk delete still executes inside the same transaction, so rollback still restores it.
  Unaffected.
- **AC6's premise is correct.** `findExpiredUsers` builds `PageRequest.of(0, batchSize)` at `:200` and
  never passes it — `UserRepository:27`'s `findAllByActivatedIsFalseAndCreatedDateBefore` takes no
  `Pageable`. The Javadoc's "Uses pagination to limit fetched amount" (`:89`) is false, exactly as stated.
- **AC7's premise is correct.** `MAX_BATCHES_PER_RUN = 100` (`:49`) multiplies a configurable batch size
  (`:138`) with no enforced relationship. The 10,000-attempt derivation in the Javadoc (`:95-108`) is
  what the fix should preserve.
- **AC4's supporting facts check out:** `ConfigBounds`'s "add them if/when" invitation, the
  `{key, reason}` Prometheus tag constraint (`ConfigStartupAssertion:134-140`), the dev/non-dev
  `failFastViolations` branching (`:122-131`), and `getBoundedLong`'s non-throwing contract
  (`ConfigService:108-117`).
- **Line numbers.** Every `:NNN` citation in the story I spot-checked matches HEAD. The Provenance
  section's warning about stale ledger citations was warranted and was acted on correctly.
- **`V143` is the next free migration number.**
- **`reinstateCoach`'s `SUSPENDED`-is-a-legal-source behavior (AC5)** is exactly as described
  (`:172-179`), and the documentation-only closure is a reasonable call. The only nit: that comment
  block is already 18 lines (`:151-168`); prefer consolidating into one sentence over appending a third
  annotation layer.

---

## Recommended sequencing

1. **Fix the story before coding.** Findings 1, 2, 3, 4 change what the code should be, not just how it
   is described. Amend AC1 and AC8 first.
2. **Group B before Group A.** AC8 (now: make public + `self`) → AC6 → AC9, deciding the
   `excludeLogins` vs `cleanup_failed_at` overlap up front (finding 11) and resolving the Envers
   question (finding 5) before writing the migration.
3. **Group A.** AC1 with the corrected tiering and the `AdminCoachEnforcementConcurrencyIT` redesign as
   a named task; then AC2, AC3 (+ decide on finding 16), AC4 (+ decide on finding 9), AC5.
4. **AC10 last**, rewritten as an explicit bullet → action table.
