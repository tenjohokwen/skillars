# Story review (pass 3) — skillars-deferred-169 (payments/Stripe bundle RS05-RS09)

**Audit date:** 2026-11-04
**Real HEAD:** `05b8bea8` — `docs(story): apply the second /mto-story-review to skillars-deferred-169`
(docs-only commits on `bed75f19`). **`origin/master` has since moved to `281b8433`** (three commits, below), so the
story is also stale against current master. Citations were checked against this HEAD for the story's own claims and
against `origin/master` for anything that has moved.
**Story:** `_bmad-output/implementation-artifacts/skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md`
**Status when reviewed:** `ready-for-dev`. This report supersedes pass 2.

**Scope of this pass.** The mechanisms added by pass 2's rewrite (resolve-before-new, the status model, AC2's
resolution rules and minimum age, the two-commit-point webhook protocol, the best-effort `REQUIRES_NEW` insert, the
conditional-UPDATE revert, the single scheduled job) and, new this time, **the interactions between those ACs**. The four layers
got only the story and HEAD. Counts: ~140 citations, ~25 attributions, ~30 behavioural claims, 13 corner-case areas.

## Headline

**Citations are sound and the repo facts are mostly right, but the design is not converging.** Pass 2 found 3 high-severity
defects; this pass finds **4**, and **all four are in mechanisms pass 2 introduced** (resolve-before-new, the minimum age, the
scan) or in how they combine. The story does not need more patches of the same kind: each patch has added mechanism, and each new
mechanism has opened new interactions. Section 7 recommends reducing the mechanism and offers a split for the owner to decide.

### Did pass 2's fixes land?

| Pass-2 finding | Fix | Verdict now |
|---|---|---|
| H1 key cleared on card save → double charge | server-side resolve-before-new guard + conditional client clear | **Partly** — the guard itself double-charges when it resolves a capture (H1) and is a check-then-insert race (H2) |
| H2 webhook race | two commit points | **Landed in principle**; implementation traps remain (M2, M3) |
| H3 AC2 cannot see refunds / "not found" unsafe | `getRefundableAmount`, `createdAfter`, minimum age, branches | **Partly** — no compare-and-set (H3), the minimum age is too short for the path, and "no customer row" is mis-classified (H4) |
| M1-M10 | applied | Mostly landed; M5's scan became the source of H4 |

## 1. Citation verification (Layer 1)

~140 opened; the seven drift corrections from pass 2 **all landed correctly**. New or remaining:

| Citation in story | Verdict | Corrected |
|---|---|---|
| `PaymentLifecycleService :195` as a `charge()` caller | MISATTRIBUTED | `:195` catches `deductSession`. Keep `:259`, `:266`, `:473`, `:480`. |
| `CashOutService :141, :217` as `charge()` callers | MISATTRIBUTED | they catch `refund()` and `getRefundableAmount()`. Drop them from the "charge() is shared" argument; the conclusion (a subclass leaves booking callers untouched) still holds. |
| `SessionPackPaymentService :414-430` (`createPurchase` bare `save`) | DRIFTED | `:415-434` on this HEAD; **`:457-` on current master** (see §2) |
| `PackSessionService.java:556` | DRIFTED | the call starts at `:555` → `:555-556` |
| `:575` (`payment.subscription.notFound`, player) | minor | the throw is `:576` |
| `limit(100)` attributed to the gateway | minor | it is `.setLimit(100L)` in `StripeClient.listPaymentIntentsForCustomer` `:77-81` |
| `evals/review/validate.py` | wrong path | **`evals/review/bin/validate.py`** (`:64` enforces id == filename) |
| README "second step" | imprecise | step 2 is `evals/review/README.md:121` |

Everything else matched with the quoted line, including all `purchasePack`, gateway, webhook, `SubscriptionService`, frontend,
evals, migration, alert and docs citations.

## 2. Repo facts and attributions (Layer 2) — master moved, the Numbering paragraph is stale

| Claim in the story | Verdict |
|---|---|
| "Two unmerged branches hold V226" | **REFUTED now.** `skillars-deferred-168` was **squash-merged as #436** (`47180ea7`; sprint-status #438 `0ee68f07`; #434 `281b8433`). **`V226__backfill_builder_packs_into_session_pack_tiers.sql` is on master.** The next free number on master is **V227**. |
| "Builder-packs branch: unmerged, pushed, status `review`" | **REFUTED now** — merged, `Status: done`. The worktree `…/skillars-deferred-168` is now on **`skillars-deferred-170-session-pack-tier-follow-ups`** (local, in progress). |
| "`SessionPackPaymentService` cites after ~`:335` will shift" | **CONFIRMED and now real**: master adds +42 lines (`replaceActiveOffer :348`, `withdrawActiveOffer :362`, `normalizeLabel :374`); `@Transactional` now at `:255,:295,:347,:361,:378` (the story says "only `:255,:295,:336`"); `getSavedPaymentMethod :363→:405`, `getOrCreateStripeCustomer :398→:440`, `createPurchase :414→:457`. `:98-192`, `:139`, `:140`, `:144`, `:203` did **not** shift. Also `BookingRequestPage zonedMidnightMs :552→:554`, `i18n pauseStartInPast :1992→:1995`. |
| "`V227+`, re-derive at dev time" | right instruction, **V227 is contested**: local-only `feature/pitchside-master-sync` already renamed its migration to `V227__registration_phone_in_use_email_template.sql`, and the `deferred-170` story says "169 also claims V227+, whichever merges second renumbers". |
| `deferred-170` | **NEW, not in the story.** It is sequenced *after* 169 and rewrites the same code: its AC3 reorders `purchasePack` (coach readiness before any write) and it edits `SessionPackPurchasePage.vue` (it independently found the `:189 data.message` bug). Sequencing is stated by 170, but 169's AC1a ordering must be written so 170's reorder composes with it. |
| Correction 3 (ancestry; draft F1) | CONFIRMED |
| PR #337 / `eea53ee9`; the label is a comment tag | CONFIRMED (nuance: `:414-416`/`:1385-1387` are about *scheduled-change* re-grants, a sibling path to the superseded guard — supporting, not identical) |
| AC11 eval conventions | CONFIRMED, plus: validator path and README step 2 `:121` |
| deferred-167 carried constraints | CONFIRMED |
| `ConfigBounds` | exists, but a key needs a `BoundedKey` constant, an entry in `ALL`, `HAS_CODE_DEFAULT` if a code default is used, and a **seed row** if none — the story names the key but not that |
| `LOOKUP_SLACK` "reuse" | `ChargeOutcomeReconciler.java:87` is `private static final` (10 min). Its minimum-age logic is a *different* mechanism: `booking.payment.charge_reconcile_after_minutes` read via `getBoundedLong(..., 10, 5, 1440)` (`:112`), not in `ConfigBounds`. |
| `GdprErasureService` "enumerates tables" | **REFUTED** — it is 1,975 lines of imperative calls, deliberately retains financial rows (`:805`) and calls only `stripeCustomerErasureOutboxSupport.scheduleErasure`. A new table is silently skipped. |
| Cross-document counts/ids | consistent (3/10/8, V227+, 14 ACs, two owner confirmations). Nits: the closing line cites "second entry" (it is the fourth); "7 drifts" vs 6 DRIFTED rows. |

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict |
|---|---|
| `processWebhook` is non-transactional, so a step after `self.handleEventAtomically(event)` is genuinely post-commit | CONFIRMED (`StripeWebhookService :116-122`; no class-level `@Transactional`; `open-in-view: false`) |
| The post-commit step needs to know an event was parked | **PARTLY** — `handleEventAtomically` is `void` and its handlers private; it needs a return value/signal (existing tests call it as a statement, so a non-void return is source-compatible) |
| A park in `REQUIRES_NEW` is possible from `handleEventAtomically` | CONFIRMED via the `@Lazy self` field (`:107-110`) on a `public` method — **but it commits before the marker**, so "park and marker commit together" (AC4 step 1) is only roughly true, and an FK from the park to `stripe_webhook_events` would fail (marker uncommitted) |
| If the post-commit step throws, the event is lost | **CONFIRMED** — `StripeWebhookResource :75-78` returns 500 on any exception, Stripe redelivers, `insertIfAbsent` returns 0 and `handleEventAtomically` returns as a duplicate (`:180-183`). The committed park row survives, so **only the scheduled drain recovers it** — the story relies on this implicitly. |
| The drain slots in after the `isEmpty()` check in `subscribeCoach`/`subscribePlayer` (`:185-193`, `:450-458`); only the non-empty branch should drain | CONFIRMED. The four `setStripeSubscriptionId` writers are exactly as listed. |
| Double-apply of a parked event is a no-op | CONFIRMED (`isStaleEvent` strict `isBefore`, `SubscriptionService :1358`; a second `deleted` finds no row) — but exactly-once is cleaner as `DELETE … RETURNING` in the apply transaction |
| SDK timeouts: connect 10 s, read 30 s, `maxNetworkRetries` 2 (`PaymentConfig :56-60`) | CONFIRMED → **~2 min worst case per Stripe call**, a purchase can make 2-4 sequential calls, so a *legitimate* live attempt can run **6-8 minutes** |
| `findChargeByIdempotencyKey` returns empty both for "no customer row" and "not found" | CONFIRMED (`:276-279`) — the caller cannot tell them apart from the return value |
| `getRefundableAmount` returns ZERO for a PaymentIntent with no latest charge (so ZERO ≠ REFUNDED in general) | CONFIRMED (`:546-548`). AC2's order of checks (pending → found-but-not-succeeded → only then `getRefundableAmount` on a `succeeded` intent) avoids the mislabel; **state it as an invariant with a test** |
| A partially refunded intent has refundable > 0 and is classed "captured" | CONFIRMED — a pack is created for partly refunded money (policy question, low) |
| AC6 `REQUIRES_NEW` insert on an FK-free table cannot deadlock against the `FOR UPDATE` | CONFIRMED by lock reasoning. **But** it needs a *second pooled connection* while the outer holds one (pool 25); the repo has a dedicated routed pool for exactly this shape — `DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY` (pool 10, 5 s timeout), selected by `RoutingDataSourceContext.set(...)` in a *non-transactional wrapper* before the proxy opens the connection (`BookingPaymentPersistenceService :132-136`). In-class self-invocation silently ignores `REQUIRES_NEW`. |
| Conditional UPDATE style | CONFIRMED (`@Modifying @Transactional @Query` at `StripeOAuthStateRepository :18-27`; `@Modifying(clearAutomatically…)` elsewhere). `clearAutomatically` is harmless with the method-level `@Transactional`. |
| A new `@Scheduled` job has no hard-coded count to bump | CONFIRMED for `SubscriptionSchedulerLockTest`, `ConfigStartupAssertion` (`locksChecked`, no total). **One hard count exists: `PessimisticLockRetryerCallSiteAuditTest.EXPECTED_CALL_SITE_COUNT = 67`**, bumped by any new `withBoundedRetry(` |
| A gauge set in a ShedLock job is accurate | **PARTLY** — only on the pod that ran it. The repo has a better pattern: `OperationalBacklogMetrics` (a DB-derived `MultiGauge`, refreshed per JVM, aggregated with `max`) and an existing alert on it (`grafana-alerts.yml :771`, `max(skillars_backlog_oldest_age_seconds{backlog="cash_outs",detail="UNKNOWN"}) or vector(0)`). One `UNION ALL` branch gives the gauge **and an alert that clears itself**. |
| Loki alert tags | **TRAP** — the regex is `\[(TAG1\|TAG2…)\]`, so a tag matches only when `]` directly follows it. Tags logged as `[TAG key=value]` (common in this codebase) do not match; the new tags must be `[TAG] key=value`. The alert window is 15 min with `for: 0s`, so a scan less frequent than 15 min fires and clears on each run. |
| `purchasePack` ordering: every listed security gate precedes `isActive()` and `requireBookableCoachStatus`; the only Stripe call before `:144` is `getOrCreateStripeCustomer` | CONFIRMED. The attempt lookup slots between `:127` (tier `findById`) and `:128` (`isActive`) because `replayedPurchase` needs the tier. |
| `charge()` is `confirm=true`, `off_session=true`, card-only | CONFIRMED → a card needing 3DS errors out; `requires_action` is not a usable state here, so "not succeeded, not pending → `NO_CHARGE`" is sound |

## 4. Corner cases, false assumptions, missed flows (survived Step 4b)

### HIGH

**H1 — Resolve-before-new double-charges the case it exists for (AC1a(4)).**
Trigger: a purchase times out client-side → row `UNKNOWN`. The parent reloads (the key is an in-memory `let`, `SessionPackPurchasePage.vue:~163`, so it
is lost) and buys again with K2. Step 4 resolves K1 via AC2: Stripe shows it **captured**, the pack is created, K1 → `SUCCEEDED`. Nothing is
unresolved any more, so the same request falls through to step 5 and **charges again**. The text says only "refuse while any remains unresolved"; it never says what happens when
resolution finds a capture. *Needed:* if step 4 resolves an earlier attempt to a captured pack for the **same player and tier**, return that pack (replay semantics) and charge
nothing (or answer 409 "your earlier purchase succeeded"); proceed only after `NO_CHARGE` or `REFUNDED`; a different tier/player proceeds.

**H2 — The guard is a check-then-insert race, and keyless requests bypass it (AC1/AC1a).**
The only constraint is `UNIQUE(parent_id, attempt_key)`. Two concurrent requests with *different* keys both read "no open attempt", both insert, both charge.
Keyless requests write no row and are invisible to the guard; a later replay of a keyed row can also resolve to `NO_CHARGE` and revert the card that a keyless purchase just set (AC8).
*Needed:* make the insert the serialisation point — a **partial unique index** on `(parent_id)` where `status IN ('IN_PROGRESS','UNKNOWN','UNRECONCILED')` (inline in `CREATE TABLE`
if possible, else with the `allow-blocking-index` marker) so the loser answers `attemptInProgress`; or `pg_advisory_xact_lock(parent_id)`. Say explicitly how keyless requests behave.

**H3 — No compare-and-set on status, and the minimum age is shorter than the path (AC1a, AC2).**
(a) The story specifies no `UPDATE … WHERE status IN (expected)`. If request B resolves a row to `NO_CHARGE` while the original request A is still mid-charge and then captures, A's `SUCCEEDED`
write overwrites it or A's result is dropped; resolve-before-new then ignores the (terminal) row and the parent can buy again while A's charge lands.
(b) "Minimum age ≥ SDK read timeout plus retries" is underspecified and too short: per Stripe call it is ~2 min worst case (§3), a purchase makes 2-4 sequential calls
(`createStripeCustomer` for a first-time payer, the charge, the lookup, the compensating refund), so a legitimate request can run **6-8 minutes**. The existing reconciler's answer is
**10 minutes** via a configurable key (`booking.payment.charge_reconcile_after_minutes`, default 10, bounds 5-1440). *Needed:* every status write is compare-and-set with a defined action when the CAS
fails after the caller has captured (re-read, never overwrite a terminal state, log); minimum age ≥ 10 min and configurable, or measured from a `charge_started_at` stamp written just before `chargeAndCapture`.

**H4 — A stuck row has no exit, the scan cannot resolve it, and "no customer row stays UNKNOWN" is wrong (AC2, AC3).**
The scan only logs. Nothing moves an `UNKNOWN`/`UNRECONCILED` row to a terminal state unless the parent comes back, and while it is open the parent is **blocked from every purchase** by
`attemptUnresolved` (AC1a(4)). The log-tag alert re-fires every scan for every unresolved row and cannot be cleared — the same reason the story rejected the per-event tag. AC2 says "when the
payer has no customer row, stay `UNKNOWN`", but `charge()` throws `payment.noStripeCustomer` *before any Stripe call* (`StripePaymentGateway :138-139`), so that case is a **proven absence**; the pod-killed-before-`getOrCreateStripeCustomer`
first-time payer would otherwise be `UNKNOWN` forever (the lookup returns empty for both "no customer row" and "not found", so the *caller* must re-query `stripeCustomerRepository`).
*Needed:* (1) the scheduled job runs the same AC2 resolution on rows past the minimum age and only reports rows it **cannot** resolve; (2) a terminal rule — a definitive lookup after Stripe's ~24 h key window plus margin → `NO_CHARGE`;
(3) "no customer row" → `NO_CHARGE`; (4) the alert is driven by an `OperationalBacklogMetrics` branch (§3), which clears when the backlog does, instead of a log tag; (5) the scan's N must exceed the minimum age plus a margin, or it false-alarms on a live `IN_PROGRESS` request.

### MEDIUM

**M1 — AC1a(2) and AC1b trap the user after an unknown outcome.** Last answer unknown (K1, tier T1); the user changes pack to T2; AC1b keeps the key; the next click sends K1 with T2 and step 2 answers
`payment.idempotencyKeyReused` 409 "before any Stripe call". The page clears only on success, the no-charge signal and `400 invalidIdempotencyKey`, so every click repeats the 409; the only exit is a reload.
The two ACs disagree. Either clear the key on `idempotencyKeyReused` (safe now that the server guard exists, once H1/H2 are fixed) or have the server resolve and answer `attemptUnresolved` when the stored row is open.

**M2 — AC4 is internally inconsistent and its failure semantics are unspecified.** Step 1 says the park and the marker commit together; the failure paragraph puts the park in `REQUIRES_NEW`, which commits *before* the marker. Pick one. Then: the park table needs `UNIQUE(event_id)` with `ON CONFLICT DO NOTHING` (an outer rollback after a committed park leads to redelivery and a re-park) and **no FK to `stripe_webhook_events`**; the post-commit re-read must still run when the park insert failed (the link may already exist);
the post-commit step must `try/catch` and log, because a throw → 500 → redelivery → `insertIfAbsent == 0` → silent drop (§3) — the event survives only as a park row, so **the drain interval is the recovery latency** and must be stated; claim a row with `DELETE … RETURNING` in the same transaction as the apply (exactly-once across the request-thread drain, the post-commit step and the scheduled drain); `handleEventAtomically` must return a signal.

**M3 — Nested `REQUIRES_NEW` should use the repo's routed pool (AC4 park, AC6 insert).** Both run while an outer transaction holds a connection. The repo added `PAYMENT_REQUIRES_NEW_DATASOURCE_KEY` for exactly this, selected by a non-transactional wrapper before the proxy opens the connection; neither AC mentions it. Without it, ~25 concurrent webhooks/supersessions can exhaust the primary pool. Require a separate bean (or `self`) — in-class self-invocation silently ignores `REQUIRES_NEW` (recorded in `OutboxService`, `PendingProviderAssetTracker`).

**M4 — The new alert tags must match the Loki regex shape.** The expression matches `\[(TAG…)\]`; a `[TAG key=value]` log does not match. Log `[PACK_PURCHASE_…] key=value`. State the scan cadence against the 15-minute / `for: 0s` rule, or (better) use the backlog gauge (H4).

**M5 — GDPR is a policy decision, not a "confirm coverage" check (Task 9).** `GdprErasureService` has no table registry; it deliberately retains financial rows (`:805`) and only schedules `scheduleErasure` for `stripe_customers`. The new tables hold `parent_id`, `previous_card`, `attempted_card` and are **silently skipped**. The dev must either null the two card columns at erasure or justify retention like `stripe_customers`; the story should say which. (A late AC8 revert after erasure is safe: the erased column is null, which does not equal `:attempted`.)

**M6 — Testability needs seams the story does not provide.** The shared spies are `paymentGateway` and `sessionPackPurchaseRepository` in `AbstractIntegrationTest`; `StubPaymentGateway` never declines and `findChargeByIdempotencyKey` returns empty. Concurrent-same-key is feasible with a latch in a `doAnswer` on `chargeAndCapture`. "Crash between refund and outcome write" needs a spy on the new attempts repository that throws once; the AC4 interleaving needs a hook on `PaymentCoachSubscriptionRepository.findByStripeSubscriptionId`. New spies must be **shared fields**, not per-class (a per-class spy changes the context key; ceiling 17). Min-age tests must backdate `created_at` via `commitWrite` (`ClockProvider` does not reach server threads).

**M7 — The story is stale against current master and must be re-based before any further review.** See §2: #436 merged (V226 on master), the line cites in `SessionPackPaymentService` after ~`:335` have shifted +42, V227 is contested, `deferred-170` is in progress and rewrites `purchasePack`'s ordering and `SessionPackPurchasePage.vue`, and this branch's base (`bed75f19`) predates all of it. Rebase, then re-locate every `SessionPackPaymentService` cite by symbol.

**M8 — Unclassified exceptions and a missing sync (L1/L2 of Layer 4, upgraded).** Only `PaymentGatewayException` subclasses transition the row; a `RuntimeException` from `chargeAndCapture` (pool timeout, NPE) leaves `IN_PROGRESS` and blocks the parent until the minimum age and skips the card revert. And when a replay resolves `UNKNOWN → SUCCEEDED`, the `scheduleSubscriptionCardSync` that the original success path would have run (`:189`) never fires — the live subscriptions never move to the new card.

**M9 — Config and registrations the tasks omit.** The retention window, the minimum age and N each need a `ConfigBounds` key (constant + `ALL` + `HAS_CODE_DEFAULT` or a seed row); `LOOKUP_SLACK` is `private`; any new `lockRetryer.withBoundedRetry(` bumps `PessimisticLockRetryerCallSiteAuditTest`'s 67; `docs/dev-docs` pages need the "Last verified against master SHA" footer stamp that `check-docs` enforces (AC13 omits it).

### LOW

- **L1** Citation drifts in §1.
- **L2** AC8: with a null `paymentMethodId`, `attempted_card` is null and `= :attempted` never matches — say "skip the revert". A replay-driven revert hours later can undo a legitimate later save of the same card (a generalisation of accepted limit (d)); `REFUNDED` returns the "no-charge signal" though money *did* move and was refunded — UI copy must not say "nothing was charged".
- **L3** Partially refunded intent classed as captured (policy).
- **L4** Stale or redundant text: the Change Log says AC4 gained "an ordering baseline" (none exists); the hard-constraint "a per-attempt key means a client retry is a new charge" is stale for keyed requests; AC12 says to correct RS05/06/08 in place *and* delete RS05-RS09 in the same PR (the correction is wasted); RS07's ledger text still reads as if `stripe_refund_failures` is reused; `migration-conventions.md` rule 1 (a new table ships a release before code uses it) is not addressed — the lint does not enforce it and 167/168 did the same, so a one-line acknowledgement suffices; the sprint-status entry's "TWO unmerged branches hold V226" is stale.

## 5. What did not survive re-verification

| Raised by | Claim | Why it died or was downgraded |
|---|---|---|
| Layer 4 | H3 "minimum age anchored to the wrong event" as a standalone | Merged with Layer 3's SDK-timing finding into H3: both are true; the fix is a configurable ≥10 min age *plus* CAS, not a new stamp alone. |
| Layer 3 | "Adding a scheduled job bumps a hard-coded scheduler count" | No such count exists; the only hard count is `PessimisticLockRetryerCallSiteAuditTest` (and only if `withBoundedRetry` is used). |
| Layer 4 | "Previous_card stale after a concurrent save between `:139` and the insert" | Checked and fine: `:140` overwrites any concurrent save before the charge, so the revert restoring the older card is consistent. |
| Layer 4 | A leftover `requires_action` PaymentIntent could be completed later | Not reachable: `confirm=true`, `off_session=true`, card-only; 3DS errors out and no client secret is ever exposed. |

## 6. Checked and found fine

Lock ordering between the webhook and `persistCoachSubscription` (no cycle); double-apply of a parked event; purge-vs-apply inside the retention window; the AC6 FK-free insert vs the `FOR UPDATE` (no lock cycle); `purchasePack`'s gate order and the lookup slot; purging terminal rows after ~30 days vs Stripe's 24 h key window; resolve-before-new reading only non-terminal rows; the conditional UPDATE style and `clearAutomatically`; AC10 (DST, east of UTC, single sender); the cross-document counts.

## 7. Recommendation

| Area | Confidence |
|---|---|
| Citations | High — ~140 opened; pass-2 corrections all landed; 6 small drifts |
| Descriptions of existing code | High — bodies read, boundaries traced, SDK settings and pool config read |
| Currency against master | **Low** — master moved (#436, V226, +42 lines); the Numbering section is wrong; `deferred-170` is not mentioned |
| The ACs as a design | **Not ready** — H1-H4 are specification defects an implementer following the text literally would ship |
| Convergence | **Poor** — passes found 6, 3, then 4 high-severity defects; pass 3's four are all in pass 2's additions |

**The trend matters more than any single finding.** Each pass has added a mechanism to close the last pass's finding (a status model, a resolve-before-new guard, a minimum age, a scan, a two-commit protocol, a routed-pool insert), and each
mechanism has produced its own interactions. Patching H1-H4 as written would add a partial unique index, a CAS column, a terminal-timeout rule and a gauge branch — more mechanism again. Two directions would reduce it instead; **the owner should choose, not the author**:

1. **Split the story.** Layer 4's natural cut: **(c) RS08** (one line + one test, no dependencies — ship it now, or first); **(a) RS06 + RS09** (attempt record, resolution, client rules, card revert, the attempts job) and **(b) RS05 + RS07** (parked events, the drain, upgrade reconciliation) as two further stories. The scheduled job is shared, so whichever ships first owns it. Today's single story carries 3 tables, 3 migrations, one job doing four things, two pages, i18n, alert regex, runbook, dashboard, GDPR, eval promotion and ledger edits; `deferred-170` is already queued behind it on the same lines of `purchasePack`.
2. **Simplify RS06.** Replace resolve-before-new + the resolution state machine with a DB constraint: a partial unique index on open attempts per parent (H2) already makes concurrency impossible by construction, and a *replay-only* resolution (no cross-attempt resolution) removes H1, the `keyReused` trap (M1) and most of H3. The cost is that a parent whose key was lost cannot self-heal and must wait for the job (H4) — which then needs only to resolve rows past the minimum age and drive a self-clearing backlog gauge.

Whichever is chosen, **rebase onto current master first** (M7); doing so changes line numbers, the migration number and the surrounding code that `deferred-170` is about to rewrite. Nothing in this report has been applied.
