# Senior-dev audit — `skillars-deferred-126` story spec

**Target:** `_bmad-output/implementation-artifacts/skillars-deferred-126-stale-claim-db-time-radar-lock-bound-shedlock-identity-and-axios-hash-redirect-fixes.md`
**Audited at:** `HEAD = 49b27359` (branch `story/deferred-126-clock-skew-lock-fixes`), 2026-09-21
**Method:** every file, line citation, constraint, precedent and named test file in the story was opened
and compared against actual source. ShedLock claims were verified against the decompiled
`shedlock-*-7.10.1` jars in `~/.m2`, not from memory. Findings below are only those I could reproduce
from the source; where a suspicion did not survive verification it is recorded under
§4 *Checked and cleared* so it is not re-raised.

**Verdict:** the story's four findings are all **real** and its four owner decisions are all
**sound**. The core mechanism claims — including the two the story left as open questions — hold up.
The defects are in the *implementation instructions*: two ACs have task lists that would produce a
vacuous test or a boot-blocking config, one AC has an unflagged behavioural race with an existing
call site, and one AC contains two tasks that contradict each other.

---

## 1. Citation accuracy

Every line citation was re-read. Accuracy is high — 27 of 30 are exact or within range.

| Story claim | Actual | Status |
|---|---|---|
| `ShedLockConfig.java:27` `.usingDbTime()` | line 27 | exact |
| `ShedLockConfig.java:21-29` builder, `.withLockedByValue` absent | lines 21-29, absent | exact |
| `RadarCompositeDlqProcessor.java:157` `Instant.now()` | line 157 | exact |
| `RadarCompositeDlqProcessor.java:161` `resetStaleClaimed(...)` | line 161 | exact |
| `RadarCompositeDlqProcessor.java:187` `deadline = ...plus(MAX_RUN_DURATION)` | line 187 | exact |
| `VideoDeletionOutboxProcessor.java:152` / `:160` / `:194` | 152 / 160 / 194 | exact |
| `RadarCompositeDlqRepository.java:24-35` `claimPendingBatch` | query 24-34, method 35 | in range |
| `RadarCompositeDlqRepository.java:56-61` `resetStaleClaimed` | 56-61 | exact |
| `VideoDeletionOutboxRepository.java:25-36` / `:70-75` | 25-36 / 70-75 | exact |
| "5 minutes on both processors" | radar 15m vs `PT10M`; video 20m vs `PT15M` | exact |
| `RadarCompositeCalculationService.java:79-133` | method 78-135 | in range |
| `RadarCompositeCalculationService.java:130-131` upserts | 130, 131 | exact |
| `PlayerProfileRepository.java:41-47` NOWAIT hint | hint 44-45, method 47 | off-by-3 start, in range |
| `PlayerRadarCompositeRepository.java:16-24` | query 16-25 | in range |
| `PlayerRadarBaselineRepository.java:19-24` | 19-24 | exact |
| `ConfigBounds.java:92` `platform.moderation_lock_timeout_minutes` | line 92 | exact |
| `boot/axios.js:165-167` hard navigation | 165-167 | exact |
| `boot/axios.js:122` module-scope interceptor | 122 | exact |
| `boot/axios.js:201` `defineBoot(async () => {` | 201 | exact |
| `quasar.config.js:40` `vueRouterMode: 'hash'` | line 40 | exact |
| `pushLoginOrHardNavigate(router, { expired })` | `sessionRedirect.js:99` | exact |
| 3 existing helper call sites (`App.vue`, `useSession.js`, `MainLayout.vue`) | `App.vue:41`, `useSession.js:114`, `MainLayout.vue:378` | exact |
| `i18n.js` destructures `{ app }`; no boot file takes `router` | `i18n.js:25`; confirmed across all 3 boot files | exact |
| `ShedLockConfigIT` exists, "all three existing tests" | 3 tests (`:31`, `:40`, `:45`) | exact |
| `assert-context-count.sh` ceiling 44 at `pr-build.yml` call site | `pr-build.yml:72`, script `:125`/`:135` | exact |
| `MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT` shipped by deferred-125 AC4 | `MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT`, in `a40d1985` | exact (rule is nested in `Rule`, and the class lives in `src/test`) |
| `AdminCoachEnforcementIsolationRuntimeIT` precedent exists | exists | exact |
| `GdprErasureService.eraseRadarAndDevelopmentData` at `:196-203` | **no such method**; `erase` (`:75`) → private `deletePlayerDevelopmentData` (`:194`); the two deletes are at **`:201-202`** | **F-12** |

---

## 2. Defects — must fix before dev-story

### F-1 (High, AC2 + Dev Notes) "No new Flyway migration in this story" contradicts AC2's own named precedent

AC2 Task 2 says to add a `ConfigBounds` entry "mirroring the existing
`platform.moderation_lock_timeout_minutes` shape". Following that precedent **requires a migration**:

- `V139__baseline_seed_data.sql:101` seeds `platform.moderation_lock_timeout_minutes` into
  `main.platform_config`.
- That key is **not** in `ConfigBounds.HAS_CODE_DEFAULT` (`ConfigBounds.java:261-277`).
- `ConfigStartupAssertion` (`:80-120`) logs `ERROR` + `config.value.misconfigured` in **every** profile
  for any `ConfigBounds.ALL` key that is absent/blank and not in `HAS_CODE_DEFAULT`, and **throws
  `AppSetupException` (blocks boot) in non-`dev`** if the key is `failFast` — which the named
  precedent is (`failFast = true`, `ConfigBounds.java:92`).

Three unstated required steps:

1. Register the key in `ConfigBounds.ALL`'s static block (`:283-309`). Task 2 says only "add a new
   `ConfigBounds` entry"; a `BoundedKey` constant not added to `ALL` is invisible to
   `ConfigStartupAssertion` **and** to `ConfigBoundsEnumCoverageTest` (`:104-114`, which iterates
   `ALL`) — a silently inert tunable.
2. Decide seed-vs-`HAS_CODE_DEFAULT` explicitly, and reconcile with the Dev Notes' "No new Flyway
   migration".
3. Pick the overload deliberately — see **F-4**.

**Fix:** either state in AC2 that a Flyway migration *is* required (re-derive the next free version
against `src/main/resources/db/migration/` at implementation time) and delete the "No new Flyway
migration" Dev Note, or use the 4-arg `getBoundedLong` + `HAS_CODE_DEFAULT` route and say so —
noting it deviates from the named precedent.

### F-2 (High, AC2 Task 5) The prescribed concurrency test would be vacuous as written

Two mandatory fixture preconditions are missing, and the story points at the wrong precedent (F-17),
whose `setUp()` would reproduce both gaps verbatim:

1. **The per-skill loop only runs for skills with assessment rows.**
   `RadarCompositeCalculationService.java:107` iterates `bySkill`, built at `:97-105` **exclusively**
   from `radarRepository.findAggregatesByPlayerAndSkills(...)` (`:88`). With no `radar_assessments`
   rows for that player+skill, `bySkill` is empty and `upsertComposite`/`insertBaselineIfAbsent`
   (`:130-131`) are **never reached**. The existing precedent IT
   (`RadarCompositeCalculationServiceConcurrencyIT.setUp()`, `:41-52`) seeds only a `user` and a
   `player_profiles` row — no assessments. A test copied from it never executes the code AC2 bounds.
2. **`ON CONFLICT` only waits when there is something to conflict with.** `upsertComposite` is
   `INSERT … ON CONFLICT (player_id, skill_code) DO UPDATE` (`PlayerRadarCompositeRepository.java:20`).
   With no pre-existing `player_radar_composites` row for that `(player_id, skill_code)`, the insert
   takes the no-conflict path and waits on nothing. The competing session must hold
   `SELECT … FOR UPDATE` on a **pre-seeded** row.

**Fix:** Task 5 must require (a) ≥1 `radar_assessments` row for the target player+skill, (b) a
pre-seeded `development.player_radar_composites` row for that exact key, (c) the lock held on that
seeded row. Without all three the assertion passes for the wrong reason, in both directions.

### F-3 (High, AC4) Unflagged double-navigation race with `App.vue`'s existing handler

`boot/axios.js:147` calls `refreshExpiryState()` **before** the 401 block. That path is synchronous:
`sessionManager.refreshExpiryState()` (`:156`) → `tick()` (`:168`) →
`window.dispatchEvent(new CustomEvent('session:expired'))` (`:172`) → `App.vue`'s window listener →
`App.vue:41` `pushLoginOrHardNavigate(router, { expired: true })`. That call is `async`; its
`router.push` has not resolved when control returns.

Execution then falls straight into `axios.js:155-168`, which after AC4 calls
`pushLoginOrHardNavigate` a **second time in the same tick**. The helper's "already on `/login`"
guard (`sessionRedirect.js:100`) cannot fire — `router.currentRoute.value.path` is still the old
route. Two concurrent `router.push({ path: '/login' })` calls: the second supersedes the first, the
first resolves with `NAVIGATION_CANCELLED`, which **is** in `DID_NOT_LAND`
(`sessionRedirect.js:59`) — so `hardNavigateToLogin` fires and does a full
`window.location.href` + `window.location.reload()`.

`axios.js:141-146` already documents this co-firing ("both paths tear down… but can produce two
navigations") as an *accepted, benign* residual. AC4 converts it into an unnecessary full page
reload. The story never engages with that comment.

**Fix:** AC4 needs a decision here, not silence. Cheapest: have the 401 handler skip the redirect
when it has just dispatched `session:expired` (the `refreshExpiryState()` return already knows), or
add an in-flight guard to `pushLoginOrHardNavigate` alongside `hardNavigated`. Whatever is chosen,
add a test for "401 while `App.vue`'s handler is mid-push" — otherwise the regression ships silently,
since a hard reload is not observably wrong in a single-assertion test.

### F-4 (High, AC2) The new config read widens a residual this codebase has already documented

`RadarCompositeDlqProcessor`'s class Javadoc (`:41-47`) records a deliberate accepted residual:
*"if `configService.getBoundedLong` itself is permanently broken … `handleFailure` can never complete
its own transaction, so `attempts` is never persisted and the row stays CLAIMED -> reset ->
re-claimed -> retried indefinitely."*

AC2 puts a **second** `getBoundedLong` call inside `recalculateComposite`, upstream of all the real
work. Consequences:

- With the **3-arg** `getBoundedLong(key, min, max)` (`ConfigService.java:126-139`), a missing key
  throws `IllegalStateException` (`:127` → `getLong(key)`). A broken config lookup would then break
  `recalculateComposite` itself, on **both** the DLQ-retry path *and* the live `AFTER_COMMIT` path —
  strictly wider than the residual above, which is confined to `handleFailure`.
- With the **4-arg** `getBoundedLong(key, default, min, max)` (`:108-116`) it never throws.

**Fix:** AC2 must name the overload, and should prefer the 4-arg form so the residual is not widened.
Task 2's "decide whether an out-of-range value should hard-fail boot or ERROR-and-fall-back" is
asking the `failFast` question; this is the separate *call-site* question, and the story does not ask
it.

### F-5 (High, AC2) The live-path hang is worse than the story states

AC2 says a hang on the `AFTER_COMMIT` path "has no self-terminating backstop whatsoever." True, but
incomplete. `onRadarEntrySubmitted` is `@Async("reportExecutor")`
(`RadarCompositeCalculationService.java:58`), and `reportExecutor`
(`DevelopmentConfig.java:105-118`) is `corePoolSize=2`, `maxPoolSize=4`, `queueCapacity=50`,
`RejectedExecutionHandler = CallerRunsPolicy`, shared with `ReportGenerationService:201`.

So a hung upsert permanently consumes 1 of at most **4** threads; four concurrent hangs exhaust the
pool and stall all report generation; and once the 50-slot queue also fills, `CallerRunsPolicy` runs
new submissions **on the publisher's own thread** — a request or scheduler thread then blocks
synchronously on the same lock. This strengthens AC2's case and should be in its rationale so the
priority is not later re-litigated.

---

## 3. Gaps and imprecisions — should fix

### F-6 (Medium, AC4) `{ expired: true }` is hardcoded but the gate covers two different errorKeys

`axios.js:156` gates on `errorKey === 'security.sessionExpired' || errorKey === 'security.unauthorized'`.
`security.unauthorized` is **not** an expiry: it is emitted for `AuthorizationException`
(`ApiAdvice.java:241`) and for every non-expiry JWT failure
(`JWTAuthorizationFilter.java:258`, `:279` — `expired ? "security.sessionExpired" : "security.unauthorized"`).

Today the `&expired=true` conflation is harmless *because of the very bug AC4 fixes* — in hash mode
the param never reaches the SPA, so `LoginPage.vue:16`'s `route.query.expired === 'true'` never sees
it. AC4 makes the param live for the first time, so a plain unauthorized 401 will now render
"Your session is no longer valid. Please sign in again." This is the same false-banner class
deferred-125's own code review fixed for logout (`sessionRedirect.js:17-20`).

**Fix:** pass `{ expired: errorKey === 'security.sessionExpired' }`.

(Verified *not* an issue: a bad-credentials login throws `AuthenticationException` →
`security.authError` (`ApiAdvice.java:250`), so the interceptor's teardown does not fire on a failed
login attempt.)

### F-7 (Medium, AC4 Task 5) Test feasibility is materially understated

Task 5 says to mirror "`sessionRedirectSpec.js`'s/`AppSpec.js`'s existing real-router test style".
That style does not transfer, because those specs work by **mocking `boot/axios` away**:

- `vi.mock('src/boot/axios', …)` appears in `AppSpec.js:20`, `useSessionSpec.js:22`,
  `MainLayoutSpec.js:22`, `BookingRequestPageSpec.js:30`, `ParentBookingsPageSpec.js:18`, and
  `paymentStoreSpec.js:25` — whose own comment states the reason: *"the real payment.api pulls in
  src/boot/axios → src/boot/i18n"*. **No spec in the suite has ever loaded the real module.**
- There is no boot-file spec for **any** boot file — no `src/boot/__tests__/` exists. Task 5's
  "check whether one already exists for `i18n.js`/`theme.js` to mirror" resolves to: none.
- Loading the real module pulls in `src/boot/i18n`, `@rajesh896/broprint.js` and the real
  `src/plugins/sessionManager` (interval logic).
- The response-error handler is an inline closure at `axios.js:136-197`, not exported. Reaching it
  needs either axios internals (`api.interceptors.response.handlers[0].rejected`) or a mock adapter —
  `axios-mock-adapter` is not a dependency.

**Fix:** Task 5 should name the actual approach and its cost, rather than pointing at a precedent
that does the opposite. (The alias is *not* a blocker: `#q-app/wrappers` is in
`.quasar/tsconfig.json`'s `paths` at line 69, so `vite-tsconfig-paths` resolves it — see §4.)

### F-8 (Medium, AC3) Tasks 3 and 5 contradict each other

- Task 3: compute the identity "once (e.g. a field or a value computed at bean-construction time),
  not per-lock-acquisition".
- Task 5: assert it is "unique across two separately-constructed `LockProvider` instances in the same
  test".

A `@Configuration` instance field or `static` field is computed once per JVM, so **both**
`lockProvider(dataSource, meterRegistry)` invocations return the *same* `locked_by` — Task 5's
assertion is then unachievable, not merely awkward.

**Fix:** compute the value inside the `@Bean` method body. The bean is a singleton, so production
still gets exactly one identity per JVM (Task 3's actual requirement), *and* two direct factory calls
in a test differ, making Task 5 achievable. Otherwise drop Task 5's uniqueness clause and assert only
the shape (non-blank, contains a UUID-shaped suffix, longer than the bare hostname).

### F-9 (Medium, AC1) Two entity Javadocs will be left stating the opposite of the new behaviour

AC1 Task 4 scopes Javadoc updates to "both classes' `STALE_CLAIM_WINDOW`/`MAX_RUN_DURATION`/
`resetStaleClaimed`-adjacent Javadoc", and the File List omits the entities entirely. These become
false the moment `claimed_at = now()` lands:

- `VideoDeletionOutbox.java:53-59` — *"stamped by `claimPendingBatch` with **the tick's own claim
  instant**"*
- `RadarCompositeDlqEntry.java:58-61` — mirrors the above by reference, so it inherits the error
- `VideoDeletionOutboxRepository.java:17-18` and `:21`; `RadarCompositeDlqRepository.java:16-18`
- `VideoDeletionOutboxProcessor.java:102` — *"this run's own claim instant, stamped by
  `claimPendingBatch`"*

**Fix:** add `VideoDeletionOutbox.java` and `RadarCompositeDlqEntry.java` to the File List and name
all five sites in Task 4. Given how heavily this codebase leans on cross-referencing Javadoc, a
stale "the tick's own claim instant" is actively misleading for the next reader of this subsystem.

### F-10 (Medium, AC2) `SET LOCAL`'s blast radius depends on a caller property the story never states

`recalculateComposite` is plain `@Transactional` (`:78`) — `Propagation.REQUIRED`. `SET LOCAL` binds
to the **enclosing** transaction, so with a future transactional caller the lock timeout would
outlive the method and apply to the rest of the caller's transaction, defeating the
"narrowest-blast-radius" decision.

Today this is safe — verified both callers: `onRadarEntrySubmitted` is `@Async` (no inherited
transaction, `:58`) and `RadarCompositeDlqProcessor.processRow` (`:240-253`) is not transactional.

**Fix:** state the constraint in the Javadoc Task 7 adds, or enforce it with
`Propagation.REQUIRES_NEW`. Silence here is how the invariant gets broken by an unrelated change.

### F-11 (Medium, AC5) The closeout will leave a ledger inconsistency it does not plan to reconcile

The same bug class AC2 fixes — a native DML statement's implicit row-lock wait with no
`NOWAIT`/`lock_timeout` — is already `[DECIDED: accepted risk — skillars-deferred-123]` for
`AdminCoachEnforcementService.deleteStrike`'s bulk `@Modifying` delete
(`deferred-work.md:2423-2438`). That bullet's own rationale says it *"would need revisiting if a
future change to `deleteStrike` or its callees makes the winning transaction's own work unbounded."*

After AC5, the ledger will fix one instance with `SET LOCAL lock_timeout` and keep the structurally
identical sibling as accepted risk, with no note connecting them. AC5 Task 5's grep list
(`RadarCompositeDlqProcessor`, `VideoDeletionOutboxProcessor`, `RadarCompositeDlqRepository`,
`VideoDeletionOutboxRepository`, `RadarCompositeCalculationService`, `ShedLockConfig`,
`boot/axios.js`, `ConfigBounds`) does not include `CoachReliabilityStrikeRepository` or
`AdminCoachEnforcementService`, so the sweep will not surface it.

**Fix:** add both to Task 5's grep list, and have Task 4's narrative state why the radar case warranted
a bound while the strike delete's `[DECIDED]` still stands (the GDPR-erasure transaction is genuinely
unbounded relative to row counts; the strike delete's winner is itself lock-retry-bounded).

---

## 4. Lower-severity findings

**F-12 (AC2) — `GdprErasureService` citation is wrong on the method name.** The story names
`eraseRadarAndDevelopmentData` "(or its equivalent method — `GdprErasureService.java:196-203`)". No
such method exists. Actual: `erase` carries the
`@Transactional(propagation = Propagation.REQUIRES_NEW)` at `:75`; the private
`deletePlayerDevelopmentData` at `:194` holds the two deletes at **`:201-202`**. The substance is
confirmed: the class contains no `findByIdForUpdate` and no `lockRetryer`, so it genuinely does not
take the `PlayerProfile` lock that `recalculateComposite` serialises on. Fix the name and line range.

**F-13 (AC2) — the `insertBaselineIfAbsent` lock claim is overstated.** AC2 says *"An
`INSERT ... ON CONFLICT` takes an implicit row lock on the conflicting row exactly like a plain
`UPDATE`"*, applied to both upserts. True for `upsertComposite`'s `DO UPDATE`
(`PlayerRadarCompositeRepository.java:20-24`). **Not** true for `insertBaselineIfAbsent`'s
`ON CONFLICT … DO NOTHING` (`PlayerRadarBaselineRepository.java:23`): against an
*already-committed* conflicting row it takes no row lock and skips; it waits only on a *concurrent
uncommitted* insert or delete of the same key. The GDPR scenario is an uncommitted DELETE, so both
statements do wait and the conclusion stands — but the stated mechanism is wrong for half the claim,
and F-2's fixture design depends on getting this right.

**F-14 (AC1) — internally contradicted "only comparison" claim.** AC1 says `resetStaleClaimed`'s
`claimed_at < :deadline` is *"the only absolute cross-instance time comparison either processor's
claim/reset design makes."* The story's own Residual paragraph 40 lines later concedes otherwise:
`next_retry_at <= :now` in the *same* `claimPendingBatch` statement is also absolute and
cross-instance, since `next_retry_at` is written from `Instant.now()`
(`RadarCompositeDlqProcessor.java:273` and the video sibling). Soften the claim to "the only one this
fix addresses" so the two paragraphs agree.

**F-15 (AC3) — `locked_by` column width is not unbounded.** `main.shedlock.locked_by` is
`character varying(255)` (`V138__baseline_schema.sql:1206`). `hostname + "-" + UUID` (37 chars of
suffix) is safe for normal and Docker-derived hostnames, but a hostname longer than 218 chars would
make the acquire `INSERT`/`UPDATE` fail with SQLState `22001`, breaking **every** `@SchedulerLock`ed
job. A one-line truncation of the hostname portion removes the failure mode entirely; worth doing
given the blast radius.

**F-16 (AC1) — three in-repo precedents for the core change go uncited.** AC1 and its Task 5 read as
though DB-time stamping is novel here. It is not:
`V144__outbox_dlq_claimed_at.sql:46` and `:50` already do `SET claimed_at = now()` on these exact two
tables; `PlayerRadarCompositeRepository.java:19` and `PlayerRadarBaselineRepository.java:22` already
use `NOW()` in native inserts; and `AbstractIntegrationTest.releaseSchedulerLock` (`:144`) already
uses `now() - interval '1 minute'` in a **test** — which is precisely the fixture shape Task 5 asks
the dev to invent. Citing these de-risks the AC and shortens the task.

**F-17 (AC2) — the ideal test precedent exists and was missed.** Task 5 and the File List say
"locate the existing test class first; confirm exact name at implementation time", and the Dev Notes
point at `AdminCoachEnforcementIsolationRuntimeIT` (a different module).
`RadarCompositeCalculationServiceConcurrencyIT` already exists in the exact package
(`…platform.development.service`) and is the precise shape AC2 needs: a second connection holding
`SELECT id FROM main.player_profiles WHERE id = ? FOR UPDATE` (`:65-67`) across an `ExecutorService`
(`:60`), `LOCK_HOLD_MILLIS = 1200` (`:38`), and a wall-clock ordering assertion (`:106-109`). It also
makes AC2 Task 6 concretely answerable — its one test is the "existing deferred-77
concurrent-recalculation test" Task 6 hedges about ("if any"). For a story that states every citation
was "re-read directly", this is a verification gap.

**F-18 (AC1 Task 3) — SQL detail worth pinning.** `now() - (:staleWindowSeconds * interval '1 second')`
works, but relies on an implicit `bigint → double precision` cast to reach Postgres's
`double precision * interval` operator. `now() - make_interval(secs => :staleWindowSeconds)` is
unambiguous. Also worth asserting positively: the new predicate stays index-friendly (`now()` is
`STABLE`, the parameter is stable), so there is **no plan regression** relative to
`V144__outbox_dlq_claimed_at.sql:30-36`'s own index-coverage note — which the story does not address
even though V144 flagged the predicate's index coverage as an accepted risk.

**F-19 (AC2 Task 3) — the real implementation trap is not the one the task names.** Task 3 asks to
"confirm Postgres's accepted syntax for the value, e.g. milliseconds as a bare integer vs. an
interval-string literal". The actual trap is that **`SET` accepts no bind parameters in Postgres**, so
`SET LOCAL lock_timeout = ?` is impossible — the value must be inlined into the SQL string (repo
precedent: `V144:37` `SET lock_timeout = '5s'`;
`docs/deployment/migration-conventions.md:213` prescribes the same form). The parameter-safe
alternative, which Task 3 should name, is `SELECT set_config('lock_timeout', :value, true)` — the
third argument makes it transaction-local, identical to `SET LOCAL`. Inlining a `ConfigService`-bounded
`long` is safe, but the task should say so rather than leave a string-concatenated `SET` to be
discovered.

**F-20 (AC2 Task 4) — only one of two possible exceptions is covered.** Task 4 asks to confirm the
exception for a `lock_timeout` expiry (`55P03`). The identified GDPR conflict can also produce a
genuine **deadlock**, because the two paths take the same two tables in **opposite order**: `erase`
deletes baselines (`:201`) then composites (`:202`); `recalculateComposite` writes composites
(`:130`) then baselines (`:131`). That surfaces as SQLState `40P01` and a different Spring exception.
Also worth stating: the configured timeout must stay **above** `deadlock_timeout` (default 1s) or the
detector never runs and a real deadlock is misreported as a lock timeout — the story's
"single-digit seconds" default satisfies this, but only by accident.

**F-21 (Provenance) — the three-category scoping claim is not exhaustive.** The story asserts the
remaining untagged ledger items are *"either genuinely too large for this bundle …, messaging-module-
scoped edge cases unrelated to this story's subsystems, or `deploy-*` items already closed."*
Counterexample: `## Deferred from: code review of story-115 (2026-09-16)`
(`deferred-work.md:2324-2326`) holds an untagged `@SchedulerLock PT12H` sizing bullet for
`VideoLifecycleScheduler` — scheduler locking, i.e. AC3's own subsystem, and none of the three
categories. It reads as decided in prose ("no code change needed, documented in AC2") but carries no
`[DECIDED]` tag, unlike the two genuinely-tagged items at `:2377` and `:2423`. Either widen the
category list or tag that bullet.

**F-22 (File List / AC5) — omissions.** Dev Notes says AC2 touches
`PlayerRadarBaselineRepository`, but the File List lists only `PlayerRadarCompositeRepository`; AC5
Task 5's grep list omits both `PlayerRadarBaselineRepository` and `sessionRedirect.js`. Add a
migration entry if F-1 resolves that way.

**F-23 (AC1) — unflagged divergence from the ledger's own number.** The ledger bullet says the hazard
bites *"at a skew of 8 minutes or more"* (`deferred-work.md:2620`); the story says
*"more than … 5 minutes"*. The story's 5 is the defensible figure (15m `STALE_CLAIM_WINDOW` − `PT10M`
`lockAtMostFor` on radar; 20m − `PT15M` on video — both verified), but the divergence is silent, so a
reader reconciling the two documents cannot tell which supersedes. Note the correction explicitly.

**F-24 (AC2) — self-referential wording.** "bounded well below both `MAX_RUN_DURATION` and the AC2
stale-window margin" — inside AC2. It means AC1's / the processors' stale-window margin.

**F-25 (AC2) — config read placement.** `ConfigService` is TTL-cached (`:245-255`), but a cache
expiry triggers `configRepository.findAll()` (`:255`). As specified, that round trip lands **inside**
the transaction already holding the `player_profiles` pessimistic lock. Read the value before
`findByIdForUpdate` and pass it down.

---

## 5. Checked and cleared — do not re-raise

Recorded so these are not re-litigated at dev-story time.

- **`.withLockedByValue(String)` exists in the pinned version.** The story hedges ("confirm
  accessibility"). Verified in `shedlock-sql-support-7.10.1`: it is on
  `SqlConfiguration$SqlConfigurationBuilder`, inherited by
  `JdbcTemplateLockProvider$Configuration$Builder`. **Available.**
- **`net.javacrumbs.shedlock.support.Utils.getHostname()` is public API in 7.10.1**, not internal —
  verified via `javap` on `shedlock-core-7.10.1`. The story's alternative
  (`InetAddress.getLocalHost()` + `UnknownHostException` fallback) is unnecessary; `Utils` already
  resolves the hostname into a static field.
- **ShedLock's default `locked_by` is `Utils.getHostname()`** — confirmed in
  `SqlConfigurationBuilder`'s constructor bytecode. Story's claim exact.
- **ShedLock's unlock predicate really has no time component.**
  `SqlStatementsSource.getUnlockStatement()` concatenates exactly `tableName`, `lockUntil`, `name`,
  `lockedBy` — i.e. `UPDATE … SET lock_until = :unlockTime WHERE name = :name AND locked_by = :lockedBy`.
  AC3's core mechanism claim is **confirmed from bytecode**, and the same-host unlock-collision it
  describes is genuinely reachable.
- **`claimed_at` is `timestamp with time zone`** (`V144:40`, `:43`), so `now()` (which returns
  `timestamptz`) introduces no timezone coercion. Combined with
  `application.yaml:109` `connection-init-sql: "SET TIME ZONE 'UTC'"`, AC1's stamp change is
  type-safe. No hazard.
- **AC1 breaks zero existing assertions.** Every `getClaimedAt()` assertion in the suite is
  `isNull()` or `isNotNull()` — `VideoDeletionOutboxProcessorIT:86`, `:126`, `:151`, `:234`, `:324`,
  `:352` and `RadarCompositeDlqRepositoryIT:64`, `:87`. Nothing asserts `claimed_at` equals a
  Java-computed `Instant`. AC1 Task 6's own self-assessment is correct.
- **The `resetStaleClaimed` signature change has exactly 6 call sites**, all inside the story's File
  List: `RadarCompositeDlqProcessor:161`, `VideoDeletionOutboxProcessor:160`,
  `RadarCompositeDlqRepositoryIT:82`, `:101`, `VideoDeletionOutboxProcessorIT:347`, `:376`. The four
  test sites' semantics survive the rewrite (seeded `claimed_at` is `-30m` / `now`, decisive under
  both the old 10/20-minute literals and the new `STALE_CLAIM_WINDOW.toSeconds()`), so no hidden
  behavioural break. Worth enumerating them in Task 5 regardless.
- **`claimPendingBatch` really is its own transaction**, so Postgres's transaction-scoped `now()`
  behaves as a single claim-instant stamp. `process()` carries no `@Transactional`
  (`RadarCompositeDlqProcessor:154`, `VideoDeletionOutboxProcessor:149`), `AbstractIntegrationTest`
  is **not** `@Transactional`, and `application.yaml:108` sets `auto-commit: false`. AC1's paragraph
  on this is accurate in every particular.
- **`Duration.toSeconds()` is available** — `pom.xml:30` pins `java.version` 17; the method is Java 9+.
- **`#q-app/wrappers` resolves under Vitest.** It is in `.quasar/tsconfig.json`'s `paths` (line 69),
  which `vite-tsconfig-paths` reads (`vitest.config.js` plugin list). Not an obstacle to AC4's spec —
  the real obstacles are in F-7.
- **`npm run test:unit` exists** (`package.json:13` → `vitest run`), and the `frontend-tests` PR-label
  convention is real (documented in `vitest.config.js`'s header). Story's claims exact.
- **`AbstractIntegrationTest.releaseSchedulerLock` is unaffected by AC3** — it keys on `name` only
  (`:144`), not `locked_by`, and no test anywhere asserts `locked_by`. AC3 is genuinely low-regression.
- **`SET LOCAL` needs an active transaction and gets one.** `recalculateComposite` is `@Transactional`
  (`:78`) and `auto-commit: false` means the connection is not autocommitting.
  `docs/deployment/migration-conventions.md:169-174` independently documents the no-enclosing-transaction
  no-op hazard; it does not apply here.
- **The `SET LOCAL` placement does not disturb the NOWAIT path.** Placed after
  `findByIdForUpdate` + `entityManager.refresh` (`:84-86`), it cannot interact with
  `PessimisticLockRetryer`'s savepoint/retry loop (`:125-166`), and `lock_timeout` does not apply to
  the plain MVCC `SELECT`s at `:88-90`. Effectively it bounds only the two upserts — as intended.
- **The GDPR hang premise survives the deadlock-detector objection for the scenario the story
  describes.** `erase` writes `main."user"` (`:99`) and `coach_profiles` (`:102-107`) but never
  locks or updates `player_profiles`, so there is no circular wait with `recalculateComposite`'s
  `player_profiles` lock, and Postgres's detector does not bail the waiter out. The
  "erasure transaction is itself slow" framing is correct. (A separate genuine cycle does exist on
  the composites/baselines ordering — see F-20.)
- **`LoginPage.vue` consumes both params safely.** `:16` reads `route.query.expired === 'true'`; the
  `redirect` consumer at `:161-165` carries an open-redirect guard
  (`startsWith('/') && !startsWith('//')`). Making `redirect` live is safe, and AC4 silently *fixes*
  it as a bonus: today `window.location.pathname` is always `/` in hash mode, so `redirect` is
  useless, whereas `pushLoginOrHardNavigate` uses `router.currentRoute.value.fullPath`
  (`sessionRedirect.js:62`). Worth stating in AC4 and asserting in the new spec.
- **No circular-import risk for AC4.** `sessionRedirect.js` imports only `vue-router`; nothing under
  `src/router/` imports `boot/axios`.
- **The deferred-125 code-review section really is the freshest** — it is the final section of
  `deferred-work.md` (line 2608 of 2652). The Provenance claim is accurate, and the four bullets
  match AC1-AC4 one-to-one. The two nearby scheduler/lock items in AC1-AC3's subsystems
  (`:2377` `ModerationSlaMonitorService` no-`@SchedulerLock`; `:2423` strike-DELETE wait) are both
  correctly `[DECIDED]`-tagged already, so excluding them from scope is right — see F-11 for the one
  reconciliation still owed, and F-21 for the one untagged item.
- **CI context ceiling is 44** (`pr-build.yml:72`, `assert-context-count.sh:125`/`:135`). Both AC1
  test homes (`RadarCompositeDlqRepositoryIT`, `VideoDeletionOutboxProcessorIT`) and AC3's
  (`ShedLockConfigIT`) already exist and extend `AbstractIntegrationTest` with no extra annotations,
  so they fork no new context. Only AC2's new test is a candidate, and reusing
  `RadarCompositeCalculationServiceConcurrencyIT` (F-17) avoids that too.

---

## 6. Recommendation

`ready-for-dev` is not yet safe. **F-1** through **F-5** change what gets built or would ship a
broken artefact (a boot-blocking config key, a vacuous test, a user-visible navigation regression);
**F-8** blocks AC3's task list from being executable as written. Fold in F-6 through F-11 while
editing, and fix the F-12 citation.

Ordering suggestion, unchanged from the story's own "separate commits" convention: **AC3** first (now
fully de-risked — F-8 is the only edit needed, and every open question in it is answered in §5), then
**AC1** (also de-risked; F-9/F-14/F-16/F-18 are edits, not investigations), then **AC4** (needs the
F-3 decision before code), then **AC2** last (needs F-1's migration decision, F-2's fixture design
and F-4's overload choice resolved first — it is the only AC with real unknowns remaining).
