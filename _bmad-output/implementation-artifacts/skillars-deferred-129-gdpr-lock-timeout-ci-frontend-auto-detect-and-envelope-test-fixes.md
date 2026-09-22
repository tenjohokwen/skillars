# Story: GDPR Erasure Statement Lock Timeout, CI Frontend-Change Auto-Detection & Envelope Durability Test Fix

**Story Key:** `skillars-deferred-129-gdpr-lock-timeout-ci-frontend-auto-detect-and-envelope-test-fixes`
**Epic:** Deferred Work
**Priority:** Medium (a real, still-unbounded blocking-DELETE hazard that silently defeats skillars-deferred-128 AC2's own per-erase deadline — plus two genuine, low-risk gaps: a CI safety net that depends on human memory to fire, and a test helper whose full-table scan the codebase's own established convention already flags as avoidable).
**Status:** ready-for-dev
**Created:** 2026-09-22
**Reviewed:** 2026-09-22 (`story-review.md`, senior-dev pre-implementation audit). 3 blocking + 10 medium/low
findings, every one independently re-verified against actual source before applying — zero false positives.
Most significant: AC1's original single `set_config` call would have bounded each of ~12 individual
statements, not the method's total wait (Postgres `lock_timeout` is per-STATEMENT, exactly the trap
`RadarCompositeCalculationService`'s own Javadoc already documents and solves for a different method) —
resolved via an owner decision (`AskUserQuestion`) to keep the simpler per-statement bound, document its
real `N × seconds` worst case honestly instead of overclaiming a method-level ceiling, and convert
`PlayerTimelineRepository.deleteByPlayerId` from a derived (N+1-shaped) delete to a real bulk statement so
`N` is at least fixed. Second: AC1 as originally scoped only wrapped the PARENT-branch call site in a
skip-and-alert catch, leaving the PLAYER-branch call site (the most common account shape) with no catch at
all — a contended row lock there would have silently `FAILED` the whole erasure with no alert, a net
regression on the most common path; resolved via a second owner decision to wrap it with equivalent
semantics. AC3's dictated JPQL (`JOIN`, not `JOIN FETCH`) would have broken two currently-green assertions
via `LazyInitializationException`. See the Change Log for the full response.

---

## Provenance & Scoping (read before starting)

This story mines `deferred-work.md` per this project's established "read same-day code-review
deferrals before drawing scope" convention, plus two owner-selected thin/previously-declined items
per an explicit owner decision (`AskUserQuestion`, this story's own creation session) taken because
the freshest same-day section alone (one bullet) was too thin for this series' "do not create small
stories" bundling convention, and the owner declined the alternative of dispatching a fresh ad-hoc
concurrency audit of `platform.marketplace`/`platform.reviews` (the only two modules never swept
under that lens) in favor of closing out existing, already-documented gaps instead.

### `## Deferred from: code review of skillars-deferred-128-...` (2026-09-22) — the one fresh bullet

`deferred-work.md:3001-3022`. Surfaced by `/bmad-code-review`'s `/txn-and-concurrency-audit` layer
while reviewing skillars-deferred-128, and explicitly deferred there (not fixed) as a pre-existing
gap the story's own new `gdprEraseLockBudget` bound made newly worth documenting. **Not
`[CLOSED]`/`[DECIDED]`** — genuinely open. **Partially closed by AC1 below** (story review,
2026-09-22: AC1 closes the "a blocked statement can hang forever" half of this bullet; the bullet
also separately documents a second, distinct hazard — the inner transaction's own pooled-connection
*acquisition* wait, up to `connection-timeout: 30000` — that `lock_timeout` has no effect on and AC1
does not touch. **Do not delete this bullet outright**; AC4 below rewords it instead of closing it).

**Citation re-verified against current `HEAD` (`ff49c148`, skillars-deferred-128 merged) —
drifted, corrected here:** the ledger cites `GdprErasureService.java:436-453`; at current `HEAD`
the method is `deletePlayerDevelopmentData` at **`:487-527`**, with its 11
`deleteAllByPlayerId`/`deleteByPlayerId` calls at **`:498-515`** (line numbers shifted when
skillars-deferred-128's own `/bmad-code-review` response landed, after the ledger bullet was
written).

### `## Deferred from: code review of skillars-deferred-108` (2026-09-10) — one still-open bullet, owner-selected

`deferred-work.md:2049-2061`. **Not `[CLOSED]`/`[DECIDED]`** (owner decision D2 there only decided
to make the job opt-in *deliberately* — this bullet records the coupling, not a disagreement, and
explicitly says "Revisit if/when the job is promoted to a required check"). Re-verified against
current `HEAD`: `.github/workflows/frontend-unit-tests.yml` still gates `frontend-unit` on
`github.event_name == 'workflow_dispatch' || contains(github.event.pull_request.labels.*.name,
'frontend-tests')` (`:51-53`) — unchanged since 2026-09-10. **Closed by AC2 below**, per this
story's own owner decision (`AskUserQuestion`): replace the human-memory dependency with automatic
detection, not promote to an unconditional required check (the latter would cost ~10 CI minutes on
every PR regardless of whether frontend was touched). **Note (story review, M4): AC2 does not
change the job's non-gating status** (still not referenced by `ci.yml`/`pr-build.yml`, still not a
required check) — this bullet's core claim ("a red frontend suite still does not block a merge")
remains true after this story ships. AC4 below rewords it down to that surviving claim; it does
not delete it.

### `## Deferred from: code review of ses-1-4-registration-email-durability` (2026-09-12) — one still-open bullet, owner-selected

`deferred-work.md:2198`. **Not `[CLOSED]`/`[DECIDED]`** — explicitly re-confirmed still open at the
2026-09-15 full-file re-audit (`[Restored 2026-09-15 — see the audit below.]`) and again untouched
by the skillars-deferred-110/-111 prunes (`deferred-work.md:2245-2251` lists it by name as "none
were in scope … and none were touched by it"). **Correction (story review, M3): this bullet's own
headline claim is the `EmailRetryScheduler.retryFailedEmails()` whole-table poll re-driving other
tests' rows in the shared JVM-static Postgres — not the `findAll()` test-helper fragility**, which
is its subordinate clause. This story's AC3 closes only the subordinate clause. The headline claim
is untouched by this story and remains open — **AC4 below rewords the bullet down to its headline
claim; it does not delete it.**

`findAll()` sub-claim: **partially pre-closed by skillars-deferred-111 AC7** (added
`committedRowBySendId`, a targeted `findBySendId` lookup, for every call site that already knows
its own generated `sendId` — see `RegistrationEmailDurabilityIT.java:95-107`). **One call site in
that class remains** (`committedRowFor`, `:85-93`), plus, per story review M10, **a second,
structurally identical, undocumented `findAll()` scan in a different test class in the same
package** (`VideoModerationAdminAlertEnvelopeIT.java:159-169`) that the original ledger-mining pass
missed. **Both closed by AC3 below.**

### Out of scope (explicitly, not re-decided here)

- A fresh ad-hoc concurrency/TOCTOU audit of `platform.marketplace`/`platform.reviews` — offered as
  an alternative during this story's own scoping (`AskUserQuestion`) and declined by the owner in
  favor of closing the two items above instead. Still a real candidate for a future story: these are
  the only two modules with no dedicated audit in this series' history (skillars-deferred-115 through
  -128 swept every other module under the `@Scheduled`/TOCTOU lens).
- `GdprErasureService.markFailed`'s lack of `AdminAlert`/auto-retry for the *generic* failure case —
  already `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened (the deadline and
  skip-and-continue paths already gained targeted alerts under skillars-deferred-128; AC1 below
  extends the same targeted-alert treatment to two new failure causes, not the generic case).
- `radar_composite_dlq` rows for an erased player not cleared — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- `main."user"` cleanup-sweep missing index — still blocked on production `EXPLAIN`/row-count
  evidence this project has no deploy history to generate; not reopened.
- Porting `RadarCompositeCalculationService`'s full cumulative spend-down `lock_timeout` budget
  mechanism into `GdprErasureService` — considered during this story's own creation
  (`AskUserQuestion`) and declined in favor of a simpler, honestly-documented per-statement bound
  (see AC1's Context/Owner decision below). Revisit if a future story needs AC2's `~10s`
  `gdprEraseLockBudget` to be a hard ceiling rather than a nominal target.

All citations below were independently re-verified against `master@ff49c148` (this story's own
creation-time `HEAD`) and again during the `story-review.md` pre-implementation audit (`HEAD =
7af5eddf`, the story-creation commit) by direct `Read`/`Grep` against the actual source files, not
assumed from the ledger's own (in one case, stale) citations. **Re-verify again at actual
implementation time** if this worktree's `HEAD` has moved.

---

## AC1 — Bound each statement in the GDPR erasure inner transaction's bulk deletes with a `lock_timeout`, and close the PLAYER-branch alerting gap it would otherwise open

### Context

`GdprErasureService.deletePlayerDevelopmentData`
(`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:487-527`,
added by skillars-deferred-128 AC1) runs, per child, inside its own `REQUIRES_NEW` transaction
(`requiresNewTemplate.executeWithoutResult(status -> { ... })`, `:488`): it re-acquires the
`player_profiles` pessimistic lock (`:489-491`), then issues bulk delete statements across 11
tables (`:498-515`), a `performance_reports` scan collecting S3 keys, a blob-deletion enqueue
(`:517-520`), and the `developmentDataErasedAt` tombstone stamp (`:522-525`) — all before the lock
is released at that inner transaction's commit.

The lock *acquisition* itself is bounded: `findByIdForUpdate` uses `NOWAIT`
(confirmed by `PlayerProfileRepository`'s own `@QueryHints`), and `PessimisticLockRetryer.
withBoundedRetry` wraps it in a jittered backoff with a documented ~3.2s worst case. **Nothing
downstream of that acquisition is bounded.** None of the delete statements, the blob-enqueue
`INSERT`, or the final tombstone `save()` carries a `lock_timeout` — unlike
`RadarCompositeCalculationService.recalculateComposite`
(`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:287-289`),
which issues `SELECT set_config('lock_timeout', ?1, true)` before its own per-skill upsert
statements, this method issues no such call at all, and `application.yaml`'s
`connection-init-sql` sets only the session time zone — there is no global `lock_timeout` either.

**The concrete hazard:** if a concurrent writer holds a conflicting row lock on any of the 11
target tables (for example, `development.radar_assessment_entries` — a coach's `submitAssessment`
transaction can legitimately hold row locks there), one of `deletePlayerDevelopmentData`'s
`DELETE` statements blocks **indefinitely** while the request thread still holds that child's
`player_profiles FOR UPDATE` lock — silently defeating skillars-deferred-128 AC2's own
`gdprEraseLockBudget` deadline (`GdprErasureService.java:99-116`), which is sampled only at the top
of each PARENT-loop iteration and never re-evaluated once inside a child, and re-extending the
exposure window for the 7 FK'd tables' `FOR KEY SHARE` RI checks that skillars-deferred-128 AC1 was
specifically written to narrow.

**Correction (story review, 2026-09-22, H1) — `lock_timeout` is per-STATEMENT in Postgres, not per
transaction; a single `set_config` call before the delete block does not bound the method's total
wait.** This codebase already documents this exact trap, for a different method:

> `RadarCompositeCalculationService.java:51-60` — "`lock_timeout` is per STATEMENT, so at the
> configured ceiling the two statements per skill could each independently wait up to [the max] —
> an N-skill call's cumulative worst case is `2 * N * lockTimeoutSeconds`, unbounded by the
> per-statement timeout alone."

That method solves it with a `CUMULATIVE_LOCK_WAIT_BUDGET` spend-down loop (`:66-68`, `:265-289`).
Counted directly from `deletePlayerDevelopmentData`'s own source, there are **≥12 independently-
timeout-able statements** inside the one `set_config` call's transaction scope (the lock
acquisition and the final tombstone `save()` cannot themselves block on an external lock — the row
is already held — but every one of the delete/scan/enqueue statements can): at the story's original
proposed default (5s), the real worst case is **~60s, not 5s**; at the proposed max (120s), **~24
minutes**, on the request thread, pinning two of the 25 Hikari connections
(`application.yaml:181`) the whole time.

**The statement count is also not fixed.**
`PlayerTimelineRepository.deleteByPlayerId` (`PlayerTimelineRepository.java:10`) is a **Spring Data
derived delete** — no `@Modifying`/`@Query`, unlike all ten siblings (`SluRepository.java:71-73`,
`SluWeeklySnapshotRepository.java:77-79`, `PlayerSluWeeklySnapshotAppliedRepository.java:17-19`,
`SluTargetRepository.java:42-44`, `PlayerRadarBaselineRepository.java:30-32`,
`PlayerRadarCompositeRepository.java:35-37`, `RadarAssessmentRepository.java:79-81`,
`HomeworkCompletionRepository.java:18-20`, all verified `@Modifying @Query`). A derived delete
loads the entities and removes them one at a time — one DELETE statement per
`player_timeline_events` row, growing with the player's own timeline length, not a single bulk
statement the way the story originally (incorrectly) described all 11 calls.

**Owner decision (this story's own creation session, `AskUserQuestion`): keep the simpler
per-statement bound, not the full cumulative spend-down mechanism.** Rather than port
`CUMULATIVE_LOCK_WAIT_BUDGET`'s shape (materially more complex here, since the deletes are not a
loop over a caller-supplied set like `recalculateComposite`'s skills — the budget would need
threading through ~11 discrete call sites individually), this AC:

1. Still bounds each statement (converting "can hang forever" into "bounded, 2–120s configurable"
   — a real, meaningful improvement over today's genuinely unbounded wait);
2. Converts `PlayerTimelineRepository.deleteByPlayerId` into a real bulk `@Modifying @Query` delete
   (matching its ten siblings), so the statement count is at least **fixed**, not open-ended;
3. **Documents the real bound honestly as `N × configured-seconds`, not a method-level ceiling** —
   this does NOT make skillars-deferred-128 AC2's `~10s` `gdprEraseLockBudget` a reliable cap on its
   own; it converts an unbounded hang into a bounded (if, under simultaneous multi-statement
   contention, possibly larger than the nominal budget) one. **Do not claim full closure of the
   ledger residual this AC targets** — see AC4 below, which rewords rather than deletes the
   relevant ledger bullet for exactly this reason.

**Mirrors `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds exactly** (default 5s, min 2s, max
120s) via a new `ConfigBounds` runtime-tunable key, rather than a plain constant — an operator can
tune it live without a redeploy exactly as they already can for the radar-composite case.

**Second required correction (story review, 2026-09-22, H2) — the PLAYER-branch call site has no
catch at all, and AC1 as originally scoped would silently convert a slow-but-successful erasure
into an unalerted `FAILED` on the most common account shape.** `deletePlayerDevelopmentData` has
**two** call sites:

1. `GdprErasureService.java:330`, inside `eraseParentChildren`'s loop — already wrapped in
   `catch (ResourceNotFoundException)` / `catch (PessimisticLockingFailureException)`
   (`:332-354`, skillars-deferred-128 AC4), which skips the contended/vanished child, raises a
   targeted `AdminAlert`, and lets the rest of the PARENT request proceed.
2. `GdprErasureService.java:191`, the `role == SkillarsRole.PLAYER` branch — **no try/catch at
   all**. Today, a concurrent writer holding a row lock on one of the 11 tables makes that `DELETE`
   block and then succeed once the writer commits. After this AC's `lock_timeout` bound, it
   **throws** instead — and the throw propagates straight out of `erase()`, aborting the whole
   erasure and routing to `markFailed` (`:262-268`), which only `log.error`s: no `AdminAlert`, no
   auto-retry (the already-`[DECIDED: accepted risk — skillars-deferred-127]` gap this story's own
   "Out of scope" section declines to reopen generically). This AC would otherwise convert a
   previously-successful (if slow) erasure into a **silent FAILED**, on the most common account
   shape (a self-registered player), for the most common trigger (any coach `submitAssessment`
   holding a row lock on any of the 11 tables) — a net regression, not a bound.

**Owner decision (this story's own creation session, `AskUserQuestion`): wrap the PLAYER-branch
call site with equivalent skip-and-alert semantics to the PARENT branch**, rather than leave it
unwrapped and merely document the new risk. On a lock-timeout/contention failure at `:191`, skip
the development-data deletion, raise the alert, detach the stale managed instance (mirroring the
existing `entityManager.detach(pp)` at `:198`), and let `erase()` continue — the account still gets
anonymised/locked, refresh tokens revoked, and the request marked `COMPLETED` — instead of the
whole erasure silently `FAILED` with no anonymisation and no alert.

**Third correction (story review, 2026-09-22, M6) — the existing `CHILD_CONTENDED` catch's log
message and alert reason become factually wrong for this AC's new failure mode, and the file's own
comment already warns against exactly this kind of silent reuse.**
`GdprErasureService.java:322-329`'s own comment states both existing catches in
`eraseParentChildren` are "precise ONLY by construction" and that "if
`deletePlayerDevelopmentData`'s shape ever changes … that new throw must NOT be silently swallowed
here … without first re-verifying this assumption still holds." This AC is exactly that change: a
lock-timeout trip on a downstream `DELETE` and a `PessimisticLockRetryer` budget exhaustion on the
`player_profiles` lock acquisition are different causes that (per `RadarCompositeCalculationService.
java:157-167`'s own empirically-confirmed precedent) both surface as the same top-level
`PessimisticLockingFailureException`, distinguishable only by inspecting the exception's `cause`
(`org.hibernate.PessimisticLockException` for a plain lock-timeout wait via the JPA-native-query
path). Reusing the existing `CHILD_CONTENDED` reason/log wording for this new cause would tell an
operator the wrong lock was contended.

### Tasks

1. Re-verify all line citations above against current `HEAD` before implementing.
2. Add `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS`
   (`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`), a new
   `BoundedKey("platform.gdpr_erase_statement_lock_timeout_seconds", 2L, 120L, false, ...)` —
   copy `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds (`:255-258`) and Javadoc reasoning
   verbatim where it applies (the `min = 2L` floor sitting above Postgres's own ~1s
   `deadlock_timeout` default applies identically here). Add its key to `HAS_CODE_DEFAULT`
   (`:304-321`) and the key itself to the `ALL` list (`:326-354`) — read via the 4-arg
   `getBoundedLong` route exactly like `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own call site, not
   the `failFast` shape. This is a plain field addition on `ConfigBounds`, not a constructor change.
3. Add a `private final ConfigService configService` field to `GdprErasureService`
   (`:66-90` block) — the class uses Lombok `@RequiredArgsConstructor` (`:62`); there is no
   hand-written constructor to edit. `ConfigService` is already injected across `video`, `security`,
   `development`, `payment` — no new module-boundary concern. Verify no test constructs this bean
   manually (`grep -rn "new GdprErasureService(" src/` should return no hits) before assuming the
   field addition is safe.
4. Convert `PlayerTimelineRepository.deleteByPlayerId` (`PlayerTimelineRepository.java:10`) from a
   derived delete into a real bulk `@Modifying @Query` delete, matching its ten siblings'
   established convention exactly (see e.g. `SluRepository.java:71-73`) — this fixes the "statement
   count is not fixed" problem the story review identified, independent of anything else in this AC.
5. Read the bounded config value **before** the lock acquisition (not after) —
   `ConfigService.ensureFresh()`'s cache-expiry path triggers a real `configRepository.findAll()`
   DB round trip (`ConfigService.java:190-193`, `:300-311`; default TTL 300s,
   `ConfigProperties.java:8`); doing this read after the lock is held would put that round trip
   inside the transaction for no reason, mirroring the exact reasoning
   `RadarCompositeCalculationService.java:195-201`'s own comment already states for its identical
   read-before-lock choice.
6. Issue `entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
   .setParameter(1, seconds + "s").getSingleResult();` **after** the lock is held — immediately
   after `entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE)` (`:491`) and before
   the first delete call (`:498`) — mirroring `recalculateComposite`'s own placement of the
   `set_config` *statement* itself (only the config *value read* moves earlier, per Task 5).
7. **(H2 fix)** Wrap the PLAYER-branch call site (`:191`) with equivalent catch semantics to
   `eraseParentChildren`'s existing two catches — extract a small shared private helper if that
   avoids duplicating the catch/alert/log logic between the two call sites (both now need
   materially the same shape), or inline it if extraction would obscure the PARENT branch's own
   loop-specific bookkeeping (`processed`/`skipped` counters). On a caught failure: raise the
   alert, `entityManager.detach(pp)` (already present at `:198` for the happy path — ensure it
   still runs, or is unnecessary, on the caught-failure path too), log distinguishably from the
   PARENT-branch case (this is a single-profile branch, not a skip-one-of-N-siblings case), and let
   `erase()` continue to its remaining steps (refresh-token revoke, `gdprRequest` cleanup, mark
   `COMPLETED`) rather than propagate.
8. **(M6 fix)** Discriminate the new lock-timeout-on-a-downstream-statement failure from the
   existing `PessimisticLockRetryer`-budget-exhaustion failure — both currently surface as
   `PessimisticLockingFailureException`, but conflating them under the existing `CHILD_CONTENDED`
   reason/log text would misdirect an operator. Inspect the exception's `cause`
   (`org.hibernate.PessimisticLockException` for the new statement-level `lock_timeout` trip, per
   `RadarCompositeCalculationService.java:157-167`'s own empirically-confirmed precedent — confirm
   this holds for the JPQL `@Modifying`/derived-delete-turned-bulk-delete path too, not just the
   native-query path that precedent was confirmed against; do not assume without a real test) and
   add a distinct reason (e.g. `CHILD_DELETE_LOCK_TIMEOUT`) with its own log wording for this cause,
   applied consistently at both call sites from Task 7. Update the `:322-329` "precise ONLY by
   construction" comment to document this new, now-distinguished thrower.
9. Document on `deletePlayerDevelopmentData`'s own Javadoc (`:440-486`): (a) this bounds each
   individual statement, not the method's total wait — state the real worst case as `N ×` the
   configured seconds, with `N` being the now-fixed statement count after Task 4's conversion; (b)
   this does NOT make skillars-deferred-128 AC2's `gdprEraseLockBudget` a hard ceiling on its own;
   (c) the PLAYER-branch call site (Task 7) and its distinguished failure reason (Task 8).
10. Confirm which exception/cause Postgres/Hibernate actually raises for each of the blocking-
    capable statement shapes this AC now covers (native `set_config`-bound JPQL `@Modifying`
    deletes, and the newly-bulk-converted `PlayerTimelineRepository` delete from Task 4) against a
    real Testcontainers-backed test — do not assume Task 8's cause-based discrimination holds for
    all of them without confirming it empirically, per this project's own established convention.

### Tests

- A `GdprErasureIT` test (note: the file lives at
  `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`, not under
  `admin/service/`) proving the bound is real: hold a conflicting row lock on one of the target
  tables (e.g. `development.radar_assessment_entries`, mirroring
  `RadarCompositeCalculationServiceConcurrencyIT`'s own Testcontainers-plus-real-threads pattern)
  from a second connection/thread, start `erase()` for a child whose
  `deletePlayerDevelopmentData` will contend on that table, and assert the call fails within
  (bound + small margin) rather than hanging.
- **Corrected test seam (story review, M2) — `@TestPropertySource` cannot override a `ConfigService`
  value and does not exist as a precedent in this repository.** `ConfigService` reads only from
  `configRepository` into its own in-memory cache (`ConfigService.java:40-53`, `:190-193`); the only
  Spring property it binds is `app.config.cache-ttl-seconds`. Use the real, already-established
  seam instead: `jdbcTemplate.update("INSERT INTO main.platform_config (key, value) VALUES (?, ?)
  ON CONFLICT (key) DO UPDATE SET value = ?", ...)` followed by `configService.invalidate()` —
  exactly `RadarCompositeCalculationServiceConcurrencyIT.java:403-411`'s own pattern. **The floor is
  2s, and the 4-arg `getBoundedLong` falls back to `defaultValue` on an out-of-range seed, it does
  NOT clamp** (`ConfigService.java:110-118`) — seed exactly `2` (the min), matching the existing
  precedent test's own choice (`RadarCompositeCalculationServiceConcurrencyIT.java:229-236`), not
  an arbitrary smaller value.
- A test proving Task 7/8's PLAYER-branch fix: a contended PLAYER-branch erasure completes with
  `erase()` reaching `COMPLETED` (account anonymised, refresh tokens revoked) and the new targeted
  alert raised, instead of the whole request silently `FAILED` with no alert.
- A test proving Task 8's discrimination is real: the new lock-timeout-on-delete failure produces a
  distinguishable log/alert reason from the existing `CHILD_CONTENDED` case (a genuine
  `PessimisticLockRetryer`-budget-exhaustion scenario, still reachable and still tested separately).
- A regression test confirming Task 4's `PlayerTimelineRepository.deleteByPlayerId` conversion to a
  bulk `@Modifying` delete still deletes the expected rows — check whether existing `GdprErasureIT`
  coverage on `player_timeline_events` row counts already exercises this, rather than assuming.
- A `GdprErasureIT` test with no contention, asserting the new `set_config`/config-read additions
  introduce no observable behavior change to the existing happy-path/lock-released-early/atomicity
  test suite skillars-deferred-128 already added.

---

## AC2 — Auto-detect frontend changes so the frontend unit-test suite no longer depends on a human remembering the `frontend-tests` label

### Context

`.github/workflows/frontend-unit-tests.yml` (`skillars-deferred-104`) runs the real Vitest suite
only on `workflow_dispatch` or when a PR carries the `frontend-tests` label
(`:51-53`, `contains(github.event.pull_request.labels.*.name, 'frontend-tests')`) — confirmed
unchanged at current `HEAD`. This is a **deliberate, already-`[DECIDED]`** choice (owner decision
D2, skillars-deferred-104/108: the job is not referenced by `ci.yml`/`pr-build.yml`, not a required
status check, and `mvn verify`'s own `npm test` execution is a no-op stub) — the gap this bullet
records is not the decision itself but its **failure mode**: a PR that touches `src/frontend/**`
and genuinely re-breaks covered behavior **can merge green** if nobody remembers to add the label.
This is not hypothetical: earlier in this same session, merging skillars-deferred-128's own PR
(#216) required a manual `git status --short | grep -i "src/frontend"` check to confirm the label
was correctly *not* needed — exactly the kind of manual step this AC should make unnecessary.

**Owner decision (this story's own creation session, `AskUserQuestion`): auto-detect via a path
filter, not promote to an unconditional required check.** An unconditional required run would cost
this job's own ~10-minute budget (two-leg `tz` matrix, `frontend-unit-tests.yml:58-69`) on every PR
regardless of whether frontend was touched. Detecting the touched-paths instead preserves the label
as a manual override while removing the human-memory dependency for the common case. **Note (story
review, M4): this AC does not change the job's non-gating status** — confirm and document this
explicitly (Task 5 below), since a still-open ledger bullet's own stated revisit condition
("promoted to a required check") is not met by this AC.

**No existing precedent for path-based diff detection in this repo's `.github/` workflows**
(grepped for `git diff --name-only`, `paths-filter`, `dorny`, `changed-files` — no hits, confirmed).
This project's own established convention for custom CI logic is a hand-rolled bash script, not a
third-party action (see `.github/scripts/assert-context-count.sh`, `.github/scripts/
container-sampler.sh`) — follow that precedent here rather than introducing a new pinned
third-party action for this. Native `on.pull_request.paths` was considered and rejected: it is an
AND at the workflow-trigger level, which would prevent the workflow from even starting for a
label-only-forced run on a non-frontend PR — breaking the label override this AC must preserve.

**Corrections (story review, M7/M8):**

- **M7 — "a step (or a small preliminary job)" is not a real choice.** A job-level `if:` condition
  is evaluated before that job's own steps run and cannot reference `steps.*` outputs from within
  the same job. Only a separate preliminary job (referenced via `needs:`/`outputs:`) can feed the
  `frontend-unit` job's own `if:` condition. The "step" alternative would force `frontend-unit` to
  always start (spinning up both matrix legs and checkout) and guard each later step individually —
  defeating this AC's own cost rationale.
- **M8 — three concrete diff-mechanics gaps:**
  1. **Two-dot vs. three-dot diff.** `git diff A B` (two-dot) includes everything that landed on
     the base branch since the PR's own branch point — a backend-only PR opened before someone else
     merged an unrelated frontend change would be reported as touching `src/frontend/**`. Use a
     three-dot/merge-base diff (`git diff --name-only "$BASE...$HEAD"`, or an explicit
     `git merge-base`) instead.
  2. **`workflow_dispatch` has no PR payload.** `github.event.pull_request.base.sha`/`.head.sha` are
     empty on manual dispatch (this workflow already triggers on both event types,
     `frontend-unit-tests.yml:41-44`). The detector job needs its own guard
     (e.g. `if: github.event_name == 'pull_request'`) so it does not fail/misbehave on a
     `workflow_dispatch` run — `workflow_dispatch` should keep triggering `frontend-unit`
     unconditionally via its own existing `if:` clause regardless of what the detector reports.
  3. **Per-job `permissions:`.** This workflow declares `permissions:` at both the workflow level
     (`:46-47`) and the existing job level (`:56-57`); a new detector job needs its own
     `permissions: contents: read` block to match the file's own convention.

### Tasks

1. Re-verify `frontend-unit-tests.yml`'s current trigger/gate logic against `HEAD` before
   implementing (cited above as `:39-53`).
2. Add a **separate preliminary job** (not a step within `frontend-unit` — see M7 above) that
   computes whether the PR's diff touches `src/frontend/**`, using a three-dot/merge-base `git
   diff --name-only` against the PR's base/head SHAs (`github.event.pull_request.base.sha` /
   `.head.sha` — see M8.1), guarded so it only runs/evaluates meaningfully on the `pull_request`
   event (see M8.2), checked out with sufficient history to diff those two SHAs directly (not a
   shallow single-commit checkout), and declaring its own `permissions: contents: read` block (see
   M8.3). Follow this project's existing pinned-action-by-SHA convention for `actions/checkout` (see
   this same file's own `:75` and `pr-build.yml:23` for the current pinned version).
3. Widen `frontend-unit`'s own `if:` condition (`:51-53`) to also fire when the preliminary job's
   output indicates a frontend-path change — an "OR" of three conditions now
   (`workflow_dispatch`, the label, the new detector output), via `needs:` on the new job.
4. Update `docs/testing/frontend-unit-tests.md` (`:28-55`) to document the new auto-detection
   behavior alongside the existing manual-dispatch/label instructions — the label section should be
   reframed as "force a run even when the diff wouldn't auto-trigger one," not deleted.
5. **(M4 fix)** Confirm and explicitly document that this does not change `frontend-unit-tests.yml`'s
   own already-`[DECIDED]` non-gating status (D2/D6, cited in the file's own header comment
   `:36-38`) — it still is not referenced by `ci.yml`/`pr-build.yml` and is still not a required
   status check; this AC only widens *when it fires automatically*, not what blocks a merge. This
   distinction matters directly for AC4's ledger reword below.
6. **(L5) Add a `concurrency:` group** to `frontend-unit-tests.yml` (this file currently has none,
   unlike `pr-build.yml:7-9`'s `cancel-in-progress: true`) — after auto-detection, every push to a
   frontend-touching PR would otherwise queue a fresh ~10-minute two-leg run with no cancellation of
   the previous one, undercutting this AC's own CI-minutes rationale. Note but do not necessarily
   engineer around: the workflow's existing `types: [opened, labeled, synchronize, reopened]`
   trigger means adding *any* unrelated label to a frontend-touching PR also fires a full run today
   (pre-existing, not introduced by this AC) — the `concurrency:` group mitigates the cost of this
   but does not eliminate the extra run; document this as a known, low-cost residual rather than
   building additional guard logic for it in this story.

### Tests

- Verify on a real PR during implementation (this project's own established practice for
  workflow-file changes, since GitHub Actions logic cannot be meaningfully unit-tested locally):
  confirm a throwaway PR touching only `src/frontend/**` auto-triggers the job with no label
  applied, a PR touching only backend files does not trigger it (absent the label/manual dispatch),
  and a `workflow_dispatch` run still works unaffected by the new detector job. Record all three
  confirmations in the Dev Agent Record, citing the actual CI run(s), mirroring how
  skillars-deferred-128's own CI-file fix (the Spring-context-ceiling bump) cited its confirming
  run.
- No unit/integration test applicable — this is a CI-configuration-only change.

---

## AC3 — Replace the avoidable `findAll()` scans in `notification`'s envelope-durability tests with a targeted repository query

### Context

`RegistrationEmailDurabilityIT.committedRowFor(String email)`
(`src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java:85-93`)
still does `envelopeEntityRepository.findAll().stream().filter(...)` — a full-table scan followed by
an in-memory filter on the nested `recipients` `@ElementCollection`, used at the one point in each
test where only the UUID-unique seeded email address is known (the registration/OTP listener
generates `sendId` internally, so it cannot be known upfront the way
`committedRowBySendId`'s call sites already know theirs — see skillars-deferred-111 AC7,
`:75-107`).

**Correction (story review, M10) — a second, structurally identical, undocumented `findAll()` scan
exists in the same package and was missed during this story's own creation:**
`VideoModerationAdminAlertEnvelopeIT.committedRow()` (`:159-169`) does the exact same
`findAll().stream().filter(e -> ... r.getEmail() ...)` pattern, with no documented justification.
It is fixed by the same new repository method. (A third hit,
`MailManagerDuplicateSendIdIT.rowCountForSendId` (`:118-121`), is **not** the same case — it counts
rows for a `sendId`, and the existing `findBySendId` already returns a single entity under
`PESSIMISTIC_WRITE`; leave it unchanged.)

`EnvelopeEntityRepository`
(`src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java`)
has no query method scoped to a recipient's email at all. A JPQL query joining the `recipients`
`@ElementCollection` on `RecipientEntity.email` (`RecipientEntity.java:24-25`, a plain
`@Embeddable` field, not a separate entity/repository) can do a single, targeted database lookup
instead of scanning and materializing every row in the table.

**Correction (story review, M9) — the composite primary key does NOT give this query real index
support; the original justification was wrong.** `envelope_entity_recipients`'s only index is its
composite PK, `PRIMARY KEY (envelope_entity_id, email)` (`V138__baseline_schema.sql:2362-2363`) —
`envelope_entity_id` is the **leading** column, so a predicate on `email` alone cannot use that
B-tree; there is no `CREATE INDEX` on `email` anywhere in the migrations (`grep "CREATE INDEX"
src/main/resources/db/migration/*.sql | grep -i recipient` returns no hits). **The real, still-
genuine benefit is avoiding `findAll()`'s full materialization of every `EnvelopeEntity` row (and
hydration of each one's `recipients` collection) into the persistence context** — a real cost that
grows with everything else the shared JVM-static test database accumulates across the whole suite,
not an indexed-lookup speedup. If real index support is later wanted, that needs its own migration,
not proposed by this story.

**Correction (story review, H3, blocking) — the story's originally-dictated exact JPQL (`JOIN`, not
`JOIN FETCH`) would break two currently-green assertions.** `EnvelopeEntity.recipients` is
`@ElementCollection` with no `fetch` attribute (`EnvelopeEntity.java:33-34`) → **LAZY** by JPA
default. `AbstractIntegrationTest` (`src/test/java/com/softropic/skillars/config/
AbstractIntegrationTest.java:60-81`) is `@SpringBootTest(webEnvironment = RANDOM_PORT)` — **not**
`@Transactional`; no persistence context spans the test method. `committedRowFor` returns the
entity **out of** its own `transactionTemplate.execute(...)` block — it works today only because
the current `findAll()` scan's own in-memory filter forces the lazy collection to initialize
*inside* that transaction. Two existing assertions then read the collection after
`committedRowFor` returns (`RegistrationEmailDurabilityIT.java:143`, `:164`,
`row.getRecipients().get(0).getFirstname()`). A plain `JOIN` filters but does not initialize the
collection — this repo documents the exact constraint already, in the same package:

> `MailManagerDuplicateSendIdIT.java:124-127` — "`EnvelopeEntity.recipients` is a lazy
> `@ElementCollection` that cannot be read once the entity is detached."

A plain `JOIN` would make both assertions throw `LazyInitializationException`. **Filtering directly
on a `JOIN FETCH`ed association is also wrong** — it is the classic JPA trap that silently prunes
the *returned* collection to only the matching recipient (which would still pass these two
single-recipient-per-envelope tests, but is incorrect in general and must not be the shipped
shape).

### Tasks

1. Re-verify all citations above against current `HEAD` before implementing. Correct
   `RecipientEntity.java`'s own stale Javadoc reference to a migration file
   (`V137__envelope_entity_recipients_composite_pk.sql`) that does not exist — that migration was
   squashed into `V138__baseline_schema.sql` (the constraint itself is real, at `:2362-2363`; the
   filename the Javadoc cites is not) — while this AC is already touching this area.
2. Add a query method to `EnvelopeEntityRepository` that fetches the full `recipients` collection
   (via `LEFT JOIN FETCH`) for envelopes matched by a recipient email (via a separate `JOIN`/`EXISTS`
   predicate, not by filtering the fetched join directly) — e.g.:
   ```java
   @Query("SELECT DISTINCT e FROM EnvelopeEntity e LEFT JOIN FETCH e.recipients WHERE e.id IN "
        + "(SELECT e2.id FROM EnvelopeEntity e2 JOIN e2.recipients r2 WHERE r2.email = :email)")
   List<EnvelopeEntity> findByRecipientsEmail(@Param("email") String email);
   ```
   No `@Lock` needed (unlike `findBySendId`'s `PESSIMISTIC_WRITE`) — this method's only callers are
   test code asserting on already-committed state.
3. Rewrite `RegistrationEmailDurabilityIT.committedRowFor` (`:85-93`) and
   `VideoModerationAdminAlertEnvelopeIT.committedRow` (`:159-169`, story review M10) to call the new
   repository method instead of `findAll().stream().filter(...)` — keep each method's existing
   `assertThat(rows).hasSize(1)` uniqueness assertion.
4. Update `committedRowFor`'s own Javadoc (`:75-84`) — the "the one `findAll()` … this class cannot
   avoid" framing is inaccurate; replace it with the real remaining constraint (only the seeded
   email is known at this call site, not the generated `sendId`) and the targeted-fetch-query fix,
   cross-referencing this story. Correct the index-support rationale per the Context section above
   (avoids full-table materialization; does not use an index that does not exist).
5. Confirm no other `findAll()` scan remains in
   `src/test/java/com/softropic/skillars/platform/notification/` after Tasks 2–3 — `grep -rn
   "findAll()"` and verify the one remaining hit (`MailManagerDuplicateSendIdIT.rowCountForSendId`)
   still has its own documented, genuinely-different reason (counting, not scoped lookup); do not
   leave any other hit unaddressed without recording why.

### Tests

- `RegistrationEmailDurabilityIT`'s own existing suite (all cases that call `committedRowFor`,
  `:138`, `:161`, `:197`, `:217`) and `VideoModerationAdminAlertEnvelopeIT`'s own existing suite
  (all cases that call `committedRow`) must stay green with unchanged externally-observable
  behavior — this is an internal-implementation change to test helpers, not a behavior change. Pay
  particular attention to `:143`/`:164`'s post-return `getRecipients()` reads (the exact assertions
  H3 identified as currently at risk) — confirm they still pass with the `LEFT JOIN FETCH` fix, not
  merely that the build compiles.
- Add a focused test for the new `findByRecipientsEmail` query method proving it returns the full
  `recipients` collection (not pruned to only the matching recipient) for an envelope with multiple
  recipients — the specific case the "filter on the fetched join" trap would silently break without
  a multi-recipient fixture to catch it.

---

## AC4 — Standard `deferred-work.md` ledger closeout

### Tasks

1. **(story review M5 — reword, do not delete)** `deferred-work.md:3001-3022` documents two
   distinct hazards: a blocked statement hanging indefinitely (AC1's target — closed, subject to
   AC1's own honest `N × seconds` framing, not a hard method ceiling) and the inner `REQUIRES_NEW`
   transaction's own pooled-*connection-acquisition* wait (up to `connection-timeout: 30000`,
   `application.yaml:178`, verified — three times the nominal `~10s` budget), which `lock_timeout`
   has no effect on and this story does not touch. Reword the bullet to close the first hazard and
   retain the second as still-open; do not delete it outright.
2. **(story review M4 — reword, do not delete)** `deferred-work.md:2049-2061`'s core claim ("a red
   frontend suite still does not block a merge unless someone remembers the label") splits after
   AC2: the "unless someone remembers the label" clause is closed (AC2 Task 5 confirms), but the
   underlying non-gating status is unchanged and explicitly confirmed still true. Narrow the bullet
   to that surviving claim; delete only the "remembers the label" sentence, not the whole bullet.
3. **(story review M3 — reword, do not delete)** `deferred-work.md:2198`'s **headline** claim
   (`EmailRetryScheduler.retryFailedEmails()`'s whole-table poll re-driving other tests' rows in the
   shared JVM-static Postgres) is untouched by this story and remains genuinely open. Only its
   subordinate `findAll()`-fragility clause is closed by AC3. Reword the bullet down to its headline
   claim; do not delete it. Also correct this story's own Provenance section above, which
   originally mischaracterised this bullet as "`RegistrationEmailDurabilityIT`'s `findAll()`
   fragility" — that is the subordinate clause, not the bullet's subject (already corrected in this
   revision).
4. Grep-sweep `deferred-work.md` for any other reference to the three items above that a targeted
   reword might miss (e.g. a narrative mention inside a `## Last audit:` summary section) —
   correct or annotate any such mention so it does not describe stale state, following this file's
   own established "narrative sections are corrected, not deleted" convention.
5. **(story review L6)** `ConfigResourceIT.java:238`'s own doc comment states "4" `HAS_CODE_DEFAULT`
   keys `V139__baseline_seed_data.sql` never seeded — AC1 adds a 5th. Update this count as part of
   the sweep (no test asserts the count numerically today, per `ConfigBoundsEnumCoverageTest.java:
   112-121`, so this is doc drift, not a build break — but correct it while in the area).
6. **(story review L4)** Add a new `## Last audit: <implementation date> (skillars-deferred-129
   dev-story completion)` heading. This story's three source sections are not adjacent
   (`deferred-work.md:2041`/2049, `:2195`/2198, `:2994`/3001) — one heading cannot sit "immediately
   above" all three the way prior single-section closeouts could. Place it immediately above the
   freshest section it touches (`:2994`, the skillars-deferred-128 same-day section) and summarize
   the edits made to the other two non-adjacent sections in its own body text (cross-referencing
   each by line/section name) — this file's own earlier multi-section prune entries (e.g. the
   skillars-deferred-110 post-merge prune) already establish this "one heading, prose
   cross-references non-adjacent edits" precedent; do not treat physical adjacency to every touched
   section as achievable or required.
7. Re-confirm this story's own "Out of scope" section above (the marketplace/reviews audit
   candidate, `markFailed`'s generic alerting gap, the `radar_composite_dlq` cleanup gap, the
   `main."user"` index gap, and the declined cumulative-budget mechanism) remains correctly
   untouched — do not close or reword any of those five as part of this AC.

### Tests

- None expected — this AC is a documentation-only ledger edit.

---

## Dev Notes

- This story bundles three independently-scoped, previously-identified gaps (not a fresh audit) —
  see Provenance & Scoping above for exactly which ledger sections each AC closes (or, per AC4's
  reworded items, partially closes) and why the freshest same-day section alone was too thin to
  stand as its own story.
- AC1 touches files already touched by skillars-deferred-127/-128 (`GdprErasureService`,
  `ConfigBounds`) and now also `PlayerTimelineRepository` (new to this series' scope). AC3 touches
  files from skillars-deferred-111 (`RegistrationEmailDurabilityIT`, `EnvelopeEntityRepository`)
  plus, per the story review's own M10 finding, `VideoModerationAdminAlertEnvelopeIT` (not
  previously touched by this series). Read each file's own recent history/Javadoc in full before
  editing, per this project's own "read files being modified" convention; do not assume the
  citations above are still accurate without re-checking `HEAD` first.
- AC1's `story-review.md` pre-implementation audit changed this AC's own scope materially (from "one
  set_config call bounds the method" to "each statement is bounded, honestly documented, plus a
  PLAYER-branch alerting fix and a failure-cause discrimination fix") — implement against THIS
  revision's Context/Tasks, not the original story-creation framing that predates the review.
- AC2 is the only AC in this story that is not a Java/Maven change — it is a GitHub Actions
  workflow-file change, verified by observing a real CI run rather than a local test. No `mvn
  verify` is run locally for this story either way, per this project's established convention.
- No frontend Vue/JS source is touched by this story (AC2 only touches the CI workflow that decides
  *when* the existing frontend test suite runs, not the suite itself) — confirm via `git status
  --short` before opening the PR whether the `frontend-tests` label is needed under the *current*
  (pre-AC2) gating rules, the same manual check this story's own AC2 exists to make unnecessary for
  future stories.

### References

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` — AC1
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` — AC1
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` — AC1 (test seam, floor/fallback semantics)
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java` — AC1
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` — AC1 (pattern mirrored, and the trap it already solves for its own method)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java` — AC1 (real test seam precedent)
- `.github/workflows/frontend-unit-tests.yml` — AC2
- `docs/testing/frontend-unit-tests.md` — AC2
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/RegistrationEmailDurabilityIT.java` — AC3
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationAdminAlertEnvelopeIT.java` — AC3
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerDuplicateSendIdIT.java` — AC3 (lazy-collection constraint precedent; the one `findAll()` hit correctly left alone)
- `src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java` — AC3
- `src/main/java/com/softropic/skillars/platform/notification/repo/RecipientEntity.java` — AC3
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC4

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-09-22: Story created via manual ledger-mining process (this project's established convention
  for `skillars-deferred-N` stories). The freshest same-day code-review deferral (skillars-
  deferred-128's own, one bullet) was too thin alone for this series' "do not create small stories"
  convention; the owner declined a fresh ad-hoc audit of `platform.marketplace`/`platform.reviews`
  (the only two modules never swept under this series' concurrency/TOCTOU lens) and instead selected
  two previously-identified, still-open, low-risk ledger items to bundle in (`AskUserQuestion`). Two
  further owner decisions taken live: (1) AC1's lock_timeout mechanism — a new `ConfigBounds`
  runtime-tunable key mirroring `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own bounds, over a plain
  constant; (2) AC2's CI fix — auto-detect frontend-path changes via a hand-rolled `git diff`-based
  step, over promoting the job to an unconditional required check.

- 2026-09-22: `/story-review` response applied (senior-dev pre-implementation audit,
  `story-review.md`). 3 blocking + 7 medium + 7 low findings, every one independently re-verified
  against actual source (`GdprErasureService`, `RadarCompositeCalculationService`,
  `PlayerTimelineRepository` and its ten siblings, `ConfigService`, `ConfigBounds`,
  `EnvelopeEntity`/`RecipientEntity`, `EnvelopeEntityRepository`, `AbstractIntegrationTest`,
  `RegistrationEmailDurabilityIT`, `VideoModerationAdminAlertEnvelopeIT`,
  `MailManagerDuplicateSendIdIT`, `frontend-unit-tests.yml`, `V138__baseline_schema.sql`,
  `application.yaml`, `deferred-work.md`'s three source sections) before applying — zero false
  positives found. Two further owner decisions taken live (`AskUserQuestion`): (1) AC1's `lock_timeout`
  fix stays the simpler per-statement bound (honestly documented as `N × seconds`, not a method-level
  ceiling) plus converting `PlayerTimelineRepository.deleteByPlayerId` to a real bulk delete, rather
  than porting `RadarCompositeCalculationService`'s full cumulative spend-down budget mechanism
  (considered, more correct, judged too complex for this story's own scope); (2) AC1 now also wraps
  the previously-unmentioned PLAYER-branch call site (`GdprErasureService.java:191`) with equivalent
  skip-and-alert semantics to the PARENT branch, rather than leave it unwrapped as a new, unalerted
  `FAILED` exposure on the most common account shape.

  Most significant blocking findings: (H1) a single `set_config` call bounds each of ~12
  independently-timeout-able statements individually, not the method's total wait — Postgres
  `lock_timeout` is per-statement, the exact trap this codebase's own
  `RadarCompositeCalculationService` already documents and solves differently for its own method;
  resolved per the owner decision above, plus converting the one non-bulk delete
  (`PlayerTimelineRepository.deleteByPlayerId`, previously a derived N+1-shaped delete unlike all
  ten siblings) to a real bulk statement so the statement count is at least fixed. (H2) the
  PLAYER-branch call site had no catch at all — resolved per the second owner decision above. (H3)
  AC3's originally-dictated exact JPQL (`JOIN`, not `JOIN FETCH`) would have thrown
  `LazyInitializationException` on two currently-green assertions, since `EnvelopeEntity.recipients`
  is a LAZY `@ElementCollection` and `AbstractIntegrationTest` is not `@Transactional` — fixed with
  `LEFT JOIN FETCH` plus a separate filtering predicate (not filtering the fetched join directly,
  which would silently prune the returned collection).

  Medium findings applied: (M1) AC1's config-value *read* moved before the lock acquisition (the
  `set_config` *statement* itself stays after, per the precedent's own actual placement — the
  original Task 4 had inverted this). (M2) AC1's test seam corrected from a non-existent
  `@TestPropertySource`-to-`ConfigService` bridge to the real, already-established
  `jdbcTemplate.update(platform_config) + configService.invalidate()` seam, and the floor corrected
  to 2s (the 4-arg `getBoundedLong` falls back to default on out-of-range, it does not clamp). (M3)
  AC4's ledger deletion of `deferred-work.md:2198` narrowed to a reword — its headline claim (the
  scheduler's whole-table poll) is untouched by this story; only its `findAll()` subordinate clause
  is closed. (M4) AC4's ledger deletion of `:2049-2061` narrowed to a reword — its core non-gating
  claim survives AC2 unchanged; only the "remembers the label" clause closes. (M5) AC4's ledger
  deletion of `:3001-3022` narrowed to a reword — a second, distinct hazard (pooled-connection
  acquisition wait, unaffected by `lock_timeout`) remains genuinely open. (M6) AC1 gained an explicit
  failure-cause discrimination (a new `CHILD_DELETE_LOCK_TIMEOUT`-style reason, distinct from the
  existing `CHILD_CONTENDED`), since the existing catch's own comment already warns against silently
  reusing it for a new thrower without re-verifying the assumption still holds. (M7) AC2's job-vs-step
  ambiguity resolved to a mandatory separate job (a job-level `if:` cannot reference another step's
  output within the same job). (M8) AC2 gained three corrections: three-dot/merge-base diff (not
  two-dot, which would false-positive on unrelated master-branch churn), a `workflow_dispatch` guard
  (no PR payload on that event), and a per-job `permissions:` block matching this file's own
  convention. (M9) AC3's "indexed by composite PK" justification corrected — the composite PK's
  leading column is `envelope_entity_id`, not `email`, so no index actually serves the new query;
  the real, still-genuine benefit is avoiding `findAll()`'s full-table materialization. (M10) AC3's
  scope widened to a second, previously-missed `findAll()` scan in `VideoModerationAdminAlertEnvelopeIT`
  (structurally identical, no documented justification), fixed by the same new repository method; a
  third hit (`MailManagerDuplicateSendIdIT.rowCountForSendId`) confirmed to have a genuinely
  different, legitimate reason and correctly left alone.

  Low findings applied: (L1) a cited migration file
  (`V137__envelope_entity_recipients_composite_pk.sql`) does not exist — squashed into
  `V138__baseline_schema.sql`; corrected here and flagged for correction in `RecipientEntity.java`'s
  own stale Javadoc while AC3 is already in that file. (L2) "constructor-injected fields" corrected
  to reflect `GdprErasureService`'s actual Lombok `@RequiredArgsConstructor` shape — a field
  addition, not a constructor edit. (L3) `GdprErasureIT`'s actual path corrected (`admin/api/`, not
  `admin/service/`). (L4) AC4's "immediately above all three sections" placement instruction
  corrected to an achievable single-heading-plus-cross-referencing-prose approach, since this
  story's three source sections are not adjacent. (L5) AC2 gained a `concurrency:` group (this
  workflow currently has none), with the `labeled`-event amplification noted as a documented,
  accepted low-cost residual rather than separately engineered around. (L6) AC4's ledger sweep
  widened to include `ConfigResourceIT.java`'s own stale "4 `HAS_CODE_DEFAULT` keys" doc count
  (becomes 5 after AC1). (L7) two minor line-citation corrections (`application.yaml`'s "STALE ON
  ARRIVAL" block actually begins at `:97`, not `:93`; `RecipientEntity.java`'s `email` field is at
  `:24-25`, not `:24-27`).

  See `story-review.md` for full finding-by-finding detail.
