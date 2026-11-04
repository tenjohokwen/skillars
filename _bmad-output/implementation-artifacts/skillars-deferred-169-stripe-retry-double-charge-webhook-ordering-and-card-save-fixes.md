# Story skillars-deferred-169: Stripe retry double-charge, webhook ordering, superseded proration, card-save and pause-date fixes

Status: ready-for-dev

## Story

As a **parent or coach paying through Skillars**,
I want **a failed payment attempt to be safe to retry, a subscription change to be applied even if its webhook races the signup, money I have paid to either grant what I bought or be recorded for recovery, a card that was declined not to become my saved default, and the earliest pause date the picker offers me to be accepted**,
so that **I am never charged twice, never silently left without what I paid for, and never blocked by a date my own screen told me to choose**.

## Context

Owner-chosen bundle of five payments items filed on the review-evals baseline sample and verified against
**master `bed75f19`** (the real HEAD at story creation — not any SHA quoted inside the ledger). Branch:
`skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes`.

**Numbering and master.** This story was created as `skillars-deferred-168` and renumbered to **169** because another story
held that id; **that story (`skillars-deferred-168`, builder packs) has since merged as #436** (`47180ea7`), and its migration
**`V226__backfill_builder_packs_into_session_pack_tiers.sql` is on master**. This branch has been merged with `origin/master`
(`281b8433` at the time), so the code the story cites is current. **The next free number on master is `V227`, but it is
contested**: `feature/pitchside-master-sync` (local and on `origin`) already renamed its migration to
`V227__registration_phone_in_use_email_template.sql`, and `skillars-deferred-170` (in progress in the worktree
`/Users/mokwen/dev/gitrepos/bluegithub/skillars-deferred-168`) says "169 also claims V227+, whichever merges second renumbers".
Every migration here is therefore **`V227` or later and must be re-derived at dev time** (list `src/main/resources/db/migration`
on `origin/master`, `git branch -a` local *and* remote, every worktree).

**`skillars-deferred-170` is sequenced after this story and rewrites the same lines.** Its AC3 moves the coach-readiness check ahead
of any write in `purchasePack` and it edits `SessionPackPurchasePage.vue` (it independently found the `data.message` bug at `:189`).
AC1a's ordering below is written so that reorder composes with it: gates and the attempt lookup first, readiness before the attempt
insert and the card write.

**Line numbers.** #436 added 42 lines to `SessionPackPaymentService.java`. Everything before `:336` is unchanged (the single inserted hunk is `@@ -335,0 +336,42`), including `:98-192`, `:139`, `:140`,
`:144`, `:148`, `:154-186`, `:204`) are unchanged; those after it moved — `getSavedPaymentMethod :363→:405` (null guard `:367→:409`),
`getOrCreateStripeCustomer :398→:440` (card saves `:402→:444`, `:411→:453`), `createPurchase :415→:457`, and `@Transactional` is now at
`:255, :295, :347, :361, :378`. Also `BookingRequestPage.vue zonedMidnightMs :552→:554` and `i18n/en-US/index.js pauseStartInPast :1992→:1995`.
**Re-locate by symbol, not by number, in anything that is not listed here.**

Ledger source: `_bmad-output/implementation-artifacts/deferred-work.md`, section
`## Deferred from: review-evals baseline real-sample, owner-verified (2026-11-03)` — items RS05, RS06,
RS07, RS08, RS09.

**Every claim below was re-verified against current source by four parallel read-only audits, and then
the whole story was audited again by `/mto-story-review` (report: `story-review.md`). Four ledger
assertions were wrong or incomplete (corrections 1, 2, 4, 5). Correction 3, as first written, was itself
wrong and has been replaced. Read the next section before trusting anything the ledger says — or this
story's first draft.**

## Corrections to the filed items — read these first

**1. RS06 says the failing response is a 500. It is a 422, and the distinction is the whole fix.**
`ChargeOutcomeUnknownException extends PaymentGatewayException`
(`ChargeOutcomeUnknownException.java:15`), and `PaymentApiAdvice` maps that whole family to
`ResponseEntity.unprocessableEntity()` (`PaymentApiAdvice.java:18,25`). So **every** post-capture
money-at-risk failure on the purchase endpoint answers **422**. A fix written as "retain the idempotency
key on 5xx" would be a no-op: the dangerous path is a 4xx.

**2. RS06 points at the wrong branch. The strictly worse path leaves no trace at all.** The ledger names
the `PACK_PURCHASE_UNRECONCILED` branch (`SessionPackPaymentService.java:166-171`), which at least writes
a `stripe_refund_failures` row and is covered by the `MoneyIntegrityEvent` Grafana alert
(`deploy/lgtm/grafana-alerts.yml:638-648`). But `chargeAndCapture` at `:144` sits **outside** the `try`
that starts at `:154`, so a `ChargeOutcomeUnknownException` — thrown on a bare timeout, a 409, or any 5xx
from Stripe (`StripePaymentGateway.java:262-266`) — propagates with **no** `session_pack_purchases` row,
**no** `stripe_refund_failures` row, **no** `booking_payments` row, and `STRIPE_CHARGE_OUTCOME_UNKNOWN`
is **absent from the alert regex**. Recoverable only from an application log line
(`StripePaymentGateway.java:181`).

**3. RS09's second clause is TRUE on a retry — the first draft of this story wrongly called it false.**
The ledger and the draft case say a declined card stays saved *and* "the subscription sync is skipped
later". The first draft of this story refuted the second half because `previousCard` is snapshotted at
`SessionPackPaymentService.java:139`, before `getOrCreateStripeCustomer` writes at `:140`. That is correct
for the **first** attempt only. After a decline the declined card X is **committed** to `stripe_customers`
(no transaction, no revert); a retry with the same card reads `previousCard == X` at `:139`, so
`scheduleSubscriptionCardSync` returns early at `:204` and the live subscriptions are never moved onto X.
The draft case's `F1` describes exactly this ("if the parent then retries with the same card … previousCard
already equals paymentMethodId") and it holds at HEAD. **Do not "correct" that draft or the ledger entry.**
Two further facts, both wrong in the first draft: `4db90562` (#362) added the snapshot, but it is an
ancestor of *both* RS09 input revisions (`b123c73de463`, `980eff338df6` — verified with
`git merge-base --is-ancestor`), so it never "landed after the sample"; and the clause was true then and is
true now. AC8's revert is what fixes the retry case, so it needs a test for it.

**4. RS05's "reconcile sweep skips matched rows" is true but understates it three ways.** The sweep
could not heal an unmatched row either — its own javadoc says it is "**Explicitly NOT auto-heal** … this
sweep only alerts" (`SubscriptionService.java:1149-1152`). It is **coach-only**
(`:1200` queries only `paymentCoachSubscriptionRepository`), so the identical player-side window has no
sweep at all. And `handleSubscriptionUpdated` does not itself mark the event processed — its **caller**
does, before dispatching (`StripeWebhookService.java:179`).

**5. RS08 reports the lesser half of its own defect.** The rejection is real, but outside the rejection
window the request is **accepted** and the pause begins `|offset|` hours before the day the parent picked,
so `findConflictingBookingsForPause` (`PackSessionService.java:556`) sweeps the previous evening's session
into the cancellation set. It silently cancels a booking on a day nobody paused. That is a money and
entitlement consequence, not the UX annoyance the `medium/edge-case` label suggests.

## Owner decisions (2026-11-04)

All three design forks raised at story creation are settled. The ACs below already encode them; this
section records the reasoning and the rejected alternatives so a later reader does not have to guess why.

**RS06 — persist the attempt server-side (option a).** A new migration (`V227+`; see Numbering) adds a pack attempt record, unique on
`(parent_id, attempt_key)`, written before the charge; replay returns the recorded outcome. *Rejected:*
frontend key retention alone, because it does not close the refunded-intent trap (AC2) and leaves dedup
dependent on browser state, so a reload or a second tab still double-charges. *Rejected:* phasing the two,
because the thing that makes retention safe is the server record itself, so the phasing would ship the half
that reads as fixed while the gap remains.

**RS07 — record for recovery, keep the 204 (option a).** A durable money-moved-no-entitlement row plus a
metric and an alert, following the `RefundReconciliationService` / `stripe_refund_failures` precedent
already used for packs. *Rejected:* auto-refunding the proration, because the subscription was revoked and
a refund may be the wrong answer while that revocation is itself disputed or being reversed, and because
the module's precedent is deliberately record-and-reconcile. *Rejected:* answering 202 and surfacing it in
the UI, because it is a two-endpoint contract change plus frontend and i18n work, and 202 invites a client
retry — which, with a per-attempt key (`SubscriptionService.java:1547`), charges a **second** proration.
Accepted cost, stated plainly: the user is still told the upgrade worked, and resolution is manual and
depends on someone acting on the alert.

**RS09 — revert to the previous card on failure (option a).** Restore the `:139` snapshot when
`chargeAndCapture` throws, idempotent across replays and safe against a concurrent save. *Rejected:*
passing the payment method into the gateway, which would remove the window entirely but changes
`StripePaymentGateway`'s contract — it re-reads from `stripe_customers` at `:138-145` — and so touches
every caller including the booking path, a materially larger blast radius than the rest of this bundle.
*Rejected:* also fixing `/save-payment-method` (`SubscriptionService.java:657-665`) — on the stated ground that the
endpoint follows a SetupIntent flow, **a premise the server does not enforce** (see the facts section below), so
this rejection now stands on scope alone. Accepted
cost: this is compensation, not prevention — a crash between the charge and the revert still leaves the
declined card saved.

**RS06 simplified — OWNER DECIDED 2026-11-04 (after the third `/mto-story-review`).** The third review showed that each prior fix had
added mechanism (a status model, a cross-attempt resolve-before-new guard, a minimum age, a scan, a two-commit protocol) and that the new
mechanisms interacted badly: resolve-before-new double-charged when it found a capture, it was a check-then-insert race, and nothing gave a
stuck attempt an exit. The owner chose to **simplify RS06 rather than split the story**: concurrency is enforced by **a partial unique index**
(one open attempt per parent) instead of by application logic; **there is no cross-attempt resolution on the request path** — an open attempt
is resolved only by a replay of its own key or by the scheduled job; and the job plus a self-clearing backlog gauge replace the log-tag scan.
*Rejected:* splitting into three stories (RS08 alone, RS06+RS09, RS05+RS07) — not rejected on merit, only deferred; it remains available if a
fourth review still finds high-severity defects. *Accepted cost, stated plainly:* a parent who has **lost the key** (page reload) while an
attempt is `UNKNOWN` cannot self-heal; they are refused with `attemptUnresolved` until the job resolves the row — at most the minimum age
(default 10 min) plus one job cycle (5 min) when Stripe holds a record, and the **absence age (default 30 min) plus one job cycle** when it holds nothing. A keyless parent has no key to
replay at all, and an unclassified pre-Stripe `RuntimeException` (e.g. a pool timeout before the charge) is `UNKNOWN` although Stripe was never reached, so the same wait applies there.

### Facts established after the decisions above (`/mto-story-review`, 2026-11-04, two passes)

The three decisions stand as chosen. These facts bear on them and the owner should be able to see them:

- **RS09's card-write is unreachable from the shipped UI.** The only caller passes `undefined` for the payment
  method (`SessionPackPurchasePage.vue:184`; `booking.store.js:309`; no other caller exists), and
  `getOrCreateStripeCustomer` writes only when the id is non-blank. AC8 protects direct API clients; for UI users the
  "declined card stays saved" problem arises through `/save-payment-method`.
- **The reason given for rejecting a `/save-payment-method` fix is not enforced by the server.** The text said that
  endpoint "follows a confirmed, customer-scoped SetupIntent so the card is already Stripe-authenticated".
  `SessionPackPaymentResource.savePaymentMethod` passes `request.paymentMethodId()` straight to
  `SubscriptionService.savePaymentMethodAndScheduleSync`, whose only guard is `StripeIdFormat.requirePaymentMethodId`.
  That is client convention, not a server guarantee.
- **RS06's server record needs more than "outcome recorded"** (AC1/AC2): a decline must not block a retry, a refund must
  be distinguishable from a capture, and "not found" at Stripe must not be treated as proof of no charge.
- **`stripe_refund_failures` cannot be the durable record for RS06's unknown outcome or RS07's proration**
  (`payment_intent_id`, `amount`, `parent_id`, `pack_tier_id` are all `NOT NULL`, `V138:1933-1944`), so RS07's
  "follow the existing precedent" means the *pattern* (record-and-reconcile), not that table.
- **RS09's revert cannot be made safe against two of its own edge cases** (AC8(d), (e)): a concurrent save of the *same*
  card, and two concurrent purchases by one parent with different cards sharing the single `stripe_customers` row.

**Still to confirm (applied with the recommended option, 2026-11-04) — OWNER TO CONFIRM:** (1) **the RS09 revert is kept**, with its two
stated limits (AC8); (2) **GDPR (AC13):** the attempt rows are retained like `stripe_customers` ("financial-record retention",
`GdprErasureService :805`) and erasure **nulls `previous_card`/`attempted_card`** — a policy call, since `GdprErasureService` has no table registry and
would silently skip the new tables; (3) **the scheduled job carries four duties** (resolve stale attempts, purge terminal attempts, drain linkable
parked events, purge never-linked parked events) under one lock.

## Acceptance Criteria

**AC1 — one open purchase attempt per parent; a retry cannot charge twice; a decline can still be retried.** *(OWNER DECIDED 2026-11-04, option a: persist the
attempt server-side; simplified the same day — see "RS06 simplified".)* A new table `payment.session_pack_purchase_attempts` (migration `V227+`, see Numbering) holds
one row per attempt: `parent_id`, `attempt_key` (the client's `Idempotency-Key`, or a **server-minted UUID when the header is absent** — so *every* purchase has a row),
`pack_tier_id`, `player_id`, `previous_card` and `attempted_card` (nullable payment-method ids), `customer_existed` (boolean), `payment_intent_id` (nullable), `status`,
`created_at`, `updated_at`. Two constraints, both created in the same migration as the (empty) table:

- `UNIQUE (parent_id, attempt_key)` declared inline in `CREATE TABLE`;
- **a partial unique index `ON (parent_id) WHERE status IN ('IN_PROGRESS','UNKNOWN','UNRECONCILED')`.** This index *is* the concurrency control: a second purchase by the same
  parent while one is open fails its insert, so two concurrent requests with different keys — or one keyed and one keyless — cannot both reach Stripe. Check
  `MigrationConventionLintTest`; if it flags the index, add `allow-blocking-index` immediately above it with the reason "table created in this migration, empty".

Statuses (open = the three in the index; terminal = the other three):

| status | meaning | a same-key replay answers |
|---|---|---|
| `IN_PROGRESS` | row written, charge not yet resolved | younger than the minimum age → `409 payment.attemptInProgress`; older → resolve via AC2 on this thread |
| `SUCCEEDED` | charged and pack recorded | the recorded pack (`replayedPurchase`) — answered from the DB, never by calling Stripe |
| `NO_CHARGE` | proven no money moved | the no-charge signal (AC1b); the client mints a fresh key |
| `REFUNDED` | captured then refunded | the no-charge signal (AC1b) |
| `UNKNOWN` | Stripe outcome not known | resolve via AC2 if older than the minimum age, else `409 payment.attemptUnresolved`; keep the key |
| `UNRECONCILED` | captured, pack not recorded, refund failed or unknown | as `UNKNOWN` |

**There is no cross-attempt resolution on the request path.** An open attempt is resolved only (a) by a replay of **its own key** past the minimum age, or (b) by the scheduled
job (AC3). A different request from the same parent is simply refused. This removes the interaction the third review found (resolving an earlier attempt, finding it captured, then
charging the retry as well).

**Every status write is a compare-and-set** — `UPDATE … SET status = :new WHERE id = :id AND status IN (:expected)`. If it affects no row, re-read and never overwrite a terminal state.
The single exception is proof of capture: a writer holding a `succeeded` PaymentIntent id may move `NO_CHARGE → SUCCEEDED` (or `UNRECONCILED`), because a captured fact beats a resolver's
*inference from absence*; log it at `error` with the tag `[PACK_PURCHASE_CAS_LOST] key=value`.

**Minimum age.** At least **10 minutes**, read from a `ConfigBounds` key (default 10, bounds 5-1440), matching the values (not the mechanism — it is a plain string key passed to `getBoundedLong`, not a `BoundedKey`) of `ChargeOutcomeReconciler`'s
`booking.payment.charge_reconcile_after_minutes`. A legitimate request can run 6-8 minutes: the SDK uses a 10 s connect and 30 s read timeout with 2 network retries
(`PaymentConfig :56-60`, ~2 min per Stripe call worst case) and a purchase makes 2-4 sequential calls (`createStripeCustomer` for a first-time payer, the charge, the lookup, the
compensating refund).

**AC1a — ordering inside `purchasePack`.** The gates currently run on every call (`:100-135`), so the order is fixed:

1. key-format check; payment-method format; player ownership and age verification (security gates, unchanged);
2. **if a key was sent, look up `(parent_id, attempt_key)`** and answer per the table — *before* the tier and coach gates, so a replay can still resolve a captured payment after the
   tier was deactivated or the coach suspended (`packTierInactive` `:128`, `requireBookableCoachStatus` `:135`). A stored `pack_tier_id`/`player_id` that differs from the request:
   **open row → `409 payment.attemptUnresolved`** (not `idempotencyKeyReused`, which would trap the user after a pack change); **terminal row → the existing `payment.idempotencyKeyReused`**;
3. only for a **new** attempt: the tier-active and coach-status gates (and `skillars-deferred-170`'s readiness check, once it merges);
4. read the `previous_card` snapshot and `customer_existed` (`:137-139`), then **insert the row** in its own committed transaction. A violation of the open-per-parent index → read the
   open row and answer `409 payment.attemptInProgress` (if `IN_PROGRESS`) or `409 payment.attemptUnresolved`; a violation of `UNIQUE (parent_id, attempt_key)` (concurrent same key) → read the
   winner and answer per step 2. Both are `23505`: **re-read by `(parent_id, attempt_key)` first** to tell them apart (found → same-key winner; not found → the open-per-parent index);
5. the card write (`:140`), then the charge (`:144`).

An **absent** `Idempotency-Key` gets a server-minted key (`:143` already mints one today): the row still serialises purchases and carries `previous_card`, but there is no client dedup — this
is today's behaviour, logged. Requiring the header is out of scope.

**Failures inside `chargeAndCapture` are not "pre-charge".** `charge()` throws `payment.coachStripeNotConfigured` (call `:116`, throw `:635`), `payment.configurationUnavailable`
(`:121-124`), `payment.noStripeCustomer` and `payment.noPaymentMethod` (`:138-143`) **after** the row exists and before any PaymentIntent is created; `getOrCreateStripeCustomer`
(`:140`) can fail after it too. All mark the row `NO_CHARGE`. **An unclassified `RuntimeException`** (a pool timeout, an NPE) from `chargeAndCapture` is treated as `UNKNOWN`, not left
`IN_PROGRESS` — it may have been thrown after Stripe captured. A failed `SUCCEEDED` write must never fail a response for a pack that exists.

**Retention.** Terminal rows are purged after a `ConfigBounds`-keyed retention window (default ~30 days) by the job in AC3.

**AC1b — the client can tell "no money moved" from "money may have moved".** A card decline is a 4xx that `charge()` maps to
`PaymentGatewayException("payment.lifecycleFailure")` (`StripePaymentGateway.java:185-187`; a non-succeeded intent at `:198-201`) — the **same code and the same 422** as the refunded and
unreconciled branches, so today's responses cannot carry the distinction. **Mechanism:** `purchasePack` classifies itself. Any plain `PaymentGatewayException` from `chargeAndCapture` that is
not a `ChargeOutcomeUnknownException` means no money moved (the capture path returns an id or throws before capture); the service marks the row `NO_CHARGE` and rethrows a **pack-service
subclass with its own code** (e.g. `payment.noChargeMade`). `REFUNDED`/`UNRECONCILED` are thrown from the service (`:171`, `:185`) and never reach the gateway. **Do not change the gateway's
decline code** — it is pinned by `StripePaymentGatewayTest:865-872`, and `charge()` is shared with bookings (`PaymentLifecycleService :259/:266` and `:473/:480` catch the base class;
nothing branches on `payment.lifecycleFailure`; `PaymentApiAdvice:20` keys only on `coachStripeNotConfigured`), so a subclass thrown only from the pack service leaves every booking caller untouched.
`REFUNDED` returns the same signal though money *did* move and was refunded — the UI copy must not say "nothing was charged".

**Wire contract (the page reads a field that does not exist today).** `SessionPackPurchasePage.vue:189` reads `e.response.data.message`, but `ErrorDto` is
`{helpCode, errorMsg:{errorKey, message}, fieldErrors}`, so the parent never sees any payment code. A `PaymentGatewayException` (422) carries the code in **`errorMsg.errorKey`**; a
`ResponseStatusException` (`payment.invalidIdempotencyKey` 400, `payment.idempotencyKeyReused`, `payment.attemptInProgress`, `payment.attemptUnresolved` — 409s) goes through
`ApiAdvice.responseStatusExceptionHandler` (`:132-136`) and carries it in **`errorMsg.message`** with `errorKey = generic.requestError`. The page reads each from its own field, and gains
i18n strings for the two new 409s ("your previous purchase is still being confirmed").

**Client rules.** Keep `attemptKey ??=` (`:182`) and the in-flight guard (`:179`). **Clear** the key on success, on the no-charge signal, on `400 payment.invalidIdempotencyKey` and on
`409 payment.idempotencyKeyReused` (terminal row, so safe). **Keep** it on a network error and on `attemptInProgress`/`attemptUnresolved`/unknown outcomes, and show the "still being confirmed"
message. On a **card save** (`onCardSaved`, `:138-140`) or a **pack change** (`watch(selected)`, `:174`) clear the key **only if the last answer was the no-charge signal** (or there was none).
These are UX rules — the partial unique index is the safety guarantee. (Saving a different card under the same key changes the PaymentIntent's `payment_method`, which Stripe rejects as an
idempotency mismatch that `isOutcomeUnknown` classifies as unknown; retaining the key across a *decline* would replay Stripe's cached decline. Both documented Stripe behaviour, not exercised in
review — pin them with a test.)

**AC2 — an open attempt is resolved from Stripe, by exactly these rules.** Used by a same-key replay past the minimum age and by the job (AC3) — **nowhere else**:

- **Existing pack first.** Call `findByStripePaymentIntentId` (as `:148` does) and go through `replayedPurchase`. `createPurchase` (`:457`) is a bare `save`, so the process can die after it and before the
  `SUCCEEDED` write; without this check the resolution would create a second pack for the same intent.
- **No customer row → `NO_CHARGE`.** `charge()` throws `payment.noStripeCustomer` (`StripePaymentGateway :138-139`) *before any Stripe call*, so nothing could have been charged. The gateway's
  `findChargeByIdempotencyKey` returns empty for both "no customer row" and "not found" (`:276-279`), so **the caller must re-query `stripeCustomerRepository` itself**.
- **Find the intent:** `findChargeByIdempotencyKey(parentId, key, createdAfter)` (`StripePaymentGateway.java:274`) with **`createdAfter = attempt.created_at − slack`** (widen `ChargeOutcomeReconciler.LOOKUP_SLACK`,
  `private static final`, 10 min, `:87`, or duplicate it). It lists the payer's PaymentIntents via `StripeClient.listPaymentIntentsForCustomer` (`limit(100)`, auto-paged).
- **Classify what it finds**, in this order (an **invariant** — with a test for a `requires_payment_method` intent that never reaches `getRefundableAmount`): `pending()` (`processing`,
  `requires_capture`) → stay `UNKNOWN`; found but **not** `succeeded` (a decline leaves a `requires_payment_method` PaymentIntent behind) → `NO_CHARGE`; `succeeded` → call
  **`getRefundableAmount`** (`PaymentGateway.java:107`, impl `:541-562`, captured − refunded — `ChargeLookup` has no refund state, a refunded PaymentIntent is still `succeeded`): zero → `REFUNDED`,
  otherwise captured. **A zero is ambiguous (fourth review, M3):** `getRefundableAmount` returns `ZERO` when `getLatestChargeObject()` is null (`StripePaymentGateway :541-552`), which would record captured
  money as `REFUNDED` with no pack. Do not change that method (cash-outs share it). Add a pack-path method that returns captured **and** refunded amounts (or a "charge not expanded" signal): `REFUNDED` only
  when `captured > 0 && refunded >= captured`; a succeeded intent with no charge object stays `UNKNOWN` and logs at `error`. Test it. A *partially* refunded intent counts as captured and the pack is created (log it) — **a policy call, flagged**.
- **"Not found" is not proof, and a request thread may still be alive (fourth review, H1).** Inferring `NO_CHARGE` from *absence* while the original request is still running frees the
  index, lets the parent start attempt B, and then A's thread captures and creates a second pack. So **one rule, no second one**: an *absence* verdict needs a **successful** lookup that finds
  nothing **and** `age ≥ absence age`, a second `ConfigBounds` key (**default 30 min, bounds 15-1440**, never below the minimum age) — comfortably beyond the worst-case life of a request
  thread (each Stripe call is bounded by the SDK timeouts above; a thread that outlives 30 min is a stalled pod, and a killed pod has no thread). Below the absence age the row stays
  `IN_PROGRESS`/`UNKNOWN`. A *positive* finding (an intent exists) is acted on from the minimum age as before. A failing lookup (`payment.chargeLookupFailed`, `payment.refundLookupFailed`,
  `REFUND_TARGET_INVALID`) stays `UNKNOWN`. There is no separate 24 h terminal rule. **Proof-of-capture CAS that violates the index:** `NO_CHARGE → UNRECONCILED` can throw on the partial
  unique index when a newer attempt is open — catch it, log at `error` with `[PACK_PURCHASE_CAS_LOST] key=value`, leave the row `NO_CHARGE`, and rely on the `stripe_refund_failures` row and that log
  as the record (the gauge does not see it; accepted). `NO_CHARGE → SUCCEEDED` is not in the index and cannot throw. Add an IT for the stalled-thread interleaving.
- **Then act:** `REFUNDED` → mark it, answer the no-charge signal, create **no** pack; captured and not refunded → create the pack (once; `uq_spp_stripe_payment_intent_id` backstops it), **resolve the open
  `stripe_refund_failures` row** (`resolved_at`) so an operator does not later refund a funded pack, and **run `scheduleSubscriptionCardSync`** (`:189`'s original success path never ran) using the row's
  `customer_existed`, `previous_card` and `attempted_card`; `NO_CHARGE` → run the AC8 revert **first**, then mark it.
- **Exits for a row that cannot resolve (fourth review, M2).** The index covers *every* tier of *every* coach, so a stuck open row blocks the parent entirely. (a) If the job resolves a row as captured
  and `createPurchase` then fails, it **never refunds** (the request-path catch's compensating refund would refund a funded pack): it logs `error`, leaves the row open and retries next cycle. (b) A lookup that
  fails forever leaves the row `UNKNOWN` and is the gauge's job to surface. (c) A row that needs a human is closed by an operator after acting in Stripe: set `status` to `REFUNDED` (money returned) or
  `SUCCEEDED` (pack exists) by SQL, as documented in the runbook in `monitoring.md` (AC3). No new status; no admin UI (AC12 defers a reconciler).
- **Transitions out of the `:154-186` catch** are specified: refund succeeded → `REFUNDED`; refund failed or its lookup failed → `UNRECONCILED`; winner found → `SUCCEEDED`. The row is updated **before** the exception propagates.

This closes the crash between the compensating refund (`:177`) and the outcome write: that crash leaves `IN_PROGRESS`, and with the same key Stripe returns the original (refunded) intent, `:148` finds no
pack, and `createPurchase` would otherwise succeed with refunded money. `createPurchase` is never run against an unverified intent.

**AC3 — a scheduled job resolves stale attempts, and a self-clearing gauge tells an operator when it cannot.**
1. **One ShedLock job, every 5 minutes** (sized per `docs/deployment/scheduler-lock-tuning.md`; non-`@Transactional`, calling transactional collaborators, per
   `SchedulerLockTransactionOrderingIT`), with four duties, **each in its own `try/catch`** so one failing cannot starve the others: (a) resolve `IN_PROGRESS`/`UNKNOWN`/`UNRECONCILED` attempts older than
   the minimum age via AC2; (b) purge terminal attempts past retention; (c) drain parked webhook events whose subscription is now linked (AC4); (d) purge never-linked parked events past retention (AC4).
2. **The alert is a backlog gauge, not a log tag.** Add a branch to `OperationalBacklogMetrics` — a DB-derived `MultiGauge`, refreshed per JVM and aggregated with `max`, so there is no per-pod staleness and it clears itself
   when the backlog does — reporting `backlog="pack_attempts"`, `detail=<status>`, the count and oldest age for open attempts; and a Grafana alert mirroring the existing
   `max(skillars_backlog_oldest_age_seconds{backlog="cash_outs",detail="UNKNOWN"}) or vector(0)` rule (`grafana-alerts.yml :771`), with a threshold above *minimum age + two job cycles*. This also finds the case nothing else
   does: the pod is killed mid-request (a rolling deploy makes that likely), Stripe may have captured, nothing was logged, and the row sits `IN_PROGRESS`. Runbook in `docs/deployment/monitoring.md`, **including the operator close-out for a row that cannot resolve** (AC2 "Exits": set `status` to `REFUNDED` or `SUCCEEDED` by SQL after acting in Stripe).
3. **Diagnostics, not alerts.** Where `purchasePack` catches `ChargeOutcomeUnknownException` at `:144`: **log first**, then mark the attempt `UNKNOWN`, then rethrow. Log with
   `[PACK_PURCHASE_CHARGE_OUTCOME_UNKNOWN] key=value`. **Tags must be written `[TAG] key=value`**: the `MoneyIntegrityEvent` expression matches `\[(TAG1|TAG2…)\]`, so `[TAG key=value]` never matches
   (`grafana-alerts.yml :638-648`). This tag is for the dashboard panel (`deploy/lgtm/dashboards/payments.json :1314`), **not** the alert — a timeout or `processing` response is routinely resolved by the
   job or a replay with no operator action, and a log alert cannot be cleared. **Do not** add the shared gateway tag `STRIPE_CHARGE_OUTCOME_UNKNOWN` to any alert: it is logged from the shared `charge()` that
   bookings also use, where `ChargeOutcomeReconciler` self-heals it, and for a Stripe 409 idempotency conflict.
4. The durable record is the attempt row, **not** `stripe_refund_failures` (`NOT NULL` PaymentIntent columns; `ChargeOutcomeUnknownException` carries only the idempotency key, `:19-28`).
   `AlertDocumentationParityTest` matches alert titles, so a new alert needs its `monitoring.md` heading — run it.

**AC4 — a subscription event that races the signup is applied, not dropped — coach and player, `updated` and `deleted`.** When `handleSubscriptionUpdated`
(`StripeWebhookService.java:271-275`) or `handleSubscriptionDeleted` (`:356-370`, which today returns silently with not even an alert) finds no local row, the event is **parked** in a new table
(`payment.stripe_pending_subscription_events`: `event_id` **`UNIQUE`**, `stripe_subscription_id`, `event_type`, `event_created`, and the **already-extracted** fields — status, period end, cancel flag —
not raw JSON, because `buildSubDataMap` is private to `StripeWebhookService`, `:499`, and the drain runs in `SubscriptionService`). **No FK** to `stripe_webhook_events` (its marker row is uncommitted when the park runs).

*The protocol, with one consistent structure.* Under READ COMMITTED (no override) re-reading inside the webhook's own transaction cannot close the race: webhook W reads no row, inserts the park (uncommitted),
re-reads and still sees no link, then `persistCoachSubscription` P commits the link and drains (finding no park), then W commits — an orphaned event. So:

1. **W parks in its own transaction that commits *before* W's outer transaction** — a `REQUIRES_NEW` call on a **separate bean** (or `self` proxy; in-class self-invocation silently ignores `REQUIRES_NEW`, as recorded in
   `OutboxService` and `PendingProviderAssetTracker`), `INSERT … ON CONFLICT DO NOTHING` on `event_id` (an outer rollback after a committed park leads to redelivery and a re-park);
2. **after W's outer transaction commits** — in `processWebhook`, which is not transactional (`:116-122`) — W **re-reads for the local row** and, if present, applies the event in its own transaction. `handleEventAtomically`
   is `void`, so it must return a signal that an event was parked. This step runs **even when the park insert failed** (the link may already exist);
3. **P commits the link, then drains** parked events for that id.

Either W's park commit precedes P's drain read (P sees the park), or P's drain read precedes W's park commit — in which case P's link commit already precedes W's re-read (W sees the link). (Alternatively a shared
`pg_advisory_xact_lock(hash(stripe_subscription_id))` in both.) Add the interleaving as an IT.

*Failure semantics.* A failed park is **logged and dropped** (today's behaviour) — it must not roll back `insertIfAbsent` and put Stripe into indefinite retry (AC5). The post-commit step is wrapped in `try/catch` and logs:
`StripeWebhookResource :75-78` returns 500 on any exception, Stripe redelivers, and `insertIfAbsent` returns 0 so `handleEventAtomically` returns as a duplicate (`:180-183`) — the event is **never re-applied by redelivery**.
It is not lost, because the committed park row survives and **the job's drain (AC3(c)) recovers it; the job cadence is the recovery latency.** A row is claimed with **`DELETE … RETURNING` in the same transaction as the
apply**, so the request-thread drain, the post-commit step and the job apply each event exactly once.

*Nested connections.* The park (`REQUIRES_NEW`) runs while the outer webhook transaction holds a connection. The repo added a dedicated routed pool for exactly this shape —
`DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY` (pool 10), selected by `RoutingDataSourceContext.set(...)` in a **non-transactional wrapper before the proxy opens the connection**
(`BookingPaymentPersistenceService :132-136`). Use it; with the primary pool at 25, ~25 concurrent webhooks could otherwise exhaust it.

*Drain.* After commit — never inside `persistCoachSubscription`/`persistPlayerSubscription` (`@Transactional`, `SubscriptionService.java:235-270`, `:489-`), where an exception would roll back a persist after Stripe has
billed (`:259-266`: "the worst available outcome"). Call it in `subscribeCoach`/`subscribePlayer` after the `isEmpty()` check, in its own `try/catch` (`:185-193`, `:450-458`); only the non-empty branch drains (an empty one
cancels a duplicate and never links). Oldest `event_created` first, each event through `subscriptionService.handleSubscriptionWebhook` (`:1220-1238`), which runs `isStaleEvent` in the `updated` branches and sets `lastStripeEventAt` through
`newer(...)` in every branch (so the drain need not). **`handleSubscriptionDeleted` (`SubscriptionService :1272-1285`) does not call `isStaleEvent`** — a delete is final whatever its order, so a parked
`deleted` is applied even if older than an already-applied `updated`; that is deliberate, state it in a comment and test it. Stop after a `deleted`.

*Retention.* Never-linked rows exist by design: `cancelDuplicateSubscription` (`:190`, `:453`, `:1522`) cancels unlinked duplicates. The job (AC3(d)) purges them after a `ConfigBounds`-keyed window, logging `warn` and
incrementing a metric for any row purged unapplied; a row deleted by a successful apply is not counted. `invoice.paid` (silent no-op without a row) and `invoice.payment_failed` have the same shape and are **out of scope** —
declared here and filed (AC12).

**AC5 — AC4 must not be bought by making the orphan path louder, or by touching the sweep.** `SUBSCRIBE_RACE_GRACE_WINDOW`
(`StripeWebhookService.java:75`) stays as it is; the orphan path still must not throw (`:287-293`, `:451-454`); `insertIfAbsent` stays **before** dispatch
(`:179`); and `reconcileStripeSubscriptions` (the daily sweep) is unchanged, so `SubscriptionServiceStripeReconciliationIT :91`/`:112`, which exercise the sweep,
stay green untouched.

**AC6 — a paid proration that cannot be granted is recorded and surfaced, and the record cannot fail the request.** *(OWNER DECIDED 2026-11-04, option a:
record and keep the 204.)* The superseded **upgrade** branches (`SubscriptionService.java:341-346` coach, `:577-580` player) stop being a bare `log.warn` +
`return`. In order: (1) **log at `error` first with a stable tag written `[SUBSCRIPTION_UPGRADE_UNGRANTED] key=value`** (the `]` must follow the tag directly or the Loki expression will not match) — the alert source, which works even if the insert fails — and increment a metric; (2) insert a row into a
**new table** (`payment.subscription_upgrade_reconciliations`: `subscriber_kind` COACH/PLAYER, `subscriber_ref` text, `stripe_subscription_id`, `target_tier`,
**`latest_invoice_id`**, `created_at`, `resolved_at`; **no FK** to the subscription tables). `persist*TierUpgrade` already receives the `Subscription updated`, and
`StripeClient` already uses `sub.getLatestInvoice()` (`:356`, `:415`), so the proration's invoice id is available (the first draft's "no identifier is available"
was wrong) and gives an operator something to find in Stripe. The insert is **best-effort**: wrap it so that a failure is logged and swallowed — and run it, like AC4's park, in a `REQUIRES_NEW` on the payment routed pool
(`PAYMENT_REQUIRES_NEW_DATASOURCE_KEY`, set by a non-transactional wrapper on a separate bean) because the outer `persist*TierUpgrade` transaction already holds a connection and the row lock — because a
same-transaction insert that throws would roll back the (otherwise empty) `persist*TierUpgrade` transaction after Stripe has billed, the endpoint would error, and a
client retry is a *new* upgrade key — a second proration (AC7). Because the table has **no FK**, a `REQUIRES_NEW` insert inside that `try/catch` does **not** deadlock on the
`FOR UPDATE` held at `:341`; the deadlock the module's carried constraint warns about needs an FK to the locked row, so keep the table FK-free and say why in a comment.
The tag joins the `MoneyIntegrityEvent` regex and runbook. Only the **upgrade** branches record (money moved); the downgrade and cancel warn-and-returns
(`:366-369`, `:407-410`, `:594-597`, `:638`) charge nothing and are unchanged. The tier must **not** be granted anyway: a row whose Stripe link is null or cancelled would re-grant a
paid tier with no subscription, which PR #337 (`eea53ee9`; the `fix/payment-subscription-entitlements` label in the comments `:414-416`, `:1385-1387`) closed.
**Not covered, stated so the dev does not chase it:** `persist*TierUpgrade` can also throw `payment.subscription.notFound` (`:342`/`:576`, e.g. after a GDPR scrub), and a DB
error after the Stripe update leaves Stripe at the new tier and the DB at the old; the retry is the same price, so its proration is probably a no-op.

**AC7 — AC6 must not cause a second proration.** The upgrade idempotency key is per-attempt (`keyPrefix + UUID.randomUUID()`, `SubscriptionService.java:1547`, prefix
`"sub-upgrade-"` passed at `:305`), so any client retry is a new key and a new charge. The endpoint keeps answering 204; **no failure of the AC6 record may surface as an
error** (above); and the Stripe call stays outside the DB transaction (`:353-357` — a rollback after Stripe has billed would leave Stripe at the new tier and the DB at the old one).

**AC8 — a declined card does not remain the payer's saved default.** *(OWNER DECIDED 2026-11-04, option a: revert to the snapshot.)* When the charge ends in a proven no-charge
outcome (`NO_CHARGE`), the `stripe_customers` default is restored to the previous card. **Mechanism, because a naive revert is wrong:**

- **A conditional column update**, never load-modify-save: `UPDATE … SET stripe_payment_method_id = :previous WHERE parent_id = :id AND stripe_payment_method_id = :attempted`.
  `StripeCustomer` has no `@Version` (`StripeCustomer.java:19-32`) and the gateway saves the same entity (`setLastPaymentIntentId`, `StripePaymentGateway.java:209-211`, success path only), so
  a merge would clobber it. Declare it `@Modifying(flushAutomatically = true, clearAutomatically = true) @Transactional @Query` — `purchasePack` is non-transactional, so a bare
  `@Modifying` method throws `TransactionRequiredException` (house precedent: `StripeOAuthStateRepository:18-27`). `NativeModifyingVersionAuditTest` is not tripped (no `@Version`).
- **Where the snapshot lives:** `previous_card` and `attempted_card` on the attempt row (AC1) — which now exists for **every** purchase, keyed or keyless, so a replay-, job- or request-driven revert has both values.
  Skip the revert when `attempted_card` is null **or blank** (a first-time payer's new row stores the id as given, `getOrCreateStripeCustomer :450-453`, so the card write is a no-op only for an *existing* row; the shipped UI always sends `undefined`; `= :attempted` would never match, so it is a harmless no-op — make it explicit).
- **Order:** revert **first**, then write `NO_CHARGE`. The other way round, a crash leaves the declined card saved permanently, because a replay never rewrites the card.
- **No revert on `UNKNOWN`/`UNRECONCILED`** (the charge may have captured, and a revert would desynchronise the default from the card actually used); for a first-time payer restore to
  `null` and keep the row (the Stripe customer exists; null is handled at `getSavedPaymentMethod:409` and `StripePaymentGateway.java:141-143`).
- Must not enqueue a card sync (the post-charge gate at `:188-190` exists so a declined card is never pushed onto live subscriptions — keep it).
- **Reachability and accepted limits, stated so the guarantee is not over-read** *(the first draft said "safe against a concurrent legitimate save"; that is false for these)*: (d) if the parent saves the **same**
  card X through `/save-payment-method` while a pack attempt with X is declining, the column equals the attempted card and the revert undoes a legitimate save; (e) two concurrent purchases by one parent
  with different cards share the single `stripe_customers` row — and because the gateway re-reads it, one can charge the other's card (pre-existing) — so the reverts can leave a declined card saved.
  Both are rare and API-only.
- **Tests:** decline with X, retry with X succeeds, and the sync *fires* (correction 3); a first-time payer's retry after the revert has `customerExisted == true` and `previousCard == null`, so the
  sync now fires where the first attempt would not — harmless, and the test should expect it.

**AC9 — AC8 does not break paying with a different card.** `StripePaymentGateway.charge` **re-reads** the card from `stripe_customers` (`:138-145`) and has no payment-method parameter, so the
pre-charge write is the transport for the new card. Removing or deferring it would break pack purchase with a new card entirely and yield `payment.noPaymentMethod` for a first-time payer.
`purchasePack` must stay non-`@Transactional` (javadoc `:78`; the compensating-refund design at `:154-186` depends on it). The revert is idempotent across replays (`:148-151`, `:173-175` re-enter the method).
*Reachability, stated so nobody over-reads AC8:* the shipped UI never sends a payment method here (see the facts section). Record the `/save-payment-method` door (`SubscriptionService.java:657-665`) in
Completion Notes as deliberately out of scope.

**AC10 — the pause start date the picker offers is accepted, and means the day the parent picked.** `SessionPackDashboardPage.vue:313` sends local midnight rather than UTC midnight, so the earliest
offered date is never refused by `PackSessionService.java:545` and the pause no longer begins on the previous evening for users west of UTC. **Out of scope, declared so it cannot inflate:** whether a
pause day belongs to the coach's calendar zone instead of the parent's — it needs a timezone field on `SessionPackPurchaseResponse`, its mapper and backend tests, and is a policy decision rather than a bug fix.

**AC11 — the eval cases.** The four existing cases (`337-subscription-entitlement-integrity.yaml` RS05, `350-pack-refund-rounding-remainder.yaml` RS06, `359-charge-idempotency-unknown-outcome.yaml` RS07,
`368-pack-status-badges.yaml` RS08) **already carry their finding with `fix_pr` and `fix_merge` set** (F12, F3, F10, F4) — nothing to add. The only edit is RS09's draft
`evals/review/cases/_drafts/pending-card-default-before-charge.yaml`, per `evals/review/README.md:117-120`: **in this PR** set `fix_pr` to the PR number, keep `input_rev`, and promote it to `cases/`
**without the `pending-` prefix, with `id` equal to the new filename** (`evals/review/bin/validate.py:64` rejects a mismatch). The PR number is only known once the PR exists, so this is a follow-up commit.
**After the squash merge**, add `fix_merge: <squash sha>` and drop `input_rev` (`README.md:121`) — a post-merge chore for Completion Notes. The draft's `F1` title and description are **correct**
(correction 3) — leave them alone. Do not change the anchors; `chargeAndCapture` is declared in `StripePaymentGateway` as well as being called from `SessionPackPaymentService`.

**AC12 — the ledger.** Per `deferred-work.md`'s own rule, **delete RS05-RS09 outright** once implemented; do not spend effort correcting entries that are being deleted. File new entries for what this story
deliberately leaves: automated reconciliation beyond the job (a pack-specific reconciler for `UNRECONCILED` rows that need a human); `invoice.paid` / `invoice.payment_failed` with no local row; the
`/save-payment-method` door; requiring the `Idempotency-Key` header; `persist*TierUpgrade` failing after a successful Stripe update (`payment.subscription.notFound`, DB error); and the partial-refund policy (AC2); a linked parked webhook event that throws on every apply (retried every cycle, blocks later events for that subscription, no alert); the "purged unapplied" metric firing on the expected `cancelDuplicateSubscription` path (log that case at `info`, not `warn`).

**AC13 — docs, registrations, config, erasure.**
- `docs/dev-docs/payment/index.html` (+ rebuilt `docs/dev-docs/assets/search-index.js`, via `node docs/dev-docs/tools/build-search-index.mjs` — never by hand) for the attempt record, the parked-event path and the
  upgrade reconciliation table, **including the "Last verified against master SHA (date)" footer stamp that `check-docs` requires**; `docs/deployment/monitoring.md` for the new alert and the AC6 tag;
  `docs/deployment/provisioning-checklist.md` for the migrations (§0), the new job (§6 "Scheduled jobs and ShedLock") and the alert. `check-docs` must pass.
- **Config.** Each of these is a `ConfigBounds` `BoundedKey` constant **plus** an entry in `ALL` (**plus** `HAS_CODE_DEFAULT`, or a seed row in a migration if there is no code default): the minimum age (default 10, 5-1440), the **absence age** (default 30, 15-1440, never below the minimum age; AC2), the
  attempts retention (default ~30 days), the parked-events retention. `ConfigStartupAssertion` consumes `ALL` at boot.
- **Scheduler registration.** Follow `SubscriptionSchedulerLockTest` and `ConfigStartupAssertionTest`; there is no hard-coded scheduler count. If any new code calls `lockRetryer.withBoundedRetry(`, bump
  `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT` (67).
- **Erasure (OWNER TO CONFIRM).** `GdprErasureService` has no table registry — it is imperative and retains financial rows (`:805`), and would silently skip the new tables. Retain the attempt rows (financial record) and
  have erasure **null `previous_card` and `attempted_card`**, with an IT. `previous_card`/`attempted_card`/`parent_id` are payment-method ids tied to a person.
- `docs/deployment/migration-conventions.md` rule 1 says a new table ships a release before any code uses it. `MigrationConventionLintTest` does not enforce it and deferred-167/168 shipped table and code together;
  acknowledge it in the migration header rather than silently diverging.

**AC14 — `/pr-review` on the final commit.** Run it, fix what it finds, run it again. Deferred-167 closed this at seven rounds by owner decision rather than on a clean round; if this one is still finding behaviour
defects after five, raise it rather than looping silently.

## Tasks / Subtasks

- [ ] Task 0: confirm this branch is current (`git fetch`, merge `origin/master`), and **re-derive the migration number** (Numbering); re-locate anything not listed there by symbol; check whether `skillars-deferred-170` has started and agree the `purchasePack` ordering
- [ ] Task 1: RS06 attempt record and index (AC1, AC1a)
  - [ ] Migration for `payment.session_pack_purchase_attempts` — additive; inline `UNIQUE`; the **partial unique index** on open attempts per parent; `SET LOCAL lock_timeout`; follow `migration-conventions.md`;
        `mvn -q -Dtest=MigrationConventionLintTest test` (add `allow-blocking-index` only if it flags the index, with the stated reason)
  - [ ] Order in `purchasePack`: gates → key lookup → tier/coach gates (new rows only) → snapshot → insert (server-minted key if absent) → card write → charge; the two 409s and the open-vs-terminal mismatch rules
  - [ ] Compare-and-set on every status write; the proof-of-capture exception for `NO_CHARGE`; unclassified `RuntimeException` → `UNKNOWN`
  - [ ] Pre-flight rejections inside `charge()` and `getOrCreateStripeCustomer` failures → `NO_CHARGE`; a failed `SUCCEEDED` write never fails the response
  - [ ] ITs: concurrent different keys (the index refuses the second); concurrent same key; a keyless request while a keyed one is open; replay after the tier is deactivated; a stale CAS
- [ ] Task 1b: AC2 resolution (same-key replay and the job)
  - [ ] Existing-pack check first; no-customer-row → `NO_CHARGE` (re-query the repository); `createdAfter = created_at − slack`; classification order as an invariant (a `requires_payment_method` intent never reaches `getRefundableAmount`)
  - [ ] Terminal rule after the key window; minimum age from a `ConfigBounds` key; resolved-captured runs `scheduleSubscriptionCardSync` and resolves the `stripe_refund_failures` row; transitions out of the `:154-186` catch
  - [ ] Absence rule: `NO_CHARGE` from an empty lookup only at `age ≥ absence age` (second `ConfigBounds` key); null-charge `succeeded` intent stays `UNKNOWN`; job never refunds on a failed `createPurchase`; operator close-out in the runbook
  - [ ] ITs: stalled original thread (A captures after the job's absence verdict and B is open); a CAS to `UNRECONCILED` that hits the index; crash between refund and outcome write; replay of `UNRECONCILED`; "not found" inside the minimum age stays `UNKNOWN`; partial refund
  - [ ] Extend `StubPaymentGateway` (`src/test/.../config/StubPaymentGateway.java` — it never declines and `findChargeByIdempotencyKey` returns empty); mock the new repository in `SessionPackPaymentServiceTest`
        (`@InjectMocks`); add the new spies as **shared fields** in `AbstractIntegrationTest` (a per-class spy changes the context key; ceiling 17); backdate `created_at` via `commitWrite` (`ClockProvider` does not reach server threads)
- [ ] Task 2: RS06 no-charge signal + frontend (AC1b)
  - [ ] Pack-service subclass/code for no-charge; gateway decline code **unchanged** (`StripePaymentGatewayTest:865-872`)
  - [ ] `SessionPackPurchasePage.vue`: read `errorMsg.errorKey` (422) and `errorMsg.message` (409/400); clear/keep rules per AC1b; i18n for the two new 409s; `:188` stops clearing on every `e?.response`
  - [ ] **`SessionPackPurchasePageSpec.js:61-71` asserts the defect** and changes deliberately; add: key kept on an unknown outcome and on `attemptUnresolved`, cleared on the no-charge signal and on `idempotencyKeyReused`
- [ ] Task 3: the job, the gauge and the diagnostics (AC3)
  - [ ] One ShedLock job, 5-minute cadence, four duties each in its own `try/catch`; `lockAtMostFor` sized per `scheduler-lock-tuning.md`; non-`@Transactional`
  - [ ] `OperationalBacklogMetrics` branch (`pack_attempts`) + a Grafana alert mirroring `grafana-alerts.yml :771` + the `monitoring.md` heading; run `AlertDocumentationParityTest`
  - [ ] Log-first catch at `:144`; `[PACK_PURCHASE_CHARGE_OUTCOME_UNKNOWN] key=value` for the dashboard panel only
- [ ] Task 4: RS05 park-and-reapply (AC4, AC5)
  - [ ] Migration for `payment.stripe_pending_subscription_events` (`UNIQUE(event_id)`, no FK)
  - [ ] Park in `REQUIRES_NEW` on the routed pool via a separate bean, `ON CONFLICT DO NOTHING`; `handleEventAtomically` returns a signal; post-commit re-read in `processWebhook` (also when the park failed), `try/catch`; `DELETE … RETURNING` claim in the apply transaction
  - [ ] Coach **and** player (`subscribePlayer:422-460`, `persistPlayerSubscription:489-`), `updated` **and** `deleted`; store extracted fields; drain after the `isEmpty()` check in its own `try/catch`
  - [ ] ITs: the AC4 interleaving (both orders — needs a hook on `PaymentCoachSubscriptionRepository.findByStripeSubscriptionId`); out-of-order parked events; deleted-then-older-update; a failed park does not trigger a Stripe retry; a throwing post-commit step leaves the park row for the job
  - [ ] Confirm `SubscriptionServiceStripeReconciliationIT :91/:112` pass **unchanged**
- [ ] Task 5: RS07 superseded proration (AC6, AC7)
  - [ ] Migration for `payment.subscription_upgrade_reconciliations` (FK-free, `latest_invoice_id`); `[SUBSCRIPTION_UPGRADE_UNGRANTED] key=value` at `error` **first**, metric, then a best-effort `REQUIRES_NEW` insert on the routed pool that cannot fail the request; tag into the `MoneyIntegrityEvent` regex and runbook
  - [ ] `SubscriptionEntitlementIntegrityTest.item9…:562-568` verifies `never()).save(any())` on `paymentCoachSubscriptionRepository` only — a write to a different repository does not trip it; the realistic failure is an NPE from an un-mocked new dependency under `@InjectMocks`
  - [ ] Keep `ALWAYS_INVOICE` + `ERROR_IF_INCOMPLETE` (`StripeClient.java:309-311`)
- [ ] Task 6: RS09 declined-card revert (AC8, AC9)
  - [ ] Conditional-update repository method with `@Modifying(flushAutomatically = true, clearAutomatically = true)` + `@Transactional`; skip when `attempted_card` is null; revert **before** writing `NO_CHARGE`; no revert on `UNKNOWN`/`UNRECONCILED`; first-time payer → null
  - [ ] Tests: decline with X, retry with X succeeds, sync fires (correction 3); a first-time payer's retry fires the sync
  - [ ] Remove the dead local `customer` at `:140` (assigned, never read)
  - [ ] Completion Notes: the shipped UI never sends a card here; the `/save-payment-method` door and AC8(d)/(e) are out of scope
- [ ] Task 7: RS08 pause date (AC10)
  - [ ] `SessionPackDashboardPage.vue:313` → local midnight; refresh the comment at `:271`
  - [ ] Add a regression test to `SessionPackDashboardPageSpec.js` (nothing existing changes; the assertion at `:180` checks the notify message, not the instant)
  - [ ] Reuse the idiom at `AvailabilityManagerPage.vue:192` / `WeeklyCalendar.vue:79`; no shared util. The DST-aware `zonedMidnightMs` (`BookingRequestPage.vue:554`, page-local, docblock `:544-553`) is only needed for the coach-zone variant AC10 puts out of scope
- [ ] Task 8: eval case promotion (AC11): rename file + `id`, then the post-merge `fix_merge` chore; ledger deletions and new entries (AC12)
- [ ] Task 9: docs, config keys, registrations, erasure (AC13) — including the erasure null-out and its IT
- [ ] Task 10: `/pr-review` (AC14)

## Dev Notes

### Verified current behaviour, with the evidence

**RS06 — `SessionPackPurchasePage.vue:178` `confirmPurchase`.** `:188` is `if (e?.response) attemptKey =
null` — no status discrimination; 400, 409, 422, 500, 502 all clear it. Network errors correctly keep the
key (axios throws without `.response`). The comment claiming "a definite failure" is `:165`. There is no
axios retry interceptor on this path (`boot/axios.js` has only request + 401 interceptors).

Server: `SessionPackPaymentResource.java:68-80` → `SessionPackPaymentService.purchasePack:98`, **not**
`@Transactional` (javadoc `:78`). The key becomes `"pack-" + parentId + "-" + attemptKey` (`:143`) and is
passed to Stripe as the Stripe idempotency key (`StripePaymentGateway.java:166,172`;
`StripeClient.java:56-59`). **The attempt key is never persisted for packs** — `V207` added
`charge_idempotency_key` to `booking_payments` and `subscribe_attempt_key` to the subscription tables
only. Local dedup is `findByStripePaymentIntentId` (`:148`) plus `uq_spp_stripe_payment_intent_id`
(`V195__session_pack_integrity.sql:18-20`), both keyed on the intent id Stripe returns — i.e. entirely
downstream of Stripe's own key matching. A new key means a new intent, and the local guard never sees a
collision. That structural gap is the defect; the frontend line is the trigger.

`ChargeOutcomeReconciler` cannot help: it is booking-only, driving off `booking_payments`
`CAPTURE_PENDING` rows (`findReconcilableCharges`, `:124-125`; the status checks are `:227`, `:259`), and a
pack purchase never writes one.
`stripe_refund_failures` has **no reader anywhere in `src/main`** outside the service that writes it.

**RS05 — `StripeWebhookService.handleSubscriptionUpdated:264-279`.** Looks up both coach and player repos
by `stripeSubscriptionId`; on no match logs `STRIPE_WEBHOOK_ORPHANED_SUBSCRIPTION`, calls the alert
helper, returns. `buildSubDataMap` is never built, so `status` / `current_period_end` /
`cancel_at_period_end` are never applied. The processed marker is `handleEventAtomically:177-187`:
`insertIfAbsent` runs **before** dispatch in the same `@Transactional`, and
`payment.stripe_webhook_events` has only `event_id`, `event_type`, `processed_at`
(`V138__baseline_schema.sql:1963-1967`) — the row's existence *is* "processed", so the no-match path
commits a transaction whose only effect is to make the event unrepeatable.

The window: `SubscriptionService` has no class-level `@Transactional`; `subscribeCoach:151` is plain.
`findOrCreateCoachSubscription:1612-1619` saves a row with `stripeSubscriptionId` **null** from a
non-transactional caller, so it commits immediately; `createPaidStripeSubscription` at `:182` runs with no
transaction open; `persistCoachSubscription` (`@Transactional`, `:235`) is the first write to set the id
(`:248`). Aggravating, and not in the ledger: that persist sets status from the **create-time** object
(`:249`) and never sets `lastStripeEventAt`, so it writes the pre-transition status over the top and the
staleness guard has nothing to compare against.

Why it is silent: `resolveCoachAndAlertIfOrphaned:344-352` suppresses the alert when the row was touched
within `SUBSCRIBE_RACE_GRACE_WINDOW` (10 min, `:75`), and `claimCoachSubscribeAttempt:205-216` touches it
seconds earlier with `@PreUpdate` refreshing `updatedAt`. The guard is correct for its own purpose and is
why there is no alert, no metric, no counter. Incidental self-heal: `handleInvoicePaid:1322-1333` returns
delinquent statuses to `ACTIVE`, so a drop *into* `PAST_DUE` self-heals at the next paid invoice — but a
dropped `active`→`past_due` does not, and silently keeps the coach entitled.

`AdminAlertEventListener.onCoachSubscriptionOrphaned:171-177` is `@EventListener` +
`REQUIRES_NEW` — **not** `@TransactionalEventListener` — so it runs inline in its own nested transaction,
unlike its `AFTER_COMMIT` siblings at `:186-188`.

**RS07 — `SubscriptionService.persistCoachTierUpgrade:339-360` / `persistPlayerTierUpgrade:573-588`.**
`FOR UPDATE` re-read, then `if (!Objects.equals(stripeSubId, sub.getStripeSubscriptionId()))` → `log.warn`
→ bare `return`, so `sub.setTier(newTier)` at `:348` is never reached. No exception, no event, no refund,
no reconciliation row. The same warn-and-return shape appears at `:366-369`, `:407-410`, `:594-597`,
`:638`, so it is a house pattern, not a one-method oversight.

`changeCoachTier:285-319` is not `@Transactional`, and neither is `SubscriptionResource`, so the Stripe
proration is invoiced **outside** any DB transaction, at `:305-306`, before `self.persistCoachTierUpgrade`
at `:307`. The endpoint answers **204 No Content** (`SubscriptionResource.java:67-73`; player `:116-122`)
— indistinguishable from a successful upgrade.

The concurrent writer is **revocation nulling the id**: only four sites write `stripeSubscriptionId`
(`:248`, `:503`, `:1392`, `:1405`), and `handleSubscriptionDeleted:1272-1287` locks by
`findByStripeSubscriptionIdForUpdate` — a different *predicate* from `findByCoachIdForUpdate` but both are
`PESSIMISTIC_WRITE` on the same `coach_subscriptions` row, so they serialise (`PaymentCoachSubscriptionRepository:43-49`);
the race is real regardless, because Stripe is called before either lock is taken — and is
explicitly exempt from the staleness guard (`:1215`, "always applied"). `checkPastDueGracePeriod:937-996`
is a narrower second route. The subscribe writers cannot race it: `requireBillingHealthy:296`,
`assertCanSubscribe:1438-1442` and `claimCoachSubscribeAttempt:205-216` all refuse a subscribe while the
row is billing-healthy.

**RS09 — `SessionPackPaymentService.purchasePack:98-192`, `getOrCreateStripeCustomer:440-455`.** The card
write is at `:444` (existing customer) or `:453` (new), reached from `:140`; the charge is at `:144`.
`purchasePack` has no `@Transactional`, the class's only transactional methods are `:255`, `:295`, `:347`, `:361`, `:378` (`:347` and `:361` were added by #436; `:378` is the old `:336` shifted +42; none on the purchase path),
no `TransactionTemplate` is injected, `getOrCreateStripeCustomer` is `private` and called in-class (so no
proxy), and `SessionPackPaymentResource` has zero `Transactional`. Therefore
`stripeCustomerRepository.save` runs in Spring Data's own transaction and **commits on return from
`:140`** — exactly one `stripe_customers` row, before the charge is attempted. There is no try/catch
around `:144` (the only `try`, `:154-186`, wraps `createPurchase`), so a decline propagates and nothing
reverts the write. Every later platform-initiated charge for that payer then picks up the declined card,
because `StripePaymentGateway.charge:138-145` re-reads it from that table, and `getSavedPaymentMethod:405-`
reports it as the saved card. Blast radius is bounded by the post-charge-only sync gate (`:188-190`,
javadoc `:198-199`): a declined card is never pushed onto live Stripe subscriptions. **But that gate is also
why the retry case is broken** (correction 3): the declined card X is already committed, so the retry's
snapshot equals it and the sync is skipped. **Reachability:** the only frontend caller passes `undefined` for
the payment method (`SessionPackPurchasePage.vue:184`), so the card write at `:140` is a no-op for every UI
purchase and this path is exercised by direct API clients.

**RS08 — `SessionPackDashboardPage.vue`.** `minPauseStartDate:272-277` builds "tomorrow" from
`getFullYear/getMonth/getDate` — **browser-local** — and renders a bare `YYYY-MM-DD`, bound to the picker
at `:125` via `:min`. `submitPause:313` then does `new Date(pauseForm.value.startDate).toISOString()`, and
per ECMAScript the date-only form parses as **UTC** midnight. Server: `PausePackRequest.java:12` types the
field as `Instant`, so Jackson pins the wire value with no zone inference, and
`PackSessionService.java:511,545` compares `!pauseStart.isAfter(now)` — a strict instant compare, no
truncation, no zone. The coach's `canonicalTimezone` is loaded in that method but used only for the
notification email (`:638`), and the comment at `:542` says the old date-only check was deliberately
removed.

Worked example: `America/Los_Angeles`, local 2026-11-04 17:30 PST. Picker's earliest is `"2026-11-05"`;
`:313` sends `2026-11-05T00:00:00.000Z`; server `now` is `2026-11-05T01:30:00Z`; `isAfter` is false →
`booking.pauseStartInPast`, whose message (`i18n/en-US/index.js:1995`) tells the user to "pick tomorrow or
later" — which is what they did. Rejection window is local time-of-day ≥ `24h − |offset|`: eight hours a
day in Los Angeles, five in New York, **never** at UTC or east.

### Hard constraints (each has already caused a defect here or in the previous story)

- **`purchasePack` must stay non-`@Transactional`** (javadoc `:78`). The compensating-refund design at
  `:154-186` depends on it, and wrapping it would hold a DB connection across a Stripe call.
- **The Stripe call stays outside the DB transaction** on both the subscribe path
  (`SubscriptionService.java:259-267`, which calls it "the worst available outcome") and the tier-change path
  (`:353-357`, which says a rollback would leave Stripe billing the new tier while the DB keeps the old one).
  Do not "fix" ordering by pulling Stripe in.
- **`insertIfAbsent` stays before dispatch** (`StripeWebhookService.java:179`); moving it after would
  reopen the concurrent-retry race `ON CONFLICT DO NOTHING` closes.
- **The orphan path must never throw** (`:287-293`, `:451-454`): that would put Stripe into indefinite
  retry against a healthy subscription.
- **Do not shorten `SUBSCRIBE_RACE_GRACE_WINDOW`.** There is no generic way to clear an `AdminAlert` once
  OPEN (`:50-57`), so that trades a silent drop for permanently unresolvable false alerts.
- **A per-attempt idempotency key means a client retry with a new key is a new charge** — packs (a keyless request mints its own key)
  (`SessionPackPaymentService.java:143`) and upgrades (`SubscriptionService.java:1547`, prefix passed at
  `:305`). Any error you
  return must not invite a retry of a call that already moved money.
- Carried from deferred-167: a `REQUIRES_NEW` write **self-deadlocks** (55P03 → 409) if the caller already
  holds a lock over the same row; use a **scalar existence query** for pre-checks rather than loading the
  entity (a locked read returns an already-managed instance without refreshing it); Java 17, so
  `instanceof` chains and no switch patterns; a finite `jakarta.persistence.lock.timeout` is **inert** on
  PostgreSQL.
- **One open attempt per parent is enforced by a partial unique index, not by application logic** (AC1). **Never resolve another attempt from the
  request path** — resolving an earlier attempt and then charging the retry is exactly how the third review found a double charge. Look up the
  caller's own key *before* the tier/coach gates, so a replay can still resolve a captured payment after the tier was deactivated or the coach suspended (AC1a).
- **"Not found" at Stripe is not proof of no charge** inside the minimum age or when the payer has no customer row (AC2); a refunded
  PaymentIntent is still `succeeded` (`ChargeLookup` has no refund state — use `getRefundableAmount`).
- **Two commit points for the webhook race** (AC4): re-reading inside the webhook's own transaction cannot see a link that commits
  after the re-read; park-and-commit first, re-read after.
- **A card decline is a 4xx and becomes `payment.lifecycleFailure`** (`StripePaymentGateway.java:185-187`),
  indistinguishable from the refunded and unreconciled outcomes; `charge()` is shared with the booking path.
  Do not change a code a booking caller already depends on (AC1b).
- **Stripe replays a keyed failure.** Reusing a key after a decline returns the cached decline, and changing
  the payment method under the same key is an idempotency mismatch that `isOutcomeUnknown` treats as an
  unknown outcome. This is documented Stripe behaviour, not exercised in this review — pin it with a test.
- Migrations: additive first, FK/CHECK `NOT VALID` then `VALIDATE` later, `SET LOCAL lock_timeout` (not
  plain `SET`), `allow-*` markers immediately above their own statement with a real reason. V226 is **on master** (builder-packs,
  #436); V227 is contested by the pitchside branch and by `skillars-deferred-170` — start from V227 and re-derive at dev time (Numbering).
- **If Task 4 touches the daily sweep, respect its lock budget.** `StripeSubscriptionReconciliationScheduler`
  is `@Scheduled(cron = "0 0 5 * * *")` with `lockAtMostFor = "PT15M"`, `lockAtLeastFor = "PT2M"`, sized
  against Stripe page latency (its javadoc `:22-36`). A per-row status comparison costs no extra Stripe
  calls; a per-row *fetch* would blow that budget. Keep the per-item `try/catch` (`:1204-1206`) and the
  per-status catch of both `StripeException` and the SDK's unchecked mid-pagination wrapper (`:1180-1187`)
  — one bad row or one rate-limited page must not abort the sweep. Do not fork `STRIPE_LIVE_STATUSES`
  (`:1130-1133`); it is derived from `StripeWebhookService.LIVE_SUBSCRIPTION_STATUSES` on purpose.
- `docs/deployment/monitoring.md` is parsed by `AlertDocumentationParityTest` and is on the no-build
  classifier's `NEEDS_BUILD` list — an alert change needs the doc change in the same commit.

### Tests that assert the current, defective behaviour

Only one existing test asserts the defect and changes deliberately:

| Test | Asserts |
|---|---|
| `SessionPackPurchasePageSpec.js:61-71` | a new key is minted after a 500 — i.e. RS06's defect |

Two tests the first draft listed here **do not change**, and passing is not a signal:
`SubscriptionServiceStripeReconciliationIT :91` and `:112` call `subscriptionService.reconcileStripeSubscriptions()`
— the daily sweep — which AC4/AC5 leave alone. And `SubscriptionEntitlementIntegrityTest.item9…:562-568`
verifies `never()).save(any())` on `paymentCoachSubscriptionRepository` only, so a write to a different
repository does not trip it (the failure to watch for is an NPE from an un-mocked new dependency under
`@InjectMocks`).

Keep green: `SessionPackPaymentServiceTest.purchasePack_insertFailsAndIntentLookupFails_noBlindRefund_reconciliationRecorded:406`
(asserts `verify(paymentGateway, never()).refund(...)` — the "prove no pack holds this charge before
refunding" ordering), `SessionPackPaymentServiceTest:423` (key-format gate),
`SubscriptionEntitlementIntegrityTest.item11_upgradeChargeDeclined_tierNotGranted:586`, and
`SubscriptionServiceConcurrencyIT` (pins the `persistCoachSubscription` lock ordering).

### Testing

`@SpringBootTest` + `@Testcontainers` for ITs, never mocking the DB; Instancio for test data; AssertJ
`assertThat`; Awaitility for async. Frontend: Vitest + `@vue/test-utils`, specs beside the code in
`__tests__/`, explicit `describe/it/expect/vi` imports, run via `cd src/frontend && npm run test:unit` —
**not** part of `mvn verify`.

Carried from deferred-167, both of which cost real debugging time: `ClockProvider` is a `ThreadLocal` and
**cannot** reach the embedded server's worker thread in HTTP ITs, so drive time through `commitWrite` or a
hand-built value instead; and with Hikari `auto-commit=false`, a bare `jdbcTemplate` write in an IT body
**never commits** — wrap it in `transactionTemplate`/`commitWrite`. Also: the Spring test-context cache
ceiling is 17 (`assert-context-count.sh`, `IntegrationTestConventionTest` pins 14 keys), and mock field
names are part of the cache key — do not add a new context shape casually.

### Project Structure Notes

Everything backend is in `com.softropic.skillars.platform.payment` (`api`, `service`, `repo`, `contract`),
which matches the module layout in `_bmad-output/project-context.md`. Request/response DTOs are Java
`record`s; mappings via MapStruct; every resource method needs `@PreAuthorize`. Frontend API calls belong
in `src/frontend/src/api/*.api.js` — `payment.api.js:22-23` already carries the pause call.

RS08's `SessionPackDashboardPage.vue` (`routes.js:127`) is a different file and route from RS06's
`SessionPackPurchasePage.vue` (`routes.js:119`), so the two frontend items do not overlap.

### References

- Ledger: `_bmad-output/implementation-artifacts/deferred-work.md` — RS05, RS06, RS07, RS08, RS09
- Eval mapping: `evals/review/results/baseline-2026-11-02/real-sample.md:314`
- Eval cases: `evals/review/cases/{337,350,359,368}-*.yaml`,
  `evals/review/cases/_drafts/pending-card-default-before-charge.yaml`
- Migration conventions: `docs/deployment/migration-conventions.md`
- Alert + runbook: `deploy/lgtm/grafana-alerts.yml:638-648`, `docs/deployment/monitoring.md#MoneyIntegrityEvent`
- Previous story: `_bmad-output/implementation-artifacts/skillars-deferred-167-per-session-jwt-revocation-and-refresh-chain-follow-ups.md`

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`)

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-04 — created (`/bmad-create-story`) from the owner-chosen payments/Stripe ledger bundle, against
  real HEAD `bed75f19`. Every ledger claim was re-verified against current source by four parallel
  read-only audits before being written down, and **four assertions did not survive**: RS06's "500" is
  actually a 422 (so a 5xx-framed fix is a no-op) and it names the better-instrumented of two branches
  while the untraceable `ChargeOutcomeUnknownException` path has no DB row and no alert; RS09's
  "subscription sync is skipped later" is false at HEAD [**RETRACTED 2026-11-04 — true on a retry after a decline; see Correction 3**], fixed by the `previousCard` snapshot that
  `4db90562` (#362) added; RS05's sweep claim understates the problem three ways (alert-only by design,
  coach-only, and the marking is the caller's); and RS08 reports only the rejection, not the silent
  cancellation of a booking on a day nobody paused. The story records the two traps that make the obvious
  fixes wrong — key retention alone would hand back a pack funded by refunded money, and granting the tier
  on a superseded row would re-grant a paid tier with no subscription — and names the four existing tests
  that assert the current defective behaviour and must change deliberately.
- 2026-11-04 — *(superseded — see the later entries; the "four existing tests" wording below was cut to one in the second review)* owner settled all three design forks the same day, each on the recommended option (persist the attempt server-side; record the superseded proration and keep the 204; revert the card to the pre-charge snapshot). The ACs already encoded these, so nothing was rewritten; the reasoning and the rejected alternatives are recorded in the new `Owner decisions` section, including the two costs the owner accepted — RS07 still tells the user the upgrade worked, and RS09 compensates rather than prevents.
- 2026-11-04 — **revised after `/mto-story-review`** against HEAD `50eebd75` (report: `story-review.md`;
  ~105 citations, 10 attributions, 12+ behavioural claims, 8 corner-case areas; 6 high / 8 medium / 5 low
  findings survived adversarial re-checking, 5 raised items were killed). Applied: **renumbered 168 → 169**
  and moved migrations to V227+ (an unmerged story already holds id 168 and V226); **Correction 3 replaced**
  (RS09's "sync skipped later" is true on a retry; `4db90562` is an ancestor of both RS09 input revisions);
  AC1 rebuilt around a status model so a decline stays retryable, with a no-charge signal (AC1b) and a key
  reset on card save; pre-flight rejections inside `charge()` become `NO_CHARGE`; AC2 resolves the truth from
  Stripe before building a pack, closing the crash-between-refund-and-write gap and the open-reconciliation-row
  hazard; AC3 uses a pack-specific alert tag and the attempt row as the durable record (the shared tag pages on
  self-healing booking events, and `stripe_refund_failures` cannot hold a row without a PaymentIntent id);
  AC4 gains the park/persist race fix, a drain-after-commit rule, an ordering baseline, retention, and now
  covers `deleted`; AC6 uses a new table instead of `stripe_refund_failures` and forbids `REQUIRES_NEW`; AC8
  specifies a conditional UPDATE, a stored `previous_card`, and no revert on unknown outcomes; the
  "tests that change" table is cut to the one test that genuinely does; citation drifts and wording fixed.
  **Not changed:** the owner's three decisions. Two facts bearing on them are recorded in the new
  "Facts established after the decisions" section: RS09 is unreachable from the shipped UI, and the SetupIntent
  rationale for rejecting a `/save-payment-method` fix is not enforced by the server.
- 2026-11-04 — **revised after the second `/mto-story-review`** against HEAD `4d348462` (report: `story-review.md`; ~140 citations, ~25 attributions,
  ~25 behavioural claims, 11 corner-case areas; **3 high / 10 medium / 8 low** survived, 6 raised items were killed or downgraded). Pass 1's rewrite had itself
  introduced two high-severity defects and left a third open; this pass fixes them. **Applied:** AC1b no longer clears the key on card save or pack change after an
  unknown outcome, and AC1a gains a server-side *resolve-before-new* guard so safety no longer depends on the client; AC2 names `getRefundableAmount` for refund state
  (`ChargeLookup` cannot express a refund), defines `createdAfter`, a minimum age and the "found but not succeeded" and pending branches, and checks for an existing pack
  first; AC1a orders the attempt lookup **before** the tier/coach gates so a replay can still resolve a captured payment; AC1b settles the signal mechanism (the pack service
  classifies; the gateway's pinned decline code is untouched) and the wire contract (`errorMsg.errorKey` vs `errorMsg.message`; the page reads a field that does not exist);
  AC3 alerts on **aged unresolved attempts** via a scheduled scan instead of the per-event tag that would page on self-healing events; AC4 gets a two-commit-point protocol
  (the first draft's "re-read after parking" does not close the race), a `REQUIRES_NEW` park so a failed park cannot cause an indefinite Stripe retry, a recovery drain and
  extracted-field storage; AC6 logs first, inserts best-effort, stores `latest_invoice_id` (the first draft wrongly said no identifier was available) and corrects its blanket
  `REQUIRES_NEW` ban (the FK-free table cannot deadlock); AC8 gains `attempted_card`, keyless handling, revert-before-`NO_CHARGE`, `@Transactional` on the repository method and two accepted
  limits; Numbering now names the **second** unmerged V226 holder and that the builder-packs branch edits `SessionPackPaymentService.java`; AC11 includes the rename-and-`id` and
  post-merge steps; seven citation drifts fixed. **Applied without an owner answer — OWNER TO CONFIRM:** the scheduled scan is included in RS06, and the RS09 revert is kept. *(The scan was replaced by a resolving job + backlog gauge in the third revision below; only the RS09 revert remains to confirm.)*
- 2026-11-04 — **revised after the third `/mto-story-review`** against HEAD `05b8bea8` (report: `story-review.md`; ~140 citations, ~25 attributions, ~30 behavioural claims, 13 corner-case areas; **4 high / 9 medium / 4 low**).
  All four high findings were in mechanisms the previous revision had added, and the story was not converging (6, 3, then 4 high-severity defects), so the **owner chose to simplify RS06 rather than patch it or split the story**.
  **Removed:** the cross-attempt *resolve-before-new* guard (it double-charged when it found a capture, was a check-then-insert race, and had no exit for a stuck row), the log-tag scan, and the "keyless requests write no row" special case.
  **Added:** a **partial unique index** — one open attempt per parent — as the concurrency control (a server-minted key means *every* purchase has a row); **compare-and-set** on every status write with a proof-of-capture exception; a **minimum age of 10 min** (configurable)
  matching the existing reconciler and the 6-8 min a legitimate request can run; resolution **only** by a same-key replay or the **job**; "no customer row → `NO_CHARGE`" and a terminal rule after the key window; an `OperationalBacklogMetrics` branch + Grafana alert (self-clearing) in place of a log alert; log tags in `[TAG] key=value` form;
  one consistent AC4 structure (park in `REQUIRES_NEW` on the routed pool via a separate bean, post-commit re-read in `processWebhook`, `DELETE … RETURNING` claim, a throwing post-commit step recovered by the job); a GDPR decision (null the card columns); `ConfigBounds`/registration notes; unclassified exceptions → `UNKNOWN`; a resolved-captured replay runs the card sync.
  **Also:** **merged `origin/master`** (#436 landed with `V226`; `+42` lines in `SessionPackPaymentService`; `V227` contested; `skillars-deferred-170` is sequenced after this story and rewrites `purchasePack`'s ordering), re-located the shifted cites, and fixed six citation drifts. **Accepted cost:** a parent who loses the key while an attempt is `UNKNOWN` waits for the job (≤ minimum age + 5 min).
  **Still to confirm:** the RS09 revert, the GDPR null-out, and the four-duty job.
- 2026-11-04 — **revised after the fourth `/mto-story-review`** against HEAD `30e2c712` (report: `story-review.md`; ~270 claims; high-severity findings 6 → 3 → 4 → **1**). **H1:** inferring `NO_CHARGE` from absence while the
  original request thread could still be alive freed the index and allowed a second charge and a second pack — now one rule: absence needs a successful empty lookup **and** `age ≥ absence age` (new `ConfigBounds` key, default 30, 15-1440);
  the 24 h terminal rule is gone (it also contradicted the "only past the minimum age" sentence, M1); a proof-of-capture CAS that violates the index is caught and logged. **M2:** exits for an unresolvable row (job never refunds, operator SQL close-out in the runbook).
  **M3:** a zero `getRefundableAmount` no longer means `REFUNDED` (pack-path method, null charge stays `UNKNOWN`). **M4:** a parked `deleted` skips `isStaleEvent` by design. **M5:** a first-time payer's row stores the card as given; revert skips null **or blank**.
  **Also:** same-key vs open-per-parent `23505` told apart by re-reading; accepted-cost wording; six citation drifts; pitchside branch is not local-only; ledger entries say "deleted outright"; low findings filed in AC12. **No fifth review is planned.**
