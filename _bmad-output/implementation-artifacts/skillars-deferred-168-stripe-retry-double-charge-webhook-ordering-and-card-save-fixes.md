# Story skillars-deferred-168: Stripe retry double-charge, webhook ordering, superseded proration, card-save and pause-date fixes

Status: ready-for-dev

## Story

As a **parent or coach paying through Skillars**,
I want **a failed payment attempt to be safe to retry, a subscription change to be applied even if its webhook races the signup, money I have paid to either grant what I bought or be recorded for recovery, a card that was declined not to become my saved default, and the earliest pause date the picker offers me to be accepted**,
so that **I am never charged twice, never silently left without what I paid for, and never blocked by a date my own screen told me to choose**.

## Context

Owner-chosen bundle of five payments items filed on the review-evals baseline sample and verified against
**master `bed75f19`** (the real HEAD at story creation — not any SHA quoted inside the ledger). Branch:
`skillars-deferred-168-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes`.

They are bundled because four of the five live in `platform.payment.service` and two of them touch
`SessionPackPaymentService.purchasePack`'s pre-charge/post-charge ordering directly; splitting them would
put two stories in the same thirty lines.

Ledger source: `_bmad-output/implementation-artifacts/deferred-work.md`, section
`## Deferred from: review-evals baseline real-sample, owner-verified (2026-11-03)` — items RS05, RS06,
RS07, RS08, RS09.

**Every claim below was re-verified against current source by four parallel read-only audits. Four ledger
assertions were wrong. Read the next section before trusting anything the ledger says.**

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

**3. RS09's second clause is false at HEAD.** It claims "the subscription sync is skipped later". It is
not: `scheduleSubscriptionCardSync`'s predicate compares `previousCard`
(`SessionPackPaymentService.java:204`), and `previousCard` is snapshotted at `:139` **before**
`getOrCreateStripeCustomer` runs at `:140`. A genuinely new card is never equal to that snapshot, so the
sync fires. `git log -L 137,141` shows the snapshot was added by `4db90562` (#362) — i.e. the fix landed
after the sample was taken, and the ledger's "HEAD identical" is wrong for this clause. RS09's *first*
clause (declined card stays saved) is confirmed and is what this story fixes.

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

**RS06 — persist the attempt server-side (option a).** `V226` adds a pack attempt record, unique on
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

## Acceptance Criteria

**AC1 — a retry after an answered failure cannot charge a second time.** *(OWNER DECIDED 2026-11-04, option a.)* Pack purchase gains a
server-side attempt record, written **before** the Stripe call, keyed on `(parentId, attemptKey)`, so
dedup no longer depends solely on the client resending the same key. A replay of a known attempt key
returns that attempt's recorded outcome and charges nothing. The frontend stops discarding the key on any
HTTP response: it keeps the key for every answer that does not prove no charge landed, and clears it only
on success, on a pre-charge rejection (400 `payment.invalidIdempotencyKey`, and the validation failures
raised before `:144`), and on a change of `selected`.

**AC1a — the attempt record's shape, because `purchasePack` has no transaction to join.** The record is
unique on `(parent_id, attempt_key)` and is written in its **own committed transaction** before `:144`
(the method is deliberately non-`@Transactional`, so there is nothing to enlist in and an unflushed write
would not be visible to a concurrent replay). Its terminal outcome is written in a second committed step
after the charge resolves. Note the interplay with AC8: `getOrCreateStripeCustomer`'s card write at `:140`
is *also* a pre-charge committed write, so after this change `purchasePack` has two of them — keep the
attempt record first, so a crash between the two leaves a replayable attempt rather than an orphaned card
change.

**AC2 — the refunded-attempt trap is closed.** When a replayed attempt's recorded outcome says the charge
was captured and then **refunded** (`SessionPackPaymentService.java:176-184`), the server must answer a
terminal error and must **not** create a pack. Key retention alone is not sufficient and must not be
shipped alone: with the same key, Stripe replays the original intent, `:148`'s lookup finds no pack
because the insert failed, and the retry would hand back a pack funded by refunded money.

**AC3 — the untraceable charge path becomes observable.** `STRIPE_CHARGE_OUTCOME_UNKNOWN` is added to the
`MoneyIntegrityEvent` alert regex (`deploy/lgtm/grafana-alerts.yml:638-648`) and to its runbook section
(`docs/deployment/monitoring.md#MoneyIntegrityEvent`), and the unknown-outcome path records a durable row
the same way the other two money-at-risk branches already do, so recovery does not depend on log
retention. `AlertDocumentationParityTest` must stay green.

**AC4 — a subscription transition that lands during the signup window is applied, not dropped, for
coach *and* player.** When `handleSubscriptionUpdated` finds no local row
(`StripeWebhookService.java:271-275`), the payload is parked in the **same transaction** that records the
event as processed, and re-applied once a local row carries that `stripeSubscriptionId`. The parked
payload must be applied through the existing `subscriptionService.handleSubscriptionWebhook` path so the
staleness and normalisation rules still run.

**AC5 — AC4 must not be bought by making the orphan path louder.** `SUBSCRIBE_RACE_GRACE_WINDOW`
(`StripeWebhookService.java:75`) stays as it is, the orphan path still must not throw
(`:287-293`, `:451-454`: Stripe must never be put into indefinite retry on a healthy subscription), and
`insertIfAbsent` stays **before** dispatch (`:179`) so the concurrent-retry race it closes stays closed.

**AC6 — a paid proration that cannot be granted is recorded and surfaced.** *(OWNER DECIDED 2026-11-04, option a: record and keep the 204.)* The superseded branches
(`SubscriptionService.java:341-346` coach, `:577-580` player) stop being a bare `log.warn` + `return`:
they record a durable money-moved-no-entitlement row following the existing
`RefundReconciliationService` / `stripe_refund_failures` precedent, and increment a metric. The fix must
**not** grant the tier anyway — a row whose Stripe link is null or cancelled would re-grant a paid tier
with no subscription, which is the defect `fix/payment-subscription-entitlements` closed
(`:414-416`, `:1385-1387`).

**AC7 — AC6 must not cause a second proration.** The upgrade idempotency key is per-attempt
(`"sub-upgrade-" + UUID.randomUUID()`, `SubscriptionService.java:1547`), so any client retry is a new key
and a new charge. Whatever AC6 returns must not invite the client to retry the upgrade, and the Stripe
call must stay outside the DB transaction (`:353-357` — rolling back after Stripe has billed is the
stated worse outcome).

**AC8 — a declined card does not remain the payer's saved default.** *(OWNER DECIDED 2026-11-04, option a: revert to the snapshot.)* When `chargeAndCapture`
(`SessionPackPaymentService.java:144`) throws, the `stripe_customers` default written at `:402`/`:411` is
restored to the `previousCard` snapshot already taken at `:139`. The compensating write must be safe
against a concurrent legitimate save of the same column, and must not enqueue a card sync (the post-charge
gate at `:188-190` exists so a declined card is never pushed onto live subscriptions — keep it).

**AC9 — AC8 does not break paying with a different card.** `StripePaymentGateway.charge` **re-reads** the
card from `stripe_customers` (`:138-145`) and has no payment-method parameter, so the pre-charge write is
the transport for the new card. Removing or deferring it would break pack purchase with a new card
entirely and yield `payment.noPaymentMethod` for a first-time payer. `purchasePack` must stay
non-`@Transactional` (javadoc `:78`; the whole compensating-refund design at `:154-186` depends on it).
The revert must also be **idempotent across replays**: `:148-151` and `:173-175` can re-enter the method
for the same intent, so a compensating write that assumes it runs once per charge is wrong.

**AC10 — the pause start date the picker offers is accepted, and means the day the parent picked.**
`SessionPackDashboardPage.vue:313` sends local midnight rather than UTC midnight, so the earliest offered
date is never refused by `PackSessionService.java:545` and the pause no longer begins on the previous
evening for users west of UTC. **Out of scope, declared here so it cannot inflate:** whether a pause day
should belong to the coach's calendar zone instead of the parent's. That variant needs a timezone field on
`SessionPackPurchaseResponse`, its mapper and backend tests, and it is a policy decision rather than a bug
fix.

**AC11 — the eval cases are updated.** Per `evals/review/results/baseline-2026-11-02/real-sample.md:314`:
add/keep expected findings on `337-subscription-entitlement-integrity.yaml` (RS05),
`350-pack-refund-rounding-remainder.yaml` (RS06), `359-charge-idempotency-unknown-outcome.yaml` (RS07),
`368-pack-status-badges.yaml` (RS08), and flip `fix_pr: pending` → this PR number on
`evals/review/cases/_drafts/pending-card-default-before-charge.yaml` (RS09). That draft's `F1` title
asserts the refuted "subscription sync is skipped later" clause (correction 3) and must be corrected, not
copied.

**AC12 — the ledger stops carrying the four false claims.** `deferred-work.md`'s RS05, RS06 and RS09
entries are corrected in place for corrections 1–4 above, and RS08's entry gains the silent-cancellation
half (correction 5). Items fully implemented by this story are deleted outright, per that file's own rule.

**AC13 — docs.** `docs/dev-docs/payment/index.html` (+ rebuilt `search-index.js`) for the new attempt
record and the parked-webhook path; `docs/deployment/monitoring.md` for AC3;
`docs/deployment/provisioning-checklist.md` if AC1's migration or the alert change needs a deploy step.
`check-docs` must pass.

**AC14 — `/pr-review` on the final commit.** Run it, fix what it finds, run it again. Deferred-167 closed
this at seven rounds by owner decision rather than on a clean round; if this one is still finding
behaviour defects after five, raise it rather than looping silently.

## Tasks / Subtasks

- [ ] Task 1: RS06 server-side attempt record (AC1, AC2)
  - [ ] `V226` migration for the pack attempt record — additive only, follow
        `docs/deployment/migration-conventions.md`; `SET LOCAL lock_timeout`; no blocking index on a hot
        table without an `allow-*` marker carrying a real reason
  - [ ] Write the attempt **before** `chargeAndCapture`, mirroring `stripe_webhook_events`' insert-first
        shape; record the intent id and terminal outcome after
  - [ ] Replay returns the recorded outcome; refunded outcome answers terminal and creates no pack (AC2)
  - [ ] `mvn -q -Dtest=MigrationConventionLintTest test`
- [ ] Task 2: RS06 frontend key retention (AC1)
  - [ ] `SessionPackPurchasePage.vue:188` stops clearing on every `e?.response`
  - [ ] Keep the `attemptKey ??=` no-response reuse (`:182`), the in-flight guard (`:179`) and the reset
        on `selected` change (`:174`)
  - [ ] **`SessionPackPurchasePageSpec.js:61-71` asserts the defect** (`'mints a new key once the server
        answered a failure'`). It changes deliberately — do not treat it as a regression
- [ ] Task 3: RS06 observability (AC3)
  - [ ] `STRIPE_CHARGE_OUTCOME_UNKNOWN` into the `MoneyIntegrityEvent` regex + runbook section
  - [ ] Durable row on the unknown-outcome path
  - [ ] `AlertDocumentationParityTest` green
- [ ] Task 4: RS05 park-and-reapply, coach and player (AC4, AC5)
  - [ ] Park the payload in the same transaction as `insertIfAbsent` (`StripeWebhookService.java:179`)
  - [ ] Re-apply via `subscriptionService.handleSubscriptionWebhook` once the id is linked
  - [ ] Cover the player path too — `subscribePlayer:422-460`, `persistPlayerSubscription:489-493`
  - [ ] `SubscriptionServiceStripeReconciliationIT:91` and `:112` **assert the current skip-and-suppress
        behaviour** and change deliberately
- [ ] Task 5: RS07 superseded proration (AC6, AC7)
  - [ ] Durable record + metric on both superseded branches
  - [ ] **`SubscriptionEntitlementIntegrityTest:562`** asserts `never()).save(any())` on this path — a
        write there fails it, which is the signal, not an obstacle
  - [ ] Keep `ALWAYS_INVOICE` + `ERROR_IF_INCOMPLETE` (`StripeClient.java:309-311`) so a decline still
        means no grant
- [ ] Task 6: RS09 declined-card revert (AC8, AC9)
  - [ ] Restore `previousCard` when `:144` throws; keep `purchasePack` non-transactional
  - [ ] Keep the `customerExisted` gate (`:188`) and the post-charge-only sync
  - [ ] Remove the dead local `customer` at `:140` (assigned, never read)
  - [ ] **Second door, deliberately out of scope:** `/save-payment-method` also overwrites the default
        unconditionally (`SubscriptionService.java:657-665`), but it follows a confirmed, customer-scoped
        SetupIntent, so the card is already Stripe-authenticated. Record this in Completion Notes rather
        than widening the story
- [ ] Task 7: RS08 pause date (AC10)
  - [ ] `SessionPackDashboardPage.vue:313` → local midnight; refresh the comment at `:271`
  - [ ] Add a regression test to `SessionPackDashboardPageSpec.js` (nothing existing needs changing;
        `:177` asserts on the notify message, not the instant)
  - [ ] Reuse the existing idiom (`AvailabilityManagerPage.vue:192`,
        `WeeklyCalendar.vue:79`); do not create a shared util for one call site. A stronger DST-aware
        helper already exists as `zonedMidnightMs` (`BookingRequestPage.vue:552`, page-local and never
        exported, with a docblock at `:545-548` warning about this exact mistake) — it is only needed for
        the coach-zone variant AC10 puts out of scope, so do not extract it for this fix
- [ ] Task 8: eval cases + ledger (AC11, AC12)
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
`CAPTURE_PENDING` rows (`:203,227,259`), and a pack purchase never writes one.
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
`findByStripeSubscriptionIdForUpdate` — a *different* lock key from `findByCoachIdForUpdate` — and is
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
javadoc `:198-199`): a declined card is never pushed onto live Stripe subscriptions.

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
  (`SubscriptionService.java:259-267`) and the tier-change path (`:353-357`). Both comments state that
  rolling back after Stripe has billed is the *worse* outcome. Do not "fix" ordering by pulling Stripe in.
- **`insertIfAbsent` stays before dispatch** (`StripeWebhookService.java:179`); moving it after would
  reopen the concurrent-retry race `ON CONFLICT DO NOTHING` closes.
- **The orphan path must never throw** (`:287-293`, `:451-454`): that would put Stripe into indefinite
  retry against a healthy subscription.
- **Do not shorten `SUBSCRIBE_RACE_GRACE_WINDOW`.** There is no generic way to clear an `AdminAlert` once
  OPEN (`:50-57`), so that trades a silent drop for permanently unresolvable false alerts.
- **A per-attempt idempotency key means a client retry is a new charge** — packs
  (`SessionPackPaymentService.java:143`) and upgrades (`SubscriptionService.java:1547`). Any error you
  return must not invite a retry of a call that already moved money.
- Carried from deferred-167: a `REQUIRES_NEW` write **self-deadlocks** (55P03 → 409) if the caller already
  holds a lock over the same row; use a **scalar existence query** for pre-checks rather than loading the
  entity (a locked read returns an already-managed instance without refreshing it); Java 17, so
  `instanceof` chains and no switch patterns; a finite `jakarta.persistence.lock.timeout` is **inert** on
  PostgreSQL.
- Migrations: additive first, FK/CHECK `NOT VALID` then `VALIDATE` later, `SET LOCAL lock_timeout` (not
  plain `SET`), `allow-*` markers immediately above their own statement with a real reason. Next free
  version is **V226**.
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

These change **deliberately**. Each is a signal that the fix landed, not a regression to route around:

| Test | Asserts |
|---|---|
| `SessionPackPurchasePageSpec.js:61-71` | a new key is minted after a 500 — i.e. RS06's defect |
| `SubscriptionServiceStripeReconciliationIT:91` | a live subscription already tracked locally raises no alert |
| `SubscriptionServiceStripeReconciliationIT:112` | the grace window suppresses the orphan alert |
| `SubscriptionEntitlementIntegrityTest:562` | `never()).save(any())` on the superseded upgrade path |

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
  "subscription sync is skipped later" is false at HEAD, fixed by the `previousCard` snapshot that
  `4db90562` (#362) added; RS05's sweep claim understates the problem three ways (alert-only by design,
  coach-only, and the marking is the caller's); and RS08 reports only the rejection, not the silent
  cancellation of a booking on a day nobody paused. The story records the two traps that make the obvious
  fixes wrong — key retention alone would hand back a pack funded by refunded money, and granting the tier
  on a superseded row would re-grant a paid tier with no subscription — and names the four existing tests
  that assert the current defective behaviour and must change deliberately.
- 2026-11-04 — owner settled all three design forks the same day, each on the recommended option (persist the attempt server-side; record the superseded proration and keep the 204; revert the card to the pre-charge snapshot). The ACs already encoded these, so nothing was rewritten; the reasoning and the rejected alternatives are recorded in the new `Owner decisions` section, including the two costs the owner accepted — RS07 still tells the user the upgrade worked, and RS09 compensates rather than prevents.
