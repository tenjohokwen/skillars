# Senior-dev audit — `skillars-deferred-86`

Story: `skillars-deferred-86-slu-snapshot-write-idempotency-async-retry-bulkhead-and-coach-radar-preferences-coach-fk.md`
Reviewer pass date: 2026-08-31
Source verified against: tip of `master` after `skillars-deferred-85` (`#130`).

## Verdict

The story is well-researched and mostly sound. **AC1 (snapshot idempotency), AC3 (bulkhead), and AC5 (ledger hygiene) are correctly motivated and the mechanisms are right.** Two items carry a factual/analytical error that should be resolved before or during dev (H1, H2), and there are four medium test/design gaps. None of the findings below are style nits dressed up as bugs — each is checked against current source.

Key confirmations (so these are *not* re-raised as doubts):

- AC1's core problem is real: `SluWeeklySnapshotRepository.upsertAdd` is `total_slu = existing + EXCLUDED.total_slu` (`SluWeeklySnapshotRepository.java:22`), and `SnapshotBatchWriter.writeAll` is a plain `for` loop over it inside one `@Transactional` — a whole-method retry after a server-side-committed-but-reported-failed `TransactionSystemException` genuinely double-adds. The CTE `upsertAddIdempotent` design (marker `INSERT … ON CONFLICT DO NOTHING RETURNING 1`, gated main upsert via `WHERE EXISTS (SELECT 1 FROM ins)`) is valid PostgreSQL and correctly makes the retry a no-op while preserving additive-across-distinct-sessions semantics.
- `player_slu_weekly_snapshot` columns are exactly `(player_id, skill_code, iso_year, iso_week, total_slu)` with `total_slu … DEFAULT 0` (`V48__development_exposure_dashboard.sql:5-13`) — the `upsertAddIdempotent` INSERT column list is complete; no hidden NOT NULL / surrogate-key column.
- AC4 migration facts check out: `marketplace.coach_profiles.id` is `UUID PRIMARY KEY` (`V26:4`); `coach_radar_preferences.coach_id` is `UUID NOT NULL` and **is** the leading column of PK `(coach_id, player_id)` (`V51:13-16`) → no separate `ix_crp_coach_id` needed, story is right.
- AC5's `skillars-deferred-40` "V98 widens DEF3 race" closure is correct: `RadarCompositeCalculationService.recalculateComposite` (`:80-87`) runs *both* `findAggregatesByPlayerAndSkills` and `findDistinctCoachCountsByPlayerAndSkills` inside the `lockRetryer.withBoundedRetry(findByIdForUpdate)` + `entityManager.refresh(PESSIMISTIC_WRITE)` player-row lock. Concurrent same-player submissions cannot interleave.
- `upsertAdd` / `writeAll` / `saveSluWithRetry` / `writeAllWithRetry` grep: the only production callers are `SnapshotBatchWriter` and `SluCalculationService` respectively — there is no backfill / admin-recompute path that AC1's marker gate would silently break. (Good; this was a real risk worth the story's "grep and record".)
- `V117` is the current max migration; `V118`/`V119` are free.
- No new Spring context is required by any proposed test — `SluCalculationServiceIT` already carries `@MockitoBean VideoProviderAdapter`, so extending it adds nothing; a new `CoachRadarPreferencesFkIT extends AbstractIntegrationTest` with no context-fragmenting annotation reuses the cached context.

---

## HIGH

### H1 — AC2's stated failure mode (`DataIntegrityViolationException` on retry → misleading "N rows lost" `@Recover` log) is very likely a false premise

AC2 exists "so the same ambiguous-commit retry stops raising a spurious `DataIntegrityViolationException` and logging a misleading `"N rows lost … manual recovery needed"`". That narrative is inherited verbatim from the `skillars-deferred-85` ledger bullet ("pre-assigned `GenerationType.UUID` PKs — then recovers with a misleading … log"). It does not survive inspection of the actual entity/`saveAll` path:

- `PlayerSkillStat` (`PlayerSkillStat.java:20-22`) is `@Id @GeneratedValue(strategy = GenerationType.UUID) UUID id`, **no `@Version`, no `Persistable`**. So `JpaMetamodelEntityInformation.isNew()` reduces to `id == null`.
- `GenerationType.UUID` is an in-VM *before-insert* generator: Hibernate assigns the id onto the entity instance during `persist()`, before flush. On an ambiguous-commit failure the instances keep that id (`hibernate.use_identifier_rollback` defaults `false`).
- `@Retryable` re-invokes `saveSluWithRetry(rows)` with the **same** `PlayerSkillStat` instances. On the retry `id != null` → `SimpleJpaRepository.save` routes each entity to `em.merge(...)`, **not** `em.persist(...)`.
  - Ambiguous-commit case (row was actually committed): `merge` does a SELECT-by-id, finds the row, and — since every non-key column is `updatable = false` — issues no meaningful UPDATE. **No exception. No duplicate row.**
  - Genuine-rollback case (clean slate): `merge` finds nothing, treats the entity as transient, INSERTs with the retained id. Works.

In no reachable path does the retry perform a colliding `persist`, so neither the `DataIntegrityViolationException` nor the misleading `recoverSluSaveFailure` "rows lost" log that AC2 is written to eliminate should actually occur today.

**Why this matters:** AC2 as scoped (new `existsBySessionId` derived query + check-then-act in `saveSluWithRetry`) is harmless defense-in-depth, but the story sells it as closing a silent-data-integrity / misleading-ops-signal bug, and AC5 then removes the `skillars-deferred-85` ledger bullet on that basis. If the premise is wrong, the ledger note and story rationale are wrong, and the added query + branch are a micro-optimisation (skip one redundant merge/SELECT round-trip on retry) rather than a fix.

**Action:** before implementing AC2, write a test that reproduces the *current* (pre-change) behaviour — persist+commit attempt 1, then throw `TransactionSystemException` from the commit boundary, then let `@Retryable` retry — and assert what actually happens (duplicate rows? DIVE? clean merge?). Then:
- if merge already makes it safe: keep AC2 only if the redundant retry round-trip is worth a query, and **correct the story + the `skillars-deferred-85` ledger bullet** to say "retry is already safe on the SLU side via `merge`-on-detached; the real gap was snapshot-side only".
- if it genuinely produces duplicates/DIVE (e.g. some `saveAll` batching path I've not accounted for): keep AC2 as-is and add the reproducing test as the regression guard.

Note the asymmetry that gives this away: AC1's snapshot side genuinely double-counts because `upsertAdd` is a raw additive native upsert with no entity-identity round-trip; the SLU side goes through Hibernate entity identity, which is exactly what protects it.

### H2 — AC4's description of the current `coach_radar_preferences` write path is factually wrong

AC4 states: *"`coach_radar_preferences` has no JPA entity in `src/main` (it is written via a native `upsert` in `RadarDisplayService` — verify by grep; if an entity exists, the FK is transparent to it …)"*.

Both factual claims are false:

- There **is** a JPA entity: `CoachRadarPreference` (`platform/development/repo/CoachRadarPreference.java`, `@Entity @Table(schema="development", name="coach_radar_preferences")`, `@EmbeddedId CoachRadarPreferenceId`), plus `CoachRadarPreferenceRepository extends JpaRepository<…>`.
- It is **not** written via a native upsert. `RadarDisplayService.savePreferences` (`RadarDisplayService.java:82-100`) does `preferenceRepository.findByIdCoachIdAndIdPlayerId(...)` then `preferenceRepository.save(pref)` — ORM `persist`/`merge`.

The story's *conclusion* still holds (adding a DB-level `ON DELETE CASCADE` FK needs no entity or `@ManyToOne` change; the FK is transparent to `save`). But the confident wrong description will send the dev looking for a native query that doesn't exist, and it undermines the "verify by grep" instruction. Fix the AC4 text to: "an entity `CoachRadarPreference` + `CoachRadarPreferenceRepository` exist and use ORM `save`; the new DB FK requires no mapping change and no `@ManyToOne` (consistent with V113/V117 adding none)."

---

## MEDIUM

### M1 — AC1 does not update `SluCalculationServiceIT`'s `@AfterEach` to purge the new marker table

`SluCalculationServiceIT.cleanUpSluRows()` (`:62-69`) deletes `player_skill_stats` and `player_slu_weekly_snapshot` for `TEST_PLAYER_ID` after every test, and its own comment explains why: `player_skill_stats` is append-only so a leak makes count-based assertions order-dependent. AC1 introduces `player_slu_weekly_snapshot_applied`, written for `TEST_PLAYER_ID` by the new AC1 test cases (and by every `writeAll` the IT already triggers), but the story's Task 2 / AC1 test list never says to add the third `DELETE`.

Consequence: marker rows accumulate across the test run. They are keyed on `session_id`, and the ITs currently mint a fresh `bookingId = UUID.randomUUID()` → fresh session per method, so *today* there is no cross-method PK collision — but (a) it is a latent order-dependency trap the moment any test reuses a session id or asserts marker-table counts, and (b) it contradicts the file's established "every test that writes rows for the shared player cleans them up itself" contract. The `ON DELETE CASCADE` FK to `player_profiles` does **not** save this — the ITs don't delete the player.

**Action:** add `jdbcTemplate.update("DELETE FROM development.player_slu_weekly_snapshot_applied WHERE player_id = ?", TEST_PLAYER_ID);` to `cleanUpSluRows()`, and call it out in the AC1 task list.

### M2 — `sluRetryExecutor` sizing (core 2 / queue 50) blunts the bulkhead during exactly the burst it targets

`ThreadPoolTaskExecutor` only grows past `corePoolSize` toward `maxPoolSize` once the queue is **full**. With `corePoolSize=2, queueCapacity=50`, a DB-hiccup burst of booking completions is serviced by **two** threads until 50 tasks are queued; threads 3–4 only start after that backlog exists. Each task parks through a `@Backoff` chain (`~100 → 200 ms`, up to `max-attempts:3`, i.e. up to ~300 ms+ of sleeping) so two threads drain on the order of ~6 tasks/s — a full 50-deep queue is ~8 s of latency before parallelism even increases, and then `CallerRunsPolicy` on saturation pushes work back onto the listener thread, which is the precise stall AC3 is meant to remove.

The isolation goal (SLU/snapshot retries never park the shared `taskExecutor` listener pool under a *transient* hiccup) is still met for modest bursts. But the story frames AC3 as neutralising listener-pool starvation; under a *sustained* DB outage with a real burst it only bounds the blast radius.

**Action:** either shrink `queueCapacity` (e.g. 8–10) so max threads engage quickly, or raise `corePoolSize` toward `maxPoolSize`, and consider `allowCoreThreadTimeOut(true)`. At minimum, state in the Dev Notes / javadoc that the bulkhead *bounds* rather than *eliminates* listener-thread stalls once `sluRetryExecutor` saturates, and that `CallerRunsPolicy` there is a deliberate fall-back to pre-story behaviour.

### M3 — AC3's concurrency safety argument covers the DB but not the shared in-JVM entity list

AC3 makes `dispatchSluSave` and `dispatchSnapshotWrite` run **concurrently** on `sluRetryExecutor` (Dev Notes, "Ordering note"). The safety argument given is purely about the database: "`writeAll` reads nothing from `player_skill_stats`". True, but incomplete:

- Today the two persistence calls run strictly sequentially on one thread.
- After AC3, thread A runs `saveSluWithRetry → sluRepository.saveAll(stats)`, which **mutates** each `PlayerSkillStat` (Hibernate assigns `id`, entity becomes managed in A's persistence context), while thread B runs `writeAll(stats, …)` iterating the *same* `List` and the *same* instances, reading `getSessionId()/getPlayerId()/getSkillCode()/getSluValue()`.
- That is unsynchronised concurrent access to shared non-`volatile` mutable objects across two threads.

In practice the fields B reads are all `updatable = false` and already populated before dispatch, and A only writes `id`, so logical corruption is unlikely — but "unlikely" via accidental field-disjointness is not a safety argument, and it is a real behavioural change from "sequential over one thread".

**Action:** make it explicit — either keep snapshot dispatch chained after save completes, or have the dispatcher pass the snapshot path an immutable projection / defensive copy (it only needs 4 scalars per row), or expand the Dev Notes to argue field-level disjointness (A writes only `id`; B reads only the four `updatable=false` scalars) and add a comment at the dispatch site so a future reader doesn't add a field B also reads.

### M4 — AC4's CASCADE test can trip unrelated non-cascade FKs on `marketplace.coach_profiles`

The proposed AC4 IT "deletes the `marketplace.coach_profiles` row and asserts the preference row is gone". `coach_profiles` is a parent to many tables — `coach_specialties`, `coach_age_groups`, `coach_pricing`, `coach_availability_windows`, `coach_subscriptions` (`V26`), plus `player_skill_stats.coach_id` (`UUID NOT NULL`), bookings, reviews, `player_slu_targets.coach_id`, etc. Any of those FKs that is *not* `ON DELETE CASCADE` will make a raw `DELETE FROM marketplace.coach_profiles WHERE id = ?` fail with a constraint violation unrelated to what the test is checking.

**Action:** seed a coach profile with *no* other references (the `SecurityIT.SEC_DATA_SQL_PATH` fixture coach is likely clean, but verify it has no bookings / skill stats / targets), or delete the child rows first, or assert the CASCADE by deleting via the same path production uses. Document the chosen approach so the test isn't silently brittle against future coach child tables.

---

## LOW / nitpicks

- **L1 — "caught by the app-wide `AsyncUncaughtExceptionHandler`" (AC3 javadoc guidance) is unverified and probably wrong.** `MdcDecorator.decorate` (`infrastructure/threadpool/MdcDecorator.java:20-23`) wraps `runnable.run()` in `catch (Exception e) { … log.error("Exception thrown from detached thread…", …) }` — it swallows and logs task exceptions itself. Whether Spring's `AsyncUncaughtExceptionHandler` (`infrastructure/config/AsyncConfig.java:48-52`) ever sees an exception from an `@Async void` method run through this decorator is at best unspecified. This is pre-existing for `taskExecutor`/`onBookingCompleted` so not a regression, but don't assert a failure path you haven't checked. Since each retrier's `@Recover` returns normally on exhausted retries, the executor task rarely throws at all — say that instead.

- **L2 — concurrent duplicate `BookingCompletedEvent` for one session: AC2's Dev Notes overstate coverage on the SLU side.** Source-side guards (booking state machine + `OptimisticLockingFailureException` in `BookingCompletionService.submitWrapUp`/`confirmCompletion`, `QuickCompleteTimeoutService`) make a duplicate event for one session effectively unreachable, and AC1's marker unique constraint fully protects the *snapshot* side even if it happened. But the *SLU* side (AC2) has no DB backstop: two concurrent `saveSluWithRetry` for one session both pass `existsBySessionId` and both `saveAll` → duplicate `player_skill_stats` rows, undetected. AC3's async dispatch widens the check→write window. The Dev Notes claim the entry guard + AC2 check "address different scenarios" and "both are wanted" — fine, but add one line acknowledging the concurrent case is closed by neither (accepted as very-low-probability, no unique constraint on `session_id` because it is nullable and legitimately repeats per row).

- **L3 — `upsertAdd` is fully dead after AC1.** Grep confirms `SnapshotBatchWriter.writeAll` is its only caller and that becomes `upsertAddIdempotent`. The story leaves "remove it or leave a `// superseded` note" to the dev — recommend deleting it outright; a dead additive-upsert with no idempotency guard is exactly the footgun this story exists to remove, and leaving it invites a future caller.

- **L4 — the marker table's `skill_code` FK is stronger than the table it guards.** `player_slu_weekly_snapshot` itself has only a `skill_code` FK and **no** `player_id` FK (`V48:5-13`). AC1's marker adds both `skill_code → skill_definitions(code)` and `player_id → main.player_profiles(id) ON DELETE CASCADE`. That's defensible (defense-in-depth, matches V113/V117 pattern), but note the asymmetry in the migration comment so a future reader doesn't "tidy it up".

- **L5 — interaction with a previously-dismissed ledger item.** `skillars-5-1` Pass 2 D5 ("No `booking_id` stored in `player_skill_stats` — no DB-level idempotency anchor … schema addition out of scope", `[DISMISSED 2026-08-30]`). AC2 now leans on `session_id` as that app-level anchor via check-then-act. Add a one-line nod in Dev Notes that `session_id` is the accepted app-level idempotency anchor and a DB unique constraint remains deliberately out of scope (and would be wrong — `session_id` is nullable and repeats per row within a session).

- **L6 — "write V118 before V119 so the ordering is V118 then V119" rationale is slightly off.** Flyway orders by version number regardless of file authoring order, and the two migrations touch independent tables with no dependency. The instruction is harmless; the stated reason isn't a real constraint. (If the intent is "lower number = the not-yet-scoped-here concern gets the earlier slot", say that.)

- **L7 — AC3 adds a second async hop to `SluCalculationServiceIT`.** Writes now traverse `taskExecutor` (`onBookingCompleted`) → `sluRetryExecutor` (retrier). The existing `await().atMost(3, SECONDS)` polls should still pass, but consider bumping to 5 s when extending the IT, given `sluRetryExecutor` core=2 and CI load.

---

## Scope / completeness observations (not defects)

- The story does not add a config-property namespace for `sluRetryExecutor` sizing (core/max/queue are hard-coded in `DevelopmentConfig`). Consistent with `infrastructure.config.AsyncConfig`'s `taskExecutor` (also hard-coded), so acceptable — but if M2's tuning is taken, consider `app.slu.retry-executor.*` expressions for post-deploy adjustment without a redeploy.
- AC1's `upsertAddIdempotent` is a data-modifying CTE issued through a Spring Data `@Modifying @Query(nativeQuery=true)` returning `void`. The story already flags "if the CTE behaves unexpectedly against Hibernate's native-query handling, stop and raise it" — good. Add to the AC1 test list an explicit assertion of the *newly-inserted-marker* path AND the *conflict/no-op* path at the repository level (not only via `SnapshotBatchWriter`), so a Hibernate statement-shape regression is caught close to the source.
- AC5 edits are precisely scoped and every target bullet was verified to exist with the quoted text. The `skillars-5-4` W1 append and the `skillars-deferred-40` / `skillars-5-2` W1 / `skillars-deferred-85` / `skillars-deferred-84` removals all line up with the live ledger. No issue.
