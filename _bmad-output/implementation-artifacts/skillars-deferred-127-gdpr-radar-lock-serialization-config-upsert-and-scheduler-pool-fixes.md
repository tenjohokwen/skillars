# Story: GDPR/Radar Lock Serialization, Unseeded Config-Key Upsert & Scheduler Pool Fixes

**Story Key:** `skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes`
**Epic:** Deferred Work
**Priority:** High (a real GDPR-erasure/radar-recalculation race that can resurrect an erased player's
development data and a genuine lock-ordering deadlock hazard between the same two paths; a real
project-wide config-key write-path gap affecting all 18 `HAS_CODE_DEFAULT` keys; a real single-thread
scheduler-starvation gap affecting all 44 `@Scheduled` methods) — plus the standard ledger closeout.
**Status:** ready-for-dev
**Created:** 2026-09-21

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md`
(`## Deferred from: code review of skillars-deferred-126-...` — surfaced by `/bmad-code-review`
across four parallel layers when skillars-deferred-126 was reviewed, same day this story was created),
per this project's established "read same-day code-review deferrals before drawing scope" convention.

That section lists eight bullets. The eighth (`next_retry_at` eligibility staying on the app clock) is
already `[DECIDED: accepted risk — skillars-deferred-126]` from a prior owner decision and is **not**
touched here. Of the remaining seven, this story closes four (bundled below as AC1–AC4) and leaves
three open (see "Explicitly left open" at the end of this section) — a project-owner decision
(`AskUserQuestion`, taken live during this story's creation) on each of the four closed items, plus a
fourth decision confirming the story stays scoped to this same-day deferral rather than reaching
further into the ledger.

All four bullets were independently re-verified against `master@24fe4a80` (skillars-deferred-126's own
merge, PR #214) at story-creation time — line citations below reflect that HEAD, not the original
deferral note's (now-superseded) line numbers, which drifted slightly as skillars-deferred-126's own
code-review fixes landed. One correction made during this verification, not assumed from the original
deferral note: the config-key gap affects **18** `HAS_CODE_DEFAULT` keys, not the 3 the original bullet
named as examples — confirmed by reading `ConfigBounds.HAS_CODE_DEFAULT`'s full `Set.of(...)` in full.

### Explicitly left open (not in this story's scope — owner decision, AskUserQuestion)

- **`now()` vs `clock_timestamp()` fragility** in `claimPendingBatch`/`resetStaleClaimed` (both
  repositories) — correct today only because neither call runs inside an outer `@Transactional`.
  Latent, not a live bug; left as a documented ledger risk rather than a code change in this story,
  since AC1–AC4 below already give this story a full 4-AC + AC5-closeout bundle matching this
  project's established sizing convention.
- **`ShedLockConfig`'s hostname-truncation `String.substring` can split a surrogate pair** — this one
  bullet from the deferral **is** folded into AC4 below alongside the truncation-length citation
  refresh (it lives in the exact file/method AC4 already touches and is a two-line, no-decision fix;
  bundling it there avoids leaving an easy, already-diagnosed correctness fix on the table while a
  four-line method sits open in the diff anyway).
- **skillars-deferred-126 AC1 Task 3's `EXPLAIN`/index-coverage confirmation was never performed or
  recorded** — folded into AC5's ledger-closeout tasks below (a verification/recording task, not a
  code change) rather than given its own AC.
- Reaching further into `deferred-work.md` for an unrelated item beyond this same-day deferral —
  considered and explicitly declined (project-owner decision, `AskUserQuestion`, live during story
  creation): this story's 4 ACs from the freshest deferral already match every prior story's bundle
  size in this series.

---

## AC1 — Serialize GDPR erasure against `recalculateComposite` via the shared `player_profiles` lock

### Context

`RadarCompositeCalculationService.recalculateComposite`
(`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:167-278`)
takes a pessimistic lock on the player's `player_profiles` row before reading radar aggregates and
upserting composites/baselines — see its own extensive Javadoc at `:97-160`, which **already documents
this exact gap** as a "concrete conflict source" (added by skillars-deferred-126's own code review,
2026-09-21):

> `GdprErasureService.erase` (its own `Propagation.REQUIRES_NEW` transaction) calls
> `deletePlayerDevelopmentData`, which deletes `player_radar_baselines` then `player_radar_composites`
> for the same player — **without** taking this method's own `player_profiles` pessimistic lock at
> all, and in the **opposite** table order this method writes them in (composites then baselines).

Two distinct failure modes follow from this:

1. **Resurrection (data-integrity bug).** Interleaving: instance A (`recalculateComposite`) locks
   `player_profiles(P)` and reads aggregates under READ COMMITTED → instance B (`GdprErasureService`)
   deletes baselines, composites and assessments for P and commits → A's per-skill loop re-inserts
   composites and baselines from its pre-erasure snapshot. An Article-17-erased player has live radar
   rows again and nothing will ever remove them (the erasure request is already `COMPLETED`).
2. **Lock-ordering deadlock (Postgres `40P01`).** The two paths write/delete `player_radar_composites`
   and `player_radar_baselines` in opposite order with no shared upstream lock — a classic
   lock-ordering deadlock shape. `recalculateComposite`'s own `lock_timeout`
   (skillars-deferred-126 AC2) does **not** address this: `lock_timeout` only makes an ordinary
   *waiter* abort itself, it cannot break a circular wait — that is Postgres's own
   `deadlock_timeout`-driven detector's job, already firing independent of anything either story adds.
   Losing that deadlock as the victim discards `GdprErasureService.erase`'s `main.user` anonymisation,
   message/review deletions and blob-deletion outbox rows in the SAME `REQUIRES_NEW` transaction, and
   routes the request to `markFailed` — a GDPR erasure fails because a background radar recalculation
   happened to be running.

**Owner decision (taken live, `AskUserQuestion`, during this story's creation): close both with a
single mechanism — have `GdprErasureService`'s player-development-data deletion take the SAME
`player_profiles` pessimistic lock `recalculateComposite` already uses, via the SAME
`PlayerProfileRepository.findByIdForUpdate` + `PessimisticLockRetryer.withBoundedRetry` pattern.** This
fully serializes the two paths — once one holds the `player_profiles` lock, the other cannot even begin
touching `player_radar_composites`/`player_radar_baselines`, so they can never race on those two
tables' row locks at all, closing the deadlock hazard as a structural consequence, not just the
resurrection bug directly named.

**Why this is lower-risk than it may first look — re-verify this claim before implementing, don't
assume it:** `PlayerProfileRepository.findByIdForUpdate`
(`src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java:44-47`) is
**NOWAIT** (`@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`), not an
indefinite wait — every call site (this story's new one included) wraps it in
`PessimisticLockRetryer.withBoundedRetry(...)`, which retries a `PessimisticLockingFailureException`
with jittered backoff (default 8 attempts, ~3.2s worst-case total, holding the caller's pooled JDBC
connection for that window — see the class's own "Cost model" Javadoc,
`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:44-56`)
before giving up and letting `PessimisticLockingFailureException` propagate. A genuinely busy
`player_profiles` row therefore makes `GdprErasureService.erase` fail fast within ~3.2s (routed to
`markFailed` by its existing caller, retriable), not hang.

### Tasks

1. Re-verify this AC's line citations against current HEAD before implementing — this story's own
   citations above were checked against `master@24fe4a80`; confirm nothing has drifted since.
2. In `GdprErasureService`
   (`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`), add two
   new `private final` fields — `PessimisticLockRetryer lockRetryer` and `EntityManager entityManager`
   (constructor injection via the class's existing `@RequiredArgsConstructor`, mirroring
   `RadarCompositeCalculationService`'s own identical field pair at `:46-47` — `EntityManager` needs no
   `@PersistenceContext` annotation for constructor injection, confirmed by that exact precedent).
3. At the top of `deletePlayerDevelopmentData(Long playerId, List<String> blobKeysToDelete)`
   (`:194-209`), before any of its existing `deleteAllByPlayerId`/`deleteByPlayerId` calls, add:
   ```java
   var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
       .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
   entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);
   ```
   — the exact pattern `recalculateComposite` uses at `:191-193`, including the follow-up
   `entityManager.refresh(..., PESSIMISTIC_WRITE)` call; do not drop that second call without first
   understanding why `recalculateComposite` needs it (re-read its surrounding code/comments — if no
   in-repo rationale is found, keep it for consistency with the established pattern rather than
   guessing it is redundant).
4. `deletePlayerDevelopmentData` is called both directly (PLAYER role) and in a loop over
   `playerProfileRepository.findByParentId(userId)` (PARENT role) — confirm the new lock acquisition
   happens once per player inside the method itself (not hoisted above the loop in `erase()`), so each
   player in a multi-child family is locked/unlocked independently, matching
   `recalculateComposite`'s own per-player granularity.
5. Import `com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer`,
   `jakarta.persistence.EntityManager`, `jakarta.persistence.LockModeType`, and
   `com.softropic.skillars.infrastructure.exception.ResourceNotFoundException` (or this project's
   equivalent — confirm the actual exception class `recalculateComposite` uses is accessible from this
   package; use the identical one, do not invent a new not-found exception type for this call site).
6. **Update `RadarCompositeCalculationService.recalculateComposite`'s own Javadoc** (`:102-160`) — it
   currently documents this exact gap as a live, unfixed "concrete conflict source" in present tense
   ("**without** taking this method's own `player_profiles` pessimistic lock at all"). Once this AC
   ships, that sentence is false. Correct it to describe the NOW-shared lock and cross-reference
   `GdprErasureService.deletePlayerDevelopmentData`'s own new lock acquisition, preserving the rest of
   the paragraph's still-accurate content (the lock-ordering discussion, the `lock_timeout` vs
   `deadlock_timeout` distinction, the exception-class findings) — do not delete the paragraph
   wholesale, it documents real, still-relevant mechanism detail for both methods.
7. **Do not also reorder `deletePlayerDevelopmentData`'s deletes** (composite-then-baseline to match
   `recalculateComposite`'s write order) as a belbelt-and-suspenders addition — the project-owner
   decision taken live was the shared lock ALONE; once serialized upstream, the two paths can never
   race on `player_radar_composites`/`player_radar_baselines` locks concurrently at all, making a
   matching write order redundant defense-in-depth the owner explicitly did not ask for. Keep the
   diff to what was decided.
8. Note as a residual, not a new task: `radar_composite_dlq` rows for an erased player are not cleared
   by `deletePlayerDevelopmentData` and will still process after this fix ships. This is **not** a
   data-integrity issue post-fix — `deletePlayerDevelopmentData` also deletes the player's
   `radar_assessments` (`:203`), so a stale DLQ row's `recalculateComposite` re-run finds no aggregates
   to derive a composite from (`bySkill` is empty, the per-skill loop is a no-op) — but it is wasted
   work. Record this as a low-value cleanup gap in AC5's ledger closeout rather than fixing it here
   (out of this AC's scope as decided).

### Tests

- New `GdprErasureIT` test(s) (`src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`,
  alongside the existing `erase_playerUser_...` tests) proving the two failure modes this AC closes are
  genuinely closed, not just that the new lock call compiles:
  - A concurrency test mirroring `RadarCompositeCalculationServiceConcurrencyIT`'s own pattern
    (Testcontainers Postgres, real threads, full latch control — not mocks): start `erase()` for a
    player with a `player_profiles` row lock already held by a concurrent `recalculateComposite`-style
    caller; assert `erase()` either waits and then succeeds once the lock releases, or fails fast
    within the ~3.2s `PessimisticLockRetryer` budget with `PessimisticLockingFailureException` — pick
    whichever is actually observed (do not assume; this exact empirical-confirmation discipline is
    what skillars-deferred-126 AC2's own tests already established as this project's convention for
    concurrency claims).
  - A regression test proving the **resurrection** scenario is closed: seed radar aggregates for a
    player, hold the `player_profiles` lock from a `recalculateComposite`-shaped caller, trigger
    `erase()` concurrently, release the lock, and assert the player's `player_radar_composites`/
    `player_radar_baselines` rows are genuinely gone after both complete (not resurrected) — this is
    the test that would have failed against the PRE-fix code and must be written to actually reach
    that pre-fix failure if run against `master@24fe4a80` (verify this by mentally tracing it against
    the current code, not just asserting the post-fix behavior).
- Existing `GdprErasureIT.erase_playerUser_...` tests must stay green — the new lock acquisition
  should not change single-threaded (no contention) erasure behavior or timing in any observable way.

---

## AC2 — `ConfigService.updateConfig`: upsert for unseeded `HAS_CODE_DEFAULT` keys

### Context

`ConfigService.updateConfig`
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:202-209`) does:

```java
public ConfigValueResponse updateConfig(String key, String newValue) {
    PlatformConfig entity = configRepository.findByKey(key)
            .orElseThrow(() -> new ResourceNotFoundException("ConfigEntry", key));
    ...
}
```

Any key with no `platform_config` row 404s through `PUT /api/config/values/{key}`
(`src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java:75-80`, the only write
path — there is no create/POST endpoint). `ConfigBounds.HAS_CODE_DEFAULT`
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:300-317`) is, by its
own class Javadoc, deliberately the set of keys with **no Flyway seed migration** — every one of its
**18** keys (re-verified by reading the full `Set.of(...)` at HEAD — not just the 3 the original
deferral bullet named as examples) is therefore unwritable through this endpoint until an operator
hand-inserts a `platform_config` row. This directly contradicts each such key's own Javadoc, which
documents "an operator can widen this at runtime" as its `max`-bound rationale (see e.g.
`RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s own Javadoc, `:245-249` — a skillars-deferred-126 addition
that inherits this project-wide gap on day one).

**Owner decision (taken live, `AskUserQuestion`): upsert on write, scoped to
`ConfigBounds.HAS_CODE_DEFAULT` keys only** — `failFast` (non-`HAS_CODE_DEFAULT`) bounded keys must
already be seeded or `ConfigStartupAssertion` would have refused to boot
(`src/main/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertion.java:88` checks
`ConfigBounds.HAS_CODE_DEFAULT.contains(bk.key())` for exactly this reason), so widening the upsert
condition to those keys would never actually change observed behavior for them — but the decision was
to keep the code's condition itself narrow and explicit (matching the exact set of keys this gap
affects) rather than accepting silent-but-harmless overreach.

### Tasks

1. Re-verify `ConfigService.updateConfig`'s current line range and `ConfigBounds.HAS_CODE_DEFAULT`'s
   current key list against HEAD before implementing.
2. Change `updateConfig` so that when `configRepository.findByKey(key)` returns empty **and**
   `ConfigBounds.HAS_CODE_DEFAULT.contains(key)`, it creates a new `PlatformConfig` row instead of
   throwing `ResourceNotFoundException` — `value` from the request (still passed through
   `rejectOutOfRange` first, unchanged), `valueType = ConfigValueType.LONG` (every `HAS_CODE_DEFAULT`
   key is a `BoundedKey` — confirm this holds for all 18 by re-checking `ConfigBounds.ALL`'s
   construction, not assumed), `description` — decide during implementation whether to leave `null` or
   populate from the matching `BoundedKey`'s own Javadoc/rationale field if one is programmatically
   accessible (check `BoundedKey`'s record/class fields before deciding; do not invent a field that
   does not exist), `updatedAt = Instant.now()`. When the key is absent and NOT in
   `HAS_CODE_DEFAULT` (a `failFast` key, or simply not a recognized key at all), preserve the current
   404 behavior exactly — do not widen this to arbitrary unknown keys.
3. `PlatformConfigRepository`
   (`src/main/java/com/softropic/skillars/platform/config/repo/PlatformConfigRepository.java`) already
   has `save` via `JpaRepository` — no new repository method needed; confirm `key` is declared
   `unique = true` (it is, `PlatformConfig.java:27`) so a racing double-create attempt fails at the DB
   constraint rather than silently duplicating — decide whether that race (two concurrent first-writes
   for the same never-before-seeded key) needs handling in this AC or is an acceptable, extremely
   rare edge left unhandled (this project's own convention — see how `rejectOutOfRange`'s sibling paths
   handle similarly rare races — should guide this; do not add new locking machinery unless the
   existing convention already does so for comparable cases).
4. `invalidate()` (cache invalidation, already called at the end of the existing method) must still run
   on the new create-path too, not just the existing update-path — trace the method's control flow to
   confirm this rather than assuming.

### Tests

- `ConfigServiceTest` (`src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java`,
  alongside the existing `updateConfig_*` tests at `:298-336`): a new
  `updateConfig_unseededHasCodeDefaultKey_createsRow` test asserting a `HAS_CODE_DEFAULT` key with no
  existing row succeeds (not 404) and the value is readable afterward; a new
  `updateConfig_unseededNonHasCodeDefaultKey_stays404` test asserting the existing 404 behavior is
  unchanged for a key that is neither present nor in `HAS_CODE_DEFAULT`, so this AC does not silently
  widen the write surface beyond its stated scope.
- Confirm `ConfigBoundsEnumCoverageTest`
  (`src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`)
  stays green unmodified — it asserts structural properties of `ConfigBounds` itself, not
  `ConfigService`, and this AC does not touch `ConfigBounds`.

---

## AC3 — Enable a real thread pool for `@Scheduled` tasks (single-thread starvation)

### Context

`SchedulingConfig`
(`src/main/java/com/softropic/skillars/infrastructure/config/SchedulingConfig.java`) has
`@EnableScheduling` (conditional on `app.scheduling.enabled`, default `true`) and nothing else — no
`TaskScheduler` bean, no `spring.task.scheduling.pool.size` property anywhere in
`src/main/resources/application*.yaml` (re-verified: grepped for `task:`/`scheduling:` under `spring:`
at HEAD, found nothing). Spring Boot's `TaskSchedulingAutoConfiguration` therefore backs
`@EnableScheduling` with a single-thread `ThreadPoolTaskScheduler` by default. This project has **44**
`@Scheduled`-annotated methods (re-counted at HEAD via `grep -rn "@Scheduled" src/main/java | wc -l`,
across every `platform.*` module) sharing that one thread. A single long-running job —
`VideoDeletionOutboxProcessor.process()`'s documented 12-minute `MAX_RUN_DURATION` — occupies it
entirely, during which none of the other 43 fire, including the two ShedLock-timing-sensitive
processors (`VideoDeletionOutboxProcessor` itself and `RadarCompositeDlqProcessor`) whose
`lockAtMostFor`/`STALE_CLAIM_WINDOW` arithmetic (skillars-deferred-126 AC1) implicitly assumes their
own scheduled cadence is not starved by an unrelated job holding the only thread.

**Owner decision (taken live, `AskUserQuestion`): fix it — `spring.task.scheduling.pool.size: 8`**, a
plain static `application.yaml` property (not a new `ConfigBounds`/`platform_config` key — a thread
pool's size cannot be resized at runtime without recreating the scheduler bean, so a
runtime-tunable config value would not actually be actionable without a restart anyway, making the
extra machinery pointless here).

### Tasks

1. Re-verify no `spring.task.scheduling.*` property already exists anywhere in
   `src/main/resources/application*.yaml` or `src/test/resources/application*.yaml` before adding one
   (confirm this story's own citation above still holds at implementation time).
2. Add a `spring.task:` block to `src/main/resources/application.yaml` (new top-level child under the
   existing `spring:` key at `:46`, alongside `lifecycle`/`mvc`/`jackson`) —
   ```yaml
   task:
     scheduling:
       pool:
         size: 8
   ```
3. Confirm `src/test/resources/application-test.yaml`'s `app.scheduling.enabled: false`
   (`:126`, per `SchedulingConfig`'s own class Javadoc) is unaffected by this change — when scheduling
   is disabled entirely, no `TaskScheduler` is even engaged, so the new pool-size property is inert in
   that profile; this is a read-and-confirm task, not a code change to the test profile.
4. Do **not** attempt to make the pool size itself configurable via `ConfigBounds`/`platform_config`
   (see the owner decision's own rationale above) — keep this a one-line static property change.
5. Grep-sweep `src/main/java` for any existing assumption of single-threaded scheduled execution (e.g.
   a comment or a field relying on scheduled methods never running concurrently with each other) that
   this change could newly violate — `SchedulingConfig`'s own class Javadoc is the most likely place to
   start; if none is found, note that explicitly in the Dev Agent Record rather than silently skipping
   the check.

### Tests

- No new automated test is expected to meaningfully prove "the scheduler now has 8 threads" in this
  project's existing IT infrastructure (per `SchedulingConfig`'s own Javadoc, the test suite
  deliberately runs with scheduling disabled or consolidated to avoid exactly this kind of
  cross-job interference) — confirm this during implementation rather than inventing a flaky
  multi-thread-timing IT. If a lightweight unit-level assertion is feasible (e.g. asserting the
  `ThreadPoolTaskScheduler` bean's configured pool size via `ApplicationContext` in a narrow,
  non-scheduling-suite test), add it; otherwise, document why not in the Dev Agent Record.

---

## AC4 — `ShedLockConfig`'s hostname truncation: avoid splitting a UTF-16 surrogate pair

### Context

`ShedLockConfig.lockProvider`
(`src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:65-69`, added by
skillars-deferred-126 AC3):

```java
String hostname = Utils.getHostname();
String truncatedHostname = hostname.length() > MAX_HOSTNAME_LENGTH
    ? hostname.substring(0, MAX_HOSTNAME_LENGTH)
    : hostname;
String lockedByValue = truncatedHostname + "-" + UUID.randomUUID();
```

`String.substring` truncates at a UTF-16 code-unit boundary. A hostname longer than
`MAX_HOSTNAME_LENGTH` (218, `:54`) whose 218th/219th code units happen to form one surrogate pair (one
Unicode code point outside the Basic Multilingual Plane — a container started with `docker run -h`
accepts arbitrary UTF-8, so this is reachable, if very unlikely) leaves a lone high surrogate, which is
not valid UTF-16 and cannot be UTF-8-encoded for the JDBC round-trip to `main.shedlock.locked_by`. The
JDBC driver then either substitutes `U+FFFD` or the `INSERT`/`UPDATE` fails with `invalid byte sequence
for encoding "UTF8"` — breaking lock acquisition for **every** `@SchedulerLock` job, the exact failure
mode this truncation exists to prevent in the first place. Recorded (not fixed) by skillars-deferred-126
as "very low likelihood"; this story closes it since it lives in the exact method/file AC-adjacent work
already touches and the fix is two lines with no decision required.

### Tasks

1. Re-verify the current line numbers for this method against HEAD before implementing (cited above as
   `:65-69`; confirm no drift since skillars-deferred-126's merge).
2. Back the truncation index off by one when it would land inside a surrogate pair, e.g.:
   ```java
   int truncateAt = MAX_HOSTNAME_LENGTH;
   if (truncateAt < hostname.length()
       && Character.isHighSurrogate(hostname.charAt(truncateAt - 1))
       && Character.isLowSurrogate(hostname.charAt(truncateAt))) {
       truncateAt--;
   }
   String truncatedHostname = hostname.length() > MAX_HOSTNAME_LENGTH
       ? hostname.substring(0, truncateAt)
       : hostname;
   ```
   Treat this as a starting sketch, not a mandate — verify the exact boundary condition against a real
   test case (see Tests below) rather than assuming the sketch above is off-by-one-correct as written.
3. Update the method's own inline comment (`:41-48`, the `MAX_LOCKED_BY_LENGTH`/`MAX_HOSTNAME_LENGTH`
   Javadoc) to note the surrogate-pair-safety fix, since it currently only discusses the raw length
   bound, not code-point safety.

### Tests

- New `ShedLockConfigIT` test (`src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java`)
  constructing a hostname (via whatever seam the existing tests already use to control
  `Utils.getHostname()`'s effective value, or a directly-testable extraction of the truncation logic if
  `Utils.getHostname()` itself is not mockable — check the existing test file's own pattern before
  inventing a new one) whose 218th/219th UTF-16 units form a surrogate pair (e.g. an emoji or other
  supplementary-plane character straddling that boundary), asserting the resulting `locked_by` value
  contains no lone surrogate and is a valid UTF-8-encodable string — and, if feasible, that it actually
  round-trips through a real `main.shedlock` INSERT/UPDATE without a `22001`/encoding error.

---

## AC5 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story genuinely
fixes; annotate `[DECIDED: accepted risk — skillars-deferred-127]` what it deliberately does not fix;
leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-126-...` in `deferred-work.md`:
   - **Bullet 1** (GDPR erasure / recalculateComposite not serialized, resurrection risk) — delete
     outright, fully fixed by AC1.
   - **Bullet 2** (GDPR erasure can be the deadlock victim) — delete outright, fully fixed by AC1 (as a
     structural consequence of the shared lock, not a separate mechanism).
   - **Bullet 3** (`now()` vs `clock_timestamp()` fragility) — leave open (this story's own Provenance
     section already explains why); do not delete or annotate `[DECIDED]` — it remains an
     un-actioned, latent risk, not a decision.
   - **Bullet 4** (unseeded `HAS_CODE_DEFAULT` config keys) — delete outright if AC2 ships as scoped
     (upsert for `HAS_CODE_DEFAULT` keys); if implementation finds the mechanism impractical for some
     reason discovered mid-implementation, annotate `[DECIDED: accepted risk — skillars-deferred-127]`
     instead and record why, and correct the "3 keys" framing to "18 keys" either way (this story's own
     re-verification found the original bullet undercounted).
   - **Bullet 5** (single-thread `@Scheduled` starvation) — delete outright, fully fixed by AC3.
   - **Bullet 6** (`ShedLockConfig` hostname truncation surrogate-pair split) — delete outright, fully
     fixed by AC4.
   - **Bullet 7** (skillars-deferred-126 AC1 Task 3's `EXPLAIN` confirmation never performed) — perform
     it now: run (or have run, and record the actual output/plan summary) an `EXPLAIN` against the
     `claimed_at < now() - make_interval(secs => ?)` predicate in both `VideoDeletionOutboxRepository`
     and `RadarCompositeDlqRepository`'s `resetStaleClaimed` queries, confirm the intended index is
     still used, and record the result inline in this task's own closeout note (not a separate file) —
     if index coverage turns out NOT to hold, that is itself a new finding to surface, not silently
     absorb.
   - Bullet 8 (`next_retry_at` app-clock eligibility) — already `[DECIDED]`, leave untouched.
3. Add the AC1 Task 8 residual (`radar_composite_dlq` rows for an erased player not cleared, but
   provably harmless post-fix since `radar_assessments` is also deleted) as a new, explicitly
   `[DECIDED: accepted risk — skillars-deferred-127]`-tagged bullet — low-value cleanup, not a
   data-integrity gap, recorded so a future reader does not have to re-derive why it is safe.
4. Add a `## Last audit: 2026-09-21 (skillars-deferred-127 story creation)` — update to
   `... dev-story completion` when implementation finishes — narrative section, in this file's
   established style, summarizing what closed and what (if anything) was accepted-and-documented
   instead.
5. Grep-sweep every file this story touches (`GdprErasureService`, `RadarCompositeCalculationService`,
   `ConfigService`, `SchedulingConfig`, `ShedLockConfig`) against the rest of the ledger for any other
   stale reference this story's changes might affect — re-confirm each hit is either unrelated or
   already correctly annotated, per this series' own standard practice.

---

## Dev Notes

- **Module boundaries respected:** AC1 touches `platform.admin` (GdprErasureService) and
  `platform.development` (RadarCompositeCalculationService's Javadoc only, no behavior change there);
  AC2 touches `platform.config`; AC3 touches `infrastructure.config`/`application.yaml`; AC4 touches
  `infrastructure.config`. No cross-module leakage beyond what each AC's own fix requires.
- **No new Flyway migration is needed for any AC.** AC1 reuses an existing repository method and lock
  pattern; AC2 creates `platform_config` rows programmatically (that is the entire point — avoiding a
  migration is why the upsert fix was chosen over "seed all 18 keys"); AC3/AC4 are pure code/config
  changes with no schema impact.
- **Testing convention (per this project's `docs/validation-strategy.md`, already followed by every
  prior story in this series):** targeted tests only for the affected classes — no `mvn verify` run
  locally. Frontend is untouched by this story; no `frontend-tests` PR label is needed this time
  (confirm at PR-creation time that the actual final diff has no `src/frontend/**` changes before
  omitting the label — do not assume from this Dev Note alone).
- **Re-verify every file/line citation in this story against HEAD immediately before implementing each
  AC** — this project's own established process (see this story's own Provenance section) requires it,
  and several of this story's own citations already needed correcting once during its creation (the
  "3 keys" → "18 keys" count; `ShedLockConfig`'s truncation lines shifting `63-64` → `65-69` since the
  original deferral note was written).

---

## File List (expected — reconcile against the actual final diff before AC5)

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  (AC1 — Javadoc only)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` (AC2)
- `src/main/resources/application.yaml` (AC3)
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java` (AC4)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1 tests)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java` (AC2 tests)
- `src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java` (AC4 tests)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review at dev-story completion)

---

## Change Log

- 2026-09-21: Story created via manual ledger-mining process (this project's established convention
  for `skillars-deferred-N` stories — `/bmad-create-story`'s generic epic/PRD-driven workflow does not
  fit this ledger-mining shape, confirmed by checking its `customize.toml` for a project-specific
  override and finding none). Mined the freshest same-day code-review deferral
  (`code review of skillars-deferred-126`, 2026-09-21, 7 open bullets + 1 already-`[DECIDED]`). All
  citations independently re-verified against `master@24fe4a80` (skillars-deferred-126's own merge,
  PR #214) at creation time — one concrete correction found during this verification: the config-key
  gap (AC2) affects 18 `HAS_CODE_DEFAULT` keys, not the 3 the original bullet named as examples. Four
  owner decisions taken live with the user (`AskUserQuestion`) before drafting: (1) close both the
  GDPR-erasure/radar-recalculation resurrection bug and its sibling deadlock hazard via a single shared
  `player_profiles` pessimistic lock (reusing the existing `findByIdForUpdate` +
  `PessimisticLockRetryer` pattern `recalculateComposite` already uses), rather than a table-order-only
  fix or leaving the resurrection risk as documented-not-fixed; (2) fix the project-wide unseeded
  `HAS_CODE_DEFAULT` config-key write gap via an upsert-on-write in `ConfigService.updateConfig`,
  scoped narrowly to `HAS_CODE_DEFAULT` keys rather than the full bounded-key set or a
  documentation-only response; (3) fix the single-thread `@Scheduled` starvation gap by adding
  `spring.task.scheduling.pool.size: 8` rather than leaving it as a documented risk for a dedicated
  future story; (4) keep this story's scope to the same-day deferral (4 ACs) rather than reaching
  further into the ledger for an additional unrelated item. Also folded in, no decision required: the
  `ShedLockConfig` hostname-truncation surrogate-pair-split bug (AC4, bundled with the truncation-length
  citation refresh since it lives in the exact method already re-verified) and skillars-deferred-126 AC1
  Task 3's never-performed `EXPLAIN`/index-coverage confirmation (folded into AC5 as a verification
  task). Left explicitly open, with rationale recorded in the Provenance section: the `now()` vs
  `clock_timestamp()` fragility bullet (latent, not a live bug, and this story is already a full 4-AC
  bundle without it).
