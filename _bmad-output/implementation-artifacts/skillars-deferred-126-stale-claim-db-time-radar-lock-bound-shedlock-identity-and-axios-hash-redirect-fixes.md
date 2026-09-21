# Story: Stale-Claim DB-Time Fix, Radar Row-Lock Bound, ShedLock Instance Identity & Axios Hash-Redirect Fixes

**Story Key:** `skillars-deferred-126-stale-claim-db-time-radar-lock-bound-shedlock-identity-and-axios-hash-redirect-fixes`
**Epic:** Deferred Work
**Priority:** High (a real cross-instance clock-skew hazard in the outbox/DLQ stale-claim mechanism
both processors share, a real unbounded-wait gap on `RadarCompositeDlqProcessor`'s per-row write path
with a concretely identified concurrent-conflict source, a real ShedLock same-host-instance-identity
gap, and a real frontend hard-navigation bug reachable on every session expiry) — plus the standard
ledger closeout.
**Status:** ready-for-dev
**Created:** 2026-09-21

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
subsystems, or `deploy-*` items already closed and merely un-struck in an old summary table (verified
during this story's creation — see the "not touched" note in AC5). Four owner decisions were taken
live with the user (`AskUserQuestion`) before drafting this story — see AC1–AC4 below, each of which
records both the recommended option the ledger's own fix note suggested and the alternative(s)
presented.

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
  — the only absolute cross-instance time comparison either processor's claim/reset design makes.

If instance B's clock runs ahead of instance A's by more than `STALE_CLAIM_WINDOW`'s margin above
`lockAtMostFor` (5 minutes on both processors after skillars-deferred-125 AC2), B's `resetStaleClaimed`
tick frees and immediately re-claims rows A is still legitimately processing — a duplicate
`recalculateComposite` / duplicate video-deletion attempt, an externally-visible side effect
`claimed_by` cannot undo after the fact. At a skew of that magnitude or more this happens during
entirely normal operation, not only in the single-slow-row residual the `MAX_RUN_DURATION`-sampling
gap already documents as accepted risk.

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
  (:staleWindowSeconds * interval '1 second'))`. Update both call sites to pass
  `STALE_CLAIM_WINDOW.toSeconds()` instead of `runClaimedAt.minus(STALE_CLAIM_WINDOW)`.
- `runClaimedAt` (the Java `Instant.now()` local) stays exactly as-is for its two other uses — binding
  `claimPendingBatch`'s `next_retry_at <= :now` eligibility check (unaffected, see above) and computing
  `Instant deadline = runClaimedAt.plus(MAX_RUN_DURATION)` (`RadarCompositeDlqProcessor.java:187` /
  `VideoDeletionOutboxProcessor.java:194`) — this is each run's own **self-terminating budget check**,
  purely in-process, not a cross-instance comparison, and must not be touched.
- Postgres's bare `now()` returns the current **transaction's** start time (equivalent to
  `CURRENT_TIMESTAMP`, constant within one transaction), and `claimPendingBatch` is its own
  `@Modifying @Transactional` method — each call is its own fresh transaction, so `now()` there behaves
  exactly like a single claim-instant stamp, matching the current semantic `runClaimedAt` provided.

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
   (:staleWindowSeconds * interval '1 second')`. Update both processors' call sites to pass
   `STALE_CLAIM_WINDOW.toSeconds()` (confirm the exact `Duration` accessor — `toSeconds()` vs.
   `getSeconds()` — against the pinned Java version's `java.time.Duration` API) instead of
   `runClaimedAt.minus(STALE_CLAIM_WINDOW)`.
4. Update both classes' `STALE_CLAIM_WINDOW`/`MAX_RUN_DURATION`/`resetStaleClaimed`-adjacent Javadoc to
   document the DB-time change and the stated residual (next_retry_at itself stays app-clock-stamped).
5. New/updated tests: `RadarCompositeDlqRepositoryIT` and the equivalent `VideoDeletionOutboxProcessorIT`
   repository-level native-query coverage already exercise `claimPendingBatch`/`resetStaleClaimed`
   against a real Postgres (confirm exact existing test names at implementation time) — extend or add
   a case that seeds a row with `claimed_at` set via a **direct SQL `now() - interval`** (not a
   Java-computed `Instant`) to prove `resetStaleClaimed`'s new signature genuinely compares against the
   database's clock, not the JVM's — the one behavior a purely app-clock-based test fixture cannot
   distinguish from the old implementation. Also confirm `claimPendingBatch`'s stamped `claimed_at`
   value, read back after the call, is close to real wall-clock time (sanity bound, not an exact-match
   assertion, since the test JVM and the test database may themselves be skewed in CI).
6. Mutation-check by hand: temporarily revert one repository's `now()` back to `:now`, confirm the new
   test(s) still pass unchanged (expected — a test-harness single-clock environment cannot distinguish
   the two without deliberately skewing the test DB or JVM clock, which is out of scope to engineer);
   instead mutation-check by confirming the new test fails if `resetStaleClaimed`'s `WHERE` clause is
   reverted to compare against a bind-passed `Instant` that is deliberately skewed in the test itself.
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
(`RadarCompositeCalculationService.java:130-131`) — both native `INSERT ... ON CONFLICT (...) DO
UPDATE`/`DO NOTHING` statements (`PlayerRadarCompositeRepository.java:16-24`,
`PlayerRadarBaselineRepository.java:19-24`). An `INSERT ... ON CONFLICT` takes an implicit row lock on
the conflicting row exactly like a plain `UPDATE`, but neither statement carries any lock/statement
timeout — unlike `findByIdForUpdate`, which deliberately does.

**Confirmed, concrete conflict source** (found during this story's creation, not in the original
deferral): `GdprErasureService.eraseRadarAndDevelopmentData` (or its equivalent method —
`GdprErasureService.java:196-203`) calls `playerRadarBaselineRepository.deleteAllByPlayerId(playerId)`
and `playerRadarCompositeRepository.deleteAllByPlayerId(playerId)` directly inside its own
`@Transactional(propagation = Propagation.REQUIRES_NEW)` erasure transaction — **without** first taking
the `PlayerProfile` pessimistic lock `recalculateComposite` serializes on. A GDPR erasure and an
in-flight radar recalculation for the same player can therefore race for the same
`player_radar_composites`/`player_radar_baselines` row through two genuinely independent lock paths.
If the erasure transaction is itself slow (blocked on something else, a slow disk, a long-running
sibling delete earlier in the same method), `recalculateComposite`'s upsert can hang on that row's lock
indefinitely — `MAX_RUN_DURATION` is sampled only between `RadarCompositeDlqProcessor`'s loop
iterations (when this path runs via the DLQ retry poller) and does not apply at all to the
`AFTER_COMMIT`-triggered live path (`onRadarEntrySubmitted`), so a hang there has no self-terminating
backstop whatsoever.

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

**New tunable, mirroring this codebase's established `ConfigService`/`ConfigBounds` pattern** (e.g.
`platform.moderation_lock_timeout_minutes` in `ConfigBounds.java:92`) rather than a hardcoded constant
— add a new bounded key (suggested: `platform.radar_composite_lock_timeout_seconds`, a conservative
default in the single-digit seconds, bounded well below both `MAX_RUN_DURATION` and the AC2
stale-window margin) and read it via `configService.getBoundedLong(...)`.

**Exception-handling scope, deliberately narrow.** A `SET LOCAL lock_timeout` expiry surfaces as a
Postgres `55P03 lock_not_available` error; confirm empirically (not assumed) what Spring/Hibernate
exception this project's pinned Spring Boot version translates that SQLState to. Whatever it is, let it
propagate — do **not** wrap the two upsert calls in `PessimisticLockRetryer.withBoundedRetry` or any
new retry-in-place mechanism. `recalculateComposite`'s two existing callers already provide
eventual-consistency recovery on any thrown exception: `onRadarEntrySubmitted`'s own `try`/`catch`
routes to `dlqService.emitFailedCompositeCalculation` (retried by `RadarCompositeDlqProcessor` on its
own schedule), and `RadarCompositeDlqProcessor`'s own loop-level `handleFailure` does the same for the
DLQ-retry path. Building a second, in-place retry mechanism here would duplicate that recovery path for
no benefit — the goal of this AC is only to make a stuck upsert **fail fast and become visible**, not
to make it silently succeed after a wait.

### Tasks

1. Re-verify `recalculateComposite`'s current structure, the two upsert call sites, and the
   `GdprErasureService` conflict path against current HEAD before implementing.
2. Add a new `ConfigBounds` entry for the lock-timeout tunable, mirroring the existing
   `platform.moderation_lock_timeout_minutes` shape (name, bounds, `failFast` — decide whether an
   out-of-range value should hard-fail boot or ERROR-and-fall-back, consistent with how the sibling
   video-quota keys this class already documents decide that question).
3. In `RadarCompositeCalculationService.recalculateComposite`, after the `findByIdForUpdate` +
   `entityManager.refresh` call and before the per-skill loop, issue `SET LOCAL lock_timeout = ...`
   scoped to the transaction (confirm the exact JDBC/`EntityManager` mechanism to use — a native query
   via `entityManager`, or the class's existing dependency surface — and confirm Postgres's accepted
   syntax for the value, e.g. milliseconds as a bare integer vs. an interval-string literal).
4. Confirm empirically what exception type is thrown when the timeout is hit against a genuinely
   blocked row (a real Testcontainers-backed test holding a competing lock open, not a mocked
   exception) — document the finding in the field/method Javadoc next to the new `SET LOCAL` call.
5. New test(s): a real concurrency test that holds an open transaction with a lock on a
   `player_radar_composites` (or `player_radar_baselines`) row for a known player+skill, then calls
   `recalculateComposite` for that same player+skill from another thread/connection, and asserts it
   fails within a bounded wall-clock time (not "eventually," a concrete upper bound with margin) rather
   than hanging past the configured timeout. Mirror this project's existing concurrency-IT conventions
   for holding a lock open across two connections (see `AdminCoachEnforcementIsolationRuntimeIT` or
   similar precedent cited elsewhere in this codebase's test suite for the general shape).
6. Confirm the new `SET LOCAL` does not change behavior for the already-bounded
   `findByIdForUpdate`/NOWAIT call — re-run the existing `deferred-77` concurrent-recalculation test(s)
   this class already has, if any, to confirm no regression in that lock's own timing.
7. Update `RadarCompositeCalculationService`'s class/method Javadoc to document the new bound, its
   config key, and the deliberate decision not to retry-in-place (existing DLQ/AFTER_COMMIT-catch
   recovery is the intended path).
8. Re-run `RadarCompositeCalculationService`'s full test class plus any IT touching
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

### Tasks

1. Re-verify `ShedLockConfig.java`'s current `JdbcTemplateLockProvider.Configuration.builder()` chain
   against HEAD before implementing — confirm `.withLockedByValue(...)` is still absent.
2. Resolve the current hostname via a reliable, already-available mechanism — confirm whether
   ShedLock's own hostname-resolution helper is public API in the pinned `shedlock` version (the
   deferral note names an internal `Utils.getHostname()`; verify its accessibility before depending on
   it) or use a standard JDK mechanism (`InetAddress.getLocalHost().getHostName()`, with a documented
   fallback if that throws `UnknownHostException` in a container without proper DNS/hosts setup — this
   is a real failure mode in some container runtimes, not hypothetical).
3. Add `.withLockedByValue(<hostname> + "-" + UUID.randomUUID())` to the `JdbcTemplateLockProvider`
   builder chain. Compute this once (e.g. a field or a value computed at bean-construction time), not
   per-lock-acquisition — a stable per-JVM identity is the point; a fresh UUID on every lock call would
   not distinguish "this JVM released its own lock" from "some other JVM released it."
4. Update `ShedLockConfig`'s class Javadoc to document the identity shape and why (same-host
   multi-instance safety during rolling deploys).
5. New test in the existing `ShedLockConfigIT` (`src/test/java/.../infrastructure/config/
   ShedLockConfigIT.java`) — after acquiring a lock via the real `lockProvider` bean, query
   `main.shedlock.locked_by` directly (mirroring `shedlockTable_existsAfterStartup`'s existing
   `jdbcTemplate` query style) and assert it is neither null/empty nor a bare hostname with no
   distinguishing suffix (e.g. assert it contains a value matching a UUID-shaped suffix, or simply
   assert it is longer than the plain hostname and unique across two separately-constructed
   `LockProvider` instances in the same test if that is feasible to construct — confirm the most
   direct way to prove uniqueness without over-engineering the test).
6. Re-run `ShedLockConfigIT` in full — zero regressions expected, all three existing tests still green.

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
3. Replace the 401 handler's `window.location.href = ...` hard navigation with a call to
   `pushLoginOrHardNavigate(router, { expired: true })` (import from `src/utils/sessionRedirect`),
   guarded by a check that `router` is actually set (see the edge-case note above); keep a minimal
   hash-aware literal fallback (`` `/#/login?...` ``) for the case it is not, rather than silently
   doing nothing.
4. Confirm `stopSessionMonitoring()`/`cleanup()`/`deleteUserCookie()` (the existing teardown calls
   immediately above the redirect) are unaffected — this AC only changes the navigation call itself.
5. New/updated tests: add or extend a boot-file spec for `axios.js` (confirm this project's existing
   convention for testing a Quasar boot file — check whether one already exists for `i18n.js`/`theme.js`
   to mirror, since `axios.js` itself currently has no dedicated spec) covering the 401 handler calling
   `pushLoginOrHardNavigate` with `{ expired: true }` against a real `createMemoryHistory` router,
   mirroring `sessionRedirectSpec.js`'s/`AppSpec.js`'s existing real-router test style rather than
   mocking `vue-router` wholesale. Needs the `frontend-tests` PR label, per this project's established
   convention for any change touching frontend test files.
6. Re-run the full frontend suite (`npm run test:unit`) — zero regressions expected.

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
     `SET LOCAL` mechanism impractical for some reason discovered mid-implementation, annotate
     `[DECIDED: accepted risk — skillars-deferred-126]` instead and record why. Whichever disposition,
     record the concrete `GdprErasureService` conflict source this story's creation identified (not in
     the original bullet's text) in the closing note, so a future reader does not have to re-derive it.
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
   `ShedLockConfig`, `boot/axios.js`, `ConfigBounds`) against the rest of the ledger for any other stale
   reference this story's changes might affect — re-confirm each hit is either unrelated or already
   correctly annotated, per this series' own standard practice.

---

## Dev Notes

**Cross-AC dependencies:** AC1 touches both `RadarCompositeDlqRepository`/`RadarCompositeDlqProcessor`
and `VideoDeletionOutboxRepository`/`VideoDeletionOutboxProcessor`; AC2 touches
`RadarCompositeCalculationService`/`PlayerRadarCompositeRepository`/`PlayerRadarBaselineRepository`/
`ConfigBounds` — a different class in the same package as AC1's Radar half, but an independent fix (no
shared method/field). AC3 (`ShedLockConfig`, infrastructure package) and AC4 (frontend, `boot/axios.js`)
are both fully independent of AC1/AC2 and of each other. Sequence as separate commits so any one AC can
be reverted independently, mirroring this series' established convention.

**No new Flyway migration in this story.** Confirm this remains true at implementation time — if any
AC's implementation turns out to need one, re-derive the next free version against the actual
`src/main/resources/db/migration/` directory at that time, not any number cited anywhere in this file.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project. Run each AC's own targeted test class(es) locally during implementation as needed;
`mvn compile`/`mvn test-compile` are fine, `mvn verify` is not. For AC4, run `npm run test:unit` (or the
project's equivalent Vitest invocation — confirm the actual script name in
`src/frontend/package.json` at implementation time) locally.

**AC2's concurrency test needs a genuinely held lock, not a mock.** Per this project's own established
practice (see `AdminCoachEnforcementIsolationRuntimeIT` and similar precedents), a test proving a
`lock_timeout` bound actually fires must hold a real competing transaction/lock open on a second
connection — a mocked repository call cannot exercise Postgres's own lock-wait/timeout machinery.
Budget real wall-clock time in that test proportional to the configured timeout value; do not set the
timeout so low in test config that the assertion becomes flaky against CI's real scheduling jitter, and
do not set it so high that the test suite's own runtime budget suffers — confirm a sensible test-profile
override for the new config key if the production default is too slow for a fast test loop.

**AC3's ShedLock hostname resolution may behave differently across environments.** `docker-compose.yml`
containers get Docker's own container-id-derived hostname by default unless explicitly overridden —
confirm what value is actually returned in this project's containerized runtime (dev/uat/prod compose
files) before assuming a human-readable value, and note in the Javadoc that "hostname" here means
"whatever the container runtime reports," which may itself be a randomly-generated container ID rather
than the VPS's own hostname — still valid for uniqueness purposes, just worth setting the right
expectation for anyone reading the `main.shedlock` table during an incident.

**CI context-count ceiling (backend ACs only):** `assert-context-count.sh`'s Spring-context ceiling was
44 as of skillars-deferred-124 AC2 (`pr-build.yml`'s call site) — confirm the current value at
implementation time (it may have moved since). AC1/AC2's new tests reuse existing test classes'
existing context configuration where possible; confirm against the CI failure message if a new context
fork is needed, and bump the ceiling with a dated justification comment mirroring the existing history
in that script if so.

---

## File List (expected — reconcile against the actual final diff before ledger closeout)

**Production code:**
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java` (AC2, only if the `SET LOCAL` call needs a repository-level home rather than an `EntityManager` call inline)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC2, new bounded key)
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java` (AC3)
- `src/frontend/src/boot/axios.js` (AC4)

**Tests:**
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepositoryIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java` (AC1, if any unit-level assertion needs updating)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java` (AC1)
- A `RadarCompositeCalculationService`-level concurrency test, new or extended (AC2 — locate the
  existing test class first; confirm exact name at implementation time)
- `src/test/java/com/softropic/skillars/infrastructure/config/ShedLockConfigIT.java` (AC3)
- A new or extended `axios.js` boot-file spec (AC4 — confirm naming/location convention against any
  existing boot-file spec before creating)

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/skillars-deferred-126-stale-claim-db-time-radar-lock-bound-shedlock-identity-and-axios-hash-redirect-fixes.md` (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

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
