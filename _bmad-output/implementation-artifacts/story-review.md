# Story review: skillars-deferred-174 (payment follow-ups)

- Audit date: 2026-11-06
- Real HEAD: `38f7d215 docs(story): create skillars-deferred-174 (payment follow-ups)` (source identical to master `c6d88a63`; `git diff c6d88a63 HEAD -- src` is empty).
- Every citation was checked against this HEAD, not against any SHA the story states.
- Method: four read-only agents (citations, ledger/precedents, Part A mechanics, Part B mechanics and corner cases), then my own second look at the findings that change the design (step 4b).

## 1. Citation verification

About 110 citations and named files were checked. **No line citation drifted.**

| Citation | Verdict | Note |
|---|---|---|
| `grafana-alerts.yml:793-815` | MATCH (off by one) | uid at 792, title 793, threshold 814, block runs to 816 |
| `Svc:273-275` (absence age config) | DRIFTED | the config is `platform/config/service/ConfigBounds.java:273-275`; `Svc:273-275` is a REFUNDED comment. The story writes `ConfigBounds.PACK_ATTEMPT_ABSENCE_AGE_MINUTES, :273-275` right after `Svc`, so it reads ambiguously |
| `OperationalBacklogMetrics`, `AdminTierGuard`, `ConfigBounds` | MATCH, wrong base | live under `infrastructure/` and `platform/config/`, not `platform/payment/` as the story's "relative to" rule implies |
| `Svc.recordCardFingerprint` "retrieves the pm" | MATCH (wording) | goes through `paymentGateway.cardFingerprint` (`StripePaymentGateway:255`) |
| all other `file:line` ranges, test names, docs, V232 as latest migration, 68 lock-audit sites | MATCH | |
| Outbox "`platform.outbox`" | WRONG name | the table is `main.outbox_messages`; the module is `platform.outbox`. Cosmetic |

## 2. Ledger and precedent attribution

| Claim | Verdict |
|---|---|
| Nine ledger ids exist, severity/category match the mapping table | CONFIRMED |
| "RS lines 159-175" | CONFIRMED but loose: the range also holds RS03d, RS05b, RS06c, RS06d (not in scope) |
| RS05c "ledger fix idea (`Subscription.getCreated()`) is wrong" | PARTIAL: the ledger says "anchor at persist time from the create response", never `getCreated()`. The story's own corrected anchor matches the ledger's idea. Reword to avoid saying the ledger was wrong |
| RS09d ledger wording is "off" | CONFIRMED as fair |
| "No eval case is tied to these items" | PARTIAL: `evals/review/cases/337-subscription-entitlement-integrity.yaml` and `_drafts/340-card-sync-not-repointed.yaml` touch the same code (169's RS05/RS09). Not tied to these ids, but say so |
| Skillars-deferred-167 "showed what a ten-item security story costs" | **WRONG**: 167 was a five-item bundle with 12 ACs, closed after seven `/pr-review` rounds |
| 169 (V228, parking table, AC8 revert, ShedLock job, 30-min absence), 172 (`EMAIL_CHANGED` :241, `EMAIL_CHANGE_UNDONE` :293) | CONFIRMED |
| release-held precedent (log-only audit, `requireFullAdmin`), `StripeCustomerErasureOutboxSupport` MANDATORY, `OutboxRowProcessor:55-58`, rule 5, count 68 | CONFIRMED |
| Player-path comment at SubSvc:504-512 repeats the "already attached" claim | PARTIAL: :512 only says "see subscribeCoach"; the quote is at :183-184 |
| Other open payment items not in scope | RS05b (invoice events not parked, "left on purpose"), RS06c (optional Idempotency-Key), RS06d (partial refund counted as captured). The story does not mention why they are out |

## 3. Mechanistic claims

Confirmed as written: the RS09b flow (no ownership check anywhere, fingerprint planted by `recordCardFingerprint`, SetupIntent id never reaches the server, `attachPaymentMethod` has no main callers, `PaymentMethod.getCustomer()` exists in stripe-java 28.4.0); the gateway full-row save (detached `save()` is a merge that writes the stale card; holds even if the entity were managed because there is no `@DynamicUpdate`); revert-before-CAS order; value-matching revert query; the three stall scenarios of RS09d; the stuck-attempt branches and statuses; the release-held pattern; the drain/purge mechanics for RS-poison; `recordUngrantedUpgrade` being safe to call after the persist transaction has rolled back; the EmailChangeService transaction scope.

Corrections (verified by me, step 4b):

| Claim | Evidence | Verdict |
|---|---|---|
| D2(c): CAS `SET pm=:attempted WHERE pm IS NOT DISTINCT FROM :previous` | `getOrCreateStripeCustomer` (Svc:814-820) already wrote `attempted` before the charge, so on a normal success the column holds `attempted`, not `previous`; the CAS updates 0 rows. `scheduleSubscriptionCardSync` (Svc:577-588) also owns the outbox enqueue via `savePaymentMethodAndScheduleSync` in one transaction | **DEFECT**: as specified the sync is skipped on every ordinary purchase. The condition must also accept `pm = :attempted`, and the enqueue must stay with it |
| AC1 "400 `payment.paymentMethodNotOwned` ... existing `PaymentGatewayException` handling" | `PaymentApiAdvice:18-28` maps every `PaymentGatewayException` to `unprocessableEntity()` (422) | **WRONG status**. A 400 needs `ResponseStatusException(BAD_REQUEST, key)` (the convention `Svc` uses); a Stripe outage during the check will answer 422. Pick and state both |
| D1 impact: foreign pm "becomes the default on live subscriptions" | Stripe rejects a pm not attached to the customer; `syncPaymentMethod` catches it. The column is poisoned and the fingerprint planted, but the money impact is smaller | PARTIAL: lead with the fingerprint planting and the poisoned column |
| D3: close to NO_CHARGE "if no PaymentIntent or nothing captured" | resolver treats a pending (processing) intent as UNKNOWN, and an absence verdict is age-gated (Svc:~369, ~397). A raw CAS ignores both, and skips `revertDeclinedCard` | **DEFECT**: close must refuse or hold on `pending()`, keep the absence-age gate, and go through `noCharge()` so the card revert runs |
| D3: "refuse captured-no-pack with 409" | the job already retries `createPurchase` every cycle | PARTIAL: try `createPurchase` first and refuse only if it still fails |
| D3: call `resolveAttempt(id)` from the resource | returns package-private `Resolution`; an unknown id returns UNKNOWN rather than throwing | needs a public service wrapper and an explicit `findById` for the 404 |
| D4: skip such rows forever | record-failed (DB blip) and unreadable-charge (Stripe read) are recoverable by a later cycle | **DEFECT**: tier-deleted is permanent, the other two are not. Use a bounded retry before `needs_operator_since`, skip immediately only for tier-deleted |
| D4/AC6 "no repeated tags" | `answerKnownAttempt` (client replay) still calls `resolveAttempt` on a skipped row | PARTIAL: true for the job only |
| D4: tags in the regex | only `PACK_PURCHASE_RECORD_FAILED` (and `..._UNRECONCILED`) is in the MoneyIntegrity regex; `CHARGE_UNREADABLE`, `UNRESOLVABLE`, `LOOKUP_FAILED` are not | CONFIRMED for the story's focus; it also fires once on the request path (~:245) |
| D5: anchor = persist time, skip parked events with `created <= anchor` | `handleSubscriptionUpdated` (:1456) applies a parked `incomplete` with no regression guard, so the bug is real. But creation uses `ERROR_IF_INCOMPLETE` (StripeClient:281) and the persisted state is always active/trialing | PARTIAL: bug confirmed, but see §5 for two holes in the fix |

## 4. Ledger-level observations

Nothing else needed beyond §2.

## 5. Corner cases, false assumptions and missed flows (survived step 4b)

Ranked by consequence.

1. **D2(c) CAS never matches** (§3). Rewrite the condition and keep the enqueue in the same transaction. Evidence: Svc:577-588, Svc:814-820.
2. **D5 / AC7 would skip cancellations.** A parked `deleted` is final by design, and a parked `updated` carrying `canceled` or `incomplete_expired` is routed to the deletion path. A `<= anchor` skip applied to those loses a cancellation made in the window between Stripe's response and the persist. The skip must apply only to non-terminal `updated` events. Same-second `past_due` and `cancel_at_period_end=true` events are also lost, because `persist*` hard-sets `cancelAtPeriodEnd=false`.
3. **D5 leaves the live path open.** A live `incomplete` created in the anchor's second passes the strict `isBefore` and regresses `active`, while the same event parked would be skipped. A status-regression guard ("ignore `incomplete` when the row is not already INCOMPLETE") is simpler, needs no clock, and works for live and parked events. Recommend it as D5 option 3. Also: `SubscriptionService` uses `Instant.now()` throughout, not `ClockProvider`.
4. **AC1 status code** (422 vs 400, §3). Also an unstubbed `PaymentGateway` mock returns `false` from the new `paymentMethodBelongsTo`, so existing `save-payment-method` WebMvc cases turn into 400s unless stubbed.
5. **D2(b) needs an interface change.** `chargeAndCapture(UUID, Long, UUID, BigDecimal[, String])` takes no payment method; the gateway reads it from the row (:145). Use a new overload, not a changed signature: about 99 references in six test files, and `SessionPackPaymentServiceTest` stubs the 5-arg form with `anyString()` at eight places. Charging `attemptedCard` makes D1 mandatory, not optional.
6. **D3 and D4 interplay.** If D4 skips a row, D3 must clear `needs_operator_since` on close.
7. **AC12 migration**: rule 7 requires `SET LOCAL lock_timeout` before the `ALTER TABLE` (the lint enforces it) and rule 9 a header naming the lock. Neither is in AC12.
8. **D7 retry safety is unresolved.** `recordUngrantedUpgrade`'s own javadoc says a client retry "is a NEW upgrade key - a second proration". After a successful retry the reconciliation row stays open (resolved only by hand), repeated failures create duplicate rows (no unique key), and the runbook line "refund or grant by hand" should first say "check whether a retry already granted it". Add an AC.
9. **D6**: with `purgeOlderThan` no longer deleting linked rows, a poison row logs `[STRIPE_WEBHOOK_PARKED_LINKED_NOT_PURGED]` at error every 5 minutes forever; nothing alerts on the tag. Rate-limit it or fold it into the alert. No index on `stripe_subscription_id` in either subscription table (small tables, fine, but the class javadoc says every branch reads an indexed status). Query needs `HAVING COUNT(*) > 0`, as `awaited_wrap_ups` does, or an empty backlog reads age 0.
10. **D8**: `undo` does not refuse a locked account (only `confirm` does), so the handler's locked/erased check is mandatory. The listener should enqueue only for users with a `stripe_customers` row. `AccountManagementFacade:255` updates an email through a DTO; check whether it is a real change path. `EmailChangeServiceIT` drains the outbox synchronously after commit, so "row enqueued" cannot be asserted from the table: assert on the handler or a spy. T9 has no rollback test for AC10's "none when the change rolls back".
11. **Part A/B ownership if D0 = 1**: AC12 names "the new alert" (Part B) yet sits with Part A; AC11 and AC13 both edit `docs/dev-docs/payment/index.html` and the search index; no task covers the provisioning-checklist edit (T7 lists runbook only); AC8 omits the checklist. AC13 and AC12 need splitting along the D0 line.
12. **Context cache gate** (`IntegrationTestConventionTest`, 14 pinned keys): the story's note is right, but the one real risk is the new IT "beside `SubscriptionLifecycleIT`" (T6): it must reuse that class's `@MockitoBean StripeClient` set exactly, or the pinned count rises to 15. `OperationalBacklogMetricsIT` and `EmailChangeServiceIT` are safe if they add no mocks.
13. **Pre-existing, out of scope but worth a ledger line**: once an attempt is NO_CHARGE the open-per-parent index is free, so a second purchase can start while a stalled first thread is still alive (double pack). Other stale full-row writers exist at `Svc:817` and `StripeCustomerErasureOutboxSupport:45`; the gateway's stale write can also resurrect a GDPR-erased pm.
14. **Story text**: the 167 comparison is wrong (§2); AC7 has no test for "live event older than the anchor"; the purge test in AC8 names no class.

## 6. What did not survive re-verification

- "A foreign pm becomes the default on live subscriptions": downgraded to PARTIAL (Stripe rejects the attach-less pm). The planted fingerprint stands.
- Part B agent's remark that RS05c "persisted state is always active, so the story's scenario is only partly right": the underlying bug survives (a parked `incomplete` event does overwrite `active`, `handleSubscriptionUpdated` has no guard), so I kept it as a PARTIAL on the narrative, not a refutation. Same-second misordering is an extra hazard, not a replacement.
- Part A agent's "double-pack after NO_CHARGE" and "other stale writers": real but outside this story's scope, kept as note 13 only.
- Part A agent's "payment must not import `admin.repo` is only a convention, not a test": I did not independently re-search for an ArchUnit rule; treated as unverified.
- Part B agent's "AccountManagementFacade:255 is a possible email path": unverified, left as a check item.

## 7. Recommendation

| Area | Confidence | Basis |
|---|---|---|
| Citations and file existence | High | checked line by line at HEAD, no drift |
| Ledger mapping | High, one wording fix | 167 comparison is wrong; RS05c attribution is imprecise |
| Part A: ownership check (D1), gateway save fix (D2a), admin close (D3), needs-operator (D4) | Medium | mechanics confirmed, but D2(c), D3 and D4 each have a concrete defect (§3, §5) |
| Part B: D5 | Low as written | holes 2 and 3 above; the status-regression guard looks better |
| Part B: D6, D7, D8 | Medium | mechanics confirmed; gaps in alerting, retry semantics and test design |
| Not independently re-checked by me | | the `PaymentApiAdvice` and CAS findings I re-read; the rest rest on agent quotes. The Stripe-side behaviour of a repeated upgrade (zero proration) is unconfirmed from code. The ArchUnit question and the AccountManagementFacade path are open |

Suggested story edits before dev: fix D2(c) and D3, split D4 into bounded-retry plus immediate skip, add D5 option 3, specify AC1's status code, add lock_timeout to AC12, add the D7 retry AC, and re-split AC11-AC13 along D0.
