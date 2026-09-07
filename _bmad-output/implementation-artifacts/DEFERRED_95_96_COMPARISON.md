# Deferred-95 vs Deferred-96: Lessons Learned & Story Correction

**Date:** 2026-09-07  
**Lesson:** How to properly create stories from deferred-work.md audits

---

## What Happened

### Deferred-95 (WITHDRAWN)
Created from deferred-work.md without re-verification against HEAD. Both ACs described bugs **already fixed** in master.

| AC | Claimed Problem | Actual State | Closed By |
|----|-----------------|--------------|-----------|
| AC1 | Deploy workflow visibility broken | ✅ Fixed | deferred-94 AC12 |
| AC2 | MessagingReportService identity bug | ✅ Fixed | deferred-16 AC4 |

**Result:** 100% no-op story. Would have wasted developer time.

---

### Deferred-96 (NEW, READY-FOR-DEV)
Created by properly analyzing the deferred-94 code review section. Addresses **real, genuinely open issues** that were missed:

| AC | Real Problem | Status | Source |
|----|--------------|--------|--------|
| AC1 | Smoke test errors silently skip failure notifications & auto-revert | OPEN | deferred-94 review, F6.1 |
| AC2 | Notification channels (Slack/SMTP) can be down without ops knowing | OPEN | deferred-94 review, F6.2 |

**Result:** Two substantial, implementable defects with clear paths to closure.

---

## Side-by-Side Comparison

### Deferred-95: What Went Wrong

| Aspect | What Happened | Why |
|--------|---------------|-----|
| **Source** | Copied from deferred-work.md 2026-09-04 audit | Didn't re-verify against HEAD |
| **Line citations** | `deploy.yml:184-188`, `MessagingReportService:127-141` | Stale (deferred-94 added 4 lines, shifted everything) |
| **Pre-implementation check** | None | Assumed ledger items were still open |
| **Code-review deferrals read?** | No | Story created before reading deferred-94's review section |
| **Result** | 2 ACs, both already implemented | 100% no-op, 0% actionable |

### Deferred-96: What's Better

| Aspect | What Changed | Why |
|--------|--------------|-----|
| **Source** | Deferred-94 code review section (2026-09-07, same-day deferrals) | Read the most-recent code-review findings first |
| **Line citations** | Re-verified all 6 line references against HEAD | Checked current state before writing |
| **Pre-implementation check** | Verified neither issue had [CLOSED by ...] annotation | Prevented duplicate scope |
| **Code-review deferrals read?** | Yes, read section 1412-1420 fully | Found the 2 real issues hiding there |
| **Result** | 2 ACs, both genuinely open with clear solutions | 100% actionable, substantial work |

---

## Process Lessons

### What to Do When Creating Stories from Deferred-Work

**❌ DON'T:**
1. Copy line numbers from dated audit blocks without re-checking
2. Assume ledger items are open just because they're listed
3. Skip reading same-file code-review deferrals before drawing scope
4. Trust a 3+ day old audit snapshot

**✅ DO:**
1. **Re-verify every line number against HEAD** before writing the story
   - `deploy.yml:184-188` → Check actual file
   - `MessagingReportService.java:127-141` → Grep current line numbers
2. **Read same-day code-review deferrals** (deferred-work.md sections dated today/yesterday)
   - Often contain discoveries that haven't made it to backlogs yet
   - May contain better-scoped work than the original audit noted
3. **Pre-check the ledger** — does the item already have [CLOSED by ...] or [PICKED UP by ...]?
   - If yes, skip it
   - If no, verify against code before committing
4. **Prefer current code over audit text** — audit annotations can be wrong
   - Read the actual file
   - Trust what you see, not what was written 3 days ago

### Story Creation Checklist (Updated)

Before finalizing a story scoped from deferred-work.md:

- [ ] All line-number citations re-verified against HEAD (not copied from audit)
- [ ] Read same-file, same-day code-review deferrals first
- [ ] Pre-check: Does the ledger show [CLOSED by ...] on this item? If yes, skip.
- [ ] Pre-check: Does the ledger show [PICKED UP by ...]? If yes, verify it's still unclaimed.
- [ ] If citations are stale or premises are false, look for the *real* issue hiding nearby
- [ ] Write the story from scratch (don't copy audit text verbatim)

---

## The Real Issues (Deferred-96)

### AC1: Smoke Test Error Visibility

**The Gap:**
- `.github/workflows/deploy.yml` lines 188-194: failure marker gates on `if: always() && steps.smoke.outputs.result == 'fail'`
- If smoke test **errors** (ssh dies, timeout) instead of completing with result=fail:
  - `outputs.result` is never written
  - Marker skips (no outputs)
  - Auto-revert skips (same reason)
  - Pre-smoke notifications skip
- **Result:** Red run, ops gets no notification

**Why Missed in Deferred-95:**
- We thought the gap was "notification throws pre-empts the marker"
- We didn't read the actual workflow conditionals carefully
- We missed that `steps.smoke.outputs.result` is only written on normal completion

### AC2: Channel Health Visibility

**The Gap:**
- Slack webhooks and SMTP configs can be down without surfacing in CI/CD
- With `continue-on-error: true` on notifications, a failed send is silent
- No health check exists to probe channels proactively

**Why Missed in Deferred-95:**
- We didn't know this was a gap until we read the deferred-94 review section
- The original deferred-work.md audit didn't mention it (it was filed during code review)
- We stopped looking once we thought we'd found the "visibility" issues

---

## Timeline

| Date | Event |
|------|-------|
| 2026-08-28 | Deferred-94 implemented and shipped |
| 2026-09-04 | Deferred-work.md audited (captured deploy-2-2 item) |
| 2026-09-07 | Deferred-94 code review filed two new issues (F6.1, F6.2) |
| 2026-09-07 | Deferred-95 created (incorrectly scoped from 2026-09-04 audit) |
| 2026-09-07 | Story-review caught the errors, story withdrawn |
| 2026-09-07 | Deferred-96 created properly (addresses real F6.1 + F6.2) |

---

## Key Takeaways

1. **Line citations age in hours, not days.** Re-verify every one.
2. **Same-file deferrals are treasure.** Code-review sections often contain better-scoped work than the original audit.
3. **When an audit is stale, look for what's *actually* open.** The real issue is usually hiding nearby.
4. **Trust code over text.** Read the file, not the annotation.
5. **Process matters.** A simple checklist prevents 100% no-op stories.

---

## What Changed Between Stories

### Deferred-95 (Withdrawn)
```
- AC1: Fix deploy workflow visibility (already done by deferred-94)
- AC2: Fix MessagingReportService identity (already done by deferred-16)
Status: No-op, 100% already implemented
```

### Deferred-96 (Ready-for-Dev)
```
- AC1: Handle smoke test errors so failure notifications & auto-revert always run
- AC2: Add HealthIndicator for SMTP/Slack channel reachability
Status: Actionable, substantial work, 2 independent visibility gaps
```

---

## Recommendation

**Keep deferred-95 as-is (withdrawn)** — it's a valuable record of what we learned:
- Shows what stale research looks like
- Documents the false-start we avoided
- Proves that the review process catches these issues

**Proceed with deferred-96** — it contains the real work:
- Addresses genuine production safety gaps
- Well-scoped with clear ACs
- Follows all the improved process lessons

This is a good example of failure → learning → success in real development.

