# Story review (pass 2) — skillars-deferred-169 (payments/Stripe bundle RS05-RS09)

**Audit date:** 2026-11-04
**Real HEAD:** `4d348462` — `docs(story): renumber skillars-deferred-168 to 169 and apply /mto-story-review`
(docs-only on master `bed75f19`; every `src/` and `src/frontend/` line is master's). Citations were checked against
this HEAD, not any SHA the story quotes.
**Story:** `_bmad-output/implementation-artifacts/skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
**Status when reviewed:** `ready-for-dev`. This report supersedes pass 1 (which reviewed the story as `skillars-deferred-168`).

**Scope of this pass.** Pass 1's fixes were rewritten into ~200 new lines of ACs and tasks. This pass concentrated
on that new text and on whether each pass-1 fix landed correctly. The four layers were given the story and HEAD only.
Counts: ~140 citations, ~25 attributions, ~25 behavioural claims, 11 corner-case areas.

## Headline

**The story is still not ready for development, and the rewrite is where the remaining problems are.** Three
high-severity findings survived adversarial re-checking: two were *introduced by pass 1's own fixes*
(H1: clearing the key on card save; H3: AC2's lookup cannot see refunds and treats "not found" as safe), and one was a pass-1 fix that does not
close what it claims to (H2: the AC4 race). Citations remain sound: 7 drifted (all in the new text), none
unlocatable, no quoted code is non-verbatim.

### Did pass 1's fixes land?

| Pass-1 finding | Fix in the story | Verdict now |
|---|---|---|
| H1 id/V226 collision | renumbered to 169, V227+ | **Landed, but incomplete** — a second V226 holder exists (M8) |
| H2 decline must stay retryable | AC1 status model, AC1b signal, key reset on card save | **Partly** — the signal is right; the card-save reset is a new double-charge hole (H1) and the wire shape is unspecified (M1) |
| H3 failures inside `charge()` are not pre-charge | those become `NO_CHARGE` | **Landed** |
| H4 refund-then-crash | AC2 resolves truth from Stripe | **Partly** — right idea, wrong tools (H3) |
| H5 park/persist race | "re-read after parking" | **Does not close the race** (H2) |
| H6 Correction 3 wrong | replaced | **Landed** (verified: `4db90562` is an ancestor of both RS09 input revs; the retry scenario is true) |
| M1 alert tag, M2 durable row, M3 AC6 table, M4 AC8 mechanism, M5-M7, M8 test table | applied | Mostly landed; each has a residual gap below (M5, M6, M7, M10) |

## 1. Citation verification (Layer 1)

~140 citations opened; **MATCH** except the following (all in text added by pass 1):

| Citation in story | Verdict | Corrected |
|---|---|---|
| `StripePaymentGateway.java:174-181` (decline → `lifecycleFailure`) | DRIFTED | decline mapping is **`:185-187`** (comment `:174-179`); a non-succeeded intent maps to the same code at **`:198-201`** |
| `StripePaymentGateway.java:123` ("null is handled at") | DRIFTED | null `paymentMethodId` is handled at **`:141-143`**; `:123` is the `configurationUnavailable` throw |
| `:117` `coachStripeNotConfigured` | DRIFTED | the call is **`:116`**; the throw is at **`:635`** inside `resolveCoachStripeAccountId` |
| `:123-125` `configurationUnavailable` | DRIFTED (minor) | throw `:123`, catch `:121-124` |
| `findChargeByIdempotencyKey ~175` | DRIFTED | declaration **`:274`** (body `:273-293`); `retrieveCharge` **`:296`**; both declared in `contract/PaymentGateway.java:52,55` |
| `BookingRequestPage.vue` docblock `:545-548` | DRIFTED (minor) | docblock is **`:542-551`** |
| `ChargeOutcomeUnknownException.java:20-27` | loose | field `:19`, ctor `:21-24`, getter `:26-28` |
| `monitoring.md#MoneyIntegrityEvent` | nit | heading `:294`; the alert's runbook field uses the lowercase slug `#moneyintegrityevent` |

Re-confirmed this pass: all `purchasePack` lines, all `SubscriptionService`/`StripeWebhookService` lines, `V138`, `V195`,
`V207`, both superseded branches, the frontend lines for RS06/RS08, the evals findings F12/F3/F10/F4, and the draft case's `F1`.

## 2. Ledger & precedent attribution (Layer 2)

| Claim | Verdict |
|---|---|
| Unmerged `skillars-deferred-168-builder-packs…` in worktree `…/skillars-deferred-168`: V226, status `review`, edits `SessionPackOfferTest` + i18n | CONFIRMED — but see M8: it is **pushed** now (`origin/…`, HEAD `42f86d0f`), ~49 files, and also edits `SessionPackPaymentService.java`, `SessionPackTier.java`, `CreateSessionPackTierRequest.java`, `CoachSessionPackAdapter.java` |
| "Only that branch holds V226" | **REFUTED** — `feature/pitchside-reg-neutral` (worktree `…/skillars-pitchside`, unmerged, 8 commits ahead) also adds `V226__registration_phone_in_use_email_template.sql`. Verified with `git ls-tree` over every branch. |
| Nothing holds 169/170/V227+ | CONFIRMED |
| Correction 3: `4db90562` (#362) ancestor of `b123c73de463`/`980eff338df6`, not of `cb23925514e5`; draft F1 narrates the retry | CONFIRMED |
| PR #337 / `eea53ee9`; `fix/payment-subscription-entitlements` is a comment label | CONFIRMED (`SubscriptionService :414-416`, `:1385-1387`) |
| AC11: cases 337/350/359/368 already carry F12/F3/F10/F4 with numeric `fix_pr` + `fix_merge` | CONFIRMED |
| AC11: "move it out of `_drafts/` following the README" | **PARTLY** — `evals/review/README.md:117-120` also requires dropping the `pending-` prefix with **`id` == filename**, and a second step *after* the squash merge (`fix_merge`, drop `input_rev`). See M9. |
| AC12 "RS09's entry gains one sentence" | already done by the annotation committed in `4d348462` → the instruction is stale |
| Carried deferred-167 constraints (55P03, scalar existence, inert `lock.timeout`, Hikari, `ClockProvider`, ceiling 17/14) | CONFIRMED (167 story `:413-462`; `IntegrationTestConventionTest:109`, `pr-build.yml:127`) |
| "scheduler conventions" for a new ShedLock job | no such file; the real references are `docs/deployment/scheduler-lock-tuning.md`, `provisioning-checklist.md` §6 (`:1098`), `SubscriptionSchedulerLockTest`, `ConfigStartupAssertionTest` (all exist) — L4 |
| Cross-document counts/ids/V227 | consistent; one internal contradiction (L5) |

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict |
|---|---|
| Decline → `isOutcomeUnknown` false → `PaymentGatewayException("payment.lifecycleFailure")` → 422; pinned by `StripePaymentGatewayTest:865-872` | CONFIRMED |
| `isOutcomeUnknown`: `IdempotencyException`/409/null/0/≥500/`ApiConnectionException`; SDK 28.4.0 maps error type `idempotency_error` → `IdempotencyException` | CONFIRMED (jar disassembled). *That Stripe returns `idempotency_error` for a param mismatch is documented behaviour, not run here.* |
| `charge()` shared with bookings; booking callers catch the base class; nothing branches on `lifecycleFailure` | CONFIRMED — `PaymentLifecycleService :195,:259,:266,:473,:480`, `CashOutService :141,:217`; the only code-keyed branch is `PaymentApiAdvice:20` (`coachStripeNotConfigured`). The 4-arg overload has no live caller. |
| Wire shape: `ErrorDto{helpCode, errorMsg{errorKey,message}, fieldErrors}`; for `PaymentGatewayException` all three carry the code; for `ResponseStatusException` (`ApiAdvice:132-136`) the reason lands in `errorMsg.message` and `errorKey` is `generic.requestError` | CONFIRMED |
| The page reads `data.message` (`SessionPackPurchasePage.vue:189`) | CONFIRMED — it is **always undefined**, so the parent never sees any payment code today |
| `purchasePack` ordering: no Stripe call in `:100-135`; `:137-139` reads; `:140` writes (and calls Stripe for a first-time payer); `:144` charges | CONFIRMED |
| `findChargeByIdempotencyKey(parentId, key, createdAfter)` needs the payer's `stripe_customers` row; lists PaymentIntents `limit(100)`, `created ≥ createdAfter`, matches `metadata.chargeKey`; returns empty if no row/customer id | CONFIRMED |
| `ChargeLookup(paymentIntentId, status, amount)` can express a refund | **REFUTED** — a refund does not change a PaymentIntent's status. Refund state needs `getRefundableAmount` (`PaymentGateway.java:107`, impl `StripePaymentGateway.java:541-562`: captured − refunded). H3 |
| `handleSubscriptionDeleted` returns silently on no match, no alert | CONFIRMED (`:363-366`) |
| `handleSubscriptionWebhook` applies `isStaleEvent`, sets `lastStripeEventAt = newer(...)` in every branch, silent no-op if the row is missing | CONFIRMED (`SubscriptionService :1249-1325`) — so AC4's "the drain must set it" is redundant (L2) |
| `persistCoachSubscription`/`persistPlayerSubscription` are called via `@Lazy self` from non-transactional `subscribeCoach`/`subscribePlayer`; "after the persist returns" is after commit | CONFIRMED (`:186`, `:450`) |
| `persist*TierUpgrade` re-reads `FOR UPDATE`; the superseded branch returns normally; `RefundReconciliationService.recordRefundFailure` is `REQUIRES_NEW` | CONFIRMED |
| "The proration has no amount/PaymentIntent, so no usable identifier" (AC6) | **REFUTED** — `persist*TierUpgrade` receives `Subscription updated` and `StripeClient` already uses `sub.getLatestInvoice()` (`:356`, `:415`). The invoice id is available. M10 |
| `StripeCustomer` has no `@Version`; the gateway's post-capture save is success-path only; the repo has no `@Modifying` | CONFIRMED. A conditional UPDATE needs `@Transactional` on the repository method (`StripeOAuthStateRepository:18-27` is the house precedent) — `purchasePack` is non-transactional; otherwise `TransactionRequiredException`. `NativeModifyingVersionAuditTest` is not tripped (no `@Version`). |
| Webhook isolation | CONFIRMED READ COMMITTED (no override) |

## 4. Corner cases, false assumptions, missed flows (survived Step 4b)

### HIGH

**H1 — AC1b clears the key on card save (and `watch(selected)` already clears it on a pack change), which double-charges after an unknown outcome.**
Trigger: attempt 1 ends `UNKNOWN` (timeout/409/`processing`) so the key is kept and the charge may have captured; the parent
assumes the card is at fault, saves a card (`onCardSaved`, `SessionPackPurchasePage.vue:138-140`) or changes the pack
(`:174`), and buys. The key is cleared, a fresh key mints, and a *new* PaymentIntent is created while the first may
be captured. A proven decline already clears the key through the no-charge signal, so the extra clear on card save only
ever fires when the key was *deliberately kept* — it removes exactly the protection being kept. The `selected` clear
predates the story but has the same hole. AC1a's "never re-charged" is per key only.
*Needed:* make the **server** the guard — before inserting a new attempt, resolve every non-terminal attempt of that
parent (AC2; the row has `parent_id` and the key, so no client cooperation is needed) and refuse with `409
payment.attemptUnresolved` while any remains unresolved. The client rule then becomes UX, not safety: clear on card save /
pack change only after the last answer was the no-charge signal.

**H2 — AC4(1)'s "re-read after parking" does not close the race; it only narrows it.**
W = webhook tx, P = `persistCoachSubscription`. W reads no row; W inserts the park row (uncommitted); W re-reads and
still sees no link; P commits the link; P's after-commit drain reads parked rows and sees none (W uncommitted); W commits
the park. The event is orphaned until the purge drops it. Under READ COMMITTED (confirmed, no override) the story's
own protocol still loses.
*Needed:* a protocol with two commit points: W commits the park **and** the processed marker, then re-reads in a
*post-commit* step and applies in its own transaction; P commits the link, then drains. Either W's commit precedes
P's drain read (P sees the park) or P's drain read precedes W's commit — in which case P's link commit already precedes W's
re-read (W sees the link). Or take a shared `pg_advisory_xact_lock(hash(stripe_subscription_id))` in both. Add the
interleaving as an IT (it is in Task 4 but the protocol it tests is wrong).

**H3 — AC2 cannot see refunds and treats "not found" as proof of no charge.**
(a) AC2 says to use `findChargeByIdempotencyKey`/`retrieveCharge` for "refunded". Neither can: `ChargeLookup`
carries `(paymentIntentId, status, amount)` and a refunded PaymentIntent is still `succeeded`. Refund state needs
`getRefundableAmount` (captured − refunded; throws `payment.refundLookupFailed`/`REFUND_TARGET_INVALID`).
(b) AC2 maps "no PaymentIntent" → `NO_CHARGE` and the client mints a fresh key. But the lookup lists the payer's
PaymentIntents (`limit(100)`, `created ≥ createdAfter`, which the story never defines) and returns empty when the payer
has no `stripe_customers` row. If the original request is still in flight at Stripe, or the intent is not yet listable, "none found" is
followed by a second charge. The existing `ChargeOutcomeReconciler` uses `reservedAt − LOOKUP_SLACK` and a minimum age.
(c) A *declined* confirm leaves a `requires_payment_method` PaymentIntent, so "found but not `succeeded`" must also be
`NO_CHARGE`; `processing`/`requires_capture` (`pending()`) must stay `UNKNOWN`. AC2 has no such branches.
*Needed:* name `getRefundableAmount`; define `createdAfter = attempt.created_at − slack`; require a minimum age (≥ SDK
read timeout + retries) before "none found" counts, and treat a *fresh* `IN_PROGRESS` row as `409 payment.attemptInProgress`, not as
resolvable; prefer re-issuing `chargeAndCapture` with the **same Stripe key and params** as the primary resolution
(Stripe returns the original result or executes once) and use the list only as a fast path; an unresolved lookup
stays `UNKNOWN`.

### MEDIUM

**M1 — AC1b's signal mechanism and wire contract are unspecified, and the story's suggestion points at the wrong layer.**
The gateway has no knowledge of `REFUNDED`/`UNRECONCILED` (those are thrown from the service at `:171`/`:185`), and it
need not: `purchasePack` can classify itself — any plain `PaymentGatewayException` from `chargeAndCapture` that is not
`ChargeOutcomeUnknownException` is "no charge" (the capture path returns an id or throws before capture) — and rethrow a
pack-service subclass with its own code. Booking callers (`PaymentLifecycleService`, `CashOutService`) catch the base class
and are untouched. Keep the decline's code `payment.lifecycleFailure` in the *gateway*, because
`StripePaymentGatewayTest:865-872` pins it. Wire shape: 422s carry the code in `errorMsg.errorKey`; the 400/409 `ResponseStatusException`
answers (`payment.invalidIdempotencyKey`, `payment.attemptInProgress`, `payment.attemptUnresolved`) carry it in
`errorMsg.message` with `errorKey = generic.requestError`. The page reads `data.message`, which is always undefined — the story
must say which field the page reads for each.

**M2 — AC4 collides with AC5 and has no recovery.** The park insert runs inside `handleEventAtomically`'s
`@Transactional` (`StripeWebhookService.java:177`). If it fails, the exception rolls back `insertIfAbsent` and Stripe retries
indefinitely — the thing AC5 forbids; a `try/catch` in the same PostgreSQL transaction does not help (poisoned). Use a savepoint
or `REQUIRES_NEW` for the park, and define failure as "log and drop" (today's behaviour). After a successful direct apply,
delete or mark the park row, or the purge later warns "purged unapplied" for an applied event. The drain runs on the request
thread after commit, so a crash between the persist commit and the drain strands rows until the purge; add a periodic drain to the same
scheduled job. `buildSubDataMap` is **private** to `StripeWebhookService` (`:499`) and the drain runs in `SubscriptionService`, so store the
already-extracted fields (status, period end, cancel flag, `event_created`) rather than re-deserialising JSON. Retention
should be a `ConfigBounds` key per repo convention.

**M3 — `SUCCEEDED` is not atomic with `createPurchase`, and AC2 omits the existing-pack check.** `createPurchase` is a bare
`save` (`:414-430`); the process can die after it and before the `SUCCEEDED` write, leaving a pack and an `IN_PROGRESS` row.
AC2's "captured and not refunded → create the pack" would then insert a second pack for the same intent; the unique index
backstops it only via the `:154` catch finding the winner — accidental. AC2 must call `findByStripePaymentIntentId` first
(as `:148` does) and go through `replayedPurchase`; a failed `SUCCEEDED` write must never fail a response for a pack that
exists. List the transitions into `UNRECONCILED`/`REFUNDED` out of the `:154-186` catch (refund failed, lookup failed, winner found).

**M4 — AC8 has gaps for keyless requests, ordering and two accepted limits.** (a) AC1a writes no row for an absent
`Idempotency-Key`, so `previous_card` has nowhere to live — and those are the direct-API clients AC8 protects. Use the in-process
`previousCard` for keyless requests. (b) The conditional UPDATE needs the *attempted* card as its predicate, which is not stored; add
`attempted_card` to the row so a replay-driven revert has a value. (c) Order: revert **first**, then write `NO_CHARGE`; the other way
round, a crash leaves the declined card saved permanently because a replay never rewrites the card. (d) Same-card collision: the user
saves X via `/save-payment-method` while a pack attempt with X declines — the column equals the attempted card, so the revert undoes a
legitimate save. (e) Two concurrent purchases by one parent with different cards share the single `stripe_customers` row (and the gateway
re-reads it, so one can charge the other's card — pre-existing). The text "safe against a concurrent legitimate save" is false for (d)/(e);
state them as accepted limits. (f) The repository method needs `@Transactional` (see §3). (g) A first-time payer's retry after the revert has
`customerExisted == true` and `previousCard == null`, so the sync now fires where the first attempt would not — harmless, but the AC8 test should expect it.

**M5 — AC3 pages on events AC2 self-heals, and nothing finds a charge whose request died.** Every timeout or `processing` response
fires a money-integrity alert though the parent's replay resolves many with no operator action, and a log alert cannot be cleared. Separately,
if the pod is killed mid-request (a rolling deploy makes this likely) the Stripe call may have captured, no log is written, and the row sits
`IN_PROGRESS` with no reader — AC3 only fires on a live `ChargeOutcomeUnknownException`. *Needed:* alert on **aged unresolved attempts**
(`IN_PROGRESS`/`UNKNOWN`/`UNRECONCILED` older than N minutes, found by a ShedLock scan that logs a stable tag and exposes a gauge) rather than, or in
addition to, the per-event tag; log the tag *before* the `UNKNOWN` write so it still fires if that write fails. (Baseline is worse — today there is no trace at
all — so this is medium, not high.)

**M6 — The attempts table has no retention, bound or erasure story.** Any authenticated parent can insert unbounded rows by sending
distinct keys (the unique constraint is per parent), and `previous_card`/`attempted_card` are payment-method ids tied to `parent_id`. Specify a
purge of terminal rows (~30 days), a per-parent bound, and confirm `GdprErasureService` enumeration covers the new tables (not verified here —
the repo has a history of erasure gaps).

**M7 — Tier/coach gates run before any attempt lookup, so a replay cannot resolve a captured payment once the world has changed.**
`purchasePack` runs the key regex, player ownership, age verification, `packTierInactive` (`:126-130`) and `requireBookableCoachStatus`
(`:133-135`) on *every* call. If the tier is deactivated or the coach suspended after an `UNKNOWN`/`UNRECONCILED` attempt, the replay throws
422 `payment.packTierInactive`/a status error and the captured payment can never resolve; the key is kept forever. Look up an existing attempt row after the key-format and
ownership checks and **before** the tier/coach gates; run those gates only for new rows.

**M8 — The numbering section is incomplete.** Two unmerged branches hold V226 (builder-packs and `feature/pitchside-reg-neutral`), so builder-packs may
itself move to V227 and this story to V228+; "re-derive at dev time" is right but the stated cause is not. The builder-packs branch is **pushed** and also edits
`SessionPackPaymentService.java` (adds `replaceActiveOffer`, `withdrawActiveOffer`, `normalizeLabel` after ~`:333`), `SessionPackTier.java`,
`CreateSessionPackTierRequest.java`, `CoachSessionPackAdapter.java`; every `SessionPackPaymentService` line cite after ~`:333` will shift. Both branches write
`story-review.md` and `sprint-status.yaml`, so a conflict there is certain, not "trivial".

**M9 — AC11 under-specifies the eval promotion.** `evals/review/README.md:117-120`: promote to `cases/` **without the `pending-` prefix and with `id` equal to the filename**
(`validate.py` rejects a mismatch), and after the squash merge add `fix_merge` and drop `input_rev`. The PR number is only known after the PR exists, so this needs an extra commit.

**M10 — AC6's record can fail the request after billing, and its "no identifier available" premise is wrong.** A same-transaction insert that throws rolls
back the (otherwise empty) `persist*TierUpgrade` transaction after Stripe has billed, the endpoint errors, and a client retry is a *new* upgrade key — a second
proration, which is exactly AC7's concern. The same-transaction insert also cannot be wrapped in a `try/catch` (poisoned transaction). Because the table has **no FK**, a
`REQUIRES_NEW` best-effort insert inside a `try/catch` *would not* deadlock — the story's blanket ban rests on the FK case — so state that: log at `error` with the stable tag first (the alert source works
even if the insert fails), then insert best-effort. And record `latest_invoice_id` (`updated.getLatestInvoice()`), which is available; without it an operator searches Stripe by subscription. Also note,
so the dev does not chase them: `persist*TierUpgrade` can throw `payment.subscription.notFound` (`:342`/`:575`, e.g. after a GDPR scrub) and a DB error after the Stripe update leaves Stripe at the new tier and the DB at the old; AC6 does not cover these.

### LOW

- **L1** The 7 citation drifts in §1.
- **L2** AC4 says the drain "must set `lastStripeEventAt`"; `handleSubscriptionWebhook` already does (`newer(...)`, `:1255…`). Remove the instruction.
- **L3** AC12: drop "RS09's entry gains one sentence" (done); RS06's ledger text still says "V226"; the RS09 annotation repeats the SetupIntent rationale that the story's own facts section says is unenforced.
- **L4** AC4/Task 4: replace "scheduler conventions" with `docs/deployment/scheduler-lock-tuning.md`, `provisioning-checklist.md` §6, and read `SubscriptionSchedulerLockTest`/`ConfigStartupAssertionTest` for what a new scheduler must register.
- **L5** The first Change Log bullet still says it "names the four existing tests"; the later bullet and Tests section cut that to one. Annotate it as historical, as was done for Correction 3.
- **L6** Test plumbing: adding a repository to `SessionPackPaymentService` breaks `@InjectMocks` tests until mocked; `StubPaymentGateway` (`src/test/.../config/StubPaymentGateway.java`) always returns `Optional.empty()` from `findChargeByIdempotencyKey` and never declines, so ITs need it extended.
- **L7** Count of migrations is unstated (the story implies three tables: attempts, pending events, upgrade reconciliations); a `CHECK` on a new empty table is fine inline, a FK to an existing table needs `NOT VALID`.
- **L8** `payments.json:1314` already lists `STRIPE_CHARGE_OUTCOME_UNKNOWN`; only the alert regex lacks it. AC3's dashboard edit is adding the *new* tag.

## 5. What did not survive re-verification

| Raised by | Claim | Why it died or was downgraded |
|---|---|---|
| Layer 4 | "No detector for stuck rows" rated **high** | Downgraded to M5: today there is no trace at all, so the rewrite is an improvement; the gap is real but not a regression. |
| Layer 4 | 3DS / `requires_action` leaves an open PaymentIntent a fresh key won't cancel | Dropped — correct but trivia; `NO_CHARGE` is the right status. |
| Layer 4 | Attempt-row insert failing "must be handled" | Checked and fine: it propagates before `:140`/`:144`, so nothing is charged. |
| Layer 4 | Lock ordering for AC4 between webhook and persist | Checked and fine — no cycle; two webhooks for one subscription serialise on the row. |
| Layer 2 | `story-review.md` pass 1 is stale (title, SHA, "not pushed") | Moot — this file replaces it. |
| Layer 3 | Frontend has "zero references" to the codes | True, and irrelevant to feasibility; folded into M1. |

## 6. Not independently re-checked

Stripe's documented behaviours (replay of a cached keyed failure; `idempotency_error` on a parameter mismatch; list consistency after create) — not
exercised, so the implementer should pin them with a test; whether `GdprErasureService` would cover the new tables; what `SubscriptionSchedulerLockTest`/`ConfigStartupAssertionTest` actually require of a new scheduler;
linter behaviour for the new tables; `AlertDocumentationParityTest` (Layer 4 says it matches alert titles only; not run); the exact line shifts the builder-packs branch will cause.

## 7. Recommendation

| Area | Confidence |
|---|---|
| Citations | High — ~140 opened; 7 drifts, all in new text, all corrected above |
| Descriptions of existing code | High — full bodies read, boundaries traced, SDK jar inspected |
| Pass-1 fixes | **Mixed** — H6 and the test table landed; AC2, AC4 and AC1b's card-save rule each need rework |
| The ACs as a design | **Not ready** — H1-H3 are specification defects an implementer following the text literally would ship |
| Owner decisions | Unchanged, but H3(b)/M5 add a resolution mechanism and a scheduled scan to RS06, M10 changes RS07's record, and M4(d)/(e) qualify RS09's "safe against a concurrent save" |

Two things for the owner, not the author: whether RS06's server record should *also* carry the scheduled scan (M5), since that adds a scheduled job to a bundle that previously had one; and whether RS09's revert still earns its complexity now that M4 shows it protects only direct API clients and cannot be made safe against two of its own edge cases. **Status of these findings:** this report records them as they stood at `4d348462`. They were applied in the revision committed immediately after it (the story's Change Log, 2026-11-04 second entry, lists what changed); the two owner questions above were answered with the recommended option and are flagged "OWNER TO CONFIRM" in the story.
