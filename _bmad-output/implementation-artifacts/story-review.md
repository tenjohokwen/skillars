# Story review — skillars-deferred-169 (pass 4)

- **Story:** `skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes` (723 lines, `ready-for-dev`)
- **Audit date:** 2026-11-04
- **Real HEAD:** `30e2c712 docs(story): simplify RS06 in skillars-deferred-169 after the third review` (master = `281b8433`). Every citation was checked against this HEAD, not any SHA the story quotes.
- **Method:** four read-only layers, then my own adversarial re-check (Step 4b) of every finding against source.

## 1. Citation verification (Layer 1)
About 200 `file:line` citations; none could not be located. Six drifted, all minor:

| Story location | Claim | Reality | Fix |
|---|---|---|---|
| AC8 | `getSavedPaymentMethod:367` | null guard is `:409` (Numbering already says so; AC8 not updated) | `:409` |
| Dev Notes RS09 | "`:347`,`:361`,`:378` added by #436" | #436 added `:347`,`:361`; `:378` is old `:336` shifted +42 | reword |
| Numbering | "unchanged at or before `:203`" | insertion hunk is `@@ -335,0 +336,42`; boundary is `:335` | reword |
| Numbering | `createPurchase :414→:457` | old position `:415` | `:415` |
| Dev Notes / Task 7 | `zonedMidnightMs` docblock `:542-551` | `:544-553` | fix |
| AC6 | player `payment.subscription.notFound` `:575` | `:576` (`:575` is the lock query) | `:576` |

Numbering claims verified: V226 on master, V227 free on master and contested by three branches, #436 added exactly 42 lines in one hunk.

## 2. Ledger & precedent attribution (Layer 2)
Almost all CONFIRMED (ledger RS05-RS09 at `deferred-work.md :338-342`; #337 `eea53ee9`; #362 `4db90562`; #436 `47180ea7`; deferred-170's text; eval cases 337/350/359/368 `fix_pr`+`fix_merge`; README/validate.py cites; routed-pool, REQUIRES_NEW and `@Modifying` precedents; Grafana lines `:638-648`, `:771`; `payments.json:1314`). Non-confirmed:

| Claim | Verdict |
|---|---|
| `feature/pitchside-master-sync` is "local-only" | WRONG: `origin/feature/pitchside-master-sync` exists with the same V227 file. The V227 contest stands. |
| AC12: "delete RS05-RS09 outright" | INCOMPLETE: the ledger entries themselves still say "corrected in place by the story's AC12/Task 8". Reword the ledger text when AC12 runs, or now. |
| `charge_reconcile_after_minutes` is a "ConfigBounds key" | IMPRECISE: a string constant passed to `configService.getBoundedLong(...)` (`ChargeOutcomeReconciler :112`) with local bounds; not a `BoundedKey`. Values (10, 5-1440) match. |

Not checked: "#436 added 42 lines" was verified by Layer 1; the 167 "table and code together" detail was not independently verified.

## 3. Mechanistic claims (Layer 3)
HOLD: `purchasePack` non-transactional and its gate order; `chargeAndCapture :144` outside the try; the catch `:154-186`; sync early-return `:204` (correction 3 true); gateway pre-flight throws before any Stripe call; decline mapping; `isOutcomeUnknown` incl. `IdempotencyException`; `findChargeByIdempotencyKey` empty for both no-customer and not-found; 422 vs `errorMsg.message` wire contract; `handleEventAtomically` transaction scope; orphan paths; webhook 500 → duplicate on redelivery; routed-pool mechanics from a separate bean; `OperationalBacklogMetrics` shape (a new branch fits); `ConfigBounds` shapes; GDPR `:805`; AC6's FK-free `REQUIRES_NEW` insert cannot deadlock or poison the outer transaction.

PARTIAL / WRONG (all re-read by me, all confirmed — see §4):
- `getOrCreateStripeCustomer` writes only when the id is non-blank **only for an existing row**; the no-row branch stores whatever it is given (null or blank).
- `handleSubscriptionWebhook` does **not** run `isStaleEvent` in the `deleted` branches (the story says "every branch").
- The 409 wire contract silently depends on `generic.requestError` staying out of the message bundles.

## 4. Findings that survived re-verification

### HIGH
**H1. A premature `NO_CHARGE` frees the index while the original request can still capture → second charge and second pack.** AC1 (CAS exception, line 194), AC2 (line 258), AC3.
Trigger: attempt A is `IN_PROGRESS` and its thread is slow or hung past the 10-minute minimum age (the story itself sizes a legitimate request at 6-8 minutes, `PaymentConfig:56-60`, 2-4 sequential Stripe calls at ~2 min worst case each, so the margin is thin). The job or a same-key replay finds nothing at Stripe and, per AC2 line 258 sentence 1, writes `NO_CHARGE` — and AC8 runs the card **revert first**. The partial index is now free, the parent starts attempt B and is charged. A's thread then captures and uses the proof-of-capture exception (`NO_CHARGE → SUCCEEDED`) to create a second pack. Nothing refunds either charge. The CAS-to-`UNRECONCILED` variant can additionally **throw** on the partial unique index when B is open, leaving the row at `NO_CHARGE` where the gauge never sees it (the story only handles an UPDATE that affects no row).
The root cause is that absence of a PaymentIntent is inferred while the originating request may be alive. Fix directions: infer `NO_CHARGE` from absence only past a much larger age (or the Stripe-key window), or keep an `IN_PROGRESS` row `IN_PROGRESS` unless a heartbeat/`updated_at` shows the thread is dead; and specify that a CAS that violates the index is caught and logged at `error`.

### MEDIUM
**M1. AC2 contradicts itself on "not found" (line 258).** Sentence 1: it "counts as `NO_CHARGE` only past the minimum age". Sentence 2: a row "stays `UNKNOWN`" until ~24 h plus a margin. The accepted cost (line 135, "minimum age plus one job cycle") assumes sentence 1; sentence 2 makes the terminal rule dead code under it, or blocks a lost-key parent for ~24 h under the other reading. A dev can implement either. Resolving H1 forces this choice.

**M2. A parent can be blocked from every pack purchase indefinitely with no exit.** The index is on `parent_id` alone across `IN_PROGRESS/UNKNOWN/UNRECONCILED`. No exit when: (a) the job's AC2 "create the pack" step fails deterministically (specified only for the request-path catch `:154-186`; a copied compensating refund would refund a funded pack); (b) the Stripe lookup fails forever (deleted customer, `REFUND_TARGET_INVALID`) — the terminal rule requires a *successful* lookup; (c) a row genuinely needs a human — AC12 defers the reconciler and there is no operator close/abandon transition, so the remedy is hand-edited SQL. The block applies to every tier of every coach. Specify the job's create-failure path and an operator resolution (status transition or runbook step).

**M3. A zero `getRefundableAmount` for a captured payment becomes `REFUNDED` with no pack.** `StripePaymentGateway.java :541-552` returns `ZERO` when `getLatestChargeObject()` is null. AC2 maps zero to `REFUNDED`, so a `succeeded` intent whose latest charge is not expanded yields captured money, no pack and no refund. Treat a null charge on a `succeeded` intent as a failed lookup (stay `UNKNOWN`), and add a test.

**M4. Parked `deleted` events bypass the staleness check.** `handleSubscriptionDeleted` (`SubscriptionService :1272-1285`) never calls `isStaleEvent`, so a parked *older* `deleted` is applied over a newer applied `updated`; AC4's drain text says `handleSubscriptionWebhook` "already runs `isStaleEvent` … in every branch", which is false for `deleted`. State the intended behaviour (a delete is final regardless of order, or compare `event_created`) and test it.

**M5. First-time payer with a null/blank payment method.** `getOrCreateStripeCustomer` (`:440-453`) stores the payment method unconditionally in the no-row branch, so "the card write is a no-op for the shipped UI" is true only for an existing customer. The `previous_card`/`attempted_card`/`customer_existed` semantics in AC1/AC8 should say so; a blank `""` is stored and passes `charge()`'s `== null` check.

### LOW
- **L1.** Keyless retries are refused 10-15 min after any timeout, and an unclassified pre-Stripe `RuntimeException` (pool timeout in `charge`'s `findById`, `:138-139`) is marked `UNKNOWN` although Stripe was never reached. Safe, but the cost is understated in line 135.
- **L2.** A linked parked event that throws on every apply is retried every 5 minutes and, with "oldest first, stop", blocks later events for that subscription; duty (d) purges only never-linked rows and no alert covers it.
- **L3.** `cancelDuplicateSubscription` (`:190,:453,:1522`) makes every lost subscribe race park an `updated`/`deleted` that is later purged unapplied with a `warn` and metric, which fires on an expected path. Tag or exclude it so the metric stays meaningful.
- **L4.** A concurrent same-key insert and the open-per-parent violation are both `23505`; AC1a step 4 should say to re-read by `(parent_id, attempt_key)` first to tell them apart.
- **L5.** The 409 wire contract relies on `generic.requestError` staying absent from the message bundles; pin it with a test.
- **L6.** Story 170 cites 169's lines `:562-563` and `:200-207`, now about `:578-580` and `:217-231`. No behavioural conflict (170's readiness check sits after 169's replay lookup and before the insert).

## 5. What did not survive re-verification
- Layer 4's "AC4 interleaving fails" and "failed apply loses the event" probes: the two-case argument at lines 298-299 holds under READ COMMITTED, and `DELETE … RETURNING` plus the apply share one transaction, so an apply failure restores the row. Dropped.
- Layer 4's AC10 DST/west-of-UTC probes: local midnight tomorrow is always accepted and `formatDate` renders in local time. Dropped.
- Layer 3's note that the refund-failed branch writes no tag: the `:157` `PACK_PURCHASE_RECORD_FAILED` tag covers it. Dropped.
- Layer 1's "AC8 `:367` drift" is a stale AC8 cite, not a wrong Numbering claim — kept only as a minor drift.
- Not verified: the "closed at seven rounds" claim for deferred-167 (Layer 4 could not glob the file); Stripe's idempotency behaviours (stated in the story as not exercised); primary pool size 25.

## 6. Recommendation
- Trend across passes: high-severity findings 6 → 3 → 4 → **1**. The simplification removed the structural defects; H1 is a residue of the "infer NO_CHARGE from absence" rule, not a new mechanism.
- **H1 should be fixed before `/bmad-dev-story`** (one rule change in AC2, with M1's contradiction resolved by the same edit). M2-M5 are small spec additions. The citation and precedent fixes are mechanical.
- Confidence: citations and precedents — high (each re-read). Mechanistic claims — high for those I re-read myself (L2/M3, M4, M5, M1 wording); the rest rest on Layer 3's quoted evidence. H1 is a reasoning chain over verified facts; its trigger needs a request exceeding the minimum age, which the story's own sizing makes plausible but rare.
- A split is no longer indicated by the trend; it remains available (RS08 | RS06+RS09 | RS05+RS07).
- The findings above were applied in the next revision of the story (see its Change Log).
