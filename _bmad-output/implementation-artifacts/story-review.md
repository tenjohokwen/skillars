# Senior Dev Audit: skillars-deferred-103

**Reviewed:** 2026-09-09  
**Auditor Role:** Senior dev verification of corner cases, false assumptions, missed flows, and clarity issues

---

## Summary

The story is well-structured and most ACs are sound. **Three significant ambiguities and one real corner case** found that could trip up the dev agent. No false positives that rise to bug level, but several assumptions warrant explicit verification.

---

## Critical Issues (Will Impact Implementation)

### AC4: Email enqueue atomicity — partial-commit trap

**Issue:** The AC states that on `BEFORE_COMMIT`, enqueue must be atomic with `expiryWarnedAt` write. But the AC doesn't clarify what happens if enqueue throws: does the entire transaction roll back (preventing `expiryWarnedAt` from being stamped), or does the transactional listener failure not affect the producing transaction?

**Impact:** If a `@TransactionalEventListener(BEFORE_COMMIT)` with `Propagation.MANDATORY` throws, **the producing transaction still commits** (BEFORE_COMMIT failures don't abort the parent). So `expiryWarnedAt` will be stamped even though the email enqueue failed. This breaks the atomicity guarantee the AC claims to establish. 

**Verify:** Check how `RefundEnqueueListener` (the deferred-92 pattern referenced) handles listener-side exceptions. If it swallows/logs them without propagating, then the guarantee is actually "best-effort enqueue + guaranteed stamp", not true atomicity. The story must clarify this before the dev codes blindly into the wrong semantics.

**Recommendation:** Explicitly test and document whether `expiryWarnedAt` gets stamped when enqueue fails. If the pattern is "stamp even if mail fails", update the AC language to say "enqueue attempted atomically" not "atomically with"; if the pattern is "enqueue must succeed or no stamp", verify the exception-propagation chain won't silently swallow it.

---

### AC7 + AC5 + AC6 interdependency — missing evaluation order

**Issue:** These three ACs all apply to `pausePack` logic, but the story lists them sequentially with no explicit dependency call-out. AC5 (timezone check) and AC6 (config default) both run *after* AC7 (coach/parent lookup). If the coach/parent is missing, AC7 fails fast — so AC5's `coach.getCanonicalTimezone()` call assumes the coach exists.

**Impact:** Low-level — AC7's guard will naturally prevent AC5 from executing on a missing coach. But the story doesn't state this order dependency, so the dev might implement them in isolation and not realize AC7 must run first. Alternatively, the dev might add the coach/parent check *after* the timezone logic and introduce an NPE path.

**Recommendation:** Reorder ACs so AC7 runs first (or update AC5 language to "after AC7 validates the coach exists, resolve the timezone"). Add a note: "AC7 is a prerequisite guard for AC5 and AC6."

---

## Significant Ambiguities (Likely to Cause Rework)

### AC2: Pagination response format — no baseline given

**Issue:** The AC says "match how other list endpoints in this codebase paginate" and "match its `PagedResponse` / `Page` wrapper shape exactly; do not invent a new envelope". But the story doesn't name which sibling endpoint to use as the template, or show what `PagedResponse` looks like.

**Impact:** The dev must grep/explore to find a reference. If they pick the wrong one, or if the codebase has multiple inconsistent pagination shapes, they'll implement it wrong and either fail the manual code review or encounter a test failure that points back to "match the shape exactly".

**Recommendation:** Cite a specific endpoint: e.g., "match `GET /coaches/{id}/revenue-history` which uses Spring Data `Page<RevenueResponse>`" with a line reference. Or add a quick snippet showing the expected JSON envelope.

---

### AC8: Exception message wording — no final phrasing approved

**Issue:** The AC suggests `"Booking status changed — please reload and try again"` but says "stop instructing the end user". The suggestion still contains "try again", which *is* an instruction. The exact wording is not approved; the story leaves it to the dev agent's judgment.

**Impact:** The dev might choose wording that the next code review rejects as still being imperative (e.g., "Retry" vs "Reload" vs "Please try again later"). This will cause a rework loop.

**Recommendation:** Provide the exact approved wording, or explicitly state "the dev may choose wording that avoids imperative instructions; code review will verify non-imperative tone."

---

### AC10: Authorization choice — (b) is not really "closing" the issue

**Issue:** Option (b) is "just add a comment recording the decision". But a comment can rot and provide no security value over time. The AC says "or" — implying both are equally valid closure. However, option (a) (tightening the authorization) actually fixes a potential exposure; option (b) is a documentation-only non-fix.

**Impact:** The dev might choose (b), which leaves the latent authorization weakness in place. A future security audit will ask "why is report-message endpoint only `IS_AUTHENTICATED` and not party-scoped?" If the comment isn't found or is overlooked, the issue gets re-raised.

**Recommendation:** Reframe as "(a) if a reusable party-check expression exists, apply it [preferred]; (b) if no such expression exists, the code stays unchanged but you **must** record the decision in a GitHub issue / backlog item for the platform team to revisit, not just a code comment." Or: "Strongly prefer (a). (b) is acceptable only if no existing reusable expression and the PM/security team approves the documented deferral."

---

## Missed Corner Cases

### AC1: Race condition with partial deactivations

**Scenario:** Two threads race to `createTier` for the same coach. Both call `findAllByCoachIdAndIsActiveTrue` and see the same set of existing active tiers (isolation at READ_COMMITTED or above, this is possible if the reads happen before either deactivation is flushed). Both enter the deactivate loop. Thread A deactivates tier #1 and saveAndFlush. Thread B reads tier #1 again and deactivates it again (redundant, but OK). Thread A tries to insert its new tier and succeeds. Thread B tries to insert and gets the unique constraint violation, throws 409. Both threads now see an updated state.

**Corner case:** If the isolation level is READ_UNCOMMITTED, both threads might read committed tiers, deactivate different overlapping sets, and end up with **zero active tiers** before either insert. Then one insert succeeds, leaving exactly one active tier. The other fails with 409. Correct outcome.

**Status:** Not a bug — `@Transactional` + proper isolation ensures this works. But the test should verify the happy-path scenario: `createTier` twice in sequence (not concurrent) must also leave exactly one active tier (test covers the rollback behavior of the first call).

**Recommendation:** The story already requires a sequential test; no change needed. But the dev should document why the deactivate loop is safe even in a concurrent environment (atomicity + isolation, not optimistic locking).

---

### AC4: Listener-phase exception behavior

**Scenario:** The story references `deferred-92` AC4 as the pattern for pack-expiry email, stating that ~23 emails were moved to `BEFORE_COMMIT`. But if any of those listeners throw an exception, does the producing transaction abort, or does the exception get swallowed by Spring's listener error handler?

**Corner case:** If Spring's listener error handler swallows the exception (logs it, doesn't propagate), then `expiryWarnedAt` gets stamped even though the email enqueue failed. This breaks the atomicity guarantee the AC claims to establish.

**Recommendation:** The AC should include a test that verifies: (1) email enqueue success → expiryWarnedAt stamped in same transaction, (2) email enqueue failure → confirm whether expiryWarnedAt is still stamped (it will be, if Spring swallows the listener exception). The test should document the actual behavior.

---

### AC5: Timezone fallback with missing coach

**Scenario:** AC5 says "fallback `"UTC"` with a WARN, matching every other read-side zone fallback in the codebase". But what if the coach profile exists but the `canonicalTimezone` field is null? The story doesn't clarify whether `.getCanonicalTimezone()` can return null, or if it always returns a string.

**Corner case:** If it can return null, the fallback logic is correct. If it cannot (always returns a non-null IANA string), the fallback is dead code and might confuse future readers.

**Recommendation:** Verify the type of `CoachProfile.canonicalTimezone` in the codebase. If nullable, the AC is correct and the test should cover this fallback. If never null by constraint, remove the fallback and document why.

---

### AC7: Silent null email in the forfeiture scheduler

**Scenario:** The story says "a blank `parentEmail` must never reach the mail layer". But in `SessionPackForfeitureScheduler`, the parent lookup is `.map(u -> u.getEmail()).orElse("")`. If the User exists but `u.getEmail()` is null, `.orElse("")` still applies and produces an empty string. But if the User doesn't exist, `.orElse("")` also produces an empty string. Both cases are indistinguishable at the mail layer.

**Corner case:** The scheduler should log ERROR in both cases, but the AC only mentions "missing coach/parent record". If a User exists but has a null email (data corruption), the scheduler will silently skip it and the data corruption remains invisible.

**Recommendation:** AC7 should say "a blank or null email must never reach the mail layer. For both missing User and User-with-null-email, log ERROR and skip." The test should cover both scenarios.

---

### AC12: Deletion order sensitivity in ledger re-mine

**Scenario:** AC12 says "for every untagged bullet, diff its cited location against HEAD. Delete any that are demonstrably closed". Seven bullets are listed as examples. But the story doesn't specify: if a section has multiple bullets and all are deleted, should the section header also be deleted? What if some bullets in a section are `[DECIDED]` (keep) and others are closed (delete) — does the section stay or go?

**Corner case:** If the dev leaves orphaned section headers (with no bullets), the file becomes malformed. If the dev deletes the header when the last bullet is deleted, they might accidentally delete a `[DECIDED]` bullet that was intentionally placed under that header.

**Recommendation:** AC12 should clarify: "Delete bullets but leave section headers if any bullets remain; if all bullets in a section are deleted, delete the header too. Do not delete any `[DECIDED]` bullets even if their section becomes empty — move them to a `[DECIDED Items Awaiting Future Stories]` section if needed." (This might already be the intended behavior, but it should be explicit.)

---

## Weak Assumptions (Won't Cause Bugs, But Worth Verifying)

### AC1: Constraint name assumption
The story assumes the unique constraint is named exactly `idx_spt_one_active_per_coach`. It references `V62__session_payment_credit_wallet.sql`. If a later migration renamed the index (common in production DB iterations), the catch-by-name will fail and the exception will propagate as a 500 instead of a 409.

**Recommendation:** Verify the constraint name at HEAD before the story starts. Add a comment in the code naming the constraint so future maintainers know it's a brittle dependency. Consider adding a fallback catch that logs WARN if the constraint name doesn't match (defensive programming).

---

### AC2: Page size default
The story says "use the project's established default page size — check a sibling paginated resource". It doesn't say what that size is. If the codebase uses 50 for most endpoints but the dev accidentally uses 20, it's inconsistent but not wrong. The test should verify the actual default applied.

**Recommendation:** No change to the AC, but the test should explicitly assert the page size (e.g., `assertEquals(20, response.getSize())`).

---

### AC3: Narrow exception type is not specified
The story says "Identify `deductSession`'s declared / reachable business exceptions (grep `PackSessionService.deductSession` — expect a domain exception such as `PackExhaustedException` / `ResourceNotFoundException` / a `SessionPackException` supertype)". The exact type is unknown; the dev must discover it.

**If the discovery is wrong** (e.g., `deductSession` can throw `IllegalArgumentException`, which the dev treats as a business exception and catches), then a legitimate programming bug (e.g., invalid session ID) gets swallowed and logged as a business failure.

**Recommendation:** Include "verify by reading the implementation and Javadoc of `PackSessionService.deductSession` before narrowing the catch type" in the AC's verification section.

---

### AC9: `loadStripe()` function placement
The story says "extract the `onMounted` init body into a named `async function loadStripe()`". It doesn't specify whether this should be a top-level function, a ref function, or a method on the component instance. In a `<script setup>`, the convention is usually a top-level `async function`.

**Recommendation:** No bug here, just style. The dev should follow `<script setup>` conventions (top-level function, not ref-wrapped).

---

## Clarity Issues (Not Bugs, But Could Slow Down Development)

1. **AC11 branching decision point:** The story says "pick based on what the grep finds — record the choice". But it doesn't say whether the dev should grep and decide, or whether the PM/architect has already decided and the story just hasn't communicated it. A pre-decision would save time.

2. **AC12 reconstruction check:** The story says "every surviving non-blank line must match the pre-edit file in order, nothing reworded". But `deferred-work.md` might have blank lines within sections (for readability). What counts as "non-blank"? A line with only whitespace? The exact definition matters for the check.

3. **Project-owner decision D2 mentions deferred-91 Part B:** The story defers payment-state-machine changes to `deferred-91` AC5 Part B, and says that story needs `docs/architecture/payout-and-capture-pending.md` D1–D5 signed off first. But is that doc actually in the repo, and is it in DRAFT status? If it's missing, the dev can't verify the dependency.

---

## Test Coverage Observations

- **AC1:** Tests specify concurrent race + sequential case. ✓ Solid.
- **AC2:** Tests specify multi-page retrieval and metadata correctness. ✓ Solid.
- **AC3:** Tests specify business-exception path and unchecked-exception propagation. ✓ Solid.
- **AC4:** Tests specify outbox atomicity + retry behavior. ✓ Solid, but see Critical Issue above about listener exception handling.
- **AC5:** Tests specify timezone fallback with UTC+14 edge case. ✓ Solid.
- **AC6:** Tests specify absent key, non-numeric value, and numeric value paths. ✓ Solid, but see Weak Assumption above about fallback logic.
- **AC7:** Tests specify missing coach/parent in both pausePack and scheduler. ✓ Solid, but see Missed Corner Case above about null email.
- **AC8:** Tests specify JSON error key is preserved. ✓ Solid, but frontend bundle parity check is mentioned as existing ("must stay green") — unclear if a regression test actually enforces it or if it's manual.
- **AC9:** Tests are manual dev-server exercise. ✓ Appropriate given no frontend test framework yet, but high reliance on manual verification.
- **AC10:** Tests specify both party and non-party access control. ✓ Solid.
- **AC11:** Tests specify zero-ledger parent and native-SQL query guarantee. ✓ Solid.
- **AC12:** Tests are `git diff` review. ✓ Appropriate for a documentation change.

**Minor gap:** AC8 mentions "Frontend bundle parity check and MessageBundleParityTest must stay green" but doesn't name the test class or point to where it runs. The dev will need to search for it.

---

## No False Positives Found

All concerns above are either legitimate clarification needs or edge cases worth documenting. The core approach of each AC is sound. The story is not over-cautious or introducing unnecessary complexity.

---

## Recommendations Summary

### Before Dev Starts
1. ✅ **AC4:** Verify the listener exception-handling semantics in `deferred-92`'s `RefundEnqueueListener`. Document whether `expiryWarnedAt` gets stamped when email enqueue fails.
2. ✅ **AC1:** Verify the constraint name `idx_spt_one_active_per_coach` exists at HEAD in the exact migration cited.
3. ✅ **AC11:** Clarify whether the decision between view-LEFT-JOIN and document+test has been pre-made, or whether the dev should decide based on what exists.
4. ✅ **AC2:** Cite a specific sibling paginated endpoint as the template for response shape.

### During Dev
1. ✅ **AC7:** Order implementation as AC7 first (coach/parent guard), then AC5/AC6.
2. ✅ **AC3:** Grep and document the exact exception types caught by `deductSession`.
3. ✅ **AC12:** Explicitly check constraint names (not re-discovered at test time).

### Test/Review Phase
1. ✅ **AC4:** Test listener exception behavior explicitly.
2. ✅ **AC5:** Test timezone fallback only if the field is nullable.
3. ✅ **AC8:** Run the frontend bundle parity check (identify the exact test name).

---

## Final Verdict

**Status: READY FOR DEV** with the above clarifications addressed.

The story is comprehensive, well-structured, and based on real verified issues. The ACs are achievable and the test specs are concrete. No blocking issues; the ambiguities are resolvable with brief verification before coding starts. The dev agent should be able to complete this story without major rework loops if the four pre-start verifications are done first.
