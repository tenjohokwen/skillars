# Senior-Dev Story Review — `skillars-deferred-133-gdpr-alerting-config-bounds-stripe-reconciliation`

**Reviewed:** 2026-09-24
**Reviewed against:** `HEAD = 71caec86625662e027a4ac832658074c11ba1a13` (the exact commit the story claims to
have verified against)
**Method:** every file, line citation, constraint, precedent and test file named in the story was opened
and read. Values in the AC2 table were checked one by one against the live call sites. DB constraints and
indexes were read from the migration SQL, not inferred from entity annotations.

**Verdict: the story is factually careful about *line numbers* and *values* — the AC2 table is 18/18
correct — but it is wrong about several *semantics*, and AC3's central premise does not survive contact
with the code.** Five findings are blocking. The largest is that `StripeWebhookService`'s orphan branch is
**not** an orphan detector: it is also the normal post-cancellation state, so AC3 as specified would raise
permanently-unresolvable false alerts on routine coach cancellations.

| Severity | Count | ACs affected |
|---|---|---|
| High (blocking) | 5 | AC1 ×3, AC3 ×2 |
| Medium | 11 | AC1 ×1, AC2 ×2, AC3 ×6, AC4 ×2 |
| Low / factual | 7 | AC1 ×2, AC2 ×1, AC3 ×3, misc ×1 |

---

## HIGH — must be resolved before implementation

### H1 (AC1) — The fix cannot alert on the `GdprRequest not found` path the story explicitly names

The story names, as one of the two paths Fix 1 exists to cover:

> a `RuntimeException("GdprRequest not found")`/`"User not found"` at `:195`/`:200`

The proposed code places the alert inside `markFailed`'s existing `ifPresent` lambda:

```java
gdprRequestRepository.findById(requestId).ifPresent(r -> { ... raiseErasureAlert(...); });
```

`eraseTransactional:194-195` throws `GdprRequest not found` precisely when
`gdprRequestRepository.findById(requestId)` is empty. `markFailed` calls **the same finder with the same
id** (`GdprErasureService.java:393`), gets the same empty `Optional`, and the lambda never runs. That
failure path remains exactly as silent as it is today.

The `"User not found"` path (`:200`) *is* reachable (the request row exists, the user row doesn't), so the
claim is half true — but the story presents both as covered.

**Fix:** move the alert outside the `ifPresent`, or add an `orElseGet` branch that alerts on the
missing-request case too (the `AdminAlert.referenceId` is the `requestId` string, which is available
regardless of whether the row exists). Whichever is chosen, the story's Fix-1 prose should stop claiming
the `:195` path is covered unless the code actually covers it.

---

### H2 (AC1) — The headline new trigger is pool saturation; the new alert write needs two connections from that same exhausted pool

The story's stated reason this fix is now urgent is `skillars-deferred-132` AC1 Fix 4's new pre-check:

> `erase()`'s own `assertConnectionPoolNotSaturated(requestId, "erase")` pre-check (`:181-182`) throws
> `PessimisticLockingFailureException` directly

Trace what happens after that throw:

1. `GdprEventListener.onErasureRequested` catches (`GdprEventListener.java:36-40`) and calls
   `markFailed` — `@Transactional(propagation = REQUIRES_NEW)` (`GdprErasureService.java:391`) —
   **connection acquisition #1** from the pool that was just reported saturated.
2. Inside it, the proposed code calls `raiseErasureAlert`, which runs on `requiresNewTemplate`
   (`PROPAGATION_REQUIRES_NEW`, `:155-156`, `:561`). That **suspends** `markFailed`'s transaction and opens
   a second one — **connection acquisition #2, held concurrently by the same thread**.

Under genuine saturation both acquisitions block up to Hikari's 30s `connection-timeout` (the value
`assertConnectionPoolNotSaturated`'s own Javadoc cites, `:581-582`). So the alert that exists to report
"the pool was saturated" is written on the path least able to write it, and Fix 1 *doubles* the number of
connections that path needs.

This is not hypothetical in test either — see **L3**: the IT the story proposes extending holds every
connection in the pool for the entire duration of `erase()`.

**Fix:** `markFailed` is *already* `REQUIRES_NEW` and *already* independent of the rolled-back erasure
transaction — the reason `raiseErasureAlert` needs its own `REQUIRES_NEW` (documented at `:553-558`: the
deadline caller throws immediately after) does not apply here. Write the alert **inside `markFailed`'s own
transaction** (one connection, not two), e.g. by extracting the insert body from `raiseErasureAlert` into a
non-transactional helper that both call sites use. Say so explicitly in the story, because "call the
existing private `raiseErasureAlert`" reads as the obvious choice and is the wrong one here.

---

### H3 (AC1) — The dedup rationale is wrong, and the real constraint exposes a live pre-existing bug the story instructs the dev to leave in place

The story justifies its reason-blind dedup check like this:

> `raiseErasureAlert` itself only dedupes per-`(requestId, reason)` (`:562-564`), so calling it
> unconditionally from `markFailed` would raise a **second**, redundant `UNCLASSIFIED_FAILURE` alert
> alongside an already-correctly-alerted `DEADLINE_EXCEEDED`

That is not what would happen. `V138__baseline_schema.sql:3166`:

```sql
CREATE UNIQUE INDEX admin_alerts_unique_open_per_ref
    ON admin.admin_alerts USING btree (reference_id, type)
    WHERE ((status)::text = 'OPEN'::text);
```

The index is on `(reference_id, type)` — **`reason` is not in it.** A second `OPEN` alert for the same
`requestId` + `GDPR_ERASURE_DEADLINE` does not get "raised redundantly"; it throws
`DataIntegrityViolationException` at flush. `raiseErasureAlert` has **no** catch for it (contrast
`AdminAlertEventListener.insertAlert:123-126`, which does exactly that catch and names this index in its
comment; `ReliabilityStrikeConcurrencyIT:45` names it too — the codebase knows about this index).

Two consequences:

**(a) The proposed fix is accidentally correct.** The reason-blind `findFirstByReferenceIdAndTypeAndStatus`
check happens to prevent the violation, so the shipped code would work — but the story's rationale is
wrong, which means a future reader who "corrects" it back to the reason-aware finder (the one
`AdminAlertRepository.java:51-59`'s Javadoc argues for) would introduce the crash. Document the real
reason: **the DB forbids a second OPEN alert per `(reference_id, type)`.**

**(b) There is a live pre-existing bug in the code AC1 tells the dev not to touch.** The story says:

> Four of the known failure reasons are already alerted, and must stay exactly as-is

But `raiseErasureAlert`'s per-`(requestId, reason)` dedup (added by the 2026-09-23 code review, Decision 2,
`:544-551`) is *incompatible with the index it writes through*. A PARENT erasure that raises
`CHILD_VANISHED` for child A and then `CHILD_CONTENDED` for child B (`:494` then `:516`) passes the
reason-aware dedup check and then violates `admin_alerts_unique_open_per_ref`. The
`DataIntegrityViolationException` is uncaught and propagates out of the `catch` block it was thrown from,
out of the `for` loop, out of `eraseParentChildren`, out of `eraseTransactional` — converting a designed
skip-and-continue into a full rollback + `FAILED`, and losing the alert.

Every alert assertion in `GdprErasureIT` (`:1037`, `:1181`, `:1271`, `:1400`, `:1487-1490`, `:1565-1568`)
checks exactly one reason per request, so nothing covers the multi-reason case.

AC1 makes this worse by adding a 5th reason to the same slot. **This belongs in AC1's scope** — either fix
`raiseErasureAlert` (catch `DataIntegrityViolationException` like `insertAlert` does, or widen the index to
include `reason` via the V153 migration AC3 already needs) or record it explicitly as a newly-found ledger
item. Silently leaving it is not defensible now that AC1 has analysed this exact dedup logic in detail.

---

### H4 (AC3) — The orphan branch is not an orphan detector; it is also the normal post-cancellation state

AC3's entire premise is:

> if neither matches, both just `log.warn(...)` and `return` ... This is exactly the detection point for
> the ledger's still-open Stripe → payment residual

It is not. `SubscriptionService.handleSubscriptionDeleted` **deliberately nulls the link** on successful
cancellation, on both sides:

- coach: `sub.setStripeSubscriptionId(null)` — `SubscriptionService.java:690`
- player: `sub.setStripeSubscriptionId(null)` — `SubscriptionService.java:703`

So after any normal, correctly-processed cancellation, *every* subsequent Stripe event carrying that
`sub_...` id falls into `StripeWebhookService`'s orphan branch (`:158-161` / `:174-177`) — because the local
row exists and is correct, and has simply been unlinked by design.

This is reachable on the ordinary path. Stripe does not guarantee webhook delivery order, and a subscription
cancelled immediately emits `customer.subscription.updated` (status → `canceled`) alongside
`customer.subscription.deleted`. If `.deleted` lands first, the `.updated` that follows is a **different
event id** — `handleEventAtomically`'s `insertIfAbsent` idempotency (`:83-87`) does not suppress it — and it
lands in the orphan branch of a perfectly healthy cancellation.

The story actually *names* this scenario in Fix 3 item 5 — "a later `.updated` after an earlier `.deleted`"
— but classifies it as "the *same still-orphaned* subscription" needing dedup. It is not orphaned. It is
the designed terminal state.

Compounding it: **there is no way to clear the resulting alert.** The only four `AdminAlertStatus.RESOLVED`
writers in the codebase are all bound to specific domain actions —
`AdminConversationService:88`, `AdminCoachEnforcementService:614` (`STRIKE_THRESHOLD` only),
`AdminMessageService:180`, `DisputeService:360` — and there is no generic dismiss endpoint
(`AdminAlertRepository.java:64-75` says so in as many words). A false-positive `SUBSCRIPTION_ORPHANED`
alert sits `OPEN` in the admin queue forever, and because of the unique index (H3) it also blocks any
*genuine* later alert for that same reference.

**Fix:** the orphan branch cannot be used as-is. Either
(a) distinguish "no row at all" from "row exists, unlinked, `status = CANCELLED`" — look up by
`coachId`/`playerId` resolved from the Stripe customer before concluding orphan; or
(b) go back to the ledger's original ask (a Stripe→payment sweep), which can apply a staleness window; or
(c) restrict AC3 to `customer.subscription.updated` with a non-terminal Stripe status, and explicitly
exclude `.deleted`.
Whichever is chosen, AC3's Context paragraph needs rewriting — "already detects this exact condition at two
call sites" is the claim the whole owner decision rests on, and it is false.

---

### H5 (AC3) — Any failure in the new alert path rolls back the webhook idempotency record and puts Stripe into an indefinite retry loop

Today the orphan branch is a guaranteed-safe no-op: `log.warn` + `return`, transaction commits, idempotency
row persists, `StripeWebhookResource` returns `200` (`:44-45`).

AC3 inserts three new DB operations into that branch, all inside `handleEventAtomically`'s `@Transactional`
(`:81-82`):

1. `stripeCustomerRepository.findByStripeCustomerId(...)`
2. `coachProfileRepository.findByUserId(...)`
3. `adminAlertRepository.save(new AdminAlert(...))`

Anything thrown by these propagates to `StripeWebhookResource:49-51` → `catch (Exception e)` → **HTTP 500**,
and the enclosing transaction rolls back the `insertIfAbsent` idempotency row. Stripe retries a 500 for up
to ~3 days. If the cause is deterministic, that is a permanent retry loop on every affected event.

Concrete deterministic causes, all reachable:

- **A `V153` / enum mismatch.** `admin_alerts_type_check` is a CHECK constraint; a value present in
  `AdminAlertType` but absent from the constraint (or a typo in either) fails *every* insert. I searched:
  **no test cross-checks the `AdminAlertType` enum against the DB CHECK constraint.**
  `AdminQueueIT`'s `READ_TYPE_CHECK_DEF` (`:344-346`) reads the constraint from `pg_constraint` and
  restores it — it deliberately does not pin its contents. So the story's "search for one before assuming
  none exists" resolves to: none exists, and AC3 ships an unverified enum↔constraint pair.
- **`admin_alerts_unique_open_per_ref` (H3).** The story's dedup is a check-then-insert with no
  `DataIntegrityViolationException` catch. Two concurrent webhook deliveries, or the
  referenceId-collision case in M4, violate it.
- **`IncorrectResultSizeDataAccessException`** from the new `Optional` finder — see **L4**.

**Fix:** raise the alert outside the webhook transaction (see M3 — the existing `AdminAlertEventListener`
seam gives you `REQUIRES_NEW` for free), and/or wrap the whole new block in a `catch (Exception)` that logs
and falls through to the existing `log.warn` + `return`. The webhook's 200-and-commit behaviour must not
become conditional on the alerting path succeeding.

---

## MEDIUM

### M1 (AC3) — An event-driven alert is not equivalent to the sweep the ledger asked for, and the story presents it as one

Owner Decision 2 reframes the work as "extend the existing webhook orphan handlers, not build a new
scheduled sweep", on the grounds that the detection already exists. But the failure mode the ledger
describes (`deferred-work.md:3356-3379`) is `persistCoachSubscription` rolling back *after*
`stripeClient.createSubscription` succeeded. In that state:

- `handleEventAtomically` dispatches only four event types (`:88-98`). **`customer.subscription.created` is
  not one of them** — so subscription creation, the moment the orphan is born, triggers nothing.
- The next `customer.subscription.updated` for a healthy active subscription may not arrive until the next
  billing-period renewal.

So detection latency goes from "one sweep interval" to "whenever Stripe next happens to send a handled
event for this subscription" — potentially a full billing cycle of the coach being charged with no local
record. The ledger's own words (`:3372-3373`, `:3699-3701`) ask for "a compensating action **or** a
reconciliation sweep"; the webhook hook is neither.

This may still be an acceptable *first* step, but the story must say plainly that it narrows the ledger
item rather than closing it — exactly the discipline AC4 correctly applies to Fix 1's auto-retry half.

### M2 (AC3) — `AdminQueueSummaryDto` needs a new bucket; the story omits it, and this is the precedent `skillars-deferred-128` already fixed

AC3 lists one `AdminQueueService` change (a `buildSummary` case). But `getSummary` (`:229-242`) computes
`total` over *all* enum-mapped types while reporting seven named buckets. A new
`AdminAlertType.SUBSCRIPTION_ORPHANED` with no bucket makes `total` silently exceed the sum of the reported
buckets — which is, verbatim, the defect `skillars-deferred-128` fixed for `GDPR_ERASURE_DEADLINE`, and it
is documented in both files the story cites:

> without its own bucket here, an open `GDPR_ERASURE_DEADLINE` alert ... was invisible from this six-bucket
> summary entirely, while still counted in `total` above — making `total` silently exceed the sum of every
> bucket this DTO actually reports. — `AdminQueueService.java:236-240`, mirrored at
> `AdminQueueSummaryDto.java:10-12`

Add `AdminQueueSummaryDto` (a new record component) to Task 4. Note this also changes the `/queue/summary`
API response shape — the story's "No frontend changes anticipated" holds only because there is no frontend
directory in this repo; any external consumer still sees a new field.

### M3 (AC3) — Reinvents `AdminAlertEventListener.insertAlert`, and makes `platform.payment` the first module outside `admin` to write `admin_alerts`

The story never mentions `AdminAlertEventListener`. It is the established seam: every cross-module alert is
raised by publishing a domain event that this listener consumes —
`onReviewFlagged:82-86`, `onStrikeThreshold:88-94` (already `AdminAlertReferenceType.COACH`),
`onDisputeRaised:96-102`. Its private `insertAlert` (`:104-127`) already implements *exactly* what Fix 3
item 5 asks to be hand-written: the `(referenceId, type, OPEN)` dedup check **plus** the
`DataIntegrityViolationException` catch for the concurrent-insert race that the check alone cannot cover.

I verified: `AdminAlertRepository` is referenced **only** from `platform.admin` today. `new AdminAlert()`
appears in exactly two places, both in `platform.admin`. AC3 would break that boundary for no stated reason.
(`platform.payment` → `platform.marketplace` is already well-established — `SubscriptionResource`,
`RevenueReportingService`, etc. — so the `CoachProfileRepository` half is fine.)

**Recommendation:** publish a `SubscriptionOrphanDetectedEvent` from `StripeWebhookService` and add an
`@EventListener @Transactional(REQUIRES_NEW)` handler to `AdminAlertEventListener` calling `insertAlert`.
This reuses the dedup + race handling, keeps the alert write out of the webhook transaction (solving H5),
and keeps `admin_alerts` writes inside `platform.admin`.

### M4 (AC3) — The `referenceId` design is self-contradictory under the unique index, and has no valid value for the unresolvable case

Fix 3 item 2 sets `referenceId = coachProfile.getId().toString()` with `referenceType = COACH`. Fix 3 item 5
then asks to "not raise a second `OPEN` alert for the same orphaned **Stripe subscription id**". These are
incompatible: `admin_alerts_unique_open_per_ref` is keyed on `(reference_id, type)`, so with
`referenceId = coachId` the DB permits exactly **one** open alert per coach — two genuinely distinct
orphaned subscriptions for the same coach collapse into one, and the second insert throws (H5). Using
`referenceId = stripeSubId` instead would make `referenceType = COACH` semantically wrong and break
`buildSummary`'s coach rendering.

Separately, Fix 3 item 2's unresolvable-customer branch is left as "decide at implementation time" — but
`AdminAlert.referenceId` is `@Column(nullable = false, length = 36)` (`AdminAlert.java:37-38`), so "still
alert" is not implementable without *some* referenceId. Note also that a `cus_...` id fits 36 chars but a
future longer Stripe id would not.

Resolve both in the story rather than deferring: pick the reference identity, then state the dedup
semantics that identity actually gives you.

### M5 (AC3) — Race with `subscribeCoach` produces false positives in the normal happy path

`subscribeCoach` (`SubscriptionService.java:111`) carries no `@Transactional`. It calls Stripe at `:148`,
then commits the local link in a **separate, later** transaction (`persistCoachSubscription:158`, setting
`stripeSubscriptionId` at `:162` and saving at `:167`). Any Stripe subscription event delivered inside that
window — e.g. the `customer.subscription.updated` that fires when an incomplete subscription transitions to
`active` after the first charge — finds no matching row and lands in the orphan branch.

Today that is a harmless no-op that resolves itself milliseconds later. With AC3 it becomes a permanently
`OPEN`, unresolvable admin alert (H4) for a subscription that is entirely healthy. Any remedy needs either a
grace period before alerting or a re-check at alert time.

### M6 (AC3) — `handleInvoicePaymentFailed` has the identical silent no-op and is untouched and unmentioned

`handleInvoicePaymentFailed` (`StripeWebhookService.java:183-202`) has **no** orphan check at all — it
passes `stripeSubId` straight to `subscriptionService.handleSubscriptionWebhook` (`:201`), whose
`handleInvoicePaymentFailed` (`SubscriptionService.java:711-730`) no-ops silently via `.ifPresent` on both
coach and player. A payment failing against an orphaned subscription is arguably the *most* actionable
signal of all, and AC3's "both orphan branches" scope excludes it without comment.

If AC3's scope is deliberately the two `customer.subscription.*` branches, say so and record the invoice
path as a residual in the AC4 closeout.

### M7 (AC2) — The stated motivation inverts the semantics of the two `getBoundedLong` overloads

AC2's Context:

> an operator ... has no registry-level record of what the call site falls back to if the stored value is
> ever absent/blank, **only what it clamps a present-but-out-of-range value to**.

`ConfigService.getBoundedLong(key, defaultValue, min, max)` — the 4-arg overload used by 17 of the 18 keys —
**does not clamp**:

```java
public long getBoundedLong(String key, long defaultValue, long min, long max) {
    long value = getLong(key, defaultValue);
    if (value < min || value > max) {
        log.warn("... — using default {}", ...);
        return defaultValue;          // ConfigService.java:110-118
    }
    return value;
}
```

Clamping is the **3-arg** overload's behaviour only (`:128-141`) — i.e. `TIMELINE_COACH_ACCESS_EXPIRY_DAYS`,
the one key the story singles out as *not* fitting the pattern. `VideoLifecycleScheduler.java:85-87` states
this outright: "the 4-arg overload falls back to the 90-day default on out-of-range".

So for 17 of 18 keys the registry's `min`/`max` tell you nothing about what happens on out-of-range — the
default does, and the default is the thing not in the registry. The fix is **more** valuable than the story
argues; the argument as written is simply wrong and should be corrected so the dev doesn't propagate it into
the new field's Javadoc.

### M8 (AC1) — The "reuse an existing alert type" precedent is misattributed, and `gdprErasureDeadlines` starts counting non-deadline failures

The story:

> mirroring this codebase's own stated preference for reusing an existing alert type over adding a narrow
> new one

`V152__admin_alerts_gdpr_erasure_deadline_type.sql`'s own comment states the opposite criterion:

> Reusing an existing value (e.g. MODERATION_UNRESOLVED / COACH) would misrepresent this alert's real
> subject in the queue UI and in AdminQueueService's per-type counts.

`raiseErasureAlert`'s Javadoc (`:537-538`) does reuse the type — but for reasons that are themselves
deadline-budget-adjacent (child skips inside a deadline-budgeted PARENT run). `UNCLASSIFIED_FAILURE` covers
a saturated connection pool and a missing `User` row; neither is a deadline. The consequence is concrete:
`AdminQueueSummaryDto.gdprErasureDeadlines` and the `/queue?type=GDPR_ERASURE_DEADLINE` filter start
reporting non-deadline failures under a name that says otherwise.

Reuse may still be the right call (it avoids a migration, and the `reason` prefix in `buildSummary:177-180`
does disambiguate in the queue UI). But justify it on those grounds, not on a precedent that says the
reverse — and note the summary-bucket semantics shift in the AC4 closeout.

### M9 (AC4) — The proposed `[DECIDED]` revisit trigger drops one of the argument's two legs

Proposed tag:

> `[DECIDED: accepted, unreachable by construction — revisit trigger: AuthorRole ever adds COACH]`

`deferred-work.md:3412-3423` records a **two-part** argument, and says so explicitly at `:3421-3423`:
"this protection is a contract-enum membership **plus** a role-precedence check in the API layer".

Both legs are real and independently breakable. I verified the second:

```java
private String resolveRole(Authentication auth) {                 // ReviewResource.java:142-146
    if (... "ROLE_COACH") return "COACH";     // resolved FIRST
    if (... "ROLE_PARENT") return "PARENT";
    return "PLAYER";
}
```

A coach who also holds `ROLE_PARENT` is only kept out of authoring reviews because `ROLE_COACH` is checked
first and `AuthorRole.valueOf("COACH")` then throws (`AuthorRole` = `{PARENT, PLAYER}`). Reorder those two
lines — a plausible change nobody would connect to a GDPR lock ordering — and a coach can author a review
with `AuthorRole.PARENT`, making the lock sets overlap, **without anyone touching `AuthorRole`**.

The single-leg trigger would not fire. Use both: *"revisit if `COACH` is added to `AuthorRole`, or if
`ReviewResource.resolveRole`'s role precedence changes."*

### M10 (AC4) — M5-2's revisit trigger is misquoted, and `[CLOSED by ...]` overstates what happened

The story:

> its stated revisit trigger ("flag() blocking behind concurrent admin moderation") was resolved by
> `skillars-deferred-132` AC1 Fix 2's new `findByIdForUpdateNoWait` method

The actual trigger (`deferred-work.md:3480-3482`) is two-pronged and neither prong reads that way:

> Revisit if flag-endpoint latency or admin-moderation contention is **observed in production**, or when
> the reviews module is next opened for a locking change.

The first prong cannot have fired — per `skillars-deferred-117`'s re-confirmed decision (which this story
itself cites in AC3), no production deploy has ever happened. The second prong fired at
`skillars-deferred-131` and was explicitly re-triaged and left armed (`:3488-3496`, closing with "The
trigger remains armed for a future story that actually touches this lock's discipline").

The decision itself was about converting the **shared** `findByIdForUpdate` to NOWAIT across all its call
sites — the story's own annotation text concedes that method is unchanged. So `[CLOSED by ...]` is the wrong
label for an item that is, by the story's own description, not closed. Use something like
`[Trigger partially discharged by skillars-deferred-132 AC1 Fix 2 — flag() no longer blocks, via a separate
findByIdForUpdateNoWait method; the shared findByIdForUpdate and its other 5 call sites are unchanged and
the module-wide NOWAIT conversion remains armed]`.

One thing the story gets **right** here and should not be talked out of: "**other 5 call sites**". The
ledger's older "four call sites" (`:3478-3479`, `:3494`) is stale. Actual `CoachReviewRepository
.findByIdForUpdate` call sites: `ReviewSubmissionService:129`, `:164`, `ReviewModerationService:102`,
`AdminReviewService:81`, `:121` — five, matching `ReviewFlagService.java:110`'s own comment.

### M11 (AC2) — The registry default becomes a third uncross-checked copy of each number, with no drift detector

`ConfigBounds`'s class Javadoc (`:33-35`) justifies the duplicated `min`/`max` literals because
"Mockito `verify(...)` in the per-site unit tests pins the exact numbers" — the duplication is tolerable
*because a test detects drift*. AC2 adds a third copy of the default with no such link: the proposed test
only asserts `default() >= min() && default() <= max()`, which passes for any wrong-but-in-range value.

Only 4 of the 18 keys even reference their constant at the call site (`ReviewSubmissionService:201`,
`ReviewFlagService:157`, `RadarCompositeCalculationService:212`, `GdprErasureService:269`/`:435` — the Dev
Notes get this count right), and only a handful have `verify(...)` pinning tests at all. So a future edit to
`PlaybackService.java:111`'s `120L` leaves the registry silently claiming `120` forever — the registry
becomes authoritative-looking and unverified, which is arguably worse than absent.

Either add a cross-check (a test that, per key, asserts the call site passes `BoundedKey.defaultValue()` —
which in practice means the call sites *do* read the accessor, i.e. the migration the owner decision
excluded), or state plainly in the field's Javadoc that the registry default is **documentation, not a
verified contract**, and that the call-site literal remains authoritative. The second is cheap and honest;
pick it deliberately rather than by omission.

---

## LOW / factual corrections

### L1 (AC1) — "the one existing test in that file" — there are four
`GdprErasureServiceTest` has four `@Test` methods: the bounds-literal pin (`:138`) plus three
`erase_parentUser_*_readsLockTimeoutConfigExactlyOnce` tests (`:174`, `:185`, `:197`) added by
`skillars-deferred-132` AC2 Fix 9. Minor, but the story asserts all citations were re-verified at `HEAD`.

Useful for the dev: that file constructs `GdprErasureService` via a 27-argument positional constructor
(`:118-126`) and wires `self` by reflection (`:134`) — adding any new dependency means editing this.

### L2 (AC3) — `StripeWebhookVerificationTest` does cover event handling
The story says it "covers signature verification, not event handling", leaving "extend it or add a new
class" as an open decision. Four of its six tests are event handling:
`processWebhook_duplicateEventId_returnsWithoutProcessing` (`:114`),
`processWebhook_accountUpdated_chargesDisabled_transitionsToRestricted` (`:126`),
`processWebhook_invoicePaymentFailed_incrementsInvoiceFailedCounter` (`:144`),
`processWebhook_invoicePaymentFailed_noSubscription_doesNotIncrementCounter` (`:158`).
It already mocks `PaymentCoachSubscriptionRepository` / `PaymentPlayerSubscriptionRepository` and has
payload+signature builders. It is clearly the right place — the decision can be closed now, not deferred.
Note its `setUp:64-66` calls the `@RequiredArgsConstructor` constructor positionally, so any new constructor
dependency requires updating it.

### L3 (AC1) — The IT the story proposes extending asserts neither `FAILED` nor calls `markFailed`
The story:

> should now leave an `OPEN` `AdminAlert` behind, not just the `FAILED` status that test already asserts —
> extend that existing IT

`GdprErasureIT.erase_connectionPoolSaturated_failsFastInsteadOfBlockingForTheFullConnectionTimeout`
(`:1063-1102`) asserts only the thrown `PessimisticLockingFailureException` and elapsed time `< 5s`. It
asserts no status. Three reasons it cannot be extended as described:

1. It calls `gdprErasureService.erase(...)` **directly** (`:1081`), bypassing `GdprEventListener` — so
   `markFailed` is never invoked at all.
2. It passes `UUID.randomUUID()` as the requestId (`:1081`) with no seeded `gdpr_requests` row — so even
   via the listener, `markFailed`'s `ifPresent` would not fire (this is H1 again).
3. It holds all `maxPoolSize` connections for the whole body (`:1071-1091`), so `markFailed` +
   `raiseErasureAlert` would block on connection acquisition (H2) and blow the `< 5s` assertion.

A meaningful IT for this path needs a seeded `GdprRequest`, routing through the listener, and a saturation
mechanism that leaves at least two connections free — i.e. a new test, not an extension.

### L4 (AC3) — `findByStripeCustomerId` returning `Optional` is unguarded by any uniqueness constraint
`payment.stripe_customers` (`V138__baseline_schema.sql:1919-1926`) has `PRIMARY KEY (parent_id)` and a
format CHECK on `stripe_customer_id`, but **no unique constraint and no index** on `stripe_customer_id`.
Two `parent_id` rows sharing a Stripe customer would make the new `Optional` finder throw
`IncorrectResultSizeDataAccessException` → H5's retry loop. Either add the unique index in `V153`, or use
`List<StripeCustomer>`/`findFirstBy...`. An index is worth adding regardless (this becomes a sequential scan
on every orphaned webhook).

### L5 (AC2) — The record change touches 32 constructor sites, not 18
`ConfigBounds.java` contains 32 `new BoundedKey(...)` calls: the named constants plus the ones generated in
the static block (`:384-398`, 6 tier segments × 2 + 3 type segments × 2). All are in that one file — I
confirmed no `new BoundedKey(` exists anywhere else in `src/main` or `src/test` — so the blast radius is
contained, but Task 3's "populated for all 18 `HAS_CODE_DEFAULT` keys per the table above" understates the
edit. The other 14 named constants and 18 generated entries each need the sentinel argument too.

Related: `VIDEO_LIFECYCLE_BATCH_SIZE`, `MODERATION_SLA_BATCH_SIZE`, `MESSAGE_RETENTION_MONTHS`,
`REVIEWS_SUBMISSION_WINDOW_DAYS` and `REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` use `getBoundedInt`, not
`getBoundedLong` — a `long defaultValue` field stores them fine, just note the widening in the Javadoc.

### L6 — `AdminAlert.reason`'s Javadoc is already stale
`AdminAlert.java:57`: *"Populated only by the messaging moderation path; null elsewhere."* False since
`skillars-deferred-128` (`GdprErasureService:572`). AC1 adds a 5th reason and AC3 may add more — a one-line
fix worth folding into Task 2.

---

## Verified correct — do not re-litigate these

Recorded so a later reviewer doesn't burn time re-checking what I already opened:

- **AC2's table is 18/18 accurate** — every default value *and* every call-site line number checked against
  the live source: `PackSessionService:173` (via `DEFAULT_PACK_PAUSE_MAX_DAYS = 90L` at `:50`),
  `DisputeService:110`, `VideoLifecycleScheduler:84`/`:89`/`:90`,
  `VideoSubscriptionLifecycleListener:125`, `ModerationSlaMonitorService:107`, `PlaybackService:111`,
  `VideoAccessGuard:98`, `VideoDeletionOutboxProcessor:372`, `RadarCompositeDlqProcessor:274`,
  `GdprExportService:87`, `MessageRetentionScheduler:52`, `ReviewSubmissionService:201`,
  `ReviewFlagService:157`, `TimelineQueryService:33`/`:36`, `RateLimitingService:123`,
  `RadarCompositeCalculationService:212`, `GdprErasureService:269`/`:435`. Also correct: the
  `TIMELINE_COACH_ACCESS_EXPIRY_DAYS` 3-arg-overload correction, and the Dev Notes' count of 4 call sites
  using `.key()`.
- **Every `GdprErasureService` citation:** `markFailed:391-398`, `raiseErasureAlert:560-575`, dedup
  `:562-564`, reason constants `:147-151`, pre-check `:181-182`, `:195`/`:200`, alert call sites `:292`,
  `:316`, `:463`, `:494`, `:516`.
- **`GdprEventListener:36-40`**, **`AdminAlertRepository:48-49`/`:58-59`**,
  **`AdminQueueService:173-176`/`:177-180`** (the "no code change needed, update the stale 4-reasons
  comment" conclusion is right — `buildSummary` prefixes generically and has a `default -> ""` at `:181`).
- **`ConfigBounds:60-61`** (5-component record, no default), **`:330-348`** (exactly 18 keys),
  **`:33-35`** (literals-as-drift-detector convention), **`ConfigService:128`** (3-arg overload),
  **`ConfigBoundsEnumCoverageTest:102-109`**.
- **Every `StripeWebhookService` citation:** `:151-165`, `:167-181`, `:156-157`, `:172-173`, `:158-161`,
  `:174-177`, `:204+`, `:83-87`.
- **Every `SubscriptionService` citation:** `:111`, `:130-133`, `:148`, `:158-188`, `:662-674`, `:663`,
  `:750-757` — including the story's pre-implementation fact-check that `findOrCreateCoachSubscription`
  eagerly commits a placeholder row before the Stripe call. That analysis is correct and is a genuine
  improvement on the ledger's "no local row at all" framing.
- **`deferred-work.md:2985-3001`, `:3356-3379`, `:3688-3697`** — all three land exactly on the cited text.
- **Migration facts:** `V152` is the highest existing migration, so `V153` is correct.
  `SUBSCRIPTION_ORPHANED` is 21 chars and fits `type varchar(25)` (`AdminAlert.java:33-34`).
  `COACH` is already in `admin_alerts_reference_type_check`, so no `reference_type` migration is needed.
- **`AdminAlertReferenceType.java:4`**, **`CoachProfileRepository.findByUserId` at `:24`**,
  **`StripeCustomer.parentId` as the `@Id` at `:21-23`** and the claim it is the coach/parent **user id**
  (confirmed by `SubscriptionService:130-133`), **`StripeCustomerRepository.java:5`** (no finders).
- **`Subscription.getCustomer()` returns `String`** in stripe-java 28.4.0 (verified against the jar) — the
  proposed resolution chain is mechanically workable.
- **AC4's "other 5 call sites"** for `CoachReviewRepository.findByIdForUpdate` — correct; the ledger's
  "four" is the stale number.
- **The story's scope-discipline framing** (3 ACs, don't pad, auto-retry stays explicitly open, no
  priceId→tier reverse map) is sound and well-argued. AC3's alert-only decision is the right call for the
  reason given — the problem is the detection mechanism, not the decision to avoid auto-heal.

---

## Recommended disposition

**AC1** — implementable after three corrections: move the alert out of `ifPresent` (H1), write it inside
`markFailed`'s own transaction rather than nesting a second `REQUIRES_NEW` (H2), and either fix or formally
record `raiseErasureAlert`'s unique-index incompatibility (H3). Replace the dedup rationale with the real
one. Replace the IT plan (L3). Resolve the alert-type naming question honestly (M8).

**AC2** — implementable close to as written; it is the best-evidenced AC in the story. Correct the inverted
overload semantics in the Context (M7), state the 32-site scope (L5), and make an explicit, documented
choice about drift detection (M11).

**AC3** — **send back for redesign.** H4 invalidates the premise the owner decision was taken on: the
orphan branch is also the normal post-cancellation state, so the fix as specified generates
permanently-unresolvable false positives on routine cancellations. H5, M1–M6 all compound from there. The
owner should see the corrected picture before this is built, since Decision 2 was taken on the strength of
"already detects this exact condition".

**AC4** — implementable after tightening both annotations: use the two-legged revisit trigger (M9) and a
label that matches what actually happened to M5-2 (M10). Add residuals for whatever M1, M6 and M11 end up
leaving open.

---

## Disposition — 2026-09-24, applied to the story

Independently re-verified against live `HEAD` (not taken on faith): H1, H2, H3, H4, H5, M2, M7, M9, and
L5 were each re-checked directly against source (exact file:line spot-checks — `GdprErasureService.java`
lines 190-200/385-398/535-580, `AdminAlertEventListener.java` lines 104-127, `V138__baseline_schema.sql:
3166`, `ConfigService.java:110-141`, `SubscriptionService.java` lines 662-703, `StripeWebhookService.java`
lines 145-185/78-114, `StripeWebhookResource.java`, `ReviewResource.java:142-146`, `AuthorRole.java`,
`AdminQueueService.java`/`AdminQueueSummaryDto.java`, and a `new BoundedKey(` count in `ConfigBounds.java`).
**Every one confirmed exactly as described — no false positives found in this sample**, so the remaining
findings (M1, M3–M6, M8, M10, M11, L1–L4, L6) were trusted at the same standard of evidence this reviewer
already demonstrated, rather than independently re-derived line by line.

All 23 findings (5 High, 11 Medium, 7 Low) are now reflected in the story:

- **AC1**: rewritten per H1 (alert moved outside `ifPresent`), H2 (extracted non-transactional
  `insertErasureAlertIfAbsent` helper — no more nested `REQUIRES_NEW`), H3 (documented the real,
  DB-driven dedup reason; added the `DataIntegrityViolationException` catch that also fixes the
  pre-existing live multi-reason bug H3 surfaced), M8 (corrected the misattributed "reuse" precedent),
  L1/L3 (corrected test-file/IT claims — a new IT is specified, not an extension), L6
  (`AdminAlert.reason` stale Javadoc folded into Task 2).
- **AC2**: Context corrected per M7 (the overload-semantics claim was inverted — fixed, and the fix is
  now argued as *more* valuable, not less); scope corrected per L5 (32 constructor sites, not 18); M11's
  drift-detection gap resolved by an explicit documented choice (registry default = documentation, not
  a verified contract) rather than left implicit.
- **AC3**: redesigned per H4 (owner re-confirmed live via AskUserQuestion — restrict to live/non-terminal
  Stripe status, never `.deleted`, never a terminal-status `.updated`); H5 and M3 resolved together by
  moving the `AdminAlert` write to a new `AdminAlertEventListener` handler (reuses `insertAlert`'s
  existing dedup + race-catch, `REQUIRES_NEW`) plus a wrapping `catch (Exception)` in
  `StripeWebhookService` itself; M1 and M6 recorded as explicit residuals rather than silently dropped;
  M2 addressed (new `AdminQueueSummaryDto` bucket added to scope); M4 resolved (referenceId = coachId,
  per-coach dedup granularity stated and justified via the `STRIKE_THRESHOLD` precedent, unresolvable
  customers explicitly out of scope, not left open); M5 resolved (10-minute grace check via the already
  -existing `findByCoachId` + `PaymentCoachSubscription.createdAt`/`updatedAt`); L2 corrected (extend
  `StripeWebhookVerificationTest`, don't add a new class); L4 resolved (`List`-returning finder, not
  `Optional`, avoiding the `IncorrectResultSizeDataAccessException` risk entirely).
- **AC4**: M9 (two-legged `[DECIDED]` trigger, both legs stated and independently verified) and M10
  (`[Trigger partially discharged by ...]`, not `[CLOSED by ...]`) both applied; AC5-closeout task
  updated to record Fix 1's and Fix 3's residuals explicitly.

Story status remains `ready-for-dev`. No further review pass requested at this time.
