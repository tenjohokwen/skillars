# Story review — skillars-deferred-168 (payments/Stripe bundle RS05-RS09)

**Audit date:** 2026-11-04
**Real HEAD:** `50eebd75` — `docs(story): record the owner's three decisions on skillars-deferred-168`
(docs-only commits on top of master `bed75f19`, so every `src/` and `src/frontend/` line is master's).
Citations were checked against this HEAD, not against any SHA the story quotes.
**Story:** `_bmad-output/implementation-artifacts/skillars-deferred-168-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
**Status when reviewed:** `ready-for-dev`.

**Independence caveat.** The author of this story also ran the four audits that produced its citations.
The four review layers here were therefore given the story and the HEAD only, and were not shown those
earlier audits' conclusions. Two of the author's own written "corrections" did not survive this review (H6, L5).

## Headline

Citations are in good shape: of ~105 checked, 3 drifted by a few lines and none could not be located.
**The design is not ready for development.** Six high-severity findings survived adversarial re-checking, and
four of them are holes in the ACs themselves — an implementer following AC1, AC3, AC4 or AC6 literally would
ship a regression or a table that cannot be written. One more is an identity collision with an unmerged
story. Recommendation: do not start `/bmad-dev-story` until H1 is decided and H2-H6 are folded into the ACs.

## 1. Citation verification (Layer 1)

~105 citations resolved and opened. **MATCH: all but the following.**

| Citation | Verdict | Corrected |
|---|---|---|
| `SessionPackDashboardPageSpec.js:177` | DRIFTED | `:177` sets `startDate`; the notify assertion is `:180` (block `:170-180`). The claim (asserts the message, not the instant) is true. |
| `SubscriptionEntitlementIntegrityTest:562` | DRIFTED (minor) | `:562` is the method; `never()).save(any())` is `:567`. |
| `ChargeOutcomeReconciler.java:203,227,259` | PARTIAL | `:227`,`:259` check `CAPTURE_PENDING`; `:203` is `findByChargeIdempotencyKey`; the driving query is `findReconcilableCharges` at `:124-125`. |
| `SubscriptionService.java:1547` quoted as `"sub-upgrade-" + UUID.randomUUID()` | form differs | `:1547` is `keyPrefix + UUID.randomUUID()`; the literal is passed at `:305`. Behaviour (fresh key per attempt) is true. |
| Hard-constraint bullet: "both comments `:259-267` and `:353-357` state rolling back after Stripe has billed is the worse outcome" | overstated | `:259-266` says "worst available outcome". `:353-357` says only that a rollback would leave Stripe at the new tier and the DB at the old one. |

Everything else matched with the quoted line, including all `purchasePack` lines (`:78,98,139,140,143,144,148,154,166,176-186,188-190,198-199,204,402,411`), the webhook lines (`:75,177-187,264-279,344-352`), both superseded branches, `V138/V195/V207`, the scheduler annotations, and every frontend line cited for RS06 and RS08.

## 2. Ledger and precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| RS05-RS09 text in `deferred-work.md` matches what the story paraphrases | ledger `:338-342` | CONFIRMED |
| `real-sample.md:314` maps RS05→337, RS06→350, RS07→359, RS08→368 | `real-sample.md:312,314` | CONFIRMED; RS09→the draft (`:312`). S07 is `fixed` and is not RS05. |
| `4db90562` / #362 introduced the `previousCard` snapshot | `git log -S`, `git blame`, subject | CONFIRMED (its body is about pack-pause; the snapshot is not described there) |
| "the fix landed after the sample was taken" | `git merge-base --is-ancestor` | **REFUTED** — see H6 |
| V207 adds keys to booking_payments + subscription tables only | `V207__…sql` | CONFIRMED (it also adds `subscribe_attempt_at` and an index; none for packs) |
| V195 `uq_spp_stripe_payment_intent_id` `:18-20`; V138 `stripe_webhook_events` columns | both files | CONFIRMED |
| "Next free migration is V226" | master tail V220-V225; **all branches** | **PARTLY** — free on master, **taken on an unmerged branch** — see H1 |
| `fix/payment-subscription-entitlements` | git branches/PRs, code comments | PARTLY — not a branch; a label in comments (`SubscriptionService :414,:1385`, test `:69`) for PR #337 / `eea53ee9` |
| deferred-167 constraints and "closed at seven rounds" | 167 story `:418-462`, Task 10 | CONFIRMED |
| Ledger rule "implemented items are deleted outright" | `deferred-work.md:8,30` | CONFIRMED |

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict |
|---|---|
| `PaymentApiAdvice` maps the whole `PaymentGatewayException` family to 422 | CONFIRMED (`@Order(HIGHEST_PRECEDENCE)`, no override on `payment.api`) |
| `purchasePack` non-transactional; card write commits before `:144`; no `try` around `:144` | CONFIRMED (no class-level annotation, no `TransactionTemplate`, `open-in-view: false`, private in-class call so no proxy) |
| `charge()` re-reads the card from `stripe_customers`, no payment-method parameter | CONFIRMED |
| `scheduleSubscriptionCardSync` compares the pre-overwrite snapshot | PARTLY — true for a first attempt; **false-as-claimed for a retry after a decline** — see H6 |
| `insertIfAbsent` precedes dispatch in one `@Transactional`; no-match path commits only that row; table has no outcome column | CONFIRMED |
| Subscribe window: row committed with null id, Stripe call outside any tx, persist sets id/status from create-time object, never sets `lastStripeEventAt` | CONFIRMED |
| Superseded upgrade branch is `warn` + bare `return`; Stripe before persist; endpoint 204 | CONFIRMED (`SubscriptionResource :72,:121`) |
| Sweep is alert-only, coach-only; `ChargeOutcomeReconciler` booking-only; `stripe_refund_failures` has no reader | CONFIRMED |
| `onCoachSubscriptionOrphaned` is `@EventListener` + `REQUIRES_NEW` | CONFIRMED |
| Pause: strict instant compare; `new Date('YYYY-MM-DD')` is UTC | CONFIRMED |
| "`handleSubscriptionDeleted` locks by a *different lock key*" | **PARTLY** — both `findBy…ForUpdate` are `PESSIMISTIC_WRITE` on the same `coach_subscriptions` row (different predicate). They serialize. The race is real regardless, because Stripe is called before the lock is taken. (L3) |
| "no generic way to clear an AdminAlert" justifies not shortening the grace window | CONFIRMED in substance — the cited `:50-57` comment states the premise verbatim, though it was written for the live-status allowlist. Not a defect. |

## 4. Corner cases, false assumptions, missed flows (survived Step 4b)

### HIGH

**H1 — The story id and migration number collide with an unmerged story already in review.**
A separate worktree `/Users/mokwen/dev/gitrepos/bluegithub/skillars-deferred-168`, branch
`skillars-deferred-168-builder-packs-become-the-purchasable-tier` (HEAD `b13047a4`, 2026-11-04 11:38,
status `review`, **not pushed**), is a *different* story under the *same id*. It adds
`V226__backfill_builder_packs_into_session_pack_tiers.sql`, a sprint-status entry
`skillars-deferred-168-builder-packs-become-the-purchasable-tier: review`, and a 44-file diff that touches
the payment module (`SessionPackOfferTest`, i18n bundles). Both claims in this story — "the next story is 168"
and "next free version is V226" — are false the moment that branch merges. Verified directly with
`git worktree list`, `git diff --name-only origin/master...<branch>`, `git ls-tree`.
*Evidence:* worktree `skillars-deferred-168`; `git diff --name-only origin/master...skillars-deferred-168-builder-packs-become-the-purchasable-tier -- src/main/resources/db/migration`.

**H2 — AC1 as written breaks retry-after-a-card-decline.**
A decline is a 4xx, so `isOutcomeUnknown` is false and `charge()` throws
`PaymentGatewayException("payment.lifecycleFailure")` (`StripePaymentGateway.java:~177-179`) — the **same
code and the same 422** as the refunded and unreconciled branches. AC1 says the frontend should keep the key
"for every answer that does not prove no charge landed", but the frontend cannot tell these apart. Keeping the
key across a proven decline then (a) replays Stripe's cached decline even after the parent tops up
(Stripe saves the result of a keyed request whether it succeeded or failed), and (b) if the parent saves a
different card first (`onCardSaved`, `SessionPackPurchasePage.vue`), changes the PaymentIntent's
`payment_method`, which Stripe rejects as an idempotency mismatch — which `isOutcomeUnknown` classifies as
**unknown outcome**. Today's "clear on any response" is *correct* for declines; the defect is only for the
non-decline failures. The ACs need a distinct, machine-readable code for proven no-charge outcomes (decline,
`packTierInactive`, validation), a key reset on card save, and a `DECLINED` attempt status that does not
block a retry.
*Evidence:* `StripePaymentGateway.java:174-181` (decline → `lifecycleFailure`), `:112-130,160-167`; `SessionPackPurchasePage.vue:75,174,188`.

**H3 — AC1/AC1a's "pre-charge rejections" include throws that happen *inside* `chargeAndCapture`, after the attempt row exists.**
`charge()` calls `resolveCoachStripeAccountId` (`payment.coachStripeNotConfigured`, `:117`),
`resolveCurrency` (`payment.configurationUnavailable`, `:123-125`) and the customer/payment-method checks
(`payment.noStripeCustomer`, `payment.noPaymentMethod`, `:138-143`) *before* creating the PaymentIntent. All
are no-charge outcomes, but `:144` is where the attempt row would already be written. Recorded as replayable
terminal outcomes they would block the retry after the coach fixes their Stripe setup; they must delete the
row or be marked non-replayable. `getOrCreateStripeCustomer` (`:140`, a Stripe call for a first-time payer)
fails after the row too.

**H4 — AC2 does not close the refunded-intent trap on the crash path.**
The refund runs at `:177`; the `REFUNDED` outcome would be a *separate* committed write after it. A process
death between them leaves the row `IN_PROGRESS`. A replay re-sends the same key, Stripe returns the original
(refunded) intent, `:148` finds no pack, and `createPurchase` succeeds — a pack funded by refunded money,
which is exactly what AC2 forbids. Replay of any non-terminal row must consult the PaymentIntent's refunded
state (`retrieveCharge` exists) before `createPurchase`. Related: replaying a recorded
`PACK_PURCHASE_UNRECONCILED` intent turns a captured-unrefunded charge into a pack (correct outcome) while the
`stripe_refund_failures` row stays open — an operator who later refunds it takes money back from a funded
pack. (Layer 4 also called the trap "unreachable from the UI today"; that is overstated — a crash yields no
HTTP response, so the key is retained and a replay is possible today. Downgraded, see §6.)

**H5 — AC4's park-and-reapply has an unspecified race that reproduces the bug it fixes, and an unsafe re-apply site.**
Interleaving: the webhook transaction reads no local row; `persistCoachSubscription` commits the link and
looks for parked rows — finding none, because the webhook's park insert is not yet committed; the webhook then
commits the park. That is the same window the story is closing, and "re-apply once a local row carries the
id" cannot see it. The handler must re-check for the row *after* parking (READ COMMITTED sees the committed
link) and apply directly if found, or a sweeper must collect it. Separately, if the re-apply runs inside
`persistCoachSubscription` (`@Transactional`, `:235-270`), an exception there rolls back the persist after
Stripe has billed — which `:259-266` calls "the worst available outcome". The re-apply needs its own
`try/catch` or an after-commit step.

**H6 — Correction 3 is itself wrong, and AC11/AC12 would instruct the dev to delete a true claim.**
The story says RS09's "the subscription sync is skipped later" is **false at HEAD** because `previousCard` is
snapshotted at `:139` before the write at `:140`. That holds only for the *first* attempt. After a decline the
declined card X is **committed** to `stripe_customers`; a retry with the same card reads
`previousCard == X` at `:139`, so `scheduleSubscriptionCardSync` returns early at `:204` and the live
subscriptions are never moved onto X. This is exactly the scenario the draft case's `F1` description narrates
("If the parent then retries with the same card and it succeeds, previousCard already equals paymentMethodId…"),
and it is true at HEAD. The story's other sentence — "the fix landed after the sample was taken" — is also
false: `4db90562` is an ancestor of both RS09 input revs (`b123c73de463`, `980eff338df6`); it is not an
ancestor of `cb23925514e5` (RS05-RS07). AC8's revert *does* fix the retry case, which is the useful thing to
say. AC11 ("correct, not copied") and AC12 must not rewrite a true clause. The story's count of "four ledger
claims refuted" is really three and a half.
*Evidence:* `SessionPackPaymentService.java:137-140,188-190,203-206`; `git merge-base --is-ancestor 4db90562 <rev>`; `evals/review/cases/_drafts/pending-card-default-before-charge.yaml` F1.

### MEDIUM

**M1 — AC3 would page on self-healing events.** The tag `[STRIPE_CHARGE_OUTCOME_UNKNOWN]` is logged from the
shared `charge()` used by *both* the booking path (4-arg `chargeAndCapture`) and the pack path (5-arg), and
`ChargeOutcomeReconciler` self-heals the booking case. It is also logged for a Stripe 409 idempotency
conflict (two tabs, same key). Adding it to the `MoneyIntegrityEvent` regex fires on both. Use a pack-specific
tag logged from `SessionPackPaymentService`; `deploy/lgtm/dashboards/payments.json:1314` already carries the
current token as a log panel and AC13 omits it.

**M2 — AC3's "durable row the same way the other two branches do" cannot be written.**
`ChargeOutcomeUnknownException` carries only the idempotency key (`ChargeOutcomeUnknownException.java:20-27`);
the PaymentIntent id is unknown. `payment.stripe_refund_failures` has `payment_intent_id text NOT NULL`,
`amount numeric NOT NULL`, `parent_id bigint NOT NULL`, `pack_tier_id uuid NOT NULL` (`V138:1933-1944`). The
record must be the attempt row with status `UNKNOWN`, keyed on the attempt key, and the story must say who
runs recovery (`findChargeByIdempotencyKey`, `StripePaymentGateway.java:~175`); nothing reads
`stripe_refund_failures` today.

**M3 — AC6's precedent does not fit.** A proration has no PaymentIntent id and no amount at the persist site
(`StripeClient.updateSubscriptionTier` returns only the `Subscription`, `:297-316`); the upgrade key is not
passed to `persist*TierUpgrade`; coach ids are UUID and player ids `Long`. It needs a new table keyed on the
subscription id, with polymorphic ids and a nullable amount. A plain same-transaction insert inside
`persistCoachTierUpgrade` is fine (the branch returns normally and commits); a `REQUIRES_NEW` write would be
wrong — `RefundReconciliationService.recordRefundFailure` is `REQUIRES_NEW`, and an FK to `coach_subscriptions`
would block on the `FOR UPDATE` held at `:341` and self-deadlock (the story's own carried constraint).

**M4 — AC8's revert has no mechanism.** `StripeCustomer` has no `@Version` (`StripeCustomer.java:19-32`), and the
gateway saves the same entity (`setLastPaymentIntentId`, `StripePaymentGateway.java:~207-211`) on success. A
load-modify-save revert can clobber a concurrent `savePaymentMethodAndScheduleSync`, whose sync then sees the
card changed and skips itself as superseded (`SubscriptionService.java:688-690`). It needs a conditional
column update (`… WHERE stripe_payment_method_id = :attempted`). Also: `previousCard` must be stored on the
attempt row, because on a replay after a crash it is re-read at `:139` and may already be the new card; the
attempt lookup must precede the card write; do **not** revert on `ChargeOutcomeUnknownException` (the charge
may have captured); first-time payer → restore to null and keep the row (null is handled downstream).

**M5 — AC4 gives no ordering baseline.** `persistCoachSubscription`/`persistPlayerSubscription` never set
`lastStripeEventAt`, so `isStaleEvent` has nothing to compare; a parked older event (e.g. `incomplete` from
the creation sequence) would overwrite the persist's correct `ACTIVE`. Several parked events for one
subscription need a defined re-apply order, and `handleSubscriptionDeleted` ignores the stale guard.

**M6 — AC4 covers only `customer.subscription.updated`.** `handleSubscriptionDeleted` has the identical drop
and does not even call the orphan alert, so a cancel in the signup window leaves the coach entitled;
`invoice.paid` is a silent no-op when no row matches. The story neither covers nor declares these out of scope.

**M7 — RS09's fix is unreachable from the shipped UI, and a decision rationale rests on an unenforced premise.**
The only frontend caller passes `undefined` for the payment method
(`SessionPackPurchasePage.vue:184`; `booking.store.js:309`; no other caller exists). `getOrCreateStripeCustomer`
writes only when the id is non-blank, so the pre-charge card write is a no-op for every UI purchase and AC8
affects only direct API clients. The "declined card stays saved" problem for UI users arises through
`/save-payment-method`, which the Owner-decisions section rejected fixing on the ground that it "follows a
confirmed, customer-scoped SetupIntent so the card is already Stripe-authenticated". The server does not
enforce that: `SessionPackPaymentResource.savePaymentMethod` passes `request.paymentMethodId()` to
`SubscriptionService.savePaymentMethodAndScheduleSync`, whose only guard is `StripeIdFormat.requirePaymentMethodId`
(`:659`). Both facts bear on a decision the owner already made; they are surfaced, not decided.

**M8 — The "tests that change deliberately" table is partly wrong.**
`SubscriptionServiceStripeReconciliationIT :91/:112` call `subscriptionService.reconcileStripeSubscriptions()` —
the **daily sweep** — which AC4/AC5 do not change, so they will not change and passing is not a signal.
`SubscriptionEntitlementIntegrityTest :562-568` verifies `never()).save(any())` on
`paymentCoachSubscriptionRepository`; a write to a *different* repository does not trip it. The realistic
failure is an NPE if `@InjectMocks` leaves a new dependency null. Only `SessionPackPurchasePageSpec.js:61-71`
is correctly listed.

### LOW

- **L1** Parked rows for subscriptions that never link (`cancelDuplicateSubscription`, `SubscriptionService :190,:453,:1522` deliberately cancels unlinked duplicates) have no retention.
- **L2** AC1 leaves undefined: what the loser of a concurrent same-key insert returns (a naive "catch and charge" yields a Stripe 409, which is an unknown-outcome 422); what happens when the optional header is absent (`:143` mints a random UUID, so there is nothing to dedup); a stuck `IN_PROGRESS` row outliving Stripe's key lifetime (~24h); storing tier and player on the row so reuse across tiers is rejected up front.
- **L3** Wording: "different lock key" (Layer 3 table); `:353-357` "worse outcome"; `"sub-upgrade-" + UUID.randomUUID()` form.
- **L4** Citation drifts listed in §1; `fix/payment-subscription-entitlements` is PR #337.
- **L5** AC11's "add/keep expected findings" on 337/350/359/368 is already satisfied: each case already carries the finding with `fix_pr` set to its original PR. The only real edit is the draft's `fix_pr`. Its `F1` title and description are **correct** (H6) — do not "correct" them.

## 5. Did not survive re-verification

| Raised by | Claim | Why it died |
|---|---|---|
| Layer 2 | The draft case's retry scenario is "false at HEAD" | It is true: a declined card is committed, so the retry's snapshot equals it (H6). |
| Layer 2 | The draft's anchor `StripePaymentGateway.chargeAndCapture` does not exist (it is in `SessionPackPaymentService`) | `chargeAndCapture` is declared in `StripePaymentGateway` (4-arg and 5-arg). |
| Layer 3 | The `:50-57` grace-window cite is wrong | The comment states the premise ("no generic way to clear an AdminAlert once raised") verbatim, albeit for the allowlist decision. Kept as a supporting cite. |
| Layer 4 | The refunded-intent trap is "unreachable from the UI today" | A crash after the refund yields no HTTP response, so the key is retained and a replay is possible today. H4 stands; the sub-claim is dropped. |
| Layer 4 | AC10 DST / east-of-UTC / single-sender concerns | Checked and fine (no Invalid Date, one sender, correct east of UTC). |

## 6. Not independently re-checked

Stripe's replay-the-cached-decline and ~24h key lifetime (documented Stripe behaviour, not exercised here —
the implementer should pin it with a test before relying on it); the `payment.idempotencyKeyReused` 409
semantics under a changed tier; that adding a token to `grafana-alerts.yml` leaves
`AlertDocumentationParityTest` green (Layer 4 reports it matches alert titles only); Flyway-linter behaviour
for a new table's unique index; the line numbers of Dev-Notes bullets not listed in §1.

## 7. Recommendation

| Area | Confidence |
|---|---|
| Citations and file:line references | High — every row opened; 3 minor drifts |
| Mechanistic descriptions of existing code | High — full method bodies read, transaction boundaries traced |
| The four "refutations" in Corrections 1-5 | **Mixed** — 1 (422), 2 (untraceable path) and 4 (sweep) hold; **3 is wrong as written (H6)**; 5 holds |
| The ACs as a design | **Low until H2-H5 are fixed** — they are specification holes, not typos |
| Owner decisions | Still valid as chosen, but H2/H3 widen the attempt-record status model, M7 changes the facts under the RS09 decision, and M3 undercuts "following the existing precedent" |

Next: decide H1 (renumber this story, and take the next free migration only after the builder-packs branch
merges), then apply H2-H6, M1-M8 to the story. Nothing in this report has been applied.
