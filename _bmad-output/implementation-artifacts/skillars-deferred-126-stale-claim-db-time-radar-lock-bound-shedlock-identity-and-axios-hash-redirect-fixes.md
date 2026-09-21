# Story: Stale-Claim DB-Time Fix, Radar Row-Lock Bound, ShedLock Instance Identity & Axios Hash-Redirect Fixes

**Story Key:** `skillars-deferred-126-stale-claim-db-time-radar-lock-bound-shedlock-identity-and-axios-hash-redirect-fixes`
**Epic:** Deferred Work
**Priority:** High (a real cross-instance clock-skew hazard in the outbox/DLQ stale-claim mechanism
both processors share, a real unbounded-wait gap on `RadarCompositeDlqProcessor`'s per-row write path
with a concretely identified concurrent-conflict source, a real ShedLock same-host-instance-identity
gap, and a real frontend hard-navigation bug reachable on every session expiry) — plus the standard
ledger closeout.
**Status:** done
**Created:** 2026-09-21
**Reviewed:** 2026-09-21 (`story-review.md`, senior-dev pre-implementation audit). 25 findings, all 25
independently re-verified against actual source/decompiled pinned jars before applying anything —
**zero false positives**, every finding confirmed genuine. See the Change Log for the full response.

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md`, per this
project's established "read same-day code-review deferrals before drawing scope" convention:

- **`## Deferred from: code review of skillars-deferred-125-outbox-claim-isolation-radar-window-
  margin-and-session-redirect-fixes (2026-09-21)`** — 4 bullets, all freshly surfaced by that story's
  own `/bmad-code-review` (Blind Hunter, Edge Case Hunter, Acceptance Auditor, `txn-and-concurrency-
  audit` layers). All four are genuinely pre-existing (none introduced by skillars-deferred-125) and
  all four were independently re-verified against HEAD (`a40d1985`, skillars-deferred-125's merge,
  PR #213) at this story's creation time, 2026-09-21 — every file/line citation below was re-read
  directly, not copied from the ledger's own prose.

No older ledger item was added to this story's scope. The full-file history of `deferred-work.md`
(90+ `## Last audit` / `## Deferred from` sections, repeatedly and recently re-audited — most recently
2026-09-19/2026-09-21) shows the remaining untagged items are either genuinely too large for this
bundle (`skillars-10-2` D1's `AFTER_COMMIT` platform-wide event-reliability concern, left alone by
three prior stories for the same reason), messaging-module-scoped edge cases unrelated to this story's
subsystems, `deploy-*` items already closed and merely un-struck in an old summary table (verified
during this story's creation — see the "not touched" note in AC5), or — one exception the
`story-review.md` senior-dev audit found this list did not actually cover — the untagged
`@SchedulerLock PT12H` sizing bullet under `## Deferred from: code review of story-115 (2026-09-16)`
(`deferred-work.md:2324-2326`). That bullet **is** in AC3's own subsystem (scheduler locking) and
carries no `[DECIDED]`/`[DISMISSED]` tag, unlike its two correctly-tagged neighbors
(`ModerationSlaMonitorService` no-`@SchedulerLock` at `:2377`; the strike-DELETE unbounded wait at
`:2423`, see AC5/F-11 below). It reads as effectively decided in prose ("no code change needed,
documented in AC2" — that AC2 is `VideoLifecycleScheduler`'s own story, not this one) but was never
formally tagged. Left out of this story's scope deliberately — it is about `VideoLifecycleScheduler`'s
lock *sizing* against a realistic provider-timeout scenario, an unrelated failure mode from AC3's
same-host-instance-*identity* gap — but flagged here rather than silently folded into an inaccurate
"only three categories" claim. AC5 gives it the formal `[DECIDED]` tag its prose disposition never
received, so a future audit does not have to re-derive that it was already considered (see AC5 Task 6).
Four owner decisions were taken live with the user (`AskUserQuestion`) before drafting this story — see
AC1–AC4 below, each of which records both the recommended option the ledger's own fix note suggested
and the alternative(s) presented.

**Line numbers below were re-verified against HEAD (`a40d1985`) at story-creation time, 2026-09-21.**
Re-verify again immediately before implementing each AC — this series' own established convention,
since every prior story in it has found at least minor drift between story-creation time and
dev-story time.

---

## AC1 — Stale-claim staleness comparison: move to DB time, matching ShedLock's own `usingDbTime()`

**The finding (re-verified against HEAD, still live).** `ShedLockConfig.java:27` configures
`JdbcTemplateLockProvider` with `.usingDbTime()` — ShedLock deliberately compares lock timestamps
using the database's clock, not each JVM's own, specifically to be immune to inter-instance clock
skew. Both outbox/DLQ processors' stale-claim recovery does the opposite:

- `RadarCompositeDlqProcessor.java:157`: `Instant runClaimedAt = Instant.now();` — the claiming JVM's
  own wall clock.
- `RadarCompositeDlqProcessor.java:161`: `dlqRepository.resetStaleClaimed(runClaimedAt.minus(STALE_CLAIM_WINDOW));`
  — the staleness deadline is computed from that same app-clock value.
- `VideoDeletionOutboxProcessor.java:152` / `:160`: the identical shape.
- `RadarCompositeDlqRepository.java:24-35` / `VideoDeletionOutboxRepository.java:25-36`
  (`claimPendingBatch`): the `SET status = 'CLAIMED', claimed_at = :now, claimed_by = :runId` clause
  stamps `claimed_at` from that same app-clock `Instant`.
- `RadarCompositeDlqRepository.java:56-61` / `VideoDeletionOutboxRepository.java:70-75`
  (`resetStaleClaimed`): `WHERE status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)`
  — the comparison this fix addresses. (It is **not** the only absolute cross-instance time comparison
  either query makes — `claimPendingBatch`'s own `next_retry_at <= :now` predicate is another, since
  `next_retry_at` is itself written from app-clock `Instant.now()` elsewhere; see the Residual
  paragraph below for why that one is deliberately left alone. An earlier draft of this AC called
  `resetStaleClaimed`'s comparison "the only" one, which contradicted that same paragraph —
  `story-review.md` F-14 caught the inconsistency.)

If instance B's clock runs ahead of instance A's by more than `STALE_CLAIM_WINDOW`'s margin above
`lockAtMostFor` (5 minutes on both processors after skillars-deferred-125 AC2 — radar: 15m
`STALE_CLAIM_WINDOW` − `PT10M` `lockAtMostFor`; video: 20m − `PT15M`, both re-verified against HEAD),
B's `resetStaleClaimed` tick frees and immediately re-claims rows A is still legitimately processing —
a duplicate `recalculateComposite` / duplicate video-deletion attempt, an externally-visible side
effect `claimed_by` cannot undo after the fact. At a skew of that magnitude or more this happens during
entirely normal operation, not only in the single-slow-row residual the `MAX_RUN_DURATION`-sampling
gap already documents as accepted risk. (The originating `deferred-work.md` bullet, `:2620`, states
this threshold as "8 minutes or more" — that figure does not reconcile with either processor's actual
`STALE_CLAIM_WINDOW`/`lockAtMostFor` arithmetic above, which is the defensible, re-derived one; treat
this story's 5-minute figure as the correction, not a second, competing number.)

**Owner decision (2026-09-21, `AskUserQuestion`): move `claimed_at`'s stamp and `resetStaleClaimed`'s
deadline computation to the database's own clock**, matching `usingDbTime()`'s existing choice for
ShedLock, rather than leaving the app-clock comparison as documented accepted risk.

**Fix shape, confirmed against the actual native queries during story creation (not assumed):**

- `claimPendingBatch`'s single `:now` bind parameter today serves **two** purposes in one query — the
  `claimed_at = :now` stamp **and** the `next_retry_at <= :now` eligibility predicate in the same
  statement. Only the stamp is the cross-instance staleness comparison the finding is about;
  `next_retry_at` is itself set from app-clock `Instant.now()` elsewhere in both processors' backoff
  logic (e.g. `row.setNextRetryAt(Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES))`), so fully
  DB-time-ing eligibility too would require touching every place `next_retry_at` is written — a much
  larger change than this deferral asked for. **Scope this fix to the stamp only**: change the `SET`
  clause to `claimed_at = now()` (a bare SQL call, no bind param), and leave the `:now`-bound
  `next_retry_at <= :now` predicate exactly as it is. Postgres allows a literal `now()` call and a
  bound parameter in the same statement freely — no query restructuring needed beyond that one clause.
  Document this scoping explicitly in the field/method Javadoc so a future reader does not assume the
  whole query is now DB-time.
- `resetStaleClaimed(@Param("deadline") Instant deadline)` takes a Java-computed absolute deadline
  today. Change the signature to take the stale-window **width**, not a computed deadline — e.g.
  `resetStaleClaimed(@Param("staleWindowSeconds") long staleWindowSeconds)` — and compute the deadline
  inside the SQL itself: `WHERE status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < now() -
  make_interval(secs => :staleWindowSeconds))`. Prefer `make_interval(secs => ...)` over
  `now() - (:staleWindowSeconds * interval '1 second')` — the latter relies on an implicit
  `bigint → double precision` cast to reach Postgres's `double precision * interval` operator,
  where `make_interval` takes the integer directly and is unambiguous (`story-review.md` F-18).
  Update both call sites to pass `STALE_CLAIM_WINDOW.toSeconds()` instead of
  `runClaimedAt.minus(STALE_CLAIM_WINDOW)`. This predicate stays index-friendly relative to
  `V144__outbox_dlq_claimed_at.sql:30-36`'s own index-coverage note — `now()` is `STABLE` and the
  bind parameter is stable within the call, so there is no new plan regression to that migration's
  already-accepted index-coverage risk; confirm this holds (e.g. via `EXPLAIN`) rather than assuming
  it during implementation.
- `runClaimedAt` (the Java `Instant.now()` local) stays exactly as-is for its two other uses — binding
  `claimPendingBatch`'s `next_retry_at <= :now` eligibility check (unaffected, see above) and computing
  `Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION)` (`RadarCompositeDlqProcessor.java:187` /
  `VideoDeletionOutboxProcessor.java:194`) — this is each run's own **self-terminating budget check**,
  purely in-process, not a cross-instance comparison, and must not be touched.
- Postgres's bare `now()` returns the current **transaction's** start time (equivalent to
  `CURRENT_TIMESTAMP`, constant within one transaction), and `claimPendingBatch` is its own
  `@Modifying @Transactional` method — each call is its own fresh transaction, so `now()` there behaves
  exactly like a single claim-instant stamp, matching the current semantic `runClaimedAt` provided.

**This is not a novel pattern in this codebase — cite the existing precedents, do not treat DB-time
stamping as being invented here (`story-review.md` F-16):** `V144__outbox_dlq_claimed_at.sql:46` and
`:50` already backfill `claimed_at = now()` on these exact two tables; `PlayerRadarCompositeRepository
.java:19` and `PlayerRadarBaselineRepository.java:22` already use `NOW()` in their own native inserts;
and `AbstractIntegrationTest.releaseSchedulerLock` (`:144`) already uses `now() - interval '1 minute'`
in a **test**, which is precisely the DB-time-seeding fixture shape Task 5 below needs.

**Javadoc scope — five sites, not the three originally named (`story-review.md` F-9).** Once
`claimed_at = now()` lands, the following all become false and must be corrected, not just the
processor classes' own `STALE_CLAIM_WINDOW`/`MAX_RUN_DURATION` Javadoc: `VideoDeletionOutbox.java:53-59`
("stamped by `claimPendingBatch` with **the tick's own claim instant**"); `RadarCompositeDlqEntry.java
:58-61` (mirrors the above by reference, so it inherits the same error); `VideoDeletionOutboxRepository
.java:17-18`/`:21`; `RadarCompositeDlqRepository.java:16-18`; and `VideoDeletionOutboxProcessor.java:102`
("this run's own claim instant, stamped by `claimPendingBatch`"). `VideoDeletionOutbox.java` and
`RadarCompositeDlqEntry.java` were missing from this AC's own File List — added below.

**Residual, to be stated in the field Javadoc rather than silently left implicit:** `next_retry_at`
itself remains app-clock-stamped at every site that writes it (row insertion, backoff computation,
`failClaimed`). This fix closes the two comparisons the deferral explicitly named (the `claimed_at`
stamp and the staleness deadline) — it does not make the whole claim/backoff subsystem DB-time. A
skew-sensitive `next_retry_at` misfire only shifts *when* a row becomes eligible for the next attempt
by the skew amount; it is not a double-processing hazard the way the staleness comparison is, which is
why this fix is deliberately scoped narrower than "every timestamp in these two classes."

### Tasks

1. Re-verify the exact native SQL text, bind parameter names, and call sites in both
   `RadarCompositeDlqRepository`/`RadarCompositeDlqProcessor` and
   `VideoDeletionOutboxRepository`/`VideoDeletionOutboxProcessor` against current HEAD before
   implementing — confirm nothing has shifted since this story's creation.
2. In both repositories' `claimPendingBatch` native query, change `claimed_at = :now` to
   `claimed_at = now()`. Leave `next_retry_at <= :now` unchanged. Update the method's Javadoc to state
   this stamp is now DB time, scoped exactly to the reason above (do not let a future reader assume the
   whole query moved to DB time).
3. In both repositories, change `resetStaleClaimed`'s signature from `@Param("deadline") Instant
   deadline` to a stale-window-width parameter (e.g. `@Param("staleWindowSeconds") long
   staleWindowSeconds`), and rewrite the `WHERE` clause to compute the deadline via `now() -
   make_interval(secs => :staleWindowSeconds)` (not a `bigint * interval` multiplication — see the
   AC's own note on why `make_interval` is unambiguous). Update both processors' call sites to pass
   `STALE_CLAIM_WINDOW.toSeconds()` (confirm the exact `Duration` accessor — `toSeconds()` vs.
   `getSeconds()` — against the pinned Java version's `java.time.Duration` API; `toSeconds()` is
   available under this project's pinned Java 17) instead of `runClaimedAt.minus(STALE_CLAIM_WINDOW)`.
4. Update the Javadoc at **all five** sites named above — both classes' `STALE_CLAIM_WINDOW`/
   `MAX_RUN_DURATION`/`resetStaleClaimed`-adjacent Javadoc, **plus** `VideoDeletionOutbox.java`,
   `RadarCompositeDlqEntry.java`, and both repositories' `claimPendingBatch`/`resetStaleClaimed` method
   Javadoc — to document the DB-time change and the stated residual (`next_retry_at` itself stays
   app-clock-stamped). Do not leave "the tick's own claim instant" phrasing standing at any of them.
5. New/updated tests: `RadarCompositeDlqRepositoryIT` and the equivalent `VideoDeletionOutboxProcessorIT`
   repository-level native-query coverage already exercise `claimPendingBatch`/`resetStaleClaimed`
   against a real Postgres (confirm exact existing test names at implementation time — the six known
   call sites are `RadarCompositeDlqProcessor:161`, `VideoDeletionOutboxProcessor:160`,
   `RadarCompositeDlqRepositoryIT:82`/`:101`, `VideoDeletionOutboxProcessorIT:347`/`:376`) — extend or
   add a case that seeds a row with `claimed_at` set via a **direct SQL `now() - interval`** (mirroring
   `AbstractIntegrationTest.releaseSchedulerLock:144`'s own existing `now() - interval '1 minute'`
   fixture shape, not inventing one), not a Java-computed `Instant`, to prove `resetStaleClaimed`'s new
   signature genuinely compares against the database's clock, not the JVM's — the one behavior a purely
   app-clock-based test fixture cannot distinguish from the old implementation. Also confirm
   `claimPendingBatch`'s stamped `claimed_at` value, read back after the call, is close to real
   wall-clock time (sanity bound, not an exact-match assertion, since the test JVM and the test database
   may themselves be skewed in CI).
6. Mutation-check by hand: temporarily revert one repository's `now()` back to `:now`, confirm the new
   test(s) still pass unchanged (expected — a test-harness single-clock environment cannot distinguish
   the two without deliberately skewing the test DB or JVM clock, which is out of scope to engineer);
   instead mutation-check by confirming the new test fails if `resetStaleClaimed`'s `WHERE` clause is
   reverted to compare against a bind-passed `Instant` that is deliberately skewed in the test itself.
   **/bmad-code-review fix (2026-09-21, Decision 1): this mutation check as originally prescribed was
   never actually performed or recorded — the delivered tests instead seeded a wide 30/40-minute
   margin against a 10/20-minute window, which passes identically whether `resetStaleClaimed` compares
   against `now()` or a same-run `Instant` bind (single host clock), so it exercised signature-shape
   only, not the DB-time behavior this task names. Superseded by the boundary-test pair added in the
   code review response (`resetStaleClaimed_boundary_justUnderWindow_notReclaimed` /
   `_justOverWindow_reclaimed`, both repositories): these pin the rewritten predicate's exact
   comparison boundary, which IS mutation-checked by construction — a reverted/flipped `WHERE` clause
   fails one of the two boundary tests — but, per those tests' own Javadoc, still do not and cannot
   prove cross-clock skew immunity in a single-host-clock test harness. This task's original
   deliberately-skewed-bind-parameter approach was assessed and not pursued: it would only prove that
   comparing against SOME skewed value differs from comparing against `now()`, which the boundary tests
   already establish structurally (one predicate reads a Java-passed value, the other does not) without
   needing to fabricate a skew scenario.**
7. Re-run both processors' full test classes plus their repository ITs — zero regressions expected.

---

## AC2 — `RadarCompositeDlqProcessor`: bound the previously-unbounded per-row upsert wait

**The finding (re-verified against HEAD, still live, with a concretely identified conflict source not
in the original deferral note).** `RadarCompositeCalculationService.recalculateComposite`
(`RadarCompositeCalculationService.java:79-133`) already takes a **bounded** pessimistic lock on the
player row — `playerProfileRepository.findByIdForUpdate` carries
`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))` (NOWAIT), wrapped in
`lockRetryer.withBoundedRetry(...)` to absorb brief legitimate contention
(`PlayerProfileRepository.java:41-47`). The two **unbounded** calls are
`compositeRepository.upsertComposite(...)` and `baselineRepository.insertBaselineIfAbsent(...)`
(`RadarCompositeCalculationService.java:130-131`) — both native `INSERT ... ON CONFLICT` statements
(`PlayerRadarCompositeRepository.java:16-24`, `PlayerRadarBaselineRepository.java:19-24`), but the two
are **not identical in locking behavior** (`story-review.md` F-13, correcting this AC's own original
overstatement): `upsertComposite`'s `... ON CONFLICT (...) DO UPDATE` genuinely takes an implicit row
lock on the conflicting row, exactly like a plain `UPDATE`. `insertBaselineIfAbsent`'s
`... ON CONFLICT (...) DO NOTHING` does **not** — against an already-*committed* conflicting row it
takes no row lock and simply skips; it only waits on a *concurrent uncommitted* insert or delete of the
same key. The GDPR scenario below is exactly that (an uncommitted `DELETE`), so both statements
genuinely do wait and the conclusion is unchanged — but the mechanism is per-statement, not uniform,
and any test fixture (see Task 5) must reflect that. Neither statement carries any lock/statement
timeout — unlike `findByIdForUpdate`, which deliberately does.

**Confirmed, concrete conflict source** (found during this story's creation, not in the original
deferral; citation corrected per `story-review.md` F-12 — the method name in the first draft did not
exist). `GdprErasureService.erase` (`:75`, `@Transactional(propagation = Propagation.REQUIRES_NEW)`)
calls the private `deletePlayerDevelopmentData` (`:194`), which deletes
`playerRadarBaselineRepository.deleteAllByPlayerId(playerId)` then
`playerRadarCompositeRepository.deleteAllByPlayerId(playerId)` (`:201-202`) directly inside that same
erasure transaction — **without** first taking the `PlayerProfile` pessimistic lock
`recalculateComposite` serializes on (the class contains no `findByIdForUpdate` call and no
`lockRetryer` dependency at all). A GDPR erasure and an in-flight radar recalculation for the same
player can therefore race for the same `player_radar_composites`/`player_radar_baselines` row through
two genuinely independent lock paths. If the erasure transaction is itself slow (blocked on something
else, a slow disk, a long-running sibling delete earlier in the same method), `recalculateComposite`'s
upsert can hang on that row's lock indefinitely — `MAX_RUN_DURATION` is sampled only between
`RadarCompositeDlqProcessor`'s loop iterations (when this path runs via the DLQ retry poller) and does
not apply at all to the `AFTER_COMMIT`-triggered live path (`onRadarEntrySubmitted`), so a hang there
has no self-terminating backstop whatsoever — and that path is worse than a bare description of "no
backstop" suggests (see the strengthened rationale below).

**The live-path hang is worse than "no backstop," and this belongs in the rationale, not just a
caveat (`story-review.md` F-5).** `onRadarEntrySubmitted` is `@Async("reportExecutor")`
(`RadarCompositeCalculationService.java:58`), and `reportExecutor`
(`DevelopmentConfig.java:105-116`) is deliberately small: `corePoolSize=2`, `maxPoolSize=4`,
`queueCapacity=50`, `RejectedExecutionHandler = CallerRunsPolicy` — and it is **shared** with
`ReportGenerationService` (`:201`). A hung upsert permanently consumes one of at most **four** threads
total; four concurrent hangs exhaust the pool entirely and stall all report generation too, not just
radar recalculation; and once the 50-slot queue also fills, `CallerRunsPolicy` runs new submissions
**on the publisher's own thread** — meaning a request or scheduler thread then blocks synchronously on
the same contended lock. This is the actual blast radius an unbounded wait threatens, not merely "the
live path has no timer."

**A genuine deadlock is also possible here, not only a bounded-vs-unbounded wait
(`story-review.md` F-20).** `erase`'s `deletePlayerDevelopmentData` deletes baselines then composites
(`:201` then `:202`); `recalculateComposite` writes composites then baselines (`:130` then `:131`) —
the two paths take the same two tables in **opposite order**. That is a textbook deadlock shape and can
surface as Postgres SQLState `40P01` (not just `55P03 lock_not_available`) with its own distinct Spring
exception mapping — see Task 4. The configured `lock_timeout` must also stay **above** Postgres's
`deadlock_timeout` (default 1s), or the deadlock detector never gets a chance to run before the
lock_timeout fires first and a real deadlock is misreported as an ordinary lock timeout; a single-digit-
second default satisfies this, but say so explicitly rather than leaving it to work by accident.

**Owner decision (2026-09-21, `AskUserQuestion`): a targeted, narrowest-blast-radius timeout bound**,
rather than a connection-wide `statement_timeout` (would affect every query on that connection,
app-wide) or documenting as accepted risk.

**Mechanism correction from story creation.** The original deferral's own suggested "NOWAIT/timeout
hint … mirroring `PlayerProfileRepository.findByIdForUpdate`" is **not literally applicable** to
`upsertComposite`/`insertBaselineIfAbsent` — `@Lock`/`@QueryHints(jakarta.persistence.lock.timeout=…)`
only affects JPA-level pessimistic-lock acquisition on a `SELECT`-shaped JPQL query; it has no effect
on a native `@Modifying INSERT` statement's implicit row lock. The narrowest mechanism that actually
bounds a native `INSERT ... ON CONFLICT`'s wait is a session-scoped `SET LOCAL lock_timeout` issued
inside the same transaction, before the upsert calls — `SET LOCAL` is transaction-scoped and
auto-resets at commit/rollback regardless of connection pooling (the exact property
`docs/deployment/migration-conventions.md` and `MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT` — just
shipped by skillars-deferred-125 AC4 — already require of migrations, for the identical connection-
pooling-safety reason). Set it once near the top of `recalculateComposite`'s transaction, after the
already-bounded `findByIdForUpdate` call (so it does not interact with that call's own per-statement
NOWAIT hint) and before the per-skill loop that calls the two upsert methods.

**The real implementation trap is not syntax, it is that `SET` accepts no bind parameters at all in
Postgres (`story-review.md` F-19).** `SET LOCAL lock_timeout = ?` is not legal SQL — this repo's own
migration precedent inlines the literal (`V144:37`: `SET lock_timeout = '5s'`;
`docs/deployment/migration-conventions.md:213` prescribes the same form). Two ways to satisfy this
safely, either acceptable: (a) inline the `ConfigService`-bounded `long` directly into the SQL string —
safe here specifically because the value is a validated, internally-controlled bound, never user input,
not because string-built SQL is safe in general; or (b) use
`SELECT set_config('lock_timeout', :value, true)` instead of a literal `SET LOCAL` — `set_config`'s
arguments are genuine function arguments, so `:value` binds normally, and its third argument
(`is_local = true`) gives the identical transaction-scoped behavior `SET LOCAL` would. Pick one and say
so in the task rather than leaving the trap to be discovered mid-implementation.

**`SET LOCAL`'s blast radius depends on `recalculateComposite`'s own transaction propagation, which
this AC must state rather than leave implicit (`story-review.md` F-10).** `recalculateComposite` is
plain `@Transactional` (`Propagation.REQUIRED`, `:78`) — `SET LOCAL` binds to whatever transaction is
active when it runs, so a **future** transactional caller (one that itself opens a transaction before
calling `recalculateComposite`) would inherit that same transaction, and the lock timeout would then
outlive this method and apply to the rest of the caller's own transaction — silently widening exactly
the "narrowest-blast-radius" decision this AC exists to satisfy. Today this is safe: both current
callers were verified — `onRadarEntrySubmitted` is `@Async` and starts no transaction of its own
(`:58`), and `RadarCompositeDlqProcessor.processRow` is not `@Transactional` either. State this
constraint explicitly in the Javadoc Task 7 adds, so a future caller-side change does not silently
break the invariant; do not change `recalculateComposite`'s own propagation to `REQUIRES_NEW` to
"solve" this preemptively — the constraint is documentation-cheap and today's callers are already safe.

**New tunable, mirroring this codebase's established `ConfigService`/`ConfigBounds` pattern rather than
a hardcoded constant.** The originally-considered precedent, `platform.moderation_lock_timeout_minutes`
(`ConfigBounds.java:92`), turns out to be the **wrong** shape to mirror literally — see the migration
question this raises under Task 2/F-1 below. Add a new bounded key (suggested:
`platform.radar_composite_lock_timeout_seconds`, a conservative default in the single-digit seconds,
bounded well below both `MAX_RUN_DURATION` and the AC1/processors' own stale-window margin — not "the
AC2 stale-window margin," which does not exist; AC1 owns that margin) and read it via
`configService.getBoundedLong(...)` — see Task 2 for which overload and why.

**Read the config value before taking the lock, not after (`story-review.md` F-25).**
`ConfigService` is TTL-cached, but a cache-expiry read triggers `configRepository.findAll()` — a real
DB round trip (`ConfigService.java:245-255`). Reading the new tunable *after*
`findByIdForUpdate`/`entityManager.refresh` would put that round trip inside the transaction already
holding the `player_profiles` pessimistic lock, for no reason. Read it before acquiring that lock and
pass the value down.

**Exception-handling scope, deliberately narrow, and widened by the config-read overload chosen
(`story-review.md` F-4).** A `SET LOCAL lock_timeout` expiry surfaces as a Postgres
`55P03 lock_not_available` error, and the genuine lock-ordering inversion above (F-20) can also surface
as `40P01` deadlock-detected — confirm empirically (not assumed) what Spring/Hibernate exception this
project's pinned Spring Boot version translates **each** SQLState to, since they may not map to the
same exception type. Whatever they are, let them propagate — do **not** wrap the two upsert calls in
`PessimisticLockRetryer.withBoundedRetry` or any new retry-in-place mechanism. `recalculateComposite`'s
two existing callers already provide eventual-consistency recovery on any thrown exception:
`onRadarEntrySubmitted`'s own `try`/`catch` routes to `dlqService.emitFailedCompositeCalculation`
(retried by `RadarCompositeDlqProcessor` on its own schedule), and `RadarCompositeDlqProcessor`'s own
loop-level `handleFailure` does the same for the DLQ-retry path. Building a second, in-place retry
mechanism here would duplicate that recovery path for no benefit — the goal of this AC is only to make
a stuck upsert **fail fast and become visible**, not to make it silently succeed after a wait. This is
precisely why the config-read overload matters: `RadarCompositeDlqProcessor`'s own class Javadoc
(`:41-47`) already documents a deliberate accepted residual — if `configService.getBoundedLong` itself
is ever permanently broken (e.g. a missing key), `handleFailure` can never complete its own
transaction, so a row cycles CLAIMED → reset → re-claimed forever. Adding a **second**
`getBoundedLong` call inside `recalculateComposite`, upstream of all the real work, must not widen that
residual: the 3-arg `getBoundedLong(key, min, max)` throws `IllegalStateException` on a missing key,
which would break `recalculateComposite` itself on **both** the DLQ-retry path *and* the live
`AFTER_COMMIT` path — strictly wider than the existing residual, which is confined to `handleFailure`.
The 4-arg `getBoundedLong(key, default, min, max)` never throws on a missing key. **Use the 4-arg
form** — see Task 2's resolution of which `ConfigBounds` shape that implies.

### Tasks

1. Re-verify `recalculateComposite`'s current structure, the two upsert call sites, and the
   `GdprErasureService.erase`/`deletePlayerDevelopmentData` conflict path (corrected citation:
   `GdprErasureService.java:75`, `:194`, deletes at `:201-202`) against current HEAD before
   implementing.
2. **Config key shape — resolved, do not mirror `platform.moderation_lock_timeout_minutes` literally
   (`story-review.md` F-1).** That precedent is a `failFast=true`, non-`HAS_CODE_DEFAULT` key —
   following its shape literally means: (a) it must be registered in `ConfigBounds.ALL`'s static block
   (a `BoundedKey` not added there is invisible to both `ConfigStartupAssertion` and
   `ConfigBoundsEnumCoverageTest`, a silently inert tunable — do not skip this), and (b) because it is
   `failFast` and not in `HAS_CODE_DEFAULT`, `ConfigStartupAssertion` throws `AppSetupException`
   (blocks boot, non-`dev`) if the key has no seeded value — which would require a **new Flyway
   migration**, contradicting this story's own Dev Notes ("No new Flyway migration in this story").
   Resolved: use the **4-arg** `getBoundedLong(key, default, min, max)` route instead (required
   anyway by F-4's residual-widening finding above) and add the new key to `HAS_CODE_DEFAULT` — this
   keeps "no new Flyway migration" true, since a `HAS_CODE_DEFAULT` key's absence from
   `main.platform_config` is expected and does not block boot. Register the `BoundedKey` in
   `ConfigBounds.ALL`'s static block regardless (both routes need this for
   `ConfigBoundsEnumCoverageTest`/`ConfigStartupAssertion` to see it at all) and in
   `HAS_CODE_DEFAULT`. `failFast` on this route only governs the boot-blocking behavior for an
   *out-of-range* (not absent) stored value, should one ever be added later — set it consistently with
   how the sibling video-quota keys in this same class already reason about that question.
3. In `RadarCompositeCalculationService.recalculateComposite`, **read the new config value before**
   `findByIdForUpdate`/`entityManager.refresh` (not after — see F-25 above), then after that call and
   before the per-skill loop, issue the transaction-scoped lock-timeout bound. `SET LOCAL
   lock_timeout = ?` is **not legal SQL** — Postgres's `SET`/`SET LOCAL` accept no bind parameters at
   all (`story-review.md` F-19). Pick one of: (a) inline the already-`ConfigService`-bounded `long`
   directly into the SQL string (safe here specifically because the value is internally validated, not
   user input — this repo's own `V144:37` migration precedent does the same for a literal
   `SET lock_timeout = '5s'`); or (b) `SELECT set_config('lock_timeout', :value, true)`, whose
   arguments are genuine function parameters and so bind normally, with the third argument giving the
   same transaction-local scoping `SET LOCAL` would. State which was chosen in the Javadoc Task 7 adds.
4. Confirm empirically what exception type is thrown for **both** failure modes against a genuinely
   blocked row (a real Testcontainers-backed test holding a competing lock/transaction open, not a
   mocked exception) — the timeout expiry (`55P03`) **and** the genuine lock-ordering deadlock between
   this method's composite→baseline write order and `GdprErasureService`'s baseline→composite delete
   order (`40P01`, see the AC's own note above) — document both findings in the field/method Javadoc
   next to the new lock-timeout call. Also confirm the configured timeout value stays above Postgres's
   `deadlock_timeout` (default 1s), so the deadlock detector gets a chance to fire before the lock
   timeout would otherwise misreport a genuine deadlock as an ordinary lock timeout.
5. **New test — reuse `RadarCompositeCalculationServiceConcurrencyIT`, not a from-scratch design or
   `AdminCoachEnforcementIsolationRuntimeIT` (`story-review.md` F-17), with three preconditions the
   existing precedent's own fixture does NOT satisfy and this AC's test must add explicitly
   (`story-review.md` F-2):**
   - (a) **Seed ≥1 `radar_assessments` row** for the target player+skill before calling
     `recalculateComposite`. `bySkill` (`RadarCompositeCalculationService.java:97-105`) is built
     *exclusively* from `radarRepository.findAggregatesByPlayerAndSkills(...)` — with zero assessment
     rows, `bySkill` is empty and the per-skill loop (`:107`) that calls `upsertComposite`/
     `insertBaselineIfAbsent` never runs at all. The existing `RadarCompositeCalculationServiceConcurrencyIT
     .setUp()` (`:41-52`) seeds only a `user` and a `player_profiles` row — copying it verbatim would
     produce a test that passes for the wrong reason (nothing was ever exercised).
   - (b) **Pre-seed a `development.player_radar_composites` row** for that exact
     `(player_id, skill_code)` key, *committed* before the competing session opens its lock. Per the
     AC's own corrected mechanism note: `upsertComposite`'s `DO UPDATE` conflicts with (and locks) an
     existing committed row; `insertBaselineIfAbsent`'s `DO NOTHING` does not contend with a committed
     row at all — only test the mechanism that is actually true for whichever repository the test
     targets, or seed both a committed composites row and a concurrent uncommitted baselines
     insert/delete if testing both.
   - (c) **Hold the competing lock on that seeded row itself** — a second connection/transaction doing
     `SELECT ... FOR UPDATE` (or an open uncommitted insert/delete of the same key for the
     `DO NOTHING` case) on the `player_radar_composites`/`player_radar_baselines` row, not the
     `player_profiles` row the existing IT's own test targets (that row's lock is the *already-bounded*
     `findByIdForUpdate` path, unrelated to what this AC bounds).
   Mirror `RadarCompositeCalculationServiceConcurrencyIT`'s existing `ExecutorService`/
   `CountDownLatch`/`LOCK_HOLD_MILLIS` shape (`:38`, `:60`, `:65-67`) for the holder thread, and assert
   `recalculateComposite` fails within a bounded wall-clock time (a concrete upper bound with margin,
   not "eventually") rather than hanging past the configured timeout.
6. Confirm the new lock-timeout call does not change behavior for the already-bounded
   `findByIdForUpdate`/NOWAIT call — re-run `RadarCompositeCalculationServiceConcurrencyIT`'s existing
   `recalculateComposite_playerRowLockedByAnotherSession_blocksUntilReleasedThenSucceeds` test (this
   *is* the "deferred-77 concurrent-recalculation test" — it exists, in this exact class, no further
   search needed) to confirm no regression in that lock's own timing.
7. Update `RadarCompositeCalculationService`'s class/method Javadoc to document: the new bound, its
   config key and chosen overload/route, the two failure-mode SQLStates and their Spring exception
   mappings (Task 4), the `Propagation.REQUIRED`-inherits-the-caller constraint (this AC's own note on
   `SET LOCAL`'s blast radius), and the deliberate decision not to retry-in-place (existing
   DLQ/`AFTER_COMMIT`-catch recovery is the intended path).
8. Re-run `RadarCompositeCalculationService`'s full test class (including
   `RadarCompositeCalculationServiceConcurrencyIT`) plus any IT touching
   `PlayerRadarCompositeRepository`/`PlayerRadarBaselineRepository` — zero regressions expected.

---

## AC3 — ShedLock: guarantee a unique `locked_by` identity per JVM instance

**The finding (re-verified against HEAD, still live).** `ShedLockConfig.java:21-29` builds
`JdbcTemplateLockProvider` without calling `.withLockedByValue(...)`, so ShedLock falls back to its own
default (hostname-derived) identity for the `locked_by` column in `main.shedlock`. Two JVMs co-located
on the same host — the exact situation during a rolling/blue-green deploy, where the old container and
the new container can both be up briefly — write the **same** `locked_by` value. ShedLock's unlock
statement predicate is `WHERE name = :name AND locked_by = :lockedBy`, with no `locked_at` component, so
an overrunning old instance's still-live lock can be released by the new instance's own unrelated
unlock call (or vice versa) purely because they share a `locked_by` value — leaving the AC1 stale-claim
window as the only remaining protection against a resulting double-run, not a second independent layer.
This directly undercuts the "safe on multiple instances" claim both processors' class Javadocs make.

**Owner decision (2026-09-21, `AskUserQuestion`): `hostname + "-" + UUID.randomUUID()`**, preserving
host-level identity for at-a-glance operational debugging in the `main.shedlock` table while
guaranteeing per-JVM uniqueness — rather than a bare random UUID (loses host identity) or documenting
as accepted risk (this repo's deploy topology already anticipates the exact rolling-deploy scenario
this gap describes, per the `node_exporter`/Alertmanager decisions already on record for the same
single-host multi-container reality).

**Two implementation questions this AC originally left open are already resolved — do not re-investigate
(`story-review.md` §5, verified against the decompiled pinned `shedlock-*-7.10.1` jars, not from
memory):** `net.javacrumbs.shedlock.support.Utils.getHostname()` is `public static` in the pinned
version — use it directly, no `InetAddress`/`UnknownHostException` fallback needed, ShedLock already
resolves the hostname into a static field this way. `withLockedByValue(String)` is confirmed present
and public on `SqlConfiguration$SqlConfigurationBuilder`, inherited by
`JdbcTemplateLockProvider.Configuration.Builder`.

### Tasks

1. Re-verify `ShedLockConfig.java`'s current `JdbcTemplateLockProvider.Configuration.builder()` chain
   against HEAD before implementing — confirm `.withLockedByValue(...)` is still absent.
2. Resolve the current hostname via `net.javacrumbs.shedlock.support.Utils.getHostname()` (confirmed
   public API in the pinned version — see the note above; no need to fall back to
   `InetAddress.getLocalHost()`).
3. **Compute the combined identity inside the `@Bean` method body itself, not a class-level field
   (`story-review.md` F-8 — the story's own Task 3 and Task 5 originally contradicted each other on
   this point).** `.withLockedByValue(Utils.getHostname() + "-" + UUID.randomUUID())` computed as a
   local expression inside `lockProvider(DataSource dataSource, MeterRegistry meterRegistry)` gives
   production exactly one identity per JVM (the bean is a Spring singleton, so the method runs once in
   a real application — satisfying the original "compute once, not per-lock-acquisition" intent) *and*
   makes Task 5's uniqueness test achievable, since two direct test-side calls to the method bypass
   Spring's singleton scoping and each compute their own fresh value. A `static`/instance field
   populated once (the original phrasing's other reading) would make both direct test calls return the
   *same* value, making Task 5's assertion unachievable as written — do not use that shape.
4. Truncate the hostname portion before concatenating (`story-review.md` F-15) —
   `main.shedlock.locked_by` is `character varying(255)` (`V138__baseline_schema.sql:1206`), and
   `hostname + "-" + UUID` adds 37 fixed characters; an abnormally long hostname (over ~218 chars —
   rare, but Docker-derived or misconfigured hostnames are not bounded by convention) would make the
   acquire `INSERT`/`UPDATE` fail with SQLState `22001`, breaking **every** `@SchedulerLock`ed job in
   the app, not just this identity fix. A one-line bound (e.g. cap the hostname portion to a fixed
   length before appending the UUID suffix) removes the failure mode entirely.
5. Update `ShedLockConfig`'s class Javadoc to document the identity shape and why (same-host
   multi-instance safety during rolling deploys).
6. New test in the existing `ShedLockConfigIT` (`src/test/java/.../infrastructure/config/
   ShedLockConfigIT.java`) — after acquiring a lock via the real `lockProvider` bean, query
   `main.shedlock.locked_by` directly (mirroring `shedlockTable_existsAfterStartup`'s existing
   `jdbcTemplate` query style) and assert it is neither null/empty nor a bare hostname with no
   distinguishing suffix, **and** that two direct calls to the `@Configuration` class's `lockProvider(...)`
   method (bypassing Spring, per Task 3's design) return different `locked_by` values — genuinely
   achievable now that the value is computed inside the method body.
7. Re-run `ShedLockConfigIT` in full — zero regressions expected, all three existing tests still green.

---

## AC4 — `boot/axios.js`'s 401 handler: fix the hash-mode redirect defect via the shared helper

**The finding (re-verified against HEAD, still live).** `boot/axios.js:165-167`'s 401 response
interceptor does a hard `window.location.href = \`/login?redirect=${encodeURIComponent(currentPath)}&expired=true\``
on session expiry. `quasar.config.js:40` sets `vueRouterMode: 'hash'`, so this app's SPA routes live in
`location.hash` (e.g. `/#/login`) — a bare-path assignment to `/login` targets the **server** path, not
the SPA route, and never reaches `/#/login`. `route.query.expired`/`route.query.redirect` are both
undefined on the next render because hash-mode parses query only from the fragment, not the path. This
is the exact defect class skillars-deferred-125 AC3 just fixed at three Vue-component call sites
(`App.vue`, `useSession.js`, `MainLayout.vue`) via the new `src/frontend/src/utils/sessionRedirect.js`
helper — `boot/axios.js`'s own independent 401 handler was explicitly left out of that story's scope
(different file, no `router` access from a plain interceptor module) and is the fourth, structurally
identical instance of the same bug.

**A second, unflagged defect must be fixed in the same pass — the `{ expired: true }` flag is
hardcoded but the 401 gate covers two different error keys (`story-review.md` F-6).**
`axios.js:156`'s gate is `errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized'`.
`security.unauthorized` is genuinely **not** an expiry — it fires for `AuthorizationException`
(`ApiAdvice.java:241`) and for every non-expiry JWT failure via
`JWTAuthorizationFilter.mintHelpCode`/`writeUnauthorized`'s own `expired ? "security.sessionExpired" :
"security.unauthorized"` ternary. Today the conflation is harmless *only because of the very bug this
AC fixes* — in hash mode the `expired` param never reaches the SPA, so `LoginPage.vue:16`'s
`route.query.expired === 'true'` never sees it. Once this AC makes the param live, a plain unauthorized
401 (not an expiry) would render "Your session is no longer valid. Please sign in again" — a false
banner, the same class deferred-125's own code review already fixed for the logout call sites
(`sessionRedirect.js:17-20`). Fix: pass `{ expired: errorKey === 'security.sessionExpired' }`, not a
hardcoded `true`. (Verified *not* a similar issue: a bad-credentials login throws
`AuthenticationException` → `security.authError`, a different `errorKey` entirely, so this interceptor
path never fires on a failed login attempt.)

**A third, unflagged defect: this AC creates a same-tick double-navigation race with `App.vue`'s
existing handler that must be decided, not silently shipped (`story-review.md` F-3).**
`axios.js:145`'s `refreshExpiryState()` call, which runs **before** the 401 block, is synchronous:
`sessionManager.refreshExpiryState()` → `tick()` → a synchronous `window.dispatchEvent(new
CustomEvent('session:expired'))` — synchronous `dispatchEvent` runs every listener, including
`App.vue`'s, before returning. `App.vue`'s handler calls `pushLoginOrHardNavigate(router, { expired:
true })` without awaiting it (fire-and-forget), then execution falls straight back into `axios.js`'s
own 401 block, which — after this AC — calls `pushLoginOrHardNavigate` a **second time in the same
tick**. `sessionRedirect.js`'s "already on `/login`" guard cannot help here: `router.currentRoute
.value.path` is still the old route for both calls, since neither has resolved yet. Two concurrent
`router.push({ path: '/login' })` calls resolve with the second superseding the first —
`NAVIGATION_CANCELLED` for the loser, which **is** in `sessionRedirect.js`'s `DID_NOT_LAND` set — so
`hardNavigateToLogin` fires unnecessarily, forcing a full `window.location.href` + `reload()`. The
existing code already documents this dual-firing as an accepted, benign residual
(`axios.js:141-146`: "both paths tear down… but can produce two navigations") — true today only
because both paths currently do the identical trivial hard-nav assignment; this AC converts a benign
no-op race into an unnecessary full page reload, and must not do so silently.

**Resolution for this third defect (decided during this review-response pass, not re-taken live with
the owner — flag for confirmation if a different shape is preferred):** add a module-level in-flight
guard to `pushLoginOrHardNavigate` itself, alongside the existing `hardNavigated` one-shot flag, rather
than a bespoke axios-side "skip if I just dispatched `session:expired`" special case. A generic
in-flight guard fixes the race for *any* two of the four call sites that might ever fire in the same
tick (this one is simply the first concrete case), not only this specific coupling between
`refreshExpiryState()` and the 401 handler, and does not require `boot/axios.js` to know anything about
`App.vue`'s internal event wiring. Shape: a module-level `let inFlight = null` set to the current call's
promise at entry and cleared in a `finally`; a call that arrives while `inFlight` is already set awaits
and returns that same in-flight promise instead of issuing a second `router.push`.

**Owner decision (2026-09-21, `AskUserQuestion`): reuse `sessionRedirect.js`'s
`pushLoginOrHardNavigate(router, { expired })`** — one consistent redirect mechanism app-wide (soft
`router.push` first, hash-aware hard-navigation fallback via `router.resolve(...).href` +
`reload()`) — rather than a standalone minimal hash-path patch that would leave two different redirect
mechanisms in the codebase for the identical event.

**Threading `router` into `boot/axios.js`, confirmed against the actual file structure during story
creation.** `api.interceptors.response.use(...)` (containing the 401 handler, `axios.js:122` onward) is
registered at **module scope** — it runs once at module import time. `export default defineBoot(async
() => {...})` (`axios.js:201`) runs later, when Quasar invokes the boot file with its context object
(`{ app, router, store, ... }` per Quasar's boot-file API — no boot file in this codebase currently
destructures `router`, though `i18n.js` shows the destructuring pattern for `{ app }`). The interceptor
therefore has no `router` reference available at the point it is defined. Fix shape: change
`defineBoot(async () => {...})` to `defineBoot(async ({ router }) => {...})`, store the injected
`router` in a module-level variable set as the first statement inside that callback (before the
existing `await setBrowserFingerprint()` call), and have the 401 handler read that module-level
variable at request time (by which point boot has already run, in every real request path) rather than
at module-definition time. Guard the handler for the theoretical case the interceptor fires before boot
completes (defensive — should not happen in Quasar's normal boot sequence, but confirm at
implementation time whether it is reachable, e.g. from the `setBrowserFingerprint()` call itself
failing with a 401, and keep a minimal hash-aware fallback for that edge case rather than silently
no-op-ing).

### Tasks

1. Re-verify `boot/axios.js`'s current structure (module-scope interceptor registration vs.
   `defineBoot`'s callback timing) against HEAD before implementing.
2. Change `defineBoot(async () => {...})` to receive `{ router }`, store it in a module-level variable
   set at the top of the callback.
3. In `sessionRedirect.js`, add the module-level in-flight guard described above
   (`story-review.md` F-3) — a call to `pushLoginOrHardNavigate` that arrives while another is already
   in flight awaits and returns the same promise rather than issuing a second `router.push`. Add a
   dedicated test for "a second call arrives before the first settles" in `sessionRedirectSpec.js`
   before wiring this AC's new call site, so the guard is proven in isolation first.
4. Replace the 401 handler's `window.location.href = ...` hard navigation with a call to
   `pushLoginOrHardNavigate(router, { expired: errorKey === 'security.sessionExpired' })` — **not**
   a hardcoded `{ expired: true }` (`story-review.md` F-6: `errorKey === 'security.unauthorized'` is
   also in this gate and is not an expiry; hardcoding `true` would render a false "session expired"
   banner for a plain unauthorized 401 once this AC makes the query param reach the SPA for the first
   time) — imported from `src/utils/sessionRedirect`, guarded by a check that `router` is actually set
   (see the edge-case note above); keep a minimal hash-aware literal fallback (`` `/#/login?...` ``) for
   the case it is not, rather than silently doing nothing.
5. Confirm `stopSessionMonitoring()`/`cleanup()`/`deleteUserCookie()` (the existing teardown calls
   immediately above the redirect) are unaffected — this AC only changes the navigation call itself.
6. **Test approach — name it explicitly, do not point at a precedent that does the opposite
   (`story-review.md` F-7).** Every existing spec that touches this territory
   (`AppSpec.js:20`, `useSessionSpec.js:22`, `MainLayoutSpec.js:22`, and others) works by
   `vi.mock('src/boot/axios', ...)` — mocking the real module *away* entirely. No spec in this suite has
   ever loaded the real `boot/axios.js`, and no boot-file spec exists for any boot file
   (`src/boot/__tests__/` does not exist) — "check whether one exists for `i18n.js`/`theme.js` to
   mirror" resolves to none. Loading the real module also pulls in `src/boot/i18n`,
   `@rajesh896/broprint.js`, and the real `src/plugins/sessionManager` interval logic, and the response
   interceptor itself is an inline, unexported closure — reaching it needs either axios's own internals
   (`api.interceptors.response.handlers[0].rejected`) or a mock HTTP adapter (`axios-mock-adapter` is
   **not** a current dependency — adding it, or reaching the handler via axios's own
   `interceptors.response.handlers` array, are the two real options; pick one and note the choice).
   The `#q-app/wrappers` import itself is not a blocker — `.quasar/tsconfig.json`'s `paths` entry lets
   `vite-tsconfig-paths` resolve it under Vitest already. This test needs the `frontend-tests` PR label,
   per this project's established convention for any change touching frontend test files.
7. Re-run the full frontend suite (`npm run test:unit`) — zero regressions expected.

---

## AC5 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story genuinely
fixes; annotate `[DECIDED: accepted risk — skillars-deferred-126]` what it deliberately does not fix;
leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-125-outbox-claim-isolation-radar-window-
   margin-and-session-redirect-fixes (2026-09-21)`:
   - **Bullet 1** (clock-skew stale-claim comparison) — delete outright, fully fixed by AC1.
   - **Bullet 2** (no `statement_timeout`/`lock_timeout` on app connections, `RadarCompositeDlqProcessor`
     per-row cost unbounded) — delete outright if AC2 ships a genuine bound; if implementation finds the
     mechanism impractical for some reason discovered mid-implementation, annotate
     `[DECIDED: accepted risk — skillars-deferred-126]` instead and record why. Whichever disposition,
     record the concrete `GdprErasureService` conflict source this story's creation identified (not in
     the original bullet's text) in the closing note, so a future reader does not have to re-derive it.
     **Also reconcile with the structurally identical sibling this story's `story-review.md` audit
     found (F-11):** `AdminCoachEnforcementService.deleteStrike`'s bulk `@Modifying` delete
     (`CoachReliabilityStrikeRepository.deleteByIdAndCoachId`) is the exact same bug class — a native
     DML statement's implicit row-lock wait with no `NOWAIT`/`lock_timeout` — and is currently
     `[DECIDED: accepted risk — skillars-deferred-123]` (`deferred-work.md:~2423-2438`), whose own
     rationale says it "would need revisiting if a future change… makes the winning transaction's own
     work unbounded." Do not let this story fix one instance while leaving the sibling's `[DECIDED]`
     note unconnected — add a one-line cross-reference to that bullet (or its replacement) stating why
     the radar case warranted a bound while the strike delete's acceptance still stands: the GDPR
     erasure transaction genuinely has unbounded work ahead of it (no lock-retry budget bounds it),
     while the strike delete's winning transaction is itself already lock-retry-bounded
     (`PessimisticLockRetryer`'s ~3.2s worst-case budget).
   - **Bullet 3** (ShedLock `locked_by` = hostname only) — delete outright, fully fixed by AC3.
   - **Bullet 4** (`boot/axios.js`'s hash-mode 401 fallback defect) — delete outright, fully fixed by
     AC4.
3. The section header itself should become empty once all four bullets are resolved (delete or
   annotate) — if bullet 2 ends up `[DECIDED]` rather than deleted, keep the header with that one
   bullet remaining, matching this file's established "annotate, don't delete a `[DECIDED]` item"
   convention; otherwise remove the now-empty header entirely.
4. Add a `## Last audit: 2026-09-21 (skillars-deferred-126 dev-story completion)` narrative section, in
   this file's established style, summarizing what closed and what (if anything) was accepted-and-
   documented instead.
5. Grep-sweep every file this story touched (`RadarCompositeDlqProcessor`, `VideoDeletionOutboxProcessor`,
   `RadarCompositeDlqRepository`, `VideoDeletionOutboxRepository`, `RadarCompositeCalculationService`,
   `PlayerRadarBaselineRepository`, `ShedLockConfig`, `boot/axios.js`, `sessionRedirect.js`,
   `ConfigBounds`) against the rest of the ledger for any other stale reference this story's changes
   might affect — re-confirm each hit is either unrelated or already correctly annotated, per this
   series' own standard practice. This list was widened from the story's original draft
   (`story-review.md` F-22 found `PlayerRadarBaselineRepository` and `sessionRedirect.js` missing) and
   must **also** include `CoachReliabilityStrikeRepository`/`AdminCoachEnforcementService`
   (`story-review.md` F-11) so the reconciliation task above actually surfaces during the sweep rather
   than being missed by it.
6. Add the formal `[DECIDED]` tag the Provenance section's own audit found missing
   (`story-review.md` F-21): the untagged `@SchedulerLock PT12H` sizing bullet under
   `## Deferred from: code review of story-115 (2026-09-16)` (`deferred-work.md:2324-2326`) reads as
   decided in prose but carries no formal tag, unlike its two correctly-tagged neighbors in nearby
   scheduler-lock sections. Tag it `[DECIDED: accepted risk — story-115, no code change needed,
   VideoLifecycleScheduler's own lock sizing is tunable by operator]` (or equivalent wording matching
   this file's established `[DECIDED]` phrasing), without changing its substance — this story does not
   touch `VideoLifecycleScheduler` and is not the one deciding this; it is only giving an
   already-effectively-made decision its proper tag, found while re-confirming this story's own
   provenance claims.

---

### Review Findings

`/bmad-code-review` 2026-09-21 — four parallel layers (Blind Hunter, Edge Case Hunter, Acceptance
Auditor, `/txn-and-concurrency-audit`). 30 raw findings → 3 decision-needed, 17 patch, 7 deferred,
3 dismissed as verified false positives. Every finding below was independently re-verified against
actual source before classification; the three dismissed ones are recorded at the end of this
section so a future review does not re-raise them.

#### Decision needed

- [x] [Review][Decision] **Both AC1 "proves DB-time" ITs are vacuous — they pass unchanged against the pre-fix implementation** — All four review layers raised this independently. `RadarCompositeDlqRepositoryIT.resetStaleClaimed_comparesAgainstDatabaseClockNotJvmClock` (`:129-141`) and `VideoDeletionOutboxProcessorIT` (`:401-421`) seed `claimed_at = now() - interval '30/40 minutes'` and sweep with a 10/20-minute window. The test JVM and the Testcontainers Postgres share one host clock, so the *old* predicate (`claimed_at < :deadline`, `deadline = Instant.now().minus(window)`) matches the same row and returns the same `reset == 1`. No clock is ever skewed, so the one behaviour AC1 exists for is untested. The two `isCloseTo(Instant.now(), within(30, SECONDS))` assertions (`RadarCompositeDlqRepositoryIT:76`, `VideoDeletionOutboxProcessorIT:335`) are equally vacuous — the old `:now` bind was itself an `Instant` from the same run. AC1 Task 6's prescribed mutation check is unachievable with the delivered width-only signature and is recorded nowhere. **Options:** (a) replace with a genuine boundary test (`now() - interval '9m59s'` → not reset; `now() - interval '10m1s'` → reset) and correct the Javadoc/story claims to drop "proves DB-time"; (b) induce real skew (e.g. a second connection with a shifted session clock) for a truly discriminating test; (c) keep the tests, fix only the false claims. AC1's regression protection is currently signature-shape only. — **decided (a)**: both ITs' vacuous test replaced with a genuine boundary-test pair (`..._boundary_justUnderWindow_notReclaimed` / `..._boundary_justOverWindow_reclaimed`); AC1 Task 6 annotated with what was actually done and why the originally-prescribed deliberate-skew test was not pursued [src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java, src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java]
- [x] [Review][Decision] **`lock_timeout` is per-statement, so AC2's bound is cumulative — `2 × skills × timeout` per call, and the 120s ceiling already exceeds the margin it must fit inside** — `RadarCompositeCalculationService.java:183-209` runs two lock-taking statements per skill (`upsertComposite` + `insertBaselineIfAbsent`). `ConfigBounds` sizes the 120s ceiling against `MAX_RUN_DURATION` (8m) and "the AC1 stale-claim margin (5 minutes)", but `RadarCompositeDlqProcessor.java:81` states the real constraint in its own words: anything *"longer than `lockAtMostFor - MAX_RUN_DURATION` (a 2-minute margin here) still overruns the lock."* One statement at the ceiling consumes that entire margin; a two-skill row at the ceiling (480s) overruns the 5-minute stale window and triggers exactly the duplicate `recalculateComposite` AC1 exists to prevent. **Options:** (a) lower `max` to ~30s — but the story widened 30→120 specifically to satisfy `ConfigStartupAssertionTest`'s "100 is inside every range" fixture, so that fixture must change too; (b) keep 120s and bound the loop cumulatively in code (track elapsed, abort the remaining skills); (c) keep 120s and correct the sizing Javadoc to state the real per-statement/cumulative semantics and the 2-minute margin. Related: the Javadoc's promise that "an operator can widen it" is unreachable as shipped — see the deferred item on the unseeded config key. — **decided (b)**: `CUMULATIVE_LOCK_WAIT_BUDGET` (= the 120s ceiling) tracked across the per-skill loop; each skill's own `lock_timeout` is shrunk to whatever budget remains, and the loop aborts (throwing, recovered by the existing DLQ retry path) once the remaining budget would fall below the floor [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java]
- [x] [Review][Decision] **AC1's skew fix is half-applied: `next_retry_at` eligibility stays on the app clock, so a skewed instance short-circuits backoff and prematurely dead-letters rows** — `claimPendingBatch` stamps `claimed_at = now()` (DB clock) but keeps `next_retry_at <= :now` (app clock) in the *same statement* (`VideoDeletionOutboxRepository.java:36,39`; `RadarCompositeDlqRepository.java:35,38`). Interleaving: instance A writes `next_retry_at = Instant.now() + 8min` from its own clock (`VideoDeletionOutboxProcessor:384`); instance B's clock runs 10 minutes ahead; B judges the row eligible ~10 minutes early, re-attempts against a still-down provider, and `attempts` reaches `max_attempts` well inside the intended backoff schedule — the row is marked `DEAD` and the Bunny.net asset is never deleted, with no further retry path. The diff documents this scope limit as deliberate, but the residual is the same defect class AC1 claims to close, with a worse terminal outcome. **Options:** (a) accept as documented residual and tag it in `deferred-work.md`; (b) move eligibility to DB time too, which means touching every writer of `next_retry_at`; (c) keep app-clock eligibility but make the dead-letter decision skew-insensitive (e.g. key `attempts` exhaustion on elapsed DB time rather than attempt count). — **decided (a)**: tagged `[DECIDED: accepted risk — skillars-deferred-126]` in `deferred-work.md`'s own code-review-of-this-story section, no code change [_bmad-output/implementation-artifacts/deferred-work.md]

#### Patch

- [x] [Review][Patch] Config floor `min = 1L` contradicts the invariant its own Javadoc states ("must stay ABOVE Postgres's `deadlock_timeout`, default 1s") — a stored `1` is in-range and makes a genuine deadlock a coin flip between `40P01` and `55P03`; should be `2L` [src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:245-248] — fixed
- [x] [Review][Patch] `recalculateComposite`'s Javadoc claims the lock-ordering deadlock is "now bounded by the transaction-scoped `lock_timeout`" — false: a deadlock was already bounded by Postgres's `deadlock_timeout`; `lock_timeout` only makes a waiter abort itself and cannot break a circular wait [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:88-92] — fixed
- [x] [Review][Patch] `make_interval` rationale is factually wrong and now duplicated in two production files — Postgres's signature is `make_interval(…, secs double precision)`, so binding a Java `long` performs the very `bigint → double precision` cast the comment claims it avoids [src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java:68-72, src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java:82-86] — fixed
- [x] [Review][Patch] AC1 Task 4 missed a named site — comment still reads "claimed_at stamped with this tick's own claim instant", directly contradicted four lines below by the deferred-126 comment; `RadarCompositeDlqRepository.java:16-19` inherits it by reference [src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java:16-17] — fixed
- [x] [Review][Patch] `inFlight` is assigned *after* the guarded IIFE has already started — a synchronous throw from `router.push` runs `finally { inFlight = null }` before `inFlight = promise` executes, pinning the guard to a settled promise forever and silently disabling every later redirect (`hardNavigated` is already true, so the hard fallback is a no-op too); assign before invoking [src/frontend/src/utils/sessionRedirect.js:121-142] — fixed: the IIFE now yields one microtask before doing anything risky, guaranteeing `inFlight = promise` has already run first; regression test added
- [x] [Review][Patch] `inFlight` coalescing discards the second caller's `expired` and `redirect` — the guard returns before `buildRedirectQuery` runs, so a deliberate logout racing an expiry dispatch renders a false "Your session has expired" banner (the exact bug deferred-125 fixed), and in the other direction a genuine expiry loses its banner [src/frontend/src/utils/sessionRedirect.js:120-124] — fixed: a module-level `pendingExpired` flag folds in any same-tick caller's `expired:true` before the query is built (upgrade-only, never downgrades a genuine expiry); regression test added
- [x] [Review][Patch] Pre-boot fallback builds `redirect` from `pathname + hash` (`/#/dashboard`) while the shared helper uses `router.currentRoute.value.fullPath` (`/dashboard`); `LoginPage.vue:161-164` accepts the malformed value and pushes it as a route, landing the user on root after login [src/frontend/src/boot/axios.js:192-193] — fixed
- [x] [Review][Patch] Pre-boot fallback omits the `window.location.reload()` that `hardNavigateToLogin` documents as mandatory — the app is served at `/`, so assigning `/#/login?…` changes only the fragment, a same-document `hashchange` with no unload, leaving the broken in-memory state the fallback exists to escape [src/frontend/src/boot/axios.js:193] — fixed
- [x] [Review][Patch] The 401 call site neither awaits nor catches the returned promise, so a throw outside the inner IIFE's `try` becomes an unhandled rejection with the session already torn down and no redirect; attach a `.catch()` [src/frontend/src/boot/axios.js:186] — fixed
- [x] [Review][Patch] `ShedLockConfigIT` asserts the *converse* of the production invariant — two hand-constructed providers producing different identities is a property of `UUID.randomUUID()`, not of the design. Nothing asserts that two `lock()` calls through the real bean write the *same* `locked_by`, which is what the unlock/extend predicates depend on; a regression to per-acquire identity would pass [src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java:107] — fixed: new `lockProviderBean_sameInstanceAcrossTwoAcquisitions_writesTheSameLockedByBothTimes` test added, asserting the actual invariant
- [x] [Review][Patch] `deadlockBetweenCompositeUpsertAndGdprStyleDelete_translatesToDistinctException` exercises no production code (raw `jdbcTemplate` UPDATE/DELETE; never calls `recalculateComposite`, never sets `lock_timeout`), its name says "DistinctException" while it asserts the *same* class as the sibling test, and its Javadoc claims "the identical lock type (native `INSERT ... ON CONFLICT` row lock)" while using a plain `UPDATE` [src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java:215-283] — fixed: renamed to `..._translatesToSameTopLevelExceptionClassButDifferentCause`, thread A's write rewritten to the exact `INSERT ... ON CONFLICT DO UPDATE` shape `upsertComposite` uses, Javadoc corrected
- [x] [Review][Patch] The per-failure-mode *cause* claims the Javadoc says were "empirically confirmed" are pinned by no assertion — both tests assert only `isInstanceOf(PessimisticLockingFailureException.class)`; add `hasCauseInstanceOf(org.hibernate.PessimisticLockException.class)` / the PSQL deadlock cause [src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java] — fixed
- [x] [Review][Patch] The bounded-time IT asserts only `isLessThan(15s)` with no lower bound, so it passes whether the exception came from the `set_config`-bounded upsert wait or from an unrelated instant `NOWAIT` failure on `player_profiles`; add `isGreaterThanOrEqualTo(Duration.ofSeconds(2))` [src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java:190-210] — fixed
- [x] [Review][Patch] New `getBoundedLong` call site breaks `ConfigBounds`' own documented convention ("Each call site still passes its `[min, max]` literally … Mockito `verify(...)` pins the exact numbers") — it passes `.min()`/`.max()` accessors, and the unit test stubs `anyLong()` with no `verify(...)`, so the 5s default and the 1/120 bounds are pinned by nothing [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:143-146, src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java:96-99] — fixed: call site now passes `2L, 120L` literally; new test `onRadarEntrySubmitted_readsLockTimeoutConfigWithTheDocumentedBoundsLiterally` pins them via `verify(...)`
- [x] [Review][Patch] AC5 cross-reference points at a section the same edit deleted — the pointer resolves to nothing; retarget it at `## Last audit: 2026-09-21` [_bmad-output/implementation-artifacts/deferred-work.md:2450] — fixed
- [x] [Review][Patch] `ShedLockConfigIT` hard-codes the 218-char truncation bound instead of referencing `ShedLockConfig.MAX_HOSTNAME_LENGTH` (currently `private`); if the column width or UUID-suffix assumption changes the assertion drifts silently rather than failing [src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java] — fixed: constant made package-private, test references it directly
- [x] [Review][Patch] AC4 Task 6's `frontend-tests` PR-label requirement is recorded nowhere in the story — without the label neither `axiosSpec.js` nor the in-flight-guard spec nor the `vitest.config.mjs` alias fix is exercised in CI at all (`mvn verify` never invokes Vitest) [_bmad-output/implementation-artifacts/skillars-deferred-126-…-fixes.md, Dev Agent Record] — fixed: explicit callout added to this story's own "Validation performed" section

#### Deferred (pre-existing — not introduced by this change)

- [x] [Review][Defer] **GDPR erasure and `recalculateComposite` are not serialized — an erased player's radar rows can be resurrected after the erasure commits** [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:152-207, src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:194-203] — deferred, pre-existing
- [x] [Review][Defer] **A GDPR erasure can be the deadlock victim and roll back wholly** — `erase()` is `REQUIRES_NEW`, so losing the circular wait against a background recalculation discards the `main.user` anonymisation, message/review deletions and blob-deletion outbox rows, and the request goes to `markFailed`. The real fix (same `player_profiles` lock, or matching table order) is not in this diff [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:75,201-202] — deferred, pre-existing
- [x] [Review][Defer] `now()` is `transaction_timestamp()`, so the claim stamp and sweep deadline are correct only because each repository call happens to run in its own short transaction — adding `@Transactional` to `process()` would freeze both at the outer transaction's start time and erode the `MAX_RUN_DURATION < lockAtMostFor < STALE_CLAIM_WINDOW` margin from both ends; `clock_timestamp()` would remove the hidden coupling [src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java:36,93] — deferred, latent (correct on today's call graph)
- [x] [Review][Defer] The new config key has no Flyway seed, so `PUT /api/config/values/{key}` 404s (`ConfigService.updateConfig` does `findByKey(key).orElseThrow`) and the documented operator-tuning path is unreachable — the `HAS_CODE_DEFAULT` siblings `rate_limit_bucket_ttl_hours` and `radar_composite_dlq_max_attempts` are equally unseeded, so this is a project-wide convention gap [src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:203] — deferred, pre-existing
- [x] [Review][Defer] `@Scheduled` runs on Spring Boot's default single-thread scheduler (no `spring.task.scheduling.pool.size`, no `SchedulingConfigurer` bean), so a 12-minute `MAX_RUN_DURATION` run starves all 43 other scheduled methods — undermining the lock-duration arithmetic this story's AC1/AC2 reasoning leans on [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java:85,154] — deferred, pre-existing
- [x] [Review][Defer] Hostname truncation uses `String.substring`, which can split a surrogate pair and emit a lone high surrogate that is not UTF-8-encodable, breaking lock acquisition for every `@SchedulerLock` job [src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:63-64] — deferred, very low likelihood
- [x] [Review][Defer] AC1 Task 3's required `EXPLAIN`/index-coverage confirmation for the rewritten `claimed_at < now() - make_interval(...)` predicate was not performed or recorded anywhere [_bmad-output/implementation-artifacts/skillars-deferred-126-…-fixes.md, Dev Agent Record] — deferred, recording gap

#### Dismissed as verified false positives (do not re-raise)

- `claimed_at = now()` implicit `timestamptz → timestamp` cast — **false positive**: `V144__outbox_dlq_claimed_at.sql:40,43` declares the column `timestamp with time zone` on both tables.
- Fallback emits `expired=false` as a truthy string → false banner — **false positive**: `LoginPage.vue:16` gates the banner on `route.query.expired === 'true'`, a strict string comparison.
- `setLockTimeoutSecondsConfig` leaks a committed config row across tests — **false positive**: `DatabaseResetTestExecutionListener.beforeTestMethod` truncates application tables, restores Flyway reference data, and calls `configService.scheduledRefresh()` → `refreshCache()` before every test method.

---

## Dev Notes

**Cross-AC dependencies:** AC1 touches both `RadarCompositeDlqRepository`/`RadarCompositeDlqProcessor`
and `VideoDeletionOutboxRepository`/`VideoDeletionOutboxProcessor`; AC2 touches
`RadarCompositeCalculationService`/`PlayerRadarCompositeRepository`/`PlayerRadarBaselineRepository`/
`ConfigBounds` — a different class in the same package as AC1's Radar half, but an independent fix (no
shared method/field). AC3 (`ShedLockConfig`, infrastructure package) and AC4 (frontend, `boot/axios.js`)
are both fully independent of AC1/AC2 and of each other. Sequence as separate commits so any one AC can
be reverted independently, mirroring this series' established convention.

**No new Flyway migration in this story — this is now a resolved decision, not merely an assumption.**
AC2's original draft would have required one (mirroring a `failFast`/non-`HAS_CODE_DEFAULT` precedent
literally implies a seeded value is mandatory); `story-review.md` F-1 caught this, and AC2 Task 2
resolves it by using the 4-arg `getBoundedLong` + `HAS_CODE_DEFAULT` route instead, which needs no seed
row. Confirm this remains true at implementation time regardless — if any AC's implementation turns out
to need a migration for an unrelated reason, re-derive the next free version against the actual
`src/main/resources/db/migration/` directory at that time, not any number cited anywhere in this file.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project. Run each AC's own targeted test class(es) locally during implementation as needed;
`mvn compile`/`mvn test-compile` are fine, `mvn verify` is not. For AC4, run `npm run test:unit` (or the
project's equivalent Vitest invocation — confirm the actual script name in
`src/frontend/package.json` at implementation time) locally.

**AC2's concurrency test needs a genuinely held lock, not a mock — and the exact class/fixture shape is
already resolved, not left to "locate at implementation time."** `RadarCompositeCalculationServiceConcurrencyIT`
already exists in this exact package and already holds a real competing lock open across an
`ExecutorService` on a second connection (`:38`, `:60`, `:65-67`) — that is the class to extend, not a
new one, and not `AdminCoachEnforcementIsolationRuntimeIT` (a different module, cited in this story's
earlier draft before `story-review.md` F-17 found the closer precedent). A mocked repository call
cannot exercise Postgres's own lock-wait/timeout machinery regardless of which class hosts the test.
Budget real wall-clock time in the new test proportional to the configured timeout value; do not set the
timeout so low in test config that the assertion becomes flaky against CI's real scheduling jitter, and
do not set it so high that the test suite's own runtime budget suffers — confirm a sensible test-profile
override for the new config key if the production default is too slow for a fast test loop.

**AC3's ShedLock hostname resolution may behave differently across environments.** `docker-compose.yml`
containers get Docker's own container-id-derived hostname by default unless explicitly overridden —
confirm what value `Utils.getHostname()` actually returns in this project's containerized runtime
(dev/uat/prod compose files) before assuming a human-readable value, and note in the Javadoc that
"hostname" here means "whatever ShedLock's own resolver reports," which may itself be a
randomly-generated container ID rather than the VPS's own hostname — still valid for uniqueness
purposes (and still bounded by the length truncation AC3 Task 4 adds), just worth setting the right
expectation for anyone reading the `main.shedlock` table during an incident.

**CI context-count ceiling (backend ACs only):** `assert-context-count.sh`'s Spring-context ceiling was
44 as of skillars-deferred-124 AC2 (`pr-build.yml`'s call site) — confirm the current value at
implementation time (it may have moved since). AC1/AC2's new tests reuse existing test classes'
existing context configuration where possible; confirm against the CI failure message if a new context
fork is needed, and bump the ceiling with a dated justification comment mirroring the existing history
in that script if so.

---

## File List (reconciled against the actual final diff)

**Production code:**
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutbox.java` (AC1 — entity Javadoc)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqEntry.java` (AC1 — entity Javadoc)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC2, new `HAS_CODE_DEFAULT` bounded key `platform.radar_composite_lock_timeout_seconds` — no migration)
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java` (AC3)
- `src/frontend/src/boot/axios.js` (AC4)
- `src/frontend/src/utils/sessionRedirect.js` (AC4 — the in-flight guard addition, see AC4 Task 3)
- `src/frontend/vitest.config.mjs` (AC4 — `#q-app/wrappers` resolve.alias; see Dev Agent Record: a real
  blocker the story's own hedge on this point turned out to understate, discovered while writing the
  first boot-file spec this suite has ever had)

Not touched, despite being on the story's own original (expected) File List — confirmed unnecessary
during implementation, not overlooked: `PlayerRadarCompositeRepository.java` and
`PlayerRadarBaselineRepository.java` (AC2's lock-timeout bound is issued once via a plain
`entityManager.createNativeQuery(...)` call in `RadarCompositeCalculationService` itself, ahead of
the per-skill loop — no repository-level home was needed for it).

**Tests:**
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java` (AC2 — unit-level constructor/mock wiring update for the new `ConfigService` dependency)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java` (AC2 — two new tests: lock-timeout-bounded wait, and a genuine deadlock reproduction)
- `src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java` (AC3)
- `src/frontend/src/utils/__tests__/sessionRedirectSpec.js` (AC4 — new in-flight-guard test)
- `src/frontend/src/boot/__tests__/axiosSpec.js` (AC4 — new; no existing boot-file spec precedent in this suite)

Not touched: `RadarCompositeDlqProcessorTest.java` — no direct `resetStaleClaimed` call site to update
(only narrative Javadoc referencing it, unaffected by the signature change).

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/skillars-deferred-126-stale-claim-db-time-radar-lock-bound-shedlock-identity-and-axios-hash-redirect-fixes.md` (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review)

## Dev Agent Record

### Completion Notes

All 4 ACs plus the standard AC5 ledger closeout implemented and verified. Each AC's own re-verification
task (re-read current HEAD before implementing) was performed; no material drift found from the story's
own line citations.

- **AC1 (DB-time stale-claim fix):** `claimPendingBatch`'s `claimed_at` stamp moved from the `:now`
  bind parameter to a bare `now()` in both repositories' native `UPDATE`; `resetStaleClaimed` on both
  repositories changed from a Java-computed `Instant deadline` parameter to a `staleWindowSeconds`
  width, computing the deadline inside the SQL via `now() - make_interval(secs => :staleWindowSeconds)`.
  All five Javadoc sites the AC named were updated. New IT tests
  (`resetStaleClaimed_comparesAgainstDatabaseClockNotJvmClock` on both the Radar and Video sides) seed
  `claimed_at` via a direct SQL `now() - interval`, not a Java `Instant`, to prove the comparison is
  genuinely DB-time — the one behavior a purely app-clock-based fixture cannot distinguish from the old
  implementation. `claimPendingBatch`'s stamped value is also sanity-bounded against real wall-clock
  time in both existing round-trip tests.
- **AC2 (radar upsert lock-timeout bound):** `RadarCompositeCalculationService.recalculateComposite`
  now reads a new `platform.radar_composite_lock_timeout_seconds` config value (4-arg `getBoundedLong`,
  default 5s, `HAS_CODE_DEFAULT` — no migration) BEFORE taking the pessimistic player-row lock, then
  issues `SELECT set_config('lock_timeout', ?, true)` (a genuine bind parameter, unlike a literal `SET
  LOCAL`, which accepts none) once, after that lock and before the per-skill loop. Two new
  `RadarCompositeCalculationServiceConcurrencyIT` tests, both against real Testcontainers-backed
  Postgres contention, not mocks: (1) a competing `SELECT ... FOR UPDATE` held on a pre-seeded,
  committed `player_radar_composites` row (with a pre-seeded `radar_assessments` row so the per-skill
  loop genuinely runs) proves the upsert fails within a bounded wall-clock time once the configured
  timeout elapses; (2) a deliberately reproduced genuine deadlock — mirroring
  `GdprErasureService.deletePlayerDevelopmentData`'s opposite table order via direct repository/JDBC
  calls under full latch control, since `recalculateComposite` itself has no injection point between
  its two internal upsert calls. Both failure modes were empirically confirmed to translate to the SAME
  Spring exception class, `org.springframework.dao.PessimisticLockingFailureException` (cause `org.
  hibernate.PessimisticLockException` for the plain timeout; cause `org.postgresql.util.PSQLException`
  "ERROR: deadlock detected" for the genuine deadlock) — documented in the method's own Javadoc, which
  corrects this story's own earlier (unverified) guess of `QueryTimeoutException`. The new config key's
  `max` bound was set to 120s (not the originally-drafted 30s) after discovering
  `ConfigStartupAssertionTest`'s existing "100 is inside every ConfigBounds range" fixture invariant —
  a real, previously-passing regression test this AC's own bound would otherwise have broken; 120s
  remains comfortably below both `MAX_RUN_DURATION` (8 min) and the AC1 stale-claim margin (5 min), and
  the story's own "single-digit seconds" language is preserved as the *default*, not the ceiling.
- **AC3 (ShedLock instance identity):** `ShedLockConfig.lockProvider` now sets `.withLockedByValue(...)`
  to `hostname + "-" + UUID.randomUUID()`, hostname resolved via `Utils.getHostname()` and truncated to
  fit `main.shedlock.locked_by`'s `varchar(255)`, computed as a local expression inside the `@Bean`
  method body (not a class-level field) so two direct test-side calls each get their own fresh identity.
  Two new `ShedLockConfigIT` tests confirm `locked_by` is neither blank nor a bare hostname, and that
  two direct calls to the method produce different values.
- **AC4 (axios.js hash-redirect fix):** `boot/axios.js`'s 401 handler now threads the boot-injected
  `router` (via `defineBoot(({ router }) => ...)`) into a module-level variable read at request time,
  and calls `sessionRedirect.js`'s `pushLoginOrHardNavigate(router, { expired })` — `expired` gated on
  the actual `errorKey` (`security.sessionExpired` only), not hardcoded `true`, so a plain
  `security.unauthorized` 401 no longer shows a false "session expired" banner once the hash-mode fix
  makes the query param reach the SPA for the first time. `sessionRedirect.js` gained a module-level
  in-flight-promise guard (checked before the "already on /login" check, since the latter cannot
  distinguish "elsewhere" from "a navigation to /login is already underway" mid-flight) closing a
  same-tick double-`router.push` race this fix would otherwise have introduced between `App.vue`'s
  `session:expired` listener and this interceptor — proven in isolation first by a dedicated
  `sessionRedirectSpec.js` test using an async router guard to genuinely land a second call while the
  first's `router.push` is still pending. A new `axiosSpec.js` — the first boot-file spec this suite has
  ever had — reaches the module-scope response interceptor via axios's own
  `api.interceptors.response.handlers[0].rejected` (no `axios-mock-adapter` dependency added) and calls
  the real `defineBoot`-wrapped callback directly (`defineBoot` is a plain identity wrapper in this
  project's pinned `@quasar/app-vite`, confirmed by reading its source). Doing so surfaced a genuine,
  previously-undiscovered gap the story's own hedge on `#q-app/wrappers` understated: `vite-
  tsconfig-paths` resolves the import *specifier* fine, but `.quasar/tsconfig.json`'s own `paths` entry
  points it at a pure `.d.ts` type-declaration file with no runtime `defineBoot` export — every prior
  spec avoided this because every one of them mocks `src/boot/axios` away entirely. Fixed with a
  `resolve.alias` in `vitest.config.mjs` pointing `#q-app/wrappers` at the real runtime module the
  public `@quasar/app-vite/wrappers` subpath already resolves to (same identity-wrapper behavior, only a
  resolution-path fix) — this durably unblocks any future boot-file spec, not just this one.
- **AC5 (ledger closeout):** All four bullets under `## Deferred from: code review of
  skillars-deferred-125…` deleted outright (all four genuinely fixed, none left `[DECIDED]`) and the
  now-empty header removed, replaced by a `## Last audit: 2026-09-21` narrative section. The
  `AdminCoachEnforcementService.deleteStrike` `[DECIDED: accepted risk — skillars-deferred-123]`
  sibling gained a one-line cross-reference explaining why its acceptance still stands (its winning
  transaction is itself already lock-retry-bounded, unlike the radar case's GDPR-erasure conflict
  source, which has genuinely unbounded work ahead of it). The untagged `@SchedulerLock PT12H` sizing
  bullet under `## Deferred from: code review of story-115` received the formal `[DECIDED]` tag its
  prose disposition never had. The grep-sweep of every touched class name across the rest of the ledger
  also found one more genuinely relevant hit not named in the story's own scope: the
  `[DECIDED: accepted risk — skillars-deferred-125]` "`MAX_RUN_DURATION` sampled only between rows"
  bullet cited `recalculateComposite` going through `PessimisticLockRetryer` across three repositories
  as part of its accepted-risk mechanism — AC2 now bounds two of those three (previously unbounded),
  narrowing (not closing) that residual; annotated in place rather than left to silently understate
  the current state.

### Validation performed

Targeted tests only, per this project's `docs/validation-strategy.md` convention — no `mvn verify` run.
Backend (re-run after applying the `### Review Findings` fixes above, `mvn -o test -Dtest=...`):
`ShedLockConfigIT` (6), `RadarCompositeDlqRepositoryIT` (6), `RadarCompositeCalculationServiceConcurrencyIT`
(3), `VideoDeletionOutboxProcessorIT` (15), `RadarCompositeCalculatorTest` (13, +1 new),
`RadarCompositeDlqProcessorTest` (12), `ConfigBoundsEnumCoverageTest` (5), `ConfigStartupAssertionTest`
(21) — all green, 81 tests, 0 failures. Frontend: `npm run test:unit` (full suite, 15 files / 126
tests, +2 new) — all green, including the two new AC4 spec files.

**Flakiness caught and fixed during this re-validation, not just assumed passing:** the first pass at
the two new `resetStaleClaimed` boundary-test pairs (AC1 Decision 1) failed intermittently in this same
Testcontainers environment — seeding `claimed_at` and calling `resetStaleClaimed` as two separate
statements/transactions left a 1-second (and even a 30-second) margin exposed to real inter-statement
clock movement (a fresh Postgres container's own clock visibly catching up to the host by several
minutes shortly after startup, empirically observed here). Fixed by wrapping both statements in one
explicit transaction so Postgres's `now()` (`transaction_timestamp()`) is identical for both, not by
further widening the margin. Also caught: a `ShedLockConfigIT` lock-name literal that exceeded
`main.shedlock.name`'s `varchar(64)` limit. All were fixed and the affected suites re-run green before
this note was written.

**⚠ PR must carry the `frontend-tests` label (/bmad-code-review fix, 2026-09-21).** AC4 Task 6 named
this requirement inline but nothing outside that one task line recorded it — `mvn verify` never
invokes Vitest, so without the label neither `axiosSpec.js`/the new `boot/__tests__/` spec, the
in-flight-guard/coalescing specs added to `sessionRedirectSpec.js`, nor the `vitest.config.mjs`
`#q-app/wrappers` alias fix this story needed are exercised in CI at all — every one of them has only
ever been run locally (`npm run test:unit` above).

## Change Log

- 2026-09-21: All 4 ACs + AC5 ledger closeout complete, all review findings applied, targeted backend
  (81) and frontend (126) tests green. Status → done. Ready for commit/PR.
- 2026-09-21: `/bmad-code-review` Review Findings applied (this file's own `### Review Findings`
  section above). 30 raw findings across four parallel layers → 3 decision-needed, 17 patch, 7
  deferred (pre-existing/latent, left open in `deferred-work.md`), 3 dismissed as verified false
  positives. Every finding independently re-verified against actual source before acting; all 3
  decision-needed items taken live with the user (`AskUserQuestion`) before implementing — no decision
  assumed. Most significant: AC2's cumulative lock-wait gap (a two-skill row at the configured ceiling
  could exceed both the DLQ processor's own margin and the stale-claim buffer AC1 relies on, reopening
  the exact duplicate-`recalculateComposite` hazard AC1 exists to close) was closed in code, not just
  documented — `RadarCompositeCalculationService` now tracks a `CUMULATIVE_LOCK_WAIT_BUDGET` across the
  per-skill loop and shrinks/aborts rather than let the per-statement-only bound compound unboundedly.
  AC1's two "proves DB-time" ITs, found vacuous (test JVM and Testcontainers Postgres share one host
  clock, so old and new predicates matched identically), were replaced with genuine boundary-test pairs
  in both repositories — real regression protection, though still not a skew-immunity proof, which
  would need deliberately-induced clock skew this fix does not attempt (recorded, not pursued). Running
  the new boundary tests (not just reasoning about them) surfaced a genuine flakiness bug of their own:
  seeding `claimed_at` and calling `resetStaleClaimed` as two separate statements/transactions left the
  test exposed to real inter-statement clock movement — empirically a multi-minute jump in this same
  Testcontainers environment, plausibly a fresh container's own clock catching up to the host shortly
  after startup — which a 1-second and even a 30-second margin both failed to survive. Fixed at the
  root, not by further widening the margin: both statements now run inside one explicit transaction, so
  Postgres's `now()` (`transaction_timestamp()`, frozen for a transaction's duration) is identical for
  both, making the original tight 1-second margin safe. The
  third decision — AC1's own half-applied residual (`next_retry_at` eligibility staying on the app
  clock) — was accepted as documented risk and given its first formal `[DECIDED: accepted risk —
  third decision — AC1's own half-applied residual (`next_retry_at` eligibility staying on the app
  clock) — was accepted as documented risk and given its first formal `[DECIDED: accepted risk —
  skillars-deferred-126]` tag in `deferred-work.md`, rather than expanding this story's scope to touch
  every writer of that column. Also fixed: two factually-wrong Javadoc claims (a deadlock was already
  bounded by Postgres's own `deadlock_timeout`, not newly bounded by `lock_timeout`; `make_interval`'s
  own `secs` parameter is itself `double precision`, so it performs the identical cast the removed
  rationale claimed it avoided); a config floor sitting AT rather than above the invariant it was
  meant to enforce (`min` 1L → 2L); a stale AC1-superseded code comment; a synchronous-throw promise-
  pinning bug and an expired-flag-discarding coalescing bug in `sessionRedirect.js`'s in-flight guard
  (both traced to the same root cause and fixed by one microtask deferral, with regression tests for
  each); three `boot/axios.js` pre-boot-fallback defects (wrong redirect shape, missing reload, no
  `.catch()`); a `ShedLockConfigIT` test asserting the converse of the actual invariant (added the
  missing same-instance/same-identity test); a mislabeled, production-code-bypassing deadlock IT
  (renamed, rewritten to use the real upsert statement shape); two IT assertions strengthened to pin
  the empirically-confirmed exception causes and a lower time bound; a `ConfigBounds` call-site
  convention violation (literal bounds + `verify(...)`, not accessor calls + unpinned `anyLong()`); a
  dangling ledger cross-reference retargeted; a hardcoded test literal replaced with a reference to the
  constant it duplicates; and the `frontend-tests` PR-label requirement surfaced beyond its one
  original task line. See the Review Findings section's own inline resolution notes for the
  file-by-file detail.
- 2026-09-21: Dev-story implementation complete (`/bmad-dev-story`), status → review. All 4 ACs + the
  standard AC5 ledger closeout implemented and verified — see Dev Agent Record above for the
  per-AC summary, the two exception-class findings (`PessimisticLockingFailureException` for both the
  lock-timeout wait and the genuine deadlock, correcting this story's own earlier unverified guess of
  `QueryTimeoutException`), the `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS` max-bound correction (30s → 120s,
  to satisfy `ConfigStartupAssertionTest`'s existing "100 is inside every range" fixture invariant), and
  the `#q-app/wrappers` Vitest resolution gap found and fixed while writing this story's first boot-file
  spec. All targeted tests green (backend + full frontend `npm run test:unit`, 124 tests); no `mvn
  verify` run, per this project's standard `/bmad-dev-story` validation policy.
- 2026-09-21: `story-review.md` (senior-dev pre-implementation audit) applied. 25 findings (5 High,
  6 Medium, 14 lower-severity), every one independently re-verified against actual source (Postgres
  semantics, `ConfigBounds`/`ConfigService`/`ConfigStartupAssertion` source, `RadarCompositeCalculation
  Service`/repository native SQL, `GdprErasureService`, `axios.js`/`sessionRedirect.js`/`LoginPage.vue`,
  and the decompiled pinned `shedlock-*-7.10.1` jars) before applying anything — **zero false
  positives**, all 25 confirmed genuine. Most significant: AC2's original draft would have produced a
  **boot-blocking config key** (mirroring a `failFast`/non-`HAS_CODE_DEFAULT` precedent literally
  requires a Flyway seed migration, contradicting this story's own "no new migration" Dev Note) —
  resolved by switching to the 4-arg `getBoundedLong` + `HAS_CODE_DEFAULT` route, which also
  independently fixes a second finding (that overload additionally avoids widening
  `RadarCompositeDlqProcessor`'s own documented `getBoundedLong`-failure residual). AC2's prescribed
  concurrency test would have been **vacuous as written** — its fixture (mirroring an IT with no
  `radar_assessments` rows and no pre-seeded conflicting row) would never have reached the code the AC
  bounds; rewritten to reuse the actual right precedent (`RadarCompositeCalculationServiceConcurrencyIT`,
  already in-package) with the three missing preconditions named explicitly. AC4 gained two unflagged
  defects of its own: a hardcoded `{ expired: true }` that would show a false "session expired" banner
  on a plain unauthorized 401 once the fix makes the query param reach the SPA for the first time
  (fixed: gate `expired` on the actual `errorKey`), and a same-tick double-`router.push` race with
  `App.vue`'s own session-expiry handler that the fix would have silently converted from a benign no-op
  into an unnecessary full-page reload (fixed: a module-level in-flight guard added to
  `sessionRedirect.js` itself, benefiting all four call sites, not just this one). AC3's Task 3 and
  Task 5 **contradicted each other** (compute the identity "once" as a field vs. assert uniqueness
  across two constructed instances in a test) — resolved by computing the value inside the `@Bean`
  method body, satisfying both; two of AC3's own hedges (whether `Utils.getHostname()` and
  `withLockedByValue` are accessible) were confirmed unnecessary by decompiling the actual pinned jars —
  both are public API, simplifying the task list. AC1 had an internal contradiction (calling
  `resetStaleClaimed`'s comparison "the only" cross-instance one, while its own Residual paragraph
  named a second) and was missing two entity classes from its own Javadoc-update scope and File List
  (`VideoDeletionOutbox.java`, `RadarCompositeDlqEntry.java`) whose "the tick's own claim instant"
  phrasing becomes false the moment this AC ships — both fixed, and three existing in-repo precedents
  for DB-time stamping (`V144`'s own `now()` backfill, both `PlayerRadar*Repository`'s native `NOW()`
  inserts, `AbstractIntegrationTest.releaseSchedulerLock`'s test fixture) were cited to de-risk the
  approach. One citation error corrected (`GdprErasureService.eraseRadarAndDevelopmentData` does not
  exist; the real method names and line numbers are `erase`/`deletePlayerDevelopmentData`, `:201-202`).
  A genuine lock-ordering deadlock hazard (AC2's write order is the exact opposite of the GDPR erasure
  path's delete order) and a ledger-hygiene gap (the structurally identical `deleteStrike`
  `[DECIDED: accepted risk]` sibling this AC2 fix should cross-reference, and one untagged
  `[DECIDED]`-shaped bullet in AC3's own subsystem the Provenance section's "three categories" claim
  had missed) were also found and folded in. No AC's core mechanism claim or owner decision was
  reopened — every correction is to implementation-instruction accuracy, task completeness, and
  citation correctness. See `story-review.md` for the full finding-by-finding detail (F-1 through
  F-25).
- 2026-09-21: Story created via `/bmad-create-story`. Mined the freshest same-day code-review deferral
  (`code review of skillars-deferred-125`, 4 bullets, all freshly surfaced by that story's own
  `/bmad-code-review`). All four bullets independently re-verified against `master@a40d1985`
  (skillars-deferred-125's merge, PR #213) before drafting — line citations confirmed accurate, and one
  concrete conflict source for AC2 (`GdprErasureService`'s unlocked `deleteAllByPlayerId` calls) was
  identified during this verification that the original deferral note did not name. Four owner
  decisions taken live with the user (`AskUserQuestion`) before drafting: (1) move the stale-claim
  `claimed_at` stamp and staleness-deadline comparison to DB time (`now()`), matching ShedLock's own
  `usingDbTime()` choice, rather than accepting the clock-skew risk as documented; (2) bound
  `RadarCompositeDlqProcessor`'s per-row upsert wait with a targeted, narrowest-blast-radius mechanism
  (a transaction-scoped `SET LOCAL lock_timeout`, since the originally-suggested `@Lock`/`@QueryHints`
  NOWAIT mechanism does not apply to a native `INSERT ... ON CONFLICT` statement — a correction made
  during story creation, not assumed from the ledger's own fix note) rather than a connection-wide
  `statement_timeout` or accepting the gap as risk; (3) give ShedLock's `locked_by` a
  hostname-plus-UUID identity, preserving host-level operational visibility while guaranteeing
  per-JVM uniqueness, rather than a bare UUID or accepting the same-host-collision risk; (4) fix
  `boot/axios.js`'s hash-mode 401 redirect defect by threading Quasar's boot-injected `router` into the
  interceptor and reusing skillars-deferred-125 AC3's shared `pushLoginOrHardNavigate` helper, rather
  than a standalone minimal patch that would leave two different redirect mechanisms in the codebase
  for the same event. No older ledger item was added to this story's scope — the full ledger history
  was consulted (not re-derived from scratch) and shows the remaining untagged items are either too
  large for this bundle (the `AFTER_COMMIT` refund-drop platform-wide concern, left alone by three
  prior stories), out-of-module scope, or already closed with a stale un-struck summary-table row
  (verified, not touched). 4 ACs bundled per this project's "do not create small stories" convention,
  plus the standard AC5 ledger closeout.
