# skillars-deferred-109: Frontend Defect Sweep + ConfigBounds Completeness + Deploy-Probe Bounding + Cutover Decisions

**Status:** done | **Epic:** deferred | **Priority:** medium
**Story ID:** deferred-109
**Branch:** `story/deferred-109-cross-module-cleanup`
**Created:** 2026-09-10 · **Story-review audit applied:** 2026-09-11
**Base:** master @ `bbad7938` (immediately after `skillars-deferred-108` (#174) and the
`deferred-108`-followup ledger prune (#175) — both merged and master-CI verified this same session)

> **Story-review audit (2026-09-11) applied.** `story-review.md` found 3 blockers (AC7, AC12.1,
> AC10), 4 high (AC2.2, AC1.1↔AC1.2, AC4.1, AC11) and a set of accuracy issues. **All were verified
> against HEAD and accepted** — no false positives. This revision: **AC7** drops the backwards
> arithmetic fix and re-scopes to pinning the (correct) fixed-instant behaviour; **AC12.1** now
> requires an explicit `null` / `SENT` branch split; **AC10** corrects the premise (nothing reads
> `video.quota.semiPro.*`/`.pro.*`; no boot refusal — ERROR + metric only) and files the real
> `resolveTierKey` entitlement gap as its own ledger bullet; **AC1.1+AC1.2** merged into one fix
> (option a); **AC2.2** conditioned on `hasSeenRintThisTab()` + ordering pinned; **AC4.1** returns
> literal `null`; **AC11** drops the ERR-trap `${DC} up -d` bound and fixes the redirection/label
> issues; **AC15** baselines corrected (**1991** lines, not 1959). Cite drift throughout corrected —
> Task 1 ("re-diff every cited line") stays load-bearing; treat every cite here as advisory.

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

> **Cite-accuracy caveat (story-review 2026-09-11).** Most cites below were re-verified against HEAD
> and are correct; a handful drifted or were mis-stated at creation and are corrected inline. Where
> the original ledger cite and this story disagree, **the ledger value in `deferred-work.md` is the
> one re-checked most recently** — Task 1 ("`git diff` every cited line against HEAD") is
> load-bearing and must be done first; treat every line number here as advisory.

### AC1 — `PaymentMethodCard.vue` card-collection lifecycle fixes (closes 4 `deferred-108` CR bullets)

**File:** `src/frontend/src/components/payment/PaymentMethodCard.vue` (script `<script setup>` at
`:75-223`). **Spec:** extend `components/payment/__tests__/PaymentMethodCardSpec.js` (+ its store
half in `stores/__tests__/paymentStoreSpec.js`).

- **AC1.1 — retry after a failed Stripe-config fetch: one click, no stale key** (merges the former
  AC1.1 + AC1.2 — `deferred-work.md:1811-1817` + `:1818-1822`; `PaymentMethodCard.vue:174-187`,
  `payment.store.js:238-259`). Two coupled defects in the same code path:
  - **Retry no-ops on the first click.** `loadStripeConfig({ isRetry: true })` sets
    `stripeUnavailable.value = false` at `:176` **before** `await Promise.all([…])` at `:178`. That
    flips `showForm` (`:101-103`) `false`→`true`, queuing the `watch(showForm, …)` job (`:155-158`)
    → `mountCardElement()` → `ensureStripeReady()` runs *during* the `await`, reads the still-null
    `paymentStore.stripeConfig?.publishableKey` (`:109`), re-sets `stripeUnavailable = true` (`:111`).
    When the successful refetch resolves, `if (showForm.value)` at `:186` is now `false` — Elements
    never mounts, user must click **Try again** twice.
  - **A failed refetch leaves a stale key in use, and the `catch` is dead.**
    `payment.store.js`'s `fetchStripeConfig` (`:238-248`) / `fetchSavedPaymentMethod` (`:249-259`)
    `catch (err) { this.error.X = err }` and **resolve** — so `Promise.all` at `:178` never rejects
    and the `catch` at `:179-181` is unreachable dead code. `fetchStripeConfig` never nulls
    `this.stripeConfig` on error (`:242-244`), so a stale-but-non-null `publishableKey` from an
    earlier success survives a later failure.
  - **Fix — one shape only (story-review: options that let `showForm` flip before the raise
    compose badly and re-flicker the unavailable block).** In `loadStripeConfig`, after the
    `await Promise.all`, check `paymentStore.error.stripeConfig` (and `error.savedPaymentMethod` if
    relevant) — if set, raise `stripeUnavailable` and `return` **before** `showForm` is allowed to
    flip. This makes the dead `catch` a live branch (or lets you delete it). Do **not** also move
    the `stripeUnavailable.value = false` line and do **not** null `stripeConfig` in the store —
    the after-`await` error check is sufficient and does not fight the watcher. Keep the store's
    swallow-and-resolve contract (grep its other callers — `deferred-108` AC1 spec,
    `SessionPackPurchase` flows — before touching it; do not make the actions rethrow).
  - **Mutation check:** (1) re-introduce the early `stripeUnavailable.value = false` clear / remove
    the after-`await` error check → the "retry mounts Elements on the first click" assertion fails;
    (2) restore the swallowed `error.stripeConfig` (so `loadStripeConfig` cannot see it) → the
    "failed refetch → `stripeUnavailable`, `loadStripe` not called with a stale key" assertion fails.

- **AC1.2 — a failed post-save refresh does not strand the entry form** (story-review finding 16 —
  same function, `PaymentMethodCard.vue:202-206`). `submit()`'s inner
  `try { await paymentStore.fetchSavedPaymentMethod() } catch { /* not a save failure */ }` is dead
  for the same reason as above — the store action swallows and resolves. Consequence: after a
  **successful save whose refresh silently fails**, `savedPaymentMethod` stays stale/null, so
  `showForm` (`:101-103`) keeps the entry form mounted even though the card saved, with no signal.
  **Fix:** after the `await paymentStore.fetchSavedPaymentMethod()` in `submit()`, if
  `paymentStore.error.savedPaymentMethod` is set, surface a non-blocking notice (the card *is*
  saved — this is "we saved it but couldn't refresh the view") rather than silently leaving the
  form. Keep `emit('saved')` — the save succeeded.
  **Mutation check:** make `fetchSavedPaymentMethod` reject after a successful `savePaymentMethod`
  in the spec → the new "post-save refresh failure surfaces a notice, form does not silently
  persist" assertion fails without the guard.

- **AC1.3 — `hasCard: true` with `brand: null` renders a real label**
  (`deferred-work.md:1823-1827`; `PaymentMethodCard.vue` template `:20-33`).
  `SessionPackPaymentService.java` returns `new SavedPaymentMethodResponse(true, null, null, null, null)`
  for a card whose brand/last4 could not be resolved. The template's `savedCard.brand ? t('payment.card.savedLabel', {…}) : t('payment.card.detailsUnavailable')`
  ternary (`:24-31`) has a false arm, and the **Replace card** button beside it is at `:34-41`
  (**not** `:62` — that is the edit-form Cancel button). Both have zero coverage; a regression that
  drops the ternary renders `savedLabel` with `undefined undefined` and ships green.
  **Spec-only — the key already exists** (`payment.card.detailsUnavailable` is in all three bundles;
  story-review verified `en-US:1224` / `de-DE:1110` / `fr-FR:991`, so there is **no** "add the key"
  branch). Assert the false arm renders `payment.card.detailsUnavailable` and the row still shows
  the "Replace card" affordance.
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
  `if (remaining <= 0 && localEstimate > 0) return localEstimate` at **HEAD `sessionManager.js:140`**
  — the ledger cite was correct; the story-creation "correction to `:141`" was itself wrong).
  This one line is the whole defence between a fast client clock and the
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
  (`deferred-work.md:1839-1843`; `refreshSession()` at **HEAD `sessionManager.js:248-273`**).
  `await sessionApi.refresh()` is at **`:259`**; `recordActivity()` + `refreshExpiryState()` follow
  at **`:263-264`** (story-creation said `:262` / `:264-265` — drifted); `refreshFailed` is set only
  in the `catch` (`:271`). No branch checks that the expiry actually moved forward. A proxy that
  strips `Set-Cookie` on `GET /refresh`, or any 200 that does not re-issue `rint`, re-enables
  "Continue session", keeps the countdown ticking, and logs the user out at `0:00` — the UX
  `deferred-90` AC4 was written to eliminate for the thrown-error case only.
  **Fix (story-review finding 4 — the naive "expiry absent → fail" breaks the documented legacy
  fallback):** `readSessionExpiryFromCookie()` (`:78-87`) returns `null` for **two** cases — cookie
  absent *and* the stale pre-1.7b non-epoch format — and the module has a whole documented fallback
  (`:113` region) plus `hasSeenRintThisTab()` (`:36`) precisely to tell "legacy build" from
  "cleared". So:
  - Only flag failure when **`hasSeenRintThisTab()` is true** (this tab is on the absolute-`rint`
    contract) — a successful refresh on a build that never issues an absolute `rint` must **not**
    set `refreshFailed`.
  - Use a **future-ness** predicate, not strict monotonicity: the expiry read back is *meaningfully
    in the future* (e.g. `> WARNING_THRESHOLD` remaining). Strict "greater than the pre-call value"
    false-positives on a multi-tab race and on two same-second `rint` writes.
  - **Ordering:** run the check **after** `refreshExpiryState()` (`tick()` clears `refreshFailed` on
    the warning-exit edge at `:184` and `cleanup()` clears it at `:286` — setting the flag before
    `refreshExpiryState()` silently loses it; setting it after, only if `tick()` did not already
    tear the session down).
  **Spec:** add (1) the "200 without a fresh `rint`, `hasSeenRintThisTab()` true → `refreshFailed`"
  case, **and** (2) a **legacy-path** case — `hasSeenRintThisTab()` false (no absolute `rint` ever
  seen), a resolved `refresh()`, expiry still `null` → `refreshFailed` stays `false`. Without (2)
  the regression ships green.
  **Mutation check:** remove the `hasSeenRintThisTab()` condition → case (2) fails (legacy refresh
  wrongly flagged); remove the whole post-refresh check → case (1) fails.

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
  **Fix — match `useSession.handleLogout`'s three guarantees, not just one (story-review finding
  17):**
  1. **Bounded `Promise.race`** around `authStore.logout()` using the same `LOGOUT_BACKEND_WAIT_MS`
     constant.
  2. **Both `rint` clears** `useSession.js` does, each for its documented reason: **pre-race**
     (`useSession.js:82` — so sibling tabs enter `computeTimeUntilExpiry`'s fast-teardown branch
     immediately instead of after the up-to-3000 ms window) **and post-race** (`useSession.js:104` —
     because any authenticated response in flight when the pre-race clear ran re-sets `rint` with
     `path=/` via `JwtManagerImpl`). One clear is not parity.
  3. `deleteUserCookie()` (`:331-333`) still clears `user`.
  - **Ordering — state what unifies and what stays different.** `useSession.handleLogout` calls
    `stopSessionMonitoring()` **first** (`:71`); `MainLayout` reaches `destroySession()` only after
    the logout await (`:338`). This story unifies the **`rint`/timeout** behaviour, **not** the
    monitoring-teardown ordering — `MainLayout` keeps its `logout → resetSelfPlayerId →
    destroySession → deleteUserCookie → router.push('/login')` sequence, now with the two `rint`
    clears wrapped around the bounded `authStore.logout()`. Say so in a code comment so the
    remaining divergence reads as deliberate.
  - Extracting a shared helper is optional and only if it is a clean lift — a duplicated bounded
    race is acceptable (`deferred-108` AC4's overlap note kept the two `handleLogout`s separate).
  **Mutation check:** revert the `Promise.race` bound → the "stalled `logout` still resolves
  `handleLogout` within `LOGOUT_BACKEND_WAIT_MS`" assertion hangs / fails; revert **either** `rint`
  clear → its matching assertion fails (final `document.cookie` still carries a live `rint`, tested
  with and without a simulated in-flight `rint` re-write for the post-race one).

- **AC3.2 — `localStorage` access is guarded** (`deferred-work.md:1851-1855`;
  `MainLayout.vue:305` in `changeLanguage`, `:314` in `loadLanguagePreference`).
  `sessionManager.js` wraps its equivalent `sessionStorage` calls in `try/catch`; `MainLayout` does
  not. In Safari private mode `localStorage.setItem` (`:305`) throws **before** the `lang` cookie
  clear at `:310` (the line that exists to unstick a stuck backend `lang` cookie) runs, and
  `localStorage.getItem` (`:314`) throws inside `onMounted` (`:348`), aborting before the `storage`
  listener registers (`:351`) and before the player self-id fetch (`:353-363`).
  **Fix:** wrap both `localStorage` accesses in `try/catch` (match `sessionManager.js`'s idiom — a
  small `safeLocalStorageGet` / `safeLocalStorageSet` helper, or inline `try/catch`). In
  `changeLanguage`, the `lang`-cookie clear (`:310`) must still run even if `setItem` (`:305`)
  throws. **Note `locale.value = lang` (`:304`) already runs *before* `setItem`**, so it is not the
  mutation-sensitive part.
  **Mutation check:** remove the `try/catch` around `changeLanguage`'s `setItem` → the new
  "`setItem` throwing does not prevent the `lang` cookie clear at `:310`" assertion fails (the
  `locale.value` half passes either way — do not rely on it for the RED signal).
  **Scope note:** `src/boot/theme.js:28,47` carry the same unguarded `localStorage`, reached from
  this component via `onToggleTheme` (`:322`) / `onStorageThemeChange` (`:327`). **Out of scope for
  AC3.2** — so "MainLayout is storage-safe" is not a claim this AC makes; a future story owns
  `boot/theme.js`.

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
  **Fix (one line) — return literal `null`, NOT `selfPlayerId.value` (story-review finding 6).**
  `return requestGeneration === selfPlayerIdGeneration ? profile.id : null`.
  Returning `selfPlayerId.value` is a **ref read at resolution time**: if account A's call is in
  flight, `resetSelfPlayerId()` fires, account B then calls `fetchSelfPlayerId()` (new request,
  new generation), B resolves and writes `selfPlayerId.value = <B's id>`, *then* A's slow chain
  resolves → generation mismatch → returns `selfPlayerId.value` = **B's id**. That is worse than
  the residual being closed. `null` is verified safe downstream: `BookingRequestPage.vue:297`
  `canSubmit` gates on `!!playerId.value` and `:544` re-checks `if (!playerId.value) return`;
  `MainLayout.vue:289` guards the nav link with `selfPlayerId.value ? … : null`. Confirm the
  `profile?.id == null` throw at `:42-44` still fires for a genuinely id-less response regardless
  of generation.
  **Spec:** `deferred-108` AC4's `playerStoreSpec.js` has a **characterization test** for the
  current leaky behaviour ("cross-account guard — returned promise (records the residual)") —
  **flip it** to assert the superseded call's promise resolves to **exactly `null`** (drive the
  B-repopulates-the-ref sequence above so the test would catch `return selfPlayerId.value` too).
  **Mutation check:** restore the unconditional `return profile.id` → the flipped assertion fails;
  change the fix to `: selfPlayerId.value` → the B-repopulation case fails (resolves B's id, not
  `null`).

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
  **Fix — option (a) only (story-review finding 8: option (b) regresses).** In the `catch`, delete
  the `batchId` entry (`setBatchAcceptResult` extended to accept a delete, or a targeted
  `delete`-into-a-copy then reassign — match the store's copy-on-write style) **before** `throw e`.
  Option (b) — moving the seed to after success — is **rejected**: the `:608` seed's real job is
  not slot reservation (`setBatchAcceptResult` at `:594-603` already does `delete; reinsert` for
  ordering on every write), it is **clearing the previous attempt's result**. Under (b), a coach
  who accept-alls, sees partial results, then accept-alls again and *fails* keeps seeing the **stale
  first result** rendered as current. Keep the rethrow — `grep` the return-value consumers first,
  but callers do depend on it.
  **Mutation check:** revert the `catch`-delete → the new "a rejected `handleAcceptAllBatch` leaves
  no `batchId` key in `batchAcceptResultsByBatch`, and the previous attempt's result is not left
  rendering" assertion fails.

- **AC5.2 — `loadCoachBookingRequests` classifies a nullish response as an error, not empty
  success** (`deferred-work.md:1870-1873`; `booking.store.js:388-429`,
  `const res = await getCoachBookingRequests()` at `:393`, `res.singleBookings ?? []` at `:395`).
  The `?? []` guards a **missing property**, not a null `res`. A `204`, or an interceptor unwrapping
  an empty body to `undefined`, makes `res.singleBookings` throw a `TypeError` inside the `try` —
  caught at `:416` and filed into `coachRequestsError.value = e` (`:424`) as though it were an HTTP
  failure.
  **Fix (story-review finding 9 — do NOT `?? {}` into an empty-success).** A bare `?? {}` would
  make a `204`/garbled body set `coachBookingRequests`/`coachBatchGroups` to `[]`, **return `true`**
  (so per the CONTRACT block every caller treats the refresh as successful and the `deferred-31` AC1
  stale-list warnings stay silent), **and** prune every `batchAcceptResultsByBatch` entry (empty
  `visibleBatchIds` at `:409-413`) — a coach's whole request list silently blanking and reporting
  success. There is no documented `204` on this endpoint; an unexpected body is closer to an error.
  So: add an explicit guard **before** the destructure —
  `if (res == null) { coachRequestsError.value = new Error('empty response from getCoachBookingRequests'); return false }`
  — which stops the `TypeError`, preserves the stale-list warning, and does **not** blank the list
  or run the prune. (Mirror in `loadCoachSchedule` only if its shape is identical — check `:431+`,
  do not over-reach.)
  **Mutation check:** remove the `res == null` guard → the new "a `null` response sets a
  distinguishable `coachRequestsError`, returns `false`, and does **not** clear
  `coachBookingRequests` or prune `batchAcceptResultsByBatch`" assertion fails (either a `TypeError`
  leaks, or — if paired with a `?? {}` — the list blanks).

### AC6 — `BookingRequestPage.vue` `slotRows` NaN filter (closes `deferred-work.md:1874-1878`)

**File:** `src/frontend/src/pages/parent/BookingRequestPage.vue`. **Spec:** extend
`pages/parent/__tests__/BookingRequestPageSpec.js`.

- `slotRows` (computed at **HEAD `:456-472`**): the `available` branch
  (`:457-462`) maps each slot to `{ key: `slot-${slot.startDatetime}`, sortKey: Date.parse(slot.startDatetime), … }`
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

### AC7 — `ParentBookingsPage.vue` `rescheduleProposedEnd`: pin the (correct) fixed-instant behaviour + handle the DST-boundary display (RE-SCOPED — closes `deferred-work.md:1879-1883`)

**File:** `src/frontend/src/pages/parent/ParentBookingsPage.vue`. **Spec:** extend
`pages/parent/__tests__/ParentBookingsPageSpec.js`.

> **Story-review finding 1 (blocker) — the original "wall-clock minutes" fix was backwards.**
> `RescheduleService.java:176-184` rejects a reschedule unless
> `Duration.between(proposedStartTime, proposedEndTime).equals(Duration.between(requestedStartTime, requestedEndTime))`
> — an **exact elapsed-instant** duration (`req.proposedStartTime()` / `proposedEndTime()` are
> `Instant`s). `rescheduleDurationMs` (`ParentBookingsPage.vue:340-348`) is derived the same way
> (`end.getTime() - start.getTime()` off the original booking's instants), and the template comment
> (`:174-176`) states the contract ("keep the session's original length"). **The current
> fixed-instant arithmetic is exactly what the backend requires.** A "start-wall-clock + N wall
> minutes" end would submit a `proposedEndTime` that is 0 or 120 elapsed minutes across a DST
> boundary → hard rejection. **Do NOT change the arithmetic.**

- **AC7.1 — pin the current behaviour as correct (spec + comment).** `rescheduleProposedEnd`
  (computed at **HEAD `:252-256`**) adds the fixed-instant `rescheduleDurationMs.value` to
  `new Date(rescheduleProposedStart.value)` and renders via `toDatetimeLocal` (`:260-264`). Add a
  spec asserting the submitted `proposedEndTime` (`:372-373`,
  `new Date(rescheduleProposedEnd.value).toISOString()`) is `proposedStartTime + originalDurationMs`
  **to the millisecond** — i.e. `Duration.between` on the submitted pair equals the original — and a
  one-line code comment citing `RescheduleService.java:176-184` so this is not re-filed.
  **Mutation check:** change the delta to wall-clock minutes → the "submitted pair has the same
  elapsed `Duration` as the original booking" assertion fails.
- **AC7.2 — the DST-boundary *display* is the real (smaller) defect.** Two things worth a spec /
  small display fix, neither touching the submitted instant:
  - **Fall-back ambiguity.** `new Date('2026-11-01T01:30')` in `America/New_York` silently resolves
    an ambiguous wall time, and the derived end can render **identical to the start**, which reads
    as a broken form. Pin this with a spec; if a display affordance is cheap (e.g. show the end in
    the session's `canonicalTimezone`, `:358`, rather than browser-local), add it — but the
    submitted `proposedEndTime` still = start + original elapsed ms.
  - **Browser-zone vs session-zone.** The arithmetic is browser-local throughout while the session
    has its own `canonicalTimezone`; the dialog only mitigates with hint text (`:167-172`,
    `:183-188`). Out of scope to fully fix here — note it as a follow-up rather than expanding this
    AC.
  **Spec note:** if `happy-dom` cannot pin `TZ`/`Intl`, assert on the elapsed-ms relationship
  directly (which is zone-independent) and record the trimming.

### AC8 — `ProfileBuilderStep3.vue` silent partial-pack discard + duration coercion (closes `deferred-work.md:1884-1890`)

**File:** `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue`. **Spec:** extend
`components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js`.

- **AC8.1 — a pack row the coach touched but that will not survive the filter is not silently
  dropped** (`submit()` at `:145-155`, `.filter((p) => p.sessionCount > 0 && p.totalPrice > 0)` at
  `:151`). `pack.sessionCount` / `pack.totalPrice` bind `v-model.number` (`:43`, `:52`) so a blank
  field is `null`. The filter also silently drops (story-review finding 10): `{5, 0}`, `{0, 20}`,
  `{-1, 20}`, and `{null, null, label: 'Starter'}` — the coach typed *something* in each and gets
  no message.
  **Fix:** define a "touched-but-invalid" row as *any field on the row is non-empty (`sessionCount`
  or `totalPrice` non-`null`, **or** a non-blank `label`) yet the row would not pass
  `sessionCount > 0 && totalPrice > 0`*. Before the `.filter`, if any such row exists, **block
  `submit()`** with a validation message — the pack q-inputs at `:42-48` / `:51-58` carry **no
  `:rules`** today (unlike `perSessionPrice` at `:9-10`), so add `:rules` for shape or gate on a
  computed `packsValid`. A fully-empty row (`{null, null, label: ''}`) stays filtered without
  complaint.
  **Mutation check:** remove the touched-but-invalid check → the new cases (`{5, null}`, `{5, 0}`,
  `{null, null, label: 'Starter'}` → submit blocked / message shown) fail (submit emits with the
  rows silently absent).

- **AC8.2 — defensive `Number()` coercion in `durationOptions` (hardening only — no live trigger at
  HEAD)** (`:122-135`, `DURATION_CHOICES.includes(current)` at `:131`).
  **Story-review finding 11: the "string from hydration" premise is unreachable at HEAD** —
  `ProfileBuilderStep3.vue` owns its `form` (`:104-112`, takes only a `loading` prop), its sole
  consumer is `CoachProfileBuilderPlaceholderPage.vue:64`, and the code comment at `:117-118` says
  "create-only today, `form.sessionDurationMinutes` always starts `null`". So `'60'` cannot arise
  today. Keep this as **explicit defensive hardening only**, matching the `deferred-63` AC7 framing
  already in the file: `includes` is strict-equality, so if a future hydration path ever set a
  string it would append a duplicate synthetic option.
  **Fix:** coerce `current` with `Number()` before the `includes` check at `:131`. **Do not** claim
  it "submits the number" — that would require also normalising `form.sessionDurationMinutes`
  itself, which is a bigger change with no trigger; leave it.
  **Mutation check:** revert the `Number()` coercion → the new "a synthetic-option list built from a
  string `'60'` does not contain a duplicate `60`" unit assertion (call `durationOptions` with the
  form field pre-set to `'60'`) fails.

### AC9 — `BookingStateChip.vue` prop type (closes `deferred-work.md:1935-1946`)

**File:** `src/frontend/src/components/booking/BookingStateChip.vue` (`:10-13`). **Spec:** update
`pages/parent/__tests__/ParentBookingsPageSpec.js`.

- `bookingId: { type: String, default: null }` (`:12`) — but the callers pass a **numeric** id.
  Two call sites (story-review finding 15):
  - `ParentBookingsPage.vue:134` — `:booking-id="booking.id"` straight from the API payload (`id` is
    a number). The `deferred-108` spec covers this one.
  - `CoachCommandCenterPage.vue:95` — `:booking-id="booking.bookingId"` (unanalysed at creation;
    `grep` its payload type during implementation — likely also numeric). `src/pages/coach/__tests__/`
    does not exist, so the spec assertion below covers `ParentBookingsPage` only; scope the "no
    warning" claim accordingly or add a coach-side check.
  Vue logs `Invalid prop: type check failed for prop "bookingId". Expected String … got Number` on
  **every render**, and `useBookingSse(props.bookingId)` (`:16-18`) receives the unconverted value.
  **Fix (one line):** widen to `bookingId: { type: [String, Number], default: null }`.
  `useBookingSse` only interpolates the id into a URL (`booking.store.js:66`,
  `new EventSource("/api/bookings/<id>/events")` — no key lookup / string compare in `:53-100`), so
  a number already works — no companion change. Verify no `BookingStateChip` consumer relies on a
  string-typed `bookingId` (`grep`).
  **Spec:** `deferred-108`'s `ParentBookingsPageSpec.js` deliberately left its fixture numeric to
  reproduce this warning (`deferred-108` CR reclassified-patch note). After the fix, the warning is
  gone — update that spec's NOTE and add an assertion that mounting `ParentBookingsPage` with the
  numeric-id fixture emits **no** `Invalid prop` warning (spy on `console.warn` / Vue's warn
  handler).
  **Mutation check:** revert to `type: String` → the "no prop-type warning on render" assertion
  fails.

### AC10 — `ConfigBounds` completeness: bound `semiPro`/`pro`, fix the drift guard, + file the real entitlement gap (RE-SCOPED — `deferred-108` CR decision 2b, `deferred-work.md:1913-1934` + `:1947-1953`)

> **Story-review finding 3 (blocker) — the premise was wrong on three counts, verified at HEAD:**
> (a) **Nothing reads `video.quota.semiPro.*` / `.pro.*`.** `QuotaConfigService.resolveTierKey`
> (the *only* producer of the tier segment) has a `switch` over `CoachSubscriptionTier {SCOUT,
> INSTRUCTOR, ACADEMY}` and a `catch` returning `"athlete"` for every non-UUID (player) ownerId —
> it can return **only** `scout`/`instructor`/`academy`/`athlete`, never `semiPro`/`pro`.
> `PlayerSubscriptionTierBilling` is referenced **nowhere** except its own declaration
> (`SubscriptionService` uses the string literals `"SEMI_PRO"` / `"PRO"`). The four `V53` rows
> (ids 121/122/127/128) are **dead configuration** today.
> (b) **There is no fail-fast.** The loop generates every tier bound as
> `new BoundedKey(…, 0L, Long.MAX_VALUE, **false**)` — `failFast = false`. Per
> `ConfigStartupAssertion` an out-of-range non-`failFast` key logs an **ERROR** and increments
> `config.value.misconfigured`, and **boots normally**. No `throw`.
> (c) **The live defect the AC walked past:** because `resolveTierKey` falls through to `"athlete"`
> for every player, a `SEMI_PRO` / `PRO` player receives the **ATHLETE** quota (2 GiB / 10 GiB)
> instead of the 4 GiB / 25 GiB and 7 GiB / 30 GiB `V53:37-38,44-45` seeds for them. That is a real
> entitlement mismatch in shipped code; "complete the hand-list" bounds the keys around the hole
> without closing it.

- **AC10.1 — bound the four seeded rows (future-proofing, not a fail-fast fix).** Add
  `"semiPro", "pro"` to `VIDEO_QUOTA_TIER_SEGMENTS` (**HEAD `:204`**,
  `List.of("scout","instructor","academy","athlete")`). The existing loop in the `ConfigBounds.ALL`
  static initializer (`for (String tier : VIDEO_QUOTA_TIER_SEGMENTS)` at `~:263-270`) auto-generates
  `video.quota.semiPro.storageBytes` / `.bandwidthBytesMonthly` and the `pro` pair at the same
  `(0L, Long.MAX_VALUE, false)` bound as every other tier. **One-line list edit**, not an enum
  iteration, so `deferred-108` AC9's decoupling decision holds. **Rationale (corrected):** these
  four rows are *currently seeded-but-unread*; bounding them now means a future `resolveTierKey`
  that honours `PlayerSubscriptionTierBilling` inherits a range check + an ERROR-on-misconfig
  signal, rather than the local developer who hand-edits one for a test getting nothing. **Not**
  "an operator setting `-1` gets no fail-fast" — no code path reads the value.
- **AC10.2 — fix `ConfigBoundsEnumCoverageTest` segment derivation** (`:32`). The
  `everyCoachSubscriptionTierHasStorageAndBandwidthBounds` test derives its expected segment via
  `tier.name().toLowerCase(Locale.ROOT)`. Every current `CoachSubscriptionTier` constant is
  single-word so this coincides with the camelCase DB key — but every **multi-word** key already in
  the DB is camelCase (`video.quota.semiPro.*`, `video.drillDemo.*`) and
  `VideoTypeConstraints.configKey` camel-cases by hand (the sibling test at `:50-55` uses a manual
  `switch`). A future `PRO_ACADEMY` would make this test demand `video.quota.pro_academy.*` while
  the runtime key is `video.quota.proAcademy.*` — build green, bound ≠ key.
  **Fix:** derive the expected segment with the same camelCase convention the runtime uses
  (`SCREAMING_SNAKE → camelCase` helper, or a manual `switch` mirroring the `VideoType` test).
- **AC10.3 — assert the completed hand-list *without* making the dead enum a test dependency**
  (story-review: cross-referencing `PlayerSubscriptionTierBilling.values()` would make that enum's
  only usage a test-side drift guard and staleifies the `:40-43` comment that says "the player
  fallback tier … is not an enum constant"). Instead: hard-code `semiPro` / `pro` assertions next
  to the existing `athlete` block, with a comment naming `PlayerSubscriptionTierBilling` **and**
  noting that `resolveTierKey` does not yet map to them (so the next reader knows the bound is
  ahead of the runtime).
- **AC10.4 — correct the `ConfigBounds.java` comment (drop the "third enum dimension" framing).**
  The templated-key comment (**HEAD `:198-205`**, added by `deferred-108` AC9) says the guard's only
  blind spot is a **removed** constant. Reword to say instead: *the guard verifies every
  `CoachSubscriptionTier` / `VideoType` constant has a bound, but cannot verify the hand-list is
  **complete** — a seeded key whose segment is not in the list (as `semiPro`/`pro` were until
  `deferred-109`) is simply unbounded, and a removed constant leaves a harmless stale entry.* There
  is **no** third enum in the *runtime key derivation* — there is an unimplemented tier mapping
  (see AC10.6); do not imply otherwise.
- **AC10.5 — retag the ledger.** `deferred-work.md:1913-1934` (decision 2b) → closed by this AC;
  `:1947-1953` (`ConfigBoundsEnumCoverageTest` camelCase) → closed. (AC15 does the edit.)
- **AC10.6 — file the `resolveTierKey` player-tier entitlement gap as a NEW ledger bullet.** Under
  a new `## Deferred from: skillars-deferred-109 story creation (2026-09-11)` section (AC15 adds it):
  *`QuotaConfigService.resolveTierKey` returns `"athlete"` for every player (non-UUID ownerId), so
  a `SEMI_PRO` / `PRO` player gets the `athlete` video quota (2 GiB storage / 10 GiB bandwidth)
  regardless of their billing tier — the `video.quota.semiPro.*` / `.pro.*` rows `V53` seeds
  (4 GiB / 25 GiB; 7 GiB / 30 GiB) are unreachable. `PlayerSubscriptionTierBilling` is a dead enum
  (`SubscriptionService` uses `"SEMI_PRO"` / `"PRO"` string literals). Closing this needs a player
  billing-tier → quota-segment mapping in `resolveTierKey` (and a decision on whether player video
  quotas are a shipped product concern yet). `deferred-109` AC10 bounded the four keys ahead of
  this so the mapping inherits a range check.* This keeps the story's "genuine one-off bugs class is
  exhausted" claim honest.
- **Test:** `ConfigBoundsEnumCoverageTest` green (existing 5 + the new `semiPro`/`pro` + camelCase
  cases). For `ConfigStartupAssertion`: add/extend a test asserting an out-of-range
  `video.quota.pro.storageBytes` now produces an **ERROR log + a `config.value.misconfigured`
  metric increment** (it was silently absent from `ALL` before) — **not** a boot refusal (these
  keys are `failFast = false`). Targeted classes only; GitHub CI is the gate
  (`docs/validation-strategy.md`).

### AC11 — bound the remaining `image_runtime_uid` daemon round-trips (RE-SCOPED — closes `deferred-work.md:1954-1960`)

`deferred-108` AC8 wrapped only `docker pull` and the `docker run … id -u` probe. Cites verified
exact by the story-review: `restore-from-volume-backup.sh:81/87/89/152/158/208`,
`provision.sh:150/156/158`.

**In scope — the two `docker image inspect` calls only:**

- `restore-…:87` (bare `docker image inspect "$img" >/dev/null 2>&1`, *before* the wrapped
  `docker pull` — so a wedged daemon hangs **here first**) and `:89`
  (`user=$(docker image inspect --format '{{.Config.User}}' "$img" 2>/dev/null)`); the identical
  pair in `provision.sh:156` / `:158`. These are genuine daemon round-trips a wedged `dockerd`
  hangs indefinitely.
- **Fix:** wrap each with `run_bounded` at a short `readonly IMAGE_INSPECT_TIMEOUT=10` (local
  metadata read; `run_bounded`'s `command -v timeout` degradation + `-k` already cover a missing
  `timeout(1)`).
- **Move the redirections onto the inner `docker` command (story-review finding 7c).** The existing
  `>/dev/null 2>&1` / `2>/dev/null` swallow fd2 — wrapping the whole thing buries `run_bounded`'s
  own timeout WARN (`log … >&2`, `restore-…:67`) inside that redirection and defeats the
  "wedged daemon vs normal probe failure" signal AC11 exists for. Rewrite so the redirection binds
  the inner `docker` invocation and `run_bounded`'s stderr survives.
- **Give `run_bounded` an explicit label (finding 7d).** It sets `what="$1"` *after* `shift`, so
  every wrapped call is labelled `'docker'`. Change the signature to
  `run_bounded <dur> <label> <cmd…>` and update the **two existing `deferred-108` AC8 call sites**
  (`docker pull`, `docker run … id -u`) to pass a label — in scope, AC11 is the AC8 follow-up.

**Deliberately NOT bounded (state each in the aggregate-bound comment so AC11 is not read as
"everything in the window is bounded"):**

- **ERR-trap `${DC} up -d` (`restore-…:152`)** — finding 7a: under `set -euo pipefail` a `timeout`
  exit 124 *inside the ERR trap* exits the script with the stack half-started (the opposite of the
  trap's purpose), and `timeout` kills the compose CLI, not the daemon's orchestration. **Leave
  unbounded.** The final `${DC} up -d` (`:208`) is left unbounded too for symmetry / simplicity —
  note the ERR trap is its recovery.
- **`aws s3 cp` (`restore-…:158`)** — finding 7e: `--cli-read-timeout` / `--cli-connect-timeout`
  bound *per-request* stalls only and interact with the CLI's retry/part counts; they do not bound
  total wall time, and a hard `timeout` wall-clock cap on a DR-archive download risks killing a
  slow-but-progressing restore. **Leave unbounded**, with a comment saying why (do **not** add
  `--cli-*-timeout` and call it "bounded").
- **`${DC} config --format json` (`restore-…:83` / `provision.sh:152`, the *first* call in
  `image_runtime_uid`) and `${DC} down` (`restore-…:145`)** — finding 7b. `${DC} config` already has
  `2>/dev/null || return 0` and is a compose-file parse, not a pull; bounding `${DC} down` carries
  the same mid-teardown-timeout hazard as the ERR trap. **Out of scope**, noted in the comment.

**Discipline & tests:**

- Asymmetric-timeout comment (`deferred-108` AC8): `provision.sh` (`IMAGE_PULL_TIMEOUT=300`,
  pre-stack) and `restore-…` (`=30`, in the outage window) already carry a "must not be copied
  between files" comment — extend it to `IMAGE_INSPECT_TIMEOUT`. Update the aggregate-bound comment
  in each script (`restore-…:30-50`, `provision.sh:79-112`) to state precisely which calls are now
  bounded and which are deliberately not. Cite `skillars-deferred-109 AC11`.
- `bash -n` + `shellcheck -S warning` clean on both scripts. No shell harness — Dev Agent Record
  documents the manual reasoning + a `docker compose config` sanity check + (if feasible) a
  stub-`timeout` behavioural check of the relabelled `run_bounded`, per `deferred-108` AC8.

### AC12 — `VideoModerationEmailListener` outbox-mapping IT + `persisted == null` tighten (re-scoped `deferred-94` AC15/AC16 — `deferred-work.md:1238-1239`)

**Re-scoped per owner decision D5 — the blank-recipient "silent drop" the bullets describe is
already handled at HEAD** (`@PostConstruct` startup ERROR + `ARACHNID_ENABLED` boot-abort +
per-send `log.error` + a **deliberate documented decision** at
`VideoModerationEmailListener.java:86-88` not to retain the outbox row for an unset config key). No
change to that path.

**File:** `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
+ `src/test/.../VideoModerationEmailListenerTest.java` + a new / extended IT.

- **AC12.1 — split the `persisted == null` and `persisted == SENT` cases (story-review finding 2 —
  blocker).** `sendAdminAlertSync` (`:94-108`) reads back the envelope, then falls through to a
  single `log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered …")` at `:107` for **two** cases:
  `persisted == null` **and** `persisted != null && status != FAILED` — the latter being a
  **genuinely successful** send (`MailManager.sendEmailSync` always persists an `EnvelopeEntity`,
  `SENT` on success — `MailManager.java:105,130-132,110-118`). Rewriting that one statement to
  `WARN` (as first drafted) would mislabel **every successful admin alert** as "not yet visible".
  **Fix — explicit branch split:**
  ```java
  if (persisted == null) {
      log.warn("[VIDEO_MODERATION_ADMIN_ALERT] send outcome not yet visible for videoId={} sendId={} "
          + "— envelope row not found on read-back", event.videoId(), envelope.sendId());
      return;
  }
  log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered alert for videoId={} subject={}",
      event.videoId(), event.subject());
  ```
  **Spec — two assertions (the pair is the mutation-sensitivity):**
  - `VideoModerationEmailListenerTest.noPersistedEnvelope_doesNotThrow` (`findBySendId` → `null`):
    flip from asserting `INFO "[VIDEO_MODERATION_ADMIN_ALERT]"` to asserting the new `WARN`.
  - **New:** a `SENT` envelope (`findBySendId` → non-null, `status = SENT`) still logs
    `INFO "[VIDEO_MODERATION_ADMIN_ALERT] delivered"` and does **not** WARN. Without this, collapsing
    the branch back to one statement ships green.
  **Mutation check:** merge the two branches into one `log.warn` → the `SENT`-case assertion fails;
  merge into one `log.info` → the `null`-case assertion fails.
- **AC12.2 — the AC16 gap: exercise the real `Exception → EnvelopeEntity(FAILED, isRetry)` mapping
  (story-review finding 13 — seam corrected).** Nothing today drives, through real code, that a
  retryable send failure stamps `FAILED, isRetry = true` and `sendAdminAlertSync` **rethrows**,
  vs. a permanent failure stamps `FAILED, isRetry = false` and it logs `UNDELIVERABLE` + returns.
  `VideoModerationEmailListenerTest` is fully mocked; `NotificationEmailOutboxAtomicityIT` uses
  `TestMailManager` (no `FAILED` row).
  - **Seam:** `@MockitoBean JavaMailSender` will **not** work — `MailService.java:41` gets its sender
    from `senderProvider.nextSender()` and `MailSenderProvider` builds `List<JavaMailSenderImpl>`
    with `new`. Mock at **`SenderProvider`** (`SenderProvider.java`) or stub `MailService` — a real
    `MailManager` over that seam.
  - **Assert on the `EnvelopeEntity` row** (`FAILED` + `isRetry`), and on `sendAdminAlertSync`'s
    behaviour (rethrow `IllegalStateException` for retryable per `:96-99`; return + `UNDELIVERABLE`
    log for permanent). Driving `sendAdminAlertSync` directly does **not** create an *outbox* row —
    that lives with `ModerationAdminAlertOutboxHandler` (`:57-72`) + its drain; if the story wants
    the retain-vs-delete-of-the-outbox-row property covered end to end, drive the handler through
    `ModerationOutboxIT` (it exists). State which of the two the IT covers.
  - **Circuit-breaker / retry state:** `MailManager.sendEmailSync` nests `retryTemplate.execute`
    inside `circuitBreaker.run` on breaker `"emailService"`; the retryable case burns its retry
    budget and both cases share breaker state in one context. **Reset the breaker between cases**
    (or use distinct contexts) and expect the retryable case to take longer.
  - **Verified achievable:** `isRetryable` (`MailManager.java:137-151`) defaults `true` unless a
    `NON_REPAIRABLE_ERRORS` type appears at a bounded depth, so the transient/permanent split is
    reachable; `sendEmailSync` is `@Transactional(REQUIRES_NEW)` so the `FAILED` row is committed
    and visible to the `findBySendId` read-back.
- **AC12.3 — retag the ledger.** `deferred-work.md:1238` and `:1239` → `[DECIDED 2026-09-11
  (skillars-deferred-109 AC12)]` for the blank-recipient behaviour (points at the current hardened
  code — startup ERROR + boot-abort + documented no-retain decision), plus a note that the
  retryable/permanent mapping is now IT-covered. (AC15 does the mechanical edit.)
- **Test:** `VideoModerationEmailListenerTest` green (flipped `null`-case + new `SENT`-case
  assertions); the new IT green. Targeted run only.

### AC13 — `frontend-unit-tests.yml`: one-step label trigger (owner decision D3 — `deferred-work.md:1904-1912`)

**File:** `.github/workflows/frontend-unit-tests.yml`.

- Add `opened` to the `pull_request: types:` list (**HEAD `:22`** — currently
  `types: [labeled, synchronize, reopened]`) so a PR created with `gh pr create --label
  frontend-tests` fires the job in **one** step. The job-level `if` (**`:29-31`** —
  `contains(github.event.pull_request.labels.*.name, 'frontend-tests')`) still gates execution on
  the label, so this is **ergonomics only**: the job stays opt-in and **non-gating**.
- Update the header comment (the trigger-mechanics paragraph is at **`:14-16`**, not `:16-18`) to
  mention `opened`.
- **Owner decision D2 / D6 stands:** not added to `ci.yml` / `pr-build.yml` / `pom.xml`, not a
  required check. The `deferred-work.md:1891` "specs are not merge-gating" coupling note is left
  as-is (B2).
- **Acceptance (story-review finding 19 — the "own PR is the proof" claim was self-contradicting
  with Task 19).** For `pull_request` events GitHub evaluates the workflow file from the **base**
  branch / merge ref, not the PR head — so **this change does not take effect for its own PR**. The
  acceptance for AC13 is therefore: (a) the YAML is valid and `opened` is in the list (local check
  + `actionlint` if available); (b) the **next** labelled PR after this one merges fires
  `frontend-unit` on `opened` in one step — note this as a deferred verification in the Dev Agent
  Record. This story's own PR still adds the label post-`gh pr create` (fires `labeled`) as
  `deferred-108` did.
- *Adjacent, not fixed here:* `unlabeled` is still absent from `types:`, so removing the label
  leaves the last green result standing as the visible check state — note only, out of scope.
- Retag `deferred-work.md:1904-1912` closed (AC15).

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
  to an explicit `[DECIDED 2026-09-11 (skillars-deferred-109 AC14): accepted. No grace path or
  config-gated allowance will be built. Mitigation is operational — see the runbook pre-production
  release gate. Revisit only if a production deploy is planned with queued legacy events that cannot
  be drained.]`. (AC15 does the mechanical edit.)
- **AC14.2 — a NEW sibling `##` section in the runbook (story-review finding 14 — the existing gate
  section is topic-scoped).** `docs/deployment/runbook.md:579` is
  `## Pre-production release gate: outstanding migration rewrites`, whose body says "**all four items
  below must be closed**" over a four-row migration table, verified by `MigrationConventionLintTest`.
  A webhook-drain item is **not** a migration rewrite — adding it as a row would falsify the "four
  items" sentence and the verification method. **Add a separate `## Pre-production release gate:
  queued webhook events`** section, with its own `**Owner:** whoever prepares the first production
  deploy. **Trigger:** before that deploy, not after.` line, then a short paragraph: drain or
  discard any queued / replayed `encoding.success` (and sibling `encoding.*`) webhook events before
  cutover — afterwards they drive `PROCESSING→READY` on the plain lifecycle path
  (`VideoLifecycleService.java:77-83`), which dead-letters the event and increments
  `video.moderation.bypass` (a false moderation-bypass alarm). Note
  `VideoLifecycleService.reconcileToReady()` is the only legitimate `PROCESSING→READY` path
  post-cutover. Correct the cite: the gate section starts at **`:579`**, not `:581`.
- **Test:** doc + ledger only. No code, no test harness.

### AC15 — Ledger hygiene + reconstruction check

Per `deferred-work.md`'s own delete-outright-when-closed convention and the reconstruction-check
discipline every prior audit block follows. **Baselines (measured at `bbad7938`, story-review
finding 12 — the creation draft's "1959 / 31 / 33" were all wrong; they were copied from the
`deferred-108` audit block's own mis-recorded intermediate figures):**

| figure | value at `bbad7938` |
|---|---|
| `wc -l deferred-work.md` | **1991** |
| `grep -c '^## Deferred from:'` | **87** |
| `grep -o '\[DECIDED' \| wc -l` (whole file) | **35** |
| `grep -o '\[DISMISSED' \| wc -l` (whole file) | **35** |

**Re-run all four before writing the audit block** — do not trust these numbers at implementation
time either.

- **Delete outright** (genuine closures shipped by this story), all inside
  `## Deferred from: code review of skillars-deferred-108 (2026-09-10)` unless noted, leaving every
  `[DECIDED]` / `[DISMISSED]` bullet untouched:
  - `PaymentMethodCard.vue:174-187` **and** `:178-181` (both closed by **AC1.1**); `:23-32` (**AC1.3**);
    `:189-214` (**AC1.4**). *(The `:202-206` sibling-dead-catch is AC1.2 — a story-review add, not a
    ledger bullet, nothing to delete.)*
  - `sessionManager.js:140` (**AC2.1**); `:259-264` (**AC2.2**).
  - `MainLayout.vue:335-341` (**AC3.1**); `:305,314` (**AC3.2**); `:353-363` (**AC3.3**).
  - `playerStore.js:32-54` (**AC4.2**); **`deferred-work.md:1695`** — the `fetchSelfPlayerId`
    return-value residual, in the `## Deferred from: skillars-deferred-108 story implementation`
    section (**AC4.1**).
  - `booking.store.js:605-630` (**AC5.1**); `:394-395` (**AC5.2**).
  - `BookingRequestPage.vue:457-462` (**AC6**); `ParentBookingsPage.vue:252-266` (**AC7** — closed
    by pinning the behaviour + the display fix; if the section convention prefers a retag over a
    delete here, record which); `ProfileBuilderStep3.vue` (**AC8**); `BookingStateChip.vue:12`
    (**AC9**).
  - `ConfigBoundsEnumCoverageTest.java:32` (**AC10.2**); the `provision.sh:104,106` /
    `restore-from-volume-backup.sh:…` remaining-unbounded-calls bullet at `:1954-1960` (**AC11** —
    but only the `docker image inspect` sub-claim is fully closed; if the bullet also names
    `aws s3 cp` / `${DC} up -d`, **retag** rather than delete, noting those are deliberately left
    unbounded per AC11).
  - **`deferred-work.md:1904-1912`** — the `frontend-unit-tests.yml` `opened`-trigger bullet
    (**AC13**). *(The sibling `:1891` "specs are not merge-gating" bullet **stays** — B2.)*
- **Retag `[DECIDED 2026-09-11 (skillars-deferred-109 …)]`** (not deleted — these are decisions, not
  closures):
  - `deferred-work.md:1913-1934` (`ConfigBounds` incomplete hand-list, decision 2b) → hand-list now
    complete, drift guard fixed, **and** the real entitlement gap filed separately (AC10.6). Keep a
    one-line bullet pointing at the new section.
  - `deferred-work.md:1238-1239` (`VideoModerationEmailListener` blank-recipient) → documented owner
    decision; retryable/permanent mapping now IT-covered.
  - `deferred-work.md:1311` (legacy `PROCESSING→READY` webhook cutover) → accepted risk; runbook
    mitigation (AC14).
- **Add a new section** `## Deferred from: skillars-deferred-109 story creation (2026-09-11)` with
  **one** bullet — the `QuotaConfigService.resolveTierKey` player-tier entitlement gap (AC10.6 has
  the full text).
- **Leave untouched:** `deferred-work.md:1891` (B2); `skillars-7-1` D4; every `[DECIDED]` /
  `[DISMISSED]` bullet.
- **Reconstruction check:** every surviving non-blank line matches the pre-edit file
  (`bbad7938` + this story's implementation commits), in order, nothing reworded/reordered apart
  from the retags / deletions / the new section enumerated above. Record in the audit block:
  pre-edit / post-delete / post-append line counts (fresh `wc -l` each time); header count
  before/after (the `deferred-108` CR section's header **stays** — `:1891` remains; the
  `deferred-108` story-implementation section's header **stays** — other bullets remain);
  `[DECIDED]` delta (**+5** retags — corrected by code review from the **+3** first written here:
  `:1238-1239` is two bullets and AC11 contributes a conditional fifth, so the implementation's 5 was
  right and this figure was wrong + note the new-section bullet is untagged open work, not a
  `[DECIDED]`); `[DISMISSED]` unchanged; `[PICKED UP by …]` touched (the two `deferred-94`
  AC15/AC16 bullets — retagged, not deleted).
- **Test:** the reconstruction check is the gate; no code.

---

## Tasks / Subtasks

- [x] 1. **Re-diff every cited line against HEAD** (`git show HEAD:<path>` / open each file) before writing
   any code — the file's convention and this story's own header both demand it. Note drift in the
   Dev Agent Record.
- [x] 2. **AC1** — `PaymentMethodCard.vue` (+ read `payment.store.js`): AC1.1 (retry + stale-key, one
   after-`await` error check — do **not** also move `stripeUnavailable=false` or null `stripeConfig`),
   AC1.2 (post-save refresh-failure notice), AC1.3 (spec-only, key exists), AC1.4 (save-path
   branches). Extend `PaymentMethodCardSpec.js` (+ `paymentStoreSpec.js` where the store half is
   asserted). Each fix mutation-verified (RED reverted, GREEN restored).
- [x] 3. **AC2** — `sessionManager.js`: skew-check spec (spec-only) + ineffective-refresh fix; extend
   `sessionManagerCoverageSpec.js`.
- [x] 4. **AC3** — `MainLayout.vue`: logout parity + `localStorage` guards + silent-404 spec; extend
   `MainLayoutSpec.js`.
- [x] 5. **AC4** — `playerStore.js`: one-line return-value fix (flip the `deferred-108` characterization
   test) + reject-path spec; extend `playerStoreSpec.js`.
- [x] 6. **AC5** — `booking.store.js`: accept-all `null`-leak fix + null-response guard; extend
   `bookingStoreSpec.js`.
- [x] 7. **AC6** — `BookingRequestPage.vue` `slotRows` NaN filter; extend `BookingRequestPageSpec.js`.
- [x] 8. **AC7** — `ParentBookingsPage.vue`: **do NOT change the arithmetic** (`RescheduleService.java:176-184`
   requires the fixed-instant delta). Add a spec pinning the submitted pair's elapsed `Duration`
   equals the original + a code comment; pin/handle the DST-boundary *display* (fall-back
   ambiguity). Extend `ParentBookingsPageSpec.js`.
- [x] 9. **AC8** — `ProfileBuilderStep3.vue`: AC8.1 touched-but-invalid pack-row guard (a single `packError` message below the pack list — **no per-field `:rules` were added**; the original wording here claimed them, corrected by code review); AC8.2
   defensive `Number()` coercion in `durationOptions` only (no "submits the number" claim). Extend
   `ProfileBuilderStep3Spec.js`.
- [x] 10. **AC9** — `BookingStateChip.vue` `bookingId: [String, Number]`; `grep`
    `CoachCommandCenterPage.vue:95`'s payload type; update `ParentBookingsPageSpec.js` NOTE +
    no-warning assertion.
- [x] 11. **Frontend green gate:** `cd src/frontend && npm run test:unit` + `npm run test:unit:ci` green;
    `eslint` + `prettier --check` clean on every touched spec.
- [x] 12. **AC10** — `ConfigBounds.java` add `semiPro`/`pro` to `VIDEO_QUOTA_TIER_SEGMENTS` + reword the
    comment (drop "third enum dimension"); `ConfigBoundsEnumCoverageTest.java` camelCase derivation
    + hard-coded `semiPro`/`pro` assertions; `ConfigStartupAssertion` test — bad
    `video.quota.pro.storageBytes` → **ERROR log + `config.value.misconfigured` metric** (not a boot
    refusal); AC10.6 the new `deferred-work.md` bullet for the `resolveTierKey` player-tier gap.
    Targeted class run.
- [x] 13. **AC11** — wrap the two `docker image inspect` calls in both scripts with a relabelled
    `run_bounded` (`IMAGE_INSPECT_TIMEOUT`, redirections on the inner `docker` cmd); relabel the two
    existing `deferred-108` AC8 call sites; leave `aws s3 cp` / ERR-trap `${DC} up -d` / `${DC} config`
    / `${DC} down` **unbounded** with reasons in the comment; update the aggregate-bound comments.
    `bash -n` + `shellcheck -S warning` clean.
- [x] 14. **AC12** — `VideoModerationEmailListener` explicit `null`/`SENT` branch split (`null`→WARN);
    flip `noPersistedEnvelope_doesNotThrow` + add the `SENT`-case assertion; new outbox-mapping IT
    at the `SenderProvider`/`MailService` seam (assert on the envelope row; reset the `emailService`
    breaker between cases). Targeted run.
- [x] 15. **AC13** — `frontend-unit-tests.yml` add `opened` to `types:` (`:22`) + update the `:14-16`
    comment. `actionlint` if available. (Effect verified on the *next* labelled PR, not this one.)
- [x] 16. **AC14** — `deferred-work.md:1311` retag `[DECIDED … AC14]` + a **new** `## Pre-production
    release gate: queued webhook events` section in `docs/deployment/runbook.md` (sibling of the
    migration-rewrites one).
- [x] 17. **AC15** — re-run `wc -l` / the three `grep` counts; ledger hygiene pass (delete/retag +
    new `deferred-109` section) + reconstruction check.
- [x] 18. **Validation summary** in the Dev Agent Record: per-AC mutation checks (RED/GREEN), frontend
    suite counts, targeted backend class results, `bash -n`/`shellcheck` results, no full
    `mvn verify` (GitHub CI is the gate — `docs/validation-strategy.md`,
    [[feedback_no_local_mvn_verify]]).
- [ ] 19. **PR:** `gh pr create` against `master`, then `gh pr edit <n> --add-label frontend-tests`
    (fires `labeled` — AC13's `opened` change does **not** take effect for its own PR, since
    `pull_request` triggers are read from the base ref). Confirm the `frontend-unit` check actually
    **ran** on this PR (not green-because-skipped). Record in the Dev Agent Record that the
    one-step `opened` behaviour is to be verified on the next labelled PR.

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
| `ParentBookingsPage.vue` | `rescheduleProposedEnd` = fixed-instant delta (**correct** — `RescheduleService.java:176-184` requires it) | AC7: **spec + comment pinning the arithmetic**; handle the DST fall-back *display* ambiguity — no arithmetic change | the fixed-instant delta; the `datetime-local` binding; the submit payload `.toISOString()` shape |
| `ProfileBuilderStep3.vue` | `submit()` silently filters partial pack rows; `durationOptions.includes` strict-eq | AC8.1 partial-row guard; AC8.2 `Number()` coercion | the `deferred-63` AC7 defensive synthetic-option behaviour; all-`null` rows stay silently filtered |
| `BookingStateChip.vue` | `bookingId: { type: String }` | AC9: `[String, Number]` | `useBookingSse` gating on `bookingId` + non-terminal status |
| `ConfigBounds.java` | `VIDEO_QUOTA_TIER_SEGMENTS` missing `semiPro`/`pro`; comment names only "removed constant" blind spot | AC10.1 list edit; AC10.4 comment (drop "third enum dimension") | the module-decoupling decision (no enum imports); the `athlete` fallback entry; every other `BoundedKey` |
| `ConfigBoundsEnumCoverageTest.java` | `:32` derives via `toLowerCase`; no `semiPro`/`pro` assertions | AC10.2 camelCase derivation; AC10.3 hard-coded `semiPro`/`pro` assertions (NOT a `PlayerSubscriptionTierBilling.values()` cross-ref) | the `VideoType` `switch` pattern (already correct); the internal-consistency / no-dup cases; the `:40-43` "player fallback … not an enum constant" comment intent |
| `QuotaConfigService` / `deferred-work.md` | `resolveTierKey` returns `"athlete"` for every player → `SEMI_PRO`/`PRO` players get the athlete quota | **not changed** — AC10.6 files it as a new ledger bullet only | — |
| `VideoModerationEmailListener.java` | `persisted == null` **and** `persisted == SENT` both fall through to one `INFO "delivered"` | AC12.1 **explicit branch split** — `null`→WARN+return, else INFO | the `@PostConstruct` check; the `isRetry()` throw / permanent-release split; the documented no-retain decision (`:86-88`) |
| `frontend-unit-tests.yml` | `types: [labeled, synchronize, reopened]` | AC13: add `opened` + comment | the job-level label `if` gate; the decoupled-from-build-gate design |
| `docs/deployment/runbook.md` | one topic-scoped `## Pre-production release gate: outstanding migration rewrites` (`:579`) | AC14.2: a **new sibling `##` section** `## Pre-production release gate: queued webhook events` | the migration-rewrites section's "four items" / `MigrationConventionLintTest` framing (do not add a row to it) |
| `deferred-work.md` | post-`deferred-108` state, **1991 lines**, 87 `## Deferred from:` headers, `[DECIDED]` **35** / `[DISMISSED]` **35** (whole-file `grep -o` counts) | AC15: delete/retag closed bullets + new `deferred-109` section + audit block | `skillars-7-1` D4; the `:1891` B2 note; every `[DECIDED]` / `[DISMISSED]` bullet |

### What must NOT change

- `.github/workflows/ci.yml`, `pr-build.yml`, `pom.xml` — the Vitest job stays opt-in (D2/D6).
- The `deferred-108` reference specs (`SkillsRadarChartSpec.js`, `sessionManagerSpec.js`) and the
  `deferred-108` spec bodies — **extend**, never restyle.
- `VideoModerationEmailListener`'s blank-recipient path (D5 — documented owner decision).
- `VideoLifecycleService` — AC14 is doc + ledger only (D4).
- `ParentBookingsPage.vue`'s `rescheduleProposedEnd` **arithmetic** — the fixed-instant delta is
  correct (`RescheduleService.java:176-184`); AC7 pins it, it does not change it.
- `VIDEO_TYPE_SEGMENTS` / any `BoundedKey` other than the four new `semiPro`/`pro` rows.
- `QuotaConfigService.resolveTierKey` — the player-tier mapping gap is filed (AC10.6), not fixed.
- `src/boot/theme.js` — its unguarded `localStorage` is out of scope for AC3.2 (noted there).
- Any `[DECIDED]` / `[DISMISSED]` ledger bullet.

### Risk note (story-review)

This is the **largest production-code change in the `deferred-10x` series** — nine `.vue`/store
files plus `payment.store.js` and `sessionManager.js`, which sit on the auth and payment critical
paths. Its only automated frontend validation is `frontend-unit-tests.yml`, which owner decisions
D2/D6 keep **opt-in and non-gating**, and which `mvn verify` never invokes. The safety net depends
on the `frontend-tests` label being present. **Mitigation for this story:** ensure the label is on
the PR before merge and confirm `frontend-unit` actually ran (green-because-skipped does not count);
the reviewer should treat the frontend diff with the scrutiny an ungated change warrants. Promoting
the job to a required check is B2 — explicitly out of scope (D6).

### Project-context rules that apply

`_bmad-output/project-context.md`: Vue `<script setup>` + Composition API; `async/await` not
`.then()` (new code); centralized `*.api.js`; Java records for DTOs; MapStruct for mappings;
targeted tests locally, GitHub CI is the full gate.

## Change Log

| Date | Change |
|---|---|
| 2026-09-10 | **Story created (→ ready-for-dev).** Drawn from `deferred-work.md` @ `bbad7938` (post-`deferred-108` #174 + prune #175). 15 ACs: AC1–AC9 frontend defect sweep (all 16 `deferred-108`-CR bullets + `playerStore.js` return-value leak, owner D1); AC10 `ConfigBounds` completeness (owner D2); AC11 bound the remaining disaster-restore image-probe calls; AC12 `VideoModerationEmailListener` re-scoped to test-only (owner D5); AC13 `frontend-unit-tests.yml` `opened` trigger, stays opt-in (owner D3/D6); AC14 legacy `PROCESSING→READY` webhook cutover — accepted risk, no code (owner D4); AC15 ledger hygiene + reconstruction check. |
| 2026-09-11 | **Implemented (→ review).** All 15 ACs done. Frontend: 10 `deferred-108` spec files extended (+13 assertions → 97 tests green), minimal local production fixes in `PaymentMethodCard.vue` / `sessionManager.js` / `MainLayout.vue` (+`useSession.js` export) / `playerStore.js` / `booking.store.js` / `BookingRequestPage.vue` / `ProfileBuilderStep3.vue` / `BookingStateChip.vue`, 2 new i18n keys ×3 bundles; `ParentBookingsPage.vue` comment-only (AC7 pins the fixed-instant delta, does not change it). Backend: `ConfigBounds.java` `semiPro`/`pro` segments + comment (AC10), `VideoModerationEmailListener.java` null/SENT branch split (AC12.1); `ConfigBoundsEnumCoverageTest` (6), `ConfigStartupAssertionTest` (11), `VideoModerationEmailListenerTest` incl. `@Nested` real-`MailManager` mapping test (8) — all green, targeted only. Deploy: `run_bounded` relabelled + both `docker image inspect` calls bounded in `provision.sh` + `restore-from-volume-backup.sh` (AC11); `aws s3 cp` / `${DC} up -d` / `${DC} config` / `${DC} down` left explicitly unbounded with reasons; `bash -n` + `shellcheck -S warning` clean. `frontend-unit-tests.yml` `opened` trigger (AC13, `actionlint` clean). `runbook.md` new pre-production webhook-drain gate + `deferred-work.md:1310` retag (AC14). Ledger hygiene (AC15): baselines 1991/87/35/35 at `bbad7938`; 15 bullets deleted + `:1695` residual deleted + 5 `[DECIDED … skillars-deferred-109]` retags + new `skillars-deferred-109 story creation` section (AC10.6 bullet) + audit block → 1936 lines / 88 headers / real `[DECIDED]` 40 / `[DISMISSED]` 35; B2 note + `skillars-7-1` D4 untouched; reconstruction check passed. Every frontend/backend fix mutation-verified in both directions. No `mvn verify` (GitHub CI is the gate — `docs/validation-strategy.md`). |
| 2026-09-11 | **Story-review audit applied — no false positives; all findings accepted.** **AC7** re-scoped: the "wall-clock minutes" fix was *backwards* (`RescheduleService.java:176-184` validates `Duration.between(Instant,Instant).equals(...)` — the current fixed-instant delta is required); now pins the correct behaviour + handles the DST-boundary *display* only. **AC12.1** now requires an explicit `null`/`SENT` branch split (rewriting the one `log.info` would mislabel every successful send). **AC10** premise corrected: nothing reads `video.quota.semiPro.*`/`.pro.*` (`resolveTierKey` never returns those segments), there is no boot refusal (keys are `failFast=false` — ERROR + `config.value.misconfigured` metric only); AC10.6 added — files the real `resolveTierKey` player-tier entitlement gap as a new ledger bullet. **AC1.1+AC1.2 merged** into one fix (option a — after-`await` error check; the "move `stripeUnavailable=false`" + "null `stripeConfig`" combo re-flickers); old AC1.2 slot reused for the sibling dead-catch (story-review finding 16). **AC2.2** conditioned on `hasSeenRintThisTab()` (naive "expiry absent → fail" breaks the legacy fallback), ordering pinned after `refreshExpiryState()`, future-ness predicate, legacy-path spec case added. **AC3.1** now requires *both* `useSession` `rint` clears + explicit statement of what ordering is unified vs left divergent. **AC3.2** mutation check corrected (`locale.value` runs before `setItem` — only the cookie-clear half is sensitive). **AC4.1** returns literal `null`, not `selfPlayerId.value` (which can hand a superseded caller a *different* account's id). **AC5.1** mandates option (a) — option (b) leaves a stale prior result rendering. **AC5.2** classifies a nullish response as a distinguishable error, not empty-success (bare `?? {}` blanks the coach's list + prunes all batch results + reports success). **AC8.1** predicate broadened (touched-but-invalid, incl. `0`/negative/label-only) + `:rules` needed. **AC8.2** re-scoped to defensive-only (no hydration path at HEAD). **AC9** notes the second call site `CoachCommandCenterPage.vue:95`. **AC11** re-scoped: drop the ERR-trap `${DC} up -d` bound (timeout mid-trap exits half-started), leave `aws s3 cp` / `${DC} config` / `${DC} down` explicitly unbounded with reasons, move redirections onto the inner `docker` cmd, relabel `run_bounded`. **AC12.2** seam corrected (`SenderProvider`/`MailService`, not `@MockitoBean JavaMailSender`); assert on the envelope row; circuit-breaker reset noted. **AC13** acceptance made coherent (change does not affect its own PR — verified on the next labelled PR). **AC14.2** now a new sibling `##` section, not a row in the migration-rewrites gate. **AC15** baselines corrected — `deferred-work.md` is **1991** lines / 87 headers / `[DECIDED]` 35 / `[DISMISSED]` 35 (was "1959 / 31 / 33", copied from the `deferred-108` block's own mis-recorded figures). Risk note added (largest prod-code change in the series; opt-in-only safety net). Cite drift corrected throughout — Task 1 stays load-bearing. |

## Dev Agent Record

### Context Reference

- `_bmad-output/implementation-artifacts/deferred-work.md` @ `bbad7938` — sections
  `## Deferred from: code review of skillars-deferred-108 (2026-09-10)` (`:1803-1960`),
  `:1695`, `:1238-1239`, `:1311`, `:1904-1912`, `:1913-1953`.
- `_bmad-output/implementation-artifacts/skillars-deferred-108-frontend-unit-spec-backfill-restore-pull-timeout-and-configbounds-templated-key-decision.md`
  — spec conventions + the 10 spec files this story extends.
- `docs/validation-strategy.md`, `docs/deployment/runbook.md` (pre-production release gate).

### Agent Model Used

Claude Sonnet 5 (`claude-sonnet-5`), `/bmad-dev-story` workflow.

### Implementation Plan

Executed AC1→AC15 in order (Tasks 1–19). Task 1: re-diffed every cite against HEAD — the branch
carries only story docs, all source at `bbad7938`; cite drift noted per-AC below. Frontend ACs
(AC1–AC9) first, each: minimal local production fix + regression block appended to the existing
`deferred-108` spec file + a two-direction mutation check (revert → RED, restore → GREEN). Backend
(AC10, AC12) with targeted test classes only, no `mvn verify` (`docs/validation-strategy.md`,
[[feedback_no_local_mvn_verify]]). Deploy scripts (AC11) with `bash -n` + `shellcheck -S warning` +
a stub-`timeout` behavioural check. Docs/ledger (AC13–AC15) last.

### Completion Notes List

- **AC1** `PaymentMethodCard.vue` — AC1.1: removed the early `stripeUnavailable.value = false`
  (it flipped `showForm` before the fetches resolved, racing the watcher); now decides
  `stripeUnavailable` from the fetch OUTCOME after the `await` (`paymentStore.error.stripeConfig ||
  error.savedPaymentMethod` → raise + return; else clear + mount). AC1.2: post-save, if
  `error.savedPaymentMethod` is set, a non-blocking `$q.notify({type:'warning'})` with a new
  `payment.card.savedRefreshFailed` key (en/de/fr). AC1.3/AC1.4: spec-only. Added `useQuasar`
  import. Spec: 7 new cases in `PaymentMethodCardSpec.js` (partial `quasar` mock stubs
  `useQuasar().notify`). Cite drift: none material (`:174-187` etc. accurate).
- **AC2** `sessionManager.js` — AC2.1 spec-only (past-`rint` + positive local estimate → no expiry;
  + `WARNING_THRESHOLD` and `remaining==0`/no-cushion boundaries). AC2.2: `refreshExpiryState()`
  now returns `tick()`'s boolean; `refreshSession` success path, when `!expired &&
  hasSeenRintThisTab()`, flags `refreshFailed` unless the read-back expiry is `> WARNING_THRESHOLD`
  in the future. Legacy-path spec case (no `rint` ever) added. Cite: line 140 was correct (the
  creation "correction to :141" was itself wrong); `:259-273` → HEAD `:248-273`.
- **AC3** `MainLayout.vue` — AC3.1: `LOGOUT_BACKEND_WAIT_MS` exported from `useSession.js`;
  `handleLogout` now does pre-race `rint` clear → bounded `Promise.race([authStore.logout(), …])`
  → post-race `rint` clear, keeping MainLayout's `logout→resetSelfPlayerId→destroySession→
  deleteUserCookie→router.push` order (comment states what is unified vs deliberately divergent).
  AC3.2: `try/catch` around both `localStorage` calls; the `lang` cookie clear still runs when
  `setItem` throws. AC3.3: spec-only (PLAYER mount, `fetchSelfPlayerId` rejecting 404 → no
  `console.error`; 500 → logged). Spec: `MainLayoutSpec.js` `vi.mock` switched to async
  `importOriginal` to keep `LOGOUT_BACKEND_WAIT_MS`; `mountLayout` gained a `preMount` hook.
- **AC4** `playerStore.js` — one-line: `return requestGeneration === selfPlayerIdGeneration ?
  profile.id : null` (literal `null`, not `selfPlayerId.value`). Flipped the `deferred-108`
  characterization test to assert the superseded call resolves to exactly `null` while driving the
  B-repopulates-the-ref sequence. AC4.2 reject-path case added.
- **AC5** `booking.store.js` — AC5.1: new `deleteBatchAcceptResult(batchId)` (copy-on-write) called
  in `handleAcceptAllBatch`'s catch before the rethrow. AC5.2: explicit `if (res == null) {
  coachRequestsError = new Error('empty response…'); return false }` before the destructure — not a
  `?? {}` (which would blank the list + prune + report success). `loadCoachSchedule` not mirrored
  (no destructure, no TypeError). Cite `:605-630`/`:388-429` → HEAD offsets ~+15.
- **AC6** `BookingRequestPage.vue` — `.filter((slot) => !Number.isNaN(Date.parse(slot.startDatetime)))`
  before the `available` map, matching `ownBlockingBookings`.
- **AC7** `ParentBookingsPage.vue` — **no arithmetic change** (the fixed-instant delta is what
  `RescheduleService.java:176-184` requires). Added a code comment citing that + a spec pinning the
  submitted `proposedStart/End` span == the original booking's elapsed ms. DST-boundary *display*
  ambiguity: pinned at the computed level (`rescheduleProposedEnd !== rescheduleProposedStart`),
  noted as a minor follow-up (happy-dom Intl is UTC — the DST-crossing drift is not exercisable
  here; the elapsed-ms invariant is zone-independent, per the story's spec note).
- **AC8** `ProfileBuilderStep3.vue` — AC8.1: `hasTouchedInvalidPack` computed (any field non-null /
  non-blank label yet not `sessionCount>0 && totalPrice>0`) → `submit()` sets `packError` and
  returns; new `packError` text ref + `auth.coach.step3PackInvalid` key (en/de/fr) + a template
  line. AC8.2: `Number()` coercion of `current` before the `DURATION_CHOICES.includes` check
  (defensive only — no live hydration path; does NOT normalise the submitted value).
- **AC9** `BookingStateChip.vue` — `bookingId: { type: [String, Number], default: null }`.
  Verified: `useBookingSse` only interpolates the id into an EventSource URL; both call sites
  (`ParentBookingsPage.vue:134` `booking.id`, `CoachCommandCenterPage.vue:95` `booking.bookingId`)
  pass numbers; no consumer relies on a string. `ParentBookingsPageSpec.js` NOTE updated + a
  no-`Invalid prop`-warning assertion (real chip, terminal-status fixture to avoid EventSource).
- **AC10** `ConfigBounds.java` — added `"semiPro"`, `"pro"` to `VIDEO_QUOTA_TIER_SEGMENTS` (loop
  auto-generates 4 `BoundedKey`s at `0..Long.MAX_VALUE`, `failFast=false`); reworded the templated-
  key comment (incomplete-hand-list, not "removed constant"; no third runtime enum). Test:
  `screamingSnakeToCamel` helper replaces `toLowerCase` in the tier-coverage derivation (+ a unit
  test for it), explicit `semiPro`/`pro` assertions, `ConfigStartupAssertionTest` case for an
  out-of-range `video.quota.pro.storageBytes` → ERROR + `config.value.misconfigured` metric, no
  boot refusal. AC10.6: new `deferred-work.md` bullet for the `resolveTierKey` player-tier gap
  (`SEMI_PRO`/`PRO` players get the ATHLETE quota). No `config`→business-module compile dep added.
- **AC11** `provision.sh` + `restore-from-volume-backup.sh` — `run_bounded` signature is now
  `<dur> <label> <cmd…>` (was reading `$1` after `shift`, labelling every call `'docker'`); it
  swallows the wrapped command's stderr internally and emits its own timeout WARN afterwards so the
  WARN survives even when the caller discards stdout. Both `docker image inspect` calls wrapped at
  `IMAGE_INSPECT_TIMEOUT=10s`; the two existing `deferred-108` AC8 call sites relabelled.
  Explicitly left UNBOUNDED with reasons in the aggregate-bound comment: `aws s3 cp`, ERR-trap +
  final `${DC} up -d`, `${DC} config`, `${DC} down`. `bash -n` + `shellcheck -S warning` clean;
  stub-`timeout` check confirms WARN-on-124 names the real label and rc propagates.
- **AC12** `VideoModerationEmailListener.java` — AC12.1: explicit `persisted == null` (→ WARN
  "send outcome not yet visible") vs fall-through `SENT` (→ INFO "delivered") branch split; the
  `FAILED` block lost its redundant `persisted != null` guard. No change to the blank-recipient
  path (owner D5). Test: flipped `noPersistedEnvelope_*` to assert the WARN, added "does not WARN"
  to the SENT case, and a `@Nested RealMailManagerMappingAC122` that drives a real `MailManager`
  (real Resilience4J CB + `RetryTemplate`) over a stubbed `MailService` (the seam — `@MockitoBean
  JavaMailSender` can't work), asserting the produced `EnvelopeEntity(FAILED, isRetry)` row and the
  listener's rethrow/release decision. Scope stated in the test doc: send-mapping + listener
  decision, not the outbox-row lifecycle (`ModerationOutboxIT`'s).
- **AC13** `.github/workflows/frontend-unit-tests.yml` — `opened` added to `pull_request: types`;
  header comment updated (incl. the "base-ref → effective next PR, not this one" caveat).
  `actionlint` clean. Not added to `ci.yml`/`pr-build.yml`/`pom.xml`; not a required check.
- **AC14** `docs/deployment/runbook.md` — new sibling `## Pre-production release gate: queued
  webhook events` section (NOT a row in the migration-rewrites gate). `deferred-work.md:1310` retag
  `[DECIDED … AC14]`. No production code.
- **AC15** ledger hygiene — baselines re-measured at `bbad7938`: **1991** lines / **87** headers /
  **35** `[DECIDED` / **35** `[DISMISSED` tokens (matches the story-review-corrected figures).
  15 bullets deleted outright, 5 `[DECIDED 2026-09-11 (skillars-deferred-109 …)]` retags (AC10,
  AC11, AC12×2, AC14), the `:1695` return-value residual deleted, one new
  `## Deferred from: skillars-deferred-109 story creation (2026-09-11)` section (the AC10.6 bullet),
  and an audit block. Post-write: **1936** lines, **88** headers, real `[DECIDED]` items 35→40,
  `[DISMISSED]` 35→35, two `[PICKED UP by …]` bullets retagged. B2 note + `skillars-7-1` D4
  untouched. Reconstruction check: `git diff bbad7938` shows only the enumerated bullet
  deletions/retags/additions — no unrelated line reworded.

**Deferred verification (recorded per AC13):** the one-step `gh pr create --label frontend-tests`
behaviour only takes effect from the NEXT labelled PR (GitHub reads `pull_request` triggers from
the base ref). This story's own PR still adds the label after `gh pr create` (fires `labeled`), and
must confirm the `frontend-unit` check actually RAN (green-because-skipped does not count).

### Validation summary

**Frontend (`cd src/frontend`):**
- `npm run test:unit` — **12 files, 97 tests, all green** (was 84 pre-story: +13 new assertions
  across the 10 extended `deferred-108` specs).
- `npm run test:unit:ci` (`vitest run --coverage`) — same, green.
- `npx eslint 'src/**/__tests__/**/*.js'` + every touched production file — exit 0.
- `npx prettier --check` on all touched files — clean (4 files auto-fixed with `--write`, re-checked).
- Per-AC mutation checks (revert → RED, restore → GREEN), all run in both directions:
  - AC1.1a delete trailing `stripeUnavailable=false` → "retry mounts on first click" RED.
  - AC1.1b remove the `error.*` re-raise block → "failed refetch, no stale loadStripe" RED.
  - AC1.2 remove the `$q.notify` guard → "post-save refresh failure notice" RED.
  - AC1.3 force the `savedCard.brand` ternary true → "detailsUnavailable rendered" RED.
  - AC1.4 delete `|| setupIntent?.status !== 'succeeded'` → "requires_action" case RED.
  - AC2.1 delete `sessionManager.js` skew guard → "past-rint stays positive, no expiry" RED.
  - AC2.2a remove the whole post-refresh advance-check → "200 w/o fresh rint → refreshFailed" RED.
  - AC2.2b drop `hasSeenRintThisTab()` from the guard → "legacy refresh stays clean" RED.
  - AC3.1 bare `await authStore.logout()` → "stalled logout still resolves" hangs/RED.
  - AC3.1 drop either `rint` clear → "rint cleared pre+post race" RED.
  - AC3.2 remove either `try/catch` → "throwing setItem/getItem does not abort" RED (spy on
    `window.localStorage`, not `Storage.prototype` — happy-dom does not inherit).
  - AC3.3 `!== 404` → `if (err)` → "404 swallowed silently" RED.
  - AC4.1 restore `return profile.id` → RED; change fix to `: selfPlayerId.value` → B-repopulation
    case RED.
  - AC4.2 delete the `.finally` ref-clear → "subsequent call retries" RED.
  - AC5.1 remove `deleteBatchAcceptResult(batchId)` from the catch → "no leaked batchId key" RED.
  - AC5.2 remove the `res == null` guard → "distinguishable /empty response/ error" RED.
  - AC6 remove the `Number.isNaN(Date.parse(...))` filter → "bad slot dropped" RED.
  - AC7 change `rescheduleProposedEnd` to `new Date(start.getTime())` → "submitted pair == original
    elapsed ms" RED (2 tests, incl. the pre-existing `deferred-108` one).
  - AC8.1 remove the `hasTouchedInvalidPack` guard → 4 touched-but-invalid cases RED.
  - AC8.2 revert `Number()` coercion → "no duplicate synthetic 60" RED.
  - AC9 revert prop to `type: String` → "no Invalid prop warning" RED.

**Backend (targeted classes only, system `mvn`; GitHub CI is the full gate):**
- `ConfigBoundsEnumCoverageTest` — 6 tests green (5 pre-existing + `screamingSnakeToCamel…`).
- `ConfigStartupAssertionTest` — 11 tests green (10 + `videoQuotaProKey_negative_…`).
- `VideoModerationEmailListenerTest` (incl. `@Nested RealMailManagerMappingAC122`) — 8 tests green.
- Mutation: AC10.1 drop `semiPro`/`pro` from the list → `ConfigBoundsEnumCoverageTest` +
  `ConfigStartupAssertionTest` each 1 FAILURE. AC12.1 remove the `persisted == null` branch →
  `VideoModerationEmailListenerTest` 1 FAILURE.
- **No `mvn verify` run** (`docs/validation-strategy.md`, [[feedback_no_local_mvn_verify]]).

**Deploy scripts:** `bash -n` OK on both; `shellcheck -S warning` exit 0 on both; a stub-`timeout`
harness confirms the relabelled `run_bounded` emits the WARN with the real label on exit 124 and
propagates a non-124 rc silently.

**Docs / workflow:** `actionlint` exit 0 on `frontend-unit-tests.yml`; YAML parses.

**Ledger reconstruction check:** baselines at `bbad7938` = 1991 lines / 87 headers / 35 `[DECIDED`
/ 35 `[DISMISSED` tokens. Post-edit = 1936 lines / 88 headers / real `[DECIDED]` items 40 /
`[DISMISSED]` 35. `git diff bbad7938 -- deferred-work.md` reviewed: every removed line belongs to
one of the 15 enumerated deleted bullets or the `:1695` residual; every changed line is an
enumerated retag or the new section; no `## Deferred from:` header emptied; B2 bullet and
`skillars-7-1` D4 present and unchanged.

### File List

**Production (frontend):**
- `src/frontend/src/components/payment/PaymentMethodCard.vue` (AC1.1, AC1.2)
- `src/frontend/src/plugins/sessionManager.js` (AC2.2)
- `src/frontend/src/composables/useSession.js` (AC3.1 — export `LOGOUT_BACKEND_WAIT_MS`)
- `src/frontend/src/layouts/MainLayout.vue` (AC3.1, AC3.2)
- `src/frontend/src/stores/playerStore.js` (AC4.1)
- `src/frontend/src/stores/booking.store.js` (AC5.1, AC5.2)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC6)
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC7 — comment only, no arithmetic change)
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue` (AC8.1, AC8.2)
- `src/frontend/src/components/booking/BookingStateChip.vue` (AC9)
- `src/frontend/src/i18n/en-US/index.js`, `src/frontend/src/i18n/de-DE/index.js`,
  `src/frontend/src/i18n/fr-FR/index.js` (AC1.2 `payment.card.savedRefreshFailed`; AC8.1
  `auth.coach.step3PackInvalid`)

**Test (frontend specs — extended, not rewritten):**
- `src/frontend/src/components/payment/__tests__/PaymentMethodCardSpec.js`
- `src/frontend/src/plugins/__tests__/sessionManagerCoverageSpec.js`
- `src/frontend/src/layouts/__tests__/MainLayoutSpec.js`
- `src/frontend/src/stores/__tests__/playerStoreSpec.js`
- `src/frontend/src/stores/__tests__/bookingStoreSpec.js`
- `src/frontend/src/pages/parent/__tests__/BookingRequestPageSpec.js`
- `src/frontend/src/pages/parent/__tests__/ParentBookingsPageSpec.js`
- `src/frontend/src/components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js`

**Production (backend):**
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC10.1, AC10.4)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (AC12.1)

**Test (backend):**
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java` (AC10.2, AC10.3)
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigStartupAssertionTest.java` (AC10)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListenerTest.java` (AC12.1, AC12.2)

**Deploy / docs / ledger:**
- `deploy/provision.sh` (AC11)
- `deploy/backup/restore-from-volume-backup.sh` (AC11)
- `.github/workflows/frontend-unit-tests.yml` (AC13)
- `docs/deployment/runbook.md` (AC14 — new pre-production release gate section)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC10.5, AC10.6, AC12.3, AC14.1, AC15)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → in-progress → review)
- `_bmad-output/implementation-artifacts/skillars-deferred-109-…-cutover-decisions.md` (this file)

**Corrected by code review — this file IS part of the change under review:**
- `docs/testing/frontend-unit-tests.md` — the "Adding the `frontend-tests` label" section. The File
  List previously called this "pre-existing, left as-is … not part of this story's diff", which was
  false: the 22-line addition is in the diff, and its text described the pre-AC13 trigger set while
  the workflow comment in the same commit contradicted it. Both were reconciled by the code review;
  the section is AC13's user-facing half and belongs to this story.

### Review Findings

_**All 20 patches applied and verified 2026-09-11.** Frontend: **108 tests green under BOTH `TZ=UTC`
and `TZ=America/New_York`** (was 97, and the AC7 spec FAILED under a DST zone before this pass);
Prettier clean. Backend: `ConfigBoundsEnumCoverageTest` 5, `ConfigStartupAssertionTest` 11,
`VideoModerationEmailListenerTest` 8, the new `VideoModerationAdminAlertEnvelopeIT` 2 (real
Postgres), plus the `IntegrationTestConventionTest` and `MigrationConventionLintTest` guardrails —
all green. `bash -n` + `shellcheck -S warning` clean on both deploy scripts; `actionlint` clean.
No `mvn verify` (GitHub CI is the gate)._

_`/bmad-code-review` 2026-09-11 — three layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor),
all three completed. 2 decision-needed (both resolved by owner, converted to patches → **20 patch**), 3 deferred, 4 dismissed as noise. Every HIGH below
was re-verified by the triager against the working tree; two were reproduced by running the suite._

#### Decision needed — both RESOLVED by owner 2026-09-11, converted to patches

- [x] [Review][Decision] `ConfigBoundsEnumCoverageTest`'s camelCase derivation is not the derivation `resolveTierKey` uses — AC10 replaced `toLowerCase()` with `screamingSnakeToCamel()`, but the runtime segment comes from a hand-written exhaustive `switch` with lowercase string literals (`QuotaConfigService.java:52-56`), and `ConfigBounds.java:213` still documents tiers as "lower-cased". Today every constant is single-word so the two coincide. Add `CoachSubscriptionTier.PRO_ACADEMY` and the guard demands `video.quota.proAcademy.*` while the switch author writes `"pro_academy"` — build green, bound ≠ key, which is the exact failure the guard's javadoc claims to prevent, relocated rather than closed. **RESOLVED 2026-09-11 (owner) → option (b):** drop the derivation for tiers and hard-code the expected tier segments; VideoType keeps `screamingSnakeToCamel`, which *is* mechanical via `VideoTypeConstraints.configKey`. Keeps the AC9 `[DECIDED]` no-enum-coupling decision intact. Reconcile the `ConfigBounds.java:213` "lower-cased" comment with whatever the test now asserts. → converted to a patch.
- [x] [Review][Decision] AC12.2's "IT" was delivered as a mocked-repository unit test — AC12 required "a new / extended **IT**" asserting on a committed `EnvelopeEntity` row, with the spec noting `sendEmailSync` is `@Transactional(REQUIRES_NEW)` "so the `FAILED` row is **committed** and visible to the `findBySendId` read-back". What shipped is `@Nested class RealMailManagerMappingAC122` inside the plain unit test, with `repo = mock(EnvelopeEntityRepository.class)` and `findBySendId` stubbed via `thenAnswer(inv -> saved.get())`. The real `MailManager` retry/`isRetryable` mapping *is* exercised and the test states its own scope honestly, but no Spring context, no transaction, and no commit visibility — the one property AC12.2 called out. The story File List contains no IT file. **RESOLVED 2026-09-11 (owner) → option (a):** write the real `@SpringBootTest` IT so a genuine committed `EnvelopeEntity` row is read back through `findBySendId`. The existing `@Nested` unit test stays (it covers the `MailManager` retry/`isRetryable` mapping); the IT is additive. → converted to a patch.

#### Patch — all applied 2026-09-11

- [x] [Review][Patch] HIGH — reschedule end round-trips through a local wall-clock string, so a DST fall-back booking submits `start == end` and is hard-rejected [src/frontend/src/pages/parent/ParentBookingsPage.vue:261-266 → :382]
- [x] [Review][Patch] HIGH — a failed *saved-card* fetch alone blanks the whole card UI; `error.savedPaymentMethod` should not raise `stripeUnavailable` [src/frontend/src/components/payment/PaymentMethodCard.vue:199]
- [x] [Review][Patch] HIGH — the AC1.1 fix is not mutation-protected: re-inserting the deleted leading `stripeUnavailable.value = false` leaves the spec 11/11 green (reproduced) [src/frontend/src/components/payment/__tests__/PaymentMethodCardSpec.js]
- [x] [Review][Patch] HIGH — the AC3.1 **pre-race** `rint` clear is not mutation-protected: deleting it leaves `MainLayoutSpec` 9/9 green; AC3.1's second (no-in-flight-rewrite) case is missing [src/frontend/src/layouts/__tests__/MainLayoutSpec.js]
- [x] [Review][Patch] HIGH — the new `res == null` guard misses `''`, the exact 204 shape its own comment names (axios returns an empty body verbatim as `''`) [src/frontend/src/stores/booking.store.js:403]
- [x] [Review][Patch] HIGH — AC4.1 introduced a `null` resolution, but `PlayerHomeRedirectPage` guards only the reject path and interpolates it into a route: `/player/locker-room/null` [src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:35]
- [x] [Review][Patch] MED — the `deferred-108` story-implementation section is now empty with a dangling "One residual surfaced:", while the new audit block claims its header "stays — other bullets remain" [_bmad-output/implementation-artifacts/deferred-work.md:1687-1695, :1932]
- [x] [Review][Patch] MED — AC15's post-write line count was never re-measured: claims **1936**, file is **1938**; "real `[DECIDED]` items 35 → 40" uses the raw token count as the item baseline [_bmad-output/implementation-artifacts/deferred-work.md:1937]
- [x] [Review][Patch] MED — the AC2.2 `advanced` check subtracts a client instant from a server instant and compares against `WARNING_THRESHOLD`, re-coupling the client to `JWT_TTL` — which `SecurityConstants.java:68-79` explicitly forbids [src/frontend/src/plugins/sessionManager.js:285]
- [x] [Review][Patch] MED — clearing a numeric `q-input` yields `''`, not `null`, so `packRowTouched` counts a visually empty row as touched and permanently blocks submit [src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue:161]
- [x] [Review][Patch] MED — `packError` is never cleared by `removePack()` or by editing a row; the stale error survives after the offending row is fixed or removed [src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue:151-153]
- [x] [Review][Patch] MED — the `opened`-trigger comment ("GitHub reads this file from the BASE ref") contradicts `docs/testing/frontend-unit-tests.md` in the same diff, and that doc is in the diff while the File List disowns it [.github/workflows/frontend-unit-tests.yml:18-21; docs/testing/frontend-unit-tests.md:34-56]
- [x] [Review][Patch] LOW — AC11's new aggregate-bound comments cite pre-change line numbers, stale by ~29 lines in both scripts [deploy/backup/restore-from-volume-backup.sh:50-58; deploy/provision.sh:105-106]
- [x] [Review][Patch] LOW — Task 9 is checked off claiming `:rules` were added; the only template change is one error `<div>` [story Tasks item 9]
- [x] [Review][Patch] LOW — `run_bounded`'s timeout WARN claims the hardcoded fallback was used, but the first wrapped `inspect` is non-terminal and falls through to `docker pull`; a timeout is also indistinguishable from "image absent", so a slow daemon triggers a full registry pull inside the outage window [deploy/backup/restore-from-volume-backup.sh:89-91, :113]
- [x] [Review][Patch] LOW — `console.error`/`console.warn` spies and `document.cookie` writes in the new spec blocks are never restored, suppressing console output for later tests in the file [src/frontend/src/layouts/__tests__/MainLayoutSpec.js; src/frontend/src/pages/parent/__tests__/ParentBookingsPageSpec.js]
- [x] [Review][Patch] LOW — the AC9 "no Invalid prop warning" test has no positive control: it never asserts `BookingStateChip` mounted with `42`, so it passes vacuously if the chip stops rendering [src/frontend/src/pages/parent/__tests__/ParentBookingsPageSpec.js]
- [x] [Review][Patch] LOW — AC15 states a `[DECIDED]` delta of **+3**; five retags were made and the audit block records 5 [story AC15]

#### Deferred (pre-existing, filed to `deferred-work.md`)

- [x] [Review][Defer] `VideoModerationEmailListener`: `persisted == null` WARNs and returns normally, which releases the outbox row for an *unknown* send outcome — identical handling to a confirmed success [src/main/java/.../VideoModerationEmailListener.java:102-107] — deferred, pre-existing (AC12.1 changed only the log level, not the release decision)
- [x] [Review][Defer] `handleAcceptAllBatch`'s catch calls `deleteBatchAcceptResult` unconditionally, so a throw *after* a successful `setBatchAcceptResult` discards real per-booking results [src/frontend/src/stores/booking.store.js:645-651] — deferred, pre-existing
- [x] [Review][Defer] No frontend CI job runs the Vitest suite under a non-UTC timezone, so every DST assertion in the suite is structurally unfalsifiable [.github/workflows/frontend-unit-tests.yml] — deferred, test-infra gap beyond this story

#### Dismissed (4)

- The trailing `stripeUnavailable.value = false` "overrides the catch arm" — false positive: the `catch` block `return`s, so there is no fall-through.
- `slotRows` "silently discards" unparseable slots — this is AC6's stated requirement; the non-ISO-format scenario is speculative against an ISO-emitting API.
- "Retry is untested against a sticky store error" — subsumed by the two `PaymentMethodCard` patches above.
- AC11's redirection "implemented differently" (`2>/dev/null` bound inside `run_bounded` rather than at each call site) — the deviation is deliberate, documented in the header, meets AC11's stated intent, and is `shellcheck -S warning` clean.
