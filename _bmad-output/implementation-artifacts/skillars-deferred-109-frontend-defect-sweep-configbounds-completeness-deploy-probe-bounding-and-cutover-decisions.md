# skillars-deferred-109: Frontend Defect Sweep + ConfigBounds Completeness + Deploy-Probe Bounding + Cutover Decisions

**Status:** ready-for-dev | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-109
**Branch:** `story/deferred-109-cross-module-cleanup`
**Created:** 2026-09-10
**Base:** master @ `bbad7938` (immediately after `skillars-deferred-108` (#174) and the
`deferred-108`-followup ledger prune (#175) — both merged and master-CI verified this same session)

---

## Story Overview

As the Skillars platform team,
I want the ~16 pre-existing frontend production defects that `skillars-deferred-108`'s code review
surfaced (all in code the new Vitest specs now touch), plus four small cross-module residuals — the
`ConfigBounds` incomplete hand-list, the deploy-probe bounding gap `deferred-108` AC8 left, the
`VideoModerationEmailListener` test gap, and the `frontend-unit-tests.yml` trigger ergonomics — closed
in one pass, and the two owner-decision items (the legacy `PROCESSING→READY` webhook cutover risk, the
`VideoModerationEmailListener` blank-recipient behaviour) recorded as deliberate decisions rather than
open gaps,
so that the frontend surfaces `deferred-108` spec-covered stop drifting between "a spec guards this"
and "this is actually correct", and the ledger's post-`deferred-108` residue is cleared.

A cross-module story drawn from `_bmad-output/implementation-artifacts/deferred-work.md`.

### Where these items come from

`skillars-deferred-108` (2026-09-10) backfilled the six `deferred-104`-unblocked frontend coverage
gaps with 10 mutation-sensitive `__tests__/…Spec.js` files (66 tests). Its `/bmad-code-review`
**Edge Case Hunter** layer, while auditing that backfill, filed ~16 **pre-existing** production
defects into `## Deferred from: code review of skillars-deferred-108 (2026-09-10)`
(`deferred-work.md:1803-1960`) — *"None is caused by that change; all are in code the new specs now
touch, so they are cheap to close next time that surface is opened."* **This story is that next
time.** It also folds in:

- `deferred-work.md:1695` — the `playerStore.js` `fetchSelfPlayerId()` return-value cross-account
  residual `deferred-108` AC4 characterized but deliberately did not fix (test-backfill story).
- `deferred-work.md:1913` / `:1947` — `deferred-108` code-review **decision 2b**: `ConfigBounds`'s
  hand-list is *incomplete* (`video.quota.semiPro.*` / `video.quota.pro.*` seeded live by `V53`,
  never range-checked) and `ConfigBoundsEnumCoverageTest`'s segment derivation would drift on a
  future multi-word constant.
- `deferred-work.md:1954` — the deploy image-probe calls `deferred-108` AC8 left unbounded
  (`docker image inspect` ×2, `aws s3 cp`, the two `${DC} up -d` calls).
- `deferred-work.md:1238-1239` — the `deferred-94` AC15/AC16 `VideoModerationEmailListener`
  residual. **Re-verified stale at HEAD** — see the closed/not-in-scope table below.
- `deferred-work.md:1904` — the `frontend-unit-tests.yml` `opened`-trigger ergonomics fix
  (`deferred-108` AC7 could not touch workflows).
- `deferred-work.md:1311` — the legacy `PROCESSING→READY` webhook cutover risk (`deferred-100` AC5).
  Owner decision: keep as accepted risk; record it explicitly + one runbook line.

### The "genuine one-off bugs & gaps" class is still exhausted

`deferred-100`/`-101` declared it; every story since (`-102`/`-103`/`-107`/`-108`) re-confirmed it.
This story's own re-mine against HEAD (`bbad7938`) confirms it again: what remains in
`deferred-work.md` after this story is `[DECIDED]` / `[DISMISSED]` (do not re-litigate),
"no dev agent can close this" (native de-DE / fr-FR register review, parent legal copy), carved-out
future stories (pre-production migration rebaseline; `main.pending_blob_deletions` drop; **`skillars-7-1`
D4** — `acceptBooking` fires `PAYMENT_CAPTURED` with no real capture, owner-decided its own
payment-architecture story + design session), and speculative/load-dependent notes
(`PessimisticLockRetryer` supplier contract, `@IanaTimezone` `Etc/GMT±N` / tzdata stability, the
measured-and-left N+1s).

## Items that looked open but are already closed / stale / not in scope (confirmed during story creation, HEAD `bbad7938`)

| Ledger item | HEAD reality |
|---|---|
| `deferred-94` AC15/AC16 — `VideoModerationEmailListener` fail-open (`deferred-work.md:1238-1239`, filed by `deferred-93` CR 2026-09-05) | **Materially stale — re-scoped to test-only (AC12).** The bullet describes a silent drop with "no ERROR". At HEAD `VideoModerationEmailListener` has been rewritten: (1) `@PostConstruct checkAdminAlertConfig()` (`:46-57`) logs `log.error("STARTUP: platform.admin_alert_email config is blank …")` and throws `IllegalStateException` ("STARTUP ABORTED") if `ARACHNID_ENABLED` is on; (2) `adminAlertEnvelope()` (`:112-117`) logs `log.error("… admin alert NOT sent")` on the blank path; (3) `sendAdminAlertSync` (`:83-89`) has a **deliberate documented decision** (`:86-88`) *not* to retain the outbox row for an unset config key ("no number of re-drives fixes an unset config key, and a retained row would occupy a claim slot until a human noticed"); (4) the retryable/permanent split is **coded** (`:95-106` — `persisted.isRetry()` → `throw` (re-drive), else `log.error("[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]")` + release). The blank-recipient behaviour is now an **owner decision, not a gap** — retag both bullets `[DECIDED]`. What genuinely remains: **no IT exercises the real `Exception → EnvelopeEntity(FAILED, isRetry)` mapping** in this flow (AC6's stated infra premise was wrong — `NotificationEmailOutboxAtomicityIT` uses `TestMailManager`), and the `persisted == null` case logs `INFO "[…] delivered"` (`:107-108`) when it could be a persistence lag. → **AC12** (test + WARN-tighten only). |
| `deferred-94` AC2 — hardcoded container UIDs (mentioned in the Step-5 request) | **Not an open item.** CLOSED by `deferred-107` AC8 (`image_runtime_uid` probe); bullet deleted 2026-09-10 (`deferred-work.md:1658-1686` audit block). |
| `skillars-deferred-94` status (Step-5 request said "cancelled") | **`done`** (`sprint-status.yaml:259`). Its AC15/AC16 residuals are the only genuine picked-up-but-unresolved items left; every other `deferred-95…-107` pickup shipped and was pruned. |
| `skillars-7-1` D4 — `acceptBooking` → `PAYMENT_CAPTURED` with no real capture (`deferred-work.md:671`, restated `:1424`) | **Owner-decided out of scope (D8).** Its own payment-architecture story + design session (same disposition as `deferred-108` D4). Stays on the ledger untouched. |
| B2 — promoting `frontend-unit-tests.yml` to a required check (`deferred-work.md:1891`) | **Owner decision D2 stands (D6 below): keep it opt-in.** AC13 fixes the trigger ergonomics only. The `:1891` "specs are not merge-gating" coupling note is left as-is. |
| Native de-DE / fr-FR register review, parent legal copy (`deferred-work.md:1152-1157`, `:1233`) | **No dev agent can close these** — untouched. |
| Pre-production migration rebaseline (`deferred-work.md:1315`); drop `main.pending_blob_deletions` (`:1293`) | **Carved-out future tasks, pre-data only** — untouched. |

## Project-owner decisions (captured 2026-09-10 during story creation)

| # | Question | Decision |
|---|---|---|
| **D1** | The ~16 frontend prod defects from the `deferred-108` review — how many into this story? | **All 16, plus the `playerStore.js` return-value cross-account leak (`:1695`).** → **AC1–AC9** |
| **D2** | `ConfigBounds` `video.quota.semiPro.*` / `.pro.*` unbounded — fix approach, given AC9 (`deferred-108`) decided "keep hand-listing, do not couple `config` → business modules"? | **Complete the hand-list + fix the test.** Add `"semiPro"`, `"pro"` to `VIDEO_QUOTA_TIER_SEGMENTS` (the existing loop in `ConfigBounds.ALL` auto-generates the 4 `BoundedKey`s at the same `0..Long.MAX_VALUE` bound as the other tiers — one-line list edit, **not** an enum iteration, so AC9's decoupling decision holds). Fix `ConfigBoundsEnumCoverageTest.java:32`'s `toLowerCase` derivation to camelCase, and add explicit `semiPro`/`pro` coverage assertions next to the existing `athlete` block. → **AC10** |
| **D3** | `frontend-unit-tests.yml` job — scope this story? | **Ergonomics only.** Add `opened` to `pull_request: types:` so `gh pr create --label frontend-tests` fires the job in one step. Job-level `if` still gates on the label — stays opt-in and **non-gating**. Owner decision D2 (out of the required set / `mvn verify` / `ci.yml` / `pr-build.yml`) is **not** revisited. → **AC13** |
| **D4** | Legacy `PROCESSING→READY` webhook grace path (`VideoLifecycleService.java`)? | **Keep as accepted-risk. Do NOT build a grace path or a config-gated allowance.** Record the acceptance explicitly in the ledger (owner decision, this story), and add one line to the pre-first-production-deploy release gate in `docs/deployment/runbook.md`: drain or discard queued/replayed pre-deploy `encoding.success` events before cutover. No production code change. → **AC14** |
| **D5** | `VideoModerationEmailListener` — the bullet is largely stale (blank-recipient path already has startup ERROR + boot-abort + per-send ERROR + a documented no-retain decision + the retryable/permanent split). | **Test-only + retag.** Add the missing IT (retryable send failure retains the outbox row, permanent deletes it — real `MailManager` + mock `JavaMailSender`); tighten the `persisted == null` case from `INFO "delivered"` to `WARN "not yet visible"`. Retag both ledger bullets `[DECIDED]` pointing at the current hardened code. No behaviour change to the blank-recipient path (that decision stands). → **AC12** |
| **D6** | Making the Vitest job a required check (B2). | **No.** Owner decision D2 stands. The coupling note (`deferred-work.md:1891`) stays as filed. |
| **D7** | Story size. | Frontend defect sweep (**AC1–AC9**) + `ConfigBounds` completeness (**AC10**) + deploy-probe bounding (**AC11**) + `VideoModerationEmailListener` test + retag (**AC12**) + `frontend-unit-tests.yml` `opened` trigger (**AC13**) + legacy-webhook cutover decision + runbook line (**AC14**) + ledger hygiene with reconstruction check (**AC15**). |
| **D8** | `skillars-7-1` D4 — scope in? | **No.** Its own story + design session (owner, restated from `deferred-108` D4). Left on the ledger untouched. |

---

## Global conventions for AC1–AC9 (the frontend defect sweep)

**These reuse `skillars-deferred-108`'s "Global conventions for AC1–AC6" verbatim** — read that
section of
`_bmad-output/implementation-artifacts/skillars-deferred-108-frontend-unit-spec-backfill-restore-pull-timeout-and-configbounds-templated-key-decision.md`
once. In particular:

- **Runner / layout.** Vitest, `happy-dom`, `globals: false` — import `describe` / `it` / `expect` /
  `vi` explicitly. New specs live in `__tests__/` beside the code, named `<Unit>Spec.js`.
  `test/vitest/setup-file.js` auto-loads Quasar + a minimal `vue-i18n` + `$t`/`$tc` stubs;
  **Pinia is NOT installed globally** — opt in with `createTestingPinia()` / `createPinia()` +
  `setActivePinia()`.
- **EXTEND, do not rewrite.** Every surface below already has a `deferred-108` spec file
  (`stores/__tests__/paymentStoreSpec.js`, `components/payment/__tests__/PaymentMethodCardSpec.js`,
  `plugins/__tests__/sessionManagerCoverageSpec.js`, `layouts/__tests__/MainLayoutSpec.js`,
  `stores/__tests__/playerStoreSpec.js`, `stores/__tests__/bookingStoreSpec.js`,
  `pages/parent/__tests__/BookingRequestPageSpec.js`,
  `pages/parent/__tests__/ParentBookingsPageSpec.js`,
  `components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js`,
  `composables/__tests__/useSessionSpec.js`). Add the regression block for each fix **into the
  existing file**, following its established style. Do not restyle the `deferred-108` reference
  specs (`SkillsRadarChartSpec.js`, `sessionManagerSpec.js`) or the `deferred-108` specs themselves.
- **Mutation-sensitivity is the acceptance bar.** For each fix, the Dev Agent Record names the
  one-line revert (delete the new guard / restore the old prop type / drop the `Number.isNaN`
  filter) that turns the new assertion red, and confirms it was run in both directions
  (fails reverted, passes restored).
- **Quasar overlays teleport out of the wrapper** (`q-dialog` / `q-menu` / `q-select` popups render
  under `document.body`). Assert via `wrapper.vm` / the backing `computed`, or query `document.body`
  after opening the overlay.
- **`vi.mock('<path>', { spy: true })`** for spying statically-imported ESM (module namespace objects
  are non-configurable — a bare `vi.spyOn` on a named import is unreliable).
- **Green gate.** `cd src/frontend && npm run test:unit` **and** `npm run test:unit:ci`
  (`vitest run --coverage`) both green — all `deferred-108` specs + these new assertions.
  `npx eslint 'src/**/__tests__/**/*.js'` exit 0; `npx prettier --check` clean on every touched
  spec.
- **Production changes ARE in scope here (unlike `deferred-108`).** These ACs fix real defects — a
  guard, a prop widening, a `Promise.race` bound, an `if` branch. Keep each fix minimal and local;
  do not refactor beyond the defect. No new abstractions.
- **`frontend-unit-tests.yml` stays opt-in.** The PR carries the `frontend-tests` label, added
  **after** `gh pr create` (the workflow's `types:` list — until AC13 lands in this same PR — has no
  `opened`; add the label via `gh pr edit <n> --add-label frontend-tests` to fire `labeled`, or rely
  on AC13's own `synchronize`). Confirm the `frontend-unit` check actually **ran** — a
  green-because-skipped result does not count.

---

## Acceptance Criteria

> **Every line/function cite below was re-verified against HEAD `bbad7938` during story creation.**
> Several drifted from the `deferred-work.md` values (noted inline). **Pre-implementation: `git diff`
> the cited lines again** — the file's own convention (`deferred-work.md:18`: "File paths and line
> numbers age fast").

### AC1 — `PaymentMethodCard.vue` card-collection lifecycle fixes (closes 4 `deferred-108` CR bullets)

**File:** `src/frontend/src/components/payment/PaymentMethodCard.vue` (script `<script setup>` at
`:75-223`). **Spec:** extend `components/payment/__tests__/PaymentMethodCardSpec.js` (+ its store
half in `stores/__tests__/paymentStoreSpec.js`).

- **AC1.1 — retry no longer no-ops on the first click** (`deferred-work.md:1811-1817`;
  `PaymentMethodCard.vue:174-187`). `loadStripeConfig({ isRetry: true })` sets
  `stripeUnavailable.value = false` at `:176` **before** `await Promise.all([…])` at `:178`. That
  flips `showForm` (`:101-103`) `false`→`true`, which queues the `watch(showForm, …)` job (`:155-158`)
  → `mountCardElement()` → `ensureStripeReady()` runs during the `await` and reads the still-null
  `paymentStore.stripeConfig?.publishableKey` (`:109`), re-setting `stripeUnavailable = true` (`:111`).
  When the successful refetch resolves, `if (showForm.value)` at `:186` is now `false` and Elements
  never mounts — the user must click **Try again** twice.
  **Fix:** do not clear `stripeUnavailable` until the refetch has actually populated `stripeConfig`
  (move the `stripeUnavailable.value = false` to after the `await Promise.all`, or gate the
  `watch`-driven `mountCardElement` so it does not run against a null key mid-refetch). Keep the
  `deferred-103` AC9 affordance semantics (a failed retry re-raises `stripeUnavailable` so the button
  stays put — `:179-181`).
  **Mutation check:** re-introduce the early `stripeUnavailable.value = false` → the new
  "retry mounts Elements on the first click" assertion fails (Elements never mounts / `loadStripe`
  spy called with the eventual key only after a second retry).

- **AC1.2 — a failed config refetch must not leave a stale publishable key in use**
  (`deferred-work.md:1818-1822`; `PaymentMethodCard.vue:178-181`, `payment.store.js:238-259`).
  `payment.store.js`'s `fetchStripeConfig` (`:238-248`) / `fetchSavedPaymentMethod` (`:249-259`)
  `catch (err) { this.error.X = err }` and **resolve** — so the `Promise.all` at `:178` never
  rejects and the `catch` at `:179-181` is unreachable dead code. Worse: `fetchStripeConfig` never
  nulls `this.stripeConfig` on error (`:242-244`), so a stale-but-non-null `publishableKey` from an
  earlier success survives a later failure and `ensureStripeReady()` proceeds with it.
  **Fix (choose the minimal correct shape, record which in the Dev Agent Record):** either (a) have
  `loadStripeConfig` detect `paymentStore.error.stripeConfig` after the `await` and raise
  `stripeUnavailable` (making the dead `catch` a real branch), **or** (b) null `this.stripeConfig`
  in the store's `catch` so a failed refetch cannot leave a usable key. Do **not** make the store
  actions rethrow — other call sites (`deferred-108` AC1 spec, `SessionPackPurchase` flows) rely on
  the swallow-and-resolve contract; verify with a grep before touching the store.
  **Mutation check:** name the revert per the chosen shape (restore the swallowed error / restore
  the retained `stripeConfig`) → the new "failed refetch → `stripeUnavailable`, `loadStripe` not
  called with the stale key" assertion fails.

- **AC1.3 — `hasCard: true` with `brand: null` renders a real label**
  (`deferred-work.md:1823-1827`; `PaymentMethodCard.vue` template `:20-32`).
  `SessionPackPaymentService.java` returns `new SavedPaymentMethodResponse(true, null, null, null, null)`
  for a card whose brand/last4 could not be resolved. The template's `savedCard.brand ? t('payment.card.savedLabel', {…}) : …`
  ternary (`:24-30`) has a false arm (`payment.card.detailsUnavailable`), but it and the adjacent
  "Replace card" button (`:62`) have zero coverage; a regression that drops the ternary would render
  `savedLabel` with `undefined undefined` and ship green.
  **Fix:** pin the current behaviour with a spec assertion (the false arm renders
  `payment.card.detailsUnavailable`, the row still shows the "Replace card" affordance). If the false
  arm's i18n key does not exist, add it to all three bundles (`en-US` / `fr-FR` / `de-DE`) — check
  first. **No new production logic** unless the key is missing.
  **Mutation check:** drop the `savedCard.brand ?` ternary → the new assertion sees
  `undefined undefined` and fails.

- **AC1.4 — the card-save path is exercised** (`deferred-work.md:1828-1832`;
  `PaymentMethodCard.vue:189-214`). `submit()` calls `createSetupIntent` (`:194`) →
  `confirmCardSetup` (`:196`) → `savePaymentMethod` (`:201`); all three are `vi.mock`ed in the
  `deferred-108` spec with **no assertions**. Three reachable failure sub-cases are unpinned,
  notably a 3DS `requires_action` intent (`error` absent, `setupIntent.status !== 'succeeded'` at
  `:197`) which surfaces the generic `payment.card.saveError` (`:198`) — indistinguishable from a
  hard decline.
  **Fix:** add spec coverage for the three branches of `submit()` (`error` set → message;
  `status !== 'succeeded'` → message; success → `savePaymentMethod` called + `emit('saved')`). This
  is **spec-only** unless the review agrees the `requires_action` case warrants a distinct message
  (a genuine product call — record it as an open question, do **not** add a new message key
  unilaterally).
  **Mutation check:** delete the `|| setupIntent?.status !== 'succeeded'` disjunct at `:197` → the
  new `requires_action` assertion fails (no error surfaced on a non-succeeded intent).

### AC2 — `sessionManager.js` skew cross-check + ineffective-refresh (closes 2 `deferred-108` CR bullets)

**File:** `src/frontend/src/plugins/sessionManager.js`. **Spec:** extend
`plugins/__tests__/sessionManagerCoverageSpec.js` (same faked-cookie + faked-clock isolation as
`sessionManagerSpec.js` — `vi.useFakeTimers()`, `afterEach` that restores real timers + clears
cookies/`sessionStorage`).

- **AC2.1 — the clock-skew cross-check has regression protection**
  (`deferred-work.md:1833-1838`; `computeTimeUntilExpiry()` — the guard is
  `if (remaining <= 0 && localEstimate > 0) return localEstimate` at **HEAD `sessionManager.js:141`**,
  ledger says `:140`). This one line is the whole defence between a fast client clock and the
  unrecoverable logout loop the method's own doc-comment (`:100-115` region) describes: a past-due
  `rint` must **not** expire the session while the local `Date.now()`-delta estimate is still
  positive. No fixture in either spec sets `expiresAt` / `rint` **in the past** — every one uses
  `Date.now() + N` — so deleting the line leaves the suite green.
  **Fix (spec-only):** add a case that writes a `rint` a few seconds in the **past**, keeps
  `lastActivityTime` recent (so `localEstimate > 0`), advances the fake clock, and asserts
  `timeUntilExpiry` reflects the **positive local estimate** and no `session:expired` is dispatched.
  Also pin the `remaining === 0` and `timeUntilExpiry === WARNING_THRESHOLD` boundaries the bullet
  names as untested.
  **Mutation check:** delete `if (remaining <= 0 && localEstimate > 0) return localEstimate` → the
  new past-`rint` case sees a non-positive `timeUntilExpiry` / a `session:expired` dispatch and
  fails.

- **AC2.2 — a refresh that returns 200 without advancing `rint` is not treated as success**
  (`deferred-work.md:1839-1843`; `refreshSession()` at **HEAD `sessionManager.js:248-273`**, ledger
  says `:259-264`). After `await sessionApi.refresh()` (`:262`) the code runs `recordActivity()` +
  `refreshExpiryState()` (`:264-265`) with **no branch checking that the expiry actually moved
  forward**; `refreshFailed` is set only in the `catch` (`:271`). A proxy that strips `Set-Cookie`
  on `GET /refresh`, or any 200 that does not re-issue `rint`, re-enables "Continue session", keeps
  the countdown ticking, and logs the user out at `0:00` with no explanation — exactly the UX
  `deferred-90` AC4 was written to eliminate for the thrown-error case.
  **Fix:** after a resolved `sessionApi.refresh()`, read the expiry back
  (`readSessionExpiryFromCookie()` / the same path `computeTimeUntilExpiry` uses) and, if it did
  **not** advance beyond where it was before the call (or is still absent), set
  `refreshFailed.value = true` and `console.error` — treat an ineffective refresh as a failed one.
  Keep the existing dynamic-import + `recordActivity()`-before-call structure.
  **Mutation check:** remove the new post-refresh expiry-advanced check → the new "200 without a
  fresh `rint` → `refreshFailed = true`" assertion fails.

### AC3 — `MainLayout.vue` logout parity + storage guards + silent-404 (closes 3 `deferred-108` CR bullets)

**File:** `src/frontend/src/layouts/MainLayout.vue`. **Spec:** extend
`layouts/__tests__/MainLayoutSpec.js`.

- **AC3.1 — `handleLogout` carries `deferred-91` AC14's guarantees** (`deferred-work.md:1844-1850`;
  `MainLayout.vue:335-341`). `async function handleLogout()` does a bare `await authStore.logout()`
  (`:336`) with **no `Promise.race` timeout bound** and **no `rint` cookie clear** — contrast
  `useSession.js` (`handleLogout` there wraps the backend call in
  `Promise.race([authStore.logout(), new Promise(r => setTimeout(r, LOGOUT_BACKEND_WAIT_MS))])` and
  clears `rint` pre- and post-race). On a stalled `POST /logout` the user is stranded on the
  authenticated page; sibling tabs keep rendering an authenticated UI until the stale absolute
  deadline. The app now ships **two divergent logout sequences**, both pinned "correct" by
  `deferred-108` specs (`MainLayoutSpec.js` AC4 block + `useSessionSpec.js` AC6 block).
  **Fix:** bring `MainLayout.handleLogout` to parity with `useSession.handleLogout` — bounded
  `Promise.race` around `authStore.logout()` using the same constant, and an `rint` cookie expire
  (`document.cookie = 'rint=; Max-Age=0; path=/'`) alongside the existing `deleteUserCookie()`
  (`:331-333`, which only clears `user`). Preserve the ordering
  `logout → resetSelfPlayerId → destroySession → deleteUserCookie → router.push('/login')`.
  Consider extracting the shared sequence if it is a clean lift — but **only** if it does not grow
  the diff or couple the two call sites awkwardly; a duplicated bounded-race is acceptable
  (`deferred-108` AC4's overlap note explicitly kept them separate).
  **Mutation check:** revert the `Promise.race` bound → the new "stalled `logout` still resolves
  `handleLogout` within `LOGOUT_BACKEND_WAIT_MS`" assertion hangs / fails; revert the `rint` clear
  → the new "`rint` cookie is expired after logout" assertion fails.

- **AC3.2 — `localStorage` access is guarded** (`deferred-work.md:1851-1855`;
  `MainLayout.vue:305` in `changeLanguage`, `:314` in `loadLanguagePreference`).
  `sessionManager.js` wraps its equivalent `sessionStorage` calls in `try/catch`; `MainLayout` does
  not. In Safari private mode `localStorage.setItem` (`:305`) throws **before** the `lang` cookie
  clear at `:310` (the line that exists to unstick a stuck backend `lang` cookie) runs, and
  `localStorage.getItem` (`:314`) throws inside `onMounted` (`:348`), aborting before the `storage`
  listener registers (`:351`) and before the player self-id fetch (`:353-363`).
  **Fix:** wrap both `localStorage` accesses in `try/catch` (match `sessionManager.js`'s idiom — a
  small `safeLocalStorageGet` / `safeLocalStorageSet` helper, or inline `try/catch`). In
  `changeLanguage`, the `locale.value = lang` assignment and the `lang`-cookie clear must still run
  even if the `setItem` throws.
  **Mutation check:** remove the `try/catch` around `changeLanguage`'s `setItem` → the new
  "`setItem` throwing does not prevent `locale.value` update or the `lang` cookie clear" assertion
  fails.

- **AC3.3 — the silent-404 contract is pinned** (`deferred-work.md:1856-1858`;
  `MainLayout.vue:353-363`, `if (err.response?.status !== 404)` at `:359`).
  `MainLayoutSpec.js` mounts as `PARENT` for every test, so `authStore.isPlayer` is false and the
  `fetchSelfPlayerId` block never runs — neither arm of the 404 discriminator is exercised. A
  regression that logs every 404, or surfaces one as a user-visible error, ships green.
  **Fix (spec-only):** add a `MainLayoutSpec.js` case that mounts as `PLAYER` with
  `playerStore.fetchSelfPlayerId` rejecting with `{ response: { status: 404 } }` → **no**
  `console.error`; and a second case rejecting with a 500 → `console.error` **is** called.
  **Mutation check:** change `!== 404` to a bare `if (err)` → the 404 case now logs and the new
  "404 is silent" assertion fails.

### AC4 — `playerStore.js` reject-path coverage + return-value cross-account fix (closes `deferred-work.md:1859-1863` + `:1695`)

**File:** `src/frontend/src/stores/playerStore.js` (`defineStore('player', setup-style)`).
**Spec:** extend `stores/__tests__/playerStoreSpec.js`.

- **AC4.1 — `fetchSelfPlayerId()` must not resolve with a superseded account's id** (the `:1695`
  residual; `playerStore.js:28-58`, `return profile.id` **unconditional** at `:45`).
  The `deferred-43` generation guard at `:39` (`requestGeneration === selfPlayerIdGeneration`) gates
  only the **cached-ref write** (`selfPlayerId.value = profile.id`, `:40`). The `.then` still
  `return profile.id` unconditionally at `:45`, so a `fetchSelfPlayerId()` call that
  `resetSelfPlayerId()` superseded mid-flight still **resolves** with the prior account's id.
  `BookingRequestPage.vue` does `selfPlayerId.value = await playerStore.fetchSelfPlayerId()` and
  feeds that into the booking submit payload — the exact cross-account booking-misattribution risk
  the `deferred-43` ledger bullet named, only half-closed by the guard.
  **Fix (one line):** gate the return on the generation too —
  `return requestGeneration === selfPlayerIdGeneration ? profile.id : selfPlayerId.value`
  (resolving with the current cached value, which a concurrent `resetSelfPlayerId` set to `null`, is
  correct: a superseded call must not hand its caller a stale id). Confirm the `profile?.id == null`
  throw at `:42-44` still fires for a genuinely id-less response regardless of generation.
  **Spec:** `deferred-108` AC4's `playerStoreSpec.js` has a **characterization test** for the
  current leaky behaviour ("cross-account guard — returned promise (records the residual)") —
  **flip it** to assert the fixed behaviour: the superseded call resolves with `null` (or the
  post-reset cached value), **not** the other account's id.
  **Mutation check:** restore the unconditional `return profile.id` → the flipped assertion fails
  (the superseded promise resolves with the wrong id).

- **AC4.2 — the reject path (the documented 404) is covered** (`deferred-work.md:1859-1863`;
  `playerStore.js:28-58`). Every existing spec case drives failure through `{ id: null }`, which
  takes the `.then`-throws path (`:42-44`). A **real** `getMyProfile()` rejection skips `.then`
  entirely; only `.finally` (`:47-54`) runs, and its `selfPlayerIdRequest === request` reset is the
  sole thing preventing a permanently poisoned in-flight-request cache.
  **Fix (spec-only):** add a case where `playerRegistrationApi.getMyProfile` **rejects** (network
  error / 404) → `fetchSelfPlayerId()` rejects, `selfPlayerId.value` stays `null`, and a subsequent
  `fetchSelfPlayerId()` issues a **fresh** `getMyProfile` call (proving `.finally` cleared the
  reference).
  **Mutation check:** delete the `if (selfPlayerIdRequest === request) selfPlayerIdRequest = null`
  line at `:53` → the "subsequent call retries" assertion fails (the rejected promise stays pinned).

### AC5 — `booking.store.js` accept-all rethrow + null-response guard (closes `deferred-work.md:1864-1873`)

**File:** `src/frontend/src/stores/booking.store.js`. **Spec:** extend
`stores/__tests__/bookingStoreSpec.js`.

- **AC5.1 — `handleAcceptAllBatch` must not leak a permanent `null` LRU entry on failure**
  (`deferred-work.md:1864-1869`; `booking.store.js:605-627`). `setBatchAcceptResult(batchId, null)`
  runs at `:608` **before** the `try` (`:609`). On rejection the `catch` sets
  `batchAcceptError.value = e` and `throw e` (`:625-626`), so `loadCoachBookingRequests()` (`:622`)
  — the **only** pruner of `batchAcceptResultsByBatch` — is never reached. Every failed accept-all
  leaves a permanent `batchId → null` entry until the 200-cap (`MAX_BATCH_ACCEPT_RESULTS`, `:593`)
  evicts it, and callers must `.catch()` or eat an unhandled rejection. This is the one rethrowing
  action in a file whose CONTRACT block (`:358-368`) states its loaders never rethrow.
  **Fix (choose one, record which):** (a) in the `catch`, delete the `batchId` entry
  (`setBatchAcceptResult` with a delete, or a targeted `delete next[batchId]`) before `throw e`;
  **or** (b) move the `setBatchAcceptResult(batchId, null)` seed to **after** a successful
  `acceptAllBatch` resolves (it exists to reserve the slot in insertion order — verify nothing reads
  the `null` placeholder between `:608` and `:616`). Keep the rethrow — callers depend on it
  (`grep` the return-value consumers first).
  **Mutation check:** revert the fix → the new "a rejected `handleAcceptAllBatch` leaves no
  `batchId` key in `batchAcceptResultsByBatch`" assertion fails.

- **AC5.2 — `loadCoachBookingRequests` guards a null response** (`deferred-work.md:1870-1873`;
  `booking.store.js:388-429`, `const res = await getCoachBookingRequests()` at `:393`,
  `res.singleBookings ?? []` at `:395`). The `?? []` guards a **missing property**, not a null
  `res`. A `204`, or an axios interceptor unwrapping an empty body to `undefined`, makes
  `res.singleBookings` throw a `TypeError` inside the `try` — caught at `:416`, and since
  `requestId === coachRequestsSequence` it is filed into `coachRequestsError.value = e` (`:424`) as
  though it were an HTTP failure, and `loadCoachBookingRequests` returns `false`.
  **Fix:** treat a nullish `res` as an empty result — `const res = (await getCoachBookingRequests()) ?? {}`
  (or an explicit `if (!res) { …empty state…; return true }`), so a 204 leaves the store in a clean
  empty state and `coachRequestsError` stays `null`. Mirror the same guard in `loadCoachSchedule`
  only if it has the identical shape (`:431+` — check; do not over-reach).
  **Mutation check:** restore `const res = await getCoachBookingRequests()` (no `?? {}`) → the new
  "a `null`/`undefined` response yields empty lists and no `coachRequestsError`" assertion fails.

### AC6 — `BookingRequestPage.vue` `slotRows` NaN filter (closes `deferred-work.md:1874-1878`)

**File:** `src/frontend/src/pages/parent/BookingRequestPage.vue`. **Spec:** extend
`pages/parent/__tests__/BookingRequestPageSpec.js`.

- `slotRows` (computed at **HEAD `:456-471`**, ledger says `:457-462,:476`): the `available` branch
  (`:458-461`) maps each slot to `{ key: `slot-${slot.startDatetime}`, sortKey: Date.parse(slot.startDatetime), … }`
  with **no `Number.isNaN` filter** — while `ownBlockingBookings` (`:426-444`) explicitly filters
  `Number.isNaN` at `:437` and `:443`. A slot missing `startDatetime` yields `sortKey: NaN`
  (implementation-defined ordering from the `a.sortKey - b.sortKey` comparator at `:471`) and
  `key: "slot-undefined"` for **every** such row → Vue row-reuse artifacts. This is the branch the
  `deferred-17`/`-18` `.startDatetime` rename actually touched.
  **Fix:** filter the `available` branch to slots with a parseable `startDatetime`
  (`.filter(s => !Number.isNaN(Date.parse(s.startDatetime)))`) before the map, matching the
  `ownBlockingBookings` idiom. Keep the interleave-by-`sortKey` sort.
  **Mutation check:** remove the new filter → the new "a slot with a missing/blank `startDatetime`
  is dropped from `slotRows`, not rendered with `key='slot-undefined'`" assertion fails.

### AC7 — `ParentBookingsPage.vue` `rescheduleProposedEnd` DST correctness (closes `deferred-work.md:1879-1883`)

**File:** `src/frontend/src/pages/parent/ParentBookingsPage.vue`. **Spec:** extend
`pages/parent/__tests__/ParentBookingsPageSpec.js`.

- `rescheduleProposedEnd` (computed at **HEAD `:252-256`**): `new Date(rescheduleProposedStart.value)`
  (`:254`) parses the `datetime-local` string as **browser-local**, a **fixed-instant** delta
  `rescheduleDurationMs.value` is added (`:256`), then `toDatetimeLocal` (`:260-264`) reads
  `getHours()/getMinutes()` **back in wall clock**. A 1-hour booking moved to
  `2026-03-08T01:30` in `America/New_York` displays `03:30` (a 2-hour session the coach never
  agreed to) and the submitted payload (`:372-373`, `new Date(rescheduleProposedEnd.value).toISOString()`)
  follows. `2026-11-01T01:30` is ambiguous and resolves silently.
  **Fix:** compute the end as `start-wall-clock + duration-in-wall-clock-minutes`, not
  `start-instant + duration-in-ms` — i.e. derive the proposed end by adding the booking's original
  **minutes** to the proposed start's wall-clock fields (or use the same local-naive arithmetic
  `BookingRequestPage.vue`'s `formatInZone` / naive-UTC helpers already use — check `:405-415`
  there for the established pattern). The displayed end and the submitted end must both reflect
  "start + N minutes of wall clock" across a DST boundary.
  **Mutation check:** revert to the fixed-ms delta → the new "a 60-min booking rescheduled across
  the 2026-03-08 America/New_York spring-forward still shows a 60-min span" assertion fails
  (shows 120). Drive the spec with a fixed `TZ`/`Intl` and fake clock; if `happy-dom` cannot pin
  the zone, assert on the wall-clock-minutes helper directly and record the trimming.

### AC8 — `ProfileBuilderStep3.vue` silent partial-pack discard + duration coercion (closes `deferred-work.md:1884-1890`)

**File:** `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue`. **Spec:** extend
`components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js`.

- **AC8.1 — a partially-filled pack row is not silently dropped** (`submit()` at `:145-155`,
  `.filter((p) => p.sessionCount > 0 && p.totalPrice > 0)` at `:151`). `pack.sessionCount` /
  `pack.totalPrice` bind `v-model.number` (`:43`, `:52`) so a blank field is `null`, and
  `null > 0` is `false` → the row is filtered out with **no user-facing branch**: a coach who types
  `sessionCount: 5` and leaves the price blank sees the pack on screen, completes onboarding, and
  the pack is never created — no message at any point. (An all-`null` row is plausibly intentional
  and stays silent.)
  **Fix:** before the `.filter`, detect a **partially**-filled row (exactly one of
  `sessionCount` / `totalPrice` set) and either (a) block `submit()` with a validation message
  (`q-form` rule / an inline error), or (b) surface a `Notify`/inline warning naming the incomplete
  row. Prefer (a) — a partial pack is a user error, not a silent no-op. All-`null` rows stay
  filtered without complaint.
  **Mutation check:** remove the partial-row check → the new "submit with
  `{ sessionCount: 5, totalPrice: null }` is blocked / warns" assertion fails (submit emits with the
  row silently absent).

- **AC8.2 — `durationOptions` tolerates a string-typed hydrated value** (`:122-135`,
  `DURATION_CHOICES.includes(current)` at `:131`). `includes` is strict-equality with no `Number()`
  coercion, so a string `sessionDurationMinutes` from hydration (`'60'`) fails the membership check,
  appends a **duplicate synthetic option** (`:131-133`), and `emit-value` (`:19`) then submits the
  string.
  **Fix:** coerce `current` with `Number()` before the `includes` check (and/or normalise
  `form.sessionDurationMinutes` to a number on hydration). Keep the `deferred-63` AC7 defensive
  intent (an out-of-`DURATION_CHOICES` numeric value still gets its synthetic option).
  **Mutation check:** revert the `Number()` coercion → the new "a hydrated `'60'` does not append a
  second `60` option and submits the number" assertion fails.

### AC9 — `BookingStateChip.vue` prop type (closes `deferred-work.md:1935-1946`)

**File:** `src/frontend/src/components/booking/BookingStateChip.vue` (`:10-13`). **Spec:** update
`pages/parent/__tests__/ParentBookingsPageSpec.js`.

- `bookingId: { type: String, default: null }` (`:12`) — but every caller passes a **numeric** id
  (`ParentBookingsPage.vue:134` binds `:booking-id="booking.id"` straight from the API payload,
  where `id` is a number). Vue logs `Invalid prop: type check failed for prop "bookingId". Expected
  String … got Number` on **every render**, and `useBookingSse(props.bookingId)` (`:16-18`) receives
  the unconverted value.
  **Fix (one line):** widen to `bookingId: { type: [String, Number], default: null }`.
  `useBookingSse` interpolates the id into a URL, so a number already works — no other change
  needed. Verify no `BookingStateChip` consumer relies on a string-typed `bookingId` (`grep`).
  **Spec:** `deferred-108`'s `ParentBookingsPageSpec.js` deliberately left its fixture numeric to
  reproduce this warning (`deferred-108` CR reclassified-patch note). After the fix, the warning is
  gone — update that spec's NOTE and add an assertion that mounting `ParentBookingsPage` with the
  numeric-id fixture emits **no** `Invalid prop` warning (spy on `console.warn` / Vue's warn
  handler).
  **Mutation check:** revert to `type: String` → the "no prop-type warning on render" assertion
  fails.

### AC10 — `ConfigBounds` completeness: bound `semiPro`/`pro`, fix the drift guard (closes `deferred-108` CR decision 2b — `deferred-work.md:1913-1934` + `:1947-1953`)

- **AC10.1 — bound the four seeded rows.** `ConfigBounds.java` `VIDEO_QUOTA_TIER_SEGMENTS`
  (**HEAD `:204`**) is `List.of("scout", "instructor", "academy", "athlete")`. `V53__video_quota_system.sql`
  seeds **twelve** `video.quota.*` rows including `video.quota.semiPro.storageBytes` (id 121),
  `video.quota.pro.storageBytes` (122), `video.quota.semiPro.bandwidthBytesMonthly` (127),
  `video.quota.pro.bandwidthBytesMonthly` (128) — templated over a **third** enum,
  `PlayerSubscriptionTierBilling {ATHLETE, SEMI_PRO, PRO}`, that the hand-list ignores.
  `ConfigStartupAssertion.onApplicationEvent` iterates `ConfigBounds.ALL` only, so those four rows
  are never range-checked (an operator setting `video.quota.pro.storageBytes = -1` gets no
  fail-fast).
  **Fix:** add `"semiPro", "pro"` to `VIDEO_QUOTA_TIER_SEGMENTS` at `:204`. The existing loop in the
  `ConfigBounds.ALL` static initializer (`for (String tier : VIDEO_QUOTA_TIER_SEGMENTS)` at
  `~:263-270`) then auto-generates `video.quota.semiPro.storageBytes` /
  `.bandwidthBytesMonthly` and the `pro` pair at the **same** `(0L, Long.MAX_VALUE, false)` bound
  every other tier uses — a **one-line list edit**, not an enum iteration, so `deferred-108` AC9's
  "keep the `config` module free of a `video.contract` / `marketplace.contract` dependency" decision
  is untouched. (`athlete` is already there for the same reason — it is a `resolveTierKey` fallback
  string, not a `CoachSubscriptionTier` constant.)
- **AC10.2 — fix `ConfigBoundsEnumCoverageTest` segment derivation** (`:32`). The
  `everyCoachSubscriptionTierHasStorageAndBandwidthBounds` test derives its expected segment via
  `tier.name().toLowerCase(Locale.ROOT)`. Every current `CoachSubscriptionTier` constant is
  single-word so `toLowerCase` coincides with the camelCase DB key — but every **multi-word** key
  already in the DB is camelCase (`video.quota.semiPro.*`, `video.drillDemo.*`) and
  `VideoTypeConstraints.configKey` camel-cases by hand (the sibling test at `:50-55` uses a manual
  `switch`). A future `PRO_ACADEMY` constant makes this test demand `video.quota.pro_academy.*`
  while the runtime key is `video.quota.proAcademy.*` — the build goes green with bound and key
  pointing at different rows.
  **Fix:** derive the expected segment with the **same camelCase convention the runtime uses**
  (`SCREAMING_SNAKE → camelCase` helper, or a manual `switch` mirroring the `VideoType` test).
- **AC10.3 — assert the completed hand-list.** Add explicit coverage assertions to
  `ConfigBoundsEnumCoverageTest` next to the existing `athlete` block (`:40-43`): the
  `PlayerSubscriptionTierBilling` billing tiers `semiPro` and `pro` each have
  `video.quota.<seg>.storageBytes` and `video.quota.<seg>.bandwidthBytesMonthly` bounds. A test
  cross-referencing `PlayerSubscriptionTierBilling.values()` is acceptable (it is test code — crossing
  a module boundary in `src/test` is fine); or hard-code the two segments with a comment naming the
  enum. Whichever, a new billing-tier constant with no bound must fail this test.
- **AC10.4 — correct the `ConfigBounds.java` comment.** The templated-key comment (**HEAD
  `:198-205`**, added by `deferred-108` AC9) says the guard's only blind spot is a **removed**
  constant. That understates it — it also cannot see a key templated over a **third enum dimension**
  the hand-list does not mirror (which is exactly the `semiPro`/`pro` gap this AC closes). Reword to
  name both blind spots; keep it a comment-only change (no behaviour beyond AC10.1's list edit).
- **AC10.5 — retag the ledger.** `deferred-work.md:1913-1934` (decision 2b) → closed by this AC;
  `:1947-1953` (`ConfigBoundsEnumCoverageTest` camelCase) → closed. (AC15 does the mechanical
  edit.)
- **Test:** `ConfigBoundsEnumCoverageTest` green (all 5 cases + the new ones);
  `ConfigStartupAssertion` boot check now covers `video.quota.semiPro.*` / `.pro.*` — add or extend
  a `ConfigStartupAssertion` test asserting an out-of-range `video.quota.pro.storageBytes` is now
  flagged (it was silently ignored before). Per `docs/validation-strategy.md`: run these targeted
  classes, not full `mvn verify` — GitHub CI is the gate.

### AC11 — bound the remaining disaster-restore image-probe calls (closes `deferred-work.md:1954-1960`)

`deferred-108` AC8 wrapped only `docker pull` and the `docker run … id -u` probe with `timeout` via
the `run_bounded` helper. Still unbounded inside the `${DC} down` → `${DC} up -d` outage window:

- **`deploy/backup/restore-from-volume-backup.sh`** — `image_runtime_uid()` at **HEAD `:81`**:
  - `:87` — `docker image inspect "$img" >/dev/null 2>&1 || run_bounded … docker pull …` — the
    **first `docker image inspect` is unwrapped**, and a wedged daemon hangs it before the wrapped
    `docker pull` is ever reached.
  - `:89` — `user=$(docker image inspect --format '{{.Config.User}}' "$img" 2>/dev/null)` — the
    **second `docker image inspect` is unwrapped**.
  - `:158` — `aws s3 cp "s3://…" "${ARCHIVE_FILE}" --endpoint-url "${HOS_ENDPOINT}"` — **no
    `--cli-read-timeout` / `--cli-connect-timeout`** (ledger cited `:123`; drifted).
  - the ERR-trap recovery `${DC} up -d` and the final `${DC} up -d` (grep for both) — legitimately
    pull absent images, so a **longer** bound, and both are already survivable via the existing
    recovery / ERR-trap path.
- **`deploy/provision.sh`** — the identical `image_runtime_uid()` at **HEAD `:150`**: unwrapped
  `docker image inspect` at `:156` and `:158`. `provision.sh` has **no `${DC}` / no outage window**
  (it runs before the stack is up) — so it needs the two `inspect` wraps but **not** an `aws s3 cp` /
  `${DC} up -d` change.
  **Fix:**
  - Wrap both `docker image inspect` calls in **both** scripts with `run_bounded` using a **short**
    timeout — `inspect` is a local metadata read, fast unless the daemon is wedged; introduce a
    `readonly IMAGE_INSPECT_TIMEOUT=10` (or reuse `IMAGE_PROBE_TIMEOUT` where the value fits) with a
    one-line comment. `run_bounded`'s `command -v timeout` degradation and `-k` kill-after already
    handle a missing `timeout(1)`.
  - `restore-from-volume-backup.sh` only: add `--cli-read-timeout <n> --cli-connect-timeout <n>` to
    the `aws s3 cp` at `:158`; and wrap / bound the two `${DC} up -d` calls with a longer timeout
    (they pull images), noting the ERR-trap already recovers.
  - **Keep the asymmetric-timeout comment discipline `deferred-108` AC8 established** —
    `provision.sh` (`IMAGE_PULL_TIMEOUT=300`, pre-stack, re-pulls seconds later) and
    `restore-from-volume-backup.sh` (`IMAGE_PULL_TIMEOUT=30`, inside the outage window) have opposite
    constraints; **do not copy values between the two files**, and both already carry a "must not be
    copied" warning comment — extend it to the new constants.
  - Update the aggregate-bound comment in each script (`restore-…:30-50`, `provision.sh:79-112`) to
    reflect the newly-bounded calls. Cite `skillars-deferred-109 AC11` / this being the
    `deferred-108` AC8 follow-up.
- **Test:** `bash -n` + `shellcheck -S warning` clean on both scripts. No shell harness — Dev Agent
  Record documents the manual reasoning + a `docker compose config` sanity check, per `deferred-108`
  AC8's precedent.

### AC12 — `VideoModerationEmailListener` outbox-mapping IT + `persisted == null` tighten (re-scoped `deferred-94` AC15/AC16 — `deferred-work.md:1238-1239`)

**Re-scoped per owner decision D5 — the blank-recipient "silent drop" the bullets describe is
already handled at HEAD** (`@PostConstruct` startup ERROR + `ARACHNID_ENABLED` boot-abort +
per-send `log.error` + a **deliberate documented decision** at
`VideoModerationEmailListener.java:86-88` not to retain the outbox row for an unset config key). No
change to that path.

**File:** `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
+ `src/test/.../VideoModerationEmailListenerTest.java` + a new / extended IT.

- **AC12.1 — tighten `persisted == null`.** `sendAdminAlertSync` (`:107-108`) logs
  `log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered alert for videoId=…")` when
  `envelopeEntityRepository.findBySendId(envelope.sendId())` returns `null` — but a `null` there can
  also mean the envelope row is **not yet visible** (persistence lag / a drain that has not
  committed), not "delivered". Change this to `log.warn("[VIDEO_MODERATION_ADMIN_ALERT] send
  outcome not yet visible for videoId=… sendId=… — envelope row not found on read-back")` (or
  similar), so an operator can distinguish "confirmed delivered" (which is really "FAILED row
  absent, send did not stamp a failure") from "unknown". Do **not** throw or retain — this is a
  logging-precision change only. Update `VideoModerationEmailListenerTest.noPersistedEnvelope_doesNotThrow`
  (currently asserts `INFO "[VIDEO_MODERATION_ADMIN_ALERT]"`) to assert the new `WARN`.
- **AC12.2 — the AC16 gap: an IT for the real `Exception → EnvelopeEntity(FAILED, isRetry)`
  mapping.** Nothing today exercises, end to end, that a **retryable** send failure **retains** the
  outbox row (so it re-drives) and a **permanent** failure **deletes** it. `VideoModerationEmailListenerTest`
  is a mocked unit test; `NotificationEmailOutboxAtomicityIT` uses `TestMailManager`, which never
  produces a `FAILED` row (AC6's stated infra premise was wrong). Add an IT (in
  `ModerationOutboxIT` or a new `VideoModerationAdminAlertOutboxIT`) that uses a **real
  `MailManager`** wired to a **mock `JavaMailSender`**: (a) `JavaMailSender` throwing a
  **transient** exception → the envelope is stamped `FAILED` with `isRetry = true`, and
  `sendAdminAlertSync` **rethrows** (`IllegalStateException`, per `:96-99`), so the outbox row is
  retained / re-driven; (b) `JavaMailSender` throwing a **permanent** exception → `FAILED`,
  `isRetry = false`, `sendAdminAlertSync` logs `[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]` and
  returns normally, so the row is released. Assert on the outbox-row state, not just the log.
  Follow the codebase's IT conventions (Testcontainers, `@MockitoBean JavaMailSender`,
  `BasePaymentIT`-style seeding where relevant).
- **AC12.3 — retag the ledger.** `deferred-work.md:1238` and `:1239` → `[DECIDED 2026-09-10
  (skillars-deferred-109 AC12)]` for the blank-recipient behaviour (points at the current hardened
  code — startup ERROR + boot-abort + documented no-retain decision), plus a note that the
  retryable/permanent outbox mapping is now covered by the new IT. (AC15 does the mechanical edit.)
- **Test:** `VideoModerationEmailListenerTest` green (with the two inverted `INFO`→`WARN` /
  behaviour-unchanged assertions); the new IT green. Targeted run only — not full `mvn verify`.

### AC13 — `frontend-unit-tests.yml`: one-step label trigger (owner decision D3 — `deferred-work.md:1904-1912`)

**File:** `.github/workflows/frontend-unit-tests.yml`.

- Add `opened` to the `pull_request: types:` list (**HEAD `:22-23`** — currently
  `types: [labeled, synchronize, reopened]`) so a PR created with `gh pr create --label
  frontend-tests` fires the job in **one** step instead of needing a follow-up
  `gh pr edit … --add-label`. The job-level `if` (`:31-33` —
  `contains(github.event.pull_request.labels.*.name, 'frontend-tests')`) still gates execution on
  the label, so this is **ergonomics only**: the job stays opt-in and **non-gating**.
- Update the header comment block (`:1-18`) — the paragraph at `:16-18` explains the trigger
  mechanics and must mention `opened`.
- **Owner decision D2 / D6 stands:** the job is **not** added to `ci.yml` / `pr-build.yml` /
  `pom.xml`, **not** made a required status check. The `deferred-work.md:1891` "specs are not
  merge-gating" coupling note is **left as-is** (that is B2, deliberately not addressed here).
- **Test:** this story's own PR is the proof — created with `--label frontend-tests`, the
  `frontend-unit` check fires **on `opened`** (confirm in the Actions tab it ran, not skipped).
  Retag `deferred-work.md:1904-1912` closed (AC15).

### AC14 — legacy `PROCESSING→READY` webhook cutover: record the accepted risk + one runbook line (owner decision D4 — `deferred-work.md:1311`)

**No production code change.** `deferred-100` AC5 removed `PROCESSING→READY` from
`VideoLifecycleService.VALID_TRANSITIONS` and made the plain path
`throw TerminalStateViolationException` + `meterRegistry.counter("video.moderation.bypass").increment()`
(**HEAD `VideoLifecycleService.java:77-82`**). A replayed pre-deploy `encoding.success` event that
drives `PROCESSING→READY` on the plain path now dead-letters **and** trips a false
`video.moderation.bypass` alarm. Verified at HEAD: the producer (`WebhookEventProcessorScheduler`)
is gone, no production deploy has ever happened (so no real pre-deploy events exist yet), and
dead-lettered events are re-drivable.

- **AC14.1 — record the acceptance.** Retag `deferred-work.md:1311` as a **deliberate accepted
  cutover risk** (owner decision, this story) — reword from "Accepted cutover risk — AC5 verified …"
  to an explicit `[DECIDED 2026-09-10 (skillars-deferred-109 AC14): accepted. No grace path or
  config-gated allowance will be built. Mitigation is operational — see the runbook pre-production
  release gate. Revisit only if a production deploy is planned with queued legacy events that cannot
  be drained.]`. (AC15 does the mechanical edit.)
- **AC14.2 — one runbook line.** `docs/deployment/runbook.md` has a **"Pre-production release gate"**
  section (`:581+` — "Owner: whoever prepares the first production deploy. Trigger: before that
  deploy, not after."). Add a short subsection (mirroring the existing "outstanding migration
  rewrites" one): before the first production deploy, **drain or discard any queued / replayed
  `encoding.success` (and sibling `encoding.*`) webhook events** — after cutover they drive
  `PROCESSING→READY` on the plain lifecycle path, which dead-letters the event and increments
  `video.moderation.bypass` (a false moderation-bypass alarm). Reference
  `VideoLifecycleService.reconcileToReady()` as the only legitimate `PROCESSING→READY` path
  post-cutover. Keep it to a short paragraph + (optionally) a one-row table entry in the existing
  gate table format.
- **Test:** doc + ledger only. No code, no test harness.

### AC15 — Ledger hygiene + reconstruction check

Per `deferred-work.md`'s own delete-outright-when-closed convention (and the reconstruction-check
discipline every prior audit block follows).

- **Delete** (genuine closures shipped by this story), leaving `[DECIDED]` / `[DISMISSED]`
  untouched:
  - The A1 sub-item bullets in `## Deferred from: code review of skillars-deferred-108 (2026-09-10)`
    that this story fixes: `PaymentMethodCard.vue:174-187` (AC1.1), `:178-181` (AC1.2), `:23-32`
    (AC1.3), `:189-214` (AC1.4); `sessionManager.js:140` (AC2.1), `:259-264` (AC2.2);
    `MainLayout.vue:335-341` (AC3.1), `:305,314` (AC3.2), `:353-363` (AC3.3); `playerStore.js:32-54`
    (AC4.2); `booking.store.js:605-630` (AC5.1), `:394-395` (AC5.2); `BookingRequestPage.vue:457-462`
    (AC6); `ParentBookingsPage.vue:252-266` (AC7); `ProfileBuilderStep3.vue` (AC8);
    `BookingStateChip.vue:12` (AC9); `ConfigBoundsEnumCoverageTest.java:32` (AC10.2).
  - `deferred-work.md:1695` — the `playerStore.js` `fetchSelfPlayerId` return-value residual (AC4.1).
  - `deferred-work.md:1904-1912` — the `frontend-unit-tests.yml` `opened`-trigger bullet (AC13).
- **Retag `[DECIDED …]`** (not deleted):
  - `deferred-work.md:1913-1934` (`ConfigBounds` incomplete hand-list, decision 2b) → closed by
    AC10, but keep a bullet noting the hand-list is now complete + the drift guard fixed. *(Or
    delete outright if the section convention prefers — record which in the audit block.)*
  - `deferred-work.md:1238-1239` (`VideoModerationEmailListener`) → `[DECIDED … skillars-deferred-109
    AC12]` (blank-recipient behaviour is documented owner decision; retryable/permanent mapping now
    IT-covered).
  - `deferred-work.md:1311` (legacy webhook cutover) → `[DECIDED … skillars-deferred-109 AC14]`
    (accepted risk; runbook mitigation).
- **Leave untouched:** `deferred-work.md:1891` (B2 coupling note); `deferred-work.md:1935-1946`
  (`BookingStateChip` prop) is deleted by AC9's line above — **wait**: it is in the same
  `deferred-108` CR section, delete it with the AC9 closure; the remaining `deferred-108` CR bullets
  this story does **not** address (`ConfigBoundsEnumCoverageTest` camelCase is AC10.2 — deleted;
  the `provision.sh:104,106` / `restore-…` remaining-unbounded-calls bullet at `:1954-1960` is
  **closed by AC11** — delete it too); `skillars-7-1` D4; all `[DECIDED]` / `[DISMISSED]`.
- **Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
  `bbad7938` + this story's implementation commits), in order, nothing reworded/reordered apart from
  the retags enumerated. **Re-run `wc -l` before writing the line-count figures** — the
  `deferred-108` pass mis-recorded them and its own code review flagged it. Record: pre-edit line
  count, post-delete line count, post-append (audit block) line count; `## Deferred from:` header
  count before/after (note which sections empty and whether their headers are removed — the
  `deferred-108` CR section keeps `:1891` + `skillars-7-1` D4 references? no — `:1891` stays, so
  that section's **header stays**); `[DECIDED]` count delta; `[DISMISSED]` count unchanged;
  `[PICKED UP by …]` bullets touched (the two `deferred-94` AC15/AC16 bullets — retagged, not
  deleted).
- **Test:** the reconstruction check itself is the gate; no code.

---

## Tasks / Subtasks

1. **Re-diff every cited line against HEAD** (`git show HEAD:<path>` / open each file) before writing
   any code — the file's convention and this story's own header both demand it. Note drift in the
   Dev Agent Record.
2. **AC1** — `PaymentMethodCard.vue` + `payment.store.js`: 4 fixes, extend
   `PaymentMethodCardSpec.js` + `paymentStoreSpec.js`. Each fix mutation-verified (RED reverted,
   GREEN restored).
3. **AC2** — `sessionManager.js`: skew-check spec (spec-only) + ineffective-refresh fix; extend
   `sessionManagerCoverageSpec.js`.
4. **AC3** — `MainLayout.vue`: logout parity + `localStorage` guards + silent-404 spec; extend
   `MainLayoutSpec.js`.
5. **AC4** — `playerStore.js`: one-line return-value fix (flip the `deferred-108` characterization
   test) + reject-path spec; extend `playerStoreSpec.js`.
6. **AC5** — `booking.store.js`: accept-all `null`-leak fix + null-response guard; extend
   `bookingStoreSpec.js`.
7. **AC6** — `BookingRequestPage.vue` `slotRows` NaN filter; extend `BookingRequestPageSpec.js`.
8. **AC7** — `ParentBookingsPage.vue` `rescheduleProposedEnd` wall-clock arithmetic; extend
   `ParentBookingsPageSpec.js`.
9. **AC8** — `ProfileBuilderStep3.vue` partial-pack guard + duration coercion; extend
   `ProfileBuilderStep3Spec.js`.
10. **AC9** — `BookingStateChip.vue` prop widening; update `ParentBookingsPageSpec.js` NOTE +
    no-warning assertion.
11. **Frontend green gate:** `cd src/frontend && npm run test:unit` + `npm run test:unit:ci` green;
    `eslint` + `prettier --check` clean on every touched spec.
12. **AC10** — `ConfigBounds.java` list edit + comment; `ConfigBoundsEnumCoverageTest.java`
    camelCase derivation + `semiPro`/`pro` assertions; `ConfigStartupAssertion` test for a bad
    `video.quota.pro.*`. Targeted class run.
13. **AC11** — wrap `docker image inspect` ×2 in both deploy scripts; `aws s3 cp` +
    `${DC} up -d` bounds in `restore-from-volume-backup.sh`; comments; `bash -n` + `shellcheck -S
    warning` clean.
14. **AC12** — `VideoModerationEmailListener` `persisted == null` INFO→WARN; new outbox-mapping IT;
    update `VideoModerationEmailListenerTest`. Targeted run.
15. **AC13** — `frontend-unit-tests.yml` add `opened` + comment update.
16. **AC14** — `deferred-work.md:1311` retag + `docs/deployment/runbook.md` pre-production
    release-gate subsection.
17. **AC15** — ledger hygiene pass + reconstruction check (re-run `wc -l`).
18. **Validation summary** in the Dev Agent Record: per-AC mutation checks (RED/GREEN), frontend
    suite counts, targeted backend class results, `bash -n`/`shellcheck` results, no full
    `mvn verify` (GitHub CI is the gate — `docs/validation-strategy.md`,
    [[feedback_no_local_mvn_verify]]).
19. **PR:** `gh pr create` against `master`, then `gh pr edit <n> --add-label frontend-tests` (or
    rely on AC13's `opened` once it lands in the same PR — but AC13 is *in* this PR, so the label
    still needs adding post-create for the **first** run; a follow-up push fires `synchronize`).
    Confirm the `frontend-unit` check ran (not skipped).

## Dev Notes

### Established patterns to follow (do not reinvent)

- **Frontend conventions:** [[project_skillars_ui]] · `vue-best-practices` / `vue-testing-best-practices`
  skills · `<script setup>` + Composition API · all API calls via `src/api/*.api.js` · `async/await`,
  no `.then()` in new code (existing `.then()` chains in `playerStore.js` / `PaymentMethodCard.vue`
  stay — minimal local fixes only).
- **`deferred-108`'s story file is the reference** for the spec conventions (its "Global conventions
  for AC1–AC6") and for how a mutation-sensitivity Dev Agent Record reads. Read it once.
- **Backend:** [[project_skillars_ui]] not relevant; `java-springboot` skill · Java records for DTOs ·
  targeted-test-then-CI validation ([[feedback_no_local_mvn_verify]], `docs/validation-strategy.md`).
- **`ConfigBounds` module boundary:** the `config` module must stay free of `video.contract` /
  `marketplace.contract` / `payment.contract` compile deps (`deferred-107` AC3 + `deferred-108`
  AC9). AC10.1 respects this — a `List.of(...)` string edit, not an enum import. The **test**
  (`src/test`) may cross module boundaries.
- **Deploy scripts:** `run_bounded` helper already exists in both (`deferred-108` AC8) — reuse it,
  do not add a second bounding mechanism. Asymmetric constants between `provision.sh` and
  `restore-from-volume-backup.sh` are deliberate — the "must not be copied between files" comment is
  load-bearing.
- **Ledger:** [[project_skillars_release_workflow]] — this story is one turn of the recurring
  done→commit→PR→merge→prune→next-story cycle. `deferred-work.md` edits follow its
  delete-outright + reconstruction-check convention.

### Files being modified — current state / what changes / what to preserve

| File | Current state (HEAD `bbad7938`) | This story changes | Must preserve |
|---|---|---|---|
| `PaymentMethodCard.vue` | `loadStripeConfig` clears `stripeUnavailable` before the refetch; store swallows fetch errors; `submit()` handles `error`/`!succeeded` with one generic message | AC1.1–AC1.4: retry ordering, stale-key guard, spec coverage for `brand:null` + save path | the `deferred-103` AC9 retry affordance; the `mountGeneration` guard; `onBeforeUnmount` cleanup |
| `payment.store.js` | `fetchStripeConfig`/`fetchSavedPaymentMethod` swallow → `this.error.*`, resolve; never null `stripeConfig` on error | AC1.2: null `stripeConfig` on error **or** surface it in `loadStripeConfig` (pick one) | the swallow-and-resolve contract other callers depend on (grep first) |
| `sessionManager.js` | `refreshSession` treats any resolved `refresh()` as success; skew-check `:141` untested | AC2.1 spec-only; AC2.2 post-refresh expiry-advanced check | `recordActivity()`-before-call ordering; the dynamic `import('src/api/session.api')`; `refreshExpiryState()` warning-edge handling |
| `MainLayout.vue` | `handleLogout` = bare `await authStore.logout()`, clears only `user` cookie; `localStorage` unguarded; silent-404 block player-only | AC3.1 bounded race + `rint` clear; AC3.2 `try/catch`; AC3.3 spec-only | the logout step ordering; `onMounted`/`onUnmounted` storage-listener lifecycle; the 404-is-silent product contract |
| `playerStore.js` | `fetchSelfPlayerId` returns `profile.id` unconditionally; generation guards only the `.value` write | AC4.1 one-line return gate | the in-flight de-dup (`selfPlayerIdRequest`); the `.finally` reference-clear; the `id == null` throw |
| `booking.store.js` | `handleAcceptAllBatch` seeds `null` before `try`, rethrows on failure without pruning; `loadCoachBookingRequests` derefs `res` with no null guard | AC5.1 delete the leaked entry (or reseed post-success); AC5.2 `?? {}` on `res` | the CONTRACT block (`:358-368`) — loaders never rethrow; the out-of-order `requestId` guard; `handleAcceptAllBatch`'s rethrow (callers depend on it) |
| `BookingRequestPage.vue` | `slotRows` available branch has no NaN filter; `ownBlockingBookings` does | AC6: `Number.isNaN(Date.parse(...))` filter on the available branch | the interleave-by-`sortKey` sort; the `deferred-17/-18` `.startDatetime` field names |
| `ParentBookingsPage.vue` | `rescheduleProposedEnd` = instant delta + wall-clock read-back | AC7: wall-clock-minutes arithmetic | the `datetime-local` binding; the submit payload `.toISOString()` shape |
| `ProfileBuilderStep3.vue` | `submit()` silently filters partial pack rows; `durationOptions.includes` strict-eq | AC8.1 partial-row guard; AC8.2 `Number()` coercion | the `deferred-63` AC7 defensive synthetic-option behaviour; all-`null` rows stay silently filtered |
| `BookingStateChip.vue` | `bookingId: { type: String }` | AC9: `[String, Number]` | `useBookingSse` gating on `bookingId` + non-terminal status |
| `ConfigBounds.java` | `VIDEO_QUOTA_TIER_SEGMENTS` missing `semiPro`/`pro`; comment names only "removed constant" blind spot | AC10.1 list edit; AC10.4 comment | the module-decoupling decision (no enum imports); the `athlete` fallback entry; every other `BoundedKey` |
| `ConfigBoundsEnumCoverageTest.java` | `:32` derives via `toLowerCase`; no `semiPro`/`pro` assertions | AC10.2 camelCase derivation; AC10.3 billing-tier assertions | the `VideoType` `switch` pattern (already correct); the internal-consistency / no-dup cases |
| `VideoModerationEmailListener.java` | `persisted == null` → `INFO "delivered"`; blank recipient fully handled (startup + per-send ERROR + documented no-retain) | AC12.1 INFO→WARN on `persisted == null` only | the `@PostConstruct` check; the `isRetry()` throw / permanent-release split; the documented no-retain decision (`:86-88`) |
| `frontend-unit-tests.yml` | `types: [labeled, synchronize, reopened]` | AC13: add `opened` + comment | the job-level label `if` gate; the decoupled-from-build-gate design |
| `docs/deployment/runbook.md` | pre-production release gate has the migration-rewrites subsection | AC14.2: new "drain queued webhook events" subsection | the existing gate structure / owner-trigger framing |
| `deferred-work.md` | post-`deferred-108` state, 1959 lines | AC15: delete/retag closed bullets + audit block | `[DECIDED]` (31) / `[DISMISSED]` (33); `skillars-7-1` D4; the `:1891` B2 note |

### What must NOT change

- `.github/workflows/ci.yml`, `pr-build.yml`, `pom.xml` — the Vitest job stays opt-in (D2/D6).
- The `deferred-108` reference specs (`SkillsRadarChartSpec.js`, `sessionManagerSpec.js`) and the
  `deferred-108` spec bodies — **extend**, never restyle.
- `VideoModerationEmailListener`'s blank-recipient path (D5 — documented owner decision).
- `VideoLifecycleService` — AC14 is doc + ledger only (D4).
- `VIDEO_TYPE_SEGMENTS` / any `BoundedKey` other than the four new `semiPro`/`pro` rows.
- Any `[DECIDED]` / `[DISMISSED]` ledger bullet.

### Project-context rules that apply

`_bmad-output/project-context.md`: Vue `<script setup>` + Composition API; `async/await` not
`.then()` (new code); centralized `*.api.js`; Java records for DTOs; MapStruct for mappings;
targeted tests locally, GitHub CI is the full gate.

## Change Log

| Date | Change |
|---|---|
| 2026-09-10 | **Story created (→ ready-for-dev).** Drawn from `deferred-work.md` @ `bbad7938` (post-`deferred-108` #174 + prune #175). 15 ACs: AC1–AC9 the frontend defect sweep (all 16 `deferred-108`-CR bullets + the `playerStore.js` return-value leak, owner D1); AC10 `ConfigBounds` completeness — add `semiPro`/`pro` to the hand-list + fix `ConfigBoundsEnumCoverageTest` camelCase derivation (owner D2); AC11 bound the remaining disaster-restore image-probe calls (`docker image inspect` ×2, `aws s3 cp`, `${DC} up -d`); AC12 `VideoModerationEmailListener` — re-scoped to test-only (the bullet is stale; blank-recipient is a documented decision at HEAD) + the missing outbox-mapping IT + `persisted==null` INFO→WARN (owner D5); AC13 `frontend-unit-tests.yml` add `opened` trigger, stays opt-in (owner D3/D6); AC14 legacy `PROCESSING→READY` webhook cutover — accepted risk, retag + one runbook line, no code (owner D4); AC15 ledger hygiene + reconstruction check (re-run `wc -l`). Out of scope: `skillars-7-1` D4 (owner D8), B2 promote-to-required-check (D6), migration rebaseline, `pending_blob_deletions` drop, native register review, all `[DECIDED]`/`[DISMISSED]`. All cited lines re-verified against HEAD during creation (several drifted — noted per-AC). |

## Dev Agent Record

### Context Reference

- `_bmad-output/implementation-artifacts/deferred-work.md` @ `bbad7938` — sections
  `## Deferred from: code review of skillars-deferred-108 (2026-09-10)` (`:1803-1960`),
  `:1695`, `:1238-1239`, `:1311`, `:1904-1912`, `:1913-1953`.
- `_bmad-output/implementation-artifacts/skillars-deferred-108-frontend-unit-spec-backfill-restore-pull-timeout-and-configbounds-templated-key-decision.md`
  — spec conventions + the 10 spec files this story extends.
- `docs/validation-strategy.md`, `docs/deployment/runbook.md` (pre-production release gate).

### Agent Model Used

_(dev agent to fill)_

### Implementation Plan

_(dev agent to fill)_

### Completion Notes List

_(dev agent to fill)_

### Validation summary

_(dev agent to fill — per-AC mutation RED/GREEN, frontend suite counts, targeted backend classes,
`bash -n`/`shellcheck`, reconstruction check line/header counts)_

### File List

_(dev agent to fill)_

### Review Findings

_(code review to fill)_
