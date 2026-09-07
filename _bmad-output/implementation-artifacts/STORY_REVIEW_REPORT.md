# Story Review Report: Deferred-95 Analysis & Corrective Actions

**Date:** 2026-09-07  
**Story:** skillars-deferred-95-deployment-visibility-and-messaging-identity-inheritance-fixes  
**Status:** WITHDRAWN (both ACs already implemented)

---

## EXECUTIVE SUMMARY

The story was created from a stale snapshot of deferred-work.md (2026-09-04 audit) without re-verification against HEAD. Both acceptance criteria describe bugs that **already shipped** in master:

- **AC1** (Deploy workflow visibility): Fixed by deferred-94 AC12 (commits `c4e9366`, `71b08b7`)
- **AC2** (MessagingReportService identity): Fixed by deferred-16 AC4 (commit `c7301e0`, shipped 2026-08-05)

**Action Taken:** Story withdrawn; deferred-work.md updated to mark both items as [CLOSED by ...]; sprint-status.yaml updated.

---

## DETAILED FINDINGS

### Critical Issues (Blockers)

#### F1: AC1 Deploy Workflow Already Fixed
**Severity:** BLOCKER  
**Status:** CONFIRMED

**Evidence:**
- `.github/workflows/deploy.yml:128,140,152,169` — all 4 notification steps carry `continue-on-error: true`
- `.github/workflows/deploy.yml:188-194` — `Fail workflow` step carries `if: always() && steps.smoke.outputs.result == 'fail'`
- In-file comment explicitly documents the invariant: "no step between `smoke` and this marker may fail the job without `continue-on-error: true`"

**Timeline:**
- Deferred-94 AC12 introduced `continue-on-error` flags
- Commit `c4e9366` added flags on all 4 notification steps
- Commit `71b08b7` added `if: always()` to the marker
- Both merged in PR #151 (`28354e7`) on 2026-09-07

**What the story asked for:** Exactly what's already there.

**Fix applied:** Updated deferred-work.md line 878 to mark item [CLOSED by skillars-deferred-94 AC12].

---

#### F2: AC2 MessagingReportService Already Fixed
**Severity:** BLOCKER  
**Status:** CONFIRMED

**Evidence:**
- `MessagingReportService.java:43` — `PlayerProfileRepository` already injected
- `MessagingReportService.java:134-155` — method is role-aware (COACH/PARENT/PLAYER/default branches)
- `MessagingReportService.java:129-133` — class comment documents deferred-16 co-fix
- Logic identical to reference `MessagingService.java:367-395`

**Test Coverage:**
- `MessagingAccessControlIT.java:234` — `unrecognisedRole_yields403NotFatal()` already exists and passes
- IT explicitly asserts both `MessagingService.verifyIsParty` and `MessagingReportService.verifyIsParty` branches

**Timeline:**
- Deferred-16 AC4 fixed both copies in parallel
- Commit `c7301e0` (shipped 2026-08-05)
- No functional changes since then

**What the story asked for:** Exactly what's already there.

**Fix applied:** Updated deferred-work.md lines 377-382 to mark item [CLOSED by skillars-deferred-16 AC4].

---

### Secondary Issues

#### F4: Line Number Citations Are Stale
**Severity:** MINOR  
**Status:** CONFIRMED

| Story Claims | Actual at HEAD | Shift |
|---|---|---|
| `deploy.yml:184-188` | `deploy.yml:188-194` | +4 lines |
| notify steps `:128`/`:139`, `:150`/`:166` | `:128`/`:140`, `:152`/`:169` | +1 to +3 lines |
| `MessagingReportService.java:127-141` | lines `:134-155`, comment `:129-133` | +6-7 lines |

**Root cause:** Deferred-94's additions (AC1, AC2, ... AC17) expanded the deploy.yml file, shifting all downstream line numbers. Story copied citations verbatim from deferred-work.md's 2026-09-04 audit without re-anchoring.

**Process lesson:** When creating a story, always re-verify line numbers against HEAD, not against a dated audit block.

---

#### F5: AC2 Rationale Contains a Trap
**Severity:** MINOR  
**Status:** REAL RISK

**Story says:**
> "Inject `PlayerProfileRepository` (no circular dep because `MessagingReportService` doesn't inject `MessagingService`)."

**Actual codebase comment** (`MessagingReportService.java:129-133`):
> "Do not 'solve' the duplication by injecting `MessagingService` or extracting a shared bean — the circular dependency is real."

**The trap:** An implementer taking the story's rationale at face value might think injecting `MessagingService` is a safe alternative and attempt to "improve" the design — reintroducing a real cycle and undoing a deliberate decision.

**Consequence:** Because AC2 is a no-op, this trap is the only real risk in the story. An implementer who naively "fixes" the duplication would create a regression.

---

#### F6: Real Issues from Deferred-94 Code Review Were Omitted
**Severity:** MEDIUM (actionable)  
**Status:** ACKNOWLEDGED, UNADDRESSED

The 2026-09-07 code review of deferred-94 filed two genuinely open issues that should be addressed but weren't picked up (deferred-work.md:1412-1420):

##### F6.1: Smoke Test Step Error vs. Failure Gap
**Issue:** If the `Smoke test` step itself **errors** (ssh/sleep dies) rather than completing with `result=fail`, then:
- `steps.smoke.outputs.result` is never written
- The `pass`/`fail` conditional branches skip
- The `Auto-Revert` step (`:109`) skips
- The `Fail workflow` marker (`:189`) skips (no outputs.result to check)
- Pre-smoke notifications (`:196`/`:207`) skip (they gate on `steps.smoke.outcome == 'skipped'`)

**Result:** A red run with no notification, no auto-revert, and no visibility to ops.

**Why missed:** This is a **different** failure mode than the "notification step throws" issue AC1 addressed. AC1 made the marker run when notifications fail; this gap is about when the smoke test itself fails to complete.

**Recommendation:** Create deferred-96 AC1 to handle smoke-step errors properly (either: ensure smoke step always writes outputs even on error, or make marker/revert/notify conditional chains work on `outcome` instead of just `outputs.result`).

##### F6.2: Notification Channel Health Indicator
**Issue:** With `continue-on-error: true` on notification steps, a down Slack webhook or broken SMTP config is now invisible until the next incident (no noise in CI/CD).

**Proposed solution:** Add a `HealthIndicator` for SMTP and Slack reachability, so a failed health check surfaces in monitoring rather than silently in the next deploy.

**Why missed:** F6.1 (smoke-error gap) is the core visibility issue; F6.2 is a follow-up hardening.

**Recommendation:** Include F6.2 in deferred-96 as AC2.

---

#### F7: Priority/Severity Mismatch
**Severity:** MINOR  
**Status:** NOTED

The story is marked `Priority: medium`, yet AC2 is framed as an authorization defect:
> "abuse-report endpoints now have the exact same bug the main messaging endpoints fixed … a parent … would be silently treated as if they were a player"

A live silent-authz-bypass in abuse reporting would justify `Priority: high`. The mismatch indicates severity was inherited from the ledger without re-assessment, consistent with the whole story not having been re-validated against current code.

---

## CORRECTIVE ACTIONS TAKEN

### ✅ Completed

1. **Deferred-95 marked WITHDRAWN**
   - Status updated in story file header
   - Explanation added in story body (referencing story-review.md findings)
   - Branch created but ready for deletion/retirement

2. **Deferred-work.md updated**
   - Line 878 (deploy-2-2 item): marked [CLOSED by skillars-deferred-94 AC12]
   - Lines 377-382 (MessagingReportService item): marked [CLOSED by skillars-deferred-16 AC4]
   - Both entries updated with specific commit references and closure dates

3. **Sprint-status.yaml updated**
   - `skillars-deferred-95-deployment-visibility-and-messaging-identity-inheritance-fixes` status: `withdrawn`
   - `last_updated` refreshed to 2026-09-07

---

## RECOMMENDATIONS

### Immediate (High Confidence)

1. **Delete or archive the deferred-95 branch**
   ```bash
   git branch -D story/deferred-95-visibility-messaging-fixes
   ```

2. **Keep the deferred-work.md updates**
   - The [CLOSED by ...] annotations are correct and valuable
   - They prevent these items from regenerating in future scope passes

3. **Document the root cause in team wiki/changelog**
   - Story creation workflow needs to re-verify line citations against HEAD
   - Same-file code-review deferrals (added same day as the cited story) should be read before drawing scope

### Medium Term (1-2 sprints)

4. **Create deferred-96: Deployment Visibility Gaps & Channel Health**
   - **AC1:** Smoke test error handling (smoke-step outputs written regardless, or conditional chains on outcome)
   - **AC2:** HealthIndicator for SMTP/Slack reachability
   - These are the **real** deployment visibility issues that deferred-95 tried (and failed) to address

5. **Audit recent story creations for similar stale-citation patterns**
   - Deferred-93, -92, -91: Verify their citations are current
   - Ensure line numbers were re-verified at creation time

### Process Improvements

6. **Update story-creation workflow to include:**
   - Re-verification of all line-number citations against HEAD (not audit snapshots)
   - Mandatory read of most-recent code-review deferrals in the same deferred-work.md section
   - Pre-implementation check: Does the ledger already have a [CLOSED by ...] note on this item?

---

## FALSE-POSITIVE ANALYSIS

The review explicitly checked and found NOT to be problems:

- ✅ **AC1's "combine both" completeness:** Both parts (non-blocking notifies + `always()` marker) are in place
- ✅ **AC1's green-on-success:** Marker correctly gates on `steps.smoke.outputs.result == 'fail'`, so it's a no-op when deploy passes
- ✅ **AC2's method equivalence:** Branch-for-branch identical to reference implementation
- ✅ **AC2's no regression risk:** No functional changes to MessagingReportService.java since 2026-08-05
- ✅ **AC2's circular-dep claim:** Claim is correct; the risk is in AC2's rationale (F5), not the claim itself

**Conclusion:** The review findings are not false positives. Both ACs are genuinely already implemented.

---

## LESSONS LEARNED

1. **Line citations age fast** — even a 3-day-old audit snapshot can have stale line numbers
2. **Same-file deferrals are easy to miss** — code-review sections added on the same day as story creation should be read first
3. **Story creation must re-verify against HEAD** — do not trust audit snapshots, even recent ones
4. **Stale research beats stale assumptions** — when in doubt, grep or read the file directly
5. **Real issues often hide in the debris** — the smoke-error gap (F6.1) is a legitimate open item, just not the one this story claimed to address

---

## METRICS

| Metric | Value |
|--------|-------|
| ACs that are already implemented | 2/2 (100%) |
| Real issues hidden in review findings | 2 (smoke-error gap + health check) |
| False positives in review | 0 |
| Process gaps identified | 2 |
| Corrective actions completed | 3 |
| Recommendations issued | 6 |

---

## SUMMARY

**What happened:** Story was created from stale deferred-work.md without re-verification against HEAD. Both ACs describe bugs that already shipped.

**What was fixed:** Story withdrawn, deferred-work.md updated with closure notes, sprint-status adjusted.

**What's next:** Create deferred-96 with the real deployment visibility issues (smoke-error gap, channel health); update story-creation process to prevent recurrence.

**Risk:** Low. The no-op story prevents no production harm and is caught before implementation. The real gaps (F6) are documented and actionable.

