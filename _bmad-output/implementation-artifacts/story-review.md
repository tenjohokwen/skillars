# Senior Dev Audit: skillars-deferred-114 Story Review

**Review Date:** 2026-09-16  
**Reviewer:** Senior Engineer  
**Status:** Audit complete — findings documented below

---

## Summary

Story is **well-scoped and implementable**. The design is sound, testing strategy is clear, and corner cases are mostly addressed. **5 findings below** (3 clarifications needed, 2 minor gaps); none are false positives. **Recommend approve with these clarifications merged before dev starts.**

---

## Findings

### 1. **AC3 — Verify complete caller inventory for `resolvePlayerTierKey`**

**Category:** Completeness  
**Severity:** Medium (low practical risk, high clarity benefit)

**Finding:**  
Story identifies `VideoResource.java:123-124` (storage + bandwidth quota calls, fires twice per request) as the primary caller but doesn't explicitly verify this is the *only* caller of `resolvePlayerTierKey` or list any other known callers. The story correctly documents the double-fire, but a dev implementing AC3 should verify there are no other hot-path callers that would also fire the counter unexpectedly.

**Why it matters:**  
If AC3's new counter fires in an unexpected location (e.g., a background job calling quota check for every player weekly), the metric becomes uninterpretable without documentation. Visible in a dashboard as "fired 1000x overnight" and someone assumes it's a production data-integrity signal, when it's actually a batch job.

**Fix:**  
Before starting AC3 implementation, grep for all callers of `resolvePlayerTierKey` and `QuotaConfigService.resolve*Tier*` — document in the story's Dev Notes exactly which callers exist and which ones fire the counter (if any beyond VideoResource). If there are others, either:
- Narrow the counter to VideoResource's call path specifically (requires refactoring), or  
- Update the metric description + dashboard to explicitly note the double-fire *and* list all known callers

**Not a blocker:** The story's documented behavior (fires 2x per VideoResource request) is correct and defensive. Just need to confirm no *other* unexpected callers exist.

---

### 2. **AC5 — Response schema and test-email outcome distinction need documentation**

**Category:** Clarity  
**Severity:** Low

**Finding:**  
Story specifies "structured JSON result: the account-check outcome, the test-send outcome, and an overall ready/not-ready verdict" but doesn't document:
1. Exact field names / structure (e.g., `{ accountStatus: {...}, sendTestResult: {...}, isReady: bool }`)
2. How to distinguish between "SES account is fully configured but email send failed" vs "account check itself threw an exception (missing ses:GetAccount permission)"

The story says "distinguishing an `SdkException` here (missing `ses:GetAccount`) in the response from a send failure below" but the response structure should make this explicit.

**Why it matters:**  
An admin running the preflight tool needs to know:
- Is the problem "account setup incomplete" (getAccount failed) → fix IAM permissions
- Or "account is fine, but sending broke" (send failed) → check email format / rate limits / recipient validation
- Or "both passed" (ready to proceed)

Without a clear response schema, the dev will either guess or create ambiguous output.

**Fix:**  
Document the response schema in AC5 as part of the story (before or after the story). Example structure:
```json
{
  "accountStatus": {
    "sendingEnabled": bool,
    "productionAccessEnabled": bool,
    "enforcementStatus": "str",
    "error": null | "SdkException message if getAccount threw"
  },
  "testSendResult": {
    "envelopeStatus": "SENT" | "FAILED",
    "errorDetails": null | "error message if send failed"
  },
  "isReady": bool,
  "summary": "human-readable one-liner"
}
```

Add this to the story's AC5 section under "Returns one structured JSON result" — this ensures the implementation matches the intent and the runbook can reference exact field names.

---

### 3. **AC1 — Advisory lock doesn't prevent silent recipient-list overwrites; document or guard**

**Category:** Edge case (pre-existing, not introduced by this AC)  
**Severity:** Medium (low practical risk, high clarity benefit)

**Finding:**  
Story correctly notes in the Design Consideration that `applyDeliveryFlags` rebuilds `EnvelopeEntity.recipients` from the **calling envelope's own** recipient list, not merged with persisted state. This means:

```
First call: sendEmailSync(sendId=X, recipients=[alice@test.com])
            → persists EnvelopeEntity with recipients=[alice@test.com]

Second call: sendEmailSync(sendId=X, recipients=[bob@test.com])  (different recipient, same sendId — caller misuse)
            → acquires advisory lock, finds existing row
            → calls applyDeliveryFlags([bob@test.com]) 
            → **overwrites recipients to [bob@test.com], silently losing alice**
```

The story says "This is pre-existing behavior once the race window closes rather than something this AC needs to newly guard against" — **correct, but this AC's new advisory lock actually *widens* the window where this can happen silently**, because the second call now blocks, waits for the first to commit, then proceeds to the update instead of throwing on DB unique constraint.

**Why it matters:**  
Today (without advisory lock): two calls racing with different recipients throws → operator notices → caller misuse is caught.  
After AC1: two calls race, second blocks, first commits, second silently overwrites → no error, audit trail is lost unless someone manually inspects the DB.

**Status:** This is **not a bug in AC1's design** (the design is correct; caller misuse should not send duplicate emails), but it's a **behavior change** that should be tested and documented explicitly.

**Fix:**  
1. AC1's test suite should include: two calls with **same** sendId but **different** recipients (caller misuse scenario) — assert the second call blocks, completes successfully (no throw), and the final row contains the **second** call's recipient list. Document this behavior in a code comment: "Silent overwrite of recipient list on re-call with same sendId but different recipients — acceptable since this indicates caller misuse, not legitimate retry."
2. Update AC1's Dev Notes to note: "Advisory lock changes duplicate-sendId behavior from 'throws on unique constraint' to 'updates silently' — verify this is acceptable for your use case before proceeding."

Not a blocker, but this test + comment are required before the story ships.

---

### 4. **AC5 — Isolated circuit breaker initialization and test-send logging need clarity**

**Category:** Implementation detail  
**Severity:** Low

**Finding:**  
Two sub-issues in AC5:

**4a. Isolated circuit breaker ("sesPreflightService"):**  
Story correctly requires a 3-arg `EmailTemplate` constructor with isolated breaker name, and explains why (don't trip shared "emailService" breaker during preflight failure). But doesn't specify:
- What if this is the first time the preflight endpoint is called, and the "sesPreflightService" circuit breaker hasn't been initialized yet? (It will be, via CircuitBreakerFactory.create(), but not explicitly called out.)
- If CircuitBreakerFactory is injected or retrieved statically — should verify this is consistent with existing EmailTemplate usage.

**4b. Test-send logging:**  
The preflight endpoint sends a real email via `MailManager.sendEmailSync`. This email will go through the normal MailService/SES SDK logging pipeline. Is this desirable? The email logs will show "test email sent to admin@example.com" which is fine, but should the test email be marked/tagged distinctly in logs (e.g., with a special EmailTemplate template name or a logging context var) so an operator can filter them out of production dashboards?

**Why it matters:**  
4a: Not a functional issue (CircuitBreakerFactory handles lazy init), but if the dev isn't familiar with that pattern, they might try to eagerly initialize the breaker or worry it's missing.  
4b: If preflight tests fire real MailService logging, they could pollute error dashboards / false-alert integrations ("New email recipient: preflight@test.com").

**Fix:**  
4a: Add a one-line Dev Note confirming CircuitBreakerFactory.create() lazy-initializes the breaker on first use.  
4b: Document that preflight test emails go through normal logging (this is correct behavior) and note in the runbook that operators should exclude "sesPreflightService" template name or use the isolated breaker name for filtering if needed.

---

### 5. **AC4 — Ledger annotation location not specified**

**Category:** Process / Clarity  
**Severity:** Low

**Finding:**  
AC4 says "The only output of this AC is a ledger annotation re-confirming the decision, dated to this story, so a future audit doesn't re-litigate it from scratch."  
AC6 (Ledger hygiene) says "Retag 'No transactional consistency tier/quota lookup' as `[DECIDED 2026-09-16 (skillars-deferred-114): accepted tradeoff, re-confirmed — see AC4]`."

This clearly goes in `deferred-work.md`, but AC4 should also specify **whether** to add an inline code comment in `QuotaConfigService.java` itself. Currently, someone reading that file has no hint that the check-then-use race is a *known, accepted tradeoff* rather than a bug waiting for AC4 to come along and add one.

**Why it matters:**  
Without a code comment, the next person who touches that method might "fix" the race by adding a read lock or inline check, unaware it's deliberately left as-is.

**Fix:**  
Clarify in AC4: "Ledger annotation goes in `deferred-work.md`'s AC6 section. **Additionally**, add a one-line code comment to `QuotaConfigService.resolvePlayerTierKey` (around line 82) like: `// Note: tier lookup and quota enforcement are non-transactional — intentional tradeoff (skillars-deferred-114 AC4)`."

---

## Absence of False Positives

Reviewed the existing audit findings mentioned in the changelog (lines 524-537). All four prior Low findings were justified and are now incorporated:
1. ✓ Missing caller (NotificationEmailOutboxHandler) — now in Files to Read  
2. ✓ AC1 rollback test case added  
3. ✓ AC1 hashtext() collision caveat documented  
4. ✓ AC5 Jakarta Validation + @Observed requirements added  

No pushback needed on any of these.

---

## Unaddressed but Acceptable

**These are known, documented decisions — not oversights:**

1. **AC1 Hash collision odds:** Story correctly asserts they're negligible at this codebase's concurrency. ✓
2. **AC3 double-fire per request:** Story documents this explicitly in the Files to Read and verification. ✓
3. **AC2 diagnostics-only scope:** Owner decision stands. ✓
4. **AC5 admin-only endpoint:** @PreAuthorize/403 enforcement required. ✓
5. **Connection pool / lock exhaustion from held advisory locks:** Not a practical concern at skillars concurrency levels; acceptable to leave as unaddressed. ✓

---

## Recommendation

**Approve story for dev with these 5 findings incorporated:**
1. Before AC3 dev starts: verify `resolvePlayerTierKey` caller inventory (grep).
2. Add response schema documentation to AC5 before implementation starts.
3. Add test case to AC1 for silent recipient-list overwrite (same sendId, different recipients) + code comment.
4. Add clarifications to AC4 and AC5 Dev Notes (circuit breaker init, test-send logging filter note, code comment in QuotaConfigService).
5. Update AC5 response schema doc.

**Risk level:** Low. Core designs (advisory lock, isolated circuit breaker, audit trail logging) are sound. Findings are process/clarity, not blockers.

---

## Verification After Implementation

Recommend re-checking:
- AC1 mutation test actually fails when advisory lock is removed
- AC3 counter fires exactly 2x per VideoResource.java:123-124 call (and nowhere else unexpectedly)
- AC5 preflight endpoint is unreachable when SES is not the active transport (404 or spring-error response is acceptable)

All are called out in the story's verification checklist already.
