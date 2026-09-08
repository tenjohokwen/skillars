# skillars-deferred-101: Story Audit Review

**Audit Date:** 2026-09-08  
**Reviewer:** Senior Dev Audit  
**Status:** Ready for Implementation

---

## Summary

The story is **well-structured and mostly correct**. No false positives detected. The "Verified at HEAD" discipline is solid, and the scope is properly bounded. However, **three critical assumptions** require explicit verification before implementation, and **two behavior changes** need caller-impact awareness.

---

## Critical Assumptions Requiring Verification

### 🔴 AC4: `onBookingCancelledByAdmin` may break strike-then-refund ordering

**Issue:** Moving from `AFTER_COMMIT` to `BEFORE_COMMIT` changes *when* the refund enqueue happens relative to the strike suspension write. The AC acknowledges this ("Watch the strike-then-refund ordering") and references javadoc `:107-121`, but **the verification is conditional on the dev**, not pre-verified.

**Risk:** If the strike suspension's visibility in the transaction is a genuine precondition (not just "should be ordered before for clarity"), moving the refund enqueue earlier could cause:
- Refund enqueued *before* the suspension is visible in the same transaction
- Downstream refund-processor assumes strike is already applied
- Silent data inconsistency (suspension is written *after* refund enqueue commits if they're in the same transaction)

**Mitigation:** The AC's fallback is correct — "If any of the four genuinely depends on AFTER_COMMIT semantics... it does NOT move." But the story must **verify this explicitly in Dev Agent Record**:
- Read `onBookingCancelledByAdmin` javadoc and the `ReliabilityStrikeService` interaction
- If the refund *must* see a committed strike, do not move this listener; record why in the Dev Agent Record
- If moving is safe, add a comment citing the verified ordering (e.g., "Both the strike and refund enqueue in the same tx; order doesn't matter since both commit atomically")

**Test:** The AC's mutation test (force business rollback) covers this, but only if the dev explicitly runs it for `onBookingCancelledByAdmin` (not just one of the four).

---

### 🔴 AC8: `createTemplate()` throwing is a breaking behavior change

**Issue:** The store function currently **swallows errors silently**. Making it throw is a behavior change. The AC assumes callers will handle because "other mutating actions throw," but this assumes:
1. All existing callers already expect exceptions, OR
2. All callers are in the codebase and visible to grep

**Risk:** If `createTemplate()` is called from a place that never had error handling (e.g., auto-save logic, background sync), throwing will cause unhandled promise rejections or crashes.

**Mitigation:**
- Grep for all callers of `createTemplate` (not just `SessionTemplateVault.vue` / `SessionBuilderPage.vue`) — use `sessionStore.createTemplate` to find them
- For each caller, verify it has a `.catch()` handler or wraps in `try/catch`
- If any caller lacks error handling, either:
  - Add the error handling to the caller before this AC ships, OR
  - Note in Dev Agent Record that callers were verified to already handle

**Test:** The AC says "code-read confirming the five actions now match" — extend this to: "code-read confirming every caller of createTemplate already handles exceptions."

---

### 🟡 AC5: 404 instead of 204 is a breaking API change

**Issue:** Changing HTTP status from `204 No Content` to `404 Not Found` is a **breaking change** to the API contract. The AC requires a "caller sweep" but assumes all callers are in the codebase.

**Risk:** If any external clients (mobile app, third-party integrations) are calling this endpoint and expecting `204`, they will break. They may have special-case logic for "204 = no active tier" and will interpret `404` as a real error.

**Mitigation:** The AC's mitigation is solid:
- Grep `src/frontend/src/api/*.api.js` and callers → confirmed in Dev Agent Record
- Note: if *no* frontend caller exists yet, this is a non-issue (new endpoint behavior)
- If external clients exist, this needs a deprecation period or explicit sign-off

**Note:** The project-owner decision `2026-09-08: 404, not 200+null` is explicit, so this is authorized. Just needs to be **recorded in the Dev Agent Record** as "breaking change — all known callers verified in codebase."

---

## Behavior/Logic Assumptions to Watch

### AC1: Exception-swallowing scope is broad

**Issue:** The AC swallows `catch (RuntimeException e)` in `BookingBatchStatusListener.onBookingStatusChanged`. This catches:
- `IllegalArgumentException` from `findById(null)` ✓ (intended)
- `PessimisticLockingFailureException` from batch update ✓ (intended)
- Any other `RuntimeException` from the listener itself (e.g., if there's a bug in the listener code)

**Risk:** A bug in the listener (division by zero, NPE in a new helper method, etc.) will be silently swallowed as "batch status is now stale" instead of surfacing the real error.

**Mitigation:** This is acceptable **if and only if**:
1. The listener is short and simple (it is — just one service call)
2. The ERROR log will surface it to monitoring (AC notes it logs ERROR)
3. The self-healing assumption (stale batch status recovers on next status change) is documented

The AC does this well — the comment cites `deferred-101 AC1` and documents the self-healing. **No change needed**, but the Dev Agent Record should note: "RuntimeException catch is intentional; listener has only one external call, and batch status self-heals."

---

### AC2: Option A's return-boolean shape is good, but verify all callers

**Issue:** Changing `reconcileToReady` to return `boolean` is a clean API change, but it requires **all callers** to be updated.

**Risk:** If a caller is missed (e.g., a private helper method somewhere), it will silently not update the incident row, and the bug persists.

**Mitigation:** The AC says "Update the two callers (`ReconciliationWorkerScheduler` here + grep for `AdminVideoService` / any other)". The grep is mandatory. The Dev Agent Record must list all callers found and the changes made to each.

---

### AC3: Orphan asset tracking assumes the sweeper runs

**Issue:** The AC records `priorAssetId` for the sweeper but doesn't address: what if the sweeper is broken or disabled?

**Risk:** `pending_provider_asset` rows accumulate forever, billing continues.

**Mitigation:** This is acceptable because:
1. The sweeper is a separate component with its own observability
2. Stale tracking rows don't break correctness (they just don't get cleaned up)
3. This is no worse than the current state (asset is leaked with no tracking row at all)

**No change needed**, but the AC is relying on the sweeper being operational. The Dev Agent Record should note: "Relies on `sweepOrphanedProviderAssets` scheduler running; alert on broken sweeper is a separate concern."

---

### AC6: `pg_terminate_backend` is safe but assumes single script invocation

**Issue:** The termination SQL uses `pid <> pg_backend_pid()` to spare the script's own connection. This works correctly for a single invocation.

**Risk:** If two `restore-from-dump.sh` invocations run concurrently on the same host, they will terminate each other's connections (unless in separate containers, which `docker exec` provides).

**Mitigation:** The script is used inside a container (`:108` `docker compose ... stop app`), so concurrent invocations are not possible. The lock-in-time assumption is fine.

**No change needed.**

---

### AC9: Three options for test isolation — is the guidance clear enough?

**Issue:** The AC offers three options for `ConfigGuardIT`:
1. `try/finally` wrapping the mutation
2. `@AfterAll` static restore net
3. Stop mutating shared state at all (preferred but "only if small")

The AC says "dev's call" but "Best" is option 3. This creates ambiguity: **when is option 3 "genuinely small"?**

**Mitigation:** The AC adds a pragmatic caveat: "Only do this if it's genuinely small; otherwise the try/finally is the pragmatic fix." This is reasonable because:
- The test has one mutation point and one clear value to restore
- `try/finally` is 2 lines of code
- The dev can judge "small" in context

**Dev guidance:** Pick option 1 (try/finally) unless option 3 is obviously smaller (e.g., the test can be rewritten in <5 minutes without logic changes).

---

## False Positives / Over-Scoping

**None detected.** The story respects its scope boundaries:

✓ AC12 is explicitly "docs + ledger only — NO migration code"  
✓ "Not in Scope" section blocks re-litigation of `deferred-91` AFTER_COMMIT items outside `CancellationRefundService`  
✓ AC4 footnote explicitly excludes `packSessionService.restoreSession` idempotency  
✓ AC8 excludes the `maxlength="200"` client-side UI fix (separate rationale)  
✓ AC13 says "do NOT scope-creep into fixing" the re-verified deploy-* items

---

## Missed Flows / Corner Cases

### AC1: What if the listener is invoked concurrently?

**Status:** ✓ Handled. `@Transactional(REQUIRES_NEW)` gives each invocation its own transaction. Concurrent invocations do not block each other. **No issue.**

### AC4: What if the business transaction rolls back after the listener enqueues?

**Status:** ✓ Handled. The AC tests this: "force the business transaction to roll back **after** the listener would have enqueued → assert **zero** `outbox` rows." **No issue.**

### AC5: What if a coach has no active tier? (current behavior)

**Status:** ✓ Addressed. `sessionPackPaymentService.getActiveCoachTier(coachId)` returns `null` for "no active tier" — this is verified in the AC. The fix translates null → `404`. **No issue.**

### AC10: Is one representative 401 IT enough?

**Status:** ✓ Acknowledged. The AC explicitly says "the fix is *representative* coverage, not exhaustive." This is a documented tradeoff. **No issue.**

---

## Ledger Consistency Check

The story closes these ledger bullets:
- deferred-100 code review (AC1, AC2, AC3, AC11)
- skillars-10-2 code review D1 (AC4)
- Group 3 deferred D11 (AC5)
- deploy-3-4 code review (AC6)
- deploy-1-3 code review (AC7)
- skillars-4-5 Round 2 code review W5 (AC8)
- skillars-deferred-1 D1, D2 (AC9, AC10)
- Six migration-lock bullets (AC12)
- skillars-3-1 code review + deploy-2-2 Fail workflow (AC13)

**Verification:** All AC **Ledger** lines are present and match the deletions in AC13. Cross-referenced entries look correct.

✓ Ledger consistency is good.

---

## Test Coverage Assessment

| AC | Test Type | Coverage | Risk |
|---|---|---|---|
| AC1 | Unit | null-id, exception isolation (2 mutations) | Low — simple happy path + failure branch |
| AC2 | IT | concurrent already-READY (1 mutation) | Low — one clear race condition |
| AC3 | IT | retry → tracking row → sweeper (1 mutation) | Low — end-to-end flow |
| AC4 | IT | rollback atomicity, happy path (2 mutations) | **Medium** — requires verifying strike-ordering first |
| AC5 | IT | 404 + error body, 200 unchanged (1 mutation) | Low — simple status-code change |
| AC6 | Shell | bash -n, manual trace | N/A — deterministic; no edge cases |
| AC7 | Compose | config parse | N/A — purely syntactic |
| AC8 | Code-read | 5 actions match (0 mutations) | Low — already tested by siblings |
| AC9 | Logic | restore-on-failure (1 reasoning check) | **Medium** — depends on test-ordering isolation |
| AC10 | Unit + IT | boundary values, real 401 (2 mutations) | Low — representative coverage noted |
| AC11 | Unit | version bump + allow-list move | **Medium** — depends on concurrent-writer testing |
| AC12 | Docs | migration-conventions.md sections | N/A — no code |
| AC13 | Ledger | line-for-line reconstruction | N/A — mechanical cleanup |

**Medium-risk items:** AC4 (strike ordering), AC9 (test isolation assumption), AC11 (concurrent writers during version bump). All are **acceptable with verification**, which the AC requires.

---

## Recommendations for Implementation

1. **Before coding AC4:** Explicitly verify the `onBookingCancelledByAdmin` strike-ordering in the Dev Agent Record. If it depends on AFTER_COMMIT semantics, this listener stays put.

2. **Before merging AC8:** Grep all `sessionStore.createTemplate` callers and add a checklist to the Dev Agent Record confirming each caller has error handling.

3. **Document in Dev Agent Record (AC5):** Record that the 204→404 change is authorized by project-owner decision 2026-09-08 and that all known frontend callers have been verified in-codebase.

4. **AC9:** Pick `try/finally` unless you have a genuinely small rewrite (< 5 min). Document your choice in the Dev Agent Record.

5. **AC11:** Test the version bump with `AuthResourceIT`, `AuthServiceIT`, and the GDPR erasure IT. If `OptimisticLockException` occurs, fall back to the allow-list + guard-test approach documented in the AC.

---

## No Blocking Issues

The story is **ready to implement**. All critical assumptions are documented, all edge cases are either handled or explicitly out-of-scope, and the test discipline is sound.

The **three items flagged as Critical** (AC4 strike ordering, AC8 caller impact, AC5 breaking change) are not blockers — they just require explicit verification before shipping, which the AC structure supports.

---

## Overall Assessment

- **Correctness:** ✓ No logical errors detected
- **Completeness:** ✓ All flows addressed or explicitly out-of-scope
- **Scope:** ✓ Well-bounded, no over-scoping
- **Test Design:** ✓ Mutations verify the fix in both directions
- **Risk:** ⚠️ Low, with three documented assumptions requiring pre-implementation verification
- **Ledger Discipline:** ✓ Clean and consistent

**Verdict:** Ready to implement. Flag the three critical assumptions in the Dev Agent Record and proceed.
