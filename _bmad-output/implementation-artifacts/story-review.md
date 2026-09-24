# Story Review: `skillars-deferred-134-admin-alert-flush-fix-and-invoice-orphan-alerting`

**Reviewed:** 2026-09-24
**Reviewer:** senior-dev audit (adversarial, source-verified) — supersedes an earlier pass on this same
file (see "Superseded prior review" at the end) whose citation-only method produced a false "no blocking
issues" verdict.
**Story HEAD:** verified against local `master` post-`skillars-deferred-133` merge (PR #227) + the 6
Dependabot PRs #221–#226 (`ee719dc2`), which is the actual tree `story/deferred-134-alert-flush-invoice-orphan`
branched from — the story's own citations were drafted against
`origin/story/deferred-133-gdpr-alerts-bounds-stripe` (pre-merge) per its own Dev Notes disclaimer.
**Method:** every file, line citation, constraint, and precedent named in the story was opened and read
directly against current `master`, not taken on the story's or the prior review's word. AC1's proposed
fix was additionally traced through JPA/Hibernate/Postgres transaction semantics rather than just
diffed against its stated template.

---

## Verdict

**The story's AC1 fix, as originally drafted, does not work — and the prior review pass on this file
missed it entirely.** The prior review verified all 23 citations as accurate (they are — see "Citation
accuracy" below, which stands) but never engaged with what actually happens when the proposed
`saveAndFlush` + `catch` fix runs inside a transaction the caller also writes to. That gap has now been
corrected directly in the story file (see "Disposition" below); this document records the finding and
why the prior review's own "✅ SOUND" verdict on the exact corner case in question was wrong.

| | Count |
|---|---|
| **Blocker** | 1 (AC1's fix mechanism) |
| **Confirmed accurate** (prior review's citation work) | 23/23 file:line citations |

---

## BLOCKER

### B1 (AC1) — `save` → `saveAndFlush` alone does not protect the caller for 5 of `insertAlert`'s 7 callers; it only works for the 2 already-`REQUIRES_NEW` ones

**The story's original claim** (AC1 "The fix," pre-correction): this "mirror[s]
`GdprErasureService.insertErasureAlertIfAbsent`'s already-shipped fix exactly... No other change to
`insertAlert`'s signature, callers, or the surrounding `isPresent()` pre-check is needed."

**Why that's false.** Once a JPA `flush()` throws a `ConstraintViolationException`
(`DataIntegrityViolationException` after Spring's translation), Hibernate marks the *current*
`EntityTransaction` rollback-only. Catching the exception in application code does not undo that marking
— the surrounding transaction is doomed regardless of whether anyone catches the exception that reports
it. `GdprErasureService`'s fix is safe not because of `saveAndFlush` per se, but because **every one of
its call sites already isolates the write in its own dedicated `REQUIRES_NEW` transaction**:
`markFailed` is itself `@Transactional(REQUIRES_NEW)`, and `raiseErasureAlert` wraps the call in
`requiresNewTemplate.executeWithoutResult(...)` (`GdprErasureService.java:583-584`). When that isolated
transaction is discarded, the only casualty is the alert insert itself — correct, since the whole point is
"someone else already has this alert open, skip gracefully."

`AdminAlertEventListener.insertAlert` does not have that property for 5 of its 7 callers —
`onMessageReported`, `onConversationReported`, `onReviewFlagged`, `onStrikeThreshold`, `onDisputeRaised`
are all plain `@Transactional` (`REQUIRED` propagation), which **joins** whatever transaction their
publisher is already running in, rather than starting a new one. Traced concretely through
`ReviewFlagService.flag()` (`ReviewFlagService.java`, class-level `@Transactional`): it calls
`reviewFlagRepository.saveAndFlush(flag)` for its own primary write (its `ReviewFlag` row), then as its
*last statement* calls `eventPublisher.publishEvent(new ReviewFlaggedEvent(...))` — which synchronously
invokes `onReviewFlagged` → `insertAlert`, **inside that same transaction** (`REQUIRED` joins; it does not
suspend and start a new one). If two flaggers race past `insertAlert`'s `isPresent()` pre-check for the
same review, the loser's `saveAndFlush(alert)` throws inside `insertAlert`'s own try block. The `catch`
swallows it and logs at `debug`. But `ReviewFlagService.flag()`'s transaction is now marked
rollback-only. When Spring's transactional advice for `flag()` tries to commit, JPA throws — the loser's
own `ReviewFlag` row (the write they were actually trying to make) is rolled back with it, typically
surfacing as `UnexpectedRollbackException`/`TransactionSystemException` at the HTTP layer, with the real
cause (a harmless duplicate-alert race) swallowed at `debug` inside `insertAlert` and never attached to
what the caller actually sees.

This is not a *new* failure mode in the sense of newly introducing silent data loss — today's uncaught
exception at commit-time auto-flush already loses the caller's write the same way. But it **completely
defeats this AC's stated purpose**: stopping a benign alert-dedup race from taking down the caller's
primary business operation. The fix as drafted achieves that only for the 2 callers that were already
`REQUIRES_NEW` (`onMessageHeldForReview`, `onCoachSubscriptionOrphaned`) — where `GdprErasureService`'s
precedent genuinely applies — and silently fails to deliver it for the other 5, while also regressing
debuggability (the real cause is now swallowed instead of propagating as the accurately-typed exception
it is today).

**Why the prior review missed this.** Its "Corner-Case & Assumption Audit" table asked exactly the right
question — *"What if a third caller publishes the same alert type before `saveAndFlush` flushes?"* — and
answered "Unique index still catches it; exception now reachable and caught. Correct. ✅ SOUND." That
answer is correct for the unique index's own behavior in isolation, but never asks what happens to the
*transaction the catch is running inside*. The review's method (verify each citation is accurate, verify
each named precedent exists) is sound for catching drafting errors, but is not adversarial about runtime
semantics — it never traced a caller's own write through the shared-transaction path the way this pass
did.

**Fix applied to the story** (see AC1 "The fix" and "Test plan," and the Tasks/Dev Notes sections, all
updated 2026-09-24): isolate `insertAlert`'s write in its own `REQUIRES_NEW` transaction via a
`TransactionTemplate` field, mirroring `GdprErasureService.raiseErasureAlert`'s exact pattern
(`GdprErasureService.java:122,160-161,583-584`), applied uniformly to all 7 callers rather than
special-cased by propagation type — `onCoachSubscriptionOrphaned`'s own Javadoc already documents this
listener is "not connection-pool-constrained the way `GdprErasureService`'s is," so the extra nested
`REQUIRES_NEW` for the 2 already-isolated callers costs one brief extra connection acquisition and is not
the pool-exhaustion concern `GdprErasureService.markFailed` had to specifically design around
(`GdprErasureService.java:588-591`). The test plan now requires a concurrency test against a
`REQUIRED`-propagation caller specifically (`ReviewFlagService.flag()`/`ReviewFlaggedEvent`, not one of
the 2 already-safe `REQUIRES_NEW` events), asserting both racers' own primary writes survive — not just
that no exception escapes — plus a second mutation-check (remove the `REQUIRES_NEW` isolation, keep
`saveAndFlush`) proving that specific property is load-bearing, not decorative.

---

## Citation accuracy (prior review's own audit — independently re-verified, stands)

Every citation the prior review checked was re-verified directly against current `master`
(`ee719dc2`, post-#227-merge and post-6-Dependabot-merges) and found accurate. None of the touched
Dependabot bumps (bouncycastle, hibernate-envers, wiremock-spring-boot, aws-sdk bom, instancio-core,
github_actions/dawidd6) touch any of the files this story cites, so line numbers are unchanged from what
the story's own Dev Notes flagged as needing re-verification post-merge:

- `AdminAlertEventListener.java`: `insertAlert` two-arg overload `:121-123`, four-arg `:125-144`, `save()`
  call `:138`, `catch (DataIntegrityViolationException e)` `:140`. `onMessageHeldForReview`'s Javadoc
  (`:53-54` region) confirmed to state the flush-timing hazard in exactly the terms the story quotes.
  Confirmed 7 handlers exist with the propagation split the story claims (5 `REQUIRED`, 2
  `REQUIRES_NEW` — `onMessageHeldForReview`, `onCoachSubscriptionOrphaned`).
- `AdminAlert.java`: `@GeneratedValue(strategy = GenerationType.UUID)` at `:29` (field itself at `:31`;
  the prior review's `:29-30` range is imprecise but not misleading — `GenerationType.UUID` is at `:29`
  as claimed).
- `GdprErasureService.java`: `saveAndFlush` at `:637` inside `insertErasureAlertIfAbsent` (`:624-641`)
  confirmed exact.
- `V138__baseline_schema.sql:3166`: `admin_alerts_unique_open_per_ref` unique index definition confirmed
  verbatim.
- `StripeWebhookService.java`: `LIVE_SUBSCRIPTION_STATUSES` `:59`, `SUBSCRIBE_RACE_GRACE_WINDOW` `:69`,
  `maybeAlertOrphanedLiveSubscription` `:215-248` (status check `:217`, customer lookup `:223`, coach
  resolution `:231`, grace window `:238-244`, event publish `:247`, `catch (Exception e)` `:248-250`),
  `handleInvoicePaymentFailed` `:269-288` confirmed exact, including the "no orphan check at all" claim.
- `SubscriptionService.java`: `handleInvoicePaymentFailed` `:711-728`, coach lookup `:712`, player lookup
  `:722` confirmed exact.
- `AdminAlertType.java`: `SUBSCRIPTION_ORPHANED` at `:11` confirmed.
- `AdminAlertEventListenerTest.java`: 7 `@Test` methods confirmed — 6 assert `verify(adminAlertRepository)
  .save(...)`, 1 (`duplicateEvent_skipsInsert`) asserts `never()`; the prior review's "six existing unit
  tests" framing is accurate for the assertions that need mechanical updating.
- `stripe-java` pinned at `28.4.0` in `pom.xml`, `Subscription.getCustomer()`/`Invoice.getCustomer()`
  signature claims not independently re-decompiled this pass but no reason to doubt the prior story's own
  `javap` verification (unrelated to this review's scope).

---

## Disposition — 2026-09-24, applied to the story

B1 has been corrected directly in
`skillars-deferred-134-admin-alert-flush-fix-and-invoice-orphan-alerting.md`:
- **AC1 "The fix"**: rewritten to require `TransactionTemplate`-based `REQUIRES_NEW` isolation around the
  `saveAndFlush`/`catch` block, applied to all 7 callers, with the reasoning above inlined so a future
  reader doesn't have to re-derive it.
- **AC1 "Test plan"**: rewritten to require the concurrency IT target a `REQUIRED`-propagation caller
  (`ReviewFlagService.flag()`) specifically, to assert both racers' own primary writes survive (not just
  "no exception escapes"), and to add a second mutation-check proving the `REQUIRES_NEW` isolation itself
  is load-bearing.
- **Tasks / Dev Notes**: updated to reference the corrected fix shape and point at the right
  `GdprErasureService` lines (`:122,160-161,583-591` in addition to `:624-641`).

AC2, AC3, and the citation work underlying both ACs required no changes — independently re-verified
against current `master` and found sound. Story status remains `ready-for-dev`. No further review pass
requested at this time.

---

## Superseded prior review (2026-09-24, this same file, method note only)

An earlier pass on this file recorded a "READY FOR DEVELOPMENT, no false positives, no missed corner
cases" verdict based on a citation-accuracy audit alone (23/23 citations verified exact, which is true and
is preserved above). It did not reproduce or trace the transactional consequences of its own corner-case
question about concurrent `saveAndFlush` failures, which is how B1 went unreported. Citation-accuracy
verification is necessary but not sufficient for a story whose entire AC1 is a transaction-semantics fix;
that class of story needs the semantics traced, not just the line numbers confirmed.
