# Story Deferred-11: Stripe Card Collection & Parent Payment-Path Correctness

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a parent using Skillars,
I want to add and manage a payment card in the app and have my saved card used for pack purchases and player subscriptions,
so that I can actually complete a purchase — today no screen in the app can collect a card, so every real charge fails.

### Why this story exists

Epic 11 (Stories 11.1 → 11.3) consolidated all session-pack payment onto `payment.session_pack_purchases` and deleted the legacy path. The legacy path was the only purchase flow that worked without a saved card. The cutover review (11.2) recorded this as the blocking gap:

> **D5:** No UI anywhere in the app collects or saves a Stripe payment method (`savePaymentMethod` / `confirmCardSetup` in `payment.api.js` have zero callers app-wide) — pre-existing gap, but this story's cutover retires the only purchase path that worked without a saved card, so any parent without a pre-existing saved payment method cannot complete a first real purchase once this ships. Resolved as ship-as-is (dev/UAT stage, no live production traffic yet per the epic) but **must be closed with a dedicated Stripe Elements card-collection story before real purchase traffic**.

Verified against the current code (2026-08-04): `StripePaymentGateway.chargeAndCapture()` reads `stripeCustomer.getStripePaymentMethodId()` and throws when it is `null` (`StripePaymentGateway.java:56-57`). That column is only ever written by `SessionPackPaymentResource.savePaymentMethod()` and `SessionPackPaymentService.getOrCreateStripeCustomer()` — both of which require a `paymentMethodId` the frontend has no way to produce. **Every** money-moving path (pack purchase, per-session booking charge, batch booking charge) is therefore dead in a real environment.

This story also folds in four smaller deferred items that live in exactly the same files and would otherwise force a second pass over the same code (see **Deferred Items Closed** below).

## Deferred Items Closed

| Source | Item | AC |
|---|---|---|
| `skillars-11-2-cutover-booking-and-frontend` (2026-08-03) | **D5** — no UI collects/saves a Stripe payment method | 1–5 |
| `skillars-7-2` Group 5 adversarial (2026-06-24) | **D17** — `loadStripe(undefined)` throws when the publishable key is null; "fix belongs in the component that owns `stripeStatus` state" | 2 |
| `skillars-7-2` Group 5 adversarial (2026-06-24) | **D18** — shared `loading`/`error` flags across concurrent `payment.store.js` actions | 6 |
| `skillars-11-2-cutover-booking-and-frontend` (2026-08-03) | **D1** — TOCTOU between `hasActivePack()` and `getActivePackId()` can attach an exhausted/expired pack id to a duplicated booking | 7 |
| `skillars-11-2-cutover-booking-and-frontend` (2026-08-03) | **D3** — shared `sessionPacks` Pinia ref gets narrowed to one child's packs | 8 |
| `skillars-11-2-cutover-booking-and-frontend` (2026-08-03) | **D4** — failed coach-name lookups are not negative-cached and repeat on every store update | 9 |

**Explicitly NOT in this story** (checked and rejected, do not implement):

- **11.2 D2** (pack-selection criteria mismatch: frontend shows soonest-expiring, backend `getActivePackId` picks oldest-created FIFO) — the deferral note itself says *"May be intentional (display urgency vs. FIFO consumption order), needs product input."* Requires a product decision, not an engineering fix. AC 7 deliberately preserves the existing FIFO backend ordering.
- **11.1 D1–D9** — all nine are byte-for-byte legacy-parity behaviours that Story 11.1's AC4 *required* mirroring. Changing them contradicts a shipped AC.
- **11.3 D1–D3** — migration `IF EXISTS` convention and staged-rollout concerns; both are explicitly recorded as "not applicable now — no live/production system exists yet."
- **Coach subscription card collection** (`CoachSubscriptionPage.vue`, which has the same raw "Payment Method ID" text input) — **blocked by a real schema constraint**: `StripeCustomer`'s `@Id` is `parent_id` (a `Long` user id) and `POST /api/payment/setup-intent` is `@PreAuthorize(HAS_PARENT_ROLE)`. Supporting coaches requires either a second customer table or re-keying `payment.stripe_customers`, which is a schema migration with its own design decision. Leave `CoachSubscriptionPage.vue` untouched and log a new deferred item (Task 11).

## Acceptance Criteria

1. **Given** a parent is authenticated
   **When** the frontend needs to initialise Stripe.js
   **Then** it fetches the publishable key from a new backend endpoint `GET /api/payment/stripe/config` returning `{ "publishableKey": "pk_..." }`
   **And** the key is sourced from a new `app.payment.stripe.publishable-key` property on `PaymentProperties`, wired in `application.yaml` as `${APP_PAYMENT_STRIPE_PUBLISHABLE_KEY:}` (same pattern as the four existing `app.payment.stripe.*` keys)
   **And** the endpoint carries `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)` — the publishable key is not a secret, but the endpoint is not anonymous either
   **And** the key is **never** hardcoded in frontend source and **never** read from a Quasar build-time env (`quasar.config.js` has no `env` block configured; do not add one)

2. **Given** the publishable key is absent, empty, or the config fetch fails
   **When** any card-collection UI is opened
   **Then** `loadStripe()` is never invoked with `null`/`undefined` — the UI shows a localized "card payments are unavailable" message and disables the submit control instead of throwing a TypeError
   **And** the guard lives in the component/store that owns the key state, **not** inside `payment.api.js` (`confirmPackPayment` / `confirmCardSetup` keep their current caller-supplies-the-key signature)
   *(closes 7.2 Group 5 D17)*

3. **Given** a parent opens the payment-method section on `/parent/credit-wallet`
   **When** they submit a valid test card through a Stripe Elements card field
   **Then** the flow is: `POST /api/payment/setup-intent` → `stripe.confirmCardSetup(clientSecret)` (existing `confirmCardSetup` in `payment.api.js`) → `POST /api/payment/save-payment-method` with the resulting `setupIntent.payment_method`
   **And** on success the section shows the saved card's brand, last4, and expiry, sourced from a new `GET /api/payment/payment-method` endpoint
   **And** submitting a declined/invalid card surfaces the Stripe error message inline and leaves any previously saved card untouched
   **And** raw card data (PAN, CVC, expiry) never touches Vue reactive state, the Pinia store, an API payload, or a log line — it lives only inside the Stripe-hosted iframe element

4. **Given** `GET /api/payment/payment-method` is called by an authenticated parent
   **When** the parent has a `payment.stripe_customers` row with a non-null `stripe_payment_method_id`
   **Then** it returns `200` with `{ hasCard: true, brand, last4, expMonth, expYear }`, populated by a new `StripeClient.retrievePaymentMethod(String paymentMethodId)` wrapper
   **And** when there is no row, or `stripe_payment_method_id` is null, it returns `200` with `{ hasCard: false, brand: null, last4: null, expMonth: null, expYear: null }` — **not** `204` and **not** `404` (see 7.2 Group 3 D11: an existing `204`-returning GET on this same resource was itself flagged as unidiomatic; do not repeat it)
   **And** when Stripe itself errors on retrieve, the endpoint returns `hasCard: true` with null card details rather than failing the whole request — a parent must never be told they have no card when they do
   **And** the response DTO is a Java `record` in `platform.payment.contract` per project convention

5. **Given** a parent is on `SessionPackPurchasePage.vue` or `PlayerSubscriptionPage.vue`
   **When** the page loads and `GET /api/payment/payment-method` reports `hasCard: false`
   **Then** the purchase/subscribe control is disabled with a localized prompt to add a card, and an "Add card" action opens the same card-collection component used on the wallet page
   **And** after the card is saved in that dialog the purchase/subscribe control becomes enabled without a full page reload
   **And** `PlayerSubscriptionPage.vue`'s raw `paymentMethodId` `q-input` (`PlayerSubscriptionPage.vue:114-119`, `paymentMethodId` ref at line 154) is **removed** — `confirmSubscribe()`'s guard becomes a saved-card check (`hasCard === false` → disable/prompt, same treatment as the purchase page) and `paymentStore.subscribePlayer({ playerId, tier, billingInterval })` is called with **no** `paymentMethodId` field at all, never a value typed or resolved by the frontend
   **And** correspondingly on the backend, `PlayerSubscribeRequest.paymentMethodId` (currently `@NotNull String`, `PlayerSubscribeRequest.java:9`) is **removed from the request DTO** — `GET /api/payment/payment-method`'s response (AC 4) deliberately does not expose the raw Stripe `pm_...` id to the frontend (display-only fields only), so the frontend has no value to send; `SubscriptionResource.subscribePlayer()` (`SubscriptionResource.java:104-105`) drops the trailing argument, and `SubscriptionService.subscribePlayer()` (`SubscriptionService.java:266-300`) resolves the payment method itself via `stripeCustomerRepository.findById(parentUserId).map(StripeCustomer::getStripePaymentMethodId)`, throwing `PaymentGatewayException("payment.noPaymentMethod")` when absent — this mirrors the exact fallback `SessionPackPaymentService.getOrCreateStripeCustomer` (lines 176-189) already uses for pack purchases, just made unconditional here instead of a null-triggered fallback, since the player-subscribe path never had a legitimate caller-supplied id to fall back from in the first place
   **And** the existing `stripeClient.attachPaymentMethod(stripeCustomerId, paymentMethodId)` call inside `SubscriptionService.subscribePlayer()` (`SubscriptionService.java:291`) is **deleted** — a payment method resolved from the stored `StripeCustomer` row was already attached to that exact customer when it was saved (`POST /setup-intent` creates a customer-scoped SetupIntent; `confirmCardSetup` + `POST /save-payment-method` complete it), and Stripe's `PaymentMethod.attach` throws ("already been attached to a customer") when called again on a payment method already attached to that same customer — calling it unconditionally, as the method does today, would make every real player-subscription attempt fail with `payment.subscription.stripeError`. Do **not** touch `SubscriptionService.subscribeCoach()`'s equivalent call (`SubscriptionService.java:130`) — that path still takes a caller-supplied id from `CoachSubscriptionPage.vue`'s raw input and is explicitly out of scope
   **And** `SessionPackPurchasePage.vue`'s existing call `bookingStore.purchasePack(playerId, selected.value)` (which passes no `paymentMethodId` and relies on the backend's saved-card fallback) keeps working unchanged — the backend already falls back to the stored card when `paymentMethodId` is null (`SessionPackPaymentService.getOrCreateStripeCustomer`, lines 176-189)

6. **Given** two `payment.store.js` actions are in flight concurrently
   **When** the faster one completes
   **Then** it does not clear the slower one's spinner or overwrite its error — the single shared `loading` / `error` state pair is replaced by per-action keys (e.g. `loading: { stripeStatus: false, creditBalance: false, ... }` + matching `error` map, or one small helper that owns a keyed map)
   **And** all four existing consumers of `payment.store.js`'s `loading` / `error` are updated — found by checking every file that imports `usePaymentStore`, **not** by grepping the literal strings `store.loading` / `store.error`: that pattern also matches `booking.store.js` (`AvailabilityManagerPage.vue`), `profileBuilder.store.js`/`auth.store.js` (`CoachProfileBuilderPlaceholderPage.vue`), and `development.store.js` (`PlayerDevelopmentDashboardPage.vue`, `PerformanceReportsPanel.vue`, `GenerateReportDialog.vue`, `ParentDevelopmentPortalPage.vue`) — none of those use `payment.store.js` and none should be touched
     - `ParentCreditWalletPage.vue:11` (`store.loading` — balance skeleton)
     - `RevenueDashboardPage.vue:25,67` (`paymentStore.loading` — drives the loading state for **both** `fetchRevenueSummary` and `fetchTransactions`; after keying, the template usage must OR the two corresponding keys, e.g. `paymentStore.loading.revenueSummary || paymentStore.loading.transactions`)
     - `CreditStatementPage.vue:25,35` (`paymentStore.loading` — `fetchCreditStatement`)
     - `CoachPaymentSettingsPage.vue:20,43` (`paymentStore.error`, `paymentStore.loading` — `fetchStripeStatus`)
   **And** no page regresses to a permanently-spinning or never-clearing state
   *(closes 7.2 Group 5 D18)*

7. **Given** `BookingDuplicationService.duplicateBooking()` resolves the pack to attach to the new booking
   **When** the player's active pack is consumed or expires between the current `hasActivePack()` check (`BookingDuplicationService.java:57`) and the current `getActivePackId()` call (line 61)
   **Then** the duplication fails loudly with the existing `OperationNotAllowedException("No effective session credits available for this coach", SecurityError.MISSING_RIGHTS)` rather than silently attaching an exhausted/expired pack id
   **And** this is achieved by a single new payment-critical method on `PackSessionService` — one `findActivePacks(playerId, coachId, now)` call, returning the first result or throwing — with **no** `findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc` fallback (that unfiltered "most recent pack ever" fallback is the actual defect: `PackSessionService.java:86-92`)
   **And** the existing `hasActivePack()` and `getActivePackId()` methods are **left in place unchanged** — `HomeworkAssignmentService.java:102,166` calls both on a non-payment gating/read path and is out of scope for this story
   **And** the two-call sequence in `BookingDuplicationService` is replaced by the single new call
   **And** ordering semantics are unchanged: `findActivePacks` keeps its `ORDER BY p.createdAt ASC` (oldest-created FIFO). Do not "fix" this to match the frontend's soonest-expiring display — that mismatch is 11.2 D2 and needs product input
   *(closes 11.2 D1)*

8. **Given** `booking.store.js`'s `loadPlayerPacks(playerId)` narrows the shared `sessionPacks` ref to a single player's packs (`booking.store.js:218-231`)
   **When** a future view needs packs across multiple children
   **Then** the store no longer silently returns another child's packs to a caller that did not load them — implement this as: the store additionally tracks which `playerId` the current `sessionPacks` content belongs to (e.g. a `sessionPacksPlayerId` ref set alongside `sessionPacks` inside `loadPlayerPacks`), so a future consumer can compare that tag against the player it cares about before trusting `sessionPacks`, and a mismatch is detectable rather than silently wrong
   **Do not** implement this as "keep the full parent-wide result in state with a player-scoped getter" — `SessionPackPurchasePage.vue:96`, `SessionPackDashboardPage.vue:9,24`, and `ParentPlayerPortalPage.vue:103` (plus `CoachPublicProfilePage.vue` and `BookingRequestPage.vue` via `creditsForCoach`) all read `bookingStore.sessionPacks` as a raw array directly, immediately after each one calls `loadPlayerPacks` with its own resolved player id — none go through a getter. Switching to a parent-wide-plus-getter shape would require rewriting every one of those read sites, which contradicts the next line
   **And** all four current consumers keep working unchanged: `SessionPackPurchasePage.vue`, `SessionPackDashboardPage.vue`, `ParentPlayerPortalPage.vue`, and the `creditsForCoach` computed (`booking.store.js:147-151`) — note this means `creditsForCoach` still only reflects whichever player was most recently loaded by whoever last called `loadPlayerPacks`; every current caller of `creditsForCoach` already loads its own player's packs immediately beforehand, so this is not a regression, and fully aggregating credits across all of a parent's children is a larger, separate change that is explicitly **not** in scope here
   *(closes 11.2 D3)*

9. **Given** `SessionPackDashboardPage.vue` resolves coach display names via `getCoachProfile(id).catch(() => null)` (`SessionPackDashboardPage.vue:149-159`)
   **When** a lookup fails or the coach profile is missing
   **Then** the failure is recorded so the same id is not re-fetched on every subsequent `bookingStore.sessionPacks` watch firing — failed ids are negative-cached by storing `null` in `coachNames`
   **And** the `unresolvedIds` filter (`SessionPackDashboardPage.vue:150`, currently `.filter((id) => !coachNames.value[id])`) is changed from a truthiness check to a key-presence check, e.g. `.filter((id) => !(id in coachNames.value))` — a plain truthiness check does **not** exclude a `null`-cached id (`!null === true`), so leaving the existing predicate untouched while only adding the `null` write would keep refetching failed ids on every watch firing, reproducing the exact bug this AC exists to close
   **And** the template's `coachNames[pack.coachId] ?? pack.coachId` fallback still renders (unaffected by the `null` values — `??` treats `null` the same as `undefined`)
   *(closes 11.2 D4)*

10. **Given** any new user-facing string added by this story
    **When** it is rendered
    **Then** it exists in **all four** locale files — `en-US` (production default), `en`, `de`, `fr-FR` — under the existing `payment.*` namespace, with real German and French translations (not English placeholders)
    **And** no literal user-facing string is hardcoded in a `.vue` template

## Tasks / Subtasks

- [x] **Task 1 — Publishable key config + endpoint** (AC: 1, 2)
  - [x] Add `private String publishableKey = "";` to `PaymentProperties.java` with a Javadoc line matching the file's existing comment style
  - [x] Add `publishable-key: ${APP_PAYMENT_STRIPE_PUBLISHABLE_KEY:}` under `app.payment.stripe` in `application.yaml` (line ~227-234)
  - [x] Add `StripeConfigResponse(String publishableKey)` record to `platform.payment.contract`
  - [x] Add `GET /api/payment/stripe/config` to `SessionPackPaymentResource` with `@PreAuthorize(SecurityConstants.IS_AUTHENTICATED)`
  - [x] Document `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` alongside the existing `APP_PAYMENT_STRIPE_*` vars in all three places that carry them: `docs/deployment/uat-deployment.md`, `.env.uat`, and `docs/dev-docs/payment/index.html`

- [x] **Task 2 — Saved payment-method read endpoint** (AC: 4)
  - [x] Add `public PaymentMethod retrievePaymentMethod(String id) throws StripeException { return PaymentMethod.retrieve(id); }` to `StripeClient.java` (the `PaymentMethod` import already exists there for `attachPaymentMethod`)
  - [x] Add `SavedPaymentMethodResponse(boolean hasCard, String brand, String last4, Long expMonth, Long expYear)` record to `platform.payment.contract`
  - [x] Add `GET /api/payment/payment-method` to `SessionPackPaymentResource` with `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)`, resolving `parentId` via `securityUtil.getCurrentCoachUserId()` (yes — that misnamed method is what every parent endpoint in this resource already uses; follow it, do not rename it)
  - [x] Catch `StripeException`/`PaymentGatewayException` on retrieve → return `hasCard: true` with null detail fields; log at WARN with the parent id only (never the payment-method id or any card data)

- [x] **Task 3 — Card collection component** (AC: 2, 3, 10)
  - [x] Create `src/frontend/src/components/payment/PaymentMethodCard.vue` (`<script setup>`): shows saved-card summary or an "Add card" / "Replace card" action, and hosts the Stripe Elements card field
  - [x] Mount the Elements card element against the key from `GET /api/payment/stripe/config`; if the key is empty/absent, render the unavailable state and disable submit (AC 2 guard lives here)
  - [x] Submit flow: `createSetupIntent()` → `confirmCardSetup(publishableKey, clientSecret)` → `savePaymentMethod(setupIntent.payment_method)` → refetch `GET /api/payment/payment-method`
  - [x] Emit a `saved` event so parent pages can re-enable their purchase control without a reload
  - [x] Destroy/unmount the Elements instance in `onBeforeUnmount` to avoid leaking iframes across route changes
  - [x] Style with the existing `glass-card` class and `--accent-*` / `--border-subtle` CSS tokens (see `SessionPackPurchasePage.vue`'s `<style lang="scss" scoped>` block for the in-repo pattern)

- [x] **Task 4 — API + store wiring** (AC: 1, 3, 4, 6)
  - [x] Add `getStripeConfig()` and `getSavedPaymentMethod()` to `src/frontend/src/api/payment.api.js`
  - [x] Add `stripeConfig` and `savedPaymentMethod` state + actions to `payment.store.js`
  - [x] Replace the single `loading` / `error` pair in `payment.store.js` with per-action keyed state (AC 6); update the four named consumers in AC 6 — `ParentCreditWalletPage.vue`, `RevenueDashboardPage.vue`, `CreditStatementPage.vue`, `CoachPaymentSettingsPage.vue` — identified by which files import `usePaymentStore`, not by a bare grep for `store.loading`/`store.error` (that string also matches `booking.store.js`, `profileBuilder.store.js`, `auth.store.js`, and `development.store.js` consumers using the same local alias; leave those untouched)

- [x] **Task 5 — Wallet page integration** (AC: 3, 6)
  - [x] Add a payment-method `q-card` section to `ParentCreditWalletPage.vue` (route `/parent/credit-wallet` already exists — do **not** add a new route), rendering `PaymentMethodCard.vue`
  - [x] Update `ParentCreditWalletPage.vue:11`'s `store.loading` usage for the new keyed store shape

- [x] **Task 6 — Purchase & subscription gating** (AC: 5)
  - [x] `SessionPackPurchasePage.vue`: fetch saved payment method on mount; disable the confirm button and show an add-card prompt when `hasCard === false`; re-enable on the component's `saved` event
  - [x] `PlayerSubscriptionPage.vue`: delete the `paymentMethodId` `q-input` (lines ~114-119) and the `paymentMethodId` ref (line ~154); in `confirmSubscribe()` replace the `if (!paymentMethodId.value)` guard with a saved-card guard (`hasCard === false` → disable/prompt); `paymentStore.subscribePlayer({ playerId, tier, billingInterval })` is called with no `paymentMethodId` field
  - [x] `PlayerSubscribeRequest.java`: remove the `paymentMethodId` field
  - [x] `SubscriptionResource.subscribePlayer()` (`SubscriptionResource.java:104-105`): drop the trailing `request.paymentMethodId()` argument from the call into `subscriptionService.subscribePlayer(...)`
  - [x] `SubscriptionService.subscribePlayer()` (`SubscriptionService.java:266-300`): resolve the payment method internally via `stripeCustomerRepository.findById(parentUserId)` → `getStripePaymentMethodId()`, throwing `PaymentGatewayException("payment.noPaymentMethod")` when the row or the id is null (defence in depth — AC 5's UI gating should make this unreachable in practice); **delete** the `stripeClient.attachPaymentMethod(...)` call at line 291 (the resolved id is already attached to the customer — see AC 5)
  - [x] Do **not** touch `CoachSubscriptionPage.vue`, `SubscriptionService.subscribeCoach()` (line ~102-134), or its `attachPaymentMethod` call at line ~130 — that path is unaffected and out of scope
  - [x] Remove the now-orphaned i18n keys `subscription.paymentMethodId` and `subscription.player.paymentMethodRequired` from all four locale files (`en-US`, `en`, `de`, `fr-FR`) — dead once the raw input is deleted — **deviation**: `subscription.paymentMethodId` (and `paymentMethodHint`) turned out to still be used by the explicitly out-of-scope `CoachSubscriptionPage.vue`, so only the confirmed-orphaned `subscription.player.paymentMethodRequired` was removed; recorded as D3 in `deferred-work.md`

- [x] **Task 7 — Pack resolution TOCTOU** (AC: 7)
  - [x] Add the single-call payment-critical resolver to `PackSessionService` (`@Transactional(readOnly = true)`, one `findActivePacks(...)`, throw `OperationNotAllowedException` with `SecurityError.MISSING_RIGHTS` when empty)
  - [x] Replace `BookingDuplicationService.java:57-61`'s two-call sequence with the new method, preserving the existing exception message string
  - [x] Leave `hasActivePack()`, `getActivePackId()`, and both `HomeworkAssignmentService` call sites untouched

- [x] **Task 8 — Store scoping & negative caching** (AC: 8, 9)
  - [x] `booking.store.js`: add the `sessionPacksPlayerId` scope tag per AC 8 (do **not** change `sessionPacks` to a parent-wide shape or add a getter); verify `creditsForCoach` and all four consumer pages still behave
  - [x] `SessionPackDashboardPage.vue`: negative-cache failed `getCoachProfile` lookups per AC 9, including changing the `unresolvedIds` filter predicate from a truthiness check to a key-presence check

- [x] **Task 9 — i18n** (AC: 10)
  - [x] Add every new key under `payment.*` to `en-US`, `en`, `de`, and `fr-FR` (`src/frontend/src/i18n/{locale}/index.js`) with genuine translations

- [x] **Task 10 — Tests** (AC: 1, 4, 5, 7)
  - [x] Backend IT: new `SessionPackPaymentResourceIT` (or extend `SessionPackPurchaseIT`) covering `GET /api/payment/stripe/config` (200 for authenticated) and `GET /api/payment/payment-method` for all three shapes — no customer row → `hasCard:false`; row with null pm → `hasCard:false`; row with pm → `hasCard:true` + card detail fields (mock `StripeClient.retrievePaymentMethod`)
  - [x] Backend IT/unit: `@PreAuthorize` enforcement — a coach principal calling `GET /api/payment/payment-method` gets 403
  - [x] Backend unit test: the new `PackSessionService` resolver throws when `findActivePacks` returns empty, and never consults `findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc` (assert the repository method is never invoked via Mockito `verify(..., never())`) — this assertion is the whole point of AC 7
  - [x] Backend test: `BookingDuplicationService` duplication fails with `OperationNotAllowedException` when no active pack exists
  - [x] Backend test (extend `SubscriptionLifecycleIT.java`, which already mocks `StripeClient` — see its `attachPaymentMethod` stub at line 93): `SubscriptionService.subscribePlayer()` resolves `paymentMethodId` from the `StripeCustomer` row and passes it to `stripeClient.createSubscription(...)`, and asserts `stripeClient.attachPaymentMethod(...)` is **never** invoked (Mockito `verify(..., never())`) — this is the assertion that catches a regression back to the "already attached" Stripe error
  - [x] Backend test (same file): `SubscriptionService.subscribePlayer()` throws `PaymentGatewayException("payment.noPaymentMethod")` when no `StripeCustomer` row exists, and when the row exists but `stripePaymentMethodId` is null
  - [x] Run the full suite: `mvn -o verify` (unit + IT), then `npx eslint .` and `quasar build` from `src/frontend`

- [x] **Task 11 — Record follow-up deferrals**
  - [x] Append to `_bmad-output/implementation-artifacts/deferred-work.md` under a new `## Deferred from: skillars-deferred-11-stripe-card-collection` heading: (a) coach subscription card collection is blocked on `payment.stripe_customers` being keyed by `parent_id`; (b) 11.2 D2 pack-selection-criteria mismatch still needs product input

### Review Findings

- [x] [Review][Patch] AC6: `RevenueDashboardPage.vue` transactions table still binds the bare `paymentStore.loading` object (now always truthy after the keyed-loading refactor) — spinner never clears [src/frontend/src/pages/coach/RevenueDashboardPage.vue:67]
- [x] [Review][Patch] AC6: `CreditStatementPage.vue` statement table has the same unkeyed `:loading="paymentStore.loading"` binding — spinner never clears [src/frontend/src/pages/parent/CreditStatementPage.vue:35]
- [x] [Review][Patch] `PaymentMethodCard.vue` `savedLabel` interpolates `brand`/`last4`/`expMonth`/`expYear` with no null guard — AC4's documented degraded response (`hasCard:true` + null fields on a Stripe retrieve error) renders a garbled label instead of a clear fallback [src/frontend/src/components/payment/PaymentMethodCard.vue:15-20]
- [x] [Review][Patch] `SessionPackPaymentService.getSavedPaymentMethod` dereferences `paymentMethod.getCard()` with no null check — a non-card `PaymentMethod` throws an uncaught NPE (500) instead of hitting AC4's mandated graceful degradation, since NPE isn't caught by the surrounding `catch (StripeException e)` [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:190-192]
- [x] [Review][Patch] `SessionPackPaymentService.getSavedPaymentMethod`'s Stripe-failure log line never passes the caught exception to the logger, discarding the actual error for diagnosis [src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:194]
- [x] [Review][Patch] `PaymentMethodCard.vue` `.card-element` background is hardcoded (`rgba(255,255,255,0.03)`) instead of the existing `--input-bg` design token — mismatched/washed-out on the light theme [src/frontend/src/components/payment/PaymentMethodCard.vue:187]
- [x] [Review][Patch] `SessionPackPurchasePage.vue` "add a card" warning banner hardcodes `rgba(255,165,0,0.15)` instead of the existing `--surface-warning` token, inconsistent with the sibling error banner added in this same diff [src/frontend/src/pages/parent/SessionPackPurchasePage.vue:48]
- [x] [Review][Patch] `PaymentMethodCard.submit()` reports a generic save failure if `paymentStore.fetchSavedPaymentMethod()` throws after `savePaymentMethod()` already succeeded — the card was actually saved but the user sees an error [src/frontend/src/components/payment/PaymentMethodCard.vue:151-156]
- [x] [Review][Patch] `PaymentMethodCard.submit()` treats any `confirmCardSetup` result without a top-level `error` as success and persists immediately, without checking `setupIntent.status` (e.g. `requires_action`) [src/frontend/src/components/payment/PaymentMethodCard.vue:139-151]
- [x] [Review][Patch] `mountCardElement`'s `stripe.elements()` / `cardElement.mount()` calls are not wrapped in try/catch — a synchronous Stripe.js throw becomes an unhandled rejection with no user-facing fallback [src/frontend/src/components/payment/PaymentMethodCard.vue:104,113-114]
- [x] [Review][Defer] `PackSessionServiceParityTest` mocks `findActivePacks` to already return ordered results and only asserts `packs.get(0)` — doesn't exercise the real repository `ORDER BY`, so a regression to actual query ordering wouldn't be caught [src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java] — deferred, pre-existing test-quality gap, not a functional defect (AC7's ordering is otherwise correct in this diff)
- [x] [Review][Defer] `PaymentMethodCard.vue`'s `watch(showForm)` has no in-flight guard against rapid toggle races between async `mountCardElement()` and sync `unmountCardElement()` [src/frontend/src/components/payment/PaymentMethodCard.vue:124-127] — deferred, low-probability race that self-heals on the next full remount
- [x] [Review][Defer] `PaymentMethodCard.vue`'s `stripeUnavailable` state has no retry affordance short of a full page reload [src/frontend/src/components/payment/PaymentMethodCard.vue:86-106] — deferred, AC2 only requires the unavailable message + disabled submit, which is satisfied; retry is a UX enhancement beyond spec scope
- [x] [Review][Defer] No frontend tests (Vitest/Vue Test Utils) were added for `PaymentMethodCard.vue` or the new `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) [src/frontend/src/components/payment/PaymentMethodCard.vue, src/frontend/src/stores/payment.store.js] — deferred, real coverage gap but a substantial addition, not a quick patch

Dismissed as noise (checked against spec/git history, not real issues): `booking.store.js`'s inert `sessionPacksPlayerId` tag (exactly AC8's intended design — a passive marker for a *future* consumer), `SessionPackDashboardPage.vue`'s permanent-for-session negative cache (exactly AC9's intended fix for 11.2 D4), `SubscriptionService.java`'s fully-qualified inline exception references (pre-existing pattern, 26 occurrences before this diff), and `securityUtil.getCurrentCoachUserId()` being used to resolve a parent's id (pre-existing misleading-but-consistent convention used at 5+ other call sites in this same file before this diff).

## Dev Notes

### Current state of the files you are changing — read before editing

**`SessionPackPaymentResource.java`** (the resource you extend)
- Base path `@RequestMapping("/api/payment")`, class-level `@Observed(name = "payment.session-packs")`.
- Already injects `stripeCustomerRepository`, `paymentGateway`, `securityUtil` — you need no new fields for Task 1/2 except `StripeClient` (or better: put the retrieve behind `PaymentGateway`/a service and keep the resource thin — see "Where the retrieve logic goes" below).
- `POST /setup-intent` (line 138) and `POST /save-payment-method` (line 158) already exist and are correct. **Do not rewrite them.** Both lazily create the `StripeCustomer` row via `paymentGateway.createStripeCustomer(parentId)` and both handle `DataIntegrityViolationException` on the create race. Your new `GET /payment-method` must **not** create a customer row — a read must not have that side effect; return `hasCard: false` when no row exists.
- Every parent endpoint here resolves the id with `securityUtil.getCurrentCoachUserId()`. The name is wrong (it returns the current user id, used for both roles) but it is the established call in this file — match it.

**Where the retrieve logic goes.** `SessionPackPaymentResource` already reaches into `stripeCustomerRepository` and `paymentGateway` directly, so a small inline read is consistent with the file. But `StripeClient` is currently only injected into services, never a resource. Prefer: add a `SavedPaymentMethodResponse getSavedPaymentMethod(Long parentId)` method to `SessionPackPaymentService` and have the resource call that. Do **not** add a `StripeClient` field to the resource.

**`StripePaymentGateway.java:56-57`** — the reason this story exists:
```java
String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
if (paymentMethodId == null) { /* throws */ }
```
Nothing you write may change this contract. This story makes the column *populated*, it does not change how it is consumed.

**`SessionPackPaymentService.getOrCreateStripeCustomer(parentId, paymentMethodId)`** (lines 176-189) — when `paymentMethodId` is null/blank it keeps the existing stored card. That is why AC 5 leaves `SessionPackPurchasePage.vue`'s two-arg `purchasePack(playerId, packTierId)` call alone: the saved card is picked up server-side. Do not add a third argument threading the pm id through the frontend — that would put a Stripe id into a Vue component for no reason.

**`payment.api.js`** — `confirmPackPayment` and `confirmCardSetup` already exist and already take the publishable key as their first argument, with an explicit comment that `loadStripe` is called *only* from this file. Keep that boundary. The AC-2 null-key guard goes in the caller (`PaymentMethodCard.vue` / the store), not inside these two functions.

**`payment.store.js`** — Options-API style (`defineStore('payment', { state, actions })`), unlike `booking.store.js` which is setup-style. Keep `payment.store.js` in Options style; do not convert it.

**`booking.store.js:206-231`** — read the existing multi-paragraph comment above `normalizePack` before touching `loadPlayerPacks`. It documents the 11.2 field-name normalisation (`purchaseId`/`remainingSessions` → `id`/`creditsRemaining`) that templates depend on. Your AC-8 change must preserve `normalizePack`'s output shape exactly.

**`PackSessionService.java:81-92`** — both `hasActivePack` and `getActivePackId` are `@Transactional(readOnly = true)` and each calls `Instant.now()` independently, so the two calls in `BookingDuplicationService` are genuinely two separate snapshots. The fallback in `getActivePackId` (`findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc`, no expiry/remaining filter) is what turns the race into a wrong-pack attachment rather than a clean failure.

**`SessionPackPurchaseRepository.findActivePacks`** (lines 33-42) is the correct query and already filters `remainingSessions > 0 AND expiresAt > :now AND (pausedUntil IS NULL OR pausedUntil <= :now)`, ordered `createdAt ASC`. Reuse it as-is; add no new query.

**`SubscriptionService.subscribePlayer(parentUserId, playerId, tier, billingInterval, paymentMethodId)`** (`SubscriptionService.java:266-300`) — today `paymentMethodId` is a caller-supplied `@NotNull` string threaded all the way from `PlayerSubscribeRequest`. Two things about it must change for AC 5 to actually work:
1. Line 291 calls `stripeClient.attachPaymentMethod(stripeCustomerId, paymentMethodId)` unconditionally before `createSubscription`. Once the frontend only ever sends the card saved via the `POST /setup-intent` → `confirmCardSetup` → `POST /save-payment-method` flow (Task 3), that payment method is *already* attached to `stripeCustomerId` — Stripe's `PaymentMethod.attach` errors on a payment method already attached to the same customer, so this call must be deleted from `subscribePlayer`. `subscribeCoach`'s identical-looking call at line 130 is a different code path (still fed by a raw caller-supplied id from the untouched `CoachSubscriptionPage.vue`) and must be left alone.
2. `paymentMethodId` must stop being a request parameter at all — `GET /api/payment/payment-method` (AC 4) intentionally never returns the raw `pm_...` id (only display fields), so there is no value for the frontend to send. Resolve it server-side the same way `chargeAndCapture` and `getOrCreateStripeCustomer` already do: read `stripeCustomerRepository.findById(parentUserId).getStripePaymentMethodId()`.

Both `StripeClient` and `stripeCustomerRepository` are already constructor-injected into `SubscriptionService` — no new fields needed.

### Project rules that apply here (from `_bmad-output/project-context.md`)

- **DTOs are Java `record`s.** `StripeConfigResponse` and `SavedPaymentMethodResponse` must be records in `platform.payment.contract`.
- **Every resource method needs `@PreAuthorize`** using `SecurityConstants`. No exceptions.
- **Never return JPA entities from a controller** — `StripeCustomer` must not leak out of the resource.
- **Never log secrets or sensitive data.** No card data, no `pm_*` id, no client secret in any log line. Log parent id only.
- **All frontend API calls live in `src/frontend/src/api/*.api.js`.** No `api.get(...)` inside a `.vue` file.
- **`<script setup>` + `async/await`** for all Vue; no `.then()` chains.
- **Prettier is mandatory** for `.js`/`.vue`/`.scss`; **ESLint must pass**.
- **All user-facing text via `vue-i18n`.** No hardcoded strings.
- **No new Flyway migration is needed** — `payment.stripe_customers.stripe_payment_method_id` already exists (V62), and this story adds no columns.

### Testing standards

- ITs: `@SpringBootTest` + `@Testcontainers`, real Postgres, **never** mock the database. Mock only `StripeClient` (that is exactly what it exists for — see its class Javadoc: *"Thin wrapper around the Stripe SDK static calls so they can be mocked in unit tests"*).
- Assertions: AssertJ `assertThat`. Test data: Instancio for DTOs/entities.
- Existing payment ITs to model on: `SessionPackPurchaseIT.java`, `PlayerSubscriptionOwnershipIT.java` (for the 403 role test), `StripeOnboardingResourceIT.java` (for a Stripe-mocking resource IT).
- Frontend: there is **no** working Vue test runner in this repo (vitest/`@vue/test-utils` are not installed — see 5.4 W9 in `deferred-work.md`). Do **not** add frontend unit tests or a test runner in this story. Verify the frontend with `npx eslint .` + `quasar build` and manual reasoning.

### Stripe library facts (verified in-repo, 2026-08-04)

- Frontend: `@stripe/stripe-js` `^9.8.0` is already a dependency in `src/frontend/package.json`. **Do not add `@stripe/react-stripe-js` or any Vue Stripe wrapper** — mount Elements directly via the `loadStripe` → `stripe.elements()` → `elements.create('card')` → `element.mount(el)` sequence. Adding a wrapper library is the "wrong library" failure mode this story must avoid.
- Backend: the Stripe Java SDK is already on the classpath (`com.stripe.model.PaymentMethod`, `SetupIntent`, etc. are imported in `StripeClient.java`). Add no new dependency.
- `stripe.confirmCardSetup(clientSecret, { payment_method: { card: cardElement } })` resolves to `{ setupIntent, error }`. The existing `confirmCardSetup` helper in `payment.api.js` currently calls `stripe.confirmCardSetup(clientSecret)` with **no** payment-method argument — you will need to pass the mounted card element through. Extend that helper's signature rather than calling `loadStripe` from a component.
- The saved id you send to `POST /api/payment/save-payment-method` is `setupIntent.payment_method` (a `pm_*` string).

### Previous story intelligence (Epic 11: 11.1 → 11.2 → 11.3)

- **11.3 (most recent, 98ebdc0)** deleted the legacy system entirely: `booking.session_packs_purchased`, `SessionPackService`, `SessionPackResource`, `SessionPackExpiryScheduler` are **gone**, and `V89__drop_legacy_session_packs.sql` dropped the table. If you find a reference to any of those names in docs or comments, it is stale — do not resurrect them, and do not "restore" the legacy purchase path as a workaround for the missing card UI.
- **11.3's own verification bar**: `mvn -o verify` (1597 tests: 777 unit + 820 IT) + eslint + `quasar build` all green. Meet the same bar.
- **11.2's most expensive bug** was a missing `coachId` field on the new-path DTO that only surfaced in review. When you add `SavedPaymentMethodResponse`, verify field-by-field that every field the frontend reads is actually populated by the backend.
- **11.2 also broke IT fixtures twice** by mixing legacy and new-path session-pack tables. Any new IT must seed `payment.session_pack_purchases` / `payment.stripe_customers`, never a `booking.*` pack table.
- Recent commits for context: `98ebdc0` (11.3), `ca11cd0` (11.2), `461fbad` (11.1).

### Prior deferred-story conventions (Deferred-1 … Deferred-10, all `done`)

These ten stories set the pattern this one follows: each groups related deferred items, states the closed items explicitly, and each AC names the exact file and line. Notably **Deferred-4** already added ShedLock + the `extendPack` pessimistic lock, and **Deferred-5** added coach-player authorization — so do not re-solve locking or authorization concerns that those stories closed. **Deferred-9** (frontend UX polish) established that user-visible error feedback uses Quasar `$q.notify`; the inline Stripe error in AC 3 should follow the existing in-page banner pattern (`q-banner` with `purchaseError`, as in `SessionPackPurchasePage.vue:43-45`) rather than a toast, because the error is field-adjacent.

### Project Structure Notes

New files:
- `src/main/java/com/softropic/skillars/platform/payment/contract/StripeConfigResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/SavedPaymentMethodResponse.java`
- `src/frontend/src/components/payment/PaymentMethodCard.vue` *(new `components/payment/` directory — matches the existing `components/booking/` convention, e.g. `SessionPackTracker.vue`)*
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`

Modified files:
- `platform/payment/config/PaymentProperties.java`, `platform/payment/api/SessionPackPaymentResource.java`, `platform/payment/service/SessionPackPaymentService.java`, `platform/payment/service/StripeClient.java`, `platform/payment/service/PackSessionService.java`, `platform/booking/service/BookingDuplicationService.java`
- `platform/payment/api/SubscriptionResource.java`, `platform/payment/service/SubscriptionService.java`, `platform/payment/contract/PlayerSubscribeRequest.java` — required by AC 5's fix to the player-subscribe payment-method flow (see Dev Notes)
- `src/main/resources/application.yaml`
- `src/frontend/src/api/payment.api.js`, `stores/payment.store.js`, `stores/booking.store.js`
- `src/frontend/src/pages/parent/ParentCreditWalletPage.vue`, `SessionPackPurchasePage.vue`, `PlayerSubscriptionPage.vue`, `SessionPackDashboardPage.vue`
- `src/frontend/src/pages/coach/RevenueDashboardPage.vue`, `src/frontend/src/pages/parent/CreditStatementPage.vue`, `src/frontend/src/pages/coach/CoachPaymentSettingsPage.vue` — AC 6 keyed loading/error consumers
- `src/frontend/src/i18n/{en-US,en,de,fr-FR}/index.js`
- `docs/deployment/*` (the file documenting `APP_PAYMENT_STRIPE_*` env vars)
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java`

No variances from the standard structure. Package layout follows `platform.payment.{api,service,repo,contract,config}` as required.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md#Deferred from: code review of skillars-11-2-cutover-booking-and-frontend (2026-08-03)` — D1, D3, D4, D5]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md#Group 5 adversarial deferred (Frontend) — 2026-06-24` — D17, D18]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md#Group 3 adversarial deferred (API + Contracts) — 2026-06-24` — D11, the 204-on-GET precedent AC 4 avoids]
- [Source: `_bmad-output/project-context.md#Critical Implementation Rules`]
- [Source: `_bmad-output/project-context.md#Architecture & Module Design (DDD)`]
- [Source: `_bmad-output/planning-artifacts/skillars-epics.md#Epic 7: Payments & Subscriptions`]
- [Source: `_bmad-output/planning-artifacts/skillars-epics.md#Epic 11: Session Pack Payment Consolidation`]
- [Source: `_bmad-output/implementation-artifacts/skillars-11-2-cutover-booking-and-frontend.md`]
- [Source: `_bmad-output/implementation-artifacts/skillars-11-3-remove-legacy-session-pack-system.md`]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `mvn -o compile` / `mvn -o test-compile` run after each backend edit to catch signature-change fallout early (e.g. `SubscriptionService.subscribePlayer()` and `PackSessionService.findActivePackId()` losing/renaming parameters broke two existing test call sites — both fixed inline).
- `mvn -o test -Dtest=SessionPackPaymentServiceTest,PackSessionServiceParityTest,BookingDuplicationServiceTest,SessionPackPaymentResourceIT` — 25/25 green.
- `mvn -o test -Dtest=SubscriptionLifecycleIT` — 13/13 green (10 pre-existing coach-subscription tests + 3 new player-subscribe tests).
- `mvn -o verify` (full suite, unit + IT) — green, see Completion Notes for count.
- `npx eslint .` (src/frontend) — 0 errors.
- `npx quasar build` (src/frontend) — build succeeded.

### Completion Notes List

- **AC 1/2 (Stripe config endpoint):** `GET /api/payment/stripe/config` added to `SessionPackPaymentResource`, backed by a new `publishableKey` field on `PaymentProperties` wired from `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY`. `PaymentMethodCard.vue` never calls `loadStripe()` with a falsy key — it flips to an "unavailable" state and disables submit instead.
- **AC 3/4 (card collection + read endpoint):** New `PaymentMethodCard.vue` hosts the Stripe Elements card field via `createSetupIntent → confirmCardSetup(key, clientSecret, cardElement) → savePaymentMethod(pm) → refetch GET /payment-method` and emits `saved`. New `GET /api/payment/payment-method` (via `SessionPackPaymentService.getSavedPaymentMethod`) never creates a customer row on read, and returns `hasCard: true` with null detail fields (never `false`) when Stripe itself errors on retrieve.
- **AC 5 (purchase/subscribe gating):** `SessionPackPurchasePage.vue` and `PlayerSubscriptionPage.vue` both gate their action on `savedPaymentMethod.hasCard`, with an "Add card" dialog hosting `PaymentMethodCard.vue`. `PlayerSubscribeRequest.paymentMethodId` removed; `SubscriptionService.subscribePlayer()` now resolves the payment method from the parent's `StripeCustomer` row server-side and no longer calls `attachPaymentMethod` (verified via `verify(..., never())` in `SubscriptionLifecycleIT`). `CoachSubscriptionPage.vue` / `subscribeCoach()` left untouched.
- **AC 6 (keyed loading/error):** `payment.store.js`'s single `loading`/`error` pair replaced with per-action keyed maps; all four actual consumers (`ParentCreditWalletPage`, `RevenueDashboardPage` — OR'd across `revenueSummary`/`transactions`, `CreditStatementPage`, `CoachPaymentSettingsPage`) updated. Confirmed via `usePaymentStore` importer search that the other four importers (`ParentDashboardPlaceholderPage`, `CoachSubscriptionPage`, `PlayerSubscriptionPage`, `CoachReliabilityPage`) never read `.loading`/`.error` and needed no change.
- **AC 7 (TOCTOU fix):** New `PackSessionService.findActivePackId()` replaces the `hasActivePack()` + `getActivePackId()` two-call sequence in `BookingDuplicationService` with one query and no unfiltered fallback; unit test asserts `findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc` is never invoked. `hasActivePack()`/`getActivePackId()` and both `HomeworkAssignmentService` call sites left untouched.
- **AC 8/9 (store scoping / negative caching):** `booking.store.js` now tags `sessionPacksPlayerId` alongside `sessionPacks`; `SessionPackDashboardPage.vue`'s coach-name resolver negative-caches failed lookups as `null` and switched its `unresolvedIds` filter from a truthiness check to a key-presence check (`!(id in coachNames.value)`).
- **AC 10 (i18n):** All new strings added under `payment.card.*` in all four locales with real German/French translations; no hardcoded template strings.
- **Deviation (documented, see Task 6 / deferred-work.md D3):** Did not remove `subscription.paymentMethodId`/`paymentMethodHint` as AC 5 literally instructed — verified they're still actively read by the explicitly out-of-scope `CoachSubscriptionPage.vue`; removing them would have broken that page's raw payment-method-id input. Only the confirmed-orphaned `subscription.player.paymentMethodRequired` was removed.
- **Full verification bar met:** `mvn -o verify` — BUILD SUCCESS, 783 unit tests + 829 IT tests, 0 failures/0 errors (4 pre-existing skips), 22:56 min. `npx eslint .` clean. `npx quasar build` succeeded (spa, build succeeded). Matches/exceeds 11.3's own bar (1597 tests then vs. 1612 now, reflecting the tests added by this story).
- Manual browser UAT of the Stripe Elements flow was **not** performed (no environment with real/test Stripe keys available in this session, consistent with 11.2's precedent) — revisit once a UAT environment exists, same as the 11.2 deferral.

### File List

**New files:**
- `src/main/java/com/softropic/skillars/platform/payment/contract/StripeConfigResponse.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/SavedPaymentMethodResponse.java`
- `src/frontend/src/components/payment/PaymentMethodCard.vue`
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/payment/config/PaymentProperties.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerSubscribeRequest.java`
- `src/main/resources/application.yaml`
- `src/frontend/src/api/payment.api.js`
- `src/frontend/src/stores/payment.store.js`
- `src/frontend/src/stores/booking.store.js`
- `src/frontend/src/pages/parent/ParentCreditWalletPage.vue`
- `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`
- `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`
- `src/frontend/src/pages/parent/SessionPackDashboardPage.vue`
- `src/frontend/src/pages/coach/RevenueDashboardPage.vue`
- `src/frontend/src/pages/parent/CreditStatementPage.vue`
- `src/frontend/src/pages/coach/CoachPaymentSettingsPage.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `docs/deployment/uat-deployment.md`
- `.env.uat`
- `docs/dev-docs/payment/index.html`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` (updated to match `PackSessionService.findActivePackId()`)
- `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java` (added `findActivePackId` tests)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java` (added `getSavedPaymentMethod` tests)

## Change Log

- 2026-08-04: Implemented Stripe card collection (setup-intent → confirmCardSetup → save-payment-method flow via a new `PaymentMethodCard.vue`), a publishable-key config endpoint, a saved-payment-method read endpoint, and gated pack purchase / player subscription on having a saved card — closing the D5 blocker from 11.2's cutover review (no purchase path could complete without a saved card). Folded in four smaller deferred items from the same files: null-key `loadStripe()` guard (7.2 D17), per-action keyed `loading`/`error` state in `payment.store.js` (7.2 D18), a pack-resolution TOCTOU fix in `BookingDuplicationService` (11.2 D1), and `sessionPacks` player-scope tagging + coach-name negative caching (11.2 D3/D4). `mvn -o verify` green (783 unit + 829 IT), `eslint`/`quasar build` clean.
