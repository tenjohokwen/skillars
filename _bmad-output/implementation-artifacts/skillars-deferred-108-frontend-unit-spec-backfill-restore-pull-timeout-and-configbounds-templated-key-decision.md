# skillars-deferred-108: Frontend Unit-Spec Backfill + Restore-Pull Timeout + ConfigBounds Templated-Key Decision

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-108
**Branch:** `story/deferred-108-frontend-spec-backfill`
**Created:** 2026-09-10
**Base:** master @ `c74abde9` (immediately after `skillars-deferred-107` (#172), the seven Dependabot
merges (#165–#171), and the `deferred-107`-followup ledger prune (#173) — all merged and master-CI
verified this same session)

---

## Story Overview

As the Skillars platform team,
I want the ~6 frontend coverage gaps that `skillars-deferred-104` **unblocked** (by standing up the
Vitest + `@vue/test-utils` runner) actually **filled** with mutation-sensitive specs, plus the two
small same-day `skillars-deferred-107` code-review residuals resolved,
so that reverting any of the frontend fixes those specs guard no longer leaves the whole suite green,
and the `deferred-107` residue stops sitting on the ledger.

A cross-module story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`.

`skillars-deferred-104` (2026-09-09) stood up the runner, the opt-in `frontend-unit-tests.yml` job,
and **two reference specs** — a `mount()` component spec (`SkillsRadarChartSpec.js`) and a
pure-logic spec (`sessionManagerSpec.js`). Its own implementation note (`deferred-work.md:1277-1280`)
lists the coverage gaps it left as *"unblocked, not closed"*:

> `deferred-11` payment, `deferred-17`/`-18` D6, `deferred-35`/`-36`/`-37`/`-38` `booking.store.js`,
> `deferred-43` `playerStore.js`, `deferred-90`/`-98` remaining `sessionManager` coverage,
> `deferred-91` `handleLogout` / i18n — still needs its own follow-up story to actually write the
> specs — the runner existing does not write them.

**This story is that follow-up.** Each of the six gaps becomes one AC whose deliverable is a
spec (or spec set) that is **mutation-sensitive**: reverting the specific fix the ledger item was
filed against must turn the new spec red. That is the exact property every one of those ledger
bullets complains is missing ("reverting X would leave the entire suite green").

The story also folds in the two `skillars-deferred-107` code-review items filed 2026-09-10
(`deferred-work.md:1658-1659`) — one small deploy-script fix, one owner decision — since they are
too small to warrant their own story and this session already touched that surface.

### The "genuine one-off bugs & gaps" class is still exhausted

`skillars-deferred-100`/`-101` declared it so; `-102`/`-103`/`-107` re-confirmed it. This story's own
re-mine against HEAD (`c74abde9`) confirms it again: what remains in `deferred-work.md` is
`[DECIDED]` / `[DISMISSED]` (do not re-litigate), "no dev agent can close this" (de-DE / fr-FR native
register review, parent legal copy), carved-out future stories (pre-production migration rebaseline;
`main.pending_blob_deletions` drop; **`skillars-7-1` D4** — `acceptBooking` fires `PAYMENT_CAPTURED`
with no real capture, an own-story payment-architecture concern, owner-decided **out of scope here**),
the ConfigBounds templated-key decision (AC9), and **this frontend-spec backfill**.

## Items that looked open but are already closed / not in scope (confirmed during story creation)

| Ledger item | HEAD reality |
|---|---|
| `deferred-104` residual "Quasar Vitest AE library-only, not `quasar ext add`" (`deferred-work.md:1267`) | **Not actionable — leave untouched.** The `@quasar/quasar-app-extension-testing-unit-vitest` index runner pins `@quasar/app-vite` `>=2.0.0 <2.4.0`; this repo is on `2.4.1`, so `quasar ext add` would break `mvn verify`'s `npx quasar build`. Blocked on a future `@quasar/app-vite` v3 bump. Kept as its own residual bullet. |
| `skillars-7-1` D4 — `acceptBooking` → `PAYMENT_CAPTURED` with no real capture (`deferred-work.md:674`, restated `:1428`) | **Owner-decided out of scope (D4 below).** Its own story + design session — retrofitting a capture-failure path and gating the state transition is a payment-architecture change (likely Part C alongside `payout-and-capture-pending.md` / `deferred-106`). Stays on the ledger untouched. |
| `deferred-91` de-DE / fr-FR native-speaker register review (`deferred-work.md:1149`, `:1166`, `:1171`) | **No dev agent can close this** — explicitly not an implementable item. Untouched. AC6's i18n coverage is behavioural (`changeLanguage` / `loadLanguagePreference`), not a translation review. |
| `deferred-107` CR item — `image_runtime_ugid` un-timed pulls (`deferred-work.md:1658`) | **Open — picked up as AC8.** Cite drifted: the function is `image_runtime_uid` (singular) at `restore-from-volume-backup.sh:35`; the pull is `:40`; the window is `${DC} down` (`:97`) → `${DC} up -d` (`:160`). |
| `deferred-107` CR item — `ConfigBounds` hand-lists templated key segments (`deferred-work.md:1659`) | **Open — picked up as AC9 (decision).** Cite drifted: `VIDEO_QUOTA_TIER_SEGMENTS` / `VIDEO_TYPE_SEGMENTS` are at `ConfigBounds.java:196-197`, not `:180-181`. A comment already at `:191-195` explains the `ConfigBoundsEnumCoverageTest` drift guard; AC9 reconciles the "never hand-listed" wording with reality + records the owner decision. |

## Project-owner decisions (captured 2026-09-10 during story creation)

| # | Question | Decision |
|---|---|---|
| **D1** | Frontend-spec scope — the four buckets named in the instruction, or all six `deferred-104`-residual gaps? | **All six.** `deferred-11` (payment) + `deferred-17`/`-18` D6 (booking-`.vue` regression) + `deferred-35`/`-36`/`-37`/`-38` (`booking.store.js`) + `deferred-43` (`playerStore.js` + `MainLayout`) + `deferred-90`/`-98` (`sessionManager.js` remainder) + `deferred-91` (`useSession.handleLogout` / i18n-locale). → **AC1–AC6** |
| **D2** | CI gate for the new specs — promote `frontend-unit-tests.yml` to a required branch-protection check, or keep it opt-in? | **Keep it opt-in, unchanged.** `deferred-104` deliberately decoupled it (runs only on `workflow_dispatch` or a PR carrying the `frontend-tests` label; never in `mvn verify` / `ci.yml` / `pr-build.yml`). This story does **not** edit `frontend-unit-tests.yml` / `ci.yml` / `pr-build.yml` / `pom.xml`. Its PR carries the `frontend-tests` label so the specs run in CI. Whether to make the job required is a separate future decision. → **AC7** |
| **D3** | Fold the two same-day `deferred-107` code-review items in? | **Both.** (a) `timeout`-wrap the disaster-restore `docker pull`s in `restore-from-volume-backup.sh` → **AC8**. (b) Accept `ConfigBounds`'s hand-listed `VIDEO_QUOTA_TIER_SEGMENTS` / `VIDEO_TYPE_SEGMENTS` **as-is** and retag the ledger bullet `[DECIDED]` — `ConfigBoundsEnumCoverageTest` already supplies the drift guard the AC was reaching for, and iterating `CoachSubscriptionTier` / `VideoType` here would couple the `config` module to `video.contract` / `marketplace.contract`. → **AC9** |
| **D4** | `skillars-7-1` D4 — `acceptBooking` fires `PAYMENT_CAPTURED` with no real Stripe capture and no failure path — scope into this story? | **No. Its own story + owner design session.** Genuinely open, but a payment-architecture change too large and design-heavy for a test-backfill story. Left on the ledger untouched. |
| **D5** | Story size. | Frontend spec backfill (**AC1–AC6**) + CI-label note, no workflow edit (**AC7**) + restore-pull `timeout` (**AC8**) + ConfigBounds templated-key decision + doc + ledger retag (**AC9**) + ledger hygiene with reconstruction check (**AC10**). |

---

## Global conventions for AC1–AC6 (the spec backfill)

Read once; every frontend AC assumes them.

- **Runner / layout.** Vitest, `happy-dom`, `globals: false` — import `describe` / `it` / `expect` /
  `vi` explicitly. `vitest.config.mjs` `include` accepts **both** `src/**/*.{spec,test}.{js,mjs}`
  (sibling layout) **and** `src/**/__tests__/**/*.{js,mjs}`. Match the existing convention: put new
  specs in a `__tests__/` directory beside the code, named `<Unit>Spec.js`
  (`stores/__tests__/bookingStoreSpec.js`, `composables/__tests__/useSessionSpec.js`, …).
- **Setup file** (`test/vitest/setup-file.js`, auto-loaded): registers the Quasar plugin, a minimal
  real `vue-i18n` instance, global `$t` / `$tc` pass-through stubs. **Pinia is NOT installed
  globally** — a spec that needs a store opts in with `createTestingPinia()` from `@pinia/testing`
  (already a dependency) or `createPinia()` + `setActivePinia()` for a real store.
- **Reference templates.** `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js`
  (the `mount()` style — `globalConfig`, stubs, `$t` mock). `src/frontend/src/plugins/__tests__/sessionManagerSpec.js`
  (the pure-logic style — faked `document.cookie`, faked clock via `vi.useFakeTimers()`,
  `afterEach` that calls `cleanup()` + `vi.useRealTimers()` + clears cookies/`sessionStorage`).
  **Do not restyle or expand the two reference specs** — extend alongside them.
- **Mutation-sensitivity is the acceptance bar.** For each AC, the Dev Agent Record must name the
  one-line revert (delete a guard / restore an old field name / drop a `catch` assignment) that
  turns the new spec red, and confirm it was run in both directions (fails reverted, passes
  restored). A spec that stays green when its target fix is reverted does not satisfy its AC.
- **No production-code changes in AC1–AC6.** These are pure test additions. If a unit turns out to
  be genuinely untestable without a seam, stop and record it in the Dev Agent Record as an open
  question rather than refactoring production code under a test-backfill story.
- **Green gate.** `cd src/frontend && npm run test:unit` passes — all new specs **plus** the two
  pre-existing reference specs (12 tests) — before the story goes to `review`.
- **ESLint + Prettier.** `npx eslint` clean on every new spec; `npx prettier --check` clean on
  every new spec (new files — no pre-existing-`[warn]` carve-out applies).

---

## Acceptance Criteria

### AC1 — `payment.store.js` card-collection actions + `PaymentMethodCard.vue` (closes `deferred-11` D-frontend)

- **Ledger:** `deferred-work.md:657` — *"No frontend tests … for `PaymentMethodCard.vue` or the new
  `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) — real coverage gap on
  a component with non-trivial lifecycle logic."*
- **Verified at HEAD:**
  - `src/frontend/src/stores/payment.store.js:238` — `async fetchStripeConfig()` — sets
    `loading.stripeConfig`, `error.stripeConfig`, assigns `this.stripeConfig = await getStripeConfig()`,
    clears loading in `finally`. (Options-API Pinia store — `defineStore('payment', { state, actions })`.)
  - `payment.store.js:249` — `async fetchSavedPaymentMethod()` — same shape around
    `this.savedPaymentMethod = await getSavedPaymentMethod()`.
  - `getStripeConfig` / `getSavedPaymentMethod` come from `src/frontend/src/api/payment.api.js`
    (confirm the exact import path/names at implementation time and `vi.mock()` that module).
  - `src/frontend/src/components/payment/PaymentMethodCard.vue` — the component under test.
- **Task:**
  - `stores/__tests__/paymentStoreSpec.js` — real store via `setActivePinia(createPinia())`,
    `payment.api.js` mocked:
    - `fetchStripeConfig` — success populates `stripeConfig`; rejection populates `error.stripeConfig`
      and leaves `stripeConfig` untouched; `loading.stripeConfig` is `true` during the call and
      `false` after both outcomes.
    - `fetchSavedPaymentMethod` — success populates `savedPaymentMethod`; a "no saved method"
      resolution (whatever the API returns for none — `null` / `{}`; confirm) is stored verbatim
      without throwing; rejection populates `error.savedPaymentMethod`.
  - `components/payment/__tests__/PaymentMethodCardSpec.js` — `mount()` with `createTestingPinia()`:
    - on mount the component calls `fetchStripeConfig` and `fetchSavedPaymentMethod` (assert the
      store actions were invoked, or the api mocks were hit);
    - renders the **saved-card** branch when `savedPaymentMethod` is populated vs the
      **add-card / empty** branch when it is not;
    - the `loadStripe` null-key guard (`deferred-11` shipped this) — with `stripeConfig` absent or
      its publishable key `null`, the component does **not** call `loadStripe` / does not throw.
      Confirm the exact guard shape in the `.vue` before asserting.
- **Mutation check (Dev Agent Record):** e.g. remove the `loadStripe` null-key guard → the
  empty-config spec throws; or drop the `error.stripeConfig = err` assignment → the rejection spec
  fails.
- **Test:** the two spec files above, green under `npm run test:unit`.

### AC2 — booking-`.vue` timezone/slot regression (closes `deferred-17` D6 + `deferred-18` D6)

- **Ledger:** `deferred-work.md:913` (`deferred-17` D6 — *"reverting the `.vue` and `booking.store.js`
  changes … would leave the entire suite green"*) and `deferred-work.md:929` (`deferred-18` /
  `uat-2` D6 — `ProfileBuilderStep3.vue` duration select, `BookingRequestPage.vue` merged own-booking
  rows + coach-timezone week bounds, `ParentBookingsPage.vue` derived read-only reschedule end).
- **Verified at HEAD (re-confirm exact lines before writing):**
  - `deferred-17` AC1 renamed every slot-object read from `.startTime` / `.endTime` to
    `.startDatetime` / `.endDatetime` in `BookingRequestPage.vue` and `booking.store.js`
    (`submitBatch` at `booking.store.js:556` maps `s.startDatetime` / `s.endDatetime`). That rename
    is the headline mutation target.
  - `deferred-17` AC2 removed the mis-aimed `Intl…timeZone` third argument from the batch-submit
    call.
  - `deferred-18` AC1 — other parents'/players' already-booked slots are filtered out of
    `getAvailabilityCalendar` results the page renders.
- **Task:** `mount()` specs (each with `createTestingPinia()`, `booking.store` actions stubbed to
  return fixture data, `vue-router` mocked or `createRouter` with memory history):
  - `pages/**/__tests__/BookingRequestPageSpec.js` — a fixture slot with
    `startDatetime` / `endDatetime` renders a valid formatted time (not `"Invalid Date"`); the
    submit payload carries `requestedStartTime` **defined** (not `undefined`); a slot already held
    by the current parent is merged into its row rather than shown as bookable; the rendered week
    bounds follow the coach timezone.
  - `pages/**/__tests__/ParentBookingsPageSpec.js` — the reschedule "end" field is derived
    read-only from start + duration (not independently editable).
  - `components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js` — the session-duration select
    renders the allowed options and binds the model.
- **Mutation check:** rename `startDatetime` → `startTime` back in the page's slot read → the
  "renders a valid time / payload defined" spec fails.
- **Scope note:** if any of these pages pulls in a large dependency tree that makes a focused
  `mount()` impractical (heavy child components, deep provide/inject), shallow-mount with targeted
  stubs and cover the **specific** regression only — record the trimming in the Dev Agent Record.
  Do not add production seams.

### AC3 — `booking.store.js` coach-request ordering + batch-accept guards (closes `deferred-35`/`-36`/`-37`/`-38`)

- **Ledger:** `deferred-work.md:997` — *"No automated test coverage for `loadCoachBookingRequests()`'s
  concurrency/request-sequencing guard … same accepted gap `deferred-35`/`36`/`37` recorded."*
  (Cite `booking.store.js:326-371` has drifted — see below.)
- **Verified at HEAD:**
  - `booking.store.js:170` — `let coachRequestsSequence = 0` (plain `let`, non-reactive ordering token).
  - `booking.store.js:388` — `async function loadCoachBookingRequests()`: captures
    `const requestId = ++coachRequestsSequence`; after the `await`, `if (requestId !== coachRequestsSequence) return true`
    (stale success — no ref writes, including not touching `coachRequestsLoading`); the `catch`
    has the mirror `if (requestId !== coachRequestsSequence) { console.warn(…); return true }`
    (stale failure — logged, `coachRequestsError` untouched); `finally` only clears
    `coachRequestsLoading` when `requestId === coachRequestsSequence`.
  - `booking.store.js:~408-414` — on the success path, `batchAcceptResultsByBatch` is pruned to the
    `batchId`s still present in `coachBatchGroups`, and **only reassigned when the prune actually
    removed an entry** (`prunedEntries.length !== currentEntries.length`) — `deferred-35/36/37` CR
    fix against needless reactivity churn.
  - `booking.store.js:593` — `const MAX_BATCH_ACCEPT_RESULTS = 200`; `:594` `setBatchAcceptResult(batchId, value)` —
    delete-then-reinsert to move the key to most-recent position, then evict from the front until
    `<= 200` (`deferred-102 AC17` / `deferred-37`).
  - `booking.store.js:605` — `async function handleAcceptAllBatch(batchId)`: `const results = await acceptAllBatch(batchId)`
    where `results` **is already the unwrapped response body** (the axios interceptor unwraps) —
    `deferred-37` CR: the old `response.data` on this value was always `undefined`. Returns
    `{ refreshed, results }`; callers must read `results` from the return value, not from
    `batchAcceptResultsByBatch[batchId]` (which the next `loadCoachBookingRequests` may prune).
- **Task:** `stores/__tests__/bookingStoreSpec.js` — real store, `booking.api` (or the specific
  `getCoachBookingRequests` / `acceptAllBatch` imports) mocked with controllable deferred promises:
  - **Out-of-order guard:** start call A, start call B (B bumps the sequence), resolve A *after* B —
    A's result is discarded: `coachBookingRequests` / `coachBatchGroups` reflect B's payload,
    `coachRequestsLoading` was not stomped to `false` by A while B was still in flight, A returns
    `true`.
  - **Stale-failure path:** call A, call B, **reject** A after B started — `console.warn` fired,
    `coachRequestsError` stays `null` (B's outcome owns it), A returns `true`.
  - **Prune fidelity:** seed `batchAcceptResultsByBatch` with two entries, return a payload whose
    `batchGroups` contains only one of them → the other is dropped; return a payload that contains
    both → the object reference is **unchanged** (no reassignment when nothing pruned).
  - **LRU cap:** `setBatchAcceptResult` 201 distinct ids → size is 200, the first-written id is
    gone, the last-written id is present; re-writing an existing id moves it to newest (write id#1
    again after 200 others → it survives the next eviction).
  - **Unwrap contract:** `acceptAllBatch` mock resolves the plain body (an array) →
    `handleAcceptAllBatch` returns `{ refreshed, results }` with `results` deep-equal to that array
    (not `undefined`, not `{ data: … }`).
- **Mutation check:** delete `if (requestId !== coachRequestsSequence) return true` → the
  out-of-order spec fails; change `results` back to `results.data` → the unwrap spec fails.

### AC4 — `playerStore.js` self-id cache + `MainLayout.vue` logout reset (closes `deferred-43` D-frontend)

- **Ledger:** `deferred-work.md:1001` — *"No automated test coverage added for `playerStore.js`'s new
  `fetchSelfPlayerId()`/`resetSelfPlayerId()` caching logic, or for `MainLayout.vue`'s new
  `resetSelfPlayerId()` call in `handleLogout()` … the cache carrying a cross-account
  booking-misattribution risk."*  (Cites `playerStore.js:24-33`, `MainLayout.vue:301` — drifted.)
- **Verified at HEAD:**
  - `src/frontend/src/stores/playerStore.js` (`defineStore('player', setup-style)`):
    `:13` `let selfPlayerIdRequest = null`, `:14` `let selfPlayerIdGeneration = 0`;
    `:28` `async function fetchSelfPlayerId()` — returns `selfPlayerId.value` immediately if non-null;
    else, if no in-flight `selfPlayerIdRequest`, captures `const requestGeneration = selfPlayerIdGeneration`
    and starts one `playerRegistrationApi.getMyProfile()` whose `.then` writes `selfPlayerId.value`
    **only if `requestGeneration === selfPlayerIdGeneration`** and `profile?.id != null` (`:39`),
    throws if `id == null`, and whose `.finally` (`:53`) nulls `selfPlayerIdRequest` **only if it
    still `=== request`**; returns the shared `selfPlayerIdRequest` promise.
    `:60` `function resetSelfPlayerId()` — nulls `selfPlayerId.value`, nulls `selfPlayerIdRequest`,
    **`selfPlayerIdGeneration++`**.
  - `src/frontend/src/layouts/MainLayout.vue:335` — `async function handleLogout()` →
    `await authStore.logout()` → `playerStore.resetSelfPlayerId()` → `destroySession()` →
    `deleteUserCookie()` → `router.push('/login')`.
- **Task:**
  - `stores/__tests__/playerStoreSpec.js` — real store, `playerRegistration.api` mocked:
    - **cache hit:** first `fetchSelfPlayerId()` calls `getMyProfile` once and returns the id; a
      second call returns the same id with **no** second API call.
    - **in-flight de-dup:** two `fetchSelfPlayerId()` calls before the first resolves share one
      `getMyProfile` call and both resolve to the same id.
    - **cross-account guard (the headline):** call `fetchSelfPlayerId()` (request in flight), call
      `resetSelfPlayerId()`, *then* resolve the in-flight `getMyProfile` with a **different**
      player's id → `selfPlayerId.value` stays `null` (the stale generation's write is dropped);
      a subsequent `fetchSelfPlayerId()` starts a fresh request.
    - **finally-reference safety:** after `resetSelfPlayerId()` has cleared `selfPlayerIdRequest`
      mid-flight and a newer call installed its own, the stale request's `.finally` does **not**
      null the newer reference.
    - **id-null rejection:** `getMyProfile` resolving `{ id: null }` (or no `id`) rejects
      `fetchSelfPlayerId()`.
  - `layouts/__tests__/MainLayoutSpec.js` (or a focused harness) — `handleLogout()` invokes
    `authStore.logout()`, then `playerStore.resetSelfPlayerId()`, then the session teardown, then
    `router.push('/login')`, **in that order** (spy call-order assertion); `resetSelfPlayerId` is
    definitely called. If a full `mount()` of `MainLayout.vue` is too heavy, extract the assertion
    via a shallow mount with the nav slots stubbed — record the trimming.
- **Mutation check:** remove the `requestGeneration === selfPlayerIdGeneration` guard at
  `playerStore.js:39` → the cross-account spec fails (the wrong id lands in `selfPlayerId`).
- **Overlap note:** `MainLayout.handleLogout` (this AC) and `useSession.handleLogout` (AC6) are two
  different functions — keep their specs in their respective ACs; do not merge.

### AC5 — `sessionManager.js` remaining coverage (closes `deferred-90`/`-98` `sessionManager` residual + 1-7b round-2)

- **Ledger:** `deferred-work.md:1121` — `[PARTIALLY ADDRESSED 2026-09-09 (deferred-104 AC8)]`:
  the stale-`rint` parse, countdown-timer leak, and unevaluated-state default are covered;
  *"Remaining `sessionManager.js` coverage (refreshSession success/failure, the deferred-90 'torn
  down' branch, multi-tab extension) is still open and in-scope for its own follow-up story."*
  Plus `deferred-work.md:1128` (1-7b round 2) — `startSessionMonitoring()`'s early return leaves no
  timer armed if the expiry navigation is swallowed.
- **Verified at HEAD (`src/frontend/src/plugins/sessionManager.js`, 298 lines):**
  - `refreshSession()` (`export async`) — sets `isRefreshing=true`, `refreshFailed=false`;
    `recordActivity()` **before** `await sessionApi.refresh()` (dynamic `import('src/api/session.api')`);
    on success `recordActivity()` again + `refreshExpiryState()` (→ `tick()` clears the warning +
    stops the 1 s countdown via its warning-edge handling); `catch` → `console.error` +
    `refreshFailed=true`; `finally` → `isRefreshing=false`.
  - `computeTimeUntilExpiry()` — the `deferred-90 AC3` "torn down" branch: when
    `readSessionExpiryFromCookie()` returns `null`, returns `0` **iff** `checkIntervalId !== null`
    **and** `hasSeenRintThisTab()` (`sessionStorage['skillars.session.rintSeen'] === '1'`) **and**
    `!hasUserSession()` (`src/utils/sessionCookies`); otherwise returns the legacy local estimate.
    Also: with a live `rint`, `remaining = expiresAt - Date.now()` has **no upper bound** (a sibling
    tab advancing `rint` freely extends this tab).
  - `startSessionMonitoring()` — clears any existing interval, `stopCountdown()`,
    `refreshFailed=false`, `recordActivity()`, then `if (tick()) return;` **before** arming the
    30 s `checkIntervalId`. `tick()` returns `true` when it already dispatched `session:expired`
    and ran `cleanup()`.
- **Task:** extend `plugins/__tests__/sessionManagerSpec.js` (same faked-cookie + faked-clock
  isolation model; `vi.mock('src/api/session.api', …)` for `refreshSession`; `vi.mock('src/utils/sessionCookies', …)`
  for `hasUserSession`; `vi.spyOn(window, 'dispatchEvent')` for `session:expired`):
  - **refreshSession success:** enter the warning band (short `rint`), then a `refresh()` that
    resolves after writing a far-future `rint` → `showWarning` back to `false`, the 1 s countdown
    interval cleared (no leak — assert timer count or that a further clock advance does not tick),
    `refreshFailed=false`, `isRefreshing` `true`→`false`.
  - **refreshSession failure:** `refresh()` rejects → `refreshFailed=true`, `isRefreshing=false`,
    `console.error` called, the countdown is still running (failure does not silently clear it).
  - **"torn down" branch:** `startSessionMonitoring()` armed (so `checkIntervalId !== null`), a
    valid `rint` seen once (sets `rintSeen`), then `rint` **and** the user cookie both cleared →
    next `tick()` computes `0`, dispatches `session:expired`, runs `cleanup()`. And the negatives:
    same cookie state but monitoring **not** armed → no dispatch; `rint` cleared but user cookie
    present → no dispatch; `rint` cleared and never-seen this tab → legacy estimate, no dispatch.
  - **multi-tab extension:** with monitoring armed and a `rint` 2 min out (warning showing),
    rewrite `rint` to 30 min out (sibling-tab extension) → next `tick()` clears the warning and
    `timeUntilExpiry` reflects ~30 min (no `LEGACY_SESSION_TTL` clamp).
  - **early-return, no timer armed:** first `tick()` in `startSessionMonitoring()` reports expired
    (idle-expired / cookies cleared) → `session:expired` dispatched once, `cleanup()` ran,
    `checkIntervalId` stays `null` (advancing the clock by 30 s produces **no** second
    `session:expired`).
- **Mutation check:** delete `if (tick()) return;` in `startSessionMonitoring()` → the early-return
  spec sees a second `session:expired` 30 s later and fails; remove `refreshFailed.value = true` in
  the `catch` → the failure spec fails.

### AC6 — `useSession.js` `handleLogout` + i18n locale switching (closes `deferred-91` `handleLogout` / i18n residual)

- **Ledger:** `deferred-work.md:1151` — `[FRAMEWORK AVAILABLE 2026-09-09 (deferred-104)]`:
  *"the `deferred-91` `handleLogout` / i18n coverage is now in-scope for its own follow-up story."*
  `deferred-91` AC14 is in `useSession.js` (not `MainLayout`); i18n AC9/AC10 were value-only bundle
  edits, so the testable i18n surface is the **locale-switch behaviour** in `MainLayout.vue`.
- **Verified at HEAD:**
  - `src/frontend/src/composables/useSession.js:71` — `async function handleLogout()`:
    `stopSessionMonitoring()` → expire `user` cookie → **expire `rint` (pre-race)** → 
    `await Promise.race([authStore.logout(), new Promise(r => setTimeout(r, LOGOUT_BACKEND_WAIT_MS))])`
    (`LOGOUT_BACKEND_WAIT_MS = 3000`) → **expire `rint` a second time (post-race)** — `deferred-91`
    code-review fix: an authenticated response in flight when the pre-race clear ran can re-set
    `rint` with `path=/` → `playerStore.resetSelfPlayerId()` → `cleanup()` → `router.push('/login')`.
  - `src/frontend/src/layouts/MainLayout.vue:303` — `changeLanguage(lang)`: `locale.value = lang`,
    `localStorage.setItem('locale', lang)`, `document.cookie = 'lang=; Max-Age=0; path=/'`
    (`deferred-92` chunk-3 fix — unstick a `?language=` `lang` cookie).
  - `MainLayout.vue:313` — `loadLanguagePreference()`: applies `localStorage['locale']` **only if**
    it is one of `languages` (`en-US` / `fr-FR` / `de-DE`).
- **Task:**
  - `composables/__tests__/useSessionSpec.js` — `useSession()` invoked inside a component or with a
    Pinia + router test harness; `vi.useFakeTimers()`; `authStore.logout` and `playerStore.resetSelfPlayerId`
    spied; `sessionManager` exports (`stopSessionMonitoring`, `cleanup`) spied; `router.push` spied;
    `document.cookie` observed:
    - **happy path order:** `stopSessionMonitoring` → (`user` + `rint` cleared) → `authStore.logout`
      → `resetSelfPlayerId` → `cleanup` → `router.push('/login')`.
    - **`rint` double-clear:** stub `authStore.logout` to resolve after simulating an in-flight
      response that re-writes `rint`; assert `rint` is expired **again** after the race resolves
      (final `document.cookie` has no live `rint`). Reverting the second
      `document.cookie = 'rint=; …'` line makes this spec fail.
    - **bounded wait:** stub `authStore.logout` to a promise that never resolves; advance fake
      timers by `LOGOUT_BACKEND_WAIT_MS` → `handleLogout()` still completes (`cleanup` +
      `router.push` run). Reverting the `Promise.race` timeout hangs this spec.
  - `layouts/__tests__/MainLayoutLocaleSpec.js` (or fold into `MainLayoutSpec.js` from AC4):
    - `changeLanguage('de-DE')` → `locale.value === 'de-DE'`, `localStorage.locale === 'de-DE'`,
      the `lang` cookie is expired (`Max-Age=0`).
    - `loadLanguagePreference()` applies a valid saved `'fr-FR'`; ignores an invalid `'xx-YY'`
      (locale unchanged).
- **Mutation check:** named above (second `rint` clear; `Promise.race` timeout arm).

### AC7 — CI: no workflow change; `frontend-tests` label on the PR (owner decision D2)

- **Task:** **Do not edit** `.github/workflows/frontend-unit-tests.yml`, `.github/workflows/ci.yml`,
  `.github/workflows/pr-build.yml`, or `pom.xml`. `deferred-104` deliberately keeps the Vitest job
  opt-in and out of `mvn verify`; this story respects that.
- The PR that ships this story **must carry the `frontend-tests` label** so
  `frontend-unit-tests.yml` runs the new suite in CI (its job `if` gates on that label /
  `workflow_dispatch`). Record this in the Dev Agent Record and the PR description.
- **Test:** `cd src/frontend && npm run test:unit` green locally (all new specs + the two reference
  specs); the labelled PR's `frontend-unit` check green.

### AC8 — `restore-from-volume-backup.sh`: bound the image-probe `docker pull` with `timeout` (closes `deferred-107` CR item 1)

- **Ledger:** `deferred-work.md:1658` — the `image_runtime_uid` probe adds up to five un-timed
  `docker pull`s to the disaster-restore path, between `${DC} down` and `${DC} up -d`, on exactly
  the host conditions a restore implies (no / slow egress, pruned images); `|| return 0` only fires
  once the pull has **finished** failing.
- **Verified at HEAD:**
  - `deploy/backup/restore-from-volume-backup.sh:35` — `image_runtime_uid() { … }`.
  - `:40` — `docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img" >/dev/null 2>&1 || return 0`.
  - `:57-63` — `chown_probed()` calls `image_runtime_uid`; invoked at `:141-145` for
    redis / prometheus / loki / tempo / grafana, i.e. **inside** the `${DC} down` (`:97`) →
    `${DC} up -d` (`:160`) outage window.
  - The numeric `uid:gid` fallbacks at `:141-145` + the WARN-on-mismatch path are already the
    documented safety net (`:132-133`).
- **Task:** wrap the `docker pull` in `timeout`:
  `docker image inspect "$img" >/dev/null 2>&1 || timeout "${IMAGE_PULL_TIMEOUT:-60}"s docker pull "$img" >/dev/null 2>&1 || return 0`
  (or an equivalent shape — reuse an existing timeout constant in the script if one exists; if not,
  a local `readonly IMAGE_PULL_TIMEOUT=…` near the top, value chosen to match the script's other
  bounded waits — check `provision.sh` / the other backup scripts for precedent). A `timeout`
  non-zero exit (124 on timeout, or pull failure) falls straight through the existing `|| return 0`
  → probe yields `""` → numeric-constant fallback + the existing WARN. Add a one-line comment
  citing `skillars-deferred-108 AC8` / `deferred-107` CR.
- **Consistency:** apply the **same** `timeout` wrap to `provision.sh`'s `image_runtime_uid` if it
  carries the identical un-timed `docker pull` (the ledger says "same probe as `provision.sh`'s
  `image_runtime_uid`"). Confirm and match; note the outcome in the Dev Agent Record.
- **Test:** `bash -n deploy/backup/restore-from-volume-backup.sh` (+ `provision.sh` if touched) and
  `shellcheck` clean. No shell harness in this repo — the Dev Agent Record documents the manual
  reasoning + a `docker compose config` sanity check if anything compose-adjacent moved.

### AC9 — `ConfigBounds` templated-key hand-listing: accept as-is + record the decision (closes `deferred-107` CR item 2)

- **Ledger:** `deferred-work.md:1659` — *"AC3's 'do not hand-list the templated key strings' is
  violated in letter … `ConfigBounds.java` hand-lists `VIDEO_QUOTA_TIER_SEGMENTS` and
  `VIDEO_TYPE_SEGMENTS` rather than iterating `CoachSubscriptionTier` / `VideoType`. Deferred: the
  documented reason … is sound, and `ConfigBoundsEnumCoverageTest` supplies exactly the drift guard
  the AC was reaching for … Needs project-owner sign-off only because `story-review.md` deliberately
  hardened that wording from 'enumerate or skip' to 'must iterate'."*
- **Owner decision (D3b):** **Accept the hand-listed segments.** Iterating `CoachSubscriptionTier`
  (`marketplace.contract`) / `VideoType` (`video.contract`) from `ConfigBounds` would give the
  `config` module a compile dependency on two business modules it otherwise does not touch;
  `ConfigBoundsEnumCoverageTest` already fails the build if a new enum constant lands without a
  matching bound, which is the guarantee the "must iterate" wording was reaching for.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:191-197` —
    a comment block ("Templated per-enum keys — generated, never hand-listed (AC3) …") followed by
    `static final List<String> VIDEO_QUOTA_TIER_SEGMENTS = List.of("scout", "instructor", "academy", "athlete");`
    and `static final List<String> VIDEO_TYPE_SEGMENTS = List.of("homework", "drillDemo", "coachReview");`.
  - `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`
    — the drift guard.
- **Task (doc + comment only, no behaviour change):**
  - Reword the `ConfigBounds.java:191-195` comment so it no longer claims the keys are "generated,
    never hand-listed" — state that the segments are **deliberately hand-listed** to keep `config`
    free of a `video.contract` / `marketplace.contract` dependency, and that
    `ConfigBoundsEnumCoverageTest` is the enforced drift guard. Cite `skillars-deferred-108 AC9
    (owner decision 2026-09-10)`.
  - Retag the `deferred-work.md:1659` bullet `[DECIDED 2026-09-10 (skillars-deferred-108 AC9)]`
    (kept in place, not deleted — the decided-retention rule). Recorded in AC10's audit block.
- **Test:** `ConfigBoundsEnumCoverageTest` still green (it is the thing the decision leans on).
  No new test — the AC is a decision + doc.

### AC10 — Ledger hygiene + reconstruction check

- **Delete outright (genuine closures — `deferred-work.md`'s delete-when-closed convention, no
  `[CLOSED by …]` tag left behind):**
  - `## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)` —
    its sole frontend-tests bullet (`:657`). Header removed if that leaves the section empty
    (confirm — it had one bullet).
  - `## Deferred from: code review of skillars-deferred-17-… (2026-08-06)` — **D6** (`:913`).
  - `## Deferred from: skillars-uat-2-… (2026-08-10)` — **D6** only (`:929`); D3 / D4 / D7 stay,
    header stays.
  - `## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard
    (2026-08-19)` — its sole bullet (`:997`). Header removed (section empties).
  - `## Deferred from: code review of skillars-deferred-43-… (2026-08-20)` — its sole bullet
    (`:1001`). Header removed (section empties).
  - `## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)` — the
    `sessionManager.js` state-machine bullet (`:1121`) now fully closed by AC5 (was
    `[PARTIALLY ADDRESSED]`). And `## Deferred from: code review (round 2) of 1-7b-… (2026-09-02)`
    — the `startSessionMonitoring()` early-return bullet (`:1128`), closed by AC5. Remove whichever
    headers empty out.
  - `## Deferred from: code review of skillars-deferred-107 (2026-09-10)` — **the first bullet**
    (`image_runtime_ugid` un-timed pulls, `:1658`), closed by AC8. The **second** bullet (`:1659`)
    is **retagged, not deleted** (AC9) — so the header stays.
- **Retag in place (decided-retention):**
  - `deferred-work.md:1659` → `[DECIDED 2026-09-10 (skillars-deferred-108 AC9)]` (AC9).
- **Update, do not delete (strike-through + annotate):**
  - `deferred-work.md:1151` (`deferred-91` residual) — strike the *"the `deferred-91` `handleLogout`
    / i18n coverage is now in-scope for its own follow-up story"* clause; annotate **CLOSED by
    `skillars-deferred-108` AC6**. Keep the de-DE native-speaker-review residual intact.
  - `deferred-work.md:1277-1280` (`deferred-104` implementation residual — *"~6 frontend coverage
    gaps are now unblocked, not closed"*) — strike/annotate each named dependent
    (`deferred-11` / `-17`/`-18` / `-35`–`-38` / `-43` / `-90`/`-98` / `-91`) **CLOSED by
    `skillars-deferred-108` AC1–AC6**. Leave the sibling `deferred-104` residual bullet
    (`:1267`, "Quasar Vitest AE library-only") **untouched** — still blocked on `@quasar/app-vite` v3.
  - Any older cross-reference lists that name these as open (`deferred-90` D9 `:941`, `deferred-91`
    `:1151`, `deferred-17`/`-18` mentions in prior audit prose) — light strike-through only where
    they assert open status; do not reword narrative.
- **New audit block** `## Last audit: 2026-09-10 (skillars-deferred-108 story implementation)` —
  enumerate every deleted bullet + emptied header, every retag, every strike-through, and the
  standard **reconstruction check** (every surviving non-blank line matches the pre-edit file at
  `HEAD` + the deferred-108 implementation commit, in order, nothing reworded; `[DISMISSED]` count
  unchanged; `[DECIDED]` count `+1`; `[PICKED UP by …]` bullets touched: state which).
- **Test:** none (ledger doc). The reconstruction check *is* the verification — run it and record
  the before/after line counts.

---

## Dev Notes

### Established patterns to follow (do not reinvent)

- **Frontend unit test =** Vitest + `@vue/test-utils`, `happy-dom`, explicit imports (no globals),
  spec beside code in `__tests__/…Spec.js`, run via `cd src/frontend && npm run test:unit`. Not in
  `mvn verify`. See `docs/testing/frontend-unit-tests.md` and the two reference specs.
- **Store specs:** real Pinia via `createPinia()` + `setActivePinia()` for behaviour under test, or
  `createTestingPinia()` (`@pinia/testing`) when the store is a collaborator you only need to spy.
  `vi.mock()` the `*.api.js` module, never the network.
- **Component specs:** `mount()` with `global.plugins` (i18n + Quasar come from the setup file),
  stub heavy children (`stubs: { 'q-tooltip': true, … }`), mock `$t` via the setup file's
  pass-through. Router: `createRouter({ history: createMemoryHistory(), routes: [] })` or a
  `push` spy.
- **Timer / clock specs:** `vi.useFakeTimers()`; drive `Date.now()` with `vi.setSystemTime()`;
  always `vi.useRealTimers()` in `afterEach`. For `sessionManager` follow its spec's existing
  `cleanup()` + cookie/`sessionStorage` reset `afterEach`.
- **Deploy scripts:** POSIX `sh`-compatible where the shebang says so; `bash -n` + `shellcheck`
  are the gate; no runtime harness — manual reasoning in the Dev Agent Record. Match existing
  `timeout` / retry idioms already in `deploy/`.

### Local config-validation / running the suite

```
cd src/frontend
npm ci            # if node_modules is stale
npm run test:unit # full suite — must be green before `review`
npx eslint 'src/**/__tests__/**/*.js'
npx prettier --check 'src/**/__tests__/**/*.js'
```

### What must NOT change

- No production `.vue` / `.js` / store / composable source edits for AC1–AC6 (pure test additions).
- No `frontend-unit-tests.yml` / `ci.yml` / `pr-build.yml` / `pom.xml` edits (AC7 / D2).
- No `ConfigBounds` **behaviour** change (AC9 is comment + ledger only).
- `deferred-104`'s two reference specs are templates — extend beside them, don't restyle them.
- The `deferred-104` "Quasar AE library-only" residual and `deferred-91`'s native-speaker-review
  residual stay on the ledger.

### Project-context rules that apply

- Frontend: `<script setup>`, `async/await` (no `.then()` in new production code — n/a here since
  no production edits), centralized `*.api.js`, all user-facing text via `vue-i18n`, Prettier
  mandatory on `.js` / `.vue` / `.scss` / `.json`.
- Backend (AC9 doc only): no behaviour change, so the record/MapStruct/`@PreAuthorize` rules are
  not exercised.

---

## Change Log

| Date | Change |
|---|---|
| 2026-09-10 | Story created. HEAD `c74abde9`. Frontend-item cites re-verified against source (line drift corrected throughout: `booking.store.js:326-371`→`:170`/`:388`/`:593`/`:605`; `playerStore.js:24-33`→`:13-14`/`:28`/`:60`; `ConfigBounds.java:180-181`→`:196-197`; `restore-from-volume-backup.sh` `image_runtime_ugid`→`image_runtime_uid` `:35`/`:40`, window `:97`→`:160`). Owner decisions D1 (all six gaps), D2 (keep `frontend-unit-tests.yml` opt-in, PR carries `frontend-tests` label), D3 (fold both `deferred-107` CR items: AC8 `timeout`-wrap, AC9 accept ConfigBounds hand-listing + retag), D4 (`skillars-7-1` D4 stays its own story). Scope = AC1–AC6 spec backfill + AC7 CI-label note + AC8 restore-pull `timeout` + AC9 ConfigBounds decision + AC10 ledger hygiene. |

---

## Dev Agent Record

### Context Reference

- `deferred-work.md` items: `:657` (deferred-11), `:913` (deferred-17 D6), `:929` (uat-2/deferred-18
  D6), `:997` (deferred-38 / -35 / -36 / -37), `:1001` (deferred-43), `:1121` + `:1128` (1-7b /
  deferred-90 / -98 sessionManager), `:1151` (deferred-91 handleLogout/i18n),
  `:1277-1280` (deferred-104 residual roll-up), `:1658-1659` (deferred-107 CR).
- `skillars-deferred-104-frontend-test-framework-initiative.md` — runner/CI/reference-spec design.
- `docs/testing/frontend-unit-tests.md` — how to run, layout, CI wiring.
- Reference specs: `src/frontend/src/components/development/__tests__/SkillsRadarChartSpec.js`,
  `src/frontend/src/plugins/__tests__/sessionManagerSpec.js`.
- Setup: `src/frontend/test/vitest/setup-file.js`, `src/frontend/vitest.config.mjs`.

### Agent Model Used

_(dev agent to fill)_

### Completion Notes List

_(dev agent to fill — per AC: spec file paths, the exact one-line mutation that turns each new spec
red and confirmation it was run both ways, `npm run test:unit` pass count, `bash -n`/`shellcheck`
output for AC8, `ConfigBoundsEnumCoverageTest` result for AC9, before/after line counts +
reconstruction-check outcome for AC10.)_

### File List

_(dev agent to fill)_
