# Senior-dev audit — `skillars-deferred-108`

**Story:** `_bmad-output/implementation-artifacts/skillars-deferred-108-frontend-unit-spec-backfill-restore-pull-timeout-and-configbounds-templated-key-decision.md`
**Reviewed at:** `HEAD` = `73f065bd` (story-creation commit; base `c74abde9`)
**Date:** 2026-09-10
**Method:** every AC's "Verified at HEAD" claim re-checked against the actual file; ledger line
cites re-checked against `deferred-work.md`; baseline suite executed (`npx vitest run` →
**12 tests / 2 files, green** — the story's green-gate arithmetic is correct).

**Verdict: needs revision before dev.** The story is unusually well-researched and most of its
cites hold. But three ACs contain preconditions that are **unreachable as written** (AC1 mutation
check, AC3 LRU cap, AC5 early-return), two contain **premises contradicted by the source**
(AC1 saved-card branch, AC2 merged rows), and AC10 instructs the deletion of a **project-owner
decision bullet** that AC5 does not and cannot close. Fixing the AC text is cheap; discovering
these mid-implementation is not.

Findings are ordered by severity. Everything below was verified against the file named — no
finding here is speculative.

---

## Blocking — an AC cannot be satisfied as written

### B1. AC10 deletes a `[DECIDED]` bullet, and AC5 cannot close either candidate

The story's AC10 says to delete *"`## Deferred from: code review (round 2) of 1-7b-… (2026-09-02)`
— the `startSessionMonitoring()` early-return bullet (`:1128`)"*. Both halves are wrong:

| | Reality |
|---|---|
| `deferred-work.md:1125` | The round-2 1-7b bullet. Verbatim: *"**Deliberately left as-is rather than patched**: the alternative (arm the interval anyway) … re-dispatches `session:expired` … Both options have real costs and the abort path is unverified."* |
| `deferred-work.md:1126` | `## Deferred from: skillars-deferred-90 story creation and implementation (2026-09-02)` |
| `deferred-work.md:1128` | A **different** bullet, in the **deferred-90** section: *"`startSessionMonitoring()`'s early-return-with-no-timer path — left as documented (**project-owner decision**)."* |

So the cited line number points into the wrong section, at a bullet whose whole content is an
owner decision. Deleting it directly violates this story's own stated rules — *"`[DECIDED]` /
`[DISMISSED]` (do not re-litigate)"* and AC10's own "decided-retention" convention.

Worse, **neither bullet is a test-coverage gap AC5 can close.** The open concern in `:1125` is:
*App.vue's `handleSessionExpired` → `router.push()` is not awaited or `.catch()`ed; Vue Router 4
rejects on an aborted navigation; if it aborts, `cleanup()` has already reset state to look
healthy and nothing re-arms monitoring.* That failure mode lives in `App.vue` + `vue-router`,
not in `sessionManager.js`. A Vitest spec asserting the *current* behaviour (no second dispatch,
`checkIntervalId` stays `null`) **documents the accepted tradeoff — it does not resolve it.**

**Fix:** drop both bullets from AC10's delete list. Keep AC5's early-return spec (it is worth
having), but reframe it as a characterization test and, at most, annotate `:1125` with
*"behaviour now pinned by `skillars-deferred-108` AC5; the router-abort concern is unchanged."*

---

### B2. AC5's "early-return, no timer armed" precondition is unreachable as described

AC5 says: *"first `tick()` in `startSessionMonitoring()` reports expired (**idle-expired** /
cookies cleared)"*. The idle-expired half cannot happen, and the cookies-cleared half needs a
setup step the AC never mentions.

The story's "Verified at HEAD" summary of `computeTimeUntilExpiry()` **omits line 140 entirely**:

```js
// sessionManager.js:139-141
const remaining = expiresAt - Date.now()
if (remaining <= 0 && localEstimate > 0) return localEstimate   // ← the skew cross-check
return remaining
```

And `startSessionMonitoring()` calls `recordActivity()` (`:215`) **immediately before** `tick()`
(`:228`), which pins `lastActivityTime = Date.now()`, making
`localEstimate === LEGACY_SESSION_TTL` (15 min, positive) every single time.

Consequences:

* **A past-due `rint` can never expire the first tick** — line 140 returns the positive local
  estimate. "Idle-expired" is structurally impossible here.
* The **only** path to `tick() === true` is the `expiresAt === null` torn-down branch
  (`:133`), which requires `checkIntervalId !== null`.
* `startSessionMonitoring():209` is `if (checkIntervalId) clearInterval(checkIntervalId)` — it
  clears the timer but **never nulls the variable**. So `checkIntervalId` is non-null only if a
  *previous* `startSessionMonitoring()` armed it and no `cleanup()` / `stopSessionMonitoring()`
  ran since (both of which set it to `null`).

**The only working recipe**, which AC5 must state:

1. write a valid future `rint`, `startSessionMonitoring()` → interval armed, `rintSeen` set;
2. clear `rint` **and** make `hasUserSession()` false;
3. `startSessionMonitoring()` again → `:209` clears the timer but leaves the id truthy →
   `tick()` hits the torn-down branch → `0` → dispatch + `cleanup()` → `return true` → early
   return, `checkIntervalId` back to `null`.

Note this also means the spec's mutation-sensitivity is load-bearing on `:209` not nulling the
id — a latent oddity worth a line in the Dev Agent Record.

Two smaller AC5 corrections while you are in there:

* The **"multi-tab extension"** case largely duplicates the existing reference spec's case 2
  (`sessionManagerSpec.js:106-118`: `rint` 4 min → 10 min, warning cleared, countdown cleared).
  The genuinely *new* assertion is the **>`LEGACY_SESSION_TTL`** value (30 min > 15 min) proving
  there is no clamp. Say that explicitly, or the spec adds nothing.
* AC5's summary of the torn-down branch and the "no upper bound" note are both **correct**; only
  line 140 is missing.

---

### B3. AC3's LRU-cap test needs a seam the store does not expose

AC3 asks for: *"**LRU cap:** `setBatchAcceptResult` 201 distinct ids → size is 200 …"*

`setBatchAcceptResult` (`booking.store.js:594`) and `MAX_BATCH_ACCEPT_RESULTS` (`:593`) are
**not in the store's return object** (verified against `booking.store.js:632-710`). They are
closure-private. The only reachable caller is `handleAcceptAllBatch` (`:605`), which on its
success path calls `loadCoachBookingRequests()` — whose prune (`:409-414`) deletes every entry
whose `batchId` is not in the returned `batchGroups`, defeating the accumulation the test needs.

There is one workaround: make the `getCoachBookingRequests` mock **reject** on every call, since
the prune only runs on the success path (`:396-414`). The map then accumulates across 201
`handleAcceptAllBatch` calls. That is non-obvious and must be written into the AC — otherwise
the dev hits the story's own escape hatch (*"if a unit turns out to be genuinely untestable
without a seam, stop and record it … rather than refactoring production code"*) and the AC
silently degrades to an open question.

**Also add a constraint the AC currently omits:** the ids must be **non-integer-like strings**
(UUID-shaped, as real `batchId`s are). `setBatchAcceptResult`'s LRU trick is delete-then-reinsert
to move a key to the end of `Object.keys()` order — but JS orders **integer-index keys
numerically, before all string keys, regardless of insertion order**. A spec using `'1'`…`'201'`
would test the opposite of the intended behaviour.

---

### B4. AC1's component spec will inject a live Stripe script unless `@stripe/stripe-js` is mocked

AC1's mock list names only `payment.api.js`. But `PaymentMethodCard.vue:77` statically imports
`loadStripe` from `@stripe/stripe-js`, and that package **starts loading Stripe.js at module
import time**, not at `loadStripe()` call time. Verified in
`node_modules/@stripe/stripe-js/dist/index.mjs` (tail):

```js
// Execute our own script injection after a tick to give users time to do their own script injection.
Promise.resolve().then(function () { return getStripePromise(); })
  ["catch"](function (error) { if (!loadCalled) { console.warn(error); } });
```

`getStripePromise()` appends `<script src="https://js.stripe.com/dahlia/stripe.js">` to the
happy-dom document. **AC1 must require `vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))`**
— both to keep the spec hermetic and because the "did not call `loadStripe`" assertion needs the
spy anyway.

---

### B5. AC1's mutation check is not mutation-sensitive

AC1 states: *"remove the `loadStripe` null-key guard → the empty-config spec **throws**"*.

It does not. `PaymentMethodCard.vue`:

```js
:107-113   const key = paymentStore.stripeConfig?.publishableKey
           if (!key) { stripeUnavailable.value = true; return false }   // ← the guard
:114-119   try { stripe = await loadStripe(key); elements = ... } catch { stripe = null; elements = null }
:120-123   if (!stripe || !elements) { stripeUnavailable.value = true; return false }
```

Delete the guard and `loadStripe(undefined)` is swallowed by the `try/catch` at `:118`, landing on
the **identical** `stripeUnavailable = true; return false` outcome at `:120-123`. Nothing throws
and no observable state differs.

The only assertion that actually goes red is **`expect(loadStripe).not.toHaveBeenCalled()`**.
AC1 lists that assertion, but its mutation-check sentence names the wrong one — which is exactly
the failure mode ("a spec that stays green when its target fix is reverted") the story's global
conventions forbid. Reword the mutation check to name the spy assertion.

---

### B6. AC1's saved-card / empty-card branch premise is wrong

AC1 asks for *"the **saved-card** branch when `savedPaymentMethod` is populated vs the
**add-card / empty** branch when it is not"*, and speculates the API returns
*"`null` / `{}`; confirm"* for "no saved method".

Confirmed, and it is neither:

```java
// SavedPaymentMethodResponse.java:3
public record SavedPaymentMethodResponse(boolean hasCard, String brand, String last4, Long expMonth, Long expYear) {}
```

The endpoint **always** returns an object. The discriminator everywhere in the component is
`hasCard`, not presence:

```
PaymentMethodCard.vue:20   v-if="savedCard?.hasCard && !editing"
PaymentMethodCard.vue:45   v-if="!savedCard?.hasCard"
PaymentMethodCard.vue:102  showForm = !stripeUnavailable && (editing || !savedCard?.hasCard)
```

A spec written to "populated vs not" would set `savedPaymentMethod = { hasCard: false, … }`,
see the *form* branch, and either fail or be rewritten to assert the wrong thing. Restate AC1 in
terms of `hasCard: true` vs `hasCard: false`.

---

### B7. AC10's `deferred-104` roll-up edit will not match the ledger text

AC10 says to strike/annotate, in the `:1277-1280` roll-up, *"each named dependent
(`deferred-11` / `-17`/`-18` / …)"*. The story's Story Overview also presents that roll-up as a
block quote beginning *"`deferred-11` payment, …"*.

The ledger does not say `deferred-11`:

```
1278:   (`deferred-12` payment, `deferred-17`/`-18` D6, `deferred-35`/`-36`/`-37`/`-38` `booking.store.js`,
```

`skillars-deferred-12` is a real, unrelated backend story (review moderation / booking locks —
see `deferred-work.md:127`, `:140`, `:905`). The `-12` in the roll-up is a pre-existing ledger
typo for `-11`. Either way, the story silently "corrects" it in what it presents as a verbatim
quote, and AC10's find-and-strike instruction will not match the file. Call the typo out
explicitly and have AC10 fix it as part of the annotation.

---

## Significant — will cost real time or produce a vacuous test

### S1. AC2's "merged own-booking rows" describes behaviour the code does not have

AC2 asks for: *"a slot already held by the current parent is **merged into its row** rather than
shown as bookable"*.

`BookingRequestPage.vue:452-471` does the opposite — it **appends** a parallel row:

```js
const slotRows = computed(() => {
  const available = bookingStore.computedSlots.map((slot) => ({ type: 'available', … }))
  const own = ownBlockingBookings.value.map((b) => ({ type: 'own', … }))
  return [...available, ...own].sort((a, b) => a.sortKey - b.sortKey)
})
```

Nothing is removed from `computedSlots` and nothing is merged; the file's own comment says
*"the merged list reads as a continuous grid with some rows greyed out"* — i.e. own rows are
**additional, non-selectable** rows interleaved by `sortKey`. The correct assertions are:
an own booking produces a `type: 'own'` row, that row is not selectable, and it does not count
toward `batchAtMax` (`:269`, which reads `batchBasketSize`).

Relatedly, AC2's own "Verified at HEAD" bullet — *"`deferred-18` AC1 — other parents'/players'
already-booked slots are **filtered out of `getAvailabilityCalendar` results**"* — describes a
**backend** filter. It is not observable from a frontend spec at all; the page renders whatever
`computedSlots` contains. Drop it from AC2 or move it to a backend AC.

### S2. AC2's mount preconditions are under-specified — the assertions will go vacuous

AC2 says *"`vue-router` mocked or `createRouter` with memory history"* and lists no other mocks.
`BookingRequestPage.vue` reads route state **at setup time**:

```js
:241   const coachId = route.params.coachId
:246-247  if (route.query.playerId && !authStore.isPlayer) { const parsed = Number(route.query.playerId) … }
```

With `routes: []` and no navigation, both are `undefined`, and then:

* `ownBlockingBookings` (`:439`) filters on `String(b.coachId) !== String(coachId)` → **every**
  fixture booking is dropped → the "own rows" assertion passes for the wrong reason;
* `canSubmit` (`:291-297`) is `selectedSlot !== null && !!playerId && !submitting` → permanently
  `false` → `submit()` returns at `:479` and the "payload carries `requestedStartTime` defined"
  assertion is **unreachable**.

The AC must require a real route with `params.coachId` + `query.playerId`, navigated, with
`await router.isReady()`.

`onMounted` (`:625-670`) additionally calls `playerStore.fetchSelfPlayerId()`, `getBatchConfig()`
and `getBookingRequestConfig()`. None are in AC2's mock list; unmocked they reach real axios.
`ownBlockingStatuses` comes from `getBookingRequestConfig()` and gates `ownBlockingBookings`
(`:441`) — so leaving it unmocked silently changes what the "own rows" test sees.

### S3. AC7 — a PR created *with* the label will never trigger the job

AC7's test is *"the labelled PR's `frontend-unit` check green"*. The workflow's trigger
(`.github/workflows/frontend-unit-tests.yml:21-23`) is:

```yaml
  pull_request:
    types: [labeled, synchronize, reopened]
```

**`opened` is not in the list.** `gh pr create --label frontend-tests` fires only `opened`, which
is not subscribed — no run, no check, AC7 unverifiable. The label must be added **after** the PR
exists (`gh pr edit --add-label frontend-tests`), or a push must follow, or use
`workflow_dispatch`. Put this in AC7 verbatim.

Second, smaller point: the workflow runs `npm run test:unit:ci` (`vitest run --coverage`), while
the story's green gate is `npm run test:unit` (`vitest run`). Different scripts. Run the CI one
locally too — it is the one that emits `coverage/` (already eslint-ignored, `eslint.config.js:21`).

### S4. AC8 misses the second unbounded `docker` call in the very same probe

AC8 bounds `docker pull`. The probe has a second unbounded remote-ish call three lines later:

```sh
# restore-from-volume-backup.sh:40
docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img" >/dev/null 2>&1 || return 0
# restore-from-volume-backup.sh:45   (identical at provision.sh:100)
''|*[!0-9]*) uid=$(docker run --rm --entrypoint sh "$img" -c "id -u '$user'" 2>/dev/null) || return 0 ;;
```

`docker run` is reached whenever the image's `Config.User` is **non-numeric** — which is the case
for `prometheus` (declares `nobody`), one of the five services probed at `:141-145`, i.e. squarely
inside the `${DC} down` (`:97`) → `${DC} up -d` (`:160`) outage window. Wrapping only the pull
leaves an unbounded container start in the exact window the ledger item is about. Wrap both.

Also state the **aggregate** bound: a per-call `timeout` × 5 services is up to 5 × N seconds
added to the outage, not N. Either pick N accordingly (e.g. 20–30s) or add a total budget.

### S5. AC8's "reuse an existing timeout constant … check `provision.sh` for precedent" — there is none

Verified: **no `timeout(1)` invocation exists anywhere under `deploy/`.** The only hit is a prose
comment at `restore-from-dump.sh:210`. The established bounded-wait idioms in this tree are:

* a deadline loop — `restore-from-volume-backup.sh:166-172`
  (`DEADLINE=$(($(date +%s) + 120))` … `until … healthy`);
* a fixed retry loop — `restore-from-dump.sh:212-221` (5 attempts, `sleep 2`).

Say this in the AC and just name the value, or the dev spends the AC hunting for a precedent that
does not exist. (The proposed `timeout "${IMAGE_PULL_TIMEOUT:-60}"s` shape is otherwise fine under
`set -euo pipefail`: a missing `timeout` binary or exit 124 both fall through the existing
`|| return 0` into the numeric-constant fallback + WARN.)

### S6. AC4 does not cover the residual cross-account hole the ledger bullet is actually about

AC4's headline mutation target — the `requestGeneration === selfPlayerIdGeneration` guard at
`playerStore.js:39` — protects the **cached ref**. It does not protect the **returned promise**:

```js
// playerStore.js:38-46
if (requestGeneration === selfPlayerIdGeneration && profile?.id != null) {
  selfPlayerId.value = profile.id          // ← suppressed for a stale generation
}
if (profile?.id == null) { throw new Error('Player profile response has no id') }
return profile.id                          // ← returned UNCONDITIONALLY
```

So a superseded `fetchSelfPlayerId()` still resolves with the **other account's id**. And that
value is consumed directly into page-local state:

```js
// BookingRequestPage.vue:628
selfPlayerId.value = await playerStore.fetchSelfPlayerId()
```

which then feeds `playerId` → the `submitBookingRequest` payload. That is precisely the
*"cross-account booking-misattribution risk"* the `deferred-43` ledger bullet (`:1001`) names.
AC4 should assert the **return value** of the superseded call, not only `selfPlayerId.value`.

Per the story's own "No production-code changes in AC1–AC6" rule, the fix is out of scope — but
this belongs in the Dev Agent Record as an open question / new ledger item, not silently closed
by deleting `:1001`.

### S7. AC4's "finally-reference safety" case is not directly assertable

`selfPlayerIdRequest` is closure-private (`playerStore.js:13`) and not exported. There is no way
to assert *"the stale request's `.finally` does not null the newer reference"* directly. The only
observable proxy is behavioural: after the stale request settles, a further `fetchSelfPlayerId()`
must **not** issue a new `getMyProfile` call (proving the newer request is still installed).
State that in the AC, or the case will be written as an untestable assertion.

*(Checked and clean: `selfPlayerIdRequest` / `selfPlayerIdGeneration` live inside the setup
function, so `setActivePinia(createPinia())` per test gives real isolation. The code comment
calling them "module-scoped" is inaccurate but harmless.)*

### S8. AC6's `vi.spyOn` on `sessionManager` exports will not work

AC6 says *"`sessionManager` exports (`stopSessionMonitoring`, `cleanup`) spied"*. `useSession.js:3-15`
imports them as static ESM named bindings. ES module namespace objects are non-configurable —
`vi.spyOn(sessionManagerModule, 'cleanup')` is not a supported pattern for a statically imported
module in Vitest. Use `vi.mock('src/plugins/sessionManager', { spy: true })` or a factory with
`importOriginal()`. Same applies to `router.push` if the spec imports the router module rather
than injecting a stub router.

---

## Worth fixing — harness gotchas the ACs should pre-empt

### H1. Any `.js` file under `src/**/__tests__/**` is collected as a test file

`vitest.config.mjs:33-38`:

```js
include: [
  'src/**/*.{spec,test}.{js,mjs}',
  'src/**/__tests__/**/*.{js,mjs}',      // ← unconditional
],
```

The second glob has **no `Spec`/`spec` qualifier**. A shared fixture or helper module dropped
next to the specs (e.g. `stores/__tests__/fixtures.js`) is collected and fails the run with
*"No test suite found in file"*. With six ACs' worth of new specs, shared fixtures are likely —
say up front that helpers go somewhere outside `src/**/__tests__/`.

### H2. AC6's locale assertions leak between tests

`test/vitest/setup-file.js:19-30` creates **one** `vue-i18n` instance per test *file* and installs
it via `config.global.plugins.unshift(i18n)`. `MainLayout.changeLanguage('de-DE')` (`:303-304`)
mutates that shared `locale` ref, and `loadLanguagePreference()` (`:313`) runs on **every**
`MainLayout` mount via `onMounted`. Without an `afterEach` that restores `locale.value = 'en-US'`
and `localStorage.removeItem('locale')`, the "ignores an invalid `'xx-YY'`" case will read a
locale a previous test set and pass or fail for the wrong reason.

### H3. Quasar overlays teleport out of the wrapper

Two AC2 targets render outside `wrapper.html()`:

* `ParentBookingsPage.vue:156-199` — the reschedule dialog is inside `<q-dialog>`; the read-only
  derived-end field (`:178-186`, backed by the `rescheduleProposedEnd` computed at `:252`) is only
  in the DOM once the dialog opens, and then under `document.body`.
* `ProfileBuilderStep3.vue:14-20` — `<q-select :options="durationOptions">`; the option list lives
  in a `QMenu` popup, also teleported.

Assert via `wrapper.vm` / component props, or query `document.body` after opening. Left
unaddressed, both specs will assert against an empty wrapper and be trivially green.

### H4. AC3's unwrap-contract test transitively exercises `loadCoachBookingRequests`

`handleAcceptAllBatch` (`:605-628`) always calls `loadCoachBookingRequests()` before returning.
The AC's mock list should therefore require `getCoachBookingRequests` to be stubbed too, and
should note that the just-written `batchAcceptResultsByBatch[batchId]` **will be pruned** by that
refresh unless the returned `batchGroups` includes the id — which is exactly why the AC's
"read `results` from the return value" framing is right. Make the mock requirement explicit.

---

## Precision — cite drift and ledger-edit accuracy

### P1. AC9's `ConfigBounds.java` cites are still off by 2–3 lines

The story's Change Log claims it corrected `:180-181` → `:196-197`. Actual:

```
193:    // ── Templated per-enum keys — generated, never hand-listed (AC3) ──…
197:    // without a matching bound here.
199:    static final List<String> VIDEO_QUOTA_TIER_SEGMENTS = List.of("scout", "instructor", "academy", "athlete");
200:    static final List<String> VIDEO_TYPE_SEGMENTS = List.of("homework", "drillDemo", "coachReview");
```

Comment block is `:193-197`; the two lists are `:199-200`. (Story says `:191-195` / `:196-197`.)

### P2. AC9's drift-guard justification is slightly stronger than the test

`ConfigBoundsEnumCoverageTest` iterates `CoachSubscriptionTier.values()` / `VideoType.values()`
and asserts each has a matching bound. It therefore catches **additions and renames** — but not
**removals**: deleting an enum constant leaves a stale hand-listed segment and the test still
passes. Harmless (a bound nobody reads), but the AC9 comment should say "fails the build when a
new constant lands without a bound" rather than implying full bidirectional drift protection.

### P3. AC10 header-removal instructions are inconsistent

AC10 marks "Header removed" for the `deferred-11`, `deferred-38` and `deferred-43` sections. Two
more sections also empty out and are not marked:

* `## Deferred from: code review of skillars-deferred-17-… (2026-08-06)` (`:911`) — D6 at `:913`
  is its **sole** bullet (`:915` is the next header).
* `## Deferred from: code review (round 2) of 1-7b-… (2026-09-02)` (`:1123`) — `:1125` is its sole
  bullet. *(Moot if you accept B1 and keep that bullet.)*

### P4. AC10's `:1151` instruction misreads the bullet

AC10 says to edit `:1151` and *"Keep the de-DE native-speaker-review residual intact."* `:1151`
is the **"No frontend test framework"** bullet; the de-DE residual is a **separate** bullet at
`:1149`. No de-DE content is at risk at `:1151`.

More substantively, `:1151`'s dependent list is
`` `5-4` W9, `deferred-17`/`-18`/`-30`/`-37`/`-38`/`-43` D6``. **`deferred-30` has no bullet
anywhere in `deferred-work.md`** and is not in this story's scope (D1). Strike only the
`deferred-91 handleLogout / i18n` clause the AC names — do not blanket-annotate the whole list as
closed.

### P5. Cites that were re-checked and are correct

For the record, so the dev does not re-verify: `booking.store.js:170` / `:388` / `:593` / `:594` /
`:605` ✓; `playerStore.js:13-14` / `:28` / `:39` / `:53` / `:60` ✓; `MainLayout.vue:303` / `:313` /
`:335` ✓; `useSession.js:38` / `:70` (story says `:71`) ✓; `payment.store.js:238` / `:249` ✓;
`restore-from-volume-backup.sh:35` / `:40` / `:97` / `:141-145` / `:160` ✓;
`provision.sh:89` / `:94` ✓; `deferred-work.md:657` / `:913` / `:929` / `:997` / `:1001` /
`:1121` / `:1151` / `:1267` / `:1277-1281` / `:1658-1659` ✓. The `skillars-uat-2` attribution for
`:929` is correct (header at `:921`) — the neighbouring `uat-1` header at `:915` is a different
section. The 12-test baseline is correct.

---

## Recommended edits before dev picks this up

1. **AC10** — remove `:1125` and `:1128` from the delete list (B1); fix the `deferred-12`/`-11`
   quote (B7); mark the two extra emptied headers (P3); narrow the `:1151` instruction (P4).
2. **AC5** — add line 140 to the "Verified at HEAD" summary and spell out the two-call recipe for
   the early-return case (B2); reframe the early-return spec as characterization, not closure.
3. **AC3** — either state the reject-the-refresh workaround for the LRU case or drop it to an
   open question; require UUID-shaped ids (B3); require the `getCoachBookingRequests` stub (H4).
4. **AC1** — require `vi.mock('@stripe/stripe-js')` (B4); rewrite the mutation check to name the
   `loadStripe` spy assertion (B5); restate the branch test in terms of `hasCard` (B6).
5. **AC2** — replace "merged into its row" with the `type: 'own'` row semantics and drop the
   backend-filter bullet (S1); require a navigated memory router with `params`/`query` plus mocks
   for `getBatchConfig` / `getBookingRequestConfig` / `fetchSelfPlayerId` (S2).
6. **AC4** — add an assertion on the superseded call's **return value** and record the residual as
   an open question rather than closing `:1001` outright (S6); restate the finally-reference case
   behaviourally (S7).
7. **AC6** — swap `vi.spyOn` for `vi.mock(..., { spy: true })` (S8); add the locale-restore
   `afterEach` (H2).
8. **AC7** — require the label to be added **after** PR creation, and run `test:unit:ci` locally
   too (S3).
9. **AC8** — wrap `docker run` as well as `docker pull`, in both scripts; state the aggregate
   bound; note that no `timeout(1)` precedent exists in `deploy/` (S4, S5).
10. **AC9** — fix the line cites (P1) and soften the drift-guard claim to "additions/renames" (P2).
11. **Global conventions** — add a line on where shared fixtures may live (H1) and on Quasar
    teleport for dialog/select assertions (H3).
