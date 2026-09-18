# Senior-Dev Audit — `skillars-deferred-123-strike-timing-scheduler-lock-config-and-envers-audit-gap-fixes`

**Reviewed:** 2026-09-18
**Story revision reviewed:** as committed in `550125b7`
**Codebase verified against:** working tree at `550125b7` (story-creation commit; `master@6ce2827c` + story file)
**Method:** every factual claim, line citation, table row and prescribed fix in the story was checked
against source. ShedLock behaviour was verified by decompiling the resolved artifact
(`~/.m2/.../shedlock-spring-7.10.1.jar`), not from documentation or memory. Findings below are only
those I could reproduce from the code; each carries its evidence so it can be re-checked cheaply.

**Verdict: do not start AC3, AC4 or AC6 as written.** AC4 rests on a premise that is verifiably
false, AC3 does not close the hole it is written to close, and AC6's test does not exercise the risk
it cites. AC1, AC2, AC5, AC7 and AC8 are directionally sound with the corrections noted.

---

## Summary table

| # | AC | Severity | Finding |
|---|---|---|---|
| B1 | AC4 | **Blocker** | "ShedLock does not resolve `${...}` in `lockAtLeastFor`" is false — it does. AC4's entire design rests on it. |
| B2 | AC3 | **Blocker** | `findClaimedBatch()` is unscoped, so `claimed_at` alone does not close the double-processing path. Task 6 would replace a correct warning with a false one. |
| H1 | AC3 | High | Prescribed `timestamp without time zone` contradicts both target tables' actual convention (`timestamp with time zone`). |
| H2 | AC6 | High | The proposed two-connection JDBC test cannot detect the failure mode it is written for. |
| H3 | AC6 | High | The two methods do not share a query pair; "two near-identical tests" understates the work. |
| M1 | AC4 | Medium | Scope table is incomplete — property-tunable **cron** schedulers have the identical exposure and are excluded by assertion, not by evidence. |
| M2 | AC4 | Medium | `failFast` boot-block punishes the exact operator action the check is about; the predicate is a heuristic, not a proof. |
| M3 | AC4 | Medium | Duplicates every `lockAtLeastFor` literal into a second source of truth with no drift guard. |
| M4 | AC4 | Medium | `env.getProperty(..., Long.class, ...)` is unguarded against duration-format values, and breaks every existing `ConfigStartupAssertionTest` case. |
| M5 | AC3 | Medium | Task 5 is self-contradictory on `handleFailure`, and omits the `DEAD` branch. |
| M6 | AC1 | Medium | Deny-list and allow-list in adjacent sentences disagree; `DRAFT` is never resolved. |
| M7 | AC1 | Medium | Guard is asymmetric — the automatic strike path keeps no guard at all; record-keeping flow not considered. |
| L1 | AC7/AC8 | Low | The ledger bullet AC7 preserves describes a mechanism that no longer exists at HEAD, and AC8 leaves it uncorrected. |
| L2 | AC2 | Low | Severity overstated; the fix reduces variance rather than removing it, and leaves three capture points. |
| L3 | AC2 | Low | Task 3 is unfalsifiable as an acceptance criterion. |
| L4 | AC5 | Low | Omits the `@NotAudited` outcome the codebase's own precedent points to; branch (a) has no history story. |
| L5 | AC3 | Low | Index coverage for the changed `resetStaleClaimed` predicate is never considered. |
| L6 | AC3 | Low | No rollout story for rows already `CLAIMED` at migration time. |
| L7 | AC8 | Low | Sweep silently drops a still-open deferred-122 ledger item. |
| L8 | — | Low | Minor citation drift despite the "re-verified against HEAD" claim. |

---

## Blockers

### B1 — AC4's stated premise is false: ShedLock 7.10.1 *does* resolve `${...}` in `lockAtLeastFor`

The story asserts this as verified fact:

> ShedLock does not resolve `${...}` placeholders in `lockAtLeastFor`/`lockAtMostFor` — verified:
> neither `MethodProxyScheduledLockAdvisor` nor `SchedulerProxyScheduledLockAdvisor` in
> `shedlock-spring:7.10.1` performs any property-placeholder or `Environment` resolution on these
> annotation attributes.

The two advisor classes were the wrong place to look — they delegate to the extractor. In
`shedlock-spring-7.10.1.jar`:

- `SpringLockConfigurationExtractor` holds a field
  `private final org.springframework.util.StringValueResolver embeddedValueResolver`.
- Both `getLockAtMostFor(AnnotationData)` and `getLockAtLeastFor(AnnotationData)` route through
  `private Duration getValue(long, String, Duration, String)`.
- `getValue`'s bytecode calls
  `StringValueResolver.resolveStringValue(...)` on the annotation's String attribute when the
  resolver is non-null, then hands the result to `StringToDurationConverter.convert(...)`.
- `LockConfigurationExtractorConfiguration` is `EmbeddedValueResolverAware` and passes that resolver
  into the extractor's constructor, so it *is* non-null in a Spring app.
- `StringToDurationConverter` accepts both ISO-8601 (`^[+-]?P.*$` → `Duration.parse`) and the
  Spring-style `<number><unit>` form (`60000ms`, `30s`).

Reproduce:

```
unzip -q ~/.m2/repository/net/javacrumbs/shedlock/shedlock-spring/7.10.1/shedlock-spring-7.10.1.jar
javap -p -c net/javacrumbs/shedlock/spring/aop/SpringLockConfigurationExtractor.class \
  | sed -n '/private java.time.Duration getValue/,/Exception table/p'
```

**Impact.** AC4 exists only because the lock floor is assumed to be un-tunable. It is tunable. The
whole prescribed apparatus — a `record SchedulerLockConfig`, a hand-maintained 7-row literal, a
per-row `failFast` judgement call, boot-blocking, and the test matrix for it — is an elaborate
runtime detector for a problem that can be removed at the declaration site. A `${...}` string is
still a compile-time constant expression, so this is legal exactly where the current literal sits:

```java
@Scheduled(fixedDelayString = "${platform.video.deletion.outbox_poll_delay_ms:60000}")
@SchedulerLock(name = "VideoDeletionOutboxProcessor_process",
               lockAtMostFor  = "${platform.video.deletion.outbox_lock_at_most:PT15M}",
               lockAtLeastFor = "${platform.video.deletion.outbox_lock_at_least:PT30S}")
```

**Recommendation.** Rewrite AC4 against the corrected premise before any code is written. Two
defensible shapes, both far smaller than what is currently specified:

1. Make each lock floor a property with the current literal as its default, so one operator changing
   cadence can change the floor in the same place. Residual risk: they still have to remember —
   which is a documentation problem, not a boot-assertion problem.
2. If a cross-check is still wanted after (1), keep it to an ERROR log + metric (see M2) and derive
   *both* sides from `Environment` so there is no duplicated literal (see M3).

Whichever is chosen, the story's "ShedLock cannot do this" sentence must be deleted, not softened —
it will otherwise be quoted as settled fact by the next story, exactly as the deferred-120 ledger
bullet was quoted by this one.

---

### B2 — AC3 does not close the double-processing path, and Task 6 would document a false invariant

AC3's problem statement frames `resetStaleClaimed` as the entry point to duplicate work:

> A concurrent tick's `resetStaleClaimed` can then free it while the first instance is still
> processing it, and `claimPendingBatch` immediately re-claims it.

That is one path. It is not the only one, and it is not the load-bearing one. Both repositories'
batch fetch is globally scoped:

```java
// VideoDeletionOutboxRepository:33-38  (RadarCompositeDlqRepository:31-36 is identical)
@Query(value = """
    SELECT * FROM main.video_deletion_outbox
    WHERE status = 'CLAIMED'
    ORDER BY next_retry_at ASC
    """, nativeQuery = true)
List<VideoDeletionOutbox> findClaimedBatch();
```

No owner column, no run token, no `LIMIT`. Walk the exact scenario AC3 names — a run that
legitimately overruns `lockAtMostFor` — **with AC3 already applied**:

1. Instance A holds rows in `CLAIMED`, `claimed_at` = 3 minutes ago, still calling `deleteAsset`.
2. A's ShedLock lock expires. Instance B's tick fires.
3. B: `resetStaleClaimed` — correctly matches nothing (`claimed_at` is recent). *AC3 working.*
4. B: `claimPendingBatch` — matches nothing (those rows are `CLAIMED`, not `PENDING`).
5. B: `findClaimedBatch()` — **returns A's in-flight rows**, because their status is `CLAIMED`.
6. B processes them. Duplicate `deleteAsset` / `recalculateComposite`, duplicate
   `video_deletion_log` rows.

`claimed_at` removed step 3 and changed nothing about step 5. The codebase already knows this — it
is written down in the class the story is about:

> `VideoDeletionOutboxProcessor.java:63-67` — "`findClaimedBatch()` is unscoped to this invocation's
> own claim (global `WHERE status = 'CLAIMED'`, no per-run filter) … so each processes rows the other
> is concurrently processing — real duplicate `videoProviderAdapter.deleteAsset` calls and duplicate
> `video_deletion_log` rows, not merely a shared-field race."

AC3 Task 6 then instructs:

> Update both classes' existing Javadoc … to state the invariant now holds structurally
> (claim-time-keyed), not just "restored via a buffer" — the `skillars-deferred-120` 20-minute-buffer
> reasoning becomes a secondary safety margin, not the primary fix, once this lands.

Both halves are wrong. The `STALE_CLAIM_WINDOW > lockAtMostFor` invariant is not demoted by
`claimed_at`; it is *re-based onto a correct clock* and remains the only thing keeping
`resetStaleClaimed` from freeing live rows (a run that overruns by more than 20 minutes still gets
its rows freed, now measured from claim time instead of eligibility time — better, not eliminated).
And nothing becomes structural while step 5 exists. Following Task 6 deletes an accurate warning
(`VideoDeletionOutboxProcessor.java:74-84`) and replaces it with a false assurance, which is a worse
outcome than not doing AC3 at all.

**Recommendation.** Keep the `claimed_at` column — it is the right primitive — and extend AC3 to
actually use it for scoping:

- `claimPendingBatch` stamps `claimed_at = :now` (as specified), and the processor passes a single
  `Instant runClaimedAt` for the whole tick.
- Add `findClaimedBatch(@Param("claimedAt") Instant claimedAt)` filtering
  `status = 'CLAIMED' AND claimed_at = :claimedAt`, and give it a `LIMIT :batchSize`. That makes the
  batch genuinely this run's own claim and closes step 5. (A `claimed_by` token is the more
  conventional shape if a future run could ever share a timestamp; a single `Instant.now()` per tick
  is sufficient here and cheaper.)
- Rewrite Task 6 to say the *claim scope* is now structural and the window-vs-lock invariant is
  still primary and still load-bearing — do not weaken it.

If the owner decides scoping `findClaimedBatch` is out of scope, then AC3 must say plainly in the
Javadoc that the duplicate-processing path remains open via the unscoped fetch, and the ledger bullet
must **not** be deleted by AC8 — only narrowed.

---

## High

### H1 — AC3 prescribes the wrong timestamp type for both tables

AC3 Task 1:

> two additive `ALTER TABLE ... ADD COLUMN IF NOT EXISTS claimed_at timestamp without time zone`
> … mirror `V143__user_cleanup_failed_at.sql`'s own header-comment style (explain what, why, and the
> **timestamp type choice matching this table's existing convention**).

Applying the story's own stated criterion to the actual tables gives the opposite answer
(`V138__baseline_schema.sql:452-461` and `:1390-1398`):

```sql
CREATE TABLE development.radar_composite_dlq (
    ...
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at    timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE main.video_deletion_outbox (
    ...
    next_retry_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at    timestamp with time zone DEFAULT now() NOT NULL
);
```

Both tables are uniformly `timestamptz`; both entities map them as `Instant`
(`VideoDeletionOutbox.java:41-48`). V143 chose `timestamp without time zone` because that is
`main."user"`'s convention — its own header says so explicitly — and that rationale does not
transfer. Adding a `timestamp` column next to two `timestamptz` columns, then comparing it against an
`Instant`-bound parameter in `resetStaleClaimed`, is a gratuitous inconsistency in a staleness
predicate.

**Fix:** `claimed_at timestamp with time zone` on both tables. Adjust the header comment's rationale
accordingly instead of copying V143's sentence.

### H2 — AC6's test cannot fail in the scenario AC6 exists for

AC6 names the risk precisely:

> it cannot catch the torn-read AC3 exists to prevent if Spring's `validateExistingTransaction = false`
> default ever silently discards the isolation request (documented risk: a future caller wrapping
> either method in an ambient `@Transactional`/`TransactionTemplate` block).

The prescribed test then drives two raw JDBC connections with hand-written
`SET TRANSACTION ISOLATION LEVEL REPEATABLE READ` and hand-written SELECTs. It never calls
`getEnforcementProfile`, never goes through Spring's transaction manager, and therefore cannot
observe whether that method's requested isolation was honoured or discarded. It asserts that
PostgreSQL implements REPEATABLE READ — which it does, and will keep doing, while
`AdminCoachEnforcementService` silently runs at READ COMMITTED. The story half-notices this
("the guarantee is the database's, not the service method's") and spends the AC anyway.

**Recommendation.** Replace the two-connection JDBC test with one that observes the real thing. The
cheap, direct version: from inside the service call, read the isolation level the transaction
actually got.

```java
// Assert the *effective* level, not the annotation. Fails exactly when
// validateExistingTransaction=false silently drops the request.
int level = DataSourceUtils.getConnection(dataSource).getTransactionIsolation();
assertThat(level).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
```

Wire it via a test-scoped `@Transactional`-aware probe (a `TransactionSynchronization`, or a spy on
one of the injected repositories that records the isolation on first call — the latter needs no
production change at all). Then add the negative case that gives the test its value: invoke
`getEnforcementProfile` from inside an ambient `TransactionTemplate` block and assert it is *not*
REPEATABLE_READ, pinning the documented hazard as observed behaviour rather than prose. That is
strictly more coverage than the proposed IT, at a fraction of the cost, and it does not require a
second raw connection at all.

If a genuine two-connection snapshot test is still wanted on top, it should call the **service
method** on connection A, not hand-written SQL.

### H3 — AC6 Task 2's "shared torn-read shape" is not shared

> Cover both `getEnforcementProfile` and `getCoachesUnderEnforcement`'s shared torn-read shape — a
> single parameterized test or two near-identical tests.

They read through different queries:

| Method | Status read | Count read |
|---|---|---|
| `getEnforcementProfile:88-91` | `coachProfileRepository.findById` | `countByCoachIdAndCreatedAtAfter` (derived, single id) |
| `getCoachesUnderEnforcement:387-394` | `findByStatusInOrderByStatusChangedAtAsc` (**paged**, `Pageable` of 20) | `countByCoachIdInAndCreatedAtAfter` (**`@Query` returning `List<Object[]>`, `GROUP BY s.coachId`**) |

Hand-writing the second pair's SQL means reproducing Spring Data's pagination SQL and an `IN`-list
`GROUP BY` projection by hand, then keeping it in sync. That is not "near-identical" to the first,
and it is the part of AC6 most likely to rot silently. Under H2's recommendation this disappears —
the isolation probe is method-agnostic and genuinely is two near-identical tests.

---

## Medium

### M1 — AC4's scope table is incomplete; the "7 sites" claim does not hold

> This story's own audit found the true risk surface is 7 sites (not "all `@Scheduled` methods" — a
> scheduler with no `@SchedulerLock`, or a `lockAtLeastFor` of `PT0S`, cannot be undercut by any
> cadence value)

The exclusion criteria are right; the filter applied is not. The story narrowed to `fixedDelayString`
only, which drops three schedulers whose cadence is *also* operator-tunable via a Spring property and
whose `lockAtLeastFor` is *also* a hardcoded non-zero literal:

| Scheduler.method | Tunable cadence | Hardcoded `lockAtLeastFor` |
|---|---|---|
| `VideoLifecycleScheduler` (`:74-75`) | `${app.video.lifecycle.cron:0 0 3 * * *}` | `PT30S` |
| `SluSnapshotAppliedRetentionService` (`:45-47`) | `${app.slu.snapshot-applied.prune-cron:0 30 3 * * *}` | `PT1M` |
| `NeglectedSkillDetectionService` (`:46-48`) | `${app.development.neglected-detection-cron:0 0 6 * * MON}` | `PT5M` |

An operator setting `app.video.lifecycle.cron=*/10 * * * * *` is undercut by `PT30S` exactly the way
lowering `outbox_poll_delay_ms` below `PT30S` is. Nothing about `fixedDelayString` is load-bearing to
the failure mode — *property-tunable cadence* is.

I confirmed the story's two stated exclusions are correct:
`ReconciliationWorkerScheduler.sweepOrphanedProviderAssets` is `lockAtLeastFor = "PT0S"` (`:175`), and
`ReconciliationWorkerScheduler.reconcile` (`:49`) carries no `@SchedulerLock` at all — so neither
belongs in the table. The remaining ~16 `@SchedulerLock` sites use literal `fixedDelay`/`cron`
values, so they are genuinely not operator-tunable and are correctly out.

Under B1's placeholder fix this finding mostly evaporates (a cron property and a lock property are
tuned in the same file). If AC4 survives as a boot check in any form, the three rows above belong in
it, and the selection criterion in the AC text must be corrected to "property-tunable cadence", not
"`fixedDelayString`".

### M2 — `failFast` boot-blocking is the wrong response, and the predicate is a heuristic

AC4:

> a dropped run whose effect is a growing backlog with a compliance/cost dimension (video/DLQ
> deletion, retry processing) should block boot.

Two problems.

**The trigger is the remedy.** The scenario in which an operator lowers
`platform.video.deletion.outbox_poll_delay_ms` below 30s is: there is a deletion backlog and they are
trying to drain it faster. AC4's response is to refuse to start the application. The check would fire
during an incident, on the change intended to resolve it, and the operator's only recourse is to
revert the mitigation. An ERROR log plus the `config.value.misconfigured` metric tells them exactly
what they need (*"your 10s poll is being floored to 30s — raise the floor too"*) without taking the
service down.

**`configuredDelay < lockAtLeastForMs` does not prove a dropped run.** `fixedDelay` is measured from
*completion*, so the effective interval is `executionTime + fixedDelay`. A processor whose batch
takes 25s with a 10s delay never contends with a `PT30S` floor. The check as specified reports a
violation there and, under the AC's own severity rule, blocks boot on it. It is a useful warning
heuristic; it is not a sound fail-fast predicate.

**Recommendation.** ERROR + metric for all rows; no `failFast` for any of them. If the owner wants a
hard gate, it belongs at build time against the annotations (see M3), not at boot against the
environment.

### M3 — AC4 creates a second source of truth for every `lockAtLeastFor` value

> a `private static final List<SchedulerLockConfig>` literal for the 7 rows above

Each row re-states a `lockAtLeastFor` that already exists in a `@SchedulerLock` annotation. Nothing
ties them together. The next person who tunes `VideoDeletionOutboxProcessor`'s floor from `PT30S` to
`PT45S` and does not know `ConfigStartupAssertion` exists leaves the check validating `PT30S` — the
assertion then reports "healthy" for a configuration it is no longer describing, which is worse than
having no check, because the ERROR's absence now reads as a clean bill of health.

If a check is kept, read the annotation rather than transcribing it:
scan `@Scheduled` + `@SchedulerLock` bean methods reflectively, resolve both attributes through the
same `Environment`, and compare. That has no drift surface and automatically covers M1's three cron
schedulers and anything added later. A `*Test`-phase reflective scan (in the spirit of
`MigrationConventionLintTest`) would catch it at build time with no runtime cost at all.

### M4 — `env.getProperty(..., Long.class, ...)` is unguarded, and it breaks the existing test class

AC4 prescribes `env.getProperty(key, Long.class, defaultMs)`. Two concrete problems:

1. **Format.** `@Scheduled(fixedDelayString = ...)` accepts a duration string as well as a bare
   millisecond count — `"30s"` and `"PT30S"` are both legal cadence values. `getProperty(key,
   Long.class, …)` throws `ConversionFailedException` on those. Thrown from
   `onApplicationEvent`, outside `dev`, that is an unhandled startup crash with a stack trace instead
   of the `AppSetupException` message the class is built to produce. `ConfigStartupAssertion` already
   models the right handling for its DB path (`:94-105` catches `NumberFormatException`, logs, emits
   a `non_numeric` metric, continues); the new block must do the same, and should accept duration
   strings rather than reject them.
2. **Existing tests.** `ConfigStartupAssertionTest:35` supplies `Environment` as a Mockito `@Mock`.
   An unstubbed `getProperty(String, Class<Long>, Long)` returns `null`, so the new loop NPEs on
   unboxing in **every existing test in that class**, not just the new ones. The AC's test task
   (extend with at-floor / below-floor / shared-property cases) does not mention that the shared
   fixture at `:45-48` needs a lenient default stub first. Budget for it.

Also minor: the `log.info("… {} bounded platform config keys checked …")` line at `:157` reports
against `ConfigBounds.ALL`; the new checks are not in that count. Either extend the message or leave
it — but decide deliberately, since the deferred-122 cross-field check already set the precedent of
*not* counting (`:119-124`).

One thing the AC gets right and should keep: the proposed `{key, reason}` tag set matches the
existing `config.value.misconfigured` registration, so the `PrometheusMeterRegistry` tag-key
collision warned about at `:172-178` will not occur.

### M5 — AC3 Task 5 contradicts itself, and omits the `DEAD` branch

> Leave `handleFailure` paths alone in both classes — a row still legitimately `PENDING`/retrying
> should keep `claimed_at` cleared too (it is no longer claimed once `handleFailure` sets status back
> to `PENDING`) — apply the same clear-on-non-CLAIMED-transition rule there as well, not just on
> `COMPLETED`.

"Leave alone" and "apply the rule there as well" are opposite instructions in one sentence. A dev
cannot implement this without guessing.

The correct rule is the second one, and it needs to name all three exits.
`VideoDeletionOutboxProcessor.handleFailure:185-202` and
`RadarCompositeDlqProcessor.handleFailure:81-100` set status to either `DEAD` (retries exhausted) or
`PENDING` (backoff) — the AC names only `PENDING`. A `DEAD` row with a stale non-null `claimed_at` is
exactly the false claim-age reading the AC's own rationale warns about.

**Rewrite as:** clear `claimed_at` on *every* transition out of `CLAIMED` — `COMPLETED` (both
`completeRow` / `completeRowWithNullAsset` / the drill-refCount branch), `PENDING`, and `DEAD`. One
sentence, no exceptions, no "leave alone".

The AC's enumeration of the success paths is otherwise correct and complete — I verified the three
video write sites (`:138-139`, `:166-167`, `:179-180`) and the single radar one (`:72-73`).

### M6 — AC1's deny-list and allow-list disagree, and `DRAFT` is unresolved

> reject with `ResponseStatusException(HttpStatus.CONFLICT, ...)` **if the status is `SUSPENDED` or
> `DEACTIVATED`**. **Allow `ACTIVE`, `PENDING_REVIEW`, and `REDUCED`**

`CoachProfileStatus` has six values:

```java
public enum CoachProfileStatus { DRAFT, ACTIVE, REDUCED, PENDING_REVIEW, SUSPENDED, DEACTIVATED }
```

The first sentence is a deny-list (`DRAFT` allowed); the second is an allow-list (`DRAFT` rejected).
The test matrix in Task 3 covers `SUSPENDED`, `DEACTIVATED`, `PENDING_REVIEW`, `REDUCED`, `ACTIVE` —
five of six — so it cannot arbitrate either. Whichever behaviour is intended, say it once and pin
`DRAFT` in `ManualStrikeIT`.

(A deny-list is the safer default here: it fails open for any status added later, which for an
admin-initiated action is the right bias.)

### M7 — AC1's guard is asymmetric, and the record-keeping flow is not considered

Two things AC1 asserts without checking:

**The automatic path keeps no guard.** `ReliabilityStrikeService.issue` has exactly two callers:
`AdminCoachEnforcementService:253` (the manual path AC1 guards) and
`CancellationRefundService:94` (the cancellation/no-show listeners). After AC1, a `SUSPENDED` coach
still accrues automatic strikes from the listener path while an admin's manual strike on the same
coach is rejected 409. AC1's rationale — *"no enforcement value — the coach is already off the
marketplace"* — applies identically to the listener path, so either the rationale is wrong or the
guard is in the wrong place. Decide explicitly; do not leave the asymmetry undocumented.

**A strike is also a record, not only an enforcement trigger.** The concrete flow AC1 blocks: a coach
is suspended on Monday; on Tuesday an admin processes a no-show for Sunday's session. Under AC1 that
is a 409 and the no-show cannot be recorded at all, which also means it never counts toward the
rolling window if the coach is later reinstated. If the intent is "no *new enforcement effect* for an
already-suspended coach", the correct shape is to record the strike and suppress the escalation —
not to refuse the call. Worth an explicit owner decision, since AC1 currently states the trade-off
as self-evident.

---

## Low

### L1 — AC7 preserves a ledger bullet whose mechanism no longer exists

The bullet AC8 instructs to keep (annotated, not deleted) says:

> `deleteById` is queued, then `withBoundedRetry`'s `entityManager.flush()` issues the DELETE *before*
> the savepoint is taken (`PessimisticLockRetryer:132-134`) … it is folded into the
> `persistence.lock_retry` timer, which makes the metric misleading.

That described the pre-deferred-122 code. At HEAD, `deleteStrike` uses the bulk `@Modifying` JPQL
delete, executed immediately at `AdminCoachEnforcementService:285` — **eight lines before**
`withBoundedRetry` at `:293`, and therefore before `Timer.start` inside it. Nothing is folded into
the timer any more.

Note the story's own Group A table says the opposite of the ledger ("**invisible to** the
`persistence.lock_retry` metric") — which is the correct reading for HEAD — yet still cites
`PessimisticLockRetryer.java:132-134` as its evidence, and AC7 Task 2 then annotates the stale bullet
without correcting it. The result is a permanently-wrong record that the next story will mine, which
is precisely the failure this story's Provenance section was written to avoid.

**Fix:** in AC7, drop the `PessimisticLockRetryer:132-134` citation (replace with
`AdminCoachEnforcementService:285` + `CoachReliabilityStrikeRepository:45-47`); in AC8, correct the
bullet's mechanism text in the same edit that adds the `[DECIDED: accepted risk]` annotation.

For the record, AC7's self-bounding argument itself checks out: `findByIdForUpdate` is
`jakarta.persistence.lock.timeout = 0` (NOWAIT) with a documented ~3.2s / 8-attempt retry budget
(`CoachProfileRepository:30-38`), so the winning transaction's own work genuinely is bounded. The
accepted-risk decision is sound; only its citation and its ledger text are stale.

### L2 — AC2's severity is overstated and the fix leaves three capture points

The story's Priority line calls this one of "two live timing/consistency bugs". The actual exposure
is a window-origin skew bounded by the ~3.2s retry budget against a **30-day** window — it changes an
outcome only for a strike whose `created_at` falls inside that specific sub-second-to-3-second band,
30 days back. That is a real nondeterminism and worth removing; calling it a live bug at High
priority is not supported by the mechanism.

More substantively: hoisting the capture above `withBoundedRetry` reduces the variance, it does not
remove it — the cutoff is still `OffsetDateTime.now()` at an arbitrary point in the method. After
AC2 there will be three different capture points for the same conceptual window:
`deleteStrike` (pre-lock), `getEnforcementProfile:91` (inline, explicitly left alone), and
`getCoachesUnderEnforcement:389` (inline, explicitly left alone). The count an admin reads off the
enforcement screen can therefore still disagree with the count `deleteStrike` acts on moments later.
Leaving the two read-only sites unchanged is a defensible call (and the AC's reasoning for it is
sound — neither takes a lock), but the AC should state the residual plainly rather than implying the
fix makes the decision input deterministic.

### L3 — AC2 Task 3 is not a testable acceptance criterion

> Add a unit or IT-level assertion … or, if no such seam exists, a code-comment-anchored regression
> note plus reliance on the existing concurrency ITs continuing to pass.

The second branch is satisfied by writing a comment and changing no tests, which means the task can
never fail. Either commit to the seam or state outright: *"no new test — the ordering is covered by
code comment only; `AdminCoachEnforcementConcurrencyIT` remains the regression guard."* The AC's
instinct not to introduce an injectable `Clock` for this alone is right; it just needs to say so as a
decision rather than as an option.

### L4 — AC5 omits the outcome the codebase's own precedent points to

AC5 forks into (a) Envers is broken → add `user_aud` columns, or (b) Envers tolerates it → document.
There is a third outcome, and it is the one this repository already chose for the same entity:
**mark the fields `@NotAudited`.** `V143__user_cleanup_failed_at.sql`'s header records exactly that
decision for the four `cleanup_*` columns on `main."user"` ("all four new/existing columns here are
annotated `@NotAudited` on the entity — they are operational marker state, not user-facing auditable
history — so no matching `user_aud` column is needed"). Whether `skillars_role` /
`verification_status` are auditable history is a real product question — a role change plausibly is —
but AC5 should present it as a decision, not exclude it.

Branch (a) also has no history story: newly-added `user_aud` columns are `NULL` for every existing
revision, indistinguishable from "was genuinely null at that revision". Say whether that is accepted
(it probably is) so the next reader does not treat the NULLs as data.

The premise itself is confirmed. `User` is `@Audited` (`User.java:45`); `skillarsRole` (`:138-140`)
and `verificationStatus` (`:142-144`) carry no `@NotAudited`; `main.user_aud`
(`V138__baseline_schema.sql:1314-1346`) has neither column, while `main."user"` has both (`:1289-1290`).
Envers is on the classpath (`pom.xml`) and configured (`application.yaml:79-80`), and
`hibernate.ddl-auto: none` (`:66`) means no schema validation would have surfaced it at boot. AC5's
investigate-first instruction is the right call.

### L5 — AC3 never considers index coverage for the changed predicate

`resetStaleClaimed` moves from `next_retry_at < :deadline` to `claimed_at < :deadline`. Existing
indexes (`V138__baseline_schema.sql:3340`, `:3664`, `:3670`):

- `idx_radar_composite_dlq_status_retry ON (status, next_retry_at)` — currently serves this query;
  after the change it serves only the `status` prefix.
- `idx_vdoutbox_status_claimed ON (status) WHERE status = 'CLAIMED'` — still covers the video side's
  status predicate, so that one degrades less.

Both tables are small enough that this is very likely fine. State that as an accepted call in the
migration header, or add a matching partial index — don't leave it unexamined.

### L6 — AC3 has no rollout story for rows already `CLAIMED` at migration time

Immediately after `V144`, every existing row has `claimed_at IS NULL`. The new predicate
(`claimed_at IS NOT NULL AND claimed_at < :deadline`) therefore never matches a row that was
`CLAIMED` when the migration ran — including rows left behind by an instance that crashed before the
deploy. In the current design `findClaimedBatch()`'s unscoped SELECT picks them up anyway, so the
effect is masked rather than absent — but under B2's recommended scoping fix it stops being masked
and those rows are stranded permanently.

Decide explicitly, and state it in the migration header. If a backfill is chosen
(`UPDATE ... SET claimed_at = now() WHERE status = 'CLAIMED'`), note that it will trip
`MigrationLint.Rule.UNBATCHED_DML` and needs either a bounded form or a
`-- migration-lint: allow-*` opt-out with a reason.

### L7 — AC8's sweep silently drops a still-open deferred-122 item

The deferred-122 ledger section contains **two** un-annotated bullets, not one. AC8's disposition
table accounts for the REPEATABLE_READ item (→ AC6) but never mentions:

> **`main."user"` has no index supporting the cleanup sweep predicate.**

Not picked up, not listed under "Explicitly out of scope", not in the disposition table. Leaving it
unmentioned is a legitimate scoping choice; leaving it *unnamed* is not, given AC8 also asks for a
`## Last audit: 2026-09-18` narrative "summarizing what was checked and closed" — that block would
misrepresent the section's state. Add it to the out-of-scope list with a one-line reason (the ledger
already supplies one: it needs production `EXPLAIN` evidence and a `CREATE INDEX CONCURRENTLY`
migration of its own).

Separately, the deferred-121 section's own preamble says "the three remaining un-annotated bullets
below are still open" when there are four. The story correctly picked up all four (AC7, AC1, AC2,
AC5); AC8 may as well fix the stale count while editing that section.

### L8 — Minor citation drift despite the "re-verified against HEAD" claim

The story states all line numbers were re-verified, and the significant ones check out — I confirmed
`AdminCoachEnforcementService` `:86-112`, `:235-243`, `:244-245`, `:285`, `:293`, `:303`, `:389`;
`ReliabilityStrikeService` `:88`, `:91`; `VideoDeletionOutboxRepository:43-48`;
`RadarCompositeDlqRepository:38-45`; `V138__baseline_schema.sql:1289-1290`; and the existence of every
referenced test class (`ManualStrikeIT`, `AdminCoachEnforcementConcurrencyIT`,
`AdminCoachEnforcementServiceIsolationTest`, `VideoDeletionOutboxProcessorIT`,
`RadarCompositeDlqProcessorTest`, `ConfigStartupAssertionTest`, `BookingServiceConcurrencyIT`,
`MigrationConventionLintTest`). Two small drifts:

- `CoachReliabilityStrikeRepository.deleteByIdAndCoachId` is at `:45-47` (`:44` is a comment line).
- `User.skillarsRole` / `verificationStatus` are at `:138-144`, not `:139-146`.

Harmless in themselves; flagged only because the story leans on the re-verification claim to tell
implementers not to trust the ledger.

---

## What I checked and found sound

Recorded so the implementer does not re-derive it:

- **AC4's two stated exclusions are correct.** `ReconciliationWorkerScheduler.sweepOrphanedProviderAssets`
  is `lockAtLeastFor = "PT0S"` (`:175`) and `ReconciliationWorkerScheduler.reconcile` (`:49`) carries
  no `@SchedulerLock` — neither belongs in AC4's table. The `QuotaReservationTimeoutService`
  "zero margin at defaults" row is real (`app.video.reservation-check-interval-ms:60000` vs `PT1M`)
  and, under a strict `<` comparison, correctly does *not* fire at defaults.
- **AC4's shared-property pair is real.** `OutboxPollerScheduler:50` and `DeletionSchedulerService:53`
  both read `${app.storage.poller.fixed-delay-ms:5000}` (set to `5000` at `application.yaml:244`);
  one operator change does affect both.
- **AC3's `:now` assumption holds.** Both `claimPendingBatch` methods already take `@Param("now")`,
  so adding `claimed_at = :now` to the `SET` clause needs no signature change.
- **AC7's self-bounding argument holds** — see L1.
- **AC5's premise holds** — see L4.
- **AC2's reasoning for leaving `getCoachesUnderEnforcement:389` alone is correct**: that method takes
  no `findByIdForUpdate`, so there is no lock wait for the cutoff to slide across.

---

## Recommended disposition

| AC | Action |
|---|---|
| AC1 | Resolve M6 (deny-list vs allow-list, `DRAFT`) and take an owner decision on M7 before implementing. Small once settled. |
| AC2 | Proceed. Fix L3 (pick one, state it) and soften the severity framing per L2. |
| AC3 | **Rewrite.** Extend scope to `findClaimedBatch` (B2), correct the column type (H1), fix Task 5 (M5), add L5/L6 to the migration header. |
| AC4 | **Rewrite from the corrected premise (B1).** Likely collapses to "make the lock floors properties" + an optional build-time reflective lint (M3), with no boot-blocking (M2). If any runtime check survives, apply M1 and M4. |
| AC5 | Proceed as investigate-first. Add `@NotAudited` as an explicit third outcome (L4). |
| AC6 | **Rewrite the test design (H2).** The isolation-probe form also dissolves H3. |
| AC7 | Proceed. Correct the citation and the ledger text (L1). |
| AC8 | Proceed, plus L1's ledger correction, L7's out-of-scope entry, and whatever B2 decides about the `resetStaleClaimed` bullet (narrow vs delete). |
