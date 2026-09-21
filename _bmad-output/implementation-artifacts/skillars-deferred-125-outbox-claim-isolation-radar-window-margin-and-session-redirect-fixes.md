# Story: Outbox Claim-Phase Isolation, Radar Stale-Window Margin & Session-Redirect Fixes

**Story Key:** `skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes`
**Epic:** Deferred Work
**Priority:** High (a real outbox/DLQ claim-phase availability gap on both processors, a real
zero-margin scheduler-lock/stale-window relationship on one processor, and a real frontend session-
redirect failure mode reachable at three call sites) — plus a repo-wide migration-convention
hardening and the standard ledger closeout.
**Status:** ready-for-dev
**Created:** 2026-09-19
**Reviewed:** 2026-09-21 (`story-review.md`, senior-dev pre-implementation audit). 25 findings, all 25
independently re-verified against actual source/installed packages before applying anything — **zero
false positives**, every finding confirmed genuine. See the Change Log for the full response.

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md` per this
project's established "read same-day code-review deferrals before drawing scope" convention, plus one
older but still-genuinely-open item surfaced during the full-file re-audit this story creation ran:

- **`## Deferred from: code review of skillars-deferred-124-strike-lock-contention-outbox-resilience-
  and-schema-fixes (2026-09-19)`** — 4 bullets, all freshly surfaced by that story's own
  `/bmad-code-review` (Edge Case Hunter + Blind Hunter layers). Bullets 1–3 (AC1, AC2, AC2's test gap)
  were genuinely independently re-verified against HEAD at story-creation time — their line citations
  were corrected relative to the ledger's own drifted ones. **Bullet 4 was not**: its "four migrations"
  claim was copied from the ledger verbatim and is wrong by a factor of 2.5 — see AC4 and the
  `story-review.md` response below.
- **`code review (round 2) of 1-7b-session-refresh-rint-contract-fix (2026-09-02)`** — the
  `sessionManager.js`/`App.vue` router-abort re-arm gap, confirmed still open and untouched by every
  subsequent audit since (most recently the 2026-09-10 skillars-deferred-108 post-merge sweep, which
  explicitly left it "item stays open"). **This one also was not independently re-verified at
  story-creation time** — the ledger bullet's own Vue Router 4 rejection claim (which even flags
  itself "the abort path is unverified") was carried over as fact and is false for the installed
  version. See AC3 and the `story-review.md` response below.

**Not in scope**, confirmed by direct re-verification during this story's creation (see the ledger's
own "Explicitly out of scope" section, `skillars-deferred-123, 2026-09-18` — which holds **two**
bullets, both re-confirmed still correctly out of scope, not just the one below):

- `main."user"` has no index supporting the cleanup-sweep predicate
  (`activated`/`created_date`/`cleanup_failed_at`). Owner decision (AskUserQuestion, 2026-09-19):
  **keep deferring** — the ledger's own blocker (production row-count/`EXPLAIN` evidence, per
  `docs/deployment/migration-conventions.md`'s guidance on hot-table indexing) still cannot be met
  pre-launch, and guessing an index shape risks shipping the wrong one as technical debt. No AC in
  this story touches it; left exactly as-is in the ledger.
- `ModerationSlaMonitorService.detectSlaViolations`'s no-`@SchedulerLock` `[DECIDED]` note — already
  decided elsewhere in the ledger, confirmed still correctly out of scope.

Three owner decisions were taken live with the user (`AskUserQuestion`) before drafting this story —
see AC2, AC4, and the "Not in scope" note above.

**Line numbers below were re-verified against HEAD (`77036728`) at story-creation time, 2026-09-19,
and against the same HEAD again during the 2026-09-21 `story-review.md` audit.** Re-verify again
immediately before implementing each AC — this series' own established convention, since every prior
story in it has found at least minor drift between story-creation time and dev-story time.

---

## AC1 — Outbox/DLQ claim-phase isolation: release a stranded claim on abnormal exit

**The finding (re-verified against HEAD, still live).** Both processors run three repository calls
back-to-back with no exception handling around any of them:

- `VideoDeletionOutboxProcessor.process()` (`VideoDeletionOutboxProcessor.java:156-159`):
  `resetStaleClaimed` → `claimPendingBatch` → `findClaimedBatch`.
- `RadarCompositeDlqProcessor.process()` (`RadarCompositeDlqProcessor.java:125-127`): the identical
  three-call sequence.

`claimPendingBatch` is its own auto-committing native `@Modifying` UPDATE (see
`VideoDeletionOutboxRepository.java:90-104`'s own comment on why `process()` is deliberately not
`@Transactional`) — by the time it returns, up to `BATCH_SIZE` (50) rows are durably `CLAIMED` under
this run's `runId`, committed to the database, independent of anything that happens next in the Java
method. If the very next call, `findClaimedBatch`, throws for a reason that leaves the connection
usable (a statement timeout on the `SELECT`, a row-mapping failure, a bad `LIMIT` bind), those rows
are never handed to the loop, the method-local `runId` variable goes out of scope when the exception
propagates out of `process()`, and nothing in this codebase can ever again address those specific rows
by that `runId`. They sit `CLAIMED` until `resetStaleClaimed`'s own next-tick invocation frees them by
elapsed time alone — `STALE_CLAIM_WINDOW` — 20 minutes for video, 10 (soon 15, see AC2) for radar —
even though the very next scheduled tick, 60 seconds later, could otherwise have retried them
immediately.

skillars-deferred-124 AC2's own new per-row guard (the `handleFailure` routing inside the `for` loop)
deliberately covers only the loop body, by design — this is a distinct, earlier phase of `process()`
that guard was never meant to reach.

**The fix is NOT a bare `try`/`finally`.** A `finally` block always runs, including on the successful
path — releasing the batch back to `PENDING` immediately after `claimPendingBatch` committed it, before
a single row is processed, undoing the claim you just took and racing a concurrent invocation into
re-claiming and double-processing the same rows. The correct shape is a `try`/`catch` that releases
**only on the exceptional path**, then rethrows — never touching the claim on the happy path.

**Two corrections from the 2026-09-21 `story-review.md` audit, both confirmed genuine:**

1. **The fix's own `releaseClaimed` call can itself fail, and must not mask the original exception.**
   Two of the three example failure causes above (a dropped connection, pool exhaustion) will also
   break the `releaseClaimed(runId)` call in the `catch` block — it is another JDBC round trip on the
   same broken connection/pool. This class already has the exact shape needed for this: the loop's own
   `handleFailure`-can-itself-throw guard
   (`VideoDeletionOutboxProcessor.java:203-209`, `RadarCompositeDlqProcessor.java:158-164` — a nested
   `try`/`catch` that logs the secondary failure at `ERROR` rather than letting it propagate and
   swallow the original cause). Follow that precedent for `releaseClaimed`'s own call, rather than
   inventing a new shape.
2. **There is no existing "no-throw-out-of-`process()`" convention for this phase, so "log and
   return" is a real behaviour change, not a style choice.** The claim/fetch phase has no guard at all
   today — a throw there genuinely escapes `process()` and reaches Spring's scheduled-task error
   handler. The loop's own `catch` (`:198-210` / `:153-165`) is a *different* mechanism at a *different*
   level; `handleFailure` does not establish any "swallow and return" precedent this phase could cite.
   **Simply rethrow after the guarded `releaseClaimed` call** — do not offer "log and return" as an
   alternative.

### Tasks

1. Re-verify this premise directly against the current source in both files before implementing —
   confirm the exact call sequence and line numbers, and confirm neither `claimPendingBatch` nor
   `findClaimedBatch` already has any exception handling (they do not, per the citations above, but
   re-check at implementation time per this story's own stated convention).
2. In `VideoDeletionOutboxProcessor.process()`, wrap `claimPendingBatch` + `findClaimedBatch` in a
   `try`/`catch` that, on any exception, attempts `outboxRepository.releaseClaimed(runId)` — itself
   guarded by a nested `try`/`catch` that logs a secondary-failure ERROR without letting it replace the
   original exception (mirror the loop's own `handleFailure`-itself-throws shape cited above) — and
   then **rethrows the original exception**. Do **not** wrap `resetStaleClaimed` in the same block —
   it runs before any claim exists for this `runId`, so there is nothing of this run's to release if
   it throws.
3. Apply the identical fix to `RadarCompositeDlqProcessor.process()`.
4. New test per processor: force an exception from `findClaimedBatch` on the **existing** spy bean —
   `VideoDeletionOutboxProcessorIT` already has `@MockitoSpyBean VideoDeletionOutboxRepository
   outboxRepository` (`VideoDeletionOutboxProcessorIT.java:57`; **not** the `DrillVideoRefRepository`
   spy at `:48`, which is unrelated to this claim phase) — and assert `releaseClaimed` was invoked.
   **`runId` is generated inside `process()` (`UUID.randomUUID()`), so the test has no a-priori handle
   on it**: capture it with an `ArgumentCaptor<UUID>` on `claimPendingBatch`'s second argument, then
   assert the same captured value reaches `releaseClaimed`. A bare `verify(repo).releaseClaimed(any())`
   would pass the mutation check below while proving almost nothing. A second test proves the happy
   path is unaffected: `releaseClaimed` is `never()` invoked when no exception occurs.
5. Mutation-check by hand: temporarily revert the `try`/`catch`, confirm the new test fails as
   expected; restore, confirm it passes.
6. Re-run both processors' full test classes plus any repository IT touching `releaseClaimed`/
   `findClaimedBatch` together — zero regressions expected.

---

## AC2 — RadarCompositeDlqProcessor: restore a real stale-window margin above its scheduler lock

**The finding (re-verified against HEAD, still live).**
`RadarCompositeDlqProcessor.STALE_CLAIM_WINDOW` (`RadarCompositeDlqProcessor.java:89`, currently
`Duration.ofMinutes(10)`) is **exactly equal** to its own `@SchedulerLock(lockAtMostFor = "PT10M")`
(`:116`) — zero buffer between the two. Its structural twin, `VideoDeletionOutboxProcessor`, documents
strict inequality between the same two quantities as mandatory (its own `STALE_CLAIM_WINDOW` Javadoc,
`VideoDeletionOutboxProcessor.java:34-58`): `MAX_RUN_DURATION (12m) < LOCK_AT_MOST_FOR (15m) <
STALE_CLAIM_WINDOW (20m)`, a 5-minute buffer between the middle and outer terms.

The equality is not simply stale framing. **Correction from the 2026-09-21 `story-review.md` audit:**
the story-creation draft cited `RadarCompositeDlqProcessor.java:55-77` as the class's "own class
Javadoc" documenting the prior deliberate decision to leave the equality unchanged — that citation is
wrong. `:55-77` is the **`MAX_RUN_DURATION` field's own Javadoc**, not the class-level comment (the
class-level `//` header block is `:20-47`), and `:71` — inside that field Javadoc — is where the
load-bearing sentence actually lives: *"Fixed at the source rather than by widening the window."*
`STALE_CLAIM_WINDOW`'s own field Javadoc (`:80-89`) separately states the value is "deliberately
unchanged at 10 minutes." **Both field Javadocs assert this class's prior decision, and both must be
revised** — rewriting only `STALE_CLAIM_WINDOW`'s leaves `MAX_RUN_DURATION`'s `:71` asserting the
opposite of what this AC does.

That prior decision's reasoning holds *if* `MAX_RUN_DURATION` were a hard, continuously-enforced
ceiling — but skillars-deferred-124's own code review (see the current ledger bullet,
`deferred-work.md`) found it is not: the deadline is sampled only at the top of each loop iteration
(`process()`'s `for` loop, `RadarCompositeDlqProcessor.java:136-143`), never inside
`processRow`/`handleFailure` itself. One row that blocks longer than `lockAtMostFor - MAX_RUN_DURATION`
— a 2-minute margin for this class — still overruns the lock. Once that happens, the zero-margin
equality means the *very next* tick's `resetStaleClaimed` immediately frees and re-claims the
still-processing run's rows, causing a real duplicate `recalculateComposite` — an external side effect
`claimed_by` cannot undo after the fact, only prevent from being written twice.

The guard test, `RadarCompositeDlqProcessorTest.runtimeBudget_staysStrictlyInsideLock`
(currently `RadarCompositeDlqProcessorTest.java:277-290`), asserts `maxRun < lockAtMostFor` and
`maxRun < staleWindow` but **never asserts `lockAtMostFor < staleWindow`** — the one inequality this
class actually violates, and the one `VideoDeletionOutboxProcessor`'s equivalent Javadoc/test
(`VideoDeletionOutboxProcessorSchedulerLockTest.runtimeBudget_staysStrictlyInsideLockAndStaleWindow`,
`:70-82`) already treats as load-bearing (`assertThat(lockAtMostFor).isLessThan(staleWindow)`).

**Owner decision (AskUserQuestion, 2026-09-19): widen `STALE_CLAIM_WINDOW` and add the missing test
assertion.** Chosen over leaving the values as-is (the current Javadoc's own accepted-equality framing)
and over engineering a hard per-row timeout around `recalculateComposite` (materially more complex for
a rare edge case). The remaining, narrower per-row-overrun residual from skillars-deferred-124's review
(bullet 2, `MAX_RUN_DURATION` sampled only between rows) is **accepted as documented risk**, not fixed
by this AC — it becomes markedly less consequential once a real buffer exists between lock expiry and
the stale sweep, since a single overrunning row no longer immediately triggers a duplicate reclaim.

**Also confirmed by the `story-review.md` audit: three stale cross-references, and one unstated
trade-off, that widening the window creates but this AC did not originally list** (all four are folded
into the Tasks below):

1. `RadarCompositeDlqProcessorTest.runtimeBudget_staysStrictlyInsideLock`'s **own test Javadoc**
   (`:261-274`) states *"Rather than widen the window, `process()` now self-terminates…"* and *"That
   makes `MAX_RUN_DURATION < lockAtMostFor` the load-bearing inequality here"* — directly contradicted
   by this AC and must be rewritten alongside the new assertion, not left as-is.
2. `VideoDeletionOutboxProcessor.java:44-47`'s Javadoc describes *"`RadarCompositeDlqProcessor`'s
   equality between its own 10-minute window and its `PT10M` lock"* as the current state — stale once
   the equality is gone.
3. `docs/deployment/scheduler-lock-tuning.md:77`'s radar row reads *"load-bearing (see that class's
   `MAX_RUN_DURATION`)"* — should mirror the video row directly above it (`:76`, *"must stay strictly
   under `STALE_CLAIM_WINDOW`"*) once radar has a real buffer too. This doc is not in this story's File
   List at all today; it must be added.
4. Widening `STALE_CLAIM_WINDOW` to 15 minutes raises radar's **crash-recovery latency** (how long a
   genuinely dead instance's rows sit `CLAIMED` before the stale sweep frees them) from 10 to 15
   minutes. `VideoDeletionOutboxProcessor.java:118-120` documents this exact trade-off explicitly for
   its own 20-minute value (*"immaterial for a deletion outbox polled every 60 seconds"*) — the new
   radar Javadoc should carry the equivalent sentence, or a future reader will read the widening as
   pure gain with no cost.

### Tasks

1. Re-verify the current values and line numbers directly against HEAD before implementing.
2. Widen `STALE_CLAIM_WINDOW` from `Duration.ofMinutes(10)` to `Duration.ofMinutes(15)` — a 5-minute
   buffer above `lockAtMostFor` (`PT10M`), matching `VideoDeletionOutboxProcessor`'s own buffer
   *amount* between its `LOCK_AT_MOST_FOR` and `STALE_CLAIM_WINDOW` (15m → 20m is also a 5-minute
   gap), not just its ratio.
3. Rewrite **both** `MAX_RUN_DURATION`'s Javadoc (`:55-77`, specifically the "Fixed at the source
   rather than by widening the window" sentence at `:71`) **and** `STALE_CLAIM_WINDOW`'s Javadoc
   (`:80-89`, the "deliberately unchanged at 10 minutes" sentence) — record why the prior decision is
   being revised (skillars-deferred-124's own review found the `MAX_RUN_DURATION` bound is not
   airtight against a single slow row, so a real buffer is worth restoring as defense-in-depth even
   with the bound in place), cross-reference this story, and add the crash-recovery-latency trade-off
   sentence (item 4 above).
4. Add the missing assertion to `runtimeBudget_staysStrictlyInsideLock`:
   `assertThat(lockAtMostFor).isLessThan(staleWindow)`, with an `.as(...)` explaining it is the one
   inequality `VideoDeletionOutboxProcessor`'s equivalent test coverage already treats as load-bearing
   and this class's previously did not. **Also rewrite the test method's own Javadoc** (`:261-274`,
   item 1 above) — its "rather than widen the window" framing is now the opposite of what this class
   does.
5. Update the two other stale cross-references found by the review (items 2–3 above):
   `VideoDeletionOutboxProcessor.java:44-47`'s Javadoc, and `docs/deployment/scheduler-lock-tuning.md`'s
   radar row (`:77`) — add the doc to this story's File List.
6. Add a short code comment (near `MAX_RUN_DURATION` or in the class header) explicitly naming the
   accepted residual: a single row that individually blocks longer than the lock/window buffer can
   still overrun, and that this is accepted (not fixed) per this story's owner decision — so a future
   reader does not mistake this AC for a complete close-out of skillars-deferred-124's bullet 2.
7. Mutation-check: confirm the new assertion currently fails against the unwidened value (write it
   first, watch it fail, matching this project's own established practice), then widen the value and
   confirm it passes.
8. Re-run `RadarCompositeDlqProcessorTest` in full — zero regressions expected. Confirm no other test
   in this class or `RadarCompositeDlqRepositoryIT` hardcodes the old 10-minute value (it does not per
   this story's own pre-check: `RadarCompositeDlqRepositoryIT:82,101` passes an explicit `Instant`
   deadline argument, never reading the processor's own constant).

---

## AC3 — Session-expiry redirect: fall back to a hard navigation when `router.push` cannot land

**Correction from the 2026-09-21 `story-review.md` audit — the original finding's Vue Router premise
was false, and the prescribed fix/test would have shipped a false sense of safety.** The story as
originally drafted claimed Vue Router 4's `router.push` **rejects** on a failed navigation. Verified
directly against the installed package (`src/frontend/node_modules/vue-router`, version **4.6.4**,
`dist/vue-router.mjs`'s `pushWithRedirect`): navigation failures are **caught internally and resolved**,
not rejected —

| Outcome | Actual `router.push()` behaviour |
|---|---|
| A guard returns `false` (`NAVIGATION_ABORTED`) | **resolves** with a `NavigationFailure` object |
| Pushing the current route again (`NAVIGATION_DUPLICATED`) | **resolves** (short-circuited before navigating) |
| Superseded by a more recent navigation (`NAVIGATION_CANCELLED`) | **resolves** with a `NavigationFailure` object |
| A guard **throws** an arbitrary (non-`NavigationFailure`) error | **rejects** |

All three of the failure modes the original story named (guard-cancelled, duplicate, superseded) are
the ones that **resolve**. The one path that genuinely rejects — a guard throwing — is the one the
original story did not name. A `.catch()`-only fix, and the "mocked router whose `push` rejects" test
the story originally prescribed, would both ship green while fixing nothing for the actual dominant
failure mode. The same wrong claim sat in `deferred-work.md:1407` (*"Vue Router 4 rejects on an
aborted/redirected navigation"*), in a bullet that itself said *"the abort path is unverified"* — this
story carried it forward as fact instead of checking it; AC5 now corrects that ledger text too.

**The finding, corrected.** `App.vue`'s `handleSessionExpired` (`src/frontend/src/App.vue:27-40`)
clears cookies, calls `authStore.logout()`, calls `playerStore.resetSelfPlayerId()`, calls `cleanup()`
(which stops `sessionManager.js`'s monitoring interval), and then calls `router.push({...})` (`:36-39`)
— **without inspecting the resolved value or attaching a `.catch()`**. By the time either a resolved
`NavigationFailure` or a rejection would be observable, every piece of session state has already been
irreversibly torn down. If the navigation does not land — for any of the four reasons above — the user
is left on the current route with no active session and no active monitoring
(`startSessionMonitoring`'s early-return-on-already-expired path, `sessionManager.js:231`, is why: an
already-expired session at mount never arms ongoing monitoring at all, since `tick()` has already
dispatched the event synchronously). The only backstop today is the axios response interceptor's own
independent 401 handler (`src/frontend/src/boot/axios.js:150-167`), which already performs an
equivalent hard navigation for the identical event — see below.

**Confirmed by the `story-review.md` audit: the same bug exists at two more live call sites, both
identical in shape and both left unfixed by the original draft.**

| Call site | Teardown before the push | Push |
|---|---|---|
| `src/App.vue:31-39` | cookie cleared, `authStore.logout()`, `resetSelfPlayerId()`, `cleanup()` | `:36-39` |
| `src/composables/useSession.js:75-110` | `stopSessionMonitoring()`, cookies cleared (twice), `authStore.logout()` raced, `resetSelfPlayerId()`, `cleanup()` | `:110` |
| `src/layouts/MainLayout.vue:354-374` | `logout → resetSelfPlayerId → destroySession → deleteUserCookie` | `:374` |

Both `useSession.handleLogout` and `MainLayout.handleLogout` are already `async` functions their
callers `await` — fixing the `router.push` call at either cost is nearly free. This story widens AC3
to all three, sharing one small helper, rather than leaving two of three identical instances open —
silently fixing one of three is how this exact item stayed open since 2026-09-02.

**A hard-navigation precedent already exists in this codebase for the identical event and target** —
`src/boot/axios.js:163-167`:

```js
const currentPath = window.location.pathname + window.location.search
const redirectUrl = `/login?redirect=${encodeURIComponent(currentPath)}&expired=true`
window.location.href = redirectUrl
```

**The `encodeURIComponent` is not optional.** `App.vue:35` currently passes `currentPath` through
`router.push`'s `query` object, which encodes it automatically. A hand-built fallback URL that skips
the encoding breaks the moment the current path carries its own query string —
`/coach/x?a=1&b=2` would arrive at `LoginPage.vue:161-164` as `route.query.redirect === '/coach/x'`
with `a`/`b` parsed as sibling params instead, and `expired` possibly lost.

**`sessionManager.js` is explicitly OUT of scope for this AC — do not touch it.** Two things the
original draft did not mention:

1. `startSessionMonitoring`'s early return (`:223-230`) is an **explicit, already-recorded
   project-owner decision** (skillars-deferred-90): *"The rejected 'arm anyway' alternative costs one
   dead interval cycle (~30s) plus a duplicate `session:expired` dispatch — and therefore a duplicate
   backend logout."* Also recorded in the ledger (`deferred-work.md:1407`, `:1409`).
2. It is pinned by a **characterization test** —
   `sessionManagerCoverageSpec.js:1-15` states the mutation check outright: *"delete `if (tick())
   return;` in `startSessionMonitoring()` → the early-return characterization test sees a second
   `session:expired` 30 s later and fails"*; the test itself is at `:136-150`.

Fixing the router side removes the trigger for this path entirely, which is the correct root-cause
fix — no change to `sessionManager.js` is needed or wanted.

**Re-entrancy (low severity, cheap to guard).** `handleSessionExpired` is a `window` listener, and
`refreshExpiryState()` → `tick()` runs from the axios response interceptor on every response
(`boot/axios.js:131, :147`), so `tick()` can dispatch `session:expired` more than once in quick
succession from concurrent in-flight requests completing after expiry. With a hard-nav fallback that
means repeated `window.location.href` assignments while the page may already be unloading — a one-shot
module-level flag in the handler is cheap insurance.

### Tasks

1. Re-verify `App.vue`'s current `handleSessionExpired`, `useSession.js`'s `handleLogout`, and
   `MainLayout.vue`'s `handleLogout` directly against HEAD before implementing — confirm line numbers
   and confirm all three still end with an unguarded `router.push`.
2. Add a small shared helper (e.g. `src/frontend/src/utils/sessionRedirect.js`, alongside the existing
   `sessionCookies.js`) that: pushes to `/login` with the `redirect`/`expired` query (mirroring
   `App.vue:36-39`'s existing query-object style, which auto-encodes); inspects the settled result for
   **both** outcomes that mean "did not land" — a resolved `NavigationFailure` (use `vue-router`'s
   exported `isNavigationFailure` helper) and a rejection (`.catch()`); and on either, falls back to a
   hard navigation built the same way `boot/axios.js:163-167` already does (`encodeURIComponent` the
   current path, `window.location.href`). Include a one-shot guard against re-entrant invocation
   (re-entrancy note above).
3. Call the new helper from all three sites: `App.vue:36-39`, `useSession.js:110`, `MainLayout.vue:374`
   — replacing each call site's own `router.push(...)` with the shared helper, preserving each
   function's own existing teardown sequence exactly as-is (this story does **not** unify the two
   `handleLogout` implementations' teardown ordering, which `MainLayout.vue`'s own comment records as
   deliberate — only the final push/fallback step is shared).
4. New tests, following `src/layouts/__tests__/MainLayoutSpec.js`'s existing template exactly (a real
   `createRouter`/`createMemoryHistory` router installed via `global: { plugins: [...] }`,
   `createTestingPinia`, `vi.spyOn(router, 'push')`) — **this is not new test infrastructure**: three
   existing specs already mount components with a real router this way
   (`MainLayoutSpec.js`, `useSessionSpec.js`, `BookingRequestPageSpec.js`), and
   `test/vitest/setup-file.js` already documents the Pinia-opt-in convention
   (`createTestingPinia()`, not globally installed). Use the established `*Spec.js` naming under
   `src/**/__tests__/`, not `*.spec.js` — every existing spec uses the former. Environment is
   `happy-dom` (`vitest.config.mjs`), not jsdom — account for its semantics when stubbing
   `window.location` for the hard-nav assertion. Cases needed, per call site (or once against the
   shared helper directly plus one integration-style case per call site — pick whichever this
   project's existing conventions favor):
   - `push` resolves with a `NavigationFailure`-shaped value → hard-nav fallback fires with the
     correctly `encodeURIComponent`-encoded URL. **This is the dominant real-world case and must not be
     skipped.**
   - `push` rejects (a guard throws) → hard-nav fallback fires.
   - `push` resolves successfully (`undefined`) → fallback does **not** fire, no double-navigation.
5. Mutation-check by hand: temporarily remove the fallback, confirm the new failure-path tests fail as
   expected; restore, confirm all tests pass.
6. Run the two actual affected spec files together —
   `src/frontend/src/plugins/__tests__/sessionManagerSpec.js` and
   `.../sessionManagerCoverageSpec.js` are untouched by this AC and do not need re-running for it, but
   any new/updated spec for `App.vue`/`useSession.js`/`MainLayout.vue`/the new helper does. Zero
   regressions expected. This story's PR needs the `frontend-tests` label per this repo's standard CI
   convention for frontend-logic changes.

---

## AC4 — Migration convention: require `SET LOCAL lock_timeout`, not session-scoped `SET`, going forward

**Correction from the 2026-09-21 `story-review.md` audit — the scope count was wrong, and the
unconditional form of the rule as originally drafted would have been actively unsafe.** The original
draft named **four** migrations (`V144`, `V145`, `V149`, `V150`) — copied verbatim from the ledger
bullet at `deferred-work.md:2492`. The actual count, re-verified directly (`grep -rln "^SET
lock_timeout" src/main/resources/db/migration/`), is **ten**: `V140`, `V142`, `V143`, `V144`, `V145`,
`V146`, `V147`, `V148`, `V149`, `V150` (`V141` is a marker-only migration with no lock-taking DDL, so
it never needed the directive). The eventual boundary *conclusion* survives unchanged — all ten still
sit at or below the new rule's boundary and stay exempt — but every enumeration in the tasks below,
and the ledger's own bullet 4 text, must say **ten (`V140`–`V150`)**, not four.

**The finding (re-verified against HEAD, still live).** All ten migrations above use plain
`SET lock_timeout = '5s';` (session-scoped: it persists for the rest of the database session) rather
than `SET LOCAL lock_timeout = '5s';` (transaction-scoped: automatically resets at `COMMIT`/`ROLLBACK`).
Flyway runs every pending migration in a single deploy over **one** JDBC connection/session (confirmed:
Spring Boot config sets no Flyway `group`/`mixed` option, so each migration runs in its own transaction
over one reused connection) — a session-scoped `SET` in an earlier migration silently carries forward
into a later migration in the same deploy run that never declared its own timeout, or that assumed the
platform default. `MigrationLint`'s existing `Rule.MISSING_LOCK_TIMEOUT` regex
(`MigrationLint.java:310-311`, `LOCK_TIMEOUT_DIRECTIVE`) already accepts either spelling — `SET` or
`SET LOCAL` — equally, so nothing today distinguishes or discourages the leakier form.
`docs/deployment/migration-conventions.md` **recommends the leakier session-scoped form in six
places, not just item 7** (see Task 7 below) — the doc's own phrasing does not distinguish the two
scopes anywhere.

**Two required corrections to the rule's own design, both confirmed genuine by the `story-review.md`
audit — implementing the rule as originally drafted would break in production and break two existing
green test suites:**

1. **`SET LOCAL` is a no-op outside a transaction block, and this repo documents a working
   non-transactional migration pattern.** PostgreSQL emits `WARNING: SET LOCAL can only be used in
   transaction blocks` and silently takes no effect when used outside one — invisible in a Flyway log,
   leaving the lock wait unbounded. `docs/deployment/migration-conventions.md` documents, as
   **"confirmed working"** (found empirically during skillars-deferred-112), a non-transactional
   `executeInTransaction=false` sidecar pattern (a `V<n>__x.sql.conf` file containing
   `executeInTransaction=false`) for multi-batch, multi-commit backfills (rule 6, `:159-162`) — the
   exact case a blanket "always `SET LOCAL`" rule would silently defeat. No migration uses this sidecar
   today, so this is latent rather than live, but the new rule is being written for *future*
   migrations, which is precisely when it would bite. **The new rule needs an opt-out (e.g.
   `-- migration-lint: allow-session-lock-timeout <reason>`) or must detect the sidecar `.conf` file
   and exempt it.**
2. **`isLockTimeoutBoundedAt` (`MigrationLint.java:981-991`) scans from the start of the file forward
   and has no concept of a transaction boundary** — correct for a session-scoped `SET`, but wrong for
   `SET LOCAL`, whose real scope ends at the enclosing `COMMIT`/`ROLLBACK`. A migration containing a
   `SET LOCAL` followed by an explicit mid-file `COMMIT` would still be treated as "bounded" for every
   later statement in the file by this static scan, even though `SET LOCAL`'s actual effect ended at
   that `COMMIT`. Migrating the convention to `SET LOCAL` without teaching this method to reset its
   tracked state on `COMMIT`/`ROLLBACK` creates a new false negative in the **existing**
   `MISSING_LOCK_TIMEOUT` rule. This codebase's own migrations do not currently contain mid-file
   `COMMIT`s (ordinary Flyway migrations run as one whole-file transaction), so this is a latent edge
   case, not a live one today — but worth closing alongside the rest of this AC rather than leaving as
   a known gap in a rule this story is actively strengthening.

**Owner decision (AskUserQuestion, 2026-09-19): require `SET LOCAL` for all new migrations going
forward, via a doc update plus a new, enforced `MigrationLint` rule — the ten already-shipped
migrations are not rewritten.** **Restated rationale, corrected by the `story-review.md` audit:** the
original draft justified this on Flyway migration immutability ("editing a shipped script's content
changes its checksum and breaks every environment that already ran it") — but this project's own
"Not in scope" note above rests on the fact that **no production deploy has ever happened**
(re-confirmed, `deferred-work.md:2462`), and `skillars-deferred-112` already set a direct precedent for
rewriting migration history (it deleted `V02`–`V137` outright and replaced them with a generated
baseline — `MigrationLint.java:43-49`). The real reason not to rewrite the ten is simpler and does not
depend on immutability: non-production environments and CI have already run these migrations, and
churning ten files for a pure convention change is not worth the coordination/review overhead relative
to just applying the (stronger) rule going forward. State it on these grounds, not on an "impossible to
edit" framing a curious future reader would correctly find inconsistent with this project's own history.

**Design note for implementation**, following this codebase's own established precedent — with one
framing correction from the review. `MigrationLint.java` already has a mechanism for exactly this
situation: a new rule that must not retroactively flag already-shipped migrations.
`GRANDFATHER_BASELINE` (`:112`) and `DEFERRED_92_BASELINE` (`:125`) are two existing, distinct
version-boundary constants for this purpose. **The class's own Javadoc (`:22-91`, corrected range —
not `:29-53` as originally cited) does not, however, present "add a new boundary constant" as the
established forward direction; it records that the two existing baselines now happen to sit at the
same value and are kept distinct only *provisionally*, pointing at `migration-rebaseline.md`'s
follow-up note for the case to *collapse* them properly in a future story.** A third boundary is still
the right call here — the rule genuinely must not bind `V140`–`V150` — but implement it with that
context in mind rather than as if it were the class's stated intent.

`V150` is the newest migration at story-creation time (re-confirm the actual tip version at
implementation time — a concurrent story could have added a newer one since). The natural shape is a
third boundary constant at the current tip, a new `Rule` enum member (e.g.
`SESSION_SCOPED_LOCK_TIMEOUT`), and a check that a plain `SET lock_timeout` (without `LOCAL`, and not
covered by the sidecar exemption above) in a migration **above** that boundary is a violation — while
all ten real migrations stay exempt by virtue of sitting at or below it.

**This is a genuine API-surface change, not a wiring detail — confirmed by the `story-review.md`
audit.** `MigrationLint` has three `lint()` overloads (`:349`, `:353`, `:368`) and a private `lintFile`
(`:533`) that already thread `baselineVersion` + `deferred92Baseline` explicitly through every call
site; a third boundary must be added to all of them. `MigrationConventionLintTest` has its own,
**separate** fixture-level boundary constant for the existing baseline
(`FIXTURE_DEFERRED_92_BASELINE = 808`, `:56`) and two `lintFixtures` helper overloads (`:58`, `:62`)
that also need the new boundary threaded through — this is a distinct, parallel numbering space from
the real migration directory's `V140`–`V150`, easy to conflate with the real-world boundary if not
handled deliberately.

**Two existing green test-suite risks the original draft did not check, both confirmed genuine:**

- `MigrationConventionLintTest.validFixtures_areClean` (`:84-86`) lints
  `src/test/resources/migration-lint/valid/` at fixture baseline **0** — every fixture in that
  directory is "above" it. **12 of those fixtures use plain `SET lock_timeout`**
  (`V809`, `V810`, `V811`, `V812`, `V813`, `V814`, `V815`, `V816`, `V817`, `V819`, `V820`,
  `R__repeatable_drop_optout`). A new rule bound only at the real-migration boundary (~`V150`) would
  still flag every one of these fixtures if the fixture-level boundary is not *also* set above `808`
  (or these 12 fixtures are converted to `SET LOCAL`, which still satisfies `MISSING_LOCK_TIMEOUT`'s
  existing either-spelling regex) — breaking a currently-green test.
- `MigrationConventionLintTest.invalidFixtures_triggerEveryRule` (`:90-106`) asserts every `Rule` enum
  value has at least one failing fixture under `src/test/resources/migration-lint/invalid/`. Adding
  `SESSION_SCOPED_LOCK_TIMEOUT` without adding a matching `invalid/` fixture breaks this test
  immediately. A matching `valid/` fixture using `SET LOCAL` correctly is also needed, to prove the new
  rule does not over-fire.

### Tasks

1. Re-verify all ten migrations' current `SET lock_timeout` lines, `MigrationLint`'s current
   `LOCK_TIMEOUT_DIRECTIVE` regex and `isLockTimeoutBoundedAt` scan logic, and the current tip
   migration version directly against HEAD before implementing.
2. Add a new boundary constant to `MigrationLint.java` at the current real-migration tip version,
   following the existing `GRANDFATHER_BASELINE`/`DEFERRED_92_BASELINE` pattern, with its own Javadoc
   that (a) explains why a new constant rather than reusing an existing one, and (b) acknowledges this
   moves against the class's own stated future-collapse direction (design note above) and states why
   that's still correct here.
3. Add a new `Rule` enum member for a plain (non-`LOCAL`, non-sidecar-exempt) `SET lock_timeout` in a
   migration above that boundary, with its own Javadoc following this file's existing per-rule
   documentation style. Include the sidecar (`executeInTransaction=false`) exemption from risk 1 above
   — either an opt-out marker or detection of the `.conf` sidecar file.
4. Fix `isLockTimeoutBoundedAt` (or add an equivalent check) to stop treating a `SET LOCAL` as bounding
   statements after an explicit `COMMIT`/`ROLLBACK` in the same file (risk 2 above) — needed for
   `MISSING_LOCK_TIMEOUT` itself to stay correct once `SET LOCAL` becomes the norm, not just for the
   new rule.
5. Implement the check and wire it into the existing aggregation/reporting mechanism (read the class
   fully first — confirm exactly how `MISSING_LOCK_TIMEOUT` and its siblings are invoked and reported
   before assuming the shape).
6. Update `MigrationConventionLintTest`: add the new boundary to both `lintFixtures` helper overloads
   and every call site that needs it; add a **separate, fixture-level** boundary constant (mirroring
   `FIXTURE_DEFERRED_92_BASELINE`, not reusing it) so the 12 existing `valid/` fixtures using plain
   `SET lock_timeout` are not newly flagged — or convert those 12 fixtures to `SET LOCAL` instead, if
   that proves simpler once the actual class is read; add a new `invalid/` fixture that triggers
   `SESSION_SCOPED_LOCK_TIMEOUT` and a new `valid/` fixture using `SET LOCAL` correctly, so
   `invalidFixtures_triggerEveryRule` keeps passing and the new rule's non-over-firing is proven. New
   unit test(s): a synthetic migration above the boundary using plain `SET lock_timeout` triggers the
   violation; the same content at or below the boundary does not; a synthetic migration above the
   boundary using `SET LOCAL` does not trigger it; a synthetic sidecar-exempt migration using plain
   `SET` above the boundary does not trigger it either. Confirm none of the ten real shipped migrations
   trip the new rule when the full real migration directory is linted.
7. Update `docs/deployment/migration-conventions.md` in **all six** places it currently prescribes
   plain `SET lock_timeout`, not item 7 alone: the pre-flight checklist (`:45`), the rebaseline/
   defensive-use guidance (`:156`, `:176`), item 7 itself (`:189-205` — the rationale: Flyway's
   single-session-per-deploy connection reuse, and why `LOCAL` closes the leak plain `SET` does not,
   plus the sidecar exemption from risk 1 and the grandfather boundary so a reader understands why the
   ten earlier migrations look inconsistent with the now-current rule), the `MISSING_LOCK_TIMEOUT` rule
   reference list (`:378-383`), and the review checklist (`:467`).
8. Re-run `MigrationConventionLintTest` in full against both the real migration directory and the
   fixture directories — zero regressions expected, and the ten real migrations must not newly fail.

---

## AC5 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story
genuinely fixes; annotate `[DECIDED: accepted risk — skillars-deferred-125]` what it deliberately
does not fix; leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-124-... (2026-09-19)`:
   - **Bullet 1** (outbox/DLQ claim-phase guard) — delete outright, fully fixed by AC1.
   - **Bullet 2** (`MAX_RUN_DURATION` sampled only between rows) — **do not delete.** Annotate
     `[DECIDED: accepted risk — skillars-deferred-125]`, citing AC2's own accepted-residual comment
     (the buffer AC2 restores makes this materially less consequential, but does not eliminate it).
   - **Bullet 3** (`STALE_CLAIM_WINDOW` == `lockAtMostFor` zero margin) — delete outright, fully fixed
     by AC2 (widened window + the previously-missing test assertion now passes).
   - **Bullet 4** (`SET` vs `SET LOCAL`) — the bullet's own text (`deferred-work.md:2492`) currently
     reads *"Repo-wide convention (`V144`, `V145`, `V149`, `V150` all do this)"*, which
     `story-review.md` found wrong (ten files, `V140`–`V150` — see AC4). Whichever disposition this
     bullet gets (delete if AC4 ships a genuinely enforced rule; annotate `[DECIDED: accepted risk]`
     if implementation finds enforcement impractical), **do not let the wrong count vanish silently**
     — the `## Last audit` narrative (task 5 below) must record the corrected figure, so a future
     audit does not re-derive the error from this story's own history.
3. Confirm the section header itself survives if any bullet remains under it (bullet 2, annotated not
   deleted) — do not delete the header.
4. Do **not** touch either bullet in the `## Explicitly out of scope (skillars-deferred-123,
   2026-09-18)` section — both the `main."user"` index bullet and the `ModerationSlaMonitorService`
   `[DECIDED]` cross-reference were owner-decided/already-decided to stay untouched; leave both
   byte-for-byte as-is. Add a one-line `## Last audit` narrative note (matching this file's own
   established style) recording that this story re-confirmed **both** still open/decided and still
   correctly out of scope, so a future audit does not have to re-derive that from scratch or wonder
   why only one of the section's two bullets was mentioned.
5. Add a `## Last audit: 2026-09-19 (skillars-deferred-125 dev-story completion)` narrative section, in
   this file's established style, summarizing what closed and what was accepted-and-documented instead
   (including the corrected ten-migration count from task 2's bullet 4), matching the level of detail
   the equivalent skillars-deferred-124 section (`deferred-work.md`, currently around line 2495)
   provides. Also correct the ledger's own `deferred-work.md:1407` bullet text (the 1-7b router-abort
   item) to state the actual Vue Router 4 resolve/reject contract this story's `story-review.md`
   established, rather than leaving its self-flagged "unverified" claim uncorrected even after AC3
   closes the underlying gap.
6. Grep-sweep every file this story touched (`VideoDeletionOutboxProcessor`, `RadarCompositeDlqProcessor`,
   `App.vue`, `useSession.js`, `MainLayout.vue`, `MigrationLint`, `migration-conventions.md`,
   `scheduler-lock-tuning.md`) against the rest of the ledger for any other stale reference this
   story's changes might affect — re-confirm each hit is either unrelated or already correctly
   annotated, per this series' own standard practice.

---

## Dev Notes

**Cross-AC dependencies:** AC1 and AC2 both touch `RadarCompositeDlqProcessor` but are independent
fixes to independent problems (AC1's `try`/`catch` wraps the claim/fetch phase; AC2 changes
`STALE_CLAIM_WINDOW`'s value and Javadoc, and the test file's assertions) — sequence as separate
commits so either can be reverted without the other, mirroring this series' established convention.
AC3 is entirely independent (frontend, different module, different test suite) and can be implemented
in any order relative to AC1/AC2/AC4. AC4 is independent of everything else in this story (a
`MigrationLint`/doc-only change, no production migration added).

**No new Flyway migration in this story.** Confirm this remains true at implementation time — if any
AC's implementation turns out to need one, re-derive the next free version against the actual
`src/main/resources/db/migration/` directory at that time, not any number cited above.

**Testing:** No `mvn verify` locally before push — GitHub CI is the sole full-verification gate for
this project. Run each AC's own targeted test class(es) locally during implementation as needed;
`mvn compile`/`mvn test-compile` are fine, `mvn verify` is not. For the frontend (AC3), run
`npm run test:unit` (= `vitest run`, `package.json:13`) locally; this is not gated by the "no local
verify" convention, which is specific to the backend's Maven `verify` phase.

**Frontend test infrastructure (AC3) — corrected by the `story-review.md` audit: this is NOT new
ground.** Three existing specs already mount a component with a real `createRouter`/
`createMemoryHistory` router installed as a plugin alongside `createTestingPinia`:
`src/layouts/__tests__/MainLayoutSpec.js` (a near-exact template — asserts
`authStore.logout → resetSelfPlayerId → destroySession → router.push('/login')` ordering on a
component whose `handleLogout` is structurally the same shape as `App.vue`'s `handleSessionExpired`),
`src/composables/__tests__/useSessionSpec.js`, and
`src/pages/parent/__tests__/BookingRequestPageSpec.js`. `test/vitest/setup-file.js` already installs
Quasar + vue-i18n globally and explicitly documents that Pinia is **not** installed globally — specs
opt in with `createTestingPinia()`. Naming/location convention is `src/**/__tests__/<Name>Spec.js`
(not `App.spec.js`); environment is `happy-dom` (`vitest.config.mjs`), not jsdom. Expect AC3's test
scaffolding to be close to copying `MainLayoutSpec.js`'s existing setup, not building a new pattern.

**CI context-count ceiling (backend ACs only):** `assert-context-count.sh`'s Spring-context ceiling is
currently 44 (raised by skillars-deferred-124 AC2, `pr-build.yml`'s call site) per that story's own
Dev Notes. AC1's new spy-based tests reuse the *same* Spring context both processors' test classes
already use — `VideoDeletionOutboxProcessorIT`'s **existing** `@MockitoSpyBean
VideoDeletionOutboxRepository outboxRepository` (`:57` — not the `DrillVideoRefRepository` spy at
`:48`, which AC1 does not need) and `RadarCompositeDlqProcessorTest`'s plain-Mockito unit style (no
Spring context at all, `@ExtendWith(MockitoExtension.class)`) — this AC should not need a new Spring
context fork, but confirm against the CI failure message if it does, and bump the ceiling with a dated
justification comment mirroring the existing history in that script if so.

---

## File List (expected — reconcile against the actual final diff before ledger closeout)

**Production code:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC1, AC2)
- `src/frontend/src/App.vue` (AC3)
- `src/frontend/src/composables/useSession.js` (AC3)
- `src/frontend/src/layouts/MainLayout.vue` (AC3)
- `src/frontend/src/utils/sessionRedirect.js` (AC3, new — shared push-or-hard-navigate helper)
- `docs/deployment/migration-conventions.md` (AC4)
- `docs/deployment/scheduler-lock-tuning.md` (AC2 — added by the `story-review.md` audit, not in the
  original draft)

**Tests (backend, `src/test`):**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC1, new isolation test)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
  (AC1 new isolation test; AC2 widened-window assertion + rewritten test-method Javadoc)
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (AC4 — this is the lint engine, not
  production code; it lives under `src/test`)
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` (AC4 — new fixture
  baseline + new tests)
- `src/test/resources/migration-lint/valid/` and `.../invalid/` (AC4 — new fixtures; possibly 12
  existing `valid/` fixtures converted to `SET LOCAL`, see AC4 task 6)

**Tests (frontend):**
- New spec(s) for `App.vue`/`useSession.js`/`MainLayout.vue`/the new `sessionRedirect.js` helper (AC3
  — follow `src/layouts/__tests__/MainLayoutSpec.js`'s naming/location/setup conventions; do not
  create `App.spec.js` or assume jsdom)

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes.md`
  (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-09-19: Story created via `/bmad-create-story`. Mined the freshest same-day code-review deferral
  (`code review of skillars-deferred-124`, 4 bullets) plus one older, still-genuinely-open item
  (`code review (round 2) of 1-7b-session-refresh-rint-contract-fix`'s router-abort re-arm gap),
  identified via a full-file re-audit of `deferred-work.md` (a dedicated cataloguing pass covering all
  ~92 section headers, cross-checked against actual HEAD source, not the ledger's own prose — though,
  per the 2026-09-21 review below, two of the five mined items were in fact carried over from the
  ledger's own prose without independent re-verification, and both turned out to contain errors).
  Three owner decisions taken live with the user (`AskUserQuestion`) before drafting: (1) widen
  `RadarCompositeDlqProcessor.STALE_CLAIM_WINDOW` and add the missing test assertion, accepting the
  narrower per-row-overrun residual as documented risk, rather than leaving values as-is or engineering
  a full per-row timeout; (2) require `SET LOCAL lock_timeout` for all new migrations going forward via
  a doc update plus a new, properly-grandfathered `MigrationLint` rule, rather than accepting the
  current session-scoped convention as-is; (3) keep deferring the `main."user"` cleanup-sweep index —
  no production deploy has happened yet to generate the row-count/`EXPLAIN` evidence the ledger itself
  says is the actual blocker, so this story does not attempt a speculative shape. All line numbers
  verified against `master@77036728` (skillars-deferred-124's merge, PR #212) on 2026-09-19. 4 ACs
  bundled per this project's "do not create small stories" convention, plus the standard AC5 ledger
  closeout.
- 2026-09-21: `story-review.md` senior-dev pre-implementation audit applied. 25 findings (4 High, 11
  Medium, 10 Low, roughly per the review's own severity labels) — every one independently re-verified
  against actual source, the installed `vue-router` package, and the real fixture/test files before
  applying anything. **Zero false positives; all 25 confirmed genuine.** Most significant:
  - **AC3's core premise was false.** Vue Router 4 (installed version 4.6.4, verified directly against
    `node_modules/vue-router/dist/vue-router.mjs`) **resolves** `router.push()`'s promise with a
    `NavigationFailure` object for aborted/cancelled/duplicated navigations — the three failure modes
    the story originally named — and only **rejects** when a navigation guard throws a non-`NavigationFailure`
    error, a path the story never named. The prescribed fix (`.catch()` only) and test (a mocked
    rejecting `push`) would both have shipped green while leaving the actual dominant failure mode
    completely unhandled. Rewrote AC3's fix to handle both the resolved-failure and rejected cases, and
    its test suite to cover both plus the happy path.
  - **AC3's "no existing router-mocking spec" claim was also false.** Three existing specs
    (`MainLayoutSpec.js`, `useSessionSpec.js`, `BookingRequestPageSpec.js`) already mount components
    with a real memory-history router; `MainLayoutSpec.js` is a near-exact template for this exact fix
    shape. Corrected the Dev Notes and AC3's test task accordingly, and corrected the wrong naming
    convention (`App.spec.js` → `*Spec.js` under `__tests__/`) and wrong test environment (jsdom →
    `happy-dom`) the original draft also assumed without checking.
  - **AC3 widened from one call site to three.** The identical unguarded `router.push` exists at
    `useSession.js:110` and `MainLayout.vue:374`, both already `async` functions their callers await —
    fixing all three via one small shared helper is a few lines more than fixing one, and leaving two
    open would repeat exactly how this item stayed open since 2026-09-02 in the first place.
  - **AC4's migration count was wrong by a factor of 2.5** — the original draft's "four migrations"
    (`V144`/`V145`/`V149`/`V150`) was copied verbatim from a ledger bullet that was itself never
    re-verified; the actual count is **ten** (`V140`–`V150`, excluding marker-only `V141`). Corrected
    throughout AC4 and AC5.
  - **AC4's rule design, as originally drafted, would have been actively unsafe and would have broken
    two existing green test suites.** `SET LOCAL` is a Postgres no-op outside a transaction block, and
    this repo documents a "confirmed working" non-transactional `executeInTransaction=false` sidecar
    migration pattern a blanket rule would silently defeat — added a required opt-out/detection.
    `isLockTimeoutBoundedAt`'s existing scan has no transaction-boundary awareness, which would become
    a new false negative once `SET LOCAL` is the norm — added a task to fix it. A new rule bound at the
    real-migration boundary would still flag 12 existing `valid/` test fixtures (a separate,
    parallel fixture-numbering space) and the new `Rule` enum member would break
    `invalidFixtures_triggerEveryRule` without a matching `invalid/` fixture — added explicit tasks for
    both, including the existing `FIXTURE_DEFERRED_92_BASELINE`-style parallel boundary this class
    already has a precedent for. Also corrected AC4's stated rationale for not rewriting the ten
    migrations (was "immutable, impossible to edit," which this project's own skillars-deferred-112
    squash-and-delete precedent contradicts; restated on the real grounds — churn/coordination cost,
    not impossibility) and broadened the doc-update task from one location to the six the doc actually
    prescribes plain `SET` in.
  - **AC2's Javadoc citation was wrong** — `:55-77` is `MAX_RUN_DURATION`'s field Javadoc, not the
    class-level comment, and it (not `STALE_CLAIM_WINDOW`'s own Javadoc, which the original task
    targeted) is where the load-bearing "fixed at the source rather than by widening" sentence lives.
    Both must be revised together. Also added three previously-missed stale cross-references
    (a test-method Javadoc, a sibling class's Javadoc, `scheduler-lock-tuning.md`) and the unstated
    10-minute-to-15-minute crash-recovery-latency trade-off, all surfaced by the review.
  - **AC1's fix was incomplete against its own named failure modes** — two of the three example causes
    for `findClaimedBatch` throwing (connection reset, pool exhaustion) would also break the fix's own
    `releaseClaimed` call; added a guard mirroring this class's own existing
    `handleFailure`-itself-throws pattern. Also removed a cited "no-throw-out-of-`process()`
    convention" that does not exist for this phase (the loop's own catch is a different mechanism at a
    different level) — the task now prescribes a plain rethrow rather than offering "log and return" as
    an equally-valid alternative. Corrected a Dev Notes citation to the wrong spy bean
    (`DrillVideoRefRepository` → the actually-relevant `VideoDeletionOutboxRepository` spy) and added
    explicit `ArgumentCaptor` guidance for the test, since `runId` is generated inside `process()` with
    no a-priori handle for a test to assert against otherwise.
  - Remaining findings (rationale-accuracy notes, a File List miscategorization, softened Provenance
    wording distinguishing genuinely-re-verified bullets from carried-over ones, minor doc-scope
    widenings) applied as noted inline above and in each AC's own text.

  No scope change to the four ACs' core intent, and none of the three original owner decisions were
  reopened — every correction is to premise accuracy, task completeness, and citation correctness.
