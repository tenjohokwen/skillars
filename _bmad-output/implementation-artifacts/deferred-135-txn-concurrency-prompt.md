# Transaction & Concurrency Audit — Deferred-135

**Context:** Focus on transaction boundaries, lock safety, race conditions. Ignore false positives (stated tradeoffs, graceful degradation paths, unlikely scenarios).

**Diff Location:** `_bmad-output/implementation-artifacts/deferred-135-review-diff.patch`

**Story Context:** 
- **AC1:** GdprErasureService alert-catch fix — moving catch block outside REQUIRES_NEW boundary (identical bug shape to skillars-deferred-134 AC1)
- **AC2:** New Stripe reconciliation scheduler (StripeSubscriptionReconciliationScheduler with @SchedulerLock)
- **AC3:** CoachReviewRepository.findByIdForUpdate → findByIdForUpdateNoWait + PessimisticLockRetryer conversion (5 call sites)

## Checklist

Apply these checks to the diff:

1. **Catch block placement vs REQUIRES_NEW boundary**
   - Is the catch inside or outside the transaction boundary?
   - If inside, does the exception propagate correctly to the caller?

2. **Lock acquisition order**
   - When taking multiple locks (e.g., row lock + alert dedup), what's the order?
   - Any deadlock hazard with other code paths?

3. **NOWAIT + PessimisticLockRetryer pattern**
   - All call sites using findByIdForUpdateNoWait also use PessimisticLockRetryer?
   - Retry budget documented in Javadoc?

4. **Scheduler lock coverage**
   - StripeSubscriptionReconciliationScheduler has @SchedulerLock?
   - lockAtMostFor/lockAtLeastFor sized from worst-case arithmetic (documented in Javadoc)?

5. **TOCTOU / stale-write risks**
   - Any read → decision → write sequence without re-check before write?
   - Especially: batch load all subscriptions, then per-item reconciliation without re-fetch?

6. **Concurrency test coverage**
   - AC1 (GdprErasureService alert race): GdprErasureServiceConcurrencyIT exists? Tests concurrent raises to same alert slot?
   - AC3 (ReviewModerationService NOWAIT): ReviewModerationServiceConcurrencyIT exists? Tests lock contention?

---

**Output format (Markdown):**
```
### [SEVERITY] FileName:line — One-line title
**Check:** #N <check name>
**Scenario:** Concrete race scenario (if X, then Y while Z, result is corrupt)
**Fix:** Specific code change needed
```

Severity:
- **Critical** — Data corruption or lost work in production today
- **High** — Real concurrency bug, needs timing window to trigger
- **Medium** — Latent risk, only breaks at scale
- **Low** — Best-practice gap

**Avoid false positives:**
- Ignore unlikely scenarios (e.g., "two simultaneous erasures for same request" if requestId is unique per operation)
- Don't flag if code already has the fix (re-check before write, explicit exception propagation, etc.)
- Note stated tradeoffs in Javadoc (e.g., "Accepted tradeoff: ...")

---

**To run this audit:**
1. Read the diff
2. Check each item above
3. Paste findings in format above
4. End with: `Checked: X schedulers, Y transactional methods, Z entities. Findings: A critical, B high, C medium, D low.`
