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

**Numbering.** This story was created as `skillars-deferred-168` and renumbered to **169** after
`/mto-story-review` found an unmerged story already using that id
(`skillars-deferred-168-builder-packs-become-the-purchasable-tier`, worktree
`/Users/mokwen/dev/gitrepos/bluegithub/skillars-deferred-168`, status `review`). That story also holds
migration **`V226`**, so every migration here is numbered **`V227` or later, and the number must be
re-derived at dev time**: list `src/main/resources/db/migration` on `origin/master`, `git branch -a`, and
every worktree, and take the next free number only after the builder-packs branch has merged or been
rebased past. That branch also edits the payment module (`SessionPackOfferTest`, i18n bundles) — rebase onto
it before starting, and expect a trivial conflict in `sprint-status.yaml`'s `last_updated` line.

They are bundled because four of the five live in `platform.payment.service` and two of them touch
`SessionPackPaymentService.purchasePack`'s pre-charge/post-charge ordering directly; splitting them would
put two stories in the same thirty lines.

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
*Rejected:* also fixing `/save-payment-method` (`SubscriptionService.java:657-665`), because that endpoint
follows a confirmed, customer-scoped SetupIntent so the card is already Stripe-authenticated. Accepted
cost: this is compensation, not prevention — a crash between the charge and the revert still leaves the
declined card saved.

### Facts established after the decisions above (`/mto-story-review`, 2026-11-04)

The three decisions stand as chosen. These facts bear on them and the owner should be able to see them:

- **RS09's card-write is unreachable from the shipped UI.** The only caller passes `undefined` for the
  payment method (`SessionPackPurchasePage.vue:184`; `booking.store.js:309`; no other caller exists), and
  `getOrCreateStripeCustomer` writes only when the id is non-blank. AC8 therefore protects direct API
  clients; for UI users the "declined card stays saved" problem arises through `/save-payment-method`.
- **The reason given for rejecting a `/save-payment-method` fix is not enforced by the server.** The
  Owner-decision text says that endpoint "follows a confirmed, customer-scoped SetupIntent so the card is
  already Stripe-authenticated". `SessionPackPaymentResource.savePaymentMethod` passes
  `request.paymentMethodId()` straight to `SubscriptionService.savePaymentMethodAndScheduleSync`, whose only
  guard is `StripeIdFormat.requirePaymentMethodId`. That is client convention, not a server guarantee.
- **RS06's server record needs more states than "outcome recorded"** (AC1): a decline must not block a
  retry, and a crash between a refund and the outcome write must not let a replay build a pack from refunded
  money.
- **`stripe_refund_failures` cannot be the durable record for RS06's unknown outcome or RS07's proration**
  (`payment_intent_id`, `amount`, `parent_id`, `pack_tier_id` are all `NOT NULL`, `V138:1933-1944`), so
  RS07's "follow the existing precedent" means the *pattern* (record-and-reconcile), not that table.

## Acceptance Criteria

**AC1 — a retry cannot charge a second time, and a decline can still be retried.** *(OWNER DECIDED
2026-11-04, option a: persist the attempt server-side.)* A new table
`payment.session_pack_purchase_attempts` (migration `V227+`, see Numbering) holds one row per attempt:
`parent_id`, `attempt_key` (matches `ATTEMPT_KEY_PATTERN`, `SessionPackPaymentService.java:58`),
`pack_tier_id`, `player_id`, `previous_card` (snapshot, nullable — AC8), `payment_intent_id` (nullable),
`status`, timestamps; **`UNIQUE (parent_id, attempt_key)` declared inline in `CREATE TABLE`** (a separate
`CREATE UNIQUE INDEX` would need an `allow-blocking-index` marker). Statuses:

| status | meaning | replay answers |
|---|---|---|
| `IN_PROGRESS` | row written, charge not yet resolved | resolve via AC2; never charge again |
| `SUCCEEDED` | charged and pack recorded | the recorded pack (`replayedPurchase`) |
| `NO_CHARGE` | proven no money moved: decline, non-`succeeded` intent, or a pre-flight rejection | the no-charge signal (AC1b); client mints a fresh key |
| `REFUNDED` | captured then refunded | the no-charge signal (AC1b) |
| `UNKNOWN` | Stripe outcome not known | resolve via AC2; keep the key |
| `UNRECONCILED` | captured, pack not recorded, refund failed or unknown | resolve via AC2; keep the key |

**AC1a — the record's mechanics.** `purchasePack` has no transaction to join (javadoc `:78`), so the attempt
row is written in **its own committed transaction before the card write at `:140` and before `:144`**, and
its outcome in a second committed step. The loser of a concurrent same-key insert (unique violation) reads
the winner's row: `IN_PROGRESS` → answer `409 payment.attemptInProgress` and charge nothing; any other
status → answer that status's replay result. Stored `pack_tier_id`/`player_id` are compared up front, so
reusing a key for a different tier or player is refused with the existing `payment.idempotencyKeyReused`
**before** any Stripe call. An `IN_PROGRESS` row older than the process could plausibly still be running
(minutes) is treated as `UNKNOWN`; Stripe keys live ~24h, so an unresolved row is **never** re-charged — it
is resolved by AC2's lookup. An **absent** `Idempotency-Key` keeps today's behaviour (no record, no dedup,
`:143` mints a random UUID) and is logged; requiring the header is out of scope.

**Failures that happen inside `chargeAndCapture` are not "pre-charge".** `charge()` throws
`payment.coachStripeNotConfigured` (`:117`), `payment.configurationUnavailable` (`:123-125`),
`payment.noStripeCustomer` and `payment.noPaymentMethod` (`:138-143`) **after** the attempt row exists and
before any PaymentIntent is created; `getOrCreateStripeCustomer` (`:140`, a Stripe call for a first-time
payer) can fail after it too. All of these mark the row `NO_CHARGE`. `payment.packTierInactive` is thrown
before the row and needs no row.

**AC1b — the client can tell "no money moved" from "money may have moved".** Today a card decline is a 4xx
that `charge()` maps to `PaymentGatewayException("payment.lifecycleFailure")` — the **same code and the
same 422** as the refunded and unreconciled branches (`StripePaymentGateway.java:174-181`), so a frontend
rule of "keep the key unless the server proved no charge" cannot be implemented from today's responses.
Retaining the key across a decline would replay Stripe's cached decline even after the parent tops up (Stripe
saves the result of a keyed request whether it succeeded or failed), and saving a different card first
changes the PaymentIntent's `payment_method`, which Stripe rejects as an idempotency mismatch — classified
by `isOutcomeUnknown` as an **unknown outcome**. The server therefore returns a machine-readable
no-charge signal for `NO_CHARGE` and `REFUNDED`. The mechanism is a dev decision (a `PaymentGatewayException`
subclass thrown by the gateway and mapped in `PaymentApiAdvice`, or a response field) **but it must not
change an error code any booking-path caller already depends on** — the gateway's `charge()` is shared with
bookings, so grep every consumer of `payment.lifecycleFailure` first. The frontend then:
clears the key on success, on the no-charge signal, on `400 payment.invalidIdempotencyKey`, on a change of
`selected` (`:174`), and **on card save (`onCardSaved`)**; and **keeps** it on a network error and on every
other answer. The `attemptKey ??=` reuse (`:182`) and the in-flight guard (`:179`) stay.

**AC2 — a replay resolves the truth before it builds anything.** Any replay of a row that is not
`SUCCEEDED`, `NO_CHARGE` or `REFUNDED` first consults Stripe for the PaymentIntent behind the key
(`findChargeByIdempotencyKey`, `StripePaymentGateway.java:~175`, and `retrieveCharge`), and only then acts:
**refunded** → mark `REFUNDED`, answer the no-charge signal, create **no** pack; **captured and not
refunded** → create the pack (once; `uq_spp_stripe_payment_intent_id` backstops it) **and resolve the
open `stripe_refund_failures` row** (`resolved_at`) so an operator does not later refund a funded pack;
**no PaymentIntent** → `NO_CHARGE`; **lookup fails** → stay `UNKNOWN`, answer
`payment.chargeOutcomeUnknown`, keep the key. `createPurchase` is never run against an unverified intent.
This covers the crash between the compensating refund (`:177`) and the outcome write, which a
"recorded outcome says refunded" check alone does not: that crash leaves `IN_PROGRESS`, and with the same key
Stripe returns the original (refunded) intent, `:148` finds no pack, and `createPurchase` would otherwise
succeed with refunded money.

**AC3 — the untraceable charge path becomes observable, without paging on healthy events.** Add a catch
around `chargeAndCapture` (`:144`) for `ChargeOutcomeUnknownException`: mark the attempt `UNKNOWN`, log a
**pack-specific** tag `PACK_PURCHASE_CHARGE_OUTCOME_UNKNOWN` from `SessionPackPaymentService`, rethrow. Add
that tag — **not** `STRIPE_CHARGE_OUTCOME_UNKNOWN` — to the `MoneyIntegrityEvent` regex
(`deploy/lgtm/grafana-alerts.yml:638-648`), to its runbook (`docs/deployment/monitoring.md#MoneyIntegrityEvent`)
and to the `deploy/lgtm/dashboards/payments.json:1314` panel. The shared gateway tag is logged by the booking
path too, where `ChargeOutcomeReconciler` self-heals it, and for a Stripe 409 idempotency conflict (two tabs,
same key); alerting on it would page on both. The durable record is the `UNKNOWN` attempt row, **not**
`stripe_refund_failures` (its `NOT NULL` columns need a PaymentIntent id, and `ChargeOutcomeUnknownException`
carries only the idempotency key, `ChargeOutcomeUnknownException.java:20-27`). Automated pack reconciliation
is **out of scope**: the runbook step is manual (look the key up in Stripe) and a ledger entry is filed
(AC12). `AlertDocumentationParityTest` matches alert titles, so a regex token does not affect it — confirm
by running it.

**AC4 — a subscription event that races the signup is applied, not dropped — coach and player,
`updated` and `deleted`.** When `handleSubscriptionUpdated` (`StripeWebhookService.java:271-275`) or
`handleSubscriptionDeleted` (`:356-370`) finds no local row, the event payload is **parked** in a new table
(`payment.stripe_pending_subscription_events`: `event_id`, `stripe_subscription_id`, `event_type`,
`event_created`, payload, `received_at`) **in the same transaction that records the event as processed**
(`:177-179`). Two things must hold, both of which a naive version gets wrong:

1. *The race.* A webhook transaction can read "no row", then `persistCoachSubscription` commits the link and
   looks for parked events (finding none, because the webhook's park insert is not yet committed), then the
   webhook commits the park — the same window being closed. So after parking, the handler **re-reads for the
   local row** (READ COMMITTED sees the committed link) and, if found, applies the event directly.
2. *The drain site.* Re-applying inside `persistCoachSubscription`/`persistPlayerSubscription`
   (`@Transactional`, `SubscriptionService.java:235-270`, `:489-`) would let an exception roll back a persist
   after Stripe has billed — `:259-266` calls that "the worst available outcome". Drain **after commit** (or
   in a separate step with its own `try/catch`), per parked event, oldest `event_created` first, through
   `subscriptionService.handleSubscriptionWebhook` so normalisation and `isStaleEvent` still run; stop after
   a `deleted`. Neither persist method sets `lastStripeEventAt` (`:247-254`, `:501-508`), so the drain must
   set it as it applies each event or the stale guard has nothing to compare against.

Parked rows that never link — `cancelDuplicateSubscription` (`:190`, `:453`, `:1522`) deliberately cancels
unlinked duplicates, so their events will never drain — are purged by a scheduled job (ShedLock; follow
`docs/deployment/` scheduler conventions and the lock-budget note in Hard constraints) after a retention
window, logging `warn` and incrementing a metric for any row purged unapplied. `invoice.paid` (silent no-op
without a row) and `invoice.payment_failed` have the same shape and are **out of scope**, declared here and
filed in the ledger (AC12) so the omission is a decision rather than an oversight.

**AC5 — AC4 must not be bought by making the orphan path louder, or by touching the sweep.**
`SUBSCRIBE_RACE_GRACE_WINDOW` (`StripeWebhookService.java:75`) stays as it is; the orphan path still must not
throw (`:287-293`, `:451-454`: Stripe must never be put into indefinite retry on a healthy subscription);
`insertIfAbsent` stays **before** dispatch (`:179`); and `reconcileStripeSubscriptions` (the daily sweep) is
unchanged, so `SubscriptionServiceStripeReconciliationIT :91`/`:112`, which exercise the sweep, stay green
untouched.

**AC6 — a paid proration that cannot be granted is recorded and surfaced.** *(OWNER DECIDED 2026-11-04,
option a: record and keep the 204.)* The superseded branches (`SubscriptionService.java:341-346` coach,
`:577-580` player) stop being a bare `log.warn` + `return`. They insert a row into a **new table**
(`payment.subscription_upgrade_reconciliations`: `subscriber_kind` COACH/PLAYER, `subscriber_ref` text,
`stripe_subscription_id`, `target_tier`, `created_at`, `resolved_at`; **no FK** to the subscription tables
and **no `amount`/`payment_intent_id` required**, because `StripeClient.updateSubscriptionTier` returns only
the `Subscription` and the upgrade key is not passed to `persist*TierUpgrade`) **in the same transaction**,
log at `error` with a stable tag, and increment a metric; that tag joins the `MoneyIntegrityEvent` regex and
runbook. A plain same-transaction insert is correct because the branch returns normally and commits. **Do not
use `REQUIRES_NEW`** (as `RefundReconciliationService.recordRefundFailure` does): with any FK to
`coach_subscriptions` it would block on the `FOR UPDATE` held at `:341` and self-deadlock — this module's own
carried constraint. Only the **upgrade** branches (money moved) record; the downgrade and cancel
warn-and-returns (`:366-369`, `:407-410`, `:594-597`, `:638`) charge nothing and are unchanged. The
tier must **not** be granted anyway: a row whose Stripe link is null or cancelled would re-grant a paid tier
with no subscription, which PR #337 (`eea53ee9`, the `fix/payment-subscription-entitlements` label in the
code comments `:414-416`, `:1385-1387`) closed.

**AC7 — AC6 must not cause a second proration.** The upgrade idempotency key is per-attempt
(`keyPrefix + UUID.randomUUID()`, `SubscriptionService.java:1547`, prefix `"sub-upgrade-"` passed at `:305`),
so any client retry is a new key and a new charge. The endpoint keeps answering 204 and must not invite a
retry, and the Stripe call stays outside the DB transaction (`:353-357` — a rollback after Stripe has billed
would leave Stripe at the new tier and the DB at the old one).

**AC8 — a declined card does not remain the payer's saved default.** *(OWNER DECIDED 2026-11-04, option a:
revert to the snapshot.)* When the charge ends in a proven no-charge outcome (`NO_CHARGE`), the
`stripe_customers` default is restored to the attempt's stored `previous_card`. Mechanism, because a naive
revert is wrong: **a conditional column update** — `UPDATE … SET stripe_payment_method_id = :previous
WHERE parent_id = :id AND stripe_payment_method_id = :attempted` — never load-modify-save.
`StripeCustomer` has no `@Version` (`StripeCustomer.java:19-32`) and the gateway saves the same entity
(`setLastPaymentIntentId`, `StripePaymentGateway.java:~207-211`), so a merge would clobber it, and a blind
revert would clobber a concurrent `savePaymentMethodAndScheduleSync` (`SubscriptionService.java:657-665`)
whose sync then sees the card changed and skips itself as superseded (`:688-690`). Further: `previous_card`
is **stored on the attempt row**, because on a replay after a crash `:139` re-reads it and it may already be
the new card; the attempt lookup **precedes the card write**, so a replay answered from a recorded outcome
never rewrites the card; **do not revert on `UNKNOWN`/`UNRECONCILED`** (the charge may have captured, and a
revert would desynchronise the default from the card actually used); for a first-time payer restore to
`null` and keep the row (the Stripe customer exists; null is handled at `getSavedPaymentMethod:367` and
`StripePaymentGateway.java:123`). Must not enqueue a card sync (the post-charge gate at `:188-190` exists so a
declined card is never pushed onto live subscriptions — keep it). **Add a test for the retry case from
correction 3**: decline with card X, retry with X succeeds, and the sync *fires*.

**AC9 — AC8 does not break paying with a different card.** `StripePaymentGateway.charge` **re-reads** the
card from `stripe_customers` (`:138-145`) and has no payment-method parameter, so the pre-charge write is the
transport for the new card. Removing or deferring it would break pack purchase with a new card entirely and
yield `payment.noPaymentMethod` for a first-time payer. `purchasePack` must stay non-`@Transactional`
(javadoc `:78`; the compensating-refund design at `:154-186` depends on it). The revert is idempotent across
replays (`:148-151`, `:173-175` re-enter the method).
*Reachability, stated so nobody over-reads AC8:* the shipped UI never sends a payment method here (see the
facts section), so AC8 protects direct API clients. Record the `/save-payment-method` door
(`SubscriptionService.java:657-665`) in Completion Notes as deliberately out of scope.

**AC10 — the pause start date the picker offers is accepted, and means the day the parent picked.**
`SessionPackDashboardPage.vue:313` sends local midnight rather than UTC midnight, so the earliest offered
date is never refused by `PackSessionService.java:545` and the pause no longer begins on the previous evening
for users west of UTC. **Out of scope, declared so it cannot inflate:** whether a pause day belongs to the
coach's calendar zone instead of the parent's — it needs a timezone field on `SessionPackPurchaseResponse`,
its mapper and backend tests, and is a policy decision rather than a bug fix.

**AC11 — the eval cases.** The four existing cases (`337-subscription-entitlement-integrity.yaml` RS05,
`350-pack-refund-rounding-remainder.yaml` RS06, `359-charge-idempotency-unknown-outcome.yaml` RS07,
`368-pack-status-badges.yaml` RS08) **already carry their finding with `fix_pr` set** (F12, F3, F10, F4) —
nothing to add. The only edit is to flip `fix_pr: pending` → this PR number on
`evals/review/cases/_drafts/pending-card-default-before-charge.yaml` (RS09) **and move it out of `_drafts/`
following the convention in `evals/review/README.md`**. Its `F1` title and description are **correct**
(correction 3) — leave them alone. Do not change the anchors; `chargeAndCapture` is declared in
`StripePaymentGateway` as well as being called from `SessionPackPaymentService`.

**AC12 — the ledger.** Per `deferred-work.md`'s own rule, delete RS05-RS09 outright once implemented. Before
that, as part of this PR, correct in place what was wrong: RS06 (422, and the untraceable path), RS05
(alert-only and coach-only sweep, caller-side marker), RS08 (the silent cancellation of a booking on a day
nobody paused). **RS09's entry is not corrected** (correction 3) — it gains one sentence noting it is
reachable only via direct API clients. File new entries for what this story deliberately leaves: automated
reconciliation of `UNKNOWN`/`UNRECONCILED` pack attempts; `invoice.paid` / `invoice.payment_failed` with no
local row; the `/save-payment-method` door; requiring the `Idempotency-Key` header.

**AC13 — docs.** `docs/dev-docs/payment/index.html` (+ rebuilt `docs/dev-docs/assets/search-index.js`) for the
attempt record, the parked-event path and the upgrade reconciliation table; `docs/deployment/monitoring.md`
for AC3/AC6; `docs/deployment/provisioning-checklist.md` for the migrations, the new scheduled job and the
alert change. `check-docs` must pass.

**AC14 — `/pr-review` on the final commit.** Run it, fix what it finds, run it again. Deferred-167 closed this
at seven rounds by owner decision rather than on a clean round; if this one is still finding behaviour
defects after five, raise it rather than looping silently.

## Tasks / Subtasks

- [ ] Task 0: rebase onto the builder-packs branch / master, and re-derive the migration number (Numbering)
- [ ] Task 1: RS06 attempt record (AC1, AC1a, AC2)
  - [ ] Migration for `payment.session_pack_purchase_attempts` — additive; inline `UNIQUE`; `SET LOCAL
        lock_timeout`; follow `docs/deployment/migration-conventions.md`;
        `mvn -q -Dtest=MigrationConventionLintTest test`
  - [ ] Write the row in its own committed transaction before `:140`/`:144`; second committed step for the outcome
  - [ ] Concurrent same-key loser → `409 payment.attemptInProgress`; tier/player mismatch → existing
        `payment.idempotencyKeyReused` before any Stripe call; stuck `IN_PROGRESS` → `UNKNOWN`
  - [ ] Pre-flight rejections inside `charge()` (`:117,123-125,138-143`) and `getOrCreateStripeCustomer`
        failures → `NO_CHARGE`
  - [ ] Replay resolution per AC2 (refunded / captured / none / lookup-fails), incl. resolving the
        `stripe_refund_failures` row
  - [ ] ITs: concurrent same key; crash between refund and outcome write; replay of `UNRECONCILED`
- [ ] Task 2: RS06 no-charge signal + frontend (AC1b)
  - [ ] Grep every consumer of `payment.lifecycleFailure` before choosing the mechanism
  - [ ] `SessionPackPurchasePage.vue:188` → clear only per AC1b; also clear in `onCardSaved`
  - [ ] **`SessionPackPurchasePageSpec.js:61-71` asserts the defect** (`'mints a new key once the server
        answered a failure'`) and changes deliberately; add: key kept on an unknown-outcome answer, cleared on
        the no-charge signal and on card save
- [ ] Task 3: RS06 observability (AC3)
  - [ ] Catch `ChargeOutcomeUnknownException` at `:144` → mark `UNKNOWN`, pack-specific tag, rethrow
  - [ ] Regex + runbook + `payments.json` panel; run `AlertDocumentationParityTest`
- [ ] Task 4: RS05 park-and-reapply (AC4, AC5)
  - [ ] Migration for `payment.stripe_pending_subscription_events`
  - [ ] Park in the same transaction as `insertIfAbsent`; re-read after parking; drain after commit, oldest
        first, per-event `try/catch`; set `lastStripeEventAt`; stop after `deleted`
  - [ ] Coach **and** player (`subscribePlayer:422-460`, `persistPlayerSubscription:489-`), `updated` **and** `deleted`
  - [ ] Scheduled purge of never-linked rows (ShedLock) with `warn` + metric; provisioning-checklist entry
  - [ ] ITs: the interleaving in AC4(1); out-of-order parked events; deleted-then-older-update
  - [ ] Confirm `SubscriptionServiceStripeReconciliationIT :91/:112` pass **unchanged**
- [ ] Task 5: RS07 superseded proration (AC6, AC7)
  - [ ] Migration for `payment.subscription_upgrade_reconciliations`; same-transaction insert on both
        upgrade branches; `error` log tag + metric; tag into the regex and runbook
  - [ ] `SubscriptionEntitlementIntegrityTest.item9…:562-568` verifies `never()).save(any())` on
        `paymentCoachSubscriptionRepository` only — a write to a different repository does not trip it; the
        realistic failure is an NPE from an un-mocked new dependency under `@InjectMocks`
  - [ ] Keep `ALWAYS_INVOICE` + `ERROR_IF_INCOMPLETE` (`StripeClient.java:309-311`)
- [ ] Task 6: RS09 declined-card revert (AC8, AC9)
  - [ ] Conditional-update repository method; `previous_card` on the attempt row; lookup before the card write
  - [ ] No revert on `UNKNOWN`/`UNRECONCILED`; first-time payer → null
  - [ ] Test: decline with X, retry with X succeeds, sync fires (correction 3)
  - [ ] Remove the dead local `customer` at `:140` (assigned, never read)
  - [ ] Completion Notes: UI never sends a card here; the `/save-payment-method` door is out of scope
- [ ] Task 7: RS08 pause date (AC10)
  - [ ] `SessionPackDashboardPage.vue:313` → local midnight; refresh the comment at `:271`
  - [ ] Add a regression test to `SessionPackDashboardPageSpec.js` (nothing existing changes; the assertion at
        `:180` checks the notify message, not the instant)
  - [ ] Reuse the idiom at `AvailabilityManagerPage.vue:192` / `WeeklyCalendar.vue:79`; no shared util. The
        DST-aware `zonedMidnightMs` (`BookingRequestPage.vue:552`, page-local, docblock `:545-548`) is only
        needed for the coach-zone variant AC10 puts out of scope
- [ ] Task 8: eval case + ledger (AC11, AC12)
- [ ] Task 9: docs (AC13)
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

**RS09 — `SessionPackPaymentService.purchasePack:98-192`, `getOrCreateStripeCustomer:398-413`.** The card
write is at `:402` (existing customer) or `:411` (new), reached from `:140`; the charge is at `:144`.
`purchasePack` has no `@Transactional`, the class's only transactional methods are `:255`, `:295`, `:336`,
no `TransactionTemplate` is injected, `getOrCreateStripeCustomer` is `private` and called in-class (so no
proxy), and `SessionPackPaymentResource` has zero `Transactional`. Therefore
`stripeCustomerRepository.save` runs in Spring Data's own transaction and **commits on return from
`:140`** — exactly one `stripe_customers` row, before the charge is attempted. There is no try/catch
around `:144` (the only `try`, `:154-186`, wraps `createPurchase`), so a decline propagates and nothing
reverts the write. Every later platform-initiated charge for that payer then picks up the declined card,
because `StripePaymentGateway.charge:138-145` re-reads it from that table, and `getSavedPaymentMethod:363-382`
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
`booking.pauseStartInPast`, whose message (`i18n/en-US/index.js:1992`) tells the user to "pick tomorrow or
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
- **A per-attempt idempotency key means a client retry is a new charge** — packs
  (`SessionPackPaymentService.java:143`) and upgrades (`SubscriptionService.java:1547`, prefix passed at
  `:305`). Any error you
  return must not invite a retry of a call that already moved money.
- Carried from deferred-167: a `REQUIRES_NEW` write **self-deadlocks** (55P03 → 409) if the caller already
  holds a lock over the same row; use a **scalar existence query** for pre-checks rather than loading the
  entity (a locked read returns an already-managed instance without refreshing it); Java 17, so
  `instanceof` chains and no switch patterns; a finite `jakarta.persistence.lock.timeout` is **inert** on
  PostgreSQL.
- **A card decline is a 4xx and becomes `payment.lifecycleFailure`** (`StripePaymentGateway.java:174-181`),
  indistinguishable from the refunded and unreconciled outcomes; `charge()` is shared with the booking path.
  Do not change a code a booking caller already depends on (AC1b).
- **Stripe replays a keyed failure.** Reusing a key after a decline returns the cached decline, and changing
  the payment method under the same key is an idempotency mismatch that `isOutcomeUnknown` treats as an
  unknown outcome. This is documented Stripe behaviour, not exercised in this review — pin it with a test.
- Migrations: additive first, FK/CHECK `NOT VALID` then `VALIDATE` later, `SET LOCAL lock_timeout` (not
  plain `SET`), `allow-*` markers immediately above their own statement with a real reason. V226 is **taken by the unmerged
  builder-packs story** — start from V227 and re-derive at dev time (Numbering).
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
- 2026-11-04 — owner settled all three design forks the same day, each on the recommended option (persist the attempt server-side; record the superseded proration and keep the 204; revert the card to the pre-charge snapshot). The ACs already encoded these, so nothing was rewritten; the reasoning and the rejected alternatives are recorded in the new `Owner decisions` section, including the two costs the owner accepted — RS07 still tells the user the upgrade worked, and RS09 compensates rather than prevents.
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
