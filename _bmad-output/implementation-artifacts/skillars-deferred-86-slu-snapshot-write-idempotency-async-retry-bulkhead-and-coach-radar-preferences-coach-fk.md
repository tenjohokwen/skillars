# Story skillars-deferred-86: SLU/snapshot write-path idempotency key, an isolated retry-executor bulkhead, and the coach_radar_preferences.coach_id FK

Status: ready-for-dev

## Story

As the platform owner,
I want (1) the weekly SLU snapshot write to carry a per-(source session, weekly bucket) write-once marker so a retried ambiguous-commit `TransactionSystemException` can no longer re-apply its additive `upsertAdd` deltas and silently inflate a player's weekly SLU totals, (2) the append-only `player_skill_stats` save to become an explicit check-then-act keyed on `session_id` so the same ambiguous-commit retry stops raising a spurious `DataIntegrityViolationException` and logging a misleading `"N rows lost … manual recovery needed"`, (3) the two SLU persistence retriers to run their `@Backoff` sleeps on a small dedicated bounded executor instead of the shared `@Async` listener pool, so a DB hiccup during a burst of booking completions can no longer stall SLU/snapshot processing for every other player behind parked listener threads, and (4) `development.coach_radar_preferences.coach_id` given the FK to `marketplace.coach_profiles(id)` that `skillars-deferred-84` AC1 (V117) explicitly left out of scope,
so that `skillars-deferred-85`'s own code-review deferral (the un-guarded additive-upsert double-count the project owner routed to a dedicated story) closes, the last widened-`retryFor` risk in the SLU write path is neutralised, and the two remaining still-open SLU/Radar ledger bullets (`skillars-deferred-40`'s stale DEF3-race claim, `skillars-5-2` W1's partial-snapshot gap) stop being re-flagged by every full-file audit.

## Story creation context

Per the standing `deferred-work.md` re-mining priority order (SLU/Radar first — see `[[project_skillars_release_workflow]]`):

**SLU/Radar is down to its last cluster of actionable work, and all of it descends from the `skillars-deferred-84` / `-85` SLU-resilience thread.** A live re-scan of every `skillars-5-1`…`5-6`, `RadarComposite*`, and `Slu*` section of `deferred-work.md` against current source (2026-08-31, tip of `master` after `skillars-deferred-85` merged as `#130`) found:

- **`skillars-deferred-85`'s own code-review section** (`## Deferred from: code review of skillars-deferred-85 …`, 2026-08-31) opens with the one genuinely-open, project-owner-routed SLU item: **the SLU snapshot write path has no idempotency guard.** `skillars-deferred-85` AC1 widened both `SluPersistenceRetrier` and `SnapshotPersistenceRetrier` `retryFor` to include `TransactionSystemException` and `CannotCreateTransactionException`. `CannotCreateTransactionException` (begin failed → nothing ran) is unambiguously safe to whole-method-retry. `TransactionSystemException` is **not**: it is raised on a commit/rollback-phase system error, which *includes* the case where PostgreSQL committed server-side but the client lost the connection/ack. `SnapshotBatchWriter.writeAll` is `@Transactional` and issues only additive `upsertAdd` calls (`total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu` — confirmed in `SluWeeklySnapshotRepository`), so a whole-method retry after a *successful-but-reported-failed* commit re-adds every delta: the affected player/skill/week totals inflate 2×/N×, silently, with no dedup key. On the sibling `SluPersistenceRetrier.saveAll` path the same scenario instead hits `DataIntegrityViolationException` on the retry (pre-assigned `GenerationType.UUID` PKs collide), which the `@Recover` method absorbs with a `log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed", …)` — a **misleading** signal that prompts an unnecessary, potentially double-counting manual recovery. **Project-owner decision, recorded in that ledger section (2026-08-31, during `skillars-deferred-85`'s code review): keep the wide `retryFor` — the observability win is real — and close the gap in a dedicated story that adds a persistence-level idempotency key + a Flyway migration.** → **AC1** (snapshot side) + **AC2** (SLU `saveAll` side).
  - **Project-owner decision, 2026-08-31 (release-workflow discussion at `skillars-deferred-86` creation): idempotency granularity = a per-`(session_id, player_id, skill_code, iso_year, iso_week)` write-once marker.** The snapshot upsert becomes conditional — apply the additive delta only when *this* `(source session, weekly bucket)` pair has not already been recorded. This preserves additive weekly aggregation **across distinct sessions** (two different sessions in the same ISO week still sum into one `player_slu_weekly_snapshot` row) while making a retry — or a duplicate replay — of *the same session's* batch a no-op. The SLU side uses the same principle with `session_id` as the natural key (the table already carries `session_id` and an index on it).
- **`## Deferred from: code review of skillars-deferred-84 …` (2026-08-31)** first bullet: **`@Backoff` retry sleeps run on the `@Async` booking-completed listener thread pool with no bulkhead.** `SluCalculationService.onBookingCompleted` is `@Async @TransactionalEventListener(AFTER_COMMIT)` and calls `sluPersistenceRetrier.saveSluWithRetry(stats)` then `snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek)` **synchronously on the listener thread**. Under a DB hiccup during a burst of booking completions, each listener thread now parks through **two sequential `@Backoff` chains** (default `100ms → 200ms` each, `max-attempts:3`) before failing over — so SLU + snapshot processing for every other player stalls behind the sleeping threads on the shared `taskExecutor` pool (`infrastructure.config.AsyncConfig`, core 4 / max 16 / queue 100, `CallerRunsPolicy`). `writeAllWithRetry` is not itself `@Async` and there is no bounded queue / bulkhead. → **AC3**.
  - **Project-owner decision, 2026-08-31 (this discussion): a dedicated bounded executor for the retriers** — a small fixed pool (`core 2 / max 4 / bounded queue ~50`) that `saveSluWithRetry` / `writeAllWithRetry` run on via a thin `@Async("sluRetryExecutor")` **dispatcher bean**, so the listener thread returns immediately and the retry sleeps happen on the isolated pool, never the shared `@Async` listener pool. **Keep `@Async` and `@Retryable` on separate beans** — the two retriers already exist as standalone `@Component`s specifically so `@Retryable` goes through the AOP proxy; the dispatcher must be a *third* bean that calls them cross-bean, never a second annotation stacked on the retrier methods (advisor-ordering between `@Async` and `@Retryable` on one method is unspecified and a documented foot-gun).
- **`## Deferred from: code review of skillars-deferred-84 …`** second bullet (**V117 not online-safe**) — an explicitly-accepted codebase-wide migration convention at current table size, **out of scope** (same call `skillars-deferred-85` made for it). AC4's V118 mirrors V117's shape deliberately.
- **`## Deferred from: code review of skillars-deferred-40 …` (2026-08-20)** first bullet claims `V98`'s new `findDistinctCoachCountsByPlayerAndSkills` query, run alongside `findAggregatesByPlayerAndSkills` inside `RadarCompositeCalculationService.onRadarEntrySubmitted`, *widens* the DEF3 concurrent-recalculation race. **Verified live and stale:** `RadarCompositeCalculationService.recalculateComposite` now serializes the **entire** read-aggregates-then-upsert sequence on `lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)…)` + `entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE)` (closed by `skillars-deferred-77` AC10 Phase 1, per the in-code comment at `recalculateComposite`'s top — never annotated in the ledger). **Both** queries run inside that lock; two concurrent submissions for the same player cannot interleave. → **AC5** ledger hygiene, close with a note that `skillars-deferred-77` AC10 Phase 1 closed it.
- **`## Deferred from: code review of skillars-5-2 …` (2026-06-19)** W1 (partial snapshot missing between `sluRepository.saveAll` and `snapshotBatchWriter.writeAll`) is already `[PARTIALLY ADDRESSED by skillars-deferred-77 AC8]` + `[PICKED UP by skillars-deferred-84 AC2: SnapshotPersistenceRetrier wraps writeAll]`, then `skillars-deferred-85` AC1 widened it to `TransactionException`, and **this story** adds the idempotency key on top. After AC1/AC3 the snapshot write has retry + structured `@Recover` + idempotency + an isolated executor — the residual "a failure strictly between the two writes loses the snapshot" is now as closed as it can be without a shared transaction (which is deliberately not wanted — the two writes are independent by design). → **AC5** ledger hygiene, assess and close. W2 in the same section is already `[AUDIT 2026-08-27: false premise …]`.
- **`## Deferred from: code review of skillars-5-4 …`** W1 residual — `skillars-deferred-84` AC1 (V117) added the `player_id` FK and its own text records that **`coach_radar_preferences.coach_id` still has no FK to `marketplace.coach_profiles(id)`**, "left for a future pass on its own merits". This is that pass. → **AC4**.

Everything else under `skillars-5-1`…`5-6` / `RadarComposite*` is `[DISMISSED …]`, `[DECIDED … leave as-is]`, or `[CLOSED …]`.

**Five ACs spanning two Flyway migrations, a new marker entity + repository, a batch-writer rework, a new bounded-executor config + dispatcher bean, a service-wiring change, a GDPR-erasure wiring addition, and their tests — comfortably past this project's "no small stories" bar.**

## Acceptance Criteria

### AC1 — The weekly SLU snapshot write is idempotent per `(session_id, player_id, skill_code, iso_year, iso_week)`.

**New Flyway migration `V119__player_slu_weekly_snapshot_applied.sql`** (next free number after `V117`; `V118` is AC4 — write `V118` first so the ordering is `V118` then `V119`):

- Create `development.player_slu_weekly_snapshot_applied`:
  ```sql
  CREATE TABLE development.player_slu_weekly_snapshot_applied (
      session_id  UUID         NOT NULL,
      player_id   BIGINT       NOT NULL,
      skill_code  VARCHAR(10)  NOT NULL REFERENCES development.skill_definitions(code),
      iso_year    SMALLINT     NOT NULL,
      iso_week    SMALLINT     NOT NULL,
      applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
      PRIMARY KEY (session_id, player_id, skill_code, iso_year, iso_week)
  );
  ```
  - Add `CONSTRAINT fk_pswsa_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE` — defense-in-depth against orphaned marker rows after a player deletion, mirroring `V113` / `V117`'s established pattern **exactly** (the explicit `deleteByPlayerId` wired into `GdprErasureService` below is the real erasure path; the FK is the backstop). Precede the `ADD CONSTRAINT` with the same defensive `DELETE FROM … WHERE NOT EXISTS (SELECT 1 FROM main.player_profiles …)` guard V113/V117 use, so the migration cannot fail on unexpected existing data (there is none today — the table is new — but keep the pattern identical for the next reader).
  - Add `CREATE INDEX ix_pswsa_player_id ON development.player_slu_weekly_snapshot_applied (player_id);` — `player_id` is not the leading PK column, so without a standalone index every `DELETE FROM main.player_profiles` cascade and every FK-integrity check sequentially scans the whole table. Same reasoning V117 spells out for its own `ix_crp_player_id`.
  - **No `platform_config` seed, no entity for `PlayerSluWeeklySnapshot` changes** — `player_slu_weekly_snapshot`'s own schema and `PlayerSluWeeklySnapshot` entity are untouched.

**New JPA entity + repository** (`src/main/java/com/softropic/skillars/platform/development/repo/`):

- `PlayerSluWeeklySnapshotApplied` — `@Entity @Table(schema = "development", name = "player_slu_weekly_snapshot_applied")`, `@EmbeddedId` composite key `PlayerSluWeeklySnapshotAppliedId` (`sessionId` UUID, `playerId` Long, `skillCode` String, `isoYear` Short, `isoWeek` Short) mirroring `PlayerSluWeeklySnapshot.PlayerSluSnapshotId`'s exact shape (`equals`/`hashCode` over all key fields, `@Getter @Setter @NoArgsConstructor`, `implements Serializable`). One non-key column: `appliedAt` (`Instant`, `insertable = true`, `updatable = false`, DB-defaulted). Mark the class `// IMMUTABLE: append-only — a row here means "this session's delta for this weekly bucket is already in player_slu_weekly_snapshot"` in the same house style as `PlayerSkillStat` / `PlayerSluWeeklySnapshot`.
- `PlayerSluWeeklySnapshotAppliedRepository extends JpaRepository<PlayerSluWeeklySnapshotApplied, PlayerSluWeeklySnapshotApplied.PlayerSluWeeklySnapshotAppliedId>` with **one** method: `@Modifying @Query("DELETE FROM PlayerSluWeeklySnapshotApplied a WHERE a.id.playerId = :playerId") int deleteAllByPlayerId(@Param("playerId") Long playerId);` — for the GDPR erasure path (mirrors `SluWeeklySnapshotRepository.deleteAllByPlayerId`). The idempotent write itself does **not** go through this repository — see below.

**Rework `SnapshotBatchWriter.writeAll`** (`…/development/repo/SnapshotBatchWriter.java`):

- Replace the `snapshotRepository.upsertAdd(...)` call per stat with a call to a **new single atomic native statement** that (a) inserts the marker row and (b) applies the additive delta *only when the marker row was newly inserted*. Add the method to `SluWeeklySnapshotRepository` (it already owns `upsertAdd`; keep both — `upsertAdd` may still have non-idempotent callers, verify by grep and note the result):
  ```java
  @Modifying
  @Transactional
  @Query(nativeQuery = true, value = """
      WITH ins AS (
          INSERT INTO development.player_slu_weekly_snapshot_applied
              (session_id, player_id, skill_code, iso_year, iso_week)
          VALUES (:sessionId, :playerId, :skillCode, :isoYear, :isoWeek)
          ON CONFLICT DO NOTHING
          RETURNING 1
      )
      INSERT INTO development.player_slu_weekly_snapshot
          (player_id, skill_code, iso_year, iso_week, total_slu)
      SELECT :playerId, :skillCode, :isoYear, :isoWeek, :sluValue
      WHERE EXISTS (SELECT 1 FROM ins)
      ON CONFLICT (player_id, skill_code, iso_year, iso_week)
      DO UPDATE SET total_slu = player_slu_weekly_snapshot.total_slu + EXCLUDED.total_slu
      """)
  void upsertAddIdempotent(@Param("sessionId") UUID sessionId,
                           @Param("playerId") Long playerId,
                           @Param("skillCode") String skillCode,
                           @Param("isoYear") short isoYear,
                           @Param("isoWeek") short isoWeek,
                           @Param("sluValue") BigDecimal sluValue);
  ```
  Both `INSERT`s run inside `writeAll`'s existing `@Transactional` boundary, so the marker and the delta commit or roll back together. On a genuine failure + retry: no markers persisted → `ins` returns a row on the retry → deltas applied once. On an ambiguous commit + retry: markers from the (actually-committed) first attempt are present → `ON CONFLICT DO NOTHING` yields no `ins` row → `WHERE EXISTS (SELECT 1 FROM ins)` is false → the snapshot `INSERT` matches zero rows → **no-op**. Two *distinct* sessions for the same `(player, skill, isoYear, isoWeek)` each insert their own marker (different `session_id`) → both deltas apply → weekly total is the sum, unchanged from today.
- `writeAll` gets each stat's `session_id` from `stat.getSessionId()` (already populated — `PlayerSkillStat.sessionId`, set on every row by `SluCalculationService`; the field is `updatable = false` and non-null on this path). **Guard**: if a passed `stat.getSessionId()` is `null` (not reachable from `SluCalculationService.onBookingCompleted`, which returns early when there is no session — but the method is `public` and unit-tested directly), skip that stat with a `log.warn` rather than passing `null` into the marker `INSERT` (`session_id` is `NOT NULL`). Document the guard.
- `SnapshotBatchWriter` stays a `@Component` in `platform.development.repo`, stays `@Transactional`, keeps its `for` loop shape — only the per-iteration call changes.

**Revisit `skillars-deferred-85` AC1's retry-safety Dev Notes.** That story's `SnapshotPersistenceRetrier` javadoc and its Dev Notes state a whole-method retry of `writeAll` "is safe … an uncaught exception mid-loop rolls the transaction back entirely (nothing partially commits) and the retry re-runs against a clean slate." That reasoning was **incomplete for the ambiguous-commit `TransactionSystemException` case** (the exact gap this story closes). Update `SnapshotPersistenceRetrier`'s class javadoc (and `SluPersistenceRetrier`'s, symmetrically) to say the retry is now safe **because** `writeAll` is idempotent per `(session, bucket)` marker (resp. `saveSluWithRetry` is a check-then-act on `session_id`), not merely because a rolled-back transaction leaves a clean slate. Do **not** change `retryFor` or the `@Recover` signatures.

- **Tests (reuse existing Spring contexts — see AC3 note; add no new `@SpringBootTest` / `@SpringJUnitConfig` context):**
  - Extend `SluCalculationServiceIT` (already `extends AbstractIntegrationTest`, already has the session + drill + `BookingCompletedEvent` fixture, reuses the shared IT context — **no new context**) with cases that call `snapshotBatchWriter.writeAll(stats, isoYear, isoWeek)` **twice** with the same `stats` list (same `session_id`) and assert `development.player_slu_weekly_snapshot.total_slu` reflects **one** application, plus a marker row exists per `(session, player, skill, year, week)`. The second `writeAll` call *is* the ambiguous-commit retry, semantically — no fault injector needed.
  - A second case: build `stats` for **two different `session_id`s** with the same `(player, skill, isoYear, isoWeek)`; call `writeAll` once per session; assert the weekly total is the **sum** (additive across sessions preserved).
  - A `null`-`sessionId` stat is skipped with a warn, not an exception (plain unit test on `SnapshotBatchWriter` with a mocked `SluWeeklySnapshotRepository`, matching `SnapshotPersistenceRetrierTest`'s plain-instantiation style — verify the repo method is not called for the null-session stat).
  - `mvn -o test -Dtest=SluCalculationServiceIT,SnapshotPersistenceRetrierTest` green.

### AC2 — The append-only `player_skill_stats` save is an explicit check-then-act on `session_id`.

- **`SluRepository`** (`…/development/repo/SluRepository.java`): add `boolean existsBySessionId(UUID sessionId);` (Spring Data derived query — `session_id` is indexed by `V46`'s `idx_player_skill_stats_session_id`). Keep `findBySessionId` (still used by `SluCalculationService`'s entry guard and by `SluCalculationServiceIT`).
- **`SluPersistenceRetrier.saveSluWithRetry`** (`…/development/service/SluPersistenceRetrier.java`): make it check-then-act:
  ```java
  public void saveSluWithRetry(List<PlayerSkillStat> rows) {
      if (rows.isEmpty()) return;
      UUID sessionId = rows.get(0).getSessionId();
      if (sessionId != null && sluRepository.existsBySessionId(sessionId)) {
          log.debug("SLU rows already persisted for session {} — save skipped (idempotent retry no-op)", sessionId);
          return;
      }
      sluRepository.saveAll(rows);
  }
  ```
  Every `PlayerSkillStat` in one `saveSluWithRetry` call shares one `session_id` (they come from a single `SluCalculationService.onBookingCompleted` invocation for one session). The check runs **inside** `@Retryable`'s proxied method, so it re-evaluates on every attempt: a genuine first-attempt failure → rows absent → `saveAll` runs; an ambiguous-commit retry → rows present → no-op return, **no** `DataIntegrityViolationException`, **no** misleading `recoverSluSaveFailure` "rows lost" log.
- **Keep the `@Retryable` / `@Recover` structure and `retryFor` set exactly as they are.** `saveSluWithRetry` stays on the `SluPersistenceRetrier` bean so the AOP proxy still fires (the `SluRetrierProxyRetryTest` proof stays valid). The `@Recover` methods keep their bodies — they are still the terminal handler if `saveAll` itself fails for a genuine, non-ambiguous reason across all attempts.
- **Do not** add a marker table for the SLU side — `player_skill_stats` already carries `session_id` and is append-only, so `existsBySessionId` *is* the write-once check. A marker table here would be redundant state.
- **`SluCalculationService.onBookingCompleted`'s existing entry guard** (`if (!sluRepository.findBySessionId(session.getId()).isEmpty()) { … return; }`) stays — it covers the *duplicate-event-delivery* case (a whole second `BookingCompletedEvent`) and short-circuits before any compute. AC2's check covers the *retry-within-one-invocation* case. Both are wanted; note in Dev Notes they address different scenarios.
- **Tests:**
  - Add to `SluPersistenceRetrierTest` (plain Mockito, no Spring context — matches the file's style) a case: stub `sluRepository.existsBySessionId(any())` → `true`, call `saveSluWithRetry(rows)` with rows carrying a `sessionId`, `verify(sluRepository, never()).saveAll(any())` and `assertThatCode(...).doesNotThrowAnyException()`. A second case: `existsBySessionId` → `false` → `verify(sluRepository).saveAll(rows)`. A third: empty list → neither called.
  - Add to `SluCalculationServiceIT` a case that publishes a `BookingCompletedEvent`, waits (Awaitility) for the SLU rows, then calls `sluPersistenceRetrier.saveSluWithRetry(sameStats)` **directly a second time** and asserts the `player_skill_stats` row count for that `session_id` is unchanged (no duplicate rows, no exception).
  - `mvn -o test -Dtest=SluPersistenceRetrierTest,SluCalculationServiceIT` green.

### AC3 — The SLU persistence retriers run on a dedicated bounded executor, not the shared `@Async` listener pool.

- **New bounded executor bean.** Add a `sluRetryExecutor` `ThreadPoolTaskExecutor` bean. Place it on `DevelopmentConfig` (`…/development/config/DevelopmentConfig.java`, already a module `@Configuration`) — add a `@Bean(name = "sluRetryExecutor")` method rather than a new config class, to keep the module's Spring wiring in one place:
  ```java
  @Bean(name = "sluRetryExecutor")
  ThreadPoolTaskExecutor sluRetryExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(2);
      executor.setMaxPoolSize(4);
      executor.setQueueCapacity(50);
      executor.setThreadNamePrefix("slu-retry-");
      executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
      executor.setTaskDecorator(task -> new MdcDecorator().decorate(task));
      executor.initialize();
      return executor;
  }
  ```
  `CallerRunsPolicy` on saturation pushes the work back onto the **listener** thread (the caller) — which is exactly the pre-story behaviour, i.e. a safe degradation, not a regression, and it applies backpressure rather than dropping SLU writes (`AbortPolicy` would drop them). `MdcDecorator` matches every other executor in this codebase (`infrastructure.config.AsyncConfig`, `notification.config.AsyncConfig`) so the retry logs keep the originating request's MDC. `@EnableAsync` is already active application-wide (`notification.config.AsyncConfig`) — do **not** add it again.
- **New dispatcher bean** `SluPersistenceDispatcher` (`…/development/service/SluPersistenceDispatcher.java`), a `@Component` with the two retriers injected:
  ```java
  @Async("sluRetryExecutor")
  public void dispatchSluSave(List<PlayerSkillStat> stats) {
      sluPersistenceRetrier.saveSluWithRetry(stats);
  }

  @Async("sluRetryExecutor")
  public void dispatchSnapshotWrite(List<PlayerSkillStat> stats, short isoYear, short isoWeek) {
      snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
  }
  ```
  The dispatcher → retrier calls are **cross-bean**, so each retrier's own `@Retryable` proxy still intercepts (the `SluRetrierProxyRetryTest` proof is unaffected — it exercises the retriers directly). The dispatcher carries **only** `@Async`, never `@Retryable`; the retriers carry **only** `@Retryable`, never `@Async`. This is the deliberate separation — stacking both annotations on one method leaves the advisor nesting order (`@Async` outside `@Retryable`? or inside?) unspecified.
  - Javadoc it in the house style: why a third bean (self-invocation would bypass both proxies; one method can't safely carry both annotations), and that a fire-and-forget failure is caught by the app-wide `AsyncUncaughtExceptionHandler` (`infrastructure.config.AsyncConfig` — logs `"Uncaught exception in @Async method …"`), while an *exhausted-retry* failure is still handled by each retrier's `@Recover` (structured "rows lost" log) before control returns to the executor thread.
- **`SluCalculationService.onBookingCompleted`** (`…/development/service/SluCalculationService.java`): replace the two synchronous calls
  ```java
  sluPersistenceRetrier.saveSluWithRetry(stats);
  …
  snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
  ```
  with
  ```java
  sluPersistenceDispatcher.dispatchSluSave(stats);
  …
  sluPersistenceDispatcher.dispatchSnapshotWrite(stats, isoYear, isoWeek);
  ```
  Inject `SluPersistenceDispatcher` in place of the two retriers (the retriers are now only referenced by the dispatcher). The two `log.info` / `log.debug` lines that currently follow each call ("SLU recorded: …", "Weekly snapshot updated: …") describe work that is now *dispatched*, not *done* — reword them to "SLU save dispatched: …" / "Weekly snapshot write dispatched: …" so the log does not overstate completion (the retriers already log their own success/failure).
  - **Ordering note:** `dispatchSluSave` and `dispatchSnapshotWrite` now run concurrently on `sluRetryExecutor` rather than strictly in sequence. This is safe — `SnapshotBatchWriter.writeAll` reads nothing from `player_skill_stats` (it upserts `player_slu_weekly_snapshot` from the in-memory `stats` list), so the snapshot write has no data dependency on the SLU `saveAll` completing. The pre-existing "snapshot is eventually-consistent and does not roll back with SLU rows" property (`skillars-5-2` W1) is unchanged in kind. State this explicitly in Dev Notes.
- **`onBookingCompleted` stays `@Async @TransactionalEventListener`** on the default `taskExecutor` — its *compute* (session load, drill batch-load, `SluFormula`) still runs there; only the two *persistence* calls move to `sluRetryExecutor`, and they return to the listener immediately (fire-and-forget). That is the "listener thread returns immediately" the decision calls for.
- **Tests (no new Spring context):**
  - Plain unit test `SluPersistenceDispatcherTest` (Mockito, no context — matches `SluPersistenceRetrierTest`): `new SluPersistenceDispatcher(mockSluRetrier, mockSnapshotRetrier)`, call `dispatchSluSave(stats)` / `dispatchSnapshotWrite(stats, y, w)`, `verify` each delegates to the matching retrier method with the same args. (The `@Async` hop is not exercised without a context — that is fine, it mirrors how `SnapshotPersistenceRetrierTest` pins the un-proxied pass-through.)
  - A reflection assertion in the same test: `SluPersistenceDispatcher.class.getMethod("dispatchSluSave", List.class).getAnnotation(Async.class).value()` equals `"sluRetryExecutor"` (and the same for `dispatchSnapshotWrite`) — this is the regression guard that the dispatch is bound to the isolated pool, not the default one.
  - Plain unit test for the bean config: `new DevelopmentConfig(mockEntityManager).sluRetryExecutor()` (or construct via the context if `DevelopmentConfig`'s `@PostConstruct` makes direct instantiation awkward — if so, assert via a `@SpringBootTest`-context test that already exists, e.g. pull the bean in `SluCalculationServiceIT` and assert `getCorePoolSize()==2`, `getMaxPoolSize()==4`, `getQueueCapacity()==50`, `getThreadNamePrefix().equals("slu-retry-")`). Prefer the plain instantiation if `DevelopmentConfig` tolerates it; otherwise reuse `SluCalculationServiceIT`'s context — **do not** create a new `@SpringJUnitConfig`.
  - Extend `SluCalculationServiceIT`: after publishing a `BookingCompletedEvent`, Awaitility-wait for the SLU rows **and** the snapshot row to appear — proving the end-to-end async dispatch through `sluRetryExecutor` still lands both writes. (The IT already uses Awaitility for the async listener; this just asserts both sinks.)
  - `mvn -o test -Dtest=SluPersistenceDispatcherTest,SluCalculationServiceIT,SluRetrierProxyRetryTest` green.

### AC4 — `development.coach_radar_preferences.coach_id` gets its FK to `marketplace.coach_profiles(id)`.

- **New Flyway migration `V118__coach_radar_preferences_coach_fk.sql`** (write this as `V118`, before AC1's `V119`). Mirror `V117`'s structure **exactly**:
  ```sql
  -- Story skillars-deferred-86 AC4: development.coach_radar_preferences (V51) was created with an FK
  -- on player_id (added by skillars-deferred-84 AC1 / V117) but NOT on coach_id. V117's own scope note
  -- explicitly left coach_id "for a future pass on its own merits" — this is that pass. ON DELETE
  -- CASCADE since a coach_radar_preferences row is pure per-coach chart-view preference state with no
  -- independent value once the coach profile is gone (same rationale V117 used for the player_id FK).

  -- Defensive: delete any rows already orphaned in a deployed environment before adding the FK
  -- (mirrors V117 / V113 / V109).
  DELETE FROM development.coach_radar_preferences crp
  WHERE NOT EXISTS (SELECT 1 FROM marketplace.coach_profiles c WHERE c.id = crp.coach_id);

  ALTER TABLE development.coach_radar_preferences
      ADD CONSTRAINT fk_crp_coach_id FOREIGN KEY (coach_id) REFERENCES marketplace.coach_profiles(id) ON DELETE CASCADE;

  -- coach_id IS the leading column of coach_radar_preferences' PK (coach_id, player_id), so it already
  -- has a usable index for the cascade / FK check — no separate CREATE INDEX needed (contrast V117,
  -- which had to add ix_crp_player_id because player_id is the trailing PK column).
  ```
  - **Verify before writing:** confirm `marketplace.coach_profiles.id` is `UUID PRIMARY KEY` (it is — `V26__marketplace_coach_profiles.sql:4`, `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`) and that `coach_radar_preferences.coach_id` is `UUID NOT NULL` (it is — `V51__radar_display_correlation.sql:14`, commented "marketplace.coach_profiles.id (UUID)"). Confirm no separate `CREATE INDEX` is needed because `coach_id` leads the `(coach_id, player_id)` PK — if a review of the live PK definition contradicts this, add `ix_crp_coach_id` and note it.
  - `V118` is **not** written for an online-safe deploy (`ADD CONSTRAINT … NOT VALID` + separate `VALIDATE CONSTRAINT`, `CREATE INDEX CONCURRENTLY`) — deliberately, matching `V117` / `V113` / the codebase-wide convention at this table's size. The `skillars-deferred-84` ledger bullet tracking that convention stays open, untouched, by design.
- **No entity change.** `coach_radar_preferences` has no JPA entity in `src/main` (it is written via a native `upsert` in `RadarDisplayService` — verify by grep; if an entity exists, the FK is transparent to it and no mapping change is needed). No repository change. No `@ManyToOne` — the codebase models these development-module cross-schema links at the DB level only (V113 / V117 added no entity associations either).
- **Test:** an IT that reuses the shared `AbstractIntegrationTest` context (**no new context**) — either a new small `CoachRadarPreferencesFkIT extends AbstractIntegrationTest` or a case folded into an existing development-module IT — that inserts a `coach_radar_preferences` row for a seeded coach, deletes the `marketplace.coach_profiles` row, and asserts the preference row is gone (CASCADE fired). If no existing IT seeds a coach profile conveniently, mirror `SluCalculationServiceIT`'s `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` fixture approach. `mvn -o test -Dtest=<thatIT>` green.

### AC5 — Ledger hygiene (`deferred-work.md`).

Done as part of this story's implementation (several closures depend on reading current source the dev agent will already have open):

- **`## Deferred from: code review of skillars-deferred-85 …` (2026-08-31)** — remove the first bullet ("SLU snapshot write path has no idempotency guard …") **only if AC1 + AC2 + AC3 all land**; it is closed by this story. Leave the other three bullets (single-part ETag/MD5 hard-fail, `head-object` no-retry, `first-time-setup.md` `chown -R` over-promise — all Deploy/Infra, out of scope here) untouched.
- **`## Deferred from: code review of skillars-deferred-84 …` (2026-08-31)** — remove the first bullet ("`@Backoff` retry sleeps run on the `@Async` booking-completed listener thread pool with no bulkhead") — closed by AC3. Leave the second bullet (V117 online-safe migration) open, untouched.
- **`## Deferred from: code review of skillars-deferred-40 …` (2026-08-20)** — remove the first bullet (V98's second query "widens the DEF3 race"), noting in the commit / this story's Dev Notes that it was **already closed by `skillars-deferred-77` AC10 Phase 1** (whole read-then-upsert sequence serialized under a `findByIdForUpdate` player-row lock — verified live at `RadarCompositeCalculationService.recalculateComposite`), never annotated. Leave the second bullet (`V98` unbatched full-table backfill — a scaling-shape concern, same class as `Def10`) open, untouched.
- **`## Deferred from: code review of skillars-5-2 …` (2026-06-19)** — the W1 bullet ("Partial snapshot missing if failure occurs between `sluRepository.saveAll` and `snapshotBatchWriter.writeAll`"): after AC1/AC3 the snapshot write has retry + `@Recover` + idempotency + an isolated executor. Close it, appending a one-line residual note that the two writes remain deliberately non-transactional-together (independent by design; a shared transaction is explicitly not wanted) so a hard crash strictly between them still leaves the snapshot un-written until the next session for that player triggers recomputation — acceptably eventually-consistent, matching the table's stated contract. Do not touch W2 (already `[AUDIT 2026-08-27: false premise …]`) or W3 (`[DISMISSED …]`).
- **`## Deferred from: code review of skillars-5-4 …` (2026-06-19)** — the W1 bullet already carries a long `[PARTIALLY CLOSED / PICKED UP …]` annotation ending "`coach_radar_preferences.coach_id` remains without an FK — … deliberately left out of AC1's scope". Append `[CLOSED by skillars-deferred-86 AC4: V118 adds the ON DELETE CASCADE FK to marketplace.coach_profiles(id), mirroring V117's pattern.]` to that annotation (do not delete the bullet — its player-baseline half is historical record).
- **Remove fully-closed bullets outright** per this file's first rule, or tag `[CLOSED by skillars-deferred-86 ACn]` where removing a bullet would leave a `## Deferred from:` section empty — match the two or three most recent closures in the file for which convention to use.
- **Add** this story's own new deferrals (if any surface during implementation) under a new `## Deferred from: … skillars-deferred-86 …` heading.
- **Do not touch:** `skillars-deferred-84`'s V117-online-safe bullet, `skillars-deferred-40`'s V98-backfill bullet, `skillars-deferred-85`'s three Deploy/Infra bullets, or any `[DISMISSED]` / `[DECIDED]` item — all explicitly out of scope.

## Tasks / Subtasks

- [ ] **Task 1: Coach-radar-preferences coach_id FK (AC: #4)** — do this first so `V118` precedes `V119`
  - [ ] Verify `marketplace.coach_profiles.id` type (`V26`) and `coach_radar_preferences.coach_id` type (`V51`); confirm `coach_id` leads the `(coach_id, player_id)` PK so no separate index is needed
  - [ ] Grep for a JPA entity / repository mapping `coach_radar_preferences`; confirm the FK needs no entity/mapping change (V113/V117 added none)
  - [ ] Write `V118__coach_radar_preferences_coach_fk.sql` mirroring `V117`'s exact defensive-delete-then-`ADD CONSTRAINT` + `ON DELETE CASCADE` shape; header comment cites this story + V117's scope note
  - [ ] IT (reuse `AbstractIntegrationTest` context): insert a preference row, delete the coach profile, assert CASCADE removed the preference row
  - [ ] `mvn -o test -Dtest=<CoachRadarPreferencesFkIT>` green
- [ ] **Task 2: Snapshot write-path idempotency marker (AC: #1)**
  - [ ] `V119__player_slu_weekly_snapshot_applied.sql` — new marker table, composite PK `(session_id, player_id, skill_code, iso_year, iso_week)`, `fk_pswsa_player_id` → `main.player_profiles(id) ON DELETE CASCADE` (with the V113/V117 defensive `DELETE … WHERE NOT EXISTS` preamble), `ix_pswsa_player_id`
  - [ ] `PlayerSluWeeklySnapshotApplied` entity + `PlayerSluWeeklySnapshotAppliedId` `@EmbeddedId` (mirror `PlayerSluWeeklySnapshot.PlayerSluSnapshotId` shape); `// IMMUTABLE: append-only` marker comment
  - [ ] `PlayerSluWeeklySnapshotAppliedRepository` with only `deleteAllByPlayerId` (mirror `SluWeeklySnapshotRepository.deleteAllByPlayerId`)
  - [ ] `SluWeeklySnapshotRepository.upsertAddIdempotent(...)` — the CTE native query (marker `INSERT … ON CONFLICT DO NOTHING RETURNING 1`, then snapshot `INSERT … SELECT … WHERE EXISTS (ins) … ON CONFLICT DO UPDATE SET total_slu = existing + EXCLUDED`); keep `upsertAdd`, grep for its other callers and record the result
  - [ ] `SnapshotBatchWriter.writeAll` — call `upsertAddIdempotent` per stat, pass `stat.getSessionId()`; `null`-sessionId stat → `log.warn` + skip (documented)
  - [ ] Wire `PlayerSluWeeklySnapshotAppliedRepository.deleteAllByPlayerId` into `GdprErasureService.deletePlayerDevelopmentData` next to `sluWeeklySnapshotRepository.deleteAllByPlayerId` (inject the new repo)
  - [ ] Update `SnapshotPersistenceRetrier` (+ `SluPersistenceRetrier`, symmetrically) class javadoc — retry-safety now rests on idempotency, not "rolled-back → clean slate"
  - [ ] Tests: `SluCalculationServiceIT` double-`writeAll`-same-session → one application + marker row present; two-different-sessions-same-bucket → additive sum preserved; `SnapshotBatchWriter` unit test → null-sessionId stat skipped, repo not called for it
  - [ ] `mvn -o test -Dtest=SluCalculationServiceIT,SnapshotPersistenceRetrierTest` green
- [ ] **Task 3: SLU saveAll check-then-act (AC: #2)**
  - [ ] `SluRepository.existsBySessionId(UUID)` (derived query; `session_id` is `V46`-indexed)
  - [ ] `SluPersistenceRetrier.saveSluWithRetry` — empty-list early return; if `sessionId != null && existsBySessionId(sessionId)` → `log.debug` + return; else `saveAll`. `@Retryable` / `@Recover` / `retryFor` unchanged
  - [ ] Dev Notes: entry-guard in `SluCalculationService` (duplicate-event) vs AC2 check (retry-within-invocation) address different scenarios; both kept
  - [ ] Tests: `SluPersistenceRetrierTest` — `existsBySessionId`→true → `never().saveAll`; →false → `saveAll(rows)`; empty list → neither. `SluCalculationServiceIT` — publish event, await rows, call `saveSluWithRetry(sameStats)` again → row count unchanged, no throw
  - [ ] `mvn -o test -Dtest=SluPersistenceRetrierTest,SluCalculationServiceIT` green
- [ ] **Task 4: Isolated retry-executor bulkhead (AC: #3)**
  - [ ] `DevelopmentConfig` — `@Bean("sluRetryExecutor")` `ThreadPoolTaskExecutor` (core 2 / max 4 / queue 50 / prefix `slu-retry-` / `CallerRunsPolicy` / `MdcDecorator`)
  - [ ] `SluPersistenceDispatcher` `@Component` — `@Async("sluRetryExecutor") dispatchSluSave(List)` → `sluPersistenceRetrier.saveSluWithRetry`; `@Async("sluRetryExecutor") dispatchSnapshotWrite(List, short, short)` → `snapshotPersistenceRetrier.writeAllWithRetry`; house-style javadoc (why a third bean; `@Async` and `@Retryable` never on one method; failure paths)
  - [ ] `SluCalculationService` — inject `SluPersistenceDispatcher` in place of the two retriers; swap the two synchronous calls for the dispatch calls; reword the two trailing `log.info`/`log.debug` lines to "dispatched", not "recorded/updated"
  - [ ] Dev Notes: the two dispatches now run concurrently on `sluRetryExecutor`; safe because `writeAll` has no read-dependency on `player_skill_stats`
  - [ ] Tests: `SluPersistenceDispatcherTest` (plain Mockito) — each dispatch delegates with same args; reflection assertion that both methods carry `@Async("sluRetryExecutor")`. Executor-config assertion (plain instantiation if `DevelopmentConfig` allows, else pull the bean in `SluCalculationServiceIT`). `SluCalculationServiceIT` — after event, Awaitility-await **both** the SLU rows and the snapshot row
  - [ ] `mvn -o test -Dtest=SluPersistenceDispatcherTest,SluCalculationServiceIT,SluRetrierProxyRetryTest` green
  - [ ] Confirm `missCount` unchanged (no new Spring context) — see Dev Notes context-count note
- [ ] **Task 5: Ledger hygiene (AC: #5)**
  - [ ] Close (remove or tag per the file's recent convention): `skillars-deferred-85` review bullet 1 (idempotency gap → AC1/AC2/AC3), `skillars-deferred-84` review bullet 1 (`@Async` bulkhead → AC3), `skillars-deferred-40` review bullet 1 (stale — closed by `skillars-deferred-77` AC10 Phase 1, verified live), `skillars-5-2` W1 (now retry + recover + idempotency + isolated executor; residual note about the deliberate non-shared-transaction). Append `[CLOSED by skillars-deferred-86 AC4]` to `skillars-5-4` W1's existing annotation
  - [ ] Leave open, untouched: `skillars-deferred-84` V117-online-safe bullet, `skillars-deferred-40` V98-backfill bullet, `skillars-deferred-85`'s three Deploy/Infra bullets
  - [ ] Add a `## Deferred from: … skillars-deferred-86 …` section only if implementation surfaces new deferrals

## Dev Notes

### Source ledger mapping

| AC | `deferred-work.md` source |
|----|---------------------------|
| AC1 | `## Deferred from: code review of skillars-deferred-85 …` (2026-08-31) — bullet 1, "SLU snapshot write path has no idempotency guard — a retried ambiguous-commit `TransactionSystemException` double-counts additive upserts". Project-owner routed to a dedicated story; granularity decided 2026-08-31 (per-`(session_id, player_id, skill_code, iso_year, iso_week)` write-once marker). |
| AC2 | Same bullet — the `SluPersistenceRetrier.saveAll` half: retry hits `DataIntegrityViolationException` → misleading `"N rows lost … manual recovery needed"` `@Recover` log. |
| AC3 | `## Deferred from: code review of skillars-deferred-84 …` (2026-08-31) — bullet 1, "`@Backoff` retry sleeps run on the `@Async` booking-completed listener thread pool with no bulkhead". Project-owner decision 2026-08-31: dedicated bounded `sluRetryExecutor` + dispatcher bean. |
| AC4 | `## Deferred from: code review of skillars-5-4 …` (2026-06-19) — W1's `[PICKED UP by skillars-deferred-84 AC1: … coach_radar_preferences.coach_id remains without an FK — … left out of AC1's scope]`. |
| AC5 | `skillars-deferred-40` review bullet 1 (stale, closed by `skillars-deferred-77` AC10 Phase 1); `skillars-5-2` W1 (retry+recover+idempotency now cover it). |

### Project-owner decisions folded in (2026-08-31)

- **AC1/AC2 granularity** = per-`(session_id, player_id, skill_code, iso_year, iso_week)` write-once marker for the snapshot; `session_id` check-then-act for the append-only `player_skill_stats`. Preserves additive weekly aggregation across distinct sessions; makes a retry / duplicate replay of one session's batch a no-op.
- **AC3 shape** = a small fixed `sluRetryExecutor` (core 2 / max 4 / queue ~50) + a `@Async("sluRetryExecutor")` **dispatcher bean** that calls the retriers cross-bean. `@Async` and `@Retryable` stay on **separate** beans — never stacked on one method.
- **Not re-opened for decision:** no further decision round is expected. If implementation surfaces a *new* decision point (e.g. the CTE `upsertAddIdempotent` behaves unexpectedly against Hibernate's native-query handling, or `DevelopmentConfig` cannot host the executor bean cleanly), stop and raise it rather than guessing.

### Architecture / conventions to follow

- **Modular-monolith DDD layering** (`[[project_skillars_devdocs]]`, `_bmad-output/project-context.md`): `platform.{module}.{api|service|repo|contract|config}`. The new `PlayerSluWeeklySnapshotApplied` entity + repository go in `platform.development.repo` (persistence models live in `repo`, per project-context rule). `SluPersistenceDispatcher` goes in `platform.development.service`. The `sluRetryExecutor` `@Bean` goes on `platform.development.config.DevelopmentConfig` (module Spring wiring stays in the module's `config` package — do **not** add it to `infrastructure.config.AsyncConfig`, which is business-agnostic).
- **Flyway** (`src/main/resources/db/migration`, `V{n}__snake_case.sql`): next free numbers are `V118` (AC4) then `V119` (AC1). Every schema change is a migration — no DDL in Java. Match `V117` / `V113`'s exact defensive-delete-then-`ADD CONSTRAINT` + `ON DELETE CASCADE` idiom for both new FKs.
- **`@Retryable` self-invocation**: `SluPersistenceRetrier` and `SnapshotPersistenceRetrier` are standalone `@Component`s *specifically* so `@Retryable` goes through the AOP proxy (their class javadocs cite the same pitfall as `BookingService.acceptAndInitiatePayment` / `TimelineEventListener`'s `@Lazy @Autowired self`). AC3's dispatcher is a **third** bean for the same reason — `SluCalculationService` calling `this.dispatch…()` would bypass the `@Async` proxy, and the dispatcher calling `this` (if it were merged into a retrier) would bypass `@Retryable`. Three beans, three single-responsibility proxies.
- **`@EnableRetry`** is active application-wide (established `skillars-deferred-77` AC8 — "spring-retry was already a `pom.xml` dependency with `@EnableRetry` already active"). `@EnableAsync` is active application-wide (`notification.config.AsyncConfig`). Add neither.
- **Executors in this codebase** all use `ThreadPoolTaskExecutor` + `MdcDecorator` + `CallerRunsPolicy` (`infrastructure.config.AsyncConfig`'s `taskExecutor`, `notification.config.AsyncConfig`'s `moderationTaskExecutor` / `sendMailPool`). `sluRetryExecutor` matches that shape. `CallerRunsPolicy` here means "on saturation, run on the listener thread" = the exact pre-story behaviour = safe degradation, not a regression.
- **Idempotent upsert via CTE**: PostgreSQL evaluates the `WITH ins AS (INSERT … ON CONFLICT DO NOTHING RETURNING 1)` and the main `INSERT … SELECT … WHERE EXISTS (SELECT 1 FROM ins)` in one statement, one snapshot — the marker insert and the conditional delta are atomic within `writeAll`'s `@Transactional`. This is the same "gate a write on a marker row" pattern the payment module uses for webhook idempotency (`stripe_webhook_events`); it is not a new concept in this codebase.
- **GDPR erasure** (`platform.admin.service.GdprErasureService.deletePlayerDevelopmentData`) deletes from every `development.*` player-scoped table **explicitly** via `deleteAllByPlayerId` (the `ON DELETE CASCADE` FKs from `V113` / `V117` / this story's `V119` are defense-in-depth, not the primary path). The new marker table must get the same explicit `deleteAllByPlayerId` call, in the same method, next to `sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId)`.
- **Context-count gate** (`.github/scripts/assert-context-count.sh`, ceiling `37`, invoked `assert-context-count.sh build.log 37` in `pr-build.yml`): `skillars-deferred-85` AC2 added `SluRetrierProxyRetryTest`'s one context and the ceiling was **kept at 37** (the speculative 37→38 bump was reverted in review; CI passed at 37, so `missCount` is ≤ 37 with headroom unknown). **This story must add ZERO new Spring contexts** — every new test either extends `AbstractIntegrationTest` (shared context) or is a plain Mockito unit test (no context). Do **not** add a `@SpringBootTest` / `@SpringJUnitConfig` / `@DataJpaTest` / `@MockitoBean` test. If a genuinely unavoidable new context appears, stop and raise it — do not bump the ceiling unilaterally.
- **Testing** (per `docs/validation-strategy.md` / `[[feedback_no_local_mvn_verify]]`): do **not** run `mvn verify` locally — GitHub CI is the sole full-verification gate. Run targeted `mvn -o test -Dtest=<Class>` for touched Java. Use **Instancio** for DTO/entity test data, **AssertJ** `assertThat`, **Awaitility** for async, **Testcontainers** (no DB mocking) for ITs — all per project-context rules.

### Files being modified — current state

- **`SnapshotBatchWriter.java`** (`platform/development/repo/`) — `@Component @Transactional`; `writeAll(List<PlayerSkillStat> stats, short isoYear, short isoWeek)` loops `snapshotRepository.upsertAdd(stat.getPlayerId(), stat.getSkillCode(), isoYear, isoWeek, stat.getSluValue())`. **AC1** changes the per-iteration call to `upsertAddIdempotent(stat.getSessionId(), …)` and adds a null-`sessionId` skip. Loop shape, `@Transactional`, `@Component` location all preserved.
- **`SluWeeklySnapshotRepository.java`** (`platform/development/repo/`) — `JpaRepository<PlayerSluWeeklySnapshot, …>`; owns `upsertAdd` (native, `ON CONFLICT … DO UPDATE SET total_slu = existing + EXCLUDED`), plus read queries and `deleteAllByPlayerId`. **AC1** adds `upsertAddIdempotent` (native CTE); keeps `upsertAdd` (grep its callers — expected: only `SnapshotBatchWriter`; if so, note that `upsertAdd` becomes dead after AC1 and either remove it or leave it with a `// superseded by upsertAddIdempotent` note per the reviewer's call).
- **`PlayerSluWeeklySnapshot.java`** (`platform/development/repo/`) — `@Entity`, `@EmbeddedId PlayerSluSnapshotId` (`playerId` Long, `skillCode` String, `isoYear` Short, `isoWeek` Short). **Not modified** — the reference shape for the new `PlayerSluWeeklySnapshotAppliedId`.
- **`PlayerSkillStat.java`** (`platform/development/repo/`) — `@Entity`, `@Id @GeneratedValue(GenerationType.UUID) UUID id`, `session_id UUID` column (`updatable = false`, nullable in DDL but always set on the `onBookingCompleted` path), `// IMMUTABLE: append-only`. **Not modified** — AC2 reads `getSessionId()` only.
- **`SluRepository.java`** (`platform/development/repo/`) — `JpaRepository<PlayerSkillStat, UUID>`; has `findBySessionId(UUID)`, aggregate native queries, `deleteAllByPlayerId`. **AC2** adds `existsBySessionId(UUID)`.
- **`SluPersistenceRetrier.java`** (`platform/development/service/`) — `@Component`; `@Retryable(retryFor = {DataAccessException, TransactionSystemException, CannotCreateTransactionException}, maxAttemptsExpression = "${app.slu.retry.max-attempts:3}", backoff = @Backoff(delayExpression="${app.slu.retry.backoff-initial-ms:100}", multiplierExpression="${app.slu.retry.backoff-multiplier:2.0}"))` on `saveSluWithRetry(List<PlayerSkillStat>)` → `sluRepository.saveAll(rows)`; two `@Recover` overloads (`DataAccessException`, `TransactionException`) logging `"… rows lost for session {} … manual recovery needed"`. **AC2** makes `saveSluWithRetry` a check-then-act (`existsBySessionId` guard before `saveAll`). **AC1** updates the class javadoc (retry-safety rationale). `@Retryable` / `@Recover` / `retryFor` / property namespaces unchanged.
- **`SnapshotPersistenceRetrier.java`** (`platform/development/service/`) — same shape, `app.slu.snapshot-retry.*` namespace, `writeAllWithRetry(List<PlayerSkillStat>, short, short)` → `snapshotBatchWriter.writeAll(...)`; two `@Recover` overloads. **AC1** updates the class javadoc only (retry-safety now rests on `writeAll`'s per-marker idempotency). Method body, annotations unchanged.
- **`SluCalculationService.java`** (`platform/development/service/`) — `@Async @TransactionalEventListener(AFTER_COMMIT) onBookingCompleted(BookingCompletedEvent)`. Has an entry idempotency guard (`findBySessionId(...).isEmpty()`), resolves config scales per-invocation, batch-loads drills, runs `SluFormula`, builds `List<PlayerSkillStat>`, then calls `sluPersistenceRetrier.saveSluWithRetry(stats)` and `snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek)` synchronously. **AC3** injects `SluPersistenceDispatcher` in place of the two retriers, swaps the two calls for `dispatchSluSave` / `dispatchSnapshotWrite`, rewords the two trailing log lines to "dispatched". The entry guard and everything above the two persistence calls stay exactly as-is.
- **`DevelopmentConfig.java`** (`platform/development/config/`) — `@Configuration` with a constructor-injected `EntityManager` and a `@PostConstruct validateSluRepositorySchema()`. **AC3** adds one `@Bean("sluRetryExecutor")` method. If the `@PostConstruct` makes plain `new DevelopmentConfig(em)` awkward for a unit test, the executor-config assertion pulls the bean from `SluCalculationServiceIT`'s context instead (no new context).
- **`GdprErasureService.java`** (`platform/admin/service/`) — `deletePlayerDevelopmentData(Long playerId)` calls `deleteAllByPlayerId` on ~9 development repositories in sequence. **AC1** injects `PlayerSluWeeklySnapshotAppliedRepository` and adds one `deleteAllByPlayerId(playerId)` call next to `sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId)`.
- **`RadarCompositeCalculationService.java`** (`platform/development/service/`) — `recalculateComposite` already serializes on `lockRetryer.withBoundedRetry(findByIdForUpdate)` + `entityManager.refresh(PESSIMISTIC_WRITE)` for the whole read-then-upsert. **Not modified** — read only, to confirm `skillars-deferred-40`'s "widens DEF3 race" bullet is stale for AC5.
- **`V117__coach_radar_preferences_player_fk.sql`**, **`V113__radar_composite_baseline_player_fk.sql`** — the reference shape for AC4's `V118` and AC1's `V119` FK blocks. **Not modified.**
- **`infrastructure/config/AsyncConfig.java`** — `taskExecutor` (core 4 / max 16 / queue 100, `CallerRunsPolicy`, `MdcDecorator`), custom `AsyncUncaughtExceptionHandler`. **Not modified** — the `sluRetryExecutor` is a sibling, not a change to this one; the uncaught-exception handler still catches a fire-and-forget dispatch failure.

### Files being created

- `src/main/resources/db/migration/V118__coach_radar_preferences_coach_fk.sql`
- `src/main/resources/db/migration/V119__player_slu_weekly_snapshot_applied.sql`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSluWeeklySnapshotApplied.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSluWeeklySnapshotAppliedRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcher.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcherTest.java`
- Optionally `src/test/java/com/softropic/skillars/platform/development/…/CoachRadarPreferencesFkIT.java` (AC4) — or fold into an existing development-module IT that already seeds a coach profile

### Project Structure Notes

Touches: **2 Flyway migrations** (`V118`, `V119`), **2 new Java main classes** (`PlayerSluWeeklySnapshotApplied` entity, its repository), **1 new Java main class** (`SluPersistenceDispatcher`), **1 new `@Bean`** on `DevelopmentConfig`, **4 modified Java main classes** (`SnapshotBatchWriter`, `SluWeeklySnapshotRepository`, `SluRepository`, `SluPersistenceRetrier`, `SnapshotPersistenceRetrier`, `SluCalculationService`, `GdprErasureService` — count is 7, all small, mostly one-method), **1 new test class** (`SluPersistenceDispatcherTest`), **3 modified test classes** (`SluCalculationServiceIT`, `SluPersistenceRetrierTest`, `SnapshotPersistenceRetrierTest`), optionally **1 new IT** (`CoachRadarPreferencesFkIT`), and `deferred-work.md`. **No** frontend change, **no** i18n change, **no** API contract / REST change, **no** new `platform_config` seed, **no** new fixture-id ranges, **no** new Spring test context. Two `@Async`/`@Retryable`/executor concerns kept on three separate single-responsibility beans per this codebase's established proxy-safety convention.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of skillars-deferred-85 …` (AC1/AC2), `## Deferred from: code review of skillars-deferred-84 …` (AC3), `## Deferred from: code review of skillars-5-4 …` W1 (AC4), `## Deferred from: code review of skillars-deferred-40 …` + `## Deferred from: code review of skillars-5-2 …` W1 (AC5).
- `_bmad-output/implementation-artifacts/skillars-deferred-85-slu-retrier-transaction-failure-recovery-and-backup-restore-alerting-and-ops-doc-hardening.md` — AC1 + its Review Findings "Decision Needed" ambiguous-commit double-count item, project-owner-routed here.
- `_bmad-output/implementation-artifacts/skillars-deferred-84-…-skill-toggle-debounce.md` — AC1 (V117, the `player_id` FK; scope note leaving `coach_id`), AC2 (`SnapshotPersistenceRetrier`), its code-review `@Async`-bulkhead deferral.
- `src/main/java/com/softropic/skillars/platform/development/repo/{SnapshotBatchWriter,SluWeeklySnapshotRepository,PlayerSluWeeklySnapshot,PlayerSkillStat,SluRepository}.java`.
- `src/main/java/com/softropic/skillars/platform/development/service/{SluCalculationService,SluPersistenceRetrier,SnapshotPersistenceRetrier,RadarCompositeCalculationService}.java`.
- `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`; `src/main/java/com/softropic/skillars/infrastructure/config/AsyncConfig.java`; `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java` (`@EnableAsync` owner).
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` — `deletePlayerDevelopmentData`.
- `src/main/resources/db/migration/{V117__coach_radar_preferences_player_fk,V113__radar_composite_baseline_player_fk,V51__radar_display_correlation,V48__development_exposure_dashboard,V46__development_module_init,V26__marketplace_coach_profiles}.sql`.
- `src/test/java/com/softropic/skillars/platform/development/service/{SluCalculationServiceIT,SluPersistenceRetrierTest,SnapshotPersistenceRetrierTest,SluRetrierProxyRetryTest}.java`.
- `.github/scripts/assert-context-count.sh`, `.github/workflows/pr-build.yml` — the context-count gate (do not touch; add no new context).
- `[[project_skillars_release_workflow]]`, `[[feedback_no_local_mvn_verify]]`, `_bmad-output/project-context.md`.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (bmad-create-story workflow)

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-08-31: Story created from `deferred-work.md` mining (SLU/Radar priority slot). SLU/Radar confirmed down to the `skillars-deferred-84`/`-85` resilience thread's tail: the project-owner-routed snapshot idempotency gap (AC1/AC2), the `@Async` retry-bulkhead gap (AC3), the `coach_radar_preferences.coach_id` FK that V117 deferred (AC4), and two stale/now-covered ledger bullets (AC5). Two project-owner decisions taken during the release-workflow discussion (2026-08-31) are folded straight into the ACs: (1) idempotency granularity = per-`(session_id, player_id, skill_code, iso_year, iso_week)` write-once marker for the snapshot + `session_id` check-then-act for `player_skill_stats`; (2) a dedicated bounded `sluRetryExecutor` (core 2 / max 4 / queue 50) + a `@Async("sluRetryExecutor")` dispatcher bean, with `@Async` and `@Retryable` kept on separate beans. Verified live during creation: `RadarCompositeCalculationService.recalculateComposite` already serializes under a `findByIdForUpdate` player-row lock (`skillars-deferred-77` AC10 Phase 1), making `skillars-deferred-40`'s "V98 widens DEF3 race" bullet stale; `marketplace.coach_profiles.id` is `UUID PK` and `coach_radar_preferences.coach_id` is `UUID NOT NULL` leading its `(coach_id, player_id)` PK (so V118 needs no separate index); next free migration numbers are V118 then V119. No new Spring test context to be added — context-count ceiling stays at 37. Status: ready-for-dev.
