# Senior-Dev Audit — `skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes.md`

**Reviewed:** 2026-09-21
**Story status at review:** `ready-for-dev`
**Repo state:** branch `story/deferred-125-outbox-radar-session-fixes`, HEAD `1f11daca` (story-creation
commit); story line citations were made against `77036728` (master, post-PR #212).
**Method:** every file, line citation, constraint, precedent and test class named in the story was
opened and checked against the actual source. Library behaviour claims were verified against the
installed package, not from memory. Findings below are only those that survived that check; a
"Verified correct" section at the end records what held up, so it is not re-litigated.

**Headline:** the story's *structure* is sound — AC1's correction of the ledger's `try`/`finally` to
`try`/`catch` is right, AC2's missing-assertion finding is real and its citations are cleaner than the
ledger's, and AC5's bullet mapping is accurate. But three load-bearing premises are false against the
actual source (AC3's Vue Router 4 semantics, AC3's "no existing router-mocking spec", AC4's "four
migrations"), and AC4 as written would break two existing lint tests and mandate a form that is
actively unsafe for this repo's own documented non-transactional migration pattern.

---

## Severity summary

| # | AC | Severity | Finding |
|---|---|---|---|
| F1 | AC3 | **High** | Vue Router 4 **resolves** (does not reject) on all three failure modes the story names — a `.catch()`-only fix plus the prescribed test would ship green and fix nothing |
| F2 | AC3 | **High** | "No existing spec mocks `useRouter()` … this will be the first" is false — three specs already do, one of them a near-exact template |
| F3 | AC4 | **High** | "All **four** … migrations" — there are **ten** (V140–V150); the four-item list was copied from the ledger, not re-verified |
| F4 | AC4 | **High** | `SET LOCAL` is a **no-op outside a transaction block**; this repo documents an `executeInTransaction=false` sidecar pattern, where the mandated form would silently remove all protection |
| F5 | AC4 | **High** | A new rule bound at ~V150 flags **12 existing `valid/` lint fixtures**, breaking `validFixtures_areClean` |
| F6 | AC4 | Medium | Adding a `Rule` enum member breaks `invalidFixtures_triggerEveryRule` until an `invalid/` fixture is added |
| F7 | AC4 | Medium | A third baseline is an API change through 3 `lint()` overloads + `lintFile` + every fixture call site |
| F8 | AC4 | Medium | Switching to `SET LOCAL` creates a new **false negative in the existing `MISSING_LOCK_TIMEOUT` rule** |
| F9 | AC4 | Medium | AC4's own rationale ("immutable once applied") contradicts this story's out-of-scope note and `deferred-112`'s precedent |
| F10 | AC4 | Medium | Doc update scoped to item 7 only; five other places in the same doc prescribe plain `SET` |
| F11 | AC3 | Medium | Same bug, two other live call sites (`useSession.js:110`, `MainLayout.vue:374`) — AC3 fixes 1 of 3 |
| F12 | AC3 | Medium | Existing hard-nav precedent missed (`boot/axios.js:163-167`); the fallback URL **must** `encodeURIComponent` the redirect path |
| F13 | AC3 | Medium | `sessionManager.js:231` is pinned by an existing characterization spec **and** a recorded owner decision — neither is mentioned |
| F14 | AC2 | Medium | The "rather than widen the window" reasoning lives in `MAX_RUN_DURATION`'s Javadoc, not the one task 3 targets |
| F15 | AC2 | Medium | Three stale cross-references left out of scope (radar test Javadoc, video sibling Javadoc, `scheduler-lock-tuning.md:77`) |
| F16 | AC1 | Medium | The fix's efficacy is overstated for the exact failure modes named |
| F17 | AC1 | Medium | "no-throw-out-of-`process()` convention" does not exist for this phase; "log and return" would be a silent behaviour change |
| F18 | AC5 | Medium | Bullet 4's own ledger text carries the four-migration error |
| F19 | — | Medium | The Provenance "independently re-verified, not merely re-read" claim is falsified for two of the five items |
| F20 | AC1 | Low | Dev Notes cite the wrong existing spy seam; the one AC1 needs already exists |
| F21 | AC1 | Low | Test shape: `runId` is generated inside `process()` — needs an `ArgumentCaptor`, or the test is near-vacuous |
| F22 | AC2 | Low | Unstated trade-off: crash-recovery latency goes 10m → 15m |
| F23 | AC3 | Low | Re-entrancy: `session:expired` can fire again via the axios interceptor, repeating the hard nav |
| F24 | AC5 | Low | The out-of-scope section has **two** bullets; the story names only one |
| F25 | AC4 | Low | `MigrationLint.java` is filed under "Production code"; it lives in `src/test` |

---

## AC1 — Outbox/DLQ claim-phase isolation

### F16 (Medium) — the fix does not close the failure modes the story names

> "If the very next call, `findClaimedBatch`, throws — a connection reset, a statement timeout, a
> pool-exhaustion `CannotGetJdbcConnectionException` …"

Two of those three (connection reset, pool exhaustion) will also break the `releaseClaimed(runId)` call
the story puts in the catch block — it is another JDBC round trip on the same broken connection/pool.
The fix genuinely helps only where `findClaimedBatch` fails for a reason that leaves the connection
usable (a statement timeout on the `SELECT`, a row-mapping failure, a bad `LIMIT` bind). That is still
worth doing, but the story presents it as closing the gap generally.

**Required task addition:** the catch must guard its own `releaseClaimed` call, so a secondary failure
does not mask the original. `e.addSuppressed(secondary)` before rethrowing, or a nested
`try`/`catch` logging at ERROR — this class already has that exact shape for `handleFailure`'s inner
guard (`VideoDeletionOutboxProcessor.java:203-209`, `RadarCompositeDlqProcessor.java:158-164`), so
follow it rather than inventing a new one.

### F17 (Medium) — the cited precedent for "log and return" does not exist

> "then rethrows (or logs and returns, matching this method's existing no-throw-out-of-`process()`
> convention — check `handleFailure`'s own return behaviour for the precedent)"

There is no such convention for this phase. Verified: `process()`'s claim phase
(`VideoDeletionOutboxProcessor.java:156-159`, `RadarCompositeDlqProcessor.java:125-127`) has no guard
at all today, so a throw there *does* escape `process()`. And `handleFailure` does not swallow
anything either — it is the *loop* (`:198-210` / `:153-165`) that catches, which is a different
mechanism at a different level.

This matters: choosing "log and return" would be a **behaviour change**, not a neutral style choice.
Spring's scheduled-task error handler currently sees claim-phase DB failures; after "log and return"
it would not, and the only signal left is a WARN line. Recommend the task simply prescribes **rethrow**
and drops the alternative.

### F20 (Low) — Dev Notes cite the wrong seam; the one AC1 needs already exists

Dev Notes say the new tests "reuse the *same* test classes and mock shapes skillars-deferred-124 AC2
already introduced (`VideoDeletionOutboxProcessorIT`'s existing `@MockitoSpyBean
DrillVideoRefRepository` …)". The seam AC1 actually needs is the *other* one added by that story:

```
src/test/java/.../VideoDeletionOutboxProcessorIT.java:57
    @MockitoSpyBean VideoDeletionOutboxRepository outboxRepository;
```

That is the exact bean whose `findClaimedBatch` must be made to throw and whose `releaseClaimed` must
be verified. The Dev Notes' conclusion (no new Spring context fork needed) is still correct, and
strengthened by this.

### F21 (Low) — test shape: `runId` is generated inside `process()`

Task 4 says "assert `releaseClaimed` was invoked with the run's `runId`". The `runId` is
`UUID.randomUUID()` inside the method (`VideoDeletionOutboxProcessor.java:153`,
`RadarCompositeDlqProcessor.java:124`), so the test has no handle on it. It must capture it — an
`ArgumentCaptor<UUID>` on `claimPendingBatch`'s second argument, then assert the captured value is what
reaches `releaseClaimed`. Writing `verify(repo).releaseClaimed(any())` instead would still pass the
prescribed mutation check while proving almost nothing. Worth stating explicitly in the task.

### Verified correct for AC1

- `VideoDeletionOutboxProcessor.java:156-159` and `RadarCompositeDlqProcessor.java:125-127` — both
  accurate. The ledger's own citations (`:147-150`, `:121-123`) had drifted; the story re-derived them.
- The correction of the ledger's "`try`/`finally`" to `try`/`catch` is right, and the stated reason
  (a `finally` would release a claim it just took, on the happy path, and race a concurrent
  re-claim) is correct.
- "Do **not** wrap `resetStaleClaimed`" is correct — no claim exists under this `runId` at that point.
- `releaseClaimed` is scoped `WHERE status = 'CLAIMED' AND claimed_by = :runId` on both repositories
  (`VideoDeletionOutboxRepository.java:90-95`, `RadarCompositeDlqRepository.java:68-73`), so a
  claim-phase release provably cannot touch a concurrent run's rows.
- `VideoDeletionOutboxRepository.java:90-104` does contain the "`process()` is not `@Transactional`"
  statement (line 99) — citation holds.

---

## AC2 — Radar stale-window margin

### F14 (Medium) — task 3 rewrites the wrong Javadoc (and mislabels the one it cites)

The story's finding text says:

> "`RadarCompositeDlqProcessor`'s own **class Javadoc** (`:55-77`) already documents, at length, a
> **prior, deliberate decision** … to leave the 10-minute/`PT10M` equality unchanged"

`:55-77` is the **`MAX_RUN_DURATION` field Javadoc**, not the class Javadoc (which is the `//` block at
`:20-47`). And it is where the load-bearing sentence lives — line 71: *"Fixed at the source rather than
by widening the window."* Yet task 3 directs rewriting only `STALE_CLAIM_WINDOW`'s Javadoc (`:80-88`,
story says `:80-89`).

If only `:80-88` is rewritten, `:71` still asserts the opposite of what AC2 just did. **Both field
Javadocs must be revised**, and the File List / task text should say so.

### F15 (Medium) — three stale cross-references left out of scope

Widening radar's window to 15 minutes invalidates text in three places AC2 does not list:

1. `src/test/.../RadarCompositeDlqProcessorTest.java:261-275` — the Javadoc on the very test AC2
   modifies states *"Rather than widen the window, `process()` now self-terminates …"* and *"That makes
   `MAX_RUN_DURATION < lockAtMostFor` the load-bearing inequality here."* Directly contradicted.
   AC2 task 4 only adds an assertion; it does not touch this Javadoc.
2. `src/main/.../VideoDeletionOutboxProcessor.java:44-47` — describes *"`RadarCompositeDlqProcessor`'s
   equality between its own 10-minute window and its `PT10M` lock."* Becomes false.
   `VideoDeletionOutboxProcessor.java` appears in the File List for **AC1 only**.
3. `docs/deployment/scheduler-lock-tuning.md:77` — the radar row reads *"load-bearing (see that class's
   `MAX_RUN_DURATION`)"*. After AC2 it should mirror the video row directly above it at `:76`:
   *"must stay strictly under `STALE_CLAIM_WINDOW`"*. This doc is not in the File List at all.

### F22 (Low) — unstated trade-off

Widening to 15 minutes raises radar's **crash-recovery latency** from 10 to 15 minutes — how long a
genuinely dead instance's rows sit `CLAIMED` before the sweep frees them. The video sibling documents
exactly this trade-off explicitly (`VideoDeletionOutboxProcessor.java:118-120`: *"Crash-recovery
latency … is 20 minutes — immaterial for a deletion outbox polled every 60 seconds"*). AC2's new
Javadoc should carry the equivalent sentence, or a future reader will read the widening as pure gain.

### Verified correct for AC2

- `STALE_CLAIM_WINDOW` at `:89` = `Duration.ofMinutes(10)` ✓; `lockAtMostFor = "PT10M"` at `:116` ✓;
  `MAX_RUN_DURATION` at `:78` = 8 minutes ✓; the 2-minute margin arithmetic ✓.
- `RadarCompositeDlqProcessorTest.runtimeBudget_staysStrictlyInsideLock` at `:277-290` ✓, and it
  genuinely asserts only `maxRun < lockAtMostFor` and `maxRun < staleWindow` — the
  `lockAtMostFor < staleWindow` assertion is genuinely absent.
- The claim that the video sibling's coverage already treats that inequality as load-bearing is
  **true**: `VideoDeletionOutboxProcessorSchedulerLockTest.runtimeBudget_staysStrictlyInsideLockAndStaleWindow`
  (`:70-82`) asserts `assertThat(lockAtMostFor).isLessThan(staleWindow)`. The `.as(...)` wording
  task 4 prescribes is accurate.
- The duplicate-`recalculateComposite` mechanism is real: `resetStaleClaimed` is
  `WHERE status='CLAIMED' AND (claimed_at IS NULL OR claimed_at < :deadline)` with
  `deadline = now - STALE_CLAIM_WINDOW`, so at equality the very next tick after lock expiry frees a
  still-running run's rows.
- 15 minutes gives the same **5-minute gap** as the video sibling (15m → 20m) ✓.
- The `QuotaReservationTimeoutService` precedent is real: `MAX_RUN_DURATION = Duration.ofMinutes(8)`
  (`:23`) under `lockAtMostFor = "PT10M"` (`:32`).
- Task 7's concern is satisfied: nothing else hardcodes the 10-minute constant.
  `RadarCompositeDlqRepositoryIT:82,101` passes `Instant.now().minus(10, ChronoUnit.MINUTES)` as an
  explicit *deadline argument* to the repository method — it never reads the processor constant, so it
  is unaffected by the widening.

---

## AC3 — Session-expiry redirect

### F1 (High) — the Vue Router 4 premise is false; the prescribed fix and test would both pass while fixing nothing

The story states:

> "Vue Router 4's `router.push` returns a `Promise` that **rejects** on a failed navigation (a guard
> cancels it, a duplicate-navigation, or a concurrently in-flight navigation superseding it — all real
> Vue Router 4 outcomes, not hypothetical)."

Installed version is **vue-router 4.6.4** (`src/frontend/node_modules/vue-router/package.json`).
Verified against the shipped source:

```js
// node_modules/vue-router/dist/vue-router.mjs:1304  (pushWithRedirect)
return (failure ? Promise.resolve(failure) : navigate(toLocation, from))
  .catch((error) => isNavigationFailure(error)
      ? (isNavigationFailure(error, ErrorTypes.NAVIGATION_GUARD_REDIRECT) ? error : markAsReady(error))
      : triggerError(error, toLocation, from))
  .then((failure) => { … return failure })   // :1318 — resolved value
```

Navigation failures are **caught and returned as a resolved value**. `markAsReady(err)` (`:1456-1464`)
returns the error; the `.then` returns it. The only rejecting path is `triggerError` (`:1440-1448`),
reached exclusively when a guard throws something that is **not** a `NavigationFailure`.

So, concretely, in Vue Router 4:

| Outcome | Story says | Actually |
|---|---|---|
| Guard returns `false` (`NAVIGATION_ABORTED`) | rejects | **resolves** with a `NavigationFailure` |
| Same-route push (`NAVIGATION_DUPLICATED`, `:1298`) | rejects | **resolves** (short-circuited before `navigate`) |
| Superseded by concurrent navigation (`NAVIGATION_CANCELLED`) | rejects | **resolves** |
| Guard *throws* an arbitrary error | not mentioned | **rejects** (`triggerError`) |

All three failure modes the story names resolve. The one that rejects is the one it does not name.

Worse, this compounds: **task 3 prescribes "a mocked router whose `push` rejects"**. A `.catch()`-only
implementation passes that test and the mutation check, ships green, and still leaves every real-world
failure mode unhandled. This is a false-confidence test, not a guard.

**Required changes:**
- The implementation must handle **both**: inspect the resolved value (`isNavigationFailure(result)`,
  or simply a truthy return) *and* `.catch()` the rejection. Given the mixed contract, `async` +
  `try`/`catch` around `const failure = await router.push(...)` is clearly the cleaner of the two
  options the story offers.
- The test suite needs a **third** case: `push` **resolves** with a `NavigationFailure`-shaped value →
  fallback fires. Without it the dominant failure mode is untested.

*Provenance note:* the same wrong claim is in the ledger at `deferred-work.md:1407`
(*"Vue Router 4 rejects on an aborted/redirected navigation"*), in a bullet that also says *"the abort
path is unverified."* The story carried it over verbatim rather than checking it. Worth correcting the
ledger bullet in AC5 rather than just deleting it.

### F2 (High) — "no existing spec mocks `useRouter()` … this will be the first" is false

Stated twice — in AC3 task 3 (*"first component-mount test in this frontend suite to touch
`useRouter()` — no existing spec mocks `vue-router`, so this establishes the pattern"*) and in Dev
Notes (*"Frontend test infrastructure gotcha (AC3)"*, which further advises locating conventions
"before writing new test scaffolding from scratch").

Three existing specs already do exactly this, with a real memory-history router installed as a plugin
alongside `createTestingPinia`:

| Spec | Evidence |
|---|---|
| `src/layouts/__tests__/MainLayoutSpec.js` | `:20` imports `createRouter, createMemoryHistory`; `:46-52` builds the router with `/` + `/login` stubs; `:61` `global: { plugins: [pinia, router] }`; `:96` `vi.spyOn(router, 'push')` |
| `src/composables/__tests__/useSessionSpec.js` | `:20, :38-53` — same shape, mounts a host component because `useSession()` calls `useRouter()` |
| `src/pages/parent/__tests__/BookingRequestPageSpec.js` | `:28, :66` |

`MainLayoutSpec.js` is a **near-exact template** for AC3: it asserts
`authStore.logout → resetSelfPlayerId → destroySession → router.push('/login')` ordering on a component
whose `handleLogout` is structurally the same function as `App.vue`'s `handleSessionExpired`.

Other corrections to the same Dev Note:
- **Naming/location:** the convention is `src/**/__tests__/<Name>Spec.js`, not `App.spec.js`. Both
  patterns are in `vitest.config.mjs`'s `include`, but every existing spec uses the former.
- **Environment is `happy-dom`**, not jsdom (`vitest.config.mjs`, `test.environment`). Relevant because
  the hard-nav fallback needs `window.location` stubbed; plan for happy-dom's semantics.
- **A shared setup helper does exist** and the question the Dev Note asks is already answered:
  `test/vitest/setup-file.js` installs Quasar + vue-i18n globally and states explicitly that
  *"Pinia is intentionally NOT installed globally here. Specs that need a store opt in with
  `createTestingPinia()`."*

Net: AC3 task 3's scaffolding work is ~30 minutes of copying `MainLayoutSpec.js`, not a new pattern.

### F11 (Medium) — the same bug exists at two other live call sites, both out of scope

`App.vue:36-39` is one of three places that tear down the session and then fire an unguarded
`router.push`:

| Call site | Teardown before the push | Push |
|---|---|---|
| `src/App.vue:31-39` | cookie cleared, `authStore.logout()`, `resetSelfPlayerId()`, `cleanup()` | `:36-39` (in scope) |
| `src/composables/useSession.js:75-110` | `stopSessionMonitoring()`, `user` + `rint` cookies cleared (twice), `authStore.logout()` raced, `resetSelfPlayerId()`, `cleanup()` | `:110` **not in scope** |
| `src/layouts/MainLayout.vue:354-374` | `logout → resetSelfPlayerId → destroySession → deleteUserCookie` | `:374` **not in scope** |

All three leave the user on an authenticated-looking page with no session and no monitoring if the
navigation does not land. `useSession.handleLogout` is arguably worse — it is `async` and its caller
awaits it, so it *could* await the push today at zero cost.

Either widen AC3 to all three (they are ~3 lines each and share a helper), or state explicitly in the
story that the other two are knowingly deferred and add them to the ledger. Silently fixing 1 of 3
identical instances is how this exact item stayed open since 2026-09-02.

### F12 (Medium) — the hard-nav precedent already exists, and the URL must be encoded

AC3 treats the hard navigation as new ground. It is not — `src/boot/axios.js:163-167` already does it,
for the same event, to the same target:

```js
const currentPath = window.location.pathname + window.location.search
const redirectUrl = `/login?redirect=${encodeURIComponent(currentPath)}&expired=true`
window.location.href = redirectUrl
```

Follow that shape. The `encodeURIComponent` is **not optional**: `App.vue:35` builds the same
`pathname + search` string but passes it through `router.push`'s `query` object, which encodes it for
you. A hand-built fallback URL that skips the encoding breaks the moment the current path carries its
own query string — `/coach/x?a=1&b=2` would arrive at `LoginPage.vue:161-164` as
`route.query.redirect === '/coach/x'` with `a`/`b` as sibling params, and `expired` possibly lost.
This is a concrete, easy-to-hit bug in the prescribed fix; call it out in the task.

(The existing axios interceptor is also the reason the story's "stuck until their next API call
happens to 401" framing is accurate — that interceptor is today's only backstop.)

### F13 (Medium) — `sessionManager.js:231` is pinned by a spec *and* a recorded owner decision

The story calls `startSessionMonitoring`'s early return *"the other half of this same gap"* and the
File List leaves `sessionManager.js` as "only if implementation finds a change needed here." Two things
the dev must know before touching it, neither of which the story mentions:

1. **It is an explicit project-owner decision**, documented in-code at `sessionManager.js:223-230`
   (*"skillars-deferred-90 (project-owner decision) … The rejected 'arm anyway' alternative costs one
   dead interval cycle (~30s) plus a duplicate 'session:expired' dispatch — and therefore a duplicate
   backend logout"*) and in the ledger at `deferred-work.md:1407` (*"Deliberately left as-is rather
   than patched"*).
2. **It is pinned by a characterization test.**
   `src/frontend/src/plugins/__tests__/sessionManagerCoverageSpec.js:1-15` states the mutation check
   outright: *"delete `if (tick()) return;` in `startSessionMonitoring()` → the early-return
   characterization test sees a second 'session:expired' 30 s later and fails"*; the test itself is at
   `:136-150`.

Recommend the story say plainly: **AC3 does not change `sessionManager.js`** — fixing the router side
removes the trigger, which is the correct root-cause fix. Also replace AC3 task 5's vague *"full
`App.vue`/`sessionManager.js` spec suite"* with the two actual files: `sessionManagerSpec.js` and
`sessionManagerCoverageSpec.js`.

### F23 (Low) — re-entrancy

`handleSessionExpired` is a `window` listener, and `refreshExpiryState()` → `tick()` runs from the
axios response interceptor on **every** response (`boot/axios.js:130, :146`). `tick()` dispatches
`session:expired` whenever `timeUntilExpiry <= 0` (`sessionManager.js:171-174`). So the handler can fire
more than once, and with a hard-nav fallback that means repeated `window.location.href` assignments
while the browser is already unloading. A one-shot module-level flag in `handleSessionExpired` is
cheap insurance; worth one line in task 2.

### Verified correct for AC3

- `App.vue:27-40` is `handleSessionExpired` ✓; `router.push` at `:36-39` ✓; genuinely unawaited and
  uncaught ✓.
- `sessionManager.js:231` is `if (tick()) return` ✓, and `cleanup()` (`:316-323`) really does stop the
  monitoring interval via `stopSessionMonitoring()` ✓.
- "currently synchronous, no other `async` function in this component" ✓ — `App.vue` has none.
- No existing spec mounts `App.vue` ✓ (that half of the Dev Note is true; the `useRouter()` half is
  not — see F2).
- `npm run test:unit` = `vitest run` ✓ (`package.json:13`); `frontend-tests` PR label gates
  `.github/workflows/frontend-unit-tests.yml:53` ✓.

---

## AC4 — `SET LOCAL lock_timeout` convention

### F3 (High) — there are ten such migrations, not four

> "All four of this codebase's `lock_timeout`-setting migrations — `V144`, `V145`, `V149`, `V150`"

```
$ grep -rln "^SET lock_timeout" src/main/resources/db/migration/ | sort -V
V140, V142, V143, V144, V145, V146, V147, V148, V149, V150      → 10 files
```

The four-item list is reproduced **verbatim** from the ledger bullet at `deferred-work.md:2492`
(*"`V144`, `V145`, `V149`, `V150` all do this"*), which is exactly what the story's Provenance section
says did not happen (*"independently re-verified against HEAD … during this story's creation, not
merely re-read"*).

**Impact:** the V150-boundary *conclusion* survives (all ten sit at or below V150, so all ten stay
exempt), but every enumeration is wrong — AC4 task 1 ("all four migrations' current `SET lock_timeout`
lines"), task 5 ("Confirm none of the four real shipped migrations … trip the new rule"), task 7, the
design note, and AC5's bullet-4 disposition. Correct to **V140–V150 (ten files)** throughout.

### F4 (High) — `SET LOCAL` is a no-op outside a transaction block, and this repo has a documented non-transactional pattern

PostgreSQL: `SET LOCAL` outside a transaction block emits
`WARNING: SET LOCAL can only be used in transaction blocks` and **has no effect**. The statement
succeeds, the warning is invisible in a Flyway log, and the lock wait is silently unbounded.

That is not hypothetical here. `docs/deployment/migration-conventions.md` endorses a **non-transactional
`executeInTransaction=false` sidecar** in four places:

- `:40` — *"committed independently via the `executeInTransaction=false` sidecar — see rule 6"*
- `:99-103` — the callout, *"confirmed present in the resolved jar"*
- `:159-162` — *"the `executeInTransaction=false` sidecar (confirmed working)"* for batched DML
- `:241-249` — *"the **non-transactional** `executeInTransaction=false` sidecar case"*

A blanket, lint-enforced "always `SET LOCAL`" would therefore mandate a form that provides **zero**
protection in precisely the case rule 7 exists for — strictly worse than today's plain `SET`, which
works in both modes. Today no migration uses the sidecar and none uses `CREATE INDEX CONCURRENTLY`,
so this is latent rather than live — but the rule is being written for *future* migrations, which is
exactly when it will bite, and `BLOCKING_INDEX` already pushes authors toward `CONCURRENTLY`.

**Required:** the new rule needs an opt-out marker (e.g. `-- migration-lint: allow-session-lock-timeout
<reason>`) or detection of the non-transactional sidecar, and the doc change (task 6) must state the
exception rather than presenting `SET LOCAL` as unconditional.

### F5 (High) — the new rule breaks `validFixtures_areClean`

`MigrationConventionLintTest.validFixtures_areClean` (`:84-86`) runs
`lintFixtures("valid", 0)` — baseline **0**, i.e. every fixture is above it. Of the
`src/test/resources/migration-lint/valid/` fixtures, **12 use plain `SET lock_timeout`**:

```
V809, V810, V811, V812, V813, V814, V815, V816, V817, V819, V820, R__repeatable_drop_optout
```

All sit at V8xx, far above any ~V150 boundary, so a new rule bound at V150 flags all twelve and this
test goes red. AC4 task 5 only says *"the four real shipped migrations must not newly fail"* and task 7
only re-runs against the real migration directory — neither catches this.

**Resolution options (pick one, state it in the task):** thread a fixture-level boundary constant, as
the class already does for the previous rule band (`FIXTURE_DEFERRED_92_BASELINE = 808`,
`MigrationConventionLintTest.java:56`); or convert the 12 valid fixtures to `SET LOCAL` (they still
satisfy `MISSING_LOCK_TIMEOUT`, whose regex accepts both spellings).

### F6 (Medium) — adding a `Rule` enum member breaks `invalidFixtures_triggerEveryRule`

`MigrationConventionLintTest.java:90-106` ends with:

```java
assertThat(triggered).containsExactlyInAnyOrder(MigrationLint.Rule.values());
```

Every rule must have at least one failing fixture under `src/test/resources/migration-lint/invalid/`.
Adding `SESSION_SCOPED_LOCK_TIMEOUT` without adding e.g.
`invalid/V929__session_scoped_lock_timeout.sql` fails this test. Task 5 describes "synthetic
migration" tests but never mentions the fixture directory, which is where this suite actually lives.
A matching `valid/` fixture using `SET LOCAL` is also needed to prove the rule does not over-fire.

### F7 (Medium) — a third baseline is an API change, not a constant

`MigrationLint` has three `lint()` overloads (`:349`, `:353`, `:368`) and `lintFile` (`:533`) threads
`baselineVersion` + `deferred92Baseline` explicitly. A third boundary must be plumbed through all of
them, plus `MigrationConventionLintTest`'s two `lintFixtures` helpers (`:58`, `:62`) and all six call
sites. Task 4's *"wire it into whatever aggregation/reporting mechanism the existing rules use"*
understates this — it is a signature change with a fan-out, not a wiring detail.

### F8 (Medium) — `SET LOCAL` creates a new false negative in the *existing* rule

`lintLockTimeout` (`MigrationLint.java:959-976`) resolves "is a timeout in effect here?" by scanning
**from the start of the file** to the statement:

```java
// A `SET lock_timeout` anywhere earlier in the file covers this statement: it is a session /
// transaction setting, not a per-statement one, so scoping it per statement would be wrong.
String before = stripComments(raw.substring(0, end));
if (isLockTimeoutBoundedAt(before)) return;
```

That model is exactly right for a session-scoped `SET`. It is **wrong** for `SET LOCAL`, whose scope
ends at the enclosing transaction — so a `SET LOCAL` followed by an explicit `COMMIT;` (or by a
non-transactional boundary) would still be treated as "bounded" for every subsequent statement.
Migrating the convention to `SET LOCAL` therefore weakens `MISSING_LOCK_TIMEOUT` unless
`isLockTimeoutBoundedAt` learns to reset on `COMMIT`/`ROLLBACK`. AC4 does not mention this at all,
and it is the kind of "guard believed stronger than it is" the class's own Javadoc (`:86-88`) warns
about having shipped three times already.

### F9 (Medium) — the stated rationale contradicts this story and this repo's own history

> "the four already-shipped migrations are not rewritten (Flyway migrations are immutable once applied;
> editing a shipped script's content changes its checksum and breaks every environment that already
> ran it)."

Two problems:

1. This story's own "Not in scope" section rests on the fact that **no production deploy has happened**,
   and the ledger re-confirms it: `deferred-work.md:2462` — *"no production deploy of this application
   has ever happened (skillars-deferred-117's owner decision, re-confirmed)."*
2. `skillars-deferred-112` **deleted V02–V137 outright** and replaced them with a generated baseline —
   documented in `MigrationLint.java:43-49` (*"closed that gap by deletion, not by edit … recoverable
   from git history at commit `4a3f218d`"*). The project has direct precedent for exactly the rewrite
   AC4 says is impossible.

The owner has decided; this is not a request to re-open it. But the *stated* justification is not
accurate, and a dev who checks will reasonably re-raise it. Restate the rationale on its real grounds
(non-production environments and CI databases have run these migrations; churning ten files for a
convention change is not worth the coordination) rather than on immutability.

### F10 (Medium) — the doc update is scoped to one of six places

Task 6 targets item 7 (`:189-205`) only. `docs/deployment/migration-conventions.md` prescribes plain
`SET lock_timeout` in five more:

- `:45` — pre-flight checklist (*"Every lock-taking DDL has `SET lock_timeout` in effect"*)
- `:156` and `:176` — rebaseline / defensive-use guidance
- `:378-383` — the rule reference list for `MISSING_LOCK_TIMEOUT`
- `:467` — the review checklist

Leaving these on the old spelling reintroduces the exact doc/rule inconsistency AC4 exists to remove.

### F25 (Low) — File List misclassification

`src/test/java/com/softropic/skillars/db/MigrationLint.java` is listed under **"Production code."**
It is a test-tree class (no Spring context, no DB, invoked from `MigrationConventionLintTest` in the
`test` phase). Cosmetic, but it affects how a reviewer reads the diff's risk.

### Verified correct for AC4

- `LOCK_TIMEOUT_DIRECTIVE` at `MigrationLint.java:310-311` ✓, and it genuinely accepts both spellings
  equally: `\bSET\s+(?:LOCAL\s+)?lock_timeout\s*(?:=|TO)\s*(…)`.
- `GRANDFATHER_BASELINE` at `:112` = 139 ✓; `DEFERRED_92_BASELINE` at `:125` = 139 ✓.
- `docs/deployment/migration-conventions.md` item 7 really does recommend the session-scoped form —
  `:200-201`, *"it is a session/transaction setting, so one statement covers the whole script"* ✓.
- The Flyway single-connection premise holds: Spring Boot config (`application.yaml:129-133`) sets
  no `group` / `mixed`, so each migration runs in its own transaction over one reused JDBC
  connection — a plain `SET` does survive into later migrations in the same run.
- `isAboveBaseline` is strictly-greater (`:624`), so a boundary at 150 correctly exempts V150 itself ✓.
- `MigrationConventionLintTest` is the right test class ✓ (task 5's hedge resolves correctly).

### One correction to the design note's framing

The story cites the class Javadoc as establishing that *"a new boundary constant is the established
pattern."* The Javadoc argues closer to the opposite: it records that the two baselines now sit at the
same value, are kept distinct only provisionally, and points at *"`migration-rebaseline.md`'s follow-up
note for the case to **collapse them properly** in a future story"* (`MigrationLint.java:53-57`,
`:114-124`). A third boundary is still the right call here — the rule genuinely must not bind
V140–V150 — but the design note should acknowledge it is moving against the class's stated direction,
not with it. (The story's cited range `:29-53` is also slightly off; the class Javadoc runs `:22-91`.)

---

## AC5 — Ledger closeout

### F18 (Medium) — bullet 4's ledger text carries the four-migration error

`deferred-work.md:2492` reads *"Repo-wide convention (`V144`, `V145`, `V149`, `V150` all do this)."*
Per F3 that is wrong (ten files, V140–V150). Deleting the bullet outright, as AC5 task 2 directs on the
success path, erases the error silently. Add a task: whichever disposition bullet 4 gets, the
`## Last audit` narrative must record the corrected count, so a future audit does not re-derive the
wrong number from the story file.

### F24 (Low) — the out-of-scope section has two bullets, the story names one

`## Explicitly out of scope (skillars-deferred-123, 2026-09-18)` (`deferred-work.md:2443-2448`)
contains **two** bullets:

1. `main."user"` has no index supporting the cleanup-sweep predicate — covered by the story.
2. `ModerationSlaMonitorService.detectSlaViolations`'s no-`@SchedulerLock` `[DECIDED]` note —
   **not mentioned anywhere in the story.**

AC5 task 4 says *"Do not touch … the `main."user"` index bullet"* and directs a `## Last audit` note
re-confirming it. For a story that claims a full-file re-audit of ~92 section headers, the second
bullet should get the same one-line re-confirmation, or a reader will assume it was missed.

### Verified correct for AC5

- The `## Deferred from: code review of skillars-deferred-124-…` section exists at `deferred-work.md:2484`
  with exactly four bullets, mapping to AC5 task 2's bullets 1–4 in order ✓.
- The `## Last audit: 2026-09-19 (skillars-deferred-124 dev-story completion)` reference section is at
  `:2495` — the story's "around line 2495" ✓.
- Task 3's instruction that the section header survives is correct: bullet 2 is annotated, not deleted.
- AC5's disposition of bullet 2 as accepted risk is well-founded — the residual is genuine and AC2
  does not remove it.

---

## Cross-cutting

### F19 (Medium) — the Provenance re-verification claim is falsified for two items

> "all freshly surfaced by that story's own `/bmad-code-review` … and **independently re-verified
> against HEAD (`77036728`) during this story's creation, not merely re-read**."

Two items were demonstrably re-read, not re-verified:

- **Bullet 4 → AC4:** the `V144`/`V145`/`V149`/`V150` list is verbatim from the ledger and is wrong by
  a factor of 2.5 (F3).
- **The 1-7b item → AC3:** *"Vue Router 4 rejects on an aborted/redirected navigation"* is verbatim
  from `deferred-work.md:1407` and is wrong for the installed version (F1). That ledger bullet even
  flags itself: *"the abort path is unverified."*

Bullets 1–3 *were* genuinely re-verified — their line citations are corrected relative to the ledger's
drifted ones (`:147-150` → `:156-159`, `:121-123` → `:125-127`, test `:268-282` → `:277-290`), which is
evidence of real checking on those three.

This is the same failure mode the ledger's own `## Last audit: 2026-09-19` note warns about in its
closing line: *"a plausible-sounding lock-mode/line-number citation is not a substitute for running the
scenario."* Recommend softening the Provenance claim to name which bullets were source-verified and how.

### Dev Notes — verified correct

- Context-count ceiling is 44 ✓ (`.github/workflows/pr-build.yml:65, :72`), and AC1's tests reuse
  existing spy beans, so no fork is expected.
- The cross-AC independence analysis is right: AC1 and AC2 touch disjoint regions of
  `RadarCompositeDlqProcessor.java` (`:125-127` vs `:80-89` + the test) and are independently
  revertable.
- "No new Flyway migration in this story" ✓ — none of the four ACs requires one.

---

## Recommended disposition

**Blocking before dev-story starts** (a dev following the story as written would ship a non-fix or a
red build):

- **F1** — rewrite AC3's failure-mode analysis against Vue Router 4's actual resolve/reject contract;
  the fix must handle the resolved-`NavigationFailure` case and the test must cover it.
- **F3** — correct four → ten (V140–V150) everywhere in AC4 and AC5.
- **F4** — add the non-transactional (`executeInTransaction=false`) exemption to AC4's rule design and
  doc change.
- **F5** / **F6** — add tasks for the 12 `valid/` fixtures and the new `invalid/` fixture, or the
  lint suite goes red.

**Should be folded in before dev-story** (correctness or scope gaps, cheap to fix in the story):

F2, F7, F8, F11, F12, F13, F14, F15, F16, F17, F18.

**Worth a line each; safe to hand to the dev as notes:**

F9, F10, F19, F20, F21, F22, F23, F24, F25.

**Structurally sound as written, no change needed:** AC1's `try`/`catch`-not-`try`/`finally` correction
and its scoping of `resetStaleClaimed` out of the guard; AC2's identification of the missing
`lockAtMostFor < staleWindow` assertion and the 15-minute value; AC5's four-bullet disposition mapping;
the cross-AC independence and commit-splitting guidance in Dev Notes.
