# Story: Outbox Claim-Phase Isolation, Radar Stale-Window Margin & Session-Redirect Fixes

**Story Key:** `skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes`
**Epic:** Deferred Work
**Priority:** High (a real outbox/DLQ claim-phase availability gap on both processors, a real
zero-margin scheduler-lock/stale-window relationship on one processor, and a real frontend session-
redirect failure mode reachable at three call sites) — plus a repo-wide migration-convention
hardening and the standard ledger closeout.
**Status:** done
**Created:** 2026-09-19
**Reviewed:** 2026-09-21 (`story-review.md`, senior-dev pre-implementation audit). 25 findings, all 25
independently re-verified against actual source/installed packages before applying anything — **zero
false positives**, every finding confirmed genuine. See the Change Log for the full response.
**Re-reviewed:** 2026-09-21 (`/bmad-code-review` on the completed implementation, four parallel layers).
23 patch findings, all 23 independently re-verified against actual source before being applied — 22
confirmed genuine and fixed, 1 found to already be covered by an existing sanctioned alternative but
fixed anyway. See the `### Review Findings` section and Change Log for the full per-finding response.

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
4. Do **not** change the substance of either bullet in the `## Explicitly out of scope
   (skillars-deferred-123, 2026-09-18)` section — both the `main."user"` index bullet and the
   `ModerationSlaMonitorService` `[DECIDED]` cross-reference were owner-decided/already-decided to
   stay open, and neither disposition changes here. **Correction from the `/bmad-code-review` audit
   (2026-09-21):** the original text below asked to leave both bullets "byte-for-byte as-is" while
   also asking for a re-confirmation to be recorded against each — those two instructions are in
   tension, and the as-implemented resolution (a short `[re-confirmed by skillars-deferred-125,
   2026-09-19: ...]` annotation appended inline to each bullet, rather than reserved for the separate
   `## Last audit` section below) is correct and should be treated as the actual instruction: append
   a one-line re-confirmation annotation to each bullet, in addition to (not instead of) the
   `## Last audit` narrative note (task 5) summarizing that both were re-confirmed.
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

## Tasks / Subtasks

Tracks completion against each AC's own `### Tasks` list above (the authoritative task text);
this section is the dev-story workflow's checkbox tracking layer over it.

- [x] AC1 — Outbox/DLQ claim-phase isolation
  - [x] Re-verified the claim/fetch call sequence against HEAD before implementing (unchanged from
        the story's own citations).
  - [x] `VideoDeletionOutboxProcessor.process()`: wrapped `claimPendingBatch` + `findClaimedBatch`
        in `try`/`catch`, releasing via a nested-guarded `releaseClaimed(runId)` and rethrowing;
        `resetStaleClaimed` left outside the block.
  - [x] Identical fix applied to `RadarCompositeDlqProcessor.process()`.
  - [x] New tests per processor using the existing `@MockitoSpyBean`/`@Mock` repository: capture
        `runId` via `ArgumentCaptor` off `claimPendingBatch`, assert the same value reaches
        `releaseClaimed` on a `findClaimedBatch` failure, plus a happy-path counterpart asserting
        `releaseClaimed` is never invoked.
  - [x] Mutation-checked: live-scripted revert/restore for `VideoDeletionOutboxProcessor` (confirmed
        the new test fails, then passes); `RadarCompositeDlqProcessorTest`'s equivalent was reasoned
        through rather than separately scripted, since the fix is structurally identical — see Dev
        Agent Record for the honest accounting of which checks were executed vs. reasoned about.
  - [x] Re-ran both processors' full test classes — zero regressions.
- [x] AC2 — RadarCompositeDlqProcessor stale-window margin
  - [x] Re-verified current values/line numbers against HEAD.
  - [x] `STALE_CLAIM_WINDOW` widened `Duration.ofMinutes(10)` → `Duration.ofMinutes(15)`.
  - [x] Rewrote both `MAX_RUN_DURATION`'s and `STALE_CLAIM_WINDOW`'s field Javadocs recording why
        the prior decision was revised, cross-referencing this story, plus the crash-recovery-latency
        trade-off sentence.
  - [x] Added the missing `lockAtMostFor < staleWindow` assertion to
        `runtimeBudget_staysStrictlyInsideLock`, with an `.as(...)` explanation; rewrote the test
        method's own Javadoc.
  - [x] Updated the two other stale cross-references: `VideoDeletionOutboxProcessor.java`'s Javadoc
        and `docs/deployment/scheduler-lock-tuning.md`'s radar row (added to File List).
  - [x] Added the accepted-residual comment near `MAX_RUN_DURATION`.
  - [x] Mutation-checked: reverted the widened value, confirmed the new assertion fails; restored,
        confirmed it passes.
  - [x] Re-ran `RadarCompositeDlqProcessorTest` in full — zero regressions; confirmed
        `RadarCompositeDlqRepositoryIT` does not hardcode the old value (it does not — explicit
        `Instant` args throughout).
- [x] AC3 — Session-expiry redirect hard-navigation fallback
  - [x] Re-verified `App.vue`, `useSession.js`, `MainLayout.vue` all still end with an unguarded
        `router.push`.
  - [x] Added `src/frontend/src/utils/sessionRedirect.js` — pushes to `/login` with the
        `redirect`/`expired` query, inspects the settled result for a resolved `NavigationFailure`
        (via `vue-router`'s `isNavigationFailure`) and a rejection, falls back to a hard
        `window.location` navigation (`encodeURIComponent`-ed), with a one-shot re-entrancy guard.
  - [x] Wired the helper into all three call sites, preserving each function's own existing teardown
        sequence.
  - [x] New tests: a dedicated `sessionRedirectSpec.js` (all three outcome cases + the re-entrancy
        guard, against a real `createRouter`/`createMemoryHistory` router with real navigation
        guards) plus one integration-style case per call site — a new `AppSpec.js` (App.vue had no
        prior spec) and updated assertions in the existing `MainLayoutSpec.js`/`useSessionSpec.js`
        (their `router.push('/login')` assertions now expect the query-object push shape).
  - [x] Mutation-checked: live-scripted for the shared helper (three ways) and `App.vue`'s call site;
        `MainLayout.vue`'s and `useSession.js`'s equivalent revert was reasoned through rather than
        separately scripted, since their wiring is line-for-line identical to `App.vue`'s and both are
        covered by their own updated specs' passing assertions — see Dev Agent Record.
  - [x] Ran the full frontend suite (`npm run test:unit`) — 114/114 green, zero regressions. PR will
        carry the `frontend-tests` label.
- [x] AC4 — Migration convention: `SET LOCAL lock_timeout`
  - [x] Re-verified all ten real migrations (`V140`–`V150`, excluding marker-only `V141`), the
        `LOCK_TIMEOUT_DIRECTIVE` regex, `isLockTimeoutBoundedAt`, and the tip version (`V150`).
  - [x] Added `MigrationLint.SESSION_SCOPED_LOCK_TIMEOUT_BASELINE` (150), a third boundary constant.
  - [x] Added `Rule.SESSION_SCOPED_LOCK_TIMEOUT` plus `lintSessionScopedLockTimeout`, with the
        `executeInTransaction=false` sidecar exemption (`hasNonTransactionalSidecar`) and a
        `-- migration-lint: allow-session-lock-timeout <reason>` statement-level opt-out.
  - [x] Fixed `isLockTimeoutBoundedAt` to stop treating a `SET LOCAL` as bounding statements after an
        explicit mid-file `COMMIT`/`ROLLBACK` (a plain `SET`'s bound correctly survives one).
  - [x] Threaded the new baseline through all three `lint()` overloads (added a fourth) and
        `lintFile`.
  - [x] `MigrationConventionLintTest`: added `FIXTURE_SESSION_SCOPED_LOCK_TIMEOUT_BASELINE` (840) and
        a third `lintFixtures` overload; new `valid/V850__session_local_lock_timeout.sql` and
        `invalid/V930__session_scoped_lock_timeout.sql` fixtures; new unit tests for above/at/`SET
        LOCAL`/sidecar-exempt boundary behavior and the `COMMIT`/`ROLLBACK` reset fix (both
        directions); fixed one existing test (`dropReferenceScan_isLoadBearing`) whose fixture
        (`V911`) incidentally uses a plain `SET` and would otherwise pick up an unrelated new
        violation.
  - [x] Updated `docs/deployment/migration-conventions.md` in all six places, not just item 7.
  - [x] Re-ran `MigrationConventionLintTest` in full (19/19) — zero regressions; confirmed the real
        migration directory still lints clean (`realMigrations_aboveBaseline_areClean`).
- [x] AC5 — Ledger closeout
  - [x] Re-verified the actual diff against the File List before touching the ledger.
  - [x] Under `## Deferred from: code review of skillars-deferred-124…`: bullet 1 deleted (AC1);
        bullet 2 annotated `[DECIDED: accepted risk — skillars-deferred-125]` (not deleted); bullet 3
        deleted (AC2); bullet 4 deleted (AC4), with the corrected ten-migration count recorded in the
        new Last-audit narrative rather than left to vanish silently.
  - [x] Section header retained (bullet 2 survives under it); section intro sentence updated to
        reflect 1-of-4 remaining.
  - [x] `## Explicitly out of scope (skillars-deferred-123, 2026-09-18)` — both bullets left
        byte-for-byte as-is except a one-line re-confirmation annotation appended to each.
  - [x] Added `## Last audit: 2026-09-19 (skillars-deferred-125 dev-story completion)`.
  - [x] Corrected `deferred-work.md`'s round-2 1-7b router-abort bullet to state the actual Vue
        Router 4 resolve/reject contract and record that AC3 closed the underlying gap at the router
        layer.
  - [x] Grep-swept every touched file/class against the rest of the ledger — no other stale
        reference found (one historical `## Last audit` narrative mention, correctly left as
        historical record per the ledger's own documented convention).

---


### Review Findings

_`/bmad-code-review` 2026-09-21 — four parallel layers (Blind Hunter, Edge Case Hunter, Acceptance
Auditor, `/txn-and-concurrency-audit`). 45 raw findings merged to 29: 23 patch, 4 deferred,
2 dismissed. The single decision-needed item was resolved by the owner on 2026-09-21 (option 1)
and folded into the patch list. No layer failed._

_All 23 patch findings below were independently re-verified against the actual source before being
applied (per this project's convention) — 22 confirmed genuine and fixed; 1 (the `addSuppressed`
finding) was already covered by an existing, story-review.md-sanctioned alternative (log-and-swallow)
but fixed anyway for cheap extra observability. Fixed 2026-09-21; see the story's Change Log for the
full response and File List for the touched files. Backend: `MigrationConventionLintTest` 19→30 green
(11 new unit tests, 2 new fixture pairs), `VideoDeletionOutboxProcessorIT` 13/13,
`RadarCompositeDlqProcessorTest` 12/12. Frontend: 114→118 green across 14 files._

- [x] [Review][Patch] Sidecar migrations must carry a trailing `RESET lock_timeout` [src/test/java/com/softropic/skillars/db/MigrationLint.java:658-660] — **owner decision 2026-09-21 (option 1 of 3, see `optionsAndRecommendations.md`): keep the `executeInTransaction=false` exemption from `SESSION_SCOPED_LOCK_TIMEOUT` (plain `SET` genuinely is the only form that works with no enclosing transaction), but add a compensating check requiring a trailing `RESET lock_timeout` whenever a sidecar migration sets one** — the hand-rolled equivalent of what `SET LOCAL` does automatically at `COMMIT`. Without it the rule is disabled exactly where its failure mode is *guaranteed* rather than merely possible: a non-transactional migration has no `COMMIT` to reset anything, so the setting survives into every later migration on Flyway's reused JDBC session. Composes with the existing rules — `isLockTimeoutBoundedAt` already has a `RESET` branch, and a `RESET` placed too early is caught by `MISSING_LOCK_TIMEOUT`. Options 2 (require the marker instead) and 3 (accept and document) were rejected as labelling changes that leave the leak intact. Keep `allow-session-lock-timeout` available as a genuine escape hatch for a sidecar migration that legitimately cannot reset. Needs 2 new fixtures (a `valid/` sidecar with `RESET`, an `invalid/` sidecar without). Raised by blind+edge. **Fixed:** new `Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET` + `lintSidecarLockTimeoutReset`, gated on the same baseline as `SESSION_SCOPED_LOCK_TIMEOUT` with the opposite sidecar condition; new `valid/V851__sidecar_lock_timeout_reset.sql(.conf)` and `invalid/V931__sidecar_lock_timeout_not_reset.sql(.conf)` fixtures plus 3 dedicated unit tests (fires, satisfied by RESET, opt-out marker suppresses).

- [x] [Review][Patch] Deliberate logout shows a false "Your session has expired" banner and hijacks the next login's redirect [src/frontend/src/utils/sessionRedirect.js:33-38, src/frontend/src/composables/useSession.js:111, src/frontend/src/layouts/MainLayout.vue:375] — **Fixed:** `pushLoginOrHardNavigate(router, { expired })` now takes an options object defaulting `expired` to `false`; only `App.vue`'s genuine session-expiry call site passes `{ expired: true }`. Covered by new/updated cases in `sessionRedirectSpec.js`, `useSessionSpec.js`, `MainLayoutSpec.js`.
- [x] [Review][Patch] Hard-navigation fallback is incompatible with this app's `hash` router mode — `/login?...` is not the SPA route and `route.query.expired` never sees the param [src/frontend/src/utils/sessionRedirect.js:40-47] — **Fixed:** `hardNavigateToLogin` now builds the href via `router.resolve({ path, query }).href` (the router's own `history.createHref`, correct for hash/history/memory alike) instead of a hand-built `/login?...` string, followed by an explicit `window.location.reload()` — a hash-only URL change does not by itself force a real page reload/unload, which would have silently no-opped the whole fallback while still permanently latching `hardNavigated`.
- [x] [Review][Patch] `SET SESSION lock_timeout` evades the new rule entirely — regex requires `lock_timeout` immediately after `SET` [src/test/java/com/softropic/skillars/db/MigrationLint.java:359] — **Fixed:** `LOCK_TIMEOUT_DIRECTIVE` and `SESSION_SCOPED_SET_LOCK_TIMEOUT` both now accept an optional `SESSION` keyword (session-scoped, same as a bare `SET`). New test `setSessionLockTimeout_boundsAndIsSessionScoped`.
- [x] [Review][Patch] `SET LOCAL` inside an `executeInTransaction=false` migration silences `MISSING_LOCK_TIMEOUT`, though it is a Postgres no-op there — the story's own new test at `MigrationConventionLintTest.java:616-635` is an instance of the broken pattern [src/test/java/com/softropic/skillars/db/MigrationLint.java:1048] — **Fixed:** `isLockTimeoutBoundedAt` now takes a `nonTransactionalSidecar` flag (threaded from `lintFile`'s existing `hasNonTransactionalSidecar` check) and ignores `SET LOCAL` entirely when true. The confounding original test was split: the COMMIT-boundary tests no longer carry a sidecar `.conf`, and a new dedicated test (`missingLockTimeout_setLocalIsIgnoredInsideANonTransactionalSidecar`) covers the sidecar case on its own.
- [x] [Review][Patch] `isLockTimeoutBoundedAt` collapses session and LOCAL scope into one flag — a plain `SET` followed by a `SET LOCAL` then `COMMIT` yields a false `MISSING_LOCK_TIMEOUT` build failure [src/test/java/com/softropic/skillars/db/MigrationLint.java:1088-1103] — **Fixed:** rewritten to track `sessionBounded` and `localActive`/`localBounded` independently — a transaction boundary now clears only the local override, never the session-scoped value. New test `missingLockTimeout_plainSetUnderASetLocalSurvivesTheLocalsCommit`.
- [x] [Review][Patch] `TRANSACTION_BOUNDARY` is a bare word match — fires on `ON COMMIT DROP`, string literals and dollar-quoted bodies; misses `END`/`ABORT` [src/test/java/com/softropic/skillars/db/MigrationLint.java:367] — **Fixed, with one correction:** the "dollar-quoted bodies" half was a false positive on re-verification — `stripComments` already blanks dollar-quoted bodies entirely before this pattern ever sees them (confirmed by tracing the one call site). The genuine parts were fixed: added a negative lookbehind excluding `ON COMMIT` (a `CREATE TEMP TABLE ... ON COMMIT DROP` clause, not a real boundary), and added `END`/`ABORT` as recognised synonyms. New tests `transactionBoundary_onCommitDropIsNotARealBoundary`, `transactionBoundary_endIsRecognisedAsACommitSynonym`.
- [x] [Review][Patch] The `MAX_RUN_DURATION` bail-out's `releaseClaimed` is still an unguarded write — same stranded-claim class AC1 hardened, on the path most likely to fail [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java:198, src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java:190] — **Fixed:** wrapped in the same try/catch-and-log-ERROR shape as AC1's own claim-phase guard, in both processors; a failure here now logs and continues rather than propagating an exception out of a deliberate, benign self-termination.
- [x] [Review][Patch] The claim-release IT asserts the mock was called, not that the row returned to `PENDING` — every other test in the class asserts real DB state [src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java:429-441] — **Fixed:** added `outboxRepository.findById(row.getId())` assertions on `status == "PENDING"` and `claimedBy == null` alongside the existing mock-invocation verification.
- [x] [Review][Patch] `R__` repeatable migrations never reach the new rule — `lintFile` returns before the statement loop, and repeatables share the same Flyway deploy session [src/test/java/com/softropic/skillars/db/MigrationLint.java:609-611] — **Fixed:** `lintRepeatable` now also calls `lintSessionScopedLockTimeout`/`lintSidecarLockTimeoutReset`, unconditionally (no baseline gate, matching this method's existing `NO_ORDERING_CHECK` convention — a repeatable always re-runs at HEAD). Required converting the pre-existing `valid/R__repeatable_drop_optout.sql` fixture's plain `SET` to `SET LOCAL` (it still satisfies `MISSING_LOCK_TIMEOUT`'s either-spelling regex). New test `repeatable_plainSetLockTimeout_trips_sessionScopedLockTimeout`.
- [x] [Review][Patch] `hasNonTransactionalSidecar` uses a raw substring match — misses `executeInTransaction = false` (spaces) and `=FALSE`, and matches a commented-out directive [src/test/java/com/softropic/skillars/db/MigrationLint.java:1228] — **Fixed:** now parses the `.conf` sidecar line by line, skipping Java-properties-style `#`/`!` comment lines, with a whitespace/case-tolerant regex on the directive itself. New test `nonTransactionalSidecar_isWhitespaceCaseToleranteAndIgnoresComments`.
- [x] [Review][Patch] `hardNavigated` latch is set before the navigation is attempted and never resets in production — a suppressed assignment permanently disables the fallback [src/frontend/src/utils/sessionRedirect.js:41-46] — **Fixed as part of the hash-router-mode fix above**: the explicit `window.location.reload()` now forces a genuine reload regardless of whether the href change was hash-only, so the fallback actually runs rather than silently no-opping while the latch stays permanently set.
- [x] [Review][Patch] `isNavigationFailure` with no type filter treats `NAVIGATION_DUPLICATED`/`NAVIGATION_CANCELLED` as "did not land", forcing a needless full reload [src/frontend/src/utils/sessionRedirect.js:62-66] — **Fixed:** filtered to `NavigationFailureType.aborted | NavigationFailureType.cancelled`, excluding `duplicated`. In practice this is defense in depth: the new "already on /login" guard (below) intercepts the only reachable duplicated case before `router.push` is even called — documented as such in `sessionRedirectSpec.js` rather than left unexplained.
- [x] [Review][Patch] `.then()/.catch()` chaining violates `project-context.md`'s "use async/await, avoid .then()" rule (also `story-review.md` F1's own recommendation) [src/frontend/src/utils/sessionRedirect.js:60-69] — **Fixed:** rewritten as `async`/`try`/`catch`.
- [x] [Review][Patch] AC5's "leave both bullets byte-for-byte as-is" was violated — both out-of-scope bullets carry appended re-confirmation annotations [_bmad-output/implementation-artifacts/deferred-work.md:2448-2449] — **Fixed:** the ledger content itself is correct and unchanged; AC5 task 4's own wording (which asked for both "byte-for-byte" *and* a recorded re-confirmation — an internal contradiction) was corrected to state the as-implemented resolution as the actual instruction.
- [x] [Review][Patch] "Mutation-checked by hand" is overclaimed — the Dev Agent Record says three of five checks were reasoned about, not executed; one sentence is also garbled [story file:605-606, :639; sprint-status.yaml:2] — **Fixed:** the two Tasks/Subtasks checklist bullets (AC1, AC3) and the `sprint-status.yaml` summary now name which halves were live-scripted vs. reasoned through; the garbled Debug Log sentence was rewritten.
- [x] [Review][Patch] AC3 task 4 unsatisfied at two of three call sites — `useSessionSpec.js` and `MainLayoutSpec.js` only had an assertion shape swapped; neither gained a fallback-path case [src/frontend/src/layouts/__tests__/MainLayoutSpec.js:105-113, src/frontend/src/composables/__tests__/useSessionSpec.js:85-93] — **Fixed:** added a genuine fallback-path integration test to each (a `router.beforeEach` guard blocking `/login`, asserting the hard-nav fallback fires from that call site's own teardown), mirroring `AppSpec.js`'s existing one.
- [x] [Review][Patch] `scheduler-lock-tuning.md` radar row says "restored to a real 15-minute buffer" — the buffer is 5 minutes (`PT10M` lock vs 15m window) [docs/deployment/scheduler-lock-tuning.md:77] — **Fixed.**
- [x] [Review][Patch] `migration-conventions.md` rule 6 prescribes `SET LOCAL` for backfills in its opening sentence and exempts that exact case in its closing paragraph [docs/deployment/migration-conventions.md:158-171] — **Fixed:** opening sentence now scoped explicitly to the default single-transaction case; the sidecar carve-out cross-references the new `SIDECAR_LOCK_TIMEOUT_NOT_RESET` rule.
- [x] [Review][Patch] `MainLayout.vue`'s teardown-ordering comment still cites `router.push('/login')` [src/frontend/src/layouts/MainLayout.vue:355] — **Fixed:** now says `pushLoginOrHardNavigate`.
- [x] [Review][Patch] `SET LOCAL lock_timeout = DEFAULT` is treated as a bound, but Postgres's default is `0` (wait forever) [src/test/java/com/softropic/skillars/db/MigrationLint.java:1108-1114] — **Fixed:** `isZeroTimeout` now treats `DEFAULT` (case-insensitive) as zero/unbounded. New test `lockTimeoutDefault_isTreatedAsUnbounded`.
- [x] [Review][Patch] No "already on /login" guard — a second `session:expired` produces a self-referential `redirect` param [src/frontend/src/utils/sessionRedirect.js:33-38] — **Fixed:** `pushLoginOrHardNavigate` now returns immediately (no-op) if `router.currentRoute.value.path === '/login'`.
- [x] [Review][Patch] The claim-phase catch discards the inner failure instead of `e.addSuppressed(inner)`, so alerting sees a clean failure with no sign rows are stranded [src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java:175-181] — **Assessed as an enhancement over a bug, then applied anyway.** The current shape (log at ERROR, don't attach) is exactly the alternative `story-review.md` itself sanctioned ("a nested try/catch logging at ERROR" — see AC1's Verified-correct section) and mirrors this class's own pre-existing `handleFailure` inner-guard shape; the inner failure is logged, not silently dropped. Still applied `e.addSuppressed(inner)` in both processors' claim-phase catches for the cheap extra observability (the suppressed exception now also appears in any tool that captures `e` directly, e.g. an exception tracker), at zero behavior-change risk since `e` is rethrown either way.

- [x] [Review][Defer] Stale-claim window is denominated in per-instance app clocks while ShedLock uses `.usingDbTime()` — clock skew lets one instance steal another's in-flight rows [src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java:157] — deferred, pre-existing
- [x] [Review][Defer] No `statement_timeout`/`lock_timeout` on application connections, so radar's per-row cost is genuinely unbounded and the 2-minute lock margin is probabilistic [src/main/resources/application.yaml:109] — deferred, pre-existing
- [x] [Review][Defer] ShedLock unlock is guarded by `locked_by` = hostname only, so two JVMs on one host can release each other's lock [src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:22-29] — deferred, pre-existing
- [x] [Review][Defer] `boot/axios.js`'s own 401 fallback has the identical hash-mode defect the new helper copied from it [src/frontend/src/boot/axios.js:166] — deferred, pre-existing
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

## Dev Agent Record

### Implementation Plan

Implemented AC1 → AC2 → AC3 → AC4 → AC5 in order, as separate logical changes (mirroring this
series' "sequence as separate commits" convention from Dev Notes above), red-green-refactor per AC:
write/adjust the failing test first where the fix was test-visible, confirm it failed for the right
reason, implement, confirm green, then mutation-check by hand.

### Debug Log

- **AC1.** No surprises against the story's own citations — the claim/fetch phase genuinely had zero
  exception handling in both processors. Added `ArgumentCaptor<UUID>` off `claimPendingBatch`'s own
  second argument in both new tests, since `runId` is generated inside `process()` with no a-priori
  handle. Mutation-checked by hand: reverted the `try`/`catch` in `VideoDeletionOutboxProcessor` (via
  a scripted patch/restore), re-ran the two new tests — `process_findClaimedBatchThrows_...` failed
  with "Wanted but not invoked: releaseClaimed(...)" as expected; restored, both tests green. Did NOT
  separately live-script the identical revert for `RadarCompositeDlqProcessorTest`'s Mockito-mock
  equivalent — reasoned through instead (compile-time reasoning + the full suite run substituting for
  a live revert), since the fix is structurally identical and the unit test's own
  `verify(dlqRepository).releaseClaimed(...)` assertion is the same shape. Recorded here plainly
  rather than folded into "mutation-checked by hand both ways" (Tasks/Subtasks checklist's original
  wording) — that phrasing overclaimed, per the `/bmad-code-review` finding, and was corrected there.
- **AC2.** Mutation-checked by hand: reverted `STALE_CLAIM_WINDOW` to `Duration.ofMinutes(10)`,
  re-ran `runtimeBudget_staysStrictlyInsideLock` alone — failed on the new
  `lockAtMostFor < staleWindow` assertion ("Expecting actual: 10M to be less than: 10M") exactly as
  expected; restored, full `RadarCompositeDlqProcessorTest` class green (12/12).
- **AC3.** The story-review.md correction was verified independently again during implementation,
  not just trusted from the story text: `node_modules/vue-router/dist/vue-router.mjs`'s
  `isNavigationFailure` genuinely distinguishes a resolved `NavigationFailure` from a rejection, and
  the installed version is 4.6.4 per `package.json`. Chose real `createRouter`/`createMemoryHistory`
  routers with real navigation guards (returning `false` / throwing) for `sessionRedirectSpec.js`
  rather than a hand-mocked `router.push`, so the resolved-`NavigationFailure` case is exercised as
  vue-router itself actually produces it. happy-dom (not jsdom) allowed direct, real
  `window.location.href` assignment/readback in tests — confirmed empirically before writing the
  assertions, no stubbing needed. Mutation-checked three ways: (1) removed the
  `isNavigationFailure(failure)` branch from `sessionRedirect.js` — the "guard aborts" test failed as
  expected (window.location never changed); (2) reverted `App.vue` to a bare `router.push(...)` — the
  new `AppSpec.js` fallback test failed as expected. (3) `MainLayout.vue`'s and `useSession.js`'s
  equivalent revert was NOT separately live-scripted — reasoned through instead, since their wiring is
  line-for-line identical to `App.vue`'s, and both are covered by the existing/updated specs' passing
  assertions on the new push shape. All mutations restored and re-confirmed green.
  Full frontend suite (`npm run test:unit`) run at the end: 114/114 green across 14 files, zero
  regressions.
- **AC4.** The most involved AC. Confirmed the real count directly
  (`grep -rln "^SET lock_timeout" src/main/resources/db/migration/`) before writing anything: ten
  files, `V140`–`V150` excluding `V141`. Threading a fourth parameter through `MigrationLint.lint`'s
  three existing overloads (plus `lintFile`) without breaking any existing call site required adding
  a new innermost overload rather than changing an existing signature — mirrors the same shape
  `deferred92Baseline` used when it was added. Hit one live regression while building the fixture
  baseline: `MigrationConventionLintTest.dropReferenceScan_isLoadBearing` copies the real
  `V911__drop_column_marker_but_live_reference.sql` fixture (which incidentally contains a plain
  `SET lock_timeout`, unrelated to what that test actually exercises) into a temp dir and calls
  `MigrationLint.lint` directly with the 5-arg overload — my new baseline defaulted to the REAL
  `SESSION_SCOPED_LOCK_TIMEOUT_BASELINE` (150) there, and V911 > 150, so it picked up a new,
  unrelated `SESSION_SCOPED_LOCK_TIMEOUT` violation that broke that test's `isEmpty()` assertion.
  Fixed by passing an explicit, locally-appropriate baseline (911) at that one call site rather than
  widening the shared fixture constant, since raising the shared constant enough to cover V911 would
  have also grandfathered the dedicated new V930 fixture this AC needed to prove the rule fires at
  all. Mutation-checked by disabling the `lintSessionScopedLockTimeout` call site in `lintFile`
  entirely: both the dedicated new test and `invalidFixtures_triggerEveryRule` (which asserts every
  `Rule` enum value has a triggering fixture) failed as expected; restored, `MigrationConventionLintTest`
  19/19 green. Confirmed `realMigrations_aboveBaseline_areClean` stays green with the real migration
  tree (proving the ten real migrations do not trip the new rule).
- **AC5.** Re-verified the actual final diff (`git status --short`) against this story's own File
  List before touching the ledger, per the AC's own first task.

### Completion Notes

All 4 ACs + AC5 ledger closeout implemented and independently verified:

- **AC1** — both outbox/DLQ processors now release a stranded claim on any exception from their
  claim/fetch phase and rethrow (never a bare `try`/`finally`). New `ArgumentCaptor`-based tests in
  both `VideoDeletionOutboxProcessorIT` (13/13 green) and `RadarCompositeDlqProcessorTest` (part of
  its 12/12) prove the released `runId` is genuinely this run's own, not a bare `any()` match.
- **AC2** — `RadarCompositeDlqProcessor.STALE_CLAIM_WINDOW` widened 10m→15m, restoring the same
  5-minute buffer amount `VideoDeletionOutboxProcessor` keeps above its own lock. Both field
  Javadocs, the test-method Javadoc, `VideoDeletionOutboxProcessor`'s stale cross-reference, and
  `scheduler-lock-tuning.md`'s radar row all rewritten. New `lockAtMostFor < staleWindow` assertion
  added and mutation-checked.
- **AC3** — a shared `sessionRedirect.js` helper now handles both ways `router.push()` can fail to
  land (a resolved `NavigationFailure` — the dominant real case — and a rejection), falling back to a
  hard navigation, at all three call sites sharing the identical unguarded-push bug
  (`App.vue`, `useSession.js`, `MainLayout.vue`). `sessionManager.js` deliberately untouched, per the
  story's own explicit scoping.
- **AC4** — new `MigrationLint.Rule.SESSION_SCOPED_LOCK_TIMEOUT`, grandfathering the ten real
  `V140`–`V150` migrations via a new `SESSION_SCOPED_LOCK_TIMEOUT_BASELINE` boundary, with the
  `executeInTransaction=false` sidecar exemption and the `isLockTimeoutBoundedAt`
  `COMMIT`/`ROLLBACK`-awareness fix both story-review.md flagged as required. Doc updated in all six
  places.
- **AC5** — ledger closeout applied exactly per the story's own disposition: 3 bullets deleted, 1
  annotated `[DECIDED: accepted risk]`, both "Explicitly out of scope" bullets re-confirmed and left
  as-is, the 1-7b router-abort bullet corrected, and a new `## Last audit` narrative added.

**Test summary:** backend — `VideoDeletionOutboxProcessorIT` 13/13, `RadarCompositeDlqProcessorTest`
12/12, `MigrationConventionLintTest` 19/19, all run together with zero regressions; a broader sweep
across `platform.video.service`, `platform.development.service`, and `db` re-run together for a final
regression check (see Change Log for the outcome). Frontend — `npm run test:unit` 114/114 across 14
files. No `mvn verify` run locally, per this project's established convention — GitHub CI is the sole
full-verification gate; `mvn test-compile`/targeted `mvn test -Dtest=...` runs were used throughout.

---

## File List (reconciled against the actual final diff)

**Production code:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessor.java`
  (AC1; `/bmad-code-review` fix — `MAX_RUN_DURATION` bail-out's `releaseClaimed` now guarded, and the
  claim-phase catch's inner failure now `addSuppressed` onto the rethrown exception)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
  (AC1, AC2; same two `/bmad-code-review` fixes as the video sibling)
- `src/frontend/src/App.vue` (AC3; `/bmad-code-review` fix — now passes `{ expired: true }`)
- `src/frontend/src/composables/useSession.js` (AC3)
- `src/frontend/src/layouts/MainLayout.vue` (AC3; `/bmad-code-review` fix — stale teardown-ordering
  comment corrected)
- `src/frontend/src/utils/sessionRedirect.js` (AC3, new — shared push-or-hard-navigate helper.
  `/bmad-code-review` fix pass: `expired` option, router-resolve-based hash-mode-safe hard nav +
  explicit `reload()`, `NavigationFailureType` filter, already-on-`/login` guard, async/await rewrite —
  see the file's own header comment for the full list)
- `docs/deployment/migration-conventions.md` (AC4; `/bmad-code-review` fix — rule 6's `SET LOCAL`/
  sidecar contradiction resolved, rule 7's sidecar exemption now cross-references the RESET requirement)
- `docs/deployment/scheduler-lock-tuning.md` (AC2 — added by the `story-review.md` audit, not in the
  original draft. `/bmad-code-review` fix — "15-minute buffer" corrected to the actual 5-minute margin)

**Tests (backend, `src/test`):**
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java`
  (AC1, new isolation tests; `/bmad-code-review` fix — the claim-release test now also asserts real DB
  state, not just the mock invocation)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
  (AC1 new isolation tests; AC2 widened-window assertion + rewritten test-method Javadoc)
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (AC4 — this is the lint engine, not
  production code; it lives under `src/test`. `/bmad-code-review` fix pass: new
  `Rule.SIDECAR_LOCK_TIMEOUT_NOT_RESET` + `lintSidecarLockTimeoutReset`; `SET SESSION` regex support;
  `isLockTimeoutBoundedAt` session/local scope tracked independently + sidecar-aware; `TRANSACTION_BOUNDARY`
  excludes `ON COMMIT`, adds `END`/`ABORT`; `isZeroTimeout` treats `DEFAULT` as unbounded;
  `hasNonTransactionalSidecar` rewritten as a whitespace/case-tolerant, comment-aware line parser;
  `lintRepeatable` now also runs the session-scoped/sidecar-reset checks)
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java` (AC4 — new fixture
  baseline + new tests; one existing test's call site adjusted, see Dev Agent Record.
  `/bmad-code-review` fix pass: 11 new unit tests covering every fix above; the two pre-existing
  COMMIT-boundary tests had their confounding sidecar `.conf` removed)
- `src/test/resources/migration-lint/valid/V850__session_local_lock_timeout.sql` (AC4, new fixture)
- `src/test/resources/migration-lint/invalid/V930__session_scoped_lock_timeout.sql` (AC4, new fixture)
- `src/test/resources/migration-lint/valid/V851__sidecar_lock_timeout_reset.sql(.conf)` (`/bmad-code-review`
  fix, new fixture pair — proves a trailing `RESET` satisfies `SIDECAR_LOCK_TIMEOUT_NOT_RESET`)
- `src/test/resources/migration-lint/invalid/V931__sidecar_lock_timeout_not_reset.sql(.conf)`
  (`/bmad-code-review` fix, new fixture pair — proves the rule fires with no trailing `RESET`)
- `src/test/resources/migration-lint/valid/R__repeatable_drop_optout.sql` (`/bmad-code-review` fix —
  plain `SET` converted to `SET LOCAL`, needed once repeatables became subject to
  `SESSION_SCOPED_LOCK_TIMEOUT`)

**Tests (frontend):**
- `src/frontend/src/utils/__tests__/sessionRedirectSpec.js` (AC3, new — the shared helper's own
  coverage. `/bmad-code-review` fix pass: rewritten to push into the router itself rather than stub
  `window.history.pushState` — see the file's own header comment — plus new `expired`-flag and
  already-on-`/login` cases)
- `src/frontend/src/__tests__/AppSpec.js` (AC3, new — `App.vue` had no prior spec)
- `src/frontend/src/layouts/__tests__/MainLayoutSpec.js` (AC3 — existing `router.push('/login')`
  assertions updated to the new query-object push shape. `/bmad-code-review` fix: assertion corrected
  to expect no `expired` param on a deliberate logout; added a genuine fallback-path integration test)
- `src/frontend/src/composables/__tests__/useSessionSpec.js` (AC3 — same assertion update.
  `/bmad-code-review` fix: same `expired`-param correction; added a genuine fallback-path integration
  test)

**Documentation / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC5)
- `_bmad-output/implementation-artifacts/skillars-deferred-125-outbox-claim-isolation-radar-window-margin-and-session-redirect-fixes.md`
  (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/optionsAndRecommendations.md` — **deleted.** Scratch analysis
  for the sidecar-RESET decision; its own header said to delete it once the decision was taken. The
  owner decision it presented (option 1) is now fully recorded in this story's Review Findings section
  above, so nothing is lost.

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
- 2026-09-21: `/bmad-dev-story` implementation complete, status → review. All 4 ACs + AC5 ledger
  closeout implemented exactly as directed by the `story-review.md`-corrected text above, with two
  small implementation-time adjustments (both consistent with the story's own instructions, not scope
  changes): (1) AC4's fixture-level `lintFixtures` threading needed a third overload, not the second
  the story's task list anticipated, plus a one-line explicit-baseline fix to one pre-existing test
  (`dropReferenceScan_isLoadBearing`) whose fixture incidentally contains a plain `SET lock_timeout`
  unrelated to what it tests; (2) AC3's "once against the shared helper directly plus one
  integration-style case per call site" was resolved as: full coverage on the shared helper
  (`sessionRedirectSpec.js`, all three outcome cases + the re-entrancy guard) plus one dedicated
  integration spec for `App.vue` (which had no prior spec) and updated assertions on the two already-
  covered call sites (`MainLayoutSpec.js`, `useSessionSpec.js`). See Dev Agent Record above for the
  full implementation notes, including the two mutation-check chains and the one live regression
  found and fixed during AC4. 44 backend targeted tests green
  (`VideoDeletionOutboxProcessorIT` 13, `RadarCompositeDlqProcessorTest` 12, `MigrationConventionLintTest`
  19) plus a broader touched-package regression sweep, and 114 frontend tests green across 14 files
  (`npm run test:unit`) — zero regressions throughout. No `mvn verify` run locally, per this project's
  established convention.
- 2026-09-21: `/bmad-code-review` (four parallel layers — Blind Hunter, Edge Case Hunter, Acceptance
  Auditor, `/txn-and-concurrency-audit`) ran against the completed implementation. 45 raw findings
  merged to 29: 23 patch, 4 deferred (pre-existing, out of this story's scope), 2 dismissed. One
  decision-needed item (the `SESSION_SCOPED_LOCK_TIMEOUT` sidecar exemption's own leak — see
  `optionsAndRecommendations.md`) was resolved by the owner same-day (option 1: keep the exemption, add
  a compensating `RESET lock_timeout` requirement). All 23 patch findings were independently
  re-verified against actual source before being applied — 22 confirmed genuine, 1 (the
  `addSuppressed` finding) found to already be covered by an existing, `story-review.md`-sanctioned
  alternative but applied anyway for cheap extra observability. See the `### Review Findings` section
  above for the per-finding disposition and fix summary. Highlights: a new `MigrationLint` rule
  (`SIDECAR_LOCK_TIMEOUT_NOT_RESET`) plus two new fixture pairs; `isLockTimeoutBoundedAt` rewritten to
  track session-scoped and `SET LOCAL` state independently (closing a real false-negative) and to
  treat `SET LOCAL` as a no-op inside a genuine non-transactional sidecar; three smaller
  `MigrationLint` regex/logic gaps closed (`SET SESSION`, `ON COMMIT DROP`, `lock_timeout = DEFAULT`);
  `R__` repeatables now subject to the session-scoped-lock-timeout checks; `hasNonTransactionalSidecar`
  rewritten as a proper line parser; `sessionRedirect.js` gained an `expired` option (fixing a false
  "session expired" banner on deliberate logout), a router-resolve-based hash-mode-safe hard-navigation
  fallback with an explicit `reload()`, a `NavigationFailureType` filter, an already-on-`/login` guard,
  and an async/await rewrite; both outbox/DLQ processors' `MAX_RUN_DURATION` bail-out now guards its
  own `releaseClaimed` call; the claim-release IT now asserts real DB state; two new fallback-path
  integration tests (`MainLayoutSpec.js`, `useSessionSpec.js`); two doc corrections
  (`scheduler-lock-tuning.md`'s buffer figure, `migration-conventions.md` rule 6's self-contradiction);
  and several story-accuracy corrections (AC5 task wording, an overclaimed/garbled mutation-check
  account in the Dev Agent Record and `sprint-status.yaml`). Test counts after this pass: backend 55
  (`MigrationConventionLintTest` 19→30, 11 new unit tests + 2 new fixture pairs; the other two
  processor test classes unchanged in count but re-verified green after their fixes), frontend 118
  (114→118, 3 net new tests across `useSessionSpec.js`/`MainLayoutSpec.js`/`sessionRedirectSpec.js`) —
  zero regressions. No `mvn verify` run locally, per this project's established convention.
