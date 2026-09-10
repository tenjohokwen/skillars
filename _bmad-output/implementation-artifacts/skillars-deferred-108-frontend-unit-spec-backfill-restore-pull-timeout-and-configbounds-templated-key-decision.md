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
pure-logic spec (`sessionManagerSpec.js`). Its own implementation note (`deferred-work.md:1277-1281`)
lists the coverage gaps it left as *"unblocked, not closed"* — verbatim:

> `deferred-12` payment, `deferred-17`/`-18` D6, `deferred-35`/`-36`/`-37`/`-38` `booking.store.js`,
> `deferred-43` `playerStore.js`, `deferred-90`/`-98` remaining `sessionManager` coverage,
> `deferred-91` `handleLogout` / i18n — still needs its own follow-up story to actually write the
> specs — the runner existing does not write them.

**`deferred-12` there is a pre-existing typo for `deferred-11`** — the payment frontend-tests bullet
is `deferred-work.md:657` under `## Deferred from: code review of skillars-deferred-11-stripe-card-collection`;
`skillars-deferred-12` is an unrelated backend story. AC10 corrects the typo when it annotates that
roll-up.

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
| `deferred-107` CR item — `image_runtime_ugid` un-timed pulls (`deferred-work.md:1658`) | **Open — picked up as AC8.** Cite drifted: the function is `image_runtime_uid` (singular) at `restore-from-volume-backup.sh:35`; it has **two** unbounded `docker` calls — `docker pull` at `:40` and, when `Config.User` is a name (e.g. `grafana`), `docker run --rm --entrypoint sh …` at `:45`. The identical probe is in `provision.sh` (function `:89`, pull `:94`, run `:100`). The window is `${DC} down` (`:97`) → `${DC} up -d` (`:160`). |
| `deferred-107` CR item — `ConfigBounds` hand-lists templated key segments (`deferred-work.md:1659`) | **Open — picked up as AC9 (decision).** Cite drifted: the comment block is `ConfigBounds.java:193-197`; `VIDEO_QUOTA_TIER_SEGMENTS` / `VIDEO_TYPE_SEGMENTS` are at `:199-200` (not `:180-181`). The comment already names `ConfigBoundsEnumCoverageTest` as the drift guard; AC9 reconciles the "generated, never hand-listed" wording with reality + records the owner decision. |

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
  pre-existing reference specs (12 tests) — before the story goes to `review`. Run
  `npm run test:unit:ci` (`vitest run --coverage`) too — that is the script the CI job runs, and it
  emits `coverage/` (already `eslint`-ignored, `eslint.config.js:21`).
- **ESLint + Prettier.** `npx eslint` clean on every new spec; `npx prettier --check` clean on
  every new spec (new files — no pre-existing-`[warn]` carve-out applies).
- **Shared fixtures/helpers do NOT go under `src/**/__tests__/`.** `vitest.config.mjs:34-38`'s
  second `include` glob is `src/**/__tests__/**/*.{js,mjs}` — **no `spec`/`Spec` qualifier** — so any
  `.js` file there is collected as a test and fails the run with *"No test suite found in file"*.
  Put shared factories in `test/vitest/` (a sibling of the existing `setup-file.js`) or inline them
  per-spec.
- **Quasar overlays teleport out of the wrapper.** `q-dialog` / `q-menu` / `q-select` popup content
  renders under `document.body`, not inside `wrapper.html()`. For AC2's `ParentBookingsPage` dialog
  field and `ProfileBuilderStep3` select options, assert via `wrapper.vm` / component state (the
  backing `computed`) or query `document.body` **after opening the overlay** — a plain
  `wrapper.find()` will match nothing and the spec will be trivially green.
- **Spying a statically-imported ESM module** (`vi.spyOn(mod, 'fn')`) is unreliable — module
  namespace objects are non-configurable. Use `vi.mock('<path>', { spy: true })` (keeps real impls,
  wraps each export in a spy) or a factory with `importOriginal()`. Applies to AC6's
  `sessionManager` exports and any spec spying `vue-router`'s `useRouter`.

---

## Acceptance Criteria

### AC1 — `payment.store.js` card-collection actions + `PaymentMethodCard.vue` (closes `deferred-11` D-frontend)

- **Ledger:** `deferred-work.md:657` — *"No frontend tests … for `PaymentMethodCard.vue` or the new
  `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) — real coverage gap on
  a component with non-trivial lifecycle logic."*
- **Verified at HEAD:**
  - `src/frontend/src/stores/payment.store.js:238` — `async fetchStripeConfig()` — sets
    `loading.stripeConfig`, `error.stripeConfig = null`, `this.stripeConfig = await getStripeConfig()`,
    clears loading in `finally`. (Options-API Pinia store — `defineStore('payment', { state, actions })`.)
  - `payment.store.js:249` — `async fetchSavedPaymentMethod()` — same shape around
    `this.savedPaymentMethod = await getSavedPaymentMethod()`.
  - `getStripeConfig` / `getSavedPaymentMethod` are imported from `src/frontend/src/api/payment.api.js`
    (confirm exact names at implementation time and `vi.mock()` that module).
  - `src/frontend/src/components/payment/PaymentMethodCard.vue` — component under test. Real shape:
    `onMounted` (`:216`) → `await loadStripeConfig()` (`:174`, `{ isRetry = false } = {}` — shared by
    mount and the "Try again" button, `deferred-103` AC9). `loadStripeConfig` calls the two store
    fetches, then `ensureStripeReady()` (`:105`). The **null-key guard** is in `ensureStripeReady`
    at `:107-113`:
    `const key = paymentStore.stripeConfig?.publishableKey; if (!key) { stripeUnavailable.value = true; return false }`.
    The template's branch discriminator is `savedCard?.hasCard` everywhere (`:20`, `:45`, and
    `showForm` computed `:101`) — **not** presence/absence of `savedPaymentMethod`.
  - **`SavedPaymentMethodResponse` (backend record) always returns an object** —
    `record SavedPaymentMethodResponse(boolean hasCard, String brand, String last4, Long expMonth, Long expYear)`.
    "No saved card" is `{ hasCard: false, … }`, never `null` / `{}`.
- **Task:**
  - `stores/__tests__/paymentStoreSpec.js` — real store via `setActivePinia(createPinia())`,
    `vi.mock('src/api/payment.api')`:
    - `fetchStripeConfig` — success populates `stripeConfig`; rejection populates `error.stripeConfig`
      and leaves `stripeConfig` untouched; `loading.stripeConfig` toggles `true`→`false` on both
      outcomes.
    - `fetchSavedPaymentMethod` — a `{ hasCard: true, brand, last4, … }` resolution populates
      `savedPaymentMethod`; a `{ hasCard: false }` resolution is stored verbatim without throwing;
      rejection populates `error.savedPaymentMethod`.
  - `components/payment/__tests__/PaymentMethodCardSpec.js` — `mount()` with `createTestingPinia()`.
    **`vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))` is mandatory** — the real
    package appends `<script src="https://js.stripe.com/…">` to the document at *import* time
    (`Promise.resolve().then(getStripePromise)` in its `dist/index.mjs`), and the guard test needs
    the spy regardless. Also `vi.mock('src/api/payment.api')` (`createSetupIntent` etc. are imported
    directly by the `.vue`).
    - on mount the component calls `fetchStripeConfig` and `fetchSavedPaymentMethod` (assert via the
      store-action spies or the api-mock call counts);
    - `savedPaymentMethod = { hasCard: true, brand: 'visa', last4: '4242', … }` → the saved-card row
      (`:20`) renders; `savedPaymentMethod = { hasCard: false }` → the form / add-card branch
      (`:45`, `showForm === true`) renders;
    - **null-key guard:** with `paymentStore.stripeConfig` unset (or its `publishableKey` null),
      trigger the card-form path (`savedPaymentMethod = { hasCard: false }`, wait for
      `loadStripeConfig` → `ensureStripeReady`) → `stripeUnavailable` becomes `true` and
      `loadStripe` **is never called**.
- **Mutation check (Dev Agent Record):** delete the `if (!key) { … return false }` guard at
  `ensureStripeReady:107-113` → `loadStripe(undefined)` is swallowed by the `try/catch` at `:114-118`
  and lands on the *same* `stripeUnavailable = true` outcome, so nothing throws and no state
  differs — **the assertion that goes red is `expect(loadStripe).not.toHaveBeenCalled()`**. (Also:
  drop `error.stripeConfig = err` in the store → the store rejection spec fails.)
- **Test:** the two spec files above, green under `npm run test:unit`.

### AC2 — booking-`.vue` timezone/slot regression (closes `deferred-17` D6 + `deferred-18` D6)

- **Ledger:** `deferred-work.md:913` (`deferred-17` D6 — *"reverting the `.vue` and `booking.store.js`
  changes … would leave the entire suite green"*) and `deferred-work.md:929` (`deferred-18` /
  `uat-2` D6 — `ProfileBuilderStep3.vue` duration select, `BookingRequestPage.vue` own-booking rows +
  coach-timezone week bounds, `ParentBookingsPage.vue` derived read-only reschedule end).
- **Verified at HEAD:**
  - `deferred-17` AC1 renamed every slot-object read from `.startTime` / `.endTime` to
    `.startDatetime` / `.endDatetime` in `BookingRequestPage.vue` and `booking.store.js`
    (`submitBatch` at `booking.store.js:556` maps `s.startDatetime` / `s.endDatetime`). Headline
    mutation target.
  - `deferred-17` AC2 removed the mis-aimed `Intl…timeZone` third argument from the batch-submit
    call.
  - **`BookingRequestPage.vue` does NOT merge own bookings into slot rows.** `slotRows` (`:456-473`)
    is `[...available, ...own].sort((a,b) => a.sortKey - b.sortKey)` — own bookings are **appended**
    as separate `{ type: 'own', … }` rows (`:463-471`) interleaved by `sortKey`; nothing is removed
    from `computedSlots`. The backend carves the slot out of `computedSlots` (comment `:46`); the
    page just shows the parent's own booking as a non-selectable row. `ownBlockingBookings` (`:426`)
    filters on `String(b.coachId) === String(coachId)`, `ownBlockingStatuses.value.includes(b.status)`
    (populated from `getBookingRequestConfig()` at `:661`), and the week window.
  - `slotRows` own rows are **not selectable, never enter the batch basket, and do not count toward
    `batchAtMax`** (`batchAtMax` reads `bookingStore.batchBasketSize`, `:269`) — the `.vue`'s own
    doc-comment at `:448-455` states this.
- **Task:** `mount()` specs. Each requires a **navigated memory router** with a real matched route —
  `createRouter({ history: createMemoryHistory(), routes: [{ path: '/…/:coachId', component }] })`,
  `router.push('/…/<coachId>?playerId=<n>')`, `await router.isReady()` — because
  `BookingRequestPage` reads `route.params.coachId` (`:241`) and `route.query.playerId` (`:246`) at
  setup, and with `routes: []` both are `undefined`, which makes `ownBlockingBookings` drop every
  fixture (`coachId` mismatch) and `canSubmit` (`:291`) permanently `false` (so `submit()`
  early-returns at `:479` and the payload assertion is unreachable). Also `vi.mock` the
  three `onMounted` calls (`:625-670`): `playerStore.fetchSelfPlayerId`, `getBatchConfig`,
  `getBookingRequestConfig` (the last feeds `ownBlockingStatuses` — leave it unmocked and the
  "own rows" test silently sees nothing).
  - `pages/**/__tests__/BookingRequestPageSpec.js` — a fixture slot with
    `startDatetime` / `endDatetime` renders a valid formatted time (not `"Invalid Date"`); after
    `selectSlot(...)`, the submit payload carries `requestedStartTime` **defined**; a fixture own
    booking for the routed `coachId` produces a `type: 'own'` row in `slotRows` that is not
    selectable and whose presence does not change `batchBasketSize` / `batchAtMax`; week bounds
    follow `bookingStore.coachTimezone`.
  - `pages/**/__tests__/ParentBookingsPageSpec.js` — open the reschedule dialog, then assert the
    read-only derived end via the backing `rescheduleProposedEnd` computed (`:252`) or by querying
    `document.body` (the dialog teleports).
  - `components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js` — assert the duration
    `q-select`'s bound options / `v-model` via `wrapper.vm` / props (the option list is a teleported
    `q-menu` popup).
- **Not frontend-observable — drop from this AC:** *"other parents'/players' already-booked slots
  are filtered out of `getAvailabilityCalendar`"* is a **backend** filter; the page renders whatever
  `computedSlots` holds. It is verified by the backend suite, not here.
- **Mutation check:** rename `slot.startDatetime` → `slot.startTime` back in `slotRows` (`:459`) →
  the "renders a valid time / payload defined" spec fails.
- **Scope note:** if a full `mount()` is impractical (heavy child tree), shallow-mount with targeted
  stubs and cover the specific regression only — record the trimming. **No production seams.**

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
    `<= 200` (`deferred-102 AC17` / `deferred-37`). **Both are closure-private** — not in the store's
    return object (`:632-710`); the only reachable caller is `handleAcceptAllBatch`. **`batchId` is a
    UUID** (`BatchGroupedBookingResponse.batchId` / `BookingBatch.@Id` are `java.util.UUID`) — the
    delete-then-reinsert LRU trick relies on JS preserving string-key insertion order, which it does
    **only for non-array-index keys**, so the test's ids must be UUID-shaped strings, **never**
    `'1'`…`'201'` (integer-index keys sort numerically, ahead of all string keys — a spec using them
    would assert the opposite of the intended eviction).
  - `booking.store.js:605` — `async function handleAcceptAllBatch(batchId)`: `const results = await acceptAllBatch(batchId)`
    where `results` **is already the unwrapped response body** (the axios interceptor unwraps) —
    `deferred-37` CR: the old `response.data` on this value was always `undefined`. Returns
    `{ refreshed, results }`; callers must read `results` from the return value, not from
    `batchAcceptResultsByBatch[batchId]` (which the next `loadCoachBookingRequests` may prune).
- **Task:** `stores/__tests__/bookingStoreSpec.js` — real store, `vi.mock` the api module so
  `getCoachBookingRequests` **and** `acceptAllBatch` are both stubbed with controllable deferred
  promises (`handleAcceptAllBatch` always calls `loadCoachBookingRequests()` before it returns, so
  `getCoachBookingRequests` must be stubbed in every case that touches `handleAcceptAllBatch`):
  - **Out-of-order guard:** start call A, start call B (B bumps the sequence), resolve A *after* B —
    A's result is discarded: `coachBookingRequests` / `coachBatchGroups` reflect B's payload,
    `coachRequestsLoading` was not stomped to `false` by A while B was still in flight, A returns
    `true`.
  - **Stale-failure path:** call A, call B, **reject** A after B started — `console.warn` fired,
    `coachRequestsError` stays `null` (B's outcome owns it), A returns `true`.
  - **Prune fidelity:** drive two `handleAcceptAllBatch` calls (or seed the map through them) for
    UUID-shaped ids, then a `getCoachBookingRequests` resolution whose `batchGroups` contains only
    one → the other entry is dropped; a resolution containing both → the object reference is
    **unchanged** (no reassignment when nothing pruned).
  - **LRU cap:** `setBatchAcceptResult` is not exported, so exercise it via `handleAcceptAllBatch`
    **with the `getCoachBookingRequests` mock set to reject** (the prune runs on the success path
    only, `:396-414`, so a rejecting refresh lets the map accumulate). 201 `handleAcceptAllBatch`
    calls with distinct UUID `batchId`s → `Object.keys(store.batchAcceptResultsByBatch).length` is
    200, the first-written id is gone, the last-written id is present; call an already-present id
    again after 200 others → it survives the next eviction. If reaching 201 accumulations proves
    impractical, record the LRU case as an open question in the Dev Agent Record rather than
    weakening it.
  - **Unwrap contract:** `acceptAllBatch` mock resolves the plain body (an array),
    `getCoachBookingRequests` mock resolves a payload whose `batchGroups` still contains that
    `batchId` (so the entry is not pruned) → `handleAcceptAllBatch` returns `{ refreshed, results }`
    with `results` deep-equal to that array (not `undefined`, not `{ data: … }`).
- **Mutation check:** delete `if (requestId !== coachRequestsSequence) return true` → the
  out-of-order spec fails; change `const results = await acceptAllBatch(batchId)` back to
  `results.data` → the unwrap spec fails.

### AC4 — `playerStore.js` self-id cache + `MainLayout.vue` logout reset (closes `deferred-43` D-frontend)

- **Ledger:** `deferred-work.md:1001` — *"No automated test coverage added for `playerStore.js`'s new
  `fetchSelfPlayerId()`/`resetSelfPlayerId()` caching logic, or for `MainLayout.vue`'s new
  `resetSelfPlayerId()` call in `handleLogout()` … the cache carrying a cross-account
  booking-misattribution risk."*  (Cites `playerStore.js:24-33`, `MainLayout.vue:301` — drifted.)
- **Verified at HEAD:**
  - `src/frontend/src/stores/playerStore.js` (`defineStore('player', setup-style)`):
    `:13` `let selfPlayerIdRequest = null`, `:14` `let selfPlayerIdGeneration = 0` — both live
    **inside the setup function**, so `setActivePinia(createPinia())` per test gives real isolation
    (the code comment calling them "module-scoped" is inaccurate but harmless);
    `:28` `async function fetchSelfPlayerId()` — returns `selfPlayerId.value` immediately if non-null;
    else, if no in-flight `selfPlayerIdRequest`, captures `const requestGeneration = selfPlayerIdGeneration`
    and starts one `playerRegistrationApi.getMyProfile()` whose `.then`:
    (a) writes `selfPlayerId.value` **only if `requestGeneration === selfPlayerIdGeneration`** and
    `profile?.id != null` (`:39`); (b) throws if `id == null`; (c) **`return profile.id`
    unconditionally (`:46`)** — the generation guard is NOT applied to the returned value. `.finally`
    (`:53`) nulls `selfPlayerIdRequest` **only if it still `=== request`**. Returns the shared
    `selfPlayerIdRequest` promise.
    `:60` `function resetSelfPlayerId()` — nulls `selfPlayerId.value`, nulls `selfPlayerIdRequest`,
    **`selfPlayerIdGeneration++`**.
  - `src/frontend/src/layouts/MainLayout.vue:335` — `async function handleLogout()` →
    `await authStore.logout()` → `playerStore.resetSelfPlayerId()` → `destroySession()` →
    `deleteUserCookie()` → `router.push('/login')`.
- **Task:**
  - `stores/__tests__/playerStoreSpec.js` — real store, `vi.mock('src/api/playerRegistration.api')`:
    - **cache hit:** first `fetchSelfPlayerId()` calls `getMyProfile` once and returns the id; a
      second call returns the same id with **no** second API call.
    - **in-flight de-dup:** two `fetchSelfPlayerId()` calls before the first resolves share one
      `getMyProfile` call and both resolve to the same id.
    - **cross-account guard — cached ref (the headline mutation target):** call `fetchSelfPlayerId()`
      (request in flight), call `resetSelfPlayerId()`, *then* resolve the in-flight `getMyProfile`
      with a **different** player's id → `selfPlayerId.value` stays `null`; a subsequent
      `fetchSelfPlayerId()` starts a fresh request.
    - **cross-account guard — returned promise (records the residual):** same setup, but also assert
      what the **superseded call's promise resolves to**. Per `:46` it resolves with the *other*
      account's id — the generation guard does not gate the return value. `BookingRequestPage.vue:628`
      does `selfPlayerId.value = await playerStore.fetchSelfPlayerId()` and feeds that into the
      submit payload, so this is exactly the *"cross-account booking-misattribution risk"* the
      ledger bullet (`:1001`) names — **not** fully closed by the guard. Per the story's
      no-production-change rule, do **not** fix it here: assert the current (leaky) behaviour, and
      **file it as a new bullet** under `## Deferred from: skillars-deferred-108` in AC10 (see AC10).
    - **finally-reference safety (state behaviourally — the field is closure-private):** after
      `resetSelfPlayerId()` clears `selfPlayerIdRequest` mid-flight and a newer `fetchSelfPlayerId()`
      installs its own, let the stale request settle, then call `fetchSelfPlayerId()` again → it
      issues **no new `getMyProfile`** (proving the newer request is still installed and was not
      nulled by the stale `.finally`).
    - **id-null rejection:** `getMyProfile` resolving `{ id: null }` (or no `id`) rejects
      `fetchSelfPlayerId()`.
  - `layouts/__tests__/MainLayoutSpec.js` (or a focused harness) — `handleLogout()` invokes
    `authStore.logout()`, then `playerStore.resetSelfPlayerId()`, then the session teardown, then
    `router.push('/login')`, **in that order** (spy call-order assertion); `resetSelfPlayerId` is
    definitely called. Full `mount()` of `MainLayout.vue` may be too heavy — a shallow mount with
    nav slots stubbed, or invoking the extracted function via `wrapper.vm`, is fine; record the
    trimming. Add an `afterEach` restoring locale (see AC6 / H2) if this file also covers AC6's
    locale cases.
- **Mutation check:** remove the `requestGeneration === selfPlayerIdGeneration` guard at
  `playerStore.js:39` → the **cached-ref** cross-account spec fails (the wrong id lands in
  `selfPlayerId.value`). (The returned-promise spec is a characterization test — it stays green
  either way, by design.)
- **Overlap note:** `MainLayout.handleLogout` (this AC) and `useSession.handleLogout` (AC6) are two
  different functions — keep their specs in their respective ACs; do not merge.

### AC5 — `sessionManager.js` remaining coverage (closes the `deferred-90`/`-98` / 1-7b-round-1 `sessionManager` **coverage** residual; pins — does not close — the two decided early-return bullets)

- **Ledger:** `deferred-work.md:1121` — `[PARTIALLY ADDRESSED 2026-09-09 (deferred-104 AC8)]`:
  the stale-`rint` parse, countdown-timer leak, and unevaluated-state default are covered;
  *"Remaining `sessionManager.js` coverage (refreshSession success/failure, the deferred-90 'torn
  down' branch, multi-tab extension) is still open and in-scope for its own follow-up story."*
  **This AC closes exactly that "remaining coverage" clause of `:1121`** (AC10 deletes `:1121`).
  It does **not** close `deferred-work.md:1125` (round-2 1-7b — *"Deliberately left as-is"*, an
  `App.vue`/`vue-router` abort concern) or `:1128` (`deferred-90` — *"left as documented
  (project-owner decision)"*): those are **decided** items. The early-return spec below is a
  **characterization test** that pins the deliberate behaviour; it does not resolve the underlying
  re-arm gap. AC10 annotates `:1125`/`:1128`, it does not delete them.
- **Verified at HEAD (`src/frontend/src/plugins/sessionManager.js`, 298 lines):**
  - `refreshSession()` (`export async`) — sets `isRefreshing=true`, `refreshFailed=false`;
    `recordActivity()` **before** `await sessionApi.refresh()` (dynamic `import('src/api/session.api')`);
    on success `recordActivity()` again + `refreshExpiryState()` (→ `tick()` clears the warning +
    stops the 1 s countdown via its warning-edge handling); `catch` → `console.error` +
    `refreshFailed=true`; `finally` → `isRefreshing=false`.
  - `computeTimeUntilExpiry()` (`:118-141`):
    - `const localEstimate = LEGACY_SESSION_TTL - (Date.now() - lastActivityTime.value)` — a pure
      `Date.now()` delta, so it is skew-immune and **positive right after `recordActivity()`**.
    - `expiresAt === null` → the `deferred-90 AC3` "torn down" branch: returns `0` **iff**
      `checkIntervalId !== null` **and** `hasSeenRintThisTab()`
      (`sessionStorage['skillars.session.rintSeen'] === '1'`) **and** `!hasUserSession()`
      (`src/utils/sessionCookies`); otherwise returns `localEstimate`.
    - `expiresAt !== null`: `const remaining = expiresAt - Date.now()`; **`:140`
      `if (remaining <= 0 && localEstimate > 0) return localEstimate`** (the skew cross-check —
      a past-due `rint` does **not** expire the session while the local estimate is still positive);
      else `return remaining`. **No upper bound** on `remaining` — a sibling tab advancing `rint`
      freely extends this tab.
  - `startSessionMonitoring()` (`:207-230`) — `:209` `if (checkIntervalId) clearInterval(checkIntervalId)`
    **clears the timer but does NOT null `checkIntervalId`**; `stopCountdown()`; `refreshFailed=false`;
    `recordActivity()` (`:215` — pins `lastActivityTime = Date.now()`, so `localEstimate` is
    ~`LEGACY_SESSION_TTL` on the tick that follows); then `if (tick()) return;` **before** arming the
    30 s `checkIntervalId` (`:230`). `tick()` returns `true` only when it already dispatched
    `session:expired` and ran `cleanup()` (which nulls `checkIntervalId` via `stopSessionMonitoring()`).
  - **Consequence for the early-return case:** because `recordActivity()` runs immediately before the
    first `tick()`, `localEstimate > 0` always, so a past-due `rint` can **never** trigger the early
    return (`:140` returns the positive estimate). "Idle-expired first tick" is structurally
    impossible. The **only** route to `tick() === true` on that first call is the `expiresAt === null`
    torn-down branch, which needs `checkIntervalId !== null` — true only if a *previous*
    `startSessionMonitoring()` armed it and no `cleanup()` / `stopSessionMonitoring()` ran since.
- **Task:** extend `plugins/__tests__/sessionManagerSpec.js` (same faked-cookie + faked-clock
  isolation model; `vi.mock('src/api/session.api', …)` for the dynamic `sessionApi.refresh`;
  `vi.mock('src/utils/sessionCookies', …)` for `hasUserSession`; `vi.spyOn(window, 'dispatchEvent')`
  for `session:expired`):
  - **refreshSession success:** enter the warning band (short `rint`), then a `refresh()` that
    resolves after writing a far-future `rint` → `showWarning` back to `false`, the 1 s countdown
    interval cleared (no leak — assert that a further clock advance does not tick), `refreshFailed=false`,
    `isRefreshing` `true`→`false`.
  - **refreshSession failure:** `refresh()` rejects → `refreshFailed=true`, `isRefreshing=false`,
    `console.error` called, the countdown is still running (failure does not silently clear it).
  - **"torn down" branch (three-step setup):** (1) write a valid future `rint` +
    `startSessionMonitoring()` → interval armed, `rintSeen` set; (2) clear `rint` **and**
    `hasUserSession()` → `false`; (3) call `startSessionMonitoring()` again → `:209` clears the timer
    but leaves `checkIntervalId` truthy → `tick()` hits the torn-down branch → `0` →
    `session:expired` dispatched, `cleanup()` ran, `checkIntervalId` back to `null`. Negatives from
    step (3)'s state: monitoring never armed → no dispatch; `rint` cleared but user cookie present →
    no dispatch; `rint` cleared and `rintSeen` never set → legacy estimate, no dispatch.
  - **multi-tab extension:** with monitoring armed and a `rint` 2 min out (warning showing), rewrite
    `rint` to **30 min** out (> `LEGACY_SESSION_TTL` = 15 min) → next `tick()` clears the warning and
    `timeUntilExpiry` reflects **~30 min** — the *new* assertion vs the existing reference spec's
    case 2 is that the value **exceeds `LEGACY_SESSION_TTL`**, proving there is no clamp. (Warning /
    countdown clearing is already covered by `sessionManagerSpec.js:106-118`; do not duplicate it.)
  - **early-return, no timer armed (characterization):** using the three-step setup above, after
    step (3) `session:expired` was dispatched **once**, `cleanup()` ran, and `checkIntervalId`
    stays `null` — advancing the clock by 30 s produces **no** second `session:expired`.
- **Mutation check:** delete `if (tick()) return;` in `startSessionMonitoring()` → the early-return
  characterization spec sees a second `session:expired` 30 s later and fails; remove
  `refreshFailed.value = true` in the `catch` → the failure spec fails. Note the early-return spec's
  sensitivity also leans on `:209` not nulling `checkIntervalId` — call that out in the Dev Agent
  Record as a latent oddity.

### AC6 — `useSession.js` `handleLogout` + i18n locale switching (closes `deferred-91` `handleLogout` / i18n residual)

- **Ledger:** `deferred-work.md:1151` — `[FRAMEWORK AVAILABLE 2026-09-09 (deferred-104)]`:
  *"the `deferred-91` `handleLogout` / i18n coverage is now in-scope for its own follow-up story."*
  `deferred-91` AC14 is in `useSession.js` (not `MainLayout`); i18n AC9/AC10 were value-only bundle
  edits, so the testable i18n surface is the **locale-switch behaviour** in `MainLayout.vue`.
- **Verified at HEAD:**
  - `src/frontend/src/composables/useSession.js:70` — `async function handleLogout()`
    (`LOGOUT_BACKEND_WAIT_MS = 3000` at `:38`): `stopSessionMonitoring()` → expire `user` cookie →
    **expire `rint` (pre-race)** →
    `await Promise.race([authStore.logout(), new Promise(r => setTimeout(r, LOGOUT_BACKEND_WAIT_MS))])`
    → **expire `rint` a second time (post-race)** — `deferred-91` code-review fix: an authenticated
    response in flight when the pre-race clear ran can re-set `rint` with `path=/` →
    `playerStore.resetSelfPlayerId()` → `cleanup()` → `router.push('/login')`. The exports it drives
    (`stopSessionMonitoring`, `cleanup`, `refreshSession`) are **static named imports** from
    `src/plugins/sessionManager` (`:3-15`).
  - `src/frontend/src/layouts/MainLayout.vue:303` — `changeLanguage(lang)`: `locale.value = lang`,
    `localStorage.setItem('locale', lang)`, `document.cookie = 'lang=; Max-Age=0; path=/'`
    (`deferred-92` chunk-3 fix — unstick a `?language=` `lang` cookie).
  - `MainLayout.vue:313` — `loadLanguagePreference()`: applies `localStorage['locale']` **only if**
    it is one of `languages` (`en-US` / `fr-FR` / `de-DE`). Runs on **every** `MainLayout` mount via
    `onMounted`.
- **Task:**
  - `composables/__tests__/useSessionSpec.js` — `useSession()` invoked inside a wrapper component (it
    calls `useRouter()`) with Pinia + a memory router; `vi.useFakeTimers()`;
    **`vi.mock('src/plugins/sessionManager', { spy: true })`** (a plain `vi.spyOn` on those static
    named imports is not reliably supported); `authStore.logout` and `playerStore.resetSelfPlayerId`
    spied via `createTestingPinia({ stubActions: false })` or explicit spies; `router.push` spied;
    `document.cookie` observed:
    - **happy path order:** `stopSessionMonitoring` → (`user` + `rint` cleared) → `authStore.logout`
      → `resetSelfPlayerId` → `cleanup` → `router.push('/login')`.
    - **`rint` double-clear:** stub `authStore.logout` to resolve only after simulating an in-flight
      response that re-writes `rint`; assert `rint` is expired **again** after the race resolves
      (final `document.cookie` has no live `rint`). Reverting the second
      `document.cookie = 'rint=; …'` line makes this spec fail.
    - **bounded wait:** stub `authStore.logout` to a promise that never resolves; advance fake
      timers by `LOGOUT_BACKEND_WAIT_MS` → `handleLogout()` still resolves (`cleanup` + `router.push`
      run). Reverting the `Promise.race` timeout hangs this spec.
  - `layouts/__tests__/MainLayoutLocaleSpec.js` (or fold into `MainLayoutSpec.js` from AC4). **Add
    `afterEach(() => { i18n.global.locale.value = 'en-US'; localStorage.removeItem('locale') })`** —
    the setup file's `vue-i18n` instance is shared per file and `loadLanguagePreference()` runs on
    every mount, so without the restore the "ignores an invalid `'xx-YY'`" case reads a locale a
    prior test set:
    - `changeLanguage('de-DE')` → `locale.value === 'de-DE'`, `localStorage.locale === 'de-DE'`,
      the `lang` cookie is expired (`Max-Age=0`).
    - `loadLanguagePreference()` applies a valid saved `'fr-FR'`; ignores an invalid `'xx-YY'`
      (locale unchanged from its `afterEach`-restored `'en-US'`).
- **Mutation check:** second `rint` clear (double-clear spec); `Promise.race` timeout arm
  (bounded-wait spec); remove the `document.cookie = 'lang=…'` line in `changeLanguage` → the
  lang-cookie assertion fails.

### AC7 — CI: no workflow change; `frontend-tests` label on the PR (owner decision D2)

- **Task:** **Do not edit** `.github/workflows/frontend-unit-tests.yml`, `.github/workflows/ci.yml`,
  `.github/workflows/pr-build.yml`, or `pom.xml`. `deferred-104` deliberately keeps the Vitest job
  opt-in and out of `mvn verify`; this story respects that.
- The PR that ships this story **must carry the `frontend-tests` label** so
  `frontend-unit-tests.yml` runs the new suite. Its trigger is
  `pull_request: types: [labeled, synchronize, reopened]` — **`opened` is not in the list**, so
  `gh pr create --label frontend-tests` will **not** fire the workflow. Add the label **after** the
  PR exists — `gh pr edit <n> --add-label frontend-tests` (fires `labeled`) — or push a follow-up
  commit (fires `synchronize`), or run it via `workflow_dispatch`. Record this in the Dev Agent
  Record and the PR description.
- **Test:** `cd src/frontend && npm run test:unit` **and** `npm run test:unit:ci` green locally
  (all new specs + the two reference specs); the labelled PR's `frontend-unit` check green (confirm
  it actually ran — a green-because-skipped result does not count).

### AC8 — bound the image-probe remote `docker` calls with `timeout` (closes `deferred-107` CR item 1)

- **Ledger:** `deferred-work.md:1658` — the `image_runtime_uid` probe adds un-timed `docker` calls
  to the disaster-restore path, between `${DC} down` and `${DC} up -d`, on exactly the host
  conditions a restore implies (no / slow egress, pruned images); `|| return 0` only fires once the
  call has **finished** failing.
- **Verified at HEAD — the probe has TWO unbounded calls, in BOTH scripts:**
  - `deploy/backup/restore-from-volume-backup.sh` — `image_runtime_uid()` at `:35`:
    - `:40` — `docker image inspect "$img" … || docker pull "$img" … || return 0`.
    - `:45` — `''|*[!0-9]*) uid=$(docker run --rm --entrypoint sh "$img" -c "id -u '$user'" …) || return 0 ;;`
      — reached whenever `Config.User` is a **name** (not numeric). Of the five services probed at
      `:141-145` (redis / prometheus / loki / tempo / grafana), `grafana`'s image historically
      declares a name, so this path is genuinely reachable in the restore window.
  - `deploy/provision.sh` — the identical `image_runtime_uid()` (`:89`): `docker pull` at `:94`,
    `docker run … id -u` at `:100`.
  - `chown_probed()` (`restore-…:57-63`) invokes the probe at `:141-145`, **inside** the
    `${DC} down` (`:97`) → `${DC} up -d` (`:160`) outage window.
  - The numeric `uid:gid` fallbacks + the WARN-on-mismatch path (`:132-133`) are the documented
    safety net — a `timeout` non-zero exit (124) or any failure falls through the existing
    `|| return 0` → probe yields `""` → numeric-constant fallback + WARN. So bounding these is
    **safe under `set -euo pipefail`**.
- **No `timeout(1)` precedent exists anywhere under `deploy/`** (verified — the only `timeout` hit
  is a prose comment at `restore-from-dump.sh:210`). The tree's bounded-wait idioms are a deadline
  loop (`restore-from-volume-backup.sh:166-172`) and a fixed retry loop
  (`restore-from-dump.sh:212-221`) — neither reusable here. So just introduce a constant and name a
  value; do not send the dev hunting for a precedent.
- **Task:** in **both** scripts' `image_runtime_uid`, add near the top a
  `readonly IMAGE_PULL_TIMEOUT=30` and `readonly IMAGE_PROBE_TIMEOUT=15` (values: a slow pull is
  the real risk; the local `docker run` is fast unless the daemon is wedged), then:
  - `… || timeout "${IMAGE_PULL_TIMEOUT}s" docker pull "$img" >/dev/null 2>&1 || return 0`
  - `uid=$(timeout "${IMAGE_PROBE_TIMEOUT}s" docker run --rm --entrypoint sh "$img" -c "id -u '$user'" 2>/dev/null) || return 0`
  Add a one-line comment citing `skillars-deferred-108 AC8` / `deferred-107` CR.
- **State the aggregate bound** in the comment: the probe runs once per service (5×), so the
  worst-case added outage is `5 × (IMAGE_PULL_TIMEOUT + IMAGE_PROBE_TIMEOUT)` ≈ 225 s, not 30 s.
  If the owner wants that tighter, drop `IMAGE_PULL_TIMEOUT` to ~15 s (a genuinely reachable
  registry answers well inside that; a dead one is what we're bounding).
- **Test:** `bash -n` + `shellcheck` clean on both scripts. No shell harness — Dev Agent Record
  documents the manual reasoning + a `docker compose config` sanity check.

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
  `ConfigBoundsEnumCoverageTest` already fails the build if a **new** enum constant lands without a
  matching bound (it iterates `enum.values()` asserting each has one) — which is the guarantee the
  "must iterate" wording was reaching for. It does **not** catch a *removed* constant leaving a
  stale hand-listed segment — harmless (a bound nothing reads), and the comment should say so
  rather than imply full bidirectional protection.
- **Verified at HEAD:**
  - `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java:193` — comment
    block `// ── Templated per-enum keys — generated, never hand-listed (AC3) …` running to `:197`;
    `:199` `static final List<String> VIDEO_QUOTA_TIER_SEGMENTS = List.of("scout", "instructor", "academy", "athlete");`
    and `:200` `static final List<String> VIDEO_TYPE_SEGMENTS = List.of("homework", "drillDemo", "coachReview");`.
    They are iterated at `:258` / `:267` to generate the per-tier / per-type bound keys.
  - `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`
    — the drift guard.
- **Task (doc + comment only, no behaviour change):**
  - Reword the `ConfigBounds.java:193-197` comment so it no longer says "generated, never
    hand-listed" — state that the segments are **deliberately hand-listed** to keep `config` free of
    a `video.contract` / `marketplace.contract` dependency, and that `ConfigBoundsEnumCoverageTest`
    fails the build **when a new enum constant lands without a matching bound** (additions / renames;
    removals leave a harmless stale segment). Cite `skillars-deferred-108 AC9 (owner decision
    2026-09-10)`.
  - Retag the `deferred-work.md:1659` bullet `[DECIDED 2026-09-10 (skillars-deferred-108 AC9)]`
    (kept in place, not deleted — the decided-retention rule). Recorded in AC10's audit block.
- **Test:** `ConfigBoundsEnumCoverageTest` still green (it is the thing the decision leans on).
  No new test — the AC is a decision + doc.

### AC10 — Ledger hygiene + reconstruction check

> **Re-verify every line number against HEAD before editing** — the numbers below are as of the
> story-creation commit and this file grows on every merge.

- **Delete outright (genuine closures — `deferred-work.md`'s delete-when-closed convention, no
  `[CLOSED by …]` tag left behind):**
  - `## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)`
    (`:656`) — its sole bullet (`:657`, the payment frontend-tests gap). **Section empties → header
    removed.**
  - `## Deferred from: code review of skillars-deferred-17-… (2026-08-06)` (`:911`) — its sole
    bullet, **D6** (`:913`). **Section empties → header removed.**
  - `## Deferred from: skillars-uat-2-… (2026-08-10)` — **D6** only (`:929`); D3 / D4 / D7 stay,
    header stays.
  - `## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard
    (2026-08-19)` — its sole bullet (`:997`). **Section empties → header removed.**
  - `## Deferred from: code review of skillars-deferred-43-… (2026-08-20)` — its sole bullet
    (`:1001`). **Section empties → header removed.** (This closes the *test-coverage* gap `:1001`
    names; the residual return-value leak AC4 surfaces is filed as a **new** bullet below, so it is
    not silently lost.)
  - `## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)` (`:1109`)
    — the `sessionManager.js` state-machine bullet (`:1121`), previously
    `[PARTIALLY ADDRESSED by deferred-104 AC8]`, whose remaining-open clause ("refreshSession
    success/failure, the deferred-90 'torn down' branch, multi-tab extension") is exactly what AC5
    covers. Check whether the section's HTML-comment note / other content survives before removing
    the header.
- **DO NOT delete — annotate only** (both are *decided* items AC5's characterization spec pins but
  does not resolve):
  - `deferred-work.md:1125` — `## Deferred from: code review (round 2) of 1-7b-… (2026-09-02)`, the
    `startSessionMonitoring()` early-return bullet. Verbatim: *"**Deliberately left as-is rather than
    patched**."* The open concern is `App.vue`'s un-awaited `router.push()` + no re-arm — an
    `App.vue` / `vue-router` matter, not `sessionManager.js`. Append: *"[behaviour pinned by
    `skillars-deferred-108` AC5's characterization spec; the `App.vue` router-abort / no-re-arm
    concern is unchanged]."* Header stays.
  - `deferred-work.md:1128` — `## Deferred from: skillars-deferred-90 …`, *"`startSessionMonitoring()`'s
    early-return-with-no-timer path — left as documented (**project-owner decision**)."* Append the
    same "[behaviour pinned by … AC5; decision unchanged]" note. Header stays.
  - `## Deferred from: code review of skillars-deferred-107 (2026-09-10)` — **first bullet**
    (`image_runtime_ugid` un-timed calls, `:1658`) → **delete** (closed by AC8). **Second bullet**
    (`:1659`) → **retag `[DECIDED 2026-09-10 (skillars-deferred-108 AC9)]`** in place. Header stays
    (still has the retagged bullet).
- **Update, do not delete (strike-through + annotate):**
  - `deferred-work.md:1151` — the **"No frontend test framework"** bullet (NOT the de-DE bullet,
    which is the separate `:1149`, untouched). Its dependent list is
    `` `5-4` W9, `deferred-17`/`-18`/`-30`/`-37`/`-38`/`-43` D6``. Strike **only** the trailing
    `[FRAMEWORK AVAILABLE …]` clause's *"the `deferred-91` `handleLogout` / i18n coverage is now
    in-scope for its own follow-up story"* and annotate **CLOSED by `skillars-deferred-108` AC6**.
    Do **not** blanket-close the whole dependent list from this bullet — and note `deferred-30` has
    **no bullet anywhere** in the file (pre-existing stray reference; leave it, it is not this
    story's to fix).
  - `deferred-work.md:1277-1281` (`deferred-104` implementation residual — *"~6 frontend coverage
    gaps are now unblocked, not closed"*). The list reads `` (`deferred-12` payment, `deferred-17`/
    `-18` D6, `deferred-35`/`-36`/`-37`/`-38` `booking.store.js`, `deferred-43` `playerStore.js`,
    `deferred-90`/`-98` remaining `sessionManager` coverage, `deferred-91` `handleLogout` / i18n)``.
    **`deferred-12` is a pre-existing ledger typo for `deferred-11`** (the payment gap is `:657` /
    `skillars-deferred-11`; `skillars-deferred-12` is an unrelated backend story). Correct the typo
    to `deferred-11` and strike/annotate each named dependent **CLOSED by `skillars-deferred-108`
    AC1–AC6**. Leave the sibling `deferred-104` residual bullet (`:1267`, "Quasar Vitest AE
    library-only") **untouched** — still blocked on `@quasar/app-vite` v3.
  - Older cross-reference prose that asserts these are open (`deferred-90` D9 `:941`, prior audit
    narrative) — light strike-through only where it states open status; do not reword narrative.
- **File as NEW bullets under `## Deferred from: skillars-deferred-108 story implementation
  (2026-09-10)`:**
  - `playerStore.js:46` returns `profile.id` **unconditionally** — the `requestGeneration ===
    selfPlayerIdGeneration` guard (`:39`) only suppresses the cached-ref write, not the resolved
    value, so a `fetchSelfPlayerId()` superseded by `resetSelfPlayerId()` still resolves with the
    prior account's id. `BookingRequestPage.vue:628` (`selfPlayerId.value = await
    playerStore.fetchSelfPlayerId()`) feeds that into the submit payload — the residual half of the
    `deferred-43` cross-account-misattribution concern. Characterized by `skillars-deferred-108` AC4;
    fix is a one-liner (gate the `return` on the generation, or resolve `null` for a superseded
    call) but out of scope for a test-backfill story.
  - the `deferred-104` "Quasar Vitest AE library-only" residual stays open (moved-forward pointer,
    not new — only note it here if the audit block's bookkeeping wants it explicit).
- **New audit block** `## Last audit: 2026-09-10 (skillars-deferred-108 story implementation)` —
  enumerate every deleted bullet + emptied header, every annotation / retag / strike-through, the
  new `## Deferred from: skillars-deferred-108` section, and the standard **reconstruction check**
  (every surviving non-blank line matches the pre-edit file at `HEAD` + the deferred-108
  implementation commit, in order, nothing reworded; `[DISMISSED]` count unchanged; `[DECIDED]`
  count `+1`; `[PICKED UP by …]` bullets touched: none).
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
| 2026-09-10 | Story created. HEAD `c74abde9`. Frontend-item cites re-verified against source (line drift corrected throughout: `booking.store.js:326-371`→`:170`/`:388`/`:593`/`:605`; `playerStore.js:24-33`→`:13-14`/`:28`/`:60`; `ConfigBounds.java:180-181`→`:193`/`:199-200`; `restore-from-volume-backup.sh` `image_runtime_ugid`→`image_runtime_uid` `:35`/`:40`/`:45`, window `:97`→`:160`). Owner decisions D1 (all six gaps), D2 (keep `frontend-unit-tests.yml` opt-in, PR carries `frontend-tests` label), D3 (fold both `deferred-107` CR items: AC8 `timeout`-wrap, AC9 accept ConfigBounds hand-listing + retag), D4 (`skillars-7-1` D4 stays its own story). Scope = AC1–AC6 spec backfill + AC7 CI-label note + AC8 restore-probe `timeout` + AC9 ConfigBounds decision + AC10 ledger hygiene. |
| 2026-09-10 | Applied `story-review.md` senior-dev audit (baseline suite re-run, every AC's "Verified at HEAD" re-checked — **no false positives found**). AC1: `@stripe/stripe-js` must be `vi.mock`ed (loads Stripe.js at import); mutation check re-pointed at `expect(loadStripe).not.toHaveBeenCalled()` (deleting the guard is swallowed by the `try/catch`, no throw); branch test restated as `hasCard: true`/`false` (`SavedPaymentMethodResponse` always returns an object). AC2: "merged own rows" → `slotRows` **appends** `type:'own'` rows (`:456-473`), not selectable, not in `batchBasketSize`; require a navigated memory router with `params.coachId`+`query.playerId`+`router.isReady()` and mocks for `fetchSelfPlayerId`/`getBatchConfig`/`getBookingRequestConfig`; dropped the non-observable backend-filter bullet. AC3: `setBatchAcceptResult`/`MAX_BATCH_ACCEPT_RESULTS` are closure-private → LRU test goes via `handleAcceptAllBatch` with a **rejecting** `getCoachBookingRequests` mock (prune runs on success only); `batchId` is a **UUID** → ids must be UUID-shaped, never `'1'..'201'`. AC4: added assertion on the **superseded call's return value** (`playerStore.js:46` returns `profile.id` unconditionally — the residual half of the cross-account concern) → filed as a new ledger bullet in AC10, `:1001` deletion still valid (test-coverage gap closed); finally-reference case restated behaviourally. AC5: added `computeTimeUntilExpiry:140` skew cross-check + `startSessionMonitoring:209` (clears timer, doesn't null `checkIntervalId`) + `recordActivity` before first `tick` → "idle-expired first tick" is impossible; spelled out the 3-step torn-down/early-return recipe; early-return spec reframed as **characterization** (does not close the decided `:1125`/`:1128` bullets). AC6: `vi.mock('src/plugins/sessionManager', { spy: true })` (static ESM named imports can't be `vi.spyOn`ed); added locale-restore `afterEach`; `useSession.js:71`→`:70`. AC7: label must be added **after** PR creation (workflow trigger has no `opened`); run `test:unit:ci` locally too. AC8: wrap **both** `docker pull` **and** `docker run` (reached for name-form `Config.User`, e.g. grafana), in **both** scripts; no `timeout(1)` precedent exists in `deploy/` → introduce constants + name values; state the 5× aggregate bound. AC9: cites `ConfigBounds.java:193`/`:199-200`; drift guard catches additions/renames, not removals. AC10: **do not delete** `:1125`/`:1128` (decided) — annotate; delete only `:1121`; mark deferred-11/17/38/43 headers removed; `:1151` is the "no framework" bullet (de-DE is `:1149`, untouched) — strike only the `handleLogout`/i18n clause; `deferred-12`→`deferred-11` typo correction in the `:1277-1281` roll-up; new `## Deferred from: skillars-deferred-108` section for the `playerStore.js:46` residual. Global conventions gained H1 (fixtures not under `__tests__/`), H3 (Quasar teleport), ESM-spy note. |

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
